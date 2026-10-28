package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBModels.Seed;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBModels.Section;

/** Separates temporary reference geometry while retaining original occurrence ownership. */
public final class LegacyBReferenceFactory {
    /**
     * Splits at protected occurrences and frozen-range boundaries, never at ordinary
     * movable nodes. Callers supply protection from the captured original authority;
     * this geometric operation grants no new movement permission.
     */
    public List<Section> partition(Seed seed, OccurrenceRange selectedRange,
            Set<Integer> protectedOccurrences, List<OccurrenceRange> frozenRanges) {
        if (seed == null || selectedRange == null || protectedOccurrences == null || frozenRanges == null) {
            throw new IllegalArgumentException("Selected reference and original protection are required");
        }
        var mapping = new TreeMap<>(seed.originalToVertex());
        if (mapping.firstKey() != selectedRange.firstIndex() || mapping.lastKey() != selectedRange.lastIndex()) {
            throw new IllegalArgumentException("Reference does not match the original selected range");
        }
        var cuts = new TreeSet<Integer>();
        cuts.add(selectedRange.firstIndex());
        cuts.add(selectedRange.lastIndex());
        for (Integer occurrence : protectedOccurrences) {
            requireMapped(occurrence, selectedRange, mapping);
            cuts.add(occurrence);
        }
        for (OccurrenceRange frozen : frozenRanges) {
            if (frozen == null) { throw new IllegalArgumentException("Frozen range is required"); }
            requireMapped(frozen.firstIndex(), selectedRange, mapping);
            requireMapped(frozen.lastIndex(), selectedRange, mapping);
            int previousVertex = -1;
            for (int occurrence = frozen.firstIndex(); ; occurrence++) {
                requireMapped(occurrence, selectedRange, mapping);
                int vertex = mapping.get(occurrence);
                if (previousVertex >= 0 && vertex != previousVertex + 1) {
                    throw new IllegalArgumentException("Frozen section contains inserted reference geometry");
                }
                previousVertex = vertex;
                if (occurrence == frozen.lastIndex()) { break; }
            }
            cuts.add(frozen.firstIndex());
            cuts.add(frozen.lastIndex());
        }
        var boundaries = new ArrayList<>(cuts);
        var result = new ArrayList<Section>();
        for (int i = 1; i < boundaries.size(); i++) {
            int first = boundaries.get(i - 1), last = boundaries.get(i);
            int fromVertex = mapping.get(first), toVertex = mapping.get(last);
            var localMapping = new TreeMap<Integer,Integer>();
            mapping.subMap(first, true, last, true).forEach((occurrence, vertex) ->
                    localMapping.put(occurrence, vertex - fromVertex));
            boolean frozen = frozenRanges.stream().anyMatch(range ->
                    Math.max(first, range.firstIndex()) < Math.min(last, range.lastIndex()));
            result.add(new Section(seed.id() + "/section-" + (i - 1), seed.id(),
                    new OccurrenceRange(first, last), seed.points().subList(fromVertex, toVertex + 1),
                    localMapping, frozen));
        }
        return List.copyOf(result);
    }

    private static void requireMapped(Integer occurrence, OccurrenceRange range, Map<Integer,Integer> mapping) {
        if (occurrence == null || occurrence < range.firstIndex() || occurrence > range.lastIndex()
                || !mapping.containsKey(occurrence)) {
            throw new IllegalArgumentException("Protected original occurrence is absent from the reference");
        }
    }
}
