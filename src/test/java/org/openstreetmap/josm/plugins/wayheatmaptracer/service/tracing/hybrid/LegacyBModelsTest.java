package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;

class LegacyBModelsTest {
    private static final List<MetricPoint> POINTS = List.of(
            new MetricPoint(0, 0), new MetricPoint(10, 3), new MetricPoint(20, 0));

    @Test void seedCopiesTemporaryGeometryAndOriginalOccurrenceAuthority() {
        var points = new ArrayList<>(POINTS);
        var mapping = new HashMap<>(Map.of(4, 0, 6, 1, 9, 2));
        var seed = new LegacyBModels.Seed("legacy-0", points, mapping);
        points.clear(); mapping.clear();
        assertEquals(POINTS, seed.points());
        assertEquals(Map.of(4, 0, 6, 1, 9, 2), seed.originalToVertex());
        assertThrows(UnsupportedOperationException.class, () -> seed.points().clear());
        assertThrows(UnsupportedOperationException.class, () -> seed.originalToVertex().clear());
    }

    @Test void reversedDuplicatedAndOutOfBoundsOccurrenceMappingsAreRejected() {
        for (Map<Integer,Integer> bad : List.of(Map.of(4, 2, 9, 0), Map.of(4, 0, 6, 0, 9, 2),
                Map.of(4, 0, 9, 3), Map.of(-1, 0, 9, 2), Map.of(4, 1, 9, 2))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new LegacyBModels.Seed("legacy-0", POINTS, bad));
        }
    }

    @Test void sectionBindsParentAndBothOriginalBoundaryOccurrences() {
        var section = new LegacyBModels.Section("part-0", "legacy-0", new OccurrenceRange(4, 9),
                POINTS, Map.of(4, 0, 9, 2), false);
        assertEquals("legacy-0", section.parentSeedId());
        assertEquals(new OccurrenceRange(4, 9), section.originalRange());
        assertThrows(IllegalArgumentException.class, () -> new LegacyBModels.Section("part-0", "",
                new OccurrenceRange(4, 9), POINTS, Map.of(4, 0, 9, 2), false));
        assertThrows(IllegalArgumentException.class, () -> new LegacyBModels.Section("part-0", "legacy-0",
                new OccurrenceRange(4, 9), POINTS, Map.of(3, 0, 9, 2), false));
        assertThrows(IllegalArgumentException.class, () -> new LegacyBModels.Section("part-0", "legacy-0",
                new OccurrenceRange(4, 9), POINTS, Map.of(4, 0, 8, 2), false));
    }

    @Test void degenerateSeedCannotBePresentedAsARefinementReference() {
        assertThrows(IllegalArgumentException.class, () -> new LegacyBModels.Seed("legacy-0",
                List.of(new MetricPoint(0, 0), new MetricPoint(0, 0)), Map.of(0, 0, 1, 1)));
    }

    @Test void degenerateLocalSectionCanBeRetainedWithoutLosingItsDistinctOccurrences() {
        var section = new LegacyBModels.Section("zero-length", "legacy-0", new OccurrenceRange(4, 5),
                List.of(new MetricPoint(0, 0), new MetricPoint(0, 0)), Map.of(4, 0, 5, 1), false);
        assertEquals(Map.of(4, 0, 5, 1), section.originalToVertex());
        assertEquals(2, section.reference().size());
    }
}
