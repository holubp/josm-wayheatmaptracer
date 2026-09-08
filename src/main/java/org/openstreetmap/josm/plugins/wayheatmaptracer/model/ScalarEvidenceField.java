package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.OptionalDouble;

/** Immutable scalar heatmap evidence with validity independent from numeric intensity. */
public final class ScalarEvidenceField {
    private final int width;
    private final int height;
    private final double[] values;
    private final boolean[] valid;
    private final EvidenceFieldLineage lineage;

    /** Creates a deeply copied normalized scalar field. */
    public ScalarEvidenceField(int width, int height, double[] values, boolean[] valid,
        EvidenceFieldLineage lineage) {
        if (width <= 0 || height <= 0 || values == null || valid == null || lineage == null
            || values.length != Math.multiplyExact(width, height) || values.length != valid.length) {
            throw new IllegalArgumentException("Scalar evidence dimensions are inconsistent");
        }
        this.width = width;
        this.height = height;
        this.values = values.clone();
        this.valid = valid.clone();
        this.lineage = lineage;
        for (int index = 0; index < this.values.length; index++) {
            if (this.valid[index] && (!Double.isFinite(this.values[index])
                || this.values[index] < 0.0 || this.values[index] > 1.0)) {
                throw new IllegalArgumentException("Valid scalar evidence must be finite and normalized");
            }
        }
    }

    /** Returns raster width. */
    public int width() {
        return width;
    }

    /** Returns raster height. */
    public int height() {
        return height;
    }

    /** Returns immutable field lineage. */
    public EvidenceFieldLineage lineage() {
        return lineage;
    }

    /** Returns one value only when the raster cell is valid. */
    public OptionalDouble sample(int x, int y) {
        int index = index(x, y);
        return valid[index] ? OptionalDouble.of(values[index]) : OptionalDouble.empty();
    }

    /** Returns a defensive copy for checksummed serialization. */
    public double[] copiedValues() {
        return values.clone();
    }

    /** Returns a defensive copy of the validity mask. */
    public boolean[] copiedValidity() {
        return valid.clone();
    }

    /**
     * Applies a normalized separable kernel to already-mapped scalar intensity.
     *
     * <p>Out-of-field or invalid support invalidates the destination. No missing sample becomes
     * zero and no edge sample is duplicated as implicit evidence.</p>
     */
    public ScalarEvidenceField convolveSeparable(double[] kernel) {
        validateKernel(kernel);
        double[] horizontal = new double[values.length];
        boolean[] horizontalValid = new boolean[values.length];
        convolve(values, valid, horizontal, horizontalValid, kernel, true);
        double[] output = new double[values.length];
        boolean[] outputValid = new boolean[values.length];
        convolve(horizontal, horizontalValid, output, outputValid, kernel, false);
        return new ScalarEvidenceField(width, height, output, outputValid,
            lineage.filtered("separable-" + java.util.Arrays.toString(kernel)));
    }

    private void convolve(double[] input, boolean[] inputValid, double[] output, boolean[] outputValid,
        double[] kernel, boolean horizontal) {
        int radius = kernel.length / 2;
        double denominator = java.util.Arrays.stream(kernel).sum();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double sum = 0.0;
                boolean complete = true;
                for (int tap = -radius; tap <= radius; tap++) {
                    int sampleX = horizontal ? x + tap : x;
                    int sampleY = horizontal ? y : y + tap;
                    if (sampleX < 0 || sampleX >= width || sampleY < 0 || sampleY >= height) {
                        complete = false;
                        break;
                    }
                    int sampleIndex = sampleY * width + sampleX;
                    if (!inputValid[sampleIndex]) {
                        complete = false;
                        break;
                    }
                    sum += kernel[tap + radius] * input[sampleIndex];
                }
                int outputIndex = y * width + x;
                outputValid[outputIndex] = complete;
                output[outputIndex] = complete ? Math.max(0.0, Math.min(1.0, sum / denominator)) : Double.NaN;
            }
        }
    }

    private int index(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException("Evidence cell outside raster");
        }
        return y * width + x;
    }

    private static void validateKernel(double[] kernel) {
        if (kernel == null || kernel.length == 0 || kernel.length % 2 == 0) {
            throw new IllegalArgumentException("A separable kernel must have positive odd length");
        }
        double sum = 0.0;
        for (double weight : kernel) {
            if (!Double.isFinite(weight) || weight < 0.0) {
                throw new IllegalArgumentException("Kernel weights must be finite and nonnegative");
            }
            sum += weight;
        }
        if (sum <= 0.0) {
            throw new IllegalArgumentException("Kernel must have positive support");
        }
    }
}
