package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Event materialization must depend on actual events without changing segment authority. */
class V022MetricRegionEventStorageTest {
    @Test
    void twoActualEventsFitWorkBudgetDespiteRemotePolygonInventory() {
        var region = remotePolygons();
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        AtomicLong units = new AtomicLong();
        LongConsumer admission = requested -> {
            if (requested > 800 - units.get()) throw new WorkRefusal();
            units.addAndGet(requested);
        };
        try {
            assertTrue(bounded(region, new MetricPoint(-1, 0), new MetricPoint(1, 0),
                    CancellationProbe.NONE, admission, owner));
            assertTrue(units.get() >= 500, "all 100 polygons and 400 edges still visit");
            assertTrue(units.get() <= 800);
            assertEquals(0L, ledger.currentBytes());
        } finally { owner.close(); }
    }

    @Test
    void twoActualEventsFitMemoryBudgetWithoutWorstCaseEventArray() {
        var ledger = new AttemptMemoryLedger(2048);
        var owner = ledger.rootOwner();
        try {
            assertTrue(bounded(remotePolygons(), new MetricPoint(-1, 0), new MetricPoint(1, 0),
                    CancellationProbe.NONE, ignored -> { }, owner));
            assertEquals(0L, ledger.currentBytes());
        } finally { owner.close(); }
    }

    @Test
    void remoteToleranceEventIsRetainedInBothDirections() {
        var region = new MetricRegion(List.of(
                MetricRegion.rectangle(-6, -1, -1.5e-9, 1).polygons().get(0),
                MetricRegion.rectangle(1.5e-9, -1, 6, 1).polygons().get(0),
                MetricRegion.rectangle(0, 5e-9, .1, 100).polygons().get(0)));
        assertFalse(region.contains(new MetricPoint(0, 0)));
        var withoutRemote = new MetricRegion(region.polygons().subList(0, 2));
        for (boolean reversed : List.of(false, true)) {
            var a = new MetricPoint(reversed ? 5 : -5, 0);
            var b = new MetricPoint(reversed ? -5 : 5, 0);
            assertFalse(withoutRemote.containsSegment(a, b));
            assertTrue(region.containsSegment(a, b));
            var ledger = AttemptMemoryLedger.production();
            var owner = ledger.rootOwner();
            try {
                assertTrue(bounded(region, a, b, CancellationProbe.NONE, ignored -> { }, owner));
                assertEquals(0L, ledger.currentBytes());
            } finally { owner.close(); }
        }
    }

    @Test
    void growthPreservesExactBoxedValuesInsertionOrderAndSort() throws Exception {
        for (int bound : new int[]{3, 5, 9, 33, 65}) {
            var ledger = AttemptMemoryLedger.production();
            var owner = ledger.rootOwner();
            try {
                var events = eventList(bound, CancellationProbe.NONE, ignored -> { }, owner);
                List<Double> expected = new ArrayList<>();
                for (int i = 0; i < bound; i++) {
                    double value = i == 0 ? -0.0 : i == 1 ? 0.0 : (bound - i) / (double) bound;
                    append(events, value);
                    expected.add(value);
                    assertEquals(expected, events);
                }
                java.util.Collections.sort(events);
                java.util.Collections.sort(expected);
                assertEquals(expected, events);
                assertThrows(IllegalArgumentException.class, () -> append(events, 0.7));
            } finally { owner.close(); }
            assertEquals(0L, ledger.currentBytes());
        }
    }

