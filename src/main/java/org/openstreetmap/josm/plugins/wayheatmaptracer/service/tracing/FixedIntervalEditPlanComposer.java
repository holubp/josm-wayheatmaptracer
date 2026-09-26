package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner;

/** Composes selected production interval routes into one exact network edit plan. */
public final class FixedIntervalEditPlanComposer {
    /** Per-interval final planning disposition. */
    public enum Disposition { CHANGED, FROZEN_LOCAL_FAILURE, BLOCKED_GLOBAL, UNCHANGED_NOOP }

    /** Selected route identity and typed outcome for one original interval. */
    public record IntervalAssessment(int intervalIndex, int routeIndex, String routeIdentity,
            Disposition disposition, String reason) { }

    /** One immutable complete preview and its final validation result. */
    public record Assessment(Optional<AlignmentEditPlan> plan,
            List<GeographicPoint> selectedWayPreview,
            Map<FinalRoutePointId, MetricPoint> assignments,
            List<IntervalAssessment> intervals) {
        public Assessment {
            plan = Optional.ofNullable(plan).orElseThrow();
            selectedWayPreview = List.copyOf(selectedWayPreview);
            assignments = Map.copyOf(assignments);
            intervals = List.copyOf(intervals);
        }
        public boolean applyAvailable() {
            return plan.isPresent() && plan.orElseThrow().validation().applicable();
        }
    }

    /** Selects one ranked production route per interval and validates a complete final plan. */
    public Assessment compose(IntervalTraceBatch batch, Map<Integer, Integer> routeChoices) {
        if (batch == null || routeChoices == null) {
            throw new IllegalArgumentException("Interval batch and route choices are required");
        }
        if (!SelectedWayIntervalPartitioner.verifyFrozenParity(
                batch.partition(), batch.network())) {
            throw new IllegalArgumentException("Interval partition no longer matches frozen source");
        }
        List<IntervalTraceBatch.IntervalRun> runs = batch.runs();
        if (routeChoices.keySet().stream().anyMatch(index -> index == null || index < 0
                || index >= runs.size()) || routeChoices.values().stream()
                .anyMatch(index -> index == null || index < 0)) {
            throw new IllegalArgumentException("Route choice is outside the interval batch");
        }
        for (int i = 0; i < runs.size(); i++) {
            if (routeChoices.containsKey(i) && runs.get(i).routes().isEmpty()) {
                throw new IllegalArgumentException("No production route exists for an explicit choice");
            }
            if (routeChoices.getOrDefault(i, 0) >= runs.get(i).routes().size()
                    && !runs.get(i).routes().isEmpty()) {
                throw new IllegalArgumentException("Route choice exceeds production alternatives");
            }
        }
        DetachedWay selected = (DetachedWay) batch.network().primitives()
                .get(batch.fullRequest().selectedWayKey());
        if (selected == null) throw new IllegalArgumentException("Selected way is absent");
        Set<Integer> frozen = new LinkedHashSet<>();
        Map<Integer, String> reasons = new HashMap<>();
        for (int i = 0; i < runs.size(); i++) {
            var run = runs.get(i);
            if (run.routes().isEmpty()) {
                frozen.add(i);
                reasons.put(i, "NO_PRODUCTION_ROUTE");
                continue;
            }
            var route = run.routes().get(routeChoices.getOrDefault(i, 0));
            boolean missingSupport = route.quality().has(
                    FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY);
            if (!routeChangesOriginal(batch, selected, run, route)) {
                frozen.add(i);
                reasons.put(i, "NO_GEOMETRY_CHANGE");
            } else if (missingSupport || route.quality().disposition()
                    == FinalGeometryEvaluator.Disposition.HARD_BLOCKED) {
                frozen.add(i);
                reasons.put(i, missingSupport ? "LOCAL_IMAGE_SUPPORT" : "LOCAL_ROUTE_BLOCKED");
            }
        }
        ModernSingleWayEditPlanAdapter adapter = new ModernSingleWayEditPlanAdapter();
        for (int attempt = 0; attempt <= runs.size(); attempt++) {
            Candidate candidate = assemble(batch, selected, routeChoices, frozen);
            if (candidate.changedIntervals().isEmpty()) {
                return assessment(batch, selected, routeChoices, frozen, reasons, Set.of(),
                        Optional.empty(), candidate.assignments(), originalPreview(batch, selected));
            }
            try {
                AlignmentEditPlan plan = adapter.adaptComposite(batch, candidate.ids(),
                        candidate.assignments(), candidate.identity(), candidate.findings());
                Set<Integer> blocked = plan.validation().applicable() ? Set.of()
                        : candidate.changedIntervals();
                return assessment(batch, selected, routeChoices, frozen, reasons, blocked,
                        Optional.of(plan), candidate.assignments(),
                        plan.finalPreviewWays().getOrDefault(selected.key(), originalPreview(batch, selected)));
            } catch (ModernSingleWayEditPlanAdapter.CompositeLocalSupportException failure) {
                Set<Integer> implicated = candidate.owners(failure.left(), failure.right());
                if (implicated.isEmpty()) implicated = candidate.changedIntervals();
                if (!frozen.addAll(implicated)) {
                    return assessment(batch, selected, routeChoices, frozen, reasons,
                            candidate.changedIntervals(), Optional.empty(), candidate.assignments(),
                            originalPreview(batch, selected));
                }
                for (int index : implicated) reasons.put(index,
                        "LOCAL_CONNECTOR_SUPPORT:" + failure.left() + "->" + failure.right());
            } catch (IncidentWayReconstructor.MissingEvidenceException failure) {
                Set<Integer> eligible = demotableEligibleT(batch, selected,
                        candidate.changedIntervals());
                if (eligible.isEmpty() || !frozen.addAll(eligible)) {
                    return assessment(batch, selected, routeChoices, frozen, reasons,
                            candidate.changedIntervals(), Optional.empty(), candidate.assignments(),
                            originalPreview(batch, selected));
                }
                for (int index : eligible) reasons.put(index, "T_LOCAL_PRECOMMAND_EVIDENCE");
            } catch (IllegalArgumentException failure) {
                for (int index : candidate.changedIntervals()) {
                    reasons.put(index, "GLOBAL_FINAL_VALIDATION:" + failure.getClass().getSimpleName()
                            + ":" + failure.getMessage());
                }
                return assessment(batch, selected, routeChoices, frozen, reasons,
                        candidate.changedIntervals(), Optional.empty(), candidate.assignments(),
                        originalPreview(batch, selected));
            }
        }
        throw new IllegalStateException("Interval freeze recomputation exceeded its bound");
    }

