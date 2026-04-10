package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentDiagnostics;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NodeMove;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;

public final class AlignmentService {
    private final RenderedHeatmapSampler sampler = new RenderedHeatmapSampler();
    private final RidgeTracker ridgeTracker = new RidgeTracker();
    private final PathOptimizer optimizer = new PathOptimizer();
    private final GeometryPostProcessor postProcessor = new GeometryPostProcessor();

    public AlignmentResult align(SelectionContext selection, ImageryLayer imageryLayer, MapView mapView) {
        ManagedHeatmapConfig config = PluginPreferences.load();
        PluginLog.verbose("Starting alignment for way %d segment [%d..%d], nodes=%d, fixed=%d, layer='%s'.",
            selection.way().getUniqueId(),
            selection.startIndex(),
            selection.endIndex(),
            selection.segmentNodes().size(),
            selection.fixedNodes().size(),
            imageryLayer.getName());
        PluginLog.verbose("Alignment mode=%s simplify=%s tolerance=%.2f.",
            config.alignmentMode(), config.simplifyEnabled(), config.simplifyTolerancePx());

        long t0 = System.nanoTime();
        BufferedImage raster = sampler.captureLayer(imageryLayer, mapView);
        long t1 = System.nanoTime();

        List<EastNorth> sourcePolyline = toEastNorth(selection.segmentNodes());
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles =
            sampler.sampleProfiles(raster, mapView, sourcePolyline, config.crossSectionHalfWidthPx(), config.crossSectionStepPx(), config.color());

        List<CenterlineCandidate> candidates = ridgeTracker.track(profiles);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No stable ridge candidate was detected in the sampled heatmap.");
        }
        long t2 = System.nanoTime();

        CenterlineCandidate primary = candidates.get(0);
        List<EastNorth> preview = optimize(selection, sourcePolyline, primary, config, mapView);
        List<NodeMove> nodeMoves = interpolateMoves(selection, preview);
        long t3 = System.nanoTime();

        AlignmentDiagnostics diagnostics = new AlignmentDiagnostics(
            imageryLayer.getName(),
            candidates.size(),
            nodeMoves.size(),
            millisBetween(t0, t1),
            millisBetween(t1, t2),
            millisBetween(t2, t3),
            config.toRedactedJson(),
            selectionToJson(selection)
        );

        PluginLog.verbose("Alignment finished: raster=%d ms ridge=%d ms optimize=%d ms candidates=%d movableNodes=%d.",
            millisBetween(t0, t1), millisBetween(t1, t2), millisBetween(t2, t3), candidates.size(), nodeMoves.size());
        if (config.debug()) {
            for (CenterlineCandidate candidate : candidates) {
                PluginLog.debug("Candidate %s score=%.3f points=%d offsets(first10)=%s",
                    candidate.id(), candidate.score(), candidate.screenPoints().size(),
                    candidate.offsetsPx().stream().limit(10).map(offset -> String.format("%.1f", offset)).toList());
            }
        }

