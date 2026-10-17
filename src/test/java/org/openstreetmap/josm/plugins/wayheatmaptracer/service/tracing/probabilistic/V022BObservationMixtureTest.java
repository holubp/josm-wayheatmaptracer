package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Independent analytic contracts for the one B-observation mixture reducer. */
class V022BObservationMixtureTest {
    @Test
    void unequalWidthAnalyticMixtureUsesMassDensityAndConditionsAnchorUnary() {
        double[] widths = {0.1, 0.3};
        var lattice = lattice(widths, new boolean[] {false, true}, List.of(
                new ObservationComponent("q0", "missing:q0", ObservationComponent.Kind.MISSING, 0.35, false),
                new ObservationComponent("ridge", "image:ridge-a", ObservationComponent.Kind.MEASURED, 0.65, false)));
        var mixture = BObservationMixture.create(List.of(
                component("q0", "missing:q0", ObservationComponent.Kind.MISSING, 0.2,
                        uniformLogs(widths)),
                component("ridge", "image:ridge-a", ObservationComponent.Kind.MEASURED, 0.8,
                        new double[] {StrictMath.log(7.5), StrictMath.log(5.0 / 6.0)})), null);

        var reduced = mixture.reduce(lattice, CancellationProbe.NONE, null);
        assertEquals(2, reduced.stateCount());
        assertEquals(2, reduced.componentCount());
        assertEquals(-StrictMath.log(6.5), reduced.unaryCost(0), 1e-12,
                "q0*U + qm*fm = 0.2*2.5 + 0.8*7.5 = 6.5");
        assertEquals(StrictMath.log(6.5), reduced.logMixtureDensity(0), 1e-12);
        assertEquals(0.0, reduced.unaryCost(1), 0.0, "an exact anchor is conditioned to unary zero");
        assertEquals(StrictMath.log(7.0 / 6.0), reduced.logMixtureDensity(1), 1e-12,
                "the anchor still exposes the actual unconditioned mixture log density");
        assertEquals(1.0 / 13.0, reduced.responsibility(0, 0), 1e-12);
        assertEquals(12.0 / 13.0, reduced.responsibility(0, 1), 1e-12);
        assertEquals(3.0 / 7.0, reduced.responsibility(1, 0), 1e-12);
        assertEquals(4.0 / 7.0, reduced.responsibility(1, 1), 1e-12);
        assertTrue(reduced.unaryCost(0) < 0.0, "negative unaries are valid log evidence");
        assertEquals(widths[1], lattice.cells().get(1).quadratureWidthMeters(), 0.0,
                "the reducer leaves original FB integration widths intact");
    }

    @Test
    void zeroSupportMeasuredRowHasZeroMassAndFullDomainMissingUniform() {
        double[] widths = {0.25, 0.75};
        var normalized = BObservationMixture.normalizeMeasured(new double[] {0.0, 0.0}, widths,
                CancellationProbe.NONE, null);
        assertFalse(normalized.hasSupport());
        assertEquals(Double.NEGATIVE_INFINITY, normalized.logL0DensityAt(0));
        assertEquals(Double.NEGATIVE_INFINITY, normalized.logL0DensityAt(1));
        var lattice = lattice(widths, new boolean[] {false, false}, List.of(
                new ObservationComponent("q0", "missing:q0", ObservationComponent.Kind.MISSING, 0.1, false),
                new ObservationComponent("empty", "image:empty", ObservationComponent.Kind.MEASURED, 0.9, false)));
        var mixture = BObservationMixture.create(List.of(
                component("q0", "missing:q0", ObservationComponent.Kind.MISSING, 1.0, uniformLogs(widths)),
                component("empty", "image:empty", ObservationComponent.Kind.MEASURED, 0.0,
                        new double[] {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY})), null);
        var reduced = mixture.reduce(lattice, CancellationProbe.NONE, null);
        assertEquals(0.0, reduced.unaryCost(0), 1e-12,
                "full-domain U=1/m for the complete 1m lattice, including unsupported cells");
        assertEquals(0.0, reduced.unaryCost(1), 1e-12);
        assertEquals(1.0, reduced.responsibility(0, 0), 0.0);
        assertEquals(0.0, reduced.responsibility(0, 1), 0.0);
    }

