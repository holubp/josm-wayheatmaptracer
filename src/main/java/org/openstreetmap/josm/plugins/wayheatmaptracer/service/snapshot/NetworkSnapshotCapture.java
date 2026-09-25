package org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import javax.swing.SwingUtilities;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedRelation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedRelationMember;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ExternalPort;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.SelectionIntegrity;

/** Captures one bounded, detached network snapshot from JOSM's EDT. */
public final class NetworkSnapshotCapture {
    private static final int MAXIMUM_AUTHORITY_IDENTITIES = 250_000;
    private static final int MAXIMUM_AUTHORITY_OCCURRENCES = 1_000_000;

    /** Production capture limits aligned with the v0.22 snapshot and collision-query contracts. */
    public static final Limits DEFAULT_LIMITS = new Limits(250_000, 1_000_000,
        250_000, 1_000_000, 1_000_000, 1_000_000);

    private NetworkSnapshotCapture() {
    }

    /** Immutable caller-owned selection and edit authority used to derive the live closure. */
    public record Specification(String snapshotId, String datasetIdentity, long sourceGeneration,
        PrimitiveKey selectedWayKey, OccurrenceRange selectedRange, LocalMetricFrame metricFrame,
        MetricRegion collisionEnvelope, MetricRegion editRegion,
        Map<PrimitiveKey, List<OccurrenceRange>> editableWayOccurrences,
        Set<PrimitiveKey> editableExistingKeys, Set<PrimitiveKey> movableExistingNodeKeys,
        Set<PrimitiveKey> removableExistingNodeKeys, Set<PrimitiveKey> explicitlyProtectedNodeKeys,
        boolean mayCreateNodes, RecoveryPermissions permissions) {
        /** Copies detached authority inputs and rejects incomplete or oversized specifications. */
        public Specification {
            if (snapshotId == null || snapshotId.isBlank() || datasetIdentity == null
                || datasetIdentity.isBlank() || sourceGeneration < 0 || selectedWayKey == null
                || selectedRange == null || metricFrame == null || collisionEnvelope == null
                || editRegion == null || editableWayOccurrences == null
                || editableExistingKeys == null || movableExistingNodeKeys == null
                || removableExistingNodeKeys == null || explicitlyProtectedNodeKeys == null
                || permissions == null) {
                throw new IllegalArgumentException("Network capture specification is incomplete");
            }
            long authorityIdentities = (long) editableWayOccurrences.size() + editableExistingKeys.size()
                + movableExistingNodeKeys.size() + removableExistingNodeKeys.size()
                + explicitlyProtectedNodeKeys.size();
            if (authorityIdentities > MAXIMUM_AUTHORITY_IDENTITIES) {
                throw new IllegalArgumentException("Network capture authority inventory exceeds its budget");
            }
            long occurrenceCount = 0;
            for (Map.Entry<PrimitiveKey, List<OccurrenceRange>> entry : editableWayOccurrences.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    throw new IllegalArgumentException("Network capture occurrence authority is incomplete");
                }
                occurrenceCount += entry.getValue().size();
                if (occurrenceCount > MAXIMUM_AUTHORITY_OCCURRENCES) {
                    throw new IllegalArgumentException("Network capture occurrence authority exceeds its budget");
                }
            }
            Map<PrimitiveKey, List<OccurrenceRange>> ranges = new LinkedHashMap<>();
            for (Map.Entry<PrimitiveKey, List<OccurrenceRange>> entry : editableWayOccurrences.entrySet()) {
                ranges.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            editableWayOccurrences = Map.copyOf(ranges);
            editableExistingKeys = Set.copyOf(editableExistingKeys);
            movableExistingNodeKeys = Set.copyOf(movableExistingNodeKeys);
            removableExistingNodeKeys = Set.copyOf(removableExistingNodeKeys);
            explicitlyProtectedNodeKeys = Set.copyOf(explicitlyProtectedNodeKeys);
        }
    }

