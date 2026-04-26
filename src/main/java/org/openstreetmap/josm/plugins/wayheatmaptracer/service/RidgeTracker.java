package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;

public final class RidgeTracker {
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
            candidates.add(new CenterlineCandidate("ridge-" + candidateIndex++, state.score(), points, smoothedOffsets));
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
        double evidence = peak.intensity() * 2.5;
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

    private record State(
        double offset,
        double delta,
        double score,
        List<Double> offsets,
        List<Double> intensities
    ) {
    }
}
