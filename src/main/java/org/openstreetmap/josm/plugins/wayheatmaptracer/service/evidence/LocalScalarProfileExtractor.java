package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
            double existenceConfidence, boolean gradientTowardEdge) { }

    /** Frozen profile features and raw scalar diagnostics. */
    public record Result(double noiseFloor, double maximumIntensity,
            List<Mode> modes, List<CensoredMode> censoredModes) {
        /** Copies extracted component lists. */
        public Result {
            modes = List.copyOf(modes);
            censoredModes = List.copyOf(censoredModes);
        }
    }

    /** Extracts locally significant complete and censored modes using the versioned policy. */
    public Result extract(List<Sample> samples, double sourcePitchMeters,
            EvidenceModelParameters.Localization parameters) {
        if (samples == null || samples.isEmpty() || !positive(sourcePitchMeters) || parameters == null) {
            throw new IllegalArgumentException("Scalar profile extraction inputs are incomplete");
        }
        double noiseFloor = robustNoiseFloor(samples);
        double[] raw = samples.stream().mapToDouble(sample -> sample.valid()
                ? sample.intensity() : Double.NaN).toArray();
        double maximum = Arrays.stream(raw).filter(Double::isFinite).max().orElse(noiseFloor);
        if (!(maximum > noiseFloor)) {
            return new Result(noiseFloor, maximum, List.of(), List.of());
        }
        double[] b3 = convolve(raw, B3);
        double[] b5 = convolve(raw, B5);
        List<Mode> modes = new ArrayList<>();
        List<CensoredMode> censored = new ArrayList<>();
        for (LocalPeak peak : localPeaks(raw)) {
            double localBackground = Math.max(noiseFloor,
                    Math.max(peak.leftMinimum(), peak.rightMinimum()));
            double prominence = peak.value() - localBackground;
            double localResponseRange = Math.max(0.0, peak.value() - noiseFloor);
            double uncertainty = localModeUncertainty(raw, b3, b5, peak);
            double requiredProminence = Math.max(
                    parameters.localModeProminenceFraction() * localResponseRange, uncertainty);
            if (!(prominence > 0.0) || prominence + 1e-12 < requiredProminence) {
                continue;
            }
            double threshold = localBackground + parameters.localModeShoulderFraction() * prominence;
            IndexInterval interval = containingInterval(raw, peak.first(), peak.last(), threshold);
            int peakIndex = peak(raw, interval);
            boolean leftCensored = interval.first() == 0
                    || interval.first() > 0 && !Double.isFinite(raw[interval.first() - 1]);
            boolean rightCensored = interval.last() == raw.length - 1
                    || interval.last() + 1 < raw.length && !Double.isFinite(raw[interval.last() + 1]);
            double existence = clamp(prominence / Math.max(localResponseRange, 1e-12));
            if (leftCensored || rightCensored) {
                boolean right = rightCensored && !leftCensored;
                int edge = right ? interval.last() : interval.first();
                censored.add(new CensoredMode(right ? CensorSide.RIGHT : CensorSide.LEFT,
                        samples.get(edge).offsetMeters(), existence, gradientTowardEdge(raw, edge, right)));
                continue;
            }
            List<Double> centers = new ArrayList<>();
            addNestedCenter(centers, raw, samples, interval, localBackground,
                    parameters.localModeCoreFraction(), true);
            addNestedCenter(centers, b3, samples, interval, localBackground,
                    parameters.localModeCoreFraction(), false);
            addNestedCenter(centers, b5, samples, interval, localBackground,
                    parameters.localModeCoreFraction(), false);
            if (centers.size() < 2) {
                continue;
            }
            double center = median(centers);
            double deviation = 1.4826 * median(centers.stream()
                    .map(value -> Math.abs(value - center)).toList());
            double sigma = Math.max(sourcePitchMeters * 0.5, deviation);
            double halfCenter = Math.min(sourcePitchMeters * 0.5,
                    Math.max(sourcePitchMeters * 0.25, deviation));
            double amplitudeReliability = prominence / (prominence
                    + parameters.scalarAmplitudeHalfResponse());
            modes.add(new Mode(center - halfCenter, center + halfCenter, sigma, existence,
                    agreementConfidence(centers, sourcePitchMeters),
                    List.of(samples.get(peakIndex).offsetMeters()), centers, amplitudeReliability));
        }
        return new Result(noiseFloor, maximum, modes, censored);
    }

    private static List<LocalPeak> localPeaks(double[] values) {
        List<LocalPeak> result = new ArrayList<>();
        int index = 0;
        while (index < values.length) {
            if (!Double.isFinite(values[index])) {
                index++;
                continue;
            }
            int first = index;
            int last = index;
            while (last + 1 < values.length && Double.isFinite(values[last + 1])
                    && Math.abs(values[last + 1] - values[first]) <= 1e-12) {
                last++;
            }
            double left = first > 0 && Double.isFinite(values[first - 1])
                    ? values[first - 1] : Double.NEGATIVE_INFINITY;
            double right = last + 1 < values.length && Double.isFinite(values[last + 1])
                    ? values[last + 1] : Double.NEGATIVE_INFINITY;
            if (values[first] > left + 1e-12 && values[first] > right + 1e-12) {
                double leftMinimum = first == 0 ? 0.0 : localMinimum(values, first, -1, values[first]);
                double rightMinimum = last == values.length - 1 ? 0.0
                        : localMinimum(values, last, 1, values[first]);
                result.add(new LocalPeak(first, last, values[first], leftMinimum, rightMinimum));
            }
            index = last + 1;
        }
        return result;
    }

    private static double localMinimum(double[] values, int start, int direction, double peakValue) {
        double minimum = peakValue;
        boolean measuredShoulder = false;
        for (int index = start + direction; index >= 0 && index < values.length; index += direction) {
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
            double threshold) {
        int first = firstPeak;
        int last = lastPeak;
        while (first > 0 && Double.isFinite(values[first - 1]) && values[first - 1] >= threshold) {
            first--;
        }
        while (last + 1 < values.length && Double.isFinite(values[last + 1])
                && values[last + 1] >= threshold) {
            last++;
        }
        return new IndexInterval(first, last);
    }

    private static void addNestedCenter(List<Double> centers, double[] values, List<Sample> samples,
            IndexInterval search, double localBackground, double coreFraction, boolean requiredFine) {
        int peakIndex = peak(values, search);
        if (!Double.isFinite(values[peakIndex])) {
            return;
        }
        double localMaximum = values[peakIndex];
        double threshold = localBackground + coreFraction * Math.max(0.0, localMaximum - localBackground);
        int left = peakIndex;
        int right = peakIndex;
        while (left > search.first() && Double.isFinite(values[left - 1])
                && values[left - 1] >= threshold) {
            left--;
        }
        while (right < search.last() && Double.isFinite(values[right + 1])
                && values[right + 1] >= threshold) {
            right++;
        }
        boolean twoSided = left > 0 && right + 1 < values.length
                && Double.isFinite(values[left - 1]) && Double.isFinite(values[right + 1]);
        if (twoSided && (!requiredFine || localMaximum > localBackground)) {
            centers.add(0.5 * (samples.get(left).offsetMeters() + samples.get(right).offsetMeters()));
        }
    }

    private static double localModeUncertainty(double[] raw, double[] b3, double[] b5, LocalPeak peak) {
        int first = Math.max(0, peak.first() - 6);
        int last = Math.min(raw.length - 1, peak.last() + 6);
        List<Double> variation = new ArrayList<>();
        for (int index = first + 1; index <= last; index++) {
            if (Double.isFinite(raw[index - 1]) && Double.isFinite(raw[index])) {
                variation.add(Math.abs(raw[index] - raw[index - 1]));
            }
        }
        double localNoise = variation.isEmpty() ? 0.0 : median(variation);
        int peakIndex = peak(raw, new IndexInterval(peak.first(), peak.last()));
        double filterUncertainty = 0.0;
        if (Double.isFinite(b3[peakIndex])) {
            filterUncertainty = Math.max(filterUncertainty, Math.abs(raw[peakIndex] - b3[peakIndex]));
        }
        if (Double.isFinite(b3[peakIndex]) && Double.isFinite(b5[peakIndex])) {
            filterUncertainty = Math.max(filterUncertainty, Math.abs(b3[peakIndex] - b5[peakIndex]));
        }
        return Math.max(3.0 * localNoise, filterUncertainty);
    }

    private static double[] convolve(double[] values, double[] kernel) {
        double[] result = new double[values.length];
        Arrays.fill(result, Double.NaN);
        int radius = kernel.length / 2;
        double denominator = Arrays.stream(kernel).sum();
        for (int index = radius; index < values.length - radius; index++) {
            double sum = 0.0;
            boolean valid = true;
            for (int tap = -radius; tap <= radius; tap++) {
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

    private static int peak(double[] values, IndexInterval interval) {
        int selected = interval.first();
        for (int index = interval.first() + 1; index <= interval.last(); index++) {
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

    private static double robustNoiseFloor(List<Sample> samples) {
        double[] valid = samples.stream().filter(Sample::valid).mapToDouble(Sample::intensity).sorted().toArray();
        return valid.length == 0 ? 0.0 : valid[(int) Math.floor(0.2 * (valid.length - 1))];
    }

    private static double median(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0 ? 0.5 * (sorted[middle - 1] + sorted[middle]) : sorted[middle];
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

    private record LocalPeak(int first, int last, double value, double leftMinimum,
            double rightMinimum) { }
    private record IndexInterval(int first, int last) { }
}
