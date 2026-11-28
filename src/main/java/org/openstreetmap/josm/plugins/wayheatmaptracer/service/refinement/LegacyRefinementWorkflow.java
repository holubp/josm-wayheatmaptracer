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
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;
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
    public record Display(AlignmentResult result,CenterlineCandidate candidate,Optional<RefinementPayload.NativeReview> nativeReview,Optional<RefinementPayload.NoChangeReview> noChange) {
        public Display(AlignmentResult result,CenterlineCandidate candidate,Optional<RefinementPayload.NativeReview> nativeReview){this(result,candidate,nativeReview,Optional.empty());}
    }
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
    private static final ThreadLocal<SourcePreparationControl> PREPARATION_TIGHTENING=new ThreadLocal<>();
    /** EDT-owned public-fixture scope; it only tightens both preparation phases and never changes preferences. */
    public static AutoCloseable tightenPreparationForTesting(long visits) {
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Preparation test scope requires EDT");
        if(PREPARATION_TIGHTENING.get()!=null)throw new IllegalStateException("Preparation test scope is already active");
        var tightened=new SourcePreparationControl(visits,AttemptMemoryLedger.MAX_BYTES,value->{});
        PREPARATION_TIGHTENING.set(tightened);
        return new AutoCloseable(){private boolean closed;public void close(){
            if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Preparation test scope must close on EDT");
            if(closed)return;
            if(PREPARATION_TIGHTENING.get()!=tightened)throw new IllegalStateException("Preparation test scope owner changed");
            PREPARATION_TIGHTENING.remove();closed=true;
        }};
    }
    /** Package-only deterministic test control: reductions and an extra checkpoint, never a replacement probe/context. */
    record SourcePreparationControl(long visits,long refinementVisits,long bytes,java.util.function.LongConsumer checkpoint) {
        SourcePreparationControl(long visits,long bytes,java.util.function.LongConsumer checkpoint){this(tighteningVisits(visits),visits,bytes,checkpoint);}
        private static long tighteningVisits(long visits){if(visits>8_000_000L)throw new IllegalArgumentException("Test tightening cannot exceed8M");return visits;}
        SourcePreparationControl {if(visits<0||visits>64_000_000L||refinementVisits<visits||refinementVisits>64_000_000L||bytes<0||bytes>AttemptMemoryLedger.MAX_BYTES)throw new IllegalArgumentException("Source preparation control may only tighten production phase limits");Objects.requireNonNull(checkpoint);}
        static SourcePreparationControl production(){var tightened=PREPARATION_TIGHTENING.get();return tightened!=null?tightened:new SourcePreparationControl(64_000_000L,64_000_000L,AttemptMemoryLedger.MAX_BYTES,value->{});}
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
    private record CommandGeneration(java.util.function.LongSupplier source,Runnable context) implements org.openstreetmap.josm.plugins.wayheatmaptracer.util.PreparedCommandSource {
        public long getAsLong(){context.run();return source.getAsLong();}
        public Runnable prepareExecution(boolean redo){context.run();if(source instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.util.PreparedCommandSource prepared){var receipt=prepared.prepareExecution(redo);return ()->{context.run();receipt.run();};}getAsLong();return ()->getAsLong();}
        public void withSource(Runnable transaction){context.run();if(source instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.util.PreparedCommandSource prepared)prepared.withSource(()->{context.run();transaction.run();});else{getAsLong();transaction.run();}}
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
        enableNativeRuntimeDiagnostics(context,boundedFallback,()->null);
    }
    /** Original completed work remains factual even when optional root preparation never succeeds. */
    public void enableNativeRuntimeDiagnostics(java.util.function.Function<Candidate,Format15Bundle> context,java.util.function.Function<Candidate,Format15Bundle> boundedFallback,java.util.function.Supplier<Format15Artifact> originalWork) {
        enableRuntimeDiagnostics(()->null);
        nativeContextSupplier=Objects.requireNonNull(context);nativeContextFallback=Objects.requireNonNull(boundedFallback);
        nativeOriginalWorkSupplier=Objects.requireNonNull(originalWork);
    }
    private java.util.function.Supplier<Map<String,CandidateRating>> ratingSupplier;
    private java.util.function.Supplier<LastSlideDebugBundle> legacyContextSupplier;
    private LastSlideDebugBundle legacyDiagnosticContext;
    private java.util.function.Function<Candidate,Format15Bundle> nativeContextSupplier,nativeContextFallback;
    private java.util.function.Supplier<Format15Artifact> nativeOriginalWorkSupplier;
    private Candidate originalNativeContextChoice;
    private Format15Bundle nativeDiagnosticContext;
    private AttemptMemoryLedger.Owner nativeDiagnosticContextMemory;
    private DiagnosticsRegistry.RefinementOwner diagnosticOwner;
    private String initialDiagnosticChoice,diagnosticState="preview-open";
    private RefinementLineageCodec.CandidateKey diagnosticSelection,reviewedSelection;
    private AttemptProgress activeProgress;
    private enum ExportUnavailable { NONE, BUDGET, BINDING, ENCODING }
    private ExportUnavailable exportUnavailable=ExportUnavailable.NONE;
    private String legacyContextAvailability="unavailable-not-retained";
    private boolean preparingExport,diagnosticSealed;
    record BootstrapConsumerFrame(String className,String methodName,int line) { }
    record BootstrapFailureTrace(List<BootstrapConsumerFrame> consumers,boolean unavailable,boolean truncated) { }
    private static final String PLUGIN_STACK_PREFIX=RefinementAttemptDiagnostics.FailureFacts.PLUGIN_PREFIX;
    private static final long BOOTSTRAP_DIAGNOSTIC_BYTES=4096;
    private static boolean stackIdentifier(String value,int maximum,boolean qualified) {
        if(value==null||value.isEmpty()||value.length()>maximum)return false;
        boolean first=true;
        for(int index=0;index<value.length();index++) {
            char c=value.charAt(index);
            if(qualified&&c=='.'){if(first)return false;first=true;continue;}
            if(!(c>='A'&&c<='Z'||c>='a'&&c<='z'||c=='_'||c=='$'||!first&&c>='0'&&c<='9'))return false;
            first=false;
        }
        return !first;
    }
    /** One bounded view of the already-created exception; never retains a Throwable or file name. */
    static BootstrapFailureTrace bootstrapFailureTrace(StackTraceElement[] stack,boolean firstLimit) {
        var consumers=new ArrayList<BootstrapConsumerFrame>(4);boolean truncated=firstLimit&&stack.length>64;
        // Includes the fixed records, UUID and one bounded failure frame with typed optional facts.
        long retainedBytes=1504;
        for(int index=0;index<Math.min(64,stack.length);index++) {
            var frame=stack[index];if(frame==null)continue;
            String type=frame.getClassName(),method=frame.getMethodName();
            if(!type.startsWith(PLUGIN_STACK_PREFIX)||!stackIdentifier(type,192,true)||!stackIdentifier(method,96,false))continue;
            if(!firstLimit||method.startsWith("lambda$"))continue;
            if(type.equals(PLUGIN_STACK_PREFIX+"service.quality.GeometryWorkContext")
                    &&(method.equals("debit")||method.equals("visit")||method.equals("reserve")||method.equals("reserveOn")))continue;
            if(type.equals(PLUGIN_STACK_PREFIX+"service.quality.PostprocessingValidationWork")
                    &&(method.equals("visit")||method.equals("charge")||method.equals("comparator")||method.equals("naturalOrder")))continue;
            long bytes=320L+2L*(type.length()+method.length()); // Conservative frame/two-string headers and UTF16 payloads.
            if(consumers.size()<4&&bytes<=BOOTSTRAP_DIAGNOSTIC_BYTES-retainedBytes){consumers.add(new BootstrapConsumerFrame(type,method,frame.getLineNumber()));retainedBytes+=bytes;}
            else truncated=true;
        }
        return new BootstrapFailureTrace(List.copyOf(consumers),consumers.isEmpty(),truncated);
    }
    private record BootstrapDiagnostic(UUID attempt,String sourceHash,TrackerMode requestedEngine,String operation,String strength,String cause,OptionalLong workVisits,OptionalLong maximumVisits,
            List<BootstrapConsumerFrame> consumers,boolean consumerFramesUnavailable,boolean consumerFramesTruncated,
            boolean limitStackObserved,Optional<RefinementAttemptDiagnostics.FailureFacts> failure) { }
    private BootstrapDiagnostic bootstrapDiagnostic;
    private AttemptMemoryLedger.Owner bootstrapDiagnosticMemory;
    private void beginBootstrapDiagnostics(TrackerMode engine,RefinementControls.Strength strength) {
        if(legacyContextSupplier==null&&nativeContextSupplier==null)return;
        if(bootstrapDiagnosticMemory!=null)bootstrapDiagnosticMemory.close();
        bootstrapDiagnostic=new BootstrapDiagnostic(UUID.randomUUID(),bootstrapSourceHash(review.sourceIdentity()),engine,strength==null?"REFINE":"SMOOTH",strength==null?"unavailable":strength.name(),"RUNNING",OptionalLong.empty(),OptionalLong.empty(),List.of(),true,false,false,Optional.empty());
        bootstrapDiagnosticMemory=rootMemory.child("bootstrap-diagnostic-slot");
        try{bootstrapDiagnosticMemory.reserve(BOOTSTRAP_DIAGNOSTIC_BYTES).adopt(bootstrapDiagnostic);}catch(AttemptMemoryLedger.ResourceLimitException limited){bootstrapDiagnosticMemory.close();bootstrapDiagnosticMemory=null;}
        if(!DiagnosticsRegistry.ownsRefinement(diagnosticOwner))diagnosticOwner=DiagnosticsRegistry.claimRefinement(this);
        DiagnosticsRegistry.invalidateRefinement(diagnosticOwner);
    }
    private void bootstrapFailed(RuntimeException failure,boolean workerReturned) {
        var failedWork=initialVisibleWork;
        initialVisibleWork=null;initialVisibleBudget=null;
        if(closed||!ownsPublication())return;
        boolean firstLimit=failure instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.LimitReached
            &&(bootstrapDiagnostic==null||!bootstrapDiagnostic.limitStackObserved());
        var trace=bootstrapFailureTrace(failure.getStackTrace(),firstLimit);
        var facts=RefinementAttemptDiagnostics.FailureFacts.capture(failure);
        computationEvidence="bootstrap; failed="+facts.kind().name()+"; phase=PREPARATION";
        if(!facts.firstPluginFrame().isEmpty())computationEvidence+="; boundary="+facts.firstPluginFrame();
        if(bootstrapDiagnostic==null)return;
        String cause=cancellation!=null&&cancellation.isCancelled()?"CANCELLED":review.invalidated()?"STALE":
            failure instanceof java.util.concurrent.RejectedExecutionException?"EXECUTOR_REJECTED":
            facts.resourceLimited()?"RESOURCE_LIMIT":"FAILED";
        var visits=workerReturned&&failedWork!=null?OptionalLong.of(failedWork.visits()):OptionalLong.empty();
        var old=bootstrapDiagnostic;
        // Four bounded ASCII class/method pairs stay inside the existing4096-byte diagnostic slot.
        bootstrapDiagnostic=new BootstrapDiagnostic(old.attempt(),old.sourceHash(),old.requestedEngine(),old.operation(),old.strength(),cause,visits,failedWork==null?OptionalLong.empty():OptionalLong.of(failedWork.maximumVisits()),
            old.limitStackObserved()?old.consumers():trace.consumers(),old.limitStackObserved()?old.consumerFramesUnavailable():trace.unavailable(),
            old.limitStackObserved()?old.consumerFramesTruncated():trace.truncated(),old.limitStackObserved()||firstLimit,Optional.of(facts));
        if(diagnosticOwner!=null)DiagnosticsRegistry.invalidateRefinement(diagnosticOwner);
    }
    private String bootstrapFailureMessage(RuntimeException failure) {
        if(cancellation!=null&&cancellation.isCancelled())return "Operation cancelled. The selected parent is still available.";
        if(failure instanceof RefinementSession.Refused refused&&refused.reason()==RefusalReason.PAYLOAD_LIMIT)
            return "The original preview exceeds the refinement history limit. The selected parent is still available. Run alignment with a smaller selection, then retry.";
        return "Original result preparation failed. The selected parent is still available. Use Export Last Alignment Diagnostics for details.";
    }
    private Format15Bundle bootstrapBundle(boolean terminal) {
        var attempt=Objects.requireNonNull(bootstrapDiagnostic);
        var context=initialVisibleWork;
        var maximum=attempt.maximumVisits().isPresent()?attempt.maximumVisits():context==null?OptionalLong.empty():OptionalLong.of(context.maximumVisits());
        if(bootstrapDiagnosticMemory==null)throw new ExportBudgetRefused();
        var original=review.originalResult().sourcePolyline();if(original.size()>2048)throw new ExportBudgetRefused();
        var projection=Projections.getProjectionByCode(review.projectionCode());
        var csv=new StringBuilder("selectedOccurrence,latitudeDegrees,longitudeDegrees\n");
        for(int i=0;i<original.size();i++){var point=projection.eastNorth2latlon(original.get(i));csv.append(i).append(',').append(point.lat()).append(',').append(point.lon()).append('\n');}
        var geometry=Format15Artifact.text("original-osm-geographic.csv",csv.toString());
        var settings=Format15Artifact.text("original-review-settings.json","{\"scope\":\"original-review-settings-only\",\"heatmap\":"+review.originalConfig().heatmap().toRedactedJson()+",\"cleanup\":"+review.originalConfig().cleanup().toRedactedJson()+"}\n");
        Format15Artifact originalWork=null;
        if(nativeOriginalWorkSupplier!=null)try {
            var known=nativeOriginalWorkSupplier.get();
            if(known!=null&&known.name().equals("original-native-work.json")&&known.bytes().length<=65536)originalWork=known;
        }catch(RuntimeException unavailable){PluginLog.verbose("Original native preparation work unavailable: %s",unavailable.getClass().getSimpleName());}
        String cause=terminal&&busy?"CANCELLED":attempt.cause();
        String json="{\"schema\":2,\"attempt\":\""+attempt.attempt()+"\",\"phase\":\"BOOTSTRAP\",\"cause\":\""+cause
            +"\",\"operation\":\""+attempt.operation()+"\",\"requestedEngine\":"+(attempt.requestedEngine()==null?"null":"\""+attempt.requestedEngine().name()+"\"")
            +",\"strength\":\""+attempt.strength()+"\",\"publication\":\"NO_PREPARED_ROOT\",\"nativeCalls\":0,\"nativeCallScope\":\"requested-optional-operation\",\"nativeFrozenInputs\":\"unavailable\",\"originalNativeWork\":\""+(originalWork==null?"unavailable":"available")+"\",\"chainReplay\":\"unavailable\",\"originalGeometryScope\":\"immutable-original-selected-segment\",\"auxiliaryWorkVisits\":"+(attempt.workVisits().isPresent()?attempt.workVisits().getAsLong():"null")
            +",\"auxiliaryMaximumVisits\":"+(maximum.isPresent()?maximum.getAsLong():"null")
            +",\"consumerFrames\":["+attempt.consumers().stream().map(frame->"{\"class\":\""+frame.className()+"\",\"method\":\""+frame.methodName()+"\",\"line\":"+frame.line()+"}").collect(java.util.stream.Collectors.joining(","))
            +"],\"consumerFramesUnavailable\":"+attempt.consumerFramesUnavailable()+",\"consumerFramesTruncated\":"+attempt.consumerFramesTruncated()
            +attempt.failure().map(RefinementAttemptDiagnostics.FailureFacts::jsonMembers).orElse("")+"}\n";
        var status=Format15Artifact.text("latest-operation.json",json);
        // This fallback deliberately has no authority receipt, generated geometry or lineage.
        var artifacts=new LinkedHashMap<String,Format15Artifact>();artifacts.put(geometry.name(),geometry);artifacts.put(settings.name(),settings);artifacts.put(status.name(),status);
        if(originalWork!=null)artifacts.put(originalWork.name(),originalWork);
        return new Format15Bundle("runtime-stepwise-preparation-v2",attempt.sourceHash(),settings.sha256(),artifacts);
    }
    private static String bootstrapSourceHash(String source) {
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("Required SHA-256 unavailable");}
    }
    private static final class ExportBudgetRefused extends IllegalStateException { }
    private String exportUnavailableMessage(){return switch(exportUnavailable){case NONE->"";case BUDGET->"Refinement diagnostics unavailable: memory budget reached";case BINDING->"Refinement diagnostics unavailable: retained binding could not be verified";case ENCODING->"Refinement diagnostics unavailable: archive encoding failed";};}
    public long workflowOwnedBytes(){return diagnosticMemory.workflowOwnedBytes();}
    public long registryOwnedBytes(){return diagnosticMemory.registryOwnedBytes();}
    @Override public boolean isCurrent(){
        return !closed&&ownsPublication()&&diagnosticOwner!=null&&!diagnosticSealed
            &&(bootstrapDiagnostic!=null||session!=null&&!nativeCatalogPresented);
    }
    private void startDiagnostics() {
        if(closed||!ownsPublication()||legacyContextSupplier==null&&nativeContextSupplier==null)return;
        bootstrapDiagnostic=null;if(bootstrapDiagnosticMemory!=null)bootstrapDiagnosticMemory.close();bootstrapDiagnosticMemory=null;
        // The initial optional history admits the selected original choice. The
        // ordinary review catalog can already be showing a different detector
        // than it showed before the user clicked a refinement.
        if(root.stage().candidates().stream().noneMatch(c->c.id().value().equals(initialDiagnosticChoice)))
            initialDiagnosticChoice=root.stage().selected().value();
        if(review instanceof ModernReviewRoot) {
            originalNativeContextChoice=root.stage().candidates().stream().filter(c->c.id().value().equals(initialDiagnosticChoice)).findFirst().orElseThrow();
            legacyContextSupplier=null;if(!DiagnosticsRegistry.ownsRefinement(diagnosticOwner))diagnosticOwner=DiagnosticsRegistry.claimRefinement(this);return;
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
        legacyContextSupplier=null;if(!closed&&ownsPublication()&&diagnosticOwner==null)diagnosticOwner=DiagnosticsRegistry.claimRefinement(this);
    }
    public boolean handlesRuntimeDiagnostics(){
        return review instanceof ModernReviewRoot?isCurrent()&&DiagnosticsRegistry.ownsRefinement(diagnosticOwner):diagnosticOwner!=null;
    }
    public void diagnosticsChanged(String state) {
        if(!Set.of("preview-open","review-confirmed","review-confirmation-failed","cancelled","applied","apply-failed","no-change").contains(state))throw new IllegalArgumentException("Fixed diagnostic state required");
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
            if(bootstrapDiagnostic!=null) {
                encoded=java.nio.file.Files.createTempFile("refinement-preparation-",".zip");Format15BundleWriter.write(bootstrapBundle(terminal),encoded);
                if(isCurrent()&&DiagnosticsRegistry.publishRefinement(diagnosticOwner,admission.read(encoded),terminal)){diagnosticSealed=terminal;exportUnavailable=ExportUnavailable.NONE;}
                return;
            }
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
                        conversion.close();retained.reserve(bytes).adopt(bundle);nativeDiagnosticContext=bundle;nativeDiagnosticContextMemory=retained;
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
            var retainedRoot=session.history().get(0);
            String admittedRootChoice=retainedRoot.candidates().stream().anyMatch(c->c.id().value().equals(initialDiagnosticChoice))
                    ?initialDiagnosticChoice:retainedRoot.selected().value();
            var bundle=RuntimeRefinementDiagnostics.capture(root.authority(),session.history(),session.current(),session.id(),admittedRootChoice,
                latest,result,availability,diagnosticState,reviewed,applied,context);
            var artifacts=new LinkedHashMap<>(bundle.artifacts());
            if(nativeOriginalWorkSupplier!=null)try {
                var originalWork=nativeOriginalWorkSupplier.get();if(originalWork!=null&&originalWork.name().equals("original-native-work.json")&&originalWork.bytes().length<=65536)artifacts.put(originalWork.name(),originalWork);
            }catch(RuntimeException unavailable){PluginLog.verbose("Original retained native work unavailable: %s",unavailable.getClass().getSimpleName());}
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
            computationEvidence+="; failed="+unavailable.getClass().getSimpleName()+"; phase=EXPORT";
            for(var frame:unavailable.getStackTrace())if(frame.getClassName().startsWith("org.openstreetmap.josm.plugins.wayheatmaptracer.")&&!frame.getMethodName().equals("invalid")) {
                computationEvidence+="; boundary="+frame.getClassName().substring(frame.getClassName().lastIndexOf('.')+1)+"."+frame.getMethodName()+":"+frame.getLineNumber();break;
            }
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
        Optional<RefinementAttemptDiagnostics.SourceFailure> sourceFailure=Optional.empty();
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
    private volatile boolean nativeCatalogPresented;
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
            if(sourceContext!=null&&!needsNativeRootPreparation())sourceContext.requireCurrent();else review.requireAuthorityCurrent();
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
    public LegacyRefinementWorkflow(RefinementReviewRoot review,TileFetchCoordinator coordinator,
            Consumer<CenterlineCandidate> rootSelection,Consumer<Display> display,Consumer<String> status,
            Executor executor,AlignmentJob.EventDispatcher publicationDispatcher,VisibleRefinementCapture.Port visibleCapture) {
        this(review,coordinator,rootSelection,display,status,executor,publicationDispatcher,SourcePreparationControl.production(),visibleCapture);
    }
    private LegacyRefinementWorkflow(RefinementReviewRoot review,TileFetchCoordinator coordinator,
            Consumer<CenterlineCandidate> rootSelection,Consumer<Display> display,Consumer<String> status,
            Executor executor,AlignmentJob.EventDispatcher publicationDispatcher,SourcePreparationControl sourcePreparationControl,VisibleRefinementCapture.Port visibleCapture) {
        this.visibleCapture=Objects.requireNonNull(visibleCapture);
        this.sourcePreparationControl=Objects.requireNonNull(sourcePreparationControl);
        this.review=review;this.coordinator=coordinator;this.presentedRootChoice=review.choiceId(review.selectedCandidate());this.rootSelection=rootSelection;this.display=display;this.status=status;
        this.executor=Objects.requireNonNull(executor);this.publicationDispatcher=Objects.requireNonNull(publicationDispatcher);
        nativeCatalogPresented=review instanceof ModernReviewRoot;
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
    public String selectedChoiceIdentity(){return atRoot()?review.choiceId(review.selectedCandidate()):session.current().selected().value();}
    public boolean atRoot(){return session==null||session.current().kind().root();}
    public boolean currentSourceAvailable(){return !closed&&!review.invalidated()&&originalSourceCurrent();}
    /** Browses a factual original composition. Optional work prepares its selected owner on demand. */
    public void selectIntervalComposition(ModernReviewRoot.IntervalSelection selection) {
        if(!SwingUtilities.isEventDispatchThread()||busy||closed||!ownsPublication()||!(review instanceof ModernReviewRoot modern)||!modern.partitionedRoot()||!atRoot())
            throw new IllegalStateException("Interval selection requires the current idle native root");
        modern.selectInterval(selection);
        confirmation=null;diagnosticState="preview-open";
        showCurrent();
        present();
    }
    /** The ordinary full-way chooser calls this after displaying its exact selected route. */
    public void originalNativeSelectionPresented() {
        if(!(review instanceof ModernReviewRoot)||!atRoot()||busy||!ownsPublication())return;
        presentedNative=null;presentedRootChoice=review.choiceId(review.selectedCandidate());
        nativeCatalogPresented=true;confirmation=null;diagnosticState="preview-open";present();
    }
    private boolean needsNativeRootPreparation() {
        return review instanceof ModernReviewRoot&&atRoot()
            &&(session==null||!session.current().selected().value().equals(review.choiceId(review.selectedCandidate())));
    }
    private record NativeBranch(RefinementSession session,PreparedRefinementRoot prepared) { }
    private NativeBranch forwardNativeBranch;
    private void replaceNativeBranch(RefinementSession next,PreparedRefinementRoot completed) {
        if(session!=null) {
            if(session.history().size()>1)forwardNativeBranch=new NativeBranch(session,preparedRoot);
            else discardNativeBranch(new NativeBranch(session,preparedRoot));
        }
        installNativeBranch(next,completed);
    }
    private void installNativeBranch(RefinementSession next,PreparedRefinementRoot completed) {
        session=next;preparedRoot=completed;root=completed.root();sourceContext=completed.source();
        preparingSourceCurrent=sourceContext::requireCurrent;
        clearDiagnostics();
        if(nativeDiagnosticContextMemory!=null)nativeDiagnosticContextMemory.close();
        nativeDiagnosticContextMemory=null;nativeDiagnosticContext=null;
        reviewedSelection=null;diagnosticState="preview-open";legacyContextAvailability="unavailable-not-retained";
    }
    private void discardNativeBranch(NativeBranch branch) {
        for(var stage:branch.session().history()) {
            var retained=retainedStages.remove(stage.id());if(retained!=null)retained.close();
        }
        branch.session().close();branch.prepared().close();
    }
    private void settleForwardNativeBranch() {
        if(forwardNativeBranch==null)return;
        discardNativeBranch(forwardNativeBranch);forwardNativeBranch=null;
    }
    public List<org.openstreetmap.josm.data.coor.EastNorth> selectedGeometry(){
        if(!nativeSelected())return review.selectedFinalGeometry();
        var selected=selected();return selected.payload().nativeGeometry().stream().filter(c->c.id().equals(selected.nativeCandidateId())).findFirst().orElseThrow().finalPreviewPoints();
    }
    public Stage currentStage(){if(session==null)throw new IllegalStateException("Refinement root has not been captured");return session.current();}
    public RefinementControls controls(){return controls;}
    public boolean busy(){return busy;}
    public boolean nativeSelected(){
        return session!=null&&session.current().kind()!=StageKind.ROOT_LEGACY
            &&(!atRoot()||!needsNativeRootPreparation()&&presentedRootChoice==null);
    }
    public Optional<RefinementPayload.NativeReview> currentNative() {
        if(!nativeSelected())return Optional.empty();return selected().payload().nativeReview();
    }
    public Optional<RefinementPayload.NoChangeReview> currentNoChangeRoot(){return nativeSelected()?selected().payload().rootNoChangeReview():Optional.empty();}
    private Candidate selected(){var stage=session.current();return stage.candidates().stream().filter(c->c.id().equals(stage.selected())).findFirst().orElseThrow();}
    private void select(String id) {
        if(busy||closed||!ownsPublication())return;
        try {review.requireOriginalAttemptCurrent();confirmation=null;
            if(review instanceof ModernReviewRoot&&atRoot()){
                review.select(review.candidateForChoice(id));diagnosticState="preview-open";showCurrent();
            } else if(session==null){var candidate=review.candidateForChoice(id);showRoot(candidate,id);}
            else if(session.current().kind()==StageKind.ROOT_LEGACY){selectLegacyRootChoice(id);}
            else{session.select(new CandidateId(id));if(nativeSelected())retainSelectionMetadata(session.current());showCurrent();}
        } catch(RuntimeException failure){message=failure.getMessage();}
        present();
    }
    private void selectLegacyRootChoice(String id) {
        var choiceId=new CandidateId(id);
        var displayed=review.candidateForChoice(id);
        var previous=review.selectedCandidate();
        var prepared=session.current().candidates().stream().filter(choice->choice.id().equals(choiceId)).findFirst();
        var newOwner=prepared.isEmpty()?rootMemory.child("legacy-original-choice"):null;
        boolean admitted=false;
        try {
            var choice=prepared.orElseGet(()->new LegacyRefinementBootstrap().materializeLegacyChoice(seed,id,newOwner));
            session.selectLegacyChoice(choice,()->{
                showRoot(displayed,id);
                review.requireOriginalAttemptCurrent();
            });
            if(newOwner!=null)rootCompositionOwners.put(choiceId,newOwner);
            admitted=true;
            var live=session.current().candidates().stream().map(Candidate::id).collect(java.util.stream.Collectors.toSet());
            rootCompositionOwners.entrySet().removeIf(entry->{
                if(live.contains(entry.getKey()))return false;
                entry.getValue().close();return true;
            });
        } catch (RuntimeException failure) {
            if(review.selectedCandidate()!=previous&&!review.invalidated())showRoot(previous,review.choiceId(previous));
            throw failure;
        } finally {
            if(newOwner!=null&&!admitted)newOwner.close();
        }
    }
    private void navigate(boolean forward) {
        if(busy||session==null||closed||!ownsPublication())return;
        try{
            review.requireOriginalAttemptCurrent();confirmation=null;
            if(forward&&atRoot()&&forwardNativeBranch!=null) {
                var branch=forwardNativeBranch;
                var next=branch.session().history().get(1);
                showStage(next);review.requireOriginalAttemptCurrent();
                discardNativeBranch(new NativeBranch(session,preparedRoot));
                forwardNativeBranch=null;installNativeBranch(branch.session(),branch.prepared());
                session.redo();
            } else {
                if(forward) {
                    session.redo();
                } else {
                    var restored=session.back();
                    if(restored.isPresent()&&atRoot()&&review instanceof ModernReviewRoot modern) {
                        modern.restoreHistorySelection(selected());
                    }
                }
                showCurrent();
            }
            if(review instanceof ModernReviewRoot&&nativeSelected())startDiagnostics();
        }catch(RuntimeException failure){if(review instanceof ModernReviewRoot)restoreCurrentPresentation();message=failure.getMessage();}
        present();
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
        if(review instanceof ModernReviewRoot)nativeCatalogPresented=true;
        presentedNative=null;presentedRootChoice=null;
        try{review.select(candidate);rootSelection.accept(candidate);presentedRootChoice=choice;}
        catch(RuntimeException|Error failed){throw new PresentationFailed();}
    }
    private void showCurrent(){
        if(session==null||needsNativeRootPreparation())showRoot(review.selectedCandidate(),review.choiceId(review.selectedCandidate()));
        else showStage(session.current());
    }
    private void showStage(Stage stage) {
        if(stage.kind()==StageKind.ROOT_LEGACY) {
            showRoot(review.candidateForChoice(stage.selected().value()),stage.selected().value());
        } else {
            if(stage.kind()==StageKind.ROOT_NATIVE)review.select(review.candidateForChoice(stage.selected().value()));
            presentedNative=null;presentedRootChoice=null;
            var candidate=stage.candidates().stream().filter(c->c.id().equals(stage.selected())).findFirst().orElseThrow();
            var result=candidate.result();var actual=result.candidates().stream().filter(c->c.id().equals(candidate.nativeCandidateId())).findFirst().orElseThrow();
            try{display.accept(new Display(result,actual,candidate.payload().nativeReview(),candidate.payload().rootNoChangeReview()));}
            catch(RuntimeException|Error failed){throw new PresentationFailed();}
            presentedNative=new Presentation(stage.id(),candidate.id(),candidate.immutableIdentity());
            nativeCatalogPresented=false;
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
            boolean needsBootstrap=session==null||needsNativeRootPreparation();
            if(needsBootstrap){
                cancellation=null;
                beginBootstrapDiagnostics(engine,smoothing);
            }
            review.requireOriginalAttemptCurrent();confirmation=null;message="";
            if(needsBootstrap) {
                var work=beginOwnedWork();
                var bootstrapMemory=rootMemory.child("lazy-original-root");
                try {
                    if(review instanceof ModernReviewRoot modern) {
                        cancellation=new CancellationToken();initialVisibleBudget=RefinementPipeline.OperationBudget.production(TraceBudgets.defaults());
                        preparingSourceCurrent=modern.source()::requireCurrent;
                        initialVisibleWork=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext(sourcePreparationControl.visits(),sourcePreparationControl.bytes(),initialVisibleBudget.finalDeadlineNanos(),probe(cancellation),bootstrapMemory);
                    } else {
                        cancellation=new CancellationToken();initialVisibleBudget=RefinementPipeline.OperationBudget.production(TraceBudgets.defaults());
                        initialVisibleWork=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext(sourcePreparationControl.visits(),sourcePreparationControl.bytes(),initialVisibleBudget.finalDeadlineNanos(),probe(cancellation),bootstrapMemory);
                        seed=new LegacyRefinementBootstrap().capture((LegacyReviewOwner)review,coordinator,initialVisibleWork);
                        var original=seed.originalSource();preparingSourceCurrent=()->original.requireCurrent(coordinator);
                    }
                }
                catch(RuntimeException refused){message=bootstrapFailureMessage(refused);bootstrapMemory.close();settleWork(work);completeLifecycle(work,false);throw refused;}
                busy=true;if(initialVisibleWork==null)cancellation=new CancellationToken();
                try{present();}catch(RuntimeException failed){busy=false;bootstrapMemory.close();settleWork(work);completeLifecycle(work,false);throw failed;}
                var captured=seed;var probe=probe(cancellation);
                var bootstrapWork=initialVisibleWork;
                CompletableFuture<PreparedRefinementRoot> bootstrap;
                try{bootstrap=CompletableFuture.supplyAsync(()->{
                    if(review instanceof ModernReviewRoot modern)return new ModernRefinementBootstrap().complete(modern,bootstrapWork,visibleCapture);
                    var completed=new LegacyRefinementBootstrap().complete(captured,coordinator,bootstrapWork);
                    return new PreparedRefinementRoot(completed,captured.settings(),captured.original(),new LegacyRefinementSourceContext(captured,completed,coordinator,visibleCapture),captured.memory(),captured.payloadLimit());
                },executor);}catch(RuntimeException rejected){bootstrap=CompletableFuture.failedFuture(rejected);}
                bootstrap
                    .whenComplete((completed,failure)->dispatchCompletion(()->{
                        boolean continueRefinement=false;
                        try {
                            if(closed||!ownsPublication())return;
                            if(failure!=null)throw unwrap(failure);
                            probe.checkpoint();review.requireOriginalAttemptCurrent();
                            var admittedRoot=completed.root();
                            var admittedSession=new RefinementSession(admittedRoot.stage(),review.originalConfig(),
                                    admittedRoot.authority().sourceIdentity(),admittedRoot.authority(),RefinementLimits.defaults());
                            if(review instanceof ModernReviewRoot) {
                                if(!admittedRoot.stage().selected().value().equals(review.choiceId(review.selectedCandidate())))
                                    throw new IllegalStateException("Native root selection changed before publication");
                                showStage(admittedRoot.stage());probe.checkpoint();review.requireOriginalAttemptCurrent();
                                replaceNativeBranch(admittedSession,completed);
                            } else {
                                preparedRoot=completed;root=admittedRoot;sourceContext=completed.source();
                                preparingSourceCurrent=sourceContext::requireCurrent;session=admittedSession;
                                bindLegacyReview();
                            }
                            retainedStages.put(root.stage().id(),new RetainedStage(review instanceof ModernReviewRoot?bootstrapMemory:completed.memory(),root.stage()));
                            startDiagnostics();continueRefinement=true;
                        }catch(RuntimeException refused){
                            bootstrapFailed(refused,true);
                            if(review instanceof ModernReviewRoot) {
                                if(completed==null||completed!=preparedRoot)bootstrapMemory.close();
                                if(!closed)restoreCurrentPresentation();
                            } else {
                                if(session!=null){session.close();session=null;}
                                if(root!=null)retainedStages.remove(root.stage().id());
                                seed=null;root=null;preparedRoot=null;sourceContext=null;preparingSourceCurrent=()->{};
                                bootstrapMemory.close();
                            }
                            message=bootstrapFailureMessage(refused);
                        }
                        finally {
                            busy=false;if(closed)releaseClosed();
                            settleWork(work);
                            if(!closed){if(continueRefinement){operate(engine,smoothing);if(!busy)completeLifecycle(work,false);}else{try{present();}finally{completeLifecycle(work,false);}}}
                        }
                    }));
                return;
            }
            if(review instanceof ModernReviewRoot&&atRoot()&&!nativeSelected()) {
                showCurrent();startDiagnostics();
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
                                        candidate.payload().nativeSourceBindings().values().forEach(RetainedSmoothingSource::requireCurrent);
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
                                    settleForwardNativeBranch();
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
                        computationEvidence+="; failed="+refused.getClass().getSimpleName()+"; phase="+progress.phase;
                        for(var frame:refused.getStackTrace())if(frame.getClassName().startsWith("org.openstreetmap.josm.plugins.wayheatmaptracer.")) {
                            computationEvidence+="; boundary="+frame.getClassName().substring(frame.getClassName().lastIndexOf('.')+1)+"."+frame.getMethodName()+":"+frame.getLineNumber();break;
                        }
                        boolean stale=!originalSourceCurrent()||review.invalidated()||progress.pipelineOutcome==Outcome.STALE;
                        boolean cancelled=entry.cancellationRequested&&!stale;
                        boolean presentationFailed=refused instanceof PresentationFailed;
                        if(progress.presentationAttempted&&!hasCurrentPresentation())presentationFailed|=!restoreCurrentPresentation();
                        boolean limited=refused instanceof AttemptMemoryLedger.ResourceLimitException||refused instanceof RefinementSession.Refused||refused instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.LimitReached;
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
            if(session==null||needsNativeRootPreparation())bootstrapFailed(refused,true);
            else if(preparing==null){
                computationEvidence="preparation; failed="+refused.getClass().getSimpleName()+"; phase=PREPARATION";
                for(var frame:refused.getStackTrace())if(frame.getClassName().startsWith("org.openstreetmap.josm.plugins.wayheatmaptracer.")){computationEvidence+="; boundary="+frame.getClassName().substring(frame.getClassName().lastIndexOf('.')+1)+"."+frame.getMethodName()+":"+frame.getLineNumber();break;}
                if(refused instanceof RefinementSession.Refused typed)computationEvidence+="; refusal="+typed.reason().name();
                if(refused instanceof SmoothingParentBinder.Unavailable typed)computationEvidence+="; smoothing-binding="+typed.reason().name();
            }
            if(active!=null&&!busy){
                if(preparing!=null)publishDiagnostics(active,preparing,RefinementAttemptDiagnostics.Publication.REFUSED,
                    refused instanceof AttemptMemoryLedger.ResourceLimitException||refused instanceof RefinementSession.Refused?RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT:RefinementAttemptDiagnostics.Cause.FAILED);
                else {clearDiagnostics();diagnosticsAvailability=RefinementAttemptDiagnostics.Availability.UNAVAILABLE_BUDGET;}
                session.fail(active,Outcome.FAILED);session.workerSettled(active);active=null;
            }
            message=session==null||needsNativeRootPreparation()?bootstrapFailureMessage(refused):refused.getMessage();busy=false;present();
        }
    }
    private void startSmoothing(RefinementControls.Strength strength) {
        var ticket=session.beginSmoothing(strength==RefinementControls.Strength.STRONG?"Strong":"Medium");active=ticket;confirmation=null;
        var input=ticket.smoothingInput().orElseThrow();
        var parentStage=session.history().stream().filter(s->s.id().equals(input.unsmoothedParent())).findFirst().orElseThrow();
        var parent=parentStage.candidates().stream().filter(c->c.id().equals(input.unsmoothedChoice())).findFirst().orElseThrow();
        var reference=new ParentReferenceBinder().bind(root.authority(),parentStage.id(),parent);
        var source=parent.payload().nativeSourceBindings().get(reference.selectedSource());
        if(source==null&&root.originalConversion()!=null&&root.originalConversion().source().equals(reference.selectedSource()))
            source=root.originalConversion();
        boolean legacyOriginal=parent.payload().nativeReview().isEmpty()&&parent.payload().rootNoChangeReview().isEmpty()&&review instanceof LegacyReviewOwner;
        if(legacyOriginal&&source!=null&&!source.legacyDisposition().map(value->value.nativeCandidateId().equals(parent.nativeCandidateId())).orElse(false))source=null;
        if(source==null&&!legacyOriginal)throw new SmoothingParentBinder.Unavailable(SmoothingParentBinder.Reason.SELECTED_SOURCE_NOT_RETAINED);
        clearDiagnostics();long available=session.availablePayloadBytes();diagnosticsSlot=RefinementAttemptDiagnostics.Slot.open(rootMemory,available).orElse(null);
        diagnosticsAvailability=diagnosticsSlot==null?RefinementAttemptDiagnostics.Availability.UNAVAILABLE_BUDGET:RefinementAttemptDiagnostics.Availability.RUNNING;
        long allowance=available-(diagnosticsSlot==null?0:diagnosticsSlot.bytes());session.reserve(ticket,1,allowance);
        var progress=new AttemptProgress(new RefinementAttemptDiagnostics.Binding(ticket,session.history().get(0).id(),root.authority().sourceIdentity(),root.authority().network().canonicalHash(),RuntimeRefinementDiagnostics.frozenSettingsIdentity(root.authority()),root.authority().frozenGeometryIdentity(),reference.canonicalIdentity()),diagnosticsSlot);activeProgress=progress;
        var operation=rootMemory.child("smoothing-"+ticket.sequence());var publication=rootMemory.child("smoothing-publication-"+ticket.sequence());
        parent.payload().retainSmoothingSources(operation);if(source!=null)source.retainInto(operation);
        var baseline=session.admissionInventories();
        if(initialVisibleWork==null)cancellation=new CancellationToken();var entry=new NativeEntry(ticket);activeEntry=entry;var probe=probe(cancellation,entry);
        var budget=initialVisibleBudget!=null?initialVisibleBudget:RefinementPipeline.OperationBudget.production(root.authority().request().budgets());
        var inherited=initialVisibleWork;initialVisibleWork=null;initialVisibleBudget=null;
        var workContext=inherited!=null?inherited:new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext(sourcePreparationControl.visits(),sourcePreparationControl.bytes(),budget.finalDeadlineNanos(),probe,operation);
        LegacyRefinementBootstrap.SmoothingSeed originalSeed;
        try{originalSeed=source==null?new LegacyRefinementBootstrap().captureForSmoothingFromOriginal((LegacyReviewOwner)review,coordinator,workContext,seed):null;}
        catch(RuntimeException failed){operation.close();publication.close();baseline.clear();activeEntry=null;entry.settlement.complete(null);throw failed;}
        var retainedSource=source;
        var originalRoot=new LegacyRefinementBootstrap.Root(root.authority(),session.history().get(0),root.receipt(),root.originalConversion());
        var completedSource=new java.util.concurrent.atomic.AtomicReference<RetainedSmoothingSource>(source);
        busy=true;message="";PreviewWorkGate.Work work;
        try{present();work=beginOwnedWork();if(lifecycle!=null)lifecycle.attachTicket(work,ticket);}
        catch(RuntimeException failed){operation.close();publication.close();baseline.clear();busy=false;throw failed;}
        CompletableFuture<Computed> computation;
        try{computation=CompletableFuture.supplyAsync(()->{
            var actual=retainedSource;
            if(actual==null){
                progress.phase=RefinementAttemptDiagnostics.Phase.SOURCE_PREPARATION;
                try{actual=new LegacyRefinementBootstrap().completeForSmoothing(originalSeed,coordinator,workContext,originalRoot).source();completedSource.set(actual);}
                catch(RuntimeException failed){
                    var cause=failed instanceof java.util.concurrent.CancellationException?RefinementAttemptDiagnostics.Cause.CANCELLED:
                        failed instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.LimitReached||failed instanceof AttemptMemoryLedger.ResourceLimitException?RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT:RefinementAttemptDiagnostics.Cause.FAILED;
                    try{sourceContext.requireCurrent();}catch(RuntimeException stale){cause=RefinementAttemptDiagnostics.Cause.STALE;}
                    progress.sourcePreparation=Optional.of(new RefinementAttemptDiagnostics.SourcePreparationWork(cause,workContext.visits(),workContext.counters(),Optional.empty(),Optional.empty(),Optional.empty(),OptionalLong.of(workContext.maximumVisits())));throw failed;
                }
            }
            return computeSmoothing(ticket,parent,actual,strength,workContext,probe,progress,publication,allowance,baseline);
        },executor);}
        catch(RuntimeException rejected){computation=CompletableFuture.failedFuture(rejected);}
        computation.whenComplete((computed,failure)->{baseline.clear();entry.workerReturned=true;dispatchCompletion(()->{
            var outcome=RefinementAttemptDiagnostics.Publication.FAILED;var cause=RefinementAttemptDiagnostics.Cause.FAILED;
            try {
                if(closed||!ownsPublication()||!Objects.equals(active,ticket))return;
                if(failure!=null)throw unwrap(failure);
                var workerPhase=progress.phase;progress.phase=RefinementAttemptDiagnostics.Phase.PUBLICATION;
                probe.checkpoint();review.requireOriginalAttemptCurrent();completedSource.get().requireCurrent();
                cause=computed.cause();
                if(computed.stage().isEmpty()){progress.phase=workerPhase;session.fail(ticket,cause==RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT?Outcome.RESOURCE_LIMIT:Outcome.NO_USABLE_RESULT);outcome=RefinementAttemptDiagnostics.Publication.NO_CHILD;message=smoothingRetainedMessage(progress.smoothingReceipt,cause);}
                else {
                    var child=computed.stage().orElseThrow();
                    if(session.publish(ticket,child,()->{
                        progress.presentationAttempted=true;showStage(child);probe.checkpoint();review.requireOriginalAttemptCurrent();
                    })) {
                        retainedStages.put(child.id(),new RetainedStage(publication,child));
                        settleForwardNativeBranch();
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
                boolean limited=failed instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.LimitReached||failed instanceof AttemptMemoryLedger.ResourceLimitException||failed instanceof RefinementSession.Refused;
                if(!closed)session.fail(ticket,stale?Outcome.STALE:cancelled?Outcome.CANCELLED:limited?Outcome.RESOURCE_LIMIT:Outcome.FAILED);
                if(progress.presentationAttempted)restoreCurrentPresentation();
                outcome=stale?RefinementAttemptDiagnostics.Publication.STALE:cancelled?RefinementAttemptDiagnostics.Publication.CANCELLED:RefinementAttemptDiagnostics.Publication.FAILED;
                cause=stale?RefinementAttemptDiagnostics.Cause.STALE:cancelled?RefinementAttemptDiagnostics.Cause.CANCELLED:limited?RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT:RefinementAttemptDiagnostics.Cause.FAILED;
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
    static String smoothingRetainedMessage(Optional<RefinementSmoothingAdapter.Receipt> receipt,
            RefinementAttemptDiagnostics.Cause cause) {
        if (receipt.isPresent() && receipt.get().outcome() == RefinementSmoothingAdapter.Outcome.UNCHANGED) {
            if ("no-movable-interior".equals(receipt.get().reason()))
                return "Smoothen retained the selected parent: there are no movable interior points.";
            return "Smoothen retained the selected parent: smoothing produced no geometric change.";
        }
        return "Smoothen retained the selected parent: " + cause.name().toLowerCase(Locale.ROOT) + ".";
    }
    private Computed computeSmoothing(OperationTicket ticket,Candidate parent,RetainedSmoothingSource source,RefinementControls.Strength strength,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext work,CancellationProbe probe,AttemptProgress progress,
            AttemptMemoryLedger.Owner publication,long allowance,List<Map<Object,Long>> baseline) {
        progress.phase=RefinementAttemptDiagnostics.Phase.PREPARATION;
        computationEvidence="smoothing-preparation";
        try(var beforeHash=root.authority().network().memoizeBeforeHash(work)) {
            var bound=new SmoothingParentBinder().bind(root.authority(),ticket,parent,source,work);
            try(var result=new RefinementSmoothingAdapter().smooth(bound,strength==RefinementControls.Strength.STRONG?SmoothingModels.Preset.STRONG_2PX:SmoothingModels.Preset.MEDIUM_1PX,work,probe)) {
                progress.smoothingReceipt=Optional.of(result.receipt());progress.smoothingProtocol=Optional.of(source.inputHashProtocol());
                computationEvidence="smoothing-"+result.receipt().outcome()+":"+result.receipt().reason()+"; visits="+result.receipt().workVisits();
                probe.checkpoint();progress.phase=RefinementAttemptDiagnostics.Phase.MATERIALIZATION;
                if(result.plan().isEmpty()) {
                    var cause=result.receipt().outcome()==RefinementSmoothingAdapter.Outcome.RETAINED_RESOURCE?RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT:RefinementAttemptDiagnostics.Cause.PLAN_REFUSED;
                    progress.pipelineOutcome=Outcome.NO_USABLE_RESULT;return new Computed(Optional.empty(),cause);
                }
                try(var workspace=work.reserveOn(publication,"smoothing-child-materialization",result.publicationWorkspaceBytes());
                    var scope=org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.PostprocessingValidationWork.open(work)) {
                    var assessed=result.assessment().orElseThrow();var edit=result.plan().orElseThrow();
                    var projection=Projections.getProjectionByCode(root.authority().projectionCode());
                    var points=result.points().stream().map(p->{var g=edit.metricFrame().toGeographic(p);return projection.latlon2eastNorth(new LatLon(g.latitudeDegrees(),g.longitudeDegrees()));}).toList();
                    var assignments=new LinkedHashMap<Long,org.openstreetmap.josm.data.coor.EastNorth>();
                    for(int i=0;i<points.size();i++)if(assessed.candidate().pointIds().get(i) instanceof FinalRoutePointId.ExistingWayNodeOccurrence id)assignments.put(id.nodeKey().id(),points.get(i));
                    String nativeId=assessed.candidate().id();var candidate=new CenterlineCandidate(nativeId,0,List.of(),List.of(),points,points,assignments,List.of(),0,null,null,null,List.of());
                    var detector=new DetectorAttempt(source.source().palette(),source.source().mapping(),ticket.engine(),edit.validation().disposition()==ValidationReport.Disposition.APPLICABLE?DetectorAttemptStatus.APPLICABLE:DetectorAttemptStatus.REVIEW_REQUIRED,List.of(nativeId),"smoothing-final","Guarded Gaussian output");
                    var alignment=new AlignmentResult(parent.payload().originalSelectionContext(),null,List.of(candidate),parent.payload().originalSourcePolyline(),points,List.of(),null,null,List.of(detector),List.of(candidate));
                    var nativeReview=new RefinementPayload.NativeReview(edit,assessed.quality(),Optional.empty(),List.of(source.source()),OptionalInt.empty(),Map.of(source.source(),source),List.of(),Optional.of(assessed.candidate().pointIds()),Optional.of(result.receipt()),Optional.of(ticket),Optional.of(bound.reference().canonicalIdentity()));
                    var id=new CandidateId("smoothing-"+ticket.sequence());String identity=edit.canonicalHash();
                    var admission=new MarginalPayloadAdmission(baseline,allowance,probe);
                    long header=RefinementSession.stageMetadataBytes(1)+RefinementSession.candidateMetadataBytes(id,identity,nativeId,source.source().palette(),assessed.candidate().correspondence().size());
                    var payload=RefinementPayload.captureNative(alignment,parent.payload(),nativeReview,admission,header);
                    var child=Stage.child(ticket,List.of(new Candidate(id,payload,assessed.candidate().correspondence(),identity,nativeId,source.source().palette())),id);
                    // Keep exact plan and source owned through the worker-to-EDT handoff.
                    // The session independently checks the marginal logical history allowance.
                    payload.retainSmoothingSources(publication);
                    long retainedBytes=Math.addExact(header,payload.publicationBytes());
                    try(var retained=work.reserveOn(publication,"smoothing-child-retained",retainedBytes)) {
                        retained.adopt(child);
                    }
                    progress.pipelineOutcome=Outcome.COMPLETE;return new Computed(Optional.of(child),RefinementAttemptDiagnostics.Cause.NONE);
                }
            }
        } catch(SmoothingParentBinder.Unavailable unavailable) {
            computationEvidence="smoothing-binding-"+unavailable.reason();throw unavailable;
        } finally {progress.sourcePreparation=Optional.of(new RefinementAttemptDiagnostics.SourcePreparationWork(RefinementAttemptDiagnostics.Cause.NONE,work.visits(),work.counters(),Optional.empty(),Optional.empty(),Optional.empty(),OptionalLong.of(work.maximumVisits())));}
    }
    private Computed compute(OperationTicket ticket,ParentTraceReference parent,RefinementSourcePlanner.Plan plan,
            CredentialSnapshot credentials,AttemptMemoryLedger.Owner operation,AttemptMemoryLedger.Owner publication,RefinementPayload sourcePayload,
            AlignmentResult original,long allowance,CancellationProbe probe,NativeEntry entry,AttemptProgress progress,List<Map<Object,Long>> baseline,Stage publishedParent,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext inheritedWork,RefinementPipeline.OperationBudget inheritedBudget) {
        progress.phase=RefinementAttemptDiagnostics.Phase.ACQUISITION;
        if(ticket.kind()!=StageKind.REFINE_A&&ticket.kind()!=StageKind.REFINE_B)throw new IllegalArgumentException("Refinement preparation requires an actual A/B ticket");
        var budget=inheritedBudget!=null?inheritedBudget:RefinementPipeline.OperationBudget.production(parent.root().request().budgets());
        var workRef=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext[1];
        CancellationProbe hashProbe=()->{probe.checkpoint();try{sourcePreparationControl.checkpoint().accept(workRef[0].visits());}finally{probe.checkpoint();}return false;};
        var hashWork=inheritedWork!=null?inheritedWork:new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext(sourcePreparationControl.refinementVisits(),sourcePreparationControl.bytes(),budget.finalDeadlineNanos(),hashProbe,operation);workRef[0]=hashWork;
        boolean sourceOpening=false;
        try {
        if(inheritedWork!=null){hashWork=inheritedWork.phaseView(sourcePreparationControl.refinementVisits());workRef[0]=hashWork;}
        sourceOpening=true;
        var sources=sourceContext.open(plan,publishedParent,hashWork,operation,probe);
        var evidence=new HashMap<RefinementSourcePlanner.SourceRequest,EvidenceSnapshot>();
        var wrapped=new RefinementPipeline.Sources(){
            public void requireCurrent(){probe.checkpoint();sources.requireCurrent();}
            public EvidenceSnapshot evidence(RefinementSourcePlanner.SourceRequest source,CancellationProbe cancellation){
                progress.phase=RefinementAttemptDiagnostics.Phase.SOURCE_PREPARATION;
                try{var actual=sources.evidence(source,cancellation);evidence.put(source,actual);progress.phase=RefinementAttemptDiagnostics.Phase.PIPELINE;return actual;}
                catch(RefinementPipeline.SourceUnavailable unavailable){progress.phase=RefinementAttemptDiagnostics.Phase.PIPELINE;throw unavailable;}
                catch(RuntimeException failure) {
                    var outcome=RefinementPipeline.sourcePreparationFailure(failure,sources,probe);
                    progress.sourcePreparationCause=sourcePreparationCause(outcome);
                    progress.failedSource=Optional.of(source);
                    progress.sourceFailure=Optional.of(new RefinementAttemptDiagnostics.SourceFailure(
                        progress.phase,RefinementAttemptDiagnostics.FailureFacts.capture(failure)));
                    throw new RefinementPipeline.SourcePreparationFailure(outcome,failure);
                }
            }
            public Optional<RefinementSourcePlanner.Plan> binding(){return sources.binding();}
            public Optional<ManagedModernPreviewSource.RefinementWorkReceipt> actualSourceWork(){return sources.actualSourceWork();}
            public Optional<RetainedSmoothingSource> retentionDescriptor(RefinementSourcePlanner.SourceRequest source,EvidenceSnapshot actual){return sources.retentionDescriptor(source,actual);}
        };
        progress.phase=RefinementAttemptDiagnostics.Phase.PIPELINE;
        var retentionCancelled=new java.util.concurrent.atomic.AtomicBoolean();
        RefinementPipeline.Result outcome;
        try(var registration=probe.onCancellation(()->retentionCancelled.set(true))) {
        probe.checkpoint();
        outcome=new RefinementPipeline().run(ticket,parent,plan,new RefinementPipeline.Adapters(wrapped,RefinementPipeline.productionRunner(actual->{
                if(!actual.equals(ticket))throw new IllegalStateException("Native entry ticket changed");
                if(entry.entered.compareAndSet(false,true))SwingUtilities.invokeLater(()->firstNativeEntry.complete(entry));
            }),operation),
            budget,probe,retentionCancelled::get,actualResult->{
                progress.pipelineOutcome=actualResult.outcome();progress.nativeWork=Optional.of(RefinementAttemptDiagnostics.NativeWork.capture(actualResult));
                actualNativeCalls+=actualResult.attempts().stream().flatMap(a->a.run().stream()).mapToInt(RefinementEngineAdapter.Run::nativeCalls).sum();
                // Original native binding capacity has settled. Meter the same complete bound
                // around the remaining canonical receipt/network inventory before copying it.
                try(var binding=operation.reserve(parent.bindingScratchBytes(parent.root().evidence()))) {
                    progress.receipt=progress.slot==null?Optional.empty():progress.slot.receipt(progress.binding,parent,actualResult);
                }
            });
        }
        computationEvidence="outcome="+outcome.outcome()+" full="+outcome.candidates().size()+" rejected="+outcome.rejected().size()+" attempts="+outcome.attempts().stream().map(a->a.kind()+":"+a.reason()+":"+a.nativeCallAvailability()).toList()
            +" rejectedFindings="+outcome.rejected().stream().map(a->a.quality().findings().stream().map(f->f.code().name()).toList()).toList();
        CancellationProbe finalProbe=()->{probe.checkpoint();budget.requireFinalTime();return false;};
        var computed=materialize(ticket,parent,outcome,evidence,sourcePayload,original,allowance,finalProbe,progress,publication,baseline);
        computationEvidence+=" plan-refusals="+progress.materializationRefusals+" auxiliary-source-hash-visits="+hashWork.visits()+" auxiliary-source-hash-counts="+hashWork.counters();return computed;
        } catch(RuntimeException failure) {
            if(progress.phase==RefinementAttemptDiagnostics.Phase.ACQUISITION) {
                var actual=unwrap(failure);
                var facts=RefinementAttemptDiagnostics.FailureFacts.capture(actual);
                var outcome=facts.resourceLimited()?Outcome.RESOURCE_LIMIT
                    :actual instanceof CancellationException?Outcome.CANCELLED:Outcome.FAILED;
                progress.sourcePreparationCause=sourcePreparationCause(outcome);
                progress.sourceFailure=Optional.of(new RefinementAttemptDiagnostics.SourceFailure(progress.phase,facts));
                // Acquisition did not enter the pipeline; its native call count is known but engine work is unavailable.
                progress.nativeWork=Optional.of(new RefinementAttemptDiagnostics.NativeWork(outcome,Optional.empty(),OptionalInt.of(0)));
            }
            throw failure;
        } finally {
            progress.sourceFacts=sourceOpening?sourceContext.sourceFacts():Optional.empty();
            progress.sourcePreparation=Optional.of(new RefinementAttemptDiagnostics.SourcePreparationWork(
                progress.sourcePreparationCause,hashWork.visits(),hashWork.counters(),progress.failedSource,
                progress.nativeWork,progress.sourceFacts,OptionalLong.of(hashWork.maximumVisits()),progress.sourceFailure));
        }
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
        if(currentNoChangeRoot().isPresent())throw new IllegalStateException("A no-change acceptance does not require edit confirmation");
        if(busy||closed||!ownsPublication()||!nativeSelected()||!hasCurrentPresentation()||!originalSourceCurrent())throw new IllegalStateException("The exact current preview is unavailable or stale");
        var plan=currentNative().orElseThrow().plan();
        confirmation=PreviewReviewState.fromEditPlan(selected().id().value(),plan).confirm();reviewedSelection=currentDiagnosticKey();diagnosticState="review-confirmed";present();
    }
    public boolean canApplyNative(){
        if(busy||closed||!ownsPublication()||review.invalidated()||!originalSourceCurrent()||!hasCurrentPresentation()||session==null||!session.canApply())return false;
        if(currentNoChangeRoot().isPresent())return currentNoChangeRoot().orElseThrow().validation().applicable();
        return currentNative().map(n->PreviewReviewState.fromEditPlan(selected().id().value(),n.plan()).canApply()||confirmation!=null&&confirmation.matches(PreviewReviewState.fromEditPlan(selected().id().value(),n.plan()))).orElse(false);
    }
    /** Accepts a proven original no-op without entering Command.execute or changing host history. */
    public void acceptNoChangeRoot(){
        review.requireOriginalAttemptCurrent();if(!canApplyNative())throw new IllegalStateException("No-change result is unavailable or stale");
        var noChange=currentNoChangeRoot().orElseThrow(()->new IllegalStateException("An actual unchanged original root is required"));
        sourceContext.requireCurrent();noChange.requireMatches(root.authority());
        try{var current=root.receipt().requireCurrent(review.dataSet());noChange.requireCurrentNetwork(current);}
        catch(IllegalArgumentException|IllegalStateException stale){originalSourceStale=true;throw new IllegalStateException("No-change source graph changed after capture",stale);}
        if(!session.terminalizeForApply())throw new IllegalStateException("No-change review is no longer idle");
        diagnosticsChanged("no-change");freezeDiagnostics(true,false);
    }
    public Command command() {
        review.requireOriginalAttemptCurrent();
        if(!canApplyNative())throw new IllegalStateException("Refinement candidate requires review or is busy");
        if(currentNoChangeRoot().isPresent())throw new IllegalStateException("No-change acceptance does not create an edit command");
        var plan=currentNative().orElseThrow().plan();
        var detached=sourceContext.detachedCommandGeneration();var sourceGuard=new CommandGeneration(detached,commandContext);
        var network=new LiveNetworkSnapshotValidator(root.receipt(),plan,sourceGuard);
        org.openstreetmap.josm.plugins.wayheatmaptracer.util.LockedApplyValidator validator=detached instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.util.PreparedCommandSource
            ?new org.openstreetmap.josm.plugins.wayheatmaptracer.util.SourcePreparedApplyValidator(network,sourceGuard,reason->javax.swing.JOptionPane.showMessageDialog(org.openstreetmap.josm.gui.MainApplication.getMainFrame(),reason,"WayHeatmapTracer",javax.swing.JOptionPane.WARNING_MESSAGE)):network;
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
        if(atRoot()){
            String label=review.engine().toString();
            choices=review.choices().stream().map(c->new RefinementControls.Choice(c.id(),label+" / "+c.candidate().displayName())).toList();
            selected=review.choiceId(review.selectedCandidate());stage=label+" — stage 1";
            redo=forwardNativeBranch!=null||session!=null&&session.history().size()>1;
        } else {
            var current=session.current();
            String label=current.kind()==StageKind.SMOOTH?"Smoothen ("+current.smoothingInput().orElseThrow().preset()+")":current.engine().toString();
            choices=current.candidates().stream().map(c->new RefinementControls.Choice(c.id().value(),label+" / "+c.sourcePalette())).toList();
            selected=current.selected().value();int cursor=session.history().indexOf(current);
            undo=cursor>0;redo=cursor+1<session.history().size();stage=label+" — stage "+(cursor+1);
        }
        String quality=message;
        if(currentNoChangeRoot().isPresent()) {
            var noChange=currentNoChangeRoot().orElseThrow();quality="No edit is needed; this result exactly matches the original captured occurrences.\n"+noChange.validation().findingCodes();
            if(!message.isEmpty())quality=message+"\n"+quality;
        } else if(nativeSelected()){
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
    private void releaseClosed(){forwardNativeBranch=null;nativeDiagnosticContextMemory=null;bootstrapDiagnostic=null;if(bootstrapDiagnosticMemory!=null)bootstrapDiagnosticMemory.close();bootstrapDiagnosticMemory=null;latestDiagnostics=null;latestSmoothingReceipt=Optional.empty();latestSmoothingProtocol=Optional.empty();diagnosticsSlot=null;diagnosticsAvailability=RefinementAttemptDiagnostics.Availability.NONE;rootCompositionOwners.values().forEach(AttemptMemoryLedger.Owner::close);rootCompositionOwners.clear();diagnosticMemory.closeWorkflow();legacyDiagnosticContext=null;legacyContextSupplier=null;nativeDiagnosticContext=null;nativeContextSupplier=null;nativeContextFallback=null;nativeOriginalWorkSupplier=null;originalNativeContextChoice=null;ratingSupplier=null;pendingLegacyReview=null;pendingLegacyReviewMemory=null;retainedStages.clear();seed=null;root=null;preparedRoot=null;sourceContext=null;preparingSourceCurrent=()->{};session=null;confirmation=null;}
    @Override public void close(){if(closed)return;if(isCurrent()&&!diagnosticSealed){if(!diagnosticState.equals("apply-failed"))diagnosticsChanged("cancelled");freezeDiagnostics(true,false);}closed=true;if(lifecycle!=null)lifecycle.close();workGate.release(this);if(cancellation!=null)cancellation.cancel();if(session!=null)session.close();if(forwardNativeBranch!=null)forwardNativeBranch.session().close();if(!busy)releaseClosed();}
}
