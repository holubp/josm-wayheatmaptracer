package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Exact authority remains independent of work ownership, cancellation, and admission. */
class V022MetricRegionSegmentWorkTest {
    @Test
    void ownedAndUnownedQueriesPreserveIndependentGapAndBoundaryOracles() {
        var notch = new MetricRegion(List.of(List.of(new MetricPoint(-5, -2),
                new MetricPoint(5, -2), new MetricPoint(5, 2), new MetricPoint(0.1, 2),
                new MetricPoint(0.1, -0.1), new MetricPoint(-0.1, -0.1),
                new MetricPoint(-0.1, 2), new MetricPoint(-5, 2))));
        var split = new MetricRegion(List.of(
                MetricRegion.rectangle(-5, -2, -0.1, 2).polygons().get(0),
                MetricRegion.rectangle(0.1, -2, 5, 2).polygons().get(0)));
        var joined = connected();
        for (var region : List.of(notch, split, joined)) {
            boolean expected = region == joined;
            for (boolean reversed : List.of(false, true)) {
                var start = new MetricPoint(reversed ? 4 : -4, 0);
                var end = new MetricPoint(reversed ? -4 : 4, 0);
                assertEquals(expected, region.containsSegment(start, end));
                var ledger = AttemptMemoryLedger.production();
                var owner = ledger.rootOwner();
                AtomicLong units = new AtomicLong();
                assertEquals(expected, region.containsSegment(start, end, CancellationProbe.NONE,
                        units::addAndGet, owner));
                assertTrue(units.get() > 0L);
                assertEquals(0L, ledger.currentBytes());
                owner.close();
            }
        }
        assertTrue(notch.containsSegment(new MetricPoint(-4, -2), new MetricPoint(4, -2)));
    }

    @Test
    void memoryRefusalPrecedesEventMaterializationAndReleasesQueryScratch() {
        var ledger = new AttemptMemoryLedger(128L);
        var owner = ledger.rootOwner();
        assertThrows(AttemptMemoryLedger.ResourceLimitException.class, () -> connected().containsSegment(
                new MetricPoint(-4, 0), new MetricPoint(4, 0), CancellationProbe.NONE, ignored -> { }, owner));
        assertEquals(0L, ledger.currentBytes());
        owner.close();
    }

    @Test
    void cancellationDuringInternalPolygonMembershipClosesAdmittedScratch() {
        var region = new MetricRegion(IntStream.range(0, 100)
                .mapToObj(index -> MetricRegion.rectangle(index * 3, -2, index * 3 + 2, 2)
                    .polygons().get(0)).toList());
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        AtomicLong units = new AtomicLong();
        assertThrows(CancellationException.class, () -> region.containsSegment(new MetricPoint(298, 0),
                new MetricPoint(299, 0), () -> units.get() >= 120L, units::addAndGet, owner));
        assertTrue(ledger.peakBytes() > 0L, "cancellation is inside membership after scratch admission");
        assertEquals(0L, ledger.currentBytes());
        owner.close();
    }

    @Test
    void typedWorkRefusalInterruptsEventDeduplicationAndReleasesScratch() {
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        AtomicLong units = new AtomicLong();
        var refusal = assertThrows(WorkRefusal.class, () -> connected().containsSegment(
                new MetricPoint(-4, 0), new MetricPoint(4, 0), CancellationProbe.NONE, requested -> {
                    if (requested > 34L - units.get()) throw new WorkRefusal();
                    units.addAndGet(requested);
                }, owner));
        assertEquals(34L, units.get());
        assertTrue(java.util.Arrays.stream(refusal.getStackTrace())
                .anyMatch(frame -> frame.getMethodName().equals("addEvent")));
        assertTrue(ledger.peakBytes() > 0L);
        assertEquals(0L, ledger.currentBytes());
        owner.close();
    }

    private static MetricRegion connected() {
        return new MetricRegion(List.of(MetricRegion.rectangle(-5, -2, 1, 2).polygons().get(0),
                MetricRegion.rectangle(-1, -2, 5, 2).polygons().get(0)));
    }

    @Test
    void ownedSegmentQueriesMatchTheHistoricalEventPredicate() {
        var random = new java.util.Random(0x221012L);
        for (var region : List.of(connected(), new MetricRegion(List.of(
                MetricRegion.rectangle(-5, -2, -0.1, 2).polygons().get(0),
                MetricRegion.rectangle(0.1, -2, 5, 2).polygons().get(0))))) {
            for (int index = 0; index < 200; index++) {
                var start = new MetricPoint(random.nextDouble() * 12 - 6, random.nextDouble() * 6 - 3);
                var end = index % 4 == 0 ? start
                        : new MetricPoint(random.nextDouble() * 12 - 6, random.nextDouble() * 6 - 3);
                boolean expected = historicalSegment(region, start, end);
                assertEquals(expected, region.containsSegment(start, end));
                var ledger = AttemptMemoryLedger.production();
                var owner = ledger.rootOwner();
                assertEquals(expected, region.containsSegment(start, end,
                        CancellationProbe.NONE, ignored -> { }, owner));
                assertEquals(0L, ledger.currentBytes());
                owner.close();
            }
        }
    }

