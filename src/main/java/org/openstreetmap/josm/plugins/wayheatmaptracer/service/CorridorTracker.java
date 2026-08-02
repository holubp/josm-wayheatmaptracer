package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Associates elementary corridor bands across profiles while preserving longitudinal identity.
 */
public final class CorridorTracker {
    private static final int MAX_GAP_PROFILES = 16;
    private static final double MAX_GAP_METERS = 20.0;
    private static final double LARGE_PREDICTION_RESIDUAL_SOURCE_PIXELS = 1.5;
    private static final int MAX_STATES_PER_SEED = 24;

    /**
     * Creates a stateless longitudinal corridor tracker.
     */
    public CorridorTracker() {
        // Stateless tracker.
    }

    /**
     * Tracks all elementary bands seeded from the first informative profile.
     *
     * @param profiles extracted corridor profiles
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @return distinct elementary tracks ordered by score
     */
    public List<CorridorTrack> track(List<CorridorProfile> profiles, double sourcePixelSizePx) {
        return track(profiles, sourcePixelSizePx, Map.of());
    }

    /**
     * Tracks elementary bands with cross-scale conflicts available to transition gating.
     *
     * @param profiles extracted corridor profiles
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @param scaleEvidence cross-scale evidence keyed by profile and band id
     * @return distinct elementary tracks ordered by score
     */
    public List<CorridorTrack> track(
        List<CorridorProfile> profiles,
        double sourcePixelSizePx,
        Map<String, BandScaleEvidence> scaleEvidence
    ) {
        if (profiles.isEmpty()) {
            return List.of();
        }
        List<Seed> seeds = collectSeeds(profiles);
        if (seeds.isEmpty()) {
            return List.of();
        }
        double sourcePixel = validSourcePixel(sourcePixelSizePx);
        List<CorridorTrack> tracks = new ArrayList<>();
        int trackIndex = 1;
        for (Seed seed : seeds) {
            PathState best = solveSeed(profiles, seed.profileIndex(), seed.band(), sourcePixel, scaleEvidence);
            int minimumSupport = Math.max(2, (int) Math.ceil(profiles.size() * 0.03));
            if (best.points().size() < minimumSupport) {
                continue;
            }
            double supportRatio = (double) best.points().size() / profiles.size();
            tracks.add(new CorridorTrack("strand-" + trackIndex++, best.points(), best.score(), supportRatio,
                false, List.of(), ""));
        }
        List<CorridorTrack> ranked = tracks.stream()
            .sorted(Comparator.comparingDouble(CorridorTrack::score).reversed()).toList();
        return deduplicate(ranked, sourcePixel);
    }

    private PathState solveSeed(
        List<CorridorProfile> profiles,
        int seedProfile,
        CorridorBand seed,
        double sourcePixel,
        Map<String, BandScaleEvidence> scaleEvidence
    ) {
        Map<Integer, CorridorTrackPoint> seedPoints = new LinkedHashMap<>();
        seedPoints.put(seedProfile, new CorridorTrackPoint(seedProfile, seed, false));
        Point2D.Double seedCenter = centerPoint(profiles.get(seedProfile), seed);
        PathState seedState = new PathState(seedProfile, -1, seed, seedCenter, null, Double.NaN,
            bandReward(seed), 0, seedPoints);
        PathState forward = advance(profiles, seedState, seedProfile + 1, profiles.size(), 1, sourcePixel,
            scaleEvidence);
        PathState backward = advance(profiles, seedState, seedProfile - 1, -1, -1, sourcePixel,
            scaleEvidence);
        Map<Integer, CorridorTrackPoint> merged = new LinkedHashMap<>(backward.points());
        merged.putAll(forward.points());
        return new PathState(forward.lastProfileIndex(), forward.previousProfileIndex(), forward.band(),
            forward.centerPoint(), forward.previousCenterPoint(), forward.previousOffsetPx(),
            forward.score() + backward.score() - bandReward(seed), 0, merged);
    }

    private PathState advance(
        List<CorridorProfile> profiles,
        PathState seedState,
        int start,
        int endExclusive,
        int direction,
        double sourcePixel,
        Map<String, BandScaleEvidence> scaleEvidence
    ) {
        List<PathState> states = List.of(seedState);
        for (int profileIndex = start; profileIndex != endExclusive; profileIndex += direction) {
            CorridorProfile profile = profiles.get(profileIndex);
            List<CorridorBand> observations = elementaryBands(profile);
            List<PathState> next = new ArrayList<>();
            for (PathState state : states) {
                double gapDistanceMeters = profileDistanceMeters(
                    profiles, state.lastProfileIndex(), profileIndex);
                if (state.gapCount() < MAX_GAP_PROFILES && gapDistanceMeters <= MAX_GAP_METERS + 1e-9) {
                    next.add(state.withGap());
                }
                for (CorridorBand observation : observations) {
                    PathState extended = extend(state, profiles, profileIndex, profile, observation, sourcePixel,
                        direction, scaleEvidence);
                    if (extended != null) {
                        next.add(extended);
                    }
                }
            }
            if (next.isEmpty()) {
                break;
            }
            states = next.stream()
                .sorted(Comparator.comparingDouble(PathState::score).reversed())
                .limit(MAX_STATES_PER_SEED)
                .toList();
        }
        return states.stream().max(Comparator.comparingDouble(PathState::score)).orElseThrow();
    }

