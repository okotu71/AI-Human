package com.okotu.npcai.map;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.awt.Color;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Generic Pl3xMap addon bridge: draws rectangular polygons with a
 * border/fill/tooltip on any number of independent, named layers, one
 * set of layer instances per world.
 *
 * <p>Adapted from mc-safeguard's {@code map.Pl3xMapIntegration} (see that
 * project's 2.0.11-2.0.16 patch notes for the full history of what was
 * tried and why) - reused here as-is rather than re-derived, since it's
 * already confirmed working against a real Pl3xMap install and is
 * completely generic (it only ever takes a layer key/label, a world, a
 * marker id, a bounding box, and a style - nothing claims/zone-specific).
 * okotu-npc-ai-engine uses it to draw a small square marker per AI-enabled
 * NPC instead of a claim/zone rectangle - see {@code NpcMapSync}.
 *
 * <p>WHY REFLECTION: there is no way to verify Pl3xMap's exact API/version/
 * coordinates from this environment, so this talks to Pl3xMap purely
 * through java.lang.reflect, resolved once and cached. Every failure is
 * logged with its real cause; with debug logging enabled it also dumps the
 * real public methods of whatever class it couldn't use correctly, so a
 * mismatch on a different Pl3xMap version is diagnosable from the console
 * alone rather than requiring a code change to even find out why.
 *
 * <p>Every shape this draws is an axis-aligned rectangle, drawn via the
 * confirmed-live {@code Marker.rectangle(String key, double minX, double
 * minZ, double maxX, double maxZ)} - no Point/Polyline/array plumbing.
 */
public class Pl3xMapIntegration {

    private static final String PL3XMAP_CLASS = "net.pl3x.map.core.Pl3xMap";
    private static final String SIMPLE_LAYER_CLASS = "net.pl3x.map.core.markers.layer.SimpleLayer";
    private static final String LAYER_CLASS = "net.pl3x.map.core.markers.layer.Layer";
    private static final String MARKER_CLASS = "net.pl3x.map.core.markers.marker.Marker";
    private static final String OPTIONS_CLASS = "net.pl3x.map.core.markers.option.Options";

    private final Logger logger;
    private final boolean debug;
    private final Plugin pl3xMapPlugin;
    private final String logPrefix;

    /** Keyed by "<layerKey>|<world>" -> the live Pl3xMap layer object. */
    private final Map<String, Object> layersByKeyAndWorld = new ConcurrentHashMap<>();

    private volatile boolean broken = false;
    private volatile boolean initLogged = false;
    private volatile boolean addMarkerFailureLogged = false;
    private volatile boolean optionsBuilderDumped = false;

    // Resolved once, lazily, on first real use.
    private Object api;
    private Class<?> markerClass;
    private Class<?> layerInterfaceClass;
    private Class<?> optionsClass;
    private Method apiWorldRegistryMethod;
    private Method worldRegistryGetMethod;
    private Method worldLayerRegistryMethod;
    private Method layerRegistryRegisterMethod;
    private Method markerRectangleMethod;
    private Method markerSetOptionsMethod;
    private Method optionsBuilderMethod;
    private Method optionsBuildMethod;
    private Constructor<?> simpleLayerConstructor;

    public Pl3xMapIntegration(Logger logger, boolean debug, String logPrefix) {
        this.logger = logger;
        this.debug = debug;
        this.logPrefix = logPrefix;
        this.pl3xMapPlugin = Bukkit.getPluginManager().getPlugin("Pl3xMap");
    }

    public boolean isPresent() {
        return pl3xMapPlugin != null && pl3xMapPlugin.isEnabled() && !broken && ensureResolved();
    }

