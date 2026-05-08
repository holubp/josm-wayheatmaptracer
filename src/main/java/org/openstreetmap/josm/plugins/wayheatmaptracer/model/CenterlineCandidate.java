package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.awt.geom.Point2D;
import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;

public record CenterlineCandidate(
    String id,
    double score,
    List<Point2D.Double> screenPoints,
    List<Double> offsetsPx,
    List<EastNorth> eastNorthPoints,
    CandidateEvidence evidence,
    List<String> safetyWarnings
) {
    public CenterlineCandidate {
        screenPoints = screenPoints == null ? List.of() : List.copyOf(screenPoints);
        offsetsPx = offsetsPx == null ? List.of() : List.copyOf(offsetsPx);
        eastNorthPoints = eastNorthPoints == null ? List.of() : List.copyOf(eastNorthPoints);
        evidence = evidence == null ? CandidateEvidence.empty() : evidence;
        safetyWarnings = safetyWarnings == null ? List.of() : List.copyOf(safetyWarnings);
    }

    public CenterlineCandidate(String id, double score, List<Point2D.Double> screenPoints, List<Double> offsetsPx) {
        this(id, score, screenPoints, offsetsPx, List.of(), CandidateEvidence.empty(), List.of());
    }

    public CenterlineCandidate withId(String newId) {
        return new CenterlineCandidate(newId, score, screenPoints, offsetsPx, eastNorthPoints, evidence, safetyWarnings);
    }

    public CenterlineCandidate withScore(double newScore) {
        return new CenterlineCandidate(id, newScore, screenPoints, offsetsPx, eastNorthPoints, evidence, safetyWarnings);
    }

    public CenterlineCandidate withEastNorthPoints(List<EastNorth> points) {
        return new CenterlineCandidate(id, score, screenPoints, offsetsPx, points, evidence, safetyWarnings);
    }

    public CenterlineCandidate withEvidence(CandidateEvidence newEvidence) {
        return new CenterlineCandidate(id, score, screenPoints, offsetsPx, eastNorthPoints, newEvidence, safetyWarnings);
    }

    public CenterlineCandidate withSafetyWarnings(List<String> warnings) {
        return new CenterlineCandidate(id, score, screenPoints, offsetsPx, eastNorthPoints, evidence, warnings);
    }

    public String displayName() {
        String normalized = id.replace("-mapped-parallel", " mapped parallel");
        String[] parts = normalized.split("/");
        StringBuilder label = new StringBuilder();
        int index = 0;
        while (index < parts.length && parts[index].startsWith("refined-")) {
            index++;
        }
        while (index < parts.length && parts[index].startsWith("consensus-")) {
            String modes = consensusModesLabel();
            if (modes.isBlank()) {
                String count = parts[index].substring("consensus-".length());
                label.append("Consensus ").append(count).append(" detectors");
            } else {
                label.append("Consensus: ").append(modes);
            }
            label.append(" - ");
            index++;
        }
        if (index < parts.length && "consensus".equals(parts[index])) {
            index++;
        }
        if (index < parts.length && parts[index].startsWith("ridge-") && label.toString().startsWith("Consensus")) {
            label.append(parts[index].replace('-', ' '));
            index++;
        } else if (index < parts.length) {
            label.append(capitalize(parts[index])).append(" detector");
            index++;
        }
        if (index < parts.length) {
            if (!label.isEmpty() && !label.toString().endsWith(" - ")) {
                label.append(" - ");
            }
            label.append(parts[index].replace('-', ' '));
            index++;
        }
        while (index < parts.length) {
            label.append(" - ").append(parts[index].replace('-', ' '));
            index++;
        }
        label.append(" - ").append(confidenceLabel());
        if (!safetyWarnings.isEmpty()) {
            label.append(" - ").append(String.join(", ", safetyWarnings));
        }
        return label.toString();
    }

    @Override
    public String toString() {
        return displayName();
    }

    private String confidenceLabel() {
        if (!evidence.hasSignal()) {
            return "no signal";
        }
        double support = evidence.supportRatio();
        double quality =
            0.34 * clamp01(support)
            + 0.28 * clamp01(evidence.signalToNoise() / 0.24)
            + 0.22 * clamp01(evidence.meanIntensity() / 0.55)
            + 0.10 * clamp01(evidence.meanGradientStrength() / 0.25)
            + 0.06 * clamp01(evidence.longitudinalStability())
            + 0.10 * (1.0 - clamp01(evidence.ambiguity() / 1.60));
        quality -= 0.16 * clamp01(evidence.maxConsecutiveEmptyProfiles() / 20.0);
        if (quality >= 0.62 && support >= 0.45 && evidence.maxConsecutiveEmptyProfiles() <= 14) {
            return "strong";
        }
        if (quality >= 0.42 && support >= 0.28 && evidence.maxConsecutiveEmptyProfiles() <= 24) {
            return "usable";
        }
        if (quality >= 0.22 || support >= 0.12 || evidence.signalToNoise() >= 0.03) {
            return "weak";
        }
        return "very weak";
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + value.substring(1);
    }

    private String consensusModesLabel() {
        if (evidence.consensusModes().isEmpty()) {
            return "";
        }
        return String.join(" + ", evidence.consensusModes());
    }
}
