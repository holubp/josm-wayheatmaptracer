package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationCatalog.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Synthetic metric adversaries for the distinct full-route oracle. */
class V022BGapFullRouteAcceptanceTest {
    private static final double PITCH = 0.5;
    private static final MetricPoint LEFT = p(0, 30), RIGHT = p(120, 30);

    @Test void sameBranchAnchorApproachPassesAndReportsEveryRequiredCheck() {
        var ledger = new AttemptMemoryLedger(1_000_000);
        var owner = ledger.rootOwner();
        var result = evaluate(parallelScene(), List.of(LEFT, p(1.5, 34), p(118.5, 34), RIGHT), PITCH,
                CancellationProbe.NONE, owner);
        assertTrue(BGapFullRouteAcceptance.ORACLE_ID.endsWith("FULL_INTERIOR_V1"));
        assertEquals(List.of(BGapFullRouteAcceptance.Reason.EXACT_ANCHORS,
                BGapFullRouteAcceptance.Reason.ONE_BRANCH_FULL_INTERIOR,
                BGapFullRouteAcceptance.Reason.ORDERED_APPROACH_DEPARTURE,
                BGapFullRouteAcceptance.Reason.WHOLE_SEGMENT_CONTAINMENT), result.checks().stream().map(BGapFullRouteAcceptance.Check::reason).toList());
        assertTrue(result.passed());
        assertTrue(ledger.currentBytes() > 0, "the returned immutable checks are owned by the caller");
        owner.close();
        assertEquals(0, ledger.currentBytes());
    }

    @Test void suppliedCurvedBranchesAndDifferentNativePitchRemainEligible() {
        var curves = new ArrayList<List<MetricPoint>>();
        for (double offset : new double[] {-4.0, 4.0}) {
            var curve = new ArrayList<MetricPoint>();
            for (int x = 0; x <= 120; x++) curve.add(p(x, selectedCenter(x) + offset));
            curves.add(List.copyOf(curve));
        }
        var scene = scene(curves, EvidenceResolution.nativeSource(1.75, 1.75));
        double pitch = 1.75;
        var path = new ArrayList<MetricPoint>();
        path.add(LEFT); path.add(p(1.5, selectedCenter(1.5) + 4.0));
        for (int x = 2; x <= 118; x++) path.add(p(x, selectedCenter(x) + 4.0));
        path.add(p(118.5, selectedCenter(118.5) + 4.0)); path.add(RIGHT);
        var result = evaluate(definition(pitch, Split.SELECT), scene, path, pitch, CancellationProbe.NONE, owner());
        assertTrue(result.passed(), result.toString());
    }

    @Test void savedAndRelocatedJumpsFailTheFullInterior() {
        assertFalse(evaluate(parallelScene(), List.of(LEFT, p(1.5, 34), p(29, 34), p(30, 30), p(31, 34), p(118.5, 34), RIGHT), PITCH,
                CancellationProbe.NONE, owner()).passed());
        assertFalse(evaluate(parallelScene(), List.of(LEFT, p(1.5, 34), p(39, 34), p(40, 29), p(41, 34), p(118.5, 34), RIGHT), PITCH,
                CancellationProbe.NONE, owner()).passed());
    }

    @Test void switchInEitherApproachFanFails() {
        assertFalse(evaluate(parallelScene(), List.of(LEFT, p(.75, 26), p(1.5, 34), p(118.5, 34), RIGHT), PITCH,
                CancellationProbe.NONE, owner()).passed());
        assertFalse(evaluate(parallelScene(), List.of(LEFT, p(1.5, 34), p(118.5, 34), p(119.25, 26), RIGHT), PITCH,
                CancellationProbe.NONE, owner()).passed());
    }

