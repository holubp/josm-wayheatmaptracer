package org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.Supplier;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.OrderedDoubleSum;

/** Measures bounded multi-direction image support along physical two-sided metric rays. */
public final class ImageOrientationDescriptor {
    /** Completeness classification for one angular response. */
    public enum Visibility { TWO_SIDED, FORWARD_ONLY, BACKWARD_ONLY, INSUFFICIENT }

    /** Bounded diagnostic summary of one undirected heading bin. */
    public record AngularResponse(double headingRadians, double response,
        double forwardMeanExcess, double backwardMeanExcess, double forwardValidFraction,
        double backwardValidFraction, Visibility visibility) {
        /** Validates normalized response and physical valid-length fractions. */
        public AngularResponse {
            if (!Double.isFinite(headingRadians) || headingRadians < 0.0 || headingRadians >= Math.PI
                || !finiteNonnegative(response) || !finiteNonnegative(forwardMeanExcess)
                || !finiteNonnegative(backwardMeanExcess) || !unit(forwardValidFraction)
                || !unit(backwardValidFraction) || visibility == null) {
                throw new IllegalArgumentException("Angular response is invalid");
            }
        }
    }

    /** Image orientation plus bounded diagnostic responses and the local robust background. */
    public record Result(ImageOrientationSupport support, List<AngularResponse> responses,
        double robustBackground, int sampledRayPoints) {
        /** Copies bounded output and validates scalar diagnostics. */
        public Result {
            if (support == null || responses == null || !Double.isFinite(robustBackground)
                || robustBackground < 0.0 || robustBackground > 1.0 || sampledRayPoints < 0) {
                throw new IllegalArgumentException("Image orientation result is invalid");
            }
            responses = List.copyOf(responses);
        }
    }

    /** Measures orientation with the normative versioned parameters and no cancellation. */
    public Result describe(EvidenceSnapshot evidence, ScalarEvidenceField field,
        MetricPoint center, double sourcePitchMeters) {
        return describe(evidence, field, center, sourcePitchMeters,
            EvidenceModelParameters.defaults(), CancellationProbe.NONE);
    }

    /** Measures orientation without copying the raster, checking cancellation between bounded rays. */
    public Result describe(EvidenceSnapshot evidence, ScalarEvidenceField field,
        MetricPoint center, double sourcePitchMeters, EvidenceModelParameters parameters,
        CancellationProbe cancellation) {
        return describe(evidence, field, center, sourcePitchMeters, parameters, cancellation, null);
    }

