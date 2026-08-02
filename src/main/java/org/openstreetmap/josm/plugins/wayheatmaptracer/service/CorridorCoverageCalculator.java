package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorCoverage;

/** Calculates whether a selected corridor strand is supported along the complete selected segment. */
public final class CorridorCoverageCalculator {
    private static final int MAX_BRIDGE_PROFILES = 16;
    private static final double MAX_BRIDGE_METERS = 20.0;

    /** Creates a stateless corridor coverage calculator. */
    public CorridorCoverageCalculator() {
    }

    /**
     * Measures direct observations, approved internal bridges, and endpoint approaches.
     *
     * @param track selected longitudinal strand
     * @param profiles extracted profile evidence
     * @param endpointApproaches endpoint support used by the optimizer
     * @return immutable coverage summary
     */
    public CorridorCoverage calculate(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        EndpointApproachModel endpointApproaches
    ) {
        if (profiles.isEmpty() || track.points().isEmpty()) {
            return new CorridorCoverage(true, false, 0, informativeCount(profiles), 0.0,
                -1, -1, 0.0, 0.0, 0, 0.0, 0, !profiles.isEmpty(), "no-observed-corridor");
        }
        List<Integer> observed = track.points().keySet().stream().sorted().toList();
        int first = observed.get(0);
        int last = observed.get(observed.size() - 1);
        int informative = informativeCount(profiles);
        int observedInformative = (int) observed.stream().filter(index -> informative(profiles.get(index))).count();
        double informativeRatio = informative == 0 ? 0.0 : observedInformative / (double) informative;
        double leading = distance(profiles, 0, first);
        double trailing = distance(profiles, last, profiles.size() - 1);
        boolean leadingSupported = first == 0 || supportedBoundary(endpointApproaches, 0, first, 1);
        boolean trailingSupported = last == profiles.size() - 1
            || supportedBoundary(endpointApproaches, profiles.size() - 1, last, -1);

        int maximumGapProfiles = 0;
        double maximumGapMeters = 0.0;
        int approvedBridges = 0;
        boolean internalComplete = true;
        List<Gap> gaps = new ArrayList<>();
        for (int index = 1; index < observed.size(); index++) {
            int left = observed.get(index - 1);
            int right = observed.get(index);
            int missing = Math.max(0, right - left - 1);
            if (missing == 0) {
                continue;
            }
            double metres = distance(profiles, left, right);
            boolean trackerApproved = track.points().get(right).bridged()
                && missing <= MAX_BRIDGE_PROFILES && metres <= MAX_BRIDGE_METERS + 1e-9;
            gaps.add(new Gap(left, right, missing, metres, trackerApproved));
            maximumGapProfiles = Math.max(maximumGapProfiles, missing);
            maximumGapMeters = Math.max(maximumGapMeters, metres);
            if (trackerApproved) {
                approvedBridges++;
            } else {
                internalComplete = false;
            }
        }

        boolean evidenceBeyond = false;
        for (int index = 0; index < profiles.size(); index++) {
            if (!informative(profiles.get(index)) || track.points().containsKey(index)) {
                continue;
            }
            int profileIndex = index;
            boolean approvedGap = gaps.stream().anyMatch(gap -> gap.approved()
                && profileIndex > gap.left() && profileIndex < gap.right());
            boolean supportedEndpoint = (index < first && leadingSupported) || (index > last && trailingSupported);
            if (!approvedGap && !supportedEndpoint) {
                evidenceBeyond = true;
                break;
            }
        }
        boolean complete = internalComplete && leadingSupported && trailingSupported;
        String reason = complete ? "complete" : !internalComplete ? "unapproved-internal-gap"
            : !leadingSupported ? "unsupported-leading-corridor" : "unsupported-trailing-corridor";
        return new CorridorCoverage(true, complete, observed.size(), informative, informativeRatio,
            first, last, leading, trailing, maximumGapProfiles, maximumGapMeters, approvedBridges,
            evidenceBeyond, reason);
    }

    private boolean supportedBoundary(
        EndpointApproachModel model,
        int constraintProfile,
        int observedBoundary,
        int direction
    ) {
        return model.approaches().stream().anyMatch(approach -> approach.supported()
            && approach.constraintProfileIndex() == constraintProfile
            && approach.direction() == direction
            && (direction > 0
                ? approach.interiorAnchorProfileIndex() >= observedBoundary
                : approach.interiorAnchorProfileIndex() <= observedBoundary));
    }

    private int informativeCount(List<CorridorProfile> profiles) {
        return (int) profiles.stream().filter(this::informative).count();
    }

    private boolean informative(CorridorProfile profile) {
        return profile.source().anchorWithinRaster()
            && profile.bands().stream().anyMatch(band -> !band.parentHypothesis());
    }

    private double distance(List<CorridorProfile> profiles, int left, int right) {
        return Math.abs(profiles.get(right).source().cumulativeGroundDistanceMeters()
            - profiles.get(left).source().cumulativeGroundDistanceMeters());
    }

    private record Gap(int left, int right, int missing, double metres, boolean approved) {
    }
}