    @Test void enteringLeavingAndReenteringChosenTubeWithinEitherFanFailsOrderedContact() {
        var initial = List.of(LEFT, p(1.35, 33.55), p(1.38, 33.4), p(1.42, 33.6),
                p(1.5, 34), p(118.5, 34), RIGHT);
        var initialResult = evaluate(parallelScene(), initial, PITCH, CancellationProbe.NONE, owner());
        assertTrue(check(initialResult, BGapFullRouteAcceptance.Reason.ONE_BRANCH_FULL_INTERIOR).verdict()
                == BIdentificationAcceptance.Verdict.PASS);
        assertEquals(BIdentificationAcceptance.Verdict.FAIL,
                check(initialResult, BGapFullRouteAcceptance.Reason.ORDERED_APPROACH_DEPARTURE).verdict());

        var terminal = List.of(LEFT, p(1.5, 34), p(118.5, 34), p(118.55, 33.6),
                p(118.58, 33.4), p(118.62, 33.6), p(118.65, 33.55), RIGHT);
        var terminalResult = evaluate(parallelScene(), terminal, PITCH, CancellationProbe.NONE, owner());
        assertEquals(BIdentificationAcceptance.Verdict.FAIL,
                check(terminalResult, BGapFullRouteAcceptance.Reason.ORDERED_APPROACH_DEPARTURE).verdict());
    }

    @Test void foreignTouchMidgapAndCollinearTravelFailWhileNoContactPasses() {
        assertTrue(evaluate(parallelScene(), List.of(LEFT, p(1.5, 34), p(60, 34), p(118.5, 34), RIGHT), PITCH,
                CancellationProbe.NONE, owner()).passed());
        assertFalse(evaluate(parallelScene(), List.of(LEFT, p(1.5, 34), p(59, 34), p(60, 26), p(61, 34), p(118.5, 34), RIGHT), PITCH,
                CancellationProbe.NONE, owner()).passed());
        var boundaryTouch = evaluate(parallelScene(), List.of(LEFT, p(1.5, 34), p(59, 34), p(60, 26.5),
                p(61, 34), p(118.5, 34)), PITCH, CancellationProbe.NONE, owner());
        assertEquals(BIdentificationAcceptance.Verdict.FAIL,
                check(boundaryTouch, BGapFullRouteAcceptance.Reason.ORDERED_APPROACH_DEPARTURE).verdict());
        var edgeCrossing = evaluate(parallelScene(), List.of(LEFT, p(1.5, 34), p(59, 34), p(61, 25),
                p(62, 34), p(118.5, 34), RIGHT), PITCH, CancellationProbe.NONE, owner());
        assertEquals(BIdentificationAcceptance.Verdict.FAIL,
                check(edgeCrossing, BGapFullRouteAcceptance.Reason.ORDERED_APPROACH_DEPARTURE).verdict());
        assertFalse(evaluate(parallelScene(), List.of(LEFT, p(1.5, 34), p(50, 34), p(52, 26), p(56, 26), p(58, 34), p(118.5, 34), RIGHT), PITCH,
                CancellationProbe.NONE, owner()).passed());
    }

    @Test void everyRouteEdgeMustBeContainedAndAnchorsMustBeExact() {
        var escaping = List.of(LEFT, p(1.5, 34), p(45, 34), p(46, 35), p(118.5, 34), RIGHT);
        assertFalse(evaluate(parallelScene(), escaping, PITCH, CancellationProbe.NONE, owner()).passed());
        var wrongAnchor = List.of(p(0, 30.1), p(1.5, 34), p(118.5, 34), RIGHT);
        assertFalse(evaluate(parallelScene(), wrongAnchor, PITCH, CancellationProbe.NONE, owner()).passed());
    }

    @Test void genuineJunctionIsUnavailableAsPersistentParallelOracle() {
        var crossing = scene(List.of(List.of(p(0, 26), p(60, 34), p(120, 26)),
                List.of(p(0, 34), p(60, 26), p(120, 34))));
        var result = evaluate(crossing, List.of(LEFT, p(1.5, 34), p(118.5, 34), RIGHT), PITCH,
                CancellationProbe.NONE, owner());
        assertFalse(result.passed());
        assertTrue(result.checks().stream().anyMatch(check -> check.verdict() == BIdentificationAcceptance.Verdict.UNAVAILABLE));
    }

