package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

class V022RawCoreContextVetoTest {
    @Test
    void typedProjectionPreservesActualBroadAsymmetricSiblingInvalidAndRmsWitnesses() {
        var extractor = new LocalScalarProfileExtractor();
        var rmsSamples = new ArrayList<LocalScalarProfileExtractor.Sample>();
        for (int i = 0; i <= 128; i++) {
            double offset = -8 + i * .125;
            double lower = Math.floor(offset / .25) * .25;
            double a = rmsSlopeNative(lower), b = rmsSlopeNative(lower + .25);
            rmsSamples.add(new LocalScalarProfileExtractor.Sample(offset,
                    a + (offset - lower) / .25 * (b - a), true));
        }
        var invalid = new ArrayList<>(profile(Double.NaN));
        var neighbor = invalid.get(61);
        invalid.set(61, new LocalScalarProfileExtractor.Sample(neighbor.offsetMeters(), Double.NaN, false));
        for (var contextSamples : List.of(profile(Double.NaN), profile(.13), asymmetricProfile(),
                brighterSiblingProfile(), invalid, rmsSamples)) {
            boolean rms = contextSamples == rmsSamples;
            var decisionSamples = rms ? contextSamples.stream().map(s -> {
                boolean valid = s.offsetMeters() >= -.25 && s.offsetMeters() <= .75;
                return new LocalScalarProfileExtractor.Sample(s.offsetMeters(),
                        valid ? s.intensity() : Double.NaN, valid);
            }).toList() : decisionWindow(contextSamples);
            var decision = extractor.extractDetailed(decisionSamples, .25,
                    EvidenceModelParameters.Localization.defaults());
            var context = extractor.extractDetailed(contextSamples, .25,
                    EvidenceModelParameters.Localization.defaults());
            var selected = decision.rawCoreReceipts().stream().map(LocalScalarProfileExtractor.RawCoreReceipt::mode)
                    .filter(m -> m.coreMinimumMeters() <= 0 && m.coreMaximumMeters() >= 0)
                    .findFirst().orElseThrow();
            var target = decision.rawCoreReceipts().stream().filter(r -> r.mode() == selected)
                    .findFirst().orElseThrow().rawPeak();
            var projection = extractor.projectContextVeto(contextSamples, .25,
                    EvidenceModelParameters.Localization.defaults(), null, CancellationProbe.NONE,
                    ignored -> { }, target);
            assertEquals(context.rawPeaks(), projection.detailsForVetoOnly().rawPeaks());
            assertEquals(context.baseline().censoredModes(),
                    projection.detailsForVetoOnly().baseline().censoredModes());
            ProbabilisticRawCoreContextVeto.DecisionPathAuthority authority = (winner, bracket) -> {
                for (int i = Math.min(winner, bracket); i <= Math.max(winner, bracket); i++)
                    if (!decisionSamples.get(i).valid()) return false;
                return true;
            };
            boolean expected = veto(decisionSamples, contextSamples, decision, context, selected);
            assertEquals(expected, ProbabilisticRawCoreContextVeto.contradicts(decisionSamples, contextSamples,
                    decision, projection, selected, CancellationProbe.NONE, ignored -> { }, authority));
            if (rms) {
                assertTrue(expected);
                assertTrue(decision.baseline().modes().stream().noneMatch(m -> m == selected));
            }
        }
    }

