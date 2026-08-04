package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.List;
import java.util.Map;

/**
 * Immutable longitudinal hypothesis combining complementary elementary heatmap tracks.
 *
 * @param id deterministic bundle identifier
 * @param childTrackIds elementary child track ids
 * @param classification {@code combined} or {@code ambiguous}
 * @param points profile-aligned bundle center evidence
 * @param unionSupportRatio direct child-union support divided by profile count
 * @param jointSupportRatio profiles with at least two direct child observations divided by profile count
 * @param valleyPersistence fraction of joint profiles with a deep persistent valley
 * @param tangentAgreement normalized tangent agreement in the range zero to one
 * @param orderStability fraction of joint observations retaining lateral order
 * @param robustSeparationPx robust child separation in sampled-raster pixels
 * @param reason machine-readable grouping reason
 */
public record SparseCorridorBundle(
    String id,
    List<String> childTrackIds,
    String classification,
    Map<Integer, SparseCorridorBundlePoint> points,
    double unionSupportRatio,
    double jointSupportRatio,
    double valleyPersistence,
    double tangentAgreement,
    double orderStability,
    double robustSeparationPx,
    String reason
) {
    /** Makes collections immutable and validates the bundle evidence contract. */
    public SparseCorridorBundle {
        childTrackIds = List.copyOf(childTrackIds);
        points = Map.copyOf(points);
        if (id == null || id.isBlank() || childTrackIds.size() < 2 || classification == null
            || !(classification.equals("combined") || classification.equals("ambiguous"))
            || points.isEmpty() || !finiteRatio(unionSupportRatio) || !finiteRatio(jointSupportRatio)
            || !finiteRatio(valleyPersistence) || !finiteRatio(tangentAgreement)
            || !finiteRatio(orderStability) || !Double.isFinite(robustSeparationPx)
            || robustSeparationPx < 0.0 || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Sparse corridor bundle values are incomplete or invalid");
        }
        int previous = -1;
        List<String> knownChildren = childTrackIds;
        for (Map.Entry<Integer, SparseCorridorBundlePoint> entry : points.entrySet().stream()
            .sorted(Map.Entry.comparingByKey()).toList()) {
            if (entry.getKey() != entry.getValue().profileIndex() || entry.getKey() <= previous) {
                throw new IllegalArgumentException("Sparse bundle points must have unique monotonic profile indexes");
            }
            if (entry.getValue().directContributorTrackIds().stream()
                .anyMatch(contributor -> !knownChildren.contains(contributor))
                || entry.getValue().predictedContributorTrackIds().stream()
                    .anyMatch(contributor -> !knownChildren.contains(contributor))) {
                throw new IllegalArgumentException("Sparse bundle point contributors must be bundle children");
            }
            previous = entry.getKey();
        }
    }

    private static boolean finiteRatio(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    /**
     * Returns the number of profiles with direct child-union evidence.
     *
     * @return directly supported profile count
     */
    public int directUnionProfileCount() {
        return (int) points.values().stream()
            .filter(point -> point.support() == CorridorPointSupport.DIRECT_UNION).count();
    }

    /**
     * Returns the number of profiles supported only by bounded interpolation.
     *
     * @return bounded-interpolation profile count
     */
    public int interpolatedProfileCount() {
        return points.size() - directUnionProfileCount();
    }
}
