package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedRelation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedRelationMember;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.JunctionCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.ReceiverGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.ReceiverPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork;

/**
 * Builds a deliberately bounded edit plan from one detached common-pipeline route.
 *
 * <p>The fixed policy remains a selected-way-only edit. Explicit legacy movement may relocate
 * captured movable boundary identities, while explicit reattachment delegates receiver ordering
 * to the pure topology planner. It has no live JOSM dependency and performs no mutation.</p>
 */
public final class ModernSingleWayEditPlanAdapter {
    /** Typed product reason describing whether the exact final preview can reach Apply. */
    public enum ApplyAvailability {
        PLAN_AVAILABLE,
        CLEANUP_UNAVAILABLE_FOR_ENGINE,
        PRECISE_SHAPE_REQUIRED,
        SOURCE_LINEAGE_UNAVAILABLE,
        CANDIDATE_ASSIGNMENTS_UNAVAILABLE,
        FINAL_TOPOLOGY_CROSSING,
        FINAL_TOPOLOGY_VERTEX_TOUCH,
        FINAL_TOPOLOGY_COLLINEAR_OVERLAP,
        FINAL_TOPOLOGY_CONTINUATION,
        FINAL_GEOMETRY_BLOCKED,
        PLAN_UNAVAILABLE
    }

    /** Exact immutable plan when inspectable, plus its typed Apply availability. */
    public record Assessment(Optional<AlignmentEditPlan> plan,
            ApplyAvailability availability, String detail) {
        /** Validates a bounded user-visible assessment. */
        public Assessment {
            boolean inspectable = availability != null
                    && (availability == ApplyAvailability.PLAN_AVAILABLE
                    || availability == ApplyAvailability.FINAL_TOPOLOGY_CROSSING
                    || availability == ApplyAvailability.FINAL_TOPOLOGY_VERTEX_TOUCH
                    || availability == ApplyAvailability.FINAL_TOPOLOGY_COLLINEAR_OVERLAP
                    || availability == ApplyAvailability.FINAL_TOPOLOGY_CONTINUATION
                    || availability == ApplyAvailability.FINAL_GEOMETRY_BLOCKED);
            if (plan == null || availability == null || detail == null || detail.isBlank()
                    || inspectable != plan.isPresent()) {
                throw new IllegalArgumentException("Modern Apply assessment is incomplete");
            }
        }

        /** Returns whether Apply may be offered, subject to exact review confirmation. */
        public boolean applyAvailable() {
            return availability == ApplyAvailability.PLAN_AVAILABLE
                    && plan.orElseThrow().validation().applicable();
        }

        /**
         * Projects exactly the complete immutable all-way preview carried by this assessment.
         *
         * @param projector coordinate projection used only for display
         * @param <T> display-coordinate type
         * @return immutable projected geometry with exactly the plan's affected-way keys
         */
        public <T> Map<PrimitiveKey, List<T>> projectFinalPreviewWays(
                Function<GeographicPoint, T> projector) {
            if (projector == null) {
                throw new IllegalArgumentException("Preview projector must not be null");
            }
            AlignmentEditPlan exactPlan = plan.orElseThrow(() ->
                    new IllegalStateException("No exact all-way preview is available"));
            Map<PrimitiveKey, List<T>> projected = new LinkedHashMap<>();
            exactPlan.finalPreviewWays().forEach((way, points) -> projected.put(way,
                    points.stream().map(projector).toList()));
            return Map.copyOf(projected);
        }
    }

    /**
     * Produces an exact plan or one typed visible reason why Apply is unavailable.
     * Blocked topology plans remain present so the user can inspect their exact geometry.
     */
    public Assessment assess(LiveBPreviewService.Computed computed, int routeIndex) {
        if (computed == null || computed.captured() == null || computed.pipeline() == null
                || routeIndex < 0 || routeIndex >= computed.pipeline().routes().size()) {
            return unavailable(ApplyAvailability.PLAN_UNAVAILABLE,
                    "The final candidate selection is incomplete");
        }
        ModernTracePipeline.Route route = computed.pipeline().routes().get(routeIndex);
        if (computed.request().engine() == TrackerMode.PROBABILISTIC
                && !computed.captured().cleanup().isDisabled()) {
            return unavailable(ApplyAvailability.CLEANUP_UNAVAILABLE_FOR_ENGINE,
                    "Probabilistic B cleanup is unavailable until its exact final pipeline is supported");
        }
        if (route.quality().has(FinalGeometryEvaluator.FindingCode.PRECISE_SHAPE_REQUIRED)) {
            return unavailable(ApplyAvailability.PRECISE_SHAPE_REQUIRED,
                    "The existing nodes cannot safely represent the actual final route; use Precise Shape");
        }
        if (!hasExactSourceLineage(computed)) {
            return unavailable(ApplyAvailability.SOURCE_LINEAGE_UNAVAILABLE,
                    "The exact frozen source lineage is unavailable");
        }
        if (!route.assignments().keySet().equals(new LinkedHashSet<>(route.pointIds()))
                || !route.sourceOwnership().keySet().equals(new LinkedHashSet<>(route.pointIds()))) {
            return unavailable(ApplyAvailability.CANDIDATE_ASSIGNMENTS_UNAVAILABLE,
                    "The exact candidate-owned final assignments are unavailable");
        }
        try {
            AlignmentEditPlan plan = adapt(computed, routeIndex);
            ApplyAvailability topology = topologyAvailability(plan.validation().findingCodes());
            if (topology != ApplyAvailability.PLAN_AVAILABLE) {
                return new Assessment(Optional.of(plan), topology,
                        "The exact final preview is blocked by " + topology.name());
            }
            if (plan.validation().disposition() == ValidationReport.Disposition.HARD_BLOCKED) {
                return new Assessment(Optional.of(plan), ApplyAvailability.FINAL_GEOMETRY_BLOCKED,
                        "The exact final preview is blocked by final geometry validation");
            }
            return new Assessment(Optional.of(plan), ApplyAvailability.PLAN_AVAILABLE,
                    "Exact immutable plan available");
        } catch (IllegalArgumentException failure) {
            return unavailable(ApplyAvailability.PLAN_UNAVAILABLE, failure.getMessage());
        }
    }

