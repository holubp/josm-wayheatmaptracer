package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.Objects;

/** Non-forgeable conservative distortion certificate over a complete geographic domain. */
public final class DistortionCertificate {
    static final String LEGACY_METHOD = "wgs84-local-tangent-v1-analytic-bound";
    static final String STRICT_METHOD = "wgs84-local-tangent-v2-strictmath-bound";
    private static final double LIMIT = 0.001;
    private static final double WGS84_A = 6_378_137.0;
    private static final double WGS84_E2 = 6.6943799901413165e-3;
    private final String method;
    private final double maximumRelativeDistanceError;
    private final GeographicPoint origin;
    private final GeographicPoint southWest;
    private final GeographicPoint northEast;
    private final double eastMetersPerRadian;
    private final double northMetersPerRadian;

    private DistortionCertificate(String method, double maximumRelativeDistanceError,
        GeographicPoint origin, GeographicPoint southWest, GeographicPoint northEast,
        double eastMetersPerRadian, double northMetersPerRadian) {
        this.method = method;
        this.maximumRelativeDistanceError = maximumRelativeDistanceError;
        this.origin = origin;
        this.southWest = southWest;
        this.northEast = northEast;
        this.eastMetersPerRadian = eastMetersPerRadian;
        this.northMetersPerRadian = northMetersPerRadian;
    }

    /** Computes a conservative WGS84 local-tangent scale bound over the complete domain. */
    static DistortionCertificate wgs84Local(GeographicPoint origin,
        GeographicPoint southWest, GeographicPoint northEast, double longitudeSpanDegrees) {
        return compute(origin, southWest, northEast, longitudeSpanDegrees, true);
    }

    /** Historical Math arithmetic for explicitly identified v1 frame readers only. */
    static DistortionCertificate wgs84LocalLegacy(GeographicPoint origin,
        GeographicPoint southWest, GeographicPoint northEast, double longitudeSpanDegrees) {
        return compute(origin, southWest, northEast, longitudeSpanDegrees, false);
    }

    private static DistortionCertificate compute(GeographicPoint origin,
        GeographicPoint southWest, GeographicPoint northEast, double longitudeSpanDegrees,
        boolean deterministic) {
        if (origin == null || southWest == null || northEast == null
            || southWest.latitudeDegrees() > northEast.latitudeDegrees()
            || !Double.isFinite(longitudeSpanDegrees) || longitudeSpanDegrees < 0.0
            || longitudeSpanDegrees > 180.0) {
            throw new IllegalArgumentException("Distortion certificate domain is inconsistent");
        }
        if (deterministic && (southWest.latitudeDegrees() <= -90.0
                || northEast.latitudeDegrees() >= 90.0)) {
            throw new IllegalArgumentException("Certified local frame cannot include a pole");
        }
        double originLatitude = radians(origin.latitudeDegrees(), deterministic);
        double eastScale = eastScale(originLatitude, deterministic);
        double northScale = northScale(originLatitude, deterministic);
        if (!(eastScale > 0.0) || !(northScale > 0.0)) {
            throw new IllegalArgumentException("Certified local frame cannot include a pole");
        }
        double[] latitudes = southWest.latitudeDegrees() <= 0.0 && northEast.latitudeDegrees() >= 0.0
            ? new double[] {southWest.latitudeDegrees(), 0.0, northEast.latitudeDegrees()}
            : new double[] {southWest.latitudeDegrees(), northEast.latitudeDegrees()};
        double scaleError = 0.0;
        double maximumEastScale = 0.0;
        for (double latitudeDegrees : latitudes) {
            double latitude = radians(latitudeDegrees, deterministic);
            double localEast = eastScale(latitude, deterministic);
            double localNorth = northScale(latitude, deterministic);
            if (!(localEast > 0.0) || !(localNorth > 0.0)) {
                throw new IllegalArgumentException("Certified local frame cannot include a pole");
            }
            scaleError = Math.max(scaleError, Math.abs(eastScale / localEast - 1.0));
            scaleError = Math.max(scaleError, Math.abs(northScale / localNorth - 1.0));
            maximumEastScale = Math.max(maximumEastScale, localEast);
        }
        double northSpan = radians(northEast.latitudeDegrees() - southWest.latitudeDegrees(), deterministic)
            * Math.max(northScale(radians(southWest.latitudeDegrees(), deterministic), deterministic),
                northScale(radians(northEast.latitudeDegrees(), deterministic), deterministic));
        double eastSpan = radians(longitudeSpanDegrees, deterministic) * maximumEastScale;
        double angularDiameter = (deterministic ? StrictMath.hypot(northSpan, eastSpan)
                : Math.hypot(northSpan, eastSpan)) / WGS84_A;
        double finiteArcError = angularDiameter * angularDiameter / 12.0;
        double bound = scaleError + finiteArcError + scaleError * finiteArcError;
        return new DistortionCertificate(deterministic ? STRICT_METHOD : LEGACY_METHOD, bound,
            origin, southWest, northEast, eastScale, northScale);
    }