    @Test
    void exactOriginalRmsOnlyModeCanReceiveNegativeContextEvidenceWithoutPromotion() {
        var contextSamples = new ArrayList<LocalScalarProfileExtractor.Sample>();
        for (int i = 0; i <= 128; i++) {
            double offset = -8 + i * 0.125;
            double lower = Math.floor(offset / 0.25) * 0.25;
            double a = rmsSlopeNative(lower), b = rmsSlopeNative(lower + 0.25);
            contextSamples.add(new LocalScalarProfileExtractor.Sample(offset,
                    a + (offset - lower) / 0.25 * (b - a), true));
        }
        var decisionSamples = contextSamples.stream().map(sample -> {
            boolean valid = sample.offsetMeters() >= -0.25 && sample.offsetMeters() <= 0.75;
            return new LocalScalarProfileExtractor.Sample(sample.offsetMeters(),
                    valid ? sample.intensity() : Double.NaN, valid);
        }).toList();
        var extractor = new LocalScalarProfileExtractor();
        var decision = extractor.extractDetailed(decisionSamples, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var context = extractor.extractDetailed(contextSamples, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var suppression = decision.suppressedModes().stream().filter(item ->
                item.mode().coreMinimumMeters() <= 0 && item.mode().coreMaximumMeters() >= 0)
                .findFirst().orElseThrow(() -> new AssertionError("actual RMS shape missing: " + decision));
        assertTrue(decision.baseline().modes().stream().noneMatch(mode -> mode == suppression.mode()));
        assertTrue(veto(decisionSamples, contextSamples, decision, context, suppression.mode()));
        assertTrue(decision.baseline().modes().stream().noneMatch(mode -> mode == suppression.mode()),
                "a negative witness must not promote its original suppression");
    }

    private static double rmsSlopeNative(double offset) {
        return offset == 0.5 ? 0.13351513626881184 : offset == 0.75 ? 0.04
                : offset == 1 ? 0.9 : nativeValue(offset, Double.NaN);
    }

    @Test
    void actualBroadContextContradictsDecisionCoreButNarrowContextsDoNot() {
        var extractor = new LocalScalarProfileExtractor();
        var broadSamples = profile(Double.NaN);
        var narrowSamples = profile(0.13);
        var narrowLowSamples = profile(0.10);
        var decisionSamples = decisionWindow(broadSamples);
        var decision = extractor.extractDetailed(decisionSamples, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var broad = extractor.extractDetailed(broadSamples, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var narrow = extractor.extractDetailed(narrowSamples, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var narrowLow = extractor.extractDetailed(narrowLowSamples, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var selected = decision.baseline().modes().stream()
                .filter(mode -> mode.coreMinimumMeters() <= 0 && mode.coreMaximumMeters() >= 0)
                .findFirst().orElseThrow();

        assertTrue(veto(decisionSamples, broadSamples, decision, broad, selected));
        assertFalse(veto(decisionSamples, narrowSamples, decision, narrow, selected));
        assertFalse(veto(decisionSamples, narrowLowSamples, decision, narrowLow, selected));

        var matchedReceipt = broad.rawCoreReceipts().get(0);
        List<LocalScalarProfileExtractor.RawCoreReceipt> duplicateReceipts = new ArrayList<>(broad.rawCoreReceipts());
        duplicateReceipts.add(matchedReceipt);
        var ambiguous = new LocalScalarProfileExtractor.DetailedResult(broad.baseline(), broad.suppressedModes(),
                broad.rawPeaks(), broad.flankReceipts(), duplicateReceipts);
        assertFalse(veto(decisionSamples, broadSamples, decision, ambiguous, selected));

        var foreignPeak = new LocalScalarProfileExtractor.RawPeak(63, 65, 0.0,
                broadSamples.get(64).intensity());
        var foreignReceipt = new LocalScalarProfileExtractor.RawCoreReceipt(matchedReceipt.mode(), foreignPeak,
                64, matchedReceipt.firstHighCoreSampleIndex(), matchedReceipt.lastHighCoreSampleIndex(),
                matchedReceipt.leftAdjacentSampleIndex(), true, true, matchedReceipt.rightAdjacentSampleIndex(),
                true, true);
        var foreignContext = new LocalScalarProfileExtractor.DetailedResult(broad.baseline(),
                broad.suppressedModes(), List.of(foreignPeak), broad.flankReceipts(), List.of(foreignReceipt));
        assertFalse(veto(decisionSamples, broadSamples, decision, foreignContext, selected));

        List<LocalScalarProfileExtractor.RawPeak> overlappingPeaks = new ArrayList<>(broad.rawPeaks());
        overlappingPeaks.add(new LocalScalarProfileExtractor.RawPeak(
                matchedReceipt.firstHighCoreSampleIndex(), matchedReceipt.firstHighCoreSampleIndex(),
                broadSamples.get(matchedReceipt.firstHighCoreSampleIndex()).offsetMeters(),
                broadSamples.get(matchedReceipt.firstHighCoreSampleIndex()).intensity()));
        var parallelAmbiguity = new LocalScalarProfileExtractor.DetailedResult(broad.baseline(),
                broad.suppressedModes(), overlappingPeaks, broad.flankReceipts(), broad.rawCoreReceipts());
        assertFalse(veto(decisionSamples, broadSamples, decision, parallelAmbiguity, selected));

        List<LocalScalarProfileExtractor.CensoredMode> censored = new ArrayList<>(broad.baseline().censoredModes());
        censored.add(new LocalScalarProfileExtractor.CensoredMode(
                LocalScalarProfileExtractor.CensorSide.LEFT, -0.5, 0.8, false, true, 0.5));
        var censoredBaseline = new LocalScalarProfileExtractor.Result(broad.baseline().noiseFloor(),
                broad.baseline().maximumIntensity(), broad.baseline().modes(), censored);
        var censoredContext = new LocalScalarProfileExtractor.DetailedResult(censoredBaseline,
                broad.suppressedModes(), broad.rawPeaks(), broad.flankReceipts(), broad.rawCoreReceipts());
        assertFalse(veto(decisionSamples, broadSamples, decision, censoredContext, selected));
    }

    @Test
    void contextPathAuthorityChecksTheWholeBracketSegment() {
        var extractor = new LocalScalarProfileExtractor();
        var contextSamples = profile(Double.NaN);
        var decisionSamples = decisionWindow(contextSamples);
        var decision = extractor.extractDetailed(decisionSamples, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var context = extractor.extractDetailed(contextSamples, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var selected = decision.baseline().modes().stream()
                .filter(mode -> mode.coreMinimumMeters() <= 0 && mode.coreMaximumMeters() >= 0)
                .findFirst().orElseThrow();
        assertTrue(ProbabilisticRawCoreContextVeto.contradicts(decisionSamples, contextSamples,
                decision, context, selected, CancellationProbe.NONE, ignored -> { }, (winner, bracket) -> {
                    assertTrue(contextSamples.get(bracket).valid());
                    return Math.abs(bracket - winner) <= 1;
                }));
    }

    @Test
    void asymmetricContradictionIsIndependentOfProfileDirection() {
        var extractor = new LocalScalarProfileExtractor();
        var samples = asymmetricProfile();
        var decisionSamples = decisionWindow(samples);
        var decision = extractor.extractDetailed(decisionSamples, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var context = extractor.extractDetailed(samples, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var selected = decision.baseline().modes().stream()
                .filter(mode -> mode.coreMinimumMeters() <= 0 && mode.coreMaximumMeters() >= 0)
                .findFirst().orElseThrow();
        assertTrue(veto(decisionSamples, samples, decision, context, selected),
                () -> "asymmetric context=" + rawCoreSummary(context) + " decision=" + rawCoreSummary(decision));

        List<LocalScalarProfileExtractor.Sample> reversed = new ArrayList<>(samples);
        java.util.Collections.reverse(reversed);
        List<LocalScalarProfileExtractor.Sample> reversedDecision = new ArrayList<>(decisionSamples);
        java.util.Collections.reverse(reversedDecision);
        var reversedContext = extractor.extractDetailed(reversed, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var reversedResult = extractor.extractDetailed(reversedDecision, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var reversedSelected = reversedResult.baseline().modes().stream()
                .filter(mode -> mode.coreMinimumMeters() <= 0 && mode.coreMaximumMeters() >= 0)
                .findFirst().orElseThrow();
        assertTrue(veto(reversedDecision, reversed, reversedResult, reversedContext, reversedSelected));
    }

    @Test
    void brighterSeparateContextSiblingDoesNotReplaceTheMatchedBranch() {
        var extractor = new LocalScalarProfileExtractor();
        var contextSamples = brighterSiblingProfile();
        var decisionSamples = decisionWindow(contextSamples);
        var decision = extractor.extractDetailed(decisionSamples, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var context = extractor.extractDetailed(contextSamples, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var selected = decision.baseline().modes().stream()
                .filter(mode -> mode.coreMinimumMeters() <= 0 && mode.coreMaximumMeters() >= 0)
                .findFirst().orElseThrow();
        assertTrue(context.rawCoreReceipts().stream().anyMatch(receipt ->
                receipt.rawPeak().offsetMeters() > 1.0));
        assertTrue(context.rawCoreReceipts().stream().anyMatch(receipt ->
                receipt.rawPeak().offsetMeters() == 0.0));
        assertTrue(veto(decisionSamples, contextSamples, decision, context, selected),
                () -> "sibling context=" + rawCoreSummary(context) + " decision=" + rawCoreSummary(decision));
    }

    @Test
    void mismatchAndEquivalentButForeignModeCannotWitnessContradiction() {
        var extractor = new LocalScalarProfileExtractor();
        var contextSamples = profile(Double.NaN);
        var decisionSamples = decisionWindow(contextSamples);
        var decision = extractor.extractDetailed(decisionSamples, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var context = extractor.extractDetailed(contextSamples, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var selected = decision.baseline().modes().stream()
                .filter(mode -> mode.coreMinimumMeters() <= 0 && mode.coreMaximumMeters() >= 0)
                .findFirst().orElseThrow();

        List<LocalScalarProfileExtractor.Sample> changedValue = new ArrayList<>(contextSamples);
        var center = changedValue.get(64);
        changedValue.set(64, new LocalScalarProfileExtractor.Sample(center.offsetMeters(),
                Math.nextUp(center.intensity()), true));
        var changedValueResult = extractor.extractDetailed(changedValue, 0.25,
                EvidenceModelParameters.Localization.defaults());
        assertFalse(veto(decisionSamples, changedValue, decision, changedValueResult, selected));

        List<LocalScalarProfileExtractor.Sample> changedOffset = new ArrayList<>(contextSamples);
        var adjacent = changedOffset.get(63);
        changedOffset.set(63, new LocalScalarProfileExtractor.Sample(Math.nextUp(adjacent.offsetMeters()),
                adjacent.intensity(), true));
        var changedOffsetResult = extractor.extractDetailed(changedOffset, 0.25,
                EvidenceModelParameters.Localization.defaults());
        assertFalse(veto(decisionSamples, changedOffset, decision, changedOffsetResult, selected));

        var equivalentButForeign = new LocalScalarProfileExtractor.Mode(selected.coreMinimumMeters(),
                selected.coreMaximumMeters(), selected.localizationSigmaMeters(), selected.existenceConfidence(),
                selected.localizationConfidence(), selected.peakOffsetsMeters(),
                selected.nestedCenterOffsetsMeters(), selected.scalarAmplitudeReliability());
        assertFalse(veto(decisionSamples, contextSamples, decision, context, equivalentButForeign));
    }

    @Test
    void pathAuthorityExceptionsPropagateUnchanged() {
        var extractor = new LocalScalarProfileExtractor();
        var contextSamples = profile(Double.NaN);
        var decisionSamples = decisionWindow(contextSamples);
        var decision = extractor.extractDetailed(decisionSamples, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var context = extractor.extractDetailed(contextSamples, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var selected = decision.baseline().modes().stream()
                .filter(mode -> mode.coreMinimumMeters() <= 0 && mode.coreMaximumMeters() >= 0)
                .findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> ProbabilisticRawCoreContextVeto.contradicts(
                decisionSamples, contextSamples, decision, context, selected, CancellationProbe.NONE,
                ignored -> { }, (winner, bracket) -> { throw new IllegalArgumentException("geometry refusal"); }));
        assertThrows(CancellationException.class, () -> ProbabilisticRawCoreContextVeto.contradicts(
                decisionSamples, contextSamples, decision, context, selected, CancellationProbe.NONE,
                ignored -> { }, (winner, bracket) -> { throw new CancellationException("cancelled"); }));
    }

    @Test
    void missingOrInvalidContextWitnessIsUnknownAndTypedRefusalsPropagate() {
        var extractor = new LocalScalarProfileExtractor();
        var contextSamples = profile(Double.NaN);
        var decisionSamples = decisionWindow(contextSamples);
        var decision = extractor.extractDetailed(decisionSamples, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var context = extractor.extractDetailed(contextSamples, 0.25,
                EvidenceModelParameters.Localization.defaults());
        var selected = decision.baseline().modes().stream()
                .filter(mode -> mode.coreMinimumMeters() <= 0 && mode.coreMaximumMeters() >= 0)
                .findFirst().orElseThrow();
        var missingReceipt = new LocalScalarProfileExtractor.DetailedResult(context.baseline(),
                context.suppressedModes(), context.rawPeaks(), context.flankReceipts());
        assertFalse(veto(decisionSamples, contextSamples, decision, missingReceipt, selected));

        List<LocalScalarProfileExtractor.Sample> invalid = new ArrayList<>(contextSamples);
        var neighbor = invalid.get(61);
        invalid.set(61, new LocalScalarProfileExtractor.Sample(neighbor.offsetMeters(), Double.NaN, false));
        var invalidContext = extractor.extractDetailed(invalid, 0.25,
                EvidenceModelParameters.Localization.defaults());
        assertFalse(veto(decisionSamples, invalid, decision, invalidContext, selected));

        AtomicInteger charged = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> ProbabilisticRawCoreContextVeto.contradicts(
                decisionSamples, contextSamples, decision, context, selected, CancellationProbe.NONE,
                ignored -> { if (charged.incrementAndGet() == 3) throw new IllegalStateException("work cap"); },
                (winner, bracket) -> true));
        AtomicInteger checkpoints = new AtomicInteger();
        assertThrows(CancellationException.class, () -> ProbabilisticRawCoreContextVeto.contradicts(
                decisionSamples, contextSamples, decision, context, selected,
                () -> {
                    if (checkpoints.incrementAndGet() == 4) throw new CancellationException();
                    return false;
                },
                ignored -> { }, (winner, bracket) -> true));
    }

    private static boolean veto(List<LocalScalarProfileExtractor.Sample> decisionSamples,
            List<LocalScalarProfileExtractor.Sample> contextSamples,
            LocalScalarProfileExtractor.DetailedResult decision,
            LocalScalarProfileExtractor.DetailedResult context,
            LocalScalarProfileExtractor.Mode selected) {
        return ProbabilisticRawCoreContextVeto.contradicts(decisionSamples, contextSamples,
                decision, context, selected, CancellationProbe.NONE, ignored -> { }, (winner, bracket) -> {
                    int low = Math.min(winner, bracket);
                    int high = Math.max(winner, bracket);
                    for (int i = low; i <= high; i++) if (!decisionSamples.get(i).valid()) return false;
                    return true;
                });
    }

    private static List<LocalScalarProfileExtractor.Sample> decisionWindow(
            List<LocalScalarProfileExtractor.Sample> context) {
        List<LocalScalarProfileExtractor.Sample> result = new ArrayList<>(context.size());
        for (int index = 0; index < context.size(); index++) {
            var sample = context.get(index);
            boolean valid = index >= 62 && index <= 66;
            result.add(new LocalScalarProfileExtractor.Sample(sample.offsetMeters(),
                    valid ? sample.intensity() : Double.NaN, valid));
        }
        return List.copyOf(result);
    }

    private static List<LocalScalarProfileExtractor.Sample> profile(double exterior) {
        List<LocalScalarProfileExtractor.Sample> result = new ArrayList<>();
        for (int index = 0; index <= 128; index++) {
            double offset = -8.0 + index * 0.125;
            double lower = StrictMath.floor(offset / 0.25) * 0.25;
            double fraction = (offset - lower) / 0.25;
            double a = nativeValue(lower, exterior);
            double b = nativeValue(lower + 0.25, exterior);
            result.add(new LocalScalarProfileExtractor.Sample(offset, a + fraction * (b - a), true));
        }
        return List.copyOf(result);
    }

    private static List<LocalScalarProfileExtractor.Sample> asymmetricProfile() {
        List<LocalScalarProfileExtractor.Sample> result = new ArrayList<>(profile(Double.NaN));
        for (int index = 0; index < result.size(); index++) {
            var sample = result.get(index);
            if (sample.offsetMeters() > 0.5) {
                result.set(index, new LocalScalarProfileExtractor.Sample(sample.offsetMeters(), 0.02, true));
            }
        }
        return List.copyOf(result);
    }

    private static List<LocalScalarProfileExtractor.Sample> brighterSiblingProfile() {
        List<LocalScalarProfileExtractor.Sample> result = new ArrayList<>();
        for (var sample : profile(Double.NaN)) {
            double z = (sample.offsetMeters() - 5.0) / 0.3;
            double intensity = Math.abs(sample.offsetMeters()) <= 0.5 ? sample.intensity()
                    : Math.min(0.95, sample.intensity() + 0.32 * StrictMath.exp(-0.5 * z * z));
            result.add(new LocalScalarProfileExtractor.Sample(sample.offsetMeters(), intensity, sample.valid()));
        }
        return List.copyOf(result);
    }

    private static double nativeValue(double offset, double exterior) {
        if (Double.isFinite(exterior) && Math.abs(offset) > 0.25) return exterior;
        double z = offset / 0.75;
        return 0.02 + 0.12 * StrictMath.exp(-0.5 * z * z);
    }

    private static String rawCoreSummary(LocalScalarProfileExtractor.DetailedResult detailed) {
        return detailed.rawCoreReceipts().stream().map(receipt -> receipt.rawPeak().offsetMeters() + " raw=["
                + receipt.rawPeak().firstSampleIndex() + "," + receipt.rawPeak().lastSampleIndex() + "]"
                + "[" + receipt.firstHighCoreSampleIndex() + "," + receipt.lastHighCoreSampleIndex()
                + "] adj=" + receipt.leftAdjacentSampleIndex() + "/" + receipt.rightAdjacentSampleIndex()).toList()
                + " modes=" + detailed.baseline().modes().size() + " suppressed=" + detailed.suppressedModes().size();
    }
}
