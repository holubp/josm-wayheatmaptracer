package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;

class V022ContextChordCacheTest {
    private static final long BUDGET = 256L * 1024L;
    private static final LocalMetricFrame FRAME = LocalMetricFrame.certifiedEquirectangular(
            geo(0, 0), geo(-0.001, -0.001), geo(0.001, 0.001));

    @Test
    void repeatedNaturalOccurrenceReusesOneImmutableChordWithinBound() {
        Context context = context(List.of(geo(0, 0), geo(0.0001, 0.0001)));
        var cache = cache(context, FRAME, BUDGET);
        long emptyBytes = cache.retainedBytes();
        var first = cache.chord(0);
        assertSame(first, cache.chord(0));
        long charge = independentChordBound(first);
        assertEquals(emptyBytes + charge, cache.retainedBytes());
        assertTrue(cache.retainedBytes() <= BUDGET);
        assertEquals(1, cache.slotCount());
    }

    @Test
    void zeroTinyAndInsufficientPayloadBudgetsNeverRetainAChord() {
        Context context = context(List.of(geo(Double.MIN_VALUE, Double.MIN_VALUE),
                geo(2 * Double.MIN_VALUE, 2 * Double.MIN_VALUE)));
        assertNull(cache(context, FRAME, 0));
        assertNull(cache(context, FRAME, 1));
        var candidate = CertifiedContextSegmentClipper.originalChord(
                context.coordinates().get(0), context.coordinates().get(1), FRAME);
        long overhead = AttemptMemoryLedger.objectBytes(48)
                + AttemptMemoryLedger.referenceArrayBytes(1);
        long budget = overhead + independentChordBound(candidate) - 1;
        assertTrue(budget < BUDGET);
        var limited = cache(context, FRAME, budget);
        long before = limited.retainedBytes();
        assertNotSame(limited.chord(0), limited.chord(0));
        assertEquals(before, limited.retainedBytes());
        assertTrue(limited.retainedBytes() <= budget);
    }

    @Test
    void capacityOverflowAndMissesStillCompareEveryPairToFrozenFullEvidence() {
        List<GeographicPoint> points = new ArrayList<>();
        for (int i = 0; i < 133; i++) points.add(geo(0.0002, -0.0005 + i * 0.00001));
        Context context = context(points);
        var cache = cache(context, FRAME, BUDGET);
        assertEquals(128, cache.slotCount());
        assertNotSame(cache.chord(128), cache.chord(128));
        for (long budget : new long[] {BUDGET, 0, 1, 1_104}) {
            var bounded = cache(context, FRAME, budget);
            int comparisons = 0;
            for (double north : new double[] {-0.0002, 0.0002, 0.0004}) {
                var selected = CertifiedContextSegmentClipper.originalChord(
                        geo(north, -0.0005), geo(north, 0.001), FRAME);
                for (int j = 0; j < points.size() - 1; j++) {
                    var chord = bounded == null ? CertifiedContextSegmentClipper.originalChord(
                            points.get(j), points.get(j + 1), FRAME) : bounded.chord(j);
                    var actual = CertifiedContextSegmentClipper.originalContactEvidence(selected,
                            null, null, chord, null, null, FRAME);
                    var expected = FrozenCertifiedContextSegmentClipperReference.originalContactEvidence(
                            frozen(selected), null, null,
                            FrozenCertifiedContextSegmentClipperReference.originalChord(points.get(j),
                                    points.get(j + 1), FRAME), null, null, FRAME);
                    assertEquals(signature(expected), signature(actual));
                    comparisons++;
                }
            }
            assertEquals(3 * 132, comparisons);
            if (bounded != null) assertTrue(bounded.retainedBytes() <= budget);
        }
        assertTrue(cache.retainedBytes() <= BUDGET);
    }

    @Test
    void fullByteBudgetKeepsEarlierEntryAndRecomputesEveryLaterMiss() {
        Context context = context(List.of(geo(0, 0), geo(0.0001, 0.0001), geo(0.0002, 0.0002)));
        var first = CertifiedContextSegmentClipper.originalChord(
                context.coordinates().get(0), context.coordinates().get(1), FRAME);
        long budget = AttemptMemoryLedger.objectBytes(48)
                + AttemptMemoryLedger.referenceArrayBytes(2) + independentChordBound(first);
        var cache = cache(context, FRAME, budget);
        var retained = cache.chord(0);
        assertSame(retained, cache.chord(0));
        assertEquals(budget, cache.retainedBytes());
        assertNotSame(cache.chord(1), cache.chord(1));
        assertEquals(budget, cache.retainedBytes());
    }

