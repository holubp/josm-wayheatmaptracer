package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Full-factory controls distinguish scalar presence from measured lateral continuation. */
class V022LocalizedBridgeReliabilityTest {
    private static final double PITCH = 0.25;
    private static final List<MetricPoint> SOURCE = List.of(new MetricPoint(0, 0), new MetricPoint(64, 0));

    @Test
    void nonlocalizedPositiveBridgeCannotCorroborateEndpointModes() {
        EvidenceSnapshot narrow = scene(1.0, 0.12);
        EvidenceSnapshot diffuse = scene(0.0, 0.12);
        assertEquals(profiles(narrow, false), profiles(diffuse, false),
                "only the interior bridge changes; frozen endpoint scalar modes/orientations must match");
        ImageCostField.RouteSample measured = sample(narrow);
        ImageCostField.RouteSample flat = sample(diffuse);
        assertEquals(measured.rawIntensity(), flat.rawIntensity(), 0.0);
        assertEquals(ObservationOwnership.DIRECT_TWO_SIDED, measured.ownership());
        assertEquals(ObservationOwnership.NO_SIGNAL_VALID_RASTER, flat.ownership());
        assertEquals(0.14, flat.noiseFloor(), 1e-14,
                "complete valid positive raster has no relative lateral contrast");
        assertTrue(coherence(narrow) > 0.8, "localized weak ribbon retains direct two-sided continuation");
        assertEquals(0.0, coherence(diffuse), 0.0,
                "interpolated endpoint noise cannot turn flat bridge presence into position evidence");
    }

    @Test
    void bridgeOnlyFadeTendsToZeroWithoutDoubleAttenuatingUniformlyWeakRibbons() {
        EvidenceSnapshot narrow = scene(1.0, 0.12);
        double previous = coherence(narrow);
        assertTrue(previous > 0.8);
        for (double contrast : List.of(0.1, 0.01, 0.001)) {
            EvidenceSnapshot faded = scene(contrast, 0.12);
            assertEquals(profiles(narrow, false), profiles(faded, false));
            assertEquals(sample(narrow).rawIntensity(), sample(faded).rawIntensity(), 0.0);
            assertEquals(ObservationOwnership.DIRECT_TWO_SIDED, sample(faded).ownership(),
                    "the bridge stays localized while its relative contrast fades");
            double current = coherence(faded);
            assertTrue(current > 0.0 && current < previous,
                    "bridge corroboration must attenuate continuously before the no-mode boundary");
            previous = current;
        }
        assertTrue(previous < 0.01);
        assertEquals(0.0, coherence(scene(0.0, 0.12)), 0.0);
        assertEquals(coherence(narrow), coherence(scene(1.0, 0.012)), 1e-10,
                "endpoint amplitude already attenuates R; uniformly faint concentration must not be counted twice");
    }

    private static double coherence(EvidenceSnapshot evidence) {
        return profiles(evidence, true).get(2).modes().get(0).branchCoherence();
    }

    @Test
    void bridgeOwnershipIsDirectionIndependentAndCannotBorrowHaloBrackets() {
        for (double contrast : List.of(1.0, 0.1, 0.0)) {
            EvidenceSnapshot evidence = scene(contrast, 0.12);
            var reversed = new ProbabilisticProfileFactory().create(List.of(SOURCE.get(1), SOURCE.get(0)),
                    16.0, 7.0, false, evidence, evidence.fields().get("scalar"),
                    EvidenceModelParameters.defaults(), CancellationProbe.NONE, true);
            assertEquals(coherence(evidence), reversed.get(2).modes().get(0).branchCoherence(), 1e-10);
        }
        EvidenceSnapshot full = scene(1.0, 0.12);
        MetricRegion decision = new MetricRegion(List.of(
                MetricRegion.rectangle(-10, -10, 7, 10).polygons().get(0),
                MetricRegion.rectangle(7, -0.25, 9, 0.25).polygons().get(0),
                MetricRegion.rectangle(9, -10, 74, 10).polygons().get(0)));
        EvidenceSnapshot clipped = new EvidenceSnapshot(full.snapshotId(), full.coordinateFrame(),
                full.transform(), full.resolution(), decision, full.evidenceRegion(), full.fields(),
                full.resampling(), full.sourceIdentity());
        assertTrue(clipped.routeSegmentAuthorized(SOURCE.get(0), SOURCE.get(1)));
        List<ProbabilisticProfile> raw = profiles(clipped, false);
        var callback = new ProbabilisticProfileFactory.RasterIntervalEvidence(clipped,
                clipped.fields().get("scalar"), EvidenceModelParameters.defaults().localization(), null);
        assertEquals(0.0, callback.support(raw.get(0), raw.get(0).modes().get(0),
                raw.get(1), raw.get(1).modes().get(0), CancellationProbe.NONE), 0.0,
                "the clipped x=7-9 flank cannot prove the direct x=0-16 leg");
        assertTrue(callback.support(raw.get(1), raw.get(1).modes().get(0),
                raw.get(2), raw.get(2).modes().get(0), CancellationProbe.NONE) > 0.0,
                "the unaffected x=16-32 leg retains direct support");
        var localized = profiles(clipped, true);
        assertEquals(0.0, localized.get(1).modes().get(0).branchCoherence(), 0.0,
                "the x=16 bridge marker cannot borrow its incoming halo bracket");
        assertEquals(0.0, coherence(clipped), 0.0,
                "positive valid halo shoulders cannot certify positional continuation inside a clipped decision band");
    }

