package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class V022ProbabilisticMemoryTest {
    private static final EvidenceModelParameters PARAMETERS =
            EvidenceModelParameters.withoutShapeTerms();

    @Test
    void ownerAwareSolvePreservesEveryExactOutputBelowTheCap() {
        List<InferenceProfile> profiles = profiles(5, 3);
        ProbabilisticInference inference = new ProbabilisticInference();
        ProbabilisticInferenceResult baseline = inference.solve(profiles, PARAMETERS,
                TraceBudgets.defaults());
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();

        ProbabilisticInferenceResult accounted = inference.solve(profiles, PARAMETERS,
                TraceBudgets.defaults(), null, CancellationProbe.NONE, owner);

        assertEquals(ProbabilisticInferenceFingerprint.capture(baseline),
                ProbabilisticInferenceFingerprint.capture(accounted));
        assertEquals(14_032L, owner.currentBytes());
        assertEquals(53_808L, owner.peakBytes());
        assertEquals(36L, accounted.evaluatedPairVisits());
        assertEquals(513L, accounted.evaluatedTransitions());
        assertTrue(owner.currentBytes() > 0L, "the returned result must remain owned");
        assertTrue(owner.peakBytes() >= owner.currentBytes());
        owner.close();
        assertEquals(0L, ledger.currentBytes());
    }

    @Test
    void forwardBackwardAllocationRefusalIsTypedAndHasNoPosterior() {
        ProbabilisticInferenceResult result = solveWithLimit(profiles(5, 3), 640);

        assertLimitedAt(result, "forward/backward");
        assertTrue(result.evaluatedPairVisits() > 0L);
    }

    @Test
    void kBestFrontierAllocationRefusalIsTypedAndKeepsActualWork() {
        // The concrete next allocation is a 136-byte frontier bucket at 2,304 live bytes.
        ProbabilisticInferenceResult result = solveWithLimit(profiles(5, 3), 2_400);

        assertLimitedAt(result, "k-best frontier");
        assertTrue(result.evaluatedPairVisits() > 0L);
        assertTrue(result.evaluatedTransitions() > 0L);
    }

    @Test
    void kBestAncestryAllocationRefusalIsTypedAndKeepsActualWork() {
        // The concrete next allocation is one 64-byte reference-counted PathRecord at 2,440 live.
        ProbabilisticInferenceResult result = solveWithLimit(profiles(5, 3), 2_496);

        assertLimitedAt(result, "k-best ancestry");
        assertTrue(result.evaluatedPairVisits() > 0L);
        assertTrue(result.evaluatedTransitions() > 0L);
    }

    @Test
    void materializationAllocationRefusalIsTypedAndKeepsActualWork() {
        ProbabilisticInferenceResult result = solveWithLimit(profiles(4, 2), 9_000);

        assertLimitedAt(result, "materialization");
        assertTrue(result.evaluatedPairVisits() > 0L);
        assertTrue(result.evaluatedTransitions() > 0L);
    }

    @Test
    void impossibleFailureDiagnosticCarriesMidInferenceWorkAfterCleanup() {
        List<InferenceProfile> profiles = profiles(1, 3);
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(160);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();

        var abort = assertThrows(
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceMemoryLimitException.class,
                () -> new ProbabilisticInference().solve(profiles, PARAMETERS,
                    TraceBudgets.defaults(), null, CancellationProbe.NONE, owner));

        assertEquals("forward/backward", abort.stage());
        assertEquals(3L, abort.pairVisits());
        assertEquals(0L, abort.transitions());
        assertEquals(0L, abort.currentBytes());
        assertEquals(128L, abort.peakBytes());
        assertEquals(160L, abort.limitBytes());
        assertEquals(0L, ledger.currentBytes());
        owner.close();

        AttemptMemoryLedger precharged = new AttemptMemoryLedger(1_000);
        AttemptMemoryLedger.Owner prechargedOwner = precharged.rootOwner();
        prechargedOwner.reserve(840).adopt(new Object());
        var nested = assertThrows(
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceMemoryLimitException.class,
                () -> new ProbabilisticInference().solve(profiles, PARAMETERS,
                    TraceBudgets.defaults(), null, CancellationProbe.NONE, prechargedOwner));
        assertEquals("forward/backward", nested.stage());
        assertEquals(3L, nested.pairVisits());
        assertEquals(840L, nested.currentBytes());
        assertEquals(968L, nested.peakBytes());
        assertEquals(840L, precharged.currentBytes());
        prechargedOwner.close();
        assertEquals(0L, precharged.currentBytes());
    }

    @Test
    void nativeInferenceFailureGraphHasAnExactTwoHundredByteBoundary() {
        List<InferenceProfile> profiles = profiles(1, 3);
        AttemptMemoryLedger refused = new AttemptMemoryLedger(199);
        AttemptMemoryLedger.Owner refusedOwner = refused.rootOwner();
        var abort = assertThrows(
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceMemoryLimitException.class,
                () -> new ProbabilisticInference().solve(profiles, PARAMETERS,
                    TraceBudgets.defaults(), null, CancellationProbe.NONE, refusedOwner));
        assertEquals("k-best ancestry", abort.stage());
        assertEquals(3L, abort.pairVisits());
        assertEquals(0L, abort.currentBytes());
        assertEquals(168L, abort.peakBytes());
        refusedOwner.close();

        AttemptMemoryLedger admitted = new AttemptMemoryLedger(200);
        AttemptMemoryLedger.Owner admittedOwner = admitted.rootOwner();
        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(profiles,
                PARAMETERS, TraceBudgets.defaults(), null, CancellationProbe.NONE, admittedOwner);
        assertLimitedAt(result, "k-best ancestry");
        assertEquals(200L, admitted.currentBytes());
        assertEquals(200L, admitted.peakBytes());
        admittedOwner.close();
        assertEquals(0L, admitted.currentBytes());
    }

    @Test
    void controlPlaneAbortRejectsImpossibleEvidenceAndNonLedgerCause() {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(1);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        AttemptMemoryLedger.ResourceLimitException refusal = assertThrows(
                AttemptMemoryLedger.ResourceLimitException.class, () -> owner.reserve(2));
        assertThrows(IllegalArgumentException.class,
                () -> new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceMemoryLimitException(
                    "test", 0, 0, 0, 2, 1, refusal));
        assertThrows(IllegalArgumentException.class,
                () -> new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceMemoryLimitException(
                    "test", 0, 0, 0, 0, 1, new IllegalStateException("not a ledger refusal")));
        owner.close();
    }

    @Test
    void survivingParentsStayChargedWhileRejectedAncestryIsReleased() {
        AttemptMemoryLedger ancestryLedger = new AttemptMemoryLedger(1_024);
        AttemptMemoryLedger.Owner ancestryRoot = ancestryLedger.rootOwner();
        ProbabilisticInference.PathArena arena = new ProbabilisticInference.PathArena(
                ancestryRoot.child("adversarial-ancestry"));
        long arenaBytes = ancestryLedger.currentBytes();
        ProbabilisticInference.PathRecord survivingParent = arena.start(0, 0.0, 0.0);
        ProbabilisticInference.PathRecord evictedParent = arena.start(1, 1.0, 0.0);
        ProbabilisticInference.PathRecord survivingChild = arena.extend(
                survivingParent, 0, 0.0, 0.0);
        long pathBytes = AttemptMemoryLedger.objectBytes(48);
        assertEquals(arenaBytes + 3L * pathBytes, ancestryLedger.currentBytes());

        arena.releaseFrontier(List.of(survivingParent, evictedParent));

        assertEquals(arenaBytes + 2L * pathBytes, ancestryLedger.currentBytes(),
                "the surviving descendant keeps its parent while the unrelated parent releases");
        assertEquals(arenaBytes + 3L * pathBytes, ancestryLedger.peakBytes());
        arena.close();
        assertEquals(0L, ancestryLedger.currentBytes());
        ancestryRoot.close();

        assertEquals(128L, chainReleaseOperations(128));
        assertEquals(256L, chainReleaseOperations(256),
                "doubling a one-state route doubles ancestry-release work");

        AttemptMemoryLedger resizeLedger = AttemptMemoryLedger.production();
        AttemptMemoryLedger.Owner resizeRoot = resizeLedger.rootOwner();
        ProbabilisticInference.PathArena resizeArena = new ProbabilisticInference.PathArena(
                resizeRoot.child("identity-table-resize"));
        for (int index = 0; index < 20; index++) resizeArena.start(index, index, 0.0);
        long beforeResize = resizeLedger.currentBytes();
        resizeArena.start(20, 20.0, 0.0);
        long grownTable = AttemptMemoryLedger.referenceArrayBytes(128);
        assertEquals(beforeResize + pathBytes + grownTable
                - AttemptMemoryLedger.referenceArrayBytes(64), resizeLedger.currentBytes());
        assertEquals(beforeResize + pathBytes + grownTable, resizeLedger.peakBytes(),
                "the old 64-reference table coexists with its replacement at entry 21");
        resizeArena.close();
        resizeRoot.close();
        assertEquals(0L, resizeLedger.currentBytes());

        List<InferenceProfile> singleRoute = profiles(30, 1);
        AttemptMemoryLedger retainedLedger = new AttemptMemoryLedger(32_768);
        AttemptMemoryLedger.Owner retainedOwner = retainedLedger.rootOwner();

        ProbabilisticInferenceResult retained = new ProbabilisticInference().solve(singleRoute,
                PARAMETERS, TraceBudgets.defaults(), null, CancellationProbe.NONE, retainedOwner);

        assertEquals(ProbabilisticInferenceResult.Status.COMPLETE, retained.status());
        assertTrue(retainedLedger.peakBytes() >= 30L * AttemptMemoryLedger.objectBytes(40),
                "every parent reachable from the terminal path must remain charged");
        retainedOwner.close();

        AttemptMemoryLedger evictedLedger = new AttemptMemoryLedger(96_000);
        AttemptMemoryLedger.Owner evictedOwner = evictedLedger.rootOwner();
        ProbabilisticInferenceResult evicted = new ProbabilisticInference().solve(
                profiles(18, 4), PARAMETERS,
                new TraceBudgets(96, 8_000_000, 128_000_000, 4, 4), null,
                CancellationProbe.NONE, evictedOwner);

        assertTrue(evicted.posteriorUsable(), evicted.explanation());
        assertTrue(evictedLedger.peakBytes() < 96_000L,
                "rejected and unreachable ancestry must not accumulate forever");
        evictedOwner.close();
        assertEquals(0L, evictedLedger.currentBytes());
    }

    private static long chainReleaseOperations(int length) {
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        AttemptMemoryLedger.Owner root = ledger.rootOwner();
        ProbabilisticInference.PathArena arena = new ProbabilisticInference.PathArena(
                root.child("chain-" + length));
        ProbabilisticInference.PathRecord current = arena.start(0, 0.0, 0.0);
        for (int index = 1; index < length; index++) {
            ProbabilisticInference.PathRecord next = arena.extend(current, 0, 0.0, 0.0);
            arena.releaseFrontier(List.of(current));
            current = next;
        }
        arena.releaseFrontier(List.of(current));
        long operations = arena.releasedRecordCount();
        arena.close();
        root.close();
        assertEquals(0L, ledger.currentBytes());
        return operations;
    }

    @Test
    void alternativeSelectionChargesMutableAndImmutableListsWhileTheyCoexist() {
        List<InferenceProfile> profiles = profiles(2, 2);
        List<ProbabilisticPath> raw = List.of(
                path(new int[] {0, 0}, "branch-0", 0.0),
                path(new int[] {1, 1}, "branch-1", 1.0));
        PathAlternativeSelector selector = new PathAlternativeSelector();
        List<ProbabilisticPath> baseline = selector.select(raw, profiles, 1.0, 2);
        long mutableBytes = ProbabilisticInference.listBytes(2);
        long resultBytes = ProbabilisticInference.listBytes(baseline.size());
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(mutableBytes + resultBytes);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();

        List<ProbabilisticPath> accounted = selector.select(raw, profiles, 1.0, 2, owner);

        assertEquals(baseline, accounted);
        assertEquals(resultBytes, ledger.currentBytes());
        assertEquals(mutableBytes + resultBytes, ledger.peakBytes());
        owner.close();
        assertEquals(0L, ledger.currentBytes());

        AttemptMemoryLedger refusedLedger = new AttemptMemoryLedger(
                mutableBytes + resultBytes - 1L);
        AttemptMemoryLedger.Owner refusedOwner = refusedLedger.rootOwner();
        ProbabilisticInference.MemoryLimit refusal = assertThrows(
                ProbabilisticInference.MemoryLimit.class,
                () -> selector.select(raw, profiles, 1.0, 2, refusedOwner));
        assertTrue(refusal.getMessage().contains("alternative selection"));
        assertEquals(0L, refusedLedger.currentBytes(),
                "the mutable selection must release when the immutable copy is refused");
        refusedOwner.close();
    }

    private static ProbabilisticInferenceResult solveWithLimit(List<InferenceProfile> profiles,
            long limitBytes) {
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(limitBytes);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(profiles,
                PARAMETERS, TraceBudgets.defaults(), null, CancellationProbe.NONE, owner);
        assertTrue(owner.peakBytes() <= limitBytes);
        assertTrue(owner.currentBytes() > 0L, "typed failure diagnostics remain owned");
        owner.close();
        assertEquals(0L, ledger.currentBytes());
        return result;
    }

    private static void assertLimitedAt(ProbabilisticInferenceResult result, String stage) {
        assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, result.status());
        assertFalse(result.posteriorUsable());
        assertTrue(result.mapPath().isEmpty());
        assertTrue(result.rawPaths().isEmpty());
        assertTrue(result.distinctPaths().isEmpty());
        assertTrue(result.completion().isEmpty());
        assertTrue(result.explanation().contains(stage), result.explanation());
    }

    private static List<InferenceProfile> profiles(int profileCount, int stateCount) {
        List<InferenceProfile> result = new ArrayList<>(profileCount);
        double[] offsets = new double[stateCount];
        double[] widths = new double[stateCount];
        double[] unary = new double[stateCount];
        String[] branches = new String[stateCount];
        for (int state = 0; state < stateCount; state++) {
            offsets[state] = state - 0.5 * (stateCount - 1);
            widths[state] = 1.0;
            unary[state] = 0.07 * state;
            branches[state] = "branch-" + state;
        }
        for (int profile = 0; profile < profileCount; profile++) {
            result.add(V022ProbabilisticInferenceTest.profile(profile, offsets, widths,
                    unary, branches));
        }
        return List.copyOf(result);
    }

    private static ProbabilisticPath path(int[] states, String branch, double energy) {
        List<MetricPoint> points = new ArrayList<>(states.length);
        for (int index = 0; index < states.length; index++) {
            points.add(new MetricPoint(index, states[index]));
        }
        return new ProbabilisticPath(states, points, branch, energy, 0.0, 0.5);
    }
}
