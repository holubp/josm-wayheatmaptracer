package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

/**
 * Fixed encounter-order compensated binary64 reduction, independent of stream implementations.
 * The operation order matches the sequential OpenJDK 17 DoublePipeline sum reduction
 * (Collectors.sumWithCompensation and computeFinalSum), including infinity fallback.
 * https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/java/util/stream/Collectors.java
 */
public final class OrderedDoubleSum {
    private double sum;
    private double compensation;
    private double simpleSum;

    /** Adds one value using the version-owned order of primitive operations. */
    public void add(double value) {
        double corrected = value - compensation;
        double next = sum + corrected;
        compensation = (next - sum) - corrected;
        sum = next;
        simpleSum += value;
    }

    /** Returns the compensated sum or the same-sign infinite simple-sum fallback. */
    public double value() {
        double result = sum - compensation;
        return Double.isNaN(result) && Double.isInfinite(simpleSum) ? simpleSum : result;
    }
}
