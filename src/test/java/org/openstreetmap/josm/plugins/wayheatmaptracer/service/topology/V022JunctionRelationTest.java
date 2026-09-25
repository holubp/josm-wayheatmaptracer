package org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.accepted;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.frozenCandidate;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.member;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.network;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.node;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.nodeId;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.plan;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.reconstructedCandidate;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.relation;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.relationId;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.way;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionTopologyFixtures.wayId;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.FindingCode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.LocationFeatureDecision;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.PlanningResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.JunctionReattachmentPlanner.TopologyEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Completeness;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Id;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.topology.TopologyNetwork.Relation;

class V022JunctionRelationTest {
    private static final long L = 31;
    private static final long J = 32;
    private static final long M = 33;
    private static final long R = 34;
    private static final long A = 35;
    private static final long K = 36;
    private static final long T = 37;
    private static final long RECEIVER = 201;
    private static final long SELECTED = 202;

    @Test
    void t091ViaNodeRestrictionKeepsOrExplicitlyRemapsViaIdentityAndIncidence() {
        Relation restriction = relation(301, Map.of("type", "restriction", "restriction", "no_left_turn"),
            Completeness.COMPLETE, member(wayId(RECEIVER), "from"), member(nodeId(J), "via"),
            member(wayId(SELECTED), "to"));
        TopologyNetwork before = narrowT(List.of(restriction), Map.of(), Map.of("highway", "path"));

        TopologyEditPlan edit = accepted(plan(before,
            List.of(frozenCandidate(J, 20, 0, SELECTED, RECEIVER)), Map.of(), Set.of(),
            RECEIVER, SELECTED));

        assertEquals(restriction, edit.after().relation(relationId(301)));
        assertTrue(edit.after().way(wayId(RECEIVER)).nodeIds().contains(nodeId(J)));
        assertTrue(edit.after().way(wayId(SELECTED)).nodeIds().contains(nodeId(J)));

        TopologyNetwork featureBefore = narrowT(List.of(restriction), Map.of("barrier", "gate"),
            Map.of("highway", "path"));
        TopologyEditPlan remapped = accepted(plan(featureBefore,
            List.of(frozenCandidate(J, 20, 0, SELECTED, RECEIVER)),
            Map.of(nodeId(J), LocationFeatureDecision.RETAIN_AT_OLD_LOCATION), Set.of(nodeId(J)),
            RECEIVER, SELECTED));
        Id newVia = remapped.junctionIdentities().get(nodeId(J));
        assertEquals(newVia, remapped.after().relation(relationId(301)).members().get(1).memberId());
        assertTrue(remapped.after().way(wayId(RECEIVER)).nodeIds().contains(newVia));
        assertTrue(remapped.after().way(wayId(SELECTED)).nodeIds().contains(newVia));
    }

    @Test
    void t092ViaWayRestrictionPreservesOrderedTraversalAndEntryExitConnections() {
        long from = 211;
        long via = 212;
        long to = 213;
        Relation restriction = relation(302, Map.of("type", "restriction", "restriction", "only_straight_on"),
            Completeness.COMPLETE, member(wayId(from), "from"), member(wayId(via), "via"),
            member(wayId(to), "to"));
        TopologyNetwork before = network(
            List.of(node(L, -10, 0), node(J, 0, 0), node(K, 20, 0), node(T, 40, 0), node(A, 10, 30)),
            List.of(way(from, A, J), way(via, L, J, K), way(to, K, T)), List.of(restriction));

        TopologyEditPlan edit = accepted(plan(before,
            List.of(frozenCandidate(J, 10, 0, from, via)), Map.of(), Set.of(), from, via));

        assertEquals(restriction.members(), edit.after().relation(relationId(302)).members());
        assertTrue(sharesNode(edit.after(), wayId(from), wayId(via)));
        assertTrue(sharesNode(edit.after(), wayId(via), wayId(to)));
    }

