package com.okotu.npcai.npc;

import com.okotu.npcai.OkotuNpcAiPlugin;
import com.okotu.npcai.util.RateLimiter;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Optional;
import java.util.logging.Level;

/**
 * Bridge between Citizens and the conversation engine. Two ways in:
 * 1) right-click on an NPC (if {@code interaction.right-click.enabled}) -
 *    opens a conversation session via {@link ConversationSessionManager};
 * 2) proximity - see {@link ProximityGreetingTask}, which opens sessions the
 *    same way when an NPC notices a player getting close and greets first.
 *
 * Either way, once a session is open for a player, their next chat message
 * is intercepted here (not broadcast) and sent to the AI engine, and the
 * reply reaches them as a message from the NPC.
 *
 * <p><b>Listens for chat on both the legacy {@link AsyncPlayerChatEvent} and
 * Paper's newer {@link AsyncChatEvent}.</b> Which one actually fires depends
 * on the server/other plugins (Paper reworked chat around 1.19.1; some
 * setups no longer fire the legacy event at all, especially with other chat
 * plugins installed) - listening to only one risks silently never seeing the
 * player's reply (the AI request never even gets sent). Both handlers funnel
 * into {@link #handleIncomingMessage}, which is itself safe to call twice
 * for the "same" message: the first call clears the session via
 * {@link ConversationSessionManager#clear}, so if both events happen to fire
 * for one real chat message, the second call just finds no active session
 * and no-ops - no duplicate request to Ollama.
 */
public class NpcBridgeListener implements Listener {

    private final OkotuNpcAiPlugin plugin;
    private final RateLimiter rateLimiter;

    public NpcBridgeListener(OkotuNpcAiPlugin plugin, RateLimiter rateLimiter) {
        this.plugin = plugin;
        this.rateLimiter = rateLimiter;
    }

    @EventHandler
    public void onNpcRightClick(NPCRightClickEvent event) {
        if (!plugin.getPluginConfig().rightClickTriggerEnabled) {
            return;
        }

        NPC npc = event.getNPC();

        if (!plugin.getEnabledNpcRegistry().isEnabled(npc.getId())) {
            return; // this NPC hasn't been AI-enabled - leave the click alone for other plugins
        }

        Player player = event.getClicker();

        plugin.getConversationSessionManager().start(player.getUniqueId(), npc.getId(), npc.getName());

        player.sendMessage(ChatColor.GRAY + "[" + npc.getName() + ChatColor.GRAY
                + "] Type in chat to talk to them. The conversation expires after a bit of inactivity.");
    }

    /** Legacy Bukkit/Spigot chat event - still what fires on many setups. */
    @EventHandler(priority = EventPriority.LOW)
    public void onLegacyPlayerChat(AsyncPlayerChatEvent event) {
        boolean handled = handleIncomingMessage(event.getPlayer(), event.getMessage());
        if (handled) {
            event.setCancelled(true);
        }
    }

    /** Paper's newer, Adventure-Component-based chat event. */
    @EventHandler(priority = EventPriority.LOW)
    public void onAsyncChat(AsyncChatEvent event) {
        String plainMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        boolean handled = handleIncomingMessage(event.getPlayer(), plainMessage);
        if (handled) {
            event.setCancelled(true);
        }
    }

    /**
     * @return true if this message was consumed as a reply to an NPC (caller must cancel its event),
     *         false if the player has no open conversation and the message should go through normally.
     */
    private boolean handleIncomingMessage(Player player, String message) {
        Optional<ConversationSessionManager.ActiveConversation> active =
                plugin.getConversationSessionManager().get(player.getUniqueId());
        if (active.isEmpty()) {
            return false;
        }

        // Consume the session immediately so a second event for the same physical
        // message (legacy + Paper both firing) is a no-op instead of a duplicate request.
        plugin.getConversationSessionManager().clear(player.getUniqueId());

        if (!rateLimiter.tryAcquire(player.getUniqueId())) {
            long remaining = rateLimiter.remainingCooldownMs(player.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(
                    ChatColor.RED + "Wait " + (remaining / 1000.0) + "s before talking to an NPC again."));
            return true;
        }

        int npcId = active.get().npcId();
        String npcName = active.get().npcName();

        plugin.getConversationService().handlePlayerMessage(npcId, npcName, player.getName(), player.getUniqueId(), message)
                .whenComplete((reply, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (throwable != null) {
                        plugin.getLogger().log(Level.WARNING,
                                "Unexpected error in the conversation flow for NPC " + npcId, throwable);
                        player.sendMessage(ChatColor.RED + "The NPC can't answer you right now.");
                        return;
                    }
                    player.sendMessage(ChatColor.GOLD + "[" + npcName + ChatColor.GOLD + "] "
                            + ChatColor.WHITE + reply);

                    NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
                    if (npc != null && npc.isSpawned()) {
                        plugin.getNpcDialogRenderer().show(npc, reply);
                    }
                }));
        return true;
    }
}
