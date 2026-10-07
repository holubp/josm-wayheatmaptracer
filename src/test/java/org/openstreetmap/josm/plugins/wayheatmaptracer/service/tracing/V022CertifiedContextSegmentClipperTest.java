package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;

class V022CertifiedContextSegmentClipperTest {
    private static final GeographicPoint SOUTH_WEST = point(-0.001, -0.001);
    private static final GeographicPoint NORTH_EAST = point(0.001, 0.001);
    private static final LocalMetricFrame FRAME = LocalMetricFrame.certifiedEquirectangular(
            point(0, 0), SOUTH_WEST, NORTH_EAST);

    @Test
    void outsideEndpointsStillDetectCrossing() {
        var forward = CertifiedContextSegmentClipper.clip(point(-0.01, 0), point(0.01, 0), FRAME)
                .orElseThrow();
        var reverse = CertifiedContextSegmentClipper.clip(point(0.01, 0), point(-0.01, 0), FRAME)
                .orElseThrow();
        assertClose(FRAME.toMetric(point(-0.001, 0)), forward.start());
        assertClose(FRAME.toMetric(point(0.001, 0)), forward.end());
        assertEquals(forward.start(), reverse.end());
        assertEquals(forward.end(), reverse.start());
        assertFalse(forward.originalStart());
        assertFalse(forward.originalEnd());
    }

    @Test
    void clippedEndpointDoesNotBecomeSharedOsmTouch() {
        var clipped = CertifiedContextSegmentClipper.clip(point(-0.01, 0), point(0, 0), FRAME)
                .orElseThrow();
        assertFalse(clipped.originalStart());
        assertTrue(clipped.originalEnd());
        assertClose(FRAME.toMetric(point(-0.001, 0)), clipped.start());
    }

    @Test
    void cornersTangenciesAndBoundaryOverlapAreRetained() {
        assertTrue(CertifiedContextSegmentClipper.clip(point(-0.01, -0.01),
                point(-0.001, -0.001), FRAME).isPresent());
        var tangent = CertifiedContextSegmentClipper.clip(point(-0.003, 0.001),
                point(0.001, -0.003), FRAME).orElseThrow();
        assertClose(FRAME.toMetric(SOUTH_WEST), tangent.start());
        assertClose(tangent.start(), tangent.end());
        assertFalse(tangent.originalStart());
        assertFalse(tangent.originalEnd());
        var boundary = CertifiedContextSegmentClipper.clip(point(-0.01, -0.001),
                point(0.01, -0.001), FRAME).orElseThrow();
        assertClose(FRAME.toMetric(SOUTH_WEST), boundary.start());
        assertClose(FRAME.toMetric(point(0.001, -0.001)), boundary.end());
        assertTrue(CertifiedContextSegmentClipper.clip(point(-0.01, -0.01),
                point(-0.009, -0.009), FRAME).isEmpty());
    }

    @Test
    void antimeridianBranchIsConsistent() {
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(
                point(0, 180), point(-0.001, 179.999), point(0.001, -179.999));
        var crossing = CertifiedContextSegmentClipper.clip(point(0, 179.99),
                point(0, -179.99), frame).orElseThrow();
        var reversed = CertifiedContextSegmentClipper.clip(point(0, -179.99),
                point(0, 179.99), frame).orElseThrow();
        assertEquals(crossing.start(), reversed.end());
        assertEquals(crossing.end(), reversed.start());
        assertEquals(frame.toMetric(point(0, 179.999)), crossing.start());
        assertEquals(frame.toMetric(point(0, -179.999)), crossing.end());
    }

    @Test
    void ambiguousOrNearBoundaryExclusionFailsClosed() {
        assertThrows(CertifiedContextSegmentClipper.InvalidContextGeometryException.class,
                () -> CertifiedContextSegmentClipper.clip(point(0, -90), point(0, 90), FRAME));
        assertThrows(CertifiedContextSegmentClipper.InvalidContextGeometryException.class,
                () -> CertifiedContextSegmentClipper.clip(point(0.00100000001, -0.01),
                        point(0.00100000001, 0.01), FRAME));
    }

