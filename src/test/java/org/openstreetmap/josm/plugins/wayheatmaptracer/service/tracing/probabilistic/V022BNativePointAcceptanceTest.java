package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
class V022BNativePointAcceptanceTest {
 static MetricPoint p(double x,double y){return new MetricPoint(x,y);}
 static final List<MetricPoint> SOURCE=List.of(p(8,37),p(32,37));
 static final List<MetricPoint> BEND=List.of(p(8,37),p(17,37),p(20,40),p(23,37),p(32,37));
 static Map<String,Object> evaluate(String id,List<MetricPoint> original,List<MetricPoint> nativeSource,List<MetricPoint> path){
  var l=AttemptMemoryLedger.production();try(var o=l.rootOwner()){return BNativePointAcceptance.evaluate(id,null,original,nativeSource,path,CancellationProbe.NONE,o);}finally{assertEquals(0,l.currentBytes());}
 }
 @Test void actualBendTruthIsNotTheStraightSelectedGuide(){
  assertEquals(true,evaluate("connected-sharp-apex",SOURCE,SOURCE,BEND).get("passed"));
  assertEquals(false,evaluate("connected-sharp-apex",SOURCE,SOURCE,SOURCE).get("passed"));
  var off=new ArrayList<>(BEND);off.set(2,p(20,42));assertEquals(false,evaluate("connected-sharp-apex",SOURCE,SOURCE,off).get("passed"));
 }
 @Test void endpointOwnershipAcceptsOnlyNativeAnchorAndDisclosesOriginalCompatibility(){
  var n=List.of(p(Math.nextDown(8.0),37),p(32,37));var path=new ArrayList<>(BEND);path.set(0,n.get(0));
  var r=evaluate("connected-sharp-apex",SOURCE,n,path);assertEquals(true,r.get("passed"));assertEquals(false,r.get("originalReferenceExactEndpointCompatibility"));
  path.set(0,p(7,37));assertEquals(false,evaluate("connected-sharp-apex",SOURCE,n,path).get("passed"));
 }
 @Test void stateDomainAndCensoredGapCapabilitiesCannotBecomeGeometryPass(){
  for(String id:List.of("positive-twenty-five-representation","negative-twenty-five-representation","censored-flank")){
   var r=evaluate(id,SOURCE,SOURCE,SOURCE);assertEquals(false,r.get("available"));assertNull(r.get("passed"));
  }
 }
 @Test void thinMergeRetainsOriginalBranchContinuityAndOneMetreReference(){
  String id="thin-strands-merge-separate";var f=BThinStrandFixtures.byId(id);var path=new ArrayList<MetricPoint>();
  for(int x=-8;x<=52;x++)path.add(p(x,f.strands().get(x<10?0:2).centerlineY()[x+20]));
  var source=List.of(p(-8,37),p(52,37));
  assertEquals(false,evaluate(id,source,source,path).get("passed"));
  var wrong=new ArrayList<>(SOURCE);wrong.set(1,p(32,39));assertEquals(false,evaluate("connected-straight",SOURCE,SOURCE,wrong).get("passed"));
 }
 @Test void thinNativeAnchorsAreSeparateAndUnchangedDomainRejectionsStayUnavailable(){
  String id="thin-local-overlap-noise";var original=List.of(p(-8,37),p(52,37));
  var inside=List.of(p(Math.nextUp(-8.0),37),p(Math.nextDown(52.0),37));
  var r=evaluate(id,original,inside,inside);assertEquals(true,r.get("passed"));assertEquals(false,r.get("originalReferenceExactEndpointCompatibility"));
  var outside=List.of(p(Math.nextDown(-8.0),37),p(52,37));var unavailable=evaluate(id,original,outside,outside);
  assertEquals(false,unavailable.get("available"));assertNull(unavailable.get("passed"));
  assertEquals("UNAVAILABLE_NATIVE_ENDPOINT_REFERENCE_ADAPTER",unavailable.get("availability"));
 }
 @Test void oracleOwnersCloseBeforeReceiptPublication(){
  var ledger=AttemptMemoryLedger.production();try(var o=ledger.rootOwner();var report=o.reserve(BNativeProductionComparison.REPORT_BYTES)){
   var source=List.of(p(-8,37),p(52,37));long before=ledger.currentBytes();
   var r=BNativePointAcceptance.evaluate("thin-local-overlap-noise",null,source,source,source,CancellationProbe.NONE,o);
   assertEquals(true,r.get("passed"));assertEquals(before,ledger.currentBytes(),"oracle-owned results must be copied under report funding and closed before ownership.json");
  }finally{assertEquals(0,ledger.currentBytes());}
 }
 @Test void supportedJunctionAndReverseSourceUseOriginalWholeSegmentTruth(){
  var junction=List.of(p(8,37),p(20,37),p(20,42),p(20,37),p(32,37));
  assertEquals(true,evaluate("genuine-junction",SOURCE,SOURCE,junction).get("passed"));
  var reverse=List.of(p(32,37),p(8,37));assertEquals(true,evaluate("reverse-original-mesh",reverse,reverse,reverse).get("passed"));
  var escape=List.of(p(32,37),p(20,39),p(8,37));assertEquals(false,evaluate("reverse-original-mesh",reverse,reverse,escape).get("passed"));
 }
 @Test void actualUnknownSupportRemainsNegativeEligibilityRatherThanQualifiedGeometry(){
  var ledger=AttemptMemoryLedger.production();try(var o=ledger.rootOwner();var cap=o.reserve(32L*1024*1024);var metadata=o.reserve(BNativeProductionComparison.REPORT_BYTES)){
   var evidence=V022BGapMatchedControlsTest.captureNativeDefaultSource("invalid-unknown-gap",new HashMap<>(),CancellationProbe.NONE);
   var r=BNativePointAcceptance.evaluate("invalid-unknown-gap",evidence,SOURCE,SOURCE,SOURCE,CancellationProbe.NONE,o);
   assertEquals(false,r.get("observedSupport"));assertEquals(true,r.get("negativeEligibilityPredicate"));
   assertEquals("NOT_AUTHORIZED_BY_NEGATIVE_CONTROL",r.get("positiveQualification"));assertEquals(false,r.get("modelQualification"));
  }finally{assertEquals(0,ledger.currentBytes());}
 }
}
