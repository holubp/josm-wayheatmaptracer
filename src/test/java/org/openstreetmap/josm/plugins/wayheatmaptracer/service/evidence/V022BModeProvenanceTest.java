package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleUnaryOperator;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

/** Verifies original detailed extraction measurements without changing baseline decisions. */
class V022BModeProvenanceTest {
    private static final EvidenceModelParameters.Localization PARAMETERS =
            EvidenceModelParameters.Localization.defaults();

    @Test
    void receiptKeepsAsymmetricRawCenterSeparateFromWinningSample() {
        var samples = asymmetricPlateau();
        var detailed = new LocalScalarProfileExtractor().extractDetailed(samples, 0.25, PARAMETERS);
        var receipt = detailed.modeObservationReceipts().stream()
                .filter(row -> row.rawPeak().offsetMeters() >= 0.0).findFirst().orElseThrow();
        var rawCenter = receipt.nestedCenters().stream().filter(row ->
                row.stage() == LocalScalarProfileExtractor.ObservationStage.RAW).findFirst().orElseThrow();
        assertTrue(rawCenter.available());
        assertSame(detailed.rawPeaks().stream().filter(peak -> peak == receipt.rawPeak())
                .findFirst().orElseThrow(), receipt.rawPeak());
        assertNotEquals(samples.get(rawCenter.winnerSampleIndex()).offsetMeters(),
                rawCenter.centerOffsetMeters(),
                "the plateau winner sample and measured raw threshold-center remain distinct");
        assertSame(receipt.mode(), detailed.baseline().modes().stream()
                .filter(mode -> mode == receipt.mode()).findFirst().orElseGet(() -> detailed.suppressedModes()
                        .stream().map(LocalScalarProfileExtractor.SuppressedMode::mode)
                        .filter(mode -> mode == receipt.mode()).findFirst().orElseThrow()));
    }

    @Test
    void receiptCarriesItsOwnBackgroundProminenceAndThresholdMeasurements() {
        var detailed = new LocalScalarProfileExtractor().extractDetailed(asymmetricPlateau(), 0.25, PARAMETERS);
        assertFalse(detailed.modeObservationReceipts().isEmpty());
        for (var receipt : detailed.modeObservationReceipts()) {
            assertEquals(receipt.rawPeakValue() - receipt.localBackground(), receipt.prominence(), 1e-12);
            assertEquals(receipt.localBackground() + PARAMETERS.localModeShoulderFraction()
                    * receipt.prominence(), receipt.shoulderThreshold(), 1e-12);
            assertTrue(receipt.rmsUncertainty() >= 0.0);
            assertTrue(receipt.filterUncertainty() >= 0.0);
            assertTrue(receipt.fractionalThreshold() >= 0.0);
            assertEquals(LocalScalarProfileExtractor.ObservationLineage.SAME_INPUT_PROFILE_FILTER_DERIVATIVES,
                    receipt.sourceLineage());
            assertEquals(List.of(LocalScalarProfileExtractor.ObservationStage.RAW,
                            LocalScalarProfileExtractor.ObservationStage.B3,
                            LocalScalarProfileExtractor.ObservationStage.B5),
                    receipt.nestedCenters().stream().map(
                            LocalScalarProfileExtractor.NestedCenterReceipt::stage).toList());
        }
    }

    @Test
    void stageAvailabilityAndModeLineageAreExplicitForMeasuredAndCensoredSupport() {
        var extractor = new LocalScalarProfileExtractor();
        var samples = profileWithUnavailableB5();
        var ordinary = extractor.extractDetailed(samples, 0.25, PARAMETERS);
        assertFalse(ordinary.modeObservationReceipts().isEmpty());
        assertTrue(ordinary.modeObservationReceipts().stream()
                .anyMatch(receipt -> !receipt.nestedCenters().get(2).available()),
                "invalid support must actually reach B5's wider kernel while RAW/B3 remain usable");
        for (var receipt : ordinary.modeObservationReceipts()) {
            assertTrue(receipt.rawPeak().firstSampleIndex() <= receipt.rawWinnerSampleIndex());
            assertTrue(receipt.rawPeak().lastSampleIndex() >= receipt.rawWinnerSampleIndex());
            var raw = receipt.nestedCenters().get(0);
            var b3 = receipt.nestedCenters().get(1);
            var b5 = receipt.nestedCenters().get(2);
            assertTrue(raw.available() && b3.available());
            for (var stage : List.of(raw, b3, b5)) {
                if (stage.available()) {
                    assertTrue(stage.intervalMeasured());
                    assertTrue(stage.firstCoreSampleIndex() <= stage.winnerSampleIndex());
                    assertTrue(stage.winnerSampleIndex() <= stage.lastCoreSampleIndex());
                    assertEquals(0.5 * (samples.get(stage.firstCoreSampleIndex()).offsetMeters()
                            + samples.get(stage.lastCoreSampleIndex()).offsetMeters()),
                            stage.centerOffsetMeters(), 1e-12);
                } else {
                    assertTrue(Double.isNaN(stage.centerOffsetMeters()));
                    if (!stage.intervalMeasured()) {
                        assertEquals(-1, stage.winnerSampleIndex());
                        assertEquals(-1, stage.firstCoreSampleIndex());
                        assertEquals(-1, stage.lastCoreSampleIndex());
                    }
                }
            }
        }
        var censored = extractor.extractDetailed(censoredEdge(), 0.25, PARAMETERS);
        assertTrue(censored.baseline().modes().isEmpty());
        assertTrue(censored.baseline().censoredModes().size() > 0);
        assertTrue(censored.modeObservationReceipts().isEmpty(),
                "an edge-censored component has no complete mode identity or invented center receipt");
    }

