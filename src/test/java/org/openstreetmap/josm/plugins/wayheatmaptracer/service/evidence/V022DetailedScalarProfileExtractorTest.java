package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

/** Detailed diagnostics retain conditional RMS-only shapes without changing baseline extraction. */
class V022DetailedScalarProfileExtractorTest {
    @Test
    void acceptedThresholdReceiptCapturesActualNonzeroBackgroundShoulderAndRawWinner() {
        List<LocalScalarProfileExtractor.Sample> input = acceptedThresholdProfile();
        var extractor = new LocalScalarProfileExtractor();
        var extraction = extractor.extractDetailedWithAcceptedThresholds(input, 0.125,
                EvidenceModelParameters.Localization.defaults(), null, CancellationProbe.NONE, ignored -> { });
        var detailed = extraction.result();

        assertEquals(1, detailed.baseline().modes().size());
        assertEquals(1, extraction.acceptedReceipts().size());
        var receipt = extraction.acceptedReceipts().get(0);
        assertSame(detailed.baseline().modes().get(0), receipt.mode());
        assertSame(detailed.rawPeaks().get(0), receipt.originPeak());
        assertEquals(0.2, receipt.localBackground(), 0.0);
        assertEquals(0.2 + 0.72 * 0.8, receipt.shoulderThreshold(), 0.0);
        var rawCore = receipt.rawCore().orElseThrow();
        assertEquals(64, rawCore.winnerSampleIndex());
        assertEquals(1.0, rawCore.winnerValue(), 0.0);
        assertEquals(0.2 + 0.92 * 0.8, rawCore.threshold(), 0.0);
        assertEquals(64, receipt.originPeak().firstSampleIndex());
        assertEquals(65, receipt.originPeak().lastSampleIndex());
        assertEquals(0.0, input.get(rawCore.winnerSampleIndex()).offsetMeters(), 0.0);
        assertEquals(0.0625, receipt.originPeak().offsetMeters(), 0.0);
        assertTrue(detailed.rawCoreReceipts().stream().anyMatch(core ->
                core.mode() == receipt.mode() && core.rawPeak() == receipt.originPeak()));
        assertEquals(extractor.extract(input, 0.125,
                EvidenceModelParameters.Localization.defaults()), detailed.baseline());
        assertThrows(UnsupportedOperationException.class,
                () -> extraction.acceptedReceipts().clear());
    }

    @Test
    void optInMetadataLeavesDetailedResultBitExactWithLegacyExtraction() {
        var extractor = new LocalScalarProfileExtractor();
        var input = acceptedThresholdProfile();
        var parameters = EvidenceModelParameters.Localization.defaults();
        var legacy = extractor.extractDetailed(input, 0.125, parameters);
        var optedIn = extractor.extractDetailedWithAcceptedThresholds(input, 0.125, parameters,
                null, CancellationProbe.NONE, ignored -> { });
        assertEquals(legacy, optedIn.result());
        assertEquals(extractor.extract(input, 0.125, parameters), optedIn.result().baseline());
    }

    @Test
    void optInChargesItsOwnWorkAndMemoryAndReleasesOnRefusalOrCancellation() {
        var extractor = new LocalScalarProfileExtractor();
        var input = acceptedThresholdProfile();
        var parameters = EvidenceModelParameters.Localization.defaults();
        var legacyLedger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var legacyOwner = legacyLedger.rootOwner();
        AtomicLong legacyWork = new AtomicLong();
        var legacy = extractor.extractDetailed(input, 0.125, parameters, legacyOwner,
                CancellationProbe.NONE, legacyWork::addAndGet);
        long legacyPeakBytes = legacyLedger.peakBytes();
        legacyOwner.close();

        var optInLedger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var optInOwner = optInLedger.rootOwner();
        AtomicLong optInWork = new AtomicLong();
        var optIn = extractor.extractDetailedWithAcceptedThresholds(input, 0.125, parameters,
                optInOwner, CancellationProbe.NONE, optInWork::addAndGet);
        assertEquals(legacy, optIn.result());
        assertEquals(1, optIn.acceptedReceipts().size());
        assertTrue(optInWork.get() > legacyWork.get());
        assertTrue(optInLedger.peakBytes() > legacyPeakBytes);
        assertTrue(optInLedger.currentBytes() > 0L);
        optInOwner.close();
        assertEquals(0L, optInLedger.currentBytes());

        var refusalLedger = new AttemptMemoryLedger(1L);
        var refusalOwner = refusalLedger.rootOwner();
        assertThrows(LocalScalarProfileExtractor.ResourceLimitException.class, () ->
                extractor.extractDetailedWithAcceptedThresholds(input, 0.125, parameters,
                        refusalOwner, CancellationProbe.NONE, ignored -> { }));
        assertEquals(0L, refusalLedger.currentBytes());
        refusalOwner.close();

        var cancelledLedger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var cancelledOwner = cancelledLedger.rootOwner();
        AtomicLong checkpoints = new AtomicLong();
        assertThrows(CancellationException.class, () -> extractor.extractDetailedWithAcceptedThresholds(
                input, 0.125, parameters, cancelledOwner,
                () -> checkpoints.incrementAndGet() > 10, ignored -> { }));
        assertEquals(0L, cancelledLedger.currentBytes());
        cancelledOwner.close();
    }

