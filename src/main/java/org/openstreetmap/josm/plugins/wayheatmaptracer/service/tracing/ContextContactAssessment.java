package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;

/** Immutable, occurrence-local classification of a final selected-way/context contact. */
public record ContextContactAssessment(PrimitiveKey selectedWayKey, PrimitiveKey contextWayKey,
        int selectedSegmentIndex, int contextSegmentIndex, int routeSegmentIndex,
        ContactType contactType, List<MetricPoint> location, boolean inherited,
        Disposition disposition, String reasonCode) {
    public enum ContactType { CROSSING, VERTEX_TOUCH, COLLINEAR_OVERLAP }
    public enum Disposition { REVIEW_REQUIRED, PRESERVED_CONNECTION, HARD_BLOCKED }
    public static final String REVIEW_CODE = "independent-context-contact-review-required";

    /** Missing location is explicit for an unproved contact; it is never invented. */
    public ContextContactAssessment {
        if (selectedWayKey == null || contextWayKey == null || selectedSegmentIndex < 0
                || contextSegmentIndex < 0 || routeSegmentIndex < 0 || contactType == null
                || location == null || disposition == null || reasonCode == null || reasonCode.isBlank()
                || disposition == Disposition.REVIEW_REQUIRED && location.isEmpty()) {
            throw new IllegalArgumentException("Context contact assessment is incomplete");
        }
        location = List.copyOf(location);
    }

    /**
     * Classifies an already detected robust contact, keeping shared identity local to this pair.
     * Semantic tags and incomplete read-only parent relations grant no movement authority and
     * do not change the disposition of independently captured, unchanged contextual geometry.
     */
    static ContextContactAssessment assess(NetworkSnapshot before,
            Map<PrimitiveKey, DetachedPrimitive> after, PrimitiveKey selectedKey,
            OccurrenceRange range, int selectedIndex, PrimitiveKey contextKey, int contextIndex,
            ContactType type, CertifiedContextSegmentClipper.ContactEvidence contact,
            EvidenceSnapshot evidence, double halfWidthMeters) {
        DetachedWay source = (DetachedWay) before.primitives().get(selectedKey);
        DetachedWay selected = (DetachedWay) after.get(selectedKey);
        DetachedWay context = (DetachedWay) after.get(contextKey);
        int first = selected.nodeKeys().indexOf(source.nodeKeys().get(range.firstIndex()));
        int last = selected.nodeKeys().indexOf(source.nodeKeys().get(range.lastIndex()));
        int routeIndex = Math.max(0, selectedIndex - Math.max(0, first));
        List<MetricPoint> location = List.of();
        boolean inherited = false;
        boolean shared = contact.ownedExactIncidence();
        Disposition disposition = Disposition.HARD_BLOCKED;
        String reason = "context-contact-evidence-unavailable";
        try {
            MetricPoint a = metric(after, selected.nodeKeys().get(selectedIndex), evidence);
            MetricPoint b = metric(after, selected.nodeKeys().get(selectedIndex + 1), evidence);
            MetricPoint c = metric(after, context.nodeKeys().get(contextIndex), evidence);
            MetricPoint d = metric(after, context.nodeKeys().get(contextIndex + 1), evidence);
            CertifiedContextSegmentClipper.requireChangedInside(a, b, evidence.coordinateFrame());
            CertifiedContextSegmentClipper.requireChangedInside(c, d, evidence.coordinateFrame());
            location = location(a, b, c, d, type);
            inherited = inheritedContact(before, source, range, contextKey, contextIndex, type, evidence);
            boolean authority = before.closure().editableExistingKeys().contains(selectedKey)
                    && before.closure().editableWayOccurrences().getOrDefault(selectedKey, List.of()).contains(range)
                    && first >= 0 && last > first && selectedIndex >= first && selectedIndex < last
                    && Double.isFinite(halfWidthMeters) && halfWidthMeters > 0;
            if (selectedKey.equals(contextKey)) {
                reason = "context-contact-self-intersection";
            } else if (!authority) {
                reason = "context-contact-edit-authority-unavailable";
            } else if (shared) {
                // Only the existing sole-owned touch exemption preserves a connection.
                disposition = contact.soleOwnedContact() && type == ContactType.VERTEX_TOUCH
                        ? Disposition.PRESERVED_CONNECTION : Disposition.HARD_BLOCKED;
                reason = "context-contact-shared-identity";
            } else if (!InheritedNonTransportContacts.unchanged(context, before, after)
                    || !parentsUnchanged(contextKey, before, after)) {
                reason = "context-contact-context-edited";
            } else if (!contact.hasAnyContact() || contact.uncertainty() || location.isEmpty()) {
                reason = "context-contact-position-unproved";
            } else {
                disposition = Disposition.REVIEW_REQUIRED;
                reason = REVIEW_CODE;
            }
        } catch (IllegalArgumentException missingCertificate) {
            // A clipped display chord cannot prove a complete independent contact.
        }
        return new ContextContactAssessment(selectedKey, contextKey, selectedIndex, contextIndex,
                routeIndex, type, location, inherited, disposition, reason);
    }

    private static boolean parentsUnchanged(PrimitiveKey context, NetworkSnapshot before,
            Map<PrimitiveKey, DetachedPrimitive> after) {
        java.util.ArrayDeque<PrimitiveKey> pending = new java.util.ArrayDeque<>();
        java.util.Set<PrimitiveKey> visited = new java.util.HashSet<>();
        pending.add(context);
        while (!pending.isEmpty()) {
            PrimitiveKey key = pending.removeFirst();
            if (!visited.add(key)) continue;
            for (PrimitiveKey parent : before.incomingReferrerWatches().getOrDefault(key, java.util.Set.of())) {
                if (parent.type() != PrimitiveKey.Type.RELATION || !before.primitives().containsKey(parent)) continue;
                if (!before.primitives().get(parent).equals(after.get(parent))) return false;
                pending.add(parent);
            }
        }
        return true;
    }

    private static boolean inheritedContact(NetworkSnapshot before, DetachedWay source,
            OccurrenceRange range, PrimitiveKey contextKey, int contextIndex, ContactType type,
            EvidenceSnapshot evidence) {
        if (!(before.primitives().get(contextKey) instanceof DetachedWay context)
                || contextIndex + 1 >= context.nodeKeys().size()) return false;
        var values = before.primitives();
        var contextChord = chord(values, context, contextIndex, evidence);
        for (int i = range.firstIndex(); i < range.lastIndex(); i++) {
            var old = CertifiedContextSegmentClipper.originalContactEvidence(chord(values, source, i, evidence),
                    source.nodeKeys().get(i), source.nodeKeys().get(i + 1), contextChord,
                    context.nodeKeys().get(contextIndex), context.nodeKeys().get(contextIndex + 1),
                    evidence.coordinateFrame());
            if (!old.hasAnyContact()) continue;
            if (type == ContactType.CROSSING && old.properCrossing()
                    || type == ContactType.COLLINEAR_OVERLAP && old.positiveOverlap()
                    || type == ContactType.VERTEX_TOUCH && !old.properCrossing()
                            && !old.positiveOverlap() && !old.witnesses().isEmpty()) return true;
        }
        return false;
    }

    private static CertifiedContextSegmentClipper.ExactChord chord(Map<PrimitiveKey, DetachedPrimitive> values,
            DetachedWay way, int index, EvidenceSnapshot evidence) {
        return CertifiedContextSegmentClipper.originalChord(node(values, way.nodeKeys().get(index)).coordinate(),
                node(values, way.nodeKeys().get(index + 1)).coordinate(), evidence.coordinateFrame());
    }

    private static DetachedNode node(Map<PrimitiveKey, DetachedPrimitive> values, PrimitiveKey key) {
        if (!(values.get(key) instanceof DetachedNode node)) {
            throw new IllegalArgumentException("Context contact node is absent");
        }
        return node;
    }

    private static MetricPoint metric(Map<PrimitiveKey, DetachedPrimitive> values, PrimitiveKey key,
            EvidenceSnapshot evidence) {
        return evidence.coordinateFrame().toMetric(node(values, key).coordinate());
    }

    private static List<MetricPoint> location(MetricPoint a, MetricPoint b, MetricPoint c,
            MetricPoint d, ContactType type) {
        double ax = b.xMeters() - a.xMeters(), ay = b.yMeters() - a.yMeters();
        double cx = d.xMeters() - c.xMeters(), cy = d.yMeters() - c.yMeters();
        double divisor = ax * cy - ay * cx;
        if (type == ContactType.CROSSING && divisor != 0) {
            double fraction = ((c.xMeters() - a.xMeters()) * cy - (c.yMeters() - a.yMeters()) * cx) / divisor;
            return List.of(new MetricPoint(a.xMeters() + fraction * ax, a.yMeters() + fraction * ay));
        }
        List<MetricPoint> locations = new ArrayList<>();
        for (MetricPoint point : List.of(a, b, c, d)) {
            if (onSegment(point, a, b) && onSegment(point, c, d) && !locations.contains(point)) locations.add(point);
        }
        return List.copyOf(locations);
    }

    private static boolean onSegment(MetricPoint p, MetricPoint a, MetricPoint b) {
        double cross = (b.xMeters() - a.xMeters()) * (p.yMeters() - a.yMeters())
                - (b.yMeters() - a.yMeters()) * (p.xMeters() - a.xMeters());
        return Math.abs(cross) <= 1e-8 && p.xMeters() >= Math.min(a.xMeters(), b.xMeters()) - 1e-9
                && p.xMeters() <= Math.max(a.xMeters(), b.xMeters()) + 1e-9
                && p.yMeters() >= Math.min(a.yMeters(), b.yMeters()) - 1e-9
                && p.yMeters() <= Math.max(a.yMeters(), b.yMeters()) + 1e-9;
    }
}
