package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class BilinearSegmentIntegralTest {
    @Test
    void integratesBilinearSaddleExactlyAlongTheDiagonal() {
        Grid grid = new Grid(new double[][] {{1.0, 0.0}, {0.0, 1.0}});
        var result = evaluate(grid, new RasterPoint(0.0, 0.0), new RasterPoint(1.0, 1.0));
        assertEquals(BilinearSegmentIntegral.Status.COMPLETE, result.status());
        assertEquals(2.0 / 3.0, result.mean().orElseThrow(), 1e-14);
        assertEquals(0.5, result.minimum().orElseThrow(), 1e-14);
    }

    @Test
    void returnsConstantValueAndExactSingleCellWorkCounts() {
        Grid grid = Grid.constant(2, 2, 0.37);
        AtomicInteger charged = new AtomicInteger();
        var result = BilinearSegmentIntegral.evaluate(grid, new RasterPoint(0.2, 0.2),
            new RasterPoint(0.8, 0.7), CancellationProbe.NONE, charged::incrementAndGet);
        assertEquals(0.37, result.mean().orElseThrow(), 1e-14);
        assertEquals(0.37, result.minimum().orElseThrow(), 1e-14);
        assertEquals(1, result.cellsVisited());
        assertEquals(4, result.nodeReads());
        assertEquals(5, charged.get());
    }

    @Test
    void integratesLinearFieldBySegmentLength() {
        Grid grid = new Grid(new double[][] {{0.0, 1.0}, {0.0, 1.0}});
        var result = evaluate(grid, new RasterPoint(0.2, 0.4), new RasterPoint(0.8, 0.6));
        assertEquals(0.5, result.mean().orElseThrow(), 1e-14);
        assertEquals(0.2, result.minimum().orElseThrow(), 1e-14);
    }

    @Test
    void traversesMultipleCellsWithoutSkippingCrossedCells() {
        Grid grid = xRamp(4, 2);
        AtomicInteger charged = new AtomicInteger();
        var result = BilinearSegmentIntegral.evaluate(grid, new RasterPoint(0.2, 0.5),
            new RasterPoint(2.8, 0.5), CancellationProbe.NONE, charged::incrementAndGet);
        assertEquals(0.5, result.mean().orElseThrow(), 1e-13);
        assertEquals(0.2 / 3.0, result.minimum().orElseThrow(), 1e-13);
        assertEquals(3, result.cellsVisited());
        assertEquals(8, result.nodeReads());
        assertEquals(11, charged.get());
    }

    @Test
    void reversingSegmentPreservesMeanAndMinimum() {
        Grid grid = new Grid(new double[][] {{0.2, 0.8}, {0.4, 0.6}});
        var forward = evaluate(grid, new RasterPoint(0.1, 0.2), new RasterPoint(0.9, 0.8));
        var reverse = evaluate(grid, new RasterPoint(0.9, 0.8), new RasterPoint(0.1, 0.2));
        assertEquals(forward.mean().orElseThrow(), reverse.mean().orElseThrow(), 1e-14);
        assertEquals(forward.minimum().orElseThrow(), reverse.minimum().orElseThrow(), 1e-14);
    }

    @Test
    void collinearSubdivisionPreservesLengthWeightedIntegral() {
        Grid grid = new Grid(new double[][] {{1.0, 0.0}, {0.0, 1.0}});
        var whole = evaluate(grid, new RasterPoint(0.0, 0.0), new RasterPoint(1.0, 1.0));
        var first = evaluate(grid, new RasterPoint(0.0, 0.0), new RasterPoint(0.5, 0.5));
        var second = evaluate(grid, new RasterPoint(0.5, 0.5), new RasterPoint(1.0, 1.0));
        assertEquals(whole.mean().orElseThrow(),
            0.5 * first.mean().orElseThrow() + 0.5 * second.mean().orElseThrow(), 1e-14);
        assertEquals(whole.minimum().orElseThrow(),
            Math.min(first.minimum().orElseThrow(), second.minimum().orElseThrow()), 1e-14);
    }

    @Test
    void preservesDistinctNearCoincidentCrossingsAgainstCheckerboardOracle() {
        CheckerboardGrid grid = new CheckerboardGrid(1002, 1002);
        RasterPoint start = new RasterPoint(0.0, 0.0);
        RasterPoint end = new RasterPoint(1000.0, 1000.000000000009);
        // Independent 60-digit Decimal oracle split at every integer x/y grid crossing.
        double oracle = 0.66666666666666816;
        var result = BilinearSegmentIntegral.evaluate(grid, start, end,
            CancellationProbe.NONE, () -> { });
        var reverse = BilinearSegmentIntegral.evaluate(grid, end, start, CancellationProbe.NONE, () -> { });
        RasterPoint midpoint = new RasterPoint(500.0, 500.0000000000045);
        var first = BilinearSegmentIntegral.evaluate(grid, start, midpoint, CancellationProbe.NONE, () -> { });
        var second = BilinearSegmentIntegral.evaluate(grid, midpoint, end, CancellationProbe.NONE, () -> { });
        assertEquals(oracle, result.mean().orElseThrow(), 2e-13);
        assertEquals(oracle, reverse.mean().orElseThrow(), 2e-13);
        assertEquals(oracle, (first.mean().orElseThrow() + second.mean().orElseThrow()) * 0.5, 2e-13);
    }

    @Test
    void rejectsUnsupportedCellTouchedOnlyAlongAnEdge() {
        Grid grid = Grid.constant(3, 2, 0.5).unsupported(0, 0);
        var result = evaluate(grid, new RasterPoint(1.0, 0.1), new RasterPoint(1.0, 0.9));
        assertUnavailable(result, BilinearSegmentIntegral.Reason.UNSUPPORTED_CELL);
        assertEquals(1, result.cellsVisited());
    }

    @Test
    void rejectsUnsupportedCellTouchedOnlyAtCorner() {
        Grid grid = Grid.constant(3, 3, 0.5).unsupported(0, 1);
        var result = evaluate(grid, new RasterPoint(0.2, 0.2), new RasterPoint(1.8, 1.8));
        assertUnavailable(result, BilinearSegmentIntegral.Reason.UNSUPPORTED_CELL);
        assertTrue(result.cellsVisited() >= 3);
    }

    @Test
    void rejectsSegmentsOutsideTheClosedPixelCenterDomain() {
        Grid grid = Grid.constant(2, 2, 0.5);
        var result = evaluate(grid, new RasterPoint(-0.01, 0.5), new RasterPoint(0.5, 0.5));
        assertUnavailable(result, BilinearSegmentIntegral.Reason.OUTSIDE_DOMAIN);
        assertEquals(0, result.cellsVisited());
    }

    @Test
    void rejectsNonFiniteAndOutOfRangeNodeValues() {
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -0.01, 1.01}) {
            Grid grid = Grid.constant(2, 2, 0.5).withNode(0, 0, invalid);
            var result = evaluate(grid, new RasterPoint(0.1, 0.1), new RasterPoint(0.8, 0.8));
            assertUnavailable(result, BilinearSegmentIntegral.Reason.INVALID_NODE);
        }
    }

    @Test
    void zeroLengthSegmentReturnsPointValueAfterCellValidation() {
        Grid grid = new Grid(new double[][] {{0.0, 1.0}, {0.0, 1.0}});
        var result = evaluate(grid, new RasterPoint(0.25, 0.5), new RasterPoint(0.25, 0.5));
        assertEquals(0.25, result.mean().orElseThrow(), 1e-14);
        assertEquals(0.25, result.minimum().orElseThrow(), 1e-14);
        assertEquals(1, result.cellsVisited());
        assertEquals(4, result.nodeReads());
    }

    @Test
    void zeroLengthAtGridCornerValidatesEveryTouchedCellAndReadsSharedNodesOnce() {
        Grid grid = Grid.constant(3, 3, 0.4);
        var result = evaluate(grid, new RasterPoint(1.0, 1.0), new RasterPoint(1.0, 1.0));
        assertEquals(0.4, result.mean().orElseThrow(), 1e-14);
        assertEquals(4, result.cellsVisited());
        assertEquals(9, result.nodeReads());
    }

    @Test
    void cancellationPropagatesAtChargedOperation() {
        AtomicInteger charged = new AtomicInteger();
        CancellationProbe probe = () -> charged.get() >= 1;
        assertThrows(CancellationException.class, () -> BilinearSegmentIntegral.evaluate(
            Grid.constant(2, 2, 0.5), new RasterPoint(0.2, 0.2), new RasterPoint(0.8, 0.8),
            probe, charged::incrementAndGet));
    }

    @Test
    void workBudgetExceptionPropagatesUnchanged() {
        RuntimeException expected = new RuntimeException("work cap");
        RuntimeException actual = assertThrows(RuntimeException.class, () -> BilinearSegmentIntegral.evaluate(
            Grid.constant(2, 2, 0.5), new RasterPoint(0.2, 0.2), new RasterPoint(0.8, 0.8),
            CancellationProbe.NONE, () -> { throw expected; }));
        assertSame(expected, actual);
    }

    @Test
    void gridNodeReadExceptionPropagatesUnchanged() {
        RuntimeException expected = new RuntimeException("grid read");
        Grid grid = Grid.constant(2, 2, 0.5).throwOnNodeRead(expected);
        RuntimeException actual = assertThrows(RuntimeException.class, () -> evaluate(grid,
            new RasterPoint(0.2, 0.2), new RasterPoint(0.8, 0.8)));
        assertSame(expected, actual);
    }

    @Test
    void gridSupportExceptionPropagatesUnchanged() {
        RuntimeException expected = new RuntimeException("support query");
        Grid grid = Grid.constant(2, 2, 0.5).throwOnSupportQuery(expected);
        RuntimeException actual = assertThrows(RuntimeException.class, () -> evaluate(grid,
            new RasterPoint(0.2, 0.2), new RasterPoint(0.8, 0.8)));
        assertSame(expected, actual);
    }

    @Test
    void cancellationProbeExceptionPropagatesUnchanged() {
        RuntimeException expected = new RuntimeException("cancellation probe");
        RuntimeException actual = assertThrows(RuntimeException.class, () -> BilinearSegmentIntegral.evaluate(
            Grid.constant(2, 2, 0.5), new RasterPoint(0.2, 0.2), new RasterPoint(0.8, 0.8),
            () -> { throw expected; }, () -> { }));
        assertSame(expected, actual);
    }

    @Test
    void invalidArgumentsAndDimensionsFailClearly() {
        assertThrows(IllegalArgumentException.class, () -> BilinearSegmentIntegral.evaluate(
            Grid.constant(1, 2, 0.4), new RasterPoint(0.2, 0.2), new RasterPoint(0.3, 0.3),
            CancellationProbe.NONE, () -> { }));
        assertThrows(IllegalArgumentException.class, () -> BilinearSegmentIntegral.evaluate(
            Grid.constant(2, 2, 0.4), null, new RasterPoint(0.3, 0.3), CancellationProbe.NONE, () -> { }));
        assertThrows(IllegalArgumentException.class, () -> BilinearSegmentIntegral.evaluate(
            Grid.constant(2, 2, 0.4), new RasterPoint(0.2, 0.2), new RasterPoint(0.3, 0.3),
            CancellationProbe.NONE, null));
        assertThrows(IllegalArgumentException.class, () -> BilinearSegmentIntegral.evaluate(
            null, new RasterPoint(0.2, 0.2), new RasterPoint(0.3, 0.3), CancellationProbe.NONE, () -> { }));
        assertThrows(IllegalArgumentException.class, () -> BilinearSegmentIntegral.evaluate(
            Grid.constant(2, 2, 0.4), new RasterPoint(0.2, 0.2), new RasterPoint(0.3, 0.3), null, () -> { }));
    }

    private static BilinearSegmentIntegral.Result evaluate(Grid grid, RasterPoint from, RasterPoint to) {
        return BilinearSegmentIntegral.evaluate(grid, from, to, CancellationProbe.NONE, () -> { });
    }

    private static Grid xRamp(int width, int height) {
        double[][] values = new double[height][width];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) values[y][x] = x / 3.0;
        return new Grid(values);
    }

    private static void assertUnavailable(BilinearSegmentIntegral.Result result,
            BilinearSegmentIntegral.Reason reason) {
        assertEquals(BilinearSegmentIntegral.Status.UNAVAILABLE, result.status());
        assertEquals(reason, result.reason());
        assertTrue(result.mean().isEmpty());
        assertTrue(result.minimum().isEmpty());
    }

    private static final class Grid implements BilinearSegmentIntegral.ReadOnlyScalarGrid {
        private final double[][] values;
        private final boolean[][] support;
        private RuntimeException readFailure;
        private RuntimeException supportFailure;

        Grid(double[][] values) {
            this.values = values;
            this.support = new boolean[values.length][values[0].length - 1];
            for (boolean[] row : support) java.util.Arrays.fill(row, true);
        }

        static Grid constant(int width, int height, double value) {
            double[][] values = new double[height][width];
            for (double[] row : values) java.util.Arrays.fill(row, value);
            return new Grid(values);
        }

        Grid unsupported(int x, int y) { support[y][x] = false; return this; }
        Grid withNode(int x, int y, double value) { values[y][x] = value; return this; }
        Grid throwOnNodeRead(RuntimeException failure) { readFailure = failure; return this; }
        Grid throwOnSupportQuery(RuntimeException failure) { supportFailure = failure; return this; }
        @Override public int width() { return values[0].length; }
        @Override public int height() { return values.length; }
        @Override public boolean supportsCell(int x, int y) {
            if (supportFailure != null) throw supportFailure;
            return support[y][x];
        }
        @Override public double nodeValue(int x, int y) {
            if (readFailure != null) throw readFailure;
            return values[y][x];
        }
    }

    private record CheckerboardGrid(int width, int height) implements BilinearSegmentIntegral.ReadOnlyScalarGrid {
        @Override public boolean supportsCell(int x, int y) { return true; }
        @Override public double nodeValue(int x, int y) { return ((x + y) & 1) == 0 ? 1.0 : 0.0; }
    }
}
