package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Field;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Candidate-specific raster proof for a real RMS-only weak core. */
class V022RmsSuppressedRasterProofTest {
    private static final double NATIVE_PITCH = 1.0;

    @Test
    void actualImpulseWeakCoreGetsIndependentProofOnBothPhysicalLegs() {
        Fixture fixture = fixture(false, false);
        var left = fixture.profiles().get(0);
        var candidate = fixture.profiles().get(1);
        var right = fixture.profiles().get(2);
        var suppression = fixture.suppression();
        var candidateMode = asProfileMode(suppression);
        var leftMode = coreMode(left);
        var rightMode = coreMode(right);
        var callback = fixture.callback(null);
        MetricPoint leftCenter = modePosition(left, leftMode.coreCenterMeters());
        MetricPoint targetCenter = modePosition(candidate, suppression.mode().centerMeters());
        assertTrue(fixture.evidence().routeSegmentAuthorized(leftCenter, targetCenter));
        assertTrue(ProbabilisticProfileFactory.supportsEveryCrossedInterpolationCell(
                fixture.evidence().fields().get("scalar"), fixture.evidence().transform(),
                leftCenter, targetCenter, CancellationProbe.NONE, ignored -> { }));
        assertTrue(suppression.mode().scalarAmplitudeReliability() > 0.0);
        assertEquals(candidate.samples().stream().map(sample -> sample.offsetMeters()).toList(),
                java.util.stream.IntStream.rangeClosed(-16, 16).mapToObj(index -> index * 0.5).toList());
        assertEquals(candidate.samples().stream().map(sample -> sample.intensity()).toList(),
                java.util.stream.IntStream.rangeClosed(-16, 16).mapToDouble(index ->
                        fixture.evidence().fields().get("scalar").sampleBilinear(60.0, 26.0 + index * 0.5)
                                .orElseThrow()).boxed().toList());
        List<LocalScalarProfileExtractor.Sample> candidateSamples = candidate.samples().stream()
                .map(sample -> new LocalScalarProfileExtractor.Sample(sample.offsetMeters(),
                        sample.intensity(), sample.valid())).toList();
        var localDetail = new LocalScalarProfileExtractor().extractDetailed(candidateSamples,
                candidate.sourcePitchMeters(), EvidenceModelParameters.defaults().localization());
        assertTrue(localDetail.suppressedModes().stream().anyMatch(mode ->
                mode.mode().equals(suppression.mode())), "local detail rows=" + localDetail.suppressedModes());
        assertTrue(localDetail.baseline().modes().stream().noneMatch(mode ->
                mode.distanceToCenterSet(0) <= mode.localizationSigmaMeters()),
                "baseline unexpectedly owns target center: " + localDetail.baseline().modes());

        assertEquals(0.0, callback.support(left, leftMode, candidate, candidateMode,
                CancellationProbe.NONE), 0.0,
                "ordinary strict proof cannot own the rejected weak core");
        double firstLeg = callback.supportRmsSuppressedPair(left, leftMode, candidate,
                candidateMode, candidate.profileIndex(), suppression, CancellationProbe.NONE);
        double secondLeg = callback.supportRmsSuppressedPair(candidate, candidateMode, right,
                rightMode, candidate.profileIndex(), suppression, CancellationProbe.NONE);
        assertTrue(firstLeg > 0.0 && firstLeg <= 1.0, "x=55 to x=60 must be physically measured");
        assertTrue(secondLeg > 0.0 && secondLeg <= 1.0, "x=60 to x=65 must be physically measured");
    }

