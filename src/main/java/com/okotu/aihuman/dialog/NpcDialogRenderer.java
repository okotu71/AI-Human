package com.okotu.aihuman.dialog;

import com.okotu.aihuman.config.PluginConfig;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows an NPC's line as floating text above its head using Paper's native
 * {@link TextDisplay} entity (available since 1.19.4) - no hologram plugin
 * required. This is IN ADDITION to the normal chat message the player
 * already receives, not a replacement for it; gated by
 * {@code dialog.world-bubble.enabled} in config.yml.
 *
 * <p>Must be called from the main thread (spawns/moves/removes an entity).
 * Reuses one display entity per NPC (moved/re-texted on each call, not
 * re-spawned) and re-schedules its removal every time so a fast follow-up
 * line doesn't get cut off by an older, shorter-lived removal task.
 */
public class NpcDialogRenderer {

    private final Plugin plugin;
    private final PluginConfig config;
    private final Map<Integer, TextDisplay> displays = new ConcurrentHashMap<>();
    private final Map<Integer, BukkitTask> removalTasks = new ConcurrentHashMap<>();

    public NpcDialogRenderer(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void show(NPC npc, String text) {
        if (!config.worldBubbleEnabled || text == null || text.isBlank() || !npc.isSpawned()) {
            return;
        }

        Location above = npc.getEntity().getLocation().clone()
                .add(0, npc.getEntity().getHeight() + config.worldBubbleHeightOffset, 0);

        TextDisplay display = displays.get(npc.getId());
        if (display == null || display.isDead()) {
            display = above.getWorld().spawn(above, TextDisplay.class, d -> {
                d.setBillboard(Display.Billboard.CENTER);
                d.setSeeThrough(false);
                d.setShadowed(true);
                d.setPersistent(false);
                // Bukkit's Display#setViewRange is a scalar on the server's own entity
                // tracking range, not a literal block count - treated here as an
                // approximation (config value against a ~64-block baseline) rather than
                // an exact distance; tune view-range in config.yml empirically if needed.
                d.setViewRange((float) (config.worldBubbleViewRange / 64.0));
            });
            displays.put(npc.getId(), display);
        } else {
            display.teleport(above);
        }
        display.text(Component.text(text));

        BukkitTask previousRemoval = removalTasks.remove(npc.getId());
        if (previousRemoval != null) {
            previousRemoval.cancel();
        }
        int npcId = npc.getId();
        TextDisplay finalDisplay = display;
        BukkitTask removalTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!finalDisplay.isDead()) {
                finalDisplay.remove();
            }
            displays.remove(npcId, finalDisplay);
            removalTasks.remove(npcId);
        }, config.worldBubbleDurationTicks);
        removalTasks.put(npcId, removalTask);
    }

    /** Removes any bubble immediately (e.g. an NPC gets disabled/despawned). Safe to call even if none exists. */
    public void clear(int npcId) {
        BukkitTask task = removalTasks.remove(npcId);
        if (task != null) {
            task.cancel();
        }
        TextDisplay display = displays.remove(npcId);
        if (display != null && !display.isDead()) {
            display.remove();
        }
    }
}
