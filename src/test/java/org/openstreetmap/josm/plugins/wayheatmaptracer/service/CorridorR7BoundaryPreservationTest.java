package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorCenterlineOptimizer.OriginalCostPolicy.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorDirectPositionCostPolicyTest.number;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorDirectPositionCostPolicyTest.unary;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorDirectPositionCostPolicyTest.withPolicy;

import java.awt.geom.Point2D;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Focused native composition at unchanged extraction, source-ownership and terminal boundaries. */
class CorridorR7BoundaryPreservationTest {
    @TempDir(cleanup=CleanupMode.NEVER) Path output;

    @Test void nativeCensorRowsKeepExtractionAndFinalCostOwnership() throws Exception {
        double[] complete={0,.08,.42,.90,1,.90,.42,.08,0};
        double[][] clipped={{0,.01,.03,.08,.20,.42,.70,.88,1},
            {.65,.66,.68,.75,.90,1,.90,.70,.20,0,0,0,0}};
        var expected=List.of(CorridorBand.BoundaryCompleteness.CORE_CENSORED,CorridorBand.BoundaryCompleteness.SHOULDER_CENSORED);
        for(int variant=0;variant<2;variant++) {
            var sources=new ArrayList<RenderedHeatmapSampler.CrossSectionProfile>();
            for(int row=0;row<7;row++) {
                var values=row==3?clipped[variant]:complete;
                var original=new CorridorExtractorTest().profile(values,values);
                sources.add(new RenderedHeatmapSampler.CrossSectionProfile(
                    new ProfileSamplingAnchor(new EastNorth(row*2,0),row*2,0,row*2),original.normalScreen(),original.peaks(),true,original.intensitySamples()));
            }
            var extracted=new CorridorExtractor().extract(sources,1.);
            assertEquals(expected.get(variant),extracted.get(3).bands().get(0).boundaryCompleteness(),"Fixture classification before DP");
            var tracker=CorridorAwareTracker.withTargetOwnedMeasuredCoreCostDomainWithoutAuthority(new CorridorCenterlineOptimizer(),
                ignored->CorridorWorkingSchedule.refusal("REFUSED_BOUNDARY_PROFILE_ONLY"));
            var result=tracker.trackDetailed(sources,1.,JunctionContext.empty(),"native",GeometryCleanupConfig.disabled(),1.);
            log("censor-"+variant,result+"\npolicies="+tracker.executedCostPolicies());
            var track=result.tracks().stream().filter(t->!t.parent()&&t.points().containsKey(0)&&t.points().containsKey(6)).findFirst().orElseThrow();
            assertEquals(DIRECT_MEASURED_CORE_V2,tracker.executedCostPolicies().get(track.id()));
            var point=track.points().get(3);
            if(variant==0) assertTrue(point==null||point.support()!=CorridorPointSupport.DIRECT_UNION,"Core censor cannot become direct position");
            else { assertNotNull(point); assertEquals(CorridorPointSupport.DIRECT_UNION,point.support()); assertTrue(point.band().hasMeasuredCenter()); }
            var optimized=result.optimizations().get(track.id()); assertEquals(7,optimized.offsetsPx().size());
            var fixture=new CorridorDirectPositionCostPolicyTest.Fixture(track,result.profiles(),result.tubes().get(track.id()));
            assertCosts(new CorridorCenterlineOptimizer(),fixture,Map.of(),optimized);
            if(variant==0) assertEquals(unary(new CorridorCenterlineOptimizer(),fixture,3,optimized.offsetsPx().get(3),Map.of(),DIRECT_POSITION_V1),
                unary(new CorridorCenterlineOptimizer(),fixture,3,optimized.offsetsPx().get(3),Map.of(),DIRECT_MEASURED_CORE_V2));
        }
    }

