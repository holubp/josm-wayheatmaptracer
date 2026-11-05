package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Fixed prepaid A execution facts, owned by the input rather than a failed Engine.
 * This is a single-attempt carrier, not a winner or a per-operation trace. Getters remain usable
 * after close/failure. Sealing performs no source reads, allocation, iteration or budget charge.
 */
public final class NativeAExecutionCensus implements AutoCloseable {
    public enum Phase { CREATED, PREPROCESSING, ENGINE, CLOSURE, SCALAR, REINSERT, CERTIFY, EXTEND, PREFIX_FRONTIER, PREFIX_PUBLICATION, FINAL_EXACT, RESULT }
    // Exact authority only while live; a terminal export must not retain the source payload.
    private SampledEmissionInput input;
    private final EmissionWorkBudget budget;
    private final AttemptMemoryLedger.Owner owner;
    private final boolean managed;
    private boolean begun,finished,closed;
    private Phase phase=Phase.CREATED;
    private int arc=-1,cursor=-1,queue,reached;
    private long prefixVersion=-1,pendingSerial=-1,refinements,reinsertions,scalarStarts,scalarCompleted,scalarComplete,scalarUnknown,scalarHits;
    private long evaluateStarts,evaluateReturned,admitted,rejected,unavailable,refusalHits,admittedHits;
    private long nativeQAvailable,positiveQ;
    private long prefixDominated,prefixRetired,stalePrefixPops,inactivePendingFiltered,maxActivePrefixes;
    private boolean prefixDominanceApplied;
    private long work,pairs,peak,current;
    private boolean hasIncumbent;
    private double upper=Double.NaN,lower=Double.NaN;
    private RuntimeException failure;
    private java.util.Collection<?> liveQueue,liveReached;
    private NativeAExecutionCensus(SampledEmissionInput input,AttemptMemoryLedger.Owner owner) {
        this.input=input;budget=input.workBudget();this.owner=owner;managed=input.managedAEndpoints().isPresent();refresh();
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
        input.requireRetainedSourceUnchanged();guardManaged();begun=true;
    }
    public void requireActive(SampledEmissionInput input) {
        if(this.input!=input||!begun||finished||closed)throw new IllegalArgumentException("Native A census is not active");
    }
    /** Read-only final authority check, with no charge/callback or publication on a revoked owner. */
    public void requireManagedPublicationAuthority(SampledEmissionInput input){
        requireActive(input);if(owner.isClosed())throw new IllegalArgumentException("Native A census owner is closed");
    }
    private boolean managedLive(){return !closed&&!finished&&!owner.isClosed()&&input!=null;}
    private void guardManaged(){if(managed&&!managedLive())throw new IllegalStateException("Managed A census is revoked");}
    /** Bounds/queue facts refer to the current original native hypothesis; counters are attempt totals. */
    public void beginEngine() {
        guardManaged();
        liveQueue=null;liveReached=null;queue=0;reached=0;hasIncumbent=false;upper=Double.NaN;lower=Double.NaN;
        boundary(Phase.ENGINE,-1,-1,-1,-1);
    }
    public void boundary(Phase phase,int arc,int cursor,long version,long serial) {
        guardManaged();
        this.phase=phase;this.arc=arc;this.cursor=cursor;prefixVersion=version;pendingSerial=serial;
        budget.charge(EmissionWorkBudget.Operation.PREPARATION,1);guardManaged();refresh();
    }
    public void frontier(int queue,int reached,boolean incumbent,double upper,double lower) {
        guardManaged();
        this.queue=queue;this.reached=reached;hasIncumbent=incumbent;this.upper=upper;this.lower=lower;
    }
    void trackFrontier(java.util.Collection<?> queue,java.util.Collection<?> reached){guardManaged();liveQueue=queue;liveReached=reached;}
    void closeFrontier(){if(managed&&(closed||owner.isClosed()))return;refresh();liveQueue=null;liveReached=null;}
    void pendingLower(double lower){guardManaged();this.lower=lower;}
    public void refinement(){guardManaged();refinements++;}
    public void reinserted(){guardManaged();reinsertions++;}
    public void scalarStarted(){guardManaged();scalarStarts++;}
    public void scalarReturned(boolean complete){guardManaged();scalarCompleted++;if(complete)scalarComplete++;else scalarUnknown++;}
    public void scalarUnknown(){guardManaged();scalarUnknown++;}
    public void scalarHit(){guardManaged();scalarHits++;}
    public void scalarQ(boolean finite,boolean positive,boolean physicallyComplete){
        guardManaged();
        if(finite)nativeQAvailable++;else if(physicallyComplete)scalarUnknown++;
        if(positive)positiveQ++;
    }
    public void evaluateStarted(){guardManaged();evaluateStarts++;}
    public void evaluateReturned(NativeConnectorEvaluator.Status status) {
        guardManaged();
        evaluateReturned++;switch(status){case ADMITTED->admitted++;case REJECTED->rejected++;case UNAVAILABLE->unavailable++;}
    }
    public void refusalHit(){guardManaged();refusalHits++;}
    public void admittedHit(){guardManaged();admittedHits++;}
    public void prefixDominated(){guardManaged();prefixDominated++;}
    public void prefixRetired(){guardManaged();prefixRetired++;}
    public void stalePrefixPopped(){guardManaged();stalePrefixPops++;}
    public void inactivePendingFiltered(){guardManaged();inactivePendingFiltered++;}
    public void activePrefixes(int count){guardManaged();maxActivePrefixes=Math.max(maxActivePrefixes,count);}
    public void prefixDominanceApplied(){guardManaged();prefixDominanceApplied=true;}
    public void finish(){guardManaged();finished=true;input=null;closeFrontier();}
    public void fail(RuntimeException failure){if(managed&&!managedLive())return;if(this.failure==null)this.failure=failure;finished=true;input=null;closeFrontier();}
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
    public long dominatedPrefixes(){return prefixDominated;}
    public long retiredPrefixes(){return prefixRetired;}
    public long stalePrefixPops(){return stalePrefixPops;}
    public long filteredInactivePending(){return inactivePendingFiltered;}
    public long maximumActivePrefixesPerKey(){return maxActivePrefixes;}
    public boolean hasPrefixDominance(){return prefixDominanceApplied;}
    public long workUsed(){return work;}
    public long pairVisits(){return pairs;}
    public long peakBytes(){return owner.peakBytes();}
    public long currentBytes(){return owner.currentBytes();}
    public boolean finished(){return finished;}
    public RuntimeException failure(){return failure;}
    @Override public void close(){if(managed){if(closed)return;refresh();liveQueue=null;liveReached=null;}input=null;if(!closed){closed=true;owner.close();}refresh();}
}
