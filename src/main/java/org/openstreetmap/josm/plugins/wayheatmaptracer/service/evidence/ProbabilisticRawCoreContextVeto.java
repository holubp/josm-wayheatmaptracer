package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import java.util.List;
import java.util.function.LongConsumer;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Pure negative witness for a matched raw core that contradicts decision-owned closure.
 *
 * <p>The caller remains responsible for binding both lists to one immutable scalar field,
 * capture transform and shared sampling frame. This seam verifies their indexed lattice and
 * factual samples; the decision-path callback supplies geometric authorization.</p>
 */
public final class ProbabilisticRawCoreContextVeto {
    private ProbabilisticRawCoreContextVeto() { }

    /** Checks the complete segment between raw-stage winner and bracket, not just its endpoints. */
    @FunctionalInterface
    public interface DecisionPathAuthority {
        boolean segmentAuthorized(int winnerSampleIndex, int bracketSampleIndex);
    }

    /** Returns only a factual negative witness; unknown inputs preserve the caller's measurement. */
    public static boolean contradicts(List<LocalScalarProfileExtractor.Sample> decisionSamples,
            List<LocalScalarProfileExtractor.Sample> contextSamples,
            LocalScalarProfileExtractor.DetailedResult decision,
            LocalScalarProfileExtractor.DetailedResult context,
            LocalScalarProfileExtractor.Mode selectedDecisionMode,
            CancellationProbe cancellation, LongConsumer featureCharge,
            DecisionPathAuthority decisionPathAuthority) {
        return contradictsScoped(decisionSamples, contextSamples, decision, context, selectedDecisionMode,
                cancellation, featureCharge, decisionPathAuthority, null);
    }

    /** Consumes only a negative projection bound to this decision query's exact raw-peak identity. */
    public static boolean contradicts(List<LocalScalarProfileExtractor.Sample> decisionSamples,
            List<LocalScalarProfileExtractor.Sample> contextSamples,
            LocalScalarProfileExtractor.DetailedResult decision,
            LocalScalarProfileExtractor.ContextVetoProjection context,
            LocalScalarProfileExtractor.Mode selectedDecisionMode,
            CancellationProbe cancellation, LongConsumer featureCharge,
            DecisionPathAuthority decisionPathAuthority) {
        if (context == null) return false;
        return contradictsScoped(decisionSamples, contextSamples, decision, context.detailsForVetoOnly(),
                selectedDecisionMode, cancellation, featureCharge, decisionPathAuthority, context.requestedPeak());
    }

