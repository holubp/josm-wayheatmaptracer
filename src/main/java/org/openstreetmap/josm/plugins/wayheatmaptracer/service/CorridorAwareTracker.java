package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;

/**
 * Orchestrates extraction, longitudinal association, grouping, and stable corridor optimization.
 */
public final class CorridorAwareTracker {
    private final CorridorExtractor extractor = new CorridorExtractor();
    private final CorridorTracker tracker = new CorridorTracker();
    private final CorridorGrouping grouping = new CorridorGrouping();
    private final CorridorCenterlineOptimizer optimizer = new CorridorCenterlineOptimizer();

    /**
     * Creates a tracker with the standard extraction, association, grouping, and optimization stages.
     */
    public CorridorAwareTracker() {
        // Stage services are initialized in field declarations.
    }

    /**
     * Converts complete scalar profiles into corridor-aware centerline candidates.
     *
     * @param profiles sampled heatmap profiles
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @return candidates sorted by corridor evidence and optimizer cost
     */
    public List<CenterlineCandidate> track(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        double sourcePixelSizePx
    ) {
        return trackDetailed(profiles, sourcePixelSizePx, JunctionContext.empty()).candidates();
    }

    /**
     * Converts complete scalar profiles into constrained corridor-aware candidates.
     *
     * @param profiles sampled heatmap profiles
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @param junctionContext endpoint and junction boundary conditions
     * @return candidates sorted by corridor evidence and optimizer cost
     */
    public List<CenterlineCandidate> track(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        double sourcePixelSizePx,
        JunctionContext junctionContext
    ) {
        return trackDetailed(profiles, sourcePixelSizePx, junctionContext).candidates();
    }

    /**
     * Runs corridor tracking and retains intermediate decisions for diagnostics.
     *
     * @param profiles sampled heatmap profiles
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @return candidates and intermediate corridor evidence
     */
    public TrackingResult trackDetailed(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        double sourcePixelSizePx
    ) {
        return trackDetailed(profiles, sourcePixelSizePx, JunctionContext.empty());
    }

    /**
     * Runs constrained corridor tracking and retains intermediate decisions for diagnostics.
     *
     * @param profiles sampled heatmap profiles
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @param junctionContext endpoint and junction boundary conditions
     * @return candidates and intermediate corridor evidence
     */
    public TrackingResult trackDetailed(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        double sourcePixelSizePx,
        JunctionContext junctionContext
    ) {
        List<CorridorProfile> corridorProfiles = extractor.extract(profiles);
        List<CorridorTrack> elementary = tracker.track(corridorProfiles, sourcePixelSizePx);
        CorridorGrouping.GroupingResult grouped = grouping.group(elementary, corridorProfiles);
        List<CenterlineCandidate> candidates = new ArrayList<>();
        Map<String, CorridorCenterlineOptimizer.OptimizationResult> optimizations = new LinkedHashMap<>();
        for (CorridorTrack track : grouped.tracks()) {
            CorridorCenterlineOptimizer.OptimizationResult optimized = optimizer.optimize(
                track, corridorProfiles, sourcePixelSizePx, junctionContext);
            if (optimized.offsetsPx().isEmpty()) {
                continue;
            }
            CandidateEvidence evidence = evidence(track, corridorProfiles, optimized);
            double normalizedCost = optimized.totalCost() / Math.max(1, profiles.size());
            double score = 2.0 * evidence.signalExistenceConfidence()
                + evidence.localizationConfidence()
                + evidence.supportRatio()
                + optimized.longitudinalStability()
                + optimized.inCorridorFraction()
                - normalizedCost * 0.25
                + (track.parent() && "combined".equals(track.groupingDecision()) ? 0.15 : 0.0);
            candidates.add(new CenterlineCandidate(track.id(), score, optimized.screenPoints(), optimized.offsetsPx())
                .withEvidence(evidence));
            optimizations.put(track.id(), optimized);
        }
        List<CenterlineCandidate> sorted = candidates.stream()
            .sorted(Comparator.comparingDouble(CenterlineCandidate::score).reversed())
            .toList();
        return new TrackingResult(sorted, corridorProfiles, grouped.tracks(), grouped.decisions(), optimizations);
    }

    private CandidateEvidence evidence(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        CorridorCenterlineOptimizer.OptimizationResult optimized
    ) {
        int supported = track.points().size();
        int empty = Math.max(0, profiles.size() - supported);
        int maxEmpty = maximumEmptyRun(track, profiles.size());
        double totalIntensity = track.points().values().stream()
            .mapToDouble(point -> point.band().peakIntensity())
            .sum();
        double meanIntensity = supported == 0 ? 0.0 : totalIntensity / supported;
        double meanExistence = track.points().values().stream()
            .mapToDouble(point -> point.band().signalExistenceConfidence())
            .average().orElse(0.0);
        double meanLocalization = track.points().values().stream()
            .mapToDouble(point -> point.band().localizationConfidence())
            .average().orElse(0.0);
        double meanGradient = track.points().values().stream()
            .mapToDouble(point -> point.band().gradientStrength())
            .average().orElse(0.0);
        double meanSnr = track.points().values().stream()
            .mapToDouble(point -> Math.max(0.0, point.band().peakIntensity() - point.band().noiseFloor()))
            .average().orElse(0.0);
        double ambiguity = track.parent() && "ambiguous".equals(track.groupingDecision()) ? 1.0 : 0.0;
        double normalizedCost = optimized.totalCost() / Math.max(1, profiles.size());
        return new CandidateEvidence(
            "",
            profiles.size(),
            supported,
            empty,
            maxEmpty,
            totalIntensity,
            meanIntensity,
            meanGradient,
            optimized.longitudinalStability(),
            meanSnr,
            ambiguity,
            meanExistence,
            meanLocalization,
            normalizedCost,
            optimized.inCorridorFraction(),
            List.of()
        );
    }

    private int maximumEmptyRun(CorridorTrack track, int profileCount) {
        int maximum = 0;
        int current = 0;
        for (int i = 0; i < profileCount; i++) {
            if (track.points().containsKey(i)) {
                current = 0;
            } else {
                maximum = Math.max(maximum, ++current);
            }
        }
        return maximum;
    }

    /**
     * Complete corridor-aware tracking output retained for debug serialization.
     *
     * @param candidates optimized centerline candidates
     * @param profiles extracted corridor profiles
     * @param tracks elementary and parent tracks
     * @param groupingDecisions pairwise lane/carriageway interpretation evidence
     * @param optimizations optimizer output keyed by track id
     */
    public record TrackingResult(
        List<CenterlineCandidate> candidates,
        List<CorridorProfile> profiles,
        List<CorridorTrack> tracks,
        List<CorridorGrouping.GroupingDecision> groupingDecisions,
        Map<String, CorridorCenterlineOptimizer.OptimizationResult> optimizations
    ) {
        /** Makes diagnostic result collections immutable. */
        public TrackingResult {
            candidates = List.copyOf(candidates);
            profiles = List.copyOf(profiles);
            tracks = List.copyOf(tracks);
            groupingDecisions = List.copyOf(groupingDecisions);
            optimizations = Map.copyOf(optimizations);
        }
    }
}
