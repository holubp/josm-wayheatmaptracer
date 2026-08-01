package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

/**
 * Forms parent-corridor hypotheses from persistent elementary tracks without hiding child alternatives.
 */
public final class CorridorGrouping {
    static final double COMBINED_VALLEY_RATIO = 0.65;
    static final double SEPARATE_VALLEY_RATIO = 0.40;
    private static final int MIN_PERSISTENT_PROFILES = 5;
    private static final double MIN_COMMON_SUPPORT_RATIO = 0.60;

    /**
     * Creates a stateless longitudinal grouping service.
     */
    public CorridorGrouping() {
        // Stateless grouping service.
    }

    /**
     * Groups adjacent elementary tracks using persistent valley and envelope evidence.
     *
     * @param elementaryTracks elementary longitudinal tracks
     * @param profiles source corridor profiles
     * @return child tracks plus any supported parent hypotheses and decision diagnostics
     */
    public GroupingResult group(List<CorridorTrack> elementaryTracks, List<CorridorProfile> profiles) {
        List<CorridorTrack> sorted = elementaryTracks.stream()
            .sorted(Comparator.comparingDouble(this::meanOffset))
            .toList();
        List<CorridorTrack> result = new ArrayList<>(sorted);
        List<GroupingDecision> decisions = new ArrayList<>();
        int parentIndex = 1;
        for (int i = 0; i + 1 < sorted.size(); i++) {
            CorridorTrack left = sorted.get(i);
            CorridorTrack right = sorted.get(i + 1);
            PairEvidence evidence = pairEvidence(left, right, profiles);
            String decision = classify(evidence);
            decisions.add(new GroupingDecision(left.id(), right.id(), evidence.commonProfiles(),
                evidence.commonSupportRatio(), evidence.meanValleyRatio(), evidence.commonEnvelopeRatio(), decision));
            if ("combined".equals(decision) || "ambiguous".equals(decision)) {
                CorridorTrack parent = parentTrack("parent-" + parentIndex++, left, right, profiles, evidence, decision);
                if (!parent.points().isEmpty()) {
                    result.add(parent);
                }
            }
        }
        return new GroupingResult(result, decisions);
    }

    private PairEvidence pairEvidence(CorridorTrack left, CorridorTrack right, List<CorridorProfile> profiles) {
        double valleySum = 0.0;
        int common = 0;
        int commonEnvelope = 0;
        for (Map.Entry<Integer, CorridorTrackPoint> entry : left.points().entrySet()) {
            CorridorTrackPoint rightPoint = right.points().get(entry.getKey());
            if (rightPoint == null) {
                continue;
            }
            CorridorBand leftBand = entry.getValue().band();
            CorridorBand rightBand = rightPoint.band();
            valleySum += valleyRatio(profiles.get(entry.getKey()), leftBand, rightBand);
            if (envelopesTouch(leftBand, rightBand)) {
                commonEnvelope++;
            }
            common++;
        }
        int minimumSupport = Math.max(1, Math.min(left.points().size(), right.points().size()));
        return new PairEvidence(common,
            (double) common / minimumSupport,
            common == 0 ? 0.0 : valleySum / common,
            common == 0 ? 0.0 : (double) commonEnvelope / common);
    }

    private String classify(PairEvidence evidence) {
        boolean persistent = evidence.commonProfiles() >= MIN_PERSISTENT_PROFILES
            && evidence.commonSupportRatio() >= MIN_COMMON_SUPPORT_RATIO
            && evidence.commonEnvelopeRatio() >= MIN_COMMON_SUPPORT_RATIO;
        if (!persistent || evidence.meanValleyRatio() <= SEPARATE_VALLEY_RATIO) {
            return "separate";
        }
        if (evidence.meanValleyRatio() >= COMBINED_VALLEY_RATIO) {
            return "combined";
        }
        return "ambiguous";
    }

