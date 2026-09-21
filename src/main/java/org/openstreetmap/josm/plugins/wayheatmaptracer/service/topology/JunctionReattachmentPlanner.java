package org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Id;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.IdentityNamespace;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Node;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Point;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.PrimitiveType;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Relation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.RelationMember;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Way;

/** Pure planner for incidence-preserving junction relocation and local receiver reconstruction. */
public final class JunctionReattachmentPlanner {
    private static final double GEOMETRY_EPSILON = 1e-8;
    private static final int MAX_COUPLED_JUNCTIONS = 8;
    private static final int MAX_INCIDENT_WAYS = 12;
    private static final int MAX_AFFECTED_PRIMITIVES = 4_096;

    /** Whether receiver geometry is frozen or all incident approaches may be reconstructed. */
    public enum ReceiverPolicy { FROZEN_LOCUS, RECONSTRUCT_INCIDENT }

    /** Explicit interpretation of tags whose meaning may be tied to the old coordinate. */
    public enum LocationFeatureDecision {
        MOVE_WITH_JUNCTION,
        RETAIN_AT_OLD_LOCATION
    }

    /** Stable typed reasons for refusing a topology-changing proposal. */
    public enum FindingCode {
        RELOCATION_PERMISSION_REQUIRED,
        RECONSTRUCTION_PERMISSION_REQUIRED,
        INVALID_CANDIDATE,
        EDIT_REGION_VIOLATION,
        WRITE_CLOSURE_INCOMPLETE,
        AMBIGUOUS_NODE_OCCURRENCE,
        RELOCATION_LIMIT_EXCEEDED,
        RECEIVER_LOCUS_MISS,
        UNSUPPORTED_SPLIT_RECEIVER,
        SEMANTIC_BOUNDARY_RELOCATION_UNSUPPORTED,
        COUPLED_ORDER_INVERSION,
        LOCATION_FEATURE_DECISION_REQUIRED,
        EXISTING_NODE_COLLISION,
        INCOMPLETE_RELATION,
        UNSUPPORTED_RELATION,
        RELATION_SEMANTICS_INVALID,
        AREA_OR_CLOSED_WAY_UNSUPPORTED,
        UNSAFE_NODE_REMOVAL,
        TOPOLOGY_BUDGET_EXCEEDED,
        UNCONNECTED_AT_GRADE_CROSSING
    }

    /** Axis-aligned edit authorization in the planner's local metric frame. */
    public record Bounds(double minX, double minY, double maxX, double maxY) {
        public Bounds {
            if (!Double.isFinite(minX) || !Double.isFinite(minY)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY)
                || minX > maxX || minY > maxY) {
                throw new IllegalArgumentException("Invalid metric edit bounds");
            }
        }

