package com.okotu.aihuman.db;

import com.okotu.aihuman.model.KnowledgeEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Access to npc_knowledge: what a given NPC knows/doesn't know about. All
 * methods are blocking: always call them from an async thread.
 */
public class KnowledgeDao {

    private final Database database;
    private final String table;

    public KnowledgeDao(Database database) {
        this.database = database;
        this.table = database.table("npc_knowledge");
    }

    public List<KnowledgeEntry> findForNpc(int npcId, int limit) throws SQLException {
        String sql = "SELECT npc_id, topic, text FROM " + table + " WHERE npc_id = ? "
                + "ORDER BY topic ASC LIMIT ?";
        List<KnowledgeEntry> entries = new ArrayList<>();
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new KnowledgeEntry(rs.getInt("npc_id"), rs.getString("topic"), rs.getString("text")));
                }
            }
        }
        return entries;
    }

    /** Insert or update a knowledge entry for a topic (topic is unique per NPC). */
    public void upsert(int npcId, String topic, String text) throws SQLException {
        String sql = "INSERT INTO " + table + " (npc_id, topic, text) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE text = VALUES(text)";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            ps.setString(2, topic);
            ps.setString(3, text);
            ps.executeUpdate();
        }
    }

    public boolean remove(int npcId, String topic) throws SQLException {
        String sql = "DELETE FROM " + table + " WHERE npc_id = ? AND topic = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            ps.setString(2, topic);
            return ps.executeUpdate() > 0;
        }
    }
}
