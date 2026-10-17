package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Native admission distinguishes proposal-only overflow from an overfull baseline. */
class V022NativeProposalAdmissionTest {
    @Test
    void proposalOverflowCoarsensOnlyAfterBaselineWorkAlsoExceedsBudget() {
        var profiles = profiles(4);
        double[] proposals = proposals(5);
        proposals[2] = 0.123;
        var budgets = new TraceBudgets(12, 200, 3_300, 1, 1);
        var builder = new ProbabilisticStateBuilder();

        var originalNative = builder.build(profiles.get(2), 12);
        assertEquals(StateSpaceBuildResult.Status.COMPLETE, originalNative.status());
        assertEquals(12, originalNative.lattice().orElseThrow().cells().size());
        assertTrue(originalNative.lattice().orElseThrow().cells().stream()
                .noneMatch(cell -> cell.offsetMeters() == proposals[2]), "proposal is novel to the native baseline");
        assertEquals(StateSpaceBuildResult.Status.STATE_LIMIT,
                builder.buildWithProposal(profiles.get(2), 12, proposals[2], null).status());

        var plan = ProbabilisticLatticeAdmission.select(profiles, proposals, budgets, CancellationProbe.NONE, null);
        assertTrue(plan.admitted());
        assertTrue(plan.coarsened());
        assertTrue(plan.nativeForwardBackwardBound() > budgets.maximumTransitions());
        assertTrue(plan.completeScoreBound() <= budgets.maximumTransitions());
        assertTrue(plan.pairBound() <= budgets.maximumPairVisits());

        for (int index = 0; index < profiles.size(); index++) {
            var build = builder.buildWithReservedProposal(profiles.get(index), plan.maximumStates(index),
                    proposals[index], null);
            assertEquals(StateSpaceBuildResult.Status.COMPLETE, build.status(), "profile " + index);
            var lattice = build.lattice().orElseThrow();
            if (index == 2) {
                double proposal = proposals[index];
                assertTrue(lattice.cells().stream().anyMatch(cell -> cell.offsetMeters() == proposal),
                        "the continuation coordinate is actually retained");
                for (var mode : profiles.get(index).modes()) {
                    assertRetained(lattice, mode.coreCenterMeters());
                }
                assertRetained(lattice, -25.0);
                assertRetained(lattice, 25.0);
                assertEquals(7, lattice.cells().size(), "all four modes, both bounds and proposal survive");
            }
        }
        assertEquals("transition-admitted-regular-continuation-v3-overflow-1",
                EvidenceModelParameters.stateSamplingPolicyVersion());
    }

    @Test
    void nativeBaselineThatFitsWorkIsPreservedAndProposalRefusedWithoutCoarsening() {
        var profiles = profiles(4);
        double[] proposals = proposals(5);
        proposals[2] = 0.123;
        var plan = ProbabilisticLatticeAdmission.select(profiles, proposals,
                new TraceBudgets(12, 1_000, 20_000, 1, 1), CancellationProbe.NONE, null);

        assertFalse(plan.admitted(), "proposal-only native overflow is an explicit refusal");
        assertFalse(plan.coarsened(), "a proposal cannot cause coarsening when native FB already fits");
        assertEquals(10_512, plan.nativeForwardBackwardBound());
        assertEquals(-1, plan.completeScoreBound());
        var nativeResult = new ProbabilisticStateBuilder().build(profiles.get(2), 12);
        assertEquals(StateSpaceBuildResult.Status.COMPLETE, nativeResult.status());
        assertEquals(12, nativeResult.lattice().orElseThrow().cells().size());
    }

    @Test
    void genuineMandatoryBaselineOverflowIsRefusedWithUnavailableBounds() {
        var profiles = profiles(11);
        var plan = ProbabilisticLatticeAdmission.select(profiles, proposals(5),
                new TraceBudgets(12, 100, 10_000, 1, 1), CancellationProbe.NONE, null);
        assertFalse(plan.admitted());
        assertFalse(plan.coarsened());
        assertEquals(-1, plan.nativeForwardBackwardBound());
        assertEquals(-1, plan.completeScoreBound());
        assertEquals(-1, plan.pairBound());
    }

