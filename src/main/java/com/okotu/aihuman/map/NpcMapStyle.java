package com.okotu.aihuman.map;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

import java.awt.Color;

/**
 * Reads the npc-map.stationary/autonomous/tooltip section of config.yml
 * and builds the per-NPC visual style and tooltip for the Pl3xMap overlay -
 * mirrors mc-safeguard's ZoneStyle, adapted for two marker variants
 * (stationary vs autonomous NPCs) instead of one.
 *
 * <p>As of 1.16 the tooltip is styled to look like a Minecraft player
 * nameplate (dark rounded label + a row of hearts for health) instead of
 * Pl3xMap/Leaflet's plain white default box, and shows only the NPC's name -
 * no "Autonomous NPC"/"AI NPC" suffix anymore. The dark styling is plain
 * inline CSS inside the tooltip's own HTML content (negative margins to
 * bleed over Leaflet's default white tooltip padding) - not a new Pl3xMap
 * API call, so it carries none of the "might not resolve on this version"
 * risk the icon-marker path does; worst case the negative-margin trick
 * doesn't perfectly cancel out this Pl3xMap version's own tooltip padding
 * and leaves a thin white sliver at the edges, which is a CSS tuning
 * problem, not a functional one.
 *
 * <p>Reads {@code plugin.getConfig()} fresh on every call rather than
 * caching a FileConfiguration reference, since {@code JavaPlugin#reloadConfig()}
 * swaps in a brand new FileConfiguration object - a cached reference would
 * silently go stale after /aihuman reload.
 */
public class NpcMapStyle {

    private static final int TOTAL_HEARTS = 10;

    private final Plugin plugin;

    public NpcMapStyle(Plugin plugin) {
        this.plugin = plugin;
    }

    public Pl3xMapIntegration.PolygonStyle forNpc(NPC npc, boolean autonomous) {
        var config = plugin.getConfig();
        String prefix = autonomous ? "npc-map.autonomous" : "npc-map.stationary";

        boolean borderEnabled = config.getBoolean(prefix + ".border.enabled", true);
        int borderWidth = config.getInt(prefix + ".border.width", 2);
        Color borderColor = parseColor(config.getString(prefix + ".border.color", "#3388FF"));

        boolean fillEnabled = config.getBoolean(prefix + ".fill.enabled", true);
        double fillOpacity = config.getDouble(prefix + ".fill.opacity", 0.5);
        Color fillColor = parseColor(config.getString(prefix + ".fill.color", "#3388FF"));

        boolean tooltipEnabled = config.getBoolean("npc-map.tooltip.enabled", true);
        String tooltip = tooltipEnabled ? tooltipHtml(npc) : null;

        return new Pl3xMapIntegration.PolygonStyle(
                borderEnabled, borderColor, borderWidth,
                fillEnabled, fillColor, fillOpacity,
                tooltip
        );
    }

    /** Raw fill color for a variant, used to tint the generated icon (see NpcIconFactory). */
    public Color iconColor(boolean autonomous) {
        var config = plugin.getConfig();
        String prefix = autonomous ? "npc-map.autonomous" : "npc-map.stationary";
        return parseColor(config.getString(prefix + ".fill.color", "#3388FF"));
    }

    /**
     * Name-only, styled like a Minecraft player nameplate: dark rounded
     * background, white text, a row of hearts underneath reflecting the
     * NPC's actual current/max health if its entity is a LivingEntity
     * (defaults to a full bar otherwise - most dialogue NPCs are set
     * invulnerable and effectively always "at full health" anyway).
     */
    private String tooltipHtml(NPC npc) {
        String name = escape(npc.getName());
        String hearts = heartsFor(npc);

        return "<div style=\"background:#000000cc; color:#ffffff; margin:-6px -8px; "
                + "padding:4px 8px; border-radius:3px; text-align:center; "
                + "font-family:sans-serif; font-size:12px; line-height:1.5; white-space:nowrap;\">"
                + name + "<br>" + hearts
                + "</div>";
    }

    private String heartsFor(NPC npc) {
        double ratio = healthRatioOf(npc);
        int filled = (int) Math.round(ratio * TOTAL_HEARTS);
        filled = Math.max(0, Math.min(TOTAL_HEARTS, filled));

        StringBuilder sb = new StringBuilder();
        sb.append("<span style=\"color:#ff5555;\">").append("\u2764".repeat(filled)).append("</span>");
        int empty = TOTAL_HEARTS - filled;
        if (empty > 0) {
            sb.append("<span style=\"color:#555555;\">").append("\u2764".repeat(empty)).append("</span>");
        }
        return sb.toString();
    }

    /** 1.0 (full) if the NPC isn't a damageable LivingEntity, or its max health reads as 0. */
    private double healthRatioOf(NPC npc) {
        if (!npc.isSpawned()) {
            return 1.0;
        }
        Entity entity = npc.getEntity();
        if (!(entity instanceof LivingEntity living)) {
            return 1.0;
        }
        var maxHealthAttribute = living.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double maxHealth = maxHealthAttribute != null ? maxHealthAttribute.getValue() : 0;
        if (maxHealth <= 0) {
            return 1.0;
        }
        return Math.max(0, Math.min(1, living.getHealth() / maxHealth));
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
