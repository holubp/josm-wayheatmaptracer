package org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.accepted;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.frozenCandidate;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.network;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.node;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.nodeId;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.plan;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.reconstructedCandidate;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.way;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.wayId;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.FindingCode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.JunctionCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.LocationFeatureDecision;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.PlanningResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.TopologyEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Id;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.IdentityNamespace;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Node;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Point;

class V022JunctionReattachmentTest {
    private static final long L = 1;
    private static final long J = 2;
    private static final long M = 3;
    private static final long R = 4;
    private static final long A = 5;
    private static final long RECEIVER = 101;
    private static final long SELECTED = 102;

    @Test
    void t081NarrowTInsertsRelocatedJunctionAtCorrectReceiverOccurrence() {
        TopologyNetwork before = narrowT(Map.of(), 0.0);

        TopologyEditPlan edit = accepted(plan(before,
            List.of(frozenCandidate(J, 20.0, 0.0, SELECTED, RECEIVER)), Map.of(), Set.of(),
            RECEIVER, SELECTED));

        assertEquals(List.of(nodeId(L), nodeId(M), nodeId(J), nodeId(R)),
            edit.after().way(wayId(RECEIVER)).nodeIds());
        assertEquals(List.of(nodeId(A), nodeId(J)), edit.after().way(wayId(SELECTED)).nodeIds());
        assertEquals(new Point(20.0, 0.0), edit.after().node(nodeId(J)).point());
        assertFalse(hasImmediateBacktrack(edit.after().way(wayId(RECEIVER)).nodeIds()));
    }

    @Test
    void t082OldReceiverBendIsRetainedAsAnUntaggedPerWayShapeNode() {
        TopologyNetwork before = narrowT(Map.of(), 3.0);

        TopologyEditPlan edit = accepted(plan(before,
            List.of(frozenCandidate(J, 19.0, 0.0, SELECTED, RECEIVER)), Map.of(), Set.of(),
            RECEIVER, SELECTED));

        List<Id> receiverNodes = edit.after().way(wayId(RECEIVER)).nodeIds();
        assertEquals(5, receiverNodes.size());
        Id retainedBend = receiverNodes.get(1);
        assertEquals(IdentityNamespace.PLAN_LOCAL, retainedBend.identityNamespace());
        assertEquals(new Point(0.0, 3.0), edit.after().node(retainedBend).point());
        assertTrue(edit.after().node(retainedBend).tags().isEmpty());
        assertEquals(List.of(nodeId(L), retainedBend, nodeId(M), nodeId(J), nodeId(R)), receiverNodes);
        assertFalse(edit.after().way(wayId(SELECTED)).nodeIds().contains(retainedBend));
    }

    @Test
    void t083SplitReceiverRebuildsBothWayHalvesWithoutChangingWayIdentity() {
        long left = 111;
        long right = 112;
        TopologyNetwork before = network(
            List.of(node(L, -60, 0), node(J, 0, 0), node(M, 10, 0), node(R, 60, 0), node(A, 20, 60)),
            List.of(way(left, L, J), way(right, J, M, R), way(SELECTED, A, J)));

        TopologyEditPlan edit = accepted(plan(before,
            List.of(frozenCandidate(J, 20.0, 0.0, SELECTED, left, right)), Map.of(), Set.of(),
            left, right, SELECTED));

        assertEquals(List.of(nodeId(L), nodeId(M), nodeId(J)), edit.after().way(wayId(left)).nodeIds());
        assertEquals(List.of(nodeId(J), nodeId(R)), edit.after().way(wayId(right)).nodeIds());
        assertEquals(before.ways().keySet(), edit.after().ways().keySet());

        TopologyNetwork taggedBoundary = network(
            List.of(node(L, -60, 0), node(J, 0, 0), node(M, 10, 0), node(R, 60, 0), node(A, 20, 60)),
            List.of(way(left, Map.of("highway", "primary", "maxspeed", "30"), L, J),
                way(right, Map.of("highway", "primary", "maxspeed", "50"), J, M, R),
                way(SELECTED, A, J)));
        PlanningResult boundaryResult = plan(taggedBoundary,
            List.of(frozenCandidate(J, 20.0, 0.0, SELECTED, left, right)), Map.of(), Set.of(),
            left, right, SELECTED);
        assertFalse(boundaryResult.accepted());
        assertTrue(boundaryResult.hasFinding(FindingCode.SEMANTIC_BOUNDARY_RELOCATION_UNSUPPORTED));
    }

