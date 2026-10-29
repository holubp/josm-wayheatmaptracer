package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.Finding;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.*;

/** Common physical assessment of each exact assembled preview, independent of B's internal objective. */
public final class LegacyBAssessor {
    /** Original frozen authority and image; incident geometry must use proposed shared-node coordinates. */
    public record AssessmentContext(TraceRequest request, NetworkSnapshot network, EvidenceSnapshot evidence,
            String fieldName, List<List<MetricPoint>> proposedIncidentGeometry,
            Map<Integer,Set<FinalRoutePointId.ExistingWayNodeOccurrence>> sharedContacts) {
        public AssessmentContext {
            if (request == null || network == null || evidence == null || fieldName == null
                    || !evidence.fields().containsKey(fieldName) || proposedIncidentGeometry == null
                    || request.engine() != TrackerMode.LEGACY_THEN_B
                    || request.geometryMode() != AlignmentMode.PRECISE_SHAPE
                    || network.role() != SnapshotRole.CAPTURED_BEFORE
                    || !request.networkSnapshotId().equals(network.snapshotId())
                    || !request.networkContentHash().equals(network.canonicalHash())
                    || !request.evidenceSnapshotId().equals(evidence.snapshotId())
                    || !request.evidenceContentHash().equals(evidence.canonicalHash())
                    || !request.evidenceResolution().equals(evidence.resolution())) {
                throw new IllegalArgumentException("Hybrid assessment lacks its original frozen authority");
            }
            proposedIncidentGeometry = proposedIncidentGeometry.stream().map(List::copyOf).toList();
            var contacts = new LinkedHashMap<Integer,Set<FinalRoutePointId.ExistingWayNodeOccurrence>>();
            sharedContacts.forEach((index, identities) -> contacts.put(index, Set.copyOf(identities)));
            sharedContacts = Map.copyOf(contacts);
        }

        /** Compatibility for callers without independently captured shared-node identity evidence. */
        public AssessmentContext(TraceRequest request, NetworkSnapshot network, EvidenceSnapshot evidence,
                String fieldName, List<List<MetricPoint>> proposedIncidentGeometry) {
            this(request, network, evidence, fieldName, proposedIncidentGeometry, Map.of());
        }
    }

    /** Native posterior remains absent for Legacy/frozen rows; image support is measured independently. */
    public record SectionSupport(SectionChoice choice, double totalLengthMeters,
            OptionalDouble directlySupportedLengthMeters, OptionalDouble nativeBPosterior, List<Finding> findings) {
        public SectionSupport {
            Objects.requireNonNull(choice); Objects.requireNonNull(directlySupportedLengthMeters);
            Objects.requireNonNull(nativeBPosterior); findings = List.copyOf(findings);
            if (!Double.isFinite(totalLengthMeters) || totalLengthMeters < 0
                    || directlySupportedLengthMeters.isPresent()
                        && (!Double.isFinite(directlySupportedLengthMeters.orElseThrow())
                            || directlySupportedLengthMeters.orElseThrow() < 0
                            || directlySupportedLengthMeters.orElseThrow() > totalLengthMeters+1e-9)
                    || nativeBPosterior.isPresent()
                        && (choice.source() != Source.B || !Double.isFinite(nativeBPosterior.orElseThrow())
                            || nativeBPosterior.orElseThrow() < 0 || nativeBPosterior.orElseThrow() > 1)) {
                throw new IllegalArgumentException("Hybrid section measurement is contradictory");
            }
        }
    }

    /** Complete final identity and measurements; this model alone is not permission to mutate OSM. */
    public record AssessedCandidate(Candidate candidate, List<FinalRoutePointId> pointIds,
            Map<FinalRoutePointId,MetricPoint> assignments, FinalGeometryEvaluator.Result quality,
            List<SectionSupport> sections, OptionalDouble directlySupportedLengthMeters) {
        public AssessedCandidate {
            Objects.requireNonNull(candidate); Objects.requireNonNull(quality);
            Objects.requireNonNull(directlySupportedLengthMeters);
            pointIds = List.copyOf(pointIds); assignments = Map.copyOf(assignments); sections = List.copyOf(sections);
            if (!quality.id().equals(candidate.id()) || pointIds.size() != candidate.points().size()
                    || assignments.size() != pointIds.size() || sections.size() != candidate.sections().size()) {
                throw new IllegalArgumentException("Hybrid assessment and final identity disagree");
            }
            for (int vertex=0; vertex<pointIds.size(); vertex++) {
                if (!candidate.points().get(vertex).equals(assignments.get(pointIds.get(vertex)))) {
                    throw new IllegalArgumentException("Hybrid final assignments differ from its assessed geometry");
                }
            }
            for (int row=0; row<sections.size(); row++) {
                if (!sections.get(row).choice().equals(candidate.sections().get(row))) {
                    throw new IllegalArgumentException("Hybrid section measurements belong to another candidate");
                }
            }
        }
    }

