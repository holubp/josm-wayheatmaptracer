package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
class V022BNativeProductionComparisonTest {
    static TraceEngineRun eightRoutes(){var routes=new ArrayList<TraceHypothesis>();
        for(int i=0;i<8;i++)routes.add(new TraceHypothesis("native-"+i,"branch-"+i,List.of(new MetricPoint(0,0),new MetricPoint(2,i)),List.of(ObservationOwnership.FIXED_TOPOLOGY_ONLY,ObservationOwnership.DIRECT_TWO_SIDED),i,.1,Map.of("nativeMetric",(double)i)));
        return new TraceEngineRun(new TraceHypothesisSet(TrackerMode.PROBABILISTIC,routes,TraceHypothesisSet.Status.COMPLETE,true,123,456,"native-result"),new TraceWorkUsage(789,456,32,8),4321);}
    @Test void nativeDefaultsAndPoliciesNeverMasqueradeAsIdentifiedB0OrHistoricalRc12(){var p=BNativeProductionComparison.policy();
        assertEquals("CURRENT_NATIVE_PRODUCTION_B_NOT_IDENTIFIED_B0_OR_HISTORICAL_RC12",p.get("identity"));
        assertEquals(List.of(96,8_000_000,128_000_000,32,8),p.get("budgets"));
        assertEquals(ProbabilisticTraceEngine.reliabilityPolicyVersionFor(TrackerMode.PROBABILISTIC),p.get("reliabilityPolicy"));
        assertEquals(ProbabilisticTraceEngine.stateSamplingPolicyVersionFor(TrackerMode.PROBABILISTIC),p.get("stateSamplingPolicy"));}
    @Test void everyNativeAlternativeAndActualWorkSurviveWithoutInventedGraphIdentity(){var r=BNativeProductionComparison.result(eightRoutes());
        assertEquals(8,((List<?>)r.get("routes")).size());assertEquals(32,r.get("rawAlternatives"));assertEquals(8,r.get("distinctAlternatives"));assertEquals(true,r.get("alternativesTruncated"));
        assertEquals(456L,r.get("transitions"));assertEquals("UNAVAILABLE_NOT_EXPOSED_BY_NATIVE_API",r.get("graphHash"));
        var routes=(List<?>)r.get("routes");for(int i=0;i<8;i++){var route=(Map<?,?>)routes.get(i);assertEquals("native-"+i,route.get("id"));assertEquals((double)i,route.get("objective"));assertEquals(2,((List<?>)route.get("points")).size());}}
    @Test void outerDeadlineAndExplicitCancellationAreDeterministicWithoutChangingNativeBudgets(){var time=new AtomicLong(7);var p=BNativeProductionComparison.deadline(time::get,CancellationProbe.NONE);
        assertFalse(p.cancelled());time.set(300_000_000_006L);assertFalse(p.cancelled());time.incrementAndGet();assertThrows(CancellationException.class,p::checkpoint);
        assertTrue(BNativeProductionComparison.deadline(()->0,()->true).cancelled());assertEquals(128_000_000,TraceBudgets.defaults().maximumTransitions());}
    @Test void aggregatePublicationRefusesOversizedLeafBeforePublishingAnyAlternative(@TempDir Path directory)throws Exception {
        var leaves=new LinkedHashMap<String,Object>();leaves.put("result.json",Map.of("status","complete"));leaves.put("route-0.json",Map.of("payload","x".repeat(65536)));
        assertThrows(IllegalArgumentException.class,()->BNativeProductionComparison.publish(directory,leaves));try(var files=Files.list(directory)){assertEquals(0,files.count());}}
    @Test void safeCauseNeverExportsArbitraryExceptionText(){assertEquals("ILLEGAL_ARGUMENT",BNativeProductionComparison.safeCause(new IllegalArgumentException("secret-arbitrary-text")));}
    @Test void oneUlpEndpointDifferenceIsDisclosedWithoutTurningItIntoShapeFailure(){
        var original=List.of(new MetricPoint(1,0),new MetricPoint(3,0));var nativeSource=List.of(new MetricPoint(Math.nextUp(1.0),0),new MetricPoint(3,0));
        var ledger=new AttemptMemoryLedger(BNativeProductionComparison.BYTES);
        try(var owner=ledger.rootOwner()){
            var receipt=BNativeProductionComparison.nativeGeometry(original,nativeSource,nativeSource,CancellationProbe.NONE,owner);
            assertEquals(true,receipt.get("passed"));assertEquals(false,receipt.get("originalReferenceExactEndpointCompatibility"));
            assertEquals("NATIVE_SELECTED_SOURCE_AFTER_GEOGRAPHIC_ROUNDTRIP",receipt.get("referenceIdentity"));
            assertEquals(Math.ulp(1.0),receipt.get("maxPhysicalSourceRoundTripErrorMeters"));
            var deviating=List.of(nativeSource.get(0),new MetricPoint(2,2),nativeSource.get(1));
            assertEquals(false,BNativeProductionComparison.nativeGeometry(original,nativeSource,deviating,CancellationProbe.NONE,owner).get("passed"));
        }finally{assertEquals(0,ledger.currentBytes());}
    }
    private static EvidenceSnapshot toy(){
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-1,-1),new GeographicPoint(1,1));
        double[] values=new double[101*81];boolean[] valid=new boolean[values.length];Arrays.fill(values,.02);Arrays.fill(valid,true);
        for(int x=0;x<101;x++){values[40*101+x]=.8;values[39*101+x]=.2;values[41*101+x]=.2;}
        var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"tiny-native-contract",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
        return new EvidenceSnapshot("tiny-native-contract",frame,RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),EvidenceResolution.nativeSource(1,1),
                MetricRegion.rectangle(0,0,100,80),MetricRegion.rectangle(-.5,-.5,100.5,80.5),Map.of("scalar",new ScalarEvidenceField(101,81,values,valid,lineage)),"tiny-native-contract");
    }
    @Test void requestUsesActualDetachedGeographicCoordinatesAndUnchangedNativePolicies(){var ledger=new AttemptMemoryLedger(BNativeProductionComparison.BYTES);
        try(var owner=ledger.rootOwner();var metadata=owner.reserve(BNativeProductionComparison.REPORT_BYTES)){
            var evidence=toy();var original=List.of(new MetricPoint(35.1,40.2),new MetricPoint(38.1,40.2));var b=BNativeProductionComparison.bind(evidence,original);
            assertEquals(TrackerMode.PROBABILISTIC,b.request().engine());assertEquals(TraceBudgets.defaults(),b.request().budgets());assertEquals(25,b.request().permissions().ordinaryRadiusMeters());
            assertEquals(JunctionPolicy.FIXED,b.request().permissions().junctionPolicy());assertEquals(evidence.canonicalHash(),b.request().evidenceContentHash());
            assertEquals(b.network().canonicalHash(),b.request().networkContentHash());
            var way=(DetachedWay)b.network().primitives().get(b.request().selectedWayKey());
            for(int i=0;i<original.size();i++){var node=(DetachedNode)b.network().primitives().get(way.nodeKeys().get(i));assertEquals(evidence.coordinateFrame().toMetric(node.coordinate()),b.actual().get(i));}
            assertEquals(new ProbabilisticProfileFactory().profileChainage(b.actual(),1.5),b.request().profileChainage());
            assertEquals(original,b.original());assertEquals(2,((List<?>)b.binding().get("roundTrip")).size());
            double maximum=0;for(int i=0;i<original.size();i++)maximum=Math.max(maximum,original.get(i).distanceTo(b.actual().get(i)));
            assertEquals(maximum,b.binding().get("maxPhysicalSourceRoundTripErrorMeters"),"source roundtrip bound must survive even when native solver returns no route");
        }finally{assertEquals(0,ledger.currentBytes());}}
    @Test void fixedSourceSeamsRefuseOtherCohortsAndCancelBeforeAnyRasterCapture()throws Exception {
        assertEquals(5,BNativeProductionComparison.IDS.size());assertEquals(BNativeProductionComparison.IDS,BNativeProductionComparison.contract().get("cases"));
        for(String id:BNativeProductionComparison.IDS)assertEquals(BThinSwitchbackFixtures.source(),V022BGapMatchedControlsTest.nativeComparisonSource(id));
        assertThrows(IllegalArgumentException.class,()->V022BGapMatchedControlsTest.nativeComparisonSource("connected-straight"));
        assertThrows(IllegalArgumentException.class,()->V022BGapMatchedControlsTest.captureNativeComparisonSource("private",new HashMap<>(),CancellationProbe.NONE));
        var binding=new HashMap<String,Object>();assertThrows(CancellationException.class,()->V022BGapMatchedControlsTest.captureNativeComparisonSource(BNativeProductionComparison.IDS.get(0),binding,()->true));assertTrue(binding.isEmpty());
    }
    @Test void actualTinyProductionEntryPreservesDefaultResultAndClosesSuccessCancelAndRefusalOwners(){var ledger=new AttemptMemoryLedger(BNativeProductionComparison.BYTES);
        try(var owner=ledger.rootOwner();var metadata=owner.reserve(BNativeProductionComparison.REPORT_BYTES)){
            var evidence=toy();var b=BNativeProductionComparison.bind(evidence,List.of(new MetricPoint(35,40),new MetricPoint(38,40)));
            var nativeEngine=new ProbabilisticTraceEngine("scalar");var result=nativeEngine.traceWithUsage(b.request(),evidence,b.network(),CancellationProbe.NONE,owner);
            try{var run=result.value();assertTrue(run.result().status()==TraceHypothesisSet.Status.COMPLETE||run.result().status()==TraceHypothesisSet.Status.AMBIGUOUS);
                assertFalse(run.result().hypotheses().isEmpty());
                var encoded=BNativeProductionComparison.result(run);assertEquals(run.result().hypotheses().size(),((List<?>)encoded.get("routes")).size());
                for(Object route:(List<?>)encoded.get("routes"))assertTrue(BIdentificationReceipt.canonical(route).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=65536);
                assertTrue(run.usage().rawAlternatives()<=32);assertTrue(run.usage().distinctAlternatives()<=8);
                assertTrue(run.result().workAccounting().isPresent());assertEquals(run.usage().transitions(),run.result().workAccounting().orElseThrow().computedTransitions());
                for(var h:run.result().hypotheses()){assertEquals(b.actual().get(0),h.points().get(0));assertEquals(b.actual().get(1),h.points().get(h.points().size()-1));}
            }finally{result.owner().close();}
            assertEquals(BNativeProductionComparison.REPORT_BYTES,ledger.currentBytes());
            var cancelled=nativeEngine.traceWithUsage(b.request(),evidence,b.network(),()->true,owner);
            try{assertEquals(TraceHypothesisSet.Status.CANCELLED,cancelled.value().result().status());}finally{cancelled.owner().close();}
            assertEquals(BNativeProductionComparison.REPORT_BYTES,ledger.currentBytes());
            try(var fill=owner.reserve(BNativeProductionComparison.BYTES-ledger.currentBytes()-1)){
                try{var refused=nativeEngine.traceWithUsage(b.request(),evidence,b.network(),CancellationProbe.NONE,owner);
                    try{assertEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT,refused.value().result().status());}finally{refused.owner().close();}
                }catch(TraceMemoryLimitException expected){assertEquals(BNativeProductionComparison.BYTES,expected.limitBytes());assertEquals("RESOURCE_LIMIT",BNativeProductionComparison.safeCause(expected));assertTrue(expected.currentBytes()<=expected.peakBytes());}
                catch(AttemptMemoryLedger.ResourceLimitException expected){assertEquals(BNativeProductionComparison.BYTES,expected.limitBytes());}
            }
            assertEquals(BNativeProductionComparison.REPORT_BYTES,ledger.currentBytes());
        }finally{assertEquals(0,ledger.currentBytes());}}
    @Test void cancelledOuterAttemptPublishesTruthfulZeroWorkAndCannotRetry(@TempDir Path directory)throws Exception {
        var r=BNativeProductionComparison.execute(BNativeProductionComparison.IDS.get(0),directory,()->0,()->true);
        assertEquals(0,r.get("nativeInvocations"));assertEquals(0,r.get("nativeReturns"));assertEquals("CANCELLED",r.get("safeCause"));assertEquals(false,r.get("routeDataAvailable"));
        var ownership=BIdentificationReceipt.read(directory.resolve("ownership.json"));assertEquals(0L,((Number)ownership.get("remainingNonReportBytes")).longValue());
        assertThrows(IllegalStateException.class,()->BNativeProductionComparison.execute(BNativeProductionComparison.IDS.get(0),directory,()->0,CancellationProbe.NONE));
    }
    /** Five fixed sources are explicitly discovered only in the dedicated opt-in job. */
    @Tag("dedicated-experiment")
    @Test void fixedNativeProductionComparison()throws Exception {
        assertEquals("true",System.getenv("V022_RUN_NATIVE_B_COMPARISON"),"Explicit native comparison opt-in is required before acquisition");
        assertEquals("true",System.getenv("GITHUB_ACTIONS"));assertTrue(System.getenv("GITHUB_SHA")!=null&&System.getenv("GITHUB_SHA").matches("[0-9a-f]{40}"));
        String id=System.getenv("V022_NATIVE_B_CASE");BNativeProductionComparison.requireCase(id);
        var r=BNativeProductionComparison.execute(id,Path.of("build/b-native-production-comparison",id),System::nanoTime,CancellationProbe.NONE);
        assertEquals(1,r.get("nativeInvocations"),"source/request failure is not a native comparison");
        assertTrue(((Number)r.get("nativeReturns")).intValue()==1||List.of("RESOURCE_LIMIT","CANCELLED","NUMERIC_FAILURE").contains(r.get("safeCause")));
        assertEquals(false,r.get("modelQualification"));
    }
    @Test void resourcePartialAlternativesStayExportableButNeverBecomeCompletedQuality(){
        var prior=eightRoutes();var partial=new TraceHypothesisSet(TrackerMode.PROBABILISTIC,prior.result().hypotheses(),TraceHypothesisSet.Status.RESOURCE_LIMIT,true,123,456,"resource-limited");
        var r=BNativeProductionComparison.result(new TraceEngineRun(partial,prior.usage(),4321));assertEquals(8,((List<?>)r.get("routes")).size());
        assertEquals(false,r.get("qualityComparisonAvailable"));assertEquals(false,r.get("finalPreviewOrApplyQualification"));assertEquals("ENGINE_BOUNDARY_ONLY",r.get("comparisonBoundary"));
        for(Object item:(List<?>)r.get("routes"))assertEquals("PARTIAL_NATIVE_ALTERNATIVE_NOT_QUALIFIED",((Map<?,?>)item).get("quality"));
        var cancelled=new TraceHypothesisSet(TrackerMode.PROBABILISTIC,List.of(),TraceHypothesisSet.Status.CANCELLED,false,0,0,"cancelled");
        assertEquals(false,BNativeProductionComparison.result(new TraceEngineRun(cancelled,TraceWorkUsage.none())).get("qualityComparisonAvailable"));
    }
    @Test void terminalExportOverflowPreservesNativeCountsButCannotClaimAvailableQuality(@TempDir Path directory)throws Exception {
        var r=BNativeProductionComparison.result(eightRoutes());r.remove("routes");r.put("routeFiles",List.of("route-0.json"));r.put("routeDataAvailable",true);r.put("attemptComplete",true);
        var leaves=new LinkedHashMap<String,Object>();leaves.put("result.json",r);leaves.put("ownership.json",Map.of("remainingNonReportBytes",0));leaves.put("route-0.json",Map.of("oversized","x".repeat(65536)));
        assertThrows(BNativeProductionComparison.ExportLimit.class,()->BNativeProductionComparison.publishOutcome(directory,r,leaves));
        var retained=BIdentificationReceipt.read(directory.resolve("result.json"));assertEquals(false,retained.get("qualityComparisonAvailable"));assertEquals(false,retained.get("routeDataAvailable"));
        assertEquals(false,retained.get("attemptComplete"));assertEquals(8,((Number)retained.get("returnedNativeAlternativeCount")).intValue());
        assertEquals("COMPLETE",retained.get("nativeStatus"));assertEquals(List.of(),retained.get("routeFiles"));assertFalse(Files.exists(directory.resolve("route-0.json")));
    }
}
