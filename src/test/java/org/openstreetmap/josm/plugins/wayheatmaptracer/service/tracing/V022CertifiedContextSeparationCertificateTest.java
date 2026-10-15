package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;

/** Frozen complete evidence and arithmetic-work oracles; no elapsed-time assertions. */
class V022CertifiedContextSeparationCertificateTest {
    private static final LocalMetricFrame FRAME = LocalMetricFrame.certifiedEquirectangular(
            new GeographicPoint(0, 0), new GeographicPoint(-.001, -.001), new GeographicPoint(.001, .001));

    @Test
    void certifiedNorthSeparationAvoidsPairTimeExactProducts() {
        Counter counter = new Counter();
        var first = countedChord(0, 0, 100, 0, counter);
        var second = countedChord(0, 100, 100, 100, counter);
        counter.products = 0;
        counter.powers = 0;
        assertFalse(CertifiedContextSegmentClipper.originalContactEvidence(
                first, null, null, second, null, null, FRAME).hasAnyContact());
        assertEquals(0, counter.products + counter.powers,
                "certified far north boxes require no pair-time unlimited-precision products");
    }

    @Test
    void completeOrderedEvidenceMatchesFrozenAcrossPhysicalAndOwnershipCases() {
        var base = chord(0, 0, 10, 0);
        List<CertifiedContextSegmentClipper.ExactChord> cases = List.of(
                chord(0, 100, 10, 100), chord(100, 0, 110, 0),
                chord(5, -1, 5, 1), chord(10, 0, 20, 0), chord(5, 0, 15, 0),
                chord(5, 1e-8, 15, 1e-8), chord(5, Math.nextUp(1e-8), 15, Math.nextUp(1e-8)),
                chord(0, 0, 0, 0), chord(0, 0, Double.MIN_VALUE, Double.MIN_VALUE),
                chord(-1e100, 1e100, 1e100, 1e100),
                chord(-150_000_000, 0, 150_000_000, 0),
                chord(0, -150_000_000, 0, 150_000_000));
        for (var candidate : cases) for (boolean reverse : List.of(false, true)) {
            var a = reverse ? new CertifiedContextSegmentClipper.ExactChord(base.end(), base.start()) : base;
            for (boolean shared : List.of(false, true)) {
                compare(a, candidate, FRAME, shared); compare(candidate, a, FRAME, shared);
            }
        }
        // The two chords individually have maxAbsEast+maxAbsNorth=150e6, but
        // their complete pair-box span is600e6 and they actually CROSS.
        var east = chord(-150_000_000, 0, 150_000_000, 0);
        var north = chord(0, -150_000_000, 0, 150_000_000);
        compare(east, north, FRAME, false);
        assertTrue(CertifiedContextSegmentClipper.originalContactEvidence(
                east, null, null, north, null, null, FRAME).properCrossing());
    }

