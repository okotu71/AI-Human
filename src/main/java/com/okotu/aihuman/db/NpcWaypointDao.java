package com.okotu.aihuman.db;

import com.okotu.aihuman.model.NpcWaypoint;
import org.bukkit.Location;
import org.bukkit.World;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Access to npc_travel_waypoints. All methods are blocking: always call
 * them from an async thread (never from the server main thread).
 */
public class NpcWaypointDao {

    private final Database database;
    private final String table;

    public NpcWaypointDao(Database database) {
        this.database = database;
        this.table = database.table("npc_travel_waypoints");
    }

    /** In sequence order (visit order). */
    public List<NpcWaypoint> findForNpc(int npcId) throws SQLException {
        String sql = "SELECT id, npc_id, sequence, world, x, y, z FROM " + table
                + " WHERE npc_id = ? ORDER BY sequence ASC";
        List<NpcWaypoint> waypoints = new ArrayList<>();
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    waypoints.add(new NpcWaypoint(
                            rs.getLong("id"), rs.getInt("npc_id"), rs.getInt("sequence"),
                            rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z")));
                }
            }
        }
        return waypoints;
    }

    /** Every waypoint for every NPC, ordered by (npc_id, sequence) - used once at startup to seed the registry cache. */
    public List<NpcWaypoint> findAll() throws SQLException {
        String sql = "SELECT id, npc_id, sequence, world, x, y, z FROM " + table
                + " ORDER BY npc_id ASC, sequence ASC";
        List<NpcWaypoint> waypoints = new ArrayList<>();
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                waypoints.add(new NpcWaypoint(
                        rs.getLong("id"), rs.getInt("npc_id"), rs.getInt("sequence"),
                        rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z")));
            }
        }
        return waypoints;
    }

    /** Appends a waypoint after the current highest sequence number for this NPC (0 if none yet). */
    public void add(int npcId, Location location) throws SQLException {
        World world = location.getWorld();
        int nextSequence = nextSequence(npcId);
        String sql = "INSERT INTO " + table + " (npc_id, sequence, world, x, y, z) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            ps.setInt(2, nextSequence);
            ps.setString(3, world != null ? world.getName() : null);
            ps.setDouble(4, location.getX());
            ps.setDouble(5, location.getY());
            ps.setDouble(6, location.getZ());
            ps.executeUpdate();
        }
    }

    public void clear(int npcId) throws SQLException {
        String sql = "DELETE FROM " + table + " WHERE npc_id = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            ps.executeUpdate();
        }
    }

    private int nextSequence(int npcId) throws SQLException {
        String sql = "SELECT COALESCE(MAX(sequence), -1) + 1 FROM " + table + " WHERE npc_id = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
