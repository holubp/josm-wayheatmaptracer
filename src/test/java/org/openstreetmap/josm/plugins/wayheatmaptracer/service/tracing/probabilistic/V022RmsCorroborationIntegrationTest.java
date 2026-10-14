package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleBinaryOperator;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.ImageOrientationDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

/** Factory integration: eligibility alone cannot manufacture a direct observation. */
class V022RmsCorroborationIntegrationTest {
    private static final List<MetricPoint> ROUTE = List.of(new MetricPoint(55, 26), new MetricPoint(65, 26));

    @Test
    void actualFactoryRestoresOnlySingletonWithOwnMeasuredOrientationAndAmplitude() {
        var evidence = impulse(false, false, false);
        var original = profiles(evidence, ROUTE, 5, 8, false, false);
        var detail = detail(original.get(1));
        var suppression = detail.suppressedModes().stream()
                .filter(value -> Math.abs(value.mode().centerMeters()) < .1).findFirst().orElseThrow();
        assertTrue(original.get(1).modes().stream().noneMatch(mode -> Math.abs(mode.coreCenterMeters()) < .5));
        var actual = profiles(evidence, ROUTE, 5, 8, false, true);
        var restored = actual.get(1).modes().stream().filter(V022RmsCorroborationIntegrationTest::restored)
                .findFirst().orElseThrow(() -> new AssertionError("factory has no independently corroborated weak core"));
        assertEquals(1, actual.stream().flatMap(profile -> profile.modes().stream()).filter(
                V022RmsCorroborationIntegrationTest::restored).count());
        assertEquals(0, restored.coreCenterMeters(), 1e-12);
        assertEquals(suppression.mode().scalarAmplitudeReliability(), restored.scalarAmplitudeReliability(), 0);
        var measured = new ImageOrientationDescriptor().describe(evidence, evidence.fields().get("scalar"),
                new MetricPoint(60, 26), 1, EvidenceModelParameters.defaults(), CancellationProbe.NONE).support();
        assertEquals(measured, restored.orientationSupport(), "restored shape must measure its own field direction");
        assertEquals(ImageOrientationSupport.Status.MEASURED_TWO_SIDED, restored.orientationSupport().status());
        assertNotSame(actual.get(0).modes().get(0).orientationSupport(), restored.orientationSupport());
        assertNotEquals(actual.get(0).modes().get(0).scalarAmplitudeReliability(), restored.scalarAmplitudeReliability());
        assertTrue(restored.leftDirectSpanMeters() >= 5 && restored.rightDirectSpanMeters() >= 5,
                "both exact proved legs must reach ordinary direct reliability");
        for (int index = 0; index < original.size(); index++) {
            var baseline = original.get(index).modes();
            for (int mode = 0; mode < baseline.size(); mode++) {
                var before = baseline.get(mode);
                var after = actual.get(index).modes().get(mode);
                assertEquals(before.id(), after.id());
                assertEquals(before.coreMinimumMeters(), after.coreMinimumMeters(), 0);
                assertEquals(before.coreMaximumMeters(), after.coreMaximumMeters(), 0);
                assertEquals(before.scalarAmplitudeReliability(), after.scalarAmplitudeReliability(), 0);
                assertEquals(before.orientationSupport(), after.orientationSupport());
            }
        }
        assertEquals(actual, profiles(evidence, ROUTE, 5, 8, false, true), "deterministic identities and ordering");
    }

    @Test
    void blankInvalidOrValleyLegCannotRestoreAnOtherwiseEligibleShape() {
        for (var evidence : List.of(impulse(true, false, false), impulse(false, true, false),
                impulse(false, false, true))) {
            assertNoRestoration(profiles(evidence, ROUTE, 5, 8, false, true));
        }
    }

    @Test
    void fixedSuppressedEndpointAndUnknownNativePitchCannotRestore() {
        var evidence = impulse(false, false, false);
        assertNoRestoration(profiles(evidence, List.of(new MetricPoint(55, 26), new MetricPoint(60, 26)),
                5, 8, true, true));
        var unknown = new EvidenceSnapshot(evidence.snapshotId(), evidence.coordinateFrame(), evidence.transform(),
                EvidenceResolution.renderedOnly(1), evidence.decisionRegion(), evidence.evidenceRegion(),
                evidence.fields(), evidence.sourceIdentity());
        assertNoRestoration(profiles(unknown, ROUTE, 5, 8, false, true));
    }

