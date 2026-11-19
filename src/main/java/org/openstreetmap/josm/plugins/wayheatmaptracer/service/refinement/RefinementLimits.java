package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

/** Retained-history bounds, distinct from engine work and managed tile-cache limits. */
public record RefinementLimits(int maxStages, int maxCandidatesPerStage, long maxPayloadBytes) {
    public RefinementLimits {
        if (maxStages < 1 || maxStages > 8 || maxCandidatesPerStage < 1 || maxCandidatesPerStage > 32
                || maxPayloadBytes < 1 || maxPayloadBytes > 64L * 1024 * 1024)
            throw new IllegalArgumentException("History limits may tighten but never exceed the approved caps");
    }
    public static RefinementLimits defaults() { return new RefinementLimits(8, 32, 64L * 1024 * 1024); }
}
