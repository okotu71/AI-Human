package com.okotu.npcai.npc;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Picks where an autonomous NPC should go next and hands the result to
 * Citizens' own Navigator - this plugin decides destinations, Citizens does
 * the actual pathfinding/walking/obstacle-avoidance, exactly once per
 * decision rather than once per step.
 *
 * <p>Must be called from the main thread (touches world blocks via
 * {@link NpcWorldSafety} and the NPC's Navigator).
 */
public class NpcMovementController {

    /**
     * Picks a random point within [wanderMinDistance, wanderMaxDistance] of
     * {@code home}, never further than {@code wanderRadius} from it, and
     * only returns it if {@link NpcWorldSafety} accepts it - retrying up to
     * {@code destinationAttempts} times. Empty if nothing safe was found
     * this cycle (the caller should just try again next interval).
     */
    public Optional<Location> pickDestination(Location home, NpcBehaviorSettings settings) {
        World world = home.getWorld();
        if (world == null) {
            return Optional.empty();
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        int span = Math.max(1, settings.wanderMaxDistance() - settings.wanderMinDistance());

        for (int attempt = 0; attempt < settings.destinationAttempts(); attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = settings.wanderMinDistance() + random.nextDouble() * span;
            double x = home.getX() + Math.cos(angle) * distance;
            double z = home.getZ() + Math.sin(angle) * distance;

            if (Math.hypot(x - home.getX(), z - home.getZ()) > settings.wanderRadius()) {
                continue; // outside the overall roam boundary, try another angle/distance
            }

            int blockX = (int) Math.floor(x);
            int blockZ = (int) Math.floor(z);
            int surfaceY = world.getHighestBlockYAt(blockX, blockZ);
            Location candidate = new Location(world, x, surfaceY + 1, z);

            if (NpcWorldSafety.isSafe(candidate, settings)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Starts (or redirects) the NPC's Navigator toward {@code destination}. */
    public void navigateTo(NPC npc, Location destination, double walkSpeed) {
        npc.getNavigator().getLocalParameters().speedModifier((float) walkSpeed);
        npc.getNavigator().setTarget(destination, false);
    }
}