    @Test
    void detailedAndOrdinaryExtractionKeepExactBaselineAcrossControlProfiles() {
        var extractor = new LocalScalarProfileExtractor();
        List<List<LocalScalarProfileExtractor.Sample>> controls = List.of(
                profile(x -> 0.0), profile(x -> 0.8), profile(x -> 0.02 + 0.06 * gaussian(x, 0, 1.4)),
                asymmetricPlateau(), parallel(), invalidGap(), censoredEdge());
        for (List<LocalScalarProfileExtractor.Sample> samples : controls) {
            assertEquals(extractor.extract(samples, 0.25, PARAMETERS),
                    extractor.extractDetailed(samples, 0.25, PARAMETERS).baseline());
        }
    }

    @Test
    void cancellationAndMemoryRefusalAfterDetailedWorkLeaveCallerUnchanged() {
        var samples = asymmetricPlateau();
        var extractor = new LocalScalarProfileExtractor();
        var measuredLedger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var measuredOwner = measuredLedger.rootOwner();
        AtomicInteger completedCheckpoints = new AtomicInteger();
        extractor.extractDetailed(samples, 0.25, PARAMETERS, measuredOwner,
                () -> { completedCheckpoints.incrementAndGet(); return false; }, ignored -> { });
        int totalCheckpoints = completedCheckpoints.get();
        measuredOwner.close();
        assertEquals(0, measuredLedger.currentBytes());

        var cancelledLedger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var cancelledOwner = cancelledLedger.rootOwner();
        AtomicInteger cancellationCheckpoint = new AtomicInteger();
        assertThrows(CancellationException.class, () -> extractor.extractDetailed(samples, 0.25, PARAMETERS,
                cancelledOwner, () -> cancellationCheckpoint.incrementAndGet() >= totalCheckpoints,
                ignored -> { }));
        assertEquals(0, cancelledLedger.currentBytes(), "late cancellation closes every detailed receipt");
        cancelledOwner.close();

        var successfulLedger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var successfulOwner = successfulLedger.rootOwner();
        extractor.extractDetailed(samples, 0.25, PARAMETERS, successfulOwner,
                CancellationProbe.NONE, ignored -> { });
        long requiredPeak = successfulLedger.peakBytes();
        successfulOwner.close();
        var refusedLedger = new AttemptMemoryLedger(requiredPeak - 1);
        var refusedOwner = refusedLedger.rootOwner();
        assertThrows(LocalScalarProfileExtractor.ResourceLimitException.class, () ->
                extractor.extractDetailed(samples, 0.25, PARAMETERS, refusedOwner,
                        CancellationProbe.NONE, ignored -> { }));
        assertEquals(0, refusedLedger.currentBytes(), "refusal releases all partially retained receipts");
        refusedOwner.close();
    }

    private static List<LocalScalarProfileExtractor.Sample> asymmetricPlateau() {
        List<LocalScalarProfileExtractor.Sample> result = new ArrayList<>();
        double[] values = {0.03, 0.04, 0.05, 0.08, 0.12, 0.20, 0.34, 0.58,
                0.86, 0.86, 0.64, 0.46, 0.29, 0.18, 0.10, 0.06, 0.04};
        for (int index = 0; index < values.length; index++) {
            result.add(new LocalScalarProfileExtractor.Sample((index - 8) * 0.25, values[index], true));
        }
        return List.copyOf(result);
    }

    private static List<LocalScalarProfileExtractor.Sample> profileWithUnavailableB5() {
        List<LocalScalarProfileExtractor.Sample> result = new ArrayList<>();
        // The invalid sample at -0.75m intersects B5's flank support. RAW and B3
        // still have a two-sided high core at zero; B5 has no measured interval.
        for (int index = 0; index <= 40; index++) {
            double offset = (index - 20) * 0.25;
            result.add(new LocalScalarProfileExtractor.Sample(offset,
                    0.02 + 0.8 * gaussian(offset, 0, 1), index != 17));
        }
        return List.copyOf(result);
    }

    private static List<LocalScalarProfileExtractor.Sample> parallel() {
        return profile(x -> 0.02 + 0.55 * gaussian(x, -2.0, 0.55) + 0.48 * gaussian(x, 2.0, 0.6));
    }

    private static List<LocalScalarProfileExtractor.Sample> invalidGap() {
        List<LocalScalarProfileExtractor.Sample> rows = new ArrayList<>(
                profile(x -> 0.02 + 0.7 * gaussian(x, 0, 1.2)));
        rows.set(14, new LocalScalarProfileExtractor.Sample(rows.get(14).offsetMeters(), Double.NaN, false));
        return List.copyOf(rows);
    }

    private static List<LocalScalarProfileExtractor.Sample> censoredEdge() {
        List<LocalScalarProfileExtractor.Sample> rows = new ArrayList<>();
        for (int index = 0; index <= 32; index++) {
            double offset = -4.0 + 0.25 * index;
            rows.add(new LocalScalarProfileExtractor.Sample(offset,
                    index == 0 ? 0.95 : 0.02, true));
        }
        return List.copyOf(rows);
    }

    private static List<LocalScalarProfileExtractor.Sample> profile(DoubleUnaryOperator function) {
        List<LocalScalarProfileExtractor.Sample> rows = new ArrayList<>();
        for (int index = 0; index <= 32; index++) {
            double offset = -4.0 + 0.25 * index;
            rows.add(new LocalScalarProfileExtractor.Sample(offset, function.applyAsDouble(offset), true));
        }
        return List.copyOf(rows);
    }

    private static double gaussian(double x, double center, double sigma) {
        double z = (x - center) / sigma;
        return StrictMath.exp(-0.5 * z * z);
    }
}
