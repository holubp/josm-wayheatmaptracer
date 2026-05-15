package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;

public final class RidgeTracker {
    public List<CenterlineCandidate> track(List<RenderedHeatmapSampler.CrossSectionProfile> profiles) {
        return track(profiles, 1.0);
    }

    public List<CenterlineCandidate> track(List<RenderedHeatmapSampler.CrossSectionProfile> profiles, double sourcePixelSizePx) {
        if (profiles.isEmpty()) {
            return List.of();
        }
        double sourcePixel = Double.isFinite(sourcePixelSizePx) && sourcePixelSizePx > 0.0 ? sourcePixelSizePx : 1.0;
        profiles = suppressLongitudinalNoise(profiles);

        List<Double> seeds = collectSeedOffsets(profiles);
        List<CenterlineCandidate> candidates = new ArrayList<>();
        int candidateIndex = 1;
        double edgeLimitPx = profileEdgeLimit(profiles);
        for (double seed : seeds) {
            State state = bestPathForSeed(profiles, seed, sourcePixel);
            PathResult anchored = anchoredPath(profiles, state, sourcePixel, edgeLimitPx);
            List<Double> offsets = anchored.offsets();
            List<Double> intensities = anchored.intensities();

            List<Double> smoothedOffsets = smoothOffsets(profiles, offsets, intensities, sourcePixel);
            List<Point2D.Double> points = new ArrayList<>();
            for (int i = 0; i < profiles.size(); i++) {
                RenderedHeatmapSampler.CrossSectionProfile profile = profiles.get(i);
                double offset = smoothedOffsets.get(i);
                points.add(new Point2D.Double(
                    profile.anchorScreen().x + profile.normalScreen().x * offset,
                    profile.anchorScreen().y + profile.normalScreen().y * offset
                ));
            }
            candidates.add(new CenterlineCandidate("ridge-" + candidateIndex++, anchored.score(), points, smoothedOffsets)
                .withEvidence(evidenceFor(profiles, smoothedOffsets, intensities)));
        }

        return deduplicate(candidates).stream()
            .sorted(Comparator.comparingDouble(CenterlineCandidate::score).reversed())
            .toList();
    }

    private List<RenderedHeatmapSampler.CrossSectionProfile> suppressLongitudinalNoise(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles
    ) {
        if (profiles.size() < 4) {
            return profiles;
        }
        List<RenderedHeatmapSampler.CrossSectionProfile> filtered = new ArrayList<>(profiles.size());
        for (int i = 0; i < profiles.size(); i++) {
            RenderedHeatmapSampler.CrossSectionProfile profile = profiles.get(i);
            List<RenderedHeatmapSampler.CrossSectionPeak> peaks = profile.peaks();
            if (peaks.size() <= 1) {
                filtered.add(profile);
                continue;
            }
            double strongest = peaks.stream()
                .mapToDouble(RenderedHeatmapSampler.CrossSectionPeak::intensity)
                .max()
                .orElse(0.0);
            int profileIndex = i;
            boolean hasPersistentCompetitor = peaks.stream()
                .anyMatch(peak -> longitudinalSupport(profiles, profileIndex, peak)
                    >= requiredLongitudinalSupport(profiles.size(), profileIndex));
            List<RenderedHeatmapSampler.CrossSectionPeak> retained = new ArrayList<>();
            for (RenderedHeatmapSampler.CrossSectionPeak peak : peaks) {
                int support = longitudinalSupport(profiles, i, peak);
                boolean persistent = support >= requiredLongitudinalSupport(profiles.size(), i);
                boolean dominantWithoutAlternative = !hasPersistentCompetitor && peak.intensity() >= strongest * 0.98;
                boolean broadSyntheticCenter = peak.syntheticCenter() && peak.supportWidthPx() >= 8.0 && support >= 1;
                boolean gradientBacked = peak.gradientStrength() >= 0.18 && peak.gradientBalance() >= 0.35 && support >= 1;
                if (persistent || dominantWithoutAlternative || broadSyntheticCenter || gradientBacked) {
                    retained.add(peak);
                }
            }
            if (retained.isEmpty()) {
                retained.add(peaks.stream()
                    .max(Comparator.comparingDouble(RenderedHeatmapSampler.CrossSectionPeak::intensity))
                    .orElse(peaks.get(0)));
            }
            filtered.add(new RenderedHeatmapSampler.CrossSectionProfile(
                profile.anchor(),
                profile.anchorScreen(),
                profile.normalScreen(),
                retained,
                profile.anchorWithinRaster()
            ));
        }
        return filtered;
    }

