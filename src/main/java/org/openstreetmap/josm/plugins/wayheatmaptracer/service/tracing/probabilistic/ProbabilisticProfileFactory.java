package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.LongConsumer;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.ImageOrientationDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.StrictScalarSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Samples full scalar cross-sections and extracts deterministic finite observation hypotheses. */
public final class ProbabilisticProfileFactory {
    /**
     * Computes the exact profile chainage used by deterministic resampling.
     *
     *  sourcePolyline selected way geometry in the snapshot metric frame
     *  configuredStepMeters desired longitudinal step in ground metres
     *  immutable request-owned chainage accepted by the profile sampler
     */
    public org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage profileChainage(
            List<MetricPoint> sourcePolyline, double configuredStepMeters) {
        if (sourcePolyline == null || sourcePolyline.size() < 2 || !positive(configuredStepMeters)) {
            throw new IllegalArgumentException("Profile chainage inputs are incomplete");
        }
        ResampledCurve curve = resample(sourcePolyline, configuredStepMeters);
        return new org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage(
                curve.chainageMeters(), configuredStepMeters);
    }

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
        return create(sourcePolyline, configuredStepMeters, searchHalfWidthMeters, fixedEndpoints,
            evidence, field, EvidenceModelParameters.defaults(), CancellationProbe.NONE);
    }

    /** Samples profiles with a versioned localization policy and cooperative cancellation. */
    public List<ProbabilisticProfile> create(List<MetricPoint> sourcePolyline,
        double configuredStepMeters, double searchHalfWidthMeters, boolean fixedEndpoints,
        EvidenceSnapshot evidence, ScalarEvidenceField field, EvidenceModelParameters parameters,
        CancellationProbe cancellation) {
        return create(sourcePolyline, configuredStepMeters, searchHalfWidthMeters,
                fixedEndpoints, evidence, field, parameters, cancellation, true);
    }

    List<ProbabilisticProfile> create(List<MetricPoint> sourcePolyline,
        double configuredStepMeters, double searchHalfWidthMeters, boolean fixedEndpoints,
        EvidenceSnapshot evidence, ScalarEvidenceField field, EvidenceModelParameters parameters,
        CancellationProbe cancellation, boolean directLongitudinalReliability) {
        return create(sourcePolyline, configuredStepMeters, searchHalfWidthMeters, fixedEndpoints,
                evidence, field, parameters, cancellation, directLongitudinalReliability, 0.0);
    }

    private List<ProbabilisticProfile> create(List<MetricPoint> sourcePolyline,
        double configuredStepMeters, double searchHalfWidthMeters, boolean fixedEndpoints,
        EvidenceSnapshot evidence, ScalarEvidenceField field, EvidenceModelParameters parameters,
        CancellationProbe cancellation, boolean directLongitudinalReliability,
        double sourceOriginGroundMeters) {
        if (sourcePolyline == null || sourcePolyline.size() < 2 || !positive(configuredStepMeters)
            || !positive(searchHalfWidthMeters) || evidence == null || field == null
            || parameters == null || cancellation == null
            || !Double.isFinite(sourceOriginGroundMeters) || sourceOriginGroundMeters < 0.0) {
            throw new IllegalArgumentException("Profile sampling inputs are incomplete");
        }
        ResampledCurve curve = resample(sourcePolyline, configuredStepMeters);
        ImageOrientationDescriptor orientationDescriptor = new ImageOrientationDescriptor();
        LocalScalarProfileExtractor profileExtractor = new LocalScalarProfileExtractor();
        List<ProbabilisticProfile> result = new ArrayList<>(curve.points().size());
        for (int index = 0; index < curve.points().size(); index++) {
            cancellation.checkpoint();
            double sourcePitch = evidence.resolution().effectivePitchMetersAt(
                    sourceOriginGroundMeters + curve.chainageMeters().get(index));
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
            LocalScalarProfileExtractor.Result scalarFeatures = profileExtractor.extract(samples.stream()
                    .map(sample -> new LocalScalarProfileExtractor.Sample(sample.offsetMeters(),
                            sample.intensity(), sample.valid())).toList(), sourcePitch,
                    parameters.localization());
            ExtractedModes extracted = adaptModes(scalarFeatures, index);
            List<ProbabilisticProfile.Mode> orientedModes = bindOrientationToModes(
                extracted.modes(), anchor, normal, sourcePitch, evidence, field, parameters,
                cancellation, orientationDescriptor, directLongitudinalReliability);
            ImageOrientationSupport orientation = aggregateOrientation(orientedModes);
            OptionalDouble exact = fixedEndpoints && (index == 0 || index == curve.points().size() - 1)
                ? OptionalDouble.of(0.0) : OptionalDouble.empty();
            result.add(new ProbabilisticProfile(index, curve.chainageMeters().get(index), anchor, normal,
                minimum, maximum, sourcePitch, evidence.resolution().nativePitchMeters().isPresent(),
                scalarFeatures.noiseFloor(),
                samples, orientedModes, extracted.censoredModes(), exact, orientation));
        }
        return directLongitudinalReliability
                ? LongitudinalModeReliability.apply(result,
                        new RasterIntervalEvidence(evidence, field), cancellation).profiles()
                : List.copyOf(result);
    }

    /** Samples profiles and proves that engine sampling matches the request-owned measured chainage. */
    public List<ProbabilisticProfile> create(List<MetricPoint> sourcePolyline,
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage profileChainage,
        double searchHalfWidthMeters, boolean fixedEndpoints, EvidenceSnapshot evidence,
        ScalarEvidenceField field) {
        return create(sourcePolyline, profileChainage, searchHalfWidthMeters, fixedEndpoints,
            evidence, field, EvidenceModelParameters.defaults(), CancellationProbe.NONE);
    }

    /** Samples a request-owned chainage with versioned localization and cancellation. */
    public List<ProbabilisticProfile> create(List<MetricPoint> sourcePolyline,
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage profileChainage,
        double searchHalfWidthMeters, boolean fixedEndpoints, EvidenceSnapshot evidence,
        ScalarEvidenceField field, EvidenceModelParameters parameters, CancellationProbe cancellation) {
        return create(sourcePolyline, profileChainage, searchHalfWidthMeters,
                fixedEndpoints, evidence, field, parameters, cancellation, true);
    }

    List<ProbabilisticProfile> create(List<MetricPoint> sourcePolyline,
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage profileChainage,
        double searchHalfWidthMeters, boolean fixedEndpoints, EvidenceSnapshot evidence,
        ScalarEvidenceField field, EvidenceModelParameters parameters, CancellationProbe cancellation,
        boolean directLongitudinalReliability) {
        if (profileChainage == null) {
            throw new IllegalArgumentException("Measured profile chainage is required");
        }
        List<ProbabilisticProfile> result = create(sourcePolyline, profileChainage.configuredStepMeters(),
            searchHalfWidthMeters, fixedEndpoints, evidence, field, parameters, cancellation,
            directLongitudinalReliability, profileChainage.sourceOriginGroundMeters());
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

    private static List<ProbabilisticProfile.Mode> bindOrientationToModes(
        List<ProbabilisticProfile.Mode> modes, MetricPoint anchor, MetricPoint normal,
        double sourcePitch, EvidenceSnapshot evidence, ScalarEvidenceField field,
        EvidenceModelParameters parameters, CancellationProbe cancellation,
        ImageOrientationDescriptor descriptor, boolean directLongitudinalReliability) {
        List<ProbabilisticProfile.Mode> result = new ArrayList<>(modes.size());
        for (ProbabilisticProfile.Mode mode : modes) {
            cancellation.checkpoint();
            MetricPoint center = offset(anchor, normal, mode.coreCenterMeters());
            ImageOrientationSupport support = descriptor.describe(evidence, field, center,
                sourcePitch, parameters, cancellation).support();
            double corroboration = directLongitudinalReliability
                    ? mode.localizationConfidence()
                    : mode.localizationConfidence() * support.certainty();
            double reliability = directLongitudinalReliability
                    ? ProbabilisticProfile.Mode.combineReliability(
                            mode.scalarAmplitudeReliability(), corroboration)
                    : mode.scalarAmplitudeReliability()
                            + (1.0 - mode.scalarAmplitudeReliability()) * corroboration;
            result.add(new ProbabilisticProfile.Mode(mode.id(), mode.evidenceLineage(),
                mode.coreMinimumMeters(), mode.coreMaximumMeters(), mode.localizationSigmaMeters(),
                mode.existenceConfidence(), mode.localizationConfidence(), mode.peakOffsetsMeters(),
                mode.nestedCenterOffsetsMeters(), mode.groupedParent(), support,
                mode.scalarAmplitudeReliability(), corroboration, reliability));
        }
        return List.copyOf(result);
    }

    private static ImageOrientationSupport aggregateOrientation(
        List<ProbabilisticProfile.Mode> modes) {
        if (modes.stream().anyMatch(mode -> mode.orientationSupport().status()
            == ImageOrientationSupport.Status.RESOURCE_LIMIT)) {
            return ImageOrientationSupport.unknown(ImageOrientationSupport.Status.RESOURCE_LIMIT);
        }
        List<ImageOrientationSupport> measured = modes.stream()
            .map(ProbabilisticProfile.Mode::orientationSupport)
            .filter(support -> !support.modes().isEmpty()).toList();
        if (measured.isEmpty()) {
            ImageOrientationSupport.Status status = modes.stream()
                .map(ProbabilisticProfile.Mode::orientationSupport)
                .map(ImageOrientationSupport::status)
                .filter(candidate -> candidate == ImageOrientationSupport.Status.INVALID_CENTER)
                .findFirst().orElse(ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT);
            return ImageOrientationSupport.unknown(status);
        }
        List<ImageOrientationSupport.AngularMode> angularModes = measured.stream()
            .flatMap(support -> support.modes().stream())
            .sorted(java.util.Comparator.comparingDouble(
                ImageOrientationSupport.AngularMode::peakBearingRadians)).toList();
        double certainty = measured.stream().mapToDouble(ImageOrientationSupport::certainty)
            .max().orElse(0.0);
        return new ImageOrientationSupport(ImageOrientationSupport.Status.MEASURED_TWO_SIDED,
            angularModes, certainty);
    }

    /** Samples one scalar value using strict bilinear validity. */
    public OptionalDouble sample(EvidenceSnapshot evidence, ScalarEvidenceField field,
        MetricPoint point) {
        return StrictScalarSampler.sample(field, evidence.transform(), evidence.evidenceRegion(), point);
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

    private static ExtractedModes adaptModes(LocalScalarProfileExtractor.Result extracted,
            int profileIndex) {
        List<ProbabilisticProfile.Mode> modes = new ArrayList<>();
        List<ProbabilisticProfile.CensoredMode> censored = new ArrayList<>();
        int modeIndex = 0;
        for (LocalScalarProfileExtractor.Mode mode : extracted.modes()) {
            String id = "p" + profileIndex + "-m" + modeIndex++;
            String lineage = id + "-scalar-band";
            modes.add(new ProbabilisticProfile.Mode(id, lineage, mode.coreMinimumMeters(),
                    mode.coreMaximumMeters(), mode.localizationSigmaMeters(),
                    mode.existenceConfidence(), mode.localizationConfidence(), mode.peakOffsetsMeters(),
                    mode.nestedCenterOffsetsMeters(), false,
                    ImageOrientationSupport.unknown(
                        ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT),
                    mode.scalarAmplitudeReliability(), 0.0, mode.scalarAmplitudeReliability()));
        }
        for (LocalScalarProfileExtractor.CensoredMode mode : extracted.censoredModes()) {
            String id = "p" + profileIndex + "-m" + modeIndex++;
            String lineage = id + "-scalar-band";
            ProbabilisticProfile.CensorSide side = mode.side()
                    == LocalScalarProfileExtractor.CensorSide.RIGHT
                    ? ProbabilisticProfile.CensorSide.RIGHT : ProbabilisticProfile.CensorSide.LEFT;
            censored.add(new ProbabilisticProfile.CensoredMode(id, lineage, side,
                    mode.boundaryOffsetMeters(), mode.existenceConfidence(), mode.gradientTowardEdge()));
        }
        return new ExtractedModes(List.copyOf(modes), List.copyOf(censored));
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

    /**
     * Visits every interpolation cell used anywhere along a raster segment.
     *
     * <p>Integer grid events and every open interval between them are checked. This catches cells
     * crossed for less than the regular prominence-sampling step and uses the field's deterministic
     * floor-cell ownership at grid boundaries and corners.</p>
     */
    static boolean supportsEveryCrossedInterpolationCell(ScalarEvidenceField field,
            RasterMetricTransform transform, MetricPoint start, MetricPoint end,
            CancellationProbe cancellation, LongConsumer admission) {
        if (field == null || transform == null || start == null || end == null
                || cancellation == null || admission == null) {
            throw new IllegalArgumentException("Raster interval traversal inputs are incomplete");
        }
        RasterPoint rasterStart = transform.metricToPixelCenter(start);
        RasterPoint rasterEnd = transform.metricToPixelCenter(end);
        if (!checkInterpolationPoint(field, rasterStart.x(), rasterStart.y(), cancellation)
                || !checkInterpolationPoint(field, rasterEnd.x(), rasterEnd.y(), cancellation)) {
            return false;
        }
        long xCrossings = gridCrossingCount(rasterStart.x(), rasterEnd.x());
        long yCrossings = gridCrossingCount(rasterStart.y(), rasterEnd.y());
        long eventCount = Math.addExact(2L, Math.addExact(xCrossings, yCrossings));
        long conservativeChecks = Math.multiplyExact(2L, eventCount);
        admission.accept(conservativeChecks);
        if (eventCount > Integer.MAX_VALUE) {
            throw new LongitudinalModeReliability.ResourceLimitException();
        }

        List<Double> events = new ArrayList<>((int) eventCount);
        events.add(0.0);
        events.add(1.0);
        addGridCrossings(rasterStart.x(), rasterEnd.x(), events, cancellation);
        addGridCrossings(rasterStart.y(), rasterEnd.y(), events, cancellation);
        events.sort(Double::compare);

        double previous = events.get(0);
        for (int index = 1; index < events.size(); index++) {
            double current = events.get(index);
            if (current > previous) {
                double midpoint = 0.5 * (previous + current);
                if (!checkInterpolationPoint(field,
                        rasterStart.x() + midpoint * (rasterEnd.x() - rasterStart.x()),
                        rasterStart.y() + midpoint * (rasterEnd.y() - rasterStart.y()),
                        cancellation)) {
                    return false;
                }
            }
            if (Double.compare(current, previous) != 0
                    && !checkInterpolationPoint(field,
                            rasterStart.x() + current * (rasterEnd.x() - rasterStart.x()),
                            rasterStart.y() + current * (rasterEnd.y() - rasterStart.y()),
                            cancellation)) {
                return false;
            }
            previous = current;
        }
        return true;
    }

    private static boolean checkInterpolationPoint(ScalarEvidenceField field, double x, double y,
            CancellationProbe cancellation) {
        cancellation.checkpoint();
        return field.supportsInterpolationAt(x, y);
    }

    private static long gridCrossingCount(double start, double end) {
        if (Double.doubleToLongBits(start) == Double.doubleToLongBits(end)) {
            return 0L;
        }
        double minimum = Math.min(start, end);
        double maximum = Math.max(start, end);
        int first = (int) Math.floor(minimum) + 1;
        int last = (int) Math.ceil(maximum) - 1;
        return Math.max(0L, (long) last - first + 1L);
    }

    private static void addGridCrossings(double start, double end, List<Double> events,
            CancellationProbe cancellation) {
        if (Double.doubleToLongBits(start) == Double.doubleToLongBits(end)) {
            return;
        }
        double minimum = Math.min(start, end);
        double maximum = Math.max(start, end);
        int first = (int) Math.floor(minimum) + 1;
        int last = (int) Math.ceil(maximum) - 1;
        for (int boundary = first; boundary <= last; boundary++) {
            cancellation.checkpoint();
            double fraction = (boundary - start) / (end - start);
            if (fraction > 0.0 && fraction < 1.0) {
                events.add(fraction);
            }
        }
    }

    /**
     * Proves that adjacent localized centers are joined by directly measured scalar evidence.
     *
     * <p>Validity and the exact decision-region union are hard ownership boundaries. Inside those
     * boundaries the minimum prominence ratio remains continuous, so a faint but concentrated
     * corridor can retain full coherence while a blank interval receives none.</p>
     */
    private static final class RasterIntervalEvidence
            implements LongitudinalModeReliability.DirectIntervalEvidence {
        private static final long MAXIMUM_SAMPLES = 2_000_000L;
        private static final double EPSILON = 1e-12;

        private final EvidenceSnapshot evidence;
        private final ScalarEvidenceField field;
        private long samples;

        RasterIntervalEvidence(EvidenceSnapshot evidence, ScalarEvidenceField field) {
            this.evidence = evidence;
            this.field = field;
        }

        @Override
        public double support(ProbabilisticProfile leftProfile,
                ProbabilisticProfile.Mode leftMode, ProbabilisticProfile rightProfile,
                ProbabilisticProfile.Mode rightMode, CancellationProbe cancellation) {
            MetricPoint left = modeCenter(leftProfile, leftMode);
            MetricPoint right = modeCenter(rightProfile, rightMode);
            if (!evidence.routeSegmentAuthorized(left, right)) {
                return 0.0;
            }
            double distance = left.distanceTo(right);
            if (!(distance > 0.0)) {
                return 0.0;
            }
            double leftProminence = directProminence(leftProfile, leftMode);
            double rightProminence = directProminence(rightProfile, rightMode);
            double referenceProminence = Math.min(leftProminence, rightProminence);
            if (!(referenceProminence > EPSILON)) {
                return 0.0;
            }
            if (!supportsEveryCrossedInterpolationCell(field, evidence.transform(), left, right,
                    cancellation, this::charge)) {
                return 0.0;
            }
            double samplingStep = 0.5 * evidence.resolution().outputRasterPitchMeters();
            long intervals = Math.max(1L, (long) Math.ceil(distance / samplingStep));
            charge(intervals + 1L);
            double minimumRatio = 1.0;
            for (long index = 0L; index <= intervals; index++) {
                cancellation.checkpoint();
                double fraction = (double) index / intervals;
                MetricPoint point = new MetricPoint(
                        left.xMeters() + fraction * (right.xMeters() - left.xMeters()),
                        left.yMeters() + fraction * (right.yMeters() - left.yMeters()));
                OptionalDouble sampled = StrictScalarSampler.sample(
                        field, evidence.transform(), evidence.evidenceRegion(), point);
                if (sampled.isEmpty()) {
                    return 0.0;
                }
                double noiseFloor = leftProfile.noiseFloor()
                        + fraction * (rightProfile.noiseFloor() - leftProfile.noiseFloor());
                double ratio = (sampled.getAsDouble() - noiseFloor) / referenceProminence;
                if (!(ratio > 0.0)) {
                    return 0.0;
                }
                minimumRatio = Math.min(minimumRatio, ratio);
            }
            return Math.min(1.0, minimumRatio);
        }

        private void charge(long requested) {
            if (requested < 0L || requested > MAXIMUM_SAMPLES - samples) {
                throw new LongitudinalModeReliability.ResourceLimitException();
            }
            samples += requested;
        }

        private static MetricPoint modeCenter(ProbabilisticProfile profile,
                ProbabilisticProfile.Mode mode) {
            double offset = mode.coreCenterMeters();
            return new MetricPoint(
                    profile.anchor().xMeters() + profile.normalUnit().xMeters() * offset,
                    profile.anchor().yMeters() + profile.normalUnit().yMeters() * offset);
        }

        private static double directProminence(ProbabilisticProfile profile,
                ProbabilisticProfile.Mode mode) {
            double tolerance = 1e-9 * Math.max(1.0, profile.sourcePitchMeters());
            double maximum = 0.0;
            for (ProbabilisticProfile.Sample sample : profile.samples()) {
                if (sample.valid()
                        && sample.offsetMeters() + tolerance >= mode.coreMinimumMeters()
                        && sample.offsetMeters() - tolerance <= mode.coreMaximumMeters()) {
                    maximum = Math.max(maximum, sample.intensity() - profile.noiseFloor());
                }
            }
            return maximum;
        }
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private record ResampledCurve(List<MetricPoint> points, List<Double> chainageMeters) { }
    private record ExtractedModes(List<ProbabilisticProfile.Mode> modes,
        List<ProbabilisticProfile.CensoredMode> censoredModes) { }
}