    @Test
    void legacyDetailedAndContextVetoRetainCleanBaseWorkAndByteReceipts() {
        var extractor = new LocalScalarProfileExtractor();
        var input = acceptedThresholdProfile();
        var parameters = EvidenceModelParameters.Localization.defaults();

        var detailedLedger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var detailedOwner = detailedLedger.rootOwner();
        AtomicLong detailedWork = new AtomicLong();
        var detailed = extractor.extractDetailed(input, 0.125, parameters, detailedOwner,
                CancellationProbe.NONE, detailedWork::addAndGet);
        assertEquals(3148L, detailedWork.get());
        assertEquals(18840L, detailedLedger.peakBytes());
        assertEquals(864L, detailedLedger.currentBytes());
        detailedOwner.close();

        var plainLedger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var plainOwner = plainLedger.rootOwner();
        AtomicLong plainWork = new AtomicLong();
        var plain = extractor.extract(input, 0.125, parameters, plainOwner,
                CancellationProbe.NONE, plainWork::addAndGet);
        assertEquals(3137L, plainWork.get());
        assertEquals(14232L, plainLedger.peakBytes());
        assertEquals(456L, plainLedger.currentBytes());
        assertEquals(plain, detailed.baseline());
        plainOwner.close();

        var contextLedger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var contextOwner = contextLedger.rootOwner();
        AtomicLong contextWork = new AtomicLong();
        var context = extractor.projectContextVeto(input, 0.125, parameters, contextOwner,
                CancellationProbe.NONE, contextWork::addAndGet, detailed.rawPeaks().get(0));
        assertEquals(3150L, contextWork.get());
        assertEquals(18840L, contextLedger.peakBytes());
        assertEquals(896L, contextLedger.currentBytes());
        assertEquals(detailed.baseline().modes().get(0),
                context.detailsForVetoOnly().baseline().modes().get(0));
        contextOwner.close();
    }

