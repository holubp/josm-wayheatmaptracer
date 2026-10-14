package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

/** Coherent cross-section slope is signal, while unresolved noise is not positional proof. */
class V022ScalarNoiseAttributionTest {
    @Test
    void neighboringImpulseRetainsRmsOnlyShapeWithoutChangingStrictBaseline() {
        for (int x : List.of(55, 60, 65)) {
            List<LocalScalarProfileExtractor.Sample> profile = new ArrayList<>();
            for (int index = 0; index <= 30; index++) {
                double offset = -7.01 + index * 14.02 / 30.0;
                double y = 26 + offset;
                int lower = (int) StrictMath.floor(y);
                double fraction = y - lower;
                double intensity = (1 - fraction) * impulseRaster(x, lower)
                        + fraction * impulseRaster(x, lower + 1);
                profile.add(new LocalScalarProfileExtractor.Sample(offset, intensity, true));
            }
            var extractor = new LocalScalarProfileExtractor();
            var result = extractor.extract(profile, 1.0,
                    EvidenceModelParameters.Localization.defaults());
            var detailed = extractor.extractDetailed(profile, 1.0,
                    EvidenceModelParameters.Localization.defaults());
            assertEquals(result, detailed.baseline(), "detail capture must preserve strict RMS arithmetic");
            boolean admittedWeakCore = result.modes().stream().anyMatch(mode ->
                    mode.peakOffsetsMeters().stream().anyMatch(offset -> Math.abs(offset) < 0.5));
            if (x != 60) {
                assertTrue(admittedWeakCore, "independently admitted exterior x=" + x);
                assertEquals(1, result.modes().size());
                assertEquals(0.0, result.modes().get(0).centerMeters(), 0.0);
                assertTrue(detailed.suppressedModes().isEmpty());
                continue;
            }
            assertFalse(admittedWeakCore, "ordinary extraction must retain its unchanged full-window RMS rejection");
            assertEquals(1, detailed.suppressedModes().size());
            var suppression = detailed.suppressedModes().get(0);
            assertEquals(LocalScalarProfileExtractor.SuppressionReason.RMS_ONLY, suppression.reason());
            assertEquals(15, suppression.firstSampleIndex());
            assertEquals(15, suppression.lastSampleIndex());
            assertEquals(0.0, suppression.rawPeakOffsetMeters(), 0.0);
            assertEquals(0.1, suppression.rawPeakValue(), 0.0);
            assertEquals(List.of(0.0), suppression.mode().peakOffsetsMeters());
            assertEquals(List.of(0.0, 0.0, 0.0), suppression.mode().nestedCenterOffsetsMeters());
            assertEquals(-0.25, suppression.mode().coreMinimumMeters(), 0.0);
            assertEquals(0.25, suppression.mode().coreMaximumMeters(), 0.0);
            assertEquals(1.0, suppression.mode().localizationConfidence(), 0.0);
            assertTrue(suppression.prominence() > suppression.fractionalThreshold());
            assertTrue(suppression.prominence() > suppression.filterUncertainty());
            assertTrue(suppression.prominence() < suppression.rmsUncertainty());
            assertEquals(0.3009439458277842, suppression.mode().scalarAmplitudeReliability(), 1e-12);
            // Conditional restoration is proved separately by the actual factory and
            // unchanged <=1 native metre full-engine impulse path controls.
        }
    }

    private static double impulseRaster(int x, int y) {
        double residual = (y - 26.0) / 0.9;
        return Math.min(1.0, 0.02 + 0.08 * StrictMath.exp(-0.5 * residual * residual)
                + (x == 60 && y == 28 ? 0.9 : 0.0));
    }

    @Test
    void valleyBoundedNoiseStillRejectsInterpolatedAlternationAcrossSamplingPhases() {
        for (double phase : List.of(0.0, 0.25, 0.5, 0.75)) {
            List<LocalScalarProfileExtractor.Sample> profile = new ArrayList<>();
            for (int index = 0; index <= 30; index++) {
                double offset = -7.01 + index * 14.02 / 30.0;
                double y = 20 + phase + offset;
                int lower = (int) StrictMath.floor(y);
                double fraction = y - lower;
                double first = lower % 2 == 0 ? 0.42 : 0.40;
                double second = lower % 2 == 0 ? 0.40 : 0.42;
                profile.add(new LocalScalarProfileExtractor.Sample(offset,
                        (1 - fraction) * first + fraction * second, true));
            }
            var result = new LocalScalarProfileExtractor().extract(profile, 1.0,
                    EvidenceModelParameters.Localization.defaults());
            assertTrue(result.modes().isEmpty(), "Alternating noise phase=" + phase);
        }
    }