    private static Assessment unavailable(ApplyAvailability availability, String detail) {
        return new Assessment(Optional.empty(), availability,
                detail == null || detail.isBlank() ? availability.name() : detail);
    }

    private static ApplyAvailability topologyAvailability(List<String> findings) {
        if (findings.contains("final-topology:CROSSING")) {
            return ApplyAvailability.FINAL_TOPOLOGY_CROSSING;
        }
        if (findings.contains("final-topology:VERTEX_TOUCH")) {
            return ApplyAvailability.FINAL_TOPOLOGY_VERTEX_TOUCH;
        }
        if (findings.contains("final-topology:COLLINEAR_OVERLAP")) {
            return ApplyAvailability.FINAL_TOPOLOGY_COLLINEAR_OVERLAP;
        }
        if (findings.contains("final-topology:CONTINUATION")) {
            return ApplyAvailability.FINAL_TOPOLOGY_CONTINUATION;
        }
        return ApplyAvailability.PLAN_AVAILABLE;
    }

    private static boolean hasExactSourceLineage(LiveBPreviewService.Computed computed) {
        String capturedIdentity = computed.captured().managedRaster() == null
                ? computed.captured().raster().sourceIdentity()
                : computed.captured().managedRaster().sourceIdentity();
        if (!capturedIdentity.equals(computed.evidence().sourceIdentity())) {
            return false;
        }
        var expectedAcquisition = computed.captured().managedRaster() == null
                ? EvidenceFieldLineage.AcquisitionKind.VISIBLE_RENDER
                : EvidenceFieldLineage.AcquisitionKind.MANAGED_TILE;
        return computed.evidence().fields().values().stream().allMatch(field ->
                field.lineage().acquisitionKind() == expectedAcquisition
                && (field.lineage().derivationKind()
                        != EvidenceFieldLineage.DerivationKind.ALL_COLOR_AGGREGATE
                    || field.lineage().completeAggregate()));
    }

    /**
     * Converts one exact final route into a validated immutable plan.
     *
     * @param computed one immutable captured/request/evidence/pipeline result
     * @param routeIndex checked index of the selected final route
     * @return complete validated single-way edit plan
     */
    public AlignmentEditPlan adapt(LiveBPreviewService.Computed computed, int routeIndex) {
        if (computed == null || computed.captured() == null || computed.evidence() == null
                || computed.request() == null || computed.pipeline() == null
                || routeIndex < 0 || routeIndex >= computed.pipeline().routes().size()) {
            throw new IllegalArgumentException("Computed route selection is incomplete");
        }
        LiveBPreviewService.Captured captured = computed.captured();
        ModernTracePipeline.Route route = computed.pipeline().routes().get(routeIndex);
        TraceRequest request = computed.request();
        EvidenceSnapshot evidence = computed.evidence();
        NetworkSnapshot before = captured.network();
        requireInputs(route, request, evidence, captured);
        DetachedWay selected = requireSupportedBoundary(request, before, captured.cleanup());
        List<PrimitiveKey> replacement = replacementNodes(route, request, evidence, before, selected);

        Map<PrimitiveKey, DetachedPrimitive> afterValues = new LinkedHashMap<>(before.primitives());
        for (PrimitiveKey removable : before.closure().removableExistingNodeKeys()) {
            if (!replacement.contains(removable)) {
                afterValues.remove(removable);
            }
        }
        Set<PrimitiveKey> sharedJunctions = sharedMovableBoundaries(request, before, selected);
        Set<PrimitiveKey> deferredJunctions = request.permissions().junctionPolicy() == JunctionPolicy.REATTACH
                ? sharedJunctions : Set.of();
        for (int index = 0; index < route.pointIds().size(); index++) {
            FinalRoutePointId pointId = route.pointIds().get(index);
            MetricPoint metric = route.assignments().get(pointId);
            GeographicPoint geographic = evidence.coordinateFrame().toGeographic(metric);
            if (pointId instanceof GeneratedCandidatePoint generated) {
                PrimitiveKey planned = plannedKey(generated);
                afterValues.put(planned,
                    new DetachedNode(planned, geographic, Map.of(), false, true));
            } else if (pointId instanceof ExistingWayNodeOccurrence existing
                    && before.closure().movableExistingNodeKeys().contains(existing.nodeKey())
                    && !deferredJunctions.contains(existing.nodeKey())) {
                DetachedNode old = (DetachedNode) before.primitives().get(existing.nodeKey());
                afterValues.put(existing.nodeKey(), new DetachedNode(existing.nodeKey(), geographic,
                        old.tags(), old.deleted(), old.modified() || !old.coordinate().equals(geographic)));
            }
        }
        boolean wayChanged = !selected.nodeKeys().equals(replacement);
        afterValues.put(selected.key(), new DetachedWay(selected.key(), replacement,
            selected.tags(), false, selected.modified() || wayChanged));
        if (!sharedJunctions.isEmpty()
                && request.permissions().junctionPolicy() == JunctionPolicy.REATTACH) {
            afterValues = applyReattachment(afterValues, before, route, request, evidence,
                    sharedJunctions);
        }
        NetworkSnapshot after = new NetworkSnapshot(
            before.snapshotId() + ":proposed:" + route.hypothesis().id(),
            SnapshotRole.PROPOSED_AFTER, before.datasetIdentity(), before.sourceGeneration(),
            before.closure(), afterValues, proposedWatches(before, afterValues));
        Map<PrimitiveKey, List<GeographicPoint>> preview = finalPreviewWays(before, afterValues);
        List<String> topologyFindings = finalTopologyFindings(before, afterValues,
                request.selectedWayKey(), request.selectedRange(), evidence);
        ValidationReport validation = validation(route.quality(),
                request.permissions().junctionPolicy(), topologyFindings);
        return new AlignmentEditPlan(request.selectedWayKey(), request.selectedRange(),
            before, after, evidence.coordinateFrame(), request.permissions(),
            captured.settingsHash(), evidence.canonicalHash(), captured.parameterHash(),
            route.hypothesis().id(), preview, validation);
    }

