package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.StrictScalarSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

/**
 * Frozen scalar-image interpolation field used by refitting and final quality checks.
 *
 * <p>The field copies scalar intensity and validity at construction. Bilinear interpolation is
 * permitted only when all four source cells are valid; no edge clamping or missing-value fill is
 * performed.</p>
 */
public final class ImageCostField {
    private static final int MAXIMUM_FROZEN_PROFILE_SAMPLES = 65_536;
    /** Frozen ownership state for a selected local image branch. */
    public enum FrozenSupport { MEASURED, AMBIGUOUS, MISSING }

    /** Cost and metric gradient from one frozen local branch field. */
    public record FrozenSample(double cost, double gradientX, double gradientY) { }

    /**
     * Immutable background-relative one-dimensional field and measured image direction.
     * The selected mode and every interpolation ordinate are fixed before optimization.
     */
    public record FrozenProfile(FrozenSupport support, MetricPoint origin, MetricPoint normal,
            double coreMinimumMeters, double coreMaximumMeters, double localizationSigmaMeters,
            double noiseFloor, double peakIntensity, double[] offsetsMeters, double[] values,
            List<ImageOrientationSupport.AngularMode> orientationModes,
            double orientationRadians, double orientationCertainty, double positionalReliability) {
        /** Retains fixtures that predate continuous image reliability. */
        public FrozenProfile(FrozenSupport support, MetricPoint origin, MetricPoint normal,
                double coreMinimumMeters, double coreMaximumMeters, double localizationSigmaMeters,
                double noiseFloor, double peakIntensity, double[] offsetsMeters, double[] values,
                List<ImageOrientationSupport.AngularMode> orientationModes,
                double orientationRadians, double orientationCertainty) {
            this(support, origin, normal, coreMinimumMeters, coreMaximumMeters,
                localizationSigmaMeters, noiseFloor, peakIntensity, offsetsMeters, values,
                orientationModes, orientationRadians, orientationCertainty, 1.0);
        }

        /** Copies interpolation arrays and validates measured fields. */
        public FrozenProfile {
            offsetsMeters = offsetsMeters.clone();
            values = values.clone();
            orientationModes = List.copyOf(orientationModes);
            if (support == null || origin == null || normal == null
                    || offsetsMeters.length != values.length || offsetsMeters.length < 2
                    || !Double.isFinite(localizationSigmaMeters) || localizationSigmaMeters <= 0.0
                    || !Double.isFinite(orientationRadians) || !Double.isFinite(orientationCertainty)
                    || orientationCertainty < 0.0 || orientationCertainty > 1.0
                    || positionalReliability < 0.0 || positionalReliability > 1.0) {
                throw new IllegalArgumentException("Frozen profile is incomplete");
            }
        }

        /** Returns a defensive copy of the fixed profile abscissae. */
        @Override public double[] offsetsMeters() { return offsetsMeters.clone(); }
        /** Returns a defensive copy of the fixed profile ordinates. */
        @Override public double[] values() { return values.clone(); }

        /** Evaluates the declared fixed branch field without selecting another mode. */
        public Optional<FrozenSample> evaluate(MetricPoint point) {
            if (support != FrozenSupport.MEASURED) {
                return Optional.empty();
            }
            double dx = point.xMeters() - origin.xMeters();
            double dy = point.yMeters() - origin.yMeters();
            double offset = dx * normal.xMeters() + dy * normal.yMeters();
            int exact = Arrays.binarySearch(offsetsMeters, offset);
            double intensity;
            double intensityDerivative;
            if (exact >= 0) {
                if (exact == 0 || exact == offsetsMeters.length - 1
                        || !Double.isFinite(values[exact - 1]) || !Double.isFinite(values[exact])
                        || !Double.isFinite(values[exact + 1])) {
                    return Optional.empty();
                }
                intensity = values[exact];
                double leftSlope = (values[exact] - values[exact - 1])
                        / (offsetsMeters[exact] - offsetsMeters[exact - 1]);
                double rightSlope = (values[exact + 1] - values[exact])
                        / (offsetsMeters[exact + 1] - offsetsMeters[exact]);
                intensityDerivative = 0.5 * (leftSlope + rightSlope);
            } else {
                int upper = -exact - 1;
                if (upper <= 0 || upper >= offsetsMeters.length
                        || !Double.isFinite(values[upper - 1]) || !Double.isFinite(values[upper])) {
                    return Optional.empty();
                }
                int lower = upper - 1;
                double span = offsetsMeters[upper] - offsetsMeters[lower];
                double fraction = (offset - offsetsMeters[lower]) / span;
                intensity = values[lower] + fraction * (values[upper] - values[lower]);
                intensityDerivative = (values[upper] - values[lower]) / span;
            }
            double responseRange = peakIntensity - noiseFloor;
            if (!(responseRange > 1.0e-12)) {
                return Optional.empty();
            }
            double response = (intensity - noiseFloor) / responseRange;
            double clippedResponse = Math.max(1.0e-6, Math.min(1.0, response));
            double responseDerivative = response > 1.0e-6 && response < 1.0
                    ? intensityDerivative / responseRange : 0.0;
            double presenceCost = -StrictMath.log(clippedResponse);
            double presenceDerivative = -responseDerivative / clippedResponse;
            double distance;
            double distanceSign;
            if (offset < coreMinimumMeters) {
                distance = coreMinimumMeters - offset;
                distanceSign = -1.0;
            } else if (offset > coreMaximumMeters) {
                distance = offset - coreMaximumMeters;
                distanceSign = 1.0;
            } else {
                distance = 0.0;
                distanceSign = 0.0;
            }
            double normalized = distance / localizationSigmaMeters;
            double centerCost = EvidenceModelParameters.huber(normalized);
            double centerDerivative = distanceSign * Math.min(1.0, normalized)
                    / localizationSigmaMeters;
            double derivative = presenceDerivative + centerDerivative;
            return Optional.of(new FrozenSample(presenceCost + centerCost,
                    derivative * normal.xMeters(), derivative * normal.yMeters()));
        }
    }
    /** Interpolated scalar evidence costs and their analytic metric-coordinate gradients. */
    public record Sample(double intensity, double presenceCost, double centerCost,
            double presenceGradientX, double presenceGradientY,
            double centerGradientX, double centerGradientY) {
    }

