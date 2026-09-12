package com.okotu.npcai.model;

/**
 * How an autonomous NPC decides where to go. Only {@link #WANDER} is
 * actually implemented as of 1.11 - the others are accepted and stored
 * (so config/commands/schema don't need to change again later) but
 * currently behave exactly like WANDER. Reserved for future releases:
 * VILLAGE (stay within a named village's bounds), TRAVEL (move between
 * named points), GUARD (stay near a fixed point, face outward), FOLLOW
 * (stick close to a specific player or NPC).
 */
public enum NpcBehaviorType {
    WANDER,
    VILLAGE,
    TRAVEL,
    GUARD,
    FOLLOW
}
