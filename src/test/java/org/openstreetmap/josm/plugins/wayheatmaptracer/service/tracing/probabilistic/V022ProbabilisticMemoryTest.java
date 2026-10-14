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
    void directAncestryLeaseFitsExactTwoRecordCapWithoutAnIdentityTable() {
        // Arena: two references, two longs and closed flag (40-byte payload).
        // Record: historical48-byte payload plus nonreferencing arena id and lease reference.
        long arenaBytes = AttemptMemoryLedger.objectBytes(40);
        long recordBytes = AttemptMemoryLedger.objectBytes(64);
        long limit = arenaBytes + 2 * recordBytes;
        var ledger = new AttemptMemoryLedger(limit);
        var root = ledger.rootOwner();
        var arena = new ProbabilisticInference.PathArena(root.child("direct-ancestry"));
        assertEquals(arenaBytes, ledger.currentBytes(), "No eliminated identity table may remain charged");
        var parent = arena.start(1, -0.0, 0.25);
        var child = arena.extend(parent, 2, 0.5, 0.125);
        assertEquals(limit, ledger.currentBytes());
        var refusal = assertThrows(RuntimeException.class, () -> arena.extend(parent, 3, 1.0, 0.0));
        assertTrue(refusal.getCause() instanceof AttemptMemoryLedger.ResourceLimitException);
        assertEquals(limit, ledger.currentBytes(), "Refused creation leaves no reservation or extra descendant");
        assertEquals(limit, ledger.peakBytes());
        assertEquals(0.5, child.energy()); assertEquals(0.375, child.logMeasure());
        assertTrue(java.util.Arrays.equals(new int[] {1, 2}, child.states()));
        arena.release(parent);
        assertEquals(limit, ledger.currentBytes(), "Child keeps its unowned parent alive");
        arena.release(child);
        assertEquals(2, arena.releasedRecordCount());
        assertEquals(arenaBytes, ledger.currentBytes());
        assertThrows(IllegalStateException.class, () -> arena.release(child));
        arena.close(); arena.close(); root.close();
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void foreignArenaCannotConsumeFrontierOrReleaseAnotherArenasLease() {
        for (boolean sharedOwner : List.of(false, true)) {
            var ledger = new AttemptMemoryLedger(4096);
            var root = ledger.rootOwner(); var firstOwner = root.child("first");
            var first = new ProbabilisticInference.PathArena(firstOwner);
            var second = new ProbabilisticInference.PathArena(sharedOwner ? firstOwner : root.child("second"));
            var record = first.start(1, 0.0, 0.0); long retained = ledger.currentBytes();
            assertThrows(IllegalArgumentException.class, () -> second.release(record),
                    "Ownership is arena identity even when an owner is shared");
            assertThrows(IllegalArgumentException.class, () -> second.extend(record, 2, 0.0, 0.0));
            assertThrows(IllegalArgumentException.class, () -> second.extendRounded(record, 2, 0.0, 0.0));
            assertEquals(retained, ledger.currentBytes());
            assertEquals(0, second.releasedRecordCount());
            first.release(record); assertEquals(1, first.releasedRecordCount());
            first.close(); second.close(); root.close(); assertEquals(0, ledger.currentBytes());
        }
    }

    @Test
    void closedArenaKeepsEmptyIndexReleaseSemanticsWithoutTouchingDeadLeases() {
        var ledger = new AttemptMemoryLedger(4096); var root = ledger.rootOwner();
        var arena = new ProbabilisticInference.PathArena(root.child("closed-ancestry"));
        var parent = arena.start(1, 0.0, 0.0); var child = arena.extend(parent, 2, 0.0, 0.0);
        arena.release(parent); assertEquals(0, arena.releasedRecordCount());
        arena.close(); assertEquals(0, ledger.currentBytes());
        arena.release(child);
        assertEquals(0, arena.releasedRecordCount(), "Closed lifetime does not close leases or cascade again");
        assertTrue(java.util.Arrays.equals(new int[] {1, 2}, child.states()));
        assertThrows(IllegalStateException.class, () -> arena.release(child));
        arena.close(); root.close(); assertEquals(0, ledger.currentBytes());
    }

    @Test
    void coownedRecordRemainsChargedAfterArenaCloseAndPostcloseRelease() {
        var ledger = new AttemptMemoryLedger(4096); var root = ledger.rootOwner();
        var arena = new ProbabilisticInference.PathArena(root.child("ancestry"));
        var coowner = root.child("retained-record");
        var record = arena.start(1, 0.0, 0.0); coowner.retain(record);
        arena.close();
        assertEquals(AttemptMemoryLedger.objectBytes(64), ledger.currentBytes(),
                "Only the actual coowned record remains; no arena/counter backreference");
        arena.release(record); assertEquals(0, arena.releasedRecordCount());
        assertEquals(AttemptMemoryLedger.objectBytes(64), ledger.currentBytes());
        coowner.close(); root.close(); assertEquals(0, ledger.currentBytes());
    }

    @Test
    void childOverflowRefusesBeforeAdoptingAnyNewRecordLease() throws Exception {
        var ledger = new AttemptMemoryLedger(4096); var root = ledger.rootOwner();
        var arena = new ProbabilisticInference.PathArena(root.child("overflow"));
        var record = arena.start(1, 0.0, 0.0); long before = ledger.currentBytes();
        var children = ProbabilisticInference.PathRecord.class.getDeclaredField("children");
        children.setAccessible(true); children.setInt(record, Integer.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> arena.extend(record, 2, 0.0, 0.0));
        assertEquals(before, ledger.currentBytes(), "Overflow occurs before any record allocation/adoption");
        assertEquals(before, ledger.peakBytes());
        children.setInt(record, 0); arena.release(record); arena.close(); root.close();
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void externallyClosedOwnerDoesNotSilentlyTreatInactiveLeaseAsLive() {
        var ledger = new AttemptMemoryLedger(4096); var root = ledger.rootOwner(); var owner = root.child("external-close");
        var arena = new ProbabilisticInference.PathArena(owner); var record = arena.start(1, 0.0, 0.0);
        owner.close(); assertEquals(0, ledger.currentBytes());
        assertThrows(IllegalStateException.class, () -> arena.release(record));
        assertEquals(0, arena.releasedRecordCount());
        arena.close(); root.close(); assertEquals(0, ledger.currentBytes());
    }

    @Test
    void releasedParentCannotBeExtendedButRetainedAncestryCan() {
        var ledger = new AttemptMemoryLedger(4096); var root = ledger.rootOwner();
        var arena = new ProbabilisticInference.PathArena(root.child("live-parent"));
        try {
            var dead = arena.start(0, 0.0, 0.0); arena.release(dead); long before = ledger.currentBytes();
            assertThrows(IllegalStateException.class, () -> arena.extend(dead, 2, 0.0, 0.0));
            assertThrows(IllegalStateException.class, () -> arena.extendRounded(dead, 2, 0.0, 0.0));
            assertEquals(before, ledger.currentBytes(), "Dead parent is rejected before reservation/adoption");
            var parent = arena.start(1, 0.0, 0.0); var child = arena.extend(parent, 2, 0.0, 0.0);
            arena.release(parent); // No frontier reference, but one live descendant still owns it.
            var sibling = arena.extendRounded(parent, 3, 1.0, 0.125);
            assertTrue(java.util.Arrays.equals(new int[] {1, 3}, sibling.states()));
            arena.release(child); arena.release(sibling);
            assertEquals(4, arena.releasedRecordCount());
            assertEquals(before, ledger.currentBytes());
        } finally {
            arena.close(); root.close();
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void ownerAwareSolvePreservesEveryExactOutputBelowTheCap() {
        List<InferenceProfile> profiles = profiles(5, 3);
        ProbabilisticInference inference = new ProbabilisticInference();
        ProbabilisticInferenceResult baseline = inference.solve(profiles, PARAMETERS,
                TraceBudgets.defaults());
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();

        org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.begin();
        ProbabilisticInferenceResult accounted;
        try {
            accounted = inference.solve(profiles, PARAMETERS,
                    TraceBudgets.defaults(), null, CancellationProbe.NONE, owner);
            var counts = org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.snapshot();
            assertEquals(2 * 9 + 27 + 81 + 243, counts.get("inference.ancestryRecordsAllocated").intValue());
            assertEquals(27 + 81 + 243, counts.get("inference.extensionDescriptors").intValue());
        } finally {
            org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.end();
        }

        assertEquals(ProbabilisticInferenceFingerprint.capture(baseline),
                ProbabilisticInferenceFingerprint.capture(accounted));
        // Record + Optional: 80 bytes; the result's new field adds eight bytes.
        assertEquals(14_032L + 88L, owner.currentBytes());
        long descriptorScratch = AttemptMemoryLedger.arrayBytes(3L * 32L, Double.BYTES)
                + AttemptMemoryLedger.arrayBytes(3L * 32L, Integer.BYTES)
                + 3L * AttemptMemoryLedger.arrayBytes(3L, Integer.BYTES);
        // All243 ternary paths survive per-pairK32. Each of nine initial pairs
        // has its own first-node prefix:9+9+27+81+243 live ancestry records.
        long ancestry = (2 * 9L + 27 + 81 + 243) * AttemptMemoryLedger.objectBytes(64);
        long arena = AttemptMemoryLedger.objectBytes(40);
        long accounting = AttemptMemoryLedger.objectBytes(80);
        long memoObjects = 2 * AttemptMemoryLedger.objectBytes(48);
        long matrix = AttemptMemoryLedger.referenceArrayBytes(3)
                + 3 * AttemptMemoryLedger.arrayBytes(3, Double.BYTES);
        long list4 = AttemptMemoryLedger.objectBytes(16) + AttemptMemoryLedger.referenceArrayBytes(4);
        long messages = 8 * matrix + 2 * list4;
        long countGrid = AttemptMemoryLedger.referenceArrayBytes(3)
                + 3 * AttemptMemoryLedger.arrayBytes(3, Integer.BYTES);
        long list32 = AttemptMemoryLedger.objectBytes(16) + AttemptMemoryLedger.referenceArrayBytes(32);
        long frontiers = 2 * (matrix + countGrid + 9 * list32);
        long lexicalScratch = AttemptMemoryLedger.objectBytes(16) + AttemptMemoryLedger.referenceArrayBytes(243);
        assertTrue(lexicalScratch > descriptorScratch, "Descriptors close before larger lexical ordering scratch");
        assertEquals(ancestry + arena + accounting + memoObjects + messages + frontiers + lexicalScratch,
                owner.peakBytes(), "Exact lexical-order peak with both frontiers and the complete retained ancestry");
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
    void descriptorScratchRefusalIsTypedAndReleasesEveryOwner() {
        boolean foundScratchBoundary = false;
        for (long limit = 2_400L; limit <= 8_000L && !foundScratchBoundary; limit += 16L) {
            AttemptMemoryLedger ledger = new AttemptMemoryLedger(limit);
            AttemptMemoryLedger.Owner owner = ledger.rootOwner();
            ProbabilisticInferenceResult result = new ProbabilisticInference().solve(
                profiles(4, 3), PARAMETERS, TraceBudgets.defaults(), null,
                CancellationProbe.NONE, owner);
            if (result.explanation().contains("k-best descriptors")) {
                assertLimitedAt(result, "k-best descriptors");
                foundScratchBoundary = true;
            }
            owner.close();
            assertEquals(0L, ledger.currentBytes());
        }
        assertTrue(foundScratchBoundary, "a charged descriptor array must be rejectable");
    }

    @Test
    void kBestAncestryAllocationRefusalIsTypedAndKeepsActualWork() {
        // The 96-byte work counter precedes the same 64-byte PathRecord at 2,536 live.
        ProbabilisticInferenceResult result = solveWithLimit(profiles(5, 3), 2_496 + 96);

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
        // Fund the 96-byte counter as well as the historical 160-byte refusal fixture.
        AttemptMemoryLedger ledger = new AttemptMemoryLedger(160 + 96);
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();

        var abort = assertThrows(
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceMemoryLimitException.class,
                () -> new ProbabilisticInference().solve(profiles, PARAMETERS,
                    TraceBudgets.defaults(), null, CancellationProbe.NONE, owner));

        assertEquals("forward/backward", abort.stage());
        assertEquals(3L, abort.pairVisits());
        assertEquals(0L, abort.transitions());
        assertEquals(0L, abort.currentBytes());
        assertEquals(128L + 96L, abort.peakBytes());
        assertEquals(160L + 96L, abort.limitBytes());
        assertEquals(0L, ledger.currentBytes());
        owner.close();

        AttemptMemoryLedger precharged = new AttemptMemoryLedger(1_000 + 96);
        AttemptMemoryLedger.Owner prechargedOwner = precharged.rootOwner();
        prechargedOwner.reserve(840).adopt(new Object());
        var nested = assertThrows(
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceMemoryLimitException.class,
                () -> new ProbabilisticInference().solve(profiles, PARAMETERS,
                    TraceBudgets.defaults(), null, CancellationProbe.NONE, prechargedOwner));
        assertEquals("forward/backward", nested.stage());
        assertEquals(3L, nested.pairVisits());
        assertEquals(840L, nested.currentBytes());
        assertEquals(968L + 96L, nested.peakBytes());
        assertEquals(840L, precharged.currentBytes());
        prechargedOwner.close();
        assertEquals(0L, precharged.currentBytes());
    }

    @Test
    void nativeInferenceFailureGraphAndAccountingHaveAnExactAtomicBoundary() {
        List<InferenceProfile> profiles = profiles(1, 3);
        // Historical graph 200 + new result field 8 + immutable witness 80 = 288.
        AttemptMemoryLedger refused = new AttemptMemoryLedger(287);
        AttemptMemoryLedger.Owner refusedOwner = refused.rootOwner();
        var abort = assertThrows(
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceMemoryLimitException.class,
                () -> new ProbabilisticInference().solve(profiles, PARAMETERS,
                    TraceBudgets.defaults(), null, CancellationProbe.NONE, refusedOwner));
        assertEquals("k-best ancestry", abort.stage());
        assertEquals(3L, abort.pairVisits());
        assertEquals(0L, abort.currentBytes());
        assertEquals(168L + 96L, abort.peakBytes());
        refusedOwner.close();

        AttemptMemoryLedger admitted = new AttemptMemoryLedger(288);
        AttemptMemoryLedger.Owner admittedOwner = admitted.rootOwner();
        ProbabilisticInferenceResult result = new ProbabilisticInference().solve(profiles,
                PARAMETERS, TraceBudgets.defaults(), null, CancellationProbe.NONE, admittedOwner);
        assertLimitedAt(result, "k-best ancestry");
        assertTrue(result.workAccounting().isPresent());
        assertEquals(288L, admitted.currentBytes());
        assertEquals(288L, admitted.peakBytes());
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
        long pathBytes = AttemptMemoryLedger.objectBytes(64);
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

        AttemptMemoryLedger resizeLedger = new AttemptMemoryLedger(
                AttemptMemoryLedger.objectBytes(40) + 21 * pathBytes);
        AttemptMemoryLedger.Owner resizeRoot = resizeLedger.rootOwner();
        ProbabilisticInference.PathArena resizeArena = new ProbabilisticInference.PathArena(
                resizeRoot.child("direct-ancestry-margin"));
        for (int index = 0; index < 20; index++) resizeArena.start(index, index, 0.0);
        long beforeResize = resizeLedger.currentBytes();
        resizeArena.start(20, 20.0, 0.0);
        assertEquals(beforeResize + pathBytes, resizeLedger.currentBytes());
        assertEquals(beforeResize + pathBytes, resizeLedger.peakBytes(),
                "Entry21 charges only its actual record; eliminated index capacity is not allocated");
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
        assertEquals(288L, owner.currentBytes(),
                "only the atomic failure graph and witness survive, including final-result admission refusal");
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
