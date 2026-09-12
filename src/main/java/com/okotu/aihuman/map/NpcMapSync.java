package com.okotu.aihuman.map;

import com.okotu.aihuman.AiHumanPlugin;
import com.okotu.aihuman.config.PluginConfig;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Periodically draws every AI-enabled Citizens NPC as a small square on a
 * dedicated Pl3xMap layer ("AI NPCs") - mirrors mc-safeguard's
 * ClaimSyncService/ZoneMapSync, simplified since NPC counts are typically
 * small enough to just re-upsert every enabled NPC each cycle on the main
 * thread, without needing an async diff-by-content-hash step.
 *
 * <p>Runs on the MAIN thread (reads live Citizens/Bukkit entity locations).
 * Not tied to any single trigger event: an NPC's marker gets removed the
 * cycle after it stops qualifying (disabled, despawned, or removed
 * entirely), same "diff against the previous snapshot" idea as claims use,
 * just keyed on NPC id + world instead of a content hash - a moving NPC's
 * position differs virtually every cycle anyway, so there's little to gain
 * from a stricter change-detection step here.
 */
public class NpcMapSync implements Runnable {

    private static final String LAYER_KEY = "okotu-npc-ai";
    private static final String LAYER_LABEL = "AI NPCs";

    private final AiHumanPlugin plugin;
    private final Pl3xMapIntegration mapIntegration;
    private final NpcMapStyle style;

    /** npcId -> world it was last drawn in, so a marker can be removed even after the NPC itself is gone. */
    private final Map<Integer, String> lastDrawnWorldByNpcId = new HashMap<>();

    public NpcMapSync(AiHumanPlugin plugin, Pl3xMapIntegration mapIntegration, NpcMapStyle style) {
        this.plugin = plugin;
        this.mapIntegration = mapIntegration;
        this.style = style;
    }

    @Override
    public void run() {
        PluginConfig config = plugin.getPluginConfig();
        if (!config.pl3xMapEnabled || !config.npcMapEnabled || !mapIntegration.isPresent()) {
            return;
        }

        Map<Integer, String> currentWorldByNpcId = new HashMap<>();
        double half = config.npcMapMarkerSize / 2.0;

        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.isSpawned()) {
                continue;
            }
            if (!plugin.getEnabledNpcRegistry().isEnabled(npc.getId())) {
                continue;
            }

            Location loc = npc.getEntity().getLocation();
            World world = loc.getWorld();
            if (world == null) {
                continue;
            }

            boolean autonomous = plugin.getAutonomousNpcRegistry().isAutonomous(npc.getId());
            Pl3xMapIntegration.PolygonStyle markerStyle = style.forNpc(npc.getName(), autonomous);
            String markerId = markerId(npc.getId());

            try {
                boolean drawn = mapIntegration.upsertRectangle(LAYER_KEY, LAYER_LABEL, world.getName(), markerId,
                        loc.getX() - half, loc.getZ() - half, loc.getX() + half, loc.getZ() + half, markerStyle);
                if (drawn) {
                    currentWorldByNpcId.put(npc.getId(), world.getName());
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to update map marker for NPC " + npc.getId(), e);
            }
        }

        // Anything drawn last cycle but not this one (disabled, despawned, or removed) gets cleaned up.
        for (Map.Entry<Integer, String> stale : lastDrawnWorldByNpcId.entrySet()) {
            if (!currentWorldByNpcId.containsKey(stale.getKey())) {
                mapIntegration.removeMarker(LAYER_KEY, stale.getValue(), markerId(stale.getKey()));
            }
        }

        lastDrawnWorldByNpcId.clear();
        lastDrawnWorldByNpcId.putAll(currentWorldByNpcId);
    }

    private String markerId(int npcId) {
        return "okotu-npc-" + npcId;
    }
}
