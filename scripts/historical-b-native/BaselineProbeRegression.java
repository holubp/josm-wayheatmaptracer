import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Deterministic observer checks; no imagery, model or original-case execution. */
public final class BaselineProbeRegression {
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        long[] now={0};int[] inspections={0};
        var probe=new HistoricalNativeB.Probe(()->now[0],CancellationProbe.NONE,()->{inspections[0]++;return true;});
        try{probe.cancelled();throw new AssertionError("Capture-only numerical entry admitted");}
        catch(SecurityException expected){require(inspections[0]==1,"Capture-only guard skipped");}
        probe.allowEngine=true;
        require(!probe.cancelled(),"Fresh allowed engine cancelled");
        require(inspections[0]==1,"Stack inspector invoked in allowed engine mode");
        now[0]=300_000_000_000L;
        require(probe.cancelled(),"Original deadline bypassed");
        now[0]=0;probe.cancelAt=probe.checkpoints+1;
        require(probe.cancelled(),"Checkpoint cancellation bypassed");
        var delegated=new HistoricalNativeB.Probe(()->0,()->true,()->{throw new AssertionError("Unexpected engine stack inspection");});
        delegated.allowEngine=true;
        require(delegated.cancelled(),"Delegate cancellation bypassed");
        System.out.println("BASELINE_PROBE_REGRESSION_PASS capture-guard engine-observer deadline count delegate");
    }
}