    @Test void missingOrInvalidNativePitchIsUnavailable() {
        for (double pitch : new double[] {Double.NaN, 0, -1, Double.POSITIVE_INFINITY}) {
            var result = evaluate(parallelScene(), List.of(LEFT, p(1.5, 34), p(118.5, 34), RIGHT), pitch,
                    CancellationProbe.NONE, owner());
            assertFalse(result.passed());
            assertTrue(result.checks().stream().allMatch(check -> check.verdict() == BIdentificationAcceptance.Verdict.UNAVAILABLE));
        }
        var sceneNativeOne = scene(List.of(List.of(p(0, 26), p(120, 26)), List.of(p(0, 34), p(120, 34))),
                EvidenceResolution.nativeSource(1.0, 1.0));
        var nativeOnePositive = evaluate(definition(1.0, Split.TRAIN), sceneNativeOne,
                List.of(LEFT, p(1.5, 34), p(118.5, 34), RIGHT), 1.0, CancellationProbe.NONE, owner());
        assertTrue(nativeOnePositive.passed(), nativeOnePositive.toString());
        var mismatched = evaluate(definition(), sceneNativeOne, List.of(LEFT, p(1.5, 34), p(118.5, 34), RIGHT),
                PITCH, CancellationProbe.NONE, owner());
        assertTrue(mismatched.checks().stream().allMatch(check -> check.verdict() == BIdentificationAcceptance.Verdict.UNAVAILABLE));
        var missingNative = scene(List.of(List.of(p(0, 26), p(120, 26)), List.of(p(0, 34), p(120, 34))),
                EvidenceResolution.renderedOnly(PITCH));
        var unavailable = evaluate(definition(), missingNative, List.of(LEFT, p(1.5, 34), p(118.5, 34), RIGHT),
                PITCH, CancellationProbe.NONE, owner());
        assertTrue(unavailable.checks().stream().allMatch(check -> check.verdict() == BIdentificationAcceptance.Verdict.UNAVAILABLE));
    }

    @Test void checksRequireEachOfTheFourDistinctReasonsExactlyOnceAndPassing() {
        var all = List.of(BGapFullRouteAcceptance.Reason.values()).stream()
                .map(reason -> new BGapFullRouteAcceptance.Check(reason, BIdentificationAcceptance.Verdict.PASS, 1)).toList();
        assertTrue(new BGapFullRouteAcceptance.Checks(all).passed());
        var duplicate = List.of(all.get(0), all.get(0), all.get(2), all.get(3));
        assertFalse(new BGapFullRouteAcceptance.Checks(duplicate).passed());
        assertFalse(new BGapFullRouteAcceptance.Checks(all.subList(0, 3)).passed());
        var unavailable = new ArrayList<>(all);
        unavailable.set(1, new BGapFullRouteAcceptance.Check(all.get(1).reason(), BIdentificationAcceptance.Verdict.UNAVAILABLE, 1));
        assertFalse(new BGapFullRouteAcceptance.Checks(unavailable).passed());
    }

    @Test void fanBoundaryContactIsSubdivisionInvariantWhenAnEdgeCrossesTheJoin() {
        var unsplit = evaluate(parallelScene(), List.of(LEFT, p(1.6, 34), p(118.5, 34), RIGHT), PITCH,
                CancellationProbe.NONE, owner());
        var split = evaluate(parallelScene(), List.of(LEFT, p(1.5, 33.75), p(1.6, 34), p(118.5, 34), RIGHT), PITCH,
                CancellationProbe.NONE, owner());
        var terminalUnsplit = evaluate(parallelScene(), List.of(LEFT, p(1.5, 34), p(118.4, 34), RIGHT), PITCH,
                CancellationProbe.NONE, owner());
        var terminalSplit = evaluate(parallelScene(), List.of(LEFT, p(1.5, 34), p(118.4, 34),
                p(118.5, 33.75), RIGHT), PITCH, CancellationProbe.NONE, owner());
        assertTrue(unsplit.passed(), unsplit.toString());
        assertTrue(split.passed(), split.toString());
        assertTrue(terminalUnsplit.passed(), terminalUnsplit.toString());
        assertTrue(terminalSplit.passed(), terminalSplit.toString());
        assertEquals(unsplit.checks().stream().map(BGapFullRouteAcceptance.Check::verdict).toList(),
                split.checks().stream().map(BGapFullRouteAcceptance.Check::verdict).toList());
        assertEquals(terminalUnsplit.checks().stream().map(BGapFullRouteAcceptance.Check::verdict).toList(),
                terminalSplit.checks().stream().map(BGapFullRouteAcceptance.Check::verdict).toList());
    }

