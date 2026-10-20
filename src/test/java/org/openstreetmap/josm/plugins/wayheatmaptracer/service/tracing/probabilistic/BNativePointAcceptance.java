package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Truth-side only. Native inference never receives this dispatcher or fixture descriptors. */
final class BNativePointAcceptance {
 static Map<String,Object> evaluate(String id,EvidenceSnapshot evidence,List<MetricPoint> original,List<MetricPoint> nativeSource,
   List<MetricPoint> path,CancellationProbe probe,AttemptMemoryLedger.Owner owner){
  BNativeProductionDefaultComparison.requireCase(id);Objects.requireNonNull(probe);probe.checkpoint();
  if(original==null||nativeSource==null||original.size()!=nativeSource.size()||original.size()<2||original.size()>12
    ||path==null||path.size()<2||path.size()>81)throw new IllegalArgumentException("Fixed native truth inventory exceeded");
  try(var scratch=owner.reserve(32768);var oracleOwner=owner.child("native-point-acceptance")){
   var r=new LinkedHashMap<String,Object>();boolean anchors=path.get(0).equals(nativeSource.get(0))&&path.get(path.size()-1).equals(nativeSource.get(nativeSource.size()-1));
   boolean compatible=original.get(0).equals(nativeSource.get(0))&&original.get(original.size()-1).equals(nativeSource.get(nativeSource.size()-1));
   r.put("referenceIdentity","ORIGINAL_POINT_TRUTH_WITH_NATIVE_ENDPOINT_CORRESPONDENCE_ONLY");
   r.put("originalReferenceExactEndpointCompatibility",compatible);r.put("nativeExactAnchors",anchors);
   r.put("returnedCoordinatesNormalized",false);r.put("modelQualification",false);
   r.put("available",true);r.put("passed",false);
   if(id.endsWith("twenty-five-representation"))return unavailable(r,"UNAVAILABLE_NATIVE_GRAPH_NOT_EXPOSED");
   if(id.equals("censored-flank")){r.put("gapCapability","NOT_APPLICABLE_NO_G_IN_NATIVE_POLICY");return unavailable(r,"UNAVAILABLE_CENSORED_POSITION");}
   if(BNativeProductionComparison.IDS.contains(id)){
    r.putAll(BNativeProductionComparison.nativeGeometry(original,nativeSource,path,probe,oracleOwner));
    r.put("available",true);return r;
   }
   if(BThinStrandFixtures.all().stream().anyMatch(f->f.id().equals(id))){
    var result=BThinStrandAcceptance.evaluate(BThinStrandFixtures.byId(id),path,1,probe,oracleOwner);
    var checks=new ArrayList<Map<String,Object>>();boolean remainder=true;boolean unknown=false;
    for(var check:result.checks()){
     probe.checkpoint();checks.add(Map.of("reason",check.reason().name(),"verdict",check.verdict().name(),"count",check.count()));
     if(check.reason()!=BThinStrandAcceptance.Reason.EXACT_ANCHORS){remainder&=check.verdict()==BThinStrandAcceptance.Verdict.PASS;unknown|=check.verdict()==BThinStrandAcceptance.Verdict.UNAVAILABLE;}
    }
    r.put("originalOracleChecks",checks);r.put("originalOracle",BThinStrandAcceptance.ORACLE_ID);r.put("expectedRouteKind",result.expectedRouteKind());
    // Keep the old interior/domain predicates unchanged. An endpoint extension that
    // those predicates cannot accept is unavailable, never a normalized-path PASS.
    boolean endpointOutsideOriginalDomain=nativeSource.get(0).xMeters() < -8||nativeSource.get(0).xMeters() > 52
        ||nativeSource.get(nativeSource.size()-1).xMeters() < -8||nativeSource.get(nativeSource.size()-1).xMeters() > 52;
    if(unknown)return unavailable(r,"UNAVAILABLE_ORIGINAL_POINT_ORACLE");
    if(endpointOutsideOriginalDomain&&!remainder)return unavailable(r,"UNAVAILABLE_NATIVE_ENDPOINT_REFERENCE_ADAPTER");
    r.put("passed",anchors&&remainder);r.put("availability","ORIGINAL_INTERIOR_AND_BRANCH_CHECKS_WITH_NATIVE_EXACT_ANCHORS");return r;
   }
   probe.checkpoint();boolean geometry=V022BGapMatchedControlsTest.nativePointTruth(id,path,original,nativeSource);
   r.put("passed",anchors&&geometry);r.put("oracle","ORIGINAL_MATCHED_WHOLE_SEGMENT_TUBE");
   r.put("endpointBoundaryOwnership","UNCHANGED_ORIGINAL_METRIC_REGION_BOUNDARY_PREDICATE_NATIVE_EXACT_ANCHORS_SEPARATE");
   r.put("observedSupport",evidence==null?null:V022BGapMatchedControlsTest.nativePointObserved(evidence,path));
   if(id.equals("invalid-unknown-gap")||id.equals("unknown-detour")){
    r.put("negativeEligibilityPredicate",anchors&&(!geometry||Boolean.FALSE.equals(r.get("observedSupport"))));
    r.put("positiveQualification","NOT_AUTHORIZED_BY_NEGATIVE_CONTROL");
   }
   if(id.equals("uniform-null")||id.equals("alternating-null"))r.put("noiseNeutrality","UNAVAILABLE_FROM_POINT_GEOMETRY_FULL_ORIGINAL_GATE_REMAINS");
   return r;
  }
 }
 private static Map<String,Object> unavailable(Map<String,Object> r,String reason){r.put("available",false);r.put("passed",null);r.put("availability",reason);return r;}
 private BNativePointAcceptance(){}
}
