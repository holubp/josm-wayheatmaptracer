package org.openstreetmap.josm.plugins.wayheatmaptracer.benchmark;

import java.awt.geom.Point2D;
import java.io.*;
import java.security.*;
import java.util.*;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ProfileSamplingAnchor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RidgeTracker;

/** Literal public fixtures and complete-bit comparison around the ordinary Legacy entry. */
public final class LegacyRuntimeProbe {
    private static final Map<String,String> INPUT_HASHES=Map.of(
        "parallel-185","8e2c0425a8efd9eb7733e6d19119f7ed4a8d9960e03719b5c230b764250e1448",
        "weak-gap-185","d765915b14beaf7548dc2aef9e569cd3c102343ee4f0ae017eaf0c1a14a45271",
        "switchback-512","c2aa8c1b901d6d43925d89c26aef9023781ea993ff91be1fe2f9fa2a6f36c280");
    private final List<CrossSectionProfile> profiles;
    private final String inputHash;
    private RidgeTracker tracker;
    private Object[] outputs;
    private String batchHash;
    private boolean kernelEntered;

    public LegacyRuntimeProbe(String fixture) throws Exception {
        int count=switch(fixture){case "parallel-185","weak-gap-185"->185;case "switchback-512"->512;default->throw new IllegalArgumentException("Unknown public fixture");};
        boolean turn=fixture.equals("switchback-512");var rows=new ArrayList<CrossSectionProfile>();double sourceDistance=0,previousX=0,previousY=0;
        for(int row=0;row<count;row++) {
            double theta=StrictMath.PI*row/(count-1),x=turn?240*StrictMath.sin(theta):row*3,
                y=turn?240*(1-StrictMath.cos(theta)):3*StrictMath.sin(row/17.0),nx=turn?-StrictMath.sin(theta):0,ny=turn?StrictMath.cos(theta):1;
            double center=turn?12*StrictMath.sin(2*StrictMath.PI*row/128):6*StrictMath.sin(row/13.0);
            if(row>0)sourceDistance+=StrictMath.hypot(x-previousX,y-previousY);previousX=x;previousY=y;
            boolean gap=fixture.equals("weak-gap-185")&&row%31>=23;
            var peaks=gap?List.<CrossSectionPeak>of():turn?List.of(peak(center,.9,.35)):List.of(peak(center,.8,.35),peak(center+18,.65,.3));
            var samples=new ArrayList<IntensitySample>();
            for(int offset=-40;offset<=40;offset+=2) {
                double intensity=gap?0:turn?.9*Math.max(0,1-Math.abs(offset-center)/5):
                    Math.max(.8*Math.max(0,1-Math.abs(offset-center)/5),.65*Math.max(0,1-Math.abs(offset-center-18)/5));
                samples.add(new IntensitySample(offset,intensity,intensity,intensity,true));
            }
            rows.add(new CrossSectionProfile(new ProfileSamplingAnchor(new EastNorth(x,y),x,y,sourceDistance),new Point2D.Double(nx,ny),peaks,true,samples));
        }
        profiles=List.copyOf(rows);inputHash=hash(List.of(1.0,profiles));
        if(!inputHash.equals(INPUT_HASHES.get(fixture)))throw new AssertionError("Frozen public input bits changed");
    }
    private static CrossSectionPeak peak(double offset,double intensity,double gradient) {
        return new CrossSectionPeak(offset,intensity,4,false,intensity-.1,.1,intensity,gradient,.8,1);
    }
    public String inputHash(){return inputHash;}
    public String currentInputHash() throws Exception{return hash(List.of(1.0,profiles));}
    public String origin(){return RidgeTracker.class.getProtectionDomain().getCodeSource().getLocation().toString();}
    public int rows(){return profiles.size();}
    public int peaks(){return profiles.stream().mapToInt(p->p.peaks().size()).sum();}
    public int candidates(){return ((List<?>)outputs[outputs.length-1]).size();}
    public long runBatch(int calls) {
        if(tracker==null)tracker=new RidgeTracker();outputs=new Object[calls];kernelEntered=true;
        long start=System.nanoTime();
        for(int i=0;i<calls;i++)outputs[i]=tracker.track(profiles,1.0);
        return System.nanoTime()-start;
    }
    public String outputHash() throws Exception {
        var hashes=new ArrayList<String>();String reference=null;
        for(Object value:outputs) {
            String current=hash(value);hashes.add(current);
            if(reference==null)reference=current;else if(!reference.equals(current))throw new AssertionError("Bits changed inside a batch");
        }
        batchHash=hash(hashes);return Objects.requireNonNull(reference);
    }
    public String batchHash(){return Objects.requireNonNull(batchHash);}
    public static void controls() throws Exception {
        if(hash(0.0).equals(hash(-0.0))||hash(1.0).equals(hash(Math.nextUp(1.0))))throw new AssertionError("Floating bits lost");
        if(hash(List.of(1,2)).equals(hash(List.of(2,1))))throw new AssertionError("Order lost");
        if(hash(OptionalDouble.empty()).equals(hash(OptionalDouble.of(0))))throw new AssertionError("Unknown became zero");
        int index=0;
        for(String fixture:List.of("parallel-185","weak-gap-185","switchback-512")) {
            var p=new LegacyRuntimeProbe(fixture);int count=index==2?512:185;
            if(p.rows()!=count||p.peaks()!=new int[]{370,276,512}[index++])throw new AssertionError("Public census changed");
            double previous=-1;
            for(var row:p.profiles) {
                if(row.cumulativeGroundDistanceMeters()<=previous||row.intensitySamples().size()!=41||!Double.isFinite(row.normalScreen().x)||!Double.isFinite(row.normalScreen().y))
                    throw new AssertionError("Public geometry/support changed");
                previous=row.cumulativeGroundDistanceMeters();
            }
            if(!p.inputHash().equals(p.currentInputHash())||p.kernelEntered)throw new AssertionError("Controls entered kernel or changed input");
            System.out.println("publicFixture="+fixture+" input="+p.inputHash()+" rows="+p.rows()+" peaks="+p.peaks()+" kernelEntered=false");
        }
        try{new LegacyRuntimeProbe("unknown");throw new AssertionError("External fixture accepted");}catch(IllegalArgumentException expected){/* literal-only factory */}
        System.out.println("PUBLIC_GENERATOR_SERIALIZATION_CONTROLS_PASS_NO_KERNEL");
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1||!args[0].equals("--controls"))throw new IllegalArgumentException("Controls only");controls();
    }
    private static String hash(Object value) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        try(var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))){write(out,value);}
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void write(DataOutputStream out,Object value) throws Exception {
        if(value==null){out.writeUTF("null");return;}Class<?> type=value.getClass();
        if(value instanceof Double v){out.writeUTF("double");out.writeLong(Double.doubleToRawLongBits(v));}
        else if(value instanceof Float v){out.writeUTF("float");out.writeInt(Float.floatToRawIntBits(v));}
        else if(value instanceof Number v){out.writeUTF(type.getName());out.writeUTF(v.toString());}
        else if(value instanceof Boolean v){out.writeUTF("boolean");out.writeBoolean(v);}
        else if(value instanceof String v){out.writeUTF("string");out.writeUTF(v);}
        else if(value instanceof Enum<?> v){out.writeUTF(type.getName());out.writeUTF(v.name());}
        else if(value instanceof Point2D p){out.writeUTF("Point2D");write(out,p.getX());write(out,p.getY());}
        else if(value instanceof EastNorth p){out.writeUTF("EastNorth");write(out,p.east());write(out,p.north());}
        else if(value instanceof List<?> list){out.writeUTF("list");out.writeInt(list.size());for(Object item:list)write(out,item);}
        else if(value instanceof Map<?,?> map) {
            out.writeUTF("map");out.writeInt(map.size());var entries=new ArrayList<>(map.entrySet());entries.sort(Comparator.comparing(e->e.getKey().toString()));
            for(var entry:entries){write(out,entry.getKey());write(out,entry.getValue());}
        }else if(value instanceof OptionalDouble v){out.writeUTF("OptionalDouble");out.writeBoolean(v.isPresent());if(v.isPresent())write(out,v.getAsDouble());}
        else if(value instanceof Optional<?> v){out.writeUTF("Optional");out.writeBoolean(v.isPresent());if(v.isPresent())write(out,v.get());}
        else if(type.isRecord()) {
            out.writeUTF(type.getName());var fields=type.getRecordComponents();out.writeInt(fields.length);
            for(var field:fields){out.writeUTF(field.getName());write(out,field.getAccessor().invoke(value));}
        }else throw new IllegalArgumentException("Unsupported complete-bit type");
    }
}
