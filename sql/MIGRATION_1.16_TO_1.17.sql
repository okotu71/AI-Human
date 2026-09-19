-- =========================================================
-- AI-Human - data migration 1.16 -> 1.17
-- =========================================================
-- 1.17 adds real TRAVEL behavior (an NPC visits a sequence of admin-placed
-- waypoints instead of wandering randomly). Only one new table, no changes
-- to anything existing - NPCs currently on WANDER keep wandering exactly as
-- before.

-- 1) Add the table (adjust the table name if you're using a
--    mysql-table-prefix, e.g. yourprefix_npc_travel_waypoints):
CREATE TABLE IF NOT EXISTS npc_travel_waypoints (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    npc_id     INT UNSIGNED NOT NULL,
    sequence   INT UNSIGNED NOT NULL,
    world      VARCHAR(64)  NOT NULL,
    x          DOUBLE       NOT NULL,
    y          DOUBLE       NOT NULL,
    z          DOUBLE       NOT NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_npc_sequence (npc_id, sequence),
    CONSTRAINT fk_waypoint_npc FOREIGN KEY (npc_id)
        REFERENCES npc_profiles (npc_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2) Set up a TRAVEL NPC:
--    /aihuman behavior <npcId> TRAVEL
--    /aihuman travel <npcId> add            (uses your current in-game position)
--    /aihuman travel <npcId> add <world> <x> <y> <z>   (or explicit coords, works from console too)
--    /aihuman travel <npcId> list           (see what's configured)
--    /aihuman travel <npcId> clear          (remove every waypoint)
--
-- An NPC set to TRAVEL with zero waypoints simply doesn't move - it does
-- NOT fall back to random wandering, since that would silently ignore what
-- you asked for.
