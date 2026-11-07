package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class V022BudgetedFrontierReasonTest {
    @Test void typedReviewCodeUsesApprovedReadableCopyForAvailablePlans() {
        String copy="Refinement could not fit within the work limit. This preview uses the completed initial search and requires review.";
        assertEquals(copy,AlignWayAction.modernPlanFindingSummary("modern-final:REFINEMENT_NOT_PERFORMED_BUDGET"));
        assertEquals("modern-final:SELF_INTERSECTION",AlignWayAction.modernPlanFindingSummary("modern-final:SELF_INTERSECTION"));
    }
}
