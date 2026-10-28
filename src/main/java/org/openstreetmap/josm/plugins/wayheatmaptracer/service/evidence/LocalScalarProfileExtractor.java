package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

/**
 * Versioned background-relative scalar profile extraction shared by modern consumers.
 *
 * <p>Complete modes require an observed two-sided raw center and agreement with at least one
 * scalar-domain filtered center. Missing support is never filled or renormalized.</p>
 */
public final class LocalScalarProfileExtractor {
    private static final double[] B3 = {1, 2, 1};
    private static final double[] B5 = {1, 4, 6, 4, 1};
    private static final double SECOND_DIFFERENCE_NOISE_RATIO = StrictMath.sqrt(3.0);

    /** One ordered physical profile sample. */
    public record Sample(double offsetMeters, double intensity, boolean valid) {
        /** Validates observed samples while permitting NaN as the explicit missing value. */
        public Sample {
            if (!Double.isFinite(offsetMeters) || valid && (!Double.isFinite(intensity)
                    || intensity < 0.0 || intensity > 1.0)) {
                throw new IllegalArgumentException("Scalar profile sample is invalid");
            }
        }
    }

    /** One complete locally significant scalar mode. */
    public record Mode(double coreMinimumMeters, double coreMaximumMeters,
            double localizationSigmaMeters, double existenceConfidence,
            double localizationConfidence, List<Double> peakOffsetsMeters,
            List<Double> nestedCenterOffsetsMeters, double scalarAmplitudeReliability) {
        /** Retains scalar mode fixtures that predate continuous amplitude reliability. */
        public Mode(double coreMinimumMeters, double coreMaximumMeters,
                double localizationSigmaMeters, double existenceConfidence,
                double localizationConfidence, List<Double> peakOffsetsMeters,
                List<Double> nestedCenterOffsetsMeters) {
            this(coreMinimumMeters, coreMaximumMeters, localizationSigmaMeters, existenceConfidence,
                localizationConfidence, peakOffsetsMeters, nestedCenterOffsetsMeters, 1.0);
        }

        /** Copies all mode evidence. */
        public Mode {
            peakOffsetsMeters = List.copyOf(peakOffsetsMeters);
            nestedCenterOffsetsMeters = List.copyOf(nestedCenterOffsetsMeters);
            if (!Double.isFinite(scalarAmplitudeReliability) || scalarAmplitudeReliability < 0.0
                    || scalarAmplitudeReliability > 1.0) {
                throw new IllegalArgumentException("Scalar amplitude reliability is invalid");
            }
        }

        /** Returns the deterministic midpoint of the measured high-core center set. */
        public double centerMeters() {
            return 0.5 * (coreMinimumMeters + coreMaximumMeters);
        }

        /** Returns physical distance from an offset to the measured center set. */
        public double distanceToCenterSet(double offsetMeters) {
            return offsetMeters < coreMinimumMeters ? coreMinimumMeters - offsetMeters
                    : offsetMeters > coreMaximumMeters ? offsetMeters - coreMaximumMeters : 0.0;
        }
    }

    /** Side of an incomplete position observation. */
    public enum CensorSide { LEFT, RIGHT }

    /** One edge-censored component, which cannot provide a measured center. */
    public record CensoredMode(CensorSide side, double boundaryOffsetMeters,
            double existenceConfidence, boolean gradientTowardEdge,
            boolean inwardExtentMeasured, double inwardSupportOffsetMeters, double shoulderThreshold) {
        /** Retains older callers without reconstructing an unavailable scalar threshold. */
        public CensoredMode(CensorSide side, double boundaryOffsetMeters,
                double existenceConfidence, boolean gradientTowardEdge,
                boolean inwardExtentMeasured, double inwardSupportOffsetMeters) {
            this(side, boundaryOffsetMeters, existenceConfidence, gradientTowardEdge,
                    inwardExtentMeasured, inwardSupportOffsetMeters, Double.NaN);
        }
        /** Retains callers that predate factual censored-interval extent. */
        public CensoredMode(CensorSide side, double boundaryOffsetMeters,
                double existenceConfidence, boolean gradientTowardEdge) {
            this(side, boundaryOffsetMeters, existenceConfidence, gradientTowardEdge,
                    false, boundaryOffsetMeters);
        }

        /** Validates the measured interval direction without inventing legacy support. */
        public CensoredMode {
            if (side == null || !Double.isFinite(boundaryOffsetMeters)
                    || !Double.isFinite(existenceConfidence) || existenceConfidence < 0.0
                    || existenceConfidence > 1.0
                    || !Double.isFinite(inwardSupportOffsetMeters)
                    || !Double.isNaN(shoulderThreshold) && (!Double.isFinite(shoulderThreshold)
                            || shoulderThreshold < 0.0 || shoulderThreshold > 1.0)
                    || inwardExtentMeasured && (side == CensorSide.LEFT
                            ? inwardSupportOffsetMeters < boundaryOffsetMeters
                            : inwardSupportOffsetMeters > boundaryOffsetMeters)) {
                throw new IllegalArgumentException("Censored scalar mode is invalid");
            }
        }
    }

    /** Frozen profile features and raw scalar diagnostics. */
    public record Result(double noiseFloor, double maximumIntensity,
            List<Mode> modes, List<CensoredMode> censoredModes) {
        /** Copies extracted component lists. */
        public Result {
            modes = List.copyOf(modes);
            censoredModes = List.copyOf(censoredModes);
        }
    }

    /** Reason a detailed mode passed every unchanged position gate except local RMS uncertainty. */
    public enum SuppressionReason { RMS_ONLY }

