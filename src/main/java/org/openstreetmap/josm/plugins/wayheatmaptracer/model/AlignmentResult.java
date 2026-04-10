package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.awt.image.BufferedImage;
import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;

public record AlignmentResult(
    SelectionContext selection,
    BufferedImage capturedHeatmap,
    List<CenterlineCandidate> candidates,
    List<EastNorth> sourcePolyline,
    List<EastNorth> previewPolyline,
    List<NodeMove> nodeMoves,
    AlignmentDiagnostics diagnostics
) {
}

