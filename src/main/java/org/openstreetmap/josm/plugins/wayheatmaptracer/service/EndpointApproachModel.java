package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.List;

/**
 * Heatmap-supported boundary guides approaching fixed or movable endpoint constraints.
 *
 * @param approaches one entry for each constrained side that has or lacks reliable support
 */
public record EndpointApproachModel(List<EndpointApproach> approaches) {
    /** Makes approach evidence immutable. */
    public EndpointApproachModel {
        approaches = List.copyOf(approaches);
    }

    /**
     * Returns all guide targets affecting one profile.
     *
     * @param profileIndex sampled profile index
     * @return immutable matching guide targets
     */
    public List<GuideTarget> targetsAt(int profileIndex) {
        return approaches.stream().flatMap(approach -> approach.targets().stream())
            .filter(target -> target.profileIndex() == profileIndex).toList();
    }

    /**
     * Reports whether all modeled sides of one constraint have reliable branch evidence.
     *
     * @param constraintProfileIndex constrained profile index
     * @return true when at least one side exists and every side is supported
     */
    public boolean supportsConstraint(int constraintProfileIndex) {
        List<EndpointApproach> matching = approaches.stream()
            .filter(value -> value.constraintProfileIndex() == constraintProfileIndex).toList();
        return !matching.isEmpty() && matching.stream().allMatch(EndpointApproach::supported);
    }

    /**
     * Boundary evidence for one direction from a constrained node.
     *
     * @param constraintProfileIndex constrained profile index
     * @param direction negative toward lower indexes or positive toward higher indexes
     * @param interiorAnchorProfileIndex reliable interior profile, or -1 when unavailable
     * @param supported whether a reliable approach could be built
     * @param reason machine-readable support or failure reason
     * @param targets expected offsets and weights inside the approach interval
     */
    public record EndpointApproach(
        int constraintProfileIndex,
        int direction,
        int interiorAnchorProfileIndex,
        boolean supported,
        String reason,
        List<GuideTarget> targets
    ) {
        /** Makes guide targets immutable. */
        public EndpointApproach {
            targets = List.copyOf(targets);
        }
    }

    /**
     * Position prior contributed by one supported endpoint approach.
     *
     * @param profileIndex sampled profile index
     * @param expectedOffsetPx expected lateral offset in sampled-raster pixels
     * @param positionWeight dimensionless squared-distance weight
     * @param ambiguousHeatmap whether local crossing/multimodal evidence should be downweighted
     */
    public record GuideTarget(
        int profileIndex,
        double expectedOffsetPx,
        double positionWeight,
        boolean ambiguousHeatmap
    ) {
    }
}