    @Test
    void candidateShapeAndProfileIndexMustMatchTheRegisteredSuppression() {
        Fixture fixture = fixture(false, false);
        var left = fixture.profiles().get(0);
        var candidate = fixture.profiles().get(1);
        var right = fixture.profiles().get(2);
        var suppression = fixture.suppression();
        var candidateMode = asProfileMode(suppression);
        var leftMode = coreMode(left);
        var rightMode = coreMode(right);
        var callback = fixture.callback(null);

        var forged = new ProbabilisticProfile.Mode(candidateMode.id(), candidateMode.evidenceLineage(),
                candidateMode.coreMinimumMeters(), candidateMode.coreMaximumMeters(),
                candidateMode.localizationSigmaMeters() + 0.01, candidateMode.existenceConfidence(),
                candidateMode.localizationConfidence(), candidateMode.peakOffsetsMeters(),
                candidateMode.nestedCenterOffsetsMeters(), candidateMode.groupedParent(),
                candidateMode.orientationSupport(), candidateMode.scalarAmplitudeReliability(),
                candidateMode.branchCoherence(), candidateMode.positionalReliability(),
                candidateMode.leftDirectSpanMeters(), candidateMode.rightDirectSpanMeters());
        assertEquals(0.0, callback.supportRmsSuppressedPair(left, leftMode, candidate, forged,
                candidate.profileIndex(), suppression, CancellationProbe.NONE), 0.0);
        assertEquals(0.0, callback.supportRmsSuppressedPair(left, leftMode, candidate, candidateMode,
                candidate.profileIndex() + 1, suppression, CancellationProbe.NONE), 0.0);
        assertEquals(0.0, callback.supportRmsSuppressedPair(left, leftMode, right, rightMode,
                candidate.profileIndex(), suppression, CancellationProbe.NONE), 0.0);
    }

    @Test
    void nonzeroProfileFrameCandidateUsesItsActualBridgeCenteredCore() {
        Fixture fixture = fixture(false, false, 25.5);
        var left = fixture.profiles().get(0);
        var candidate = fixture.profiles().get(1);
        var right = fixture.profiles().get(2);
        assertEquals(0.5, fixture.suppression().mode().centerMeters(), 1e-9,
                "the selected candidate is half a metre off the sampled profile origin");
        assertTrue(fixture.suppression().mode().distanceToCenterSet(0.0)
                <= fixture.suppression().mode().localizationSigmaMeters());
        assertFalse(ProbabilisticProfileFactory.RasterIntervalEvidence
                .ownsHighCoreAtZero(fixture.suppression().mode()),
                "sigma proximity alone cannot make this off-core candidate own the query center");
        var candidateMode = asProfileMode(fixture.suppression());
        var callback = fixture.callback(null);
        assertTrue(callback.supportRmsSuppressedPair(left, coreMode(left, 0.5), candidate, candidateMode,
                candidate.profileIndex(), fixture.suppression(), CancellationProbe.NONE) > 0.0);
        assertTrue(callback.supportRmsSuppressedPair(candidate, candidateMode, right, coreMode(right, 0.5),
                candidate.profileIndex(), fixture.suppression(), CancellationProbe.NONE) > 0.0);
    }

    @Test
    void rotatedSourceProfileNormalDoesNotChangeChordLocalPhysicalOwnership() {
        List<MetricPoint> route = List.of(new MetricPoint(55, 26), new MetricPoint(65, 26));
        Fixture fixture = fixtureOnRoute(false, false, route, 0.1);
        var left = fixture.profiles().get(0);
        var candidate = fixture.profiles().get(1);
        var right = fixture.profiles().get(2);
        assertTrue(Math.abs(fixture.suppression().mode().centerMeters()) < 0.1,
                "the candidate remains centered on the same raster ridge");
        assertEquals(0.0, left.normalUnit().xMeters(), 1e-9,
                "source profiles retain their vertical cross-section frame");
        assertTrue(Math.abs(nearestMode(right, 0.5).coreCenterMeters()
                - nearestMode(left, -0.5).coreCenterMeters()) > 0.5,
                "the selected physical centers define a rotated chord");
        var candidateMode = asProfileMode(fixture.suppression());
        var callback = fixture.callback(null);
        assertTrue(callback.supportRmsSuppressedPair(left, nearestMode(left, 0.5), candidate,
                candidateMode, candidate.profileIndex(), fixture.suppression(), CancellationProbe.NONE) > 0.0);
        assertTrue(callback.supportRmsSuppressedPair(candidate, candidateMode, right,
                nearestMode(right, -0.5), candidate.profileIndex(), fixture.suppression(),
                CancellationProbe.NONE) > 0.0);
    }