    @Test void scaleAndChannelAdversityRetainsZeroMotionInV2Dp() throws Exception {
        double[] centers={0,1,2,3,4,5,6,5,4,3,2.4,2.8,1.8,.8,-.2,-1.2,-2.2};
        var scenario=CorridorBoundedApexMotionTest.scenario(centers,1);
        for(boolean channel:List.of(false,true)) {
            var scales=new LinkedHashMap<String,BandScaleEvidence>();
            var profiles=new ArrayList<>(scenario.profiles());
            if(!channel) for(int row=0;row<17;row++) scales.put(CorridorCenterlineOptimizer.scaleEvidenceKey(row,"b"+row),
                new BandScaleEvidence(.5,Double.NaN,Double.NaN,List.of(0),true,false));
            else {
                profiles.clear();
                for(var p:scenario.profiles()) {
                    double c=p.bands().get(0).centerOffsetPx();
                    var peak=new RenderedHeatmapSampler.CrossSectionPeak(c,.25,0,false,.23,.02,.25,1,1,1,c,c+1,c+2,0,1,.5);
                    var old=p.source();
                    var source=new RenderedHeatmapSampler.CrossSectionProfile(old.samplingAnchor(),old.normalScreen(),List.of(peak),true,List.of());
                    profiles.add(new CorridorProfile(p.index(),source,p.bands(),.25,.02,.23,true));
                }
            }
            var tube=ownedTube(scenario.track(),profiles,scales);
            assertEquals(0.,tube.at(8).motionSupport());
            assertTrue(tube.at(8).motionSupportReason().contains(channel?"center-disagreement":"scale-conflict"));
            var optimizer=new CorridorCenterlineOptimizer();
            var domain=v2(optimizer.prepareOriginalDomain(scenario.track(),profiles,1.,JunctionContext.empty(),scales,tube));
            var result=solve("adverse-"+channel,optimizer,domain);
            assertCosts(optimizer,new CorridorDirectPositionCostPolicyTest.Fixture(scenario.track(),profiles,tube),scales,result);
        }
    }

    @Test void boundProtectedAndOversizedGapDomainsKeepOriginalStates() throws Exception {
        for(boolean oversized:List.of(false,true)) {
            var f=CorridorGapDomainTest.fixture(); var points=new LinkedHashMap<>(f.track().points());
            var slices=new ArrayList<>(f.tube().slices());
            var context=oversized?f.context():CorridorGapDomainTest.context(f.evidence(),List.of(18.));
            if(oversized) {
                points.keySet().removeIf(row->row>5&&row<19);
                var right=points.get(19); points.put(19,new CorridorTrackPoint(19,right.band(),true));
                slices.clear();
                for(var s:f.tube().slices()) slices.add(new CorridorTubeSlice(s.profileIndex(),s.distanceMeters(),-.4,0,0,
                    s.coreMinPx(),s.coreMaxPx(),s.shoulderMinPx(),s.shoulderMaxPx(),s.uncertaintyPx(),s.confidence(),false,false,-.4,-.4,-.4,s.observed()));
            }
            var track=new CorridorTrack(f.track().id(),points,1,oversized?.5:29./30,false,List.of(),"");
            var optimizer=CorridorGapDomainTest.guarded();
            var original=optimizer.prepareOriginalDomain(track,f.profiles(),1.,JunctionContext.empty(),Map.of(),new LongitudinalCorridorTube(slices),TraceBudgets.defaults(),context);
            if(!oversized) assertNotEquals(original.states().get(12).get(0),original.tube().at(12).centerOffsetPx(),"Meaningful rejected extra choice");
            var gap=optimizer.prepareGapDomain(original,7.01);
            assertEquals(original.states(),gap.states()); assertEquals(0,gap.gapPlan().orElseThrow().work().pointChecks());
            var result=solve("gap-negative-"+oversized,optimizer,v2(gap));
            assertEquals(original.states().get(12).get(0),result.offsetsPx().get(12));
            assertCosts(optimizer,new CorridorDirectPositionCostPolicyTest.Fixture(track,f.profiles(),original.tube()),Map.of(),result);
        }
    }

    @Test void boundNotchRejectsUncertifiedV2EdgesAtFirstPairAndLaterRows() throws Exception {
        for(int gap:List.of(1,12)) {
            var f=CorridorGapDomainTest.fixture(gap); var e=f.evidence(); double x=1.5*(10+gap)-.75;
            var polygons=new ArrayList<List<MetricPoint>>();
            for(var b:List.of(new double[]{0,0,x-.45,70},new double[]{x+.45,0,94,70},
                new double[]{x-.45,0,x+.45,28.8},new double[]{x-.45,30,x+.45,70}))
                polygons.addAll(MetricRegion.rectangle(b[0],b[1],b[2],b[3]).polygons());
            var notched=new EvidenceSnapshot(e.snapshotId(),e.coordinateFrame(),e.transform(),e.resolution(),new MetricRegion(polygons),e.evidenceRegion(),e.fields(),e.sourceIdentity());
            var optimizer=CorridorGapDomainTest.guarded();
            var domain=optimizer.prepareGapDomain(CorridorGapDomainTest.original(optimizer,f,CorridorGapDomainTest.context(notched,List.of()),f.track(),JunctionContext.empty(),TraceBudgets.defaults()),7.01);
            var plan=domain.gapPlan().orElseThrow(); assertTrue(plan.work().rejectedEdges()>0);
            var result=solve("source-notch-"+gap,optimizer,v2(domain));
            for(int row=1;row<domain.states().size();row++) assertTrue(plan.allows(row,
                domain.states().get(row-1).indexOf(result.offsetsPx().get(row-1)),domain.states().get(row).indexOf(result.offsetsPx().get(row))));
        }
    }

