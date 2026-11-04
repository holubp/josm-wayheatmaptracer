package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget.Operation;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;

/**
 * Explicit, single-worker diagnostics. The prepaid view contains no geometry or partial winner.
 * Read it after sealing and before closing this input-owned lifetime. Null is the default observer.
 */
public final class LegacyEmissionProgress implements AutoCloseable {
    public static final int SEED_SLOTS=8, STAGE_SLOTS=8, MILESTONES=8, PREFIX_CAPACITY=64, DESCRIPTOR_WORDS=12;
    public static final int PREFIX_WORK_WORDS=10;
    /** Conservative bound for this object, all fixed arrays, and its one preallocated terminal view. */
    public static final long STORAGE_BYTES=65_536;
    public enum Phase { NOT_ENTERED, DOMAIN, BASE, ANCHOR_ELIGIBILITY, INTERVAL, STITCH, RESULT, SMOOTH }
    public enum Termination { SUCCESS, TOTAL_WORK, PAIR_VISITS, MEMORY, CANCELLED, STATE_FAMILY, UNAVAILABLE, OTHER }
    public enum Counter {
        PHASE_ENTERED,PHASE_COMPLETED,SEEDS_STARTED,SEEDS_COMPLETED,
        POSITIVE_LOOKUP,POSITIVE_HIT,POSITIVE_MISS,POSITIVE_INSERT,
        ZERO_LOOKUP,ZERO_HIT,ZERO_MISS,ZERO_INSERT,
        EVALUATION_STARTED,EVALUATION_COMPLETED,ADMITTED,REJECTED,UNAVAILABLE,
        OBSERVED_STATES,CARRIES,TARGETS,ORDINARY_LEGS,ZERO_CURSORS,
        PROFILE_STARTED,PROFILE_COMPLETED,LOCAL_INVOCATIONS,WINDOW_STARTED,WINDOW_COMPLETED,LOCAL_WINDOWS,
        DENSE_FALLBACK,CROSS_REPLACEMENT,SAMPLE_STARTED,SAMPLE_COMPLETED,SAMPLING_COMPLETED,
        EXTRACTION_STARTED,EXTRACTION_COMPLETED,MODES_RETURNED,ACCEPTED_THRESHOLDS,OPTIONAL_INTEGRAL_STARTED,OPTIONAL_INTEGRAL_COMPLETED,REPEAT_STARTED,REPEAT_COMPLETED,
        NEW_STARTED,NEW_COMPLETED,REPEAT_REGION_WORK,REPEAT_CELL_WORK,REPEAT_NODE_WORK,
        CENSUS_INELIGIBLE,CENSUS_ELIGIBLE_WINDOWS,CENSUS_EXCLUDED_STARTED,CENSUS_EXCLUDED_COMPLETED,EXACT_ZERO_PROOFS,PREFLIGHT_RECEIPTS,INITIAL_LOCAL_NOISE_SKIPS
    }
    private static final Counter[] COUNTERS=Counter.values();
    private static final Phase[] PHASES=Phase.values();
    private static final Operation[] OPERATIONS=Operation.values();
    private static final int REASONS=Reason.values().length;
    private final EmissionWorkBudget budget;
    /** Live admission identity only; terminal diagnostics must not retain the source input. */
    private SampledEmissionInput boundInput;
    private final AttemptMemoryLedger.Owner owner;
    private final QueryPolicy query;
    private final ExecutionPolicy execution;
    private final long attemptOrdinal;
    private final long[] counts=new long[COUNTERS.length];
    private final long[] scoped=new long[(SEED_SLOTS+1)*PHASES.length*COUNTERS.length];
    private final long[] staged=new long[(STAGE_SLOTS+1)*COUNTERS.length];
    private final long[] reasons=new long[(SEED_SLOTS+1)*PHASES.length*REASONS];
    private final long[] tax=new long[OPERATIONS.length], accepted=new long[OPERATIONS.length];
    private final int[] seedIds=new int[SEED_SLOTS];
    private final int[] stageOrdinals=new int[STAGE_SLOTS];
    private final long[] ring=new long[MILESTONES*8], prefix=new long[PREFIX_CAPACITY*DESCRIPTOR_WORDS];
    private final long[] prefixWork=new long[PREFIX_CAPACITY*PREFIX_WORK_WORDS];
    private final long[] descriptor=new long[DESCRIPTOR_WORDS], identity=new long[9];
    private final long[] expansionHistogram=new long[16], sizeHistogram=new long[32], fallbackHistogram=new long[FallbackCause.values().length];
    private final View view=new View(this);
    private int seedSlot=SEED_SLOTS, seed=-1, row=-1, from=-1,to=-1,fromId=-1,toId=-1,stage=-1,left=-1,right=-1;
    private int stageSlot=STAGE_SLOTS;
    private int seedCountPlanned=-1;
    private int milestoneCount,prefixCount, previousHalf=-1,currentHalf=-1;
    private int ineligibilityMask;
    private long seedOverflow,stageOverflow,prefixOverflow,milestoneOverflow,histogramOverflow;
    private long orderHash=0xcbf29ce484222325L,orderHash2=0x9e3779b97f4a7c15L;
    private long regionBefore,cellBefore,nodeBefore,used,pairs,peak,current,requested,failureUsed,failureLimit;
    private int observedAtCheckpoint,carriesAtCheckpoint,targetsAtCheckpoint,positiveAtCheckpoint,zeroAtCheckpoint,ordinaryAtCheckpoint,cursorsAtCheckpoint;
    private Phase phase=Phase.NOT_ENTERED;
    private Termination termination;
    private LegacyEmissionKernel.UnavailableReason unavailableReason;
    private int failureOriginalOrdinal=-1;
    private static final int REQUESTED_AVAILABLE=1,USED_AVAILABLE=2,LIMIT_AVAILABLE=4,ORDINAL_AVAILABLE=8;
    private int failureFields;
    private int noiseSamples,noiseValid,noiseZeros,noiseRank;
    private ProfileBackend backend;
    private boolean sealed,evaluationInFlight,sampleInFlight,extractionInFlight,samplingFinished;
    private boolean profileInFlight,windowInFlight;
    private boolean integralInFlight;
    private boolean inventoryAvailable;
    private boolean firstStageComplete;
    private boolean localActive,identitySet,eligible,repeated;

