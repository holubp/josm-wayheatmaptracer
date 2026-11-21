package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.awt.image.BufferedImage;
import java.util.OptionalDouble;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;

/** Slide-time metadata only. The ordinary Legacy result remains the owner of its existing raster. */
public record LegacyVisibleSource(double minimumEast, double minimumNorth,
        double maximumEast, double maximumNorth, double projectionUnitsPerViewPixel,
        double groundMetersPerViewPixel, OptionalDouble nativePitchMeters,
        String projectionCode, VisibleSourceEpoch.Receipt sourceReceipt) {
    public LegacyVisibleSource {
        if (!Double.isFinite(minimumEast) || !Double.isFinite(minimumNorth)
                || !Double.isFinite(maximumEast) || !Double.isFinite(maximumNorth)
                || minimumEast >= maximumEast || minimumNorth >= maximumNorth
                || !Double.isFinite(projectionUnitsPerViewPixel) || projectionUnitsPerViewPixel <= 0
                || !Double.isFinite(groundMetersPerViewPixel) || groundMetersPerViewPixel <= 0
                || projectionCode == null || projectionCode.isBlank()) {
            throw new IllegalArgumentException("Original visible capture metadata is incomplete");
        }
        nativePitchMeters = nativePitchMeters == null ? OptionalDouble.empty() : nativePitchMeters;
        if (nativePitchMeters.isPresent() && (!Double.isFinite(nativePitchMeters.getAsDouble())
                || nativePitchMeters.getAsDouble() <= 0)) {
            throw new IllegalArgumentException("Original native pitch is invalid");
        }
    }
    /** Freezes pixels only for an explicit operation, after validating the original source receipt. */
    LiveBPreviewService.VisibleRaster freeze(BufferedImage raster, String sourceIdentity) {
        if (sourceReceipt == null) {
            throw new IllegalStateException("The original visible source has no unchanged-pixel receipt");
        }
        sourceReceipt.owner().requireCurrent(sourceReceipt);
        if (raster == null) throw new IllegalStateException("The original visible capture is unavailable");
        int[] pixels = raster.getRGB(0, 0, raster.getWidth(), raster.getHeight(), null, 0, raster.getWidth());
        sourceReceipt.owner().requireCurrent(sourceReceipt);
        return new LiveBPreviewService.VisibleRaster(raster.getWidth(), raster.getHeight(), pixels,
                minimumEast, minimumNorth, maximumEast, maximumNorth, projectionUnitsPerViewPixel,
                groundMetersPerViewPixel, nativePitchMeters, sourceIdentity, projectionCode, sourceReceipt);
    }
}
