package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorBand.BoundaryCompleteness;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorBand.BoundarySide;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

/**
 * Extracts nested, relative-intensity corridor observations without relying on a single brightest pixel.
 */
public final class CorridorExtractor {
    static final double[] RELATIVE_LEVELS = {0.60, 0.72, 0.84, 0.92};
    private static final double NUMERICAL_EMPTY_PROMINENCE = 1e-6;

    /**
     * Creates a stateless nested-corridor extractor.
     */
    public CorridorExtractor() {
        // Stateless extractor.
    }

    /**
     * Extracts corridor observations from all sampled profiles.
     *
     * @param profiles complete scalar cross-sections
     * @return profile-aligned corridor observations
     */
    public List<CorridorProfile> extract(List<CrossSectionProfile> profiles) {
        return extract(profiles, Double.NaN);
    }

    /**
     * Extracts corridor observations while retaining the native source-pixel localization floor.
     *
     * @param profiles complete scalar cross-sections
     * @param sourcePixelSizePx native source-pixel pitch in sampled-raster pixels
     * @return profile-aligned corridor observations
     */
    public List<CorridorProfile> extract(List<CrossSectionProfile> profiles, double sourcePixelSizePx) {
        List<CorridorProfile> result = new ArrayList<>(profiles.size());
        for (int i = 0; i < profiles.size(); i++) {
            result.add(extract(i, profiles.get(i), sourcePixelSizePx));
        }
        return List.copyOf(result);
    }

    /**
     * Extracts corridor observations from one sampled profile.
     *
     * @param index longitudinal profile index
     * @param profile complete scalar cross-section
     * @return corridor profile, including an explicit unsupported state
     */
    public CorridorProfile extract(int index, CrossSectionProfile profile) {
        return extract(index, profile, Double.NaN);
    }

    /**
     * Extracts one profile with a factual native source-pixel localization scale.
     *
     * @param index longitudinal profile index
     * @param profile complete scalar cross-section
     * @param sourcePixelSizePx native source-pixel pitch in sampled-raster pixels
     * @return corridor profile with explicit search-boundary evidence
     */
    public CorridorProfile extract(int index, CrossSectionProfile profile, double sourcePixelSizePx) {
        List<IntensitySample> samples = profile.intensitySamples().stream()
            .sorted(Comparator.comparingDouble(IntensitySample::offsetPx))
            .toList();
        List<IntensitySample> validSamples = samples.stream().filter(IntensitySample::insideRaster).toList();
        if (validSamples.isEmpty()) {
            return new CorridorProfile(index, profile, List.of(), 0.0, 0.0, 0.0, false);
        }

        ProfileStatistics stats = statistics(validSamples);
        if (stats.prominence() <= NUMERICAL_EMPTY_PROMINENCE) {
            return new CorridorProfile(index, profile, List.of(), stats.maximum(), stats.noiseFloor(),
                stats.prominence(), true);
        }

        List<List<Interval>> levels = new ArrayList<>(RELATIVE_LEVELS.length);
        for (double relativeLevel : RELATIVE_LEVELS) {
            double threshold = stats.noiseFloor() + relativeLevel * stats.prominence();
            levels.add(intervals(samples, threshold));
        }

        List<CorridorBand> bands = new ArrayList<>();
        int lowIndex = 0;
        for (Interval shoulder : levels.get(0)) {
            List<Interval> highestChildren = levels.get(levels.size() - 1).stream()
                .filter(interval -> overlaps(shoulder, interval))
                .toList();
            if (highestChildren.isEmpty()) {
                highestChildren = List.of(strongestNestedInterval(levels, shoulder));
            }
            List<String> childIds = new ArrayList<>();
            int childIndex = 0;
            for (Interval childCore : highestChildren) {
                String id = "band-" + lowIndex + '-' + childIndex++;
                childIds.add(id);
                bands.add(buildBand(id, samples, stats, shoulder, childCore, levels, false, List.of(),
                    sourcePixelSizePx));
            }
            if (highestChildren.size() > 1) {
                Interval combinedCore = new Interval(
                    highestChildren.get(0).start(),
                    highestChildren.get(highestChildren.size() - 1).end(),
                    highestChildren.get(0).minimumOffsetPx(),
                    highestChildren.get(highestChildren.size() - 1).maximumOffsetPx()
                );
                bands.add(buildBand("parent-" + lowIndex, samples, stats, shoulder, combinedCore,
                    levels, true, childIds, sourcePixelSizePx));
            }
            lowIndex++;
        }
        return new CorridorProfile(index, profile, bands, stats.maximum(), stats.noiseFloor(), stats.prominence(), true);
    }

