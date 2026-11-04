package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;

/** Bounded P0 arithmetic; no physical coordinate adapter or scalar extraction. */
final class CensusGeometry {
    static final class Unavailable extends RuntimeException { }
    record Interval(double lower, double upper) {
        Interval {
            if (!Double.isFinite(lower) || !Double.isFinite(upper) || lower > upper) throw new Unavailable();
        }
        static Interval point(double value) { return new Interval(value, value); }
        Interval hull(Interval other) { return new Interval(Math.min(lower, other.lower), Math.max(upper, other.upper)); }
    }
    record Box(Interval x, Interval y) { }
    record Rectangle(Interval x, Interval y) { }
    record Counts(long presentCells, long invalidCells, long outsideCells, long nodeReads) { }
    private final EmissionWorkBudget budget;
    CensusGeometry(EmissionWorkBudget budget) { this.budget = budget; }
    void work(long n) { budget.charge(EmissionWorkBudget.Operation.PREPARATION, n); }
    private BigDecimal exact(double value) { work(1); return new BigDecimal(value); }
    private BigDecimal add(BigDecimal a, BigDecimal b) { work(1); return a.add(b); }
    private BigDecimal sub(BigDecimal a, BigDecimal b) { work(1); return a.subtract(b); }
    private BigDecimal mul(BigDecimal a, BigDecimal b) { work(1); return a.multiply(b); }
    Interval add(Interval a, Interval b) { work(1); return new Interval(Math.nextDown(a.lower + b.lower), Math.nextUp(a.upper + b.upper)); }
    Interval sub(Interval a, double b) { return add(a, Interval.point(-b)); }
    Interval mul(Interval a, Interval b) {
        work(4);
        double a0 = a.lower * b.lower, a1 = a.lower * b.upper, a2 = a.upper * b.lower, a3 = a.upper * b.upper;
        return new Interval(Math.nextDown(Math.min(Math.min(a0, a1), Math.min(a2, a3))),
            Math.nextUp(Math.max(Math.max(a0, a1), Math.max(a2, a3))));
    }
    Interval div(Interval a, double positive) {
        work(2); if (!(positive > 0) || !Double.isFinite(positive)) throw new Unavailable();
        return new Interval(Math.nextDown(a.lower / positive), Math.nextUp(a.upper / positive));
    }
    double upperL1(RasterPoint a, RasterPoint b) {
        work(3);
        double x = Math.max(Math.abs(Math.nextDown(a.x() - b.x())), Math.abs(Math.nextUp(a.x() - b.x())));
        double y = Math.max(Math.abs(Math.nextDown(a.y() - b.y())), Math.abs(Math.nextUp(a.y() - b.y())));
        double result = Math.nextUp(x + y); if (!Double.isFinite(result)) throw new Unavailable(); return result;
    }
    Interval ratio(BigDecimal numerator, BigDecimal denominator) {
        work(4);
        double candidate = numerator.divide(denominator, new MathContext(40)).doubleValue();
        if (!Double.isFinite(candidate)) throw new Unavailable();
        int comparison = mul(exact(candidate), denominator).compareTo(numerator);
        double lower = comparison > 0 ? Math.nextDown(candidate) : candidate;
        double upper = comparison < 0 ? Math.nextUp(candidate) : candidate;
        var result = new Interval(lower, upper);
        if (mul(exact(lower), denominator).compareTo(numerator) > 0
                || mul(exact(upper), denominator).compareTo(numerator) < 0) throw new Unavailable();
        return result;
    }
    final class Chart {
        final RasterPoint origin, direction, normal;
        final BigDecimal determinant;
        Chart(RasterPoint a, RasterPoint b) {
            work(4); origin = a;
            direction = new RasterPoint(b.x() - a.x(), b.y() - a.y());
            normal = new RasterPoint(-direction.y(), direction.x());
            determinant = add(mul(exact(direction.x()), exact(direction.x())),
                mul(exact(direction.y()), exact(direction.y())));
            if (determinant.signum() <= 0) throw new Unavailable();
        }
        BigDecimal numerator(RasterPoint p, RasterPoint axis) {
            return add(mul(sub(exact(p.x()), exact(origin.x())), exact(axis.x())),
                mul(sub(exact(p.y()), exact(origin.y())), exact(axis.y())));
        }
        Box coordinates(RasterPoint p) {
            return new Box(ratio(numerator(p, direction), determinant), ratio(numerator(p, normal), determinant));
        }
        final class Extent {
            private BigDecimal minimum, maximum;
            void include(RasterPoint actualPoint) {
                var value = numerator(actualPoint, normal); work(2);
                if (minimum == null || value.compareTo(minimum) < 0) minimum = value;
                if (maximum == null || value.compareTo(maximum) > 0) maximum = value;
            }
            boolean nondegenerate() { work(1); return minimum != null && minimum.compareTo(maximum) < 0; }
        }
        Rectangle enclose(Box box) {
            work(6);
            Box a = coordinates(new RasterPoint(box.x.lower, box.y.lower));
            Box b = coordinates(new RasterPoint(box.x.lower, box.y.upper));
            Box c = coordinates(new RasterPoint(box.x.upper, box.y.lower));
            Box d = coordinates(new RasterPoint(box.x.upper, box.y.upper));
            return new Rectangle(a.x.hull(b.x).hull(c.x).hull(d.x), a.y.hull(b.y).hull(c.y).hull(d.y));
        }
        RasterPoint point(double x, double y) {
            work(6);
            try { return new RasterPoint((origin.x() + direction.x() * x) + normal.x() * y,
                (origin.y() + direction.y() * x) + normal.y() * y); }
            catch (IllegalArgumentException nonfinite) { throw new Unavailable(); }
        }
    }
    final class GridMap {
        final NativeScalarGridView grid;
        final double originX, originY, scale;
        GridMap(NativeScalarGridView grid) {
            work(1); this.grid = grid;
            if (grid instanceof ManagedScalarGridView managed) {
                originX = 0; originY = 0; scale = managed.profileCoordinateScale();
            } else if (grid instanceof ScalarFieldGridView field) {
                originX = field.originX(); originY = field.originY(); scale = field.reduction();
            } else throw new Unavailable();
            if (!Double.isFinite(scale) || !(scale > 0)) throw new Unavailable();
        }
        Box pointBounds(RasterPoint p) {
            return new Box(div(sub(Interval.point(p.x()), originX), scale),
                div(sub(Interval.point(p.y()), originY), scale));
        }
        Box sourceLine(RasterPoint anchor, RasterPoint normal, double from, double to) {
            var parameter = new Interval(from, to);
            // Each directed operation encloses both the exact expression and the actual two rounded operations.
            var x = add(Interval.point(anchor.x()), mul(Interval.point(normal.x()), parameter));
            var y = add(Interval.point(anchor.y()), mul(Interval.point(normal.y()), parameter));
            return new Box(div(sub(x, originX), scale), div(sub(y, originY), scale));
        }
    }
    private record Fraction(BigDecimal numerator, BigDecimal denominator) { }
    private Fraction fraction(BigDecimal n, BigDecimal d) {
        work(1); return d.signum() < 0 ? new Fraction(n.negate(), d.negate()) : new Fraction(n, d);
    }
    private int compare(Fraction a, Fraction b) { return mul(a.numerator, b.denominator).compareTo(mul(b.numerator, a.denominator)); }
    private long integral(Fraction a, RoundingMode rounding) {
        work(1);
        try { return a.numerator.divide(a.denominator, 0, rounding).longValueExact(); }
        catch (ArithmeticException outside) { throw new Unavailable(); }
    }
    private Fraction yAt(BigDecimal y, BigDecimal dy, Fraction t) {
        return fraction(add(mul(y, t.denominator), mul(dy, t.numerator)), t.denominator);
    }
    /** Exact dyadic/rational closed-cell sweep, constant memory and no image-sized set. */
    Counts support(NativeScalarGridView grid, RasterPoint from, RasterPoint to) {
        BigDecimal x0 = exact(from.x()), y0 = exact(from.y());
        BigDecimal dx = sub(exact(to.x()), x0), dy = sub(exact(to.y()), y0);
        long first, last;
        try {
            work(2);
            first = Math.subtractExact(exact(Math.min(from.x(), to.x())).setScale(0, RoundingMode.CEILING).longValueExact(), 1);
            last = exact(Math.max(from.x(), to.x())).setScale(0, RoundingMode.FLOOR).longValueExact();
        } catch (ArithmeticException outside) { throw new Unavailable(); }
        if (first < Integer.MIN_VALUE || last > Integer.MAX_VALUE) throw new Unavailable();
        long columns;
        try { columns = Math.addExact(Math.subtractExact(last, first), 1); }
        catch (ArithmeticException outside) { throw new Unavailable(); }
        work(columns); // Pre-admit sweep generation before visiting any column.
        long present = 0, invalid = 0, outside = 0, reads = 0;
        var zero = fraction(BigDecimal.ZERO, BigDecimal.ONE); var one = fraction(BigDecimal.ONE, BigDecimal.ONE);
        for (long x = first; x <= last; x++) {
            work(1); Fraction lo = zero, hi = one;
            if (dx.signum() != 0) {
                var t0 = fraction(sub(BigDecimal.valueOf(x), x0), dx);
                var t1 = fraction(sub(BigDecimal.valueOf(x + 1), x0), dx);
                if (compare(t0, t1) > 0) { var swap = t0; t0 = t1; t1 = swap; }
                if (compare(t0, lo) > 0) lo = t0;
                if (compare(t1, hi) < 0) hi = t1;
                if (compare(lo, hi) > 0) continue;
            }
            var ya = yAt(y0, dy, lo); var yb = yAt(y0, dy, hi);
            if (compare(ya, yb) > 0) { var swap = ya; ya = yb; yb = swap; }
            long lowY, highY;
            try { lowY = Math.subtractExact(integral(ya, RoundingMode.CEILING), 1); highY = integral(yb, RoundingMode.FLOOR); }
            catch (ArithmeticException overflow) { throw new Unavailable(); }
            if (lowY < Integer.MIN_VALUE || highY > Integer.MAX_VALUE) throw new Unavailable();
            long rowCount;
            try { rowCount = Math.addExact(Math.subtractExact(highY, lowY), 1); }
            catch (ArithmeticException overflow) { throw new Unavailable(); }
            work(rowCount);
            for (long y = lowY; y <= highY; y++) {
                budget.charge(EmissionWorkBudget.Operation.CELL_QUERY, 1);
                if (x < 0 || y < 0 || x >= grid.width() - 1L || y >= grid.height() - 1L) { outside++; continue; }
                if (!grid.supportsCell((int) x, (int) y)) { invalid++; continue; }
                boolean valid = true;
                for (int iy = (int) y; iy <= y + 1; iy++) for (int ix = (int) x; ix <= x + 1; ix++) {
                    budget.charge(EmissionWorkBudget.Operation.NODE_READ, 1); reads++;
                    double value = grid.nodeValue(ix, iy);
                    valid &= Double.isFinite(value) && value >= 0 && value <= 1;
                }
                if (valid) present++; else invalid++;
            }
        }
        return new Counts(present, invalid, outside, reads);
    }
}
