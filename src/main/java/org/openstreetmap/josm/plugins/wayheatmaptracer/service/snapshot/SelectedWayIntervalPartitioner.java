package org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedRelation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ExternalPort;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;

/** Proves immutable manual-junction islands and partitions one selected way into owned ranges. */
public final class SelectedWayIntervalPartitioner {
    private static final double NORMAL_PORT_METERS = 30.0;
    private static final double MAXIMUM_PORT_METERS = 60.0;
    private static final int MAXIMUM_PARTITION_OCCURRENCES = 1_000_000;

    /** Describes why a selected-way occurrence constrains an interval boundary. */
    public enum BoundaryKind { SELECTED_ENDPOINT, FIXED_ISLAND, ELIGIBLE_SIMPLE_T }

    /** A read-only endpoint constraint retained for the later joint interval plan. */
    public record BoundaryConstraint(BoundaryKind kind, int occurrenceIndex,
            PrimitiveKey nodeKey, boolean mayMove, boolean requiresJointPlan,
            ManualJunctionEligibility.Reason reason) {
        public BoundaryConstraint {
            if (kind == null || occurrenceIndex < 0 || nodeKey == null
                    || nodeKey.type() != PrimitiveKey.Type.NODE || reason == null
                    || requiresJointPlan != (kind == BoundaryKind.ELIGIBLE_SIMPLE_T)) {
                throw new IllegalArgumentException("Interval boundary constraint is inconsistent");
            }
        }
    }

    /** One inclusive, immutable original selected-way occurrence span that must remain exact. */
    public record FixedIsland(OccurrenceRange range, List<PrimitiveKey> occurrenceKeys,
            Set<PrimitiveKey> junctionKeys, List<ManualJunctionEligibility.Reason> reasons,
            BoundaryConstraint beforeBoundary, BoundaryConstraint afterBoundary,
            Set<PrimitiveKey> provedPrimitiveKeys, Set<ExternalPort> provedPorts) {
        public FixedIsland {
            occurrenceKeys = List.copyOf(occurrenceKeys);
            junctionKeys = Set.copyOf(junctionKeys);
            reasons = List.copyOf(reasons);
            provedPrimitiveKeys = Set.copyOf(provedPrimitiveKeys);
            provedPorts = Set.copyOf(provedPorts);
            if (range == null || occurrenceKeys.size() != range.size() || reasons.isEmpty()
                    || beforeBoundary == null || afterBoundary == null) {
                throw new IllegalArgumentException("Fixed island proof is incomplete");
            }
            if (beforeBoundary.occurrenceIndex() != range.firstIndex()
                    || afterBoundary.occurrenceIndex() != range.lastIndex()
                    || !beforeBoundary.nodeKey().equals(occurrenceKeys.get(0))
                    || !afterBoundary.nodeKey().equals(occurrenceKeys.get(occurrenceKeys.size() - 1))) {
                throw new IllegalArgumentException("Fixed island boundaries must own its exact endpoints");
            }
        }
    }

    /** One inclusive ownership span; adjoining fixed boundary nodes remain read-only anchors. */
    public record SlideInterval(OccurrenceRange range, List<PrimitiveKey> occurrenceKeys,
            BoundaryConstraint startBoundary, BoundaryConstraint endBoundary) {
        public SlideInterval {
            occurrenceKeys = List.copyOf(occurrenceKeys);
            if (range == null || occurrenceKeys.size() != range.size()
                    || startBoundary == null || endBoundary == null
                    || startBoundary.occurrenceIndex() > range.firstIndex()
                    || endBoundary.occurrenceIndex() < range.lastIndex()
                    || startBoundary.occurrenceIndex() > endBoundary.occurrenceIndex()
                    || endBoundary.occurrenceIndex() - startBoundary.occurrenceIndex() < 1
                    || startBoundary.occurrenceIndex() == range.firstIndex()
                            && !startBoundary.nodeKey().equals(occurrenceKeys.get(0))
                    || endBoundary.occurrenceIndex() == range.lastIndex()
                            && !endBoundary.nodeKey().equals(occurrenceKeys.get(occurrenceKeys.size() - 1))) {
                throw new IllegalArgumentException("Slide interval requires two proved boundaries");
            }
        }

        /** Inclusive engine input range, including both boundary anchors without transferring ownership. */
        public OccurrenceRange traceRange() {
            if (startBoundary.occurrenceIndex() > endBoundary.occurrenceIndex()) {
                throw new IllegalStateException("Slide interval boundaries are reversed");
            }
            return new OccurrenceRange(startBoundary.occurrenceIndex(), endBoundary.occurrenceIndex());
        }
    }

    /** Typed disposition for each selected-way junction occurrence. */
    public record JunctionDisposition(int selectedOccurrenceIndex, PrimitiveKey nodeKey,
            ManualJunctionEligibility.Reason reason, boolean automaticEligible,
            OccurrenceRange provedFootprint) {
        public JunctionDisposition {
            if (selectedOccurrenceIndex < 0 || nodeKey == null || reason == null
                    || automaticEligible != (reason == ManualJunctionEligibility.Reason.SIMPLE_T)
                    || automaticEligible && provedFootprint != null) {
                throw new IllegalArgumentException("Junction disposition is inconsistent");
            }
        }
    }