    private static Set<Integer> demotableEligibleT(IntervalTraceBatch batch,
            DetachedWay selected, Set<Integer> changed) {
        Set<PrimitiveKey> sharedMovable = new LinkedHashSet<>();
        for (PrimitiveKey key : selected.nodeKeys().subList(
                batch.fullRequest().selectedRange().firstIndex(),
                batch.fullRequest().selectedRange().lastIndex() + 1)) {
            if (batch.network().closure().movableExistingNodeKeys().contains(key)
                    && batch.network().incomingReferrerWatches().getOrDefault(key, Set.of())
                            .stream().filter(ref -> ref.type() == PrimitiveKey.Type.WAY).count() > 1) {
                sharedMovable.add(key);
            }
        }
        if (sharedMovable.size() != 1) return Set.of();
        PrimitiveKey soleJunction = sharedMovable.iterator().next();
        var eligibleJunctions = batch.partition().junctionDispositions().stream()
                .filter(disposition -> disposition.automaticEligible()
                        && disposition.nodeKey().equals(soleJunction)).toList();
        if (eligibleJunctions.size() != 1) return Set.of();
        int occurrence = eligibleJunctions.get(0).selectedOccurrenceIndex();
        Set<Integer> eligible = new LinkedHashSet<>();
        for (int index : changed) {
            var interval = batch.runs().get(index).interval();
            if (interval.traceRange().firstIndex() <= occurrence
                    && occurrence <= interval.traceRange().lastIndex()) {
                eligible.add(index);
            }
        }
        return Set.copyOf(eligible);
    }