    // Frozen pre-hook segment algorithm: independent event inventory, dedup and midpoint oracle.
    static boolean historicalSegment(MetricRegion region, MetricPoint start, MetricPoint end) {
        if (!historicalContains(region, start) || !historicalContains(region, end)) return false;
        var events = new java.util.ArrayList<Double>();
        events.add(0.0); events.add(1.0);
        for (var polygon : region.polygons()) for (int index = 0; index < polygon.size(); index++) {
            var a = polygon.get(index); var b = polygon.get((index + 1) % polygon.size());
            double rx = end.xMeters() - start.xMeters(), ry = end.yMeters() - start.yMeters();
            double sx = b.xMeters() - a.xMeters(), sy = b.yMeters() - a.yMeters();
            double denominator = cross(rx, ry, sx, sy);
            double apx = a.xMeters() - start.xMeters(), apy = a.yMeters() - start.yMeters();
            if (Math.abs(denominator) <= 1e-10) {
                if (Math.abs(cross(apx, apy, rx, ry)) <= 1e-10) {
                    double lengthSquared = rx * rx + ry * ry;
                    if (lengthSquared > 1e-10) {
                        historicalEvent(events, (apx * rx + apy * ry) / lengthSquared);
                        historicalEvent(events, ((b.xMeters() - start.xMeters()) * rx
                                + (b.yMeters() - start.yMeters()) * ry) / lengthSquared);
                    }
                }
            } else {
                double t = cross(apx, apy, sx, sy) / denominator;
                double u = cross(apx, apy, rx, ry) / denominator;
                if (t >= -1e-10 && t <= 1 + 1e-10 && u >= -1e-10 && u <= 1 + 1e-10)
                    historicalEvent(events, t);
            }
        }
        java.util.Collections.sort(events);
        for (int index = 1; index < events.size(); index++) {
            double left = events.get(index - 1), right = events.get(index);
            double fraction = (left + right) / 2.0;
            if (right - left > 1e-10 && !historicalContains(region, new MetricPoint(
                    start.xMeters() + fraction * (end.xMeters() - start.xMeters()),
                    start.yMeters() + fraction * (end.yMeters() - start.yMeters())))) return false;
        }
        return true;
    }

    private static void historicalEvent(List<Double> events, double value) {
        if (value >= -1e-10 && value <= 1 + 1e-10) {
            double bounded = Math.max(0.0, Math.min(1.0, value));
            if (events.stream().noneMatch(existing -> Math.abs(existing - bounded) <= 1e-10)) events.add(bounded);
        }
    }

    private static boolean historicalContains(MetricRegion region, MetricPoint point) {
        for (var polygon : region.polygons()) {
            boolean inside = false;
            for (int current = 0, previous = polygon.size() - 1; current < polygon.size(); previous = current++) {
                var a = polygon.get(previous); var b = polygon.get(current);
                double scale = Math.max(1.0, a.distanceTo(b));
                if (Math.abs(cross(b.xMeters() - a.xMeters(), b.yMeters() - a.yMeters(),
                        point.xMeters() - a.xMeters(), point.yMeters() - a.yMeters())) <= 1e-9 * scale
                        && point.xMeters() >= Math.min(a.xMeters(), b.xMeters()) - 1e-9
                        && point.xMeters() <= Math.max(a.xMeters(), b.xMeters()) + 1e-9
                        && point.yMeters() >= Math.min(a.yMeters(), b.yMeters()) - 1e-9
                        && point.yMeters() <= Math.max(a.yMeters(), b.yMeters()) + 1e-9) return true;
                boolean crosses = (a.yMeters() > point.yMeters()) != (b.yMeters() > point.yMeters())
                        && point.xMeters() < (b.xMeters() - a.xMeters()) * (point.yMeters() - a.yMeters())
                            / (b.yMeters() - a.yMeters()) + a.xMeters();
                if (crosses) inside = !inside;
            }
            if (inside) return true;
        }
        return false;
    }

    private static double cross(double ax, double ay, double bx, double by) { return ax * by - ay * bx; }

    private static final class WorkRefusal extends RuntimeException { }
}