    /** Background-relative evidence measured on the normal of an actual proposed route segment. */
    public record RouteSample(MetricPoint normal, double rawIntensity, double noiseFloor,
            double peakIntensity, double presenceResponse, double presenceCost, double centerCost,
            double imageEnergy, double existenceConfidence, double localizationConfidence,
            boolean directlyLocalized, ObservationOwnership ownership) {
        /** Compatibility constructor deriving the prior coarse ownership categories. */
        public RouteSample(MetricPoint normal, double rawIntensity, double noiseFloor,
                double peakIntensity, double presenceResponse, double presenceCost, double centerCost,
                double imageEnergy, double existenceConfidence, double localizationConfidence,
                boolean directlyLocalized) {
            this(normal, rawIntensity, noiseFloor, peakIntensity, presenceResponse, presenceCost,
                    centerCost, imageEnergy, existenceConfidence, localizationConfidence,
                    directlyLocalized, directlyLocalized ? ObservationOwnership.DIRECT_TWO_SIDED
                            : localizationConfidence > 0.0 || Double.isFinite(centerCost)
                                    ? ObservationOwnership.DIRECT_AMBIGUOUS
                                    : existenceConfidence > 0.0
                                            ? ObservationOwnership.CORE_CENSORED
                                            : ObservationOwnership.NO_SIGNAL_VALID_RASTER);
        }

        /** Requires an explicit ownership classification for the current route query. */
        public RouteSample {
            boolean directOwnership = ownership == ObservationOwnership.DIRECT_TWO_SIDED;
            boolean measuredOwnership = directOwnership
                    || ownership == ObservationOwnership.DIRECT_AMBIGUOUS
                    || ownership == ObservationOwnership.CORE_CENSORED
                    || ownership == ObservationOwnership.SHOULDER_CENSORED
                    || ownership == ObservationOwnership.NO_SIGNAL_VALID_RASTER;
            if (normal == null || ownership == null || !measuredOwnership
                    || directlyLocalized != directOwnership) {
                throw new IllegalArgumentException("Route sample ownership is inconsistent");
            }
        }
    }

    private final int width;
    private final int height;
    private final double[] intensity;
    private final boolean[] valid;
    private final boolean[] interpolationValid;
    private final ScalarEvidenceField scalarField;
    private final RasterMetricTransform transform;
    private final MetricRegion decisionRegion;
    private final double sourcePitchMeters;

    /** Creates a deep immutable image field from already mapped scalar evidence. */
    public ImageCostField(ScalarEvidenceField field, RasterMetricTransform transform,
            MetricRegion decisionRegion, double sourcePitchMeters) {
        if (field == null || transform == null || decisionRegion == null
                || !Double.isFinite(sourcePitchMeters) || sourcePitchMeters <= 0.0) {
            throw new IllegalArgumentException("Image cost field inputs must be complete and physical");
        }
        this.width = field.width();
        this.height = field.height();
        this.intensity = field.copiedValues();
        this.valid = field.copiedValidity();
        this.interpolationValid = field.copiedInterpolationValidity();
        this.scalarField = field;
        this.transform = transform;
        this.decisionRegion = decisionRegion;
        this.sourcePitchMeters = sourcePitchMeters;
    }

