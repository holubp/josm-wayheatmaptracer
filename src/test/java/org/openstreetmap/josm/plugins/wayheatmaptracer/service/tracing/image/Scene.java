package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.image;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;

/** Deterministic scalar-only scenes for CP08 graph and bound tests. */
final class Scene {
    private final int width;
    private final int height;
    private final double[] values;
    private final boolean[] valid;
    private final MetricPoint start;
    private final MetricPoint end;
    private MetricRegion decision;

    private Scene(int width, int height, MetricPoint start, MetricPoint end) {
        this.width = width;
        this.height = height;
        this.start = start;
        this.end = end;
        this.values = new double[width * height];
        Arrays.fill(values, 1e-4);
        this.valid = new boolean[values.length];
        Arrays.fill(valid, true);
        this.decision = MetricRegion.rectangle(0.0, 0.0, width - 1.0, height - 1.0);
    }

    static Scene straight(int width, int height) {
        Scene scene = uniform(width, height, new MetricPoint(1, height / 2.0), new MetricPoint(width - 2, height / 2.0));
        scene.draw(List.of(scene.start, scene.end), 1.0);
        return scene;
    }

    static Scene diagonal(int width, int height) {
        Scene scene = uniform(width, height, new MetricPoint(1, 1), new MetricPoint(width - 2, height - 2));
        scene.draw(List.of(scene.start, scene.end), 1.0);
        return scene;
    }

    static Scene uniform(int width, int height, MetricPoint start, MetricPoint end) {
        return new Scene(width, height, start, end);
    }

    MetricPoint start() { return start; }
    MetricPoint end() { return end; }

    void draw(List<MetricPoint> points, double value) {
        for (int segment = 1; segment < points.size(); segment++) {
            MetricPoint a = points.get(segment - 1);
            MetricPoint b = points.get(segment);
            int samples = Math.max(1, (int) Math.ceil(a.distanceTo(b) * 4));
            for (int index = 0; index <= samples; index++) {
                MetricPoint point = new MetricPoint(a.xMeters() + (b.xMeters() - a.xMeters()) * index / samples,
                    a.yMeters() + (b.yMeters() - a.yMeters()) * index / samples);
                int x = (int) Math.round(point.xMeters());
                int y = (int) Math.round(point.yMeters());
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        int sx = x + dx;
                        int sy = y + dy;
                        if (sx >= 0 && sx < width && sy >= 0 && sy < height) {
                            values[(height - 1 - sy) * width + sx] = value;
                        }
                    }
                }
            }
        }
    }

    void invalidate(int x, int y) { valid[(height - 1 - y) * width + x] = false; }
    void invalidateColumn(int x) { for (int y = 0; y < height; y++) invalidate(x, y); }
    void setDecisionMaximumY(double maximumY) { decision = MetricRegion.rectangle(0, 0, width - 1, maximumY); }
    void cutDecisionGap(double left, double right) {
        decision = new MetricRegion(List.of(List.of(new MetricPoint(0, 0), new MetricPoint(left, 0),
            new MetricPoint(left, height - 1), new MetricPoint(0, height - 1)),
            List.of(new MetricPoint(right, 0), new MetricPoint(width - 1, 0),
                new MetricPoint(width - 1, height - 1), new MetricPoint(right, height - 1))));
    }

    EvidenceSnapshot evidence() {
        RasterMetricTransform transform = RasterMetricTransform.visible(new MetricPoint(0, height - 1), 1.0);
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, values, valid,
            new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "synthetic",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
            new GeographicPoint(-1, -1), new GeographicPoint(1, 1));
        return new EvidenceSnapshot("scene", frame, transform, EvidenceResolution.nativeSource(1, 1), decision,
            MetricRegion.rectangle(0, 0, width - 1, height - 1), new LinkedHashMap<>(java.util.Map.of("scalar", field)),
            "synthetic-scene");
    }

    DirectionalImageSearchProblem problem(List<MetricPoint> anchors, DirectionalImageSearchProblem.Scope scope,
        boolean zeroHeuristic) {
        return problem(anchors, scope, zeroHeuristic, false);
    }

    DirectionalImageSearchProblem problem(List<MetricPoint> anchors, DirectionalImageSearchProblem.Scope scope,
        boolean zeroHeuristic, boolean topologyOnly) {
        RecoveryPermissions permissions = topologyOnly
            ? new RecoveryPermissions(false, 4, 4, org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy.REATTACH, false)
            : new RecoveryPermissions(true, 4, 12, org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy.FIXED, false);
        return new DirectionalImageSearchProblem(evidence(), "scalar", anchors,
            new TraceBudgets(96, 2_000_000, 8_000_000, 8, 4), permissions, scope, zeroHeuristic);
    }
}
