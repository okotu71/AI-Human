package com.okotu.npcai.npc;

import java.util.UUID;

/**
 * Runtime (in-RAM only, not persisted) state machine holder for one
 * autonomous NPC - see {@link NpcActivityState}. Owned and updated by
 * {@code NpcBehaviorManager}; resets to WANDERING with no target whenever
 * the plugin restarts (nothing to persist: "was mid-approach toward player
 * X" isn't meaningful after a restart anyway).
 */
public class NpcController {

    private final int npcId;
    private NpcActivityState state = NpcActivityState.WANDERING;
    private UUID targetPlayerUuid;

    public NpcController(int npcId) {
        this.npcId = npcId;
    }

    public int npcId() {
        return npcId;
    }

    public NpcActivityState state() {
        return state;
    }

    public void setState(NpcActivityState state) {
        this.state = state;
    }

    public UUID targetPlayerUuid() {
        return targetPlayerUuid;
    }

    public void setTargetPlayerUuid(UUID targetPlayerUuid) {
        this.targetPlayerUuid = targetPlayerUuid;
    }
}
