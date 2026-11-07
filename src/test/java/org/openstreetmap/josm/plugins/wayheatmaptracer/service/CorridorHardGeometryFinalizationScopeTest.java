package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Actual finalization counterexamples prevent extrapolating raw-path preservation to all modes. */
class CorridorHardGeometryFinalizationScopeTest {
    @Test void moveModeCanRemoveHardRawReversalsSoRawAdmissionIsNotGloballyNecessary()throws Exception {
        var fixture=fixture(false);var original=(TraceRequest)call(fixture,"request");
        var request=new TraceRequest(original.selectedWayKey(),original.selectedRange(),original.engine(),AlignmentMode.MOVE_EXISTING_NODES,original.permissions(),original.budgets(),original.evidenceSnapshotId(),original.evidenceContentHash(),original.networkSnapshotId(),original.networkContentHash(),original.settingsHash(),original.parameterHash(),original.samplerId(),original.configuredSampleStepMeters(),original.profileChainage(),original.evidenceResolution());
        var raw=List.of(new MetricPoint(0,0),new MetricPoint(3,0),new MetricPoint(1,0),new MetricPoint(4,0));
        assertFalse(FinalGeometryEvaluator.backtrackFindings(raw).isEmpty());
        var result=finalize(fixture,request,raw,GeometryCleanupConfig.disabled());
        assertEquals(2,result.hypothesis().points().size());assertTrue(FinalGeometryEvaluator.backtrackFindings(result.hypothesis().points()).isEmpty());
    }
    @Test void cleanupMayRemoveRawVerticesAndProtectedInsertionsMayLeaveRawPolyline()throws Exception {
        var fixture=fixture(false);var request=(TraceRequest)call(fixture,"request");
        var straight=List.of(new MetricPoint(0,0),new MetricPoint(2,0),new MetricPoint(4,0));
        var cleaned=finalize(fixture,request,straight,GeometryCleanupConfig.disabled().withMode(GeometryCleanupMode.REDUCE_POINTS_ONLY));
        assertTrue(cleaned.hypothesis().points().size()<straight.size(),"Enabled cleanup must not be assumed to retain every raw triple");
        var protectedFixture=fixture(true);var protectedRequest=(TraceRequest)call(protectedFixture,"request");
        var raised=List.of(new MetricPoint(0,0),new MetricPoint(1,.5),new MetricPoint(3,.5),new MetricPoint(4,0));
        var result=finalize(protectedFixture,protectedRequest,raised,GeometryCleanupConfig.disabled());
        assertTrue(result.hypothesis().points().stream().anyMatch(p->Math.abs(p.xMeters()-2)<1e-8&&Math.abs(p.yMeters())<1e-8),"Actual protected source anchor must be inserted off the raised raw segment");
        assertFalse(raised.stream().anyMatch(p->Math.abs(p.xMeters()-2)<1e-8&&Math.abs(p.yMeters())<1e-8));
    }
    private static Object fixture(boolean protectedInterior)throws Exception{var c=Class.forName("org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.V022ModernTracePipelineTest");return call(c,"fixture",TrackerMode.CORRIDOR_AWARE,.1,1.0,protectedInterior);}
    private static ModernTracePipeline.Route finalize(Object fixture,TraceRequest request,List<MetricPoint> points,GeometryCleanupConfig cleanup)throws Exception {
        var evidence=(EvidenceSnapshot)call(fixture,"evidence");var network=(NetworkSnapshot)call(fixture,"network");
        var source=new TraceHypothesis("scope-counterexample","scope",points,Collections.nCopies(points.size(),ObservationOwnership.DIRECT_TWO_SIDED),0,OptionalDouble.empty(),Map.of());
        var inference=new TraceHypothesisSet(TrackerMode.CORRIDOR_AWARE,List.of(source),TraceHypothesisSet.Status.COMPLETE,false,0,0,"synthetic scope control");
        var options=new ModernTracePipeline.Options("native",cleanup,"native",0);var image=ImageCostField.fromEvidence(evidence,"native");
        return (ModernTracePipeline.Route)call(ModernTracePipeline.class,"finalizeRoute",source,inference,image,evidence,network,request,options,evidence.resolution().effectivePitchMeters(),CancellationProbe.NONE,Set.of(),SharedNodeDisconnectionPlanner.forRequest(request,network));
    }
    private static Object call(Object target,String name,Object...args)throws Exception{Class<?> c=target instanceof Class<?> t?t:target.getClass();for(Method m:c.getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){m.setAccessible(true);return m.invoke(target instanceof Class<?>?null:target,args);}throw new NoSuchMethodException(name);}
}
