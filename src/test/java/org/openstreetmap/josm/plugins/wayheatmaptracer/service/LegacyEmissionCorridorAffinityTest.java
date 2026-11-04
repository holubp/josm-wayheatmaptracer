package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.RetainedScalarProfiles;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Image-space adversary for preserving one corridor when two continuous strands swap strength. */
class LegacyEmissionCorridorAffinityTest {
    private static final String FIELD = "crossing-strength-strands";
    private static final int WIDTH = 128;
    private static final int HEIGHT = 128;
    private static final int FIRST_Y = 60;
    private static final int SECOND_Y = 68;

    @Test void nativeV2EntryRemainsAnExplicitSeparateStrengthSwapBaseline() {
        Fixture fixture = fixtureWithWeakBand(.60);
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var root = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(fixture.evidence(), FIELD,
                 fixture.input(), fixture.chainage(), 25, budget, root)) {
            var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(fixture.evidence(), FIELD,
                fixture.input().profileLocations(), 25, 1, 1, budget, permit);
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained,
                java.util.stream.IntStream.range(0, 11).boxed().toList(), 1, List.of());
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained, binding);
                 var result = new RidgeTracker().trackEmission(input, CancellationProbe.NONE)) {
                assertEquals("LEGACY_OPTIONAL_NATIVE_INTEGRAL_V2", result.sourceReference().modelPolicyId());
                assertTrue(result.affinity().isEmpty());
                assertTrue(result.inferenceVertices().stream().anyMatch(v -> v.position().yMeters() < 64));
                assertTrue(result.inferenceVertices().stream().anyMatch(v -> v.position().yMeters() > 64));
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void strengthSwapDoesNotSwitchBetweenTwoContinuousDarkSeparatedStrands() {
        Fixture fixture = fixture();
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        EmissionWorkBudget budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var root = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(fixture.evidence(), FIELD,
                 fixture.input(), fixture.chainage(), 25, budget, root)) {
            RetainedScalarProfiles retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(
                fixture.evidence(), FIELD, fixture.input().profileLocations(), 25, 1, 1, budget, permit);
            assertEquals(11, retained.profiles().levelZeroProfiles().size());
            var rowMap = java.util.stream.IntStream.range(0, 11).boxed().toList();
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained, rowMap, 1, List.of());
            var source = fixture.evidence().fields().get(FIELD);
            assertTrue(source.sample(10, FIRST_Y).orElseThrow() > 0.89);
            assertTrue(source.sample(10, SECOND_Y).orElseThrow() > 0.29);
            assertTrue(source.sample(90, FIRST_Y).orElseThrow() > 0.29);
            assertTrue(source.sample(90, SECOND_Y).orElseThrow() > 0.89);
            assertEquals(0.0, source.sample(50, 64).orElseThrow(), 0.0,
                "The two actual source strands remain separated by a dark raster gap");
            for (int row = 0; row < 11; row++) {
                CrossSectionProfile profile = retained.profiles().levelZeroProfiles().get(row);
                assertEquals(NativeConnectorEvaluator.SourceClass.OBSERVED, binding.observations().get(row).sourceClass());
                System.out.println("R4 affinity profile row=" + row + " peaks=" + profile.peaks().stream()
                    .map(peak -> peak.offsetPx() + "@" + peak.intensity() + " => y="
                        + (profile.anchorScreen().y + profile.normalScreen().y * peak.offsetPx()))
                    .toList());
                assertTrue(profile.peaks().stream().anyMatch(peak -> {
                    double y = profile.anchorScreen().y + profile.normalScreen().y * peak.offsetPx();
                    return Math.abs(y - FIRST_Y) < 1.0 || Math.abs(y - SECOND_Y) < 1.0;
                }), "Each source row must retain at least the stronger real raster strand: " + row);
            }

            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained, binding);
                 var result = new RidgeTracker().trackEmissionMeasuredAffinity(input, CancellationProbe.NONE)) {
                String evidence = summarize(result);
                System.out.println("Legacy strength-swap corridor evidence: " + evidence);
                assertEquals("LEGACY_OPTIONAL_MEASURED_AFFINITY_V1", result.sourceReference().modelPolicyId());
                assertEquals(22, result.affinity().orElseThrow().measuredStates().size());
                assertEquals(11, result.originReferences().stream().filter(o -> o.measuredMode().isPresent()).count());
                assertTrue(result.originReferences().stream().filter(o -> o.measuredMode().isPresent())
                    .allMatch(o -> o.observedPeak().isEmpty() && o.seed().isEmpty()));
                assertTrue(result.affinity().orElseThrow().fullyCertified());
                assertEquals(11, result.originalObservations().size(), evidence);
                assertTrue(result.inferenceVertices().size() >= 2, evidence);
                assertOneBand(result.inferenceVertices(), evidence);
                assertOneBand(result.smoothedVertices(), evidence);
                assertNoDarkArc(result.inferenceArcs(), evidence);
                assertNoDarkArc(result.postSmoothingArcs(), evidence);
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void strongerSecondaryBandSurvivesProducerAndSuppressionBeforeKernelCheck() throws Exception {
        Fixture fixture = fixtureWithWeakBand(0.60);
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        EmissionWorkBudget budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var root = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(fixture.evidence(), FIELD,
                 fixture.input(), fixture.chainage(), 25, budget, root)) {
            RetainedScalarProfiles retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(
                fixture.evidence(), FIELD, fixture.input().profileLocations(), 25, 1, 1, budget, permit);
            var profiles = retained.profiles().levelZeroProfiles();
            assertEquals(11, profiles.size());
            for (int row = 0; row < profiles.size(); row++) {
                assertTwoBands(profiles.get(row), row, "retained L0 producer");
            }

            Method suppression = RidgeTracker.class.getDeclaredMethod("suppressLongitudinalNoise", List.class);
            suppression.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<CrossSectionProfile> suppressed = (List<CrossSectionProfile>) suppression.invoke(
                new RidgeTracker(), profiles);
            assertEquals(11, suppressed.size());
            for (int row = 0; row < suppressed.size(); row++) {
                assertTwoBands(suppressed.get(row), row, "unchanged longitudinal suppression");
            }

            var rowMap = java.util.stream.IntStream.range(0, 11).boxed().toList();
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained, rowMap, 1, List.of());
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained, binding);
                 var result = new RidgeTracker().trackEmissionMeasuredAffinity(input, CancellationProbe.NONE)) {
                String evidence = summarize(result);
                System.out.println("Legacy stronger-band affinity control: " + evidence);
                assertEquals("LEGACY_OPTIONAL_MEASURED_AFFINITY_V1", result.sourceReference().modelPolicyId());
                assertTrue(result.affinity().orElseThrow().fullyCertified());
                assertEquals(11, result.originalObservations().size(), evidence);
                assertOneBand(result.inferenceVertices(), evidence);
                assertOneBand(result.smoothedVertices(), evidence);
                assertNoDarkArc(result.inferenceArcs(), evidence);
                assertNoDarkArc(result.postSmoothingArcs(), evidence);
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    private static void assertTwoBands(CrossSectionProfile profile, int row, String stage) {
        for (int y : new int[] { FIRST_Y, SECOND_Y }) {
            assertTrue(profile.peaks().stream().anyMatch(peak -> {
                double peakY = profile.anchorScreen().y + profile.normalScreen().y * peak.offsetPx();
                return Math.abs(peakY - y) < 1.0;
            }), stage + " must retain a genuine peak on y=" + y + " at row=" + row
                + "; peaks=" + profile.peaks().stream().map(peak -> peak.offsetPx() + "@" + peak.intensity()).toList());
        }
    }

    private static void assertOneBand(List<EmissionResult.Vertex> vertices, String evidence) {
        int selectedBand = -1;
        for (var vertex : vertices) {
            assertEquals(EmissionResult.StateKind.OBSERVED_PEAK, vertex.kind(), evidence);
            double y = vertex.position().yMeters();
            int band = Math.abs(y - FIRST_Y) < 1.0 ? FIRST_Y
                : Math.abs(y - SECOND_Y) < 1.0 ? SECOND_Y : -1;
            assertNotEquals(-1, band,
                "Every chosen vertex must sit on one of the two independent raster bands; " + evidence);
            if (selectedBand < 0) selectedBand = band;
            assertEquals(selectedBand, band,
                "The route must retain one corridor through the strength swap; " + evidence);
        }
    }

    private static void assertNoDarkArc(List<EmissionResult.Arc> arcs, String evidence) {
        assertFalse(arcs.isEmpty(), evidence);
        for (var arc : arcs) {
            assertTrue(arc.meanNativeIntensity().isPresent(), evidence);
            assertTrue(arc.meanNativeIntensity().orElseThrow() >= 0.20,
                "A selected connector cannot cross the dark gap between the one-pixel bands; " + evidence);
        }
    }

    private static String summarize(EmissionResult result) {
        return "vertices=" + result.inferenceVertices().stream()
                .map(vertex -> vertex.owner() + "@" + vertex.position() + "/" + vertex.kind())
                .toList()
            + ", finalVertices=" + result.smoothedVertices().stream()
                .map(vertex -> vertex.owner() + "@" + vertex.position() + "/" + vertex.kind())
                .toList()
            + ", arcs=" + result.inferenceArcs().stream().map(arc -> arc.kind() + "[Q=" + arc.q()
                + ",C=" + arc.c() + ",T=" + arc.t() + ",K=" + arc.k() + ",mean="
                + arc.meanNativeIntensity() + ",span=" + arc.sourceSpanMeters() + "]").toList()
            + ", finalArcs=" + result.postSmoothingArcs().stream().map(arc -> arc.kind() + "[Q=" + arc.q()
                + ",C=" + arc.c() + ",T=" + arc.t() + ",K=" + arc.k() + ",mean="
                + arc.meanNativeIntensity() + ",span=" + arc.sourceSpanMeters() + "]").toList()
            + ", stages=(" + result.firstStage() + "," + result.intervalStages() + ","
                + result.stitched() + "," + result.postSmoothing() + ")"
            + ", work=" + result.work() + ", peakBytes=" + result.peakRetainedBytes();
    }

    private record Fixture(EvidenceSnapshot evidence, CorridorTraceInput input, ProfileChainage chainage) { }

    private static Fixture fixture() {
        double[] values = new double[WIDTH * HEIGHT];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        for (int x = 10; x <= 90; x++) {
            double firstStrength = x < 50 ? 0.90 : 0.30;
            double secondStrength = x < 50 ? 0.30 : 0.90;
            values[FIRST_Y * WIDTH + x] = Math.rint(firstStrength * 255.0) / 255.0;
            values[SECOND_Y * WIDTH + x] = Math.rint(secondStrength * 255.0) / 255.0;
        }
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
            new GeographicPoint(-0.01, -0.01), new GeographicPoint(0.01, 0.01));
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1);
        var lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, FIELD,
            EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        var field = new ScalarEvidenceField(WIDTH, HEIGHT, values, valid, lineage);
        var bounds = MetricRegion.rectangle(0, 0, WIDTH - 1, HEIGHT - 1);
        var evidence = new EvidenceSnapshot("crossing-strength-strands", frame, transform,
            EvidenceResolution.nativeSource(1, 1), bounds,
            MetricRegion.rectangle(-0.5, -0.5, WIDTH - 0.5, HEIGHT - 0.5), Map.of(FIELD, field),
            "corridor-affinity-test");
        List<DetachedProfileSamplingLocation> locations = new ArrayList<>(11);
        List<Double> chainages = new ArrayList<>(11);
        for (int row = 0; row < 11; row++) {
            double chainage = row * 8.0;
            MetricPoint point = new MetricPoint(10 + chainage, 64);
            locations.add(DetachedProfileSamplingLocation.at(frame.toGeographic(point), frame, transform, chainage));
            chainages.add(chainage);
        }
        return new Fixture(evidence, new CorridorTraceInput(locations, 1), new ProfileChainage(chainages, 80));
    }

    private static Fixture fixtureWithWeakBand(double weakStrength) {
        double[] values = new double[WIDTH * HEIGHT];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        for (int x = 10; x <= 90; x++) {
            double firstStrength = x < 50 ? 0.90 : weakStrength;
            double secondStrength = x < 50 ? weakStrength : 0.90;
            values[FIRST_Y * WIDTH + x] = Math.rint(firstStrength * 255.0) / 255.0;
            values[SECOND_Y * WIDTH + x] = Math.rint(secondStrength * 255.0) / 255.0;
        }
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
            new GeographicPoint(-0.01, -0.01), new GeographicPoint(0.01, 0.01));
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1);
        var lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, FIELD,
            EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        var field = new ScalarEvidenceField(WIDTH, HEIGHT, values, valid, lineage);
        var bounds = MetricRegion.rectangle(0, 0, WIDTH - 1, HEIGHT - 1);
        var evidence = new EvidenceSnapshot("crossing-strength-strands-060", frame, transform,
            EvidenceResolution.nativeSource(1, 1), bounds,
            MetricRegion.rectangle(-0.5, -0.5, WIDTH - 0.5, HEIGHT - 0.5), Map.of(FIELD, field),
            "corridor-affinity-test-060");
        List<DetachedProfileSamplingLocation> locations = new ArrayList<>(11);
        List<Double> chainages = new ArrayList<>(11);
        for (int row = 0; row < 11; row++) {
            double chainage = row * 8.0;
            MetricPoint point = new MetricPoint(10 + chainage, 64);
            locations.add(DetachedProfileSamplingLocation.at(frame.toGeographic(point), frame, transform, chainage));
            chainages.add(chainage);
        }
        return new Fixture(evidence, new CorridorTraceInput(locations, 1), new ProfileChainage(chainages, 80));
    }
}
