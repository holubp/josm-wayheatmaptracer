package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;

/** Exact output oracle frozen before invariant reuse; deliberately independent of production helpers. */
class V022CertifiedContextInvariantReuseTest {
    private static final LocalMetricFrame FRAME = LocalMetricFrame.certifiedEquirectangular(
            geo(0, 0), geo(-0.001, -0.001), geo(0.001, 0.001));

    @Test
    void longitudeTranslationReusesImmutableChordInvariants() {
        var original = chord("1E-100", "-2.7", "1234.000000000000001", "3.1");
        for (String shift : List.of("-40075000.123456789", "0", "40075000.123456789")) {
            var shifted = original.shifted(new BigDecimal(shift));
            var frozen = frozenChord(shifted.start(), shifted.end());
            assertEquals(0, original.lengthSquared().compareTo(frozen.lengthSquared()),
                    "unlimited-precision translation preserves the exact squared length");
            assertSame(original.lengthSquared(), shifted.lengthSquared(),
                    "translation must not recompute the immutable length invariant");
            assertSame(original.minNorth(), shifted.minNorth());
            assertSame(original.maxNorth(), shifted.maxNorth());
        }
    }

    @Test
    void everyEvidenceFlagAndOrderedWitnessMatchesFrozenPredicate() {
        List<CertifiedContextSegmentClipper.ExactChord> contexts = List.of(
                chord("0", "0", "0", "1"),
                chord("0", "0", "1", "0.000000001"),
                chord("0", "0", "-1", "0.000000001"),
                chord("0", "0", "0.0000001", "0"),
                chord("0", "0", "0", "0"),
                chord("0.5", "0", "0.5", "1"),
                chord("0.5", "0.000000001", "0.5", "1"),
                chord("0.5", "0.50000025", "0", "0"),
                chord("-1", "1", "2", "1"));
        var east = chord("0", "0", "1", "0");
        var diagonal = chord("0", "0", "1", "1");
        for (var first : List.of(east, diagonal)) {
            for (var second : contexts) compareEveryOrder(first, second, FRAME);
        }
        compareEveryOrder(chord("1E-100", "-2.7", "1234.000000000000001", "3.1"),
                chord("-987.000000000000001", "1E-100", "100", "1E-99"), FRAME);
        // Exact binary64 boundary neighbours, not rounded decimal approximations.
        for (double gap : new double[] {Math.nextDown(1e-8), 1e-8, Math.nextUp(1e-8),
                Math.nextDown(1e-5), 1e-5, Math.nextUp(1e-5)}) {
            var context = new CertifiedContextSegmentClipper.ExactChord(
                    new CertifiedContextSegmentClipper.ExactPoint(BigDecimal.ZERO, new BigDecimal(gap)),
                    new CertifiedContextSegmentClipper.ExactPoint(BigDecimal.ONE, new BigDecimal(gap)));
            compareEveryOrder(east, context, FRAME);
        }
    }

    @Test
    void geographicBranchesDistortionAndClippingMatchFrozenPredicate() {
        List<LocalMetricFrame> frames = List.of(FRAME,
                LocalMetricFrame.certifiedEquirectangular(geo(50, 14),
                        geo(49.999, 13.999), geo(50.001, 14.001)),
                LocalMetricFrame.certifiedEquirectangular(geo(0, 180),
                        geo(-0.001, 179.999), geo(0.001, -179.999)));
        for (LocalMetricFrame frame : frames) {
            double latitude = frame.origin().latitudeDegrees();
            double longitude = frame.origin().longitudeDegrees();
            GeographicPoint a = geo(latitude - 0.0005, longitude);
            GeographicPoint b = geo(latitude + 0.0005, longitude);
            GeographicPoint c = geo(latitude, longitude - 0.01);
            GeographicPoint d = geo(latitude, longitude == 180 ? -179.99 : longitude + 0.01);
            var first = CertifiedContextSegmentClipper.originalChord(a, b, frame);
            var second = CertifiedContextSegmentClipper.originalChord(c, d, frame);
            var frozenFirst = FrozenCertifiedContextSegmentClipperReference.originalChord(a, b, frame);
            var frozenSecond = FrozenCertifiedContextSegmentClipperReference.originalChord(c, d, frame);
            assertEquals(frozenFirst.start().east(), first.start().east());
            assertEquals(frozenFirst.end().north(), first.end().north());
            assertEquals(frozenSecond.lengthSquared(), second.lengthSquared());
            compareEveryOrder(first, second, frame);
            for (GeographicPoint[] edge : List.of(new GeographicPoint[] {a, b},
                    new GeographicPoint[] {c, d},
                    new GeographicPoint[] {frame.distortionCertificate().southWest(),
                            frame.distortionCertificate().northEast()},
                    new GeographicPoint[] {geo(latitude + 0.002, longitude),
                            geo(latitude + 0.002, longitude - 0.002)},
                    new GeographicPoint[] {geo(Math.nextDown(latitude + 0.001), longitude),
                            geo(Math.nextUp(latitude + 0.001), longitude)})) {
                assertEquals(outcome(() -> FrozenCertifiedContextSegmentClipperReference.clip(
                                edge[0], edge[1], frame)),
                        outcome(() -> CertifiedContextSegmentClipper.clip(edge[0], edge[1], frame)));
                assertEquals(outcome(() -> FrozenCertifiedContextSegmentClipperReference.clip(
                                edge[1], edge[0], frame)),
                        outcome(() -> CertifiedContextSegmentClipper.clip(edge[1], edge[0], frame)));
            }
        }
        assertEquals(outcome(() -> FrozenCertifiedContextSegmentClipperReference.originalChord(
                        geo(0, 0), geo(0, 180), FRAME)),
                outcome(() -> CertifiedContextSegmentClipper.originalChord(geo(0, 0), geo(0, 180), FRAME)));
    }

