package com.okotu.aihuman.npc;

import com.okotu.aihuman.db.NpcWaypointDao;
import com.okotu.aihuman.model.NpcWaypoint;
import org.bukkit.Location;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of TRAVEL-behavior waypoints, mirroring
 * {@code AutonomousNpcRegistry}'s design so {@code NpcBehaviorManager}'s
 * per-tick loop (main thread) never hits the database directly - every
 * waypoint is loaded eagerly, either at startup ({@link #loadInitialState()})
 * or synchronously within the same async command call that just wrote it
 * ({@link #add}/{@link #clear}), so {@link #get} is always a plain map
 * lookup, never a lazy DB load.
 */
public class NpcWaypointRegistry {

    private final NpcWaypointDao dao;
    private final Map<Integer, List<NpcWaypoint>> waypointsByNpcId = new ConcurrentHashMap<>();

    public NpcWaypointRegistry(NpcWaypointDao dao) {
        this.dao = dao;
    }

    /** Blocking: call once during plugin startup, same as the other registries. */
    public void loadInitialState() throws SQLException {
        for (NpcWaypoint waypoint : dao.findAll()) {
            waypointsByNpcId.computeIfAbsent(waypoint.npcId(), id -> new ArrayList<>()).add(waypoint);
        }
    }

    /** Fast, non-blocking: safe to call from the main thread. Empty if none configured (or none loaded). */
    public List<NpcWaypoint> get(int npcId) {
        return waypointsByNpcId.getOrDefault(npcId, List.of());
    }

    /** Blocking: call from an async thread. */
    public void add(int npcId, Location location) throws SQLException {
        dao.add(npcId, location);
        refresh(npcId);
    }

    /** Blocking: call from an async thread. */
    public void clear(int npcId) throws SQLException {
        dao.clear(npcId);
        waypointsByNpcId.remove(npcId);
    }

    private void refresh(int npcId) throws SQLException {
        waypointsByNpcId.put(npcId, dao.findForNpc(npcId));
    }
}
