package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Finite-graph proof only; closed-region composition is not a generic selector identity. */
final class BSingleTrainComparatorRegion {
    @FunctionalInterface interface Solver<T>{T solve(MetricRegion region) throws Exception;}
    static final class Work {
        final long maximumPairs,maximumUnits;long pairs,finishedPairs,units,pointQueries,segmentQueries,noHookSegmentQueries,clips,refusedCharges;
        private final MessageDigest digest;private final ByteBuffer answer=ByteBuffer.allocate(16);
        boolean complete;String coordinateHash="UNAVAILABLE";Map<String,Object> mismatch=Map.of();
        Work(long pairs,long units){
            if(pairs<1||pairs>101_160||units<1||units>1_000_000_000L)throw new IllegalArgumentException("Fixed proof limits required");
            maximumPairs=pairs;maximumUnits=units;
            try{digest=MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        }
        void charge(long amount){if(amount<0||amount>maximumUnits-units){refusedCharges++;throw new BObservationIdentificationTool.RunLimitException("PROOF_CALLBACK_WORK");}units+=amount;}
        private void answer(int row,int a,int b,boolean hard,int selector,boolean candidate){
            answer.clear();answer.putInt(row).putInt(a).putInt(b).put((byte)(hard?1:0)).put((byte)selector).put((byte)(candidate?1:0));digest.update(answer.array(),0,answer.position());finishedPairs++;
        }
        Map<String,Object> fields(){
            String hash;try{hash=HexFormat.of().formatHex(((MessageDigest)digest.clone()).digest());}catch(CloneNotSupportedException failure){throw new IllegalStateException("SHA snapshot unavailable",failure);}
            var result=new TreeMap<String,Object>();result.put("schema",1);result.put("complete",complete);result.put("pairs",pairs);result.put("finishedPairs",finishedPairs);result.put("orderedAnswerSha256",hash);
            result.put("hashScope","ORDERED_COMPLETE_BOOLEAN_ANSWERS_FOR_FINISHED_PAIRS_ATTEMPTS_MAY_BE_PARTIAL");
            result.put("admittedCallbackUnits",units);result.put("refusedCallbackCharges",refusedCharges);result.put("explicitPointQueries",pointQueries);result.put("wholeSegmentQueries",segmentQueries);result.put("selectorClipQueries",clips);
            result.put("maximumPairs",maximumPairs);result.put("maximumCallbackUnits",maximumUnits);
            result.put("noHookSegmentQueries",noHookSegmentQueries);result.put("solverVariant","EXACT_CURRENT_INDEXED_AND_FALLBACK_CONVENIENCE_PREDICATE_CHECKED_AGAINST_CALLBACK_VARIANT");
            result.put("firstMismatch",mismatch);result.put("callbackUnitMeaning","ACTUAL_GEOMETRY_CALLBACK_CHARGES_INCLUDING_NLOGN_SORT_PROXY_NOT_COMPARISON_COUNT");
            result.put("coordinateSha256",coordinateHash);
            result.put("uncountedInternals","POINT_MEMBERSHIP_CLIP_ARITHMETIC_POLYGON_CONSTRUCTION_NOT_PRIMITIVE_TIMINGS");return result;
        }
    }
    static final class Certified {
        private final MetricRegion region;private boolean used;
        private Certified(MetricRegion region){this.region=region;}
        <T>T solveOnce(Solver<T> solver) throws Exception {
            if(used)throw new IllegalStateException("No second solver invocation");used=true;return solver.solve(region);
        }
    }
    static MetricRegion candidate(List<List<MetricPoint>> rows,List<List<MetricPoint>> tubes,AttemptMemoryLedger.Owner owner) {
        long vertices=tubes.stream().mapToLong(List::size).sum();if(rows.isEmpty()||tubes.isEmpty()||vertices>1024)throw new IllegalArgumentException("Fixed bounded polygons required");
        owner.reserve(8192+vertices*512L);
        double minX=0,maxX=120,minY=Double.POSITIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY;
        for(var row:rows)for(var p:row){minX=Math.min(minX,p.xMeters());maxX=Math.max(maxX,p.xMeters());minY=Math.min(minY,p.yMeters());maxY=Math.max(maxY,p.yMeters());}
        if(!Double.isFinite(minY-1)||!Double.isFinite(maxY+1)||!Double.isFinite(minX-1)||!Double.isFinite(maxX+1))throw new IllegalArgumentException("Finite bounded coordinates required");
        var polygons=new ArrayList<List<MetricPoint>>();polygons.addAll(MetricRegion.rectangle(minX-1,minY-1,40,maxY+1).polygons());
        polygons.addAll(MetricRegion.rectangle(80,minY-1,maxX+1,maxY+1).polygons());polygons.addAll(tubes);return new MetricRegion(polygons);
    }
    static Certified certify(List<List<MetricPoint>> rows,MetricRegion original,MetricRegion candidate,MetricRegion union,
            Work work,AttemptMemoryLedger.Owner owner,CancellationProbe cancellation) throws Exception {
        Objects.requireNonNull(original);Objects.requireNonNull(candidate);Objects.requireNonNull(union);
        for(var region:List.of(original,candidate,union))if(region.polygons().stream().mapToLong(List::size).sum()>1024)throw new IllegalArgumentException("Fixed geometry event envelope exceeded");
        if(work.pairs!=0||work.complete||rows.size()<2||rows.size()>81)throw new IllegalArgumentException("Fresh bounded full-graph proof required");
        long total=0;for(int i=0;i<rows.size();i++) {
            if(rows.get(i).isEmpty()||rows.get(i).size()>96)throw new IllegalArgumentException("Bounded complete rows required");
            if(i>0)total=Math.addExact(total,Math.multiplyExact((long)rows.get(i-1).size(),rows.get(i).size()));
        }
        try(var scratch=owner.child("finite-graph-proof")) {
            scratch.reserve(8192);
            var coordinates=MessageDigest.getInstance("SHA-256");var bytes=ByteBuffer.allocate(24);
            for(int i=0;i<rows.size();i++)for(int s=0;s<rows.get(i).size();s++){cancellation.checkpoint();var p=rows.get(i).get(s);bytes.clear();bytes.putInt(i).putInt(s).putDouble(p.xMeters()).putDouble(p.yMeters());coordinates.update(bytes.array());}
            work.coordinateHash=HexFormat.of().formatHex(coordinates.digest());
            for(int i=1;i<rows.size();i++)for(int a=0;a<rows.get(i-1).size();a++)for(int b=0;b<rows.get(i).size();b++) {
                cancellation.checkpoint();if(work.pairs>=work.maximumPairs)throw new BObservationIdentificationTool.RunLimitException("PROOF_PAIRS");work.pairs++;
                var start=rows.get(i-1).get(a);var end=rows.get(i).get(b);
                boolean hard=admitted(original,start,end,work,scratch,cancellation);int selector=2; // Not queried when original hard admission rejects.
                if(hard){work.clips++;var clip=BSingleTrainDiagnosticBridge.clip(start,end);selector=1;
                    if(clip.isPresent()){work.segmentQueries++;selector=union.containsSegment(clip.get().get(0),clip.get().get(1),cancellation,work::charge,scratch)?1:0;}}
                boolean actual=admitted(candidate,start,end,work,scratch,cancellation);work.answer(i-1,a,b,hard,selector,actual);
                if(actual!=(hard&&selector==1)) {
                    work.mismatch=Map.of("startProfile",i-1,"startState",a,"endState",b,"start",point(start),"end",point(end),
                            "originalHard",hard,"selector",selector==2?"UNAVAILABLE_ORIGINAL_HARD_REJECTED":selector==1,"candidate",actual);
                    throw new SecurityException("Finite-graph admission mismatch; solver forbidden");
                }
            }
            if(work.pairs!=total)throw new IllegalStateException("Missing finite-graph pair");cancellation.checkpoint();work.complete=true;return new Certified(candidate);
        }
    }
    private static Map<String,Object> point(MetricPoint p){return Map.of("xMeters",p.xMeters(),"yMeters",p.yMeters());}
    private static boolean admitted(MetricRegion region,MetricPoint a,MetricPoint b,Work w,AttemptMemoryLedger.Owner owner,CancellationProbe cancel) {
        w.pointQueries++;if(!region.contains(a))return false;w.pointQueries++;if(!region.contains(b))return false;
        w.segmentQueries++;boolean hooked=region.containsSegment(a,b,cancel,w::charge,owner);
        // Both frozen IndexedGeometryAdmissions and fallback TransitionAdmissionMemo use THIS
        // exact convenience predicate. Do not infer variant equality from documentation.
        boolean actual;
        try(var scratch=owner.child("actual-solver-geometry-variant")) {
            // <=1024 edges => <=2050 event slots. Conservative envelope for the no-owner
            // convenience event/list/boxing/sort scratch; its internals are NOT callback-counted.
            scratch.reserve(1_048_576);cancel.checkpoint();w.segmentQueries++;w.noHookSegmentQueries++;
            actual=region.containsSegment(a,b);cancel.checkpoint();
        }
        if(actual!=hooked){w.mismatch=Map.of("scope","CONVENIENCE_CALLBACK_VARIANT","start",point(a),"end",point(b),"convenience",actual,"callback",hooked);throw new SecurityException("Actual solver geometry variant disagrees; solver forbidden");}return actual;
    }
    static void requireFixedGraph(List<InferenceProfile> profiles,List<List<MetricPoint>> rows) {
        if(profiles.size()!=81||rows.size()!=81||rows.get(0).size()!=1||rows.get(80).size()!=1)throw new SecurityException("Original fixed anchors/rows required");
        long cells=0;int maximum=0;double previous=Double.NEGATIVE_INFINITY;
        for(int i=0;i<81;i++) {
            var profile=profiles.get(i);if(profile.chainageMeters()<=previous||rows.get(i).size()!=profile.cells().size())throw new SecurityException("Original profile order changed");previous=profile.chainageMeters();
            cells+=rows.get(i).size();maximum=Math.max(maximum,rows.get(i).size());
            // Preserve the frozen two-point resampler's operation order, not idealized 1.5*i.
            // Row41 is one ULP below61.5 after target/length followed by fraction*length.
            double target=i==80?120.0:i*120.0/80;
            double expectedX=(target/120.0)*120.0;
            for(int s=0;s<rows.get(i).size();s++) {
                var p=rows.get(i).get(s);if(!p.equals(profile.point(s))||Double.doubleToRawLongBits(p.xMeters())!=Double.doubleToRawLongBits(expectedX)||p.xMeters()==40||p.xMeters()==80)
                    throw new SecurityException("Fixed x graph or exact coordinate identity changed");
            }
        }
        if(cells!=2846||maximum!=36||!profiles.get(0).cells().get(0).exactAnchor()||!profiles.get(80).cells().get(0).exactAnchor())throw new SecurityException("Original state/anchor inventory changed");
    }
    private BSingleTrainComparatorRegion() { }
}
