package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;

/** Exact pre-P6 energy bodies from base6365077; never call optimized production helpers. */
final class FrozenPairLocalEnergyReference {
    private FrozenPairLocalEnergyReference() { }

    static double increment(List<InferenceProfile> profiles, int profile, int before, int prior,
            int state, EvidenceModelParameters parameters) {
        return profileEnergy(profiles, profile, state, parameters)
                + pairEnergy(profiles, profile - 1, prior, state, parameters)
                + tripleEnergy(profiles, profile, before, prior, state, parameters);
    }

    static double profileEnergy(List<InferenceProfile> profiles, int profileIndex, int state,
        EvidenceModelParameters parameters) {
        InferenceProfile profile = profiles.get(profileIndex);
        return profileQuadrature(profiles, profileIndex) * (parameters.dataWeight()
            * profile.unaryCost(state) + parameters.guideWeight() * profile.structuralGuideCost(state));
    }

    static double pairEnergy(List<InferenceProfile> profiles, int startProfile, int startState,
        int endState, EvidenceModelParameters parameters) {
        if (parameters.orientationWeight() == 0.0) {
            return 0.0;
        }
        InferenceProfile start = profiles.get(startProfile);
        InferenceProfile end = profiles.get(startProfile + 1);
        MetricPoint first = start.point(startState);
        MetricPoint second = end.point(endState);
        double heading = StrictMath.atan2(second.yMeters() - first.yMeters(), second.xMeters() - first.xMeters());
        double span = end.chainageMeters() - start.chainageMeters();
        // Integrate direct branch-owned evidence at both ends of the physical segment.
        // A weak arrival must not discard a reliable departure, and reversing the trace
        // must preserve this objective. Missing endpoint evidence contributes exactly zero.
        return parameters.orientationWeight() * span * 0.5
            * (orientationMismatch(start, startState, heading)
                + orientationMismatch(end, endState, heading));
    }

    static double orientationMismatch(InferenceProfile profile, int state, double heading) {
        var support = profile.orientationSupport(state);
        if (support.modes().isEmpty() || support.certainty() == 0.0) return 0.0;
        return support.certainty() * profile.orientationReliability(state)
                * support.mismatchSquared(heading);
    }

    static double tripleEnergy(List<InferenceProfile> profiles, int profileIndex,
        int beforeState, int priorState, int state, EvidenceModelParameters parameters) {
        if (parameters.turnWeight() == 0.0) {
            return 0.0;
        }
        MetricPoint before = profiles.get(profileIndex - 2).point(beforeState);
        MetricPoint prior = profiles.get(profileIndex - 1).point(priorState);
        MetricPoint current = profiles.get(profileIndex).point(state);
        double firstHeading = StrictMath.atan2(prior.yMeters() - before.yMeters(), prior.xMeters() - before.xMeters());
        double secondHeading = StrictMath.atan2(current.yMeters() - prior.yMeters(), current.xMeters() - prior.xMeters());
        double normalizedTurn = wrap(secondHeading - firstHeading) / parameters.turnScaleRadians();
        double middleSpan = Math.max(1e-6, 0.5 * (profiles.get(profileIndex).chainageMeters()
            - profiles.get(profileIndex - 2).chainageMeters()));
        return parameters.turnWeight() * EvidenceModelParameters.huber(normalizedTurn) / middleSpan;
    }

    static double profileQuadrature(List<InferenceProfile> profiles, int index) {
        if (profiles.size() == 1) {
            return 1.0;
        }
        if (index == 0) {
            return 0.5 * (profiles.get(1).chainageMeters() - profiles.get(0).chainageMeters());
        }
        if (index == profiles.size() - 1) {
            return 0.5 * (profiles.get(index).chainageMeters() - profiles.get(index - 1).chainageMeters());
        }
        return 0.5 * (profiles.get(index + 1).chainageMeters()
            - profiles.get(index - 1).chainageMeters());
    }

    private static double wrap(double angle) {
        return StrictMath.atan2(StrictMath.sin(angle), StrictMath.cos(angle));
    }
}
