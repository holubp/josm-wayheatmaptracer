package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.function.LongSupplier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceEngineRun;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceWorkUsage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBModels.Reference;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBModels.Section;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBModels.Seed;

/** Serial, bounded Legacy-to-B assembly. Results remain provisional until whole-geometry assessment. */
public final class LegacyBPipeline {
    public enum Outcome { B_REFINED, LEGACY_RETAINED, FROZEN, UNATTEMPTED_BUDGET }
    public enum Source { LEGACY, B }
    /** Cancellation and integrity failures throw; only successfully returned provisional batches have this status. */
    public enum Status { COMPLETE, PARTIAL, RESOURCE_LIMIT, NO_ROUTE }
    public enum Reason {
        B_REFINED, LEGACY_ALTERNATIVE, FIXED_SECTION, NO_ROUTE, DEGENERATE_REFERENCE,
        EVIDENCE_COVERAGE, SPATIAL_RESOLUTION_UNAVAILABLE, WORK_BUDGET, RESOURCE_LIMIT, DEADLINE,
        NO_USABLE_REFINEMENT
    }

    /** One attempt clock, created before Legacy runs and shared by every B section. */
    public static final class Deadline {
        private final LongSupplier clock;
        private final long started;
        private final long duration;

        /** Clock and duration use the same monotonic units (nanoseconds in production). */
        public Deadline(LongSupplier clock, long duration) {
            this.clock = Objects.requireNonNull(clock);
            if (duration <= 0) { throw new IllegalArgumentException("Positive inference duration required"); }
            this.duration = duration;
            this.started = clock.getAsLong();
        }

        public static Deadline production() { return new Deadline(System::nanoTime, 300_000_000_000L); }
        public boolean expired() { return clock.getAsLong() - started >= duration; }

        /** Native B reports actual completed work even when this probe interrupts it. */
        public CancellationProbe probe(CancellationProbe cancellation) {
            Objects.requireNonNull(cancellation);
            return () -> cancellation.cancelled() || expired();
        }
    }

    /** Runner retains the factual native result, optional reference binding, and work exactly once. */
    public record SectionExecution(TraceEngineRun run, Optional<Reference> reference,
            Set<String> eligibleHypothesisIds, long authorityWorkUnits, boolean authorityWorkLimited) {
        public SectionExecution {
            Objects.requireNonNull(run);
            Objects.requireNonNull(reference);
            eligibleHypothesisIds = Set.copyOf(eligibleHypothesisIds);
            if (authorityWorkUnits < 0) throw new IllegalArgumentException("Negative authority proof work");
            if (run.result().engine() != TrackerMode.PROBABILISTIC) {
                throw new IllegalArgumentException("Section must execute the native B engine");
            }
            var actualIds = run.result().hypotheses().stream().map(TraceHypothesis::id)
                    .collect(java.util.stream.Collectors.toSet());
            if (!actualIds.containsAll(eligibleHypothesisIds)) {
                throw new IllegalArgumentException("Eligible hypotheses must belong to the unchanged native result");
            }
        }

        /** Compatibility for callers without additional authority-proof work. */
        public SectionExecution(TraceEngineRun run, Optional<Reference> reference, Set<String> eligibleHypothesisIds) {
            this(run, reference, eligibleHypothesisIds, 0, false);
        }

        /** Compatibility for runners whose native outputs have no additional local quality exclusions. */
        public SectionExecution(TraceEngineRun run, Optional<Reference> reference) {
            this(run, reference, run.result().hypotheses().stream().map(TraceHypothesis::id)
                    .collect(java.util.stream.Collectors.toSet()));
        }
    }

    @FunctionalInterface
    public interface SectionRunner {
        /** Only typed reference unavailability is contained; stale/integrity errors propagate. */
        SectionExecution run(Section section, TraceBudgets remaining, CancellationProbe cancellation);
    }

    public record SectionOutcome(Section section, Outcome outcome, Reason reason,
            Optional<SectionExecution> execution) {
        public SectionOutcome {
            Objects.requireNonNull(section); Objects.requireNonNull(outcome);
            Objects.requireNonNull(reason); Objects.requireNonNull(execution);
        }
    }

    /** Inclusive final vertex indexes, separate from original occurrence indexes. */
    public record VertexRange(int first, int last) {
        public VertexRange {
            if (first < 0 || last <= first) { throw new IllegalArgumentException("Invalid final vertex range"); }
        }
    }

