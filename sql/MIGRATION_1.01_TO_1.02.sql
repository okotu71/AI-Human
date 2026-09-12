-- =========================================================
-- AI-Human - data migration 1.01 -> 1.02
-- =========================================================
-- 1.02 renames/redesigns the character table (npc_character -> npc_profiles,
-- richer fields) and the dialog table (npc_conversation_log -> npc_dialog_history,
-- renamed columns), and adds four brand new tables. This is NOT a simple
-- ALTER TABLE: run this AFTER the plugin has started once on 1.02 (so the
-- new tables already exist), against the same database your 1.01 install used.
--
-- Adjust the database name below if needed (run "USE your_database;" first,
-- or edit the statements) - assumes an empty table prefix throughout.

-- 1) Migrate character sheets: npc_character -> npc_profiles
--    1.01 had: npc_id, nome, backstory, personalita, tratti_json, model
--    1.02 splits "backstory" into background, and has new structured fields
--    (role, village, profession, speech_style) that 1.01 never captured -
--    they come across empty/NULL and you'll want to fill them in via
--    /aihuman profile ... afterwards.
INSERT INTO npc_profiles (npc_id, name, role, personality, background, village, profession, speech_style, model)
SELECT npc_id, nome, '', personalita, backstory, NULL, NULL, NULL, model
FROM npc_character
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- 2) Migrate dialog history: npc_conversation_log -> npc_dialog_history
--    1.01 had: id, npc_id, player_uuid, ruolo, messaggio, ts
--    1.02 renames ruolo -> speaker, messaggio -> message (same values/enum).
INSERT INTO npc_dialog_history (npc_id, player_uuid, speaker, message, ts)
SELECT npc_id, player_uuid, ruolo, messaggio, ts
FROM npc_conversation_log;

-- 3) Seed npc_player_memory from what we can infer out of the migrated
--    dialog history (last_seen = most recent message per pair). Everything
--    else (relationship_score, summary, known_name) starts at defaults -
--    1.01 never tracked those, so there is nothing to carry over.
INSERT INTO npc_player_memory (npc_id, player_uuid, last_seen, messages_since_summary)
SELECT npc_id, player_uuid, MAX(ts), COUNT(*)
FROM npc_dialog_history
GROUP BY npc_id, player_uuid
ON DUPLICATE KEY UPDATE last_seen = VALUES(last_seen);

-- 4) Give every migrated NPC a default emotional state row (npc_state has
--    no 1.01 equivalent to migrate from).
INSERT INTO npc_state (npc_id)
SELECT npc_id FROM npc_profiles
ON DUPLICATE KEY UPDATE npc_id = npc_id;

-- 5) Once you've verified the data above looks correct, drop the old 1.01
--    tables (NOT done automatically - review first!):
-- DROP TABLE npc_conversation_log;
-- DROP TABLE npc_character;
