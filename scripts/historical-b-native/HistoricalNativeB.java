import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

/**
 * External source-isolated historical scalar recapture; deliberately has no engine mode.
 * Compile only against literal117baaf main classes and its declared dependency JAR.
 * Input ZIP handling belongs to the earlier, exited preparation process.
 */
public final class HistoricalNativeB {
    static final String HISTORICAL="117baaf09f4b81a38ad4679eedc8fd1b12ed3d2a";
    static final String NATIVE_SHA="34c0c300332f3d1eb5701eecf9b980a64c94940188d8fc5e6c221b5e4609639c";
    static final String SCOPE="CAPTURE_ONLY_PREPARATION_NOT_ENGINE_ELIGIBLE";
    static final long COLD=12L*1024*1024,CAPTURE=20L*1024*1024,REPORT=2L*1024*1024;
    // Exact old census946 entries/depth9/path156 bytes; round up fixed metadata bounds.
    static final int MAX_TREE_ENTRIES=1024,MAX_TREE_DEPTH=16,MAX_TREE_PATH_BYTES=256;
    static final List<String> IDS=List.of("strong-parallel-gap","connected-straight","connected-short-bend","connected-sharp-apex",
        "faint-six-metre-bend","faint-twelve-metre-bend","genuine-junction","positive-twenty-five-representation",
        "negative-twenty-five-representation","flecks-versus-faint-coherent","valid-sparse-gap","invalid-unknown-gap",
        "censored-flank","strong-modulated-versus-quiet-faint","unknown-detour","uniform-null","alternating-null",
        "half-pitch-alias","quarter-phase-alias","reverse-original-mesh","thin-three-strands","thin-three-strands-intermittent",
        "thin-strands-merge-separate","drifting-strong-with-faint-original","thin-strong-sharp-bend","thin-faint-coherent-sharp-bend",
        "thin-local-overlap-noise","same-trail-three-strong","same-trail-five-strong","switchback-thin-strong",
        "switchback-thin-faint","same-trail-three-separated-strong");
    static final Set<String> DESCRIPTOR=Set.of("schema","id","width","height","origin","pitchMeters","source","frame","outputGrid","capture","inputRasterOrigin");
    static final StackWalker WALKER=StackWalker.getInstance();