    /** One raw local maximum with its original profile sample interval. */
    public record RawPeak(int firstSampleIndex, int lastSampleIndex,
            double offsetMeters, double value) {
        /** Validates the immutable raw-peak census row. */
        public RawPeak {
            if (firstSampleIndex < 0 || lastSampleIndex < firstSampleIndex
                    || !Double.isFinite(offsetMeters) || !Double.isFinite(value)) {
                throw new IllegalArgumentException("Raw scalar peak is invalid");
            }
        }
    }

    /** A fully shaped but non-authoritative mode suppressed solely by RMS uncertainty. */
    public record SuppressedMode(Mode mode, int firstSampleIndex, int lastSampleIndex,
            double rawPeakOffsetMeters, double rawPeakValue, double localBackground,
            double prominence, double rmsUncertainty, double filterUncertainty,
            double fractionalThreshold, SuppressionReason reason) {
        /** Validates the detailed evidence while retaining the exact measured core shape. */
        public SuppressedMode {
            if (mode == null || firstSampleIndex < 0 || lastSampleIndex < firstSampleIndex
                    || !Double.isFinite(rawPeakOffsetMeters) || !Double.isFinite(rawPeakValue)
                    || !Double.isFinite(localBackground) || !Double.isFinite(prominence)
                    || !Double.isFinite(rmsUncertainty) || rmsUncertainty < 0.0
                    || !Double.isFinite(filterUncertainty) || filterUncertainty < 0.0
                    || !Double.isFinite(fractionalThreshold) || fractionalThreshold < 0.0
                    || reason != SuppressionReason.RMS_ONLY || !(prominence > 0.0)
                    || prominence + 1e-12 < filterUncertainty
                    || prominence + 1e-12 < fractionalThreshold
                    || !(rmsUncertainty > Math.max(filterUncertainty, fractionalThreshold))
                    || !(prominence + 1e-12 < rmsUncertainty)) {
                throw new IllegalArgumentException("Suppressed scalar mode is invalid");
            }
        }
    }

    /** Actual threshold-bracket provenance for one exact mode object in a detailed extraction. */
    public record FlankReceipt(Mode mode, int leftBracketSampleIndex, int rightBracketSampleIndex,
            boolean leftValidOutwardContinuation, boolean rightValidOutwardContinuation) {
        /** Validates indices while preserving the exact mode object identity. */
        public FlankReceipt {
            if (mode == null || leftBracketSampleIndex < -1 || rightBracketSampleIndex < -1
                    || leftValidOutwardContinuation && leftBracketSampleIndex < 1
                    || rightValidOutwardContinuation && rightBracketSampleIndex < 0) {
                throw new IllegalArgumentException("Scalar flank receipt is invalid");
            }
        }

        /** Returns whether this exact mode has measured valid continuation beyond both brackets. */
        public boolean hasTwoSidedValidOutwardContinuation() {
            return leftValidOutwardContinuation && rightValidOutwardContinuation;
        }
    }

    /** Raw high-core extent and adjacent observed samples from one exact nested-center calculation. */
    public record RawCoreReceipt(Mode mode, RawPeak rawPeak, int rawStageWinnerSampleIndex,
            int firstHighCoreSampleIndex,
            int lastHighCoreSampleIndex, int leftAdjacentSampleIndex,
            boolean leftAdjacentObservedValid, boolean leftAdjacentBelowCore,
            int rightAdjacentSampleIndex, boolean rightAdjacentObservedValid,
            boolean rightAdjacentBelowCore) {
        /** Validates immutable raw-stage provenance while preserving exact object identities. */
        public RawCoreReceipt {
            if (mode == null || rawPeak == null || rawStageWinnerSampleIndex < rawPeak.firstSampleIndex()
                    || rawStageWinnerSampleIndex > rawPeak.lastSampleIndex() || firstHighCoreSampleIndex < 0
                    || firstHighCoreSampleIndex > rawStageWinnerSampleIndex
                    || lastHighCoreSampleIndex < rawStageWinnerSampleIndex
                    || firstHighCoreSampleIndex > rawPeak.firstSampleIndex()
                    || lastHighCoreSampleIndex < rawPeak.lastSampleIndex()
                    || lastHighCoreSampleIndex < firstHighCoreSampleIndex
                    || leftAdjacentSampleIndex != firstHighCoreSampleIndex - 1
                    || rightAdjacentSampleIndex != lastHighCoreSampleIndex + 1
                    || leftAdjacentSampleIndex < -1 || rightAdjacentSampleIndex < -1
                    || leftAdjacentObservedValid && leftAdjacentSampleIndex < 0
                    || rightAdjacentObservedValid && rightAdjacentSampleIndex < 0
                    || leftAdjacentBelowCore && !leftAdjacentObservedValid
                    || rightAdjacentBelowCore && !rightAdjacentObservedValid) {
                throw new IllegalArgumentException("Scalar raw-core receipt is invalid");
            }
        }
    }

