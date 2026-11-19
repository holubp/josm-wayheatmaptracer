package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Immutable, explicit ancestry map from final geometry slots to independent control DOFs. */
public final class FinalControlBasis {
    public static final int MAX_FINAL_POINTS = 32_768;

    /** One final slot: either an independent control, or a two-anchor affine subdivision. */
    public record Row(int finalIndex, boolean independent, int leftIndependent,
                      int rightIndependent, double weight) {
        public Row {
            if (finalIndex < 0) throw new IllegalArgumentException("Negative final index");
            if (independent) {
                if (leftIndependent != -1 || rightIndependent != -1 || weight != 0.0)
                    throw new IllegalArgumentException("Independent row cannot carry ancestry");
            } else if (leftIndependent < 0 || rightIndependent <= leftIndependent
                    || !Double.isFinite(weight) || weight <= 0.0 || weight >= 1.0) {
                throw new IllegalArgumentException("Invalid dependent-row ancestry");
            }
        }
        public static Row independent(int finalIndex) { return new Row(finalIndex, true, -1, -1, 0); }
        public static Row dependent(int finalIndex, int leftIndependent, int rightIndependent, double weight) {
            return new Row(finalIndex, false, leftIndependent, rightIndependent, weight);
        }
    }

    private final List<MetricPoint> initialCoordinates;
    private final List<Row> rows;
    private final List<Integer> independentIndices;
    private final Set<Integer> protectedIndices;
    private final boolean[] independent;
    private final long scratchReservationBytes;

    /**
     * @param initialCoordinates full ordered final geometry (one entry per slot)
     * @param rows explicit, equally sized ancestry rows in final-index order
     * @param protectedIndices fixed/exterior slots, which must be independent
     */
    public FinalControlBasis(List<MetricPoint> initialCoordinates, List<Row> rows,
                             Set<Integer> protectedIndices) {
        if (initialCoordinates == null || rows == null || protectedIndices == null)
            throw new IllegalArgumentException("Basis inputs are required");
        int n = initialCoordinates.size();
        if (n < 2 || n > MAX_FINAL_POINTS || rows.size() != n)
            throw new IllegalArgumentException("Invalid final-control count");
        for (MetricPoint point : initialCoordinates)
            if (point == null || !Double.isFinite(point.xMeters()) || !Double.isFinite(point.yMeters()))
                throw new IllegalArgumentException("Initial coordinates must be finite and complete");
        for (Row row : rows) if (row == null) throw new IllegalArgumentException("Provenance rows must be complete");
        this.initialCoordinates = List.copyOf(initialCoordinates);
        this.rows = List.copyOf(rows);
        this.independent = new boolean[n];
        List<Integer> controls = new ArrayList<>();
        int priorLeft = -1, priorRight = -1;
        double priorWeight = -1;
        for (int i = 0; i < n; i++) {
            Row row = this.rows.get(i);
            if (row == null || row.finalIndex() != i) throw new IllegalArgumentException("Missing, repeated, or out-of-order provenance row");
            if (row.independent()) { independent[i] = true; controls.add(i); }
        }
        if (controls.size() < 2 || controls.get(0) != 0 || controls.get(controls.size() - 1) != n - 1)
            throw new IllegalArgumentException("At least two ordered independent endpoint anchors are required");
        for (int i = 0; i < n; i++) {
            Row row = this.rows.get(i);
            if (row.independent()) continue;
            if (row.leftIndependent() >= n || row.rightIndependent() >= n
                    || row.leftIndependent() >= i || row.rightIndependent() <= i
                    || !independent[row.leftIndependent()] || !independent[row.rightIndependent()])
                throw new IllegalArgumentException("Dependent row must lie between independent anchors");
            int leftPosition = Collections.binarySearch(controls, row.leftIndependent());
            if (leftPosition < 0 || leftPosition + 1 >= controls.size()
                    || controls.get(leftPosition + 1) != row.rightIndependent())
                throw new IllegalArgumentException("Dependent row crosses or skips an independent anchor");
            if (row.leftIndependent() != priorLeft || row.rightIndependent() != priorRight) {
                priorLeft = row.leftIndependent(); priorRight = row.rightIndependent(); priorWeight = -1;
            }
            if (row.weight() <= priorWeight) throw new IllegalArgumentException("Dependent provenance order is not strictly increasing");
            priorWeight = row.weight();
        }
        TreeSet<Integer> fixed = new TreeSet<>();
        for (Integer index : protectedIndices) {
            if (index == null || index < 0 || index >= n || !independent[index])
                throw new IllegalArgumentException("Protected/exterior slots must be independent");
            fixed.add(index);
        }
        this.independentIndices = List.copyOf(controls);
        this.protectedIndices = Collections.unmodifiableSet(fixed);
        // Covers owned row/list/boxed-index metadata, the independent bitmap, and peak
        // lift/pullback temporaries without depending on VM object-layout details.
        this.scratchReservationBytes = Math.addExact(1024L, Math.addExact(512L * n,
                Math.addExact(64L * controls.size(), 64L * fixed.size())));
    }

