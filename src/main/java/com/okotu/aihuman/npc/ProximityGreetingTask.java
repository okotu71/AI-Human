package com.okotu.aihuman.npc;

import com.okotu.aihuman.AiHumanPlugin;
import com.okotu.aihuman.config.PluginConfig;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Runs on the main thread every {@code interaction.proximity.check-interval-ticks}
 * and, for every spawned Citizens NPC, looks for online players within
 * {@code interaction.proximity.radius} blocks. The first time a given
 * (npc, player) pair gets close enough (subject to a per-pair cooldown so
 * standing next to an NPC doesn't repeatedly re-trigger it), the NPC opens a
 * conversation session and greets the player first via
 * {@link com.okotu.aihuman.service.ConversationService#greetApproachingPlayer}.
 *
 * This exists specifically as an alternative to right-click, for servers
 * where another plugin hijacks right-clicking an NPC/entity (e.g. a "ride on
 * shoulders" plugin) before Citizens/this plugin ever sees the interaction -
 * see {@code interaction.right-click.enabled} in config.yml to turn
 * right-click off entirely once proximity is working for you.
 */
public class ProximityGreetingTask implements Runnable {

    private final AiHumanPlugin plugin;

    // "npcId:playerUuid" -> last time this pair was greeted (millis)
    private final Map<String, Long> lastGreetAt = new ConcurrentHashMap<>();

    public ProximityGreetingTask(AiHumanPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        PluginConfig config = plugin.getPluginConfig();
        if (!config.proximityTriggerEnabled) {
            return;
        }

        ConversationSessionManager sessionManager = plugin.getConversationSessionManager();
        double radiusSquared = config.proximityRadius * config.proximityRadius;

        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npc.isSpawned()) {
                continue;
            }
            if (!plugin.getEnabledNpcRegistry().isEnabled(npc.getId())) {
                continue; // this NPC hasn't been AI-enabled - skip it entirely, don't even check distances
            }
            Location npcLocation = npc.getEntity().getLocation();
            if (npcLocation.getWorld() == null) {
                continue;
            }

            for (Player player : npcLocation.getWorld().getPlayers()) {
                if (!player.hasPermission("aihuman.talk")) {
                    continue;
                }
                if (sessionManager.isActive(player.getUniqueId())) {
                    continue; // already talking to this NPC or another one
                }
                if (npcLocation.distanceSquared(player.getLocation()) > radiusSquared) {
                    continue;
                }
                if (isOnCooldown(npc.getId(), player.getUniqueId(), config)) {
                    continue;
                }

                markGreeted(npc.getId(), player.getUniqueId());
                greet(npc, player, sessionManager);
            }
        }
    }

    private boolean isOnCooldown(int npcId, UUID playerUuid, PluginConfig config) {
        Long last = lastGreetAt.get(key(npcId, playerUuid));
        return last != null && System.currentTimeMillis() - last < config.proximityGreetCooldownMs;
    }

    private void markGreeted(int npcId, UUID playerUuid) {
        lastGreetAt.put(key(npcId, playerUuid), System.currentTimeMillis());
    }

    private String key(int npcId, UUID playerUuid) {
        return npcId + ":" + playerUuid;
    }

    private void greet(NPC npc, Player player, ConversationSessionManager sessionManager) {
        int npcId = npc.getId();
        String npcName = npc.getName();

        // Open the session immediately (main thread, no race with the player
        // typing while the AI call is still in flight) - even if the greeting
        // call below fails, the player can still just start typing to the NPC.
        sessionManager.start(player.getUniqueId(), npcId, npcName);

        plugin.getConversationService().greetApproachingPlayer(npcId, npcName, player.getName(), player.getUniqueId())
                .whenComplete((greeting, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    // Restart the chat-capture window now that the player actually has
                    // something to react to. The Ollama round-trip for the greeting can
                    // take a few seconds (a cold model load after keep-alive expired is
                    // the usual culprit) - if the countdown had kept running from the
                    // moment we first noticed the player, a slow greeting could eat most
                    // or all of interaction.chat-capture-timeout-seconds before they even
                    // saw the message, silently dropping their reply. Refreshing it here
                    // (success or failure) means the timeout always starts counting from
                    // when the player had a chance to respond, not from when we noticed them.
                    //
                    // Guarded by isActiveWith: if the player already replied while the
                    // greeting was still in flight, handleIncomingMessage() already
                    // consumed and cleared the session - restarting it here regardless
                    // would silently reopen "listening" mode for whatever the player
                    // types next, even if it's unrelated to this NPC. Only refresh if
                    // this is still the same, not-yet-consumed session.
                    if (sessionManager.isActiveWith(player.getUniqueId(), npcId)) {
                        sessionManager.start(player.getUniqueId(), npcId, npcName);
                    }

                    if (throwable != null) {
                        plugin.getLogger().log(Level.FINE,
                                "Proximity greeting failed for NPC " + npcId + " (skipping silently)", throwable);
                        return;
                    }
                    player.sendMessage(ChatColor.GOLD + "[" + npcName + ChatColor.GOLD + "] "
                            + ChatColor.WHITE + greeting);
                    plugin.getNpcDialogRenderer().show(npc, greeting);
                }));
    }
}
