package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Versioned reversible local WGS84 tangent frame admitted by a conservative analytic bound. */
public record LocalMetricFrame(String projectionId, GeographicPoint origin,
    DistortionCertificate distortionCertificate) {
    public static final String LEGACY_PROJECTION_ID = "local-wgs84-tangent-v1";
    public static final String STRICT_PROJECTION_ID = "local-wgs84-tangent-v2";
    /** Accepts only a factory-produced certificate bound to this exact transform and origin. */
    public LocalMetricFrame {
        if (projectionId == null || projectionId.isBlank() || origin == null || distortionCertificate == null
            || !distortionCertificate.supports(projectionId, origin) || !distortionCertificate.acceptable()
            || !contains(distortionCertificate, origin)) {
            throw new IllegalArgumentException("Local metric frame lacks an acceptable bound certificate");
        }
    }

    /** Creates a local WGS84 tangent frame with a bound over the complete declared domain. */
    public static LocalMetricFrame certifiedEquirectangular(GeographicPoint origin,
        GeographicPoint southWest, GeographicPoint northEast) {
        return certified(origin, southWest, northEast, false);
    }

    /**
     * Reads a historical frame with its historical Math arithmetic and incomplete numeric identity.
     * This is not a reconstruction of missing original coefficients or a v2 numeric attestation.
     */
    public static LocalMetricFrame legacyCertifiedEquirectangular(GeographicPoint origin,
        GeographicPoint southWest, GeographicPoint northEast) {
        return certified(origin, southWest, northEast, true);
    }

    private static LocalMetricFrame certified(GeographicPoint origin,
        GeographicPoint southWest, GeographicPoint northEast, boolean legacy) {
        if (southWest.latitudeDegrees() > northEast.latitudeDegrees()) {
            throw new IllegalArgumentException("Certified latitude domain is inverted");
        }
        double longitudeSpan = positiveLongitudeSpan(southWest.longitudeDegrees(), northEast.longitudeDegrees());
        if (longitudeSpan > 180.0) {
            throw new IllegalArgumentException("Local tangent domain crosses the long antimeridian arc");
        }
        DistortionCertificate certificate = legacy ? DistortionCertificate.wgs84LocalLegacy(
            origin, southWest, northEast, longitudeSpan) : DistortionCertificate.wgs84Local(
            origin, southWest, northEast, longitudeSpan);
        if (!contains(certificate, origin)) {
            throw new IllegalArgumentException("Frame origin must lie inside its certified domain");
        }
        return new LocalMetricFrame(legacy ? LEGACY_PROJECTION_ID : STRICT_PROJECTION_ID, origin, certificate);
    }

    /**
     * Restores the complete numerical v2 transform only after exact independent StrictMath proof.
     * The declared domain, analytic distortion bound (at most 0.1%), and both positive metre scales
     * must match the deterministic factory bit for bit; no epsilon or legacy repair is permitted.
     */
    public static LocalMetricFrame restoreCertified(String projectionId, String proofMethod,
            GeographicPoint origin, GeographicPoint southWest, GeographicPoint northEast,
            double bound, double eastMetersPerRadian, double northMetersPerRadian) {
        if (!STRICT_PROJECTION_ID.equals(projectionId) || origin == null || southWest == null
                || northEast == null || southWest.latitudeDegrees() > northEast.latitudeDegrees()) {
            throw new IllegalArgumentException("Stored frame identity or domain is invalid");
        }
        double longitudeSpan = positiveLongitudeSpan(southWest.longitudeDegrees(), northEast.longitudeDegrees());
        DistortionCertificate certificate = DistortionCertificate.restoreStrict(proofMethod,
                origin, southWest, northEast, longitudeSpan, bound,
                eastMetersPerRadian, northMetersPerRadian);
        return new LocalMetricFrame(projectionId, origin, certificate);
    }

    /** Whether this frame carries a complete deterministic v2 numerical attestation. */
    public boolean hasCompleteNumericalIdentity() {
        return STRICT_PROJECTION_ID.equals(projectionId);
    }

    /** Converts a geographic coordinate inside the certified domain to local ground metres. */
    public MetricPoint toMetric(GeographicPoint point) {
        if (!contains(distortionCertificate, point)) {
            throw new IllegalArgumentException("Coordinate lies outside the certified metric-frame domain");
        }
        if (hasCompleteNumericalIdentity()) {
            // Subtract/normalize, convert with one binary64 multiply, then scale; no reassociation.
            return new MetricPoint(
                StrictFrameArithmetic.toRadians(normalizeDelta(point.longitudeDegrees() - origin.longitudeDegrees()))
                    * distortionCertificate.eastMetersPerRadian(),
                StrictFrameArithmetic.toRadians(point.latitudeDegrees() - origin.latitudeDegrees())
                    * distortionCertificate.northMetersPerRadian());
        }
        return new MetricPoint(
            Math.toRadians(normalizeDelta(point.longitudeDegrees() - origin.longitudeDegrees()))
                * distortionCertificate.eastMetersPerRadian(),
            Math.toRadians(point.latitudeDegrees() - origin.latitudeDegrees())
                * distortionCertificate.northMetersPerRadian());
    }

    /** Converts a metric coordinate back to a geographic coordinate inside the certified domain. */
    public GeographicPoint toGeographic(MetricPoint point) {
        double latitude;
        double longitude;
        if (hasCompleteNumericalIdentity()) {
            // Divide, convert with one binary64 multiply, then add origin and normalize.
            latitude = origin.latitudeDegrees() + StrictFrameArithmetic.toDegrees(
                point.yMeters() / distortionCertificate.northMetersPerRadian());
            longitude = normalize(origin.longitudeDegrees() + StrictFrameArithmetic.toDegrees(
                point.xMeters() / distortionCertificate.eastMetersPerRadian()));
        } else {
            latitude = origin.latitudeDegrees() + Math.toDegrees(
                point.yMeters() / distortionCertificate.northMetersPerRadian());
            longitude = normalize(origin.longitudeDegrees() + Math.toDegrees(
                point.xMeters() / distortionCertificate.eastMetersPerRadian()));
        }
        GeographicPoint result = new GeographicPoint(latitude, longitude);
        if (!contains(distortionCertificate, result)) {
            throw new IllegalArgumentException("Metric coordinate lies outside the certified frame domain");
        }
        return result;
    }

    private static boolean contains(DistortionCertificate certificate, GeographicPoint point) {
        GeographicPoint southWest = certificate.southWest();
        GeographicPoint northEast = certificate.northEast();
        boolean latitude = point.latitudeDegrees() >= southWest.latitudeDegrees() - 1e-12
            && point.latitudeDegrees() <= northEast.latitudeDegrees() + 1e-12;
        double offset = normalizeDelta(point.longitudeDegrees() - southWest.longitudeDegrees());
        double span = positiveLongitudeSpan(southWest.longitudeDegrees(), northEast.longitudeDegrees());
        return latitude && offset >= -1e-12 && offset <= span + 1e-12;
    }

    private static double positiveLongitudeSpan(double west, double east) {
        double result = (east - west) % 360.0;
        return result < 0.0 ? result + 360.0 : result;
    }

    private static double normalizeDelta(double value) {
        double result = value % 360.0;
        if (result > 180.0) {
            result -= 360.0;
        } else if (result <= -180.0) {
            result += 360.0;
        }
        return result;
    }

    private static double normalize(double value) {
        double result = normalizeDelta(value);
        return result == -180.0 ? 180.0 : result;
    }
}
