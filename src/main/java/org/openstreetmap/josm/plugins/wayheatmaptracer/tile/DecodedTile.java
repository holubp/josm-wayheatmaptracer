package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import java.awt.image.BufferedImage;

/**
 * Pure decoder/classifier output for one response body.
 *
 * @param status structured classification status
 * @param image decoded image when available
 * @param quality controlled quality evidence
 * @param safeReasonCode controlled diagnostic reason
 * @param safeMessage controlled user-facing detail
 */
public record DecodedTile(TileFetchStatus status, BufferedImage image, TileQuality quality,
                          String safeReasonCode, String safeMessage) {
    /**
     * Returns whether the image is valid for sampling or visualization.
     *
     * @return true for a validated network image
     */
    public boolean usable() {
        return status == TileFetchStatus.SUCCESS_NETWORK && image != null;
    }
}