    @Test
    void componentOutcomeIsTypedAndUnavailableEvidenceRequiresExplicitZeroSupport() {
        var defaultMissing = component("q0", "missing:q0", ObservationComponent.Kind.MISSING,
                1.0, new double[] {0.0});
        var defaultCensored = component("censored", "censored:edge", ObservationComponent.Kind.CENSORED,
                0.0, new double[] {Double.NEGATIVE_INFINITY});
        var defaultMeasured = component("ridge", "image:ridge", ObservationComponent.Kind.MEASURED,
                1.0, new double[] {0.0});
        var defaultZero = component("default-zero", "image:default-zero", ObservationComponent.Kind.MEASURED,
                0.0, new double[] {Double.NEGATIVE_INFINITY});
        assertEquals(BObservationMixture.Outcome.BASELINE_MISSING, defaultMissing.outcome());
        assertEquals(BObservationMixture.Outcome.BASELINE_CENSORED, defaultCensored.outcome());
        assertEquals(BObservationMixture.Outcome.MEASURED, defaultMeasured.outcome());
        assertEquals(BObservationMixture.Outcome.OBSERVED_ZERO_EXCESS, defaultZero.outcome());

        var noReceipt = explicitComponent("no-receipt", ObservationComponent.Kind.MEASURED, 0.0,
                new double[] {Double.NEGATIVE_INFINITY}, BObservationMixture.Outcome.UNAVAILABLE_ORIGINAL_RECEIPT);
        assertEquals(BObservationMixture.Outcome.UNAVAILABLE_ORIGINAL_RECEIPT, noReceipt.outcome());
        var noTent = explicitComponent("no-tent", ObservationComponent.Kind.MEASURED, 0.0,
                new double[] {Double.NEGATIVE_INFINITY}, BObservationMixture.Outcome.NO_ADMITTED_TENT_SUPPORT);
        assertEquals(BObservationMixture.Outcome.NO_ADMITTED_TENT_SUPPORT, noTent.outcome());
        var zeroExcess = explicitComponent("zero-excess", ObservationComponent.Kind.MEASURED, 0.0,
                new double[] {Double.NEGATIVE_INFINITY}, BObservationMixture.Outcome.OBSERVED_ZERO_EXCESS);
        assertEquals(BObservationMixture.Outcome.OBSERVED_ZERO_EXCESS, zeroExcess.outcome());

        assertThrows(IllegalArgumentException.class, () -> explicitComponent("available-mass",
                ObservationComponent.Kind.MEASURED, 0.25, new double[] {0.0},
                BObservationMixture.Outcome.UNAVAILABLE_SOURCE_PITCH));
        assertThrows(IllegalArgumentException.class, () -> explicitComponent("unavailable-with-density",
                ObservationComponent.Kind.MEASURED, 0.0, new double[] {0.0},
                BObservationMixture.Outcome.GROUPED_PARENT_UNAVAILABLE));
        assertThrows(IllegalArgumentException.class, () -> explicitComponent("wrong-kind",
                ObservationComponent.Kind.MISSING, 0.0, new double[] {Double.NEGATIVE_INFINITY},
                BObservationMixture.Outcome.MEASURED));
        assertThrows(IllegalArgumentException.class, () -> explicitComponent("untyped-zero",
                ObservationComponent.Kind.MEASURED, 0.0, new double[] {Double.NEGATIVE_INFINITY},
                BObservationMixture.Outcome.MEASURED));
    }

    @Test
    void censoredComponentCannotUseAnEmptyZeroMassDensityRow() {
        double[] widths = {0.5, 0.5};
        var lattice = lattice(widths, new boolean[] {false, false}, List.of(
                new ObservationComponent("q0", "missing:q0", ObservationComponent.Kind.MISSING, 0.5, false),
                new ObservationComponent("edge", "censored:edge", ObservationComponent.Kind.CENSORED, 0.5, false)));
        var mixture = BObservationMixture.create(List.of(
                component("q0", "missing:q0", ObservationComponent.Kind.MISSING, 1.0, uniformLogs(widths)),
                component("edge", "censored:edge", ObservationComponent.Kind.CENSORED, 0.0,
                        new double[] {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY})), null);
        assertThrows(IllegalArgumentException.class,
                () -> mixture.reduce(lattice, CancellationProbe.NONE, null));
    }

