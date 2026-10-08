package org.openstreetmap.josm.plugins.wayheatmaptracer;

import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;

/** Independent physical oracle for the public displaced-way fixture. */
final class PublicGuiSmokeOracle {
    static final double EARTH_RADIUS_METERS = 6_378_137.0;
    static final double BASE_LATITUDE = 0.01;
    static final double BASE_LONGITUDE = 0.01;

    private PublicGuiSmokeOracle() { }

    static GeographicPoint point(double eastMeters, double northMeters) {
        double latitude = BASE_LATITUDE + Math.toDegrees(northMeters / EARTH_RADIUS_METERS);
        double longitude = BASE_LONGITUDE + Math.toDegrees(eastMeters /
                (EARTH_RADIUS_METERS * Math.cos(Math.toRadians(BASE_LATITUDE))));
        return new GeographicPoint(latitude, longitude);
    }

    /**
     * The analytic ridge is 4*cos(pi*x/64) metres north of the source way,
     * clipped at zero. It reaches the source at +/-32 m before the +/-40 m
     * fixed endpoints. The central +/-10 m is well beyond those approach
     * zones, so every exported sample there must follow the measured ridge.
     */
    static void verifyFinalGeometry(List<GeographicPoint> geometry) {
        if (geometry == null || geometry.size() < 3) {
            throw new IllegalStateException("Public preview has no sampled interior geometry");
        }
        requireNear(geometry.get(0), point(-40, 0), 0.01, "first fixed endpoint");
        requireNear(geometry.get(geometry.size() - 1), point(40, 0), 0.01,
                "last fixed endpoint");
        int central = 0;
        double previousEast = Double.NEGATIVE_INFINITY;
        for (GeographicPoint sample : geometry) {
            double east = eastMeters(sample);
            double north = northMeters(sample);
            if (!Double.isFinite(east) || !Double.isFinite(north)
                    || east < previousEast - 0.25 || Math.abs(north) > 8.0) {
                throw new IllegalStateException("Public preview geometry is nonmonotonic or out of corridor");
            }
            previousEast = east;
            if (Math.abs(east) <= 10.0) {
                central++;
                double analyticNorth = 4.0 * Math.cos(Math.PI * east / 64.0);
                if (Math.abs(north - analyticNorth) > 1.8) {
                    throw new IllegalStateException("Public preview missed the analytic central ridge");
                }
            }
        }
        if (central == 0) {
            throw new IllegalStateException("Public preview has no central ridge sample");
        }
    }

    private static void requireNear(GeographicPoint actual, GeographicPoint expected,
            double toleranceMeters, String label) {
        double east = (actual.longitudeDegrees() - expected.longitudeDegrees())
                * Math.PI / 180.0 * EARTH_RADIUS_METERS
                * Math.cos(Math.toRadians(BASE_LATITUDE));
        double north = (actual.latitudeDegrees() - expected.latitudeDegrees())
                * Math.PI / 180.0 * EARTH_RADIUS_METERS;
        if (!Double.isFinite(east) || !Double.isFinite(north)
                || Math.hypot(east, north) > toleranceMeters) {
            throw new IllegalStateException("Public preview moved the " + label);
        }
    }

    private static double eastMeters(GeographicPoint point) {
        return Math.toRadians(point.longitudeDegrees() - BASE_LONGITUDE)
                * EARTH_RADIUS_METERS * Math.cos(Math.toRadians(BASE_LATITUDE));
    }

    private static double northMeters(GeographicPoint point) {
        return Math.toRadians(point.latitudeDegrees() - BASE_LATITUDE) * EARTH_RADIUS_METERS;
    }
}