    /** Baseline extraction plus optional, non-authoritative peak/core diagnostics. */
    public record DetailedResult(Result baseline, List<SuppressedMode> suppressedModes,
            List<RawPeak> rawPeaks, List<FlankReceipt> flankReceipts,
            List<RawCoreReceipt> rawCoreReceipts) {
        /** Retains the earlier detailed-result constructor without inventing flank authority. */
        public DetailedResult(Result baseline, List<SuppressedMode> suppressedModes,
                List<RawPeak> rawPeaks) {
            this(baseline, suppressedModes, rawPeaks, List.of(), List.of());
        }

        /** Retains the flank-aware constructor without inventing raw-core provenance. */
        public DetailedResult(Result baseline, List<SuppressedMode> suppressedModes,
                List<RawPeak> rawPeaks, List<FlankReceipt> flankReceipts) {
            this(baseline, suppressedModes, rawPeaks, flankReceipts, List.of());
        }

        /** Freezes all result collections. */
        public DetailedResult {
            if (baseline == null || suppressedModes == null || rawPeaks == null || flankReceipts == null
                    || rawCoreReceipts == null) {
                throw new IllegalArgumentException("Detailed scalar result is incomplete");
            }
            suppressedModes = List.copyOf(suppressedModes);
            rawPeaks = List.copyOf(rawPeaks);
            flankReceipts = List.copyOf(flankReceipts);
            rawCoreReceipts = List.copyOf(rawCoreReceipts);
        }
    }

    /** Opaque negative-only context; it cannot be consumed as complete positional evidence. */
    public static final class ContextVetoProjection {
        private final DetailedResult projection;
        private final RawPeak requestedPeak;

        private ContextVetoProjection(DetailedResult projection, RawPeak requestedPeak) {
            this.projection = projection;
            this.requestedPeak = requestedPeak;
        }

        // These scoped views are available only to the evidence veto and its tests.
        DetailedResult detailsForVetoOnly() { return projection; }
        RawPeak requestedPeak() { return requestedPeak; }
        List<RawCoreReceipt> matchingReceipts() { return projection.rawCoreReceipts(); }
    }

    private static final class DetailedCollector {
        private final AttemptMemoryLedger.Owner resultOwner;
        private final List<SuppressedMode> suppressed;
        private final List<RawPeak> rawPeaks;
        private final List<FlankReceipt> flankReceipts;
        private final List<RawCoreReceipt> rawCoreReceipts;
        private WorkProbe work;

        DetailedCollector(AttemptMemoryLedger.Owner scratchOwner,
                AttemptMemoryLedger.Owner resultOwner, int capacity) {
            this.resultOwner = resultOwner;
            long eachList = listBytes(capacity);
            this.suppressed = allocated(scratchOwner, eachList,
                    () -> new ArrayList<>(capacity));
            this.rawPeaks = allocated(scratchOwner, eachList,
                    () -> new ArrayList<>(capacity));
            this.flankReceipts = allocated(scratchOwner, eachList,
                    () -> new ArrayList<>(capacity));
            this.rawCoreReceipts = allocated(scratchOwner, eachList,
                    () -> new ArrayList<>(capacity));
        }

        void rawPeak(RawPeak value) {
            rawPeaks.add(value);
        }

        void suppressed(SuppressedMode value) {
            suppressed.add(value);
        }

        void flank(FlankReceipt value) {
            flankReceipts.add(value);
        }

        void rawCore(RawCoreReceipt value) {
            rawCoreReceipts.add(value);
        }

        DetailedResult freeze(Result baseline) {
            long immutableBytes = AttemptMemoryLedger.objectBytes(40)
                    + listBytes(suppressed.size()) + listBytes(rawPeaks.size())
                    + listBytes(flankReceipts.size()) + listBytes(rawCoreReceipts.size());
            work.bulk(Math.addExact(Math.addExact(suppressed.size(), rawPeaks.size()),
                    Math.addExact(flankReceipts.size(), rawCoreReceipts.size())));
            return allocated(resultOwner, immutableBytes,
                    () -> new DetailedResult(baseline, suppressed, rawPeaks, flankReceipts, rawCoreReceipts));
        }
    }

    /** Extracts baseline and detailed evidence without changing the baseline decision. */
    public DetailedResult extractDetailed(List<Sample> samples, double sourcePitchMeters,
            EvidenceModelParameters.Localization parameters) {
        return extractDetailed(samples, sourcePitchMeters, parameters, null,
                CancellationProbe.NONE, ignored -> { });
    }

    /** Extracts detailed evidence retained by the caller's attempt owner on success. */
    public DetailedResult extractDetailed(List<Sample> samples, double sourcePitchMeters,
            EvidenceModelParameters.Localization parameters, AttemptMemoryLedger.Owner owner) {
        return extractDetailed(samples, sourcePitchMeters, parameters, owner,
                CancellationProbe.NONE, ignored -> { });
    }

    /** Extracts detailed evidence with bounded cancellation, memory ownership, and work charging. */
    public DetailedResult extractDetailed(List<Sample> samples, double sourcePitchMeters,
            EvidenceModelParameters.Localization parameters, AttemptMemoryLedger.Owner owner,
            CancellationProbe cancellation, LongConsumer charge) {
        return extractDetailedScoped(samples, sourcePitchMeters, parameters, owner, cancellation, charge, null);
    }

    /**
     * Retains all raw competitors and censored bands, but shapes only the preregistered peak.
     * The target is borrowed from the decision query and must remain alive with the result.
     * Only the negative context veto may consume this projection; it grants no center authority.
     */
    public ContextVetoProjection projectContextVeto(List<Sample> samples, double sourcePitchMeters,
            EvidenceModelParameters.Localization parameters, AttemptMemoryLedger.Owner owner,
            CancellationProbe cancellation, LongConsumer charge, RawPeak target) {
        if (target == null || cancellation == null || charge == null)
            throw new IllegalArgumentException("Context veto scope is incomplete");
        cancellation.checkpoint();
        var projectionOwner = owner == null ? null : owner.child("context-veto-projection");
        try {
            var details = extractDetailedScoped(samples, sourcePitchMeters, parameters, projectionOwner,
                    cancellation, charge, target);
            cancellation.checkpoint();
            charge.accept(1L);
            ContextVetoProjection result = allocated(projectionOwner, AttemptMemoryLedger.objectBytes(16),
                    () -> new ContextVetoProjection(details, target));
            if (projectionOwner != null) projectionOwner.transferTo(owner);
            return result;
        } finally {
            if (projectionOwner != null) projectionOwner.close();
        }
    }