    /** Immutable authority proof and disjoint occurrence partition. */
    public record Partition(PrimitiveKey selectedWayKey, OccurrenceRange selectedRange,
            List<FixedIsland> fixedIslands, List<SlideInterval> slideIntervals,
            List<JunctionDisposition> junctionDispositions, Set<ExternalPort> provedPorts,
            Map<PrimitiveKey, DetachedPrimitive> parityPrimitives,
            Map<PrimitiveKey, Set<PrimitiveKey>> parityIncomingReferrers,
            String datasetIdentity, long sourceGeneration) {
        public Partition {
            fixedIslands = List.copyOf(fixedIslands);
            slideIntervals = List.copyOf(slideIntervals);
            junctionDispositions = List.copyOf(junctionDispositions);
            provedPorts = Set.copyOf(provedPorts);
            parityPrimitives = Map.copyOf(parityPrimitives);
            Map<PrimitiveKey, Set<PrimitiveKey>> watches = new LinkedHashMap<>();
            parityIncomingReferrers.forEach((key, refs) -> watches.put(key, Set.copyOf(refs)));
            parityIncomingReferrers = Map.copyOf(watches);
            if (selectedWayKey == null || selectedRange == null || datasetIdentity == null
                    || datasetIdentity.isBlank() || sourceGeneration < 0
                    || !parityPrimitives.containsKey(selectedWayKey)) {
                throw new IllegalArgumentException("Selected-way partition lacks its authority identity");
            }
            List<OccurrenceRange> ownership = new ArrayList<>();
            fixedIslands.forEach(island -> ownership.add(island.range()));
            slideIntervals.forEach(interval -> ownership.add(interval.range()));
            ownership.sort(Comparator.comparingInt(OccurrenceRange::firstIndex));
            int next = selectedRange.firstIndex();
            for (OccurrenceRange range : ownership) {
                if (range.firstIndex() != next || range.lastIndex() > selectedRange.lastIndex()) {
                    throw new IllegalArgumentException("Partition ranges must own every occurrence exactly once");
                }
                next = range.lastIndex() + 1;
            }
            if (next != selectedRange.lastIndex() + 1) {
                throw new IllegalArgumentException("Partition does not cover the selected occurrence range");
            }
        }
    }

    private SelectedWayIntervalPartitioner() { }