    private int requiredLongitudinalSupport(int profileCount, int profileIndex) {
        if (profileIndex == 0 || profileIndex == profileCount - 1) {
            return 1;
        }
        return profileCount <= 4 ? 1 : 2;
    }

    private int longitudinalSupport(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        int profileIndex,
        RenderedHeatmapSampler.CrossSectionPeak peak
    ) {
        int support = 0;
        int start = Math.max(0, profileIndex - 2);
        int end = Math.min(profiles.size() - 1, profileIndex + 2);
        for (int i = start; i <= end; i++) {
            if (i == profileIndex) {
                continue;
            }
            if (hasNearbyPeak(profiles.get(i), peak)) {
                support++;
            }
        }
        return support;
    }

    private boolean hasNearbyPeak(
        RenderedHeatmapSampler.CrossSectionProfile profile,
        RenderedHeatmapSampler.CrossSectionPeak target
    ) {
        double tolerance = Math.max(6.0, Math.min(12.0, Math.max(2.0, target.supportWidthPx()) * 1.5));
        double minimumIntensity = Math.max(0.10, target.intensity() * 0.35);
        for (RenderedHeatmapSampler.CrossSectionPeak peak : profile.peaks()) {
            boolean intensityBacked = peak.intensity() >= minimumIntensity;
            boolean gradientBacked = Math.min(peak.gradientStrength(), target.gradientStrength()) >= 0.12
                && Math.min(peak.gradientBalance(), target.gradientBalance()) >= 0.25;
            if (Math.abs(peak.offsetPx() - target.offsetPx()) <= tolerance && (intensityBacked || gradientBacked)) {
                return true;
            }
        }
        return false;
    }

    private State bestPathForSeed(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        double seed,
        double sourcePixelSizePx
    ) {
        double edgeLimitPx = profileEdgeLimit(profiles);
        List<State> states = List.of(new State(seed, 0.0, 0.0, List.of(), List.of()));
        for (RenderedHeatmapSampler.CrossSectionProfile profile : profiles) {
            if (isUnsupportedProfile(profile)) {
                List<State> nextStates = new ArrayList<>();
                for (State previous : states) {
                    nextStates.add(append(previous, new RenderedHeatmapSampler.CrossSectionPeak(previous.offset(), 0.0)));
                }
                states = nextStates;
                continue;
            }
            List<RenderedHeatmapSampler.CrossSectionPeak> peaks = profile.peaks();
            List<State> nextStates = new ArrayList<>();
            for (RenderedHeatmapSampler.CrossSectionPeak peak : peaks) {
                State best = null;
                for (State previous : states) {
                    State candidate = append(previous, peak, profile, Double.NaN, 0.0, sourcePixelSizePx, edgeLimitPx);
                    if (best == null || candidate.score() > best.score()) {
                        best = candidate;
                    }
                }
                nextStates.add(best);
            }
            states = nextStates;
        }
        return states.stream()
            .max(Comparator.comparingDouble(State::score))
            .orElseThrow(() -> new IllegalStateException("No ridge path state was produced."));
    }

    private State append(State previous, RenderedHeatmapSampler.CrossSectionPeak peak) {
        return append(previous, peak, null, Double.NaN, 0.0, 1.0, Double.NaN);
    }