    @Test void curvedFanReentryAlsoFailsAtEitherBoundary() {
        double pitch = 1.75;
        var curves = List.of(curve(-4), curve(4));
        var scene = scene(curves, EvidenceResolution.nativeSource(pitch, pitch));
        double initialCenter = selectedCenter(1.35) + 4;
        var initial = new ArrayList<MetricPoint>();
        initial.add(LEFT);
        initial.add(p(1.35, initialCenter - 1.7));
        initial.add(p(1.38, selectedCenter(1.38) + 4 - 1.8));
        initial.add(p(1.42, selectedCenter(1.42) + 4 - 1.7));
        initial.add(p(1.5, selectedCenter(1.5) + 4));
        for (int x = 2; x <= 118; x++) initial.add(p(x, selectedCenter(x) + 4));
        initial.add(p(118.5, selectedCenter(118.5) + 4)); initial.add(RIGHT);
        var initialResult = evaluate(definition(pitch, Split.SELECT), scene, initial, pitch, CancellationProbe.NONE, owner());
        assertEquals(BIdentificationAcceptance.Verdict.PASS,
                check(initialResult, BGapFullRouteAcceptance.Reason.ONE_BRANCH_FULL_INTERIOR).verdict());
        assertEquals(BIdentificationAcceptance.Verdict.FAIL,
                check(initialResult, BGapFullRouteAcceptance.Reason.ORDERED_APPROACH_DEPARTURE).verdict());
        assertEquals(BIdentificationAcceptance.Verdict.PASS,
                check(initialResult, BGapFullRouteAcceptance.Reason.WHOLE_SEGMENT_CONTAINMENT).verdict());

        var terminal = new ArrayList<MetricPoint>();
        terminal.add(LEFT); terminal.add(p(1.5, selectedCenter(1.5) + 4));
        for (int x = 2; x <= 118; x++) terminal.add(p(x, selectedCenter(x) + 4));
        terminal.add(p(118.5, selectedCenter(118.5) + 4));
        terminal.add(p(118.55, selectedCenter(118.55) + 4 - 1.7));
        terminal.add(p(118.58, selectedCenter(118.58) + 4 - 1.8));
        terminal.add(p(118.62, selectedCenter(118.62) + 4 - 1.7));
        terminal.add(RIGHT);
        var terminalResult = evaluate(definition(pitch, Split.SELECT), scene, terminal, pitch, CancellationProbe.NONE, owner());
        assertEquals(BIdentificationAcceptance.Verdict.PASS,
                check(terminalResult, BGapFullRouteAcceptance.Reason.ONE_BRANCH_FULL_INTERIOR).verdict());
        assertEquals(BIdentificationAcceptance.Verdict.FAIL,
                check(terminalResult, BGapFullRouteAcceptance.Reason.ORDERED_APPROACH_DEPARTURE).verdict());
    }

    @Test void cancellationAndOwnerRefusalDoNotLeaveScratchOwned() {
        var cancellationLedger = new AttemptMemoryLedger(1_000_000);
        var cancellationOwner = cancellationLedger.rootOwner();
        assertThrows(CancellationException.class, () -> evaluate(parallelScene(), List.of(LEFT, p(1.5, 34), p(118.5, 34), RIGHT), PITCH,
                () -> true, cancellationOwner));
        cancellationOwner.close();
        assertEquals(0, cancellationLedger.currentBytes());

        var limitedLedger = new AttemptMemoryLedger(1);
        var limitedOwner = limitedLedger.rootOwner();
        assertThrows(AttemptMemoryLedger.ResourceLimitException.class, () -> evaluate(parallelScene(), List.of(LEFT, p(1.5, 34), p(118.5, 34), RIGHT), PITCH,
                CancellationProbe.NONE, limitedOwner));
        limitedOwner.close();
        assertEquals(0, limitedLedger.currentBytes());
    }

    @Test void midGeometryCancellationAndWorkRefusalCloseTheEntireOwnedTree() {
        var curves = denseBranches(2100);
        var scene = scene(curves, EvidenceResolution.nativeSource(PITCH, PITCH));
        var cancelLedger = new AttemptMemoryLedger(8_000_000);
        var cancelOwner = cancelLedger.rootOwner();
        var probes = new int[1];
        assertThrows(CancellationException.class, () -> evaluate(definition(), scene,
                List.of(LEFT, p(1.5, 34), p(118.5, 34), RIGHT), PITCH,
                () -> ++probes[0] >= 24, cancelOwner));
        assertTrue(probes[0] >= 24);
        assertEquals(0, cancelLedger.currentBytes());
        try (var reservation = cancelOwner.reserve(8)) { assertEquals(8, reservation.bytes()); }
        cancelOwner.close();
        assertEquals(0, cancelLedger.currentBytes());

        var workLedger = new AttemptMemoryLedger(8_000_000);
        var workOwner = workLedger.rootOwner();
        assertThrows(IllegalArgumentException.class, () -> evaluate(definition(), scene,
                List.of(LEFT, p(1.5, 34), p(118.5, 34), RIGHT), PITCH,
                CancellationProbe.NONE, workOwner));
        assertEquals(0, workLedger.currentBytes());
        workOwner.close();
        assertEquals(0, workLedger.currentBytes());
    }