    @Test
    void fullFiftyOneProfileSparseCensusAndEveryInteriorRemainUnpromoted() {
        var evidence = scene((x, y) -> {
            double result = .02;
            for (int strand = 0; strand < 3; strand++) {
                double phase = 2 * Math.PI * (x - 10) / 24 + strand * 2 * Math.PI / 3;
                result += .06 * gaussian(y - 30 - 2.5 * Math.sin(phase), .65);
            }
            return result;
        }, false);
        var route = List.of(new MetricPoint(10, 30), new MetricPoint(110, 30));
        var baseline = profiles(evidence, route, 2, 7.01, false, false);
        var actual = profiles(evidence, route, 2, 7.01, false, true);
        assertEquals(51, actual.size());
        for (int index = 0; index < 51; index++) {
            assertEquals(baseline.get(index).modes().stream().map(ProbabilisticProfile.Mode::id).toList(),
                    actual.get(index).modes().stream().map(ProbabilisticProfile.Mode::id).toList(), "row=" + index);
            assertTrue(detail(baseline.get(index)).rawPeaks().size() >= 2, "raw census row=" + index);
        }
        assertNoRestoration(actual);
    }

    @Test
    void alternatingPhasesAndEqualParallelModesRemainUnpromoted() {
        for (double phase : List.of(0.0, .25, .5)) {
            var evidence = scene((x, y) -> ((int) y % 2 == 0 ? .42 : .40), false);
            assertNoRestoration(profiles(evidence, List.of(new MetricPoint(55, 26 + phase),
                    new MetricPoint(65, 26 + phase)), 5, 8, false, true));
        }
        var parallel = scene((x, y) -> .02 + .08 * gaussian(y - 26, .9) + .08 * gaussian(y - 32, .9), false);
        var baseline = profiles(parallel, ROUTE, 5, 8, false, false);
        var actual = profiles(parallel, ROUTE, 5, 8, false, true);
        assertNoRestoration(actual);
        for (int index = 0; index < actual.size(); index++) assertEquals(
                baseline.get(index).modes().stream().map(ProbabilisticProfile.Mode::id).toList(),
                actual.get(index).modes().stream().map(ProbabilisticProfile.Mode::id).toList());
    }

    @Test
    void adjacentActualSuppressedRowsCannotSupplyTransitiveExteriorEvidence() {
        var evidence = scene((x, y) -> Math.min(1, .02 + .08 * gaussian(y - 26, .9)
                + ((x == 60 || x == 65) && y == 28 ? .9 : 0)), false);
        var route = List.of(new MetricPoint(55, 26), new MetricPoint(70, 26));
        var original = profiles(evidence, route, 5, 8, false, false);
        for (int index : List.of(1, 2)) assertTrue(detail(original.get(index)).suppressedModes().stream()
                .anyMatch(value -> Math.abs(value.mode().centerMeters()) < .1));
        assertNoRestoration(profiles(evidence, route, 5, 8, false, true));
    }

    @Test
    void capturedHaloCannotRestoreAnOriginallyCensoredCore() {
        var source = impulse(false, false, false);
        var clipped = new EvidenceSnapshot(source.snapshotId(), source.coordinateFrame(), source.transform(),
                source.resolution(), MetricRegion.rectangle(0, 26, 120, 60), source.evidenceRegion(),
                source.fields(), source.sourceIdentity());
        var original = profiles(clipped, ROUTE, 5, 8, false, false);
        assertTrue(original.get(0).censoredModes().size() > 0, "decision edge actually censors the exterior core");
        assertNoRestoration(profiles(clipped, ROUTE, 5, 8, false, true));
    }