    private static void requireInputs(ModernTracePipeline.Route route, TraceRequest request,
            EvidenceSnapshot evidence, LiveBPreviewService.Captured captured) {
        NetworkSnapshot before = captured.network();
        if (route == null || before == null || captured.specification() == null
                || captured.settingsHash() == null || captured.settingsHash().isBlank()
                || captured.parameterHash() == null || captured.parameterHash().isBlank()
                || captured.engine() != request.engine()
                || !captured.settingsHash().equals(request.settingsHash())
                || !captured.parameterHash().equals(request.parameterHash())
                || !captured.specification().selectedWayKey().equals(request.selectedWayKey())
                || !captured.specification().selectedRange().equals(request.selectedRange())
                || !captured.specification().permissions().equals(request.permissions())
                || !request.evidenceSnapshotId().equals(evidence.snapshotId())
                || !request.evidenceContentHash().equals(evidence.canonicalHash())
                || !request.networkSnapshotId().equals(before.snapshotId())
                || !request.networkContentHash().equals(before.canonicalHash())
                || !route.hypothesis().id().equals(route.quality().id())) {
            throw new IllegalArgumentException(
                "Final route, snapshots, and effective identities do not match");
        }
        if (route.quality().disposition() == FinalGeometryEvaluator.Disposition.APPLICABLE
                    && !route.quality().findings().isEmpty()) {
            throw new IllegalArgumentException("Inconsistent final route cannot form a plan");
        }
    }

    private static DetachedWay requireSupportedBoundary(
            TraceRequest request, NetworkSnapshot before, GeometryCleanupConfig cleanup) {
        ClosureDescriptor closure = before.closure();
        if (before.role() != SnapshotRole.CAPTURED_BEFORE
                || request.engine() == TrackerMode.LEGACY_V02
                || request.geometryMode() != AlignmentMode.PRECISE_SHAPE
                    && request.geometryMode() != AlignmentMode.MOVE_EXISTING_NODES
                || request.permissions().widerDiscovery()
                || request.permissions().junctionPolicy() == JunctionPolicy.LEGACY_BOUNDED_MOVE
                    && request.permissions().reconstructIncidentWays()
                || request.permissions().ordinaryRadiusMeters()
                    != request.permissions().maximumDiscoveryRadiusMeters()
                || closure.scope() != ClosureDescriptor.Scope.EDIT_COMPONENT
                || request.geometryMode() == AlignmentMode.PRECISE_SHAPE
                    && !closure.mayCreateNodes()) {
            throw new IllegalArgumentException(
                "Route exceeds the supported bounded edit-plan boundary");
        }
        DetachedPrimitive primitive = before.primitives().get(request.selectedWayKey());
        if (!(primitive instanceof DetachedWay selected)
                || request.selectedRange().lastIndex() >= selected.nodeKeys().size()
                || new HashSet<>(selected.nodeKeys()).size() != selected.nodeKeys().size()) {
            throw new IllegalArgumentException("Selected way occurrence identity is incomplete or repeated");
        }
        if (!closure.removableExistingNodeKeys().isEmpty()) {
            PrimitiveKey middle = selected.nodeKeys().get(request.selectedRange().firstIndex() + 1);
            DetachedPrimitive value = before.primitives().get(middle);
            if (request.engine() != TrackerMode.CORRIDOR_AWARE
                    || request.geometryMode() != AlignmentMode.PRECISE_SHAPE
                    || cleanup.mode() != GeometryCleanupMode.REDUCE_POINTS_ONLY
                    || request.selectedRange().size() != 3
                    || !closure.removableExistingNodeKeys().equals(Set.of(middle))
                    || !(value instanceof DetachedNode node) || !node.tags().isEmpty()
                    || !before.incomingReferrerWatches().getOrDefault(middle, Set.of())
                            .equals(Set.of(request.selectedWayKey()))) {
                throw new IllegalArgumentException(
                        "Only one captured ordinary interior shape occurrence may be removed");
            }
        }
        if (!closure.editableExistingKeys().contains(request.selectedWayKey())
                || !closure.editableWayOccurrences().getOrDefault(request.selectedWayKey(), List.of())
                        .equals(List.of(request.selectedRange()))) {
            throw new IllegalArgumentException("Selected occurrence authority is incomplete");
        }
        List<PrimitiveKey> selectedNodes = selected.nodeKeys().subList(
            request.selectedRange().firstIndex(), request.selectedRange().lastIndex() + 1);
        Set<PrimitiveKey> authorizedNodes = new LinkedHashSet<>(closure.protectedExistingNodeKeys());
        authorizedNodes.addAll(closure.movableExistingNodeKeys());
        authorizedNodes.addAll(closure.removableExistingNodeKeys());
        if (!authorizedNodes.containsAll(selectedNodes)
                || request.geometryMode() == AlignmentMode.PRECISE_SHAPE
                    && request.permissions().junctionPolicy() == JunctionPolicy.FIXED
                    && !closure.protectedExistingNodeKeys().containsAll(selectedNodes)
                || request.geometryMode() == AlignmentMode.PRECISE_SHAPE
                    && request.permissions().junctionPolicy() == JunctionPolicy.FIXED
                    && (!closure.movableExistingNodeKeys().isEmpty()
                        || !closure.editableExistingKeys().equals(Set.of(request.selectedWayKey())))) {
            throw new IllegalArgumentException(
                "Selected occurrence movement/protection authority is incomplete");
        }
        return selected;
    }

