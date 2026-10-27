package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import java.nio.*;
import java.nio.file.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;

/** Independent schematic turn-preservation controls; no private pixels/points, model or scoring. */
final class BThinSwitchbackFixtures {
    static final String STRONG="switchback-thin-strong", FAINT="switchback-thin-faint";
    static final List<String> IDS=List.of(STRONG,FAINT);
    private BThinSwitchbackFixtures() { }
    static MetricPoint origin(){return new MetricPoint(-25,-20);}
    static List<MetricPoint> source() {
        return List.of(p(0,0),p(9,4),p(19,12),p(24,16),p(25,19),p(20,20),
                p(11,18),p(4,17),p(1,20),p(5,25),p(14,27),p(23,29));
    }
    static List<List<MetricPoint>> fragments() {
        return List.of(List.of(p(9,1),p(14,4),p(18,7)),List.of(p(8,21),p(13,22),p(17,23)));
    }
    static double length() {var p=source();double d=0;for(int i=1;i<p.size();i++)d+=p.get(i-1).distanceTo(p.get(i));return d;}
    static Fixture byId(String id) {
        if(!IDS.contains(id))throw new IllegalArgumentException("Unknown frozen switchback");
        double amplitude=id.equals(STRONG)?.70:.08;var truth=source();var fragments=fragments();
        double[] scalar=new double[81*81];boolean[] valid=new boolean[scalar.length];Arrays.fill(valid,true);
        for(int row=0;row<81;row++)for(int col=0;col<81;col++) {
            var point=p(-25+col,-20+row);double value=.02;
            if(distance(point,truth)<=.5)value+=amplitude*(point.distanceTo(p(25,19))<=2?1.25:1);
            for(var fragment:fragments)if(distance(point,fragment)<=.5)value+=amplitude*.5;
            if(!Double.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Declared native scalar outside unit interval");
            scalar[row*81+col]=value;
        }
        return new Fixture(id,amplitude,scalar,valid);
    }
    /** The owned branch prepays all original byId allocation sites as one
     * source-derived reservation before it invokes the unchanged renderer. */
    static Fixture byIdOwned(BSharedCaptureAdmission owner,String id){
        var reservation=owner.reserveThinSwitchbackFixtureUnit(id);
        try{
            var fixture=byId(id);reservation.adopt(fixture);return fixture;
        }catch(RuntimeException|Error primary){
            try{reservation.close();}catch(RuntimeException|Error secondary){ /* preserve primary */ }
            owner.abortOriginalScalarInputUnit(primary);throw primary;
        }
    }
    private static double distance(MetricPoint point,List<MetricPoint> polyline) {
        double distance=Double.POSITIVE_INFINITY;
        for(int i=1;i<polyline.size();i++)distance=Math.min(distance,distance(point,polyline.get(i-1),polyline.get(i)));
        return distance;
    }
    private static double distance(MetricPoint p,MetricPoint a,MetricPoint b) {
        double dx=b.xMeters()-a.xMeters(),dy=b.yMeters()-a.yMeters();
        double t=((p.xMeters()-a.xMeters())*dx+(p.yMeters()-a.yMeters())*dy)/(dx*dx+dy*dy);t=Math.max(0,Math.min(1,t));
        return StrictMath.hypot(p.xMeters()-(a.xMeters()+t*dx),p.yMeters()-(a.yMeters()+t*dy));
    }
    private static MetricPoint p(double x,double y){return new MetricPoint(x,y);}
    /** Fixed public synthetic pixels only; no capture, extraction, evaluation or solve. */
    static void exportNative(Path directory) throws Exception {
        var ledger=new AttemptMemoryLedger(BGapEvidence.BYTES);
        // <=2 native scalar/valid arrays, clones, current52,488B encoded plane,
        // current6,561B mask and two small metadata records fit this fixed envelope.
        try(var owner=ledger.rootOwner();var render=owner.reserve(32L*1024*1024);var metadata=owner.reserve(1024L*1024)) {
            var records=new ArrayList<Map<String,Object>>();long[] written={0};Files.createDirectories(directory);
            for(String id:IDS) {
                var fixture=byId(id);var scalar=fixture.scalar();var validity=fixture.validity();
                var plane=ByteBuffer.allocate(scalar.length*8).order(ByteOrder.BIG_ENDIAN);
                for(double value:scalar)plane.putDouble(value);
                var mask=new byte[validity.length];for(int i=0;i<mask.length;i++)mask[i]=(byte)(validity[i]?1:0);
                String raster=id+".f64",valid=id+".valid";
                writeNative(directory,raster,plane.array(),written);writeNative(directory,valid,mask,written);
                var record=new LinkedHashMap<String,Object>();record.put("id",id);record.put("width",81);record.put("height",81);
                record.put("origin",List.of(-25,-20));record.put("pitch",1);record.put("background",.02);record.put("mainAmplitude",fixture.amplitude());
                record.put("source",coordinates(source()));record.put("expected",coordinates(source()));
                record.put("fragments",fragments().stream().map(BThinSwitchbackFixtures::coordinates).toList());
                record.put("rasterFile",raster);record.put("rasterEncoding","big-endian-float64-row-major");
                record.put("rasterSha256",BIdentificationReceipt.hash(plane.array()));record.put("validityFile",valid);
                record.put("validityEncoding","row-major-u8-0-or-1");record.put("validitySha256",BIdentificationReceipt.hash(mask));
                record.put("meaning","PRESERVE_SUPPORTED_TURN_NOT_LARGE_OFF_WAY_RECOVERY");records.add(record);
            }
            byte[] recipe;try(var input=BThinSwitchbackFixtures.class.getResourceAsStream("/b-observed-gap/thin-switchback-controls.json")) {
                if(input==null)throw new IllegalStateException("Frozen recipe absent");recipe=input.readNBytes(65537);
                if(recipe.length>65536)throw new IllegalArgumentException("Frozen recipe too large");
            }
            var metadataReceipt=Map.of("schema","thin-switchback-native-v1","recipeSha256",BIdentificationReceipt.hash(recipe),"fixtures",records);
            writeNative(directory,"native-fixtures.json",BIdentificationReceipt.canonical(metadataReceipt).getBytes(java.nio.charset.StandardCharsets.UTF_8),written);
        }
        if(ledger.currentBytes()!=0)throw new IllegalStateException("Synthetic export ownership leaked");
    }
    private static List<List<Double>> coordinates(List<MetricPoint> points){return points.stream().map(p->List.of(p.xMeters(),p.yMeters())).toList();}
    private static void writeNative(Path directory,String name,byte[] bytes,long[] total) throws Exception {
        if(!List.of("native-fixtures.json",STRONG+".f64",STRONG+".valid",FAINT+".f64",FAINT+".valid").contains(name)||bytes.length>65536)
            throw new IllegalArgumentException("Fixed native export leaf required");
        total[0]=Math.addExact(total[0],bytes.length);if(total[0]>2L*1024*1024*1024)throw new IllegalArgumentException("Synthetic disk cap");
        Files.write(directory.resolve(name),bytes);
    }
    record Fixture(String id,double amplitude,double[] scalar,boolean[] validity) {
        Fixture {scalar=scalar.clone();validity=validity.clone();}
        @Override public double[] scalar(){return scalar.clone();}
        @Override public boolean[] validity(){return validity.clone();}
        double[] scalarOwned(BSharedCaptureAdmission owner){return owner.rawScalarCopyOriginalUnit(scalar);}
        boolean[] validityOwned(BSharedCaptureAdmission owner){return owner.rawValidityCopyOriginalUnit(validity);}
    }
}
