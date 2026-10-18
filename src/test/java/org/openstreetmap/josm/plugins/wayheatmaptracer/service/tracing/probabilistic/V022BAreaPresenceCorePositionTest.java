package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationFacts.*;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.NestedCenterReceipt;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.ObservationStage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Independent analytic checks for area-derived mass and tent-derived conditional position. */
class V022BAreaPresenceCorePositionTest {
    private static final BIdentifiedObservationKernel.Parameters PARAMETERS =
            new BIdentifiedObservationKernel.Parameters(1, 1, 0);
    private static final BIdentifiedObservationKernel KERNEL = new BIdentifiedObservationKernel(PARAMETERS);

    @Test
    void unequalWidthOracleSeparatesAreaMassFromNormalizedTentPosition() {
        var cells = List.of(cell(0, 1, false), cell(1, 2, false));
        var facts = facts(new double[] {.2, .1}, new boolean[] {true, true}, -1, 1);
        var lattice = lattice(cells, false);
        var mixture = KERNEL.evaluate(facts, lattice, CancellationProbe.NONE, null);
        var reduced = mixture.reduce(lattice, CancellationProbe.NONE, null);

        // E=.2*1+.1*2=.4, Z=1*1+.5*2=2, f=[1/2,1/4], H=.4.
        assertEquals(2.0 / 7.0, mixture.component(1).mass(), 1e-12);
        assertEquals(5.0 / 7.0, mixture.component(0).mass(), 1e-12);
        assertEquals(StrictMath.log(.5), mixture.component(1).logL0DensityAt(0), 1e-12);
        assertEquals(StrictMath.log(.25), mixture.component(1).logL0DensityAt(1), 1e-12);
        assertEquals(8.0 / 21.0, StrictMath.exp(reduced.logMixtureDensity(0)), 1e-12);
        assertEquals(13.0 / 42.0, StrictMath.exp(reduced.logMixtureDensity(1)), 1e-12);
        assertEquals(-StrictMath.log(8.0 / 21.0), reduced.unaryCost(0), 1e-12);
        assertEquals(-StrictMath.log(13.0 / 42.0), reduced.unaryCost(1), 1e-12);

        var anchoredLattice = lattice(cells, true);
        var anchored = KERNEL.evaluate(facts, anchoredLattice, CancellationProbe.NONE, null)
                .reduce(anchoredLattice, CancellationProbe.NONE, null);
        assertEquals(0.0, anchored.unaryCost(1), 0.0);
        assertEquals(reduced.logMixtureDensity(1), anchored.logMixtureDensity(1), 1e-12);
    }

    @Test
    void equalAreaRedistributionKeepsPresenceAndCorePositionUnchanged() {
        var lattice = lattice(List.of(cell(0, 1, false), cell(1, 2, false)), false);
        var first = KERNEL.evaluate(facts(new double[] {.2, .1}, new boolean[] {true, true}, -1, 1),
                lattice, CancellationProbe.NONE, null);
        var redistributed = KERNEL.evaluate(
                facts(new double[] {.1, .15}, new boolean[] {true, true}, -1, 1),
                lattice, CancellationProbe.NONE, null);
        assertEquals(first.component(1).mass(), redistributed.component(1).mass(), 1e-12);
        for (int state = 0; state < 2; state++) {
            assertEquals(first.component(1).logL0DensityAt(state),
                    redistributed.component(1).logL0DensityAt(state), 1e-12);
        }
    }

    @Test
    void vanishingScalarExcessRemovesMeasuredMassContinuously() {
        var lattice = lattice(List.of(cell(0, 1, false), cell(1, 2, false)), false);
        double previousMass = Double.POSITIVE_INFINITY;
        double lastUniformDistance = Double.POSITIVE_INFINITY;
        for (double scale : new double[] {1.0, .1, .01, .001, 0.0}) {
            var mixture = KERNEL.evaluate(
                    facts(new double[] {.2 * scale, .1 * scale}, new boolean[] {true, true}, -1, 1),
                    lattice, CancellationProbe.NONE, null);
            assertTrue(mixture.component(1).mass() <= previousMass + 1e-12);
            previousMass = mixture.component(1).mass();
            var reduced = mixture.reduce(lattice, CancellationProbe.NONE, null);
            lastUniformDistance = Math.abs(reduced.unaryCost(0) - StrictMath.log(3.0));
        }
        assertEquals(0.0, previousMass, 0.0);
        assertEquals(0.0, lastUniformDistance, 1e-12);
    }

