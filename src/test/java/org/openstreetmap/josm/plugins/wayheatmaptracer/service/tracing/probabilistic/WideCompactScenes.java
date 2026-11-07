package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;
import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayInput;

/** Fixed public analytic rasters: native centers, no blur/dilation/oversampling. */
final class WideCompactScenes {
    static final double P=1.556065358178362,L=62*P,R=4*P,LEG=(L-StrictMath.PI*R)/2;
    static final List<String> IDS=List.of("remote-drift","thin-hairpin","faint-apex","distinct-parallel","diffuse-three-strand","remote-drift-reversed");
    static final String SPEC="wide-compact-v1;p=1.556065358178362;extent=62p,faint100.5m;radius=25;step=p;K32/8;defaults;cleanupOff;extentAdjustments=1;gaussianDrift=.9/.08,sigma=.55p;hairpinR=4p,rough=.3p;faint=.08,sigma=.65,apexX50,height3/base12;parallel=.35+/-.035,sep8p,sigma.55p,noiseSeed13579;diffuse=.06x3,amplitude2.5,wavelength24,sigma.65";
    record Scene(String id,FrozenReplayInput input,List<MetricPoint> analytic,boolean strong,boolean hairpin,double length) { }
    static Scene scene(String id){return scene(id,id.equals("faint-apex")?100.5:L,false);}
    static Scene scene(String id,BPolicy policy){return scene(id,id.equals("faint-apex")?100.5:L,false,policy);}
    static Scene tiny(){return scene("tiny",2.5*P,true);}
    /** Eight physical rows retain a nontrivial K32 full-work bound, with the same native +/-25 m evidence. */
    static Scene tinyRetention(){return scene("tiny",6.5*P,true);}
    private static Scene scene(String id,double length,boolean tiny) {
        return scene(id,length,tiny,BPolicy.INVENTORY_PREP_V4);
    }
    private static Scene scene(String id,double length,boolean tiny,BPolicy policy) {
        if(policy!=BPolicy.INVENTORY_PREP_V4&&policy!=BPolicy.INVENTORY_FRONTIER_V5)
            throw new IllegalArgumentException("An explicit V4/V5 inventory fixture policy is required");
        boolean hairpin=id.equals("thin-hairpin"),reverse=id.endsWith("reversed");
        var source=new ArrayList<MetricPoint>();
        if(hairpin) {
            source.add(new MetricPoint(0,-R));source.add(rough(LEG));
            for(int j=1;j<=12;j++)source.add(rough(LEG+R*StrictMath.PI*j/12));
            source.add(new MetricPoint(0,R));
        }else {
            double y=id.equals("distinct-parallel")?-4*P:0;
            source.add(new MetricPoint(0,y));source.add(new MetricPoint(length,y));
        }
        if(reverse)Collections.reverse(source);
        double originX=-36*P,originY=-36*P;
        int width=(int)StrictMath.ceil((length+72*P)/P)+1,height=73;
        double[] values=new double[width*height];boolean[] valid=new boolean[values.length];Arrays.fill(valid,true);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)values[y*width+x]=intensity(id,originX+x*P,originY+y*P,length,x,y);
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-.01,-.01),new GeographicPoint(.01,.01));
        var polygons=new ArrayList<List<MetricPoint>>();
        for(int i=1;i<source.size();i++) {
            var a=source.get(i-1);var b=source.get(i);
            polygons.add(MetricRegion.rectangle(StrictMath.min(a.xMeters(),b.xMeters())-25,StrictMath.min(a.yMeters(),b.yMeters())-25,
                    StrictMath.max(a.xMeters(),b.xMeters())+25,StrictMath.max(a.yMeters(),b.yMeters())+25).polygons().get(0));
        }
        var region=new MetricRegion(polygons);
        var rasterRegion=MetricRegion.rectangle(originX,originY,originX+(width-1)*P,originY+(height-1)*P);
        var scalar=new ScalarEvidenceField(width,height,values,valid,new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"public-wide-analytic",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
        var resolution=EvidenceResolution.nativeSource(P,P);
        var evidence=new EvidenceSnapshot("public-wide-"+id,frame,RasterMetricTransform.metricGrid(new MetricPoint(originX,originY),P,0,0,P),
                resolution,region,rasterRegion,Map.of("scalar",scalar),"public-wide-analytic-v1");
        var way=PrimitiveKey.existing(PrimitiveKey.Type.WAY,7000);var nodes=new ArrayList<PrimitiveKey>();
        Map<PrimitiveKey,DetachedPrimitive> primitives=new LinkedHashMap<>();
        for(int i=0;i<source.size();i++) {
            var key=PrimitiveKey.existing(PrimitiveKey.Type.NODE,7100+i);nodes.add(key);
            primitives.put(key,new DetachedNode(key,frame.toGeographic(source.get(i)),Map.of(),false,false));
        }
        primitives.put(way,new DetachedWay(way,nodes,Map.of("highway","path"),false,false));
        var protectedNodes=Set.of(nodes.get(0),nodes.get(nodes.size()-1));var movable=new HashSet<>(nodes);movable.removeAll(protectedNodes);
        var editable=new HashSet<PrimitiveKey>(movable);editable.add(way);
        var closure=new ClosureDescriptor(ClosureDescriptor.Scope.EDIT_COMPONENT,"public-wide-v1",primitives.keySet(),editable,movable,protectedNodes,Set.of(),
                Map.of(way,List.of(new OccurrenceRange(0,nodes.size()-1))),List.of(),region,region,true,true,true,true);
        var network=new NetworkSnapshot("wide-network-"+id,SnapshotRole.CAPTURED_BEFORE,"public-wide-dataset",1,closure,primitives,V022SnapshotFixtures.closedWorldReferrerWatches(primitives));
        var factualSource=nodes.stream().map(k->frame.toMetric(((DetachedNode)primitives.get(k)).coordinate())).toList();
        var exact=new TraceRequest(way,new OccurrenceRange(0,nodes.size()-1),TrackerMode.PROBABILISTIC,AlignmentMode.PRECISE_SHAPE,
                RecoveryPermissions.disabled(25),TraceBudgets.defaults(),evidence.snapshotId(),evidence.canonicalHash(),network.snapshotId(),network.canonicalHash(),
                "public-wide-v1",BPolicy.visibleExactParameterHash(),"public-native-pitch",P,new ProbabilisticProfileFactory().profileChainage(factualSource,P),resolution);
        var request=BPolicy.adaptiveRequest(exact,policy);
        var input=new FrozenReplayInput(request,evidence,network,new ModernTracePipeline.Options("scalar",GeometryCleanupConfig.disabled(),"synthetic",0),25,null);
        return new Scene(id,input,analytic(id,length),id.startsWith("remote-drift")||hairpin,hairpin,length);
    }
    private static MetricPoint rough(double s) {
        double x,y,nx,ny;
        if(s<=LEG){x=s;y=-R;nx=0;ny=1;}
        else if(s<=LEG+StrictMath.PI*R){double t=-StrictMath.PI/2+(s-LEG)/R;x=LEG+R*StrictMath.cos(t);y=R*StrictMath.sin(t);nx=-StrictMath.cos(t);ny=-StrictMath.sin(t);}
        else{x=LEG-(s-LEG-StrictMath.PI*R);y=R;nx=0;ny=-1;}
        double offset=.3*P*StrictMath.sin(2*StrictMath.PI*s/L);return new MetricPoint(x+nx*offset,y+ny*offset);
    }
    static double drift(double x,double length) {
        double u=x/length,ramp=.35;
        if(u<=0||u>=1)return 0;
        if(u<ramp)return 11*(1-StrictMath.cos(StrictMath.PI*u/ramp));
        if(u>1-ramp)return 11*(1-StrictMath.cos(StrictMath.PI*(1-u)/ramp));
        return 22;
    }
    static double hairpinDistance(MetricPoint p) {
        double d=StrictMath.min(distance(p,new MetricPoint(0,-R),new MetricPoint(LEG,-R)),distance(p,new MetricPoint(LEG,R),new MetricPoint(0,R)));
        return p.xMeters()>=LEG?StrictMath.min(d,StrictMath.abs(StrictMath.hypot(p.xMeters()-LEG,p.yMeters())-R)):d;
    }
    static double hairpinChainage(MetricPoint p) {
        double lower=distance(p,new MetricPoint(0,-R),new MetricPoint(LEG,-R)),upper=distance(p,new MetricPoint(LEG,R),new MetricPoint(0,R));
        double arc=p.xMeters()>=LEG?StrictMath.abs(StrictMath.hypot(p.xMeters()-LEG,p.yMeters())-R):Double.POSITIVE_INFINITY;
        if(arc<=StrictMath.min(lower,upper))return LEG+R*(StrictMath.atan2(p.yMeters(),p.xMeters()-LEG)+StrictMath.PI/2);
        return lower<=upper?StrictMath.max(0,StrictMath.min(LEG,p.xMeters())):LEG+StrictMath.PI*R+LEG-StrictMath.max(0,StrictMath.min(LEG,p.xMeters()));
    }
    static double distance(MetricPoint p,MetricPoint a,MetricPoint b) {
        double dx=b.xMeters()-a.xMeters(),dy=b.yMeters()-a.yMeters(),den=dx*dx+dy*dy;
        double t=den==0?0:StrictMath.max(0,StrictMath.min(1,((p.xMeters()-a.xMeters())*dx+(p.yMeters()-a.yMeters())*dy)/den));
        return StrictMath.hypot(p.xMeters()-a.xMeters()-t*dx,p.yMeters()-a.yMeters()-t*dy);
    }
    private static double intensity(String id,double x,double y,double length,int col,int row) {
        double value=.02;
        if(id.equals("thin-hairpin"))return .02+.9*StrictMath.exp(-.5*StrictMath.pow(hairpinDistance(new MetricPoint(x,y))/(.55*P),2));
        if(id.startsWith("remote-drift"))value+=.9*g(y,drift(x,length),.55*P)+.08*g(y,0,.55*P);
        else if(id.equals("faint-apex"))value+=.08*g(y,3*StrictMath.max(0,1-StrictMath.abs(x-50)/6),.65);
        else if(id.equals("diffuse-three-strand")){for(int s=0;s<3;s++)value+=.06*g(y,2.5*StrictMath.sin(2*StrictMath.PI*x/24+s*2*StrictMath.PI/3),.65);}
        else if(id.equals("distinct-parallel")) {
            double delta=.035*StrictMath.cos(2*StrictMath.PI*x/(12*P));value+=(.35+delta)*g(y,-4*P,.55*P)+(.35-delta)*g(y,4*P,.55*P);
            long seed=(col*73856093L)^(row*19349663L)^13579L;seed^=seed>>>13;seed*=1274126177L;
            if((seed&1023)==0)value+=.4;
        }else value+=.7*g(y,0,.55*P);
        return StrictMath.min(1,value);
    }
    private static double g(double y,double center,double sigma){double d=(y-center)/sigma;return StrictMath.exp(-.5*d*d);}
    private static List<MetricPoint> analytic(String id,double length) {
        var points=new ArrayList<MetricPoint>();
        if(id.equals("thin-hairpin")) {
            points.add(new MetricPoint(0,-R));points.add(new MetricPoint(LEG,-R));
            int n=(int)StrictMath.ceil(StrictMath.PI*R/(P/32));for(int i=1;i<=n;i++){double t=-StrictMath.PI/2+StrictMath.PI*i/n;points.add(new MetricPoint(LEG+R*StrictMath.cos(t),R*StrictMath.sin(t)));}
            points.add(new MetricPoint(0,R));return List.copyOf(points);
        }
        var xs=new TreeSet<Double>();int n=id.startsWith("remote-drift")?(int)StrictMath.ceil(length/(P/32)):1;for(int i=0;i<=n;i++)xs.add(length*i/n);
        if(id.equals("faint-apex"))xs.addAll(List.of(44.0,50.0,56.0));
        for(double x:xs)points.add(new MetricPoint(x,id.startsWith("remote-drift")?drift(x,length):id.equals("faint-apex")?3*StrictMath.max(0,1-StrictMath.abs(x-50)/6):id.equals("distinct-parallel")?-4*P:0));
        return List.copyOf(points);
    }
    private WideCompactScenes() { }
}
