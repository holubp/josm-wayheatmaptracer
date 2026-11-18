package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.ScalarReplayFingerprint;

/** Bounded cancellation-only transport; contains no routes, assignments, arrays or source fields. */
public final class CorridorCancellationDiagnostics {
    private final TraceHypothesisSet.Status engineOutcome;
    private final long evaluatedStates,evaluatedTransitions;
    private final int retainedRawHypotheses;
    private final TraceWorkUsage usage;
    private final CorridorExecutionReceipt receipt;
    private CorridorCancellationDiagnostics(CorridorFinalizationCancelledException cancelled){
        var engine=cancelled.engineResult();receipt=cancelled.receipt();usage=cancelled.usage();
        if(!receipt.resultHash().equals(ScalarReplayFingerprint.sha256(engine))
            ||engine.evaluatedStates()!=receipt.totals().unaryEvaluations()
            ||engine.evaluatedTransitions()!=receipt.totals().attemptedTransitions()
            ||usage.pairVisits()!=receipt.totals().pairVisits()||usage.transitions()!=engine.evaluatedTransitions())
            throw new IllegalArgumentException("Cancellation engine receipt binding differs");
        engineOutcome=engine.status();evaluatedStates=engine.evaluatedStates();evaluatedTransitions=engine.evaluatedTransitions();
        retainedRawHypotheses=engine.hypotheses().size();
    }
    static CorridorCancellationDiagnostics from(CorridorFinalizationCancelledException cancelled){return new CorridorCancellationDiagnostics(cancelled);}
    public String phase(){return "FINALIZATION";}
    public String cause(){return "CANCELLED_DURING_NATIVE_FINALIZATION";}
    public String overallOutcome(){return "CANCELLED_AT_FINALIZATION";}
    public String finalGeometryAvailability(){return "UNAVAILABLE";}
    public String assignmentsAvailability(){return "UNAVAILABLE";}
    public String planAvailability(){return "UNAVAILABLE";}
    public TraceHypothesisSet.Status engineOutcome(){return engineOutcome;}
    public long evaluatedStates(){return evaluatedStates;} public long evaluatedTransitions(){return evaluatedTransitions;}
    public int retainedRawHypotheses(){return retainedRawHypotheses;} public TraceWorkUsage usage(){return usage;}
    public CorridorExecutionReceipt receipt(){return receipt;}
    public boolean matchesSource(String snapshotId,String settingsHash,String networkHash){
        return receipt.networkSnapshotId().equals(snapshotId)&&receipt.settingsHash().equals(settingsHash)&&receipt.networkHash().equals(networkHash);
    }
}
