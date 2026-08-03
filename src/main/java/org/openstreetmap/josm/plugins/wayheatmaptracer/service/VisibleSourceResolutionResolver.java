package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.Locale;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import org.openstreetmap.gui.jmapviewer.interfaces.TileSource;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.gui.layer.AbstractTileSourceLayer;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.gui.layer.TMSLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.ManagedImageryService;

/** Resolves factual native tile resolution for a rendered imagery layer when metadata is sufficient. */
public final class VisibleSourceResolutionResolver {
    private static final int STRAVA_TILE_SIZE = 512;

    /** Creates a stateless source-resolution resolver. */
    public VisibleSourceResolutionResolver() {
    }

    /**
     * Resolves source zoom and tile size from the slide-time imagery layer.
     *
     * @param layer rendered imagery layer
     * @param latitude representative selected-way latitude
     * @return known native resolution or an explicit unknown reason
     */
    public SourceResolution resolve(ImageryLayer layer, double latitude) {
        if (!(layer instanceof AbstractTileSourceLayer<?> tileLayer)) {
            return SourceResolution.unknown("non-tile-visible-layer");
        }
        return resolveAtZoom(layer, tileLayer.getZoomLevel(), latitude);
    }

    /**
     * Resolves native resolution for an explicitly captured slide-time tile zoom.
     *
     * @param layer rendered imagery layer
     * @param sourceTileZoom tile zoom observed while the alignment raster was rendered
     * @param latitude representative selected-way latitude
     * @return known native resolution or an explicit unknown reason
     */
    public SourceResolution resolveAtZoom(ImageryLayer layer, int sourceTileZoom, double latitude) {
        if (!(layer instanceof AbstractTileSourceLayer<?>)) {
            return SourceResolution.unknown("non-tile-visible-layer");
        }
        return resolveMetadata(layer.getInfo(), sourceTileZoom, latitude);
    }

    /** Resolves metadata directly for deterministic tests and non-layer callers. */
    SourceResolution resolveMetadata(ImageryInfo info, int zoom, double latitude) {
        if (info == null || zoom < 0 || zoom > 30 || !Double.isFinite(latitude) || Math.abs(latitude) > 85.05112878) {
            return SourceResolution.unknown("invalid-or-missing-tile-metadata");
        }
        if (isRecognizedStrava(info)) {
            return SourceResolution.known(zoom, STRAVA_TILE_SIZE,
                TileHeatmapSampler.metersPerPixel(zoom, latitude, STRAVA_TILE_SIZE),
                "recognized-strava-512");
        }
        try {
            TileSource source = TMSLayer.getTileSourceStatic(info);
            int tileSize = source == null ? 0 : source.getTileSize();
            if (tileSize <= 0) {
                return SourceResolution.unknown("invalid-tile-source-size");
            }
            return SourceResolution.known(zoom, tileSize,
                TileHeatmapSampler.metersPerPixel(zoom, latitude, tileSize),
                "tms-tile-source-metadata");
        } catch (RuntimeException ex) {
            return SourceResolution.unknown("unavailable-tile-source-metadata");
        }
    }

    private boolean isRecognizedStrava(ImageryInfo info) {
        if (ManagedImageryService.MANAGED_LAYER_ID.equals(info.getId())) {
            return true;
        }
        String url = info.getUrl() == null ? "" : info.getUrl().toLowerCase(Locale.ROOT);
        return url.contains("strava.com") && url.contains("globalheat");
    }

    /**
     * Native source-tile resolution with explicit unknown handling.
     *
     * @param zoom source zoom when known
     * @param tileSize native source tile size when known
     * @param metersPerPixel native source ground resolution when known
     * @param method machine-readable resolution source or unknown reason
     */
    public record SourceResolution(
        OptionalInt zoom,
        OptionalInt tileSize,
        OptionalDouble metersPerPixel,
        String method
    ) {
        /** Validates known fields as an all-or-none group. */
        public SourceResolution {
            zoom = zoom == null ? OptionalInt.empty() : zoom;
            tileSize = tileSize == null ? OptionalInt.empty() : tileSize;
            metersPerPixel = metersPerPixel == null ? OptionalDouble.empty() : metersPerPixel;
            if (method == null || method.isBlank()) {
                throw new IllegalArgumentException("Source-resolution method must not be blank");
            }
            boolean known = zoom.isPresent() && tileSize.isPresent() && metersPerPixel.isPresent();
            if (known != (zoom.isPresent() || tileSize.isPresent() || metersPerPixel.isPresent())) {
                throw new IllegalArgumentException("Source zoom, tile size, and metres per pixel must be all known or absent");
            }
            if (known && (zoom.getAsInt() < 0 || tileSize.getAsInt() <= 0
                || !Double.isFinite(metersPerPixel.getAsDouble()) || metersPerPixel.getAsDouble() <= 0.0)) {
                throw new IllegalArgumentException("Known source resolution must be finite and positive");
            }
        }

        /**
         * Returns a complete known source resolution.
         *
         * @param zoom native tile zoom
         * @param tileSize native tile edge length in pixels
         * @param metersPerPixel geographic ground metres represented by one native pixel
         * @param method machine-readable metadata source
         * @return complete validated source resolution
         */
        public static SourceResolution known(int zoom, int tileSize, double metersPerPixel, String method) {
            return new SourceResolution(OptionalInt.of(zoom), OptionalInt.of(tileSize),
                OptionalDouble.of(metersPerPixel), method);
        }

        /**
         * Returns an explicit unknown source-resolution result.
         *
         * @param reason machine-readable reason that native resolution could not be established
         * @return source resolution with no fabricated numeric fields
         */
        public static SourceResolution unknown(String reason) {
            return new SourceResolution(OptionalInt.empty(), OptionalInt.empty(), OptionalDouble.empty(), reason);
        }

        /**
         * Returns whether zoom, tile size, and native ground resolution are all known.
         *
         * @return {@code true} when every native-resolution component is present
         */
        public boolean known() {
            return metersPerPixel.isPresent();
        }
    }
}
