package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Exhaustive independent image/branch oracle over all paths in a real three-row source. */
class MeasuredAffinityExhaustiveOracleTest {
    private static final String FIELD = "affinity-contract-probe";
    private record Path(List<Integer> ids, List<Double> offsets, List<Integer> rows,
        double q, double c, double t, int consumed) {
        double total() { return q + c + t; }
    }

    @Test void exactGraphRemovalCostsAndNativeTiesMatchEveryTinyPath() {
        for (double weak : new double[] {.6, .9}) {
            var base = ExperimentalOrdinaryAffinityTest.scene(1, 1, 0, weak, 8, false, false);
            var locations = new ArrayList<DetachedProfileSamplingLocation>();
            for (int i = 0; i < 3; i++) {
                var source = base.input().profileLocations().get(i + 4);
                locations.add(new DetachedProfileSamplingLocation(source.geographicPoint(), source.metricPoint(),
                    source.rasterPoint(), source.coordinateFrame(), source.rasterTransform(), i * 8));
            }
            var request = new CorridorTraceInput(locations, 1);
            var ledger = AttemptMemoryLedger.production();
            var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
            try (var root = ledger.rootOwner();
                 var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(base.evidence(), FIELD,
                     request, new ProfileChainage(List.of(0.0, 8.0, 16.0), 16), 25, budget, root)) {
                var retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(base.evidence(), FIELD,
                    request.profileLocations(), 25, 1, 1, budget, permit);
                var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained, List.of(0, 1, 2), 1, List.of());
                try (var input = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained, binding)) {
                    var domain = new LegacyEmissionDomain(binding, retained.profiles().levelZeroProfiles(),
                        List.of(-4.0, 4.0), budget, root, input.experimentalMeasuredModes());
                    var classifier = new OrdinaryAffinityClassifier(input, domain, root);
                    int excluded = 0;
                    for (int row = 0; row < 2; row++) for (var from : domain.observedAt(row))
                            for (var to : domain.observedAt(row + 1)) {
                        boolean sameImageStrand = Math.signum(from.offsetRasterPixels()) == Math.signum(to.offsetRasterPixels());
                        var receipt = classifier.classify(from, to, domain.metricPoint(from, input.context()),
                            domain.metricPoint(to, input.context()));
                        assertEquals(sameImageStrand, receipt.ordinaryEligible(),
                            "The actual hard-admission graph must exclude every cross-band edge, not debit its cost");
                        if (!receipt.ordinaryEligible()) excluded++;
                    }
                    assertEquals(4, excluded);
                    var paths = enumerate(domain, weak);
                    assertEquals(8, paths.size(), "Two complete strands, full/optional route, two original seeds");
                    Path expected = paths.stream().min(MeasuredAffinityExhaustiveOracleTest::compare).orElseThrow();
                    var anchors = new ArrayList<LegacyEmissionKernel.AnchorProposal>();
                    for (int row : new int[] {0, 2}) {
                        double chosenOffset = expected.offsets().get(0);
                        var chosenState = domain.observedAt(row).stream()
                            .filter(s -> s.offsetRasterPixels() == chosenOffset).findFirst().orElseThrow();
                        anchors.add(new LegacyEmissionKernel.AnchorProposal(row, chosenState.peakOwner()));
                    }
                    try (var result = new LegacyEmissionKernel().solveMeasuredAffinity(input,
                            retained.profiles().levelZeroProfiles(), List.of(-4.0, 4.0), anchors,
                            (profiles, offsets, strengths) -> offsets, CancellationProbe.NONE)) {
                        assertEquals(expected.ids(), result.inferenceVertices().stream().map(v -> v.nativeStateId().orElseThrow()).toList());
                        assertEquals(expected.offsets(), result.inferenceVertices().stream().map(v -> v.offsetRasterPixels().orElseThrow()).toList());
                        assertEquals(expected.rows(), result.inferenceVertices().stream()
                            .map(v -> ((EmissionMapping.ObservationVertex) v.owner()).observationIndex()).toList());
                        assertEquals(expected.q(), result.stitched().q(), 1e-12);
                        assertEquals(expected.c(), result.stitched().c(), 0);
                        assertEquals(expected.t(), result.stitched().t(), 0);
                        assertEquals(0, result.stitched().k(), 0);
                        assertEquals(expected.total(), result.stitched().total(), 1e-12);
                        assertTrue(result.affinity().orElseThrow().fullyCertified());
                        assertEquals(1, result.intervalStages().size());
                        assertEquals(1, result.intervalStageReferences().size());
                        assertEquals(0, expected.consumed(), "An exact tie keeps all original rows");
                    }
                    if (weak == .9) {
                        try (var flipped = new LegacyEmissionKernel().solveMeasuredAffinity(input,
                                retained.profiles().levelZeroProfiles(), List.of(-4.0, 4.0), List.of(),
                                (profiles, offsets, strengths) -> {
                                    var proposal = new ArrayList<>(offsets);
                                    proposal.set(1, -offsets.get(1));
                                    return proposal;
                                }, CancellationProbe.NONE)) {
                            assertEquals(flipped.inferenceVertices(), flipped.smoothedVertices(),
                                "A smoother cannot publish a newly moved opposite-strand point as UNKNOWN");
                            assertTrue(flipped.affinity().orElseThrow().fullyCertified());
                            var outcome = flipped.affinity().orElseThrow().smoothingOutcomes().get(0);
                            assertEquals(EmissionResult.SmoothingDisposition.RAW_RETAINED, outcome.disposition());
                            assertEquals(EmissionResult.SmoothingReason.FINAL_BRANCH_CONFLICT, outcome.reason());
                            var rejected = outcome.proposedEdgeEvidence().get(0);
                            assertEquals(OrdinaryAffinityClassifier.Status.INCOMPATIBLE, rejected.status());
                            assertNotEquals(rejected.fromChain(), rejected.toChain());
                            assertEquals(OrdinaryAffinityClassifier.PointOwnerKind.MEASURED_FINAL_POINT,
                                rejected.toOwnership().orElseThrow().kind());
                        }
                        try (var unsupported = new LegacyEmissionKernel().solveMeasuredAffinity(input,
                                retained.profiles().levelZeroProfiles(), List.of(-4.0, 4.0), List.of(),
                                (profiles, offsets, strengths) -> {
                                    var proposal = new ArrayList<>(offsets); proposal.set(1, 0.0); return proposal;
                                }, CancellationProbe.NONE)) {
                            assertEquals(unsupported.inferenceVertices(), unsupported.smoothedVertices());
                            assertEquals(EmissionResult.SmoothingReason.FINAL_EVIDENCE_UNAVAILABLE,
                                unsupported.affinity().orElseThrow().smoothingOutcomes().get(0).reason());
                        }
                        try (var supported = new LegacyEmissionKernel().solveMeasuredAffinity(input,
                                retained.profiles().levelZeroProfiles(), List.of(-4.0, 4.0), List.of(),
                                (profiles, offsets, strengths) -> {
                                    var proposal = new ArrayList<>(offsets); proposal.set(1, offsets.get(1) + .1); return proposal;
                                }, CancellationProbe.NONE)) {
                            assertNotEquals(supported.inferenceVertices(), supported.smoothedVertices());
                            assertTrue(supported.affinity().orElseThrow().fullyCertified());
                            assertEquals(EmissionResult.SmoothingDisposition.ACCEPTED,
                                supported.affinity().orElseThrow().smoothingOutcomes().get(0).disposition());
                            var fresh = supported.affinity().orElseThrow().finalGeometry().get(0).receipt().orElseThrow()
                                .toOwnership().orElseThrow();
                            assertEquals(supported.smoothedVertices().get(1).position(), fresh.position());
                            assertEquals(OrdinaryAffinityClassifier.PointOwnerKind.MEASURED_FINAL_POINT, fresh.kind());
                            assertTrue(fresh.pointScalar().orElseThrow().mean().orElseThrow() < fresh.sourceOwner().originPeak().value());
                        }
                        var normal = input.profiles().levelZeroProfiles().get(0).normalScreen();
                        double originalNormal = normal.x;
                        try {
                            assertThrows(IllegalStateException.class, () -> new LegacyEmissionKernel().solveMeasuredAffinity(input,
                                retained.profiles().levelZeroProfiles(), List.of(-4.0, 4.0), List.of(),
                                (profiles, offsets, strengths) -> { normal.x += .1; return offsets; }, CancellationProbe.NONE));
                        } finally { normal.x = originalNormal; }
                    }
                }
            }
            assertEquals(0, ledger.currentBytes());
        }
    }

    private static List<Path> enumerate(LegacyEmissionDomain domain, double weak) {
        var paths = new ArrayList<Path>();
        for (double seed : new double[] {-4, 4}) {
            for (int mask = 0; mask < 8; mask++) {
                int a = mask & 1, b = (mask >>> 1) & 1, c = (mask >>> 2) & 1;
                if (a == b && b == c) paths.add(path(domain, List.of(0, 1, 2), a, seed, weak));
            }
            for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++)
                if (a == b) paths.add(path(domain, List.of(0, 2), a, seed, weak));
        }
        return paths;
    }

    private static Path path(LegacyEmissionDomain domain, List<Integer> rows, int band, double seed, double weak) {
        var ids = new ArrayList<Integer>(); var offsets = new ArrayList<Double>();
        double offset = band == 0 ? -4 : 4;
        for (int row : rows) {
            var state = domain.observedAt(row).stream().filter(s -> s.offsetRasterPixels() == offset).findFirst().orElseThrow();
            ids.add(state.nativeStateId()); offsets.add(offset);
        }
        double q = 0;
        for (int i = 1; i < rows.size(); i++) {
            double start = 42 + rows.get(i - 1) * 8.0, end = 42 + rows.get(i) * 8.0;
            double integral = 0;
            for (int x = (int) start; x < end; x++) integral += .5 * (pixel(x, band, weak) + pixel(x + 1, band, weak));
            double mean = integral / (end - start);
            q += (end - start) * (1 - mean);
        }
        double departure = Math.abs(offset - seed);
        // Initial departure and the first return to zero slope both retain their original terms.
        return new Path(ids, offsets, rows, q, .16 * departure, .24 * departure, 3 - rows.size());
    }

    private static double pixel(int x, int band, double weak) {
        double intensity = band == 0 ? x < 50 ? .9 : weak : x < 50 ? weak : .9;
        return (float) (Math.rint(intensity * 255) / 255);
    }
    private static int compare(Path a, Path b) {
        int cost = Double.compare(a.total(), b.total()); if (cost != 0) return cost;
        int consumed = Integer.compare(a.consumed(), b.consumed()); if (consumed != 0) return consumed;
        for (int i = 0; i < Math.min(a.ids().size(), b.ids().size()); i++) {
            int row = Integer.compare(a.rows().get(i), b.rows().get(i)); if (row != 0) return row;
            int id = Integer.compare(a.ids().get(i), b.ids().get(i)); if (id != 0) return id;
        }
        return Integer.compare(a.ids().size(), b.ids().size());
    }
}
