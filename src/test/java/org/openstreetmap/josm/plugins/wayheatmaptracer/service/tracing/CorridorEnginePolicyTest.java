package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageSupportedLocalCleanup;

class CorridorEnginePolicyTest {
    private static TraceEngineRun run(CorridorPolicyFixture.Fixture f,CancellationProbe probe){
        return new CorridorEngineAdapter("native").traceWithUsage(f.request(),f.evidence(),f.network(),probe);
    }
    @Test void realPublicEngineAdmitsAndMapsAnalyticFoldWithoutSelectingATrack(){
        var f=CorridorPolicyFixture.admittedParabola();
        var enabled=f.options().cleanup().withMode(GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE);
        var options=new ModernTracePipeline.Options("native",enabled,"synthetic",0);
        var pipeline=new ModernTracePipeline(new CorridorEngineAdapter("native"))
            .runWithUsage(f.request(),f.evidence(),f.network(),options,CancellationProbe.NONE);
        var receipt=pipeline.corridorReceipt().orElseThrow();var inference=pipeline.result().inference();
        assertFalse(inference.hypotheses().isEmpty(),inference.explanation());
        assertFalse(receipt.candidates().isEmpty(),()->"Actual native admission census: "+receipt.tracks());
        assertTrue(receipt.tracks().values().stream().anyMatch(t->t.admissionStatus().equals("ADMITTED")));
        for(var h:inference.hypotheses()){
            var provenance=receipt.candidates().get(h.id());if(provenance==null)continue;
            assertEquals(provenance.mapping().workingCount(),h.points().size());
            assertTrue(h.points().size()<f.request().profileChainage().cumulativeGroundMeters().size());
            assertEquals(h.points().size(),h.support().size());
            assertTrue(provenance.cleanupUnavailable());assertEquals(CleanupEvidenceStatus.MISALIGNED_CANDIDATE_ROWS,provenance.cleanupStatus());
            assertEquals(Double.valueOf(provenance.mapping().originalCount()),h.diagnostics().get("originalObservationProfiles"));
            assertTrue(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.backtrackFindings(h.points()).isEmpty());
            for(int i=0;i+1<h.points().size();i++)for(int j=i+2;j+1<h.points().size();j++)
                assertFalse(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.properIntersection(
                    h.points().get(i),h.points().get(i+1),h.points().get(j),h.points().get(j+1)));
        }
        for(var route:pipeline.result().routes())if(receipt.candidates().containsKey(route.rawHypothesis().id())){
            assertEquals(route.pointIds().size(),route.assignments().size());
            assertEquals(route.pointIds().size(),route.sourceOwnership().size());
            for(int i=0;i<route.pointIds().size();i++)assertEquals(route.hypothesis().points().get(i),route.assignments().get(route.pointIds().get(i)));
            assertFalse(route.quality().findings().stream().anyMatch(finding->finding.severity()==
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.Severity.HARD_BLOCK),()->route.quality().toString());
            assertNotEquals(ImageSupportedLocalCleanup.Status.CLEANED,route.cleanupStatus());
            assertNotEquals(ImageSupportedLocalCleanup.Status.PARTIALLY_CLEANED,route.cleanupStatus());
            assertEquals(1.0,route.hypothesis().diagnostics().getOrDefault("cleanupEvidenceMisalignedCandidateRows",0.0));
            assertTrue(route.hypothesis().points().containsAll(route.rawHypothesis().points()),"Native anchor reconstruction must retain the raw shortened geometry when cleanup is unavailable");
        }
        assertEquals(receipt.totals().unaryEvaluations(),inference.evaluatedStates());
        assertTrue(receipt.totals().bridgeUnaryEvaluations()>0);
        assertEquals(receipt.visitedTracks(),receipt.totals().solverStarts());
        assertEquals(175,receipt.totals().bridgeUnaryEvaluations(),"Native preparation measured25 admitted edges times7 original consumed observations");
        assertEquals(GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE,options.cleanup().mode());
        System.out.println("PUBLIC_ADMITTED_PIPELINE receipt="+receipt.tracks()+" totals="+receipt.totals()+" candidates="+receipt.candidates().keySet());
    }
    @Test void unsampledProtectedOccurrenceKeepsOriginalSolveAndAllRivals(){
        var f=CorridorPolicyFixture.parabolaWithProtectedBetweenRows();var run=run(f,CancellationProbe.NONE);
        var receipt=run.corridorReceipt().orElseThrow();
        assertEquals(32,receipt.sourceOccurrences().orElseThrow());
        assertEquals(31,receipt.sampling().orElseThrow().originalAnchorCount());
        assertEquals(3,receipt.fixedSourceCoordinates().orElseThrow());
        assertTrue(receipt.tracks().values().stream().anyMatch(t->t.admissionStatus().equals("REFUSED_SOURCE_PROTECTION_OR_SPAN")),()->receipt.tracks().toString());
        assertTrue(receipt.candidates().isEmpty());
        for(var t:receipt.tracks().values())assertEquals(1,t.preparation().solverStarts());
        var parallel=run(CorridorPolicyFixture.straight(true,true,Set.of()),CancellationProbe.NONE);
        var p=parallel.corridorReceipt().orElseThrow();assertTrue(p.discoveredTracks().orElseThrow()>1);
        assertEquals(p.discoveredTracks().orElseThrow(),p.visitedTracks());
        assertTrue(p.tracks().values().stream().allMatch(t->t.preparation().solverStarts()==1));
        assertTrue(parallel.result().hypotheses().size()>1,"Persistent rivals must remain native alternatives");
    }
    @Test void samplerCancellationIsUnavailableAndLaterCancellationRetainsSpentWork(){
        var f=CorridorPolicyFixture.straight(true,false,Set.of());var calls=new AtomicInteger();
        CancellationProbe sample=()->StackWalker.getInstance().walk(s->s.anyMatch(frame->frame.getMethodName().equals("radiusThreeContexts")))&&calls.incrementAndGet()==2;
        var cancelled=run(f,sample);var receipt=cancelled.corridorReceipt().orElseThrow();
        assertEquals(TraceHypothesisSet.Status.CANCELLED,cancelled.result().status());
        assertEquals(CorridorExecutionReceipt.Availability.UNAVAILABLE,receipt.samplingAvailability());
        assertTrue(receipt.sampling().isEmpty());assertEquals(0,receipt.totals().solverStarts());
        var cacheCalls=new AtomicInteger();CancellationProbe cache=()->StackWalker.getInstance().walk(s->s.anyMatch(frame->frame.getMethodName().equals("metricEdgeCache")))&&cacheCalls.incrementAndGet()==2;
        var late=run(f,cache);var actual=late.corridorReceipt().orElseThrow();
        assertEquals(TraceHypothesisSet.Status.CANCELLED,late.result().status());
        assertTrue(late.result().evaluatedStates()>0,"Completed unary work cannot disappear in cancellation catches");
        assertEquals(CorridorExecutionReceipt.Availability.COMPLETE,actual.samplingAvailability());
        assertEquals(1,actual.totals().solverStarts());assertEquals(0,actual.totals().adjacentObjectiveEntries());
    }
    @Test void noRouteAndResourceResultsRetainSamplingAndTruthfulDiscovery(){
        var no=run(CorridorPolicyFixture.straight(false,false,Set.of()),CancellationProbe.NONE);
        assertEquals(TraceHypothesisSet.Status.NO_ROUTE,no.result().status());assertTrue(no.result().hypotheses().isEmpty());
        assertEquals(CorridorExecutionReceipt.Availability.COMPLETE,no.corridorReceipt().orElseThrow().samplingAvailability());
        var f=CorridorPolicyFixture.straight(true,false,Set.of());var r=f.request();
        var limited=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),new TraceBudgets(96,1,1,32,8),
            r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),
            r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput());
        var resource=new CorridorEngineAdapter("native").traceWithUsage(limited,f.evidence(),f.network(),CancellationProbe.NONE);
        assertEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT,resource.result().status());
        assertTrue(resource.corridorReceipt().orElseThrow().discoveredTracks().orElseThrow()>0);
        assertEquals(0,resource.corridorReceipt().orElseThrow().totals().solverStarts());
    }
    @Test void finiteStateChordRefusalKeepsOneOrdinarySolveWithoutCompressedProvenance(){
        var f=CorridorPolicyFixture.parabola(Set.of());var run=run(f,CancellationProbe.NONE);
        var receipt=run.corridorReceipt().orElseThrow();assertTrue(receipt.candidates().isEmpty());
        assertTrue(receipt.tracks().values().stream().anyMatch(t->t.admissionStatus().equals("REFUSED_NO_ADMITTED_EDGES")));
        assertTrue(receipt.tracks().values().stream().allMatch(t->t.preparation().solverStarts()==1));
        assertEquals(0,receipt.totals().bridgeUnaryEvaluations());
    }
    @Test void finalizationCancellationRetainsCompletedEngineButNeverAResultOrRoutes(){
        var cancelled=CorridorPolicyFixture.finalizationCancellation();
        assertInstanceOf(CancellationException.class,cancelled.getCause());
        assertEquals("CANCELLED_AT_FINALIZATION",cancelled.overallOutcome());assertEquals("FINALIZATION",cancelled.phase());
        assertEquals(TraceHypothesisSet.Status.COMPLETE,cancelled.engineResult().status());
        assertTrue(cancelled.usage().transitions()>0);assertTrue(cancelled.receipt().totals().solverStarts()>0);
        assertEquals(cancelled.engineResult().evaluatedTransitions(),cancelled.usage().transitions());
    }
}