    /**
     * Draws or updates a rectangular polygon on the given layer
     * (created on demand, per world) from its min/max X/Z bounds.
     *
     * @return true if the marker was actually added to a Pl3xMap layer.
     */
    public boolean upsertRectangle(String layerKey, String layerLabel, String world, String markerId,
                                    double minX, double minZ, double maxX, double maxZ, PolygonStyle style) {
        if (!isPresent()) {
            if (debug) {
                logger.info(logPrefix + " upsert(" + markerId + ") skipped: isPresent()=false "
                        + "(pl3xMapPlugin=" + (pl3xMapPlugin != null) + ", broken=" + broken + ")");
            }
            return false;
        }

        try {
            Object layer = layerFor(layerKey, layerLabel, world);
            if (layer == null) {
                if (debug) {
                    logger.info(logPrefix + " upsert(" + markerId + ") skipped: Pl3xMap has no "
                            + "map registered for world '" + world + "'.");
                }
                return false;
            }

            Object marker = markerRectangleMethod.invoke(null, markerId, minX, minZ, maxX, maxZ);
            Object options = buildOptions(style);
            if (options != null) {
                tryInvoke(markerSetOptionsMethod, marker, options);
            }

            boolean added = addMarker(layer, marker, markerId);
            if (debug) {
                if (added) {
                    logger.info(String.format(
                            "%s Drew marker %s on layer '%s': world=%s bounds=(%.1f,%.1f)-(%.1f,%.1f)",
                            logPrefix, markerId, layerKey, world, minX, minZ, maxX, maxZ));
                } else {
                    logger.warning(logPrefix + " Built marker " + markerId
                            + " but could not find a way to add it to layer '" + layerKey
                            + "' (see addMarker warning above).");
                }
            }
            return added;
        } catch (Throwable t) {
            markBroken("draw marker " + markerId + " on layer '" + layerKey + "'", t);
            return false;
        }
    }

    public void removeMarker(String layerKey, String world, String markerId) {
        if (!isPresent()) return;
        try {
            Object layer = layersByKeyAndWorld.get(layerKey + "|" + world);
            if (layer == null) return;
            Method removeMarker = findMethod(layer.getClass(), "removeMarker", String.class);
            if (removeMarker != null) {
                removeMarker.invoke(layer, markerId);
            } else if (debug) {
                logger.warning(logPrefix + " Could not find removeMarker(String) on " + layer.getClass().getName());
            }
        } catch (Throwable t) {
            markBroken("remove marker " + markerId + " from layer '" + layerKey + "'", t);
        }
    }

    // ---- one-time reflective resolution -------------------------------

    private boolean ensureResolved() {
        if (api != null) return true;
        if (broken) return false;

        try {
            Class<?> pl3xMapClass = Class.forName(PL3XMAP_CLASS);
            Method apiMethod = pl3xMapClass.getMethod("api");
            Object apiInstance = apiMethod.invoke(null);
            if (apiInstance == null) {
                markBroken("locate the Pl3xMap API singleton", new IllegalStateException("api() returned null"));
                return false;
            }
            if (debug) {
                dumpPublicMethods("Pl3xMap API instance", apiInstance.getClass());
            }

            Method worldRegistryMethod = findMethod(apiInstance.getClass(), "getWorldRegistry");
            if (worldRegistryMethod == null) {
                markBroken("locate Pl3xMap#getWorldRegistry()", new NoSuchMethodException("getWorldRegistry"));
                return false;
            }
            Object worldRegistry = worldRegistryMethod.invoke(apiInstance);

            Method worldGetMethod = findMethod(worldRegistry.getClass(), "get", String.class);
            if (worldGetMethod == null) {
                if (debug) dumpPublicMethods("WorldRegistry", worldRegistry.getClass());
                markBroken("locate WorldRegistry#get(String)", new NoSuchMethodException("get(String)"));
                return false;
            }

            this.markerClass = Class.forName(MARKER_CLASS);
            this.layerInterfaceClass = Class.forName(LAYER_CLASS);
            this.optionsClass = Class.forName(OPTIONS_CLASS);
            this.simpleLayerConstructor = Class.forName(SIMPLE_LAYER_CLASS)
                    .getConstructor(String.class, Supplier.class);

            this.markerRectangleMethod = findExactMethod(markerClass, "rectangle",
                    String.class, double.class, double.class, double.class, double.class);
            this.markerSetOptionsMethod = findExactMethod(markerClass, "setOptions", optionsClass);
            this.optionsBuilderMethod = findExactMethod(optionsClass, "builder");
            Object probeBuilder = optionsBuilderMethod != null ? optionsBuilderMethod.invoke(null) : null;
            this.optionsBuildMethod = probeBuilder != null ? findExactMethod(probeBuilder.getClass(), "build") : null;

            if (markerRectangleMethod == null || markerSetOptionsMethod == null) {
                if (debug) dumpPublicMethods("Marker (static)", markerClass);
                markBroken("resolve Marker.rectangle(...)/setOptions(...)", new NoSuchMethodException("required member missing"));
                return false;
            }

            this.apiWorldRegistryMethod = worldRegistryMethod;
            this.worldRegistryGetMethod = worldGetMethod;
            this.api = apiInstance;

            if (!initLogged) {
                logger.info(logPrefix + " Pl3xMap API resolved via reflection (Marker.rectangle); "
                        + "map integration ready.");
                initLogged = true;
            }
            return true;
        } catch (Throwable t) {
            markBroken("resolve the Pl3xMap addon API", t);
            return false;
        }
    }

