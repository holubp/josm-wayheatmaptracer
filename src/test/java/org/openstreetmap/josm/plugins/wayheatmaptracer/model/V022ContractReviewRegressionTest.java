package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Regressions for counterexamples found by the independent CP01 contract review. */
class V022ContractReviewRegressionTest {
    @Test
    void wgs84NorthingMeetsCertificateAtEquator() {
        GeographicPoint origin = new GeographicPoint(0.0, 10.0);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(-0.02, 9.98), new GeographicPoint(0.02, 10.02));
        double measured = frame.toMetric(new GeographicPoint(0.01, 10.0)).yMeters();
        double wgs84Reference = 1_105.742758;

        assertTrue(Math.abs(measured - wgs84Reference) / wgs84Reference < 0.001);
        assertTrue(frame.distortionCertificate().acceptable());
    }

    @Test
    void externalPortRepresentsAnActualEditableBoundaryAdjacency() {
        PrimitiveKey outside = node(1);
        PrimitiveKey boundary = node(2);
        PrimitiveKey interior = node(3);
        PrimitiveKey way = way(4);
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>();
        values.put(outside, detachedNode(outside, 42.0));
        values.put(boundary, detachedNode(boundary, 42.0001));
        values.put(interior, detachedNode(interior, 42.0002));
        values.put(way, new DetachedWay(way, List.of(outside, boundary, interior),
            Map.of("highway", "path"), false, false));
        ClosureDescriptor closure = closure(values.keySet(), Set.of(), Set.of(outside, boundary, interior),
            Map.of(), List.of(new ExternalPort(way, boundary, outside, 1, ExternalPort.Side.BEFORE,
                ((DetachedNode) values.get(outside)).coordinate())));

        assertDoesNotThrow(() -> new NetworkSnapshot("before", SnapshotRole.CAPTURED_BEFORE,
            "dataset", 1, closure, values, V022SnapshotFixtures.closedWorldReferrerWatches(values)));
    }

    @Test
    void evidenceContentHashChangesWhenPixelsChangeUnderSameLabel() {
        EvidenceSnapshot first = evidence(new double[] {0.0, 0.2, 0.4, 0.8});
        EvidenceSnapshot second = evidence(new double[] {0.0, 0.2, 0.4, 0.9});

        assertNotEquals(first.canonicalHash(), second.canonicalHash());
    }

    @Test
    void movedSharedIncidentSegmentCannotEscapeEditRegion() {
        PrimitiveKey selected = way(10);
        PrimitiveKey incident = way(11);
        PrimitiveKey start = node(12);
        PrimitiveKey junction = node(13);
        PrimitiveKey far = node(14);
        GeographicPoint origin = new GeographicPoint(42.0, 19.0);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(41.999, 18.999), new GeographicPoint(42.001, 19.001));
        GeographicPoint startPoint = frame.toGeographic(new MetricPoint(0, 0));
        GeographicPoint junctionPoint = frame.toGeographic(new MetricPoint(5, 0));
        GeographicPoint movedJunction = frame.toGeographic(new MetricPoint(5, 5));
        GeographicPoint farPoint = frame.toGeographic(new MetricPoint(30, 0));
        Map<PrimitiveKey, DetachedPrimitive> beforeValues = new LinkedHashMap<>();
        beforeValues.put(start, new DetachedNode(start, startPoint, Map.of(), false, false));
        beforeValues.put(junction, new DetachedNode(junction, junctionPoint, Map.of(), false, false));
        beforeValues.put(far, new DetachedNode(far, farPoint, Map.of(), false, false));
        beforeValues.put(selected, new DetachedWay(selected, List.of(start, junction),
            Map.of("highway", "path"), false, false));
        beforeValues.put(incident, new DetachedWay(incident, List.of(junction, far),
            Map.of("highway", "path"), false, false));
        Set<PrimitiveKey> editable = Set.of(selected, incident, junction);
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.EDIT_COMPONENT,
            "shared-move-v1", beforeValues.keySet(), editable, Set.of(junction), Set.of(start, far), Set.of(),
            Map.of(selected, List.of(new OccurrenceRange(0, 1)),
                incident, List.of(new OccurrenceRange(0, 1))), List.of(),
            MetricRegion.rectangle(-40, -10, 40, 10), MetricRegion.rectangle(-2, -2, 10, 10),
            false, true, true, true);
        NetworkSnapshot before = new NetworkSnapshot("before", SnapshotRole.CAPTURED_BEFORE,
            "dataset", 1, closure, beforeValues,
            V022SnapshotFixtures.closedWorldReferrerWatches(beforeValues));
        Map<PrimitiveKey, DetachedPrimitive> afterValues = new LinkedHashMap<>(beforeValues);
        afterValues.put(junction, new DetachedNode(junction, movedJunction, Map.of(), false, true));
        NetworkSnapshot after = new NetworkSnapshot("after", SnapshotRole.PROPOSED_AFTER,
            "dataset", 1, closure, afterValues,
            V022SnapshotFixtures.closedWorldReferrerWatches(afterValues));
        Map<PrimitiveKey, List<GeographicPoint>> preview = Map.of(
            selected, List.of(startPoint, movedJunction), incident, List.of(movedJunction, farPoint));

        assertThrows(IllegalArgumentException.class, () -> new AlignmentEditPlan(selected,
            new OccurrenceRange(0, 1), before, after, frame,
            new RecoveryPermissions(false, 7.01, 7.01, JunctionPolicy.REATTACH, false),
            "settings", "evidence", "parameters", "route", preview,
            new ValidationReport(ValidationReport.Disposition.APPLICABLE, List.of())));
    }

    private static EvidenceSnapshot evidence(double[] values) {
        GeographicPoint origin = new GeographicPoint(42.0, 19.0);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(41.999, 18.999), new GeographicPoint(42.001, 19.001));
        EvidenceFieldLineage lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "direct",
            EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        ScalarEvidenceField field = new ScalarEvidenceField(2, 2, values,
            new boolean[] {true, true, true, true}, lineage);
        return new EvidenceSnapshot("same-label", frame,
            RasterMetricTransform.visible(new MetricPoint(0, 1), 1.0),
            EvidenceResolution.nativeSource(1.0, 1.0), MetricRegion.rectangle(0.2, 0.2, 0.8, 0.8),
            MetricRegion.rectangle(0, 0, 1, 1), Map.of("direct", field), "same-source");
    }

    private static ClosureDescriptor closure(Set<PrimitiveKey> keys, Set<PrimitiveKey> editable,
        Set<PrimitiveKey> protectedNodes, Map<PrimitiveKey, List<OccurrenceRange>> occurrences,
        List<ExternalPort> ports) {
        return new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY, "ports-v1", keys,
            editable, Set.of(), protectedNodes, Set.of(), occurrences, ports,
            MetricRegion.rectangle(-20, -20, 20, 20), MetricRegion.rectangle(-10, -10, 10, 10),
            false, true, true, true);
    }

    private static DetachedNode detachedNode(PrimitiveKey key, double latitude) {
        return new DetachedNode(key, new GeographicPoint(latitude, 19.0), Map.of(), false, false);
    }

    private static PrimitiveKey node(long id) { return PrimitiveKey.existing(PrimitiveKey.Type.NODE, id); }

    private static PrimitiveKey way(long id) { return PrimitiveKey.existing(PrimitiveKey.Type.WAY, id); }
}