    @Test
    void equalOrCensoredNeighborsCannotReplaceTheOwnedCenterButDistantBrightModesStayIndependent() {
        var left = localMode(-0.25, 0.6);
        var right = localMode(0.25, 0.6);
        assertEquals(1.0, factor(List.of(left), List.of()), 0.0);
        assertEquals(0.0, factor(List.of(left, right), List.of()), 0.0);
        var censored = new LocalScalarProfileExtractor.CensoredMode(
                LocalScalarProfileExtractor.CensorSide.RIGHT, 0.25, 1.0, true);
        assertEquals(0.0, factor(List.of(left), List.of(censored)), 0.0);
        assertEquals(1.0, factor(List.of(localMode(0, 0.6), localMode(4, 0.95)), List.of()), 0.0,
                "a distant bright complete sibling must not normalize away the faint owned branch");
        assertEquals(1.0, factor(List.of(localMode(0, 0.6), localMode(-4, 0.95)), List.of()), 0.0);
    }

    @Test
    void bridgeConfidenceDeteriorationRemainsContinuousRelativeToMeasuredEndpointConfidence() {
        for (double confidence : List.of(0.5, 0.25, 0.01, 0.0)) {
            var mode = new LocalScalarProfileExtractor.Mode(0, 0, 0.5, 1.0, confidence,
                    List.of(0.0), List.of(0.0), 0.6);
            double actual = ProbabilisticProfileFactory.RasterIntervalEvidence.measuredBridgeFactor(
                    new LocalScalarProfileExtractor.Result(0.02, 0.14, List.of(mode), List.of()),
                    0.6, 0.5, CancellationProbe.NONE, ignored -> { });
            assertEquals(confidence / 0.5, actual, 1e-14,
                    "equal endpoint confidence is not counted twice; declining owned confidence still attenuates");
        }
    }

    @Test
    void capturedLocalizationAndCheckedLateralAdmissionAreUsedByTheActualBridgeCallback() {
        EvidenceSnapshot evidence = scene(0.1, 0.12);
        var raw = profiles(evidence, false);
        var defaults = EvidenceModelParameters.defaults().localization();
        var changed = localization(defaults, 3.0, 0.4);
        double ordinary = support(evidence, raw, defaults, null);
        double custom = support(evidence, raw, changed, null);
        assertTrue(custom > 0.0 && custom < ordinary,
                "the bridge must use the captured half-response, not diagnostic/default parameters");
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        assertThrows(LongitudinalModeReliability.ResourceLimitException.class,
                () -> support(evidence, raw, localization(defaults, 1e6, 0.08), owner));
        assertEquals(0L, ledger.currentBytes(), "rejected lateral count allocates no retained query payload");
        assertTrue(ledger.peakBytes() < 16_384L, "preflight rejects before a million-sample allocation");
        owner.close();
    }

