package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.OptionalDouble;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;

/**
 * Cropped scalar heatmap intensity field in base-raster coordinates.
 *
 * <p>Coordinates always refer to pixel centers in the original L0 raster. A field may represent a
 * decimated level; {@link #reduction()} maps its grid back to that common coordinate system.</p>
 */
public final class ScalarIntensityField {
    private final int originX;
    private final int originY;
    private final int width;
    private final int height;
    private final int reduction;
    private final float[] values;
    private final boolean[] valid;
    private final boolean[] interpolationValid;

    ScalarIntensityField(
        int originX,
        int originY,
        int width,
        int height,
        int reduction,
        float[] values,
        boolean[] valid
    ) {
        this(originX, originY, width, height, reduction, values, valid,
            deriveInterpolationValidity(width, height, valid));
    }

    ScalarIntensityField(
        int originX,
        int originY,
        int width,
        int height,
        int reduction,
        float[] values,
        boolean[] valid,
        boolean[] interpolationValid
    ) {
        if (width <= 0 || height <= 0 || reduction <= 0
            || values.length != Math.multiplyExact(width, height) || valid.length != values.length
            || interpolationValid.length != Math.multiplyExact(width - 1, height - 1)) {
            throw new IllegalArgumentException("Invalid scalar intensity field dimensions");
        }
        this.originX = originX;
        this.originY = originY;
        this.width = width;
        this.height = height;
        this.reduction = reduction;
        this.values = values.clone();
        this.valid = valid.clone();
        this.interpolationValid = interpolationValid.clone();
    }

    /** Copies an already scalar modern field while retaining its explicit complete-cell mask. */
    static ScalarIntensityField fromEvidence(ScalarEvidenceField source, Runnable rowCheckpoint) {
        if (source == null || rowCheckpoint == null) {
            throw new IllegalArgumentException("Modern scalar evidence and cancellation checkpoint are required");
        }
        rowCheckpoint.run();
        int width = source.width();
        int height = source.height();
        float[] values = new float[Math.multiplyExact(width, height)];
        boolean[] valid = new boolean[values.length];
        for (int y = 0; y < height; y++) {
            rowCheckpoint.run();
            for (int x = 0; x < width; x++) {
                OptionalDouble sample = source.sample(x, y);
                int index = y * width + x;
                if (sample.isPresent()) {
                    values[index] = (float) sample.getAsDouble();
                    valid[index] = true;
                }
            }
        }
        boolean[] interpolationValid = new boolean[Math.multiplyExact(width - 1, height - 1)];
        for (int y = 0; y < height - 1; y++) {
            rowCheckpoint.run();
            for (int x = 0; x < width - 1; x++) {
                interpolationValid[y * (width - 1) + x] = source.supportsInterpolationCell(x, y);
            }
        }
        return new ScalarIntensityField(0, 0, width, height, 1,
            values, valid, interpolationValid);
    }

