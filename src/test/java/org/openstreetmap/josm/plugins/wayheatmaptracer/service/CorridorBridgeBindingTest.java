package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Cross-stage admission identity, preservation and current-attempt work regressions. */
class CorridorBridgeBindingTest {
    static final RasterMetricTransform FRAME=RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);

    @Test void objectiveAdjacencyReportMatchesSuccessAndResetsForNextTrack()throws Exception{
        var f=fixture();var optimizer=guarded(CancellationProbe.NONE);var context=context();
        var domain=original(optimizer,f,context);var result=optimizer.optimizeOriginalDomain(domain);
        assertTrue(result.adjacentGeometryEntries()>0);
        assertEquals(result.adjacentGeometryEntries(),optimizer.preparedAdjacentObjectiveEntries());
        original(optimizer,f,context);
        assertEquals(0,optimizer.preparedAdjacentObjectiveEntries());
    }

    @Test void physicalEdgeCacheCancellationDoesNotInventObjectiveEntries()throws Exception{
        var f=fixture();var calls=new java.util.concurrent.atomic.AtomicInteger();
        CancellationProbe cancel=()->StackWalker.getInstance().walk(frames->frames.anyMatch(frame->frame.getMethodName().equals("metricEdgeCache")))
            &&calls.incrementAndGet()==f.profiles().size()+3;
        var optimizer=guarded(cancel);var domain=original(optimizer,f,context());
        assertThrows(java.util.concurrent.CancellationException.class,()->optimizer.optimizeOriginalDomain(domain));
        assertTrue(optimizer.hardGeometryWork().metricEdges()>0);
        assertEquals(0,optimizer.preparedAdjacentObjectiveEntries());
    }

    @Test void cancellationAfterObjectiveWorkKeepsPositiveActualEntries()throws Exception{
        var f=fixture();var holder=new CorridorCenterlineOptimizer[1];
        holder[0]=guarded(()->holder[0].preparedAdjacentObjectiveEntries()>0);
        var domain=original(holder[0],f,context());
        assertThrows(java.util.concurrent.CancellationException.class,()->holder[0].optimizeOriginalDomain(domain));
        assertTrue(holder[0].preparedAdjacentObjectiveEntries()>0);
    }

    @Test void equalStateCoordinatesCannotReuseAdmissionFromAnotherOriginalDomain()throws Exception{
        var f=fixture();var optimizer=guarded(()->false);var context=context();
        var first=original(optimizer,f,context);var admission=prepare(first,context);
        assertTrue(admission.admitted(),admission::toString);
        var second=original(optimizer,f,context);assertEquals(first.states(),second.states());
        assertThrows(IllegalArgumentException.class,()->optimizer.optimizePrepared(second,admission),
            "A different original invocation cannot inherit native/protection admission merely because offsets match");
        assertEquals(0,optimizer.preparedWork().retainedUnaryEvaluations());
    }

    @Test void factualHardGeometryFrameMustMatchAdmittedFrameBeforeAnyCosts()throws Exception{
        var f=fixture();var optimizer=guarded(()->false);var context=context();var domain=original(optimizer,f,context);
        var admission=prepare(domain,context);assertTrue(admission.admitted());
        var wrong=new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(
            RasterMetricTransform.metricGrid(new MetricPoint(100,200),0,1,-1,0),()->false);
        assertThrows(IllegalArgumentException.class,()->wrong.optimizePrepared(domain,admission));
        assertEquals(0,wrong.preparedWork().retainedUnaryEvaluations());
    }

    @Test void immutableDomainAndNativeProtectionTokensRejectStaleContextsBeforeAdmission()throws Exception{
        var f=fixture();var optimizer=guarded(()->false);var context=(CorridorWorkingSchedule.BoundContext)context();
        assertNotNull(context);var d=original(optimizer,f,context);var p=prepare(d,context);
        var stale=(CorridorWorkingSchedule.BoundContext)context();
        assertThrows(IllegalArgumentException.class,()->CorridorWorkingSchedule.prepare(d,stale,1,CancellationProbe.NONE));
        var exposed=d.profiles();double originalX=d.profiles().get(0).source().normalScreen().x;
        exposed.get(0).source().normalScreen().x=123;
        assertEquals(originalX,d.profiles().get(0).source().normalScreen().x,"Caller mutation cannot change the frozen original normal");
        var changed=optimizer.prepareOriginalDomain(d.track(),exposed,1,d.junctions(),d.scales(),d.tube(),d.budgets(),context);
        assertThrows(IllegalArgumentException.class,()->optimizer.optimizePrepared(changed,p));
        assertEquals(0,optimizer.preparedWork().retainedUnaryEvaluations());assertEquals(0,optimizer.preparedWork().solverStarts());
        assertEquals(0,CorridorWorkingSchedule.Preparation.class.getConstructors().length,"Public admitted preparations are sealed");
        assertThrows(IllegalArgumentException.class,()->CorridorWorkingSchedule.refusal("ADMITTED"));
    }

    @Test void admissionCancellationOnSecondTrackRetainsBothDistinctWorkReceipts()throws Exception{
        var f=PhysicalGeometryFixture.parallelFrame(.95);var calls=new java.util.concurrent.atomic.AtomicInteger();
        var tracker=CorridorAwareTracker.withTargetOwnedBridgeCorrespondence(guarded(()->false),d->{
            if(calls.incrementAndGet()==1)return refusal();
            return CorridorWorkingSchedule.prepare(d,FRAME,1,new CorridorWorkingSchedule.SourceProtection(0,10000,List.of()),(a,b)->true,()->true);
        });
        assertThrows(CorridorWorkingSchedule.PreparationCancelledException.class,()->tracker.trackDetailed(f.profileSet(),f.sourcePixelPitch(),JunctionContext.empty()));
        assertEquals(2,tracker.correspondenceWork().size());
        assertEquals(1,tracker.correspondenceWork().values().stream().filter(w->w.preparation().solverStarts()==1).count());
        var cancelled=tracker.correspondenceWork().values().stream().filter(w->w.preparation().outcome().equals("CANCELLED")).findFirst().orElseThrow();
        assertEquals(0,cancelled.preparation().solverStarts());assertTrue(cancelled.preparation().originalStates()>0);
        assertEquals("CANCELLED",cancelled.admission().orElseThrow().status());
        assertEquals(0,cancelled.hardGeometry().profileCostEvaluations());
    }

    @Test void changedTubeAndEndpointEvidenceCannotReuseEqualStateAdmission()throws Exception{
        var f=fixture();var optimizer=guarded(()->false);var context=(CorridorWorkingSchedule.BoundContext)context();
        var original=original(optimizer,f,context);var admission=prepare(original,context);
        var slices=new ArrayList<>(original.tube().slices());var s=slices.get(0);var components=CorridorTubeSlice.class.getRecordComponents();
        var types=new Class<?>[components.length];var values=new Object[components.length];
        for(int i=0;i<components.length;i++){types[i]=components[i].getType();values[i]=components[i].getAccessor().invoke(s);if(components[i].getName().equals("confidence"))values[i]=.123;}
        slices.set(0,CorridorTubeSlice.class.getDeclaredConstructor(types).newInstance(values));
        var changed=optimizer.prepareOriginalDomain(original.track(),original.profiles(),1,original.junctions(),original.scales(),new LongitudinalCorridorTube(slices),original.budgets(),context);
        assertEquals(original.states(),changed.states());assertThrows(IllegalArgumentException.class,()->optimizer.optimizePrepared(changed,admission));
        var firstPorts=new JunctionContext(List.of(new EndpointConstraint(0,1,false,false,100,.1,0)));
        var d1=optimizer.prepareOriginalDomain(f.track,f.profiles,1,firstPorts,Map.of(),f.tube,TraceBudgets.defaults(),context);
        var p1=prepare(d1,context);assertEquals("REFUSED_ENDPOINT_DEPENDENCY",p1.status());assertFalse(d1.approaches().approaches().isEmpty());
        var secondPorts=new JunctionContext(List.of(new EndpointConstraint(0,1,false,false,100,.2,0)));
        var d2=optimizer.prepareOriginalDomain(f.track,f.profiles,1,secondPorts,Map.of(),f.tube,TraceBudgets.defaults(),context);
        assertEquals(d1.states(),d2.states());assertThrows(IllegalArgumentException.class,()->optimizer.optimizePrepared(d2,admission));
        assertEquals(0,optimizer.preparedWork().solverStarts(),"Changed endpoint context cannot inherit the earlier admitted result");
    }

    @Test void tableEstimateIsOverflowSafeAndDoesNotPretendToReserveHeap(){
        assertTrue(CorridorCenterlineOptimizer.estimatePreparedTableBytes(10,4,3,10,4,1,1,6)>0);
        assertThrows(ArithmeticException.class,()->CorridorCenterlineOptimizer.estimatePreparedTableBytes(Long.MAX_VALUE,1,1,1,1,1,1,1));
        assertThrows(IllegalArgumentException.class,()->CorridorCenterlineOptimizer.estimatePreparedTableBytes(-1,1,1,1,1,1,1,1));
        assertTrue(guarded(()->false).preparedWork().estimatedPreparedTableBytes().isEmpty(),"Uncomputed estimates are unavailable, not measured zero bytes");
    }

    @Test void cancelledOriginalStateGenerationPreservesPartialCurrentTrackWork()throws Exception{
        var f=fixture();var calls=new java.util.concurrent.atomic.AtomicInteger();var optimizer=guarded(()->calls.incrementAndGet()>2);
        assertThrows(java.util.concurrent.CancellationException.class,()->optimizer.prepareOriginalDomain(f.track,f.profiles,1,JunctionContext.empty(),Map.of(),f.tube));
        assertTrue(optimizer.preparedWork().originalStates()>0,"Completed original state rows must remain available after cancellation");
        assertEquals(0,optimizer.preparedWork().retainedUnaryEvaluations());
        assertEquals("CANCELLED",optimizer.preparedWork().outcome());
    }

    @Test void eligibilityRefusalKeepsEveryOrdinaryRivalExactlyOnce()throws Exception{
        var f=PhysicalGeometryFixture.parallelFrame(.95);
        var baseline=new CorridorAwareTracker(guarded(()->false),new CorridorTubeBuilder(CorridorTubeBuilder.MotionPolicy.TARGET_OWNED_APEX))
            .trackDetailed(f.profileSet(),f.sourcePixelPitch(),JunctionContext.empty());
        var tracker=CorridorAwareTracker.withTargetOwnedBridgeCorrespondence(guarded(()->false),d->refusal());
        var actual=tracker.trackDetailed(f.profileSet(),f.sourcePixelPitch(),JunctionContext.empty());
        assertFalse(baseline.candidates().isEmpty());assertEquals(baseline.optimizations(),actual.optimizations());
        assertEquals(baseline.candidates().size(),actual.candidates().size());
        for(int i=0;i<baseline.candidates().size();i++){
            var a=baseline.candidates().get(i);var b=actual.candidates().get(i);
            assertEquals(a.id(),b.id());assertEquals(a.screenPoints(),b.screenPoints());assertEquals(a.evidence(),b.evidence());
        }
        assertEquals(actual.tracks().size(),tracker.correspondencePreparations().size());
        assertEquals(actual.tracks().size(),tracker.correspondenceWork().size());
        for(var receipt:tracker.correspondenceWork().values()){
            assertEquals(1,receipt.preparation().solverStarts());assertTrue(receipt.preparation().originalStates()>0);
            assertEquals("REFUSED_SOURCE_PROTECTION_OR_SPAN",receipt.admission().orElseThrow().status());
        }
    }

    @Test void admittedNamedFactoryKeepsOriginalObservationsAndOnlyRetainedPhysicalGeometry()throws Exception{
        // Analytic ray chart x(j)=j, y(j)=64-9.7+j²/(2*8.5). Its straight-ridge
        // intersections have a small order fold (derivative1-9.7/8.5 at the apex).
        var sampled=PhysicalGeometryFixture.straightRidgeThroughParabola(8.5);
        var evidence=parabolaEvidence();
        var context=CorridorWorkingSchedule.bindContext("analytic-parabola-request","analytic-empty-network",evidence,"native",
            new CorridorWorkingSchedule.SourceProtection(0,100,List.of()),"complete-analytic-no-protected-occurrences",CancellationProbe.NONE);
        var originals=new LinkedHashMap<String,CorridorCenterlineOptimizer.OriginalDomain>();
        var tracker=CorridorAwareTracker.withTargetOwnedBridgeCorrespondence(guarded(()->false),context,d->{
            originals.put(d.track().id(),d);return CorridorWorkingSchedule.prepare(d,context,1,CancellationProbe.NONE);
        });
        var result=tracker.trackDetailed(sampled,1,JunctionContext.empty());
        assertFalse(tracker.preparedOptimizations().isEmpty(),()->"Expected an admitted native alias component: "+tracker.correspondencePreparations());
        for(var e:tracker.preparedOptimizations().entrySet()){
            var p=e.getValue();var d=originals.get(e.getKey());var s=p.schedule();
            assertFalse(p.geometry().screenPoints().isEmpty());assertEquals(sampled.size(),d.profiles().size());
            assertEquals(sampled.size()-s.consumedRows().size(),p.geometry().screenPoints().size());
            assertEquals(0,s.originalRow(0));assertEquals(sampled.size()-1,s.originalRow(s.workingCount()-1));
            assertEquals(List.of(0,1,2,14,15,16),s.originalRows());
            for(int k=0;k<s.workingCount();k++)assertEquals(k,s.workingRow(s.originalRow(k)));
            for(int k=0;k<s.workingCount();k++){
                int row=s.originalRow(k);var source=d.profiles().get(row).source();double offset=p.geometry().offsetsPx().get(k);
                assertTrue(d.states().get(row).contains(offset));assertEquals(source.anchorScreen().x+source.normalScreen().x*offset,p.geometry().screenPoints().get(k).x);
                assertEquals(source.anchorScreen().y+source.normalScreen().y*offset,p.geometry().screenPoints().get(k).y);
            }
            assertEquals(d.approaches(),p.originalEndpointApproaches());
            var candidate=result.candidates().stream().filter(c->c.id().equals(e.getKey())).findFirst().orElseThrow();
            assertEquals(sampled.size(),candidate.evidence().totalProfiles());
            assertEquals(CleanupEvidenceStatus.MISALIGNED_CANDIDATE_ROWS,candidate.cleanupEvidence().status());
            assertEquals(1,tracker.correspondenceWork().get(e.getKey()).preparation().solverStarts());
            var admission=tracker.correspondencePreparations().get(e.getKey());
            assertEquals(admission.domain().edges().size()*s.consumedRows().size(),p.bridgeUnaryEvaluations());
            assertEquals(p.geometry().costs().stream().mapToDouble(CorridorCenterlineOptimizer.CostRow::weightedTotal).sum()+p.bridgeUnaryCost(),p.geometry().totalCost());
            for(var edge:admission.domain().edges())assertEquals(s.consumedRows(),p.bridgeUnaryRows().stream()
                .filter(r->r.leftState()==edge.leftState()&&r.rightState()==edge.rightState()).map(CorridorCenterlineOptimizer.BridgeUnaryRow::originalRow).toList());
            var points=p.geometry().screenPoints().stream().map(q->FRAME.pixelCenterToMetric(q.x,q.y)).toList();
            var ownership=org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CorridorEngineAdapter.support(
                d.track(),p.geometry().offsetsPx(),points,evidence,"native",false,s);
            assertEquals(points.size(),ownership.size());assertTrue(ownership.stream().allMatch(o->o==ObservationOwnership.DIRECT_TWO_SIDED||o==ObservationOwnership.DIRECT_AMBIGUOUS));
            System.out.println("ADMITTED bound fixture originalRows="+s.originalRows()+" consumed="+s.consumedRows()+" admittedEdges="+admission.domain().edges().size()+" bridgeUnaryEvaluations="+p.bridgeUnaryEvaluations());
        }
    }

    private static EvidenceSnapshot parabolaEvidence(){
        int width=128,height=128;double[] values=new double[width*height];boolean[] valid=new boolean[values.length];Arrays.fill(valid,true);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)values[y*width+x]=Math.round(255*Math.exp(-.5*Math.pow((y-64)/.8,2)))/255.0;
        var field=new ScalarEvidenceField(width,height,values,valid,new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"native",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(50,14),new GeographicPoint(49.99,13.99),new GeographicPoint(50.01,14.02));
        return new EvidenceSnapshot("analytic-parabola",frame,FRAME,EvidenceResolution.nativeSource(1,1),MetricRegion.rectangle(0,0,127,127),
            MetricRegion.rectangle(-.5,-.5,127.5,127.5),Map.of("native",field),"analytic-native-image");
    }

    private record Fixture(List<RenderedHeatmapSampler.CrossSectionProfile> sampled,List<CorridorProfile> profiles,
        CorridorTrack track,LongitudinalCorridorTube tube){ }
    @SuppressWarnings("unchecked") static Fixture fixture()throws Exception{
        var f=call(CorridorBridgePreparedTest.class,"fixture",true);
        return new Fixture((List<RenderedHeatmapSampler.CrossSectionProfile>)call(f,"sampled"),
            (List<CorridorProfile>)call(f,"profiles"),(CorridorTrack)call(f,"track"),
            (LongitudinalCorridorTube)call(CorridorBridgePreparedTest.class,"foldingReference",f));
    }
    static CorridorCenterlineOptimizer guarded(CancellationProbe p){return new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(FRAME,p);}
    static Object context()throws Exception{
        try{
            var evidence=(EvidenceSnapshot)call(CorridorBridgePreparedTest.class,"nativeEvidence",false);
            return call(CorridorWorkingSchedule.class,"bindContext","synthetic-request","synthetic-network",evidence,"native",
                new CorridorWorkingSchedule.SourceProtection(0,100,List.of()),"complete-synthetic-occurrence-proof",CancellationProbe.NONE);
        }catch(NoSuchMethodException absentBinding){return null;}
    }
    static CorridorCenterlineOptimizer.OriginalDomain original(CorridorCenterlineOptimizer optimizer,Fixture f,Object context)throws Exception{
        if(context==null)return optimizer.prepareOriginalDomain(f.track,f.profiles,1,JunctionContext.empty(),Map.of(),f.tube);
        return (CorridorCenterlineOptimizer.OriginalDomain)call(optimizer,"prepareOriginalDomain",f.track,f.profiles,1.0,JunctionContext.empty(),Map.of(),f.tube,TraceBudgets.defaults(),context);
    }
    static CorridorWorkingSchedule.Preparation prepare(CorridorCenterlineOptimizer.OriginalDomain d,Object context)throws Exception{
        if(context!=null)return (CorridorWorkingSchedule.Preparation)call(CorridorWorkingSchedule.class,"prepare",d,context,1.0,CancellationProbe.NONE);
        return CorridorWorkingSchedule.prepare(d,FRAME,1,new CorridorWorkingSchedule.SourceProtection(0,100,List.of()),(a,b)->true,CancellationProbe.NONE);
    }
    static CorridorWorkingSchedule.Preparation refusal(){
        try{return (CorridorWorkingSchedule.Preparation)call(CorridorWorkingSchedule.class,"refusal","REFUSED_SOURCE_PROTECTION_OR_SPAN");}
        catch(NoSuchMethodException absentFactory){try{return CorridorWorkingSchedule.Preparation.class.getConstructor(String.class,CorridorWorkingSchedule.class,CorridorWorkingSchedule.BridgeDomain.class,long.class,long.class,long.class,long.class)
            .newInstance("REFUSED_SOURCE_PROTECTION_OR_SPAN",null,null,0L,0L,0L,0L);}catch(Exception e){throw new AssertionError(e);}}
        catch(Exception e){throw new AssertionError(e);}
    }
    static Object call(Object target,String name,Object...args)throws Exception{
        Class<?> type=target instanceof Class<?> c?c:target.getClass();
        for(var m:type.getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){
            boolean match=true;for(int i=0;i<args.length;i++)if(args[i]!=null&&!m.getParameterTypes()[i].isPrimitive()&&!m.getParameterTypes()[i].isInstance(args[i]))match=false;
            if(!match)continue;m.setAccessible(true);try{return m.invoke(target instanceof Class<?>?null:target,args);}catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw e;}
        }throw new NoSuchMethodException(name);
    }
}
