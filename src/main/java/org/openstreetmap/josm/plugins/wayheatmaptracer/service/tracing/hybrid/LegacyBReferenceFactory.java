package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBModels.Seed;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBModels.Section;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBModels.Reference;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.MetricCorridorRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

/** Separates temporary reference geometry while retaining original occurrence ownership. */
public final class LegacyBReferenceFactory {
    public static final String REFERENCE_SAMPLER_SUFFIX = "/legacy-reference-v1";

    /** Safe local fallback causes; arbitrary exception text is not a diagnostic reason. */
    public enum UnavailableReason { FROZEN_SECTION, DEGENERATE_REFERENCE, EVIDENCE_COVERAGE, SPATIAL_RESOLUTION_UNAVAILABLE }

    /** An unavailable reference may retain Legacy locally without weakening original authority. */
    public static final class ReferenceUnavailableException extends RuntimeException {
        private final UnavailableReason reason;
        ReferenceUnavailableException(UnavailableReason reason) {
            super(reason.name());
            this.reason = reason;
        }
        public UnavailableReason reason() { return reason; }
    }

    /** Creates fresh reference chainage and sampling identity, retaining the captured original network. */
    public Reference prepare(Section section, TraceRequest originalRequest, EvidenceSnapshot evidence,
            NetworkSnapshot originalNetwork, TraceBudgets remaining, CancellationProbe cancellation) {
        if (section == null || originalRequest == null || evidence == null || originalNetwork == null
                || remaining == null || cancellation == null
                || originalRequest.engine() != TrackerMode.LEGACY_THEN_B
                || originalNetwork.role() != SnapshotRole.CAPTURED_BEFORE
                || !originalRequest.networkSnapshotId().equals(originalNetwork.snapshotId())
                || !originalRequest.networkContentHash().equals(originalNetwork.canonicalHash())
                || !originalRequest.evidenceSnapshotId().equals(evidence.snapshotId())
                || !originalRequest.evidenceContentHash().equals(evidence.canonicalHash())
                || !originalRequest.evidenceResolution().equals(evidence.resolution())
                || section.originalRange().firstIndex() < originalRequest.selectedRange().firstIndex()
                || section.originalRange().lastIndex() > originalRequest.selectedRange().lastIndex()
                || remaining.maximumStatesPerProfile() != originalRequest.budgets().maximumStatesPerProfile()
                || remaining.maximumPairVisits() > originalRequest.budgets().maximumPairVisits()
                || remaining.maximumTransitions() > originalRequest.budgets().maximumTransitions()
                || remaining.maximumRawAlternatives() > originalRequest.budgets().maximumRawAlternatives()
                || remaining.maximumDistinctAlternatives() > originalRequest.budgets().maximumDistinctAlternatives()) {
            throw new IllegalArgumentException("Refinement must retain its original capture and remaining authority");
        }
        cancellation.checkpoint();
        if (section.frozen()) { throw new ReferenceUnavailableException(UnavailableReason.FROZEN_SECTION); }
        boolean hasSegment = false;
        for (int i = 1; i < section.reference().size(); i++) {
            cancellation.checkpoint();
            hasSegment |= section.reference().get(i - 1).distanceTo(section.reference().get(i)) > 1e-9;
        }
        if (!hasSegment) { throw new ReferenceUnavailableException(UnavailableReason.DEGENERATE_REFERENCE); }
        validateOriginalGeometry(section, originalRequest, evidence, originalNetwork, cancellation);
        return prepareSampling(section, originalRequest, evidence, remaining);
    }