    /** Builds an occurrence partition from the initial complete authority snapshot only. */
    public static Partition partition(NetworkSnapshot authoritySnapshot,
            NetworkSnapshotCapture.Specification authoritySpecification) {
        if (authoritySnapshot == null || authoritySpecification == null) {
            throw new IllegalArgumentException("Partition authority is required");
        }
        DetachedPrimitive selectedPrimitive = authoritySnapshot.primitives()
                .get(authoritySpecification.selectedWayKey());
        if (authoritySnapshot.role() != SnapshotRole.CAPTURED_BEFORE
                || !authoritySnapshot.datasetIdentity().equals(authoritySpecification.datasetIdentity())
                || authoritySnapshot.sourceGeneration() != authoritySpecification.sourceGeneration()
                || !authoritySnapshot.closure().wayReferrersComplete()
                || !authoritySnapshot.closure().relationReferrersComplete()
                || !authoritySnapshot.closure().nearbyGeometryComplete()
                || !(selectedPrimitive instanceof DetachedWay selected)
                || authoritySpecification.selectedRange().lastIndex() >= selected.nodeKeys().size()) {
            return wholeSelection(authoritySnapshot, authoritySpecification,
                    ManualJunctionEligibility.Reason.INCOMPLETE_CLOSURE, null, -1);
        }
        OccurrenceRange selection = authoritySpecification.selectedRange();
        if (selection.size() > MAXIMUM_PARTITION_OCCURRENCES) {
            return wholeSelection(authoritySnapshot, authoritySpecification,
                    ManualJunctionEligibility.Reason.RESOURCE_LIMIT, selected, -1);
        }
        if (selection.size() < 2) {
            return wholeSelection(authoritySnapshot, authoritySpecification,
                    ManualJunctionEligibility.Reason.INCOMPLETE_ARM, selected, -1);
        }

        List<Integer> junctionOccurrences = new ArrayList<>();
        for (int index = selection.firstIndex(); index <= selection.lastIndex(); index++) {
            PrimitiveKey node = selected.nodeKeys().get(index);
            if (wayReferrers(authoritySnapshot, node).size() > 1) junctionOccurrences.add(index);
        }
        boolean repeatedSelected = new LinkedHashSet<>(selected.nodeKeys()).size() != selected.nodeKeys().size();
        if (repeatedSelected) {
            int representative = junctionOccurrences.isEmpty() ? selection.firstIndex()
                    : junctionOccurrences.get(0);
            return wholeSelection(authoritySnapshot, authoritySpecification,
                    ManualJunctionEligibility.Reason.REPEATED_OCCURRENCE, selected, representative);
        }

        Proof proof = new Proof(authoritySnapshot);
        proof.add(authoritySpecification.selectedWayKey());
        if (!incomingComplete(authoritySnapshot, authoritySpecification.selectedWayKey())) {
            return wholeSelection(authoritySnapshot, authoritySpecification,
                    ManualJunctionEligibility.Reason.INCOMPLETE_CLOSURE, selected, -1, new ArrayList<>(), proof);
        }
        for (int index = selection.firstIndex(); index <= selection.lastIndex(); index++) {
            PrimitiveKey occurrence = selected.nodeKeys().get(index);
            proof.add(occurrence);
            if (!incomingComplete(authoritySnapshot, occurrence)) {
                return wholeSelection(authoritySnapshot, authoritySpecification,
                        ManualJunctionEligibility.Reason.INCOMPLETE_CLOSURE, selected, index,
                        new ArrayList<>(), proof);
            }
        }
        if (proof.resourceExceeded) {
            return wholeSelection(authoritySnapshot, authoritySpecification,
                    ManualJunctionEligibility.Reason.RESOURCE_LIMIT, selected, -1, new ArrayList<>(), proof);
        }
        List<JunctionDisposition> dispositions = new ArrayList<>();
        List<IslandDraft> drafts = new ArrayList<>();
        for (int selectedIndex : junctionOccurrences) {
            PrimitiveKey node = selected.nodeKeys().get(selectedIndex);
            if (!proof.charge(authoritySnapshot.primitives().size())) {
                return wholeSelection(authoritySnapshot, authoritySpecification,
                        ManualJunctionEligibility.Reason.RESOURCE_LIMIT, selected, selectedIndex,
                        dispositions, proof);
            }
            ManualJunctionEligibility.Decision decision = classify(authoritySnapshot,
                    authoritySpecification, selected, selectedIndex);
            ManualJunctionEligibility.Reason reason = decision.reason();
            if (reason == ManualJunctionEligibility.Reason.SIMPLE_T) {
                if (proof.resourceExceeded) {
                    return wholeSelection(authoritySnapshot, authoritySpecification,
                            ManualJunctionEligibility.Reason.RESOURCE_LIMIT, selected, selectedIndex,
                            dispositions, proof);
                }
                proof.add(node);
                proof.addAll(decision.affectedNodes());
                Set<PrimitiveKey> incident = wayReferrers(authoritySnapshot, node);
                incident.forEach(proof::add);
                decision.affectedNodes().forEach(affected ->
                        wayReferrers(authoritySnapshot, affected).forEach(proof::add));
                incident.forEach(way -> addPorts(authoritySnapshot, way, proof));
                if (proof.resourceExceeded) {
                    return wholeSelection(authoritySnapshot, authoritySpecification,
                            ManualJunctionEligibility.Reason.RESOURCE_LIMIT, selected,
                            selectedIndex, dispositions, proof);
                }
                if (proof.authorityIncomplete) {
                    dispositions.add(new JunctionDisposition(selectedIndex, node,
                            ManualJunctionEligibility.Reason.INCOMPLETE_CLOSURE, false, selection));
                    return wholeSelection(authoritySnapshot, authoritySpecification,
                            ManualJunctionEligibility.Reason.INCOMPLETE_CLOSURE, selected,
                            selectedIndex, dispositions, proof);
                }
                dispositions.add(new JunctionDisposition(selectedIndex, node, reason, true, null));
                continue;
            }
            ManualFootprint footprint = proveFootprint(authoritySnapshot,
                    authoritySpecification, selected, selectedIndex, proof);
            if (footprint == null) {
                ManualJunctionEligibility.Reason refusal = proof.resourceExceeded
                        ? ManualJunctionEligibility.Reason.RESOURCE_LIMIT
                        : ManualJunctionEligibility.Reason.INCOMPLETE_CLOSURE;
                dispositions.add(new JunctionDisposition(selectedIndex, node,
                        refusal, false, selection));
                return wholeSelection(authoritySnapshot, authoritySpecification,
                        refusal, selected, selectedIndex,
                        dispositions, proof);
            }
            dispositions.add(new JunctionDisposition(selectedIndex, node, reason, false,
                    footprint.selectedRange()));
            drafts.add(new IslandDraft(footprint.selectedRange().firstIndex(),
                    footprint.selectedRange().lastIndex(), new LinkedHashSet<>(Set.of(node)),
                    new LinkedHashSet<>(Set.of(reason)), footprint.proofKeys(), footprint.ports()));
        }

        List<IslandDraft> islands = unionDrafts(drafts);
        List<FixedIsland> fixed = materializeIslands(islands, selected, selection);
        List<SlideInterval> slides = materializeSlides(authoritySnapshot, authoritySpecification,
                selected, fixed, dispositions);
        return new Partition(selected.key(), selection, fixed, slides, dispositions,
                proof.ports, proof.primitives, proof.referrers, authoritySnapshot.datasetIdentity(),
                authoritySnapshot.sourceGeneration());
    }