    /** Measures orientation while charging scratch and the immutable returned graph separately. */
    public Result describe(EvidenceSnapshot evidence, ScalarEvidenceField field,
        MetricPoint center, double sourcePitchMeters, EvidenceModelParameters parameters,
        CancellationProbe cancellation, AttemptMemoryLedger.Owner owner) {
        if (evidence == null || field == null || center == null || parameters == null
            || cancellation == null || !Double.isFinite(sourcePitchMeters) || sourcePitchMeters <= 0.0) {
            throw new IllegalArgumentException("Image orientation inputs are incomplete");
        }
        AttemptMemoryLedger.Owner scratch = owner == null ? null : owner.child("orientation-scratch");
        try {
        cancellation.checkpoint();
        OptionalDouble centerValue = sample(evidence, field, center);
        if (centerValue.isEmpty()) {
            return unknown(ImageOrientationSupport.Status.INVALID_CENTER, parameters, 0.0, 0,
                    owner, scratch);
        }
        EvidenceModelParameters.Localization localization = parameters.localization();
        double rayLength = Math.max(localization.minimumOrientationRayMeters(),
            localization.orientationRayLengthPitches() * sourcePitchMeters);
        double maximumStep = localization.maximumOrientationStepPitches() * sourcePitchMeters;
        double requestedIntervals = Math.max(1.0,
            Math.ceil((rayLength - sourcePitchMeters) / maximumStep));
        long headingRays = (long) localization.orientationHeadingCount() * 2L;
        if (!Double.isFinite(requestedIntervals)
            || requestedIntervals > Integer.MAX_VALUE - 1.0
            || requestedIntervals + 1.0
                > localization.maximumOrientationSampleCount() / (double) headingRays) {
            return unknown(ImageOrientationSupport.Status.RESOURCE_LIMIT, parameters,
                centerValue.getAsDouble(), 0, owner, scratch);
        }
        int intervals = (int) requestedIntervals;
        int samplesPerRay = intervals + 1;
        long totalSamples = headingRays * samplesPerRay;
        double[] distances = allocated(scratch,
                AttemptMemoryLedger.arrayBytes(samplesPerRay, Double.BYTES),
                () -> new double[samplesPerRay]);
        for (int index = 0; index < samplesPerRay; index++) {
            distances[index] = sourcePitchMeters
                + (rayLength - sourcePitchMeters) * index / intervals;
        }
        RaySamples[][] rays = allocated(scratch,
                AttemptMemoryLedger.referenceArrayBytes(localization.orientationHeadingCount())
                    + Math.multiplyExact(localization.orientationHeadingCount(),
                        AttemptMemoryLedger.referenceArrayBytes(2)),
                () -> new RaySamples[localization.orientationHeadingCount()][2]);
        double[] backgroundSamples = allocated(scratch,
                AttemptMemoryLedger.arrayBytes(totalSamples + 1L, Double.BYTES),
                () -> new double[(int) totalSamples + 1]);
        int backgroundCount = 0;
        backgroundSamples[backgroundCount++] = centerValue.getAsDouble();
        for (int headingIndex = 0; headingIndex < localization.orientationHeadingCount(); headingIndex++) {
            cancellation.checkpoint();
            double heading = Math.PI * headingIndex / localization.orientationHeadingCount();
            for (int side = 0; side < 2; side++) {
                double sign = side == 0 ? 1.0 : -1.0;
                double[] values = allocated(scratch,
                        AttemptMemoryLedger.arrayBytes(samplesPerRay, Double.BYTES),
                        () -> new double[samplesPerRay]);
                Arrays.fill(values, Double.NaN);
                for (int sampleIndex = 0; sampleIndex < samplesPerRay; sampleIndex++) {
                    cancellation.checkpoint();
                    double distance = sign * distances[sampleIndex];
                    AttemptMemoryLedger.Owner sampleOwner = scratch == null ? null
                            : scratch.child("ray-point");
                    MetricPoint metricSample = allocated(sampleOwner,
                            AttemptMemoryLedger.objectBytes(16), () -> new MetricPoint(
                                center.xMeters() + StrictMath.cos(heading) * distance,
                                center.yMeters() + StrictMath.sin(heading) * distance));
                    OptionalDouble value;
                    try {
                        value = sample(evidence, field, metricSample);
                    } finally {
                        if (sampleOwner != null) sampleOwner.close();
                    }
                    if (value.isPresent()) {
                        values[sampleIndex] = value.getAsDouble();
                        backgroundSamples[backgroundCount++] = value.getAsDouble();
                    }
                }
                double[] weights = quadratureWeights(distances, scratch);
                rays[headingIndex][side] = allocated(scratch,
                        AttemptMemoryLedger.objectBytes(16), () -> new RaySamples(values, weights));
            }
        }
        double background = quantile(backgroundSamples, backgroundCount,
            localization.orientationBackgroundQuantile(), scratch);
        List<AngularResponse> responses = allocated(scratch,
                listBytes(localization.orientationHeadingCount()),
                () -> new ArrayList<>(localization.orientationHeadingCount()));
        for (int headingIndex = 0; headingIndex < localization.orientationHeadingCount(); headingIndex++) {
            RaySummary forward = summarize(rays[headingIndex][0], background);
            RaySummary backward = summarize(rays[headingIndex][1], background);
            boolean forwardComplete = forward.validFraction() + 1e-12
                >= localization.minimumOrientationValidFraction();
            boolean backwardComplete = backward.validFraction() + 1e-12
                >= localization.minimumOrientationValidFraction();
            Visibility visibility = forwardComplete && backwardComplete ? Visibility.TWO_SIDED
                : forwardComplete ? Visibility.FORWARD_ONLY
                : backwardComplete ? Visibility.BACKWARD_ONLY : Visibility.INSUFFICIENT;
            double response = visibility == Visibility.TWO_SIDED
                ? Math.min(forward.meanExcess(), backward.meanExcess()) : 0.0;
            double responseHeading = Math.PI * headingIndex / localization.orientationHeadingCount();
            responses.add(allocated(owner, AttemptMemoryLedger.objectBytes(56),
                    () -> new AngularResponse(responseHeading, response, forward.meanExcess(),
                        backward.meanExcess(), forward.validFraction(), backward.validFraction(), visibility)));
        }
        List<AngularResponse> retainedResponses = allocated(owner, listBytes(responses.size()),
                () -> List.copyOf(responses));
        ImageOrientationSupport support = extractModes(retainedResponses, localization, owner, scratch);
        return allocated(owner, AttemptMemoryLedger.objectBytes(32),
                () -> new Result(support, retainedResponses, background, (int) totalSamples));
        } finally {
            if (scratch != null) scratch.close();
        }
    }

