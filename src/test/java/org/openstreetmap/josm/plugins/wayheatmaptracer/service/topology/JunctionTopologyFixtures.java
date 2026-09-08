package org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.Bounds;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.JunctionCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.LocationFeatureDecision;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.Permissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.PlanningResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.ReattachmentRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.ReceiverGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.ReceiverPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Completeness;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Id;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Node;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Point;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.PrimitiveType;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Relation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.RelationMember;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Way;

final class JunctionTopologyFixtures {
    static final Bounds EDIT_REGION = new Bounds(-80.0, -80.0, 80.0, 80.0);

    private JunctionTopologyFixtures() {
        // Test fixture utility.
    }

    static Id nodeId(long value) {
        return Id.existing(PrimitiveType.NODE, value);
    }

    static Id wayId(long value) {
        return Id.existing(PrimitiveType.WAY, value);
    }

    static Id relationId(long value) {
        return Id.existing(PrimitiveType.RELATION, value);
    }

    static Node node(long id, double x, double y) {
        return node(id, x, y, Map.of());
    }

    static Node node(long id, double x, double y, Map<String, String> tags) {
        return new Node(nodeId(id), new Point(x, y), tags);
    }

    static Way way(long id, Map<String, String> tags, long... nodeIds) {
        List<Id> keys = new ArrayList<>(nodeIds.length);
        for (long nodeId : nodeIds) {
            keys.add(nodeId(nodeId));
        }
        return new Way(wayId(id), keys, tags);
    }

    static Way way(long id, long... nodeIds) {
        return way(id, Map.of("highway", "path"), nodeIds);
    }

    static Relation relation(long id, Map<String, String> tags, Completeness completeness,
        RelationMember... members) {
        return new Relation(relationId(id), List.of(members), tags, completeness);
    }

    static RelationMember member(Id id, String role) {
        return new RelationMember(id, role);
    }

    static TopologyNetwork network(List<Node> nodes, List<Way> ways) {
        return new TopologyNetwork(nodes, ways, List.of());
    }

    static TopologyNetwork network(List<Node> nodes, List<Way> ways, List<Relation> relations) {
        return new TopologyNetwork(nodes, ways, relations);
    }

    static JunctionCandidate frozenCandidate(long junctionNodeId, double x, double y,
        long selectedWayId, long... receivingWayIds) {
        List<Id> receiverIds = new ArrayList<>(receivingWayIds.length);
        for (long wayId : receivingWayIds) {
            receiverIds.add(wayId(wayId));
        }
        List<ReceiverGroup> groups = receiverIds.isEmpty()
            ? List.of() : List.of(new ReceiverGroup(receiverIds));
        return new JunctionCandidate(nodeId(junctionNodeId), new Point(x, y), wayId(selectedWayId),
            groups, ReceiverPolicy.FROZEN_LOCUS, 0.1);
    }

    static JunctionCandidate reconstructedCandidate(long junctionNodeId, double x, double y,
        long selectedWayId) {
        return new JunctionCandidate(nodeId(junctionNodeId), new Point(x, y), wayId(selectedWayId),
            List.of(), ReceiverPolicy.RECONSTRUCT_INCIDENT, 0.1);
    }

    static PlanningResult plan(TopologyNetwork network, List<JunctionCandidate> candidates,
        Map<Id, LocationFeatureDecision> featureDecisions, Set<Id> approvedRelationRemaps,
        long... editableWayIds) {
        Set<Id> editable = new LinkedHashSet<>();
        for (long wayId : editableWayIds) {
            editable.add(wayId(wayId));
        }
        boolean reconstruct = candidates.stream()
            .anyMatch(candidate -> candidate.receiverPolicy() == ReceiverPolicy.RECONSTRUCT_INCIDENT);
        ReattachmentRequest request = new ReattachmentRequest(network, candidates, editable,
            EDIT_REGION, new Permissions(true, reconstruct), featureDecisions,
            approvedRelationRemaps, 0.05);
        return new JunctionReattachmentPlanner().plan(request);
    }

    static JunctionReattachmentPlanner.TopologyEditPlan accepted(PlanningResult result) {
        assertTrue(result.accepted(), () -> "Expected accepted plan, got " + result.findings());
        return result.plan().orElseThrow();
    }
}