    public record SectionChoice(Section section, VertexRange vertices, Source source,
            Outcome outcome, Reason reason, Optional<String> hypothesisId) {
        public SectionChoice {
            Objects.requireNonNull(section); Objects.requireNonNull(vertices); Objects.requireNonNull(source);
            Objects.requireNonNull(outcome); Objects.requireNonNull(reason); Objects.requireNonNull(hypothesisId);
            if ((source == Source.B) != hypothesisId.isPresent()) {
                throw new IllegalArgumentException("B section must retain its actual hypothesis identity");
            }
        }
    }

    /** One factual interpolation on an unchanged native assembled edge; never new image evidence. */
    public record Subdivision(int nativeEdgeIndex, double fraction) {
        public Subdivision {
            if (nativeEdgeIndex < 0 || !Double.isFinite(fraction) || fraction < 0 || fraction > 1)
                throw new IllegalArgumentException("Invalid native edge subdivision");
        }
        public MetricPoint interpolate(MetricPoint start, MetricPoint end) {
            return new MetricPoint(start.xMeters() + (end.xMeters() - start.xMeters()) * fraction,
                    start.yMeters() + (end.yMeters() - start.yMeters()) * fraction);
        }
    }

    public record Candidate(String id, String parentSeedId, List<MetricPoint> points,
            Map<Integer,Integer> originalToVertex, List<SectionChoice> sections,
            Map<Integer,Subdivision> subdivisions) {
        public Candidate {
            // Reuse ordered mapping and positive-length validation; section metadata grants no authority.
            new Seed(id, points, originalToVertex);
            Objects.requireNonNull(parentSeedId);
            points = List.copyOf(points);
            originalToVertex = Map.copyOf(originalToVertex);
            sections = List.copyOf(sections);
            subdivisions = Map.copyOf(subdivisions);
            int nativeCount = points.size() - subdivisions.size();
            int finalCount = points.size();
            if (nativeCount < 2 || subdivisions.entrySet().stream().anyMatch(entry ->
                    entry.getKey() <= 0 || entry.getKey() >= finalCount - 1
                    || entry.getValue().nativeEdgeIndex() >= nativeCount - 1))
                throw new IllegalArgumentException("Subdivision ownership differs from native geometry");
        }
        public Candidate(String id, String parentSeedId, List<MetricPoint> points,
                Map<Integer,Integer> originalToVertex, List<SectionChoice> sections) {
            this(id, parentSeedId, points, originalToVertex, sections, Map.of());
        }
        /** Native vertices retain their exact coordinates and order, including coincident occurrences. */
        public List<Integer> nativeVertexIndexes() {
            var indexes = new ArrayList<Integer>();
            for (int vertex = 0; vertex < points.size(); vertex++)
                if (!subdivisions.containsKey(vertex)) indexes.add(vertex);
            return List.copyOf(indexes);
        }
    }

    public record Batch(List<Candidate> candidates, List<SectionOutcome> sections,
            TraceWorkUsage usage, boolean truncated, int omittedParents) {
        public Batch {
            candidates = List.copyOf(candidates); sections = List.copyOf(sections);
            Objects.requireNonNull(usage);
            if (omittedParents < 0 || candidates.size() > 6) {
                throw new IllegalArgumentException("Hybrid candidate cap is invalid");
            }
        }

        /** Derived from actual section outcomes, without relabeling a local fallback as whole-attempt failure. */
        public Status status() {
            if (candidates.isEmpty()) { return omittedParents > 0 ? Status.RESOURCE_LIMIT : Status.NO_ROUTE; }
            if (sections.stream().anyMatch(section -> section.outcome() == Outcome.UNATTEMPTED_BUDGET
                    || section.reason() == Reason.DEADLINE || section.reason() == Reason.RESOURCE_LIMIT
                    || section.execution().isPresent() && (section.execution().orElseThrow().run().result().status()
                            == TraceHypothesisSet.Status.RESOURCE_LIMIT
                            || section.execution().orElseThrow().authorityWorkLimited()))) {
                return Status.RESOURCE_LIMIT;
            }
            return truncated || omittedParents > 0
                    || sections.stream().anyMatch(section -> section.outcome() == Outcome.LEGACY_RETAINED)
                    ? Status.PARTIAL : Status.COMPLETE;
        }
    }