    /** Verifies the authority proof on the read-only recapture without recomputing arm boundaries. */
    public static boolean verifyFrozenParity(Partition partition, NetworkSnapshot frozenSnapshot) {
        if (partition == null || frozenSnapshot == null
                || frozenSnapshot.role() != SnapshotRole.CAPTURED_BEFORE
                || !partition.datasetIdentity().equals(frozenSnapshot.datasetIdentity())
                || partition.sourceGeneration() != frozenSnapshot.sourceGeneration()
                || !frozenSnapshot.closure().wayReferrersComplete()
                || !frozenSnapshot.closure().relationReferrersComplete()
                || !frozenSnapshot.closure().nearbyGeometryComplete()
                || !frozenSnapshot.closure().primitiveKeys().containsAll(partition.parityPrimitives().keySet())) {
            return false;
        }
        for (Map.Entry<PrimitiveKey, DetachedPrimitive> entry : partition.parityPrimitives().entrySet()) {
            if (!entry.getValue().equals(frozenSnapshot.primitives().get(entry.getKey()))) return false;
        }
        for (Map.Entry<PrimitiveKey, Set<PrimitiveKey>> entry
                : partition.parityIncomingReferrers().entrySet()) {
            if (!entry.getValue().equals(frozenSnapshot.incomingReferrerWatches().get(entry.getKey()))) {
                return false;
            }
        }
        return frozenSnapshot.closure().externalPorts().containsAll(partition.provedPorts());
    }

    private static ManualJunctionEligibility.Decision classify(NetworkSnapshot snapshot,
            NetworkSnapshotCapture.Specification specification, DetachedWay selected, int index) {
        PrimitiveKey node = selected.nodeKeys().get(index);
        if (!incomingComplete(snapshot, node)) return decision(
                ManualJunctionEligibility.Reason.INCOMPLETE_CLOSURE, node);
        if (index != specification.selectedRange().firstIndex()
                && index != specification.selectedRange().lastIndex()) {
            return decision(ManualJunctionEligibility.Reason.SELECTED_INTERIOR, node);
        }
        if (snapshot.primitives().get(node) instanceof DetachedNode detached
                && !detached.tags().isEmpty()) {
            return decision(ManualJunctionEligibility.Reason.AFFECTED_NODE_TAGGED, node);
        }
        if (hasRelationReferrer(snapshot, node)) {
            return decision(ManualJunctionEligibility.Reason.AFFECTED_NODE_RELATION, node);
        }
        Set<PrimitiveKey> incidentWays = wayReferrers(snapshot, node);
        if (!incomingComplete(snapshot, selected.key())
                || incidentWays.stream().anyMatch(way -> !incomingComplete(snapshot, way))) {
            return decision(ManualJunctionEligibility.Reason.INCOMPLETE_CLOSURE, node);
        }
        if (hasRelationReferrer(snapshot, selected.key())
                || incidentWays.stream().anyMatch(way -> hasRelationReferrer(snapshot, way))) {
            return decision(ManualJunctionEligibility.Reason.PARTICIPATING_RELATION, node);
        }
        OccurrenceRange range;
        if (index == specification.selectedRange().firstIndex()) {
            if (index == specification.selectedRange().lastIndex()) {
                return decision(ManualJunctionEligibility.Reason.SELECTED_INTERIOR, node);
            }
            range = new OccurrenceRange(index, index + 1);
        } else if (index == specification.selectedRange().lastIndex()) {
            range = new OccurrenceRange(index - 1, index);
        } else {
            range = new OccurrenceRange(index, index);
        }
        NetworkSnapshotCapture.Specification local = withSelectedRange(specification, range);
        try {
            return ManualJunctionEligibility.evaluate(snapshot, local);
        } catch (RuntimeException incompleteAuthority) {
            return decision(ManualJunctionEligibility.Reason.INCOMPLETE_CLOSURE, node);
        }
    }

    private static ManualJunctionEligibility.Decision decision(
            ManualJunctionEligibility.Reason reason, PrimitiveKey node) {
        return new ManualJunctionEligibility.Decision(reason, node, null, Set.of(node));
    }

    private static NetworkSnapshotCapture.Specification withSelectedRange(
            NetworkSnapshotCapture.Specification spec, OccurrenceRange selectedRange) {
        return new NetworkSnapshotCapture.Specification(spec.snapshotId(), spec.datasetIdentity(),
                spec.sourceGeneration(), spec.selectedWayKey(), selectedRange, spec.metricFrame(),
                spec.collisionEnvelope(), spec.editRegion(), spec.editableWayOccurrences(),
                spec.editableExistingKeys(), spec.movableExistingNodeKeys(),
                spec.removableExistingNodeKeys(), spec.explicitlyProtectedNodeKeys(),
                spec.mayCreateNodes(), spec.permissions());
    }