    /** Revalidates original geometry ownership at the actual B entry, without rehashing image arrays. */
    public void validateReferenceAuthority(Reference reference, NetworkSnapshot network,
            CancellationProbe cancellation) {
        if (reference == null || network == null || cancellation == null) {
            throw new IllegalArgumentException("A bound reference and original network are required");
        }
        var original = reference.originalRequest();
        var request = reference.request();
        var section = reference.section();
        if (section.frozen() || original.geometryMode()
                    != org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.PRECISE_SHAPE
                || request.geometryMode() != original.geometryMode()
                || !original.networkSnapshotId().equals(network.snapshotId())
                || !original.networkContentHash().equals(network.canonicalHash())
                || section.originalRange().firstIndex() < original.selectedRange().firstIndex()
                || section.originalRange().lastIndex() > original.selectedRange().lastIndex()
                || !request.samplerId().equals(original.samplerId() + REFERENCE_SAMPLER_SUFFIX)
                || !request.parameterHash().equals(parameterHash(original.parameterHash()))
                || request.budgets().maximumStatesPerProfile() != original.budgets().maximumStatesPerProfile()
                || request.budgets().maximumPairVisits() > original.budgets().maximumPairVisits()
                || request.budgets().maximumTransitions() > original.budgets().maximumTransitions()
                || request.budgets().maximumRawAlternatives() > original.budgets().maximumRawAlternatives()
                || request.budgets().maximumDistinctAlternatives() > original.budgets().maximumDistinctAlternatives()
                || !reference.evidence().decisionRegion().equals(MetricCorridorRegion.aroundPolyline(
                    section.reference(), original.permissions().ordinaryRadiusMeters()))) {
            throw new IllegalArgumentException("Refinement lost its original authority or sampling identity");
        }
        validateOriginalGeometry(section, original, reference.originalEvidence(), network, cancellation);
    }

    private static void validateOriginalGeometry(Section section, TraceRequest originalRequest,
            EvidenceSnapshot evidence, NetworkSnapshot originalNetwork, CancellationProbe cancellation) {
        if (!(originalNetwork.primitives().get(originalRequest.selectedWayKey()) instanceof DetachedWay way)
                || way.deleted() || originalRequest.selectedRange().lastIndex() >= way.nodeKeys().size()) {
            throw new IllegalArgumentException("Original selected way is unavailable");
        }
        for (int occurrence = section.originalRange().firstIndex(); ; occurrence++) {
            cancellation.checkpoint();
            var key = way.nodeKeys().get(occurrence);
            if (!(originalNetwork.primitives().get(key) instanceof DetachedNode node) || node.deleted()) {
                throw new IllegalArgumentException("Original selected occurrence is unavailable");
            }
            boolean interior = occurrence != section.originalRange().firstIndex()
                    && occurrence != section.originalRange().lastIndex();
            boolean shared = originalNetwork.incomingReferrerWatches().getOrDefault(key, Set.of()).stream()
                    .anyMatch(referrer -> referrer.type()
                            == org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.Type.WAY
                            && !referrer.equals(originalRequest.selectedWayKey())
                            && (!(originalNetwork.primitives().get(referrer) instanceof DetachedWay other)
                                    || !other.deleted()));
            if (interior && shared) {
                throw new IllegalArgumentException("B refinement intervals must end at shared original occurrences");
            }
            if (originalNetwork.closure().protectedExistingNodeKeys().contains(key) || !node.tags().isEmpty()) {
                Integer vertex = section.originalToVertex().get(occurrence);
                if (vertex == null || !section.reference().get(vertex).equals(
                        evidence.coordinateFrame().toMetric(node.coordinate()))) {
                    throw new IllegalArgumentException("Reference moved an original protected occurrence");
                }
                if (interior) {
                    throw new IllegalArgumentException("Refinement section crosses a protected original anchor");
                }
            }
            if (occurrence == section.originalRange().lastIndex()) { break; }
        }
    }

    private static Reference prepareSampling(Section section, TraceRequest originalRequest,
            EvidenceSnapshot evidence, TraceBudgets remaining) {
        // A chainage table on the original curve is not a spatial pitch field on a different curve.
        if (evidence.resolution().spatialPitchSamples().size() != 1) {
            throw new ReferenceUnavailableException(UnavailableReason.SPATIAL_RESOLUTION_UNAVAILABLE);
        }
        var decision = MetricCorridorRegion.aroundPolyline(section.reference(),
                originalRequest.permissions().ordinaryRadiusMeters());
        if (!evidence.evidenceRegion().containsRegion(decision)) {
            throw new ReferenceUnavailableException(UnavailableReason.EVIDENCE_COVERAGE);
        }
        var referenceEvidence = new EvidenceSnapshot(evidence.snapshotId() + "/" + section.id(),
                evidence.coordinateFrame(), evidence.transform(), evidence.resolution(), decision,
                evidence.evidenceRegion(), evidence.fields(), evidence.resampling(), evidence.sourceIdentity());
        var chainage = new ProbabilisticProfileFactory().profileChainage(section.reference(),
                originalRequest.configuredSampleStepMeters());
        var request = new TraceRequest(originalRequest.selectedWayKey(), section.originalRange(),
                TrackerMode.PROBABILISTIC, originalRequest.geometryMode(), originalRequest.permissions(), remaining,
                referenceEvidence.snapshotId(), referenceEvidence.canonicalHash(),
                originalRequest.networkSnapshotId(), originalRequest.networkContentHash(), originalRequest.settingsHash(),
                parameterHash(originalRequest.parameterHash()),
                originalRequest.samplerId() + REFERENCE_SAMPLER_SUFFIX,
                originalRequest.configuredSampleStepMeters(), chainage, evidence.resolution(),
                java.util.Optional.empty(), originalRequest.disconnectionSource());
        return new Reference(section, request, referenceEvidence, originalRequest, evidence);
    }

