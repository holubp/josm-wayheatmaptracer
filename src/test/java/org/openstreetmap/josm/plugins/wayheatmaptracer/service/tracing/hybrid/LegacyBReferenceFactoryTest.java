package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;

class LegacyBReferenceFactoryTest {
    private final LegacyBModels.Seed seed = new LegacyBModels.Seed("legacy-0",
            IntStream.range(0, 7).mapToObj(i -> new MetricPoint(i * 10, 0)).toList(),
            Map.of(10, 0, 11, 1, 12, 2, 13, 3, 14, 4, 15, 5, 16, 6));

    @Test void selectedSubrangeSplitsAtTwoJunctionsWithoutSplittingAtOrdinaryNodes() {
        var sections = new LegacyBReferenceFactory().partition(seed, new OccurrenceRange(10, 16),
                Set.of(12, 14), List.of());
        assertEquals(List.of(new OccurrenceRange(10, 12), new OccurrenceRange(12, 14),
                new OccurrenceRange(14, 16)), sections.stream().map(LegacyBModels.Section::originalRange).toList());
        assertEquals(List.of(false, false, false), sections.stream().map(LegacyBModels.Section::frozen).toList());
        assertEquals(new MetricPoint(20, 0), sections.get(0).reference().get(2));
        assertEquals(sections.get(0).reference().get(2), sections.get(1).reference().get(0));
        assertEquals(Map.of(12, 0, 13, 1, 14, 2), sections.get(1).originalToVertex());
        assertTrue(sections.stream().allMatch(section -> section.parentSeedId().equals("legacy-0")));
    }

    @Test void frozenVicinityDoesNotDisableItsTwoEligibleNeighbors() {
        var sections = new LegacyBReferenceFactory().partition(seed, new OccurrenceRange(10, 16),
                Set.of(12, 14), List.of(new OccurrenceRange(12, 14)));
        assertEquals(List.of(false, true, false), sections.stream().map(LegacyBModels.Section::frozen).toList());
        assertEquals(List.of(new MetricPoint(20, 0), new MetricPoint(30, 0), new MetricPoint(40, 0)),
                sections.get(1).reference());
    }

    @Test void scopeAndMissingProtectedOccurrenceFailClosed() {
        var factory = new LegacyBReferenceFactory();
        assertThrows(IllegalArgumentException.class, () -> factory.partition(seed,
                new OccurrenceRange(11, 16), Set.of(12), List.of()));
        assertThrows(IllegalArgumentException.class, () -> factory.partition(seed,
                new OccurrenceRange(10, 16), Set.of(9), List.of()));
        var missing = new LegacyBModels.Seed("legacy-0", seed.points(), Map.of(10, 0, 16, 6));
        assertThrows(IllegalArgumentException.class, () -> factory.partition(missing,
                new OccurrenceRange(10, 16), Set.of(12), List.of()));
    }

    @Test void frozenVicinityCannotContainExtraLegacyVerticesOrLoseOriginalNodes() {
        var altered = new LegacyBModels.Seed("legacy-0", seed.points(), Map.of(10, 0, 12, 2, 14, 4, 16, 6));
        assertThrows(IllegalArgumentException.class, () -> new LegacyBReferenceFactory().partition(altered,
                new OccurrenceRange(10, 16), Set.of(), List.of(new OccurrenceRange(12, 14))));
    }
}