    @Test void physicalEndpointAnchorRemainsIndependentOfRasterSpacingInV2Dp() throws Exception {
        Integer anchor=null;
        for(double spacing:List.of(5.,120.)) {
            var s=new CorridorEndpointApproachTest().scenario(4.,16,true,spacing);
            var junctions=new JunctionContext(List.of(new EndpointConstraint(0,1L,true,true,0.,0.,6)));
            var tube=ownedTube(s.track(),s.profiles(),Map.of()); var optimizer=new CorridorCenterlineOptimizer();
            var domain=v2(optimizer.prepareOriginalDomain(s.track(),s.profiles(),1.,junctions,Map.of(),tube));
            assertTrue(domain.approaches().supportsConstraint(0));
            int current=domain.approaches().approaches().get(0).interiorAnchorProfileIndex();
            if(anchor==null) anchor=current; else assertEquals(anchor.intValue(),current);
            var result=solve("endpoint-"+spacing,optimizer,domain);
            assertEquals(0.,result.offsetsPx().get(0),1e-9);
            for(int row=1;row<=5;row++) assertTrue(result.offsetsPx().get(row)>=result.offsetsPx().get(row-1)-1e-9);
        }
    }

    @Test void taggedAndSharedCapturedOccurrenceRemainExactInBoundR7Finalization() throws Exception {
        for(boolean shared:List.of(false,true)) {
            var f=protectedFixture(shared);
            var certificate=CorridorSourceProtection.inspect(f.request(),f.evidence(),f.network(),"native",CancellationProbe.NONE);
            assertTrue(certificate.available(),"Source fixture must bind before production");
            assertTrue(certificate.occurrences().get(1).fixedCoordinate());
            var run=new ModernTracePipeline(new CorridorEngineAdapter("native")).runWithUsage(f.request(),f.evidence(),f.network(),f.options(),CancellationProbe.NONE);
            log("protected-final-"+shared,run);
            var receipt=run.corridorReceipt().orElseThrow(); receipt.requireBinding(f.request(),f.evidence(),f.network(),run.result().inference());
            assertEquals(3,receipt.fixedSourceCoordinates().orElseThrow()); assertEffectivePolicies(receipt);
            assertFalse(run.result().routes().isEmpty());
            var way=(DetachedWay)f.network().primitives().get(f.request().selectedWayKey());
            for(var route:run.result().routes()) for(int occurrence:List.of(0,1,2)) {
                var id=new FinalRoutePointId.ExistingWayNodeOccurrence(way.key(),way.nodeKeys().get(occurrence),occurrence);
                var node=(DetachedNode)f.network().primitives().get(id.nodeKey());
                assertEquals(f.evidence().coordinateFrame().toMetric(node.coordinate()),route.assignments().get(id));
                assertEquals(ObservationOwnership.FIXED_TOPOLOGY_ONLY,route.sourceOwnership().get(id));
                assertEquals(route.assignments().get(id),route.hypothesis().points().get(route.pointIds().indexOf(id)));
            }
        }
    }