    private static String parameterHash(String original) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String field : List.of(original, REFERENCE_SAMPLER_SUFFIX,
                    EvidenceModelParameters.defaults().toString(),
                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic
                            .ProbabilisticTraceEngine.reliabilityPolicyVersionFor(TrackerMode.PROBABILISTIC))) {
                byte[] value = field.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(Integer.toString(value.length).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(value);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Java runtime lacks required SHA-256", unavailable);
        }
    }

    /**
     * Splits at protected occurrences and frozen-range boundaries, never at ordinary
     * movable nodes. Callers supply protection from the captured original authority;
     * this geometric operation grants no new movement permission.
     */
    public List<Section> partition(Seed seed, OccurrenceRange selectedRange,
            Set<Integer> protectedOccurrences, List<OccurrenceRange> frozenRanges) {
        if (seed == null || selectedRange == null || protectedOccurrences == null || frozenRanges == null) {
            throw new IllegalArgumentException("Selected reference and original protection are required");
        }
        var mapping = new TreeMap<>(seed.originalToVertex());
        if (mapping.firstKey() != selectedRange.firstIndex() || mapping.lastKey() != selectedRange.lastIndex()) {
            throw new IllegalArgumentException("Reference does not match the original selected range");
        }
        var cuts = new TreeSet<Integer>();
        cuts.add(selectedRange.firstIndex());
        cuts.add(selectedRange.lastIndex());
        for (Integer occurrence : protectedOccurrences) {
            requireMapped(occurrence, selectedRange, mapping);
            cuts.add(occurrence);
        }
        for (OccurrenceRange frozen : frozenRanges) {
            if (frozen == null) { throw new IllegalArgumentException("Frozen range is required"); }
            requireMapped(frozen.firstIndex(), selectedRange, mapping);
            requireMapped(frozen.lastIndex(), selectedRange, mapping);
            int previousVertex = -1;
            for (int occurrence = frozen.firstIndex(); ; occurrence++) {
                requireMapped(occurrence, selectedRange, mapping);
                int vertex = mapping.get(occurrence);
                if (previousVertex >= 0 && vertex != previousVertex + 1) {
                    throw new IllegalArgumentException("Frozen section contains inserted reference geometry");
                }
                previousVertex = vertex;
                if (occurrence == frozen.lastIndex()) { break; }
            }
            cuts.add(frozen.firstIndex());
            cuts.add(frozen.lastIndex());
        }
        var boundaries = new ArrayList<>(cuts);
        var result = new ArrayList<Section>();
        for (int i = 1; i < boundaries.size(); i++) {
            int first = boundaries.get(i - 1), last = boundaries.get(i);
            int fromVertex = mapping.get(first), toVertex = mapping.get(last);
            var localMapping = new TreeMap<Integer,Integer>();
            mapping.subMap(first, true, last, true).forEach((occurrence, vertex) ->
                    localMapping.put(occurrence, vertex - fromVertex));
            boolean frozen = frozenRanges.stream().anyMatch(range ->
                    Math.max(first, range.firstIndex()) < Math.min(last, range.lastIndex()));
            result.add(new Section(seed.id() + "/section-" + (i - 1), seed.id(),
                    new OccurrenceRange(first, last), seed.points().subList(fromVertex, toVertex + 1),
                    localMapping, frozen));
        }
        return List.copyOf(result);
    }

    private static void requireMapped(Integer occurrence, OccurrenceRange range, Map<Integer,Integer> mapping) {
        if (occurrence == null || occurrence < range.firstIndex() || occurrence > range.lastIndex()
                || !mapping.containsKey(occurrence)) {
            throw new IllegalArgumentException("Protected original occurrence is absent from the reference");
        }
    }
}
