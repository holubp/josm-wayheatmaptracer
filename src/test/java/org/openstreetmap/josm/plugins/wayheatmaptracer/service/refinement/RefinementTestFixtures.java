package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.v022.SyntheticHeatmapScene;
import org.openstreetmap.josm.plugins.wayheatmaptracer.v022.V022Point;

/** Shared conversion of public synthetic scenes into immutable production refinement evidence. */
final class RefinementTestFixtures {
    private RefinementTestFixtures() {
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
        RasterMetricTransform transform = new RasterMetricTransform("synthetic-positive-y-v1",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(scene.pixelCenterX(0), scene.pixelCenterY(0)), pitch, 0.0, 0.0, pitch, 1.0);
        return new ImageCostField(field, transform, region, pitch);
    }

    static List<MetricPoint> metric(List<V022Point> points) {
        List<MetricPoint> result = new ArrayList<>(points.size());
        for (V022Point point : points) {
            result.add(new MetricPoint(point.x(), point.y()));
        }
        return List.copyOf(result);
    }

    static MetricRegion fullRegion(SyntheticHeatmapScene scene) {
        return MetricRegion.rectangle(scene.bounds().minX(), scene.bounds().minY(),
                scene.bounds().maxX(), scene.bounds().maxY());
    }

    static ImageSupportedLocalCleanup.Request uCorridorReductionRequest() {
        List<MetricPoint> points = List.of(new MetricPoint(0, 0), new MetricPoint(0, 10),
                new MetricPoint(10, 10), new MetricPoint(10, 0));
        MetricRegion corridor = new MetricRegion(List.of(
                List.of(new MetricPoint(-1, -1), new MetricPoint(1, -1),
                        new MetricPoint(1, 11), new MetricPoint(-1, 11)),
                List.of(new MetricPoint(-1, 9), new MetricPoint(11, 9),
                        new MetricPoint(11, 11), new MetricPoint(-1, 11)),
                List.of(new MetricPoint(9, -1), new MetricPoint(11, -1),
                        new MetricPoint(11, 11), new MetricPoint(9, 11))));
        ImageCostField image = constantImage(0.8);
        return new ImageSupportedLocalCleanup.Request(List.of("a", "b", "c", "d"), points,
                Set.of(0, 3), image, corridor, ImageSupportedLocalCleanup.Mode.REDUCE_POINTS_ONLY,
                ImageSupportedRefitter.Config.defaults(1.0), 20.0, Map.of(), List.of());
    }

    static ImageSupportedLocalCleanup.Request unlocalizedLimitedCleanupRequest() {
        List<MetricPoint> points = List.of(new MetricPoint(0, 0), new MetricPoint(5, 0),
                new MetricPoint(10, 3), new MetricPoint(15, 0), new MetricPoint(20, 0));
        return new ImageSupportedLocalCleanup.Request(List.of("a", "b", "c", "d", "e"), points,
                Set.of(0, 4), constantImage(0.1), MetricRegion.rectangle(-40, -40, 40, 40),
                ImageSupportedLocalCleanup.Mode.REFIT_AND_REDUCE,
                ImageSupportedRefitter.Config.defaults(1.0).withIterationLimit(1), 0.45,
                Map.of(), List.of());
    }

    static ImageSupportedLocalCleanup.Request cleanupOffRejectedByValidatorRequest() {
        List<MetricPoint> points = List.of(new MetricPoint(0, 0), new MetricPoint(10, 0));
        return new ImageSupportedLocalCleanup.Request(List.of("a", "b"), points, Set.of(0, 1),
                constantImage(0.8), MetricRegion.rectangle(-40, -40, 40, 40),
                ImageSupportedLocalCleanup.Mode.OFF, ImageSupportedRefitter.Config.defaults(1.0),
                0.0, Map.of(), List.of(geometry -> ImageSupportedRefitter.Validation.rejected("blocked")));
    }

    private static ImageCostField constantImage(double value) {
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
