package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/**
 * Unweighted physical/source-resolution quality metrics for a corridor-aware candidate.
 *
 * @param tubeResidualMeanSourcePx mean absolute robust-center residual in source pixels
 * @param tubeResidualP95SourcePx p95 robust-center residual in source pixels
 * @param highFrequencyRmsSourcePx RMS local high-frequency residual in source pixels
 * @param highFrequencyP95SourcePx p95 local high-frequency residual in source pixels
 * @param nonSustainedHighFrequencyRmsSourcePx RMS high-frequency residual where motion lacks support
 * @param nonSustainedHighFrequencyP95SourcePx p95 high-frequency residual where motion lacks support
 * @param unsupportedReversalCount number of unsupported alternating lateral reversals
 * @param unsupportedReversalRatio unsupported reversal count divided by eligible transitions
 * @param p95DeltaSourcePx p95 first lateral difference in source pixels
 * @param p95AccelerationSourcePx p95 second lateral difference in source pixels
 * @param turnP95Degrees p95 absolute candidate turn
 * @param turnMaximumDegrees maximum absolute candidate turn
 * @param curvatureChangeP95Degrees p95 change in consecutive candidate turns
 * @param forwardProgressViolations number of segments that reverse relative to source progression
 * @param unsupportedExcursions number of short lateral excursions larger than 1.5 source pixels
 * @param maximumGapMeters longest selected-track evidence gap
 * @param endpointApproachMaximumTurnDegrees maximum turn inside modeled endpoint approaches
 * @param longitudinalPersistence physical longitudinal identity/stability score from zero to one
 * @param endpointApproachesSupported whether every modeled endpoint side has reliable evidence
 */
public record CorridorQuality(
    double tubeResidualMeanSourcePx,
    double tubeResidualP95SourcePx,
    double highFrequencyRmsSourcePx,
    double highFrequencyP95SourcePx,
    double nonSustainedHighFrequencyRmsSourcePx,
    double nonSustainedHighFrequencyP95SourcePx,
    int unsupportedReversalCount,
    double unsupportedReversalRatio,
    double p95DeltaSourcePx,
    double p95AccelerationSourcePx,
    double turnP95Degrees,
    double turnMaximumDegrees,
    double curvatureChangeP95Degrees,
    int forwardProgressViolations,
    int unsupportedExcursions,
    double maximumGapMeters,
    double endpointApproachMaximumTurnDegrees,
    double longitudinalPersistence,
    boolean endpointApproachesSupported
) {
    /**
     * Creates quality evidence without explicit non-sustained ripple fields.
     *
     * @param tubeResidualMeanSourcePx mean tube residual
     * @param tubeResidualP95SourcePx p95 tube residual
     * @param highFrequencyRmsSourcePx RMS high-frequency residual
     * @param highFrequencyP95SourcePx p95 high-frequency residual
     * @param p95DeltaSourcePx p95 first difference
     * @param p95AccelerationSourcePx p95 second difference
     * @param turnP95Degrees p95 turn
     * @param turnMaximumDegrees maximum turn
     * @param curvatureChangeP95Degrees p95 curvature change
     * @param forwardProgressViolations forward-progress violations
     * @param unsupportedExcursions unsupported excursions
     * @param maximumGapMeters maximum evidence gap
     * @param endpointApproachMaximumTurnDegrees maximum endpoint approach turn
     * @param longitudinalPersistence persistence score
     * @param endpointApproachesSupported whether endpoint approaches are supported
     */
    public CorridorQuality(
        double tubeResidualMeanSourcePx,
        double tubeResidualP95SourcePx,
        double highFrequencyRmsSourcePx,
        double highFrequencyP95SourcePx,
        double p95DeltaSourcePx,
        double p95AccelerationSourcePx,
        double turnP95Degrees,
        double turnMaximumDegrees,
        double curvatureChangeP95Degrees,
        int forwardProgressViolations,
        int unsupportedExcursions,
        double maximumGapMeters,
        double endpointApproachMaximumTurnDegrees,
        double longitudinalPersistence,
        boolean endpointApproachesSupported
    ) {
        this(tubeResidualMeanSourcePx, tubeResidualP95SourcePx, highFrequencyRmsSourcePx,
            highFrequencyP95SourcePx, highFrequencyRmsSourcePx, highFrequencyP95SourcePx,
            unsupportedExcursions, unsupportedExcursions > 0 ? 1.0 : 0.0,
            p95DeltaSourcePx, p95AccelerationSourcePx, turnP95Degrees, turnMaximumDegrees,
            curvatureChangeP95Degrees, forwardProgressViolations, unsupportedExcursions, maximumGapMeters,
            endpointApproachMaximumTurnDegrees, longitudinalPersistence, endpointApproachesSupported);
    }

    /**
     * Returns empty legacy-compatible quality evidence.
     *
     * @return neutral quality value
     */
    public static CorridorQuality empty() {
        return new CorridorQuality(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0.0, 0.0, 0.0, true);
    }

    /**
     * Returns whether this object contains corridor-aware measurements.
     *
     * @return true when persistence or any measured quantity is non-zero
     */
    public boolean measured() {
        return longitudinalPersistence > 0.0 || tubeResidualMeanSourcePx > 0.0
            || highFrequencyRmsSourcePx > 0.0 || nonSustainedHighFrequencyRmsSourcePx > 0.0
            || unsupportedReversalCount > 0 || turnMaximumDegrees > 0.0
            || forwardProgressViolations > 0 || unsupportedExcursions > 0
            || !endpointApproachesSupported;
    }

    /**
     * Serializes the quality metrics into a compact JSON object.
     *
     * @return JSON object containing every physical quality metric
     */
    public String toJson() {
        return "{"
            + "\"tubeResidualMeanSourcePx\":" + tubeResidualMeanSourcePx + ','
            + "\"tubeResidualP95SourcePx\":" + tubeResidualP95SourcePx + ','
            + "\"highFrequencyRmsSourcePx\":" + highFrequencyRmsSourcePx + ','
            + "\"highFrequencyP95SourcePx\":" + highFrequencyP95SourcePx + ','
            + "\"nonSustainedHighFrequencyRmsSourcePx\":" + nonSustainedHighFrequencyRmsSourcePx + ','
            + "\"nonSustainedHighFrequencyP95SourcePx\":" + nonSustainedHighFrequencyP95SourcePx + ','
            + "\"unsupportedReversalCount\":" + unsupportedReversalCount + ','
            + "\"unsupportedReversalRatio\":" + unsupportedReversalRatio + ','
            + "\"p95DeltaSourcePx\":" + p95DeltaSourcePx + ','
            + "\"p95AccelerationSourcePx\":" + p95AccelerationSourcePx + ','
            + "\"turnP95Degrees\":" + turnP95Degrees + ','
            + "\"turnMaximumDegrees\":" + turnMaximumDegrees + ','
            + "\"curvatureChangeP95Degrees\":" + curvatureChangeP95Degrees + ','
            + "\"forwardProgressViolations\":" + forwardProgressViolations + ','
            + "\"unsupportedExcursions\":" + unsupportedExcursions + ','
            + "\"maximumGapMeters\":" + maximumGapMeters + ','
            + "\"endpointApproachMaximumTurnDegrees\":" + endpointApproachMaximumTurnDegrees + ','
            + "\"longitudinalPersistence\":" + longitudinalPersistence + ','
            + "\"endpointApproachesSupported\":" + endpointApproachesSupported
            + '}';
    }
}
