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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner;

/** Proves representation-only native B subdivisions; it grants no image support or movement rights. */
final class OriginalSourceSubdivisionVerifier {
    record Edge(PrimitiveKey left, PrimitiveKey right) { }
    private record Position(double chainage, Set<Integer> pieces) { }
    private record Anchor(MetricPoint point, Position position) { }
    record NativeGeometry(SelectedWayIntervalPartitioner.SlideInterval interval, TraceRequest request,
            List<ModernTracePipeline.Route> routes) {
        NativeGeometry { routes = List.copyOf(routes); }
    }
    record Input(TraceRequest fullRequest, EvidenceSnapshot evidence, NetworkSnapshot network,
            ModernTracePipeline.Options options, List<NativeGeometry> runs) {
        Input { runs = List.copyOf(runs); }
    }

    static Set<Edge> verify(IntervalTraceBatch batch, List<FinalRoutePointId> ids,
            Map<FinalRoutePointId, MetricPoint> assignments, List<PrimitiveKey> keys,
            Map<PrimitiveKey, DetachedPrimitive> after, Map<Integer, Integer> choices) {
        return verify(new Input(batch.fullRequest(), batch.evidence(), batch.network(), batch.options(),
                batch.runs().stream().map(run -> new NativeGeometry(run.interval(), run.request(), run.routes())).toList()),
                ids, assignments, keys, after, choices);
    }

    static Set<Edge> verify(Input batch, List<FinalRoutePointId> ids,
            Map<FinalRoutePointId, MetricPoint> assignments, List<PrimitiveKey> keys,
            Map<PrimitiveKey, DetachedPrimitive> after, Map<Integer, Integer> choices) {
        if (batch.fullRequest().engine() != TrackerMode.PROBABILISTIC
                || !batch.options().cleanup().isDisabled() || ids.size() != keys.size()
                || new HashSet<>(ids).size() != ids.size()
                || !batch.fullRequest().networkContentHash().equals(batch.network().canonicalHash())
                || !batch.fullRequest().evidenceContentHash().equals(batch.evidence().canonicalHash())
                || !batch.fullRequest().networkSnapshotId().equals(batch.network().snapshotId())
                || !batch.fullRequest().evidenceSnapshotId().equals(batch.evidence().snapshotId())) return Set.of();
        var source = (DetachedWay) batch.network().primitives().get(batch.fullRequest().selectedWayKey());
        var frame = batch.evidence().coordinateFrame();
        int selectedFirst = batch.fullRequest().selectedRange().firstIndex();
        int selectedLast = batch.fullRequest().selectedRange().lastIndex();
        List<MetricPoint> original = source.nodeKeys().subList(selectedFirst, selectedLast + 1).stream().map(key -> frame.toMetric(
                ((DetachedNode) batch.network().primitives().get(key)).coordinate())).toList();
        List<Double> cumulative = cumulative(original);
        Map<FinalRoutePointId, Anchor> anchors = new HashMap<>();
        var factory = new ProbabilisticProfileFactory();
        for (int runIndex = 0; runIndex < batch.runs().size(); runIndex++) {
            var run = batch.runs().get(runIndex);
            int choice = choices.getOrDefault(runIndex, 0);
            if (choice < 0 || choice >= run.routes().size()) continue;
            // Exact reconstruction binds original occurrences, source frame, and native sampling settings.
            if (!new IntervalTraceRequestFactory().createDetached(batch.fullRequest(), batch.evidence(),
                    batch.network(), run.interval(), run.request().budgets()).equals(run.request())) continue;
            var route = run.routes().get(choice);
            int first = run.request().selectedRange().firstIndex();
            int last = run.request().selectedRange().lastIndex();
            List<MetricPoint> localSource = original.subList(first - selectedFirst, last - selectedFirst + 1);
            var geometry = factory.sourceSamplingGeometry(localSource, run.request().configuredSampleStepMeters());
            List<Double> localCumulative = cumulative(localSource);
            for (FinalRoutePointId id : route.pointIds()) {
                if (!(id instanceof GeneratedCandidatePoint generated)
                        || !generated.candidateId().equals(route.rawHypothesis().id())) continue;
                int index = generated.originalPointIndex();
                if (index >= geometry.points().size() || index >= route.rawHypothesis().points().size()
                        || index >= 1_000_000) continue;
                MetricPoint anchor = geometry.points().get(index);
                if (!anchor.equals(route.rawHypothesis().points().get(index))
                        || !anchor.equals(route.assignments().get(id))) continue;
                double target = geometry.chainageMeters().get(index);
                double selectedChainage = cumulative.get(first - selectedFirst) + target;
                Set<Integer> pieces = ambiguousVertex(cumulative, selectedChainage) ? Set.of()
                        : piecesAt(localCumulative, target, first);
                var composite = new GeneratedCandidatePoint("interval-" + runIndex + ":"
                        + route.hypothesis().id(), Math.addExact(Math.multiplyExact(runIndex, 1_000_000), index));
                anchors.put(composite, new Anchor(anchor, new Position(selectedChainage, pieces)));
            }
        }
        List<Position> positions = new ArrayList<>();
        for (int index = 0; index < ids.size(); index++) {
            FinalRoutePointId id = ids.get(index);
            Position position = null;
            if (after.get(keys.get(index)) instanceof DetachedNode proposed) {
                if (id instanceof ExistingWayNodeOccurrence existing) {
                    int occurrence = existing.originalOccurrenceIndex();
                    if (existing.wayKey().equals(source.key()) && occurrence >= selectedFirst && occurrence <= selectedLast
                            && source.nodeKeys().get(occurrence).equals(existing.nodeKey())
                            && keys.get(index).equals(existing.nodeKey())) {
                        var old = (DetachedNode) batch.network().primitives().get(existing.nodeKey());
                        if (old.coordinate().equals(proposed.coordinate())
                                && original.get(occurrence - selectedFirst).equals(assignments.get(id))) {
                            position = new Position(cumulative.get(occurrence - selectedFirst),
                                    incidentPieces(cumulative, occurrence - selectedFirst, selectedFirst));
                        }
                    }
                } else {
                    Anchor anchor = anchors.get(id);
                    if (anchor != null && keys.get(index).identityKind() == PrimitiveKey.IdentityKind.PLAN_LOCAL
                            && keys.get(index).type() == PrimitiveKey.Type.NODE
                            && anchor.point().equals(assignments.get(id))
                            && frame.toGeographic(anchor.point()).equals(proposed.coordinate())) {
                        position = anchor.position();
                    }
                }
            }
            positions.add(position);
        }
        Set<Edge> result = new HashSet<>();
        for (int index = 1; index < positions.size(); index++) {
            Position left = positions.get(index - 1), right = positions.get(index);
            if (left != null && right != null && left.chainage() < right.chainage()
                    && left.pieces().stream().anyMatch(right.pieces()::contains)) {
                result.add(new Edge(keys.get(index - 1), keys.get(index)));
            }
        }
        return Set.copyOf(result);
    }