    private static ManualFootprint proveFootprint(NetworkSnapshot snapshot,
            NetworkSnapshotCapture.Specification specification, DetachedWay selected,
            int selectedIndex, Proof proof) {
        PrimitiveKey junction = selected.nodeKeys().get(selectedIndex);
        if (!incomingComplete(snapshot, junction)) return null;
        Set<PrimitiveKey> incident = snapshot.incomingReferrerWatches().get(junction).stream()
                .filter(key -> key.type() == PrimitiveKey.Type.WAY).collect(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!incident.contains(selected.key()) || incident.isEmpty()) return null;
        int selectedLow = selectedIndex;
        int selectedHigh = selectedIndex;
        Set<PrimitiveKey> localProofKeys = new LinkedHashSet<>();
        Set<ExternalPort> localPorts = new LinkedHashSet<>();
        localProofKeys.add(junction);
        for (PrimitiveKey referrer : snapshot.incomingReferrerWatches().get(junction)) {
            if (!snapshot.primitives().containsKey(referrer)) return null;
            proof.add(referrer);
            if (referrer.type() == PrimitiveKey.Type.RELATION) localProofKeys.add(referrer);
            if (referrer.type() != PrimitiveKey.Type.WAY && referrer.type() != PrimitiveKey.Type.RELATION) {
                return null;
            }
        }
        for (PrimitiveKey wayKey : incident) {
            if (!(snapshot.primitives().get(wayKey) instanceof DetachedWay way)) return null;
            if (!incomingComplete(snapshot, wayKey)) return null;
            List<Integer> matches = new ArrayList<>();
            for (int occurrence = 0; occurrence < way.nodeKeys().size(); occurrence++) {
                if (!proof.charge(1)) return null;
                if (way.nodeKeys().get(occurrence).equals(junction)) matches.add(occurrence);
            }
            if (matches.size() != 1) return null;
            int at = matches.get(0);
            proof.add(wayKey);
            localProofKeys.add(wayKey);
            addPorts(snapshot, wayKey, proof);
            snapshot.closure().externalPorts().stream().filter(port -> port.wayKey().equals(wayKey))
                    .forEach(localPorts::add);
            for (int direction : new int[] {-1, 1}) {
                ArmProof arm = proveArm(snapshot, specification, way, at, direction, proof);
                if (arm == null) return null;
                localProofKeys.addAll(arm.keys());
                localPorts.addAll(arm.ports());
                if (wayKey.equals(selected.key())) {
                    selectedLow = Math.min(selectedLow, arm.minimumIndex());
                    selectedHigh = Math.max(selectedHigh, arm.maximumIndex());
                }
            }
        }
        OccurrenceRange selectedRange = specification.selectedRange();
        int first = Math.max(selectedRange.firstIndex(), selectedLow);
        int last = Math.min(selectedRange.lastIndex(), selectedHigh);
        if (first > last || selectedIndex < first || selectedIndex > last
                || proof.authorityIncomplete || proof.resourceExceeded) return null;
        proof.addAll(localProofKeys);
        proof.ports.addAll(localPorts);
        return new ManualFootprint(new OccurrenceRange(first, last), localProofKeys, localPorts);
    }

    private static ArmProof proveArm(NetworkSnapshot snapshot,
            NetworkSnapshotCapture.Specification specification, DetachedWay way,
            int junctionIndex, int direction, Proof proof) {
        double distance = 0.0;
        int index = junctionIndex;
        int minimum = junctionIndex;
        int maximum = junctionIndex;
        Set<PrimitiveKey> keys = new LinkedHashSet<>();
        Set<ExternalPort> ports = new LinkedHashSet<>();
        PrimitiveKey start = way.nodeKeys().get(junctionIndex);
        keys.add(start);
        proof.add(start);
        if (!incomingComplete(snapshot, start)) return null;
        for (int steps = 0; steps < MAXIMUM_PARTITION_OCCURRENCES; steps++) {
            if (!proof.charge(1)) return null;
            int next = index + direction;
            if (next < 0 || next >= way.nodeKeys().size()) {
                return distance > 0.0 ? new ArmProof(minimum, maximum, keys, ports) : null;
            }
            PrimitiveKey currentKey = way.nodeKeys().get(index);
            PrimitiveKey nextKey = way.nodeKeys().get(next);
            if (!(snapshot.primitives().get(currentKey) instanceof DetachedNode current)
                    || !(snapshot.primitives().get(nextKey) instanceof DetachedNode nextNode)
                    || !incomingComplete(snapshot, nextKey)) return null;
            double segment;
            try {
                segment = specification.metricFrame().toMetric(current.coordinate()).distanceTo(
                        specification.metricFrame().toMetric(nextNode.coordinate()));
            } catch (IllegalArgumentException outsideCertifiedFrame) {
                return null;
            }
            if (!Double.isFinite(segment) || segment <= 0.0 || distance + segment > MAXIMUM_PORT_METERS) {
                return null;
            }
            distance += segment;
            index = next;
            minimum = Math.min(minimum, index);
            maximum = Math.max(maximum, index);
            keys.add(nextKey);
            proof.add(nextKey);
            List<ExternalPort> boundaryPorts = portsAt(snapshot, way.key(), nextKey, next, direction);
            if (distance >= NORMAL_PORT_METERS && !boundaryPorts.isEmpty()) {
                for (ExternalPort port : boundaryPorts) {
                    ports.add(port);
                    proof.addPort(port);
                }
                if (proof.authorityIncomplete) return null;
                return new ArmProof(minimum, maximum, keys, ports);
            }
            if (index == 0 || index == way.nodeKeys().size() - 1) {
                return new ArmProof(minimum, maximum, keys, ports);
            }
            if (distance >= NORMAL_PORT_METERS && reliableBoundary(snapshot, specification, way, nextKey)) {
                return new ArmProof(minimum, maximum, keys, ports);
            }
        }
        return null;
    }

