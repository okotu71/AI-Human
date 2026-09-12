package com.okotu.npcai.npc;

import com.okotu.npcai.db.NpcBehaviorDao;
import com.okotu.npcai.model.NpcBehaviorConfig;
import com.okotu.npcai.model.NpcBehaviorType;
import org.bukkit.Location;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache of which NPCs currently have autonomous movement turned
 * on, and their resolved behavior config - mirrors {@code EnabledNpcRegistry}'s
 * design so {@code NpcBehaviorManager}'s per-tick loop never hits the
 * database. Populated once at startup ({@link #loadInitialState()}) and
 * kept in sync by the admin commands ({@code /okotunpc autonomous|wander|behavior}).
 */
public class AutonomousNpcRegistry {

    private final NpcBehaviorDao npcBehaviorDao;
    private final Map<Integer, NpcBehaviorConfig> autonomous = new ConcurrentHashMap<>();

    public AutonomousNpcRegistry(NpcBehaviorDao npcBehaviorDao) {
        this.npcBehaviorDao = npcBehaviorDao;
    }

    /** Blocking: call once during plugin startup, same as EnabledNpcRegistry#loadInitialState. */
    public void loadInitialState() throws SQLException {
        for (int npcId : npcBehaviorDao.findAutonomousNpcIds()) {
            npcBehaviorDao.find(npcId).ifPresent(cfg -> autonomous.put(npcId, cfg));
        }
    }

    /** Fast, non-blocking: safe to call from the main thread as often as needed. */
    public Optional<NpcBehaviorConfig> get(int npcId) {
        return Optional.ofNullable(autonomous.get(npcId));
    }

    public boolean isAutonomous(int npcId) {
        return autonomous.containsKey(npcId);
    }

    /** Blocking: call from an async thread. Captures {@code homeLocation} only if the NPC has no config yet. */
    public void enable(int npcId, Location homeLocation) throws SQLException {
        npcBehaviorDao.enableAutonomous(npcId, homeLocation);
        refresh(npcId);
    }

    /** Blocking: call from an async thread. Keeps the stored config (home/wander/etc), just flips the flag off. */
    public void disable(int npcId) throws SQLException {
        npcBehaviorDao.setAutonomous(npcId, false);
        autonomous.remove(npcId);
    }

    /** Blocking: call from an async thread. */
    public void updateWander(int npcId, int radius, int minDistance, int maxDistance) throws SQLException {
        npcBehaviorDao.setWanderParams(npcId, radius, minDistance, maxDistance);
        refresh(npcId);
    }

    /** Blocking: call from an async thread. */
    public void updateBehaviorType(int npcId, NpcBehaviorType type) throws SQLException {
        npcBehaviorDao.setBehaviorType(npcId, type);
        refresh(npcId);
    }

    /** Blocking: call from an async thread. */
    public void updateDetectionRadius(int npcId, double radius) throws SQLException {
        npcBehaviorDao.setDetectionRadius(npcId, radius);
        refresh(npcId);
    }

    public int autonomousCount() {
        return autonomous.size();
    }

    private void refresh(int npcId) throws SQLException {
        Optional<NpcBehaviorConfig> updated = npcBehaviorDao.find(npcId);
        if (updated.isPresent() && updated.get().autonomous()) {
            autonomous.put(npcId, updated.get());
        } else {
            autonomous.remove(npcId);
        }
    }
}
