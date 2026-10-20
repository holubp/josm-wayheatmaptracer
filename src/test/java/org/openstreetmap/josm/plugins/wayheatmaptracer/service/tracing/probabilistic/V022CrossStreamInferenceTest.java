package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceMemoryLimitException;

/** Actual tiny solver API/ownership tests: four two-state rows, at most 16 routes. */
class V022CrossStreamInferenceTest {
    private static final EvidenceModelParameters PARAMETERS = new EvidenceModelParameters(
            "cross-stream-api-only", 1, 1, 1, 0, 0, 1, 1, 1e-9);
    private static final ProbabilisticExecutionPolicy.Mode V3 = ProbabilisticExecutionPolicy.Mode.EXACT_CROSS_STREAM_RAW1_V3;

    @Test
    void oldOverloadsRejectV3BeforeAnyAcquisition() {
        var solver = new ProbabilisticInference();
        var ledger = new AttemptMemoryLedger(100);
        int[] profileReads = {0}, polls = {0};
        List<InferenceProfile> trap = new AbstractList<>() {
            @Override public InferenceProfile get(int index) { profileReads[0]++; throw new AssertionError("profile read"); }
            @Override public int size() { profileReads[0]++; throw new AssertionError("profile size"); }
        };
        CancellationProbe probe = () -> { polls[0]++; return false; };
        try (var owner = ledger.rootOwner()) {
            assertThrows(IllegalArgumentException.class, () -> solver.solve(trap, PARAMETERS, budgets(1), null, probe, owner, V3));
            assertThrows(IllegalArgumentException.class, () -> solver.solve(trap, PARAMETERS, budgets(1), null, probe, owner, V3,
                    (stage, left, right) -> { throw new AssertionError("potential invoked"); }));
            assertEquals(0, polls[0]);
            assertEquals(0, profileReads[0]);
            assertEquals(0, owner.peakBytes());
        }
    }

