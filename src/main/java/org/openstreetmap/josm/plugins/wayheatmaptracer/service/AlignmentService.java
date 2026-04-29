package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.AbstractTileSourceLayer;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentDiagnostics;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NodeMove;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;

public final class AlignmentService {
    private static final List<String> ALL_COLOR_MODES = List.of("hot", "blue", "bluered", "purple", "gray", "dual");
    private static final double MAX_UNSUPPORTED_FIXED_TURN_DEGREES = 75.0;

    private final RenderedHeatmapSampler sampler = new RenderedHeatmapSampler();
    private final RidgeTracker ridgeTracker = new RidgeTracker();
    private final PathOptimizer optimizer = new PathOptimizer();
    private final GeometryPostProcessor postProcessor = new GeometryPostProcessor();

    public AlignmentResult align(SelectionContext selection, ImageryLayer imageryLayer, MapView mapView) {
        if (imageryLayer == null) {
            throw new IllegalStateException("No visible heatmap imagery layer was resolved.");
        }
        ManagedHeatmapConfig config = PluginPreferences.load();
        PluginLog.verbose("Starting v0.2-compatible visible-layer alignment for way %d segment [%d..%d], nodes=%d, fixed=%d, layer='%s'.",
            selection.way().getUniqueId(),
            selection.startIndex(),
            selection.endIndex(),
            selection.segmentNodes().size(),
            selection.fixedNodes().size(),
            imageryLayer.getName());
        PluginLog.verbose("Alignment mode=%s simplify=%s tolerance=%.2f multiColor=%s renderedLayerZoom=%s.",
            config.alignmentMode(), config.simplifyEnabled(), config.simplifyTolerancePx(),
            config.multiColorDetection(), renderedZoomSummary(imageryLayer));

        long t0 = System.nanoTime();
        BufferedImage raster = sampler.captureLayer(imageryLayer, mapView);
        long t1 = System.nanoTime();

        List<EastNorth> sourcePolyline = toEastNorth(selection.segmentNodes());
        List<String> colorModes = detectionColorModes(config);
        List<CenterlineCandidate> candidates = detectCandidates(raster, mapView, sourcePolyline, config, colorModes);
        long t2 = System.nanoTime();
        if (candidates.isEmpty()) {
            AlignmentResult partial = partialResult(selection, raster, sourcePolyline, imageryLayer, config, colorModes, t0, t1, t2, t2);
            throw new AlignmentFailureException("No stable ridge candidate was detected in the sampled heatmap.", partial);
        }

        CenterlineCandidate primary = candidates.get(0);
        List<EastNorth> preview = optimize(selection, sourcePolyline, primary, config, mapView);
        List<NodeMove> nodeMoves = interpolateMoves(selection, preview);
        long t3 = System.nanoTime();

        AlignmentDiagnostics diagnostics = diagnostics(
            imageryLayer,
            candidates.size(),
            nodeMoves.size(),
            t0,
            t1,
            t2,
            t3,
            raster,
            config,
            selection,
            colorModes,
            candidates
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

        return new AlignmentResult(selection, raster, candidates, sourcePolyline, preview, nodeMoves, diagnostics, null);
    }

    private AlignmentResult partialResult(
        SelectionContext selection,
        BufferedImage raster,
        List<EastNorth> sourcePolyline,
        ImageryLayer imageryLayer,
        ManagedHeatmapConfig config,
        List<String> colorModes,
        long t0,
        long t1,
        long t2,
        long t3
    ) {
        return new AlignmentResult(
            selection,
            raster,
            List.of(),
            sourcePolyline,
            sourcePolyline,
            List.of(),
            diagnostics(imageryLayer, 0, 0, t0, t1, t2, t3, raster, config, selection, colorModes, List.of()),
            null
        );
    }

    private List<CenterlineCandidate> detectCandidates(
        BufferedImage raster,
        MapView mapView,
        List<EastNorth> sourcePolyline,
        ManagedHeatmapConfig config,
        List<String> colorModes
    ) {
        List<CenterlineCandidate> candidates = new ArrayList<>();
        for (String colorMode : colorModes) {
            List<RenderedHeatmapSampler.CrossSectionProfile> profiles =
                sampler.sampleProfiles(raster, mapView, sourcePolyline, config.crossSectionHalfWidthPx(), config.crossSectionStepPx(), colorMode);
            List<CenterlineCandidate> colorCandidates = ridgeTracker.track(profiles);
            for (CenterlineCandidate candidate : colorCandidates) {
                CenterlineCandidate withMode = candidate
                    .withId(colorMode + "/" + candidate.id())
                    .withEvidence(candidate.evidence().withDetectorMode(colorMode));
                withMode = withMode.withEastNorthPoints(optimizer.projectCandidate(withMode, mapView));
                candidates.add(withMode);
            }
            PluginLog.verbose("Color mode '%s' produced %d ridge candidates.", colorMode, colorCandidates.size());
        }
        return candidates.stream()
            .sorted(java.util.Comparator.comparingDouble(CenterlineCandidate::score).reversed())
            .toList();
    }

    private List<String> detectionColorModes(ManagedHeatmapConfig config) {
        String selected = config.color() == null || config.color().isBlank()
            ? "hot"
            : config.color().trim().toLowerCase(java.util.Locale.ROOT);
        if (!config.multiColorDetection()) {
            return List.of(selected);
        }

        List<String> modes = new ArrayList<>();
        modes.add(selected);
        for (String mode : ALL_COLOR_MODES) {
            if (!modes.contains(mode)) {
                modes.add(mode);
            }
        }
        return modes;
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
            base.diagnostics(),
            base.tileMosaics()
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

        for (int i = 0; i < segmentNodes.size(); i++) {
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
        MapView effectiveMapView = mapView != null ? mapView : MainApplication.getMap().mapView;
        List<EastNorth> candidateCenterline = optimizer.projectCandidate(candidate, effectiveMapView);
        PluginLog.debug("Candidate %s projected centerline point count=%d.", candidate.id(), candidateCenterline.size());
        List<EastNorth> working;
        if (config.alignmentMode() == AlignmentMode.MOVE_EXISTING_NODES) {
            working = candidateCenterline;
            PluginLog.verbose("Move-existing-nodes mode uses direct centerline projection without dense path optimization.");
            if (config.simplifyEnabled()) {
                PluginLog.verbose("Simplification checkbox is ignored in Move Existing Nodes mode.");
            }
        } else {
            working = candidateCenterline;
            PluginLog.verbose("Precise-shape mode rebuilds the segment from the traced centerline.");
            if (config.simplifyEnabled() && config.adjustJunctionNodes()) {
                PluginLog.verbose("Simplification is ignored while junction/end node adjustment is enabled.");
            } else if (config.simplifyEnabled()) {
                List<EastNorth> simplified = postProcessor.simplify(working, config.simplifyTolerancePx());
                PluginLog.verbose("Simplification enabled: %d -> %d points with tolerance %.2f.",
                    working.size(), simplified.size(), config.simplifyTolerancePx());
                working = simplified;
            } else {
                PluginLog.verbose("Simplification disabled; using raw traced centerline.");
            }
        }
        List<EastNorth> preview = switch (config.alignmentMode()) {
            case MOVE_EXISTING_NODES -> moveExistingNodesPreview(selection, sourcePolyline, working);
            case PRECISE_SHAPE -> preciseShapePreview(selection, sourcePolyline, working);
        };
        return guardFixedAnchorTurns(selection, sourcePolyline, preview, config.alignmentMode());
    }

    public static boolean isSketchLikeSelection(SelectionContext selection) {
        return selection.isFullWaySelection()
            && selection.segmentNodes().size() >= 2
            && selection.segmentNodes().size() <= 5;
    }

    public static AlignmentMode effectiveAlignmentMode(SelectionContext selection, ManagedHeatmapConfig config) {
        return config.alignmentMode();
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
        if (selection.fixedNodes().isEmpty()) {
            PluginLog.verbose("Precise-shape preview has no fixed anchors; using traced centerline endpoints.");
            return new ArrayList<>(working);
        }
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

    private List<EastNorth> guardFixedAnchorTurns(
        SelectionContext selection,
        List<EastNorth> sourcePolyline,
        List<EastNorth> preview,
        AlignmentMode mode
    ) {
        if (preview.size() < 3 || selection.fixedNodes().isEmpty()) {
            return preview;
        }
        return switch (mode) {
            case MOVE_EXISTING_NODES -> guardMoveModeFixedTurns(selection, sourcePolyline, preview);
            case PRECISE_SHAPE -> guardPreciseModeFixedTurns(selection, sourcePolyline, preview);
        };
    }

    private List<EastNorth> guardMoveModeFixedTurns(SelectionContext selection, List<EastNorth> sourcePolyline, List<EastNorth> preview) {
        List<EastNorth> guarded = new ArrayList<>(preview);
        List<Node> nodes = selection.segmentNodes();
        for (int i = 1; i < nodes.size() - 1; i++) {
            if (!selection.fixedNodes().contains(nodes.get(i))) {
                continue;
            }
            double turn = turningAngleDegrees(guarded.get(i - 1), guarded.get(i), guarded.get(i + 1));
            if (turn < MAX_UNSUPPORTED_FIXED_TURN_DEGREES) {
                continue;
            }
            if (!selection.fixedNodes().contains(nodes.get(i - 1))) {
                guarded.set(i - 1, sourcePolyline.get(i - 1));
            }
            if (!selection.fixedNodes().contains(nodes.get(i + 1))) {
                guarded.set(i + 1, sourcePolyline.get(i + 1));
            }
            PluginLog.debug("Guarded fixed-node turn at segment index %d from %.1f degrees by keeping adjacent movable nodes closer to source.",
                i, turn);
        }
        return guarded;
    }

    private List<EastNorth> guardPreciseModeFixedTurns(SelectionContext selection, List<EastNorth> sourcePolyline, List<EastNorth> preview) {
        List<EastNorth> guarded = new ArrayList<>(preview);
        for (int fixedIndex : fixedIndices(selection)) {
            EastNorth anchor = sourcePolyline.get(fixedIndex);
            int previewIndex = findMatchingPoint(guarded, anchor);
            if (previewIndex <= 0 || previewIndex >= guarded.size() - 1) {
                continue;
            }
            while (guarded.size() >= 3 && previewIndex > 0 && previewIndex < guarded.size() - 1) {
                double turn = turningAngleDegrees(guarded.get(previewIndex - 1), guarded.get(previewIndex), guarded.get(previewIndex + 1));
                if (turn < MAX_UNSUPPORTED_FIXED_TURN_DEGREES) {
                    break;
                }
                double leftDistance = guarded.get(previewIndex - 1).distance(anchor);
                double rightDistance = guarded.get(previewIndex + 1).distance(anchor);
                boolean canRemoveLeft = previewIndex - 1 > 0
                    && !isFixedAnchorPoint(selection, sourcePolyline, guarded.get(previewIndex - 1));
                boolean canRemoveRight = previewIndex + 1 < guarded.size() - 1
                    && !isFixedAnchorPoint(selection, sourcePolyline, guarded.get(previewIndex + 1));
                if (leftDistance <= rightDistance && canRemoveLeft) {
                    guarded.remove(previewIndex - 1);
                    previewIndex--;
                } else if (canRemoveRight) {
                    guarded.remove(previewIndex + 1);
                } else if (canRemoveLeft) {
                    guarded.remove(previewIndex - 1);
                    previewIndex--;
                } else {
                    break;
                }
                PluginLog.debug("Removed a near-anchor precise preview point to avoid an unsupported %.1f degree fixed-node turn.", turn);
            }
        }
        return guarded;
    }

    private boolean isFixedAnchorPoint(SelectionContext selection, List<EastNorth> sourcePolyline, EastNorth point) {
        for (int fixedIndex : fixedIndices(selection)) {
            if (sourcePolyline.get(fixedIndex).distance(point) < 0.01) {
                return true;
            }
        }
        return false;
    }

    private int findMatchingPoint(List<EastNorth> points, EastNorth target) {
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).distance(target) < 0.01) {
                return i;
            }
        }
        return -1;
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

    private double turningAngleDegrees(EastNorth previous, EastNorth current, EastNorth next) {
        double ax = current.east() - previous.east();
        double ay = current.north() - previous.north();
        double bx = next.east() - current.east();
        double by = next.north() - current.north();
        double aNorm = Math.hypot(ax, ay);
        double bNorm = Math.hypot(bx, by);
        if (aNorm == 0.0 || bNorm == 0.0) {
            return 0.0;
        }
        double cosine = (ax * bx + ay * by) / (aNorm * bNorm);
        cosine = Math.max(-1.0, Math.min(1.0, cosine));
        return Math.toDegrees(Math.acos(cosine));
    }

    private AlignmentDiagnostics diagnostics(
        ImageryLayer imageryLayer,
        int candidateCount,
        int nodeMoveCount,
        long t0,
        long t1,
        long t2,
        long t3,
        BufferedImage raster,
        ManagedHeatmapConfig config,
        SelectionContext selection,
        List<String> colorModes,
        List<CenterlineCandidate> candidates
    ) {
        return new AlignmentDiagnostics(
            imageryLayer.getName(),
            candidateCount,
            nodeMoveCount,
            millisBetween(t0, t1),
            millisBetween(t1, t2),
            millisBetween(t2, t3),
            config.toRedactedJson(),
            selectionToJson(selection),
            samplingJson(imageryLayer, raster),
            stringArray(colorModes),
            candidatesToJson(candidates)
        );
    }

    private String samplingJson(ImageryLayer imageryLayer, BufferedImage raster) {
        int zoom = -1;
        int bestZoom = -1;
        if (imageryLayer instanceof AbstractTileSourceLayer<?> tileLayer) {
            zoom = tileLayer.getZoomLevel();
            bestZoom = tileLayer.getBestZoom();
        }
        return "{"
            + "\"type\":\"rendered-visible-layer\","
            + "\"algorithm\":\"v0.2-compatible\","
            + "\"layerClass\":\"" + jsonEscape(imageryLayer.getClass().getName()) + "\","
            + "\"tileZoom\":" + nullableInt(zoom) + ','
            + "\"bestTileZoom\":" + nullableInt(bestZoom) + ','
            + "\"rasterScale\":" + RenderedHeatmapSampler.RASTER_SCALE + ','
            + "\"rasterWidth\":" + (raster == null ? 0 : raster.getWidth()) + ','
            + "\"rasterHeight\":" + (raster == null ? 0 : raster.getHeight()) + ','
            + "\"layerPPD\":" + imageryLayer.getPPD()
            + "}";
    }

    private String renderedZoomSummary(ImageryLayer imageryLayer) {
        if (imageryLayer instanceof AbstractTileSourceLayer<?> tileLayer) {
            return "z" + tileLayer.getZoomLevel() + " (best z" + tileLayer.getBestZoom() + ")";
        }
        return "not a tiled imagery layer";
    }

    private String nullableInt(int value) {
        return value < 0 ? "null" : Integer.toString(value);
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

    private String candidatesToJson(List<CenterlineCandidate> candidates) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < candidates.size(); i++) {
            CenterlineCandidate candidate = candidates.get(i);
            if (i > 0) {
                builder.append(',');
            }
            builder.append('{')
                .append("\"id\":\"").append(jsonEscape(candidate.id())).append("\",")
                .append("\"score\":").append(candidate.score()).append(',')
                .append("\"points\":").append(candidate.screenPoints().size()).append(',')
                .append("\"offsetsPx\":").append(doubleArray(candidate.offsetsPx())).append(',')
                .append("\"safetyWarnings\":").append(stringArray(candidate.safetyWarnings())).append(',')
                .append("\"evidence\":").append(candidate.evidence().toJson())
                .append('}');
        }
        return builder.append(']').toString();
    }

    private String stringArray(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"').append(jsonEscape(values.get(i))).append('"');
        }
        return builder.append(']').toString();
    }

    private String doubleArray(List<Double> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(String.format(java.util.Locale.ROOT, "%.3f", values.get(i)));
        }
        return builder.append(']').toString();
    }

    private String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static final class AlignmentFailureException extends IllegalStateException {
        private final AlignmentResult partialResult;

        AlignmentFailureException(String message, AlignmentResult partialResult) {
            super(message);
            this.partialResult = partialResult;
        }

        public AlignmentResult partialResult() {
            return partialResult;
        }
    }
}
