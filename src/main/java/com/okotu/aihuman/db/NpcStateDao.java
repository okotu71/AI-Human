package com.okotu.aihuman.db;

import com.okotu.aihuman.model.NpcState;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Access to npc_state: emotional state of an NPC (0-100 scales), one row
 * per NPC. All methods are blocking: always call them from an async thread.
 */
public class NpcStateDao {

    private final Database database;
    private final String table;

    public NpcStateDao(Database database) {
        this.database = database;
        this.table = database.table("npc_state");
    }

    public Optional<NpcState> find(int npcId) throws SQLException {
        String sql = "SELECT npc_id, happiness, fear, anger, fatigue, hunger FROM " + table
                + " WHERE npc_id = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new NpcState(
                        rs.getInt("npc_id"), rs.getInt("happiness"), rs.getInt("fear"),
                        rs.getInt("anger"), rs.getInt("fatigue"), rs.getInt("hunger")));
            }
        }
    }

    public NpcState findOrCreate(int npcId) throws SQLException {
        Optional<NpcState> existing = find(npcId);
        if (existing.isPresent()) {
            return existing.get();
        }
        NpcState fresh = NpcState.defaultFor(npcId);
        String sql = "INSERT INTO " + table + " (npc_id, happiness, fear, anger, fatigue, hunger) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE npc_id = npc_id";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            ps.setInt(2, fresh.happiness());
            ps.setInt(3, fresh.fear());
            ps.setInt(4, fresh.anger());
            ps.setInt(5, fresh.fatigue());
            ps.setInt(6, fresh.hunger());
            ps.executeUpdate();
        }
        return fresh;
    }

    /** Updates any subset of fields (pass null to leave a field unchanged). Values are clamped to 0-100. */
    public void update(int npcId, Integer happiness, Integer fear, Integer anger, Integer fatigue, Integer hunger)
            throws SQLException {
        findOrCreate(npcId);
        StringBuilder sql = new StringBuilder("UPDATE ").append(table).append(" SET ");
        java.util.List<Object> params = new java.util.ArrayList<>();
        appendIfPresent(sql, params, "happiness", happiness);
        appendIfPresent(sql, params, "fear", fear);
        appendIfPresent(sql, params, "anger", anger);
        appendIfPresent(sql, params, "fatigue", fatigue);
        appendIfPresent(sql, params, "hunger", hunger);
        if (params.isEmpty()) {
            return;
        }
        sql.setLength(sql.length() - 2); // trim trailing ", "
        sql.append(" WHERE npc_id = ?");
        params.add(npcId);

        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ps.executeUpdate();
        }
    }

    private void appendIfPresent(StringBuilder sql, java.util.List<Object> params, String column, Integer value) {
        if (value == null) return;
        int clamped = Math.max(0, Math.min(100, value));
        sql.append(column).append(" = ?, ");
        params.add(clamped);
    }
}
