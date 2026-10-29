package org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;

class V022SharedInteriorContactTest {
    private static final ExistingWayNodeOccurrence JUNCTION = new ExistingWayNodeOccurrence(
            PrimitiveKey.existing(PrimitiveKey.Type.WAY, 10), PrimitiveKey.existing(PrimitiveKey.Type.NODE, 2), 1);
    private static MetricPoint p(double x, double y) { return new MetricPoint(x, y); }
    private FinalGeometryEvaluator.Request request(List<MetricPoint> incident) {
        return new FinalGeometryEvaluator.Request("whole", List.of(p(0, 0), p(10, 0), p(20, 0)),
                List.<FinalRoutePointId>of(new FinalRoutePointId.GeneratedCandidatePoint("whole", 0),
                        JUNCTION, new FinalRoutePointId.GeneratedCandidatePoint("whole", 2)),
                QualityTestFixtures.constantImage(.8), 1, Map.of(), List.of(incident),
                false, false, false, false);
    }
    private boolean blocked(FinalGeometryEvaluator.Request request) {
        return new FinalGeometryEvaluator().evaluate(request).has(
                FinalGeometryEvaluator.FindingCode.PREJUNCTION_CROSSING);
    }

    @Test void exactProvenInteriorJunctionContactIsPreserved() {
        var request = request(List.of(p(10, 0), p(10, 8)));
        assertTrue(blocked(request));
        assertFalse(blocked(request.withSharedContacts(Map.of(0, Set.of(JUNCTION)))));
    }

    @Test void sharedInteriorNodeOfBothWaysIsPreserved() {
        var request = request(List.of(p(10, -8), p(10, 0), p(10, 8)));
        assertFalse(blocked(request.withSharedContacts(Map.of(0, Set.of(JUNCTION)))));
    }

    @Test void sharedNodeCannotExcuseAnotherRemoteCrossing() {
        var request = request(List.of(p(10, 0), p(10, 8), p(15, 8), p(15, -8)));
        assertTrue(blocked(request.withSharedContacts(Map.of(0, Set.of(JUNCTION)))));
    }

    @Test void sharedNodeCannotExcuseCollinearOverlap() {
        assertTrue(blocked(request(List.of(p(10, 0), p(18, 0)))
                .withSharedContacts(Map.of(0, Set.of(JUNCTION)))));
    }

    @Test void contactProofMustNameAnActualRetainedOccurrenceAndIncidentVertex() {
        var request = request(List.of(p(10, -8), p(10, 8)));
        assertThrows(IllegalArgumentException.class, () -> request.withSharedContacts(Map.of(0, Set.of(JUNCTION))));
        var absent = new ExistingWayNodeOccurrence(JUNCTION.wayKey(),
                PrimitiveKey.existing(PrimitiveKey.Type.NODE, 99), 1);
        assertThrows(IllegalArgumentException.class, () -> request(List.of(p(10, 0), p(10, 8)))
                .withSharedContacts(Map.of(0, Set.of(absent))));
        assertThrows(IllegalArgumentException.class, () -> request(List.of(p(10, 0), p(10, 8)))
                .withSharedContacts(Map.of(2, Set.of(JUNCTION))));
    }
}