    @Test
    void bridgeQueryReleasesItsOwnerOnMemoryRefusalAndCancellationDuringExtraction() {
        EvidenceSnapshot evidence = scene(1.0, 0.12);
        var raw = profiles(evidence, false);
        var localization = EvidenceModelParameters.defaults().localization();
        // Leave room for the new source-cell traversal so this control still refuses
        // inside extraction rather than at the earlier owned geometry preflight.
        AttemptMemoryLedger small = new AttemptMemoryLedger(12_288L);
        var smallOwner = small.rootOwner();
        var refusal = assertThrows(LocalScalarProfileExtractor.ResourceLimitException.class,
                () -> support(evidence, raw, localization, smallOwner));
        assertTrue(refusal.getCause() instanceof AttemptMemoryLedger.ResourceLimitException);
        assertEquals(176L, small.currentBytes(), "only the two attempt-owned rectangle indexes remain");
        smallOwner.close();
        assertEquals(0L, small.currentBytes());

        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        var callback = new ProbabilisticProfileFactory.RasterIntervalEvidence(
                evidence, evidence.fields().get("scalar"), localization, owner);
        assertThrows(CancellationException.class, () -> callback.support(
                raw.get(1), raw.get(1).modes().get(0), raw.get(2), raw.get(2).modes().get(0),
                () -> ledger.peakBytes() > 12_288L));
        assertTrue(ledger.peakBytes() > 12_288L, "cancellation happens after local extraction scratch admission");
        assertEquals(176L, ledger.currentBytes(), "canceled query retains only attempt indexes");
        owner.close();
        assertEquals(0L, ledger.currentBytes());
    }

    private static double support(EvidenceSnapshot evidence, List<ProbabilisticProfile> raw,
            EvidenceModelParameters.Localization localization, AttemptMemoryLedger.Owner owner) {
        return new ProbabilisticProfileFactory.RasterIntervalEvidence(evidence,
                evidence.fields().get("scalar"), localization, owner).support(
                    raw.get(1), raw.get(1).modes().get(0), raw.get(2), raw.get(2).modes().get(0),
                    CancellationProbe.NONE);
    }

    private static EvidenceModelParameters.Localization localization(
            EvidenceModelParameters.Localization p, double halfWidth, double halfResponse) {
        return new EvidenceModelParameters.Localization(p.orientationHeadingCount(),
                p.minimumOrientationRayMeters(), p.orientationRayLengthPitches(),
                p.maximumOrientationStepPitches(), p.minimumOrientationValidFraction(),
                p.orientationBackgroundQuantile(), p.orientationProminenceFraction(),
                p.maximumOrientationSampleCount(), p.localModeProminenceFraction(),
                p.localModeShoulderFraction(), p.localModeCoreFraction(), halfWidth, halfResponse);
    }

    private static LocalScalarProfileExtractor.Mode localMode(double center, double amplitude) {
        return new LocalScalarProfileExtractor.Mode(center, center, 0.5, 1.0, 1.0,
                List.of(center), List.of(center), amplitude);
    }

    private static double factor(List<LocalScalarProfileExtractor.Mode> modes,
            List<LocalScalarProfileExtractor.CensoredMode> censored) {
        return ProbabilisticProfileFactory.RasterIntervalEvidence.measuredBridgeFactor(
                new LocalScalarProfileExtractor.Result(0.02, 0.8, modes, censored), 0.6,
                CancellationProbe.NONE, ignored -> { });
    }

    @Test
    void cancellableExtractorPreservesLegacyArithmetic() {
        var samples = extractorSamples();
        var extractor = new LocalScalarProfileExtractor();
        var localization = EvidenceModelParameters.defaults().localization();
        var expected = extractor.extract(samples, PITCH, localization);
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        long[] charged = {0L};
        var actual = extractor.extract(samples, PITCH, localization, owner,
                CancellationProbe.NONE, units -> charged[0] = Math.addExact(charged[0], units));
        assertEquals(expected, actual, "the new execution boundary must preserve all extracted arithmetic");
        assertTrue(charged[0] > samples.size());
        assertTrue(owner.peakBytes() > owner.currentBytes(), "helper scratch is released before result retention");
        owner.close();
        assertEquals(0L, ledger.currentBytes());
    }

