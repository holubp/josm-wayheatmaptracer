package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Public source-mask and independently enumerated coupled-geometry retention oracles. */
class V022SourceFrontierRetentionTest {
    private static final ObservationComponent COMPONENT = new ObservationComponent(
            "source", "synthetic", ObservationComponent.Kind.MEASURED, 1, false);

    @Test void originalFineNeighborhoodIsPresentBeforeTheFirstSolveOnBothSides() {
        var sampled = sampled(0, -2, 2, List.of(mode(0, 0)), false);
        var dense = dense(sampled, grid(-2, 2), false, false);
        var ledger = AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner()) {
            var view = requested(sampled, dense, owner, CancellationProbe.NONE);
            assertEquals(List.of(-2., -1.5, -1., -.5, 0., .5, 1., 1.5, 2.), offsets(view));
            assertEquals(List.of(-.5, 0., .5), fineOffsets(view));
            assertOriginalEvidence(dense, view);
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test void originalMaskDoesNotCascadeAndEverySeparatedModeKeepsItsOwnFrontier() {
        for (var modes : List.of(List.of(mode(0, 0)), List.of(mode(-3, -3), mode(3, 3)))) {
            var sampled = sampled(0, -6, 6, modes, false);
            var coordinates = new ArrayList<>(grid(-6, 6));
            coordinates.add(.3); coordinates.add(-.7); coordinates.sort(Double::compare);
            var dense = dense(sampled, coordinates, false, false);
            var ledger = AttemptMemoryLedger.production();
            try (var owner = ledger.rootOwner()) {
                var historical = AdaptiveSolverView.initial(sampled, dense, List.of(COMPONENT),
                        Double.NaN, CancellationProbe.NONE, owner);
                var view = requested(sampled, dense, owner, CancellationProbe.NONE);
                var originalFine = fineOffsets(historical);
                assertEquals(originalFine, fineOffsets(view), "Frontier retention cannot manufacture fine evidence");
                assertTrue(offsets(view).containsAll(offsets(historical)), "Retention is monotonic");
                for (var cell : dense.cells()) {
                    if (originalFine.stream().anyMatch(seed -> Math.abs(seed - cell.offsetMeters()) <= 1))
                        assertTrue(offsets(view).contains(cell.offsetMeters()), "Missing source frontier " + cell);
                }
                if (modes.size() == 1) assertFalse(offsets(view).contains(2.5),
                        "A new 1.5 m frontier cell must not seed another expansion");
                assertOriginalEvidence(dense, view);
            }
            assertEquals(0, ledger.currentBytes());
        }
    }

    @Test void missingRowsInventNoFineSeedAndSingletonAnchorsRemainExact() {
        for (boolean anchor : new boolean[] {false, true}) {
            var sampled = sampled(0, -6, 6, List.of(), anchor);
            var dense = dense(sampled, anchor ? List.of(0.) : grid(-6, 6), anchor, true);
            var ledger = AttemptMemoryLedger.production();
            try (var owner = ledger.rootOwner()) {
                var historical = AdaptiveSolverView.initial(sampled, dense, List.of(COMPONENT),
                        Double.NaN, CancellationProbe.NONE, owner);
                var view = requested(sampled, dense, owner, CancellationProbe.NONE);
                assertEquals(offsets(historical), offsets(view));
                assertEquals(anchor ? List.of(0.) : List.of(), fineOffsets(view));
                assertEquals(dense.ownership(), view.profile().ownership());
                assertOriginalEvidence(dense, view);
            }
            assertEquals(0, ledger.currentBytes());
        }
    }

    @Test void phaseShiftedBroadWeakCensoredAndInterpolatedRowsPreserveSourceOwnership() {
        var cases = List.of(List.of(mode(.23, .27)), List.of(mode(-2.25, 2.75)),
                List.of(new ProbabilisticProfile.Mode("weak", "synthetic", -3.75, -3.65,
                        .5, .02, .02, List.of(-3.7), List.of(-3.7), false)), List.<ProbabilisticProfile.Mode>of(),
                List.<ProbabilisticProfile.Mode>of());
        var ownership = List.of(ObservationOwnership.DIRECT_TWO_SIDED, ObservationOwnership.DIRECT_AMBIGUOUS,
                ObservationOwnership.SHOULDER_CENSORED, ObservationOwnership.CORE_CENSORED, ObservationOwnership.INFERRED_GAP);
        for (int scenario = 0; scenario < cases.size(); scenario++) {
            var base = sampled(0, -6, 6, cases.get(scenario), false);
            var censored = scenario == 2 || scenario == 3 ? List.of(new ProbabilisticProfile.CensoredMode(
                    "clipped", "synthetic", ProbabilisticProfile.CensorSide.RIGHT, 6, .8, true))
                    : List.<ProbabilisticProfile.CensoredMode>of();
            var p = new ProbabilisticProfile(0, 0, base.anchor(), base.normalUnit(), -6, 6, 1, true,
                    base.noiseFloor(), base.samples(), base.modes(), censored, OptionalDouble.empty(), base.orientationSupport());
            var cells = grid(-6, 6).stream().map(v -> new LateralStateCell(v, .5, false, false,
                    v < 0 ? "left" : "right")).toList();
            var d = new InferenceProfile(0, p.anchor(), p.normalUnit(), cells, new double[cells.size()], List.of(),
                    0, ownership.get(scenario), scenario == 4);
            var ledger = AttemptMemoryLedger.production();
            try (var owner = ledger.rootOwner()) {
                var historical = AdaptiveSolverView.initial(p, d, List.of(COMPONENT), Double.NaN, CancellationProbe.NONE, owner);
                var originalFine = fineOffsets(historical);
                var actual = requested(p, d, owner, CancellationProbe.NONE);
                assertEquals(originalFine, fineOffsets(actual));
                assertTrue(offsets(actual).containsAll(offsets(historical)));
                for (var cell : d.cells()) if (originalFine.stream().anyMatch(seed -> Math.abs(seed - cell.offsetMeters()) <= 1))
                    assertTrue(offsets(actual).contains(cell.offsetMeters()));
                if (cases.get(scenario).isEmpty()) assertTrue(originalFine.isEmpty(), "Censoring/interpolation supplies no fine seed");
                assertOriginalEvidence(d, actual);
                assertEquals(d.entirelyMissing(), actual.profile().entirelyMissing());
            }
            assertEquals(0, ledger.currentBytes());
        }
    }

    @Test void turnCompatibleDenseOptimumSurvivesTheConditionalRestriction() {
        for (int mirror : new int[] {-1, 1}) for (boolean reverse : new boolean[] {false, true}) {
            var sampled = new ArrayList<ProbabilisticProfile>();
            var dense = new ArrayList<InferenceProfile>();
            var lattices = new ArrayList<ProbabilisticStateLattice>();
            for (int row = 0; row < 19; row++) {
                boolean fixed = row % 4 != 3;
                var base = sampled(row, -2, 2, fixed ? List.of() : List.of(mode(0, 0)), fixed);
                var p = new ProbabilisticProfile(row, row, new MetricPoint(reverse ? 18 - row : row, 0),
                        new MetricPoint(0, 1), -2, 2, 1, true, base.noiseFloor(), base.samples(),
                        base.modes(), base.censoredModes(), fixed ? OptionalDouble.of(mirror) : OptionalDouble.empty(),
                        base.orientationSupport());
                var offsets = fixed ? List.of((double) mirror) : grid(-2, 2);
                var cells = offsets.stream().map(v -> new LateralStateCell(v, .5, fixed, fixed, "source")).toList();
                double[] unary = offsets.stream().mapToDouble(v -> Math.abs(v) == 2 ? 1e6 : 0).toArray();
                sampled.add(p);
                dense.add(new InferenceProfile(row, new MetricPoint(reverse ? 18 - row : row, 0), new MetricPoint(0, 1),
                        cells, unary, List.of(), 0, ObservationOwnership.DIRECT_TWO_SIDED, false));
                lattices.add(new ProbabilisticStateLattice(cells, List.of(COMPONENT), .5, false));
            }
            // Reverse physical traversal while keeping factual cumulative chainage increasing.
            var parameters = EvidenceModelParameters.defaults();
            var oracle = V022ProbabilisticInferenceTest.bruteForce(dense, parameters);
            var expected = oracle.paths().get(0);
            assertTrue(expected.energy() < oracle.paths().get(1).energy(), "Dense optimum must be unique");
            for (int row = 0; row < dense.size(); row++)
                assertEquals(mirror, dense.get(row).cells().get(expected.states()[row]).offsetMeters());
            var ledger = AttemptMemoryLedger.production();
            try (var owner = ledger.rootOwner()) {
                var historical = new AdaptiveBInference().solve(sampled, dense, lattices,
                        noContinuation(dense.size()), parameters, TraceBudgets.defaults(), null,
                        CancellationProbe.NONE, owner, AdaptiveBObserver.NONE,
                        ProbabilisticInference.WorkProgress.NONE, BPolicy.ADAPTIVE_MULTIMODAL_V2);
                assertEquals(0, historical.refinements(), "All old top-32/credible choices remain fine");
                assertEquals(32, historical.inference().rawPaths().size());
                assertTrue(historical.inference().rawPaths().stream().allMatch(path -> {
                    for (int row = 3; row < dense.size(); row += 4)
                        if (Math.abs(historical.profiles().get(row).cells().get(path.stateIndexAt(row)).offsetMeters()) > .5)
                            return false;
                    return true;
                }));
                assertTrue(historical.inference().mapPath().orElseThrow().energy() > expected.energy());
                var historicalMap = historical.inference().mapPath().orElseThrow();
                var oldDisplacements = new ArrayList<Double>();
                for (int row = 0; row < dense.size(); row++) {
                    double oldOffset = historical.profiles().get(row).cells().get(historicalMap.stateIndexAt(row)).offsetMeters();
                    assertEquals(row % 4 == 3 ? mirror * .5 : mirror, oldOffset);
                    oldDisplacements.add(Math.abs(oldOffset - mirror));
                }
                oldDisplacements.sort(Double::compare);
                assertTrue(oldDisplacements.get((int) Math.ceil(.95 * oldDisplacements.size()) - 1) > .25,
                        "The original restriction fails the unchanged strong .25P p95 bound at P=1");
                var retained = new HashMap<String, Set<Integer>>();
                var actual = new AdaptiveBInference().solve(sampled, dense, lattices,
                        noContinuation(dense.size()), parameters, TraceBudgets.defaults(), null,
                        CancellationProbe.NONE, owner, (pass, row, index, original) -> retained
                                .computeIfAbsent(pass + ":" + row, ignored -> new HashSet<>()).add(original),
                        ProbabilisticInference.WorkProgress.NONE, requestedPolicy());
                var chosen = actual.inference().mapPath().orElseThrow();
                assertEquals(expected.energy(), chosen.energy(), 1e-12);
                for (int row = 0; row < dense.size(); row++)
                    assertEquals(mirror, actual.profiles().get(row).cells().get(chosen.stateIndexAt(row)).offsetMeters());
                assertTrue(actual.refinements() <= 1);
                assertFalse(actual.inference().posteriorUsable());
                if (actual.refinements() == 1) for (int row = 0; row < dense.size(); row++)
                    assertTrue(retained.get("1:" + row).containsAll(retained.get("0:" + row)),
                            "The sole later refinement must retain the complete initial graph");
                if (mirror == 1 && !reverse) {
                    assertEquals(1, actual.refinements(), "A chosen non-fine frontier retains the existing refinement rule");
                    var capped = new AdaptiveBInference().solve(sampled, dense, lattices,
                            noContinuation(dense.size()), parameters,
                            new TraceBudgets(96, 8_000_000, actual.firstScores() + 1, 32, 8), null,
                            CancellationProbe.NONE, owner, AdaptiveBObserver.NONE,
                            ProbabilisticInference.WorkProgress.NONE, requestedPolicy());
                    assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT, capped.inference().status());
                    assertEquals(actual.firstScores(), capped.firstScores());
                    assertEquals(1, capped.secondScores());
                    assertEquals(actual.firstScores() + 1, capped.inference().evaluatedTransitions());
                    assertTrue(capped.inference().rawPaths().isEmpty());
                    long[] completed = {0};
                    int[] refinementChecks = {0};
                    assertThrows(CancellationException.class, () -> new AdaptiveBInference().solve(sampled, dense, lattices,
                            noContinuation(dense.size()), parameters, TraceBudgets.defaults(), null,
                            () -> StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                                    frame.getClassName().equals(AdaptiveSolverView.class.getName())
                                            && frame.getMethodName().equals("refined"))) && ++refinementChecks[0] == 3,
                            owner, AdaptiveBObserver.NONE, (pairs, scores) -> completed[0] = scores, requestedPolicy()));
                    assertEquals(actual.firstScores(), completed[0], "Refinement cancellation retains completed first-pass work");
                }
            }
            assertEquals(0, ledger.currentBytes());
        }
    }

    @Test void cancellationAndMemoryDenialReleaseRetentionOwners() {
        var p = sampled(0, -6, 6, List.of(mode(0, 0)), false);
        var d = dense(p, grid(-6, 6), false, false);
        var ledger = AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner()) {
            int[] checks = {0};
            assertThrows(CancellationException.class, () -> requested(p, d, owner, () -> ++checks[0] == 8));
        }
        assertEquals(0, ledger.currentBytes());
        var tiny = new AttemptMemoryLedger(1);
        try (var owner = tiny.rootOwner()) {
            assertThrows(ProbabilisticInference.MemoryLimit.class, () -> requested(p, d, owner, CancellationProbe.NONE));
        }
        assertEquals(0, tiny.currentBytes());
    }

    private static BPolicy requestedPolicy() { return BPolicy.INVENTORY_FRONTIER_V5; }
    private static double[] noContinuation(int rows) {
        var values = new double[rows]; Arrays.fill(values, Double.NaN); return values;
    }
    private static AdaptiveSolverView requested(ProbabilisticProfile p, InferenceProfile d,
            AttemptMemoryLedger.Owner owner, CancellationProbe cancellation) {
        return AdaptiveSolverView.initial(p, d, List.of(COMPONENT), Double.NaN, cancellation, owner, requestedPolicy());
    }
    private static List<Double> offsets(AdaptiveSolverView view) {
        return view.profile().cells().stream().map(LateralStateCell::offsetMeters).toList();
    }
    private static List<Double> fineOffsets(AdaptiveSolverView view) {
        var result = new ArrayList<Double>();
        for (int i = 0; i < view.size(); i++) if (view.fine(i)) result.add(view.profile().cells().get(i).offsetMeters());
        return result;
    }
    private static void assertOriginalEvidence(InferenceProfile dense, AdaptiveSolverView view) {
        for (int i = 0; i < view.size(); i++) {
            int original = view.sourceIndexAt(i);
            assertSame(dense.cells().get(original), view.profile().cells().get(i));
            assertEquals(Double.doubleToRawLongBits(dense.unaryCost(original)),
                    Double.doubleToRawLongBits(view.profile().unaryCost(i)));
        }
        assertEquals(dense.ownership(), view.profile().ownership());
    }
    private static List<Double> grid(int minimum, int maximum) {
        return java.util.stream.IntStream.rangeClosed(minimum * 2, maximum * 2).mapToObj(i -> i * .5).toList();
    }
    private static ProbabilisticProfile.Mode mode(double minimum, double maximum) {
        return new ProbabilisticProfile.Mode("mode-" + minimum, "synthetic", minimum, maximum,
                .5, .8, .8, List.of((minimum + maximum) * .5), List.of((minimum + maximum) * .5), false);
    }
    private static ProbabilisticProfile sampled(int row, double minimum, double maximum,
            List<ProbabilisticProfile.Mode> modes, boolean anchor) {
        var samples = grid((int) minimum, (int) maximum).stream().map(v -> new ProbabilisticProfile.Sample(v, .5, true)).toList();
        return new ProbabilisticProfile(row, row, new MetricPoint(row, 0), new MetricPoint(0, 1), minimum, maximum,
                1, true, .01, samples, modes, List.of(), anchor ? OptionalDouble.of(0) : OptionalDouble.empty(),
                ImageOrientationSupport.unknown(ImageOrientationSupport.Status.UNKNOWN_FLAT));
    }
    private static InferenceProfile dense(ProbabilisticProfile p, List<Double> offsets, boolean anchor, boolean missing) {
        var cells = offsets.stream().map(v -> new LateralStateCell(v, .5, anchor, anchor || !missing && v == 0, "source")).toList();
        var unary = offsets.stream().mapToDouble(v -> v * v + .125).toArray();
        return new InferenceProfile(p.chainageMeters(), p.anchor(), p.normalUnit(), cells, unary, List.of(), 0,
                missing ? ObservationOwnership.NO_RASTER : ObservationOwnership.DIRECT_TWO_SIDED, missing);
    }
}
