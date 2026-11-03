package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.ManagedSelectionWitness;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Source-curve arclength for managed Legacy rows, separate from native dense-chord chainage. */
public final class SourceParameterCertificate {
    private static final int SUBDIVISIONS = 64;
    private static final double ROUNDING_PAD_METERS_PER_SUBINTERVAL = 1e-7;

    /** Numerical integral and an outward enclosure of the same source-curve arc. */
    public record Arc(double numerical, double lower, double upper) {
        public Arc {
            if (!Double.isFinite(numerical) || !Double.isFinite(lower) || !Double.isFinite(upper)
                    || lower < 0.0 || numerical < lower || numerical > upper) {
                throw new IllegalArgumentException("Uncertified source arc");
            }
        }
    }

    /** Frozen original selected occurrence and its source-parameter bracket. */
    public record OriginalOccurrence(String occurrenceId, int fullWayIndex, long osmNodeId,
            LatLon exactGeographic, MetricPoint exactMetric, double sourceChainageMeters,
            int leftRow, int rightRow, boolean protectedOccurrence, boolean explicitlyFixed) {
        public OriginalOccurrence {
            if (occurrenceId == null || occurrenceId.isBlank() || fullWayIndex < 0
                    || exactGeographic == null || exactMetric == null
                    || !Double.isFinite(sourceChainageMeters) || sourceChainageMeters < 0.0
                    || leftRow < 0 || rightRow != leftRow + 1) {
                throw new IllegalArgumentException("Uncertified original occurrence");
            }
        }
    }

    private final List<PolylineMath.SourceParameter> rows;
    private final List<Double> rowS;
    private final List<Double> sourceVertexS;
    private final List<Double> adjacentUpper;
    private final List<OriginalOccurrence> originalOccurrences;
    private final EmissionWorkBudget budget;
    private final AttemptMemoryLedger.Owner owner;

    private SourceParameterCertificate(List<PolylineMath.SourceParameter> rows,
            List<Double> rowS, List<Double> sourceVertexS, List<Double> adjacentUpper,
            List<OriginalOccurrence> originalOccurrences,
            EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner) {
        this.rows = rows;
        this.rowS = rowS;
        this.sourceVertexS = sourceVertexS;
        this.adjacentUpper = adjacentUpper;
        this.originalOccurrences = originalOccurrences;
        this.budget = budget;
        this.owner = owner;
    }

