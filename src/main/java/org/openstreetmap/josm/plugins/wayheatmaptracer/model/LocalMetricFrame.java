package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/** Versioned reversible local metric frame admitted by a conservative analytic distortion bound. */
public record LocalMetricFrame(String projectionId, GeographicPoint origin,
    DistortionCertificate distortionCertificate) {
    private static final double EARTH_RADIUS_METERS = 6_378_137.0;

    /** Accepts only a factory-produced certificate bound to this exact transform and origin. */
    public LocalMetricFrame {
        if (projectionId == null || projectionId.isBlank() || origin == null || distortionCertificate == null
            || !distortionCertificate.supports(projectionId, origin) || !distortionCertificate.acceptable()
            || !contains(distortionCertificate, origin)) {
            throw new IllegalArgumentException("Local metric frame lacks an acceptable bound certificate");
        }
    }

    /** Creates a local equirectangular frame with a bound over the complete declared domain. */
    public static LocalMetricFrame certifiedEquirectangular(GeographicPoint origin,
        GeographicPoint southWest, GeographicPoint northEast) {
        if (southWest.latitudeDegrees() > northEast.latitudeDegrees()) {
            throw new IllegalArgumentException("Certified latitude domain is inverted");
        }
        double longitudeSpan = positiveLongitudeSpan(southWest.longitudeDegrees(), northEast.longitudeDegrees());
        if (longitudeSpan > 180.0) {
            throw new IllegalArgumentException("Local equirectangular domain crosses the long antimeridian arc");
        }
        DistortionCertificate certificate = DistortionCertificate.equirectangular(
            origin, southWest, northEast, longitudeSpan);
        if (!contains(certificate, origin)) {
            throw new IllegalArgumentException("Frame origin must lie inside its certified domain");
        }
        return new LocalMetricFrame("local-equirectangular-v3", origin, certificate);
    }

    /** Converts a geographic coordinate inside the certified domain to local ground metres. */
    public MetricPoint toMetric(GeographicPoint point) {
        if (!contains(distortionCertificate, point)) {
            throw new IllegalArgumentException("Coordinate lies outside the certified metric-frame domain");
        }
        double cosLatitude = Math.cos(Math.toRadians(origin.latitudeDegrees()));
        return new MetricPoint(EARTH_RADIUS_METERS
                * Math.toRadians(normalizeDelta(point.longitudeDegrees() - origin.longitudeDegrees()))
                * cosLatitude,
            EARTH_RADIUS_METERS * Math.toRadians(point.latitudeDegrees() - origin.latitudeDegrees()));
    }

    /** Converts a metric coordinate back to a geographic coordinate inside the certified domain. */
    public GeographicPoint toGeographic(MetricPoint point) {
        double latitude = origin.latitudeDegrees() + Math.toDegrees(point.yMeters() / EARTH_RADIUS_METERS);
        double longitude = normalize(origin.longitudeDegrees() + Math.toDegrees(point.xMeters()
            / (EARTH_RADIUS_METERS * Math.cos(Math.toRadians(origin.latitudeDegrees())))));
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
        double offset = positiveLongitudeSpan(southWest.longitudeDegrees(), point.longitudeDegrees());
        double span = positiveLongitudeSpan(southWest.longitudeDegrees(), northEast.longitudeDegrees());
        return latitude && offset <= span + 1e-12;
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
