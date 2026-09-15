package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.Optional;
import java.util.OptionalDouble;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.StrictScalarSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

/**
 * Frozen scalar-image interpolation field used by refitting and final quality checks.
 *
 * <p>The field copies scalar intensity and validity at construction. Bilinear interpolation is
 * permitted only when all four source cells are valid; no edge clamping or missing-value fill is
 * performed.</p>
 */
public final class ImageCostField {
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
        double tangentLength = Math.hypot(routeTangent.xMeters(), routeTangent.yMeters());
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
                    ? -Math.log(Math.max(1.0e-6, presenceResponse)) : Double.POSITIVE_INFINITY;
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
            double presenceCost = -Math.log(Math.max(1.0e-6, presenceResponse));
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
        double presenceCost = -Math.log(Math.max(1.0e-6, presenceResponse));
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
        return Optional.of(new Sample(interpolated, -Math.log(presence), residual * residual,
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
