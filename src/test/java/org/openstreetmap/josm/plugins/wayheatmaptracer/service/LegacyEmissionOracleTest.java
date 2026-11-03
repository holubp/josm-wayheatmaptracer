package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EmissionResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionPeak;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.RetainedScalarProfiles;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Independent exhaustive row-path oracle over an actual retained bright raster fixture. */
class LegacyEmissionOracleTest {
    private static final int WIDTH = 96;
    private static final int HEIGHT = 96;
    private static final double RIDGE_Y = 64.5;
    private static final double GUIDE_Y = 64.22;
    private static final String FIELD = "bright";

    @Test
    void exhaustiveOrdinaryAndAdmittedOptionalPathsMatchActualEmission() throws Exception {
        Fixture f = fixture();
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        EmissionWorkBudget budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(f.evidence(), FIELD,
                 f.input(), f.chainage(), 25, budget, owner)) {
            RetainedScalarProfiles retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(
                f.evidence(), FIELD, f.input().profileLocations(), 25, 1, 1, budget, permit);
            var producerProfiles = retained.profiles().levelZeroProfiles();
            List<Integer> rowMap = List.of(0, 1, 2);
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained, rowMap, 24, List.of());
            try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained, binding)) {
                List<CrossSectionProfile> suppressed = invokeProfiles(new RidgeTracker(), producerProfiles);
                @SuppressWarnings("unchecked")
                List<Double> seeds = (List<Double>) invoke(new RidgeTracker(), "collectSeedOffsets", suppressed);
                assertEquals(List.of(0.0), seeds);
                for (int row = 0; row < 3; row++) {
                    assertEquals(1, suppressed.get(row).peaks().size());
                    assertEquals(NativeConnectorEvaluator.SourceClass.OBSERVED,
                        binding.observations().get(row).sourceClass());
                }
                double measuredSeedDeparture = Math.abs(suppressed.get(0).peaks().get(0).offsetPx() - seeds.get(0));
                assertTrue(measuredSeedDeparture > 0.05,
                    "The actual sampled peak must exercise a nonzero initial seed departure");

                List<NativeConnectorEvaluator.RowState> evaluatorStates = new ArrayList<>();
                List<NativeConnectorEvaluator.RowState> byRow = new ArrayList<>();
                for (int row = 0; row < 3; row++) {
                    CrossSectionPeak peak = suppressed.get(row).peaks().get(0);
                    int nativeId = binding.observations().get(row).producerPeaks().stream()
                        .filter(p -> p.peak() == peak).findFirst().orElseThrow().originalPeakId();
                    var state = new NativeConnectorEvaluator.RowState(row, nativeId, peak.offsetPx(),
                        NativeConnectorEvaluator.SourceClass.OBSERVED, peak);
                    byRow.add(state);
                    evaluatorStates.add(state);
                }
                try (var evaluator = new NativeConnectorEvaluator(input,
                        new NativeConnectorEvaluator.Domain(evaluatorStates, List.of(0, 2)))) {
                    var optional = evaluator.evaluate(byRow.get(0), byRow.get(2));
                    assertEquals(NativeConnectorEvaluator.Status.ADMITTED, optional.status(), optional.toString());
                    double analyticMean = analyticHorizontalMean(suppressed.get(0), byRow.get(0).offsetRasterPixels());
                    assertEquals(analyticMean, optional.scalar().orElseThrow().mean().orElseThrow(), 1e-12);

                    List<OraclePath> allPaths = enumerateThreeRowPaths(suppressed, byRow, optional,
                        seeds.get(0), input.context().nativePitchMeters().orElseThrow());
                    assertEquals(2, allPaths.size(), "Direct ordinary path and admitted 0-to-2 optional path");
                    OraclePath expected = allPaths.stream().min(LegacyEmissionOracleTest::compare).orElseThrow();
                    assertEquals(0, expected.consumedRows(), "Exact objective tie prefers fewer consumed rows");
                    assertEquals(2, expected.arcKinds().size());
                    assertEquals(0.16 * measuredSeedDeparture, expected.firstC(), 1e-12);
                    assertEquals(0.12 * measuredSeedDeparture, expected.firstT(), 1e-12);
                    assertEquals(0.28 * measuredSeedDeparture,
                        expected.firstC() + expected.firstT(), 1e-12);
                }

                try (EmissionResult actual = new RidgeTracker().trackEmission(input, CancellationProbe.NONE)) {
                    List<CrossSectionProfile> originals = binding.observations().stream()
                        .map(LegacyEmissionBinding.OriginalObservation::profile).toList();
                    @SuppressWarnings("unchecked")
                    List<Double> actualSeeds = (List<Double>) invoke(new RidgeTracker(), "collectSeedOffsets",
                        invokeProfiles(new RidgeTracker(), originals));
                    List<NativeConnectorEvaluator.RowState> states = new ArrayList<>();
                    for (int row = 0; row < 3; row++) {
                        var peak = originals.get(row).peaks().get(0);
                        int id = binding.observations().get(row).producerPeaks().stream()
                            .filter(p -> p.peak() == peak).findFirst().orElseThrow().originalPeakId();
                        states.add(new NativeConnectorEvaluator.RowState(row, id, peak.offsetPx(),
                            binding.observations().get(row).sourceClass(), peak));
                    }
                    try (var evaluator = new NativeConnectorEvaluator(input,
                            new NativeConnectorEvaluator.Domain(states, List.of(0, 2)))) {
                        var optional = evaluator.evaluate(states.get(0), states.get(2));
                        List<OraclePath> expectedPaths = enumerateThreeRowPaths(originals, states, optional,
                            actualSeeds.get(0), input.context().nativePitchMeters().orElseThrow());
                        OraclePath expected = expectedPaths.stream().min(LegacyEmissionOracleTest::compare).orElseThrow();
                        assertEquals(3, actual.originalObservations().size());
                        assertEquals(3, actual.inferenceVertices().size());
                        assertEquals(2, actual.inferenceArcs().size());
                        for (int i = 0; i < 3; i++) {
                            assertEquals(expected.stateIds().get(i), actual.inferenceVertices().get(i)
                                .nativeStateId().orElseThrow());
                            assertEquals(expected.offsets().get(i), actual.inferenceVertices().get(i)
                                .offsetRasterPixels().orElseThrow(), 1e-12);
                        }
                        assertEquals(expected.c(), actual.firstStage().c(), 1e-12);
                        assertEquals(expected.t(), actual.firstStage().t(), 1e-12);
                        assertEquals(expected.q(), actual.stitched().q(), 1e-10);
                        assertEquals(expected.c(), actual.stitched().c(), 1e-12);
                        assertEquals(expected.t(), actual.stitched().t(), 1e-12);
                        assertEquals(expected.k(), actual.stitched().k(), 1e-12);
                        assertEquals(expected.total(), actual.stitched().total(), 1e-10);
                        for (int i = 0; i < 2; i++) {
                            assertEquals(expected.arcQ().get(i), actual.inferenceArcs().get(i).q(), 1e-10);
                            assertEquals(expected.arcC().get(i), actual.inferenceArcs().get(i).c(), 1e-12);
                            assertEquals(expected.arcT().get(i), actual.inferenceArcs().get(i).t(), 1e-12);
                            assertEquals(expected.arcK().get(i), actual.inferenceArcs().get(i).k(), 1e-12);
                            assertEquals(expected.arcSourceSpans().get(i),
                                actual.inferenceArcs().get(i).sourceSpanMeters(), 1e-12);
                            assertEquals(expected.arcLengths().get(i),
                                actual.inferenceArcs().get(i).lengthMeters(), 1e-12);
                        }
                    }
                }
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    private static List<CrossSectionProfile> invokeProfiles(RidgeTracker tracker,
            List<CrossSectionProfile> profiles) throws Exception {
        @SuppressWarnings("unchecked")
        List<CrossSectionProfile> result = (List<CrossSectionProfile>) invoke(tracker,
            "suppressLongitudinalNoise", profiles);
        return result;
    }

    private static Object invoke(Object target, String name, Object argument) throws Exception {
        Method method = Arrays.stream(target.getClass().getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(name) && candidate.getParameterCount() == 1)
            .findFirst().orElseThrow();
        method.setAccessible(true);
        return method.invoke(target, argument);
    }

    private static List<OraclePath> enumerateThreeRowPaths(List<CrossSectionProfile> profiles,
            List<NativeConnectorEvaluator.RowState> states, NativeConnectorEvaluator.Receipt optional,
            double seed, double pitch) {
        List<OraclePath> paths = new ArrayList<>();
        double firstOffset = states.get(0).offsetRasterPixels();
        double departure = Math.abs(firstOffset - seed);
        List<Double> q = new ArrayList<>(), c = new ArrayList<>(), t = new ArrayList<>(), k = new ArrayList<>();
        List<Double> spans = new ArrayList<>(), lengths = new ArrayList<>();
        List<NativeConnectorEvaluator.Status> kinds = new ArrayList<>();
        q.add(0.0); c.add(0.16 * departure); t.add(0.12 * departure); k.add(0.0);
        walk(0, profiles, states, optional, seed, pitch, firstOffset - seed,
            new ArrayList<>(List.of(0)), new ArrayList<>(List.of(firstOffset)), q, c, t, k, spans, lengths,
            kinds, paths);
        return List.copyOf(paths);
    }

    private static void walk(int row, List<CrossSectionProfile> profiles,
            List<NativeConnectorEvaluator.RowState> states, NativeConnectorEvaluator.Receipt optional,
            double seed, double pitch, double priorSlope, List<Integer> rows, List<Double> offsets,
            List<Double> q, List<Double> c, List<Double> t, List<Double> k,
            List<Double> spans, List<Double> lengths,
            List<NativeConnectorEvaluator.Status> kinds, List<OraclePath> paths) {
        if (row == 2) {
            double qsum = q.stream().mapToDouble(Double::doubleValue).sum();
            double csum = c.stream().mapToDouble(Double::doubleValue).sum();
            double tsum = t.stream().mapToDouble(Double::doubleValue).sum();
            double ksum = k.stream().mapToDouble(Double::doubleValue).sum();
            int consumed = 2 - rows.size() + 1;
            List<Integer> pathStateIds = rows.stream().map(index -> states.get(index).nativeStateId()).toList();
            paths.add(new OraclePath(pathStateIds, List.copyOf(offsets), qsum, csum, tsum, ksum, consumed,
                c.get(0), t.get(0), List.copyOf(q.subList(1, q.size())),
                List.copyOf(c.subList(1, c.size())), List.copyOf(t.subList(1, t.size())),
                List.copyOf(k.subList(1, k.size())), List.copyOf(spans), List.copyOf(lengths), List.copyOf(kinds)));
            return;
        }
        for (int next = row + 1; next <= 2; next++) {
            boolean isOptional = next > row + 1;
            if (isOptional && optional.status() != NativeConnectorEvaluator.Status.ADMITTED) continue;
            double first = states.get(row).offsetRasterPixels();
            double second = states.get(next).offsetRasterPixels();
            double slope = (second - first) / (next - row);
            double span = profiles.get(next).cumulativeGroundDistanceMeters()
                - profiles.get(row).cumulativeGroundDistanceMeters();
            double mean = analyticHorizontalMean(profiles.get(row), first);
            MetricPoint from = point(profiles.get(row), first);
            MetricPoint to = point(profiles.get(next), second);
            double turn = 0;
            if (rows.size() >= 2) {
                int priorRow = rows.get(rows.size() - 2);
                MetricPoint priorFrom = point(profiles.get(priorRow), offsets.get(offsets.size() - 2));
                MetricPoint priorTo = point(profiles.get(row), offsets.get(offsets.size() - 1));
                turn = turnCost(priorFrom, priorTo, from, to, pitch);
            }
            double transitionQ = span / pitch * (1 - mean);
            double transitionC = 0.16 * Math.abs(second - first);
            double transitionT = 0.12 * Math.abs(slope - priorSlope);
            offsets.add(second); q.add(transitionQ); c.add(transitionC);
            t.add(transitionT); k.add(turn); kinds.add(isOptional
                ? NativeConnectorEvaluator.Status.ADMITTED : NativeConnectorEvaluator.Status.REJECTED);
            rows.add(next); // The new row is active while the recursive suffix is enumerated.
            spans.add(span); lengths.add(from.distanceTo(to));
            walk(next, profiles, states, optional, seed, pitch, slope, rows, offsets, q, c, t, k,
                spans, lengths, kinds, paths);
            rows.remove(rows.size()-1); spans.remove(spans.size()-1); lengths.remove(lengths.size()-1);
            offsets.remove(offsets.size()-1); q.remove(q.size()-1);
            c.remove(c.size()-1); t.remove(t.size()-1); k.remove(k.size()-1); kinds.remove(kinds.size()-1);
        }
    }

    private static MetricPoint point(CrossSectionProfile profile, double offset) {
        var anchor = profile.anchorScreen(); var normal = profile.normalScreen();
        return new MetricPoint(anchor.x + normal.x * offset, anchor.y + normal.y * offset);
    }

    private static double turnCost(MetricPoint previousFrom, MetricPoint previousTo,
            MetricPoint from, MetricPoint to, double pitch) {
        double ax = previousTo.xMeters() - previousFrom.xMeters();
        double ay = previousTo.yMeters() - previousFrom.yMeters();
        double bx = to.xMeters() - from.xMeters();
        double by = to.yMeters() - from.yMeters();
        double previousLength = previousFrom.distanceTo(previousTo);
        double nextLength = from.distanceTo(to);
        double angle = Math.atan2(ax * by - ay * bx, ax * bx + ay * by);
        return angle * angle / Math.max(1, (previousLength + nextLength) / (2 * pitch));
    }

    private static double analyticHorizontalMean(CrossSectionProfile profile, double offset) {
        double y = profile.anchorScreen().y + profile.normalScreen().y * offset;
        int lower = (int) Math.floor(y);
        double fraction = y - lower;
        return scalarNode(lower) + fraction * (scalarNode(lower + 1) - scalarNode(lower));
    }

    private static double scalarNode(int y) {
        return (float) Math.rint(255 * Math.exp(-0.5 * Math.pow((y - RIDGE_Y) / 0.8, 2))) / 255.0f;
    }

    private static int compare(OraclePath a, OraclePath b) {
        int objective = Double.compare(a.total(), b.total());
        if (objective != 0) return objective;
        int consumed = Integer.compare(a.consumedRows(), b.consumedRows());
        if (consumed != 0) return consumed;
        for (int i = 0; i < Math.min(a.stateIds().size(), b.stateIds().size()); i++) {
            int id = Integer.compare(a.stateIds().get(i), b.stateIds().get(i));
            if (id != 0) return id;
        }
        return Integer.compare(a.stateIds().size(), b.stateIds().size());
    }

    private record OraclePath(List<Integer> stateIds, List<Double> offsets,
            double q, double c, double t, double k, int consumedRows,
            double firstC, double firstT, List<Double> arcQ, List<Double> arcC,
            List<Double> arcT, List<Double> arcK, List<Double> arcSourceSpans,
            List<Double> arcLengths,
            List<NativeConnectorEvaluator.Status> arcKinds) {
        double total() { return q + c + t + k; }
    }

    private record Fixture(EvidenceSnapshot evidence, CorridorTraceInput input, ProfileChainage chainage) { }

    private static Fixture fixture() {
        double[] values = new double[WIDTH * HEIGHT];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        for (int y = 0; y < HEIGHT; y++) {
            double intensity = scalarNode(y);
            Arrays.fill(values, y * WIDTH, (y + 1) * WIDTH, intensity);
        }
        var origin = new GeographicPoint(0, 0);
        var frame = LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(-0.01, -0.01), new GeographicPoint(0.01, 0.01));
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1);
        var field = new ScalarEvidenceField(WIDTH, HEIGHT, values, valid,
            new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, FIELD,
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        var evidence = new EvidenceSnapshot("legacy-oracle-fixture", frame, transform,
            EvidenceResolution.nativeSource(1, 1), MetricRegion.rectangle(0, 0, WIDTH - 1, HEIGHT - 1),
            MetricRegion.rectangle(-0.5, -0.5, WIDTH - 0.5, HEIGHT - 0.5), Map.of(FIELD, field), "oracle-test");
        List<DetachedProfileSamplingLocation> locations = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            MetricPoint point = new MetricPoint(24 + 8.0 * i, GUIDE_Y);
            locations.add(DetachedProfileSamplingLocation.at(frame.toGeographic(point), frame, transform, 8.0 * i));
        }
        List<Double> distances = locations.stream()
            .map(DetachedProfileSamplingLocation::cumulativeGroundDistanceMeters).toList();
        return new Fixture(evidence, new CorridorTraceInput(locations, 1), new ProfileChainage(distances, 16));
    }
}
