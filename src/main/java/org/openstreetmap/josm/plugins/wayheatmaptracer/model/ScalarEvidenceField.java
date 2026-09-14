package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.OptionalDouble;

/** Immutable scalar heatmap evidence with validity independent from numeric intensity. */
public final class ScalarEvidenceField {
    private final int width;
    private final int height;
    private final double[] values;
    private final boolean[] valid;
    private final boolean[] interpolationValid;
    private final EvidenceFieldLineage lineage;

    /** Creates a deeply copied normalized scalar field. */
    public ScalarEvidenceField(int width, int height, double[] values, boolean[] valid,
        EvidenceFieldLineage lineage) {
        this(width, height, values, valid, deriveInterpolationValidity(width, height, valid), lineage);
    }

    /** Creates a deeply copied scalar field with explicit complete-cell interpolation support. */
    public ScalarEvidenceField(int width, int height, double[] values, boolean[] valid,
            boolean[] interpolationValid, EvidenceFieldLineage lineage) {
        if (width <= 0 || height <= 0 || values == null || valid == null || lineage == null
            || interpolationValid == null || values.length != Math.multiplyExact(width, height)
            || values.length != valid.length
            || interpolationValid.length != Math.multiplyExact(width - 1, height - 1)) {
            throw new IllegalArgumentException("Scalar evidence dimensions are inconsistent");
        }
        this.width = width;
        this.height = height;
        this.values = values.clone();
        this.valid = valid.clone();
        this.interpolationValid = interpolationValid.clone();
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

    /** Returns a defensive copy of complete support for every adjacent interpolation cell. */
    public boolean[] copiedInterpolationValidity() {
        return interpolationValid.clone();
    }

    /** Returns whether one adjacent raster cell has complete source support throughout its area. */
    public boolean supportsInterpolationCell(int x, int y) {
        if (x < 0 || y < 0 || x >= width - 1 || y >= height - 1) {
            return false;
        }
        return interpolationValid[y * (width - 1) + x];
    }

    /** Returns whether a continuous raster coordinate may be sampled without crossing missing support. */
    public boolean supportsInterpolationAt(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || width < 2 || height < 2
                || x < 0.0 || y < 0.0 || x > width - 1.0 || y > height - 1.0) {
            return false;
        }
        int cellX = Math.min(width - 2, (int) Math.floor(x));
        int cellY = Math.min(height - 2, (int) Math.floor(y));
        return supportsInterpolationCell(cellX, cellY);
    }

    /** Samples at a continuous raster coordinate only when its complete interpolation cell is supported. */
    public OptionalDouble sampleBilinear(double x, double y) {
        if (!supportsInterpolationAt(x, y)) {
            return OptionalDouble.empty();
        }
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(width - 1, x0 + 1);
        int y1 = Math.min(height - 1, y0 + 1);
        if (!valid[y0 * width + x0] || !valid[y0 * width + x1]
                || !valid[y1 * width + x0] || !valid[y1 * width + x1]) {
            return OptionalDouble.empty();
        }
        double fx = x - x0;
        double fy = y - y0;
        double top = values[y0 * width + x0]
                + fx * (values[y0 * width + x1] - values[y0 * width + x0]);
        double bottom = values[y1 * width + x0]
                + fx * (values[y1 * width + x1] - values[y1 * width + x0]);
        return OptionalDouble.of(top + fy * (bottom - top));
    }

    /**
     * Applies a normalized separable kernel to already-mapped scalar intensity.
     *
     * <p>Out-of-field or invalid support invalidates the destination. No missing sample becomes
     * zero and no edge sample is duplicated as implicit evidence.</p>
     */
    public ScalarEvidenceField convolveSeparable(double[] kernel) {
        return convolveSeparable(kernel, () -> { });
    }

    /** Applies a bounded separable kernel while checking cancellation once per processing row. */
    public ScalarEvidenceField convolveSeparable(double[] kernel, Runnable rowCheckpoint) {
        if (rowCheckpoint == null) {
            throw new IllegalArgumentException("A filter cancellation checkpoint is required");
        }
        double[] retainedKernel = kernel == null ? null : kernel.clone();
        validateKernel(retainedKernel);
        double[] horizontal = new double[values.length];
        boolean[] horizontalValid = new boolean[values.length];
        convolve(values, valid, horizontal, horizontalValid, retainedKernel, true, rowCheckpoint);
        double[] output = new double[values.length];
        boolean[] outputValid = new boolean[values.length];
        convolve(horizontal, horizontalValid, output, outputValid, retainedKernel, false, rowCheckpoint);
        boolean[] outputInterpolationValid = filteredInterpolationValidity(
                outputValid, retainedKernel.length / 2, rowCheckpoint);
        return new ScalarEvidenceField(width, height, output, outputValid, outputInterpolationValid,
            lineage.filtered("separable-" + java.util.Arrays.toString(retainedKernel)));
    }

    private void convolve(double[] input, boolean[] inputValid, double[] output, boolean[] outputValid,
            double[] kernel, boolean horizontal, Runnable rowCheckpoint) {
        int radius = kernel.length / 2;
        double denominator = java.util.Arrays.stream(kernel).sum();
        for (int y = 0; y < height; y++) {
            rowCheckpoint.run();
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

    private boolean[] filteredInterpolationValidity(boolean[] outputValid, int radius,
            Runnable rowCheckpoint) {
        boolean[] result = new boolean[Math.multiplyExact(width - 1, height - 1)];
        for (int y = 0; y < height - 1; y++) {
            rowCheckpoint.run();
            for (int x = 0; x < width - 1; x++) {
                boolean complete = outputValid[y * width + x] && outputValid[y * width + x + 1]
                        && outputValid[(y + 1) * width + x]
                        && outputValid[(y + 1) * width + x + 1];
                for (int sourceY = y - radius; complete && sourceY <= y + radius; sourceY++) {
                    for (int sourceX = x - radius; complete && sourceX <= x + radius; sourceX++) {
                        complete = supportsInterpolationCell(sourceX, sourceY);
                    }
                }
                result[y * (width - 1) + x] = complete;
            }
        }
        return result;
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
