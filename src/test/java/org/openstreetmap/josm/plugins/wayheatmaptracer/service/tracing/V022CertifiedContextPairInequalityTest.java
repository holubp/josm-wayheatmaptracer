package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;

class V022CertifiedContextPairInequalityTest {
    // Independently expanded from (1e-8 + 2*5e-8)^2*8, not read from production.
    private static final BigDecimal C = new BigDecimal("0.0000000000000968");

    @Test
    void twoChordComparisonUsesOneGapExpressionProduct() throws Exception {
        for (boolean reverse : new boolean[] {false, true}) {
            Counter counter = new Counter();
            BigDecimal gap = new CountedDecimal(new BigDecimal("2"), counter);
            var first = chord(reverse ? "2" : "1");
            var second = chord(reverse ? "1" : "2");
            assertTrue(beyondBoth(gap, first, second, BigDecimal.ONE));
            assertEquals(1, counter.products,
                    "exact conjunction must use one final gap-expression product, not both chord products");
        }
    }

    @Test
    void scalarBoundariesMatchIndependentExpandedIntegerInequalities() throws Exception {
        BigDecimal one = BigDecimal.ONE;
        BigDecimal two = new BigDecimal("2");
        BigDecimal four = new BigDecimal("4");
        BigDecimal tiny = new BigDecimal("1E-1000");
        // D<0, D=0, D>0 with T=0; strict equality, either shorter operand,
        // and same numerical value with deliberately different decimal scales.
        List<BigDecimal[]> cases = List.of(
                values(C.subtract(tiny), one, four, BigDecimal.ZERO),
                values(C, one, four, BigDecimal.ZERO),
                values(C.add(tiny), one, four, BigDecimal.ZERO),
                values(C.add(one), one, four, two),
                values(C.add(one), four, one, two),
                values(C.add(one), two, four, two),
                values(C.add(one).add(tiny), two, four, two),
                values(C.add(one).subtract(tiny), two, four, two),
                values(C.add(one), new BigDecimal("2.00000"), new BigDecimal("2.0"), two),
                values(C.add(new BigDecimal("1E-3222")), new BigDecimal("1E-6444"), one, BigDecimal.ZERO),
                values(new BigDecimal("1E1000"), new BigDecimal("1E1000"), one, new BigDecimal("1E999")));
        for (BigDecimal[] row : cases) {
            assertEquals(expandedIntegerOracle(row[0], row[1], row[2], row[3]),
                    beyondSquared(row[0], row[1], row[2], row[3]));
            assertEquals(expandedIntegerOracle(row[0], row[2], row[1], row[3]),
                    beyondSquared(row[0], row[2], row[1], row[3]));
        }
    }

    @Test
    void actualGapGuardsAndBinary64BoundaryNeighborsMatchExpandedOracle() throws Exception {
        for (String gap : List.of("-1", "0", "1E-1000")) {
            assertEquals(false, beyondBoth(new BigDecimal(gap), chord("1"), chord("2"), BigDecimal.ONE));
        }
        for (String gap : List.of("-1", "0", "100")) {
            assertEquals(false, beyondBoth(new BigDecimal(gap), chord("0"), chord("2"), BigDecimal.ONE));
            assertEquals(false, beyondBoth(new BigDecimal(gap), chord("2"), chord("0"), BigDecimal.ONE));
        }
        double boundary = StrictMath.sqrt(2 + C.doubleValue());
        for (double gap : new double[] {Math.nextDown(boundary), boundary, Math.nextUp(boundary)}) {
            BigDecimal exactGap = new BigDecimal(gap);
            boolean expected = expandedIntegerOracle(exactGap.pow(2), BigDecimal.ONE,
                    new BigDecimal("4"), new BigDecimal("2"));
            assertEquals(expected, beyondBoth(exactGap, chord("1"), chord("2"), BigDecimal.ONE));
            assertEquals(expected, beyondBoth(exactGap, chord("2"), chord("1"), BigDecimal.ONE));
        }
    }