    @Test
    void excessOutsideTentButInsideOwnShoulderContributesToAreaMass() {
        var cells = List.of(cell(0, 1, false), cell(3, 2, false));
        var lattice = lattice(cells, false);
        var facts = facts(new double[] {.1, .1}, new boolean[] {true, true}, -4, 4);
        var mixture = KERNEL.evaluate(facts, lattice, CancellationProbe.NONE, null);
        assertEquals(.3 / 1.3, mixture.component(1).mass(), 1e-12);
        assertEquals(0.0, StrictMath.exp(mixture.component(1).logL0DensityAt(1)), 0.0);
        assertEquals(1.0, StrictMath.exp(mixture.component(1).logL0DensityAt(0)), 1e-12);
    }

    @Test
    void validZeroExcessCellRetainsTentDensityAndInvalidCellRemainsUniformOnly() {
        var cells = List.of(cell(0, 1, false), cell(1, 2, false));
        var lattice = lattice(cells, false);
        var validZero = KERNEL.evaluate(facts(new double[] {.2, 0}, new boolean[] {true, true}, -1, 1),
                lattice, CancellationProbe.NONE, null);
        assertTrue(Double.isFinite(validZero.component(1).logL0DensityAt(1)));
        assertEquals(StrictMath.log(.25), validZero.component(1).logL0DensityAt(1), 1e-12);

        var invalid = KERNEL.evaluate(facts(new double[] {.2, .1}, new boolean[] {true, false}, -1, 1),
                lattice, CancellationProbe.NONE, null);
        assertEquals(Double.NEGATIVE_INFINITY, invalid.component(1).logL0DensityAt(1));
        var reduced = invalid.reduce(lattice, CancellationProbe.NONE, null);
        assertEquals(StrictMath.log(invalid.component(0).mass() / 3.0),
                reduced.logMixtureDensity(1), 1e-12);
    }

    @Test
    void invalidRequiredInterpolationNeighborDoesNotBecomeAvailableShoulderSupport() {
        var facts = factsWithSamples(new double[] {-4, -1, 0, 1, 4},
                new double[] {0, 0, .2, .1, 0},
                new boolean[] {true, true, true, false, true}, -1, 1);
        var lattice = lattice(List.of(cell(.5, 1, false)), false);
        var mixture = KERNEL.evaluate(facts, lattice, CancellationProbe.NONE, null);
        assertEquals("UNAVAILABLE_ADMITTED_SHOULDER_SUPPORT", mixture.component(1).outcome().name());
        assertEquals(0.0, mixture.component(1).mass(), 0.0);
        assertEquals(Double.NEGATIVE_INFINITY, mixture.component(1).logL0DensityAt(0));
    }