    private PathState extend(
        PathState state,
        List<CorridorProfile> profiles,
        int profileIndex,
        CorridorProfile profile,
        CorridorBand observation,
        double sourcePixel,
        int direction,
        Map<String, BandScaleEvidence> scaleEvidence
    ) {
        if (state.gapCount() > 0
            && profileDistanceMeters(profiles, state.lastProfileIndex(), profileIndex) > MAX_GAP_METERS + 1e-9) {
            return null;
        }
        Point2D.Double center = centerPoint(profile, observation);
        double longitudinalDistance = center.distance(state.centerPoint());
        double anchorDistance = profile.source().anchorScreen().distance(
            profiles.get(state.lastProfileIndex()).source().anchorScreen());
        double expectedLongitudinal = Math.max(sourcePixel, anchorDistance);
        double excessDistance = Math.max(0.0, longitudinalDistance - expectedLongitudinal);
        double lateralTolerance = Math.max(sourcePixel * 3.0,
            (observation.shoulderWidthPx() + state.band().shoulderWidthPx()) * 0.75 + sourcePixel);
        if (excessDistance > lateralTolerance * (state.gapCount() + 1.0)) {
            return null;
        }

        double predictedOffset = predictedOffset(state, profiles, profileIndex);
        double predictionResidual = Math.abs(observation.centerOffsetPx() - predictedOffset) / sourcePixel;
        BandScaleEvidence observationScale = scaleEvidence.get(
            CorridorCenterlineOptimizer.scaleEvidenceKey(profileIndex, observation.id()));
        boolean unreliableScale = observationScale != null
            && (observationScale.scaleConflict() || observationScale.parentMerge());
        boolean sustainedMotion = hasSustainedMotion(
            profiles, state, profileIndex, observation, direction, sourcePixel);
        if (predictionResidual > LARGE_PREDICTION_RESIDUAL_SOURCE_PIXELS
            && (unreliableScale || !sustainedMotion)) {
            return null;
        }

        double displacement = excessDistance / sourcePixel;
        double widthChange = Math.abs(observation.shoulderWidthPx() - state.band().shoulderWidthPx())
            / Math.max(sourcePixel, Math.max(observation.shoulderWidthPx(), state.band().shoulderWidthPx()));
        double acceleration = 0.0;
        if (state.previousCenterPoint() != null) {
            double previousDx = state.centerPoint().x - state.previousCenterPoint().x;
            double previousDy = state.centerPoint().y - state.previousCenterPoint().y;
            double currentDx = center.x - state.centerPoint().x;
            double currentDy = center.y - state.centerPoint().y;
            acceleration = Math.hypot(currentDx - previousDx, currentDy - previousDy) / sourcePixel;
        }
        double uncertainty = observation.uncertaintyPx() / sourcePixel;
        double cost = displacement * 0.75 + widthChange * 0.35 + acceleration * 0.18
            + uncertainty * 0.10 + state.gapCount() * 0.35;
        Map<Integer, CorridorTrackPoint> points = new LinkedHashMap<>(state.points());
        points.put(profileIndex, new CorridorTrackPoint(profileIndex, observation, state.gapCount() > 0));
        return new PathState(profileIndex, state.lastProfileIndex(), observation, center, state.centerPoint(),
            state.band().centerOffsetPx(), state.score() + bandReward(observation) - cost, 0, points);
    }

    private double predictedOffset(PathState state, List<CorridorProfile> profiles, int profileIndex) {
        if (state.previousProfileIndex() < 0 || !Double.isFinite(state.previousOffsetPx())) {
            return state.band().centerOffsetPx();
        }
        double previousDistance = profileDistancePixels(profiles, state.previousProfileIndex(),
            state.lastProfileIndex());
        if (previousDistance <= 1e-9) {
            return state.band().centerOffsetPx();
        }
        double currentDistance = profileDistancePixels(profiles, state.lastProfileIndex(), profileIndex);
        double slope = (state.band().centerOffsetPx() - state.previousOffsetPx()) / previousDistance;
        return state.band().centerOffsetPx() + slope * currentDistance;
    }

    private boolean hasSustainedMotion(
        List<CorridorProfile> profiles,
        PathState state,
        int profileIndex,
        CorridorBand observation,
        int direction,
        double sourcePixel
    ) {
        double initialMotion = observation.centerOffsetPx() - state.band().centerOffsetPx();
        if (Math.abs(initialMotion) <= 1e-9) {
            return false;
        }
        double currentOffset = observation.centerOffsetPx();
        int coherent = 0;
        int examined = 0;
        for (int lookahead = 1; lookahead <= 3; lookahead++) {
            int nextIndex = profileIndex + direction * lookahead;
            if (nextIndex < 0 || nextIndex >= profiles.size()) {
                break;
            }
            double expectedOffset = currentOffset;
            CorridorBand next = elementaryBands(profiles.get(nextIndex)).stream()
                .min(Comparator.comparingDouble(band -> Math.abs(band.centerOffsetPx() - expectedOffset)))
                .orElse(null);
            if (next == null) {
                break;
            }
            double motion = next.centerOffsetPx() - currentOffset;
            if (Math.abs(motion) > 0.10 * sourcePixel
                && Math.signum(motion) == Math.signum(initialMotion)) {
                coherent++;
            }
            examined++;
            currentOffset = next.centerOffsetPx();
        }
        return examined >= 2 && coherent >= 2
            && Math.abs(currentOffset - observation.centerOffsetPx()) >= 0.5 * sourcePixel;
    }