    @Test
    void t093RestrictionExceptAndConditionalTagsArePreservedExactly() {
        Map<String, String> tags = Map.of(
            "type", "restriction",
            "restriction", "no_right_turn",
            "except", "bicycle;psv",
            "restriction:conditional", "no_right_turn @ (Mo-Fr 07:00-09:00)");
        Relation restriction = relation(303, tags, Completeness.COMPLETE,
            member(wayId(RECEIVER), "from"), member(nodeId(J), "via"), member(wayId(SELECTED), "to"));
        TopologyNetwork before = narrowT(List.of(restriction), Map.of(), Map.of("highway", "path"));

        TopologyEditPlan edit = accepted(plan(before,
            List.of(frozenCandidate(J, 20, 0, SELECTED, RECEIVER)), Map.of(), Set.of(),
            RECEIVER, SELECTED));

        assertEquals(tags, edit.after().relation(relationId(303)).tags());
    }

    @Test
    void t094RouteRelationPreservesWayIdsMemberOrderRolesAndLocalContinuity() {
        long left = 221;
        long right = 222;
        Relation route = relation(304, Map.of("type", "route", "route", "hiking"), Completeness.COMPLETE,
            member(wayId(left), "forward"), member(wayId(right), "backward"));
        TopologyNetwork before = network(
            List.of(node(L, -60, 0), node(J, 0, 0), node(M, 10, 0), node(R, 60, 0), node(A, 20, 60)),
            List.of(way(left, L, J), way(right, J, M, R), way(SELECTED, A, J)), List.of(route));

        TopologyEditPlan edit = accepted(plan(before,
            List.of(frozenCandidate(J, 20, 0, SELECTED, left, right)), Map.of(), Set.of(),
            left, right, SELECTED));

        assertEquals(route, edit.after().relation(relationId(304)));
        assertTrue(sharesNode(edit.after(), wayId(left), wayId(right)));
    }

    @Test
    void t095LocationBoundTagRequiresExplicitDecisionAndIsNeverDuplicatedOrDropped() {
        TopologyNetwork before = narrowT(List.of(), Map.of("highway", "traffic_signals"),
            Map.of("highway", "path"));
        PlanningResult unresolved = plan(before,
            List.of(frozenCandidate(J, 20, 0, SELECTED, RECEIVER)), Map.of(), Set.of(),
            RECEIVER, SELECTED);
        assertFalse(unresolved.accepted());
        assertTrue(unresolved.hasFinding(FindingCode.LOCATION_FEATURE_DECISION_REQUIRED));

        TopologyEditPlan edit = accepted(plan(before,
            List.of(frozenCandidate(J, 20, 0, SELECTED, RECEIVER)),
            Map.of(nodeId(J), LocationFeatureDecision.RETAIN_AT_OLD_LOCATION), Set.of(),
            RECEIVER, SELECTED));
        Id replacement = edit.junctionIdentities().get(nodeId(J));
        assertEquals(Map.of("highway", "traffic_signals"), edit.after().node(nodeId(J)).tags());
        assertTrue(edit.after().node(replacement).tags().isEmpty());
        assertEquals(1, edit.after().nodes().values().stream()
            .filter(node -> "traffic_signals".equals(node.tags().get("highway"))).count());
    }

    @Test
    void t096IncompleteOrUnknownAffectedRelationBlocksTheProposal() {
        Relation incomplete = relation(305, Map.of("type", "restriction"), Completeness.INCOMPLETE,
            member(wayId(RECEIVER), "from"), member(nodeId(J), "via"));
        PlanningResult missing = plan(narrowT(List.of(incomplete), Map.of(), Map.of("highway", "path")),
            List.of(frozenCandidate(J, 20, 0, SELECTED, RECEIVER)), Map.of(), Set.of(),
            RECEIVER, SELECTED);
        assertFalse(missing.accepted());
        assertTrue(missing.hasFinding(FindingCode.INCOMPLETE_RELATION));

        Relation unknown = relation(306, Map.of("type", "connectivity"), Completeness.COMPLETE,
            member(wayId(RECEIVER), ""));
        PlanningResult unsupported = plan(narrowT(List.of(unknown), Map.of(), Map.of("highway", "path")),
            List.of(frozenCandidate(J, 20, 0, SELECTED, RECEIVER)), Map.of(), Set.of(),
            RECEIVER, SELECTED);
        assertFalse(unsupported.accepted());
        assertTrue(unsupported.hasFinding(FindingCode.UNSUPPORTED_RELATION));
    }