    private static ImageOrientationSupport extractModes(List<AngularResponse> responses,
        EvidenceModelParameters.Localization parameters, AttemptMemoryLedger.Owner owner,
        AttemptMemoryLedger.Owner scratch) {
        double minimum = responses.stream().filter(response -> response.visibility() == Visibility.TWO_SIDED)
            .mapToDouble(AngularResponse::response).min().orElse(Double.NaN);
        double maximum = responses.stream().filter(response -> response.visibility() == Visibility.TWO_SIDED)
            .mapToDouble(AngularResponse::response).max().orElse(Double.NaN);
        if (!Double.isFinite(minimum)) {
            return unknownSupport(ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT,
                    owner);
        }
        double range = maximum - minimum;
        if (!(range > 1e-12) || !(maximum > 1e-12)) {
            return unknownSupport(ImageOrientationSupport.Status.UNKNOWN_FLAT, owner);
        }
        int count = responses.size();
        boolean[] visited = allocated(scratch, AttemptMemoryLedger.arrayBytes(count, 1),
                () -> new boolean[count]);
        List<ImageOrientationSupport.AngularMode> modes = allocated(scratch, listBytes(count),
                () -> new ArrayList<>(count));
        for (int index = 0; index < count; index++) {
            if (visited[index] || responses.get(index).visibility() != Visibility.TWO_SIDED) {
                continue;
            }
            int previous = Math.floorMod(index - 1, count);
            int next = (index + 1) % count;
            double value = responses.get(index).response();
            if (responses.get(previous).visibility() != Visibility.TWO_SIDED
                || responses.get(next).visibility() != Visibility.TWO_SIDED
                || value + 1e-12 < responses.get(previous).response()
                || value + 1e-12 < responses.get(next).response()
                || !(value > responses.get(previous).response() + 1e-12
                    || value > responses.get(next).response() + 1e-12)) {
                continue;
            }
            int start = index;
            int end = index;
            while (Math.floorMod(start - 1, count) != end
                && equalResponse(responses, Math.floorMod(start - 1, count), value)) {
                start = Math.floorMod(start - 1, count);
            }
            while ((end + 1) % count != start && equalResponse(responses, (end + 1) % count, value)) {
                end = (end + 1) % count;
            }
            int outsideBefore = Math.floorMod(start - 1, count);
            int outsideAfter = (end + 1) % count;
            if (responses.get(outsideBefore).visibility() != Visibility.TWO_SIDED
                || responses.get(outsideAfter).visibility() != Visibility.TWO_SIDED
                || !(value > responses.get(outsideBefore).response() + 1e-12)
                || !(value > responses.get(outsideAfter).response() + 1e-12)
                || value - minimum + 1e-12 < parameters.orientationProminenceFraction() * range) {
                continue;
            }
            int plateauSize = 1;
            for (int cursor = start; cursor != end; cursor = (cursor + 1) % count) {
                plateauSize++;
            }
            for (int cursor = start;; cursor = (cursor + 1) % count) {
                visited[cursor] = true;
                if (cursor == end) {
                    break;
                }
            }
            double step = Math.PI / count;
            double peak = plateauSize == 1 ? interpolatePeak(responses, index, step)
                : ImageOrientationSupport.normalize((start + 0.5 * (plateauSize - 1)) * step);
            double modeStart = plateauSize == 1 ? peak : start * step;
            double modeEnd = plateauSize == 1 ? peak : end * step;
            modes.add(allocated(owner, AttemptMemoryLedger.objectBytes(32),
                    () -> new ImageOrientationSupport.AngularMode(
                        modeStart, modeEnd, peak, value)));
        }
        if (modes.isEmpty()) {
            return unknownSupport(ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT,
                    owner);
        }
        modes.sort(Comparator.comparingDouble(ImageOrientationSupport.AngularMode::peakBearingRadians));
        double minimumRayFraction = responses.stream()
            .filter(response -> response.visibility() == Visibility.TWO_SIDED)
            .flatMapToDouble(response -> java.util.stream.DoubleStream.of(
                response.forwardValidFraction(), response.backwardValidFraction())).min().orElse(0.0);
        double certainty = Math.max(0.0, Math.min(1.0,
            range / Math.max(maximum, 1e-12))) * minimumRayFraction;
        List<ImageOrientationSupport.AngularMode> retainedModes = allocated(owner,
                listBytes(modes.size()), () -> List.copyOf(modes));
        return allocated(owner, AttemptMemoryLedger.objectBytes(24),
                () -> new ImageOrientationSupport(ImageOrientationSupport.Status.MEASURED_TWO_SIDED,
                    retainedModes, certainty));
    }

    private static boolean equalResponse(List<AngularResponse> responses, int index, double value) {
        return responses.get(index).visibility() == Visibility.TWO_SIDED
            && Math.abs(responses.get(index).response() - value) <= 1e-12;
    }

    private static double interpolatePeak(List<AngularResponse> responses, int index, double step) {
        int count = responses.size();
        double left = responses.get(Math.floorMod(index - 1, count)).response();
        double center = responses.get(index).response();
        double right = responses.get((index + 1) % count).response();
        double denominator = left - 2.0 * center + right;
        if (Math.abs(denominator) <= 1e-15) {
            return index * step;
        }
        double offset = 0.5 * (left - right) / denominator;
        if (!Double.isFinite(offset) || Math.abs(offset) > 1.0) {
            return index * step;
        }
        return ImageOrientationSupport.normalize((index + offset) * step);
    }

