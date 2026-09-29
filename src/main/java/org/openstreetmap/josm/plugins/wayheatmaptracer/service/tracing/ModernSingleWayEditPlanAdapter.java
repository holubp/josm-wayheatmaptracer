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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.ManualJunctionEligibility;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
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
        MANUAL_JUNCTION,
        FINAL_TOPOLOGY_CROSSING,
        FINAL_TOPOLOGY_VERTEX_TOUCH,
        FINAL_TOPOLOGY_COLLINEAR_OVERLAP,
        FINAL_TOPOLOGY_CONTINUATION,
        FINAL_GEOMETRY_BLOCKED,
        PLAN_UNAVAILABLE
    }

    /** Exact immutable plan when inspectable, plus its typed Apply availability. */
    public record Assessment(Optional<AlignmentEditPlan> plan,
            ApplyAvailability availability, String detail,
            ManualJunctionEligibility.Reason manualReason,
            ManualJunctionEligibility.Reason junctionReason) {
        public Assessment(Optional<AlignmentEditPlan> plan,
                ApplyAvailability availability, String detail) {
            this(plan, availability, detail, null, null);
        }

        public Assessment(Optional<AlignmentEditPlan> plan,
                ApplyAvailability availability, String detail,
                ManualJunctionEligibility.Reason manualReason) {
            this(plan, availability, detail, manualReason, manualReason);
        }

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
                    || inspectable != plan.isPresent()
                    || manualReason != null && availability != ApplyAvailability.MANUAL_JUNCTION
                    || manualReason != null && manualReason != junctionReason
                    || junctionReason != null && (junctionReason == ManualJunctionEligibility.Reason.NO_JUNCTION
                            || junctionReason == ManualJunctionEligibility.Reason.SIMPLE_T)) {
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
        if (computed != null && computed.partitioned()) {
            throw new IllegalArgumentException(
                    "Partitioned preview requires the fixed-interval plan composer");
        }
        if (computed == null || computed.captured() == null || computed.pipeline() == null
                || routeIndex < 0 || routeIndex >= computed.pipeline().routes().size()) {
            return unavailable(ApplyAvailability.PLAN_UNAVAILABLE,
                    "The final candidate selection is incomplete");
        }
        ModernTracePipeline.Route route = computed.pipeline().routes().get(routeIndex);
        ManualJunctionEligibility.Decision junction = computed.request().permissions().junctionPolicy()
                != JunctionPolicy.FIXED ? junctionDecision(computed.captured()) : null;
        boolean manual = junction != null && junction.manualOnly();
        if (manual && !canSlideOutside(junction)) {
            return unavailable(ApplyAvailability.MANUAL_JUNCTION, junction.manualInstruction(),
                    junction.reason());
        }
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
                        "The exact final preview is blocked by " + topology.name()
                                + (manual ? " " + junction.manualInstruction() : ""),
                        null, manual ? junction.reason() : null);
            }
            if (plan.validation().disposition() == ValidationReport.Disposition.HARD_BLOCKED) {
                return new Assessment(Optional.of(plan), ApplyAvailability.FINAL_GEOMETRY_BLOCKED,
                        "The exact final preview is blocked by final geometry validation"
                                + (manual ? ". " + junction.manualInstruction() : ""),
                        null, manual ? junction.reason() : null);
            }
            return new Assessment(Optional.of(plan), ApplyAvailability.PLAN_AVAILABLE,
                    manual ? "Exact outside-junction plan available; junction stays fixed"
                            : "Exact immutable plan available", null,
                    manual ? junction.reason() : null);
        } catch (IllegalArgumentException failure) {
            boolean missingReceiverEvidence = junction != null && junction.automaticallyEligible()
                    && failure instanceof IncidentWayReconstructor.MissingEvidenceException;
            if (missingReceiverEvidence) {
                ManualJunctionEligibility.Reason reason = ManualJunctionEligibility.Reason
                        .MISSING_RECEIVER_EVIDENCE;
                return unavailable(ApplyAvailability.MANUAL_JUNCTION,
                        failure.getMessage() + " " + new ManualJunctionEligibility.Decision(reason,
                                junction.junction(), junction.receiver(), junction.affectedNodes())
                                .manualInstruction(), reason);
            }
            return unavailable(manual ? ApplyAvailability.MANUAL_JUNCTION
                            : ApplyAvailability.PLAN_UNAVAILABLE,
                    manual ? failure.getMessage() + " " + junction.manualInstruction()
                            : failure.getMessage(), manual ? junction.reason() : null);
        }
    }

    private static Assessment unavailable(ApplyAvailability availability, String detail) {
        return unavailable(availability, detail, null);
    }

    private static Assessment unavailable(ApplyAvailability availability, String detail,
            ManualJunctionEligibility.Reason manualReason) {
        return new Assessment(Optional.empty(), availability,
                detail == null || detail.isBlank() ? availability.name() : detail, manualReason);
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
        ManualJunctionEligibility.Decision junction = request.permissions().junctionPolicy()
                != JunctionPolicy.FIXED ? junctionDecision(captured) : null;
        boolean manual = junction != null && junction.manualOnly();
        if (manual && !canSlideOutside(junction)) {
            throw new IllegalArgumentException(junction.manualInstruction());
        }
        DetachedWay selected = requireSupportedBoundary(request, before, captured.cleanup());
        if (manual) {
            route = retainFrozenManualArm(route, computed, junction);
        }
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
        if (manual) {
            requireSafeManualOutside(before, afterValues, selected, junction, evidence,
                    computed.options().fieldName());
        }
        NetworkSnapshot after = new NetworkSnapshot(
            before.snapshotId() + ":proposed:" + route.hypothesis().id(),
            SnapshotRole.PROPOSED_AFTER, before.datasetIdentity(), before.sourceGeneration(),
            before.closure(), afterValues, proposedWatches(before, afterValues));
        Map<PrimitiveKey, List<GeographicPoint>> preview = finalPreviewWays(before, afterValues);
        List<String> topologyFindings = new ArrayList<>(finalTopologyFindings(before, afterValues,
                request.selectedWayKey(), request.selectedRange(), evidence));
        if (!sharedJunctions.isEmpty()
                && request.permissions().junctionPolicy() == JunctionPolicy.REATTACH) {
            topologyFindings.addAll(unsupportedSelectedExtensions(before, afterValues, route,
                    request, evidence, computed.options().fieldName(), sharedJunctions));
        }
        ValidationReport validation = validation(route.quality(),
                request.permissions().junctionPolicy(), topologyFindings);
        return new AlignmentEditPlan(request.selectedWayKey(), request.selectedRange(),
            before, after, evidence.coordinateFrame(), request.permissions(),
            captured.settingsHash(), evidence.canonicalHash(), captured.parameterHash(),
            route.hypothesis().id(), preview, validation);
    }

    /** Builds one exact plan from ordered, candidate-owned interval geometry. */
    AlignmentEditPlan adaptComposite(IntervalTraceBatch batch, List<FinalRoutePointId> ids,
            Map<FinalRoutePointId, MetricPoint> assignments, String identity,
            List<String> routeFindings) {
        TraceRequest request = batch.fullRequest();
        EvidenceSnapshot evidence = batch.evidence();
        NetworkSnapshot before = batch.network();
        if (ids == null || ids.size() < 2 || assignments == null
                || !assignments.keySet().equals(new LinkedHashSet<>(ids))
                || identity == null || identity.isBlank() || routeFindings == null
                || !request.evidenceContentHash().equals(evidence.canonicalHash())
                || !request.networkContentHash().equals(before.canonicalHash())) {
            throw new IllegalArgumentException("Composite candidate and frozen inputs do not match");
        }
        DetachedWay selected = requireCompositeBoundary(request, before,
                batch.options().cleanup(), ids, assignments, evidence,
                batch.partition());
        List<PrimitiveKey> replacement = replacementNodes(ids, assignments, identity,
                request, evidence, before, selected, true);
        Map<PrimitiveKey, DetachedPrimitive> afterValues = new LinkedHashMap<>(before.primitives());
        for (PrimitiveKey removable : before.closure().removableExistingNodeKeys()) {
            if (!replacement.contains(removable)) afterValues.remove(removable);
        }
        Set<PrimitiveKey> sharedJunctions = sharedMovableBoundaries(request, before, selected);
        Set<PrimitiveKey> deferredJunctions = request.permissions().junctionPolicy()
                == JunctionPolicy.REATTACH ? sharedJunctions : Set.of();
        for (FinalRoutePointId id : ids) {
            GeographicPoint geographic = evidence.coordinateFrame().toGeographic(assignments.get(id));
            if (id instanceof GeneratedCandidatePoint generated) {
                PrimitiveKey key = plannedKey(generated);
                afterValues.put(key, new DetachedNode(key, geographic, Map.of(), false, true));
            } else if (id instanceof ExistingWayNodeOccurrence existing
                    && before.closure().movableExistingNodeKeys().contains(existing.nodeKey())
                    && !deferredJunctions.contains(existing.nodeKey())) {
                DetachedNode old = (DetachedNode) before.primitives().get(existing.nodeKey());
                if (assignments.get(id).equals(
                        evidence.coordinateFrame().toMetric(old.coordinate()))) {
                    geographic = old.coordinate();
                }
                afterValues.put(existing.nodeKey(), new DetachedNode(existing.nodeKey(), geographic,
                        old.tags(), old.deleted(), old.modified() || !old.coordinate().equals(geographic)));
            }
        }
        boolean wayChanged = !selected.nodeKeys().equals(replacement);
        afterValues.put(selected.key(), new DetachedWay(selected.key(), replacement,
                selected.tags(), false, selected.modified() || wayChanged));
        if (!sharedJunctions.isEmpty()
                && request.permissions().junctionPolicy() == JunctionPolicy.REATTACH) {
            afterValues = applyReattachment(afterValues, before, ids, assignments,
                    request, evidence, sharedJunctions);
        }
        requireCompositeChangedSupport(before, afterValues, selected, evidence,
                batch.options().fieldName());
        NetworkSnapshot after = new NetworkSnapshot(before.snapshotId() + ":proposed:" + identity,
                SnapshotRole.PROPOSED_AFTER, before.datasetIdentity(), before.sourceGeneration(),
                before.closure(), afterValues, proposedWatches(before, afterValues));
        List<String> findings = new ArrayList<>(routeFindings);
        findings.addAll(finalTopologyFindings(before, afterValues, request.selectedWayKey(),
                request.selectedRange(), evidence));
        ValidationReport.Disposition disposition = findings.stream().anyMatch(finding ->
                finding.startsWith("final-topology:"))
                ? ValidationReport.Disposition.HARD_BLOCKED
                : findings.isEmpty() ? ValidationReport.Disposition.APPLICABLE
                        : ValidationReport.Disposition.REVIEW_REQUIRED;
        if (request.permissions().junctionPolicy() != JunctionPolicy.FIXED
                && disposition != ValidationReport.Disposition.HARD_BLOCKED) {
            disposition = ValidationReport.Disposition.REVIEW_REQUIRED;
            findings.add("network-review-required");
        }
        return new AlignmentEditPlan(request.selectedWayKey(), request.selectedRange(),
                before, after, evidence.coordinateFrame(), request.permissions(),
                request.settingsHash(), evidence.canonicalHash(), request.parameterHash(),
                identity, finalPreviewWays(before, afterValues),
                new ValidationReport(disposition, findings));
    }

    private static void requireCompositeChangedSupport(NetworkSnapshot before,
            Map<PrimitiveKey, DetachedPrimitive> after, DetachedWay source,
            EvidenceSnapshot evidence, String fieldName) {
        var field = evidence.fields().get(fieldName);
        if (field == null) throw new IllegalArgumentException("Composite image field is unavailable");
        double pitch = evidence.resolution().effectivePitchMeters();
        ImageCostField image = new ImageCostField(field, evidence.transform(),
                evidence.decisionRegion(), pitch);
        DetachedWay proposed = (DetachedWay) after.get(source.key());
        for (int i = 1; i < proposed.nodeKeys().size(); i++) {
            PrimitiveKey left = proposed.nodeKeys().get(i - 1);
            PrimitiveKey right = proposed.nodeKeys().get(i);
            if (!unchangedSelectedEdge(before, after, source, left, right)
                    && unsupportedBoundaryConnector(image, List.of(),
                            metric(after, left, evidence), metric(after, right, evidence), pitch)) {
                throw new CompositeLocalSupportException(left, right);
            }
        }
    }

    static final class CompositeLocalSupportException extends IllegalArgumentException {
        private final PrimitiveKey left;
        private final PrimitiveKey right;
        CompositeLocalSupportException(PrimitiveKey left, PrimitiveKey right) {
            super("Changed composite span lacks direct support");
            this.left = left;
            this.right = right;
        }
        PrimitiveKey left() { return left; }
        PrimitiveKey right() { return right; }
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

    private static ManualJunctionEligibility.Decision junctionDecision(
            LiveBPreviewService.Captured captured) {
        return captured.junctionDecision() != null ? captured.junctionDecision()
                : ManualJunctionEligibility.evaluate(captured.network(), captured.specification());
    }

    private static boolean canSlideOutside(ManualJunctionEligibility.Decision junction) {
        return junction.reason() == ManualJunctionEligibility.Reason.PARTICIPATING_RELATION
                || junction.reason() == ManualJunctionEligibility.Reason.AFFECTED_NODE_TAGGED
                || junction.reason() == ManualJunctionEligibility.Reason.AFFECTED_NODE_RELATION
                || junction.reason() == ManualJunctionEligibility.Reason.LEGACY_POLICY;
    }

    private static ModernTracePipeline.Route retainFrozenManualArm(ModernTracePipeline.Route route,
            LiveBPreviewService.Computed computed,
            ManualJunctionEligibility.Decision junction) {
        List<FinalRoutePointId> ids = route.pointIds();
        int first = -1;
        int last = -1;
        for (int index = 0; index < ids.size(); index++) {
            if (ids.get(index) instanceof ExistingWayNodeOccurrence existing
                    && junction.affectedNodes().contains(existing.nodeKey())) {
                first = first < 0 ? index : first;
                last = index;
            }
        }
        if (first < 0 || last <= first) {
            throw new IllegalArgumentException("Final route omits the fixed manual junction arm");
        }
        List<FinalRoutePointId> kept = new ArrayList<>();
        List<MetricPoint> positions = new ArrayList<>();
        List<ObservationOwnership> support = new ArrayList<>();
        Map<FinalRoutePointId, MetricPoint> assignments = new LinkedHashMap<>();
        Map<FinalRoutePointId, ObservationOwnership> ownership = new LinkedHashMap<>();
        Map<Integer, MetricPoint> protectedAssignments = new LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            FinalRoutePointId id = ids.get(index);
            if (index > first && index < last
                    && !(id instanceof ExistingWayNodeOccurrence existing
                            && junction.affectedNodes().contains(existing.nodeKey()))) {
                continue;
            }
            MetricPoint point = route.assignments().get(id);
            ObservationOwnership source = route.sourceOwnership().get(id);
            int outputIndex = kept.size();
            kept.add(id);
            positions.add(point);
            support.add(source);
            assignments.put(id, point);
            ownership.put(id, source);
            if (id instanceof ExistingWayNodeOccurrence existing
                    && computed.captured().network().closure().protectedExistingNodeKeys()
                            .contains(existing.nodeKey())) {
                protectedAssignments.put(outputIndex, point);
            }
        }
        if (kept.size() == ids.size()) {
            return route;
        }
        EvidenceSnapshot evidence = computed.evidence();
        double pitch = evidence.resolution().effectivePitchMeters();
        var field = evidence.fields().get(computed.options().fieldName());
        if (field == null) {
            throw new IllegalArgumentException("Final manual-junction image field is unavailable");
        }
        ImageCostField image = new ImageCostField(field, evidence.transform(),
                evidence.decisionRegion(), pitch);
        TraceHypothesis original = route.hypothesis();
        TraceHypothesis finalHypothesis = new TraceHypothesis(original.id(),
                original.branchSignature(), positions, support, original.objective(),
                original.posteriorProbability(), original.diagnostics());
        FinalGeometryEvaluator.Result quality = new FinalGeometryEvaluator().evaluate(
                new FinalGeometryEvaluator.Request(original.id(), positions, kept, image, pitch,
                        protectedAssignments, List.of(), route.geometryChanged(),
                        computed.pipeline().inference().status()
                                == TraceHypothesisSet.Status.AMBIGUOUS,
                        computed.pipeline().inference().alternativesTruncated(),
                        computed.pipeline().inference().status()
                                == TraceHypothesisSet.Status.RESOURCE_LIMIT));
        return new ModernTracePipeline.Route(route.rawHypothesis(), finalHypothesis, kept,
                assignments, ownership, quality, route.cleanupStatus(), route.geometryChanged());
    }

    private static void requireSafeManualOutside(NetworkSnapshot before,
            Map<PrimitiveKey, DetachedPrimitive> after, DetachedWay selected,
            ManualJunctionEligibility.Decision junction, EvidenceSnapshot evidence,
            String fieldName) {
        if (junction.affectedNodes().isEmpty()
                || !(after.get(selected.key()) instanceof DetachedWay proposed)) {
            throw new IllegalArgumentException("The manual junction footprint is incomplete");
        }
        for (PrimitiveKey node : junction.affectedNodes()) {
            if (!before.primitives().get(node).equals(after.get(node))) {
                throw new IllegalArgumentException("The manual junction footprint would move");
            }
        }
        for (Map.Entry<PrimitiveKey, DetachedPrimitive> entry : before.primitives().entrySet()) {
            if (entry.getValue() instanceof DetachedWay && !entry.getKey().equals(selected.key())
                    && !entry.getValue().equals(after.get(entry.getKey()))) {
                throw new IllegalArgumentException("An incident way would change at the manual junction");
            }
        }
        int first = -1;
        int last = -1;
        for (int index = 0; index < selected.nodeKeys().size(); index++) {
            if (junction.affectedNodes().contains(selected.nodeKeys().get(index))) {
                first = first < 0 ? index : first;
                last = index;
            }
        }
        if (first < 0 || last <= first) {
            throw new IllegalArgumentException("The selected manual junction arm has no fixed span");
        }
        int proposedFirst = proposed.nodeKeys().indexOf(selected.nodeKeys().get(first));
        int proposedLast = proposed.nodeKeys().indexOf(selected.nodeKeys().get(last));
        if (proposedFirst < 0 || proposedLast < proposedFirst
                || !proposed.nodeKeys().subList(proposedFirst, proposedLast + 1)
                        .equals(selected.nodeKeys().subList(first, last + 1))) {
            throw new IllegalArgumentException("The selected manual junction arm would change");
        }
        if (selected.equals(proposed) && selected.nodeKeys().stream()
                .allMatch(key -> before.primitives().get(key).equals(after.get(key)))) {
            throw new IllegalArgumentException("No safe slide remains outside the manual junction");
        }
        var field = evidence.fields().get(fieldName);
        if (field == null) {
            throw new IllegalArgumentException("Final connector source evidence is unavailable");
        }
        double pitch = evidence.resolution().effectivePitchMeters();
        ImageCostField image = new ImageCostField(field, evidence.transform(),
                evidence.decisionRegion(), pitch);
        if (first > 0 && (proposedFirst == 0 || unsupportedChangedConnector(before, after,
                selected, proposed.nodeKeys().get(proposedFirst - 1),
                proposed.nodeKeys().get(proposedFirst), evidence, image, pitch))) {
            throw new IllegalArgumentException("The final manual-junction connector lacks direct support");
        }
        if (last < selected.nodeKeys().size() - 1
                && (proposedLast == proposed.nodeKeys().size() - 1
                    || unsupportedChangedConnector(before, after, selected,
                            proposed.nodeKeys().get(proposedLast),
                            proposed.nodeKeys().get(proposedLast + 1), evidence, image, pitch))) {
            throw new IllegalArgumentException("The final manual-junction connector lacks direct support");
        }
    }

    private static boolean unsupportedChangedConnector(NetworkSnapshot before,
            Map<PrimitiveKey, DetachedPrimitive> after, DetachedWay selected,
            PrimitiveKey first, PrimitiveKey second, EvidenceSnapshot evidence,
            ImageCostField image, double pitch) {
        return !unchangedSelectedEdge(before, after, selected, first, second)
                && unsupportedBoundaryConnector(image, List.of(),
                        metric(after, first, evidence), metric(after, second, evidence), pitch);
    }

    private static DetachedWay requireSupportedBoundary(
            TraceRequest request, NetworkSnapshot before, GeometryCleanupConfig cleanup) {
        return requireSupportedBoundary(request, before, cleanup, false);
    }

    private static DetachedWay requireCompositeBoundary(TraceRequest request,
            NetworkSnapshot before, GeometryCleanupConfig cleanup,
            List<FinalRoutePointId> ids, Map<FinalRoutePointId, MetricPoint> assignments,
            EvidenceSnapshot evidence,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot
                    .SelectedWayIntervalPartitioner.Partition partition) {
        DetachedWay selected = (DetachedWay) before.primitives().get(request.selectedWayKey());
        if (selected == null || partition == null
                || !partition.selectedWayKey().equals(selected.key())
                || !partition.selectedRange().equals(request.selectedRange())) {
            throw new IllegalArgumentException("Composite original-node authority is unavailable");
        }
        Set<Integer> fixedOccurrences = new LinkedHashSet<>();
        for (var island : partition.fixedIslands()) {
            for (int index = island.range().firstIndex(); index <= island.range().lastIndex(); index++) {
                fixedOccurrences.add(index);
            }
        }
        int expected = request.selectedRange().firstIndex();
        for (FinalRoutePointId id : ids) {
            if (!(id instanceof ExistingWayNodeOccurrence occurrence)) continue;
            int index = occurrence.originalOccurrenceIndex();
            if (index < expected || index > request.selectedRange().lastIndex()
                    || java.util.stream.IntStream.range(expected, index).anyMatch(skipped ->
                            !before.closure().removableExistingNodeKeys()
                                    .contains(selected.nodeKeys().get(skipped)))
                    || !occurrence.wayKey().equals(selected.key())
                    || !occurrence.nodeKey().equals(selected.nodeKeys().get(index))
                    || !(before.primitives().get(occurrence.nodeKey()) instanceof DetachedNode node)
                    || (!before.closure().movableExistingNodeKeys().contains(occurrence.nodeKey())
                            || fixedOccurrences.contains(index))
                            && !evidence.coordinateFrame().toMetric(node.coordinate())
                                    .equals(assignments.get(id))) {
                throw new IllegalArgumentException(
                        "Composite original occurrence exceeds its captured movement authority");
            }
            expected = index + 1;
        }
        if (java.util.stream.IntStream.rangeClosed(expected,
                request.selectedRange().lastIndex()).anyMatch(skipped ->
                        !before.closure().removableExistingNodeKeys()
                                .contains(selected.nodeKeys().get(skipped)))) {
            throw new IllegalArgumentException("Composite plan omitted an original selected node");
        }
        return requireSupportedBoundary(request, before, cleanup, true);
    }

    private static DetachedWay requireSupportedBoundary(
            TraceRequest request, NetworkSnapshot before, GeometryCleanupConfig cleanup,
            boolean exactOriginalComposite) {
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
                || !exactOriginalComposite && request.geometryMode() == AlignmentMode.PRECISE_SHAPE
                    && request.permissions().junctionPolicy() == JunctionPolicy.FIXED
                    && !hasOrdinaryFixedPreciseAuthority(request, before, selectedNodes)) {
            throw new IllegalArgumentException(
                "Selected occurrence movement/protection authority is incomplete");
        }
        return selected;
    }

    /** Selected-way-only authority: protected boundaries and factual ordinary interior identities. */
    private static boolean hasOrdinaryFixedPreciseAuthority(TraceRequest request,
            NetworkSnapshot before, List<PrimitiveKey> selectedNodes) {
        ClosureDescriptor closure = before.closure();
        Set<PrimitiveKey> ordinary = new LinkedHashSet<>(closure.movableExistingNodeKeys());
        ordinary.addAll(closure.removableExistingNodeKeys());
        Set<PrimitiveKey> editable = new LinkedHashSet<>(ordinary);
        editable.add(request.selectedWayKey());
        if (!closure.editableExistingKeys().equals(editable)
                || !closure.protectedExistingNodeKeys().contains(selectedNodes.get(0))
                || !closure.protectedExistingNodeKeys().contains(selectedNodes.get(selectedNodes.size() - 1))
                || !new HashSet<>(selectedNodes.subList(1, selectedNodes.size() - 1)).containsAll(ordinary)) {
            return false;
        }
        for (PrimitiveKey key : ordinary) {
            if (!(before.primitives().get(key) instanceof DetachedNode node)
                    || node.deleted() || !node.tags().isEmpty()
                    || !before.incomingReferrerWatches().getOrDefault(key, Set.of())
                            .equals(Set.of(request.selectedWayKey()))) {
                return false;
            }
        }
        return true;
    }

    private static List<PrimitiveKey> replacementNodes(ModernTracePipeline.Route route,
            TraceRequest request, EvidenceSnapshot evidence, NetworkSnapshot before,
            DetachedWay selected) {
        return replacementNodes(route.pointIds(), route.assignments(), route.hypothesis().id(),
                request, evidence, before, selected, false);
    }

    private static List<PrimitiveKey> replacementNodes(List<FinalRoutePointId> ids,
            Map<FinalRoutePointId, MetricPoint> assignments, String identity,
            TraceRequest request, EvidenceSnapshot evidence, NetworkSnapshot before,
            DetachedWay selected, boolean composite) {
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
                if (captured == null || assignments.get(id) == null
                        || !movable && !captured.equals(assignments.get(id))) {
                    throw new IllegalArgumentException(
                        "Existing occurrence assignment exceeds its movement authority");
                }
                result.add(existing.nodeKey());
                expectedOccurrence = occurrence + 1;
            } else if (id instanceof GeneratedCandidatePoint generated) {
                if (!composite && !generated.candidateId().equals(identity)
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

    private record SelectedReceiverIntersection(MetricPoint point,
            PrimitiveKey selectedPredecessor, double routeDistanceMeters,
            int selectedSegment, int receiverSegment, PrimitiveKey receiverVertex) {
    }

    private static List<PrimitiveKey> splitReceiverSpan(NetworkSnapshot before,
            TopologyConversion conversion, List<TopologyNetwork.Id> receivers,
            PrimitiveKey junction) {
        if (receivers.size() != 2) {
            return null;
        }
        List<PrimitiveKey> combined = new ArrayList<>();
        for (int half = 0; half < 2; half++) {
            PrimitiveKey wayKey = conversion.key(receivers.get(half));
            if (!(before.primitives().get(wayKey) instanceof DetachedWay way)) {
                return null;
            }
            List<OccurrenceRange> ranges = before.closure().editableWayOccurrences().get(wayKey);
            if (ranges == null || ranges.size() != 1) {
                return null;
            }
            OccurrenceRange range = ranges.get(0);
            List<PrimitiveKey> local = new ArrayList<>(way.nodeKeys().subList(
                    range.firstIndex(), range.lastIndex() + 1));
            if (half == 0 && local.get(0).equals(junction)
                    || half == 1 && local.get(local.size() - 1).equals(junction)) {
                java.util.Collections.reverse(local);
            }
            if (half == 0 && !local.get(local.size() - 1).equals(junction)
                    || half == 1 && !local.get(0).equals(junction)) {
                return null;
            }
            combined.addAll(half == 0 ? local : local.subList(1, local.size()));
        }
        combined.remove(junction);
        return List.copyOf(combined);
    }

    /** Intersections of the final selected trace with directly reconstructed receiver spans. */
    private static List<SelectedReceiverIntersection> selectedReceiverIntersections(
            NetworkSnapshot before, ModernTracePipeline.Route route,
            Map<PrimitiveKey, DetachedPrimitive> evidenced, EvidenceSnapshot evidence,
            List<PrimitiveKey> receiving, PrimitiveKey junction, MetricPoint proposed) {
        return selectedReceiverIntersections(before, route.pointIds(), route.assignments(),
                evidenced, evidence, receiving, junction, proposed);
    }

    private static List<SelectedReceiverIntersection> selectedReceiverIntersections(
            NetworkSnapshot before, List<FinalRoutePointId> pointIds,
            Map<FinalRoutePointId, MetricPoint> assignments,
            Map<PrimitiveKey, DetachedPrimitive> evidenced, EvidenceSnapshot evidence,
            DetachedWay receiver, PrimitiveKey junction, MetricPoint proposed) {
        List<FinalRoutePointId> selected = pointIds.stream()
                .filter(id -> !(id instanceof ExistingWayNodeOccurrence occurrence
                        && occurrence.nodeKey().equals(junction)))
                .toList();
        List<SelectedReceiverIntersection> result = new ArrayList<>();
        List<OccurrenceRange> ranges = before.closure().editableWayOccurrences()
                .getOrDefault(receiver.key(), List.of());
        for (OccurrenceRange range : ranges) {
            List<PrimitiveKey> receiving = receiver.nodeKeys().subList(
                    range.firstIndex(), range.lastIndex() + 1).stream()
                    .filter(key -> !key.equals(junction)).toList();
            addSelectedReceiverIntersections(result, before, pointIds, assignments, selected,
                    evidenced, evidence, receiving, junction, proposed);
        }
        return bestIntersections(result);
    }

    private static List<SelectedReceiverIntersection> selectedReceiverIntersections(
            NetworkSnapshot before, List<FinalRoutePointId> pointIds,
            Map<FinalRoutePointId, MetricPoint> assignments,
            Map<PrimitiveKey, DetachedPrimitive> evidenced, EvidenceSnapshot evidence,
            List<PrimitiveKey> receiving, PrimitiveKey junction, MetricPoint proposed) {
        List<FinalRoutePointId> selected = pointIds.stream()
                .filter(id -> !(id instanceof ExistingWayNodeOccurrence occurrence
                        && occurrence.nodeKey().equals(junction)))
                .toList();
        List<SelectedReceiverIntersection> result = new ArrayList<>();
        addSelectedReceiverIntersections(result, before, pointIds, assignments, selected,
                evidenced, evidence, receiving, junction, proposed);
        return bestIntersections(result);
    }

    private static void addSelectedReceiverIntersections(
            List<SelectedReceiverIntersection> result, NetworkSnapshot before,
            List<FinalRoutePointId> pointIds,
            Map<FinalRoutePointId, MetricPoint> assignments,
            List<FinalRoutePointId> selected,
            Map<PrimitiveKey, DetachedPrimitive> evidenced, EvidenceSnapshot evidence,
            List<PrimitiveKey> receiving, PrimitiveKey junction, MetricPoint proposed) {
        for (int selectedIndex = 0; selectedIndex < selected.size(); selectedIndex++) {
            MetricPoint selectedStart = assignments.get(selected.get(selectedIndex));
            MetricPoint selectedEnd;
            if (selectedIndex + 1 < selected.size()) {
                selectedEnd = assignments.get(selected.get(selectedIndex + 1));
            } else if (pointIds.get(pointIds.size() - 1)
                    instanceof ExistingWayNodeOccurrence last
                    && last.nodeKey().equals(junction)) {
                double dx = proposed.xMeters() - selectedStart.xMeters();
                double dy = proposed.yMeters() - selectedStart.yMeters();
                double length = Math.hypot(dx, dy);
                if (!(length > 1.0e-9)) {
                    continue;
                }
                selectedEnd = new MetricPoint(proposed.xMeters() + 20.0 * dx / length,
                        proposed.yMeters() + 20.0 * dy / length);
            } else {
                continue;
            }
            for (int receiverIndex = 0; receiverIndex + 1 < receiving.size(); receiverIndex++) {
                MetricPoint receiverStart = evidence.coordinateFrame().toMetric(
                        ((DetachedNode) evidenced.get(receiving.get(receiverIndex))).coordinate());
                MetricPoint receiverEnd = evidence.coordinateFrame().toMetric(
                        ((DetachedNode) evidenced.get(receiving.get(receiverIndex + 1))).coordinate());
                MetricPoint crossing = segmentCrossing(selectedStart, selectedEnd,
                        receiverStart, receiverEnd);
                if (crossing == null || crossing.distanceTo(proposed) > 20.0
                        || !before.closure().editRegion().contains(crossing)) {
                    continue;
                }
                FinalRoutePointId predecessor = selected.get(selectedIndex);
                PrimitiveKey predecessorKey = predecessor instanceof ExistingWayNodeOccurrence existing
                        ? existing.nodeKey() : plannedKey((GeneratedCandidatePoint) predecessor);
                PrimitiveKey receiverVertex = arithmeticallySamePoint(crossing, receiverStart)
                        ? receiving.get(receiverIndex)
                        : arithmeticallySamePoint(crossing, receiverEnd)
                                ? receiving.get(receiverIndex + 1) : null;
                result.add(new SelectedReceiverIntersection(crossing, predecessorKey,
                        crossing.distanceTo(proposed), selectedIndex, receiverIndex,
                        receiverVertex));
            }
        }
    }

    private static List<SelectedReceiverIntersection> bestIntersections(
            List<SelectedReceiverIntersection> result) {
        result.sort(java.util.Comparator.comparingDouble(
                    SelectedReceiverIntersection::routeDistanceMeters)
                .thenComparingInt(SelectedReceiverIntersection::selectedSegment)
                .thenComparingInt(SelectedReceiverIntersection::receiverSegment));
        List<SelectedReceiverIntersection> distinct = new ArrayList<>();
        for (SelectedReceiverIntersection candidate : result) {
            boolean repeatedVertex = distinct.stream().anyMatch(existing ->
                    (candidate.point().equals(existing.point())
                        || candidate.receiverVertex() != null
                            && candidate.receiverVertex().equals(existing.receiverVertex())
                            && arithmeticallySamePoint(candidate.point(), existing.point()))
                    && Math.abs(candidate.selectedSegment() - existing.selectedSegment()) <= 1
                    && Math.abs(candidate.receiverSegment() - existing.receiverSegment()) <= 1);
            if (!repeatedVertex) {
                distinct.add(candidate);
            }
        }
        return List.copyOf(distinct.subList(0, Math.min(8, distinct.size())));
    }

    private static boolean arithmeticallySamePoint(MetricPoint first, MetricPoint second) {
        double magnitude = Math.max(1.0, Math.max(
                Math.max(Math.abs(first.xMeters()), Math.abs(first.yMeters())),
                Math.max(Math.abs(second.xMeters()), Math.abs(second.yMeters()))));
        return first.distanceTo(second) <= 64.0 * Math.ulp(magnitude);
    }

    private static MetricPoint segmentCrossing(MetricPoint a, MetricPoint b,
            MetricPoint c, MetricPoint d) {
        double abX = b.xMeters() - a.xMeters();
        double abY = b.yMeters() - a.yMeters();
        double cdX = d.xMeters() - c.xMeters();
        double cdY = d.yMeters() - c.yMeters();
        double denominator = abX * cdY - abY * cdX;
        if (Math.abs(denominator) < 1.0e-9) {
            return null;
        }
        double acX = c.xMeters() - a.xMeters();
        double acY = c.yMeters() - a.yMeters();
        double alongSelected = (acX * cdY - acY * cdX) / denominator;
        double alongReceiver = (acX * abY - acY * abX) / denominator;
        if (alongSelected < 0.0 || alongSelected > 1.0
                || alongReceiver < 0.0 || alongReceiver > 1.0) {
            return null;
        }
        return new MetricPoint(a.xMeters() + alongSelected * abX,
                a.yMeters() + alongSelected * abY);
    }

    private static Set<PrimitiveKey> sharedMovableBoundaries(TraceRequest request,
            NetworkSnapshot before, DetachedWay selected) {
        Set<PrimitiveKey> result = new LinkedHashSet<>();
        for (int occurrence = request.selectedRange().firstIndex();
                occurrence <= request.selectedRange().lastIndex(); occurrence++) {
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
        return applyReattachment(baseValues, before, route.pointIds(), route.assignments(),
                request, evidence, sharedJunctions);
    }

    private static Map<PrimitiveKey, DetachedPrimitive> applyReattachment(
            Map<PrimitiveKey, DetachedPrimitive> baseValues, NetworkSnapshot before,
            List<FinalRoutePointId> pointIds,
            Map<FinalRoutePointId, MetricPoint> assignments,
            TraceRequest request, EvidenceSnapshot evidence,
            Set<PrimitiveKey> sharedJunctions) {
        Map<PrimitiveKey, DetachedPrimitive> evidenced = request.permissions().reconstructIncidentWays()
                ? IncidentWayReconstructor.reconstruct(before, baseValues, evidence,
                        request.selectedWayKey(), sharedJunctions)
                : baseValues;
        TopologyConversion conversion = new TopologyConversion(before.primitives(), evidence);
        long firstAvailablePlanNodeId = baseValues.keySet().stream()
                .filter(key -> key.type() == PrimitiveKey.Type.NODE
                        && key.identityKind() == PrimitiveKey.IdentityKind.PLAN_LOCAL)
                .mapToLong(PrimitiveKey::id).max().orElse(0L) + 1L;
        conversion.reservePlanNodeKeysBefore(firstAvailablePlanNodeId);
        TopologyNetwork topology = conversion.toTopology();
        TopologyNetwork.Id selectedWay = conversion.id(request.selectedWayKey());
        List<JunctionCandidate> candidates = new ArrayList<>();
        Map<PrimitiveKey, MetricPoint> jointPositions = new LinkedHashMap<>();
        Map<PrimitiveKey, PrimitiveKey> selectedInsertionAfter = new LinkedHashMap<>();
        for (PrimitiveKey junction : sharedJunctions.stream().sorted().toList()) {
            ExistingWayNodeOccurrence occurrence = pointIds.stream()
                    .filter(ExistingWayNodeOccurrence.class::isInstance)
                    .map(ExistingWayNodeOccurrence.class::cast)
                    .filter(value -> value.nodeKey().equals(junction)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Final route omits a movable junction occurrence"));
            MetricPoint proposed = assignments.get(occurrence);
            List<TopologyNetwork.Id> receivers = topology.ways().values().stream()
                    .filter(way -> !way.id().equals(selectedWay)
                            && way.nodeIds().contains(conversion.id(junction)))
                    .map(TopologyNetwork.Way::id).sorted().toList();
            ReceiverPolicy receiverPolicy = request.permissions().reconstructIncidentWays()
                    ? ReceiverPolicy.RECONSTRUCT_INCIDENT : ReceiverPolicy.FROZEN_LOCUS;
            List<ReceiverGroup> groups;
            if (receiverPolicy == ReceiverPolicy.RECONSTRUCT_INCIDENT) {
                if (receivers.size() == 1
                        && before.primitives().get(conversion.key(receivers.get(0)))
                            instanceof DetachedWay receiver
                        && receiver.nodeKeys().indexOf(junction) > 0
                        && receiver.nodeKeys().indexOf(junction) < receiver.nodeKeys().size() - 1) {
                    List<SelectedReceiverIntersection> intersections =
                            selectedReceiverIntersections(before, pointIds, assignments,
                                    evidenced, evidence,
                                    receiver, junction, proposed);
                    if (!intersections.isEmpty()) {
                        SelectedReceiverIntersection intersection = uniqueIntersection(intersections);
                        proposed = intersection.point();
                        jointPositions.put(junction, proposed);
                        selectedInsertionAfter.put(junction, intersection.selectedPredecessor());
                        groups = List.of(new ReceiverGroup(receivers));
                    } else {
                        groups = List.of();
                    }
                } else if (receivers.size() == 2
                        && splitReceiverSpan(before, conversion, receivers, junction) != null) {
                    List<PrimitiveKey> receiving = splitReceiverSpan(before, conversion,
                            receivers, junction);
                    List<SelectedReceiverIntersection> intersections =
                            selectedReceiverIntersections(before, pointIds, assignments, evidenced,
                                    evidence, receiving, junction, proposed);
                    if (intersections.isEmpty()) {
                        groups = List.of();
                    } else {
                        SelectedReceiverIntersection intersection = uniqueIntersection(intersections);
                        proposed = intersection.point();
                        jointPositions.put(junction, proposed);
                        selectedInsertionAfter.put(junction, intersection.selectedPredecessor());
                        groups = List.of(new ReceiverGroup(receivers));
                    }
                } else {
                    groups = List.of();
                }
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
        Map<TopologyNetwork.Id, TopologyNetwork.Point> evidencedPositions = new LinkedHashMap<>();
        for (Map.Entry<PrimitiveKey, DetachedPrimitive> entry : evidenced.entrySet()) {
            if (!(entry.getValue() instanceof DetachedNode node)
                    || sharedJunctions.contains(entry.getKey())
                    || !before.primitives().containsKey(entry.getKey())
                    || before.primitives().get(entry.getKey()).equals(node)) {
                continue;
            }
            MetricPoint point = evidence.coordinateFrame().toMetric(node.coordinate());
            evidencedPositions.put(conversion.id(entry.getKey()),
                    new TopologyNetwork.Point(point.xMeters(), point.yMeters()));
        }
        JunctionReattachmentPlanner.ReattachmentRequest topologyRequest =
                new JunctionReattachmentPlanner.ReattachmentRequest(topology, candidates,
                        editableWays, new JunctionReattachmentPlanner.Bounds(
                                bounds[0], bounds[1], bounds[2], bounds[3]),
                        new JunctionReattachmentPlanner.Permissions(true,
                                request.permissions().reconstructIncidentWays(), 20.0),
                        Map.of(), Set.of(), Math.max(1.0e-6,
                        evidence.resolution().effectivePitchMeters() * 0.25),
                        evidencedPositions, firstAvailablePlanNodeId);
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
        DetachedWay selectedProposal = (DetachedWay) baseValues.get(request.selectedWayKey());
        List<PrimitiveKey> selectedOrder = new ArrayList<>(selectedProposal.nodeKeys());
        for (PrimitiveKey junction : sharedJunctions.stream().sorted().toList()) {
            PrimitiveKey predecessor = selectedInsertionAfter.get(junction);
            if (predecessor == null) {
                continue;
            }
            selectedOrder.remove(junction);
            int insertion = selectedOrder.indexOf(predecessor);
            if (insertion < 0) {
                throw new IllegalArgumentException("Evidenced selected predecessor is absent");
            }
            selectedOrder.add(insertion + 1, junction);
        }
        combined.put(request.selectedWayKey(), new DetachedWay(selectedProposal.key(), selectedOrder,
                selectedProposal.tags(), selectedProposal.deleted(),
                selectedProposal.modified() || !selectedProposal.nodeKeys().equals(selectedOrder)));
        for (PrimitiveKey junction : sharedJunctions) {
            ExistingWayNodeOccurrence occurrence = pointIds.stream()
                    .filter(ExistingWayNodeOccurrence.class::isInstance)
                    .map(ExistingWayNodeOccurrence.class::cast)
                    .filter(value -> value.nodeKey().equals(junction)).findFirst().orElseThrow();
            MetricPoint expected = jointPositions.getOrDefault(junction,
                    assignments.get(occurrence));
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

    private static SelectedReceiverIntersection uniqueIntersection(
            List<SelectedReceiverIntersection> intersections) {
        if (intersections.size() != 1) {
            throw new IllegalArgumentException(
                    "AMBIGUOUS_JUNCTION_CROSSING: distinct receiver crossings need joint evidence");
        }
        return intersections.get(0);
    }

    private record MetricSegment(MetricPoint start, MetricPoint end) { }

    private static List<String> unsupportedSelectedExtensions(NetworkSnapshot before,
            Map<PrimitiveKey, DetachedPrimitive> after, ModernTracePipeline.Route route,
            TraceRequest request, EvidenceSnapshot evidence, String fieldName,
            Set<PrimitiveKey> junctions) {
        double pitch = evidence.resolution().effectivePitchMeters();
        var field = evidence.fields().get(fieldName);
        if (field == null) {
            return List.of("final-support:UNSUPPORTED_REATTACHED_SELECTED_APPROACH");
        }
        // Recovery pixels outside the original selected decision corridor are considered
        // only along the final selected span, after a direct seed inside that corridor.
        ImageCostField recoveryImage = new ImageCostField(field, evidence.transform(),
                before.closure().editRegion(), pitch);
        ImageCostField selectedDecisionImage = new ImageCostField(field, evidence.transform(),
                evidence.decisionRegion(), pitch);
        DetachedWay selected = (DetachedWay) after.get(request.selectedWayKey());
        Set<PrimitiveKey> routeOwnedNodes = route.pointIds().stream().map(id ->
                id instanceof ExistingWayNodeOccurrence occurrence
                        ? occurrence.nodeKey() : plannedKey((GeneratedCandidatePoint) id))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<String> findings = new ArrayList<>();
        for (PrimitiveKey junction : junctions) {
            int routeIndex = -1;
            for (int index = 0; index < route.pointIds().size(); index++) {
                if (route.pointIds().get(index) instanceof ExistingWayNodeOccurrence occurrence
                        && occurrence.nodeKey().equals(junction)) {
                    routeIndex = index;
                    break;
                }
            }
            if (routeIndex < 0) {
                findings.add("final-support:UNSUPPORTED_REATTACHED_SELECTED_APPROACH");
                continue;
            }
            if (routeIndex != 0 && routeIndex != route.pointIds().size() - 1) {
                continue;
            }
            int selectedIndex = selected.nodeKeys().indexOf(junction);
            if (selectedIndex < 0) {
                findings.add("final-support:UNSUPPORTED_REATTACHED_SELECTED_APPROACH");
                continue;
            }
            MetricPoint target = metric(after, junction, evidence);
            MetricPoint original = route.assignments().get(route.pointIds().get(routeIndex));
            if (original == null) {
                findings.add("final-support:UNSUPPORTED_REATTACHED_SELECTED_APPROACH");
                continue;
            }
            if (original.distanceTo(target) <= pitch) {
                continue;
            }
            if (original.distanceTo(target) > 20.0 + pitch) {
                findings.add("final-support:UNSUPPORTED_REATTACHED_SELECTED_APPROACH");
                continue;
            }
            List<MetricSegment> receiverArms = capturedReceiverArms(before, evidence,
                    request.selectedWayKey(), junction);
            boolean inspected = false;
            for (int side : new int[] {-1, 1}) {
                int neighborIndex = selectedIndex + side;
                if (neighborIndex < 0 || neighborIndex >= selected.nodeKeys().size()
                        || !routeOwnedNodes.contains(selected.nodeKeys().get(neighborIndex))) {
                    continue;
                }
                inspected = true;
                MetricPoint neighbor = metric(after, selected.nodeKeys().get(neighborIndex), evidence);
                if (unsupportedSelectedInterval(selectedDecisionImage, recoveryImage,
                        receiverArms, original, neighbor, target, pitch)) {
                    findings.add("final-support:UNSUPPORTED_REATTACHED_SELECTED_APPROACH");
                    break;
                }
            }
            if (!inspected) {
                findings.add("final-support:UNSUPPORTED_REATTACHED_SELECTED_APPROACH");
            }
        }
        DetachedWay sourceSelected = (DetachedWay) before.primitives().get(request.selectedWayKey());
        List<MetricSegment> receiverArms = junctions.stream()
                .flatMap(junction -> capturedReceiverArms(before, evidence,
                        request.selectedWayKey(), junction).stream())
                .toList();
        for (int index = 0; index + 1 < selected.nodeKeys().size(); index++) {
            PrimitiveKey left = selected.nodeKeys().get(index);
            PrimitiveKey right = selected.nodeKeys().get(index + 1);
            if (routeOwnedNodes.contains(left) == routeOwnedNodes.contains(right)
                    || unchangedSelectedEdge(before, after, sourceSelected, left, right)) {
                continue;
            }
            if (unsupportedBoundaryConnector(recoveryImage, receiverArms,
                    metric(after, left, evidence), metric(after, right, evidence), pitch)) {
                findings.add("final-support:UNSUPPORTED_REATTACHED_SELECTED_APPROACH");
            }
        }
        return List.copyOf(findings);
    }

    private static boolean unchangedSelectedEdge(NetworkSnapshot before,
            Map<PrimitiveKey, DetachedPrimitive> after, DetachedWay sourceSelected,
            PrimitiveKey left, PrimitiveKey right) {
        int index = sourceSelected.nodeKeys().indexOf(left);
        if (index < 0 || index + 1 >= sourceSelected.nodeKeys().size()
                || !sourceSelected.nodeKeys().get(index + 1).equals(right)
                || !(before.primitives().get(left) instanceof DetachedNode oldLeft)
                || !(before.primitives().get(right) instanceof DetachedNode oldRight)) {
            return false;
        }
        return oldLeft.coordinate().equals(((DetachedNode) after.get(left)).coordinate())
                && oldRight.coordinate().equals(((DetachedNode) after.get(right)).coordinate());
    }

    private static boolean unsupportedBoundaryConnector(ImageCostField image,
            List<MetricSegment> receiverArms, MetricPoint start, MetricPoint end,
            double pitch) {
        double length = start.distanceTo(end);
        if (!(length > 1.0e-9)) {
            return true;
        }
        double allowance = Math.max(2.0, pitch);
        int count = Math.max(1, (int) Math.ceil(length / Math.min(1.0, pitch * 0.5)));
        MetricPoint tangent = new MetricPoint(end.xMeters() - start.xMeters(),
                end.yMeters() - start.yMeters());
        double unsupported = 0.0;
        boolean directlyObserved = false;
        for (int sample = 0; sample < count; sample++) {
            double t = (sample + 0.5) / count;
            MetricPoint point = new MetricPoint(start.xMeters() + t * tangent.xMeters(),
                    start.yMeters() + t * tangent.yMeters());
            boolean direct = image.sampleRoute(point, tangent)
                    .map(value -> value.ownership() == ObservationOwnership.DIRECT_TWO_SIDED)
                    .orElse(false)
                    && receiverArms.stream()
                        .noneMatch(segment -> distanceToSegment(point, segment) <= pitch);
            directlyObserved |= direct;
            unsupported = direct ? 0.0 : unsupported + length / count;
            if (unsupported > allowance) {
                return true;
            }
        }
        return !directlyObserved;
    }

    private static List<MetricSegment> capturedReceiverArms(NetworkSnapshot before,
            EvidenceSnapshot evidence, PrimitiveKey selectedWayKey, PrimitiveKey junction) {
        List<MetricSegment> arms = new ArrayList<>();
        for (Map.Entry<PrimitiveKey, List<OccurrenceRange>> entry
                : before.closure().editableWayOccurrences().entrySet()) {
            if (entry.getKey().equals(selectedWayKey)
                    || !(before.primitives().get(entry.getKey()) instanceof DetachedWay receiver)
                    || !receiver.nodeKeys().contains(junction)) {
                continue;
            }
            for (OccurrenceRange range : entry.getValue()) {
                for (int index = range.firstIndex(); index < range.lastIndex(); index++) {
                    arms.add(new MetricSegment(
                            metric(before.primitives(), receiver.nodeKeys().get(index), evidence),
                            metric(before.primitives(), receiver.nodeKeys().get(index + 1), evidence)));
                }
            }
        }
        return List.copyOf(arms);
    }

    private static double distanceToSegment(MetricPoint point, MetricSegment segment) {
        double dx = segment.end().xMeters() - segment.start().xMeters();
        double dy = segment.end().yMeters() - segment.start().yMeters();
        double squaredLength = dx * dx + dy * dy;
        if (!(squaredLength > 1.0e-12)) {
            return point.distanceTo(segment.start());
        }
        double fraction = Math.max(0.0, Math.min(1.0,
                ((point.xMeters() - segment.start().xMeters()) * dx
                        + (point.yMeters() - segment.start().yMeters()) * dy) / squaredLength));
        return point.distanceTo(new MetricPoint(segment.start().xMeters() + fraction * dx,
                segment.start().yMeters() + fraction * dy));
    }

    private static boolean unsupportedSelectedInterval(ImageCostField selectedDecisionImage,
            ImageCostField recoveryImage, List<MetricSegment> receiverArms,
            MetricPoint original, MetricPoint neighbor, MetricPoint target, double pitch) {
        double dx = target.xMeters() - neighbor.xMeters();
        double dy = target.yMeters() - neighbor.yMeters();
        double squaredLength = dx * dx + dy * dy;
        if (!(squaredLength > 1.0e-12)) {
            return true;
        }
        double fraction = ((original.xMeters() - neighbor.xMeters()) * dx
                + (original.yMeters() - neighbor.yMeters()) * dy) / squaredLength;
        MetricPoint projected = new MetricPoint(neighbor.xMeters() + fraction * dx,
                neighbor.yMeters() + fraction * dy);
        if (fraction >= 1.0 && original.distanceTo(projected) <= pitch) {
            return false;
        }
        double allowance = Math.max(2.0, pitch);
        MetricPoint start = fraction >= 0.0 && fraction < 1.0
                && original.distanceTo(projected) <= pitch ? projected : neighbor;
        double extension = start.distanceTo(target);
        if (extension <= allowance) {
            return false;
        }
        MetricPoint tangent = new MetricPoint(dx, dy);
        if (!selectedDecisionImage.sampleRoute(original, tangent)
                .map(value -> value.ownership() == ObservationOwnership.DIRECT_TWO_SIDED)
                .orElse(false)) {
            return true;
        }
        int count = Math.max(1, (int) Math.ceil(extension / Math.min(1.0, pitch * 0.5)));
        double unsupported = 0.0;
        for (int sample = 0; sample < count; sample++) {
            double t = (sample + 0.5) / count;
            MetricPoint point = new MetricPoint(start.xMeters()
                    + t * (target.xMeters() - start.xMeters()),
                    start.yMeters() + t * (target.yMeters() - start.yMeters()));
            boolean direct = recoveryImage.sampleRoute(point, tangent)
                    .map(value -> value.ownership() == ObservationOwnership.DIRECT_TWO_SIDED)
                    .orElse(false);
            // A sustained nearby captured receiver arm makes the scalar mode's branch
            // ownership ambiguous; ignore the shared junction core itself.
            if (point.distanceTo(target) > allowance && receiverArms.stream()
                    .anyMatch(segment -> distanceToSegment(point, segment) <= pitch)) {
                direct = false;
            }
            unsupported = direct ? 0.0 : unsupported + extension / count;
            if (unsupported > allowance) {
                return true;
            }
        }
        return false;
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
                    boolean changedSelectedContinuation = first.wayKey().equals(selectedWay)
                            && first.insideSelectedRange() && second.insideSelectedRange()
                            && (first.changed() || second.changed());
                    boolean changedIncidentContinuation = !first.wayKey().equals(selectedWay)
                            && (first.changed() || second.changed());
                    if (changedSelectedContinuation && classify(first.first(), first.second(),
                            second.first(), second.second()) == TopologyDefect.COLLINEAR_OVERLAP) {
                        findings.add("final-topology:COLLINEAR_OVERLAP");
                    }
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

        private void reservePlanNodeKeysBefore(long firstAvailable) {
            nextLocal = Math.max(nextLocal, firstAvailable);
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
