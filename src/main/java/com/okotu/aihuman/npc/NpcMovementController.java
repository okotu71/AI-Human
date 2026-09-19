package com.okotu.aihuman.npc;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

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
     *
     * <p>{@code wanderMinDistance}/{@code wanderMaxDistance} are clamped to
     * {@code wanderRadius} before picking: if an admin sets, say, radius=200
     * with minDistance=500, every single candidate would otherwise be
     * guaranteed to land outside the radius and get rejected - a
     * configuration that looks "on" but makes the NPC never move at all,
     * forever, with nothing in the logs to explain why. Clamping means the
     * NPC always has a chance to find a valid destination; {@code /aihuman wander}
     * separately warns when it sees a combination like this, since it's
     * almost always a mistake (radius should be &gt;= maxDistance).
     *
     * <p>1.17+: also rejects a candidate whose ground elevation differs from
     * home's by more than {@code maxElevationChange} - the usual cause of an
     * NPC picking a destination up (or down) a mountain and getting stuck
     * partway - and, once a safe candidate is found, nudges it onto a nearby
     * path block if {@code preferPaths} is on and one exists within
     * {@code pathSearchRadius} (re-validated with {@link NpcWorldSafety}
     * before being used, so a nearby-but-unsafe path block is never picked
     * over a safe plain destination).
     */
    public Optional<Location> pickDestination(Location home, NpcBehaviorSettings settings) {
        World world = home.getWorld();
        if (world == null) {
            return Optional.empty();
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        double effectiveMax = Math.min(settings.wanderMaxDistance(), settings.wanderRadius());
        double effectiveMin = Math.min(settings.wanderMinDistance(), effectiveMax);
        double span = Math.max(1, effectiveMax - effectiveMin);

        for (int attempt = 0; attempt < settings.destinationAttempts(); attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = effectiveMin + random.nextDouble() * span;
            double x = home.getX() + Math.cos(angle) * distance;
            double z = home.getZ() + Math.sin(angle) * distance;

            int blockX = (int) Math.floor(x);
            int blockZ = (int) Math.floor(z);
            int surfaceY = world.getHighestBlockYAt(blockX, blockZ);

            if (Math.abs(surfaceY - home.getBlockY()) > settings.maxElevationChange()) {
                continue; // too big a climb/descent from home - likely a mountain/ravine, skip it
            }

            Location candidate = new Location(world, x, surfaceY + 1, z);

            if (NpcWorldSafety.isSafe(candidate, settings)) {
                if (settings.preferPaths()) {
                    Location snapped = snapToNearbyPath(candidate, settings.pathSearchRadius());
                    if (snapped != null && NpcWorldSafety.isSafe(snapped, settings)) {
                        return Optional.of(snapped);
                    }
                }
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Starts (or redirects) the NPC's Navigator toward {@code destination}. */
    public void navigateTo(NPC npc, Location destination, double walkSpeed) {
        npc.getNavigator().getLocalParameters().speedModifier((float) walkSpeed);
        npc.getNavigator().setTarget(destination);
    }

    /**
     * Looks for the nearest vanilla path block within {@code radius} blocks
     * (a square scan, cheap and bounded) of {@code candidate} and returns a
     * point on top of it, or null if none was found close enough. Only
     * checks {@link Material#DIRT_PATH} - the vanilla "grass path" block
     * (village paths, and whatever players make with a shovel); doesn't
     * attempt to recognize arbitrary player-built roads out of other
     * materials, since there's no reliable way to tell a road from a floor
     * or a wall from block type alone.
     */
    private Location snapToNearbyPath(Location candidate, int radius) {
        World world = candidate.getWorld();
        if (world == null) {
            return null;
        }
        int centerX = candidate.getBlockX();
        int centerZ = candidate.getBlockZ();

        Block closest = null;
        int closestDistanceSquared = Integer.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = centerX + dx;
                int z = centerZ + dz;
                int y = world.getHighestBlockYAt(x, z);
                Block block = world.getBlockAt(x, y, z);
                if (block.getType() == Material.DIRT_PATH) {
                    int distanceSquared = dx * dx + dz * dz;
                    if (distanceSquared < closestDistanceSquared) {
                        closestDistanceSquared = distanceSquared;
                        closest = block;
                    }
                }
            }
        }

        if (closest == null) {
            return null;
        }
        return new Location(world, closest.getX() + 0.5, closest.getY() + 1, closest.getZ() + 0.5);
    }
}