    @Test
    void broadOffsetGaussianRetainsItsMeasuredCore() {
        assertGaussian(0.9);
    }

    @Test
    void faintBroadGaussianRetainsTheSameMeasuredCore() {
        assertGaussian(0.07);
    }

    @Test
    void flatAndAffineProfilesCannotSupplyMeasuredCenters() {
        assertTrue(extract(samples(x -> 0.9)).modes().isEmpty());
        assertTrue(extract(samples(x -> 0.2 + 0.04 * (x + 7))).modes().isEmpty());
    }

    @Test
    void alternatingNoiseCannotSupplyMeasuredCenters() {
        List<LocalScalarProfileExtractor.Sample> samples = new ArrayList<>();
        for (int i = 0; i <= 12; i++) {
            samples.add(new LocalScalarProfileExtractor.Sample(-7 + i * 14.0 / 12,
                    i % 2 == 0 ? 0.42 : 0.40, true));
        }
        assertTrue(extract(samples).modes().isEmpty());
    }

    @Test
    void invalidNeighborsCannotAuthorizeTheIsolatedPeak() {
        List<LocalScalarProfileExtractor.Sample> samples = new ArrayList<>(samples(x ->
                0.04 + 0.9 * StrictMath.exp(-0.5 * x * x / (1.8 * 1.8))));
        for (int index : List.of(5, 7)) {
            samples.set(index, new LocalScalarProfileExtractor.Sample(
                    samples.get(index).offsetMeters(), Double.NaN, false));
        }
        assertTrue(extract(samples).modes().isEmpty());
    }

    @Test
    void narrowCoherentCoreRemainsLocalized() {
        var result = extract(samples(x -> 0.04 + 0.9 * StrictMath.exp(-0.5 * x * x / (1.2 * 1.2))));
        assertEquals(1, result.modes().size());
        var mode = result.modes().get(0);
        assertEquals(0.0, 0.5 * (mode.coreMinimumMeters() + mode.coreMaximumMeters()), 0.1);
    }

    @Test
    void equivalentDenserSamplingRetainsThePhysicalCore() {
        for (int intervals : List.of(12, 24)) {
            List<LocalScalarProfileExtractor.Sample> samples = new ArrayList<>();
            for (int i = 0; i <= intervals; i++) {
                double x = -7 + i * 14.0 / intervals;
                samples.add(new LocalScalarProfileExtractor.Sample(x,
                        0.04 + 0.9 * StrictMath.exp(-0.5 * (x - 4) * (x - 4) / (2.88 * 2.88)), true));
            }
            var result = extract(samples);
            assertEquals(1, result.modes().size());
            var mode = result.modes().get(0);
            assertEquals(4.0, 0.5 * (mode.coreMinimumMeters() + mode.coreMaximumMeters()), 1.2);
        }
    }

    private static void assertGaussian(double amplitude) {
        var result = extract(samples(x -> 0.04 + amplitude
                * StrictMath.exp(-0.5 * (x - 4) * (x - 4) / (2.88 * 2.88))));
        assertEquals(1, result.modes().size(), "a two-sided high core must survive its coherent slopes");
        var mode = result.modes().get(0);
        assertEquals(4.0, 0.5 * (mode.coreMinimumMeters() + mode.coreMaximumMeters()), 1.2);
    }

    private static List<LocalScalarProfileExtractor.Sample> samples(DoubleUnaryOperator intensity) {
        List<LocalScalarProfileExtractor.Sample> samples = new ArrayList<>();
        for (int i = 0; i <= 12; i++) {
            double x = -7 + i * 14.0 / 12;
            samples.add(new LocalScalarProfileExtractor.Sample(x, intensity.applyAsDouble(x), true));
        }
        return List.copyOf(samples);
    }

    private static LocalScalarProfileExtractor.Result extract(List<LocalScalarProfileExtractor.Sample> samples) {
        return new LocalScalarProfileExtractor().extract(samples, 2.4,
                EvidenceModelParameters.Localization.defaults());
    }
}