    private CorridorBand buildBand(
        String id,
        List<IntensitySample> samples,
        ProfileStatistics stats,
        Interval shoulder,
        Interval core,
        List<List<Interval>> levels,
        boolean parent,
        List<String> childIds,
        double sourcePixelSizePx
    ) {
        List<WeightedCenter> centers = new ArrayList<>();
        for (int level = 0; level < levels.size(); level++) {
            double weight = RELATIVE_LEVELS[level] * RELATIVE_LEVELS[level];
            for (Interval interval : levels.get(level)) {
                if (overlaps(core, interval) && overlaps(shoulder, interval)) {
                    centers.add(new WeightedCenter(midpoint(interval), weight));
                }
            }
        }
        if (parent) {
            centers.add(new WeightedCenter(midpoint(core), 2.0));
            centers.add(new WeightedCenter(midpoint(shoulder), 1.0));
        }
        double center = weightedMedian(centers);
        List<Double> nestedCenters = centers.stream().map(WeightedCenter::center).toList();
        double shoulderMin = shoulder.minimumOffsetPx();
        double shoulderMax = shoulder.maximumOffsetPx();
        double coreMin = core.minimumOffsetPx();
        double coreMax = core.maximumOffsetPx();
        double peak = maximum(samples, shoulder);
        double sampleStep = sampleStep(samples);
        double localizationResolution = Double.isFinite(sourcePixelSizePx) && sourcePixelSizePx > 0.0
            ? Math.max(sampleStep, sourcePixelSizePx)
            : sampleStep;
        int gradientStride = Math.max(1, (int) Math.round(localizationResolution / sampleStep));
        double centerSpread = centerSpread(nestedCenters, center);
        double leftGradient = boundaryGradient(samples, shoulder.start(), -gradientStride, stats.prominence());
        double rightGradient = boundaryGradient(samples, shoulder.end(), gradientStride, stats.prominence());
        double gradientStrength = clamp((leftGradient + rightGradient) / 2.0);
        double gradientBalance = leftGradient + rightGradient <= 1e-9
            ? 0.0
            : clamp(1.0 - Math.abs(leftGradient - rightGradient) / (leftGradient + rightGradient));
        double scaleAgreement = scaleAgreement(samples, shoulder, stats.prominence());
        double amplitude = stats.prominence() / (stats.prominence() + 0.08);
        double existence = clamp(0.55 * amplitude + 0.25 * gradientStrength + 0.20 * scaleAgreement);
        double nestedAgreement = Math.exp(-centerSpread / Math.max(localizationResolution, 1.0));
        double shoulderWidth = Math.max(localizationResolution, shoulderMax - shoulderMin);
        double coreWidth = Math.max(0.0, coreMax - coreMin);
        double coreDefinition = clamp(1.0 - coreWidth / (shoulderWidth + localizationResolution));
        double localization = clamp(0.35 * gradientBalance + 0.40 * nestedAgreement + 0.25 * coreDefinition);
        double uncertainty = Math.max(localizationResolution / 2.0,
            centerSpread + (1.0 - localization) * shoulderWidth * 0.35);
        BoundarySide coreCensoring = boundarySide(core, samples);
        BoundarySide shoulderCensoring = boundarySide(shoulder, samples);
        BoundaryCompleteness completeness = completeness(coreCensoring, shoulderCensoring);
        BoundarySide boundarySide = coreCensoring != BoundarySide.NONE ? coreCensoring : shoulderCensoring;
        localization = completeness.hasMeasuredCenter() ? localization : 0.0;
        uncertainty = completeness.hasMeasuredCenter() ? uncertainty : Math.max(uncertainty, shoulderWidth);
        double valleyRatio = parent ? valleyRatio(samples, core) : 1.0;
        return new CorridorBand(id, center, shoulderMin, shoulderMax, coreMin, coreMax, nestedCenters,
            peak, stats.noiseFloor(), valleyRatio, gradientStrength, gradientBalance, scaleAgreement,
            existence, localization, uncertainty, parent, childIds, completeness, boundarySide);
    }