    private DetailedResult extractDetailedScoped(List<Sample> samples, double sourcePitchMeters,
            EvidenceModelParameters.Localization parameters, AttemptMemoryLedger.Owner owner,
            CancellationProbe cancellation, LongConsumer charge, RawPeak contextTarget) {
        if (samples == null || samples.isEmpty() || !positive(sourcePitchMeters) || parameters == null
                || cancellation == null || charge == null) {
            throw new IllegalArgumentException("Scalar profile extraction inputs are incomplete");
        }
        AttemptMemoryLedger.Owner detailedOwner = owner == null ? null : owner.child("detailed-profile-extractor");
        AttemptMemoryLedger.Owner detailScratch = detailedOwner == null ? null
                : detailedOwner.child("detailed-profile-diagnostics");
        try {
            cancellation.checkpoint();
            DetailedCollector collector = allocated(detailScratch, AttemptMemoryLedger.objectBytes(48),
                    () -> new DetailedCollector(detailScratch, detailedOwner, samples.size()));
            Result baseline = extractInternal(samples, sourcePitchMeters, parameters,
                    detailedOwner, cancellation, charge, collector, contextTarget);
            DetailedResult result = collector.freeze(baseline);
            if (detailScratch != null) detailScratch.close();
            if (detailedOwner != null) detailedOwner.transferTo(owner);
            return result;
        } finally {
            if (detailedOwner != null) detailedOwner.close();
            // On refusal/cancellation close releases all partially retained diagnostic identities.
        }
    }

    /** Extracts locally significant complete and censored modes using the versioned policy. */
    public Result extract(List<Sample> samples, double sourcePitchMeters,
            EvidenceModelParameters.Localization parameters) {
        return extract(samples, sourcePitchMeters, parameters, null);
    }

    /** Extracts while charging profile-local arrays and result components to an attempt owner. */
    public Result extract(List<Sample> samples, double sourcePitchMeters,
            EvidenceModelParameters.Localization parameters,
            AttemptMemoryLedger.Owner owner) {
        return extract(samples, sourcePitchMeters, parameters, owner, CancellationProbe.NONE, ignored -> { });
    }

    /**
     * Extracts with cooperative cancellation and caller-owned bounded work accounting.
     *
     * <p>Sample, filter and shoulder scans retain the original arithmetic and tie order. Primitive
     * sorting is charged by a declared size-based proxy and checked before and after; a caller's
     * work limit also bounds its input size. These units are not measured comparison counts and
     * cancellation does not interrupt the opaque primitive sort itself.</p>
     */
    public Result extract(List<Sample> samples, double sourcePitchMeters,
            EvidenceModelParameters.Localization parameters, AttemptMemoryLedger.Owner owner,
            CancellationProbe cancellation, LongConsumer charge) {
        return extractInternal(samples, sourcePitchMeters, parameters, owner, cancellation, charge, null);
    }

    private Result extractInternal(List<Sample> samples, double sourcePitchMeters,
            EvidenceModelParameters.Localization parameters, AttemptMemoryLedger.Owner owner,
            CancellationProbe cancellation, LongConsumer charge, DetailedCollector detailed) {
        return extractInternal(samples, sourcePitchMeters, parameters, owner, cancellation, charge,
                detailed, null);
    }

