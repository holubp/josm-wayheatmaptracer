package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.LiveNetworkSnapshotValidator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.ui.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ApplyAlignmentEditPlanCommand;

/** Actual modeless Legacy -> Task4 -> cached history connection. All session/UI operations stay on EDT. */
public final class LegacyRefinementWorkflow implements AutoCloseable {
    public record Display(AlignmentResult result,CenterlineCandidate candidate,Optional<RefinementPayload.NativeReview> nativeReview) { }
    private final LegacyReviewOwner review;
    private final TileFetchCoordinator coordinator;
    private final Consumer<Display> display;
    private final Consumer<String> status;
    private final Consumer<CenterlineCandidate> rootSelection;
    private final Executor executor;
    private final AlignmentJob.EventDispatcher publicationDispatcher;
    private PreviewWorkGate workGate;
    private PreviewWorkGate.Work activeWork;
    public long ownedBytes(){return memory.currentBytes();}
    public boolean ownsPublication(){return workGate.isCurrent(this);}
    public void attachOwnership(PreviewWorkGate shared,Object transferredOwner,Runnable closeWindow) {
        if(!SwingUtilities.isEventDispatchThread()||busy)throw new IllegalStateException("Ownership transfer requires idle EDT");
        workGate.release(this);workGate=Objects.requireNonNull(shared);workGate.release(transferredOwner);
        workGate.adopt(this,()->{close();closeWindow.run();});
    }
    private final AttemptMemoryLedger memory=AttemptMemoryLedger.production();
    private final AttemptMemoryLedger.Owner rootMemory=memory.rootOwner();
    private final Map<StageId,AttemptMemoryLedger.Owner> retainedStages=new HashMap<>();
    private RefinementSession session;
    private LegacyRefinementBootstrap.Seed seed;
    private LegacyRefinementBootstrap.Root root;
    private CancellationToken cancellation;
    private OperationTicket active;
    private boolean busy,closed;
    private String message="";
    private volatile int actualNativeCalls;
    public int actualNativeCalls(){return actualNativeCalls;}
    private volatile String computationEvidence="not-run";
    public String computationEvidence(){return computationEvidence;}
    private PreviewReviewState confirmation;
    private final RefinementControls controls;
    public LegacyRefinementWorkflow(LegacyReviewOwner review,TileFetchCoordinator coordinator,
            Consumer<CenterlineCandidate> rootSelection,Consumer<Display> display,Consumer<String> status) {
        this(review,coordinator,rootSelection,display,status,ForkJoinPool.commonPool(),SwingUtilities::invokeLater);
    }
    /** Controlled worker/publication queues preserve the same production ownership path. */
    public LegacyRefinementWorkflow(LegacyReviewOwner review,TileFetchCoordinator coordinator,
            Consumer<CenterlineCandidate> rootSelection,Consumer<Display> display,Consumer<String> status,
            Executor executor,AlignmentJob.EventDispatcher publicationDispatcher) {
        this.review=review;this.coordinator=coordinator;this.rootSelection=rootSelection;this.display=display;this.status=status;
        this.executor=Objects.requireNonNull(executor);this.publicationDispatcher=Objects.requireNonNull(publicationDispatcher);
        workGate=new PreviewWorkGate(publicationDispatcher);workGate.adopt(this,this::close);
        controls=new RefinementControls(new RefinementControls.Listener() {
            public void selected(String id){select(id);}
            public void undo(){navigate(false);}
            public void redo(){navigate(true);}
            public void refineA(){refine(TrackerMode.CORRIDOR_AWARE);}
            public void refineB(){refine(TrackerMode.PROBABILISTIC);}
            public void cancel(){cancelOperation();}
            public void strengthSelected(RefinementControls.Strength value){ }
            public void smooth(RefinementControls.Strength value){message="Smoothing is not qualified in this build.";present();}
        });present();
    }
    public void refresh(){present();}
    public String selectedChoiceIdentity(){return session==null?review.choiceId(review.selectedCandidate()):session.current().selected().value();}
    public List<org.openstreetmap.josm.data.coor.EastNorth> selectedGeometry(){
        if(!nativeSelected())return review.selectedCandidate().finalPreviewPoints();
        var selected=selected();return selected.payload().nativeGeometry().stream().filter(c->c.id().equals(selected.nativeCandidateId())).findFirst().orElseThrow().finalPreviewPoints();
    }
    public Stage currentStage(){if(session==null)throw new IllegalStateException("Refinement root has not been captured");return session.current();}
    public RefinementControls controls(){return controls;}
    public boolean busy(){return busy;}
    public boolean nativeSelected(){return session!=null&&session.current().kind()!=StageKind.ROOT_LEGACY;}
    public Optional<RefinementPayload.NativeReview> currentNative() {
        if(!nativeSelected())return Optional.empty();return selected().payload().nativeReview();
    }
    private Candidate selected(){var stage=session.current();return stage.candidates().stream().filter(c->c.id().equals(stage.selected())).findFirst().orElseThrow();}
    private void select(String id) {
        if(busy||closed||!ownsPublication())return;
        try {review.requireOriginalAttemptCurrent();confirmation=null;
            if(session==null){var candidate=review.candidateForChoice(id);rootSelection.accept(candidate);}
            else{session.select(new CandidateId(id));showCurrent();}
        } catch(RuntimeException failure){message=failure.getMessage();}
        present();
    }
    private void navigate(boolean forward) {
        if(busy||session==null||closed||!ownsPublication())return;
        try{review.requireOriginalAttemptCurrent();confirmation=null;if(forward)session.redo();else session.back();showCurrent();}
        catch(RuntimeException failure){message=failure.getMessage();}present();
    }
    private void showCurrent() {
        if(session.current().kind()==StageKind.ROOT_LEGACY) {
            var candidate=review.candidateForChoice(session.current().selected().value());review.select(candidate);rootSelection.accept(candidate);
        } else {
            var selected=selected();var result=selected.result();
            var actual=result.candidates().stream().filter(c->c.id().equals(selected.nativeCandidateId())).findFirst().orElseThrow();
            display.accept(new Display(result,actual,selected.payload().nativeReview()));
        }
    }
    public void refine(TrackerMode engine) {
        if(busy||closed||!ownsPublication())return;
        try {
            review.requireOriginalAttemptCurrent();confirmation=null;message="";
            if(session==null) {
                var bootstrapMemory=rootMemory.child("lazy-original-root");
                try {seed=new LegacyRefinementBootstrap().capture(review,coordinator,bootstrapMemory);}
                catch(RuntimeException refused){bootstrapMemory.close();throw refused;}
                busy=true;cancellation=new CancellationToken();present();
                var work=workGate.beginWork(this);activeWork=work;
                var captured=seed;var probe=probe(cancellation);
                CompletableFuture.supplyAsync(()->new LegacyRefinementBootstrap().complete(captured,coordinator,probe),executor)
                    .whenComplete((completed,failure)->dispatchCompletion(()->{
                        boolean continueRefinement=false;
                        try {
                            if(closed||!ownsPublication())return;
                            if(failure!=null)throw unwrap(failure);
                            probe.checkpoint();review.requireOriginalAttemptCurrent();
                            root=completed;session=new RefinementSession(root.stage(),review.originalConfig(),review.sourceIdentity(),root.authority(),RefinementLimits.defaults());
                            continueRefinement=true;
                        }catch(RuntimeException refused){captured.memory().close();seed=null;message=refused.getMessage();}
                        finally {
                            busy=false;if(closed)releaseClosed();
                            settleWork(work);
                            if(!closed){if(continueRefinement)refine(engine);else present();}
                        }
                    }));
                return;
            }
            seed.receipt().requireCurrent(coordinator,seed.original().tileMosaics());
            var parent=new ParentReferenceBinder().bind(session.authority(),session.current().id(),selected());
            var ticket=session.begin(engine);active=ticket;
            long allowance=session.availablePayloadBytes();session.reserve(ticket,32,allowance);
            var plan=RefinementSourcePlanner.plan(ticket,seed.settings(),parent);
            var sourcePayload=selected().payload();var original=review.originalResult();var credentials=CredentialSnapshot.fromConfig(review.originalConfig().heatmap());
            var operation=rootMemory.child("live-refinement-"+ticket.sequence());
            var publication=rootMemory.child("publication-"+ticket.sequence());
            var publicationAllowance=publication.reserve(allowance);
            cancellation=new CancellationToken();var probe=probe(cancellation);busy=true;present();
            var work=workGate.beginWork(this);activeWork=work;
            CompletableFuture.supplyAsync(()->compute(ticket,parent,plan,credentials,operation,sourcePayload,original,allowance,probe),executor)
                .whenComplete((completed,failure)->dispatchCompletion(()->{
                    try {
                        if(!closed&&ownsPublication()) {
                            if(failure!=null)throw unwrap(failure);
                            probe.checkpoint();review.requireOriginalAttemptCurrent();
                            if(completed==null){session.fail(ticket,Outcome.NO_USABLE_RESULT);message="No usable refinement candidate was produced.";}
                            else if(session.publish(ticket,completed)){
                                publicationAllowance.close();
                                long bytes=completed.candidates().stream().mapToLong(c->c.payload().retainedBytes()).sum();
                                publication.reserve(bytes).adopt(completed);retainedStages.put(completed.id(),publication);
                                var live=session.history().stream().map(Stage::id).collect(java.util.stream.Collectors.toSet());
                                retainedStages.entrySet().removeIf(entry->{if(live.contains(entry.getKey()))return false;entry.getValue().close();return true;});
                                message="";showCurrent();
                            }
                        }
                    }catch(RuntimeException refused){if(!closed)session.fail(ticket,probe.cancelled()?Outcome.CANCELLED:Outcome.FAILED);message=refused.getMessage();}
                    finally{operation.close();if(!retainedStages.containsValue(publication))publication.close();session.workerSettled(ticket);active=null;busy=false;if(closed)releaseClosed();settleWork(work);if(!closed)present();}
                }));
        }catch(RuntimeException refused){
            if(active!=null&&!busy){session.fail(active,Outcome.FAILED);session.workerSettled(active);active=null;}
            message=refused.getMessage();busy=false;present();
        }
    }
    private Stage compute(OperationTicket ticket,ParentTraceReference parent,RefinementSourcePlanner.Plan plan,
            CredentialSnapshot credentials,AttemptMemoryLedger.Owner operation,RefinementPayload sourcePayload,
            AlignmentResult original,long allowance,CancellationProbe probe) {
        var acquired=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquire(plan,credentials,probe,operation);
        var sources=RefinementPipeline.managedSources(acquired.value(),coordinator);
        var evidence=new HashMap<RefinementSourcePlanner.SourceRequest,EvidenceSnapshot>();
        var wrapped=new RefinementPipeline.Sources(){
            public void requireCurrent(){probe.checkpoint();sources.requireCurrent();}
            public EvidenceSnapshot evidence(RefinementSourcePlanner.SourceRequest source,CancellationProbe cancellation){var actual=sources.evidence(source,cancellation);evidence.put(source,actual);return actual;}
            public Optional<RefinementSourcePlanner.Plan> binding(){return sources.binding();}
            public Optional<ManagedModernPreviewSource.RefinementWorkReceipt> actualSourceWork(){return sources.actualSourceWork();}
        };
        var outcome=new RefinementPipeline().run(ticket,parent,plan,new RefinementPipeline.Adapters(wrapped,RefinementPipeline.productionRunner(),operation),
            RefinementPipeline.OperationBudget.production(parent.root().request().budgets()),probe);
        var operationReceipt=RefinementOperationReceipt.capture(ticket,parent,outcome);
        actualNativeCalls+=outcome.attempts().stream().flatMap(a->a.run().stream()).mapToInt(RefinementEngineAdapter.Run::nativeCalls).sum();
        computationEvidence="outcome="+outcome.outcome()+" full="+outcome.candidates().size()+" rejected="+outcome.rejected().size()+" attempts="+outcome.attempts().stream().map(a->a.kind()+":"+a.reason()+":"+a.nativeCallAvailability()).toList()
            +" rejectedFindings="+outcome.rejected().stream().map(a->a.quality().findings().stream().map(f->f.code().name()).toList()).toList();
        var candidates=new ArrayList<Candidate>();int index=0;long remaining=allowance;
        for(var retained:outcome.candidates()) {
            probe.checkpoint();var assessed=retained.assessed();var source=retained.sources().get(0);
            try {
                var edit=new ModernSingleWayEditPlanAdapter().adaptRefinement(parent,assessed,evidence.get(source));
                var points=assessed.candidate().points().stream().map(p->{var ll=edit.metricFrame().toGeographic(p);return Projections.getProjectionByCode(parent.root().projectionCode()).latlon2eastNorth(new LatLon(ll.latitudeDegrees(),ll.longitudeDegrees()));}).toList();
                var assignments=new LinkedHashMap<Long,org.openstreetmap.josm.data.coor.EastNorth>();
                for(int i=0;i<points.size();i++)if(assessed.candidate().pointIds().get(i) instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing)assignments.put(existing.nodeKey().id(),points.get(i));
                String id="refinement-"+ticket.sequence()+"-"+index++;
                String nativeId=assessed.candidate().id();
                var candidate=new CenterlineCandidate(nativeId,0,List.of(),List.of(),points,points,assignments,List.of(),0,null,null,null,List.of());
                var detector=new DetectorAttempt(source.palette(),source.mapping(),ticket.engine(),edit.validation().disposition()==ValidationReport.Disposition.APPLICABLE?DetectorAttemptStatus.APPLICABLE:DetectorAttemptStatus.REVIEW_REQUIRED,List.of(nativeId),"refinement-final","Actual full parent-backed composition");
                var result=new AlignmentResult(original.selection(),null,List.of(candidate),original.sourcePolyline(),points,List.of(),null,null,List.of(detector),List.of(candidate));
                var nativeReview=new RefinementPayload.NativeReview(edit,assessed.quality(),Optional.of(operationReceipt),retained.sources(),
                    OptionalInt.of((int)assessed.candidate().spans().stream().filter(ParentBackedComposer.Span::parentRetained).count()));
                var payload=RefinementPayload.captureNative(result,sourcePayload,nativeReview,remaining);
                candidates.add(new Candidate(new CandidateId(id),payload,assessed.candidate().correspondence(),edit.canonicalHash(),nativeId,source.palette()));remaining-=payload.retainedBytes();
            }catch(IllegalArgumentException unsupported){computationEvidence+=" plan-refusal="+unsupported.getClass().getSimpleName();}
        }
        probe.checkpoint();return candidates.isEmpty()?null:Stage.child(ticket,candidates,candidates.get(0).id());
    }
    private CancellationProbe probe(CancellationToken token){
        var originalSource=seed==null?null:seed.receipt().generationGuard();
        return new CancellationProbe(){
        public boolean cancelled(){
            if(token.isCancelled()||review.invalidated()||!ownsPublication())return true;
            try{if(originalSource!=null)originalSource.getAsLong();return false;}
            catch(IllegalStateException stale){return true;}
        }
        public Registration onCancellation(Runnable callback){var registration=token.onCancellation(callback);return registration::close;}
    };}
    private void dispatchCompletion(Runnable completion) {
        try{publicationDispatcher.dispatch(completion);}catch(RuntimeException rejected){SwingUtilities.invokeLater(completion);}
    }
    private void settleWork(PreviewWorkGate.Work work){if(activeWork==work)activeWork=null;workGate.workerSettled(work);}
    private static RuntimeException unwrap(Throwable failure){while(failure instanceof CompletionException&&failure.getCause()!=null)failure=failure.getCause();return failure instanceof RuntimeException runtime?runtime:new IllegalStateException("Refinement failed",failure);}
    public void confirm() {
        review.requireOriginalAttemptCurrent();var plan=currentNative().orElseThrow().plan();
        confirmation=PreviewReviewState.fromEditPlan(selected().id().value(),plan).confirm();present();
    }
    public boolean canApplyNative(){return !busy&&!closed&&ownsPublication()&&!review.invalidated()&&session!=null&&session.canApply()&&currentNative().map(n->PreviewReviewState.fromEditPlan(selected().id().value(),n.plan()).canApply()||confirmation!=null&&confirmation.matches(PreviewReviewState.fromEditPlan(selected().id().value(),n.plan()))).orElse(false);}
    public Command command() {
        review.requireOriginalAttemptCurrent();
        if(!canApplyNative())throw new IllegalStateException("Refinement candidate requires review or is busy");
        var plan=currentNative().orElseThrow().plan();
        var validator=new LiveNetworkSnapshotValidator(root.receipt(),plan,seed.receipt().generationGuard());
        var command=new ApplyAlignmentEditPlanCommand(review.dataSet(),plan,validator,"Apply heatmap refinement");
        if(!session.terminalizeForApply())throw new IllegalStateException("The refinement session is no longer idle");
        return command;
    }
    private void cancelOperation(){if(cancellation!=null)cancellation.cancel();if(active!=null)session.cancel(active);message="Refinement cancelled; waiting for its worker to settle.";present();}
    private void present() {
        if(closed||!ownsPublication())return;
        var yes=new RefinementControls.Availability(!busy,"");
        var no=new RefinementControls.Availability(false,"Smoothing is not qualified in this build.");
        List<RefinementControls.Choice> choices;String selected;String stage;boolean undo=false,redo=false;
        if(session==null){choices=review.choices().stream().map(c->new RefinementControls.Choice(c.id(),"Legacy / "+c.candidate().displayName())).toList();selected=review.choiceId(review.selectedCandidate());stage="Legacy — stage 1";}
        else{var current=session.current();choices=current.candidates().stream().map(c->new RefinementControls.Choice(c.id().value(),current.engine()+" / "+c.sourcePalette())).toList();selected=current.selected().value();int cursor=session.history().indexOf(current);undo=cursor>0;redo=cursor+1<session.history().size();stage=current.engine()+" — stage "+(cursor+1);}
        String quality=message;
        if(nativeSelected()){
            var actual=currentNative().orElseThrow();
            quality=actual.plan().validation().disposition()+"\n"+actual.quality().findings()+"\n"
                +actual.operation().map(RefinementOperationReceipt::description).orElse("Operation work unavailable")
                +actual.retainedParentSections().stream().mapToObj(count->"; retained parent sections: "+count).findFirst().orElse("");
            if(!message.isEmpty())quality=message+"\n"+quality;
        }
        if(busy)quality="Refinement is running…";status.accept(quality);
        var refine=new RefinementControls.Availability(!busy&&review.managedReceipt()!=null,"Original managed acquisition is required.");
        controls.present(new RefinementControls.ViewState(stage,choices,selected,quality,RefinementControls.Strength.MEDIUM,busy,yes,
            new RefinementControls.Availability(!busy&&undo,"First preview stage"),new RefinementControls.Availability(!busy&&redo,"No later preview stage"),refine,refine,no));
    }
    private void releaseClosed(){rootMemory.close();retainedStages.clear();seed=null;root=null;session=null;confirmation=null;}
    @Override public void close(){if(closed)return;closed=true;workGate.release(this);if(cancellation!=null)cancellation.cancel();if(session!=null)session.close();if(!busy)releaseClosed();}
}