    @Test
    void zeroTentWithPositiveShoulderAreaAndNoShoulderCellsHaveTypedPrecedence() {
        var noTentCells = List.of(cell(3, 2, false));
        var noTentLattice = lattice(noTentCells, false);
        var positiveArea = KERNEL.evaluate(
                facts(new double[] {.2}, new boolean[] {true}, -4, 4),
                noTentLattice, CancellationProbe.NONE, null);
        var noTentFacts = facts(new double[] {.2}, new boolean[] {true}, -4, 4);
        var noTentCalculation = BIdentifiedObservationKernel.calculateMode(new double[noTentCells.size()],
                widths(noTentLattice), noTentFacts.modes().get(0), noTentFacts.baseline(), noTentLattice,
                PARAMETERS, CancellationProbe.NONE, null);
        assertEquals("NO_ADMITTED_TENT_SUPPORT", positiveArea.component(1).outcome().name());
        assertEquals(0.0, positiveArea.component(1).mass(), 0.0);
        assertEquals(.4, noTentCalculation.excessArea(), 1e-12);
        assertEquals(0.0, noTentCalculation.tentNormalizer(), 0.0);
        assertEquals(1, noTentCalculation.availableShoulderCells());
        assertEquals("NO_ADMITTED_TENT_SUPPORT", noTentCalculation.outcome().name());

        var noShoulderCells = List.of(cell(2, 1, false));
        var noShoulder = KERNEL.evaluate(
                facts(new double[] {.2}, new boolean[] {true}, -1, 1),
                lattice(noShoulderCells, false), CancellationProbe.NONE, null);
        assertEquals("UNAVAILABLE_ADMITTED_SHOULDER_SUPPORT", noShoulder.component(1).outcome().name());
        assertEquals(0.0, noShoulder.component(1).mass(), 0.0);

        var entirelyInvalid = KERNEL.evaluate(
                facts(new double[] {.2, .1}, new boolean[] {false, false}, -1, 1),
                lattice(List.of(cell(0, 1, false), cell(1, 1, false)), false),
                CancellationProbe.NONE, null);
        assertEquals("UNAVAILABLE_ADMITTED_SHOULDER_SUPPORT",
                entirelyInvalid.component(1).outcome().name());
        assertEquals(0.0, entirelyInvalid.component(1).mass(), 0.0);
    }

    @Test
    void tinyPositiveShoulderAreaCannotDisappearIntoTentProductUnderflow() {
        double nearTentEdge = Math.nextDown(2.0);
        double positiveExcess = 1e-310;
        var facts = factsWithSamples(
                new double[] {-4, -1, 0, 1, nearTentEdge, 4},
                new double[] {0, 0, 0, 0, positiveExcess, 0},
                new boolean[] {true, true, true, true, true, true}, -4, 4);
        var lattice = lattice(List.of(cell(0, 1, false), cell(nearTentEdge, 1, false)), false);
        var mixture = KERNEL.evaluate(facts, lattice, CancellationProbe.NONE, null);
        assertEquals("MEASURED", mixture.component(1).outcome().name());
        assertTrue(mixture.component(1).mass() > 0);
        assertTrue(Double.isFinite(mixture.component(1).logL0DensityAt(1)));
    }

    @Test
    void zeroExcessAndFixedBaselineCensoredMassKeepTheirOwnOutcomesAndRows() {
        var cells = List.of(cell(-1, 1, false), cell(0, 2, false), cell(1, 1, false));
        var facts = facts(new double[] {0, 0, 0}, new boolean[] {true, true, true}, -1, 1);
        var withCensored = latticeWithCensored(cells, true);
        var mixture = KERNEL.evaluate(facts, withCensored, CancellationProbe.NONE, null);
        assertEquals("OBSERVED_ZERO_EXCESS", mixture.component(2).outcome().name());
        assertEquals(0.0, mixture.component(2).mass(), 0.0);
        assertEquals("BASELINE_CENSORED", mixture.component(0).outcome().name());
        assertEquals(.1, mixture.component(0).mass(), 1e-12);
        double[] baseline = new double[cells.size()];
        ProbabilisticObservationModel.fillBaselineCensoredRow(
                baseline, facts.baseline(), cells, withCensored.components().get(0), CancellationProbe.NONE);
        for (int cell = 0; cell < cells.size(); cell++) {
            assertEquals(baseline[cell], mixture.component(0).logL0DensityAt(cell), 1e-12);
        }
        var reduced = mixture.reduce(withCensored, CancellationProbe.NONE, null);
        var unanchoredLattice = latticeWithCensored(cells, false);
        var unanchored = KERNEL.evaluate(facts, unanchoredLattice, CancellationProbe.NONE, null)
                .reduce(unanchoredLattice, CancellationProbe.NONE, null);
        assertEquals(0.0, reduced.unaryCost(1), 0.0);
        assertEquals(unanchored.logMixtureDensity(1), reduced.logMixtureDensity(1), 1e-12);
    }