    @Test
    void t084InteriorJunctionRelocationPreservesBothSidesOfEveryContinuation() {
        long west = 11;
        long east = 12;
        long south = 13;
        long north = 14;
        long horizontal = 121;
        long vertical = 122;
        TopologyNetwork before = network(
            List.of(node(west, -60, 0), node(J, 7, 6), node(east, 60, 0),
                node(south, 0, -60), node(north, 0, 60)),
            List.of(way(horizontal, west, J, east), way(vertical, south, J, north)));

        TopologyEditPlan edit = accepted(plan(before,
            List.of(reconstructedCandidate(J, 0.0, 0.0, horizontal)), Map.of(), Set.of(),
            horizontal, vertical));

        assertEquals(List.of(nodeId(west), nodeId(J), nodeId(east)),
            edit.after().way(wayId(horizontal)).nodeIds());
        assertEquals(List.of(nodeId(south), nodeId(J), nodeId(north)),
            edit.after().way(wayId(vertical)).nodeIds());
        assertEquals(4, incidenceCount(edit.after(), nodeId(J)));
        assertTrue(edit.changedPrimitiveIds().containsAll(Set.of(wayId(horizontal), wayId(vertical))));
    }

    @Test
    void t085MultiArmRelocationRetainsAllIncidentArmsAndIgnoresNearbyUnconnectedPaths() {
        long west = 11;
        long east = 12;
        long south = 13;
        long north = 14;
        long diagonal = 15;
        long unrelatedA = 16;
        long unrelatedB = 17;
        long horizontal = 131;
        long vertical = 132;
        long diagonalWay = 133;
        long unrelatedWay = 134;
        TopologyNetwork before = network(
            List.of(node(west, -60, 0), node(J, 7, 6), node(east, 60, 0),
                node(south, 0, -60), node(north, 0, 60), node(diagonal, 50, 50),
                node(unrelatedA, -5, 10), node(unrelatedB, 40, 10)),
            List.of(way(horizontal, west, J, east), way(vertical, south, J, north),
                way(diagonalWay, J, diagonal), way(unrelatedWay, unrelatedA, unrelatedB)));

        TopologyEditPlan edit = accepted(plan(before,
            List.of(reconstructedCandidate(J, 0.0, 0.0, horizontal)), Map.of(), Set.of(),
            horizontal, vertical, diagonalWay));

        assertEquals(5, incidenceCount(edit.after(), nodeId(J)));
        assertEquals(before.way(wayId(unrelatedWay)), edit.after().way(wayId(unrelatedWay)));
        assertFalse(edit.after().way(wayId(unrelatedWay)).nodeIds().contains(nodeId(J)));
    }

    @Test
    void t086CoupledJunctionsArePlannedJointlyAndCannotInvertReceiverOrder() {
        long j2 = 6;
        long branchB = 7;
        long receiver = 141;
        long selectedA = 142;
        long selectedB = 143;
        TopologyNetwork before = network(
            List.of(node(L, -60, 0), node(J, 35, 0), node(M, 30, 0), node(j2, 25, 0),
                node(R, 60, 0), node(A, 20, 50), node(branchB, 40, 50)),
            List.of(way(receiver, L, J, M, j2, R), way(selectedA, A, J), way(selectedB, branchB, j2)));
        JunctionCandidate first = frozenCandidate(J, 20.0, 0.0, selectedA, receiver);
        JunctionCandidate second = frozenCandidate(j2, 40.0, 0.0, selectedB, receiver);

        TopologyEditPlan edit = accepted(plan(before, List.of(first, second), Map.of(), Set.of(),
            receiver, selectedA, selectedB));
        assertEquals(List.of(nodeId(L), nodeId(J), nodeId(M), nodeId(j2), nodeId(R)),
            edit.after().way(wayId(receiver)).nodeIds());

        PlanningResult reversed = plan(before,
            List.of(frozenCandidate(J, 40.0, 0.0, selectedA, receiver),
                frozenCandidate(j2, 20.0, 0.0, selectedB, receiver)),
            Map.of(), Set.of(), receiver, selectedA, selectedB);
        assertFalse(reversed.accepted());
        assertTrue(reversed.hasFinding(FindingCode.COUPLED_ORDER_INVERSION));
    }

    @Test
    void t087TerminalReattachmentDoesNotDeleteARealBendToChooseAShorterPath() {
        long b = 18;
        long c = 19;
        TopologyNetwork before = network(
            List.of(node(A, 20, 60), node(b, 5, 45), node(c, 30, 25), node(J, 0, 0),
                node(L, -60, 0), node(R, 60, 0)),
            List.of(way(SELECTED, A, b, c, J), way(RECEIVER, L, J, R)));

        TopologyEditPlan edit = accepted(plan(before,
            List.of(frozenCandidate(J, 20.0, 0.0, SELECTED, RECEIVER)), Map.of(), Set.of(),
            SELECTED, RECEIVER));

        assertEquals(List.of(nodeId(A), nodeId(b), nodeId(c), nodeId(J)),
            edit.after().way(wayId(SELECTED)).nodeIds());
        assertEquals(before.node(nodeId(b)), edit.after().node(nodeId(b)));
        assertEquals(before.node(nodeId(c)), edit.after().node(nodeId(c)));
    }

