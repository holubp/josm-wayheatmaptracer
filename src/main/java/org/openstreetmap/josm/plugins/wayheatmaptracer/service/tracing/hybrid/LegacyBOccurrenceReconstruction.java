package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.TreeMap;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.MonotonicOccurrenceMatcher;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PolylineMath;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.Candidate;

/** Reuses ordinary mutable identities without changing native Legacy/B coordinates or interval boundaries. */
final class LegacyBOccurrenceReconstruction {
    private LegacyBOccurrenceReconstruction() { }

    static Candidate reuseAvailableVertices(LegacyBProductionRunner.Input input, Candidate candidate,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner owner) {
        var mapping = new TreeMap<>(candidate.originalToVertex());
        var way = (DetachedWay) input.network().primitives().get(input.request().selectedWayKey());
        var source = input.originalProjected().stream().map(point -> new EastNorth(point.east(), point.north())).toList();
        var sourceFractions = PolylineMath.fractionsForSegment(source);
        var projection = Projections.getProjectionByCode(input.projectionCode());
        var preview = candidate.points().stream().map(point -> {
            var geographic = input.evidence().coordinateFrame().toGeographic(point);
            return projection.latlon2eastNorth(new LatLon(geographic.latitudeDegrees(), geographic.longitudeDegrees()));
        }).toList();
        var previewFractions = PolylineMath.fractionsForSegment(preview);
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
        return new Candidate(candidate.id(), candidate.parentSeedId(), candidate.points(), mapping, candidate.sections());
    }
}
