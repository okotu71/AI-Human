package com.okotu.aihuman.service;

import com.okotu.aihuman.config.PluginConfig;
import com.okotu.aihuman.db.PlayerMemoryDao;

import java.sql.SQLException;
import java.util.UUID;

/**
 * Wraps PlayerMemoryDao's relationship-score column with clamping and the
 * named "actions" from config.yml (relationship.actions), plus a qualitative
 * description used in the MEMORIA section of the prompt.
 */
public class RelationshipService {

    private final PlayerMemoryDao playerMemoryDao;
    private final PluginConfig config;

    public RelationshipService(PlayerMemoryDao playerMemoryDao, PluginConfig config) {
        this.playerMemoryDao = playerMemoryDao;
        this.config = config;
    }

    /** BLOCKING: call from an async thread. Returns the new (clamped) score. */
    public int adjust(int npcId, UUID playerUuid, int delta) throws SQLException {
        return playerMemoryDao.adjustRelationship(npcId, playerUuid, delta, config.relationshipMin, config.relationshipMax);
    }

    /**
     * Applies a named action delta from config.yml (relationship.actions).
     * BLOCKING: call from an async thread.
     *
     * @throws IllegalArgumentException if the action key isn't configured
     */
    public int applyAction(int npcId, UUID playerUuid, String actionKey) throws SQLException {
        Integer delta = config.relationshipActionDelta(actionKey);
        if (delta == null) {
            throw new IllegalArgumentException("Unknown relationship action: " + actionKey
                    + " (check relationship.actions in config.yml)");
        }
        return adjust(npcId, playerUuid, delta);
    }

    /** Short qualitative description of a relationship score, for use inside prompts. */
    public String describe(int score) {
        if (score <= -60) return "hates you";
        if (score <= -20) return "is wary of you";
        if (score < 20) return "is neutral towards you";
        if (score < 60) return "considers you friendly";
        return "considers you a close friend";
    }
}
