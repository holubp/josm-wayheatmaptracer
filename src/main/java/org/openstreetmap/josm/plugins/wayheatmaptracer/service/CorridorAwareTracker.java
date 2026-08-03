package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorCoverage;

/**
 * Orchestrates extraction, longitudinal association, grouping, and stable corridor optimization.
 */
public final class CorridorAwareTracker {
    private final CorridorExtractor extractor = new CorridorExtractor();
    private final CorridorTracker tracker = new CorridorTracker();
    private final CorridorGrouping grouping = new CorridorGrouping();
    private final CorridorTubeBuilder tubeBuilder = new CorridorTubeBuilder();
    private final CorridorCenterlineOptimizer optimizer = new CorridorCenterlineOptimizer();
    private final CorridorCoverageCalculator coverageCalculator = new CorridorCoverageCalculator();

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
        validatePhysicalProfileSequence(profiles);
        long extractionStart = System.nanoTime();
        List<CorridorProfile> corridorProfiles = extractor.extract(profiles);
        long extractionNanos = System.nanoTime() - extractionStart;
        return trackExtracted(corridorProfiles, sourcePixelSizePx, junctionContext, Map.of(), List.of(),
            extractionNanos, 0L);
    }

    /**
     * Tracks a fine corridor using compatible L1/L2 observations as uncertainty-gated evidence.
     *
     * @param profileSet aligned L0/L1/L2 scalar profiles
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @param junctionContext endpoint and junction boundary conditions
     * @return candidates and complete multiscale diagnostics
     */
    public TrackingResult trackDetailed(
        MultiScaleProfileSet profileSet,
        double sourcePixelSizePx,
        JunctionContext junctionContext
    ) {
        if (profileSet.levels().isEmpty()) {
            return trackDetailed(List.of(), sourcePixelSizePx, junctionContext);
        }
        for (MultiScaleProfileSet.ScaleProfileLevel level : profileSet.levels()) {
            validatePhysicalProfileSequence(level.profiles());
        }
        validateAlignedPhysicalSequences(profileSet);
        long extractionStart = System.nanoTime();
        List<List<CorridorProfile>> extractedLevels = profileSet.levels().stream()
            .map(level -> extractor.extract(level.profiles()))
            .toList();
        long extractionNanos = System.nanoTime() - extractionStart;
        List<CorridorProfile> fine = extractedLevels.get(0);
        long associationStart = System.nanoTime();
        ScaleAssociation association = associateScales(profileSet, extractedLevels, sourcePixelSizePx);
        long associationNanos = System.nanoTime() - associationStart;
        return trackExtracted(fine, sourcePixelSizePx, junctionContext,
            association.evidence(), association.profiles(), extractionNanos, associationNanos);
    }

    private void validatePhysicalProfileSequence(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles
    ) {
        double previous = -1.0;
        for (int index = 0; index < profiles.size(); index++) {
            double current = profiles.get(index).cumulativeGroundDistanceMeters();
            if (!Double.isFinite(current) || current < 0.0 || current + 1e-9 < previous) {
                throw new IllegalArgumentException(
                    "Corridor profile physical distances must be finite, non-negative, and monotonic at index "
                        + index + '.');
            }
            previous = current;
        }
    }

    private void validateAlignedPhysicalSequences(MultiScaleProfileSet profileSet) {
        List<RenderedHeatmapSampler.CrossSectionProfile> reference = profileSet.levelZeroProfiles();
        for (MultiScaleProfileSet.ScaleProfileLevel level : profileSet.levels()) {
            if (level.profiles().size() != reference.size()) {
                throw new IllegalArgumentException("Corridor scale levels must have identical profile counts.");
            }
            for (int index = 0; index < reference.size(); index++) {
                double expected = reference.get(index).cumulativeGroundDistanceMeters();
                double actual = level.profiles().get(index).cumulativeGroundDistanceMeters();
                if (Math.abs(expected - actual) > 1e-6) {
                    throw new IllegalArgumentException(
                        "Corridor scale levels must share one physical profile-distance sequence.");
                }
            }
        }
    }

    private TrackingResult trackExtracted(
        List<CorridorProfile> corridorProfiles,
        double sourcePixelSizePx,
        JunctionContext junctionContext,
        Map<String, BandScaleEvidence> scaleEvidence,
        List<MultiScaleCorridorProfile> multiScaleProfiles,
        long extractionNanos,
        long scaleAssociationNanos
    ) {
        long trackingStart = System.nanoTime();
        List<CorridorTrack> elementary = tracker.track(corridorProfiles, sourcePixelSizePx, scaleEvidence);
        CorridorGrouping.GroupingResult grouped = grouping.group(elementary, corridorProfiles);
        long trackingNanos = System.nanoTime() - trackingStart;
        long optimizationStart = System.nanoTime();
        List<CenterlineCandidate> candidates = new ArrayList<>();
        Map<String, CorridorCenterlineOptimizer.OptimizationResult> optimizations = new LinkedHashMap<>();
        Map<String, LongitudinalCorridorTube> tubes = new LinkedHashMap<>();
        for (CorridorTrack track : grouped.tracks()) {
            LongitudinalCorridorTube tube = tubeBuilder.build(
                track, corridorProfiles, sourcePixelSizePx, scaleEvidence);
            CorridorCenterlineOptimizer.OptimizationResult optimized = optimizer.optimize(
                track, corridorProfiles, sourcePixelSizePx, junctionContext, scaleEvidence, tube);
            if (optimized.offsetsPx().isEmpty()) {
                continue;
            }
            CorridorCoverage coverage = coverageCalculator.calculate(
                track, corridorProfiles, optimized.endpointApproaches());
            CandidateEvidence evidence = evidence(track, corridorProfiles, optimized, scaleEvidence, coverage);
            double normalizedCost = optimized.totalCost() / Math.max(1, corridorProfiles.size());
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
            tubes.put(track.id(), tube);
        }
        List<CenterlineCandidate> sorted = candidates.stream()
            .sorted(Comparator.comparingDouble(CenterlineCandidate::score).reversed())
            .toList();
        long optimizationNanos = System.nanoTime() - optimizationStart;
        return new TrackingResult(sorted, corridorProfiles, grouped.tracks(), grouped.decisions(), optimizations,
            tubes, multiScaleProfiles, scaleEvidence, sourcePixelSizePx,
            new TrackingTiming(extractionNanos, scaleAssociationNanos, trackingNanos, optimizationNanos));
    }

    private CandidateEvidence evidence(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        CorridorCenterlineOptimizer.OptimizationResult optimized,
        Map<String, BandScaleEvidence> scaleEvidence,
        CorridorCoverage coverage
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
        double meanPersistence = track.points().values().stream()
            .map(point -> scaleEvidence.get(CorridorCenterlineOptimizer.scaleEvidenceKey(
                point.profileIndex(), point.band().id())))
            .filter(java.util.Objects::nonNull)
            .mapToDouble(BandScaleEvidence::scalePersistence)
            .average().orElse(0.0);
        double conflictFraction = supported == 0 ? 0.0 : track.points().values().stream()
            .map(point -> scaleEvidence.get(CorridorCenterlineOptimizer.scaleEvidenceKey(
                point.profileIndex(), point.band().id())))
            .filter(java.util.Objects::nonNull)
            .filter(BandScaleEvidence::scaleConflict)
            .count() / (double) supported;
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
            meanPersistence,
            conflictFraction,
            optimized.quality(),
            coverage,
            List.of()
        );
    }

    private ScaleAssociation associateScales(
        MultiScaleProfileSet profileSet,
        List<List<CorridorProfile>> extractedLevels,
        double sourcePixelSizePx
    ) {
        List<MultiScaleCorridorProfile> diagnostics = new ArrayList<>();
        Map<String, BandScaleEvidence> evidence = new LinkedHashMap<>();
        List<CorridorProfile> fineProfiles = extractedLevels.get(0);
        for (int profileIndex = 0; profileIndex < fineProfiles.size(); profileIndex++) {
            List<ScaleCorridorObservation> observations = new ArrayList<>();
            for (int levelIndex = 0; levelIndex < extractedLevels.size(); levelIndex++) {
                CorridorProfile profile = extractedLevels.get(levelIndex).get(profileIndex);
                MultiScaleProfileSet.ScaleProfileLevel level = profileSet.levels().get(levelIndex);
                observations.add(new ScaleCorridorObservation(level.level(), level.reduction(),
                    level.effectiveSigmaL0(), profile.source().anchorWithinRaster(), profile.bands()));
            }
            diagnostics.add(new MultiScaleCorridorProfile(profileIndex, observations));
            for (CorridorBand fineBand : fineProfiles.get(profileIndex).bands()) {
                evidence.put(CorridorCenterlineOptimizer.scaleEvidenceKey(profileIndex, fineBand.id()),
                    associateBand(fineBand, fineProfiles.get(profileIndex).bands(), observations,
                        sourcePixelSizePx));
            }
        }
        return new ScaleAssociation(Map.copyOf(evidence), List.copyOf(diagnostics));
    }

    private BandScaleEvidence associateBand(
        CorridorBand fineBand,
        List<CorridorBand> fineBands,
        List<ScaleCorridorObservation> observations,
        double sourcePixelSizePx
    ) {
        double persistence = 0.50;
        double weightedCenter = 0.0;
        double centerWeight = 0.0;
        double uncertainty = 0.0;
        boolean conflict = false;
        boolean parentMerge = false;
        List<Integer> participating = new ArrayList<>(List.of(0));
        for (int level = 1; level < observations.size(); level++) {
            ScaleCorridorObservation observation = observations.get(level);
            List<CorridorBand> matches = observation.bands().stream()
                .filter(coarse -> compatible(fineBand, coarse, sourcePixelSizePx))
                .sorted(Comparator.comparingDouble(coarse -> Math.abs(
                    coarse.centerOffsetPx() - fineBand.centerOffsetPx())))
                .toList();
            if (matches.isEmpty()) {
                conflict |= !observation.bands().isEmpty();
                continue;
            }
            CorridorBand match = matches.get(0);
            double levelWeight = level == 1 ? 0.35 : 0.15;
            persistence += levelWeight;
            participating.add(level);
            long fineChildren = fineBands.stream().filter(fine -> compatible(fine, match, sourcePixelSizePx)).count();
            boolean merged = match.parentHypothesis() || fineChildren > 1;
            parentMerge |= merged;
            if (!merged) {
                weightedCenter += levelWeight * match.centerOffsetPx();
                centerWeight += levelWeight;
                uncertainty = Math.max(uncertainty, match.uncertaintyPx());
            }
        }
        return new BandScaleEvidence(persistence,
            centerWeight <= 0.0 ? Double.NaN : weightedCenter / centerWeight,
            centerWeight <= 0.0 ? Double.NaN : Math.max(sourcePixelSizePx * 0.5, uncertainty),
            participating, conflict, parentMerge);
    }

    private boolean compatible(CorridorBand fine, CorridorBand coarse, double sourcePixelSizePx) {
        double overlap = Math.max(0.0, Math.min(fine.shoulderMaxPx(), coarse.shoulderMaxPx())
            - Math.max(fine.shoulderMinPx(), coarse.shoulderMinPx()));
        double narrower = Math.max(1e-9, Math.min(fine.shoulderWidthPx(), coarse.shoulderWidthPx()));
        double centerLimit = Math.max(0.5 * sourcePixelSizePx, fine.uncertaintyPx() + coarse.uncertaintyPx());
        return overlap / narrower >= 0.50
            || Math.abs(fine.centerOffsetPx() - coarse.centerOffsetPx()) <= centerLimit;
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
     * @param tubes robust longitudinal evidence keyed by track id
     * @param multiScaleProfiles extracted L0/L1/L2 observations
     * @param scaleEvidence fine-band scale associations keyed by profile and band id
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @param timing observational phase timings that never affect candidate decisions
     */
    public record TrackingResult(
        List<CenterlineCandidate> candidates,
        List<CorridorProfile> profiles,
        List<CorridorTrack> tracks,
        List<CorridorGrouping.GroupingDecision> groupingDecisions,
        Map<String, CorridorCenterlineOptimizer.OptimizationResult> optimizations,
        Map<String, LongitudinalCorridorTube> tubes,
        List<MultiScaleCorridorProfile> multiScaleProfiles,
        Map<String, BandScaleEvidence> scaleEvidence,
        double sourcePixelSizePx,
        TrackingTiming timing
    ) {
        /** Makes diagnostic result collections immutable. */
        public TrackingResult {
            candidates = List.copyOf(candidates);
            profiles = List.copyOf(profiles);
            tracks = List.copyOf(tracks);
            groupingDecisions = List.copyOf(groupingDecisions);
            optimizations = Map.copyOf(optimizations);
            tubes = Map.copyOf(tubes);
            multiScaleProfiles = List.copyOf(multiScaleProfiles);
            scaleEvidence = Map.copyOf(scaleEvidence);
            timing = java.util.Objects.requireNonNull(timing);
        }
    }

    /**
     * Observational corridor phase timings in nanoseconds.
     *
     * @param extractionNanos scalar-profile corridor extraction
     * @param scaleAssociationNanos cross-scale association
     * @param trackingAndGroupingNanos longitudinal tracking and grouping
     * @param optimizationNanos exact optimization, evidence, and candidate construction
     */
    public record TrackingTiming(
        long extractionNanos,
        long scaleAssociationNanos,
        long trackingAndGroupingNanos,
        long optimizationNanos
    ) {
        /** Rejects negative timer values. */
        public TrackingTiming {
            if (extractionNanos < 0L || scaleAssociationNanos < 0L
                || trackingAndGroupingNanos < 0L || optimizationNanos < 0L) {
                throw new IllegalArgumentException("Corridor timing values must be non-negative");
            }
        }
    }

    private record ScaleAssociation(
        Map<String, BandScaleEvidence> evidence,
        List<MultiScaleCorridorProfile> profiles
    ) {
    }
}
