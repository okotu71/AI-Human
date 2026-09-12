package com.okotu.aihuman.db;

import com.okotu.aihuman.model.VillageEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Access to village_events: memory shared by every NPC belonging to the same
 * village. All methods are blocking: always call them from an async thread.
 */
public class VillageEventDao {

    private final Database database;
    private final String table;

    public VillageEventDao(Database database) {
        this.database = database;
        this.table = database.table("village_events");
    }

    /** Active (non-expired) events for a village, highest priority first, capped at {@code limit}. */
    public List<VillageEvent> findActive(String village, int limit) throws SQLException {
        String sql = "SELECT id, village, priority, summary, expires FROM " + table + " "
                + "WHERE village = ? AND (expires IS NULL OR expires > NOW()) "
                + "ORDER BY priority DESC, id DESC LIMIT ?";
        List<VillageEvent> events = new ArrayList<>();
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, village);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.add(fromRow(rs));
                }
            }
        }
        return events;
    }

    public long add(String village, int priority, String summary, Instant expires) throws SQLException {
        String sql = "INSERT INTO " + table + " (village, priority, summary, expires) VALUES (?, ?, ?, ?)";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, village);
            ps.setInt(2, priority);
            ps.setString(3, summary);
            if (expires != null) {
                ps.setTimestamp(4, Timestamp.from(expires));
            } else {
                ps.setNull(4, java.sql.Types.TIMESTAMP);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        }
    }

    public boolean remove(long id) throws SQLException {
        String sql = "DELETE FROM " + table + " WHERE id = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    /** Deletes events past their expiry date. Run periodically alongside the dialog cleanup job. */
    public int deleteExpired() throws SQLException {
        String sql = "DELETE FROM " + table + " WHERE expires IS NOT NULL AND expires < NOW()";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            return ps.executeUpdate();
        }
    }

    private VillageEvent fromRow(ResultSet rs) throws SQLException {
        Timestamp expires = rs.getTimestamp("expires");
        return new VillageEvent(
                rs.getLong("id"),
                rs.getString("village"),
                rs.getInt("priority"),
                rs.getString("summary"),
                expires != null ? expires.toInstant() : null
        );
    }
}