    private Result extractInternal(List<Sample> samples, double sourcePitchMeters,
            EvidenceModelParameters.Localization parameters, AttemptMemoryLedger.Owner owner,
            CancellationProbe cancellation, LongConsumer charge, DetailedCollector detailed,
            RawPeak contextTarget) {
        if (samples == null || samples.isEmpty() || !positive(sourcePitchMeters) || parameters == null
                || cancellation == null || charge == null) {
            throw new IllegalArgumentException("Scalar profile extraction inputs are incomplete");
        }
        cancellation.checkpoint();
        AttemptMemoryLedger.Owner scratch = owner == null ? null : owner.child("profile-extractor-scratch");
        try {
        WorkProbe work = allocated(scratch, AttemptMemoryLedger.objectBytes(16),
                () -> new WorkProbe(cancellation, charge));
        if (detailed != null) detailed.work = work;
        work.checkpoint();
        double noiseFloor = robustNoiseFloor(samples, scratch, work);
        double[] raw = allocated(scratch,
                AttemptMemoryLedger.arrayBytes(samples.size(), Double.BYTES),
                () -> new double[samples.size()]);
        for (int index = 0; index < samples.size(); index++) {
            work.checkpoint();
            Sample sample = samples.get(index);
            raw[index] = sample.valid() ? sample.intensity() : Double.NaN;
        }
        work.bulk(raw.length);
        double maximum = Arrays.stream(raw).filter(Double::isFinite).max().orElse(noiseFloor);
        work.checkpoint();
        if (!(maximum > noiseFloor)) {
            return allocated(owner, AttemptMemoryLedger.objectBytes(32),
                    () -> new Result(noiseFloor, maximum, List.of(), List.of()));
        }
        double[] b3 = convolve(raw, B3, scratch, work);
        double[] b5 = convolve(raw, B5, scratch, work);
        List<Mode> modes = allocated(scratch, listBytes(samples.size()),
                () -> new ArrayList<>(samples.size()));
        List<CensoredMode> censored = allocated(scratch, listBytes(samples.size()),
                () -> new ArrayList<>(samples.size()));
        for (LocalPeak peak : localPeaks(raw, scratch, work)) {
            work.checkpoint();
            RawPeak detailedPeak = null;
            if (detailed != null) {
                double rawOffset = 0.5 * (samples.get(peak.first()).offsetMeters()
                        + samples.get(peak.last()).offsetMeters());
                work.bulk(1L);
                detailedPeak = allocated(detailed.resultOwner, AttemptMemoryLedger.objectBytes(24),
                        () -> new RawPeak(peak.first(), peak.last(), rawOffset, peak.value()));
                detailed.rawPeak(detailedPeak);
            }
            AttemptMemoryLedger.Owner peakScratch = scratch == null ? null
                    : scratch.child("peak-helper");
            try {
            int[] rawCoreProbe = detailed == null ? null : allocated(peakScratch,
                    AttemptMemoryLedger.arrayBytes(9, Integer.BYTES), () -> {
                        int[] probe = new int[9];
                        probe[0] = -1;
                        return probe;
                    });
            double localBackground = Math.max(noiseFloor,
                    Math.max(peak.leftMinimum(), peak.rightMinimum()));
            double prominence = peak.value() - localBackground;
            double localResponseRange = Math.max(0.0, peak.value() - noiseFloor);
            double[] uncertaintyParts = detailed == null ? null
                    : allocated(peakScratch, AttemptMemoryLedger.arrayBytes(2, Double.BYTES),
                            () -> new double[2]);
            double uncertainty = localModeUncertainty(raw, b3, b5, peak, peakScratch, work,
                    uncertaintyParts);
            double requiredProminence = Math.max(
                    parameters.localModeProminenceFraction() * localResponseRange, uncertainty);
            boolean rmsOnly = false;
            if (!(prominence > 0.0) || prominence + 1e-12 < requiredProminence) {
                if (detailed == null || uncertaintyParts == null) continue;
                double fractionThreshold = parameters.localModeProminenceFraction() * localResponseRange;
                double rmsUncertainty = uncertaintyParts[0];
                double filterUncertainty = uncertaintyParts[1];
                if (!(prominence > 0.0) || prominence + 1e-12 < fractionThreshold
                        || prominence + 1e-12 < filterUncertainty
                        || !(rmsUncertainty > Math.max(fractionThreshold, filterUncertainty))
                        || !(prominence + 1e-12 < rmsUncertainty)) {
                    continue;
                }
                rmsOnly = true;
            }
            double threshold = localBackground + parameters.localModeShoulderFraction() * prominence;
            IndexInterval interval = containingInterval(raw, peak.first(), peak.last(), threshold, work);
            int peakIndex = peak(raw, interval, work);
            boolean leftCensored = interval.first() == 0
                    || interval.first() > 0 && !Double.isFinite(raw[interval.first() - 1]);
            boolean rightCensored = interval.last() == raw.length - 1
                    || interval.last() + 1 < raw.length && !Double.isFinite(raw[interval.last() + 1]);
            double existence = clamp(prominence / Math.max(localResponseRange, 1e-12));
            if (leftCensored || rightCensored) {
                // Detailed RMS-only candidates are diagnostics only and cannot create baseline
                // censored evidence that the unchanged extractor rejected before this gate.
                if (rmsOnly) continue;
                boolean right = rightCensored && !leftCensored;
                int edge = right ? interval.last() : interval.first();
                int inward = right ? interval.first() : interval.last();
                CensorSide side = right ? CensorSide.RIGHT : CensorSide.LEFT;
                double boundary = samples.get(edge).offsetMeters();
                double inwardSupport = samples.get(inward).offsetMeters();
                boolean gradient = gradientTowardEdge(raw, edge, right);
                censored.add(allocated(owner, AttemptMemoryLedger.objectBytes(48),
                        () -> new CensoredMode(side, boundary, existence, gradient,
                                true, inwardSupport, threshold)));
                continue;
            }
            if (contextTarget != null) {
                work.checkpoint();
                if (!ProbabilisticRawCoreContextVeto.samePeak(contextTarget, detailedPeak)) continue;
            }
            List<Double> centers = allocated(peakScratch,
                    listBytes(3) + 3L * AttemptMemoryLedger.objectBytes(8),
                    () -> new ArrayList<>(3));
            addNestedCenter(centers, raw, samples, interval, localBackground,
                    parameters.localModeCoreFraction(), true, work, rawCoreProbe);
            addNestedCenter(centers, b3, samples, interval, localBackground,
                    parameters.localModeCoreFraction(), false, work, null);
            addNestedCenter(centers, b5, samples, interval, localBackground,
                    parameters.localModeCoreFraction(), false, work, null);
            if (centers.size() < 2) {
                continue;
            }
            double center = median(centers, peakScratch, work);
            List<Double> deviations = allocated(peakScratch,
                    listBytes(centers.size()) + Math.multiplyExact(centers.size(),
                        AttemptMemoryLedger.objectBytes(8)),
                    () -> new ArrayList<>(centers.size()));
            for (double value : centers) {
                work.checkpoint();
                deviations.add(Math.abs(value - center));
            }
            double deviation = 1.4826 * median(deviations, peakScratch, work);
            double sigma = Math.max(sourcePitchMeters * 0.5, deviation);
            double halfCenter = Math.min(sourcePitchMeters * 0.5,
                    Math.max(sourcePitchMeters * 0.25, deviation));
            double amplitudeReliability = prominence / (prominence
                    + parameters.scalarAmplitudeHalfResponse());
            double peakOffset = samples.get(peakIndex).offsetMeters();
            work.bulk(2L * centers.size());
            double confidence = agreementConfidence(centers, sourcePitchMeters);
            List<Double> peakOffsets = allocated(owner,
                    listBytes(1) + AttemptMemoryLedger.objectBytes(8),
                    () -> List.of(peakOffset));
            List<Double> nestedCenters = allocated(owner,
                    listBytes(centers.size())
                        + Math.multiplyExact(centers.size(), AttemptMemoryLedger.objectBytes(8)),
                    () -> List.copyOf(centers));
            Mode measured = allocated(rmsOnly ? detailed.resultOwner : owner, AttemptMemoryLedger.objectBytes(64),
                    () -> new Mode(center - halfCenter, center + halfCenter, sigma, existence,
                        confidence, peakOffsets, nestedCenters, amplitudeReliability));
            if (rawCoreProbe != null && rawCoreProbe[0] >= peak.first()
                    && rawCoreProbe[0] <= peak.last() && rawCoreProbe[1] <= peak.first()
                    && rawCoreProbe[2] >= peak.last()) {
                retainRawCoreReceipt(measured, detailedPeak, rawCoreProbe, detailed, work);
            }
            if (rmsOnly) {
                double[] components = uncertaintyParts;
                work.bulk(1L);
                SuppressedMode suppressed = allocated(detailed.resultOwner, AttemptMemoryLedger.objectBytes(80),
                        () -> new SuppressedMode(measured, peak.first(), peak.last(), peakOffset,
                                peak.value(), localBackground, prominence, components[0], components[1],
                                parameters.localModeProminenceFraction() * localResponseRange,
                                SuppressionReason.RMS_ONLY));
                detailed.suppressed(suppressed);
                retainFlankReceipt(measured, interval, raw, threshold, detailed, work);
            } else {
                modes.add(measured);
                if (detailed != null) retainFlankReceipt(measured, interval, raw, threshold, detailed, work);
            }
            } finally {
                if (peakScratch != null) peakScratch.close();
            }
        }
        long resultBytes = AttemptMemoryLedger.objectBytes(32)
                + listBytes(modes.size()) + listBytes(censored.size());
        return allocated(owner, resultBytes,
                () -> new Result(noiseFloor, maximum, modes, censored));
        } finally {
            if (scratch != null) scratch.close();
        }
    }

