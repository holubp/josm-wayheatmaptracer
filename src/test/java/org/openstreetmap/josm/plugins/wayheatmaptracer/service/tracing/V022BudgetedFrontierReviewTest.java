package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState;

class V022BudgetedFrontierReviewTest {
    @Test void exactNativeMarkerAddsReviewWithNoMetricOrRankingChange() {
        var original=new Result("route",Disposition.APPLICABLE,List.of(),10,10,0,.2,.1);
        for(double marker:new double[]{0,Math.nextDown(1.),Math.nextUp(1.),Double.NaN})assertSame(original,
            FinalGeometryEvaluator.withRefinementBudgetReview(original,marker,3));
        var review=FinalGeometryEvaluator.withRefinementBudgetReview(original,1,3);
        assertEquals(Disposition.REVIEW_REQUIRED,review.disposition());
        assertTrue(review.has(FindingCode.REFINEMENT_NOT_PERFORMED_BUDGET));
        assertEquals(original.meanImageCenterCost(),review.meanImageCenterCost());
        assertEquals(original.bendPreservingRoughness(),review.bendPreservingRoughness());
        assertEquals(original.directlySupportedLengthMeters(),review.directlySupportedLengthMeters());
        var state=PreviewReviewState.create("route","plan","permission","source",ValidationReport.Disposition.REVIEW_REQUIRED);
        assertFalse(state.canApply());assertTrue(state.confirm().canApply());
        assertFalse(state.confirm().withCandidate("other").confirmed());
    }
    @Test void existingHardBlocksRemainDominantAndCannotBeConfirmed() {
        var original=new Result("route",Disposition.HARD_BLOCKED,
            List.of(new Finding(FindingCode.PROTECTED_ASSIGNMENT_MISMATCH,Severity.HARD_BLOCK,0,0,0)),10,10,0,.2,.1);
        var result=FinalGeometryEvaluator.withRefinementBudgetReview(original,1,3);
        assertEquals(Disposition.HARD_BLOCKED,result.disposition());assertTrue(result.has(FindingCode.PROTECTED_ASSIGNMENT_MISMATCH));
        assertTrue(result.has(FindingCode.REFINEMENT_NOT_PERFORMED_BUDGET));
        assertThrows(IllegalStateException.class,()->PreviewReviewState.create("route","plan","permission","source",
            ValidationReport.Disposition.HARD_BLOCKED).confirm());
    }
}