    private static BGapFullRouteAcceptance.Checks evaluate(Scene scene, List<MetricPoint> path, double pitch,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner owner) {
        return evaluate(definition(), scene, path, pitch, cancellation, owner);
    }

    private static BGapFullRouteAcceptance.Checks evaluate(CaseDefinition definition, Scene scene,
            List<MetricPoint> path, double pitch, CancellationProbe cancellation, AttemptMemoryLedger.Owner owner) {
        return BGapFullRouteAcceptance.evaluate(definition, scene, path, pitch, cancellation, owner);
    }

    private static AttemptMemoryLedger.Owner owner() { return AttemptMemoryLedger.production().rootOwner(); }

    private static Scene parallelScene() { return scene(List.of(List.of(p(0, 26), p(120, 26)), List.of(p(0, 34), p(120, 34)))); }

    private static Scene scene(List<List<MetricPoint>> branches) {
        return scene(branches, EvidenceResolution.nativeSource(PITCH, PITCH));
    }

    private static Scene scene(List<List<MetricPoint>> branches, EvidenceResolution resolution) {
        var labels = List.of(new LabelRegion(MetricRegion.rectangle(0, 0, 120, 60),
                StructureLabel.OBSERVED_STRUCTURE, RasterLabel.VALID, AssociationLabel.AMBIGUOUS, branches));
        EvidenceSnapshot original = V022LocalizationEvidenceTest.snapshot((x, y) -> 0);
        var evidence = new EvidenceSnapshot(original.snapshotId(), original.coordinateFrame(), original.transform(),
                resolution, original.decisionRegion(), original.evidenceRegion(), original.fields(),
                original.resampling(), original.sourceIdentity());
        return new Scene(evidence, List.of(LEFT, RIGHT), new Truth(labels));
    }

    private static List<List<MetricPoint>> denseBranches(int subdivisions) {
        var lower = new ArrayList<MetricPoint>(); var upper = new ArrayList<MetricPoint>();
        for (int index = 0; index <= subdivisions; index++) {
            double x = 120.0 * index / subdivisions;
            lower.add(p(x, 26)); upper.add(p(x, 34));
        }
        return List.of(List.copyOf(lower), List.copyOf(upper));
    }

    private static List<MetricPoint> curve(double offset) {
        var curve = new ArrayList<MetricPoint>();
        for (int x = 0; x <= 120; x++) curve.add(p(x, selectedCenter(x) + offset));
        return List.copyOf(curve);
    }

    private static CaseDefinition definition() {
        return definition(PITCH, Split.REGRESSION);
    }

    private static CaseDefinition definition(double pitch, Split split) {
        var key = new CaseKey("synthetic/persistent-parallel", "synthetic-generator", "synthetic-geometry",
                "synthetic-source", split, "synthetic-recipe", 1L);
        return new CaseDefinition(key, "sparse-crossing-noisy-strands-vs-persistent-parallel", "right", "v0",
                0, pitch, 1, false, "synthetic two-branch parallel control", List.of("analytic-scalar", "typed-native-resolution"));
    }

    private static BGapFullRouteAcceptance.Check check(BGapFullRouteAcceptance.Checks checks,
            BGapFullRouteAcceptance.Reason reason) {
        return checks.checks().stream().filter(check -> check.reason() == reason).findFirst().orElseThrow();
    }

    private static double selectedCenter(double x) {
        double t = x / 120.0;
        double twiceMinusOne = 2 * t - 1;
        return 30 + 16 * t * t * (1 - t) * (1 - t)
                * (5 + 7 * twiceMinusOne - 6 * twiceMinusOne * twiceMinusOne);
    }

    private static MetricPoint p(double x, double y) { return new MetricPoint(x, y); }
}
