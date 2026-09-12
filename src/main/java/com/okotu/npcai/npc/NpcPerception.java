package com.okotu.npcai.npc;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Finds nearby players for an NPC to react to. Shared by
 * {@code ProximityGreetingTask} (stationary AI NPCs) and
 * {@code NpcBehaviorManager} (autonomous NPCs, while WANDERING) so both use
 * the same "who's close enough" logic.
 */
public final class NpcPerception {

    private NpcPerception() {
    }

    /** Nearest player within {@code radius} blocks (same world) that passes {@code filter}, if any. */
    public static Optional<Player> findNearestPlayer(Location origin, double radius, Predicate<Player> filter) {
        World world = origin.getWorld();
        if (world == null) {
            return Optional.empty();
        }
        double radiusSquared = radius * radius;
        Player nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;

        for (Player player : world.getPlayers()) {
            if (!filter.test(player)) {
                continue;
            }
            double distanceSquared = origin.distanceSquared(player.getLocation());
            if (distanceSquared <= radiusSquared && distanceSquared < nearestDistanceSquared) {
                nearest = player;
                nearestDistanceSquared = distanceSquared;
            }
        }
        return Optional.ofNullable(nearest);
    }
}
