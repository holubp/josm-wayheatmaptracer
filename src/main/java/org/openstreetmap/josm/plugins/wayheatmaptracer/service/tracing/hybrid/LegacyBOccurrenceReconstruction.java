package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.MonotonicOccurrenceMatcher;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PolylineMath;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.Candidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.SectionChoice;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.Subdivision;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.VertexRange;

/** Reuses ordinary identities on native vertices or explicitly proved subdivisions of native edges. */
final class LegacyBOccurrenceReconstruction {
    private LegacyBOccurrenceReconstruction() { }

    static Candidate reuseAvailableVertices(LegacyBProductionRunner.Input input, Candidate candidate,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner owner) {
        long scratchBytes = Math.addExact(1024, Math.multiplyExact(512L,
                (long) input.originalMetric().size() + candidate.points().size()));
        try (var reconstructionScratch = owner.reserve(scratchBytes)) {
        var way = (DetachedWay) input.network().primitives().get(input.request().selectedWayKey());
        var source = input.originalProjected().stream().map(point -> new EastNorth(point.east(), point.north())).toList();
        var sourceFractions = PolylineMath.fractionsForSegment(source);
        candidate = ensureRequiredSlots(input, candidate, sourceFractions, fractions(input, candidate), cancellation, owner);
        var mapping = new TreeMap<>(candidate.originalToVertex());
        var previewFractions = fractions(input, candidate);
        var anchors = new ArrayList<>(mapping.entrySet());
        for (int boundary = 1; boundary < anchors.size(); boundary++) {
            cancellation.checkpoint();
            var left = anchors.get(boundary - 1); var right = anchors.get(boundary);
            var occurrences = new ArrayList<Integer>();
            for (int occurrence = left.getKey() + 1; occurrence < right.getKey(); occurrence++) {
                if (input.network().closure().movableExistingNodeKeys().contains(way.nodeKeys().get(occurrence)))
                    occurrences.add(occurrence);
            }
            if (occurrences.isEmpty()) continue;
            var slots = new ArrayList<Integer>();
            for (int vertex = left.getValue() + 1; vertex < right.getValue(); vertex++) slots.add(vertex);
            try (var scratch = owner.reserve(MonotonicOccurrenceMatcher.workingBytes(occurrences.size(), slots.size()))) {
                var matches = MonotonicOccurrenceMatcher.match(occurrences.stream().map(occurrence ->
                                sourceFractions.get(occurrence - input.request().selectedRange().firstIndex())).toList(),
                        slots.stream().map(previewFractions::get).toList(),
                        Collections.nCopies(occurrences.size(), 0), Collections.nCopies(slots.size(), 0), cancellation);
                matches.forEach((slot, occurrence) -> mapping.put(occurrences.get(occurrence), slots.get(slot)));
            }
        }
        return new Candidate(candidate.id(), candidate.parentSeedId(), candidate.points(), mapping,
                candidate.sections(), candidate.subdivisions());
        }
    }

    private static List<Double> fractions(LegacyBProductionRunner.Input input, Candidate candidate) {
        var projection = Projections.getProjectionByCode(input.projectionCode());
        return PolylineMath.fractionsForSegment(candidate.points().stream().map(point -> {
            var geographic = input.evidence().coordinateFrame().toGeographic(point);
            return projection.latlon2eastNorth(new LatLon(geographic.latitudeDegrees(), geographic.longitudeDegrees()));
        }).toList());
    }