    @Test
    void productionOwnedFactoryRetainsOutputGraphsAndReleasesAllOnClose() {
        var evidence = impulse(false, false, false);
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        var factory = new ProbabilisticProfileFactory();
        var actual = factory.create(ROUTE, factory.profileChainage(ROUTE, 5), 8, false, evidence,
                evidence.fields().get("scalar"), EvidenceModelParameters.defaults(), CancellationProbe.NONE, true, owner);
        var restored = actual.get(1).modes().stream().filter(V022RmsCorroborationIntegrationTest::restored)
                .findFirst().orElseThrow();
        assertTrue(ledger.currentBytes() > 0);
        assertEquals(ImageOrientationSupport.Status.MEASURED_TWO_SIDED, restored.orientationSupport().status());
        owner.close();
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void physicalNativePitchControlsRestorationAndOwnDescriptorInScaledSource() {
        var original = impulse(false, false, false);
        var transform = new RasterMetricTransform("scaled-native",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER, new MetricPoint(0, 0), 2, 0, 0, 2, 1);
        var region = MetricRegion.rectangle(0, 0, 240, 120);
        var evidence = new EvidenceSnapshot("scaled-native", original.coordinateFrame(), transform,
                EvidenceResolution.nativeSource(2, 2), region, region, original.fields(), "scaled-native");
        var actual = profiles(evidence, List.of(new MetricPoint(110, 52), new MetricPoint(130, 52)), 10, 16, false, true);
        var restored = actual.get(1).modes().stream().filter(V022RmsCorroborationIntegrationTest::restored).findFirst().orElseThrow();
        assertEquals(2, actual.get(1).sourcePitchMeters(), 0);
        assertEquals(-.5, restored.coreMinimumMeters(), 0);
        assertEquals(.5, restored.coreMaximumMeters(), 0);
        assertEquals(new ImageOrientationDescriptor().describe(evidence, evidence.fields().get("scalar"),
                new MetricPoint(120, 52), 2, EvidenceModelParameters.defaults(), CancellationProbe.NONE).support(),
                restored.orientationSupport());
        assertTrue(restored.leftDirectSpanMeters() >= 10 && restored.rightDirectSpanMeters() >= 10);
    }

    @Test
    void cachedProofRequiresExactFinalProfileAndModeDependencies() throws Exception {
        var evidence = impulse(false, false, false);
        var original = profiles(evidence, ROUTE, 5, 8, false, false);
        var suppression = detail(original.get(1)).suppressedModes().get(0);
        var actual = profiles(evidence, ROUTE, 5, 8, false, true);
        var restored = actual.get(1).modes().stream().filter(V022RmsCorroborationIntegrationTest::restored).findFirst().orElseThrow();
        var candidate = copyProfile(original.get(1), actual.get(1).modes());
        var left = original.get(0);
        var leftMode = left.modes().get(0);
        var callback = new ProbabilisticProfileFactory.RasterIntervalEvidence(evidence,
                evidence.fields().get("scalar"), EvidenceModelParameters.defaults().localization(), null);
        double factor = callback.supportRmsSuppressedPair(left, leftMode, original.get(1), restored,
                original.get(1).profileIndex(), suppression, CancellationProbe.NONE);
        assertTrue(factor > 0);
        var receipt = new ProbabilisticProfileFactory.RmsLegReceipt(left, leftMode, candidate, restored,
                left, original.get(1), suppression, factor);
        var cached = new ProbabilisticProfileFactory.CertifiedRmsIntervalEvidence(callback, List.of(receipt));
        long queries = counter(callback, "queries");
        assertEquals(factor, cached.support(left, leftMode, candidate, restored, CancellationProbe.NONE), 0);
        assertEquals(queries, counter(callback, "queries"), "exact immutable receipt is reused");
        var equalClone = copyProfile(candidate, candidate.modes());
        assertNotSame(candidate, equalClone);
        assertEquals(candidate, equalClone);
        assertEquals(0, cached.support(left, leftMode, equalClone, restored, CancellationProbe.NONE), 0,
                "equal profile values or IDs cannot transfer measured support to another dependency");
        var equalMode = copyMode(restored, restored.orientationSupport());
        assertNotSame(restored, equalMode);
        assertEquals(restored, equalMode);
        assertEquals(0, cached.support(left, leftMode, candidate, equalMode, CancellationProbe.NONE), 0);
        assertEquals(0, cached.support(candidate, restored, left, leftMode, CancellationProbe.NONE), 0);
        var duplicate = new ProbabilisticProfileFactory.CertifiedRmsIntervalEvidence(callback, List.of(receipt, receipt));
        assertEquals(0, duplicate.support(left, leftMode, candidate, restored, CancellationProbe.NONE), 0);

        // Even a receipt key cannot supply direction or bypass the actual association gate.
        var unmeasured = copyMode(restored, ImageOrientationSupport.unknown(
                ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT));
        var unmatched = copyProfile(original.get(1), List.of(unmeasured));
        var unused = new ProbabilisticProfileFactory.CertifiedRmsIntervalEvidence(callback, List.of(
                new ProbabilisticProfileFactory.RmsLegReceipt(left, leftMode, unmatched, unmeasured,
                        left, original.get(1), suppression, factor)));
        var reliable = LongitudinalModeReliability.apply(List.of(left, unmatched, original.get(2)),
                unused, CancellationProbe.NONE).profiles().get(1).modes().get(0);
        assertEquals(0, reliable.leftDirectSpanMeters(), 0);
        assertEquals(0, reliable.rightDirectSpanMeters(), 0);
        assertEquals(0, reliable.branchCoherence(), 0);
    }

    @Test
    void cumulativePrepassAndOrientationCapsRefuseWithoutReleasingBorrowedInputs() throws Exception {
        for (String capped : List.of("featureUnits", "samplingUnits")) {
            var evidence = impulse(false, false, false);
            var ledger = AttemptMemoryLedger.production();
            var owner = ledger.rootOwner();
            var inputOwner = owner.child("original-inputs");
            var factory = new ProbabilisticProfileFactory();
            var originals = factory.create(ROUTE, factory.profileChainage(ROUTE, 5), 8, false, evidence,
                    evidence.fields().get("scalar"), EvidenceModelParameters.defaults(), CancellationProbe.NONE, false, inputOwner);
            var details = originals.stream().map(profile -> new LocalScalarProfileExtractor().extractDetailed(
                    profile.samples().stream().map(sample -> new LocalScalarProfileExtractor.Sample(
                        sample.offsetMeters(), sample.intensity(), sample.valid())).toList(), profile.sourcePitchMeters(),
                    EvidenceModelParameters.defaults().localization(), inputOwner, CancellationProbe.NONE, ignored -> { })).toList();
            long borrowedBytes = ledger.currentBytes();
            var workOwner = owner.child("new-work");
            var outputOwner = owner.child("new-output");
            var identityOwner = workOwner.child("new-identities");
            var callback = new ProbabilisticProfileFactory.RasterIntervalEvidence(evidence,
                    evidence.fields().get("scalar"), EvidenceModelParameters.defaults().localization(), workOwner);
            setCounter(callback, capped, (capped.equals("featureUnits") ? 8_000_000L : 2_000_000L) - 1);
            try {
                assertThrows(LongitudinalModeReliability.ResourceLimitException.class, () -> invokeRestoration(
                        originals, details, evidence, callback, workOwner, outputOwner, identityOwner));
            } finally {
                workOwner.close(); outputOwner.close();
            }
            assertEquals(borrowedBytes, ledger.currentBytes(), "failed new work cannot retain or release borrowed input graphs");
            assertEquals(0, counter(callback, "queries"), "cap admission precedes candidate scalar proof");
            assertTrue(counter(callback, "featureUnits") > 0, "prepass shares this exact cumulative RIE budget");
            owner.close(); assertEquals(0, ledger.currentBytes());
        }
    }

    @Test
    void factoryLateOrientationCancellationAndMemoryRefusalLeaveNoPartialOutput() {
        for (boolean memory : List.of(false, true)) {
            var evidence = impulse(false, false, false);
            var ledger = AttemptMemoryLedger.production();
            var owner = ledger.rootOwner();
            ModernDiagnosticCounters.begin();
            try {
                CancellationProbe cancellation = () -> {
                    if (ModernDiagnosticCounters.snapshot().getOrDefault("reliability.rmsProposals", 0).longValue() > 0
                            && Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame ->
                                frame.getClassName().equals(ImageOrientationDescriptor.class.getName()))) {
                        if (memory) owner.reserve(AttemptMemoryLedger.MAX_BYTES);
                        return true;
                    }
                    return false;
                };
                var factory = new ProbabilisticProfileFactory();
                if (memory) assertThrows(AttemptMemoryLedger.ResourceLimitException.class, () -> factory.create(
                        ROUTE, factory.profileChainage(ROUTE, 5), 8, false, evidence, evidence.fields().get("scalar"),
                        EvidenceModelParameters.defaults(), cancellation, true, owner));
                else assertThrows(CancellationException.class, () -> factory.create(ROUTE,
                        factory.profileChainage(ROUTE, 5), 8, false, evidence, evidence.fields().get("scalar"),
                        EvidenceModelParameters.defaults(), cancellation, true, owner));
                assertTrue(ledger.peakBytes() > 0);
                assertEquals(0, ledger.currentBytes(), "factory releases raw, detailed, proposal and candidate owners before abort returns");
            } finally { ModernDiagnosticCounters.end(); owner.close(); }
        }
    }

