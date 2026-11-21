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
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.*;

/** Actual modeless Legacy -> Task4 -> cached history connection. All session/UI operations stay on EDT. */
public final class LegacyRefinementWorkflow implements AutoCloseable, org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry.RefinementProducer {
    /** Exact native lifecycle without retaining imagery/history. Notification is delivered on EDT. */
    public static final class NativeEntry {
        private final OperationTicket ticket;
        private final java.util.concurrent.atomic.AtomicBoolean entered=new java.util.concurrent.atomic.AtomicBoolean();
        private final CompletableFuture<Void> settlement=new CompletableFuture<>();
        private volatile boolean cancellationRequested,cancellationObserved,workerReturned;
        private NativeEntry(OperationTicket ticket){this.ticket=ticket;}
        public OperationTicket ticket(){return ticket;}
        public boolean entered(){return entered.get();}
        public boolean cancellationRequested(){return cancellationRequested;}
        public boolean cancellationObserved(){return cancellationObserved;}
        public boolean workerReturned(){return workerReturned;}
        public CompletionStage<Void> settlement(){return settlement.minimalCompletionStage();}
    }
    private final CompletableFuture<NativeEntry> firstNativeEntry=new CompletableFuture<>();
    private NativeEntry activeEntry;
    public CompletionStage<NativeEntry> firstNativeEntry(){return firstNativeEntry.minimalCompletionStage();}
    public boolean ownsNativeEntry(NativeEntry entry){return busy&&ownsPublication()&&activeEntry==entry&&Objects.equals(active,entry.ticket());}
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
    /** Installs the actual retained Legacy context lazily; ordinary Legacy review does no export conversion. */
    public void enableRuntimeDiagnostics(java.util.function.Supplier<LastSlideDebugBundle> rootContext) {
        enableRuntimeDiagnostics(rootContext,Map::of);
    }
    public void enableRuntimeDiagnostics(java.util.function.Supplier<LastSlideDebugBundle> rootContext,java.util.function.Supplier<Map<String,CandidateRating>> ratings) {
        if(!SwingUtilities.isEventDispatchThread()||busy||session!=null||closed||!ownsPublication())throw new IllegalStateException("Diagnostic attachment requires original idle review");
        legacyContextSupplier=Objects.requireNonNull(rootContext);ratingSupplier=Objects.requireNonNull(ratings);initialDiagnosticChoice=review.choiceId(review.selectedCandidate());
    }
    private java.util.function.Supplier<Map<String,CandidateRating>> ratingSupplier;
    private java.util.function.Supplier<LastSlideDebugBundle> legacyContextSupplier;
    private LastSlideDebugBundle legacyDiagnosticContext;
    private DiagnosticsRegistry.RefinementOwner diagnosticOwner;
    private String initialDiagnosticChoice,diagnosticState="preview-open";
    private RefinementLineageCodec.CandidateKey diagnosticSelection,reviewedSelection;
    private AttemptProgress activeProgress;
    private enum ExportUnavailable { NONE, BUDGET, BINDING, ENCODING }
    private ExportUnavailable exportUnavailable=ExportUnavailable.NONE;
    private String legacyContextAvailability="unavailable-not-retained";
    private boolean preparingExport,diagnosticSealed;
    private static final class ExportBudgetRefused extends IllegalStateException { }
    private String exportUnavailableMessage(){return switch(exportUnavailable){case NONE->"";case BUDGET->"Refinement diagnostics unavailable: memory budget reached";case BINDING->"Refinement diagnostics unavailable: retained binding could not be verified";case ENCODING->"Refinement diagnostics unavailable: archive encoding failed";};}
    public long workflowOwnedBytes(){return diagnosticMemory.workflowOwnedBytes();}
    public long registryOwnedBytes(){return diagnosticMemory.registryOwnedBytes();}
    @Override public boolean isCurrent(){return !closed&&ownsPublication()&&session!=null&&diagnosticOwner!=null&&!diagnosticSealed;}
    private void startDiagnostics() {
        if(closed||!ownsPublication()||legacyContextSupplier==null||diagnosticOwner!=null)return;
        // Conversion is admitted only after explicit refinement. Root imagery is factual retained Legacy context.
        var owner=rootMemory.child("legacy-export-context");
        var conversion=rootMemory.child("legacy-export-conversion");
        try(var allowance=conversion.reserve(32L*1024*1024)) {
            var context=legacyContextSupplier.get();
            if(closed||!ownsPublication()){owner.close();return;}
            if(context!=null) {
                long bytes=context.retainedContextBytes(review.originalResult());if(bytes>32L*1024*1024)throw new ExportBudgetRefused();
                // The admitted conversion covers temporary construction; retain only the factual declared context bound.
                synchronized(memory){allowance.close();owner.reserve(bytes).adopt(context);}
                legacyDiagnosticContext=context;legacyContextAvailability="retained-root-only";
            }
        } catch(RuntimeException unavailable){owner.close();legacyContextAvailability=unavailable instanceof AttemptMemoryLedger.ResourceLimitException||unavailable instanceof ExportBudgetRefused?"unavailable-budget":"unavailable-construction";}
        finally{conversion.close();}
        legacyContextSupplier=null;if(!closed&&ownsPublication())diagnosticOwner=DiagnosticsRegistry.claimRefinement(this);
    }
    public boolean handlesRuntimeDiagnostics(){return diagnosticOwner!=null;}
    public void diagnosticsChanged(String state) {
        if(!Set.of("preview-open","review-confirmed","review-confirmation-failed","cancelled","applied","apply-failed").contains(state))throw new IllegalArgumentException("Fixed diagnostic state required");
        diagnosticState=state;if(diagnosticOwner!=null)DiagnosticsRegistry.invalidateRefinement(diagnosticOwner);
    }
    private record PendingLegacyReview(String choice,String fingerprint) { }
    private PendingLegacyReview pendingLegacyReview;
    private AttemptMemoryLedger.Owner pendingLegacyReviewMemory;
    /** Historical review proof only; it never sets session-local Apply authorization. */
    public void reviewedLegacy(CandidateReviewConfirmation confirmation){
        if(closed||busy||!ownsPublication()||nativeSelected()||!hasCurrentPresentation())return;
        var candidate=review.selectedCandidate();
        if(!Objects.requireNonNull(confirmation).matches(candidate,candidate.finalPreviewPoints()))throw new IllegalArgumentException("Legacy review geometry differs from the exact original choice");
        if(pendingLegacyReviewMemory!=null)pendingLegacyReviewMemory.close();
        pendingLegacyReviewMemory=rootMemory.child("legacy-reviewed-identity");
        try(var allowance=pendingLegacyReviewMemory.reserve(1024)){
            pendingLegacyReview=new PendingLegacyReview(review.choiceId(candidate),legacyReviewFingerprint(confirmation));allowance.adopt(pendingLegacyReview);
            bindLegacyReview();diagnosticsChanged("review-confirmed");
        }catch(AttemptMemoryLedger.ResourceLimitException unavailable){pendingLegacyReviewMemory.close();pendingLegacyReviewMemory=null;pendingLegacyReview=null;exportUnavailable=ExportUnavailable.BUDGET;}
    }
    private void bindLegacyReview(){
        if(session==null||pendingLegacyReview==null)return;
        var original=session.history().get(0);
        original.candidates().stream().filter(candidate->candidate.id().value().equals(pendingLegacyReview.choice())).findFirst().ifPresent(candidate->{
            var nativeCandidate=candidate.payload().nativeGeometry().stream().filter(value->value.id().equals(candidate.nativeCandidateId())).findFirst().orElseThrow();
            var captured=new CandidateReviewConfirmation(nativeCandidate.id(),candidate.payload().finalPreviewGeometry(),nativeCandidate.proposedNodePositions());
            if(pendingLegacyReview.fingerprint().equals(legacyReviewFingerprint(captured)))reviewedSelection=new RefinementLineageCodec.CandidateKey(original.id(),candidate.id());
        });
        pendingLegacyReview=null;if(pendingLegacyReviewMemory!=null)pendingLegacyReviewMemory.close();pendingLegacyReviewMemory=null;
    }
    private static String legacyReviewFingerprint(CandidateReviewConfirmation proof){
        try{
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            java.util.function.Consumer<String> field=value->{digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)0);};
            field.accept(proof.candidateId());field.accept(Integer.toString(proof.previewGeometry().size()));
            for(var point:proof.previewGeometry()){field.accept(Double.toHexString(point.east()));field.accept(Double.toHexString(point.north()));}
            proof.proposedNodePositions().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry->{field.accept(Long.toString(entry.getKey()));field.accept(Double.toHexString(entry.getValue().east()));field.accept(Double.toHexString(entry.getValue().north()));});
            return java.util.HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("SHA-256 unavailable",unavailable);}
    }
    private RefinementLineageCodec.CandidateKey currentDiagnosticKey(){return new RefinementLineageCodec.CandidateKey(session.current().id(),session.current().selected());}
    @Override public void prepareExport() {
        if(!SwingUtilities.isEventDispatchThread()) {
            try{SwingUtilities.invokeAndWait(this::prepareExport);}catch(Exception unavailable){throw new IllegalStateException("Current refinement diagnostics are unavailable");}
            return;
        }
        freezeDiagnostics(false,false);
        if(exportUnavailable!=ExportUnavailable.NONE&&!diagnosticSealed)throw new IllegalStateException(exportUnavailableMessage());
    }
    /** Called after the existing command actually executes, with observed post-command geometry. */
    private boolean appliedNotificationFailed;
    public void appliedDiagnostics(){appliedDiagnostics(false);}
    public void appliedDiagnostics(boolean notificationFailed) {
        if(!isCurrent())return;appliedNotificationFailed=notificationFailed;diagnosticsChanged("applied");freezeDiagnostics(true,true);
    }
    static RefinementAttemptDiagnostics terminalDiagnostics(AttemptProgress progress,boolean workerReturned) {
        // The volatile returned flag publishes stable worker facts. Before it, do not read mutable progress.
        return new RefinementAttemptDiagnostics(progress.binding,workerReturned?progress.receipt:Optional.empty(),
            RefinementAttemptDiagnostics.Publication.CANCELLED,RefinementAttemptDiagnostics.Phase.PUBLICATION,
            RefinementAttemptDiagnostics.Cause.CANCELLED,workerReturned?progress.materializationRefusals:0,workerReturned);
    }
    private void freezeDiagnostics(boolean terminal,boolean appliedNow) {
        if(!isCurrent()||preparingExport)return;preparingExport=true;
        java.nio.file.Path encoded=null,legacy=null;
        try(var admission=diagnosticMemory.reserveExport(32L*1024*1024)) {
            Optional<RefinementAttemptDiagnostics> latest=Optional.ofNullable(latestDiagnostics);
            Optional<RefinementLineageCodec.CandidateKey> result=Optional.ofNullable(diagnosticSelection);
            var availability=diagnosticsAvailability;
            if(terminal&&busy&&activeProgress!=null) {
                // UI closure settles publication now, while physical input/result pins remain until the worker returns.
                latest=Optional.of(terminalDiagnostics(activeProgress,activeEntry!=null&&activeEntry.workerReturned));
                result=Optional.of(new RefinementLineageCodec.CandidateKey(activeProgress.binding.ticket().parent(),activeProgress.binding.ticket().choice()));
                availability=RefinementAttemptDiagnostics.Availability.AVAILABLE;
            }
            long points=session.history().stream().flatMap(stage->stage.candidates().stream()).mapToLong(candidate->candidate.payload().finalPreviewGeometry().size()).sum();
            var originalWay=(DetachedWay)root.authority().network().primitives().get(root.authority().request().selectedWayKey());
            // Bounds cover original/candidate CSV construction in addition to codec's separate 2 MiB metadata cap.
            if(points+originalWay.nodeKeys().size()>2048)throw new ExportBudgetRefused();
            Optional<Format15Artifact> context=Optional.empty();
            if(legacyDiagnosticContext!=null) {
                legacy=java.nio.file.Files.createTempFile("legacy-root-context-",".zip");legacyDiagnosticContext.writeTo(legacy.toFile());
                if(java.nio.file.Files.size(legacy)<=2L*1024*1024){context=Optional.of(Format15Artifact.binary("legacy-root-diagnostics.zip",java.nio.file.Files.readAllBytes(legacy)));legacyContextAvailability="available-root-only";}else legacyContextAvailability="unavailable-encoded-budget";
            }
            Optional<RuntimeRefinementDiagnostics.Applied> applied=Optional.empty();
            if(appliedNow) {
                var nodes=review.originalResult().selection().way().getNodes();
                if(nodes.size()>2048)throw new ExportBudgetRefused();
                var actual=nodes.stream().map(node->{var coordinate=node.getCoor();if(coordinate==null)throw new IllegalStateException("Applied coordinates unavailable");return new GeographicPoint(coordinate.lat(),coordinate.lon());}).toList();
                if(nativeSelected()&&!currentNative().orElseThrow().plan().finalPreviewWays().get(root.authority().request().selectedWayKey()).equals(actual))throw new IllegalStateException("Observed applied way differs from the exact stored plan");
                applied=Optional.of(new RuntimeRefinementDiagnostics.Applied(currentDiagnosticKey(),selected().immutableIdentity(),actual));
            }
            var reviewed=Optional.ofNullable(reviewedSelection).filter(key->session.history().stream().anyMatch(stage->stage.id().equals(key.stage())&&stage.candidates().stream().anyMatch(candidate->candidate.id().equals(key.candidate()))));
            var bundle=RuntimeRefinementDiagnostics.capture(root.authority(),session.history(),session.current(),session.id(),initialDiagnosticChoice,
                latest,result,availability,diagnosticState,reviewed,applied,context);
            var artifacts=new LinkedHashMap<>(bundle.artifacts());
            if(appliedNow){var publication=Format15Artifact.text("host-command-publication.txt",appliedNotificationFailed?"committed-notification-failed\n":"committed\n");artifacts.put(publication.name(),publication);}
            var ratings=ratingSupplier==null?Map.<String,CandidateRating>of():ratingSupplier.get();
            if(ratings.size()>256)throw new ExportBudgetRefused();
            var calibration=Format15Artifact.text("candidate-ratings.json",LastSlideDebugBundle.redactedCandidateRatings(ratings));artifacts.put(calibration.name(),calibration);
            var contextStatus=Format15Artifact.text("legacy-root-context-status.txt",legacyContextAvailability+"\n");artifacts.put(contextStatus.name(),contextStatus);
            bundle=new Format15Bundle(bundle.buildIdentity(),bundle.sourceIdentityHash(),bundle.parameterHash(),artifacts);
            encoded=java.nio.file.Files.createTempFile("refinement-diagnostics-",".zip");Format15BundleWriter.write(bundle,encoded);
            if(!isCurrent())return;
            boolean published=DiagnosticsRegistry.publishRefinement(diagnosticOwner,admission.read(encoded),terminal);
            if(published){diagnosticSealed=terminal;exportUnavailable=ExportUnavailable.NONE;}
        } catch(Exception unavailable){exportUnavailable=unavailable instanceof AttemptMemoryLedger.ResourceLimitException||unavailable instanceof ExportBudgetRefused?ExportUnavailable.BUDGET:unavailable instanceof java.io.IOException?ExportUnavailable.ENCODING:ExportUnavailable.BINDING;
            if(terminal&&isCurrent())diagnosticSealed=DiagnosticsRegistry.sealRefinementUnavailable(diagnosticOwner,DiagnosticsRegistry.RefinementUnavailable.valueOf(exportUnavailable.name()));
        }
        finally {
            for(var path:new java.nio.file.Path[]{encoded,legacy})if(path!=null)try{java.nio.file.Files.deleteIfExists(path);}catch(java.io.IOException ignored){}
            preparingExport=false;
        }
    }
    public long ownedBytes(){return memory.currentBytes();}
    public boolean ownsPublication(){return workGate.isCurrent(this);}
    public void attachOwnership(PreviewWorkGate shared,Object transferredOwner,Runnable closeWindow) {
        if(!SwingUtilities.isEventDispatchThread()||busy)throw new IllegalStateException("Ownership transfer requires idle EDT");
        workGate.release(this);workGate=Objects.requireNonNull(shared);workGate.release(transferredOwner);
        workGate.adopt(this,()->{close();closeWindow.run();});
    }
    private final AttemptMemoryLedger memory=AttemptMemoryLedger.production();
    private final RefinementDiagnosticsMemory diagnosticMemory=new RefinementDiagnosticsMemory(memory);
    private final AttemptMemoryLedger.Owner rootMemory=diagnosticMemory.workflowOwner();
    private final Map<StageId,AttemptMemoryLedger.Owner> retainedStages=new HashMap<>();
    private volatile RefinementAttemptDiagnostics latestDiagnostics;
    private RefinementAttemptDiagnostics.Slot diagnosticsSlot;
    private volatile RefinementAttemptDiagnostics.Availability diagnosticsAvailability=RefinementAttemptDiagnostics.Availability.NONE;
    public Optional<RefinementAttemptDiagnostics> latestAttemptDiagnostics(){return closed||!ownsPublication()?Optional.empty():Optional.ofNullable(latestDiagnostics);}
    public RefinementAttemptDiagnostics.Availability attemptDiagnosticsAvailability(){return closed||!ownsPublication()?RefinementAttemptDiagnostics.Availability.NONE:diagnosticsAvailability;}
    private void clearDiagnostics(){latestDiagnostics=null;diagnosticSelection=null;if(diagnosticsSlot!=null)diagnosticsSlot.close();diagnosticsSlot=null;diagnosticsAvailability=RefinementAttemptDiagnostics.Availability.NONE;}
    static final class AttemptProgress {
        final RefinementAttemptDiagnostics.Binding binding;
        final RefinementAttemptDiagnostics.Slot slot;
        RefinementAttemptDiagnostics.Phase phase=RefinementAttemptDiagnostics.Phase.PREPARATION;
        Optional<RefinementOperationReceipt> receipt=Optional.empty();
        int materializationRefusals;
        boolean presentationAttempted;
        Outcome pipelineOutcome;
        AttemptProgress(RefinementAttemptDiagnostics.Binding binding,RefinementAttemptDiagnostics.Slot slot){this.binding=binding;this.slot=slot;}
        RefinementAttemptDiagnostics snapshot(RefinementAttemptDiagnostics.Publication publication,RefinementAttemptDiagnostics.Cause cause){return new RefinementAttemptDiagnostics(binding,receipt,publication,phase,cause,materializationRefusals);}
    }
    record Computed(Optional<Stage> stage,RefinementAttemptDiagnostics.Cause cause,
            Optional<AttemptMemoryLedger.Reservation> publicationAllowance) {
        Computed(Optional<Stage> stage,RefinementAttemptDiagnostics.Cause cause){this(stage,cause,Optional.empty());}
    }
    private void publishDiagnostics(OperationTicket ticket,AttemptProgress progress,RefinementAttemptDiagnostics.Publication publication,RefinementAttemptDiagnostics.Cause cause){
        if(closed||!ownsPublication()||!Objects.equals(active,ticket))return;
        if(progress.slot==null){diagnosticsAvailability=RefinementAttemptDiagnostics.Availability.UNAVAILABLE_BUDGET;return;}
        try {
            var snapshot=progress.snapshot(publication,cause);
            if(progress.slot.retain(snapshot)){latestDiagnostics=snapshot;diagnosticSelection=publication==RefinementAttemptDiagnostics.Publication.PUBLISHED?currentDiagnosticKey():new RefinementLineageCodec.CandidateKey(ticket.parent(),ticket.choice());diagnosticsAvailability=RefinementAttemptDiagnostics.Availability.AVAILABLE;return;}
        }catch(RuntimeException unavailable){ /* Diagnostic admission must never interrupt result/input settlement. */ }
        diagnosticsAvailability=RefinementAttemptDiagnostics.Availability.UNAVAILABLE_BUDGET;progress.slot.close();diagnosticsSlot=null;
    }

    private RefinementSession session;
    private LegacyRefinementBootstrap.Seed seed;
    private LegacyRefinementBootstrap.Root root;
    private CancellationToken cancellation;
    private OperationTicket active;
    private boolean busy,closed;
    private volatile boolean originalSourceStale;
    private String presentedRootChoice;
    private record Presentation(StageId stage,CandidateId choice,String identity) { }
    private Presentation presentedNative;
    private static final class PresentationFailed extends IllegalStateException {
        PresentationFailed(){super("The selected preview could not be displayed.");}
    }
    /** Exact selected geometry must have completed its display callback before any review/apply boundary. */
    public boolean hasCurrentPresentation(){
        if(closed||!ownsPublication())return false;
        if(!nativeSelected())return Objects.equals(presentedRootChoice,review.choiceId(review.selectedCandidate()));
        var stage=session.current();var candidate=selected();
        return new Presentation(stage.id(),candidate.id(),candidate.immutableIdentity()).equals(presentedNative);
    }
    private boolean originalSourceCurrent(){
        if(originalSourceStale)return false;
        if(seed!=null)try{seed.receipt().generationGuard().getAsLong();}
        catch(IllegalStateException stale){originalSourceStale=true;return false;}
        return true;
    }
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
        this.review=review;this.coordinator=coordinator;this.presentedRootChoice=review.choiceId(review.selectedCandidate());this.rootSelection=rootSelection;this.display=display;this.status=status;
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
            if(session==null){var candidate=review.candidateForChoice(id);showRoot(candidate,id);}
            else{session.select(new CandidateId(id));showCurrent();}
        } catch(RuntimeException failure){message=failure.getMessage();}
        present();
    }
    private void navigate(boolean forward) {
        if(busy||session==null||closed||!ownsPublication())return;
        try{review.requireOriginalAttemptCurrent();confirmation=null;if(forward)session.redo();else session.back();showCurrent();}
        catch(RuntimeException failure){message=failure.getMessage();}present();
    }
    private void showRoot(CenterlineCandidate candidate,String choice) {
        presentedNative=null;presentedRootChoice=null;
        try{review.select(candidate);rootSelection.accept(candidate);presentedRootChoice=choice;}
        catch(RuntimeException|Error failed){throw new PresentationFailed();}
    }
    private void showCurrent(){showStage(session.current());}
    private void showStage(Stage stage) {
        if(stage.kind()==StageKind.ROOT_LEGACY) {
            showRoot(review.candidateForChoice(stage.selected().value()),stage.selected().value());
        } else {
            presentedNative=null;presentedRootChoice=null;
            var candidate=stage.candidates().stream().filter(c->c.id().equals(stage.selected())).findFirst().orElseThrow();
            var result=candidate.result();var actual=result.candidates().stream().filter(c->c.id().equals(candidate.nativeCandidateId())).findFirst().orElseThrow();
            try{display.accept(new Display(result,actual,candidate.payload().nativeReview()));}
            catch(RuntimeException|Error failed){throw new PresentationFailed();}
            presentedNative=new Presentation(stage.id(),candidate.id(),candidate.immutableIdentity());
        }
    }
    private boolean restoreCurrentPresentation(){
        if(closed||!ownsPublication()){presentedNative=null;presentedRootChoice=null;return false;}
        try{
            showCurrent();
            if(closed||!ownsPublication()){presentedNative=null;presentedRootChoice=null;return false;}
            return hasCurrentPresentation();
        }catch(PresentationFailed failed){return false;}
    }
    public void refine(TrackerMode engine) {
        if(busy||closed||!ownsPublication())return;
        AttemptProgress preparing=null;
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
                            bindLegacyReview();startDiagnostics();continueRefinement=true;
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
            clearDiagnostics();
            long available=session.availablePayloadBytes();
            diagnosticsSlot=RefinementAttemptDiagnostics.Slot.open(rootMemory,available).orElse(null);
            diagnosticsAvailability=diagnosticsSlot==null?RefinementAttemptDiagnostics.Availability.UNAVAILABLE_BUDGET:RefinementAttemptDiagnostics.Availability.RUNNING;
            long allowance=available-(diagnosticsSlot==null?0:diagnosticsSlot.bytes());
            var progress=new AttemptProgress(new RefinementAttemptDiagnostics.Binding(ticket,session.history().get(0).id(),root.authority().sourceIdentity(),
                root.authority().network().canonicalHash(),RuntimeRefinementDiagnostics.frozenSettingsIdentity(root.authority()),root.authority().frozenGeometryIdentity(),parent.canonicalIdentity()),diagnosticsSlot);
            activeProgress=progress;preparing=progress;session.reserve(ticket,32,allowance);
            var plan=RefinementSourcePlanner.plan(ticket,seed.settings(),parent);
            var sourcePayload=selected().payload();var original=review.originalResult();var credentials=CredentialSnapshot.fromConfig(review.originalConfig().heatmap());
            var operation=rootMemory.child("live-refinement-"+ticket.sequence());
            var publication=rootMemory.child("publication-"+ticket.sequence());
            var entry=new NativeEntry(ticket);activeEntry=entry;
            cancellation=new CancellationToken();var probe=probe(cancellation,entry);busy=true;present();
            var work=workGate.beginWork(this);activeWork=work;
            CompletableFuture<Computed> computation;
            try {computation=CompletableFuture.supplyAsync(()->compute(ticket,parent,plan,credentials,operation,publication,sourcePayload,original,allowance,probe,entry,progress),executor);}
            catch(RuntimeException rejected){computation=CompletableFuture.failedFuture(rejected);}
            computation.whenComplete((completed,failure)->{entry.workerReturned=true;dispatchCompletion(()->{
                    var publicationOutcome=RefinementAttemptDiagnostics.Publication.FAILED;
                    var diagnosticCause=RefinementAttemptDiagnostics.Cause.FAILED;
                    try {
                        if(!closed&&ownsPublication()&&Objects.equals(active,ticket)) {
                            if(failure!=null)throw unwrap(failure);
                            diagnosticCause=completed.cause();
                            var workerPhase=progress.phase;progress.phase=RefinementAttemptDiagnostics.Phase.PUBLICATION;
                            probe.checkpoint();review.requireOriginalAttemptCurrent();
                            if(completed.stage().isEmpty()){
                                progress.phase=workerPhase;
                                Outcome factual=progress.pipelineOutcome==null?Outcome.NO_USABLE_RESULT:progress.pipelineOutcome;
                                session.fail(ticket,factual==Outcome.COMPLETE||factual==Outcome.PARTIAL?Outcome.NO_USABLE_RESULT:factual);
                                publicationOutcome=RefinementAttemptDiagnostics.Publication.NO_CHILD;
                                message=diagnosticCause==RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT?"Refinement reached its resource limit; retained parent.":
                                    diagnosticCause==RefinementAttemptDiagnostics.Cause.PLAN_REFUSED?"Refinement candidate could not form an edit plan; retained parent.":"No usable refinement candidate was produced.";
                            } else {
                                progress.phase=RefinementAttemptDiagnostics.Phase.PUBLICATION;
                                var child=completed.stage().get();
                                if(session.publish(ticket,child,()->{
                                    long bytes=child.candidates().stream().mapToLong(c->c.payload().retainedBytes()).sum();
                                    synchronized(memory) {
                                        completed.publicationAllowance().orElseThrow().close();
                                        publication.reserve(bytes).adopt(child);
                                    }
                                    progress.presentationAttempted=true;showStage(child);
                                    probe.checkpoint();review.requireOriginalAttemptCurrent();
                                })) {
                                    retainedStages.put(child.id(),publication);
                                    var live=session.history().stream().map(Stage::id).collect(java.util.stream.Collectors.toSet());
                                    retainedStages.entrySet().removeIf(item->{if(live.contains(item.getKey()))return false;item.getValue().close();return true;});
                                    publicationOutcome=RefinementAttemptDiagnostics.Publication.PUBLISHED;diagnosticCause=RefinementAttemptDiagnostics.Cause.NONE;
                                    message="";
                                } else {
                                    publicationOutcome=RefinementAttemptDiagnostics.Publication.REFUSED;diagnosticCause=RefinementAttemptDiagnostics.Cause.PUBLICATION_REFUSED;
                                    if(progress.presentationAttempted&&!restoreCurrentPresentation()){publicationOutcome=RefinementAttemptDiagnostics.Publication.FAILED;diagnosticCause=RefinementAttemptDiagnostics.Cause.PRESENTATION_FAILED;}
                                }
                            }
                        }
                    }catch(RuntimeException refused){
                        boolean stale=!originalSourceCurrent()||review.invalidated()||progress.pipelineOutcome==Outcome.STALE;
                        boolean cancelled=entry.cancellationRequested&&!stale;
                        boolean presentationFailed=refused instanceof PresentationFailed;
                        if(progress.presentationAttempted&&!hasCurrentPresentation())presentationFailed|=!restoreCurrentPresentation();
                        boolean limited=refused instanceof AttemptMemoryLedger.ResourceLimitException||refused instanceof RefinementSession.Refused;
                        var outcome=cancelled?Outcome.CANCELLED:stale?Outcome.STALE:limited?Outcome.RESOURCE_LIMIT:Outcome.FAILED;
                        if(!closed)session.fail(ticket,outcome);
                        publicationOutcome=cancelled?RefinementAttemptDiagnostics.Publication.CANCELLED:stale?RefinementAttemptDiagnostics.Publication.STALE:RefinementAttemptDiagnostics.Publication.FAILED;
                        diagnosticCause=stale?RefinementAttemptDiagnostics.Cause.STALE:cancelled?RefinementAttemptDiagnostics.Cause.CANCELLED:
                            presentationFailed?RefinementAttemptDiagnostics.Cause.PRESENTATION_FAILED:limited?RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT:RefinementAttemptDiagnostics.Cause.FAILED;
                        message=cancelled?"Refinement cancelled.":stale?"Refinement source changed.":"Refinement failed.";
                    }
                    finally{publishDiagnostics(ticket,progress,publicationOutcome,diagnosticCause);operation.close();if(!retainedStages.containsValue(publication))publication.close();session.workerSettled(ticket);active=null;activeProgress=null;busy=false;if(closed)releaseClosed();activeEntry=null;settleWork(work);if(!closed)present();entry.settlement.complete(null);}
                });});
        }catch(RuntimeException refused){
            if(active!=null&&!busy){
                if(preparing!=null)publishDiagnostics(active,preparing,RefinementAttemptDiagnostics.Publication.REFUSED,
                    refused instanceof AttemptMemoryLedger.ResourceLimitException||refused instanceof RefinementSession.Refused?RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT:RefinementAttemptDiagnostics.Cause.FAILED);
                else {clearDiagnostics();diagnosticsAvailability=RefinementAttemptDiagnostics.Availability.UNAVAILABLE_BUDGET;}
                session.fail(active,Outcome.FAILED);session.workerSettled(active);active=null;
            }
            message=refused.getMessage();busy=false;present();
        }
    }
    private Computed compute(OperationTicket ticket,ParentTraceReference parent,RefinementSourcePlanner.Plan plan,
            CredentialSnapshot credentials,AttemptMemoryLedger.Owner operation,AttemptMemoryLedger.Owner publication,RefinementPayload sourcePayload,
            AlignmentResult original,long allowance,CancellationProbe probe,NativeEntry entry,AttemptProgress progress) {
        progress.phase=RefinementAttemptDiagnostics.Phase.ACQUISITION;
        var acquired=new RefinementAcquisition(new ManagedModernPreviewSource(coordinator),coordinator).acquire(plan,credentials,probe,operation);
        var sources=RefinementPipeline.managedSources(acquired.value(),coordinator);
        var evidence=new HashMap<RefinementSourcePlanner.SourceRequest,EvidenceSnapshot>();
        var wrapped=new RefinementPipeline.Sources(){
            public void requireCurrent(){probe.checkpoint();sources.requireCurrent();}
            public EvidenceSnapshot evidence(RefinementSourcePlanner.SourceRequest source,CancellationProbe cancellation){var actual=sources.evidence(source,cancellation);evidence.put(source,actual);return actual;}
            public Optional<RefinementSourcePlanner.Plan> binding(){return sources.binding();}
            public Optional<ManagedModernPreviewSource.RefinementWorkReceipt> actualSourceWork(){return sources.actualSourceWork();}
        };
        progress.phase=RefinementAttemptDiagnostics.Phase.PIPELINE;
        var outcome=new RefinementPipeline().run(ticket,parent,plan,new RefinementPipeline.Adapters(wrapped,RefinementPipeline.productionRunner(actual->{
                if(!actual.equals(ticket))throw new IllegalStateException("Native entry ticket changed");
                if(entry.entered.compareAndSet(false,true))SwingUtilities.invokeLater(()->firstNativeEntry.complete(entry));
            }),operation),
            RefinementPipeline.OperationBudget.production(parent.root().request().budgets()),probe);
        progress.pipelineOutcome=outcome.outcome();
        progress.receipt=progress.slot==null?Optional.empty():progress.slot.receipt(progress.binding,parent,outcome);
        actualNativeCalls+=outcome.attempts().stream().flatMap(a->a.run().stream()).mapToInt(RefinementEngineAdapter.Run::nativeCalls).sum();
        computationEvidence="outcome="+outcome.outcome()+" full="+outcome.candidates().size()+" rejected="+outcome.rejected().size()+" attempts="+outcome.attempts().stream().map(a->a.kind()+":"+a.reason()+":"+a.nativeCallAvailability()).toList()
            +" rejectedFindings="+outcome.rejected().stream().map(a->a.quality().findings().stream().map(f->f.code().name()).toList()).toList();
        var computed=materialize(ticket,parent,outcome,evidence,sourcePayload,original,allowance,probe,progress,publication);
        computationEvidence+=" plan-refusals="+progress.materializationRefusals;return computed;
    }
    /** Claims the unchanged future Stage bound only once actual assessed output needs materialization. */
    static Computed materialize(OperationTicket ticket,ParentTraceReference parent,RefinementPipeline.Result outcome,
            Map<RefinementSourcePlanner.SourceRequest,EvidenceSnapshot> evidence,RefinementPayload sourcePayload,
            AlignmentResult original,long allowance,CancellationProbe probe,AttemptProgress progress,AttemptMemoryLedger.Owner publication) {
        if(outcome.candidates().isEmpty())return materialize(ticket,parent,outcome,evidence,sourcePayload,original,allowance,probe,progress);
        progress.phase=RefinementAttemptDiagnostics.Phase.MATERIALIZATION;
        probe.checkpoint();
        // Input, native result, assembly and assessment owners are independent. This reservation
        // covers the new Stage payload from its first allocation through queued publication.
        var reservation=publication.reserve(allowance);
        var computed=materialize(ticket,parent,outcome,evidence,sourcePayload,original,allowance,probe,progress);
        return new Computed(computed.stage(),computed.cause(),Optional.of(reservation));
    }
    /** Same assessed full-composition materialization used by the live worker, including typed plan refusal. */
    static Computed materialize(OperationTicket ticket,ParentTraceReference parent,RefinementPipeline.Result outcome,
            Map<RefinementSourcePlanner.SourceRequest,EvidenceSnapshot> evidence,RefinementPayload sourcePayload,
            AlignmentResult original,long allowance,CancellationProbe probe,AttemptProgress progress) {
        var operationReceipt=progress.receipt;
        if(!outcome.candidates().isEmpty())progress.phase=RefinementAttemptDiagnostics.Phase.MATERIALIZATION;
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
                var nativeReview=new RefinementPayload.NativeReview(edit,assessed.quality(),operationReceipt,retained.sources(),
                    OptionalInt.of((int)assessed.candidate().spans().stream().filter(ParentBackedComposer.Span::parentRetained).count()));
                var payload=RefinementPayload.captureNative(result,sourcePayload,nativeReview,remaining);
                candidates.add(new Candidate(new CandidateId(id),payload,assessed.candidate().correspondence(),edit.canonicalHash(),nativeId,source.palette()));remaining-=payload.retainedBytes();
            }catch(IllegalArgumentException unsupported){progress.materializationRefusals++;}
        }
        probe.checkpoint();
        var cause=progress.materializationRefusals>0&&candidates.isEmpty()?RefinementAttemptDiagnostics.Cause.PLAN_REFUSED:
            outcome.outcome()==Outcome.RESOURCE_LIMIT?RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT:
            outcome.outcome()==Outcome.FAILED?RefinementAttemptDiagnostics.Cause.FAILED:
            outcome.outcome()==Outcome.STALE?RefinementAttemptDiagnostics.Cause.STALE:
            candidates.isEmpty()?RefinementAttemptDiagnostics.Cause.NO_USABLE_RESULT:RefinementAttemptDiagnostics.Cause.NONE;
        return new Computed(candidates.isEmpty()?Optional.empty():Optional.of(Stage.child(ticket,candidates,candidates.get(0).id())),cause);
    }
    private CancellationProbe probe(CancellationToken token){return probe(token,null);}
    private CancellationProbe probe(CancellationToken token,NativeEntry entry){
        var originalSource=seed==null?null:seed.receipt().generationGuard();
        return new CancellationProbe(){
        public boolean cancelled(){
            boolean stale=originalSourceStale;
            try{if(originalSource!=null)originalSource.getAsLong();}
            catch(IllegalStateException invalid){originalSourceStale=true;stale=true;}
            boolean cancelled=token.isCancelled();if(cancelled&&entry!=null)entry.cancellationObserved=true;
            return stale||cancelled||review.invalidated()||!ownsPublication();
        }
        public Registration onCancellation(Runnable callback){var registration=token.onCancellation(callback);return registration::close;}
    };}
    private void dispatchCompletion(Runnable completion) {
        try{publicationDispatcher.dispatch(completion);}catch(RuntimeException rejected){SwingUtilities.invokeLater(completion);}
    }
    private void settleWork(PreviewWorkGate.Work work){if(activeWork==work)activeWork=null;workGate.workerSettled(work);}
    private static RuntimeException unwrap(Throwable failure){while(failure instanceof CompletionException&&failure.getCause()!=null)failure=failure.getCause();return failure instanceof RuntimeException runtime?runtime:new IllegalStateException("Refinement failed",failure);}
    public void confirm() {
        review.requireOriginalAttemptCurrent();
        if(busy||closed||!ownsPublication()||!nativeSelected()||!hasCurrentPresentation()||!originalSourceCurrent())throw new IllegalStateException("The exact current preview is unavailable or stale");
        var plan=currentNative().orElseThrow().plan();
        confirmation=PreviewReviewState.fromEditPlan(selected().id().value(),plan).confirm();reviewedSelection=currentDiagnosticKey();diagnosticState="review-confirmed";present();
    }
    public boolean canApplyNative(){return !busy&&!closed&&ownsPublication()&&!review.invalidated()&&originalSourceCurrent()&&hasCurrentPresentation()&&session!=null&&session.canApply()&&currentNative().map(n->PreviewReviewState.fromEditPlan(selected().id().value(),n.plan()).canApply()||confirmation!=null&&confirmation.matches(PreviewReviewState.fromEditPlan(selected().id().value(),n.plan()))).orElse(false);}
    public Command command() {
        review.requireOriginalAttemptCurrent();
        if(!canApplyNative())throw new IllegalStateException("Refinement candidate requires review or is busy");
        var plan=currentNative().orElseThrow().plan();
        var validator=new LiveNetworkSnapshotValidator(root.receipt(),plan,seed.receipt().generationGuard());
        var command=new ApplyAlignmentEditPlanCommand(review.dataSet(),plan,validator,"Apply heatmap refinement");
        if(!session.terminalizeForApply())throw new IllegalStateException("The refinement session is no longer idle");
        return command;
    }
    private void cancelOperation(){if(activeEntry!=null)activeEntry.cancellationRequested=true;if(cancellation!=null)cancellation.cancel();if(active!=null)session.cancel(active);message="Refinement cancelled; waiting for its worker to settle.";present();}
    private void present() {
        if(closed||!ownsPublication())return;
        if(diagnosticOwner!=null&&!diagnosticSealed)DiagnosticsRegistry.invalidateRefinement(diagnosticOwner);
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
        if(diagnosticsAvailability==RefinementAttemptDiagnostics.Availability.UNAVAILABLE_BUDGET)quality="Operation diagnostics unavailable: memory budget reached.\n"+quality;
        if(exportUnavailable!=ExportUnavailable.NONE)quality=exportUnavailableMessage()+".\n"+quality;
        if(busy)quality="Refinement is running…";status.accept(quality);
        var refine=new RefinementControls.Availability(!busy&&review.managedReceipt()!=null,"Original managed acquisition is required.");
        controls.present(new RefinementControls.ViewState(stage,choices,selected,quality,RefinementControls.Strength.MEDIUM,busy,yes,
            new RefinementControls.Availability(!busy&&undo,"First preview stage"),new RefinementControls.Availability(!busy&&redo,"No later preview stage"),refine,refine,no));
    }
    private void releaseClosed(){latestDiagnostics=null;diagnosticsSlot=null;diagnosticsAvailability=RefinementAttemptDiagnostics.Availability.NONE;diagnosticMemory.closeWorkflow();legacyDiagnosticContext=null;legacyContextSupplier=null;ratingSupplier=null;pendingLegacyReview=null;pendingLegacyReviewMemory=null;retainedStages.clear();seed=null;root=null;session=null;confirmation=null;}
    @Override public void close(){if(closed)return;if(isCurrent()&&!diagnosticSealed){if(!diagnosticState.equals("apply-failed"))diagnosticsChanged("cancelled");freezeDiagnostics(true,false);}closed=true;workGate.release(this);if(cancellation!=null)cancellation.cancel();if(session!=null)session.close();if(!busy)releaseClosed();}
}
