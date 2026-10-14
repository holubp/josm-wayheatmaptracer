package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CancellationException;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

/** R02/R18: a negative context witness needs one core, but every raw competitor. */
class V022ContextVetoProjectionTest {
    private final LocalScalarProfileExtractor extractor = new LocalScalarProfileExtractor();

    @Test
    void projectionShapesOnlyThePreregisteredPeak() throws Exception {
        var samples = fiveSeparatedRidges();
        var full = extractor.extractDetailed(samples, 1.0, parameters());
        assertEquals(5, full.rawCoreReceipts().size(), "five independently measured ridges");
        var target = full.rawCoreReceipts().stream()
                .min(java.util.Comparator.comparingDouble(r -> Math.abs(r.rawPeak().offsetMeters())))
                .orElseThrow().rawPeak();
        var projected = project(samples, target, ignored -> { });
        assertEquals(1, matchingReceipts(projected).size(),
                "unrelated complete cores are not negative witnesses for this target");
    }

    @Test
    void scopedProjectionFitsABudgetThatFullContextExtractionCannot() throws Exception {
        var samples = fiveSeparatedRidges();
        var referenceWork = new AtomicLong();
        var full = extractor.extractDetailed(samples, 1.0, parameters(), null,
                CancellationProbe.NONE, referenceWork::addAndGet);
        var target = full.rawCoreReceipts().stream()
                .min(java.util.Comparator.comparingDouble(r -> Math.abs(r.rawPeak().offsetMeters())))
                .orElseThrow().rawPeak();
        long limit = referenceWork.get() - 50L;
        assertThrows(FeatureRefusal.class, () -> extractor.extractDetailed(samples, 1.0,
                parameters(), null, CancellationProbe.NONE, budget(limit)));
        var projected = project(samples, target, budget(limit));
        assertEquals(1, matchingReceipts(projected).size());
    }

    @Test
    void expandedOutsideDecisionCoreStillVetoesAndCompetitorsRemainExact() {
        // Retain the independently established clipped-core counterexample at native .25m pitch.
        var contextSamples = broadProfile(Double.NaN);
        var decisionSamples = contextSamples.stream().map(s -> Math.abs(s.offsetMeters()) <= .25 ? s
                : new LocalScalarProfileExtractor.Sample(s.offsetMeters(), Double.NaN, false)).toList();
        var decision = extractor.extractDetailed(decisionSamples, .25, parameters());
        var selected = decision.baseline().modes().stream()
                .filter(m -> m.coreMinimumMeters() <= 0 && m.coreMaximumMeters() >= 0)
                .findFirst().orElseThrow();
        var target = decision.rawCoreReceipts().stream().filter(r -> r.mode() == selected)
                .findFirst().orElseThrow().rawPeak();
        assertTrue(compareVeto(decisionSamples, contextSamples, decision, selected, target, .25));
        assertFalse(compareVeto(decisionSamples, broadProfile(.13), decision, selected, target, .25));
        assertFalse(compareVeto(decisionSamples, decisionSamples, decision, selected, target, .25));
    }

    @Test
    void rawCompetitorsCensoredBandsAndMatchedPointersAreRetained() {
        var samples = new ArrayList<>(triangle(false));
        samples.set(0, new LocalScalarProfileExtractor.Sample(-6.0, 0.9, true));
        samples.set(1, new LocalScalarProfileExtractor.Sample(-5.0, 0.8, true));
        samples.set(2, new LocalScalarProfileExtractor.Sample(-4.0, 0.6, true));
        var full = extractor.extractDetailed(samples, 1.0, parameters());
        var target = full.rawCoreReceipts().stream()
                .filter(r -> Math.abs(r.rawPeak().offsetMeters()) < 0.1).findFirst().orElseThrow().rawPeak();
        var projected = project(samples, target, ignored -> { });
        var view = projected.detailsForVetoOnly();
        assertEquals(full.rawPeaks(), view.rawPeaks());
        assertEquals(full.baseline().censoredModes(), view.baseline().censoredModes());
        assertFalse(view.baseline().censoredModes().isEmpty());
        for (var receipt : projected.matchingReceipts()) {
            assertTrue(view.rawPeaks().stream().anyMatch(p -> p == receipt.rawPeak()));
            assertTrue(view.baseline().modes().stream().anyMatch(m -> m == receipt.mode())
                    || view.suppressedModes().stream().anyMatch(s -> s.mode() == receipt.mode()));
        }
        assertSame(target, projected.requestedPeak());
        assertFalse(java.util.Arrays.stream(projected.getClass().getMethods())
                .anyMatch(m -> m.getReturnType() == LocalScalarProfileExtractor.DetailedResult.class));
    }

