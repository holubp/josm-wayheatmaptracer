package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.List;

/** Immutable image-derived undirected orientation modes at one physical location. */
public record ImageOrientationSupport(Status status, List<AngularMode> modes, double certainty) {
    /** Why an image orientation is measured or unavailable. */
    public enum Status {
        MEASURED_TWO_SIDED,
        UNKNOWN_FLAT,
        INVALID_CENTER,
        INSUFFICIENT_TWO_SIDED_SUPPORT,
        RESOURCE_LIMIT,
        LEGACY_POINT_DIRECTIONS
    }

    /** One local maximum, retaining an equal circular plateau as one angular interval. */
    public record AngularMode(double startRadians, double endRadians,
        double peakBearingRadians, double response) {
        /** Validates normalized undirected bearings and a nonnegative response. */
        public AngularMode {
            if (!bearing(startRadians) || !bearing(endRadians) || !bearing(peakBearingRadians)
                || !Double.isFinite(response) || response < 0.0) {
                throw new IllegalArgumentException("Image orientation mode is invalid");
            }
        }

        /** Returns whether this plateau crosses the 180-to-0 degree boundary. */
        public boolean wraps() {
            return startRadians > endRadians;
        }

        /** Returns whether an undirected bearing lies within this retained plateau. */
        public boolean contains(double bearingRadians) {
            double normalized = normalize(bearingRadians);
            return wraps() ? normalized >= startRadians - 1e-12 || normalized <= endRadians + 1e-12
                : normalized >= startRadians - 1e-12 && normalized <= endRadians + 1e-12;
        }

        /** Returns the smallest undirected angular distance to the retained interval. */
        public double distanceTo(double bearingRadians) {
            if (contains(bearingRadians)) {
                return 0.0;
            }
            return Math.min(undirectedDistance(bearingRadians, startRadians),
                undirectedDistance(bearingRadians, endRadians));
        }
    }

    /** Validates immutable modes and truthful unknown status. */
    public ImageOrientationSupport {
        if (status == null || modes == null || !Double.isFinite(certainty)
            || certainty < 0.0 || certainty > 1.0) {
            throw new IllegalArgumentException("Image orientation support is invalid");
        }
        modes = List.copyOf(modes);
        boolean measured = status == Status.MEASURED_TWO_SIDED
            || status == Status.LEGACY_POINT_DIRECTIONS;
        if (measured != !modes.isEmpty() || measured != (certainty > 0.0)) {
            throw new IllegalArgumentException("Image orientation status does not match its evidence");
        }
    }

    /** Creates an unavailable image orientation with zero certainty. */
    public static ImageOrientationSupport unknown(Status status) {
        if (status == Status.MEASURED_TWO_SIDED || status == Status.LEGACY_POINT_DIRECTIONS) {
            throw new IllegalArgumentException("Measured status requires orientation modes");
        }
        return new ImageOrientationSupport(status, List.of(), 0.0);
    }

    /** Adapts point directions retained by older constructors without claiming image measurement. */
    public static ImageOrientationSupport legacy(List<Double> directions, double certainty) {
        if (directions == null || directions.isEmpty() || certainty == 0.0) {
            return unknown(Status.INSUFFICIENT_TWO_SIDED_SUPPORT);
        }
        List<AngularMode> modes = directions.stream().map(ImageOrientationSupport::normalize)
            .distinct().sorted().map(value -> new AngularMode(value, value, value, 1.0)).toList();
        return new ImageOrientationSupport(Status.LEGACY_POINT_DIRECTIONS, modes, certainty);
    }

    /** Returns representative bearings for diagnostics and the compatibility profile API. */
    public List<Double> supportedDirectionsRadians() {
        return modes.stream().map(AngularMode::peakBearingRadians).toList();
    }

    /** Returns the minimum squared sine mismatch to any retained angular interval. */
    public double mismatchSquared(double bearingRadians) {
        if (modes.isEmpty()) {
            return 0.0;
        }
        double distance = modes.stream().mapToDouble(mode -> mode.distanceTo(bearingRadians))
            .min().orElse(0.0);
        double sine = Math.sin(distance);
        return sine * sine;
    }

    /** Normalizes a directed bearing into the undirected half-circle. */
    public static double normalize(double bearingRadians) {
        if (!Double.isFinite(bearingRadians)) {
            throw new IllegalArgumentException("Orientation bearing must be finite");
        }
        double result = bearingRadians % Math.PI;
        if (result < 0.0) {
            result += Math.PI;
        }
        return result >= Math.PI ? 0.0 : result;
    }

    private static boolean bearing(double value) {
        return Double.isFinite(value) && value >= 0.0 && value < Math.PI;
    }

    private static double undirectedDistance(double first, double second) {
        double difference = Math.abs(normalize(first) - normalize(second));
        return Math.min(difference, Math.PI - difference);
    }
}
