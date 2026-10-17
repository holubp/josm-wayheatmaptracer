package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

/** Attempt-wide regular-grid admission must retain evidence while respecting unchanged work limits. */
class V022TransitionLatticeAdmissionTest {
    @Test
    void wideProductionTraceAdmitsCompleteRegularGridWithinOriginalTransitionBudget() {
        var fixture = fixture(4, 10, 25, new TraceBudgets(96, 8_000_000, 30_000, 32, 8));
        var run = new ProbabilisticTraceEngine("scalar", EvidenceModelParameters.withoutShapeTerms())
                .traceWithUsage(fixture.request(), fixture.evidence(), fixture.network(), CancellationProbe.NONE);
        assertNotEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT, run.result().status(), run.result().explanation());
        assertFalse(run.result().hypotheses().isEmpty(), run.result().explanation());
        assertTrue(run.usage().transitions() <= 30_000);
    }

    @Test
    void isolatedMandatoryRichProfileRetainsItsOwnFloorInsideAFeasibleSparseRun() {
        var profiles = new ArrayList<ProbabilisticProfile>();
        for (int i = 0; i < 843; i++) profiles.add(profile(i, 25, List.of(), false));
        var modes = java.util.stream.IntStream.range(0, 12).mapToObj(i -> mode("weak-" + i, -21.37 + i * 3.67)).toList();
        profiles.set(421, profile(421, 25, modes, false));
        double[] proposals = noProposals(profiles.size());
        var plan = ProbabilisticLatticeAdmission.select(profiles, proposals, TraceBudgets.defaults(),
                CancellationProbe.NONE, null);
        assertTrue(plan.admitted(), "one mandatory-rich profile must not inherit the background ceiling");
        assertTrue(plan.coarsened());
        assertTrue(plan.maximumStates(421) > plan.maximumStates());
        var lattice = new ProbabilisticStateBuilder().buildWithReservedProposal(profiles.get(421),
                plan.maximumStates(421), Double.NaN, null).lattice().orElseThrow();
        for (var mode : modes) {
            assertRetained(lattice, mode.coreCenterMeters());
            for (double offset : mode.peakOffsetsMeters()) assertRetained(lattice, offset);
            for (double offset : mode.nestedCenterOffsetsMeters()) assertRetained(lattice, offset);
        }
        assertRetained(lattice, -25); assertRetained(lattice, 25);
    }

    @Test
    void twoProfileAdmissionIncludesInitialPairWorkRatherThanAnEmptyTripleSum() {
        var profiles = List.of(profile(0, 25, List.of(), false), profile(1, 25, List.of(), false));
        var plan = ProbabilisticLatticeAdmission.select(profiles, noProposals(2),
                new TraceBudgets(96, 1000, 1, 32, 8), CancellationProbe.NONE, null);
        assertFalse(plan.admitted(), "two full-window endpoints per profile cannot fit one unit of initial pair work");
    }

    @Test
    void heterogeneousTripleAndInitialPairWorkHaveIndependentLiteralBounds() {
        var profiles = List.of(profile(0, 0.5, List.of(), false), profile(1, 1, List.of(), false),
                profile(2, 1.5, List.of(), false));
        var plan = ProbabilisticLatticeAdmission.select(profiles, noProposals(3),
                new TraceBudgets(96, 1000, 40, 1, 1), CancellationProbe.NONE, null);
        assertTrue(plan.admitted()); assertTrue(plan.coarsened());
        assertEquals(54, plan.nativeForwardBackwardBound()); // 2*3 + 2*(2*3*4)
        assertEquals(2, plan.maximumStates());
        assertEquals(32, plan.completeScoreBound()); // 2*(2*2) + (2+1)*(2*2*2)
        assertEquals(8, plan.pairBound());
    }

    @Test
    void oneAndTwoProfileCompleteBoundsIncludeInitialScoreAllowances() {
        var budgets = new TraceBudgets(96, 1000, 20, 32, 8);
        var one = ProbabilisticLatticeAdmission.select(List.of(profile(0, 25, List.of(), false)),
                noProposals(1), budgets, CancellationProbe.NONE, null);
        assertTrue(one.admitted()); assertTrue(one.coarsened());
        assertEquals(10, one.maximumStates()); assertEquals(20, one.completeScoreBound());
        assertEquals(10, one.pairBound());
        var two = ProbabilisticLatticeAdmission.select(List.of(profile(0, 25, List.of(), false),
                profile(1, 25, List.of(), false)), noProposals(2), budgets, CancellationProbe.NONE, null);
        assertTrue(two.admitted()); assertTrue(two.coarsened());
        assertEquals(3, two.maximumStates()); assertEquals(18, two.completeScoreBound());
        assertEquals(9, two.pairBound());
    }

    @Test
    void nativeNarrowPositionsComponentsAndQuadratureRemainExactlyUnchanged() {
        var profiles = java.util.stream.IntStream.range(0, 843).mapToObj(i ->
                profile(i, 7.01, List.of(mode("weak-parallel", 2.137)), i == 0 || i == 842)).toList();
        double[] proposals = noProposals(profiles.size()); proposals[40] = 0.37;
        var plan = ProbabilisticLatticeAdmission.select(profiles, proposals, TraceBudgets.defaults(),
                CancellationProbe.NONE, null);
        assertTrue(plan.admitted()); assertFalse(plan.coarsened());
        assertEquals(-1, plan.completeScoreBound(), "native compatibility makes no complete-run proof");
        var builder = new ProbabilisticStateBuilder();
        for (int i : new int[] {0, 40, 421, 842}) {
            assertEquals(builder.buildWithProposal(profiles.get(i), 96, proposals[i], null),
                    builder.buildWithProposal(profiles.get(i), plan.maximumStates(i), proposals[i], null));
        }
    }

    @Test
    void coarsenedContinuationReservesCapacityAndNeverBecomesObservationEvidence() {
        var profiles = java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> profile(i, 25, List.of(), false)).toList();
        // This is a native regular-grid point, but it disappears from the coarser baseline.
        double[] proposals = noProposals(5); proposals[2] = 1.0;
        var plan = ProbabilisticLatticeAdmission.select(profiles, proposals,
                new TraceBudgets(96, 1000, 30_000, 32, 8), CancellationProbe.NONE, null);
        assertTrue(plan.admitted()); assertTrue(plan.coarsened());
        var builder = new ProbabilisticStateBuilder();
        assertRetained(builder.build(profiles.get(2), 96).lattice().orElseThrow(), 1.0);
        var lattice = builder.buildWithReservedProposal(profiles.get(2), plan.maximumStates(2), 1.0, null)
                .lattice().orElseThrow();
        assertRetained(lattice, 1.0); assertRetained(lattice, -25); assertRetained(lattice, 25);
        for (var cell : builder.build(profiles.get(2), plan.maximumStates(2) - 1).lattice().orElseThrow().cells())
            assertRetained(lattice, cell.offsetMeters());
        assertTrue(lattice.resolutionLimited());
        assertEquals(50, lattice.cells().stream().mapToDouble(LateralStateCell::quadratureWidthMeters).sum(), 1e-12);
        assertTrue(lattice.components().stream().noneMatch(c -> c.kind() == ObservationComponent.Kind.MEASURED));
        assertTrue(new ProbabilisticObservationModel().evaluate(profiles.get(2), lattice,
                EvidenceModelParameters.defaults()).entirelyMissing());
        var fixed = profile(0, 25, List.of(mode("fixed", 5.137)), true);
        var anchor = builder.buildWithReservedProposal(fixed, plan.maximumStates(), 1.0, null)
                .lattice().orElseThrow();
        assertEquals(1, anchor.cells().size()); assertRetained(anchor, 0);
        assertEquals(1, anchor.cells().get(0).quadratureWidthMeters());
    }

    @Test
    void continuationAlreadyMandatoryDoesNotConsumeAnotherReservedSlot() {
        var modes = List.of(mode("a", -10.137), mode("b", 0.137), mode("c", 10.137));
        var profile = profile(0, 25, modes, false);
        var builder = new ProbabilisticStateBuilder();
        int floor = builder.minimumReservedStateLimit(profile, Double.NaN, null);
        var baseline = builder.buildWithReservedProposal(profile, floor, Double.NaN, null);
        assertEquals(StateSpaceBuildResult.Status.COMPLETE, baseline.status());
        var result = builder.buildWithReservedProposal(profile, floor, modes.get(1).coreCenterMeters(), null);
        assertEquals(baseline, result, "an existing mandatory position needs no separate capacity");
    }

    @Test
    void everyWeakModalFamilyRetainsOriginalMandatoryPositionsWithoutAddingEvidence() {
        var modes = List.of(mode("weak-left", -10.137), mode("weak-right", 10.137));
        var profile = profile(0, 25, modes, false);
        var builder = new ProbabilisticStateBuilder();
        int floor = builder.minimumReservedStateLimit(profile, Double.NaN, null);
        var lattice = builder.buildWithReservedProposal(profile, floor, Double.NaN, null).lattice().orElseThrow();
        var nativeLattice = builder.build(profile, 96).lattice().orElseThrow();
        assertEquals(nativeLattice.components(), lattice.components(), "coordinate choices cannot create evidence priors");
        for (var mode : modes) {
            var centers = new ArrayList<Double>(); centers.add(mode.coreCenterMeters());
            centers.addAll(mode.peakOffsetsMeters()); centers.addAll(mode.nestedCenterOffsetsMeters());
            for (double center : centers) {
                assertRetained(lattice, center);
            }
        }
        assertRetained(lattice, -25); assertRetained(lattice, 25);
        assertEquals(50, lattice.cells().stream().mapToDouble(LateralStateCell::quadratureWidthMeters).sum(), 1e-12);
        assertEquals(floor,lattice.cells().size(),"the original mandatory floor requires no rejected V4 stencil");
        assertTrue(lattice.resolutionLimited());
    }

    @Test
    void adaptiveCellPartitionAndMarginalsEqualIndependentMeasureWeightedEnumeration() {
        var profiles = new ArrayList<ProbabilisticProfile>();
        for (int i = 0; i < 3; i++) {
            double center = new double[] {-0.37, 0.23, 1.13}[i];
            var mode = new ProbabilisticProfile.Mode("adaptive-" + i, "adaptive-" + i,
                    center - 0.2, center + 0.2, 0.8, 0.9, 0.9, List.of(center), List.of(), false);
            profiles.add(profile(i, 25, List.of(mode), false));
        }
        var budgets = new TraceBudgets(96, 8_000_000, 5_000, 1, 1);
        var plan = ProbabilisticLatticeAdmission.select(profiles, noProposals(3), budgets, CancellationProbe.NONE, null);
        assertTrue(plan.coarsened()); assertTrue(plan.admitted());
        var evaluated = new ArrayList<InferenceProfile>();
        for (var profile : profiles) {
            var lattice = new ProbabilisticStateBuilder().buildWithReservedProposal(profile,
                    plan.maximumStates(profile.profileIndex()), Double.NaN, null).lattice().orElseThrow();
            assertEquals(50, lattice.cells().stream().mapToDouble(LateralStateCell::quadratureWidthMeters).sum(), 1e-12);
            assertTrue(lattice.cells().stream().map(LateralStateCell::quadratureWidthMeters).distinct().count() > 2,
                    "oracle must exercise adaptive, unequal quadrature cells");
            evaluated.add(new ProbabilisticObservationModel().evaluate(profile, lattice, EvidenceModelParameters.defaults()));
        }
        // This oracle enumerates paths independently and includes each actual cell's integration measure.
        var oracle = V022ProbabilisticInferenceTest.bruteForce(evaluated, EvidenceModelParameters.defaults());
        var result = new ProbabilisticInference().solve(evaluated, EvidenceModelParameters.defaults(), budgets);
        assertArrayEquals(oracle.paths().get(0).states(), result.mapPath().orElseThrow().stateIndices());
        assertEquals(oracle.paths().get(0).energy(), result.mapPath().orElseThrow().energy(), 1e-12);
        assertEquals(oracle.logPartition(), result.logPartition(), 1e-12);
        for (int i = 0; i < 3; i++) assertArrayEquals(oracle.marginals().get(i), result.positionMarginals().get(i), 1e-12);
    }

    @Test
    void optionalFineGridCoincidenceCannotWaiveTheStableContinuationReservation() {
        var profile = profile(0, 25, List.of(mode("weak", -10.137)), false);
        var builder = new ProbabilisticStateBuilder();
        int floor = builder.minimumReservedStateLimit(profile, Double.NaN, null);
        assertEquals(floor + 1, builder.minimumReservedStateLimit(profile, -10, null));
        assertEquals(StateSpaceBuildResult.Status.STATE_LIMIT,
                builder.buildWithReservedProposal(profile, floor, -10, null).status());
        var lattice = builder.buildWithReservedProposal(profile, floor + 1, -10, null).lattice().orElseThrow();
        assertRetained(lattice, -10);
    }

    @Test
    void twentyModesFitOriginalMandatoryFloorWithoutDroppingOriginalModes() {
        var modes = java.util.stream.IntStream.range(0, 20).mapToObj(i -> mode("weak-" + i, -21.37 + i * 2.17)).toList();
        var profiles = new ArrayList<ProbabilisticProfile>();
        for (int i = 0; i < 843; i++) profiles.add(profile(i, 25, i == 421 ? modes : List.of(), false));
        var builder = new ProbabilisticStateBuilder();
        int floor=builder.minimumReservedStateLimit(profiles.get(421), Double.NaN, null);
        assertTrue(floor <= 96);
        var plan = ProbabilisticLatticeAdmission.select(profiles, noProposals(843), TraceBudgets.defaults(), CancellationProbe.NONE, null);
        assertTrue(plan.admitted()); assertTrue(plan.coarsened());
        var result = builder.buildWithReservedProposal(profiles.get(421), 96, Double.NaN, null);
        assertEquals(StateSpaceBuildResult.Status.COMPLETE, result.status());
        assertEquals(20, profiles.get(421).modes().size());
        for (var mode : modes) {
            assertTrue(result.retainedMandatoryOffsets().contains(mode.coreCenterMeters()));
            assertTrue(result.retainedMandatoryOffsets().containsAll(mode.peakOffsetsMeters()));
            assertTrue(result.retainedMandatoryOffsets().containsAll(mode.nestedCenterOffsetsMeters()));
            var lattice=result.lattice().orElseThrow();
            assertRetained(lattice,mode.coreCenterMeters());
            for(double offset:mode.peakOffsetsMeters()) assertRetained(lattice,offset);
            for(double offset:mode.nestedCenterOffsetsMeters()) assertRetained(lattice,offset);
        }
        assertEquals(50,result.lattice().orElseThrow().cells().stream()
                .mapToDouble(LateralStateCell::quadratureWidthMeters).sum(),1e-12);
    }

    @Test
    void infeasibleMandatoryFloorsFailInsteadOfDroppingWeakModes() {
        var modes = java.util.stream.IntStream.range(0, 20).mapToObj(i -> mode("weak-" + i, -21.37 + i * 2.17)).toList();
        var profiles = List.of(profile(0, 25, List.of(), false), profile(1, 25, modes, false),
                profile(2, 25, List.of(), false));
        var plan = ProbabilisticLatticeAdmission.select(profiles, noProposals(3),
                new TraceBudgets(96, 1000, 1000, 32, 8), CancellationProbe.NONE, null);
        assertFalse(plan.admitted());
        assertEquals(20, profiles.get(1).modes().size());
    }

    @Test
    void admissionCancellationAndMemoryRefusalReleaseEveryTemporaryInventory() {
        var profiles = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> profile(i, 25, List.of(), false)).toList();
        var tiny = new AttemptMemoryLedger(1); var tinyOwner = tiny.rootOwner();
        assertThrows(ProbabilisticInference.MemoryLimit.class, () -> ProbabilisticLatticeAdmission.select(
                profiles, noProposals(20), TraceBudgets.defaults(), CancellationProbe.NONE, tinyOwner));
        assertEquals(0, tiny.currentBytes()); tinyOwner.close();
        var ledger = AttemptMemoryLedger.production(); var owner = ledger.rootOwner(); int[] checks = {0};
        assertThrows(CancellationException.class, () -> ProbabilisticLatticeAdmission.select(profiles,
                noProposals(20), TraceBudgets.defaults(), () -> ++checks[0] == 15, owner));
        assertEquals(0, ledger.currentBytes()); owner.close();
    }

    @Test
    void public843ProfileTwentyFiveMeterProductionRunFitsUnchangedDefaultBudgets() {
        var fixture = fixture(842, 1, 25, TraceBudgets.defaults());
        ModernDiagnosticCounters.begin();
        try {
            var run = new ProbabilisticTraceEngine("scalar", EvidenceModelParameters.withoutShapeTerms())
                    .traceWithUsage(fixture.request(), fixture.evidence(), fixture.network(), CancellationProbe.NONE);
            assertNotEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT, run.result().status(), run.result().explanation());
            assertFalse(run.result().hypotheses().isEmpty(), run.result().explanation());
            assertTrue(run.usage().transitions() <= 128_000_000);
            var counters = ModernDiagnosticCounters.snapshot();
            assertEquals(1, counters.get("trace.latticeBudgetCoarsened").intValue());
            assertTrue(counters.get("trace.latticeCompleteScoreBound").longValue() <= 128_000_000);
            assertTrue(counters.get("trace.latticeResolutionLimitedProfiles").intValue() > 0);
        } finally { ModernDiagnosticCounters.end(); }
    }

    private static void assertRetained(ProbabilisticStateLattice lattice, double offset) {
        assertTrue(lattice.cells().stream().anyMatch(cell -> cell.offsetMeters() == offset), "missing " + offset);
    }

    private static double[] noProposals(int count) {
        double[] result = new double[count]; Arrays.fill(result, Double.NaN); return result;
    }

    private static ProbabilisticProfile.Mode mode(String id, double center) {
        return new ProbabilisticProfile.Mode(id, id, center - 0.1, center + 0.1, 0.6,
                0.08, 0.1, List.of(center - 0.07), List.of(center + 0.06), false);
    }

    private static ProbabilisticProfile profile(int index, double halfWidth,
            List<ProbabilisticProfile.Mode> modes, boolean fixed) {
        var samples = List.of(new ProbabilisticProfile.Sample(-halfWidth, 0.02, true),
                new ProbabilisticProfile.Sample(0, 0.02, true),
                new ProbabilisticProfile.Sample(halfWidth, 0.02, true));
        return new ProbabilisticProfile(index, index, new MetricPoint(index, 0), new MetricPoint(0, 1),
                -halfWidth, halfWidth, 2, true, 0.02, samples, modes, List.of(),
                fixed ? OptionalDouble.of(0) : OptionalDouble.empty(), List.of(), 0);
    }

    private record Fixture(TraceRequest request, EvidenceSnapshot evidence, NetworkSnapshot network) { }

    private static Fixture fixture(int intervals, double step, double halfWidth, TraceBudgets budgets) {
        int width = (int) Math.ceil(intervals * step) + 41, height = 101;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            values[y * width + x] = 0.02 + 0.8 * StrictMath.exp(-0.5 * Math.pow((y - 55.25) / 1.2, 2));
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
                new GeographicPoint(-1, -1), new GeographicPoint(1, 1));
        var region = MetricRegion.rectangle(0, 0, width - 1, height - 1);
        var lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "wide-admission",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        var evidence = new EvidenceSnapshot("wide-admission", frame,
                new RasterMetricTransform("wide-admission", RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                        new MetricPoint(0, 0), 1, 0, 0, 1, 1), EvidenceResolution.nativeSource(2, 1),
                region, region, Map.of("scalar", new ScalarEvidenceField(width, height, values, valid, lineage)),
                "wide-admission");
        var first = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 101);
        var last = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 102);
        var way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 103);
        var source = List.of(new MetricPoint(20, 50), new MetricPoint(20 + intervals * step, 50));
        Map<PrimitiveKey, DetachedPrimitive> primitives = Map.of(
                first, new DetachedNode(first, frame.toGeographic(source.get(0)), Map.of(), false, false),
                last, new DetachedNode(last, frame.toGeographic(source.get(1)), Map.of(), false, false),
                way, new DetachedWay(way, List.of(first, last), Map.of("highway", "path"), false, false));
        var closure = new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY, "wide-admission",
                primitives.keySet(), Set.of(way), Set.of(), Set.of(first, last), Set.of(),
                Map.of(way, List.of(new OccurrenceRange(0, 1))), List.of(), region, region, false, true, true, true);
        var network = new NetworkSnapshot("wide-admission-network", SnapshotRole.CAPTURED_BEFORE,
                "wide-admission-dataset", 1, closure, primitives,
                V022SnapshotFixtures.closedWorldReferrerWatches(primitives));
        var request = new TraceRequest(way, new OccurrenceRange(0, 1), TrackerMode.PROBABILISTIC,
                AlignmentMode.PRECISE_SHAPE, RecoveryPermissions.disabled(halfWidth), budgets,
                evidence.snapshotId(), evidence.canonicalHash(), network.snapshotId(), network.canonicalHash(),
                "settings", "parameters", "sampler", step,
                new ProbabilisticProfileFactory().profileChainage(List.of(
                        frame.toMetric(((DetachedNode) primitives.get(first)).coordinate()),
                        frame.toMetric(((DetachedNode) primitives.get(last)).coordinate())), step), evidence.resolution());
        return new Fixture(request, evidence, network);
    }
}
