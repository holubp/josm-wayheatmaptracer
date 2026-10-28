package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;

/** Detached temporary geometry; original occurrence identities never become preview vertex identities. */
public final class LegacyBModels {
    private LegacyBModels() { }

    /** Derived reference sampling with explicit lineage to the factual original image capture. */
    public record Reference(Section section, TraceRequest request, EvidenceSnapshot evidence,
            TraceRequest originalRequest, EvidenceSnapshot originalEvidence) {
        public Reference {
            if (section == null || request == null || evidence == null || originalRequest == null
                    || originalEvidence == null || originalRequest.engine() != TrackerMode.LEGACY_THEN_B
                    || request.engine() != TrackerMode.PROBABILISTIC
                    || !request.selectedRange().equals(section.originalRange())
                    || !request.selectedWayKey().equals(originalRequest.selectedWayKey())
                    || !request.networkSnapshotId().equals(originalRequest.networkSnapshotId())
                    || !request.networkContentHash().equals(originalRequest.networkContentHash())
                    || !request.settingsHash().equals(originalRequest.settingsHash())
                    || !request.permissions().equals(originalRequest.permissions())
                    || !java.util.Objects.equals(request.disconnectionSource(), originalRequest.disconnectionSource())
                    || !originalRequest.evidenceSnapshotId().equals(originalEvidence.snapshotId())
                    || !originalRequest.evidenceContentHash().equals(originalEvidence.canonicalHash())
                    || !evidence.fields().equals(originalEvidence.fields())
                    || !evidence.coordinateFrame().equals(originalEvidence.coordinateFrame())
                    || !evidence.transform().equals(originalEvidence.transform())
                    || !evidence.resolution().equals(originalEvidence.resolution())
                    || !evidence.resampling().equals(originalEvidence.resampling())
                    || !evidence.evidenceRegion().equals(originalEvidence.evidenceRegion())
                    || !evidence.sourceIdentity().equals(originalEvidence.sourceIdentity())
                    || !request.evidenceSnapshotId().equals(evidence.snapshotId())
                    || !request.evidenceContentHash().equals(evidence.canonicalHash())) {
                throw new IllegalArgumentException("Refinement request does not match its factual reference evidence");
            }
        }

        /** Actual original capture digest, never an independent self-asserted provenance string. */
        public String originalEvidenceHash() { return originalEvidence.canonicalHash(); }
    }

    /** One Legacy alternative with ordered original anchor ownership. */
    public record Seed(String id, List<MetricPoint> points, Map<Integer,Integer> originalToVertex) {
        public Seed {
            requireId(id);
            validateGeometry(points, originalToVertex, true);
            points = List.copyOf(points);
            originalToVertex = Map.copyOf(originalToVertex);
        }
    }

    /** One parent-owned reference section, including exact protected boundary anchors. */
    public record Section(String id, String parentSeedId, OccurrenceRange originalRange,
            List<MetricPoint> reference, Map<Integer,Integer> originalToVertex, boolean frozen) {
        public Section {
            requireId(id);
            requireId(parentSeedId);
            validateGeometry(reference, originalToVertex, false);
            if (originalRange == null || !originalToVertex.containsKey(originalRange.firstIndex())
                    || !originalToVertex.containsKey(originalRange.lastIndex())
                    || originalToVertex.keySet().stream().anyMatch(index -> index < originalRange.firstIndex()
                            || index > originalRange.lastIndex())) {
                throw new IllegalArgumentException("Section must own its original boundary occurrences");
            }
            reference = List.copyOf(reference);
            originalToVertex = Map.copyOf(originalToVertex);
        }
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) { throw new IllegalArgumentException("Geometry identity is required"); }
    }

    private static void validateGeometry(List<MetricPoint> points, Map<Integer,Integer> mapping,
            boolean requirePositiveLength) {
        if (points == null || points.size() < 2 || points.stream().anyMatch(java.util.Objects::isNull)
                || mapping == null || mapping.size() < 2
                || mapping.keySet().stream().anyMatch(index -> index == null || index < 0)
                || mapping.values().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Temporary geometry and original anchor mapping are required");
        }
        int previous = -1;
        for (int vertex : new TreeMap<>(mapping).values()) {
            if (vertex <= previous || vertex >= points.size()) {
                throw new IllegalArgumentException("Original anchors must map one-to-one and monotonically");
            }
            previous = vertex;
        }
        var ordered = new TreeMap<>(mapping);
        if (ordered.firstEntry().getValue() != 0 || ordered.lastEntry().getValue() != points.size() - 1) {
            throw new IllegalArgumentException("Temporary geometry must retain its boundary anchors");
        }
        double length = 0;
        for (int i = 1; i < points.size(); i++) { length += points.get(i - 1).distanceTo(points.get(i)); }
        if (!Double.isFinite(length) || requirePositiveLength && length <= 0) {
            throw new IllegalArgumentException("Temporary geometry must have positive finite metric length");
        }
    }
}