    @Test void partialV2DpAndGapCancellationRetainFactualWorkWithoutFallback() throws Exception {
        var f=withPolicy(CorridorPolicyFixture.straight(true,false,Set.of()),CorridorTraceInput.ExecutionPolicy.R7_OWNED_MEASURED_CORE_COST_V1);
        var checkpoints=new AtomicInteger();
        CancellationProbe probe=()->StackWalker.getInstance().walk(frames->{
            var methods=frames.map(StackWalker.StackFrame::getMethodName).toList();
            return methods.contains("solveExactInternal")&&!methods.contains("metricEdgeCache")&&checkpoints.incrementAndGet()==2;
        });
        var run=new CorridorEngineAdapter("native").traceWithUsage(f.request(),f.evidence(),f.network(),probe);
        log("partial-dp-cancel",run);
        var receipt=run.corridorReceipt().orElseThrow();
        assertEquals(TraceHypothesisSet.Status.CANCELLED,run.result().status()); assertTrue(run.result().hypotheses().isEmpty());
        assertEquals(CorridorExecutionReceipt.Availability.COMPLETE,receipt.samplingAvailability());
        assertEquals(1,receipt.totals().solverStarts()); assertTrue(receipt.totals().attemptedTransitions()>0);
        assertTrue(receipt.totals().testedTriples()>0); assertTrue(receipt.totals().scoredTransitions()>0);
        assertEquals(receipt.totals().unaryEvaluations(),run.result().evaluatedStates());
        assertEquals(receipt.totals().attemptedTransitions(),run.usage().transitions()); assertEffectivePolicies(receipt);
        var gap=CorridorGapDomainTest.fixture(); var holder=new CorridorCenterlineOptimizer[1];
        holder[0]=new CorridorCenterlineOptimizer().withLocalHardBacktrackGuard(CorridorGapDomainTest.FRAME,
            ()->holder[0].preparedWork().gapWork().map(w->w.pointChecks()>0).orElse(false));
        var original=v2(CorridorGapDomainTest.original(holder[0],gap,gap.context(),gap.track(),JunctionContext.empty(),TraceBudgets.defaults()));
        assertThrows(CancellationException.class,()->holder[0].prepareGapDomain(original,7.01));
        log("partial-gap-cancel",holder[0].preparedWork());
        assertEquals(1,holder[0].preparedWork().gapWork().orElseThrow().pointChecks()); assertEquals(0,holder[0].preparedWork().solverStarts());
        CorridorGapDomainTest.original(holder[0],gap,gap.context(),gap.track(),JunctionContext.empty(),TraceBudgets.defaults());
        assertTrue(holder[0].preparedWork().gapWork().isEmpty()); assertEquals(0,holder[0].preparedWork().solverStarts());
    }