    private static List<PrimitiveKey> replacementNodes(ModernTracePipeline.Route route,
            TraceRequest request, EvidenceSnapshot evidence, NetworkSnapshot before,
            DetachedWay selected) {
        List<FinalRoutePointId> ids = route.pointIds();
        if (!(ids.get(0) instanceof ExistingWayNodeOccurrence first)
                || !(ids.get(ids.size() - 1) instanceof ExistingWayNodeOccurrence last)
                || first.originalOccurrenceIndex() != request.selectedRange().firstIndex()
                || last.originalOccurrenceIndex() != request.selectedRange().lastIndex()) {
            throw new IllegalArgumentException("Final route must retain both selected boundaries");
        }
        List<PrimitiveKey> result = new ArrayList<>();
        result.addAll(selected.nodeKeys().subList(0, request.selectedRange().firstIndex()));
        int expectedOccurrence = request.selectedRange().firstIndex();
        Set<PrimitiveKey> planned = new LinkedHashSet<>();
        for (FinalRoutePointId id : ids) {
            if (id instanceof ExistingWayNodeOccurrence existing) {
                int occurrence = existing.originalOccurrenceIndex();
                if (!existing.wayKey().equals(request.selectedWayKey())
                        || occurrence < expectedOccurrence
                        || occurrence > request.selectedRange().lastIndex()
                        || !selected.nodeKeys().get(occurrence).equals(existing.nodeKey())) {
                    throw new IllegalArgumentException(
                            "Final route existing occurrences are missing, duplicated, or reordered");
                }
                for (int skipped = expectedOccurrence; skipped < occurrence; skipped++) {
                    if (!before.closure().removableExistingNodeKeys()
                            .contains(selected.nodeKeys().get(skipped))) {
                        throw new IllegalArgumentException(
                                "Final route omits a protected or movable existing occurrence");
                    }
                }
                DetachedPrimitive value = before.primitives().get(existing.nodeKey());
                MetricPoint captured = value instanceof DetachedNode node
                    ? evidence.coordinateFrame().toMetric(node.coordinate()) : null;
                boolean movable = before.closure().movableExistingNodeKeys().contains(existing.nodeKey());
                if (captured == null || route.assignments().get(id) == null
                        || !movable && !captured.equals(route.assignments().get(id))) {
                    throw new IllegalArgumentException(
                        "Existing occurrence assignment exceeds its movement authority");
                }
                result.add(existing.nodeKey());
                expectedOccurrence = occurrence + 1;
            } else if (id instanceof GeneratedCandidatePoint generated) {
                if (!generated.candidateId().equals(route.hypothesis().id())
                        || !planned.add(plannedKey(generated))) {
                    throw new IllegalArgumentException(
                        "Generated route identities are inconsistent or duplicated");
                }
                result.add(plannedKey(generated));
            } else {
                throw new IllegalArgumentException("Unsupported final route point identity");
            }
        }
        if (expectedOccurrence != request.selectedRange().lastIndex() + 1) {
            throw new IllegalArgumentException("Final route omits a selected existing occurrence");
        }
        result.addAll(selected.nodeKeys().subList(request.selectedRange().lastIndex() + 1,
            selected.nodeKeys().size()));
        if (new HashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException(
                "Final selected-way node occurrence identities are duplicated");
        }
        return List.copyOf(result);
    }

    private static Set<PrimitiveKey> sharedMovableBoundaries(TraceRequest request,
            NetworkSnapshot before, DetachedWay selected) {
        Set<PrimitiveKey> result = new LinkedHashSet<>();
        for (int occurrence : List.of(request.selectedRange().firstIndex(),
                request.selectedRange().lastIndex())) {
            PrimitiveKey node = selected.nodeKeys().get(occurrence);
            long referringWays = before.incomingReferrerWatches().getOrDefault(node, Set.of()).stream()
                    .filter(key -> key.type() == PrimitiveKey.Type.WAY).count();
            if (referringWays > 1 && before.closure().movableExistingNodeKeys().contains(node)) {
                result.add(node);
            }
        }
        return Set.copyOf(result);
    }