    @Test
    void matchedDuplicateReceiptsAndEquivalentSourceResamplingDoNotAddEvidence() {
        var cells = List.of(cell(0, 1, false), cell(1, 2, false));
        var lattice = lattice(cells, false);
        var coarse = facts(new double[] {.2, .1}, new boolean[] {true, true}, -1, 1);
        var coarseMixture = KERNEL.evaluate(coarse, lattice, CancellationProbe.NONE, null);

        var mode = coarse.modes().get(0);
        var aliased = new ProfileFacts(coarse.baseline(), List.of(mode, mode), VERSION);
        var aliasMixture = KERNEL.evaluate(aliased, lattice, CancellationProbe.NONE, null);
        assertEquals(coarseMixture.component(1).mass(), aliasMixture.component(1).mass(), 1e-12);
        assertEquals(coarseMixture.component(1).logL0DensityAt(1),
                aliasMixture.component(1).logL0DensityAt(1), 1e-12);
        var inconsistentValues = new ArrayList<>(mode.values());
        put(inconsistentValues, Feature.BACKGROUND, .05);
        var inconsistentMode = new ModeFacts("m", inconsistentValues, mode.provenance());
        var inconsistentAliases = new ProfileFacts(
                coarse.baseline(), List.of(mode, inconsistentMode), VERSION);
        assertThrows(IllegalArgumentException.class,
                () -> KERNEL.evaluate(inconsistentAliases, lattice, CancellationProbe.NONE, null));

        var resampled = factsOnGrid(.5, new double[] {.2, .1}, new boolean[] {true, true}, -1, 1);
        var resampledMixture = KERNEL.evaluate(resampled, lattice, CancellationProbe.NONE, null);
        assertEquals(coarseMixture.component(1).mass(), resampledMixture.component(1).mass(), 1e-12);
        for (int state = 0; state < 2; state++) {
            assertEquals(coarseMixture.component(1).logL0DensityAt(state),
                    resampledMixture.component(1).logL0DensityAt(state), 1e-12);
        }
    }

    @Test
    void cancellationAndMemoryRefusalLeaveNoPartialMixtureAllocation() {
        var lattice = lattice(List.of(cell(0, 1, false), cell(1, 2, false)), false);
        var facts = facts(new double[] {.2, .1}, new boolean[] {true, true}, -1, 1);
        var ledger = AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner()) {
            var checkpoints = new AtomicInteger();
            assertThrows(CancellationException.class, () -> KERNEL.evaluate(
                    facts, lattice, () -> checkpoints.incrementAndGet() > 5, owner));
            assertTrue(checkpoints.get() > 5);
            assertEquals(0, ledger.currentBytes());
        }
        assertEquals(0, ledger.currentBytes());

