-- =========================================================
-- AI-Human - data migration 1.05 -> 1.06
-- =========================================================
-- 1.06 makes AI chat opt-in per NPC: a new npc_profiles.enabled column
-- (TINYINT(1), default 0) controls whether an NPC will talk to players at
-- all. New NPCs no longer start talking automatically the first time a
-- player clicks/approaches - an admin has to explicitly run
-- /aihuman enable <npcId> first (console-friendly, no in-game NPC
-- selection needed).
--
-- IMPORTANT BEHAVIOUR CHANGE if you're upgrading with existing data: every
-- NPC that already has a row in npc_profiles from 1.02-1.05 will get
-- enabled = 0 after this migration (the column default), meaning they will
-- STOP talking until you either re-enable them one by one, or run the
-- optional bulk statement below.

-- 1) Add the column (adjust the table name if you're using a
--    mysql-table-prefix, e.g. yourprefix_npc_profiles):
ALTER TABLE npc_profiles
    ADD COLUMN enabled TINYINT(1) NOT NULL DEFAULT 0;

-- 2) OPTIONAL: if you want every NPC that was already talking under 1.05 to
--    keep talking without having to re-enable each one by hand, uncomment
--    and run this once, right after the ALTER TABLE above:
-- UPDATE npc_profiles SET enabled = 1;

-- 3) Going forward, enable/disable NPCs individually with:
--    /aihuman enable <npcId>
--    /aihuman disable <npcId>