    /** Creates a frozen field for one named immutable evidence-snapshot scalar source. */
    public static ImageCostField fromEvidence(EvidenceSnapshot evidence, String fieldName) {
        if (evidence == null || fieldName == null || fieldName.isBlank()
                || !evidence.fields().containsKey(fieldName)) {
            throw new IllegalArgumentException("Named scalar evidence is unavailable");
        }
        return new ImageCostField(evidence.fields().get(fieldName), evidence.transform(),
                evidence.decisionRegion(), evidence.resolution().effectivePitchMeters());
    }

    /** Returns the physical source-pixel pitch used for uncertainty and trust limits. */
    public double sourcePitchMeters() {
        return sourcePitchMeters;
    }

    /** Freezes one selected scalar mode and its local interpolation field at a route point. */
    public FrozenProfile freezeProfile(MetricPoint point, MetricPoint routeTangent) {
        return freezeProfile(point, routeTangent, CancellationProbe.NONE);
    }

    /** Freezes one profile with bounded cooperative cancellation during scalar sampling. */
    public FrozenProfile freezeProfile(MetricPoint point, MetricPoint routeTangent,
            CancellationProbe cancellation) {
        if (point == null || routeTangent == null) {
            throw new IllegalArgumentException("Frozen profile requires a point and tangent");
        }
        if (cancellation == null) {
            throw new IllegalArgumentException("Frozen profile cancellation probe is required");
        }
        double tangentLength = StrictMath.hypot(routeTangent.xMeters(), routeTangent.yMeters());
        if (!(tangentLength > 0.0) || !Double.isFinite(tangentLength)) {
            throw new IllegalArgumentException("Frozen profile tangent must be finite and nonzero");
        }
        MetricPoint normal = new MetricPoint(-routeTangent.yMeters() / tangentLength,
                routeTangent.xMeters() / tangentLength);
        EvidenceModelParameters.Localization parameters = EvidenceModelParameters.defaults().localization();
        double halfWidth = Math.max(parameters.routeProfileHalfWidthMeters(), 4.0 * sourcePitchMeters);
        double requestedIntervals = Math.max(4.0,
                Math.ceil(2.0 * halfWidth / (sourcePitchMeters * 0.25)));
        if (!Double.isFinite(requestedIntervals)
                || requestedIntervals + 1.0 > MAXIMUM_FROZEN_PROFILE_SAMPLES) {
            throw new IllegalArgumentException("Frozen profile exceeds the deterministic resource bound");
        }
        int intervals = (int) requestedIntervals;
        // Put offset zero inside one fixed interpolation interval, avoiding a
        // derivative convention change at the initial numerical point.
        if ((intervals & 1) == 0) {
            intervals++;
        }
        double[] offsets = new double[intervals + 1];
        double[] values = new double[intervals + 1];
        List<LocalScalarProfileExtractor.Sample> samples = new ArrayList<>(intervals + 1);
        for (int index = 0; index <= intervals; index++) {
            if ((index & 1023) == 0) cancellation.checkpoint();
            double offset = -halfWidth + 2.0 * halfWidth * index / intervals;
            offsets[index] = offset;
            MetricPoint location = new MetricPoint(point.xMeters() + normal.xMeters() * offset,
                    point.yMeters() + normal.yMeters() * offset);
            OptionalDouble value = sampleScalar(location);
            values[index] = value.orElse(Double.NaN);
            samples.add(new LocalScalarProfileExtractor.Sample(offset, values[index], value.isPresent()));
        }
        LocalScalarProfileExtractor.Result extracted = new LocalScalarProfileExtractor().extract(samples,
                sourcePitchMeters, parameters);
        List<LocalScalarProfileExtractor.Mode> ordered = extracted.modes().stream()
                .sorted(Comparator.comparingDouble(mode -> mode.distanceToCenterSet(0.0))).toList();
        if (ordered.isEmpty()) {
            return unavailable(FrozenSupport.MISSING, point, normal, offsets, values,
                    extracted.noiseFloor(), extracted.maximumIntensity());
        }
        LocalScalarProfileExtractor.Mode selected = ordered.get(0);
        if (ordered.size() > 1 && ordered.get(1).distanceToCenterSet(0.0)
                <= selected.distanceToCenterSet(0.0) + sourcePitchMeters) {
            return unavailable(FrozenSupport.AMBIGUOUS, point, normal, offsets, values,
                    extracted.noiseFloor(), extracted.maximumIntensity());
        }
        Orientation orientation = measureOrientation(point, routeTangent, cancellation);
        double routeBearing = normalizeBearing(StrictMath.atan2(routeTangent.yMeters(), routeTangent.xMeters()));
        return new FrozenProfile(FrozenSupport.MEASURED, point, normal,
                selected.coreMinimumMeters(), selected.coreMaximumMeters(),
                Math.max(sourcePitchMeters * 0.5, selected.localizationSigmaMeters()),
                extracted.noiseFloor(), extracted.maximumIntensity(),
                offsets, values, orientation.modes(),
                orientation.measured() ? orientation.radians() : routeBearing,
                orientation.measured() ? orientation.certainty() : 0.0,
                selected.scalarAmplitudeReliability() + (1.0 - selected.scalarAmplitudeReliability())
                    * (orientation.measured() ? orientation.certainty() : 0.0));
    }