    @Test
    void t097GradeSeparatedCrossingStaysDisconnectedButExistingBridgeEndpointStaysShared() {
        long lowerA = 41;
        long lowerB = 42;
        long lowerWay = 231;
        TopologyNetwork gradeSeparated = network(
            List.of(node(L, -60, 0), node(J, 0, 0), node(M, 10, 0), node(R, 60, 0), node(A, 20, 60),
                node(lowerA, 20, -40), node(lowerB, 20, 40)),
            List.of(way(RECEIVER, Map.of("highway", "primary", "layer", "0"), L, J, M, R),
                way(SELECTED, A, J), way(lowerWay, Map.of("highway", "service", "tunnel", "yes", "layer", "-1"),
                    lowerA, lowerB)));

        TopologyEditPlan crossingEdit = accepted(plan(gradeSeparated,
            List.of(frozenCandidate(J, 20, 0, SELECTED, RECEIVER)), Map.of(), Set.of(),
            RECEIVER, SELECTED));
        assertEquals(gradeSeparated.way(wayId(lowerWay)), crossingEdit.after().way(wayId(lowerWay)));
        assertFalse(crossingEdit.after().way(wayId(lowerWay)).nodeIds().contains(nodeId(J)));

        TopologyNetwork bridgeEndpoint = network(
            List.of(node(L, -60, 0), node(J, 0, 0), node(R, 60, 0), node(A, 20, 60)),
            List.of(way(RECEIVER, Map.of("highway", "primary", "layer", "0"), L, J, R),
                way(SELECTED, Map.of("highway", "path", "bridge", "yes", "layer", "1"), A, J)));
        TopologyEditPlan endpointEdit = accepted(plan(bridgeEndpoint,
            List.of(frozenCandidate(J, 20, 0, SELECTED, RECEIVER)), Map.of(), Set.of(),
            RECEIVER, SELECTED));
        assertTrue(endpointEdit.after().way(wayId(RECEIVER)).nodeIds().contains(nodeId(J)));
        assertTrue(endpointEdit.after().way(wayId(SELECTED)).nodeIds().contains(nodeId(J)));
    }

    @Test
    void t098AreaOrClosedWayThatWouldNeedTopologyChangeIsRejectedWithoutImplicitSplit() {
        long areaWay = 241;
        TopologyNetwork before = network(
            List.of(node(L, -40, 0), node(J, 0, 0), node(R, 40, 0), node(A, 20, 50)),
            List.of(way(areaWay, Map.of("area", "yes", "landuse", "grass"), L, J, R, L),
                way(SELECTED, A, J)));

        PlanningResult result = plan(before,
            List.of(frozenCandidate(J, 20, 0, SELECTED, areaWay)), Map.of(), Set.of(),
            areaWay, SELECTED);

        assertFalse(result.accepted());
        assertTrue(result.hasFinding(FindingCode.AREA_OR_CLOSED_WAY_UNSUPPORTED));
        assertTrue(result.plan().isEmpty());
    }

    @Test
    void t100ViaWayRestrictionRejectsIncidentalSharedNodesWithEntryExitAgainstOneway() {
        long from = 261;
        long via = 262;
        long to = 263;
        Relation restriction = relation(308,
            Map.of("type", "restriction", "restriction", "only_straight_on"), Completeness.COMPLETE,
            member(wayId(from), "from"), member(wayId(via), "via"), member(wayId(to), "to"));
        TopologyNetwork before = network(
            List.of(node(L, -10, 0), node(J, 0, 0), node(K, 20, 0),
                node(T, 30, 0), node(A, 10, 20)),
            List.of(way(from, Map.of("highway", "path", "oneway", "yes"), A, K),
                way(via, Map.of("highway", "path", "oneway", "yes"), L, J, K),
                way(to, Map.of("highway", "path", "oneway", "yes"), J, T)),
            List.of(restriction));

        PlanningResult result = plan(before,
            List.of(frozenCandidate(J, 1, 0, to, via)), Map.of(), Set.of(), via, to);

        assertRelationSemanticsRefusal(result);
    }