    /** Fixed bounded watchdog and no-numerical-entry observer, shared by parsing and old capture. */
    static final class Probe implements CancellationProbe {
        final long started=System.nanoTime(); long checkpoints; long cancelAt=Long.MAX_VALUE;
        public boolean cancelled(){
            checkpoints++;
            boolean forbidden=WALKER.walk(frames->frames.anyMatch(f->
                f.getClassName().endsWith(".ProbabilisticProfileFactory")||f.getClassName().endsWith(".ProbabilisticInference")
                ||f.getClassName().endsWith(".ProbabilisticObservationModel")||f.getClassName().endsWith(".ProbabilisticTraceEngine")));
            if(forbidden)throw new SecurityException("Numerical entry in CAPTURE_ONLY");
            return checkpoints>=cancelAt||System.nanoTime()-started>=300_000_000_000L;
        }
    }
    static void require(boolean condition,String safe){if(!condition)throw new SecurityException(safe);}
    static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static byte[] read(Path path,int limit,Probe probe)throws Exception {
        probe.checkpoint();require(!Files.isSymbolicLink(path)&&Files.isRegularFile(path)&&Files.size(path)>0&&Files.size(path)<=limit,"Bounded regular input");
        try(var in=Files.newInputStream(path)){byte[] bytes=in.readNBytes(limit+1);require(bytes.length>0&&bytes.length<=limit,"Bounded input bytes");return bytes;}
    }
    static String fileHash(Path path,Probe probe)throws Exception {
        require(!Files.isSymbolicLink(path)&&Files.isRegularFile(path),"Classpath regular file");
        var digest=MessageDigest.getInstance("SHA-256");byte[] page=new byte[8192];
        try(var stream=Files.newInputStream(path)){int n;long total=0;while((n=stream.read(page))!=-1){probe.checkpoint();total+=n;require(total<=64L*1024*1024,"Classpath file bound");digest.update(page,0,n);}}
        return HexFormat.of().formatHex(digest.digest());
    }
    static String treeHash(Path root,Probe probe)throws Exception {
        require(!Files.isSymbolicLink(root)&&Files.isDirectory(root),"Classpath directory");
        var paths=new ArrayList<Path>();
        Files.walkFileTree(root,EnumSet.noneOf(FileVisitOption.class),MAX_TREE_DEPTH+1,new SimpleFileVisitor<Path>() {
            int entries;long bytes;
            void check(Path path){
                probe.checkpoint();if(path.equals(root))return;
                require(++entries<=MAX_TREE_ENTRIES,"Classpath tree entry bound");
                Path relative=root.relativize(path);require(relative.getNameCount()<=MAX_TREE_DEPTH,"Classpath tree depth");
                require(relative.toString().replace(File.separatorChar,'/').getBytes(StandardCharsets.UTF_8).length<=MAX_TREE_PATH_BYTES,"Classpath tree path bytes");
                require(!Files.isSymbolicLink(path),"Classpath link");
            }
            @Override public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attributes){check(path);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attributes){
                check(path);require(attributes.isRegularFile(),"Classpath regular file");bytes=Math.addExact(bytes,attributes.size());
                require(attributes.size()>0&&attributes.size()<=2L*1024*1024&&bytes<=64L*1024*1024,"Classpath tree bound");
                paths.add(path);return FileVisitResult.CONTINUE;
            }
        });
        paths.sort(Comparator.comparing(p->root.relativize(p).toString().replace(File.separatorChar,'/')));
        var digest=MessageDigest.getInstance("SHA-256");long total=0;
        for(var p:paths){probe.checkpoint();total+=Files.size(p);require(Files.size(p)<=2L*1024*1024&&total<=64L*1024*1024,"Classpath tree bound");
            digest.update(root.relativize(p).toString().replace(File.separatorChar,'/').getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
            digest.update(fileHash(p,probe).getBytes(StandardCharsets.US_ASCII));digest.update((byte)'\n');}
        require(!paths.isEmpty(),"Empty classpath tree");return HexFormat.of().formatHex(digest.digest());
    }
    static Map<String,Object> object(Object value){require(value instanceof Map<?,?>,"Metadata object");return (Map<String,Object>)value;}
    static List<?> list(Object value,int size){require(value instanceof List<?> && ((List<?>)value).size()==size,"Metadata list");return (List<?>)value;}
    static String text(Object value){require(value instanceof String,"Metadata string");return (String)value;}
    static double number(Object value){require(value instanceof Number&&Double.isFinite(((Number)value).doubleValue()),"Finite metadata number");return ((Number)value).doubleValue();}
    static int integer(Object value){double n=number(value);require(n>=2&&n<=25921&&n==(int)n,"Metadata dimension");return (int)n;}
    static void keys(Map<?,?> value,Set<String> expected){require(value.keySet().equals(expected),"Closed metadata schema");}
    static MetricPoint metric(Object value){var p=list(value,2);return new MetricPoint(number(p.get(0)),number(p.get(1)));}
    static GeographicPoint geographic(Object value){var p=list(value,2);return new GeographicPoint(number(p.get(0)),number(p.get(1)));}
    static List<Double> point(MetricPoint p){return List.of(p.xMeters(),p.yMeters());}
    static List<String> bits(double x,double y){return List.of(String.format(Locale.ROOT,"%016x",Double.doubleToRawLongBits(x)),String.format(Locale.ROOT,"%016x",Double.doubleToRawLongBits(y)));}