    /**
     * Every candidate, including each Legacy parent, receives the same complete-geometry evaluator.
     * Candidate ordering is retained for the caller's common physical ranking; no refinement bonus,
     * average posterior, summed B objective, or hidden extra trace is introduced here.
     */
    public List<AssessedCandidate> assess(Batch batch, AssessmentContext context, CancellationProbe cancellation) {
        Objects.requireNonNull(batch); Objects.requireNonNull(context); Objects.requireNonNull(cancellation);
        cancellation.checkpoint();
        var image = ImageCostField.fromEvidence(context.evidence(), context.fieldName());
        if (!(context.network().primitives().get(context.request().selectedWayKey()) instanceof DetachedWay way)
                || way.deleted() || context.request().selectedRange().lastIndex() >= way.nodeKeys().size()) {
            throw new IllegalArgumentException("Hybrid selected-way authority is unavailable");
        }
        var result = new ArrayList<AssessedCandidate>();
        for (Candidate candidate : batch.candidates()) {
            cancellation.checkpoint();
            var pointIds = new ArrayList<FinalRoutePointId>();
            for (int i=0; i<candidate.points().size(); i++) {
                pointIds.add(new FinalRoutePointId.GeneratedCandidatePoint(candidate.id(), i));
            }
            var protectedAssignments = new LinkedHashMap<Integer,MetricPoint>();
            for (var entry : candidate.originalToVertex().entrySet()) {
                int occurrence = entry.getKey();
                if (occurrence < context.request().selectedRange().firstIndex()
                        || occurrence > context.request().selectedRange().lastIndex()) {
                    throw new IllegalArgumentException("Hybrid occurrence lies outside the original selection");
                }
                var nodeKey = way.nodeKeys().get(occurrence);
                pointIds.set(entry.getValue(), new FinalRoutePointId.ExistingWayNodeOccurrence(
                        way.key(), nodeKey, occurrence));
            }
            for (int occurrence=context.request().selectedRange().firstIndex();
                    occurrence<=context.request().selectedRange().lastIndex(); occurrence++) {
                var nodeKey = way.nodeKeys().get(occurrence);
                if (!(context.network().primitives().get(nodeKey) instanceof DetachedNode node) || node.deleted()) {
                    throw new IllegalArgumentException("Original Hybrid node authority is incomplete");
                }
                if (context.network().closure().protectedExistingNodeKeys().contains(nodeKey) || !node.tags().isEmpty()) {
                    var vertex = candidate.originalToVertex().get(occurrence);
                    if (vertex == null) { throw new IllegalArgumentException("Hybrid omitted a protected occurrence"); }
                    protectedAssignments.put(vertex, context.evidence().coordinateFrame().toMetric(node.coordinate()));
                }
            }
            validatePartition(candidate, batch, context);
            for (SectionChoice choice : candidate.sections()) {
                if (choice.section().frozen()) {
                    for (var entry : choice.section().originalToVertex().entrySet()) {
                        var node = (DetachedNode) context.network().primitives().get(way.nodeKeys().get(entry.getKey()));
                        protectedAssignments.put(choice.vertices().first()+entry.getValue(),
                                context.evidence().coordinateFrame().toMetric(node.coordinate()));
                    }
                }
            }
            boolean ambiguous = false, truncated = batch.truncated();
            for (SectionChoice choice : candidate.sections()) {
                if (choice.source() == Source.B) {
                    var execution = execution(batch, choice);
                    ambiguous |= execution.run().result().status() == TraceHypothesisSet.Status.AMBIGUOUS;
                    truncated |= execution.run().result().truncated();
                }
            }
            var request = new FinalGeometryEvaluator.Request(candidate.id(), candidate.points(), pointIds, image,
                    context.evidence().resolution().effectivePitchMeters(), protectedAssignments,
                    context.proposedIncidentGeometry(), false, ambiguous, truncated, false)
                    .withSharedContacts(context.sharedContacts());
            var quality = new FinalGeometryEvaluator().evaluate(request, cancellation);
            cancellation.checkpoint();
            var support = FinalGeometryEvaluator.inspectSupport(candidate.points(), image,
                    context.evidence().resolution().effectivePitchMeters(), cancellation);
            var rows = new ArrayList<SectionSupport>();
            boolean allAvailable = true;
            for (SectionChoice choice : candidate.sections()) {
                double total = 0, direct = 0;
                boolean available = true;
                int previousEdge = -1;
                double distanceAlongEdge = 0;
                for (var sample : support.samples()) {
                    if (sample.edgeIndex() < choice.vertices().first() || sample.edgeIndex() >= choice.vertices().last()) {
                        continue;
                    }
                    cancellation.checkpoint();
                    if (previousEdge != sample.edgeIndex()) { previousEdge = sample.edgeIndex(); distanceAlongEdge = 0; }
                    var a = candidate.points().get(sample.edgeIndex());
                    var b = candidate.points().get(sample.edgeIndex()+1);
                    double length = a.distanceTo(b);
                    double fraction = length == 0 ? 0 : (distanceAlongEdge + sample.pieceMeters()/2)/length;
                    available &= image.supports(new MetricPoint(a.xMeters()+(b.xMeters()-a.xMeters())*fraction,
                            a.yMeters()+(b.yMeters()-a.yMeters())*fraction));
                    distanceAlongEdge += sample.pieceMeters();
                    total += sample.pieceMeters();
                    if (sample.directlyLocalized()) { direct += sample.pieceMeters(); }
                }
                allAvailable &= available;
                OptionalDouble posterior = OptionalDouble.empty();
                if (choice.source() == Source.B) {
                    posterior = execution(batch,choice).run().result().hypotheses().stream()
                            .filter(h -> h.id().equals(choice.hypothesisId().orElseThrow()))
                            .findFirst().orElseThrow().posteriorProbability();
                }
                var findings = quality.findings().stream().filter(finding ->
                        finding.firstVertex() <= choice.vertices().last()
                                && finding.lastVertex() >= choice.vertices().first()).toList();
                rows.add(new SectionSupport(choice, total, available ? OptionalDouble.of(direct) : OptionalDouble.empty(),
                        posterior, findings));
            }
            var assignments = new LinkedHashMap<FinalRoutePointId,MetricPoint>();
            for (int vertex=0; vertex<pointIds.size(); vertex++) {
                assignments.put(pointIds.get(vertex), candidate.points().get(vertex));
            }
            result.add(new AssessedCandidate(candidate, pointIds, assignments, quality, rows,
                    allAvailable ? OptionalDouble.of(support.directlySupportedLengthMeters()) : OptionalDouble.empty()));
        }
        cancellation.checkpoint();
        return List.copyOf(result);
    }

