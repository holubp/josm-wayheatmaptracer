package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class V022MetricRegionLazyScratchTest {
    @Test
    @SuppressWarnings("unchecked")
    void privateLazyListKeepsExactValuesOrderingAndDenseEventBound() throws Exception {
        Class<?> type = Class.forName(MetricRegion.class.getName() + "$LazyEventList");
        var constructor = type.getDeclaredConstructor(int.class); constructor.setAccessible(true);
        for (int bound : new int[] {8, 10, 34, 802}) {
            var actual = (ArrayList<Double>) constructor.newInstance(bound);
            var expected = new ArrayList<Double>(bound);
            var field = type.getDeclaredField("eventBound"); field.setAccessible(true);
            assertEquals(bound, field.getInt(actual));
            for (int index = 0; index < bound; index++) {
                double value = index == 0 ? -0.0 : index == 1 ? 1.0 : (bound - index) / (double) bound;
                assertEquals(expected.add(value), actual.add(value));
                assertEquals(expected, actual);
            }
            actual.sort(null); expected.sort(null);
            assertEquals(expected.stream().map(Double::doubleToRawLongBits).toList(),
                    actual.stream().map(Double::doubleToRawLongBits).toList());
        }
    }
    @Test
    void convenienceAndControlledPathsKeepHistoricalAuthorityAndOwnedReceipts() {
        var union = new MetricRegion(List.of(MetricRegion.rectangle(-5, -2, 1, 2).polygons().get(0),
                MetricRegion.rectangle(-1, -2, 5, 2).polygons().get(0)));
        var notch = new MetricRegion(List.of(List.of(new MetricPoint(-5, -2), new MetricPoint(5, -2),
                new MetricPoint(5, 2), new MetricPoint(.1, 2), new MetricPoint(.1, -.1),
                new MetricPoint(-.1, -.1), new MetricPoint(-.1, 2), new MetricPoint(-5, 2))));
        for (var region : List.of(MetricRegion.rectangle(-5, -2, 5, 2), union, notch)) {
            for (double y : new double[] {-3, -2, Math.nextDown(-2), -1, -0.0, Double.MIN_VALUE, 1, 2, 3}) {
                var a = new MetricPoint(-4, y); var b = new MetricPoint(4, y);
                for (boolean reverse : List.of(false, true)) {
                    var start = reverse ? b : a; var end = reverse ? a : b;
                    boolean expected = V022MetricRegionSegmentWorkTest.historicalSegment(region, start, end);
                    assertEquals(expected, region.containsSegment(start, end));
                    List<Long> plain = new ArrayList<>(); List<Long> owned = new ArrayList<>();
                    assertEquals(expected, region.containsSegment(start, end, CancellationProbe.NONE, plain::add, null));
                    var ledger = AttemptMemoryLedger.production(); var owner = ledger.rootOwner();
                    try {
                        assertEquals(expected, region.containsSegment(start, end, CancellationProbe.NONE, owned::add, owner));
                        assertEquals(plain, owned); assertEquals(0L, ledger.currentBytes());
                    } finally { owner.close(); }
                }
            }
        }
    }

    @Test
    void nullAndControlledFailureBoundariesRemainExact() {
        var region = MetricRegion.rectangle(-1, -1, 1, 1);
        var point = new MetricPoint(0, 0);
        assertFalse(region.containsSegment(null, point));
        assertThrows(IllegalArgumentException.class,
                () -> region.containsSegment(point, point, null, value -> { }, null));
        assertThrows(IllegalArgumentException.class,
                () -> region.containsSegment(point, point, CancellationProbe.NONE, null, null));
        List<String> trace = new ArrayList<>();
        assertThrows(Refusal.class, () -> region.containsSegment(point, point,
                () -> { trace.add("cancel"); return false; }, value -> {
                    trace.add("admit:" + value); throw new Refusal();
                }, null));
        assertEquals(List.of("cancel", "cancel", "admit:1"), trace);
    }
    private static final class Refusal extends RuntimeException { }
}