    @Test
    void zeroAndTinyProbabilityCellsUseFiniteUnaryFloorWithoutChangingResponsibilities() {
        double[] widths = {0.25, 0.25, 0.5};
        double tiny = 1e-310;
        var lattice = lattice(widths, new boolean[] {false, false, false}, List.of(
                new ObservationComponent("q0", "missing:q0", ObservationComponent.Kind.MISSING, 0.4, false),
                new ObservationComponent("tiny-a", "image:tiny-a", ObservationComponent.Kind.MEASURED, 0.3, false),
                new ObservationComponent("tiny-b", "image:tiny-b", ObservationComponent.Kind.MEASURED, 0.3, false)));
        var mixture = BObservationMixture.create(List.of(
                component("q0", "missing:q0", ObservationComponent.Kind.MISSING, 0.0,
                        uniformLogs(widths)),
                component("tiny-a", "image:tiny-a", ObservationComponent.Kind.MEASURED, 0.5,
                        new double[] {StrictMath.log(tiny), Double.NEGATIVE_INFINITY, StrictMath.log(2.0)}),
                component("tiny-b", "image:tiny-b", ObservationComponent.Kind.MEASURED, 0.5,
                        new double[] {StrictMath.log(2.0 * tiny), Double.NEGATIVE_INFINITY, StrictMath.log(2.0)})), null);

        var reduced = mixture.reduce(lattice, CancellationProbe.NONE, null);
        double floorCost = -StrictMath.log(1e-300);
        assertEquals(floorCost, reduced.unaryCost(0), 1e-12,
                "positive but sub-floor density gets the established finite cost");
        assertEquals(StrictMath.log(1.5e-310), reduced.logMixtureDensity(0), 1e-12,
                "the retained likelihood remains the true tiny density, below the unary floor");
        assertEquals(floorCost, reduced.unaryCost(1), 1e-12,
                "a zero-probability cell gets the same finite floor");
        assertEquals(Double.NEGATIVE_INFINITY, reduced.logMixtureDensity(1),
                "the true likelihood remains zero even though the unary is floored");
        assertEquals(1.0 / 3.0, reduced.responsibility(0, 1), 1e-12,
                "the unary floor must not replace the actual tiny component density");
        assertEquals(2.0 / 3.0, reduced.responsibility(0, 2), 1e-12);
        assertEquals(0.0, reduced.responsibility(1, 0), 0.0);
        assertEquals(0.0, reduced.responsibility(1, 1), 0.0);
        assertEquals(0.0, reduced.responsibility(1, 2), 0.0);
    }

    @Test
    void invalidNormalizationDuplicateLineageAndMalformedMassesFailClosed() {
        double[] widths = {0.1, 0.3};
        var q0 = component("q0", "missing:q0", ObservationComponent.Kind.MISSING,
                0.2, uniformLogs(widths));
        var measured = component("ridge", "image:ridge-a", ObservationComponent.Kind.MEASURED,
                0.8, new double[] {StrictMath.log(7.5), StrictMath.log(5.0 / 6.0)});
        assertThrows(IllegalArgumentException.class, () -> BObservationMixture.create(List.of(q0,
                component("alias", "missing:q0", ObservationComponent.Kind.MEASURED,
                        0.0, new double[] {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY})), null));
        assertThrows(IllegalArgumentException.class, () -> component("positive-zero", "image:zero",
                ObservationComponent.Kind.MEASURED, 0.1,
                new double[] {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY}));
        assertThrows(IllegalArgumentException.class, () -> BObservationMixture.normalizeMeasured(
                new double[] {0.0, 1.0}, new double[] {0.1, Double.NaN}, CancellationProbe.NONE, null));

        var lattice = lattice(widths, new boolean[] {false, false}, List.of(
                new ObservationComponent("q0", "missing:q0", ObservationComponent.Kind.MISSING, 0.5, false),
                new ObservationComponent("ridge", "image:ridge-a", ObservationComponent.Kind.MEASURED, 0.5, false)));
        assertThrows(IllegalArgumentException.class, () -> BObservationMixture.create(List.of(q0,
                component("ridge", "image:ridge-a", ObservationComponent.Kind.MEASURED,
                        0.7, measuredLogDensity())), null).reduce(lattice, CancellationProbe.NONE, null));
        assertThrows(IllegalArgumentException.class, () -> BObservationMixture.create(List.of(
                component("q0", "missing:q0", ObservationComponent.Kind.MISSING, 0.2,
                        new double[] {StrictMath.log(1.0), StrictMath.log(4.0)}),
                component("ridge", "image:ridge-a", ObservationComponent.Kind.MEASURED,
                        0.8, new double[] {StrictMath.log(7.5), StrictMath.log(5.0 / 6.0)})), null)
                .reduce(lattice, CancellationProbe.NONE, null));
    }