    private final Deadline deadline;

    public LegacyBPipeline(Deadline deadline) { this.deadline = Objects.requireNonNull(deadline); }

    /**
     * Preserves each of at most two Legacy parents and builds rank-zero/rank-one refinements.
     * A section's failure cannot discard another section or grant movement across a protected anchor.
     */
    public Batch refine(List<Seed> seeds, List<Section> sections, SectionRunner runner,
            TraceBudgets budgets, CancellationProbe cancellation) {
        return refine(seeds, sections, runner, budgets, cancellation, true, Reason.WORK_BUDGET);
    }

    /** Retains admitted Legacy parents when the root attempt has no remaining B allocation. */
    public Batch retainLegacy(List<Seed> seeds, List<Section> sections, TraceBudgets budgets,
            CancellationProbe cancellation) {
        return retainLegacy(seeds, sections, budgets, Reason.WORK_BUDGET, cancellation);
    }

    public Batch retainLegacy(List<Seed> seeds, List<Section> sections, TraceBudgets budgets,
            Reason reason, CancellationProbe cancellation) {
        if (reason != Reason.WORK_BUDGET && reason != Reason.DEADLINE && reason != Reason.RESOURCE_LIMIT)
            throw new IllegalArgumentException("Legacy-only allocation requires an actual root resource reason");
        return refine(seeds, sections, (section, remaining, probe) -> {
            throw new IllegalStateException("An exhausted root allocation cannot invoke B");
        }, budgets, cancellation, false, reason);
    }

    private Batch refine(List<Seed> seeds, List<Section> sections, SectionRunner runner,
            TraceBudgets budgets, CancellationProbe cancellation, boolean inferenceAllowed, Reason unavailableReason) {
        Objects.requireNonNull(seeds); Objects.requireNonNull(sections); Objects.requireNonNull(runner);
        Objects.requireNonNull(budgets); Objects.requireNonNull(cancellation);
        cancellation.checkpoint();
        validateInputs(seeds, sections);
        var candidates = new ArrayList<Candidate>();
        var outcomes = new ArrayList<SectionOutcome>();
        TraceWorkUsage usage = TraceWorkUsage.none();
        boolean stopped = !inferenceAllowed;
        Reason stoppedReason = unavailableReason;
        boolean truncated = seeds.size() > 2;
        for (Seed seed : seeds.subList(0, Math.min(2, seeds.size()))) {
            var parentOutcomes = new ArrayList<SectionOutcome>();
            var parentSections = sections.stream().filter(s -> s.parentSeedId().equals(seed.id()))
                    .sorted(Comparator.comparingInt(s -> s.originalRange().firstIndex())).toList();
            for (Section section : parentSections) {
                cancellation.checkpoint();
                SectionOutcome outcome;
                if (section.frozen()) {
                    outcome = unavailable(section, Outcome.FROZEN, Reason.FIXED_SECTION);
                } else if (stopped || deadline.expired() || exhausted(usage, budgets)) {
                    outcome = unavailable(section, Outcome.UNATTEMPTED_BUDGET,
                            inferenceAllowed && deadline.expired() ? Reason.DEADLINE : stopped ? stoppedReason : Reason.WORK_BUDGET);
                    truncated = true;
                } else {
                    TraceBudgets remaining = remaining(usage, budgets);
                    try {
                        var execution = Objects.requireNonNull(runner.run(section, remaining, deadline.probe(cancellation)));
                        cancellation.checkpoint();
                        validateExecution(section, execution, remaining);
                        usage = usage.plus(execution.run().usage());
                        var result = execution.run().result();
                        if (result.status() == TraceHypothesisSet.Status.CANCELLED) {
                            if (!deadline.expired()) { throw new CancellationException("B section cancelled"); }
                            stopped = true;
                            stoppedReason = Reason.DEADLINE;
                            outcome = new SectionOutcome(section, Outcome.LEGACY_RETAINED, Reason.DEADLINE,
                                    Optional.of(execution));
                        } else if (!execution.eligibleHypothesisIds().isEmpty()) {
                            outcome = new SectionOutcome(section, Outcome.B_REFINED, Reason.B_REFINED,
                                    Optional.of(execution));
                        } else {
                            boolean resource = result.status() == TraceHypothesisSet.Status.RESOURCE_LIMIT
                                    || execution.authorityWorkLimited();
                            outcome = new SectionOutcome(section, Outcome.LEGACY_RETAINED,
                                    resource ? Reason.RESOURCE_LIMIT : result.hypotheses().isEmpty()
                                            ? Reason.NO_ROUTE : Reason.NO_USABLE_REFINEMENT, Optional.of(execution));
                        }
                        // A native resource refusal is terminal for starting new inference. Retain its output.
                        if (result.status() == TraceHypothesisSet.Status.RESOURCE_LIMIT || execution.authorityWorkLimited()) {
                            stopped = true; stoppedReason = Reason.RESOURCE_LIMIT;
                        }
                        truncated |= result.truncated() || stopped;
                    } catch (LegacyBReferenceFactory.ReferenceUnavailableException unavailable) {
                        outcome = unavailable(section, Outcome.LEGACY_RETAINED,
                                switch (unavailable.reason()) {
                                    case FROZEN_SECTION -> Reason.FIXED_SECTION;
                                    case DEGENERATE_REFERENCE -> Reason.DEGENERATE_REFERENCE;
                                    case EVIDENCE_COVERAGE -> Reason.EVIDENCE_COVERAGE;
                                    case SPATIAL_RESOLUTION_UNAVAILABLE -> Reason.SPATIAL_RESOLUTION_UNAVAILABLE;
                                });
                    }
                }
                outcomes.add(outcome); parentOutcomes.add(outcome);
            }
            candidates.add(assemble(seed, parentOutcomes, -1));
            for (int rank = 0; rank < 2; rank++) {
                var assembled = assemble(seed, parentOutcomes, rank);
                boolean duplicate = candidates.stream().anyMatch(existing -> existing.parentSeedId().equals(seed.id())
                        && existing.points().equals(assembled.points())
                        && existing.originalToVertex().equals(assembled.originalToVertex()));
                if (!duplicate) { candidates.add(assembled); }
            }
        }
        cancellation.checkpoint();
        return new Batch(candidates, outcomes, usage, truncated, Math.max(0, seeds.size() - 2));
    }

