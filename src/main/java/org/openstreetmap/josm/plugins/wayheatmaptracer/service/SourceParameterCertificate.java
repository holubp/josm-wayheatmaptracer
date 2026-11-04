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
    /** Producer-parameter alias with explicit outward coordinate roundoff enclosures. */
    public record SourceAlias(int row, int originalVertex, double rawFraction,
            double projectedErrorUpper, double metricErrorUpperMeters) { }

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
    private final List<Arc> rowArcs;
    private final List<Arc> vertexArcs;
    private final List<SourceAlias> aliases;
    private final EmissionWorkBudget budget;
    private final AttemptMemoryLedger.Owner owner;

    private SourceParameterCertificate(List<PolylineMath.SourceParameter> rows,
            List<Double> rowS, List<Double> sourceVertexS, List<Double> adjacentUpper,
            List<OriginalOccurrence> originalOccurrences, List<Arc> rowArcs,
            List<Arc> vertexArcs, List<SourceAlias> aliases,
            EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner) {
        this.rows = rows;
        this.rowS = rowS;
        this.sourceVertexS = sourceVertexS;
        this.adjacentUpper = adjacentUpper;
        this.originalOccurrences = originalOccurrences;
        this.rowArcs = rowArcs; this.vertexArcs = vertexArcs; this.aliases = aliases;
        this.budget = budget;
        this.owner = owner;
    }

    /** Certifies the exact dense rows produced by the managed sampler's parameter-recording loop. */
    public static SourceParameterCertificate fromManagedProducer(
            TileHeatmapSampler.PreparedManagedCapture capture,
            List<PolylineMath.SourceParameter> producedRows, List<EastNorth> dense,
            List<ProfileSamplingAnchor> anchors,
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
                    Math.multiplyExact((long) producedRows.size(), 384L),
                    Math.multiplyExact((long) original.size(), 512L)));
            var reservation = owner.reserve(bytes);
            double worldSize = TileHeatmapSampler.TILE_SIZE * Math.scalb(1.0, capture.selectedMosaic().zoom());
            double eastScale = ground.frame().distortionCertificate().eastMetersPerRadian();
            double northScale = ground.frame().distortionCertificate().northMetersPerRadian();
            List<Arc> segments = new ArrayList<>(world.size() - 1);
            List<Double> vertexS = new ArrayList<>(world.size());
            List<Arc> vertexArcs = new ArrayList<>(world.size());
            vertexArcs.add(new Arc(0, 0, 0));
            vertexS.add(0.0);
            for (int segment = 0; segment < world.size() - 1; segment++) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                Arc full = integrate(world.get(segment), world.get(segment + 1), 0.0, 1.0,
                        worldSize, eastScale, northScale, budget);
                if (full.lower() <= 0.0) throw new IllegalArgumentException("Source segment has no ground length");
                segments.add(full);
                vertexS.add(vertexS.get(segment) + full.numerical());
                vertexArcs.add(add(vertexArcs.get(segment), full));
            }
            List<Double> rowS = new ArrayList<>(producedRows.size());
            List<Double> adjacentUpper = new ArrayList<>(producedRows.size() - 1);
            List<Arc> rowArcs = new ArrayList<>(producedRows.size());
            List<SourceAlias> aliases = new ArrayList<>();
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
                // Preserve the native fraction/point; only its certified source parameter aliases
                // the original final boundary under a separately retained roundoff enclosure.
                boolean finalAlias = row == producedRows.size() - 1
                    && segment == segments.size() - 1 && Math.abs(t - 1.0) <= Math.ulp(1.0);
                if (t > 1.0 && !finalAlias) throw new IllegalArgumentException("Nonterminal source parameter exceeds segment");
                double certifiedT = finalAlias ? 1.0 : t;
                Arc partial = integrate(world.get(segment), world.get(segment + 1), 0.0, certifiedT,
                        worldSize, eastScale, northScale, budget);
                if (finalAlias) partial = segments.get(segment);
                Arc rowArc = add(vertexArcs.get(segment), partial);
                double s = certifiedT == 1.0 ? vertexS.get(segment + 1) : rowArc.numerical();
                rowArcs.add(new Arc(s, rowArc.lower(), rowArc.upper()));
                int aliasVertex = certifiedT == 0.0 ? segment : certifiedT == 1.0 ? segment + 1 : -1;
                if (aliasVertex >= 0) aliases.add(certifyAlias(row, aliasVertex, parameter,
                    original, anchors.get(row).rasterPoint(), ground, capture, budget));
                if (previous != null) {
                    if (segment < previous.segmentIndex()
                            || segment == previous.segmentIndex() && t <= previous.fraction()) {
                        throw new IllegalArgumentException("Dense source parameters are not strictly ordered");
                    }
                    var certifiedPrevious = normalizedBoundary(previous, row == producedRows.size() - 1);
                    var certifiedCurrent = normalizedBoundary(parameter, finalAlias);
                    Arc span = span(world, certifiedPrevious, certifiedCurrent, worldSize, eastScale, northScale, budget);
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
                    List.copyOf(rowArcs), List.copyOf(vertexArcs), List.copyOf(aliases),
                    budget, owner);
            reservation.adopt(result);
            complete = true;
            return result;
        } finally {
            if (!complete) owner.close();
        }
    }
    private static PolylineMath.SourceParameter normalizedBoundary(PolylineMath.SourceParameter p, boolean alias) {
        return alias && Math.abs(p.fraction() - 1.0) <= Math.ulp(1.0)
            ? new PolylineMath.SourceParameter(p.segmentIndex(), 1.0, p.point()) : p;
    }
    private static Arc add(Arc a, Arc b) {
        double numerical = a.numerical() + b.numerical();
        return new Arc(numerical, Math.max(0, Math.nextDown(a.lower() + b.lower())),
                Math.nextUp(a.upper() + b.upper()));
    }
    private static SourceAlias certifyAlias(int row, int vertex, PolylineMath.SourceParameter parameter,
            List<EastNorth> original, RasterPoint actualRaster, ManagedGroundTransform ground,
            TileHeatmapSampler.PreparedManagedCapture capture, EmissionWorkBudget budget) {
        budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
        EastNorth target = original.get(vertex), point = parameter.point();
        double coordinateRoundoff = 32.0 * Math.max(Math.ulp(Math.abs(target.east())),
                Math.ulp(Math.abs(target.north())));
        double fractionRoundoff = Math.abs(parameter.fraction() - (vertex == parameter.segmentIndex() ? 0.0 : 1.0))
                * original.get(parameter.segmentIndex()).distance(original.get(parameter.segmentIndex() + 1));
        double projectedUpper = Math.nextUp(coordinateRoundoff + fractionRoundoff);
        if (point.distance(target) > projectedUpper) {
            throw new IllegalArgumentException("Producer endpoint alias exceeds its arithmetic roundoff enclosure");
        }
        // EPSG:3857 projected metres bound local ground displacement. The captured frame
        // certificate provides the geographic scale; 2x encloses that scale plus inverse roundoff.
        double metricUpper = Math.nextUp(projectedUpper * 2.0 + 1e-6);
        if (capture.selectionWitness().isPresent()) {
            var coordinate = capture.selectionWitness().orElseThrow().frozenSelectedFacts().get(vertex).coordinate();
            MetricPoint exact = ground.frame().toMetric(new GeographicPoint(coordinate.lat(), coordinate.lon()));
            if (ground.metricAt(actualRaster).distanceTo(exact) > metricUpper) {
                throw new IllegalArgumentException("Source-parameter alias exceeds its retained metric roundoff enclosure");
            }
        }
        return new SourceAlias(row, vertex, parameter.fraction(), projectedUpper, metricUpper);
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
    public Arc rowArc(int row) { requireOpen(); return rowArcs.get(row); }
    public Arc originalVertexArc(int vertex) { requireOpen(); return vertexArcs.get(vertex); }
    public List<SourceAlias> aliases() { requireOpen(); return aliases; }
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
