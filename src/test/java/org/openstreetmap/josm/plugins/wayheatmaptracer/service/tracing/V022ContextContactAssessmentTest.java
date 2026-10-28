package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;

/** Hand-constructed final contacts, independent of tracing/search and dataset mutation. */
class V022ContextContactAssessmentTest {
    private static final PrimitiveKey SELECTED = way(10);
    private static final PrimitiveKey CONTEXT = way(20);
    private static final OccurrenceRange RANGE = new OccurrenceRange(0, 1);
    private static final LocalMetricFrame FRAME = LocalMetricFrame.certifiedEquirectangular(
            new GeographicPoint(0, 0), new GeographicPoint(-0.001, -0.001),
            new GeographicPoint(0.001, 0.001));

    @ParameterizedTest
    @ValueSource(strings = {"waterway", "highway", "railway", "building", "natural", "unknown"})
    void newIndependentCrossingRequiresReviewForEveryFeatureType(String semantic) {
        Fixture fixture = fixture(new double[][] {{0, 1}, {0, 5}}, semantic, false, false);
        assertReview(fixture);
    }

    @Test
    void inheritedBoundaryWithIncompleteParentRemainsReviewableWithoutCompletingRelation() {
        Fixture fixture = fixture(new double[][] {{0, -5}, {0, 5}}, "boundary", false, true);
        var witness = fixture.before().semanticWitness();
        assertFalse(witness.relations().get(relation(90)).complete());
        assertReview(fixture);
        assertFalse(fixture.before().semanticWitness().relations().get(relation(90)).complete());
        assertEquals(witness, fixture.before().semanticWitness());
        var contact = details(fixture).contacts().get(0);
        assertTrue(contact.inherited());
        assertEquals(CONTEXT, contact.contextWayKey());
    }

    @Test
    void independentVertexTouchRequiresReview() {
        assertReview(fixture(new double[][] {{0, 2}, {0, 5}}, "waterway", false, false));
    }

    @Test
    void independentCollinearOverlapRequiresReview() {
        assertReview(fixture(new double[][] {{-2, 2}, {2, 2}}, "highway", false, false));
    }

    @Test
    void contactElsewhereOnConnectedWayRemainsIndependent() {
        assertReview(fixture(new double[][] {{-8, 0}, {-8, 5}, {0, 5}, {0, 1}},
                "highway", true, false));
    }

    @Test
    void sharedNodeAtActualContactKeepsExistingConnectionRule() {
        Fixture fixture = fixture(new double[][] {{-8, 0}, {-8, 5}}, "highway", true, false);
        assertEquals(List.of(), findings(fixture));
    }

    @Test
    void changingContextGeometryCannotBorrowIndependentReviewPolicy() {
        Fixture fixture = fixture(new double[][] {{0, 1}, {0, 5}}, "waterway", false, false);
        DetachedNode old = (DetachedNode) fixture.after().get(node(4));
        fixture.after().put(old.key(), new DetachedNode(old.key(), geographic(0, 6), old.tags(), false, true));
        assertTrue(findings(fixture).contains("final-topology:CROSSING"));
    }

    @Test
    void missingContextGeometryFailsClosed() {
        Fixture fixture = fixture(new double[][] {{0, 1}, {0, 5}}, "waterway", false, false);
        fixture.after().remove(node(4));
        assertThrows(IllegalArgumentException.class, () -> findings(fixture));
    }

    @Test
    void missingSelectedOccurrenceAuthorityCannotBecomeReviewable() {
        Fixture fixture = fixture(new double[][] {{0, 1}, {0, 5}}, "waterway", false, false);
        ClosureDescriptor old = fixture.before().closure();
        var closure = new ClosureDescriptor(old.scope(), old.queryVersion(), old.primitiveKeys(),
                Set.of(), Set.of(), old.protectedExistingNodeKeys(), Set.of(), Map.of(),
                old.externalPorts(), old.collisionEnvelope(), old.editRegion(), old.mayCreateNodes(),
                true, true, true);
        NetworkSnapshot before = new NetworkSnapshot("no-authority", fixture.before().role(), "dataset", 1,
                closure, fixture.before().primitives(), fixture.before().incomingReferrerWatches());
        assertTrue(findings(new Fixture(before, fixture.after())).contains("final-topology:CROSSING"));
    }

