package com.okotu.aihuman.npc;

import com.okotu.aihuman.AiHumanPlugin;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Picks where an autonomous NPC should go next and hands the result to
 * Citizens' own Navigator - this plugin decides destinations, Citizens does
 * the actual pathfinding/walking/obstacle-avoidance, exactly once per
 * decision rather than once per step.
 *
 * <p>Must be called from the main thread (touches world blocks via
 * {@link NpcWorldSafety} and the NPC's Navigator).
 */
public class NpcMovementController {

    private final AiHumanPlugin plugin;

    public NpcMovementController(AiHumanPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean debugEnabled() {
        return plugin != null && plugin.getPluginConfig().debugLogMovement;
    }

    private void debug(String message) {
        if (debugEnabled()) {
            plugin.getLogger().log(Level.INFO, "[AI-Human MOVE] " + message);
        }
    }

    /**
     * Picks a random point within [wanderMinDistance, wanderMaxDistance] of
     * {@code home}, never further than {@code wanderRadius} from it, and
     * only returns it if {@link NpcWorldSafety} accepts it - retrying up to
     * {@code destinationAttempts} times. Empty if nothing safe was found
     * this cycle (the caller should just try again next interval).
     *
     * <p>{@code wanderMinDistance}/{@code wanderMaxDistance} are clamped to
     * {@code wanderRadius} before picking: if an admin sets, say, radius=200
     * with minDistance=500, every single candidate would otherwise be
     * guaranteed to land outside the radius and get rejected - a
     * configuration that looks "on" but makes the NPC never move at all,
     * forever, with nothing in the logs to explain why. Clamping means the
     * NPC always has a chance to find a valid destination; {@code /aihuman wander}
     * separately warns when it sees a combination like this, since it's
     * almost always a mistake (radius should be &gt;= maxDistance).
     *
     * <p>1.17+: also rejects a candidate whose ground elevation differs from
     * home's by more than {@code maxElevationChange} - the usual cause of an
     * NPC picking a destination up (or down) a mountain and getting stuck
     * partway - and, once a safe candidate is found, nudges it onto a nearby
     * path block if {@code preferPaths} is on and one exists within
     * {@code pathSearchRadius} (re-validated with {@link NpcWorldSafety}
     * before being used, so a nearby-but-unsafe path block is never picked
     * over a safe plain destination).
     */
    public Optional<Location> pickDestination(Location home, NpcBehaviorSettings settings) {
        World world = home.getWorld();
        if (world == null) {
            return Optional.empty();
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        double effectiveMax = Math.min(settings.wanderMaxDistance(), settings.wanderRadius());
        double effectiveMin = Math.min(settings.wanderMinDistance(), effectiveMax);
        double span = Math.max(1, effectiveMax - effectiveMin);
        boolean debug = debugEnabled();
        Map<String, Integer> rejectionCounts = debug ? new LinkedHashMap<>() : null;

        for (int attempt = 0; attempt < settings.destinationAttempts(); attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = effectiveMin + random.nextDouble() * span;
            double x = home.getX() + Math.cos(angle) * distance;
            double z = home.getZ() + Math.sin(angle) * distance;

            int blockX = (int) Math.floor(x);
            int blockZ = (int) Math.floor(z);
            // 1.19+: MOTION_BLOCKING_NO_LEAVES instead of the default heightmap -
            // the default (MOTION_BLOCKING) treats leaves as ground, so in any
            // forested/hilly area a huge share of candidates were landing on top
            // of tree canopies: rejected as a false "elevation change" against
            // home, or, when accepted, placed on an unreachable point above the
            // real ground that Citizens' Navigator could never actually path to
            // (looking exactly like "wanders locally, then gets stuck"). Ignoring
            // leaves gives the real terrain surface instead.
            int surfaceY = world.getHighestBlockYAt(blockX, blockZ, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            int elevationDelta = surfaceY - home.getBlockY();

            if (Math.abs(elevationDelta) > settings.maxElevationChange()) {
                // too big a climb/descent from home - likely a mountain/ravine, skip it
                if (debug) {
                    rejectionCounts.merge("elevation(" + elevationDelta + ")", 1, Integer::sum);
                }
                continue;
            }

            Location candidate = new Location(world, x, surfaceY + 1, z);

            String reason = NpcWorldSafety.rejectionReason(candidate, settings);
            if (reason == null) {
                if (settings.preferPaths()) {
                    Location snapped = snapToNearbyPath(candidate, settings.pathSearchRadius());
                    if (snapped != null && NpcWorldSafety.isSafe(snapped, settings)) {
                        debug("pickDestination: attempt " + (attempt + 1) + "/" + settings.destinationAttempts()
                                + " chose " + describe(snapped) + " (snapped to path near " + describe(candidate) + ")"
                                + " home=" + describe(home) + " range=[" + effectiveMin + "," + effectiveMax + "]");
                        return Optional.of(snapped);
                    }
                }
                debug("pickDestination: attempt " + (attempt + 1) + "/" + settings.destinationAttempts()
                        + " chose " + describe(candidate) + " home=" + describe(home)
                        + " range=[" + effectiveMin + "," + effectiveMax + "]");
                return Optional.of(candidate);
            }
            if (debug) {
                rejectionCounts.merge(reason, 1, Integer::sum);
            }
        }
        if (debug) {
            debug("pickDestination: no safe destination found after " + settings.destinationAttempts()
                    + " attempts around home=" + describe(home) + " range=[" + effectiveMin + "," + effectiveMax
                    + "] maxElevationChange=" + settings.maxElevationChange() + " - rejections: " + rejectionCounts
                    + (rejectionCounts.keySet().stream().anyMatch(k -> k.startsWith("elevation"))
                        ? " (mostly elevation rejections usually means max-elevation-change is too strict for this"
                          + " terrain, or the wander radius reaches into hillier terrain than home sits on)"
                        : ""));
        }
        return Optional.empty();
    }

    private static String describe(Location location) {
        if (location == null || location.getWorld() == null) {
            return "null";
        }
        return location.getWorld().getName() + "@(" + location.getBlockX() + "," + location.getBlockY() + ","
                + location.getBlockZ() + ")";
    }

    /** Starts (or redirects) the NPC's Navigator toward {@code destination}. */
    public void navigateTo(NPC npc, Location destination, double walkSpeed) {
        Location current = npc.isSpawned() ? npc.getEntity().getLocation() : null;
        boolean sameWorld = current != null && current.getWorld() != null
                && current.getWorld().equals(destination.getWorld());
        if (!sameWorld && plugin != null) {
            // Citizens' Navigator cannot path across worlds - setTarget below will
            // silently do nothing observable (no exception, Navigator just never
            // reports progress), which looks exactly like "the NPC doesn't move at
            // all". Always worth a warning regardless of debug.log-movement, since
            // this is the single most likely explanation for a TRAVEL-behavior NPC
            // that never moves: its stored waypoint's world doesn't match the world
            // the NPC actually spawned in (e.g. a waypoint added from console with a
            // mistyped world name, or a world that was later renamed/removed).
            plugin.getLogger().log(Level.WARNING, "[AI-Human MOVE] NPC " + npc.getId()
                    + ": destination " + describe(destination) + " is in a different world than the NPC's "
                    + "current position " + describe(current) + " - Citizens cannot path across worlds, so this "
                    + "NPC will not move toward it. Check its home/waypoint world name.");
        }
        if (debugEnabled()) {
            debug("navigateTo: NPC " + npc.getId() + " from " + describe(current) + " to "
                    + describe(destination) + " speed=" + walkSpeed);
        }
        npc.getNavigator().getLocalParameters().speedModifier((float) walkSpeed);
        npc.getNavigator().setTarget(destination);
    }

    /**
     * Looks for the nearest vanilla path block within {@code radius} blocks
     * (a square scan, cheap and bounded) of {@code candidate} and returns a
     * point on top of it, or null if none was found close enough. Only
     * checks {@link Material#DIRT_PATH} - the vanilla "grass path" block
     * (village paths, and whatever players make with a shovel); doesn't
     * attempt to recognize arbitrary player-built roads out of other
     * materials, since there's no reliable way to tell a road from a floor
     * or a wall from block type alone.
     */
    private Location snapToNearbyPath(Location candidate, int radius) {
        World world = candidate.getWorld();
        if (world == null) {
            return null;
        }
        int centerX = candidate.getBlockX();
        int centerZ = candidate.getBlockZ();

        Block closest = null;
        int closestDistanceSquared = Integer.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = centerX + dx;
                int z = centerZ + dz;
                int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Block block = world.getBlockAt(x, y, z);
                if (block.getType() == Material.DIRT_PATH) {
                    int distanceSquared = dx * dx + dz * dz;
                    if (distanceSquared < closestDistanceSquared) {
                        closestDistanceSquared = distanceSquared;
                        closest = block;
                    }
                }
            }
        }

        if (closest == null) {
            return null;
        }
        return new Location(world, closest.getX() + 0.5, closest.getY() + 1, closest.getZ() + 0.5);
    }
}
