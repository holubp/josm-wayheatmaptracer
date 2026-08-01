package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.List;

/**
 * Cross-scale support for one fine L0 corridor band.
 *
 * @param scalePersistence fixed-weight persistence over compatible L0/L1/L2 evidence
 * @param coarseCenterPx compatible coarse center in common L0 sampled-raster pixels, or NaN
 * @param coarseUncertaintyPx uncertainty of the compatible coarse center in L0 pixels
 * @param participatingLevels compatible level indexes
 * @param scaleConflict whether coarse evidence selected a different nearby corridor
 * @param parentMerge whether the fine child merged into a multi-child coarse parent
 */
public record BandScaleEvidence(
    double scalePersistence,
    double coarseCenterPx,
    double coarseUncertaintyPx,
    List<Integer> participatingLevels,
    boolean scaleConflict,
    boolean parentMerge
) {
    /** Makes level metadata immutable. */
    public BandScaleEvidence {
        participatingLevels = List.copyOf(participatingLevels);
    }

    /**
     * Returns evidence containing only the mandatory L0 observation.
     *
     * @return L0-only scale evidence
     */
    public static BandScaleEvidence levelZeroOnly() {
        return new BandScaleEvidence(0.50, Double.NaN, Double.NaN, List.of(0), false, false);
    }

    /**
     * Returns whether a coarse midpoint is safe to use as a localization prior.
     *
     * @return true for finite, unique, conflict-free coarse evidence
     */
    public boolean hasCoarseCenterPrior() {
        return Double.isFinite(coarseCenterPx) && Double.isFinite(coarseUncertaintyPx)
            && coarseUncertaintyPx > 0.0 && !scaleConflict && !parentMerge;
    }
}
