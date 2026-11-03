package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Lock;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.SelectionIntegrity;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Bounded live selected-occurrence and incoming-referrer witness under one dataset read lock. */
public final class ManagedSelectionWitness implements AutoCloseable {
    private static final int MAX_FULL_WAY_NODES = 20_000;
    private static final int MAX_RELEVANT_PRIMITIVES = 2_048;
    private static final int MAX_RELEVANT_MEMBERS = 20_000;
    private static final int MAX_RELEVANT_TAG_ENTRIES = 20_000;

    private record NodeFact(Node node, LatLon coordinate, Map<String, String> tags, boolean fixed) { }
    private record WayFact(Way way, List<Node> nodes, List<LatLon> coordinates,
            Map<String, String> tags) { }
    private record MemberFact(OsmPrimitive primitive, String role) { }
    private record RelationFact(Relation relation, List<MemberFact> members,
            Map<String, String> tags) { }
    private record Snapshot(List<NodeFact> fullWay, Map<Way, WayFact> incidentWays,
            Map<Relation, RelationFact> incomingRelations) { }

    private final DataSet dataSet;
    private final SelectionContext selection;
    private final Projection projection;
    private final List<EastNorth> originalSource;
    private final Snapshot frozen;
    private final AttemptMemoryLedger.Owner owner;
    private final List<AttemptMemoryLedger.Owner> retainedOwners = new ArrayList<>();

    private ManagedSelectionWitness(DataSet dataSet, SelectionContext selection,
            Projection projection, List<EastNorth> originalSource, Snapshot frozen,
            AttemptMemoryLedger.Owner owner) {
        this.dataSet = dataSet;
        this.selection = selection;
        this.projection = projection;
        this.originalSource = originalSource;
        this.frozen = frozen;
        this.owner = owner;
    }

    /** Captures only dependencies reachable from selected nodes/way through live referrers. */
    public static ManagedSelectionWitness capture(DataSet dataSet, SelectionContext selection,
            Projection projection, List<EastNorth> originalSource, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner parent) {
        Objects.requireNonNull(dataSet, "active dataset");
        Objects.requireNonNull(selection, "selected occurrence range");
        Objects.requireNonNull(projection, "captured projection");
        Objects.requireNonNull(originalSource, "original selected source");
        Objects.requireNonNull(budget, "shared work budget");
        Objects.requireNonNull(parent, "attempt owner");
        budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
        if (originalSource.size() < 2 || originalSource.size() > 4097
                || selection.way() == null || selection.way().getNodesCount() > MAX_FULL_WAY_NODES) {
            throw new IllegalArgumentException("Managed selected source exceeds bounded witness");
        }
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        var owner = parent.child("managed-selection-witness");
        boolean complete = false;
        try {
            long bytes = Math.addExact(16384L, Math.addExact(
                    Math.multiplyExact((long) selection.way().getNodesCount(), 256L),
                    Math.addExact(MAX_RELEVANT_PRIMITIVES * 512L,
                            MAX_RELEVANT_MEMBERS * 64L)));
            var reservation = owner.reserve(bytes);
            List<EastNorth> sourceCopy = List.copyOf(originalSource);
            Snapshot frozen = snapshot(dataSet, selection, projection, sourceCopy, budget);
            var result = new ManagedSelectionWitness(dataSet, selection, projection,
                    sourceCopy, frozen, owner);
            reservation.adopt(result);
            complete = true;
            return result;
        } finally {
            if (!complete) owner.close();
        }
    }

    /** Rechecks exact source/referrer facts under the same read-lock rule before a later decision. */
    public void requireCurrent(EmissionWorkBudget budget, AttemptMemoryLedger.Owner scratchParent) {
        if (!isLive()) throw new IllegalStateException("Managed selection witness is closed");
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        try (var scratch = scratchParent.child("managed-selection-recheck")) {
            long bytes = Math.addExact(16384L, Math.addExact(
                    Math.multiplyExact((long) frozen.fullWay().size(), 256L),
                    Math.addExact(MAX_RELEVANT_PRIMITIVES * 512L,
                            MAX_RELEVANT_MEMBERS * 64L)));
            scratch.reserve(bytes);
            Snapshot current = snapshot(dataSet, selection, projection, originalSource, budget);
            if (!sameSnapshot(frozen, current)) {
                throw new IllegalStateException("Managed selected source or relevant referrers changed");
            }
        }
    }

