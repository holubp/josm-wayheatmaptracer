package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;

public final class RidgeTracker {
    private static final int MAX_SEEDS = 6;
    private static final int MAX_STATES_PER_PROFILE = 18;

    public List<CenterlineCandidate> track(List<RenderedHeatmapSampler.CrossSectionProfile> profiles) {
        if (profiles.isEmpty()) {
            return List.of();
        }

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
            double stableScore = state.score() + stabilityBonus(smoothedOffsets, intensities);
            candidates.add(new CenterlineCandidate("ridge-" + candidateIndex++, stableScore, points, smoothedOffsets));
        }

        return deduplicate(candidates).stream()
            .sorted(Comparator.comparingDouble(CenterlineCandidate::score).reversed())
            .toList();
    }

    private State bestPathForSeed(List<RenderedHeatmapSampler.CrossSectionProfile> profiles, double seed) {
        List<State> states = List.of(new State(seed, 0.0, 0.0, List.of(), List.of()));
        for (RenderedHeatmapSampler.CrossSectionProfile profile : profiles) {
            if (profile.peaks().isEmpty()) {
                List<State> nextStates = new ArrayList<>();
                for (State previous : states) {
                    nextStates.add(append(previous, new RenderedHeatmapSampler.CrossSectionPeak(previous.offset(), 0.0)));
                }
                states = prune(nextStates);
                continue;
            }
            List<RenderedHeatmapSampler.CrossSectionPeak> peaks = profile.peaks();
            List<State> nextStates = new ArrayList<>();
            for (RenderedHeatmapSampler.CrossSectionPeak peak : peaks) {
                for (State previous : states) {
                    nextStates.add(append(previous, peak));
                }
            }
            states = prune(nextStates);
        }
        return states.stream()
            .max(Comparator.comparingDouble(State::score))
            .orElseThrow(() -> new IllegalStateException("No ridge path state was produced."));
    }

    private State append(State previous, RenderedHeatmapSampler.CrossSectionPeak peak) {
        double delta = peak.offsetPx() - previous.offset();
        double acceleration = delta - previous.delta();
        double supportBonus = Math.min(0.22, peak.supportWidthPx() / 80.0);
        double centerBonus = peak.syntheticCenter() ? 0.10 : 0.0;
        double evidence = peak.intensity() * (2.6 + supportBonus + centerBonus);
        double jump = Math.abs(delta);
        double continuityPenalty = jump * (peak.intensity() >= 0.30 ? 0.17 : 0.10);
        double curvaturePenalty = Math.abs(acceleration) * 0.16;
        double modeJumpPenalty = jump > 10.0 && peak.intensity() < 0.70 ? (jump - 10.0) * 0.20 : 0.0;
        List<Double> offsets = new ArrayList<>(previous.offsets());
        List<Double> intensities = new ArrayList<>(previous.intensities());
        offsets.add(peak.offsetPx());
        intensities.add(peak.intensity());
        return new State(
            peak.offsetPx(),
            delta,
            previous.score() + evidence - continuityPenalty - curvaturePenalty - modeJumpPenalty,
            offsets,
            intensities
        );
    }

    private List<Double> collectSeedOffsets(List<RenderedHeatmapSampler.CrossSectionProfile> profiles) {
        Map<Integer, Double> clusters = new LinkedHashMap<>();
        for (RenderedHeatmapSampler.CrossSectionProfile profile : profiles) {
            for (RenderedHeatmapSampler.CrossSectionPeak peak : profile.peaks()) {
                if (peak.intensity() < 0.20) {
                    continue;
                }
                int bucket = (int) Math.round(peak.offsetPx() / 4.0);
                clusters.merge(bucket, peak.intensity() * (1.0 + Math.min(0.35, peak.supportWidthPx() / 60.0)), Double::sum);
            }
        }
        if (clusters.isEmpty()) {
            return List.of(0.0);
        }
        return clusters.entrySet().stream()
            .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
            .limit(MAX_SEEDS)
            .map(entry -> entry.getKey() * 4.0)
            .toList();
    }

    private List<State> prune(List<State> states) {
        return states.stream()
            .sorted(Comparator.comparingDouble(State::score).reversed())
            .limit(MAX_STATES_PER_PROFILE)
            .toList();
    }

    private List<CenterlineCandidate> deduplicate(List<CenterlineCandidate> candidates) {
        List<CenterlineCandidate> distinct = new ArrayList<>();
        for (CenterlineCandidate candidate : candidates) {
            boolean duplicate = distinct.stream().anyMatch(existing -> averageOffsetDistance(existing, candidate) < 4.0);
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
                double previous = current.get(i - 1);
                double center = current.get(i);
                double following = current.get(i + 1);
                double secondDifference = center - (previous + following) / 2.0;
                double previousDelta = center - previous;
                double nextDelta = following - center;
                boolean alternating = Math.signum(previousDelta) != Math.signum(nextDelta)
                    && Math.abs(previousDelta) + Math.abs(nextDelta) >= 3.0;
                double smoothing = Math.max(0.0, Math.min(0.70, 0.58 - intensity * 0.48));
                if (!alternating && Math.abs(secondDifference) < 5.0) {
                    smoothing *= 0.25;
                }
                if (smoothing <= 0.01) {
                    continue;
                }
                double neighborAverage = (previous + following) / 2.0;
                next.set(i, center * (1.0 - smoothing) + neighborAverage * smoothing);
            }
            current = next;
        }
        return current;
    }

    private double stabilityBonus(List<Double> offsets, List<Double> intensities) {
        if (offsets.size() < 3) {
            return 0.0;
        }
        double support = intensities.stream().mapToDouble(Double::doubleValue).sum();
        double oscillation = 0.0;
        double longJump = 0.0;
        for (int i = 1; i < offsets.size() - 1; i++) {
            double previousDelta = offsets.get(i) - offsets.get(i - 1);
            double nextDelta = offsets.get(i + 1) - offsets.get(i);
            if (Math.signum(previousDelta) != Math.signum(nextDelta)) {
                oscillation += Math.min(12.0, Math.abs(previousDelta) + Math.abs(nextDelta));
            }
            longJump += Math.max(0.0, Math.abs(previousDelta) - 9.0);
        }
        return support * 0.18 - oscillation * 0.08 - longJump * 0.12;
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