    @Test
    void t088NodesAndWaysOutsideTheAuthorizedRegionRemainBitForBitUnchanged() {
        long outside = 20;
        long contextA = 21;
        long contextB = 22;
        long contextWay = 151;
        TopologyNetwork before = network(
            List.of(node(outside, 0, 120), node(A, 20, 60), node(J, 0, 0), node(L, -60, 0),
                node(M, 10, 0), node(R, 60, 0), node(contextA, 100, 100), node(contextB, 140, 100)),
            List.of(way(SELECTED, outside, A, J), way(RECEIVER, L, J, M, R),
                way(contextWay, contextA, contextB)));

        TopologyEditPlan edit = accepted(plan(before,
            List.of(frozenCandidate(J, 20.0, 0.0, SELECTED, RECEIVER)), Map.of(), Set.of(),
            SELECTED, RECEIVER));

        assertEquals(before.node(nodeId(outside)), edit.after().node(nodeId(outside)));
        assertEquals(before.node(nodeId(contextA)), edit.after().node(nodeId(contextA)));
        assertEquals(before.node(nodeId(contextB)), edit.after().node(nodeId(contextB)));
        assertEquals(before.way(wayId(contextWay)), edit.after().way(wayId(contextWay)));
    }

    @Test
    void t089LocationBoundOldNodeCanBeRetainedWhileOneNewJunctionIdentityIsShared() {
        TopologyNetwork before = narrowT(Map.of("barrier", "gate"), 0.0);

        TopologyEditPlan edit = accepted(plan(before,
            List.of(frozenCandidate(J, 20.0, 0.0, SELECTED, RECEIVER)),
            Map.of(nodeId(J), LocationFeatureDecision.RETAIN_AT_OLD_LOCATION), Set.of(),
            RECEIVER, SELECTED));

        Id newJunction = edit.junctionIdentities().get(nodeId(J));
        assertNotEquals(nodeId(J), newJunction);
        assertEquals(IdentityNamespace.PLAN_LOCAL, newJunction.identityNamespace());
        assertEquals(new Point(0.0, 0.0), edit.after().node(nodeId(J)).point());
        assertEquals(Map.of("barrier", "gate"), edit.after().node(nodeId(J)).tags());
        assertEquals(3, incidenceCount(edit.after(), newJunction));
        assertFalse(edit.after().way(wayId(SELECTED)).nodeIds().contains(nodeId(J)));
    }

    @Test
    void t090UnrelatedExistingNodeCollisionIsRejectedInsteadOfSilentlyMerged() {
        long collision = 23;
        long context = 24;
        long contextWay = 161;
        TopologyNetwork before = network(
            List.of(node(L, -60, 0), node(J, 0, 0), node(M, 10, 0), node(R, 60, 0),
                node(A, 20, 60), node(collision, 20, 0), node(context, 20, -20)),
            List.of(way(RECEIVER, L, J, M, R), way(SELECTED, A, J), way(contextWay, context, collision)));

        PlanningResult result = plan(before,
            List.of(frozenCandidate(J, 20.0, 0.0, SELECTED, RECEIVER)), Map.of(), Set.of(),
            RECEIVER, SELECTED);

        assertFalse(result.accepted());
        assertTrue(result.hasFinding(FindingCode.EXISTING_NODE_COLLISION));
    }

    private static TopologyNetwork narrowT(Map<String, String> junctionTags, double junctionY) {
        return network(
            List.of(node(L, -60, 0), node(J, 0, junctionY, junctionTags), node(M, 10, 0),
                node(R, 60, 0), node(A, 20, 60)),
            List.of(way(RECEIVER, L, J, M, R), way(SELECTED, A, J)));
    }

    private static int incidenceCount(TopologyNetwork network, Id nodeId) {
        int count = 0;
        for (TopologyNetwork.Way way : network.ways().values()) {
            for (int index = 1; index < way.nodeIds().size(); index++) {
                if (way.nodeIds().get(index - 1).equals(nodeId) || way.nodeIds().get(index).equals(nodeId)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean hasImmediateBacktrack(List<Id> nodeIds) {
        for (int index = 2; index < nodeIds.size(); index++) {
            if (nodeIds.get(index - 2).equals(nodeIds.get(index))) {
                return true;
            }
        }
        return false;
    }
}