    @Test
    void blankAndInvalidRasterEvidenceCannotProveEitherPhysicalLeg() {
        Fixture source = fixture(false, false);
        var left = source.profiles().get(0);
        var candidate = source.profiles().get(1);
        var right = source.profiles().get(2);
        var candidateMode = asProfileMode(source.suppression());
        var leftMode = coreMode(left);
        var rightMode = coreMode(right);

        EvidenceSnapshot blankEvidence = scene(true, false);
        var blank = new ProbabilisticProfileFactory.RasterIntervalEvidence(
                blankEvidence, blankEvidence.fields().get("scalar"),
                EvidenceModelParameters.defaults().localization(), null);
        assertEquals(0.0, blank.supportRmsSuppressedPair(left, leftMode, candidate, candidateMode,
                candidate.profileIndex(), source.suppression(), CancellationProbe.NONE), 0.0);

        EvidenceSnapshot invalidEvidence = scene(false, true);
        var invalid = new ProbabilisticProfileFactory.RasterIntervalEvidence(invalidEvidence,
                invalidEvidence.fields().get("scalar"), EvidenceModelParameters.defaults().localization(), null);
        assertEquals(0.0, invalid.supportRmsSuppressedPair(left, leftMode, candidate, candidateMode,
                candidate.profileIndex(), source.suppression(), CancellationProbe.NONE), 0.0);
    }

    @Test
    void interiorHalfPitchValleyRejectsProofWhileBothEndpointRidgesRemainValid() {
        Fixture source = fixture(false, false);
        var left = source.profiles().get(0);
        var candidate = source.profiles().get(1);
        var field = scene(false, false, 0.0, true).fields().get("scalar");
        assertTrue(field.sampleBilinear(55.0, 26.0).orElseThrow() > left.noiseFloor());
        assertTrue(field.sampleBilinear(60.0, 26.0).orElseThrow() > candidate.noiseFloor());
        assertTrue(field.sampleBilinear(58.0, 26.0).orElseThrow() < left.noiseFloor(),
                "the valley is a direct half-pitch query on the physical chord");
        var callback = new ProbabilisticProfileFactory.RasterIntervalEvidence(source.evidence(), field,
                EvidenceModelParameters.defaults().localization(), null);
        assertEquals(0.0, callback.supportRmsSuppressedPair(left, coreMode(left), candidate,
                asProfileMode(source.suppression()), candidate.profileIndex(), source.suppression(),
                CancellationProbe.NONE), 0.0);
    }

    @Test
    void detailedOwnershipRejectsCompetingActualCoresAndCensoredZeroInterval() {
        Fixture fixture = fixture(false, false);
        var candidate = fixture.profiles().get(1);
        List<LocalScalarProfileExtractor.Sample> samples = candidate.samples().stream()
                .map(sample -> new LocalScalarProfileExtractor.Sample(sample.offsetMeters(),
                        sample.intensity(), sample.valid())).toList();
        var extracted = new LocalScalarProfileExtractor().extractDetailed(samples,
                candidate.sourcePitchMeters(), EvidenceModelParameters.defaults().localization());
        var target = fixture.suppression().mode();
        var baseline = extracted.baseline();
        var duplicateOwnerBaseline = new LocalScalarProfileExtractor.Result(baseline.noiseFloor(),
                baseline.maximumIntensity(), List.of(target), baseline.censoredModes());
        var competing = new LocalScalarProfileExtractor.DetailedResult(duplicateOwnerBaseline,
                extracted.suppressedModes(), extracted.rawPeaks());
        assertNull(ProbabilisticProfileFactory.RasterIntervalEvidence.uniqueMeasuredHighCoreAtZero(
                competing, CancellationProbe.NONE, ignored -> { }),
                "the same measured target core present as admitted and RMS-only is two owners");

        var zeroCensor = new LocalScalarProfileExtractor.CensoredMode(
                LocalScalarProfileExtractor.CensorSide.LEFT, -0.25, 1.0, true, true, 0.25);
        var censoredBaseline = new LocalScalarProfileExtractor.Result(baseline.noiseFloor(),
                baseline.maximumIntensity(), List.of(), List.of(zeroCensor));
        var censored = new LocalScalarProfileExtractor.DetailedResult(censoredBaseline,
                extracted.suppressedModes(), extracted.rawPeaks());
        assertNull(ProbabilisticProfileFactory.RasterIntervalEvidence.uniqueMeasuredHighCoreAtZero(
                censored, CancellationProbe.NONE, ignored -> { }),
                "a factual censored interval spanning zero defeats an otherwise owning suppression");
    }

