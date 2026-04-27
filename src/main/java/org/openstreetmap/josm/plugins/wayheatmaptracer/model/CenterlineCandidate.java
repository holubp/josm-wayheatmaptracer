package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.awt.geom.Point2D;
import java.util.List;

public record CenterlineCandidate(
    String id,
    double score,
    List<Point2D.Double> screenPoints,
    List<Double> offsetsPx
) {
    public CenterlineCandidate withId(String newId) {
        return new CenterlineCandidate(newId, score, screenPoints, offsetsPx);
    }

    public CenterlineCandidate withScore(double newScore) {
        return new CenterlineCandidate(id, newScore, screenPoints, offsetsPx);
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
        if (score >= 20.0) {
            return "strong";
        }
        if (score >= 5.0) {
            return "usable";
        }
        if (score >= -25.0) {
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
