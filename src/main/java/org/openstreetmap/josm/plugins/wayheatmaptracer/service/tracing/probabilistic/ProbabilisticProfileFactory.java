package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;

/** Samples full scalar cross-sections and extracts deterministic finite observation hypotheses. */
public final class ProbabilisticProfileFactory {
    private static final double[] LEVELS = {0.72, 0.84, 0.92};
    private static final double[] B3 = {1, 2, 1};
    private static final double[] B5 = {1, 4, 6, 4, 1};

    /**
     * Samples complete physical profiles along an immutable source polyline.
     *
     * @param sourcePolyline selected way geometry in the snapshot metric frame
     * @param configuredStepMeters desired longitudinal step in ground metres
     * @param searchHalfWidthMeters ordinary authorized search radius
     * @param fixedEndpoints whether first and last source positions remain exact
     * @param evidence immutable scalar snapshot
     * @param field selected scalar evidence field
     * @return ordered profiles independent of Corridor A membership
     */
    public List<ProbabilisticProfile> create(List<MetricPoint> sourcePolyline,
        double configuredStepMeters, double searchHalfWidthMeters, boolean fixedEndpoints,
        EvidenceSnapshot evidence, ScalarEvidenceField field) {
        if (sourcePolyline == null || sourcePolyline.size() < 2 || !positive(configuredStepMeters)
            || !positive(searchHalfWidthMeters) || evidence == null || field == null) {
            throw new IllegalArgumentException("Profile sampling inputs are incomplete");
        }
        ResampledCurve curve = resample(sourcePolyline, configuredStepMeters);
        List<ProbabilisticProfile> result = new ArrayList<>(curve.points().size());
        for (int index = 0; index < curve.points().size(); index++) {
            double sourcePitch = evidence.resolution().effectivePitchMetersAt(curve.chainageMeters().get(index));
            double samplePitch = 0.5 * sourcePitch;
            MetricPoint anchor = curve.points().get(index);
            MetricPoint tangent = tangent(curve.points(), index);
            MetricPoint normal = new MetricPoint(-tangent.yMeters(), tangent.xMeters());
            double minimum = authorizedBoundary(anchor, normal, -1.0, searchHalfWidthMeters,
                samplePitch, evidence);
            double maximum = authorizedBoundary(anchor, normal, 1.0, searchHalfWidthMeters,
                samplePitch, evidence);
            if (maximum - minimum < 1e-9) {
                minimum = -Math.min(samplePitch, searchHalfWidthMeters);
                maximum = Math.min(samplePitch, searchHalfWidthMeters);
            }
            List<ProbabilisticProfile.Sample> samples = sampleProfile(anchor, normal, minimum,
                maximum, samplePitch, evidence, field);
            double noise = robustNoiseFloor(samples);
            ExtractedModes extracted = extractModes(samples, noise, sourcePitch, index);
            OptionalDouble exact = fixedEndpoints && (index == 0 || index == curve.points().size() - 1)
                ? OptionalDouble.of(0.0) : OptionalDouble.empty();
            result.add(new ProbabilisticProfile(index, curve.chainageMeters().get(index), anchor, normal,
                minimum, maximum, sourcePitch, evidence.resolution().nativePitchMeters().isPresent(),
                noise, samples, extracted.modes(), extracted.censoredModes(), exact,
                List.of(Math.atan2(tangent.yMeters(), tangent.xMeters())),
                extracted.modes().isEmpty() ? 0.0 : 1.0));
        }
        return List.copyOf(result);
    }

    /** Samples profiles and proves that engine sampling matches the request-owned measured chainage. */
    public List<ProbabilisticProfile> create(List<MetricPoint> sourcePolyline,
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage profileChainage,
        double searchHalfWidthMeters, boolean fixedEndpoints, EvidenceSnapshot evidence,
        ScalarEvidenceField field) {
        if (profileChainage == null) {
            throw new IllegalArgumentException("Measured profile chainage is required");
        }
        List<ProbabilisticProfile> result = create(sourcePolyline, profileChainage.configuredStepMeters(),
            searchHalfWidthMeters, fixedEndpoints, evidence, field);
        if (result.size() != profileChainage.cumulativeGroundMeters().size()) {
            throw new IllegalArgumentException("Request chainage does not match deterministic profile sampling");
        }
        for (int index = 0; index < result.size(); index++) {
            if (Math.abs(result.get(index).chainageMeters()
                - profileChainage.cumulativeGroundMeters().get(index)) > 1e-8) {
                throw new IllegalArgumentException("Request chainage differs from sampled profile anchors");
            }
        }
        return result;
    }

