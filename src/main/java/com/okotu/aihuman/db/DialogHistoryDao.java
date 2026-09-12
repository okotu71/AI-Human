package com.okotu.aihuman.db;

import com.okotu.aihuman.model.ConversationEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Access to npc_dialog_history (raw recent turns). All methods are blocking:
 * always call them from an async thread (never from the server main thread).
 *
 * <p>This table is kept intentionally short-lived: SummaryService compresses
 * and deletes rows once {@code summary-trigger-messages} accumulate for a
 * given (npc, player) pair (see {@link #deleteAll(int, UUID)}), and
 * {@link #trimSafety(int)} is a periodic hard cap in case that ever fails.
 */
public class DialogHistoryDao {

    private final Database database;
    private final String table;

    public DialogHistoryDao(Database database) {
        this.database = database;
        this.table = database.table("npc_dialog_history");
    }

    public void insert(int npcId, UUID playerUuid, ConversationEntry entry) throws SQLException {
        String sql = "INSERT INTO " + table + " (npc_id, player_uuid, speaker, message, ts) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            ps.setString(2, playerUuid.toString());
            ps.setString(3, entry.role().dbValue());
            ps.setString(4, entry.message());
            ps.setTimestamp(5, Timestamp.from(entry.timestamp()));
            ps.executeUpdate();
        }
    }

    /** Last {@code limit} turns for the (npc, player) pair, in ascending chronological order. */
    public List<ConversationEntry> fetchLast(int npcId, UUID playerUuid, int limit) throws SQLException {
        String sql = "SELECT speaker, message, ts FROM " + table + " "
                + "WHERE npc_id = ? AND player_uuid = ? "
                + "ORDER BY ts DESC, id DESC LIMIT ?";
        List<ConversationEntry> reversed = new ArrayList<>();
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            ps.setString(2, playerUuid.toString());
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    reversed.add(new ConversationEntry(
                            ConversationEntry.Role.fromDb(rs.getString("speaker")),
                            rs.getString("message"),
                            rs.getTimestamp("ts").toInstant()
                    ));
                }
            }
        }
        Collections.reverse(reversed);
        return reversed;
    }

    /** ALL turns for the (npc, player) pair, ascending, used right before compression. */
    public List<ConversationEntry> fetchAll(int npcId, UUID playerUuid) throws SQLException {
        String sql = "SELECT speaker, message, ts FROM " + table + " "
                + "WHERE npc_id = ? AND player_uuid = ? ORDER BY ts ASC, id ASC";
        List<ConversationEntry> entries = new ArrayList<>();
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            ps.setString(2, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new ConversationEntry(
                            ConversationEntry.Role.fromDb(rs.getString("speaker")),
                            rs.getString("message"),
                            rs.getTimestamp("ts").toInstant()
                    ));
                }
            }
        }
        return entries;
    }

    /** Deletes every row for the (npc, player) pair. Called right after a successful compression. */
    public int deleteAll(int npcId, UUID playerUuid) throws SQLException {
        String sql = "DELETE FROM " + table + " WHERE npc_id = ? AND player_uuid = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            ps.setString(2, playerUuid.toString());
            return ps.executeUpdate();
        }
    }

    /**
     * Safety net only: keeps at most {@code keep} rows per (npc_id, player_uuid)
     * pair across the whole table, in case SummaryService ever falls behind
     * (e.g. Ollama down for a long time). Should rarely delete anything in
     * normal operation, since compression already clears rows well before
     * this limit is reached.
     */
    public int trimSafety(int keep) throws SQLException {
        String sql = "DELETE c FROM " + table + " c "
                + "JOIN ( "
                + "    SELECT id, "
                + "           ROW_NUMBER() OVER (PARTITION BY npc_id, player_uuid ORDER BY ts DESC, id DESC) AS rn "
                + "    FROM " + table + " "
                + ") ranked ON ranked.id = c.id "
                + "WHERE ranked.rn > ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, keep);
            return ps.executeUpdate();
        }
    }
}
