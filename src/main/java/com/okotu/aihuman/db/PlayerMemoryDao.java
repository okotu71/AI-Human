package com.okotu.aihuman.db;

import com.okotu.aihuman.model.PlayerMemory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Access to npc_player_memory. All methods are blocking: always call them
 * from an async thread (never from the server main thread).
 */
public class PlayerMemoryDao {

    private final Database database;
    private final String table;
    private final int defaultScore;

    public PlayerMemoryDao(Database database, int defaultScore) {
        this.database = database;
        this.table = database.table("npc_player_memory");
        this.defaultScore = defaultScore;
    }

    public Optional<PlayerMemory> find(int npcId, UUID playerUuid) throws SQLException {
        String sql = "SELECT npc_id, player_uuid, relationship_score, known_name, notes, summary, "
                + "messages_since_summary, last_seen FROM " + table + " WHERE npc_id = ? AND player_uuid = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            ps.setString(2, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(fromRow(rs));
            }
        }
    }

    public PlayerMemory findOrCreate(int npcId, UUID playerUuid) throws SQLException {
        Optional<PlayerMemory> existing = find(npcId, playerUuid);
        if (existing.isPresent()) {
            return existing.get();
        }
        PlayerMemory fresh = PlayerMemory.defaultFor(npcId, playerUuid, defaultScore);
        String sql = "INSERT INTO " + table + " (npc_id, player_uuid, relationship_score) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE npc_id = npc_id";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            ps.setString(2, playerUuid.toString());
            ps.setInt(3, defaultScore);
            ps.executeUpdate();
        }
        return fresh;
    }

    /** Marks a visit: updates last_seen (=now) and known_name if provided/changed. */
    public void touch(int npcId, UUID playerUuid, String knownName) throws SQLException {
        findOrCreate(npcId, playerUuid);
        String sql = "UPDATE " + table + " SET last_seen = ?, known_name = COALESCE(?, known_name) "
                + "WHERE npc_id = ? AND player_uuid = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, knownName);
            ps.setInt(3, npcId);
            ps.setString(4, playerUuid.toString());
            ps.executeUpdate();
        }
    }

    /** Adds {@code delta} to the relationship score, clamped to [min, max]. Returns the new score. */
    public int adjustRelationship(int npcId, UUID playerUuid, int delta, int min, int max) throws SQLException {
        findOrCreate(npcId, playerUuid);
        String sql = "UPDATE " + table
                + " SET relationship_score = LEAST(?, GREATEST(?, relationship_score + ?)) "
                + "WHERE npc_id = ? AND player_uuid = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, max);
            ps.setInt(2, min);
            ps.setInt(3, delta);
            ps.setInt(4, npcId);
            ps.setString(5, playerUuid.toString());
            ps.executeUpdate();
        }
        return find(npcId, playerUuid).map(PlayerMemory::relationshipScore).orElse(defaultScore);
    }

    public void incrementMessagesSinceSummary(int npcId, UUID playerUuid, int by) throws SQLException {
        String sql = "UPDATE " + table + " SET messages_since_summary = messages_since_summary + ? "
                + "WHERE npc_id = ? AND player_uuid = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, by);
            ps.setInt(2, npcId);
            ps.setString(3, playerUuid.toString());
            ps.executeUpdate();
        }
    }

    /** Applied after a successful memory compression: saves the new summary, resets the counter. */
    public void applySummary(int npcId, UUID playerUuid, String newSummary) throws SQLException {
        String sql = "UPDATE " + table + " SET summary = ?, messages_since_summary = 0 "
                + "WHERE npc_id = ? AND player_uuid = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newSummary);
            ps.setInt(2, npcId);
            ps.setString(3, playerUuid.toString());
            ps.executeUpdate();
        }
    }

    public void updateNotes(int npcId, UUID playerUuid, String notes) throws SQLException {
        findOrCreate(npcId, playerUuid);
        String sql = "UPDATE " + table + " SET notes = ? WHERE npc_id = ? AND player_uuid = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, notes);
            ps.setInt(2, npcId);
            ps.setString(3, playerUuid.toString());
            ps.executeUpdate();
        }
    }

    private PlayerMemory fromRow(ResultSet rs) throws SQLException {
        Timestamp lastSeen = rs.getTimestamp("last_seen");
        return new PlayerMemory(
                rs.getInt("npc_id"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getInt("relationship_score"),
                rs.getString("known_name"),
                rs.getString("notes"),
                rs.getString("summary"),
                rs.getInt("messages_since_summary"),
                lastSeen != null ? lastSeen.toInstant() : null
        );
    }
}
