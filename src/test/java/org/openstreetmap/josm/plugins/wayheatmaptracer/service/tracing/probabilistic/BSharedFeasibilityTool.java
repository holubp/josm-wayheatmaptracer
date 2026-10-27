package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.nio.file.*;
import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Fixed public experiment CLI; original-case execution belongs exclusively to CI. */
public final class BSharedFeasibilityTool {
    private BSharedFeasibilityTool(){}
    static void requireCi(){
        if(!"true".equals(System.getenv("CI"))||!"true".equals(System.getenv("V022_RUN_SHARED_FEASIBILITY")))
            throw new IllegalStateException("Explicit public feasibility CI opt-in required");
    }
    public static void main(String[] args)throws Exception{
        if(args.length==0||!Set.of("export","candidate","check-baseline").contains(args[0]))
            throw new IllegalArgumentException("Expected export, candidate or check-baseline");
        requireCi();
        switch(args[0]){
            case "export" -> {if(args.length!=2)throw new IllegalArgumentException("export directory");
                BNativeNeutralFixtureExporter.export(Path.of(args[1]));}
            case "candidate" -> {if(args.length!=5)throw new IllegalArgumentException("candidate case gain eta output");
                var settings=new BSharedFeasibilityComparison.Settings(Integer.parseInt(args[2]),Double.parseDouble(args[3]));
                var receipt=BSharedFeasibilityComparison.execute(new BSharedFeasibilityComparison.Request(args[1],settings,Path.of(args[4])),System::nanoTime,CancellationProbe.NONE);
                System.out.println(BIdentificationReceipt.canonical(Map.of("status",receipt.get("status"),"caseId",args[1])));}
            case "check-baseline" -> {if(args.length!=4)throw new IllegalArgumentException("check-baseline case neutral-directory attempt-directory");
                checkBaseline(args[1],Path.of(args[2]),Path.of(args[3]));}
        }
    }
    static List<List<Double>> reference(String id){
        List<MetricPoint> points;
        if(id.equals("strong-modulated-versus-quiet-faint")||id.equals("strong-parallel-gap"))
            points=List.of(new MetricPoint(8,37),new MetricPoint(32,37));
        else if(id.equals("thin-strong-sharp-bend")||id.equals("thin-faint-coherent-sharp-bend"))
            points=BThinStrandFixtures.byId(id).strands().get(0).centerline().stream().filter(p->p.xMeters()>=-8&&p.xMeters()<=52).toList();
        else if(BThinSwitchbackFixtures.IDS.contains(id)||BSameTrailRecordingFixtures.IDS.contains(id)||id.equals(BSeparatedSameTrailFixtures.ID))
            points=BThinSwitchbackFixtures.source(); //Explicit renderer/latent reference; not inferred from OSM guide.
        else if(id.equals("faint-twelve-metre-bend"))points=List.of(new MetricPoint(8,37),new MetricPoint(14,37),
            new MetricPoint(20,40),new MetricPoint(26,37),new MetricPoint(32,37));
        else return List.of();
        return points.stream().map(p->List.of(p.xMeters(),p.yMeters())).toList();
    }
    private static void checkBaseline(String id,Path neutral,Path attempt)throws Exception{
        checkBaselineSince(id,neutral,attempt,System.nanoTime());
    }
    /** Invoked after native return in an isolated loader, sharing its original clock. */
    public static void checkBaselineSince(String id,Path neutral,Path attempt,long started)throws Exception{
        requireCi();CancellationProbe probe=()->System.nanoTime()-started>=900_000_000_000L;probe.checkpoint();
        BSharedPositionComparison.requireAttempt(id,1,0);
        var receipt=BIdentificationReceipt.read(attempt.resolve("receipt.json"));
        boolean completed=Boolean.TRUE.equals(receipt.get("routeReturned"));
        if(completed){
            var original=points(receipt.get("original"));var nativeSource=points(receipt.get("nativeSource"));var path=points(receipt.get("returned"));
            var ledger=new AttemptMemoryLedger(64L<<20);
            try(var root=ledger.rootOwner();var capture=BNativeNeutralFixtureExporter.recapture(neutral,id,probe,root)){
                receipt.put("quality",BNativePointAcceptance.evaluate(id,capture.snapshot(),original,nativeSource,path,probe,root));
            }
        }
        receipt.put("reference",reference(id));receipt.put("nativePitchMeters",1);
        probe.checkpoint();BIdentificationReceipt.publish(attempt.resolve("checked.json"),receipt);probe.checkpoint();
    }
    @SuppressWarnings("unchecked") private static List<MetricPoint> points(Object input){
        if(!(input instanceof List<?> list)||list.size()<2||list.size()>81)throw new IllegalArgumentException("Bounded returned points required");
        return list.stream().map(p->{if(!(p instanceof List<?> xy)||xy.size()!=2)throw new IllegalArgumentException("Point required");
            return new MetricPoint(((Number)xy.get(0)).doubleValue(),((Number)xy.get(1)).doubleValue());}).toList();
    }
}