    @Test void requestPairTransitionRawAndDistinctCapsRemainTerminalUnderR7() throws Exception {
        var f=withPolicy(CorridorPolicyFixture.straight(true,true,Set.of()),CorridorTraceInput.ExecutionPolicy.R7_OWNED_MEASURED_CORE_COST_V1);
        var budgets=List.of(new TraceBudgets(96,1,128000000,32,8),new TraceBudgets(96,8000000,1,32,8),
            new TraceBudgets(96,8000000,128000000,1,1),new TraceBudgets(96,8000000,128000000,32,1));
        for(int variant=0;variant<4;variant++) {
            var request=request(f.request(),f.network(),budgets.get(variant));
            assertTrue(CorridorSourceProtection.inspect(request,f.evidence(),f.network(),"native",CancellationProbe.NONE).available());
            var run=new CorridorEngineAdapter("native").traceWithUsage(request,f.evidence(),f.network(),CancellationProbe.NONE);
            log("request-cap-"+variant,run);
            var receipt=run.corridorReceipt().orElseThrow();
            assertEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT,run.result().status());
            assertEquals(CorridorExecutionReceipt.Availability.COMPLETE,receipt.samplingAvailability());
            assertTrue(receipt.discoveredTracks().orElseThrow()>1);
            if(variant<3) {
                assertEquals(0,receipt.totals().solverStarts()); assertTrue(run.result().hypotheses().isEmpty());
                assertEquals(0,run.usage().transitions()); assertTrue(receipt.originalCostPolicies().isEmpty());
                assertTrue(receipt.originalCostPolicyMetadataBytes().isEmpty());
            } else {
                assertEquals(1,run.result().hypotheses().size()); assertTrue(run.result().alternativesTruncated());
                assertEquals(receipt.discoveredTracks().orElseThrow(),receipt.visitedTracks());
                assertEquals(receipt.visitedTracks(),receipt.totals().solverStarts()); assertEffectivePolicies(receipt);
                assertEquals(receipt.totals().attemptedTransitions(),run.usage().transitions());
            }
        }
    }

    private static CorridorPolicyFixture.Fixture protectedFixture(boolean shared) {
        var f=withPolicy(CorridorPolicyFixture.straight(true,false,Set.of()),CorridorTraceInput.ExecutionPolicy.R7_OWNED_MEASURED_CORE_COST_V1);
        var primitives=new LinkedHashMap<>(f.network().primitives());
        var selected=(DetachedWay)primitives.get(f.request().selectedWayKey()); var middle=selected.nodeKeys().get(1);
        var node=(DetachedNode)primitives.get(middle);
        if(!shared) primitives.put(middle,new DetachedNode(middle,node.coordinate(),Map.of("name","synthetic-protected"),false,false));
        else {
            var branchNode=PrimitiveKey.existing(PrimitiveKey.Type.NODE,4); var branchWay=PrimitiveKey.existing(PrimitiveKey.Type.WAY,101);
            primitives.put(branchNode,new DetachedNode(branchNode,f.evidence().coordinateFrame().toGeographic(new MetricPoint(75,40)),Map.of(),false,false));
            primitives.put(branchWay,new DetachedWay(branchWay,List.of(middle,branchNode),Map.of("highway","path"),false,false));
        }
        var c=f.network().closure(); var editable=new LinkedHashSet<>(c.editableExistingKeys()); editable.remove(middle);
        var movable=new LinkedHashSet<>(c.movableExistingNodeKeys()); movable.remove(middle);
        var protect=new LinkedHashSet<>(c.protectedExistingNodeKeys()); protect.add(middle);
        var closure=new ClosureDescriptor(c.scope(),c.queryVersion()+"-boundary-"+shared,primitives.keySet(),editable,movable,protect,
            c.removableExistingNodeKeys(),c.editableWayOccurrences(),c.externalPorts(),c.collisionEnvelope(),c.editRegion(),
            c.mayCreateNodes(),c.wayReferrersComplete(),c.relationReferrersComplete(),c.nearbyGeometryComplete());
        var network=new NetworkSnapshot("boundary-network-"+shared,SnapshotRole.CAPTURED_BEFORE,"policy-dataset",1,closure,primitives,V022SnapshotFixtures.closedWorldReferrerWatches(primitives));
        return new CorridorPolicyFixture.Fixture(request(f.request(),network,f.request().budgets()),f.evidence(),network,f.options());
    }
    private static TraceRequest request(TraceRequest r,NetworkSnapshot network,TraceBudgets budgets) {
        return new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),budgets,
            r.evidenceSnapshotId(),r.evidenceContentHash(),network.snapshotId(),network.canonicalHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),
            r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput());
    }
    private static LongitudinalCorridorTube ownedTube(CorridorTrack track,List<CorridorProfile> profiles,Map<String,BandScaleEvidence> scales) {
        return new CorridorTubeBuilder(CorridorTubeBuilder.MotionPolicy.TARGET_OWNED_APEX).build(track,profiles,1.,scales);
    }
    private static CorridorCenterlineOptimizer.OriginalDomain v2(CorridorCenterlineOptimizer.OriginalDomain domain) {
        return CorridorCenterlineOptimizer.withNonAdmittedCostPolicy(domain,CorridorWorkingSchedule.refusal("REFUSED_BOUNDARY_ELIGIBILITY"),DIRECT_MEASURED_CORE_V2);
    }
    private CorridorCenterlineOptimizer.OptimizationResult solve(String name,CorridorCenterlineOptimizer optimizer,CorridorCenterlineOptimizer.OriginalDomain domain) throws Exception {
        assertEquals(DIRECT_MEASURED_CORE_V2,domain.costPolicy());
        try {
            var result=optimizer.optimizeOriginalDomain(domain); log(name,result); assertFalse(result.offsetsPx().isEmpty()); return result;
        } finally { log(name+"-work",optimizer.preparedWork()+"\nhard="+optimizer.hardGeometryWork()+"\npolicy="+domain.costPolicy()); }
    }
    private static void assertCosts(CorridorCenterlineOptimizer optimizer,CorridorDirectPositionCostPolicyTest.Fixture fixture,
            Map<String,BandScaleEvidence> scales,CorridorCenterlineOptimizer.OptimizationResult result) throws Exception {
        for(var row:result.costs()) assertEquals(number(unary(optimizer,fixture,row.profileIndex(),row.chosenOffsetPx(),scales,DIRECT_MEASURED_CORE_V2),"total"),row.dataCost(),1e-12);
        assertEquals(result.totalCost(),result.costs().stream().mapToDouble(CorridorCenterlineOptimizer.CostRow::weightedTotal).sum(),1e-9);
    }
    private static void assertEffectivePolicies(CorridorExecutionReceipt receipt) {
        var policies=receipt.originalCostPolicies().orElseThrow(); assertTrue(policies.containsValue(DIRECT_MEASURED_CORE_V2));
        assertEquals(128L*policies.size(),receipt.originalCostPolicyMetadataBytes().orElseThrow());
        for(var work:receipt.tracks().values()) if(policies.containsKey(work.trackId()))
            assertEquals(work.admissionStatus().equals("ADMITTED")?HISTORICAL:DIRECT_MEASURED_CORE_V2,policies.get(work.trackId()));
    }
    private void log(String name,Object value) throws Exception {
        System.out.println("R7 boundary retained evidence: "+output.toAbsolutePath());
        Files.writeString(output.resolve(name+".txt"),value+"\n",StandardOpenOption.CREATE_NEW);
    }
}
