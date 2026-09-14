package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Immutable provenance for the operation that produced the snapshot raster. */
public record RasterResamplingProvenance(
    String method,
    String sourceTransformKind,
    String sourceTransformIdentity,
    String inputCoordinateConvention,
    String scalarOrder,
    String validityRule,
    int inputWidth,
    int inputHeight,
    int outputWidth,
    int outputHeight
) {
    /** Validates a complete finite-raster provenance declaration. */
    public RasterResamplingProvenance {
        if (blank(method) || blank(sourceTransformKind) || blank(sourceTransformIdentity)
                || blank(inputCoordinateConvention) || blank(scalarOrder)
                || blank(validityRule) || inputWidth <= 0 || inputHeight <= 0
                || outputWidth <= 0 || outputHeight <= 0) {
            throw new IllegalArgumentException("Raster resampling provenance is incomplete");
        }
    }

    /** Records exact geographic inverse lookup with strict four-corner bilinear support. */
    public static RasterResamplingProvenance exactInverseBilinear(
            String sourceTransformKind, String sourceTransformIdentity,
            int inputWidth, int inputHeight,
            int outputWidth, int outputHeight) {
        return new RasterResamplingProvenance(
                "exact-analytic-cell-support-strict-bilinear-v2",
                sourceTransformKind,
                sourceTransformIdentity,
                "integer-input-raster-pixel-centers-v1",
                "argb-to-scalar-before-resampling-v1",
                "complete-input-lattice-support-over-output-interpolation-cells-v2",
                inputWidth, inputHeight, outputWidth, outputHeight);
    }

    /** Records a legacy or synthetic field already expressed on its retained raster grid. */
    public static RasterResamplingProvenance direct(int width, int height) {
        return new RasterResamplingProvenance("direct-scalar-grid-v1",
                "already-retained-raster-grid-v1",
                "already-retained-raster-grid-v1",
                "integer-retained-raster-pixel-centers-v1", "already-scalar-v1",
                "retained-field-mask-v1", width, height, width, height);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