    private LegacyEmissionProgress(SampledEmissionInput input,EmissionWorkBudget budget,AttemptMemoryLedger.Owner owner,QueryPolicy query,ExecutionPolicy execution,long attemptOrdinal) {
        this.boundInput=input;this.budget=budget;this.owner=owner;this.query=query;this.execution=execution;this.attemptOrdinal=attemptOrdinal;
        Arrays.fill(seedIds,-1);Arrays.fill(stageOrdinals,-1);
    }

    /** Admission failure returns no buffer; its original exception becomes sticky on the input. */
    public static LegacyEmissionProgress open(SampledEmissionInput input,QueryPolicy query,ExecutionPolicy execution) {
        return open(input,query,execution,0);
    }
    /** The nonnegative caller label is bound externally by its manifest, never source authority. */
    public static LegacyEmissionProgress open(SampledEmissionInput input,QueryPolicy query,ExecutionPolicy execution,long attemptOrdinal) {
        Objects.requireNonNull(input);Objects.requireNonNull(query);Objects.requireNonNull(execution);
        if(attemptOrdinal<0)throw new IllegalArgumentException("Attempt ordinal must be nonnegative");
        if(execution!=ExecutionPolicy.EAGER_ALL_PROBES_V1&&query!=QueryPolicy.ZERO_GUARDED_LOCAL_V1)
            throw new IllegalArgumentException("Early execution requires the zero-guarded local policy");
        var b=input.workBudget();
        AttemptMemoryLedger.Owner scope=null;
        try {
        // Prepay initialization reads and the terminal seal before any new bookkeeping is done.
        b.charge(Operation.PREPARATION,64);
        // Stack primitives reconstruct the pre-tax baseline without allocating a snapshot/array.
        long a=b.operationUnits(Operation.PREPARATION)-64,c=b.operationUnits(Operation.CELL_QUERY),n=b.operationUnits(Operation.NODE_READ),
            m=b.operationUnits(Operation.MODE_EXTRACTION),r=b.operationUnits(Operation.REGION_QUERY),al=b.operationUnits(Operation.ALLOCATION),
            pi=b.operationUnits(Operation.PAIR_INITIALIZATION),pl=b.operationUnits(Operation.PAIR_LOOKUP),tr=b.operationUnits(Operation.TRANSITION),
            t=b.operationUnits(Operation.TRANSPORT),tc=b.operationUnits(Operation.TIE_COMPARISON),bp=b.operationUnits(Operation.BACKPOINTER_VISIT);
            scope=input.openExecutionOwner("legacy-progress");b.charge(Operation.ALLOCATION,1);
            var memory=scope.reserve(STORAGE_BYTES);
            var p=new LegacyEmissionProgress(input,b,scope,query,execution,attemptOrdinal);memory.adopt(p);
            p.tax[Operation.PREPARATION.ordinal()]=b.operationUnits(Operation.PREPARATION)-a;p.tax[Operation.CELL_QUERY.ordinal()]=b.operationUnits(Operation.CELL_QUERY)-c;
            p.tax[Operation.NODE_READ.ordinal()]=b.operationUnits(Operation.NODE_READ)-n;p.tax[Operation.MODE_EXTRACTION.ordinal()]=b.operationUnits(Operation.MODE_EXTRACTION)-m;
            p.tax[Operation.REGION_QUERY.ordinal()]=b.operationUnits(Operation.REGION_QUERY)-r;p.tax[Operation.ALLOCATION.ordinal()]=b.operationUnits(Operation.ALLOCATION)-al;
            p.tax[Operation.PAIR_INITIALIZATION.ordinal()]=b.operationUnits(Operation.PAIR_INITIALIZATION)-pi;p.tax[Operation.PAIR_LOOKUP.ordinal()]=b.operationUnits(Operation.PAIR_LOOKUP)-pl;
            p.tax[Operation.TRANSITION.ordinal()]=b.operationUnits(Operation.TRANSITION)-tr;p.tax[Operation.TRANSPORT.ordinal()]=b.operationUnits(Operation.TRANSPORT)-t;
            p.tax[Operation.TIE_COMPARISON.ordinal()]=b.operationUnits(Operation.TIE_COMPARISON)-tc;p.tax[Operation.BACKPOINTER_VISIT.ordinal()]=b.operationUnits(Operation.BACKPOINTER_VISIT)-bp;
            return p;
        } catch(RuntimeException failure) {
            if(scope!=null)scope.close();input.recordTerminalExecutionFailure(failure);throw failure;
        }
    }

