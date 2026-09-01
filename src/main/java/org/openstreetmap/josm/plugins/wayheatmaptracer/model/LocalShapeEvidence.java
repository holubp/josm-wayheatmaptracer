package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.util.Objects;

/**
 * Immutable profile-aligned multiscale evidence distinguishing wrinkles from supported bends.
 *
 * <p>Lateral centers are source-pixel normalized. Trend slope and curvature are respectively
 * source pixels per metre and source pixels per metre squared. A missing selected offset or
 * reversal spacing is represented by {@link Double#NaN}; bounded scores are always finite.</p>
 *
 * @param profileIndex profile index in the candidate sampling frame
 * @param chainageMeters cumulative ground distance in metres
 * @param sourcePixelPitchRasterPx native source-pixel pitch in sampled-raster pixels
 * @param provenance direct, interpolated, or unsupported evidence provenance
 * @param directCoverage bounded direct-evidence coverage
 * @param rawCenterSourcePx raw intensity center in source-pixel units
 * @param lightCenterSourcePx B3-filtered center in source-pixel units
 * @param standardCenterSourcePx B5-filtered center in source-pixel units
 * @param coreCenterSourcePx high-intensity core center in source-pixel units
 * @param localTubeCenterSourcePx local corridor-tube center in source-pixel units
 * @param stabilityTubeCenterSourcePx stable corridor-tube center in source-pixel units
 * @param effectiveTubeCenterSourcePx effective optimizer tube center in source-pixel units
 * @param selectedOffsetSourcePx selected candidate offset, or {@link Double#NaN}
 * @param trendCenterSourcePx robust local trend center in source-pixel units
 * @param trendSlopeSourcePxPerMeter robust trend slope in source pixels per metre
 * @param trendCurvatureSourcePxPerMeter2 robust trend curvature in source pixels per metre squared
 * @param trendUncertaintySourcePx robust trend uncertainty in source-pixel units
 * @param residualSourcePx signed residual from the local trend in source-pixel units
 * @param residualAmplitudeSourcePx robust residual amplitude in source-pixel units
 * @param reversalCount supported residual direction reversals in the local window
 * @param reversalSpacingMeters median physical spacing between reversals, or {@link Double#NaN}
 * @param scaleAgreement agreement of raw, B3, B5, and core centers
 * @param trendReliability bounded reliability of the selected trend
 * @param wrinkleScore bounded unsupported-wrinkle attribution
 * @param bendScore bounded coherent-bend attribution
 * @param ambiguityScore bounded ambiguity between competing explanations
 * @param cleanupIntervention bounded cleanup authorization
 * @param bendProtection bounded protection against flattening supported shape
 * @param decision typed local cleanup decision
 * @param reason primary reason for the decision
 */
