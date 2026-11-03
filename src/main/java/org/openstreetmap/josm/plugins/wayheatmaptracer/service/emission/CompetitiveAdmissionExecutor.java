package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/**
 * Experimental exact competitive-closure executor. Its provider is the production boundary for
 * native admission and costs; no production A entry uses this component yet.
 */
public final class CompetitiveAdmissionExecutor {
    /** Original A vertex with a stable native identity and source occurrence. */
    public record Vertex(int id, int originalRow, int phaseId, boolean numeric,
            boolean initial, boolean terminal, int tieId) { }

    /** Complete potential directed arc, including source mass and zero-geometry identity. */
    public record Arc(int id, int fromId, int toId, double sourceSpanMeters,
            int consumedRows, boolean optional, boolean zeroPhysical, int tieId) { }

    /** Exact state required to transport physical direction and last numeric memory. */
    public record CanonicalKey(int cursorId, int effectivePositiveArcId,
            int lastNumericVertexId, int phaseId) { }

    /** Charged complete native inventory, before image admission. */
    public static final class Graph {
        private final List<Vertex> vertices;
        private final List<Arc> arcs;
        private final Set<Integer> mandatoryVertexIds;
        private final double nativePitchMeters;
        private final boolean syntheticControl;
        private final EmissionWorkBudget workBudget;

        private Graph(List<Vertex> vertices, List<Arc> arcs, Set<Integer> mandatoryVertexIds,
                double nativePitchMeters, boolean syntheticControl, EmissionWorkBudget workBudget) {
            this.vertices = vertices;
            this.arcs = arcs;
            this.mandatoryVertexIds = mandatoryVertexIds;
            this.nativePitchMeters = nativePitchMeters;
            this.syntheticControl = syntheticControl;
            this.workBudget = workBudget;
        }

        /** Copies all declared states and arcs under the attempt owner before publication. */
        public static Graph create(List<Vertex> vertices, List<Arc> arcs,
                Set<Integer> mandatoryVertexIds, double pitch,
                EmissionWorkBudget budget, AttemptMemoryLedger.Owner owner) {
            return createInternal(vertices, arcs, mandatoryVertexIds, pitch, budget, owner, false);
        }

        /** Package-local graph for adversarial exhaustive controls with synthetic costs. */
        static Graph createSynthetic(List<Vertex> vertices, List<Arc> arcs,
                Set<Integer> mandatoryVertexIds, double pitch,
                EmissionWorkBudget budget, AttemptMemoryLedger.Owner owner) {
            return createInternal(vertices, arcs, mandatoryVertexIds, pitch, budget, owner, true);
        }

        private static Graph createInternal(List<Vertex> vertices, List<Arc> arcs,
                Set<Integer> mandatoryVertexIds, double pitch,
                EmissionWorkBudget budget, AttemptMemoryLedger.Owner owner, boolean syntheticControl) {
            Objects.requireNonNull(vertices, "vertices");
            Objects.requireNonNull(arcs, "arcs");
            Objects.requireNonNull(mandatoryVertexIds, "mandatoryVertexIds");
            Objects.requireNonNull(budget, "budget");
            Objects.requireNonNull(owner, "owner");
            long bytes = Math.addExact(AttemptMemoryLedger.objectBytes(128),
                Math.addExact(AttemptMemoryLedger.referenceArrayBytes(vertices.size()),
                    AttemptMemoryLedger.referenceArrayBytes(arcs.size())));
            bytes = Math.addExact(bytes, Math.addExact(Math.multiplyExact(vertices.size(), 96L),
                Math.multiplyExact(arcs.size(), 128L)));
            bytes = Math.addExact(bytes, Math.multiplyExact(mandatoryVertexIds.size(), 64L));
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 3);
            try (var reservation = owner.reserve(bytes)) {
                Graph result = new Graph(List.copyOf(vertices), List.copyOf(arcs),
                    Set.copyOf(mandatoryVertexIds), pitch, syntheticControl, budget);
                reservation.adopt(result);
                return result;
            }
        }

