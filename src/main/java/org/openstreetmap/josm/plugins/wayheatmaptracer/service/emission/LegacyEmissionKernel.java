package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

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
        NO_ADMITTED_PATH, NO_POSITIVE_PHYSICAL_LEG, STATE_FAMILY_LIMIT, NATIVE_PITCH, FINAL_AFFINITY_CONFLICT
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
            Object fromNativeIdentity, Object toNativeIdentity,
            ReferenceIdentity nativeReference) {
        double length() { return from.distanceTo(to); }
    }
    private static final class ReferenceIdentity {
        final Object value;
        ReferenceIdentity(Object value) { this.value = Objects.requireNonNull(value); }
        @Override public boolean equals(Object other) {
            return other instanceof ReferenceIdentity reference && reference.value == value;
        }
        @Override public int hashCode() { return System.identityHashCode(value); }
    }
    private record ScalarMemory(int previousRow, double previousOffset, int lastRow,
            double lastOffset, double lastSlope, int offsetOriginId) { }
    private record PhaseContext(int stageOrdinal, String lastBoundaryOccurrenceId) { }
    private record Key(LegacyEmissionDomain.State last, ScalarMemory scalar,
            PhysicalLeg lastPositive, ReferenceIdentity incomingCursor,
            PhaseContext phase) { }
    private record ArcDraft(EmissionResult.ArcKind kind, MetricPoint from, MetricPoint to,
            double sourceSpan, double q, double c, double t, double k,
            Optional<Double> mean, Optional<NativeConnectorEvaluator.Receipt> receipt,
            Object ordinaryCursor) {
        ArcDraft withCursor(Object cursor) {
            return new ArcDraft(kind, from, to, sourceSpan, q, c, t, k, mean, receipt, cursor);
        }
    }
    private record PointDraft(EmissionMapping.VertexOwner owner, LegacyEmissionDomain.State state,
            MetricPoint position) { }
    private static final class Node {
        final Node previous;
        final Key key;
        final List<PointDraft> addedPoints;
        final List<ArcDraft> addedArcs;
        final double q, c, t, k;
        final double seedOffset;
        final int seedId;
        final int consumed;
        final int depth;
        final AttemptMemoryLedger.Reservation memory;
        int retainedChildren;
        boolean inLayer;
        int pinCount;
        boolean released;
        Node(Node previous, Key key, List<PointDraft> addedPoints, List<ArcDraft> addedArcs,
                double q, double c, double t, double k, int consumed,
                AttemptMemoryLedger.Reservation memory, int initialSeedId) {
            this.previous = previous; this.key = key;
            this.addedPoints = addedPoints; this.addedArcs = addedArcs;
            this.q = q; this.c = c; this.t = t; this.k = k;
            this.seedOffset = previous == null ? key.scalar().previousOffset()
                : previous.seedOffset;
            this.seedId = previous == null ? initialSeedId : previous.seedId;
            this.consumed = consumed; this.depth = previous == null ? 1 : previous.depth + 1;
            this.memory = memory;
            if (previous != null) previous.retainedChildren++;
        }
        double total() { return q + c + t + k; }
    }

    /** Runs every original seed under one retained work budget and memory owner. */
    public EmissionResult solve(SampledEmissionInput input, List<CrossSectionProfile> suppressed,
            List<Double> seeds, List<AnchorProposal> proposals, Smoother smoother,
            CancellationProbe cancellation) {
        return solve(input, suppressed, seeds, proposals, smoother, cancellation,
            NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
            NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1);
    }

    /** Applies one caller-explicit existing evaluator policy under the same attempt and numerical objective. */
    public EmissionResult solve(SampledEmissionInput input, List<CrossSectionProfile> suppressed,
            List<Double> seeds, List<AnchorProposal> proposals, Smoother smoother,
            CancellationProbe cancellation, NativeConnectorEvaluator.QueryPolicy queryPolicy,
            NativeConnectorEvaluator.ExecutionPolicy executionPolicy) {
        return solve(input, suppressed, seeds, proposals, smoother, cancellation, queryPolicy, executionPolicy, false);
    }

    /** Versioned measured-mode affinity experiment; original v2 solve remains unchanged. */
    public EmissionResult solveMeasuredAffinity(SampledEmissionInput input, List<CrossSectionProfile> suppressed,
            List<Double> seeds, List<AnchorProposal> proposals, Smoother smoother,
            CancellationProbe cancellation) {
        return solve(input, suppressed, seeds, proposals, smoother, cancellation,
            NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
            NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1, true);
    }

    private EmissionResult solve(SampledEmissionInput input, List<CrossSectionProfile> suppressed,
            List<Double> seeds, List<AnchorProposal> proposals, Smoother smoother,
            CancellationProbe cancellation, NativeConnectorEvaluator.QueryPolicy queryPolicy,
            NativeConnectorEvaluator.ExecutionPolicy executionPolicy, boolean measuredAffinity) {
        Objects.requireNonNull(input); Objects.requireNonNull(suppressed); Objects.requireNonNull(seeds);
        Objects.requireNonNull(proposals); Objects.requireNonNull(smoother); Objects.requireNonNull(cancellation);
        Objects.requireNonNull(queryPolicy); Objects.requireNonNull(executionPolicy);
        if (executionPolicy != NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1
                && queryPolicy != NativeConnectorEvaluator.QueryPolicy.ZERO_GUARDED_LOCAL_V1)
            throw new IllegalArgumentException("Stop-on-final-mode-failure requires the zero-guarded local query policy");
        input.requireOpenAndUnchanged();
        var binding = input.legacyBinding().orElseThrow(() ->
            new IllegalArgumentException("Original Legacy binding is required"));
        if (binding.observations().size() < 2) throw new IllegalArgumentException("Legacy requires at least two original rows");
        if (binding.protectedOccurrences().stream().anyMatch(event -> !event.fixed()))
            throw new UnavailableException(UnavailableReason.MOVABLE_BOUNDARY_ASSIGNMENT);
        double pitch = input.context().nativePitchMeters().orElseThrow(() ->
            new UnavailableException(UnavailableReason.NATIVE_PITCH));
        var budget = input.workBudget();
        var resultLease = input.retainResultLifetime();
        AttemptMemoryLedger.Owner owner;
        try {
            owner = input.openExecutionOwner("legacy-emission-kernel");
        } catch (RuntimeException failure) {
            resultLease.close();
            throw failure;
        }
        NativeConnectorEvaluator bestEvaluator = null;
        AttemptMemoryLedger.Owner bestSeedOwner = null;
        try {
            var domain = new LegacyEmissionDomain(binding, suppressed, seeds, budget, owner,
                measuredAffinity ? input.experimentalMeasuredModes() : null);
            var affinity = measuredAffinity ? new OrdinaryAffinityClassifier(input, domain, owner) : null;
            Node best = null;
            Inventory bestInventory = null;
            for (var seed : domain.seeds()) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                cancellation.checkpoint();
                var seedOwner = owner.child("seed-" + seed.id());
                NativeConnectorEvaluator evaluator = null;
                boolean retained = false;
                try {
                    var inventory = inventory(domain, budget, seedOwner, affinity);
                    evaluator = evaluator(input, domain, inventory, queryPolicy, executionPolicy);
                    Node candidate = solveBase(input, domain, seed, inventory, evaluator,
                        seedOwner, cancellation, pitch);
                    if (candidate != null && better(candidate, best, budget, seedOwner)) {
                        if (bestEvaluator != null) bestEvaluator.close();
                        if (bestSeedOwner != null) bestSeedOwner.close();
                        best = candidate; bestInventory = inventory; bestEvaluator = evaluator;
                        bestSeedOwner = seedOwner; retained = true;
                    }
                } finally {
                    if (!retained) {
                        if (evaluator != null) evaluator.close();
                        seedOwner.close();
                    }
                }
            }
            if (best == null) throw new UnavailableException(UnavailableReason.NO_ADMITTED_PATH);
            if (best.key.lastPositive() == null)
                throw new UnavailableException(UnavailableReason.NO_POSITIVE_PHYSICAL_LEG);

            Node firstStage = best;
            // First-stage flattening retains its drafts until provenance is copied into the
            // result. Keep that ownership separate from the current stitched winner.
            pinChain(firstStage, budget);
            try (var path = flatten(firstStage, budget, bestSeedOwner)) {
            verifyCosts(path.arcs(), firstStage, budget);
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
            var intervalReferences = new ArrayList<EmissionResult.IntervalStageReference>();
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
                Node previousBest = best;
                Node intervalWinner = solveInterval(input, domain, prefix, rightState, left, right,
                    index + 1, bestInventory, bestEvaluator, bestSeedOwner, cancellation, pitch);
                if (intervalWinner == null) throw new UnavailableException(UnavailableReason.NO_ADMITTED_PATH);
                Node interval = intervalWinner;
                var intervalCost = intervalIncrement(prefix, interval, budget);
                intervalCosts.add(intervalCost);
                intervalReferences.add(intervalStage(prefix, interval, left, right,
                    index + 1, intervalCost, domain, input, budget));
                boolean suffix = false;
                for (Node node : chain) {
                    int row = node.key.last().originalRow();
                    if (row == right.originalRow()) { suffix = true; continue; }
                    if (suffix) {
                        interval = advance(input, domain, bestInventory, interval, node.key.last(),
                            bestEvaluator, bestSeedOwner,
                            cancellation, index + 1, pitch);
                        if (interval == null)
                            throw new UnavailableException(UnavailableReason.NO_ADMITTED_PATH);
                    }
                }
                pinChain(interval, budget);
                // Transfer the selected interval's ownership to the stitched path before
                // releasing either predecessor chain; their prefixes may be shared.
                unpinChain(intervalWinner, budget);
                unpinChain(previousBest, budget);
                best = interval;
            }
            try (var stitchedPath = flatten(best, budget, bestSeedOwner)) {
            verifyCosts(stitchedPath.arcs(), best, budget);
            if (best.key.lastPositive() == null)
                throw new UnavailableException(UnavailableReason.NO_POSITIVE_PHYSICAL_LEG);
            return result(input, domain, stitchedPath, path, firstStage, best, intervalCosts,
                intervalReferences, eligible, bestInventory,
                smoother, bestSeedOwner, owner, bestEvaluator, budget, cancellation, resultLease, queryPolicy, executionPolicy);
            }
            }
        } catch (RuntimeException failure) {
            if (bestEvaluator != null) bestEvaluator.close();
            owner.close();
            resultLease.close();
            if (failure instanceof CancellationException
                    || failure instanceof EmissionWorkBudget.LimitExceededException
                    || failure instanceof AttemptMemoryLedger.ResourceLimitException
                    || failure instanceof LegacyEmissionDomain.StateFamilyLimitException
                    || failure instanceof NativeConnectorEvaluator.StateFamilyLimitException)
                input.recordTerminalExecutionFailure(failure);
            throw failure;
        }
    }

    private record CarryIdentity(int row, int originId, long offsetBits) { }
    private record TargetIdentity(int row, int leftAnchorRow, int rightAnchorRow) { }
    private record ConnectorIdentity(LegacyEmissionDomain.State from,
            LegacyEmissionDomain.State to) { }
    private record OrdinaryLegIdentity(NativeConnectorEvaluator.PhysicalEndpoint from,
            NativeConnectorEvaluator.PhysicalEndpoint to) { }
    private record DirectionOrigin(ReferenceIdentity optional, ReferenceIdentity ordinary) { }
    private record ZeroCursorIdentity(DirectionOrigin origin,
            NativeConnectorEvaluator.PhysicalEndpoint from,
            NativeConnectorEvaluator.PhysicalEndpoint to) { }
    private record ZeroConnectorIdentity(ConnectorIdentity endpoints, DirectionOrigin origin) { }
    private record Inventory(List<List<LegacyEmissionDomain.State>> byRow, int[] familySizes,
            Map<CarryIdentity, LegacyEmissionDomain.State> reachedCarries,
            Map<TargetIdentity, LegacyEmissionDomain.State> intervalTargets,
            Map<String, NativeConnectorEvaluator.Barrier> barriersById,
            Map<ConnectorIdentity, NativeConnectorEvaluator.Receipt> positiveReceipts,
            Map<OrdinaryLegIdentity, NativeConnectorEvaluator.OrdinaryPhysicalLeg> ordinaryLegs,
            Map<ZeroCursorIdentity, NativeConnectorEvaluator.PhysicalDirectionCursor> zeroCursors,
            Map<ZeroConnectorIdentity, NativeConnectorEvaluator.Receipt> zeroReceipts,
            List<NativeConnectorEvaluator.RowState> evaluatorStates, OrdinaryAffinityClassifier affinity) { }

    private Inventory inventory(LegacyEmissionDomain domain,
            EmissionWorkBudget budget, AttemptMemoryLedger.Owner owner, OrdinaryAffinityClassifier affinity) {
        var byRow = new ArrayList<List<LegacyEmissionDomain.State>>(domain.rowCount());
        var familySizes = new int[domain.rowCount()];
        var all = new ArrayList<NativeConnectorEvaluator.RowState>();
        for (int row = 0; row < domain.rowCount(); row++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var states = domain.observedAt(row);
            if (states.size() > budget.stateFamilyLimit())
                throw new UnavailableException(UnavailableReason.STATE_FAMILY_LIMIT);
            familySizes[row] = states.size();
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION, states.size() + 1L);
            owner.reserve(Math.addExact(64, Math.multiplyExact(128L, states.size())));
            byRow.add(states);
            for (var state : states) all.add(state.evaluatorState());
        }
        return new Inventory(List.copyOf(byRow), familySizes, new HashMap<>(), new HashMap<>(),
            new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
            new HashMap<>(), List.copyOf(all), affinity);
    }

    private LegacyEmissionDomain.State reachedCarry(LegacyEmissionDomain domain, Inventory inventory,
            NativeConnectorEvaluator evaluator, int row, int originId, double offset,
            EmissionWorkBudget budget, AttemptMemoryLedger.Owner owner) {
        var key = new CarryIdentity(row, originId, Double.doubleToLongBits(offset));
        budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
        var reached = inventory.reachedCarries().get(key);
        if (reached != null) return reached;
        requireFamilyCapacity(row, inventory.familySizes()[row], budget);
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        owner.reserve(160);
        var state = domain.carry(row, originId, offset, budget);
        evaluator.registerState(state.evaluatorState(), row);
        inventory.reachedCarries().put(key, state);
        inventory.familySizes()[row]++;
        return state;
    }

    private LegacyEmissionDomain.State intervalTarget(LegacyEmissionDomain domain, Inventory inventory,
            NativeConnectorEvaluator evaluator, int row, LegacyEmissionDomain.State left,
            LegacyEmissionDomain.State right, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner) {
        var key = new TargetIdentity(row, left.originalRow(), right.originalRow());
        budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
        var existing = inventory.intervalTargets().get(key);
        if (existing != null) return existing;
        requireFamilyCapacity(row, inventory.familySizes()[row], budget);
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        owner.reserve(160);
        var state = domain.intervalTarget(row, left.originalRow(), right.originalRow(), left, right,
            budget);
        evaluator.registerState(state.evaluatorState(), row);
        inventory.intervalTargets().put(key, state);
        inventory.familySizes()[row]++;
        return state;
    }

    /** Shared precreation gate for both genuine generated-state families. */
    static void requireFamilyCapacity(int originalRow, int existingMembers,
            EmissionWorkBudget budget) {
        budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
        if (existingMembers >= budget.stateFamilyLimit())
            throw new LegacyEmissionDomain.StateFamilyLimitException(originalRow,
                Math.addExact(existingMembers, 1), budget.stateFamilyLimit());
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

    private static EmissionResult.Costs intervalIncrement(Node prefix, Node terminal,
            EmissionWorkBudget budget) {
        double q = 0, c = 0, t = 0, k = 0;
        for (Node node = terminal; node != prefix; node = node.previous) {
            budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
            if (node == null) throw new IllegalStateException("Anchored prefix is not an ancestor");
            for (var arc : node.addedArcs) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                q += arc.q(); c += arc.c(); t += arc.t(); k += arc.k();
            }
        }
        return new EmissionResult.Costs(q, c, t, k);
    }

    private static EmissionResult.IntervalStageReference intervalStage(Node prefix, Node terminal,
            AnchorProposal left, AnchorProposal right, int ordinal,
            EmissionResult.Costs costs, LegacyEmissionDomain domain, SampledEmissionInput input,
            EmissionWorkBudget budget) {
        var reverse = new ArrayList<Node>();
        for (Node node = terminal; node != prefix; node = node.previous) {
            budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
            if (node == null) throw new IllegalStateException("Interval prefix is not an ancestor");
            reverse.add(node);
        }
        java.util.Collections.reverse(reverse);
        var vertices = new ArrayList<EmissionResult.Vertex>();
        vertices.add(vertex(new PointDraft(new EmissionMapping.ObservationVertex(left.originalRow()),
            prefix.key.last(), domain.metricPoint(prefix.key.last(), input.context()))));
        var arcs = new ArrayList<ArcDraft>();
        for (Node node : reverse) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            for (var point : node.addedPoints) vertices.add(vertex(point));
            arcs.addAll(node.addedArcs);
        }
        return new EmissionResult.IntervalStageReference(ordinal,
            new EmissionResult.AnchorReference(left.originalRow(), domain.originalPeakId(left.peakOwner())),
            new EmissionResult.AnchorReference(right.originalRow(), domain.originalPeakId(right.peakOwner())),
            vertices, arcResults(arcs), costs);
    }

    private static void verifyCosts(List<ArcDraft> arcs, Node terminal,
            EmissionWorkBudget budget) {
        double q = 0, c = .16 * Math.abs(terminal.key.scalar().lastOffset()
            - terminal.key.scalar().previousOffset()), t = 0, k = 0;
        // Initial prior belongs to the first emitted row, which may differ from the terminal row.
        Node first = terminal;
        while (first.previous != null) {
            budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
            first = first.previous;
        }
        double departure = Math.abs(first.key.scalar().lastOffset() - first.seedOffset);
        c = .16 * departure; t = .12 * departure;
        for (var arc : arcs) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            q += arc.q(); c += arc.c(); t += arc.t(); k += arc.k();
        }
        double tolerance = 1e-8 * Math.max(1, terminal.total());
        if (Math.abs(q - terminal.q) > tolerance || Math.abs(c - terminal.c) > tolerance
                || Math.abs(t - terminal.t) > tolerance || Math.abs(k - terminal.k) > tolerance)
            throw new IllegalStateException("Stitched Legacy objective differs from actual arc costs");
    }

    private Node solveInterval(SampledEmissionInput input, LegacyEmissionDomain domain,
            Node prefix, LegacyEmissionDomain.State rightState, AnchorProposal left,
            AnchorProposal right, int stageOrdinal, Inventory inventory,
            NativeConnectorEvaluator evaluator,
            AttemptMemoryLedger.Owner owner, CancellationProbe cancellation, double pitch) {
        var budget = input.workBudget();
        int intervalRows = right.originalRow() - left.originalRow() + 1;
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, intervalRows + 1L);
        var layerMemory = owner.reserve(Math.multiplyExact(128L, intervalRows));
        try {
        var layers = new HashMap<Integer, Map<Key, Node>>();
        var firstLayer = new LinkedHashMap<Key, Node>();
        firstLayer.put(prefix.key, prefix);
        layers.put(left.originalRow(), firstLayer);
        for (int row = left.originalRow(); row < right.originalRow(); row++) {
            var current = layers.get(row);
            if (current == null) continue;
            for (Node from : List.copyOf(current.values())) {
                budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
                cancellation.checkpoint();
                for (int toRow = row + 1; toRow <= right.originalRow() && toRow <= row + 17; toRow++) {
                    budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                    if (toRow > row + 1 && (sourceSpanUpper(domain, input.context(), row, toRow) > 20
                            || crossesMandatory(domain, row, toRow))) continue;
                    List<LegacyEmissionDomain.State> choices;
                    if (toRow == right.originalRow()) choices = List.of(rightState);
                    else if (domain.unsupported(toRow))
                        choices = List.of(intervalTarget(domain, inventory, evaluator, toRow,
                            prefix.key.last(), rightState, budget, owner));
                    else choices = domain.observedAt(toRow);
                    for (var to : choices) {
                        budget.charge(EmissionWorkBudget.Operation.TRANSITION, 1);
                        Node next = advance(input, domain, inventory, from, to, evaluator, owner,
                            cancellation, stageOrdinal, pitch);
                        if (next != null) insert(layers.computeIfAbsent(toRow, ignored -> new LinkedHashMap<>()), next,
                            budget, owner);
                    }
                }
            }
        }
        Node best = null;
        for (var candidate : layers.getOrDefault(right.originalRow(), Map.of()).values())
            if (better(candidate, best, budget, owner)) best = candidate;
        if (best != null) pinChain(best, budget);
        for (var layer : layers.values())
            for (Node node : layer.values()) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                node.inLayer = false;
            }
        for (var layer : layers.values())
            for (Node node : layer.values())
                releaseUnretained(node, budget);
        return best;
        } finally {
            layerMemory.close();
        }
    }

    private NativeConnectorEvaluator evaluator(SampledEmissionInput input, LegacyEmissionDomain domain,
            Inventory inventory, NativeConnectorEvaluator.QueryPolicy queryPolicy,
            NativeConnectorEvaluator.ExecutionPolicy executionPolicy) {
        var binding = domain.binding();
        var mapping = binding.originalRowToRetainedObservation();
        var ordinals = new HashMap<Integer, Integer>();
        for (int row = 0; row < mapping.size(); row++)
            ordinals.put(mapping.get(row), row);
        var mandatory = new ArrayList<Integer>();
        mandatory.add(mapping.get(0)); mandatory.add(mapping.get(mapping.size() - 1));
        var barriers = new ArrayList<NativeConnectorEvaluator.Barrier>();
        for (var event : binding.protectedOccurrences()) {
            mandatory.add(mapping.get(event.leftOriginalRow()));
            mandatory.add(mapping.get(event.rightOriginalRow()));
            barriers.add(new NativeConnectorEvaluator.Barrier(event.occurrenceId(),
                mapping.get(event.leftOriginalRow()), mapping.get(event.rightOriginalRow()),
                event.sourceChainageMeters()
                    - input.context().profileChainage().sourceOriginGroundMeters(),
                event.exactPosition()));
            inventory.barriersById().put(event.occurrenceId(), barriers.get(barriers.size() - 1));
        }
        return new NativeConnectorEvaluator(input,
            new NativeConnectorEvaluator.Domain(inventory.evaluatorStates(), mandatory, barriers,
                ordinals), queryPolicy, executionPolicy, domain.measuredModes());
    }

    private Node solveBase(SampledEmissionInput input, LegacyEmissionDomain domain,
            LegacyEmissionDomain.Seed seed, Inventory inventory, NativeConnectorEvaluator evaluator,
            AttemptMemoryLedger.Owner owner, CancellationProbe cancellation, double pitch) {
        var budget = input.workBudget();
        int n = domain.rowCount();
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, n + 1L);
        var layerMemory = owner.reserve(Math.multiplyExact(128L, n));
        try {
        var layers = new ArrayList<Map<Key, Node>>(n);
        for (int row = 0; row < n; row++) {
            layers.add(new LinkedHashMap<>());
        }
        List<LegacyEmissionDomain.State> firstChoices = domain.unsupported(0)
            ? List.of(reachedCarry(domain, inventory, evaluator, 0, seed.id(),
                seed.offsetRasterPixels(), budget, owner)) : inventory.byRow().get(0);
        for (var first : firstChoices) {
            budget.charge(EmissionWorkBudget.Operation.PAIR_INITIALIZATION, 1);
            double departure = Math.abs(first.offsetRasterPixels() - seed.offsetRasterPixels());
            var key = new Key(first, new ScalarMemory(-1, seed.offsetRasterPixels(), 0,
                first.offsetRasterPixels(), first.offsetRasterPixels() - seed.offsetRasterPixels(),
                first.offsetOriginId()), null, null, new PhaseContext(0, null));
            var point = new PointDraft(new EmissionMapping.ObservationVertex(0), first,
                domain.metricPoint(first, input.context()));
            var node = allocateNode(owner, budget, null, key, List.of(point), List.of(),
                0, .16 * departure, .12 * departure, 0, 0, seed.id());
            insert(layers.get(0), node, budget, owner);
        }
        for (int row = 0; row < n - 1; row++) {
            for (Node from : List.copyOf(layers.get(row).values())) {
                budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
                cancellation.checkpoint();
                for (int toRow = row + 1; toRow < n && toRow <= row + 17; toRow++) {
                    budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                    if (toRow > row + 1 && sourceSpanUpper(domain, input.context(), row, toRow) > 20) break;
                    if (toRow > row + 1 && crossesMandatory(domain, row, toRow)) continue;
                    List<LegacyEmissionDomain.State> choices = domain.unsupported(toRow)
                        ? List.of(reachedCarry(domain, inventory, evaluator, toRow,
                            from.key.last().offsetOriginId(), from.key.last().offsetRasterPixels(),
                            budget, owner)) : inventory.byRow().get(toRow);
                    for (var to : choices) {
                        budget.charge(EmissionWorkBudget.Operation.TRANSITION, 1);
                        if (to.kind() == LegacyEmissionDomain.Kind.BASE_CARRY
                                && (to.offsetOriginId() != from.key.last().offsetOriginId()
                                    || Double.doubleToLongBits(to.offsetRasterPixels())
                                        != Double.doubleToLongBits(from.key.last().offsetRasterPixels())))
                            continue;
                        Node candidate = advance(input, domain, inventory, from, to, evaluator, owner,
                            cancellation, 0, pitch);
                        if (candidate != null) insert(layers.get(toRow), candidate, budget, owner);
                    }
                }
            }
        }
        Node best = null;
        for (Node candidate : layers.get(n - 1).values())
            if (better(candidate, best, budget, owner)) best = candidate;
        if (best != null) pinChain(best, budget);
        for (int row = n - 1; row >= 0; row--)
            for (Node node : layers.get(row).values()) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                node.inLayer = false;
            }
        for (int row = n - 1; row >= 0; row--)
            for (Node node : layers.get(row).values())
                releaseUnretained(node, budget);
        return best;
        } finally {
            layerMemory.close();
        }
    }

    private Node advance(SampledEmissionInput input, LegacyEmissionDomain domain,
            Inventory inventory, Node from,
            LegacyEmissionDomain.State to, NativeConnectorEvaluator evaluator,
            AttemptMemoryLedger.Owner owner, CancellationProbe cancellation,
            int stageOrdinal, double pitch) {
        var budget = input.workBudget();
        var a = from.key.last(); int rowA = a.originalRow(), rowB = to.originalRow();
        boolean optional = rowB > rowA + 1;
        MetricPoint pa = domain.metricPoint(a, input.context()), pb = domain.metricPoint(to, input.context());
        double sourceSpan = sourceChainage(domain, input.context(), rowB)
            - sourceChainage(domain, input.context(), rowA);
        List<PointDraft> points = new ArrayList<>();
        List<ArcDraft> arcs = new ArrayList<>();
        var events = eventsBetween(domain.binding(), rowA, rowB);
        if (optional && !events.isEmpty()) return null;
        if (!optional && events.isEmpty() && inventory.affinity() != null
                && !inventory.affinity().classifyInValidatedExecution(a, to, pa, pb).ordinaryEligible()) return null;
        MetricPoint cursor = pa;
        double cursorSource = sourceChainage(domain, input.context(), rowA);
        PhysicalLeg physical = from.key.lastPositive();
        Object incoming = from.key.incomingCursor() == null ? null : from.key.incomingCursor().value;
        NativeConnectorEvaluator.PhysicalEndpoint endpoint = a.evaluatorState();
        String lastBoundaryId = from.key.phase().lastBoundaryOccurrenceId();
        for (var event : events) {
            var boundary = inventory.barriersById().get(event.occurrenceId());
            if (boundary == null) throw new IllegalStateException("Protected event has no evaluator identity");
            double eventLocalChainage = event.sourceChainageMeters()
                - input.context().profileChainage().sourceOriginGroundMeters();
            var arc = ordinary(input, cursor, event.exactPosition(), cursorSource,
                eventLocalChainage, physical, budget, owner, cancellation);
            incoming = ordinaryCursor(evaluator, inventory, endpoint, boundary, cursor,
                event.exactPosition(), incoming, budget, owner);
            arc = arc.withCursor(incoming);
            arcs.add(arc); physical = nextPhysical(physical, arc, endpoint, boundary, incoming);
            points.add(new PointDraft(new EmissionMapping.BoundaryVertex(event.occurrenceId()), null,
                event.exactPosition()));
            cursor = event.exactPosition(); cursorSource = eventLocalChainage;
            endpoint = boundary;
            lastBoundaryId = event.occurrenceId();
        }
        ArcDraft finalArc;
        if (optional) {
            NativeConnectorEvaluator.Receipt receipt;
            if (cursor.distanceTo(pb) == 0 && incoming == null) return null;
            if (cursor.distanceTo(pb) == 0) {
                var origin = directionOrigin(incoming);
                if (origin == null) return null;
                budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
                var edge = new ZeroConnectorIdentity(new ConnectorIdentity(a, to), origin);
                receipt = inventory.zeroReceipts().get(edge);
                if (receipt == null) {
                    if (incoming instanceof NativeConnectorEvaluator.OrdinaryPhysicalLeg leg)
                        receipt = evaluator.evaluateWithOrdinaryIncoming(a.evaluatorState(),
                            to.evaluatorState(), leg);
                    else if (incoming instanceof NativeConnectorEvaluator.PhysicalDirectionCursor direction)
                        receipt = evaluator.evaluateWithDirectionCursor(a.evaluatorState(),
                            to.evaluatorState(), direction);
                    else receipt = evaluator.evaluate(a.evaluatorState(), to.evaluatorState(),
                        (NativeConnectorEvaluator.Receipt) incoming);
                    budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
                    owner.reserve(96);
                    inventory.zeroReceipts().put(edge, receipt);
                }
            } else {
                budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
                var edge = new ConnectorIdentity(a, to);
                receipt = inventory.positiveReceipts().get(edge);
                if (receipt == null) {
                    receipt = evaluator.evaluate(a.evaluatorState(), to.evaluatorState());
                    budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
                    owner.reserve(96);
                    inventory.positiveReceipts().put(edge, receipt);
                }
            }
            if (receipt.status() != NativeConnectorEvaluator.Status.ADMITTED) return null;
            double q = receipt.sourceWeightedLoss();
            finalArc = new ArcDraft(EmissionResult.ArcKind.OPTIONAL_ADMITTED, cursor, pb,
                sourceSpan, q, 0, 0, turn(physical, cursor, pb, pitch),
                Optional.of(receipt.scalar().orElseThrow().mean().orElseThrow()),
                Optional.of(receipt), null);
            incoming = receipt;
        } else {
            finalArc = ordinary(input, cursor, pb, cursorSource, sourceChainage(domain, input.context(), rowB),
                physical, budget, owner, cancellation);
            incoming = ordinaryCursor(evaluator, inventory, endpoint, to.evaluatorState(),
                cursor, pb, incoming, budget, owner);
            finalArc = finalArc.withCursor(incoming);
        }
        double scalarSlope = (to.offsetRasterPixels() - a.offsetRasterPixels()) / (rowB - rowA);
        double c = .16 * Math.abs(to.offsetRasterPixels() - a.offsetRasterPixels());
        double t = .12 * Math.abs(scalarSlope - from.key.scalar().lastSlope());
        finalArc = new ArcDraft(finalArc.kind(), finalArc.from(), finalArc.to(),
            finalArc.sourceSpan(), finalArc.q(), c, t, finalArc.k(), finalArc.mean(),
            finalArc.receipt(), finalArc.ordinaryCursor());
        arcs.add(finalArc);
        physical = nextPhysical(physical, finalArc, endpoint, to.evaluatorState(), incoming);
        points.add(new PointDraft(new EmissionMapping.ObservationVertex(rowB), to, pb));
        double qSum = 0, kSum = 0;
        for (var arc : arcs) { qSum += arc.q(); kSum += arc.k(); }
        var key = new Key(to, new ScalarMemory(rowA, a.offsetRasterPixels(), rowB,
            to.offsetRasterPixels(), scalarSlope, to.offsetOriginId()), physical,
            incoming == null ? null : new ReferenceIdentity(incoming),
            new PhaseContext(stageOrdinal, lastBoundaryId));
        return allocateNode(owner, budget, from, key, List.copyOf(points), List.copyOf(arcs),
            from.q + qSum, from.c + c, from.t + t, from.k + kSum,
            from.consumed + rowB - rowA - 1);
    }

    private ArcDraft ordinary(SampledEmissionInput input, MetricPoint from, MetricPoint to,
            double sourceFrom, double sourceTo, PhysicalLeg previous, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner, CancellationProbe cancellation) {
        double sourceSpan = sourceTo - sourceFrom;
        var context = input.context();
        RasterPoint ra = context.rasterAt(from), rb = context.rasterAt(to);
        var grid = input.context().nativeGrid();
        var ga = grid.toGrid(ra); var gb = grid.toGrid(rb);
        long cells = (long) Math.ceil(Math.abs(gb.x() - ga.x()) + Math.abs(gb.y() - ga.y())) + 16;
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        BilinearSegmentIntegral.Result integral;
        try (var scratch = owner.reserve(Math.addExact(4096, Math.multiplyExact(cells, 2048)))) {
            integral = BilinearSegmentIntegral.evaluate(grid, ga, gb, cancellation,
                () -> { cancellation.checkpoint(); budget.charge(EmissionWorkBudget.Operation.CELL_QUERY, 1); });
        }
        double pitch = input.context().nativePitchMeters().orElseThrow();
        boolean measured = integral.status() == BilinearSegmentIntegral.Status.COMPLETE;
        double mean = measured ? integral.mean().orElseThrow() : 0;
        double q = sourceSpan / pitch * (1 - mean);
        return new ArcDraft(measured ? EmissionResult.ArcKind.ORDINARY_MEASURED
                : EmissionResult.ArcKind.ORDINARY_UNAVAILABLE,
            from, to, sourceSpan, q, 0, 0, turn(previous, from, to, pitch),
            measured ? Optional.of(mean) : Optional.empty(), Optional.empty(), null);
    }

    /** Native adjacent admission is the unchanged Legacy row/event transition contract. */
    private static DirectionOrigin directionOrigin(Object incoming) {
        if (incoming instanceof NativeConnectorEvaluator.OrdinaryPhysicalLeg leg)
            return new DirectionOrigin(null, new ReferenceIdentity(leg));
        if (incoming instanceof NativeConnectorEvaluator.PhysicalDirectionCursor cursor)
            return cursor.optionalOrigin().isEmpty() && cursor.ordinaryOrigin().isEmpty() ? null
                : new DirectionOrigin(cursor.optionalOrigin().map(ReferenceIdentity::new).orElse(null),
                    cursor.ordinaryOrigin().map(ReferenceIdentity::new).orElse(null));
        if (incoming instanceof NativeConnectorEvaluator.Receipt receipt) {
            if (receipt.status() != NativeConnectorEvaluator.Status.ADMITTED) return null;
            if (receipt.emittedLengthMeters() > 0)
                return new DirectionOrigin(new ReferenceIdentity(receipt), null);
            if (receipt.transportedIncoming().isEmpty() && receipt.transportedOrdinary().isEmpty())
                return null;
            return new DirectionOrigin(receipt.transportedIncoming().map(ReferenceIdentity::new).orElse(null),
                receipt.transportedOrdinary().map(ReferenceIdentity::new).orElse(null));
        }
        return null;
    }

    private static Object ordinaryCursor(NativeConnectorEvaluator evaluator, Inventory inventory,
            NativeConnectorEvaluator.PhysicalEndpoint from,
            NativeConnectorEvaluator.PhysicalEndpoint to,
            MetricPoint fromPosition, MetricPoint toPosition, Object incoming,
            EmissionWorkBudget budget, AttemptMemoryLedger.Owner owner) {
        if (fromPosition.distanceTo(toPosition) > 0) {
            budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
            var edge = new OrdinaryLegIdentity(from, to);
            var cached = inventory.ordinaryLegs().get(edge);
            if (cached != null) return cached;
            var status = NativeConnectorEvaluator.NativeReferenceStatus.UNAVAILABLE;
            NativeConnectorEvaluator.OrdinaryPhysicalLeg leg;
            if (from instanceof NativeConnectorEvaluator.RowState a
                    && to instanceof NativeConnectorEvaluator.RowState b)
                leg = evaluator.registerOrdinaryPhysicalLeg(a, b, status);
            else if (from instanceof NativeConnectorEvaluator.RowState a
                    && to instanceof NativeConnectorEvaluator.Barrier b)
                leg = evaluator.registerOrdinaryPhysicalLeg(a, b, status);
            else if (from instanceof NativeConnectorEvaluator.Barrier a
                    && to instanceof NativeConnectorEvaluator.RowState b)
                leg = evaluator.registerOrdinaryPhysicalLeg(a, b, status);
            else if (from instanceof NativeConnectorEvaluator.Barrier a
                    && to instanceof NativeConnectorEvaluator.Barrier b)
                leg = evaluator.registerOrdinaryPhysicalLeg(a, b, status);
            else throw new IllegalArgumentException("Unknown ordinary physical endpoint type");
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
            owner.reserve(96);
            inventory.ordinaryLegs().put(edge, leg);
            return leg;
        }
        var origin = directionOrigin(incoming);
        if (origin == null) return null; // First direction remains unavailable.
        budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
        var edge = new ZeroCursorIdentity(origin, from, to);
        var cached = inventory.zeroCursors().get(edge);
        if (cached != null) return cached;
        NativeConnectorEvaluator.PhysicalDirectionCursor cursor;
        if (incoming instanceof NativeConnectorEvaluator.Receipt receipt)
            cursor = evaluator.advanceOrdinaryZero(receipt, from, to);
        else if (incoming instanceof NativeConnectorEvaluator.OrdinaryPhysicalLeg leg)
            cursor = evaluator.advanceOrdinaryZero(leg, from, to);
        else if (incoming instanceof NativeConnectorEvaluator.PhysicalDirectionCursor prior)
            cursor = evaluator.advanceOrdinaryZero(prior, from, to);
        else throw new IllegalArgumentException("Unknown ordinary incoming cursor type");
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        owner.reserve(96);
        inventory.zeroCursors().put(edge, cursor);
        return cursor;
    }

    private static PhysicalLeg nextPhysical(PhysicalLeg previous, ArcDraft arc,
            Object fromNativeIdentity, Object toNativeIdentity,
            Object nativeReference) {
        return arc.from().distanceTo(arc.to()) > 0
            ? new PhysicalLeg(arc.from(), arc.to(), fromNativeIdentity, toNativeIdentity,
                nativeReference == null ? null : new ReferenceIdentity(nativeReference)) : previous;
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

    private static int contextRow(LegacyEmissionDomain domain, int originalRow) {
        // Original Legacy ordinals can be a strict subset of the retained source rows.
        return domain.binding().observations().get(originalRow).retainedObservationIndex();
    }

    private static double sourceChainage(LegacyEmissionDomain domain,
            OptionalEmissionContext context, int originalRow) {
        return context.sourceChainageMeters(contextRow(domain, originalRow));
    }

    private static double sourceSpanUpper(LegacyEmissionDomain domain,
            OptionalEmissionContext context, int fromOriginalRow, int toOriginalRow) {
        // Admission needs the direct source-arc enclosure, never rounded prefix differences.
        return context.sourceSpanUpperMeters(contextRow(domain, fromOriginalRow),
            contextRow(domain, toOriginalRow));
    }

    private static Node allocateNode(AttemptMemoryLedger.Owner owner, EmissionWorkBudget budget,
            Node previous, Key key, List<PointDraft> points, List<ArcDraft> arcs,
            double q, double c, double t, double k, int consumed) {
        return allocateNode(owner, budget, previous, key, points, arcs, q, c, t, k,
            consumed, previous == null ? -1 : previous.seedId);
    }

    private static Node allocateNode(AttemptMemoryLedger.Owner owner, EmissionWorkBudget budget,
            Node previous, Key key, List<PointDraft> points, List<ArcDraft> arcs,
            double q, double c, double t, double k, int consumed, int initialSeedId) {
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        var reservation = owner.reserve(Math.addExact(256,
            Math.addExact(128L * points.size(), 192L * arcs.size())));
        return new Node(previous, key, points, arcs, q, c, t, k, consumed, reservation,
            initialSeedId);
    }

    private static void insert(Map<Key, Node> layer, Node candidate, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner) {
        budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
        Node old = layer.get(candidate.key);
        if (better(candidate, old, budget, owner)) {
            candidate.inLayer = true;
            layer.put(candidate.key, candidate);
            if (old != null) {
                old.inLayer = false;
                releaseUnretained(old, budget);
            }
        } else releaseUnretained(candidate, budget);
    }

    private static void releaseUnretained(Node node, EmissionWorkBudget budget) {
        while (node != null && !node.inLayer && node.pinCount == 0
                && node.retainedChildren == 0 && !node.released) {
            budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
            node.released = true;
            node.memory.close();
            Node parent = node.previous;
            if (parent != null) parent.retainedChildren--;
            node = parent;
        }
    }

    private static void pinChain(Node terminal, EmissionWorkBudget budget) {
        for (Node node = terminal; node != null; node = node.previous) {
            budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
            if (node.released) throw new IllegalStateException("Cannot pin a released Legacy predecessor");
            node.pinCount = Math.addExact(node.pinCount, 1);
        }
    }

    private static void unpinChain(Node terminal, EmissionWorkBudget budget) {
        for (Node node = terminal; node != null; node = node.previous) {
            budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
            if (node.pinCount <= 0 || node.released)
                throw new IllegalStateException("Legacy predecessor pin lifetime is unbalanced");
            node.pinCount--;
        }
        releaseUnretained(terminal, budget);
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

    private record Flattened(List<PointDraft> points, List<ArcDraft> arcs,
            AttemptMemoryLedger.Reservation memory) implements AutoCloseable {
        @Override public void close() { memory.close(); }
    }
    private static Flattened flatten(Node terminal, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner) {
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 3);
        var memory = owner.reserve(Math.addExact(256, Math.multiplyExact(32L, terminal.depth)));
        try {
            var nodes = new ArrayList<Node>();
            for (Node node = terminal; node != null; node = node.previous) {
                budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
                nodes.add(node);
            }
            var points = new ArrayList<PointDraft>(); var arcs = new ArrayList<ArcDraft>();
            for (int i = nodes.size() - 1; i >= 0; i--) {
                points.addAll(nodes.get(i).addedPoints); arcs.addAll(nodes.get(i).addedArcs);
            }
            return new Flattened(List.copyOf(points), List.copyOf(arcs), memory);
        } catch (RuntimeException failure) {
            memory.close();
            throw failure;
        }
    }

    private EmissionResult result(SampledEmissionInput input, LegacyEmissionDomain domain,
            Flattened path, Flattened firstPath, Node firstNode, Node best,
            List<EmissionResult.Costs> intervals,
            List<EmissionResult.IntervalStageReference> intervalReferences,
            List<AnchorProposal> eligible, Inventory inventory,
            Smoother smoother, AttemptMemoryLedger.Owner owner,
            AttemptMemoryLedger.Owner kernelOwner,
            NativeConnectorEvaluator evaluator, EmissionWorkBudget budget,
            CancellationProbe cancellation, SampledEmissionInput.ResultLease resultLease,
            NativeConnectorEvaluator.QueryPolicy queryPolicy, NativeConnectorEvaluator.ExecutionPolicy executionPolicy) {
        if (domain.measuredModes() != null) input.requireOpenAndUnchanged();
        int n = domain.rowCount();
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 6);
        owner.reserve(Math.addExact(1024, Math.addExact(Math.multiplyExact(96L, n),
            Math.multiplyExact(512L, path.points().size()))));
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
        List<EmissionResult.SmoothingOutcome> smoothingOutcomes = inventory.affinity() == null
            ? List.of() : new ArrayList<>();
        var smoothed = smooth(path, smoother, domain, input.context(), budget, owner, inventory.affinity(), smoothingOutcomes);
        var smoothedArcs = arcResults(remeasure(input, path, smoothed, best.seedOffset,
            owner, budget, cancellation));
        if (smoothedArcs.stream().noneMatch(arc -> arc.lengthMeters() > 0))
            throw new UnavailableException(UnavailableReason.NO_POSITIVE_PHYSICAL_LEG);
        var first = new EmissionResult.Costs(firstNode.q, firstNode.c, firstNode.t, firstNode.k);
        var stitched = new EmissionResult.Costs(best.q, best.c, best.t, best.k);
        var post = totals(smoothedArcs, best.seedOffset,
            path.points().get(0).state().offsetRasterPixels());
        double source = 0, measured = 0, length = 0;
        for (var arc : arcs) source += arc.sourceSpanMeters();
        for (var arc : smoothedArcs) {
            length += arc.lengthMeters();
            if (arc.meanNativeIntensity().isPresent()) measured += arc.lengthMeters();
        }
        var sourceRecords = new ArrayList<EmissionResult.OriginalObservation>(n);
        for (var observation : domain.binding().observations()) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var raster = observation.measurementProfile().samplingAnchor().rasterPoint();
            var metric = input.context().metricAt(raster);
            sourceRecords.add(new EmissionResult.OriginalObservation(observation.originalRow(),
                observation.retainedObservationIndex(), observation.sourceClass(), raster, metric,
                input.context().profileChainage().sourceOriginGroundMeters()
                    + input.context().sourceChainageMeters(observation.retainedObservationIndex()),
                observation.producerPeaks().stream().map(LegacyEmissionBinding.PeakOwner::originalPeakId).toList()));
        }
        var provenance = provenance(input, domain, firstPath, firstNode, best,
            intervalReferences, eligible, inventory, owner, budget, queryPolicy, executionPolicy);
        var affinityDiagnostics = affinityDiagnostics(domain, inventory, path, vertices, smoothed,
            owner, budget, smoothingOutcomes);
        if (domain.measuredModes() != null) input.requireOpenAndUnchanged();
        // The result retains immutable vertex/arc values, never the DP predecessor graph.
        unpinChain(best, budget);
        unpinChain(firstNode, budget);
        return new EmissionResult(sourceRecords, mapping, vertices, smoothed,
            arcs, smoothedArcs, first, intervals, stitched, post, source, measured, length,
            budget.snapshot(), owner.peakBytes(), provenance, () -> {
                try {
                    evaluator.close();
                } finally {
                    try {
                        owner.close();
                    } finally {
                        try {
                            kernelOwner.close();
                        } finally {
                            resultLease.close();
                        }
                    }
                }
            }, affinityDiagnostics);
    }

    private Optional<EmissionResult.AffinityDiagnostics> affinityDiagnostics(LegacyEmissionDomain domain,
            Inventory inventory, Flattened path, List<EmissionResult.Vertex> vertices,
            List<EmissionResult.Vertex> smoothed, AttemptMemoryLedger.Owner owner, EmissionWorkBudget budget,
            List<EmissionResult.SmoothingOutcome> smoothingOutcomes) {
        if (inventory.affinity() == null) return Optional.empty();
        long states = 0;
        for (int row = 0; row < domain.rowCount(); row++) states += domain.observedAt(row).size();
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        owner.reserve(512 + states * 256 + path.arcs().size() * 256L);
        var measured = new ArrayList<EmissionResult.MeasuredStateReference>();
        for (int row = 0; row < domain.rowCount(); row++) for (var state : domain.observedAt(row)) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var alias = domain.measuredModes().ownerFor(state.evaluatorState());
            if (alias != null) measured.add(new EmissionResult.MeasuredStateReference(row, alias.retainedRow(),
                state.nativeStateId(), state.offsetOriginId(), state.kind() == LegacyEmissionDomain.Kind.OBSERVED_SCALAR_MODE
                    ? EmissionResult.MeasuredOwnerKind.ACCEPTED_SCALAR_CENTER : EmissionResult.MeasuredOwnerKind.NATIVE_PEAK_ALIAS,
                state.offsetRasterPixels(), alias.metersPerRasterOffset(), alias.nativePitchMeters(),
                alias.receipt(), alias.rawCore(), alias.flanks()));
        }
        var inference = affinityArcs(inventory.affinity(), path, vertices, budget);
        var finalGeometry = affinityArcs(inventory.affinity(), path, smoothed, budget);
        return Optional.of(new EmissionResult.AffinityDiagnostics(ExperimentalMeasuredModeInventory.POLICY_ID,
            measured, inference, finalGeometry, inventory.affinity().preparedRowPairs(),
            inventory.affinity().stationExtractions(), smoothingOutcomes));
    }

    private List<EmissionResult.AffinityArcReference> affinityArcs(OrdinaryAffinityClassifier classifier,
            Flattened path, List<EmissionResult.Vertex> geometry, EmissionWorkBudget budget) {
        var result = new ArrayList<EmissionResult.AffinityArcReference>();
        for (int i = 0; i < path.arcs().size(); i++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            if (path.arcs().get(i).kind() == EmissionResult.ArcKind.OPTIONAL_ADMITTED) {
                result.add(new EmissionResult.AffinityArcReference(i, true, Optional.empty()));
                continue;
            }
            var from = path.points().get(i).state(); var to = path.points().get(i + 1).state();
            OrdinaryAffinityClassifier.Receipt receipt = null;
            if (from != null && to != null) {
                boolean moved = moved(geometry.get(i), path.points().get(i))
                    || moved(geometry.get(i + 1), path.points().get(i + 1));
                receipt = moved ? classifier.classifyFinalInValidatedExecution(from, to,
                    geometry.get(i).position(), geometry.get(i).offsetRasterPixels().orElseThrow(),
                    geometry.get(i + 1).position(), geometry.get(i + 1).offsetRasterPixels().orElseThrow())
                    : classifier.classifyInValidatedExecution(from, to, geometry.get(i).position(), geometry.get(i + 1).position());
                if (receipt.status() == OrdinaryAffinityClassifier.Status.INCOMPATIBLE)
                    throw new UnavailableException(UnavailableReason.FINAL_AFFINITY_CONFLICT);
                if (moved && receipt.status() != OrdinaryAffinityClassifier.Status.COMPATIBLE)
                    throw new UnavailableException(UnavailableReason.FINAL_AFFINITY_CONFLICT);
            }
            result.add(new EmissionResult.AffinityArcReference(i, false, Optional.ofNullable(receipt)));
        }
        return List.copyOf(result);
    }

    private static EmissionResult.Provenance provenance(SampledEmissionInput input,
            LegacyEmissionDomain domain, Flattened firstPath, Node firstNode, Node best,
            List<EmissionResult.IntervalStageReference> intervalReferences,
            List<AnchorProposal> eligible, Inventory inventory,
            AttemptMemoryLedger.Owner owner, EmissionWorkBudget budget,
            NativeConnectorEvaluator.QueryPolicy queryPolicy, NativeConnectorEvaluator.ExecutionPolicy executionPolicy) {
        var binding = domain.binding();
        long peakCount = binding.observations().stream()
            .mapToLong(observation -> observation.producerPeaks().size()).sum();
        long generatedCount = inventory.reachedCarries().size() + (long) inventory.intervalTargets().size();
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        owner.reserve(Math.addExact(1024, Math.addExact(Math.multiplyExact(160, peakCount),
            Math.addExact(Math.multiplyExact(160, generatedCount),
                Math.multiplyExact(192L, intervalReferences.size())))));
        var context = input.context();
        boolean revised = domain.measuredModes() != null;
        var source = new EmissionResult.SourceReference(context.sourceIdentity(),
            context.evidenceContentHash(), context.requestIdentity(), revised
                ? context.policyId() + "/" + ExperimentalMeasuredModeInventory.POLICY_ID : context.policyId(),
            revised ? "LEGACY_OPTIONAL_MEASURED_AFFINITY_V1"
                : modelPolicyId(queryPolicy, executionPolicy, owner, budget), binding.trackerNormalizationRasterPx(),
            context.nativePitchMeters().orElseThrow(), binding.productionEligible());
        var seeds = domain.seeds().stream().map(seed ->
            new EmissionResult.SeedReference(seed.id(), seed.offsetRasterPixels())).toList();
        var chosen = seeds.stream().filter(seed -> seed.id() == best.seedId)
            .findFirst().orElseThrow();
        if (Double.doubleToLongBits(chosen.offsetRasterPixels())
                != Double.doubleToLongBits(best.seedOffset))
            throw new IllegalStateException("Chosen Legacy seed identity changed");
        double edgeLimit = Double.NaN;
        for (int row = 0; row < domain.rowCount(); row++)
            for (var peak : domain.suppressedProfile(row).peaks())
                edgeLimit = Double.isNaN(edgeLimit) ? Math.abs(peak.offsetPx())
                    : Math.max(edgeLimit, Math.abs(peak.offsetPx()));
        var tables = new ArrayList<EmissionResult.NativeStateTable>();
        var origins = new ArrayList<EmissionResult.OriginReference>();
        for (var seed : seeds)
            origins.add(new EmissionResult.OriginReference(seed.id(), Optional.of(seed), Optional.empty()));
        for (int row = 0; row < domain.rowCount(); row++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var observation = binding.observations().get(row);
            var profile = domain.suppressedProfile(row);
            var references = new ArrayList<EmissionResult.NativePeakReference>();
            for (var peakOwner : observation.producerPeaks()) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                var peak = peakOwner.peak();
                boolean eligibleState = domain.observedAt(row).stream()
                    .anyMatch(state -> state.nativeStateId() == peakOwner.originalPeakId()
                        && state.peakOwner() == peak);
                if (eligibleState) {
                    double reward = nativeEvidenceReward(peak);
                    double penalty = nativeLocalPenalty(profile, peak,
                        binding.trackerNormalizationRasterPx(), edgeLimit);
                    references.add(new EmissionResult.NativePeakReference(peakOwner.originalPeakId(),
                        peak.offsetPx(), peak.intensity(), true, java.util.OptionalDouble.of(reward),
                        java.util.OptionalDouble.of(penalty), java.util.OptionalDouble.of(reward - penalty)));
                } else references.add(new EmissionResult.NativePeakReference(peakOwner.originalPeakId(),
                    peak.offsetPx(), peak.intensity(), false, java.util.OptionalDouble.empty(),
                    java.util.OptionalDouble.empty(), java.util.OptionalDouble.empty()));
            }
            var nativeProfile = observation.profile();
            var normal = nativeProfile.normalScreen();
            tables.add(new EmissionResult.NativeStateTable(row, observation.sourceClass(),
                nativeProfile.samplingAnchor().rasterPoint(), new RasterPoint(normal.x, normal.y),
                references));
            for (var state : domain.observedAt(row)) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                if (state.kind() == LegacyEmissionDomain.Kind.OBSERVED_SCALAR_MODE)
                    origins.add(new EmissionResult.OriginReference(state.offsetOriginId(), Optional.empty(),
                        Optional.empty(), Optional.of(new EmissionResult.MeasuredOriginReference(row, state.nativeStateId()))));
                else origins.add(new EmissionResult.OriginReference(state.offsetOriginId(),
                    Optional.empty(), Optional.of(new EmissionResult.AnchorReference(row, state.nativeStateId()))));
            }
        }
        origins.sort(Comparator.comparingInt(EmissionResult.OriginReference::originId));
        var generated = new ArrayList<EmissionResult.GeneratedStateReference>();
        for (var entry : inventory.reachedCarries().entrySet()) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var state = entry.getValue();
            generated.add(new EmissionResult.GeneratedStateReference(state.originalRow(),
                state.nativeStateId(), EmissionResult.StateKind.BASE_CARRY,
                state.offsetRasterPixels(), entry.getKey().originId(), Optional.empty(),
                Optional.empty()));
        }
        for (var entry : inventory.intervalTargets().entrySet()) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var state = entry.getValue();
            var left = eligible.stream().filter(anchor -> anchor.originalRow() == entry.getKey().leftAnchorRow())
                .findFirst().orElseThrow();
            var right = eligible.stream().filter(anchor -> anchor.originalRow() == entry.getKey().rightAnchorRow())
                .findFirst().orElseThrow();
            generated.add(new EmissionResult.GeneratedStateReference(state.originalRow(),
                state.nativeStateId(), EmissionResult.StateKind.INTERVAL_TARGET,
                state.offsetRasterPixels(), state.offsetOriginId(),
                Optional.of(anchorReference(domain, left)), Optional.of(anchorReference(domain, right))));
        }
        generated.sort(Comparator.comparingInt(EmissionResult.GeneratedStateReference::originalRow)
            .thenComparingInt(EmissionResult.GeneratedStateReference::nativeStateId)
            .thenComparingInt(EmissionResult.GeneratedStateReference::originId));
        var eligibleReferences = eligible.stream().map(anchor -> anchorReference(domain, anchor)).toList();
        var selected = new java.util.LinkedHashSet<EmissionResult.AnchorReference>();
        for (var stage : intervalReferences) {
            selected.add(stage.leftAnchor()); selected.add(stage.rightAnchor());
        }
        var firstVertices = firstPath.points().stream().map(LegacyEmissionKernel::vertex).toList();
        return new EmissionResult.Provenance(source, seeds, chosen, tables, origins, generated,
            eligibleReferences, List.copyOf(selected), firstVertices, arcResults(firstPath.arcs()),
            intervalReferences);
    }

    private static String modelPolicyId(NativeConnectorEvaluator.QueryPolicy queryPolicy,
            NativeConnectorEvaluator.ExecutionPolicy executionPolicy,
            AttemptMemoryLedger.Owner owner, EmissionWorkBudget budget) {
        String base = "LEGACY_OPTIONAL_NATIVE_INTEGRAL_V2";
        if (queryPolicy == NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1
                && executionPolicy == NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1) return base;
        int characters = base.length() + "/query=".length() + queryPolicy.name().length()
            + "/execution=".length() + executionPolicy.name().length();
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        owner.reserve(AttemptMemoryLedger.objectBytes(24) + AttemptMemoryLedger.arrayBytes(characters, Character.BYTES));
        return base + "/query=" + queryPolicy.name() + "/execution=" + executionPolicy.name();
    }

    private static EmissionResult.AnchorReference anchorReference(LegacyEmissionDomain domain,
            AnchorProposal proposal) {
        return new EmissionResult.AnchorReference(proposal.originalRow(),
            domain.originalPeakId(proposal.peakOwner()));
    }

    private static double nativeEvidenceReward(CrossSectionPeak peak) {
        return peak.intensity() * 2.25
            + peak.gradientStrength() * (0.22 + 0.18 * peak.gradientBalance())
            + peak.nativeFilteredAgreement() * 0.12
            + peak.scaleAgreement() * 0.06;
    }

    private static double nativeLocalPenalty(CrossSectionProfile profile, CrossSectionPeak peak,
            double sourcePixelSizePx, double edgeLimitPx) {
        double strongest = profile.peaks().stream().mapToDouble(CrossSectionPeak::intensity)
            .max().orElse(0.0);
        double regret = Math.max(0.0, strongest - peak.intensity());
        double penalty = regret * 4.2;
        if (Double.isFinite(edgeLimitPx) && edgeLimitPx > 0.0
                && Math.abs(peak.offsetPx()) >= edgeLimitPx * 0.88
                && peak.supportWidthPx() <= Math.max(3.0, sourcePixelSizePx * 0.30))
            penalty += 0.65 + regret * 2.0;
        if (peak.supportWidthPx() <= sourcePixelSizePx * 0.15
                && strongest > peak.intensity() + 0.03)
            penalty += 0.30;
        penalty += (1.0 - peak.nativeFilteredAgreement()) * 0.18;
        penalty += (1.0 - peak.scaleAgreement()) * 0.10;
        penalty += Math.min(0.12, peak.centerUncertaintyPx()
            / Math.max(1.0, sourcePixelSizePx) * 0.03);
        return penalty;
    }

    private static EmissionResult.Vertex vertex(PointDraft point) {
        if (point.state() == null)
            return new EmissionResult.Vertex(point.position(), point.owner(),
                EmissionResult.StateKind.BOUNDARY_EVENT, Optional.empty(), Optional.empty());
        var state = point.state();
        var kind = switch (state.kind()) {
            case OBSERVED_PEAK, OBSERVED_SCALAR_MODE -> EmissionResult.StateKind.OBSERVED_PEAK;
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
                arc.from().distanceTo(arc.to()), arc.q(), arc.c(), arc.t(), arc.k(), arc.mean(),
                arc.receipt(),
                arc.ordinaryCursor() instanceof NativeConnectorEvaluator.OrdinaryPhysicalLeg leg
                    ? Optional.of(leg) : Optional.empty(),
                arc.ordinaryCursor() instanceof NativeConnectorEvaluator.PhysicalDirectionCursor cursor
                    ? Optional.of(cursor) : Optional.empty()));
        }
        return List.copyOf(result);
    }

    private static List<EmissionResult.Vertex> smooth(Flattened path, Smoother smoother,
            LegacyEmissionDomain domain, OptionalEmissionContext context, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner, OrdinaryAffinityClassifier affinity,
            List<EmissionResult.SmoothingOutcome> outcomes) {
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
                if (affinity != null) {
                    budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
                    owner.reserve(256L + 512L * (end - start + 1));
                }
                var rawRun = affinity == null ? List.<EmissionResult.Vertex>of()
                    : List.copyOf(vertices.subList(start, end + 1));
                var profiles = new ArrayList<CrossSectionProfile>();
                var offsets = new ArrayList<Double>(); var intensities = new ArrayList<Double>();
                for (int i = start; i <= end; i++) {
                    var state = path.points().get(i).state();
                    profiles.add(domain.binding().observations().get(state.originalRow()).profile());
                    offsets.add(state.offsetRasterPixels());
                    var measured = domain.measuredModes() == null ? null
                        : domain.measuredModes().ownerFor(state.evaluatorState());
                    intensities.add(state.peakOwner() != null ? state.peakOwner().intensity()
                        : measured != null ? measured.receipt().originPeak().value() : 0.0);
                }
                var result = smoother.smooth(List.copyOf(profiles), List.copyOf(offsets), List.copyOf(intensities));
                if (result.size() != offsets.size())
                    throw new IllegalArgumentException("Legacy smoother changed run length");
                for (int i = start + 1; i < end; i++) {
                    var state = path.points().get(i).state();
                    var profile = domain.binding().observations().get(state.originalRow()).measurementProfile();
                    double offset = result.get(i - start);
                    if (!Double.isFinite(offset)) throw new IllegalArgumentException("Legacy smoother produced nonfinite offset");
                    var rp = new RasterPoint(profile.anchorScreen().x + profile.normalScreen().x * offset,
                        profile.anchorScreen().y + profile.normalScreen().y * offset);
                    var mp = context.metricAt(rp);
                    vertices.set(i, new EmissionResult.Vertex(mp,
                        path.points().get(i).owner(), vertices.get(i).kind(),
                        vertices.get(i).nativeStateId(), Optional.of(offset)));
                }
                if (affinity != null) {
                    var evidence = new ArrayList<OrdinaryAffinityClassifier.Receipt>();
                    boolean changed = false, safe = true;
                    var reason = EmissionResult.SmoothingReason.NO_CHANGE;
                    for (int edge = start; edge < end; edge++) {
                        budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                        if (!moved(vertices.get(edge), path.points().get(edge))
                                && !moved(vertices.get(edge + 1), path.points().get(edge + 1))) continue;
                        changed = true;
                        var receipt = affinity.classifyFinalInValidatedExecution(path.points().get(edge).state(),
                            path.points().get(edge + 1).state(), vertices.get(edge).position(),
                            vertices.get(edge).offsetRasterPixels().orElseThrow(), vertices.get(edge + 1).position(),
                            vertices.get(edge + 1).offsetRasterPixels().orElseThrow());
                        evidence.add(receipt);
                        if (receipt.status() != OrdinaryAffinityClassifier.Status.COMPATIBLE) {
                            safe = false;
                            reason = receipt.status() == OrdinaryAffinityClassifier.Status.INCOMPATIBLE
                                ? EmissionResult.SmoothingReason.FINAL_BRANCH_CONFLICT
                                : EmissionResult.SmoothingReason.FINAL_EVIDENCE_UNAVAILABLE;
                            break;
                        }
                    }
                    if (!safe) for (int i = start; i <= end; i++) vertices.set(i, rawRun.get(i - start));
                    if (changed && safe) reason = EmissionResult.SmoothingReason.MEASURED_COMPATIBLE;
                    outcomes.add(new EmissionResult.SmoothingOutcome(start, end,
                        !changed ? EmissionResult.SmoothingDisposition.UNCHANGED : safe
                            ? EmissionResult.SmoothingDisposition.ACCEPTED : EmissionResult.SmoothingDisposition.RAW_RETAINED,
                        reason, evidence));
                }
            }
            start = end + 1;
        }
        return List.copyOf(vertices);
    }

    private static boolean moved(EmissionResult.Vertex actual, PointDraft raw) {
        return !actual.position().equals(raw.position()) || raw.state() != null
            && !ExperimentalMeasuredModeInventory.same(actual.offsetRasterPixels().orElseThrow(), raw.state().offsetRasterPixels());
    }

    private List<ArcDraft> remeasure(SampledEmissionInput input, Flattened path,
            List<EmissionResult.Vertex> smoothed, double seedOffset, AttemptMemoryLedger.Owner owner,
            EmissionWorkBudget budget, CancellationProbe cancellation) {
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
                    sourceFrom + original.sourceSpan(), physical, budget, owner, cancellation);
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
                c, t, k, measured.mean(), measured.receipt(), null);
            result.add(arc);
            physical = nextPhysical(physical, arc,
                path.points().get(i).state() == null ? path.points().get(i).owner()
                    : path.points().get(i).state(),
                path.points().get(i + 1).state() == null ? path.points().get(i + 1).owner()
                    : path.points().get(i + 1).state(), arc.receipt().orElse(null));
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
