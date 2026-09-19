package com.okotu.aihuman.npc;

import org.bukkit.Location;

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

    // Stuck detection (1.17+): last position where the NPC was seen making
    // real progress while "navigating", and when. See NpcBehaviorManager.
    private Location lastProgressPosition;
    private long lastProgressAtMillis = System.currentTimeMillis();

    // TRAVEL behavior (1.17+): which waypoint in npc_travel_waypoints (in
    // sequence order) this NPC is currently heading to.
    private int waypointIndex = 0;

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

    public Location lastProgressPosition() {
        return lastProgressPosition;
    }

    public void setLastProgressPosition(Location lastProgressPosition) {
        this.lastProgressPosition = lastProgressPosition;
    }

    public long lastProgressAtMillis() {
        return lastProgressAtMillis;
    }

    public void setLastProgressAtMillis(long lastProgressAtMillis) {
        this.lastProgressAtMillis = lastProgressAtMillis;
    }

    public int waypointIndex() {
        return waypointIndex;
    }

    public void setWaypointIndex(int waypointIndex) {
        this.waypointIndex = waypointIndex;
    }
}
