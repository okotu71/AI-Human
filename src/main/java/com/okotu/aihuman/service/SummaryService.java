package com.okotu.aihuman.service;

import com.okotu.aihuman.ai.OllamaClient;
import com.okotu.aihuman.cache.RecentMessageCache;
import com.okotu.aihuman.config.PluginConfig;
import com.okotu.aihuman.db.DialogHistoryDao;
import com.okotu.aihuman.db.PlayerMemoryDao;
import com.okotu.aihuman.model.ConversationEntry;
import com.okotu.aihuman.model.PlayerMemory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The "compressed memory" feature: once {@code summary-trigger-messages} raw
 * turns have piled up for a (npc, player) pair, asks Ollama to fold them
 * (plus any existing summary) into a short standing summary, saves it to
 * npc_player_memory.summary, and deletes the raw rows from
 * npc_dialog_history. This is what lets long-term memory grow "pochissimo"
 * instead of every message being replayed into every future prompt forever.
 *
 * <p>Runs fully async and never blocks the reply already sent to the player -
 * it's triggered as a fire-and-forget follow-up after a turn completes.
 */
public class SummaryService {

    private final PluginConfig config;
    private final PlayerMemoryDao playerMemoryDao;
    private final DialogHistoryDao dialogHistoryDao;
    private final RecentMessageCache recentMessageCache;
    private final OllamaClient ollamaClient;
    private final Executor asyncExecutor;
    private final Logger logger;

    public SummaryService(PluginConfig config, PlayerMemoryDao playerMemoryDao, DialogHistoryDao dialogHistoryDao,
                           RecentMessageCache recentMessageCache, OllamaClient ollamaClient,
                           Executor asyncExecutor, Logger logger) {
        this.config = config;
        this.playerMemoryDao = playerMemoryDao;
        this.dialogHistoryDao = dialogHistoryDao;
        this.recentMessageCache = recentMessageCache;
        this.ollamaClient = ollamaClient;
        this.asyncExecutor = asyncExecutor;
        this.logger = logger;
    }

    /**
     * Checks the trigger and, if reached, compresses in the background.
     * Fire-and-forget: never propagates failures to the caller, only logs
     * them (the next turn will simply try again, since the counter stays
     * above the threshold until a compression succeeds).
     */
    public void maybeCompressAsync(int npcId, UUID playerUuid) {
        CompletableFuture.runAsync(() -> {
            try {
                PlayerMemory memory = playerMemoryDao.findOrCreate(npcId, playerUuid);
                if (memory.messagesSinceSummary() < config.summaryTriggerMessages) {
                    return;
                }
                compress(npcId, playerUuid, memory.summary());
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error checking/compressing memory for npc=" + npcId
                        + " player=" + playerUuid, e);
            }
        }, asyncExecutor);
    }

    private void compress(int npcId, UUID playerUuid, String previousSummary) throws Exception {
        List<ConversationEntry> all = dialogHistoryDao.fetchAll(npcId, playerUuid);
        if (all.isEmpty()) {
            return;
        }

        String transcript = buildTranscript(all);
        String systemPrompt = config.summaryPromptTemplate.replace(
                "{max_words}", String.valueOf(config.summaryMaxWords));

        StringBuilder userMessage = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            userMessage.append("Previous summary:\n").append(previousSummary).append("\n\n");
        }
        userMessage.append("New conversation to integrate:\n").append(transcript);

        String newSummary = ollamaClient
                .chat(config.ollamaSummaryModel, systemPrompt, List.of(), userMessage.toString(),
                        new OllamaClient.CallOptions(config.ollamaSummaryNumPredict,
                                config.ollamaSummaryTimeoutMs, config.ollamaSummaryNumCtx))
                .get(); // blocking .get() is fine here: we're already on an async worker thread

        playerMemoryDao.applySummary(npcId, playerUuid, newSummary.trim());
        dialogHistoryDao.deleteAll(npcId, playerUuid);
        recentMessageCache.invalidate(npcId, playerUuid);

        logger.info("Compressed memory for npc=" + npcId + " player=" + playerUuid
                + " (" + all.size() + " messages -> summary).");
    }

    private String buildTranscript(List<ConversationEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (ConversationEntry entry : entries) {
            String label = entry.role() == ConversationEntry.Role.PLAYER ? "Player" : "NPC";
            sb.append(label).append(": ").append(entry.message()).append('\n');
        }
        return sb.toString();
    }
}