    private static void compareEveryOrder(CertifiedContextSegmentClipper.ExactChord first,
            CertifiedContextSegmentClipper.ExactChord second, LocalMetricFrame frame) {
        for (boolean reverseFirst : new boolean[] {false, true}) {
            for (boolean reverseSecond : new boolean[] {false, true}) {
                var a = reverseFirst ? reversed(first) : first;
                var b = reverseSecond ? reversed(second) : second;
                for (boolean shared : new boolean[] {false, true}) {
                    PrimitiveKey aStart = key(reverseFirst ? 11 : 10);
                    PrimitiveKey aEnd = key(reverseFirst ? 10 : 11);
                    PrimitiveKey bStart = key(reverseSecond ? 21 : shared ? 10 : 20);
                    PrimitiveKey bEnd = key(reverseSecond ? shared ? 10 : 20 : 21);
                    compare(a, aStart, aEnd, b, bStart, bEnd, frame);
                    compare(b, bStart, bEnd, a, aStart, aEnd, frame);
                }
            }
        }
    }

    private static void compare(CertifiedContextSegmentClipper.ExactChord a, PrimitiveKey aStart,
            PrimitiveKey aEnd, CertifiedContextSegmentClipper.ExactChord b, PrimitiveKey bStart,
            PrimitiveKey bEnd, LocalMetricFrame frame) {
        var actual = CertifiedContextSegmentClipper.originalContactEvidence(a, aStart, aEnd,
                b, bStart, bEnd, frame);
        var expected = FrozenCertifiedContextSegmentClipperReference.originalContactEvidence(
                frozenChord(a.start(), a.end()), aStart, aEnd,
                frozenChord(b.start(), b.end()), bStart, bEnd, frame);
        assertEquals(expected.properCrossing(), actual.properCrossing());
        assertEquals(expected.positiveOverlap(), actual.positiveOverlap());
        assertEquals(expected.uncertainty(), actual.uncertainty());
        assertEquals(expected.ownedExactIncidence(), actual.ownedExactIncidence());
        assertEquals(expected.witnesses().stream().map(w -> new Witness(w.branchShift(),
                        w.endpoint().name(), w.kind().name(), w.owned())).toList(),
                actual.witnesses().stream().map(w -> new Witness(w.branchShift(),
                        w.endpoint().name(), w.kind().name(), w.owned())).toList());
        assertEquals(expected.soleOwnedContact(), actual.soleOwnedContact());
    }

    private record Witness(int branchShift, String endpoint, String kind, boolean owned) { }

    private static String outcome(Supplier<?> call) {
        try {
            // Optional clipping records have exactly equal field toString across the two classes.
            return call.get().toString();
        } catch (IllegalArgumentException invalid) {
            return invalid.getClass().getSimpleName() + ":" + invalid.getMessage();
        }
    }

    private static CertifiedContextSegmentClipper.ExactChord chord(String ax, String ay,
            String bx, String by) {
        return new CertifiedContextSegmentClipper.ExactChord(
                new CertifiedContextSegmentClipper.ExactPoint(new BigDecimal(ax), new BigDecimal(ay)),
                new CertifiedContextSegmentClipper.ExactPoint(new BigDecimal(bx), new BigDecimal(by)));
    }

    private static CertifiedContextSegmentClipper.ExactChord reversed(
            CertifiedContextSegmentClipper.ExactChord chord) {
        return new CertifiedContextSegmentClipper.ExactChord(chord.end(), chord.start());
    }

    private static FrozenCertifiedContextSegmentClipperReference.ExactChord frozenChord(
            CertifiedContextSegmentClipper.ExactPoint a, CertifiedContextSegmentClipper.ExactPoint b) {
        return new FrozenCertifiedContextSegmentClipperReference.ExactChord(
                new FrozenCertifiedContextSegmentClipperReference.ExactPoint(a.east(), a.north()),
                new FrozenCertifiedContextSegmentClipperReference.ExactPoint(b.east(), b.north()));
    }

    private static GeographicPoint geo(double latitude, double longitude) {
        return new GeographicPoint(latitude, longitude);
    }

    private static PrimitiveKey key(long id) {
        return PrimitiveKey.existing(PrimitiveKey.Type.NODE, id);
    }
}
