package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EmissionMapping;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EmissionResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionPeak;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Exact, bounded opt-in Legacy row-emission solve. No old candidate path calls this service. */
public final class LegacyEmissionKernel {
    public record AnchorProposal(int originalRow, CrossSectionPeak peakOwner) {
        public AnchorProposal {
            if (originalRow < 0 || peakOwner == null) throw new IllegalArgumentException("Anchor owner is required");
        }
    }
    @FunctionalInterface public interface Smoother {
        List<Double> smooth(List<CrossSectionProfile> profiles, List<Double> offsets, List<Double> intensities);
    }
    public enum UnavailableReason {
        ORIGINAL_ROW_MAPPING, NATIVE_REFERENCE_ADAPTER, MOVABLE_BOUNDARY_ASSIGNMENT,
        NO_ADMITTED_PATH, NO_POSITIVE_PHYSICAL_LEG, STATE_FAMILY_LIMIT, NATIVE_PITCH
    }
    public static final class UnavailableException extends RuntimeException {
        private final UnavailableReason reason;
        public UnavailableException(UnavailableReason reason) {
            super("Legacy optional emission unavailable: " + reason);
            this.reason = reason;
        }
        public UnavailableReason reason() { return reason; }
    }

    private record PhysicalLeg(MetricPoint from, MetricPoint to,
            EmissionMapping.VertexOwner fromOwner, EmissionMapping.VertexOwner toOwner,
            Object nativeReference) {
        double length() { return from.distanceTo(to); }
    }
    private record ScalarMemory(int previousRow, double previousOffset, int lastRow,
            double lastOffset, double lastSlope, int offsetOriginId) { }
    private record Key(LegacyEmissionDomain.State last, ScalarMemory scalar,
            PhysicalLeg lastPositive, Object incomingCursor) { }
    private record ArcDraft(EmissionResult.ArcKind kind, MetricPoint from, MetricPoint to,
            double sourceSpan, double q, double c, double t, double k,
            Optional<Double> mean, Optional<NativeConnectorEvaluator.Receipt> receipt) { }
    private record PointDraft(EmissionMapping.VertexOwner owner, LegacyEmissionDomain.State state,
            MetricPoint position) { }
    private static final class Node {
        final Node previous;
        final Key key;
        final List<PointDraft> addedPoints;
        final List<ArcDraft> addedArcs;
        final double q, c, t, k;
        final double seedOffset;
        final int consumed;
        final int depth;
        Node(Node previous, Key key, List<PointDraft> addedPoints, List<ArcDraft> addedArcs,
                double q, double c, double t, double k, int consumed) {
            this.previous = previous; this.key = key;
            this.addedPoints = addedPoints; this.addedArcs = addedArcs;
            this.q = q; this.c = c; this.t = t; this.k = k;
            this.seedOffset = previous == null ? key.scalar().previousOffset()
                : previous.seedOffset;
            this.consumed = consumed; this.depth = previous == null ? 1 : previous.depth + 1;
        }
        double total() { return q + c + t + k; }
    }