    private static void retainFlankReceipt(Mode mode, IndexInterval interval, double[] raw,
            double threshold, DetailedCollector detailed, WorkProbe work) {
        int leftBracket = interval.first() - 1;
        int rightBracket = interval.last() + 1;
        int leftOutward = leftBracket - 1;
        int rightOutward = rightBracket + 1;
        work.bulk(4L);
        boolean leftValid = leftBracket >= 0 && leftOutward >= 0
                && Double.isFinite(raw[leftBracket]) && raw[leftBracket] < threshold
                && Double.isFinite(raw[leftOutward]);
        boolean rightValid = rightBracket < raw.length && rightOutward < raw.length
                && Double.isFinite(raw[rightBracket]) && raw[rightBracket] < threshold
                && Double.isFinite(raw[rightOutward]);
        FlankReceipt receipt = allocated(detailed.resultOwner, AttemptMemoryLedger.objectBytes(18),
                () -> new FlankReceipt(mode, leftBracket, rightBracket, leftValid, rightValid));
        detailed.flank(receipt);
    }

    private static List<LocalPeak> localPeaks(double[] values, AttemptMemoryLedger.Owner owner,
            WorkProbe work) {
        List<LocalPeak> result = allocated(owner,
                listBytes(values.length) + Math.multiplyExact(values.length,
                    AttemptMemoryLedger.objectBytes(40)),
                () -> new ArrayList<>(values.length));
        int index = 0;
        while (index < values.length) {
            work.checkpoint();
            if (!Double.isFinite(values[index])) {
                index++;
                continue;
            }
            int first = index;
            int last = index;
            while (last + 1 < values.length && Double.isFinite(values[last + 1])
                    && Math.abs(values[last + 1] - values[first]) <= 1e-12) {
                work.checkpoint();
                last++;
            }
            double left = first > 0 && Double.isFinite(values[first - 1])
                    ? values[first - 1] : Double.NEGATIVE_INFINITY;
            double right = last + 1 < values.length && Double.isFinite(values[last + 1])
                    ? values[last + 1] : Double.NEGATIVE_INFINITY;
            if (values[first] > left + 1e-12 && values[first] > right + 1e-12) {
                double leftMinimum = first == 0 ? 0.0 : localMinimum(values, first, -1, values[first], work);
                double rightMinimum = last == values.length - 1 ? 0.0
                        : localMinimum(values, last, 1, values[first], work);
                result.add(new LocalPeak(first, last, values[first], leftMinimum, rightMinimum));
            }
            index = last + 1;
        }
        return result;
    }

    private static double localMinimum(double[] values, int start, int direction, double peakValue,
            WorkProbe work) {
        double minimum = peakValue;
        boolean measuredShoulder = false;
        for (int index = start + direction; index >= 0 && index < values.length; index += direction) {
            work.checkpoint();
            if (!Double.isFinite(values[index])) {
                return measuredShoulder ? minimum : 0.0;
            }
            if (values[index] > peakValue + 1e-12) {
                break;
            }
            measuredShoulder = true;
            minimum = Math.min(minimum, values[index]);
        }
        return minimum;
    }

