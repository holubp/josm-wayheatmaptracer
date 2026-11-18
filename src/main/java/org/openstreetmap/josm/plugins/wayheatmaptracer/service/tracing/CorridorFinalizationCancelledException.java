package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.concurrent.CancellationException;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;

/** Diagnostic-only completed engine work; never a preview, final route or successful pipeline result. */
public final class CorridorFinalizationCancelledException extends CancellationException {
    private final TraceHypothesisSet engineResult;
    private final TraceWorkUsage usage;
    private final CorridorExecutionReceipt receipt;
    CorridorFinalizationCancelledException(TraceEngineRun engine,CancellationException original){
        super("A native finalization cancelled");initCause(original);
        engineResult=engine.result();usage=engine.usage();receipt=engine.corridorReceipt().orElseThrow();
    }
    public String phase(){return "FINALIZATION";}
    public String overallOutcome(){return "CANCELLED_AT_FINALIZATION";}
    public TraceHypothesisSet engineResult(){return engineResult;}
    public TraceWorkUsage usage(){return usage;}
    public CorridorExecutionReceipt receipt(){return receipt;}
    /** Extract before queueing delivery so the raw engine result and original exception are not retained. */
    public CorridorCancellationDiagnostics diagnostics(){return CorridorCancellationDiagnostics.from(this);}
}
