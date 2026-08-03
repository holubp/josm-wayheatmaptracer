package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Unit-explicit sampling scale shared by alignment decisions and diagnostics.
 *
 * @param source sampling source identifier
 * @param projectionUnitsPerViewPixel JOSM projected units per view pixel, absent for source-tile mosaics
 * @param rasterScale sampled-raster pixels per view/reference pixel
 * @param groundMetersPerViewPixel measured or defined ground metres per view/reference pixel
 * @param groundMetersPerRasterPixel ground metres per sampled-raster pixel
 * @param nativeSourceMetersPerPixel native tile ground resolution when known
 * @param nativeSourcePixelSizeRasterPx sampled-raster pixels occupied by one native tile pixel when known
 * @param nativeSourceZoom native tile zoom when known
 * @param nativeSourceTileSizePx native tile edge size in pixels when known
 * @param nativeResolutionMethod machine-readable source of native resolution
 * @param eastMetersPerProjectionUnit east-axis geographic scale, absent for source-tile mosaics
 * @param northMetersPerProjectionUnit north-axis geographic scale, absent for source-tile mosaics
 * @param minimumGroundMetersPerViewPixel minimum measured scale along selected geometry
 * @param maximumGroundMetersPerViewPixel maximum measured scale along selected geometry
 * @param anisotropyRatio maximum east/north scale difference
 * @param longitudinalVariationRatio relative scale variation along selected geometry
 * @param trackerNormalizationRasterPx raster-pixel normalization supplied to the configured tracker
 * @param trackerNormalizationMethod machine-readable normalization policy
 */
