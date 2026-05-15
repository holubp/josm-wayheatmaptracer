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
        if (profiles.isEmpty()) {
            return List.of();
        }
        profiles = suppressLongitudinalNoise(profiles);

        List<Double> seeds = collectSeedOffsets(profiles);
        List<CenterlineCandidate> candidates = new ArrayList<>();
        int candidateIndex = 1;
        for (double seed : seeds) {
            State state = bestPathForSeed(profiles, seed);
            List<Double> offsets = state.offsets();
            List<Double> intensities = state.intensities();

            List<Double> smoothedOffsets = smoothOffsets(offsets, intensities);
            List<Point2D.Double> points = new ArrayList<>();
            for (int i = 0; i < profiles.size(); i++) {
                RenderedHeatmapSampler.CrossSectionProfile profile = profiles.get(i);
                double offset = smoothedOffsets.get(i);
                points.add(new Point2D.Double(
                    profile.anchorScreen().x + profile.normalScreen().x * offset,
                    profile.anchorScreen().y + profile.normalScreen().y * offset
                ));
            }
            candidates.add(new CenterlineCandidate("ridge-" + candidateIndex++, state.score(), points, smoothedOffsets)
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

    private State bestPathForSeed(List<RenderedHeatmapSampler.CrossSectionProfile> profiles, double seed) {
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
                    State candidate = append(previous, peak);
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
        double delta = peak.offsetPx() - previous.offset();
        double acceleration = delta - previous.delta();
        double evidence = peak.intensity() * 2.25
            + peak.gradientStrength() * (0.22 + 0.18 * peak.gradientBalance());
        double continuityPenalty = Math.abs(delta) * 0.16;
        double curvaturePenalty = Math.abs(acceleration) * 0.12;
        List<Double> offsets = new ArrayList<>(previous.offsets());
        List<Double> intensities = new ArrayList<>(previous.intensities());
        offsets.add(peak.offsetPx());
        intensities.add(peak.intensity());
        return new State(
            peak.offsetPx(),
            delta,
            previous.score() + evidence - continuityPenalty - curvaturePenalty,
            offsets,
            intensities
        );
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

    private List<Double> smoothOffsets(List<Double> offsets, List<Double> intensities) {
        if (offsets.size() < 3) {
            return offsets;
        }
        List<Double> current = new ArrayList<>(offsets);
        for (int pass = 0; pass < 2; pass++) {
            List<Double> next = new ArrayList<>(current);
            for (int i = 1; i < current.size() - 1; i++) {
                double intensity = intensities.get(i);
                double smoothing = Math.max(0.0, Math.min(0.75, 0.75 - intensity * 0.70));
                if (smoothing <= 0.01) {
                    continue;
                }
                double neighborAverage = (current.get(i - 1) + current.get(i + 1)) / 2.0;
                next.set(i, current.get(i) * (1.0 - smoothing) + neighborAverage * smoothing);
            }
            current = next;
        }
        return current;
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
}