    private static IndexInterval containingInterval(double[] values, int firstPeak, int lastPeak,
            double threshold, WorkProbe work) {
        int first = firstPeak;
        int last = lastPeak;
        while (first > 0 && Double.isFinite(values[first - 1]) && values[first - 1] >= threshold) {
            work.checkpoint();
            first--;
        }
        while (last + 1 < values.length && Double.isFinite(values[last + 1])
                && values[last + 1] >= threshold) {
            work.checkpoint();
            last++;
        }
        return new IndexInterval(first, last);
    }

    private static void addNestedCenter(List<Double> centers, double[] values, List<Sample> samples,
            IndexInterval search, double localBackground, double coreFraction, boolean requiredFine,
            WorkProbe work, int[] rawCoreProbe) {
        int peakIndex = peak(values, search, work);
        if (!Double.isFinite(values[peakIndex])) {
            return;
        }
        double localMaximum = values[peakIndex];
        double threshold = localBackground + coreFraction * Math.max(0.0, localMaximum - localBackground);
        int left = peakIndex;
        int right = peakIndex;
        while (left > search.first() && Double.isFinite(values[left - 1])
                && values[left - 1] >= threshold) {
            work.checkpoint();
            left--;
        }
        while (right < search.last() && Double.isFinite(values[right + 1])
                && values[right + 1] >= threshold) {
            work.checkpoint();
            right++;
        }
        boolean twoSided = left > 0 && right + 1 < values.length
                && Double.isFinite(values[left - 1]) && Double.isFinite(values[right + 1]);
        if (twoSided && (!requiredFine || localMaximum > localBackground)) {
            centers.add(0.5 * (samples.get(left).offsetMeters() + samples.get(right).offsetMeters()));
            if (rawCoreProbe != null) {
                int leftAdjacent = left - 1;
                int rightAdjacent = right + 1;
                boolean leftValid = Double.isFinite(values[leftAdjacent]);
                boolean rightValid = Double.isFinite(values[rightAdjacent]);
                rawCoreProbe[0] = peakIndex;
                rawCoreProbe[1] = left;
                rawCoreProbe[2] = right;
                rawCoreProbe[3] = leftAdjacent;
                rawCoreProbe[4] = rightAdjacent;
                rawCoreProbe[5] = leftValid ? 1 : 0;
                rawCoreProbe[6] = leftValid && values[leftAdjacent] < threshold ? 1 : 0;
                rawCoreProbe[7] = rightValid ? 1 : 0;
                rawCoreProbe[8] = rightValid && values[rightAdjacent] < threshold ? 1 : 0;
            }
        }
    }

    private static void retainRawCoreReceipt(Mode mode, RawPeak rawPeak, int[] probe,
            DetailedCollector detailed, WorkProbe work) {
        work.bulk(1L);
        RawCoreReceipt receipt = allocated(detailed.resultOwner, AttemptMemoryLedger.objectBytes(40),
                () -> new RawCoreReceipt(mode, rawPeak, probe[0], probe[1], probe[2], probe[3],
                        probe[5] != 0, probe[6] != 0, probe[4], probe[7] != 0, probe[8] != 0));
        detailed.rawCore(receipt);
    }

    private static double localModeUncertainty(double[] raw, double[] b3, double[] b5,
            LocalPeak peak, AttemptMemoryLedger.Owner owner, WorkProbe work) {
        return localModeUncertainty(raw, b3, b5, peak, owner, work, null);
    }

    private static double localModeUncertainty(double[] raw, double[] b3, double[] b5,
            LocalPeak peak, AttemptMemoryLedger.Owner owner, WorkProbe work,
            double[] components) {
        AttemptMemoryLedger.Owner helper = owner == null ? null : owner.child("local-uncertainty");
        try {
        int first = Math.max(0, peak.first() - 6);
        int last = Math.min(raw.length - 1, peak.last() + 6);
        int maximumVariation = Math.max(0, last - first);
        List<Double> variation = allocated(helper,
                listBytes(maximumVariation) + Math.multiplyExact(maximumVariation,
                    AttemptMemoryLedger.objectBytes(8)),
                () -> new ArrayList<>(maximumVariation));
        for (int index = first + 2; index <= last; index++) {
            work.checkpoint();
            if (Double.isFinite(raw[index - 2]) && Double.isFinite(raw[index - 1])
                    && Double.isFinite(raw[index])) {
                // A coherent affine slope is signal, not sample noise. For
                // independent noise, second differences have three times the
                // variance of first differences; normalize that variance before
                // taking RMS so interpolation zeros cannot erase noise energy.
                variation.add(Math.abs((raw[index] - raw[index - 1])
                        - (raw[index - 1] - raw[index - 2])) / SECOND_DIFFERENCE_NOISE_RATIO);
            }
        }
        double squareSum = 0.0;
        for (double value : variation) {
            work.checkpoint();
            squareSum += value * value;
        }
        double localNoise = variation.isEmpty() ? 0.0 : StrictMath.sqrt(squareSum / variation.size());
        int peakIndex = peak(raw, new IndexInterval(peak.first(), peak.last()), work);
        double filterUncertainty = 0.0;
        if (Double.isFinite(b3[peakIndex])) {
            filterUncertainty = Math.max(filterUncertainty, Math.abs(raw[peakIndex] - b3[peakIndex]));
        }
        if (Double.isFinite(b3[peakIndex]) && Double.isFinite(b5[peakIndex])) {
            filterUncertainty = Math.max(filterUncertainty, Math.abs(b3[peakIndex] - b5[peakIndex]));
        }
        double rmsUncertainty = 3.0 * localNoise;
        if (components != null) {
            if (components.length != 2) throw new IllegalArgumentException("Uncertainty output must have two values");
            work.bulk(2L);
            components[0] = rmsUncertainty;
            components[1] = filterUncertainty;
        }
        return Math.max(rmsUncertainty, filterUncertainty);
        } finally {
            if (helper != null) helper.close();
        }
    }