    /** Charge-free entry validation, before a caller may consume or seal this input-owned observer. */
    public void requireCompatible(SampledEmissionInput input,QueryPolicy query,ExecutionPolicy execution) {
        if(input!=boundInput||sealed||owner.isClosed()||budget!=input.workBudget()||this.query!=query||this.execution!=execution)
            throw new IllegalArgumentException("Progress lifetime or execution identity differs");
    }
    /** Explicit diagnostic entry; never called by the default numerical overload. */
    public void begin(SampledEmissionInput input,QueryPolicy query,ExecutionPolicy execution) {
        requireCompatible(input,query,execution);if(phase==Phase.NOT_ENTERED)phase(Phase.DOMAIN,0,-1,-1);
    }
    private void pay(long units) {
        if(sealed||owner.isClosed())throw new IllegalStateException("Progress is sealed or closed");
        budget.charge(Operation.PREPARATION,units);tax[Operation.PREPARATION.ordinal()]+=units;
    }
    private void add(Counter counter,long amount) {
        counts[counter.ordinal()]+=amount;
        scoped[(seedSlot*PHASES.length+phase.ordinal())*COUNTERS.length+counter.ordinal()]+=amount;
        staged[stageSlot*COUNTERS.length+counter.ordinal()]+=amount;
    }
    void event(Counter counter) {pay(8);add(counter,1);}
    void amount(Counter counter,long amount) {pay(8);add(counter,amount);}
    void prepareMutation(){pay(8);}
    void mutation(Counter counter){add(counter,1);}
    void inventoryComplete(int observed){add(Counter.OBSERVED_STATES,observed);}
    void inventorySizes(int observed,int carries,int targets,int positive,int zero,int ordinary,int cursors) {
        pay(16);inventoryAvailable=true;observedAtCheckpoint=observed;carriesAtCheckpoint=carries;targetsAtCheckpoint=targets;
        positiveAtCheckpoint=positive;zeroAtCheckpoint=zero;ordinaryAtCheckpoint=ordinary;cursorsAtCheckpoint=cursors;
    }
    void cacheLookup(boolean zero){pay(24);add(zero?Counter.ZERO_LOOKUP:Counter.POSITIVE_LOOKUP,1);}
    void cacheResult(boolean zero,boolean hit){add(zero?(hit?Counter.ZERO_HIT:Counter.ZERO_MISS):(hit?Counter.POSITIVE_HIT:Counter.POSITIVE_MISS),1);}
    void phase(Phase next,int ordinal,int left,int right) {
        pay(16);phase=next;stage=ordinal;stageSlot=selectStage(ordinal);this.left=left;this.right=right;
        row=from=to=fromId=toId=-1;add(Counter.PHASE_ENTERED,1);milestone(0);
    }
    void phaseComplete() {add(Counter.PHASE_COMPLETED,1);milestone(1);}
    void domainComplete(int seedCount){seedCountPlanned=seedCount;phaseComplete();}
    void firstStageComplete(){firstStageComplete=true;}
    private int selectStage(int ordinal) {
        for(int i=0;i<STAGE_SLOTS;i++)if(stageOrdinals[i]==ordinal)return i;
        for(int i=0;i<STAGE_SLOTS;i++)if(stageOrdinals[i]<0){stageOrdinals[i]=ordinal;return i;}
        stageOverflow++;return STAGE_SLOTS;
    }
    void seedBegin(int id) {
        pay(16);seed=id;seedSlot=SEED_SLOTS;phase=Phase.BASE;stage=0;stageSlot=selectStage(0);inventoryAvailable=false;
        for(int i=0;i<SEED_SLOTS;i++)if(seedIds[i]==id){seedSlot=i;break;}
        if(seedSlot==SEED_SLOTS)for(int i=0;i<SEED_SLOTS;i++)if(seedIds[i]<0){seedIds[i]=id;seedSlot=i;break;}
        if(seedSlot==SEED_SLOTS)seedOverflow++;
        add(Counter.SEEDS_STARTED,1);
    }
    void seedComplete(){add(Counter.SEEDS_COMPLETED,1);}
    void seedSelect(int id){pay(8);seed=id;seedSlot=SEED_SLOTS;inventoryAvailable=false;for(int i=0;i<SEED_SLOTS;i++)if(seedIds[i]==id){seedSlot=i;break;}}
    void row(int value){pay(8);row=value;milestone(0);}
    void edge(int a,int b,int ai,int bi){pay(8);row=from=a;to=b;fromId=ai;toId=bi;}
    private void milestone(int completed) {
        int at=milestoneCount%MILESTONES;
        if(milestoneCount>=MILESTONES)milestoneOverflow++;
        int i=at*8;ring[i]=seed;ring[i+1]=phase.ordinal();ring[i+2]=stage;ring[i+3]=row;
        ring[i+4]=from;ring[i+5]=to;ring[i+6]=completed;ring[i+7]=counts[Counter.EVALUATION_COMPLETED.ordinal()];
        milestoneCount++;
    }
    void evaluationBegin(int a,int b,int ai,int bi,boolean zero,int originKind,long origin1,long origin2) {
        pay(32);evaluationInFlight=true;sampleInFlight=extractionInFlight=integralInFlight=samplingFinished=profileInFlight=windowInFlight=false;
        add(Counter.EVALUATION_STARTED,1);
        descriptor[0]=seed;descriptor[1]=phase.ordinal();descriptor[2]=stage;descriptor[3]=a;descriptor[4]=b;
        descriptor[5]=ai;descriptor[6]=bi;descriptor[7]=zero?1:0;descriptor[8]=originKind;
        descriptor[9]=origin1;descriptor[10]=origin2;
    }
    void evaluationComplete(Receipt receipt) {
        evaluationInFlight=false;add(Counter.EVALUATION_COMPLETED,1);
        add(switch(receipt.status()){case ADMITTED->Counter.ADMITTED;case REJECTED->Counter.REJECTED;case UNAVAILABLE->Counter.UNAVAILABLE;},1);
        reasons[(seedSlot*PHASES.length+phase.ordinal())*REASONS+receipt.reason().ordinal()]++;
        if(receipt.exactSlabPreflight().isPresent())add(Counter.PREFLIGHT_RECEIPTS,1);
        if(receipt.reason()==Reason.EXACT_ZERO_SLAB_NECESSARY_FAILURE)add(Counter.EXACT_ZERO_PROOFS,1);
        descriptor[11]=(long)receipt.status().ordinal()*REASONS+receipt.reason().ordinal();
        for(int i=0;i<DESCRIPTOR_WORDS;i++) {
            long value=descriptor[i];orderHash=(orderHash^value)*0x100000001b3L;
            orderHash2=Long.rotateLeft(orderHash2^value,17)*0x9e3779b97f4a7c15L;
            if(prefixCount<PREFIX_CAPACITY)prefix[prefixCount*DESCRIPTOR_WORDS+i]=value;
        }
        if(prefixCount<PREFIX_CAPACITY) {
            int at=prefixCount*PREFIX_WORK_WORDS;long diagnostic=0;for(long units:tax)diagnostic+=units;
            prefixWork[at]=budget.workUsed();prefixWork[at+1]=diagnostic;prefixWork[at+2]=budget.pairVisits();
            prefixWork[at+3]=budget.operationUnits(Operation.MODE_EXTRACTION);prefixWork[at+4]=budget.operationUnits(Operation.REGION_QUERY);
            prefixWork[at+5]=budget.operationUnits(Operation.CELL_QUERY);prefixWork[at+6]=budget.operationUnits(Operation.NODE_READ);
            prefixWork[at+7]=counts[Counter.REPEAT_REGION_WORK.ordinal()];prefixWork[at+8]=counts[Counter.REPEAT_CELL_WORK.ordinal()];prefixWork[at+9]=counts[Counter.REPEAT_NODE_WORK.ordinal()];
            prefixCount++;
        } else prefixOverflow++;
    }
    void profileBegin(){pay(16);profileInFlight=true;add(Counter.PROFILE_STARTED,1);}
    void profileComplete(){profileInFlight=false;add(Counter.PROFILE_COMPLETED,1);}
    void localBegin(){pay(16);add(Counter.LOCAL_INVOCATIONS,1);localActive=true;identitySet=false;eligible=true;previousHalf=-1;}
    void localEnd(){localActive=false;identitySet=false;Arrays.fill(identity,0L);}
    void fallback(FallbackCause cause){pay(8);add(Counter.DENSE_FALLBACK,1);fallbackHistogram[cause.ordinal()]++;}
    void windowBegin(ProfileBackend backend,int half,int expansion) {
        pay(24);this.backend=backend;currentHalf=half;windowInFlight=true;samplingFinished=false;sampleInFlight=false;extractionInFlight=false;
        add(Counter.WINDOW_STARTED,1);
        if(backend==ProfileBackend.LOCAL){add(Counter.LOCAL_WINDOWS,1);expansionHistogram[Math.min(15,Math.max(0,expansion))]++;
            sizeHistogram[Math.min(31,Math.max(0,half))]++;if(expansion>15||half>31)histogramOverflow++;}
    }
    /** Values are the actual evaluated window inputs, never newly sampled/recomputed evidence. */
    void windowIdentity(boolean sourceSame,double px,double py,double cx,double cy,double nx,double ny,double delta,double source,double pitch) {
        if(backend!=ProfileBackend.LOCAL||!localActive)return;
        boolean same=sourceSame;
        if(!sourceSame)ineligibilityMask|=1;
        if(identitySet) {
            if(identity[0]!=Double.doubleToRawLongBits(px)||identity[1]!=Double.doubleToRawLongBits(py))ineligibilityMask|=2;
            if(identity[2]!=Double.doubleToRawLongBits(cx)||identity[3]!=Double.doubleToRawLongBits(cy))ineligibilityMask|=4;
            if(identity[4]!=Double.doubleToRawLongBits(nx)||identity[5]!=Double.doubleToRawLongBits(ny))ineligibilityMask|=8;
            if(identity[6]!=Double.doubleToRawLongBits(delta))ineligibilityMask|=16;
            if(identity[7]!=Double.doubleToRawLongBits(source))ineligibilityMask|=32;
            if(identity[8]!=Double.doubleToRawLongBits(pitch))ineligibilityMask|=64;
            same&=identity[0]==Double.doubleToRawLongBits(px)&&identity[1]==Double.doubleToRawLongBits(py)
                &&identity[2]==Double.doubleToRawLongBits(cx)&&identity[3]==Double.doubleToRawLongBits(cy)
                &&identity[4]==Double.doubleToRawLongBits(nx)&&identity[5]==Double.doubleToRawLongBits(ny)
                &&identity[6]==Double.doubleToRawLongBits(delta)&&identity[7]==Double.doubleToRawLongBits(source)&&identity[8]==Double.doubleToRawLongBits(pitch);
        } else {
            identity[0]=Double.doubleToRawLongBits(px);identity[1]=Double.doubleToRawLongBits(py);
            identity[2]=Double.doubleToRawLongBits(cx);identity[3]=Double.doubleToRawLongBits(cy);
            identity[4]=Double.doubleToRawLongBits(nx);identity[5]=Double.doubleToRawLongBits(ny);
            identity[6]=Double.doubleToRawLongBits(delta);identity[7]=Double.doubleToRawLongBits(source);identity[8]=Double.doubleToRawLongBits(pitch);identitySet=true;
        }
        if(!same&&eligible){eligible=false;add(Counter.CENSUS_INELIGIBLE,1);}
        if(same&&eligible)add(Counter.CENSUS_ELIGIBLE_WINDOWS,1);
    }
    void sampleBegin(int j) {
        // Pays both hooks, six primitive operation reads and the bounded classification updates.
        pay(32);sampleInFlight=true;add(Counter.SAMPLE_STARTED,1);
        boolean census=localActive&&backend==ProfileBackend.LOCAL&&eligible&&identitySet;
        repeated=census&&previousHalf>=0&&j>=-previousHalf&&j<=previousHalf;
        if(census)add(repeated?Counter.REPEAT_STARTED:Counter.NEW_STARTED,1);else add(Counter.CENSUS_EXCLUDED_STARTED,1);
        regionBefore=budget.operationUnits(Operation.REGION_QUERY);cellBefore=budget.operationUnits(Operation.CELL_QUERY);nodeBefore=budget.operationUnits(Operation.NODE_READ);
    }
    void sampleComplete() {
        sampleInFlight=false;add(Counter.SAMPLE_COMPLETED,1);
        if(localActive&&backend==ProfileBackend.LOCAL&&eligible&&identitySet) {
            add(repeated?Counter.REPEAT_COMPLETED:Counter.NEW_COMPLETED,1);
            if(repeated){add(Counter.REPEAT_REGION_WORK,budget.operationUnits(Operation.REGION_QUERY)-regionBefore);
                add(Counter.REPEAT_CELL_WORK,budget.operationUnits(Operation.CELL_QUERY)-cellBefore);
                add(Counter.REPEAT_NODE_WORK,budget.operationUnits(Operation.NODE_READ)-nodeBefore);}
        } else add(Counter.CENSUS_EXCLUDED_COMPLETED,1);
    }
    void samplingComplete(){samplingFinished=true;add(Counter.SAMPLING_COMPLETED,1);if(localActive&&backend==ProfileBackend.LOCAL&&eligible&&identitySet)previousHalf=currentHalf;}
    void extractionBegin(){pay(16);extractionInFlight=true;add(Counter.EXTRACTION_STARTED,1);}
    void extractionComplete(int modes){extractionComplete(modes,0);}
    void extractionComplete(int modes,int thresholds){extractionInFlight=false;add(Counter.EXTRACTION_COMPLETED,1);add(Counter.MODES_RETURNED,modes);add(Counter.ACCEPTED_THRESHOLDS,thresholds);}
    void integralBegin(){pay(16);integralInFlight=true;add(Counter.OPTIONAL_INTEGRAL_STARTED,1);}
    void integralComplete(){integralInFlight=false;add(Counter.OPTIONAL_INTEGRAL_COMPLETED,1);}
    void windowComplete(){windowInFlight=false;add(Counter.WINDOW_COMPLETED,1);}
    void initialLocalNoiseSkipped(int samples,int valid,int zeros,int rank) {
        pay(8);add(Counter.INITIAL_LOCAL_NOISE_SKIPS,1);noiseSamples=samples;noiseValid=valid;noiseZeros=zeros;noiseRank=rank;
    }

