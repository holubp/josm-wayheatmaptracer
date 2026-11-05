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
    /** Counts describe only candidates actually visited, never future geometry or certification. */
    public enum CeilingCounter {
        FINITE_Q0(8),ZERO_DIRECTION(9),INVALID_Q0(10),INVALID_CAP(11),CAP_CROSSINGS(14),Q0_SURVIVOR_CROSSINGS(15),Q0_STRICT_BOUNDS(16),CANDIDATES(17);
        private final int word;CeilingCounter(int word){this.word=word;}
    }
    private static final int CEILING_STAGE_WORDS=18,CEILING_TOTAL_WORDS=8;
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
        CENSUS_INELIGIBLE,CENSUS_ELIGIBLE_WINDOWS,CENSUS_EXCLUDED_STARTED,CENSUS_EXCLUDED_COMPLETED,EXACT_ZERO_PROOFS,PREFLIGHT_RECEIPTS,INITIAL_LOCAL_NOISE_SKIPS,
        WITNESS_ATTEMPTED,WITNESS_CERTIFIED,COMPONENT_BOUND_PREPARATIONS,STRICT_CERTIFICATE_DEFERRALS
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
    private LegacyEmissionKernel.CompetitiveExecution competitiveExecution;
    private LegacyEmissionKernel.GroupedExecution groupedExecution;
    private boolean currentWitnessAvailable;
    private double witnessUpper;
    private int maxCompetitiveKeyLabels;
    // New opt-in only. One CURRENT stage, plus whole-observed-execution totals; no stage history cap.
    // The array/header and added object fields fit the existing STORAGE_BYTES bound (layout regression).
    private long[] ceiling;
    private boolean ceilingStageAvailable,ceilingOverflow;
    private ProfileBackend backend;
    private boolean sealed,evaluationInFlight,sampleInFlight,extractionInFlight,samplingFinished;
    private boolean profileInFlight,windowInFlight;
    private boolean integralInFlight;
    private boolean inventoryAvailable;
    private boolean firstStageComplete;
    private boolean localActive,identitySet,eligible,repeated;
    private LegacyEmissionKernel.GuideScope guideScope;
    private int nativeGuideOriginalSeedCount=-1,nativeGuideSeedId=-1;
    private long nativeGuideSeedOffsetBits;
    private LegacyEmissionKernel.GuideStatus nativeGuideStatus;
    private boolean nativeGuideComplete,nativeGuideUpperAvailable;
    private double nativeGuideUpper;

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
        if(groupedExecution!=null)currentWitnessAvailable=false;
        ceilingStageAvailable=false;
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
        if(groupedExecution!=null)currentWitnessAvailable=false;
        ceilingStageAvailable=false;
        pay(16);seed=id;seedSlot=SEED_SLOTS;phase=Phase.BASE;stage=0;stageSlot=selectStage(0);inventoryAvailable=false;
        for(int i=0;i<SEED_SLOTS;i++)if(seedIds[i]==id){seedSlot=i;break;}
        if(seedSlot==SEED_SLOTS)for(int i=0;i<SEED_SLOTS;i++)if(seedIds[i]<0){seedIds[i]=id;seedSlot=i;break;}
        if(seedSlot==SEED_SLOTS)seedOverflow++;
        add(Counter.SEEDS_STARTED,1);
    }
    void seedComplete(){add(Counter.SEEDS_COMPLETED,1);}
    void seedSelect(int id){if(groupedExecution!=null)currentWitnessAvailable=false;ceilingStageAvailable=false;pay(8);seed=id;seedSlot=SEED_SLOTS;inventoryAvailable=false;for(int i=0;i<SEED_SLOTS;i++)if(seedIds[i]==id){seedSlot=i;break;}}
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
    void competitiveBegin(LegacyEmissionKernel.CompetitiveExecution execution){pay(8);competitiveExecution=execution;}
    void witnessBegin(){event(Counter.WITNESS_ATTEMPTED);}
    void groupedBegin(LegacyEmissionKernel.GroupedExecution execution){
        if(execution!=LegacyEmissionKernel.GroupedExecution.EXACT_Q0_CEILING_CENSUS_V1){pay(8);groupedExecution=execution;return;}
        var input=boundInput;ceilingCharge(Operation.PREPARATION,72);ceilingCharge(Operation.ALLOCATION,1);
        try(var publication=ceilingRequireFresh(input)) {
            groupedExecution=execution;ceiling=new long[CEILING_STAGE_WORDS+CEILING_TOTAL_WORDS];
        }
    }
    void nativeGuideOnlyScope(LegacyEmissionKernel.GuideScope scope){
        if(scope!=LegacyEmissionKernel.GuideScope.BASE_NATIVE_GUIDE_ONLY)throw new IllegalArgumentException("Native guide-only scope is required");
        pay(8);guideScope=scope;
    }
    void nativeGuideOnlyRequest(int originalSeedCount,LegacyEmissionDomain.Seed seed){
        Objects.requireNonNull(seed);pay(16);
        if(guideScope!=LegacyEmissionKernel.GuideScope.BASE_NATIVE_GUIDE_ONLY||originalSeedCount<1||seed.id()!=0)
            throw new IllegalArgumentException("Native guide-only must request original seed zero");
        nativeGuideOriginalSeedCount=originalSeedCount;nativeGuideSeedId=seed.id();
        nativeGuideSeedOffsetBits=Double.doubleToRawLongBits(seed.offsetRasterPixels());
    }
    void prepareNativeGuideOnly(LegacyEmissionKernel.NativeGuideOnlyDiagnostic diagnostic){
        requireNativeGuideRequest(diagnostic);pay(16);
    }
    void publishNativeGuideOnly(LegacyEmissionKernel.NativeGuideOnlyDiagnostic diagnostic){
        requireNativeGuideRequest(diagnostic);
        nativeGuideStatus=diagnostic.guidedStatus();nativeGuideUpperAvailable=diagnostic.witnessUpper().isPresent();
        if(nativeGuideUpperAvailable){nativeGuideUpper=diagnostic.witnessUpper().getAsDouble();witnessUpper=nativeGuideUpper;
            currentWitnessAvailable=true;add(Counter.WITNESS_CERTIFIED,1);}
        nativeGuideComplete=true;
    }
    private void requireNativeGuideRequest(LegacyEmissionKernel.NativeGuideOnlyDiagnostic diagnostic){
        if(diagnostic==null||guideScope!=LegacyEmissionKernel.GuideScope.BASE_NATIVE_GUIDE_ONLY
                ||nativeGuideOriginalSeedCount!=diagnostic.originalSeedCount()
                ||diagnostic.requestedSeed().isEmpty()||diagnostic.requestedSeed().orElseThrow().id()!=nativeGuideSeedId
                ||diagnostic.requestedSeed().orElseThrow().offsetRawBits()!=nativeGuideSeedOffsetBits
                ||diagnostic.coverage()!=LegacyEmissionKernel.NativeGuideCoverage.FIRST_ORIGINAL_SEED_ONLY
                ||diagnostic.greedyStatus()!=LegacyEmissionKernel.GuideStatus.NOT_REQUESTED||diagnostic.comparisonPerformed())
            throw new IllegalArgumentException("Native guide-only progress identity differs");
    }
    boolean ceilingEnabled(){return ceiling!=null;}
    /** Actual already-stored absolute witness, with all observation work prepaid before publication. */
    void ceilingWitness(SampledEmissionInput input,java.util.Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.model.EmissionResult.Costs> costs) {
        ceilingCharge(Operation.PREPARATION,costs.isPresent()?72:64);
        try(var publication=ceilingRequireFresh(input)) {
        Arrays.fill(ceiling,0,CEILING_STAGE_WORDS,0);ceiling[0]=seed;ceiling[1]=stage;
        if(costs.isPresent()) {
            var c=costs.orElseThrow();ceiling[2]=1;
            ceiling[3]=Double.doubleToRawLongBits(c.q());ceiling[4]=Double.doubleToRawLongBits(c.c());
            ceiling[5]=Double.doubleToRawLongBits(c.t());ceiling[6]=Double.doubleToRawLongBits(c.k());
            witnessUpper=c.total();ceiling[7]=Double.doubleToRawLongBits(witnessUpper);
            currentWitnessAvailable=true;add(Counter.WITNESS_CERTIFIED,1);
        }
        ceilingStageAvailable=true;
        }
    }
    void prepareCeilingObservation(){ceilingCharge(Operation.PREPARATION,128);}
    /** Complete owned final check, followed only by prepaid callback-free census writes. */
    void ceilingObservation(SampledEmissionInput input,boolean zero,double l0,double cap,boolean q0Strict) {
        try(var publication=ceilingRequireFresh(input)) {
        if(!ceilingStageAvailable)throw new IllegalStateException("Ceiling stage is unavailable");
        ceilingIncrement(CeilingCounter.CANDIDATES);
        if(zero){ceilingIncrement(CeilingCounter.ZERO_DIRECTION);return;}
        if(!Double.isFinite(l0)||l0<0){ceilingIncrement(CeilingCounter.INVALID_Q0);return;}
        ceilingIncrement(CeilingCounter.FINITE_Q0);
        if((ceiling[2]&2)==0||l0>Double.longBitsToDouble(ceiling[12]))ceiling[12]=Double.doubleToRawLongBits(l0);
        ceiling[2]|=2;
        if(q0Strict)ceilingIncrement(CeilingCounter.Q0_STRICT_BOUNDS);
        if(!Double.isFinite(cap)||cap<0){ceilingIncrement(CeilingCounter.INVALID_CAP);return;}
        if((ceiling[2]&4)==0||cap>Double.longBitsToDouble(ceiling[13]))ceiling[13]=Double.doubleToRawLongBits(cap);
        ceiling[2]|=4;
        if((ceiling[2]&1)!=0&&cap>Double.longBitsToDouble(ceiling[7])) {
            ceilingIncrement(CeilingCounter.CAP_CROSSINGS);
            if(!q0Strict)ceilingIncrement(CeilingCounter.Q0_SURVIVOR_CROSSINGS);
        }
        }
    }
    /** Same exact live-owner identity as requireCompatible for this buffer's immutable policies. */
    private boolean ceilingAuthority(SampledEmissionInput input) {
        return input!=null&&input==boundInput&&!sealed&&!owner.isClosed()&&budget==input.workBudget();
    }
    /** New-mode prepayment cannot mutate a buffer revoked by its own charge callback. */
    private void ceilingCharge(Operation operation,long units) {
        var input=boundInput;requireCompatible(input,query,execution);long before=budget.operationUnits(operation);
        try {budget.charge(operation,units);budget.requireActiveForFinalPublication();}
        finally {if(ceilingAuthority(input))tax[operation.ordinal()]+=budget.operationUnits(operation)-before;}
        requireCompatible(input,query,execution);
    }
    /** Ticket owns all source/preparation scratch through the caller's actual numeric publication. */
    private SampledEmissionInput.FinalPublication ceilingRequireFresh(SampledEmissionInput input) {
        requireCompatible(input,query,execution);
        long a=budget.operationUnits(Operation.PREPARATION),c=budget.operationUnits(Operation.CELL_QUERY),n=budget.operationUnits(Operation.NODE_READ),
            m=budget.operationUnits(Operation.MODE_EXTRACTION),r=budget.operationUnits(Operation.REGION_QUERY),al=budget.operationUnits(Operation.ALLOCATION),
            pi=budget.operationUnits(Operation.PAIR_INITIALIZATION),pl=budget.operationUnits(Operation.PAIR_LOOKUP),tr=budget.operationUnits(Operation.TRANSITION),
            t=budget.operationUnits(Operation.TRANSPORT),tc=budget.operationUnits(Operation.TIE_COMPARISON),bp=budget.operationUnits(Operation.BACKPOINTER_VISIT);
        SampledEmissionInput.FinalPublication publication=null;
        try {
            publication=input.prepareManagedFinalPublication(owner,64,256);
            publication.validate();requireCompatible(input,query,execution);return publication;
        } catch(RuntimeException failure) {
            RuntimeException primary=failure;
            // A supplier may revoke the observer and also fail the attempt. The already
            // sticky failure takes priority over the consequent closed-ticket rejection.
            try {budget.requireActiveForFinalPublication();}catch(RuntimeException terminal){primary=terminal;}
            if(publication!=null)try{publication.close();}catch(RuntimeException cleanup){if(cleanup!=primary)primary.addSuppressed(cleanup);}
            throw primary;
        }
        finally {
            // No exception-producing guard in finally: a revoked snapshot stays unchanged,
            // and an already thrown source/cap/cancellation remains the primary exception.
            if(ceilingAuthority(input)) {
            tax[Operation.PREPARATION.ordinal()]+=budget.operationUnits(Operation.PREPARATION)-a;tax[Operation.CELL_QUERY.ordinal()]+=budget.operationUnits(Operation.CELL_QUERY)-c;
            tax[Operation.NODE_READ.ordinal()]+=budget.operationUnits(Operation.NODE_READ)-n;tax[Operation.MODE_EXTRACTION.ordinal()]+=budget.operationUnits(Operation.MODE_EXTRACTION)-m;
            tax[Operation.REGION_QUERY.ordinal()]+=budget.operationUnits(Operation.REGION_QUERY)-r;tax[Operation.ALLOCATION.ordinal()]+=budget.operationUnits(Operation.ALLOCATION)-al;
            tax[Operation.PAIR_INITIALIZATION.ordinal()]+=budget.operationUnits(Operation.PAIR_INITIALIZATION)-pi;tax[Operation.PAIR_LOOKUP.ordinal()]+=budget.operationUnits(Operation.PAIR_LOOKUP)-pl;
            tax[Operation.TRANSITION.ordinal()]+=budget.operationUnits(Operation.TRANSITION)-tr;tax[Operation.TRANSPORT.ordinal()]+=budget.operationUnits(Operation.TRANSPORT)-t;
            tax[Operation.TIE_COMPARISON.ordinal()]+=budget.operationUnits(Operation.TIE_COMPARISON)-tc;tax[Operation.BACKPOINTER_VISIT.ordinal()]+=budget.operationUnits(Operation.BACKPOINTER_VISIT)-bp;
            }
        }
    }
    private void ceilingIncrement(CeilingCounter counter) {
        int total=CEILING_STAGE_WORDS+counter.ordinal();
        if(ceiling[counter.word]<Long.MAX_VALUE)ceiling[counter.word]++;else ceilingOverflow=true;
        if(ceiling[total]<Long.MAX_VALUE)ceiling[total]++;else ceilingOverflow=true;
    }
    void witnessCertified(double upper){pay(8);witnessUpper=upper;currentWitnessAvailable=true;add(Counter.WITNESS_CERTIFIED,1);}
    void prepareOwnedGuideWitness(){ceilingCharge(Operation.PREPARATION,8);}
    void requireOwnedGuideAuthority(SampledEmissionInput input){requireCompatible(input,query,execution);}
    void publishOwnedGuideWitness(double upper){witnessUpper=upper;currentWitnessAvailable=true;add(Counter.WITNESS_CERTIFIED,1);}
    void competitiveFront(int size){pay(8);maxCompetitiveKeyLabels=Math.max(maxCompetitiveKeyLabels,size);}

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
        public boolean hasGuideScope(){return p.guideScope!=null;}
        public LegacyEmissionKernel.GuideScope guideScope(){if(!hasGuideScope())throw new IllegalStateException("Guide diagnostic scope is unavailable");return p.guideScope;}
        public boolean hasNativeGuideOnlyRequest(){return p.nativeGuideOriginalSeedCount>=0;}
        public int nativeGuideOriginalSeedCount(){if(!hasNativeGuideOnlyRequest())throw new IllegalStateException("Native guide seed census is unavailable");return p.nativeGuideOriginalSeedCount;}
        public int nativeGuideRequestedSeedId(){if(!hasNativeGuideOnlyRequest())throw new IllegalStateException("Native guide seed identity is unavailable");return p.nativeGuideSeedId;}
        public long nativeGuideRequestedSeedOffsetRawBits(){if(!hasNativeGuideOnlyRequest())throw new IllegalStateException("Native guide seed identity is unavailable");return p.nativeGuideSeedOffsetBits;}
        public int nativeGuideSeedsNotRequested(){if(!hasNativeGuideOnlyRequest())throw new IllegalStateException("Native guide seed coverage is unavailable");return p.nativeGuideOriginalSeedCount-1;}
        public LegacyEmissionKernel.GuideStatus nativeGuideGreedyStatus(){if(!hasNativeGuideOnlyRequest())throw new IllegalStateException("Native guide comparison status is unavailable");return LegacyEmissionKernel.GuideStatus.NOT_REQUESTED;}
        public boolean nativeGuideComparisonPerformed(){if(!hasGuideScope()||p.guideScope!=LegacyEmissionKernel.GuideScope.BASE_NATIVE_GUIDE_ONLY)throw new IllegalStateException("Native guide comparison status is unavailable");return false;}
        public boolean hasNativeGuideOnlyResult(){return p.nativeGuideComplete;}
        public LegacyEmissionKernel.GuideStatus nativeGuideStatus(){if(!hasNativeGuideOnlyResult())throw new IllegalStateException("Native guide result is unavailable");return p.nativeGuideStatus;}
        public boolean hasNativeGuideOnlyUpper(){return hasNativeGuideOnlyResult()&&p.nativeGuideUpperAvailable;}
        public double nativeGuideOnlyUpper(){if(!hasNativeGuideOnlyUpper())throw new IllegalStateException("Native guide upper is unavailable");return p.nativeGuideUpper;}
        /** Selected first-stage path was flattened and verified; this never publishes a candidate. */
        public boolean firstStageCompleted(){return p.firstStageComplete;}
        public int intervalLeft(){return p.left;}public int intervalRight(){return p.right;}
        public int fromOrdinal(){return p.from;}public int toOrdinal(){return p.to;}
        public int fromNativeId(){return p.fromId;}public int toNativeId(){return p.toId;}
        public long count(Counter c){return p.counts[c.ordinal()];}
        public boolean hasCompetitiveExecution(){return p.competitiveExecution!=null;}
        public LegacyEmissionKernel.CompetitiveExecution competitiveExecution(){if(!hasCompetitiveExecution())throw new IllegalStateException("Competitive proof execution is unavailable");return p.competitiveExecution;}
        public boolean hasGroupedExecution(){return p.groupedExecution!=null;}
        public LegacyEmissionKernel.GroupedExecution groupedExecution(){if(!hasGroupedExecution())throw new IllegalStateException("Grouped execution is unavailable");return p.groupedExecution;}
        public boolean hasWitnessUpper(){return p.currentWitnessAvailable;}
        public double witnessUpper(){if(!hasWitnessUpper())throw new IllegalStateException("Witness upper is unavailable");return p.witnessUpper;}
        public boolean hasCeilingCensus(){return p.ceiling!=null;}
        private void requireCeiling(){if(!hasCeilingCensus())throw new IllegalStateException("Ceiling census is unavailable");}
        /** Only the current seed/stage is retained. Past/future stage facts are unavailable. */
        public boolean hasCeilingStage(){return hasCeilingCensus()&&p.ceilingStageAvailable;}
        private void requireCeilingStage(){if(!hasCeilingStage())throw new IllegalStateException("Current ceiling stage is unavailable");}
        public int ceilingSeed(){requireCeilingStage();return (int)p.ceiling[0];}
        public int ceilingStage(){requireCeilingStage();return (int)p.ceiling[1];}
        public long ceilingCount(CeilingCounter counter){requireCeilingStage();return p.ceiling[counter.word];}
        public long ceilingTotalCount(CeilingCounter counter){requireCeiling();return p.ceiling[CEILING_STAGE_WORDS+counter.ordinal()];}
        public boolean ceilingCounterOverflow(){requireCeiling();return p.ceilingOverflow;}
        public boolean hasCeilingWitness(){return hasCeilingStage()&&(p.ceiling[2]&1)!=0;}
        /** Components0..3 are the stored absolute Q/C/T/K, never reconstructed geometry. */
        public double ceilingWitnessComponent(int component){if(!hasCeilingWitness())throw new IllegalStateException("Ceiling witness is unavailable");return Double.longBitsToDouble(p.ceiling[3+Objects.checkIndex(component,4)]);}
        public double ceilingWitnessUpper(){if(!hasCeilingWitness())throw new IllegalStateException("Ceiling witness is unavailable");return Double.longBitsToDouble(p.ceiling[7]);}
        public boolean hasCeilingMaximum(boolean nativeCap){return hasCeilingStage()&&(p.ceiling[2]&(nativeCap?4:2))!=0;}
        public double ceilingMaximum(boolean nativeCap){if(!hasCeilingMaximum(nativeCap))throw new IllegalStateException("Ceiling maximum is unavailable");return Double.longBitsToDouble(p.ceiling[nativeCap?13:12]);}
        public int maxCompetitiveKeyLabels(){competitiveExecution();return p.maxCompetitiveKeyLabels;}
        public int maxGroupedKeyLabels(){groupedExecution();return p.maxCompetitiveKeyLabels;}
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
