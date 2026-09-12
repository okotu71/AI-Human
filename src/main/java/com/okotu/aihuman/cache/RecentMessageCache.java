package com.okotu.aihuman.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.okotu.aihuman.config.PluginConfig;
import com.okotu.aihuman.db.DialogHistoryDao;
import com.okotu.aihuman.model.ConversationEntry;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keeps the last {@code conversation.recent-messages} turns per (npc, player)
 * pair in RAM, for the "ULTIMI MESSAGGI" section of the prompt - independent
 * from {@code summary-trigger-messages}, which SummaryService tracks
 * separately via npc_player_memory.messages_since_summary.
 *
 * MySQL (via DialogHistoryDao) remains the source of truth: on a cache miss
 * (e.g. after a restart) this reloads from there. Key: "npcId:playerUuid".
 */
public class RecentMessageCache {

    private final DialogHistoryDao dao;
    private final int recentMessages;
    private final Cache<String, Deque<ConversationEntry>> cache;
    private final ReentrantLock loadLock = new ReentrantLock();

    public RecentMessageCache(DialogHistoryDao dao, PluginConfig config) {
        this.dao = dao;
        this.recentMessages = config.recentMessages;
        this.cache = Caffeine.newBuilder()
                .maximumSize(config.cacheMaxEntries)
                .expireAfterAccess(config.cacheExpireAfterMinutes, TimeUnit.MINUTES)
                .build();
    }

    private static String key(int npcId, UUID playerUuid) {
        return npcId + ":" + playerUuid;
    }

    /** BLOCKING if a DB load is needed: always call from an async thread. */
    public List<ConversationEntry> getRecent(int npcId, UUID playerUuid) throws SQLException {
        String k = key(npcId, playerUuid);
        Deque<ConversationEntry> deque = cache.getIfPresent(k);
        if (deque == null) {
            loadLock.lock();
            try {
                deque = cache.getIfPresent(k);
                if (deque == null) {
                    List<ConversationEntry> fromDb = dao.fetchLast(npcId, playerUuid, recentMessages);
                    deque = new ArrayDeque<>(fromDb);
                    cache.put(k, deque);
                }
            } finally {
                loadLock.unlock();
            }
        }
        synchronized (deque) {
            return new ArrayList<>(deque);
        }
    }

    /** Appends to both the RAM cache (truncated to recent-messages) and MySQL. BLOCKING: call from async. */
    public void append(int npcId, UUID playerUuid, ConversationEntry entry) throws SQLException {
        String k = key(npcId, playerUuid);
        Deque<ConversationEntry> deque = cache.getIfPresent(k);
        if (deque == null) {
            getRecent(npcId, playerUuid);
            deque = cache.getIfPresent(k);
        }
        synchronized (deque) {
            deque.addLast(entry);
            while (deque.size() > recentMessages) {
                deque.removeFirst();
            }
        }
        dao.insert(npcId, playerUuid, entry);
    }

    /** Called by SummaryService right after a compression cycle deletes the underlying DB rows. */
    public void invalidate(int npcId, UUID playerUuid) {
        cache.invalidate(key(npcId, playerUuid));
    }
}