        var tiny = new AttemptMemoryLedger(8);
        try (var owner = tiny.rootOwner()) {
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                    () -> KERNEL.evaluate(facts, lattice, CancellationProbe.NONE, owner));
            assertEquals(0, tiny.currentBytes());
        }
        assertEquals(0, tiny.currentBytes());
    }

    @Test
    void sharedModeCalculationRejectsWidthsThatDifferFromItsBoundLattice() {
        var lattice = lattice(List.of(cell(0, 1, false), cell(1, 2, false)), false);
        var facts = facts(new double[] {.2, .1}, new boolean[] {true, true}, -1, 1);
        var wrongWidths = widths(lattice);
        wrongWidths[0] = 99;
        assertThrows(IllegalArgumentException.class, () -> BIdentifiedObservationKernel.calculateMode(
                new double[lattice.cells().size()], wrongWidths, facts.modes().get(0), facts.baseline(),
                lattice, PARAMETERS, CancellationProbe.NONE, null));
    }

    @Test
    void sharedModeCalculationCancellationAndMemoryRefusalReleaseTemporaryOwnership() {
        var cells = List.of(cell(0, 1, false), cell(1, 2, false));
        var lattice = lattice(cells, false);
        var facts = facts(new double[] {.2, .1}, new boolean[] {true, true}, -1, 1);
        var checkpoints = new AtomicInteger();
        var cancellationLedger = AttemptMemoryLedger.production();
        try (var owner = cancellationLedger.rootOwner()) {
            assertThrows(CancellationException.class, () -> BIdentifiedObservationKernel.calculateMode(
                    new double[cells.size()], widths(lattice), facts.modes().get(0), facts.baseline(),
                    lattice, PARAMETERS, () -> checkpoints.incrementAndGet() >= 5, owner));
            assertTrue(checkpoints.get() >= 5);
            assertEquals(0, cancellationLedger.currentBytes());
        }
        assertEquals(0, cancellationLedger.currentBytes());

        var refusalLedger = new AttemptMemoryLedger(127);
        try (var owner = refusalLedger.rootOwner()) {
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                    () -> BIdentifiedObservationKernel.calculateMode(new double[cells.size()], widths(lattice),
                            facts.modes().get(0), facts.baseline(), lattice, PARAMETERS,
                            CancellationProbe.NONE, owner));
            assertEquals(0, refusalLedger.currentBytes());
        }
        assertEquals(0, refusalLedger.currentBytes());
    }

    private static LateralStateCell cell(double offset, double width, boolean anchor) {
        return new LateralStateCell(offset, width, anchor, true, "m");
    }

    private static double[] widths(ProbabilisticStateLattice lattice) {
        return lattice.cells().stream().mapToDouble(LateralStateCell::quadratureWidthMeters).toArray();
    }

    private static ProbabilisticStateLattice lattice(List<LateralStateCell> cells, boolean anchor) {
        List<LateralStateCell> anchored = cells;
        if (anchor && cells.size() > 1) {
            var changed = new ArrayList<>(cells);
            var source = changed.get(1);
            changed.set(1, cell(source.offsetMeters(), source.quadratureWidthMeters(), true));
            anchored = List.copyOf(changed);
        }
        return new ProbabilisticStateLattice(anchored, List.of(
                new ObservationComponent("missing", "missing", ObservationComponent.Kind.MISSING, .5, false),
                new ObservationComponent("m", "m", ObservationComponent.Kind.MEASURED, .5, false)),
                1, false);
    }

    private static ProbabilisticStateLattice latticeWithCensored(List<LateralStateCell> cells, boolean anchor) {
        List<LateralStateCell> adjusted = cells;
        if (anchor && cells.size() > 1) {
            var changed = new ArrayList<>(cells);
            var source = changed.get(1);
            changed.set(1, cell(source.offsetMeters(), source.quadratureWidthMeters(), true));
            adjusted = List.copyOf(changed);
        }
        return new ProbabilisticStateLattice(adjusted, List.of(
                new ObservationComponent("c", "c", ObservationComponent.Kind.CENSORED, .2, false),
                new ObservationComponent("missing", "missing", ObservationComponent.Kind.MISSING, .2, false),
                new ObservationComponent("m", "m", ObservationComponent.Kind.MEASURED, .6, false)),
                1, false);
    }

    private static ProfileFacts facts(double[] cellIntensities, boolean[] cellValidity,
            double shoulderMinimum, double shoulderMaximum) {
        return factsOnGrid(1.0, cellIntensities, cellValidity, shoulderMinimum, shoulderMaximum);
    }

    private static ProfileFacts factsOnGrid(double sampleStep, double[] cellIntensities,
            boolean[] cellValidity, double shoulderMinimum, double shoulderMaximum) {
        int intervals = (int) StrictMath.rint(8.0 / sampleStep);
        double[] offsets = new double[intervals + 1];
        double[] intensities = new double[intervals + 1];
        boolean[] validity = new boolean[intervals + 1];
        for (int index = 0; index <= intervals; index++) {
            double offset = -4 + index * sampleStep;
            double intensity;
            boolean valid = true;
            if (Math.abs(offset) < 1e-12) {
                intensity = cellIntensities[0];
                valid = cellValidity[0];
            } else if (StrictMath.abs(offset - 1.0) < 1e-12) {
                intensity = cellIntensities.length > 1 ? cellIntensities[1] : cellIntensities[0];
                valid = cellValidity.length > 1 ? cellValidity[1] : cellValidity[0];
            } else if (StrictMath.abs(offset - 3.0) < 1e-12) {
                intensity = cellIntensities.length > 1 ? cellIntensities[1] : cellIntensities[0];
                valid = cellValidity.length > 1 ? cellValidity[1] : cellValidity[0];
            } else {
                intensity = 0;
            }
            offsets[index] = offset;
            intensities[index] = intensity;
            validity[index] = valid;
        }
        return factsWithSamples(offsets, intensities, validity, shoulderMinimum, shoulderMaximum);
    }

    private static ProfileFacts factsWithSamples(double[] offsets, double[] intensities,
            boolean[] validity, double shoulderMinimum, double shoulderMaximum) {
        if (offsets.length != intensities.length || offsets.length != validity.length) {
            throw new IllegalArgumentException("Fixture sample arrays must have matching lengths");
        }
        var samples = new ArrayList<ProbabilisticProfile.Sample>();
        for (int index = 0; index < offsets.length; index++) {
            samples.add(new ProbabilisticProfile.Sample(offsets[index], intensities[index], validity[index]));
        }
        int firstCore = sampleIndex(samples, -1);
        int winner = sampleIndex(samples, 0);
        int lastCore = sampleIndex(samples, 1);
        var mode = new ProbabilisticProfile.Mode("m", "m", -1, 1, .5, 1, 1,
                List.of(0.0), List.of(0.0), false);
        var censored = List.of(new ProbabilisticProfile.CensoredMode(
                "c", "c", ProbabilisticProfile.CensorSide.RIGHT, 0, .4,
                false, false, 0, Double.NaN, .5));
        var profile = new ProbabilisticProfile(0, 0, new MetricPoint(0, 0), new MetricPoint(0, 1),
                -4, 4, 2, true, 0, samples, List.of(mode), censored, OptionalDouble.empty(),
                List.of(), 0);
        var values = new ArrayList<ScalarFact>();
        for (var feature : Feature.values()) {
            values.add(new ScalarFact(feature, expectedUnit(feature), Availability.UNAVAILABLE, OptionalDouble.empty()));
        }
        put(values, Feature.BACKGROUND, 0);
        put(values, Feature.RMS_DESCRIPTOR, 0);
        put(values, Feature.FILTER_DESCRIPTOR, 0);
        put(values, Feature.RAW_CENTER, 0);
        put(values, Feature.B3_CENTER, 0);
        put(values, Feature.B5_CENTER, 0);
        put(values, Feature.NATIVE_PITCH, 2);
        put(values, Feature.SHOULDER_MINIMUM, shoulderMinimum);
        put(values, Feature.SHOULDER_MAXIMUM, shoulderMaximum);
        put(values, Feature.LEFT_DIRECT_SPAN, 0);
        put(values, Feature.RIGHT_DIRECT_SPAN, 0);
        var stages = new ArrayList<Receipt>();
        for (var stage : ObservationStage.values()) {
            stages.add(new StageReceipt(new NestedCenterReceipt(stage, true, true,
                    winner, firstCore, lastCore, .2, .1, 0), -1, 1));
        }
        return new ProfileFacts(profile, List.of(new ModeFacts("m", values, stages)), VERSION);
    }

    private static int sampleIndex(List<ProbabilisticProfile.Sample> samples, double offset) {
        for (int index = 0; index < samples.size(); index++) {
            if (samples.get(index).offsetMeters() == offset) return index;
        }
        throw new AssertionError("Fixture sampling grid omitted a receipt endpoint");
    }

    private static void put(List<ScalarFact> facts, Feature feature, double value) {
        facts.set(feature.ordinal(), new ScalarFact(
                feature, expectedUnit(feature), Availability.MEASURED, OptionalDouble.of(value)));
    }
}
