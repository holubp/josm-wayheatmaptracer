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
    CandidateEvidence evidence
) {
    public CenterlineCandidate(String id, double score, List<Point2D.Double> screenPoints, List<Double> offsetsPx) {
        this(id, score, screenPoints, offsetsPx, List.of(), CandidateEvidence.empty());
    }

    public CenterlineCandidate withId(String newId) {
        return new CenterlineCandidate(newId, score, screenPoints, offsetsPx, eastNorthPoints, evidence);
    }

    public CenterlineCandidate withScore(double newScore) {
        return new CenterlineCandidate(id, newScore, screenPoints, offsetsPx, eastNorthPoints, evidence);
    }

    public CenterlineCandidate withEastNorthPoints(List<EastNorth> points) {
        return new CenterlineCandidate(id, score, screenPoints, offsetsPx, points == null ? List.of() : List.copyOf(points), evidence);
    }

    public CenterlineCandidate withEvidence(CandidateEvidence newEvidence) {
        return new CenterlineCandidate(id, score, screenPoints, offsetsPx, eastNorthPoints, newEvidence == null ? CandidateEvidence.empty() : newEvidence);
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
            String count = parts[index].substring("consensus-".length());
            label.append("Consensus ").append(count).append(" detectors - ");
            index++;
        }
        if (index < parts.length) {
            label.append(capitalize(parts[index])).append(" detector");
            index++;
        }
        if (index < parts.length) {
            label.append(" - ").append(parts[index].replace('-', ' '));
            index++;
        }
        while (index < parts.length) {
            label.append(" - ").append(parts[index].replace('-', ' '));
            index++;
        }
        label.append(" - ").append(confidenceLabel());
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
        if (score >= 20.0 && support >= 0.60 && evidence.maxConsecutiveEmptyProfiles() <= 8
                && evidence.signalToNoise() >= 0.16) {
            return "strong";
        }
        if (score >= 5.0 && support >= 0.40 && evidence.maxConsecutiveEmptyProfiles() <= 16) {
            return "usable";
        }
        if (score >= -25.0 || support >= 0.15) {
            return "weak";
        }
        return "very weak";
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + value.substring(1);
    }
}