    public int size() { return rows.size(); }
    public List<MetricPoint> initialCoordinates() { return initialCoordinates; }
    public List<Row> rows() { return rows; }
    public List<Integer> independentIndices() { return independentIndices; }
    public Set<Integer> protectedIndices() { return protectedIndices; }
    /** Conservative additional descriptor/list scratch reservation, in bytes. */
    public long scratchReservationBytes() { return scratchReservationBytes; }

    /** Lift independent candidate values supplied at their full final-slot indices. */
    public List<MetricPoint> lift(List<MetricPoint> independentValuesByFinalIndex) {
        requireFullList(independentValuesByFinalIndex, "independent values");
        for (int i : protectedIndices) if (!sameBits(initialCoordinates.get(i), independentValuesByFinalIndex.get(i)))
            throw new IllegalArgumentException("Protected/exterior control movement is forbidden");
        List<MetricPoint> result = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.independent()) { result.add(independentValuesByFinalIndex.get(i)); continue; }
            MetricPoint origin = initialCoordinates.get(i);
            MetricPoint left0 = initialCoordinates.get(row.leftIndependent());
            MetricPoint right0 = initialCoordinates.get(row.rightIndependent());
            MetricPoint left = independentValuesByFinalIndex.get(row.leftIndependent());
            MetricPoint right = independentValuesByFinalIndex.get(row.rightIndependent());
            double lx = left.xMeters() - left0.xMeters(), ly = left.yMeters() - left0.yMeters();
            double rx = right.xMeters() - right0.xMeters(), ry = right.yMeters() - right0.yMeters();
            if (lx == 0 && ly == 0 && rx == 0 && ry == 0) { result.add(origin); continue; }
            double dx = (1.0 - row.weight()) * lx + row.weight() * rx;
            double dy = (1.0 - row.weight()) * ly + row.weight() * ry;
            double x = dx == 0 ? origin.xMeters() : origin.xMeters() + dx;
            double y = dy == 0 ? origin.yMeters() : origin.yMeters() + dy;
            result.add(x == origin.xMeters() && y == origin.yMeters() ? origin : finitePoint(x, y));
        }
        return List.copyOf(result);
    }

    /** Pull a full-geometry gradient back through the transpose of the affine lift. */
    public List<MetricPoint> pullback(List<MetricPoint> fullGradient) {
        requireFullList(fullGradient, "full gradient");
        double[] gx = new double[rows.size()], gy = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            MetricPoint g = fullGradient.get(i);
            Row row = rows.get(i);
            if (row.independent()) { gx[i] += g.xMeters(); gy[i] += g.yMeters(); }
            else {
                double wl = 1.0 - row.weight();
                gx[row.leftIndependent()] += wl * g.xMeters(); gy[row.leftIndependent()] += wl * g.yMeters();
                gx[row.rightIndependent()] += row.weight() * g.xMeters(); gy[row.rightIndependent()] += row.weight() * g.yMeters();
            }
        }
        List<MetricPoint> result = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) result.add(independent[i] ? finitePoint(gx[i], gy[i]) : new MetricPoint(0, 0));
        return List.copyOf(result);
    }

    private void requireFullList(List<MetricPoint> values, String label) {
        if (values == null || values.size() != rows.size()) throw new IllegalArgumentException(label + " must use full final-slot indexing");
        for (MetricPoint point : values) if (point == null || !Double.isFinite(point.xMeters()) || !Double.isFinite(point.yMeters()))
            throw new IllegalArgumentException(label + " must contain finite points");
    }

    private static boolean sameBits(MetricPoint a, MetricPoint b) {
        return Double.doubleToRawLongBits(a.xMeters()) == Double.doubleToRawLongBits(b.xMeters())
                && Double.doubleToRawLongBits(a.yMeters()) == Double.doubleToRawLongBits(b.yMeters());
    }
    private static MetricPoint finitePoint(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("Control-basis arithmetic overflow");
        return new MetricPoint(x, y);
    }
}