    private State append(
        State previous,
        RenderedHeatmapSampler.CrossSectionPeak peak,
        RenderedHeatmapSampler.CrossSectionProfile profile,
        double targetOffset,
        double targetWeight,
        double sourcePixelSizePx,
        double edgeLimitPx
    ) {
        double delta = peak.offsetPx() - previous.offset();
        double acceleration = delta - previous.delta();
        double evidence = peak.intensity() * 2.25
            + peak.gradientStrength() * (0.22 + 0.18 * peak.gradientBalance())
            + peak.nativeFilteredAgreement() * 0.18;
        double continuityPenalty = Math.abs(delta) * 0.16;
        double curvaturePenalty = Math.abs(acceleration) * 0.12;
        double localPenalty = profile == null ? 0.0 : localPeakPenalty(profile, peak, sourcePixelSizePx, edgeLimitPx);
        double targetPenalty = Double.isFinite(targetOffset)
            ? Math.abs(peak.offsetPx() - targetOffset) / Math.max(1.0, sourcePixelSizePx) * targetWeight
            : 0.0;
        List<Double> offsets = new ArrayList<>(previous.offsets());
        List<Double> intensities = new ArrayList<>(previous.intensities());
        offsets.add(peak.offsetPx());
        intensities.add(peak.intensity());
        return new State(
            peak.offsetPx(),
            delta,
            previous.score() + evidence - continuityPenalty - curvaturePenalty - localPenalty - targetPenalty,
            offsets,
            intensities
        );
    }

    private PathResult anchoredPath(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        State base,
        double sourcePixelSizePx,
        double edgeLimitPx
    ) {
        List<Anchor> anchors = reliableAnchors(profiles, sourcePixelSizePx, edgeLimitPx);
        if (anchors.size() < 2) {
            return new PathResult(base.score(), base.offsets(), base.intensities());
        }

        List<Double> offsets = new ArrayList<>(base.offsets());
        List<Double> intensities = new ArrayList<>(base.intensities());
        double score = base.score();
        for (int i = 0; i < anchors.size() - 1; i++) {
            Anchor left = anchors.get(i);
            Anchor right = anchors.get(i + 1);
            if (right.index() <= left.index()) {
                continue;
            }
            IntervalPath interval = solveAnchoredInterval(profiles, left, right, sourcePixelSizePx, edgeLimitPx);
            if (interval.offsets().isEmpty()) {
                continue;
            }
            for (int profileIndex = left.index(); profileIndex <= right.index(); profileIndex++) {
                int local = profileIndex - left.index();
                offsets.set(profileIndex, interval.offsets().get(local));
                intensities.set(profileIndex, interval.intensities().get(local));
            }
            score += interval.scoreBonus();
        }
        return new PathResult(score, offsets, intensities);
    }

    private IntervalPath solveAnchoredInterval(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        Anchor left,
        Anchor right,
        double sourcePixelSizePx,
        double edgeLimitPx
    ) {
        if (right.index() == left.index()) {
            return new IntervalPath(0.0, List.of(left.offsetPx()), List.of(left.intensity()));
        }
        List<State> states = List.of(new State(left.offsetPx(), 0.0, left.quality(), List.of(left.offsetPx()), List.of(left.intensity())));
        int span = right.index() - left.index();
        for (int index = left.index() + 1; index <= right.index(); index++) {
            double fraction = (double) (index - left.index()) / span;
            double target = left.offsetPx() + (right.offsetPx() - left.offsetPx()) * fraction;
            boolean endpoint = index == right.index();
            List<RenderedHeatmapSampler.CrossSectionPeak> peaks = endpoint
                ? List.of(right.peak())
                : intervalPeaks(profiles.get(index), target);
            List<State> nextStates = new ArrayList<>();
            for (RenderedHeatmapSampler.CrossSectionPeak peak : peaks) {
                State best = null;
                for (State previous : states) {
                    State candidate = append(previous, peak, profiles.get(index), target,
                        intervalTargetWeight(span, peak, profiles.get(index), sourcePixelSizePx),
                        sourcePixelSizePx, edgeLimitPx);
                    if (best == null || candidate.score() > best.score()) {
                        best = candidate;
                    }
                }
                nextStates.add(best);
            }
            states = nextStates;
        }
        State best = states.stream()
            .max(Comparator.comparingDouble(State::score))
            .orElseThrow(() -> new IllegalStateException("No anchored interval state was produced."));
        return new IntervalPath(best.score(), best.offsets(), best.intensities());
    }

    private double intervalTargetWeight(
        int span,
        RenderedHeatmapSampler.CrossSectionPeak peak,
        RenderedHeatmapSampler.CrossSectionProfile profile,
        double sourcePixelSizePx
    ) {
        if (span >= 18 && peak.intensity() >= strongestIntensity(profile) * 0.98 && longitudinalSupportNearby(profile, peak)) {
            return 0.04;
        }
        if (span >= 10 && peak.intensity() >= 0.62) {
            return 0.08;
        }
        return span <= 4 ? 0.36 : 0.20;
    }

