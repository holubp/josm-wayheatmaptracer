package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.ArrayList;
import java.util.List;

/** Deterministically removes insignificant grid variants without averaging route geometry. */
public final class PathAlternativeSelector {
    /**
     * Retains at most the requested number of geometrically distinct exact paths.
     *
     * @param rawPaths exact energy-ordered k-best paths
     * @param profiles physical profile geometry
     * @param evidencePitchMeters factual source or rendered evidence pitch
     * @param maximumDistinct hard distinct-route cap
     * @return stable subset in original energy order
     */
    public List<ProbabilisticPath> select(List<ProbabilisticPath> rawPaths,
        List<InferenceProfile> profiles, double evidencePitchMeters, int maximumDistinct) {
        if (rawPaths == null || profiles == null || !Double.isFinite(evidencePitchMeters)
            || evidencePitchMeters <= 0.0 || maximumDistinct <= 0) {
            throw new IllegalArgumentException("Alternative selection inputs are invalid");
        }
        List<ProbabilisticPath> selected = new ArrayList<>();
        for (ProbabilisticPath candidate : rawPaths) {
            boolean duplicate = selected.stream().anyMatch(existing -> !different(existing, candidate,
                profiles, evidencePitchMeters));
            if (!duplicate) {
                selected.add(candidate);
                if (selected.size() == maximumDistinct) {
                    break;
                }
            }
        }
        return List.copyOf(selected);
    }

    private static boolean different(ProbabilisticPath first, ProbabilisticPath second,
        List<InferenceProfile> profiles, double pitch) {
        if (!first.branchSignature().equals(second.branchSignature())) {
            return true;
        }
        double separationThreshold = Math.max(pitch, 1.0);
        double spanThreshold = Math.max(10.0, 5.0 * pitch);
        double runStart = Double.NaN;
        double maximumSpan = 0.0;
        for (int index = 0; index < profiles.size(); index++) {
            double separation = first.points().get(index).distanceTo(second.points().get(index));
            if (separation > separationThreshold) {
                if (!Double.isFinite(runStart)) {
                    runStart = profiles.get(index).chainageMeters();
                }
                maximumSpan = Math.max(maximumSpan, profiles.get(index).chainageMeters() - runStart);
            } else {
                runStart = Double.NaN;
            }
        }
        return maximumSpan >= spanThreshold;
    }
}
