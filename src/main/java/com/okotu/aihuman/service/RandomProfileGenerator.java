package com.okotu.aihuman.service;

import com.okotu.aihuman.config.PluginConfig;
import com.okotu.aihuman.model.NpcProfile;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates a randomized starter profile the first time an admin enables AI
 * chat for a given NPC (see {@code EnabledNpcRegistry#enable}, run via
 * {@code /aihuman enable <npcId>}), picking independently from the pools
 * configured under {@code npc-defaults} in config.yml (roles, personalities,
 * backgrounds, professions, speech-styles).
 *
 * <p>{@code village} is deliberately left unset: village membership drives
 * shared village_events, so it's left for an admin to assign explicitly
 * (`/aihuman profile <id> village <name>`) rather than being picked at
 * random. There is no per-NPC model to set anymore since 1.04 - every NPC
 * uses whatever {@code ollama.default-model} is configured (qwen2.5:0.5b
 * out of the box).
 */
public class RandomProfileGenerator {

    private final PluginConfig config;

    public RandomProfileGenerator(PluginConfig config) {
        this.config = config;
    }

    public NpcProfile generate(int npcId, String name) {
        return new NpcProfile(
                npcId,
                name,
                pick(config.npcDefaultRoles),
                pick(config.npcDefaultPersonalities),
                pick(config.npcDefaultBackgrounds),
                null,
                pick(config.npcDefaultProfessions),
                pick(config.npcDefaultSpeechStyles),
                null,
                null,
                false
        );
    }

    private String pick(List<String> pool) {
        if (pool == null || pool.isEmpty()) {
            return "";
        }
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }
}
