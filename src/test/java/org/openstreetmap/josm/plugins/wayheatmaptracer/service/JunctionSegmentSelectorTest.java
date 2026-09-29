package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.WaySegmentRange;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class JunctionSegmentSelectorTest {
    @BeforeAll
    static void setProjection() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void selectsLongestSegmentBetweenJunctionsAndEndpoints() {
        DataSet dataSet = new DataSet();
        Node n0 = node(0.0);
        Node n1 = node(0.001);
        Node n2 = node(0.002);
        Node n3 = node(0.010);
        Node n4 = node(0.011);
        for (Node node : List.of(n0, n1, n2, n3, n4)) {
            dataSet.addPrimitive(node);
        }
        Way way = way(n0, n1, n2, n3, n4);
        Way branchAtN2 = way(n2, node(0.0025));
        Way branchAtN3 = way(n3, node(0.0105));
        dataSet.addPrimitive(way);
        dataSet.addPrimitive(branchAtN2.getNode(1));
        dataSet.addPrimitive(branchAtN2);
        dataSet.addPrimitive(branchAtN3.getNode(1));
        dataSet.addPrimitive(branchAtN3);

        WaySegmentRange range = new JunctionSegmentSelector().longestJunctionBoundedSegment(way);

        assertEquals(new WaySegmentRange(0, 1), range);
    }

    @Test
    void selectsWholeTwoNodeWayWithoutJunctions() {
        DataSet dataSet = new DataSet();
        Way way = way(node(0.0), node(0.001));
        addWayWithNodes(dataSet, way);

        assertEquals(new WaySegmentRange(0, 1),
            new JunctionSegmentSelector().longestJunctionBoundedSegment(way));
    }

    @Test
    void excludesTNodesAtBothSelectedWayEndpoints() {
        DataSet dataSet = new DataSet();
        Node start = node(0.0);
        Node first = node(0.001);
        Node middle = node(0.002);
        Node last = node(0.003);
        Node end = node(0.004);
        Way selected = way(start, first, middle, last, end);
        addWayWithNodes(dataSet, selected);
        addBranch(dataSet, start, -0.001);
        addBranch(dataSet, start, 0.0001);
        addBranch(dataSet, end, 0.0041);
        addBranch(dataSet, end, 0.005);

        assertEquals(new WaySegmentRange(1, 3),
            new JunctionSegmentSelector().longestJunctionBoundedSegment(selected));
    }

    @Test
    void keepsSimpleEndToEndContinuationAsAnEndpoint() {
        DataSet dataSet = new DataSet();
        Node junction = node(0.0);
        Way selected = way(junction, node(0.001), node(0.002));
        Way continuation = way(node(-0.002), node(-0.001), junction);
        addWayWithNodes(dataSet, selected);
        addWayWithNodes(dataSet, continuation);

        assertEquals(new WaySegmentRange(0, 2),
            new JunctionSegmentSelector().longestJunctionBoundedSegment(selected));
    }

    @Test
    void keepsSafeContinuationHintAsReturnedEndpoint() {
        DataSet dataSet = new DataSet();
        Node junction = node(0.0);
        Way selected = way(junction, node(0.001), node(0.002));
        Way continuation = way(node(-0.002), node(-0.001), junction);
        addWayWithNodes(dataSet, selected);
        addWayWithNodes(dataSet, continuation);

        assertEquals(new WaySegmentRange(0, 2), new JunctionSegmentSelector()
            .longestJunctionBoundedSegmentContaining(selected, junction));
    }

    @Test
    void excludesEndpointMeetingTheInteriorOfAnotherWay() {
        DataSet dataSet = new DataSet();
        Node junction = node(0.0);
        Way selected = way(junction, node(0.001), node(0.002));
        Way throughWay = way(node(-0.001), junction, node(0.0005));
        addWayWithNodes(dataSet, selected);
        addWayWithNodes(dataSet, throughWay);

        assertEquals(new WaySegmentRange(1, 2),
            new JunctionSegmentSelector().longestJunctionBoundedSegment(selected));
    }

    @Test
    void excludesInteriorTJunctionOccurrenceFromReturnedSpan() {
        DataSet dataSet = new DataSet();
        Node start = node(0.0);
        Node junction = node(0.001);
        Node after = node(0.002);
        Node end = node(0.003);
        Way selected = way(start, junction, after, end);
        addWayWithNodes(dataSet, selected);
        addBranch(dataSet, junction, 0.0015);

        assertEquals(new WaySegmentRange(2, 3),
            new JunctionSegmentSelector().longestJunctionBoundedSegment(selected));
    }

    @Test
    void excludesSharedInteriorCrossingAndKeepsOnlySafeAdjacentSpan() {
        DataSet dataSet = new DataSet();
        Node before = node(0.0);
        Node crossing = node(0.001);
        Node after = node(0.002);
        Node end = node(0.003);
        Way selected = way(before, crossing, after, end);
        Way crossingWay = way(node(0.0005), crossing, node(0.0015));
        addWayWithNodes(dataSet, selected);
        addWayWithNodes(dataSet, crossingWay);

        assertEquals(new WaySegmentRange(2, 3),
            new JunctionSegmentSelector().longestJunctionBoundedSegment(selected));
    }

    @Test
    void ignoresDeletedReferrerWhenRecognizingSimpleEndToEndContinuation() {
        DataSet dataSet = new DataSet();
        Node junction = node(0.0);
        Way selected = way(junction, node(0.001), node(0.002));
        Way continuation = way(node(-0.002), node(-0.001), junction);
        Way deletedBranch = way(junction, node(0.0005));
        addWayWithNodes(dataSet, selected);
        addWayWithNodes(dataSet, continuation);
        addWayWithNodes(dataSet, deletedBranch);
        deletedBranch.setDeleted(true);

        assertEquals(new WaySegmentRange(0, 2),
            new JunctionSegmentSelector().longestJunctionBoundedSegment(selected));
    }

    @Test
    void incompleteReferrerCannotMakeAnEndpointAContinuation() {
        DataSet dataSet = new DataSet();
        Node junction = node(0.0);
        Way selected = way(junction, node(0.001), node(0.002));
        Way incompleteNeighbor = way(junction, new Node(9003));
        addWayWithNodes(dataSet, selected);
        addWayWithNodes(dataSet, incompleteNeighbor);

        assertEquals(new WaySegmentRange(1, 2),
            new JunctionSegmentSelector().longestJunctionBoundedSegment(selected));
    }

    @Test
    void refusesRangeWhenTrimmingJunctionsLeavesOnlyOneOccurrence() {
        DataSet dataSet = new DataSet();
        Node start = node(0.0);
        Node junction1 = node(0.001);
        Node junction2 = node(0.002);
        Node end = node(0.003);
        Way selected = way(start, junction1, junction2, end);
        addWayWithNodes(dataSet, selected);
        addBranch(dataSet, junction1, 0.0015);
        addBranch(dataSet, junction2, 0.0025);

        assertThrows(IllegalStateException.class,
            () -> new JunctionSegmentSelector().longestJunctionBoundedSegment(selected));
    }

    @Test
    void ranksRangesByLengthAfterBranchEndpointsAreTrimmed() {
        DataSet dataSet = new DataSet();
        Node n0 = node(0.0);
        Node n1 = node(0.001);
        Node n2 = node(0.010);
        Node n3 = node(0.0101);
        Node n4 = node(0.015);
        Node n5 = node(0.020);
        Node n6 = node(0.021);
        Node n7 = node(0.0211);
        Node n8 = node(0.0212);
        Way selected = way(n0, n1, n2, n3, n4, n5, n6, n7, n8);
        addWayWithNodes(dataSet, selected);
        addBranch(dataSet, n2, 0.0105);
        addBranch(dataSet, n6, 0.0215);

        assertEquals(new WaySegmentRange(3, 5),
            new JunctionSegmentSelector().longestJunctionBoundedSegment(selected));
    }

    @Test
    void globalTieKeepsEarlierSegmentInWayOrder() {
        DataSet dataSet = new DataSet();
        Way way = way(node(0.0), node(0.125), node(0.25), node(0.375),
            node(0.5), node(0.625), node(0.75), node(0.875));
        addWayWithNodes(dataSet, way);
        addBranch(dataSet, way.getNode(2), 0.2505);
        addBranch(dataSet, way.getNode(5), 0.6255);

        assertEquals(new WaySegmentRange(0, 1),
            new JunctionSegmentSelector().longestJunctionBoundedSegment(way));
    }

    @Test
    void relationMembershipAndGeometricCrossingDoNotCreateWayJunctions() {
        DataSet dataSet = new DataSet();
        Node start = node(0.0);
        Node middle = node(0.001);
        Node end = node(0.002);
        Way selected = way(start, middle, end);
        Way crossing = way(new Node(new LatLon(-0.001, 0.001)), new Node(new LatLon(0.001, 0.001)));
        addWayWithNodes(dataSet, selected);
        addWayWithNodes(dataSet, crossing);
        Relation relation = new Relation();
        relation.addMember(new RelationMember("point", middle));
        dataSet.addPrimitive(relation);

        assertEquals(new WaySegmentRange(0, 2),
            new JunctionSegmentSelector().longestJunctionBoundedSegment(selected));
    }

    @Test
    void selectsContainingSegmentForInteriorHintInsteadOfGlobalLongest() {
        DataSet dataSet = new DataSet();
        Way way = way(node(0.0), node(0.001), node(0.002), node(0.003),
            node(0.004), node(0.005), node(0.006), node(0.007));
        addWayWithNodes(dataSet, way);
        addBranch(dataSet, way.getNode(3), 0.0035);

        JunctionSegmentSelector selector = new JunctionSegmentSelector();
        WaySegmentRange range = selector.longestJunctionBoundedSegmentContaining(way, way.getNode(1));

        assertEquals(new WaySegmentRange(0, 2), range);
        assertEquals(new WaySegmentRange(4, 7), selector.longestJunctionBoundedSegment(way));
    }

    @Test
    void selectedJunctionChoosesLongerAdjacentSegmentAndEarlierOnTie() {
        Fixture unequal = fixtureWithCentralJunction(0.0, 0.001, 0.002, 0.003, 0.004,
            0.010, 0.011, 0.012);
        Fixture tied = fixtureWithCentralJunction(0.0, 0.125, 0.25, 0.375, 0.5,
            0.625, 0.75, 1.0);
        JunctionSegmentSelector selector = new JunctionSegmentSelector();

        assertEquals(new WaySegmentRange(0, 3), selector.longestJunctionBoundedSegmentContaining(
            unequal.way(), unequal.way().getNode(4)));
        assertEquals(new WaySegmentRange(0, 3), selector.longestJunctionBoundedSegmentContaining(
            tied.way(), tied.way().getNode(4)));
    }

    @Test
    void endpointHintsChooseTheirAdjacentSegments() {
        Fixture fixture = fixtureWithCentralJunction(0.0, 0.001, 0.002, 0.003, 0.004,
            0.010, 0.011, 0.012);
        JunctionSegmentSelector selector = new JunctionSegmentSelector();

        assertEquals(new WaySegmentRange(0, 3), selector.longestJunctionBoundedSegmentContaining(
            fixture.way(), fixture.way().firstNode()));
        assertEquals(new WaySegmentRange(5, 7), selector.longestJunctionBoundedSegmentContaining(
            fixture.way(), fixture.way().lastNode()));
    }

    @Test
    void rejectsHintsOutsideWayOrWithRepeatedOccurrence() {
        Node repeated = node(0.001);
        Way way = way(node(0.0), repeated, node(0.002), repeated);
        JunctionSegmentSelector selector = new JunctionSegmentSelector();

        assertThrows(IllegalArgumentException.class,
            () -> selector.longestJunctionBoundedSegmentContaining(way, node(0.001)));
        assertThrows(IllegalArgumentException.class,
            () -> selector.longestJunctionBoundedSegmentContaining(way, repeated));
    }

    @Test
    void skipsLongestStructuralRangeWhenRepeatedNodeMakesItUnsafe() {
        DataSet dataSet = new DataSet();
        Node repeated = node(0.010);
        Node junction1 = node(0.030);
        Node junction2 = node(0.032);
        Way way = way(node(0.0), repeated, node(0.020), repeated, junction1,
            node(0.0305), node(0.031), junction2, node(0.0325));
        addWayWithNodes(dataSet, way);
        addBranch(dataSet, junction1, 0.0305);
        addBranch(dataSet, junction2, 0.0322);

        WaySegmentRange range = new JunctionSegmentSelector().longestJunctionBoundedSegment(way);

        assertEquals(new WaySegmentRange(5, 6), range);
    }

    @Test
    void junctionHintCanChooseSafeSideWhenOtherSideContainsRepeatedNode() {
        DataSet dataSet = new DataSet();
        Node repeated = node(0.001);
        Node junction = node(0.010);
        Way way = way(node(0.0), repeated, node(0.002), repeated, junction, node(0.011), node(0.012));
        addWayWithNodes(dataSet, way);
        addBranch(dataSet, junction, 0.0105);

        WaySegmentRange range = new JunctionSegmentSelector()
            .longestJunctionBoundedSegmentContaining(way, junction);

        assertEquals(new WaySegmentRange(5, 6), range);
    }

    @Test
    void failsWhenNoEligibleMaximalSegmentExists() {
        DataSet dataSet = new DataSet();
        Node repeated = node(0.0);
        Way closed = way(repeated, node(0.001), repeated);
        addWayWithNodes(dataSet, closed);

        assertThrows(IllegalStateException.class,
            () -> new JunctionSegmentSelector().longestJunctionBoundedSegment(closed));
    }

    @Test
    void failsWhenNoEligibleAdjacentSegmentContainsJunctionHint() {
        DataSet dataSet = new DataSet();
        Node leftRepeated = node(0.001);
        Node junction = node(0.010);
        Node rightRepeated = node(0.011);
        Way way = way(leftRepeated, node(0.0), leftRepeated, junction,
            rightRepeated, node(0.012), rightRepeated);
        addWayWithNodes(dataSet, way);
        addBranch(dataSet, junction, 0.0105);

        assertThrows(IllegalStateException.class,
            () -> new JunctionSegmentSelector().longestJunctionBoundedSegmentContaining(way, junction));
    }

    @Test
    void rejectsMalformedWayWithFewerThanTwoOccurrences() {
        Way malformed = way(node(0.0));

        assertThrows(IllegalArgumentException.class,
            () -> new JunctionSegmentSelector().longestJunctionBoundedSegment(malformed));
    }

    @Test
    void closedWayCanSelectIndependentSafeInteriorRange() {
        DataSet dataSet = new DataSet();
        Node repeated = node(0.0);
        Node junction1 = node(0.002);
        Node junction2 = node(0.004);
        Way closed = way(repeated, node(0.001), junction1, node(0.0025), node(0.003),
            junction2, node(0.005), repeated);
        addWayWithNodes(dataSet, closed);
        addBranch(dataSet, junction1, 0.0025);
        addBranch(dataSet, junction2, 0.0045);

        assertEquals(new WaySegmentRange(3, 4),
            new JunctionSegmentSelector().longestJunctionBoundedSegment(closed));
    }

    private static Fixture fixtureWithJunctions(double... longitudes) {
        DataSet dataSet = new DataSet();
        Node[] nodes = java.util.Arrays.stream(longitudes).mapToObj(JunctionSegmentSelectorTest::node)
            .toArray(Node[]::new);
        Way way = way(nodes);
        addWayWithNodes(dataSet, way);
        addBranch(dataSet, nodes[2], longitudes[2] + 0.0002);
        addBranch(dataSet, nodes[3], longitudes[3] + 0.0002);
        return new Fixture(dataSet, way);
    }

    private static Fixture fixtureWithCentralJunction(double... longitudes) {
        DataSet dataSet = new DataSet();
        Node[] nodes = java.util.Arrays.stream(longitudes).mapToObj(JunctionSegmentSelectorTest::node)
            .toArray(Node[]::new);
        Way way = way(nodes);
        addWayWithNodes(dataSet, way);
        addBranch(dataSet, nodes[4], longitudes[4] + 0.0002);
        return new Fixture(dataSet, way);
    }

    private static void addWayWithNodes(DataSet dataSet, Way way) {
        for (Node node : way.getNodes()) {
            if (node.getDataSet() == null) {
                dataSet.addPrimitive(node);
            }
        }
        dataSet.addPrimitive(way);
    }

    private static void addBranch(DataSet dataSet, Node junction, double endLongitude) {
        Node end = node(endLongitude);
        dataSet.addPrimitive(end);
        dataSet.addPrimitive(way(junction, end));
    }

    private static Way way(Node... nodes) {
        Way way = new Way();
        way.setNodes(List.of(nodes));
        return way;
    }

    private static Node node(double lon) {
        return new Node(new LatLon(0.0, lon));
    }

    private record Fixture(DataSet dataSet, Way way) {
    }
}