public record LocalShapeEvidence(
    int profileIndex,
    double chainageMeters,
    double sourcePixelPitchRasterPx,
    CleanupEvidenceProvenance provenance,
    double directCoverage,
    double rawCenterSourcePx,
    double lightCenterSourcePx,
    double standardCenterSourcePx,
    double coreCenterSourcePx,
    double localTubeCenterSourcePx,
    double stabilityTubeCenterSourcePx,
    double effectiveTubeCenterSourcePx,
    double selectedOffsetSourcePx,
    double trendCenterSourcePx,
    double trendSlopeSourcePxPerMeter,
    double trendCurvatureSourcePxPerMeter2,
    double trendUncertaintySourcePx,
    double residualSourcePx,
    double residualAmplitudeSourcePx,
    int reversalCount,
    double reversalSpacingMeters,
    double scaleAgreement,
    double trendReliability,
    double wrinkleScore,
    double bendScore,
    double ambiguityScore,
    double cleanupIntervention,
    double bendProtection,
    Decision decision,
    Reason reason
) {
    /** Validates physical identity, finite metrics, bounded scores, and typed decisions. */
    public LocalShapeEvidence {
        provenance = Objects.requireNonNull(provenance, "provenance");
        decision = Objects.requireNonNull(decision, "decision");
        reason = Objects.requireNonNull(reason, "reason");
        if (profileIndex < 0 || !nonNegative(chainageMeters)
            || !positive(sourcePixelPitchRasterPx)
            || !nonNegative(trendUncertaintySourcePx)
            || !nonNegative(residualAmplitudeSourcePx) || reversalCount < 0
            || !(Double.isNaN(reversalSpacingMeters) || nonNegative(reversalSpacingMeters))) {
            throw new IllegalArgumentException("Local shape physical metrics are invalid");
        }
        for (double value : new double[] {directCoverage, scaleAgreement, trendReliability,
                wrinkleScore, bendScore, ambiguityScore, cleanupIntervention, bendProtection}) {
            if (!ratio(value)) {
                throw new IllegalArgumentException("Local shape scores must be in [0, 1]");
            }
        }
        for (double value : new double[] {rawCenterSourcePx, lightCenterSourcePx,
                standardCenterSourcePx, coreCenterSourcePx, localTubeCenterSourcePx,
                stabilityTubeCenterSourcePx, effectiveTubeCenterSourcePx, trendCenterSourcePx,
                trendSlopeSourcePxPerMeter, trendCurvatureSourcePxPerMeter2, residualSourcePx}) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Available local shape centers and trends must be finite");
            }
        }
        if (!(Double.isNaN(selectedOffsetSourcePx) || Double.isFinite(selectedOffsetSourcePx))) {
            throw new IllegalArgumentException("Selected offset must be finite or unavailable");
        }
    }

    /**
     * Returns evidence with no authorized intervention for an unavailable profile/window.
     *
     * @param profileIndex profile index in the candidate sampling frame
     * @param chainageMeters cumulative ground distance in metres
     * @param sourcePixelPitchRasterPx native source-pixel pitch in sampled-raster pixels
     * @param provenance evidence provenance
     * @param rawCenterSourcePx raw center in source-pixel units
     * @param lightCenterSourcePx B3 center in source-pixel units
     * @param standardCenterSourcePx B5 center in source-pixel units
     * @param coreCenterSourcePx high-intensity core center in source-pixel units
     * @param localCenterSourcePx local tube center in source-pixel units
     * @param stabilityCenterSourcePx stability tube center in source-pixel units
     * @param effectiveCenterSourcePx effective tube center in source-pixel units
     * @param reason reason evidence is unavailable
     * @return immutable unavailable evidence row
     */
    public static LocalShapeEvidence unavailable(
        int profileIndex,
        double chainageMeters,
        double sourcePixelPitchRasterPx,
        CleanupEvidenceProvenance provenance,
        double rawCenterSourcePx,
        double lightCenterSourcePx,
        double standardCenterSourcePx,
        double coreCenterSourcePx,
        double localCenterSourcePx,
        double stabilityCenterSourcePx,
        double effectiveCenterSourcePx,
        Reason reason
    ) {
        return new LocalShapeEvidence(profileIndex, chainageMeters, sourcePixelPitchRasterPx,
            provenance, 0.0, rawCenterSourcePx, lightCenterSourcePx, standardCenterSourcePx,
            coreCenterSourcePx, localCenterSourcePx, stabilityCenterSourcePx, effectiveCenterSourcePx,
            Double.NaN, effectiveCenterSourcePx, 0.0, 0.0, 0.0, 0.0, 0.0, 0, Double.NaN,
            0.0, 0.0, 0.0, 0.0, reason == Reason.SCALE_CONFLICT ? 1.0 : 0.0,
            0.0, 0.0, Decision.UNAVAILABLE, reason);
    }

    /** Profile-level cleanup decision. */
    public enum Decision {
        /** Direct evidence supports intervention against a short residual. */
        WRINKLE,
        /** Coherent multiscale corridor movement is protected. */
        SUPPORTED_BEND,
        /** Competing explanations require cleanup abstention. */
        AMBIGUOUS,
        /** Direct evidence is stable and needs no intervention. */
        STABLE,
        /** Evidence is missing, censored, conflicting, or non-direct. */
        UNAVAILABLE
    }

    /** Typed primary explanation for the decision. */
    public enum Reason {
        /** Short alternating residual is unsupported longitudinally. */
        DIRECT_WRINKLE,
        /** Affine/quadratic and channel evidence supports a bend. */
        SUPPORTED_BEND,
        /** No material residual or bend was found. */
        STABLE,
        /** Target lacks direct evidence. */
        NON_DIRECT,
        /** A physical scale window lacks two-sided support. */
        BOUNDARY_CENSORED,
        /** Fine/coarse evidence conflicts. */
        SCALE_CONFLICT,
        /** Wrinkle and bend explanations are too close. */
        AMBIGUOUS,
        /** No scale had sufficient direct physical support. */
        INSUFFICIENT_WINDOW
    }

    private static boolean ratio(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private static boolean nonNegative(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }
}
