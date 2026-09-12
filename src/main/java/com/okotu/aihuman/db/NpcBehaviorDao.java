package com.okotu.aihuman.db;

import com.okotu.aihuman.model.NpcBehaviorConfig;
import com.okotu.aihuman.model.NpcBehaviorType;
import org.bukkit.Location;
import org.bukkit.World;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Access to npc_behavior_config (autonomous movement settings). All methods
 * are blocking: always call them from an async thread (never from the
 * server main thread) - except where noted.
 */
public class NpcBehaviorDao {

    private final Database database;
    private final String table;

    public NpcBehaviorDao(Database database) {
        this.database = database;
        this.table = database.table("npc_behavior_config");
    }

    public Optional<NpcBehaviorConfig> find(int npcId) throws SQLException {
        String sql = "SELECT npc_id, autonomous, behavior_type, home_world, home_x, home_y, home_z, "
                + "wander_radius, wander_min_distance, wander_max_distance, detection_radius, "
                + "avoid_lava, avoid_deep_water, avoid_cliffs, avoid_fire FROM " + table + " WHERE npc_id = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(fromRow(rs));
            }
        }
    }

    public NpcBehaviorConfig findOrDefault(int npcId) throws SQLException {
        return find(npcId).orElseGet(() -> NpcBehaviorConfig.disabledDefault(npcId));
    }

    /**
     * Turns autonomous movement on, capturing {@code homeLocation} as the
     * wander anchor point (only used the first time - re-enabling an
     * already-configured NPC keeps its existing home/radius/etc).
     */
    public void enableAutonomous(int npcId, Location homeLocation) throws SQLException {
        Optional<NpcBehaviorConfig> existing = find(npcId);
        if (existing.isPresent()) {
            setAutonomous(npcId, true);
            return;
        }
        World world = homeLocation.getWorld();
        String sql = "INSERT INTO " + table
                + " (npc_id, autonomous, behavior_type, home_world, home_x, home_y, home_z) "
                + "VALUES (?, 1, 'WANDER', ?, ?, ?, ?)";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, npcId);
            ps.setString(2, world != null ? world.getName() : null);
            ps.setDouble(3, homeLocation.getX());
            ps.setDouble(4, homeLocation.getY());
            ps.setDouble(5, homeLocation.getZ());
            ps.executeUpdate();
        }
    }

    public void setAutonomous(int npcId, boolean autonomous) throws SQLException {
        String sql = "UPDATE " + table + " SET autonomous = ? WHERE npc_id = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, autonomous);
            ps.setInt(2, npcId);
            ps.executeUpdate();
        }
    }

    public void setBehaviorType(int npcId, NpcBehaviorType type) throws SQLException {
        String sql = "UPDATE " + table + " SET behavior_type = ? WHERE npc_id = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type.name());
            ps.setInt(2, npcId);
            ps.executeUpdate();
        }
    }

    public void setWanderParams(int npcId, int radius, int minDistance, int maxDistance) throws SQLException {
        String sql = "UPDATE " + table
                + " SET wander_radius = ?, wander_min_distance = ?, wander_max_distance = ? WHERE npc_id = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, radius);
            ps.setInt(2, minDistance);
            ps.setInt(3, maxDistance);
            ps.setInt(4, npcId);
            ps.executeUpdate();
        }
    }

    public void setDetectionRadius(int npcId, double radius) throws SQLException {
        String sql = "UPDATE " + table + " SET detection_radius = ? WHERE npc_id = ?";
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, radius);
            ps.setInt(2, npcId);
            ps.executeUpdate();
        }
    }

    /** All npc_ids currently marked autonomous. Used at startup/reload to seed the in-RAM controller set. */
    public java.util.List<Integer> findAutonomousNpcIds() throws SQLException {
        String sql = "SELECT npc_id FROM " + table + " WHERE autonomous = 1";
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getInt("npc_id"));
            }
        }
        return ids;
    }

    private NpcBehaviorConfig fromRow(ResultSet rs) throws SQLException {
        return new NpcBehaviorConfig(
                rs.getInt("npc_id"),
                rs.getBoolean("autonomous"),
                NpcBehaviorType.valueOf(rs.getString("behavior_type")),
                rs.getString("home_world"),
                getNullableDouble(rs, "home_x"),
                getNullableDouble(rs, "home_y"),
                getNullableDouble(rs, "home_z"),
                getNullableInt(rs, "wander_radius"),
                getNullableInt(rs, "wander_min_distance"),
                getNullableInt(rs, "wander_max_distance"),
                getNullableDouble(rs, "detection_radius"),
                getNullableBoolean(rs, "avoid_lava"),
                getNullableBoolean(rs, "avoid_deep_water"),
                getNullableBoolean(rs, "avoid_cliffs"),
                getNullableBoolean(rs, "avoid_fire")
        );
    }

    private Double getNullableDouble(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    private Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private Boolean getNullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean v = rs.getBoolean(column);
        return rs.wasNull() ? null : v;
    }
}
