package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.List;

/** Builds an in-memory L0/L1/L2 scalar Gaussian pyramid without network access. */
public final class GaussianIntensityPyramid {
    static final int[] B5_KERNEL = {1, 4, 6, 4, 1};
    static final int B5_SUM = 16;
    static final long MAX_ESTIMATED_BYTES = 128L * 1024L * 1024L;

    private final List<IntensityScaleLevel> levels;
    private final long estimatedBytes;

    private GaussianIntensityPyramid(List<IntensityScaleLevel> levels) {
        this.levels = List.copyOf(levels);
        this.estimatedBytes = levels.stream().mapToLong(level -> level.field().estimatedBytes()).sum();
    }

    /**
     * Builds at most three levels from an already mapped L0 field.
     *
     * @param levelZero cropped scalar L0 field
     * @return deterministic Gaussian pyramid
     */
    public static GaussianIntensityPyramid build(ScalarIntensityField levelZero) {
        return build(levelZero, 4);
    }

    /**
     * Builds levels until the requested L0 reduction is available or exceeded.
     *
     * @param levelZero cropped scalar L0 field
     * @param maximumReduction largest useful reduction relative to L0
     * @return deterministic Gaussian pyramid
     */
    public static GaussianIntensityPyramid build(ScalarIntensityField levelZero, int maximumReduction) {
        List<IntensityScaleLevel> result = new ArrayList<>();
        result.add(new IntensityScaleLevel(0, 1, 0.0, levelZero));
        ScalarIntensityField current = levelZero;
        double sigma = 0.0;
        int requestedReduction = Math.max(1, maximumReduction);
        for (int level = 1; level <= 8 && current.reduction() < requestedReduction; level++) {
            ScalarIntensityField next = blurAndDecimate(current);
            if (next == null) {
                break;
            }
            sigma = Math.sqrt(sigma * sigma + current.reduction() * current.reduction());
            result.add(new IntensityScaleLevel(level, next.reduction(), sigma, next));
            current = next;
        }
        GaussianIntensityPyramid pyramid = new GaussianIntensityPyramid(result);
        if (pyramid.estimatedBytes > MAX_ESTIMATED_BYTES) {
            throw new IllegalStateException("Scalar Gaussian pyramid exceeds 128 MiB working-set limit");
        }
        return pyramid;
    }

    private static ScalarIntensityField blurAndDecimate(ScalarIntensityField source) {
        int targetReduction = source.reduction() * 2;
        int phaseX = Math.floorMod(-source.originX(), targetReduction) / source.reduction();
        int phaseY = Math.floorMod(-source.originY(), targetReduction) / source.reduction();
        int targetWidth = (source.width() - phaseX + 1) / 2;
        int targetHeight = (source.height() - phaseY + 1) / 2;
        if (targetWidth < 2 || targetHeight < 2) {
            return null;
        }
        float[] values = new float[targetWidth * targetHeight];
        boolean[] valid = new boolean[values.length];
        for (int y = 0; y < targetHeight; y++) {
            int sourceY = phaseY + y * 2;
            for (int x = 0; x < targetWidth; x++) {
                int sourceX = phaseX + x * 2;
                double weighted = 0.0;
                boolean complete = true;
                for (int ky = -2; ky <= 2 && complete; ky++) {
                    for (int kx = -2; kx <= 2; kx++) {
                        int sx = sourceX + kx;
                        int sy = sourceY + ky;
                        if (!source.isValid(sx, sy)) {
                            complete = false;
                            break;
                        }
                        weighted += source.value(sx, sy) * B5_KERNEL[kx + 2] * B5_KERNEL[ky + 2];
                    }
                }
                int index = y * targetWidth + x;
                if (complete) {
                    values[index] = (float) (weighted / (B5_SUM * B5_SUM));
                    valid[index] = true;
                }
            }
        }
        return new ScalarIntensityField(
            source.originX() + phaseX * source.reduction(),
            source.originY() + phaseY * source.reduction(),
            targetWidth,
            targetHeight,
            targetReduction,
            values,
            valid
        );
    }

    /**
     * Returns immutable pyramid levels.
     *
     * @return levels from finest to coarsest
     */
    public List<IntensityScaleLevel> levels() {
        return levels;
    }

    /**
     * Returns the estimated value-plus-mask storage in bytes.
     *
     * @return bounded working-set estimate
     */
    public long estimatedBytes() {
        return estimatedBytes;
    }
}
