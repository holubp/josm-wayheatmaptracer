package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.PreviewReviewState;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;

class V022BudgetedFrontierReviewTest {
    private static ModernTracePipeline.Route rankedRoute(String id,boolean budgetReview) {
        return rankedRoute(id,budgetReview,.2);
    }
    private static ModernTracePipeline.Route rankedRoute(String id,boolean budgetReview,double fit) {
        var points=List.of(new MetricPoint(0,0),new MetricPoint(10,0));
        var support=List.of(ObservationOwnership.DIRECT_TWO_SIDED,ObservationOwnership.DIRECT_TWO_SIDED);
        var hypothesis=new TraceHypothesis(id,"same-branch",points,support,0,1,Map.of());
        List<FinalRoutePointId> ids=List.of(new FinalRoutePointId.GeneratedCandidatePoint(id,0),
                new FinalRoutePointId.GeneratedCandidatePoint(id,1));
        var quality=new Result(id,Disposition.APPLICABLE,List.of(),10,10,0,fit,.1);
        if(budgetReview)quality=FinalGeometryEvaluator.withRefinementBudgetReview(quality,1,2);
        return new ModernTracePipeline.Route(hypothesis,hypothesis,ids,Map.of(ids.get(0),points.get(0),ids.get(1),points.get(1)),
                Map.of(ids.get(0),support.get(0),ids.get(1),support.get(1)),quality,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageSupportedLocalCleanup.Status.SKIPPED,false);
    }
    @Test void budgetReviewHasNoNumericalPenaltyInActualProductionRanking() {
        var options=new ModernTracePipeline.Options("native",GeometryCleanupConfig.disabled(),"native",0);
        var original=ModernTracePipeline.rankRoutes(List.of(rankedRoute("a",false),rankedRoute("z",false)),TrackerMode.PROBABILISTIC,options);
        var reviewed=ModernTracePipeline.rankRoutes(List.of(rankedRoute("a",true),rankedRoute("z",false)),TrackerMode.PROBABILISTIC,options);
        assertEquals(original.stream().map(r->r.hypothesis().id()).toList(),
            reviewed.stream().map(r->r.hypothesis().id()).toList());
        var byFit=ModernTracePipeline.rankRoutes(List.of(rankedRoute("z",true,.1),rankedRoute("a",false,.2)),TrackerMode.PROBABILISTIC,options);
        assertEquals(List.of("z","a"),byFit.stream().map(r->r.hypothesis().id()).toList());
    }
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