    @Test
    void extractorCancellationDuringFilterReleasesAllScratch() {
        var samples = extractorSamples();
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        int[] checks = {0};
        long[] charged = {0};
        int afterInputAndNoiseScans = 3 * samples.size() + 50;
        assertThrows(CancellationException.class, () -> new LocalScalarProfileExtractor().extract(
                samples, PITCH, EvidenceModelParameters.defaults().localization(), owner,
                () -> ++checks[0] > afterInputAndNoiseScans,
                units -> charged[0] = Math.addExact(charged[0], units)));
        assertTrue(charged[0] > samples.size());
        assertTrue(ledger.peakBytes() > 0L, "cancellation occurs during extraction, after scratch admission");
        assertEquals(0L, ledger.currentBytes());
        owner.close();
    }

    @Test
    void equalHeightPeakShoulderWorkIsBoundedAndReleasesScratchOnRefusal() {
        var samples = java.util.stream.IntStream.rangeClosed(0, 512)
                .mapToObj(index -> new LocalScalarProfileExtractor.Sample(index * 0.25,
                        index % 4 == 2 ? 0.2 : 0.02, true)).toList();
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        long[] work = {0};
        boolean[] refusedDuringShoulderScan = {false};
        long cap = 50L * samples.size();
        assertThrows(LongitudinalModeReliability.ResourceLimitException.class,
                () -> new LocalScalarProfileExtractor().extract(samples, PITCH,
                        EvidenceModelParameters.defaults().localization(), owner, CancellationProbe.NONE,
                        units -> {
                            work[0] = Math.addExact(work[0], units);
                            if (work[0] > cap) {
                                refusedDuringShoulderScan[0] = StackWalker.getInstance().walk(frames ->
                                        frames.anyMatch(frame -> frame.getMethodName().equals("localMinimum")));
                                throw new LongitudinalModeReliability.ResourceLimitException();
                            }
                        }));
        assertTrue(work[0] > cap, "the equal-peak scan reaches the explicit work bound");
        assertTrue(refusedDuringShoulderScan[0], "refusal interrupts the equal-height peak shoulder scan");
        assertTrue(ledger.peakBytes() > 0L);
        assertEquals(0L, ledger.currentBytes());
        owner.close();
    }

    private static List<LocalScalarProfileExtractor.Sample> extractorSamples() {
        return profiles(scene(1.0, 0.12), false).get(1).samples().stream()
                .map(sample -> new LocalScalarProfileExtractor.Sample(sample.offsetMeters(),
                        sample.intensity(), sample.valid())).toList();
    }

    private static List<ProbabilisticProfile> profiles(EvidenceSnapshot evidence, boolean reliability) {
        return new ProbabilisticProfileFactory().create(SOURCE, 16.0, 7.0, false,
                evidence, evidence.fields().get("scalar"), EvidenceModelParameters.defaults(),
                CancellationProbe.NONE, reliability);
    }

    private static ImageCostField.RouteSample sample(EvidenceSnapshot evidence) {
        return new ImageCostField(evidence.fields().get("scalar"), evidence.transform(),
                evidence.decisionRegion(), PITCH).sampleRoute(new MetricPoint(8, 0), new MetricPoint(1, 0)).orElseThrow();
    }

    private static EvidenceSnapshot scene(double bridgeContrast, double prominence) {
        int width = 417, height = 161;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(valid, true);
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                double x = -20.0 + col * PITCH, y = -20.0 + row * PITCH;
                double distance = Math.abs(x - 16.0 * Math.rint(x / 16.0));
                // All sampled profiles and their six-metre orientation footprints stay identical.
                double cap = Math.max(0.0, Math.min(1.0, (7.5 - distance) / 0.5));
                double localized = bridgeContrast + (1.0 - bridgeContrast) * cap;
                values[row * width + col] = 0.02 + prominence * (1.0 - localized
                        + localized * Math.exp(-0.5 * y * y / (0.75 * 0.75)));
            }
        }
        EvidenceFieldLineage lineage = new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "localized-bridge-control",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false);
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, values, valid, lineage);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0, 0),
                new GeographicPoint(-1, -1), new GeographicPoint(1, 1));
        RasterMetricTransform transform = new RasterMetricTransform("localized-bridge-control",
                RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(-20, -20), PITCH, 0, 0, PITCH, 1);
        return new EvidenceSnapshot("localized-bridge-control", frame, transform,
                EvidenceResolution.nativeSource(PITCH, PITCH), MetricRegion.rectangle(-10, -10, 74, 10),
                MetricRegion.rectangle(-19, -19, 83, 19), Map.of("scalar", field), "localized-bridge-control");
    }
}