    private static SectionOutcome unavailable(Section section, Outcome outcome, Reason reason) {
        return new SectionOutcome(section, outcome, reason, Optional.empty());
    }

    private static boolean exhausted(TraceWorkUsage usage, TraceBudgets budget) {
        return usage.pairVisits() >= Math.min(8_000_000L, budget.maximumPairVisits())
                || usage.transitions() >= Math.min(128_000_000L, budget.maximumTransitions())
                || usage.rawAlternatives() >= budget.maximumRawAlternatives()
                || usage.distinctAlternatives() >= budget.maximumDistinctAlternatives();
    }

    private static TraceBudgets remaining(TraceWorkUsage usage, TraceBudgets budget) {
        int raw = Math.min(2, budget.maximumRawAlternatives() - usage.rawAlternatives());
        int distinct = Math.min(raw, Math.min(2, budget.maximumDistinctAlternatives() - usage.distinctAlternatives()));
        return new TraceBudgets(Math.min(96, budget.maximumStatesPerProfile()),
                Math.min(8_000_000L, budget.maximumPairVisits()) - usage.pairVisits(),
                Math.min(128_000_000L, budget.maximumTransitions()) - usage.transitions(), raw, distinct);
    }

    private static void validateInputs(List<Seed> seeds, List<Section> sections) {
        var ids = new HashSet<String>();
        for (Seed seed : seeds) {
            if (!ids.add(seed.id())) { throw new IllegalArgumentException("Duplicate Legacy parent identity"); }
            var own = sections.stream().filter(s -> s.parentSeedId().equals(seed.id()))
                    .sorted(Comparator.comparingInt(s -> s.originalRange().firstIndex())).toList();
            if (own.isEmpty()) { throw new IllegalArgumentException("Legacy parent has no complete partition"); }
            int previousOccurrence = new TreeMap<>(seed.originalToVertex()).firstKey();
            int previousVertex = 0;
            var sectionIds = new HashSet<String>();
            for (Section section : own) {
                if (!sectionIds.add(section.id()) || section.originalRange().firstIndex() != previousOccurrence) {
                    throw new IllegalArgumentException("Legacy partition is duplicated or discontinuous");
                }
                Integer end = seed.originalToVertex().get(section.originalRange().lastIndex());
                if (end == null || end <= previousVertex
                        || !section.reference().equals(seed.points().subList(previousVertex, end + 1))) {
                    throw new IllegalArgumentException("Section does not own its actual Legacy geometry");
                }
                var expected = new TreeMap<Integer,Integer>();
                for (var entry : seed.originalToVertex().entrySet()) {
                    if (entry.getValue() >= previousVertex && entry.getValue() <= end) {
                        expected.put(entry.getKey(), entry.getValue() - previousVertex);
                    }
                }
                if (!section.originalToVertex().equals(expected)) {
                    throw new IllegalArgumentException("Section occurrence mapping differs from its parent");
                }
                previousOccurrence = section.originalRange().lastIndex(); previousVertex = end;
            }
            if (previousVertex != seed.points().size() - 1) {
                throw new IllegalArgumentException("Legacy partition does not cover the complete parent");
            }
        }
        if (sections.stream().anyMatch(s -> !ids.contains(s.parentSeedId()))) {
            throw new IllegalArgumentException("Foreign Legacy section ownership");
        }
    }