    private CorridorTrack parentTrack(
        String id,
        CorridorTrack left,
        CorridorTrack right,
        List<CorridorProfile> profiles,
        PairEvidence evidence,
        String decision
    ) {
        Map<Integer, CorridorTrackPoint> points = new LinkedHashMap<>();
        for (Map.Entry<Integer, CorridorTrackPoint> entry : left.points().entrySet()) {
            CorridorTrackPoint rightPoint = right.points().get(entry.getKey());
            if (rightPoint == null) {
                continue;
            }
            CorridorBand leftBand = entry.getValue().band();
            CorridorBand rightBand = rightPoint.band();
            double shoulderMin = Math.min(leftBand.shoulderMinPx(), rightBand.shoulderMinPx());
            double shoulderMax = Math.max(leftBand.shoulderMaxPx(), rightBand.shoulderMaxPx());
            double coreMin = Math.min(leftBand.coreMinPx(), rightBand.coreMinPx());
            double coreMax = Math.max(leftBand.coreMaxPx(), rightBand.coreMaxPx());
            double outerCenter = (shoulderMin + shoulderMax) / 2.0;
            double coreCenter = (coreMin + coreMax) / 2.0;
            double center = 0.35 * outerCenter + 0.65 * coreCenter;
            double valley = valleyRatio(profiles.get(entry.getKey()), leftBand, rightBand);
            CorridorBand parentBand = new CorridorBand(
                id + "-profile-" + entry.getKey(),
                center,
                shoulderMin,
                shoulderMax,
                coreMin,
                coreMax,
                List.of(leftBand.centerOffsetPx(), rightBand.centerOffsetPx(), outerCenter, coreCenter),
                Math.max(leftBand.peakIntensity(), rightBand.peakIntensity()),
                Math.max(leftBand.noiseFloor(), rightBand.noiseFloor()),
                valley,
                (leftBand.gradientStrength() + rightBand.gradientStrength()) / 2.0,
                (leftBand.gradientBalance() + rightBand.gradientBalance()) / 2.0,
                (leftBand.scaleAgreement() + rightBand.scaleAgreement()) / 2.0,
                Math.min(leftBand.signalExistenceConfidence(), rightBand.signalExistenceConfidence()),
                (leftBand.localizationConfidence() + rightBand.localizationConfidence()) / 2.0,
                Math.max(leftBand.uncertaintyPx(), rightBand.uncertaintyPx()),
                true,
                List.of(leftBand.id(), rightBand.id())
            );
            points.put(entry.getKey(), new CorridorTrackPoint(entry.getKey(), parentBand,
                entry.getValue().bridged() || rightPoint.bridged()));
        }
        double supportRatio = profiles.isEmpty() ? 0.0 : (double) points.size() / profiles.size();
        double scoreBonus = "combined".equals(decision) ? evidence.meanValleyRatio() : 0.0;
        return new CorridorTrack(id, points, (left.score() + right.score()) / 2.0 + scoreBonus,
            supportRatio, true, List.of(left.id(), right.id()), decision);
    }

    private double valleyRatio(CorridorProfile profile, CorridorBand left, CorridorBand right) {
        double from = Math.min(left.centerOffsetPx(), right.centerOffsetPx());
        double to = Math.max(left.centerOffsetPx(), right.centerOffsetPx());
        List<IntensitySample> between = profile.source().intensitySamples().stream()
            .filter(IntensitySample::insideRaster)
            .filter(sample -> sample.offsetPx() >= from && sample.offsetPx() <= to)
            .toList();
        if (between.isEmpty()) {
            return 0.0;
        }
        double valley = between.stream().mapToDouble(IntensitySample::standardFilteredIntensity).min().orElse(0.0);
        double weakerPeak = Math.min(left.peakIntensity(), right.peakIntensity());
        return weakerPeak <= 1e-9 ? 0.0 : clamp(valley / weakerPeak);
    }

    private boolean envelopesTouch(CorridorBand left, CorridorBand right) {
        double gap = Math.max(0.0, Math.max(left.shoulderMinPx(), right.shoulderMinPx())
            - Math.min(left.shoulderMaxPx(), right.shoulderMaxPx()));
        double width = Math.max(1.0, Math.min(left.shoulderWidthPx(), right.shoulderWidthPx()));
        return gap <= width * 0.5;
    }

    private double meanOffset(CorridorTrack track) {
        return track.points().values().stream().mapToDouble(point -> point.band().centerOffsetPx()).average().orElse(0.0);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * Result of longitudinal parent-corridor interpretation.
     *
     * @param tracks elementary tracks plus parent hypotheses
     * @param decisions pairwise grouping evidence
     */
    public record GroupingResult(List<CorridorTrack> tracks, List<GroupingDecision> decisions) {
        /** Makes grouping result collections immutable. */
        public GroupingResult {
            tracks = List.copyOf(tracks);
            decisions = List.copyOf(decisions);
        }
    }

    /**
     * Persistent evidence used to classify two neighboring tracks.
     *
     * @param leftTrackId left elementary track
     * @param rightTrackId right elementary track
     * @param commonProfiles number of profiles supporting both tracks
     * @param commonSupportRatio common support relative to the shorter track
     * @param meanValleyRatio normalized valley intensity
     * @param commonEnvelopeRatio fraction whose shoulder envelopes touch
     * @param decision {@code combined}, {@code ambiguous}, or {@code separate}
     */
    public record GroupingDecision(
        String leftTrackId,
        String rightTrackId,
        int commonProfiles,
        double commonSupportRatio,
        double meanValleyRatio,
        double commonEnvelopeRatio,
        String decision
    ) {
    }

    private record PairEvidence(
        int commonProfiles,
        double commonSupportRatio,
        double meanValleyRatio,
        double commonEnvelopeRatio
    ) {
    }
}
