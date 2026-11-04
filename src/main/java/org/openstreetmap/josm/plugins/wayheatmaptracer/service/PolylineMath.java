package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import org.openstreetmap.josm.data.coor.EastNorth;

/**
 * Geometry helpers for resampling, interpolating, and projecting points along polylines.
 */
public final class PolylineMath {
    /**
     * Caller-admitted capacity for one endpoint arithmetic proof. A finite binary64 exact
     * decimal has at most 1075 coefficient digits; two-operand products have at most 2150.
     * Divisions retain 80 digits. The fixed straight-line proof has fewer than 24 live
     * operands/results; 64 KiB includes their magnitude arrays and temporary arithmetic.
     * This is an attempt-ledger scratch declaration, not a whole-JVM heap measurement.
     */
    static final long ENDPOINT_ARITHMETIC_SCRATCH_BYTES = 64L * 1024L;
    private PolylineMath() {
    }

    /**
     * Resamples a polyline to approximately equal point spacing.
     *
     * @param polyline source geometry in projected coordinates
     * @param spacing requested spacing in meters
     * @return resampled geometry including original endpoints
     */
    public static List<EastNorth> resampleBySpacing(List<EastNorth> polyline, double spacing) {
        if (polyline.size() < 2) {
            return polyline;
        }
        double total = length(polyline);
        int count = Math.max(2, (int) Math.round(total / Math.max(1.0, spacing)) + 1);
        return resampleByCount(polyline, count);
    }

