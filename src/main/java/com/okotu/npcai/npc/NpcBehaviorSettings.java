package com.okotu.npcai.npc;

import com.okotu.npcai.config.PluginConfig;
import com.okotu.npcai.model.NpcBehaviorConfig;

/**
 * Resolved settings for one autonomous NPC: per-NPC overrides from
 * {@link NpcBehaviorConfig} layered on top of the
 * {@code interaction.autonomous.*} defaults from config.yml. Computed fresh
 * each time {@code NpcBehaviorManager} evaluates an NPC - cheap, and always
 * reflects the latest config/overrides without needing its own cache.
 */
public record NpcBehaviorSettings(
        int wanderRadius,
        int wanderMinDistance,
        int wanderMaxDistance,
        double detectionRadius,
        boolean avoidLava,
        boolean avoidDeepWater,
        boolean avoidCliffs,
        boolean avoidFire,
        int maxSafeFallBlocks,
        int maxWaterDepth,
        int destinationAttempts,
        double approachStopDistance,
        double walkSpeed
) {

    public static NpcBehaviorSettings resolve(PluginConfig config, NpcBehaviorConfig override) {
        return new NpcBehaviorSettings(
                override.wanderRadius() != null ? override.wanderRadius() : config.autonomousDefaultWanderRadius,
                override.wanderMinDistance() != null
                        ? override.wanderMinDistance() : config.autonomousDefaultWanderMinDistance,
                override.wanderMaxDistance() != null
                        ? override.wanderMaxDistance() : config.autonomousDefaultWanderMaxDistance,
                override.detectionRadius() != null
                        ? override.detectionRadius() : config.autonomousDefaultDetectionRadius,
                override.avoidLava() != null ? override.avoidLava() : config.autonomousAvoidLava,
                override.avoidDeepWater() != null ? override.avoidDeepWater() : config.autonomousAvoidDeepWater,
                override.avoidCliffs() != null ? override.avoidCliffs() : config.autonomousAvoidCliffs,
                override.avoidFire() != null ? override.avoidFire() : config.autonomousAvoidFire,
                config.autonomousMaxSafeFallBlocks,
                config.autonomousMaxWaterDepth,
                config.autonomousDestinationAttempts,
                config.autonomousApproachStopDistance,
                config.autonomousWalkSpeed
        );
    }
}
