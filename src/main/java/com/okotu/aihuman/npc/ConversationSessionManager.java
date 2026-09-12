package com.okotu.aihuman.npc;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which NPC (if any) each player is currently "in conversation with" -
 * i.e. whose next chat message should be captured and sent to the AI engine
 * instead of being broadcast normally. Shared between whatever opens a
 * conversation (right-click via NpcBridgeListener, or proximity via
 * ProximityGreetingTask) and whatever consumes it (the chat listener).
 *
 * A session expires on its own after {@code timeoutMs} of inactivity -
 * {@link #get(UUID)} lazily removes and reports it as gone once expired,
 * so callers never need to run a separate cleanup sweep.
 */
public class ConversationSessionManager {

    private final Map<UUID, ActiveConversation> active = new ConcurrentHashMap<>();
    private final long timeoutMs;

    public ConversationSessionManager(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /** Opens (or replaces) the active conversation for a player. */
    public void start(UUID playerUuid, int npcId, String npcName) {
        active.put(playerUuid, new ActiveConversation(npcId, npcName, System.currentTimeMillis()));
    }

    /** Returns the active, non-expired conversation for a player, if any. */
    public Optional<ActiveConversation> get(UUID playerUuid) {
        ActiveConversation conversation = active.get(playerUuid);
        if (conversation == null) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() - conversation.startedAt() > timeoutMs) {
            active.remove(playerUuid);
            return Optional.empty();
        }
        return Optional.of(conversation);
    }

    /** True if the player currently has an open, non-expired conversation with any NPC. */
    public boolean isActive(UUID playerUuid) {
        return get(playerUuid).isPresent();
    }

    /** True if the player currently has an open, non-expired conversation with this specific NPC. */
    public boolean isActiveWith(UUID playerUuid, int npcId) {
        return get(playerUuid).map(c -> c.npcId() == npcId).orElse(false);
    }

    public void clear(UUID playerUuid) {
        active.remove(playerUuid);
    }

    public record ActiveConversation(int npcId, String npcName, long startedAt) {
    }
}