    @Test
    void newEntryReservesSnapshotAndWrapperBeforeSolver() {
        var ledger = new AttemptMemoryLedger(430);
        int[] reads = {0};
        List<InferenceProfile> trap = new AbstractList<>() {
            @Override public InferenceProfile get(int index) { reads[0]++; throw new AssertionError("profile read before funding"); }
            @Override public int size() { reads[0]++; throw new AssertionError("profile size before funding"); }
        };
        try (var owner = ledger.rootOwner(); var caller = owner.reserve(7)) {
            var refusal = assertThrows(TraceMemoryLimitException.class, () -> new ProbabilisticInference().solveCrossStreamRaw1(
                    trap, PARAMETERS, budgets(1), null, CancellationProbe.NONE, owner, null));
            assertEquals(0, reads[0]);
            assertEquals(0, refusal.pairVisits());
            assertEquals(0, refusal.transitions());
            assertEquals(7, owner.currentBytes(), "caller reservation survives whole new-attempt rollback");
            assertTrue(refusal.peakBytes() <= 430);
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void newEntryOwnsSuccessfulSidecarsWithoutChangingExactOutputs() {
        var solver = new ProbabilisticInference();
        var profiles = profiles();
        var oldLedger = new AttemptMemoryLedger(1_000_000);
        ProbabilisticInferenceResult old;
        long retained, peak;
        try (var owner = oldLedger.rootOwner()) {
            old = solver.solve(profiles, PARAMETERS, budgets(1), null, CancellationProbe.NONE, owner,
                    ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            retained = owner.currentBytes(); peak = owner.peakBytes();
        }
        var ledger = new AttemptMemoryLedger(1_000_000);
        try (var owner = ledger.rootOwner()) {
            var actual = solver.solveCrossStreamRaw1(profiles, PARAMETERS, budgets(1), null, CancellationProbe.NONE, owner, null);
            assertEquivalent(old, actual.result());
            assertEquals(ProbabilisticExecutionPolicy.CROSS_STREAM_VERSION,
                    actual.result().workAccounting().orElseThrow().executionPolicy());
            assertEquals(8, actual.boundWork().boundUnits(), "Task2 initializes accounting but does not prune");
            assertEquals(0, actual.boundWork().boundQueries());
            assertEquals(retained + 208, owner.currentBytes());
            assertEquals(peak + 424, owner.peakBytes());
            assertThrows(IllegalArgumentException.class, () -> new CrossStreamInferenceResult(actual.result(), null));
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void newEntryReportsActualUnprunedKBestCalculations() {
        var ledger = new AttemptMemoryLedger(1_000_000);
        try (var owner = ledger.rootOwner()) {
            var value = new ProbabilisticInference().solveCrossStreamRaw1(profiles(), PARAMETERS, budgets(1), null,
                    CancellationProbe.NONE, owner, null);
            // Two extension stages * four target pairs * two before-states =16.
            assertEquals(16, value.boundWork().firstHeadingEvaluations());
            assertEquals(8, value.boundWork().secondHeadingEvaluations());
            assertEquals(16, value.boundWork().wrapEvaluations());
            assertEquals(16, value.boundWork().huberEvaluations());
            assertEquals(16, value.boundWork().tripleEvaluations());
            assertEquals(0, value.boundWork().skippedStreams(), "Task2 has no pruning");
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void ineligibleRawCapsRetainV2AndZeroBoundWork() {
        for (int cap : new int[] {2, 32}) {
            var ledger = new AttemptMemoryLedger(1_000_000);
            try (var owner = ledger.rootOwner()) {
                var result = new ProbabilisticInference().solveCrossStreamRaw1(profiles(), PARAMETERS, budgets(cap), null,
                        CancellationProbe.NONE, owner, null);
                assertEquals(ProbabilisticExecutionPolicy.CURRENT_VERSION, result.result().workAccounting().orElseThrow().executionPolicy());
                assertEquals(0, result.boundWork().boundUnits());
                assertEquals(0, result.boundWork().boundQueries());
                assertEquals(Math.min(cap, 16), result.result().rawPaths().size());
            }
            assertEquals(0, ledger.currentBytes());
        }
    }

    @Test
    void typedFailureAndControlPlaneAbortCloseAllNewOwners() {
        var ledger = new AttemptMemoryLedger(1000);
        try (var owner = ledger.rootOwner()) {
            var value = new ProbabilisticInference().solveCrossStreamRaw1(profiles(), PARAMETERS, budgets(1), null,
                    CancellationProbe.NONE, owner, null);
            assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, value.result().status());
            assertTrue(value.result().rawPaths().isEmpty());
            assertEquals(ProbabilisticExecutionPolicy.CROSS_STREAM_VERSION, value.result().workAccounting().orElseThrow().executionPolicy());
            assertEquals(496, owner.currentBytes(), "288 original failure +208 immutable opt-in publication");
        }
        assertEquals(0, ledger.currentBytes());
        var small = new AttemptMemoryLedger(624);
        try (var owner = small.rootOwner()) {
            var abort = assertThrows(TraceMemoryLimitException.class, () -> new ProbabilisticInference().solveCrossStreamRaw1(
                    profiles(), PARAMETERS, budgets(1), null, CancellationProbe.NONE, owner, null));
            assertEquals(624, abort.limitBytes());
            assertEquals(0, owner.currentBytes(), "actual outer cleanup is distinct from captured mid-abort usage");
        }
    }

    @Test
    void capabilityResourceRefusalIsNotRelabelledAsMetadataOrZeroWork() {
        var ledger = new AttemptMemoryLedger(1_000_000);
        AttemptMemoryLedger.ResourceLimitException[] original = {null};
        try (var owner = ledger.rootOwner(); var caller = owner.reserve(7)) {
            var refused = assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                    () -> new ProbabilisticInference().solveCrossStreamRaw1(profiles(), PARAMETERS, budgets(1), null,
                            CancellationProbe.NONE, owner, (stage, left, right) -> {
                                try (var impossible = owner.reserve(2_000_000)) {
                                    throw new AssertionError("comparison memory guard was bypassed");
                                } catch (AttemptMemoryLedger.ResourceLimitException failure) {
                                    original[0] = failure;
                                    throw failure;
                                }
                            }));
            assertSame(original[0], refused, "preserve the factual capability exception, not a made-up metadata abort");
            assertTrue(refused.currentBytes() > 7, "captured usage is distinct from post-cleanup caller usage");
            assertEquals(7, owner.currentBytes());
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void cancellationAtEntryAndFinalPublicationNeverReturnsAWrapper() {
        var profiles = profiles();
        int[] originalPolls = {0};
        var baseline = new AttemptMemoryLedger(1_000_000);
        try (var owner = baseline.rootOwner()) {
            new ProbabilisticInference().solve(profiles, PARAMETERS, budgets(1), null,
                    () -> { originalPolls[0]++; return false; }, owner, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
        }
        for (int cancelAt : new int[] {1, originalPolls[0] + 2}) {
            int[] polls = {0};
            var ledger = new AttemptMemoryLedger(1_000_000);
            try (var owner = ledger.rootOwner(); var caller = owner.reserve(7)) {
                assertThrows(CancellationException.class, () -> new ProbabilisticInference().solveCrossStreamRaw1(
                        profiles, PARAMETERS, budgets(1), null, () -> ++polls[0] == cancelAt, owner, null));
                assertEquals(cancelAt, polls[0]);
                assertEquals(7, owner.currentBytes());
            }
            assertEquals(0, ledger.currentBytes());
        }
    }

    private static List<InferenceProfile> profiles() {
        var result = new ArrayList<InferenceProfile>();
        for (int i = 0; i < 4; i++) result.add(V022ProbabilisticInferenceTest.profile(i,
                new double[] {-0.5, 0.5}, new double[] {1, 1}, new double[] {0, 0.07}, new String[] {"one", "two"}));
        return List.copyOf(result);
    }

    private static TraceBudgets budgets(int cap) { return new TraceBudgets(96, 8_000_000, 128_000_000, cap, Math.min(cap, 8)); }

    private static void assertEquivalent(ProbabilisticInferenceResult expected, ProbabilisticInferenceResult actual) {
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.completion(), actual.completion());
        assertEquals(expected.alternativeSearchTruncated(), actual.alternativeSearchTruncated());
        assertEquals(expected.evaluatedPairVisits(), actual.evaluatedPairVisits());
        assertEquals(expected.evaluatedTransitions(), actual.evaluatedTransitions());
        assertEquals(Double.doubleToRawLongBits(expected.logPartition()), Double.doubleToRawLongBits(actual.logPartition()));
        assertEquals(expected.rawPaths(), actual.rawPaths());
        assertEquals(expected.distinctPaths(), actual.distinctPaths());
        assertEquals(expected.credibleSets(), actual.credibleSets());
        var first = expected.positionMarginals(); var second = actual.positionMarginals();
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).length, second.get(i).length);
            for (int j = 0; j < first.get(i).length; j++) assertEquals(Double.doubleToRawLongBits(first.get(i)[j]),
                    Double.doubleToRawLongBits(second.get(i)[j]));
        }
    }
}