    private double profileDistancePixels(List<CorridorProfile> profiles, int leftIndex, int rightIndex) {
        return profiles.get(leftIndex).source().anchorScreen().distance(
            profiles.get(rightIndex).source().anchorScreen());
    }

    private double profileDistanceMeters(List<CorridorProfile> profiles, int leftIndex, int rightIndex) {
        return Math.abs(profiles.get(rightIndex).source().cumulativeGroundDistanceMeters()
            - profiles.get(leftIndex).source().cumulativeGroundDistanceMeters());
    }

    private double bandReward(CorridorBand band) {
        return 1.5 * band.signalExistenceConfidence() + band.localizationConfidence();
    }

    private List<CorridorBand> elementaryBands(CorridorProfile profile) {
        return profile.bands().stream().filter(band -> !band.parentHypothesis()).toList();
    }

    private List<Seed> collectSeeds(List<CorridorProfile> profiles) {
        List<Seed> seeds = new ArrayList<>();
        int stride = Math.max(1, (profiles.size() - 1) / 8);
        for (int target = 0; target < profiles.size(); target += stride) {
            int profileIndex = nearestInformativeProfile(profiles, target);
            if (profileIndex < 0) {
                continue;
            }
            for (CorridorBand band : elementaryBands(profiles.get(profileIndex))) {
                boolean duplicate = seeds.stream().anyMatch(seed -> seed.profileIndex() == profileIndex
                    && seed.band().id().equals(band.id()));
                if (!duplicate) {
                    seeds.add(new Seed(profileIndex, band));
                }
            }
            if (seeds.size() >= 16) {
                break;
            }
        }
        return seeds;
    }

    private int nearestInformativeProfile(List<CorridorProfile> profiles, int target) {
        for (int radius = 0; radius < profiles.size(); radius++) {
            int left = target - radius;
            if (left >= 0 && !elementaryBands(profiles.get(left)).isEmpty()) {
                return left;
            }
            int right = target + radius;
            if (right < profiles.size() && !elementaryBands(profiles.get(right)).isEmpty()) {
                return right;
            }
        }
        return -1;
    }

    private Point2D.Double centerPoint(CorridorProfile profile, CorridorBand band) {
        return new Point2D.Double(
            profile.source().anchorScreen().x + profile.source().normalScreen().x * band.centerOffsetPx(),
            profile.source().anchorScreen().y + profile.source().normalScreen().y * band.centerOffsetPx()
        );
    }

    private List<CorridorTrack> deduplicate(List<CorridorTrack> tracks, double sourcePixel) {
        List<CorridorTrack> retained = new ArrayList<>();
        for (CorridorTrack candidate : tracks) {
            boolean duplicate = retained.stream().anyMatch(existing -> sameLongitudinalIdentity(
                existing, candidate, sourcePixel));
            if (!duplicate) {
                retained.add(candidate);
            }
        }
        return retained;
    }

    private boolean sameLongitudinalIdentity(CorridorTrack left, CorridorTrack right, double sourcePixel) {
        int common = 0;
        int close = 0;
        for (Map.Entry<Integer, CorridorTrackPoint> entry : left.points().entrySet()) {
            CorridorTrackPoint other = right.points().get(entry.getKey());
            if (other != null) {
                common++;
                double separation = Math.abs(entry.getValue().band().centerOffsetPx()
                    - other.band().centerOffsetPx());
                if (separation < sourcePixel * 0.65) {
                    close++;
                }
            }
        }
        int shorterSupport = Math.min(left.points().size(), right.points().size());
        return common >= Math.ceil(shorterSupport * 0.60)
            && close >= Math.ceil(common * 0.75);
    }

    private double validSourcePixel(double sourcePixelSizePx) {
        return Double.isFinite(sourcePixelSizePx) && sourcePixelSizePx > 0.0 ? sourcePixelSizePx : 1.0;
    }

    private record PathState(
        int lastProfileIndex,
        int previousProfileIndex,
        CorridorBand band,
        Point2D.Double centerPoint,
        Point2D.Double previousCenterPoint,
        double previousOffsetPx,
        double score,
        int gapCount,
        Map<Integer, CorridorTrackPoint> points
    ) {
        PathState withGap() {
            return new PathState(lastProfileIndex, previousProfileIndex, band, centerPoint, previousCenterPoint,
                previousOffsetPx, score - 0.18 * (gapCount + 1), gapCount + 1, points);
        }
    }

    private record Seed(int profileIndex, CorridorBand band) {
    }
}