    private static double[] convolve(double[] values, double[] kernel,
            AttemptMemoryLedger.Owner owner, WorkProbe work) {
        double[] result = allocated(owner,
                AttemptMemoryLedger.arrayBytes(values.length, Double.BYTES),
                () -> new double[values.length]);
        Arrays.fill(result, Double.NaN);
        int radius = kernel.length / 2;
        double denominator = Arrays.stream(kernel).sum();
        for (int index = radius; index < values.length - radius; index++) {
            work.checkpoint();
            double sum = 0.0;
            boolean valid = true;
            for (int tap = -radius; tap <= radius; tap++) {
                work.checkpoint();
                if (!Double.isFinite(values[index + tap])) {
                    valid = false;
                    break;
                }
                sum += kernel[tap + radius] * values[index + tap];
            }
            if (valid) {
                result[index] = sum / denominator;
            }
        }
        return result;
    }

    private static int peak(double[] values, IndexInterval interval, WorkProbe work) {
        int selected = interval.first();
        for (int index = interval.first() + 1; index <= interval.last(); index++) {
            work.checkpoint();
            if (values[index] > values[selected]) {
                selected = index;
            }
        }
        return selected;
    }

    private static boolean gradientTowardEdge(double[] values, int edge, boolean right) {
        int inside = right ? edge - 1 : edge + 1;
        return inside >= 0 && inside < values.length && Double.isFinite(values[edge])
                && Double.isFinite(values[inside]) && values[edge] > values[inside];
    }

    private static double robustNoiseFloor(List<Sample> samples,
            AttemptMemoryLedger.Owner owner, WorkProbe work) {
        AttemptMemoryLedger.Owner helper = owner == null ? null : owner.child("noise-floor");
        try {
        int validCount = 0;
        for (Sample sample : samples) {
            work.checkpoint();
            if (sample.valid()) validCount++;
        }
        int retainedValidCount = validCount;
        double[] valid = allocated(helper,
                AttemptMemoryLedger.arrayBytes(validCount, Double.BYTES),
                () -> new double[retainedValidCount]);
        int index = 0;
        for (Sample sample : samples) {
            work.checkpoint();
            if (sample.valid()) valid[index++] = sample.intensity();
        }
        work.sort(valid);
        return valid.length == 0 ? 0.0 : valid[(int) Math.floor(0.2 * (valid.length - 1))];
        } finally {
            if (helper != null) helper.close();
        }
    }

    private static double median(List<Double> values, AttemptMemoryLedger.Owner owner, WorkProbe work) {
        AttemptMemoryLedger.Owner helper = owner == null ? null : owner.child("median");
        try {
        double[] sorted = allocated(helper,
                AttemptMemoryLedger.arrayBytes(values.size(), Double.BYTES),
                () -> new double[values.size()]);
        for (int index = 0; index < values.size(); index++) {
            work.checkpoint();
            sorted[index] = values.get(index);
        }
        work.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0 ? 0.5 * (sorted[middle - 1] + sorted[middle]) : sorted[middle];
        } finally {
            if (helper != null) helper.close();
        }
    }

    private static double agreementConfidence(List<Double> centers, double sourcePitchMeters) {
        double minimum = centers.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double maximum = centers.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        return clamp(1.0 - (maximum - minimum) / Math.max(sourcePitchMeters, 1e-12));
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private record WorkProbe(CancellationProbe cancellation, LongConsumer charge) {
        void checkpoint() {
            cancellation.checkpoint();
            charge.accept(1L);
        }

        void bulk(long units) {
            cancellation.checkpoint();
            charge.accept(units);
        }

        void sort(double[] values) {
            // Declared admission proxy, not an exact or worst-case Java comparison count.
            long levels = Math.max(1L, 64L - Long.numberOfLeadingZeros(values.length));
            bulk(Math.multiplyExact(values.length, levels));
            Arrays.sort(values);
            checkpoint();
        }
    }

    private static long listBytes(long size) {
        return Math.addExact(AttemptMemoryLedger.objectBytes(16),
                AttemptMemoryLedger.referenceArrayBytes(size));
    }

    private static <T> T allocated(AttemptMemoryLedger.Owner owner, long bytes,
            Supplier<T> allocation) {
        if (owner == null) return allocation.get();
        AttemptMemoryLedger.Reservation reservation;
        try {
            reservation = owner.reserve(bytes);
        } catch (AttemptMemoryLedger.ResourceLimitException exception) {
            throw new ResourceLimitException(exception);
        }
        try {
            T value = allocation.get();
            reservation.adopt(value);
            return value;
        } catch (RuntimeException | Error exception) {
            reservation.close();
            throw exception;
        }
    }

    /** Typed profile-extraction allocation refusal translated by B's owning engine boundary. */
    public static final class ResourceLimitException extends RuntimeException {
        private ResourceLimitException(AttemptMemoryLedger.ResourceLimitException cause) {
            super("sampled profiles: " + cause.getMessage(), cause);
        }
    }

    private record LocalPeak(int first, int last, double value, double leftMinimum,
            double rightMinimum) { }
    private record IndexInterval(int first, int last) { }
}