    @Test
    void eachInvocationBindsItsOwnCoordinatesAndFrame() {
        Context original = context(List.of(geo(0, 0), geo(0.0001, 0.0001)));
        Context changed = context(List.of(geo(0, 0), geo(0.0002, 0.0001)));
        var first = cache(original, FRAME, BUDGET).chord(0);
        var fresh = cache(original, FRAME, BUDGET).chord(0);
        assertNotSame(first, fresh);
        assertEquals(first.end(), fresh.end());
        var moved = cache(changed, FRAME, BUDGET).chord(0);
        assertNotEquals(first.end().north(), moved.end().north());
        LocalMetricFrame otherFrame = LocalMetricFrame.certifiedEquirectangular(
                geo(0.0001, 0), geo(-0.001, -0.001), geo(0.001, 0.001));
        var reframed = cache(original, otherFrame, BUDGET).chord(0);
        assertNotEquals(first.start().north(), reframed.start().north());
    }

    @Test
    void actualContactsRefusesEarlyBeforeLaterAmbiguousArcButThrowsWhenReached() throws Exception {
        Context selected = context(List.of(geo(0, -0.0001), geo(0, 0.0001)));
        Context context = context(List.of(geo(0, -0.00005), geo(0, 0), geo(0, 180)));
        assertNull(contacts(selected, context));
        Context reversed = context(List.of(context.coordinates().get(2), context.coordinates().get(1),
                context.coordinates().get(0)));
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> contacts(selected, reversed));
        assertInstanceOf(CertifiedContextSegmentClipper.InvalidContextGeometryException.class,
                failure.getCause());
    }

    private static Object contacts(Context selected, Context context) throws Exception {
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>(selected.values());
        values.putAll(context.values());
        var method = InheritedNonTransportContacts.class.getDeclaredMethod("contacts",
                DetachedWay.class, OccurrenceRange.class, DetachedWay.class, Map.class, EvidenceSnapshot.class);
        method.setAccessible(true);
        return method.invoke(null, selected.way(), new OccurrenceRange(0, 1), context.way(),
                Map.copyOf(values), evidence());
    }

    private static EvidenceSnapshot evidence() {
        double[] pixels = new double[16];
        boolean[] valid = new boolean[16];
        Arrays.fill(valid, true);
        var field = new ScalarEvidenceField(4, 4, pixels, valid, new EvidenceFieldLineage(
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "public-cache-fixture",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        var region = MetricRegion.rectangle(-100, -100, 100, 100);
        var transform = new RasterMetricTransform("public-cache-fixture",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(-150, -150), 100, 0, 0, 100, 1);
        return new EvidenceSnapshot("public-cache-fixture", FRAME, transform,
                EvidenceResolution.nativeSource(100, 100), region, region, Map.of("scalar", field), "public");
    }

    private static long independentChordBound(CertifiedContextSegmentClipper.ExactChord chord) {
        long total = 56 + 2 * 32;
        for (BigDecimal value : List.of(chord.start().east(), chord.start().north(),
                chord.end().east(), chord.end().north(), chord.lengthSquared(),
                chord.minNorth(), chord.maxNorth())) {
            long words = ((long) value.unscaledValue().bitLength() + 1 + 31) / 32;
            total += 48 + 48 + ((16 + 4 * words + 7) / 8) * 8;
        }
        return total;
    }

    private static InheritedNonTransportContacts.ContextChordCache cache(Context context,
            LocalMetricFrame frame, long budget) {
        return InheritedNonTransportContacts.ContextChordCache.create(
                context.way(), context.values(), frame, budget);
    }

    private record Context(DetachedWay way, Map<PrimitiveKey, DetachedPrimitive> values,
            List<GeographicPoint> coordinates) { }

    private static Context context(List<GeographicPoint> points) {
        long base = points.hashCode() & 0x7fffffffL;
        List<PrimitiveKey> keys = new ArrayList<>();
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>();
        for (int i = 0; i < points.size(); i++) {
            PrimitiveKey key = PrimitiveKey.existing(PrimitiveKey.Type.NODE, base * 1000 + i + 1);
            keys.add(key);
            values.put(key, new DetachedNode(key, points.get(i), Map.of(), false, false));
        }
        return new Context(new DetachedWay(PrimitiveKey.existing(PrimitiveKey.Type.WAY, base + 1),
                keys, Map.of(), false, false), Map.copyOf(values), List.copyOf(points));
    }

    private static GeographicPoint geo(double latitude, double longitude) {
        return new GeographicPoint(latitude, longitude);
    }

    private static FrozenCertifiedContextSegmentClipperReference.ExactChord frozen(
            CertifiedContextSegmentClipper.ExactChord chord) {
        return new FrozenCertifiedContextSegmentClipperReference.ExactChord(
                new FrozenCertifiedContextSegmentClipperReference.ExactPoint(chord.start().east(), chord.start().north()),
                new FrozenCertifiedContextSegmentClipperReference.ExactPoint(chord.end().east(), chord.end().north()));
    }

    private static String signature(Object evidence) {
        return evidence.toString(); // Both records preserve every flag and ordered witness field.
    }
}
