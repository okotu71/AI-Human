package com.okotu.aihuman.api;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public integration point for other plugins (quest plugins, economy
 * plugins, custom event systems, ...). Registered as a Bukkit service:
 *
 * <pre>{@code
 * RegisteredServiceProvider<AiHumanApi> rsp =
 *         Bukkit.getServicesManager().getRegistration(AiHumanApi.class);
 * if (rsp != null) {
 *     AiHumanApi api = rsp.getProvider();
 *     api.applyRelationshipAction(15, playerUuid, "saved-villager");
 * }
 * }</pre>
 *
 * All methods are async (backed by AI-Human's own worker pool)
 * and safe to call from the main server thread.
 */
public interface AiHumanApi {

    /** Adjusts a player's relationship score with an NPC by an arbitrary delta (clamped to configured min/max). */
    CompletableFuture<Integer> adjustRelationship(int npcId, UUID playerUuid, int delta);

    /** Applies a named action delta from config.yml's relationship.actions (e.g. "saved-villager"). */
    CompletableFuture<Integer> applyRelationshipAction(int npcId, UUID playerUuid, String actionKey);

    /** Adds a shared event visible to every NPC in the given village. {@code expires} may be null (never expires). */
    CompletableFuture<Long> addVillageEvent(String village, int priority, String summary, Instant expires);

    /** Adds or updates a knowledge entry for an NPC (topic is unique per NPC). */
    CompletableFuture<Void> setKnowledge(int npcId, String topic, String text);

    /** Removes a knowledge entry. Returns whether a row was actually deleted. */
    CompletableFuture<Boolean> removeKnowledge(int npcId, String topic);

    /**
     * Updates any subset of an NPC's emotional state (0-100 scales). Pass
     * null for fields that should stay unchanged.
     */
    CompletableFuture<Void> setNpcState(int npcId, Integer happiness, Integer fear, Integer anger,
                                         Integer fatigue, Integer hunger);
}