    /** Allocation/charge-free: only already prepaid primitive storage is sealed. Original error is not replaced. */
    public void seal(RuntimeException failure) {
        if(sealed)return;
        for(var op:OPERATIONS)accepted[op.ordinal()]=budget.operationUnits(op);
        used=budget.workUsed();pairs=budget.pairVisits();peak=owner.peakBytes();current=owner.currentBytes();
        if(failure==null)termination=Termination.SUCCESS;
        else if(failure instanceof EmissionWorkBudget.LimitExceededException e){termination=e.kind()==EmissionWorkBudget.LimitKind.TOTAL_WORK?Termination.TOTAL_WORK:Termination.PAIR_VISITS;
            requested=e.requestedUnits();failureUsed=e.used();failureLimit=e.limit();failureFields=REQUESTED_AVAILABLE|USED_AVAILABLE|LIMIT_AVAILABLE;}
        else if(failure instanceof AttemptMemoryLedger.ResourceLimitException e){termination=Termination.MEMORY;requested=e.requestedBytes();failureUsed=e.currentBytes();failureLimit=e.limitBytes();failureFields=REQUESTED_AVAILABLE|USED_AVAILABLE|LIMIT_AVAILABLE;}
        else if(failure instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.ResourceLimitException
                &&failure.getCause() instanceof AttemptMemoryLedger.ResourceLimitException e){termination=Termination.MEMORY;requested=e.requestedBytes();failureUsed=e.currentBytes();failureLimit=e.limitBytes();failureFields=REQUESTED_AVAILABLE|USED_AVAILABLE|LIMIT_AVAILABLE;}
        else if(failure instanceof LegacyEmissionDomain.StateFamilyLimitException e){termination=Termination.STATE_FAMILY;failureOriginalOrdinal=e.originalRow();failureUsed=e.familySize();failureLimit=e.limit();failureFields=USED_AVAILABLE|LIMIT_AVAILABLE|ORDINAL_AVAILABLE;}
        else if(failure instanceof NativeConnectorEvaluator.StateFamilyLimitException e){termination=Termination.STATE_FAMILY;failureOriginalOrdinal=e.originalOrdinal();failureLimit=e.limit();failureFields=LIMIT_AVAILABLE|ORDINAL_AVAILABLE;}
        else if(failure instanceof LegacyEmissionKernel.UnavailableException e){termination=Termination.UNAVAILABLE;unavailableReason=e.reason();}
        else termination=failure instanceof CancellationException?Termination.CANCELLED:Termination.OTHER;
        localEnd();boundInput=null;sealed=true;
    }
    public View view(){if(!sealed)throw new IllegalStateException("Progress is not sealed");return view;}
    /** Primitive live milestones for a same-worker diagnostic/cancellation observer; no source data. */
    public Phase currentPhase(){return phase;}
    public int currentFromOrdinal(){return from;}
    public boolean sampleInFlight(){return sampleInFlight;}
    public boolean extractionInFlight(){return extractionInFlight;}
    public boolean samplingFinished(){return samplingFinished;}
    public long currentCount(Counter counter){return counts[counter.ordinal()];}
    @Override public void close(){boundInput=null;owner.close();}