    @Test
    void measuredOverlappingPeakAndInvalidSupportCannotBecomeNegativeWitnesses() {
        var broad = broadProfile(Double.NaN);
        var decisionSamples = broad.stream().map(s -> Math.abs(s.offsetMeters()) <= .25 ? s
                : new LocalScalarProfileExtractor.Sample(s.offsetMeters(), Double.NaN, false)).toList();
        var decision = extractor.extractDetailed(decisionSamples, .25, parameters());
        var selected = decision.baseline().modes().get(0);
        var target = decision.rawCoreReceipts().stream().filter(r -> r.mode() == selected)
                .findFirst().orElseThrow().rawPeak();
        assertTrue(compareVeto(decisionSamples, broad, decision, selected, target, .25));
        var competitor = new ArrayList<>(broad);
        var sample = competitor.get(68);
        competitor.set(68, new LocalScalarProfileExtractor.Sample(sample.offsetMeters(), .18, true));
        var competing = extractor.extractDetailed(competitor, .25, parameters());
        assertTrue(competing.rawPeaks().size() >= 2, "actual nearby brighter peak remains in the census");
        assertFalse(compareVeto(decisionSamples, competitor, decision, selected, target, .25));
        var censored = new ArrayList<>(broad);
        // Invalid native support cannot provide the original broad witness. The
        // existing full-veto adversary separately isolates a competing censored
        // band at the selected peak; this case checks extraction/veto parity.
        sample = censored.get(72);
        censored.set(72, new LocalScalarProfileExtractor.Sample(sample.offsetMeters(), Double.NaN, false));
        var censoredDetails = extractor.extractDetailed(censored, .25, parameters());
        assertFalse(censoredDetails.baseline().censoredModes().isEmpty());
        assertFalse(compareVeto(decisionSamples, censored, decision, selected, target, .25));
    }

    @Test
    void equalValuedForeignAndBitAlteredTargetsCannotVeto() {
        var contextSamples = broadProfile(Double.NaN);
        var decisionSamples = contextSamples.stream().map(s -> Math.abs(s.offsetMeters()) <= .25 ? s
                : new LocalScalarProfileExtractor.Sample(s.offsetMeters(), Double.NaN, false)).toList();
        var decision = extractor.extractDetailed(decisionSamples, .25, parameters());
        var selected = decision.baseline().modes().get(0);
        var target = decision.rawCoreReceipts().stream().filter(r -> r.mode() == selected)
                .findFirst().orElseThrow().rawPeak();
        assertTrue(compareVeto(decisionSamples, contextSamples, decision, selected, target, .25));
        var foreign = new LocalScalarProfileExtractor.RawPeak(target.firstSampleIndex(), target.lastSampleIndex(),
                target.offsetMeters(), target.value());
        assertEquals(target, foreign);
        var altered = new LocalScalarProfileExtractor.RawPeak(target.firstSampleIndex(), target.lastSampleIndex(),
                target.offsetMeters(), Math.nextUp(target.value()));
        var absent = new LocalScalarProfileExtractor.RawPeak(0, 0, -8, .99);
        for (var scopedTarget : List.of(foreign, altered, absent)) {
            var projection = extractor.projectContextVeto(contextSamples, .25, parameters(), null,
                    CancellationProbe.NONE, ignored -> { }, scopedTarget);
            assertFalse(ProbabilisticRawCoreContextVeto.contradicts(decisionSamples, contextSamples,
                    decision, projection, selected, CancellationProbe.NONE, ignored -> { },
                    (winner, bracket) -> {
                        for (int index = Math.min(winner, bracket); index <= Math.max(winner, bracket); index++)
                            if (!decisionSamples.get(index).valid()) return false;
                        return true;
                    }), "only the original query-owned peak grants scope");
        }
    }

