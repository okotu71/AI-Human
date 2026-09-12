package com.okotu.aihuman.model;

/**
 * How an NPC behaves in the world (autonomous movement), as opposed to
 * {@link NpcProfile} (who it is) or {@link NpcState} (how it currently
 * feels). One row per NPC, only meaningful once {@code autonomous} is true.
 *
 * <p>Nullable numeric/boolean fields mean "use the
 * {@code interaction.autonomous.*} default from config.yml" - see
 * {@code NpcBehaviorSettings}, which resolves a config default against any
 * per-NPC override from this record.
 */
public record NpcBehaviorConfig(
        int npcId,
        boolean autonomous,
        NpcBehaviorType behaviorType,
        String homeWorld,
        Double homeX,
        Double homeY,
        Double homeZ,
        Integer wanderRadius,
        Integer wanderMinDistance,
        Integer wanderMaxDistance,
        Double detectionRadius,
        Boolean avoidLava,
        Boolean avoidDeepWater,
        Boolean avoidCliffs,
        Boolean avoidFire
) {

    public static NpcBehaviorConfig disabledDefault(int npcId) {
        return new NpcBehaviorConfig(npcId, false, NpcBehaviorType.WANDER,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public boolean hasHome() {
        return homeWorld != null && homeX != null && homeY != null && homeZ != null;
    }
}