    @Test
    void allLongitudeBranchesKeepOrderedWitnessesAndOriginalNullFrameBoundary() {
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 180),
                new GeographicPoint(-.001, 179.999), new GeographicPoint(.001, -179.999));
        var a = CertifiedContextSegmentClipper.originalChord(new GeographicPoint(0, 179.9999),
                new GeographicPoint(0, -179.9999), frame);
        var b = CertifiedContextSegmentClipper.originalChord(new GeographicPoint(-.0001, 180),
                new GeographicPoint(.0001, 180), frame);
        compare(a, b, frame, true);
        var near = chord(0, 0, 10, 0);
        assertFalse(CertifiedContextSegmentClipper.originalContactEvidence(near, null, null,
                chord(0, 100, 10, 100), null, null, null).hasAnyContact());
        assertThrows(NullPointerException.class, () -> CertifiedContextSegmentClipper.originalContactEvidence(
                near, null, null, chord(100, 0, 110, 0), null, null, null));
    }

    @Test
    void exactCertificateRejectsDegenerateOrUnrepresentableProposalsAndShiftDoesNotRecertify() throws Exception {
        for (double length : new double[] {1e-100, .1, 1, 100, 1e100}) {
            var value = chord(0, 0, length, 0);
            double radius = scalar(value, "separationRadius");
            if (radius > 0) {
                assertTrue(new BigDecimal(radius).pow(2).multiply(value.lengthSquared()).compareTo(
                        new BigDecimal("8192").add(new BigDecimal("0.0000000000000968")
                                .multiply(value.lengthSquared()))) >= 0);
            }
        }
        assertTrue(scalar(chord(0, 0, 100, 0), "separationRadius") > 0);
        for (double length : new double[] {0, Double.MIN_VALUE, Double.MAX_VALUE})
            assertEquals(0.0, scalar(chord(0, 0, length, 0), "separationRadius"));
        Method certify = CertifiedContextSegmentClipper.class.getDeclaredMethod("certifiedSeparationRadius", BigDecimal.class);
        certify.setAccessible(true);
        // An intentionally low finite proposal can fail its exact certificate;
        // refusal is a normal miss, with no iterative search or contact assertion.
        assertEquals(0.0, (double) certify.invoke(null, new BigDecimal("1E-1000")));
        var original = chord(1, -2, 101, 3);
        Counter counter = new Counter();
        var lengthField = original.getClass().getDeclaredField("lengthSquared"); lengthField.setAccessible(true);
        lengthField.set(original, new CountedDecimal(original.lengthSquared(), counter));
        for (String shift : List.of("-40075016.68557849", "0.000000000000", "40075016.68557849")) {
            var shifted = original.shifted(new BigDecimal(shift));
            assertSame(original.lengthSquared(), shifted.lengthSquared());
            assertEquals(Double.doubleToRawLongBits(scalar(original, "separationRadius")),
                    Double.doubleToRawLongBits(scalar(shifted, "separationRadius")));
            assertEquals(0, counter.doubleReads, "translated chord must not propose/certify its length again");
            assertOutward(shifted);
        }
    }

    @Test
    void radiusNeighboursAndCompletePairSpanUseConservativeFallback() throws Exception {
        var a = chord(0, 0, 100, 0);
        double radius = scalar(a, "separationRadius");
        for (double gap : new double[] {Math.nextDown(radius), radius, Math.nextUp(radius), radius * 2}) {
            var b = chord(0, gap, 100, gap);
            compare(a, b, FRAME, false);
            if (gap <= radius) assertFalse(predicate("certifiedNorthSeparation", a, b));
        }
        double below = 300_000_000;
        for (int index = 0; index < 8; index++) below = Math.nextDown(below);
        assertTrue(predicate("certifiedBranchSeparation", a, chord(below - 100, 0, below, 0)));
        for (double span : new double[] {300_000_000, Math.nextUp(300_000_000)}) {
            var b = chord(span - 100, 0, span, 0);
            assertFalse(predicate("certifiedBranchSeparation", a, b));
            compare(a, b, FRAME, false);
        }
        var east = chord(-150_000_000, 0, 150_000_000, 0);
        var north = chord(0, -150_000_000, 0, 150_000_000);
        assertTrue(scalar(east, "maxEastUpper") - scalar(east, "minEastLower")
                + scalar(north, "maxNorthUpper") - scalar(north, "minNorthLower") > 600_000_000);
        assertFalse(predicate("certifiedBranchSeparation", east, north));
    }

    @Test
    void preparedFrameKeepsExactShiftScaleAndRejectsForeignFrame() {
        var prepared = CertifiedContextSegmentClipper.prepareOriginalContactFrame(FRAME);
        BigDecimal turn = BigDecimal.valueOf(360).multiply(new BigDecimal(0x1.1df46a2529d39p-6))
                .multiply(new BigDecimal(FRAME.distortionCertificate().eastMetersPerRadian()));
        assertSame(FRAME, prepared.frame());
        assertEquals(turn.multiply(BigDecimal.valueOf(-1)), prepared.negativeShift());
        assertEquals(turn.multiply(BigDecimal.valueOf(0)), prepared.zeroShift());
        assertEquals(turn.multiply(BigDecimal.valueOf(1)), prepared.positiveShift());
        for (var b : List.of(chord(5, -1, 5, 1), chord(0, 100, 100, 100), chord(10, 0, 20, 0))) {
            var a = chord(0, 0, 10, 0);
            assertEquals(CertifiedContextSegmentClipper.originalContactEvidence(a, null, null, b, null, null, FRAME),
                    CertifiedContextSegmentClipper.originalContactEvidencePrepared(
                            a, null, null, b, null, null, FRAME, prepared));
            var foreign = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
                    new GeographicPoint(-.001, -.001), new GeographicPoint(.001, .001));
            assertThrows(IllegalArgumentException.class, () -> CertifiedContextSegmentClipper.originalContactEvidencePrepared(
                    a, null, null, b, null, null, foreign, prepared));
        }
    }

    @Test
    void publicMixedLongWayCensusRetainsEveryPairAndFrozenEvidence() throws Exception {
        var prepared = CertifiedContextSegmentClipper.prepareOriginalContactFrame(FRAME);
        int pairs = 0, northHits = 0, branchVisits = 0, branchHits = 0;
        // Eight changed edges against24 factual context occurrences, with far,
        // nearby, overlapping and exactly shared-coordinate populations.
        for (int selected = 0; selected < 8; selected++) {
            var a = chord(selected * 10, 0, selected * 10 + 10, 0);
            for (int context = 0; context < 24; context++) {
                double y = context < 12 ? 100 + context : context < 18 ? .1 : 0;
                var b = chord((context % 8) * 10, y, (context % 8) * 10 + 10, y);
                pairs++; compare(a, b, FRAME, context >= 18);
                if (predicate("certifiedNorthSeparation", a, b)) { northHits++; continue; }
                for (BigDecimal shift : List.of(prepared.negativeShift(), prepared.zeroShift(), prepared.positiveShift())) {
                    branchVisits++;
                    if (predicate("certifiedBranchSeparation", a, b.shifted(shift))) branchHits++;
                }
            }
        }
        assertEquals(192, pairs); assertEquals(96, northHits);
        assertTrue(branchHits > 0 && branchHits < branchVisits);
        System.out.println("P7_PUBLIC_CENSUS pairs=" + pairs + " originalCertificates=" + 200
                + " northHits=" + northHits + " branchVisits=" + branchVisits + " branchHits=" + branchHits
                + " branchFallbacks=" + (branchVisits - branchHits));
    }

    private static boolean predicate(String name, CertifiedContextSegmentClipper.ExactChord a,
            CertifiedContextSegmentClipper.ExactChord b) throws Exception {
        Method method = CertifiedContextSegmentClipper.class.getDeclaredMethod(name, a.getClass(), b.getClass());
        method.setAccessible(true); return (boolean) method.invoke(null, a, b);
    }
    private static double scalar(CertifiedContextSegmentClipper.ExactChord value, String name) throws Exception {
        var field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.getDouble(value);
    }
    private static void assertOutward(CertifiedContextSegmentClipper.ExactChord value) throws Exception {
        assertTrue(new BigDecimal(scalar(value, "minEastLower")).compareTo(
                value.start().east().min(value.end().east())) < 0);
        assertTrue(new BigDecimal(scalar(value, "maxEastUpper")).compareTo(
                value.start().east().max(value.end().east())) > 0);
        assertTrue(new BigDecimal(scalar(value, "minNorthLower")).compareTo(value.minNorth()) < 0);
        assertTrue(new BigDecimal(scalar(value, "maxNorthUpper")).compareTo(value.maxNorth()) > 0);
    }

    private static void compare(CertifiedContextSegmentClipper.ExactChord a,
            CertifiedContextSegmentClipper.ExactChord b, LocalMetricFrame frame, boolean shared) {
        PrimitiveKey a0 = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 1);
        PrimitiveKey a1 = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 2);
        PrimitiveKey b0 = shared ? a0 : PrimitiveKey.existing(PrimitiveKey.Type.NODE, 3);
        PrimitiveKey b1 = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 4);
        var actual = CertifiedContextSegmentClipper.originalContactEvidence(a, a0, a1, b, b0, b1, frame);
        var expected = FrozenCertifiedContextSegmentClipperReference.originalContactEvidence(
                frozen(a), a0, a1, frozen(b), b0, b1, frame);
        assertEquals(expected.properCrossing(), actual.properCrossing());
        assertEquals(expected.positiveOverlap(), actual.positiveOverlap());
        assertEquals(expected.uncertainty(), actual.uncertainty());
        assertEquals(expected.ownedExactIncidence(), actual.ownedExactIncidence());
        assertEquals(expected.witnesses().stream().map(Object::toString).toList(),
                actual.witnesses().stream().map(Object::toString).toList());
        assertEquals(expected.soleOwnedContact(), actual.soleOwnedContact());
        assertEquals(expected.displayDefect().name(), actual.displayDefect().name());
    }

    private static FrozenCertifiedContextSegmentClipperReference.ExactChord frozen(
            CertifiedContextSegmentClipper.ExactChord chord) {
        return new FrozenCertifiedContextSegmentClipperReference.ExactChord(
                new FrozenCertifiedContextSegmentClipperReference.ExactPoint(chord.start().east(), chord.start().north()),
                new FrozenCertifiedContextSegmentClipperReference.ExactPoint(chord.end().east(), chord.end().north()));
    }
    private static CertifiedContextSegmentClipper.ExactChord chord(double ax, double ay, double bx, double by) {
        return new CertifiedContextSegmentClipper.ExactChord(
                new CertifiedContextSegmentClipper.ExactPoint(new BigDecimal(ax), new BigDecimal(ay)),
                new CertifiedContextSegmentClipper.ExactPoint(new BigDecimal(bx), new BigDecimal(by)));
    }
    private static CertifiedContextSegmentClipper.ExactChord countedChord(double ax, double ay,
            double bx, double by, Counter counter) {
        return new CertifiedContextSegmentClipper.ExactChord(
                new CertifiedContextSegmentClipper.ExactPoint(new CountedDecimal(ax, counter), new CountedDecimal(ay, counter)),
                new CertifiedContextSegmentClipper.ExactPoint(new CountedDecimal(bx, counter), new CountedDecimal(by, counter)));
    }
    private static final class Counter { int products; int powers; int doubleReads; }
    private static final class CountedDecimal extends BigDecimal {
        private final Counter counter;
        CountedDecimal(double value, Counter counter) { super(value); this.counter = counter; }
        CountedDecimal(BigDecimal value, Counter counter) {
            super(value.unscaledValue(), value.scale()); this.counter = counter;
        }
        @Override public BigDecimal subtract(BigDecimal value) {
            return new CountedDecimal(super.subtract(value), counter);
        }
        @Override public BigDecimal pow(int exponent) {
            counter.powers++; return new CountedDecimal(super.pow(exponent), counter);
        }
        @Override public BigDecimal multiply(BigDecimal value) {
            counter.products++; return new CountedDecimal(super.multiply(value), counter);
        }
        @Override public double doubleValue() { counter.doubleReads++; return super.doubleValue(); }
    }
}
