package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntToDoubleFunction;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupInterval;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import static org.junit.jupiter.api.Assertions.*;

class LongScaleNormalFairingTest {
    @Test void negativeZeroSignCannotCreateAZeroMovementProposal() {
        var points = List.of(new MetricPoint(-20, 1e6), new MetricPoint(-0.0, 1e6 + .1), new MetricPoint(20, 1e6));
        var base = request(points, Set.of(0, 2));
        var result = LongScaleNormalFairing.propose(new LongScaleNormalFairing.Request(base.basis(), 1e-10,
                base.intervals(), List.of()), CancellationProbe.NONE);
        assertEquals(0, result.maximumMovementMeters());
        assertEquals(LongScaleNormalFairing.Status.UNCHANGED, result.status());
        assertEquals(LongScaleNormalFairing.Disposition.UNCHANGED, result.intervals().get(0).disposition());
        assertEquals(points, result.points()); assertSame(points.get(1), result.points().get(1));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(result.points().get(1).xMeters()));
    }

    @Test void actualLiftedRoundingToRawIsUnchangedRatherThanAProposal() {
        var points = List.of(new MetricPoint(0, 1e6), new MetricPoint(20, 1e6 + .1), new MetricPoint(40, 1e6));
        var base = request(points, Set.of(0, 2));
        var result = LongScaleNormalFairing.propose(new LongScaleNormalFairing.Request(base.basis(), 1e-10,
                base.intervals(), List.of()), CancellationProbe.NONE);
        assertEquals(points, result.points()); assertEquals(0, result.maximumMovementMeters());
        assertEquals(LongScaleNormalFairing.Status.UNCHANGED, result.status());
        assertEquals(LongScaleNormalFairing.Disposition.UNCHANGED, result.intervals().get(0).disposition());
        assertFalse(result.intervals().get(0).trials().isEmpty()); assertTrue(result.workVisits() > 0);
    }

    @Test void actualLiftedRoundingAfterBacktrackingCannotBecomeAChangedIsland() {
        var points = List.of(new MetricPoint(0, 1e6), new MetricPoint(20, 1e6 + .1), new MetricPoint(40, 1e6));
        var base = request(points, Set.of(0, 2)); var calls = new AtomicInteger();
        var result = LongScaleNormalFairing.propose(new LongScaleNormalFairing.Request(base.basis(), 1e-8,
                base.intervals(), List.of(p -> {
                    calls.incrementAndGet(); return p.equals(points) ? ImageSupportedRefitter.Validation.accepted()
                            : ImageSupportedRefitter.Validation.rejected("rounded-output-required");
                })), CancellationProbe.NONE);
        assertEquals(points, result.points()); assertEquals(0, result.maximumMovementMeters());
        assertEquals(LongScaleNormalFairing.Status.UNCHANGED, result.status());
        assertEquals(LongScaleNormalFairing.Disposition.UNCHANGED, result.intervals().get(0).disposition());
        assertTrue(result.intervals().get(0).trials().size() > 1);
        assertEquals(calls.get(), result.externalValidationCalls());
    }

    @Test void wrinkleMovesAndWholeGeometryValidatorCanRejectIt() {
        var points = new ArrayList<MetricPoint>();
        var rows = new ArrayList<FinalControlBasis.Row>();
        double length = 0;
        for (int i = 0; i <= 60; i++) {
            var p = new MetricPoint(i, .15 * Math.sin(i * Math.PI / 2));
            if (i > 0) length += Math.hypot(p.xMeters() - points.get(i - 1).xMeters(), p.yMeters() - points.get(i - 1).yMeters());
            points.add(p);
            rows.add(FinalControlBasis.Row.independent(i));
        }
        var basis = new FinalControlBasis(points, rows, Set.of(0, 60));
        var interval = new CleanupInterval(0, 60, 0, length, 59, true, true, false, List.of());
        var result = LongScaleNormalFairing.propose(new LongScaleNormalFairing.Request(
                basis, 1, List.of(interval), List.of()), CancellationProbe.NONE);
        assertEquals(points.size(), result.points().size());
        assertTrue(Math.abs(result.points().get(29).yMeters()) < Math.abs(points.get(29).yMeters()) - 0.001);
        var rejected = LongScaleNormalFairing.propose(new LongScaleNormalFairing.Request(
                basis, 1, List.of(interval), List.of(p -> ImageSupportedRefitter.Validation.rejected("test"))),
                CancellationProbe.NONE);
        assertEquals(points, rejected.points());
    }

    @Test void straightGeometryRetainsExactSlotsAndFixedEndpoints() {
        var points = List.of(new MetricPoint(-0.0, 0), new MetricPoint(5, 0), new MetricPoint(10, 0));
        var rows = new ArrayList<FinalControlBasis.Row>();
        for (int i = 0; i < points.size(); i++) rows.add(FinalControlBasis.Row.independent(i));
        var basis = new FinalControlBasis(points, rows, Set.of(0, 2));
        var interval = new CleanupInterval(0, 2, 0, 10, 1, true, true, false, List.of());
        var result = LongScaleNormalFairing.propose(new LongScaleNormalFairing.Request(
                basis, 1, List.of(interval), List.of()), CancellationProbe.NONE);
        assertEquals(points, result.points());
        assertSame(points.get(0), result.points().get(0));
        assertSame(points.get(2), result.points().get(2));
    }

    @Test void derivedNonmidpointInsertionCannotChangeNativeFieldOrAddOutputSlots() {
        var original = wave(60, i -> .15 * Math.sin(i * Math.PI / 2));
        var plain = request(original, Set.of(0, 60));
        var expected = LongScaleNormalFairing.propose(plain, CancellationProbe.NONE);
        var split = new ArrayList<MetricPoint>(); var rows = new ArrayList<FinalControlBasis.Row>();
        for (int i = 0; i < original.size(); i++) {
            split.add(original.get(i)); rows.add(FinalControlBasis.Row.independent(split.size() - 1));
            if (i == 29) {
                MetricPoint a = original.get(i), b = original.get(i + 1);
                split.add(new MetricPoint(a.xMeters() + .37 * (b.xMeters() - a.xMeters()),
                        a.yMeters() + .37 * (b.yMeters() - a.yMeters())));
                rows.add(FinalControlBasis.Row.dependent(30, 29, 31, .37));
            }
        }
        var basis = new FinalControlBasis(split, rows, Set.of(0, split.size() - 1));
        var actual = LongScaleNormalFairing.propose(new LongScaleNormalFairing.Request(basis, 1,
                List.of(interval(split, 0, split.size() - 1)), List.of()), CancellationProbe.NONE);
        assertEquals(expected.status(), actual.status());
        assertEquals(split.size(), actual.points().size());
        for (int i = 0; i < original.size(); i++)
            assertTrue(expected.points().get(i).distanceTo(actual.points().get(i <= 29 ? i : i + 1)) < 1e-9);
        MetricPoint a = expected.points().get(29), b = expected.points().get(30);
        assertEquals(a.xMeters() + .37 * (b.xMeters() - a.xMeters()), actual.points().get(30).xMeters(), 1e-12);
        assertEquals(a.yMeters() + .37 * (b.yMeters() - a.yMeters()), actual.points().get(30).yMeters(), 1e-12);
        assertTrue(actual.requiresSourceValidation());
    }

    @Test void censoredEndpointIslandStaysExactWhileSafeInteriorWrinkleMoves() {
        var points = wave(80, i -> i == 1 ? 2 : i < 15 ? 0 : .15 * Math.sin(i * Math.PI / 2));
        var result = LongScaleNormalFairing.propose(request(points, Set.of(0, 80)), CancellationProbe.NONE);
        assertEquals(LongScaleNormalFairing.Status.PARTIALLY_PROPOSED, result.status());
        for (int i = 0; i < 10; i++) assertEquals(points.get(i), result.points().get(i));
        assertTrue(Math.abs(result.points().get(41).yMeters()) < .14);
        assertTrue(result.intervals().stream().anyMatch(i -> i.reason().equals("censored-broad-feature")));
    }

    @Test void rejectedLeftIslandDoesNotSuppressIndependentRightIsland() {
        var points = wave(60, i -> .15 * Math.sin(i * Math.PI / 2));
        var base = request(points, Set.of(0, 30, 60));
        var result = LongScaleNormalFairing.propose(new LongScaleNormalFairing.Request(base.basis(), 1,
                base.intervals(), List.of(p -> p.get(15).equals(points.get(15))
                    ? ImageSupportedRefitter.Validation.accepted() : ImageSupportedRefitter.Validation.rejected("left"))),
                CancellationProbe.NONE);
        assertEquals(LongScaleNormalFairing.Status.PARTIALLY_PROPOSED, result.status());
        assertEquals(points.get(15), result.points().get(15));
        assertTrue(Math.abs(result.points().get(45).yMeters()) < .14);
        assertTrue(result.intervals().stream().anyMatch(i -> i.trials().size() == 13));
        assertSame(points.get(30), result.points().get(30));
    }

    @Test void workBudgetIsSharedAndExhaustionDiscardsPreviouslyAcceptedIslands() {
        var points = wave(60, i -> .15 * Math.sin(i * Math.PI / 2));
        var base = request(points, Set.of(0, 30, 60));
        var complete = LongScaleNormalFairing.propose(base, CancellationProbe.NONE);
        assertTrue(complete.maximumMovementMeters() > .01);
        long cap = complete.workVisits() - 1;
        var refused = LongScaleNormalFairing.propose(new LongScaleNormalFairing.Request(base.basis(), 1,
                base.intervals(), List.of(), cap), CancellationProbe.NONE);
        assertEquals(LongScaleNormalFairing.Status.REFUSED_RESOURCE, refused.status());
        assertEquals(points, refused.points()); assertTrue(refused.workVisits() <= cap);
        var zero = LongScaleNormalFairing.propose(new LongScaleNormalFairing.Request(base.basis(), 1,
                base.intervals(), List.of(), 0), CancellationProbe.NONE);
        assertEquals(LongScaleNormalFairing.Status.REFUSED_RESOURCE, zero.status());
        assertEquals(0, zero.workVisits());
    }

    @Test void cancellationPropagatesDuringBoundedPhysicalWork() {
        var points = wave(60, i -> .15 * Math.sin(i * Math.PI / 2));
        var calls = new AtomicInteger();
        assertThrows(CancellationException.class, () -> LongScaleNormalFairing.propose(
                request(points, Set.of(0, 60)), () -> calls.incrementAndGet() > 50));
        assertTrue(calls.get() > 50);
    }

    @Test void invalidPhysicalAuthorityAndOversizedMeshRefuseWithoutMovingAnything() {
        var points = wave(60, i -> .15 * Math.sin(i * Math.PI / 2));
        var base = request(points, Set.of(0, 60)); var real = base.intervals().get(0);
        var wrong = new CleanupInterval(0, 60, 0, real.endChainageMeters() + 1, 59, true, true, false, List.of());
        var refused = LongScaleNormalFairing.propose(new LongScaleNormalFairing.Request(base.basis(), 1,
                List.of(wrong), List.of()), CancellationProbe.NONE);
        assertEquals(LongScaleNormalFairing.Status.REJECTED, refused.status()); assertEquals(points, refused.points());
        var longPoints = List.of(new MetricPoint(0, 0), new MetricPoint(1e6, 0), new MetricPoint(2e6, 0));
        var mesh = LongScaleNormalFairing.propose(request(longPoints, Set.of(0, 2)), CancellationProbe.NONE);
        assertEquals(LongScaleNormalFairing.Status.REFUSED_RESOURCE, mesh.status()); assertEquals("mesh-limit", mesh.reason());
    }

    @Test void fullLiftedGeometryIncludingDependentSlotsIsPassedToValidators() {
        var nativePoints = wave(60, i -> .15 * Math.sin(i * Math.PI / 2));
        var points = new ArrayList<MetricPoint>(); var rows = new ArrayList<FinalControlBasis.Row>();
        for (int i = 0; i < nativePoints.size(); i++) {
            points.add(nativePoints.get(i)); rows.add(FinalControlBasis.Row.independent(points.size() - 1));
            if (i == 29) {
                points.add(interpolate(nativePoints.get(29), nativePoints.get(30), .37));
                rows.add(FinalControlBasis.Row.dependent(30, 29, 31, .37));
            }
        }
        var basis = new FinalControlBasis(points, rows, Set.of(0, 61));
        var calls = new AtomicInteger(); var shortcutRejections = new AtomicInteger();
        var guarded = LongScaleNormalFairing.propose(new LongScaleNormalFairing.Request(basis, 1,
                List.of(interval(points, 0, 61)), List.of(p -> {
                    calls.incrementAndGet(); assertEquals(points.size(), p.size());
                    assertSame(points.get(0), p.get(0)); assertSame(points.get(61), p.get(61));
                    MetricPoint origin = points.get(30), left0 = points.get(29), right0 = points.get(31);
                    assertEquals(origin.xMeters() + .63 * (p.get(29).xMeters() - left0.xMeters())
                            + .37 * (p.get(31).xMeters() - right0.xMeters()), p.get(30).xMeters(), 1e-12);
                    assertEquals(origin.yMeters() + .63 * (p.get(29).yMeters() - left0.yMeters())
                            + .37 * (p.get(31).yMeters() - right0.yMeters()), p.get(30).yMeters(), 1e-12);
                    // Every independent control passes this fixture's broad fit bound; only
                    // the explicit derived-slot constraint detects the introduced shortcut.
                    for (int i : basis.independentIndices()) assertTrue(Math.abs(p.get(i).yMeters()) < 1);
                    if (!p.get(30).equals(origin)) {
                        shortcutRejections.incrementAndGet();
                        return ImageSupportedRefitter.Validation.rejected("derived-slot-shortcut");
                    }
                    return ImageSupportedRefitter.Validation.accepted();
                })), CancellationProbe.NONE);
        assertEquals(points, guarded.points()); assertTrue(calls.get() >= 13);
        assertEquals(13, shortcutRejections.get());
        assertEquals(calls.get(), guarded.externalValidationCalls());
        assertTrue(guarded.requiresSourceValidation());
    }

    @Test void r3GuardAllowsWrinkleReductionInsideBroadBend() {
        var raw = curve(12, x -> 5 * (1 - x * x / 144)
                + .12 * Math.sin(2 * Math.PI * (x + 12) / 3) * Math.pow(Math.sin(Math.PI * (x + 12) / 24), 2));
        var q = curve(12, x -> 5 * (1 - x * x / 144));
        assertTrue(assess(raw, q).accepted());
    }

    @Test void r3GuardRejectsMeaningfulBendFlattening() {
        var raw = curve(12, x -> 5 * (1 - x * x / 144));
        var q = curve(12, x -> .7 * 5 * (1 - x * x / 144));
        assertEquals("broad-amplitude-loss", assess(raw, q).lobeReason());
    }

    @Test void r3GuardRejectsArmRotationIndependently() {
        var raw = curve(12, x -> 5 * (1 - x * x / 144)); var q = new ArrayList<MetricPoint>();
        for (MetricPoint p : raw) q.add(new MetricPoint(Math.cos(.4) * p.xMeters() - Math.sin(.4) * p.yMeters(),
                Math.sin(.4) * p.xMeters() + Math.cos(.4) * p.yMeters()));
        assertEquals("broad-arm-heading", assess(raw, q).armReason());
    }

    @Test void r3GuardRejectsApexDisplacementWithArmsRetained() {
        var raw = curve(12, x -> 5 * (1 - Math.abs(x) / 12)); var q = new ArrayList<MetricPoint>();
        double[] s = chainage(raw); double center = s[s.length - 1] / 2;
        double left = at(raw, s, center - 5).yMeters(), right = at(raw, s, center + 5).yMeters();
        for (int i = 0; i < raw.size(); i++) {
            double u = s[i] - center, y = raw.get(i).yMeters();
            if (u > -5 && u <= 3) y = left + (5 - left) * (u + 5) / 8;
            if (u > 3 && u < 5) y = 5 + (right - 5) * (u - 3) / 2;
            q.add(new MetricPoint(raw.get(i).xMeters(), y));
        }
        var assessment = assess(raw, q);
        assertEquals("ok", assessment.armReason()); assertEquals("broad-apex-shift", assessment.lobeReason());
    }

    @Test void r3GuardRejectsSwitchbackLobeLossWhenNetTurnCancels() {
        var raw = curve(6, x -> 2 * Math.sin(2 * Math.PI * (x + 6) / 12));
        double[] originalS = chainage(raw); double center = originalS[originalS.length - 1] / 2;
        raw = addStations(raw, new double[]{center - 6, center + 6});
        double[] s = chainage(raw); center = s[s.length - 1] / 2;
        MetricPoint left = at(raw, s, center - 6), right = at(raw, s, center + 6);
        var q = new ArrayList<MetricPoint>();
        for (int i = 0; i < raw.size(); i++) q.add(s[i] > center - 6 && s[i] < center + 6
                ? interpolate(left, right, (s[i] - center + 6) / 12) : raw.get(i));
        var assessment = assess(raw, q);
        assertEquals("ok", assessment.armReason());
        assertEquals("broad-amplitude-loss", assessment.lobeReason());
        assertEquals(2, assessment.meaningfulLobes());
    }

    @Test void r3GuardAllowsUnchangedTightSwitchback() {
        var raw = curve(6, x -> 2 * Math.sin(2 * Math.PI * (x + 6) / 12));
        assertTrue(assess(raw, raw).accepted());
    }

    @Test void r3GuardDoesNotCallSubpitchNoiseAMeaningfulApex() {
        var raw = curve(12, x -> .2 * Math.sin(2 * Math.PI * (x + 12) / 24));
        var assessment = assess(raw, curve(12, x -> 0));
        assertEquals(0, assessment.meaningfulLobes()); assertTrue(assessment.accepted());
    }

    @Test void r3GuardKeepsDisconnectedApexBandsSeparate() {
        var raw = curve(12, x -> 4 * Math.pow(Math.sin(Math.PI * (x + 12) / 12), 2));
        var q = curve(12, x -> 3.8 * (1 - Math.abs(x) / 12));
        assertEquals("broad-apex-shift", assess(raw, q).lobeReason());
    }

    @Test void undefinedInnerApexChordFreezesItsLocalIsland() {
        var raw = new ArrayList<MetricPoint>();
        for (int x = -20; x <= 0; x++) raw.add(new MetricPoint(x, 0));
        raw.add(new MetricPoint(2, 0)); raw.add(new MetricPoint(2, 2)); raw.add(new MetricPoint(0, 2)); raw.add(new MetricPoint(0, 0));
        for (int x = 1; x <= 40; x++) raw.add(new MetricPoint(x, x < 15 ? 0 : .15 * Math.sin(x * Math.PI / 2)));
        assertEquals("undefined-broad-feature", LongScaleNormalFairing.assessBroadFeature(raw, raw, 1, 24, 4).lobeReason());
        assertEquals("ok", LongScaleNormalFairing.assessBroadFeature(raw, raw, 1, 24, 12).lobeReason());
        var result = LongScaleNormalFairing.propose(request(raw, Set.of(0, raw.size() - 1)), CancellationProbe.NONE);
        assertTrue(result.intervals().stream().anyMatch(i -> i.reason().equals("censored-inner-apex-feature")));
        for (int i = 20; i <= 24; i++) assertEquals(raw.get(i), result.points().get(i));
    }

    @Test void tinyFiniteReferenceCannotOverflowStencilIndexEvenWithHugeWorkAllowance() {
        var points = List.of(new MetricPoint(0, 0), new MetricPoint(1e-10, 1e-11), new MetricPoint(2e-10, 0));
        var base = request(points, Set.of(0, 2));
        var result = LongScaleNormalFairing.propose(new LongScaleNormalFairing.Request(base.basis(), 1,
                base.intervals(), List.of(), Long.MAX_VALUE), CancellationProbe.NONE);
        assertEquals(LongScaleNormalFairing.Status.REFUSED_RESOURCE, result.status());
        assertEquals("stencil-limit", result.reason()); assertEquals(points, result.points());
        assertEquals(0, result.externalValidationCalls());
    }

    @Test void meaningfulBroadBendAndBothTightSwitchbackLobesRetainAmplitude() {
        for (boolean switchback : List.of(false, true)) {
            var points = new ArrayList<MetricPoint>();
            for (int i = 0; i <= 240; i++) {
                double x = i * .25;
                double y = switchback ? 4 * Math.sin(2 * Math.PI * x / 24)
                        : 5 * Math.sin(Math.PI * x / 60) + .1 * Math.sin(2 * Math.PI * x / 3);
                points.add(new MetricPoint(x, y));
            }
            var result = LongScaleNormalFairing.propose(request(points, Set.of(0, 240)), CancellationProbe.NONE);
            assertNotEquals(LongScaleNormalFairing.Status.REFUSED_RESOURCE, result.status());
            assertTrue(result.points().stream().mapToDouble(MetricPoint::yMeters).max().orElseThrow()
                    >= .9 * points.stream().mapToDouble(MetricPoint::yMeters).max().orElseThrow());
            if (switchback) assertTrue(result.points().stream().mapToDouble(MetricPoint::yMeters).min().orElseThrow()
                    <= .9 * points.stream().mapToDouble(MetricPoint::yMeters).min().orElseThrow());
            for (int i = 0; i < points.size(); i++) assertTrue(points.get(i).distanceTo(result.points().get(i)) <= .5 + 1e-9);
            assertEquals(points.size(), result.points().size());
        }
    }

    private static List<MetricPoint> wave(int last, IntToDoubleFunction y) {
        var result = new ArrayList<MetricPoint>();
        for (int i = 0; i <= last; i++) result.add(new MetricPoint(i, y.applyAsDouble(i)));
        return result;
    }
    private static List<MetricPoint> curve(double halfExtent, DoubleUnaryOperator y) {
        var points = new ArrayList<MetricPoint>();
        for (int i = 0; i <= 240; i++) { double x = -halfExtent + 2 * halfExtent * i / 240; points.add(new MetricPoint(x, y.applyAsDouble(x))); }
        return points;
    }
    private static LongScaleNormalFairing.BroadFeatureAssessment assess(List<MetricPoint> raw, List<MetricPoint> proposed) {
        double[] s = chainage(raw);
        return LongScaleNormalFairing.assessBroadFeature(raw, proposed, 1, s[s.length - 1] / 2, 12);
    }
    private static double[] chainage(List<MetricPoint> points) {
        double[] s = new double[points.size()];
        for (int i = 1; i < s.length; i++) s[i] = s[i - 1] + points.get(i - 1).distanceTo(points.get(i));
        return s;
    }
    private static MetricPoint interpolate(MetricPoint a, MetricPoint b, double t) {
        return new MetricPoint(a.xMeters() + t * (b.xMeters() - a.xMeters()), a.yMeters() + t * (b.yMeters() - a.yMeters()));
    }
    private static MetricPoint at(List<MetricPoint> points, double[] s, double station) {
        int i = 1; while (i < s.length - 1 && s[i] < station) i++;
        return interpolate(points.get(i - 1), points.get(i), (station - s[i - 1]) / (s[i] - s[i - 1]));
    }
    private static List<MetricPoint> addStations(List<MetricPoint> points, double[] stations) {
        double[] s = chainage(points); var all = new TreeMap<Double, MetricPoint>();
        for (int i = 0; i < s.length; i++) all.put(s[i], points.get(i));
        for (double station : stations) all.put(station, at(points, s, station));
        return List.copyOf(all.values());
    }
    private static LongScaleNormalFairing.Request request(List<MetricPoint> points, Set<Integer> fixed) {
        var rows = new ArrayList<FinalControlBasis.Row>();
        for (int i = 0; i < points.size(); i++) rows.add(FinalControlBasis.Row.independent(i));
        return new LongScaleNormalFairing.Request(new FinalControlBasis(points, rows, fixed), 1,
                List.of(interval(points, 0, points.size() - 1)), List.of());
    }
    private static CleanupInterval interval(List<MetricPoint> points, int first, int last) {
        double start = 0, end = 0;
        for (int i = 1; i < points.size(); i++) {
            end += points.get(i - 1).distanceTo(points.get(i)); if (i == first) start = end;
            if (i == last) break;
        }
        return new CleanupInterval(first, last, start, end, last - first - 1, true, true, false, List.of());
    }
}