    private static Map<PrimitiveKey, DetachedPrimitive> applyReattachment(
            Map<PrimitiveKey, DetachedPrimitive> baseValues, NetworkSnapshot before,
            ModernTracePipeline.Route route, TraceRequest request, EvidenceSnapshot evidence,
            Set<PrimitiveKey> sharedJunctions) {
        TopologyConversion conversion = new TopologyConversion(before.primitives(), evidence);
        TopologyNetwork topology = conversion.toTopology();
        TopologyNetwork.Id selectedWay = conversion.id(request.selectedWayKey());
        List<JunctionCandidate> candidates = new ArrayList<>();
        for (PrimitiveKey junction : sharedJunctions.stream().sorted().toList()) {
            ExistingWayNodeOccurrence occurrence = route.pointIds().stream()
                    .filter(ExistingWayNodeOccurrence.class::isInstance)
                    .map(ExistingWayNodeOccurrence.class::cast)
                    .filter(value -> value.nodeKey().equals(junction)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Final route omits a movable junction occurrence"));
            MetricPoint proposed = route.assignments().get(occurrence);
            List<TopologyNetwork.Id> receivers = topology.ways().values().stream()
                    .filter(way -> !way.id().equals(selectedWay)
                            && way.nodeIds().contains(conversion.id(junction)))
                    .map(TopologyNetwork.Way::id).sorted().toList();
            ReceiverPolicy receiverPolicy = request.permissions().reconstructIncidentWays()
                    ? ReceiverPolicy.RECONSTRUCT_INCIDENT : ReceiverPolicy.FROZEN_LOCUS;
            List<ReceiverGroup> groups;
            if (receiverPolicy == ReceiverPolicy.RECONSTRUCT_INCIDENT) {
                groups = List.of();
            } else if (receivers.size() == 1) {
                groups = List.of(new ReceiverGroup(receivers));
            } else {
                throw new IllegalArgumentException(
                        "Frozen-locus reattachment requires one unambiguous receiver way");
            }
            candidates.add(new JunctionCandidate(conversion.id(junction),
                    new TopologyNetwork.Point(proposed.xMeters(), proposed.yMeters()), selectedWay,
                    groups, receiverPolicy,
                    Math.max(1.0e-6, evidence.resolution().effectivePitchMeters())));
        }
        Set<TopologyNetwork.Id> editableWays = before.closure().editableExistingKeys().stream()
                .filter(key -> key.type() == PrimitiveKey.Type.WAY)
                .map(conversion::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
        double[] bounds = bounds(before.closure().editRegion());
        JunctionReattachmentPlanner.ReattachmentRequest topologyRequest =
                new JunctionReattachmentPlanner.ReattachmentRequest(topology, candidates,
                        editableWays, new JunctionReattachmentPlanner.Bounds(
                                bounds[0], bounds[1], bounds[2], bounds[3]),
                        new JunctionReattachmentPlanner.Permissions(true,
                                request.permissions().reconstructIncidentWays(), 20.0),
                        Map.of(), Set.of(), Math.max(1.0e-6,
                                evidence.resolution().effectivePitchMeters() * 0.25));
        JunctionReattachmentPlanner planner = new JunctionReattachmentPlanner();
        JunctionReattachmentPlanner.PlanningResult planned = planner.plan(topologyRequest);
        if (!planned.accepted()) {
            throw new IllegalArgumentException("Junction reattachment was rejected: "
                    + planned.findings().stream().map(finding -> finding.code().name())
                            .distinct().toList());
        }
        Map<PrimitiveKey, DetachedPrimitive> combined = new LinkedHashMap<>(conversion.toDetached(
                planned.plan().orElseThrow().after(), before.primitives()));
        for (Map.Entry<PrimitiveKey, DetachedPrimitive> entry : baseValues.entrySet()) {
            if (before.primitives().containsKey(entry.getKey())) {
                continue;
            }
            DetachedPrimitive displaced = combined.putIfAbsent(entry.getKey(), entry.getValue());
            if (displaced != null && !displaced.equals(entry.getValue())) {
                throw new IllegalArgumentException(
                        "Topology planning collided with a route-local primitive identity");
            }
        }
        combined.put(request.selectedWayKey(), baseValues.get(request.selectedWayKey()));
        if (request.permissions().reconstructIncidentWays()) {
            combined = new LinkedHashMap<>(IncidentWayReconstructor.reconstruct(before, combined,
                    evidence, request.selectedWayKey(), sharedJunctions));
        }
        for (PrimitiveKey junction : sharedJunctions) {
            ExistingWayNodeOccurrence occurrence = route.pointIds().stream()
                    .filter(ExistingWayNodeOccurrence.class::isInstance)
                    .map(ExistingWayNodeOccurrence.class::cast)
                    .filter(value -> value.nodeKey().equals(junction)).findFirst().orElseThrow();
            MetricPoint expected = route.assignments().get(occurrence);
            DetachedNode finalNode = (DetachedNode) combined.get(junction);
            MetricPoint actual = evidence.coordinateFrame().toMetric(finalNode.coordinate());
            if (actual.distanceTo(expected) > 1.0e-9) {
                throw new IllegalArgumentException(
                        "Topology adjustment would make the edit plan differ from the reviewed route");
            }
        }
        TopologyNetwork combinedTopology = new TopologyConversion(combined, evidence).toTopology();
        List<JunctionReattachmentPlanner.Finding> wholeComponentFindings =
                planner.validateWholeComponent(topology, combinedTopology);
        if (!wholeComponentFindings.isEmpty()) {
            throw new IllegalArgumentException("Combined junction proposal was rejected: "
                    + wholeComponentFindings.stream().map(finding -> finding.code().name())
                            .distinct().toList());
        }
        return Map.copyOf(combined);
    }

    private static double[] bounds(org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion region) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (List<MetricPoint> polygon : region.polygons()) {
            for (MetricPoint point : polygon) {
                minX = Math.min(minX, point.xMeters());
                minY = Math.min(minY, point.yMeters());
                maxX = Math.max(maxX, point.xMeters());
                maxY = Math.max(maxY, point.yMeters());
            }
        }
        return new double[] {minX, minY, maxX, maxY};
    }

    private static Map<PrimitiveKey, List<GeographicPoint>> finalPreviewWays(
            NetworkSnapshot before, Map<PrimitiveKey, DetachedPrimitive> after) {
        Set<PrimitiveKey> movedNodes = new LinkedHashSet<>();
        after.forEach((key, value) -> {
            if (value instanceof DetachedNode node
                    && before.primitives().get(key) instanceof DetachedNode old
                    && !old.coordinate().equals(node.coordinate())) {
                movedNodes.add(key);
            }
        });
        Map<PrimitiveKey, List<GeographicPoint>> result = new LinkedHashMap<>();
        after.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (!(entry.getValue() instanceof DetachedWay way)) {
                return;
            }
            DetachedPrimitive oldValue = before.primitives().get(entry.getKey());
            boolean changedSequence = !(oldValue instanceof DetachedWay old)
                    || !old.nodeKeys().equals(way.nodeKeys());
            if (changedSequence || way.nodeKeys().stream().anyMatch(movedNodes::contains)) {
                result.put(entry.getKey(), way.nodeKeys().stream()
                        .map(key -> ((DetachedNode) after.get(key)).coordinate()).toList());
            }
        });
        return Map.copyOf(result);
    }

    private static PrimitiveKey plannedKey(GeneratedCandidatePoint generated) {
        return PrimitiveKey.planned(PrimitiveKey.Type.NODE, generated.originalPointIndex());
    }

    private static Map<PrimitiveKey, Set<PrimitiveKey>> proposedWatches(
            NetworkSnapshot before, Map<PrimitiveKey, DetachedPrimitive> after) {
        Map<PrimitiveKey, Set<PrimitiveKey>> beforeInternal = before.internalIncomingReferrers();
        Map<PrimitiveKey, Set<PrimitiveKey>> afterInternal = internalWatches(after);
        Map<PrimitiveKey, Set<PrimitiveKey>> result = new LinkedHashMap<>();
        for (PrimitiveKey target : after.keySet()) {
            Set<PrimitiveKey> referrers = new LinkedHashSet<>(afterInternal.get(target));
            if (before.primitives().containsKey(target)) {
                Set<PrimitiveKey> external = new LinkedHashSet<>(
                    before.incomingReferrerWatches().get(target));
                external.removeAll(beforeInternal.get(target));
                referrers.addAll(external);
            }
            result.put(target, Set.copyOf(referrers));
        }
        return Map.copyOf(result);
    }

    private static Map<PrimitiveKey, Set<PrimitiveKey>> internalWatches(
            Map<PrimitiveKey, DetachedPrimitive> values) {
        Map<PrimitiveKey, Set<PrimitiveKey>> result = new LinkedHashMap<>();
        values.keySet().forEach(key -> result.put(key, new LinkedHashSet<>()));
        for (DetachedPrimitive primitive : values.values()) {
            if (primitive instanceof DetachedWay way) {
                way.nodeKeys().forEach(node -> result.get(node).add(way.key()));
            } else if (primitive instanceof DetachedRelation relation) {
                relation.members().forEach(member ->
                    result.get(member.memberKey()).add(relation.key()));
            }
        }
        Map<PrimitiveKey, Set<PrimitiveKey>> immutable = new LinkedHashMap<>();
        result.forEach((key, referrers) -> immutable.put(key, Set.copyOf(referrers)));
        return Map.copyOf(immutable);
    }

    private enum TopologyDefect { NONE, CROSSING, VERTEX_TOUCH, COLLINEAR_OVERLAP }

    private record Segment(PrimitiveKey wayKey, int index, PrimitiveKey firstKey,
            PrimitiveKey secondKey, MetricPoint first, MetricPoint second, boolean changed,
            boolean insideSelectedRange) { }

    private static List<String> finalTopologyFindings(NetworkSnapshot before,
            Map<PrimitiveKey, DetachedPrimitive> after, PrimitiveKey selectedWay,
            OccurrenceRange selectedRange, EvidenceSnapshot evidence) {
        DetachedWay beforeSelected = (DetachedWay) before.primitives().get(selectedWay);
        PrimitiveKey selectedFirst = beforeSelected.nodeKeys().get(selectedRange.firstIndex());
        PrimitiveKey selectedLast = beforeSelected.nodeKeys().get(selectedRange.lastIndex());
        List<Segment> segments = new ArrayList<>();
        after.values().stream().filter(DetachedWay.class::isInstance)
                .map(DetachedWay.class::cast).sorted(java.util.Comparator.comparing(DetachedWay::key))
                .forEach(way -> {
                    int selectedFirstIndex = way.key().equals(selectedWay)
                            ? way.nodeKeys().indexOf(selectedFirst) : -1;
                    int selectedLastIndex = way.key().equals(selectedWay)
                            ? way.nodeKeys().indexOf(selectedLast) : -1;
                    for (int index = 1; index < way.nodeKeys().size(); index++) {
                        PrimitiveKey firstKey = way.nodeKeys().get(index - 1);
                        PrimitiveKey secondKey = way.nodeKeys().get(index);
                        MetricPoint first = metric(after, firstKey, evidence);
                        MetricPoint second = metric(after, secondKey, evidence);
                        boolean insideSelectedRange = selectedFirstIndex >= 0
                                && selectedLastIndex > selectedFirstIndex
                                && index - 1 >= selectedFirstIndex
                                && index - 1 < selectedLastIndex;
                        segments.add(new Segment(way.key(), index - 1, firstKey, secondKey,
                                first, second, segmentChanged(before, way.key(), firstKey,
                                        secondKey, after), insideSelectedRange));
                    }
                });
        Set<String> findings = new LinkedHashSet<>();
        for (int firstIndex = 0; firstIndex < segments.size(); firstIndex++) {
            Segment first = segments.get(firstIndex);
            for (int secondIndex = firstIndex + 1; secondIndex < segments.size(); secondIndex++) {
                Segment second = segments.get(secondIndex);
                if (!first.changed() && !second.changed()) {
                    continue;
                }
                boolean sameWay = first.wayKey().equals(second.wayKey());
                boolean adjacent = sameWay && Math.abs(first.index() - second.index()) == 1;
                if (adjacent) {
                    boolean selectedBoundary = first.wayKey().equals(selectedWay)
                            && first.insideSelectedRange() != second.insideSelectedRange();
                    boolean changedIncidentContinuation = !first.wayKey().equals(selectedWay)
                            && (first.changed() || second.changed());
                    if ((selectedBoundary || changedIncidentContinuation)
                            && continuationReverses(first, second)) {
                        findings.add("final-topology:CONTINUATION");
                    }
                    continue;
                }
                TopologyDefect defect = classify(first.first(), first.second(),
                        second.first(), second.second());
                if (defect == TopologyDefect.NONE) {
                    continue;
                }
                boolean sharedEndpoint = first.firstKey().equals(second.firstKey())
                        || first.firstKey().equals(second.secondKey())
                        || first.secondKey().equals(second.firstKey())
                        || first.secondKey().equals(second.secondKey());
                if (defect == TopologyDefect.VERTEX_TOUCH && sharedEndpoint) {
                    continue;
                }
                findings.add("final-topology:" + defect.name());
            }
        }
        return List.copyOf(findings);
    }

    private static MetricPoint metric(Map<PrimitiveKey, DetachedPrimitive> values,
            PrimitiveKey nodeKey, EvidenceSnapshot evidence) {
        DetachedPrimitive primitive = values.get(nodeKey);
        if (!(primitive instanceof DetachedNode node)) {
            throw new IllegalArgumentException("Final topology references an absent node");
        }
        return evidence.coordinateFrame().toMetric(node.coordinate());
    }

    private static boolean segmentChanged(NetworkSnapshot before, PrimitiveKey wayKey,
            PrimitiveKey firstKey, PrimitiveKey secondKey,
            Map<PrimitiveKey, DetachedPrimitive> after) {
        DetachedPrimitive beforePrimitive = before.primitives().get(wayKey);
        if (!(beforePrimitive instanceof DetachedWay beforeWay)) {
            return true;
        }
        boolean sameEdge = false;
        for (int index = 1; index < beforeWay.nodeKeys().size(); index++) {
            if (beforeWay.nodeKeys().get(index - 1).equals(firstKey)
                    && beforeWay.nodeKeys().get(index).equals(secondKey)) {
                sameEdge = true;
                break;
            }
        }
        if (!sameEdge) {
            return true;
        }
        return !java.util.Objects.equals(before.primitives().get(firstKey), after.get(firstKey))
                || !java.util.Objects.equals(before.primitives().get(secondKey), after.get(secondKey));
    }

    private static boolean continuationReverses(Segment first, Segment second) {
        Segment earlier = first.index() < second.index() ? first : second;
        Segment later = earlier == first ? second : first;
        return continuationReverses(earlier.first(), earlier.second(), later.second());
    }

    static boolean continuationReverses(MetricPoint before, MetricPoint boundary,
            MetricPoint after) {
        double ax = boundary.xMeters() - before.xMeters();
        double ay = boundary.yMeters() - before.yMeters();
        double bx = after.xMeters() - boundary.xMeters();
        double by = after.yMeters() - boundary.yMeters();
        double scale = Math.hypot(ax, ay) * Math.hypot(bx, by);
        return scale <= 1.0e-12 || ax * bx + ay * by < -0.25 * scale;
    }

    private static TopologyDefect classify(MetricPoint a, MetricPoint b,
            MetricPoint c, MetricPoint d) {
        double abC = orientation(a, b, c);
        double abD = orientation(a, b, d);
        double cdA = orientation(c, d, a);
        double cdB = orientation(c, d, b);
        double epsilon = 1.0e-8;
        boolean collinear = Math.abs(abC) <= epsilon && Math.abs(abD) <= epsilon
                && Math.abs(cdA) <= epsilon && Math.abs(cdB) <= epsilon;
        if (collinear) {
            double overlap = Math.min(Math.max(a.xMeters(), b.xMeters()),
                            Math.max(c.xMeters(), d.xMeters()))
                    - Math.max(Math.min(a.xMeters(), b.xMeters()),
                            Math.min(c.xMeters(), d.xMeters()));
            if (Math.abs(a.xMeters() - b.xMeters()) < Math.abs(a.yMeters() - b.yMeters())) {
                overlap = Math.min(Math.max(a.yMeters(), b.yMeters()),
                                Math.max(c.yMeters(), d.yMeters()))
                        - Math.max(Math.min(a.yMeters(), b.yMeters()),
                                Math.min(c.yMeters(), d.yMeters()));
            }
            return overlap > epsilon ? TopologyDefect.COLLINEAR_OVERLAP
                    : overlap >= -epsilon ? TopologyDefect.VERTEX_TOUCH : TopologyDefect.NONE;
        }
        if (abC * abD < -epsilon * epsilon && cdA * cdB < -epsilon * epsilon) {
            return TopologyDefect.CROSSING;
        }
        if (pointOnSegment(a, b, c, epsilon) || pointOnSegment(a, b, d, epsilon)
                || pointOnSegment(c, d, a, epsilon) || pointOnSegment(c, d, b, epsilon)) {
            return TopologyDefect.VERTEX_TOUCH;
        }
        return TopologyDefect.NONE;
    }

    private static boolean pointOnSegment(MetricPoint a, MetricPoint b,
            MetricPoint point, double epsilon) {
        return Math.abs(orientation(a, b, point)) <= epsilon
                && point.xMeters() >= Math.min(a.xMeters(), b.xMeters()) - epsilon
                && point.xMeters() <= Math.max(a.xMeters(), b.xMeters()) + epsilon
                && point.yMeters() >= Math.min(a.yMeters(), b.yMeters()) - epsilon
                && point.yMeters() <= Math.max(a.yMeters(), b.yMeters()) + epsilon;
    }

    private static double orientation(MetricPoint a, MetricPoint b, MetricPoint c) {
        return (b.xMeters() - a.xMeters()) * (c.yMeters() - a.yMeters())
                - (b.yMeters() - a.yMeters()) * (c.xMeters() - a.xMeters());
    }

    private static ValidationReport validation(FinalGeometryEvaluator.Result quality,
            JunctionPolicy junctionPolicy, List<String> topologyFindings) {
        ValidationReport.Disposition disposition = switch (quality.disposition()) {
            case APPLICABLE -> ValidationReport.Disposition.APPLICABLE;
            case REVIEW_REQUIRED -> ValidationReport.Disposition.REVIEW_REQUIRED;
            case HARD_BLOCKED -> ValidationReport.Disposition.HARD_BLOCKED;
        };
        List<String> findings = new ArrayList<>(quality.findings().stream()
            .map(finding -> "modern-final:" + finding.code().name()).distinct().toList());
        findings.addAll(topologyFindings);
        if (!topologyFindings.isEmpty()) {
            disposition = ValidationReport.Disposition.HARD_BLOCKED;
        }
        if (junctionPolicy != JunctionPolicy.FIXED) {
            if (disposition != ValidationReport.Disposition.HARD_BLOCKED) {
                disposition = ValidationReport.Disposition.REVIEW_REQUIRED;
            }
            findings.add("network-review-required");
        }
        return new ValidationReport(disposition, findings);
    }

    private static final class TopologyConversion {
        private final Map<PrimitiveKey, TopologyNetwork.Id> ids = new LinkedHashMap<>();
        private final Map<TopologyNetwork.Id, PrimitiveKey> keys = new LinkedHashMap<>();
        private final EvidenceSnapshot evidence;
        private final Map<PrimitiveKey, DetachedPrimitive> sourceValues;
        private long nextLocal;

        private TopologyConversion(Map<PrimitiveKey, DetachedPrimitive> values,
                EvidenceSnapshot evidence) {
            this.evidence = evidence;
            this.sourceValues = values;
            long local = 1L;
            for (PrimitiveKey key : values.keySet().stream().sorted().toList()) {
                TopologyNetwork.Id id = key.identityKind() == PrimitiveKey.IdentityKind.OSM_UNIQUE
                        ? TopologyNetwork.Id.existing(type(key.type()), key.id())
                        : TopologyNetwork.Id.planned(type(key.type()), local++);
                ids.put(key, id);
                keys.put(id, key);
            }
            nextLocal = values.keySet().stream()
                    .filter(key -> key.identityKind() == PrimitiveKey.IdentityKind.PLAN_LOCAL)
                    .mapToLong(PrimitiveKey::id).max().orElse(0L) + 1L;
        }

        private TopologyNetwork.Id id(PrimitiveKey key) {
            TopologyNetwork.Id id = ids.get(key);
            if (id == null) {
                throw new IllegalArgumentException("Topology identity is outside the captured plan: " + key);
            }
            return id;
        }

        private TopologyNetwork toTopology() {
            List<TopologyNetwork.Node> nodes = new ArrayList<>();
            List<TopologyNetwork.Way> ways = new ArrayList<>();
            List<TopologyNetwork.Relation> relations = new ArrayList<>();
            ids.forEach((key, id) -> {
                DetachedPrimitive primitive = value(key);
                if (primitive instanceof DetachedNode node) {
                    MetricPoint point = evidence.coordinateFrame().toMetric(node.coordinate());
                    nodes.add(new TopologyNetwork.Node(id,
                            new TopologyNetwork.Point(point.xMeters(), point.yMeters()), node.tags()));
                } else if (primitive instanceof DetachedWay way) {
                    ways.add(new TopologyNetwork.Way(id,
                            way.nodeKeys().stream().map(this::id).toList(), way.tags()));
                } else if (primitive instanceof DetachedRelation relation) {
                    relations.add(new TopologyNetwork.Relation(id, relation.members().stream()
                            .map(member -> new TopologyNetwork.RelationMember(
                                    id(member.memberKey()), member.role())).toList(),
                            relation.tags(), TopologyNetwork.Completeness.COMPLETE));
                }
            });
            return new TopologyNetwork(nodes, ways, relations);
        }

        private DetachedPrimitive value(PrimitiveKey key) {
            return sourceValues.get(key);
        }

        private Map<PrimitiveKey, DetachedPrimitive> toDetached(TopologyNetwork topology,
                Map<PrimitiveKey, DetachedPrimitive> base) {
            Map<PrimitiveKey, DetachedPrimitive> result = new LinkedHashMap<>();
            topology.nodes().values().stream().sorted(java.util.Comparator.comparing(TopologyNetwork.Node::id))
                    .forEach(node -> {
                        PrimitiveKey key = key(node.id());
                        DetachedPrimitive oldValue = base.get(key);
                        if (oldValue instanceof DetachedNode old) {
                            MetricPoint oldMetric = evidence.coordinateFrame().toMetric(old.coordinate());
                            if (Double.compare(oldMetric.xMeters(), node.point().xMeters()) == 0
                                    && Double.compare(oldMetric.yMeters(), node.point().yMeters()) == 0
                                    && old.tags().equals(node.tags())) {
                                result.put(key, old);
                                return;
                            }
                        }
                        GeographicPoint coordinate = evidence.coordinateFrame().toGeographic(
                                new MetricPoint(node.point().xMeters(), node.point().yMeters()));
                        boolean oldModified = oldValue instanceof DetachedNode old && old.modified();
                        boolean changed = !(oldValue instanceof DetachedNode old)
                                || !old.coordinate().equals(coordinate) || !old.tags().equals(node.tags());
                        result.put(key, new DetachedNode(key, coordinate, node.tags(), false,
                                oldModified || changed));
                    });
            topology.ways().values().stream().sorted(java.util.Comparator.comparing(TopologyNetwork.Way::id))
                    .forEach(way -> {
                        PrimitiveKey key = key(way.id());
                        List<PrimitiveKey> nodeKeys = way.nodeIds().stream().map(this::key).toList();
                        DetachedPrimitive oldValue = base.get(key);
                        if (oldValue instanceof DetachedWay old
                                && old.nodeKeys().equals(nodeKeys) && old.tags().equals(way.tags())) {
                            result.put(key, old);
                            return;
                        }
                        boolean oldModified = oldValue instanceof DetachedWay old && old.modified();
                        boolean changed = !(oldValue instanceof DetachedWay old)
                                || !old.nodeKeys().equals(nodeKeys) || !old.tags().equals(way.tags());
                        result.put(key, new DetachedWay(key, nodeKeys, way.tags(), false,
                                oldModified || changed));
                    });
            topology.relations().values().stream()
                    .sorted(java.util.Comparator.comparing(TopologyNetwork.Relation::id))
                    .forEach(relation -> {
                        PrimitiveKey key = key(relation.id());
                        List<DetachedRelationMember> members = relation.members().stream()
                                .map(member -> new DetachedRelationMember(
                                        key(member.memberId()), member.role())).toList();
                        DetachedPrimitive oldValue = base.get(key);
                        if (oldValue instanceof DetachedRelation old
                                && old.members().equals(members) && old.tags().equals(relation.tags())) {
                            result.put(key, old);
                            return;
                        }
                        boolean oldModified = oldValue instanceof DetachedRelation old && old.modified();
                        boolean changed = !(oldValue instanceof DetachedRelation old)
                                || !old.members().equals(members) || !old.tags().equals(relation.tags());
                        result.put(key, new DetachedRelation(key, members, relation.tags(), false,
                                oldModified || changed));
                    });
            return result;
        }

        private PrimitiveKey key(TopologyNetwork.Id id) {
            PrimitiveKey known = keys.get(id);
            if (known != null) {
                return known;
            }
            PrimitiveKey created = PrimitiveKey.planned(type(id.type()), nextLocal++);
            keys.put(id, created);
            ids.put(created, id);
            return created;
        }

        private static TopologyNetwork.PrimitiveType type(PrimitiveKey.Type type) {
            return TopologyNetwork.PrimitiveType.valueOf(type.name());
        }

        private static PrimitiveKey.Type type(TopologyNetwork.PrimitiveType type) {
            return PrimitiveKey.Type.valueOf(type.name());
        }
    }
}