    private static long counter(Object callback, String name) throws Exception {
        var field = callback.getClass().getDeclaredField(name); field.setAccessible(true); return field.getLong(callback);
    }
    private static void setCounter(Object callback, String name, long value) throws Exception {
        var field = callback.getClass().getDeclaredField(name); field.setAccessible(true); field.setLong(callback, value);
    }
    private static void invokeRestoration(List<ProbabilisticProfile> originals,
            List<LocalScalarProfileExtractor.DetailedResult> details, EvidenceSnapshot evidence,
            ProbabilisticProfileFactory.RasterIntervalEvidence callback, AttemptMemoryLedger.Owner workOwner,
            AttemptMemoryLedger.Owner outputOwner, AttemptMemoryLedger.Owner identityOwner) throws Exception {
        var method = ProbabilisticProfileFactory.class.getDeclaredMethod("restoreRmsSingletons", List.class, List.class,
                EvidenceSnapshot.class, ScalarEvidenceField.class, EvidenceModelParameters.class, CancellationProbe.class,
                ImageOrientationDescriptor.class, ProbabilisticProfileFactory.RasterIntervalEvidence.class,
                AttemptMemoryLedger.Owner.class, AttemptMemoryLedger.Owner.class, AttemptMemoryLedger.Owner.class);
        method.setAccessible(true);
        try { method.invoke(null, originals, details, evidence, evidence.fields().get("scalar"),
                EvidenceModelParameters.defaults(), CancellationProbe.NONE, new ImageOrientationDescriptor(),
                callback, workOwner, outputOwner, identityOwner); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }
    private static ProbabilisticProfile copyProfile(ProbabilisticProfile profile, List<ProbabilisticProfile.Mode> modes) {
        return new ProbabilisticProfile(profile.profileIndex(), profile.chainageMeters(), profile.anchor(), profile.normalUnit(),
                profile.minimumOffsetMeters(), profile.maximumOffsetMeters(), profile.sourcePitchMeters(), profile.nativePitchKnown(),
                profile.noiseFloor(), profile.samples(), modes, profile.censoredModes(), profile.exactAnchorOffsetMeters(), profile.orientationSupport());
    }
    private static ProbabilisticProfile.Mode copyMode(ProbabilisticProfile.Mode mode, ImageOrientationSupport orientation) {
        return new ProbabilisticProfile.Mode(mode.id(), mode.evidenceLineage(), mode.coreMinimumMeters(), mode.coreMaximumMeters(),
                mode.localizationSigmaMeters(), mode.existenceConfidence(), mode.localizationConfidence(), mode.peakOffsetsMeters(),
                mode.nestedCenterOffsetsMeters(), mode.groupedParent(), orientation, mode.scalarAmplitudeReliability(), mode.branchCoherence(),
                mode.positionalReliability(), mode.leftDirectSpanMeters(), mode.rightDirectSpanMeters());
    }

    private static boolean restored(ProbabilisticProfile.Mode mode) { return mode.id().contains("-rms-"); }
    private static void assertNoRestoration(List<ProbabilisticProfile> profiles) {
        assertFalse(profiles.stream().flatMap(profile -> profile.modes().stream()).anyMatch(
                V022RmsCorroborationIntegrationTest::restored));
    }
    private static LocalScalarProfileExtractor.DetailedResult detail(ProbabilisticProfile profile) {
        return new LocalScalarProfileExtractor().extractDetailed(profile.samples().stream().map(sample ->
                new LocalScalarProfileExtractor.Sample(sample.offsetMeters(), sample.intensity(), sample.valid())).toList(),
                profile.sourcePitchMeters(), EvidenceModelParameters.defaults().localization());
    }
    private static List<ProbabilisticProfile> profiles(EvidenceSnapshot evidence, List<MetricPoint> route,
            double step, double halfWidth, boolean fixed, boolean direct) {
        return new ProbabilisticProfileFactory().create(route, step, halfWidth, fixed, evidence,
                evidence.fields().get("scalar"), EvidenceModelParameters.defaults(), CancellationProbe.NONE, direct);
    }
    private static EvidenceSnapshot impulse(boolean blank, boolean invalid, boolean valley) {
        return scene((x, y) -> blank && x >= 58 && x <= 60 ? .02
                : valley && x == 58 && y == 26 ? 0
                : Math.min(1, .02 + .08 * gaussian(y - 26, .9) + (x == 60 && y == 28 ? .9 : 0)), invalid);
    }
    private static EvidenceSnapshot scene(DoubleBinaryOperator intensity, boolean invalid) {
        int width = 121, height = 61;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) values[y * width + x] = intensity.applyAsDouble(x, y);
        if (invalid) valid[26 * width + 58] = false;
        var lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "rms-integration",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        var field = new ScalarEvidenceField(width, height, values, valid, lineage);
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
                new GeographicPoint(-1, -1), new GeographicPoint(1, 1));
        var transform = new RasterMetricTransform("rms-integration",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER, new MetricPoint(0, 0), 1, 0, 0, 1, 1);
        var region = MetricRegion.rectangle(0, 0, 120, 60);
        return new EvidenceSnapshot("rms-integration", frame, transform, EvidenceResolution.nativeSource(1, 1),
                region, region, Map.of("scalar", field), "rms-integration");
    }
    private static double gaussian(double residual, double sigma) {
        return StrictMath.exp(-.5 * residual * residual / (sigma * sigma));
    }
}
