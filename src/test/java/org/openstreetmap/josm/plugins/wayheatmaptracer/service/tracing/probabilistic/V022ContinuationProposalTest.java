package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class V022ContinuationProposalTest {
    @Test
    void augmentationRetainsEveryBaselinePositionAndRepartitionsPhysicalWidths() {
        var profile = series(false, 1).get(4);
        var builder = new ProbabilisticStateBuilder();
        var baseline = builder.build(profile, 96).lattice().orElseThrow();
        var augmented = builder.buildWithProposal(profile, 96, 0.37, null).lattice().orElseThrow();
        for (var old : baseline.cells()) {
            assertTrue(augmented.cells().stream().anyMatch(cell -> cell.offsetMeters() == old.offsetMeters()
                    && cell.mandatory() == old.mandatory() && cell.exactAnchor() == old.exactAnchor()
                    && cell.branchLabel().equals(old.branchLabel())));
        }
        assertEquals(baseline.cells().size() + 1, augmented.cells().size());
        assertTrue(augmented.cells().stream().anyMatch(cell -> cell.offsetMeters() == 0.37
                && cell.branchLabel().equals("unlocalized")));
        assertEquals(16.0, augmented.cells().stream()
                .mapToDouble(LateralStateCell::quadratureWidthMeters).sum(), 1e-12);
    }

    @Test
    void fullLatticeRefusesAnotherDistinctPositionInsteadOfRemovingAnOldOne() {
        var profile = series(false, 1).get(4);
        var result = new ProbabilisticStateBuilder().buildWithProposal(profile, 8, 0.37, null);
        assertEquals(StateSpaceBuildResult.Status.STATE_LIMIT, result.status());
        assertTrue(result.lattice().isEmpty());
    }

    @Test
    void duplicateProposalAndFixedAnchorKeepTheOriginalLatticeExactly() {
        var profile = series(false, 1).get(4);
        var builder = new ProbabilisticStateBuilder();
        var baseline = builder.build(profile, 96).lattice().orElseThrow();
        assertEquals(baseline, builder.buildWithProposal(profile, 96,
                baseline.cells().get(4).offsetMeters(), null).lattice().orElseThrow());
        var fixed = series(true, 1).get(5);
        assertEquals(builder.build(fixed, 96), builder.buildWithProposal(fixed, 96, 0.37, null));
    }

    @Test
    void uniqueMeasuredBoundariesSupplyAnalyticPositionsOnlyInsideTheWeakRun() {
        var profiles = series(false, 1);
        double[] offsets = ProbabilisticContinuationProposals.build(profiles,
                CancellationProbe.NONE, null);
        for (int i = 0; i < offsets.length; i++) {
            if (i > 2 && i < 8) assertEquals(0.2 * (i - 1), offsets[i], 1e-12);
            else assertTrue(Double.isNaN(offsets[i]), "boundary/strong profile " + i);
        }
    }

    @Test
    void physicalIntersectionsAreInvariantUnderRotationAndTraversalReversal() {
        var input = series(false, 1);
        var original = ProbabilisticContinuationProposals.build(input, CancellationProbe.NONE, null);
        for (boolean reversed : List.of(false, true)) {
            var transformed = transformed(input, 1.1, reversed);
            var offsets = ProbabilisticContinuationProposals.build(transformed, CancellationProbe.NONE, null);
            for (int i = 0; i < offsets.length; i++) {
                double expected = original[reversed ? offsets.length - 1 - i : i];
                if (Double.isNaN(expected)) assertTrue(Double.isNaN(offsets[i]));
                else assertEquals(reversed ? -expected : expected, offsets[i], 1e-12);
            }
        }
    }

    @Test
    void unknownNativePitchCannotAuthorizeTheNativePixelResidualPredicate() {
        var input = new ArrayList<>(series(false, 1));
        var profile = input.get(2);
        input.set(2, new ProbabilisticProfile(profile.profileIndex(), profile.chainageMeters(),
                profile.anchor(), profile.normalUnit(), -8, 8, 1, false, profile.noiseFloor(),
                profile.samples(), profile.modes(), profile.censoredModes(),
                profile.exactAnchorOffsetMeters(), profile.orientationSupport()));
        assertNone(ProbabilisticContinuationProposals.build(input, CancellationProbe.NONE, null));
    }

    @Test
    void allocationRefusalAndCancellationAfterAllocationReleaseTheirOwner() {
        var refused = new AttemptMemoryLedger(1);
        var refusalOwner = refused.rootOwner();
        assertThrows(ProbabilisticInference.MemoryLimit.class, () -> ProbabilisticContinuationProposals.build(
                series(false, 1), CancellationProbe.NONE, refusalOwner));
        assertEquals(0L, refused.currentBytes());
        refusalOwner.close();
        var canceled = AttemptMemoryLedger.production();
        var cancelOwner = canceled.rootOwner();
        int[] checkpoints = {0};
        assertThrows(CancellationException.class, () -> ProbabilisticContinuationProposals.build(
                series(false, 1), () -> { if (++checkpoints[0] == 3) throw new CancellationException();
                    return false; },
                cancelOwner));
        assertEquals(0L, canceled.currentBytes());
        cancelOwner.close();
    }

    @Test
    void multipleCompatibleBranchesDoNotProduceAnAverageOrChooseOneBranch() {
        assertNone(ProbabilisticContinuationProposals.build(series(false, 2),
                CancellationProbe.NONE, null));
    }

    @Test
    void localDirectionUncertaintyIsNotExtrapolatedAcrossTheUnobservedGap() {
        var input = stretchedSeries();
        var offsets = ProbabilisticContinuationProposals.build(input, CancellationProbe.NONE, null);
        for (int index = 3; index < 8; index++) {
            assertEquals(0.2 * (index - 1), offsets[index], 1e-12,
                    "A local native-pixel direction error must not pretend to be gap-wide proof");
        }
    }

    @Test
    void customDescriptorRayAndNativePitchRetainTheirPhysicalEligibilityScale() {
        var defaults = EvidenceModelParameters.defaults().localization();
        var longRay = new EvidenceModelParameters.Localization(defaults.orientationHeadingCount(),
                40.0, defaults.orientationRayLengthPitches(), defaults.maximumOrientationStepPitches(),
                defaults.minimumOrientationValidFraction(), defaults.orientationBackgroundQuantile(),
                defaults.orientationProminenceFraction(), defaults.maximumOrientationSampleCount(),
                defaults.localModeProminenceFraction(), defaults.localModeShoulderFraction(),
                defaults.localModeCoreFraction(), defaults.routeProfileHalfWidthMeters(),
                defaults.scalarAmplitudeHalfResponse());
        assertNone(ProbabilisticContinuationProposals.build(stretchedSeries(), CancellationProbe.NONE, null,
                longRay));
        var coarse = new ArrayList<ProbabilisticProfile>();
        for (var old : stretchedSeries()) {
            coarse.add(new ProbabilisticProfile(old.profileIndex(), old.chainageMeters(), old.anchor(),
                    old.normalUnit(), -8, 8, 3.0, true, old.noiseFloor(), old.samples(), old.modes(),
                    old.censoredModes(), old.exactAnchorOffsetMeters(), old.orientationSupport()));
        }
        var offsets = ProbabilisticContinuationProposals.build(coarse, CancellationProbe.NONE, null, longRay);
        assertEquals(0.8, offsets[5], 1e-12, "physical localization uncertainty scales with native pitch");
    }

    @Test
    void augmentedQuadratureMatchesAnIndependentUniformPartitionAndBothExactSolvers() {
        var sampled = series(false, 1).subList(2, 5);
        var evaluated = new ArrayList<InferenceProfile>();
        var parameters = new EvidenceModelParameters("continuation-uniform-partition", 0, 0, 0, 0, 0,
                0.1, 1, 1e-9);
        for (var profile : sampled) {
            var lattice = new ProbabilisticStateBuilder().buildWithProposal(profile, 96, 0.37, null)
                    .lattice().orElseThrow();
            evaluated.add(new ProbabilisticObservationModel().evaluate(profile, lattice, parameters));
        }
        var eagerOwner = AttemptMemoryLedger.production().rootOwner();
        var exactOwner = AttemptMemoryLedger.production().rootOwner();
        try {
            var region = org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion
                    .rectangle(-100, -100, 100, 100);
            var budgets = org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults();
            var solver = new ProbabilisticInference();
            var eager = solver.solve(evaluated, parameters, budgets, region, CancellationProbe.NONE,
                    eagerOwner, ProbabilisticExecutionPolicy.Mode.EAGER_V1);
            var exact = solver.solve(evaluated, parameters, budgets, region, CancellationProbe.NONE,
                    exactOwner, ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            assertEquals(3 * Math.log(16), exact.logPartition(), 1e-11,
                    "zero energy integrates the product of three independent16m windows");
            assertEquals(eager.logPartition(), exact.logPartition(), 0.0);
            assertEquals(eager.mapPath().orElseThrow().points(), exact.mapPath().orElseThrow().points());
            assertEquals(eager.mapPath().orElseThrow().energy(), exact.mapPath().orElseThrow().energy(), 0.0);
            for (int index = 0; index < evaluated.size(); index++) {
                var marginals = exact.positionMarginals().get(index);
                for (int state = 0; state < marginals.length; state++) {
                    assertEquals(evaluated.get(index).cells().get(state).quadratureWidthMeters() / 16,
                            marginals[state], 1e-11, "independent Voronoi-measure oracle");
                }
            }
        } finally {
            eagerOwner.close();
            exactOwner.close();
        }
    }

    @Test
    void transverseMeasuredDirectionCannotSupplyAContinuation() {
        var input = new ArrayList<>(series(false, 1));
        var profile = input.get(2);
        var old = profile.modes().get(0);
        var transverse = new ImageOrientationSupport(ImageOrientationSupport.Status.MEASURED_TWO_SIDED,
                List.of(new ImageOrientationSupport.AngularMode(Math.PI / 2, Math.PI / 2,
                        Math.PI / 2, 1)), 1);
        input.set(2, withModes(profile, List.of(new ProbabilisticProfile.Mode(old.id(), old.evidenceLineage(),
                old.coreMinimumMeters(), old.coreMaximumMeters(), 0.2, 1, 1, List.of(), List.of(), false,
                transverse, 0.8, 1, 0.0, 4.0, 4.0))));
        assertNone(ProbabilisticContinuationProposals.build(input, CancellationProbe.NONE, null));
    }

    @Test
    void proposalUsesIndependentLocalCoreOwnershipAndItsActualOrientation() throws ReflectiveOperationException {
        var profile = series(false, 1).get(4);
        var measured = series(false, 1).get(2).modes().get(0);
        var local = new ProbabilisticProfile.Mode("local", "local", 0.27, 0.47, 0.2,
                1, 1, List.of(0.37), List.of(), false, measured.orientationSupport(), 0.8, 0, 0.2);
        profile = withModes(profile, List.of(local));
        var lattice = new ProbabilisticStateBuilder().buildWithProposal(profile, 96, 0.36, null)
                .lattice().orElseThrow();
        var evaluated = new ProbabilisticObservationModel().evaluate(profile, lattice,
                EvidenceModelParameters.defaults());
        int state = java.util.stream.IntStream.range(0, lattice.cells().size())
                .filter(index -> lattice.cells().get(index).offsetMeters() == 0.36).findFirst().orElseThrow();
        assertEquals("local", lattice.cells().get(state).branchLabel());
        assertEquals(local.orientationSupport(), evaluated.orientationSupport(state));
        assertTrue(directlyOwned(profile, evaluated, state));
    }

    @Test
    void emptyOrCensoredInteriorCannotBorrowTheBoundaryMode() throws ReflectiveOperationException {
        var empty = series(false, 1).get(4);
        var censored = new ProbabilisticProfile(empty.profileIndex(), empty.chainageMeters(),
                empty.anchor(), empty.normalUnit(), -8, 8, 1, true, empty.noiseFloor(), empty.samples(),
                List.of(), List.of(new ProbabilisticProfile.CensoredMode("clipped", "clipped",
                    ProbabilisticProfile.CensorSide.LEFT, -8, 1, true)), OptionalDouble.empty(),
                empty.orientationSupport());
        for (var profile : List.of(empty, censored)) {
            var lattice = new ProbabilisticStateBuilder().buildWithProposal(profile, 96, 0.37, null)
                    .lattice().orElseThrow();
            var evaluated = new ProbabilisticObservationModel().evaluate(profile, lattice,
                    EvidenceModelParameters.defaults());
            int state = java.util.stream.IntStream.range(0, lattice.cells().size())
                    .filter(index -> lattice.cells().get(index).offsetMeters() == 0.37).findFirst().orElseThrow();
            assertEquals("unlocalized", lattice.cells().get(state).branchLabel());
            assertTrue(!directlyOwned(profile, evaluated, state));
            assertTrue(lattice.components().stream().noneMatch(component ->
                    component.kind() == ObservationComponent.Kind.MEASURED));
        }
    }

    @Test
    void nonmonotoneAndOffWindowIntersectionsRejectTheEntireRun() {
        for (boolean offWindow : List.of(false, true)) {
            var input = new ArrayList<>(series(false, 1));
            var profile = input.get(5);
            input.set(5, new ProbabilisticProfile(profile.profileIndex(), profile.chainageMeters(),
                    offWindow ? profile.anchor() : new MetricPoint(100, 0), profile.normalUnit(),
                    offWindow ? -0.1 : -8, offWindow ? 0.1 : 8, 1, true, profile.noiseFloor(),
                    profile.samples(), profile.modes(), profile.censoredModes(),
                    profile.exactAnchorOffsetMeters(), profile.orientationSupport()));
            assertNone(ProbabilisticContinuationProposals.build(input, CancellationProbe.NONE, null));
        }
    }

    @Test
    void augmentedAndStateLimitedLatticesReleaseAllRetainedObjectsWithTheOwner() {
        for (int cap : List.of(8, 96)) {
            var ledger = AttemptMemoryLedger.production();
            var owner = ledger.rootOwner();
            var result = new ProbabilisticStateBuilder().buildWithProposal(series(false, 1).get(4),
                    cap, 0.37, owner);
            assertEquals(cap == 8 ? StateSpaceBuildResult.Status.STATE_LIMIT
                    : StateSpaceBuildResult.Status.COMPLETE, result.status());
            assertTrue(owner.currentBytes() > 0, "baseline and its borrowed components remain charged");
            owner.close();
            assertEquals(0L, ledger.currentBytes());
        }
    }

    @Test
    void pairCountAboveEightCannotCertifyUniquenessFromItsFirstEightPairs() {
        assertNone(ProbabilisticContinuationProposals.build(series(false, 3),
                CancellationProbe.NONE, null));
    }

    @Test
    void anInteriorExactAnchorPreventsAChordAcrossTheRun() {
        assertNone(ProbabilisticContinuationProposals.build(series(true, 1),
                CancellationProbe.NONE, null));
    }

    @Test
    void anUnsupportedExteriorCannotActAsAContinuationBoundary() {
        var profiles = new ArrayList<>(series(false, 1));
        var boundary = profiles.get(2);
        var mode = boundary.modes().get(0);
        profiles.set(2, withModes(boundary, List.of(new ProbabilisticProfile.Mode(
                mode.id(), mode.evidenceLineage(), mode.coreMinimumMeters(), mode.coreMaximumMeters(),
                0.2, 1.0, 1.0, List.of(), List.of(), false, mode.orientationSupport(),
                0.8, 0.0, 0.0))));
        assertNone(ProbabilisticContinuationProposals.build(profiles, CancellationProbe.NONE, null));
    }

    @Test
    void generationChecksCancellationAndChargesItsRetainedArray() {
        assertThrows(CancellationException.class, () -> ProbabilisticContinuationProposals.build(
                series(false, 1), () -> { throw new CancellationException(); }, null));
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        ProbabilisticContinuationProposals.build(series(false, 1), CancellationProbe.NONE, owner);
        assertTrue(owner.currentBytes() >= AttemptMemoryLedger.arrayBytes(11, Double.BYTES));
        owner.close();
        assertEquals(0L, ledger.currentBytes());
    }

    @Test
    void individuallyEligibleRunsCannotEvadeTheAttemptWideWorkLimit() {
        var input = new ArrayList<ProbabilisticProfile>();
        for (int run = 0; run < 10; run++) {
            for (var profile : series(false, 1)) {
                double x = profile.anchor().xMeters() + run * 30.0;
                input.add(new ProbabilisticProfile(input.size(), x, new MetricPoint(x, 0),
                        profile.normalUnit(), -8, 8, 1, true, profile.noiseFloor(), profile.samples(),
                        profile.modes(), profile.censoredModes(), profile.exactAnchorOffsetMeters(),
                        profile.orientationSupport()));
            }
        }
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        assertThrows(ProbabilisticContinuationProposals.ResourceLimitException.class,
                () -> ProbabilisticContinuationProposals.build(input, CancellationProbe.NONE, owner, 100));
        assertEquals(0L, ledger.currentBytes(), "a refused run cannot retain a partial proposal array");
        owner.close();
    }

    private static void assertNone(double[] offsets) {
        for (double offset : offsets) assertTrue(Double.isNaN(offset));
    }

    private static boolean directlyOwned(ProbabilisticProfile profile, InferenceProfile evaluated,
            int state) throws ReflectiveOperationException {
        var predicate = ProbabilisticTraceEngine.class.getDeclaredMethod("directlyOwnedByMeasuredMode",
                ProbabilisticProfile.class, InferenceProfile.class, int.class);
        predicate.setAccessible(true);
        return (boolean) predicate.invoke(null, profile, evaluated, state);
    }

    private static List<ProbabilisticProfile> series(boolean fixedInterior, int branches) {
        var profiles = new ArrayList<ProbabilisticProfile>();
        double bearing = Math.atan(0.1);
        var orientation = new ImageOrientationSupport(ImageOrientationSupport.Status.MEASURED_TWO_SIDED,
                List.of(new ImageOrientationSupport.AngularMode(bearing, bearing, bearing, 1.0)), 1.0);
        for (int i = 0; i <= 10; i++) {
            var modes = new ArrayList<ProbabilisticProfile.Mode>();
            if (i <= 2 || i >= 8) {
                for (int branch = 0; branch < branches; branch++) {
                    double offset = 0.2 * (i - 1) + branch * 3.0;
                    modes.add(new ProbabilisticProfile.Mode("b" + branch, "l" + branch,
                            offset - 0.1, offset + 0.1, 0.2, 1.0, 1.0, List.of(offset), List.of(),
                            false, orientation, 0.8, 1.0, 0.0,
                            i == 2 ? 4.0 : 0.0, i == 8 ? 4.0 : 0.0));
                }
            }
            profiles.add(new ProbabilisticProfile(i, i * 2.0, new MetricPoint(i * 2.0, 0),
                    new MetricPoint(0, 1), -8, 8, 1, true, 0.02,
                    List.of(new ProbabilisticProfile.Sample(-8, 0.02, true),
                            new ProbabilisticProfile.Sample(8, 0.02, true)),
                    modes, List.of(), fixedInterior && i == 5 ? OptionalDouble.of(0)
                            : OptionalDouble.empty(), orientation));
        }
        return profiles;
    }

    private static List<ProbabilisticProfile> stretchedSeries() {
        var result = new ArrayList<ProbabilisticProfile>();
        for (var old : series(false, 1)) {
            double x = old.profileIndex() * 10.0;
            result.add(new ProbabilisticProfile(old.profileIndex(), x, new MetricPoint(x, 0),
                    old.normalUnit(), -8, 8, 1, true, old.noiseFloor(), old.samples(), old.modes(),
                    old.censoredModes(), old.exactAnchorOffsetMeters(), old.orientationSupport()));
        }
        return result;
    }

    private static ProbabilisticProfile withModes(ProbabilisticProfile source,
            List<ProbabilisticProfile.Mode> modes) {
        return new ProbabilisticProfile(source.profileIndex(), source.chainageMeters(), source.anchor(),
                source.normalUnit(), source.minimumOffsetMeters(), source.maximumOffsetMeters(),
                source.sourcePitchMeters(), source.nativePitchKnown(), source.noiseFloor(), source.samples(),
                modes, source.censoredModes(), source.exactAnchorOffsetMeters(), source.orientationSupport());
    }

    private static List<ProbabilisticProfile> transformed(List<ProbabilisticProfile> input,
            double angle, boolean reversed) {
        var result = new ArrayList<ProbabilisticProfile>();
        double cosine = Math.cos(angle);
        double sine = Math.sin(angle);
        double sign = reversed ? -1 : 1;
        for (int i = 0; i < input.size(); i++) {
            var original = input.get(reversed ? input.size() - 1 - i : i);
            var modes = new ArrayList<ProbabilisticProfile.Mode>();
            for (var old : original.modes()) {
                double bearing = ImageOrientationSupport.normalize(Math.atan(0.1) + angle);
                var support = new ImageOrientationSupport(ImageOrientationSupport.Status.MEASURED_TWO_SIDED,
                        List.of(new ImageOrientationSupport.AngularMode(bearing, bearing, bearing, 1)), 1);
                modes.add(new ProbabilisticProfile.Mode(old.id(), old.evidenceLineage(),
                        sign * (reversed ? old.coreMaximumMeters() : old.coreMinimumMeters()),
                        sign * (reversed ? old.coreMinimumMeters() : old.coreMaximumMeters()),
                        0.2, 1, 1, List.of(sign * old.coreCenterMeters()), List.of(), false,
                        support, old.scalarAmplitudeReliability(), old.branchCoherence(), old.positionalReliability(),
                        reversed ? old.rightDirectSpanMeters() : old.leftDirectSpanMeters(),
                        reversed ? old.leftDirectSpanMeters() : old.rightDirectSpanMeters()));
            }
            double x = original.anchor().xMeters();
            result.add(new ProbabilisticProfile(i, i * 2.0, new MetricPoint(cosine * x, sine * x),
                    new MetricPoint(-sign * sine, sign * cosine), -8, 8, 1, true, 0.02,
                    original.samples(), modes, List.of(), OptionalDouble.empty(), original.orientationSupport()));
        }
        return result;
    }
}