    /** Explicit hard limits for every live scan and retained detached inventory. */
    public record Limits(int maximumExaminedPrimitives, int maximumExaminedReferences,
        int maximumMaterializedPrimitives, int maximumMaterializedReferences,
        int maximumWatchIdentities, int maximumTagEntries) {
        /** Requires positive hard bounds. */
        public Limits {
            if (maximumExaminedPrimitives <= 0 || maximumExaminedReferences <= 0
                || maximumMaterializedPrimitives <= 0 || maximumMaterializedReferences <= 0
                || maximumWatchIdentities <= 0 || maximumTagEntries <= 0) {
                throw new IllegalArgumentException("Network capture limits must be positive");
            }
        }
    }

    /**
     * Immutable proof that one exact specification produced one snapshot from one live dataset.
     *
     * <p>Only a successful capture can create this receipt. The dataset reference is retained solely
     * for identity validation at a later locked command boundary.</p>
     */
    public static final class CapturedSnapshot {
        private final DataSet dataSet;
        private final Specification specification;
        private final NetworkSnapshot snapshot;

        private CapturedSnapshot(DataSet dataSet, Specification specification,
                NetworkSnapshot snapshot) {
            this.dataSet = dataSet;
            this.specification = specification;
            this.snapshot = snapshot;
        }

        /** Returns the exact immutable capture specification. */
        public Specification specification() {
            return specification;
        }

        /** Returns the exact detached snapshot produced by the capture. */
        public NetworkSnapshot snapshot() {
            return snapshot;
        }

        boolean belongsTo(DataSet candidate) {
            return dataSet == candidate;
        }
    }

    /** Captures using the production limits. */
    public static NetworkSnapshot capture(DataSet dataSet, Specification specification) {
        return capture(dataSet, specification, DEFAULT_LIMITS);
    }

    /**
     * Captures and returns an unforgeable receipt binding the specification, result, and dataset.
     */
    public static CapturedSnapshot captureBound(DataSet dataSet, Specification specification) {
        NetworkSnapshot snapshot = capture(dataSet, specification, DEFAULT_LIMITS);
        return new CapturedSnapshot(dataSet, specification, snapshot);
    }

