package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.nio.*;
import java.nio.file.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** One additive fixed public visualization control; old fixtures and exports are untouched. */
final class BSeparatedSameTrailFixtures {
    static final String ID="same-trail-three-separated-strong";
    private BSeparatedSameTrailFixtures() { }
    static MetricPoint point(int j,double s) {
        if(j<0||j>=3)throw new IllegalArgumentException("Fixed recording required");
        var base=BSameTrailRecordingFixtures.referenceAt(s);var normal=BSameTrailRecordingFixtures.normalAt(s);
        double length=BSameTrailRecordingFixtures.length(),u=s/length,chain=0,tip=1;
        var source=BSameTrailRecordingFixtures.source();
        for(int i=1;i<source.size();i++){chain+=source.get(i-1).distanceTo(source.get(i));
            if(i==4||i==8)tip=Math.min(tip,Math.min(1,.2+.8*StrictMath.abs(s-chain)/3));}
        double bias=j==0?-4:j==1?.3:3.7;
        double offset=Math.min(1,Math.min(s/4,(length-s)/4))*tip*(bias+.6*StrictMath.sin(2*StrictMath.PI*u+.35)
                +.6*StrictMath.sin(2*StrictMath.PI*u+2*StrictMath.PI*(j-1)/3+.35)+.1*StrictMath.sin(4*StrictMath.PI*u+1.3*j+.7));
        return new MetricPoint(base.xMeters()+offset*normal.xMeters(),base.yMeters()+offset*normal.yMeters());
    }
    static BSameTrailRecordingFixtures.Fixture fixture(){
        var stations=BSameTrailRecordingFixtures.stations(1);var traces=new ArrayList<List<MetricPoint>>();
        for(int j=0;j<3;j++){var trace=new ArrayList<MetricPoint>();for(double s:stations)trace.add(point(j,s));traces.add(List.copyOf(trace));}
        double[] scalar=new double[6561];boolean[] valid=new boolean[6561];Arrays.fill(valid,true);
        for(int y=0;y<81;y++)for(int x=0;x<81;x++){var p=new MetricPoint(x-25,y-20);int k=0;
            for(var trace:traces)if(distance(p,trace)<=.5)k++;scalar[y*81+x]=BSameTrailRecordingFixtures.response(k);}
        return new BSameTrailRecordingFixtures.Fixture(ID,3,scalar,valid,traces,stations);
    }
    /** Precharge the existing separated fixture call without changing its
     * recording order or making an unfunded caller-owned Fixture. */
    static BSameTrailRecordingFixtures.Fixture fixtureOwned(BSharedCaptureAdmission owner){
        var reservation=owner.reserveSeparatedSameTrailFixtureUnit();
        try{
            var result=fixture();reservation.adopt(result);return result;
        }catch(RuntimeException|Error primary){
            try{reservation.close();}catch(RuntimeException|Error secondary){ /* preserve primary */ }
            owner.abortOriginalScalarInputUnit(primary);throw primary;
        }
    }
    private static double distance(MetricPoint p,List<MetricPoint> trace){double best=Double.POSITIVE_INFINITY;
        for(int i=1;i<trace.size();i++){var a=trace.get(i-1);var b=trace.get(i);double dx=b.xMeters()-a.xMeters(),dy=b.yMeters()-a.yMeters(),norm=dx*dx+dy*dy;
            double t=norm==0?0:Math.max(0,Math.min(1,((p.xMeters()-a.xMeters())*dx+(p.yMeters()-a.yMeters())*dy)/norm));
            best=Math.min(best,StrictMath.hypot(p.xMeters()-(a.xMeters()+t*dx),p.yMeters()-(a.yMeters()+t*dy)));}return best;}
    static void exportNative(Path directory) throws Exception {
        var ledger=new AttemptMemoryLedger(BGapEvidence.BYTES);
        // One81² fixture, three94-point traces, raw buffers and bounded JSON under the existing funded envelopes.
        try(var owner=ledger.rootOwner();var render=owner.reserve(32L*1024*1024);var metadata=owner.reserve(1024L*1024)){
            var f=fixture();var values=f.scalar();var valid=f.validity();var bytes=ByteBuffer.allocate(values.length*8).order(ByteOrder.BIG_ENDIAN);
            for(double v:values)bytes.putDouble(v);byte[] mask=new byte[valid.length];for(int i=0;i<mask.length;i++)mask[i]=(byte)(valid[i]?1:0);
            Files.createDirectories(directory);long total=write(directory,ID+".f64",bytes.array(),0);total=write(directory,ID+".valid",mask,total);
            var record=new LinkedHashMap<String,Object>();record.put("id",ID);record.put("width",81);record.put("height",81);record.put("origin",List.of(-25,-20));record.put("pitch",1);
            record.put("source",coordinates(BSameTrailRecordingFixtures.source()));record.put("expected",coordinates(BSameTrailRecordingFixtures.source()));
            record.put("traces",f.traces().stream().map(BSeparatedSameTrailFixtures::coordinates).toList());record.put("recordingCount",3);record.put("background",.02);
            record.put("meaning","ONE_TRAIL_NOISY_RECORDINGS_NO_PRIVILEGED_CENTRAL_INPUT");record.put("rasterFile",ID+".f64");record.put("rasterSha256",BIdentificationReceipt.hash(bytes.array()));
            record.put("rasterEncoding","big-endian-float64-row-major");record.put("validityFile",ID+".valid");record.put("validitySha256",BIdentificationReceipt.hash(mask));record.put("validityEncoding","row-major-u8-0-or-1");
            byte[] recipe;try(var input=BSeparatedSameTrailFixtures.class.getResourceAsStream("/b-observed-gap/same-trail-separated.json")){
                if(input==null)throw new IllegalStateException("Frozen recipe absent");recipe=input.readNBytes(65537);if(recipe.length>65536)throw new IllegalArgumentException("Recipe leaf cap");}
            write(directory,"native-fixtures.json",BIdentificationReceipt.canonical(Map.of("schema","same-trail-separated-native-v1","recipeSha256",BIdentificationReceipt.hash(recipe),"fixtures",List.of(record))).getBytes(java.nio.charset.StandardCharsets.UTF_8),total);
        }if(ledger.currentBytes()!=0)throw new IllegalStateException("Native export ownership leaked");
    }
    private static List<List<Double>> coordinates(List<MetricPoint> points){return points.stream().map(p->List.of(p.xMeters(),p.yMeters())).toList();}
    private static long write(Path directory,String name,byte[] value,long total) throws Exception {
        if(!List.of("native-fixtures.json",ID+".f64",ID+".valid").contains(name)||value.length>65536)throw new IllegalArgumentException("Fixed leaf cap");
        total=Math.addExact(total,value.length);if(total>2L*1024*1024*1024)throw new IllegalArgumentException("Synthetic disk cap");Files.write(directory.resolve(name),value);return total;
    }
}
