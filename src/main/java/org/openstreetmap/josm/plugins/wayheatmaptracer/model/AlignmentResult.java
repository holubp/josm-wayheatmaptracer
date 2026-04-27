package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.awt.image.BufferedImage;
import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler;

public record AlignmentResult(
    SelectionContext selection,
    BufferedImage capturedHeatmap,
    List<CenterlineCandidate> candidates,
    List<EastNorth> sourcePolyline,
    List<EastNorth> previewPolyline,
    List<NodeMove> nodeMoves,
    AlignmentDiagnostics diagnostics,
    TileHeatmapSampler.TileMosaicSet tileMosaics
) {
}
