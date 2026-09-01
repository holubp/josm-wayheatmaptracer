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
        return calculate(track, profiles, endpointApproaches, null);
    }

    /**
     * Measures coverage using direct child-union support for a sparse parent.
     *
     * @param track selected elementary or sparse-parent track
     * @param profiles extracted profile evidence
     * @param endpointApproaches endpoint support used by the optimizer
     * @param bundle sparse bundle metadata, or {@code null} for an elementary track
     * @return immutable coverage summary that never counts interpolation as direct support
     */
    public CorridorCoverage calculate(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        EndpointApproachModel endpointApproaches,
        SparseCorridorBundle bundle
    ) {
        if (profiles.isEmpty() || track.points().isEmpty()) {
            return new CorridorCoverage(true, false, 0, informativeCount(profiles), 0.0,
                -1, -1, 0.0, 0.0, 0, 0.0, 0, !profiles.isEmpty(), "no-observed-corridor");
        }
        List<Integer> observed = bundle == null ? track.points().keySet().stream().sorted().toList()
            : bundle.points().values().stream()
                .filter(point -> point.support() == CorridorPointSupport.DIRECT_UNION)
                .map(SparseCorridorBundlePoint::profileIndex).sorted().toList();
        if (observed.isEmpty()) {
            return new CorridorCoverage(true, false, 0, informativeCount(profiles), 0.0,
                -1, -1, 0.0, 0.0, 0, 0.0, 0, !profiles.isEmpty(), "no-direct-union-corridor");
        }
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
        boolean bridgedSearchEdgeCensoring = false;
        boolean unresolvedSearchEdgeCensoring = false;
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
            boolean trackerApproved = (bundle == null ? track.points().get(right).bridged()
                : boundedBundleGap(bundle, left, right))
                && missing <= MAX_BRIDGE_PROFILES && metres <= MAX_BRIDGE_METERS + 1e-9;
            boolean searchEdgeCensored = containsRelevantUnmeasuredBoundaryEvidence(
                track, profiles, left, right, left + 1, right - 1);
            gaps.add(new Gap(left, right, missing, metres, trackerApproved));
            maximumGapProfiles = Math.max(maximumGapProfiles, missing);
            maximumGapMeters = Math.max(maximumGapMeters, metres);
            if (trackerApproved) {
                approvedBridges++;
                bridgedSearchEdgeCensoring |= searchEdgeCensored;
            } else {
                internalComplete = false;
                unresolvedSearchEdgeCensoring |= searchEdgeCensored;
            }
        }

        boolean evidenceBeyond = false;
        for (int index = 0; index < profiles.size(); index++) {
            if (!informative(profiles.get(index)) || observed.contains(index)) {
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
        unresolvedSearchEdgeCensoring |= !leadingSupported
            && containsRelevantUnmeasuredBoundaryEvidence(track, profiles, first, first, 0, first - 1);
        unresolvedSearchEdgeCensoring |= !trailingSupported
            && containsRelevantUnmeasuredBoundaryEvidence(
                track, profiles, last, last, last + 1, profiles.size() - 1);
        boolean complete = internalComplete && leadingSupported && trailingSupported;
        String reason;
        if (complete && bridgedSearchEdgeCensoring) {
            reason = "complete-with-search-edge-bridge";
        } else if (!complete && unresolvedSearchEdgeCensoring) {
            reason = "unresolved-search-edge-censoring";
        } else {
            reason = complete ? "complete" : !internalComplete ? "unapproved-internal-gap"
                : !leadingSupported ? "unsupported-leading-corridor" : "unsupported-trailing-corridor";
        }
        return new CorridorCoverage(true, complete, observed.size(), informative, informativeRatio,
            first, last, leading, trailing, maximumGapProfiles, maximumGapMeters, approvedBridges,
            evidenceBeyond, reason);
    }

    private boolean boundedBundleGap(SparseCorridorBundle bundle, int left, int right) {
        for (int index = left + 1; index < right; index++) {
            SparseCorridorBundlePoint point = bundle.points().get(index);
            if (point == null || point.support() != CorridorPointSupport.BOUNDED_INTERPOLATION) {
                return false;
            }
        }
        return true;
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
    private boolean containsRelevantUnmeasuredBoundaryEvidence(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        int leftObservedIndex,
        int rightObservedIndex,
        int firstIndex,
        int lastIndex
    ) {
        if (firstIndex > lastIndex) {
            return false;
        }
        CorridorTrackPoint leftPoint = track.points().get(leftObservedIndex);
        CorridorTrackPoint rightPoint = track.points().get(rightObservedIndex);
        if (leftPoint == null || rightPoint == null) {
            return false;
        }
        double leftDistance = profiles.get(leftObservedIndex).source().cumulativeGroundDistanceMeters();
        double rightDistance = profiles.get(rightObservedIndex).source().cumulativeGroundDistanceMeters();
        double distanceSpan = rightDistance - leftDistance;
        double envelope = 1.5 * Math.max(1e-6,
            Math.max(leftPoint.band().uncertaintyPx(), rightPoint.band().uncertaintyPx()));
        int boundedFirst = Math.max(0, firstIndex);
        int boundedLast = Math.min(lastIndex, profiles.size() - 1);
        for (int index = boundedFirst; index <= boundedLast; index++) {
            double profileDistance = profiles.get(index).source().cumulativeGroundDistanceMeters();
            double fraction = Math.abs(distanceSpan) <= 1e-9 ? 0.0
                : (profileDistance - leftDistance) / distanceSpan;
            double expected = leftPoint.band().centerOffsetPx()
                + fraction * (rightPoint.band().centerOffsetPx() - leftPoint.band().centerOffsetPx());
            boolean relevant = profiles.get(index).bands().stream()
                .filter(band -> !band.parentHypothesis() && !band.hasMeasuredCenter())
                .anyMatch(band -> distanceToInterval(expected, band.shoulderMinPx(),
                    band.shoulderMaxPx()) <= envelope + 1e-9);
            if (relevant) {
                return true;
            }
        }
        return false;
    }

    private double distanceToInterval(double value, double minimum, double maximum) {
        if (value < minimum) {
            return minimum - value;
        }
        if (value > maximum) {
            return value - maximum;
        }
        return 0.0;
    }


    private boolean informative(CorridorProfile profile) {
        return profile.source().anchorWithinRaster()
            && profile.bands().stream().anyMatch(band -> !band.parentHypothesis() && band.hasMeasuredCenter());
    }

    private double distance(List<CorridorProfile> profiles, int left, int right) {
        return Math.abs(profiles.get(right).source().cumulativeGroundDistanceMeters()
            - profiles.get(left).source().cumulativeGroundDistanceMeters());
    }

    private record Gap(int left, int right, int missing, double metres, boolean approved) {
    }
}
