package org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class V022CollisionEnvelopeQueryTest {
    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
    }

    @Test
    void crossingSegmentWithBothEndpointsOutsideEnvelopeIsDiscovered() {
        LocalMetricFrame frame = frameAtZero();
        DataSet dataSet = new DataSet();
        Way way = addMetricWay(dataSet, frame,
            new MetricPoint(0.0, -150.0), new MetricPoint(0.0, 150.0));

        CollisionEnvelopeQuery.Result result = CollisionEnvelopeQuery.execute(
            dataSet, frame, MetricRegion.rectangle(-50.0, -50.0, 50.0, 50.0));

        assertEquals(List.of(key(way)), result.intersectingWayKeys().stream().toList());
        assertEquals(3, result.examinedPrimitives());
        assertEquals(1, result.examinedSegments());
        assertEquals(CollisionEnvelopeQuery.DEFINITION, result.definition());
    }

    @Test
    void remoteOutOfFrameWayAndUnconnectedLocalNodeAreIgnored() {
        LocalMetricFrame frame = frameAtZero();
        DataSet dataSet = new DataSet();
        dataSet.addPrimitive(new Node(new LatLon(0.0, 0.0)));
        addGeographicWay(dataSet, new GeographicPoint(40.0, 40.0),
            new GeographicPoint(40.01, 40.01));

        CollisionEnvelopeQuery.Result result = CollisionEnvelopeQuery.execute(
            dataSet, frame, MetricRegion.rectangle(-50.0, -50.0, 50.0, 50.0));

        assertTrue(result.intersectingWayKeys().isEmpty());
        assertEquals(4, result.examinedPrimitives());
        assertEquals(1, result.examinedSegments());
    }

    @Test
    void incompleteWayWithoutAnySegmentsDoesNotBlockCollisionCapture() {
        LocalMetricFrame frame = frameAtZero();
        DataSet dataSet = new DataSet();
        Way placeholder = new Way(1548442848L);
        Node loneNode = new Node(new LatLon(0.0, 0.0));
        Way oneNodePlaceholder = new Way(1548442849L);
        oneNodePlaceholder.setNodes(List.of(loneNode));
        dataSet.addPrimitive(placeholder);
        dataSet.addPrimitive(loneNode);
        dataSet.addPrimitive(oneNodePlaceholder);

        CollisionEnvelopeQuery.Result result = CollisionEnvelopeQuery.execute(
            dataSet, frame, MetricRegion.rectangle(-50.0, -50.0, 50.0, 50.0));

        assertTrue(result.intersectingWayKeys().isEmpty());
        assertEquals(0, result.examinedSegments());
    }

    @Test
    void antimeridianSegmentIsClippedBeforeCertifiedMetricConversion() {
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(
            new GeographicPoint(10.0, 179.95), new GeographicPoint(9.99, 179.8),
            new GeographicPoint(10.01, -179.8));
        DataSet dataSet = new DataSet();
        Way way = addGeographicWay(dataSet, new GeographicPoint(9.98, 179.95),
            new GeographicPoint(10.02, -179.95));

        CollisionEnvelopeQuery.Result result = CollisionEnvelopeQuery.execute(
            dataSet, frame, MetricRegion.rectangle(-10_000.0, -500.0, 10_000.0, 500.0));

        assertTrue(result.intersectingWayKeys().contains(key(way)));
    }

    @Test
    void incompleteWayWithKnownPointInsideEnvelopeFailsClosed() {
        LocalMetricFrame frame = frameAtZero();
        DataSet dataSet = new DataSet();
        Node known = new Node(new LatLon(0.0, 0.0));
        Node incomplete = new Node(9001L);
        Way way = new Way();
        way.setNodes(List.of(known, incomplete));
        dataSet.addPrimitive(known);
        dataSet.addPrimitive(incomplete);
        dataSet.addPrimitive(way);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> CollisionEnvelopeQuery.execute(dataSet, frame,
                MetricRegion.rectangle(-50.0, -50.0, 50.0, 50.0)));

        assertTrue(failure.getMessage().contains("incomplete geometry"));
        assertTrue(failure.getMessage().contains("unknown-node-9001"));
    }

    @Test
    void unknownInteriorNodeFailsClosedWhenKnownEndpointsAreOutside() {
        LocalMetricFrame frame = frameAtZero();
        DataSet dataSet = new DataSet();
        Node left = new Node(new LatLon(frame.toGeographic(new MetricPoint(-100.0, 0.0))
            .latitudeDegrees(), frame.toGeographic(new MetricPoint(-100.0, 0.0)).longitudeDegrees()));
        Node unknown = new Node(9001L);
        Node right = new Node(new LatLon(frame.toGeographic(new MetricPoint(100.0, 0.0))
            .latitudeDegrees(), frame.toGeographic(new MetricPoint(100.0, 0.0)).longitudeDegrees()));
        Way way = new Way();
        way.setNodes(List.of(left, unknown, right));
        dataSet.addPrimitive(left);
        dataSet.addPrimitive(unknown);
        dataSet.addPrimitive(right);
        dataSet.addPrimitive(way);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> CollisionEnvelopeQuery.execute(dataSet, frame,
                MetricRegion.rectangle(-50.0, -50.0, 50.0, 50.0)));

        assertTrue(failure.getMessage().contains("incomplete geometry"));
        assertTrue(failure.getMessage().contains("indeterminate"));
    }

    @Test
    void entirelyUnknownWayFailsClosedWithoutRemoteBound() {
        LocalMetricFrame frame = frameAtZero();
        DataSet dataSet = new DataSet();
        Node first = new Node(9001L);
        Node second = new Node(9002L);
        Way way = new Way();
        way.setNodes(List.of(first, second));
        dataSet.addPrimitive(first);
        dataSet.addPrimitive(second);
        dataSet.addPrimitive(way);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> CollisionEnvelopeQuery.execute(dataSet, frame,
                MetricRegion.rectangle(-50.0, -50.0, 50.0, 50.0)));

        assertTrue(failure.getMessage().contains("incomplete geometry"));
        assertTrue(failure.getMessage().contains("indeterminate"));
    }

    @Test
    void antipodalLongitudeArcInForwardOrderFailsClosed() {
        LocalMetricFrame frame = frameAtZero();
        DataSet dataSet = new DataSet();
        addGeographicWay(dataSet, new GeographicPoint(0.0, -90.0),
            new GeographicPoint(0.0, 90.0));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> CollisionEnvelopeQuery.execute(dataSet, frame,
                MetricRegion.rectangle(-50.0, -50.0, 50.0, 50.0)));

        assertTrue(failure.getMessage().contains("antipodal"));
    }

    @Test
    void antipodalLongitudeArcInReverseOrderFailsClosed() {
        LocalMetricFrame frame = frameAtZero();
        DataSet dataSet = new DataSet();
        addGeographicWay(dataSet, new GeographicPoint(0.0, 90.0),
            new GeographicPoint(0.0, -90.0));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> CollisionEnvelopeQuery.execute(dataSet, frame,
                MetricRegion.rectangle(-50.0, -50.0, 50.0, 50.0)));

        assertTrue(failure.getMessage().contains("antipodal"));
    }

    @Test
    void primitiveAndSegmentBudgetsFailInsteadOfOmittingWork() {
        LocalMetricFrame frame = frameAtZero();
        DataSet dataSet = new DataSet();
        addMetricWay(dataSet, frame, new MetricPoint(-20.0, 0.0),
            new MetricPoint(0.0, 0.0), new MetricPoint(20.0, 0.0));
        MetricRegion envelope = MetricRegion.rectangle(-50.0, -50.0, 50.0, 50.0);

        IllegalStateException primitiveFailure = assertThrows(IllegalStateException.class,
            () -> CollisionEnvelopeQuery.execute(dataSet, frame, envelope,
                new CollisionEnvelopeQuery.Definition("primitive-budget-test", 1, 10)));
        IllegalStateException segmentFailure = assertThrows(IllegalStateException.class,
            () -> CollisionEnvelopeQuery.execute(dataSet, frame, envelope,
                new CollisionEnvelopeQuery.Definition("segment-budget-test", 10, 1)));

        assertTrue(primitiveFailure.getMessage().contains("primitive budget exceeded"));
        assertTrue(segmentFailure.getMessage().contains("segment budget exceeded"));
    }

    @Test
    void insufficientSegmentBudgetRejectsOneOversizedWayFromItsNodeCount() {
        LocalMetricFrame frame = frameAtZero();
        DataSet dataSet = new DataSet();
        addMetricWay(dataSet, frame, new MetricPoint(-20.0, 0.0),
            new MetricPoint(0.0, 0.0), new MetricPoint(20.0, 0.0));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> CollisionEnvelopeQuery.execute(dataSet, frame,
                MetricRegion.rectangle(-50.0, -50.0, 50.0, 50.0),
                new CollisionEnvelopeQuery.Definition("early-budget-test", 10, 1)));

        assertTrue(failure.getMessage().contains("segment budget exceeded"));
    }

    @Test
    void envelopeOutsideCertifiedDomainFailsClosed() {
        LocalMetricFrame frame = frameAtZero();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> CollisionEnvelopeQuery.execute(new DataSet(), frame,
                MetricRegion.rectangle(-3_000.0, -50.0, 50.0, 50.0)));

        assertTrue(failure.getMessage().contains("exceeds the certified"));
    }

    private static LocalMetricFrame frameAtZero() {
        return LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0.0, 0.0),
            new GeographicPoint(-0.02, -0.02), new GeographicPoint(0.02, 0.02));
    }

    private static Way addMetricWay(DataSet dataSet, LocalMetricFrame frame, MetricPoint... points) {
        GeographicPoint[] geographic = java.util.Arrays.stream(points).map(frame::toGeographic)
            .toArray(GeographicPoint[]::new);
        return addGeographicWay(dataSet, geographic);
    }

    private static Way addGeographicWay(DataSet dataSet, GeographicPoint... points) {
        List<Node> nodes = java.util.Arrays.stream(points)
            .map(point -> new Node(new LatLon(point.latitudeDegrees(), point.longitudeDegrees())))
            .toList();
        Way way = new Way();
        way.setNodes(nodes);
        nodes.forEach(dataSet::addPrimitive);
        dataSet.addPrimitive(way);
        return way;
    }

    private static PrimitiveKey key(Way way) {
        return PrimitiveKey.existing(PrimitiveKey.Type.WAY, way.getUniqueId());
    }
}
