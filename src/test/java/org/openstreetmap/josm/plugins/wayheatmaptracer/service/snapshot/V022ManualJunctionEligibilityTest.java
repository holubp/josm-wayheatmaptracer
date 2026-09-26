package org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class V022ManualJunctionEligibilityTest {
    private static final LocalMetricFrame FRAME = LocalMetricFrame.certifiedEquirectangular(
            new GeographicPoint(0, 0), new GeographicPoint(-0.01, -0.01),
            new GeographicPoint(0.01, 0.01));

    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void isolatedCompleteSimpleTIsEligible() {
        Fixture fixture = fixture();
        assertEquals(ManualJunctionEligibility.Reason.SIMPLE_T,
                fixture.decision().reason());
    }

    @Test
    void canonicalExternalPortAtThirtyOneMetresCompletesOrdinaryReceiverArm() {
        Fixture fixture = fixture();
        Node outer = node(45, 0, -0.00063);
        Node port = node(46, 0, -0.000279);
        Node inside = node(47, 0, -0.00018);
        fixture.data.addPrimitive(outer);
        fixture.data.addPrimitive(port);
        fixture.data.addPrimitive(inside);
        fixture.receiver.setNodes(List.of(outer, port, inside, fixture.junction,
                fixture.receiver.getNode(2)));

        assertEquals(ManualJunctionEligibility.Reason.SIMPLE_T,
                fixture.decision().reason());
    }

    @Test
    void longReceiverBeyondMetricCertificateDoesNotInvalidateProvedPort() {
        Fixture fixture = fixture();
        Node outer = node(58, 0, -0.02);
        Node port = node(59, 0, -0.000279);
        Node inside = node(60, 0, -0.00018);
        for (Node node : List.of(outer, port, inside)) fixture.data.addPrimitive(node);
        fixture.receiver.setNodes(List.of(outer, port, inside, fixture.junction,
                fixture.receiver.getNode(2)));
        LocalMetricFrame narrow = LocalMetricFrame.certifiedEquirectangular(
                new GeographicPoint(0, 0), new GeographicPoint(-0.0005, -0.0005),
                new GeographicPoint(0.0005, 0.0005));

        assertEquals(ManualJunctionEligibility.Reason.SIMPLE_T,
                fixture.decision(new OccurrenceRange(0, 1), narrow).reason());
    }

    @Test
    void secondReceiverJunctionJustBeyondProvedPortIsCoupled() {
        Fixture fixture = fixture();
        Node outer = node(48, 0, -0.00063);
        Node port = node(49, 0, -0.000279);
        Node secondJunction = node(50, 0, -0.00036);
        Node branch = node(51, -0.0002, -0.00036);
        for (Node node : List.of(outer, port, secondJunction, branch)) fixture.data.addPrimitive(node);
        fixture.receiver.setNodes(List.of(outer, secondJunction, port, fixture.junction,
                fixture.receiver.getNode(2)));
        fixture.data.addPrimitive(way(52, List.of(secondJunction, branch)));

        assertEquals(ManualJunctionEligibility.Reason.COUPLED_JUNCTION,
                fixture.decision().reason());
    }

    @Test
    void secondReceiverJunctionAtSixtyMetreGeodesicBoundaryIsCoupled() {
        Fixture fixture = fixture();
        Node inside = node(61, 0.00018, 0);
        Node port = node(62, 0.000279, 0);
        Node secondJunction = node(63, 0.0005417, 0);
        Node outer = node(64, 0.0007, 0);
        Node branch = node(65, 0.0005417, 0.0002);
        for (Node node : List.of(inside, port, secondJunction, outer, branch)) {
            fixture.data.addPrimitive(node);
        }
        fixture.receiver.setNodes(List.of(fixture.receiver.getNode(0), fixture.junction,
                inside, port, secondJunction, outer));
        fixture.data.addPrimitive(way(66, List.of(secondJunction, branch)));

        assertEquals(ManualJunctionEligibility.Reason.COUPLED_JUNCTION,
                fixture.decision().reason());
    }

    @Test
    void secondSelectedJunctionOutsideSelectedRangeIsCoupled() {
        Fixture fixture = fixture();
        Node secondJunction = node(53, -0.000135, 0);
        Node port = node(54, 0.000279, 0);
        Node outer = node(55, 0.00063, 0);
        Node branch = node(56, -0.000135, 0.0002);
        for (Node node : List.of(secondJunction, port, outer, branch)) fixture.data.addPrimitive(node);
        fixture.selected.setNodes(List.of(secondJunction, fixture.junction, port, outer));
        fixture.data.addPrimitive(way(57, List.of(secondJunction, branch)));

        assertEquals(ManualJunctionEligibility.Reason.COUPLED_JUNCTION,
                fixture.decision(new OccurrenceRange(1, 2)).reason());
    }

    @Test
    void participatingWayRelationIsManualOnly() {
        Fixture fixture = fixture();
        Relation relation = new Relation();
        relation.setMembers(List.of(new RelationMember("route", fixture.receiver)));
        relation.setOsmId(40, 1);
        relation.setModified(false);
        fixture.data.addPrimitive(relation);
        assertEquals(ManualJunctionEligibility.Reason.PARTICIPATING_RELATION,
                fixture.decision().reason());
    }

    @Test
    void taggedJunctionAndReceiverArmAreManualOnly() {
        Fixture atJunction = fixture();
        atJunction.junction.put("barrier", "gate");
        assertEquals(ManualJunctionEligibility.Reason.AFFECTED_NODE_TAGGED,
                atJunction.decision().reason());

        Fixture onArm = fixture();
        onArm.receiver.getNode(2).put("name", "protected");
        assertEquals(ManualJunctionEligibility.Reason.AFFECTED_NODE_TAGGED,
                onArm.decision().reason());

        Fixture selectedArm = fixture();
        selectedArm.selected.getNode(0).put("name", "protected");
        assertEquals(ManualJunctionEligibility.Reason.AFFECTED_NODE_TAGGED,
                selectedArm.decision().reason());
    }

    @Test
    void thirdIncidentWayIsManualOnly() {
        Fixture fixture = fixture();
        Node third = node(8, -0.0004, 0);
        fixture.data.addPrimitive(third);
        fixture.data.addPrimitive(way(13, List.of(fixture.junction, third)));
        assertEquals(ManualJunctionEligibility.Reason.MULTIPLE_RECEIVERS,
                fixture.decision().reason());
    }

    @Test
    void splitReceiverAndSelectedInteriorAreManualOnly() {
        Fixture split = fixture();
        Node east = split.receiver.getNode(2);
        split.receiver.setNodes(List.of(split.receiver.getNode(0), split.junction));
        split.data.addPrimitive(way(12, List.of(split.junction, east)));
        assertEquals(ManualJunctionEligibility.Reason.MULTIPLE_RECEIVERS,
                split.decision().reason());

        Fixture interior = fixture();
        Node north = node(7, 0.0002, 0);
        interior.data.addPrimitive(north);
        interior.selected.setNodes(List.of(interior.selected.getNode(0), interior.junction, north));
        assertEquals(ManualJunctionEligibility.Reason.SELECTED_INTERIOR,
                interior.decision().reason());
    }

    @Test
    void overlappingJunctionsAndMissingArmPortAreManualOnly() {
        Fixture coupled = fixture();
        Node other = node(7, -0.0002, 0);
        Node branch = node(8, -0.0002, 0.0002);
        coupled.data.addPrimitive(other);
        coupled.data.addPrimitive(branch);
        coupled.selected.setNodes(List.of(coupled.selected.getNode(0), other, coupled.junction));
        coupled.data.addPrimitive(way(12, List.of(other, branch)));
        assertEquals(ManualJunctionEligibility.Reason.MULTIPLE_JUNCTIONS,
                coupled.decision().reason());

        Fixture missing = fixture();
        missing.receiver.getNode(0).setCoor(new LatLon(0, -0.0007));
        assertEquals(ManualJunctionEligibility.Reason.INCOMPLETE_ARM,
                missing.decision().reason());
    }

    @Test
    void relationReferencedArmNodeIsManualOnly() {
        Fixture fixture = fixture();
        Relation relation = new Relation();
        relation.setMembers(List.of(new RelationMember("via", fixture.selected.getNode(0))));
        relation.setOsmId(41, 1);
        relation.setModified(false);
        fixture.data.addPrimitive(relation);
        assertEquals(ManualJunctionEligibility.Reason.AFFECTED_NODE_RELATION,
                fixture.decision().reason());
    }

    @Test
    void unconnectedCrossingInsideAffectedArmIsManualOnly() {
        Fixture fixture = fixture();
        Node lower = node(14, -0.0002, -0.0002);
        Node upper = node(15, -0.0002, 0.0002);
        fixture.data.addPrimitive(lower);
        fixture.data.addPrimitive(upper);
        fixture.data.addPrimitive(way(16, List.of(lower, upper)));
        assertEquals(ManualJunctionEligibility.Reason.AMBIGUOUS_CROSSING,
                fixture.decision().reason());
    }

    @Test
    void remoteContinuationOfUnrelatedMaterializedWayDoesNotEscapeFrame() {
        Fixture fixture = fixture();
        Node localOne = node(67, 0.00018, 0.00009);
        Node localTwo = node(68, 0.00018, 0.00018);
        Node remote = node(69, 0.00018, 0.02);
        for (Node node : List.of(localOne, localTwo, remote)) fixture.data.addPrimitive(node);
        fixture.data.addPrimitive(way(70, List.of(localOne, localTwo, remote)));

        assertEquals(ManualJunctionEligibility.Reason.SIMPLE_T,
                fixture.decision().reason());
    }

    @Test
    void provedThirtyMetrePortSeparatesTaggedContinuationButFindsSharedJunction() {
        Fixture fixture = fixture();
        Node outer = node(17, 0, -0.00045);
        Node tagged = node(18, 0, -0.00036);
        Node ordinary = node(19, 0, -0.00028);
        tagged.put("barrier", "gate");
        fixture.data.addPrimitive(outer);
        fixture.data.addPrimitive(tagged);
        fixture.data.addPrimitive(ordinary);
        fixture.receiver.setNodes(List.of(outer, tagged, ordinary, fixture.junction,
                fixture.receiver.getNode(2)));
        // The canonical external port at ordinary is the exact footprint boundary;
        // a tag beyond it does not grant or block motion inside the footprint.
        assertEquals(ManualJunctionEligibility.Reason.SIMPLE_T,
                fixture.decision().reason());

        Fixture shared = fixture();
        Node sharedOuter = node(20, 0, -0.00045);
        Node sharedNode = node(21, 0, -0.00036);
        Node sharedOrdinary = node(22, 0, -0.00028);
        Node branch = node(23, 0.0002, -0.00036);
        for (Node node : List.of(sharedOuter, sharedNode, sharedOrdinary, branch)) {
            shared.data.addPrimitive(node);
        }
        shared.receiver.setNodes(List.of(sharedOuter, sharedNode, sharedOrdinary,
                shared.junction, shared.receiver.getNode(2)));
        shared.data.addPrimitive(way(24, List.of(sharedNode, branch)));
        assertEquals(ManualJunctionEligibility.Reason.COUPLED_JUNCTION,
                shared.decision().reason());
    }

    private static Fixture fixture() {
        DataSet data = new DataSet();
        Node south = node(1, -0.0004, 0);
        Node junction = node(2, 0, 0);
        Node west = node(3, 0, -0.0004);
        Node east = node(4, 0, 0.0004);
        for (Node node : List.of(south, junction, west, east)) {
            data.addPrimitive(node);
        }
        Way selected = way(10, List.of(south, junction));
        Way receiver = way(11, List.of(west, junction, east));
        data.addPrimitive(selected);
        data.addPrimitive(receiver);
        return new Fixture(data, selected, receiver, junction);
    }

    private record Fixture(DataSet data, Way selected, Way receiver, Node junction) {
        ManualJunctionEligibility.Decision decision() {
            return decision(new OccurrenceRange(0, selected.getNodesCount() - 1));
        }

        ManualJunctionEligibility.Decision decision(OccurrenceRange selectedRange) {
            return decision(selectedRange, FRAME);
        }

        ManualJunctionEligibility.Decision decision(OccurrenceRange selectedRange,
                LocalMetricFrame frame) {
            boolean movableJunction = junction.getNumKeys() == 0
                    && junction.getReferrers().stream().filter(Way.class::isInstance).count() == 2;
            Map<PrimitiveKey, List<OccurrenceRange>> occurrences = new LinkedHashMap<>();
            occurrences.put(key(selected), List.of(selectedRange));
            if (movableJunction) {
                occurrences.put(key(receiver), List.of(
                        JunctionAuthorityBounds.localOccurrenceRange(receiver, junction, frame)));
            }
            var specification = new NetworkSnapshotCapture.Specification("manual-policy", "dataset", 1,
                    key(selected), selectedRange, frame,
                    frame == FRAME ? MetricRegion.rectangle(-100, -100, 100, 100)
                            : MetricRegion.rectangle(-45, -45, 45, 45),
                    frame == FRAME ? MetricRegion.rectangle(-100, -100, 100, 100)
                            : MetricRegion.rectangle(-45, -45, 45, 45), occurrences,
                    movableJunction ? Set.of(key(selected), key(receiver), key(junction))
                            : Set.of(key(selected)),
                    movableJunction ? Set.of(key(junction)) : Set.of(),
                    Set.of(), selectedRange.firstIndex() > 0
                            ? Set.of(key(selected.getNode(selectedRange.lastIndex())))
                            : movableJunction ? Set.of() : Set.of(key(junction)), true,
                    new RecoveryPermissions(false, 7.01, 7.01, JunctionPolicy.REATTACH, true));
            AtomicReference<NetworkSnapshot> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            try {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        result.set(NetworkSnapshotCapture.capture(data, specification));
                    } catch (Throwable error) {
                        failure.set(error);
                    }
                });
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            } catch (InvocationTargetException error) {
                throw new IllegalStateException(error);
            }
            if (failure.get() != null) {
                throw new IllegalStateException(failure.get());
            }
            return ManualJunctionEligibility.evaluate(result.get(), specification);
        }
    }

    private static Node node(long id, double latitude, double longitude) {
        Node node = new Node(new LatLon(latitude, longitude));
        node.setOsmId(id, 1);
        node.setModified(false);
        return node;
    }

    private static Way way(long id, List<Node> nodes) {
        Way way = new Way();
        way.setNodes(nodes);
        way.setOsmId(id, 1);
        way.setModified(false);
        return way;
    }

    private static PrimitiveKey key(org.openstreetmap.josm.data.osm.OsmPrimitive value) {
        PrimitiveKey.Type type = value instanceof Node ? PrimitiveKey.Type.NODE
                : value instanceof Way ? PrimitiveKey.Type.WAY : PrimitiveKey.Type.RELATION;
        return PrimitiveKey.existing(type, value.getUniqueId());
    }
}
