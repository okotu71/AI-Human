package com.okotu.aihuman.db;

import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.logging.Level;

/**
 * Run periodically (async):
 * 1) safety-trims npc_dialog_history in case SummaryService ever falls
 *    behind (normal operation should rarely delete anything here, since
 *    compression already clears rows well before this cap);
 * 2) deletes expired village_events.
 */
public class CleanupTask implements Runnable {

    private final Plugin plugin;
    private final DialogHistoryDao dialogHistoryDao;
    private final VillageEventDao villageEventDao;
    private final int maxRawMessagesSafety;

    public CleanupTask(Plugin plugin, DialogHistoryDao dialogHistoryDao, VillageEventDao villageEventDao,
                        int maxRawMessagesSafety) {
        this.plugin = plugin;
        this.dialogHistoryDao = dialogHistoryDao;
        this.villageEventDao = villageEventDao;
        this.maxRawMessagesSafety = maxRawMessagesSafety;
    }

    @Override
    public void run() {
        try {
            int deletedDialog = dialogHistoryDao.trimSafety(maxRawMessagesSafety);
            if (deletedDialog > 0) {
                plugin.getLogger().warning("Safety cleanup removed " + deletedDialog + " dialog rows beyond "
                        + maxRawMessagesSafety + " per (npc, player) pair - memory compression may be falling "
                        + "behind (check Ollama availability).");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Error during periodic dialog history cleanup", e);
        }

        try {
            int deletedEvents = villageEventDao.deleteExpired();
            if (deletedEvents > 0) {
                plugin.getLogger().fine("Cleanup: removed " + deletedEvents + " expired village events.");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Error during periodic village events cleanup", e);
        }
    }
}