    private List<RenderedHeatmapSampler.CrossSectionPeak> intervalPeaks(
        RenderedHeatmapSampler.CrossSectionProfile profile,
        double targetOffset
    ) {
        if (isUnsupportedProfile(profile)) {
            return List.of(new RenderedHeatmapSampler.CrossSectionPeak(targetOffset, 0.0, 0.0, true,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
        }
        return profile.peaks();
    }

    private List<Anchor> reliableAnchors(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        double sourcePixelSizePx,
        double edgeLimitPx
    ) {
        List<Anchor> anchors = new ArrayList<>();
        for (int i = 0; i < profiles.size(); i++) {
            RenderedHeatmapSampler.CrossSectionProfile profile = profiles.get(i);
            Anchor anchor = reliableAnchor(profiles, i, sourcePixelSizePx, edgeLimitPx);
            if (anchor == null) {
                continue;
            }
            if (!anchors.isEmpty()) {
                Anchor previous = anchors.get(anchors.size() - 1);
                if (i - previous.index() <= 2 && Math.abs(anchor.offsetPx() - previous.offsetPx()) > sourcePixelSizePx * 1.35) {
                    continue;
                }
            }
            anchors.add(anchor);
        }
        return anchors;
    }

    private Anchor reliableAnchor(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        int index,
        double sourcePixelSizePx,
        double edgeLimitPx
    ) {
        RenderedHeatmapSampler.CrossSectionProfile profile = profiles.get(index);
        if (isUnsupportedProfile(profile)) {
            return null;
        }
        List<RenderedHeatmapSampler.CrossSectionPeak> candidates = profile.peaks().stream()
            .filter(peak -> peak.intensity() >= 0.30)
            .filter(peak -> peak.supportWidthPx() >= Math.max(6.0, sourcePixelSizePx * 0.20))
            .filter(peak -> !isEdgePeak(peak, sourcePixelSizePx, edgeLimitPx))
            .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        RenderedHeatmapSampler.CrossSectionPeak best = candidates.stream()
            .max(Comparator.comparingDouble(peak -> anchorQuality(profiles, index, peak, sourcePixelSizePx, edgeLimitPx)))
            .orElse(null);
        if (best == null) {
            return null;
        }
        double quality = anchorQuality(profiles, index, best, sourcePixelSizePx, edgeLimitPx);
        int support = longitudinalSupport(profiles, index, best);
        if (support < requiredAnchorSupport(profiles.size(), index)) {
            return null;
        }
        double secondQuality = candidates.stream()
            .filter(peak -> peak != best)
            .mapToDouble(peak -> anchorQuality(profiles, index, peak, sourcePixelSizePx, edgeLimitPx))
            .max()
            .orElse(Double.NEGATIVE_INFINITY);
        boolean dominant = secondQuality == Double.NEGATIVE_INFINITY || quality >= secondQuality + 0.16;
        boolean strongEnough = best.intensity() >= 0.42 || best.prominence() >= 0.16 || best.gradientStrength() >= 0.45;
        if (!dominant && !(strongEnough && quality >= secondQuality + 0.08)) {
            return null;
        }
        return new Anchor(index, best, quality);
    }

    private int requiredAnchorSupport(int profileCount, int profileIndex) {
        if (profileIndex <= 1 || profileIndex >= profileCount - 2) {
            return 1;
        }
        return profileCount <= 8 ? 1 : 2;
    }

    private double anchorQuality(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        int index,
        RenderedHeatmapSampler.CrossSectionPeak peak,
        double sourcePixelSizePx,
        double edgeLimitPx
    ) {
        double supportWidthReward = Math.min(0.34, peak.supportWidthPx() / Math.max(1.0, sourcePixelSizePx) * 0.08);
        double supportReward = Math.min(0.28, longitudinalSupport(profiles, index, peak) * 0.10);
        double gradientReward = peak.gradientStrength() * (0.16 + 0.10 * peak.gradientBalance());
        double nativeFilteredReward = peak.nativeFilteredAgreement() * 0.24;
        double edgePenalty = isEdgePeak(peak, sourcePixelSizePx, edgeLimitPx) ? 0.55 : 0.0;
        double narrowPenalty = peak.supportWidthPx() <= sourcePixelSizePx * 0.15 ? 0.28 : 0.0;
        return peak.intensity() + peak.prominence() * 0.18 + supportWidthReward + supportReward + gradientReward + nativeFilteredReward
            - edgePenalty - narrowPenalty;
    }

    private double localPeakPenalty(
        RenderedHeatmapSampler.CrossSectionProfile profile,
        RenderedHeatmapSampler.CrossSectionPeak peak,
        double sourcePixelSizePx,
        double edgeLimitPx
    ) {
        double strongest = strongestIntensity(profile);
        double regret = Math.max(0.0, strongest - peak.intensity());
        double penalty = regret * 4.2;
        if (isEdgePeak(peak, sourcePixelSizePx, edgeLimitPx)) {
            penalty += 0.65 + regret * 2.0;
        }
        if (peak.supportWidthPx() <= sourcePixelSizePx * 0.15 && strongest > peak.intensity() + 0.03) {
            penalty += 0.30;
        }
        penalty += (1.0 - peak.nativeFilteredAgreement()) * 0.26;
        return penalty;
    }

    private double strongestIntensity(RenderedHeatmapSampler.CrossSectionProfile profile) {
        return profile.peaks().stream()
            .mapToDouble(RenderedHeatmapSampler.CrossSectionPeak::intensity)
            .max()
            .orElse(0.0);
    }

    private boolean longitudinalSupportNearby(
        RenderedHeatmapSampler.CrossSectionProfile profile,
        RenderedHeatmapSampler.CrossSectionPeak peak
    ) {
        return profile.peaks().stream()
            .anyMatch(candidate -> Math.abs(candidate.offsetPx() - peak.offsetPx()) <= Math.max(4.0, peak.supportWidthPx()));
    }

    private boolean isEdgePeak(RenderedHeatmapSampler.CrossSectionPeak peak, double sourcePixelSizePx, double edgeLimitPx) {
        if (!Double.isFinite(edgeLimitPx) || edgeLimitPx <= 0.0) {
            return false;
        }
        return Math.abs(peak.offsetPx()) >= edgeLimitPx * 0.88
            && peak.supportWidthPx() <= Math.max(3.0, sourcePixelSizePx * 0.30);
    }

    private double profileEdgeLimit(List<RenderedHeatmapSampler.CrossSectionProfile> profiles) {
        return profiles.stream()
            .flatMap(profile -> profile.peaks().stream())
            .mapToDouble(peak -> Math.abs(peak.offsetPx()))
            .max()
            .orElse(Double.NaN);
    }

    private List<Double> collectSeedOffsets(List<RenderedHeatmapSampler.CrossSectionProfile> profiles) {
        Map<Integer, Double> clusters = new LinkedHashMap<>();
        int informativeProfiles = 0;
        for (RenderedHeatmapSampler.CrossSectionProfile profile : profiles) {
            boolean informative = false;
            for (RenderedHeatmapSampler.CrossSectionPeak peak : profile.peaks()) {
                if (peak.intensity() < 0.20) {
                    continue;
                }
                informative = true;
                int bucket = (int) Math.round(peak.offsetPx() / 4.0);
                clusters.merge(bucket, peak.intensity(), Double::sum);
            }
            if (informative && ++informativeProfiles >= 12) {
                break;
            }
        }
        if (clusters.isEmpty()) {
            return List.of(0.0);
        }
        return clusters.entrySet().stream()
            .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
            .limit(3)
            .map(entry -> entry.getKey() * 4.0)
            .toList();
    }

    private List<CenterlineCandidate> deduplicate(List<CenterlineCandidate> candidates) {
        List<CenterlineCandidate> distinct = new ArrayList<>();
        for (CenterlineCandidate candidate : candidates) {
            boolean duplicate = distinct.stream().anyMatch(existing -> averageOffsetDistance(existing, candidate) < 3.0);
            if (!duplicate) {
                distinct.add(candidate);
            }
        }
        return distinct;
    }

    private double averageOffsetDistance(CenterlineCandidate left, CenterlineCandidate right) {
        int count = Math.min(left.offsetsPx().size(), right.offsetsPx().size());
        if (count == 0) {
            return Double.MAX_VALUE;
        }
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            sum += Math.abs(left.offsetsPx().get(i) - right.offsetsPx().get(i));
        }
        return sum / count;
    }

    private List<Double> smoothOffsets(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        List<Double> offsets,
        List<Double> intensities,
        double sourcePixelSizePx
    ) {
        if (offsets.size() < 3) {
            return offsets;
        }
        double sourcePixel = Math.max(1.0, sourcePixelSizePx);
        double aliasDeltaThreshold = Math.max(4.0, sourcePixel * 0.18);
        double aliasResidualThreshold = Math.max(5.0, sourcePixel * 0.24);
        double plateauSupportThreshold = Math.max(36.0, sourcePixel * 1.75);
        List<Double> current = new ArrayList<>(offsets);
        for (int pass = 0; pass < 4; pass++) {
            List<Double> baseline = movingAverage(current, 7);
            List<Double> next = new ArrayList<>(current);
            for (int i = 1; i < current.size() - 1; i++) {
                double intensity = intensities.get(i);
                double leftDelta = current.get(i) - current.get(i - 1);
                double rightDelta = current.get(i + 1) - current.get(i);
                double neighborAverage = (current.get(i - 1) + current.get(i + 1)) / 2.0;
                double residual = Math.abs(current.get(i) - neighborAverage);
                boolean alternating = Math.abs(leftDelta) >= aliasDeltaThreshold
                    && Math.abs(rightDelta) >= aliasDeltaThreshold
                    && Math.signum(leftDelta) != Math.signum(rightDelta)
                    && residual >= aliasResidualThreshold;
                RenderedHeatmapSampler.CrossSectionPeak peak = selectedPeak(profiles.get(i), current.get(i));
                boolean broadPlateau = peak.supportWidthPx() >= plateauSupportThreshold && intensity >= 0.45;
                int localFlipCount = localDeltaSignFlips(current, i, 3, Math.max(3.0, sourcePixel * 0.14));
                double highFrequencyResidual = Math.abs(current.get(i) - baseline.get(i));
                boolean subSourcePixelWander = localFlipCount >= 2
                    && highFrequencyResidual >= aliasResidualThreshold
                    && Math.abs(leftDelta) <= sourcePixel * 1.25
                    && Math.abs(rightDelta) <= sourcePixel * 1.25;
                boolean broadPlateauWander = broadPlateau
                    && (localFlipCount >= 2 || subSourcePixelWander)
                    && highFrequencyResidual >= aliasResidualThreshold;
                double lowConfidenceSmoothing = 0.45 * clamp01((0.55 - intensity) / 0.55);
                double aliasingSmoothing = alternating ? (intensity >= 0.80 ? 0.70 : 0.55) : 0.0;
                double plateauSmoothing = broadPlateauWander ? 0.70 : 0.0;
                double smoothing = Math.max(lowConfidenceSmoothing, aliasingSmoothing);
                smoothing = Math.max(smoothing, plateauSmoothing);
                if (smoothing <= 0.01) {
                    continue;
                }
                double target = plateauSmoothing > aliasingSmoothing ? baseline.get(i) : neighborAverage;
                next.set(i, current.get(i) * (1.0 - smoothing) + target * smoothing);
            }
            current = next;
        }
        return current;
    }

    private List<Double> movingAverage(List<Double> offsets, int window) {
        int radius = Math.max(1, window / 2);
        List<Double> smoothed = new ArrayList<>(offsets.size());
        for (int i = 0; i < offsets.size(); i++) {
            int start = Math.max(0, i - radius);
            int end = Math.min(offsets.size() - 1, i + radius);
            double total = 0.0;
            for (int j = start; j <= end; j++) {
                total += offsets.get(j);
            }
            smoothed.add(total / (end - start + 1));
        }
        return smoothed;
    }

    private int localDeltaSignFlips(List<Double> offsets, int center, int radius, double thresholdPx) {
        int start = Math.max(1, center - radius);
        int end = Math.min(offsets.size() - 1, center + radius);
        int flips = 0;
        Double previous = null;
        for (int i = start; i <= end; i++) {
            double delta = offsets.get(i) - offsets.get(i - 1);
            if (Math.abs(delta) < thresholdPx) {
                continue;
            }
            double sign = Math.signum(delta);
            if (previous != null && sign != previous) {
                flips++;
            }
            previous = sign;
        }
        return flips;
    }

    private double clamp01(double value) {
        if (value <= 0.0) {
            return 0.0;
        }
        if (value >= 1.0) {
            return 1.0;
        }
        return value;
    }

    private CandidateEvidence evidenceFor(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        List<Double> offsets,
        List<Double> intensities
    ) {
        int supported = 0;
        int empty = 0;
        int maxEmpty = 0;
        int currentEmpty = 0;
        double total = 0.0;
        double ambiguity = 0.0;
        double prominenceTotal = 0.0;
        double gradientTotal = 0.0;
        double stabilityTotal = 0.0;
        for (int i = 0; i < profiles.size(); i++) {
            RenderedHeatmapSampler.CrossSectionProfile profile = profiles.get(i);
            double intensity = i < intensities.size() ? intensities.get(i) : 0.0;
            if (intensity > 0.0) {
                supported++;
                total += intensity;
                prominenceTotal += strongestProminence(profile);
                RenderedHeatmapSampler.CrossSectionPeak strongest = selectedPeak(profile, i < offsets.size() ? offsets.get(i) : 0.0);
                gradientTotal += strongest.gradientStrength();
                stabilityTotal += Math.min(1.0, longitudinalSupport(profiles, i, strongest) / 2.0);
                currentEmpty = 0;
            } else {
                currentEmpty++;
                maxEmpty = Math.max(maxEmpty, currentEmpty);
            }
            if (profile.peaks().isEmpty()) {
                empty++;
            } else if (isUnsupportedProfile(profile)) {
                empty++;
            } else {
                ambiguity += Math.max(0, profile.peaks().size() - 1);
            }
        }
        double mean = supported == 0 ? 0.0 : total / supported;
        double supportRatio = profiles.isEmpty() ? 0.0 : (double) supported / profiles.size();
        double ambiguityPenalty = profiles.isEmpty() ? 0.0 : ambiguity / profiles.size();
        double meanProminence = supported == 0 ? 0.0 : prominenceTotal / supported;
        double meanGradient = supported == 0 ? 0.0 : gradientTotal / supported;
        double longitudinalStability = supported == 0 ? 0.0 : stabilityTotal / supported;
        double snr = (mean * 0.58 + meanProminence * 0.82 + meanGradient * 0.28) * supportRatio
            * (0.75 + 0.25 * longitudinalStability) / (1.0 + ambiguityPenalty);
        return new CandidateEvidence("", profiles.size(), supported, empty, maxEmpty,
            total, mean, meanGradient, longitudinalStability, snr, ambiguityPenalty, List.of());
    }

    private double strongestProminence(RenderedHeatmapSampler.CrossSectionProfile profile) {
        return profile.peaks().stream()
            .mapToDouble(RenderedHeatmapSampler.CrossSectionPeak::prominence)
            .max()
            .orElse(0.0);
    }

    private RenderedHeatmapSampler.CrossSectionPeak selectedPeak(RenderedHeatmapSampler.CrossSectionProfile profile, double offset) {
        return profile.peaks().stream()
            .min(Comparator.comparingDouble(peak -> Math.abs(peak.offsetPx() - offset)))
            .orElse(new RenderedHeatmapSampler.CrossSectionPeak(0.0, 0.0));
    }

    private boolean isUnsupportedProfile(RenderedHeatmapSampler.CrossSectionProfile profile) {
        return profile.peaks().isEmpty()
            || profile.peaks().stream().allMatch(this::isUnsupportedFallbackPeak);
    }

    private boolean isUnsupportedFallbackPeak(RenderedHeatmapSampler.CrossSectionPeak peak) {
        return peak.syntheticCenter()
            && peak.supportWidthPx() <= 0.0
            && peak.prominence() <= 0.0
            && peak.maxProfileIntensity() <= 0.0;
    }

    private record State(
        double offset,
        double delta,
        double score,
        List<Double> offsets,
        List<Double> intensities
    ) {
    }

    private record Anchor(int index, RenderedHeatmapSampler.CrossSectionPeak peak, double quality) {
        double offsetPx() {
            return peak.offsetPx();
        }

        double intensity() {
            return peak.intensity();
        }
    }

    private record PathResult(double score, List<Double> offsets, List<Double> intensities) {
    }

    private record IntervalPath(double scoreBonus, List<Double> offsets, List<Double> intensities) {
    }
}