    @Test
    void componentAndMixtureFactoriesDetachCallerArraysAndLists() {
        double[] widths = {0.1, 0.3};
        double[] missingLogs = uniformLogs(widths);
        double[] measuredLogs = measuredLogDensity();
        var q0 = component("q0", "missing:q0", ObservationComponent.Kind.MISSING, 0.2, missingLogs);
        var measured = component("ridge", "image:ridge-a", ObservationComponent.Kind.MEASURED, 0.8, measuredLogs);
        missingLogs[0] = 100.0;
        measuredLogs[0] = Double.NEGATIVE_INFINITY;
        List<BObservationMixture.Component> inputRows = new ArrayList<>(List.of(q0, measured));
        var mixture = BObservationMixture.create(inputRows, null);
        inputRows.clear();
        assertEquals(StrictMath.log(2.5), q0.logL0DensityAt(0), 1e-12);
        assertEquals(StrictMath.log(7.5), measured.logL0DensityAt(0), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> component("nan", "image:nan",
                ObservationComponent.Kind.MEASURED, 0.0, new double[] {Double.NaN}));
        var reduced = mixture.reduce(lattice(widths, new boolean[] {false, false}, List.of(
                new ObservationComponent("q0", "missing:q0", ObservationComponent.Kind.MISSING, 0.3, false),
                new ObservationComponent("ridge", "image:ridge-a", ObservationComponent.Kind.MEASURED, 0.7, false))),
                CancellationProbe.NONE, null);
        assertEquals(2, reduced.componentCount());
    }

    @Test
    void lateCancellationAndMemoryRefusalReleaseEveryReducerAllocation() {
        double[] widths = {0.1, 0.3};
        var lattice = lattice(widths, new boolean[] {false, false}, List.of(
                new ObservationComponent("q0", "missing:q0", ObservationComponent.Kind.MISSING, 0.3, false),
                new ObservationComponent("ridge", "image:ridge-a", ObservationComponent.Kind.MEASURED, 0.7, false)));
        var mixture = BObservationMixture.create(List.of(
                component("q0", "missing:q0", ObservationComponent.Kind.MISSING, 0.2, uniformLogs(widths)),
                component("ridge", "image:ridge-a", ObservationComponent.Kind.MEASURED,
                        0.8, measuredLogDensity())), null);

        var successfulLedger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var successfulOwner = successfulLedger.rootOwner();
        AtomicInteger calls = new AtomicInteger();
        mixture.reduce(lattice, () -> { calls.incrementAndGet(); return false; }, successfulOwner);
        int checkpoints = calls.get();
        long peak = successfulLedger.peakBytes();
        assertTrue(checkpoints > 0);
        assertTrue(peak > 0);
        successfulOwner.close();
        assertEquals(0, successfulLedger.currentBytes());

        var cancelledLedger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var cancelledOwner = cancelledLedger.rootOwner();
        AtomicInteger cancelledCalls = new AtomicInteger();
        assertThrows(CancellationException.class, () -> mixture.reduce(lattice,
                () -> cancelledCalls.incrementAndGet() >= checkpoints, cancelledOwner));
        assertEquals(0, cancelledLedger.currentBytes(), "late cancellation rolls back the child transaction");
        cancelledOwner.close();

        var refusedLedger = new AttemptMemoryLedger(peak - 1);
        var refusedOwner = refusedLedger.rootOwner();
        assertThrows(RuntimeException.class, () -> mixture.reduce(lattice, CancellationProbe.NONE, refusedOwner));
        assertEquals(0, refusedLedger.currentBytes(), "budget refusal leaves no caller-owned partial output");
        refusedOwner.close();
    }

    private static BObservationMixture.Component component(String id, String lineage,
            ObservationComponent.Kind kind, double mass, double[] logDensity) {
        return BObservationMixture.Component.create(id, lineage, kind, mass, logDensity, null);
    }

    private static BObservationMixture.Component explicitComponent(String id,
            ObservationComponent.Kind kind, double mass, double[] logDensity,
            BObservationMixture.Outcome outcome) {
        return BObservationMixture.Component.create(id, "lineage:" + id, kind, mass, logDensity, outcome, null);
    }

    private static ProbabilisticStateLattice lattice(double[] widths, boolean[] anchors,
            List<ObservationComponent> components) {
        List<LateralStateCell> cells = new ArrayList<>();
        double offset = 0.0;
        for (int index = 0; index < widths.length; index++) {
            cells.add(new LateralStateCell(offset, widths[index], anchors[index], false, "cell-" + index));
            offset += widths[index];
        }
        return new ProbabilisticStateLattice(cells, components, widths[0], false);
    }

    private static double[] uniformLogs(double[] widths) {
        double total = 0.0;
        for (double width : widths) total += width;
        double value = StrictMath.log(BObservationMixture.REFERENCE_LENGTH_METERS / total);
        double[] logs = new double[widths.length];
        java.util.Arrays.fill(logs, value);
        return logs;
    }

    private static double[] measuredLogDensity() {
        return new double[] {StrictMath.log(7.5), StrictMath.log(5.0 / 6.0)};
    }
}