    @Test
    void changedGeometryNearCertificateEdgeCannotUseContextClipping() {
        assertThrows(CertifiedContextSegmentClipper.InvalidContextGeometryException.class,
                () -> CertifiedContextSegmentClipper.requireChangedInside(
                        FRAME.toMetric(point(0.00099999999999, 0)),
                        FRAME.toMetric(point(0.0009, 0)), FRAME));
        MetricPoint boundary = FRAME.toMetric(point(0.001, 0));
        assertThrows(CertifiedContextSegmentClipper.InvalidContextGeometryException.class,
                () -> CertifiedContextSegmentClipper.requireChangedInside(
                        new MetricPoint(0, boundary.yMeters() - 0.0002),
                        new MetricPoint(0.0001, boundary.yMeters() - 0.0002), FRAME));
    }

    @Test
    void actualClassifierCanCallNearBoundaryShortParallelSegmentsOverlapping() throws Exception {
        double north = FRAME.toMetric(NORTH_EAST).yMeters();
        MetricPoint changedStart = new MetricPoint(0, north - 0.00002);
        MetricPoint changedEnd = new MetricPoint(0.0001, north - 0.00002);
        MetricPoint outsideStart = new MetricPoint(0, north + 0.00002);
        MetricPoint outsideEnd = new MetricPoint(0.0001, north + 0.00002);
        var classify = ModernSingleWayEditPlanAdapter.class.getDeclaredMethod("classify",
                MetricPoint.class, MetricPoint.class, MetricPoint.class, MetricPoint.class);
        classify.setAccessible(true);

        assertEquals("COLLINEAR_OVERLAP", classify.invoke(null, changedStart, changedEnd,
                outsideStart, outsideEnd).toString());
        assertThrows(CertifiedContextSegmentClipper.InvalidContextGeometryException.class,
                () -> CertifiedContextSegmentClipper.requireChangedInside(
                        changedStart, changedEnd, FRAME));
    }

    @Test
    void originalChordDecisionKeepsPhysicalCoordinateAndAreaMargins() {
        var changed = CertifiedContextSegmentClipper.originalChord(
                point(0, 0), point(0, 0.000001), FRAME);
        assertContact(changed, point(latitudeMeters(0.5e-8), 0),
                point(latitudeMeters(0.5e-8), 0.000001), false);
        assertContact(changed, point(latitudeMeters(1e-5), 0),
                point(latitudeMeters(1e-5), 0.000001), true);

        var shortChanged = CertifiedContextSegmentClipper.originalChord(
                point(0, 0), point(0, 1e-10), FRAME);
        assertContact(shortChanged, point(latitudeMeters(0.0001), 0),
                point(latitudeMeters(0.0001), 1e-10), false);
        assertContact(shortChanged, point(latitudeMeters(0.1), 0),
                point(latitudeMeters(0.1), 1e-10), true);
    }

    @Test
    void originalChordDecisionRetainsReversalAndAntimeridianCrossing() {
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(
                point(0, 180), point(-0.001, 179.999), point(0.001, -179.999));
        var changed = CertifiedContextSegmentClipper.originalChord(
                point(-0.0005, 180), point(0.0005, 180), frame);
        var eastward = CertifiedContextSegmentClipper.originalChord(
                point(0, 179.99), point(0, -179.99), frame);
        var westward = CertifiedContextSegmentClipper.originalChord(
                point(0, -179.99), point(0, 179.99), frame);
        assertEquals(CertifiedContextSegmentClipper.OriginalContact.CROSSING,
                CertifiedContextSegmentClipper.originalContact(changed, eastward, frame));
        assertEquals(CertifiedContextSegmentClipper.OriginalContact.CROSSING,
                CertifiedContextSegmentClipper.originalContact(changed, westward, frame));
        assertEquals(CertifiedContextSegmentClipper.OriginalContact.CROSSING,
                CertifiedContextSegmentClipper.originalContact(eastward, changed, frame));
    }