    private static SectionExecution execution(Batch batch, SectionChoice choice) {
        return batch.sections().stream().filter(outcome -> outcome.section().equals(choice.section()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Missing native section execution"))
                .execution().orElseThrow(() -> new IllegalArgumentException("B section has no native result"));
    }

    private static void validatePartition(Candidate candidate, Batch batch, AssessmentContext context) {
        var selected = context.request().selectedRange();
        var way = (DetachedWay) context.network().primitives().get(context.request().selectedWayKey());
        int next = 0;
        int nextOriginal = selected.firstIndex();
        for (SectionChoice choice : candidate.sections()) {
            if (!choice.section().parentSeedId().equals(candidate.parentSeedId()) || choice.vertices().first() != next
                    || choice.vertices().last() >= candidate.points().size()
                    || choice.section().originalRange().firstIndex() != nextOriginal
                    || choice.section().originalRange().lastIndex() > selected.lastIndex()) {
                throw new IllegalArgumentException("Hybrid final section partition is incomplete");
            }
            List<MetricPoint> factual = choice.section().reference();
            var expectedMapping = new LinkedHashMap<Integer,Integer>();
            if (choice.source() == Source.B) {
                if (choice.section().frozen()) { throw new IllegalArgumentException("B cannot own a frozen section"); }
                factual = execution(batch, choice).run().result().hypotheses().stream()
                        .filter(h -> h.id().equals(choice.hypothesisId().orElseThrow())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Missing native B hypothesis")).points();
                expectedMapping.put(choice.section().originalRange().firstIndex(),choice.vertices().first());
                expectedMapping.put(choice.section().originalRange().lastIndex(),choice.vertices().last());
            } else {
                choice.section().originalToVertex().forEach((original,vertex) ->
                        expectedMapping.put(original,choice.vertices().first()+vertex));
            }
            if (!factual.equals(candidate.points().subList(choice.vertices().first(),choice.vertices().last()+1))) {
                throw new IllegalArgumentException("Hybrid final geometry differs from its claimed native section");
            }
            var actualMapping = new LinkedHashMap<Integer,Integer>();
            candidate.originalToVertex().forEach((original,vertex) -> {
                if (original >= choice.section().originalRange().firstIndex()
                        && original <= choice.section().originalRange().lastIndex()) {
                    actualMapping.put(original,vertex);
                }
            });
            for (var extra : actualMapping.entrySet()) {
                if (expectedMapping.containsKey(extra.getKey())) continue;
                var key = way.nodeKeys().get(extra.getKey());
                if (!context.network().closure().movableExistingNodeKeys().contains(key)
                        || context.network().closure().protectedExistingNodeKeys().contains(key)
                        || !(context.network().primitives().get(key) instanceof DetachedNode node) || !node.tags().isEmpty()
                        || context.network().incomingReferrerWatches().getOrDefault(key, Set.of()).stream()
                                .anyMatch(referrer -> !referrer.equals(way.key()))
                        || extra.getValue() <= choice.vertices().first() || extra.getValue() >= choice.vertices().last()) {
                    throw new IllegalArgumentException("Hybrid reconstructed occurrence lacks ordinary movement authority");
                }
                expectedMapping.put(extra.getKey(), extra.getValue());
            }
            if (!expectedMapping.equals(actualMapping)) {
                throw new IllegalArgumentException("Hybrid final occurrence assignments differ from section ownership");
            }
            next = choice.vertices().last();
            nextOriginal = choice.section().originalRange().lastIndex();
        }
        if (next != candidate.points().size()-1 || nextOriginal != selected.lastIndex()) {
            throw new IllegalArgumentException("Hybrid final geometry is unowned");
        }
    }
}
