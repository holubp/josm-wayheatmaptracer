package org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;

/** Conservative v0.22 decision for automatic junction editing from one complete frozen closure. */
public final class ManualJunctionEligibility {
    private static final double NORMAL_ARM_METERS = 30.0;
    private static final double MAXIMUM_ARM_METERS = 60.0;

    /** Typed reason; only {@link #SIMPLE_T} authorizes automatic reattachment. */
    public enum Reason {
        NO_JUNCTION, SIMPLE_T, INCOMPLETE_CLOSURE, REPEATED_OCCURRENCE,
        MULTIPLE_JUNCTIONS, SELECTED_INTERIOR, MULTIPLE_RECEIVERS, SPLIT_OR_TERMINAL_RECEIVER,
        PARTICIPATING_RELATION, AFFECTED_NODE_TAGGED, AFFECTED_NODE_RELATION,
        COUPLED_JUNCTION, INCOMPLETE_ARM, AMBIGUOUS_CROSSING, RESOURCE_LIMIT,
        LEGACY_POLICY
    }

    /** The exact frozen junction and protected neighborhood known to the decision. */
    public record Decision(Reason reason, PrimitiveKey junction, PrimitiveKey receiver,
            Set<PrimitiveKey> affectedNodes) {
        public Decision {
            affectedNodes = Set.copyOf(affectedNodes);
        }

        /** Whether the ordinary, relation-free simple T may enter automatic planning. */
        public boolean automaticallyEligible() {
            return reason == Reason.SIMPLE_T;
        }

        /** Whether a selected shared junction requires a manual decision. */
        public boolean manualOnly() {
            return reason != Reason.NO_JUNCTION && reason != Reason.SIMPLE_T;
        }

        /** Visible instruction for an unavailable automatic junction slide. */
        public String manualInstruction() {
            return reason.name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT)
                    + ". Adjust this junction manually, then run alignment again.";
        }
    }

    private ManualJunctionEligibility() { }

    /** Evaluates the same immutable closure used by the live capture and final edit plan. */
    public static Decision evaluate(NetworkSnapshot snapshot,
            NetworkSnapshotCapture.Specification specification) {
        if (snapshot == null || specification == null
                || !snapshot.closure().wayReferrersComplete()
                || !snapshot.closure().relationReferrersComplete()
                || !snapshot.closure().nearbyGeometryComplete()
                || !snapshot.primitives().containsKey(specification.selectedWayKey())) {
            return decision(Reason.INCOMPLETE_CLOSURE, null, null, Set.of());
        }
        if (!(snapshot.primitives().get(specification.selectedWayKey()) instanceof DetachedWay selected)
                || specification.selectedRange().lastIndex() >= selected.nodeKeys().size()) {
            return decision(Reason.INCOMPLETE_CLOSURE, null, null, Set.of());
        }
        OccurrenceRange selectedRange = specification.selectedRange();
        if (new HashSet<>(selected.nodeKeys()).size() != selected.nodeKeys().size()) {
            return decision(Reason.REPEATED_OCCURRENCE, null, null, Set.of());
        }
        List<PrimitiveKey> shared = selected.nodeKeys().subList(selectedRange.firstIndex(),
                selectedRange.lastIndex() + 1).stream()
                .filter(node -> wayReferrers(snapshot, node).size() > 1).distinct().toList();
        if (shared.isEmpty()) {
            return decision(Reason.NO_JUNCTION, null, null, Set.of());
        }
        if (shared.size() != 1) {
            return decision(Reason.MULTIPLE_JUNCTIONS, null, null, Set.copyOf(shared));
        }
        PrimitiveKey junction = shared.get(0);
        int selectedIndex = selected.nodeKeys().indexOf(junction);
        if (selectedIndex != selectedRange.firstIndex() && selectedIndex != selectedRange.lastIndex()) {
            return decision(Reason.SELECTED_INTERIOR, junction, null, Set.of(junction));
        }
        Set<PrimitiveKey> incident = wayReferrers(snapshot, junction);
        if (incident.size() != 2 || !incident.contains(specification.selectedWayKey())) {
            return decision(Reason.MULTIPLE_RECEIVERS, junction, null, Set.of(junction));
        }
        PrimitiveKey receiverKey = incident.stream()
                .filter(key -> !key.equals(specification.selectedWayKey())).findFirst().orElseThrow();
        if (!(snapshot.primitives().get(receiverKey) instanceof DetachedWay receiver)) {
            return decision(Reason.INCOMPLETE_CLOSURE, junction, receiverKey, Set.of(junction));
        }
        if (new HashSet<>(receiver.nodeKeys()).size() != receiver.nodeKeys().size()) {
            return decision(Reason.REPEATED_OCCURRENCE, junction, receiverKey, Set.of(junction));
        }
        int receiverIndex = receiver.nodeKeys().indexOf(junction);
        if (receiverIndex <= 0 || receiverIndex >= receiver.nodeKeys().size() - 1) {
            return decision(Reason.SPLIT_OR_TERMINAL_RECEIVER, junction, receiverKey,
                    Set.of(junction));
        }
        OccurrenceRange receivingRange = onlyRange(snapshot, receiverKey);
        if (receivingRange == null) {
            receivingRange = new OccurrenceRange(0, receiver.nodeKeys().size() - 1);
        }
        Set<PrimitiveKey> affected = new LinkedHashSet<>();
        if (!collectArm(snapshot, specification, selected, selectedIndex,
                selectedIndex == selectedRange.firstIndex() ? 1 : -1,
                selectedRange, affected)
                || !collectArm(snapshot, specification, receiver, receiverIndex, -1,
                        receivingRange, affected)
                || !collectArm(snapshot, specification, receiver, receiverIndex, 1,
                        receivingRange, affected)) {
            return decision(Reason.INCOMPLETE_ARM, junction, receiverKey, affected);
        }
        NearbyJunction nearby = nearbyJunction(snapshot, selected,
                selectedIndex);
        if (nearby == NearbyJunction.CLEAR) {
            nearby = nearbyJunction(snapshot, receiver, receiverIndex);
        }
        if (nearby != NearbyJunction.CLEAR) {
            return decision(nearby == NearbyJunction.COUPLED ? Reason.COUPLED_JUNCTION
                    : Reason.INCOMPLETE_CLOSURE, junction, receiverKey, affected);
        }
        if (hasRelationReferrer(snapshot, specification.selectedWayKey())
                || hasRelationReferrer(snapshot, receiverKey)) {
            return decision(Reason.PARTICIPATING_RELATION, junction, receiverKey, affected);
        }
        for (PrimitiveKey node : affected) {
            if (!(snapshot.primitives().get(node) instanceof DetachedNode detached)) {
                return decision(Reason.INCOMPLETE_CLOSURE, junction, receiverKey, affected);
            }
            if (!detached.tags().isEmpty()) {
                return decision(Reason.AFFECTED_NODE_TAGGED, junction, receiverKey, affected);
            }
            if (hasRelationReferrer(snapshot, node)) {
                return decision(Reason.AFFECTED_NODE_RELATION, junction, receiverKey, affected);
            }
            Set<PrimitiveKey> referrers = wayReferrers(snapshot, node);
            if (node.equals(junction) ? !referrers.equals(incident)
                    : referrers.size() > 1) {
                return decision(Reason.COUPLED_JUNCTION, junction, receiverKey, affected);
            }
        }
        CrossingCheck crossing = checkUnconnectedCrossing(snapshot, specification,
                selected, receiver, affected);
        if (crossing == CrossingCheck.RESOURCE_LIMIT) {
            return decision(Reason.RESOURCE_LIMIT, junction, receiverKey, affected);
        }
        if (crossing == CrossingCheck.CROSSING) {
            return decision(Reason.AMBIGUOUS_CROSSING, junction, receiverKey, affected);
        }
        if (specification.permissions().junctionPolicy()
                == org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy
                        .LEGACY_BOUNDED_MOVE) {
            return decision(Reason.LEGACY_POLICY, junction, receiverKey, affected);
        }
        if (onlyRange(snapshot, receiverKey) == null
                || !snapshot.closure().movableExistingNodeKeys().contains(junction)
                || !snapshot.closure().editableExistingKeys().contains(receiverKey)) {
            return decision(Reason.INCOMPLETE_CLOSURE, junction, receiverKey, affected);
        }
        return decision(Reason.SIMPLE_T, junction, receiverKey, affected);
    }

    private static OccurrenceRange onlyRange(NetworkSnapshot snapshot, PrimitiveKey way) {
        List<OccurrenceRange> ranges = snapshot.closure().editableWayOccurrences().get(way);
        return ranges != null && ranges.size() == 1 ? ranges.get(0) : null;
    }

    private static boolean collectArm(NetworkSnapshot snapshot,
            NetworkSnapshotCapture.Specification specification, DetachedWay way,
            int junctionIndex, int direction, OccurrenceRange range, Set<PrimitiveKey> affected) {
        if (range == null || junctionIndex < range.firstIndex()
                || junctionIndex > range.lastIndex()) {
            return false;
        }
        affected.add(way.nodeKeys().get(junctionIndex));
        double distance = 0.0;
        int index = junctionIndex;
        boolean selected = way.key().equals(specification.selectedWayKey());
        while (true) {
            int next = index + direction;
            if (next < 0 || next >= way.nodeKeys().size()) {
                return index != junctionIndex;
            }
            if (selected && (next < range.firstIndex() || next > range.lastIndex())) {
                return index != junctionIndex
                        && snapshot.closure().protectedExistingNodeKeys()
                                .contains(way.nodeKeys().get(index))
                        && hasPort(snapshot, way.key(), way.nodeKeys().get(index), index,
                                direction);
            }
            DetachedNode first = (DetachedNode) snapshot.primitives().get(way.nodeKeys().get(index));
            DetachedNode second = (DetachedNode) snapshot.primitives().get(way.nodeKeys().get(next));
            if (first == null || second == null) {
                return false;
            }
            double length = specification.metricFrame().toMetric(first.coordinate()).distanceTo(
                    specification.metricFrame().toMetric(second.coordinate()));
            if (!Double.isFinite(length) || length <= 0.0
                    || distance + length > MAXIMUM_ARM_METERS) {
                return false;
            }
            distance += length;
            affected.add(way.nodeKeys().get(next));
            index = next;
            if (index == 0 || index == way.nodeKeys().size() - 1) {
                return true;
            }
            PrimitiveKey node = way.nodeKeys().get(index);
            if (distance >= NORMAL_ARM_METERS
                    && (hasPort(snapshot, way.key(), node, index, direction)
                        || selected && snapshot.closure().protectedExistingNodeKeys().contains(node)
                        || !selected && (hasRelationReferrer(snapshot, node)
                            || wayReferrers(snapshot, node).size() > 1
                            || !((DetachedNode) snapshot.primitives().get(node)).tags().isEmpty()))) {
                return true;
            }
        }
    }

    private static boolean hasPort(NetworkSnapshot snapshot, PrimitiveKey way,
            PrimitiveKey node, int occurrence, int direction) {
        return snapshot.closure().externalPorts().stream().anyMatch(port ->
                port.wayKey().equals(way) && port.boundaryNodeKey().equals(node)
                        && port.boundaryOccurrenceIndex() == occurrence
                        && (direction < 0 ? port.side() == org.openstreetmap.josm.plugins
                                .wayheatmaptracer.model.ExternalPort.Side.BEFORE
                                : port.side() == org.openstreetmap.josm.plugins
                                        .wayheatmaptracer.model.ExternalPort.Side.AFTER));
    }

    private enum NearbyJunction { CLEAR, COUPLED, INCOMPLETE }

    private static NearbyJunction nearbyJunction(NetworkSnapshot snapshot, DetachedWay way,
            int junctionIndex) {
        for (int direction : new int[] {-1, 1}) {
            double distance = 0.0;
            for (int index = junctionIndex; index + direction >= 0
                    && index + direction < way.nodeKeys().size(); index += direction) {
                PrimitiveKey firstKey = way.nodeKeys().get(index);
                PrimitiveKey nextKey = way.nodeKeys().get(index + direction);
                if (!(snapshot.primitives().get(firstKey) instanceof DetachedNode first)
                        || !(snapshot.primitives().get(nextKey) instanceof DetachedNode next)
                        || !snapshot.incomingReferrerWatches().containsKey(nextKey)) {
                    return NearbyJunction.INCOMPLETE;
                }
                double length = geographicDistance(first.coordinate(), next.coordinate());
                if (!Double.isFinite(length) || length <= 0.0) {
                    return NearbyJunction.INCOMPLETE;
                }
                if (distance + length > MAXIMUM_ARM_METERS) {
                    break;
                }
                distance += length;
                if (wayReferrers(snapshot, nextKey).size() > 1) {
                    return NearbyJunction.COUPLED;
                }
            }
        }
        return NearbyJunction.CLEAR;
    }

    private static double geographicDistance(
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint first,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint second) {
        double latitude = Math.toRadians(second.latitudeDegrees() - first.latitudeDegrees());
        double longitude = Math.toRadians(second.longitudeDegrees() - first.longitudeDegrees());
        double haversine = Math.pow(Math.sin(latitude / 2.0), 2.0)
                + Math.cos(Math.toRadians(first.latitudeDegrees()))
                * Math.cos(Math.toRadians(second.latitudeDegrees()))
                * Math.pow(Math.sin(longitude / 2.0), 2.0);
        // Minimum WGS84 local curvature makes this an underbound for overlap rejection.
        return 6_335_439.0 * 2.0 * Math.asin(Math.sqrt(Math.min(1.0, haversine)));
    }

    private enum CrossingCheck { CLEAR, CROSSING, RESOURCE_LIMIT }

    private static CrossingCheck checkUnconnectedCrossing(NetworkSnapshot snapshot,
            NetworkSnapshotCapture.Specification specification, DetachedWay selected,
            DetachedWay receiver, Set<PrimitiveKey> affected) {
        long comparisons = 0L;
        for (DetachedPrimitive primitive : snapshot.primitives().values()) {
            if (!(primitive instanceof DetachedWay other) || other.key().equals(selected.key())
                    || other.key().equals(receiver.key())) {
                continue;
            }
            for (DetachedWay arm : List.of(selected, receiver)) {
                for (int index = 0; index + 1 < arm.nodeKeys().size(); index++) {
                    PrimitiveKey left = arm.nodeKeys().get(index);
                    PrimitiveKey right = arm.nodeKeys().get(index + 1);
                    if (!affected.contains(left) || !affected.contains(right)) {
                        continue;
                    }
                    MetricPoint a = metric(snapshot, specification, left);
                    MetricPoint b = metric(snapshot, specification, right);
                    for (int otherIndex = 0; otherIndex + 1 < other.nodeKeys().size(); otherIndex++) {
                        if (++comparisons > 1_000_000L) {
                            return CrossingCheck.RESOURCE_LIMIT;
                        }
                        MetricPoint c = metric(snapshot, specification,
                                other.nodeKeys().get(otherIndex));
                        MetricPoint d = metric(snapshot, specification,
                                other.nodeKeys().get(otherIndex + 1));
                        if (intersects(a, b, c, d)) {
                            return CrossingCheck.CROSSING;
                        }
                    }
                }
            }
        }
        return CrossingCheck.CLEAR;
    }

    private static MetricPoint metric(NetworkSnapshot snapshot,
            NetworkSnapshotCapture.Specification specification, PrimitiveKey key) {
        return specification.metricFrame().toMetric(
                ((DetachedNode) snapshot.primitives().get(key)).coordinate());
    }

    private static boolean intersects(MetricPoint a, MetricPoint b,
            MetricPoint c, MetricPoint d) {
        double abC = cross(a, b, c);
        double abD = cross(a, b, d);
        double cdA = cross(c, d, a);
        double cdB = cross(c, d, b);
        double tolerance = 1.0e-9;
        if (abC * abD < -tolerance && cdA * cdB < -tolerance) {
            return true;
        }
        return Math.abs(abC) <= tolerance && within(a, b, c)
                || Math.abs(abD) <= tolerance && within(a, b, d)
                || Math.abs(cdA) <= tolerance && within(c, d, a)
                || Math.abs(cdB) <= tolerance && within(c, d, b);
    }

    private static double cross(MetricPoint a, MetricPoint b, MetricPoint c) {
        return (b.xMeters() - a.xMeters()) * (c.yMeters() - a.yMeters())
                - (b.yMeters() - a.yMeters()) * (c.xMeters() - a.xMeters());
    }

    private static boolean within(MetricPoint a, MetricPoint b, MetricPoint point) {
        return point.xMeters() >= Math.min(a.xMeters(), b.xMeters()) - 1.0e-9
                && point.xMeters() <= Math.max(a.xMeters(), b.xMeters()) + 1.0e-9
                && point.yMeters() >= Math.min(a.yMeters(), b.yMeters()) - 1.0e-9
                && point.yMeters() <= Math.max(a.yMeters(), b.yMeters()) + 1.0e-9;
    }

    private static boolean hasRelationReferrer(NetworkSnapshot snapshot, PrimitiveKey key) {
        return snapshot.incomingReferrerWatches().getOrDefault(key, Set.of()).stream()
                .anyMatch(referrer -> referrer.type() == PrimitiveKey.Type.RELATION);
    }

    private static Set<PrimitiveKey> wayReferrers(NetworkSnapshot snapshot, PrimitiveKey key) {
        return snapshot.incomingReferrerWatches().getOrDefault(key, Set.of()).stream()
                .filter(referrer -> referrer.type() == PrimitiveKey.Type.WAY)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Decision decision(Reason reason, PrimitiveKey junction,
            PrimitiveKey receiver, Set<PrimitiveKey> affected) {
        return new Decision(reason, junction, receiver, affected);
    }
}