    private static Snapshot snapshot(DataSet dataSet, SelectionContext selection,
            Projection projection, List<EastNorth> originalSource,
            EmissionWorkBudget budget) {
        Lock lock = dataSet.getReadLock();
        lock.lock();
        try {
            if (ProjectionRegistry.getProjection() != projection
                    || selection.way().getDataSet() != dataSet || selection.way().isDeleted()
                    || selection.way().isIncomplete() || selection.way().hasIncompleteNodes()) {
                throw new IllegalStateException("Managed selection source or projection changed");
            }
            Way selectedWay = selection.way();
            int start = selection.startIndex(), end = selection.endIndex();
            List<Node> fullNodes = selectedWay.getNodes();
            if (start < 0 || end >= fullNodes.size() || end <= start
                    || end - start + 1 != originalSource.size()
                    || selection.segmentNodes().size() != originalSource.size()
                    || fullNodes.size() > MAX_FULL_WAY_NODES) {
                throw new IllegalStateException("Managed selected occurrence range changed");
            }
            SelectionIntegrity.requireNoRepeatedNodeOccurrences(selectedWay, start, end);
            List<NodeFact> fullFacts = new ArrayList<>(fullNodes.size());
            int tagEntries = 0;
            for (int index = 0; index < fullNodes.size(); index++) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                Node node = fullNodes.get(index);
                if (node == null || node.getDataSet() != dataSet || node.isDeleted()
                        || node.isIncomplete() || node.getCoor() == null) {
                    throw new IllegalStateException("Managed selected way has an incomplete node");
                }
                LatLon coordinate = node.getCoor();
                Map<String, String> keys = node.getKeys();
                tagEntries = Math.addExact(tagEntries, keys.size());
                if (tagEntries > MAX_RELEVANT_TAG_ENTRIES) {
                    throw new IllegalStateException("Managed relevant tags exceed bound");
                }
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, keys.size());
                fullFacts.add(new NodeFact(node,
                        new LatLon(coordinate.lat(), coordinate.lon()),
                        Map.copyOf(keys), selection.fixedNodes().contains(node)));
                if (index >= start && index <= end) {
                    int row = index - start;
                    if (selection.segmentNodes().get(row) != node) {
                        throw new IllegalStateException("Managed selected node identity changed");
                    }
                    EastNorth projected = node.getEastNorth(projection), expected = originalSource.get(row);
                    if (projected == null || expected == null
                            || Double.doubleToLongBits(projected.east())
                                != Double.doubleToLongBits(expected.east())
                            || Double.doubleToLongBits(projected.north())
                                != Double.doubleToLongBits(expected.north())) {
                        throw new IllegalStateException("Managed selected source projection differs");
                    }
                }
            }
            Set<OsmPrimitive> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            ArrayDeque<OsmPrimitive> queue = new ArrayDeque<>();
            queue.add(selectedWay);
            for (int index = start; index <= end; index++) queue.add(fullNodes.get(index));
            Map<Way, WayFact> ways = new IdentityHashMap<>();
            Map<Relation, RelationFact> relations = new IdentityHashMap<>();
            int memberVisits = 0;
            while (!queue.isEmpty()) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                OsmPrimitive primitive = queue.removeFirst();
                if (!visited.add(primitive)) continue;
                if (visited.size() > MAX_RELEVANT_PRIMITIVES) {
                    throw new IllegalStateException("Managed selected referrer closure exceeds bound");
                }
                if (primitive.getDataSet() != dataSet || primitive.isDeleted()
                        || primitive.isIncomplete()) {
                    throw new IllegalStateException("Relevant managed referrer is incomplete");
                }
                if (primitive instanceof Way way) {
                    if (way.hasIncompleteNodes() || way.getNodesCount() < 2) {
                        throw new IllegalStateException("Relevant managed way is incomplete");
                    }
                    budget.charge(EmissionWorkBudget.Operation.PREPARATION, way.getNodesCount());
                    memberVisits = Math.addExact(memberVisits, way.getNodesCount());
                    if (memberVisits > MAX_RELEVANT_MEMBERS) {
                        throw new IllegalStateException("Managed relevant members exceed bound");
                    }
                    List<Node> wayNodes = way.getNodes();
                    List<LatLon> wayCoordinates = new ArrayList<>(wayNodes.size());
                    for (Node node : wayNodes) {
                        if (node == null || node.getDataSet() != dataSet || node.isDeleted()
                                || node.isIncomplete() || node.getCoor() == null) {
                            throw new IllegalStateException("Relevant managed way node is incomplete");
                        }
                        LatLon coordinate = node.getCoor();
                        wayCoordinates.add(new LatLon(coordinate.lat(), coordinate.lon()));
                    }
                    Map<String, String> keys = way.getKeys();
                    tagEntries = Math.addExact(tagEntries, keys.size());
                    if (tagEntries > MAX_RELEVANT_TAG_ENTRIES) {
                        throw new IllegalStateException("Managed relevant tags exceed bound");
                    }
                    budget.charge(EmissionWorkBudget.Operation.PREPARATION, keys.size());
                    ways.put(way, new WayFact(way, List.copyOf(wayNodes), List.copyOf(wayCoordinates),
                            Map.copyOf(keys)));
                } else if (primitive instanceof Relation relation) {
                    // A loaded route may name a distant unloaded member. Its ID and role are
                    // local membership facts; distant geometry is outside this selected edit.
                    budget.charge(EmissionWorkBudget.Operation.PREPARATION, relation.getMembersCount());
                    memberVisits = Math.addExact(memberVisits, relation.getMembersCount());
                    if (memberVisits > MAX_RELEVANT_MEMBERS) {
                        throw new IllegalStateException("Managed relevant members exceed bound");
                    }
                    List<MemberFact> members = new ArrayList<>(relation.getMembersCount());
                    for (RelationMember member : relation.getMembers()) {
                        OsmPrimitive target = member.getMember();
                        if (target == null) {
                            throw new IllegalStateException("Relevant managed relation member is absent");
                        }
                        members.add(new MemberFact(target, member.getRole()));
                    }
                    Map<String, String> keys = relation.getKeys();
                    tagEntries = Math.addExact(tagEntries, keys.size());
                    if (tagEntries > MAX_RELEVANT_TAG_ENTRIES) {
                        throw new IllegalStateException("Managed relevant tags exceed bound");
                    }
                    budget.charge(EmissionWorkBudget.Operation.PREPARATION, keys.size());
                    relations.put(relation, new RelationFact(relation, List.copyOf(members),
                            Map.copyOf(keys)));
                }
                for (OsmPrimitive referrer : primitive.getReferrers()) {
                    budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                    memberVisits++;
                    if (memberVisits > MAX_RELEVANT_MEMBERS) {
                        throw new IllegalStateException("Managed relevant referrers exceed bound");
                    }
                    if (referrer == null || referrer.getDataSet() != dataSet
                            || referrer.isIncomplete()) {
                        throw new IllegalStateException("Relevant managed incoming referrer is incomplete");
                    }
                    if (referrer.isDeleted()) continue;
                    if (referrer instanceof Way way && primitive instanceof Node node) {
                        budget.charge(EmissionWorkBudget.Operation.PREPARATION, way.getNodesCount());
                        if (!way.getNodes().contains(node)) {
                            throw new IllegalStateException("Managed incoming way backlink is inconsistent");
                        }
                    } else if (referrer instanceof Relation relation) {
                        budget.charge(EmissionWorkBudget.Operation.PREPARATION, relation.getMembersCount());
                        boolean linked = false;
                        for (RelationMember member : relation.getMembers()) {
                            if (member.getMember() == primitive) linked = true;
                        }
                        if (!linked) {
                            throw new IllegalStateException("Managed incoming relation backlink is inconsistent");
                        }
                    } else {
                        throw new IllegalStateException("Managed incoming referrer type is unsupported");
                    }
                    queue.addLast(referrer);
                }
            }
            return new Snapshot(List.copyOf(fullFacts), ways, relations);
        } finally {
            lock.unlock();
        }
    }

    private static boolean sameSnapshot(Snapshot first, Snapshot second) {
        if (first.fullWay().size() != second.fullWay().size()
                || first.incidentWays().size() != second.incidentWays().size()
                || first.incomingRelations().size() != second.incomingRelations().size()) return false;
        for (int index = 0; index < first.fullWay().size(); index++) {
            NodeFact a = first.fullWay().get(index), b = second.fullWay().get(index);
            if (a.node() != b.node() || !sameCoordinate(a.coordinate(), b.coordinate())
                    || !a.tags().equals(b.tags()) || a.fixed() != b.fixed()) return false;
        }
        for (var entry : first.incidentWays().entrySet()) {
            WayFact a = entry.getValue(), b = second.incidentWays().get(entry.getKey());
            if (b == null || !a.tags().equals(b.tags()) || !sameNodes(a.nodes(), b.nodes())
                    || !sameCoordinates(a.coordinates(), b.coordinates())) return false;
        }
        for (var entry : first.incomingRelations().entrySet()) {
            RelationFact a = entry.getValue(), b = second.incomingRelations().get(entry.getKey());
            if (b == null || !a.tags().equals(b.tags()) || a.members().size() != b.members().size()) return false;
            for (int index = 0; index < a.members().size(); index++) {
                MemberFact left = a.members().get(index), right = b.members().get(index);
                if (left.primitive() != right.primitive() || !left.role().equals(right.role())) return false;
            }
        }
        return true;
    }

    private static boolean sameNodes(List<Node> first, List<Node> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) if (first.get(i) != second.get(i)) return false;
        return true;
    }

    private static boolean sameCoordinate(LatLon first, LatLon second) {
        return Double.doubleToLongBits(first.lat()) == Double.doubleToLongBits(second.lat())
                && Double.doubleToLongBits(first.lon()) == Double.doubleToLongBits(second.lon());
    }

    private static boolean sameCoordinates(List<LatLon> first, List<LatLon> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            if (!sameCoordinate(first.get(i), second.get(i))) return false;
        }
        return true;
    }

    public void retainFor(AttemptMemoryLedger.Owner coOwner) {
        if (!isLive()) throw new IllegalStateException("Managed selection witness is closed");
        coOwner.retain(this);
        synchronized (retainedOwners) { retainedOwners.add(coOwner); }
    }

    private boolean isLive() {
        if (!owner.isClosed()) return true;
        synchronized (retainedOwners) {
            return retainedOwners.stream().anyMatch(retainer -> !retainer.isClosed());
        }
    }

    @Override public void close() { owner.close(); }
    public DataSet dataSet() { return dataSet; }
    public SelectionContext selection() { return selection; }
    public Projection projection() { return projection; }
    public List<EastNorth> originalSource() { return originalSource; }
    public List<Node> selectedOccurrences() {
        return selection.segmentNodes();
    }
    public boolean isProtected(Node node) {
        int index = -1;
        for (int i = selection.startIndex(); i <= selection.endIndex(); i++) {
            if (frozen.fullWay().get(i).node() == node) { index = i; break; }
        }
        if (index < selection.startIndex() || index > selection.endIndex()) return false;
        NodeFact fact = frozen.fullWay().get(index);
        if (index == selection.startIndex() || index == selection.endIndex()
                || fact.fixed() || !fact.tags().isEmpty()) return true;
        int incidentWays = 0;
        for (WayFact way : frozen.incidentWays().values()) {
            if (way.nodes().contains(node)) incidentWays++;
        }
        if (incidentWays > 1) return true;
        for (RelationFact relation : frozen.incomingRelations().values()) {
            for (MemberFact member : relation.members()) {
                if (member.primitive() == node) return true;
            }
        }
        return false;
    }
}