    /** Certifies the exact dense rows produced by the managed sampler's parameter-recording loop. */
    public static SourceParameterCertificate fromManagedProducer(
            TileHeatmapSampler.PreparedManagedCapture capture,
            List<PolylineMath.SourceParameter> producedRows, List<EastNorth> dense,
            ManagedGroundTransform ground, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner parent) {
        Objects.requireNonNull(capture, "prepared capture");
        Objects.requireNonNull(producedRows, "producer parameters");
        Objects.requireNonNull(dense, "producer anchors");
        Objects.requireNonNull(ground, "certified ground transform");
        Objects.requireNonNull(budget, "shared work budget");
        Objects.requireNonNull(parent, "attempt owner");
        var original = capture.originalRequest().originalSource();
        var world = capture.originalRequest().originalWorldPoints();
        if (producedRows.size() < 2 || producedRows.size() != dense.size()
                || original.size() != world.size() || original.size() < 2) {
            throw new IllegalArgumentException("Source parameters differ from the managed producer");
        }
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        var owner = parent.child("managed-source-parameter-certificate");
        boolean complete = false;
        try {
            long bytes = Math.addExact(4096L, Math.addExact(
                    Math.multiplyExact((long) producedRows.size(), 160L),
                    Math.multiplyExact((long) original.size(), 256L)));
            var reservation = owner.reserve(bytes);
            double worldSize = TileHeatmapSampler.TILE_SIZE * Math.scalb(1.0, capture.selectedMosaic().zoom());
            double eastScale = ground.frame().distortionCertificate().eastMetersPerRadian();
            double northScale = ground.frame().distortionCertificate().northMetersPerRadian();
            List<Arc> segments = new ArrayList<>(world.size() - 1);
            List<Double> vertexS = new ArrayList<>(world.size());
            vertexS.add(0.0);
            for (int segment = 0; segment < world.size() - 1; segment++) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                Arc full = integrate(world.get(segment), world.get(segment + 1), 0.0, 1.0,
                        worldSize, eastScale, northScale, budget);
                if (full.lower() <= 0.0) throw new IllegalArgumentException("Source segment has no ground length");
                segments.add(full);
                vertexS.add(vertexS.get(segment) + full.numerical());
            }
            List<Double> rowS = new ArrayList<>(producedRows.size());
            List<Double> adjacentUpper = new ArrayList<>(producedRows.size() - 1);
            PolylineMath.SourceParameter previous = null;
            for (int row = 0; row < producedRows.size(); row++) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                var parameter = producedRows.get(row);
                if (parameter.segmentIndex() >= segments.size()
                        || !sameBits(parameter.point(), dense.get(row))
                        || !sameInterpolatedPoint(original, parameter)) {
                    throw new IllegalArgumentException("Dense row differs from its original source parameter");
                }
                int segment = parameter.segmentIndex();
                double t = parameter.fraction();
                Arc partial = integrate(world.get(segment), world.get(segment + 1), 0.0, t,
                        worldSize, eastScale, northScale, budget);
                double s = vertexS.get(segment) + partial.numerical();
                if (previous != null) {
                    if (segment < previous.segmentIndex()
                            || segment == previous.segmentIndex() && t <= previous.fraction()) {
                        throw new IllegalArgumentException("Dense source parameters are not strictly ordered");
                    }
                    Arc span = span(world, previous, parameter, worldSize, eastScale, northScale, budget);
                    adjacentUpper.add(span.upper());
                    if (span.lower() <= 0.0 || s <= rowS.get(row - 1)) {
                        throw new IllegalArgumentException("Dense source arclength is ambiguous");
                    }
                }
                rowS.add(s);
                previous = parameter;
            }
            if (producedRows.get(0).segmentIndex() != 0
                    || producedRows.get(0).fraction() != 0.0
                    || producedRows.get(producedRows.size() - 1).segmentIndex() != segments.size() - 1
                    || Math.abs(producedRows.get(producedRows.size() - 1).fraction() - 1.0) > 1e-12) {
                throw new IllegalArgumentException("Dense rows do not span the original source");
            }
            List<OriginalOccurrence> originalOccurrences = new ArrayList<>();
            if (capture.selectionWitness().isPresent()) {
                ManagedSelectionWitness witness = capture.selectionWitness().orElseThrow();
                var frozen = witness.frozenSelectedFacts();
                if (frozen.size() != original.size()) {
                    throw new IllegalArgumentException("Frozen selected occurrences differ from source parameters");
                }
                int nextRow = 0;
                for (int vertex = 0; vertex < frozen.size(); vertex++) {
                    budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                    var fact = frozen.get(vertex);
                    if (!sameBits(fact.projectedSource(), original.get(vertex))) {
                        throw new IllegalArgumentException("Frozen occurrence changed from original source");
                    }
                    while (nextRow < producedRows.size()
                            && parameterOrder(producedRows.get(nextRow)) <= vertex) {
                        budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                        nextRow++;
                    }
                    int left = Math.max(0, Math.min(producedRows.size() - 2, nextRow - 1));
                    int right = left + 1;
                    double s = vertexS.get(vertex);
                    if (s < rowS.get(left) - 1e-7 || s > rowS.get(right) + 1e-7) {
                        throw new IllegalArgumentException("Protected occurrence source bracket is ambiguous");
                    }
                    LatLon coordinate = fact.coordinate();
                    MetricPoint exact = ground.frame().toMetric(new GeographicPoint(
                            coordinate.lat(), coordinate.lon()));
                    originalOccurrences.add(new OriginalOccurrence(
                            witness.selectedWayUniqueId() + ":" + fact.fullWayIndex() + ":" + fact.uniqueId(),
                            fact.fullWayIndex(), fact.uniqueId(), coordinate, exact, s,
                            left, right, fact.protectedOccurrence(), fact.explicitlyFixed()));
                }
            }
            var result = new SourceParameterCertificate(List.copyOf(producedRows), List.copyOf(rowS),
                    List.copyOf(vertexS), List.copyOf(adjacentUpper), List.copyOf(originalOccurrences),
                    budget, owner);
            reservation.adopt(result);
            complete = true;
            return result;
        } finally {
            if (!complete) owner.close();
        }
    }

    private static Arc span(List<RasterPoint> world, PolylineMath.SourceParameter first,
            PolylineMath.SourceParameter last, double worldSize, double eastScale,
            double northScale, EmissionWorkBudget budget) {
        if (first.segmentIndex() == last.segmentIndex()) {
            return integrate(world.get(first.segmentIndex()), world.get(first.segmentIndex() + 1),
                    first.fraction(), last.fraction(), worldSize, eastScale, northScale, budget);
        }
        double numerical = 0.0, lower = 0.0, upper = 0.0;
        for (int segment = first.segmentIndex(); segment <= last.segmentIndex(); segment++) {
            double from = segment == first.segmentIndex() ? first.fraction() : 0.0;
            double to = segment == last.segmentIndex() ? last.fraction() : 1.0;
            Arc arc = integrate(world.get(segment), world.get(segment + 1), from, to,
                    worldSize, eastScale, northScale, budget);
            numerical += arc.numerical();
            lower += arc.lower();
            upper += arc.upper();
        }
        return new Arc(numerical, Math.max(0.0, Math.nextDown(lower - 1e-9)),
                Math.nextUp(upper + 1e-9));
    }

    private static Arc integrate(RasterPoint start, RasterPoint end, double from, double to,
            double worldSize, double eastScale, double northScale, EmissionWorkBudget budget) {
        if (!Double.isFinite(from) || !Double.isFinite(to) || from < 0 || to > 1 || from > to) {
            throw new IllegalArgumentException("Source arc parameter is outside its segment");
        }
        if (from == to) return new Arc(0.0, 0.0, 0.0);
        double k = 2.0 * Math.PI / worldSize;
        double dx = end.x() - start.x(), dy = end.y() - start.y();
        double east = Math.abs(dx) * k * eastScale;
        double north = Math.abs(dy) * k * northScale;
        double numerical = 0.0, lower = 0.0, upper = 0.0;
        double step = (to - from) / SUBDIVISIONS;
        budget.charge(EmissionWorkBudget.Operation.PREPARATION, SUBDIVISIONS);
        for (int piece = 0; piece < SUBDIVISIONS; piece++) {
            double a = from + piece * step;
            double b = piece == SUBDIVISIONS - 1 ? to : from + (piece + 1) * step;
            double center = (a + b) * 0.5;
            double latA = latitude(start.y() + dy * a, worldSize);
            double latB = latitude(start.y() + dy * b, worldSize);
            double latCenter = latitude(start.y() + dy * center, worldSize);
            double minCos = Math.min(Math.cos(latA), Math.cos(latB));
            double maxCos = latA * latB <= 0.0 ? 1.0 : Math.max(Math.cos(latA), Math.cos(latB));
            double width = b - a;
            numerical += width * Math.hypot(east, north * Math.cos(latCenter));
            lower += width * Math.hypot(east, north * minCos);
            upper += width * Math.hypot(east, north * maxCos);
        }
        double pad = ROUNDING_PAD_METERS_PER_SUBINTERVAL * SUBDIVISIONS;
        return new Arc(numerical, Math.max(0.0, Math.nextDown(lower - pad)),
                Math.nextUp(upper + pad));
    }

    private static double latitude(double worldY, double size) {
        return Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * worldY / size)));
    }

    private static boolean sameBits(EastNorth a, EastNorth b) {
        return Double.doubleToLongBits(a.east()) == Double.doubleToLongBits(b.east())
                && Double.doubleToLongBits(a.north()) == Double.doubleToLongBits(b.north());
    }

    private static double parameterOrder(PolylineMath.SourceParameter parameter) {
        return parameter.segmentIndex() + parameter.fraction();
    }

    private static boolean sameInterpolatedPoint(List<EastNorth> source,
            PolylineMath.SourceParameter parameter) {
        int index = parameter.segmentIndex();
        EastNorth start = source.get(index), end = source.get(index + 1);
        double fraction = parameter.fraction();
        return sameBits(parameter.point(), new EastNorth(
                start.east() + (end.east() - start.east()) * fraction,
                start.north() + (end.north() - start.north()) * fraction));
    }

    private void requireOpen() {
        if (owner.isClosed()) throw new IllegalStateException("Source parameter certificate is closed");
    }
    public int rowCount() { requireOpen(); return rows.size(); }
    public int sourceVertexCount() { requireOpen(); return sourceVertexS.size(); }
    public PolylineMath.SourceParameter rowParameter(int row) { requireOpen(); return rows.get(row); }
    public double sourceChainageMeters(int row) { requireOpen(); return rowS.get(row); }
    public double sourceVertexChainageMeters(int sourceVertexIndex) {
        requireOpen(); return sourceVertexS.get(sourceVertexIndex);
    }
    public List<OriginalOccurrence> originalOccurrences() {
        requireOpen(); return originalOccurrences;
    }
    /** Adds direct per-row arc upper bounds, never subtracts independent prefix enclosures. */
    public double sourceSpanUpperMeters(int fromRow, int toRow) {
        requireOpen();
        if (fromRow < 0 || toRow >= rows.size() || toRow < fromRow) {
            throw new IllegalArgumentException("Source span rows are invalid");
        }
        budget.charge(EmissionWorkBudget.Operation.PREPARATION, toRow - fromRow);
        double upper = 0.0;
        for (int row = fromRow; row < toRow; row++) upper = Math.nextUp(upper + adjacentUpper.get(row));
        return upper;
    }
}