    /** Preallocated immutable-after-seal view; numeric/enumerated only. No arrays are exposed. */
    public static final class View {
        private final LegacyEmissionProgress p;
        private View(LegacyEmissionProgress p){this.p=p;}
        public Phase phase(){return p.phase;} public Termination termination(){return p.termination;}
        public QueryPolicy queryPolicy(){return p.query;}public ExecutionPolicy executionPolicy(){return p.execution;}
        public long attemptOrdinal(){return p.attemptOrdinal;}
        public int seed(){return p.seed;}public int row(){return p.row;}public int stage(){return p.stage;}
        public int seedsPlanned(){return p.seedCountPlanned;}
        /** Selected first-stage path was flattened and verified; this never publishes a candidate. */
        public boolean firstStageCompleted(){return p.firstStageComplete;}
        public int intervalLeft(){return p.left;}public int intervalRight(){return p.right;}
        public int fromOrdinal(){return p.from;}public int toOrdinal(){return p.to;}
        public int fromNativeId(){return p.fromId;}public int toNativeId(){return p.toId;}
        public long count(Counter c){return p.counts[c.ordinal()];}
        public boolean hasInitialLocalNoiseProof(){return count(Counter.INITIAL_LOCAL_NOISE_SKIPS)>0;}
        private void requireNoiseProof(){if(!hasInitialLocalNoiseProof())throw new IllegalStateException("Sampled noise proof is unavailable");}
        public int lastNoiseSampleCount(){requireNoiseProof();return p.noiseSamples;}public int lastNoiseValidCount(){requireNoiseProof();return p.noiseValid;}
        public int lastNoiseZeroCount(){requireNoiseProof();return p.noiseZeros;}public int lastNoiseRank(){requireNoiseProof();return p.noiseRank;}
        public String localNoiseProofPolicy(){requireNoiseProof();return org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.RAW_NOISE_RANK_POLICY;}
        public long seedStageCount(int slot,Phase phase,Counter c){Objects.checkIndex(slot,SEED_SLOTS+1);return p.scoped[(slot*PHASES.length+phase.ordinal())*COUNTERS.length+c.ordinal()];}
        public long reasonCount(int slot,Phase phase,Reason reason){Objects.checkIndex(slot,SEED_SLOTS+1);return p.reasons[(slot*PHASES.length+phase.ordinal())*REASONS+reason.ordinal()];}
        public int seedId(int slot){return p.seedIds[Objects.checkIndex(slot,SEED_SLOTS)];}
        public long seedOverflow(){return p.seedOverflow;}public long prefixOverflow(){return p.prefixOverflow;}
        public int stageOrdinal(int slot){return p.stageOrdinals[Objects.checkIndex(slot,STAGE_SLOTS)];}
        public long stageCount(int slot,Counter counter){Objects.checkIndex(slot,STAGE_SLOTS+1);return p.staged[slot*COUNTERS.length+counter.ordinal()];}
        /** Stage-entry events assigned to overflow; not a count of distinct unrecorded stages. */
        public long stageOverflow(){return p.stageOverflow;}
        public int prefixLength(){return p.prefixCount;}public long prefixWord(int index,int word){Objects.checkIndex(index,p.prefixCount);Objects.checkIndex(word,DESCRIPTOR_WORDS);return p.prefix[index*DESCRIPTOR_WORDS+word];}
        /** At this completed evaluation: total, diagnostic total, pairs, MODE/REGION/CELL/NODE, repeated REGION/CELL/NODE work. */
        public long prefixWorkWord(int index,int word){Objects.checkIndex(index,p.prefixCount);Objects.checkIndex(word,PREFIX_WORK_WORDS);return p.prefixWork[index*PREFIX_WORK_WORDS+word];}
        public long orderFingerprint(){return p.orderHash;}public long orderFingerprint2(){return p.orderHash2;}
        public int milestoneLength(){return Math.min(MILESTONES,p.milestoneCount);}public long milestoneOverflow(){return p.milestoneOverflow;}
        public long milestoneWord(int index,int word){Objects.checkIndex(index,milestoneLength());Objects.checkIndex(word,8);int start=Math.max(0,p.milestoneCount-MILESTONES);return p.ring[((start+index)%MILESTONES)*8+word];}
        public long expansionBucket(int index){return p.expansionHistogram[Objects.checkIndex(index,16)];}
        public long halfIntervalBucket(int index){return p.sizeHistogram[Objects.checkIndex(index,32)];}
        public long histogramOverflow(){return p.histogramOverflow;}public long fallbackCount(FallbackCause cause){return p.fallbackHistogram[cause.ordinal()];}
        /** Bits: source identity, point, center, normal, delta, source chainage, pitch. */
        public int censusIneligibilityMask(){return p.ineligibilityMask;}
        public boolean evaluationInFlight(){return p.evaluationInFlight;}public boolean sampleInFlight(){return p.sampleInFlight;}
        public boolean extractionInFlight(){return p.extractionInFlight;}public boolean samplingFinished(){return p.samplingFinished;}
        public boolean profileInFlight(){return p.profileInFlight;}public boolean windowInFlight(){return p.windowInFlight;}
        public boolean optionalIntegralInFlight(){return p.integralInFlight;}
        public int observedStatesAtLastCheckpoint(){return p.observedAtCheckpoint;}public int carriesAtLastCheckpoint(){return p.carriesAtCheckpoint;}
        public boolean inventoryCheckpointAvailable(){return p.inventoryAvailable;}
        public int targetsAtLastCheckpoint(){return p.targetsAtCheckpoint;}public int positiveReceiptsAtLastCheckpoint(){return p.positiveAtCheckpoint;}
        public int zeroReceiptsAtLastCheckpoint(){return p.zeroAtCheckpoint;}public int ordinaryLegsAtLastCheckpoint(){return p.ordinaryAtCheckpoint;}
        public int zeroCursorsAtLastCheckpoint(){return p.cursorsAtCheckpoint;}
        public long operationUnits(Operation op){return p.accepted[op.ordinal()];}public long diagnosticUnits(Operation op){return p.tax[op.ordinal()];}
        public long productionUnits(Operation op){return operationUnits(op)-diagnosticUnits(op);}
        public long workUsed(){return p.used;}public long pairVisits(){return p.pairs;}
        public long workLimit(){return p.budget.workLimit();}public long pairLimit(){return p.budget.pairLimit();}
        public long memoryLimit(){return p.owner.limitBytes();}public long peakMemoryBytes(){return p.peak;}public long currentMemoryBytes(){return p.current;}
        public boolean hasFailureRequested(){return (p.failureFields&REQUESTED_AVAILABLE)!=0;}
        public boolean hasFailureUsed(){return (p.failureFields&USED_AVAILABLE)!=0;}
        public boolean hasFailureLimit(){return (p.failureFields&LIMIT_AVAILABLE)!=0;}
        public boolean hasFailureOriginalOrdinal(){return (p.failureFields&ORDINAL_AVAILABLE)!=0;}
        public long failureRequested(){if(!hasFailureRequested())throw new IllegalStateException("Failure request is unavailable");return p.requested;}
        public long failureUsed(){if(!hasFailureUsed())throw new IllegalStateException("Failure used quantity is unavailable");return p.failureUsed;}
        public long failureLimit(){if(!hasFailureLimit())throw new IllegalStateException("Failure limit is unavailable");return p.failureLimit;}
        public int failureOriginalOrdinal(){if(!hasFailureOriginalOrdinal())throw new IllegalStateException("Failure ordinal is unavailable");return p.failureOriginalOrdinal;}
        public LegacyEmissionKernel.UnavailableReason unavailableReason(){return p.unavailableReason;}
    }
}