    static NetworkSnapshot capture(DataSet dataSet, Specification specification, Limits limits) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Network snapshot capture must execute on the EDT");
        }
        Objects.requireNonNull(dataSet, "dataSet");
        Objects.requireNonNull(specification, "specification");
        Objects.requireNonNull(limits, "limits");
        Lock readLock = dataSet.getReadLock();
        readLock.lock();
        try {
            return captureLocked(dataSet, specification, limits);
        } finally {
            readLock.unlock();
        }
    }

    private static NetworkSnapshot captureLocked(DataSet dataSet, Specification specification,
        Limits limits) {
        Inventory inventory = Inventory.scan(dataSet, limits);
        validateAuthority(dataSet, specification, inventory);
        CollisionEnvelopeQuery.Result query = CollisionEnvelopeQuery.execute(
            dataSet, specification.metricFrame(), specification.collisionEnvelope());
        Materializer materializer = new Materializer(dataSet, inventory, limits);
        materializer.include(specification.selectedWayKey());
        query.intersectingWayKeys().stream().sorted().forEach(materializer::include);
        specification.editableExistingKeys().stream().sorted().forEach(materializer::include);

        Set<PrimitiveKey> decisionNodes = decisionRelevantNodes(specification, inventory);
        Set<PrimitiveKey> affectedWays = new LinkedHashSet<>(
            specification.editableWayOccurrences().keySet());
        for (PrimitiveKey node : decisionNodes) {
            for (PrimitiveKey referrer : inventory.referrers(node)) {
                materializer.include(referrer);
                if (referrer.type() == PrimitiveKey.Type.WAY) {
                    affectedWays.add(referrer);
                }
            }
        }
        for (PrimitiveKey way : affectedWays) {
            for (PrimitiveKey referrer : inventory.referrers(way)) {
                if (referrer.type() == PrimitiveKey.Type.RELATION) {
                    materializer.include(referrer);
                }
            }
        }
        for (PrimitiveKey editable : specification.editableExistingKeys()) {
            if (editable.type() == PrimitiveKey.Type.RELATION) {
                inventory.referrers(editable).forEach(materializer::include);
            }
        }
        validateRemovalAndMovementAuthority(specification, inventory);

        Set<PrimitiveKey> capturedKeys = materializer.keys();
        Set<PrimitiveKey> movable = requireCaptured(specification.movableExistingNodeKeys(), capturedKeys,
            "movable node");
        Set<PrimitiveKey> removable = requireCaptured(specification.removableExistingNodeKeys(), capturedKeys,
            "removable node");
        Set<PrimitiveKey> protectedNodes = capturedKeys.stream()
            .filter(key -> key.type() == PrimitiveKey.Type.NODE
                && !movable.contains(key) && !removable.contains(key))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        protectedNodes.addAll(requireCaptured(specification.explicitlyProtectedNodeKeys(),
            capturedKeys, "protected node"));
        List<ExternalPort> ports = externalPorts(specification, inventory);
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.EDIT_COMPONENT,
            query.definition().version(), capturedKeys, specification.editableExistingKeys(),
            movable, Set.copyOf(protectedNodes), removable, specification.editableWayOccurrences(),
            ports, specification.collisionEnvelope(), specification.editRegion(),
            specification.mayCreateNodes(), true, true, true);
        Map<PrimitiveKey, DetachedPrimitive> primitives = materializer.detach();
        Map<PrimitiveKey, Set<PrimitiveKey>> watches = materializer.watches();
        return new NetworkSnapshot(specification.snapshotId(), SnapshotRole.CAPTURED_BEFORE,
            specification.datasetIdentity(), specification.sourceGeneration(), closure, primitives, watches);
    }

    private static void validateAuthority(DataSet dataSet, Specification specification,
        Inventory inventory) {
        if (specification.selectedWayKey().type() != PrimitiveKey.Type.WAY
            || specification.selectedWayKey().identityKind() != PrimitiveKey.IdentityKind.OSM_UNIQUE) {
            throw new IllegalStateException("Selected way identity is invalid");
        }
        OsmPrimitive selectedPrimitive = inventory.require(specification.selectedWayKey());
        if (!(selectedPrimitive instanceof Way selected) || selected.isDeleted() || selected.isIncomplete()
            || selected.hasIncompleteNodes() || selected.getNodesCount() < 2
            || specification.selectedRange().lastIndex() >= selected.getNodesCount()) {
            throw new IllegalStateException("Selected way or occurrence range is incomplete");
        }
        SelectionIntegrity.requireNoRepeatedNodeOccurrences(selected,
            specification.selectedRange().firstIndex(), specification.selectedRange().lastIndex());
        Set<PrimitiveKey> allAuthority = new LinkedHashSet<>(specification.editableExistingKeys());
        allAuthority.addAll(specification.movableExistingNodeKeys());
        allAuthority.addAll(specification.removableExistingNodeKeys());
        allAuthority.addAll(specification.explicitlyProtectedNodeKeys());
        for (PrimitiveKey key : allAuthority) {
            if (key.identityKind() != PrimitiveKey.IdentityKind.OSM_UNIQUE) {
                throw new IllegalStateException("Capture authority requires existing primitive identities");
            }
            OsmPrimitive primitive = inventory.require(key);
            if (primitive.getDataSet() != dataSet || primitive.isDeleted() || primitive.isIncomplete()) {
                throw new IllegalStateException("Capture authority refers to unusable live data: " + key);
            }
        }
        if (!specification.editableExistingKeys().containsAll(specification.movableExistingNodeKeys())
            || !specification.editableExistingKeys().containsAll(specification.removableExistingNodeKeys())
            || !java.util.Collections.disjoint(specification.movableExistingNodeKeys(),
                specification.removableExistingNodeKeys())
            || !java.util.Collections.disjoint(specification.explicitlyProtectedNodeKeys(),
                specification.movableExistingNodeKeys())
            || !java.util.Collections.disjoint(specification.explicitlyProtectedNodeKeys(),
                specification.removableExistingNodeKeys())
            || specification.movableExistingNodeKeys().stream().anyMatch(key -> key.type() != PrimitiveKey.Type.NODE)
            || specification.removableExistingNodeKeys().stream().anyMatch(key -> key.type() != PrimitiveKey.Type.NODE)
            || specification.explicitlyProtectedNodeKeys().stream()
                .anyMatch(key -> key.type() != PrimitiveKey.Type.NODE)) {
            throw new IllegalStateException("Capture node authority is inconsistent");
        }
        validateOccurrenceAuthority(specification, inventory);
        boolean incidentOccurrenceAuthority = specification.editableWayOccurrences().keySet().stream()
            .anyMatch(key -> !key.equals(specification.selectedWayKey()));
        if (specification.editableExistingKeys().stream().anyMatch(key ->
            key.type() == PrimitiveKey.Type.RELATION
                && specification.permissions().junctionPolicy() != JunctionPolicy.REATTACH)
            || incidentOccurrenceAuthority
                && specification.permissions().junctionPolicy() == JunctionPolicy.FIXED
            || incidentOccurrenceAuthority
                && specification.permissions().junctionPolicy() == JunctionPolicy.LEGACY_BOUNDED_MOVE
                && !legacyIncidentMoveAuthority(specification, inventory, selected)
            || specification.permissions().junctionPolicy() == JunctionPolicy.REATTACH
                && !boundedReattachmentAuthority(specification, inventory, selected)) {
            throw new IllegalStateException("Capture authority exceeds accepted recovery permissions");
        }
        if (specification.permissions().junctionPolicy() == JunctionPolicy.FIXED) {
            PrimitiveKey first = key(selected.getNode(specification.selectedRange().firstIndex()));
            PrimitiveKey last = key(selected.getNode(specification.selectedRange().lastIndex()));
            if (specification.movableExistingNodeKeys().contains(first)
                || specification.movableExistingNodeKeys().contains(last)) {
                throw new IllegalStateException("Fixed selected boundaries cannot be movable");
            }
        }
    }

    private static boolean legacyIncidentMoveAuthority(Specification specification,
            Inventory inventory, Way selected) {
        Set<PrimitiveKey> selectedBoundaries = Set.of(
            key(selected.getNode(specification.selectedRange().firstIndex())),
            key(selected.getNode(specification.selectedRange().lastIndex())));
        for (Map.Entry<PrimitiveKey, List<OccurrenceRange>> entry
                : specification.editableWayOccurrences().entrySet()) {
            if (entry.getKey().equals(specification.selectedWayKey())) {
                continue;
            }
            Way incident = (Way) inventory.require(entry.getKey());
            for (OccurrenceRange range : entry.getValue()) {
                if (range.size() != 1) {
                    return false;
                }
                PrimitiveKey node = key(incident.getNode(range.firstIndex()));
                if (!selectedBoundaries.contains(node)
                        || !specification.movableExistingNodeKeys().contains(node)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean boundedReattachmentAuthority(Specification specification,
            Inventory inventory, Way selected) {
        Set<PrimitiveKey> selectedRangeNodes = new LinkedHashSet<>();
        for (int index = specification.selectedRange().firstIndex();
                index <= specification.selectedRange().lastIndex(); index++) {
            selectedRangeNodes.add(key(selected.getNode(index)));
        }
        Set<PrimitiveKey> movableSharedJunctions = selectedRangeNodes.stream()
                .filter(specification.movableExistingNodeKeys()::contains)
                .filter(node -> inventory.referrers(node).stream()
                        .filter(key -> key.type() == PrimitiveKey.Type.WAY).count() > 1)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (specification.movableExistingNodeKeys().stream()
                .filter(selectedRangeNodes::contains)
                .anyMatch(node -> !movableSharedJunctions.contains(node))) {
            return false;
        }
        for (PrimitiveKey editable : specification.editableExistingKeys()) {
            if (editable.type() == PrimitiveKey.Type.WAY
                    && !editable.equals(specification.selectedWayKey())
                    && !specification.editableWayOccurrences().containsKey(editable)) {
                return false;
            }
        }
        Set<PrimitiveKey> locallyAuthorizedNodes = new LinkedHashSet<>(selectedRangeNodes);
        for (Map.Entry<PrimitiveKey, List<OccurrenceRange>> entry
                : specification.editableWayOccurrences().entrySet()) {
            if (entry.getKey().equals(specification.selectedWayKey())) {
                continue;
            }
            Way incident = (Way) inventory.require(entry.getKey());
            if (new LinkedHashSet<>(incident.getNodes()).size() != incident.getNodesCount()) {
                return false;
            }
            Map<PrimitiveKey, Node> junctions = new LinkedHashMap<>();
            for (Node node : incident.getNodes()) {
                if (movableSharedJunctions.contains(key(node))) {
                    junctions.put(key(node), node);
                }
            }
            if (junctions.isEmpty()) {
                return false;
            }
            List<OccurrenceRange> expected = List.of();
            try {
                for (Node junction : junctions.values()) {
                    expected = JunctionAuthorityBounds.mergeOccurrenceRanges(expected,
                            List.of(JunctionAuthorityBounds.localOccurrenceRange(
                                    incident, junction, specification.metricFrame())));
                }
            } catch (IllegalArgumentException failure) {
                return false;
            }
            List<OccurrenceRange> supplied = JunctionAuthorityBounds.mergeOccurrenceRanges(
                    List.of(), entry.getValue());
            if (!expected.equals(supplied)) {
                return false;
            }
            for (OccurrenceRange range : expected) {
                for (int index = range.firstIndex(); index <= range.lastIndex(); index++) {
                    PrimitiveKey node = key(incident.getNode(index));
                    locallyAuthorizedNodes.add(node);
                    if (specification.movableExistingNodeKeys().contains(node)
                            && !movableSharedJunctions.contains(node)
                            && (index == range.firstIndex() || index == range.lastIndex()
                                || !inventory.referrers(node).equals(Set.of(entry.getKey())))) {
                        return false;
                    }
                }
            }
        }
        return specification.movableExistingNodeKeys().stream()
                .allMatch(locallyAuthorizedNodes::contains);
    }

    private static void validateOccurrenceAuthority(Specification specification, Inventory inventory) {
        if (!specification.editableWayOccurrences().containsKey(specification.selectedWayKey())) {
            throw new IllegalStateException("Selected way requires explicit occurrence authority");
        }
        for (Map.Entry<PrimitiveKey, List<OccurrenceRange>> entry
            : specification.editableWayOccurrences().entrySet()) {
            if (entry.getKey().type() != PrimitiveKey.Type.WAY
                || !specification.editableExistingKeys().contains(entry.getKey())
                || entry.getValue().isEmpty()) {
                throw new IllegalStateException("Editable way occurrence authority is inconsistent");
            }
            OsmPrimitive primitive = inventory.require(entry.getKey());
            if (!(primitive instanceof Way way) || way.getNodesCount() < 2) {
                throw new IllegalStateException("Editable occurrence way is incomplete");
            }
            int previousLast = -1;
            for (OccurrenceRange range : entry.getValue().stream()
                .sorted(Comparator.comparingInt(OccurrenceRange::firstIndex)).toList()) {
                if (range.lastIndex() >= way.getNodesCount() || range.firstIndex() <= previousLast) {
                    throw new IllegalStateException("Editable occurrence range is invalid");
                }
                if (entry.getKey().equals(specification.selectedWayKey())
                    && (range.firstIndex() < specification.selectedRange().firstIndex()
                        || range.lastIndex() > specification.selectedRange().lastIndex())) {
                    throw new IllegalStateException("Selected way authority exceeds the selected range");
                }
                previousLast = range.lastIndex();
            }
        }
    }

    private static Set<PrimitiveKey> decisionRelevantNodes(Specification specification,
        Inventory inventory) {
        Set<PrimitiveKey> result = new LinkedHashSet<>(specification.movableExistingNodeKeys());
        result.addAll(specification.removableExistingNodeKeys());
        specification.editableWayOccurrences().forEach((wayKey, ranges) -> {
            Way way = (Way) inventory.require(wayKey);
            for (OccurrenceRange range : ranges) {
                for (int index = range.firstIndex(); index <= range.lastIndex(); index++) {
                    PrimitiveKey node = key(way.getNode(index));
                    if (!specification.explicitlyProtectedNodeKeys().contains(node)) {
                        result.add(node);
                    }
                }
            }
        });
        return Set.copyOf(result);
    }

    private static void validateRemovalAndMovementAuthority(Specification specification,
        Inventory inventory) {
        for (PrimitiveKey key : specification.removableExistingNodeKeys()) {
            OsmPrimitive primitive = inventory.require(key);
            if (!(primitive instanceof Node node) || node.getNumKeys() != 0
                || !specification.editableExistingKeys().containsAll(inventory.referrers(key))) {
                throw new IllegalStateException("Removable node is tagged or has noneditable referrers");
            }
        }
        for (PrimitiveKey key : specification.movableExistingNodeKeys()) {
            Node node = (Node) inventory.require(key);
            if (node.getNumKeys() != 0) {
                throw new IllegalStateException(
                    "Movable node is tagged and lacks an explicit location-feature decision");
            }
            for (PrimitiveKey referrer : inventory.referrers(key)) {
                if (referrer.type() != PrimitiveKey.Type.WAY) {
                    continue;
                }
                if (!specification.editableExistingKeys().contains(referrer)
                    || !allOccurrencesAuthorized((Way) inventory.require(referrer), key,
                        specification.editableWayOccurrences().get(referrer))) {
                    throw new IllegalStateException("Movable node has a fixed incident way occurrence");
                }
                if (!referrer.equals(specification.selectedWayKey())
                    && !specification.permissions().reconstructIncidentWays()
                    && !inventory.referrers(key).contains(specification.selectedWayKey())) {
                    throw new IllegalStateException(
                        "Incident shape-node movement requires reconstruction permission");
                }
            }
        }
    }

    private static boolean allOccurrencesAuthorized(Way way, PrimitiveKey node,
        List<OccurrenceRange> ranges) {
        if (ranges == null) {
            return false;
        }
        boolean found = false;
        for (int index = 0; index < way.getNodesCount(); index++) {
            if (!key(way.getNode(index)).equals(node)) {
                continue;
            }
            found = true;
            int occurrence = index;
            if (ranges.stream().noneMatch(range -> occurrence >= range.firstIndex()
                && occurrence <= range.lastIndex())) {
                return false;
            }
        }
        return found;
    }

    private static List<ExternalPort> externalPorts(Specification specification,
        Inventory inventory) {
        List<ExternalPort> result = new ArrayList<>();
        specification.editableWayOccurrences().entrySet().stream()
            .sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                Way way = (Way) inventory.require(entry.getKey());
                List<OccurrenceRange> portRanges = entry.getKey().equals(specification.selectedWayKey())
                    ? List.of(specification.selectedRange()) : entry.getValue();
                portRanges.stream().sorted(Comparator.comparingInt(OccurrenceRange::firstIndex))
                    .forEach(range -> {
                        if (range.firstIndex() > 0
                            && !occurrenceAuthorized(portRanges, range.firstIndex() - 1)) {
                            result.add(port(way, range.firstIndex(), range.firstIndex() - 1,
                                ExternalPort.Side.BEFORE));
                        }
                        if (range.lastIndex() + 1 < way.getNodesCount()
                            && !occurrenceAuthorized(portRanges, range.lastIndex() + 1)) {
                            result.add(port(way, range.lastIndex(), range.lastIndex() + 1,
                                ExternalPort.Side.AFTER));
                        }
                    });
            });
        return List.copyOf(result);
    }

    private static boolean occurrenceAuthorized(List<OccurrenceRange> ranges, int occurrence) {
        return ranges.stream().anyMatch(range -> occurrence >= range.firstIndex()
            && occurrence <= range.lastIndex());
    }

    private static ExternalPort port(Way way, int boundaryIndex, int outsideIndex,
        ExternalPort.Side side) {
        Node boundary = way.getNode(boundaryIndex);
        Node outside = way.getNode(outsideIndex);
        if (!outside.isLatLonKnown()) {
            throw new IllegalStateException("External port has unknown outside geometry");
        }
        return new ExternalPort(key(way), key(boundary), key(outside), boundaryIndex, side,
            new GeographicPoint(outside.lat(), outside.lon()));
    }

    private static Set<PrimitiveKey> requireCaptured(Set<PrimitiveKey> values,
        Set<PrimitiveKey> captured, String description) {
        if (!captured.containsAll(values)) {
            throw new IllegalStateException("Capture omitted authorized " + description + " identities");
        }
        return Set.copyOf(values);
    }

    private static PrimitiveKey key(OsmPrimitive primitive) {
        PrimitiveKey.Type type = primitive instanceof Node ? PrimitiveKey.Type.NODE
            : primitive instanceof Way ? PrimitiveKey.Type.WAY
                : primitive instanceof Relation ? PrimitiveKey.Type.RELATION : null;
        if (type == null) {
            throw new IllegalStateException("Unsupported live primitive type: "
                + primitive.getClass().getName());
        }
        return PrimitiveKey.existing(type, primitive.getUniqueId());
    }

    private static final class Inventory {
        private final DataSet dataSet;
        private final Limits limits;
        private final Map<PrimitiveKey, OsmPrimitive> primitives = new LinkedHashMap<>();
        private final Map<PrimitiveKey, Set<PrimitiveKey>> incoming = new LinkedHashMap<>();
        private int examinedReferences;
        private int storedReferrers;

        private Inventory(DataSet dataSet, Limits limits) {
            this.dataSet = dataSet;
            this.limits = limits;
        }

        static Inventory scan(DataSet dataSet, Limits limits) {
            Inventory inventory = new Inventory(dataSet, limits);
            int examinedPrimitives = 0;
            for (OsmPrimitive primitive : dataSet.allPrimitives()) {
                if (examinedPrimitives >= limits.maximumExaminedPrimitives()) {
                    throw new IllegalStateException("Network capture primitive budget exceeded");
                }
                examinedPrimitives++;
                PrimitiveKey key = key(primitive);
                if (inventory.primitives.put(key, primitive) != null) {
                    throw new IllegalStateException("Duplicate typed primitive identity in live dataset: " + key);
                }
                inventory.incoming.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
                if (primitive instanceof Way way) {
                    inventory.scanWay(way);
                } else if (primitive instanceof Relation relation) {
                    inventory.scanRelation(relation);
                }
            }
            return inventory;
        }

        private void scanWay(Way way) {
            chargeExaminedReferences(way.getNodesCount());
            PrimitiveKey referrer = key(way);
            for (int index = 0; index < way.getNodesCount(); index++) {
                addReferrer(key(way.getNode(index)), referrer);
            }
        }

        private void scanRelation(Relation relation) {
            chargeExaminedReferences(relation.getMembersCount());
            PrimitiveKey referrer = key(relation);
            for (int index = 0; index < relation.getMembersCount(); index++) {
                RelationMember member = relation.getMember(index);
                addReferrer(key(member.getMember()), referrer);
            }
        }

        private void chargeExaminedReferences(int amount) {
            if (amount < 0 || amount > limits.maximumExaminedReferences() - examinedReferences) {
                throw new IllegalStateException("Network capture reference scan budget exceeded");
            }
            examinedReferences += amount;
        }

        private void addReferrer(PrimitiveKey target, PrimitiveKey referrer) {
            if (incoming.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(referrer)) {
                if (storedReferrers >= limits.maximumExaminedReferences()) {
                    throw new IllegalStateException("Network capture referrer index budget exceeded");
                }
                storedReferrers++;
            }
        }

        OsmPrimitive require(PrimitiveKey key) {
            OsmPrimitive primitive = primitives.get(key);
            if (primitive == null || primitive.getDataSet() != dataSet) {
                throw new IllegalStateException("Required live primitive is missing: " + key);
            }
            return primitive;
        }

        Set<PrimitiveKey> referrers(PrimitiveKey key) {
            return incoming.getOrDefault(key, Set.of());
        }
    }

    private static final class Materializer {
        private final DataSet dataSet;
        private final Inventory inventory;
        private final Limits limits;
        private final Set<PrimitiveKey> included = new LinkedHashSet<>();
        private int payloadReferences;
        private int tagEntries;

        private Materializer(DataSet dataSet, Inventory inventory, Limits limits) {
            this.dataSet = dataSet;
            this.inventory = inventory;
            this.limits = limits;
        }

        void include(PrimitiveKey key) {
            ArrayDeque<PrimitiveKey> pending = new ArrayDeque<>();
            pending.addLast(key);
            while (!pending.isEmpty()) {
                PrimitiveKey current = pending.removeLast();
                if (included.contains(current)) {
                    continue;
                }
                OsmPrimitive primitive = inventory.require(current);
                if (primitive.getDataSet() != dataSet || primitive.isDeleted() || primitive.isIncomplete()) {
                    throw new IllegalStateException("Required primitive is incomplete or deleted: " + current);
                }
                if (included.size() >= limits.maximumMaterializedPrimitives()) {
                    throw new IllegalStateException("Network capture materialized primitive budget exceeded");
                }
                chargeTags(primitive.getNumKeys());
                included.add(current);
                if (primitive instanceof Node node) {
                    if (!node.isLatLonKnown()) {
                        throw new IllegalStateException("Required node has unknown coordinates: " + current);
                    }
                } else if (primitive instanceof Way way) {
                    if (way.hasIncompleteNodes() || way.getNodesCount() < 2) {
                        throw new IllegalStateException("Required way geometry is incomplete: " + current);
                    }
                    chargePayloadReferences(way.getNodesCount());
                    for (int index = way.getNodesCount() - 1; index >= 0; index--) {
                        pending.addLast(key(way.getNode(index)));
                    }
                } else if (primitive instanceof Relation relation) {
                    if (relation.hasIncompleteMembers()) {
                        throw new IllegalStateException("Required relation members are incomplete: " + current);
                    }
                    chargePayloadReferences(relation.getMembersCount());
                    for (int index = relation.getMembersCount() - 1; index >= 0; index--) {
                        pending.addLast(key(relation.getMember(index).getMember()));
                    }
                }
            }
        }

        private void chargePayloadReferences(int amount) {
            if (amount < 0 || amount > limits.maximumMaterializedReferences() - payloadReferences) {
                throw new IllegalStateException("Network capture materialized reference budget exceeded");
            }
            payloadReferences += amount;
        }

        private void chargeTags(int amount) {
            if (amount < 0 || amount > limits.maximumTagEntries() - tagEntries) {
                throw new IllegalStateException("Network capture tag budget exceeded");
            }
            tagEntries += amount;
        }

        Set<PrimitiveKey> keys() {
            return Set.copyOf(included);
        }

        Map<PrimitiveKey, DetachedPrimitive> detach() {
            Map<PrimitiveKey, DetachedPrimitive> result = new LinkedHashMap<>();
            included.stream().sorted().forEach(key -> result.put(key, detach(inventory.require(key))));
            return Map.copyOf(result);
        }

        Map<PrimitiveKey, Set<PrimitiveKey>> watches() {
            long total = 0;
            for (PrimitiveKey key : included.stream().sorted().toList()) {
                Set<PrimitiveKey> refs = inventory.referrers(key);
                total += refs.size();
                if (total > limits.maximumWatchIdentities()) {
                    throw new IllegalStateException("Network capture watch identity budget exceeded");
                }
            }
            Map<PrimitiveKey, Set<PrimitiveKey>> result = new LinkedHashMap<>();
            for (PrimitiveKey key : included.stream().sorted().toList()) {
                Set<PrimitiveKey> refs = inventory.referrers(key);
                result.put(key, Set.copyOf(refs));
            }
            return Map.copyOf(result);
        }

        private static DetachedPrimitive detach(OsmPrimitive primitive) {
            PrimitiveKey key = key(primitive);
            Map<String, String> tags = Map.copyOf(primitive.getKeys());
            if (primitive instanceof Node node) {
                return new DetachedNode(key, new GeographicPoint(node.lat(), node.lon()), tags,
                    false, node.isModified());
            }
            if (primitive instanceof Way way) {
                List<PrimitiveKey> nodes = new ArrayList<>(way.getNodesCount());
                for (int index = 0; index < way.getNodesCount(); index++) {
                    nodes.add(key(way.getNode(index)));
                }
                return new DetachedWay(key, nodes, tags, false, way.isModified());
            }
            Relation relation = (Relation) primitive;
            List<DetachedRelationMember> members = new ArrayList<>(relation.getMembersCount());
            for (int index = 0; index < relation.getMembersCount(); index++) {
                RelationMember member = relation.getMember(index);
                members.add(new DetachedRelationMember(key(member.getMember()), member.getRole()));
            }
            return new DetachedRelation(key, members, tags, false, relation.isModified());
        }
    }
}