    /** Builds a cropped L0 scalar field after applying one detector mapping. */
    static ScalarIntensityField fromRaster(
        BufferedImage raster,
        int minimumX,
        int minimumY,
        int maximumX,
        int maximumY,
        String colorMode,
        IntensitySamplingMode samplingMode
    ) {
        int minX = Math.max(0, minimumX);
        int minY = Math.max(0, minimumY);
        int maxX = Math.min(raster.getWidth() - 1, maximumX);
        int maxY = Math.min(raster.getHeight() - 1, maximumY);
        if (maxX < minX || maxY < minY) {
            return empty();
        }
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        float[] values = new float[width * height];
        boolean[] valid = new boolean[values.length];
        IntensitySamplingMode source = samplingMode == null ? IntensitySamplingMode.COLOR_MAPPING : samplingMode;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = raster.getRGB(minX + x, minY + y);
                int alpha = (argb >>> 24) & 0xFF;
                int red = (argb >>> 16) & 0xFF;
                int green = (argb >>> 8) & 0xFF;
                int blue = argb & 0xFF;
                double intensity = alpha == 0 ? 0.0 : source.usesColorMapping()
                    ? RenderedHeatmapSampler.colorIntensity(red, green, blue, colorMode)
                    : RenderedHeatmapSampler.directIntensity(red, green, blue, alpha, source);
                int index = y * width + x;
                values[index] = (float) intensity;
                valid[index] = true;
            }
        }
        return new ScalarIntensityField(minX, minY, width, height, 1, values, valid);
    }

    /** Builds a cropped L0 field from the complete managed all-color scalar aggregate. */
    static ScalarIntensityField fromAggregatedRasters(
        Map<String, BufferedImage> rasters,
        int minimumX,
        int minimumY,
        int maximumX,
        int maximumY
    ) {
        if (rasters.isEmpty()) {
            return empty();
        }
        int rasterWidth = rasters.values().stream().mapToInt(BufferedImage::getWidth).min().orElse(0);
        int rasterHeight = rasters.values().stream().mapToInt(BufferedImage::getHeight).min().orElse(0);
        int minX = Math.max(0, minimumX);
        int minY = Math.max(0, minimumY);
        int maxX = Math.min(rasterWidth - 1, maximumX);
        int maxY = Math.min(rasterHeight - 1, maximumY);
        if (maxX < minX || maxY < minY) {
            return empty();
        }
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        float[] values = new float[width * height];
        boolean[] valid = new boolean[values.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                values[index] = (float) RenderedHeatmapSampler.aggregatedSourceIntensityAt(
                    rasters, minX + x, minY + y);
                valid[index] = true;
            }
        }
        return new ScalarIntensityField(minX, minY, width, height, 1, values, valid);
    }

    private static ScalarIntensityField empty() {
        return new ScalarIntensityField(0, 0, 1, 1, 1, new float[] {0.0f}, new boolean[] {false});
    }

    /**
     * Samples the field at an L0/base-raster coordinate using bilinear interpolation.
     *
     * @param baseX horizontal L0 pixel-center coordinate
     * @param baseY vertical L0 pixel-center coordinate
     * @return scalar intensity, or NaN when required support is invalid
     */
    public double sample(double baseX, double baseY) {
        double localX = (baseX - originX) / reduction;
        double localY = (baseY - originY) / reduction;
        int x0 = (int) Math.floor(localX);
        int y0 = (int) Math.floor(localY);
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        if (!isValid(x0, y0) || !isValid(x1, y0) || !isValid(x0, y1) || !isValid(x1, y1)) {
            int nearestX = (int) Math.round(localX);
            int nearestY = (int) Math.round(localY);
            return isValid(nearestX, nearestY) ? value(nearestX, nearestY) : Double.NaN;
        }
        double fx = localX - x0;
        double fy = localY - y0;
        double top = value(x0, y0) * (1.0 - fx) + value(x1, y0) * fx;
        double bottom = value(x0, y1) * (1.0 - fx) + value(x1, y1) * fx;
        return top * (1.0 - fy) + bottom * fy;
    }

    /**
     * Samples only through an explicitly complete interpolation cell.
     *
     * <p>This is the modern detached contract. {@link #sample(double, double)} deliberately retains
     * the legacy nearest-valid fallback.</p>
     */
    double sampleStrict(double baseX, double baseY) {
        double localX = (baseX - originX) / reduction;
        double localY = (baseY - originY) / reduction;
        if (!Double.isFinite(localX) || !Double.isFinite(localY) || width < 2 || height < 2
            || localX < 0.0 || localY < 0.0 || localX > width - 1.0 || localY > height - 1.0) {
            return Double.NaN;
        }
        int x0 = Math.min(width - 2, (int) Math.floor(localX));
        int y0 = Math.min(height - 2, (int) Math.floor(localY));
        if (!supportsInterpolationCell(x0, y0)) {
            return Double.NaN;
        }
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        if (!isValid(x0, y0) || !isValid(x1, y0) || !isValid(x0, y1) || !isValid(x1, y1)) {
            return Double.NaN;
        }
        double fx = localX - x0;
        double fy = localY - y0;
        double top = value(x0, y0) * (1.0 - fx) + value(x1, y0) * fx;
        double bottom = value(x0, y1) * (1.0 - fx) + value(x1, y1) * fx;
        return top * (1.0 - fy) + bottom * fy;
    }

    /**
     * Proves complete interpolation-cell support over a whole straight segment.
     * Cells touched only at an edge or corner are included conservatively.
     */
    boolean supportsStrictSegment(double firstBaseX, double firstBaseY,
            double secondBaseX, double secondBaseY, Runnable checkpoint) {
        if (checkpoint == null) {
            throw new IllegalArgumentException("Segment support requires a cancellation checkpoint");
        }
        double firstX = (firstBaseX - originX) / reduction;
        double firstY = (firstBaseY - originY) / reduction;
        double secondX = (secondBaseX - originX) / reduction;
        double secondY = (secondBaseY - originY) / reduction;
        if (!Double.isFinite(firstX) || !Double.isFinite(firstY)
            || !Double.isFinite(secondX) || !Double.isFinite(secondY)
            || width < 2 || height < 2
            || firstX < 0.0 || firstY < 0.0 || secondX < 0.0 || secondY < 0.0
            || firstX > width - 1.0 || secondX > width - 1.0
            || firstY > height - 1.0 || secondY > height - 1.0
            || !Double.isFinite(sampleStrict(firstBaseX, firstBaseY))
            || !Double.isFinite(sampleStrict(secondBaseX, secondBaseY))) {
            return false;
        }
        int minimumY = Math.max(0, (int) Math.floor(Math.min(firstY, secondY)) - 1);
        int maximumY = Math.min(height - 2, (int) Math.floor(Math.max(firstY, secondY)));
        double deltaX = secondX - firstX;
        double deltaY = secondY - firstY;
        boolean touched = false;
        for (int y = minimumY; y <= maximumY; y++) {
            checkpoint.run();
            double lower = 0.0;
            double upper = 1.0;
            if (Math.abs(deltaY) <= 1.0e-15) {
                if (firstY < y - 1.0e-12 || firstY > y + 1.0 + 1.0e-12) {
                    continue;
                }
            } else {
                double first = (y - firstY) / deltaY;
                double second = (y + 1.0 - firstY) / deltaY;
                lower = Math.max(lower, Math.min(first, second));
                upper = Math.min(upper, Math.max(first, second));
                if (lower > upper + 1.0e-12) {
                    continue;
                }
            }
            double rowFirstX = firstX + deltaX * lower;
            double rowSecondX = firstX + deltaX * upper;
            int minimumX = Math.max(0,
                (int) Math.floor(Math.min(rowFirstX, rowSecondX)) - 1);
            int maximumX = Math.min(width - 2,
                (int) Math.floor(Math.max(rowFirstX, rowSecondX)));
            for (int x = minimumX; x <= maximumX; x++) {
                checkpoint.run();
                if (segmentIntersectsCell(firstX, firstY, secondX, secondY, x, y)) {
                    touched = true;
                    if (!supportsInterpolationCell(x, y)) {
                        return false;
                    }
                }
            }
        }
        return touched;
    }

    private static boolean segmentIntersectsCell(double firstX, double firstY,
            double secondX, double secondY, int cellX, int cellY) {
        double deltaX = secondX - firstX;
        double deltaY = secondY - firstY;
        double lower = 0.0;
        double upper = 1.0;
        if (Math.abs(deltaX) <= 1.0e-15) {
            if (firstX < cellX - 1.0e-12 || firstX > cellX + 1.0 + 1.0e-12) {
                return false;
            }
        } else {
            double first = (cellX - firstX) / deltaX;
            double second = (cellX + 1.0 - firstX) / deltaX;
            lower = Math.max(lower, Math.min(first, second));
            upper = Math.min(upper, Math.max(first, second));
        }
        if (Math.abs(deltaY) <= 1.0e-15) {
            if (firstY < cellY - 1.0e-12 || firstY > cellY + 1.0 + 1.0e-12) {
                return false;
            }
        } else {
            double first = (cellY - firstY) / deltaY;
            double second = (cellY + 1.0 - firstY) / deltaY;
            lower = Math.max(lower, Math.min(first, second));
            upper = Math.min(upper, Math.max(first, second));
        }
        return lower <= upper + 1.0e-12;
    }

    boolean isValid(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height && valid[y * width + x];
    }

    float value(int x, int y) {
        return values[y * width + x];
    }

    boolean supportsInterpolationCell(int x, int y) {
        return x >= 0 && y >= 0 && x < width - 1 && y < height - 1
            && interpolationValid[y * (width - 1) + x];
    }

    int originX() {
        return originX;
    }

    int originY() {
        return originY;
    }

    /**
     * Returns the field width in level pixels.
     *
     * @return level-grid width
     */
    public int width() {
        return width;
    }

    /**
     * Returns the field height in level pixels.
     *
     * @return level-grid height
     */
    public int height() {
        return height;
    }

    /**
     * Returns the level-to-L0 integer reduction factor.
     *
     * @return L0 pixels per level pixel
     */
    public int reduction() {
        return reduction;
    }

    /**
     * Returns an upper-bound storage estimate for values and validity flags.
     *
     * @return estimated bytes
     */
    public long estimatedBytes() {
        return Math.addExact((long) values.length * (Float.BYTES + 1L), interpolationValid.length);
    }

    private static boolean[] deriveInterpolationValidity(int width, int height, boolean[] valid) {
        if (width <= 0 || height <= 0 || valid == null
            || valid.length != Math.multiplyExact(width, height)) {
            return new boolean[0];
        }
        boolean[] result = new boolean[Math.multiplyExact(width - 1, height - 1)];
        for (int y = 0; y < height - 1; y++) {
            for (int x = 0; x < width - 1; x++) {
                int upperLeft = y * width + x;
                result[y * (width - 1) + x] = valid[upperLeft] && valid[upperLeft + 1]
                    && valid[upperLeft + width] && valid[upperLeft + width + 1];
            }
        }
        return result;
    }
}
