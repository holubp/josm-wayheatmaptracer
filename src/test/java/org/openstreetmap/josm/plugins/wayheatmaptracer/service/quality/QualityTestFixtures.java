package org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.v022.SyntheticHeatmapScene;
import org.openstreetmap.josm.plugins.wayheatmaptracer.v022.V022Point;

/** Shared production-evidence adapter for final-geometry tests. */
final class QualityTestFixtures {
    private QualityTestFixtures() {
        // Utility class.
    }

    static ImageCostField image(SyntheticHeatmapScene scene) {
        double pitch = scene.rasterPitchMeters();
        int width = Math.max(2, (int) Math.floor((scene.bounds().maxX() - scene.bounds().minX()) / pitch));
        int height = Math.max(2, (int) Math.floor((scene.bounds().maxY() - scene.bounds().minY()) / pitch));
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double metricX = scene.pixelCenterX(x);
                double metricY = scene.pixelCenterY(y);
                int index = y * width + x;
                valid[index] = scene.evidenceMask().isObserved(metricX, metricY);
                values[index] = valid[index] ? scene.intensityAt(metricX, metricY) : Double.NaN;
            }
        }
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, values, valid,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "synthetic", EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        MetricRegion region = MetricRegion.rectangle(scene.bounds().minX(), scene.bounds().minY(),
                scene.bounds().maxX(), scene.bounds().maxY());
        return new ImageCostField(field, new RasterMetricTransform("synthetic-positive-y-v1",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(scene.pixelCenterX(0), scene.pixelCenterY(0)), pitch, 0.0, 0.0, pitch, 1.0), region, pitch);
    }

    static List<MetricPoint> metric(List<V022Point> points) {
        List<MetricPoint> result = new ArrayList<>(points.size());
        for (V022Point point : points) {
            result.add(new MetricPoint(point.x(), point.y()));
        }
        return List.copyOf(result);
    }

    static FinalGeometryEvaluator.Result constantFieldDogleg(FinalGeometryEvaluator evaluator) {
        List<MetricPoint> points = List.of(new MetricPoint(0, 0), new MetricPoint(5, 0),
                new MetricPoint(10, 3), new MetricPoint(15, 0), new MetricPoint(20, 0));
        return evaluator.evaluate(new FinalGeometryEvaluator.Request("plateau", points,
                constantImage(0.1), 1.0, Set.of(0, 4), List.of(), false, false, false, false));
    }

    static FinalGeometryEvaluator.Result incidentOverlap(FinalGeometryEvaluator evaluator) {
        List<MetricPoint> points = List.of(new MetricPoint(0, 0), new MetricPoint(10, 0),
                new MetricPoint(20, 0));
        List<MetricPoint> incident = List.of(new MetricPoint(0, 0), new MetricPoint(5, 0));
        return evaluator.evaluate(new FinalGeometryEvaluator.Request("overlap", points,
                constantImage(0.8), 1.0, Set.of(0, 2), List.of(incident), false, false, false, false));
    }

    static ImageCostField constantImage(double value) {
        int size = 100;
        double[] values = new double[size * size];
        boolean[] valid = new boolean[values.length];
        java.util.Arrays.fill(values, value);
        java.util.Arrays.fill(valid, true);
        ScalarEvidenceField field = new ScalarEvidenceField(size, size, values, valid,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
                        "synthetic", EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        MetricRegion region = MetricRegion.rectangle(-50, -50, 49, 49);
        return new ImageCostField(field,
                new RasterMetricTransform("synthetic-positive-y-v1",
                        RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                        new MetricPoint(-50, -50), 1.0, 0.0, 0.0, 1.0, 1.0), region, 1.0);
    }
}
