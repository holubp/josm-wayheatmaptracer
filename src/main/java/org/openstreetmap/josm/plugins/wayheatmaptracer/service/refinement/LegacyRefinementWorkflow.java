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
    private final RefinementReviewRoot review;
    private final TileFetchCoordinator coordinator;
    private final VisibleRefinementCapture.Port visibleCapture;
    private org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext initialVisibleWork;
    private RefinementPipeline.OperationBudget initialVisibleBudget;
    private static VisibleRefinementCapture.Port unavailableVisiblePort(){return (request,work)->{throw new UnsupportedOperationException("visible-recapture-port-unavailable");};}
    private final Consumer<Display> display;
    private final Consumer<String> status;
    private final Consumer<CenterlineCandidate> rootSelection;
    private final Executor executor;
    private final AlignmentJob.EventDispatcher publicationDispatcher;
    /** Package-only deterministic test control: reductions and an extra checkpoint, never a replacement probe/context. */
    record SourcePreparationControl(long visits,long bytes,java.util.function.LongConsumer checkpoint) {
        SourcePreparationControl {if(visits<0||visits>8_000_000L||bytes<0||bytes>AttemptMemoryLedger.MAX_BYTES)throw new IllegalArgumentException("Source preparation control may only tighten production limits");Objects.requireNonNull(checkpoint);}
        static SourcePreparationControl production(){return new SourcePreparationControl(8_000_000L,AttemptMemoryLedger.MAX_BYTES,value->{});}
    }
    private final SourcePreparationControl sourcePreparationControl;
    private PreviewWorkGate workGate;
    private PreviewWorkGate.Work activeWork;
    private RefinementReviewLifecycle lifecycle;
    private Runnable commandContext=()->{};
    public void attachCommandContext(Runnable context) {
        if(!SwingUtilities.isEventDispatchThread()||busy||closed||session!=null)throw new IllegalStateException("Command context requires an idle original review");
        commandContext=Objects.requireNonNull(context);
    }
    private record CommandGeneration(java.util.function.LongSupplier source,Runnable context) implements java.util.function.LongSupplier {
        public long getAsLong(){context.run();return source.getAsLong();}
    }
    public void attachLifecycle(RefinementReviewLifecycle lifecycle) {
        if(!SwingUtilities.isEventDispatchThread()||busy||closed||!ownsPublication())throw new IllegalStateException("Review lifecycle requires the current idle owner");
        if(this.lifecycle!=null)throw new IllegalStateException("Review lifecycle is already attached");
        this.lifecycle=Objects.requireNonNull(lifecycle);
    }
    public void cancel(){cancelOperation();}
    private PreviewWorkGate.Work beginOwnedWork() {
        var work=workGate.beginWork(this);activeWork=work;
        try{if(lifecycle!=null)lifecycle.begin(this,work);return work;}
        catch(RuntimeException failed){activeWork=null;workGate.workerSettled(work);throw failed;}
    }
    private void completeLifecycle(PreviewWorkGate.Work work,boolean published) {
        if(lifecycle==null)return;
        try{lifecycle.complete(work,published,message);}catch(RuntimeException failed){message="The retained result could not be displayed.";}
    }
    /** Installs the actual retained Legacy context lazily; ordinary Legacy review does no export conversion. */
    public void enableRuntimeDiagnostics(java.util.function.Supplier<LastSlideDebugBundle> rootContext) {
        enableRuntimeDiagnostics(rootContext,Map::of);
    }
    public void enableRuntimeDiagnostics(java.util.function.Supplier<LastSlideDebugBundle> rootContext,java.util.function.Supplier<Map<String,CandidateRating>> ratings) {
        if(!SwingUtilities.isEventDispatchThread()||busy||session!=null||closed||!ownsPublication())throw new IllegalStateException("Diagnostic attachment requires original idle review");
        legacyContextSupplier=Objects.requireNonNull(rootContext);ratingSupplier=Objects.requireNonNull(ratings);initialDiagnosticChoice=review.choiceId(review.selectedCandidate());
    }
    public void enableNativeRuntimeDiagnostics(java.util.function.Function<Candidate,Format15Bundle> context,java.util.function.Function<Candidate,Format15Bundle> boundedFallback) {
        enableRuntimeDiagnostics(()->null);
        nativeContextSupplier=Objects.requireNonNull(context);nativeContextFallback=Objects.requireNonNull(boundedFallback);
    }
    private java.util.function.Supplier<Map<String,CandidateRating>> ratingSupplier;
    private java.util.function.Supplier<LastSlideDebugBundle> legacyContextSupplier;
    private LastSlideDebugBundle legacyDiagnosticContext;
    private java.util.function.Function<Candidate,Format15Bundle> nativeContextSupplier,nativeContextFallback;
    private Candidate originalNativeContextChoice;
    private Format15Bundle nativeDiagnosticContext;
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
        if(review instanceof ModernReviewRoot) {
            var initial=root.stage().candidates().stream().filter(c->c.id().value().equals(initialDiagnosticChoice)).findFirst();
            if(initial.isEmpty())initialDiagnosticChoice=root.stage().selected().value();
            originalNativeContextChoice=root.stage().candidates().stream().filter(c->c.id().value().equals(initialDiagnosticChoice)).findFirst().orElseThrow();
            legacyContextSupplier=null;diagnosticOwner=DiagnosticsRegistry.claimRefinement(this);return;
        }
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
            RefinementAttemptDiagnostics.Cause.CANCELLED,workerReturned?progress.materializationRefusals:0,workerReturned,workerReturned?progress.sourcePreparation:Optional.empty());
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
            if(nativeContextSupplier!=null&&nativeDiagnosticContext==null) {
                var retained=rootMemory.child("native-root-diagnostic-context");
                try {
                    long facts=0;for(long bytes:root.authority().charges().values())facts=Math.addExact(facts,bytes);
                    // Four times the admitted immutable graph bounds binary/UTF-8 conversion and copies.
                    boolean full=facts<=8L*1024*1024;
                    try(var conversion=rootMemory.reserve(32L*1024*1024)) {
                        var bundle=(full?nativeContextSupplier:nativeContextFallback).apply(originalNativeContextChoice);
                        long bytes=4096;for(var artifact:bundle.artifacts().values())bytes=Math.addExact(bytes,Math.addExact(256,artifact.bytes().length));
                        if(bytes>32L*1024*1024)throw new ExportBudgetRefused();
                        conversion.close();retained.reserve(bytes).adopt(bundle);nativeDiagnosticContext=bundle;
                        legacyContextAvailability=full?"retained-full-native-root-only":"retained-native-root-metadata-only";
                    }
                }catch(RuntimeException unavailable){retained.close();legacyContextAvailability=unavailable instanceof AttemptMemoryLedger.ResourceLimitException||unavailable instanceof ExportBudgetRefused?"unavailable-budget":"unavailable-construction";}
            }
            if(legacyDiagnosticContext!=null) {
                legacy=java.nio.file.Files.createTempFile("legacy-root-context-",".zip");legacyDiagnosticContext.writeTo(legacy.toFile());
                if(java.nio.file.Files.size(legacy)<=2L*1024*1024){context=Optional.of(Format15Artifact.binary("legacy-root-diagnostics.zip",java.nio.file.Files.readAllBytes(legacy)));legacyContextAvailability="available-root-only";}else legacyContextAvailability="unavailable-encoded-budget";
            }
            if(nativeDiagnosticContext!=null) {
                legacy=java.nio.file.Files.createTempFile("native-root-context-",".zip");Format15BundleWriter.write(nativeDiagnosticContext,legacy);
                if(java.nio.file.Files.size(legacy)<=2L*1024*1024)context=Optional.of(Format15Artifact.binary("native-root-diagnostics.zip",java.nio.file.Files.readAllBytes(legacy)));
                else legacyContextAvailability="unavailable-encoded-budget";
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
            var smoothing=latestSmoothingReceipt;var protocol=latestSmoothingProtocol;
            if(terminal&&busy&&activeProgress!=null&&activeEntry!=null&&activeEntry.workerReturned) {smoothing=activeProgress.smoothingReceipt;protocol=activeProgress.smoothingProtocol;}
            if(latest.isPresent()&&smoothing.isPresent()) {
                var artifact=RuntimeRefinementDiagnostics.smoothingAttemptArtifact(latest.orElseThrow(),smoothing.orElseThrow(),protocol);artifacts.put(artifact.name(),artifact);
            }
            if(appliedNow){var publication=Format15Artifact.text("host-command-publication.txt",appliedNotificationFailed?"committed-notification-failed\n":"committed\n");artifacts.put(publication.name(),publication);}
            var ratings=ratingSupplier==null?Map.<String,CandidateRating>of():ratingSupplier.get();
            if(ratings.size()>256)throw new ExportBudgetRefused();
            var calibration=Format15Artifact.text("candidate-ratings.json",LastSlideDebugBundle.redactedCandidateRatings(ratings));artifacts.put(calibration.name(),calibration);
            var contextStatus=Format15Artifact.text(review instanceof ModernReviewRoot?"native-root-context-status.txt":"legacy-root-context-status.txt",legacyContextAvailability+"\n");artifacts.put(contextStatus.name(),contextStatus);
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
    private record SelectionMetadata(Stage wrapper,AttemptMemoryLedger.Owner owner) { }
    private static final class RetainedStage {
        final AttemptMemoryLedger.Owner owner;final Stage metadata;SelectionMetadata selection;
        RetainedStage(AttemptMemoryLedger.Owner owner,Stage metadata){this.owner=owner;this.metadata=metadata;}
        void close(){if(selection!=null)selection.owner().close();owner.close();}
    }
    private final Map<StageId,RetainedStage> retainedStages=new HashMap<>();
    private final Map<CandidateId,AttemptMemoryLedger.Owner> rootCompositionOwners=new HashMap<>();
    private volatile RefinementAttemptDiagnostics latestDiagnostics;
    private Optional<RefinementSmoothingAdapter.Receipt> latestSmoothingReceipt=Optional.empty();
    private Optional<String> latestSmoothingProtocol=Optional.empty();
    private RefinementAttemptDiagnostics.Slot diagnosticsSlot;
    private volatile RefinementAttemptDiagnostics.Availability diagnosticsAvailability=RefinementAttemptDiagnostics.Availability.NONE;
    public Optional<RefinementAttemptDiagnostics> latestAttemptDiagnostics(){return closed||!ownsPublication()?Optional.empty():Optional.ofNullable(latestDiagnostics);}
    public RefinementAttemptDiagnostics.Availability attemptDiagnosticsAvailability(){return closed||!ownsPublication()?RefinementAttemptDiagnostics.Availability.NONE:diagnosticsAvailability;}
    private void clearDiagnostics(){latestDiagnostics=null;latestSmoothingReceipt=Optional.empty();latestSmoothingProtocol=Optional.empty();diagnosticSelection=null;if(diagnosticsSlot!=null)diagnosticsSlot.close();diagnosticsSlot=null;diagnosticsAvailability=RefinementAttemptDiagnostics.Availability.NONE;}
    static final class AttemptProgress {
        final RefinementAttemptDiagnostics.Binding binding;
        final RefinementAttemptDiagnostics.Slot slot;
        RefinementAttemptDiagnostics.Phase phase=RefinementAttemptDiagnostics.Phase.PREPARATION;
        Optional<RefinementOperationReceipt> receipt=Optional.empty();
        Optional<RefinementSmoothingAdapter.Receipt> smoothingReceipt=Optional.empty();
        Optional<String> smoothingProtocol=Optional.empty();
        int materializationRefusals;
        boolean presentationAttempted;
        Outcome pipelineOutcome;
        Optional<RefinementAttemptDiagnostics.SourcePreparationWork> sourcePreparation=Optional.empty();
        RefinementAttemptDiagnostics.Cause sourcePreparationCause=RefinementAttemptDiagnostics.Cause.NONE;
        Optional<RefinementSourcePlanner.SourceRequest> failedSource=Optional.empty();
        Optional<RefinementAttemptDiagnostics.NativeWork> nativeWork=Optional.empty();
        Optional<RefinementAttemptDiagnostics.SourceFacts> sourceFacts=Optional.empty();
        AttemptProgress(RefinementAttemptDiagnostics.Binding binding,RefinementAttemptDiagnostics.Slot slot){this.binding=binding;this.slot=slot;}
        RefinementAttemptDiagnostics snapshot(RefinementAttemptDiagnostics.Publication publication,RefinementAttemptDiagnostics.Cause cause){return new RefinementAttemptDiagnostics(binding,receipt,publication,phase,cause,materializationRefusals,true,sourcePreparation);}
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
            if(progress.slot.retain(snapshot,progress.smoothingReceipt,progress.smoothingProtocol)){latestDiagnostics=snapshot;latestSmoothingReceipt=progress.smoothingReceipt;latestSmoothingProtocol=progress.smoothingProtocol;diagnosticSelection=publication==RefinementAttemptDiagnostics.Publication.PUBLISHED?currentDiagnosticKey():new RefinementLineageCodec.CandidateKey(ticket.parent(),ticket.choice());diagnosticsAvailability=RefinementAttemptDiagnostics.Availability.AVAILABLE;return;}
        }catch(RuntimeException unavailable){ /* Diagnostic admission must never interrupt result/input settlement. */ }
        diagnosticsAvailability=RefinementAttemptDiagnostics.Availability.UNAVAILABLE_BUDGET;progress.slot.close();diagnosticsSlot=null;
    }

    private RefinementSession session;
    private LegacyRefinementBootstrap.Seed seed;
    private LegacyRefinementBootstrap.Root root;
    private PreparedRefinementRoot preparedRoot;
    private RefinementSourceContext sourceContext;
    private Runnable preparingSourceCurrent=()->{};
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
        try{
            if(sourceContext!=null)sourceContext.requireCurrent();else review.requireAuthorityCurrent();
        }
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
    public LegacyRefinementWorkflow(RefinementReviewRoot review,TileFetchCoordinator coordinator,
            Consumer<CenterlineCandidate> rootSelection,Consumer<Display> display,Consumer<String> status) {
        this(review,coordinator,rootSelection,display,status,ForkJoinPool.commonPool(),SwingUtilities::invokeLater);
    }
    /** Controlled worker/publication queues preserve the same production ownership path. */
    public LegacyRefinementWorkflow(RefinementReviewRoot review,TileFetchCoordinator coordinator,
            Consumer<CenterlineCandidate> rootSelection,Consumer<Display> display,Consumer<String> status,
            Executor executor,AlignmentJob.EventDispatcher publicationDispatcher) {
        this(review,coordinator,rootSelection,display,status,executor,publicationDispatcher,SourcePreparationControl.production());
    }
    LegacyRefinementWorkflow(RefinementReviewRoot review,TileFetchCoordinator coordinator,
            Consumer<CenterlineCandidate> rootSelection,Consumer<Display> display,Consumer<String> status,
            Executor executor,AlignmentJob.EventDispatcher publicationDispatcher,SourcePreparationControl sourcePreparationControl) {
        this(review,coordinator,rootSelection,display,status,executor,publicationDispatcher,sourcePreparationControl,unavailableVisiblePort());
    }
    public LegacyRefinementWorkflow(RefinementReviewRoot review,TileFetchCoordinator coordinator,
            Consumer<CenterlineCandidate> rootSelection,Consumer<Display> display,Consumer<String> status,VisibleRefinementCapture.Port visibleCapture) {
        this(review,coordinator,rootSelection,display,status,ForkJoinPool.commonPool(),SwingUtilities::invokeLater,SourcePreparationControl.production(),visibleCapture);
    }
    private LegacyRefinementWorkflow(RefinementReviewRoot review,TileFetchCoordinator coordinator,
            Consumer<CenterlineCandidate> rootSelection,Consumer<Display> display,Consumer<String> status,
            Executor executor,AlignmentJob.EventDispatcher publicationDispatcher,SourcePreparationControl sourcePreparationControl,VisibleRefinementCapture.Port visibleCapture) {
        this.visibleCapture=Objects.requireNonNull(visibleCapture);
        this.sourcePreparationControl=Objects.requireNonNull(sourcePreparationControl);
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
            public void strengthSelected(RefinementControls.Strength value){if(session!=null&&session.current().kind()==StageKind.SMOOTH)smooth(value);}
            public void smooth(RefinementControls.Strength value){LegacyRefinementWorkflow.this.smooth(session!=null&&session.current().kind()==StageKind.SMOOTH?value:RefinementControls.Strength.MEDIUM);}
        });present();
    }
    public void refresh(){present();}
    public String selectedChoiceIdentity(){return session==null?review.choiceId(review.selectedCandidate()):session.current().selected().value();}
    public boolean atRoot(){return session==null||session.current().kind().root();}
    public boolean currentSourceAvailable(){return !closed&&!review.invalidated()&&originalSourceCurrent();}
    /** Materializes one factual original interval composition, preserving a saved forward branch. */
    public void selectIntervalComposition(ModernReviewRoot.IntervalSelection selection) {
        if(!SwingUtilities.isEventDispatchThread()||busy||closed||!ownsPublication()||!(review instanceof ModernReviewRoot modern)||!modern.partitionedRoot()||!atRoot())
            throw new IllegalStateException("Interval selection requires the current idle native root");
        modern.requireAuthorityCurrent();
        if(session==null){modern.replaceUnpreparedInterval(selection);rootSelection.accept(modern.selectedCandidate());present();return;}
        String id=ModernReviewRoot.intervalChoiceId(selection);
        var cached=session.current().candidates().stream().filter(c->c.id().value().equals(id)).findFirst();
        if(cached.isPresent()) {publishRootComposition(modern,selection,cached.orElseThrow(),null);present();return;}
        var preparation=modern.preparationFor(selection);
        var operation=rootMemory.child("interval-root-preparation");
        var publication=rootMemory.child("interval-root-publication");
        cancellation=new CancellationToken();var probe=probe(cancellation);
        var budget=RefinementPipeline.OperationBudget.production(TraceBudgets.defaults());
        var workContext=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext(sourcePreparationControl.visits(),sourcePreparationControl.bytes(),budget.finalDeadlineNanos(),probe,operation);
        busy=true;message="";PreviewWorkGate.Work work;
        try{work=beginOwnedWork();present();}
        catch(RuntimeException failed){busy=false;operation.close();publication.close();preparation.close();throw failed;}
        CompletableFuture<PreparedRefinementRoot> computation;
        try{computation=CompletableFuture.supplyAsync(()->new ModernRefinementBootstrap().complete(preparation,workContext),executor);}
        catch(RuntimeException rejected){computation=CompletableFuture.failedFuture(rejected);}
        computation.whenComplete((prepared,failure)->dispatchCompletion(()->{
            boolean published=false;
            try {
                if(closed||!ownsPublication())return;
                if(failure!=null)throw unwrap(failure);probe.checkpoint();modern.requireAuthorityCurrent();
                var stage=prepared.root().stage();var candidate=stage.candidates().stream().filter(c->c.id().equals(stage.selected())).findFirst().orElseThrow();
                candidate.payload().retainSmoothingSources(publication);
                for(var charge:candidate.payload().publicationCharges().entrySet()) {
                    try{publication.retainIfAbsent(charge.getKey());}
                    catch(IllegalArgumentException absent){publication.reserve(charge.getValue()).adopt(charge.getKey());}
                }
                publishRootComposition(modern,selection,candidate,publication);published=true;message="";
            }catch(RuntimeException failed) {
                message=cancellation.isCancelled()?"Root selection cancelled; retained the previous composition.":"Root selection unavailable; retained the previous composition.";
                if(!closed)restoreCurrentPresentation();
            }finally {
                if(prepared!=null)prepared.close();preparation.close();operation.close();if(!published)publication.close();
                busy=false;settleWork(work);
                if(closed)releaseClosed();
                else try{present();}finally{completeLifecycle(work,published);}
            }
        }));
    }
    private void publishRootComposition(ModernReviewRoot modern,ModernReviewRoot.IntervalSelection selection,Candidate candidate,AttemptMemoryLedger.Owner owner) {
        var previous=session.current();boolean registered=previous.candidates().stream().noneMatch(c->c.id().equals(candidate.id()));
        var metadataOwner=rootMemory.child("root-selection-wrapper");
        try {
            var metadataReservation=metadataOwner.reserve(Math.addExact(256L,Math.multiplyExact(8L,previous.candidates().size()+(registered?1:0))));
            if(registered)modern.registerPreparedInterval(selection,candidate);
            session.selectRootComposition(candidate,()->{
                var candidates=new ArrayList<>(previous.candidates());if(registered)candidates.add(candidate);
                showStage(new Stage(previous.id(),previous.parent(),previous.parentChoice(),previous.kind(),previous.engine(),candidates,candidate.id(),previous.smoothingInput()));
                modern.requireAuthorityCurrent();
            });
            metadataReservation.adopt(session.current());
            var retained=retainedStages.get(previous.id());var old=retained.selection;
            retained.selection=new SelectionMetadata(session.current(),metadataOwner);if(old!=null)old.owner().close();
            confirmation=null;
            if(owner!=null)rootCompositionOwners.put(candidate.id(),owner);
            diagnosticState="preview-open";
        }catch(RuntimeException failed) {
            metadataOwner.close();
            if(registered)modern.unregisterPreparedInterval(candidate.id().value());
            restoreCurrentPresentation();throw failed;
        }
    }
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
            else{session.select(new CandidateId(id));if(nativeSelected())retainSelectionMetadata(session.current());showCurrent();}
        } catch(RuntimeException failure){message=failure.getMessage();}
        present();
    }
    private void navigate(boolean forward) {
        if(busy||session==null||closed||!ownsPublication())return;
        try{review.requireOriginalAttemptCurrent();confirmation=null;if(forward)session.redo();else session.back();showCurrent();}
        catch(RuntimeException failure){message=failure.getMessage();}present();
    }
    /** Selection changes only a bounded wrapper; the admitted candidate graph keeps its original identity. */
    private RetainedStage retainSelectionMetadata(Stage current) {
        var retained=retainedStages.get(current.id());if(retained==null)throw new IllegalStateException("Published stage metadata is unavailable");
        if(current==retained.metadata||retained.selection!=null&&retained.selection.wrapper()==current)return retained;
        var owner=rootMemory.child("selected-stage-wrapper");
        try {
            owner.reserve(Math.addExact(256L,Math.multiplyExact(8L,current.candidates().size()))).adopt(current);
            var previous=retained.selection;retained.selection=new SelectionMetadata(current,owner);if(previous!=null)previous.owner().close();return retained;
        }catch(RuntimeException|Error failure){owner.close();throw failure;}
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
            if(stage.kind()==StageKind.ROOT_NATIVE)review.select(review.candidateForChoice(stage.selected().value()));
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
        operate(engine,null);
    }
    public void smooth(RefinementControls.Strength strength) {
        operate(null,Objects.requireNonNull(strength));
    }
    private void operate(TrackerMode engine,RefinementControls.Strength smoothing) {
        if(busy||closed||!ownsPublication())return;
        AttemptProgress preparing=null;
        try {
            review.requireOriginalAttemptCurrent();confirmation=null;message="";
            if(session==null) {
                var work=beginOwnedWork();
                var bootstrapMemory=rootMemory.child("lazy-original-root");
                try {
                    if(review instanceof ModernReviewRoot modern) {
                        cancellation=new CancellationToken();initialVisibleBudget=RefinementPipeline.OperationBudget.production(TraceBudgets.defaults());
                        preparingSourceCurrent=modern.source()::requireCurrent;
                        initialVisibleWork=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext(sourcePreparationControl.visits(),sourcePreparationControl.bytes(),initialVisibleBudget.finalDeadlineNanos(),probe(cancellation),bootstrapMemory);
                    } else if(((LegacyReviewOwner)review).managedReceipt()==null) {
                        cancellation=new CancellationToken();initialVisibleBudget=RefinementPipeline.OperationBudget.production(TraceBudgets.defaults());
                        initialVisibleWork=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext(sourcePreparationControl.visits(),sourcePreparationControl.bytes(),initialVisibleBudget.finalDeadlineNanos(),probe(cancellation),bootstrapMemory);
                        seed=new LegacyRefinementBootstrap().capture((LegacyReviewOwner)review,coordinator,initialVisibleWork);
                        var original=seed.originalSource();preparingSourceCurrent=()->original.requireCurrent(coordinator);
                    } else {
                        seed=new LegacyRefinementBootstrap().capture((LegacyReviewOwner)review,coordinator,bootstrapMemory);
                        var original=seed.originalSource();preparingSourceCurrent=()->original.requireCurrent(coordinator);
                    }
                }
                catch(RuntimeException refused){bootstrapMemory.close();settleWork(work);completeLifecycle(work,false);throw refused;}
                busy=true;if(initialVisibleWork==null)cancellation=new CancellationToken();
                try{present();}catch(RuntimeException failed){busy=false;bootstrapMemory.close();settleWork(work);completeLifecycle(work,false);throw failed;}
                var captured=seed;var probe=probe(cancellation);
                var bootstrapWork=initialVisibleWork;
                CompletableFuture<PreparedRefinementRoot> bootstrap;
                try{bootstrap=CompletableFuture.supplyAsync(()->{
                    if(review instanceof ModernReviewRoot modern)return new ModernRefinementBootstrap().complete(modern,bootstrapWork);
                    var completed=captured.context()==null?new LegacyRefinementBootstrap().complete(captured,coordinator,probe):new LegacyRefinementBootstrap().complete(captured,coordinator,captured.context());
                    return new PreparedRefinementRoot(completed,captured.settings(),captured.original(),new LegacyRefinementSourceContext(captured,completed,coordinator,visibleCapture),captured.memory(),captured.payloadLimit());
                },executor);}catch(RuntimeException rejected){bootstrap=CompletableFuture.failedFuture(rejected);}
                bootstrap
                    .whenComplete((completed,failure)->dispatchCompletion(()->{
                        boolean continueRefinement=false;
                        try {
                            if(closed||!ownsPublication())return;
                            if(failure!=null)throw unwrap(failure);
                            probe.checkpoint();review.requireOriginalAttemptCurrent();
                            preparedRoot=completed;root=completed.root();sourceContext=completed.source();preparingSourceCurrent=sourceContext::requireCurrent;
                            session=new RefinementSession(root.stage(),review.originalConfig(),review.sourceIdentity(),root.authority(),RefinementLimits.defaults());
                            retainedStages.put(root.stage().id(),new RetainedStage(completed.memory(),root.stage()));
                            if(review instanceof LegacyReviewOwner)bindLegacyReview();
                            else showCurrent();
                            startDiagnostics();continueRefinement=true;
                        }catch(RuntimeException refused){bootstrapMemory.close();seed=null;message=cancellation!=null&&cancellation.isCancelled()?"Operation cancelled; retained the selected parent.":"Original result preparation failed; retained the selected parent.";}
                        finally {
                            busy=false;if(closed)releaseClosed();
                            settleWork(work);
                            if(!closed){if(continueRefinement){operate(engine,smoothing);if(!busy)completeLifecycle(work,false);}else{try{present();}finally{completeLifecycle(work,false);}}}
                        }
                    }));
                return;
            }
            sourceContext.requireCurrent();
            if(smoothing!=null){startSmoothing(smoothing);return;}
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
            var plan=RefinementSourcePlanner.plan(ticket,preparedRoot.settings(),parent);
            var sourcePayload=selected().payload();var original=review.originalResult();var credentials=CredentialSnapshot.fromConfig(review.originalConfig().heatmap());
            var operation=rootMemory.child("live-refinement-"+ticket.sequence());
            // Physical input co-ownership survives branch removal until queued worker/result settlement.
            if(nativeSelected()) {
                var current=session.current();var retained=retainSelectionMetadata(current);
                operation.retainIfAbsent(retained.metadata);
                if(current!=retained.metadata)operation.retainIfAbsent(current);
            }
            sourcePayload.retainSmoothingSources(operation);
            ArrayList<Map<Object,Long>> baseline;
            try(var snapshot=operation.reserve(session.admissionInventoryReferenceBytes())){baseline=session.admissionInventories();snapshot.adopt(baseline);}
            catch(RuntimeException refused){operation.close();throw refused;}
            var publication=rootMemory.child("publication-"+ticket.sequence());
            retainSelectionMetadata(session.current());
            var publishedParent=session.current();
            var inheritedWork=initialVisibleWork;var inheritedBudget=initialVisibleBudget;initialVisibleWork=null;initialVisibleBudget=null;
            var entry=new NativeEntry(ticket);activeEntry=entry;
            if(inheritedWork==null)cancellation=new CancellationToken();var probe=probe(cancellation,entry);busy=true;
            PreviewWorkGate.Work work;
            try{present();work=beginOwnedWork();if(lifecycle!=null)lifecycle.attachTicket(work,ticket);}
            catch(RuntimeException failed){busy=false;operation.close();publication.close();baseline.clear();throw failed;}
            CompletableFuture<Computed> computation;
            try {computation=CompletableFuture.supplyAsync(()->compute(ticket,parent,plan,credentials,operation,publication,sourcePayload,original,allowance,probe,entry,progress,baseline,publishedParent,inheritedWork,inheritedBudget),executor);}
            catch(RuntimeException rejected){computation=CompletableFuture.failedFuture(rejected);}
            computation.whenComplete((completed,failure)->{baseline.clear();entry.workerReturned=true;dispatchCompletion(()->{
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
                                    var metadata=new IdentityHashMap<Object,Long>();
                                    for(var candidate:child.candidates()) {
                                        candidate.payload().retainSmoothingSources(publication);
                                        candidate.payload().nativeReview().ifPresent(value->value.retainedSources().values().forEach(RetainedSmoothingSource::requireCurrent));
                                        candidate.payload().publicationCharges().forEach((identity,bytes)->metadata.merge(identity,bytes,Math::max));
                                    }
                                    long bytes=0;for(long value:metadata.values())bytes=Math.addExact(bytes,value);
                                    synchronized(memory) {
                                        completed.publicationAllowance().orElseThrow().close();
                                        publication.reserve(Math.addExact(bytes,64L)).adopt(child);
                                    }
                                    progress.presentationAttempted=true;showStage(child);
                                    probe.checkpoint();review.requireOriginalAttemptCurrent();
                                })) {
                                    retainedStages.put(child.id(),new RetainedStage(publication,child));
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
                    finally{
                        try{publishDiagnostics(ticket,progress,publicationOutcome,diagnosticCause);}finally{
                            operation.close();if(retainedStages.values().stream().noneMatch(value->value.owner==publication))publication.close();session.workerSettled(ticket);active=null;activeProgress=null;busy=false;if(closed)releaseClosed();activeEntry=null;settleWork(work);
                            try{if(!closed)present();}finally{completeLifecycle(work,publicationOutcome==RefinementAttemptDiagnostics.Publication.PUBLISHED);entry.settlement.complete(null);}
                        }
                    }
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
    private void startSmoothing(RefinementControls.Strength strength) {
        var ticket=session.beginSmoothing(strength==RefinementControls.Strength.STRONG?"Strong":"Medium");active=ticket;confirmation=null;
        var input=ticket.smoothingInput().orElseThrow();
        var parentStage=session.history().stream().filter(s->s.id().equals(input.unsmoothedParent())).findFirst().orElseThrow();
        var parent=parentStage.candidates().stream().filter(c->c.id().equals(input.unsmoothedChoice())).findFirst().orElseThrow();
        var source=parent.payload().nativeReview().flatMap(n->n.sources().stream().map(n.retainedSources()::get).filter(Objects::nonNull).findFirst()).orElse(root.originalConversion());
        if(source==null)throw new SmoothingParentBinder.Unavailable(SmoothingParentBinder.Reason.SELECTED_SOURCE_NOT_RETAINED);
        var reference=new ParentReferenceBinder().bind(root.authority(),parentStage.id(),parent);
        clearDiagnostics();long available=session.availablePayloadBytes();diagnosticsSlot=RefinementAttemptDiagnostics.Slot.open(rootMemory,available).orElse(null);
        diagnosticsAvailability=diagnosticsSlot==null?RefinementAttemptDiagnostics.Availability.UNAVAILABLE_BUDGET:RefinementAttemptDiagnostics.Availability.RUNNING;
        long allowance=available-(diagnosticsSlot==null?0:diagnosticsSlot.bytes());session.reserve(ticket,1,allowance);
        var progress=new AttemptProgress(new RefinementAttemptDiagnostics.Binding(ticket,session.history().get(0).id(),root.authority().sourceIdentity(),root.authority().network().canonicalHash(),RuntimeRefinementDiagnostics.frozenSettingsIdentity(root.authority()),root.authority().frozenGeometryIdentity(),reference.canonicalIdentity()),diagnosticsSlot);activeProgress=progress;
        var operation=rootMemory.child("smoothing-"+ticket.sequence());var publication=rootMemory.child("smoothing-publication-"+ticket.sequence());
        parent.payload().retainSmoothingSources(operation);source.retainInto(operation);
        var baseline=session.admissionInventories();
        cancellation=new CancellationToken();var entry=new NativeEntry(ticket);activeEntry=entry;var probe=probe(cancellation,entry);
        var budget=initialVisibleBudget!=null?initialVisibleBudget:RefinementPipeline.OperationBudget.production(root.authority().request().budgets());
        var inherited=initialVisibleWork;initialVisibleWork=null;initialVisibleBudget=null;
        var workContext=inherited!=null?inherited:new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext(sourcePreparationControl.visits(),sourcePreparationControl.bytes(),budget.finalDeadlineNanos(),probe,operation);
        busy=true;message="";PreviewWorkGate.Work work;
        try{present();work=beginOwnedWork();if(lifecycle!=null)lifecycle.attachTicket(work,ticket);}
        catch(RuntimeException failed){operation.close();publication.close();baseline.clear();busy=false;throw failed;}
        CompletableFuture<Computed> computation;
        try{computation=CompletableFuture.supplyAsync(()->computeSmoothing(ticket,parent,source,strength,workContext,probe,progress,publication,allowance,baseline),executor);}
        catch(RuntimeException rejected){computation=CompletableFuture.failedFuture(rejected);}
        computation.whenComplete((computed,failure)->{baseline.clear();entry.workerReturned=true;dispatchCompletion(()->{
            var outcome=RefinementAttemptDiagnostics.Publication.FAILED;var cause=RefinementAttemptDiagnostics.Cause.FAILED;
            try {
                if(closed||!ownsPublication()||!Objects.equals(active,ticket))return;
                if(failure!=null)throw unwrap(failure);probe.checkpoint();review.requireOriginalAttemptCurrent();source.requireCurrent();
                cause=computed.cause();
                if(computed.stage().isEmpty()){session.fail(ticket,cause==RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT?Outcome.RESOURCE_LIMIT:Outcome.NO_USABLE_RESULT);outcome=RefinementAttemptDiagnostics.Publication.NO_CHILD;message="Smoothen retained the selected parent: "+cause.name().toLowerCase(Locale.ROOT)+".";}
                else {
                    var child=computed.stage().orElseThrow();
                    if(session.publish(ticket,child,()->{
                        var metadata=new IdentityHashMap<Object,Long>();
                        for(var candidate:child.candidates()){candidate.payload().retainSmoothingSources(publication);metadata.putAll(candidate.payload().publicationCharges());}
                        long bytes=64;for(long charge:metadata.values())bytes=Math.addExact(bytes,charge);
                        synchronized(memory){computed.publicationAllowance().orElseThrow().close();publication.reserve(bytes).adopt(child);}
                        progress.presentationAttempted=true;showStage(child);probe.checkpoint();review.requireOriginalAttemptCurrent();
                    })) {
                        retainedStages.put(child.id(),new RetainedStage(publication,child));
                        var live=session.history().stream().map(Stage::id).collect(java.util.stream.Collectors.toSet());
                        retainedStages.entrySet().removeIf(item->{if(live.contains(item.getKey()))return false;item.getValue().close();return true;});
                        outcome=RefinementAttemptDiagnostics.Publication.PUBLISHED;cause=RefinementAttemptDiagnostics.Cause.NONE;message="";
                    } else {outcome=RefinementAttemptDiagnostics.Publication.REFUSED;cause=RefinementAttemptDiagnostics.Cause.PUBLICATION_REFUSED;restoreCurrentPresentation();}
                }
            } catch(RuntimeException failed) {
                computationEvidence+="; failed="+failed.getClass().getSimpleName()+"; phase="+progress.phase;
                for(var frame:failed.getStackTrace())if(frame.getClassName().startsWith("org.openstreetmap.josm.plugins.wayheatmaptracer.")) {
                    computationEvidence+="; boundary="+frame.getClassName().substring(frame.getClassName().lastIndexOf('.')+1)+"."+frame.getMethodName()+":"+frame.getLineNumber();break;
                }
                boolean stale=review.invalidated()||!originalSourceCurrent();
                boolean cancelled=entry.cancellationRequested&&!stale;
                if(!closed)session.fail(ticket,stale?Outcome.STALE:cancelled?Outcome.CANCELLED:Outcome.FAILED);
                if(progress.presentationAttempted)restoreCurrentPresentation();
                outcome=stale?RefinementAttemptDiagnostics.Publication.STALE:cancelled?RefinementAttemptDiagnostics.Publication.CANCELLED:RefinementAttemptDiagnostics.Publication.FAILED;
                cause=stale?RefinementAttemptDiagnostics.Cause.STALE:cancelled?RefinementAttemptDiagnostics.Cause.CANCELLED:RefinementAttemptDiagnostics.Cause.FAILED;
                message=stale?"Smoothing source changed.":cancelled?"Smoothen cancelled; retained the selected parent.":"Smoothen failed; retained the selected parent.";
            } finally {
                try{publishDiagnostics(ticket,progress,outcome,cause);}finally{
                    operation.close();if(retainedStages.values().stream().noneMatch(value->value.owner==publication))publication.close();
                    session.workerSettled(ticket);active=null;activeProgress=null;activeEntry=null;busy=false;
                    if(closed)releaseClosed();settleWork(work);
                    try{if(!closed)present();}finally{completeLifecycle(work,outcome==RefinementAttemptDiagnostics.Publication.PUBLISHED);entry.settlement.complete(null);}
                }
            }
        });});
    }
    private Computed computeSmoothing(OperationTicket ticket,Candidate parent,RetainedSmoothingSource source,RefinementControls.Strength strength,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext work,CancellationProbe probe,AttemptProgress progress,
            AttemptMemoryLedger.Owner publication,long allowance,List<Map<Object,Long>> baseline) {
        progress.phase=RefinementAttemptDiagnostics.Phase.PREPARATION;
        computationEvidence="smoothing-preparation";
        try {
            var bound=new SmoothingParentBinder().bind(root.authority(),ticket,parent,source,work);
            try(var result=new RefinementSmoothingAdapter().smooth(bound,strength==RefinementControls.Strength.STRONG?SmoothingModels.Preset.STRONG_2PX:SmoothingModels.Preset.MEDIUM_1PX,work,probe)) {
                progress.smoothingReceipt=Optional.of(result.receipt());progress.smoothingProtocol=Optional.of(source.inputHashProtocol());
                computationEvidence="smoothing-"+result.receipt().outcome()+":"+result.receipt().reason()+"; visits="+result.receipt().workVisits();
                probe.checkpoint();progress.phase=RefinementAttemptDiagnostics.Phase.MATERIALIZATION;
                if(result.plan().isEmpty()) {
                    var cause=result.receipt().outcome()==RefinementSmoothingAdapter.Outcome.RETAINED_RESOURCE?RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT:RefinementAttemptDiagnostics.Cause.PLAN_REFUSED;
                    progress.pipelineOutcome=Outcome.NO_USABLE_RESULT;return new Computed(Optional.empty(),cause);
                }
                var reserve=publication.reserve(allowance);
                try {
                    var assessed=result.assessment().orElseThrow();var edit=result.plan().orElseThrow();
                    var projection=Projections.getProjectionByCode(root.authority().projectionCode());
                    var points=result.points().stream().map(p->{var g=edit.metricFrame().toGeographic(p);return projection.latlon2eastNorth(new LatLon(g.latitudeDegrees(),g.longitudeDegrees()));}).toList();
                    var assignments=new LinkedHashMap<Long,org.openstreetmap.josm.data.coor.EastNorth>();
                    for(int i=0;i<points.size();i++)if(assessed.candidate().pointIds().get(i) instanceof FinalRoutePointId.ExistingWayNodeOccurrence id)assignments.put(id.nodeKey().id(),points.get(i));
                    String nativeId=assessed.candidate().id();var candidate=new CenterlineCandidate(nativeId,0,List.of(),List.of(),points,points,assignments,List.of(),0,null,null,null,List.of());
                    var detector=new DetectorAttempt(source.source().palette(),source.source().mapping(),ticket.engine(),edit.validation().disposition()==ValidationReport.Disposition.APPLICABLE?DetectorAttemptStatus.APPLICABLE:DetectorAttemptStatus.REVIEW_REQUIRED,List.of(nativeId),"smoothing-final","Guarded Gaussian output");
                    var alignment=new AlignmentResult(parent.payload().originalSelectionContext(),null,List.of(candidate),parent.payload().originalSourcePolyline(),points,List.of(),null,null,List.of(detector),List.of(candidate));
                    var nativeReview=new RefinementPayload.NativeReview(edit,assessed.quality(),Optional.empty(),List.of(source.source()),OptionalInt.empty(),Map.of(source.source(),source),List.of(),Optional.of(assessed.candidate().pointIds()),Optional.of(result.receipt()));
                    var id=new CandidateId("smoothing-"+ticket.sequence());String identity=edit.canonicalHash();
                    var admission=new MarginalPayloadAdmission(baseline,allowance,probe);
                    long header=RefinementSession.stageMetadataBytes(1)+RefinementSession.candidateMetadataBytes(id,identity,nativeId,source.source().palette(),assessed.candidate().correspondence().size());
                    var payload=RefinementPayload.captureNative(alignment,parent.payload(),nativeReview,admission,header);
                    var child=Stage.child(ticket,List.of(new Candidate(id,payload,assessed.candidate().correspondence(),identity,nativeId,source.source().palette())),id);
                    progress.pipelineOutcome=Outcome.COMPLETE;return new Computed(Optional.of(child),RefinementAttemptDiagnostics.Cause.NONE,Optional.of(reserve));
                }catch(RuntimeException|Error failure){reserve.close();throw failure;}
            }
        } catch(SmoothingParentBinder.Unavailable unavailable) {
            computationEvidence="smoothing-binding-"+unavailable.reason();throw unavailable;
        } finally {progress.sourcePreparation=Optional.of(new RefinementAttemptDiagnostics.SourcePreparationWork(RefinementAttemptDiagnostics.Cause.NONE,work.visits(),work.counters(),Optional.empty(),Optional.empty()));}
    }
    private Computed compute(OperationTicket ticket,ParentTraceReference parent,RefinementSourcePlanner.Plan plan,
            CredentialSnapshot credentials,AttemptMemoryLedger.Owner operation,AttemptMemoryLedger.Owner publication,RefinementPayload sourcePayload,
            AlignmentResult original,long allowance,CancellationProbe probe,NativeEntry entry,AttemptProgress progress,List<Map<Object,Long>> baseline,Stage publishedParent,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext inheritedWork,RefinementPipeline.OperationBudget inheritedBudget) {
        progress.phase=RefinementAttemptDiagnostics.Phase.ACQUISITION;
        var budget=inheritedBudget!=null?inheritedBudget:RefinementPipeline.OperationBudget.production(parent.root().request().budgets());
        var workRef=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext[1];
        CancellationProbe hashProbe=()->{probe.checkpoint();try{sourcePreparationControl.checkpoint().accept(workRef[0].visits());}finally{probe.checkpoint();}return false;};
        var hashWork=inheritedWork!=null?inheritedWork:new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext(sourcePreparationControl.visits(),sourcePreparationControl.bytes(),budget.finalDeadlineNanos(),hashProbe,operation);workRef[0]=hashWork;
        try {
        var sources=sourceContext.open(plan,publishedParent,hashWork,operation,probe);
        var evidence=new HashMap<RefinementSourcePlanner.SourceRequest,EvidenceSnapshot>();
        var wrapped=new RefinementPipeline.Sources(){
            public void requireCurrent(){probe.checkpoint();sources.requireCurrent();}
            public EvidenceSnapshot evidence(RefinementSourcePlanner.SourceRequest source,CancellationProbe cancellation){
                progress.phase=RefinementAttemptDiagnostics.Phase.SOURCE_PREPARATION;
                try{var actual=sources.evidence(source,cancellation);evidence.put(source,actual);progress.phase=RefinementAttemptDiagnostics.Phase.PIPELINE;return actual;}
                catch(RefinementPipeline.SourceUnavailable unavailable){progress.phase=RefinementAttemptDiagnostics.Phase.PIPELINE;throw unavailable;}
                catch(RuntimeException failure){var outcome=RefinementPipeline.sourcePreparationFailure(failure,sources,probe);progress.sourcePreparationCause=sourcePreparationCause(outcome);progress.failedSource=Optional.of(source);throw new RefinementPipeline.SourcePreparationFailure(outcome,failure);}
            }
            public Optional<RefinementSourcePlanner.Plan> binding(){return sources.binding();}
            public Optional<ManagedModernPreviewSource.RefinementWorkReceipt> actualSourceWork(){return sources.actualSourceWork();}
            public Optional<RetainedSmoothingSource> retentionDescriptor(RefinementSourcePlanner.SourceRequest source,EvidenceSnapshot actual){return sources.retentionDescriptor(source,actual);}
        };
        progress.phase=RefinementAttemptDiagnostics.Phase.PIPELINE;
        var outcome=new RefinementPipeline().run(ticket,parent,plan,new RefinementPipeline.Adapters(wrapped,RefinementPipeline.productionRunner(actual->{
                if(!actual.equals(ticket))throw new IllegalStateException("Native entry ticket changed");
                if(entry.entered.compareAndSet(false,true))SwingUtilities.invokeLater(()->firstNativeEntry.complete(entry));
            }),operation),
            budget,probe);
        progress.pipelineOutcome=outcome.outcome();progress.nativeWork=Optional.of(RefinementAttemptDiagnostics.NativeWork.capture(outcome));
        progress.receipt=progress.slot==null?Optional.empty():progress.slot.receipt(progress.binding,parent,outcome);
        actualNativeCalls+=outcome.attempts().stream().flatMap(a->a.run().stream()).mapToInt(RefinementEngineAdapter.Run::nativeCalls).sum();
        computationEvidence="outcome="+outcome.outcome()+" full="+outcome.candidates().size()+" rejected="+outcome.rejected().size()+" attempts="+outcome.attempts().stream().map(a->a.kind()+":"+a.reason()+":"+a.nativeCallAvailability()).toList()
            +" rejectedFindings="+outcome.rejected().stream().map(a->a.quality().findings().stream().map(f->f.code().name()).toList()).toList();
        var computed=materialize(ticket,parent,outcome,evidence,sourcePayload,original,allowance,probe,progress,publication,baseline);
        computationEvidence+=" plan-refusals="+progress.materializationRefusals+" auxiliary-source-hash-visits="+hashWork.visits()+" auxiliary-source-hash-counts="+hashWork.counters();return computed;
        } finally {progress.sourceFacts=sourceContext.sourceFacts();progress.sourcePreparation=Optional.of(new RefinementAttemptDiagnostics.SourcePreparationWork(progress.sourcePreparationCause,hashWork.visits(),hashWork.counters(),progress.failedSource,progress.nativeWork,progress.sourceFacts));}
    }
    /** Claims the unchanged future Stage bound only once actual assessed output needs materialization. */
    static Computed materialize(OperationTicket ticket,ParentTraceReference parent,RefinementPipeline.Result outcome,
            Map<RefinementSourcePlanner.SourceRequest,EvidenceSnapshot> evidence,RefinementPayload sourcePayload,
            AlignmentResult original,long allowance,CancellationProbe probe,AttemptProgress progress,AttemptMemoryLedger.Owner publication) {
        return materialize(ticket,parent,outcome,evidence,sourcePayload,original,allowance,probe,progress,publication,List.of(parent.root().charges(),sourcePayload.charges()));
    }
    static Computed materialize(OperationTicket ticket,ParentTraceReference parent,RefinementPipeline.Result outcome,
            Map<RefinementSourcePlanner.SourceRequest,EvidenceSnapshot> evidence,RefinementPayload sourcePayload,
            AlignmentResult original,long allowance,CancellationProbe probe,AttemptProgress progress,AttemptMemoryLedger.Owner publication,List<Map<Object,Long>> baseline) {
        if(outcome.candidates().isEmpty())return materialize(ticket,parent,outcome,evidence,sourcePayload,original,allowance,probe,progress,baseline);
        progress.phase=RefinementAttemptDiagnostics.Phase.MATERIALIZATION;
        probe.checkpoint();
        // Input, native result, assembly and assessment owners are independent. This reservation
        // covers the new Stage payload from its first allocation through queued publication.
        var reservation=publication.reserve(allowance);
        var computed=materialize(ticket,parent,outcome,evidence,sourcePayload,original,allowance,probe,progress,baseline);
        return new Computed(computed.stage(),computed.cause(),Optional.of(reservation));
    }
    private static RefinementAttemptDiagnostics.Cause sourcePreparationCause(Outcome outcome){return switch(outcome){case RESOURCE_LIMIT->RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT;case CANCELLED->RefinementAttemptDiagnostics.Cause.CANCELLED;case STALE->RefinementAttemptDiagnostics.Cause.STALE;default->RefinementAttemptDiagnostics.Cause.FAILED;};}
    /** Same assessed full-composition materialization used by the live worker, including typed plan refusal. */
    static Computed materialize(OperationTicket ticket,ParentTraceReference parent,RefinementPipeline.Result outcome,
            Map<RefinementSourcePlanner.SourceRequest,EvidenceSnapshot> evidence,RefinementPayload sourcePayload,
            AlignmentResult original,long allowance,CancellationProbe probe,AttemptProgress progress) {
        return materialize(ticket,parent,outcome,evidence,sourcePayload,original,allowance,probe,progress,List.of(parent.root().charges(),sourcePayload.charges()));
    }
    static Computed materialize(OperationTicket ticket,ParentTraceReference parent,RefinementPipeline.Result outcome,
            Map<RefinementSourcePlanner.SourceRequest,EvidenceSnapshot> evidence,RefinementPayload sourcePayload,
            AlignmentResult original,long allowance,CancellationProbe probe,AttemptProgress progress,List<Map<Object,Long>> baseline) {
        var operationReceipt=progress.receipt;var admission=new MarginalPayloadAdmission(baseline,allowance,probe);
        if(!outcome.candidates().isEmpty())progress.phase=RefinementAttemptDiagnostics.Phase.MATERIALIZATION;
        var candidates=new ArrayList<Candidate>();int index=0;
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
                var detectors=retained.sources().stream().map(alias->new DetectorAttempt(alias.palette(),alias.mapping(),ticket.engine(),edit.validation().disposition()==ValidationReport.Disposition.APPLICABLE?DetectorAttemptStatus.APPLICABLE:DetectorAttemptStatus.REVIEW_REQUIRED,List.of(nativeId),"refinement-final","Actual full parent-backed composition")).toList();
                var result=new AlignmentResult(original.selection(),null,List.of(candidate),original.sourcePolyline(),points,List.of(),null,null,detectors,List.of(candidate));
                var nativeReview=new RefinementPayload.NativeReview(edit,assessed.quality(),operationReceipt,retained.sources(),
                    OptionalInt.of((int)assessed.candidate().spans().stream().filter(ParentBackedComposer.Span::parentRetained).count()),retained.retainedSources(),retained.sourceSpans(),Optional.of(assessed.candidate().pointIds()));
                var choiceId=new CandidateId(id);String immutableIdentity=edit.canonicalHash();
                long header=(candidates.isEmpty()?RefinementSession.stageMetadataBytes(1):8L)+RefinementSession.candidateMetadataBytes(choiceId,immutableIdentity,nativeId,source.palette(),assessed.candidate().correspondence().size());
                var payload=RefinementPayload.captureNative(result,sourcePayload,nativeReview,admission,header);
                candidates.add(new Candidate(choiceId,payload,assessed.candidate().correspondence(),immutableIdentity,nativeId,source.palette()));
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
        var sourceCurrent=preparingSourceCurrent;
        return new CancellationProbe(){
        public boolean cancelled(){
            boolean stale=originalSourceStale;
            try{sourceCurrent.run();}
            catch(IllegalStateException invalid){originalSourceStale=true;stale=true;}
            boolean cancelled=token.isCancelled();if(cancelled&&entry!=null)entry.cancellationObserved=true;
            return stale||cancelled||review.invalidated()||!ownsPublication();
        }
        public Registration onCancellation(Runnable callback){var registration=token.onCancellation(callback);return registration::close;}
    };}
    private void dispatchCompletion(Runnable completion) {
        var delivered=new java.util.concurrent.atomic.AtomicBoolean();
        Runnable once=()->{if(delivered.compareAndSet(false,true))completion.run();};
        try{publicationDispatcher.dispatch(once);}catch(RuntimeException rejected){SwingUtilities.invokeLater(once);}
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
        var sourceGuard=new CommandGeneration(sourceContext.detachedCommandGeneration(),commandContext);
        var validator=new LiveNetworkSnapshotValidator(root.receipt(),plan,sourceGuard);
        var command=new ApplyAlignmentEditPlanCommand(review.dataSet(),plan,validator,"Apply heatmap refinement");
        if(!session.terminalizeForApply())throw new IllegalStateException("The refinement session is no longer idle");
        return command;
    }
    private void cancelOperation(){if(activeEntry!=null)activeEntry.cancellationRequested=true;if(cancellation!=null)cancellation.cancel();if(active!=null)session.cancel(active);message="Refinement cancelled; waiting for its worker to settle.";present();}
    private void present() {
        if(closed||!ownsPublication())return;
        if(diagnosticOwner!=null&&!diagnosticSealed)DiagnosticsRegistry.invalidateRefinement(diagnosticOwner);
        var yes=new RefinementControls.Availability(!busy,"");
        List<RefinementControls.Choice> choices;String selected;String stage;boolean undo=false,redo=false;
        if(session==null){choices=review.choices().stream().map(c->new RefinementControls.Choice(c.id(),review.engine()+" / "+c.candidate().displayName())).toList();selected=review.choiceId(review.selectedCandidate());stage=review.engine()+" — stage 1";}
        else{var current=session.current();String label=current.kind()==StageKind.SMOOTH?"Smoothen ("+current.smoothingInput().orElseThrow().preset()+")":current.engine().toString();choices=current.candidates().stream().map(c->new RefinementControls.Choice(c.id().value(),label+" / "+c.sourcePalette())).toList();selected=current.selected().value();int cursor=session.history().indexOf(current);undo=cursor>0;redo=cursor+1<session.history().size();stage=label+" — stage "+(cursor+1);}
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
        boolean capable=review instanceof ModernReviewRoot||((LegacyReviewOwner)review).managedReceipt()!=null||visibleCapable();
        var refine=new RefinementControls.Availability(!busy&&capable&&originalSourceCurrent(),"Original visible Color Mapping and factual source frame are required.");
        var strength=session!=null&&session.current().smoothingInput().map(s->s.preset().equals("Strong")).orElse(false)?RefinementControls.Strength.STRONG:RefinementControls.Strength.MEDIUM;
        var smooth=new RefinementControls.Availability(!busy&&capable&&originalSourceCurrent(),"Original selected evidence is required for smoothing.");
        controls.present(new RefinementControls.ViewState(stage,choices,selected,quality,strength,busy,yes,
            new RefinementControls.Availability(!busy&&undo,"First preview stage"),new RefinementControls.Availability(!busy&&redo,"No later preview stage"),refine,refine,smooth));
    }
    /** Metadata-only capability; refreshing controls never copies or hashes pixels. */
    private boolean visibleCapable(){
        if(!(review instanceof LegacyReviewOwner legacy))return true;
        var metadata=legacy.originalVisibleSource();var intensity=review.originalConfig().heatmap().intensitySamplingMode();
        return metadata!=null&&metadata.sourceReceipt()!=null&&"EPSG:3857".equals(metadata.projectionCode())
            &&metadata.decisionRadiusMeters().isPresent()&&metadata.sampleStepMeters().isPresent()
            &&metadata.paintedFrame().filter(LegacyVisibleSource.PaintedFrame::commonResolution).isPresent()
            &&review.originalResult().capturedHeatmap()!=null&&(intensity==null||intensity.usesColorMapping())&&!review.originalConfig().heatmap().aggregateAllColorSchemes();
    }
    private void releaseClosed(){latestDiagnostics=null;latestSmoothingReceipt=Optional.empty();latestSmoothingProtocol=Optional.empty();diagnosticsSlot=null;diagnosticsAvailability=RefinementAttemptDiagnostics.Availability.NONE;rootCompositionOwners.values().forEach(AttemptMemoryLedger.Owner::close);rootCompositionOwners.clear();diagnosticMemory.closeWorkflow();legacyDiagnosticContext=null;legacyContextSupplier=null;nativeDiagnosticContext=null;nativeContextSupplier=null;nativeContextFallback=null;originalNativeContextChoice=null;ratingSupplier=null;pendingLegacyReview=null;pendingLegacyReviewMemory=null;retainedStages.clear();seed=null;root=null;preparedRoot=null;sourceContext=null;preparingSourceCurrent=()->{};session=null;confirmation=null;}
    @Override public void close(){if(closed)return;if(isCurrent()&&!diagnosticSealed){if(!diagnosticState.equals("apply-failed"))diagnosticsChanged("cancelled");freezeDiagnostics(true,false);}closed=true;if(lifecycle!=null)lifecycle.close();workGate.release(this);if(cancellation!=null)cancellation.cancel();if(session!=null)session.close();if(!busy)releaseClosed();}
}
