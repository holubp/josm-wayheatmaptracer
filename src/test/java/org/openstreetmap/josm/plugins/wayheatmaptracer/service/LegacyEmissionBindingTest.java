package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.Point2D;
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
    void r4DeclaredSyntheticBindingMustRetainOriginalNativeProfilesAndPeakObjects() {
        var source = PhysicalGeometryFixture.r4SyntheticFrame(4.0);
        var nativeRows = source.profiles().subList(1, 18);
        var crop = r4Crop(source);
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(crop.evidence(), FIELD,
                crop.input(), crop.chainage(), 25, budget, owner)) {
            RetainedScalarProfiles retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(
                crop.evidence(), FIELD, crop.input().profileLocations(), 25, 1, 1, budget, permit);
            var mapping = java.util.stream.IntStream.range(1, 18).boxed().toList();
            var truth = new OptionalEmissionContextFactory.DeclaredSyntheticRasterTruth(source.fullScalarGrid(),
                18, 17, 1.0, 0.0, List.of());
            var binding = OptionalEmissionContextFactory.createDeclaredSyntheticR4Binding(permit, retained,
                nativeRows, mapping, 1.0, truth);
            for (int row = 0; row < nativeRows.size(); row++) {
                var observation = binding.observations().get(row);
                assertSame(nativeRows.get(row), observation.profile(),
                    "R4 native row " + row + " must keep its original Legacy profile object");
                assertSame(nativeRows.get(row).peaks().get(0), observation.producerPeaks().get(0).peak(),
                    "R4 native row " + row + " must keep the original Legacy peak object");
                assertSame(retained.profiles().levelZeroProfiles().get(row + 1), observation.measurementProfile());
                assertSame(retained.profiles().levelZeroProfiles().get(row + 1).peaks().get(0),
                    observation.producerPeaks().get(0).measurementPeakOwner());
                assertEquals(0, observation.producerPeaks().get(0).originalPeakId());
                assertEquals(1, observation.producerPeaks().size());
                assertEquals(NativeConnectorEvaluator.SourceClass.OBSERVED, observation.sourceClass());
            }
            assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17),
                binding.originalRowToRetainedObservation());
            assertEquals(1.0, binding.trackerNormalizationRasterPx());
            assertFalse(binding.productionEligible());
            var tracker = new RidgeTracker();
            try {
                var suppression = RidgeTracker.class.getDeclaredMethod("suppressLongitudinalNoise", List.class);
                suppression.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<RenderedHeatmapSampler.CrossSectionProfile> postSuppression =
                    (List<RenderedHeatmapSampler.CrossSectionProfile>) suppression.invoke(tracker, nativeRows);
                var seedCollection = RidgeTracker.class.getDeclaredMethod("collectSeedOffsets", List.class);
                seedCollection.setAccessible(true);
                assertEquals(List.of(12.0, 8.0, 4.0), seedCollection.invoke(tracker, postSuppression));
                for (int row = 0; row < nativeRows.size(); row++) {
                    assertSame(nativeRows.get(row).peaks().get(0), postSuppression.get(row).peaks().get(0));
                }
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("R4 fixture must retain the unchanged Legacy suppression/seed behavior", failure);
            }
            try (var sampled = OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit, retained,
                    binding)) {
                assertSame(binding, sampled.legacyBinding().orElseThrow());
                sampled.requireOpenAndUnchanged();
                var normal = nativeRows.get(0).normalScreen();
                double originalX = normal.x;
                normal.x += 0.125;
                assertThrows(IllegalStateException.class, sampled::requireOpenAndUnchanged,
                    "Native profile mutation after publication must invalidate the input");
                normal.x = originalX;
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void declaredR4BindingRejectsChangedRasterNodeAndDuplicateNativePeakClaim() {
        var source = PhysicalGeometryFixture.r4SyntheticFrame(4.0);
        var nativeRows = source.profiles().subList(1, 18);
        var crop = r4Crop(source);
        var ledger = AttemptMemoryLedger.production();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try (var owner = ledger.rootOwner();
             var permit = OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(crop.evidence(), FIELD,
                crop.input(), crop.chainage(), 25, budget, owner)) {
            RetainedScalarProfiles retained = new RenderedHeatmapSampler().sampleDetachedRetainingL0(
                crop.evidence(), FIELD, crop.input().profileLocations(), 25, 1, 1, budget, permit);
            var mapping = java.util.stream.IntStream.range(1, 18).boxed().toList();

            double[] changedValues = source.fullScalarGrid().copiedValues();
            changedValues[64 * 128 + 64] -= 1.0 / 255.0;
            var changedGrid = new ScalarEvidenceField(128, 128, changedValues,
                source.fullScalarGrid().copiedValidity(), source.fullScalarGrid().lineage());
            var changedTruth = new OptionalEmissionContextFactory.DeclaredSyntheticRasterTruth(changedGrid,
                18, 17, 1.0, 0.0, List.of());
            assertThrows(IllegalArgumentException.class, () ->
                OptionalEmissionContextFactory.createDeclaredSyntheticR4Binding(permit, retained, nativeRows,
                    mapping, 1.0, changedTruth),
                "An altered source node must fail even if the producer peak offset could still match");

            var duplicated = new ArrayList<>(nativeRows);
            var first = nativeRows.get(0);
            var peak = first.peaks().get(0);
            duplicated.set(0, new RenderedHeatmapSampler.CrossSectionProfile(first.samplingAnchor(),
                new Point2D.Double(first.normalScreen().x, first.normalScreen().y), List.of(peak, peak),
                first.anchorWithinRaster(), first.intensitySamples(), first.projectedLateralTransform()));
            var truth = new OptionalEmissionContextFactory.DeclaredSyntheticRasterTruth(source.fullScalarGrid(),
                18, 17, 1.0, 0.0, List.of());
            assertEquals(1.556, PhysicalGeometryFixture.NATIVE_PITCH,
                "The fixture's declared source pitch remains distinct from its Legacy normalization of 1px");
            assertThrows(IllegalArgumentException.class, () ->
                OptionalEmissionContextFactory.createDeclaredSyntheticR4Binding(permit, retained, nativeRows,
                    java.util.stream.IntStream.range(2, 19).boxed().toList(), 1.0, truth),
                "The R4 ordinal-to-crop mapping must preserve the padded 19-row context indices");
            var wrongPitch = new OptionalEmissionContextFactory.DeclaredSyntheticRasterTruth(source.fullScalarGrid(),
                18, 17, PhysicalGeometryFixture.NATIVE_PITCH, 0.0, List.of());
            assertThrows(IllegalArgumentException.class, () ->
                OptionalEmissionContextFactory.createDeclaredSyntheticR4Binding(permit, retained, nativeRows,
                    mapping, 1.0, wrongPitch),
                "Native source pitch must not replace the actual unit tracker normalization");
            assertThrows(IllegalArgumentException.class, () ->
                OptionalEmissionContextFactory.createDeclaredSyntheticR4Binding(permit, retained, duplicated,
                    mapping, 1.0, truth),
                "A duplicate native peak claim must not be assigned one retained band owner twice");
        }
        assertEquals(0, ledger.currentBytes());
    }

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

    record R4Crop(EvidenceSnapshot evidence, CorridorTraceInput input, ProfileChainage chainage) { }

    static R4Crop r4Crop(PhysicalGeometryFixture.R4SyntheticFrame source) {
        int minX = 18, minY = 17, width = 93, height = 86, fullWidth = 128;
        double[] original = source.fullScalarGrid().copiedValues();
        boolean[] originalValid = source.fullScalarGrid().copiedValidity();
        double[] cropped = new double[width * height];
        boolean[] valid = new boolean[cropped.length];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int fullIndex = (minY + y) * fullWidth + minX + x;
            int cropIndex = y * width + x;
            cropped[cropIndex] = original[fullIndex];
            valid[cropIndex] = originalValid[fullIndex];
        }
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(50, 14),
            new GeographicPoint(49.99, 13.99), new GeographicPoint(50.01, 14.02));
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(minX, minY), 1, 0, 0, 1);
        var lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, FIELD,
            EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        var field = new ScalarEvidenceField(width, height, cropped, valid, lineage);
        var bounds = MetricRegion.rectangle(minX, minY, minX + width - 1, minY + height - 1);
        var evidence = new EvidenceSnapshot("legacy-r4-crop", frame, transform,
            EvidenceResolution.nativeSource(1, 1), bounds, bounds, Map.of(FIELD, field), "R4 synthetic crop");
        List<DetachedProfileSamplingLocation> locations = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        double cumulative = 0.0;
        Point2D.Double previous = null;
        for (Point2D.Double anchor : source.anchors()) {
            if (previous != null) cumulative += previous.distance(anchor);
            distances.add(cumulative);
            RasterPoint local = new RasterPoint(anchor.x - minX, anchor.y - minY);
            MetricPoint point = transform.pixelCenterToMetric(local.x(), local.y());
            locations.add(new DetachedProfileSamplingLocation(frame.toGeographic(point), point, local,
                frame, transform, cumulative));
            previous = anchor;
        }
        var chainage = new ProfileChainage(distances, cumulative);
        return new R4Crop(evidence, new CorridorTraceInput(locations, 1), chainage);
    }

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
