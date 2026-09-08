package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.image;

import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;

/** One validated image-space route alternative, retaining actual incoming vector bearings. */
public record DirectionalImagePath(List<MetricPoint> points, List<Double> headingsRadians,
    double objective, String branchSignature) {
    public DirectionalImagePath {
        if (points == null || points.size() < 2 || headingsRadians == null
            || headingsRadians.size() != points.size() - 1 || !Double.isFinite(objective)
            || branchSignature == null || branchSignature.isBlank()) {
            throw new IllegalArgumentException("Directional image path is incomplete");
        }
        points = List.copyOf(points);
        headingsRadians = List.copyOf(headingsRadians);
    }
}