    private static List<Double> cumulative(List<MetricPoint> points) {
        List<Double> result = new ArrayList<>();
        result.add(0.0);
        for (int index = 1; index < points.size(); index++) {
            result.add(result.get(index - 1) + points.get(index - 1).distanceTo(points.get(index)));
        }
        return result;
    }

    private static Set<Integer> piecesAt(List<Double> cumulative, double target, int origin) {
        if (ambiguousVertex(cumulative, target)) return Set.of();
        Set<Integer> result = new HashSet<>();
        for (int index = 1; index < cumulative.size(); index++) {
            if (cumulative.get(index - 1) <= target && target <= cumulative.get(index)
                    && cumulative.get(index - 1) < cumulative.get(index)) result.add(origin + index - 1);
        }
        return Set.copyOf(result);
    }

    private static boolean ambiguousVertex(List<Double> cumulative, double target) {
        int matches = 0;
        for (double vertex : cumulative) {
            if (vertex == target && ++matches > 1) return true;
        }
        return false;
    }

    /** An occurrence owns only its two actual adjacent pieces, never a coincident occurrence's piece. */
    private static Set<Integer> incidentPieces(List<Double> cumulative, int occurrence, int origin) {
        Set<Integer> result = new HashSet<>();
        if (occurrence > 0 && cumulative.get(occurrence - 1) < cumulative.get(occurrence)) {
            result.add(origin + occurrence - 1);
        }
        if (occurrence + 1 < cumulative.size() && cumulative.get(occurrence) < cumulative.get(occurrence + 1)) {
            result.add(origin + occurrence);
        }
        return Set.copyOf(result);
    }
}