    private Object layerFor(String layerKey, String layerLabel, String worldName) throws ReflectiveOperationException {
        String cacheKey = layerKey + "|" + worldName;
        Object existing = layersByKeyAndWorld.get(cacheKey);
        if (existing != null) return existing;

        Object worldRegistry = apiWorldRegistryMethod.invoke(api);
        Object mapWorld = worldRegistryGetMethod.invoke(worldRegistry, worldName);
        if (mapWorld == null) {
            // Pl3xMap has no map for this world -> skip, no error.
            return null;
        }

        if (worldLayerRegistryMethod == null) {
            worldLayerRegistryMethod = findMethod(mapWorld.getClass(), "getLayerRegistry");
            if (worldLayerRegistryMethod == null && debug) {
                dumpPublicMethods("Pl3xMap World", mapWorld.getClass());
            }
        }
        Object layerRegistry = worldLayerRegistryMethod.invoke(mapWorld);

        if (layerRegistryRegisterMethod == null) {
            layerRegistryRegisterMethod = findMethod(layerRegistry.getClass(), "register", layerInterfaceClass);
            if (layerRegistryRegisterMethod == null && debug) {
                dumpPublicMethods("LayerRegistry", layerRegistry.getClass());
            }
        }

        Supplier<String> label = () -> layerLabel;
        Object layer = simpleLayerConstructor.newInstance(layerKey, label);
        layerRegistryRegisterMethod.invoke(layerRegistry, layer);
        layersByKeyAndWorld.put(cacheKey, layer);

        if (debug) {
            logger.info(logPrefix + " Registered Pl3xMap layer '" + layerKey + "' for world '" + worldName + "'.");
        }

        return layer;
    }

    private Object buildOptions(PolygonStyle style) {
        if (optionsBuilderMethod == null || optionsBuildMethod == null) {
            return null;
        }
        try {
            Object builder = optionsBuilderMethod.invoke(null);

            if (debug && !optionsBuilderDumped) {
                optionsBuilderDumped = true;
                dumpPublicMethods("Options.Builder", builder.getClass());
            }

            if (style.borderEnabled()) {
                tryInvoke(findMethod(builder.getClass(), "strokeColor", int.class), builder, style.borderColor().getRGB());
                tryInvoke(findMethod(builder.getClass(), "strokeWeight", int.class), builder, style.borderWidth());
            } else {
                tryInvoke(findMethod(builder.getClass(), "stroke", boolean.class), builder, false);
            }

            if (style.fillEnabled()) {
                tryInvoke(findMethod(builder.getClass(), "fillColor", int.class), builder,
                        colorWithAlpha(style.fillColor(), style.fillOpacity()));
            } else {
                tryInvoke(findMethod(builder.getClass(), "fill", boolean.class), builder, false);
            }

            if (style.tooltip() != null) {
                Method tooltip = findMethod(builder.getClass(), "tooltipContent", String.class);
                if (tooltip == null) {
                    tooltip = findMethod(builder.getClass(), "tooltip", String.class);
                }
                tryInvoke(tooltip, builder, style.tooltip());
            }

            return optionsBuildMethod.invoke(builder);
        } catch (Throwable t) {
            logger.fine(logPrefix + " Could not build Pl3xMap marker options: " + t);
            return null;
        }
    }

