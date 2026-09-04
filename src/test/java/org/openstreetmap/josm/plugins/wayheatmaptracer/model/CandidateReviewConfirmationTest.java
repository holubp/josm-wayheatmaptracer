package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;

/** Verifies that review confirmation is bound to one exact immutable candidate preview. */
class CandidateReviewConfirmationTest {
    @Test
    void confirmationMatchesOnlyTheReviewedCandidateGeometryAndAssignments() {
        List<EastNorth> geometry = List.of(new EastNorth(0, 0), new EastNorth(10, 1));
        CenterlineCandidate reviewed = new CenterlineCandidate("hot/strand-1", 1.0, List.of(), List.of())
            .withFinalPreviewGeometry(geometry, Map.of(7L, geometry.get(1)));
        CandidateReviewConfirmation confirmation = CandidateReviewConfirmation.capture(reviewed, geometry);

        assertTrue(confirmation.matches(reviewed, geometry));
        assertFalse(confirmation.matches(reviewed.withId("hot/strand-2"), geometry));
        assertFalse(confirmation.matches(reviewed,
            List.of(new EastNorth(0, 0), new EastNorth(10, 2))));
        assertFalse(confirmation.matches(reviewed.withFinalPreviewGeometry(
            geometry, Map.of(7L, new EastNorth(10, 2))), geometry));
    }
}