    @Test
    void successfulProjectionOwnershipAndPeakBudgetRefusalReleaseEverything() {
        var samples = fiveSeparatedRidges();
        var target = extractor.extractDetailed(samples, 1.0, parameters()).rawCoreReceipts().get(2).rawPeak();
        var measure = new AttemptMemoryLedger(1_000_000);
        long peak;
        try (var owner = measure.rootOwner()) {
            var projection = extractor.projectContextVeto(samples, 1.0, parameters(), owner,
                    CancellationProbe.NONE, ignored -> { }, target);
            assertEquals(1, projection.matchingReceipts().size());
            assertTrue(measure.currentBytes() > 0, "retained graph belongs to the caller");
            peak = measure.peakBytes();
        }
        assertEquals(0, measure.currentBytes());
        var insufficient = new AttemptMemoryLedger(peak - 1);
        try (var owner = insufficient.rootOwner()) {
            var refusal = assertThrows(RuntimeException.class, () -> extractor.projectContextVeto(samples, 1.0,
                    parameters(), owner, CancellationProbe.NONE, ignored -> { }, target));
            assertTrue(refusal instanceof AttemptMemoryLedger.ResourceLimitException
                    || refusal instanceof LocalScalarProfileExtractor.ResourceLimitException
                    && refusal.getCause() instanceof AttemptMemoryLedger.ResourceLimitException);
            assertEquals(0, insufficient.currentBytes());
        }
    }

    @Test
    void lateWorkRefusalClosesAllProjectionChildren() {
        var samples = fiveSeparatedRidges();
        var target = extractor.extractDetailed(samples, 1.0, parameters()).rawCoreReceipts().get(2).rawPeak();
        var work = new AtomicLong();
        project(samples, target, work::addAndGet);
        var ledger = new AttemptMemoryLedger(1_000_000);
        try (var owner = ledger.rootOwner()) {
            assertThrows(FeatureRefusal.class, () -> extractor.projectContextVeto(samples, 1.0,
                    parameters(), owner, CancellationProbe.NONE, budget(work.get() - 1L), target));
            assertEquals(0, ledger.currentBytes());
        }
    }

    @Test
    void cancellationAndMemoryRefusalReleasePartialEvidence() {
        var samples = fiveSeparatedRidges();
        var target = extractor.extractDetailed(samples, 1.0, parameters()).rawCoreReceipts().get(2).rawPeak();
        var ledger = new AttemptMemoryLedger(1_000_000);
        var checkpoints = new AtomicLong();
        try (var owner = ledger.rootOwner()) {
            assertThrows(CancellationException.class, () -> extractor.projectContextVeto(samples, 1.0,
                    parameters(), owner, () -> {
                        if (checkpoints.incrementAndGet() == 100) throw new CancellationException();
                        return false;
                    }, ignored -> { }, target));
            assertEquals(0, ledger.currentBytes());
        }
        var tiny = new AttemptMemoryLedger(256);
        try (var owner = tiny.rootOwner()) {
            var refusal = assertThrows(LocalScalarProfileExtractor.ResourceLimitException.class,
                    () -> extractor.projectContextVeto(
                    samples, 1.0, parameters(), owner, CancellationProbe.NONE, ignored -> { }, target));
            assertTrue(refusal.getCause() instanceof AttemptMemoryLedger.ResourceLimitException);
            assertEquals(0, tiny.currentBytes());
        }
    }

    private boolean compareVeto(List<LocalScalarProfileExtractor.Sample> decisionSamples,
            List<LocalScalarProfileExtractor.Sample> contextSamples,
            LocalScalarProfileExtractor.DetailedResult decision,
            LocalScalarProfileExtractor.Mode selected, LocalScalarProfileExtractor.RawPeak target, double pitch) {
        var full = extractor.extractDetailed(contextSamples, pitch, parameters());
        var projected = extractor.projectContextVeto(contextSamples, pitch, parameters(), null,
                CancellationProbe.NONE, ignored -> { }, target);
        assertEquals(full.rawPeaks(), projected.detailsForVetoOnly().rawPeaks());
        assertEquals(full.baseline().censoredModes(), projected.detailsForVetoOnly().baseline().censoredModes());
        ProbabilisticRawCoreContextVeto.DecisionPathAuthority authority = (winner, bracket) -> {
            for (int index = Math.min(winner, bracket); index <= Math.max(winner, bracket); index++)
                if (!decisionSamples.get(index).valid()) return false;
            return true;
        };
        boolean expected = ProbabilisticRawCoreContextVeto.contradicts(decisionSamples, contextSamples,
                decision, full, selected, CancellationProbe.NONE, ignored -> { }, authority);
        assertEquals(expected, ProbabilisticRawCoreContextVeto.contradicts(decisionSamples, contextSamples,
                decision, projected, selected, CancellationProbe.NONE, ignored -> { }, authority));
        return expected;
    }

