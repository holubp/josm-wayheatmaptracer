package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField.FrozenProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField.FrozenSupport;

/** Evidence-owning reconstruction of bounded incident-way approaches. */
final class IncidentWayReconstructor {
    private static final double MAXIMUM_TRUST_RADIUS_METERS = 20.0;
    private static final double MOVEMENT_EPSILON_METERS = 1.0e-6;

    private IncidentWayReconstructor() {
    }

    static Map<PrimitiveKey, DetachedPrimitive> reconstruct(NetworkSnapshot before,
            Map<PrimitiveKey, DetachedPrimitive> proposed, EvidenceSnapshot evidence,
            PrimitiveKey selectedWayKey, Set<PrimitiveKey> sharedJunctions) {
        if (before == null || proposed == null || evidence == null || selectedWayKey == null
                || sharedJunctions == null || sharedJunctions.isEmpty()
                || evidence.fields().size() != 1) {
            throw failure("requires one complete scalar field and a shared junction");
        }
        boolean splitReceiver = isBoundedSplitReceiverTopology(
                before, evidence, selectedWayKey, sharedJunctions);
        if (!splitReceiver
                && !isBoundedSelectedInteriorThroughTopology(before, selectedWayKey,
                    sharedJunctions)
                && !isBoundedTwoThroughReceiverTopology(before, selectedWayKey,
                    sharedJunctions)
                && !isBoundedCoupledEndpointsTopology(before, selectedWayKey,
                    sharedJunctions)
                && !isBoundedCoupledSameReceiverTopology(before, selectedWayKey,
                    sharedJunctions)) {
            requireBoundedTerminalThroughTopology(before, selectedWayKey, sharedJunctions);
        }
        ImageCostField image = new ImageCostField(evidence.fields().values().iterator().next(),
                evidence.transform(), before.closure().editRegion(),
                evidence.resolution().effectivePitchMeters());
        Map<PrimitiveKey, DetachedPrimitive> result = new LinkedHashMap<>(proposed);
        Set<PrimitiveKey> reconstructedMovableNodes = new LinkedHashSet<>();
        Set<PrimitiveKey> expectedMovableNodes = expectedIncidentShapeNodes(
                before, selectedWayKey, sharedJunctions);
        int reconstructedApproaches = 0;

        for (Map.Entry<PrimitiveKey, List<OccurrenceRange>> entry
                : before.closure().editableWayOccurrences().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
            if (entry.getKey().equals(selectedWayKey)) {
                continue;
            }
            DetachedPrimitive primitive = before.primitives().get(entry.getKey());
            if (!(primitive instanceof DetachedWay way)) {
                throw failure("contains an editable identity that is not a complete way");
            }
            Set<PrimitiveKey> portBoundaries = before.closure().externalPorts().stream()
                    .filter(port -> port.wayKey().equals(way.key()))
                    .map(port -> port.boundaryNodeKey())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            for (OccurrenceRange range : entry.getValue()) {
                if (splitReceiver) {
                    PrimitiveKey opposite = way.nodeKeys().get(
                            way.nodeKeys().get(range.firstIndex())
                                    .equals(sharedJunctions.iterator().next())
                                ? range.lastIndex() : range.firstIndex());
                    if (!portBoundaries.equals(Set.of(opposite))) {
                        throw failure("requires one exact external port on each split arm");
                    }
                } else if (!portBoundaries.contains(way.nodeKeys().get(range.firstIndex()))
                        || !portBoundaries.contains(way.nodeKeys().get(range.lastIndex()))) {
                    throw failure("requires exact external ports on both sides of each reconstructed range");
                }
                List<Integer> junctionOccurrences = new ArrayList<>();
                for (int index = range.firstIndex(); index <= range.lastIndex(); index++) {
                    if (sharedJunctions.contains(way.nodeKeys().get(index))) {
                        junctionOccurrences.add(index);
                    }
                }
                if (junctionOccurrences.size() != 1) {
                    throw failure("requires one unambiguous junction occurrence per incident range");
                }
                int junctionIndex = junctionOccurrences.get(0);
                if (splitReceiver) {
                    if (junctionIndex != range.firstIndex()
                            && junctionIndex != range.lastIndex()) {
                        throw failure("split receiver requires one terminal junction per arm");
                    }
                    reconstructedApproaches += reconstructArm(before, result, evidence, image,
                            way, range.firstIndex(), range.lastIndex(),
                            junctionIndex == range.firstIndex(), reconstructedMovableNodes);
                } else {
                    if (junctionIndex <= range.firstIndex()
                            || junctionIndex >= range.lastIndex()) {
                        throw failure("requires a measured approach on both sides of the junction");
                    }
                    reconstructedApproaches += reconstructArm(before, result, evidence, image, way,
                            range.firstIndex(), junctionIndex, false, reconstructedMovableNodes);
                    reconstructedApproaches += reconstructArm(before, result, evidence, image, way,
                            junctionIndex, range.lastIndex(), true, reconstructedMovableNodes);
                }
            }
            requireSimpleIncidentWay(result, evidence, way);
        }
        if (reconstructedApproaches == 0
                || !reconstructedMovableNodes.containsAll(expectedMovableNodes)) {
            throw failure("did not cover every authorized incident shape occurrence");
        }
        return Map.copyOf(result);
    }

