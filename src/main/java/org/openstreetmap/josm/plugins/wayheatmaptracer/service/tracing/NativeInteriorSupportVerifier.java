package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner;

/** Admits physically checked retained B body spans, without granting connector or boundary authority. */
final class NativeInteriorSupportVerifier {
    record Edge(PrimitiveKey left, PrimitiveKey right) { }
    record Geometry(SelectedWayIntervalPartitioner.SlideInterval interval, TraceRequest request,
            TraceHypothesisSet inference, List<ModernTracePipeline.Route> routes) {
        Geometry { routes = List.copyOf(routes); }
    }
    record Input(TraceRequest fullRequest, EvidenceSnapshot evidence, NetworkSnapshot network,
            ModernTracePipeline.Options options, List<Geometry> runs) {
        Input { runs = List.copyOf(runs); }
    }

    static Set<Edge> verify(IntervalTraceBatch batch, List<FinalRoutePointId> ids,
            Map<FinalRoutePointId, MetricPoint> assignments, List<PrimitiveKey> keys,
            Map<PrimitiveKey, DetachedPrimitive> after, Map<Integer, Integer> choices) {
        return verify(new Input(batch.fullRequest(), batch.evidence(), batch.network(), batch.options(),
                batch.runs().stream().map(run -> new Geometry(run.interval(), run.request(),
                        run.result().inference(), run.routes())).toList()), ids, assignments, keys, after, choices);
    }

