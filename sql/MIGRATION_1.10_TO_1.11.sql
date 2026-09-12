-- =========================================================
-- AI-Human - data migration 1.10 -> 1.11
-- =========================================================
-- 1.11 adds autonomous movement (wandering + an approach/talk state machine)
-- as an OPT-IN layer on top of AI-enabled NPCs. This only adds one new
-- table, npc_behavior_config - nothing existing changes shape, and no NPC
-- moves on its own until you explicitly turn it on.

-- 1) Add the table (adjust the table name if you're using a
--    mysql-table-prefix, e.g. yourprefix_npc_behavior_config):
CREATE TABLE IF NOT EXISTS npc_behavior_config (
    npc_id               INT UNSIGNED NOT NULL,
    autonomous           TINYINT(1)   NOT NULL DEFAULT 0,
    behavior_type        ENUM('WANDER','VILLAGE','TRAVEL','GUARD','FOLLOW') NOT NULL DEFAULT 'WANDER',
    home_world           VARCHAR(64)  NULL,
    home_x               DOUBLE       NULL,
    home_y               DOUBLE       NULL,
    home_z               DOUBLE       NULL,
    wander_radius        INT UNSIGNED NULL,
    wander_min_distance  INT UNSIGNED NULL,
    wander_max_distance  INT UNSIGNED NULL,
    detection_radius     DOUBLE       NULL,
    avoid_lava           TINYINT(1)   NULL,
    avoid_deep_water     TINYINT(1)   NULL,
    avoid_cliffs         TINYINT(1)   NULL,
    avoid_fire           TINYINT(1)   NULL,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (npc_id),
    CONSTRAINT fk_behavior_npc FOREIGN KEY (npc_id)
        REFERENCES npc_profiles (npc_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2) Nothing else to do - existing AI-enabled NPCs keep behaving exactly
--    like 1.10 (stationary, proximity/click-triggered conversation) until
--    you opt individual NPCs into autonomous movement:
--
--    /aihuman autonomous <npcId> on
--
-- This captures the NPC's current position as its "home" point and starts
-- it wandering within interaction.autonomous.default-wander-radius (or its
-- own override, set via /aihuman wander <npcId> <radius> <min> <max>).
