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
            double previous = seed;
            double previousDelta = 0.0;
            double score = 0.0;
            List<RenderedHeatmapSampler.CrossSectionProfile> chosenProfiles = new ArrayList<>();
            List<Double> offsets = new ArrayList<>();
            List<Double> intensities = new ArrayList<>();

            for (RenderedHeatmapSampler.CrossSectionProfile profile : profiles) {
                final double referenceOffset = previous;
                final double referenceDelta = previousDelta;
                RenderedHeatmapSampler.CrossSectionPeak bestPeak = profile.peaks().stream()
                    .max(Comparator.comparingDouble(peak -> peakScore(peak, referenceOffset, referenceDelta)))
                    .orElse(null);

                double offset = bestPeak != null ? bestPeak.offsetPx() : previous;
                double intensity = bestPeak != null ? bestPeak.intensity() : 0.0;
                double delta = offset - previous;
                chosenProfiles.add(profile);
                offsets.add(offset);
                intensities.add(intensity);
                score += intensity - Math.abs(delta) * 0.020 - Math.abs(delta - previousDelta) * 0.010;
                previous = offset;
                previousDelta = delta;
            }

            List<Double> smoothedOffsets = smoothOffsets(offsets, intensities);
            List<Point2D.Double> points = new ArrayList<>();
            for (int i = 0; i < chosenProfiles.size(); i++) {
                RenderedHeatmapSampler.CrossSectionProfile profile = chosenProfiles.get(i);
                double offset = smoothedOffsets.get(i);
                points.add(new Point2D.Double(
                    profile.anchorScreen().x + profile.normalScreen().x * offset,
                    profile.anchorScreen().y + profile.normalScreen().y * offset
                ));
            }
            candidates.add(new CenterlineCandidate("ridge-" + candidateIndex++, score, points, offsets));
        }

        return deduplicate(candidates).stream()
            .sorted(Comparator.comparingDouble(CenterlineCandidate::score).reversed())
            .toList();
    }

    private double peakScore(RenderedHeatmapSampler.CrossSectionPeak peak, double referenceOffset, double referenceDelta) {
        double delta = peak.offsetPx() - referenceOffset;
        return peak.intensity() * 2.5
            - Math.abs(delta) * 0.16
            - Math.abs(delta - referenceDelta) * 0.08;
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
}