    /** Returns whether a point has complete bilinear image support inside the decision region. */
    public boolean supports(MetricPoint point) {
        return sample(point).isPresent();
    }

    /**
     * Re-extracts shared scalar features along the supplied route tangent.
     * A valid query with no complete two-sided mode is returned as unknown, not as localization.
     */
    public Optional<RouteSample> sampleRoute(MetricPoint point, MetricPoint routeTangent) {
        if (point == null || routeTangent == null) {
            throw new IllegalArgumentException("Route-local sampling requires a point and tangent");
        }
        double tangentLength = StrictMath.hypot(routeTangent.xMeters(), routeTangent.yMeters());
        if (!(tangentLength > 0.0) || !Double.isFinite(tangentLength)) {
            throw new IllegalArgumentException("Route tangent must be finite and nonzero");
        }
        OptionalDouble raw = StrictScalarSampler.sample(scalarField, transform, decisionRegion, point);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        MetricPoint normal = new MetricPoint(-routeTangent.yMeters() / tangentLength,
                routeTangent.xMeters() / tangentLength);
        EvidenceModelParameters.Localization parameters = EvidenceModelParameters.defaults().localization();
        double halfWidth = Math.max(parameters.routeProfileHalfWidthMeters(), 4.0 * sourcePitchMeters);
        int intervals = Math.max(4, (int) Math.ceil(2.0 * halfWidth / (sourcePitchMeters * 0.5)));
        if ((intervals & 1) != 0) {
            intervals++;
        }
        java.util.List<LocalScalarProfileExtractor.Sample> samples = new java.util.ArrayList<>(intervals + 1);
        for (int index = 0; index <= intervals; index++) {
            double offset = -halfWidth + 2.0 * halfWidth * index / intervals;
            MetricPoint location = new MetricPoint(point.xMeters() + normal.xMeters() * offset,
                    point.yMeters() + normal.yMeters() * offset);
            OptionalDouble value = StrictScalarSampler.sample(scalarField, transform, decisionRegion, location);
            samples.add(new LocalScalarProfileExtractor.Sample(offset,
                    value.orElse(Double.NaN), value.isPresent()));
        }
        LocalScalarProfileExtractor.Result features = new LocalScalarProfileExtractor().extract(samples,
                sourcePitchMeters, parameters);
        double responseRange = features.maximumIntensity() - features.noiseFloor();
        double presenceResponse = responseRange > 0.0
                ? clamp((raw.getAsDouble() - features.noiseFloor()) / responseRange) : 0.0;
        if (features.modes().isEmpty() && features.censoredModes().isEmpty()) {
            double presenceCost = responseRange > 0.0
                    ? -StrictMath.log(Math.max(1.0e-6, presenceResponse)) : Double.POSITIVE_INFINITY;
            return Optional.of(new RouteSample(normal, raw.getAsDouble(), features.noiseFloor(),
                    features.maximumIntensity(), presenceResponse, presenceCost,
                    Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0.0, 0.0, false,
                    ObservationOwnership.NO_SIGNAL_VALID_RASTER));
        }
        LocalScalarProfileExtractor.Mode nearestMode = features.modes().stream()
                .min(java.util.Comparator.comparingDouble(mode -> mode.distanceToCenterSet(0.0)))
                .orElse(null);
        LocalScalarProfileExtractor.CensoredMode nearestCensored = features.censoredModes().stream()
                    .min(java.util.Comparator.comparingDouble(mode -> Math.abs(mode.boundaryOffsetMeters())))
                    .orElse(null);
        if (nearestMode == null || nearestCensored != null
                && Math.abs(nearestCensored.boundaryOffsetMeters())
                        < nearestMode.distanceToCenterSet(0.0)) {
            double directedDistance = nearestCensored.side() == LocalScalarProfileExtractor.CensorSide.RIGHT
                    ? Math.max(0.0, nearestCensored.boundaryOffsetMeters())
                    : Math.max(0.0, -nearestCensored.boundaryOffsetMeters());
            double presenceCost = -StrictMath.log(Math.max(1.0e-6, presenceResponse));
            double directionalCost = nearestCensored.gradientTowardEdge()
                    ? EvidenceModelParameters.huber(directedDistance / sourcePitchMeters) : 0.0;
            return Optional.of(new RouteSample(normal, raw.getAsDouble(), features.noiseFloor(),
                    features.maximumIntensity(), presenceResponse, presenceCost,
                    Double.POSITIVE_INFINITY, presenceCost + directionalCost,
                    nearestCensored.existenceConfidence(), 0.0, false,
                    censoredOwnership(samples, nearestCensored, features.noiseFloor(),
                            parameters.localModeCoreFraction())));
        }
        LocalScalarProfileExtractor.Mode selected = nearestMode;
        double distance = selected.distanceToCenterSet(0.0);
        double normalizedDistance = distance
                / Math.max(sourcePitchMeters * 0.5, selected.localizationSigmaMeters());
        double centerCost = EvidenceModelParameters.huber(normalizedDistance);
        double presenceCost = -StrictMath.log(Math.max(1.0e-6, presenceResponse));
        boolean directlyLocalized = selected.localizationConfidence() > 0.0
                && distance <= selected.localizationSigmaMeters() + 1.0e-12;
        return Optional.of(new RouteSample(normal, raw.getAsDouble(), features.noiseFloor(),
                features.maximumIntensity(), presenceResponse, presenceCost, centerCost,
                presenceCost + centerCost, selected.existenceConfidence(),
                selected.localizationConfidence(), directlyLocalized,
                directlyLocalized ? ObservationOwnership.DIRECT_TWO_SIDED
                        : ObservationOwnership.DIRECT_AMBIGUOUS));
    }

