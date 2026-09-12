-- =========================================================
-- AI-Human - MySQL schema (template) - v1.02
-- =========================================================
-- Applied automatically by Database.java on startup (CREATE TABLE IF NOT
-- EXISTS), against whichever database the active profile (prod/test) points
-- to. {{PREFIX}} is replaced at runtime with "mysql-table-prefix".
--
-- Coming from 1.01 (npc_character / npc_conversation_log)? See
-- MIGRATION_1.01_TO_1.02.sql and the README "Migrating from 1.01 to 1.02"
-- section: that is a real data migration, not just a rename.
--
-- Coming from 1.02/1.03 (npc_profiles still has a "model" column)? See
-- MIGRATION_1.03_TO_1.04.sql: 1.04 drops that column, the Ollama model is
-- now chosen once, globally, via ollama.default-model in config.yml.
--
-- Coming from before 1.06 (npc_profiles has no "enabled" column)? See
-- MIGRATION_1.05_TO_1.06.sql: 1.06 makes AI chat opt-in per NPC (new column,
-- defaults to disabled) instead of every NPC talking automatically.

-- ---------------------------------------------------------
-- 1) npc_profiles - the "character sheet", changes rarely
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS {{PREFIX}}npc_profiles (
    npc_id        INT UNSIGNED NOT NULL,
    name          VARCHAR(64)  NOT NULL,
    role          VARCHAR(64)  NOT NULL DEFAULT '',
    personality   TEXT         NOT NULL,    -- MySQL forbids a literal DEFAULT on TEXT/BLOB/JSON columns;
                                             -- NpcProfileDao.insert() always supplies a value explicitly
    background    TEXT         NOT NULL,
    village       VARCHAR(64)  NULL,
    profession    VARCHAR(64)  NULL,
    speech_style  VARCHAR(128) NULL,
    knowledge     TEXT         NULL,        -- free-text summary; structured facts live in npc_knowledge
    system_prompt TEXT         NULL,        -- if set, used verbatim instead of auto-building from the fields above
    enabled       TINYINT(1)   NOT NULL DEFAULT 0,  -- AI chat is opt-in: 0 = NPC won't talk at all until enabled
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (npc_id),
    KEY idx_village (village)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------
-- 2) npc_player_memory - one row per (npc, player) relationship
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS {{PREFIX}}npc_player_memory (
    npc_id                  INT UNSIGNED NOT NULL,
    player_uuid             CHAR(36)     NOT NULL,
    relationship_score      SMALLINT     NOT NULL DEFAULT 0,   -- -100 (hatred) .. 100 (best friend)
    known_name              VARCHAR(32)  NULL,                 -- player's name as the NPC knows them
    notes                   TEXT         NULL,                 -- free-form admin/plugin notes
    summary                 TEXT         NULL,                 -- compressed long-term memory (see npc_dialog_history)
    messages_since_summary  INT UNSIGNED NOT NULL DEFAULT 0,   -- counter driving the compression trigger
    last_seen               DATETIME     NULL,
    created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (npc_id, player_uuid),
    CONSTRAINT {{PREFIX}}fk_memory_npc FOREIGN KEY (npc_id)
        REFERENCES {{PREFIX}}npc_profiles (npc_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------
-- 3) npc_dialog_history - raw recent turns, kept short on purpose
-- ---------------------------------------------------------
-- Not meant to grow forever: SummaryService compresses+deletes every
-- "summary-trigger-messages" (default 30) messages per (npc, player) pair.
-- A periodic safety cleanup also hard-caps this table in case compression
-- ever fails (see conversation.max-raw-messages-safety in config.yml).
CREATE TABLE IF NOT EXISTS {{PREFIX}}npc_dialog_history (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    npc_id       INT UNSIGNED NOT NULL,
    player_uuid  CHAR(36)     NOT NULL,
    speaker      ENUM('player', 'npc') NOT NULL,
    message      TEXT         NOT NULL,
    ts           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_npc_player_ts (npc_id, player_uuid, ts),
    CONSTRAINT {{PREFIX}}fk_dialog_npc FOREIGN KEY (npc_id)
        REFERENCES {{PREFIX}}npc_profiles (npc_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------
-- 4) village_events - shared memory across every NPC in a village
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS {{PREFIX}}village_events (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    village    VARCHAR(64)  NOT NULL,
    priority   TINYINT UNSIGNED NOT NULL DEFAULT 1,  -- higher = more important, included first if the prompt has to trim
    summary    VARCHAR(500) NOT NULL,
    expires    DATETIME     NULL,                    -- NULL = never expires (until removed manually)
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_village_expires (village, expires)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------
-- 5) npc_knowledge - what a given NPC does/doesn't know about
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS {{PREFIX}}npc_knowledge (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    npc_id     INT UNSIGNED NOT NULL,
    topic      VARCHAR(100) NOT NULL,
    text       TEXT         NOT NULL,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_npc_topic (npc_id, topic),
    CONSTRAINT {{PREFIX}}fk_knowledge_npc FOREIGN KEY (npc_id)
        REFERENCES {{PREFIX}}npc_profiles (npc_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------
-- 6) npc_state - emotional state, one row per NPC
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS {{PREFIX}}npc_state (
    npc_id     INT UNSIGNED NOT NULL,
    happiness  TINYINT UNSIGNED NOT NULL DEFAULT 70,  -- 0-100 scales
    fear       TINYINT UNSIGNED NOT NULL DEFAULT 10,
    anger      TINYINT UNSIGNED NOT NULL DEFAULT 10,
    fatigue    TINYINT UNSIGNED NOT NULL DEFAULT 20,
    hunger     TINYINT UNSIGNED NOT NULL DEFAULT 20,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (npc_id),
    CONSTRAINT {{PREFIX}}fk_state_npc FOREIGN KEY (npc_id)
        REFERENCES {{PREFIX}}npc_profiles (npc_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------
-- 7) npc_behavior_config - autonomous movement, one row per NPC (1.11+)
-- ---------------------------------------------------------
-- Separate from npc_profiles (the character sheet) on purpose: this is
-- "how the NPC behaves in the world", not "who the NPC is". NULL on any
-- of the nullable columns means "use the interaction.autonomous.* default
-- from config.yml" rather than a per-NPC override.
CREATE TABLE IF NOT EXISTS {{PREFIX}}npc_behavior_config (
    npc_id               INT UNSIGNED NOT NULL,
    autonomous           TINYINT(1)   NOT NULL DEFAULT 0,  -- master per-NPC switch, see /aihuman autonomous
    behavior_type        ENUM('WANDER','VILLAGE','TRAVEL','GUARD','FOLLOW') NOT NULL DEFAULT 'WANDER',
    -- Only WANDER is actually implemented as of 1.11 - the others are accepted
    -- and stored for forward compatibility but currently behave like WANDER.
    home_world           VARCHAR(64)  NULL,      -- captured automatically when autonomous is turned on
    home_x               DOUBLE       NULL,
    home_y               DOUBLE       NULL,
    home_z               DOUBLE       NULL,
    wander_radius        INT UNSIGNED NULL,      -- max distance from home the NPC will roam
    wander_min_distance  INT UNSIGNED NULL,      -- min distance per hop
    wander_max_distance  INT UNSIGNED NULL,      -- max distance per hop
    detection_radius     DOUBLE       NULL,      -- overrides interaction.proximity.radius for this NPC's own perception
    avoid_lava           TINYINT(1)   NULL,
    avoid_deep_water     TINYINT(1)   NULL,
    avoid_cliffs         TINYINT(1)   NULL,
    avoid_fire           TINYINT(1)   NULL,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (npc_id),
    CONSTRAINT {{PREFIX}}fk_behavior_npc FOREIGN KEY (npc_id)
        REFERENCES {{PREFIX}}npc_profiles (npc_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------
-- Reference queries (documentation only, not executed automatically)
-- ---------------------------------------------------------
-- Safety cap on npc_dialog_history in case SummaryService ever fails to
-- compress+delete in time (keeps at most N rows per npc/player pair):
--
-- DELETE c FROM {{PREFIX}}npc_dialog_history c
-- JOIN (
--     SELECT id,
--            ROW_NUMBER() OVER (PARTITION BY npc_id, player_uuid ORDER BY ts DESC, id DESC) AS rn
--     FROM {{PREFIX}}npc_dialog_history
-- ) ranked ON ranked.id = c.id
-- WHERE ranked.rn > 90;
--
-- Expired village events cleanup:
-- DELETE FROM {{PREFIX}}village_events WHERE expires IS NOT NULL AND expires < NOW();
