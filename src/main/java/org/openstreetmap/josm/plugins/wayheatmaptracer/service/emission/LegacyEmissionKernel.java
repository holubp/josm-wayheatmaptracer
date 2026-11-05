package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EmissionMapping;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EmissionResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionPeak;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RidgeTracker;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Bounded Legacy compatibility solve and separately named exact proof execution. */
public final class LegacyEmissionKernel {
    /** Bounded first-stage experiment; never selected by the ordinary consumer overloads. */
    public enum CompetitiveExecution { EXACT_VERSIONS_PROOF_V1, EXACT_CUTOFF_PROOF_V1 }
    /** Explicit grouped-objective repair; Q0 is a correctness bound, not source-completion evidence. */
    public enum GroupedExecution { EXACT_VERSIONS_V1, EXACT_Q0_CUTOFF_V1, EXACT_Q0_CEILING_CENSUS_V1, EXACT_Q0_OWNED_NATIVE_GUIDE_V1 }
    /** Exact stored absolute vectors; interval increments and post-smoothing evidence are distinct. */
    public record GroupedStageReference(int seedId,int ordinal,Optional<EmissionResult.Costs> prefix,
            Optional<EmissionResult.Costs> terminal,Optional<EmissionResult.Costs> ordinaryWitness) { }
    public static final class GroupedResult implements AutoCloseable {
        private final GroupedExecution execution;private final EmissionResult emission;
        private final List<GroupedStageReference> stages;private final AttemptMemoryLedger.Owner owner;private boolean closed;
        private final EmissionWorkBudget.Snapshot work;private final long peakRetainedBytes;
        private GroupedResult(GroupedExecution execution,EmissionResult emission,List<GroupedStageReference> stages,
                AttemptMemoryLedger.Owner owner,EmissionWorkBudget.Snapshot work) {
            this.execution=execution;this.emission=emission;this.stages=List.copyOf(stages);this.owner=owner;
            this.work=work;this.peakRetainedBytes=owner.peakBytes();
        }
        private void live(){if(closed)throw new IllegalStateException("Grouped result is closed");}
        public GroupedExecution execution(){live();return execution;}
        public EmissionResult emission(){live();return emission;}
        public List<GroupedStageReference> stages(){live();return stages;}
        /** Authoritative attempt work including sidecar publication; emission.work is the earlier controller boundary. */
        public EmissionWorkBudget.Snapshot work(){live();return work;}
        public long peakRetainedBytes(){live();return peakRetainedBytes;}
        @Override public void close(){if(!closed){closed=true;try{emission.close();}finally{owner.close();}}}
    }
    private record GuideContext(RidgeTracker.OwnedOrdinaryGuide provider,List<GuideStageReference> comparisons) { }
    // The added nullable reference fits the existing512-byte facts shell; old routes allocate no guide arrays.
    private record GroupedFacts(GroupedExecution execution,List<GroupedStageReference> stages,AttemptMemoryLedger.Owner owner,GuideContext guide) {
        private GroupedFacts(GroupedExecution execution,List<GroupedStageReference> stages,AttemptMemoryLedger.Owner owner){this(execution,stages,owner,null);}
    }
    public enum GuideScope { FULL_GROUPED, BASE_WITNESS_ONLY, BASE_NATIVE_GUIDE_ONLY }
    public enum GuideStatus { FEASIBLE, ADMISSION_REFUSED, NO_POSITIVE_FINITE_VECTOR, NO_OWNED_ANCHOR,
        OWNERSHIP_UNAVAILABLE, NOT_REQUESTED, NO_ORIGINAL_SEED, NON_POSITIVE_UPPER }
    public enum GuideChoice { GREEDY, GUIDED, NONE }
    public record GuideOwnedStep(int originalRow,RidgeTracker.GuideKind kind,int nativeStateId) { }
    public record GuideStageReference(int seedId,int ordinal,Optional<EmissionResult.Costs> greedy,GuideStatus greedyStatus,
            Optional<EmissionResult.Costs> guided,GuideStatus guidedStatus,GuideChoice choice,
            List<GuideOwnedStep> greedySteps,List<GuideOwnedStep> guidedSteps,boolean hasProposalScore,double proposalScore) { }
    private record GuideReplay(Optional<EmissionResult.Costs> costs,GuideStatus status,List<GuideOwnedStep> steps) { }
    public enum NativeGuideCoverage { FIRST_ORIGINAL_SEED_ONLY, NO_ORIGINAL_SEEDS }
    public record NativeGuideSeedIdentity(int id,long offsetRawBits) {
        public NativeGuideSeedIdentity {
            if (id < 0 || !Double.isFinite(Double.longBitsToDouble(offsetRawBits)))
                throw new IllegalArgumentException("Native guide seed identity is invalid");
        }
        public double offsetRasterPixels() { return Double.longBitsToDouble(offsetRawBits); }
    }
    /** One actual native-guided BASE route; greedy comparison and all other seeds are explicitly absent. */
    public record NativeGuideOnlyDiagnostic(int originalSeedCount,Optional<NativeGuideSeedIdentity> requestedSeed,
            NativeGuideCoverage coverage,GuideStatus greedyStatus,boolean comparisonPerformed,
            GuideStatus guidedStatus,Optional<EmissionResult.Costs> guidedCosts,List<GuideOwnedStep> guidedSteps,
            java.util.OptionalDouble witnessUpper) {
        public NativeGuideOnlyDiagnostic {
            Objects.requireNonNull(requestedSeed);Objects.requireNonNull(coverage);Objects.requireNonNull(greedyStatus);
            Objects.requireNonNull(guidedStatus);Objects.requireNonNull(guidedCosts);Objects.requireNonNull(guidedSteps);
            Objects.requireNonNull(witnessUpper);guidedSteps=List.copyOf(guidedSteps);
            if(originalSeedCount<0||greedyStatus!=GuideStatus.NOT_REQUESTED||comparisonPerformed)
                throw new IllegalArgumentException("Native guide-only comparison status is invalid");
            if(originalSeedCount==0) {
                if(requestedSeed.isPresent()||coverage!=NativeGuideCoverage.NO_ORIGINAL_SEEDS
                        ||guidedStatus!=GuideStatus.NO_ORIGINAL_SEED||guidedCosts.isPresent()||witnessUpper.isPresent()||!guidedSteps.isEmpty())
                    throw new IllegalArgumentException("Empty native seed coverage is inconsistent");
            } else {
                var seed=requestedSeed.orElseThrow();
                if(seed.id()!=0||coverage!=NativeGuideCoverage.FIRST_ORIGINAL_SEED_ONLY)
                    throw new IllegalArgumentException("Native guide-only must retain the first original seed");
                if(guidedCosts.isPresent()) {
                    var costs=guidedCosts.orElseThrow();double upper=costs.total();
                    if(guidedStatus!=GuideStatus.FEASIBLE||!witnessUpper.isPresent()
                            ||!Double.isFinite(upper)||upper<0.0
                            ||Double.doubleToRawLongBits(upper)!=Double.doubleToRawLongBits(witnessUpper.getAsDouble()))
                        throw new IllegalArgumentException("Native guide-only upper is not a finite nonnegative certified route total");
                } else if(witnessUpper.isPresent()||guidedStatus==GuideStatus.FEASIBLE) {
                    throw new IllegalArgumentException("Unavailable native guide cannot carry an upper");
                }
            }
        }
    }
    /** New-route sidecar; witness-only never exposes an optimizer winner, intervals or smoothed geometry. */
    public static final class OwnedGuideResult implements AutoCloseable {
        private final GuideScope scope;private final GroupedResult grouped;private final List<GuideStageReference> comparisons;
        private final NativeGuideOnlyDiagnostic nativeGuideOnly;
        private final RidgeTracker.OwnedGuideLease guideLifetime;private final AttemptMemoryLedger.Owner owner;
        private final SampledEmissionInput.ResultLease lease;private final EmissionWorkBudget.Snapshot work;private final long peak;private final String modelIdentity;
        private boolean closed;
        private OwnedGuideResult(GuideScope scope,GroupedResult grouped,List<GuideStageReference> comparisons,
                NativeGuideOnlyDiagnostic nativeGuideOnly,RidgeTracker.OwnedGuideLease guideLifetime,
                AttemptMemoryLedger.Owner owner,SampledEmissionInput.ResultLease lease,EmissionWorkBudget.Snapshot work,String modelIdentity) {
            this.scope=scope;this.grouped=grouped;this.comparisons=List.copyOf(comparisons);this.nativeGuideOnly=nativeGuideOnly;
            this.guideLifetime=guideLifetime;this.owner=owner;this.lease=lease;this.work=work;peak=owner.peakBytes();this.modelIdentity=modelIdentity;
        }
        private void live(){if(closed||owner.isClosed()||guideLifetime.isClosed())throw new IllegalStateException("Owned guide result is closed");}
        public GuideScope scope(){live();return scope;}public Optional<GroupedResult> grouped(){live();return Optional.ofNullable(grouped);}
        public List<GuideStageReference> comparisons(){live();return comparisons;}public EmissionWorkBudget.Snapshot work(){live();return work;}
        public Optional<NativeGuideOnlyDiagnostic> nativeGuideOnly(){live();return Optional.ofNullable(nativeGuideOnly);}
        public long peakRetainedBytes(){live();return peak;}public String modelIdentity(){live();return modelIdentity;}
        @Override public void close(){if(!closed){closed=true;try{if(grouped!=null)grouped.close();}finally{try{guideLifetime.close();}finally{try{owner.close();}finally{if(lease!=null)lease.close();}}}}}
    }

    /** An owned proof-stage path, explicitly not a multi-stage/smoothed emission result. */
    public static final class ObservedSeedProof implements AutoCloseable {
        private final CompetitiveExecution execution;
        private final List<EmissionResult.Vertex> vertices,witnessVertices;
        private final List<EmissionResult.Arc> arcs;
        private final EmissionResult.Costs costs;
        private final Optional<EmissionResult.Costs> witnessCosts;
        private final NativeConnectorEvaluator evaluator;
        private final AttemptMemoryLedger.Owner owner;
        private final SampledEmissionInput.ResultLease lease;
        private boolean closed;
        private ObservedSeedProof(CompetitiveExecution execution,List<EmissionResult.Vertex> vertices,
                List<EmissionResult.Arc> arcs,EmissionResult.Costs costs,List<EmissionResult.Vertex> witnessVertices,
                Optional<EmissionResult.Costs> witnessCosts,NativeConnectorEvaluator evaluator,
                AttemptMemoryLedger.Owner owner,SampledEmissionInput.ResultLease lease) {
            this.execution=execution;this.vertices=vertices;this.arcs=arcs;this.costs=costs;
            this.witnessVertices=witnessVertices;this.witnessCosts=witnessCosts;
            this.evaluator=evaluator;this.owner=owner;this.lease=lease;
        }
        private void live(){if(closed)throw new IllegalStateException("Proof-stage result is closed");}
        public CompetitiveExecution execution(){live();return execution;}
        public List<EmissionResult.Vertex> vertices(){live();return vertices;}
        public List<EmissionResult.Arc> arcs(){live();return arcs;}
        public EmissionResult.Costs costs(){live();return costs;}
        public List<EmissionResult.Vertex> witnessVertices(){live();return witnessVertices;}
        public Optional<EmissionResult.Costs> witnessCosts(){live();return witnessCosts;}
        @Override public void close(){if(!closed){closed=true;evaluator.close();owner.close();lease.close();}}
    }

