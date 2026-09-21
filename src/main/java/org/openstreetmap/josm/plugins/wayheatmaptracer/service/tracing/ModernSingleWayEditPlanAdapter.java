package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedRelation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedRelationMember;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
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
        DetachedWay selected = requireSupportedBoundary(request, before);
        List<PrimitiveKey> replacement = replacementNodes(route, request, evidence, before, selected);

        Map<PrimitiveKey, DetachedPrimitive> afterValues = new LinkedHashMap<>(before.primitives());
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
        ValidationReport validation = validation(route.quality(), request.permissions().junctionPolicy());
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
        boolean hardFinding = route.quality().findings().stream().anyMatch(
            finding -> finding.severity() == FinalGeometryEvaluator.Severity.HARD_BLOCK);
        if (route.quality().disposition() == FinalGeometryEvaluator.Disposition.HARD_BLOCKED
                || hardFinding
                || route.quality().disposition() == FinalGeometryEvaluator.Disposition.APPLICABLE
                    && !route.quality().findings().isEmpty()) {
            throw new IllegalArgumentException("Hard-blocked or inconsistent final route cannot form a plan");
        }
    }

    private static DetachedWay requireSupportedBoundary(
            TraceRequest request, NetworkSnapshot before) {
        ClosureDescriptor closure = before.closure();
        if (before.role() != SnapshotRole.CAPTURED_BEFORE
                || request.engine() != TrackerMode.PROBABILISTIC
                    && request.engine() != TrackerMode.CORRIDOR_AWARE
                || request.geometryMode() != AlignmentMode.PRECISE_SHAPE
                || request.permissions().widerDiscovery()
                || request.permissions().junctionPolicy() == JunctionPolicy.LEGACY_BOUNDED_MOVE
                    && request.permissions().reconstructIncidentWays()
                || request.permissions().ordinaryRadiusMeters()
                    != request.permissions().maximumDiscoveryRadiusMeters()
                || closure.scope() != ClosureDescriptor.Scope.EDIT_COMPONENT
                || !closure.removableExistingNodeKeys().isEmpty()
                || !closure.mayCreateNodes()) {
            throw new IllegalArgumentException(
                "Route exceeds the supported bounded edit-plan boundary");
        }
        DetachedPrimitive primitive = before.primitives().get(request.selectedWayKey());
        if (!(primitive instanceof DetachedWay selected)
                || request.selectedRange().lastIndex() >= selected.nodeKeys().size()
                || new HashSet<>(selected.nodeKeys()).size() != selected.nodeKeys().size()) {
            throw new IllegalArgumentException("Selected way occurrence identity is incomplete or repeated");
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
        if (!authorizedNodes.containsAll(selectedNodes)
                || request.permissions().junctionPolicy() == JunctionPolicy.FIXED
                    && !closure.protectedExistingNodeKeys().containsAll(selectedNodes)
                || request.permissions().junctionPolicy() == JunctionPolicy.FIXED
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
                if (!existing.wayKey().equals(request.selectedWayKey())
                        || existing.originalOccurrenceIndex() != expectedOccurrence
                        || expectedOccurrence > request.selectedRange().lastIndex()
                        || !selected.nodeKeys().get(expectedOccurrence).equals(existing.nodeKey())) {
                    throw new IllegalArgumentException(
                        "Final route existing occurrences are missing, duplicated, or reordered");
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
                expectedOccurrence++;
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

    private static ValidationReport validation(FinalGeometryEvaluator.Result quality,
            JunctionPolicy junctionPolicy) {
        ValidationReport.Disposition disposition = switch (quality.disposition()) {
            case APPLICABLE -> ValidationReport.Disposition.APPLICABLE;
            case REVIEW_REQUIRED -> ValidationReport.Disposition.REVIEW_REQUIRED;
            case HARD_BLOCKED -> throw new IllegalArgumentException(
                "Hard-blocked final route cannot form a plan");
        };
        List<String> findings = new ArrayList<>(quality.findings().stream()
            .map(finding -> "modern-final:" + finding.code().name()).distinct().toList());
        if (junctionPolicy != JunctionPolicy.FIXED) {
            disposition = ValidationReport.Disposition.REVIEW_REQUIRED;
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