    private record Candidate(List<FinalRoutePointId> ids,
            Map<FinalRoutePointId, MetricPoint> assignments, String identity,
            List<String> findings, Set<Integer> changedIntervals,
            Map<PrimitiveKey, Set<Integer>> ownersByKey) {
        Set<Integer> owners(PrimitiveKey left, PrimitiveKey right) {
            Set<Integer> leftOwners = ownersByKey.getOrDefault(left, Set.of());
            Set<Integer> rightOwners = ownersByKey.getOrDefault(right, Set.of());
            Set<Integer> common = new HashSet<>(leftOwners);
            common.retainAll(rightOwners);
            if (!common.isEmpty()) return Set.copyOf(common);
            common.addAll(leftOwners);
            common.addAll(rightOwners);
            return Set.copyOf(common);
        }
    }

    private static Candidate assemble(IntervalTraceBatch batch, DetachedWay selected,
            Map<Integer, Integer> choices, Set<Integer> frozen) {
        String identity = "interval-composite:" + java.util.stream.IntStream.range(0, batch.runs().size())
                .mapToObj(i -> i + "=" + (frozen.contains(i) ? "frozen" :
                        batch.runs().get(i).routes().get(choices.getOrDefault(i, 0))
                                .hypothesis().id())).collect(java.util.stream.Collectors.joining(";"));
        List<FinalRoutePointId> ids = new ArrayList<>();
        Map<FinalRoutePointId, MetricPoint> assignments = new LinkedHashMap<>();
        Map<PrimitiveKey, Set<Integer>> owners = new LinkedHashMap<>();
        Set<Integer> changed = new LinkedHashSet<>();
        List<String> findings = new ArrayList<>();
        int cursor = batch.fullRequest().selectedRange().firstIndex();
        for (int i = 0; i < batch.runs().size(); i++) {
            var run = batch.runs().get(i);
            int first = run.interval().traceRange().firstIndex();
            int last = run.interval().traceRange().lastIndex();
            for (int occurrence = cursor; occurrence < first; occurrence++) {
                appendExisting(batch, selected, ids, assignments, occurrence);
            }
            if (frozen.contains(i)) {
                for (int occurrence = first; occurrence <= last; occurrence++) {
                    appendExisting(batch, selected, ids, assignments, occurrence);
                }
            } else {
                var route = run.routes().get(choices.getOrDefault(i, 0));
                if (route.pointIds().isEmpty()) {
                    throw new IllegalArgumentException("Selected interval route has no points");
                }
                for (FinalRoutePointId sourceId : route.pointIds()) {
                    FinalRoutePointId id = sourceId;
                    if (sourceId instanceof GeneratedCandidatePoint generated) {
                        int pointIndex = Math.addExact(Math.multiplyExact(i, 1_000_000),
                                generated.originalPointIndex());
                        if (generated.originalPointIndex() >= 1_000_000) {
                            throw new IllegalArgumentException("Interval generated point budget exceeded");
                        }
                        id = new GeneratedCandidatePoint("interval-" + i + ":"
                                + route.hypothesis().id(), pointIndex);
                    }
                    MetricPoint point = route.assignments().get(sourceId);
                    append(ids, assignments, id, point);
                    PrimitiveKey key = id instanceof ExistingWayNodeOccurrence existing
                            ? existing.nodeKey() : PrimitiveKey.planned(PrimitiveKey.Type.NODE,
                                    ((GeneratedCandidatePoint) id).originalPointIndex());
                    owners.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(i);
                }
                if (routeChangesOriginal(batch, selected, run, route)) changed.add(i);
                for (var finding : route.quality().findings()) {
                    findings.add("interval-" + i + ":" + finding.code().name());
                }
            }
            cursor = Math.max(cursor, last + 1);
        }
        for (int occurrence = cursor; occurrence <= batch.fullRequest().selectedRange().lastIndex();
                occurrence++) {
            appendExisting(batch, selected, ids, assignments, occurrence);
        }
        return new Candidate(List.copyOf(ids), Map.copyOf(assignments), identity,
                List.copyOf(findings), Set.copyOf(changed), Map.copyOf(owners));
    }

