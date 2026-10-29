package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacySeedAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;

class LegacyVisibleProfileSamplerTest {
    @Test void frozenBridgeMatchesProductionRenderedSamplingAndLegacyTracking() {
        var input = fixture();
        var anchorValues = input.anchors();
        var referenceAnchors = anchorValues.stream().map(anchor -> new ProfileSamplingAnchor(
                new EastNorth(anchor.source().east(), anchor.source().north()),
                anchor.raster().x(), anchor.raster().y(), anchor.cumulativeGroundDistanceMeters())).toList();
        var referenceRaster = input.raster().image();
        var reference = new RenderedHeatmapSampler();
        var projector = (RenderedHeatmapSampler.RasterCoordinateProjector) (x, y) ->
                new EastNorth(input.raster().minimumEast() + x / 6.0
                                * input.raster().projectionUnitsPerViewPixel(),
                        input.raster().maximumNorth() - y / 6.0
                                * input.raster().projectionUnitsPerViewPixel());

        var actual = new LegacyVisibleProfileSampler().sample(input, CancellationProbe.NONE);

        assertEquals(List.of("hot", "blue"), actual.stream().map(
                LegacyVisibleProfileSampler.DetectorProfiles::detector).toList());
        var tracker = new LegacySeedAdapter();
        for (var detector : actual) {
            var expected = reference.sampleProfilesOnAnchors(referenceRaster, referenceAnchors,
                    input.options().halfWidthPx(), input.options().stepPx(), detector.detector(),
                    RenderedHeatmapSampler.RASTER_SCALE, 1.0, input.options().intensityMode(), projector);
            assertEquals(expected, detector.profiles());
            var expectedCandidates = new RidgeTracker().track(expected,
                    input.options().trackerNormalizationRasterPx());
            var actualCandidates = tracker.track(detector.profiles(),
                    input.options().trackerNormalizationRasterPx());
            assertEquals(expectedCandidates.size(), actualCandidates.size());
            for (int index = 0; index < expectedCandidates.size(); index++) {
                assertEquals(expectedCandidates.get(index).offsetsPx(), actualCandidates.get(index).offsetsPx());
                assertEquals(expectedCandidates.get(index).score(), actualCandidates.get(index).score());
            }
        }
        assertEquals(List.of(new ProjectedPoint(2, 14), new ProjectedPoint(20, 12)),
                new LegacyVisibleProfileSampler().project(input,
                        List.of(new RasterPoint(12, 12), new RasterPoint(120, 24))));
    }

    @Test void frozenCoordinatesDoNotDependOnCurrentProjection() {
        var previous = ProjectionRegistry.getProjection();
        var baseline = previous == null ? Projections.getProjectionByCode("EPSG:3857") : previous;
        if (previous == null) ProjectionRegistry.setProjection(baseline);
        try {
            var input = fixture();
            var sampler = new LegacyVisibleProfileSampler();
            var before = sampler.sample(input, CancellationProbe.NONE);
            var expectedProjected = List.of(new ProjectedPoint(2, 14));
            ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:4326"));
            var after = sampler.sample(input, CancellationProbe.NONE);
            var projected = sampler.project(input, List.of(new RasterPoint(12, 12)));
            assertEquals(before, after);
            assertEquals(expectedProjected, projected);
            assertEquals(List.of("hot", "blue"), before.stream().map(
                    LegacyVisibleProfileSampler.DetectorProfiles::detector).toList());
        } finally {
            ProjectionRegistry.setProjection(baseline);
        }
    }

    @Test void inputsAreCopiedAndInvalidAssociationsAreRejected() {
        var input = fixture();
        var mutable = new ArrayList<>(input.detectors());
        var copied = new LegacyVisibleProfileSampler.Input(input.raster(), input.anchors(), mutable,
                input.options());
        mutable.clear();
        assertEquals(List.of("hot", "blue"), copied.detectors());
        assertThrows(UnsupportedOperationException.class, () -> copied.detectors().add("purple"));
        var badAnchor = new LegacyVisibleProfileSampler.Anchor(new ProjectedPoint(999, 999),
                input.anchors().get(0).raster(), 0);
        assertThrows(IllegalArgumentException.class, () -> new LegacyVisibleProfileSampler.Input(
                input.raster(), List.of(badAnchor, input.anchors().get(1)), input.detectors(), input.options()));
        assertThrows(IllegalArgumentException.class, () -> new LegacyVisibleProfileSampler.Input(
                input.raster(), input.anchors(), List.of("hot", "hot"), input.options()));
    }

    @Test void samplingChecksCancellationBetweenProfiles() {
        var checks = new AtomicInteger();
        assertThrows(CancellationException.class, () -> new LegacyVisibleProfileSampler().sample(
                fixture(), () -> checks.incrementAndGet() > 1));
        assertEquals(2, checks.get());
    }

    private static LegacyVisibleProfileSampler.Input fixture() {
        int width = 192, height = 96;
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int intensity = Math.max(0, 255 - Math.abs(y - 48) * 18);
            int hot = 0xFF000000 | (intensity << 16) | (Math.max(0, intensity - 90) << 8)
                    | Math.max(0, intensity - 180);
            int blue = 0xFF000000 | (Math.max(0, intensity - 180) << 16)
                    | (Math.max(0, intensity - 90) << 8) | intensity;
            for (int x = 0; x < width; x++) pixels[y * width + x] = (x < width / 2) ? hot : blue;
        }
        var raster = new LiveBPreviewService.VisibleRaster(width, height, pixels,
                0, 0, 32, 16, 1.0, 1.0, OptionalDouble.empty(), "synthetic", "EPSG:3857");
        var anchors = List.of(anchor(12, 12, 0), anchor(48, 24, 7.5), anchor(84, 36, 15),
                anchor(120, 48, 22.5));
        return new LegacyVisibleProfileSampler.Input(raster, anchors, List.of("hot", "blue"),
                new LegacyVisibleProfileSampler.Options(5, 1, 1.75, IntensitySamplingMode.COLOR_MAPPING));
    }

    private static LegacyVisibleProfileSampler.Anchor anchor(double x, double y, double chainage) {
        return new LegacyVisibleProfileSampler.Anchor(new ProjectedPoint(x / 6.0, 16 - y / 6.0),
                new RasterPoint(x, y), chainage);
    }
}
