package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.List;

/**
 * Profile-aligned robust center evidence for a sparse corridor bundle.
 *
 * @param profileIndex longitudinal profile index
 * @param support direct-union or bounded-interpolation provenance
 * @param directContributorTrackIds child tracks observed at this profile
 * @param predictedContributorTrackIds child tracks predicted from bracketing physical evidence
 * @param centerOffsetPx robust lateral center in sampled-raster pixels
 * @param uncertaintyPx lateral uncertainty in sampled-raster pixels
 * @param shoulderMinPx union shoulder minimum in sampled-raster pixels
 * @param shoulderMaxPx union shoulder maximum in sampled-raster pixels
 * @param coreMinPx union core minimum in sampled-raster pixels
 * @param coreMaxPx union core maximum in sampled-raster pixels
 * @param occupancy fraction of bundle children contributing directly or by bounded prediction
 * @param contributorAgreement normalized agreement in the range zero to one
 */
public record SparseCorridorBundlePoint(
    int profileIndex,
    CorridorPointSupport support,
    List<String> directContributorTrackIds,
    List<String> predictedContributorTrackIds,
    double centerOffsetPx,
    double uncertaintyPx,
    double shoulderMinPx,
    double shoulderMaxPx,
    double coreMinPx,
    double coreMaxPx,
    double occupancy,
    double contributorAgreement
) {
    /** Makes contributor lists immutable and validates interval and finite-value contracts. */
    public SparseCorridorBundlePoint {
        directContributorTrackIds = List.copyOf(directContributorTrackIds);
        predictedContributorTrackIds = List.copyOf(predictedContributorTrackIds);
        if (profileIndex < 0 || support == null
            || (directContributorTrackIds.isEmpty() && support == CorridorPointSupport.DIRECT_UNION)
            || (!directContributorTrackIds.isEmpty() && support == CorridorPointSupport.BOUNDED_INTERPOLATION)
            || directContributorTrackIds.stream().anyMatch(predictedContributorTrackIds::contains)
            || !Double.isFinite(centerOffsetPx) || !Double.isFinite(uncertaintyPx) || uncertaintyPx <= 0.0
            || !Double.isFinite(shoulderMinPx) || !Double.isFinite(shoulderMaxPx)
            || !Double.isFinite(coreMinPx) || !Double.isFinite(coreMaxPx)
            || shoulderMinPx > coreMinPx || coreMinPx > coreMaxPx || coreMaxPx > shoulderMaxPx
            || !Double.isFinite(occupancy) || occupancy < 0.0 || occupancy > 1.0
            || !Double.isFinite(contributorAgreement) || contributorAgreement < 0.0
            || contributorAgreement > 1.0) {
            throw new IllegalArgumentException("Sparse bundle point geometry and confidence must be finite and ordered");
        }
    }
}