    private static boolean reliableBoundary(NetworkSnapshot snapshot,
            NetworkSnapshotCapture.Specification specification, DetachedWay way, PrimitiveKey node) {
        if (!(snapshot.primitives().get(node) instanceof DetachedNode detached)) return false;
        return !detached.tags().isEmpty() || hasRelationReferrer(snapshot, node)
                || way.key().equals(specification.selectedWayKey())
                        && snapshot.closure().protectedExistingNodeKeys().contains(node)
                || wayReferrers(snapshot, node).stream().anyMatch(referrer -> !referrer.equals(way.key()));
    }

    private static List<ExternalPort> portsAt(NetworkSnapshot snapshot, PrimitiveKey way,
            PrimitiveKey node, int occurrence, int direction) {
        ExternalPort.Side side = direction < 0 ? ExternalPort.Side.BEFORE : ExternalPort.Side.AFTER;
        return snapshot.closure().externalPorts().stream().filter(port -> port.wayKey().equals(way)
                && port.boundaryNodeKey().equals(node) && port.boundaryOccurrenceIndex() == occurrence
                && port.side() == side).toList();
    }

    private static boolean incomingComplete(NetworkSnapshot snapshot, PrimitiveKey target) {
        Set<PrimitiveKey> referrers = snapshot.incomingReferrerWatches().get(target);
        return referrers != null && referrers.stream().allMatch(snapshot.primitives()::containsKey);
    }