    @Test
    void structuredContactFeedsFinalQualityWithoutReplacingOtherFindingsOrMetrics() {
        Fixture fixture = fixture(new double[][] {{0, 1}, {0, 5}}, "waterway", false, false);
        var contacts = details(fixture).contacts();
        assertEquals(1, contacts.size());
        var contact = contacts.get(0);
        assertEquals(SELECTED, contact.selectedWayKey());
        assertEquals(CONTEXT, contact.contextWayKey());
        assertEquals(ContextContactAssessment.ContactType.CROSSING, contact.contactType());
        assertEquals(ContextContactAssessment.Disposition.REVIEW_REQUIRED, contact.disposition());
        assertEquals(1, contact.selectedSegmentIndex());
        assertEquals(0, contact.contextSegmentIndex());
        assertFalse(contact.inherited());
        assertEquals(1, contact.location().size());
        assertEquals(0.0, contact.location().get(0).xMeters(), 1e-8);
        assertEquals(2.0, contact.location().get(0).yMeters(), 1e-8);
        var original = new FinalGeometryEvaluator.Result("route", FinalGeometryEvaluator.Disposition.APPLICABLE,
                List.of(), 16, 15, 1, 0.25, 0.5);
        var reviewed = FinalGeometryEvaluator.withContextContacts(original, contacts);
        assertEquals(FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED, reviewed.disposition());
        assertTrue(reviewed.has(FinalGeometryEvaluator.FindingCode.INDEPENDENT_CONTEXT_CONTACT));
        assertEquals(16, reviewed.totalLengthMeters());
        assertEquals(15, reviewed.directlySupportedLengthMeters());
        assertEquals(1, reviewed.worstUnsupportedSpanMeters());
        assertEquals(0.25, reviewed.meanImageCenterCost());
        assertEquals(0.5, reviewed.bendPreservingRoughness());
        var opaqueIncident = new FinalGeometryEvaluator.Finding(FinalGeometryEvaluator.FindingCode.PREJUNCTION_CROSSING,
                FinalGeometryEvaluator.Severity.HARD_BLOCK, 0, 1, 0);
        var blocked = FinalGeometryEvaluator.withContextContacts(new FinalGeometryEvaluator.Result("route",
                FinalGeometryEvaluator.Disposition.HARD_BLOCKED, List.of(opaqueIncident), 16, 15, 1, 0.25, 0.5), contacts);
        assertEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED, blocked.disposition());
        assertTrue(blocked.findings().contains(opaqueIncident));
    }

    @Test
    void selfCrossingRemainsHardBlocked() {
        Fixture fixture = fixture(new double[][] {{30, 0}, {30, 5}}, "waterway", false, false);
        List<PrimitiveKey> order = new java.util.ArrayList<>();
        order.add(node(1));
        double[][] points = {{4, 4}, {-4, 4}, {4, -4}};
        for (int i = 0; i < points.length; i++) {
            PrimitiveKey key = PrimitiveKey.planned(PrimitiveKey.Type.NODE, i + 1);
            fixture.after().put(key, new DetachedNode(key, geographic(points[i][0], points[i][1]), Map.of(), false, true));
            order.add(key);
        }
        order.add(node(2));
        fixture.after().put(SELECTED, new DetachedWay(SELECTED, order, Map.of("highway", "path"), false, true));
        assertTrue(findings(fixture).contains("final-topology:CROSSING"));
    }

    private static void assertReview(Fixture fixture) {
        List<String> findings = findings(fixture);
        assertTrue(findings.contains("independent-context-contact-review-required"), findings.toString());
        assertFalse(findings.stream().anyMatch(value -> value.startsWith("final-topology:")), findings.toString());
        for (var entry : fixture.before().primitives().entrySet()) {
            if (!entry.getKey().equals(SELECTED)) assertEquals(entry.getValue(), fixture.after().get(entry.getKey()));
        }
    }

    private static List<String> findings(Fixture fixture) {
        return ModernSingleWayEditPlanAdapter.finalTopologyFindings(fixture.before(), fixture.after(),
                SELECTED, RANGE, evidence(), 7.01);
    }

    private static ModernSingleWayEditPlanAdapter.FinalTopologyAssessment details(Fixture fixture) {
        return ModernSingleWayEditPlanAdapter.assessFinalTopology(fixture.before(), fixture.after(),
                SELECTED, RANGE, evidence(), 7.01);
    }

    private static Fixture fixture(double[][] contextPoints, String semantic, boolean shared, boolean incompleteParent) {
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>();
        values.put(node(1), new DetachedNode(node(1), geographic(-8, 0), Map.of(), false, false));
        values.put(node(2), new DetachedNode(node(2), geographic(8, 0), Map.of(), false, false));
        values.put(SELECTED, new DetachedWay(SELECTED, List.of(node(1), node(2)), Map.of("highway", "path"), false, false));
        List<PrimitiveKey> contextOrder = new java.util.ArrayList<>();
        for (int i = 0; i < contextPoints.length; i++) {
            PrimitiveKey key = shared && i == 0 ? node(1) : node(i + 3);
            values.putIfAbsent(key, new DetachedNode(key, geographic(contextPoints[i][0], contextPoints[i][1]), Map.of(), false, false));
            contextOrder.add(key);
        }
        values.put(CONTEXT, new DetachedWay(CONTEXT, contextOrder,
                semantic.equals("unknown") ? Map.of() : Map.of(semantic, "stream"), false, false));
        MetricRegion region = MetricRegion.rectangle(-50, -50, 50, 50);
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.EDIT_COMPONENT,
                "context-contact-test-v1", values.keySet(), Set.of(SELECTED), Set.of(), Set.of(node(1), node(2)),
                Set.of(), Map.of(SELECTED, List.of(RANGE)), List.of(), region, region, true, true, true, true);
        var watches = new LinkedHashMap<>(V022SnapshotFixtures.closedWorldReferrerWatches(values));
        if (incompleteParent) watches.put(CONTEXT, Set.of(relation(90)));
        NetworkSnapshot before = new NetworkSnapshot("before", SnapshotRole.CAPTURED_BEFORE, "dataset", 1,
                closure, values, watches);
        if (incompleteParent) {
            var parent = new NonTransportSemanticWitness.RelationEvidence(relation(90),
                    Map.of("type", "multipolygon", "boundary", "protected_area"),
                    List.of(new NonTransportSemanticWitness.Member(CONTEXT, "outer", true),
                            new NonTransportSemanticWitness.Member(way(99), "inner", false)),
                    false, false, false, Set.of());
            var witness = new NonTransportSemanticWitness(before.canonicalHash(), "dataset", 1, Map.of(relation(90), parent));
            before = new NetworkSnapshot(before.snapshotId(), before.role(), before.datasetIdentity(),
                    before.sourceGeneration(), closure, values, watches, witness);
        }
        Map<PrimitiveKey, DetachedPrimitive> after = new LinkedHashMap<>(values);
        PrimitiveKey p = PrimitiveKey.planned(PrimitiveKey.Type.NODE, 1);
        PrimitiveKey q = PrimitiveKey.planned(PrimitiveKey.Type.NODE, 2);
        after.put(p, new DetachedNode(p, geographic(-4, 2), Map.of(), false, true));
        after.put(q, new DetachedNode(q, geographic(4, 2), Map.of(), false, true));
        after.put(SELECTED, new DetachedWay(SELECTED, List.of(node(1), p, q, node(2)), Map.of("highway", "path"), false, true));
        return new Fixture(before, after);
    }

    private static EvidenceSnapshot evidence() {
        boolean[] valid = new boolean[16];
        Arrays.fill(valid, true);
        var field = new ScalarEvidenceField(4, 4, new double[16], valid, new EvidenceFieldLineage(
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC, EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
                "contacts", EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        var region = MetricRegion.rectangle(-50, -50, 50, 50);
        var transform = new RasterMetricTransform("contacts", RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(-75, -75), 50, 0, 0, 50, 1);
        return new EvidenceSnapshot("contacts", FRAME, transform, EvidenceResolution.nativeSource(50, 50),
                region, region, Map.of("scalar", field), "public");
    }

    private static GeographicPoint geographic(double east, double north) { return FRAME.toGeographic(new MetricPoint(east, north)); }
    private static PrimitiveKey node(long id) { return PrimitiveKey.existing(PrimitiveKey.Type.NODE, id); }
    private static PrimitiveKey way(long id) { return PrimitiveKey.existing(PrimitiveKey.Type.WAY, id); }
    private static PrimitiveKey relation(long id) { return PrimitiveKey.existing(PrimitiveKey.Type.RELATION, id); }
    private record Fixture(NetworkSnapshot before, Map<PrimitiveKey, DetachedPrimitive> after) { }
}
