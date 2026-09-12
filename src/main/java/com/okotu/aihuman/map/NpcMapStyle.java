package com.okotu.aihuman.map;

import org.bukkit.plugin.Plugin;

import java.awt.Color;

/**
 * Reads the npc-map.stationary/autonomous/tooltip section of config.yml
 * and builds the per-NPC visual style and tooltip for the Pl3xMap overlay -
 * mirrors mc-safeguard's ZoneStyle, adapted for two marker variants
 * (stationary vs autonomous NPCs) instead of one.
 *
 * <p>Reads {@code plugin.getConfig()} fresh on every call rather than
 * caching a FileConfiguration reference, since {@code JavaPlugin#reloadConfig()}
 * swaps in a brand new FileConfiguration object - a cached reference would
 * silently go stale after /aihuman reload.
 */
public class NpcMapStyle {

    private final Plugin plugin;

    public NpcMapStyle(Plugin plugin) {
        this.plugin = plugin;
    }

    public Pl3xMapIntegration.PolygonStyle forNpc(String displayName, boolean autonomous) {
        var config = plugin.getConfig();
        String prefix = autonomous ? "npc-map.autonomous" : "npc-map.stationary";

        boolean borderEnabled = config.getBoolean(prefix + ".border.enabled", true);
        int borderWidth = config.getInt(prefix + ".border.width", 2);
        Color borderColor = parseColor(config.getString(prefix + ".border.color", "#3388FF"));

        boolean fillEnabled = config.getBoolean(prefix + ".fill.enabled", true);
        double fillOpacity = config.getDouble(prefix + ".fill.opacity", 0.5);
        Color fillColor = parseColor(config.getString(prefix + ".fill.color", "#3388FF"));

        boolean tooltipEnabled = config.getBoolean("npc-map.tooltip.enabled", true);
        String tooltip = tooltipEnabled ? tooltipHtml(displayName, autonomous) : null;

        return new Pl3xMapIntegration.PolygonStyle(
                borderEnabled, borderColor, borderWidth,
                fillEnabled, fillColor, fillOpacity,
                tooltip
        );
    }

    private String tooltipHtml(String displayName, boolean autonomous) {
        StringBuilder sb = new StringBuilder();
        sb.append(escape(displayName)).append("<br>");
        sb.append(autonomous ? "Autonomous NPC" : "AI NPC");
        return sb.toString();
    }

    private Color parseColor(String hex) {
        try {
            return Color.decode(hex);
        } catch (NumberFormatException e) {
            return new Color(0x33, 0x88, 0xFF);
        }
    }

    private String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
