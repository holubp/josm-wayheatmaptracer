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

    @Test void explicitProtectedPolicyChangesOnlyEligibleNormalsAndRetainsOriginalFence() {
        var old = fixture();
        var anchors = new ArrayList<LegacyVisibleProfileSampler.Anchor>();
        for (int i = 0; i < 9; i++) {
            anchors.add(anchor(16 + 18 * i, 48 + 30 * Math.exp(-Math.pow(i - 4, 2) / 2.0), i));
        }
        var immediate = new LegacyVisibleProfileSampler.Input(old.raster(), anchors, List.of("hot"), old.options());
        var protectedInput = immediate.withNormalPolicy(LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1, List.of(0, 8));
        var fenced = immediate.withNormalPolicy(LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1, List.of(0, 4, 8));
        var sampler = new LegacyVisibleProfileSampler();
        var ordinary = sampler.sample(immediate, CancellationProbe.NONE).get(0).profiles();
        var wide = sampler.sample(protectedInput, CancellationProbe.NONE).get(0).profiles();
        var guarded = sampler.sample(fenced, CancellationProbe.NONE).get(0).profiles();
        assertEquals(LegacyProfileNormalPolicy.IMMEDIATE_V1, immediate.normalPolicy());
        assertTrue(immediate.fixedSourceIndices().isEmpty());
        assertNotEquals(ordinary.get(3).normalScreen().x, wide.get(3).normalScreen().x);
        assertEquals(ordinary.get(3), guarded.get(3), "The fixed original occurrence must fence the wider chord");
        assertEquals(immediate.anchors(), protectedInput.anchors());
        assertEquals(ordinary.size(), wide.size());
        assertEquals(ordinary.get(0), wide.get(0));
        var calls = new AtomicInteger();
        assertThrows(CancellationException.class, () -> sampler.sample(protectedInput, () -> calls.incrementAndGet() > 1));
        assertEquals(2, calls.get());
    }

    @Test void liveDegeneratePlanFallsBackBeforeFreezeButExplicitProtectedClaimIsRejected() {
        var old = fixture();
        var point = old.anchors().get(0);
        var flat = new LegacyVisibleProfileSampler.Input(old.raster(), List.of(point, point), List.of("hot"), old.options());
        var resolved = flat.withNormalPolicy(LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1, List.of(0, 1));
        assertEquals(LegacyProfileNormalPolicy.IMMEDIATE_V1, resolved.normalPolicy());
        assertEquals("degenerate-geometry", resolved.normalPolicyFallbackReason());
        assertEquals(List.of(0, 1), resolved.fixedSourceIndices());
        assertEquals(new LegacyVisibleProfileSampler().sample(flat, CancellationProbe.NONE),
                new LegacyVisibleProfileSampler().sample(resolved, CancellationProbe.NONE));
        assertThrows(IllegalArgumentException.class, () -> new LegacyVisibleProfileSampler.Input(
                old.raster(), flat.anchors(), flat.detectors(), flat.options(),
                LegacyProfileNormalPolicy.PROTECTED_SECANT3_V1, List.of(0, 1), ""));
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