    private static void requireBoundedTerminalThroughTopology(NetworkSnapshot before,
            PrimitiveKey selectedWayKey, Set<PrimitiveKey> sharedJunctions) {
        if (sharedJunctions.size() != 1
                || !(before.primitives().get(selectedWayKey) instanceof DetachedWay selected)) {
            throw failure("exceeds the bounded terminal-through topology");
        }
        PrimitiveKey junction = sharedJunctions.iterator().next();
        int selectedOccurrence = selected.nodeKeys().indexOf(junction);
        if (selected.nodeKeys().stream().filter(junction::equals).count() != 1
                || selectedOccurrence < 0
                || selectedOccurrence != 0
                    && selectedOccurrence != selected.nodeKeys().size() - 1) {
            throw failure("exceeds the bounded terminal-through topology");
        }
        List<PrimitiveKey> receivers = before.closure().editableWayOccurrences().keySet().stream()
                .filter(key -> !key.equals(selectedWayKey)).sorted().toList();
        if (receivers.size() != 1
                || !(before.primitives().get(receivers.get(0)) instanceof DetachedWay receiver)) {
            throw failure("exceeds the bounded terminal-through topology");
        }
        int receiverOccurrence = receiver.nodeKeys().indexOf(junction);
        List<OccurrenceRange> ranges = before.closure().editableWayOccurrences()
                .get(receivers.get(0));
        Set<PrimitiveKey> incidentWays = before.incomingReferrerWatches()
                .getOrDefault(junction, Set.of()).stream()
                .filter(key -> key.type() == PrimitiveKey.Type.WAY)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (new LinkedHashSet<>(receiver.nodeKeys()).size() != receiver.nodeKeys().size()
                || receiver.nodeKeys().stream().filter(junction::equals).count() != 1
                || receiverOccurrence <= 0
                || receiverOccurrence >= receiver.nodeKeys().size() - 1
                || ranges == null || ranges.size() != 1
                || receiverOccurrence <= ranges.get(0).firstIndex()
                || receiverOccurrence >= ranges.get(0).lastIndex()
                || !incidentWays.equals(Set.of(selectedWayKey, receivers.get(0)))) {
            throw failure("exceeds the bounded terminal-through topology");
        }
    }

