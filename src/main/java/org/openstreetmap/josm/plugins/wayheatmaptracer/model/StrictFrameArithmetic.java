package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/**
 * Exact unit conversions belonging to the unreleased strict-v2 numerical frame.
 * Each conversion is one Java 17 binary64 multiplication by the specified factor;
 * the factors and operation order are part of the frame algorithm, not Math API policy.
 */
final class StrictFrameArithmetic {
    // Correctly rounded binary64 pi/180 and 180/pi, independently derived at 100-digit precision.
    private static final double RADIANS_PER_DEGREE = 0x1.1df46a2529d39p-6;
    private static final double DEGREES_PER_RADIAN = 0x1.ca5dc1a63c1f8p5;

    private StrictFrameArithmetic() { }

    static double toRadians(double degrees) {
        return degrees * RADIANS_PER_DEGREE;
    }

    static double toDegrees(double radians) {
        return radians * DEGREES_PER_RADIAN;
    }
}