    private static OptionalDouble sample(EvidenceSnapshot evidence, ScalarEvidenceField field,
        MetricPoint metricPoint) {
        RasterPoint raster = evidence.transform().metricToPixelCenter(metricPoint);
        return field.sampleBilinear(raster.x(), raster.y());
    }

    private static double[] quadratureWeights(double[] distances,
            AttemptMemoryLedger.Owner owner) {
        double[] weights = allocated(owner,
                AttemptMemoryLedger.arrayBytes(distances.length, Double.BYTES),
                () -> new double[distances.length]);
        for (int index = 0; index < distances.length - 1; index++) {
            double halfInterval = 0.5 * (distances[index + 1] - distances[index]);
            weights[index] += halfInterval;
            weights[index + 1] += halfInterval;
        }
        return weights;
    }

    private static RaySummary summarize(RaySamples samples, double background) {
        double excessIntegral = 0.0;
        OrderedDoubleSum total = new OrderedDoubleSum();
        OrderedDoubleSum valid = new OrderedDoubleSum();
        for (double weight : samples.weights()) total.add(weight);
        double totalWeight = total.value();
        for (int index = 0; index < samples.values().length; index++) {
            if (Double.isFinite(samples.values()[index])) {
                valid.add(samples.weights()[index]);
                excessIntegral += samples.weights()[index]
                    * Math.max(0.0, samples.values()[index] - background);
            }
        }
        double validWeight = valid.value();
        double validFraction = totalWeight > 0.0
            ? Math.max(0.0, Math.min(1.0, validWeight / totalWeight)) : 0.0;
        return new RaySummary(validWeight > 0.0 ? excessIntegral / validWeight : 0.0,
            validFraction);
    }

    private static double quantile(double[] values, int length, double quantile,
            AttemptMemoryLedger.Owner owner) {
        AttemptMemoryLedger.Owner quantileOwner = owner == null ? null : owner.child("quantile");
        try {
            double[] sorted = allocated(quantileOwner,
                    AttemptMemoryLedger.arrayBytes(length, Double.BYTES),
                    () -> Arrays.copyOf(values, length));
            Arrays.sort(sorted);
            int index = (int) Math.floor(quantile * (sorted.length - 1));
            return sorted[index];
        } finally {
            if (quantileOwner != null) quantileOwner.close();
        }
    }

    private static Result unknown(ImageOrientationSupport.Status status,
        EvidenceModelParameters parameters, double background, int sampledPoints,
        AttemptMemoryLedger.Owner owner, AttemptMemoryLedger.Owner scratch) {
        int count = parameters.localization().orientationHeadingCount();
        List<AngularResponse> responses = allocated(scratch, listBytes(count),
                () -> new ArrayList<>(count));
        for (int index = 0; index < parameters.localization().orientationHeadingCount(); index++) {
            double heading = Math.PI * index / count;
            responses.add(allocated(owner, AttemptMemoryLedger.objectBytes(56),
                    () -> new AngularResponse(heading, 0.0, 0.0, 0.0,
                        0.0, 0.0, Visibility.INSUFFICIENT)));
        }
        List<AngularResponse> retained = allocated(owner, listBytes(responses.size()),
                () -> List.copyOf(responses));
        ImageOrientationSupport support = unknownSupport(status, owner);
        return allocated(owner, AttemptMemoryLedger.objectBytes(32),
                () -> new Result(support, retained, background, sampledPoints));
    }

    private static ImageOrientationSupport unknownSupport(ImageOrientationSupport.Status status,
            AttemptMemoryLedger.Owner owner) {
        return allocated(owner, AttemptMemoryLedger.objectBytes(24),
                () -> ImageOrientationSupport.unknown(status));
    }

    private static long listBytes(long size) {
        return AttemptMemoryLedger.objectBytes(16)
                + AttemptMemoryLedger.referenceArrayBytes(size);
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
            T result = allocation.get();
            reservation.adopt(result);
            return result;
        } catch (RuntimeException | Error exception) {
            reservation.close();
            throw exception;
        }
    }

    /** Typed descriptor allocation refusal translated at the owning engine boundary. */
    public static final class ResourceLimitException extends RuntimeException {
        private ResourceLimitException(AttemptMemoryLedger.ResourceLimitException cause) {
            super("orientation descriptor: " + cause.getMessage(), cause);
        }
    }

    private static boolean unit(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private static boolean finiteNonnegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    private record RaySamples(double[] values, double[] weights) { }
    private record RaySummary(double meanExcess, double validFraction) { }
}