    @Test
    void sharedWitnessPartitionHandlesRaysOverlapAndDegeneracyInEveryOrder() {
        NamedChord east = chord("0", "0", "1", "0", 70, 71);
        NamedChord north = chord("0", "0", "0", "1", 70, 72);
        NamedChord sameRay = chord("0", "0", "1", "0.000000001", 70, 73);
        NamedChord oppositeRay = chord("0", "0", "-1", "0.000000001", 70, 74);
        NamedChord shortOverlap = chord("0", "0", "0.0000001", "0", 70, 75);
        NamedChord point = chord("0", "0", "0", "0", 70, 76);
        assertEveryOrder(east, north, true, false);
        NamedChord unsavedEast = chord("0", "0", "1", "0", -101, 71);
        NamedChord unsavedNorth = chord("0", "0", "0", "1", -101, 72);
        assertEveryOrder(unsavedEast, unsavedNorth, true, false);
        assertEveryOrder(east, sameRay, false, true);
        assertEveryOrder(east, oppositeRay, true, false);
        assertEveryOrder(east, shortOverlap, false, true);
        assertEveryOrder(east, point, false, false);
    }

    @Test
    void remoteOutwardWitnessSurvivesSharedTouchAndEveryPermutation() {
        NamedChord diagonal = chord("0", "0", "1", "1", 80, 81);
        NamedChord nearInterior = chord("0.5", "0.50000025", "0", "0", 82, 80);
        assertFalse(evidence(diagonal, nearInterior).positiveOverlap(),
                "the unequal-origin orientation allowance must not become collinear overlap");
        for (NamedChord first : List.of(diagonal, diagonal.reversed())) {
            for (NamedChord second : List.of(nearInterior, nearInterior.reversed())) {
                for (boolean swap : new boolean[] {false, true}) {
                    var evidence = swap ? evidence(second, first) : evidence(first, second);
                    assertFalse(evidence.soleOwnedContact(), evidence.toString());
                    assertTrue(evidence.witnesses().stream().anyMatch(w -> !w.owned()
                            && w.kind() == CertifiedContextSegmentClipper.WitnessKind.TOLERANT),
                            evidence.toString());
                }
            }
        }
    }

    @Test
    void coincidentDistinctNodesAndRemoteTouchesNeverInheritSharedOwnership() {
        NamedChord east = chord("0", "0", "1", "0", 90, 91);
        NamedChord distinctStart = chord("0", "0", "0", "1", 92, 93);
        NamedChord exactInterior = chord("0.5", "0", "0.5", "1", 94, 95);
        NamedChord tolerantInterior = chord("0.5", "0.000000001", "0.5", "1", 96, 97);
        assertEveryOrder(east, distinctStart, false, false);
        assertEveryOrder(east, exactInterior, false, false);
        assertEveryOrder(east, tolerantInterior, false, false);
        PrimitiveKey local = PrimitiveKey.planned(PrimitiveKey.Type.NODE, 1);
        NamedChord localEast = new NamedChord(east.geometry(), local, key(91));
        NamedChord localNorth = new NamedChord(distinctStart.geometry(), local, key(93));
        assertEveryOrder(localEast, localNorth, false, false);
    }

