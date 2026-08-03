package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

/**
 * Longitudinally stabilized corridor evidence for one sampled profile.
 *
 * @param profileIndex sampled profile index
 * @param distanceMeters cumulative distance from the first profile
 * @param centerOffsetPx robust lateral center in sampled-raster pixels
 * @param tangentOffsetPerMeter local lateral slope along the way
 * @param localCenterOffsetPx center from the approximately five-metre support window
 * @param localTangentOffsetPerMeter tangent from the approximately five-metre support window
 * @param stabilityCenterOffsetPx center from the approximately twelve-metre support window
 * @param stabilityTangentOffsetPerMeter tangent from the approximately twelve-metre support window
 * @param stabilityUncertaintyPx robust residual scale of the stability fit
 * @param motionSupport evidence in the range zero to one that local lateral motion is sustained
 * @param motionSupportReason machine-readable explanation of the support decision
 * @param curvatureOffsetPerMeterSquared local lateral curvature, or zero when unsupported
 * @param coreMinPx selected fine core minimum, or NaN without local evidence
 * @param coreMaxPx selected fine core maximum, or NaN without local evidence
 * @param shoulderMinPx selected fine shoulder minimum, or NaN without local evidence
 * @param shoulderMaxPx selected fine shoulder maximum, or NaN without local evidence
 * @param uncertaintyPx conservative lateral center uncertainty
 * @param confidence longitudinal reference confidence in the range zero to one
 * @param scaleConflict whether scale-space observations conflict at this profile
 * @param parentMerge whether coarse evidence merges more than one fine child
 * @param rawCenterPx nearest raw-filter center, or the fine-band center when unavailable
 * @param lightCenterPx nearest B3 center, or the fine-band center when unavailable
 * @param standardCenterPx nearest B5 center, or the fine-band center when unavailable
 * @param observed whether the selected elementary track has direct evidence at this profile
 */
public record CorridorTubeSlice(
    int profileIndex,
    double distanceMeters,
    double centerOffsetPx,
    double tangentOffsetPerMeter,
    double localCenterOffsetPx,
    double localTangentOffsetPerMeter,
    double stabilityCenterOffsetPx,
    double stabilityTangentOffsetPerMeter,
    double stabilityUncertaintyPx,
    double motionSupport,
    String motionSupportReason,
    double curvatureOffsetPerMeterSquared,
    double coreMinPx,
    double coreMaxPx,
    double shoulderMinPx,
    double shoulderMaxPx,
    double uncertaintyPx,
    double confidence,
    boolean scaleConflict,
    boolean parentMerge,
    double rawCenterPx,
    double lightCenterPx,
    double standardCenterPx,
    boolean observed
) {
    /**
     * Creates a slice whose effective, local, and stability references are identical.
     *
     * <p>This compatibility constructor is intended for focused metric tests and callers that already
     * provide a pre-stabilized reference.</p>
     *
     * @param profileIndex sampled profile index
     * @param distanceMeters cumulative distance from the first profile
     * @param centerOffsetPx pre-stabilized center offset
     * @param tangentOffsetPerMeter pre-stabilized tangent
     * @param curvatureOffsetPerMeterSquared pre-stabilized curvature
     * @param coreMinPx core minimum
     * @param coreMaxPx core maximum
     * @param shoulderMinPx shoulder minimum
     * @param shoulderMaxPx shoulder maximum
     * @param uncertaintyPx center uncertainty
     * @param confidence reference confidence
     * @param scaleConflict whether scale evidence conflicts
     * @param parentMerge whether coarse evidence merges children
     * @param rawCenterPx raw center
     * @param lightCenterPx B3 center
     * @param standardCenterPx B5 center
     * @param observed whether direct evidence exists
     */
    public CorridorTubeSlice(
        int profileIndex,
        double distanceMeters,
        double centerOffsetPx,
        double tangentOffsetPerMeter,
        double curvatureOffsetPerMeterSquared,
        double coreMinPx,
        double coreMaxPx,
        double shoulderMinPx,
        double shoulderMaxPx,
        double uncertaintyPx,
        double confidence,
        boolean scaleConflict,
        boolean parentMerge,
        double rawCenterPx,
        double lightCenterPx,
        double standardCenterPx,
        boolean observed
    ) {
        this(profileIndex, distanceMeters, centerOffsetPx, tangentOffsetPerMeter,
            centerOffsetPx, tangentOffsetPerMeter, centerOffsetPx, tangentOffsetPerMeter,
            uncertaintyPx, 1.0, "pre-stabilized", curvatureOffsetPerMeterSquared, coreMinPx, coreMaxPx,
            shoulderMinPx, shoulderMaxPx, uncertaintyPx, confidence, scaleConflict, parentMerge,
            rawCenterPx, lightCenterPx, standardCenterPx, observed);
    }

    /**
     * Returns whether this slice contains valid selected-track interval evidence.
     *
     * @return true when core and shoulder bounds are finite and ordered
     */
    public boolean hasIntervals() {
        return Double.isFinite(coreMinPx) && Double.isFinite(coreMaxPx)
            && Double.isFinite(shoulderMinPx) && Double.isFinite(shoulderMaxPx)
            && coreMinPx <= coreMaxPx && shoulderMinPx <= shoulderMaxPx;
    }
}
