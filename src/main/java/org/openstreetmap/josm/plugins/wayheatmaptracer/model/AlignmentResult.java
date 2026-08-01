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
 * @param detectorAttempts one terminal entry for every requested detector mapping
 * @param applicableCandidates ranked candidates that pass signal and structural safety gates
 */
public record AlignmentResult(
    SelectionContext selection,
    BufferedImage capturedHeatmap,
    List<CenterlineCandidate> candidates,
    List<EastNorth> sourcePolyline,
    List<EastNorth> previewPolyline,
    List<NodeMove> nodeMoves,
    AlignmentDiagnostics diagnostics,
    TileHeatmapSampler.TileMosaicSet tileMosaics,
    List<DetectorAttempt> detectorAttempts,
    List<CenterlineCandidate> applicableCandidates
) {
    /**
     * Creates a legacy result whose candidate list is entirely applicable.
     *
     * @param selection selected source segment
     * @param capturedHeatmap rendered source capture, or null
     * @param candidates ranked candidates
     * @param sourcePolyline original projected geometry
     * @param previewPolyline selected preview geometry
     * @param nodeMoves existing-node move targets
     * @param diagnostics redacted alignment diagnostics
     * @param tileMosaics managed source mosaics, or null
     */
    public AlignmentResult(
        SelectionContext selection,
        BufferedImage capturedHeatmap,
        List<CenterlineCandidate> candidates,
        List<EastNorth> sourcePolyline,
        List<EastNorth> previewPolyline,
        List<NodeMove> nodeMoves,
        AlignmentDiagnostics diagnostics,
        TileHeatmapSampler.TileMosaicSet tileMosaics
    ) {
        this(selection, capturedHeatmap, candidates, sourcePolyline, previewPolyline, nodeMoves, diagnostics,
            tileMosaics, List.of(), candidates);
    }

    /** Makes diagnostic and applicable collections immutable. */
    public AlignmentResult {
        candidates = List.copyOf(candidates);
        sourcePolyline = List.copyOf(sourcePolyline);
        previewPolyline = List.copyOf(previewPolyline);
        nodeMoves = List.copyOf(nodeMoves);
        detectorAttempts = List.copyOf(detectorAttempts);
        applicableCandidates = List.copyOf(applicableCandidates);
    }
}
