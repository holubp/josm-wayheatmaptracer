package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.List;
import java.util.Objects;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Bounded detached scalar-to-profile input prerequisite for corridor-aware Engine A. */
public final class DetachedScalarProfileSampler {
    static final long MAX_ESTIMATED_BYTES = 128L * 1024L * 1024L;
    private static final double FRAME_TOLERANCE = 1.0e-9;
    private static final long BYTES_PER_PROFILE_SAMPLE = 256L;
    private static final long BYTES_PER_PROFILE = 1_024L;

    private final long maximumEstimatedBytes;

    /** Creates a sampler with the production component budget. */
    public DetachedScalarProfileSampler() {
        this(MAX_ESTIMATED_BYTES);
    }

    /** Creates a sampler with an explicit component budget for boundary verification. */
    DetachedScalarProfileSampler(long maximumEstimatedBytes) {
        if (maximumEstimatedBytes <= 0L || maximumEstimatedBytes > MAX_ESTIMATED_BYTES) {
            throw new IllegalArgumentException("Detached scalar profile budget is outside the component limit");
        }
        this.maximumEstimatedBytes = maximumEstimatedBytes;
    }

    /**
     * Samples detached scalar evidence into the existing multi-scale corridor profile contract.
     *
     * <p>The evidence snapshot owns source and output-raster pitch independently. Search width and
     * lateral step are supplied in ground metres and converted only through the factual output pitch.
     * The source pitch selects scale-space levels and remains the tracker's localization uncertainty.</p>
     *
     * @param evidence immutable scalar evidence snapshot
     * @param fieldName named scalar field from the snapshot
     * @param anchors ordered detached geographic/metric/raster sampling locations
     * @param searchHalfWidthMeters authorized lateral decision radius in ground metres
     * @param lateralStepMeters desired lateral sampling step in ground metres
     * @param cancellation cooperative worker cancellation probe
     * @return raw/B3/B5 cross-section diagnostics on selected L0/L1/L2-style levels
     */
    public MultiScaleProfileSet sample(EvidenceSnapshot evidence, String fieldName,
            List<DetachedProfileSamplingLocation> anchors, double searchHalfWidthMeters,
            double lateralStepMeters, CancellationProbe cancellation) {
        validateInputs(evidence, fieldName, anchors, searchHalfWidthMeters,
            lateralStepMeters, cancellation);
        cancellation.checkpoint();
        ScalarEvidenceField source = evidence.fields().get(fieldName);
        double outputPitchMeters = evidence.resolution().outputRasterPitchMeters();
        double maximumSourcePitchMeters = maximumSourcePitch(evidence, anchors);
        double sourcePitchRasterPixels = maximumSourcePitchMeters / outputPitchMeters;
        if (!Double.isFinite(sourcePitchRasterPixels) || sourcePitchRasterPixels <= 0.0
            || sourcePitchRasterPixels > Integer.MAX_VALUE / 4.0) {
            throw new IllegalArgumentException("Source pitch cannot be represented on the output raster");
        }
        double halfWidthRasterPixels = searchHalfWidthMeters / outputPitchMeters;
        double stepRasterPixels = lateralStepMeters / outputPitchMeters;
        int maximumReduction = Math.max(4, (int) Math.ceil(4.0 * sourcePitchRasterPixels));
        long estimate = estimatedMaterializationBytes(source.width(), source.height(), maximumReduction,
            anchors.size(), halfWidthRasterPixels, stepRasterPixels);
        if (estimate > maximumEstimatedBytes) {
            throw new IllegalArgumentException("Detached scalar profile materialization exceeds its component budget");
        }
        cancellation.checkpoint();
        List<DetachedProfileSamplingLocation> retainedAnchors = List.copyOf(anchors);
        ScalarIntensityField levelZero = ScalarIntensityField.fromEvidence(source, cancellation::checkpoint);
        return new RenderedHeatmapSampler().sampleMultiScaleProfilesOnDetachedField(
            levelZero, retainedAnchors, halfWidthRasterPixels, stepRasterPixels,
            sourcePitchRasterPixels, evidence, cancellation);
    }

