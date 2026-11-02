package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Computes exact length-averaged scalar evidence along a straight raster-space segment. */
public final class BilinearSegmentIntegral {
    private BilinearSegmentIntegral() { }

    /** Read-only scalar grid indexed at native raster pixel centers. */
    public interface ReadOnlyScalarGrid {
        int width();
        int height();
        boolean supportsCell(int x, int y);
        double nodeValue(int x, int y);
    }

    /** Result status. */
    public enum Status { COMPLETE, UNAVAILABLE }

    /** Closed reason set for unavailable results and the successful no-reason value. */
    public enum Reason { NONE, OUTSIDE_DOMAIN, UNSUPPORTED_CELL, INVALID_NODE }

    /** Immutable scalar integral result. */
    public record Result(Status status, OptionalDouble mean, OptionalDouble minimum,
            long cellsVisited, long nodeReads, Reason reason) {
        /** Enforces complete values on success and empty values with a reason on failure. */
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(mean, "mean");
            Objects.requireNonNull(minimum, "minimum");
            Objects.requireNonNull(reason, "reason");
            boolean validValues = mean.isPresent() && minimum.isPresent()
                && Double.isFinite(mean.getAsDouble()) && Double.isFinite(minimum.getAsDouble())
                && mean.getAsDouble() >= 0.0 && mean.getAsDouble() <= 1.0
                && minimum.getAsDouble() >= 0.0 && minimum.getAsDouble() <= 1.0;
            if (cellsVisited < 0 || nodeReads < 0
                    || status == Status.COMPLETE && (!validValues || reason != Reason.NONE)
                    || status == Status.UNAVAILABLE && (mean.isPresent() || minimum.isPresent() || reason == Reason.NONE)) {
                throw new IllegalArgumentException("Integral result status, values, counts, and reason disagree");
            }
        }
    }

    /** Evaluates one actual straight segment without copying the source raster. */
    public static Result evaluate(ReadOnlyScalarGrid grid, RasterPoint from, RasterPoint to,
            CancellationProbe cancellation, Runnable chargeWork) {
        if (grid == null || from == null || to == null || cancellation == null || chargeWork == null) {
            throw new IllegalArgumentException("Grid, segment endpoints, cancellation, and work charge are required");
        }
        int width = grid.width();
        int height = grid.height();
        if (width < 2 || height < 2) {
            throw new IllegalArgumentException("Scalar grid must have at least one interpolation cell");
        }
        Work work = new Work(grid, cancellation, chargeWork, width, height);
        double x0 = from.x();
        double y0 = from.y();
        double dx = to.x() - x0;
        double dy = to.y() - y0;
        double maxX = width - 1.0;
        double maxY = height - 1.0;
        if (!inside(x0, y0, maxX, maxY) || !inside(to.x(), to.y(), maxX, maxY)) {
            return unavailable(Reason.OUTSIDE_DOMAIN, work);
        }

        if (!work.validatePoint(x0, y0)) {
            return unavailable(work.reason, work);
        }
        if (dx == 0.0 && dy == 0.0) {
            int cellX = cellIndex(x0, width - 1);
            int cellY = cellIndex(y0, height - 1);
            double value = work.sample(cellX, cellY, x0, y0);
            if (!Double.isFinite(value)) {
                return unavailable(work.reason, work);
            }
            return complete(value, value, work);
        }

        double nextXLine = firstGridLine(x0, dx);
        double nextYLine = firstGridLine(y0, dy);
        double current = 0.0;
        double integral = 0.0;
        double minimum = Double.POSITIVE_INFINITY;
        while (current < 1.0) {
            double crossingX = parameterAt(nextXLine, x0, dx);
            double crossingY = parameterAt(nextYLine, y0, dy);
            while (crossingX <= current && dx != 0.0) {
                nextXLine += Math.signum(dx);
                crossingX = parameterAt(nextXLine, x0, dx);
            }
            while (crossingY <= current && dy != 0.0) {
                nextYLine += Math.signum(dy);
                crossingY = parameterAt(nextYLine, y0, dy);
            }
            double next = Math.min(1.0, Math.min(crossingX, crossingY));
            if (!(next > current)) {
                throw new IllegalStateException("Raster traversal failed to advance");
            }
            double middle = current + (next - current) * 0.5;
            double xMiddle = x0 + dx * middle;
            double yMiddle = y0 + dy * middle;
            if (!work.validateIntervalCells(xMiddle, yMiddle, dx == 0.0, dy == 0.0)) {
                return unavailable(work.reason, work);
            }
            int cellX = cellIndex(xMiddle, width - 1);
            int cellY = cellIndex(yMiddle, height - 1);
            double f0 = work.sample(cellX, cellY, x0 + dx * current, y0 + dy * current);
            double fm = work.sample(cellX, cellY, xMiddle, yMiddle);
            double f1 = work.sample(cellX, cellY, x0 + dx * next, y0 + dy * next);
            if (!Double.isFinite(f0) || !Double.isFinite(fm) || !Double.isFinite(f1)) {
                return unavailable(work.reason, work);
            }
            double span = next - current;
            integral += span * (f0 + 4.0 * fm + f1) / 6.0;
            minimum = Math.min(minimum, Math.min(f0, f1));
            double quadratic = 2.0 * (f1 + f0 - 2.0 * fm);
            if (quadratic != 0.0) {
                double vertex = -(f1 - f0 - quadratic) / (2.0 * quadratic);
                if (vertex > 0.0 && vertex < 1.0) {
                    double linear = f1 - f0 - quadratic;
                    double value = (quadratic * vertex + linear) * vertex + f0;
                    minimum = Math.min(minimum, clamp01(value));
                }
            }
            current = next;
            if (!work.validatePoint(x0 + dx * current, y0 + dy * current)) {
                return unavailable(work.reason, work);
            }
            if (nearParameter(crossingX, next) && dx != 0.0) {
                nextXLine += Math.signum(dx);
            }
            if (nearParameter(crossingY, next) && dy != 0.0) {
                nextYLine += Math.signum(dy);
            }
        }
        return complete(integral, minimum, work);
    }

    private static Result complete(double mean, double minimum, Work work) {
        return new Result(Status.COMPLETE, OptionalDouble.of(clamp01(mean)),
            OptionalDouble.of(clamp01(minimum)), work.cellsVisited, work.nodeReads, Reason.NONE);
    }

    private static Result unavailable(Reason reason, Work work) {
        return new Result(Status.UNAVAILABLE, OptionalDouble.empty(), OptionalDouble.empty(),
            work.cellsVisited, work.nodeReads, reason);
    }

    private static boolean inside(double x, double y, double maxX, double maxY) {
        return x >= 0.0 && x <= maxX && y >= 0.0 && y <= maxY;
    }

    private static double firstGridLine(double coordinate, double delta) {
        if (delta > 0.0) return Math.floor(coordinate) + 1.0;
        if (delta < 0.0) return Math.ceil(coordinate) - 1.0;
        return Double.POSITIVE_INFINITY;
    }

    private static double parameterAt(double line, double origin, double delta) {
        return delta == 0.0 || !Double.isFinite(line) ? Double.POSITIVE_INFINITY : (line - origin) / delta;
    }

    private static boolean nearParameter(double first, double second) {
        return Double.isFinite(first) && Math.abs(first - second) <= Math.max(1e-14, 4.0 * Math.ulp(second));
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int cellIndex(double coordinate, int maxNode) {
        return Math.min(maxNode - 1, Math.max(0, (int) Math.floor(coordinate)));
    }

    private static int[] touchingCellIndexes(double coordinate, int maxNode, boolean includeBoundaryNeighbors) {
        double nearest = Math.rint(coordinate);
        boolean onGridLine = Math.abs(coordinate - nearest)
            <= Math.max(1e-12, 4.0 * Math.ulp(coordinate));
        if (onGridLine && includeBoundaryNeighbors) {
            int line = (int) nearest;
            int before = line - 1;
            int after = line;
            if (before < 0) return new int[] {0};
            if (after >= maxNode) return new int[] {maxNode - 1};
            return new int[] {before, after};
        }
        return new int[] {cellIndex(coordinate, maxNode)};
    }

    private static final class Work {
        private final ReadOnlyScalarGrid grid;
        private final CancellationProbe cancellation;
        private final Runnable chargeWork;
        private final int width;
        private final int height;
        private final Set<Long> validatedCells = new HashSet<>();
        private final Map<Long, Double> nodes = new HashMap<>();
        private long cellsVisited;
        private long nodeReads;
        private Reason reason = Reason.NONE;

        Work(ReadOnlyScalarGrid grid, CancellationProbe cancellation, Runnable chargeWork, int width, int height) {
            this.grid = grid;
            this.cancellation = cancellation;
            this.chargeWork = chargeWork;
            this.width = width;
            this.height = height;
        }

        private void charge() {
            chargeWork.run();
            cancellation.checkpoint();
        }

        private boolean validatePoint(double x, double y) {
            return validateCells(touchingCellIndexes(x, width - 1, true),
                touchingCellIndexes(y, height - 1, true));
        }

        private boolean validateIntervalCells(double x, double y, boolean verticalGridLine,
                boolean horizontalGridLine) {
            return validateCells(touchingCellIndexes(x, width - 1, verticalGridLine),
                touchingCellIndexes(y, height - 1, horizontalGridLine));
        }

        private boolean validateCells(int[] xs, int[] ys) {
            for (int cellY : ys) {
                for (int cellX : xs) {
                    long key = key(cellX, cellY);
                    if (validatedCells.add(key)) {
                        cellsVisited++;
                        charge();
                        if (!grid.supportsCell(cellX, cellY)) {
                            reason = Reason.UNSUPPORTED_CELL;
                            return false;
                        }
                        for (int nodeY = cellY; nodeY <= cellY + 1; nodeY++) {
                            for (int nodeX = cellX; nodeX <= cellX + 1; nodeX++) {
                                if (!validNode(nodeX, nodeY)) return false;
                            }
                        }
                    }
                }
            }
            return true;
        }

        private boolean validNode(int x, int y) {
            long key = key(x, y);
            Double cached = nodes.get(key);
            if (cached == null) {
                nodeReads++;
                charge();
                double value = grid.nodeValue(x, y);
                nodes.put(key, value);
                cached = value;
            }
            if (!Double.isFinite(cached) || cached < 0.0 || cached > 1.0) {
                reason = Reason.INVALID_NODE;
                return false;
            }
            return true;
        }

        private double sample(int cellX, int cellY, double x, double y) {
            double f00 = node(cellX, cellY);
            double f10 = node(cellX + 1, cellY);
            double f01 = node(cellX, cellY + 1);
            double f11 = node(cellX + 1, cellY + 1);
            if (!validScalar(f00) || !validScalar(f10) || !validScalar(f01) || !validScalar(f11)) {
                reason = Reason.INVALID_NODE;
                return Double.NaN;
            }
            double u = clamp01(x - cellX);
            double v = clamp01(y - cellY);
            double top = f00 + u * (f10 - f00);
            double bottom = f01 + u * (f11 - f01);
            return clamp01(top + v * (bottom - top));
        }

        private double node(int x, int y) {
            Double value = nodes.get(key(x, y));
            return value == null ? Double.NaN : value;
        }

        private static boolean validScalar(double value) {
            return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
        }

        private static long key(int x, int y) {
            return ((long) y << 32) ^ (x & 0xffffffffL);
        }
    }
}
