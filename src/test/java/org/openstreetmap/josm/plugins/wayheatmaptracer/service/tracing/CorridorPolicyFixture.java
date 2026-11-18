package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;

/** Small independent detached source/image fixtures shared by adapter and cancellation-owner tests. */
public final class CorridorPolicyFixture {
    public record Fixture(TraceRequest request,EvidenceSnapshot evidence,NetworkSnapshot network,
        ModernTracePipeline.Options options){ }
    private CorridorPolicyFixture(){ }

    public static Fixture straight(boolean signal,boolean parallel,Set<Integer> fixed){
        var points=List.of(new MetricPoint(20,50),new MetricPoint(75,50),new MetricPoint(140,50));
        return create(points,signal,parallel,fixed,20,-1);
    }
    public static Fixture parabola(Set<Integer> fixed){
        var points=new ArrayList<MetricPoint>();
        // Native fixed-endpoint approach dependencies span15m. These same analytic arms
        // extend beyond20m of factual source arc on each side of the unchanged apex.
        for(int j=-15;j<=15;j++)points.add(new MetricPoint(64+j,64-9.7+j*j/(2*8.5)));
        return create(points,true,false,fixed,1,-1);
    }
    /** Reviewed finite-chord admission control: same image/parabola, factual nonuniform ground chainage. */
    public static Fixture admittedParabola(){
        var points=new ArrayList<MetricPoint>();
        for(int j=-10;j<=10;j++){
            double u=1.5*j;
            points.add(new MetricPoint(64+u,64-9.7+u*u/(2*8.5)));
        }
        return create(points,true,false,Set.of(),1.5,-1);
    }
    public static Fixture parabolaWithProtectedBetweenRows(){
        var points=new ArrayList<MetricPoint>();
        for(int j=-15;j<=15;j++){
            points.add(new MetricPoint(64+j,64-9.7+j*j/(2*8.5)));
            if(j==0)points.add(new MetricPoint(64.5,64-9.7+.5/(2*8.5)));
        }
        return create(points,true,false,Set.of(16),1,16);
    }
    private static Fixture create(List<MetricPoint> source,boolean signal,boolean parallel,Set<Integer> fixed,double step,int omittedSourceRow){
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(50,14),
            new GeographicPoint(49.99,13.99),new GeographicPoint(50.01,14.02));
        var transform=RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);
        int width=160,height=100;var values=new double[width*height];var valid=new boolean[values.length];Arrays.fill(valid,true);
        double center=source.size()>3?64:53;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            double primary=signal?Math.exp(-.5*Math.pow((y-center)/.8,2)):0;
            double second=signal&&parallel?.8*Math.exp(-.5*Math.pow((y-63)/.8,2)):0;
            values[y*width+x]=Math.min(1,primary+second);
        }
        var field=new ScalarEvidenceField(width,height,values,valid,new EvidenceFieldLineage(
            EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
            "native",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
        var region=MetricRegion.rectangle(0,0,159,99);
        var evidence=new EvidenceSnapshot("policy-evidence",frame,transform,EvidenceResolution.nativeSource(1,1),region,
            MetricRegion.rectangle(-.5,-.5,159.5,99.5),Map.of("native",field),"independent-policy-ridge");
        var nodes=new ArrayList<PrimitiveKey>();var primitives=new LinkedHashMap<PrimitiveKey,DetachedPrimitive>();
        var geographic=new ArrayList<GeographicPoint>();var factual=new ArrayList<MetricPoint>();
        for(int i=0;i<source.size();i++){
            var key=PrimitiveKey.existing(PrimitiveKey.Type.NODE,i+1);var geo=frame.toGeographic(source.get(i));
            nodes.add(key);geographic.add(geo);factual.add(frame.toMetric(geo));
            primitives.put(key,new DetachedNode(key,geo,Map.of(),false,false));
        }
        var way=PrimitiveKey.existing(PrimitiveKey.Type.WAY,100);
        primitives.put(way,new DetachedWay(way,nodes,Map.of("highway","path"),false,false));
        var protectedNodes=Set.of(nodes.get(0),nodes.get(nodes.size()-1));
        var movable=new LinkedHashSet<>(nodes);movable.removeAll(protectedNodes);
        var editable=new LinkedHashSet<>(movable);editable.add(way);
        var range=new OccurrenceRange(0,nodes.size()-1);
        var closure=new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,"policy-fixture-v1",primitives.keySet(),editable,
            movable,protectedNodes,Set.of(),Map.of(way,List.of(range)),List.of(),region,region,true,true,true,true);
        var network=new NetworkSnapshot("policy-network",SnapshotRole.CAPTURED_BEFORE,"policy-dataset",1,closure,primitives,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.V022SnapshotFixtures.closedWorldReferrerWatches(primitives));
        ProfileChainage chainage=source.size()>3?ProfileChainage.measured(factual,step):
            new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory().profileChainage(factual,step);
        if(omittedSourceRow>=0){
            var rows=new ArrayList<>(chainage.cumulativeGroundMeters());rows.remove(omittedSourceRow);
            chainage=new ProfileChainage(rows,step);
        }
        var input=IntervalTraceRequestFactory.corridorForAnchors(chainage,factual,geographic,evidence)
            .withPolicy(CorridorTraceInput.ExecutionPolicy.R3_OWNED_BOUNDED_BRIDGE_V1,fixed);
        var request=new TraceRequest(way,range,TrackerMode.CORRIDOR_AWARE,AlignmentMode.PRECISE_SHAPE,RecoveryPermissions.disabled(25),
            TraceBudgets.defaults(),evidence.snapshotId(),evidence.canonicalHash(),network.snapshotId(),network.canonicalHash(),
            "1".repeat(64),"2".repeat(64),"policy-fixture",step,chainage,evidence.resolution(),Optional.of(input));
        return new Fixture(request,evidence,network,new ModernTracePipeline.Options("native",GeometryCleanupConfig.disabled(),"synthetic",0));
    }

    public static CorridorFinalizationCancelledException finalizationCancellation(){
        var f=straight(true,false,Set.of());
        CancellationProbe probe=()->StackWalker.getInstance().walk(frames->frames.anyMatch(frame->frame.getClassName().endsWith(".FinalGeometryEvaluator")));
        try{new ModernTracePipeline(new CorridorEngineAdapter("native")).runWithUsage(f.request(),f.evidence(),f.network(),f.options(),probe);}
        catch(CorridorFinalizationCancelledException cancelled){return cancelled;}
        throw new AssertionError("Actual finalization must cancel after engine completion");
    }
}
