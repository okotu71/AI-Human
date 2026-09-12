-- =========================================================
-- AI-Human - data migration 1.02/1.03 -> 1.04
-- =========================================================
-- 1.04 removes the per-NPC "model" override: every NPC now uses whatever
-- ollama.default-model is configured in config.yml (change it there, e.g.
-- to "gemma3:1b", to switch every NPC's model at once).
--
-- This is a genuine schema change - a real ALTER TABLE, not just a rename.
-- If any NPC had a non-default per-NPC model set in 1.02/1.03, review the
-- SELECT below BEFORE dropping the column, since that information is not
-- migrated anywhere (there's nowhere left to put a per-NPC value).

-- 1) Check which NPCs had a custom model set, so you can note them down
--    before the column disappears (e.g. to move heavier NPCs' traffic to a
--    stronger global default, or accept they'll now use the same model as
--    everyone else):
SELECT npc_id, name, model
FROM npc_profiles
WHERE model IS NOT NULL;

-- 2) Drop the column (adjust the table name if you're using a
--    mysql-table-prefix, e.g. yourprefix_npc_profiles):
ALTER TABLE npc_profiles
    DROP COLUMN model;
