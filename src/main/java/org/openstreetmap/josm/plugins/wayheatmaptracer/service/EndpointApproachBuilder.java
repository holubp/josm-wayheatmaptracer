package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.EndpointApproachModel.EndpointApproach;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.EndpointApproachModel.GuideTarget;

/**
 * Builds physical-distance endpoint guides from the selected branch's longitudinal corridor tube.
 */
public final class EndpointApproachBuilder {
    private static final double MIN_BASELINE_METERS = 8.0;
    private static final double MAX_BASELINE_METERS = 15.0;

    /** Creates a stateless endpoint approach builder. */
    public EndpointApproachBuilder() {
        // Stateless builder.
    }

    /**
     * Builds supported or explicitly unsupported guides for every constrained side.
     *
     * @param track selected corridor identity
     * @param profiles extracted fine corridor profiles
     * @param tube selected-track longitudinal corridor tube
     * @param context endpoint and junction constraints
     * @param scaleEvidence cross-scale evidence keyed by profile and band id
     * @return complete approach model
     */
    public EndpointApproachModel build(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        LongitudinalCorridorTube tube,
        JunctionContext context,
        Map<String, BandScaleEvidence> scaleEvidence
    ) {
        List<EndpointApproach> approaches = new ArrayList<>();
        for (EndpointConstraint constraint : context.constraints()) {
            for (int direction : List.of(-1, 1)) {
                if (constraint.profileIndex() + direction < 0
                    || constraint.profileIndex() + direction >= profiles.size()) {
                    continue;
                }
                approaches.add(buildSide(track, profiles, tube, constraint, direction, scaleEvidence));
            }
        }
        return new EndpointApproachModel(approaches);
    }

    private EndpointApproach buildSide(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        LongitudinalCorridorTube tube,
        EndpointConstraint constraint,
        int direction,
        Map<String, BandScaleEvidence> scaleEvidence
    ) {
        int endpointIndex = constraint.profileIndex();
        int anchorIndex = findReliableAnchor(track, profiles, tube, endpointIndex, direction, scaleEvidence);
        if (anchorIndex < 0) {
            return new EndpointApproach(endpointIndex, direction, -1, false,
                "no-reliable-interior-corridor", List.of());
        }
        double endpointDistance = tube.at(endpointIndex).distanceMeters();
        double anchorDistance = tube.at(anchorIndex).distanceMeters();
        double length = Math.abs(anchorDistance - endpointDistance);
        if (length <= 1e-9) {
            return new EndpointApproach(endpointIndex, direction, -1, false,
                "zero-length-approach", List.of());
        }
        double endpointOffset = constraint.fixed() ? 0.0
            : clamp(tube.at(endpointIndex).centerOffsetPx(), constraint.maxDisplacementPx());
        double anchorOffset = tube.at(anchorIndex).centerOffsetPx();
        double chordSlope = (anchorOffset - endpointOffset) / length;
        double interiorSlope = direction * tube.at(anchorIndex).tangentOffsetPerMeter();
        double endpointSlope = chordSlope * interiorSlope >= 0.0
            ? (chordSlope + interiorSlope) / 2.0
            : chordSlope;
        List<GuideTarget> targets = new ArrayList<>();
        for (int profileIndex = endpointIndex;
            direction > 0 ? profileIndex <= anchorIndex : profileIndex >= anchorIndex;
            profileIndex += direction) {
            double distance = Math.abs(tube.at(profileIndex).distanceMeters() - endpointDistance);
            double fraction = Math.max(0.0, Math.min(1.0, distance / length));
            double expected = hermite(endpointOffset, anchorOffset, endpointSlope * length,
                interiorSlope * length, fraction);
            double weight = 2.5 * square(1.0 - fraction);
            targets.add(new GuideTarget(profileIndex, expected, weight,
                ambiguousProfile(profiles.get(profileIndex))));
        }
        return new EndpointApproach(endpointIndex, direction, anchorIndex, true, "supported", targets);
    }

    private int findReliableAnchor(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        LongitudinalCorridorTube tube,
        int endpointIndex,
        int direction,
        Map<String, BandScaleEvidence> scaleEvidence
    ) {
        int fallback = -1;
        int preferred = -1;
        double endpointDistance = tube.at(endpointIndex).distanceMeters();
        for (int profileIndex = endpointIndex + direction;
            profileIndex >= 0 && profileIndex < profiles.size(); profileIndex += direction) {
            double distance = Math.abs(tube.at(profileIndex).distanceMeters() - endpointDistance);
            if (distance > MAX_BASELINE_METERS + 1e-9) {
                break;
            }
            if (!reliable(track, profiles.get(profileIndex), tube.at(profileIndex), scaleEvidence)) {
                continue;
            }
            fallback = profileIndex;
            if (distance >= MIN_BASELINE_METERS) {
                preferred = profileIndex;
            }
        }
        return preferred >= 0 ? preferred : fallback;
    }

    private boolean reliable(
        CorridorTrack track,
        CorridorProfile profile,
        CorridorTubeSlice slice,
        Map<String, BandScaleEvidence> scaleEvidence
    ) {
        CorridorTrackPoint point = track.points().get(profile.index());
        if (point == null || point.band().parentHypothesis() || ambiguousProfile(profile)) {
            return false;
        }
        BandScaleEvidence evidence = scaleEvidence.get(
            CorridorCenterlineOptimizer.scaleEvidenceKey(profile.index(), point.band().id()));
        if (evidence != null && (evidence.scaleConflict() || evidence.parentMerge())) {
            return false;
        }
        double referenceDisagreement = Math.abs(slice.localCenterOffsetPx() - slice.stabilityCenterOffsetPx());
        double referenceTolerance = 2.0 * Math.max(1.0,
            slice.uncertaintyPx() + slice.stabilityUncertaintyPx());
        return point.band().signalExistenceConfidence() >= 0.15
            && (point.band().localizationConfidence() >= 0.15 || slice.confidence() >= 0.20)
            && referenceDisagreement <= referenceTolerance;
    }

    private boolean ambiguousProfile(CorridorProfile profile) {
        return profile.bands().stream().filter(band -> !band.parentHypothesis()).count() > 1;
    }

    private double hermite(double start, double end, double startDerivative, double endDerivative, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return (2.0 * t3 - 3.0 * t2 + 1.0) * start
            + (t3 - 2.0 * t2 + t) * startDerivative
            + (-2.0 * t3 + 3.0 * t2) * end
            + (t3 - t2) * endDerivative;
    }

    private double clamp(double value, double maximum) {
        return Math.max(-maximum, Math.min(maximum, value));
    }

    private double square(double value) {
        return value * value;
    }
}
