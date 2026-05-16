package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.awt.image.BufferedImage;
import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler;

/**
 * Complete result of one alignment attempt, including preview geometry and debug payloads.
 *
 * @param selection selected way segment that was aligned
 * @param capturedHeatmap rendered heatmap raster used by visible-layer sampling, or {@code null} for source-tile sampling
 * @param candidates ranked ridge candidates available for preview
 * @param sourcePolyline original selected geometry in projected coordinates
 * @param previewPolyline default candidate geometry in projected coordinates
 * @param nodeMoves existing-node moves that would be applied in move-node mode
 * @param diagnostics redacted logs and CSV/JSON diagnostics for the attempt
 * @param tileMosaics managed source-tile mosaics sampled or exported for the attempt
 */
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
