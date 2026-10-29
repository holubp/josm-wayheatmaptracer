package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.geom.Point2D;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RidgeTracker;

class LegacySeedAdapterTest {
    @Test void finalSeedUsesNativeFinishAndRebasesExactProtectedOccurrences() {
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
                new GeographicPoint(-.01, -.01), new GeographicPoint(.01, .01));
        var original = List.of(new ProjectedPoint(0, 0), new ProjectedPoint(5, 0), new ProjectedPoint(10, 0));
        var originalMetric = List.of(new MetricPoint(0, 0), new MetricPoint(5.000000000001, 0),
                new MetricPoint(10.000000000001, 0));
        var trace = List.of(new ProjectedPoint(0, .1), new ProjectedPoint(2.5, .1),
                new ProjectedPoint(5, .1), new ProjectedPoint(7.5, .1), new ProjectedPoint(10, .1));

        var seed = new LegacySeedAdapter().finishSeed("legacy-final", original, originalMetric,
                trace, List.of(1), new OccurrenceRange(7, 9), "EPSG:3857", frame, true, .5);

        assertEquals(3, seed.points().size());
        assertEquals(Map.of(7, 0, 8, 1, 9, 2), seed.originalToVertex());
        assertEquals(originalMetric, seed.points());
    }

    @Test void finalSeedPropagatesLostProtectedOccurrence() {
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
                new GeographicPoint(-.01, -.01), new GeographicPoint(.01, .01));
        var source = List.of(new ProjectedPoint(0, 0), new ProjectedPoint(1, 1),
                new ProjectedPoint(5, 0), new ProjectedPoint(10, 0));
        var metric = List.of(new MetricPoint(0, 0), new MetricPoint(1, 1),
                new MetricPoint(5, 0), new MetricPoint(10, 0));

        assertThrows(LegacyPreciseGeometry.ProtectedOccurrenceLostException.class,
                () -> new LegacySeedAdapter().finishSeed("lost-anchor", source, metric,
                        source, List.of(1), new OccurrenceRange(20, 23), "EPSG:3857", frame, false, 0));
    }

    @Test void reconstructionPreservesOriginalAnchorBitsAndUsesCapturedProjectionUnits() {
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
                new GeographicPoint(-.01, -.01), new GeographicPoint(.01, .01));
        var original = List.of(new ProjectedPoint(0, 0), new ProjectedPoint(5, 0), new ProjectedPoint(10, 0));
        // Original authority is retained exactly; do not recreate it with a rounded projection round trip.
        var originalMetric = List.of(new MetricPoint(0, 0), new MetricPoint(5.000000000001, 0),
                new MetricPoint(10.000000000001, 0));
        var trace = List.of(new ProjectedPoint(0, 14), new ProjectedPoint(2.5, 14),
                new ProjectedPoint(5, 14), new ProjectedPoint(7.5, 14), new ProjectedPoint(10, 14));
        var seed = new LegacySeedAdapter().reconstructSeed("legacy-0", original, originalMetric,
                trace, List.of(1), new OccurrenceRange(7, 9), "EPSG:3857", frame);
        assertEquals(Map.of(7, 0, 8, 2, 9, 4), seed.originalToVertex());
        assertEquals(originalMetric.get(0), seed.points().get(0));
        assertEquals(originalMetric.get(1), seed.points().get(2));
        assertEquals(originalMetric.get(2), seed.points().get(4));
        assertEquals(2.5, seed.points().get(1).xMeters(), 1e-8);
        assertEquals(13.90627868, seed.points().get(1).yMeters(), 1e-5);
        assertThrows(IllegalArgumentException.class, () -> new LegacySeedAdapter().reconstructSeed(
                "bad", original, originalMetric, trace, List.of(1), new OccurrenceRange(7, 10),
                "EPSG:3857", frame));
    }

    @Test void cancellationInterruptsLegacyWorkAfterItHasStarted() {
        var profiles = IntStream.range(0, 80).mapToObj(i ->
                new RenderedHeatmapSampler.CrossSectionProfile(new EastNorth(i, 0),
                        new Point2D.Double(i, 0), new Point2D.Double(0, 1),
                        List.of(new RenderedHeatmapSampler.CrossSectionPeak(14, .85, 2, false)))).toList();
        var checks = new AtomicInteger();
        assertThrows(CancellationException.class, () -> new LegacySeedAdapter().track(
                profiles, 1, () -> checks.incrementAndGet() == 40));
        assertEquals(40, checks.get());
    }

    @Test void legacyAdapterUsesProductionLegacyNumericsForLargeDeviation() {
        var profiles = IntStream.range(0, 5).mapToObj(i ->
                new RenderedHeatmapSampler.CrossSectionProfile(new EastNorth(i * 10, 0),
                        new Point2D.Double(i * 10, 0), new Point2D.Double(0, 1),
                        List.of(new RenderedHeatmapSampler.CrossSectionPeak(14, .85, 2, false)))).toList();
        var result = new LegacySeedAdapter().track(profiles, 1);
        assertFalse(result.isEmpty());
        assertEquals(List.of(14.0, 14.0, 14.0, 14.0, 14.0), result.get(0).offsetsPx());
        var original = new RidgeTracker().track(profiles, 1);
        assertEquals(original.size(), result.size());
        for (int i = 0; i < result.size(); i++) {
            assertEquals(original.get(i).offsetsPx(), result.get(i).offsetsPx());
            assertEquals(original.get(i).score(), result.get(i).score());
            assertEquals(original.get(i).screenPoints(), result.get(i).screenPoints());
        }
    }

    @Test void absentLegacyProfilesYieldNoInventedSeed() {
        assertTrue(new LegacySeedAdapter().track(List.of(), 1).isEmpty());
    }
}
