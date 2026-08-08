package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/**
 * Candidate-specific selected-corridor evidence aligned to one shared sampling-frame profile.
 *
 * @param profileIndex zero-based profile index
 * @param selectedCoreMinPx selected high-intensity core minimum
 * @param selectedCoreMaxPx selected high-intensity core maximum
 * @param selectedShoulderMinPx selected corridor shoulder minimum
 * @param selectedShoulderMaxPx selected corridor shoulder maximum
 * @param tubeCenterOffsetPx robust longitudinal tube center
 * @param tubeUncertaintyPx robust tube uncertainty
 * @param provenance direct, bounded-interpolation, or unsupported evidence
 * @param motionSupport sustained lateral-motion evidence in the range zero to one
 * @param turnSupport sustained turn/apex evidence in the range zero to one
 * @param scaleConflict whether scale-space evidence conflicts at this profile
 */
public record CandidateCleanupProfile(
    int profileIndex,
    double selectedCoreMinPx,
    double selectedCoreMaxPx,
    double selectedShoulderMinPx,
    double selectedShoulderMaxPx,
    double tubeCenterOffsetPx,
    double tubeUncertaintyPx,
    CleanupEvidenceProvenance provenance,
    double motionSupport,
    double turnSupport,
    boolean scaleConflict
) {
    /** Validates profile identity, support ranges, and supported-row geometry. */
    public CandidateCleanupProfile {
        if (profileIndex < 0 || provenance == null || !ratio(motionSupport) || !ratio(turnSupport)) {
            throw new IllegalArgumentException("Candidate cleanup profile metadata is invalid");
        }
        if (provenance != CleanupEvidenceProvenance.UNSUPPORTED
            && (!ordered(selectedCoreMinPx, selectedCoreMaxPx)
                || !ordered(selectedShoulderMinPx, selectedShoulderMaxPx)
                || selectedShoulderMinPx > selectedCoreMinPx || selectedCoreMaxPx > selectedShoulderMaxPx
                || !Double.isFinite(tubeCenterOffsetPx) || !Double.isFinite(tubeUncertaintyPx)
                || tubeUncertaintyPx <= 0.0)) {
            throw new IllegalArgumentException("Supported cleanup profile geometry must be finite and ordered");
        }
        if (provenance == CleanupEvidenceProvenance.UNSUPPORTED
            && (Double.isFinite(selectedCoreMinPx) || Double.isFinite(selectedCoreMaxPx)
                || Double.isFinite(selectedShoulderMinPx) || Double.isFinite(selectedShoulderMaxPx)
                || motionSupport != 0.0 || turnSupport != 0.0)) {
            throw new IllegalArgumentException(
                "Unsupported cleanup rows cannot contain corridor bounds or authorize motion");
        }
    }

    private static boolean ordered(double minimum, double maximum) {
        return Double.isFinite(minimum) && Double.isFinite(maximum) && minimum <= maximum;
    }

    private static boolean ratio(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }
}
