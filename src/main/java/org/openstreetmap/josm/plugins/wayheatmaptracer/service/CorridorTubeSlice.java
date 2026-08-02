package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

/**
 * Longitudinally stabilized corridor evidence for one sampled profile.
 *
 * @param profileIndex sampled profile index
 * @param distanceMeters cumulative distance from the first profile
 * @param centerOffsetPx robust lateral center in sampled-raster pixels
 * @param tangentOffsetPerMeter local lateral slope along the way
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