    /** Bounded standalone parser, with strict UTF8, duplicate keys, depth/nodes/tokens. */
    static final class Json {
        final String input;final int maximumNodes;final Probe probe;int offset,nodes;
        Json(byte[] bytes,int byteLimit,int nodeLimit,Probe probe)throws Exception {
            require(bytes.length>0&&bytes.length<=byteLimit,"JSON byte bound");
            input=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            maximumNodes=nodeLimit;this.probe=probe;
        }
        void space(){while(offset<input.length()&&" \t\r\n".indexOf(input.charAt(offset))>=0){if((offset&1023)==0)probe.checkpoint();offset++;}}
        char take(){require(offset<input.length(),"JSON truncated");if((offset&1023)==0)probe.checkpoint();return input.charAt(offset++);}
        String string(){require(take()=='"',"JSON string");var out=new StringBuilder();boolean end=false;
            while(offset<input.length()){char c=take();if(c=='"'){end=true;break;}require(c>=32,"JSON control");
                if(c=='\\'){c=take();switch(c){case '"','\\','/'->out.append(c);case 'b'->out.append('\b');case 'f'->out.append('\f');case 'n'->out.append('\n');case 'r'->out.append('\r');case 't'->out.append('\t');case 'u'->{require(offset+4<=input.length(),"JSON unicode");out.append((char)Integer.parseInt(input.substring(offset,offset+4),16));offset+=4;}default->throw new SecurityException("JSON escape");}}
                else out.append(c);require(out.length()<=256,"JSON string bound");}
            require(end,"JSON unterminated string");return out.toString();}
        Object value(int depth){require(depth<=8&&++nodes<=maximumNodes,"JSON depth/node bound");space();require(offset<input.length(),"JSON value");char c=input.charAt(offset);
            if(c=='{'){offset++;var map=new LinkedHashMap<String,Object>();space();if(offset<input.length()&&input.charAt(offset)=='}'){offset++;return map;}
                while(true){space();require(++nodes<=maximumNodes,"JSON node bound");String key=string();require(!map.containsKey(key),"JSON duplicate key");space();require(take()==':',"JSON colon");map.put(key,value(depth+1));space();c=take();if(c=='}')return map;require(c==',',"JSON comma");}}
            if(c=='['){offset++;var values=new ArrayList<Object>();space();if(offset<input.length()&&input.charAt(offset)==']'){offset++;return values;}while(true){values.add(value(depth+1));space();c=take();if(c==']')return values;require(c==',',"JSON comma");}}
            if(c=='"')return string();
            for(String token:List.of("true","false","null"))if(input.startsWith(token,offset)){offset+=token.length();return token.equals("null")?null:Boolean.valueOf(token);}
            int start=offset;while(offset<input.length()&&",]} \t\r\n".indexOf(input.charAt(offset))<0){offset++;require(offset-start<=64,"JSON numeric bound");}
            String token=input.substring(start,offset);require(token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"),"JSON number grammar");
            if(token.contains(".")||token.contains("e")||token.contains("E")){double n=Double.parseDouble(token);require(Double.isFinite(n),"JSON finite number");return n;}return Long.valueOf(token);
        }
        Map<String,Object> parse(){Object result=value(0);space();require(offset==input.length(),"JSON trailing content");return object(result);}
    }
    static Map<String,Object> parse(byte[] raw,int limit,int nodes,Probe probe)throws Exception{return new Json(raw,limit,nodes,probe).parse();}
    static String json(Object value){
        if(value==null)return "null";
        if(value instanceof String s){var b=new StringBuilder("\"");for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='"'||c=='\\')b.append('\\').append(c);else if(c<32)b.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else b.append(c);}return b.append('"').toString();}
        if(value instanceof Map<?,?> m){var keys=new TreeSet<String>();for(Object key:m.keySet())keys.add(text(key));var items=new ArrayList<String>();for(String key:keys)items.add(json(key)+":"+json(m.get(key)));return "{"+String.join(",",items)+"}";}
        if(value instanceof List<?> values){var items=new ArrayList<String>();for(Object item:values)items.add(json(item));return "["+String.join(",",items)+"]";}
        require(value instanceof Boolean||value instanceof Number,"JSON output type");if(value instanceof Number)number(value);return value.toString();
    }
    static Map<String,Object> pinned(Path path,String sha,Probe probe)throws Exception {
        require(sha.matches("[0-9a-f]{64}"),"External digest syntax");byte[] raw=read(path,65536,probe);require(hash(raw).equals(sha),"External metadata digest");return parse(raw,65536,4096,probe);
    }
    static Map<String,Object> attest(Path path,String expected,Probe probe)throws Exception {
        var attestation=pinned(path,expected,probe);var source=object(attestation.get("source"));require(HISTORICAL.equals(source.get("commit")),"Historical source identity");
        Path main=Path.of(text(attestation.get("mainClasses"))).toRealPath(),adapter=Path.of(text(attestation.get("adapterClasses"))).toRealPath();
        require(treeHash(main,probe).equals(attestation.get("mainSha256"))&&treeHash(adapter,probe).equals(attestation.get("adapterSha256")),"Historical class bytes");
        var entries=new ArrayList<String>();entries.add(adapter.toString());
        for(Object item:(List<?>)attestation.get("runtimeEntries"))entries.add(text(item));
        require(Arrays.asList(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))).equals(entries),"Historical exact classpath");
        for(Object item:(List<?>)attestation.get("dependencies")){var dependency=object(item);require(fileHash(Path.of(text(dependency.get("path"))),probe).equals(dependency.get("sha256")),"Historical dependency bytes");}
        String resources=text(attestation.get("resources"));if(!resources.isEmpty())require(treeHash(Path.of(resources),probe).equals(attestation.get("resourcesSha256")),"Historical resources");
        var origins=new TreeMap<String,Object>();
        for(Class<?> type:List.of(RasterEvidenceCapture.class,ScalarEvidenceField.class,EvidenceSnapshot.class,LocalMetricFrame.class,MetricRasterGrid.class,
                SupportedInputRasterTransform.class,EvidenceResolution.class,EvidenceFieldLineage.class,RasterResamplingProvenance.class,AttemptMemoryLedger.class,EvidenceModelParameters.class)){
            probe.checkpoint();Path origin=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();require(origin.equals(main),"Historical class origin");
            var urls=Collections.list(ClassLoader.getSystemResources(type.getName().replace('.','/')+".class"));require(urls.size()==1,"Duplicate historical class");
            origins.put(type.getName(),Map.of("codeSource",origin.toString(),"classSha256",fileHash(main.resolve(type.getName().replace('.','/')+".class"),probe)));}
        require(Path.of(HistoricalNativeB.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(adapter),"Adapter origin");
        return origins;
    }
    static Set<String> names(){var names=new TreeSet<String>();names.add("index.json");for(String id:IDS)for(String leaf:List.of("scalar.f64","validity.bin","descriptor.json"))names.add(id+"/"+leaf);return names;}
    static void inventory(Path root,Probe probe)throws Exception {
        require(!Files.isSymbolicLink(root)&&Files.isDirectory(root),"Prepared directory");var actual=new TreeSet<String>();
        try(var paths=Files.walk(root)){var it=paths.iterator();while(it.hasNext()){var p=it.next();probe.checkpoint();require(!Files.isSymbolicLink(p),"Prepared link");String name=root.relativize(p).toString().replace(File.separatorChar,'/');
            if(Files.isDirectory(p)){require(p.equals(root)||IDS.contains(name),"Prepared directory inventory");}
            else{require(Files.isRegularFile(p)&&names().contains(name)&&actual.add(name),"Prepared exact file inventory");require(actual.size()<=97,"Prepared inventory bound");}}}
        require(actual.equals(names()),"Prepared exact97 inventory");
    }
    record Input(double[] values,boolean[] validity,LocalMetricFrame frame,MetricRasterGrid grid,List<MetricPoint> source,RasterPoint inputRasterOrigin) { }
    static Input decode(String id,byte[] scalar,byte[] mask,byte[] descriptor,Probe probe)throws Exception {
        require(scalar.length==52488&&mask.length==6561,"Raw source lengths");
        // Validate the complete byte payload before allocating decoded arrays/capture objects.
        var reader=ByteBuffer.wrap(scalar).order(ByteOrder.BIG_ENDIAN);
        for(int i=0;i<6561;i++){if((i&255)==0)probe.checkpoint();double value=reader.getDouble();require((mask[i]==0||mask[i]==1)&&Double.isFinite(value)&&value>=0&&value<=1,"Raw scalar/validity value");}
        var d=parse(descriptor,8192,256,probe);keys(d,DESCRIPTOR);require("neutral-native-public32-v1".equals(d.get("schema"))&&id.equals(d.get("id"))&&number(d.get("width"))==81&&number(d.get("height"))==81&&number(d.get("pitchMeters"))==1,"Descriptor source identity");
        var capture=object(d.get("capture"));keys(capture,Set.of("snapshotId","sourceIdentity","selectedField","palette","acquisition","derivation","correlationGroup","nativePitchMeters","renderedPitchMeters","searchHalfWidthMeters"));
        require(("matched-"+id).equals(capture.get("snapshotId"))&&("matched-public-declared-"+id).equals(capture.get("sourceIdentity"))&&"scalar".equals(capture.get("selectedField"))
            &&"matched-declared-scalar".equals(capture.get("palette"))&&"SYNTHETIC".equals(capture.get("acquisition"))&&"DIRECT_INTENSITY".equals(capture.get("derivation"))
            &&"SYNTHETIC_TRUTH".equals(capture.get("correlationGroup"))&&number(capture.get("nativePitchMeters"))==1&&number(capture.get("renderedPitchMeters"))==1&&number(capture.get("searchHalfWidthMeters"))==25,"Descriptor capture lineage");
        var f=object(d.get("frame"));keys(f,Set.of("projectionId","method","origin","southWest","northEast","bound","eastMetersPerRadian","northMetersPerRadian"));
        var frame=LocalMetricFrame.restoreCertified(text(f.get("projectionId")),text(f.get("method")),geographic(f.get("origin")),geographic(f.get("southWest")),geographic(f.get("northEast")),number(f.get("bound")),number(f.get("eastMetersPerRadian")),number(f.get("northMetersPerRadian")));
        var g=object(d.get("outputGrid"));keys(g,Set.of("origin","axes","pitchMeters","width","height"));var a=list(g.get("axes"),4);int width=integer(g.get("width")),height=integer(g.get("height"));
        require((long)width*height<=25921,"Output pixel bound");
        var grid=new MetricRasterGrid(frame,metric(g.get("origin")),number(a.get(0)),number(a.get(1)),number(a.get(2)),number(a.get(3)),number(g.get("pitchMeters")),width,height);
        require(d.get("source") instanceof List<?>,"Source array");var raw=(List<?>)d.get("source");require(raw.size()>=2&&raw.size()<=12,"Source point bound");var source=new ArrayList<MetricPoint>();for(Object p:raw){probe.checkpoint();source.add(metric(p));}
        var raster=list(d.get("inputRasterOrigin"),2);var original=metric(d.get("origin"));
        require(number(raster.get(0))==-original.xMeters()&&number(raster.get(1))==-original.yMeters(),"Input affine/source origin agreement");
        double[] values=new double[6561];boolean[] validity=new boolean[6561];reader.rewind();for(int i=0;i<values.length;i++){if((i&255)==0)probe.checkpoint();values[i]=reader.getDouble();validity[i]=mask[i]==1;}
        return new Input(values,validity,frame,grid,List.copyOf(source),new RasterPoint(number(raster.get(0)),number(raster.get(1))));
    }
    static EvidenceSnapshot capture(String id,Input input,CancellationProbe probe){
        var inverse=SupportedInputRasterTransform.localMetricAffine(input.frame(),input.inputRasterOrigin(),1,0,0,1);
        var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"matched-declared-scalar",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
        return new RasterEvidenceCapture(CAPTURE).captureWithMetricScalarSource("matched-"+id,new BufferedImage(81,81,BufferedImage.TYPE_INT_ARGB),input.validity(),input.values(),
            input.source().stream().map(input.frame()::toGeographic).toList(),input.source(),inverse,input.grid(),EvidenceResolution.nativeSource(1,1),25,
            "matched-public-declared-"+id,EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,"scalar",lineage,probe);
    }
    static String doublesHash(double[] values)throws Exception {var b=ByteBuffer.allocate(values.length*8).order(ByteOrder.BIG_ENDIAN);for(double value:values)b.putDouble(value);return hash(b.array());}
    static String maskHash(boolean[] values)throws Exception {byte[] b=new byte[values.length];for(int i=0;i<b.length;i++)b[i]=(byte)(values[i]?1:0);return hash(b);}
    static Map<String,Object> captured(EvidenceSnapshot snapshot)throws Exception {
        var field=snapshot.fields().get("scalar");return Map.of("snapshotHash",snapshot.canonicalHash(),"scalarSha256",doublesHash(field.copiedValues()),
            "vertexMaskSha256",maskHash(field.copiedValidity()),"interpolationMaskSha256",maskHash(field.copiedInterpolationValidity()),"width",field.width(),"height",field.height());
    }
    static String prefixed(String... values)throws Exception {var digest=MessageDigest.getInstance("SHA-256");for(String value:values){byte[] raw=value.getBytes(StandardCharsets.UTF_8);digest.update(Integer.toString(raw.length).getBytes(StandardCharsets.US_ASCII));digest.update((byte)':');digest.update(raw);}return HexFormat.of().formatHex(digest.digest());}
    static Map<String,Object> identities()throws Exception {
        var defaults=EvidenceModelParameters.defaults();String identity=defaults.version()+"/DIRECT_LONGITUDINAL_V2/"+ModernNumericalPolicy.SHARED_COST;
        return Map.of("scope","METADATA_ONLY_NOT_A_REQUEST_OR_ENGINE_EXECUTION","defaultsToString",defaults.toString(),"diagnosticDefaultsToStringSha256",hash(defaults.toString().getBytes(StandardCharsets.UTF_8)),
            "liveParameterIdentity",identity,"visibleLiveParameterHash",prefixed("live-probabilistic-v2",identity),"managedLiveParameterHash",prefixed("managed-live-probabilistic-v2",identity));
    }
    static void publish(Path path,Map<String,Object> value)throws Exception {
        byte[] bytes=(json(value)+"\n").getBytes(StandardCharsets.UTF_8);require(bytes.length<=65536,"Receipt leaf bound");
        Path parent=path.getParent();require(parent!=null&&!Files.isSymbolicLink(parent)&&Files.isDirectory(parent),"Receipt directory");long total=0;
        try(var files=Files.list(parent)){var it=files.iterator();while(it.hasNext()){var p=it.next();require(!Files.isSymbolicLink(p)&&Files.isRegularFile(p),"Receipt sibling inventory");total=Math.addExact(total,Files.size(p));}}
        require(total<=2L*1024*1024*1024-bytes.length,"Receipt aggregate bound");Files.write(path,bytes,StandardOpenOption.CREATE_NEW);
    }
    static Map<String,Object> selfTest()throws Exception {
        var probe=new Probe();int checks=0;
        try{parse("{\"x\":1,\"x\":2}".getBytes(StandardCharsets.UTF_8),8192,256,probe);throw new AssertionError("Duplicate accepted");}catch(SecurityException expected){checks++;}
        var ledger=new AttemptMemoryLedger(COLD+CAPTURE+REPORT-1);
        try(var root=ledger.rootOwner();var report=root.reserve(REPORT)){
            try(var source=root.child("refusal")){source.reserve(COLD);try{source.reserve(CAPTURE);throw new AssertionError("Unfunded capture accepted");}catch(AttemptMemoryLedger.ResourceLimitException expected){checks++;}}
            require(ledger.currentBytes()==REPORT,"Refusal child cleanup");
        }require(ledger.currentBytes()==0,"Refusal root cleanup");
        var cancel=new Probe();cancel.cancelAt=1;var cancelled=AttemptMemoryLedger.production();
        try(var root=cancelled.rootOwner();var report=root.reserve(REPORT);var source=root.child("cancelled")){source.reserve(COLD);source.reserve(CAPTURE);
            try{cancel.checkpoint();throw new AssertionError("Cancellation accepted");}catch(CancellationException expected){checks++;}}
        require(cancelled.currentBytes()==0,"Cancellation root cleanup");
        byte[] raw=new byte[52488],mask=new byte[6561];mask[0]=2;
        try{decode(IDS.get(0),raw,mask,new byte[]{'{','}'},probe);throw new AssertionError("Invalid validity accepted");}catch(SecurityException expected){require(expected.getMessage().equals("Raw scalar/validity value"),"Mask predecode failure");checks++;}
        mask[0]=0;ByteBuffer.wrap(raw).putDouble(Double.NaN);
        try{decode(IDS.get(0),raw,mask,new byte[]{'{','}'},probe);throw new AssertionError("Invalid hidden scalar accepted");}catch(SecurityException expected){require(expected.getMessage().equals("Raw scalar/validity value"),"Scalar predecode failure");checks++;}
        int tinyCalls=0,tinyReturns=0;var tinyLedger=AttemptMemoryLedger.production();
        try(var root=tinyLedger.rootOwner();var report=root.reserve(REPORT);var source=root.child("tiny-old-capture")){
            source.reserve(COLD);source.reserve(CAPTURE);
            var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-.01,-.01),new GeographicPoint(.01,.01));
            var grid=new MetricRasterGrid(frame,new MetricPoint(0,0),1,0,0,1,1,17,17);
            var metric=List.of(new MetricPoint(5,8),new MetricPoint(11,8));double[] scalar=new double[289];Arrays.fill(scalar,.5);boolean[] valid=new boolean[289];Arrays.fill(valid,true);
            var inverse=SupportedInputRasterTransform.localMetricAffine(frame,new RasterPoint(0,0),1,0,0,1);
            var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"scalar",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
            tinyCalls++;
            var snapshot=new RasterEvidenceCapture(CAPTURE).captureWithMetricScalarSource("tiny-capture-only",new BufferedImage(17,17,BufferedImage.TYPE_INT_ARGB),valid,scalar,
                metric.stream().map(frame::toGeographic).toList(),metric,inverse,grid,EvidenceResolution.nativeSource(1,1),2,"tiny-capture-only",EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,"scalar",lineage,probe);tinyReturns++;
            require(snapshot.fields().get("scalar").width()==17&&snapshot.fields().get("scalar").sample(8,8).orElseThrow()==.5,"Tiny capture scalar");captured(snapshot);checks++;
            tinyCalls++;
            try{new RasterEvidenceCapture(CAPTURE).captureWithMetricScalarSource("tiny-cancel",new BufferedImage(17,17,BufferedImage.TYPE_INT_ARGB),valid,scalar,
                metric.stream().map(frame::toGeographic).toList(),metric,inverse,grid,EvidenceResolution.nativeSource(1,1),2,"tiny-cancel",EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,"scalar",lineage,()->true);throw new AssertionError("Old capture ignored cancellation");}
            catch(CancellationException expected){checks++;}
        }require(tinyLedger.currentBytes()==0,"Tiny capture owner cleanup");
        return Map.of("checks",checks,"passed",checks==7,"engineInvocations",0,"refusedCaptureInvocations",0,"tinyCaptureInvocations",tinyCalls,"tinyCaptureReturns",tinyReturns,"finalRootBytes",0,"tinyPeakBytes",tinyLedger.peakBytes());
    }
    public static void main(String[] args)throws Exception {
        if(args.length==1&&args[0].equals("SELF_TEST")){System.out.println(json(selfTest()));return;}
        require(args.length==9&&(args[0].equals("CAPTURE_ONLY")||args[0].equals("SELF_TEST")),"Fixed CAPTURE_ONLY arguments");
        var probe=new Probe();var ledger=AttemptMemoryLedger.production();Map<String,Object> receipt=new TreeMap<>();String phase="SOURCE_ATTESTATION";int calls=0,returns=0;Exception failure=null;
        try(var root=ledger.rootOwner();var report=root.reserve(REPORT)){
            try(var source=root.child("neutral-source-and-capture")){source.reserve(COLD);source.reserve(CAPTURE);
                var origins=attest(Path.of(args[1]),args[2],probe);phase="EXTERNAL_INPUT_TUPLE";
                var protocol=pinned(Path.of(args[3]),args[4],probe);keys(protocol,Set.of("scope","historicalCommit","archiveSha256","indexSha256","nativeContractSha256","cases","members","captures","sourceCommit"));
                require(SCOPE.equals(protocol.get("scope"))&&HISTORICAL.equals(protocol.get("historicalCommit"))&&IDS.equals(protocol.get("cases"))&&NATIVE_SHA.equals(protocol.get("nativeContractSha256")),"Protocol purpose/source/cases");
                var nativeInputs=pinned(Path.of(args[6]),NATIVE_SHA,probe);require(IDS.equals(nativeInputs.get("cases")),"Original frozen case identity");
                String id=args[7];require(IDS.contains(id),"Fixed public case");Path inputRoot=Path.of(args[5]);inventory(inputRoot,probe);
                var index=pinned(inputRoot.resolve("index.json"),text(protocol.get("indexSha256")),probe);
                keys(index,Set.of("schema","cases","members","captures","preparation"));require("neutral-native-public32-v1".equals(index.get("schema"))&&IDS.equals(index.get("cases"))
                    &&json(index.get("members")).equals(json(protocol.get("members")))&&json(index.get("captures")).equals(json(protocol.get("captures"))),"External index/member/capture binding");
                var members=object(protocol.get("members"));var memberNames=new TreeSet<>(names());memberNames.remove("index.json");require(members.keySet().equals(memberNames),"External96 member inventory");
                byte[][] bytes=new byte[3][];String[] leaves={"scalar.f64","validity.bin","descriptor.json"};int[] limits={52488,6561,8192};
                for(int i=0;i<3;i++){var binding=object(members.get(id+"/"+leaves[i]));keys(binding,Set.of("bytes","sha256"));bytes[i]=read(inputRoot.resolve(id).resolve(leaves[i]),limits[i],probe);
                    require(number(binding.get("bytes"))==bytes[i].length&&hash(bytes[i]).equals(binding.get("sha256")),"External selected member binding");}
                var original=object(object(nativeInputs.get("nativeInputs")).get(id));require(hash(bytes[0]).equals(original.get("rasterSha256"))&&hash(bytes[1]).equals(original.get("validitySha256")),"Original frozen raw source");
                phase="DECODE";var input=decode(id,bytes[0],bytes[1],bytes[2],probe);
                receipt.put("scope",SCOPE);receipt.put("case",id);receipt.put("historicalSource",HISTORICAL);receipt.put("protocolSha256",args[4]);receipt.put("archiveSha256",protocol.get("archiveSha256"));receipt.put("indexSha256",protocol.get("indexSha256"));receipt.put("nativeContractSha256",NATIVE_SHA);receipt.put("classOrigins",origins);receipt.put("identities",identities());
                if(args[0].equals("SELF_TEST")){receipt.put("selfTest",selfTest());}
                else{phase="CAPTURE";calls++;var snapshot=capture(id,input,probe);returns++;phase="CAPTURE_HASHES";var facts=captured(snapshot);
                    require(json(facts).equals(json(object(protocol.get("captures")).get(id))),"Historical resampled snapshot/scalar/masks changed");
                    require(facts.get("snapshotHash").equals(original.get("snapshotHash"))&&number(facts.get("width"))==number(original.get("outputWidth"))&&number(facts.get("height"))==number(original.get("outputHeight")),"Original frozen output identity");
                    receipt.put("captured",facts);var originalPoints=new ArrayList<Object>();var geographicPoints=new ArrayList<Object>();var nativePoints=new ArrayList<Object>();
                    var originalBits=new ArrayList<Object>();var geographicBits=new ArrayList<Object>();var nativeBits=new ArrayList<Object>();double maxRoundtrip=0;
                    for(var p:input.source()){originalPoints.add(point(p));var geo=input.frame().toGeographic(p);var nativePoint=input.frame().toMetric(geo);geographicPoints.add(List.of(geo.latitudeDegrees(),geo.longitudeDegrees()));nativePoints.add(point(nativePoint));
                        originalBits.add(bits(p.xMeters(),p.yMeters()));geographicBits.add(bits(geo.latitudeDegrees(),geo.longitudeDegrees()));nativeBits.add(bits(nativePoint.xMeters(),nativePoint.yMeters()));
                        maxRoundtrip=Math.max(maxRoundtrip,StrictMath.hypot(nativePoint.xMeters()-p.xMeters(),nativePoint.yMeters()-p.yMeters()));}
                    receipt.put("originalMetricSource",originalPoints);receipt.put("geographicSource",geographicPoints);receipt.put("nativeRoundtripSource",nativePoints);
                    receipt.put("originalMetricSourceRawBits",originalBits);receipt.put("geographicSourceRawBits",geographicBits);receipt.put("nativeRoundtripSourceRawBits",nativeBits);receipt.put("maximumSourceRoundtripMeters",maxRoundtrip);
                }
            }catch(Exception error){failure=error;}
            receipt.put("phase",failure==null?"CAPTURE_COMPLETE":phase);receipt.put("status",failure==null?"COMPLETE_CAPTURE_ONLY":"REFUSED_CAPTURE_ONLY");receipt.put("cause",failure==null?null:failure instanceof CancellationException?"CANCELLED":failure instanceof AttemptMemoryLedger.ResourceLimitException?"RESOURCE_LIMIT":"INPUT_OR_BINDING_REFUSAL");
            receipt.put("captureInvocations",calls);receipt.put("captureReturns",returns);receipt.put("engineInvocations",0);receipt.put("checkpointCalls",probe.checkpoints);receipt.put("ledgerLimitBytes",ledger.limitBytes());receipt.put("ledgerPeakBytes",ledger.peakBytes());
            receipt.put("sourceBytesAfterClose",ledger.currentBytes()-REPORT);receipt.put("publicationBytesLive",ledger.currentBytes());require(ledger.currentBytes()==REPORT,"Source owner cleanup");
            publish(Path.of(args[8]),receipt);
        }
        require(ledger.currentBytes()==0,"Final root ownership");System.out.println(json(Map.of("scope",SCOPE,"finalRootBytes",ledger.currentBytes(),"ledgerPeakBytes",ledger.peakBytes(),"captureInvocations",calls,"captureReturns",returns,"engineInvocations",0)));
        if(failure!=null)throw new SecurityException("Capture-only attempt refused",failure);
    }
}