    private static Candidate ensureRequiredSlots(LegacyBProductionRunner.Input input, Candidate candidate,
            List<Double> sourceFractions, List<Double> previewFractions, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner) {
        if (!candidate.subdivisions().isEmpty()) throw new IllegalArgumentException("Native candidate was already reconstructed");
        var way = (DetachedWay) input.network().primitives().get(input.request().selectedWayKey());
        var anchors = new ArrayList<>(new TreeMap<>(candidate.originalToVertex()).entrySet());
        var insertions = new TreeMap<Integer,List<Subdivision>>();
        int first = input.request().selectedRange().firstIndex();
        for (int boundary = 1; boundary < anchors.size(); boundary++) {
            cancellation.checkpoint();
            var left = anchors.get(boundary - 1); var right = anchors.get(boundary);
            var required = new ArrayList<Integer>();
            for (int occurrence = left.getKey() + 1; occurrence < right.getKey(); occurrence++)
                if (input.network().closure().movableExistingNodeKeys().contains(way.nodeKeys().get(occurrence)))
                    required.add(occurrence);
            if (required.size() <= right.getValue() - left.getValue() - 1) continue;
            var nativeSlots = new ArrayList<Integer>();
            for (int vertex = left.getValue() + 1; vertex < right.getValue(); vertex++) nativeSlots.add(vertex);
            var ownership = new TreeMap<Integer,Integer>();
            ownership.put(-1,left.getValue()); ownership.put(required.size(),right.getValue());
            try (var scratch = owner.reserve(MonotonicOccurrenceMatcher.workingBytes(required.size(), nativeSlots.size()))) {
                var matches = MonotonicOccurrenceMatcher.match(
                        required.stream().map(occurrence -> sourceFractions.get(occurrence - first)).toList(),
                        nativeSlots.stream().map(previewFractions::get).toList(),
                        Collections.nCopies(required.size(), 0), Collections.nCopies(nativeSlots.size(), 0),
                        cancellation);
                if (matches.size() != nativeSlots.size())
                    throw new IllegalArgumentException("Native reuse did not consume every available ordinary slot");
                matches.forEach((slot,ordinal) -> ownership.put(ordinal,nativeSlots.get(slot)));
            }
            var owners = new ArrayList<>(ownership.entrySet());
            for (int bracket = 1; bracket < owners.size(); bracket++) {
                cancellation.checkpoint();
                var before = owners.get(bracket - 1); var after = owners.get(bracket);
                int missing = after.getKey() - before.getKey() - 1;
                if (missing == 0) continue;
                int edge = before.getValue();
                if (after.getValue() != edge + 1)
                    throw new IllegalArgumentException("Unmatched occurrences must lie between adjacent reused native slots");
                int originalLeft = before.getKey() < 0 ? left.getKey() : required.get(before.getKey());
                int originalRight = after.getKey() == required.size() ? right.getKey() : required.get(after.getKey());
                double sourceStart = sourceFractions.get(originalLeft - first);
                double sourceSpan = sourceFractions.get(originalRight - first) - sourceStart;
                var start = candidate.points().get(edge); var end = candidate.points().get(edge + 1);
                double scale = Math.max(1, Math.max(Math.max(Math.abs(start.xMeters()), Math.abs(start.yMeters())),
                        Math.max(Math.abs(end.xMeters()), Math.abs(end.yMeters()))));
                double rounding = 32 * Math.ulp(scale);
                var fractions = new double[missing]; boolean ordinalFallback = sourceSpan <= 0;
                for (int index = 0; index < missing; index++) {
                    cancellation.checkpoint();
                    int occurrence = required.get(before.getKey() + index + 1);
                    double fraction = sourceSpan > 0 ? (sourceFractions.get(occurrence - first) - sourceStart) / sourceSpan : 0;
                    fractions[index] = fraction;
                    var inserted = new Subdivision(edge,Math.max(0,Math.min(1,fraction))).interpolate(start,end);
                    ordinalFallback |= fraction <= 0 || fraction >= 1 || index > 0 && fraction <= fractions[index - 1]
                            || Math.hypot(inserted.xMeters()-start.xMeters(),inserted.yMeters()-start.yMeters()) <= rounding
                            || Math.hypot(inserted.xMeters()-end.xMeters(),inserted.yMeters()-end.yMeters()) <= rounding;
                }
                for (int index = 0; index < missing; index++) {
                    double fraction = ordinalFallback ? (double) (index + 1) / (missing + 1) : fractions[index];
                    insertions.computeIfAbsent(edge, ignored -> new ArrayList<>()).add(new Subdivision(edge,fraction));
                }
            }
        }
        if (insertions.isEmpty()) return candidate;
        var points = new ArrayList<MetricPoint>(); points.add(candidate.points().get(0));
        var nativeToFinal = new TreeMap<Integer,Integer>(); nativeToFinal.put(0, 0);
        var subdivisions = new TreeMap<Integer,Subdivision>();
        for (int edge = 0; edge < candidate.points().size() - 1; edge++) {
            cancellation.checkpoint();
            for (var subdivision : insertions.getOrDefault(edge, List.of()).stream()
                    .sorted(java.util.Comparator.comparingDouble(Subdivision::fraction)).toList()) {
                subdivisions.put(points.size(), subdivision);
                points.add(subdivision.interpolate(candidate.points().get(edge), candidate.points().get(edge + 1)));
            }
            nativeToFinal.put(edge + 1, points.size()); points.add(candidate.points().get(edge + 1));
        }
        var mapping = new TreeMap<Integer,Integer>();
        candidate.originalToVertex().forEach((occurrence, vertex) -> mapping.put(occurrence, nativeToFinal.get(vertex)));
        var choices = candidate.sections().stream().map(choice -> new SectionChoice(choice.section(),
                new VertexRange(nativeToFinal.get(choice.vertices().first()), nativeToFinal.get(choice.vertices().last())),
                choice.source(), choice.outcome(), choice.reason(), choice.hypothesisId())).toList();
        return new Candidate(candidate.id(), candidate.parentSeedId(), points, mapping, choices, subdivisions);
    }
}
