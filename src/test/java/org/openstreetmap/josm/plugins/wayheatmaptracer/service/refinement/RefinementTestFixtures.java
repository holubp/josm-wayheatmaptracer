package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
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
        List<FinalRoutePointId> ids = pointIds("u", points.size());
        return new ImageSupportedLocalCleanup.Request(ids, points, Set.of(0, 3), Set.of(0, 3),
                image, corridor, ImageSupportedLocalCleanup.Mode.REDUCE_POINTS_ONLY,
                ImageSupportedRefitter.Config.defaults(1.0), 20.0,
                assignments(ids, points), List.of());
    }

    static ImageSupportedLocalCleanup.Request unlocalizedLimitedCleanupRequest() {
        List<MetricPoint> points = List.of(new MetricPoint(0, 0), new MetricPoint(5, 0),
                new MetricPoint(10, 3), new MetricPoint(15, 0), new MetricPoint(20, 0));
        List<FinalRoutePointId> ids = pointIds("limited", points.size());
        return new ImageSupportedLocalCleanup.Request(ids, points, Set.of(0, 4), Set.of(0, 4),
                constantImage(0.1), MetricRegion.rectangle(-40, -40, 40, 40),
                ImageSupportedLocalCleanup.Mode.REFIT_AND_REDUCE,
                ImageSupportedRefitter.Config.defaults(1.0).withIterationLimit(1), 0.45,
                assignments(ids, points), List.of());
    }

    static ImageSupportedLocalCleanup.Request cleanupOffRejectedByValidatorRequest() {
        List<MetricPoint> points = List.of(new MetricPoint(0, 0), new MetricPoint(10, 0));
        List<FinalRoutePointId> ids = pointIds("off", points.size());
        return new ImageSupportedLocalCleanup.Request(ids, points, Set.of(0, 1), Set.of(0, 1),
                constantImage(0.8), MetricRegion.rectangle(-40, -40, 40, 40),
                ImageSupportedLocalCleanup.Mode.OFF, ImageSupportedRefitter.Config.defaults(1.0),
                0.0, assignments(ids, points),
                List.of(geometry -> ImageSupportedRefitter.Validation.rejected("blocked")));
    }

    static ImageSupportedLocalCleanup.Request twoIslandCleanupRequest() {
        List<MetricPoint> points = List.of(
                new MetricPoint(10, 0), new MetricPoint(15, 0), new MetricPoint(20, 1),
                new MetricPoint(25, 0), new MetricPoint(30, 0), new MetricPoint(40, 0),
                new MetricPoint(50, 0), new MetricPoint(55, 0), new MetricPoint(60, -1),
                new MetricPoint(65, 0), new MetricPoint(70, 0));
        List<FinalRoutePointId> ids = pointIds("two-island", points.size());
        Set<Integer> retained = java.util.stream.IntStream.range(0, points.size())
                .boxed().collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new ImageSupportedLocalCleanup.Request(ids, points, retained, Set.of(0, 5, 10),
                straightGaussianWithGap(), MetricRegion.rectangle(0, -20, 89.5, 19.5),
                ImageSupportedLocalCleanup.Mode.REFIT_AND_REDUCE,
                ImageSupportedRefitter.Config.defaults(1.0), 0.45,
                assignments(ids, points), List.of());
    }

    private static List<FinalRoutePointId> pointIds(String candidate, int count) {
        List<FinalRoutePointId> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(new GeneratedCandidatePoint(candidate, index));
        }
        return List.copyOf(result);
    }

    private static Map<FinalRoutePointId, MetricPoint> assignments(
            List<FinalRoutePointId> ids, List<MetricPoint> points) {
        Map<FinalRoutePointId, MetricPoint> result = new java.util.LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            result.put(ids.get(index), points.get(index));
        }
        return Map.copyOf(result);
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

    private static ImageCostField straightGaussianWithGap() {
        int width = 180;
        int height = 80;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double metricX = 0.5 * x;
                double metricY = -20.0 + 0.5 * y;
                int index = y * width + x;
                valid[index] = metricX < 39.0 || metricX > 41.0;
                values[index] = valid[index]
                        ? 0.01 + 0.8 * Math.exp(-0.5 * metricY * metricY) : Double.NaN;
            }
        }
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, values, valid,
                new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                        EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
                        "two-island-gaussian", EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        return new ImageCostField(field,
                new RasterMetricTransform("two-island-gaussian-v1",
                        RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                        new MetricPoint(0, -20), 0.5, 0.0, 0.0, 0.5, 1.0),
                MetricRegion.rectangle(0, -20, 89.5, 19.5), 1.0);
    }
}