    private static Set<PrimitiveKey> wayReferrers(NetworkSnapshot snapshot, PrimitiveKey node) {
        return snapshot.incomingReferrerWatches().getOrDefault(node, Set.of()).stream()
                .filter(key -> key.type() == PrimitiveKey.Type.WAY)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static boolean hasRelationReferrer(NetworkSnapshot snapshot, PrimitiveKey key) {
        return snapshot.incomingReferrerWatches().getOrDefault(key, Set.of()).stream()
                .anyMatch(referrer -> referrer.type() == PrimitiveKey.Type.RELATION);
    }

    private static void addPorts(NetworkSnapshot snapshot, PrimitiveKey way, Proof proof) {
        snapshot.closure().externalPorts().stream().filter(port -> port.wayKey().equals(way))
                .forEach(proof::addPort);
    }

    private static List<IslandDraft> unionDrafts(List<IslandDraft> source) {
        List<IslandDraft> sorted = source.stream().sorted(Comparator.comparingInt(IslandDraft::first))
                .toList();
        List<IslandDraft> result = new ArrayList<>();
        for (IslandDraft next : sorted) {
            if (result.isEmpty() || next.first() > result.get(result.size() - 1).last() + 1) {
                result.add(next.copy());
            } else {
                IslandDraft prior = result.get(result.size() - 1);
                prior.last = Math.max(prior.last, next.last());
                prior.junctions.addAll(next.junctions);
                prior.reasons.addAll(next.reasons);
                prior.proofKeys.addAll(next.proofKeys);
                prior.ports.addAll(next.ports);
            }
        }
        return result;
    }

    private static List<FixedIsland> materializeIslands(List<IslandDraft> drafts,
            DetachedWay selected, OccurrenceRange selection) {
        List<FixedIsland> result = new ArrayList<>();
        for (IslandDraft draft : drafts) {
            int first = Math.max(selection.firstIndex(), draft.first);
            int last = Math.min(selection.lastIndex(), draft.last);
            if (first > last) continue;
            OccurrenceRange range = new OccurrenceRange(first, last);
            List<PrimitiveKey> keys = selected.nodeKeys().subList(first, last + 1);
            ManualJunctionEligibility.Reason reason = draft.reasons.stream().sorted().findFirst()
                    .orElse(ManualJunctionEligibility.Reason.INCOMPLETE_CLOSURE);
            List<ManualJunctionEligibility.Reason> reasons = draft.reasons.stream().sorted().toList();
            result.add(new FixedIsland(range, keys, draft.junctions, reasons,
                    fixedBoundary(first, selected, reason), fixedBoundary(last, selected, reason),
                    draft.proofKeys, draft.ports));
        }
        return List.copyOf(result);
    }

    private static List<SlideInterval> materializeSlides(NetworkSnapshot snapshot,
            NetworkSnapshotCapture.Specification specification, DetachedWay selected,
            List<FixedIsland> fixed, List<JunctionDisposition> dispositions) {
        List<SlideInterval> result = new ArrayList<>();
        int cursor = specification.selectedRange().firstIndex();
        FixedIsland priorIsland = null;
        for (FixedIsland island : fixed) {
            if (cursor < island.range().firstIndex()) {
                addSlide(result, snapshot, specification, selected, dispositions, cursor,
                        island.range().firstIndex() - 1,
                        priorIsland == null ? boundaryBefore(snapshot, specification, selected,
                                dispositions, specification.selectedRange().firstIndex())
                                : fixedBoundary(priorIsland.range().lastIndex(), selected,
                                        priorIsland.reasons().get(0)),
                        fixedBoundary(island.range().firstIndex(), selected,
                                island.reasons().get(0)));
            }
            cursor = Math.max(cursor, island.range().lastIndex() + 1);
            priorIsland = island;
        }
        if (cursor <= specification.selectedRange().lastIndex()) {
            addSlide(result, snapshot, specification, selected, dispositions, cursor,
                    specification.selectedRange().lastIndex(),
                    priorIsland == null ? boundaryBefore(snapshot, specification, selected,
                            dispositions, specification.selectedRange().firstIndex())
                            : fixedBoundary(priorIsland.range().lastIndex(), selected,
                                    priorIsland.reasons().get(0)),
                    boundaryAfter(snapshot, specification, selected, dispositions,
                            specification.selectedRange().lastIndex()));
        }
        return List.copyOf(result);
    }

    private static void addSlide(List<SlideInterval> result, NetworkSnapshot snapshot,
            NetworkSnapshotCapture.Specification specification, DetachedWay selected,
            List<JunctionDisposition> dispositions, int first, int last,
            BoundaryConstraint start, BoundaryConstraint end) {
        if (first > last) return;
        result.add(new SlideInterval(new OccurrenceRange(first, last),
                selected.nodeKeys().subList(first, last + 1), start, end));
    }

    private static BoundaryConstraint boundaryBefore(NetworkSnapshot snapshot,
            NetworkSnapshotCapture.Specification spec, DetachedWay selected,
            List<JunctionDisposition> dispositions, int index) {
        return endpointBoundary(snapshot, spec, selected, dispositions, index);
    }

    private static BoundaryConstraint boundaryAfter(NetworkSnapshot snapshot,
            NetworkSnapshotCapture.Specification spec, DetachedWay selected,
            List<JunctionDisposition> dispositions, int index) {
        return endpointBoundary(snapshot, spec, selected, dispositions, index);
    }

    private static BoundaryConstraint endpointBoundary(NetworkSnapshot snapshot,
            NetworkSnapshotCapture.Specification spec, DetachedWay selected,
            List<JunctionDisposition> dispositions, int index) {
        PrimitiveKey node = selected.nodeKeys().get(index);
        JunctionDisposition simple = dispositions.stream().filter(value -> value.selectedOccurrenceIndex() == index
                && value.automaticEligible()).findFirst().orElse(null);
        if (simple != null) {
            return new BoundaryConstraint(BoundaryKind.ELIGIBLE_SIMPLE_T, index, node,
                    snapshot.closure().movableExistingNodeKeys().contains(node), true, simple.reason());
        }
        boolean endpoint = index == spec.selectedRange().firstIndex()
                || index == spec.selectedRange().lastIndex();
        return new BoundaryConstraint(endpoint ? BoundaryKind.SELECTED_ENDPOINT : BoundaryKind.FIXED_ISLAND,
                index, node, snapshot.closure().movableExistingNodeKeys().contains(node), false,
                ManualJunctionEligibility.Reason.NO_JUNCTION);
    }

    private static BoundaryConstraint fixedBoundary(int index, DetachedWay selected,
            ManualJunctionEligibility.Reason reason) {
        return new BoundaryConstraint(BoundaryKind.FIXED_ISLAND, index, selected.nodeKeys().get(index),
                false, false, reason);
    }

    private static Partition wholeSelection(NetworkSnapshot snapshot,
            NetworkSnapshotCapture.Specification specification,
            ManualJunctionEligibility.Reason reason, DetachedWay selected, int representative) {
        return wholeSelection(snapshot, specification, reason, selected, representative,
                new ArrayList<>(), new Proof(snapshot));
    }

    private static Partition wholeSelection(NetworkSnapshot snapshot,
            NetworkSnapshotCapture.Specification specification,
            ManualJunctionEligibility.Reason reason, DetachedWay selected, int representative,
            List<JunctionDisposition> dispositions, Proof proof) {
        if (selected == null || specification.selectedRange().lastIndex() >= selected.nodeKeys().size()) {
            throw new IllegalArgumentException("Cannot partition an incomplete selected-way authority");
        }
        OccurrenceRange range = specification.selectedRange();
        proof.add(selected.key());
        for (int index = range.firstIndex(); index <= range.lastIndex(); index++) {
            PrimitiveKey node = selected.nodeKeys().get(index);
            proof.add(node);
            if (wayReferrers(snapshot, node).size() > 1) {
                dispositions.add(new JunctionDisposition(index, node, reason, false, range));
            }
        }
        if (representative >= range.firstIndex() && representative <= range.lastIndex()
                && dispositions.stream().noneMatch(d -> d.selectedOccurrenceIndex() == representative)) {
            PrimitiveKey node = selected.nodeKeys().get(representative);
            dispositions.add(new JunctionDisposition(representative, node, reason, false, range));
        }
        FixedIsland island = new FixedIsland(range,
                selected.nodeKeys().subList(range.firstIndex(), range.lastIndex() + 1),
                dispositions.stream().map(JunctionDisposition::nodeKey)
                        .collect(java.util.stream.Collectors.toSet()), List.of(reason),
                fixedBoundary(range.firstIndex(), selected, reason),
                fixedBoundary(range.lastIndex(), selected, reason), proof.primitives.keySet(), proof.ports);
        dispositions.sort(Comparator.comparingInt(JunctionDisposition::selectedOccurrenceIndex));
        return new Partition(selected.key(), range, List.of(island), List.of(), dispositions,
                proof.ports, proof.primitives, proof.referrers, snapshot.datasetIdentity(),
                snapshot.sourceGeneration());
    }

    private static final class Proof {
        private final NetworkSnapshot snapshot;
        private final Map<PrimitiveKey, DetachedPrimitive> primitives = new LinkedHashMap<>();
        private final Map<PrimitiveKey, Set<PrimitiveKey>> referrers = new LinkedHashMap<>();
        private final Set<ExternalPort> ports = new LinkedHashSet<>();
        private long work;
        private boolean resourceExceeded;
        private boolean authorityIncomplete;

        Proof(NetworkSnapshot snapshot) { this.snapshot = snapshot; }

        void add(PrimitiveKey key) {
            DetachedPrimitive primitive = snapshot.primitives().get(key);
            if (primitive != null) primitives.put(key, primitive);
            Set<PrimitiveKey> watched = snapshot.incomingReferrerWatches().get(key);
            if (watched != null) {
                if (!charge(watched.size())) return;
                referrers.put(key, Set.copyOf(watched));
                for (PrimitiveKey referrer : watched) {
                    DetachedPrimitive payload = snapshot.primitives().get(referrer);
                    if (payload != null) {
                        primitives.put(referrer, payload);
                        Set<PrimitiveKey> incoming = snapshot.incomingReferrerWatches().get(referrer);
                        if (incoming != null) {
                            if (!charge(incoming.size())) return;
                            referrers.put(referrer, Set.copyOf(incoming));
                        }
                    }
                }
            }
        }

        void addAll(Set<PrimitiveKey> keys) { keys.forEach(this::add); }

        void addPort(ExternalPort port) {
            ports.add(port);
            add(port.wayKey());
            add(port.boundaryNodeKey());
            add(port.outsideNeighborKey());
            if (!incomingComplete(snapshot, port.wayKey())
                    || !incomingComplete(snapshot, port.boundaryNodeKey())
                    || !incomingComplete(snapshot, port.outsideNeighborKey())) {
                authorityIncomplete = true;
            }
        }

        boolean charge(long amount) {
            if (amount < 0 || amount > MAXIMUM_PARTITION_OCCURRENCES - work) {
                resourceExceeded = true;
                return false;
            }
            work += amount;
            return true;
        }
    }

    private record ManualFootprint(OccurrenceRange selectedRange,
            Set<PrimitiveKey> proofKeys, Set<ExternalPort> ports) { }

    private record ArmProof(int minimumIndex, int maximumIndex,
            Set<PrimitiveKey> keys, Set<ExternalPort> ports) { }

    private static final class IslandDraft {
        private int first;
        private int last;
        private final Set<PrimitiveKey> junctions;
        private final Set<ManualJunctionEligibility.Reason> reasons;
        private final Set<PrimitiveKey> proofKeys;
        private final Set<ExternalPort> ports;
        IslandDraft(int first, int last, Set<PrimitiveKey> junctions,
                Set<ManualJunctionEligibility.Reason> reasons, Set<PrimitiveKey> proofKeys,
                Set<ExternalPort> ports) {
            this.first = first;
            this.last = last;
            this.junctions = junctions;
            this.reasons = reasons;
            this.proofKeys = proofKeys;
            this.ports = ports;
        }
        int first() { return first; }
        int last() { return last; }
        IslandDraft copy() {
            return new IslandDraft(first, last, new LinkedHashSet<>(junctions),
                    new LinkedHashSet<>(reasons), new LinkedHashSet<>(proofKeys),
                    new LinkedHashSet<>(ports));
        }
    }
}