        /** Returns whether a point is inside or on the authorized boundary. */
        public boolean contains(Point point) {
            return point.xMeters() >= minX && point.xMeters() <= maxX
                && point.yMeters() >= minY && point.yMeters() <= maxY;
        }
    }

    /** Independent topology permissions and relocation distance limit. */
    public record Permissions(boolean relocateExistingJunctions,
        boolean reconstructIncidentWayGeometry, double maximumRelocationMeters) {
        public Permissions {
            if (!Double.isFinite(maximumRelocationMeters) || maximumRelocationMeters <= 0.0) {
                throw new IllegalArgumentException("Maximum junction relocation must be positive");
            }
        }

        /** Uses the v0.22 initial 20 m relocation bound. */
        public Permissions(boolean relocateExistingJunctions, boolean reconstructIncidentWayGeometry) {
            this(relocateExistingJunctions, reconstructIncidentWayGeometry, 20.0);
        }
    }

    /** Ordered set of one frozen receiver way or two ways split at the old junction. */
    public record ReceiverGroup(List<Id> wayIds) {
        public ReceiverGroup {
            Objects.requireNonNull(wayIds, "wayIds");
            if (wayIds.isEmpty() || wayIds.size() > 2
                || wayIds.stream().anyMatch(id -> id == null || id.type() != PrimitiveType.WAY)
                || new HashSet<>(wayIds).size() != wayIds.size()) {
                throw new IllegalArgumentException("Receiver groups require one or two distinct way identities");
            }
            wayIds = List.copyOf(wayIds);
        }
    }

    /** One proposed junction position and the branch/receiver identities it constrains. */
    public record JunctionCandidate(Id originalJunctionNodeId, Point proposedPosition,
        Id selectedWayId, List<ReceiverGroup> receiverGroups, ReceiverPolicy receiverPolicy,
        double localizationToleranceMeters) {
        public JunctionCandidate {
            requireType(originalJunctionNodeId, PrimitiveType.NODE, "junction");
            Objects.requireNonNull(proposedPosition, "proposedPosition");
            requireType(selectedWayId, PrimitiveType.WAY, "selected way");
            Objects.requireNonNull(receiverGroups, "receiverGroups");
            receiverGroups = List.copyOf(receiverGroups);
            Objects.requireNonNull(receiverPolicy, "receiverPolicy");
            if (!Double.isFinite(localizationToleranceMeters) || localizationToleranceMeters < 0.0) {
                throw new IllegalArgumentException("Localization tolerance must be finite and nonnegative");
            }
            if (receiverPolicy == ReceiverPolicy.FROZEN_LOCUS && receiverGroups.isEmpty()) {
                throw new IllegalArgumentException("Frozen-locus relocation requires a receiving locus");
            }
        }
    }

    /** Complete immutable input to one side-effect-free planning attempt. */
    public record ReattachmentRequest(TopologyNetwork before, List<JunctionCandidate> candidates,
        Set<Id> editableWayIds, Bounds editRegion, Permissions permissions,
        Map<Id, LocationFeatureDecision> locationFeatureDecisions,
        Set<Id> approvedRelationNodeRemaps, double existingNodeCollisionToleranceMeters) {
        public ReattachmentRequest {
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(candidates, "candidates");
            if (candidates.isEmpty() || candidates.size() > MAX_COUPLED_JUNCTIONS) {
                throw new IllegalArgumentException("A request requires one to eight coupled junctions");
            }
            candidates = List.copyOf(candidates);
            Objects.requireNonNull(editableWayIds, "editableWayIds");
            if (editableWayIds.stream().anyMatch(id -> id == null || id.type() != PrimitiveType.WAY)) {
                throw new IllegalArgumentException("Editable identities must be ways");
            }
            editableWayIds = Set.copyOf(editableWayIds);
            Objects.requireNonNull(editRegion, "editRegion");
            Objects.requireNonNull(permissions, "permissions");
            Objects.requireNonNull(locationFeatureDecisions, "locationFeatureDecisions");
            if (locationFeatureDecisions.keySet().stream()
                .anyMatch(id -> id == null || id.type() != PrimitiveType.NODE)) {
                throw new IllegalArgumentException("Location decisions must target nodes");
            }
            locationFeatureDecisions = Map.copyOf(locationFeatureDecisions);
            Objects.requireNonNull(approvedRelationNodeRemaps, "approvedRelationNodeRemaps");
            if (approvedRelationNodeRemaps.stream()
                .anyMatch(id -> id == null || id.type() != PrimitiveType.NODE)) {
                throw new IllegalArgumentException("Relation remap approvals must target nodes");
            }
            approvedRelationNodeRemaps = Set.copyOf(approvedRelationNodeRemaps);
            if (!Double.isFinite(existingNodeCollisionToleranceMeters)
                || existingNodeCollisionToleranceMeters < 0.0) {
                throw new IllegalArgumentException("Collision tolerance must be finite and nonnegative");
            }
        }
    }

    /** One typed planning refusal. */
    public record Finding(FindingCode code, String detail) {
        public Finding {
            Objects.requireNonNull(code, "code");
            if (detail == null || detail.isBlank()) {
                throw new IllegalArgumentException("A finding requires bounded explanatory detail");
            }
        }
    }

    /** Immutable before/after graph and identity accounting produced by this planner. */
    public record TopologyEditPlan(TopologyNetwork before, TopologyNetwork after,
        Map<Id, Id> junctionIdentities, Map<Id, List<Id>> retainedShapeNodes,
        Set<Id> changedPrimitiveIds) {
        public TopologyEditPlan {
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
            junctionIdentities = Map.copyOf(junctionIdentities);
            Map<Id, List<Id>> shapeCopy = new LinkedHashMap<>();
            retainedShapeNodes.forEach((way, nodes) -> shapeCopy.put(way, List.copyOf(nodes)));
            retainedShapeNodes = Map.copyOf(shapeCopy);
            changedPrimitiveIds = Set.copyOf(changedPrimitiveIds);
        }
    }

    /** Accepted plan or complete typed refusal list. */
    public record PlanningResult(Optional<TopologyEditPlan> plan, List<Finding> findings) {
        public PlanningResult {
            Objects.requireNonNull(plan, "plan");
            findings = List.copyOf(findings);
            if (plan.isPresent() == !findings.isEmpty()) {
                throw new IllegalArgumentException("A result must contain either a plan or findings");
            }
        }

        /** Returns whether a complete immutable plan was produced. */
        public boolean accepted() {
            return plan.isPresent();
        }

        /** Returns whether the refusal list contains a specific typed reason. */
        public boolean hasFinding(FindingCode code) {
            return findings.stream().anyMatch(finding -> finding.code() == code);
        }
    }

    /** Plans all coupled junctions as one immutable before/after network. */
    public PlanningResult plan(ReattachmentRequest request) {
        Objects.requireNonNull(request, "request");
        Optional<Finding> eligibilityFailure = validateEligibility(request);
        if (eligibilityFailure.isPresent()) {
            return blocked(eligibilityFailure.get());
        }

        TopologyNetwork before = request.before();
        IdAllocator allocator = new IdAllocator(before.nodes().keySet());
        Map<Id, CandidateState> states = createCandidateStates(request, allocator);
        Optional<Finding> collisionFailure = validateExistingNodeCollisions(request, states);
        if (collisionFailure.isPresent()) {
            return blocked(collisionFailure.get());
        }

        Map<Id, Node> nodes = new LinkedHashMap<>(before.nodes());
        Map<Id, Way> ways = new LinkedHashMap<>(before.ways());
        Map<Id, Relation> relations = new LinkedHashMap<>(before.relations());
        Map<Id, List<Id>> retainedShapes = new LinkedHashMap<>();
        for (CandidateState state : states.values()) {
            if (!state.finalNodeId.equals(state.candidate.originalJunctionNodeId())) {
                nodes.put(state.finalNodeId, new Node(state.finalNodeId, state.target, Map.of()));
            }
        }

        Map<ReceiverGroup, List<CandidateState>> frozenGroups = frozenGroups(states.values());
        Set<Id> frozenReceiverWays = new LinkedHashSet<>();
        for (Map.Entry<ReceiverGroup, List<CandidateState>> entry : frozenGroups.entrySet()) {
            frozenReceiverWays.addAll(entry.getKey().wayIds());
            Optional<Finding> failure = entry.getKey().wayIds().size() == 1
                ? rebuildSingleReceiver(before, nodes, ways, entry.getKey().wayIds().get(0),
                    entry.getValue(), retainedShapes, allocator)
                : rebuildSplitReceiver(before, nodes, ways, entry.getKey(), entry.getValue(),
                    retainedShapes, allocator);
            if (failure.isPresent()) {
                return blocked(failure.get());
            }
        }

        for (CandidateState state : states.values()) {
            if (state.candidate.receiverPolicy() == ReceiverPolicy.RECONSTRUCT_INCIDENT) {
                putNodeAtTarget(nodes, state);
            }
        }
        replaceJunctionOccurrences(before, ways, states, frozenReceiverWays);
        applyApprovedRelationRemaps(relations, states, request.approvedRelationNodeRemaps());

        TopologyNetwork after = TopologyNetwork.fromMaps(nodes, ways, relations);
        Set<Id> changed = changedPrimitiveIds(before, after);
        List<Finding> relationFindings = RelationSafetyValidator.validate(before, after, changed,
            effectiveNodeRemaps(states), request.approvedRelationNodeRemaps());
        if (!relationFindings.isEmpty()) {
            return new PlanningResult(Optional.empty(), relationFindings);
        }
        Optional<Finding> crossingFailure = validateNewAtGradeCrossings(before, after, changed);
        if (crossingFailure.isPresent()) {
            return blocked(crossingFailure.get());
        }

        Map<Id, Id> identities = new LinkedHashMap<>();
        states.values().stream().sorted(Comparator.comparing(state -> state.candidate.originalJunctionNodeId()))
            .forEach(state -> identities.put(state.candidate.originalJunctionNodeId(), state.finalNodeId));
        return new PlanningResult(Optional.of(new TopologyEditPlan(before, after, identities,
            retainedShapes, changed)), List.of());
    }

    /**
     * Revalidates one complete proposed component against its true captured before-state.
     *
     * <p>This seam is used when an engine-owned selected route is combined with the topology
     * planner's receiver edits. It deliberately recomputes changed identities from the supplied
     * before-state so selected-route crossings cannot be mistaken for pre-existing defects.</p>
     *
     * @param before exact captured component before any route or topology edit
     * @param after complete combined proposal
     * @return typed fail-closed findings, or an empty list when the component remains valid
     */
    public List<Finding> validateWholeComponent(TopologyNetwork before, TopologyNetwork after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Set<Id> changed = changedPrimitiveIds(before, after);
        List<Finding> findings = new ArrayList<>(RelationSafetyValidator.validate(before, after,
                changed, Map.of(), Set.of()));
        validateNewAtGradeCrossings(before, after, changed).ifPresent(findings::add);
        return List.copyOf(findings);
    }

    private static Optional<Finding> validateEligibility(ReattachmentRequest request) {
        if (!request.permissions().relocateExistingJunctions()) {
            return finding(FindingCode.RELOCATION_PERMISSION_REQUIRED,
                "Relocating existing junctions was not authorized");
        }
        Set<Id> seenJunctions = new HashSet<>();
        Set<Id> affectedWayIds = new LinkedHashSet<>();
        int capturedPrimitiveCount = request.before().nodes().size() + request.before().ways().size()
            + request.before().relations().size();
        if (capturedPrimitiveCount > MAX_AFFECTED_PRIMITIVES) {
            return finding(FindingCode.TOPOLOGY_BUDGET_EXCEEDED,
                "Detached junction closure exceeds the 4096-primitive component budget");
        }
        for (JunctionCandidate candidate : request.candidates()) {
            if (!seenJunctions.add(candidate.originalJunctionNodeId())
                || !request.before().nodes().containsKey(candidate.originalJunctionNodeId())
                || !request.before().ways().containsKey(candidate.selectedWayId())) {
                return finding(FindingCode.INVALID_CANDIDATE,
                    "Candidate identities are missing or duplicated: " + candidate.originalJunctionNodeId());
            }
            Node original = request.before().node(candidate.originalJunctionNodeId());
            if (!request.editRegion().contains(original.point())
                || !request.editRegion().contains(candidate.proposedPosition())) {
                return finding(FindingCode.EDIT_REGION_VIOLATION,
                    "Junction movement leaves the authorized edit region: " + candidate.originalJunctionNodeId());
            }
            if (original.point().distance(candidate.proposedPosition())
                > request.permissions().maximumRelocationMeters() + candidate.localizationToleranceMeters()) {
                return finding(FindingCode.RELOCATION_LIMIT_EXCEEDED,
                    "Junction movement exceeds its independent relocation bound: "
                        + candidate.originalJunctionNodeId());
            }
            if (candidate.receiverPolicy() == ReceiverPolicy.RECONSTRUCT_INCIDENT
                && !request.permissions().reconstructIncidentWayGeometry()) {
                return finding(FindingCode.RECONSTRUCTION_PERMISSION_REQUIRED,
                    "Incident-way reconstruction was not authorized");
            }

            List<Way> incidentWays = incidentWays(request.before(), candidate.originalJunctionNodeId());
            incidentWays.stream().map(Way::id).forEach(affectedWayIds::add);
            if (affectedWayIds.size() > MAX_INCIDENT_WAYS) {
                return finding(FindingCode.TOPOLOGY_BUDGET_EXCEEDED,
                    "Coupled junction component exceeds the 12-incident-way budget");
            }
            if (incidentWays.size() < 2
                || incidentWays.stream().noneMatch(way -> way.id().equals(candidate.selectedWayId()))) {
                return finding(FindingCode.INVALID_CANDIDATE,
                    "The proposed node is not a shared junction on the selected way");
            }
            for (Way way : incidentWays) {
                long occurrences = way.nodeIds().stream()
                    .filter(candidate.originalJunctionNodeId()::equals).count();
                if (occurrences != 1) {
                    return finding(FindingCode.AMBIGUOUS_NODE_OCCURRENCE,
                        "Repeated junction occurrence is unsupported: " + way.id());
                }
                if (!request.editableWayIds().contains(way.id())) {
                    return finding(FindingCode.WRITE_CLOSURE_INCOMPLETE,
                        "Every incident way must be explicitly editable: " + way.id());
                }
                if (isAreaOrClosedWay(way)) {
                    return finding(FindingCode.AREA_OR_CLOSED_WAY_UNSUPPORTED,
                        "Area and closed-way topology changes require a dedicated handler: " + way.id());
                }
            }
            for (ReceiverGroup group : candidate.receiverGroups()) {
                for (Id receiverId : group.wayIds()) {
                    if (!request.before().ways().containsKey(receiverId)
                        || !request.editableWayIds().contains(receiverId)
                        || !request.before().way(receiverId).nodeIds()
                            .contains(candidate.originalJunctionNodeId())) {
                        return finding(FindingCode.INVALID_CANDIDATE,
                            "Receiving locus is absent from the editable junction closure: " + receiverId);
                    }
                }
            }

            if (isLocationBound(original.tags())
                && !request.locationFeatureDecisions().containsKey(candidate.originalJunctionNodeId())) {
                return finding(FindingCode.LOCATION_FEATURE_DECISION_REQUIRED,
                    "Location-bound node tags require an explicit feature decision: "
                        + candidate.originalJunctionNodeId());
            }
        }
        return Optional.empty();
    }

    private static Map<Id, CandidateState> createCandidateStates(ReattachmentRequest request,
        IdAllocator allocator) {
        Map<Id, CandidateState> states = new LinkedHashMap<>();
        request.candidates().stream().sorted(Comparator.comparing(JunctionCandidate::originalJunctionNodeId))
            .forEach(candidate -> {
                Node original = request.before().node(candidate.originalJunctionNodeId());
                LocationFeatureDecision decision = request.locationFeatureDecisions()
                    .getOrDefault(candidate.originalJunctionNodeId(), LocationFeatureDecision.MOVE_WITH_JUNCTION);
                Id finalId = isLocationBound(original.tags())
                    && decision == LocationFeatureDecision.RETAIN_AT_OLD_LOCATION
                        ? allocator.nextNodeId() : candidate.originalJunctionNodeId();
                states.put(candidate.originalJunctionNodeId(),
                    new CandidateState(candidate, finalId, candidate.proposedPosition()));
            });
        return states;
    }

    private static Optional<Finding> validateExistingNodeCollisions(ReattachmentRequest request,
        Map<Id, CandidateState> states) {
        Set<Id> movingJunctions = states.keySet();
        for (CandidateState state : states.values()) {
            for (Node node : request.before().nodes().values()) {
                if (movingJunctions.contains(node.id())
                    || node.point().distance(state.target) > request.existingNodeCollisionToleranceMeters()) {
                    continue;
                }
                if (!collisionIsGradeSeparated(request.before(), state, node.id())) {
                    return finding(FindingCode.EXISTING_NODE_COLLISION,
                        "Candidate collides with an unrelated existing node: " + node.id());
                }
            }
        }
        return Optional.empty();
    }

    private static boolean collisionIsGradeSeparated(TopologyNetwork network, CandidateState state,
        Id collidingNode) {
        List<Way> candidateWays = incidentWays(network, state.candidate.originalJunctionNodeId());
        List<Way> collisionWays = incidentWays(network, collidingNode);
        return !collisionWays.isEmpty() && collisionWays.stream().allMatch(collisionWay ->
            candidateWays.stream().allMatch(candidateWay -> gradeSeparated(candidateWay, collisionWay)));
    }

    private static Map<ReceiverGroup, List<CandidateState>> frozenGroups(Collection<CandidateState> states) {
        Map<ReceiverGroup, List<CandidateState>> groups = new LinkedHashMap<>();
        for (CandidateState state : states) {
            if (state.candidate.receiverPolicy() != ReceiverPolicy.FROZEN_LOCUS) {
                continue;
            }
            for (ReceiverGroup group : state.candidate.receiverGroups()) {
                groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(state);
            }
        }
        return groups;
    }

    private static Optional<Finding> rebuildSingleReceiver(TopologyNetwork before,
        Map<Id, Node> nodes, Map<Id, Way> ways, Id receiverWayId, List<CandidateState> states,
        Map<Id, List<Id>> retainedShapes, IdAllocator allocator) {
        Way originalWay = before.way(receiverWayId);
        Map<Id, CandidateState> onWay = indexStates(states);
        List<Id> base = new ArrayList<>();
        List<Id> newShapes = new ArrayList<>();
        for (int index = 0; index < originalWay.nodeIds().size(); index++) {
            Id nodeId = originalWay.nodeIds().get(index);
            CandidateState state = onWay.get(nodeId);
            if (state == null) {
                base.add(nodeId);
            } else if (!state.finalNodeId.equals(nodeId)) {
                base.add(nodeId);
            } else if (retainsShapeAtOccurrence(before, originalWay, index)) {
                Id shapeId = allocator.nextNodeId();
                nodes.put(shapeId, new Node(shapeId, before.node(nodeId).point(), Map.of()));
                base.add(shapeId);
                newShapes.add(shapeId);
            }
        }
        if (base.size() < 2) {
            return finding(FindingCode.RECEIVER_LOCUS_MISS,
                "Removing the old junction leaves no measurable receiver locus: " + receiverWayId);
        }

        Map<CandidateState, Projection> projections = new LinkedHashMap<>();
        for (CandidateState state : states) {
            Projection projection = project(before, nodes, base, state.target);
            if (projection.distance > state.candidate.localizationToleranceMeters() + GEOMETRY_EPSILON) {
                return finding(FindingCode.RECEIVER_LOCUS_MISS,
                    "Candidate does not lie on the frozen receiving locus: "
                        + state.candidate.originalJunctionNodeId());
            }
            projections.put(state, projection);
        }
        Optional<Finding> orderFailure = validateCoupledOrder(originalWay, states, projections);
        if (orderFailure.isPresent()) {
            return orderFailure;
        }

        List<Id> rebuilt = insertProjectedJunctions(base, projections);
        ways.put(receiverWayId, new Way(receiverWayId, rebuilt, originalWay.tags()));
        if (!newShapes.isEmpty()) {
            retainedShapes.put(receiverWayId, newShapes);
        }
        projections.forEach((state, projection) -> {
            state.target = projection.point;
            putNodeAtTarget(nodes, state);
        });
        return Optional.empty();
    }

    private static Optional<Finding> rebuildSplitReceiver(TopologyNetwork before,
        Map<Id, Node> nodes, Map<Id, Way> ways, ReceiverGroup group, List<CandidateState> states,
        Map<Id, List<Id>> retainedShapes, IdAllocator allocator) {
        if (states.size() != 1) {
            return finding(FindingCode.UNSUPPORTED_SPLIT_RECEIVER,
                "A split receiver can relocate one shared boundary junction per group");
        }
        CandidateState state = states.get(0);
        if (!state.finalNodeId.equals(state.candidate.originalJunctionNodeId())) {
            return finding(FindingCode.UNSUPPORTED_SPLIT_RECEIVER,
                "Retaining a location-bound split point needs an explicit redistribution handler");
        }
        Way first = before.way(group.wayIds().get(0));
        Way second = before.way(group.wayIds().get(1));
        if (!first.tags().equals(second.tags())) {
            return finding(FindingCode.SEMANTIC_BOUNDARY_RELOCATION_UNSUPPORTED,
                "Moving a split receiver join would relocate a way-tag boundary");
        }
        OrientedWay firstOriented = orientToEnd(first, state.candidate.originalJunctionNodeId());
        OrientedWay secondOriented = orientFromStart(second, state.candidate.originalJunctionNodeId());
        if (firstOriented == null || secondOriented == null) {
            return finding(FindingCode.UNSUPPORTED_SPLIT_RECEIVER,
                "Split receiver ways must each terminate once at the old junction");
        }

        List<Id> combined = new ArrayList<>(firstOriented.nodeIds);
        combined.addAll(secondOriented.nodeIds.subList(1, secondOriented.nodeIds.size()));
        int oldIndex = combined.indexOf(state.candidate.originalJunctionNodeId());
        List<Id> base = new ArrayList<>(combined);
        base.remove(oldIndex);
        if (retainsShapeAtCombinedOccurrence(before, combined, oldIndex)) {
            Id shapeId = allocator.nextNodeId();
            nodes.put(shapeId, new Node(shapeId,
                before.node(state.candidate.originalJunctionNodeId()).point(), Map.of()));
            base.add(oldIndex, shapeId);
            retainedShapes.put(first.id(), List.of(shapeId));
        }
        Projection projection = project(before, nodes, base, state.target);
        if (projection.distance > state.candidate.localizationToleranceMeters() + GEOMETRY_EPSILON) {
            return finding(FindingCode.RECEIVER_LOCUS_MISS,
                "Candidate does not lie on the combined frozen receiver locus");
        }
        List<Id> rebuilt = insertProjectedJunctions(base, Map.of(state, projection));
        int junctionIndex = rebuilt.indexOf(state.finalNodeId);
        if (junctionIndex <= 0 || junctionIndex >= rebuilt.size() - 1) {
            return finding(FindingCode.RECEIVER_LOCUS_MISS,
                "Relocated split point must leave a receiving arm on both sides");
        }
        List<Id> firstNodes = new ArrayList<>(rebuilt.subList(0, junctionIndex + 1));
        List<Id> secondNodes = new ArrayList<>(rebuilt.subList(junctionIndex, rebuilt.size()));
        if (firstOriented.reversed) {
            java.util.Collections.reverse(firstNodes);
        }
        if (secondOriented.reversed) {
            java.util.Collections.reverse(secondNodes);
        }
        ways.put(first.id(), new Way(first.id(), firstNodes, first.tags()));
        ways.put(second.id(), new Way(second.id(), secondNodes, second.tags()));
        state.target = projection.point;
        putNodeAtTarget(nodes, state);
        return Optional.empty();
    }

    private static Optional<Finding> validateCoupledOrder(Way originalWay,
        List<CandidateState> states, Map<CandidateState, Projection> projections) {
        List<CandidateState> oldOrder = states.stream().sorted(Comparator.comparingInt(state ->
            originalWay.nodeIds().indexOf(state.candidate.originalJunctionNodeId()))).toList();
        List<CandidateState> newOrder = states.stream().sorted(Comparator
            .comparing((CandidateState state) -> projections.get(state))).toList();
        if (!oldOrder.equals(newOrder)) {
            return finding(FindingCode.COUPLED_ORDER_INVERSION,
                "Coupled junction proposals invert their semantic order on receiver " + originalWay.id());
        }
        for (int index = 1; index < newOrder.size(); index++) {
            if (projections.get(newOrder.get(index - 1)).samePosition(projections.get(newOrder.get(index)))) {
                return finding(FindingCode.COUPLED_ORDER_INVERSION,
                    "Coupled junction proposals collapse to one receiver position");
            }
        }
        return Optional.empty();
    }

    private static List<Id> insertProjectedJunctions(List<Id> base,
        Map<CandidateState, Projection> projections) {
        Map<Integer, List<CandidateState>> bySegment = new HashMap<>();
        projections.forEach((state, projection) ->
            bySegment.computeIfAbsent(projection.segmentIndex, ignored -> new ArrayList<>()).add(state));
        bySegment.values().forEach(values -> values.sort(Comparator.comparingDouble(state ->
            projections.get(state).fraction)));
        List<Id> result = new ArrayList<>();
        for (int index = 0; index < base.size(); index++) {
            result.add(base.get(index));
            for (CandidateState state : bySegment.getOrDefault(index, List.of())) {
                result.add(state.finalNodeId);
            }
        }
        return result;
    }

    private static void replaceJunctionOccurrences(TopologyNetwork before, Map<Id, Way> ways,
        Map<Id, CandidateState> states, Set<Id> frozenReceiverWays) {
        for (Way original : before.ways().values()) {
            if (frozenReceiverWays.contains(original.id())) {
                continue;
            }
            List<Id> replacements = original.nodeIds().stream()
                .map(nodeId -> states.containsKey(nodeId) ? states.get(nodeId).finalNodeId : nodeId)
                .toList();
            if (!replacements.equals(original.nodeIds())) {
                ways.put(original.id(), new Way(original.id(), replacements, original.tags()));
            }
        }
    }

    private static void applyApprovedRelationRemaps(Map<Id, Relation> relations,
        Map<Id, CandidateState> states, Set<Id> approvals) {
        for (Relation relation : List.copyOf(relations.values())) {
            if (!"restriction".equals(relation.tags().get("type"))) {
                continue;
            }
            boolean changed = false;
            List<RelationMember> members = new ArrayList<>(relation.members().size());
            for (RelationMember member : relation.members()) {
                CandidateState state = states.get(member.memberId());
                if (state != null && !state.finalNodeId.equals(member.memberId())
                    && approvals.contains(member.memberId()) && "via".equals(member.role())) {
                    members.add(new RelationMember(state.finalNodeId, member.role()));
                    changed = true;
                } else {
                    members.add(member);
                }
            }
            if (changed) {
                relations.put(relation.id(), new Relation(relation.id(), members,
                    relation.tags(), relation.completeness()));
            }
        }
    }

    private static Set<Id> changedPrimitiveIds(TopologyNetwork before, TopologyNetwork after) {
        Set<Id> changed = new LinkedHashSet<>();
        addChanged(changed, before.nodes(), after.nodes());
        addChanged(changed, before.ways(), after.ways());
        addChanged(changed, before.relations(), after.relations());
        Set<Id> changedNodes = changed.stream()
            .filter(id -> id.type() == PrimitiveType.NODE).collect(java.util.stream.Collectors.toSet());
        after.ways().values().stream()
            .filter(way -> way.nodeIds().stream().anyMatch(changedNodes::contains))
            .map(Way::id).forEach(changed::add);
        return Set.copyOf(changed);
    }

    private static Map<Id, Id> effectiveNodeRemaps(Map<Id, CandidateState> states) {
        Map<Id, Id> remaps = new LinkedHashMap<>();
        states.forEach((oldId, state) -> remaps.put(oldId, state.finalNodeId));
        return Map.copyOf(remaps);
    }

    private static <T> void addChanged(Set<Id> changed, Map<Id, T> before, Map<Id, T> after) {
        Set<Id> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        keys.stream().filter(key -> !Objects.equals(before.get(key), after.get(key))).forEach(changed::add);
    }

    private static Optional<Finding> validateNewAtGradeCrossings(TopologyNetwork before,
        TopologyNetwork after, Set<Id> changed) {
        List<Way> allWays = new ArrayList<>(after.ways().values());
        for (int firstIndex = 0; firstIndex < allWays.size(); firstIndex++) {
            Way first = allWays.get(firstIndex);
            for (int secondIndex = firstIndex + 1; secondIndex < allWays.size(); secondIndex++) {
                Way second = allWays.get(secondIndex);
                if (!changed.contains(first.id()) && !changed.contains(second.id())
                    || gradeSeparated(first, second)
                    || !hasUnsharedIntersection(after, first, second)) {
                    continue;
                }
                Way beforeFirst = before.ways().get(first.id());
                Way beforeSecond = before.ways().get(second.id());
                if (beforeFirst == null || beforeSecond == null
                    || !hasUnsharedIntersection(before, beforeFirst, beforeSecond)) {
                    return finding(FindingCode.UNCONNECTED_AT_GRADE_CROSSING,
                        "Proposal creates an unconnected at-grade crossing between "
                            + first.id() + " and " + second.id());
                }
            }
        }
        return Optional.empty();
    }

    private static boolean hasUnsharedIntersection(TopologyNetwork network, Way first, Way second) {
        for (int firstIndex = 1; firstIndex < first.nodeIds().size(); firstIndex++) {
            Id firstAId = first.nodeIds().get(firstIndex - 1);
            Id firstBId = first.nodeIds().get(firstIndex);
            Point firstA = network.node(firstAId).point();
            Point firstB = network.node(firstBId).point();
            for (int secondIndex = 1; secondIndex < second.nodeIds().size(); secondIndex++) {
                Id secondAId = second.nodeIds().get(secondIndex - 1);
                Id secondBId = second.nodeIds().get(secondIndex);
                if (firstAId.equals(secondAId) || firstAId.equals(secondBId)
                    || firstBId.equals(secondAId) || firstBId.equals(secondBId)) {
                    continue;
                }
                Point secondA = network.node(secondAId).point();
                Point secondB = network.node(secondBId).point();
                if (segmentsIntersect(firstA, firstB, secondA, secondB)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean segmentsIntersect(Point a, Point b, Point c, Point d) {
        double abC = cross(a, b, c);
        double abD = cross(a, b, d);
        double cdA = cross(c, d, a);
        double cdB = cross(c, d, b);
        if (((abC > GEOMETRY_EPSILON && abD < -GEOMETRY_EPSILON)
            || (abC < -GEOMETRY_EPSILON && abD > GEOMETRY_EPSILON))
            && ((cdA > GEOMETRY_EPSILON && cdB < -GEOMETRY_EPSILON)
                || (cdA < -GEOMETRY_EPSILON && cdB > GEOMETRY_EPSILON))) {
            return true;
        }
        return Math.abs(abC) <= GEOMETRY_EPSILON && onSegment(a, b, c)
            || Math.abs(abD) <= GEOMETRY_EPSILON && onSegment(a, b, d)
            || Math.abs(cdA) <= GEOMETRY_EPSILON && onSegment(c, d, a)
            || Math.abs(cdB) <= GEOMETRY_EPSILON && onSegment(c, d, b);
    }

    private static double cross(Point a, Point b, Point c) {
        return (b.xMeters() - a.xMeters()) * (c.yMeters() - a.yMeters())
            - (b.yMeters() - a.yMeters()) * (c.xMeters() - a.xMeters());
    }

    private static boolean onSegment(Point a, Point b, Point p) {
        return p.xMeters() >= Math.min(a.xMeters(), b.xMeters()) - GEOMETRY_EPSILON
            && p.xMeters() <= Math.max(a.xMeters(), b.xMeters()) + GEOMETRY_EPSILON
            && p.yMeters() >= Math.min(a.yMeters(), b.yMeters()) - GEOMETRY_EPSILON
            && p.yMeters() <= Math.max(a.yMeters(), b.yMeters()) + GEOMETRY_EPSILON;
    }

    private static Projection project(TopologyNetwork before, Map<Id, Node> nodes,
        List<Id> locus, Point target) {
        Projection best = null;
        for (int index = 0; index < locus.size() - 1; index++) {
            Point first = point(before, nodes, locus.get(index));
            Point second = point(before, nodes, locus.get(index + 1));
            double dx = second.xMeters() - first.xMeters();
            double dy = second.yMeters() - first.yMeters();
            double lengthSquared = dx * dx + dy * dy;
            if (lengthSquared <= GEOMETRY_EPSILON) {
                continue;
            }
            double fraction = ((target.xMeters() - first.xMeters()) * dx
                + (target.yMeters() - first.yMeters()) * dy) / lengthSquared;
            fraction = Math.max(0.0, Math.min(1.0, fraction));
            Point projected = new Point(first.xMeters() + fraction * dx,
                first.yMeters() + fraction * dy);
            Projection candidate = new Projection(index, fraction, projected, projected.distance(target));
            if (best == null || candidate.distance < best.distance - GEOMETRY_EPSILON
                || Math.abs(candidate.distance - best.distance) <= GEOMETRY_EPSILON
                    && candidate.compareTo(best) < 0) {
                best = candidate;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("Receiver locus has no nonzero segment");
        }
        return best;
    }

    private static Point point(TopologyNetwork before, Map<Id, Node> nodes, Id id) {
        Node node = nodes.get(id);
        return node != null ? node.point() : before.node(id).point();
    }

    private static boolean retainsShapeAtOccurrence(TopologyNetwork network, Way way, int index) {
        if (index <= 0 || index >= way.nodeIds().size() - 1) {
            return false;
        }
        Point previous = network.node(way.nodeIds().get(index - 1)).point();
        Point point = network.node(way.nodeIds().get(index)).point();
        Point next = network.node(way.nodeIds().get(index + 1)).point();
        return pointToLineDistance(point, previous, next) > GEOMETRY_EPSILON;
    }

    private static boolean retainsShapeAtCombinedOccurrence(TopologyNetwork network,
        List<Id> combined, int index) {
        if (index <= 0 || index >= combined.size() - 1) {
            return false;
        }
        return pointToLineDistance(network.node(combined.get(index)).point(),
            network.node(combined.get(index - 1)).point(),
            network.node(combined.get(index + 1)).point()) > GEOMETRY_EPSILON;
    }

    private static double pointToLineDistance(Point point, Point start, Point end) {
        double dx = end.xMeters() - start.xMeters();
        double dy = end.yMeters() - start.yMeters();
        double length = Math.hypot(dx, dy);
        return length <= GEOMETRY_EPSILON ? point.distance(start)
            : Math.abs(dx * (start.yMeters() - point.yMeters())
                - (start.xMeters() - point.xMeters()) * dy) / length;
    }

    private static OrientedWay orientToEnd(Way way, Id junction) {
        if (way.nodeIds().get(way.nodeIds().size() - 1).equals(junction)) {
            return new OrientedWay(way.nodeIds(), false);
        }
        if (way.nodeIds().get(0).equals(junction)) {
            List<Id> reversed = new ArrayList<>(way.nodeIds());
            java.util.Collections.reverse(reversed);
            return new OrientedWay(reversed, true);
        }
        return null;
    }

    private static OrientedWay orientFromStart(Way way, Id junction) {
        if (way.nodeIds().get(0).equals(junction)) {
            return new OrientedWay(way.nodeIds(), false);
        }
        if (way.nodeIds().get(way.nodeIds().size() - 1).equals(junction)) {
            List<Id> reversed = new ArrayList<>(way.nodeIds());
            java.util.Collections.reverse(reversed);
            return new OrientedWay(reversed, true);
        }
        return null;
    }

    private static Map<Id, CandidateState> indexStates(List<CandidateState> states) {
        Map<Id, CandidateState> indexed = new LinkedHashMap<>();
        states.forEach(state -> indexed.put(state.candidate.originalJunctionNodeId(), state));
        return indexed;
    }

    private static void putNodeAtTarget(Map<Id, Node> nodes, CandidateState state) {
        Node current = nodes.get(state.finalNodeId);
        Map<String, String> tags = state.finalNodeId.equals(state.candidate.originalJunctionNodeId())
            ? current.tags() : Map.of();
        nodes.put(state.finalNodeId, new Node(state.finalNodeId, state.target, tags));
    }

    private static List<Way> incidentWays(TopologyNetwork network, Id nodeId) {
        return network.ways().values().stream().filter(way -> way.nodeIds().contains(nodeId)).toList();
    }

    private static boolean isAreaOrClosedWay(Way way) {
        return way.closed() || "yes".equals(way.tags().get("area"))
            || way.tags().containsKey("building") || way.tags().containsKey("landuse")
            || way.tags().containsKey("boundary");
    }

    private static boolean isLocationBound(Map<String, String> tags) {
        String highway = tags.get("highway");
        return tags.containsKey("barrier") || tags.containsKey("crossing")
            || "traffic_signals".equals(highway) || "crossing".equals(highway)
            || "stop".equals(highway) || "give_way".equals(highway)
            || tags.containsKey("railway") || tags.containsKey("entrance");
    }

    private static boolean gradeSeparated(Way first, Way second) {
        int firstLayer = parseLayer(first.tags().get("layer"));
        int secondLayer = parseLayer(second.tags().get("layer"));
        if (firstLayer != secondLayer) {
            return true;
        }
        return isYes(first.tags().get("bridge")) && isYes(second.tags().get("tunnel"))
            || isYes(second.tags().get("bridge")) && isYes(first.tags().get("tunnel"));
    }

    private static int parseLayer(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static boolean isYes(String value) {
        return "yes".equals(value) || "true".equals(value) || "1".equals(value);
    }

    private static void requireType(Id id, PrimitiveType type, String label) {
        if (id == null || id.type() != type) {
            throw new IllegalArgumentException("Expected typed " + label + " identity");
        }
    }

    private static Optional<Finding> finding(FindingCode code, String detail) {
        return Optional.of(new Finding(code, detail));
    }

    private static PlanningResult blocked(Finding finding) {
        return new PlanningResult(Optional.empty(), List.of(finding));
    }

    private static final class CandidateState {
        private final JunctionCandidate candidate;
        private final Id finalNodeId;
        private Point target;

        private CandidateState(JunctionCandidate candidate, Id finalNodeId, Point target) {
            this.candidate = candidate;
            this.finalNodeId = finalNodeId;
            this.target = target;
        }
    }

    private record OrientedWay(List<Id> nodeIds, boolean reversed) {
        private OrientedWay {
            nodeIds = List.copyOf(nodeIds);
        }
    }

    private record Projection(int segmentIndex, double fraction, Point point, double distance)
        implements Comparable<Projection> {
        @Override
        public int compareTo(Projection other) {
            int segmentOrder = Integer.compare(segmentIndex, other.segmentIndex);
            return segmentOrder != 0 ? segmentOrder : Double.compare(fraction, other.fraction);
        }

        private boolean samePosition(Projection other) {
            return compareTo(other) == 0 || point.distance(other.point) <= GEOMETRY_EPSILON;
        }
    }

    private static final class IdAllocator {
        private long next;

        private IdAllocator(Collection<Id> existingNodes) {
            next = existingNodes.stream()
                .filter(id -> id.type() == PrimitiveType.NODE
                    && id.identityNamespace() == IdentityNamespace.PLAN_LOCAL)
                .mapToLong(Id::value).max().orElse(0L) + 1L;
        }

        private Id nextNodeId() {
            return Id.planned(PrimitiveType.NODE, next++);
        }
    }
}
