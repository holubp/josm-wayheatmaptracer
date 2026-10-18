package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

/** Baseline production-factory replay of the original weak-coherence analytic bend. */
class V022BOriginalReceiptShapeRegressionTest {
    private static final List<MetricPoint> SOURCE = List.of(
            new MetricPoint(10, 30), new MetricPoint(110, 30));
    private static final double NATIVE_BOUND_METERS = 1.8;

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.3, 0.6})
    void baselineNativeAndCoarsenedProductionTraceRetainOriginalBendAndReceipts(double phase) {
        var raster = V022LocalizationEvidenceTest.snapshot((x, y) -> scalar(x, y, phase));
        var evidence = new EvidenceSnapshot("original-receipt-shape-phase-" + phase,
                raster.coordinateFrame(), raster.transform(),
                EvidenceResolution.nativeSource(NATIVE_BOUND_METERS, 1), raster.decisionRegion(),
                raster.evidenceRegion(), raster.fields(), "original-receipt-shape-analytic");
        var profiles = new ProbabilisticProfileFactory().create(SOURCE, 1.5, 25.0, true,
                evidence, evidence.fields().get("scalar"));

        assertFalse(profiles.isEmpty());
        assertTrue(profiles.stream().allMatch(profile -> profile.nativePitchKnown()
                && profile.sourcePitchMeters() == NATIVE_BOUND_METERS));
        var profileFacts = new BIdentificationFactsExtractor().extract(
                evidence, profiles, CancellationProbe.NONE, null);
        assertEquals(profiles.size(), profileFacts.size());
        for (int index = 0; index < profiles.size(); index++) {
            assertSame(profiles.get(index), profileFacts.get(index).baseline(),
                    "facts must preserve the exact factory profile");
        }
        assertTrue(hasOriginalReceiptNearBend(profileFacts),
                "the real factory must expose measured original receipt facts near the bend");

        var nativeRun = trace(evidence, 128_000_000L);
        var coarseRun = trace(evidence, 250_000L);
        double nativeError = centralErrorOrNaN(nativeRun.points(), phase);
        double coarseError = centralErrorOrNaN(coarseRun.points(), phase);
        // Retain complete public baseline geometry/work in the JUnit XML for the
        // later candidate comparison, including when a fixed quality bound fails.
        System.out.println("ORIGINAL_RECEIPT_BASELINE phase=" + phase
                + " nativeErrorMeters=" + nativeError + " coarseErrorMeters=" + coarseError
                + " native=" + nativeRun + " coarse=" + coarseRun);

        assertAll("phase=" + phase,
                () -> assertTrue(isUsable(nativeRun.status()),
                        "native baseline: " + nativeRun.status() + ": " + nativeRun.explanation()),
                () -> assertTrue(isUsable(coarseRun.status()),
                        "coarsened baseline: " + coarseRun.status() + ": " + coarseRun.explanation()),
                () -> assertTrue(nativeRun.transitions() > 0),
                () -> assertTrue(coarseRun.transitions() > 0),
                () -> assertEquals(0L, nativeRun.coarsened(), "native baseline must retain full lattice"),
                () -> assertEquals(1L, coarseRun.coarsened(), "coarse baseline must exercise admission coarsening"),
                () -> assertTrue(nativeRun.transitions() <= nativeRun.transitionLimit()),
                () -> assertTrue(coarseRun.transitions() <= coarseRun.transitionLimit()),
                () -> assertTrue(hasExactEndpoints(nativeRun.points())),
                () -> assertTrue(hasExactEndpoints(coarseRun.points())),
                () -> assertTrue(nativeError <= NATIVE_BOUND_METERS,
                        "original native 1.8 m bend bound; measured=" + nativeError),
                () -> assertTrue(coarseError <= NATIVE_BOUND_METERS,
                        "original coarsened 1.8 m bend bound; native=" + nativeError
                                + ", measured=" + coarseError));
    }

    private static boolean hasOriginalReceiptNearBend(List<BIdentificationFacts.ProfileFacts> facts) {
        for (var profile : facts) {
            if (Math.abs(profile.baseline().anchor().xMeters() - 60.0) > 4.0) continue;
            for (var mode : profile.modes()) {
                boolean measuredScalars = mode.fact(BIdentificationFacts.Feature.BACKGROUND).availability()
                        == BIdentificationFacts.Availability.MEASURED
                        && mode.fact(BIdentificationFacts.Feature.PROMINENCE).availability()
                        == BIdentificationFacts.Availability.MEASURED
                        && mode.fact(BIdentificationFacts.Feature.SHOULDER_THRESHOLD).availability()
                        == BIdentificationFacts.Availability.MEASURED;
                boolean originalStage = mode.provenance().stream()
                        .filter(BIdentificationFacts.StageReceipt.class::isInstance)
                        .map(BIdentificationFacts.StageReceipt.class::cast)
                        .anyMatch(receipt -> receipt.original().available()
                                && receipt.original().stage() == LocalScalarProfileExtractor.ObservationStage.RAW
                                && mode.fact(BIdentificationFacts.Feature.RAW_CENTER).value().orElse(Double.NaN)
                                    == receipt.original().centerOffsetMeters());
                boolean rawPeak = mode.provenance().stream()
                        .anyMatch(BIdentificationFacts.RawReceipt.class::isInstance);
                if (measuredScalars && originalStage && rawPeak) return true;
            }
        }
        return false;
    }

    private static Run trace(EvidenceSnapshot evidence, long maximumTransitions) {
        PrimitiveKey first = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 1);
        PrimitiveKey last = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 2);
        PrimitiveKey way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 3);
        Map<PrimitiveKey, DetachedPrimitive> primitives = new LinkedHashMap<>();
        primitives.put(first, new DetachedNode(first,
                evidence.coordinateFrame().toGeographic(SOURCE.get(0)), Map.of(), false, false));
        primitives.put(last, new DetachedNode(last,
                evidence.coordinateFrame().toGeographic(SOURCE.get(1)), Map.of(), false, false));
        primitives.put(way, new DetachedWay(way, List.of(first, last), Map.of("highway", "path"), false, false));
        var region = evidence.decisionRegion();
        var closure = new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,
                "original-receipt-shape-baseline", primitives.keySet(), java.util.Set.of(way),
                java.util.Set.of(), java.util.Set.of(first, last), java.util.Set.of(),
                Map.of(way, List.of(new OccurrenceRange(0, 1))), List.of(), region, region,
                false, true, true, true);
        var network = new NetworkSnapshot("original-receipt-shape-network", SnapshotRole.CAPTURED_BEFORE,
                "synthetic-public-analytic", 1, closure, primitives,
                V022SnapshotFixtures.closedWorldReferrerWatches(primitives));
        var budgets = new TraceBudgets(96, 8_000_000, maximumTransitions, 1, 1);
        var request = new TraceRequest(way, new OccurrenceRange(0, 1), TrackerMode.PROBABILISTIC,
                AlignmentMode.PRECISE_SHAPE, RecoveryPermissions.disabled(25.0), budgets,
                evidence.snapshotId(), evidence.canonicalHash(), network.snapshotId(), network.canonicalHash(),
                "original-receipt-shape-settings", "default-evidence-parameters", "analytic-scalar", 1.5,
                new ProbabilisticProfileFactory().profileChainage(SOURCE, 1.5), evidence.resolution());
        ModernDiagnosticCounters.begin();
        try {
            var run = new ProbabilisticTraceEngine("scalar").traceWithUsage(
                    request, evidence, network, CancellationProbe.NONE);
            var result = run.result();
            var points = result.hypotheses().isEmpty() ? List.<MetricPoint>of()
                    : result.hypotheses().get(0).points();
            return new Run(result.status(), result.explanation(), points, run.usage().transitions(),
                    maximumTransitions, ModernDiagnosticCounters.snapshot()
                            .get("trace.latticeBudgetCoarsened").longValue());
        } finally {
            ModernDiagnosticCounters.end();
        }
    }

    private static double centralErrorOrNaN(List<MetricPoint> points, double phase) {
        if (points.isEmpty()) return Double.NaN;
        // Preserve the original weak-coherence island's x=60 +/-4m selector.
        return points.stream().filter(point -> Math.abs(point.xMeters() - 60.0) <= 4.0)
                .mapToDouble(point -> Math.abs(point.yMeters() - curve(point.xMeters(), phase)))
                .max().orElse(Double.NaN);
    }

    private static boolean hasExactEndpoints(List<MetricPoint> points) {
        return points.size() >= 2 && SOURCE.get(0).equals(points.get(0))
                && SOURCE.get(1).equals(points.get(points.size() - 1));
    }

    private static boolean isUsable(TraceHypothesisSet.Status status) {
        return status == TraceHypothesisSet.Status.COMPLETE || status == TraceHypothesisSet.Status.AMBIGUOUS;
    }

    private static double scalar(double x, double y, double phase) {
        return 0.02 + 0.08 * gaussian(y, curve(x, phase), 0.9);
    }

    private static double curve(double x, double phase) {
        double angle = Math.PI * (x - 10.0) / 100.0;
        return 30.0 + 10.0 * StrictMath.sin(angle)
                + 3.0 * StrictMath.exp(-0.5 * StrictMath.pow((x - 60.0) / 7.0, 2.0))
                + phase * 1.8 * StrictMath.sin(angle);
    }

    private static double gaussian(double value, double center, double sigma) {
        double normalized = (value - center) / sigma;
        return StrictMath.exp(-0.5 * normalized * normalized);
    }

    private record Run(TraceHypothesisSet.Status status, String explanation, List<MetricPoint> points,
            long transitions, long transitionLimit, long coarsened) { }
}