    @Test
    void lateCancellationReleasesAllQueryOwnership() {
        Fixture fixture = fixture(false, false);
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        var candidate = fixture.profiles().get(1);
        assertThrows(CancellationException.class, () -> fixture.callback(owner)
                .supportRmsSuppressedPair(fixture.profiles().get(0), coreMode(fixture.profiles().get(0)),
                        candidate, asProfileMode(fixture.suppression()), candidate.profileIndex(),
                        fixture.suppression(), () -> ledger.peakBytes() > 1000L));
        assertTrue(ledger.peakBytes() > 1000L, "cancel only after query scratch has been admitted");
        assertEquals(88L, ledger.currentBytes(), "failed proof releases queries; one attempt index remains");
        owner.close();
        assertEquals(0L, ledger.currentBytes());
    }

    @Test
    void decisionWindowCensorAndMemoryRefusalCannotLeakOrCertify() {
        Fixture fixture = fixture(false, false);
        var left = fixture.profiles().get(0);
        var candidate = fixture.profiles().get(1);
        var candidateMode = asProfileMode(fixture.suppression());
        var leftMode = coreMode(left);

        EvidenceSnapshot full = fixture.evidence();
        EvidenceSnapshot clipped = new EvidenceSnapshot(full.snapshotId(), full.coordinateFrame(), full.transform(),
                full.resolution(), MetricRegion.rectangle(0, 26, 100, 59), full.evidenceRegion(),
                full.fields(), full.sourceIdentity());
        var censored = new ProbabilisticProfileFactory.RasterIntervalEvidence(clipped,
                clipped.fields().get("scalar"), EvidenceModelParameters.defaults().localization(), null);
        assertEquals(0.0, censored.supportRmsSuppressedPair(left, leftMode, candidate, candidateMode,
                candidate.profileIndex(), fixture.suppression(), CancellationProbe.NONE), 0.0,
                "one-sided query profiles must not own a center");

        AttemptMemoryLedger small = new AttemptMemoryLedger(1024L);
        var owner = small.rootOwner();
        ProbabilisticInference.MemoryLimit refusal = assertThrows(ProbabilisticInference.MemoryLimit.class,
                () -> fixture.callback(owner).supportRmsSuppressedPair(left, leftMode, candidate,
                        candidateMode, candidate.profileIndex(), fixture.suppression(), CancellationProbe.NONE));
        assertTrue(refusal.getCause() instanceof AttemptMemoryLedger.ResourceLimitException);
        assertEquals(88L, small.currentBytes(), "typed refusal retains only its attempt index");
        owner.close();
        assertEquals(0L, small.currentBytes());
    }