    /**
     * Independently validates exact v2 wire parameters against the deterministic analytic factory.
     * No numeric tolerance is accepted: an unknown Math backend is a legacy input, not a v2 proof.
     */
    static DistortionCertificate restoreStrict(String method, GeographicPoint origin,
            GeographicPoint southWest, GeographicPoint northEast, double longitudeSpanDegrees,
            double bound, double east, double north) {
        DistortionCertificate reference = wgs84Local(origin, southWest, northEast, longitudeSpanDegrees);
        if (!STRICT_METHOD.equals(method) || !reference.acceptable()
                || !sameBits(bound, reference.maximumRelativeDistanceError)
                || !sameBits(east, reference.eastMetersPerRadian)
                || !sameBits(north, reference.northMetersPerRadian)) {
            throw new IllegalArgumentException("Stored numerical frame is not the certified v2 transform");
        }
        return new DistortionCertificate(method, bound, origin, southWest, northEast, east, north);
    }

    private static boolean sameBits(double first, double second) {
        return Double.doubleToLongBits(first) == Double.doubleToLongBits(second);
    }

    private static double radians(double degrees, boolean deterministic) {
        return deterministic ? StrictFrameArithmetic.toRadians(degrees) : Math.toRadians(degrees);
    }

    /** Returns the versioned proof method. */
    public String method() { return method; }

    /** Returns the conservative maximum relative distance error. */
    public double maximumRelativeDistanceError() { return maximumRelativeDistanceError; }

    /** Returns the frame origin bound to this certificate. */
    public GeographicPoint origin() { return origin; }

    /** Returns the southwest domain corner. */
    public GeographicPoint southWest() { return southWest; }

    /** Returns the northeast domain corner. */
    public GeographicPoint northEast() { return northEast; }

    /** Returns the WGS84 east-axis scale at the frame origin. */
    public double eastMetersPerRadian() { return eastMetersPerRadian; }

    /** Returns the WGS84 north-axis scale at the frame origin. */
    public double northMetersPerRadian() { return northMetersPerRadian; }

    /** Returns whether this certificate meets the v0.22.0 0.1% requirement. */
    public boolean acceptable() {
        return Double.isFinite(maximumRelativeDistanceError)
            && maximumRelativeDistanceError >= 0.0 && maximumRelativeDistanceError <= LIMIT;
    }

    /** Returns whether this certificate is bound to the exact declared transform and origin. */
    public boolean supports(String projectionId, GeographicPoint requestedOrigin) {
        return (LEGACY_METHOD.equals(method) && LocalMetricFrame.LEGACY_PROJECTION_ID.equals(projectionId)
                || STRICT_METHOD.equals(method) && LocalMetricFrame.STRICT_PROJECTION_ID.equals(projectionId))
            && origin.equals(requestedOrigin);
    }

    private static double eastScale(double latitude, boolean deterministic) {
        double sin = deterministic ? StrictMath.sin(latitude) : Math.sin(latitude);
        double denominator = 1.0 - WGS84_E2 * sin * sin;
        double primeVertical = WGS84_A / (deterministic ? StrictMath.sqrt(denominator) : Math.sqrt(denominator));
        return primeVertical * (deterministic ? StrictMath.cos(latitude) : Math.cos(latitude));
    }

    private static double northScale(double latitude, boolean deterministic) {
        double sin = deterministic ? StrictMath.sin(latitude) : Math.sin(latitude);
        double base = 1.0 - WGS84_E2 * sin * sin;
        double denominator = deterministic ? StrictMath.pow(base, 1.5) : Math.pow(base, 1.5);
        return WGS84_A * (1.0 - WGS84_E2) / denominator;
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof DistortionCertificate other)) {
            return false;
        }
        return method.equals(other.method)
            && Double.doubleToLongBits(maximumRelativeDistanceError)
                == Double.doubleToLongBits(other.maximumRelativeDistanceError)
            && origin.equals(other.origin) && southWest.equals(other.southWest) && northEast.equals(other.northEast)
            && Double.doubleToLongBits(eastMetersPerRadian) == Double.doubleToLongBits(other.eastMetersPerRadian)
            && Double.doubleToLongBits(northMetersPerRadian) == Double.doubleToLongBits(other.northMetersPerRadian);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, maximumRelativeDistanceError, origin, southWest, northEast,
            eastMetersPerRadian, northMetersPerRadian);
    }
}