    private static boolean contradictsScoped(List<LocalScalarProfileExtractor.Sample> decisionSamples,
            List<LocalScalarProfileExtractor.Sample> contextSamples,
            LocalScalarProfileExtractor.DetailedResult decision,
            LocalScalarProfileExtractor.DetailedResult context,
            LocalScalarProfileExtractor.Mode selectedDecisionMode,
            CancellationProbe cancellation, LongConsumer featureCharge,
            DecisionPathAuthority decisionPathAuthority,
            LocalScalarProfileExtractor.RawPeak requestedPeak) {
        if (decisionSamples == null || contextSamples == null || decision == null || context == null
                || selectedDecisionMode == null || cancellation == null || featureCharge == null
                || decisionPathAuthority == null || decisionSamples.isEmpty()
                || decisionSamples.size() != contextSamples.size()) return false;
        int count = decisionSamples.size();
        for (int index = 0; index < count; index++) {
            checkpoint(cancellation, featureCharge);
            var left = decisionSamples.get(index);
            var right = contextSamples.get(index);
            if (Double.doubleToLongBits(left.offsetMeters()) != Double.doubleToLongBits(right.offsetMeters())) {
                return false;
            }
            if (left.valid() && (!right.valid()
                    || Double.doubleToLongBits(left.intensity()) != Double.doubleToLongBits(right.intensity()))) {
                return false;
            }
        }

        LocalScalarProfileExtractor.RawCoreReceipt original = null;
        int originalReceiptCount = 0;
        for (var receipt : decision.rawCoreReceipts()) {
            checkpoint(cancellation, featureCharge);
            if (receipt.mode() == selectedDecisionMode) {
                original = receipt;
                originalReceiptCount++;
            }
        }
        if (originalReceiptCount != 1 || !validClosedReceipt(original, decisionSamples,
                cancellation, featureCharge)
                || identityCount(decision.rawPeaks(), original.rawPeak(), cancellation, featureCharge) != 1
                || !shapeOwned(decision, original, cancellation, featureCharge)) {
            return false;
        }
        if (requestedPeak != null) {
            checkpoint(cancellation, featureCharge);
            if (requestedPeak != original.rawPeak()) return false;
        }
        checkpoint(cancellation, featureCharge);
        if (!decisionPathAuthority.segmentAuthorized(original.rawStageWinnerSampleIndex(),
                original.leftAdjacentSampleIndex())) return false;
        checkpoint(cancellation, featureCharge);
        if (!decisionPathAuthority.segmentAuthorized(original.rawStageWinnerSampleIndex(),
                    original.rightAdjacentSampleIndex())) return false;

        LocalScalarProfileExtractor.RawCoreReceipt matched = null;
        int matchCount = 0;
        for (var receipt : context.rawCoreReceipts()) {
            checkpoint(cancellation, featureCharge);
            if (samePeak(original.rawPeak(), receipt.rawPeak())
                    && original.rawStageWinnerSampleIndex() == receipt.rawStageWinnerSampleIndex()) {
                matched = receipt;
                matchCount++;
            }
        }
        if (matchCount != 1 || !validClosedReceipt(matched, contextSamples, cancellation, featureCharge)
                || identityCount(context.rawPeaks(), matched.rawPeak(), cancellation, featureCharge) != 1
                || !shapeOwned(context, matched, cancellation, featureCharge)
                || hasCensoredCompetitor(context.baseline().censoredModes(), matched.rawPeak().offsetMeters(),
                        cancellation, featureCharge)
                || hasDistinctPeakIntersectingCore(context.rawPeaks(), matched,
                        cancellation, featureCharge)) return false;

        boolean contradictsLeft = inside(original.leftAdjacentSampleIndex(), matched);
        boolean contradictsRight = inside(original.rightAdjacentSampleIndex(), matched);
        if (contradictsLeft) {
            checkpoint(cancellation, featureCharge);
            if (!decisionPathAuthority.segmentAuthorized(matched.rawStageWinnerSampleIndex(),
                    matched.leftAdjacentSampleIndex())) return true;
        }
        if (contradictsRight) {
            checkpoint(cancellation, featureCharge);
            return !decisionPathAuthority.segmentAuthorized(matched.rawStageWinnerSampleIndex(),
                    matched.rightAdjacentSampleIndex());
        }
        return false;
    }

    private static void checkpoint(CancellationProbe cancellation, LongConsumer charge) {
        cancellation.checkpoint();
        charge.accept(1L);
    }

    private static int identityCount(List<?> values, Object target,
            CancellationProbe cancellation, LongConsumer charge) {
        int count = 0;
        for (Object value : values) {
            checkpoint(cancellation, charge);
            if (value == target) count++;
        }
        return count;
    }

    static boolean samePeak(LocalScalarProfileExtractor.RawPeak left,
            LocalScalarProfileExtractor.RawPeak right) {
        return left.firstSampleIndex() == right.firstSampleIndex()
                && left.lastSampleIndex() == right.lastSampleIndex()
                && Double.doubleToLongBits(left.offsetMeters()) == Double.doubleToLongBits(right.offsetMeters())
                && Double.doubleToLongBits(left.value()) == Double.doubleToLongBits(right.value());
    }

