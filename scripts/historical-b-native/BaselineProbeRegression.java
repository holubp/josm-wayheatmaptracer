import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Deterministic observer checks; no imagery, model or original-case execution. */
public final class BaselineProbeRegression {
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        long[] now={0};int[] inspections={0};
        var probe=new HistoricalNativeB.Probe(()->now[0],CancellationProbe.NONE,()->{inspections[0]++;return true;});
        try{probe.cancelled();throw new AssertionError("Capture-only numerical entry admitted");}
        catch(SecurityException expected){require(inspections[0]==1,"Capture-only guard skipped");}
        probe.allowEngine=true;
        require(!probe.cancelled(),"Fresh allowed engine cancelled");
        require(inspections[0]==1,"Stack inspector invoked in allowed engine mode");
        now[0]=899_999_999_999L;
        require(!probe.cancelled(),"Extended offline deadline ended early");
        now[0]=900_000_000_000L;
        require(probe.cancelled(),"Extended offline deadline bypassed");
        now[0]=0;probe.cancelAt=probe.checkpoints+1;
        require(probe.cancelled(),"Checkpoint cancellation bypassed");
        var delegated=new HistoricalNativeB.Probe(()->0,()->true,()->{throw new AssertionError("Unexpected engine stack inspection");});
        delegated.allowEngine=true;
        require(delegated.cancelled(),"Delegate cancellation bypassed");
        // A tiny capture/request only: never invoke the engine or original cases.
        var quiet=new HistoricalNativeB.Probe(()->0,CancellationProbe.NONE,()->false);
        var evidence=HistoricalNativeB.tinyNativeSource(quiet);
        var original=HistoricalNativeB.bind(evidence,java.util.List.of(
            new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(-1.5,0),
            new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(1.5,0)),quiet);
        var extended=ExploratoryBaselineB.extendedBudget(original);
        require(extended.request().budgets().maximumTransitions()==1_024_000_000L,"Actual request retains old scored-work cap");
        require(original.request().budgets().maximumTransitions()==128_000_000L,"Historical defaults mutated");
        for(var component:org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest.class.getRecordComponents()){
            if(java.util.Set.of("budgets","settingsHash").contains(component.getName()))continue;
            require(java.util.Objects.equals(component.getAccessor().invoke(original.request()),component.getAccessor().invoke(extended.request())),"Request field changed: "+component.getName());
        }
        var budget=extended.request().budgets();
        require(budget.maximumStatesPerProfile()==96&&budget.maximumPairVisits()==8_000_000L
            &&budget.maximumRawAlternatives()==32&&budget.maximumDistinctAlternatives()==8,"Other native limits changed");
        require(!extended.request().settingsHash().equals(original.request().settingsHash()),"Extended request identity unchanged");
        System.out.println("BASELINE_PROBE_REGRESSION_PASS capture-guard engine-observer deadline count delegate");
    }
}
