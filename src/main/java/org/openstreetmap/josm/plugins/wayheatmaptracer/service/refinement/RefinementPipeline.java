package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import java.util.function.LongSupplier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.CandidateRankingPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedModernPreviewSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator;
import java.util.concurrent.CancellationException;

/** One bounded preview-only operation across actual sources, whole range and contained fallback. */
public final class RefinementPipeline {
    public interface Sources {
        void requireCurrent();
        EvidenceSnapshot evidence(RefinementSourcePlanner.SourceRequest source,CancellationProbe cancellation);
        default Optional<ManagedModernPreviewSource.RefinementWorkReceipt> actualSourceWork(){return Optional.empty();}
        default Optional<RefinementSourcePlanner.Plan> binding(){return Optional.empty();}
    }
    /** Uses the coordinator-sealed source generation, acquisition outcomes and factual work receipt. */
    public static Sources managedSources(RefinementAcquisition.Capture capture,TileFetchCoordinator coordinator) {
        return new Sources() {
            public void requireCurrent(){if(!capture.isCurrent(coordinator))throw new IllegalStateException("Refinement source invalidated");}
            public EvidenceSnapshot evidence(RefinementSourcePlanner.SourceRequest source,CancellationProbe cancellation) {
                requireCurrent();
                if(source.aggregate()) {
                    if(!capture.completeAggregate())throw new SourceUnavailable();
                    return capture.aggregateEvidence(cancellation);
                }
                if(!capture.palettes().contains(source.palette()))throw new SourceUnavailable();
                return source.mapping().equals(source.palette())?capture.evidence(source.palette(),cancellation):capture.mappingEvidence(source.mapping(),cancellation);
            }
            public Optional<ManagedModernPreviewSource.RefinementWorkReceipt> actualSourceWork(){return Optional.of(capture.workReceipt());}
            public Optional<RefinementSourcePlanner.Plan> binding(){return Optional.of(capture.plan());}
        };
    }
    /** Each supplied mapping keeps its captured visible-layer epoch; it cannot supply other palettes. */
    public static Sources visibleSources(Map<String,RefinementAcquisition.VisibleCapture> captures) {
        var frozen=Map.copyOf(captures);
        var plans=frozen.values().stream().map(RefinementAcquisition.VisibleCapture::plan).distinct().toList();
        if(plans.size()!=1)throw new IllegalArgumentException("Visible mappings must belong to one operation");
        return new Sources() {
            public void requireCurrent(){frozen.values().forEach(RefinementAcquisition.VisibleCapture::requireCurrent);}
            public EvidenceSnapshot evidence(RefinementSourcePlanner.SourceRequest source,CancellationProbe cancellation) {
                cancellation.checkpoint();requireCurrent();var capture=frozen.get(source.mapping());
                if(source.aggregate()||capture==null)throw new SourceUnavailable();return capture.evidence();
            }
            public Optional<RefinementSourcePlanner.Plan> binding(){return Optional.of(plans.get(0));}
        };
    }
    public static final class SourceUnavailable extends IllegalStateException { }
    @FunctionalInterface public interface NativeRunner {
        RefinementEngineAdapter.Run run(OperationTicket ticket,ParentTraceReference parent,EvidenceSnapshot evidence,
            TraceBudgets remaining,CancellationProbe cancellation,AttemptMemoryLedger.Owner owner);
    }
    public record Adapters(Sources sources,NativeRunner nativeRunner,AttemptMemoryLedger.Owner owner) {
        public Adapters {Objects.requireNonNull(sources);Objects.requireNonNull(nativeRunner);Objects.requireNonNull(owner);}
    }
    public enum AttemptKind { WHOLE, INTERVAL, FROZEN, UNAVAILABLE, UNATTEMPTED }
    public enum AllocationPolicy { NOT_ISSUED, BASE_COVERAGE_ORDERED_EXTRAS_V2 }
    public record Attempt(RefinementSourcePlanner.SourceRequest source,AttemptKind kind,OccurrenceRange range,
            Optional<RefinementEngineAdapter.Run> run,String reason,Optional<TraceBudgets> issuedBudgets,AllocationPolicy allocationPolicy) {
        public Attempt(RefinementSourcePlanner.SourceRequest source,AttemptKind kind,OccurrenceRange range,Optional<RefinementEngineAdapter.Run> run,String reason) {
            this(source,kind,range,run,reason,Optional.empty(),AllocationPolicy.NOT_ISSUED);
        }
        public Attempt {Objects.requireNonNull(source);Objects.requireNonNull(kind);Objects.requireNonNull(range);Objects.requireNonNull(run);Objects.requireNonNull(reason);Objects.requireNonNull(issuedBudgets);Objects.requireNonNull(allocationPolicy);}
        public OptionalInt nativeCallAvailability() {
            return run.isPresent()?OptionalInt.of(run.get().nativeCalls()):kind==AttemptKind.WHOLE||kind==AttemptKind.INTERVAL?OptionalInt.empty():OptionalInt.of(0);
        }
    }
    public record Retained(ParentBackedComposer.Assessed assessed,List<RefinementSourcePlanner.SourceRequest> sources) {
        public Retained {Objects.requireNonNull(assessed);sources=List.copyOf(sources);}
    }
    public record SourceOutcome(RefinementSourcePlanner.SourceRequest source,List<ParentBackedComposer.Assessed> assessments,List<Attempt> attempts) {
        public SourceOutcome {Objects.requireNonNull(source);assessments=List.copyOf(assessments);attempts=List.copyOf(attempts);}
    }
    public record Result(Outcome outcome,List<Retained> candidates,List<Attempt> attempts,
            Optional<TraceWorkUsage> actualWork,boolean truncated,List<ParentBackedComposer.Assessed> rejected,
            Optional<ManagedModernPreviewSource.RefinementWorkReceipt> actualSourceWork,List<SourceOutcome> sourceOutcomes) {
        public Result {candidates=List.copyOf(candidates);attempts=List.copyOf(attempts);rejected=List.copyOf(rejected);sourceOutcomes=List.copyOf(sourceOutcomes);Objects.requireNonNull(actualWork);Objects.requireNonNull(actualSourceWork);}
    }
    private static final class DeadlineReached extends CancellationException { }
    private static final class SourceInvalid extends IllegalStateException { }
    public static final class OperationBudget {
        private final TraceBudgets maximum;private final LongSupplier clock;private final long started,duration,reserve;
        private TraceWorkUsage used=TraceWorkUsage.none();private boolean claimed,unknown;
        public OperationBudget(TraceBudgets maximum,LongSupplier clock,long durationNanos,long finalReserveNanos) {
            this.maximum=Objects.requireNonNull(maximum);this.clock=Objects.requireNonNull(clock);
            if(durationNanos<=0||finalReserveNanos<=0||finalReserveNanos>=durationNanos)throw new IllegalArgumentException("Positive inference/finalization deadline shares required");
            started=clock.getAsLong();duration=durationNanos;reserve=finalReserveNanos;
        }
        public static OperationBudget production(TraceBudgets maximum) {return new OperationBudget(maximum,System::nanoTime,300_000_000_000L,5_000_000_000L);}
        private void claim(TraceBudgets root) {
            if(claimed||maximum.maximumStatesPerProfile()!=root.maximumStatesPerProfile()||maximum.maximumPairVisits()>root.maximumPairVisits()
                ||maximum.maximumTransitions()>root.maximumTransitions()||maximum.maximumRawAlternatives()>root.maximumRawAlternatives()
                ||maximum.maximumDistinctAlternatives()>root.maximumDistinctAlternatives())throw new IllegalArgumentException("Operation budget reused or exceeds original native policy");
            claimed=true;
        }
        private boolean inferenceExpired(){return clock.getAsLong()-started>=duration-reserve;}
        private boolean finalExpired(){return clock.getAsLong()-started>=duration;}
        private boolean exhausted(){return unknown||used.pairVisits()>=maximum.maximumPairVisits()||used.transitions()>=maximum.maximumTransitions()
            ||used.rawAlternatives()>=maximum.maximumRawAlternatives()||used.distinctAlternatives()>=maximum.maximumDistinctAlternatives();}
        private TraceBudgets remaining(long potentialCalls){
            if(exhausted()||inferenceExpired())throw new DeadlineReached();
            // Reserve one slot for each later potential base call, then give this earlier
            // source the extras. Pair/score work and lateral-state cap are unchanged.
            // Unused offers are never charged; no earlier source is run a second time.
            long calls=Math.max(1,potentialCalls);
            int raw=(int)Math.max(1,maximum.maximumRawAlternatives()-used.rawAlternatives()-(calls-1));
            int distinct=(int)Math.max(1,maximum.maximumDistinctAlternatives()-used.distinctAlternatives()-(calls-1));
            return new TraceBudgets(maximum.maximumStatesPerProfile(),maximum.maximumPairVisits()-used.pairVisits(),maximum.maximumTransitions()-used.transitions(),
                raw,Math.min(distinct,raw));
        }
        private void charge(RefinementEngineAdapter.Run run,TraceBudgets issued) {
            var work=run.actualWorkAvailability();
            if(work.isEmpty()){unknown=true;return;}
            var value=work.get();used=used.plus(value);
            if(value.pairVisits()>issued.maximumPairVisits()||value.transitions()>issued.maximumTransitions()
                ||value.rawAlternatives()>issued.maximumRawAlternatives()||value.distinctAlternatives()>issued.maximumDistinctAlternatives())
                throw new IllegalStateException("Native receipt exceeded the one remaining operation budget");
        }
    }
    public static NativeRunner productionRunner() {return productionRunner(ticket->{});}
    public static NativeRunner productionRunner(java.util.function.Consumer<OperationTicket> nativeEntry) {
        Objects.requireNonNull(nativeEntry);return (ticket,parent,evidence,remaining,cancellation,owner)->
        new RefinementEngineAdapter(ticket.engine(),"native",()->nativeEntry.accept(ticket)).run(ticket,parent,evidence,remaining,cancellation,owner).value();
    }
    public Result run(OperationTicket ticket,ParentTraceReference parent,RefinementSourcePlanner.Plan sourcePlan,
            Adapters adapters,OperationBudget budget,CancellationProbe cancellation) {
        Objects.requireNonNull(ticket);Objects.requireNonNull(parent);Objects.requireNonNull(sourcePlan);Objects.requireNonNull(adapters);Objects.requireNonNull(budget);Objects.requireNonNull(cancellation);
        if(ticket.kind()==StageKind.SMOOTH||!sourcePlan.ticket().equals(ticket)||sourcePlan.parent()!=parent
                ||!ticket.parent().equals(parent.parentStage())||!ticket.choice().equals(parent.parentChoice())
                ||ticket.mode()!=parent.root().request().geometryMode()||!parent.originalRange().equals(parent.root().request().selectedRange()))throw new IllegalArgumentException("Operation/source plan does not pin this exact complete selected parent");
        if(adapters.sources().binding().isPresent()&&!adapters.sources().binding().get().equals(sourcePlan))throw new IllegalArgumentException("Source capture belongs to another operation/parent policy");
        sourcePlan.settings().requireRootPolicy(parent.root());
        if(!RefinementSourcePlanner.plan(ticket,sourcePlan.settings(),parent).equals(sourcePlan))throw new IllegalArgumentException("Source scheduling differs from canonical frozen policy");
        budget.claim(parent.root().request().budgets());
        if(parent.root().frozenGeometryProof().isEmpty()) {
            boolean cancelled=false;try{cancellation.checkpoint();}catch(CancellationException stopped){cancelled=true;}
            final boolean wasCancelled=cancelled;
            var refusals=sourcePlan.requests().stream().map(s->new Attempt(s,AttemptKind.UNAVAILABLE,parent.originalRange(),Optional.<RefinementEngineAdapter.Run>empty(),
                wasCancelled?"operation-cancelled":ParentTraceReference.Unavailable.FROZEN_GEOMETRY_PROOF_UNAVAILABLE.name())).toList();
            var sources=sourcePlan.requests().stream().map(s->new SourceOutcome(s,List.of(),refusals.stream().filter(a->a.source().equals(s)).toList())).toList();
            return new Result(wasCancelled?Outcome.CANCELLED:Outcome.NO_USABLE_RESULT,List.of(),refusals,Optional.of(TraceWorkUsage.none()),false,List.of(),adapters.sources().actualSourceWork(),sources);
        }
        var attempts=new ArrayList<Attempt>();var completed=new ArrayList<SourceCandidates>();var rejected=new ArrayList<ParentBackedComposer.Assessed>();
        var intervals=new RefinementIntervalPlanner().plan(parent);var composer=new ParentBackedComposer();
        var assessedBySource=new LinkedHashMap<RefinementSourcePlanner.SourceRequest,List<ParentBackedComposer.Assessed>>();
        long fallbackCalls=intervals.stream().filter(i->i.eligible()&&!i.range().equals(parent.originalRange())).count();
        var owner=adapters.owner().child("refinement-operation");AttemptMemoryLedger.Reservation retained=null;
        Outcome outcome=Outcome.COMPLETE;boolean stopped=false,partial=false;int sourceIndex=0;
        CancellationProbe finalProbe=()->{cancellation.checkpoint();requireCurrent(adapters.sources());if(budget.finalExpired())throw new DeadlineReached();return false;};
        CancellationProbe nativeProbe=()->{finalProbe.checkpoint();if(budget.inferenceExpired())throw new DeadlineReached();return false;};
        try {
            finalProbe.checkpoint();
            // Reserve every possible retained assembled alternative before any engine entry. Native
            // receipts keep their own same-ledger owners; no source/interval receives a fresh pool.
            long vertices=maximumAssembledVertices(parent,intervals.size());
            retained=owner.reserve(Math.addExact(1_048_576L,Math.multiplyExact(32L,Math.multiplyExact(vertices,2048L))));
            for(;sourceIndex<sourcePlan.requests().size();sourceIndex++) {
                var source=sourcePlan.requests().get(sourceIndex);
                if(!source.unavailableReason().isEmpty()) {attempts.add(new Attempt(source,AttemptKind.UNAVAILABLE,parent.originalRange(),Optional.empty(),source.unavailableReason()));continue;}
                if(stopped||budget.exhausted()||budget.inferenceExpired()) {
                    stopped=true;outcome=Outcome.RESOURCE_LIMIT;attempts.add(unattempted(source,parent.originalRange(),"operation-budget-stopped"));continue;
                }
                finalProbe.checkpoint();EvidenceSnapshot evidence;
                try{evidence=adapters.sources().evidence(source,finalProbe);}
                catch(SourceUnavailable unavailable){attempts.add(new Attempt(source,AttemptKind.UNAVAILABLE,parent.originalRange(),Optional.empty(),"source-unavailable"));partial=true;continue;}
                finalProbe.checkpoint();
                // One reusable assessment/export scratch reservation. 65,536 cached scalar rows
                // and local-window rows, 2,048 directed supports (bounded by edit-region diameter),
                // scalar cloning, detached graph/topology inventories and geometry containers.
                try(var scratch=owner.reserve(assessmentScratchBytes(parent,evidence,vertices))) {
                    long futureSources=sourcePlan.requests().subList(sourceIndex+1,sourcePlan.requests().size()).stream().filter(r->r.unavailableReason().isEmpty()).count();
                    var sourceAssessments=new ArrayList<ParentBackedComposer.Assessed>();assessedBySource.put(source,sourceAssessments);
                    var whole=invoke(ticket,parent,evidence,source,AttemptKind.WHOLE,adapters,budget,nativeProbe,owner,attempts,(1+futureSources)*(1+fallbackCalls));
                    if(aborts(whole,budget))throw terminal(whole,budget);
                    var candidates=new ArrayList<ParentBackedComposer.Assessed>();
                    if(whole.finalized().isPresent())for(var route:whole.finalized().get().routes()) {
                        var choice=choice(whole,route,source);
                        var assessed=assess(composer,parent,List.of(choice),List.of(route),evidence,finalProbe,identity(ticket,source,"whole",candidates.size()+rejected.size()));
                        sourceAssessments.add(assessed);
                        if(usable(assessed))candidates.add(assessed);else rejected.add(assessed);
                    }
                    if(!candidates.isEmpty()) {completed.add(new SourceCandidates(source,candidates));continue;}
                    if(whole.outcome()==Outcome.RESOURCE_LIMIT||budget.exhausted()||budget.inferenceExpired()) {
                        stopped=true;outcome=Outcome.RESOURCE_LIMIT;recordRemainingIntervals(source,intervals,attempts,"operation-budget-stopped");continue;
                    }
                    var selected=new ArrayList<Selected>();var alternatives=new ArrayList<Selected>();
                    long remainingIntervals=fallbackCalls;
                    for(var interval:intervals) {
                        if(!interval.eligible()) {attempts.add(new Attempt(source,AttemptKind.FROZEN,interval.range(),Optional.empty(),interval.reason().name()));partial=true;continue;}
                        // Retrying an identical whole range is not interval fallback.
                        if(interval.range().equals(parent.originalRange()))continue;
                        long potentialCalls=remainingIntervals--+futureSources*(1+fallbackCalls);
                        if(stopped||budget.exhausted()||budget.inferenceExpired()) {
                            stopped=true;outcome=Outcome.RESOURCE_LIMIT;attempts.add(unattempted(source,interval.range(),"operation-budget-stopped"));continue;
                        }
                        var local=invoke(ticket,interval.reference().orElseThrow(),evidence,source,AttemptKind.INTERVAL,adapters,budget,nativeProbe,owner,attempts,potentialCalls);
                        if(aborts(local,budget))throw terminal(local,budget);
                        if(local.outcome()==Outcome.RESOURCE_LIMIT){stopped=true;outcome=Outcome.RESOURCE_LIMIT;partial=true;continue;}
                        var usable=local.finalized().stream().flatMap(r->r.routes().stream()).filter(r->r.quality().disposition()!=FinalGeometryEvaluator.Disposition.HARD_BLOCKED).toList();
                        if(usable.isEmpty()){partial=true;continue;}
                        selected.add(new Selected(choice(local,usable.get(0),source),usable.get(0)));
                        for(int i=1;i<usable.size();i++)alternatives.add(new Selected(choice(local,usable.get(i),source),usable.get(i)));
                    }
                    if(!selected.isEmpty()) {
                        var primary=assess(composer,parent,selected.stream().map(Selected::choice).toList(),selected.stream().map(Selected::route).toList(),evidence,finalProbe,identity(ticket,source,"assembled",0));
                        sourceAssessments.add(primary);
                        if(usable(primary))candidates.add(primary);else rejected.add(primary);
                        // Each extra changes one interval of the primary. No palette/interval product.
                        for(var alternative:alternatives) {
                            if(candidates.size()+rejected.size()>=32)break;
                            var single=new ArrayList<>(selected);
                            for(int i=0;i<single.size();i++)if(single.get(i).choice().reference().originalRange().equals(alternative.choice().reference().originalRange()))single.set(i,alternative);
                            var assessed=assess(composer,parent,single.stream().map(Selected::choice).toList(),single.stream().map(Selected::route).toList(),evidence,finalProbe,identity(ticket,source,"single-interval",candidates.size()+rejected.size()));
                            sourceAssessments.add(assessed);
                            if(usable(assessed))candidates.add(assessed);else rejected.add(assessed);
                        }
                    }
                    completed.add(new SourceCandidates(source,candidates));
                }
            }
            finalProbe.checkpoint();
        } catch(DeadlineReached limited) {outcome=Outcome.RESOURCE_LIMIT;stopped=true;}
        catch(SourceInvalid stale){outcome=Outcome.STALE;stopped=true;}
        catch(CancellationException cancelled){outcome=Outcome.CANCELLED;stopped=true;}
        catch(AttemptMemoryLedger.ResourceLimitException limited){outcome=Outcome.RESOURCE_LIMIT;stopped=true;}
        catch(UnknownWork unavailable){outcome=Outcome.FAILED;stopped=true;}
        catch(RuntimeException invalid){outcome=Outcome.FAILED;stopped=true;}
        for(;sourceIndex<sourcePlan.requests().size();sourceIndex++) {
            var source=sourcePlan.requests().get(sourceIndex);
            boolean already=attempts.stream().anyMatch(a->a.source().equals(source));
            if(!already)attempts.add(unattempted(source,parent.originalRange(),"operation-aborted"));
        }
        var retainedCandidates=retain(completed,ticket.engine(),sourcePlan.requests());
        if(budget.unknown||outcome==Outcome.CANCELLED||outcome==Outcome.STALE||outcome==Outcome.FAILED)retainedCandidates=List.of();
        if(outcome==Outcome.COMPLETE)outcome=retainedCandidates.isEmpty()?Outcome.NO_USABLE_RESULT:partial?Outcome.PARTIAL:Outcome.COMPLETE;
        var sourceOutcomes=sourcePlan.requests().stream().map(s->new SourceOutcome(s,assessedBySource.getOrDefault(s,List.of()),attempts.stream().filter(a->a.source().equals(s)).toList())).toList();
        boolean nativeTruncated=attempts.stream().flatMap(a->a.run().stream()).flatMap(r->r.nativeRun().stream())
            .anyMatch(r->r.result().alternativesTruncated());
        var result=new Result(outcome,retainedCandidates,attempts,budget.unknown?Optional.empty():Optional.of(budget.used),stopped||partial||nativeTruncated,rejected,adapters.sources().actualSourceWork(),sourceOutcomes);
        if(retained!=null)retained.adopt(result);
        return result;
    }
    private record SourceCandidates(RefinementSourcePlanner.SourceRequest source,List<ParentBackedComposer.Assessed> candidates) { }
    private record Selected(ParentBackedComposer.Choice choice,ModernTracePipeline.Route route) { }
    private static final class UnknownWork extends IllegalStateException { }
    private static void requireCurrent(Sources sources){try{sources.requireCurrent();}catch(RuntimeException invalid){throw new SourceInvalid();}}
    private static Attempt unattempted(RefinementSourcePlanner.SourceRequest source,OccurrenceRange range,String reason){return new Attempt(source,AttemptKind.UNATTEMPTED,range,Optional.empty(),reason);}
    private static void recordRemainingIntervals(RefinementSourcePlanner.SourceRequest source,List<RefinementIntervalPlanner.Interval> intervals,List<Attempt> attempts,String reason) {
        for(var interval:intervals)attempts.add(interval.eligible()?unattempted(source,interval.range(),reason):new Attempt(source,AttemptKind.FROZEN,interval.range(),Optional.empty(),interval.reason().name()));
    }
    private static RefinementEngineAdapter.Run invoke(OperationTicket ticket,ParentTraceReference parent,EvidenceSnapshot evidence,
            RefinementSourcePlanner.SourceRequest source,AttemptKind kind,Adapters adapters,OperationBudget budget,CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner,List<Attempt> attempts,long potentialCalls) {
        cancellation.checkpoint();var remaining=budget.remaining(potentialCalls);
        RefinementEngineAdapter.Run run;
        try {run=Objects.requireNonNull(adapters.nativeRunner().run(ticket,parent,evidence,remaining,cancellation,owner));}
        catch(RuntimeException failure) {
            budget.unknown=true;attempts.add(new Attempt(source,kind,parent.originalRange(),Optional.empty(),"native-receipt-unavailable",Optional.of(remaining),AllocationPolicy.BASE_COVERAGE_ORDERED_EXTRAS_V2));throw failure;
        }
        attempts.add(new Attempt(source,kind,parent.originalRange(),Optional.of(run),run.outcome().name(),Optional.of(remaining),AllocationPolicy.BASE_COVERAGE_ORDERED_EXTRAS_V2));
        budget.charge(run,remaining);
        if(run.reference().root()!=parent.root()||!run.reference().originalRange().equals(parent.originalRange())
                ||!run.reference().points().equals(parent.points()))throw new IllegalArgumentException("Native run belongs to another parent range");
        if(run.execution().isPresent())run.execution().get().requireBinding(run.reference(),run.request(),evidence,parent.root().network());
        return run;
    }
    private static boolean aborts(RefinementEngineAdapter.Run run,OperationBudget budget){return budget.unknown||run.outcome()==Outcome.CANCELLED||run.outcome()==Outcome.STALE;}
    private static RuntimeException terminal(RefinementEngineAdapter.Run run,OperationBudget budget) {
        if(budget.inferenceExpired())return new DeadlineReached();if(budget.unknown)return new UnknownWork();
        if(run.outcome()==Outcome.STALE)return new SourceInvalid();return new CancellationException();
    }
    private static ParentBackedComposer.Choice choice(RefinementEngineAdapter.Run run,ModernTracePipeline.Route route,RefinementSourcePlanner.SourceRequest source) {
        if(run.execution().isEmpty()||run.nativeRun().isEmpty()||!run.nativeRun().get().result().hypotheses().contains(route.rawHypothesis()))throw new IllegalArgumentException("Final route lacks actual native provenance");
        var arcs=run.finalCorrespondence().get(route.hypothesis().id());if(arcs==null)throw new IllegalArgumentException("Final route correspondence unavailable");
        return new ParentBackedComposer.Choice(run.reference(),route.hypothesis().points(),route.pointIds(),arcs,route.hypothesis().id(),source.palette());
    }
    private static ParentBackedComposer.Assessed assess(ParentBackedComposer composer,ParentTraceReference parent,
            List<ParentBackedComposer.Choice> choices,List<ModernTracePipeline.Route> nativeRoutes,EvidenceSnapshot evidence,
            CancellationProbe cancellation,String id) {
        cancellation.checkpoint();var assembled=composer.compose(id,parent,choices);var result=composer.assess(parent,assembled,evidence,"native",cancellation);
        var nativeSpans=assembled.spans().stream().filter(s->!s.parentRetained()).toList();
        if(nativeSpans.size()!=nativeRoutes.size())throw new IllegalArgumentException("Final native evidence lost interval ownership");
        var q=result.quality();var findings=new ArrayList<>(q.findings());var disposition=q.disposition();
        boolean unavailable=q.has(FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY);
        for(int i=0;i<nativeRoutes.size();i++) {
            var route=nativeRoutes.get(i);var span=nativeSpans.get(i);
            if(span.lastVertex()-span.firstVertex()+1!=route.pointIds().size())throw new IllegalArgumentException("Native finding geometry differs from assembled interval");
            if(route.quality().disposition().ordinal()>disposition.ordinal())disposition=route.quality().disposition();
            for(var finding:route.quality().findings()) {
                int first=span.firstVertex()+Math.max(0,Math.min(route.pointIds().size()-1,finding.firstVertex()));
                int last=span.firstVertex()+Math.max(0,Math.min(route.pointIds().size()-1,finding.lastVertex()));
                var mapped=new FinalGeometryEvaluator.Finding(finding.code(),finding.severity(),first,last,finding.amplitudeMeters());
                if(!findings.contains(mapped))findings.add(mapped);
                unavailable|=finding.code()==FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY;
            }
        }
        result=new ParentBackedComposer.Assessed(assembled,new FinalGeometryEvaluator.Result(q.id(),disposition,findings,q.totalLengthMeters(),q.directlySupportedLengthMeters(),q.worstUnsupportedSpanMeters(),unavailable?Double.POSITIVE_INFINITY:q.meanImageCenterCost(),q.bendPreservingRoughness()),result.topologyFindings(),result.copies());
        cancellation.checkpoint();return result;
    }
    private static boolean usable(ParentBackedComposer.Assessed assessed){return assessed.quality().disposition()!=FinalGeometryEvaluator.Disposition.HARD_BLOCKED;}
    private static String identity(OperationTicket ticket,RefinementSourcePlanner.SourceRequest source,String phase,int alternative) {
        return ticket.session().value()+":"+ticket.sequence()+"/"+source.palette()+"/"+source.mapping()+"/"+phase+"/"+alternative;
    }
    private static List<Retained> retain(List<SourceCandidates> completed,TrackerMode engine,List<RefinementSourcePlanner.SourceRequest> order) {
        var result=new ArrayList<Retained>();
        // Base palette representation precedes all extra native alternatives and derived variants.
        for(var source:completed)if(!source.candidates().isEmpty()&&RefinementSourcePlanner.BASE_PALETTES.contains(source.source().mapping())&&!source.source().aggregate())admit(result,source.candidates().get(0),source.source(),engine,order);
        for(var source:completed)for(var candidate:source.candidates())admit(result,candidate,source.source(),engine,order);
        return rank(result,engine,order);
    }
    private static void admit(List<Retained> retained,ParentBackedComposer.Assessed candidate,RefinementSourcePlanner.SourceRequest source,
            TrackerMode engine,List<RefinementSourcePlanner.SourceRequest> order) {
        // Equal geometry can still have conflicting source-owned review evidence. Keep
        // those choices separate; compatible aliases retain one actual source's metrics.
        for(int i=0;i<retained.size();i++)if(retained.get(i).assessed().candidate().points().equals(candidate.candidate().points())
                &&retained.get(i).assessed().quality().disposition()==candidate.quality().disposition()
                &&retained.get(i).assessed().quality().findings().equals(candidate.quality().findings())
                &&retained.get(i).assessed().topologyFindings().equals(candidate.topologyFindings())) {
            var old=retained.get(i);var best=old.assessed();var provenance=new ArrayList<>(old.sources());
            if(!old.assessed().candidate().id().equals(candidate.candidate().id())
                    &&rank(List.of(old,new Retained(candidate,List.of(source))),engine,order).get(0).assessed()==candidate) {
                best=candidate;provenance.remove(source);provenance.add(0,source);
            }else if(!provenance.contains(source))provenance.add(source);
            retained.set(i,new Retained(best,provenance));return;
        }
        if(retained.size()<32)retained.add(new Retained(candidate,List.of(source)));
    }
    /** Same physical policy as native final routes; engine objectives remain only in native receipts. */
    private static List<Retained> rank(List<Retained> retained,TrackerMode engine,List<RefinementSourcePlanner.SourceRequest> order) {
        var candidates=new ArrayList<CandidateRankingPolicy.Candidate>();var positions=new HashMap<String,Integer>();
        for(var value:retained) {
            var q=value.assessed().quality();if(q.has(FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY))continue;
            var source=value.sources().get(0);int branch=q.has(FinalGeometryEvaluator.FindingCode.BRANCH_SWITCH)||q.has(FinalGeometryEvaluator.FindingCode.AMBIGUOUS_BRANCH)?1:0;
            var physical=new CandidateRankingPolicy.PhysicalQuality(q.directlySupportedLengthMeters()+1e-6>=q.totalLengthMeters(),
                q.disposition()==FinalGeometryEvaluator.Disposition.HARD_BLOCKED,q.findings().size()-branch,branch,0,
                q.directlySupportedLengthMeters(),q.totalLengthMeters(),q.worstUnsupportedSpanMeters(),q.meanImageCenterCost(),0,q.bendPreservingRoughness(),order.indexOf(source),0);
            String tier=source.aggregate()?"aggregate":source.mapping().equals(source.palette())?"native":"cross-mapping";
            // Orientation/guide and objective have no assembled cross-source measurement. Equal
            // neutral ranking metadata is not exported as a measured value or a refinement bonus.
            candidates.add(new CandidateRankingPolicy.Candidate(q.id(),engine,tier,physical,0,value.assessed().candidate().points().size(),false,"selected-range",false));
        }
        int index=0;for(var value:new CandidateRankingPolicy().rank(candidates))positions.put(value.id(),index++);
        var result=new ArrayList<>(retained);result.sort(Comparator.comparingInt((Retained r)->positions.getOrDefault(r.assessed().candidate().id(),Integer.MAX_VALUE))
            .thenComparing(r->r.assessed().candidate().id()));return List.copyOf(result);
    }
    private static long maximumAssembledVertices(ParentTraceReference parent,int intervals) {
        double length=0;for(int i=1;i<parent.points().size();i++)length+=parent.points().get(i-1).distanceTo(parent.points().get(i));
        double rows=Math.ceil(length/parent.root().request().configuredSampleStepMeters())+1;
        if(!Double.isFinite(rows)||rows>Integer.MAX_VALUE)throw new IllegalArgumentException("Unbounded parent profile schedule");
        return Math.addExact((long)rows,Math.addExact(parent.points().size()+parent.originalRange().size(),4L*intervals+16L));
    }
    private static long assessmentScratchBytes(ParentTraceReference parent,EvidenceSnapshot evidence,long vertices) {
        // Directed support cache has 2048 entries; each boolean support array is bounded by
        // the original edit-region bounding-box diagonal / min(1 m, sourcePitch/2).
        double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX;
        for(var polygon:parent.root().network().closure().editRegion().polygons())for(var point:polygon){minX=Math.min(minX,point.xMeters());minY=Math.min(minY,point.yMeters());maxX=Math.max(maxX,point.xMeters());maxY=Math.max(maxY,point.yMeters());}
        double samples=Math.ceil(Math.hypot(maxX-minX,maxY-minY)/Math.min(1,evidence.resolution().effectivePitchMeters()/2))+1;
        if(!Double.isFinite(samples)||samples>Integer.MAX_VALUE)throw new IllegalArgumentException("Unbounded final support workspace");
        long bytes=Math.addExact(64L*1024*1024,Math.multiplyExact(2048L,(long)samples+512L));
        bytes=Math.addExact(bytes,Math.multiplyExact(vertices+parent.root().network().primitives().size(),8192L));
        for(var field:evidence.fields().values())bytes=Math.addExact(bytes,Math.multiplyExact((long)field.width()*field.height(),32L));
        return bytes;
    }
}