    private static boolean validClosedReceipt(LocalScalarProfileExtractor.RawCoreReceipt receipt,
            List<LocalScalarProfileExtractor.Sample> samples, CancellationProbe cancellation,
            LongConsumer charge) {
        if (receipt == null || receipt.rawPeak().lastSampleIndex() >= samples.size()
                || receipt.rawStageWinnerSampleIndex() >= samples.size()
                || receipt.leftAdjacentSampleIndex() < 0 || receipt.rightAdjacentSampleIndex() >= samples.size()
                || !receipt.leftAdjacentObservedValid() || !receipt.leftAdjacentBelowCore()
                || !receipt.rightAdjacentObservedValid() || !receipt.rightAdjacentBelowCore()) return false;
        int first = receipt.firstHighCoreSampleIndex();
        int last = receipt.lastHighCoreSampleIndex();
        int winner = receipt.rawStageWinnerSampleIndex();
        if (receipt.rawPeak().firstSampleIndex() < 0 || first < 0 || last >= samples.size()
                || first > last || winner < first || winner > last
                || first > receipt.rawPeak().firstSampleIndex()
                || last < receipt.rawPeak().lastSampleIndex()
                || receipt.leftAdjacentSampleIndex() != first - 1
                || receipt.rightAdjacentSampleIndex() != last + 1
                || receipt.rawPeak().firstSampleIndex() > receipt.rawPeak().lastSampleIndex()
                || winner < receipt.rawPeak().firstSampleIndex()
                || winner > receipt.rawPeak().lastSampleIndex()) return false;
        double peakValue = receipt.rawPeak().value();
        double firstOffset = samples.get(receipt.rawPeak().firstSampleIndex()).offsetMeters();
        double lastOffset = samples.get(receipt.rawPeak().lastSampleIndex()).offsetMeters();
        if (Double.doubleToLongBits(receipt.rawPeak().offsetMeters())
                != Double.doubleToLongBits(0.5 * (firstOffset + lastOffset))) return false;
        for (int index = receipt.rawPeak().firstSampleIndex(); index <= receipt.rawPeak().lastSampleIndex(); index++) {
            checkpoint(cancellation, charge);
            var sample = samples.get(index);
            if (!sample.valid() || Double.doubleToLongBits(sample.intensity())
                    != Double.doubleToLongBits(peakValue)) return false;
        }
        for (int index = first; index <= last; index++) {
            checkpoint(cancellation, charge);
            if (!samples.get(index).valid()) return false;
        }
        return samples.get(receipt.leftAdjacentSampleIndex()).valid()
                && samples.get(receipt.rightAdjacentSampleIndex()).valid();
    }

    private static boolean shapeOwned(LocalScalarProfileExtractor.DetailedResult detailed,
            LocalScalarProfileExtractor.RawCoreReceipt receipt,
            CancellationProbe cancellation, LongConsumer charge) {
        int baseline = identityCount(detailed.baseline().modes(), receipt.mode(), cancellation, charge);
        if (baseline > 1) return false;
        int suppressed = 0;
        for (var item : detailed.suppressedModes()) {
            checkpoint(cancellation, charge);
            if (item.mode() == receipt.mode()
                    && item.firstSampleIndex() == receipt.rawPeak().firstSampleIndex()
                    && item.lastSampleIndex() == receipt.rawPeak().lastSampleIndex()
                    && Double.doubleToLongBits(item.rawPeakOffsetMeters())
                        == Double.doubleToLongBits(receipt.rawPeak().offsetMeters())
                    && Double.doubleToLongBits(item.rawPeakValue())
                        == Double.doubleToLongBits(receipt.rawPeak().value())) suppressed++;
        }
        // This grants only a negative witness for the exact already selected shape.
        // It cannot admit a suppression or replace the caller's original measurement.
        return baseline + suppressed == 1;
    }

    private static boolean hasCensoredCompetitor(List<LocalScalarProfileExtractor.CensoredMode> censored,
            double peakOffset, CancellationProbe cancellation, LongConsumer charge) {
        for (var item : censored) {
            checkpoint(cancellation, charge);
            double low = item.inwardExtentMeasured()
                    ? Math.min(item.boundaryOffsetMeters(), item.inwardSupportOffsetMeters())
                    : item.boundaryOffsetMeters();
            double high = item.inwardExtentMeasured()
                    ? Math.max(item.boundaryOffsetMeters(), item.inwardSupportOffsetMeters())
                    : item.boundaryOffsetMeters();
            if (peakOffset >= low && peakOffset <= high) return true;
        }
        return false;
    }

    private static boolean hasDistinctPeakIntersectingCore(List<LocalScalarProfileExtractor.RawPeak> peaks,
            LocalScalarProfileExtractor.RawCoreReceipt matched, CancellationProbe cancellation,
            LongConsumer charge) {
        for (var peak : peaks) {
            checkpoint(cancellation, charge);
            if (peak != matched.rawPeak() && peak.firstSampleIndex() <= matched.lastHighCoreSampleIndex()
                    && peak.lastSampleIndex() >= matched.firstHighCoreSampleIndex()) return true;
        }
        return false;
    }

    private static boolean inside(int sampleIndex, LocalScalarProfileExtractor.RawCoreReceipt receipt) {
        return sampleIndex >= receipt.firstHighCoreSampleIndex()
                && sampleIndex <= receipt.lastHighCoreSampleIndex();
    }
}
