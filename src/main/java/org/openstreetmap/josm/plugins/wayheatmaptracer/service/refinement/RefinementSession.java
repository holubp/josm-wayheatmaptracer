package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import javax.swing.SwingUtilities;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;

/** Event-thread-owned linear preview history. Never modifies OSM or the command stack. */
public final class RefinementSession implements AutoCloseable {
    public static final class Refused extends IllegalStateException {
        private final RefusalReason reason;
        public Refused(RefusalReason reason) { super(reason.name()); this.reason = reason; }
        public RefusalReason reason() { return reason; }
    }
    private final SessionId id = new SessionId(UUID.randomUUID());
    private final AlignmentConfig config;
    private final String sourceIdentity;
    private final RefinementRootAuthority authority;
    private final RefinementLimits limits;
    private final List<Stage> history = new ArrayList<>();
    private int cursor;
    private long sequence;
    private OperationTicket active;
    private final Map<OperationTicket, Reservation> reservations = new HashMap<>();
    private final Map<OperationTicket, List<Stage>> inputPins = new HashMap<>();
    private Failure lastFailure;
    private boolean terminal;
    private boolean confirmed;

    /** Preserves a supported starting engine and the configured or explicit invocation mode. */
    public static AlignmentConfig normalRootConfig(AlignmentConfig saved, AlignmentMode explicitMode) {
        Objects.requireNonNull(saved);
        var selected=saved.heatmap().trackerMode();
        var engine=Set.of(TrackerMode.LEGACY_V02,TrackerMode.CORRIDOR_AWARE,TrackerMode.PROBABILISTIC).contains(selected)?selected:TrackerMode.LEGACY_V02;
        return new AlignmentConfig(saved.heatmap().withTrackerMode(engine).withAlignmentMode(explicitMode),
            saved.cleanup(), saved.searchHalfWidthMetersOverride());
    }
    /**
     * Reserves additional owned history before a worker copies it. The receipt binds the exact current operation;
     * cancelled/failed work must discard its temporary payload, and publication rechecks actual bytes and counts.
     */
    public Reservation reserve(OperationTicket ticket, int candidateCount, long additionalBytes) {
        requireEdt();
        if (!owns(ticket) || reservations.containsKey(ticket))
            throw new IllegalStateException("Reservation requires the current unreserved operation");
        if (candidateCount < 1 || additionalBytes < 1) throw new IllegalArgumentException("Positive reservation required");
        RefusalReason reason = candidateCount > limits.maxCandidatesPerStage() ? RefusalReason.CANDIDATE_LIMIT
            : addWouldExceed(RefinementPayload.add(retainedBytes(), reservedBytes()), additionalBytes,
                limits.maxPayloadBytes()) ? RefusalReason.PAYLOAD_LIMIT : null;
        if (reason != null) {
            reject(ticket, reason);
            throw new Refused(reason);
        }
        Reservation reservation = new Reservation(ticket, candidateCount, additionalBytes);
        reservations.put(ticket, reservation);
        return reservation;
    }
    public long availablePayloadBytes() {
        requireEdt();
        return Math.max(0, limits.maxPayloadBytes() - RefinementPayload.add(retainedBytes(), reservedBytes()));
    }
    public long reservedBytes() {
        requireEdt();
        long bytes = 0;
        for (Reservation reservation : reservations.values()) bytes = RefinementPayload.add(bytes, reservation.additionalBytes());
        return bytes;
    }
    /**
     * Acknowledges that worker and queued-result owners have released their exact input stages and temporary payload.
     * Neither Cancel/Close nor successful publication implies input settlement. Call this on the EDT only after
     * handling publication/failure and worker teardown; discarded history remains charged until this acknowledgment.
     */
    public void workerSettled(OperationTicket ticket) {
        requireEdt();
        if (owns(ticket)) throw new IllegalStateException("A live operation cannot release its publication reservation");
        reservations.remove(ticket);
        inputPins.remove(ticket);
    }
    /** Pure operation ownership only; callers must supply a separately qualified smoothing implementation. */
    public OperationTicket beginSmoothing(String preset) {
        requireIdle();
        Stage owner = current();
        SmoothingInput old = owner.smoothingInput().orElse(null);
        SmoothingInput input = new SmoothingInput(owner.id(), owner.selected(),
            old == null ? owner.id() : old.unsmoothedParent(),
            old == null ? owner.selected() : old.unsmoothedChoice(), preset);
        checkBegin(owner.kind() == StageKind.SMOOTH);
        confirmed = false;
        active = new OperationTicket(id, ++sequence, owner.id(), owner.selected(), owner.engine(),
            config.heatmap().alignmentMode(), StageKind.SMOOTH, Optional.of(input));
        pinInputs(active, owner);
        return active;
    }

