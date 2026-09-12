package com.okotu.aihuman.model;

/**
 * Moment-to-moment emotional state of an NPC (npc_state), 0-100 scales.
 * One row per NPC; independent from any specific player.
 */
public record NpcState(int npcId, int happiness, int fear, int anger, int fatigue, int hunger) {

    public static NpcState defaultFor(int npcId) {
        return new NpcState(npcId, 70, 10, 10, 20, 20);
    }
}