        public List<Vertex> vertices() { return vertices; }
        public List<Arc> arcs() { return arcs; }
        public Set<Integer> mandatoryVertexIds() { return mandatoryVertexIds; }
        public double nativePitchMeters() { return nativePitchMeters; }
        boolean syntheticControl() { return syntheticControl; }
        EmissionWorkBudget workBudget() { return workBudget; }
    }

    /** Source-owned exact native admission and cost provider. */
    public interface TransitionProvider {
        /** The same retained native grid that supplies scalar costs to the admission adapter. */
        BilinearSegmentIntegral.ReadOnlyScalarGrid nativeGrid();
        /** Certifies actual admission and unchanged cost, retaining any receipt under the source owner. */
        Admission certify(CanonicalKey prefix, Arc arc);
    }

    /** An actual measured admission or refusal, with source-bound Q on success. */
    public static final class Admission {
        private enum Provenance { MEASURED, ORDINARY_UNAVAILABLE, SYNTHETIC_CONTROL, REFUSED }
        private final boolean admitted;
        private final String reason;
        private final double q, c, t, k;
        private final Object receipt;
        private final BilinearSegmentIntegral.Result scalar;
        private final Provenance provenance;

        private Admission(boolean admitted, String reason, double q, double c, double t, double k,
                Object receipt, BilinearSegmentIntegral.Result scalar, Provenance provenance) {
            this.admitted = admitted;
            this.reason = Objects.requireNonNull(reason, "reason");
            this.q = q; this.c = c; this.t = t; this.k = k;
            this.receipt = receipt; this.scalar = scalar; this.provenance = provenance;
            if (admitted && !reason.equals("NONE") || !admitted && reason.equals("NONE"))
                throw new IllegalArgumentException("Admission status and reason disagree");
        }

        /** Computes unchanged Q from the exact complete native integral; provider retains its receipt. */
        public static Admission measured(double sourceSpanMeters, double pitchMeters,
                BilinearSegmentIntegral.Result scalar, double c, double t, double k, Object receipt) {
            Objects.requireNonNull(scalar, "scalar");
            if (scalar.status() != BilinearSegmentIntegral.Status.COMPLETE
                    || !Double.isFinite(sourceSpanMeters) || sourceSpanMeters < 0
                    || !Double.isFinite(pitchMeters) || pitchMeters <= 0)
                throw new IllegalArgumentException("measured Q requires complete native scalar and positive pitch");
            double q = sourceSpanMeters / pitchMeters * (1.0 - scalar.mean().orElseThrow());
            return new Admission(true, "NONE", q, c, t, k, receipt, scalar, Provenance.MEASURED);
        }

        /** Applies the approved ordinary-unavailable loss one, with no fabricated scalar receipt. */
        public static Admission ordinaryUnavailable(double sourceSpanMeters, double pitchMeters,
                double c, double t, double k, Object receipt) {
            if (!Double.isFinite(sourceSpanMeters) || sourceSpanMeters < 0
                    || !Double.isFinite(pitchMeters) || pitchMeters <= 0)
                throw new IllegalArgumentException("ordinary unavailable Q requires positive pitch");
            return new Admission(true, "NONE", sourceSpanMeters / pitchMeters,
                c, t, k, receipt, null, Provenance.ORDINARY_UNAVAILABLE);
        }

        /** Refuses the actual native transition with no invented cost or image evidence. */
        public static Admission refused(String reason) {
            return new Admission(false, Objects.requireNonNull(reason, "reason"), 0, 0, 0, 0,
                null, null, Provenance.REFUSED);
        }

        /** Synthetic-only cost hook for exhaustive numerical controls in this package. */
        static Admission syntheticExact(double q, double c, double t, double k, Object receipt) {
            return new Admission(true, "NONE", q, c, t, k, receipt, null,
                Provenance.SYNTHETIC_CONTROL);
        }

        public boolean admitted() { return admitted; }
        public String reason() { return reason; }
        public double q() { return q; }
        public double c() { return c; }
        public double t() { return t; }
        public double k() { return k; }
        public Object receipt() { return receipt; }
        public java.util.Optional<BilinearSegmentIntegral.Result> scalar() {
            return java.util.Optional.ofNullable(scalar);
        }
    }

    public enum Status { CERTIFIED, NO_ROUTE }

    /** Actual measured transition retained for the later A canonical DP. */
    public record MeasuredTransition(CanonicalKey prefix, int arcId, boolean admitted,
            String reason, double q, double c, double t, double k, Object receipt) { }

    /** Live frontier obligation excluded only by the strict final lower-bound proof. */
    public record PendingProof(CanonicalKey prefix, int arcId, double certifiedPrefixCost,
            double sourceSpanMeters, double scalarLowerBound, double lowerBound,
            boolean laterIncrementsNonnegative, boolean admissionAlreadyMeasured,
            long prefixVersion) { }

    /** Charged closure snapshot; this is not yet a production A candidate. */
    public record Result(Status status, List<Integer> vertexIds, List<Integer> arcIds,
            double cost, double q, double c, double t, double k,
            int potentialArcs, int measuredAdmitted, int measuredRefused,
            int unmeasuredCertifiedNoncompetitive, double finalMinimumLowerBound,
            double certifiedUpperBound, double certifiedIntensityUpperBound,
            List<MeasuredTransition> measuredTransitions,
            List<PendingProof> pendingProofs) { }

    /** A bound or source/cost claim disagreed with a measured transition. */
    public static final class InvalidCertificateException extends IllegalStateException {
        public InvalidCertificateException(String message) { super(message); }
    }

    private record TransitionKey(CanonicalKey prefix, int arcId) { }

    private static final class Prefix {
        final CanonicalKey key;
        final Prefix parent;
        final Arc via;
        final double cost;
        final double q;
        final double c;
        final double t;
        final double k;
        final int consumed;
        final int depth;
        final long serial;

        Prefix(CanonicalKey key, Prefix parent, Arc via, double cost,
                double q, double c, double t, double k, int consumed, long serial) {
            this.key = key;
            this.parent = parent;
            this.via = via;
            this.cost = cost;
            this.q = q;
            this.c = c;
            this.t = t;
            this.k = k;
            this.consumed = consumed;
            this.depth = parent == null ? 1 : parent.depth + 1;
            this.serial = serial;
        }
    }

    private static final class Pending {
        final Prefix prefix;
        final Arc arc;
        final double scalarLower;
        final double lower;
        final boolean laterIncrementsNonnegative;
        final long serial;
        AttemptMemoryLedger.MemoryLease lease;

        Pending(Prefix prefix, Arc arc, double scalarLower, double lower,
                boolean laterIncrementsNonnegative, long serial) {
            this.prefix = prefix;
            this.arc = arc;
            this.scalarLower = scalarLower;
            this.lower = lower;
            this.laterIncrementsNonnegative = laterIncrementsNonnegative;
            this.serial = serial;
        }
    }

    private final Graph graph;
    private final TransitionProvider provider;
    private final EmissionWorkBudget budget;
    private final AttemptMemoryLedger.Owner parentOwner;
    private boolean used;
    private long serial;

    public CompetitiveAdmissionExecutor(Graph graph, TransitionProvider provider,
            EmissionWorkBudget budget, AttemptMemoryLedger.Owner parentOwner) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.parentOwner = Objects.requireNonNull(parentOwner, "parentOwner");
    }

    /** Runs one charged attempt; a cap/cancel/source contradiction publishes no partial winner. */
    public synchronized Result run() {
        if (used) throw new IllegalStateException("competitive attempt was already used");
        used = true;
        if (graph.workBudget() != budget)
            throw new IllegalArgumentException("graph and executor must share one work budget");
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        parentOwner.retainIfAbsent(graph);
        try (var scope = parentOwner.child("a-competitive-closure")) {
            validateGraph();
            // Package-local synthetic raw costs have no source proof. They can
            // exercise the exhaustive oracle but can never justify deferred admission.
            double upperIntensity = graph.syntheticControl()
                ? Double.POSITIVE_INFINITY : certifiedNativeUpperBound();
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 3);
            // Retained collection capacity is charged separately from live entry leases.
            scope.reserve(384);
            PriorityQueue<Pending> queue = new PriorityQueue<>(pendingOrder());
            Map<TransitionKey, Admission> measured = new HashMap<>();
            List<Prefix> reached = new ArrayList<>();
            int[] edgeStarts = edgeStarts(scope);
            for (Vertex vertex : graph.vertices()) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                if (!vertex.initial()) continue;
                Prefix seed = retainPrefix(scope, reached, vertex.id(), -1,
                    vertex.numeric() ? vertex.id() : -1, vertex.phaseId(),
                    null, null, 0, 0, 0, 0, 0, 0);
                enqueueOutgoing(scope, queue, seed, edgeStarts, upperIntensity);
            }
            Prefix best = null;
            int admitted = 0;
            int refused = 0;
            while (!queue.isEmpty()) {
                budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
                Pending pending = queue.peek();
                if (best != null && pending.lower > best.cost) break;
                pending = queue.remove();
                try {
                    budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
                    budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
                    Admission admission;
                    try (var keyReservation = scope.reserve(320)) {
                        TransitionKey cacheKey = new TransitionKey(pending.prefix.key, pending.arc.id());
                        admission = measured.get(cacheKey);
                        if (admission == null) {
                            budget.charge(EmissionWorkBudget.Operation.TRANSITION, 1);
                            admission = Objects.requireNonNull(provider.certify(pending.prefix.key, pending.arc),
                                "provider admission");
                            keyReservation.adopt(cacheKey);
                            scope.reserve(64); // retained map bucket/capacity allowance
                            measured.put(cacheKey, admission);
                            if (admission.admitted()) admitted++; else refused++;
                        }
                    }
                    if (admission.admitted()) {
                        verifyAdmitted(pending.arc, admission, upperIntensity);
                        Prefix next = extend(scope, reached, pending.prefix, pending.arc, admission);
                        if (graph.vertices().get(next.key.cursorId()).terminal()) {
                            if (best == null || compareTerminal(next, best) < 0) best = next;
                        } else {
                            enqueueOutgoing(scope, queue, next, edgeStarts, upperIntensity);
                        }
                    }
                } finally {
                    pending.lease.close();
                }
            }
            int unmeasured = 0;
            for (Pending pending : queue) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
                try (var scratch = scope.reserve(64)) {
                    if (!measured.containsKey(new TransitionKey(pending.prefix.key, pending.arc.id())))
                        unmeasured++;
                }
            }
            double minLower = queue.isEmpty() ? Double.POSITIVE_INFINITY : queue.peek().lower;
            if (best == null && !queue.isEmpty())
                throw new InvalidCertificateException("frontier cannot close without a feasible incumbent");
            if (best != null && !(minLower > best.cost))
                throw new InvalidCertificateException("unmeasured frontier is not strictly worse");
            return snapshotResult(best, admitted, refused, unmeasured, minLower,
                upperIntensity, measured, queue);
        }
    }

    private void validateGraph() {
        if (!Double.isFinite(graph.nativePitchMeters()) || graph.nativePitchMeters() <= 0
                || graph.vertices().isEmpty()) throw new IllegalArgumentException("positive pitch and vertices required");
        int initials = 0, terminals = 0;
        for (int i = 0; i < graph.vertices().size(); i++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            Vertex vertex = graph.vertices().get(i);
            if (vertex.id() != i || vertex.originalRow() < 0)
                throw new IllegalArgumentException("vertices require contiguous IDs and original rows");
            if (vertex.initial()) initials++;
            if (vertex.terminal()) terminals++;
        }
        if (initials == 0 || terminals == 0) throw new IllegalArgumentException("initial and terminal states required");
        for (int mandatoryId : graph.mandatoryVertexIds()) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            if (mandatoryId < 0 || mandatoryId >= graph.vertices().size())
                throw new IllegalArgumentException("mandatory occurrence is outside the graph");
            for (Vertex vertex : graph.vertices()) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                if (vertex.initial() && vertex.id() > mandatoryId
                        || vertex.terminal() && vertex.id() < mandatoryId)
                    throw new IllegalArgumentException("mandatory occurrence is outside a terminal path");
            }
        }
        int previousFrom = -1;
        for (int i = 0; i < graph.arcs().size(); i++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            Arc arc = graph.arcs().get(i);
            if (arc.id() != i || arc.fromId() < 0 || arc.toId() >= graph.vertices().size()
                    || arc.toId() <= arc.fromId() || arc.fromId() < previousFrom
                    || !Double.isFinite(arc.sourceSpanMeters()) || arc.sourceSpanMeters() < 0
                    || arc.consumedRows() < 0 || arc.optional() &&
                        (arc.consumedRows() > 16 || arc.sourceSpanMeters() > 20))
                throw new IllegalArgumentException("potential arcs must be complete, sorted and within hard gates");
            if (graph.vertices().get(arc.fromId()).originalRow()
                    > graph.vertices().get(arc.toId()).originalRow())
                throw new IllegalArgumentException("source order must be monotone");
            for (int mandatoryId : graph.mandatoryVertexIds()) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                if (arc.fromId() < mandatoryId && arc.toId() > mandatoryId)
                    throw new IllegalArgumentException("potential arc skips a protected occurrence");
            }
            previousFrom = arc.fromId();
        }
    }

    private double certifiedNativeUpperBound() {
        var grid = Objects.requireNonNull(provider.nativeGrid(), "retained native grid");
        if (grid.width() < 2 || grid.height() < 2)
            throw new IllegalArgumentException("native grid must contain a cell");
        double max = Double.NEGATIVE_INFINITY;
        for (int y = 0; y < grid.height(); y++) for (int x = 0; x < grid.width(); x++) {
            budget.charge(EmissionWorkBudget.Operation.NODE_READ, 1);
            double value = grid.nodeValue(x, y);
            if (!Double.isFinite(value)) return Double.POSITIVE_INFINITY;
            max = Math.max(max, value);
        }
        // BilinearSegmentIntegral clamps complete samples to [0,1]. The larger
        // bound is a conservative fallback for a future unnormalized provider.
        return max <= 1.0 ? 1.0 : Math.nextUp(max);
    }

    private int[] edgeStarts(AttemptMemoryLedger.Owner scope) {
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        try (var reservation = scope.reserve(AttemptMemoryLedger.arrayBytes(graph.vertices().size() + 1L, 4))) {
            int[] starts = new int[graph.vertices().size() + 1];
            int edge = 0;
            for (int vertex = 0; vertex < graph.vertices().size(); vertex++) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                starts[vertex] = edge;
                while (edge < graph.arcs().size() && graph.arcs().get(edge).fromId() == vertex) edge++;
            }
            starts[graph.vertices().size()] = edge;
            reservation.adopt(starts);
            return starts;
        }
    }

    private Comparator<Pending> pendingOrder() {
        return (a, b) -> {
            budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
            int lower = Double.compare(a.lower, b.lower);
            if (lower != 0) return lower;
            int nativeId = Integer.compare(a.arc.tieId(), b.arc.tieId());
            if (nativeId != 0) return nativeId;
            return Long.compare(a.serial, b.serial);
        };
    }

    private Prefix retainPrefix(AttemptMemoryLedger.Owner scope, List<Prefix> reached,
            int cursor, int effectiveArc, int lastNumeric, int phase,
            Prefix parent, Arc via, double cost, double q, double c, double t, double k,
            int consumed) {
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 2);
        Prefix prefix;
        try (var reservation = scope.reserve(320)) {
            CanonicalKey key = new CanonicalKey(cursor, effectiveArc, lastNumeric, phase);
            prefix = new Prefix(key, parent, via, cost, q, c, t, k, consumed, serial++);
            reservation.adopt(prefix);
        }
        scope.reserve(32); // growing reached-list backing and reference allowance
        reached.add(prefix);
        return prefix;
    }

    private void enqueueOutgoing(AttemptMemoryLedger.Owner scope, PriorityQueue<Pending> queue,
            Prefix prefix, int[] edgeStarts, double intensityUpper) {
        int cursor = prefix.key.cursorId();
        for (int index = edgeStarts[cursor]; index < edgeStarts[cursor + 1]; index++) {
            budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
            Arc arc = graph.arcs().get(index);
            double qLower = OutwardCostBounds.scalarLossLower(arc.sourceSpanMeters(),
                graph.nativePitchMeters(), intensityUpper);
            boolean noNegativeSuffix = intensityUpper <= 1.0
                || graph.vertices().get(arc.toId()).terminal();
            double lower = OutwardCostBounds.completedPathLower(prefix.cost, qLower, noNegativeSuffix);
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
            try (var reservation = scope.reserve(192)) {
                Pending pending = new Pending(prefix, arc, qLower, lower,
                    noNegativeSuffix, serial++);
                pending.lease = reservation.adopt(pending);
                scope.reserve(64); // heap backing capacity remains after pop
                queue.add(pending);
            }
        }
    }

    private void verifyAdmitted(Arc arc, Admission admission, double intensityUpper) {
        if (!Double.isFinite(admission.q()) || !Double.isFinite(admission.c())
                || !Double.isFinite(admission.t()) || !Double.isFinite(admission.k())
                || admission.c() < 0 || admission.t() < 0 || admission.k() < 0)
            throw new InvalidCertificateException("admitted transition has nonfinite or negative CTK");
        switch (admission.provenance) {
            case MEASURED -> {
                if (admission.scalar == null || admission.scalar.status() != BilinearSegmentIntegral.Status.COMPLETE)
                    throw new InvalidCertificateException("measured Q lost its complete scalar receipt");
                double expected = arc.sourceSpanMeters() / graph.nativePitchMeters()
                    * (1.0 - admission.scalar.mean().orElseThrow());
                if (Double.doubleToLongBits(expected) != Double.doubleToLongBits(admission.q()))
                    throw new InvalidCertificateException("measured Q uses different source mass or pitch");
            }
            case ORDINARY_UNAVAILABLE -> {
                double expected = arc.sourceSpanMeters() / graph.nativePitchMeters();
                if (arc.optional() || Double.doubleToLongBits(expected) != Double.doubleToLongBits(admission.q()))
                    throw new InvalidCertificateException("ordinary unavailable loss is not source-bound");
            }
            case SYNTHETIC_CONTROL -> {
                if (!graph.syntheticControl())
                    throw new InvalidCertificateException("synthetic raw cost cannot enter a production graph");
            }
            case REFUSED -> throw new InvalidCertificateException("refusal cannot be admitted");
        }
        double qLower = OutwardCostBounds.scalarLossLower(arc.sourceSpanMeters(),
            graph.nativePitchMeters(), intensityUpper);
        if (admission.q() < qLower || intensityUpper <= 1.0 && admission.q() < 0)
            throw new InvalidCertificateException("measured Q contradicts retained native source bound");
    }

    private Prefix extend(AttemptMemoryLedger.Owner scope, List<Prefix> reached,
            Prefix prefix, Arc arc, Admission admission) {
        budget.charge(EmissionWorkBudget.Operation.TRANSITION, 1);
        double cost = prefix.cost + admission.q() + admission.c() + admission.t() + admission.k();
        if (!Double.isFinite(cost)) throw new InvalidCertificateException("nonfinite path objective");
        Vertex destination = graph.vertices().get(arc.toId());
        int consumed = Math.addExact(prefix.consumed, arc.consumedRows());
        return retainPrefix(scope, reached, destination.id(),
            arc.zeroPhysical() ? prefix.key.effectivePositiveArcId() : arc.id(),
            destination.numeric() ? destination.id() : prefix.key.lastNumericVertexId(),
            destination.phaseId(), prefix, arc, cost, prefix.q + admission.q(),
            prefix.c + admission.c(), prefix.t + admission.t(), prefix.k + admission.k(), consumed);
    }

    private int compareTerminal(Prefix first, Prefix second) {
        budget.charge(EmissionWorkBudget.Operation.TIE_COMPARISON, 1);
        int cost = Double.compare(first.cost, second.cost);
        if (cost != 0) return cost;
        int consumed = Integer.compare(first.consumed, second.consumed);
        if (consumed != 0) return consumed;
        return compareLex(first, second);
    }

    private int compareLex(Prefix first, Prefix second) {
        Prefix a = first, b = second;
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 2);
        long bytes = Math.addExact(AttemptMemoryLedger.arrayBytes(first.depth, 4),
            AttemptMemoryLedger.arrayBytes(second.depth, 4));
        try (var scratch = parentOwner.reserve(bytes)) {
            int[] left = new int[first.depth], right = new int[second.depth];
            int ai = left.length, bi = right.length;
            while (a != null) {
                budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
                left[--ai] = graph.vertices().get(a.key.cursorId()).tieId();
                a = a.parent;
            }
            while (b != null) {
                budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
                right[--bi] = graph.vertices().get(b.key.cursorId()).tieId();
                b = b.parent;
            }
            for (int i = 0; i < Math.min(left.length, right.length); i++) {
                budget.charge(EmissionWorkBudget.Operation.TIE_COMPARISON, 1);
                int difference = Integer.compare(left[i], right[i]);
                if (difference != 0) return difference;
            }
            return Integer.compare(left.length, right.length);
        }
    }

    private Result snapshotResult(Prefix best, int admitted, int refused,
            int unmeasured, double minLower, double intensityUpper,
            Map<TransitionKey, Admission> measured,
            PriorityQueue<Pending> queue) {
        int depth = best == null ? 0 : best.depth;
        long bytes = Math.addExact(512L, Math.multiplyExact(64L, depth));
        bytes = Math.addExact(bytes, Math.multiplyExact(256L, measured.size()));
        bytes = Math.addExact(bytes, Math.multiplyExact(192L, queue.size()));
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 3);
        try (var reservation = parentOwner.reserve(bytes)) {
            List<Integer> vertexIds = new ArrayList<>(depth);
            List<Integer> arcIds = new ArrayList<>(Math.max(0, depth - 1));
            List<MeasuredTransition> measuredRows = new ArrayList<>(measured.size());
            List<PendingProof> pendingRows = new ArrayList<>(queue.size());
            Prefix cursor = best;
            while (cursor != null) {
                budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
                vertexIds.add(cursor.key.cursorId());
                if (cursor.via != null) arcIds.add(cursor.via.id());
                cursor = cursor.parent;
            }
            java.util.Collections.reverse(vertexIds);
            java.util.Collections.reverse(arcIds);
            for (var entry : measured.entrySet()) {
                budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
                budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
                CanonicalKey source = entry.getKey().prefix();
                CanonicalKey copy = new CanonicalKey(source.cursorId(), source.effectivePositiveArcId(),
                    source.lastNumericVertexId(), source.phaseId());
                Admission admission = entry.getValue();
                measuredRows.add(new MeasuredTransition(copy, entry.getKey().arcId(),
                    admission.admitted(), admission.reason(), admission.q(), admission.c(),
                    admission.t(), admission.k(), admission.receipt()));
            }
            measuredRows.sort((a, b) -> {
                budget.charge(EmissionWorkBudget.Operation.TIE_COMPARISON, 1);
                int arc = Integer.compare(a.arcId(), b.arcId());
                return arc != 0 ? arc : compareKeys(a.prefix(), b.prefix());
            });
            for (Pending pending : queue) {
                budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
                budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
                CanonicalKey source = pending.prefix.key;
                CanonicalKey copy = new CanonicalKey(source.cursorId(), source.effectivePositiveArcId(),
                    source.lastNumericVertexId(), source.phaseId());
                boolean known = measured.containsKey(new TransitionKey(source, pending.arc.id()));
                pendingRows.add(new PendingProof(copy, pending.arc.id(), pending.prefix.cost,
                    pending.arc.sourceSpanMeters(), pending.scalarLower, pending.lower,
                    pending.laterIncrementsNonnegative, known, pending.prefix.serial));
            }
            pendingRows.sort((a, b) -> {
                budget.charge(EmissionWorkBudget.Operation.TIE_COMPARISON, 1);
                int bound = Double.compare(a.lowerBound(), b.lowerBound());
                if (bound != 0) return bound;
                int arc = Integer.compare(a.arcId(), b.arcId());
                return arc != 0 ? arc : Long.compare(a.prefixVersion(), b.prefixVersion());
            });
            Result result = new Result(best == null ? Status.NO_ROUTE : Status.CERTIFIED,
                List.copyOf(vertexIds), List.copyOf(arcIds), best == null ? Double.POSITIVE_INFINITY : best.cost,
                best == null ? 0 : best.q, best == null ? 0 : best.c, best == null ? 0 : best.t,
                best == null ? 0 : best.k, graph.arcs().size(), admitted, refused, unmeasured,
                minLower, best == null ? Double.POSITIVE_INFINITY : best.cost, intensityUpper,
                List.copyOf(measuredRows), List.copyOf(pendingRows));
            reservation.adopt(result);
            return result;
        }
    }

    private static int compareKeys(CanonicalKey a, CanonicalKey b) {
        int cursor = Integer.compare(a.cursorId(), b.cursorId());
        if (cursor != 0) return cursor;
        int incoming = Integer.compare(a.effectivePositiveArcId(), b.effectivePositiveArcId());
        if (incoming != 0) return incoming;
        int numeric = Integer.compare(a.lastNumericVertexId(), b.lastNumericVertexId());
        return numeric != 0 ? numeric : Integer.compare(a.phaseId(), b.phaseId());
    }
}