    /** One interior selected junction and one complete, two-port through receiver. */
    private static boolean isBoundedSelectedInteriorThroughTopology(NetworkSnapshot before,
            PrimitiveKey selectedWayKey, Set<PrimitiveKey> sharedJunctions) {
        if (sharedJunctions.size() != 1
                || !(before.primitives().get(selectedWayKey) instanceof DetachedWay selected)) {
            return false;
        }
        PrimitiveKey junction = sharedJunctions.iterator().next();
        int selectedOccurrence = selected.nodeKeys().indexOf(junction);
        if (selected.nodeKeys().stream().filter(junction::equals).count() != 1
                || selectedOccurrence <= 0
                || selectedOccurrence >= selected.nodeKeys().size() - 1) {
            return false;
        }
        List<PrimitiveKey> receivers = before.closure().editableWayOccurrences().keySet().stream()
                .filter(key -> !key.equals(selectedWayKey)).sorted().toList();
        if (receivers.size() != 1
                || !(before.primitives().get(receivers.get(0)) instanceof DetachedWay receiver)) {
            return false;
        }
        int receiverOccurrence = receiver.nodeKeys().indexOf(junction);
        List<OccurrenceRange> ranges = before.closure().editableWayOccurrences()
                .get(receivers.get(0));
        Set<PrimitiveKey> incidentWays = before.incomingReferrerWatches()
                .getOrDefault(junction, Set.of()).stream()
                .filter(key -> key.type() == PrimitiveKey.Type.WAY)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<PrimitiveKey> receiverPorts = before.closure().externalPorts().stream()
                .filter(port -> port.wayKey().equals(receiver.key()))
                .map(port -> port.boundaryNodeKey())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new LinkedHashSet<>(receiver.nodeKeys()).size() == receiver.nodeKeys().size()
                && receiver.nodeKeys().stream().filter(junction::equals).count() == 1
                && receiverOccurrence > 0
                && receiverOccurrence < receiver.nodeKeys().size() - 1
                && ranges != null && ranges.size() == 1
                && receiverOccurrence > ranges.get(0).firstIndex()
                && receiverOccurrence < ranges.get(0).lastIndex()
                && receiverPorts.contains(receiver.nodeKeys().get(ranges.get(0).firstIndex()))
                && receiverPorts.contains(receiver.nodeKeys().get(ranges.get(0).lastIndex()))
                && incidentWays.equals(Set.of(selectedWayKey, receiver.key()));
    }