    @Test
    void branchUnionCannotEraseAnUnownedWitnessAndAntimeridianOwnerIsAligned() {
        var owned = new CertifiedContextSegmentClipper.ContactEvidence(false, false, false,
                true, List.of(new CertifiedContextSegmentClipper.EndpointWitness(0,
                        CertifiedContextSegmentClipper.EndpointRole.A,
                        CertifiedContextSegmentClipper.WitnessKind.EXACT, true)));
        var remote = new CertifiedContextSegmentClipper.ContactEvidence(false, false, false,
                false, List.of(new CertifiedContextSegmentClipper.EndpointWitness(1,
                        CertifiedContextSegmentClipper.EndpointRole.D,
                        CertifiedContextSegmentClipper.WitnessKind.NEAR, false)));
        assertFalse(owned.union(remote).soleOwnedContact());
        assertFalse(remote.union(owned).soleOwnedContact());

        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(
                point(0, 180), point(-0.001, 179.999), point(0.001, -179.999));
        PrimitiveKey shared = key(98);
        var west = CertifiedContextSegmentClipper.originalChord(point(0, 180),
                point(0, 179.9999), frame);
        var north = CertifiedContextSegmentClipper.originalChord(point(0, 180),
                point(0.0001, 180), frame);
        var result = CertifiedContextSegmentClipper.originalContactEvidence(west,
                shared, key(99), north, shared, key(100), frame);
        assertTrue(result.soleOwnedContact(), result.toString());
        assertTrue(result.witnesses().stream().allMatch(w -> w.branchShift() == 0));
    }

    private record NamedChord(CertifiedContextSegmentClipper.ExactChord geometry,
            PrimitiveKey first, PrimitiveKey last) {
        NamedChord reversed() {
            return new NamedChord(new CertifiedContextSegmentClipper.ExactChord(
                    geometry.end(), geometry.start()), last, first);
        }
    }

    private static NamedChord chord(String ax, String ay, String bx, String by,
            long firstId, long lastId) {
        var a = new CertifiedContextSegmentClipper.ExactPoint(
                new BigDecimal(ax), new BigDecimal(ay));
        var b = new CertifiedContextSegmentClipper.ExactPoint(
                new BigDecimal(bx), new BigDecimal(by));
        return new NamedChord(new CertifiedContextSegmentClipper.ExactChord(a, b),
                key(firstId), key(lastId));
    }

    private static PrimitiveKey key(long id) {
        return PrimitiveKey.existing(PrimitiveKey.Type.NODE, id);
    }

    private static CertifiedContextSegmentClipper.ContactEvidence evidence(
            NamedChord first, NamedChord second) {
        return CertifiedContextSegmentClipper.originalContactEvidence(first.geometry(),
                first.first(), first.last(), second.geometry(), second.first(), second.last(), FRAME);
    }

    private static void assertEveryOrder(NamedChord first, NamedChord second,
            boolean soleOwned, boolean positiveOverlap) {
        for (NamedChord a : List.of(first, first.reversed())) {
            for (NamedChord b : List.of(second, second.reversed())) {
                for (boolean swap : new boolean[] {false, true}) {
                    var result = swap ? evidence(b, a) : evidence(a, b);
                    assertEquals(soleOwned, result.soleOwnedContact(), result.toString());
                    if (positiveOverlap) assertTrue(result.positiveOverlap(), result.toString());
                    else assertFalse(result.positiveOverlap(), result.toString());
                }
            }
        }
    }

    private static void assertContact(CertifiedContextSegmentClipper.ExactChord changed,
            GeographicPoint contextFirst, GeographicPoint contextLast, boolean expectNone) {
        var forward = CertifiedContextSegmentClipper.originalChord(contextFirst, contextLast, FRAME);
        var reverse = CertifiedContextSegmentClipper.originalChord(contextLast, contextFirst, FRAME);
        var forwardContact = CertifiedContextSegmentClipper.originalContact(changed, forward, FRAME);
        var reverseContact = CertifiedContextSegmentClipper.originalContact(changed, reverse, FRAME);
        assertEquals(expectNone, forwardContact == CertifiedContextSegmentClipper.OriginalContact.NONE);
        assertEquals(forwardContact, reverseContact);
    }

    private static double latitudeMeters(double metres) {
        return Math.toDegrees(metres / FRAME.distortionCertificate().northMetersPerRadian());
    }

    private static GeographicPoint point(double latitude, double longitude) {
        return new GeographicPoint(latitude, longitude);
    }

    private static void assertClose(MetricPoint expected, MetricPoint actual) {
        assertEquals(expected.xMeters(), actual.xMeters(), 1e-9);
        assertEquals(expected.yMeters(), actual.yMeters(), 1e-9);
    }
}