    @Test
    void t101ViaWayRestrictionRejectsFinalTraversalReversedAgainstOneway() {
        long from = 264;
        long via = 265;
        long to = 266;
        Relation restriction = relation(309,
            Map.of("type", "restriction", "restriction", "only_straight_on"), Completeness.COMPLETE,
            member(wayId(from), "from"), member(wayId(via), "via"), member(wayId(to), "to"));
        List<TopologyNetwork.Node> nodes = List.of(node(L, -10, 0), node(J, 0, 0),
            node(K, 20, 0), node(T, 30, 0), node(A, 10, 20));
        TopologyNetwork before = network(nodes,
            List.of(way(from, A, J),
                way(via, Map.of("highway", "path", "oneway", "yes"), L, J, K),
                way(to, K, T)), List.of(restriction));
        TopologyNetwork after = network(nodes,
            List.of(way(from, A, J),
                way(via, Map.of("highway", "path", "oneway", "yes"), L, K, J),
                way(to, K, T)), List.of(restriction));

        assertTrue(RelationSafetyValidator.validate(before, after, Set.of(wayId(via)),
            Map.of(), Set.of()).stream()
            .anyMatch(finding -> finding.code() == FindingCode.RELATION_SEMANTICS_INVALID));
    }

    @Test
    void t102ViaNodeRestrictionRejectsFinalOnewayApproachReversal() {
        long from = 267;
        long to = 268;
        Relation restriction = relation(310,
            Map.of("type", "restriction", "restriction", "no_left_turn"), Completeness.COMPLETE,
            member(wayId(from), "from"), member(nodeId(J), "via"), member(wayId(to), "to"));
        List<TopologyNetwork.Node> nodes = List.of(node(J, 0, 0), node(A, 10, 20), node(T, 30, 0));
        TopologyNetwork before = network(nodes,
            List.of(way(from, Map.of("highway", "path", "oneway", "yes"), A, J),
                way(to, Map.of("highway", "path", "oneway", "yes"), J, T)),
            List.of(restriction));
        TopologyNetwork after = network(nodes,
            List.of(way(from, Map.of("highway", "path", "oneway", "yes"), J, A),
                way(to, Map.of("highway", "path", "oneway", "yes"), J, T)),
            List.of(restriction));

        assertTrue(RelationSafetyValidator.validate(before, after, Set.of(wayId(from)),
            Map.of(), Set.of()).stream()
            .anyMatch(finding -> finding.code() == FindingCode.RELATION_SEMANTICS_INVALID));
    }

    @Test
    void t103ViaWayRestrictionAllowsOrderedReverseOnewayTraversal() {
        long from = 269;
        long via = 270;
        long to = 271;
        Relation restriction = relation(311,
            Map.of("type", "restriction", "restriction", "only_straight_on"), Completeness.COMPLETE,
            member(wayId(from), "from"), member(wayId(via), "via"), member(wayId(to), "to"));
        TopologyNetwork network = network(
            List.of(node(L, -10, 0), node(J, 0, 0), node(K, 20, 0),
                node(T, 30, 0), node(A, 10, 20)),
            List.of(way(from, Map.of("highway", "path", "oneway", "-1"), J, A),
                way(via, Map.of("highway", "path", "oneway", "-1"), K, J, L),
                way(to, Map.of("highway", "path", "oneway", "-1"), T, K)),
            List.of(restriction));

        assertEquals(List.of(), RelationSafetyValidator.validate(network, network,
            Set.of(wayId(via)), Map.of(), Set.of()));
    }