    static Set<Edge> verify(Input input, List<FinalRoutePointId> ids,
            Map<FinalRoutePointId, MetricPoint> assignments, List<PrimitiveKey> keys,
            Map<PrimitiveKey, DetachedPrimitive> after, Map<Integer, Integer> choices) {
        if (input.fullRequest().engine() != TrackerMode.PROBABILISTIC || !input.options().cleanup().isDisabled()
                || ids.size() != keys.size() || new HashSet<>(ids).size() != ids.size()
                || !input.fullRequest().networkContentHash().equals(input.network().canonicalHash())
                || !input.fullRequest().evidenceContentHash().equals(input.evidence().canonicalHash())
                || !input.fullRequest().networkSnapshotId().equals(input.network().snapshotId())
                || !input.fullRequest().evidenceSnapshotId().equals(input.evidence().snapshotId())
                || !input.fullRequest().evidenceResolution().equals(input.evidence().resolution())) return Set.of();
        var source = (DetachedWay) input.network().primitives().get(input.fullRequest().selectedWayKey());
        var frame = input.evidence().coordinateFrame();
        double pitch = input.evidence().resolution().effectivePitchMeters();
        var field = input.evidence().fields().get(input.options().fieldName());
        if (field == null) return Set.of();
        var image = new ImageCostField(field, input.evidence().transform(), input.evidence().decisionRegion(), pitch);
        Map<FinalRoutePointId, Integer> actualIndexes = new HashMap<>();
        for (int index = 0; index < ids.size(); index++) actualIndexes.put(ids.get(index), index);
        Set<Edge> result = new HashSet<>();
        var disconnections = SharedNodeDisconnectionPlanner.forRequest(input.fullRequest(), input.network());
        for (int runIndex = 0; runIndex < input.runs().size(); runIndex++) {
            var run = input.runs().get(runIndex);
            int choice = choices.getOrDefault(runIndex, 0);
            if (choice < 0 || choice >= run.routes().size()) continue;
            var route = run.routes().get(choice);
            int ownedRunIndex = runIndex;
            List<FinalRoutePointId> nativeIds = route.pointIds().stream()
                    .map(id -> normalized(id, ownedRunIndex, route.hypothesis().id())).toList();
            boolean contributed = false;
            for (int index = 0; index < nativeIds.size(); index++) {
                var id = nativeIds.get(index);
                if (!actualIndexes.containsKey(id)) continue;
                contributed |= id instanceof GeneratedCandidatePoint;
                if (id instanceof ExistingWayNodeOccurrence existing) {
                    var old = (DetachedNode) input.network().primitives().get(existing.nodeKey());
                    contributed |= old != null && !frame.toMetric(old.coordinate()).equals(assignments.get(id));
                }
            }
            if (!contributed) continue; // Frozen original spans do not borrow the discarded route's evidence.
            if (route.quality().disposition() != FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED) continue;
            if (!new IntervalTraceRequestFactory().createDetached(input.fullRequest(), input.evidence(), input.network(),
                    run.interval(), run.request().budgets()).equals(run.request())
                    || run.inference().engine() != TrackerMode.PROBABILISTIC
                    || !run.inference().hypotheses().contains(route.rawHypothesis())) {
                throw new IllegalArgumentException("Native interval source or route binding differs");
            }
            if (route.quality().disposition() == FinalGeometryEvaluator.Disposition.HARD_BLOCKED
                    || route.quality().findings().stream().anyMatch(finding ->
                            finding.severity() == FinalGeometryEvaluator.Severity.HARD_BLOCK)) {
                throw new IllegalArgumentException("Native interval has a hard finding");
            }
            List<MetricPoint> points = route.pointIds().stream().map(route.assignments()::get).toList();
            var support = FinalGeometryEvaluator.inspectSupport(points, image, pitch);
            var reported = route.quality();
            if (!same(support.totalLengthMeters(), reported.totalLengthMeters())
                    || !same(support.directlySupportedLengthMeters(), reported.directlySupportedLengthMeters())
                    || !same(support.worstUnsupportedSpanMeters(), reported.worstUnsupportedSpanMeters())
                    || !same(support.meanImageCenterCost(), reported.meanImageCenterCost())
                    || !Double.isFinite(support.directlySupportedLengthMeters())
                    || !(support.directlySupportedLengthMeters() > 0.0)) {
                throw new IllegalArgumentException("Native physical support summary differs");
            }
            boolean[] exact = new boolean[points.size()];
            boolean[] protectedVertex = new boolean[points.size()];
            protectedVertex[0] = true; protectedVertex[points.size() - 1] = true;
            for (int index = 0; index < points.size(); index++) {
                var rawId = route.pointIds().get(index); var id = nativeIds.get(index);
                Integer actual = actualIndexes.get(id);
                GeographicPoint expected = frame.toGeographic(points.get(index));
                boolean nativeIdentity = true;
                if (rawId instanceof GeneratedCandidatePoint generated) {
                    int rawIndex = generated.originalPointIndex();
                    nativeIdentity = generated.candidateId().equals(route.rawHypothesis().id())
                            && rawIndex < route.rawHypothesis().points().size()
                            && points.get(index).equals(route.rawHypothesis().points().get(rawIndex))
                            && route.sourceOwnership().get(rawId) == route.rawHypothesis().support().get(rawIndex);
                } else if (rawId instanceof ExistingWayNodeOccurrence existing) {
                    int occurrence = existing.originalOccurrenceIndex();
                    nativeIdentity = existing.wayKey().equals(source.key())
                            && occurrence >= run.request().selectedRange().firstIndex()
                            && occurrence <= run.request().selectedRange().lastIndex()
                            && source.nodeKeys().get(occurrence).equals(existing.nodeKey());
                    var old = (DetachedNode) input.network().primitives().get(existing.nodeKey());
                    if (points.get(index).equals(frame.toMetric(old.coordinate()))) expected = old.coordinate();
                    boolean replacement = disconnections.mayPosition(occurrence);
                    protectedVertex[index] |= !replacement && (input.network().closure().protectedExistingNodeKeys().contains(existing.nodeKey())
                            || !input.network().closure().movableExistingNodeKeys().contains(existing.nodeKey()))
                            || route.sourceOwnership().get(rawId) == ObservationOwnership.FIXED_TOPOLOGY_ONLY;
                }
                exact[index] = nativeIdentity && actual != null && points.get(index).equals(assignments.get(id))
                        && (rawId instanceof ExistingWayNodeOccurrence existing ? keys.get(actual).equals(
                                disconnections.destinationKey(existing))
                                : keys.get(actual).type() == PrimitiveKey.Type.NODE
                                    && keys.get(actual).identityKind() == PrimitiveKey.IdentityKind.PLAN_LOCAL)
                        && after.get(keys.get(actual)) instanceof DetachedNode node && expected.equals(node.coordinate());
            }
            List<Double> chainage = new ArrayList<>(); chainage.add(0.0);
            for (int index = 1; index < points.size(); index++) {
                chainage.add(chainage.get(index - 1) + points.get(index - 1).distanceTo(points.get(index)));
            }
            int[] starts = new int[points.size() - 1], ends = new int[points.size() - 1];
            int start = 0;
            for (int end = 1; end < points.size(); end++) {
                if (!protectedVertex[end]) continue;
                for (int edge = start; edge < end; edge++) { starts[edge] = start; ends[edge] = end; }
                start = end;
            }
            Map<Integer, Integer> firstDirect = new HashMap<>(), lastDirect = new HashMap<>();
            for (int index = 0; index < support.samples().size(); index++) {
                var sample = support.samples().get(index);
                if (sample.directlyLocalized()) {
                    int group = starts[sample.edgeIndex()];
                    firstDirect.putIfAbsent(group, index); lastDirect.put(group, index);
                }
            }
            boolean[] nonDirect = new boolean[points.size() - 1], bounded = new boolean[points.size() - 1];
            java.util.Arrays.fill(bounded, true);
            for (int index = 0; index < support.samples().size(); index++) {
                var sample = support.samples().get(index); if (sample.directlyLocalized()) continue;
                int edge = sample.edgeIndex(), group = starts[edge]; nonDirect[edge] = true;
                bounded[edge] &= index > firstDirect.getOrDefault(group, Integer.MAX_VALUE)
                        && index < lastDirect.getOrDefault(group, Integer.MIN_VALUE);
            }
            double approach = Math.max(2.0, pitch);
            for (int edge = 0; edge < points.size() - 1; edge++) {
                if (!nonDirect[edge] || !bounded[edge] || !exact[edge] || !exact[edge + 1]
                        || chainage.get(edge) - chainage.get(starts[edge]) < approach
                        || chainage.get(ends[edge]) - chainage.get(edge + 1) < approach) continue;
                int left = actualIndexes.get(nativeIds.get(edge)), right = actualIndexes.get(nativeIds.get(edge + 1));
                if (right == left + 1) result.add(new Edge(keys.get(left), keys.get(right)));
            }
        }
        return Set.copyOf(result);
    }

    private static FinalRoutePointId normalized(FinalRoutePointId id, int runIndex, String routeId) {
        if (!(id instanceof GeneratedCandidatePoint generated)) return id;
        if (generated.originalPointIndex() >= 1_000_000) throw new IllegalArgumentException("Native point index exceeds interval bound");
        return new GeneratedCandidatePoint("interval-" + runIndex + ":" + routeId,
                Math.addExact(Math.multiplyExact(runIndex, 1_000_000), generated.originalPointIndex()));
    }

    private static boolean same(double left, double right) {
        return Double.doubleToLongBits(left) == Double.doubleToLongBits(right);
    }
}
