package com.okotu.aihuman.npc;

import com.okotu.aihuman.db.NpcProfileDao;
import com.okotu.aihuman.model.NpcProfile;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-memory cache of which NPCs are allowed to use AI chat at all. Not every
 * Citizens NPC should suddenly start talking - as of 1.06 an admin has to
 * explicitly opt an NPC in via {@code /aihuman enable <npcId>} (console
 * friendly: no in-game "selected NPC" needed, just the numeric id).
 *
 * <p>{@link #isEnabled(int)} is a plain in-memory set lookup - safe to call
 * from the main thread on every right-click and on every proximity scan
 * tick without hitting the database each time. The set is populated once at
 * startup ({@link #loadInitialState()}) and kept in sync by
 * {@link #enable}/{@link #disable}.
 */
public class EnabledNpcRegistry {

    private final NpcProfileDao npcProfileDao;
    private final Set<Integer> enabledNpcIds = ConcurrentHashMap.newKeySet();

    public EnabledNpcRegistry(NpcProfileDao npcProfileDao) {
        this.npcProfileDao = npcProfileDao;
    }

    /**
     * Blocking: call once during plugin startup (before any listener/task
     * can query {@link #isEnabled}), same as {@code Database#applySchema}.
     */
    public void loadInitialState() throws SQLException {
        enabledNpcIds.addAll(npcProfileDao.findEnabledNpcIds());
    }

    /** Fast, non-blocking: safe to call from the main thread as often as needed. */
    public boolean isEnabled(int npcId) {
        return enabledNpcIds.contains(npcId);
    }

    /**
     * Ensures the NPC has a profile (creating one via {@code defaultProfileSupplier}
     * if missing) and marks it enabled. Blocking: call from an async thread.
     */
    public void enable(int npcId, Supplier<NpcProfile> defaultProfileSupplier) throws SQLException {
        npcProfileDao.findOrCreate(npcId, defaultProfileSupplier);
        npcProfileDao.setEnabled(npcId, true);
        enabledNpcIds.add(npcId);
    }

    /**
     * Marks the NPC disabled. The profile row (backstory, knowledge, etc.)
     * is kept as-is, only the flag flips - re-enabling later picks up where
     * it left off instead of generating a new random profile. Blocking:
     * call from an async thread.
     */
    public void disable(int npcId) throws SQLException {
        npcProfileDao.setEnabled(npcId, false);
        enabledNpcIds.remove(npcId);
    }

    public int enabledCount() {
        return enabledNpcIds.size();
    }
}
