package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Image-space geometry controls independent of the kernel's objective bookkeeping. */
class LegacyEmissionGeometryTest {
    private static final String FIELD = "geometry";

    @Test
    void sustainedBrightBendKeepsItsApexInsteadOfTakingADarkChord() {
        var fixture = fixture(false);
        solve(fixture, result -> {
            assertEquals(9, result.originalObservations().size());
            for (int x = 28; x <= 52; x += 4) {
                double actualY = interpolatedY(result.inferenceVertices(), x);
                assertEquals(bendY(x), actualY, 2.0,
                    "Selected physical path must follow the independent bright V at x=" + x);
            }
            assertTrue(interpolatedY(result.inferenceVertices(), 40) > 68,
                "A long optional chord across the dark side of the apex is unsafe");
            assertTrue(result.inferenceArcs().stream().anyMatch(arc -> arc.meanNativeIntensity().isPresent()));
        });
    }

    @Test
    void symmetricParallelBandsDoNotInviteAnUnsupportedCrossBandJump() {
        var fixture = fixture(true);
        solve(fixture, result -> {
            assertEquals(9, result.originalObservations().size());
            assertTrue(result.inferenceVertices().size() >= 2);
            double firstY = result.inferenceVertices().get(0).position().yMeters();
            double selectedBand = Math.abs(firstY - 60) < Math.abs(firstY - 68) ? 60 : 68;
            for (var vertex : result.inferenceVertices()) {
                assertEquals(EmissionResult.StateKind.OBSERVED_PEAK, vertex.kind());
                assertEquals(selectedBand, vertex.position().yMeters(), 1.5,
                    "A selected route cannot switch through the dark gap between equal bright bands");
            }
            for (var arc : result.inferenceArcs()) {
                assertTrue(arc.lengthMeters() > 0);
                assertTrue(arc.meanNativeIntensity().isPresent());
            }
        });
    }

    @Test
    void callerStateCeilingRefusesTwoObservedNativePeaksBeforeExpansion() {
        var fixture = fixture(true);
        var ledger = AttemptMemoryLedger.production();
        var limits = new TraceBudgets(1, 8_000_000, 128_000_000, 32, 8);
        var budget = new EmissionWorkBudget(limits, CancellationProbe.NONE);
        try (var root = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(
                 fixture.evidence(), FIELD, fixture.input(), fixture.chainage(), 12, budget, root)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(
                fixture.evidence(), FIELD, fixture.input().profileLocations(), 12, 1, 1, budget, permit);
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained,
                List.of(0, 1, 2, 3, 4, 5, 6, 7, 8), 1, List.of());
            assertTrue(binding.observations().get(0).producerPeaks().size() > 1);
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,
                    retained, binding)) {
                assertThrows(LegacyEmissionDomain.StateFamilyLimitException.class,
                    () -> new RidgeTracker().trackEmission(input, CancellationProbe.NONE));
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    private static void solve(Fixture fixture, java.util.function.Consumer<EmissionResult> assertion) {
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var root = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(
                 fixture.evidence(), FIELD, fixture.input(), fixture.chainage(), 12, budget, root)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(
                fixture.evidence(), FIELD, fixture.input().profileLocations(), 12, 1, 1, budget, permit);
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained,
                List.of(0, 1, 2, 3, 4, 5, 6, 7, 8), 1, List.of());
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,
                    retained, binding);
                 var result = new RidgeTracker().trackEmission(input, CancellationProbe.NONE)) {
                assertion.accept(result);
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    private static double interpolatedY(List<EmissionResult.Vertex> vertices, double x) {
        for (int index = 0; index + 1 < vertices.size(); index++) {
            var a = vertices.get(index).position(); var b = vertices.get(index + 1).position();
            if (a.xMeters() <= x && x <= b.xMeters()) {
                double fraction = (x - a.xMeters()) / (b.xMeters() - a.xMeters());
                return a.yMeters() + fraction * (b.yMeters() - a.yMeters());
            }
        }
        throw new AssertionError("Selected path does not cover source x=" + x);
    }

    private static double bendY(double x) {
        return 64 + 6 * Math.max(0, 1 - Math.abs(x - 40) / 16);
    }

    private record Fixture(EvidenceSnapshot evidence, CorridorTraceInput input,
            ProfileChainage chainage) { }

    private static Fixture fixture(boolean parallel) {
        int width = 96, height = 96;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            double strength = parallel
                ? Math.max(gaussian(y, 60, 0.8), gaussian(y, 68, 0.8))
                : gaussian(y, bendY(x), 0.9);
            values[y * width + x] = (float) Math.rint(255 * strength) / 255.0f;
        }
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
            new GeographicPoint(-0.01, -0.01), new GeographicPoint(0.01, 0.01));
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1);
        var field = new ScalarEvidenceField(width, height, values, valid,
            new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, FIELD,
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        var bounds = MetricRegion.rectangle(0, 0, width - 1, height - 1);
        var evidence = new EvidenceSnapshot(parallel ? "parallel-bands" : "bright-bend",
            frame, transform, EvidenceResolution.nativeSource(1, 1), bounds,
            MetricRegion.rectangle(-0.5, -0.5, width - 0.5, height - 0.5),
            Map.of(FIELD, field), "geometry-test");
        var locations = new ArrayList<DetachedProfileSamplingLocation>();
        for (int row = 0; row < 9; row++) {
            var point = new MetricPoint(24 + 4.0 * row, 64);
            locations.add(DetachedProfileSamplingLocation.at(frame.toGeographic(point), frame,
                transform, 4.0 * row));
        }
        return new Fixture(evidence, new CorridorTraceInput(locations, 1),
            new ProfileChainage(locations.stream()
                .map(DetachedProfileSamplingLocation::cumulativeGroundDistanceMeters).toList(), 32));
    }

    private static double gaussian(double y, double center, double sigma) {
        double t = (y - center) / sigma;
        return Math.exp(-0.5 * t * t);
    }
}
