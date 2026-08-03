package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;

/**
 * Measures ground metres represented by one JOSM projected-coordinate unit at slide-time anchors.
 *
 * @param eastMetersPerProjectionUnitMedian median east-axis ground scale
 * @param northMetersPerProjectionUnitMedian median north-axis ground scale
 * @param representativeMetersPerProjectionUnit median geometric-mean ground scale
 * @param minimumMetersPerProjectionUnit minimum geometric-mean scale over sampled anchors
 * @param maximumMetersPerProjectionUnit maximum geometric-mean scale over sampled anchors
 * @param anisotropyRatio maximum relative difference between east and north scale
 * @param longitudinalVariationRatio relative range of representative scale along the selected geometry
 * @param sampleCount number of slide-time anchors measured
 */
public record ProjectionGroundScale(
    double eastMetersPerProjectionUnitMedian,
    double northMetersPerProjectionUnitMedian,
    double representativeMetersPerProjectionUnit,
    double minimumMetersPerProjectionUnit,
    double maximumMetersPerProjectionUnit,
    double anisotropyRatio,
    double longitudinalVariationRatio,
    int sampleCount
) {
    private static final int[] QUANTILE_PERCENTAGES = {0, 25, 50, 75, 100};

    /** Validates that all measured scales are finite and positive. */
    public ProjectionGroundScale {
        requirePositive(eastMetersPerProjectionUnitMedian, "east ground scale");
        requirePositive(northMetersPerProjectionUnitMedian, "north ground scale");
        requirePositive(representativeMetersPerProjectionUnit, "representative ground scale");
        requirePositive(minimumMetersPerProjectionUnit, "minimum ground scale");
        requirePositive(maximumMetersPerProjectionUnit, "maximum ground scale");
        requireNonNegative(anisotropyRatio, "ground-scale anisotropy");
        requireNonNegative(longitudinalVariationRatio, "ground-scale variation");
        if (minimumMetersPerProjectionUnit > representativeMetersPerProjectionUnit
            || representativeMetersPerProjectionUnit > maximumMetersPerProjectionUnit) {
            throw new IllegalArgumentException("Representative ground scale must lie inside its measured range");
        }
        if (sampleCount < 1) {
            throw new IllegalArgumentException("At least one ground-scale sample is required");
        }
    }

    /**
     * Measures the active JOSM projection at five quantiles of the selected slide-time geometry.
     *
     * @param anchors selected geometry in JOSM projected coordinates
     * @param projectionUnitsPerViewPixel slide-time {@code MapView} scale
     * @return measured geographic ground scale
     */
    public static ProjectionGroundScale measure(
        List<EastNorth> anchors,
        double projectionUnitsPerViewPixel
    ) {
        if (anchors == null || anchors.isEmpty()) {
            throw new IllegalArgumentException("Ground-scale measurement requires at least one slide-time anchor");
        }
        requirePositive(projectionUnitsPerViewPixel, "projection units per view pixel");
        Projection projection = ProjectionRegistry.getProjection();
        if (projection == null) {
            throw new IllegalStateException("No active JOSM projection is available for ground-scale measurement");
        }
        double delta = projectionUnitsPerViewPixel * 100.0;
        List<AxisScale> measurements = new ArrayList<>();
        int previousIndex = -1;
        for (int percentage : QUANTILE_PERCENTAGES) {
            int index = (int) Math.round((anchors.size() - 1) * percentage / 100.0);
            if (index == previousIndex) {
                continue;
            }
            previousIndex = index;
            measurements.add(measureAt(projection, anchors.get(index), delta));
        }
        List<Double> east = measurements.stream().map(AxisScale::east).sorted().toList();
        List<Double> north = measurements.stream().map(AxisScale::north).sorted().toList();
        List<Double> representative = measurements.stream().map(AxisScale::representative).sorted().toList();
        double median = median(representative);
        double maximumAnisotropy = measurements.stream().mapToDouble(value ->
            Math.max(value.east(), value.north()) / Math.min(value.east(), value.north()) - 1.0).max().orElse(0.0);
        return new ProjectionGroundScale(
            median(east),
            median(north),
            median,
            representative.get(0),
            representative.get(representative.size() - 1),
            maximumAnisotropy,
            (representative.get(representative.size() - 1) - representative.get(0)) / median,
            measurements.size()
        );
    }

    private static AxisScale measureAt(Projection projection, EastNorth anchor, double delta) {
        if (anchor == null || !Double.isFinite(anchor.east()) || !Double.isFinite(anchor.north())) {
            throw new IllegalArgumentException("Ground-scale anchors must contain finite projected coordinates");
        }
        LatLon west = projection.eastNorth2latlon(new EastNorth(anchor.east() - delta, anchor.north()));
        LatLon east = projection.eastNorth2latlon(new EastNorth(anchor.east() + delta, anchor.north()));
        LatLon south = projection.eastNorth2latlon(new EastNorth(anchor.east(), anchor.north() - delta));
        LatLon north = projection.eastNorth2latlon(new EastNorth(anchor.east(), anchor.north() + delta));
        double eastScale = west.greatCircleDistance(east) / (2.0 * delta);
        double northScale = south.greatCircleDistance(north) / (2.0 * delta);
        requirePositive(eastScale, "east ground scale");
        requirePositive(northScale, "north ground scale");
        return new AxisScale(eastScale, northScale, Math.sqrt(eastScale * northScale));
    }

    private static double median(List<Double> sorted) {
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("Cannot calculate a median from no ground-scale samples");
        }
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0 ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0 : sorted.get(middle);
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    private static void requireNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private record AxisScale(double east, double north, double representative) {
    }
}
