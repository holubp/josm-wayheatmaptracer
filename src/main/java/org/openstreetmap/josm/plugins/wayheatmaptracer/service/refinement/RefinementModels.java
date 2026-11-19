package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;

/** Immutable value contracts shared by preview-only refinement adapters and history. */
public final class RefinementModels {
    private RefinementModels() { }
    public record SessionId(UUID value) { public SessionId { Objects.requireNonNull(value); } }
    public record StageId(UUID value) { public StageId { Objects.requireNonNull(value); } }
    public record CandidateId(String value) {
        public CandidateId { if (value == null || value.isBlank()) throw new IllegalArgumentException("Candidate id required"); }
    }
    public enum StageKind { ROOT_LEGACY, REFINE_A, REFINE_B, SMOOTH }
    public enum Outcome { COMPLETE, PARTIAL, NO_USABLE_RESULT, CANCELLED, STALE, RESOURCE_LIMIT, FAILED }
    public enum RefusalReason { NON_LEGACY_ROOT, STAGE_LIMIT, CANDIDATE_LIMIT, PAYLOAD_LIMIT, INVALID_ENGINE, RESERVATION_REQUIRED }
    /** Interior vertices belong to the outgoing edge at fraction zero; only the final vertex uses one. */
    public record ParentArc(int originalOccurrence, int parentEdge, double fraction) {
        public ParentArc {
            if (originalOccurrence < 0 || parentEdge < 0 || !Double.isFinite(fraction) || fraction < 0 || fraction > 1)
                throw new IllegalArgumentException("Invalid original-occurrence/parent-edge correspondence");
        }
    }
    public record SmoothingInput(StageId owner, CandidateId choice, StageId unsmoothedParent,
            CandidateId unsmoothedChoice, String preset) {
        public SmoothingInput {
            Objects.requireNonNull(owner); Objects.requireNonNull(choice);
            Objects.requireNonNull(unsmoothedParent); Objects.requireNonNull(unsmoothedChoice);
            if (!Set.of("Medium", "Strong").contains(preset)) throw new IllegalArgumentException("Explicit smoothing preset required");
        }
    }
    public record Candidate(CandidateId id, RefinementPayload payload, List<ParentArc> correspondence,
            String immutableIdentity) {
        public Candidate {
            Objects.requireNonNull(id); Objects.requireNonNull(payload);
            if (immutableIdentity == null || immutableIdentity.isBlank()) throw new IllegalArgumentException("Immutable identity required");
            correspondence = List.copyOf(correspondence);
            ParentArc previous = null;
            for (int index = 0; index < correspondence.size(); index++) {
                ParentArc arc = correspondence.get(index);
                if (previous != null && (arc.originalOccurrence() <= previous.originalOccurrence()
                        || arc.parentEdge() < previous.parentEdge()
                        || arc.parentEdge() == previous.parentEdge() && arc.fraction() <= previous.fraction()))
                    throw new IllegalArgumentException("Correspondence must traverse occurrences and parent monotonically");
                if (arc.fraction() == 1 && index != correspondence.size() - 1)
                    throw new IllegalArgumentException("Interior parent vertices require outgoing-edge ownership");
                previous = arc;
            }
        }
        public Candidate(CandidateId id, AlignmentResult result, List<ParentArc> correspondence, String identity) {
            this(id, RefinementPayload.capture(result, RefinementLimits.defaults().maxPayloadBytes()), correspondence, identity);
        }
        public AlignmentResult result() { return payload.result(); }
    }
    public record Stage(StageId id, Optional<StageId> parent, Optional<CandidateId> parentChoice,
            StageKind kind, TrackerMode engine, List<Candidate> candidates, CandidateId selected,
            Optional<SmoothingInput> smoothingInput) {
        public Stage {
            Objects.requireNonNull(id); Objects.requireNonNull(parent); Objects.requireNonNull(parentChoice);
            Objects.requireNonNull(kind); Objects.requireNonNull(engine); Objects.requireNonNull(selected);
            Objects.requireNonNull(smoothingInput);
            candidates = List.copyOf(candidates);
            Set<CandidateId> ids = new HashSet<>();
            for (Candidate candidate : candidates)
                if (!ids.add(candidate.id())) throw new IllegalArgumentException("Duplicate candidate id");
            if (!ids.contains(selected)) throw new IllegalArgumentException("Selected candidate missing");
            if (parent.isPresent() != parentChoice.isPresent()) throw new IllegalArgumentException("Incomplete parent binding");
            if ((kind == StageKind.ROOT_LEGACY) != parent.isEmpty()) throw new IllegalArgumentException("Invalid root lineage");
            if ((kind == StageKind.SMOOTH) != smoothingInput.isPresent()) throw new IllegalArgumentException("Invalid smoothing lineage");
            if (kind == StageKind.REFINE_A && engine != TrackerMode.CORRIDOR_AWARE
                    || kind == StageKind.REFINE_B && engine != TrackerMode.PROBABILISTIC)
                throw new IllegalArgumentException("Stage kind must match native engine");
            if (smoothingInput.isPresent() && (!parent.orElseThrow().equals(smoothingInput.get().unsmoothedParent())
                    || !parentChoice.orElseThrow().equals(smoothingInput.get().unsmoothedChoice())))
                throw new IllegalArgumentException("Smoothing history must retain the exact unsmoothed input");
        }
        public Stage withSelected(CandidateId choice) {
            return new Stage(id, parent, parentChoice, kind, engine, candidates, choice, smoothingInput);
        }
        public static Stage root(List<Candidate> candidates, CandidateId selected) {
            return new Stage(new StageId(UUID.randomUUID()), Optional.empty(), Optional.empty(),
                StageKind.ROOT_LEGACY, TrackerMode.LEGACY_V02, candidates, selected, Optional.empty());
        }
        public static Stage child(OperationTicket ticket, List<Candidate> candidates, CandidateId selected) {
            return new Stage(new StageId(UUID.randomUUID()),
                Optional.of(ticket.smoothingInput().map(SmoothingInput::unsmoothedParent).orElse(ticket.parent())),
                Optional.of(ticket.smoothingInput().map(SmoothingInput::unsmoothedChoice).orElse(ticket.choice())),
                ticket.kind(), ticket.engine(), candidates, selected, ticket.smoothingInput());
        }
    }
    public record OperationTicket(SessionId session, long sequence, StageId parent, CandidateId choice,
            TrackerMode engine, AlignmentMode mode, StageKind kind, Optional<SmoothingInput> smoothingInput) {
        public OperationTicket(SessionId session, long sequence, StageId parent, CandidateId choice,
                TrackerMode engine, AlignmentMode mode) {
            this(session, sequence, parent, choice, engine, mode,
                engine == TrackerMode.CORRIDOR_AWARE ? StageKind.REFINE_A : StageKind.REFINE_B, Optional.empty());
        }
        public OperationTicket {
            Objects.requireNonNull(session); Objects.requireNonNull(parent); Objects.requireNonNull(choice);
            Objects.requireNonNull(engine); Objects.requireNonNull(mode);
            Objects.requireNonNull(kind); Objects.requireNonNull(smoothingInput);
            if (sequence <= 0) throw new IllegalArgumentException("Positive operation sequence required");
            if (kind == StageKind.ROOT_LEGACY || (kind == StageKind.SMOOTH) != smoothingInput.isPresent()
                    || kind == StageKind.REFINE_A && engine != TrackerMode.CORRIDOR_AWARE
                    || kind == StageKind.REFINE_B && engine != TrackerMode.PROBABILISTIC)
                throw new IllegalArgumentException("Operation kind and native engine/input binding disagree");
            if (smoothingInput.isPresent() && (!parent.equals(smoothingInput.get().owner())
                    || !choice.equals(smoothingInput.get().choice())))
                throw new IllegalArgumentException("Smoothing operation must pin the exact current owner choice");
        }
    }
    public record Failure(OperationTicket ticket, Outcome outcome, RefusalReason reason) { }
    /** Immutable pre-allocation receipt; worker code may use its byte ceiling without touching EDT history. */
    public record Reservation(OperationTicket ticket, int candidateCount, long additionalBytes) {
        public Reservation {
            Objects.requireNonNull(ticket);
            if (candidateCount < 1 || additionalBytes < 1) throw new IllegalArgumentException("Positive reservation required");
        }
    }
}