    @Test
    void t104ViaNodeRestrictionAllowsOnewayApproachAndDeparture() {
        long from = 272;
        long to = 273;
        Relation restriction = relation(312,
            Map.of("type", "restriction", "restriction", "no_left_turn"), Completeness.COMPLETE,
            member(wayId(from), "from"), member(nodeId(J), "via"), member(wayId(to), "to"));
        TopologyNetwork network = network(
            List.of(node(J, 0, 0), node(A, 10, 20), node(T, 30, 0)),
            List.of(way(from, Map.of("highway", "path", "oneway", "yes"), A, J),
                way(to, Map.of("highway", "path", "oneway", "yes"), J, T)),
            List.of(restriction));

        assertEquals(List.of(), RelationSafetyValidator.validate(network, network,
            Set.of(wayId(from)), Map.of(), Set.of()));
    }

    @Test
    void t105ViaWayRestrictionRejectsRepeatedAmbiguousTransferPort() {
        long from = 274;
        long via = 275;
        long to = 276;
        Relation restriction = relation(313,
            Map.of("type", "restriction", "restriction", "only_straight_on"), Completeness.COMPLETE,
            member(wayId(from), "from"), member(wayId(via), "via"), member(wayId(to), "to"));
        TopologyNetwork network = network(
            List.of(node(L, -10, 0), node(J, 0, 0), node(A, 10, 20),
                node(K, 20, 0), node(T, 30, 0)),
            List.of(way(from, L, J),
                way(via, Map.of("highway", "path", "oneway", "yes"), J, A, J, K),
                way(to, J, T)), List.of(restriction));

        assertTrue(RelationSafetyValidator.validate(network, network, Set.of(wayId(via)),
            Map.of(), Set.of()).stream()
            .anyMatch(finding -> finding.code() == FindingCode.RELATION_SEMANTICS_INVALID));
    }

    @Test
    void t106PlannerRejectsRepeatedViaPortWhenDistinctJunctionMoves() {
        long from = 277;
        long via = 278;
        long to = 279;
        long receiver = 280;
        Relation restriction = relation(314,
            Map.of("type", "restriction", "restriction", "only_straight_on"), Completeness.COMPLETE,
            member(wayId(from), "from"), member(wayId(via), "via"), member(wayId(to), "to"));
        TopologyNetwork before = network(
            List.of(node(L, -10, 0), node(J, 0, 0), node(A, 10, 10),
                node(K, 20, 0), node(T, 0, -10), node(M, 20, 20)),
            List.of(way(from, L, J),
                way(via, Map.of("highway", "path", "oneway", "yes"), J, A, J, K),
                way(to, J, T), way(receiver, K, M)), List.of(restriction));

        PlanningResult result = plan(before,
            List.of(reconstructedCandidate(K, 20, 1, via)), Map.of(), Set.of(), via, receiver);

        assertRelationSemanticsRefusal(result);
    }

    private static void assertRelationSemanticsRefusal(PlanningResult result) {
        assertFalse(result.accepted(), () -> "expected a relation-semantics refusal, got "
            + result.findings());
        assertTrue(result.hasFinding(FindingCode.RELATION_SEMANTICS_INVALID),
            () -> "expected RELATION_SEMANTICS_INVALID, got " + result.findings());
        assertTrue(result.plan().isEmpty(), "an invalid relation cannot produce a plan");
    }

    private static TopologyNetwork narrowT(List<Relation> relations, Map<String, String> junctionTags,
        Map<String, String> selectedTags) {
        return network(
            List.of(node(L, -60, 0), node(J, 0, 0, junctionTags), node(M, 10, 0),
                node(R, 60, 0), node(A, 20, 60)),
            List.of(way(RECEIVER, Map.of("highway", "primary"), L, J, M, R),
                way(SELECTED, selectedTags, A, J)), relations);
    }

    private static boolean sharesNode(TopologyNetwork network, Id firstWay, Id secondWay) {
        return network.way(firstWay).nodeIds().stream()
            .anyMatch(network.way(secondWay).nodeIds()::contains);
    }
}
