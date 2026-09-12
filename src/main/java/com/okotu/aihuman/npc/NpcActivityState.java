package com.okotu.aihuman.npc;

/**
 * Behavioural state of an autonomous NPC (1.11+), separate from
 * {@code model.NpcState} (emotional state - happiness/fear/etc) and from
 * {@link ConversationSessionManager} (which only tracks "is chat capture
 * open", the last/TALKING piece of this bigger machine).
 *
 * <pre>
 * WANDERING --[player enters detection radius]--> APPROACHING
 * APPROACHING --[reached player / gave up]--> TALKING
 * APPROACHING --[player left range before reaching them]--> WANDERING
 * TALKING --[conversation session closed]--> WANDERING
 * </pre>
 */
public enum NpcActivityState {
    WANDERING,
    APPROACHING,
    TALKING
}
