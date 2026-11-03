package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.RetainedScalarProfiles;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class LegacyEmissionBindingTest {
    private static final String FIELD = "synthetic";

    @Test
    void mapsSeventeenOriginalRowsToNineteenRetainedProfilesWithoutPromotion() {
        Fixture f = fixture();
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(f.evidence(), FIELD,
                f.input(), f.chainage(), 3, budget, owner)) {
            RetainedScalarProfiles retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(
                f.evidence(), FIELD, f.input().profileLocations(), 3, 1, 1, budget, permit);
            List<Integer> mapping = new ArrayList<>();
            for (int index = 1; index <= 17; index++) mapping.add(index);
            var movable = new LegacyEmissionBinding.ProtectedOccurrence("source-occ-7", 5, 6, 6.0,
                f.input().profileLocations().get(6).metricPoint(), false);
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained, mapping, 24,
                List.of(movable));
            assertEquals(17, binding.observations().size());
            assertEquals(19, retained.profiles().levelZeroProfiles().size());
            assertEquals(24, binding.trackerNormalizationRasterPx());
            assertEquals(List.of(movable), binding.protectedOccurrences());
            assertFalse(binding.protectedOccurrences().get(0).fixed(),
                "A movable source occurrence must not be silently rewritten as fixed");
            assertThrows(UnsupportedOperationException.class, () -> binding.protectedOccurrences().clear());
            for (int row = 0; row < 17; row++) {
                var observation = binding.observations().get(row);
                assertEquals(row, observation.originalRow());
                assertEquals(row + 1, observation.retainedObservationIndex());
                assertSame(retained.profiles().levelZeroProfiles().get(row + 1), observation.profile());
                assertEquals(NativeConnectorEvaluator.SourceClass.OBSERVED, observation.sourceClass());
                assertEquals(observation.profile().peaks().size(), observation.producerPeaks().size());
                for (int peak = 0; peak < observation.producerPeaks().size(); peak++) {
                    assertSame(observation.profile().peaks().get(peak), observation.producerPeaks().get(peak).peak());
                    assertEquals(peak, observation.producerPeaks().get(peak).originalPeakId());
                }
            }
            assertFalse(binding.productionEligible(), "Declared synthetic evidence is never production authority");
            try (var sampled = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained,
                    binding)) {
                assertSame(binding, sampled.legacyBinding().orElseThrow());
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void invalidMappingsAndAbsentLegacyBindingFailClosedWithoutChangingOldPublication() {
        Fixture f = fixture();
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(f.evidence(), FIELD,
                f.input(), f.chainage(), 3, budget, owner)) {
            RetainedScalarProfiles retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(
                f.evidence(), FIELD, f.input().profileLocations(), 3, 1, 1, budget, permit);
            for (List<Integer> invalid : List.of(List.of(1, 1, 2), List.of(2, 1), List.of(-1, 1),
                    List.of(17, 19))) {
                assertThrows(IllegalArgumentException.class, () ->
                    OptionalEmissionContextFactory.createLegacyBinding(permit, retained, invalid, 24, List.of()));
            }
            var validMapping = java.util.stream.IntStream.range(1, 18).boxed().toList();
            for (double invalidNormalization : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
                assertThrows(IllegalArgumentException.class, () ->
                    OptionalEmissionContextFactory.createLegacyBinding(permit, retained, validMapping,
                        invalidNormalization, List.of()));
            }
            assertThrows(IllegalArgumentException.class, () ->
                OptionalEmissionContextFactory.createLegacyBinding(permit, retained, validMapping, 24,
                    List.of(new LegacyEmissionBinding.ProtectedOccurrence("outside", 0, 1, 100,
                        new MetricPoint(0, 0), false))));
            assertThrows(IllegalArgumentException.class, () ->
                OptionalEmissionContextFactory.createLegacyBinding(permit, retained, validMapping, 24,
                    List.of(new LegacyEmissionBinding.ProtectedOccurrence("wrong-position", 5, 6, 6.0,
                        new MetricPoint(f.input().profileLocations().get(6).metricPoint().xMeters() + 0.25,
                            f.input().profileLocations().get(6).metricPoint().yMeters()), false))));
            assertThrows(IllegalArgumentException.class, () ->
                OptionalEmissionContextFactory.createLegacyBinding(permit, retained, validMapping, 24, List.of(
                    new LegacyEmissionBinding.ProtectedOccurrence("reused", 0, 1, 1.5,
                        new MetricPoint(0, 0), false),
                    new LegacyEmissionBinding.ProtectedOccurrence("between", 1, 2, 2.5,
                        new MetricPoint(0, 0), true),
                    new LegacyEmissionBinding.ProtectedOccurrence("reused", 2, 3, 3.5,
                        new MetricPoint(0, 0), false))));
            assertThrows(IllegalArgumentException.class, () ->
                OptionalEmissionContextFactory.createLegacyBinding(permit, retained, validMapping, 24, List.of(
                    new LegacyEmissionBinding.ProtectedOccurrence("same-chain-a", 0, 1, 1.5,
                        new MetricPoint(0, 0), false),
                    new LegacyEmissionBinding.ProtectedOccurrence("same-chain-b", 0, 1, 1.5,
                        new MetricPoint(0.25, 0), false))));
            assertDoesNotThrow(() -> OptionalEmissionContextFactory.createLegacyBinding(
                permit, retained, validMapping, 24, List.of(
                    new LegacyEmissionBinding.ProtectedOccurrence("coincident-a", 0, 1, 1.5,
                        new MetricPoint(0, 0), false),
                    new LegacyEmissionBinding.ProtectedOccurrence("coincident-b", 0, 1, 1.5,
                        new MetricPoint(0, 0), true))));
            try (var ordinary = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained)) {
                assertTrue(ordinary.legacyBinding().isEmpty());
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void foreignPermitAndChangedProducerProfileCannotPublishLegacyFacts() {
        Fixture f = fixture();
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(f.evidence(), FIELD,
                f.input(), f.chainage(), 3, budget, owner);
             var foreign = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(f.evidence(), FIELD,
                f.input(), f.chainage(), 3, budget, owner)) {
            RetainedScalarProfiles retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(
                f.evidence(), FIELD, f.input().profileLocations(), 3, 1, 1, budget, permit);
            List<Integer> mapping = java.util.stream.IntStream.range(1, 18).boxed().toList();
            assertThrows(IllegalArgumentException.class, () ->
                OptionalEmissionContextFactory.createLegacyBinding(foreign, retained, mapping, 24, List.of()));
            var binding = OptionalEmissionContextFactory.createLegacyBinding(permit, retained, mapping, 24,
                List.of());
            binding.observations().get(0).profile().normalScreen().x += 0.25;
            assertThrows(IllegalStateException.class, () ->
                OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained, binding));
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void metadataReservationRefusalReleasesWithTheAttemptOwner() {
        Fixture f = fixture();
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(f.evidence(), FIELD,
                f.input(), f.chainage(), 3, budget, owner)) {
            RetainedScalarProfiles retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(
                f.evidence(), FIELD, f.input().profileLocations(), 3, 1, 1, budget, permit);
            List<Integer> mapping = java.util.stream.IntStream.range(1, 18).boxed().toList();
            long remaining = ledger.limitBytes() - ledger.currentBytes();
            assertTrue(remaining > 1);
            AttemptMemoryLedger.ResourceLimitException first;
            try (var pressure = owner.reserve(remaining - 1)) {
                first = assertThrows(AttemptMemoryLedger.ResourceLimitException.class, () ->
                    OptionalEmissionContextFactory.createLegacyBinding(permit, retained, mapping, 24, List.of()));
            }
            assertSame(first, assertThrows(AttemptMemoryLedger.ResourceLimitException.class, () ->
                OptionalEmissionContextFactory.createLegacyBinding(permit, retained, mapping, 24, List.of())));
        }
        assertEquals(0, ledger.currentBytes());
    }

    private record Fixture(EvidenceSnapshot evidence, CorridorTraceInput input, ProfileChainage chainage) { }

    private static Fixture fixture() {
        int width = 40, height = 40;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            values[y * width + x] = Math.exp(-Math.pow(y - 20, 2) / 2.0);
        }
        var origin = new GeographicPoint(50, 14);
        var frame = LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(49.99, 13.99), new GeographicPoint(50.01, 14.02));
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1);
        var field = new ScalarEvidenceField(width, height, values, valid,
            new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, FIELD,
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        var evidence = new EvidenceSnapshot("legacy-binding-test", frame, transform,
            EvidenceResolution.nativeSource(1, 1), MetricRegion.rectangle(0, 0, 39, 39),
            MetricRegion.rectangle(0, 0, 39, 39), Map.of(FIELD, field), "test");
        List<DetachedProfileSamplingLocation> locations = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        for (int index = 0; index < 19; index++) {
            distances.add((double) index);
            MetricPoint point = transform.pixelCenterToMetric(5 + index, 20);
            locations.add(DetachedProfileSamplingLocation.at(frame.toGeographic(point), frame, transform, index));
        }
        var chainage = new ProfileChainage(distances, 18);
        return new Fixture(evidence, new CorridorTraceInput(locations, 1), chainage);
    }
}