        return new AlignmentResult(selection, raster, candidates, sourcePolyline, preview, nodeMoves, diagnostics);
    }

    public AlignmentResult applyCandidate(AlignmentResult base, CenterlineCandidate candidate) {
        ManagedHeatmapConfig config = PluginPreferences.load();
        List<EastNorth> preview = optimize(base.selection(), base.sourcePolyline(), candidate, config, null);
        List<NodeMove> nodeMoves = interpolateMoves(base.selection(), preview);
        PluginLog.verbose("Using candidate %s for preview/apply: previewPoints=%d movableNodes=%d.",
            candidate.id(), preview.size(), nodeMoves.size());
        if (config.debug()) {
            for (int i = 0; i < nodeMoves.size(); i++) {
                NodeMove move = nodeMoves.get(i);
                EastNorth source = move.node().getEastNorth(ProjectionRegistry.getProjection());
                PluginLog.debug("Move[%d] node=%d from=(%.3f,%.3f) to=(%.3f,%.3f) delta=(%.3f,%.3f)",
                    i,
                    move.node().getUniqueId(),
                    source.east(), source.north(),
                    move.target().east(), move.target().north(),
                    move.target().east() - source.east(),
                    move.target().north() - source.north());
            }
        }
        return new AlignmentResult(
            base.selection(),
            base.capturedHeatmap(),
            base.candidates(),
            base.sourcePolyline(),
            preview,
            nodeMoves,
            base.diagnostics()
        );
    }

    private List<EastNorth> toEastNorth(List<Node> nodes) {
        List<EastNorth> result = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            result.add(node.getEastNorth(ProjectionRegistry.getProjection()));
        }
        return result;
    }

    private List<NodeMove> interpolateMoves(SelectionContext selection, List<EastNorth> preview) {
        List<NodeMove> moves = new ArrayList<>();
        if (preview.size() < 2) {
            return moves;
        }

        List<EastNorth> samples;
        if (preview.size() == selection.segmentNodes().size()) {
            samples = preview;
            PluginLog.debug("Applying node moves directly from preview points without resampling.");
        } else {
            samples = PolylineMath.resampleByCount(preview, selection.segmentNodes().size());
            PluginLog.debug("Resampled preview from %d to %d points for node move interpolation.",
                preview.size(), selection.segmentNodes().size());
        }
        List<Node> segmentNodes = selection.segmentNodes();

        for (int i = 1; i < segmentNodes.size() - 1; i++) {
            Node node = segmentNodes.get(i);
            if (selection.fixedNodes().contains(node)) {
                continue;
            }
            moves.add(new NodeMove(node, samples.get(i)));
        }
        return moves;
    }

    private List<EastNorth> optimize(
        SelectionContext selection,
        List<EastNorth> sourcePolyline,
        CenterlineCandidate candidate,
        ManagedHeatmapConfig config,
        MapView mapView
    ) {
        MapView effectiveMapView = mapView != null ? mapView : org.openstreetmap.josm.gui.MainApplication.getMap().mapView;
        List<EastNorth> candidateCenterline = optimizer.projectCandidate(candidate, effectiveMapView);
        PluginLog.debug("Candidate %s projected centerline point count=%d.", candidate.id(), candidateCenterline.size());
        List<EastNorth> working;
        if (config.alignmentMode() == org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode.MOVE_EXISTING_NODES) {
            working = candidateCenterline;
            PluginLog.verbose("Move-existing-nodes mode uses direct centerline projection without dense path optimization.");
            if (config.simplifyEnabled()) {
                PluginLog.verbose("Simplification checkbox is ignored in Move Existing Nodes mode.");
            }
        } else {
            working = candidateCenterline;
            PluginLog.verbose("Precise-shape mode rebuilds the segment from the traced centerline.");
            if (config.simplifyEnabled()) {
                List<EastNorth> simplified = postProcessor.simplify(working, config.simplifyTolerancePx());
                PluginLog.verbose("Simplification enabled: %d -> %d points with tolerance %.2f.",
                    working.size(), simplified.size(), config.simplifyTolerancePx());
                working = simplified;
            } else {
                PluginLog.verbose("Simplification disabled; using raw traced centerline.");
            }
        }
        return switch (config.alignmentMode()) {
            case MOVE_EXISTING_NODES -> moveExistingNodesPreview(selection, sourcePolyline, working);
            case PRECISE_SHAPE -> preciseShapePreview(selection, sourcePolyline, working);
        };
    }

    private List<EastNorth> moveExistingNodesPreview(SelectionContext selection, List<EastNorth> sourcePolyline, List<EastNorth> working) {
        List<Double> sourceFractions = fractionsForSegment(sourcePolyline);
        List<Double> centerlineFractions = fractionsForSegment(working);
        List<EastNorth> result = new ArrayList<>(selection.segmentNodes().size());
        for (int i = 0; i < selection.segmentNodes().size(); i++) {
            EastNorth sourcePoint = sourcePolyline.get(i);
            if (selection.fixedNodes().contains(selection.segmentNodes().get(i))) {
                result.add(sourcePoint);
                PluginLog.debug("MoveMode[%d] node=%d fixed -> stays at source=(%.3f,%.3f)",
                    i, selection.segmentNodes().get(i).getUniqueId(), sourcePoint.east(), sourcePoint.north());
                continue;
            }
            double fraction = sourceFractions.get(i);
            EastNorth projected = interpolateAtFraction(working, centerlineFractions, fraction);
            result.add(projected);
            PluginLog.debug("MoveMode[%d] node=%d fraction=%.5f source=(%.3f,%.3f) centerline=(%.3f,%.3f)",
                i,
                selection.segmentNodes().get(i).getUniqueId(),
                fraction,
                sourcePoint.east(), sourcePoint.north(),
                projected.east(), projected.north());
        }
        return result;
    }

    private List<EastNorth> preciseShapePreview(SelectionContext selection, List<EastNorth> sourcePolyline, List<EastNorth> working) {
        List<Integer> fixedIndices = fixedIndices(selection);
        List<Double> sourceFractions = fractionsForSegment(sourcePolyline);
        List<Double> workingFractions = fractionsForSegment(working);
        List<EastNorth> result = new ArrayList<>();

        int intervalCount = fixedIndices.size() - 1;
        for (int interval = 0; interval < intervalCount; interval++) {
            int startIndex = fixedIndices.get(interval);
            int endIndex = fixedIndices.get(interval + 1);
            double startFraction = sourceFractions.get(startIndex);
            double endFraction = sourceFractions.get(endIndex);

            List<EastNorth> section = new ArrayList<>();
            section.add(sourcePolyline.get(startIndex));
            for (int i = 0; i < working.size(); i++) {
                double fraction = workingFractions.get(i);
                if (fraction > startFraction + 1e-9 && fraction < endFraction - 1e-9) {
                    section.add(working.get(i));
                }
            }
            section.add(sourcePolyline.get(endIndex));
            if (!result.isEmpty()) {
                section = new ArrayList<>(section.subList(1, section.size()));
            }
            result.addAll(section);
        }
        PluginLog.verbose("Precise-shape preview prepared with %d points across %d fixed-anchor intervals.", result.size(), intervalCount);
        return result;
    }

    private void applyFixedNodeAnchors(SelectionContext selection, List<EastNorth> sourcePolyline, List<EastNorth> preview) {
        for (int i = 0; i < selection.segmentNodes().size(); i++) {
            if (selection.fixedNodes().contains(selection.segmentNodes().get(i))) {
                preview.set(i, sourcePolyline.get(i));
            }
        }
    }

    private List<Integer> fixedIndices(SelectionContext selection) {
        List<Integer> indices = new ArrayList<>();
        Set<Node> fixedNodes = selection.fixedNodes();
        for (int i = 0; i < selection.segmentNodes().size(); i++) {
            if (fixedNodes.contains(selection.segmentNodes().get(i))) {
                indices.add(i);
            }
        }
        if (indices.isEmpty() || indices.get(0) != 0) {
            indices.add(0, 0);
        }
        int last = selection.segmentNodes().size() - 1;
        if (indices.get(indices.size() - 1) != last) {
            indices.add(last);
        }
        return indices;
    }

    private List<Double> fractionsForSegment(List<EastNorth> polyline) {
        List<Double> fractions = new ArrayList<>(polyline.size());
        double total = 0.0;
        double[] cumulative = new double[polyline.size()];
        cumulative[0] = 0.0;
        for (int i = 1; i < polyline.size(); i++) {
            total += polyline.get(i - 1).distance(polyline.get(i));
            cumulative[i] = total;
        }
        if (total == 0.0) {
            for (int i = 0; i < polyline.size(); i++) {
                fractions.add(i == polyline.size() - 1 ? 1.0 : 0.0);
            }
            return fractions;
        }
        for (double value : cumulative) {
            fractions.add(value / total);
        }
        return fractions;
    }

    private List<EastNorth> sampleBetweenFractions(List<EastNorth> polyline, double startFraction, double endFraction, int count) {
        List<Double> fractions = fractionsForSegment(polyline);
        List<EastNorth> samples = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double local = count == 1 ? 0.0 : i / (double) (count - 1);
            double targetFraction = startFraction + (endFraction - startFraction) * local;
            samples.add(interpolateAtFraction(polyline, fractions, targetFraction));
        }
        return samples;
    }

    private EastNorth interpolateAtFraction(List<EastNorth> polyline, List<Double> fractions, double targetFraction) {
        if (targetFraction <= 0.0) {
            return polyline.get(0);
        }
        if (targetFraction >= 1.0) {
            return polyline.get(polyline.size() - 1);
        }
        for (int i = 1; i < polyline.size(); i++) {
            double left = fractions.get(i - 1);
            double right = fractions.get(i);
            if (targetFraction <= right) {
                double span = right - left;
                double t = span == 0.0 ? 0.0 : (targetFraction - left) / span;
                EastNorth start = polyline.get(i - 1);
                EastNorth end = polyline.get(i);
                return new EastNorth(
                    start.east() + (end.east() - start.east()) * t,
                    start.north() + (end.north() - start.north()) * t
                );
            }
        }
        return polyline.get(polyline.size() - 1);
    }

    private long millisBetween(long startNs, long endNs) {
        return Math.round((endNs - startNs) / 1_000_000.0);
    }

    private String selectionToJson(SelectionContext selection) {
        return "{"
            + "\"wayId\":" + selection.way().getUniqueId() + ','
            + "\"startIndex\":" + selection.startIndex() + ','
            + "\"endIndex\":" + selection.endIndex() + ','
            + "\"segmentNodeCount\":" + selection.segmentNodes().size() + ','
            + "\"fixedNodeCount\":" + selection.fixedNodes().size()
            + "}";
    }
}
