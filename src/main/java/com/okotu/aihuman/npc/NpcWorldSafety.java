package com.okotu.aihuman.npc;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Validates a candidate wander destination against the world itself -
 * separate from (and in addition to) Citizens' own Navigator obstacle
 * avoidance, which is about not getting physically stuck, not about
 * "should an NPC willingly walk here at all". Called once per candidate
 * point before it's ever handed to {@code Navigator.setTarget}, so an NPC
 * never even attempts to path into lava, deep water, fire, or off a cliff.
 *
 * <p>Must be called from the main thread: reads live block/world data.
 * Deliberately simple (small fixed-radius scans) since this runs once per
 * destination-pick attempt, not per tick - see {@code NpcMovementController}.
 *
 * <p>Known limitation: ground-finding uses
 * {@link World#getHighestBlockAt(int, int)}, which works well above ground
 * but doesn't understand caves/overhangs - candidate points inside a cave
 * system may be misjudged. Fine for typical village/overworld wandering,
 * worth revisiting if autonomous NPCs are ever used underground.
 */
public final class NpcWorldSafety {

    private NpcWorldSafety() {
    }

    public static boolean isSafe(Location candidate, NpcBehaviorSettings settings) {
        return rejectionReason(candidate, settings) == null;
    }

    /**
     * Same check as {@link #isSafe}, but returns a short human-readable reason
     * ("no-world", "cliff", "lava", "fire", "cactus", "deep-water") instead of
     * a bare boolean, or {@code null} if the candidate is safe. Used only by
     * the {@code debug.log-movement} path in {@code NpcMovementController} so
     * a rejected wander candidate can be explained in the server log instead
     * of just silently retried.
     */
    public static String rejectionReason(Location candidate, NpcBehaviorSettings settings) {
        World world = candidate.getWorld();
        if (world == null) {
            return "no-world";
        }

        // Same MOTION_BLOCKING_NO_LEAVES reasoning as NpcMovementController#pickDestination:
        // the default heightmap counts leaves as ground, which misjudges "ground" as the
        // top of a tree canopy almost anywhere there's forest cover.
        Block ground = world.getHighestBlockAt(candidate.getBlockX(), candidate.getBlockZ(),
                HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (ground == null) {
            return "no-ground";
        }

        if (settings.avoidCliffs()) {
            int fall = candidate.getBlockY() - ground.getY() - 1;
            if (fall > settings.maxSafeFallBlocks()) {
                return "cliff(fall=" + fall + ")";
            }
        }

        if (settings.avoidLava() && (isType(ground, Material.LAVA) || nearAny(candidate, 1, Material.LAVA))) {
            return "lava";
        }

        if (settings.avoidFire() && nearAny(candidate, 1, Material.FIRE, Material.SOUL_FIRE)) {
            return "fire";
        }

        // Not configurable on its own (always avoided): standing an NPC on/next to a
        // cactus looks broken and can damage it for no in-character reason.
        if (nearAny(candidate, 1, Material.CACTUS)) {
            return "cactus";
        }

        if (settings.avoidDeepWater() && isDeepWater(world, ground, settings.maxWaterDepth())) {
            return "deep-water";
        }

        return null;
    }

    private static boolean isType(Block block, Material material) {
        return block.getType() == material;
    }

    private static boolean nearAny(Location center, int radius, Material... materials) {
        World world = center.getWorld();
        if (world == null) {
            return false;
        }
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Material type = world.getBlockAt(cx + dx, cy + dy, cz + dz).getType();
                    for (Material m : materials) {
                        if (type == m) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean isDeepWater(World world, Block surfaceBlock, int maxDepth) {
        if (surfaceBlock.getType() != Material.WATER) {
            return false;
        }
        int depth = 0;
        Block current = surfaceBlock;
        while (current.getType() == Material.WATER && depth <= maxDepth + 1) {
            depth++;
            current = current.getRelative(0, -1, 0);
        }
        return depth > maxDepth;
    }
}
