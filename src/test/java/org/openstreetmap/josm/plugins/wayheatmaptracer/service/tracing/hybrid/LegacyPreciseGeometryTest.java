package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.DetachedValueVerifier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyPreciseGeometry.ProjectedPoint;

class LegacyPreciseGeometryTest {
    private final List<ProjectedPoint> source = List.of(
            new ProjectedPoint(0, 0), new ProjectedPoint(5, 0), new ProjectedPoint(10, 0));
    private final List<ProjectedPoint> traced = List.of(new ProjectedPoint(0, 14),
            new ProjectedPoint(2.5, 14), new ProjectedPoint(5, 14),
            new ProjectedPoint(7.5, 14), new ProjectedPoint(10, 14));

    @Test void restoresProtectedJunctionAndMapsItsExactOriginalOccurrence() {
        var result = LegacyPreciseGeometry.reconstruct(source, traced, List.of(1));
        assertEquals(List.of(source.get(0), traced.get(1), source.get(1), traced.get(3), source.get(2)),
                result.points());
        assertEquals(Map.of(0, 0, 1, 2, 2, 4), result.originalToVertex());
        DetachedValueVerifier.verify(result);
    }

    @Test void noFixedNodesKeepsLegacyTracedEndpointsRatherThanForcingOriginalOnes() {
        var result = LegacyPreciseGeometry.reconstruct(source, traced, List.of());
        assertEquals(traced, result.points());
        assertEquals(Map.of(0, 0, 2, 4), result.originalToVertex());
    }

    @Test void endpointProtectionPreservesOrdinaryTracedInteriorPoints() {
        var result = LegacyPreciseGeometry.reconstruct(source, traced, List.of(0, 2));
        assertEquals(List.of(source.get(0), traced.get(1), traced.get(2), traced.get(3), source.get(2)),
                result.points());
        assertEquals(Map.of(0, 0, 2, 4), result.originalToVertex());
        assertThrows(UnsupportedOperationException.class, () -> result.points().clear());
    }

    @Test void protectedIndicesOutsideTheSourceAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LegacyPreciseGeometry.reconstruct(source, traced, List.of(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> LegacyPreciseGeometry.reconstruct(source, traced, List.of(3)));
    }
}