    private static List<LocalScalarProfileExtractor.Sample> triangle(boolean sibling) {
        // Context core threshold is .4; .42 avoids a binary64 equality boundary.
        double[] values = {0, .1, .2, .3, .42, .46, .5, .46, .42, .3, .2, .1, 0};
        if (sibling) values[3] = .46;
        List<LocalScalarProfileExtractor.Sample> rows = new ArrayList<>();
        for (int index = 0; index < values.length; index++)
            rows.add(new LocalScalarProfileExtractor.Sample(index - 6.0, values[index], true));
        return List.copyOf(rows);
    }

    private static List<LocalScalarProfileExtractor.Sample> broadProfile(double exterior) {
        List<LocalScalarProfileExtractor.Sample> rows = new ArrayList<>();
        for (int index = 0; index <= 128; index++) {
            double offset = -8.0 + .125 * index;
            double lower = StrictMath.floor(offset / .25) * .25;
            double fraction = (offset - lower) / .25;
            double a = nativeBroadValue(lower, exterior);
            double b = nativeBroadValue(lower + .25, exterior);
            rows.add(new LocalScalarProfileExtractor.Sample(offset, a + fraction * (b - a), true));
        }
        return List.copyOf(rows);
    }

    private static double nativeBroadValue(double offset, double exterior) {
        if (Double.isFinite(exterior) && Math.abs(offset) > .25) return exterior;
        double z = offset / .75;
        return .02 + .12 * StrictMath.exp(-.5 * z * z);
    }

    private static List<LocalScalarProfileExtractor.Sample> decisionWindow(
            List<LocalScalarProfileExtractor.Sample> source) {
        return source.stream().map(s -> Math.abs(s.offsetMeters()) <= 2.0 ? s
                : new LocalScalarProfileExtractor.Sample(s.offsetMeters(), Double.NaN, false)).toList();
    }

    private static EvidenceModelParameters.Localization parameters() {
        return EvidenceModelParameters.defaults().localization();
    }

    private static List<LocalScalarProfileExtractor.Sample> fiveSeparatedRidges() {
        List<LocalScalarProfileExtractor.Sample> samples = new ArrayList<>();
        for (int index = 0; index <= 64; index++) {
            double offset = -16.0 + 0.5 * index;
            double value = 0.0;
            for (int center = -12; center <= 12; center += 6) {
                double distance = (offset - center) / 1.2;
                value += 0.8 * StrictMath.exp(-0.5 * distance * distance);
            }
            samples.add(new LocalScalarProfileExtractor.Sample(offset, value, true));
        }
        return List.copyOf(samples);
    }

    private LocalScalarProfileExtractor.ContextVetoProjection project(
            List<LocalScalarProfileExtractor.Sample> samples,
            LocalScalarProfileExtractor.RawPeak target, LongConsumer charge) {
        return extractor.projectContextVeto(samples, 1.0, parameters(), null,
                CancellationProbe.NONE, charge, target);
    }

    @SuppressWarnings("unchecked")
    private static List<LocalScalarProfileExtractor.RawCoreReceipt> matchingReceipts(Object result)
            throws Exception {
        return ((LocalScalarProfileExtractor.ContextVetoProjection) result).matchingReceipts();
    }

    private static LongConsumer budget(long maximum) {
        var used = new AtomicLong();
        return units -> {
            if (units > maximum - used.get()) throw new FeatureRefusal();
            used.addAndGet(units);
        };
    }

    private static final class FeatureRefusal extends RuntimeException { }
}