    @Test
    void proposalFloorAboveOriginalCeilingIsRefusedWithoutRaisingTheCap() {
        var profiles = profiles(10);
        double[] proposals = proposals(5);
        proposals[2] = 0.123;
        var builder = new ProbabilisticStateBuilder();
        assertEquals(12, builder.build(profiles.get(2), 12).lattice().orElseThrow().cells().size());
        assertEquals(13, builder.minimumReservedStateLimit(profiles.get(2), proposals[2], null));
        assertEquals(StateSpaceBuildResult.Status.STATE_LIMIT,
                builder.buildWithProposal(profiles.get(2), 12, proposals[2], null).status());
        var plan = ProbabilisticLatticeAdmission.select(profiles, proposals,
                new TraceBudgets(12, 200, 3_300, 1, 1), CancellationProbe.NONE, null);
        assertFalse(plan.admitted());
        assertTrue(plan.coarsened());
        assertEquals(10_512, plan.nativeForwardBackwardBound());
        assertEquals(-1, plan.completeScoreBound());
        assertEquals(10, profiles.get(2).modes().size());
    }

    @Test
    void cancellationAndInventoryMemoryRefusalReleaseAttemptOwnedState() {
        var profiles = profiles(4);
        double[] proposals = proposals(5);
        proposals[2] = 0.123;
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        AtomicInteger checkpoints = new AtomicInteger();
        assertThrows(CancellationException.class, () -> ProbabilisticLatticeAdmission.select(profiles,
                proposals, new TraceBudgets(12, 200, 3_300, 1, 1),
                () -> checkpoints.incrementAndGet() >= 3, owner));
        assertEquals(0, ledger.currentBytes());
        owner.close();

        var tiny = new AttemptMemoryLedger(1);
        var tinyOwner = tiny.rootOwner();
        assertThrows(ProbabilisticInference.MemoryLimit.class, () -> ProbabilisticLatticeAdmission.select(
                profiles, proposals, new TraceBudgets(12, 200, 3_300, 1, 1), CancellationProbe.NONE, tinyOwner));
        assertEquals(0, tiny.currentBytes());
        tinyOwner.close();
    }

    private static List<ProbabilisticProfile> profiles(int modeCount) {
        var profiles = new ArrayList<ProbabilisticProfile>();
        for (int index = 0; index < 5; index++) {
            List<ProbabilisticProfile.Mode> modes = index == 2 ? modes(modeCount) : List.of();
            List<ProbabilisticProfile.Sample> samples = List.of(
                    new ProbabilisticProfile.Sample(-25.0, 0.02, true),
                    new ProbabilisticProfile.Sample(0.0, 0.02, true),
                    new ProbabilisticProfile.Sample(25.0, 0.02, true));
            profiles.add(new ProbabilisticProfile(index, index, new MetricPoint(index, 0), new MetricPoint(0, 1),
                    -25.0, 25.0, 1.0, true, 0.02, samples, modes, List.of(), OptionalDouble.empty(),
                    ImageOrientationSupport.unknown(ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT)));
        }
        return List.copyOf(profiles);
    }

    private static List<ProbabilisticProfile.Mode> modes(int count) {
        var modes = new ArrayList<ProbabilisticProfile.Mode>();
        for (int index = 0; index < count; index++) {
            double center = -18.0 + 4.0 * index;
            modes.add(new ProbabilisticProfile.Mode("mode-" + index, "lineage-" + index,
                    center - 0.05, center + 0.05, 0.5, 0.8, 0.9, List.of(), List.of(), false));
        }
        return List.copyOf(modes);
    }

    private static double[] proposals(int count) {
        double[] proposals = new double[count];
        java.util.Arrays.fill(proposals, Double.NaN);
        return proposals;
    }

    private static void assertRetained(ProbabilisticStateLattice lattice, double offset) {
        assertTrue(lattice.cells().stream().anyMatch(cell -> cell.offsetMeters() == offset), "missing " + offset);
    }
}