    @Test
    void thresholdReceiptsRejectForeignModeOrPeakIdentityAndInvalidValues() {
        var detailed = new LocalScalarProfileExtractor().extractDetailed(acceptedThresholdProfile(), 0.125,
                EvidenceModelParameters.Localization.defaults());
        var acceptedMode = detailed.baseline().modes().get(0);
        var acceptedPeak = detailed.rawPeaks().get(0);
        var receipt = new LocalScalarProfileExtractor.AcceptedModeThresholdReceipt(acceptedMode,
                acceptedPeak, 0.2, 0.2 + 0.72 * 0.8,
                Optional.of(new LocalScalarProfileExtractor.RawCoreThreshold(64, 1.0,
                        0.2 + 0.92 * 0.8)));
        var extraction = new LocalScalarProfileExtractor.AcceptedThresholdExtraction(
                detailed, List.of(receipt));
        assertEquals(List.of(receipt), extraction.acceptedReceipts());
        assertThrows(IllegalArgumentException.class, () -> new LocalScalarProfileExtractor.AcceptedThresholdExtraction(
                detailed, List.of(receipt, receipt)));
        assertThrows(IllegalArgumentException.class, () -> new LocalScalarProfileExtractor.AcceptedThresholdExtraction(
                detailed, null));
        var foreignMode = new LocalScalarProfileExtractor.Mode(receipt.mode().coreMinimumMeters(),
                receipt.mode().coreMaximumMeters(), receipt.mode().localizationSigmaMeters(),
                receipt.mode().existenceConfidence(), receipt.mode().localizationConfidence(),
                receipt.mode().peakOffsetsMeters(), receipt.mode().nestedCenterOffsetsMeters(),
                receipt.mode().scalarAmplitudeReliability());
        var foreignPeak = new LocalScalarProfileExtractor.RawPeak(receipt.originPeak().firstSampleIndex(),
                receipt.originPeak().lastSampleIndex(), receipt.originPeak().offsetMeters(),
                receipt.originPeak().value());
        assertThrows(IllegalArgumentException.class, () -> new LocalScalarProfileExtractor.AcceptedThresholdExtraction(
                detailed, List.of(new LocalScalarProfileExtractor.AcceptedModeThresholdReceipt(foreignMode,
                        receipt.originPeak(), receipt.localBackground(), receipt.shoulderThreshold(),
                        receipt.rawCore()))));
        assertThrows(IllegalArgumentException.class, () -> new LocalScalarProfileExtractor.AcceptedThresholdExtraction(
                detailed, List.of(new LocalScalarProfileExtractor.AcceptedModeThresholdReceipt(receipt.mode(),
                        foreignPeak, receipt.localBackground(), receipt.shoulderThreshold(), receipt.rawCore()))));
        assertThrows(IllegalArgumentException.class, () -> new LocalScalarProfileExtractor.AcceptedThresholdExtraction(
                detailed, List.of(receipt, receipt)));
        assertThrows(IllegalArgumentException.class, () -> new LocalScalarProfileExtractor.RawCoreThreshold(
                -1, 1.0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new LocalScalarProfileExtractor.RawCoreThreshold(
                0, Double.NaN, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new LocalScalarProfileExtractor.RawCoreThreshold(
                0, 1.01, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new LocalScalarProfileExtractor.RawCoreThreshold(
                0, 0.5, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new LocalScalarProfileExtractor.AcceptedModeThresholdReceipt(
                receipt.mode(), receipt.originPeak(), -0.1, 0.5, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new LocalScalarProfileExtractor.AcceptedModeThresholdReceipt(
                receipt.mode(), receipt.originPeak(), 0.2, Double.NaN, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new LocalScalarProfileExtractor.AcceptedModeThresholdReceipt(
                receipt.mode(), receipt.originPeak(), 0.2, 0.8, null));
    }

    @Test
    void detailedExtractionExposesRawCoreProvenanceForActualNestedCalculation() {
        var extractor = new LocalScalarProfileExtractor();
        var broadInput = contextVetoProfile(Double.NaN);
        var narrowInput = contextVetoProfile(0.13);
        var narrowLowInput = contextVetoProfile(0.10);
        assertEquals(broadInput.subList(62, 67), narrowInput.subList(62, 67));
        assertEquals(broadInput.subList(62, 67), narrowLowInput.subList(62, 67));
        var broad = extractor.extractDetailed(broadInput, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var narrow = extractor.extractDetailed(narrowInput, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var narrowLowBackground = extractor.extractDetailed(narrowLowInput, 0.25,
                EvidenceModelParameters.Localization.defaults());
        assertEquals(extractor.extract(broadInput, 0.25,
                EvidenceModelParameters.Localization.defaults()), broad.baseline());
        assertEquals(extractor.extract(narrowInput, 0.25,
                EvidenceModelParameters.Localization.defaults()), narrow.baseline());
        assertEquals(extractor.extract(narrowLowInput, 0.25,
                EvidenceModelParameters.Localization.defaults()), narrowLowBackground.baseline());
        var broadCore = rawCoreReceiptAtZero(broad);
        var narrowCore = rawCoreReceiptAtZero(narrow);
        var narrowLowCore = rawCoreReceiptAtZero(narrowLowBackground);
        assertEquals(64, broadCore.rawStageWinnerSampleIndex());
        assertEquals(62, broadCore.firstHighCoreSampleIndex());
        assertEquals(66, broadCore.lastHighCoreSampleIndex());
        assertEquals(61, broadCore.leftAdjacentSampleIndex());
        assertEquals(67, broadCore.rightAdjacentSampleIndex());
        assertTrue(broadCore.leftAdjacentObservedValid() && broadCore.leftAdjacentBelowCore());
        assertTrue(broadCore.rightAdjacentObservedValid() && broadCore.rightAdjacentBelowCore());
        for (var receipt : List.of(narrowCore, narrowLowCore)) {
            assertEquals(64, receipt.rawStageWinnerSampleIndex());
            assertEquals(64, receipt.firstHighCoreSampleIndex(), receipt.toString());
            assertEquals(64, receipt.lastHighCoreSampleIndex(), receipt.toString());
            assertTrue(receipt.leftAdjacentObservedValid() && receipt.leftAdjacentBelowCore());
            assertTrue(receipt.rightAdjacentObservedValid() && receipt.rightAdjacentBelowCore());
        }
        assertEquals(61, flankReceiptFor(narrowLowBackground, narrowLowCore).leftBracketSampleIndex());
        assertEquals(67, flankReceiptFor(narrowLowBackground, narrowLowCore).rightBracketSampleIndex());
        assertTrue(broad.rawPeaks().stream().anyMatch(peak -> peak == broadCore.rawPeak()));
        assertModeIdentityRetained(broad, broadCore);
        assertModeIdentityRetained(narrow, narrowCore);
        assertModeIdentityRetained(narrowLowBackground, narrowLowCore);
        assertThrows(UnsupportedOperationException.class, () -> broad.rawCoreReceipts().clear());
        assertTrue(new LocalScalarProfileExtractor.DetailedResult(broad.baseline(), List.of(), List.of())
                .rawCoreReceipts().isEmpty());
        assertTrue(new LocalScalarProfileExtractor.DetailedResult(broad.baseline(), List.of(), List.of(), List.of())
                .rawCoreReceipts().isEmpty());
    }

    @Test
    void rawCoreReceiptsAreAbsentWhenRawStageCannotProveBothObservedSides() {
        var extractor = new LocalScalarProfileExtractor();
        List<LocalScalarProfileExtractor.Sample> invalid = new ArrayList<>(contextVetoProfile(Double.NaN));
        invalid.set(61, new LocalScalarProfileExtractor.Sample(invalid.get(61).offsetMeters(), Double.NaN, false));
        var invalidResult = extractor.extractDetailed(invalid, 0.25,
                EvidenceModelParameters.Localization.defaults());
        assertTrue(invalidResult.rawCoreReceipts().stream().noneMatch(receipt ->
                receipt.rawPeak().firstSampleIndex() <= 64 && receipt.rawPeak().lastSampleIndex() >= 64));

        List<LocalScalarProfileExtractor.Sample> edge = new ArrayList<>();
        for (int index = 0; index <= 30; index++) {
            edge.add(new LocalScalarProfileExtractor.Sample(-7.01 + index * 14.02 / 30.0,
                    index == 0 ? 0.95 : 0.02, true));
        }
        var censored = extractor.extractDetailedWithAcceptedThresholds(edge, 1.0,
                EvidenceModelParameters.Localization.defaults(), null, CancellationProbe.NONE, ignored -> { });
        assertFalse(censored.result().baseline().censoredModes().isEmpty());
        assertTrue(censored.result().rawCoreReceipts().isEmpty());
        assertTrue(censored.acceptedReceipts().isEmpty());
    }

    @Test
    void rawCoreReceiptsRemainBoundToTheirOwnParallelRawPeaksAndModes() {
        List<LocalScalarProfileExtractor.Sample> parallel = new ArrayList<>();
        for (int index = 0; index <= 128; index++) {
            double offset = -8.0 + index * 0.125;
            double value = 0.02 + 0.12 * gaussian(offset, -2.0, 0.4)
                    + 0.11 * gaussian(offset, 2.0, 0.4);
            parallel.add(new LocalScalarProfileExtractor.Sample(offset, value, true));
        }
        var detailed = new LocalScalarProfileExtractor().extractDetailed(parallel, 0.125,
                EvidenceModelParameters.Localization.defaults());
        assertEquals(2, detailed.rawCoreReceipts().size());
        assertTrue(detailed.rawCoreReceipts().get(0).rawPeak().offsetMeters()
                < detailed.rawCoreReceipts().get(1).rawPeak().offsetMeters());
        for (var receipt : detailed.rawCoreReceipts()) {
            assertTrue(detailed.rawPeaks().stream().anyMatch(peak -> peak == receipt.rawPeak()));
            assertModeIdentityRetained(detailed, receipt);
        }
    }

    @Test
    void detailedExtractionPreservesTheExactBaselineAcrossRepresentativeProfiles() {
        var extractor = new LocalScalarProfileExtractor();
        for (List<LocalScalarProfileExtractor.Sample> profile : List.of(
                impulse(55), impulse(60), impulse(65), samples(x -> 0.2),
                samples(x -> x % 2 == 0 ? 0.42 : 0.40),
                samples(x -> 0.02 + 0.35 * gaussian(x, -3, 0.8) + 0.31 * gaussian(x, 3, 0.8)))) {
            var baseline = extractor.extract(profile, 1.0, EvidenceModelParameters.Localization.defaults());
            var detailed = extractor.extractDetailed(profile, 1.0, EvidenceModelParameters.Localization.defaults());
            assertEquals(baseline, detailed.baseline());
        }
    }

    @Test
    void detailedExtractionReportsTheRejectedImpulseWithItsActualProvisionalCore() {
        var extraction = new LocalScalarProfileExtractor().extractDetailedWithAcceptedThresholds(
                impulse(60), 1.0, EvidenceModelParameters.Localization.defaults(),
                null, CancellationProbe.NONE, ignored -> { });
        var detailed = extraction.result();
        assertTrue(detailed.baseline().modes().stream().noneMatch(mode ->
                mode.peakOffsetsMeters().stream().anyMatch(offset -> Math.abs(offset) < 0.5)));
        var suppressed = detailed.suppressedModes().stream().filter(mode ->
                Math.abs(mode.rawPeakOffsetMeters()) < 0.5).findFirst().orElseThrow();
        assertTrue(extraction.acceptedReceipts().stream().noneMatch(receipt ->
                Math.abs(receipt.originPeak().offsetMeters()) < 0.5));
        assertEquals("RMS_ONLY", suppressed.reason().name());
        assertEquals(0.0, suppressed.mode().centerMeters(), 1e-12);
        assertEquals(-0.25, suppressed.mode().coreMinimumMeters(), 1e-12);
        assertEquals(0.25, suppressed.mode().coreMaximumMeters(), 1e-12);
        assertEquals(List.of(0.0, 0.0, 0.0), suppressed.mode().nestedCenterOffsetsMeters());
        assertEquals(0.1, suppressed.rawPeakValue(), 1e-12);
        assertEquals(0.034440036, suppressed.prominence(), 1e-8);
        assertTrue(suppressed.rmsUncertainty() > suppressed.prominence());
        assertTrue(suppressed.filterUncertainty() < suppressed.prominence());
        assertTrue(suppressed.prominence() >= suppressed.fractionalThreshold());
        assertThrows(IllegalArgumentException.class, () -> new LocalScalarProfileExtractor.SuppressedMode(
                suppressed.mode(), suppressed.firstSampleIndex(), suppressed.lastSampleIndex(),
                suppressed.rawPeakOffsetMeters(), suppressed.rawPeakValue(), suppressed.localBackground(),
                suppressed.prominence(), suppressed.rmsUncertainty(), suppressed.prominence() + 0.01,
                suppressed.fractionalThreshold(), LocalScalarProfileExtractor.SuppressionReason.RMS_ONLY));
    }

    @Test
    void nonRmsFilteredAndCensoredEvidenceCannotBecomeSuppressedShapes() {
        var extractor = new LocalScalarProfileExtractor();
        var alternating = samples(x -> (Math.round((x + 7) * 2) & 1) == 0 ? 0.42 : 0.40);
        var alternatingDetailed = extractor.extractDetailed(alternating, 1.0,
                EvidenceModelParameters.Localization.defaults());
        assertTrue(alternatingDetailed.suppressedModes().stream().allMatch(mode ->
                mode.reason().name().equals("RMS_ONLY")
                    && mode.prominence() + 1e-12 >= mode.filterUncertainty()),
                alternatingDetailed.suppressedModes().toString());

        List<LocalScalarProfileExtractor.Sample> invalid = new ArrayList<>(samples(x ->
                0.04 + 0.9 * gaussian(x, 0, 1.8)));
        for (int index : List.of(13, 15)) {
            invalid.set(index, new LocalScalarProfileExtractor.Sample(
                    invalid.get(index).offsetMeters(), Double.NaN, false));
        }
        var invalidDetailed = extractor.extractDetailed(invalid, 1.0,
                EvidenceModelParameters.Localization.defaults());
        assertTrue(invalidDetailed.suppressedModes().isEmpty());
        assertEquals(extractor.extract(invalid, 1.0, EvidenceModelParameters.Localization.defaults()),
                invalidDetailed.baseline());

        List<LocalScalarProfileExtractor.Sample> edge = new ArrayList<>();
        for (int i = 0; i <= 30; i++) {
            double offset = -7.01 + i * 14.02 / 30.0;
            double value = i == 0 ? 0.1 : i == 6 ? 0.95 : 0.02;
            edge.add(new LocalScalarProfileExtractor.Sample(offset, value, true));
        }
        var edgeDetailed = extractor.extractDetailed(edge, 1.0,
                EvidenceModelParameters.Localization.defaults());
        assertTrue(edgeDetailed.suppressedModes().stream().noneMatch(mode -> mode.firstSampleIndex() == 0));
        assertEquals(extractor.extract(edge, 1.0, EvidenceModelParameters.Localization.defaults()),
                edgeDetailed.baseline());
    }

    @Test
    void rawPeakCensusIsImmutableAndCarriesOriginalSampleIndices() {
        var detailed = new LocalScalarProfileExtractor().extractDetailed(impulse(60), 1.0,
                EvidenceModelParameters.Localization.defaults());
        assertFalse(detailed.rawPeaks().isEmpty());
        for (var peak : detailed.rawPeaks()) {
            assertTrue(peak.firstSampleIndex() >= 0);
            assertTrue(peak.lastSampleIndex() >= peak.firstSampleIndex());
            assertTrue(peak.lastSampleIndex() < 31);
        }
        assertThrows(UnsupportedOperationException.class, () -> detailed.rawPeaks().clear());
        assertThrows(UnsupportedOperationException.class, () -> detailed.suppressedModes().clear());
    }

    @Test
    void detailedOwnerReleasesAllocationsOnRefusalAndCancellationAndRetainsOnSuccess() {
        var samples = impulse(60);
        var localization = EvidenceModelParameters.Localization.defaults();
        var tiny = new AttemptMemoryLedger(1L);
        var tinyOwner = tiny.rootOwner();
        assertThrows(LocalScalarProfileExtractor.ResourceLimitException.class, () ->
                new LocalScalarProfileExtractor().extractDetailed(samples, 1.0, localization,
                        tinyOwner, CancellationProbe.NONE, ignored -> { }));
        assertEquals(0L, tiny.currentBytes());
        tinyOwner.close();

        var cancelled = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var cancelledOwner = cancelled.rootOwner();
        AtomicInteger checkpoints = new AtomicInteger();
        assertThrows(CancellationException.class, () -> new LocalScalarProfileExtractor().extractDetailed(
                samples, 1.0, localization, cancelledOwner,
                () -> checkpoints.incrementAndGet() > 100, ignored -> { }));
        assertEquals(0L, cancelled.currentBytes());
        cancelledOwner.close();

        var retained = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var retainedOwner = retained.rootOwner();
        var result = new LocalScalarProfileExtractor().extractDetailed(samples, 1.0,
                localization, retainedOwner, CancellationProbe.NONE, ignored -> { });
        assertTrue(result.baseline().maximumIntensity() > 0.1);
        assertTrue(retained.currentBytes() > 0L);
        retainedOwner.close();
        assertEquals(0L, retained.currentBytes());
    }

    @Test
    void detailedRetainedGraphIsChargedAgainstTheStrictBaseline() {
        var samples = impulse(60);
        var localization = EvidenceModelParameters.Localization.defaults();
        var strictLedger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var strictOwner = strictLedger.rootOwner();
        new LocalScalarProfileExtractor().extract(samples, 1.0, localization, strictOwner);
        long strictRetained = strictLedger.currentBytes();

        var detailedLedger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var detailedOwner = detailedLedger.rootOwner();
        var result = new LocalScalarProfileExtractor().extractDetailed(samples, 1.0, localization,
                detailedOwner, CancellationProbe.NONE, ignored -> { });
        long rawCount = result.rawPeaks().size();
        long suppressedCount = result.suppressedModes().size();
        long flankCount = result.flankReceipts().size();
        long rawCoreCount = result.rawCoreReceipts().size();
        assertTrue(rawCount > 0);
        assertTrue(suppressedCount > 0);
        assertTrue(flankCount > 0);
        for (var receipt : result.flankReceipts()) {
            assertTrue(result.baseline().modes().stream().anyMatch(mode -> mode == receipt.mode())
                    || result.suppressedModes().stream().anyMatch(mode -> mode.mode() == receipt.mode()),
                    "flank receipts must retain the exact measured mode object");
        }
        assertThrows(UnsupportedOperationException.class, () -> result.flankReceipts().clear());

        // Independent retained-object inventory for this fixture: raw peak objects; each
        // suppression plus its Mode, singleton peak-offset list/value, and three-center list/values;
        // each flank receipt and raw-core receipt (each referring to an already-counted exact Mode);
        // then the five-reference result wrapper and all four immutable list/reference-array pairs.
        long rawRows = rawCount * AttemptMemoryLedger.objectBytes(24);
        long suppressedRows = suppressedCount * (AttemptMemoryLedger.objectBytes(80)
                + AttemptMemoryLedger.objectBytes(64)
                + AttemptMemoryLedger.objectBytes(16) + AttemptMemoryLedger.referenceArrayBytes(1)
                + AttemptMemoryLedger.objectBytes(8)
                + AttemptMemoryLedger.objectBytes(16) + AttemptMemoryLedger.referenceArrayBytes(3)
                + 3 * AttemptMemoryLedger.objectBytes(8));
        long flankRows = flankCount * AttemptMemoryLedger.objectBytes(18);
        long rawCoreRows = rawCoreCount * AttemptMemoryLedger.objectBytes(40);
        long immutableLists = AttemptMemoryLedger.objectBytes(40)
                + AttemptMemoryLedger.objectBytes(16) + AttemptMemoryLedger.referenceArrayBytes(suppressedCount)
                + AttemptMemoryLedger.objectBytes(16) + AttemptMemoryLedger.referenceArrayBytes(rawCount)
                + AttemptMemoryLedger.objectBytes(16) + AttemptMemoryLedger.referenceArrayBytes(flankCount)
                + AttemptMemoryLedger.objectBytes(16) + AttemptMemoryLedger.referenceArrayBytes(rawCoreCount);
        assertEquals(rawRows + suppressedRows + flankRows + rawCoreRows + immutableLists,
                detailedLedger.currentBytes() - strictRetained,
                "detailed retained graph must be fully charged in addition to baseline ownership");
        strictOwner.close();
        detailedOwner.close();
    }

    @Test
    void detailedLateFailuresReleaseACompletedDiagnosticGraph() {
        var samples = impulse(60);
        var localization = EvidenceModelParameters.Localization.defaults();
        var baselineLedger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var baselineOwner = baselineLedger.rootOwner();
        new LocalScalarProfileExtractor().extract(samples, 1.0, localization, baselineOwner);
        long baselineRetained = baselineLedger.currentBytes();

        var successfulLedger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var successfulOwner = successfulLedger.rootOwner();
        AtomicInteger chargeCalls = new AtomicInteger();
        AtomicInteger cancellationCalls = new AtomicInteger();
        long[] beforeFinalCharge = {0L};
        var successfulResult = new LocalScalarProfileExtractor().extractDetailed(samples, 1.0, localization,
                successfulOwner, () -> {
                    cancellationCalls.incrementAndGet();
                    beforeFinalCharge[0] = successfulLedger.currentBytes();
                    return false;
                }, ignored -> chargeCalls.incrementAndGet());
        long retainedChargeCount = chargeCalls.get();
        long retainedCancellationCount = cancellationCalls.get();
        assertTrue(retainedChargeCount > 0);
        assertTrue(retainedCancellationCount > 0);
        assertTrue(beforeFinalCharge[0] > baselineRetained,
                "the last cancellation checkpoint follows baseline and diagnostic retention");
        long diagnosticRows = successfulResult.rawPeaks().size() * AttemptMemoryLedger.objectBytes(24)
                + successfulResult.flankReceipts().size() * AttemptMemoryLedger.objectBytes(18)
                + successfulResult.rawCoreReceipts().size() * AttemptMemoryLedger.objectBytes(40)
                + successfulResult.suppressedModes().size() * (
                        AttemptMemoryLedger.objectBytes(80) + AttemptMemoryLedger.objectBytes(64)
                        + AttemptMemoryLedger.objectBytes(16) + AttemptMemoryLedger.referenceArrayBytes(1)
                        + AttemptMemoryLedger.objectBytes(8) + AttemptMemoryLedger.objectBytes(16)
                        + AttemptMemoryLedger.referenceArrayBytes(3)
                        + 3 * AttemptMemoryLedger.objectBytes(8));
        assertTrue(beforeFinalCharge[0] >= baselineRetained + diagnosticRows,
                "last pre-wrapper checkpoint follows retained raw peaks, flank receipts, and a full suppressed core");
        assertTrue(successfulLedger.peakBytes() > beforeFinalCharge[0],
                "temporary extraction workspace also contributes to peak admission");
        successfulOwner.close();
        baselineOwner.close();

        var refusedLedger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var refusedOwner = refusedLedger.rootOwner();
        AtomicInteger refusalCheckpointCalls = new AtomicInteger();
        long[] bytesAtLateRefusal = {0L};
        assertThrows(AttemptMemoryLedger.ResourceLimitException.class, () ->
                new LocalScalarProfileExtractor().extractDetailed(samples, 1.0, localization,
                        refusedOwner, () -> {
                            if (refusalCheckpointCalls.incrementAndGet() == retainedCancellationCount) {
                                bytesAtLateRefusal[0] = refusedLedger.currentBytes();
                                refusedOwner.reserve(AttemptMemoryLedger.MAX_BYTES);
                            }
                            return false;
                        }, ignored -> { }));
        assertEquals(retainedCancellationCount, refusalCheckpointCalls.get(),
                "memory pressure is injected at the final pre-wrapper checkpoint");
        assertTrue(bytesAtLateRefusal[0] >= baselineRetained + diagnosticRows,
                "typed refusal follows partial result and diagnostic-row retention");
        assertEquals(0L, refusedLedger.currentBytes(), "late refusal releases retained partial rows");
        refusedOwner.close();

        var cancelledLedger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var cancelledOwner = cancelledLedger.rootOwner();
        AtomicInteger cancellationCallsOnRetry = new AtomicInteger();
        assertThrows(CancellationException.class, () ->
                new LocalScalarProfileExtractor().extractDetailed(samples, 1.0, localization,
                        cancelledOwner, () -> cancellationCallsOnRetry.incrementAndGet()
                                >= retainedCancellationCount, ignored -> { }));
        assertEquals(retainedCancellationCount, cancellationCallsOnRetry.get(),
                "late cancellation occurs at the final pre-wrapper checkpoint");
        assertTrue(cancelledLedger.peakBytes() > baselineRetained);
        assertEquals(0L, cancelledLedger.currentBytes(), "late cancellation releases partial rows");
        cancelledOwner.close();

        var chargedLedger = new AttemptMemoryLedger(AttemptMemoryLedger.MAX_BYTES);
        var chargedOwner = chargedLedger.rootOwner();
        AtomicInteger chargeCallsOnRetry = new AtomicInteger();
        assertThrows(DeliberateChargeFailure.class, () ->
                new LocalScalarProfileExtractor().extractDetailed(samples, 1.0, localization,
                        chargedOwner, CancellationProbe.NONE, ignored -> {
                            if (chargeCallsOnRetry.incrementAndGet() == retainedChargeCount) {
                                throw new DeliberateChargeFailure();
                            }
                        }));
        assertEquals(retainedChargeCount, chargeCallsOnRetry.get(),
                "late work-charge failure occurs after all detailed rows were retained");
        assertEquals(0L, chargedLedger.currentBytes(), "late work failure releases partial rows");
        chargedOwner.close();
    }

    private static final class DeliberateChargeFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static LocalScalarProfileExtractor.RawCoreReceipt rawCoreReceiptAtZero(
            LocalScalarProfileExtractor.DetailedResult detailed) {
        return detailed.rawCoreReceipts().stream()
                .filter(receipt -> Math.abs(receipt.rawPeak().offsetMeters()) < 1e-12)
                .findFirst().orElseThrow();
    }

    private static LocalScalarProfileExtractor.FlankReceipt flankReceiptFor(
            LocalScalarProfileExtractor.DetailedResult detailed,
            LocalScalarProfileExtractor.RawCoreReceipt core) {
        return detailed.flankReceipts().stream().filter(receipt -> receipt.mode() == core.mode())
                .findFirst().orElseThrow();
    }

    private static void assertModeIdentityRetained(LocalScalarProfileExtractor.DetailedResult detailed,
            LocalScalarProfileExtractor.RawCoreReceipt receipt) {
        assertTrue(detailed.baseline().modes().stream().anyMatch(mode -> mode == receipt.mode())
                || detailed.suppressedModes().stream().anyMatch(mode -> mode.mode() == receipt.mode()),
                "raw-core receipt must reference the exact emitted Mode object");
    }

    private static List<LocalScalarProfileExtractor.Sample> impulse(int x) {
        List<LocalScalarProfileExtractor.Sample> result = new ArrayList<>();
        for (int index = 0; index <= 30; index++) {
            double offset = -7.01 + index * 14.02 / 30.0;
            double y = 26 + offset;
            int lower = (int) StrictMath.floor(y);
            double fraction = y - lower;
            double intensity = (1 - fraction) * impulseRaster(x, lower)
                    + fraction * impulseRaster(x, lower + 1);
            result.add(new LocalScalarProfileExtractor.Sample(offset, intensity, true));
        }
        return List.copyOf(result);
    }

    private static double impulseRaster(int x, int y) {
        double residual = (y - 26.0) / 0.9;
        return Math.min(1.0, 0.02 + 0.08 * StrictMath.exp(-0.5 * residual * residual)
                + (x == 60 && y == 28 ? 0.9 : 0.0));
    }

    private static List<LocalScalarProfileExtractor.Sample> samples(DoubleUnaryOperator function) {
        List<LocalScalarProfileExtractor.Sample> result = new ArrayList<>();
        for (int index = 0; index <= 30; index++) {
            double offset = -7.01 + index * 14.02 / 30.0;
            result.add(new LocalScalarProfileExtractor.Sample(offset, function.applyAsDouble(offset), true));
        }
        return List.copyOf(result);
    }

    private static List<LocalScalarProfileExtractor.Sample> contextVetoProfile(double exteriorLevel) {
        List<LocalScalarProfileExtractor.Sample> result = new ArrayList<>();
        for (int index = 0; index <= 128; index++) {
            double offset = -8.0 + index * 0.125;
            double lowerNative = StrictMath.floor(offset / 0.25) * 0.25;
            double upperNative = lowerNative + 0.25;
            double fraction = (offset - lowerNative) / 0.25;
            double lowerValue = contextNativeValue(lowerNative, exteriorLevel);
            double upperValue = contextNativeValue(upperNative, exteriorLevel);
            double value = lowerValue + fraction * (upperValue - lowerValue);
            result.add(new LocalScalarProfileExtractor.Sample(offset, value, true));
        }
        return List.copyOf(result);
    }

    private static List<LocalScalarProfileExtractor.Sample> acceptedThresholdProfile() {
        List<LocalScalarProfileExtractor.Sample> result = new ArrayList<>();
        for (int index = 0; index <= 128; index++) {
            double offset = -8.0 + index * 0.125;
            double distance = offset < 0.0 ? -offset : offset > 0.125 ? offset - 0.125 : 0.0;
            double intensity = 0.2 + 0.8 * Math.max(0.0, 1.0 - distance / 0.75);
            result.add(new LocalScalarProfileExtractor.Sample(offset, intensity, true));
        }
        return List.copyOf(result);
    }

    private static double contextNativeValue(double offset, double exteriorLevel) {
        if (Double.isFinite(exteriorLevel) && Math.abs(offset) > 0.25) return exteriorLevel;
        double z = offset / 0.75;
        return 0.02 + 0.12 * StrictMath.exp(-0.5 * z * z);
    }

    private static double gaussian(double x, double center, double sigma) {
        double z = (x - center) / sigma;
        return StrictMath.exp(-0.5 * z * z);
    }
}
