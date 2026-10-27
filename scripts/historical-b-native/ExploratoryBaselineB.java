import java.nio.file.*;
import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.*;

/** Separate user-approved source/defaults reference; never old engine authority. */
public final class ExploratoryBaselineB {
    static final String SCOPE="EXPLORATORY_QUALITY_ONLY_V1";
    static final String COMMIT="117baaf09f4b81a38ad4679eedc8fd1b12ed3d2a";
    static final String DEFAULTS="4a6c463c009d9dc454741a8146de9f69c1e065b98f5026514a921c9b377a9d77";
    static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
    public static void main(String[] args)throws Exception{
        require("true".equals(System.getenv("CI"))&&"true".equals(System.getenv("V022_RUN_SHARED_FEASIBILITY")),"Explicit CI opt-in required");
        require(args.length==4,"neutral-directory case output-directory isolated-checker-classpath");
        var neutral=Path.of(args[0]);String id=args[1];var out=Path.of(args[2]);
        require(HistoricalNativeB.IDS.contains(id),"Literal public case required");
        var origin=Path.of(ProbabilisticTraceEngine.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        require(origin.getFileName().toString().equals("baseline-main"),"Baseline classes must be isolated from current classes");
        require(DEFAULTS.equals(HistoricalNativeB.identities().get("diagnosticDefaultsToStringSha256")),"Baseline defaults changed");
        Files.createDirectory(out);long start=System.nanoTime();
        var probe=new HistoricalNativeB.Probe(System::nanoTime,()->System.nanoTime()-start>=300_000_000_000L);
        var receipt=new TreeMap<String,Object>();receipt.put("evidenceClass",SCOPE);receipt.put("caseId",id);
        receipt.put("baselineSourceCommit",COMMIT);receipt.put("exactHistoricalTestedBinaryClaim",false);
        receipt.put("defaultsToStringSha256",DEFAULTS);receipt.put("routeReturned",false);receipt.put("status","INCONCLUSIVE");
        receipt.put("primarySelectionPolicy","FIRST_NATIVE_RETURNED_ALTERNATIVE");
        var ledger=AttemptMemoryLedger.production();int calls=0;
        try(var root=ledger.rootOwner();var sourceOwner=root.child("exploratory-baseline-source")){
            sourceOwner.reserve(32L<<20);root.reserve(2L<<20);
            var index=HistoricalNativeB.parse(HistoricalNativeB.read(neutral.resolve("index.json"),65536,probe),65536,4096,probe);
            var members=HistoricalNativeB.object(index.get("members"));byte[][] bytes=new byte[3][];
            String[] names={"scalar.f64","validity.bin","descriptor.json"};int[] limits={52488,6561,8192};
            for(int i=0;i<3;i++){
                String name=id+"/"+names[i];bytes[i]=HistoricalNativeB.read(neutral.resolve(name),limits[i],probe);
                require(HistoricalNativeB.hash(bytes[i]).equals(HistoricalNativeB.object(members.get(name)).get("sha256")),"Neutral member changed");
            }
            var input=HistoricalNativeB.decode(id,bytes[0],bytes[1],bytes[2],probe);
            var snapshot=HistoricalNativeB.capture(id,input,probe);
            var expected=HistoricalNativeB.object(index.get("captures")).get(id);
            require(HistoricalNativeB.json(HistoricalNativeB.captured(snapshot)).equals(HistoricalNativeB.json(expected)),"Old/current scalar, masks or frame differ");
            receipt.put("inputEquivalent",true);receipt.put("snapshotHash",snapshot.canonicalHash());
            receipt.put("original",HistoricalNativeB.coordinates(input.source()));
            var bound=HistoricalNativeB.bind(snapshot,input.source(),probe);
            receipt.put("nativeSource",HistoricalNativeB.coordinates(bound.nativeSource()));
            receipt.put("baselineRequest",bound.binding());
            var result=HistoricalNativeB.nativeCall(bound,snapshot,probe,root,out);calls=result.calls;
            receipt.put("nativeFacts",result.facts);
            for(var entry:result.routes.entrySet())Files.write(out.resolve(entry.getKey()),entry.getValue(),StandardOpenOption.CREATE_NEW);
            boolean completed=result.failure==null&&Boolean.TRUE.equals(result.facts.get("qualityComparisonAvailable"));
            if(completed){
                var primary=HistoricalNativeB.parse(result.routes.get("route-0.json"),65536,4096,probe);
                receipt.put("returned",primary.get("points"));receipt.put("routeReturned",true);
                receipt.put("status","MODEL_RETURNED_ROUTE");
            }
        }catch(Exception failure){receipt.remove("returned");receipt.put("routeReturned",false);receipt.put("failureClass",failure.getClass().getSimpleName());}
        receipt.put("solverInvocations",calls);receipt.put("elapsedNanos",System.nanoTime()-start);
        receipt.put("trackedBytesAfterClose",ledger.currentBytes());receipt.put("runtimeQualified",false);
        HistoricalNativeB.publish(out.resolve("receipt.json"),receipt);
        // The old engine has returned and closed before current checker classes
        // are loaded. Parent=platform prevents old/current application shadowing.
        // Both phases share this JVM's original monotonic300s clock and the one
        // external360s watchdog; no fresh checker process/deadline is issued.
        var paths=args[3].split(java.io.File.pathSeparator);var urls=new java.net.URL[paths.length];
        for(int i=0;i<paths.length;i++)urls[i]=Path.of(paths[i]).toUri().toURL();
        try(var loader=new java.net.URLClassLoader(urls,ClassLoader.getPlatformClassLoader())){
            var checker=Class.forName("org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BSharedFeasibilityTool",true,loader);
            var method=checker.getMethod("checkBaselineSince",String.class,Path.class,Path.class,long.class);
            method.invoke(null,id,neutral,out,start);
        }
    }
}
