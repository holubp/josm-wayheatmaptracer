package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Adversarial contract tests for fail-closed v0.22 detached values. */
class V022ContractAdversarialTest {
    @Test
    void spatialResolutionMustStartAtZeroMatchRepresentativeAndCoverRoute() {
        assertThrows(IllegalArgumentException.class, () -> new EvidenceResolution(
            EvidenceResolution.Kind.NATIVE_SOURCE, OptionalDouble.of(2.0), 1.0,
            List.of(new EvidenceResolution.PitchSample(1.0, OptionalDouble.of(2.0), 1.0))));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceResolution(
            EvidenceResolution.Kind.NATIVE_SOURCE, OptionalDouble.of(2.0), 1.0,
            List.of(new EvidenceResolution.PitchSample(0.0, OptionalDouble.of(3.0), 1.0))));
        EvidenceResolution varying = new EvidenceResolution(EvidenceResolution.Kind.NATIVE_SOURCE,
            OptionalDouble.of(2.0), 1.0, List.of(
                new EvidenceResolution.PitchSample(0.0, OptionalDouble.of(2.0), 1.0),
                new EvidenceResolution.PitchSample(5.0, OptionalDouble.of(2.5), 1.2)));
        assertFalse(varying.covers(new ProfileChainage(List.of(0.0, 6.0), 1.0)));
        assertThrows(IllegalArgumentException.class, () -> varying.effectivePitchMetersAt(6.0));
    }

    @Test
    void contradictoryValidationAndDuplicateAlternativesAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new ValidationReport(false, true, List.of("blocked")));
        TraceHypothesis first = hypothesis("a", "same");
        TraceHypothesis second = hypothesis("b", "same");
        assertThrows(IllegalArgumentException.class, () -> new TraceHypothesisSet(
            TrackerMode.PROBABILISTIC, List.of(first, second), TraceHypothesisSet.Status.AMBIGUOUS,
            false, 2, 1, "duplicate branches"));
    }

    @Test
    void unionBoundaryDoesNotClaimToContainItsCentralGap() {
        MetricRegion frame = new MetricRegion(List.of(
            rectangle(0, 0, 1, 3), rectangle(2, 0, 3, 3),
            rectangle(1, 0, 2, 1), rectangle(1, 2, 2, 3)));
        MetricRegion gap = MetricRegion.rectangle(1.1, 1.1, 1.9, 1.9);

        assertFalse(frame.containsRegion(gap));
        assertTrue(frame.containsRegion(MetricRegion.rectangle(0.1, 0.1, 0.9, 2.9)));
    }

    @Test
    void externalPortMustCrossClosureAtDeclaredTerminalSide() {
        PrimitiveKey first = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 1);
        PrimitiveKey second = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 2);
        PrimitiveKey way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 3);
        PrimitiveKey outside = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 4);
        Map<PrimitiveKey, DetachedPrimitive> values = Map.of(
            first, new DetachedNode(first, new GeographicPoint(42, 19), Map.of(), false, false),
            second, new DetachedNode(second, new GeographicPoint(42.001, 19), Map.of(), false, false),
            way, new DetachedWay(way, List.of(first, second), Map.of("highway", "path"), false, false));
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,
            "ports-v1", values.keySet(), Set.of(), Set.of(), Set.of(first, second), Set.of(), Map.of(),
            List.of(new ExternalPort(way, second, outside, 1, ExternalPort.Side.BEFORE,
                new GeographicPoint(42.002, 19))), MetricRegion.rectangle(-10, -10, 10, 10),
            MetricRegion.rectangle(-5, -5, 5, 5), false, true, true, true);

        assertThrows(IllegalArgumentException.class, () -> new NetworkSnapshot("before",
            SnapshotRole.CAPTURED_BEFORE, "dataset", 1, closure, values));
    }

    @Test
    void equatorCrossingCertificateUsesInteriorMaximumScale() {
        GeographicPoint origin = new GeographicPoint(3.0, 10.0);
        assertThrows(IllegalArgumentException.class, () -> LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(-3.0, 9.9), new GeographicPoint(3.0, 10.1)));
    }

    private static TraceHypothesis hypothesis(String id, String branch) {
        return new TraceHypothesis(id, branch, List.of(new MetricPoint(0, 0), new MetricPoint(1, 0)),
            List.of(ObservationOwnership.DIRECT_TWO_SIDED, ObservationOwnership.DIRECT_TWO_SIDED),
            0.0, 0.4, Map.of());
    }

    private static List<MetricPoint> rectangle(double minX, double minY, double maxX, double maxY) {
        return List.of(new MetricPoint(minX, minY), new MetricPoint(maxX, minY),
            new MetricPoint(maxX, maxY), new MetricPoint(minX, maxY));
    }
}