    private static ObservationOwnership censoredOwnership(
            java.util.List<LocalScalarProfileExtractor.Sample> samples,
            LocalScalarProfileExtractor.CensoredMode censored, double noiseFloor,
            double coreFraction) {
        int edge = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < samples.size(); index++) {
            double distance = Math.abs(samples.get(index).offsetMeters()
                    - censored.boundaryOffsetMeters());
            if (distance < bestDistance) {
                edge = index;
                bestDistance = distance;
            }
        }
        int direction = censored.side() == LocalScalarProfileExtractor.CensorSide.LEFT ? 1 : -1;
        double edgeIntensity = samples.get(edge).intensity();
        double localPeak = edgeIntensity;
        double previous = edgeIntensity;
        boolean descending = false;
        for (int index = edge + direction; index >= 0 && index < samples.size();
                index += direction) {
            double value = samples.get(index).intensity();
            if (!samples.get(index).valid() || !Double.isFinite(value)) {
                break;
            }
            if (descending && value > previous + 1.0e-12) {
                break;
            }
            localPeak = Math.max(localPeak, value);
            descending |= value < previous - 1.0e-12;
            previous = value;
        }
        double coreThreshold = noiseFloor + coreFraction * Math.max(0.0, localPeak - noiseFloor);
        return edgeIntensity + 1.0e-12 >= coreThreshold
                ? ObservationOwnership.CORE_CENSORED
                : ObservationOwnership.SHOULDER_CENSORED;
    }

    /** Returns mean nonnegative route-local image energy, or infinity when evidence is unknown. */
    public double meanRouteSegmentCost(MetricPoint start, MetricPoint end) {
        MetricPoint tangent = new MetricPoint(end.xMeters() - start.xMeters(),
                end.yMeters() - start.yMeters());
        double length = start.distanceTo(end);
        if (!(length > 0.0)) {
            return Double.POSITIVE_INFINITY;
        }
        int samples = Math.max(1, (int) Math.ceil(length / Math.min(2.0, sourcePitchMeters / 2.0)));
        double total = 0.0;
        for (int index = 0; index < samples; index++) {
            Optional<RouteSample> value = sampleRoute(interpolate(start, end,
                    (index + 0.5) / samples), tangent);
            if (value.isEmpty() || !Double.isFinite(value.orElseThrow().imageEnergy())) {
                return Double.POSITIVE_INFINITY;
            }
            total += value.orElseThrow().imageEnergy();
        }
        return total / samples;
    }

    /** Returns physical-length-weighted route-local image energy for a polyline. */
    public double meanRoutePolylineCost(java.util.List<MetricPoint> points) {
        double weighted = 0.0;
        double length = 0.0;
        for (int index = 1; index < points.size(); index++) {
            double segmentLength = points.get(index - 1).distanceTo(points.get(index));
            if (segmentLength <= 1.0e-12) {
                continue;
            }
            double cost = meanRouteSegmentCost(points.get(index - 1), points.get(index));
            if (!Double.isFinite(cost)) {
                return Double.POSITIVE_INFINITY;
            }
            weighted += segmentLength * cost;
            length += segmentLength;
        }
        return length > 0.0 ? weighted / length : Double.POSITIVE_INFINITY;
    }


    /**
     * Returns bilinear center cost and its analytic gradient at a metric point.
     *
     * <p>The center cost is {@code (1-intensity)^2}; it is common across engines and has no
     * engine-objective semantics.</p>
     */
    public Optional<Sample> sample(MetricPoint point) {
        if (!decisionRegion.contains(point)) {
            return Optional.empty();
        }
        RasterPoint raster = transform.metricToPixelCenter(point);
        int x0 = (int) Math.floor(raster.x());
        int y0 = (int) Math.floor(raster.y());
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        if (x0 < 0 || y0 < 0 || x1 >= width || y1 >= height
                || !interpolationValid[y0 * (width - 1) + x0]
                || !isValid(x0, y0) || !isValid(x1, y0) || !isValid(x0, y1) || !isValid(x1, y1)) {
            return Optional.empty();
        }
        double tx = raster.x() - x0;
        double ty = raster.y() - y0;
        double i00 = value(x0, y0);
        double i10 = value(x1, y0);
        double i01 = value(x0, y1);
        double i11 = value(x1, y1);
        double top = i00 + tx * (i10 - i00);
        double bottom = i01 + tx * (i11 - i01);
        double interpolated = top + ty * (bottom - top);
        double derivativeRasterX = symmetricDerivativeX(x0, y0, ty, tx, i00, i10, i01, i11);
        double derivativeRasterY = symmetricDerivativeY(x0, y0, tx, ty, i00, i10, i01, i11);

        double determinant = transform.xAxisEastMetersPerSourcePixel()
                * transform.yAxisNorthMetersPerSourcePixel()
                - transform.xAxisNorthMetersPerSourcePixel()
                * transform.yAxisEastMetersPerSourcePixel();
        double scale = transform.rasterPixelsPerSourcePixel();
        double drxDmx = scale * transform.yAxisNorthMetersPerSourcePixel() / determinant;
        double drxDmy = -scale * transform.yAxisEastMetersPerSourcePixel() / determinant;
        double dryDmx = -scale * transform.xAxisNorthMetersPerSourcePixel() / determinant;
        double dryDmy = scale * transform.xAxisEastMetersPerSourcePixel() / determinant;
        double gradientIntensityX = derivativeRasterX * drxDmx + derivativeRasterY * dryDmx;
        double gradientIntensityY = derivativeRasterX * drxDmy + derivativeRasterY * dryDmy;
        double presence = Math.max(1.0e-6, interpolated);
        double residual = 1.0 - interpolated;
        return Optional.of(new Sample(interpolated, -StrictMath.log(presence), residual * residual,
                -gradientIntensityX / presence, -gradientIntensityY / presence,
                -2.0 * residual * gradientIntensityX, -2.0 * residual * gradientIntensityY));
    }

    /** Returns mean center cost along a segment, or positive infinity when support is incomplete. */
    public double meanSegmentCost(MetricPoint start, MetricPoint end) {
        double length = start.distanceTo(end);
        int samples = Math.max(1, (int) Math.ceil(length / Math.min(2.0, sourcePitchMeters / 2.0)));
        double total = 0.0;
        for (int index = 0; index < samples; index++) {
            double fraction = (index + 0.5) / samples;
            MetricPoint point = interpolate(start, end, fraction);
            Optional<Sample> sample = sample(point);
            if (sample.isEmpty()) {
                return Double.POSITIVE_INFINITY;
            }
            total += sample.orElseThrow().centerCost();
        }
        return total / samples;
    }

    /** Returns mean center cost along a polyline, weighted by physical segment length. */
    public double meanPolylineCost(java.util.List<MetricPoint> points) {
        double weighted = 0.0;
        double length = 0.0;
        for (int index = 1; index < points.size(); index++) {
            double segmentLength = points.get(index - 1).distanceTo(points.get(index));
            double cost = meanSegmentCost(points.get(index - 1), points.get(index));
            if (!Double.isFinite(cost)) {
                return Double.POSITIVE_INFINITY;
            }
            weighted += segmentLength * cost;
            length += segmentLength;
        }
        return length > 0.0 ? weighted / length : Double.POSITIVE_INFINITY;
    }

    private boolean isValid(int x, int y) {
        return valid[y * width + x];
    }

    private OptionalDouble sampleScalar(MetricPoint point) {
        return StrictScalarSampler.sample(scalarField, transform, decisionRegion, point);
    }

    private FrozenProfile unavailable(FrozenSupport support, MetricPoint point, MetricPoint normal,
            double[] offsets, double[] values, double noiseFloor, double peakIntensity) {
        return new FrozenProfile(support, point, normal, 0.0, 0.0, sourcePitchMeters,
                noiseFloor, peakIntensity, offsets, values, List.of(), 0.0, 0.0);
    }

    private Orientation measureOrientation(MetricPoint center, MetricPoint routeTangent,
            CancellationProbe cancellation) {
        EvidenceModelParameters.Localization parameters = EvidenceModelParameters.defaults().localization();
        int headings = parameters.orientationHeadingCount();
        double rayLength = Math.max(parameters.minimumOrientationRayMeters(),
                parameters.orientationRayLengthPitches() * sourcePitchMeters);
        int samples = Math.max(2, (int) Math.ceil((rayLength - sourcePitchMeters)
                / (parameters.maximumOrientationStepPitches() * sourcePitchMeters)) + 1);
        if ((long) headings * 2L * samples > parameters.maximumOrientationSampleCount()) {
            return Orientation.UNKNOWN;
        }
        double[][][] rays = new double[headings][2][samples];
        double[] backgroundValues = new double[headings * 2 * samples + 1];
        int backgroundCount = 0;
        OptionalDouble centerValue = sampleScalar(center);
        if (centerValue.isEmpty()) {
            return Orientation.UNKNOWN;
        }
        backgroundValues[backgroundCount++] = centerValue.getAsDouble();
        for (int heading = 0; heading < headings; heading++) {
            cancellation.checkpoint();
            double angle = Math.PI * heading / headings;
            for (int side = 0; side < 2; side++) {
                Arrays.fill(rays[heading][side], Double.NaN);
                double sign = side == 0 ? 1.0 : -1.0;
                for (int index = 0; index < samples; index++) {
                    if ((index & 1023) == 0) cancellation.checkpoint();
                    double distance = sourcePitchMeters + (rayLength - sourcePitchMeters)
                            * index / (samples - 1.0);
                    MetricPoint point = new MetricPoint(center.xMeters()
                            + sign * StrictMath.cos(angle) * distance,
                            center.yMeters() + sign * StrictMath.sin(angle) * distance);
                    OptionalDouble value = sampleScalar(point);
                    if (value.isPresent()) {
                        rays[heading][side][index] = value.getAsDouble();
                        backgroundValues[backgroundCount++] = value.getAsDouble();
                    }
                }
            }
        }
        Arrays.sort(backgroundValues, 0, backgroundCount);
        int quantileIndex = Math.min(backgroundCount - 1,
                (int) Math.floor(parameters.orientationBackgroundQuantile() * (backgroundCount - 1)));
        double background = backgroundValues[quantileIndex];
        double[] response = new double[headings];
        double[] validFraction = new double[headings];
        Arrays.fill(response, Double.NaN);
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (int heading = 0; heading < headings; heading++) {
            double[] means = new double[2];
            double[] fractions = new double[2];
            boolean complete = true;
            for (int side = 0; side < 2; side++) {
                int validCount = 0;
                double total = 0.0;
                for (double value : rays[heading][side]) {
                    if (Double.isFinite(value)) {
                        validCount++;
                        total += Math.max(0.0, value - background);
                    }
                }
                fractions[side] = validCount / (double) samples;
                complete &= fractions[side] + 1.0e-12 >= parameters.minimumOrientationValidFraction();
                means[side] = validCount == 0 ? 0.0 : total / validCount;
            }
            if (complete) {
                response[heading] = Math.min(means[0], means[1]);
                validFraction[heading] = Math.min(fractions[0], fractions[1]);
                minimum = Math.min(minimum, response[heading]);
                maximum = Math.max(maximum, response[heading]);
            }
        }
        double range = maximum - minimum;
        if (!Double.isFinite(range) || !(maximum > 1.0e-12) || !(range > 1.0e-12)) {
            return Orientation.UNKNOWN;
        }
        ImageOrientationSupport support = extractOrientationModes(response, validFraction,
                minimum, maximum, parameters);
        if (support.status() != ImageOrientationSupport.Status.MEASURED_TWO_SIDED) {
            return Orientation.UNKNOWN;
        }
        double routeAngle = normalizeBearing(StrictMath.atan2(routeTangent.yMeters(), routeTangent.xMeters()));
        ImageOrientationSupport.AngularMode selectedMode = support.modes().stream()
                .min(Comparator.comparingDouble(mode -> mode.distanceTo(routeAngle))).orElseThrow();
        double selected = selectedMode.contains(routeAngle) ? routeAngle
                : undirectedDistance(routeAngle, selectedMode.startRadians())
                        <= undirectedDistance(routeAngle, selectedMode.endRadians())
                                ? selectedMode.startRadians() : selectedMode.endRadians();
        return new Orientation(true, selected, support.certainty(), support.modes());
    }

    private static ImageOrientationSupport extractOrientationModes(double[] response,
            double[] validFraction, double minimum, double maximum,
            EvidenceModelParameters.Localization parameters) {
        double range = maximum - minimum;
        int count = response.length;
        boolean[] visited = new boolean[count];
        List<ImageOrientationSupport.AngularMode> modes = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            if (visited[index] || !Double.isFinite(response[index])) continue;
            int previous = Math.floorMod(index - 1, count);
            int next = (index + 1) % count;
            double value = response[index];
            if (!Double.isFinite(response[previous]) || !Double.isFinite(response[next])
                    || value + 1.0e-12 < response[previous] || value + 1.0e-12 < response[next]
                    || !(value > response[previous] + 1.0e-12 || value > response[next] + 1.0e-12)) {
                continue;
            }
            int start = index;
            int end = index;
            while (Math.floorMod(start - 1, count) != end
                    && equalOrientationResponse(response, Math.floorMod(start - 1, count), value)) {
                start = Math.floorMod(start - 1, count);
            }
            while ((end + 1) % count != start
                    && equalOrientationResponse(response, (end + 1) % count, value)) {
                end = (end + 1) % count;
            }
            int outsideBefore = Math.floorMod(start - 1, count);
            int outsideAfter = (end + 1) % count;
            if (!Double.isFinite(response[outsideBefore]) || !Double.isFinite(response[outsideAfter])
                    || !(value > response[outsideBefore] + 1.0e-12)
                    || !(value > response[outsideAfter] + 1.0e-12)
                    || value - minimum + 1.0e-12
                            < parameters.orientationProminenceFraction() * range) {
                continue;
            }
            int plateauSize = 1;
            for (int cursor = start; cursor != end; cursor = (cursor + 1) % count) plateauSize++;
            for (int cursor = start;; cursor = (cursor + 1) % count) {
                visited[cursor] = true;
                if (cursor == end) break;
            }
            double step = Math.PI / count;
            double peak = plateauSize == 1 ? interpolateOrientationPeak(response, index, step)
                    : ImageOrientationSupport.normalize((start + 0.5 * (plateauSize - 1)) * step);
            modes.add(new ImageOrientationSupport.AngularMode(
                    plateauSize == 1 ? peak : start * step,
                    plateauSize == 1 ? peak : end * step, peak, value));
        }
        if (modes.isEmpty()) {
            return ImageOrientationSupport.unknown(
                    ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT);
        }
        modes.sort(Comparator.comparingDouble(ImageOrientationSupport.AngularMode::peakBearingRadians));
        double minimumRayFraction = java.util.stream.IntStream.range(0, count)
                .filter(index -> Double.isFinite(response[index]))
                .mapToDouble(index -> validFraction[index]).min().orElse(0.0);
        double certainty = Math.max(0.0, Math.min(1.0, range / Math.max(maximum, 1.0e-12)))
                * minimumRayFraction;
        return new ImageOrientationSupport(ImageOrientationSupport.Status.MEASURED_TWO_SIDED,
                modes, certainty);
    }

    private static boolean equalOrientationResponse(double[] response, int index, double value) {
        return Double.isFinite(response[index]) && Math.abs(response[index] - value) <= 1.0e-12;
    }

    private static double interpolateOrientationPeak(double[] response, int index, double step) {
        double left = response[Math.floorMod(index - 1, response.length)];
        double center = response[index];
        double right = response[(index + 1) % response.length];
        double denominator = left - 2.0 * center + right;
        if (Math.abs(denominator) <= 1.0e-15) return index * step;
        double offset = 0.5 * (left - right) / denominator;
        return !Double.isFinite(offset) || Math.abs(offset) > 1.0 ? index * step
                : ImageOrientationSupport.normalize((index + offset) * step);
    }

    private static double normalizeBearing(double value) {
        double result = value % Math.PI;
        return result < 0.0 ? result + Math.PI : result;
    }

    private static double undirectedDistance(double first, double second) {
        double difference = Math.abs(normalizeBearing(first) - normalizeBearing(second));
        return Math.min(difference, Math.PI - difference);
    }

    private record Orientation(boolean measured, double radians, double certainty,
            List<ImageOrientationSupport.AngularMode> modes) {
        private static final Orientation UNKNOWN = new Orientation(false, 0.0, 0.0, List.of());
    }

    private double value(int x, int y) {
        return intensity[y * width + x];
    }

    private double symmetricDerivativeX(int x0, int y0, double ty, double tx,
            double i00, double i10, double i01, double i11) {
        double right = (1.0 - ty) * (i10 - i00) + ty * (i11 - i01);
        if (Math.abs(tx) > 1.0e-10 || x0 == 0 || !isValid(x0 - 1, y0) || !isValid(x0 - 1, y0 + 1)) {
            return right;
        }
        double left = (1.0 - ty) * (i00 - value(x0 - 1, y0))
                + ty * (i01 - value(x0 - 1, y0 + 1));
        return 0.5 * (left + right);
    }

    private double symmetricDerivativeY(int x0, int y0, double tx, double ty,
            double i00, double i10, double i01, double i11) {
        double lower = (1.0 - tx) * (i01 - i00) + tx * (i11 - i10);
        if (Math.abs(ty) > 1.0e-10 || y0 == 0 || !isValid(x0, y0 - 1) || !isValid(x0 + 1, y0 - 1)) {
            return lower;
        }
        double upper = (1.0 - tx) * (i00 - value(x0, y0 - 1))
                + tx * (i10 - value(x0 + 1, y0 - 1));
        return 0.5 * (upper + lower);
    }

    private static MetricPoint interpolate(MetricPoint start, MetricPoint end, double fraction) {
        return new MetricPoint(start.xMeters() + fraction * (end.xMeters() - start.xMeters()),
                start.yMeters() + fraction * (end.yMeters() - start.yMeters()));
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
