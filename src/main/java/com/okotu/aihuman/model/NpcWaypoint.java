package com.okotu.aihuman.model;

/**
 * A single stop in a TRAVEL-behavior NPC's route (npc_travel_waypoints).
 * Visited in {@code sequence} order, looping back to the lowest sequence
 * after the highest - see {@code NpcBehaviorManager}.
 */
public record NpcWaypoint(long id, int npcId, int sequence, String world, double x, double y, double z) {
}
