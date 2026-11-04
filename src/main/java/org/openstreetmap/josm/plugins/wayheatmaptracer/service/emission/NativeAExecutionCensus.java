package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Fixed prepaid A execution facts, owned by the input rather than a failed Engine.
 * This is a single-attempt carrier, not a winner or a per-operation trace. Getters remain usable
 * after close/failure. Sealing performs no source reads, allocation, iteration or budget charge.
 */
public final class NativeAExecutionCensus implements AutoCloseable {
    public enum Phase { CREATED, PREPROCESSING, ENGINE, CLOSURE, SCALAR, REINSERT, CERTIFY, EXTEND, FINAL_EXACT, RESULT }
    // Exact authority only while live; a terminal export must not retain the source payload.
    private SampledEmissionInput input;
    private final EmissionWorkBudget budget;
    private final AttemptMemoryLedger.Owner owner;
    private boolean begun,finished,closed;
    private Phase phase=Phase.CREATED;
    private int arc=-1,cursor=-1,queue,reached;
    private long prefixVersion=-1,pendingSerial=-1,refinements,reinsertions,scalarStarts,scalarCompleted,scalarComplete,scalarUnknown,scalarHits;
    private long evaluateStarts,evaluateReturned,admitted,rejected,unavailable,refusalHits,admittedHits;
    private long nativeQAvailable,positiveQ;
    private long work,pairs,peak,current;
    private boolean hasIncumbent;
    private double upper=Double.NaN,lower=Double.NaN;
    private RuntimeException failure;
    private java.util.Collection<?> liveQueue,liveReached;
    private NativeAExecutionCensus(SampledEmissionInput input,AttemptMemoryLedger.Owner owner) {
        this.input=input;budget=input.workBudget();this.owner=owner;refresh();
    }
    public static NativeAExecutionCensus open(SampledEmissionInput input) {
        var budget=input.workBudget();var owner=input.openRetainedSourceExecutionOwner("native-a-census");
        try {
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);owner.reserve(2048);
            return new NativeAExecutionCensus(input,owner);
        }catch(RuntimeException failure){owner.close();input.recordTerminalExecutionFailure(failure);throw failure;}
    }
    /** Nonmutating caller compatibility; run outside the owning attempt's failure boundary. */
    public void requireCanBegin(SampledEmissionInput input) {
        if(this.input!=input||closed||begun||finished)throw new IllegalArgumentException("Native A census is foreign or already used");
    }
    public void begin(SampledEmissionInput input) {
        requireCanBegin(input);
        input.requireRetainedSourceUnchanged();begun=true;
    }
    public void requireActive(SampledEmissionInput input) {
        if(this.input!=input||!begun||finished||closed)throw new IllegalArgumentException("Native A census is not active");
    }
    /** Bounds/queue facts refer to the current original native hypothesis; counters are attempt totals. */
    public void beginEngine() {
        liveQueue=null;liveReached=null;queue=0;reached=0;hasIncumbent=false;upper=Double.NaN;lower=Double.NaN;
        boundary(Phase.ENGINE,-1,-1,-1,-1);
    }
    public void boundary(Phase phase,int arc,int cursor,long version,long serial) {
        this.phase=phase;this.arc=arc;this.cursor=cursor;prefixVersion=version;pendingSerial=serial;
        budget.charge(EmissionWorkBudget.Operation.PREPARATION,1);refresh();
    }
    public void frontier(int queue,int reached,boolean incumbent,double upper,double lower) {
        this.queue=queue;this.reached=reached;hasIncumbent=incumbent;this.upper=upper;this.lower=lower;
    }
    void trackFrontier(java.util.Collection<?> queue,java.util.Collection<?> reached){liveQueue=queue;liveReached=reached;}
    void closeFrontier(){refresh();liveQueue=null;liveReached=null;}
    void pendingLower(double lower){this.lower=lower;}
    public void refinement(){refinements++;}
    public void reinserted(){reinsertions++;}
    public void scalarStarted(){scalarStarts++;}
    public void scalarReturned(boolean complete){scalarCompleted++;if(complete)scalarComplete++;else scalarUnknown++;}
    public void scalarUnknown(){scalarUnknown++;}
    public void scalarHit(){scalarHits++;}
    public void scalarQ(boolean finite,boolean positive,boolean physicallyComplete){
        if(finite)nativeQAvailable++;else if(physicallyComplete)scalarUnknown++;
        if(positive)positiveQ++;
    }
    public void evaluateStarted(){evaluateStarts++;}
    public void evaluateReturned(NativeConnectorEvaluator.Status status) {
        evaluateReturned++;switch(status){case ADMITTED->admitted++;case REJECTED->rejected++;case UNAVAILABLE->unavailable++;}
    }
    public void refusalHit(){refusalHits++;}
    public void admittedHit(){admittedHits++;}
    public void finish(){finished=true;input=null;closeFrontier();}
    public void fail(RuntimeException failure){if(this.failure==null)this.failure=failure;finished=true;input=null;closeFrontier();}
    private void refresh(){work=budget.workUsed();pairs=budget.pairVisits();peak=owner.peakBytes();current=owner.currentBytes();
        if(liveQueue!=null)queue=liveQueue.size();if(liveReached!=null)reached=liveReached.size();}
    public Phase phase(){return phase;}
    public int arcId(){return arc;}
    public int cursorId(){return cursor;}
    public long prefixVersion(){return prefixVersion;}
    public long pendingSerial(){return pendingSerial;}
    public int queueSize(){return queue;}
    public int reachedSize(){return reached;}
    public boolean hasIncumbent(){return hasIncumbent;}
    public double upperBound(){return upper;}
    public double pendingLowerBound(){return lower;}
    public long refinements(){return refinements;}
    public long reinsertions(){return reinsertions;}
    public long scalarStarts(){return scalarStarts;}
    public long scalarCompleted(){return scalarCompleted;}
    public long scalarComplete(){return scalarComplete;}
    public long scalarUnknownCount(){return scalarUnknown;}
    public long scalarHits(){return scalarHits;}
    public long nativeQAvailable(){return nativeQAvailable;}
    public long positiveQ(){return positiveQ;}
    public long evaluateStarts(){return evaluateStarts;}
    public long evaluateReturned(){return evaluateReturned;}
    public long admitted(){return admitted;}
    public long rejected(){return rejected;}
    public long unavailable(){return unavailable;}
    public long refusalHits(){return refusalHits;}
    public long admittedHits(){return admittedHits;}
    public long workUsed(){return work;}
    public long pairVisits(){return pairs;}
    public long peakBytes(){return owner.peakBytes();}
    public long currentBytes(){return owner.currentBytes();}
    public boolean finished(){return finished;}
    public RuntimeException failure(){return failure;}
    @Override public void close(){input=null;if(!closed){closed=true;owner.close();}refresh();}
}