    private static void validateExecution(Section section, SectionExecution execution, TraceBudgets remaining) {
        var run = execution.run();
        var usage = run.usage();
        if (usage.pairVisits() > remaining.maximumPairVisits() || usage.transitions() > remaining.maximumTransitions()
                || usage.rawAlternatives() > remaining.maximumRawAlternatives()
                || usage.distinctAlternatives() > remaining.maximumDistinctAlternatives()) {
            throw new IllegalArgumentException("Native B section exceeded its allocated remaining budget");
        }
        if (execution.reference().isPresent() && !execution.reference().orElseThrow().section().equals(section)) {
            throw new IllegalArgumentException("Native B result belongs to another reference section");
        }
        for (TraceHypothesis hypothesis : run.result().hypotheses()) {
            if (section.originalToVertex().size() != 2 || !hypothesis.points().get(0).equals(section.reference().get(0))
                    || !hypothesis.points().get(hypothesis.points().size()-1)
                            .equals(section.reference().get(section.reference().size()-1))) {
                throw new IllegalArgumentException("B refinement lost protected occurrence anchors");
            }
        }
    }

    private static Candidate assemble(Seed seed, List<SectionOutcome> outcomes, int rank) {
        var points = new ArrayList<MetricPoint>();
        var mapping = new TreeMap<Integer,Integer>();
        var choices = new ArrayList<SectionChoice>();
        for (SectionOutcome outcome : outcomes) {
            Section section = outcome.section();
            TraceHypothesis hypothesis = null;
            if (rank >= 0 && outcome.outcome() == Outcome.B_REFINED) {
                var execution = outcome.execution().orElseThrow();
                var alternatives = execution.run().result().hypotheses().stream()
                        .filter(item -> execution.eligibleHypothesisIds().contains(item.id())).toList();
                hypothesis = alternatives.get(rank < alternatives.size() ? rank : 0);
            }
            var geometry = hypothesis == null ? section.reference() : hypothesis.points();
            int first = points.isEmpty() ? 0 : points.size()-1;
            if (points.isEmpty()) { points.addAll(geometry); }
            else { points.addAll(geometry.subList(1, geometry.size())); }
            int last = points.size()-1;
            if (hypothesis == null) {
                section.originalToVertex().forEach((original, vertex) -> mapping.put(original, first+vertex));
            } else {
                mapping.put(section.originalRange().firstIndex(), first);
                mapping.put(section.originalRange().lastIndex(), last);
            }
            var source = hypothesis == null ? Source.LEGACY : Source.B;
            var actualOutcome = hypothesis != null ? Outcome.B_REFINED : outcome.outcome() == Outcome.B_REFINED
                    ? Outcome.LEGACY_RETAINED : outcome.outcome();
            var reason = hypothesis == null && outcome.outcome() == Outcome.B_REFINED
                    ? Reason.LEGACY_ALTERNATIVE : outcome.reason();
            choices.add(new SectionChoice(section, new VertexRange(first,last), source, actualOutcome, reason,
                    hypothesis == null ? Optional.empty() : Optional.of(hypothesis.id())));
        }
        return new Candidate(seed.id() + (rank < 0 ? "/legacy" : "/refined-"+rank), seed.id(), points, mapping, choices);
    }
}