public record SamplingScale(
    String source,
    OptionalDouble projectionUnitsPerViewPixel,
    double rasterScale,
    double groundMetersPerViewPixel,
    double groundMetersPerRasterPixel,
    OptionalDouble nativeSourceMetersPerPixel,
    OptionalDouble nativeSourcePixelSizeRasterPx,
    OptionalInt nativeSourceZoom,
    OptionalInt nativeSourceTileSizePx,
    String nativeResolutionMethod,
    OptionalDouble eastMetersPerProjectionUnit,
    OptionalDouble northMetersPerProjectionUnit,
    double minimumGroundMetersPerViewPixel,
    double maximumGroundMetersPerViewPixel,
    double anisotropyRatio,
    double longitudinalVariationRatio,
    double trackerNormalizationRasterPx,
    String trackerNormalizationMethod
) {
    /** Validates scale units and optional native-resolution consistency. */
    public SamplingScale {
        source = requireText(source, "sampling source");
        projectionUnitsPerViewPixel = projectionUnitsPerViewPixel == null ? OptionalDouble.empty()
            : projectionUnitsPerViewPixel;
        nativeSourceMetersPerPixel = nativeSourceMetersPerPixel == null ? OptionalDouble.empty()
            : nativeSourceMetersPerPixel;
        nativeSourcePixelSizeRasterPx = nativeSourcePixelSizeRasterPx == null ? OptionalDouble.empty()
            : nativeSourcePixelSizeRasterPx;
        nativeSourceZoom = nativeSourceZoom == null ? OptionalInt.empty() : nativeSourceZoom;
        nativeSourceTileSizePx = nativeSourceTileSizePx == null ? OptionalInt.empty() : nativeSourceTileSizePx;
        eastMetersPerProjectionUnit = eastMetersPerProjectionUnit == null ? OptionalDouble.empty()
            : eastMetersPerProjectionUnit;
        northMetersPerProjectionUnit = northMetersPerProjectionUnit == null ? OptionalDouble.empty()
            : northMetersPerProjectionUnit;
        requireOptionalPositive(projectionUnitsPerViewPixel, "projection units per view pixel");
        requirePositive(rasterScale, "raster scale");
        requirePositive(groundMetersPerViewPixel, "ground metres per view pixel");
        requirePositive(groundMetersPerRasterPixel, "ground metres per raster pixel");
        requireOptionalPositive(nativeSourceMetersPerPixel, "native source metres per pixel");
        requireOptionalPositive(nativeSourcePixelSizeRasterPx, "native source pixel size in raster pixels");
        requireOptionalPositive(eastMetersPerProjectionUnit, "east ground scale");
        requireOptionalPositive(northMetersPerProjectionUnit, "north ground scale");
        requirePositive(minimumGroundMetersPerViewPixel, "minimum ground metres per view pixel");
        requirePositive(maximumGroundMetersPerViewPixel, "maximum ground metres per view pixel");
        requireNonNegative(anisotropyRatio, "ground-scale anisotropy");
        requireNonNegative(longitudinalVariationRatio, "ground-scale variation");
        requirePositive(trackerNormalizationRasterPx, "tracker normalization");
        nativeResolutionMethod = requireText(nativeResolutionMethod, "native resolution method");
        trackerNormalizationMethod = requireText(trackerNormalizationMethod, "tracker normalization method");
        if (nativeSourceMetersPerPixel.isPresent() != nativeSourcePixelSizeRasterPx.isPresent()) {
            throw new IllegalArgumentException("Native source metres and raster pixel size must both be known or absent");
        }
        if (nativeSourceZoom.isPresent() != nativeSourceTileSizePx.isPresent()) {
            throw new IllegalArgumentException("Native source zoom and tile size must both be known or absent");
        }
        if (nativeSourceZoom.isPresent()
            && (nativeSourceZoom.getAsInt() < 0 || nativeSourceTileSizePx.getAsInt() <= 0)) {
            throw new IllegalArgumentException("Native source zoom and tile size must be non-negative and positive");
        }
        if (minimumGroundMetersPerViewPixel > maximumGroundMetersPerViewPixel) {
            throw new IllegalArgumentException("Minimum ground resolution must not exceed maximum ground resolution");
        }
        double expectedRaster = groundMetersPerViewPixel / rasterScale;
        if (Math.abs(expectedRaster - groundMetersPerRasterPixel) > Math.max(1e-12, expectedRaster * 1e-9)) {
            throw new IllegalArgumentException("Ground raster resolution must equal view resolution divided by raster scale");
        }
        if (nativeSourceMetersPerPixel.isPresent()) {
            double expectedNativeRaster = nativeSourceMetersPerPixel.getAsDouble() / groundMetersPerRasterPixel;
            if (Math.abs(expectedNativeRaster - nativeSourcePixelSizeRasterPx.getAsDouble())
                > Math.max(1e-12, expectedNativeRaster * 1e-9)) {
                throw new IllegalArgumentException(
                    "Native source raster footprint must equal source resolution divided by raster resolution");
            }
        }
    }

    /**
     * Returns whether native tile resolution is known from validated source metadata.
     *
     * @return {@code true} when native metres and raster footprint are both available
     */
    public boolean nativeResolutionKnown() {
        return nativeSourceMetersPerPixel.isPresent();
    }

    /**
     * Converts a signed sampled-raster displacement to geographic ground metres.
     *
     * @param rasterPixels signed displacement in sampled-raster pixels
     * @return signed displacement in ground metres
     */
    public double groundMetersForRasterPixels(double rasterPixels) {
        requireFinite(rasterPixels, "raster-pixel displacement");
        return rasterPixels * groundMetersPerRasterPixel;
    }

    /**
     * Converts a signed ground displacement to sampled-raster pixels.
     *
     * @param groundMeters signed geographic ground displacement in metres
     * @return signed displacement in sampled-raster pixels
     */
    public double rasterPixelsForGroundMeters(double groundMeters) {
        requireFinite(groundMeters, "ground displacement");
        return groundMeters / groundMetersPerRasterPixel;
    }

    /**
     * Converts a signed sampled-raster displacement to native source pixels when source metadata is known.
     *
     * @param rasterPixels signed displacement in sampled-raster pixels
     * @return signed native-source displacement, or empty when native source resolution is unknown
     */
    public OptionalDouble nativeSourcePixelsForRasterPixels(double rasterPixels) {
        requireFinite(rasterPixels, "raster-pixel displacement");
        return nativeSourcePixelSizeRasterPx.isPresent()
            ? OptionalDouble.of(rasterPixels / nativeSourcePixelSizeRasterPx.getAsDouble())
            : OptionalDouble.empty();
    }

    /**
     * Builds factual visible-rendered scale with an explicit tracker normalization policy.
     *
     * @param projectionUnitsPerViewPixel slide-time JOSM projection units per view pixel
     * @param rasterScale sampled-raster pixels per view pixel
     * @param ground measured slide-time projection-to-ground scale
     * @param sourceResolution validated native tile resolution or an explicit unknown result
     * @param trackerNormalizationRasterPx raster-pixel pitch supplied to the tracker
     * @param trackerNormalizationMethod machine-readable normalization policy
     * @return immutable unit-explicit visible sampling scale
     */
    public static SamplingScale visible(
        double projectionUnitsPerViewPixel,
        double rasterScale,
        ProjectionGroundScale ground,
        VisibleSourceResolutionResolver.SourceResolution sourceResolution,
        double trackerNormalizationRasterPx,
        String trackerNormalizationMethod
    ) {
        if (ground == null) {
            throw new IllegalArgumentException("Visible sampling requires measured projection ground scale");
        }
        VisibleSourceResolutionResolver.SourceResolution resolution = sourceResolution == null
            ? VisibleSourceResolutionResolver.SourceResolution.unknown("source-resolution-unavailable")
            : sourceResolution;
        double viewGround = projectionUnitsPerViewPixel * ground.representativeMetersPerProjectionUnit();
        double rasterGround = viewGround / rasterScale;
        OptionalDouble nativeMeters = resolution.metersPerPixel();
        OptionalDouble nativeRaster = nativeMeters.isPresent()
            ? OptionalDouble.of(nativeMeters.getAsDouble() / rasterGround) : OptionalDouble.empty();
        return new SamplingScale(
            "rendered-visible-layer",
            OptionalDouble.of(projectionUnitsPerViewPixel),
            rasterScale,
            viewGround,
            rasterGround,
            nativeMeters,
            nativeRaster,
            resolution.zoom(),
            resolution.tileSize(),
            resolution.method(),
            OptionalDouble.of(ground.eastMetersPerProjectionUnitMedian()),
            OptionalDouble.of(ground.northMetersPerProjectionUnitMedian()),
            projectionUnitsPerViewPixel * ground.minimumMetersPerProjectionUnit(),
            projectionUnitsPerViewPixel * ground.maximumMetersPerProjectionUnit(),
            ground.anisotropyRatio(),
            ground.longitudinalVariationRatio(),
            trackerNormalizationRasterPx,
            trackerNormalizationMethod
        );
    }

    /**
     * Builds managed source-tile scale where source and decision resolution are factual.
     *
     * @param referenceGroundMetersPerViewPixel ground metres represented by one reference view pixel
     * @param rasterScale sampled-raster pixels per reference view pixel
     * @param nativeSourceMetersPerPixel ground metres represented by one native source pixel
     * @return immutable managed sampling scale without explicit source zoom metadata
     */
    public static SamplingScale managed(
        double referenceGroundMetersPerViewPixel,
        double rasterScale,
        double nativeSourceMetersPerPixel
    ) {
        return managed(referenceGroundMetersPerViewPixel, rasterScale, nativeSourceMetersPerPixel,
            OptionalInt.empty(), OptionalInt.empty());
    }

    /**
     * Builds managed source-tile scale with explicit native zoom and tile size.
     *
     * @param referenceGroundMetersPerViewPixel ground metres represented by one reference view pixel
     * @param rasterScale sampled-raster pixels per reference view pixel
     * @param nativeSourceMetersPerPixel ground metres represented by one native source pixel
     * @param nativeSourceZoom source tile zoom
     * @param nativeSourceTileSizePx source tile edge length in pixels
     * @return immutable managed sampling scale with source metadata
     */
    public static SamplingScale managed(
        double referenceGroundMetersPerViewPixel,
        double rasterScale,
        double nativeSourceMetersPerPixel,
        int nativeSourceZoom,
        int nativeSourceTileSizePx
    ) {
        return managed(referenceGroundMetersPerViewPixel, rasterScale, nativeSourceMetersPerPixel,
            OptionalInt.of(nativeSourceZoom), OptionalInt.of(nativeSourceTileSizePx));
    }

    private static SamplingScale managed(
        double referenceGroundMetersPerViewPixel,
        double rasterScale,
        double nativeSourceMetersPerPixel,
        OptionalInt nativeSourceZoom,
        OptionalInt nativeSourceTileSizePx
    ) {
        requirePositive(referenceGroundMetersPerViewPixel, "managed reference ground resolution");
        requirePositive(rasterScale, "managed raster scale");
        requirePositive(nativeSourceMetersPerPixel, "managed native source resolution");
        double rasterGround = referenceGroundMetersPerViewPixel / rasterScale;
        double nativeRaster = nativeSourceMetersPerPixel / rasterGround;
        return new SamplingScale(
            "managed-source-tiles",
            OptionalDouble.empty(),
            rasterScale,
            referenceGroundMetersPerViewPixel,
            rasterGround,
            OptionalDouble.of(nativeSourceMetersPerPixel),
            OptionalDouble.of(nativeRaster),
            nativeSourceZoom,
            nativeSourceTileSizePx,
            "managed-mosaic-metadata",
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            referenceGroundMetersPerViewPixel,
            referenceGroundMetersPerViewPixel,
            0.0,
            0.0,
            nativeRaster,
            "native-source-pixel"
        );
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireOptionalPositive(OptionalDouble value, String name) {
        if (value.isPresent()) {
            requirePositive(value.getAsDouble(), name);
        }
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    private static void requireNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