    @Test
    void lateGrowthReservesCoexistingBackingArraysBeforeAllocation() throws Exception {
        var ledger = new AttemptMemoryLedger(320);
        var owner = ledger.rootOwner();
        try {
            var events = eventList(5, CancellationProbe.NONE, ignored -> { }, owner);
            for (double value : new double[]{0, .2, .4, .6}) append(events, value);
            var failure = assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                    () -> append(events, .8));
            assertTrue(java.util.Arrays.stream(failure.getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals("grow")));
            assertEquals(List.of(0.0, .2, .4, .6), events);
        } finally { owner.close(); }
        assertEquals(0L, ledger.currentBytes());
    }

    @Test
    void copyingIsChargedBeforeReferenceReadAndWrite() throws Exception {
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        AtomicLong units = new AtomicLong();
        try {
            var events = eventList(5, CancellationProbe.NONE, requested -> {
                if (requested > 11 - units.get()) throw new WorkRefusal();
                units.addAndGet(requested);
            }, owner);
            append(events, 0); append(events, 1);
            var failure = assertThrows(WorkRefusal.class, () -> append(events, .5));
            assertEquals(10L, units.get());
            assertTrue(java.util.Arrays.stream(failure.getStackTrace())
                    .anyMatch(frame -> frame.getMethodName().equals("grow")));
            assertEquals(List.of(0.0, 1.0), events);
        } finally { owner.close(); }
        assertEquals(0L, ledger.currentBytes());
    }

    @Test
    void cancellationDuringCopyPreservesOldValuesAndClosesAllStorage() throws Exception {
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        AtomicLong units = new AtomicLong();
        try {
            var events = eventList(5, () -> units.get() >= 12, units::addAndGet, owner);
            append(events, 0); append(events, 1);
            assertThrows(CancellationException.class, () -> append(events, .5));
            assertEquals(List.of(0.0, 1.0), events);
        } finally { owner.close(); }
        assertEquals(0L, ledger.currentBytes());
    }

    @Test
    void sortMemoryAndCancellationRefusalsReleaseWholeQuery() {
        var region = joined();
        for (boolean cancel : List.of(false, true)) {
            var ledger = new AttemptMemoryLedger(cancel ? 4096 : 550);
            var owner = ledger.rootOwner();
            try {
                CancellationProbe probe = cancel ? () -> java.util.Arrays.stream(
                        Thread.currentThread().getStackTrace()).anyMatch(frame ->
                            frame.getClassName().endsWith("BoundedEventList")
                                && frame.getMethodName().equals("sort")) : CancellationProbe.NONE;
                Class<? extends RuntimeException> type = cancel ? CancellationException.class
                        : AttemptMemoryLedger.ResourceLimitException.class;
                var failure = assertThrows(type, () -> bounded(region, new MetricPoint(-4, 0),
                        new MetricPoint(4, 0), probe, ignored -> { }, owner));
                assertTrue(java.util.Arrays.stream(failure.getStackTrace())
                        .anyMatch(frame -> frame.getMethodName().equals("sort")));
                assertEquals(0L, ledger.currentBytes());
            } finally { owner.close(); }
        }
    }

    @Test
    void lateSortWorkRefusalReleasesWholeQuery() {
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        try {
            assertThrows(WorkRefusal.class, () -> bounded(joined(), new MetricPoint(-4, 0),
                    new MetricPoint(4, 0), CancellationProbe.NONE, requested -> {
                        if (requested == 12) throw new WorkRefusal(); // Four events' original sort proxy.
                    }, owner));
            assertEquals(0L, ledger.currentBytes());
        } finally { owner.close(); }
    }

    @Test
    void exactAuthorityMatchesFrozenHistoricalPredicateAcrossGeometryAndReversal() {
        var notch = new MetricRegion(List.of(List.of(new MetricPoint(-5, -2), new MetricPoint(5, -2),
                new MetricPoint(5, 2), new MetricPoint(.1, 2), new MetricPoint(.1, -.1),
                new MetricPoint(-.1, -.1), new MetricPoint(-.1, 2), new MetricPoint(-5, 2))));
        var split = new MetricRegion(List.of(MetricRegion.rectangle(-5, -2, -.1, 2).polygons().get(0),
                MetricRegion.rectangle(.1, -2, 5, 2).polygons().get(0)));
        var random = new java.util.Random(0x1022);
        for (var region : List.of(joined(), notch, split, remotePolygons(),
                MetricRegion.rectangle(-1e100, -1e100, 1e100, 1e100))) {
            var ledger = AttemptMemoryLedger.production();
            var owner = ledger.rootOwner();
            try {
                for (int i = 0; i < 200; i++) {
                    var a = new MetricPoint(random.nextDouble()*12-6, random.nextDouble()*6-3);
                    var b = i%4 == 0 ? a : new MetricPoint(random.nextDouble()*12-6, random.nextDouble()*6-3);
                    for (boolean reverse : List.of(false, true)) {
                        var start = reverse ? b : a; var end = reverse ? a : b;
                        assertEquals(V022MetricRegionSegmentWorkTest.historicalSegment(region, start, end),
                                bounded(region, start, end, CancellationProbe.NONE, ignored -> { }, owner));
                        assertEquals(0L, ledger.currentBytes());
                    }
                }
                for (double y : new double[]{-2, -2-1e-9, Math.nextDown(-2-1e-9), -0.0,
                        Double.MIN_VALUE, 2, 2+1e-9, Math.nextUp(2+1e-9)}) {
                    var a = new MetricPoint(-4, y); var b = new MetricPoint(4, y);
                    assertEquals(V022MetricRegionSegmentWorkTest.historicalSegment(region, a, b),
                            bounded(region, a, b, CancellationProbe.NONE, ignored -> { }, owner));
                }
            } finally { owner.close(); }
        }
    }

    private static MetricRegion joined() {
        return new MetricRegion(List.of(MetricRegion.rectangle(-5,-2,1,2).polygons().get(0),
                MetricRegion.rectangle(-1,-2,5,2).polygons().get(0)));
    }

    @SuppressWarnings("unchecked")
    private static List<Double> eventList(int bound, CancellationProbe cancellation,
            LongConsumer admission, AttemptMemoryLedger.Owner owner) throws Exception {
        owner.reserve(AttemptMemoryLedger.objectBytes(56) + AttemptMemoryLedger.objectBytes(16));
        var type = Class.forName(MetricRegion.class.getName()+"$BoundedEventList");
        var constructor = type.getDeclaredConstructor(int.class, CancellationProbe.class,
                LongConsumer.class, AttemptMemoryLedger.Owner.class);
        constructor.setAccessible(true);
        return (List<Double>) constructor.newInstance(bound, cancellation, admission, owner);
    }

    private static void append(List<Double> events, double value) {
        try {
            var method = events.getClass().getDeclaredMethod("append", double.class);
            method.setAccessible(true); method.invoke(events, value);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static MetricRegion remotePolygons() {
        List<List<MetricPoint>> polygons = new ArrayList<>();
        polygons.add(MetricRegion.rectangle(-5, -2, 5, 2).polygons().get(0));
        for (int i = 1; i < 100; i++)
            polygons.add(MetricRegion.rectangle(-5, 10 + i * 4, 5, 12 + i * 4).polygons().get(0));
        return new MetricRegion(polygons);
    }

    private static boolean bounded(MetricRegion region, MetricPoint a, MetricPoint b,
            CancellationProbe cancellation, LongConsumer admission, AttemptMemoryLedger.Owner owner) {
        return region.containsSegmentWithBoundedEvents(a, b, cancellation, admission, owner);
    }

    private static final class WorkRefusal extends RuntimeException { }
}