    /** Runs every original seed under one retained work budget and memory owner. */
    public EmissionResult solve(SampledEmissionInput input, List<CrossSectionProfile> suppressed,
            List<Double> seeds, List<AnchorProposal> proposals, Smoother smoother,
            CancellationProbe cancellation) {
        Objects.requireNonNull(input); Objects.requireNonNull(suppressed); Objects.requireNonNull(seeds);
        Objects.requireNonNull(proposals); Objects.requireNonNull(smoother); Objects.requireNonNull(cancellation);
        input.requireOpenAndUnchanged();
        var binding = input.legacyBinding().orElseThrow(() ->
            new IllegalArgumentException("Original Legacy binding is required"));
        if (binding.observations().size() < 2) throw new IllegalArgumentException("Legacy requires at least two original rows");
        if (binding.protectedOccurrences().stream().anyMatch(event -> !event.fixed()))
            throw new UnavailableException(UnavailableReason.MOVABLE_BOUNDARY_ASSIGNMENT);
        double pitch = input.context().nativePitchMeters().orElseThrow(() ->
            new UnavailableException(UnavailableReason.NATIVE_PITCH));
        var budget = input.workBudget();
        var domain = new LegacyEmissionDomain(binding, suppressed, seeds, budget);
        var owner = input.openExecutionOwner("legacy-emission-kernel");
        List<NativeConnectorEvaluator> evaluators = new ArrayList<>();
        try {
            Node best = null;
            Inventory bestInventory = null;
            NativeConnectorEvaluator bestEvaluator = null;
            for (var seed : domain.seeds()) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                cancellation.checkpoint();
                var inventory = inventory(domain, seed, proposals, budget, owner);
                var evaluator = evaluator(input, domain, inventory);
                evaluators.add(evaluator);
                Node candidate = solveBase(input, domain, seed, inventory, evaluator, owner, cancellation, pitch);
                if (candidate != null && better(candidate, best, budget, owner)) {
                    best = candidate; bestInventory = inventory; bestEvaluator = evaluator;
                }
            }
            if (best == null) throw new UnavailableException(UnavailableReason.NO_ADMITTED_PATH);
            if (best.key.lastPositive() == null)
                throw new UnavailableException(UnavailableReason.NO_POSITIVE_PHYSICAL_LEG);

            Node firstStage = best;
            var path = flatten(firstStage, budget);
            // Anchors are computed on the unchanged original N profiles. A proposal is eligible only
            // when its first-stage emitted state owns the exact proposed native peak object.
            var eligible = new ArrayList<AnchorProposal>();
            for (var proposal : proposals) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                for (var point : path.points()) {
                    if (point.state() != null && point.state().originalRow() == proposal.originalRow()
                            && point.state().peakOwner() == proposal.peakOwner()) {
                        eligible.add(proposal);
                        break;
                    }
                }
            }
            var intervalCosts = new ArrayList<EmissionResult.Costs>();
            for (int index = 0; index + 1 < eligible.size(); index++) {
                cancellation.checkpoint();
                var left = eligible.get(index); var right = eligible.get(index + 1);
                var chain = chain(best, budget);
                Node prefix = chain.stream().filter(node -> node.key.last().originalRow() == left.originalRow())
                    .findFirst().orElseThrow(() -> new IllegalStateException("Eligible anchor was not emitted"));
                var rightState = chain.stream().filter(node -> node.key.last().originalRow() == right.originalRow())
                    .map(node -> node.key.last()).findFirst().orElseThrow();
                if (rightState.peakOwner() != right.peakOwner())
                    throw new IllegalStateException("Anchored endpoint lost its exact native peak");
                Node interval = solveInterval(input, domain, prefix, rightState, left, right,
                    bestInventory, bestEvaluator, owner, cancellation, pitch);
                if (interval == null) throw new UnavailableException(UnavailableReason.NO_ADMITTED_PATH);
                intervalCosts.add(new EmissionResult.Costs(interval.q - prefix.q,
                    interval.c - prefix.c, interval.t - prefix.t, interval.k - prefix.k));
                boolean suffix = false;
                for (Node node : chain) {
                    int row = node.key.last().originalRow();
                    if (row == right.originalRow()) { suffix = true; continue; }
                    if (suffix) {
                        interval = advance(input, domain, interval, node.key.last(), bestEvaluator, owner, pitch);
                        if (interval == null)
                            throw new UnavailableException(UnavailableReason.NO_ADMITTED_PATH);
                    }
                }
                best = interval;
            }
            return result(input, domain, flatten(best, budget), firstStage, best, intervalCosts,
                smoother, owner, evaluators, budget, cancellation);
        } catch (RuntimeException failure) {
            for (var evaluator : evaluators) evaluator.close();
            owner.close();
            throw failure;
        }
    }

    private record Inventory(List<List<LegacyEmissionDomain.State>> byRow,
            Map<Integer, LegacyEmissionDomain.State> intervalTargets,
            List<NativeConnectorEvaluator.RowState> evaluatorStates) { }

    private Inventory inventory(LegacyEmissionDomain domain, LegacyEmissionDomain.Seed seed,
            List<AnchorProposal> proposals,
            EmissionWorkBudget budget, AttemptMemoryLedger.Owner owner) {
        var byRow = new ArrayList<List<LegacyEmissionDomain.State>>(domain.rowCount());
        var all = new ArrayList<NativeConnectorEvaluator.RowState>();
        var origins = new ArrayList<LegacyEmissionDomain.State>();
        var targets = new HashMap<Integer, LegacyEmissionDomain.State>();
        for (int pair = 0; pair + 1 < proposals.size(); pair++) {
            var left = proposals.get(pair); var right = proposals.get(pair + 1);
            LegacyEmissionDomain.State a = exactAnchorState(domain, left);
            LegacyEmissionDomain.State b = exactAnchorState(domain, right);
            for (int row = left.originalRow() + 1; row < right.originalRow(); row++) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                if (domain.unsupported(row)) {
                    var target = domain.intervalTarget(row, left.originalRow(), right.originalRow(), a, b, budget);
                    if (targets.put(row, target) != null)
                        throw new IllegalArgumentException("Overlapping Legacy anchor interval targets");
                }
            }
        }
        for (int row = 0; row < domain.rowCount(); row++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var states = new ArrayList<LegacyEmissionDomain.State>();
            if (domain.unsupported(row)) {
                // The seed and every earlier native origin are candidate family members. DP only
                // expands the carry matching the actual incoming origin; no fallback center is used.
                states.add(domain.carry(row, seed.id(), seed.offsetRasterPixels(), budget));
                for (var origin : origins) {
                    budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                    states.add(domain.carry(row, origin.offsetOriginId(), origin.offsetRasterPixels(), budget));
                }
            } else {
                states.addAll(domain.observedAt(row));
                origins.addAll(states);
            }
            if (states.size() > 96)
                throw new UnavailableException(UnavailableReason.STATE_FAMILY_LIMIT);
            var target = targets.get(row);
            if (target != null) all.add(target.evaluatorState());
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION, states.size() + 1L);
            owner.reserve(Math.addExact(64, Math.multiplyExact(128L, states.size())));
            byRow.add(List.copyOf(states));
            for (var state : states) all.add(state.evaluatorState());
        }
        return new Inventory(List.copyOf(byRow), Map.copyOf(targets), List.copyOf(all));
    }

    private static LegacyEmissionDomain.State exactAnchorState(LegacyEmissionDomain domain,
            AnchorProposal proposal) {
        return domain.observedAt(proposal.originalRow()).stream()
            .filter(state -> state.peakOwner() == proposal.peakOwner()).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Anchor proposal has no exact native state"));
    }

    private static List<Node> chain(Node terminal, EmissionWorkBudget budget) {
        var reverse = new ArrayList<Node>();
        for (Node node = terminal; node != null; node = node.previous) {
            budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
            reverse.add(node);
        }
        java.util.Collections.reverse(reverse);
        return List.copyOf(reverse);
    }

    private Node solveInterval(SampledEmissionInput input, LegacyEmissionDomain domain,
            Node prefix, LegacyEmissionDomain.State rightState, AnchorProposal left,
            AnchorProposal right, Inventory inventory, NativeConnectorEvaluator evaluator,
            AttemptMemoryLedger.Owner owner, CancellationProbe cancellation, double pitch) {
        var budget = input.workBudget();
        var layers = new HashMap<Integer, Map<Key, Node>>();
        layers.put(left.originalRow(), new HashMap<>(Map.of(prefix.key, prefix)));
        for (int row = left.originalRow(); row < right.originalRow(); row++) {
            var current = layers.get(row);
            if (current == null) continue;
            for (Node from : List.copyOf(current.values())) {
                budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
                cancellation.checkpoint();
                for (int toRow = row + 1; toRow <= right.originalRow() && toRow <= row + 17; toRow++) {
                    budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                    if (toRow > row + 1 && (chainage(domain, toRow) - chainage(domain, row) > 20
                            || crossesMandatory(domain, row, toRow))) continue;
                    List<LegacyEmissionDomain.State> choices;
                    if (toRow == right.originalRow()) choices = List.of(rightState);
                    else if (domain.unsupported(toRow)) {
                        var target = inventory.intervalTargets().get(toRow);
                        if (target == null) throw new IllegalStateException("Anchored interval target absent");
                        choices = List.of(target);
                    } else choices = domain.observedAt(toRow);
                    for (var to : choices) {
                        budget.charge(EmissionWorkBudget.Operation.TRANSITION, 1);
                        Node next = advance(input, domain, from, to, evaluator, owner, pitch);
                        if (next != null) insert(layers.computeIfAbsent(toRow, ignored -> new HashMap<>()), next,
                            budget, owner);
                    }
                }
            }
        }
        Node best = null;
        for (var candidate : layers.getOrDefault(right.originalRow(), Map.of()).values())
            if (better(candidate, best, budget, owner)) best = candidate;
        return best;
    }

    private NativeConnectorEvaluator evaluator(SampledEmissionInput input, LegacyEmissionDomain domain,
            Inventory inventory) {
        var binding = domain.binding();
        // This source API currently has only retained row indexes. Exact ordinal mapping is a
        // connector-owner follow-on; refuse truthful nonconsecutive source rows until it lands.
        var mapping = binding.originalRowToRetainedObservation();
        for (int row = 1; row < mapping.size(); row++)
            if (mapping.get(row) != mapping.get(0) + row)
                throw new UnavailableException(UnavailableReason.ORIGINAL_ROW_MAPPING);
        var mandatory = new ArrayList<Integer>();
        mandatory.add(mapping.get(0)); mandatory.add(mapping.get(mapping.size() - 1));
        var barriers = new ArrayList<NativeConnectorEvaluator.Barrier>();
        for (var event : binding.protectedOccurrences()) {
            mandatory.add(mapping.get(event.leftOriginalRow()));
            mandatory.add(mapping.get(event.rightOriginalRow()));
            barriers.add(new NativeConnectorEvaluator.Barrier(event.occurrenceId(),
                mapping.get(event.leftOriginalRow()), mapping.get(event.rightOriginalRow()),
                event.sourceChainageMeters(), event.exactPosition()));
        }
        return new NativeConnectorEvaluator(input,
            new NativeConnectorEvaluator.Domain(inventory.evaluatorStates(), mandatory, barriers));
    }

    private Node solveBase(SampledEmissionInput input, LegacyEmissionDomain domain,
            LegacyEmissionDomain.Seed seed, Inventory inventory, NativeConnectorEvaluator evaluator,
            AttemptMemoryLedger.Owner owner, CancellationProbe cancellation, double pitch) {
        var budget = input.workBudget();
        int n = domain.rowCount();
        var layers = new ArrayList<Map<Key, Node>>(n);
        for (int row = 0; row < n; row++) {
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
            owner.reserve(128);
            layers.add(new HashMap<>());
        }
        for (var first : inventory.byRow().get(0)) {
            budget.charge(EmissionWorkBudget.Operation.PAIR_INITIALIZATION, 1);
            if (first.kind() == LegacyEmissionDomain.Kind.BASE_CARRY && first.offsetOriginId() != seed.id())
                continue;
            double departure = Math.abs(first.offsetRasterPixels() - seed.offsetRasterPixels());
            var key = new Key(first, new ScalarMemory(-1, seed.offsetRasterPixels(), 0,
                first.offsetRasterPixels(), first.offsetRasterPixels() - seed.offsetRasterPixels(),
                first.offsetOriginId()), null, null);
            var point = new PointDraft(new EmissionMapping.ObservationVertex(0), first,
                domain.metricPoint(first, input.context()));
            var node = allocateNode(owner, budget, null, key, List.of(point), List.of(),
                0, .16 * departure, .12 * departure, 0, 0);
            insert(layers.get(0), node, budget, owner);
        }
        for (int row = 0; row < n - 1; row++) {
            for (Node from : List.copyOf(layers.get(row).values())) {
                budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
                cancellation.checkpoint();
                for (int toRow = row + 1; toRow < n && toRow <= row + 17; toRow++) {
                    budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                    double span = chainage(domain, toRow) - chainage(domain, row);
                    if (toRow > row + 1 && span > 20) break;
                    if (toRow > row + 1 && crossesMandatory(domain, row, toRow)) continue;
                    for (var to : inventory.byRow().get(toRow)) {
                        budget.charge(EmissionWorkBudget.Operation.TRANSITION, 1);
                        if (to.kind() == LegacyEmissionDomain.Kind.BASE_CARRY
                                && (to.offsetOriginId() != from.key.last().offsetOriginId()
                                    || Double.doubleToLongBits(to.offsetRasterPixels())
                                        != Double.doubleToLongBits(from.key.last().offsetRasterPixels())))
                            continue;
                        Node candidate = advance(input, domain, from, to, evaluator, owner, pitch);
                        if (candidate != null) insert(layers.get(toRow), candidate, budget, owner);
                    }
                }
            }
        }
        Node best = null;
        for (Node candidate : layers.get(n - 1).values())
            if (better(candidate, best, budget, owner)) best = candidate;
        return best;
    }

    private Node advance(SampledEmissionInput input, LegacyEmissionDomain domain, Node from,
            LegacyEmissionDomain.State to, NativeConnectorEvaluator evaluator,
            AttemptMemoryLedger.Owner owner, double pitch) {
        var budget = input.workBudget();
        var a = from.key.last(); int rowA = a.originalRow(), rowB = to.originalRow();
        boolean optional = rowB > rowA + 1;
        MetricPoint pa = domain.metricPoint(a, input.context()), pb = domain.metricPoint(to, input.context());
        double sourceSpan = chainage(domain, rowB) - chainage(domain, rowA);
        List<PointDraft> points = new ArrayList<>();
        List<ArcDraft> arcs = new ArrayList<>();
        var events = eventsBetween(domain.binding(), rowA, rowB);
        if (optional && !events.isEmpty()) return null;
        MetricPoint cursor = pa;
        double cursorSource = chainage(domain, rowA);
        PhysicalLeg physical = from.key.lastPositive();
        Object incoming = from.key.incomingCursor();
        for (var event : events) {
            double eventLocalChainage = event.sourceChainageMeters()
                - input.context().profileChainage().sourceOriginGroundMeters();
            var arc = ordinary(input, cursor, event.exactPosition(), cursorSource,
                eventLocalChainage, physical, budget, owner);
            arcs.add(arc); physical = nextPhysical(physical, arc,
                points.isEmpty() ? new EmissionMapping.ObservationVertex(rowA) : points.get(points.size() - 1).owner(),
                new EmissionMapping.BoundaryVertex(event.occurrenceId()), null);
            incoming = null; // Typed boundary reference adapter is supplied by evaluator owner.
            points.add(new PointDraft(new EmissionMapping.BoundaryVertex(event.occurrenceId()), null,
                event.exactPosition()));
            cursor = event.exactPosition(); cursorSource = eventLocalChainage;
        }
        ArcDraft finalArc;
        if (optional) {
            NativeConnectorEvaluator.Receipt receipt;
            if (cursor.distanceTo(pb) == 0 && incoming == null)
                throw new UnavailableException(UnavailableReason.NATIVE_REFERENCE_ADAPTER);
            receipt = evaluator.evaluate(a.evaluatorState(), to.evaluatorState(),
                incoming instanceof NativeConnectorEvaluator.Receipt r ? r : null);
            if (receipt.status() != NativeConnectorEvaluator.Status.ADMITTED) return null;
            double q = receipt.sourceWeightedLoss();
            finalArc = new ArcDraft(EmissionResult.ArcKind.OPTIONAL_ADMITTED, cursor, pb,
                sourceSpan, q, 0, 0, turn(physical, cursor, pb, pitch),
                Optional.of(receipt.scalar().orElseThrow().mean().orElseThrow()), Optional.of(receipt));
            incoming = receipt;
        } else {
            finalArc = ordinary(input, cursor, pb, cursorSource, chainage(domain, rowB),
                physical, budget, owner);
            incoming = null;
        }
        double scalarSlope = (to.offsetRasterPixels() - a.offsetRasterPixels()) / (rowB - rowA);
        double c = .16 * Math.abs(to.offsetRasterPixels() - a.offsetRasterPixels());
        double t = .12 * Math.abs(scalarSlope - from.key.scalar().lastSlope());
        finalArc = new ArcDraft(finalArc.kind(), finalArc.from(), finalArc.to(),
            finalArc.sourceSpan(), finalArc.q(), c, t, finalArc.k(), finalArc.mean(), finalArc.receipt());
        arcs.add(finalArc);
        physical = nextPhysical(physical, finalArc,
            points.isEmpty() ? new EmissionMapping.ObservationVertex(rowA) : points.get(points.size() - 1).owner(),
            new EmissionMapping.ObservationVertex(rowB), incoming);
        points.add(new PointDraft(new EmissionMapping.ObservationVertex(rowB), to, pb));
        double qSum = 0, kSum = 0;
        for (var arc : arcs) { qSum += arc.q(); kSum += arc.k(); }
        var key = new Key(to, new ScalarMemory(rowA, a.offsetRasterPixels(), rowB,
            to.offsetRasterPixels(), scalarSlope, to.offsetOriginId()), physical, incoming);
        return allocateNode(owner, budget, from, key, List.copyOf(points), List.copyOf(arcs),
            from.q + qSum, from.c + c, from.t + t, from.k + kSum,
            from.consumed + rowB - rowA - 1);
    }

    private ArcDraft ordinary(SampledEmissionInput input, MetricPoint from, MetricPoint to,
            double sourceFrom, double sourceTo, PhysicalLeg previous, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner) {
        double sourceSpan = sourceTo - sourceFrom;
        var transform = input.context().rasterTransform();
        RasterPoint ra = transform.metricToPixelCenter(from), rb = transform.metricToPixelCenter(to);
        var grid = input.context().nativeGrid();
        var ga = grid.toGrid(ra); var gb = grid.toGrid(rb);
        long cells = (long) Math.ceil(Math.abs(gb.x() - ga.x()) + Math.abs(gb.y() - ga.y())) + 16;
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        BilinearSegmentIntegral.Result integral;
        try (var scratch = owner.reserve(Math.addExact(4096, Math.multiplyExact(cells, 2048)))) {
            integral = BilinearSegmentIntegral.evaluate(grid, ga, gb, CancellationProbe.NONE,
                () -> budget.charge(EmissionWorkBudget.Operation.CELL_QUERY, 1));
        }
        double pitch = input.context().nativePitchMeters().orElseThrow();
        boolean measured = integral.status() == BilinearSegmentIntegral.Status.COMPLETE;
        double mean = measured ? integral.mean().orElseThrow() : 0;
        double q = sourceSpan / pitch * (1 - mean);
        return new ArcDraft(measured ? EmissionResult.ArcKind.ORDINARY_MEASURED
                : EmissionResult.ArcKind.ORDINARY_UNAVAILABLE,
            from, to, sourceSpan, q, 0, 0, turn(previous, from, to, pitch),
            measured ? Optional.of(mean) : Optional.empty(), Optional.empty());
    }

    private static PhysicalLeg nextPhysical(PhysicalLeg previous, ArcDraft arc,
            EmissionMapping.VertexOwner fromOwner, EmissionMapping.VertexOwner toOwner,
            Object nativeReference) {
        return arc.from().distanceTo(arc.to()) > 0
            ? new PhysicalLeg(arc.from(), arc.to(), fromOwner, toOwner, nativeReference) : previous;
    }

    static double turn(PhysicalLeg previous, MetricPoint from, MetricPoint to, double pitch) {
        double nextLength = from.distanceTo(to);
        if (previous == null || nextLength == 0) return 0;
        double ax = previous.to().xMeters() - previous.from().xMeters();
        double ay = previous.to().yMeters() - previous.from().yMeters();
        double bx = to.xMeters() - from.xMeters(), by = to.yMeters() - from.yMeters();
        double angle = Math.atan2(ax * by - ay * bx, ax * bx + ay * by);
        return angle * angle / Math.max(1, (previous.length() + nextLength) / (2 * pitch));
    }

    private static boolean crossesMandatory(LegacyEmissionDomain domain, int from, int to) {
        for (var event : domain.binding().protectedOccurrences())
            if (from <= event.leftOriginalRow() && to >= event.rightOriginalRow()) return true;
        for (var event : domain.binding().protectedOccurrences())
            if (event.leftOriginalRow() > from && event.leftOriginalRow() < to
                    || event.rightOriginalRow() > from && event.rightOriginalRow() < to) return true;
        return false;
    }

    private static List<LegacyEmissionBinding.ProtectedOccurrence> eventsBetween(
            LegacyEmissionBinding binding, int from, int to) {
        return binding.protectedOccurrences().stream()
            .filter(event -> event.leftOriginalRow() == from && event.rightOriginalRow() == to)
            .toList();
    }

    private static double chainage(LegacyEmissionDomain domain, int row) {
        return domain.binding().observations().get(row).profile().cumulativeGroundDistanceMeters();
    }

    private static Node allocateNode(AttemptMemoryLedger.Owner owner, EmissionWorkBudget budget,
            Node previous, Key key, List<PointDraft> points, List<ArcDraft> arcs,
            double q, double c, double t, double k, int consumed) {
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        owner.reserve(Math.addExact(256, Math.addExact(128L * points.size(), 192L * arcs.size())));
        return new Node(previous, key, points, arcs, q, c, t, k, consumed);
    }

    private static void insert(Map<Key, Node> layer, Node candidate, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner) {
        budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
        Node old = layer.get(candidate.key);
        if (better(candidate, old, budget, owner)) layer.put(candidate.key, candidate);
    }

    private static boolean better(Node left, Node right, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner) {
        if (left == null) return false;
        if (right == null) return true;
        budget.charge(EmissionWorkBudget.Operation.TIE_COMPARISON, 1);
        int objective = Double.compare(left.total(), right.total());
        if (objective != 0) return objective < 0;
        int consumed = Integer.compare(left.consumed, right.consumed);
        return consumed != 0 ? consumed < 0 : compareTie(left, right, budget, owner) < 0;
    }

    private static int compareTie(Node left, Node right, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner) {
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 2);
        try (var scratch = owner.reserve(AttemptMemoryLedger.referenceArrayBytes(left.depth)
                + AttemptMemoryLedger.referenceArrayBytes(right.depth))) {
            var a = new Node[left.depth]; var b = new Node[right.depth];
            for (Node node = left; node != null; node = node.previous) {
                budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
                a[node.depth - 1] = node;
            }
            for (Node node = right; node != null; node = node.previous) {
                budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
                b[node.depth - 1] = node;
            }
            for (int i = 0; i < Math.min(a.length, b.length); i++) {
                budget.charge(EmissionWorkBudget.Operation.TIE_COMPARISON, 1);
                var x = a[i].key.last(); var y = b[i].key.last();
                int row = Integer.compare(x.originalRow(), y.originalRow());
                if (row != 0) return row;
                int state = Integer.compare(x.nativeStateId(), y.nativeStateId());
                if (state != 0) return state;
                int origin = Integer.compare(x.offsetOriginId(), y.offsetOriginId());
                if (origin != 0) return origin;
            }
            return Integer.compare(a.length, b.length);
        }
    }

    private record Flattened(List<PointDraft> points, List<ArcDraft> arcs) { }
    private static Flattened flatten(Node terminal, EmissionWorkBudget budget) {
        var nodes = new ArrayList<Node>();
        for (Node node = terminal; node != null; node = node.previous) {
            budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
            nodes.add(node);
        }
        var points = new ArrayList<PointDraft>(); var arcs = new ArrayList<ArcDraft>();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            points.addAll(nodes.get(i).addedPoints); arcs.addAll(nodes.get(i).addedArcs);
        }
        return new Flattened(List.copyOf(points), List.copyOf(arcs));
    }

    private EmissionResult result(SampledEmissionInput input, LegacyEmissionDomain domain,
            Flattened path, Node firstNode, Node best, List<EmissionResult.Costs> intervals,
            Smoother smoother, AttemptMemoryLedger.Owner owner,
            List<NativeConnectorEvaluator> evaluators, EmissionWorkBudget budget,
            CancellationProbe cancellation) {
        int n = domain.rowCount();
        var vertexOwners = new ArrayList<EmissionMapping.VertexOwner>();
        var observationOwners = new ArrayList<EmissionMapping.ObservationOwner>();
        for (int row = 0; row < n; row++) observationOwners.add(null);
        var vertices = new ArrayList<EmissionResult.Vertex>();
        for (int index = 0; index < path.points().size(); index++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var point = path.points().get(index);
            vertexOwners.add(point.owner());
            if (point.owner() instanceof EmissionMapping.ObservationVertex observed) {
                observationOwners.set(observed.observationIndex(), new EmissionMapping.Emitted(index));
                vertices.add(vertex(point));
            } else vertices.add(vertex(point));
        }
        for (int arc = 0; arc < path.arcs().size(); arc++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var left = vertexOwners.get(arc); var right = vertexOwners.get(arc + 1);
            if (left instanceof EmissionMapping.ObservationVertex a
                    && right instanceof EmissionMapping.ObservationVertex b)
                for (int row = a.observationIndex() + 1; row < b.observationIndex(); row++)
                    observationOwners.set(row, new EmissionMapping.Consumed(arc));
        }
        Set<Integer> mandatory = new HashSet<>(Set.of(0, n - 1));
        for (var event : domain.binding().protectedOccurrences()) {
            mandatory.add(event.leftOriginalRow()); mandatory.add(event.rightOriginalRow());
        }
        var mapping = EmissionMapping.create(n, vertexOwners, observationOwners, mandatory);
        var arcs = arcResults(path.arcs());
        var smoothed = smooth(path, smoother, domain, input.context(), budget);
        var smoothedArcs = arcResults(remeasure(input, path, smoothed, best.seedOffset, owner, budget));
        var first = new EmissionResult.Costs(firstNode.q, firstNode.c, firstNode.t, firstNode.k);
        var stitched = new EmissionResult.Costs(best.q, best.c, best.t, best.k);
        var post = totals(smoothedArcs, best.seedOffset,
            path.points().get(0).state().offsetRasterPixels());
        double source = 0, measured = 0, length = 0;
        for (var arc : arcs) {
            source += arc.sourceSpanMeters(); length += arc.lengthMeters();
            if (arc.meanNativeIntensity().isPresent()) measured += arc.lengthMeters();
        }
        owner.reserve(Math.addExact(512, Math.addExact(160L * vertices.size(), 192L * arcs.size())));
        return new EmissionResult(domain.binding().observations(), mapping, vertices, smoothed,
            arcs, smoothedArcs, first, intervals, stitched, post, source, measured, length,
            budget.snapshot(), owner.peakBytes(), () -> {
                for (var evaluator : evaluators) evaluator.close();
                owner.close();
            });
    }

    private static EmissionResult.Vertex vertex(PointDraft point) {
        if (point.state() == null)
            return new EmissionResult.Vertex(point.position(), point.owner(),
                EmissionResult.StateKind.BOUNDARY_EVENT, Optional.empty(), Optional.empty());
        var state = point.state();
        var kind = switch (state.kind()) {
            case OBSERVED_PEAK -> EmissionResult.StateKind.OBSERVED_PEAK;
            case BASE_CARRY -> EmissionResult.StateKind.BASE_CARRY;
            case INTERVAL_TARGET -> EmissionResult.StateKind.INTERVAL_TARGET;
        };
        return new EmissionResult.Vertex(point.position(), point.owner(), kind,
            Optional.of(state.nativeStateId()), Optional.of(state.offsetRasterPixels()));
    }

    private static List<EmissionResult.Arc> arcResults(List<ArcDraft> drafts) {
        var result = new ArrayList<EmissionResult.Arc>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            var arc = drafts.get(i);
            result.add(new EmissionResult.Arc(i, arc.kind(), arc.sourceSpan(),
                arc.from().distanceTo(arc.to()), arc.q(), arc.c(), arc.t(), arc.k(), arc.mean(), arc.receipt()));
        }
        return List.copyOf(result);
    }

    private static List<EmissionResult.Vertex> smooth(Flattened path, Smoother smoother,
            LegacyEmissionDomain domain, OptionalEmissionContext context, EmissionWorkBudget budget) {
        var vertices = new ArrayList<EmissionResult.Vertex>();
        for (var point : path.points()) vertices.add(vertex(point));
        int start = 0;
        while (start < path.points().size()) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            if (path.points().get(start).state() == null) { start++; continue; }
            int end = start;
            while (end + 1 < path.points().size()
                    && path.points().get(end + 1).state() != null
                    && path.points().get(end + 1).state().originalRow()
                        == path.points().get(end).state().originalRow() + 1) end++;
            if (end - start >= 2) {
                var profiles = new ArrayList<CrossSectionProfile>();
                var offsets = new ArrayList<Double>(); var intensities = new ArrayList<Double>();
                for (int i = start; i <= end; i++) {
                    var state = path.points().get(i).state();
                    profiles.add(domain.binding().observations().get(state.originalRow()).profile());
                    offsets.add(state.offsetRasterPixels());
                    intensities.add(state.peakOwner() == null ? 0.0 : state.peakOwner().intensity());
                }
                var result = smoother.smooth(List.copyOf(profiles), List.copyOf(offsets), List.copyOf(intensities));
                if (result.size() != offsets.size())
                    throw new IllegalArgumentException("Legacy smoother changed run length");
                for (int i = start + 1; i < end; i++) {
                    var state = path.points().get(i).state();
                    var profile = domain.binding().observations().get(state.originalRow()).profile();
                    double offset = result.get(i - start);
                    if (!Double.isFinite(offset)) throw new IllegalArgumentException("Legacy smoother produced nonfinite offset");
                    var rp = new RasterPoint(profile.anchorScreen().x + profile.normalScreen().x * offset,
                        profile.anchorScreen().y + profile.normalScreen().y * offset);
                    var mp = context.rasterTransform().pixelCenterToMetric(rp.x(), rp.y());
                    vertices.set(i, new EmissionResult.Vertex(mp,
                        path.points().get(i).owner(), vertices.get(i).kind(),
                        vertices.get(i).nativeStateId(), Optional.of(offset)));
                }
            }
            start = end + 1;
        }
        return List.copyOf(vertices);
    }

    private List<ArcDraft> remeasure(SampledEmissionInput input, Flattened path,
            List<EmissionResult.Vertex> smoothed, double seedOffset, AttemptMemoryLedger.Owner owner,
            EmissionWorkBudget budget) {
        var result = new ArrayList<ArcDraft>();
        PhysicalLeg physical = null;
        int numericRow = path.points().get(0).state().originalRow();
        double numericOffset = smoothed.get(0).offsetRasterPixels().orElseThrow();
        double scalarSlope = numericOffset - seedOffset;
        double pitch = input.context().nativePitchMeters().orElseThrow();
        for (int i = 0; i < path.arcs().size(); i++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var original = path.arcs().get(i);
            MetricPoint a = smoothed.get(i).position(), b = smoothed.get(i + 1).position();
            ArcDraft measured;
            if (original.kind() == EmissionResult.ArcKind.OPTIONAL_ADMITTED) {
                if (!a.equals(original.from()) || !b.equals(original.to()))
                    throw new IllegalStateException("Smoother changed a consumed-arc endpoint");
                measured = original;
            } else {
                double sourceFrom = 0;
                measured = ordinary(input, a, b, sourceFrom,
                    sourceFrom + original.sourceSpan(), physical, budget, owner);
            }
            double c = 0, t = 0;
            var nextPoint = path.points().get(i + 1);
            if (nextPoint.state() != null) {
                int row = nextPoint.state().originalRow();
                double offset = smoothed.get(i + 1).offsetRasterPixels().orElseThrow();
                double nextSlope = (offset - numericOffset) / (row - numericRow);
                c = .16 * Math.abs(offset - numericOffset);
                t = .12 * Math.abs(nextSlope - scalarSlope);
                numericRow = row; numericOffset = offset; scalarSlope = nextSlope;
            }
            double k = turn(physical, a, b, pitch);
            var arc = new ArcDraft(measured.kind(), a, b, original.sourceSpan(), measured.q(),
                c, t, k, measured.mean(), measured.receipt());
            result.add(arc);
            physical = nextPhysical(physical, arc, smoothed.get(i).owner(),
                smoothed.get(i + 1).owner(), arc.receipt().orElse(null));
        }
        return List.copyOf(result);
    }

    private static EmissionResult.Costs totals(List<EmissionResult.Arc> arcs,
            double seedOffset, double firstOffset) {
        double q = 0, c = .16 * Math.abs(firstOffset - seedOffset),
            t = .12 * Math.abs(firstOffset - seedOffset), k = 0;
        for (var arc : arcs) { q += arc.q(); c += arc.c(); t += arc.t(); k += arc.k(); }
        return new EmissionResult.Costs(q, c, t, k);
    }

}
