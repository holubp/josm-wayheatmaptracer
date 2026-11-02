package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** An optional strict authority predicate must refuse geometry it cannot resolve. */
class V022MetricRegionConservativeEventsTest {
    @Test
    void representableNarrowUnionGapCannotDisappearBetweenDistinctEvents() {
        MetricRegion region = new MetricRegion(List.of(
            List.of(p(0, 0), p(20 - .5e-9, 0), p(20 - .5e-9, 63), p(0, 63)),
            List.of(p(20 + .5e-9, 0), p(63, 0), p(63, 63), p(20 + .5e-9, 63))));
        assertFalse(conservative(region, p(10, 32), p(30, 32)));
        assertFalse(conservative(region, p(30, 32), p(10, 32)));
    }

    @Test
    void pointJustOutsideOldBoundaryToleranceIsNotAuthority() {
        MetricRegion region = MetricRegion.rectangle(0, 0, 30, 30);
        MetricPoint outside = p(-.5e-9, 15);
        assertTrue(region.contains(outside), "old tolerant boundary behavior is retained");
        assertFalse(region.containsConservatively(outside, CancellationProbe.NONE, ignored -> { }));
        assertFalse(conservative(region, outside, p(15, 15)));
    }

    @Test
    void overlappingNearParallelEdgeCannotBeSkippedAsParallel() {
        MetricRegion region = new MetricRegion(List.of(List.of(
            p(0, -2), p(20, -2), p(20, 2), p(10.01, 2),
            p(10.01, 5e-12), p(10, 0), p(10, 2), p(0, 2))));
        assertTrue(region.containsConservatively(p(5, 0), CancellationProbe.NONE, ignored -> { }));
        assertTrue(region.containsConservatively(p(15, 0), CancellationProbe.NONE, ignored -> { }));
        assertFalse(conservative(region, p(5, 0), p(15, 0)));
    }

    @Test
    void interiorExactAxisBoundaryAndDuplicateUnionEventsRemainUsable() {
        MetricRegion rectangle = MetricRegion.rectangle(0, 0, 30, 30);
        assertTrue(conservative(rectangle, p(5, 15), p(25, 15)));
        assertTrue(conservative(rectangle, p(5, 0), p(25, 0)));
        assertTrue(rectangle.containsConservatively(p(0, 15), CancellationProbe.NONE, ignored -> { }));
        var union = new MetricRegion(List.of(
            MetricRegion.rectangle(0, 0, 20, 30).polygons().get(0),
            MetricRegion.rectangle(20, 0, 40, 30).polygons().get(0)));
        assertTrue(conservative(union, p(5, 15), p(35, 15)));
        assertTrue(conservative(rectangle, p(10, 15), p(10, 15)));
    }

    @Test
    void offSegmentGapAndDisjointRegionCannotAuthorizeOrVetoWrongSegment() {
        MetricRegion region = new MetricRegion(List.of(
            MetricRegion.rectangle(0, 0, 20, 20).polygons().get(0),
            MetricRegion.rectangle(20 + 1e-9, 0, 40, 20).polygons().get(0)));
        assertTrue(conservative(region, p(2, 10), p(18, 10)));
        assertFalse(conservative(region, p(2, 10), p(35, 10)));
        assertFalse(conservative(region, p(45, 10), p(45, 10)));
    }

    @Test
    void tinyMemoryAndWorkRefusalsAndCancellationReleaseScratch() {
        MetricRegion region = MetricRegion.rectangle(0, 0, 30, 30);
        var ledger = new AttemptMemoryLedger(64);
        try (var owner = ledger.rootOwner()) {
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                () -> region.containsSegmentWithConservativeEvents(p(5, 15), p(25, 15),
                    CancellationProbe.NONE, ignored -> { }, owner));
            assertEquals(0, ledger.currentBytes());
        }
        var enough = AttemptMemoryLedger.production();
        try (var owner = enough.rootOwner()) {
            AtomicLong work = new AtomicLong();
            assertThrows(IllegalStateException.class,
                () -> region.containsSegmentWithConservativeEvents(p(5, 15), p(25, 15),
                    CancellationProbe.NONE, units -> {
                        if (units > 3 - work.get()) throw new IllegalStateException("test work cap");
                        work.addAndGet(units);
                    }, owner));
            assertEquals(0, enough.currentBytes());
            AtomicLong checks = new AtomicLong();
            assertThrows(CancellationException.class,
                () -> region.containsSegmentWithConservativeEvents(p(5, 15), p(25, 15),
                    () -> checks.incrementAndGet() > 4, ignored -> { }, owner));
            assertEquals(0, enough.currentBytes());
        }
    }

    private static boolean conservative(MetricRegion region, MetricPoint a, MetricPoint b) {
        return region.containsSegmentWithConservativeEvents(a, b,
            CancellationProbe.NONE, ignored -> { }, null);
    }

    private static MetricPoint p(double x, double y) { return new MetricPoint(x, y); }
}