    /** One terminal selected way and two distinct, singly ported terminal receiver arms. */
    private static boolean isBoundedSplitReceiverTopology(NetworkSnapshot before,
            EvidenceSnapshot evidence, PrimitiveKey selectedWayKey,
            Set<PrimitiveKey> sharedJunctions) {
        if (sharedJunctions.size() != 1
                || !(before.primitives().get(selectedWayKey) instanceof DetachedWay selected)) {
            return false;
        }
        PrimitiveKey junction = sharedJunctions.iterator().next();
        int selectedOccurrence = selected.nodeKeys().indexOf(junction);
        if (selected.nodeKeys().stream().filter(junction::equals).count() != 1
                || selectedOccurrence != 0
                    && selectedOccurrence != selected.nodeKeys().size() - 1) {
            return false;
        }
        List<PrimitiveKey> receivers = before.closure().editableWayOccurrences().keySet().stream()
                .filter(key -> !key.equals(selectedWayKey)).sorted().toList();
        if (receivers.size() != 2) {
            return false;
        }
        Set<PrimitiveKey> incidentWays = before.incomingReferrerWatches()
                .getOrDefault(junction, Set.of()).stream()
                .filter(key -> key.type() == PrimitiveKey.Type.WAY)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!incidentWays.equals(Set.of(selectedWayKey, receivers.get(0), receivers.get(1)))) {
            return false;
        }
        MetricPoint junctionPoint = metricPoint(before.primitives(), evidence, junction);
        List<MetricPoint> outgoing = new ArrayList<>();
        for (PrimitiveKey receiverKey : receivers) {
            if (!(before.primitives().get(receiverKey) instanceof DetachedWay receiver)
                    || new LinkedHashSet<>(receiver.nodeKeys()).size() != receiver.nodeKeys().size()
                    || receiver.nodeKeys().stream().filter(junction::equals).count() != 1) {
                return false;
            }
            int occurrence = receiver.nodeKeys().indexOf(junction);
            if (occurrence != 0 && occurrence != receiver.nodeKeys().size() - 1) {
                return false;
            }
            PrimitiveKey adjacent = receiver.nodeKeys().get(occurrence == 0
                    ? 1 : occurrence - 1);
            MetricPoint adjacentPoint = metricPoint(before.primitives(), evidence, adjacent);
            outgoing.add(new MetricPoint(adjacentPoint.xMeters() - junctionPoint.xMeters(),
                    adjacentPoint.yMeters() - junctionPoint.yMeters()));
            List<OccurrenceRange> ranges = before.closure().editableWayOccurrences()
                    .get(receiverKey);
            if (ranges == null || ranges.size() != 1) {
                return false;
            }
            OccurrenceRange range = ranges.get(0);
            if (occurrence != range.firstIndex() && occurrence != range.lastIndex()) {
                return false;
            }
            PrimitiveKey opposite = receiver.nodeKeys().get(occurrence == range.firstIndex()
                    ? range.lastIndex() : range.firstIndex());
            Set<PrimitiveKey> ports = before.closure().externalPorts().stream()
                    .filter(port -> port.wayKey().equals(receiverKey))
                    .map(port -> port.boundaryNodeKey())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!ports.equals(Set.of(opposite))) {
                return false;
            }
        }
        MetricPoint first = outgoing.get(0);
        MetricPoint second = outgoing.get(1);
        double firstLength = Math.hypot(first.xMeters(), first.yMeters());
        double secondLength = Math.hypot(second.xMeters(), second.yMeters());
        return firstLength > 1.0e-9 && secondLength > 1.0e-9
                && first.xMeters() * second.xMeters() + first.yMeters() * second.yMeters()
                    <= -0.75 * firstLength * secondLength;
    }

    /** One terminal selected way and exactly two complete two-port through receivers. */
    private static boolean isBoundedTwoThroughReceiverTopology(NetworkSnapshot before,
            PrimitiveKey selectedWayKey, Set<PrimitiveKey> sharedJunctions) {
        if (sharedJunctions.size() != 1
                || !(before.primitives().get(selectedWayKey) instanceof DetachedWay selected)) {
            return false;
        }
        PrimitiveKey junction = sharedJunctions.iterator().next();
        int selectedOccurrence = selected.nodeKeys().indexOf(junction);
        if (selected.nodeKeys().stream().filter(junction::equals).count() != 1
                || selectedOccurrence != 0
                    && selectedOccurrence != selected.nodeKeys().size() - 1) {
            return false;
        }
        List<PrimitiveKey> receivers = before.closure().editableWayOccurrences().keySet().stream()
                .filter(key -> !key.equals(selectedWayKey)).sorted().toList();
        if (receivers.size() != 2) {
            return false;
        }
        Set<PrimitiveKey> incidentWays = before.incomingReferrerWatches()
                .getOrDefault(junction, Set.of()).stream()
                .filter(key -> key.type() == PrimitiveKey.Type.WAY)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!incidentWays.equals(Set.of(selectedWayKey, receivers.get(0), receivers.get(1)))) {
            return false;
        }
        Set<PrimitiveKey> receiverShapeNodes = new LinkedHashSet<>();
        for (PrimitiveKey receiverKey : receivers) {
            if (!(before.primitives().get(receiverKey) instanceof DetachedWay receiver)
                    || new LinkedHashSet<>(receiver.nodeKeys()).size() != receiver.nodeKeys().size()
                    || receiver.nodeKeys().stream().filter(junction::equals).count() != 1) {
                return false;
            }
            for (PrimitiveKey node : receiver.nodeKeys()) {
                if (!node.equals(junction) && !receiverShapeNodes.add(node)) {
                    return false;
                }
            }
            int occurrence = receiver.nodeKeys().indexOf(junction);
            List<OccurrenceRange> ranges = before.closure().editableWayOccurrences()
                    .get(receiverKey);
            if (occurrence <= 0 || occurrence >= receiver.nodeKeys().size() - 1
                    || ranges == null || ranges.size() != 1
                    || occurrence <= ranges.get(0).firstIndex()
                    || occurrence >= ranges.get(0).lastIndex()) {
                return false;
            }
            Set<PrimitiveKey> ports = before.closure().externalPorts().stream()
                    .filter(port -> port.wayKey().equals(receiverKey))
                    .map(port -> port.boundaryNodeKey())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!ports.equals(Set.of(receiver.nodeKeys().get(ranges.get(0).firstIndex()),
                    receiver.nodeKeys().get(ranges.get(0).lastIndex())))) {
                return false;
            }
        }
        return true;
    }

    /** Two selected endpoints, each with its own fully captured through receiver. */
    private static boolean isBoundedCoupledEndpointsTopology(NetworkSnapshot before,
            PrimitiveKey selectedWayKey, Set<PrimitiveKey> sharedJunctions) {
        if (sharedJunctions.size() != 2
                || !(before.primitives().get(selectedWayKey) instanceof DetachedWay selected)
                || selected.nodeKeys().size() != 2
                || !sharedJunctions.equals(Set.copyOf(selected.nodeKeys()))) {
            return false;
        }
        List<PrimitiveKey> receivers = before.closure().editableWayOccurrences().keySet().stream()
                .filter(key -> !key.equals(selectedWayKey)).sorted().toList();
        if (receivers.size() != 2) {
            return false;
        }
        Set<PrimitiveKey> assignedJunctions = new LinkedHashSet<>();
        Set<PrimitiveKey> receiverShapeNodes = new LinkedHashSet<>();
        for (PrimitiveKey receiverKey : receivers) {
            if (!(before.primitives().get(receiverKey) instanceof DetachedWay receiver)
                    || new LinkedHashSet<>(receiver.nodeKeys()).size() != receiver.nodeKeys().size()) {
                return false;
            }
            List<PrimitiveKey> junctions = receiver.nodeKeys().stream()
                    .filter(sharedJunctions::contains).toList();
            if (junctions.size() != 1 || !assignedJunctions.add(junctions.get(0))) {
                return false;
            }
            PrimitiveKey junction = junctions.get(0);
            Set<PrimitiveKey> incidentWays = before.incomingReferrerWatches()
                    .getOrDefault(junction, Set.of()).stream()
                    .filter(key -> key.type() == PrimitiveKey.Type.WAY)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!incidentWays.equals(Set.of(selectedWayKey, receiverKey))) {
                return false;
            }
            for (PrimitiveKey node : receiver.nodeKeys()) {
                if (!node.equals(junction) && !receiverShapeNodes.add(node)) {
                    return false;
                }
            }
            int occurrence = receiver.nodeKeys().indexOf(junction);
            List<OccurrenceRange> ranges = before.closure().editableWayOccurrences()
                    .get(receiverKey);
            if (occurrence <= 0 || occurrence >= receiver.nodeKeys().size() - 1
                    || ranges == null || ranges.size() != 1
                    || occurrence <= ranges.get(0).firstIndex()
                    || occurrence >= ranges.get(0).lastIndex()) {
                return false;
            }
            Set<PrimitiveKey> ports = before.closure().externalPorts().stream()
                    .filter(port -> port.wayKey().equals(receiverKey))
                    .map(port -> port.boundaryNodeKey())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!ports.equals(Set.of(receiver.nodeKeys().get(ranges.get(0).firstIndex()),
                    receiver.nodeKeys().get(ranges.get(0).lastIndex())))) {
                return false;
            }
        }
        return assignedJunctions.equals(sharedJunctions);
    }

    /** Two selected endpoints with one receiver captured as two disjoint local junction ranges. */
    private static boolean isBoundedCoupledSameReceiverTopology(NetworkSnapshot before,
            PrimitiveKey selectedWayKey, Set<PrimitiveKey> sharedJunctions) {
        if (sharedJunctions.size() != 2
                || !(before.primitives().get(selectedWayKey) instanceof DetachedWay selected)
                || selected.nodeKeys().size() != 2
                || !sharedJunctions.equals(Set.copyOf(selected.nodeKeys()))) {
            return false;
        }
        List<PrimitiveKey> receivers = before.closure().editableWayOccurrences().keySet().stream()
                .filter(key -> !key.equals(selectedWayKey)).toList();
        if (receivers.size() != 1
                || !(before.primitives().get(receivers.get(0)) instanceof DetachedWay receiver)
                || new LinkedHashSet<>(receiver.nodeKeys()).size() != receiver.nodeKeys().size()) {
            return false;
        }
        List<OccurrenceRange> ranges = before.closure().editableWayOccurrences()
                .get(receivers.get(0));
        if (ranges == null || ranges.size() != 2
                || ranges.get(0).lastIndex() + 1 >= ranges.get(1).firstIndex()) {
            return false;
        }
        Set<PrimitiveKey> ports = before.closure().externalPorts().stream()
                .filter(port -> port.wayKey().equals(receiver.key()))
                .map(port -> port.boundaryNodeKey())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<PrimitiveKey> expectedPorts = new LinkedHashSet<>();
        Set<PrimitiveKey> assignedJunctions = new LinkedHashSet<>();
        for (OccurrenceRange range : ranges) {
            if (range.firstIndex() < 0 || range.lastIndex() >= receiver.nodeKeys().size()) {
                return false;
            }
            List<PrimitiveKey> localJunctions = receiver.nodeKeys().subList(
                    range.firstIndex(), range.lastIndex() + 1).stream()
                    .filter(sharedJunctions::contains).toList();
            if (localJunctions.size() != 1
                    || !assignedJunctions.add(localJunctions.get(0))) {
                return false;
            }
            int occurrence = receiver.nodeKeys().indexOf(localJunctions.get(0));
            if (occurrence <= range.firstIndex() || occurrence >= range.lastIndex()) {
                return false;
            }
            expectedPorts.add(receiver.nodeKeys().get(range.firstIndex()));
            expectedPorts.add(receiver.nodeKeys().get(range.lastIndex()));
            Set<PrimitiveKey> incidentWays = before.incomingReferrerWatches()
                    .getOrDefault(localJunctions.get(0), Set.of()).stream()
                    .filter(key -> key.type() == PrimitiveKey.Type.WAY)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!incidentWays.equals(Set.of(selectedWayKey, receiver.key()))) {
                return false;
            }
        }
        return assignedJunctions.equals(sharedJunctions)
                && ports.size() == 4
                && ports.equals(expectedPorts)
                && before.closure().protectedExistingNodeKeys().containsAll(ports);
    }

    private static int reconstructArm(NetworkSnapshot before,
            Map<PrimitiveKey, DetachedPrimitive> proposed, EvidenceSnapshot evidence,
            ImageCostField image, DetachedWay way, int firstIndex, int lastIndex,
            boolean junctionAtStart, Set<PrimitiveKey> reconstructedMovableNodes) {
        List<PrimitiveKey> nodeKeys = way.nodeKeys().subList(firstIndex, lastIndex + 1);
        List<MetricPoint> initial = nodeKeys.stream()
                .map(key -> metricPoint(proposed, evidence, key)).toList();
        if (initial.size() < 2 || !segmentsAuthorized(before, initial)) {
            throw failure("leaves the authorized incident edit region");
        }
        List<MetricPoint> fitted = new ArrayList<>(initial);
        for (int index = 1; index < initial.size() - 1; index++) {
            PrimitiveKey key = nodeKeys.get(index);
            MetricPoint tangent = tangent(initial, index);
            FrozenProfile profile = image.freezeProfile(initial.get(index), tangent);
            if (profile.support() != FrozenSupport.MEASURED) {
                throw failure("is missing direct evidence for incident control "
                        + way.key() + "#" + (firstIndex + index));
            }
            double offset = 0.5 * (profile.coreMinimumMeters() + profile.coreMaximumMeters());
            if (!before.closure().movableExistingNodeKeys().contains(key)) {
                double distanceToCore = profile.coreMinimumMeters() > 0.0
                        ? profile.coreMinimumMeters()
                        : profile.coreMaximumMeters() < 0.0
                            ? -profile.coreMaximumMeters() : 0.0;
                double uncertainty = Math.max(evidence.resolution().effectivePitchMeters(),
                        profile.localizationSigmaMeters());
                if (distanceToCore > uncertainty) {
                    throw failure("cannot fit a protected incident control " + way.key()
                            + "#" + (firstIndex + index));
                }
                continue;
            }
            MetricPoint target = new MetricPoint(
                    initial.get(index).xMeters() + profile.normal().xMeters() * offset,
                    initial.get(index).yMeters() + profile.normal().yMeters() * offset);
            if (!Double.isFinite(offset) || Math.abs(offset) > MAXIMUM_TRUST_RADIUS_METERS
                    || !before.closure().editRegion().contains(target)) {
                throw failure("exceeds the bounded incident localization region");
            }
            fitted.set(index, target);
        }
        if (!segmentsAuthorized(before, fitted)) {
            throw failure("produced a segment outside the authorized incident region");
        }
        requireDirectArmEvidence(image, fitted, junctionAtStart);
        requireMonotonicOccurrenceOrder(fitted);
        for (int index = 1; index < fitted.size() - 1; index++) {
            PrimitiveKey key = nodeKeys.get(index);
            if (!before.closure().movableExistingNodeKeys().contains(key)) {
                continue;
            }
            DetachedPrimitive value = proposed.get(key);
            if (!(value instanceof DetachedNode old)) {
                throw failure("omits an authorized incident shape node");
            }
            MetricPoint point = fitted.get(index);
            proposed.put(key, new DetachedNode(key, evidence.coordinateFrame().toGeographic(point),
                    old.tags(), old.deleted(), old.modified()
                        || point.distanceTo(initial.get(index)) > MOVEMENT_EPSILON_METERS));
            reconstructedMovableNodes.add(key);
        }
        return 1;
    }

    private static MetricPoint tangent(List<MetricPoint> points, int index) {
        MetricPoint previous = points.get(index - 1);
        MetricPoint next = points.get(index + 1);
        MetricPoint tangent = new MetricPoint(next.xMeters() - previous.xMeters(),
                next.yMeters() - previous.yMeters());
        if (!(Math.hypot(tangent.xMeters(), tangent.yMeters()) > 1.0e-9)) {
            throw failure("contains a zero-length incident tangent");
        }
        return tangent;
    }

    private static void requireDirectArmEvidence(ImageCostField image, List<MetricPoint> points,
            boolean junctionAtStart) {
        int measuredOutsideCore = 0;
        for (int index = 1; index < points.size(); index++) {
            if (junctionAtStart && index == 1
                    || !junctionAtStart && index == points.size() - 1) {
                continue;
            }
            MetricPoint start = points.get(index - 1);
            MetricPoint end = points.get(index);
            MetricPoint tangent = new MetricPoint(end.xMeters() - start.xMeters(),
                    end.yMeters() - start.yMeters());
            MetricPoint midpoint = new MetricPoint((start.xMeters() + end.xMeters()) * 0.5,
                    (start.yMeters() + end.yMeters()) * 0.5);
            FrozenProfile profile = image.freezeProfile(midpoint, tangent);
            if (profile.support() != FrozenSupport.MEASURED) {
                throw failure("has missing or ambiguous scalar localization outside "
                        + "the junction core on segment " + (index - 1) + "->" + index);
            }
            measuredOutsideCore++;
        }
        if (measuredOutsideCore == 0) {
            throw failure("has no measured incident section outside the junction core");
        }
    }

    static void requireMonotonicOccurrenceOrder(List<MetricPoint> points) {
        MetricPoint start = points.get(0);
        MetricPoint end = points.get(points.size() - 1);
        double dx = end.xMeters() - start.xMeters();
        double dy = end.yMeters() - start.yMeters();
        double lengthSquared = dx * dx + dy * dy;
        if (!(lengthSquared > 1.0e-12)) {
            throw failure("has coincident port and junction positions");
        }
        double previous = Double.NEGATIVE_INFINITY;
        for (MetricPoint point : points) {
            double projection = ((point.xMeters() - start.xMeters()) * dx
                    + (point.yMeters() - start.yMeters()) * dy) / lengthSquared;
            if (projection <= previous + 1.0e-12) {
                throw failure("would invert incident occurrence order");
            }
            previous = projection;
        }
    }

    private static void requireSimpleIncidentWay(Map<PrimitiveKey, DetachedPrimitive> proposed,
            EvidenceSnapshot evidence, DetachedWay way) {
        List<MetricPoint> points = way.nodeKeys().stream()
                .map(key -> metricPoint(proposed, evidence, key)).toList();
        for (int first = 1; first < points.size(); first++) {
            if (points.get(first - 1).distanceTo(points.get(first)) <= 1.0e-9) {
                throw failure("would create a zero-length incident segment");
            }
            for (int second = first + 2; second < points.size(); second++) {
                if (segmentsIntersect(points.get(first - 1), points.get(first),
                        points.get(second - 1), points.get(second))) {
                    throw failure("would create an incident-way self-intersection");
                }
            }
        }
    }

    private static boolean segmentsIntersect(MetricPoint a, MetricPoint b,
            MetricPoint c, MetricPoint d) {
        double abC = cross(a, b, c);
        double abD = cross(a, b, d);
        double cdA = cross(c, d, a);
        double cdB = cross(c, d, b);
        double epsilon = 1.0e-9;
        if ((abC > epsilon && abD < -epsilon || abC < -epsilon && abD > epsilon)
                && (cdA > epsilon && cdB < -epsilon
                    || cdA < -epsilon && cdB > epsilon)) {
            return true;
        }
        return Math.abs(abC) <= epsilon && onSegment(a, b, c, epsilon)
                || Math.abs(abD) <= epsilon && onSegment(a, b, d, epsilon)
                || Math.abs(cdA) <= epsilon && onSegment(c, d, a, epsilon)
                || Math.abs(cdB) <= epsilon && onSegment(c, d, b, epsilon);
    }

    private static double cross(MetricPoint a, MetricPoint b, MetricPoint c) {
        return (b.xMeters() - a.xMeters()) * (c.yMeters() - a.yMeters())
                - (b.yMeters() - a.yMeters()) * (c.xMeters() - a.xMeters());
    }

    private static boolean onSegment(MetricPoint a, MetricPoint b, MetricPoint point,
            double epsilon) {
        return point.xMeters() >= Math.min(a.xMeters(), b.xMeters()) - epsilon
                && point.xMeters() <= Math.max(a.xMeters(), b.xMeters()) + epsilon
                && point.yMeters() >= Math.min(a.yMeters(), b.yMeters()) - epsilon
                && point.yMeters() <= Math.max(a.yMeters(), b.yMeters()) + epsilon;
    }

    private static MetricPoint metricPoint(Map<PrimitiveKey, DetachedPrimitive> proposed,
            EvidenceSnapshot evidence, PrimitiveKey key) {
        DetachedPrimitive value = proposed.get(key);
        if (!(value instanceof DetachedNode node)) {
            throw failure("omits a required incident node");
        }
        return evidence.coordinateFrame().toMetric(node.coordinate());
    }

    private static boolean segmentsAuthorized(NetworkSnapshot before, List<MetricPoint> points) {
        for (int index = 1; index < points.size(); index++) {
            if (!before.closure().editRegion().containsSegment(
                    points.get(index - 1), points.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static Set<PrimitiveKey> expectedIncidentShapeNodes(NetworkSnapshot before,
            PrimitiveKey selectedWayKey, Set<PrimitiveKey> sharedJunctions) {
        Set<PrimitiveKey> result = new LinkedHashSet<>();
        before.closure().editableWayOccurrences().forEach((wayKey, ranges) -> {
            if (wayKey.equals(selectedWayKey)) {
                return;
            }
            DetachedPrimitive primitive = before.primitives().get(wayKey);
            if (!(primitive instanceof DetachedWay way)) {
                throw failure("contains an incomplete incident way");
            }
            for (OccurrenceRange range : ranges) {
                for (int index = range.firstIndex(); index <= range.lastIndex(); index++) {
                    PrimitiveKey node = way.nodeKeys().get(index);
                    if (!sharedJunctions.contains(node)
                            && before.closure().movableExistingNodeKeys().contains(node)) {
                        result.add(node);
                    }
                }
            }
        });
        return Set.copyOf(result);
    }

    private static IllegalArgumentException failure(String detail) {
        return new IllegalArgumentException("incident approach evidence " + detail);
    }
}