    @Test
    void subnormalAndRemoteFiniteGeographicChordsKeepEveryFrozenEvidenceField() {
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(
                geo(0, 0), geo(-0.001, -0.001), geo(0.001, 0.001));
        List<GeographicPoint[]> edges = List.of(
                new GeographicPoint[] {geo(0, 0), geo(Double.MIN_VALUE, Double.MIN_VALUE)},
                new GeographicPoint[] {geo(Double.MIN_VALUE, 0), geo(0, Double.MIN_VALUE)},
                new GeographicPoint[] {geo(-89.99, -179.99), geo(89.99, 179.99)},
                new GeographicPoint[] {geo(0.0005, -0.0005), geo(0.0005, 0.0005)},
                new GeographicPoint[] {geo(0, 179.99), geo(0, -179.99)},
                new GeographicPoint[] {geo(0, 0), geo(0, 0)});
        for (GeographicPoint[] first : edges) for (GeographicPoint[] second : edges) {
            var a = CertifiedContextSegmentClipper.originalChord(first[0], first[1], frame);
            var b = CertifiedContextSegmentClipper.originalChord(second[0], second[1], frame);
            var oldA = FrozenCertifiedContextSegmentClipperReference.originalChord(first[0], first[1], frame);
            var oldB = FrozenCertifiedContextSegmentClipperReference.originalChord(second[0], second[1], frame);
            var actual = CertifiedContextSegmentClipper.originalContactEvidence(a, null, null, b, null, null, frame);
            var expected = FrozenCertifiedContextSegmentClipperReference.originalContactEvidence(
                    oldA, null, null, oldB, null, null, frame);
            assertEquals(expected.properCrossing(), actual.properCrossing());
            assertEquals(expected.positiveOverlap(), actual.positiveOverlap());
            assertEquals(expected.uncertainty(), actual.uncertainty());
            assertEquals(expected.ownedExactIncidence(), actual.ownedExactIncidence());
            assertEquals(expected.soleOwnedContact(), actual.soleOwnedContact());
            assertEquals(expected.witnesses().stream().map(Object::toString).toList(),
                    actual.witnesses().stream().map(Object::toString).toList());
        }
    }

    private static BigDecimal[] values(BigDecimal gapSquared, BigDecimal firstLength,
            BigDecimal secondLength, BigDecimal twiceAreaSquared) {
        return new BigDecimal[] {gapSquared, firstLength, secondLength, twiceAreaSquared};
    }

    /** Original expanded inequalities in common-scale integers, with no factored expression. */
    private static boolean expandedIntegerOracle(BigDecimal gapSquared, BigDecimal firstLength,
            BigDecimal secondLength, BigDecimal twiceAreaSquared) {
        int scale = Math.max(C.scale(), Math.max(gapSquared.scale(), Math.max(firstLength.scale(),
                Math.max(secondLength.scale(), twiceAreaSquared.scale()))));
        BigInteger unit = BigInteger.TEN.pow(scale);
        BigInteger g = gapSquared.setScale(scale).unscaledValue();
        BigInteger a = firstLength.setScale(scale).unscaledValue();
        BigInteger b = secondLength.setScale(scale).unscaledValue();
        BigInteger t = twiceAreaSquared.setScale(scale).unscaledValue();
        BigInteger c = C.setScale(scale).unscaledValue();
        return g.multiply(a).compareTo(t.multiply(unit).add(c.multiply(a))) > 0
                && g.multiply(b).compareTo(t.multiply(unit).add(c.multiply(b))) > 0;
    }

    private static boolean beyondSquared(BigDecimal gapSquared, BigDecimal firstLength,
            BigDecimal secondLength, BigDecimal twiceAreaSquared) throws Exception {
        Method method = CertifiedContextSegmentClipper.class.getDeclaredMethod("beyondSquared",
                BigDecimal.class, BigDecimal.class, BigDecimal.class, BigDecimal.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, gapSquared, firstLength, secondLength, twiceAreaSquared);
    }

    private static GeographicPoint geo(double latitude, double longitude) {
        return new GeographicPoint(latitude, longitude);
    }

    private static boolean beyondBoth(BigDecimal gap,
            CertifiedContextSegmentClipper.ExactChord first,
            CertifiedContextSegmentClipper.ExactChord second, BigDecimal area) throws Exception {
        Method method = CertifiedContextSegmentClipper.class.getDeclaredMethod("beyondBoth",
                BigDecimal.class, CertifiedContextSegmentClipper.ExactChord.class,
                CertifiedContextSegmentClipper.ExactChord.class, BigDecimal.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, gap, first, second, area);
    }

    private static CertifiedContextSegmentClipper.ExactChord chord(String length) {
        return new CertifiedContextSegmentClipper.ExactChord(
                new CertifiedContextSegmentClipper.ExactPoint(BigDecimal.ZERO, BigDecimal.ZERO),
                new CertifiedContextSegmentClipper.ExactPoint(new BigDecimal(length), BigDecimal.ZERO));
    }

    private static final class Counter { private int products; }

    /** Test-only exact numerical subtype: observe executed arithmetic, never elapsed time. */
    private static final class CountedDecimal extends BigDecimal {
        private final Counter counter;
        CountedDecimal(BigDecimal value, Counter counter) {
            super(value.unscaledValue(), value.scale());
            this.counter = counter;
        }
        @Override public BigDecimal pow(int exponent) {
            return new CountedDecimal(super.pow(exponent), counter);
        }
        @Override public BigDecimal subtract(BigDecimal other) {
            return new CountedDecimal(super.subtract(other), counter);
        }
        @Override public BigDecimal multiply(BigDecimal other) {
            counter.products++;
            return new CountedDecimal(super.multiply(other), counter);
        }
    }
}