    private BoundaryCompleteness completeness(BoundarySide core, BoundarySide shoulder) {
        if (core == BoundarySide.BOTH) {
            return BoundaryCompleteness.FULLY_CENSORED;
        }
        if (core != BoundarySide.NONE) {
            return BoundaryCompleteness.CORE_CENSORED;
        }
        return shoulder == BoundarySide.NONE
            ? BoundaryCompleteness.COMPLETE : BoundaryCompleteness.SHOULDER_CENSORED;
    }

    private BoundarySide boundarySide(Interval interval, List<IntensitySample> samples) {
        boolean left = interval.start() == 0 || !samples.get(interval.start() - 1).insideRaster();
        boolean right = interval.end() == samples.size() - 1
            || !samples.get(interval.end() + 1).insideRaster();
        return BoundarySide.of(left, right);
    }

    private ProfileStatistics statistics(List<IntensitySample> samples) {
        List<Double> values = samples.stream().map(IntensitySample::standardFilteredIntensity).sorted().toList();
        double maximum = values.get(values.size() - 1);
        double lowerQuartile = values.get((int) Math.floor((values.size() - 1) * 0.25));
        double median = values.get((int) Math.floor((values.size() - 1) * 0.50));
        double noiseFloor = clamp(Math.min(median * 0.80, lowerQuartile * 1.25));
        noiseFloor = Math.min(noiseFloor, maximum);
        return new ProfileStatistics(maximum, noiseFloor, Math.max(0.0, maximum - noiseFloor));
    }

    private List<Interval> intervals(List<IntensitySample> samples, double threshold) {
        List<Interval> result = new ArrayList<>();
        int start = -1;
        double minimumOffset = Double.NaN;
        for (int i = 0; i < samples.size(); i++) {
            IntensitySample sample = samples.get(i);
            if (sample.insideRaster() && sample.standardFilteredIntensity() >= threshold) {
                if (start < 0) {
                    start = i;
                    minimumOffset = i == 0 || !samples.get(i - 1).insideRaster()
                        ? sample.offsetPx()
                        : thresholdCrossing(samples.get(i - 1), samples.get(i), threshold);
                }
            } else if (start >= 0) {
                result.add(new Interval(start, i - 1, minimumOffset,
                    sample.insideRaster()
                        ? thresholdCrossing(samples.get(i - 1), sample, threshold)
                        : samples.get(i - 1).offsetPx()));
                start = -1;
                minimumOffset = Double.NaN;
            }
        }
        if (start >= 0) {
            result.add(new Interval(start, samples.size() - 1, minimumOffset,
                samples.get(samples.size() - 1).offsetPx()));
        }
        return result;
    }

    private double thresholdCrossing(IntensitySample left, IntensitySample right, double threshold) {
        double leftValue = left.standardFilteredIntensity();
        double rightValue = right.standardFilteredIntensity();
        double difference = rightValue - leftValue;
        if (Math.abs(difference) <= 1e-12) {
            return (left.offsetPx() + right.offsetPx()) / 2.0;
        }
        double fraction = clamp((threshold - leftValue) / difference);
        return left.offsetPx() + fraction * (right.offsetPx() - left.offsetPx());
    }

