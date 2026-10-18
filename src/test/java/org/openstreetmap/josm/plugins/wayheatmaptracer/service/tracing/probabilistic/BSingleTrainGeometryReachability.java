package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Diagnostic geometry reachability only; never inference or model qualification. */
final class BSingleTrainGeometryReachability {
    enum Status { WITNESS, EXHAUSTIVE_ABSENCE, INCOMPLETE_UNKNOWN }
    record Result(Status status,List<Integer> states,long examinedPairs,String reason) { }
    static final class Oracle {
        final MetricRegion union;final List<List<MetricPoint>> polygons;
        private final AttemptMemoryLedger.Owner owner;private final CancellationProbe cancellation;
        Oracle(List<List<MetricPoint>> curves,double pitch,AttemptMemoryLedger.Owner owner,CancellationProbe cancellation) {
            if(curves==null||curves.isEmpty()||!(pitch>0)||!Double.isFinite(pitch))throw new IllegalArgumentException("Bound curves required");
            this.owner=Objects.requireNonNull(owner);this.cancellation=Objects.requireNonNull(cancellation);
            long vertices=curves.stream().mapToLong(List::size).sum();
            if(vertices>4096)throw new IllegalArgumentException("Curve bound exceeded");
            owner.reserve(4096+vertices*512L);
            var polygons=new ArrayList<List<MetricPoint>>();
            for(var curve:curves) {
                cancellation.checkpoint();if(curve.size()<2)throw new IllegalArgumentException("Complete curves required");
                var polygon=new ArrayList<MetricPoint>();
                for(var p:curve)polygon.add(new MetricPoint(p.xMeters(),p.yMeters()-pitch));
                for(int i=curve.size()-1;i>=0;i--){var p=curve.get(i);polygon.add(new MetricPoint(p.xMeters(),p.yMeters()+pitch));}
                polygons.add(polygon);
            }
            union=new MetricRegion(polygons);this.polygons=union.polygons();
        }
        boolean intersects(MetricPoint a,MetricPoint b) {
            try {cancellation.checkpoint();return BSingleTrainDiagnosticBridge.clip(a,b).isPresent();}
            catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("Fixed clip seam failed",failure);}
        }
        boolean allowed(MetricPoint a,MetricPoint b) {
            try {
                cancellation.checkpoint();var clipped=BSingleTrainDiagnosticBridge.clip(a,b);
                return clipped.isEmpty()||union.containsSegment(clipped.get().get(0),clipped.get().get(1),cancellation,ignored->{},owner);
            }catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("Fixed clip seam failed",failure);}
        }
    }
    static Result search(List<List<MetricPoint>> rows,MetricRegion hard,Oracle oracle,
            AttemptMemoryLedger.Owner owner,CancellationProbe cancellation,long maximumPairs) {
        long examined=0;
        try(var scratch=owner.child("diagnostic-reachability")) {
            cancellation.checkpoint();
            if(rows==null||rows.size()<2||rows.size()>4096||rows.get(0).size()!=1||rows.get(rows.size()-1).size()!=1)
                throw new IllegalArgumentException("Fixed-anchor layered graph required");
            long cells=0;for(var row:rows){if(row.isEmpty()||row.size()>96)throw new IllegalArgumentException("Admitted rows required");cells+=row.size();}
            scratch.reserve(4096+rows.size()*128L+cells*32L);
            int[][] predecessor=new int[rows.size()][];
            for(int i=0;i<rows.size();i++){predecessor[i]=new int[2*rows.get(i).size()];Arrays.fill(predecessor[i],-1);}
            predecessor[0][0]=0;
            for(int i=1;i<rows.size();i++)for(int before=0;before<rows.get(i-1).size();before++) {
                if(predecessor[i-1][2*before]<0&&predecessor[i-1][2*before+1]<0)continue;
                for(int state=0;state<rows.get(i).size();state++) {
                    cancellation.checkpoint();if(examined>=maximumPairs)return new Result(Status.INCOMPLETE_UNKNOWN,List.of(),examined,"PAIR_LIMIT");
                    examined++;var a=rows.get(i-1).get(before);var b=rows.get(i).get(state);
                    if(hard!=null&&(!hard.contains(a)||!hard.contains(b)
                            ||!hard.containsSegment(a,b,cancellation,ignored->{},scratch)))continue;
                    if(!oracle.allowed(a,b))continue;
                    boolean intersects=oracle.intersects(a,b);
                    for(int seen=0;seen<2;seen++)if(predecessor[i-1][2*before+seen]>=0) {
                        int next=2*state+(intersects?1:seen);
                        if(predecessor[i][next]<0)predecessor[i][next]=2*before+seen;
                    }
                }
            }
            int end=1;
            if(predecessor[rows.size()-1][end]<0)return new Result(Status.EXHAUSTIVE_ABSENCE,List.of(),examined,"COMPLETE_REACHABLE_EXPANSION");
            var states=new ArrayList<Integer>();
            for(int i=rows.size()-1;i>=0;i--){states.add(end/2);end=predecessor[i][end];}
            Collections.reverse(states);cancellation.checkpoint();
            return new Result(Status.WITNESS,List.copyOf(states),examined,"GEOMETRY_ONLY_NOT_MODEL_QUALIFICATION");
        }catch(java.util.concurrent.CancellationException failure){return new Result(Status.INCOMPLETE_UNKNOWN,List.of(),examined,"CANCELLED");}
        catch(AttemptMemoryLedger.ResourceLimitException failure){return new Result(Status.INCOMPLETE_UNKNOWN,List.of(),examined,"MEMORY_LIMIT");}
    }
}
