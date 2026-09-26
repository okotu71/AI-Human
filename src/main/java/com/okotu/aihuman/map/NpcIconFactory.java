package com.okotu.aihuman.map;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generates a small "person" icon (a round head over a rounded body, like a
 * map-pin silhouette) at runtime via Graphics2D - no bundled image asset, no
 * network fetch, no NPC skin extraction. This is a stylized, distinctly
 * colored marker so an NPC reads as "a person" instead of a bare rectangle,
 * and so autonomous/stationary NPCs are visually distinguishable from each
 * other - it is NOT an attempt at showing the NPC's actual face/skin.
 * Getting an NPC's real skin texture onto the map (e.g. for Citizens NPCs
 * using a player skin trait) is meaningfully more work - fetching/caching
 * the texture, cropping the head, keeping it in sync if the NPC's skin
 * changes - and hasn't been attempted here; ask if you want that as a
 * follow-up.
 *
 * <p>1.19+: also loads a fixed, bundled brand icon
 * ({@code icons/npc-marker-icon.png} in the jar) via {@link #customIcon},
 * used instead of the generated silhouette when
 * {@code npc-map.custom-icon.enabled} is true - see {@code NpcMapSync}.
 */
public final class NpcIconFactory {

    private static BufferedImage cachedCustomIcon;
    private static boolean customIconLoadAttempted = false;

    private NpcIconFactory() {
    }

    /**
     * Loads {@code icons/npc-marker-icon.png} from the plugin jar once and
     * caches the result (including a cached "not found/unreadable" outcome,
     * so a broken resource doesn't retry a classpath lookup every map
     * refresh cycle). Returns null if the resource is missing or isn't a
     * valid image - callers should fall back to {@link #personIcon} in that
     * case exactly like any other "Pl3xMap feature unavailable" fallback in
     * this integration.
     */
    public static synchronized BufferedImage customIcon(Logger logger) {
        if (customIconLoadAttempted) {
            return cachedCustomIcon;
        }
        customIconLoadAttempted = true;
        try (InputStream in = NpcIconFactory.class.getClassLoader().getResourceAsStream("icons/npc-marker-icon.png")) {
            if (in == null) {
                logger.warning("[AI-Human] npc-map.custom-icon.enabled is true but icons/npc-marker-icon.png "
                        + "wasn't found in the jar - falling back to the generated person icon.");
                return null;
            }
            cachedCustomIcon = ImageIO.read(in);
            if (cachedCustomIcon == null) {
                logger.warning("[AI-Human] icons/npc-marker-icon.png couldn't be decoded as an image - "
                        + "falling back to the generated person icon.");
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "[AI-Human] Failed to load icons/npc-marker-icon.png - "
                    + "falling back to the generated person icon.", e);
            cachedCustomIcon = null;
        }
        return cachedCustomIcon;
    }

    public static BufferedImage personIcon(Color color, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int headDiameter = (int) Math.round(size * 0.45);
            int headX = (size - headDiameter) / 2;
            int headY = (int) Math.round(size * 0.05);

            int bodyWidth = (int) Math.round(size * 0.62);
            int bodyHeight = (int) Math.round(size * 0.42);
            int bodyX = (size - bodyWidth) / 2;
            int bodyY = (int) Math.round(size * 0.50);
            int bodyArc = Math.max(4, size / 6);

            // Thin dark outline first, so the colored fill on top reads clearly
            // against any map background/zoom level.
            g.setColor(Color.BLACK);
            g.fillOval(headX - 1, headY - 1, headDiameter + 2, headDiameter + 2);
            g.fillRoundRect(bodyX - 1, bodyY - 1, bodyWidth + 2, bodyHeight + 2, bodyArc, bodyArc);

            g.setColor(color);
            g.fillOval(headX, headY, headDiameter, headDiameter);
            g.fillRoundRect(bodyX, bodyY, bodyWidth, bodyHeight, bodyArc, bodyArc);
        } finally {
            g.dispose();
        }
        return image;
    }
}
