-- =========================================================
-- okotu-npc-ai-engine v1.02 - PRODUCTION database (okotu_npc_ai)
-- =========================================================
-- Manual reference copy (empty mysql-table-prefix, the default). The plugin
-- also applies this schema automatically on startup. If you use a table
-- prefix, prepend it to every table name and to the FK/constraint names.

CREATE DATABASE IF NOT EXISTS okotu_npc_ai
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE okotu_npc_ai;

CREATE TABLE IF NOT EXISTS npc_profiles (
    npc_id        INT UNSIGNED NOT NULL,
    name          VARCHAR(64)  NOT NULL,
    role          VARCHAR(64)  NOT NULL DEFAULT '',
    personality   TEXT         NOT NULL,
    background    TEXT         NOT NULL,
    village       VARCHAR(64)  NULL,
    profession    VARCHAR(64)  NULL,
    speech_style  VARCHAR(128) NULL,
    knowledge     TEXT         NULL,
    system_prompt TEXT         NULL,
    enabled       TINYINT(1)   NOT NULL DEFAULT 0,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (npc_id),
    KEY idx_village (village)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_player_memory (
    npc_id                  INT UNSIGNED NOT NULL,
    player_uuid             CHAR(36)     NOT NULL,
    relationship_score      SMALLINT     NOT NULL DEFAULT 0,
    known_name              VARCHAR(32)  NULL,
    notes                   TEXT         NULL,
    summary                 TEXT         NULL,
    messages_since_summary  INT UNSIGNED NOT NULL DEFAULT 0,
    last_seen               DATETIME     NULL,
    created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (npc_id, player_uuid),
    CONSTRAINT fk_memory_npc FOREIGN KEY (npc_id)
        REFERENCES npc_profiles (npc_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_dialog_history (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    npc_id       INT UNSIGNED NOT NULL,
    player_uuid  CHAR(36)     NOT NULL,
    speaker      ENUM('player', 'npc') NOT NULL,
    message      TEXT         NOT NULL,
    ts           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_npc_player_ts (npc_id, player_uuid, ts),
    CONSTRAINT fk_dialog_npc FOREIGN KEY (npc_id)
        REFERENCES npc_profiles (npc_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS village_events (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    village    VARCHAR(64)  NOT NULL,
    priority   TINYINT UNSIGNED NOT NULL DEFAULT 1,
    summary    VARCHAR(500) NOT NULL,
    expires    DATETIME     NULL,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_village_expires (village, expires)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_knowledge (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    npc_id     INT UNSIGNED NOT NULL,
    topic      VARCHAR(100) NOT NULL,
    text       TEXT         NOT NULL,
    updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_npc_topic (npc_id, topic),
    CONSTRAINT fk_knowledge_npc FOREIGN KEY (npc_id)
        REFERENCES npc_profiles (npc_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_state (
    npc_id     INT UNSIGNED NOT NULL,
    happiness  TINYINT UNSIGNED NOT NULL DEFAULT 70,
    fear       TINYINT UNSIGNED NOT NULL DEFAULT 10,
    anger      TINYINT UNSIGNED NOT NULL DEFAULT 10,
    fatigue    TINYINT UNSIGNED NOT NULL DEFAULT 20,
    hunger     TINYINT UNSIGNED NOT NULL DEFAULT 20,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (npc_id),
    CONSTRAINT fk_state_npc FOREIGN KEY (npc_id)
        REFERENCES npc_profiles (npc_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Example dedicated user (adjust host/password, then match config.yml "prod" block):
-- CREATE USER 'usr'@'%' IDENTIFIED BY 'psw';
-- GRANT ALL PRIVILEGES ON okotu_npc_ai.* TO 'usr'@'%';
-- FLUSH PRIVILEGES;
