package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;

/**
 * Binds one longitudinal sampling position across map, raster, and physical-distance spaces.
 *
 * @param sourceMapCoordinate source-way coordinate in the active JOSM projection
 * @param rasterX sampled-raster x coordinate in raster pixels
 * @param rasterY sampled-raster y coordinate in raster pixels
 * @param cumulativeGroundDistanceMeters cumulative geographic distance from the first profile, in metres
 */
public record ProfileSamplingAnchor(
    EastNorth sourceMapCoordinate,
    double rasterX,
    double rasterY,
    double cumulativeGroundDistanceMeters
) {
    /** Validates that every coordinate and the physical distance are finite and usable. */
    public ProfileSamplingAnchor {
        Objects.requireNonNull(sourceMapCoordinate, "sourceMapCoordinate");
        if (!Double.isFinite(sourceMapCoordinate.east()) || !Double.isFinite(sourceMapCoordinate.north())) {
            throw new IllegalArgumentException("Source map coordinate must be finite.");
        }
        if (!Double.isFinite(rasterX) || !Double.isFinite(rasterY)) {
            throw new IllegalArgumentException("Raster coordinate must be finite.");
        }
        if (!Double.isFinite(cumulativeGroundDistanceMeters) || cumulativeGroundDistanceMeters < 0.0) {
            throw new IllegalArgumentException("Cumulative ground distance must be finite and non-negative.");
        }
    }

    /**
     * Returns a fresh mutable point for raster calculations.
     *
     * @return sampled-raster coordinate
     */
    public Point2D.Double rasterCoordinate() {
        return new Point2D.Double(rasterX, rasterY);
    }

    /**
     * Pairs source and raster positions and measures cumulative geographic distance.
     *
     * @param sourceCoordinates source-way positions in the active JOSM projection
     * @param rasterCoordinates corresponding sampled-raster positions
     * @return immutable, index-aligned sampling anchors
     */
    public static List<ProfileSamplingAnchor> pair(
        List<EastNorth> sourceCoordinates,
        List<Point2D.Double> rasterCoordinates
    ) {
        Objects.requireNonNull(sourceCoordinates, "sourceCoordinates");
        Objects.requireNonNull(rasterCoordinates, "rasterCoordinates");
        if (sourceCoordinates.size() != rasterCoordinates.size()) {
            throw new IllegalArgumentException("Source and raster anchor counts must match.");
        }
        List<ProfileSamplingAnchor> result = new ArrayList<>(sourceCoordinates.size());
        double cumulative = 0.0;
        LatLon previous = null;
        for (int index = 0; index < sourceCoordinates.size(); index++) {
            EastNorth source = Objects.requireNonNull(sourceCoordinates.get(index), "source coordinate");
            Point2D.Double raster = Objects.requireNonNull(rasterCoordinates.get(index), "raster coordinate");
            LatLon current = ProjectionRegistry.getProjection().eastNorth2latlon(source);
            if (previous != null) {
                double step = previous.greatCircleDistance(current);
                if (!Double.isFinite(step) || step < 0.0) {
                    throw new IllegalArgumentException("Profile ground distance must be finite and non-negative.");
                }
                cumulative += step;
            }
            result.add(new ProfileSamplingAnchor(source, raster.x, raster.y, cumulative));
            previous = current;
        }
        return List.copyOf(result);
    }

    /**
     * Creates compatibility anchors when only raster geometry exists.
     *
     * <p>The cumulative value is deliberately raster distance and must only be used by legacy or isolated
     * sampler paths. Corridor-aware production callers must use {@link #pair(List, List)}.</p>
     *
     * @param rasterCoordinates sampled-raster positions
     * @return compatibility anchors preserving the historical raster coordinate contract
     */
    static List<ProfileSamplingAnchor> rasterOnly(List<Point2D.Double> rasterCoordinates) {
        List<ProfileSamplingAnchor> result = new ArrayList<>(rasterCoordinates.size());
        double cumulative = 0.0;
        Point2D.Double previous = null;
        for (Point2D.Double point : rasterCoordinates) {
            Point2D.Double current = Objects.requireNonNull(point, "raster coordinate");
            if (previous != null) {
                cumulative += previous.distance(current);
            }
            result.add(new ProfileSamplingAnchor(
                new EastNorth(current.x, current.y), current.x, current.y, cumulative));
            previous = current;
        }
        return List.copyOf(result);
    }
}