    @Test
    void samplingAndFeatureBudgetsStaySpentAcrossSuccessfulLegAndReleaseLateFailure()
            throws ReflectiveOperationException {
        Fixture fixture = fixture(false, false);
        var left = fixture.profiles().get(0);
        var candidate = fixture.profiles().get(1);
        var right = fixture.profiles().get(2);
        var candidateMode = asProfileMode(fixture.suppression());
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();

        var samplingCallback = fixture.callback(owner);
        assertTrue(samplingCallback.supportRmsSuppressedPair(left, coreMode(left), candidate,
                candidateMode, candidate.profileIndex(), fixture.suppression(), CancellationProbe.NONE) > 0.0);
        long samplingBefore = counter(samplingCallback, "samplingUnits");
        long samplingCap = staticCounter("MAXIMUM_SAMPLING_UNITS");
        setCounter(samplingCallback, "samplingUnits", samplingCap - 1L);
        long samplingSeed = counter(samplingCallback, "samplingUnits");
        assertThrows(LongitudinalModeReliability.ResourceLimitException.class,
                () -> samplingCallback.supportRmsSuppressedPair(candidate, candidateMode, right,
                        coreMode(right), candidate.profileIndex(), fixture.suppression(), CancellationProbe.NONE));
        assertTrue(samplingSeed >= samplingBefore);
        assertEquals(samplingSeed, counter(samplingCallback, "samplingUnits"),
                "refused sampling charge leaves the shared production counter monotonic");
        assertEquals(88L, ledger.currentBytes(), "sampling refusal leaves only the previously owned index");
        owner.close();
        assertEquals(0L, ledger.currentBytes());

        AttemptMemoryLedger featureLedger = AttemptMemoryLedger.production();
        var featureOwner = featureLedger.rootOwner();
        var featureCallback = fixture.callback(featureOwner);
        assertTrue(featureCallback.supportRmsSuppressedPair(left, coreMode(left), candidate,
                candidateMode, candidate.profileIndex(), fixture.suppression(), CancellationProbe.NONE) > 0.0);
        long featureBefore = counter(featureCallback, "featureUnits");
        long featureCap = staticCounter("MAXIMUM_FEATURE_UNITS");
        setCounter(featureCallback, "featureUnits", featureCap - 3L);
        long featureSeed = counter(featureCallback, "featureUnits");
        assertThrows(LongitudinalModeReliability.ResourceLimitException.class,
                () -> featureCallback.supportRmsSuppressedPair(candidate, candidateMode, right,
                        coreMode(right), candidate.profileIndex(), fixture.suppression(), CancellationProbe.NONE));
        assertTrue(featureSeed >= featureBefore);
        long featureAfter = counter(featureCallback, "featureUnits");
        assertTrue(featureAfter >= featureSeed && featureAfter <= featureCap,
                "accepted prior charges stay monotonic and the rejected charge cannot exceed the cap");
        assertEquals(88L, featureLedger.currentBytes(), "late feature refusal retains only the attempt index");
        featureOwner.close();
        assertEquals(0L, featureLedger.currentBytes());
    }

    private static ProbabilisticProfile.Mode asProfileMode(
            LocalScalarProfileExtractor.SuppressedMode suppression) {
        var mode = suppression.mode();
        return new ProbabilisticProfile.Mode("rms-candidate", "test-raster",
                mode.coreMinimumMeters(), mode.coreMaximumMeters(), mode.localizationSigmaMeters(),
                mode.existenceConfidence(), mode.localizationConfidence(), mode.peakOffsetsMeters(),
                mode.nestedCenterOffsetsMeters(), false,
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport.unknown(
                        org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport.Status
                                .INSUFFICIENT_TWO_SIDED_SUPPORT),
                mode.scalarAmplitudeReliability(), 0.0, 1.0, 0.0, 0.0);
    }

    private static ProbabilisticProfile.Mode coreMode(ProbabilisticProfile profile) {
        return coreMode(profile, 0.0);
    }

    private static ProbabilisticProfile.Mode coreMode(ProbabilisticProfile profile, double offset) {
        return profile.modes().stream().filter(mode ->
                mode.coreMinimumMeters() <= offset && mode.coreMaximumMeters() >= offset)
                .findFirst().orElseThrow(() -> new AssertionError("no admitted y=26 exterior core: "
                        + profile.modes()));
    }

    private static ProbabilisticProfile.Mode nearestMode(ProbabilisticProfile profile, double offset) {
        return profile.modes().stream().min(java.util.Comparator.comparingDouble(mode ->
                Math.abs(mode.coreCenterMeters() - offset))).orElseThrow();
    }

    private static MetricPoint modePosition(ProbabilisticProfile profile, double offset) {
        return new MetricPoint(profile.anchor().xMeters() + profile.normalUnit().xMeters() * offset,
                profile.anchor().yMeters() + profile.normalUnit().yMeters() * offset);
    }

    private static Fixture fixture(boolean blankStripe, boolean invalidCrossedCell) {
        return fixture(blankStripe, invalidCrossedCell, 26.0);
    }

    private static Fixture fixture(boolean blankStripe, boolean invalidCrossedCell, double routeY) {
        List<MetricPoint> route = List.of(new MetricPoint(55, routeY), new MetricPoint(65, routeY));
        return fixtureOnRoute(blankStripe, invalidCrossedCell, route);
    }