    /** Samples one scalar value using strict bilinear validity. */
    public OptionalDouble sample(EvidenceSnapshot evidence, ScalarEvidenceField field,
        MetricPoint point) {
        RasterPoint raster = evidence.transform().metricToPixelCenter(point);
        double x = raster.x();
        double y = raster.y();
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0.0 || y < 0.0
            || x > field.width() - 1.0 || y > field.height() - 1.0) {
            return OptionalDouble.empty();
        }
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int x1 = Math.min(field.width() - 1, x0 + 1);
        int y1 = Math.min(field.height() - 1, y0 + 1);
        OptionalDouble q00 = field.sample(x0, y0);
        OptionalDouble q10 = field.sample(x1, y0);
        OptionalDouble q01 = field.sample(x0, y1);
        OptionalDouble q11 = field.sample(x1, y1);
        if (q00.isEmpty() || q10.isEmpty() || q01.isEmpty() || q11.isEmpty()) {
            return OptionalDouble.empty();
        }
        double fx = x - x0;
        double fy = y - y0;
        double top = q00.getAsDouble() + fx * (q10.getAsDouble() - q00.getAsDouble());
        double bottom = q01.getAsDouble() + fx * (q11.getAsDouble() - q01.getAsDouble());
        return OptionalDouble.of(top + fy * (bottom - top));
    }

    private List<ProbabilisticProfile.Sample> sampleProfile(MetricPoint anchor, MetricPoint normal,
        double minimum, double maximum, double pitch, EvidenceSnapshot evidence,
        ScalarEvidenceField field) {
        int intervals = Math.max(1, (int) Math.ceil((maximum - minimum) / pitch));
        List<ProbabilisticProfile.Sample> result = new ArrayList<>(intervals + 1);
        for (int index = 0; index <= intervals; index++) {
            double offset = index == intervals ? maximum : minimum + index * (maximum - minimum) / intervals;
            MetricPoint point = offset(anchor, normal, offset);
            OptionalDouble value = sample(evidence, field, point);
            result.add(new ProbabilisticProfile.Sample(offset, value.orElse(Double.NaN), value.isPresent()));
        }
        return List.copyOf(result);
    }

    private static ExtractedModes extractModes(List<ProbabilisticProfile.Sample> samples,
        double noiseFloor, double sourcePitch, int profileIndex) {
        double[] raw = samples.stream().mapToDouble(sample -> sample.valid() ? sample.intensity() : Double.NaN)
            .toArray();
        double maximum = Arrays.stream(raw).filter(Double::isFinite).max().orElse(noiseFloor);
        if (!(maximum > noiseFloor)) {
            return new ExtractedModes(List.of(), List.of());
        }
        double[] b3 = convolve(raw, B3);
        double[] b5 = convolve(raw, B5);
        double threshold = noiseFloor + LEVELS[0] * (maximum - noiseFloor);
        List<IndexInterval> elementary = intervals(raw, threshold);
        List<ProbabilisticProfile.Mode> modes = new ArrayList<>();
        List<ProbabilisticProfile.CensoredMode> censored = new ArrayList<>();
        int modeIndex = 0;
        for (IndexInterval interval : elementary) {
            int peakIndex = peak(raw, interval);
            boolean leftCensored = interval.first() == 0;
            boolean rightCensored = interval.last() == raw.length - 1;
            String id = "p" + profileIndex + "-m" + modeIndex++;
            String lineage = id + "-scalar-band";
            if (leftCensored || rightCensored) {
                ProbabilisticProfile.CensorSide side = rightCensored
                    ? ProbabilisticProfile.CensorSide.RIGHT : ProbabilisticProfile.CensorSide.LEFT;
                int edge = rightCensored ? interval.last() : interval.first();
                censored.add(new ProbabilisticProfile.CensoredMode(id, lineage, side,
                    samples.get(edge).offsetMeters(), 1.0, gradientTowardEdge(raw, edge, rightCensored)));
                continue;
            }
            List<Double> centers = new ArrayList<>();
            addNestedCenter(centers, raw, samples, peakIndex, noiseFloor, maximum, true);
            addNestedCenter(centers, b3, samples, peakIndex, noiseFloor, maximum, false);
            addNestedCenter(centers, b5, samples, peakIndex, noiseFloor, maximum, false);
            if (centers.size() < 2) {
                continue;
            }
            double center = median(centers);
            double deviation = 1.4826 * median(centers.stream().map(value -> Math.abs(value - center)).toList());
            double sigma = Math.max(sourcePitch * 0.5, deviation);
            double halfCenter = Math.min(sourcePitch * 0.5, Math.max(sourcePitch * 0.25, deviation));
            double peakOffset = samples.get(peakIndex).offsetMeters();
            modes.add(new ProbabilisticProfile.Mode(id, lineage, center - halfCenter, center + halfCenter,
                sigma, 1.0, agreementConfidence(centers, sourcePitch), List.of(peakOffset), centers, false));
        }
        return new ExtractedModes(modes, censored);
    }

    private static void addNestedCenter(List<Double> centers, double[] values,
        List<ProbabilisticProfile.Sample> samples, int peakIndex, double noiseFloor,
        double rawMaximum, boolean requiredFine) {
        if (!Double.isFinite(values[peakIndex])) {
            return;
        }
        double localMaximum = values[peakIndex];
        double threshold = noiseFloor + LEVELS[2] * Math.max(0.0, localMaximum - noiseFloor);
        int left = peakIndex;
        int right = peakIndex;
        while (left > 0 && Double.isFinite(values[left - 1]) && values[left - 1] >= threshold) {
            left--;
        }
        while (right + 1 < values.length && Double.isFinite(values[right + 1])
            && values[right + 1] >= threshold) {
            right++;
        }
        if (left > 0 && right + 1 < values.length && (!requiredFine || rawMaximum > noiseFloor)) {
            centers.add(0.5 * (samples.get(left).offsetMeters() + samples.get(right).offsetMeters()));
        }
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

    private static List<IndexInterval> intervals(double[] values, double threshold) {
        List<IndexInterval> result = new ArrayList<>();
        int start = -1;
        for (int index = 0; index <= values.length; index++) {
            boolean high = index < values.length && Double.isFinite(values[index]) && values[index] >= threshold;
            if (high && start < 0) {
                start = index;
            } else if (!high && start >= 0) {
                result.add(new IndexInterval(start, index - 1));
                start = -1;
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

    private static double robustNoiseFloor(List<ProbabilisticProfile.Sample> samples) {
        double[] valid = samples.stream().filter(ProbabilisticProfile.Sample::valid)
            .mapToDouble(ProbabilisticProfile.Sample::intensity).sorted().toArray();
        return valid.length == 0 ? 0.0 : valid[(int) Math.floor(0.2 * (valid.length - 1))];
    }

    private static double authorizedBoundary(MetricPoint anchor, MetricPoint normal, double direction,
        double maximum, double step, EvidenceSnapshot evidence) {
        double last = 0.0;
        for (double distance = Math.min(step, maximum); distance <= maximum + 1e-9;
             distance += step) {
            double bounded = Math.min(distance, maximum);
            if (!evidence.routePositionAuthorized(offset(anchor, normal, direction * bounded))) {
                break;
            }
            last = bounded;
            if (bounded == maximum) {
                break;
            }
        }
        return direction * last;
    }

    private static ResampledCurve resample(List<MetricPoint> points, double step) {
        List<Double> sourceChainage = new ArrayList<>(points.size());
        sourceChainage.add(0.0);
        for (int index = 1; index < points.size(); index++) {
            sourceChainage.add(sourceChainage.get(index - 1) + points.get(index - 1).distanceTo(points.get(index)));
        }
        double length = sourceChainage.get(sourceChainage.size() - 1);
        if (!(length > 0.0)) {
            throw new IllegalArgumentException("Source polyline has zero length");
        }
        int intervals = Math.max(1, (int) Math.ceil(length / step));
        List<MetricPoint> sampled = new ArrayList<>(intervals + 1);
        List<Double> chainage = new ArrayList<>(intervals + 1);
        int segment = 1;
        for (int index = 0; index <= intervals; index++) {
            double target = index == intervals ? length : index * length / intervals;
            while (segment < sourceChainage.size() - 1 && sourceChainage.get(segment) < target) {
                segment++;
            }
            double startDistance = sourceChainage.get(segment - 1);
            double endDistance = sourceChainage.get(segment);
            double fraction = (target - startDistance) / (endDistance - startDistance);
            MetricPoint start = points.get(segment - 1);
            MetricPoint end = points.get(segment);
            sampled.add(new MetricPoint(start.xMeters() + fraction * (end.xMeters() - start.xMeters()),
                start.yMeters() + fraction * (end.yMeters() - start.yMeters())));
            chainage.add(target);
        }
        return new ResampledCurve(List.copyOf(sampled), List.copyOf(chainage));
    }

    private static MetricPoint tangent(List<MetricPoint> points, int index) {
        MetricPoint start = points.get(Math.max(0, index - 1));
        MetricPoint end = points.get(Math.min(points.size() - 1, index + 1));
        double dx = end.xMeters() - start.xMeters();
        double dy = end.yMeters() - start.yMeters();
        double length = Math.hypot(dx, dy);
        if (!(length > 0.0)) {
            throw new IllegalArgumentException("Resampled source has a zero tangent");
        }
        return new MetricPoint(dx / length, dy / length);
    }

    private static MetricPoint offset(MetricPoint anchor, MetricPoint normal, double offset) {
        return new MetricPoint(anchor.xMeters() + normal.xMeters() * offset,
            anchor.yMeters() + normal.yMeters() * offset);
    }

    private static double median(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0 ? 0.5 * (sorted[middle - 1] + sorted[middle]) : sorted[middle];
    }

    private static double agreementConfidence(List<Double> centers, double sourcePitch) {
        double minimum = centers.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double maximum = centers.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        return Math.max(0.0, Math.min(1.0, 1.0 - (maximum - minimum) / Math.max(sourcePitch, 1e-12)));
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private record ResampledCurve(List<MetricPoint> points, List<Double> chainageMeters) { }
    private record IndexInterval(int first, int last) { }
    private record ExtractedModes(List<ProbabilisticProfile.Mode> modes,
        List<ProbabilisticProfile.CensoredMode> censoredModes) { }
}
