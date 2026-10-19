package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class V022BThinStrandAcceptanceTest {
    private static final double PITCH = 1.0;
    private static final MetricPoint LEFT = new MetricPoint(-8, 37);
    private static final MetricPoint RIGHT = new MetricPoint(52, 37);

    @Test void completeRoutesFollowOneContinuousStrandEvenWhenObservationsBlinkOut() {
        for (String id : List.of("thin-three-strands", "thin-three-strands-intermittent")) {
            var fixture = BThinStrandFixtures.byId(id);
            var result = evaluate(fixture, pathOn(fixture, fixture.strands().get(1)), PITCH, CancellationProbe.NONE, owner());
            assertTrue(result.passed(), id + ": " + result);
            assertEquals(BThinStrandAcceptance.Verdict.PASS, check(result, BThinStrandAcceptance.Reason.WHOLE_PATH_TUBE).verdict());
            assertEquals(LEFT, pathOn(fixture, fixture.strands().get(1)).get(0));
            assertEquals(RIGHT, pathOn(fixture, fixture.strands().get(1)).get(60));
        }
    }

    @Test void everyCompleteEdgeIsSplitAtNativeCenterlineBoundaries() {
        var fixture = BThinStrandFixtures.byId("thin-strong-sharp-bend");
        var chord = List.of(LEFT, RIGHT); // Endpoints pass, but this chord misses the unsampled apex by 3m.
        var result = evaluate(fixture, chord, PITCH, CancellationProbe.NONE, owner());
        assertEquals(BThinStrandAcceptance.Verdict.PASS, check(result, BThinStrandAcceptance.Reason.EXACT_ANCHORS).verdict());
        assertEquals(BThinStrandAcceptance.Verdict.FAIL, check(result, BThinStrandAcceptance.Reason.WHOLE_PATH_TUBE).verdict());
        assertEquals(BThinStrandAcceptance.Verdict.FAIL, check(result, BThinStrandAcceptance.Reason.SHARP_APEX).verdict());
    }

    @Test void anchorsMustBeExactAndThePathMustNotRunBackwards() {
        var fixture = BThinStrandFixtures.byId("thin-three-strands");
        var wrongLeft = List.of(new MetricPoint(-7.999, 37), new MetricPoint(52, 37));
        var backwards = List.of(RIGHT, new MetricPoint(-8, 37));
        var leftResult = evaluate(fixture, wrongLeft, PITCH, CancellationProbe.NONE, owner());
        var backwardResult = evaluate(fixture, backwards, PITCH, CancellationProbe.NONE, owner());
        assertEquals(BThinStrandAcceptance.Verdict.FAIL, check(leftResult, BThinStrandAcceptance.Reason.EXACT_ANCHORS).verdict());
        assertEquals(BThinStrandAcceptance.Verdict.FAIL, check(backwardResult, BThinStrandAcceptance.Reason.NONDECREASING_X).verdict());
        assertFalse(backwardResult.passed());
    }

    @Test void aSeparatedStrandSwitchCannotPassAsOneContinuousRoute() {
        var fixture = BThinStrandFixtures.byId("thin-three-strands");
        var path = new ArrayList<MetricPoint>();
        for (int x = -8; x <= 22; x++) path.add(pointOn(fixture, fixture.strands().get(0), x));
        for (int x = 23; x <= 52; x++) path.add(pointOn(fixture, fixture.strands().get(2), x));
        var result = evaluate(fixture, path, PITCH, CancellationProbe.NONE, owner());
        assertEquals(BThinStrandAcceptance.Verdict.FAIL, check(result, BThinStrandAcceptance.Reason.BRANCH_CONTINUITY).verdict());
        assertFalse(result.passed());
    }

    @Test void strandHandoffIsAllowedOnlyInsideTheDeclaredMerge() {
        var fixture = BThinStrandFixtures.byId("thin-strands-merge-separate");
        var allowed = new ArrayList<MetricPoint>();
        for (int x = -8; x <= 21; x++) allowed.add(pointOn(fixture, fixture.strands().get(0), x));
        for (int x = 22; x <= 23; x++) allowed.add(pointOn(fixture, fixture.strands().get(1), x));
        for (int x = 24; x <= 52; x++) allowed.add(pointOn(fixture, fixture.strands().get(2), x));
        var accepted = evaluate(fixture, allowed, PITCH, CancellationProbe.NONE, owner());
        assertTrue(accepted.passed(), accepted.toString());

        var outside = new ArrayList<MetricPoint>();
        for (int x = -8; x < 20; x++) outside.add(pointOn(fixture, fixture.strands().get(0), x));
        outside.add(pointOn(fixture, fixture.strands().get(0), 20));
        outside.add(pointOn(fixture, fixture.strands().get(2), 20));
        for (int x = 21; x <= 52; x++) outside.add(pointOn(fixture, fixture.strands().get(2), x));
        var rejected = evaluate(fixture, outside, PITCH, CancellationProbe.NONE, owner());
        assertEquals(BThinStrandAcceptance.Verdict.FAIL,
                check(rejected, BThinStrandAcceptance.Reason.BRANCH_CONTINUITY).verdict());
        assertFalse(rejected.passed());
    }

    @Test void aMergeEdgeCannotJumpBetweenSeparatedStrandsBeforeTheActualMerge() {
        var fixture = BThinStrandFixtures.byId("thin-strands-merge-separate");
        var path = new ArrayList<MetricPoint>();
        for (int x = -8; x <= 18; x++) path.add(pointOn(fixture, fixture.strands().get(0), x));
        for (int x = 19; x <= 20; x++) path.add(pointOn(fixture, fixture.strands().get(2), x));
        for (int x = 21; x <= 23; x++) path.add(pointOn(fixture, fixture.strands().get(1), x));
        for (int x = 24; x <= 52; x++) path.add(pointOn(fixture, fixture.strands().get(2), x));
        var result = evaluate(fixture, path, PITCH, CancellationProbe.NONE, owner());
        var whole = check(result, BThinStrandAcceptance.Reason.WHOLE_PATH_TUBE);
        assertEquals(BThinStrandAcceptance.Verdict.FAIL, whole.verdict());
        assertEquals(path.size() - 2, whole.count(), "exactly the separated x=18..19 edge lacks one valid branch tube");
        assertEquals(BThinStrandAcceptance.Verdict.FAIL,
                check(result, BThinStrandAcceptance.Reason.BRANCH_CONTINUITY).verdict());
    }

    @Test void driftingFixtureAcceptsOnlyItsDeclaredStrongCourse() {
        var fixture = BThinStrandFixtures.byId("drifting-strong-with-faint-original");
        var strong = fixture.strongCourse().orElseThrow();
        var accepted = evaluate(fixture, pathOn(fixture, strong), PITCH, CancellationProbe.NONE, owner());
        assertTrue(accepted.passed(), accepted.toString());
        var faintShortcut = evaluate(fixture, pathOn(fixture, fixture.strands().get(1)), PITCH,
                CancellationProbe.NONE, owner());
        assertEquals(BThinStrandAcceptance.Verdict.FAIL,
                check(faintShortcut, BThinStrandAcceptance.Reason.WHOLE_PATH_TUBE).verdict());
        assertFalse(faintShortcut.passed());
    }

    @Test void bothSharpControlsNeedTheirApexAndTheWholeCourse() {
        for (String id : List.of("thin-strong-sharp-bend", "thin-faint-coherent-sharp-bend")) {
            var fixture = BThinStrandFixtures.byId(id);
            var route = pathOn(fixture, fixture.strands().get(0));
            assertTrue(evaluate(fixture, route, PITCH, CancellationProbe.NONE, owner()).passed(), id);
            var flat = List.of(LEFT, RIGHT);
            var flattened = evaluate(fixture, flat, PITCH, CancellationProbe.NONE, owner());
            assertEquals(BThinStrandAcceptance.Verdict.FAIL,
                    check(flattened, BThinStrandAcceptance.Reason.SHARP_APEX).verdict());
        }
    }

    @Test void overlapNoiseRouteMustStayWithinTheIndependentOneMetreReferenceBand() {
        var fixture = BThinStrandFixtures.byId("thin-local-overlap-noise");
        var middle = fixture.strands().get(1);
        var accepted = evaluate(fixture, pathOn(fixture, middle), PITCH, CancellationProbe.NONE, owner());
        assertTrue(accepted.passed(), accepted.toString());
        var excursion = new ArrayList<>(pathOn(fixture, middle));
        int column = 42; // world x=22m; perturb one vertex beyond the frozen y[36,38] band.
        var point = excursion.get(column - 12);
        excursion.set(column - 12, new MetricPoint(point.xMeters(), point.yMeters() + 1.01));
        var rejected = evaluate(fixture, excursion, PITCH, CancellationProbe.NONE, owner());
        assertEquals(BThinStrandAcceptance.Verdict.FAIL,
                check(rejected, BThinStrandAcceptance.Reason.OVERLAP_REFERENCE).verdict());
    }

    @Test void closedOneMetreTubeBoundaryPassesAtBothSigns() {
        var fixture = BThinStrandFixtures.byId("thin-three-strands");
        for (double offset : new double[] {-1.0, 1.0}) {
            var result = evaluate(fixture, offsetPath(fixture, fixture.strands().get(1), offset), PITCH,
                    CancellationProbe.NONE, owner());
            assertTrue(result.passed(), offset + ": " + result);
        }
    }

    @Test void missingIncompleteNonfiniteOrWrongPitchInputsRemainUnavailable() {
        var fixture = BThinStrandFixtures.byId("thin-three-strands");
        assertUnavailable(evaluate(fixture, null, PITCH, CancellationProbe.NONE, owner()));
        assertUnavailable(evaluate(fixture, List.of(), PITCH, CancellationProbe.NONE, owner()));
        assertThrows(IllegalArgumentException.class, () -> new MetricPoint(Double.NaN, 37));
        var incomplete = new ArrayList<MetricPoint>();
        incomplete.add(LEFT); incomplete.add(null); incomplete.add(RIGHT);
        assertUnavailable(evaluate(fixture, incomplete, PITCH, CancellationProbe.NONE, owner()));
        assertUnavailable(evaluate(fixture, pathOn(fixture, fixture.strands().get(1)), 0.5,
                CancellationProbe.NONE, owner()));
        var tooLong = new ArrayList<MetricPoint>(Collections.nCopies(4097, LEFT));
        assertUnavailable(evaluate(fixture, tooLong, PITCH, CancellationProbe.NONE, owner()));
    }

    @Test void cancellationAndOwnerOrResourceRefusalReleaseTheWholeTree() {
        var fixture = BThinStrandFixtures.byId("thin-three-strands");
        var path = pathOn(fixture, fixture.strands().get(1));
        var cancelledLedger = new AttemptMemoryLedger(1_000_000);
        try (var owner = cancelledLedger.rootOwner()) {
            int[] calls = {0};
            assertThrows(CancellationException.class, () -> evaluate(fixture, path, PITCH,
                    () -> ++calls[0] >= 6, owner));
            assertEquals(0, cancelledLedger.currentBytes());
        }
        assertEquals(0, cancelledLedger.currentBytes());

        var refusedLedger = new AttemptMemoryLedger(1);
        try (var owner = refusedLedger.rootOwner()) {
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                    () -> evaluate(fixture, path, PITCH, CancellationProbe.NONE, owner));
        }
        assertEquals(0, refusedLedger.currentBytes());

        var closedLedger = new AttemptMemoryLedger(1_000_000);
        var closed = closedLedger.rootOwner();
        closed.close();
        assertThrows(IllegalStateException.class,
                () -> evaluate(fixture, path, PITCH, CancellationProbe.NONE, closed));
        assertEquals(0, closedLedger.currentBytes());
    }

    @Test void checkSetCannotPassWhenRequiredEvidenceIsMissingDuplicatedOrUnavailable() {
        var all = List.of(
                new BThinStrandAcceptance.Check(BThinStrandAcceptance.Reason.EXACT_ANCHORS, BThinStrandAcceptance.Verdict.PASS, 2),
                new BThinStrandAcceptance.Check(BThinStrandAcceptance.Reason.NONDECREASING_X, BThinStrandAcceptance.Verdict.PASS, 60),
                new BThinStrandAcceptance.Check(BThinStrandAcceptance.Reason.WHOLE_PATH_TUBE, BThinStrandAcceptance.Verdict.PASS, 60),
                new BThinStrandAcceptance.Check(BThinStrandAcceptance.Reason.BRANCH_CONTINUITY, BThinStrandAcceptance.Verdict.PASS, 1));
        assertTrue(new BThinStrandAcceptance.Checks("single-coherent-strand", all).passed());
        assertFalse(new BThinStrandAcceptance.Checks("single-coherent-strand", all.subList(0, 3)).passed());
        assertFalse(new BThinStrandAcceptance.Checks("single-coherent-strand", List.of(all.get(0), all.get(0), all.get(2), all.get(3))).passed());
        var unavailable = new ArrayList<>(all);
        unavailable.set(2, new BThinStrandAcceptance.Check(BThinStrandAcceptance.Reason.WHOLE_PATH_TUBE,
                BThinStrandAcceptance.Verdict.UNAVAILABLE, 0));
        assertFalse(new BThinStrandAcceptance.Checks("single-coherent-strand", unavailable).passed());
    }

    private static BThinStrandAcceptance.Result evaluate(BThinStrandFixtures.Fixture fixture,
            List<MetricPoint> path, double pitch, CancellationProbe cancellation, AttemptMemoryLedger.Owner owner) {
        try {
            return BThinStrandAcceptance.evaluate(fixture, path, pitch, cancellation, owner);
        } finally {
            owner.close();
        }
    }

    private static BThinStrandAcceptance.Check check(BThinStrandAcceptance.Result result,
            BThinStrandAcceptance.Reason reason) {
        return result.checks().stream().filter(value -> value.reason() == reason).findFirst().orElseThrow();
    }

    private static void assertUnavailable(BThinStrandAcceptance.Result result) {
        assertFalse(result.passed());
        assertTrue(result.checks().stream().anyMatch(check -> check.verdict() == BThinStrandAcceptance.Verdict.UNAVAILABLE));
    }

    private static List<MetricPoint> pathOn(BThinStrandFixtures.Fixture fixture, BThinStrandFixtures.Strand strand) {
        var points = new ArrayList<MetricPoint>();
        points.add(LEFT);
        for (int x = -7; x <= 51; x++) points.add(pointOn(fixture, strand, x));
        points.add(RIGHT);
        return List.copyOf(points);
    }

    private static List<MetricPoint> offsetPath(BThinStrandFixtures.Fixture fixture,
            BThinStrandFixtures.Strand strand, double offset) {
        var points = new ArrayList<MetricPoint>();
        points.add(LEFT);
        for (int x = -7; x <= 51; x++) {
            int col = (int) (x - fixture.originX());
            points.add(new MetricPoint(x, strand.centerY(col) + offset));
        }
        points.add(RIGHT);
        return List.copyOf(points);
    }

    private static MetricPoint pointOn(BThinStrandFixtures.Fixture fixture,
            BThinStrandFixtures.Strand strand, int x) {
        int column = (int) (x - fixture.originX());
        return new MetricPoint(x, strand.centerY(column));
    }

    private static AttemptMemoryLedger.Owner owner() { return AttemptMemoryLedger.production().rootOwner(); }
}