    private static boolean routeChangesOriginal(IntervalTraceBatch batch, DetachedWay selected,
            IntervalTraceBatch.IntervalRun run, ModernTracePipeline.Route route) {
        int first = run.interval().traceRange().firstIndex();
        int last = run.interval().traceRange().lastIndex();
        if (route.pointIds().size() != last - first + 1) return true;
        for (int offset = 0; offset <= last - first; offset++) {
            int occurrence = first + offset;
            FinalRoutePointId id = route.pointIds().get(offset);
            if (!(id instanceof ExistingWayNodeOccurrence existing)
                    || existing.originalOccurrenceIndex() != occurrence
                    || !existing.nodeKey().equals(selected.nodeKeys().get(occurrence))
                    || !route.assignments().get(id).equals(original(batch, selected, occurrence))) {
                return true;
            }
        }
        return false;
    }

    private static void appendExisting(IntervalTraceBatch batch, DetachedWay selected,
            List<FinalRoutePointId> ids, Map<FinalRoutePointId, MetricPoint> assignments,
            int occurrence) {
        PrimitiveKey key = selected.nodeKeys().get(occurrence);
        append(ids, assignments, new ExistingWayNodeOccurrence(selected.key(), key, occurrence),
                original(batch, selected, occurrence));
    }

    private static MetricPoint original(IntervalTraceBatch batch, DetachedWay selected,
            int occurrence) {
        DetachedNode node = (DetachedNode) batch.network().primitives()
                .get(selected.nodeKeys().get(occurrence));
        return batch.evidence().coordinateFrame().toMetric(node.coordinate());
    }

    private static void append(List<FinalRoutePointId> ids,
            Map<FinalRoutePointId, MetricPoint> assignments,
            FinalRoutePointId id, MetricPoint point) {
        if (point == null) throw new IllegalArgumentException("Candidate-owned point is missing");
        if (!ids.isEmpty() && ids.get(ids.size() - 1).equals(id)) {
            if (!assignments.get(id).equals(point)) {
                throw new IllegalArgumentException("Shared interval boundary assignments disagree");
            }
            return;
        }
        if (assignments.putIfAbsent(id, point) != null) {
            throw new IllegalArgumentException("Composite occurrence identity is duplicated");
        }
        ids.add(id);
    }

    private static List<GeographicPoint> originalPreview(IntervalTraceBatch batch,
            DetachedWay selected) {
        return selected.nodeKeys().stream().map(key -> ((DetachedNode) batch.network()
                .primitives().get(key)).coordinate()).toList();
    }

    private static Assessment assessment(IntervalTraceBatch batch, DetachedWay selected,
            Map<Integer, Integer> choices, Set<Integer> frozen, Map<Integer, String> reasons,
            Set<Integer> blocked, Optional<AlignmentEditPlan> plan,
            Map<FinalRoutePointId, MetricPoint> assignments,
            List<GeographicPoint> selectedPreview) {
        List<IntervalAssessment> intervals = new ArrayList<>();
        for (int i = 0; i < batch.runs().size(); i++) {
            var run = batch.runs().get(i);
            int choice = choices.getOrDefault(i, 0);
            String routeId = run.routes().isEmpty() ? "unavailable"
                    : run.routes().get(choice).hypothesis().id();
            Disposition disposition = "NO_GEOMETRY_CHANGE".equals(reasons.get(i))
                    ? Disposition.UNCHANGED_NOOP
                    : frozen.contains(i) ? Disposition.FROZEN_LOCAL_FAILURE
                    : blocked.contains(i) ? Disposition.BLOCKED_GLOBAL : Disposition.CHANGED;
            intervals.add(new IntervalAssessment(i, choice, routeId, disposition,
                    frozen.contains(i) ? reasons.getOrDefault(i, "LOCAL_FAILURE")
                            : blocked.contains(i) ? reasons.getOrDefault(i, "GLOBAL_FINAL_VALIDATION")
                                    : "VALIDATED"));
        }
        return new Assessment(plan, selectedPreview, assignments, intervals);
    }
}
