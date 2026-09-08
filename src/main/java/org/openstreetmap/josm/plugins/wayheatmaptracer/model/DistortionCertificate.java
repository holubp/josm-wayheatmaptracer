package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.Objects;

/** Non-forgeable conservative distortion certificate over a complete geographic domain. */
public final class DistortionCertificate {
    private static final double LIMIT = 0.001;
    private final String method;
    private final double maximumRelativeDistanceError;
    private final GeographicPoint origin;
    private final GeographicPoint southWest;
    private final GeographicPoint northEast;

    private DistortionCertificate(String method, double maximumRelativeDistanceError,
        GeographicPoint origin, GeographicPoint southWest, GeographicPoint northEast) {
        this.method = method;
        this.maximumRelativeDistanceError = maximumRelativeDistanceError;
        this.origin = origin;
        this.southWest = southWest;
        this.northEast = northEast;
    }

    /** Computes the conservative whole-domain bound for the local equirectangular transform. */
    static DistortionCertificate equirectangular(GeographicPoint origin,
        GeographicPoint southWest, GeographicPoint northEast, double longitudeSpanDegrees) {
        if (origin == null || southWest == null || northEast == null
            || southWest.latitudeDegrees() > northEast.latitudeDegrees()
            || !Double.isFinite(longitudeSpanDegrees) || longitudeSpanDegrees < 0.0
            || longitudeSpanDegrees > 180.0) {
            throw new IllegalArgumentException("Distortion certificate domain is inconsistent");
        }
        double originCosine = Math.cos(Math.toRadians(origin.latitudeDegrees()));
        double southCosine = Math.cos(Math.toRadians(southWest.latitudeDegrees()));
        double northCosine = Math.cos(Math.toRadians(northEast.latitudeDegrees()));
        double minimumCosine = Math.min(southCosine, northCosine);
        double maximumCosine = southWest.latitudeDegrees() <= 0.0 && northEast.latitudeDegrees() >= 0.0
            ? 1.0 : Math.max(southCosine, northCosine);
        if (minimumCosine <= 1e-8 || originCosine <= 1e-8) {
            throw new IllegalArgumentException("Certified local frame cannot include a pole");
        }
        double scaleError = Math.max(Math.abs(originCosine / minimumCosine - 1.0),
            Math.abs(originCosine / maximumCosine - 1.0));
        double latitudeSpan = Math.toRadians(northEast.latitudeDegrees() - southWest.latitudeDegrees());
        double longitudeSpan = Math.toRadians(longitudeSpanDegrees);
        double angularDiameter = Math.hypot(latitudeSpan, longitudeSpan * maximumCosine);
        double finiteArcError = angularDiameter * angularDiameter / 12.0;
        double bound = scaleError + finiteArcError + scaleError * finiteArcError;
        return new DistortionCertificate("equirectangular-v3-analytic-bound", bound,
            origin, southWest, northEast);
    }

    /** Returns the versioned proof method. */
    public String method() {
        return method;
    }

    /** Returns the conservative maximum relative distance error. */
    public double maximumRelativeDistanceError() {
        return maximumRelativeDistanceError;
    }

    /** Returns the frame origin bound to this certificate. */
    public GeographicPoint origin() {
        return origin;
    }

    /** Returns the southwest domain corner. */
    public GeographicPoint southWest() {
        return southWest;
    }

    /** Returns the northeast domain corner. */
    public GeographicPoint northEast() {
        return northEast;
    }

    /** Returns whether this certificate meets the v0.22.0 0.1% requirement. */
    public boolean acceptable() {
        return Double.isFinite(maximumRelativeDistanceError)
            && maximumRelativeDistanceError >= 0.0 && maximumRelativeDistanceError <= LIMIT;
    }

    /** Returns whether this certificate is bound to the exact declared transform and origin. */
    public boolean supports(String projectionId, GeographicPoint requestedOrigin) {
        return "local-equirectangular-v3".equals(projectionId) && origin.equals(requestedOrigin);
    }

    @Override
    public boolean equals(Object object) {
        if (!(object instanceof DistortionCertificate other)) {
            return false;
        }
        return method.equals(other.method)
            && Double.doubleToLongBits(maximumRelativeDistanceError)
                == Double.doubleToLongBits(other.maximumRelativeDistanceError)
            && origin.equals(other.origin) && southWest.equals(other.southWest) && northEast.equals(other.northEast);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, maximumRelativeDistanceError, origin, southWest, northEast);
    }
}