    private static void validateInputs(EvidenceSnapshot evidence, String fieldName,
            List<DetachedProfileSamplingLocation> anchors, double searchHalfWidthMeters,
            double lateralStepMeters, CancellationProbe cancellation) {
        if (evidence == null || fieldName == null || fieldName.isBlank()
            || anchors == null || anchors.size() < 2
            || !Double.isFinite(searchHalfWidthMeters) || searchHalfWidthMeters <= 0.0
            || !Double.isFinite(lateralStepMeters) || lateralStepMeters <= 0.0
            || cancellation == null || !evidence.fields().containsKey(fieldName)) {
            throw new IllegalArgumentException("Detached scalar profile input is incomplete");
        }
        validateOutputRasterFrame(evidence);
        double previousChainage = -1.0;
        for (int index = 0; index < anchors.size(); index++) {
            DetachedProfileSamplingLocation anchor = Objects.requireNonNull(
                anchors.get(index), "detached sampling anchor");
            if (!anchor.coordinateFrame().equals(evidence.coordinateFrame())
                || !anchor.rasterTransform().equals(evidence.transform())) {
                throw new IllegalArgumentException("Detached sampling anchor uses a different evidence frame");
            }
            double chainage = anchor.cumulativeGroundDistanceMeters();
            if (index == 0 && Double.doubleToLongBits(chainage) != Double.doubleToLongBits(0.0)
                || index > 0 && chainage <= previousChainage
                || !evidence.routePositionAuthorized(anchor.metricPoint())) {
                throw new IllegalArgumentException(
                    "Detached sampling anchors require zero-based monotonic ground chainage inside the decision region");
            }
            previousChainage = chainage;
        }
        evidence.resolution().effectivePitchMetersAt(previousChainage);
    }

    private static double maximumSourcePitch(
        EvidenceSnapshot evidence,
        List<DetachedProfileSamplingLocation> anchors
    ) {
        double maximum = 0.0;
        for (DetachedProfileSamplingLocation anchor : anchors) {
            maximum = Math.max(maximum, evidence.resolution().effectivePitchMetersAt(
                anchor.cumulativeGroundDistanceMeters()));
        }
        return maximum;
    }

    private static void validateOutputRasterFrame(EvidenceSnapshot evidence) {
        RasterMetricTransform transform = evidence.transform();
        double scale = transform.axisUnit() == RasterMetricTransform.AxisUnit.SOURCE_PIXEL
            ? transform.rasterPixelsPerSourcePixel() : 1.0;
        double xEast = transform.xAxisEastMetersPerSourcePixel() / scale;
        double xNorth = transform.xAxisNorthMetersPerSourcePixel() / scale;
        double yEast = transform.yAxisEastMetersPerSourcePixel() / scale;
        double yNorth = transform.yAxisNorthMetersPerSourcePixel() / scale;
        double xPitch = Math.hypot(xEast, xNorth);
        double yPitch = Math.hypot(yEast, yNorth);
        double declaredPitch = evidence.resolution().outputRasterPitchMeters();
        double dot = xEast * yEast + xNorth * yNorth;
        if (!close(xPitch, declaredPitch) || !close(yPitch, declaredPitch)
            || Math.abs(dot) > FRAME_TOLERANCE * Math.max(1.0, xPitch * yPitch)) {
            throw new IllegalArgumentException(
                "Detached corridor sampling requires a rotated or axis-aligned orthonormal metric raster");
        }
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) <= FRAME_TOLERANCE
            * Math.max(1.0, Math.max(Math.abs(left), Math.abs(right)));
    }

    static long estimatedMaterializationBytes(int width, int height, int maximumReduction,
            int anchorCount, double halfWidthRasterPixels, double stepRasterPixels) {
        if (anchorCount < 2 || !Double.isFinite(halfWidthRasterPixels)
            || halfWidthRasterPixels <= 0.0 || !Double.isFinite(stepRasterPixels)
            || stepRasterPixels <= 0.0) {
            throw new IllegalArgumentException("Detached scalar profile estimate inputs are inconsistent");
        }
        try {
            long pyramidBytes = GaussianIntensityPyramid.estimatedBytesForDimensions(
                width, height, maximumReduction);
            double finestStep = Math.max(stepRasterPixels, 1.0) / 2.0;
            double sampleCountValue = Math.floor(2.0 * halfWidthRasterPixels / finestStep) + 2.0;
            if (!Double.isFinite(sampleCountValue) || sampleCountValue > Long.MAX_VALUE) {
                throw new ArithmeticException("profile sample count overflowed");
            }
            long samplesPerProfile = (long) sampleCountValue;
            long levelProfiles = Math.multiplyExact((long) anchorCount, 3L);
            long profileBytes = Math.addExact(
                Math.multiplyExact(Math.multiplyExact(levelProfiles, samplesPerProfile),
                    BYTES_PER_PROFILE_SAMPLE),
                Math.multiplyExact(levelProfiles, BYTES_PER_PROFILE));
            return Math.addExact(Math.multiplyExact(pyramidBytes, 2L), profileBytes);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Detached scalar profile estimate overflowed", exception);
        }
    }
}
