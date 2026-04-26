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

    @Override
    public String toString() {
        return id + " (" + String.format("%.2f", score) + ")";
    }
}