    private Interval strongestNestedInterval(List<List<Interval>> levels, Interval shoulder) {
        for (int level = levels.size() - 1; level >= 0; level--) {
            for (Interval interval : levels.get(level)) {
                if (overlaps(shoulder, interval)) {
                    return interval;
                }
            }
        }
        return shoulder;
    }

    private double scaleAgreement(List<IntensitySample> samples, Interval interval, double prominence) {
        if (prominence <= 1e-9) {
            return 0.0;
        }
        double disagreement = 0.0;
        int count = 0;
        for (int i = interval.start(); i <= interval.end(); i++) {
            IntensitySample sample = samples.get(i);
            if (!sample.insideRaster()) {
                continue;
            }
            disagreement += Math.abs(sample.nativeIntensity() - sample.lightFilteredIntensity());
            disagreement += Math.abs(sample.lightFilteredIntensity() - sample.standardFilteredIntensity());
            count += 2;
        }
        return clamp(1.0 - disagreement / Math.max(1e-9, count * prominence));
    }

    private double boundaryGradient(List<IntensitySample> samples, int boundary, int direction, double prominence) {
        int outside = Math.max(0, Math.min(samples.size() - 1, boundary + direction));
        double gradient = samples.get(boundary).standardFilteredIntensity()
            - samples.get(outside).standardFilteredIntensity();
        return clamp(Math.max(0.0, gradient) / Math.max(1e-9, prominence * 0.35));
    }

    private double valleyRatio(List<IntensitySample> samples, Interval interval) {
        double peak = maximum(samples, interval);
        double valley = Double.POSITIVE_INFINITY;
        for (int i = interval.start(); i <= interval.end(); i++) {
            valley = Math.min(valley, samples.get(i).standardFilteredIntensity());
        }
        return peak <= 1e-9 ? 0.0 : clamp(valley / peak);
    }

    private double maximum(List<IntensitySample> samples, Interval interval) {
        double maximum = 0.0;
        for (int i = interval.start(); i <= interval.end(); i++) {
            if (samples.get(i).insideRaster()) {
                maximum = Math.max(maximum, samples.get(i).standardFilteredIntensity());
            }
        }
        return maximum;
    }

    private double midpoint(Interval interval) {
        return (interval.minimumOffsetPx() + interval.maximumOffsetPx()) / 2.0;
    }

    private double weightedMedian(List<WeightedCenter> centers) {
        List<WeightedCenter> sorted = centers.stream().sorted(Comparator.comparingDouble(WeightedCenter::center)).toList();
        double total = sorted.stream().mapToDouble(WeightedCenter::weight).sum();
        double cumulative = 0.0;
        for (WeightedCenter center : sorted) {
            cumulative += center.weight();
            if (cumulative >= total / 2.0) {
                return center.center();
            }
        }
        return sorted.isEmpty() ? 0.0 : sorted.get(sorted.size() - 1).center();
    }

    private double centerSpread(List<Double> centers, double center) {
        if (centers.isEmpty()) {
            return 0.0;
        }
        double sum = centers.stream().mapToDouble(value -> square(value - center)).sum();
        return Math.sqrt(sum / centers.size());
    }

    private double sampleStep(List<IntensitySample> samples) {
        return samples.size() < 2 ? 1.0 : Math.max(1e-6, Math.abs(samples.get(1).offsetPx() - samples.get(0).offsetPx()));
    }

    private boolean overlaps(Interval left, Interval right) {
        return left.minimumOffsetPx() <= right.maximumOffsetPx()
            && right.minimumOffsetPx() <= left.maximumOffsetPx();
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double square(double value) {
        return value * value;
    }

    private record Interval(int start, int end, double minimumOffsetPx, double maximumOffsetPx) {
    }

    private record WeightedCenter(double center, double weight) {
    }

    private record ProfileStatistics(double maximum, double noiseFloor, double prominence) {
    }
}
