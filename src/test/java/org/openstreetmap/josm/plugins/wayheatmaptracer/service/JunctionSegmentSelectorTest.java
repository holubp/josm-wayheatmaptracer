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

        assertEquals(new WaySegmentRange(2, 3), range);
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
    void globalTieKeepsEarlierSegmentInWayOrder() {
        Fixture fixture = fixtureWithJunctions(0.0, 0.001, 0.002, 0.004, 0.005);

        assertEquals(new WaySegmentRange(0, 2),
            new JunctionSegmentSelector().longestJunctionBoundedSegment(fixture.way()));
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
        Fixture fixture = fixtureWithJunctions(0.0, 0.004, 0.006, 0.020, 0.021);

        WaySegmentRange range = new JunctionSegmentSelector()
            .longestJunctionBoundedSegmentContaining(fixture.way(), fixture.way().getNode(1));

        assertEquals(new WaySegmentRange(0, 2), range);
    }

    @Test
    void selectedJunctionChoosesLongerAdjacentSegmentAndEarlierOnTie() {
        Fixture unequal = fixtureWithJunctions(0.0, 0.001, 0.002, 0.010, 0.011);
        Fixture tied = fixtureWithJunctions(0.0, 0.001, 0.002, 0.004, 0.005);
        JunctionSegmentSelector selector = new JunctionSegmentSelector();

        assertEquals(new WaySegmentRange(2, 3), selector.longestJunctionBoundedSegmentContaining(
            unequal.way(), unequal.way().getNode(2)));
        assertEquals(new WaySegmentRange(0, 2), selector.longestJunctionBoundedSegmentContaining(
            tied.way(), tied.way().getNode(2)));
    }

    @Test
    void endpointHintsChooseTheirAdjacentSegments() {
        Fixture fixture = fixtureWithJunctions(0.0, 0.001, 0.002, 0.010, 0.011);
        JunctionSegmentSelector selector = new JunctionSegmentSelector();

        assertEquals(new WaySegmentRange(0, 2), selector.longestJunctionBoundedSegmentContaining(
            fixture.way(), fixture.way().firstNode()));
        assertEquals(new WaySegmentRange(3, 4), selector.longestJunctionBoundedSegmentContaining(
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
            node(0.031), junction2, node(0.0325));
        addWayWithNodes(dataSet, way);
        addBranch(dataSet, junction1, 0.0305);
        addBranch(dataSet, junction2, 0.0322);

        WaySegmentRange range = new JunctionSegmentSelector().longestJunctionBoundedSegment(way);

        assertEquals(new WaySegmentRange(4, 6), range);
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

        assertEquals(new WaySegmentRange(4, 6), range);
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
        Way closed = way(repeated, node(0.001), junction1, node(0.003), junction2, node(0.005), repeated);
        addWayWithNodes(dataSet, closed);
        addBranch(dataSet, junction1, 0.0025);
        addBranch(dataSet, junction2, 0.0045);

        assertEquals(new WaySegmentRange(2, 4),
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