    /**
     * Opt-in exactness/performance proof only: one seed, 2–3 all-observed rows, no
     * protected events/intervals, and at most four structurally possible complete paths.
     */
    public ObservedSeedProof solveObservedSeedProof(SampledEmissionInput input,List<CrossSectionProfile> profiles,
            double seedOffset,CancellationProbe cancellation,CompetitiveExecution execution,LegacyEmissionProgress progress) {
        Objects.requireNonNull(input);Objects.requireNonNull(profiles);Objects.requireNonNull(cancellation);Objects.requireNonNull(execution);
        if(progress==null)return observedSeedProofBody(input,profiles,seedOffset,cancellation,execution,null);
        var query=NativeConnectorEvaluator.QueryPolicy.ZERO_GUARDED_LOCAL_V1;
        var nativeExecution=NativeConnectorEvaluator.ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1;
        progress.requireCompatible(input,query,nativeExecution);
        try {
            progress.begin(input,query,nativeExecution);progress.competitiveBegin(execution);
            return observedSeedProofBody(input,profiles,seedOffset,cancellation,execution,progress);
        } catch(RuntimeException failure){progress.seal(failure);throw failure;}
    }
    private ObservedSeedProof observedSeedProofBody(SampledEmissionInput input,List<CrossSectionProfile> profiles,
            double seedOffset,CancellationProbe cancellation,CompetitiveExecution execution,LegacyEmissionProgress progress) {
        input.requireOpenAndUnchanged();
        var binding=input.legacyBinding().orElseThrow(()->new IllegalArgumentException("Original Legacy binding is required"));
        if(binding.observations().size()<2||binding.observations().size()>3||!binding.protectedOccurrences().isEmpty()
                ||binding.observations().stream().anyMatch(row->row.sourceClass()!=NativeConnectorEvaluator.SourceClass.OBSERVED))
            throw new IllegalArgumentException("Observed proof slice requires 2–3 OBSERVED rows and no protected events");
        var query=NativeConnectorEvaluator.QueryPolicy.ZERO_GUARDED_LOCAL_V1;
        var nativeExecution=NativeConnectorEvaluator.ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1;
        var budget=input.workBudget();var lease=input.retainResultLifetime();AttemptMemoryLedger.Owner owner=null;
        NativeConnectorEvaluator evaluator=null;
        try {
            owner=input.openExecutionOwner("legacy-observed-competitive-proof");
            var domain=new LegacyEmissionDomain(binding,profiles,List.of(seedOffset),budget,owner);
            if(progress!=null)progress.domainComplete(1);
            long paths=domain.observedAt(0).size();
            for(int row=1;row<domain.rowCount();row++) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION,1);
                if(domain.unsupported(row))throw new IllegalArgumentException("Proof slice cannot construct carries");
                paths=Math.multiplyExact(paths,domain.observedAt(row).size());
            }
            if(domain.unsupported(0)||paths*(domain.rowCount()==3?2:1)>4)
                throw new IllegalArgumentException("Proof slice has more than four structural paths");
            if(progress!=null){progress.seedBegin(0);progress.phase(LegacyEmissionProgress.Phase.BASE,0,-1,-1);progress.prepareMutation();}
            var inventory=inventory(domain,budget,owner,null);
            if(progress!=null)progress.inventoryComplete(inventory.evaluatorStates().size());
            evaluator=evaluator(input,domain,inventory,query,nativeExecution,progress);
            double pitch=input.context().nativePitchMeters().orElseThrow(()->new UnavailableException(UnavailableReason.NATIVE_PITCH));
            if(progress!=null)progress.witnessBegin();
            var seed=domain.seeds().get(0);
            Node witness=null;
            for(int row=0;row<domain.rowCount();row++) {
                cancellation.checkpoint();input.requireOpenAndUnchanged();
                LegacyEmissionDomain.State chosen=null;
                for(var state:domain.observedAt(row)) {
                    budget.charge(EmissionWorkBudget.Operation.PREPARATION,1);
                    if(chosen==null||Math.abs(state.offsetRasterPixels()-seedOffset)<Math.abs(chosen.offsetRasterPixels()-seedOffset)
                            ||Math.abs(state.offsetRasterPixels()-seedOffset)==Math.abs(chosen.offsetRasterPixels()-seedOffset)
                                &&state.nativeStateId()<chosen.nativeStateId())chosen=state;
                }
                witness=row==0?initialProofNode(input,domain,seed,chosen,owner,budget)
                    :advance(input,domain,inventory,witness,chosen,evaluator,owner,cancellation,0,pitch);
                if(witness==null)break;
            }
            double upper=certifiedWitnessUpper(witness);
            boolean certified=Double.isFinite(upper);
            if(certified){pinChain(witness,budget);if(progress!=null)progress.witnessCertified(upper);}
            else if(witness!=null){releaseUnretained(witness,budget);witness=null;}
            Node best=solveExactObservedBase(input,domain,seed,inventory,evaluator,owner,cancellation,pitch,execution,upper);
            if(best==null||!finiteComponents(best))throw new UnavailableException(UnavailableReason.NO_ADMITTED_PATH);
            if(best.key.lastPositive()==null)throw new UnavailableException(UnavailableReason.NO_POSITIVE_PHYSICAL_LEG);
            try(var selected=flatten(best,budget,owner)) {
                verifyCosts(selected.arcs(),best,budget);
                budget.charge(EmissionWorkBudget.Operation.ALLOCATION,6);
                owner.reserve(Math.addExact(2048,Math.multiplyExact(1024L,domain.rowCount())));
                var vertices=selected.points().stream().map(LegacyEmissionKernel::vertex).toList();
                List<EmissionResult.Vertex> witnessVertices=List.of();
                if(certified)try(var ordinary=flatten(witness,budget,owner)) {
                    verifyCosts(ordinary.arcs(),witness,budget);
                    witnessVertices=ordinary.points().stream().map(LegacyEmissionKernel::vertex).toList();
                }
                var result=new ObservedSeedProof(execution,vertices,arcResults(selected.arcs()),
                    new EmissionResult.Costs(best.q,best.c,best.t,best.k),witnessVertices,
                    certified?Optional.of(new EmissionResult.Costs(witness.q,witness.c,witness.t,witness.k)):Optional.empty(),evaluator,owner,lease);
                cancellation.checkpoint();input.requireOpenAndUnchanged();
                if(progress!=null){progress.firstStageComplete();progress.seedComplete();progress.phaseComplete();progress.seal(null);}
                return result;
            }
        } catch(RuntimeException failure) {
            if(evaluator!=null)evaluator.close();if(owner!=null)owner.close();lease.close();
            if(failure instanceof CancellationException||failure instanceof EmissionWorkBudget.LimitExceededException
                    ||failure instanceof AttemptMemoryLedger.ResourceLimitException||failure instanceof LegacyEmissionDomain.StateFamilyLimitException
                    ||failure instanceof NativeConnectorEvaluator.StateFamilyLimitException)input.recordTerminalExecutionFailure(failure);
            if(progress!=null)progress.seal(failure);throw failure;
        }
    }

    private static boolean finiteComponents(Node node) {
        return finiteNonnegative(node.q)&&finiteNonnegative(node.c)&&finiteNonnegative(node.t)&&finiteNonnegative(node.k)&&Double.isFinite(node.total());
    }
    private static double certifiedWitnessUpper(Node node) {
        return node!=null&&node.key.lastPositive()!=null&&finiteComponents(node)?node.total():Double.POSITIVE_INFINITY;
    }
    private static boolean finiteNonnegative(double value){return Double.isFinite(value)&&value>=0;}
    private static Node initialProofNode(SampledEmissionInput input,LegacyEmissionDomain domain,LegacyEmissionDomain.Seed seed,
            LegacyEmissionDomain.State first,AttemptMemoryLedger.Owner owner,EmissionWorkBudget budget) {
        budget.charge(EmissionWorkBudget.Operation.PAIR_INITIALIZATION,1);
        double departure=Math.abs(first.offsetRasterPixels()-seed.offsetRasterPixels());
        var key=new Key(first,new ScalarMemory(-1,seed.offsetRasterPixels(),0,first.offsetRasterPixels(),
            first.offsetRasterPixels()-seed.offsetRasterPixels(),first.offsetOriginId()),null,null,new PhaseContext(0,null));
        return allocateNode(owner,budget,null,key,List.of(new PointDraft(new EmissionMapping.ObservationVertex(0),first,
            domain.metricPoint(first,input.context()))),List.of(),0,.16*departure,.12*departure,0,0,seed.id());
    }

    private static final class ExactLabel {
        final Node node;final AttemptMemoryLedger.Reservation memory;ExactLabel next;
        ExactLabel(Node node,AttemptMemoryLedger.Reservation memory,ExactLabel next){this.node=node;this.memory=memory;this.next=next;}
    }
    private static final class ExactFront {
        final AttemptMemoryLedger.Reservation memory;ExactLabel first;int size;
        ExactFront(AttemptMemoryLedger.Reservation memory){this.memory=memory;}
    }

    private Node solveExactObservedBase(SampledEmissionInput input,LegacyEmissionDomain domain,LegacyEmissionDomain.Seed seed,
            Inventory inventory,NativeConnectorEvaluator evaluator,AttemptMemoryLedger.Owner owner,
            CancellationProbe cancellation,double pitch,CompetitiveExecution execution,double upper) {
        var budget=input.workBudget();var progress=evaluator.progress();int n=domain.rowCount();
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION,n+1L);
        try(var layerMemory=owner.reserve(256L*n)) {
            var layers=new ArrayList<Map<Key,ExactFront>>(n);
            for(int row=0;row<n;row++)layers.add(new LinkedHashMap<>());
            for(var first:domain.observedAt(0))insertExact(layers.get(0),initialProofNode(input,domain,seed,first,owner,budget),budget,owner,progress);
            for(int row=0;row<n-1;row++) {
                if(progress!=null)progress.row(row);
                for(var front:layers.get(row).values())for(var label=front.first;label!=null;label=label.next) {
                    Node from=label.node;
                    budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP,1);cancellation.checkpoint();
                    for(int toRow=row+1;toRow<n;toRow++) {
                        budget.charge(EmissionWorkBudget.Operation.PREPARATION,1);
                        if(toRow>row+1&&sourceSpanUpper(domain,input.context(),row,toRow)>20)break;
                        for(var to:domain.observedAt(toRow)) {
                            budget.charge(EmissionWorkBudget.Operation.TRANSITION,1);
                            if(toRow>row+1&&execution==CompetitiveExecution.EXACT_CUTOFF_PROOF_V1
                                    &&deferOptional(input,domain,from,to,pitch,upper,budget,cancellation,progress))continue;
                            Node candidate=advance(input,domain,inventory,from,to,evaluator,owner,cancellation,0,pitch);
                            if(candidate!=null)insertExact(layers.get(toRow),candidate,budget,owner,progress);
                        }
                    }
                }
            }
            Node best=null;
            for(var front:layers.get(n-1).values())for(var label=front.first;label!=null;label=label.next)
                if(finiteComponents(label.node)&&better(label.node,best,budget,owner))best=label.node;
            if(best!=null)pinChain(best,budget);
            for(int row=n-1;row>=0;row--)for(var front:layers.get(row).values())for(var label=front.first;label!=null;label=label.next) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION,1);label.node.inLayer=false;
            }
            for(int row=n-1;row>=0;row--) {
                for(var front:layers.get(row).values()) {
                    for(var label=front.first;label!=null;) {
                        releaseUnretained(label.node,budget);var next=label.next;label.memory.close();label=next;
                    }
                    front.first=null;front.memory.close();
                }
                layers.get(row).clear();
            }
            return best;
        }
    }

    /** Native candidate C/T/K with Q lower0; no integral, Mode, threshold or admission is supplied. */
    private static boolean deferOptional(SampledEmissionInput input,LegacyEmissionDomain domain,Node from,
            LegacyEmissionDomain.State to,double pitch,double upper,EmissionWorkBudget budget,
            CancellationProbe cancellation,LegacyEmissionProgress progress) {
        return deferOptional(input,domain,from,to,pitch,upper,budget,cancellation,progress,false);
    }
    private static boolean deferOptional(SampledEmissionInput input,LegacyEmissionDomain domain,Node from,
            LegacyEmissionDomain.State to,double pitch,double upper,EmissionWorkBudget budget,
            CancellationProbe cancellation,LegacyEmissionProgress progress,boolean census) {
        input.requireOpenAndUnchanged();cancellation.checkpoint();
        budget.charge(EmissionWorkBudget.Operation.PREPARATION,32);
        if(progress!=null){progress.edge(from.key.last().originalRow(),to.originalRow(),from.key.last().nativeStateId(),to.nativeStateId());
            progress.event(LegacyEmissionProgress.Counter.COMPONENT_BOUND_PREPARATIONS);}
        var a=from.key.last();var pa=domain.metricPoint(a,input.context());var pb=domain.metricPoint(to,input.context());
        // No invented orientation for a zero edge: retain the full native admission path.
        if(pa.distanceTo(pb)==0){
            if(census&&progress!=null){progress.prepareCeilingObservation();cancellation.checkpoint();progress.ceilingObservation(input,true,Double.NaN,Double.NaN,false);}
            return false;
        }
        double slope=(to.offsetRasterPixels()-a.offsetRasterPixels())/(to.originalRow()-a.originalRow());
        double c=.16*Math.abs(to.offsetRasterPixels()-a.offsetRasterPixels());
        double t=.12*Math.abs(slope-from.key.scalar().lastSlope());
        double k=turn(from.key.lastPositive(),pa,pb,pitch);
        double qLower=from.q+0.0,cLower=from.c+c,tLower=from.t+t,kLower=from.k+(0.0+k);
        double lower=qLower+cLower+tLower+kLower;
        boolean bounded=finiteNonnegative(qLower)&&finiteNonnegative(cLower)&&finiteNonnegative(tLower)&&finiteNonnegative(kLower);
        if(census&&progress!=null) {
            progress.prepareCeilingObservation();
            double span=sourceChainage(domain,input.context(),to.originalRow())-sourceChainage(domain,input.context(),a.originalRow());
            double cap=ceilingLower(from.q,cLower,tLower,kLower,span,input.context().nativePitchMeters().orElse(Double.NaN));
            cancellation.checkpoint();progress.ceilingObservation(input,false,bounded?lower:Double.NaN,cap,bounded&&strictCutoff(lower,upper));
        }
        input.requireOpenAndUnchanged();cancellation.checkpoint();
        if(!bounded||!strictCutoff(lower,upper))return false;
        // Admit observer bookkeeping before the last freshness check; a callback at this
        // charge must not publish a deferral for a now-stale producer. Mutation is prepaid.
        if(progress!=null)progress.prepareMutation();
        cancellation.checkpoint();input.requireOpenAndUnchanged();
        if(progress!=null)progress.mutation(LegacyEmissionProgress.Counter.STRICT_CERTIFICATE_DEFERRALS);
        return true;
    }
    private static boolean strictCutoff(double lower,double upper) {
        return finiteNonnegative(lower)&&Double.isFinite(upper)&&upper>=0&&lower>upper;
    }
    /** Upper envelope on the one-edge scalar-strengthened lower, not measured Q or a terminal upper. */
    static double ceilingLower(double prefixQ,double cLower,double tLower,double kLower,double sourceSpan,double nativePitch) {
        if(!finiteNonnegative(prefixQ)||!finiteNonnegative(cLower)||!finiteNonnegative(tLower)||!finiteNonnegative(kLower)
                ||!finiteNonnegative(sourceSpan)||!Double.isFinite(nativePitch)||nativePitch<=0)return Double.NaN;
        double qCap=sourceSpan/nativePitch;
        if(!finiteNonnegative(qCap))return Double.NaN;
        double cap=((prefixQ+qCap)+cLower)+tLower+kLower;
        return finiteNonnegative(cap)?cap:Double.NaN;
    }

    private static void insertExact(Map<Key,ExactFront> layer,Node candidate,EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner,LegacyEmissionProgress progress) {
        budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP,1);
        var front=layer.get(candidate.key);
        if(front==null) {
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);
            // Includes the map's retained first Key/scalar/phase/physical wrappers even if
            // its original Node is later dominated and released.
            var memory=owner.reserve(512);front=new ExactFront(memory);memory.adopt(front);layer.put(candidate.key,front);
        }
        for(var label=front.first;label!=null;label=label.next)if(dominates(label.node,candidate,budget,owner)) {
            releaseUnretained(candidate,budget);return;
        }
        ExactLabel previous=null;
        for(var label=front.first;label!=null;) {
            var next=label.next;
            if(dominates(candidate,label.node,budget,owner)) {
                if(previous==null)front.first=next;else previous.next=next;
                front.size--;label.node.inLayer=false;releaseUnretained(label.node,budget);label.memory.close();
            } else previous=label;
            label=next;
        }
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);
        var memory=owner.reserve(96);var label=new ExactLabel(candidate,memory,front.first);memory.adopt(label);
        front.first=label;front.size++;candidate.inLayer=true;
        if(progress!=null)progress.competitiveFront(front.size);
    }
    private static boolean dominates(Node left,Node right,EmissionWorkBudget budget,AttemptMemoryLedger.Owner owner) {
        budget.charge(EmissionWorkBudget.Operation.TIE_COMPARISON,1);
        budget.charge(EmissionWorkBudget.Operation.PREPARATION,16);
        // Equality is the complete future Key and this front belongs to one seed/stage.
        // In particular, equal geometry alone cannot replace cursor/receipt identity.
        if(!left.key.equals(right.key)||left.seedId!=right.seedId||!finiteComponents(left)||!finiteComponents(right)
                ||negativeZero(left)||negativeZero(right)||left.q>right.q||left.c>right.c||left.t>right.t||left.k>right.k)return false;
        int consumed=Integer.compare(left.consumed,right.consumed);
        // Strict row order and a common current row prohibit a proper-prefix lex reversal
        // when the same suffix is appended. Component strictness alone is insufficient.
        return consumed<0||consumed==0&&compareTie(left,right,budget,owner)<=0;
    }
    private static boolean negativeZero(Node node) {
        return Double.doubleToRawLongBits(node.q)==Long.MIN_VALUE||Double.doubleToRawLongBits(node.c)==Long.MIN_VALUE
            ||Double.doubleToRawLongBits(node.t)==Long.MIN_VALUE||Double.doubleToRawLongBits(node.k)==Long.MIN_VALUE;
    }
    public record AnchorProposal(int originalRow, CrossSectionPeak peakOwner) {
        public AnchorProposal {
            if (originalRow < 0 || peakOwner == null) throw new IllegalArgumentException("Anchor owner is required");
        }
    }
    @FunctionalInterface public interface Smoother {
        List<Double> smooth(List<CrossSectionProfile> profiles, List<Double> offsets, List<Double> intensities);
    }
    public enum UnavailableReason {
        ORIGINAL_ROW_MAPPING, NATIVE_REFERENCE_ADAPTER, MOVABLE_BOUNDARY_ASSIGNMENT,
        NO_ADMITTED_PATH, NO_POSITIVE_PHYSICAL_LEG, STATE_FAMILY_LIMIT, NATIVE_PITCH, FINAL_AFFINITY_CONFLICT
    }
    public static final class UnavailableException extends RuntimeException {
        private final UnavailableReason reason;
        public UnavailableException(UnavailableReason reason) {
            super("Legacy optional emission unavailable: " + reason);
            this.reason = reason;
        }
        public UnavailableReason reason() { return reason; }
    }

    private record PhysicalLeg(MetricPoint from, MetricPoint to,
            Object fromNativeIdentity, Object toNativeIdentity,
            ReferenceIdentity nativeReference) {
        double length() { return from.distanceTo(to); }
    }
    private static final class ReferenceIdentity {
        final Object value;
        ReferenceIdentity(Object value) { this.value = Objects.requireNonNull(value); }
        @Override public boolean equals(Object other) {
            return other instanceof ReferenceIdentity reference && reference.value == value;
        }
        @Override public int hashCode() { return System.identityHashCode(value); }
    }
    private record ScalarMemory(int previousRow, double previousOffset, int lastRow,
            double lastOffset, double lastSlope, int offsetOriginId) { }
    private record PhaseContext(int stageOrdinal, String lastBoundaryOccurrenceId) { }
    private record Key(LegacyEmissionDomain.State last, ScalarMemory scalar,
            PhysicalLeg lastPositive, ReferenceIdentity incomingCursor,
            PhaseContext phase) { }
    private record ArcDraft(EmissionResult.ArcKind kind, MetricPoint from, MetricPoint to,
            double sourceSpan, double q, double c, double t, double k,
            Optional<Double> mean, Optional<NativeConnectorEvaluator.Receipt> receipt,
            Object ordinaryCursor) {
        ArcDraft withCursor(Object cursor) {
            return new ArcDraft(kind, from, to, sourceSpan, q, c, t, k, mean, receipt, cursor);
        }
    }
    private record PointDraft(EmissionMapping.VertexOwner owner, LegacyEmissionDomain.State state,
            MetricPoint position) { }
    private static final class Node {
        final Node previous;
        final Key key;
        final List<PointDraft> addedPoints;
        final List<ArcDraft> addedArcs;
        final double q, c, t, k;
        final double seedOffset;
        final int seedId;
        final int consumed;
        final int depth;
        final AttemptMemoryLedger.Reservation memory;
        int retainedChildren;
        boolean inLayer;
        int pinCount;
        boolean released;
        Node(Node previous, Key key, List<PointDraft> addedPoints, List<ArcDraft> addedArcs,
                double q, double c, double t, double k, int consumed,
                AttemptMemoryLedger.Reservation memory, int initialSeedId) {
            this.previous = previous; this.key = key;
            this.addedPoints = addedPoints; this.addedArcs = addedArcs;
            this.q = q; this.c = c; this.t = t; this.k = k;
            this.seedOffset = previous == null ? key.scalar().previousOffset()
                : previous.seedOffset;
            this.seedId = previous == null ? initialSeedId : previous.seedId;
            this.consumed = consumed; this.depth = previous == null ? 1 : previous.depth + 1;
            this.memory = memory;
            if (previous != null) previous.retainedChildren++;
        }
        double total() { return q + c + t + k; }
    }

    /** Runs every original seed under one retained work budget and memory owner. */
    public EmissionResult solve(SampledEmissionInput input, List<CrossSectionProfile> suppressed,
            List<Double> seeds, List<AnchorProposal> proposals, Smoother smoother,
            CancellationProbe cancellation) {
        return solve(input, suppressed, seeds, proposals, smoother, cancellation,
            NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
            NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1);
    }

    /** Applies one caller-explicit existing evaluator policy under the same attempt and numerical objective. */
    public EmissionResult solve(SampledEmissionInput input, List<CrossSectionProfile> suppressed,
            List<Double> seeds, List<AnchorProposal> proposals, Smoother smoother,
            CancellationProbe cancellation, NativeConnectorEvaluator.QueryPolicy queryPolicy,
            NativeConnectorEvaluator.ExecutionPolicy executionPolicy) {
        return solveBody(input, suppressed, seeds, proposals, smoother, cancellation, queryPolicy, executionPolicy, false, null);
    }

    /** Versioned measured-mode affinity experiment; original v2 solve remains unchanged. */
    public EmissionResult solveMeasuredAffinity(SampledEmissionInput input, List<CrossSectionProfile> suppressed,
            List<Double> seeds, List<AnchorProposal> proposals, Smoother smoother,
            CancellationProbe cancellation) {
        return solve(input, suppressed, seeds, proposals, smoother, cancellation,
            NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
            NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1, true, null);
    }
    /** Explicit local managed connector experiment; creates no supplemental state family. */
    public EmissionResult solveManagedChartAffinity(SampledEmissionInput input,List<CrossSectionProfile> suppressed,
            List<Double> seeds,List<AnchorProposal> proposals,Smoother smoother,CancellationProbe cancellation){
        return solveBody(input,suppressed,seeds,proposals,smoother,cancellation,
            NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1,
            NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1,false,true,null);
    }

    private EmissionResult solve(SampledEmissionInput input, List<CrossSectionProfile> suppressed,
            List<Double> seeds, List<AnchorProposal> proposals, Smoother smoother,
            CancellationProbe cancellation, NativeConnectorEvaluator.QueryPolicy queryPolicy,
            NativeConnectorEvaluator.ExecutionPolicy executionPolicy, boolean measuredAffinity, LegacyEmissionProgress progress) {
        return solveBody(input,suppressed,seeds,proposals,smoother,cancellation,queryPolicy,executionPolicy,measuredAffinity,progress);
    }
    /** Explicit bounded diagnostics; null preserves the original work and allocation path. */
    public EmissionResult solve(SampledEmissionInput input,List<CrossSectionProfile> suppressed,
            List<Double> seeds,List<AnchorProposal> proposals,Smoother smoother,CancellationProbe cancellation,
            NativeConnectorEvaluator.QueryPolicy queryPolicy,NativeConnectorEvaluator.ExecutionPolicy executionPolicy,
            LegacyEmissionProgress progress) {
        if(progress==null)return solveBody(input,suppressed,seeds,proposals,smoother,cancellation,queryPolicy,executionPolicy,false,null);
        progress.requireCompatible(input,queryPolicy,executionPolicy);
        try {
            progress.begin(input,queryPolicy,executionPolicy);
            var result=solveBody(input,suppressed,seeds,proposals,smoother,cancellation,queryPolicy,executionPolicy,false,progress);
            progress.seal(null);return result;
        } catch(RuntimeException failure){progress.seal(failure);throw failure;}
    }
    private EmissionResult solveBody(SampledEmissionInput input,List<CrossSectionProfile> suppressed,
            List<Double> seeds,List<AnchorProposal> proposals,Smoother smoother,CancellationProbe cancellation,
            NativeConnectorEvaluator.QueryPolicy queryPolicy,NativeConnectorEvaluator.ExecutionPolicy executionPolicy,
            boolean measuredAffinity, LegacyEmissionProgress progress) {
        return solveBody(input,suppressed,seeds,proposals,smoother,cancellation,queryPolicy,executionPolicy,measuredAffinity,false,progress,null);
    }
    private EmissionResult solveBody(SampledEmissionInput input,List<CrossSectionProfile> suppressed,
            List<Double> seeds,List<AnchorProposal> proposals,Smoother smoother,CancellationProbe cancellation,
            NativeConnectorEvaluator.QueryPolicy queryPolicy,NativeConnectorEvaluator.ExecutionPolicy executionPolicy,
            boolean measuredAffinity,boolean managedAffinity,LegacyEmissionProgress progress){
        return solveBody(input,suppressed,seeds,proposals,smoother,cancellation,queryPolicy,executionPolicy,measuredAffinity,managedAffinity,progress,null);
    }

    /** Opt-in all-stage grouped execution. Compatibility, affinity and UI consumers never select it implicitly. */
    public GroupedResult solveGrouped(SampledEmissionInput input,List<CrossSectionProfile> suppressed,
            List<Double> seeds,List<AnchorProposal> proposals,Smoother smoother,CancellationProbe cancellation,
            NativeConnectorEvaluator.QueryPolicy queryPolicy,NativeConnectorEvaluator.ExecutionPolicy executionPolicy,
            GroupedExecution grouped,LegacyEmissionProgress progress) {
        Objects.requireNonNull(grouped);
        if(grouped==GroupedExecution.EXACT_Q0_OWNED_NATIVE_GUIDE_V1)throw new IllegalArgumentException("Owned guide preparation is required");
        if(progress!=null)progress.requireCompatible(input,queryPolicy,executionPolicy);
        AttemptMemoryLedger.Owner factsOwner=null;EmissionResult emission=null;
        try {
            if(progress!=null){progress.begin(input,queryPolicy,executionPolicy);progress.groupedBegin(grouped);}
            input.requireOpenAndUnchanged();factsOwner=input.openExecutionOwner("legacy-grouped-provenance");
            var budget=input.workBudget();budget.charge(EmissionWorkBudget.Operation.ALLOCATION,3);factsOwner.reserve(512);
            var facts=new GroupedFacts(grouped,new ArrayList<>(),factsOwner);
            emission=solveBody(input,suppressed,seeds,proposals,smoother,cancellation,queryPolicy,executionPolicy,false,progress,facts);
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION,6);factsOwner.reserve(1024L+32L*facts.stages().size());
            cancellation.checkpoint();input.requireOpenAndUnchanged();
            var result=new GroupedResult(grouped,emission,facts.stages(),factsOwner,budget.snapshot());
            if(progress!=null)progress.seal(null);return result;
        } catch(RuntimeException failure){
            if(emission!=null)emission.close();if(factsOwner!=null)factsOwner.close();
            if(failure instanceof CancellationException||failure instanceof EmissionWorkBudget.LimitExceededException
                    ||failure instanceof AttemptMemoryLedger.ResourceLimitException)input.recordTerminalExecutionFailure(failure);
            if(progress!=null) {
                boolean seal=true;
                if(grouped==GroupedExecution.EXACT_Q0_CEILING_CENSUS_V1) {
                    try {progress.requireCompatible(input,queryPolicy,executionPolicy);}
                    catch(IllegalArgumentException revoked){seal=false;}
                }
                if(seal)progress.seal(failure);
            }
            throw failure;
        }
    }
    /** Explicit composition; old grouped/affine/default policies keep their own entry and publication. */
    public GroupedResult solveManagedGroupedChartAffinity(SampledEmissionInput input,List<CrossSectionProfile> suppressed,
            List<Double> seeds,List<AnchorProposal> proposals,Smoother smoother,CancellationProbe cancellation,
            NativeConnectorEvaluator.QueryPolicy queryPolicy,NativeConnectorEvaluator.ExecutionPolicy executionPolicy,
            GroupedExecution grouped,LegacyEmissionProgress progress){
        Objects.requireNonNull(grouped);
        if(grouped==GroupedExecution.EXACT_Q0_OWNED_NATIVE_GUIDE_V1)throw new IllegalArgumentException("Owned guide preparation is required");
        if(progress!=null)progress.requireCompatible(input,queryPolicy,executionPolicy);
        AttemptMemoryLedger.Owner factsOwner=null;EmissionResult emission=null;GroupedResult candidate=null;
        try{
            if(progress!=null){progress.begin(input,queryPolicy,executionPolicy);progress.groupedBegin(grouped);}
            input.requireOpenAndUnchanged();factsOwner=input.openExecutionOwner("legacy-managed-grouped-provenance");
            var budget=input.workBudget();budget.charge(EmissionWorkBudget.Operation.ALLOCATION,3);factsOwner.reserve(512);
            var facts=new GroupedFacts(grouped,new ArrayList<>(),factsOwner);
            emission=solveBody(input,suppressed,seeds,proposals,smoother,cancellation,queryPolicy,executionPolicy,false,true,progress,facts);
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION,6);factsOwner.reserve(1024L+32L*facts.stages().size());
            cancellation.checkpoint();input.requireOpenAndUnchanged();
            try(var publication=input.prepareManagedFinalPublication(factsOwner,256L+16L*facts.stages().size(),2048L+128L*facts.stages().size())){
                candidate=new GroupedResult(grouped,emission,facts.stages(),factsOwner,budget.snapshot());
                publication.validate();
            }
            // The ticket's owned scratch closes before success is sealed. No callback or source read remains.
            if(progress!=null){progress.requireCompatible(input,queryPolicy,executionPolicy);progress.seal(null);}
            return candidate;
        }catch(RuntimeException failure){
            if(candidate!=null)closeAfterFailure(candidate,failure);else closeAfterFailure(emission,failure);
            closeAfterFailure(factsOwner,failure);
            if(failure instanceof CancellationException||failure instanceof EmissionWorkBudget.LimitExceededException
                    ||failure instanceof AttemptMemoryLedger.ResourceLimitException)input.recordTerminalExecutionFailure(failure);
            if(progress!=null){
                boolean live=false;try{progress.requireCompatible(input,queryPolicy,executionPolicy);live=true;}
                catch(RuntimeException revoked){/* A revoked owner cannot publish a new terminal view. */}
                if(live)try{progress.seal(failure);}catch(RuntimeException cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}
            }throw failure;
        }
    }
    private static void closeAfterFailure(AutoCloseable resource,RuntimeException primary){
        if(resource==null)return;
        try{resource.close();}catch(Exception cleanup){if(cleanup!=primary)primary.addSuppressed(cleanup);}
    }
    private EmissionResult solveBody(SampledEmissionInput input,List<CrossSectionProfile> suppressed,
            List<Double> seeds,List<AnchorProposal> proposals,Smoother smoother,CancellationProbe cancellation,
            NativeConnectorEvaluator.QueryPolicy queryPolicy,NativeConnectorEvaluator.ExecutionPolicy executionPolicy,
            boolean measuredAffinity,LegacyEmissionProgress progress,GroupedFacts grouped) {
        return solveBody(input,suppressed,seeds,proposals,smoother,cancellation,queryPolicy,executionPolicy,measuredAffinity,false,progress,grouped);
    }
    private EmissionResult solveBody(SampledEmissionInput input,List<CrossSectionProfile> suppressed,
            List<Double> seeds,List<AnchorProposal> proposals,Smoother smoother,CancellationProbe cancellation,
            NativeConnectorEvaluator.QueryPolicy queryPolicy,NativeConnectorEvaluator.ExecutionPolicy executionPolicy,
            boolean measuredAffinity,boolean managedAffinity,LegacyEmissionProgress progress,GroupedFacts grouped){
        Objects.requireNonNull(input); Objects.requireNonNull(suppressed); Objects.requireNonNull(seeds);
        Objects.requireNonNull(proposals); Objects.requireNonNull(smoother); Objects.requireNonNull(cancellation);
        Objects.requireNonNull(queryPolicy); Objects.requireNonNull(executionPolicy);
        if (executionPolicy != NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1
                && queryPolicy != NativeConnectorEvaluator.QueryPolicy.ZERO_GUARDED_LOCAL_V1)
            throw new IllegalArgumentException("Stop-on-final-mode-failure requires the zero-guarded local query policy");
        input.requireOpenAndUnchanged();
        var binding = input.legacyBinding().orElseThrow(() ->
            new IllegalArgumentException("Original Legacy binding is required"));
        if (binding.observations().size() < 2) throw new IllegalArgumentException("Legacy requires at least two original rows");
        if (binding.protectedOccurrences().stream().anyMatch(event -> !event.fixed()))
            throw new UnavailableException(UnavailableReason.MOVABLE_BOUNDARY_ASSIGNMENT);
        double pitch = input.context().nativePitchMeters().orElseThrow(() ->
            new UnavailableException(UnavailableReason.NATIVE_PITCH));
        var budget = input.workBudget();
        var resultLease = input.retainResultLifetime();
        AttemptMemoryLedger.Owner owner;
        try {
            owner = input.openExecutionOwner("legacy-emission-kernel");
        } catch (RuntimeException failure) {
            resultLease.close();
            throw failure;
        }
        NativeConnectorEvaluator bestEvaluator = null;
        AttemptMemoryLedger.Owner bestSeedOwner = null;
        ManagedOrdinaryAffinity managedClassifier=null;
        try {
            var domain = new LegacyEmissionDomain(binding, suppressed, seeds, budget, owner,
                measuredAffinity ? input.experimentalMeasuredModes() : null);
            var affinity = measuredAffinity ? new OrdinaryAffinityClassifier(input, domain, owner) : null;
            managedClassifier=managedAffinity?new ManagedOrdinaryAffinity(input,domain):null;
            if(progress!=null)progress.domainComplete(domain.seeds().size());
            Node best = null;
            Inventory bestInventory = null;
            for (var seed : domain.seeds()) {
                if(progress!=null){progress.seedBegin(seed.id());progress.phase(LegacyEmissionProgress.Phase.BASE,0,-1,-1);}
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                cancellation.checkpoint();
                var seedOwner = owner.child("seed-" + seed.id());
                NativeConnectorEvaluator evaluator = null;
                boolean retained = false;
                try {
                    if(progress!=null)progress.prepareMutation();
                    var inventory = inventory(domain, budget, seedOwner, affinity,managedClassifier);
                    if(progress!=null)progress.inventoryComplete(inventory.evaluatorStates().size());
                    evaluator = evaluator(input, domain, inventory, queryPolicy, executionPolicy,progress);
                    Node candidate = grouped==null ? solveBase(input, domain, seed, inventory, evaluator,
                        seedOwner, cancellation, pitch) : solveGroupedBase(input,domain,seed,inventory,evaluator,
                            seedOwner,cancellation,pitch,grouped);
                    if (candidate != null && better(candidate, best, budget, seedOwner)) {
                        if (bestEvaluator != null) bestEvaluator.close();
                        if (bestSeedOwner != null) bestSeedOwner.close();
                        best = candidate; bestInventory = inventory; bestEvaluator = evaluator;
                        bestSeedOwner = seedOwner; retained = true;
                    }
                    if(progress!=null){progress.seedComplete();progress.phaseComplete();}
                } finally {
                    if (!retained) {
                        if (evaluator != null) evaluator.close();
                        seedOwner.close();
                    }
                }
            }
            if (best == null) throw new UnavailableException(UnavailableReason.NO_ADMITTED_PATH);
            if (best.key.lastPositive() == null)
                throw new UnavailableException(UnavailableReason.NO_POSITIVE_PHYSICAL_LEG);

            Node firstStage = best;
            // First-stage flattening retains its drafts until provenance is copied into the
            // result. Keep that ownership separate from the current stitched winner.
            pinChain(firstStage, budget);
            try (var path = flatten(firstStage, budget, bestSeedOwner)) {
            verifyCosts(path.arcs(), firstStage, budget);
            if(progress!=null)progress.firstStageComplete();
            // Anchors are computed on the unchanged original N profiles. A proposal is eligible only
            // when its first-stage emitted state owns the exact proposed native peak object.
            var eligible = new ArrayList<AnchorProposal>();
            if(progress!=null){progress.seedSelect(best.seedId);progress.phase(LegacyEmissionProgress.Phase.ANCHOR_ELIGIBILITY,0,-1,-1);}
            for (var proposal : proposals) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                for (var point : path.points()) {
                    if (point.state() != null && point.state().originalRow() == proposal.originalRow()
                            && point.state().peakOwner() == proposal.peakOwner()) {
                        eligible.add(proposal);
                        break;
                    }
                }
            }
            var intervalCosts = new ArrayList<EmissionResult.Costs>();
            if(progress!=null)progress.phaseComplete();
            var intervalReferences = new ArrayList<EmissionResult.IntervalStageReference>();
            for (int index = 0; index + 1 < eligible.size(); index++) {
                if(progress!=null)progress.phase(LegacyEmissionProgress.Phase.INTERVAL,index+1,
                    eligible.get(index).originalRow(),eligible.get(index+1).originalRow());
                cancellation.checkpoint();
                var left = eligible.get(index); var right = eligible.get(index + 1);
                var chain = chain(best, budget);
                Node prefix = chain.stream().filter(node -> node.key.last().originalRow() == left.originalRow())
                    .findFirst().orElseThrow(() -> new IllegalStateException("Eligible anchor was not emitted"));
                var rightState = chain.stream().filter(node -> node.key.last().originalRow() == right.originalRow())
                    .map(node -> node.key.last()).findFirst().orElseThrow();
                if (rightState.peakOwner() != right.peakOwner())
                    throw new IllegalStateException("Anchored endpoint lost its exact native peak");
                Node previousBest = best;
                Node intervalWinner;
                if(grouped==null)intervalWinner=solveInterval(input, domain, prefix, rightState, left, right,
                    index + 1, bestInventory, bestEvaluator, bestSeedOwner, cancellation, pitch);
                else {
                    var witness=grouped.guide()==null?groupedWitness(input,domain,null,prefix,rightState,index+1,bestInventory,bestEvaluator,bestSeedOwner,cancellation,pitch)
                        :ownedWitness(input,domain,null,prefix,rightState,index+1,bestInventory,bestEvaluator,bestSeedOwner,cancellation,pitch,grouped);
                    intervalWinner=solveGroupedStage(input,domain,null,prefix,rightState,index+1,bestInventory,bestEvaluator,bestSeedOwner,
                        cancellation,pitch,grouped.execution(),witness.map(EmissionResult.Costs::total).orElse(Double.POSITIVE_INFINITY));
                    groupedStageFact(grouped,prefix,intervalWinner,witness,best.seedId,index+1,budget);
                }
                if(progress!=null)progress.phaseComplete();
                if (intervalWinner == null) throw new UnavailableException(UnavailableReason.NO_ADMITTED_PATH);
                Node interval = intervalWinner;
                var intervalCost = intervalIncrement(prefix, interval, budget);
                intervalCosts.add(intervalCost);
                intervalReferences.add(intervalStage(prefix, interval, left, right,
                    index + 1, intervalCost, domain, input, budget));
                boolean suffix = false;
                if(progress!=null)progress.phase(LegacyEmissionProgress.Phase.STITCH,index+1,left.originalRow(),right.originalRow());
                for (Node node : chain) {
                    int row = node.key.last().originalRow();
                    if (row == right.originalRow()) { suffix = true; continue; }
                    if (suffix) {
                        interval = advance(input, domain, bestInventory, interval, node.key.last(),
                            bestEvaluator, bestSeedOwner,
                            cancellation, index + 1, pitch);
                        if (interval == null)
                            throw new UnavailableException(UnavailableReason.NO_ADMITTED_PATH);
                    }
                }
                pinChain(interval, budget);
                // Transfer the selected interval's ownership to the stitched path before
                // releasing either predecessor chain; their prefixes may be shared.
                unpinChain(intervalWinner, budget);
                unpinChain(previousBest, budget);
                best = interval;
                if(progress!=null)progress.phaseComplete();
            }
            try (var stitchedPath = flatten(best, budget, bestSeedOwner)) {
            verifyCosts(stitchedPath.arcs(), best, budget);
            if (best.key.lastPositive() == null)
                throw new UnavailableException(UnavailableReason.NO_POSITIVE_PHYSICAL_LEG);
            if(progress!=null)progress.phase(LegacyEmissionProgress.Phase.RESULT,0,-1,-1);
            var result = result(input, domain, stitchedPath, path, firstStage, best, intervalCosts,
                intervalReferences, eligible, bestInventory,
                smoother, bestSeedOwner, owner, bestEvaluator, budget, cancellation, resultLease, queryPolicy, executionPolicy,progress,grouped);
            if(progress!=null)progress.phaseComplete();
            return result;
            }
            }
        } catch (RuntimeException failure) {
            if(managedAffinity&&grouped!=null){
                closeAfterFailure(managedClassifier,failure);closeAfterFailure(bestEvaluator,failure);
                closeAfterFailure(owner,failure);closeAfterFailure(resultLease,failure);
                if(failure instanceof CancellationException||failure instanceof EmissionWorkBudget.LimitExceededException
                        ||failure instanceof AttemptMemoryLedger.ResourceLimitException
                        ||failure instanceof LegacyEmissionDomain.StateFamilyLimitException
                        ||failure instanceof NativeConnectorEvaluator.StateFamilyLimitException)input.recordTerminalExecutionFailure(failure);
                throw failure;
            }
            if(managedClassifier!=null)try{managedClassifier.close();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}
            if (bestEvaluator != null) bestEvaluator.close();
            owner.close();
            resultLease.close();
            if (failure instanceof CancellationException
                    || failure instanceof EmissionWorkBudget.LimitExceededException
                    || failure instanceof AttemptMemoryLedger.ResourceLimitException
                    || failure instanceof LegacyEmissionDomain.StateFamilyLimitException
                    || failure instanceof NativeConnectorEvaluator.StateFamilyLimitException)
                input.recordTerminalExecutionFailure(failure);
            throw failure;
        }
    }

    private static Optional<EmissionResult.Costs> storedCosts(Node node) {
        return node!=null&&finiteComponents(node)?Optional.of(new EmissionResult.Costs(node.q,node.c,node.t,node.k)):Optional.empty();
    }
    private static void groupedStageFact(GroupedFacts facts,Node prefix,Node terminal,
            Optional<EmissionResult.Costs> witness,int seed,int ordinal,EmissionWorkBudget budget) {
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION,4);facts.owner().reserve(512);
        facts.stages().add(new GroupedStageReference(seed,ordinal,storedCosts(prefix),storedCosts(terminal),witness));
    }
    private static LegacyEmissionDomain.State witnessObserved(LegacyEmissionDomain domain,int row,double desired,EmissionWorkBudget budget) {
        LegacyEmissionDomain.State chosen=null;
        for(var state:domain.observedAt(row)) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION,1);
            if(chosen==null||Math.abs(state.offsetRasterPixels()-desired)<Math.abs(chosen.offsetRasterPixels()-desired)
                    ||Math.abs(state.offsetRasterPixels()-desired)==Math.abs(chosen.offsetRasterPixels()-desired)
                        &&state.nativeStateId()<chosen.nativeStateId())chosen=state;
        }
        return chosen;
    }
    /** One actual ordinary proposal, never an optimization or admission substitute. */
    private Optional<EmissionResult.Costs> groupedWitness(SampledEmissionInput input,LegacyEmissionDomain domain,
            LegacyEmissionDomain.Seed seed,Node prefix,LegacyEmissionDomain.State right,int stage,Inventory inventory,
            NativeConnectorEvaluator evaluator,AttemptMemoryLedger.Owner owner,CancellationProbe cancellation,double pitch) {
        var budget=input.workBudget();var progress=evaluator.progress();
        if(progress!=null)progress.witnessBegin();
        Node current=prefix;int first=prefix==null?0:prefix.key.last().originalRow()+1;
        int end=right==null?domain.rowCount()-1:right.originalRow();
        for(int row=first;row<=end;row++) {
            input.requireOpenAndUnchanged();cancellation.checkpoint();
            LegacyEmissionDomain.State chosen;
            if(right!=null&&row==end)chosen=right;
            else if(domain.unsupported(row))chosen=prefix==null
                ?reachedCarry(domain,inventory,evaluator,row,current==null?seed.id():current.key.last().offsetOriginId(),
                    current==null?seed.offsetRasterPixels():current.key.last().offsetRasterPixels(),budget,owner)
                :intervalTarget(domain,inventory,evaluator,row,prefix.key.last(),right,budget,owner);
            else chosen=witnessObserved(domain,row,current==null?seed.offsetRasterPixels():current.key.last().offsetRasterPixels(),budget);
            Node next=current==null?initialProofNode(input,domain,seed,chosen,owner,budget)
                :advance(input,domain,inventory,current,chosen,evaluator,owner,cancellation,stage,pitch);
            if(next==null){
                releaseUnretained(current,budget);
                if(inventory.managedAffinity()!=null&&progress!=null&&progress.ceilingEnabled())progress.ceilingWitness(input,Optional.empty());
                return Optional.empty();
            }
            current=next;
        }
        double upper=certifiedWitnessUpper(current);
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION,2);owner.reserve(96);
        var costs=Double.isFinite(upper)?storedCosts(current):Optional.<EmissionResult.Costs>empty();
        releaseUnretained(current,budget);
        input.requireOpenAndUnchanged();cancellation.checkpoint();
        if(progress!=null) {
            if(progress.ceilingEnabled())progress.ceilingWitness(input,costs);
            else if(costs.isPresent())progress.witnessCertified(upper);
        }
        return costs;
    }
    private Node solveGroupedBase(SampledEmissionInput input,LegacyEmissionDomain domain,LegacyEmissionDomain.Seed seed,
            Inventory inventory,NativeConnectorEvaluator evaluator,AttemptMemoryLedger.Owner owner,CancellationProbe cancellation,
            double pitch,GroupedFacts facts) {
        var witness=facts.guide()==null?groupedWitness(input,domain,seed,null,null,0,inventory,evaluator,owner,cancellation,pitch)
            :ownedWitness(input,domain,seed,null,null,0,inventory,evaluator,owner,cancellation,pitch,facts);
        Node best=solveGroupedStage(input,domain,seed,null,null,0,inventory,evaluator,owner,cancellation,pitch,facts.execution(),
            witness.map(EmissionResult.Costs::total).orElse(Double.POSITIVE_INFINITY));
        groupedStageFact(facts,null,best,witness,seed.id(),0,input.workBudget());return best;
    }
    private Node solveGroupedStage(SampledEmissionInput input,LegacyEmissionDomain domain,LegacyEmissionDomain.Seed seed,
            Node prefix,LegacyEmissionDomain.State right,int stage,Inventory inventory,NativeConnectorEvaluator evaluator,
            AttemptMemoryLedger.Owner owner,CancellationProbe cancellation,double pitch,GroupedExecution execution,double upper) {
        var budget=input.workBudget();var progress=evaluator.progress();int n=domain.rowCount();
        int start=prefix==null?0:prefix.key.last().originalRow(),end=right==null?n-1:right.originalRow();
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION,end-start+2L);
        try(var layerMemory=owner.reserve(256L*(end-start+1))) {
            var layers=new ArrayList<Map<Key,ExactFront>>(end-start+1);
            for(int row=start;row<=end;row++)layers.add(new LinkedHashMap<>());
            if(prefix!=null)insertExact(layers.get(0),prefix,budget,owner,progress);
            else {
                var choices=domain.unsupported(0)?List.of(reachedCarry(domain,inventory,evaluator,0,seed.id(),seed.offsetRasterPixels(),budget,owner)):inventory.byRow().get(0);
                for(var first:choices)insertExact(layers.get(0),initialProofNode(input,domain,seed,first,owner,budget),budget,owner,progress);
            }
            for(int row=start;row<end;row++) {
                if(progress!=null)progress.row(row);
                for(var front:layers.get(row-start).values())for(var label=front.first;label!=null;label=label.next) {
                    Node from=label.node;budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP,1);cancellation.checkpoint();
                    for(int toRow=row+1;toRow<=end&&toRow<=row+17;toRow++) {
                        budget.charge(EmissionWorkBudget.Operation.PREPARATION,1);
                        if(toRow>row+1&&sourceSpanUpper(domain,input.context(),row,toRow)>20)break;
                        if(toRow>row+1&&crossesMandatory(domain,row,toRow))continue;
                        List<LegacyEmissionDomain.State> choices;
                        if(right!=null&&toRow==end)choices=List.of(right);
                        else if(domain.unsupported(toRow))choices=List.of(prefix==null
                            ?reachedCarry(domain,inventory,evaluator,toRow,from.key.last().offsetOriginId(),from.key.last().offsetRasterPixels(),budget,owner)
                            :intervalTarget(domain,inventory,evaluator,toRow,prefix.key.last(),right,budget,owner));
                        else choices=inventory.byRow().get(toRow);
                        for(var to:choices) {
                            budget.charge(EmissionWorkBudget.Operation.TRANSITION,1);
                            if(toRow>row+1&&(execution==GroupedExecution.EXACT_Q0_CUTOFF_V1||execution==GroupedExecution.EXACT_Q0_CEILING_CENSUS_V1||execution==GroupedExecution.EXACT_Q0_OWNED_NATIVE_GUIDE_V1)
                                    &&deferOptional(input,domain,from,to,pitch,upper,budget,cancellation,progress,
                                        execution==GroupedExecution.EXACT_Q0_CEILING_CENSUS_V1))continue;
                            Node candidate=advance(input,domain,inventory,from,to,evaluator,owner,cancellation,stage,pitch);
                            if(candidate!=null)insertExact(layers.get(toRow-start),candidate,budget,owner,progress);
                        }
                    }
                }
            }
            Node best=null;
            for(var front:layers.get(end-start).values())for(var label=front.first;label!=null;label=label.next)
                if(finiteComponents(label.node)&&better(label.node,best,budget,owner))best=label.node;
            if(best!=null)pinChain(best,budget);
            for(int row=end;row>=start;row--)for(var front:layers.get(row-start).values())for(var label=front.first;label!=null;label=label.next) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION,1);label.node.inLayer=false;
            }
            for(int row=end;row>=start;row--) {
                for(var front:layers.get(row-start).values()) {
                    for(var label=front.first;label!=null;) {releaseUnretained(label.node,budget);var next=label.next;label.memory.close();label=next;}
                    front.first=null;front.memory.close();
                }
                layers.get(row-start).clear();
            }
            return best;
        }
    }

    private record CarryIdentity(int row, int originId, long offsetBits) { }
    private record TargetIdentity(int row, int leftAnchorRow, int rightAnchorRow) { }
    private record ConnectorIdentity(LegacyEmissionDomain.State from,
            LegacyEmissionDomain.State to) { }
    private record OrdinaryLegIdentity(NativeConnectorEvaluator.PhysicalEndpoint from,
            NativeConnectorEvaluator.PhysicalEndpoint to) { }
    private record DirectionOrigin(ReferenceIdentity optional, ReferenceIdentity ordinary) { }
    private record ZeroCursorIdentity(DirectionOrigin origin,
            NativeConnectorEvaluator.PhysicalEndpoint from,
            NativeConnectorEvaluator.PhysicalEndpoint to) { }
    private record ZeroConnectorIdentity(ConnectorIdentity endpoints, DirectionOrigin origin) { }
    private record Inventory(List<List<LegacyEmissionDomain.State>> byRow, int[] familySizes,
            Map<CarryIdentity, LegacyEmissionDomain.State> reachedCarries,
            Map<TargetIdentity, LegacyEmissionDomain.State> intervalTargets,
            Map<String, NativeConnectorEvaluator.Barrier> barriersById,
            Map<ConnectorIdentity, NativeConnectorEvaluator.Receipt> positiveReceipts,
            Map<OrdinaryLegIdentity, NativeConnectorEvaluator.OrdinaryPhysicalLeg> ordinaryLegs,
            Map<ZeroCursorIdentity, NativeConnectorEvaluator.PhysicalDirectionCursor> zeroCursors,
            Map<ZeroConnectorIdentity, NativeConnectorEvaluator.Receipt> zeroReceipts,
            List<NativeConnectorEvaluator.RowState> evaluatorStates, OrdinaryAffinityClassifier affinity,
            ManagedOrdinaryAffinity managedAffinity) { }

    private Inventory inventory(LegacyEmissionDomain domain,EmissionWorkBudget budget,AttemptMemoryLedger.Owner owner,
            OrdinaryAffinityClassifier affinity){
        return inventory(domain,budget,owner,affinity,null);
    }
    private Inventory inventory(LegacyEmissionDomain domain,
            EmissionWorkBudget budget, AttemptMemoryLedger.Owner owner, OrdinaryAffinityClassifier affinity,
            ManagedOrdinaryAffinity managedAffinity) {
        var byRow = new ArrayList<List<LegacyEmissionDomain.State>>(domain.rowCount());
        var familySizes = new int[domain.rowCount()];
        var all = new ArrayList<NativeConnectorEvaluator.RowState>();
        for (int row = 0; row < domain.rowCount(); row++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var states = domain.observedAt(row);
            if (states.size() > budget.stateFamilyLimit())
                throw new UnavailableException(UnavailableReason.STATE_FAMILY_LIMIT);
            familySizes[row] = states.size();
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION, states.size() + 1L);
            owner.reserve(Math.addExact(64, Math.multiplyExact(128L, states.size())));
            byRow.add(states);
            for (var state : states) all.add(state.evaluatorState());
        }
        return new Inventory(List.copyOf(byRow), familySizes, new HashMap<>(), new HashMap<>(),
            new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
            new HashMap<>(), List.copyOf(all), affinity,managedAffinity);
    }

    private LegacyEmissionDomain.State reachedCarry(LegacyEmissionDomain domain, Inventory inventory,
            NativeConnectorEvaluator evaluator, int row, int originId, double offset,
            EmissionWorkBudget budget, AttemptMemoryLedger.Owner owner) {
        var key = new CarryIdentity(row, originId, Double.doubleToLongBits(offset));
        budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
        var reached = inventory.reachedCarries().get(key);
        if (reached != null) return reached;
        requireFamilyCapacity(row, inventory.familySizes()[row], budget);
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        owner.reserve(160);
        var state = domain.carry(row, originId, offset, budget);
        evaluator.registerState(state.evaluatorState(), row);
        if(evaluator.progress()!=null)evaluator.progress().prepareMutation();
        inventory.reachedCarries().put(key, state);
        if(evaluator.progress()!=null)evaluator.progress().mutation(LegacyEmissionProgress.Counter.CARRIES);
        inventory.familySizes()[row]++;
        return state;
    }

    private LegacyEmissionDomain.State intervalTarget(LegacyEmissionDomain domain, Inventory inventory,
            NativeConnectorEvaluator evaluator, int row, LegacyEmissionDomain.State left,
            LegacyEmissionDomain.State right, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner) {
        var key = new TargetIdentity(row, left.originalRow(), right.originalRow());
        budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
        var existing = inventory.intervalTargets().get(key);
        if (existing != null) return existing;
        requireFamilyCapacity(row, inventory.familySizes()[row], budget);
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        owner.reserve(160);
        var state = domain.intervalTarget(row, left.originalRow(), right.originalRow(), left, right,
            budget);
        evaluator.registerState(state.evaluatorState(), row);
        if(evaluator.progress()!=null)evaluator.progress().prepareMutation();
        inventory.intervalTargets().put(key, state);
        if(evaluator.progress()!=null)evaluator.progress().mutation(LegacyEmissionProgress.Counter.TARGETS);
        inventory.familySizes()[row]++;
        return state;
    }

    /** Shared precreation gate for both genuine generated-state families. */
    static void requireFamilyCapacity(int originalRow, int existingMembers,
            EmissionWorkBudget budget) {
        budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
        if (existingMembers >= budget.stateFamilyLimit())
            throw new LegacyEmissionDomain.StateFamilyLimitException(originalRow,
                Math.addExact(existingMembers, 1), budget.stateFamilyLimit());
    }

    private static List<Node> chain(Node terminal, EmissionWorkBudget budget) {
        var reverse = new ArrayList<Node>();
        for (Node node = terminal; node != null; node = node.previous) {
            budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
            reverse.add(node);
        }
        java.util.Collections.reverse(reverse);
        return List.copyOf(reverse);
    }

    private static EmissionResult.Costs intervalIncrement(Node prefix, Node terminal,
            EmissionWorkBudget budget) {
        double q = 0, c = 0, t = 0, k = 0;
        for (Node node = terminal; node != prefix; node = node.previous) {
            budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
            if (node == null) throw new IllegalStateException("Anchored prefix is not an ancestor");
            for (var arc : node.addedArcs) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                q += arc.q(); c += arc.c(); t += arc.t(); k += arc.k();
            }
        }
        return new EmissionResult.Costs(q, c, t, k);
    }

    private static EmissionResult.IntervalStageReference intervalStage(Node prefix, Node terminal,
            AnchorProposal left, AnchorProposal right, int ordinal,
            EmissionResult.Costs costs, LegacyEmissionDomain domain, SampledEmissionInput input,
            EmissionWorkBudget budget) {
        var reverse = new ArrayList<Node>();
        for (Node node = terminal; node != prefix; node = node.previous) {
            budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
            if (node == null) throw new IllegalStateException("Interval prefix is not an ancestor");
            reverse.add(node);
        }
        java.util.Collections.reverse(reverse);
        var vertices = new ArrayList<EmissionResult.Vertex>();
        vertices.add(vertex(new PointDraft(new EmissionMapping.ObservationVertex(left.originalRow()),
            prefix.key.last(), domain.metricPoint(prefix.key.last(), input.context()))));
        var arcs = new ArrayList<ArcDraft>();
        for (Node node : reverse) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            for (var point : node.addedPoints) vertices.add(vertex(point));
            arcs.addAll(node.addedArcs);
        }
        return new EmissionResult.IntervalStageReference(ordinal,
            new EmissionResult.AnchorReference(left.originalRow(), domain.originalPeakId(left.peakOwner())),
            new EmissionResult.AnchorReference(right.originalRow(), domain.originalPeakId(right.peakOwner())),
            vertices, arcResults(arcs), costs);
    }

    private static void verifyCosts(List<ArcDraft> arcs, Node terminal,
            EmissionWorkBudget budget) {
        double q = 0, c = .16 * Math.abs(terminal.key.scalar().lastOffset()
            - terminal.key.scalar().previousOffset()), t = 0, k = 0;
        // Initial prior belongs to the first emitted row, which may differ from the terminal row.
        Node first = terminal;
        while (first.previous != null) {
            budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
            first = first.previous;
        }
        double departure = Math.abs(first.key.scalar().lastOffset() - first.seedOffset);
        c = .16 * departure; t = .12 * departure;
        for (var arc : arcs) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            q += arc.q(); c += arc.c(); t += arc.t(); k += arc.k();
        }
        double tolerance = 1e-8 * Math.max(1, terminal.total());
        if (Math.abs(q - terminal.q) > tolerance || Math.abs(c - terminal.c) > tolerance
                || Math.abs(t - terminal.t) > tolerance || Math.abs(k - terminal.k) > tolerance)
            throw new IllegalStateException("Stitched Legacy objective differs from actual arc costs");
    }

    private Node solveInterval(SampledEmissionInput input, LegacyEmissionDomain domain,
            Node prefix, LegacyEmissionDomain.State rightState, AnchorProposal left,
            AnchorProposal right, int stageOrdinal, Inventory inventory,
            NativeConnectorEvaluator evaluator,
            AttemptMemoryLedger.Owner owner, CancellationProbe cancellation, double pitch) {
        var budget = input.workBudget();
        int intervalRows = right.originalRow() - left.originalRow() + 1;
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, intervalRows + 1L);
        var layerMemory = owner.reserve(Math.multiplyExact(128L, intervalRows));
        try {
        var layers = new HashMap<Integer, Map<Key, Node>>();
        var firstLayer = new LinkedHashMap<Key, Node>();
        firstLayer.put(prefix.key, prefix);
        layers.put(left.originalRow(), firstLayer);
        for (int row = left.originalRow(); row < right.originalRow(); row++) {
            if(evaluator.progress()!=null)evaluator.progress().row(row);
            var current = layers.get(row);
            if (current == null) continue;
            for (Node from : List.copyOf(current.values())) {
                budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
                cancellation.checkpoint();
                for (int toRow = row + 1; toRow <= right.originalRow() && toRow <= row + 17; toRow++) {
                    budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                    if (toRow > row + 1 && (sourceSpanUpper(domain, input.context(), row, toRow) > 20
                            || crossesMandatory(domain, row, toRow))) continue;
                    List<LegacyEmissionDomain.State> choices;
                    if (toRow == right.originalRow()) choices = List.of(rightState);
                    else if (domain.unsupported(toRow))
                        choices = List.of(intervalTarget(domain, inventory, evaluator, toRow,
                            prefix.key.last(), rightState, budget, owner));
                    else choices = domain.observedAt(toRow);
                    for (var to : choices) {
                        budget.charge(EmissionWorkBudget.Operation.TRANSITION, 1);
                        Node next = advance(input, domain, inventory, from, to, evaluator, owner,
                            cancellation, stageOrdinal, pitch);
                        if (next != null) insert(layers.computeIfAbsent(toRow, ignored -> new LinkedHashMap<>()), next,
                            budget, owner);
                    }
                }
            }
        }
        Node best = null;
        for (var candidate : layers.getOrDefault(right.originalRow(), Map.of()).values())
            if (better(candidate, best, budget, owner)) best = candidate;
        if (best != null) pinChain(best, budget);
        for (var layer : layers.values())
            for (Node node : layer.values()) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                node.inLayer = false;
            }
        for (var layer : layers.values())
            for (Node node : layer.values())
                releaseUnretained(node, budget);
        return best;
        } finally {
            layerMemory.close();
        }
    }

    private NativeConnectorEvaluator evaluator(SampledEmissionInput input, LegacyEmissionDomain domain,
            Inventory inventory, NativeConnectorEvaluator.QueryPolicy queryPolicy,
            NativeConnectorEvaluator.ExecutionPolicy executionPolicy,LegacyEmissionProgress progress) {
        var binding = domain.binding();
        var mapping = binding.originalRowToRetainedObservation();
        var ordinals = new HashMap<Integer, Integer>();
        for (int row = 0; row < mapping.size(); row++)
            ordinals.put(mapping.get(row), row);
        var mandatory = new ArrayList<Integer>();
        mandatory.add(mapping.get(0)); mandatory.add(mapping.get(mapping.size() - 1));
        var barriers = new ArrayList<NativeConnectorEvaluator.Barrier>();
        for (var event : binding.protectedOccurrences()) {
            mandatory.add(mapping.get(event.leftOriginalRow()));
            mandatory.add(mapping.get(event.rightOriginalRow()));
            barriers.add(new NativeConnectorEvaluator.Barrier(event.occurrenceId(),
                mapping.get(event.leftOriginalRow()), mapping.get(event.rightOriginalRow()),
                event.sourceChainageMeters()
                    - input.context().profileChainage().sourceOriginGroundMeters(),
                event.exactPosition()));
            inventory.barriersById().put(event.occurrenceId(), barriers.get(barriers.size() - 1));
        }
        return NativeConnectorEvaluator.forLegacyProgress(input,
            new NativeConnectorEvaluator.Domain(inventory.evaluatorStates(), mandatory, barriers,
                ordinals), queryPolicy, executionPolicy, domain.measuredModes(),progress);
    }

    private Node solveBase(SampledEmissionInput input, LegacyEmissionDomain domain,
            LegacyEmissionDomain.Seed seed, Inventory inventory, NativeConnectorEvaluator evaluator,
            AttemptMemoryLedger.Owner owner, CancellationProbe cancellation, double pitch) {
        var budget = input.workBudget();
        int n = domain.rowCount();
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, n + 1L);
        var layerMemory = owner.reserve(Math.multiplyExact(128L, n));
        try {
        var layers = new ArrayList<Map<Key, Node>>(n);
        for (int row = 0; row < n; row++) {
            layers.add(new LinkedHashMap<>());
        }
        List<LegacyEmissionDomain.State> firstChoices = domain.unsupported(0)
            ? List.of(reachedCarry(domain, inventory, evaluator, 0, seed.id(),
                seed.offsetRasterPixels(), budget, owner)) : inventory.byRow().get(0);
        for (var first : firstChoices) {
            budget.charge(EmissionWorkBudget.Operation.PAIR_INITIALIZATION, 1);
            double departure = Math.abs(first.offsetRasterPixels() - seed.offsetRasterPixels());
            var key = new Key(first, new ScalarMemory(-1, seed.offsetRasterPixels(), 0,
                first.offsetRasterPixels(), first.offsetRasterPixels() - seed.offsetRasterPixels(),
                first.offsetOriginId()), null, null, new PhaseContext(0, null));
            var point = new PointDraft(new EmissionMapping.ObservationVertex(0), first,
                domain.metricPoint(first, input.context()));
            var node = allocateNode(owner, budget, null, key, List.of(point), List.of(),
                0, .16 * departure, .12 * departure, 0, 0, seed.id());
            insert(layers.get(0), node, budget, owner);
        }
        for (int row = 0; row < n - 1; row++) {
            if(evaluator.progress()!=null)evaluator.progress().row(row);
            for (Node from : List.copyOf(layers.get(row).values())) {
                budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
                cancellation.checkpoint();
                for (int toRow = row + 1; toRow < n && toRow <= row + 17; toRow++) {
                    budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                    if (toRow > row + 1 && sourceSpanUpper(domain, input.context(), row, toRow) > 20) break;
                    if (toRow > row + 1 && crossesMandatory(domain, row, toRow)) continue;
                    List<LegacyEmissionDomain.State> choices = domain.unsupported(toRow)
                        ? List.of(reachedCarry(domain, inventory, evaluator, toRow,
                            from.key.last().offsetOriginId(), from.key.last().offsetRasterPixels(),
                            budget, owner)) : inventory.byRow().get(toRow);
                    for (var to : choices) {
                        budget.charge(EmissionWorkBudget.Operation.TRANSITION, 1);
                        if (to.kind() == LegacyEmissionDomain.Kind.BASE_CARRY
                                && (to.offsetOriginId() != from.key.last().offsetOriginId()
                                    || Double.doubleToLongBits(to.offsetRasterPixels())
                                        != Double.doubleToLongBits(from.key.last().offsetRasterPixels())))
                            continue;
                        Node candidate = advance(input, domain, inventory, from, to, evaluator, owner,
                            cancellation, 0, pitch);
                        if (candidate != null) insert(layers.get(toRow), candidate, budget, owner);
                    }
                }
            }
        }
        Node best = null;
        for (Node candidate : layers.get(n - 1).values())
            if (better(candidate, best, budget, owner)) best = candidate;
        if (best != null) pinChain(best, budget);
        for (int row = n - 1; row >= 0; row--)
            for (Node node : layers.get(row).values()) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                node.inLayer = false;
            }
        for (int row = n - 1; row >= 0; row--)
            for (Node node : layers.get(row).values())
                releaseUnretained(node, budget);
        return best;
        } finally {
            layerMemory.close();
        }
    }

    private Node advance(SampledEmissionInput input, LegacyEmissionDomain domain,
            Inventory inventory, Node from,
            LegacyEmissionDomain.State to, NativeConnectorEvaluator evaluator,
            AttemptMemoryLedger.Owner owner, CancellationProbe cancellation,
            int stageOrdinal, double pitch) {
        var budget = input.workBudget();
        var a = from.key.last(); int rowA = a.originalRow(), rowB = to.originalRow();
        var progress=evaluator.progress();
        if(progress!=null)progress.edge(rowA,rowB,a.nativeStateId(),to.nativeStateId());
        if(progress!=null)progress.inventorySizes(inventory.evaluatorStates().size(),inventory.reachedCarries().size(),
            inventory.intervalTargets().size(),inventory.positiveReceipts().size(),inventory.zeroReceipts().size(),
            inventory.ordinaryLegs().size(),inventory.zeroCursors().size());
        boolean optional = rowB > rowA + 1;
        MetricPoint pa = domain.metricPoint(a, input.context()), pb = domain.metricPoint(to, input.context());
        double sourceSpan = sourceChainage(domain, input.context(), rowB)
            - sourceChainage(domain, input.context(), rowA);
        List<PointDraft> points = new ArrayList<>();
        List<ArcDraft> arcs = new ArrayList<>();
        var events = eventsBetween(domain.binding(), rowA, rowB);
        if (optional && !events.isEmpty()) return null;
        if (!optional && events.isEmpty() && inventory.affinity() != null
                && !inventory.affinity().classifyInValidatedExecution(a, to, pa, pb).ordinaryEligible()) return null;
        if(!optional&&events.isEmpty()&&inventory.managedAffinity()!=null
                &&!inventory.managedAffinity().classify(a,to).ordinaryEligible())return null;
        MetricPoint cursor = pa;
        double cursorSource = sourceChainage(domain, input.context(), rowA);
        PhysicalLeg physical = from.key.lastPositive();
        Object incoming = from.key.incomingCursor() == null ? null : from.key.incomingCursor().value;
        NativeConnectorEvaluator.PhysicalEndpoint endpoint = a.evaluatorState();
        String lastBoundaryId = from.key.phase().lastBoundaryOccurrenceId();
        for (var event : events) {
            var boundary = inventory.barriersById().get(event.occurrenceId());
            if (boundary == null) throw new IllegalStateException("Protected event has no evaluator identity");
            double eventLocalChainage = event.sourceChainageMeters()
                - input.context().profileChainage().sourceOriginGroundMeters();
            var arc = ordinary(input, cursor, event.exactPosition(), cursorSource,
                eventLocalChainage, physical, budget, owner, cancellation);
            incoming = ordinaryCursor(evaluator, inventory, endpoint, boundary, cursor,
                event.exactPosition(), incoming, budget, owner);
            arc = arc.withCursor(incoming);
            arcs.add(arc); physical = nextPhysical(physical, arc, endpoint, boundary, incoming);
            points.add(new PointDraft(new EmissionMapping.BoundaryVertex(event.occurrenceId()), null,
                event.exactPosition()));
            cursor = event.exactPosition(); cursorSource = eventLocalChainage;
            endpoint = boundary;
            lastBoundaryId = event.occurrenceId();
        }
        ArcDraft finalArc;
        if (optional) {
            NativeConnectorEvaluator.Receipt receipt;
            if (cursor.distanceTo(pb) == 0 && incoming == null) return null;
            if (cursor.distanceTo(pb) == 0) {
                var origin = directionOrigin(incoming);
                if (origin == null) return null;
                budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
                var edge = new ZeroConnectorIdentity(new ConnectorIdentity(a, to), origin);
                if(progress!=null)progress.cacheLookup(true);
                receipt = inventory.zeroReceipts().get(edge);
                if(progress!=null)progress.cacheResult(true,receipt!=null);
                if (receipt == null) {
                    if (incoming instanceof NativeConnectorEvaluator.OrdinaryPhysicalLeg leg)
                        receipt = evaluator.evaluateWithOrdinaryIncoming(a.evaluatorState(),
                            to.evaluatorState(), leg);
                    else if (incoming instanceof NativeConnectorEvaluator.PhysicalDirectionCursor direction)
                        receipt = evaluator.evaluateWithDirectionCursor(a.evaluatorState(),
                            to.evaluatorState(), direction);
                    else receipt = evaluator.evaluate(a.evaluatorState(), to.evaluatorState(),
                        (NativeConnectorEvaluator.Receipt) incoming);
                    budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
                    owner.reserve(96);
                    if(progress!=null)progress.prepareMutation();
                    inventory.zeroReceipts().put(edge, receipt);
                    if(progress!=null)progress.mutation(LegacyEmissionProgress.Counter.ZERO_INSERT);
                }
            } else {
                budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
                var edge = new ConnectorIdentity(a, to);
                if(progress!=null)progress.cacheLookup(false);
                receipt = inventory.positiveReceipts().get(edge);
                if(progress!=null)progress.cacheResult(false,receipt!=null);
                if (receipt == null) {
                    receipt = evaluator.evaluate(a.evaluatorState(), to.evaluatorState());
                    budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
                    owner.reserve(96);
                    if(progress!=null)progress.prepareMutation();
                    inventory.positiveReceipts().put(edge, receipt);
                    if(progress!=null)progress.mutation(LegacyEmissionProgress.Counter.POSITIVE_INSERT);
                }
            }
            if (receipt.status() != NativeConnectorEvaluator.Status.ADMITTED) return null;
            double q = receipt.sourceWeightedLoss();
            finalArc = new ArcDraft(EmissionResult.ArcKind.OPTIONAL_ADMITTED, cursor, pb,
                sourceSpan, q, 0, 0, turn(physical, cursor, pb, pitch),
                Optional.of(receipt.scalar().orElseThrow().mean().orElseThrow()),
                Optional.of(receipt), null);
            incoming = receipt;
        } else {
            finalArc = ordinary(input, cursor, pb, cursorSource, sourceChainage(domain, input.context(), rowB),
                physical, budget, owner, cancellation);
            incoming = ordinaryCursor(evaluator, inventory, endpoint, to.evaluatorState(),
                cursor, pb, incoming, budget, owner);
            finalArc = finalArc.withCursor(incoming);
        }
        double scalarSlope = (to.offsetRasterPixels() - a.offsetRasterPixels()) / (rowB - rowA);
        double c = .16 * Math.abs(to.offsetRasterPixels() - a.offsetRasterPixels());
        double t = .12 * Math.abs(scalarSlope - from.key.scalar().lastSlope());
        finalArc = new ArcDraft(finalArc.kind(), finalArc.from(), finalArc.to(),
            finalArc.sourceSpan(), finalArc.q(), c, t, finalArc.k(), finalArc.mean(),
            finalArc.receipt(), finalArc.ordinaryCursor());
        arcs.add(finalArc);
        physical = nextPhysical(physical, finalArc, endpoint, to.evaluatorState(), incoming);
        points.add(new PointDraft(new EmissionMapping.ObservationVertex(rowB), to, pb));
        double qSum = 0, kSum = 0;
        for (var arc : arcs) { qSum += arc.q(); kSum += arc.k(); }
        var key = new Key(to, new ScalarMemory(rowA, a.offsetRasterPixels(), rowB,
            to.offsetRasterPixels(), scalarSlope, to.offsetOriginId()), physical,
            incoming == null ? null : new ReferenceIdentity(incoming),
            new PhaseContext(stageOrdinal, lastBoundaryId));
        return allocateNode(owner, budget, from, key, List.copyOf(points), List.copyOf(arcs),
            from.q + qSum, from.c + c, from.t + t, from.k + kSum,
            from.consumed + rowB - rowA - 1);
    }

    private ArcDraft ordinary(SampledEmissionInput input, MetricPoint from, MetricPoint to,
            double sourceFrom, double sourceTo, PhysicalLeg previous, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner, CancellationProbe cancellation) {
        double sourceSpan = sourceTo - sourceFrom;
        var context = input.context();
        RasterPoint ra = context.rasterAt(from), rb = context.rasterAt(to);
        var grid = input.context().nativeGrid();
        var ga = grid.toGrid(ra); var gb = grid.toGrid(rb);
        long cells = (long) Math.ceil(Math.abs(gb.x() - ga.x()) + Math.abs(gb.y() - ga.y())) + 16;
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        BilinearSegmentIntegral.Result integral;
        try (var scratch = owner.reserve(Math.addExact(4096, Math.multiplyExact(cells, 2048)))) {
            integral = BilinearSegmentIntegral.evaluate(grid, ga, gb, cancellation,
                () -> { cancellation.checkpoint(); budget.charge(EmissionWorkBudget.Operation.CELL_QUERY, 1); });
        }
        double pitch = input.context().nativePitchMeters().orElseThrow();
        boolean measured = integral.status() == BilinearSegmentIntegral.Status.COMPLETE;
        double mean = measured ? integral.mean().orElseThrow() : 0;
        double q = sourceSpan / pitch * (1 - mean);
        return new ArcDraft(measured ? EmissionResult.ArcKind.ORDINARY_MEASURED
                : EmissionResult.ArcKind.ORDINARY_UNAVAILABLE,
            from, to, sourceSpan, q, 0, 0, turn(previous, from, to, pitch),
            measured ? Optional.of(mean) : Optional.empty(), Optional.empty(), null);
    }

    /** Native adjacent admission is the unchanged Legacy row/event transition contract. */
    private static DirectionOrigin directionOrigin(Object incoming) {
        if (incoming instanceof NativeConnectorEvaluator.OrdinaryPhysicalLeg leg)
            return new DirectionOrigin(null, new ReferenceIdentity(leg));
        if (incoming instanceof NativeConnectorEvaluator.PhysicalDirectionCursor cursor)
            return cursor.optionalOrigin().isEmpty() && cursor.ordinaryOrigin().isEmpty() ? null
                : new DirectionOrigin(cursor.optionalOrigin().map(ReferenceIdentity::new).orElse(null),
                    cursor.ordinaryOrigin().map(ReferenceIdentity::new).orElse(null));
        if (incoming instanceof NativeConnectorEvaluator.Receipt receipt) {
            if (receipt.status() != NativeConnectorEvaluator.Status.ADMITTED) return null;
            if (receipt.emittedLengthMeters() > 0)
                return new DirectionOrigin(new ReferenceIdentity(receipt), null);
            if (receipt.transportedIncoming().isEmpty() && receipt.transportedOrdinary().isEmpty())
                return null;
            return new DirectionOrigin(receipt.transportedIncoming().map(ReferenceIdentity::new).orElse(null),
                receipt.transportedOrdinary().map(ReferenceIdentity::new).orElse(null));
        }
        return null;
    }

    private static Object ordinaryCursor(NativeConnectorEvaluator evaluator, Inventory inventory,
            NativeConnectorEvaluator.PhysicalEndpoint from,
            NativeConnectorEvaluator.PhysicalEndpoint to,
            MetricPoint fromPosition, MetricPoint toPosition, Object incoming,
            EmissionWorkBudget budget, AttemptMemoryLedger.Owner owner) {
        if (fromPosition.distanceTo(toPosition) > 0) {
            budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
            var edge = new OrdinaryLegIdentity(from, to);
            var cached = inventory.ordinaryLegs().get(edge);
            if (cached != null) return cached;
            var status = NativeConnectorEvaluator.NativeReferenceStatus.UNAVAILABLE;
            NativeConnectorEvaluator.OrdinaryPhysicalLeg leg;
            if (from instanceof NativeConnectorEvaluator.RowState a
                    && to instanceof NativeConnectorEvaluator.RowState b)
                leg = evaluator.registerOrdinaryPhysicalLeg(a, b, status);
            else if (from instanceof NativeConnectorEvaluator.RowState a
                    && to instanceof NativeConnectorEvaluator.Barrier b)
                leg = evaluator.registerOrdinaryPhysicalLeg(a, b, status);
            else if (from instanceof NativeConnectorEvaluator.Barrier a
                    && to instanceof NativeConnectorEvaluator.RowState b)
                leg = evaluator.registerOrdinaryPhysicalLeg(a, b, status);
            else if (from instanceof NativeConnectorEvaluator.Barrier a
                    && to instanceof NativeConnectorEvaluator.Barrier b)
                leg = evaluator.registerOrdinaryPhysicalLeg(a, b, status);
            else throw new IllegalArgumentException("Unknown ordinary physical endpoint type");
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
            owner.reserve(96);
            if(evaluator.progress()!=null)evaluator.progress().prepareMutation();
            inventory.ordinaryLegs().put(edge, leg);
            if(evaluator.progress()!=null)evaluator.progress().mutation(LegacyEmissionProgress.Counter.ORDINARY_LEGS);
            return leg;
        }
        var origin = directionOrigin(incoming);
        if (origin == null) return null; // First direction remains unavailable.
        budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
        var edge = new ZeroCursorIdentity(origin, from, to);
        var cached = inventory.zeroCursors().get(edge);
        if (cached != null) return cached;
        NativeConnectorEvaluator.PhysicalDirectionCursor cursor;
        if (incoming instanceof NativeConnectorEvaluator.Receipt receipt)
            cursor = evaluator.advanceOrdinaryZero(receipt, from, to);
        else if (incoming instanceof NativeConnectorEvaluator.OrdinaryPhysicalLeg leg)
            cursor = evaluator.advanceOrdinaryZero(leg, from, to);
        else if (incoming instanceof NativeConnectorEvaluator.PhysicalDirectionCursor prior)
            cursor = evaluator.advanceOrdinaryZero(prior, from, to);
        else throw new IllegalArgumentException("Unknown ordinary incoming cursor type");
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        owner.reserve(96);
        if(evaluator.progress()!=null)evaluator.progress().prepareMutation();
        inventory.zeroCursors().put(edge, cursor);
        if(evaluator.progress()!=null)evaluator.progress().mutation(LegacyEmissionProgress.Counter.ZERO_CURSORS);
        return cursor;
    }

    private static PhysicalLeg nextPhysical(PhysicalLeg previous, ArcDraft arc,
            Object fromNativeIdentity, Object toNativeIdentity,
            Object nativeReference) {
        return arc.from().distanceTo(arc.to()) > 0
            ? new PhysicalLeg(arc.from(), arc.to(), fromNativeIdentity, toNativeIdentity,
                nativeReference == null ? null : new ReferenceIdentity(nativeReference)) : previous;
    }

    static double turn(PhysicalLeg previous, MetricPoint from, MetricPoint to, double pitch) {
        double nextLength = from.distanceTo(to);
        if (previous == null || nextLength == 0) return 0;
        double ax = previous.to().xMeters() - previous.from().xMeters();
        double ay = previous.to().yMeters() - previous.from().yMeters();
        double bx = to.xMeters() - from.xMeters(), by = to.yMeters() - from.yMeters();
        double angle = Math.atan2(ax * by - ay * bx, ax * bx + ay * by);
        return angle * angle / Math.max(1, (previous.length() + nextLength) / (2 * pitch));
    }

    private static boolean crossesMandatory(LegacyEmissionDomain domain, int from, int to) {
        for (var event : domain.binding().protectedOccurrences())
            if (from <= event.leftOriginalRow() && to >= event.rightOriginalRow()) return true;
        for (var event : domain.binding().protectedOccurrences())
            if (event.leftOriginalRow() > from && event.leftOriginalRow() < to
                    || event.rightOriginalRow() > from && event.rightOriginalRow() < to) return true;
        return false;
    }

    private static List<LegacyEmissionBinding.ProtectedOccurrence> eventsBetween(
            LegacyEmissionBinding binding, int from, int to) {
        return binding.protectedOccurrences().stream()
            .filter(event -> event.leftOriginalRow() == from && event.rightOriginalRow() == to)
            .toList();
    }

    private static int contextRow(LegacyEmissionDomain domain, int originalRow) {
        // Original Legacy ordinals can be a strict subset of the retained source rows.
        return domain.binding().observations().get(originalRow).retainedObservationIndex();
    }

    private static double sourceChainage(LegacyEmissionDomain domain,
            OptionalEmissionContext context, int originalRow) {
        return context.sourceChainageMeters(contextRow(domain, originalRow));
    }

    private static double sourceSpanUpper(LegacyEmissionDomain domain,
            OptionalEmissionContext context, int fromOriginalRow, int toOriginalRow) {
        // Admission needs the direct source-arc enclosure, never rounded prefix differences.
        return context.sourceSpanUpperMeters(contextRow(domain, fromOriginalRow),
            contextRow(domain, toOriginalRow));
    }

    private static Node allocateNode(AttemptMemoryLedger.Owner owner, EmissionWorkBudget budget,
            Node previous, Key key, List<PointDraft> points, List<ArcDraft> arcs,
            double q, double c, double t, double k, int consumed) {
        return allocateNode(owner, budget, previous, key, points, arcs, q, c, t, k,
            consumed, previous == null ? -1 : previous.seedId);
    }

    private static Node allocateNode(AttemptMemoryLedger.Owner owner, EmissionWorkBudget budget,
            Node previous, Key key, List<PointDraft> points, List<ArcDraft> arcs,
            double q, double c, double t, double k, int consumed, int initialSeedId) {
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        var reservation = owner.reserve(Math.addExact(256,
            Math.addExact(128L * points.size(), 192L * arcs.size())));
        return new Node(previous, key, points, arcs, q, c, t, k, consumed, reservation,
            initialSeedId);
    }

    private static void insert(Map<Key, Node> layer, Node candidate, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner) {
        budget.charge(EmissionWorkBudget.Operation.PAIR_LOOKUP, 1);
        Node old = layer.get(candidate.key);
        if (better(candidate, old, budget, owner)) {
            candidate.inLayer = true;
            layer.put(candidate.key, candidate);
            if (old != null) {
                old.inLayer = false;
                releaseUnretained(old, budget);
            }
        } else releaseUnretained(candidate, budget);
    }

    private static void releaseUnretained(Node node, EmissionWorkBudget budget) {
        while (node != null && !node.inLayer && node.pinCount == 0
                && node.retainedChildren == 0 && !node.released) {
            budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
            node.released = true;
            node.memory.close();
            Node parent = node.previous;
            if (parent != null) parent.retainedChildren--;
            node = parent;
        }
    }

    private static void pinChain(Node terminal, EmissionWorkBudget budget) {
        for (Node node = terminal; node != null; node = node.previous) {
            budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
            if (node.released) throw new IllegalStateException("Cannot pin a released Legacy predecessor");
            node.pinCount = Math.addExact(node.pinCount, 1);
        }
    }

    private static void unpinChain(Node terminal, EmissionWorkBudget budget) {
        for (Node node = terminal; node != null; node = node.previous) {
            budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
            if (node.pinCount <= 0 || node.released)
                throw new IllegalStateException("Legacy predecessor pin lifetime is unbalanced");
            node.pinCount--;
        }
        releaseUnretained(terminal, budget);
    }

    private static boolean better(Node left, Node right, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner) {
        if (left == null) return false;
        if (right == null) return true;
        budget.charge(EmissionWorkBudget.Operation.TIE_COMPARISON, 1);
        int objective = Double.compare(left.total(), right.total());
        if (objective != 0) return objective < 0;
        int consumed = Integer.compare(left.consumed, right.consumed);
        return consumed != 0 ? consumed < 0 : compareTie(left, right, budget, owner) < 0;
    }

    private static int compareTie(Node left, Node right, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner) {
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 2);
        try (var scratch = owner.reserve(AttemptMemoryLedger.referenceArrayBytes(left.depth)
                + AttemptMemoryLedger.referenceArrayBytes(right.depth))) {
            var a = new Node[left.depth]; var b = new Node[right.depth];
            for (Node node = left; node != null; node = node.previous) {
                budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
                a[node.depth - 1] = node;
            }
            for (Node node = right; node != null; node = node.previous) {
                budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
                b[node.depth - 1] = node;
            }
            for (int i = 0; i < Math.min(a.length, b.length); i++) {
                budget.charge(EmissionWorkBudget.Operation.TIE_COMPARISON, 1);
                var x = a[i].key.last(); var y = b[i].key.last();
                int row = Integer.compare(x.originalRow(), y.originalRow());
                if (row != 0) return row;
                int state = Integer.compare(x.nativeStateId(), y.nativeStateId());
                if (state != 0) return state;
                int origin = Integer.compare(x.offsetOriginId(), y.offsetOriginId());
                if (origin != 0) return origin;
            }
            return Integer.compare(a.length, b.length);
        }
    }

    private record Flattened(List<PointDraft> points, List<ArcDraft> arcs,
            AttemptMemoryLedger.Reservation memory) implements AutoCloseable {
        @Override public void close() { memory.close(); }
    }
    private static Flattened flatten(Node terminal, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner) {
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 3);
        var memory = owner.reserve(Math.addExact(256, Math.multiplyExact(32L, terminal.depth)));
        try {
            var nodes = new ArrayList<Node>();
            for (Node node = terminal; node != null; node = node.previous) {
                budget.charge(EmissionWorkBudget.Operation.BACKPOINTER_VISIT, 1);
                nodes.add(node);
            }
            var points = new ArrayList<PointDraft>(); var arcs = new ArrayList<ArcDraft>();
            for (int i = nodes.size() - 1; i >= 0; i--) {
                points.addAll(nodes.get(i).addedPoints); arcs.addAll(nodes.get(i).addedArcs);
            }
            return new Flattened(List.copyOf(points), List.copyOf(arcs), memory);
        } catch (RuntimeException failure) {
            memory.close();
            throw failure;
        }
    }

    private EmissionResult result(SampledEmissionInput input, LegacyEmissionDomain domain,
            Flattened path, Flattened firstPath, Node firstNode, Node best,
            List<EmissionResult.Costs> intervals,
            List<EmissionResult.IntervalStageReference> intervalReferences,
            List<AnchorProposal> eligible, Inventory inventory,
            Smoother smoother, AttemptMemoryLedger.Owner owner,
            AttemptMemoryLedger.Owner kernelOwner,
            NativeConnectorEvaluator evaluator, EmissionWorkBudget budget,
            CancellationProbe cancellation, SampledEmissionInput.ResultLease resultLease,
            NativeConnectorEvaluator.QueryPolicy queryPolicy, NativeConnectorEvaluator.ExecutionPolicy executionPolicy,
            LegacyEmissionProgress progress,GroupedFacts grouped) {
        if (domain.measuredModes() != null) input.requireOpenAndUnchanged();
        int n = domain.rowCount();
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 6);
        owner.reserve(Math.addExact(1024, Math.addExact(Math.multiplyExact(96L, n),
            Math.multiplyExact(512L, path.points().size()))));
        var vertexOwners = new ArrayList<EmissionMapping.VertexOwner>();
        var observationOwners = new ArrayList<EmissionMapping.ObservationOwner>();
        for (int row = 0; row < n; row++) observationOwners.add(null);
        var vertices = new ArrayList<EmissionResult.Vertex>();
        for (int index = 0; index < path.points().size(); index++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var point = path.points().get(index);
            vertexOwners.add(point.owner());
            if (point.owner() instanceof EmissionMapping.ObservationVertex observed) {
                observationOwners.set(observed.observationIndex(), new EmissionMapping.Emitted(index));
                vertices.add(vertex(point));
            } else vertices.add(vertex(point));
        }
        for (int arc = 0; arc < path.arcs().size(); arc++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var left = vertexOwners.get(arc); var right = vertexOwners.get(arc + 1);
            if (left instanceof EmissionMapping.ObservationVertex a
                    && right instanceof EmissionMapping.ObservationVertex b)
                for (int row = a.observationIndex() + 1; row < b.observationIndex(); row++)
                    observationOwners.set(row, new EmissionMapping.Consumed(arc));
        }
        Set<Integer> mandatory = new HashSet<>(Set.of(0, n - 1));
        for (var event : domain.binding().protectedOccurrences()) {
            mandatory.add(event.leftOriginalRow()); mandatory.add(event.rightOriginalRow());
        }
        var mapping = EmissionMapping.create(n, vertexOwners, observationOwners, mandatory);
        var arcs = arcResults(path.arcs());
        if(progress!=null)progress.phase(LegacyEmissionProgress.Phase.SMOOTH,0,-1,-1);
        List<EmissionResult.SmoothingOutcome> smoothingOutcomes = inventory.affinity() == null
            ? List.of() : new ArrayList<>();
        List<EmissionResult.ManagedSmoothingOutcome> managedOutcomes=inventory.managedAffinity()==null?List.of():new ArrayList<>();
        var smoothed = inventory.managedAffinity()==null
            ?smooth(path,smoother,domain,input.context(),budget,owner,inventory.affinity(),smoothingOutcomes)
            :smoothManaged(path,smoother,domain,input.context(),budget,owner,inventory.managedAffinity(),managedOutcomes);
        var smoothedArcs = arcResults(remeasure(input, path, smoothed, best.seedOffset,
            owner, budget, cancellation));
        if(progress!=null){progress.phaseComplete();progress.phase(LegacyEmissionProgress.Phase.RESULT,0,-1,-1);}
        if (smoothedArcs.stream().noneMatch(arc -> arc.lengthMeters() > 0))
            throw new UnavailableException(UnavailableReason.NO_POSITIVE_PHYSICAL_LEG);
        var first = new EmissionResult.Costs(firstNode.q, firstNode.c, firstNode.t, firstNode.k);
        var stitched = new EmissionResult.Costs(best.q, best.c, best.t, best.k);
        var post = totals(smoothedArcs, best.seedOffset,
            path.points().get(0).state().offsetRasterPixels());
        double source = 0, measured = 0, length = 0;
        for (var arc : arcs) source += arc.sourceSpanMeters();
        for (var arc : smoothedArcs) {
            length += arc.lengthMeters();
            if (arc.meanNativeIntensity().isPresent()) measured += arc.lengthMeters();
        }
        var sourceRecords = new ArrayList<EmissionResult.OriginalObservation>(n);
        for (var observation : domain.binding().observations()) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var raster = observation.measurementProfile().samplingAnchor().rasterPoint();
            var metric = input.context().metricAt(raster);
            sourceRecords.add(new EmissionResult.OriginalObservation(observation.originalRow(),
                observation.retainedObservationIndex(), observation.sourceClass(), raster, metric,
                input.context().profileChainage().sourceOriginGroundMeters()
                    + input.context().sourceChainageMeters(observation.retainedObservationIndex()),
                observation.producerPeaks().stream().map(LegacyEmissionBinding.PeakOwner::originalPeakId).toList()));
        }
        var provenance = provenance(input, domain, firstPath, firstNode, best,
            intervalReferences, eligible, inventory, owner, budget, queryPolicy, executionPolicy,grouped);
        var affinityDiagnostics = affinityDiagnostics(domain, inventory, path, vertices, smoothed,
            owner, budget, smoothingOutcomes);
        var managedDiagnostics=managedAffinityDiagnostics(domain,inventory,path,vertices,smoothed,input.context(),owner,budget,managedOutcomes);
        if (domain.measuredModes() != null||inventory.managedAffinity()!=null) input.requireOpenAndUnchanged();
        // The result retains immutable vertex/arc values, never the DP predecessor graph.
        unpinChain(best, budget);
        unpinChain(firstNode, budget);
        try(var publication=inventory.managedAffinity()==null||grouped!=null?null:input.prepareManagedFinalPublication(owner,
                256L+16L*(sourceRecords.size()+vertices.size()+arcs.size()+smoothedArcs.size()+intervals.size()),
                2048L+128L*(sourceRecords.size()+vertices.size()+arcs.size()+smoothedArcs.size()+intervals.size()))){
        var candidate=new EmissionResult(sourceRecords, mapping, vertices, smoothed,
            arcs, smoothedArcs, first, intervals, stitched, post, source, measured, length,
            budget.snapshot(), owner.peakBytes(), provenance, () -> {
                try {
                    try{if(inventory.managedAffinity()!=null)inventory.managedAffinity().close();}
                    finally{evaluator.close();}
                } finally {
                    try {
                        owner.close();
                    } finally {
                        try {
                            kernelOwner.close();
                        } finally {
                            resultLease.close();
                        }
                    }
                }
            }, affinityDiagnostics,managedDiagnostics);
        if(publication!=null)try{publication.validate();}
        catch(RuntimeException failure){try{candidate.close();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}throw failure;}
        // Only uncharged ownership closes remain on the managed route (progress is absent).
        return candidate;
        }
    }

    private Optional<EmissionResult.ManagedAffinityDiagnostics> managedAffinityDiagnostics(LegacyEmissionDomain domain,
            Inventory inventory,Flattened path,List<EmissionResult.Vertex> raw,List<EmissionResult.Vertex> finalGeometry,
            OptionalEmissionContext context,AttemptMemoryLedger.Owner owner,EmissionWorkBudget budget,List<EmissionResult.ManagedSmoothingOutcome> outcomes){
        var classifier=inventory.managedAffinity();if(classifier==null)return Optional.empty();
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);budget.charge(EmissionWorkBudget.Operation.PREPARATION,64L+path.arcs().size()*2L+outcomes.size());
        owner.reserve(512L+256L*path.arcs().size()+64L*outcomes.size());
        var inference=managedAffinityArcs(classifier,domain,path,raw,context,budget);
        var actualFinal=managedAffinityArcs(classifier,domain,path,finalGeometry,context,budget);
        return Optional.of(new EmissionResult.ManagedAffinityDiagnostics(ManagedOrdinaryAffinity.POLICY_ID,inference,actualFinal,
            classifier.preparedRowPairs(),classifier.stationExtractions(),outcomes,NativeMeasuredProfileReceipt.Availability.UNAVAILABLE));
    }
    private List<EmissionResult.ManagedAffinityArcReference> managedAffinityArcs(ManagedOrdinaryAffinity classifier,
            LegacyEmissionDomain domain,Flattened path,List<EmissionResult.Vertex> geometry,OptionalEmissionContext context,EmissionWorkBudget budget){
        var result=new ArrayList<EmissionResult.ManagedAffinityArcReference>();
        for(int i=0;i<path.arcs().size();i++){
            budget.charge(EmissionWorkBudget.Operation.PREPARATION,1);
            boolean moved=moved(geometry.get(i),path.points().get(i))||moved(geometry.get(i+1),path.points().get(i+1));
            if(!moved&&path.arcs().get(i).kind()==EmissionResult.ArcKind.OPTIONAL_ADMITTED){
                result.add(new EmissionResult.ManagedAffinityArcReference(i,true,Optional.empty()));continue;
            }
            var from=path.points().get(i).state();var to=path.points().get(i+1).state();
            ManagedOrdinaryAffinity.Receipt receipt=null;
            if(from!=null&&to!=null){
                receipt=moved?classifier.classifyFinalGeometry(from,to,geometry.get(i),geometry.get(i+1)):classifier.classify(from,to);
                if(receipt.status()==OrdinaryAffinityClassifier.Status.INCOMPATIBLE||moved&&receipt.status()!=OrdinaryAffinityClassifier.Status.COMPATIBLE)
                    throw new UnavailableException(UnavailableReason.FINAL_AFFINITY_CONFLICT);
            }
            result.add(new EmissionResult.ManagedAffinityArcReference(i,false,Optional.ofNullable(receipt)));
        }return List.copyOf(result);
    }

    private Optional<EmissionResult.AffinityDiagnostics> affinityDiagnostics(LegacyEmissionDomain domain,
            Inventory inventory, Flattened path, List<EmissionResult.Vertex> vertices,
            List<EmissionResult.Vertex> smoothed, AttemptMemoryLedger.Owner owner, EmissionWorkBudget budget,
            List<EmissionResult.SmoothingOutcome> smoothingOutcomes) {
        if (inventory.affinity() == null) return Optional.empty();
        long states = 0;
        for (int row = 0; row < domain.rowCount(); row++) states += domain.observedAt(row).size();
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        owner.reserve(512 + states * 256 + path.arcs().size() * 256L);
        var measured = new ArrayList<EmissionResult.MeasuredStateReference>();
        for (int row = 0; row < domain.rowCount(); row++) for (var state : domain.observedAt(row)) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var alias = domain.measuredModes().ownerFor(state.evaluatorState());
            if (alias != null) measured.add(new EmissionResult.MeasuredStateReference(row, alias.retainedRow(),
                state.nativeStateId(), state.offsetOriginId(), state.kind() == LegacyEmissionDomain.Kind.OBSERVED_SCALAR_MODE
                    ? EmissionResult.MeasuredOwnerKind.ACCEPTED_SCALAR_CENTER : EmissionResult.MeasuredOwnerKind.NATIVE_PEAK_ALIAS,
                state.offsetRasterPixels(), alias.metersPerRasterOffset(), alias.nativePitchMeters(),
                alias.receipt(), alias.rawCore(), alias.flanks()));
        }
        var inference = affinityArcs(inventory.affinity(), path, vertices, budget);
        var finalGeometry = affinityArcs(inventory.affinity(), path, smoothed, budget);
        return Optional.of(new EmissionResult.AffinityDiagnostics(ExperimentalMeasuredModeInventory.POLICY_ID,
            measured, inference, finalGeometry, inventory.affinity().preparedRowPairs(),
            inventory.affinity().stationExtractions(), smoothingOutcomes));
    }

    private List<EmissionResult.AffinityArcReference> affinityArcs(OrdinaryAffinityClassifier classifier,
            Flattened path, List<EmissionResult.Vertex> geometry, EmissionWorkBudget budget) {
        var result = new ArrayList<EmissionResult.AffinityArcReference>();
        for (int i = 0; i < path.arcs().size(); i++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            if (path.arcs().get(i).kind() == EmissionResult.ArcKind.OPTIONAL_ADMITTED) {
                result.add(new EmissionResult.AffinityArcReference(i, true, Optional.empty()));
                continue;
            }
            var from = path.points().get(i).state(); var to = path.points().get(i + 1).state();
            OrdinaryAffinityClassifier.Receipt receipt = null;
            if (from != null && to != null) {
                boolean moved = moved(geometry.get(i), path.points().get(i))
                    || moved(geometry.get(i + 1), path.points().get(i + 1));
                receipt = moved ? classifier.classifyFinalInValidatedExecution(from, to,
                    geometry.get(i).position(), geometry.get(i).offsetRasterPixels().orElseThrow(),
                    geometry.get(i + 1).position(), geometry.get(i + 1).offsetRasterPixels().orElseThrow())
                    : classifier.classifyInValidatedExecution(from, to, geometry.get(i).position(), geometry.get(i + 1).position());
                if (receipt.status() == OrdinaryAffinityClassifier.Status.INCOMPATIBLE)
                    throw new UnavailableException(UnavailableReason.FINAL_AFFINITY_CONFLICT);
                if (moved && receipt.status() != OrdinaryAffinityClassifier.Status.COMPATIBLE)
                    throw new UnavailableException(UnavailableReason.FINAL_AFFINITY_CONFLICT);
            }
            result.add(new EmissionResult.AffinityArcReference(i, false, Optional.ofNullable(receipt)));
        }
        return List.copyOf(result);
    }

    private static EmissionResult.Provenance provenance(SampledEmissionInput input,
            LegacyEmissionDomain domain, Flattened firstPath, Node firstNode, Node best,
            List<EmissionResult.IntervalStageReference> intervalReferences,
            List<AnchorProposal> eligible, Inventory inventory,
            AttemptMemoryLedger.Owner owner, EmissionWorkBudget budget,
            NativeConnectorEvaluator.QueryPolicy queryPolicy, NativeConnectorEvaluator.ExecutionPolicy executionPolicy,GroupedFacts grouped) {
        var binding = domain.binding();
        long peakCount = binding.observations().stream()
            .mapToLong(observation -> observation.producerPeaks().size()).sum();
        long generatedCount = inventory.reachedCarries().size() + (long) inventory.intervalTargets().size();
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        owner.reserve(Math.addExact(1024, Math.addExact(Math.multiplyExact(160, peakCount),
            Math.addExact(Math.multiplyExact(160, generatedCount),
                Math.multiplyExact(192L, intervalReferences.size())))));
        var context = input.context();
        boolean revised = domain.measuredModes() != null;
        boolean managed=inventory.managedAffinity()!=null;
        var source = new EmissionResult.SourceReference(context.sourceIdentity(),
            context.evidenceContentHash(), context.requestIdentity(),managed?context.policyId()+"/"+ManagedOrdinaryAffinity.POLICY_ID: revised
                ? context.policyId() + "/" + ExperimentalMeasuredModeInventory.POLICY_ID : context.policyId(),
            managed?(grouped==null?"LEGACY_OPTIONAL_MANAGED_LOCAL_CONNECTOR_V1":managedGroupedModelPolicyId(grouped.execution(),queryPolicy,executionPolicy,owner,budget)):revised ? "LEGACY_OPTIONAL_MEASURED_AFFINITY_V1"
                : grouped==null?modelPolicyId(queryPolicy, executionPolicy, owner, budget)
                : groupedModelPolicyId(grouped.execution(),queryPolicy,executionPolicy,owner,budget), binding.trackerNormalizationRasterPx(),
            context.nativePitchMeters().orElseThrow(), binding.productionEligible());
        var seeds = domain.seeds().stream().map(seed ->
            new EmissionResult.SeedReference(seed.id(), seed.offsetRasterPixels())).toList();
        var chosen = seeds.stream().filter(seed -> seed.id() == best.seedId)
            .findFirst().orElseThrow();
        if (Double.doubleToLongBits(chosen.offsetRasterPixels())
                != Double.doubleToLongBits(best.seedOffset))
            throw new IllegalStateException("Chosen Legacy seed identity changed");
        double edgeLimit = Double.NaN;
        for (int row = 0; row < domain.rowCount(); row++)
            for (var peak : domain.suppressedProfile(row).peaks())
                edgeLimit = Double.isNaN(edgeLimit) ? Math.abs(peak.offsetPx())
                    : Math.max(edgeLimit, Math.abs(peak.offsetPx()));
        var tables = new ArrayList<EmissionResult.NativeStateTable>();
        var origins = new ArrayList<EmissionResult.OriginReference>();
        for (var seed : seeds)
            origins.add(new EmissionResult.OriginReference(seed.id(), Optional.of(seed), Optional.empty()));
        for (int row = 0; row < domain.rowCount(); row++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var observation = binding.observations().get(row);
            var profile = domain.suppressedProfile(row);
            var references = new ArrayList<EmissionResult.NativePeakReference>();
            for (var peakOwner : observation.producerPeaks()) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                var peak = peakOwner.peak();
                boolean eligibleState = domain.observedAt(row).stream()
                    .anyMatch(state -> state.nativeStateId() == peakOwner.originalPeakId()
                        && state.peakOwner() == peak);
                if (eligibleState) {
                    double reward = nativeEvidenceReward(peak);
                    double penalty = nativeLocalPenalty(profile, peak,
                        binding.trackerNormalizationRasterPx(), edgeLimit);
                    references.add(new EmissionResult.NativePeakReference(peakOwner.originalPeakId(),
                        peak.offsetPx(), peak.intensity(), true, java.util.OptionalDouble.of(reward),
                        java.util.OptionalDouble.of(penalty), java.util.OptionalDouble.of(reward - penalty)));
                } else references.add(new EmissionResult.NativePeakReference(peakOwner.originalPeakId(),
                    peak.offsetPx(), peak.intensity(), false, java.util.OptionalDouble.empty(),
                    java.util.OptionalDouble.empty(), java.util.OptionalDouble.empty()));
            }
            var nativeProfile = observation.profile();
            var normal = nativeProfile.normalScreen();
            tables.add(new EmissionResult.NativeStateTable(row, observation.sourceClass(),
                nativeProfile.samplingAnchor().rasterPoint(), new RasterPoint(normal.x, normal.y),
                references));
            for (var state : domain.observedAt(row)) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                if (state.kind() == LegacyEmissionDomain.Kind.OBSERVED_SCALAR_MODE)
                    origins.add(new EmissionResult.OriginReference(state.offsetOriginId(), Optional.empty(),
                        Optional.empty(), Optional.of(new EmissionResult.MeasuredOriginReference(row, state.nativeStateId()))));
                else origins.add(new EmissionResult.OriginReference(state.offsetOriginId(),
                    Optional.empty(), Optional.of(new EmissionResult.AnchorReference(row, state.nativeStateId()))));
            }
        }
        origins.sort(Comparator.comparingInt(EmissionResult.OriginReference::originId));
        var generated = new ArrayList<EmissionResult.GeneratedStateReference>();
        for (var entry : inventory.reachedCarries().entrySet()) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var state = entry.getValue();
            generated.add(new EmissionResult.GeneratedStateReference(state.originalRow(),
                state.nativeStateId(), EmissionResult.StateKind.BASE_CARRY,
                state.offsetRasterPixels(), entry.getKey().originId(), Optional.empty(),
                Optional.empty()));
        }
        for (var entry : inventory.intervalTargets().entrySet()) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var state = entry.getValue();
            var left = eligible.stream().filter(anchor -> anchor.originalRow() == entry.getKey().leftAnchorRow())
                .findFirst().orElseThrow();
            var right = eligible.stream().filter(anchor -> anchor.originalRow() == entry.getKey().rightAnchorRow())
                .findFirst().orElseThrow();
            generated.add(new EmissionResult.GeneratedStateReference(state.originalRow(),
                state.nativeStateId(), EmissionResult.StateKind.INTERVAL_TARGET,
                state.offsetRasterPixels(), state.offsetOriginId(),
                Optional.of(anchorReference(domain, left)), Optional.of(anchorReference(domain, right))));
        }
        generated.sort(Comparator.comparingInt(EmissionResult.GeneratedStateReference::originalRow)
            .thenComparingInt(EmissionResult.GeneratedStateReference::nativeStateId)
            .thenComparingInt(EmissionResult.GeneratedStateReference::originId));
        var eligibleReferences = eligible.stream().map(anchor -> anchorReference(domain, anchor)).toList();
        var selected = new java.util.LinkedHashSet<EmissionResult.AnchorReference>();
        for (var stage : intervalReferences) {
            selected.add(stage.leftAnchor()); selected.add(stage.rightAnchor());
        }
        var firstVertices = firstPath.points().stream().map(LegacyEmissionKernel::vertex).toList();
        return new EmissionResult.Provenance(source, seeds, chosen, tables, origins, generated,
            eligibleReferences, List.copyOf(selected), firstVertices, arcResults(firstPath.arcs()),
            intervalReferences);
    }

    private static String groupedModelPolicyId(GroupedExecution grouped,NativeConnectorEvaluator.QueryPolicy query,
            NativeConnectorEvaluator.ExecutionPolicy execution,AttemptMemoryLedger.Owner owner,EmissionWorkBudget budget) {
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);owner.reserve(1024);
        return "LEGACY_GROUPED_EXACT_V1/kernel="+grouped+"/query="+query+"/execution="+execution;
    }
    private static String managedGroupedModelPolicyId(GroupedExecution grouped,NativeConnectorEvaluator.QueryPolicy query,
            NativeConnectorEvaluator.ExecutionPolicy execution,AttemptMemoryLedger.Owner owner,EmissionWorkBudget budget){
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);owner.reserve(1024);
        return "LEGACY_GROUPED_MANAGED_LOCAL_CONNECTOR_V1/kernel="+grouped+"/query="+query+"/execution="+execution+"/affinity="+ManagedOrdinaryAffinity.POLICY_ID;
    }
    private static String modelPolicyId(NativeConnectorEvaluator.QueryPolicy queryPolicy,
            NativeConnectorEvaluator.ExecutionPolicy executionPolicy,
            AttemptMemoryLedger.Owner owner, EmissionWorkBudget budget) {
        String base = "LEGACY_OPTIONAL_NATIVE_INTEGRAL_V2";
        if (queryPolicy == NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1
                && executionPolicy == NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1) return base;
        int characters = base.length() + "/query=".length() + queryPolicy.name().length()
            + "/execution=".length() + executionPolicy.name().length();
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        owner.reserve(AttemptMemoryLedger.objectBytes(24) + AttemptMemoryLedger.arrayBytes(characters, Character.BYTES));
        return base + "/query=" + queryPolicy.name() + "/execution=" + executionPolicy.name();
    }

    private static EmissionResult.AnchorReference anchorReference(LegacyEmissionDomain domain,
            AnchorProposal proposal) {
        return new EmissionResult.AnchorReference(proposal.originalRow(),
            domain.originalPeakId(proposal.peakOwner()));
    }

    private static double nativeEvidenceReward(CrossSectionPeak peak) {
        return peak.intensity() * 2.25
            + peak.gradientStrength() * (0.22 + 0.18 * peak.gradientBalance())
            + peak.nativeFilteredAgreement() * 0.12
            + peak.scaleAgreement() * 0.06;
    }

    private static double nativeLocalPenalty(CrossSectionProfile profile, CrossSectionPeak peak,
            double sourcePixelSizePx, double edgeLimitPx) {
        double strongest = profile.peaks().stream().mapToDouble(CrossSectionPeak::intensity)
            .max().orElse(0.0);
        double regret = Math.max(0.0, strongest - peak.intensity());
        double penalty = regret * 4.2;
        if (Double.isFinite(edgeLimitPx) && edgeLimitPx > 0.0
                && Math.abs(peak.offsetPx()) >= edgeLimitPx * 0.88
                && peak.supportWidthPx() <= Math.max(3.0, sourcePixelSizePx * 0.30))
            penalty += 0.65 + regret * 2.0;
        if (peak.supportWidthPx() <= sourcePixelSizePx * 0.15
                && strongest > peak.intensity() + 0.03)
            penalty += 0.30;
        penalty += (1.0 - peak.nativeFilteredAgreement()) * 0.18;
        penalty += (1.0 - peak.scaleAgreement()) * 0.10;
        penalty += Math.min(0.12, peak.centerUncertaintyPx()
            / Math.max(1.0, sourcePixelSizePx) * 0.03);
        return penalty;
    }

    private static EmissionResult.Vertex vertex(PointDraft point) {
        if (point.state() == null)
            return new EmissionResult.Vertex(point.position(), point.owner(),
                EmissionResult.StateKind.BOUNDARY_EVENT, Optional.empty(), Optional.empty());
        var state = point.state();
        var kind = switch (state.kind()) {
            case OBSERVED_PEAK, OBSERVED_SCALAR_MODE -> EmissionResult.StateKind.OBSERVED_PEAK;
            case BASE_CARRY -> EmissionResult.StateKind.BASE_CARRY;
            case INTERVAL_TARGET -> EmissionResult.StateKind.INTERVAL_TARGET;
        };
        return new EmissionResult.Vertex(point.position(), point.owner(), kind,
            Optional.of(state.nativeStateId()), Optional.of(state.offsetRasterPixels()));
    }

    private static List<EmissionResult.Arc> arcResults(List<ArcDraft> drafts) {
        var result = new ArrayList<EmissionResult.Arc>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            var arc = drafts.get(i);
            result.add(new EmissionResult.Arc(i, arc.kind(), arc.sourceSpan(),
                arc.from().distanceTo(arc.to()), arc.q(), arc.c(), arc.t(), arc.k(), arc.mean(),
                arc.receipt(),
                arc.ordinaryCursor() instanceof NativeConnectorEvaluator.OrdinaryPhysicalLeg leg
                    ? Optional.of(leg) : Optional.empty(),
                arc.ordinaryCursor() instanceof NativeConnectorEvaluator.PhysicalDirectionCursor cursor
                    ? Optional.of(cursor) : Optional.empty()));
        }
        return List.copyOf(result);
    }

    /** One unchanged smoother proposal, followed by fresh managed evidence or whole raw-run retention. */
    private static List<EmissionResult.Vertex> smoothManaged(Flattened path,Smoother smoother,
            LegacyEmissionDomain domain,OptionalEmissionContext context,EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner,ManagedOrdinaryAffinity classifier,
            List<EmissionResult.ManagedSmoothingOutcome> outcomes){
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);
        budget.charge(EmissionWorkBudget.Operation.PREPARATION,64L+8L*path.points().size());
        owner.reserve(2048L+1024L*path.points().size());
        var raw=path.points().stream().map(LegacyEmissionKernel::vertex).toList();
        var actual=new ArrayList<>(smooth(path,smoother,domain,context,budget,owner,null,List.of()));
        int start=0;
        while(start<path.points().size()){
            budget.charge(EmissionWorkBudget.Operation.PREPARATION,1);
            if(path.points().get(start).state()==null){start++;continue;}
            int end=start;
            while(end+1<path.points().size()&&path.points().get(end+1).state()!=null
                    &&path.points().get(end+1).state().originalRow()==path.points().get(end).state().originalRow()+1)end++;
            if(end-start>=2){
                budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);budget.charge(EmissionWorkBudget.Operation.PREPARATION,end-start+1L);
                owner.reserve(64L+16L*(end-start+1L));
                var states=path.points().subList(start,end+1).stream().map(PointDraft::state).toList();
                outcomes.add(classifier.certifyRun(states,raw.subList(start,end+1),actual.subList(start,end+1),start));
            }start=end+1;
        }return List.copyOf(actual);
    }

    private static List<EmissionResult.Vertex> smooth(Flattened path, Smoother smoother,
            LegacyEmissionDomain domain, OptionalEmissionContext context, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner, OrdinaryAffinityClassifier affinity,
            List<EmissionResult.SmoothingOutcome> outcomes) {
        var vertices = new ArrayList<EmissionResult.Vertex>();
        for (var point : path.points()) vertices.add(vertex(point));
        int start = 0;
        while (start < path.points().size()) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            if (path.points().get(start).state() == null) { start++; continue; }
            int end = start;
            while (end + 1 < path.points().size()
                    && path.points().get(end + 1).state() != null
                    && path.points().get(end + 1).state().originalRow()
                        == path.points().get(end).state().originalRow() + 1) end++;
            if (end - start >= 2) {
                if (affinity != null) {
                    budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
                    owner.reserve(256L + 512L * (end - start + 1));
                }
                var rawRun = affinity == null ? List.<EmissionResult.Vertex>of()
                    : List.copyOf(vertices.subList(start, end + 1));
                var profiles = new ArrayList<CrossSectionProfile>();
                var offsets = new ArrayList<Double>(); var intensities = new ArrayList<Double>();
                for (int i = start; i <= end; i++) {
                    var state = path.points().get(i).state();
                    profiles.add(domain.binding().observations().get(state.originalRow()).profile());
                    offsets.add(state.offsetRasterPixels());
                    var measured = domain.measuredModes() == null ? null
                        : domain.measuredModes().ownerFor(state.evaluatorState());
                    intensities.add(state.peakOwner() != null ? state.peakOwner().intensity()
                        : measured != null ? measured.receipt().originPeak().value() : 0.0);
                }
                var result = smoother.smooth(List.copyOf(profiles), List.copyOf(offsets), List.copyOf(intensities));
                if (result.size() != offsets.size())
                    throw new IllegalArgumentException("Legacy smoother changed run length");
                for (int i = start + 1; i < end; i++) {
                    var state = path.points().get(i).state();
                    var profile = domain.binding().observations().get(state.originalRow()).measurementProfile();
                    double offset = result.get(i - start);
                    if (!Double.isFinite(offset)) throw new IllegalArgumentException("Legacy smoother produced nonfinite offset");
                    var rp = new RasterPoint(profile.anchorScreen().x + profile.normalScreen().x * offset,
                        profile.anchorScreen().y + profile.normalScreen().y * offset);
                    var mp = context.metricAt(rp);
                    vertices.set(i, new EmissionResult.Vertex(mp,
                        path.points().get(i).owner(), vertices.get(i).kind(),
                        vertices.get(i).nativeStateId(), Optional.of(offset)));
                }
                if (affinity != null) {
                    var evidence = new ArrayList<OrdinaryAffinityClassifier.Receipt>();
                    boolean changed = false, safe = true;
                    var reason = EmissionResult.SmoothingReason.NO_CHANGE;
                    for (int edge = start; edge < end; edge++) {
                        budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                        if (!moved(vertices.get(edge), path.points().get(edge))
                                && !moved(vertices.get(edge + 1), path.points().get(edge + 1))) continue;
                        changed = true;
                        var receipt = affinity.classifyFinalInValidatedExecution(path.points().get(edge).state(),
                            path.points().get(edge + 1).state(), vertices.get(edge).position(),
                            vertices.get(edge).offsetRasterPixels().orElseThrow(), vertices.get(edge + 1).position(),
                            vertices.get(edge + 1).offsetRasterPixels().orElseThrow());
                        evidence.add(receipt);
                        if (receipt.status() != OrdinaryAffinityClassifier.Status.COMPATIBLE) {
                            safe = false;
                            reason = receipt.status() == OrdinaryAffinityClassifier.Status.INCOMPATIBLE
                                ? EmissionResult.SmoothingReason.FINAL_BRANCH_CONFLICT
                                : EmissionResult.SmoothingReason.FINAL_EVIDENCE_UNAVAILABLE;
                            break;
                        }
                    }
                    if (!safe) for (int i = start; i <= end; i++) vertices.set(i, rawRun.get(i - start));
                    if (changed && safe) reason = EmissionResult.SmoothingReason.MEASURED_COMPATIBLE;
                    outcomes.add(new EmissionResult.SmoothingOutcome(start, end,
                        !changed ? EmissionResult.SmoothingDisposition.UNCHANGED : safe
                            ? EmissionResult.SmoothingDisposition.ACCEPTED : EmissionResult.SmoothingDisposition.RAW_RETAINED,
                        reason, evidence));
                }
            }
            start = end + 1;
        }
        return List.copyOf(vertices);
    }

    private static boolean moved(EmissionResult.Vertex actual, PointDraft raw) {
        return !actual.position().equals(raw.position()) || raw.state() != null
            && !ExperimentalMeasuredModeInventory.same(actual.offsetRasterPixels().orElseThrow(), raw.state().offsetRasterPixels());
    }

    private List<ArcDraft> remeasure(SampledEmissionInput input, Flattened path,
            List<EmissionResult.Vertex> smoothed, double seedOffset, AttemptMemoryLedger.Owner owner,
            EmissionWorkBudget budget, CancellationProbe cancellation) {
        var result = new ArrayList<ArcDraft>();
        PhysicalLeg physical = null;
        int numericRow = path.points().get(0).state().originalRow();
        double numericOffset = smoothed.get(0).offsetRasterPixels().orElseThrow();
        double scalarSlope = numericOffset - seedOffset;
        double pitch = input.context().nativePitchMeters().orElseThrow();
        for (int i = 0; i < path.arcs().size(); i++) {
            budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
            var original = path.arcs().get(i);
            MetricPoint a = smoothed.get(i).position(), b = smoothed.get(i + 1).position();
            ArcDraft measured;
            if (original.kind() == EmissionResult.ArcKind.OPTIONAL_ADMITTED) {
                if (!a.equals(original.from()) || !b.equals(original.to()))
                    throw new IllegalStateException("Smoother changed a consumed-arc endpoint");
                measured = original;
            } else {
                double sourceFrom = 0;
                measured = ordinary(input, a, b, sourceFrom,
                    sourceFrom + original.sourceSpan(), physical, budget, owner, cancellation);
            }
            double c = 0, t = 0;
            var nextPoint = path.points().get(i + 1);
            if (nextPoint.state() != null) {
                int row = nextPoint.state().originalRow();
                double offset = smoothed.get(i + 1).offsetRasterPixels().orElseThrow();
                double nextSlope = (offset - numericOffset) / (row - numericRow);
                c = .16 * Math.abs(offset - numericOffset);
                t = .12 * Math.abs(nextSlope - scalarSlope);
                numericRow = row; numericOffset = offset; scalarSlope = nextSlope;
            }
            double k = turn(physical, a, b, pitch);
            var arc = new ArcDraft(measured.kind(), a, b, original.sourceSpan(), measured.q(),
                c, t, k, measured.mean(), measured.receipt(), null);
            result.add(arc);
            physical = nextPhysical(physical, arc,
                path.points().get(i).state() == null ? path.points().get(i).owner()
                    : path.points().get(i).state(),
                path.points().get(i + 1).state() == null ? path.points().get(i + 1).owner()
                    : path.points().get(i + 1).state(), arc.receipt().orElse(null));
        }
        return List.copyOf(result);
    }

    private static EmissionResult.Costs totals(List<EmissionResult.Arc> arcs,
            double seedOffset, double firstOffset) {
        double q = 0, c = .16 * Math.abs(firstOffset - seedOffset),
            t = .12 * Math.abs(firstOffset - seedOffset), k = 0;
        for (var arc : arcs) { q += arc.q(); c += arc.c(); t += arc.t(); k += arc.k(); }
        return new EmissionResult.Costs(q, c, t, k);
    }

    public OwnedGuideResult evaluateOwnedOrdinaryWitnesses(SampledEmissionInput input,RidgeTracker.OwnedOrdinaryGuide provider,
            CancellationProbe cancellation,NativeConnectorEvaluator.QueryPolicy query,NativeConnectorEvaluator.ExecutionPolicy execution,LegacyEmissionProgress progress) {
        return ownedGuideExecution(input,provider,cancellation,query,execution,progress,GuideScope.BASE_WITNESS_ONLY,null);
    }
    /** Diagnostic only: replay the unchanged native proposal for original seed zero without greedy comparison. */
    public OwnedGuideResult evaluateFirstOriginalOwnedNativeGuide(SampledEmissionInput input,
            RidgeTracker.OwnedOrdinaryGuide provider,CancellationProbe cancellation,
            NativeConnectorEvaluator.QueryPolicy query,NativeConnectorEvaluator.ExecutionPolicy execution,
            LegacyEmissionProgress progress) {
        return ownedGuideExecution(input,provider,cancellation,query,execution,progress,
            GuideScope.BASE_NATIVE_GUIDE_ONLY,null);
    }
    public OwnedGuideResult solveOwnedGuideManagedGrouped(SampledEmissionInput input,RidgeTracker.OwnedOrdinaryGuide provider,Smoother smoother,
            CancellationProbe cancellation,NativeConnectorEvaluator.QueryPolicy query,NativeConnectorEvaluator.ExecutionPolicy execution,LegacyEmissionProgress progress) {
        return ownedGuideExecution(input,provider,cancellation,query,execution,progress,GuideScope.FULL_GROUPED,Objects.requireNonNull(smoother));
    }
    private OwnedGuideResult ownedGuideExecution(SampledEmissionInput input,RidgeTracker.OwnedOrdinaryGuide provider,
            CancellationProbe cancellation,NativeConnectorEvaluator.QueryPolicy query,NativeConnectorEvaluator.ExecutionPolicy execution,
            LegacyEmissionProgress progress,GuideScope scope,Smoother smoother) {
        Objects.requireNonNull(provider).requireInput(input);Objects.requireNonNull(cancellation);Objects.requireNonNull(query);Objects.requireNonNull(execution);
        if(execution!=NativeConnectorEvaluator.ExecutionPolicy.EAGER_ALL_PROBES_V1&&query!=NativeConnectorEvaluator.QueryPolicy.ZERO_GUARDED_LOCAL_V1)
            throw new IllegalArgumentException("Early execution requires local query policy");
        if(progress!=null)progress.requireCompatible(input,query,execution);
        AttemptMemoryLedger.Owner owner=null;SampledEmissionInput.ResultLease lease=null;RidgeTracker.OwnedGuideLease guideLifetime=null;EmissionResult emission=null;GroupedResult grouped=null;OwnedGuideResult result=null;
        NativeGuideOnlyDiagnostic nativeGuideOnly=null;
        try {
            if(progress!=null){progress.begin(input,query,execution);progress.groupedBegin(GroupedExecution.EXACT_Q0_OWNED_NATIVE_GUIDE_V1);}
            if(progress!=null&&scope==GuideScope.BASE_NATIVE_GUIDE_ONLY)progress.nativeGuideOnlyScope(scope);
            lease=scope!=GuideScope.FULL_GROUPED?input.retainResultLifetime():null;
            owner=input.openExecutionOwner("owned-ordinary-guide-result");var budget=input.workBudget();
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION,8);owner.reserve(2048);
            var facts=new GroupedFacts(GroupedExecution.EXACT_Q0_OWNED_NATIVE_GUIDE_V1,new ArrayList<>(),owner,new GuideContext(provider,new ArrayList<>()));
            if(scope==GuideScope.FULL_GROUPED) {
                emission=solveBody(input,provider.profiles(),provider.seeds(),provider.proposals(),smoother,cancellation,query,execution,false,true,progress,facts);
            } else {
                budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);
                try(var work=owner.child("base-witness-only-domain")) {
                    var binding=input.legacyBinding().orElseThrow();var domain=new LegacyEmissionDomain(binding,provider.profiles(),provider.seeds(),budget,work);
                    try(var classifier=new ManagedOrdinaryAffinity(input,domain)) {
                        if(scope==GuideScope.BASE_NATIVE_GUIDE_ONLY) {
                            var seed=domain.seeds().get(0);
                            if(progress!=null){progress.nativeGuideOnlyRequest(domain.seeds().size(),seed);progress.domainComplete(1);
                                progress.seedBegin(seed.id());progress.phase(LegacyEmissionProgress.Phase.BASE,0,-1,-1);}
                            budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);
                            try(var seedOwner=work.child("base-native-guide-only-seed")) {
                                var inventory=inventory(domain,budget,seedOwner,null,classifier);
                                long entries=domain.rowCount()+(long)binding.protectedOccurrences().size();
                                budget.charge(EmissionWorkBudget.Operation.PREPARATION,128+16*entries);budget.charge(EmissionWorkBudget.Operation.ALLOCATION,32+8*entries);seedOwner.reserve(4096+256*entries);
                                try(var evaluator=evaluator(input,domain,inventory,query,execution,progress)) {
                                    nativeGuideOnly=ownedNativeGuideWitness(input,domain,seed,inventory,evaluator,seedOwner,
                                        cancellation,input.context().nativePitchMeters().orElseThrow(),facts);
                                }
                            }
                            if(progress!=null){progress.seedComplete();progress.phaseComplete();}
                        } else {
                            if(progress!=null)progress.domainComplete(domain.seeds().size());
                            for(var seed:domain.seeds()) {
                                if(progress!=null){progress.seedBegin(seed.id());progress.phase(LegacyEmissionProgress.Phase.BASE,0,-1,-1);}
                                budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);
                                try(var seedOwner=work.child("base-witness-only-seed")) {
                                    var inventory=inventory(domain,budget,seedOwner,null,classifier);
                                    long entries=domain.rowCount()+(long)binding.protectedOccurrences().size();
                                    budget.charge(EmissionWorkBudget.Operation.PREPARATION,128+16*entries);budget.charge(EmissionWorkBudget.Operation.ALLOCATION,32+8*entries);seedOwner.reserve(4096+256*entries);
                                    try(var evaluator=evaluator(input,domain,inventory,query,execution,progress)) {
                                        ownedWitness(input,domain,seed,null,null,0,inventory,evaluator,seedOwner,cancellation,input.context().nativePitchMeters().orElseThrow(),facts);
                                    }
                                }
                                if(progress!=null){progress.seedComplete();progress.phaseComplete();}
                            }
                        }
                    }
                }
            }
            int n=facts.guide().comparisons().size();budget.charge(EmissionWorkBudget.Operation.PREPARATION,256L+32L*n);
            long diagnosticSteps=nativeGuideOnly==null?0:nativeGuideOnly.guidedSteps().size();
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION,12L+n+(nativeGuideOnly==null?0:16+diagnosticSteps));
            owner.reserve(4096L+512L*n+(nativeGuideOnly==null?0:1024L+32L*diagnosticSteps));
            String identity="LEGACY_OWNED_ORDINARY_GUIDE_V1/guide="+RidgeTracker.OwnedOrdinaryGuide.POLICY_ID+"/scope="+scope+"/grouped="+facts.execution()+"/query="+query+"/execution="+execution+"/managed="+ManagedOrdinaryAffinity.POLICY_ID;
            if(nativeGuideOnly!=null)identity+="/coverage="+nativeGuideOnly.coverage()+"/seed="+nativeGuideOnly.requestedSeed().orElseThrow().id();
            guideLifetime=provider.transferToResult(input);
            cancellation.checkpoint();input.requireOpenAndUnchanged();
            if(progress!=null&&nativeGuideOnly!=null)progress.prepareNativeGuideOnly(nativeGuideOnly);
            try(var publication=input.prepareManagedFinalPublication(owner,256L+32L*n,4096L+256L*n)) {
                publication.validate();if(progress!=null)progress.requireCompatible(input,query,execution);
                if(emission!=null)grouped=new GroupedResult(facts.execution(),emission,facts.stages(),owner,budget.snapshot());
                result=new OwnedGuideResult(scope,grouped,facts.guide().comparisons(),nativeGuideOnly,
                    guideLifetime,owner,lease,budget.snapshot(),identity);
            }
            if(progress!=null&&nativeGuideOnly!=null)progress.publishNativeGuideOnly(nativeGuideOnly);
            if(progress!=null){progress.requireCompatible(input,query,execution);progress.seal(null);}
            return result;
        } catch(RuntimeException failure) {
            if(result!=null)closeAfterFailure(result,failure);else {closeAfterFailure(grouped==null?emission:grouped,failure);closeAfterFailure(owner,failure);closeAfterFailure(guideLifetime==null?provider:guideLifetime,failure);closeAfterFailure(lease,failure);}
            if(ExperimentalMeasuredModeInventory.terminal(failure))input.recordTerminalExecutionFailure(failure);
            if(progress!=null){boolean live=false;try{progress.requireCompatible(input,query,execution);live=true;}catch(RuntimeException revoked){}
                if(live)try{progress.seal(failure);}catch(RuntimeException cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}}
            throw failure;
        }
    }

    private Optional<EmissionResult.Costs> ownedWitness(SampledEmissionInput input,LegacyEmissionDomain domain,LegacyEmissionDomain.Seed seed,
            Node prefix,LegacyEmissionDomain.State right,int stage,Inventory inventory,NativeConnectorEvaluator evaluator,AttemptMemoryLedger.Owner owner,
            CancellationProbe cancellation,double pitch,GroupedFacts facts) {
        var provider=facts.guide().provider();provider.requireInput(input);
        input.workBudget().charge(EmissionWorkBudget.Operation.ALLOCATION,4);facts.owner().reserve(256);
        RidgeTracker.GuideTrace trace=null;GuideStatus unavailable=GuideStatus.NO_OWNED_ANCHOR;
        try {
            if(prefix==null)trace=provider.base(input,seed.id(),seed.offsetRasterPixels());
            else {
                var leftAnchor=provider.anchor(prefix.key.last().originalRow(),prefix.key.last().peakOwner());var rightAnchor=provider.anchor(right.originalRow(),right.peakOwner());
                if(leftAnchor.isPresent()&&rightAnchor.isPresent())trace=provider.interval(input,leftAnchor.get(),rightAnchor.get());
            }
            if(trace!=null)trace.requireProvider(provider,input);
            var greedy=replayOwnedOrdinary(input,domain,seed,prefix,right,stage,inventory,evaluator,owner,cancellation,pitch,null,facts.owner());
            var guided=trace==null?new GuideReplay(Optional.empty(),unavailable,List.of()):replayOwnedOrdinary(input,domain,seed,prefix,right,stage,inventory,evaluator,owner,cancellation,pitch,trace,facts.owner());
            GuideChoice choice=guided.costs().isPresent()&&(greedy.costs().isEmpty()||guided.costs().get().total()<greedy.costs().get().total())?GuideChoice.GUIDED:
                greedy.costs().isPresent()?GuideChoice.GREEDY:GuideChoice.NONE;
            var selected=choice==GuideChoice.GUIDED?guided.costs():greedy.costs();var budget=input.workBudget();var progress=evaluator.progress();
            int records=facts.guide().comparisons().size();budget.charge(EmissionWorkBudget.Operation.PREPARATION,128L+records);budget.charge(EmissionWorkBudget.Operation.ALLOCATION,8);facts.owner().reserve(1024L+32L*records);
            var summary=new GuideStageReference(seed==null?prefix.seedId:seed.id(),stage,greedy.costs(),greedy.status(),guided.costs(),guided.status(),choice,greedy.steps(),guided.steps(),trace!=null,trace==null?0:trace.score());
            if(progress!=null&&selected.isPresent())progress.prepareOwnedGuideWitness();
            try(var publication=input.prepareManagedFinalPublication(facts.owner(),128,1024)) {
                publication.validate();if(progress!=null)progress.requireOwnedGuideAuthority(input);
                facts.guide().comparisons().add(summary);if(progress!=null&&selected.isPresent())progress.publishOwnedGuideWitness(selected.get().total());
            }
            return selected;
        } finally {if(trace!=null)trace.close();}
    }

    private NativeGuideOnlyDiagnostic ownedNativeGuideWitness(SampledEmissionInput input,LegacyEmissionDomain domain,
            LegacyEmissionDomain.Seed seed,Inventory inventory,NativeConnectorEvaluator evaluator,
            AttemptMemoryLedger.Owner owner,CancellationProbe cancellation,double pitch,GroupedFacts facts) {
        var provider=facts.guide().provider();provider.requireInput(input);
        input.workBudget().charge(EmissionWorkBudget.Operation.ALLOCATION,4);facts.owner().reserve(256);
        RidgeTracker.GuideTrace trace=null;RuntimeException primary=null;
        try {
            trace=provider.base(input,seed.id(),seed.offsetRasterPixels());
            trace.requireProvider(provider,input);
            GuideReplay replay=replayOwnedOrdinary(input,domain,seed,null,null,0,inventory,evaluator,
                owner,cancellation,pitch,trace,facts.owner());
            Optional<EmissionResult.Costs> costs=replay.costs();
            GuideStatus status=replay.status();java.util.OptionalDouble upper=java.util.OptionalDouble.empty();
            if(costs.isPresent()) {
                double total=costs.orElseThrow().total();
                if(Double.isFinite(total)&&total>=0.0)upper=java.util.OptionalDouble.of(total);
                else {costs=Optional.empty();status=GuideStatus.NO_POSITIVE_FINITE_VECTOR;}
            }
            int steps=replay.steps().size();var budget=input.workBudget();
            budget.charge(EmissionWorkBudget.Operation.PREPARATION,128L+steps);
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION,16L+steps);
            facts.owner().reserve(1024L+32L*steps);
            var diagnostic=new NativeGuideOnlyDiagnostic(domain.seeds().size(),
                Optional.of(new NativeGuideSeedIdentity(seed.id(),Double.doubleToRawLongBits(seed.offsetRasterPixels()))),
                NativeGuideCoverage.FIRST_ORIGINAL_SEED_ONLY,GuideStatus.NOT_REQUESTED,false,status,costs,
                replay.steps(),upper);
            try(var publication=input.prepareManagedFinalPublication(facts.owner(),128,1024)) {
                publication.validate();
                if(evaluator.progress()!=null)evaluator.progress().requireOwnedGuideAuthority(input);
            }
            return diagnostic;
        } catch(RuntimeException failure){primary=failure;throw failure;}
        finally {if(trace!=null)try{trace.close();}catch(RuntimeException cleanup){if(primary!=null&&cleanup!=primary)primary.addSuppressed(cleanup);else if(primary==null)throw cleanup;}}
    }

    private GuideReplay replayOwnedOrdinary(SampledEmissionInput input,LegacyEmissionDomain domain,LegacyEmissionDomain.Seed seed,Node prefix,
            LegacyEmissionDomain.State right,int stage,Inventory inventory,NativeConnectorEvaluator evaluator,AttemptMemoryLedger.Owner owner,
            CancellationProbe cancellation,double pitch,RidgeTracker.GuideTrace trace,AttemptMemoryLedger.Owner factsOwner) {
        var budget=input.workBudget();var progress=evaluator.progress();if(progress!=null)progress.witnessBegin();
        int first=prefix==null?0:prefix.key.last().originalRow()+1,end=right==null?domain.rowCount()-1:right.originalRow();Node current=prefix;
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION,8);factsOwner.reserve(256L+128L*(end-first+1));var references=new ArrayList<GuideOwnedStep>();RuntimeException primary=null;
        try {
            if(trace!=null&&trace.steps().size()!=end-first+1+(prefix==null?0:1))return new GuideReplay(Optional.empty(),GuideStatus.OWNERSHIP_UNAVAILABLE,List.of());
            for(int row=first;row<=end;row++) {
                input.requireOpenAndUnchanged();cancellation.checkpoint();budget.charge(EmissionWorkBudget.Operation.PREPARATION,32);budget.charge(EmissionWorkBudget.Operation.ALLOCATION,2);
                RidgeTracker.GuideStep instruction=trace==null?null:trace.steps().get(row-first+(prefix==null?0:1));
                if(instruction!=null&&instruction.originalRow()!=row)return new GuideReplay(Optional.empty(),GuideStatus.OWNERSHIP_UNAVAILABLE,List.copyOf(references));
                LegacyEmissionDomain.State chosen=null;RidgeTracker.GuideKind kind;
                if(right!=null&&row==end){chosen=right;kind=RidgeTracker.GuideKind.OBSERVED;if(instruction!=null&&(instruction.kind()!=kind||instruction.peak()!=right.peakOwner()))chosen=null;}
                else if(domain.unsupported(row)) {
                    kind=prefix==null?RidgeTracker.GuideKind.CARRY_PREVIOUS:RidgeTracker.GuideKind.INTERVAL_TARGET;
                    if(instruction==null||instruction.kind()==kind)chosen=prefix==null?reachedCarry(domain,inventory,evaluator,row,current==null?seed.id():current.key.last().offsetOriginId(),current==null?seed.offsetRasterPixels():current.key.last().offsetRasterPixels(),budget,owner)
                        :intervalTarget(domain,inventory,evaluator,row,prefix.key.last(),right,budget,owner);
                } else {
                    kind=RidgeTracker.GuideKind.OBSERVED;
                    if(instruction==null)chosen=witnessObserved(domain,row,current==null?seed.offsetRasterPixels():current.key.last().offsetRasterPixels(),budget);
                    else if(instruction.kind()==kind)for(var candidate:domain.observedAt(row)) {
                        budget.charge(EmissionWorkBudget.Operation.PREPARATION,1);
                        if(candidate.peakOwner()==instruction.peak()){if(chosen!=null)return new GuideReplay(Optional.empty(),GuideStatus.OWNERSHIP_UNAVAILABLE,List.copyOf(references));chosen=candidate;}
                    }
                }
                if(chosen==null)return new GuideReplay(Optional.empty(),GuideStatus.OWNERSHIP_UNAVAILABLE,List.copyOf(references));
                references.add(new GuideOwnedStep(row,kind,chosen.nativeStateId()));
                Node next=current==null?initialProofNode(input,domain,seed,chosen,owner,budget):advance(input,domain,inventory,current,chosen,evaluator,owner,cancellation,stage,pitch);
                if(next==null)return new GuideReplay(Optional.empty(),GuideStatus.ADMISSION_REFUSED,List.copyOf(references));current=next;
            }
            budget.charge(EmissionWorkBudget.Operation.PREPARATION,64L+references.size());budget.charge(EmissionWorkBudget.Operation.ALLOCATION,4);factsOwner.reserve(256L+32L*references.size());
            double upper=certifiedWitnessUpper(current);Optional<EmissionResult.Costs> costs=Double.isFinite(upper)?Optional.of(new EmissionResult.Costs(current.q,current.c,current.t,current.k)):Optional.empty();
            return new GuideReplay(costs,costs.isPresent()?GuideStatus.FEASIBLE:GuideStatus.NO_POSITIVE_FINITE_VECTOR,List.copyOf(references));
        } catch(RuntimeException failure){primary=failure;throw failure;}
        finally {if(primary==null)releaseUnretained(current,budget);else try{releaseUnretained(current,budget);}catch(RuntimeException cleanup){if(cleanup!=primary)primary.addSuppressed(cleanup);}}
    }

}