    /**
     * Resamples a polyline to a fixed number of evenly spaced points.
     *
     * @param polyline source geometry in projected coordinates
     * @param count requested point count
     * @return resampled geometry including endpoints when possible
     */
    public static List<EastNorth> resampleByCount(List<EastNorth> polyline, int count) {
        if (polyline.isEmpty()) {
            return List.of();
        }
        if (polyline.size() == 1 || count <= 1) {
            return List.of(polyline.get(0));
        }

        double total = length(polyline);
        if (total == 0.0) {
            return new ArrayList<>(polyline);
        }

        List<EastNorth> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double target = total * i / (count - 1.0);
            result.add(interpolate(polyline, target));
        }
        return result;
    }

    /** The exact source segment and interpolation fraction selected for a dense producer row. */
    public record SourceParameter(int segmentIndex, double fraction, EastNorth point,
            EndpointArithmeticEnclosure endpointEnclosure) {
        public SourceParameter(int segmentIndex, double fraction, EastNorth point) {
            this(segmentIndex, fraction, point, null);
        }
        public SourceParameter {
            if (segmentIndex < 0 || !Double.isFinite(fraction) || fraction < 0.0
                    || fraction > 1.0 && endpointEnclosure == null || point == null) {
                throw new IllegalArgumentException("Invalid source parameter");
            }
            if (endpointEnclosure != null && !endpointEnclosure.matches(segmentIndex, fraction, point)) {
                throw new IllegalArgumentException("Endpoint arithmetic belongs to another produced row");
            }
        }
    }

    /** Exact-operation residual proof, constructible only by this native producer. */
    public static final class EndpointArithmeticEnclosure {
        private static final MathContext UP = new MathContext(80, RoundingMode.CEILING);
        private final int segment;
        private final double rawFraction;
        private final EastNorth point;
        private final double fractionErrorUpper;
        private final double projectedErrorUpper;
        private final boolean originalEndpointFallback;

        private EndpointArithmeticEnclosure(int segment, double rawFraction, EastNorth point,
                double fractionErrorUpper, double projectedErrorUpper) {
            this(segment, rawFraction, point, fractionErrorUpper, projectedErrorUpper, false);
        }
        private EndpointArithmeticEnclosure(int segment, double rawFraction, EastNorth point,
                double fractionErrorUpper, double projectedErrorUpper, boolean originalEndpointFallback) {
            this.segment = segment; this.rawFraction = rawFraction; this.point = point;
            this.fractionErrorUpper = fractionErrorUpper; this.projectedErrorUpper = projectedErrorUpper;
            this.originalEndpointFallback = originalEndpointFallback;
        }
        private boolean matches(int segment, double fraction, EastNorth point) {
            return this.segment == segment && Double.doubleToLongBits(rawFraction) == Double.doubleToLongBits(fraction)
                && Double.doubleToLongBits(this.point.east()) == Double.doubleToLongBits(point.east())
                && Double.doubleToLongBits(this.point.north()) == Double.doubleToLongBits(point.north());
        }
        public double fractionErrorUpper() { return fractionErrorUpper; }
        public double projectedErrorUpper() { return projectedErrorUpper; }
        public boolean originalEndpointFallback() { return originalEndpointFallback; }
        private static BigDecimal exact(double value) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Endpoint arithmetic is not finite");
            return new BigDecimal(value);
        }
        private static BigDecimal error(double actual, BigDecimal exact) { return exact(actual).subtract(exact).abs(); }
        private static double outward(BigDecimal bound) {
            double result = bound.doubleValue();
            if (!Double.isFinite(result)) throw new IllegalArgumentException("Endpoint arithmetic enclosure overflow");
            return exact(result).compareTo(bound) < 0 ? Math.nextUp(result) : result;
        }
        private static BigDecimal axisError(double a, double b, double t, BigDecimal fractionError) {
            double d = b - a, product = d * t, output = a + product;
            return error(d, exact(b).subtract(exact(a)))
                .add(exact(d).abs().multiply(fractionError))
                .add(error(product, exact(d).multiply(exact(t))))
                .add(error(output, exact(a).add(exact(product))));
        }
        private static EndpointArithmeticEnclosure finalRow(int segment, EastNorth a, EastNorth b,
                double walked, double distance, double total, int intervals, double target,
                double fraction, EastNorth point) {
            if (!(distance > 0) || intervals <= 0
                    || Double.doubleToLongBits(walked + distance) != Double.doubleToLongBits(total)) {
                throw new IllegalArgumentException("Endpoint cumulative arithmetic is unresolved");
            }
            double multiplied = total * intervals;
            double numerator = target - walked;
            if (Double.doubleToLongBits(multiplied / intervals) != Double.doubleToLongBits(target)
                    || Double.doubleToLongBits(numerator / distance) != Double.doubleToLongBits(fraction)) {
                throw new IllegalArgumentException("Endpoint producer operation order changed");
            }
            BigDecimal k = BigDecimal.valueOf(intervals), d = exact(distance);
            BigDecimal addError = error(total, exact(walked).add(d));
            BigDecimal multiplyError = error(multiplied, exact(total).multiply(k));
            BigDecimal targetDivideError = exact(target).multiply(k).subtract(exact(multiplied)).abs().divide(k, UP);
            BigDecimal subtractError = error(numerator, exact(target).subtract(exact(walked)));
            BigDecimal fractionDivideError = exact(fraction).multiply(d).subtract(exact(numerator)).abs().divide(d, UP);
            BigDecimal fractionError = addError.add(multiplyError.divide(k, UP)).add(targetDivideError)
                .add(subtractError).divide(d, UP).add(fractionDivideError);
            double fractionBound = outward(fractionError);
            if (Math.abs(fraction - 1.0) > fractionBound) {
                throw new IllegalArgumentException("Endpoint fraction exceeds its operation residual enclosure");
            }
            double x = outward(axisError(a.east(), b.east(), fraction, fractionError));
            double y = outward(axisError(a.north(), b.north(), fraction, fractionError));
            double projected = Math.nextUp(Math.nextUp(Math.hypot(x, y)));
            return new EndpointArithmeticEnclosure(segment, fraction, point, fractionBound, projected);
        }
    }
    /** Outward dyadic operation bound for an exact 0/1 source boundary parameter. */
    public static double boundaryProjectedErrorUpper(List<EastNorth> source, SourceParameter parameter,
            int originalVertex) {
        if (originalVertex != parameter.segmentIndex() && originalVertex != parameter.segmentIndex() + 1)
            throw new IllegalArgumentException("Source boundary is not adjacent to its producer segment");
        double canonical = originalVertex == parameter.segmentIndex() ? 0.0 : 1.0;
        if (parameter.fraction() != canonical) {
            if (canonical == 1.0 && parameter.endpointEnclosure() != null)
                return parameter.endpointEnclosure().projectedErrorUpper();
            throw new IllegalArgumentException("Source boundary has no producer arithmetic alias");
        }
        var a = source.get(parameter.segmentIndex()); var b = source.get(parameter.segmentIndex() + 1);
        double x = EndpointArithmeticEnclosure.outward(EndpointArithmeticEnclosure.axisError(
                a.east(), b.east(), canonical, BigDecimal.ZERO));
        double y = EndpointArithmeticEnclosure.outward(EndpointArithmeticEnclosure.axisError(
                a.north(), b.north(), canonical, BigDecimal.ZERO));
        return Math.nextUp(Math.nextUp(Math.hypot(x, y)));
    }

    /**
     * Records source parameters in the same interpolation loop that produces managed Legacy rows.
     * The returned point is the same object used for the row anchor; no nearest-source reconstruction occurs.
     */
    public static List<SourceParameter> resampleByCountWithParameters(List<EastNorth> polyline, int count) {
        return resampleByCountWithParameters(polyline, count, units -> { });
    }

    /** Charges each source-segment visit before the managed producer performs it. */
    public static List<SourceParameter> resampleByCountWithParameters(List<EastNorth> polyline,
            int count, java.util.function.LongConsumer work) {
        if (polyline == null || polyline.size() < 2 || count < 2) {
            throw new IllegalArgumentException("Parameterized resampling needs a source and two rows");
        }
        java.util.Objects.requireNonNull(work, "source parameter work charge");
        work.accept(polyline.size() - 1L);
        double total = length(polyline);
        if (!Double.isFinite(total) || total <= 0.0) {
            throw new IllegalArgumentException("Parameterized source has no finite length");
        }
        List<SourceParameter> result = new ArrayList<>(count);
        for (int row = 0; row < count; row++) {
            work.accept(1);
            double target = total * row / (count - 1.0);
            double walked = 0.0;
            boolean selected = false;
            for (int segmentIndex = 1; segmentIndex < polyline.size(); segmentIndex++) {
                work.accept(1);
                EastNorth start = polyline.get(segmentIndex - 1);
                EastNorth end = polyline.get(segmentIndex);
                double segment = start.distance(end);
                if (walked + segment >= target) {
                    double fraction = segment == 0.0 ? 0.0 : (target - walked) / segment;
                    EastNorth point = new EastNorth(
                        start.east() + (end.east() - start.east()) * fraction,
                        start.north() + (end.north() - start.north()) * fraction);
                    EndpointArithmeticEnclosure endpoint = null;
                    if (row == count - 1 && segmentIndex == polyline.size() - 1) {
                        work.accept(256);
                        endpoint = EndpointArithmeticEnclosure.finalRow(segmentIndex - 1, start, end, walked,
                            segment, total, count - 1, target, fraction, point);
                    }
                    result.add(new SourceParameter(segmentIndex - 1, fraction, point, endpoint));
                    selected = true;
                    break;
                }
                walked += segment;
            }
            if (!selected) {
                EastNorth endpoint = polyline.get(polyline.size() - 1);
                result.add(new SourceParameter(polyline.size() - 2, 1.0, endpoint,
                        new EndpointArithmeticEnclosure(polyline.size() - 2, 1.0, endpoint, 0, 0, true)));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Normalizes a vector, returning the unit x-axis for zero-length vectors.
     *
     * @param x vector x component
     * @param y vector y component
     * @return normalized vector
     */
    public static Point2D.Double normalize(double x, double y) {
        double norm = Math.hypot(x, y);
        if (norm == 0.0) {
            return new Point2D.Double(1.0, 0.0);
        }
        return new Point2D.Double(x / norm, y / norm);
    }

    /**
     * Computes cumulative path-length fractions for each point in a polyline.
     *
     * @param polyline source geometry in projected coordinates
     * @return fractions from {@code 0.0} at the start to {@code 1.0} at the end
     */
    public static List<Double> fractionsForSegment(List<EastNorth> polyline) {
        if (polyline.isEmpty()) {
            return List.of();
        }
        List<Double> fractions = new ArrayList<>(polyline.size());
        double total = 0.0;
        double[] cumulative = new double[polyline.size()];
        cumulative[0] = 0.0;
        for (int i = 1; i < polyline.size(); i++) {
            total += polyline.get(i - 1).distance(polyline.get(i));
            cumulative[i] = total;
        }
        if (total == 0.0) {
            for (int i = 0; i < polyline.size(); i++) {
                fractions.add(i == polyline.size() - 1 ? 1.0 : 0.0);
            }
            return fractions;
        }
        for (double value : cumulative) {
            fractions.add(value / total);
        }
        return fractions;
    }

    /**
     * Interpolates a point at a cumulative path-length fraction.
     *
     * @param polyline source geometry in projected coordinates
     * @param fractions fractions produced by {@link #fractionsForSegment(List)}
     * @param targetFraction requested fraction along the polyline
     * @return interpolated projected coordinate
     */
    public static EastNorth interpolateAtFraction(List<EastNorth> polyline, List<Double> fractions, double targetFraction) {
        if (targetFraction <= 0.0) {
            return polyline.get(0);
        }
        if (targetFraction >= 1.0) {
            return polyline.get(polyline.size() - 1);
        }
        for (int i = 1; i < polyline.size(); i++) {
            double left = fractions.get(i - 1);
            double right = fractions.get(i);
            if (targetFraction <= right) {
                double span = right - left;
                double t = span == 0.0 ? 0.0 : (targetFraction - left) / span;
                EastNorth start = polyline.get(i - 1);
                EastNorth end = polyline.get(i);
                return new EastNorth(
                    start.east() + (end.east() - start.east()) * t,
                    start.north() + (end.north() - start.north()) * t
                );
            }
        }
        return polyline.get(polyline.size() - 1);
    }

    /**
     * Projects a source point onto a polyline near a requested path fraction.
     *
     * @param polyline target polyline
     * @param fractions target polyline fractions
     * @param source point to project
     * @param sourceFraction expected fraction around which to search
     * @param window half-width of the allowed fraction search window
     * @return closest projected point and its fraction/distance
     */
    public static ProjectionOnPolyline closestPointNearFraction(
        List<EastNorth> polyline,
        List<Double> fractions,
        EastNorth source,
        double sourceFraction,
        double window
    ) {
        double minFraction = Math.max(0.0, sourceFraction - window);
        double maxFraction = Math.min(1.0, sourceFraction + window);
        ProjectionOnPolyline best = null;
        for (int i = 1; i < polyline.size(); i++) {
            double leftFraction = fractions.get(i - 1);
            double rightFraction = fractions.get(i);
            if (rightFraction < minFraction || leftFraction > maxFraction) {
                continue;
            }
            ProjectionOnPolyline projected = projectToSegment(polyline.get(i - 1), polyline.get(i), leftFraction, rightFraction, source);
            if (projected.fraction() < minFraction || projected.fraction() > maxFraction) {
                continue;
            }
            if (best == null || projected.distance() < best.distance()) {
                best = projected;
            }
        }
        if (best != null) {
            return best;
        }
        EastNorth fallback = interpolateAtFraction(polyline, fractions, sourceFraction);
        return new ProjectionOnPolyline(fallback, sourceFraction, fallback.distance(source));
    }

    private static ProjectionOnPolyline projectToSegment(
        EastNorth start,
        EastNorth end,
        double leftFraction,
        double rightFraction,
        EastNorth source
    ) {
        double dx = end.east() - start.east();
        double dy = end.north() - start.north();
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0.0
            ? 0.0
            : ((source.east() - start.east()) * dx + (source.north() - start.north()) * dy) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        EastNorth point = new EastNorth(start.east() + dx * t, start.north() + dy * t);
        double fraction = leftFraction + (rightFraction - leftFraction) * t;
        return new ProjectionOnPolyline(point, fraction, point.distance(source));
    }

    private static EastNorth interpolate(List<EastNorth> polyline, double targetDistance) {
        double walked = 0.0;
        for (int i = 1; i < polyline.size(); i++) {
            EastNorth start = polyline.get(i - 1);
            EastNorth end = polyline.get(i);
            double segment = start.distance(end);
            if (walked + segment >= targetDistance) {
                double factor = segment == 0.0 ? 0.0 : (targetDistance - walked) / segment;
                return new EastNorth(
                    start.east() + (end.east() - start.east()) * factor,
                    start.north() + (end.north() - start.north()) * factor
                );
            }
            walked += segment;
        }
        return polyline.get(polyline.size() - 1);
    }

    /**
     * Computes total polyline length.
     *
     * @param polyline projected polyline
     * @return total length in projected units/meters
     */
    public static double length(List<EastNorth> polyline) {
        double total = 0.0;
        for (int i = 1; i < polyline.size(); i++) {
            total += polyline.get(i - 1).distance(polyline.get(i));
        }
        return total;
    }

    /**
     * Result of projecting a point onto a polyline.
     *
     * @param point closest point on the polyline
     * @param fraction cumulative fraction at the closest point
     * @param distance distance from the source point to the projection
     */
    public record ProjectionOnPolyline(EastNorth point, double fraction, double distance) {
    }
}
