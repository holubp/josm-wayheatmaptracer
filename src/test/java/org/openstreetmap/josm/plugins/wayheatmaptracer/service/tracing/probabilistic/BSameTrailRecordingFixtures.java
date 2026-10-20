package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.nio.*;
import java.nio.file.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;

/** Strong equal-response recordings of one latent trail; never model inputs beyond scalar/source. */
final class BSameTrailRecordingFixtures {
    static final String THREE="same-trail-three-strong",FIVE="same-trail-five-strong";
    static final List<String> IDS=List.of(THREE,FIVE);
    private BSameTrailRecordingFixtures() { }
    static void exportNative(Path directory) throws Exception {
        var ledger=new AttemptMemoryLedger(BGapEvidence.BYTES);
        // Fixed raw planes/clones+at most475 generated points in the current fixture;
        // both coordinate metadata records, current byte buffers and serialization fit1MiB.
        try(var owner=ledger.rootOwner();var render=owner.reserve(32L*1024*1024);var metadata=owner.reserve(1024L*1024)) {
            var records=new ArrayList<Map<String,Object>>();long total=0;Files.createDirectories(directory);
            for(String id:IDS) {
                var fixture=byId(id);var scalar=fixture.scalar();var validity=fixture.validity();
                var plane=ByteBuffer.allocate(scalar.length*8).order(ByteOrder.BIG_ENDIAN);for(double value:scalar)plane.putDouble(value);
                byte[] mask=new byte[validity.length];for(int i=0;i<mask.length;i++)mask[i]=(byte)(validity[i]?1:0);
                String raster=id+".f64",valid=id+".valid";total=write(directory,raster,plane.array(),total);total=write(directory,valid,mask,total);
                var record=new LinkedHashMap<String,Object>();record.put("id",id);record.put("width",81);record.put("height",81);
                record.put("origin",List.of(-25,-20));record.put("pitch",1);record.put("background",.02);record.put("recordingCount",fixture.count());
                record.put("source",coordinates(source()));record.put("expected",coordinates(source()));
                record.put("traces",fixture.traces().stream().map(BSameTrailRecordingFixtures::coordinates).toList());
                record.put("meaning","ONE_TRAIL_NOISY_RECORDINGS_NO_PRIVILEGED_CENTRAL_INPUT");
                record.put("rasterFile",raster);record.put("rasterEncoding","big-endian-float64-row-major");record.put("rasterSha256",BIdentificationReceipt.hash(plane.array()));
                record.put("validityFile",valid);record.put("validityEncoding","row-major-u8-0-or-1");record.put("validitySha256",BIdentificationReceipt.hash(mask));records.add(record);
            }
            byte[] recipe;try(var input=BSameTrailRecordingFixtures.class.getResourceAsStream("/b-observed-gap/same-trail-recordings.json")) {
                if(input==null)throw new IllegalStateException("Frozen recipe absent");recipe=input.readNBytes(65537);
                if(recipe.length>65536)throw new IllegalArgumentException("Frozen recipe too large");}
            var receipt=Map.of("schema","same-trail-recordings-native-v1","recipeSha256",BIdentificationReceipt.hash(recipe),"fixtures",records);
            write(directory,"native-fixtures.json",BIdentificationReceipt.canonical(receipt).getBytes(java.nio.charset.StandardCharsets.UTF_8),total);
        }if(ledger.currentBytes()!=0)throw new IllegalStateException("Same-trail export ownership leaked");
    }
    private static List<List<Double>> coordinates(List<MetricPoint> points){return points.stream().map(p->List.of(p.xMeters(),p.yMeters())).toList();}
    private static long write(Path directory,String name,byte[] bytes,long total) throws Exception {
        if(!List.of("native-fixtures.json",THREE+".f64",THREE+".valid",FIVE+".f64",FIVE+".valid").contains(name)||bytes.length>65536)
            throw new IllegalArgumentException("Fixed native export leaf required");
        total=Math.addExact(total,bytes.length);if(total>2L*1024*1024*1024)throw new IllegalArgumentException("Synthetic disk cap");
        Files.write(directory.resolve(name),bytes);return total;
    }
    static List<MetricPoint> source(){return BThinSwitchbackFixtures.source();}
    static double length(){return BThinSwitchbackFixtures.length();}
    static double response(int occupancy){if(occupancy<0||occupancy>5)throw new IllegalArgumentException("Fixed count required");return .02+.98*(1-StrictMath.pow(.30,occupancy));}
    static double[] stations(double step){return new Frame().stations(step);}
    static MetricPoint referenceAt(double s){return new Frame().referenceAt(s);}
    static MetricPoint normalAt(double s){return new Frame().normalAt(s);}
    static MetricPoint recordingAt(int count,int recording,double s){return new Frame().recordingAt(count,recording,s);}
    static List<MetricPoint> recording(int count,int recording,double step){return new Frame().recording(count,recording,step);}
    static Fixture byId(String id){int count=id.equals(THREE)?3:id.equals(FIVE)?5:0;if(count==0)throw new IllegalArgumentException("Unknown same-trail fixture");
        var frame=new Frame();var traces=new ArrayList<List<MetricPoint>>();for(int j=0;j<count;j++)traces.add(frame.recording(count,j,1));
        double[] scalar=new double[6561];boolean[] validity=new boolean[scalar.length];Arrays.fill(validity,true);
        for(int y=0;y<81;y++)for(int x=0;x<81;x++) {
            var point=new MetricPoint(-25+x,-20+y);int occupancy=0;
            for(var trace:traces)if(distance(point,trace)<=.5)occupancy++;
            scalar[y*81+x]=response(occupancy);
        }
        return new Fixture(id,count,scalar,validity,traces,frame.stations(1));}
    private static double distance(MetricPoint point,List<MetricPoint> trace) {
        double best=Double.POSITIVE_INFINITY;
        for(int i=1;i<trace.size();i++){var a=trace.get(i-1);var b=trace.get(i);double dx=b.xMeters()-a.xMeters(),dy=b.yMeters()-a.yMeters();
            double norm=dx*dx+dy*dy;double t=norm==0?0:((point.xMeters()-a.xMeters())*dx+(point.yMeters()-a.yMeters())*dy)/norm;t=Math.max(0,Math.min(1,t));
            best=Math.min(best,StrictMath.hypot(point.xMeters()-(a.xMeters()+t*dx),point.yMeters()-(a.yMeters()+t*dy)));}
        return best;
    }
    /** Frame depends only on the frozen source; sampling station insertion cannot change it. */
    private static final class Frame {
        final List<MetricPoint> source=BSameTrailRecordingFixtures.source();
        final double[] chain=new double[source.size()],nx=new double[source.size()],ny=new double[source.size()];
        final double length;
        Frame(){
            for(int i=1;i<source.size();i++)chain[i]=chain[i-1]+source.get(i-1).distanceTo(source.get(i));length=chain[chain.length-1];
            for(int i=0;i<source.size();i++) {
                double tx=0,ty=0;
                if(i>0){var a=source.get(i-1);var b=source.get(i);double norm=a.distanceTo(b);tx+=(b.xMeters()-a.xMeters())/norm;ty+=(b.yMeters()-a.yMeters())/norm;}
                if(i+1<source.size()){var a=source.get(i);var b=source.get(i+1);double norm=a.distanceTo(b);tx+=(b.xMeters()-a.xMeters())/norm;ty+=(b.yMeters()-a.yMeters())/norm;}
                double norm=StrictMath.hypot(tx,ty);if(!(norm>0)||!Double.isFinite(norm))throw new IllegalArgumentException("Singular declared source tangent");
                nx[i]=-ty/norm;ny[i]=tx/norm;
            }
        }
        int segment(double s){if(!Double.isFinite(s)||s<0||s>length)throw new IllegalArgumentException("Chainage outside declared source");
            int segment=1;while(segment+1<chain.length&&s>chain[segment])segment++;return segment;}
        MetricPoint referenceAt(double s){int i=segment(s);if(s==chain[i])return source.get(i);if(s==0)return source.get(0);
            var a=source.get(i-1);var b=source.get(i);double t=(s-chain[i-1])/(chain[i]-chain[i-1]);
            return new MetricPoint(a.xMeters()+t*(b.xMeters()-a.xMeters()),a.yMeters()+t*(b.yMeters()-a.yMeters()));}
        MetricPoint normalAt(double s){int i=segment(s);double t=(s-chain[i-1])/(chain[i]-chain[i-1]);
            double x=nx[i-1]+t*(nx[i]-nx[i-1]),y=ny[i-1]+t*(ny[i]-ny[i-1]),norm=StrictMath.hypot(x,y);
            if(!(norm>0)||!Double.isFinite(norm))throw new IllegalArgumentException("Singular declared interpolated normal");return new MetricPoint(x/norm,y/norm);}
        double[] stations(double step){if(!Double.isFinite(step)||step<=0||step<.25||step>length)throw new IllegalArgumentException("Fixed bounded station step required");
            var stations=new TreeSet<Double>();for(int i=0;i*step<length;i++)stations.add(i*step);for(double s:chain)stations.add(s);
            double[] result=new double[stations.size()];int i=0;for(double s:stations)result[i++]=s;return result;}
        MetricPoint recordingAt(int count,int recording,double s){
            if((count!=3&&count!=5)||recording<0||recording>=count)throw new IllegalArgumentException("Fixed recording required");
            var base=referenceAt(s);var normal=normalAt(s);double u=s/length;
            double amplitude=2+.4*StrictMath.pow(StrictMath.sin(3*StrictMath.PI*u+.2),2);
            double end=Math.min(1,Math.min(s/4,(length-s)/4)),tip=1;
            for(int index:new int[]{4,8})tip=Math.min(tip,Math.min(1,.2+.8*StrictMath.abs(s-chain[index])/3));
            double phi=2*StrictMath.PI*recording/count+.17+.13*StrictMath.sin(1.7*recording+.6);
            double psi=4*StrictMath.PI*recording/count+.63+.19*StrictMath.cos(2.3*recording+.2);
            double offset=amplitude*end*tip*(.7*StrictMath.sin(8*StrictMath.PI*u+phi)+.3*StrictMath.sin(18*StrictMath.PI*u+psi));
            return new MetricPoint(base.xMeters()+offset*normal.xMeters(),base.yMeters()+offset*normal.yMeters());
        }
        List<MetricPoint> recording(int count,int recording,double step){var points=new ArrayList<MetricPoint>();for(double s:stations(step))points.add(recordingAt(count,recording,s));return List.copyOf(points);}
    }
    record Fixture(String id,int count,double[] scalar,boolean[] validity,List<List<MetricPoint>> traces,double[] stations) {
        Fixture{scalar=scalar.clone();validity=validity.clone();traces=traces.stream().map(List::copyOf).toList();stations=stations.clone();}
        @Override public double[] scalar(){return scalar.clone();}
        @Override public boolean[] validity(){return validity.clone();}
        @Override public double[] stations(){return stations.clone();}
    }
}