    /**
     * Packs a Color's RGB with an explicit alpha derived from an opacity
     * fraction (0.0-1.0) into a single ARGB int - this Pl3xMap version has
     * no separate fillOpacity()/strokeOpacity() setter (confirmed via the
     * Options.Builder method dump in mc-safeguard's testing); transparency
     * is controlled entirely by the alpha channel of the color integer
     * passed to fillColor(Integer)/strokeColor(Integer). Color#getRGB()
     * always returns alpha=0xFF (opaque) on its own.
     */
    private int colorWithAlpha(Color color, double opacity) {
        int alpha = (int) Math.round(Math.max(0, Math.min(1, opacity)) * 255);
        return (alpha << 24) | (color.getRGB() & 0x00FFFFFF);
    }

    private boolean addMarker(Object layer, Object marker, String markerId) throws ReflectiveOperationException {
        Method addMarkerSingle = findExactMethod(layer.getClass(), "addMarker", markerClass);
        if (addMarkerSingle != null) {
            addMarkerSingle.invoke(layer, marker);
            return true;
        }

        Method addMarkerKeyed = findExactMethod(layer.getClass(), "addMarker", String.class, markerClass);
        if (addMarkerKeyed != null) {
            addMarkerKeyed.invoke(layer, markerId, marker);
            return true;
        }

        if (!addMarkerFailureLogged) {
            addMarkerFailureLogged = true;
            logger.warning(logPrefix + " Could not find an addMarker(Marker) or addMarker(String, Marker) "
                    + "method on " + layer.getClass().getName() + ". Markers are being built correctly but "
                    + "cannot be added to the layer. Dumping its public methods below to find the real one:");
            dumpPublicMethods("Pl3xMap Layer", layer.getClass());
        }
        return false;
    }

    private void tryInvoke(Method method, Object target, Object arg) {
        if (method == null) return;
        try {
            method.invoke(target, arg);
        } catch (ReflectiveOperationException ignored) {
            // this particular style option isn't available on this Pl3xMap
            // version; skip it rather than failing the whole marker.
        }
    }

    /** Exact-signature lookup only -- never guesses a same-name/same-arity overload. */
    private Method findExactMethod(Class<?> type, String name, Class<?>... paramTypes) {
        try {
            return type.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * Used only for methods where Pl3xMap is very unlikely to have
     * multiple overloads sharing the name+arity (getWorldRegistry(),
     * getLayerRegistry(), register(Layer), removeMarker(String)).
     * Falls back to a name+arity match if the exact-type lookup fails.
     */
    private Method findMethod(Class<?> type, String name, Class<?>... paramTypes) {
        Method exact = findExactMethod(type, name, paramTypes);
        if (exact != null) return exact;
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramTypes.length) {
                return m;
            }
        }
        return null;
    }

    private void dumpPublicMethods(String label, Class<?> type) {
        StringBuilder sb = new StringBuilder(logPrefix + " Public methods on " + label
                + " (" + type.getName() + "):");
        for (Method m : type.getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            sb.append("\n  - ").append(m.getReturnType().getSimpleName()).append(' ')
                    .append(m.getName()).append('(');
            Class<?>[] params = m.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i].getSimpleName());
            }
            sb.append(')');
        }
        logger.info(sb.toString());
    }

    private void markBroken(String action, Throwable t) {
        broken = true;
        Throwable cause = (t instanceof InvocationTargetException ite && ite.getCause() != null) ? ite.getCause() : t;
        logger.warning(logPrefix + " Failed to " + action + " on Pl3xMap (API mismatch for your "
                + "Pl3xMap version?). Map integration disabled for this session: " + cause);
    }

    /** Plain style data for a single polygon: border/fill/tooltip, decoupled from any specific data source. */
    public record PolygonStyle(boolean borderEnabled, Color borderColor, int borderWidth,
                                boolean fillEnabled, Color fillColor, double fillOpacity,
                                String tooltip) {
    }
}
