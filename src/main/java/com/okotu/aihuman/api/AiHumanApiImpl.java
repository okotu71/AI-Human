package com.okotu.aihuman.api;

import com.okotu.aihuman.db.KnowledgeDao;
import com.okotu.aihuman.db.NpcStateDao;
import com.okotu.aihuman.db.VillageEventDao;
import com.okotu.aihuman.service.RelationshipService;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class AiHumanApiImpl implements AiHumanApi {

    private final RelationshipService relationshipService;
    private final VillageEventDao villageEventDao;
    private final KnowledgeDao knowledgeDao;
    private final NpcStateDao npcStateDao;
    private final Executor asyncExecutor;

    public AiHumanApiImpl(RelationshipService relationshipService, VillageEventDao villageEventDao,
                            KnowledgeDao knowledgeDao, NpcStateDao npcStateDao, Executor asyncExecutor) {
        this.relationshipService = relationshipService;
        this.villageEventDao = villageEventDao;
        this.knowledgeDao = knowledgeDao;
        this.npcStateDao = npcStateDao;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public CompletableFuture<Integer> adjustRelationship(int npcId, UUID playerUuid, int delta) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return relationshipService.adjust(npcId, playerUuid, delta);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Integer> applyRelationshipAction(int npcId, UUID playerUuid, String actionKey) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return relationshipService.applyAction(npcId, playerUuid, actionKey);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Long> addVillageEvent(String village, int priority, String summary, Instant expires) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return villageEventDao.add(village, priority, summary, expires);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Void> setKnowledge(int npcId, String topic, String text) {
        return CompletableFuture.runAsync(() -> {
            try {
                knowledgeDao.upsert(npcId, topic, text);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Boolean> removeKnowledge(int npcId, String topic) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return knowledgeDao.remove(npcId, topic);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Void> setNpcState(int npcId, Integer happiness, Integer fear, Integer anger,
                                                Integer fatigue, Integer hunger) {
        return CompletableFuture.runAsync(() -> {
            try {
                npcStateDao.update(npcId, happiness, fear, anger, fatigue, hunger);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }
}
