package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.image.BufferedImage;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;

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

    ScalarIntensityField(
        int originX,
        int originY,
        int width,
        int height,
        int reduction,
        float[] values,
        boolean[] valid
    ) {
        if (width <= 0 || height <= 0 || reduction <= 0
            || values.length != width * height || valid.length != values.length) {
            throw new IllegalArgumentException("Invalid scalar intensity field dimensions");
        }
        this.originX = originX;
        this.originY = originY;
        this.width = width;
        this.height = height;
        this.reduction = reduction;
        this.values = values.clone();
        this.valid = valid.clone();
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

    boolean isValid(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height && valid[y * width + x];
    }

    float value(int x, int y) {
        return values[y * width + x];
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
        return (long) values.length * (Float.BYTES + 1L);
    }
}
