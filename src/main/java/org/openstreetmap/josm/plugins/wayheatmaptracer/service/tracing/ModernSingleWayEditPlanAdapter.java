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

/**
 * Builds the deliberately bounded selected-way edit plan from one detached common-pipeline route.
 *
 * <p>This adapter accepts only the current A/B Precise Shape capture contract: fixed selected
 * occurrences, no removals or movement, no incident-way/relation authority, and deterministic
 * plan-local inserted shape nodes. It has no live JOSM dependency and performs no mutation.</p>
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
        for (int index = 0; index < route.pointIds().size(); index++) {
            FinalRoutePointId pointId = route.pointIds().get(index);
            MetricPoint metric = route.assignments().get(pointId);
            GeographicPoint geographic = evidence.coordinateFrame().toGeographic(metric);
            if (pointId instanceof GeneratedCandidatePoint generated) {
                PrimitiveKey planned = plannedKey(generated);
                afterValues.put(planned,
                    new DetachedNode(planned, geographic, Map.of(), false, true));
            }
        }
        boolean wayChanged = !selected.nodeKeys().equals(replacement);
        afterValues.put(selected.key(), new DetachedWay(selected.key(), replacement,
            selected.tags(), false, selected.modified() || wayChanged));
        List<GeographicPoint> preview = replacement.stream()
            .map(key -> ((DetachedNode) afterValues.get(key)).coordinate()).toList();
        NetworkSnapshot after = new NetworkSnapshot(
            before.snapshotId() + ":proposed:" + route.hypothesis().id(),
            SnapshotRole.PROPOSED_AFTER, before.datasetIdentity(), before.sourceGeneration(),
            before.closure(), afterValues, proposedWatches(before, afterValues));
        ValidationReport validation = validation(route.quality());
        return new AlignmentEditPlan(request.selectedWayKey(), request.selectedRange(),
            before, after, evidence.coordinateFrame(), request.permissions(),
            captured.settingsHash(), evidence.canonicalHash(), captured.parameterHash(),
            route.hypothesis().id(),
            Map.of(request.selectedWayKey(), List.copyOf(preview)), validation);
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
                || request.permissions().junctionPolicy() != JunctionPolicy.FIXED
                || request.permissions().reconstructIncidentWays()
                || request.permissions().ordinaryRadiusMeters()
                    != request.permissions().maximumDiscoveryRadiusMeters()
                || closure.scope() != ClosureDescriptor.Scope.EDIT_COMPONENT
                || !closure.editableExistingKeys().equals(Set.of(request.selectedWayKey()))
                || !closure.editableWayOccurrences().equals(
                    Map.of(request.selectedWayKey(), List.of(request.selectedRange())))
                || !closure.movableExistingNodeKeys().isEmpty()
                || !closure.removableExistingNodeKeys().isEmpty()
                || !closure.mayCreateNodes()) {
            throw new IllegalArgumentException(
                "Route exceeds the supported fixed single-way edit boundary");
        }
        DetachedPrimitive primitive = before.primitives().get(request.selectedWayKey());
        if (!(primitive instanceof DetachedWay selected)
                || request.selectedRange().lastIndex() >= selected.nodeKeys().size()
                || new HashSet<>(selected.nodeKeys()).size() != selected.nodeKeys().size()) {
            throw new IllegalArgumentException("Selected way occurrence identity is incomplete or repeated");
        }
        List<PrimitiveKey> selectedNodes = selected.nodeKeys().subList(
            request.selectedRange().firstIndex(), request.selectedRange().lastIndex() + 1);
        if (!closure.protectedExistingNodeKeys().containsAll(selectedNodes)) {
            throw new IllegalArgumentException(
                "Every supported selected occurrence must be a fixed protected anchor");
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
                if (captured == null || !captured.equals(route.assignments().get(id))) {
                    throw new IllegalArgumentException(
                        "Fixed existing occurrence assignment changed or is missing");
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

    private static ValidationReport validation(FinalGeometryEvaluator.Result quality) {
        ValidationReport.Disposition disposition = switch (quality.disposition()) {
            case APPLICABLE -> ValidationReport.Disposition.APPLICABLE;
            case REVIEW_REQUIRED -> ValidationReport.Disposition.REVIEW_REQUIRED;
            case HARD_BLOCKED -> throw new IllegalArgumentException(
                "Hard-blocked final route cannot form a plan");
        };
        List<String> findings = quality.findings().stream()
            .map(finding -> "modern-final:" + finding.code().name()).distinct().toList();
        return new ValidationReport(disposition, findings);
    }
}