    private static Fixture fixtureOnRoute(boolean blankStripe, boolean invalidCrossedCell,
            List<MetricPoint> route) {
        return fixtureOnRoute(blankStripe, invalidCrossedCell, route, 0.0);
    }

    private static Fixture fixtureOnRoute(boolean blankStripe, boolean invalidCrossedCell,
            List<MetricPoint> route, double signalSlope) {
        EvidenceSnapshot evidence = scene(blankStripe, invalidCrossedCell, signalSlope);
        var profiles = new ProbabilisticProfileFactory().create(route, 5.0, 8.0, false,
                evidence, evidence.fields().get("scalar"), EvidenceModelParameters.defaults(),
                CancellationProbe.NONE, false);
        var candidate = profiles.get(1);
        List<LocalScalarProfileExtractor.Sample> samples = candidate.samples().stream()
                .map(sample -> new LocalScalarProfileExtractor.Sample(sample.offsetMeters(),
                        sample.intensity(), sample.valid())).toList();
        var detailed = new LocalScalarProfileExtractor().extractDetailed(samples,
                candidate.sourcePitchMeters(), EvidenceModelParameters.defaults().localization());
        double expectedPeakOffset = (26.0 - candidate.anchor().yMeters())
                * candidate.normalUnit().yMeters();
        var suppression = detailed.suppressedModes().stream().filter(mode ->
                Math.abs(mode.rawPeakOffsetMeters() - expectedPeakOffset) < 0.1).findFirst().orElseThrow(() ->
                    new AssertionError("actual x=60 profile did not emit the weak RMS-only candidate: "
                            + detailed));
        return new Fixture(evidence, profiles, suppression);
    }

    private static EvidenceSnapshot scene(boolean blankStripe, boolean invalidCrossedCell) {
        return scene(blankStripe, invalidCrossedCell, 0.0);
    }

    private static EvidenceSnapshot scene(boolean blankStripe, boolean invalidCrossedCell, double signalSlope) {
        return scene(blankStripe, invalidCrossedCell, signalSlope, false);
    }

    private static EvidenceSnapshot scene(boolean blankStripe, boolean invalidCrossedCell,
            double signalSlope, boolean interiorValley) {
        int width = 101;
        int height = 60;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                double centerY = 26.0 + signalSlope * (x - 60.0);
                double residual = (y - centerY) / 0.9;
                values[index] = blankStripe && x >= 58 && x <= 60 ? 0.02
                        : interiorValley && x == 58 && y == 26 ? 0.0
                        : Math.min(1.0, 0.02 + 0.08 * StrictMath.exp(-0.5 * residual * residual)
                                + (x == 60 && y == 28 ? 0.9 : 0.0));
            }
        }
        if (invalidCrossedCell) valid[26 * width + 58] = false;
        var lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "rms-pair-fixture",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        var field = new ScalarEvidenceField(width, height, values, valid, lineage);
        var frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
                new GeographicPoint(-1, -1), new GeographicPoint(1, 1));
        var transform = new RasterMetricTransform("rms-pair-fixture",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(0, 0), NATIVE_PITCH, 0, 0, NATIVE_PITCH, 1);
        var region = MetricRegion.rectangle(0, 0, 100, 59);
        return new EvidenceSnapshot("rms-pair-fixture", frame, transform,
                EvidenceResolution.nativeSource(NATIVE_PITCH, NATIVE_PITCH), region, region,
                Map.of("scalar", field), "rms-pair-fixture");
    }

    private record Fixture(EvidenceSnapshot evidence, List<ProbabilisticProfile> profiles,
            LocalScalarProfileExtractor.SuppressedMode suppression) {
        ProbabilisticProfileFactory.RasterIntervalEvidence callback(AttemptMemoryLedger.Owner owner) {
            return new ProbabilisticProfileFactory.RasterIntervalEvidence(evidence,
                    evidence.fields().get("scalar"), EvidenceModelParameters.defaults().localization(), owner);
        }
    }

    private static long counter(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(target);
    }

    private static void setCounter(Object target, String name, long value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(target, value);
    }

    private static long staticCounter(String name) throws ReflectiveOperationException {
        Field field = ProbabilisticProfileFactory.RasterIntervalEvidence.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(null);
    }
}
