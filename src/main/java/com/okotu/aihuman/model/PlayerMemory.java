package com.okotu.aihuman.model;

import java.time.Instant;
import java.util.UUID;

/**
 * What a specific NPC remembers about a specific player (npc_player_memory).
 * One row per (npc, player) pair.
 */
public record PlayerMemory(
        int npcId,
        UUID playerUuid,
        int relationshipScore,
        String knownName,
        String notes,
        String summary,
        int messagesSinceSummary,
        Instant lastSeen
) {

    public static PlayerMemory defaultFor(int npcId, UUID playerUuid, int defaultScore) {
        return new PlayerMemory(npcId, playerUuid, defaultScore, null, null, null, 0, null);
    }
}