    /** The caller retains its ordinary Legacy flow if bounded payload capture refuses the root. */
    @Deprecated
    public RefinementSession(Stage root,AlignmentConfig config,String sourceIdentity,Object authority,RefinementLimits limits) {
        this(root,config,sourceIdentity,requireRootAuthority(authority),limits);
    }
    private static RefinementRootAuthority requireRootAuthority(Object authority) {
        if(!(authority instanceof RefinementRootAuthority root))throw new IllegalArgumentException("Factual detached root authority required");
        return root;
    }
    public RefinementSession(Stage root,AlignmentConfig config,String sourceIdentity,RefinementRootAuthority authority,
            RefinementLimits limits) {
        requireEdt();
        if(!config.equals(authority.effectiveConfig())||!sourceIdentity.equals(authority.sourceIdentity()))
            throw new IllegalArgumentException("Session config/source differs from its factual root authority");
        if (root.parent().isPresent()||root.engine()!=authority.request().engine()) throw new IllegalArgumentException("A session requires its factual original root engine");
        this.config = authority.effectiveConfig();
        this.sourceIdentity = authority.sourceIdentity();
        this.authority = Objects.requireNonNull(authority);
        this.limits = Objects.requireNonNull(limits);
        RefusalReason refusal = retentionRefusal(List.of(root));
        if (refusal != null) throw new Refused(refusal);
        history.add(root);
    }
    /** Stable owner identity for factual diagnostics; navigation creates no new operation. */
    public SessionId id(){requireEdt();return id;}
    public AlignmentConfig rootConfig() { requireEdt(); return config; }
    public String sourceIdentity() { requireEdt(); return sourceIdentity; }
    /** Factual detached root; live dataset/source revalidation remains a consumer obligation. */
    public RefinementRootAuthority authority() { requireEdt(); return authority; }
    public Stage current() { requireEdt(); return history.get(cursor); }
    public List<Stage> history() { requireEdt(); return List.copyOf(history); }
    /** Small admitted borrow list; the worker clears it before queued publication can discard old history. */
    long admissionInventoryReferenceBytes() {
        requireEdt();long count=1;
        for(var stage:history)count=Math.addExact(count,stage.candidates().size());
        for(var pins:inputPins.values())for(var stage:pins)count=Math.addExact(count,stage.candidates().size());
        return Math.addExact(1024L,Math.multiplyExact(64L,Math.addExact(count,32L)));
    }
    ArrayList<Map<Object,Long>> admissionInventories() {
        requireEdt();var inventories=new ArrayList<Map<Object,Long>>();inventories.add(authority.charges());
        for(var stage:history)for(var candidate:stage.candidates())inventories.add(candidate.payload().charges());
        for(var pins:inputPins.values())for(var stage:pins)for(var candidate:stage.candidates())inventories.add(candidate.payload().charges());
        return inventories;
    }
    public void select(CandidateId id) {
        requireIdle();
        Stage updated = current().withSelected(id);
        if (!current().selected().equals(id)) confirmed = false;
        history.set(cursor, updated);
    }
    /** Adds a validated root composition without rewriting a retained child's factual parent choice. */
    public void selectRootComposition(Candidate candidate) {
        requireIdle();Objects.requireNonNull(candidate);
        if(cursor!=0||!current().kind().root())throw new IllegalStateException("Root composition selection requires the root cursor");
        var root=current();var candidates=new ArrayList<>(root.candidates());
        var existing=candidates.stream().filter(c->c.id().equals(candidate.id())).findFirst();
        if(existing.isPresent()) {
            var old=existing.get();
            if(!old.immutableIdentity().equals(candidate.immutableIdentity())||!old.nativeCandidateId().equals(candidate.nativeCandidateId())
                    ||!old.sourcePalette().equals(candidate.sourcePalette())||!old.payload().rootReviewState().equals(candidate.payload().rootReviewState()))
                throw new IllegalArgumentException("Root candidate identity belongs to a different composition");
        } else candidates.add(candidate);
        var updated=new Stage(root.id(),root.parent(),root.parentChoice(),root.kind(),root.engine(),candidates,candidate.id(),root.smoothingInput());
        var proposed=new ArrayList<>(history);proposed.set(0,updated);
        var refusal=retentionRefusal(proposed);if(refusal!=null)throw new Refused(refusal);
        history.set(0,updated);confirmed=false;
    }
    public Optional<Stage> back() {
        requireIdle();
        if (cursor == 0) return Optional.empty();
        cursor--;
        confirmed = false;
        return Optional.of(current());
    }
    public Optional<Stage> redo() {
        requireIdle();
        if (cursor + 1 >= history.size()) return Optional.empty();
        cursor++;
        confirmed = false;
        return Optional.of(current());
    }
    public OperationTicket begin(TrackerMode engine) {
        requireIdle();
        if (engine != TrackerMode.CORRIDOR_AWARE && engine != TrackerMode.PROBABILISTIC)
            throw new Refused(RefusalReason.INVALID_ENGINE);
        checkBegin(false);
        confirmed = false;
        active = new OperationTicket(id, ++sequence, current().id(), current().selected(), engine,
            config.heatmap().alignmentMode());
        pinInputs(active, current());
        return active;
    }
    public boolean publish(OperationTicket ticket, Stage stage) {return publish(ticket,stage,()->{});}
    /** Validates first, prepares owned memory/presentation, then commits the still-owned history atomically on EDT.
     * A throwing preparation leaves parent, forward history and input/publication reservations unchanged. */
    public boolean publish(OperationTicket ticket,Stage stage,Runnable beforeCommit) {
        requireEdt();Objects.requireNonNull(beforeCommit);
        if (!owns(ticket)) return false;
        Objects.requireNonNull(stage);
        StageId inputStage = ticket.smoothingInput().map(SmoothingInput::unsmoothedParent).orElse(ticket.parent());
        CandidateId inputChoice = ticket.smoothingInput().map(SmoothingInput::unsmoothedChoice).orElse(ticket.choice());
        if (!stage.parent().equals(Optional.of(inputStage))
                || !stage.parentChoice().equals(Optional.of(inputChoice)) || stage.engine() != ticket.engine()
                || stage.kind() != ticket.kind() || !stage.smoothingInput().equals(ticket.smoothingInput())
                || history.stream().anyMatch(existing -> existing.id().equals(stage.id()))) {
            fail(ticket, Outcome.FAILED);
            return false;
        }
        boolean replacement = ticket.kind() == StageKind.SMOOTH && current().kind() == StageKind.SMOOTH;
        List<Stage> proposed = new ArrayList<>(history.subList(0, replacement ? cursor : cursor + 1));
        proposed.add(stage);
        List<Stage> coexist = new ArrayList<>(history); coexist.add(stage);
        long actualAdditional = retainedBytesWithPins(coexist) - retainedBytes();
        Reservation reservation = reservations.get(ticket);
        RefusalReason refusal = reservation == null ? RefusalReason.RESERVATION_REQUIRED
            : stage.candidates().size() > reservation.candidateCount() ? RefusalReason.CANDIDATE_LIMIT
            : actualAdditional > reservation.additionalBytes() ? RefusalReason.PAYLOAD_LIMIT : retentionRefusal(proposed);
        if (refusal != null) {
            reject(ticket, refusal);
            return false;
        }
        beforeCommit.run();
        if(!owns(ticket))return false;
        // Successful publication removes the forward branch from visible history. Unsettled inputs remain pinned.
        history.clear(); history.addAll(proposed); cursor = history.size() - 1;
        active = null; reservations.remove(ticket); confirmed = false;
        return true;
    }
    public void cancel(OperationTicket ticket) { requireEdt(); if (owns(ticket)) fail(ticket, Outcome.CANCELLED); }
    public void fail(OperationTicket ticket, Outcome outcome) {
        requireEdt();
        if (outcome == Outcome.COMPLETE || outcome == Outcome.PARTIAL) throw new IllegalArgumentException("Failure outcome required");
        if (owns(ticket)) {
            lastFailure = new Failure(ticket, Objects.requireNonNull(outcome), null); active = null;
        }
    }
    public void confirmCurrent() { requireIdle(); confirmed = true; }
    public boolean confirmed() { requireEdt(); return confirmed; }
    public Optional<Failure> lastFailure() { requireEdt(); return Optional.ofNullable(lastFailure); }
    /** Ownership availability only; existing candidate safety and source guards still gate actual Apply. */
    public boolean canApply() { requireEdt(); return !terminal && active == null; }
    public long retainedBytes() { requireEdt(); return retainedBytesWithPins(history); }
    public boolean terminalizeForApply() {
        requireEdt();
        if (!canApply()) return false;
        terminal = true; confirmed = false; sequence++;
        return true;
    }
    @Override public void close() {
        requireEdt();
        terminal = true; active = null; confirmed = false; sequence++;
    }
    private boolean owns(OperationTicket ticket) {
        return !terminal && active != null && active.equals(ticket) && id.equals(ticket.session())
            && ticket.sequence() == sequence && current().id().equals(ticket.parent())
            && current().selected().equals(ticket.choice());
    }
    private RefusalReason retentionRefusal(List<Stage> stages) {
        if (stages.size() > limits.maxStages()) return RefusalReason.STAGE_LIMIT;
        if (stages.stream().anyMatch(stage -> stage.candidates().size() > limits.maxCandidatesPerStage()))
            return RefusalReason.CANDIDATE_LIMIT;
        return retainedBytesWithPins(stages) > limits.maxPayloadBytes() ? RefusalReason.PAYLOAD_LIMIT : null;
    }
    private void reject(OperationTicket ticket, RefusalReason reason) {
        lastFailure = new Failure(ticket, Outcome.RESOURCE_LIMIT, reason); active = null;
    }
    private void checkBegin(boolean replacingSmoothing) {
        if (!replacingSmoothing && cursor + 1 >= limits.maxStages()) throw new Refused(RefusalReason.STAGE_LIMIT);
        RefusalReason refusal = retentionRefusal(history);
        if (refusal != null) throw new Refused(refusal);
    }
    private static boolean addWouldExceed(long retained, long added, long limit) {
        return retained > limit || added > limit - retained;
    }
    private void pinInputs(OperationTicket ticket, Stage owner) {
        Stage input = ticket.smoothingInput().map(binding -> history.stream()
            .filter(stage -> stage.id().equals(binding.unsmoothedParent())).findFirst()
            .orElseThrow(() -> new IllegalStateException("Pinned smoothing input is absent from history"))).orElse(owner);
        inputPins.put(ticket, input == owner ? List.of(owner) : List.of(owner, input));
    }
    private long retainedBytesWithPins(List<Stage> stages) {
        List<Stage> retained = new ArrayList<>(stages);
        inputPins.values().forEach(retained::addAll);
        return retainedBytes(retained);
    }
    private long retainedBytes(List<Stage> stages) {
        IdentityHashMap<Object, Long> charges = new IdentityHashMap<>();
        Map<Object,Long> rootCharges=authority.charges();
        long bytes = authority.retainedBytes();
        for (Stage stage : stages) {
            if (charges.put(stage, 0L) != null) continue;
            bytes = RefinementPayload.add(bytes, stageMetadataBytes(stage.candidates().size()));
            for (Candidate candidate : stage.candidates()) {
                if (charges.put(candidate, 0L) == null) {
                    bytes = RefinementPayload.add(bytes,candidateMetadataBytes(candidate.id(),candidate.immutableIdentity(),candidate.nativeCandidateId(),candidate.sourcePalette(),candidate.correspondence().size()));
                }
                for (Map.Entry<Object, Long> entry : candidate.payload().charges().entrySet()) {
                    Long previous=charges.get(entry.getKey());
                    if(previous==null)previous=rootCharges.get(entry.getKey());
                    long prior=previous==null?0:previous;
                    if(entry.getValue()>prior)bytes=RefinementPayload.add(bytes,entry.getValue()-prior);
                    charges.put(entry.getKey(),Math.max(prior,entry.getValue()));
                }
            }
        }
        return bytes;
    }
    static long stageMetadataBytes(int count){return 256L+8L*count;}
    static long candidateMetadataBytes(CandidateId id,String identity,String nativeId,String palette,int arcs){return 384L+32L*arcs+2L*identity.length()+2L*id.value().length()+2L*nativeId.length()+2L*palette.length();}
    private void requireIdle() {
        requireEdt();
        if (terminal || active != null) throw new IllegalStateException("Session is terminal or an operation is running");
    }
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Refinement history requires the event thread");
    }
}
