package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.image.BufferedImage;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentDiagnostics;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NodeMove;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;

public final class AlignmentService {
    private static final List<String> ALL_COLOR_MODES = List.of("hot", "blue", "bluered", "purple", "gray", "dual");
    private static final double MAX_UNSUPPORTED_FIXED_TURN_DEGREES = 75.0;
    private static final int MAX_INTERNAL_REFINEMENT_PASSES = 2;
    private static final double MIN_REFINEMENT_SCORE_GAIN = 0.75;
    private static final double PARALLEL_MATCH_THRESHOLD_RASTER_PX = 42.0;

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
        List<CenterlineCandidate> candidates = detectCandidates(selection, raster, mapView, sourcePolyline, config);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No stable ridge candidate was detected in the sampled heatmap.");
        }
        candidates = refineCandidates(selection, raster, mapView, sourcePolyline, candidates, config);
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

    private List<CenterlineCandidate> detectCandidates(
        SelectionContext selection,
        BufferedImage raster,
        MapView mapView,
        List<EastNorth> sourcePolyline,
        ManagedHeatmapConfig config
    ) {
        List<String> colorModes = detectionColorModes(config);
        List<CenterlineCandidate> candidates = new ArrayList<>();
        int halfWidthPx = effectiveHalfWidthPx(selection, config);
        for (String colorMode : colorModes) {
            List<RenderedHeatmapSampler.CrossSectionProfile> profiles =
                sampler.sampleProfiles(raster, mapView, sourcePolyline, halfWidthPx, config.crossSectionStepPx(), colorMode);
            List<CenterlineCandidate> colorCandidates = ridgeTracker.track(profiles);
            for (CenterlineCandidate candidate : colorCandidates) {
                candidates.add(candidate.withId(colorMode + "/" + candidate.id()));
            }
            PluginLog.verbose("Color mode '%s' produced %d ridge candidates.", colorMode, colorCandidates.size());
        }
        if (config.multiColorDetection()) {
            candidates = applyColorConsensus(candidates);
        }
        if (config.parallelWayAwareness()) {
            candidates = applyParallelWayContext(selection, mapView, candidates);
        }
        return candidates.stream()
            .sorted(java.util.Comparator.comparingDouble(CenterlineCandidate::score).reversed())
            .toList();
    }

    private List<CenterlineCandidate> refineCandidates(
        SelectionContext selection,
        BufferedImage raster,
        MapView mapView,
        List<EastNorth> sourcePolyline,
        List<CenterlineCandidate> initial,
        ManagedHeatmapConfig config
    ) {
        List<CenterlineCandidate> bestCandidates = initial;
        CenterlineCandidate best = bestCandidates.get(0);
        List<EastNorth> currentAxis = optimizer.projectCandidate(best, mapView);
        for (int pass = 1; pass <= MAX_INTERNAL_REFINEMENT_PASSES; pass++) {
            List<CenterlineCandidate> refined = detectCandidates(selection, raster, mapView, currentAxis, config);
            if (refined.isEmpty()) {
                break;
            }
            CenterlineCandidate refinedBest = refined.get(0);
            double movement = meanScreenDistance(best.screenPoints(), refinedBest.screenPoints());
            double gain = refinedBest.score() - best.score();
            if (gain < MIN_REFINEMENT_SCORE_GAIN) {
                PluginLog.verbose("Internal refinement stopped after pass %d: score gain %.2f, mean movement %.1f raster px.",
                    pass, gain, movement);
                break;
            }
            if (addsUnsupportedOscillation(best.offsetsPx(), refinedBest.offsetsPx())) {
                PluginLog.verbose("Internal refinement pass %d rejected because it increased high-frequency lateral oscillation.", pass);
                break;
            }
            PluginLog.verbose("Internal refinement pass %d accepted: score %.2f -> %.2f, mean movement %.1f raster px.",
                pass, best.score(), refinedBest.score(), movement);
            best = refinedBest.withId("refined-" + pass + "/" + refinedBest.id());
            List<CenterlineCandidate> merged = new ArrayList<>();
            merged.add(best);
            merged.addAll(bestCandidates);
            bestCandidates = merged.stream()
                .sorted(java.util.Comparator.comparingDouble(CenterlineCandidate::score).reversed())
                .toList();
            currentAxis = optimizer.projectCandidate(best, mapView);
        }
        return bestCandidates.stream()
            .sorted(java.util.Comparator.comparingDouble(CenterlineCandidate::score).reversed())
            .toList();
    }

    private int effectiveHalfWidthPx(SelectionContext selection, ManagedHeatmapConfig config) {
        int configured = Math.max(1, config.crossSectionHalfWidthPx());
        if (isSketchLikeSelection(selection)) {
            return Math.max(configured, 64);
        }
        return configured;
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

    private List<CenterlineCandidate> applyColorConsensus(List<CenterlineCandidate> candidates) {
        if (candidates.size() < 2) {
            return candidates;
        }
        List<CenterlineCandidate> scored = new ArrayList<>(candidates.size());
        for (CenterlineCandidate candidate : candidates) {
            java.util.Set<String> supportingModes = new java.util.LinkedHashSet<>();
            double supportWeight = 0.0;
            for (CenterlineCandidate other : candidates) {
                if (averageOffsetDistance(candidate, other) > 7.0) {
                    continue;
                }
                String mode = detectorMode(other);
                if (supportingModes.add(mode)) {
                    supportWeight += detectorConsensusWeight(mode);
                }
            }
            if (supportingModes.size() <= 1) {
                scored.add(candidate);
                continue;
            }
            double consensusBonus = supportWeight * 2.4 + supportingModes.size() * 0.8;
            String consensusId = "consensus-" + supportingModes.size() + "/" + candidate.id();
            scored.add(candidate.withId(consensusId).withScore(candidate.score() + consensusBonus));
        }
        PluginLog.verbose("Multi-color consensus scoring compared %d ridge candidates.", candidates.size());
        return scored;
    }

    private String detectorMode(CenterlineCandidate candidate) {
        String id = candidate.id();
        int slash = id.indexOf('/');
        if (slash <= 0) {
            return id;
        }
        String first = id.substring(0, slash);
        if (first.startsWith("refined-") || first.startsWith("consensus-")) {
            String rest = id.substring(slash + 1);
            int nextSlash = rest.indexOf('/');
            return nextSlash <= 0 ? rest : rest.substring(0, nextSlash);
        }
        return first;
    }

    private double detectorConsensusWeight(String mode) {
        return switch (mode) {
            case "dual" -> 1.55;
            case "bluered", "gray" -> 1.35;
            case "hot", "blue", "purple" -> 1.0;
            default -> 0.85;
        };
    }

    private List<CenterlineCandidate> applyParallelWayContext(
        SelectionContext selection,
        MapView mapView,
        List<CenterlineCandidate> candidates
    ) {
        DataSet dataSet = selection.way().getDataSet();
        if (dataSet == null || candidates.isEmpty()) {
            return candidates;
        }
        List<List<Point2D.Double>> parallelWayScreens = new ArrayList<>();
        for (Way way : dataSet.getWays()) {
            if (way == selection.way() || way.isDeleted() || way.getNodesCount() < 2 || !isRelevantLinearWay(way)) {
                continue;
            }
            List<Point2D.Double> screen = wayToRasterScreen(way, mapView);
            if (screen.size() >= 2 && isCloseToAnyCandidate(screen, candidates)) {
                parallelWayScreens.add(screen);
            }
        }
        if (parallelWayScreens.isEmpty()) {
            return candidates;
        }

        boolean sketch = isSketchLikeSelection(selection);
        List<CenterlineCandidate> scored = new ArrayList<>(candidates.size());
        for (CenterlineCandidate candidate : candidates) {
            double nearest = parallelWayScreens.stream()
                .mapToDouble(screen -> meanNearestDistance(candidate.screenPoints(), screen))
                .min()
                .orElse(Double.POSITIVE_INFINITY);
            if (nearest > PARALLEL_MATCH_THRESHOLD_RASTER_PX) {
                scored.add(candidate);
                continue;
            }
            double penalty = sketch ? 2.0 : 3.5;
            double adjusted = candidate.score() - penalty * (1.0 - nearest / PARALLEL_MATCH_THRESHOLD_RASTER_PX);
            scored.add(candidate.withId(candidate.id() + "/near-mapped-parallel").withScore(adjusted));
        }
        PluginLog.verbose("Parallel-way awareness evaluated %d nearby mapped linear ways.", parallelWayScreens.size());
        return scored;
    }

    private boolean isRelevantLinearWay(Way way) {
        String highway = way.get("highway");
        if (highway == null || highway.isBlank()) {
            return false;
        }
        return switch (highway) {
            case "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
                 "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
                 "residential", "service", "track", "path", "footway", "cycleway", "bridleway",
                 "steps", "pedestrian", "living_street", "road" -> true;
            default -> false;
        };
    }

    private List<Point2D.Double> wayToRasterScreen(Way way, MapView mapView) {
        List<Point2D.Double> points = new ArrayList<>();
        for (Node node : way.getNodes()) {
            if (!node.isUsable() || node.getEastNorth(ProjectionRegistry.getProjection()) == null) {
                continue;
            }
            Point2D point = mapView.getPoint2D(node.getEastNorth(ProjectionRegistry.getProjection()));
            points.add(new Point2D.Double(
                point.getX() * RenderedHeatmapSampler.RASTER_SCALE,
                point.getY() * RenderedHeatmapSampler.RASTER_SCALE
            ));
        }
        return points;
    }

    private boolean isCloseToAnyCandidate(List<Point2D.Double> wayScreen, List<CenterlineCandidate> candidates) {
        for (CenterlineCandidate candidate : candidates) {
            if (meanNearestDistance(candidate.screenPoints(), wayScreen) <= PARALLEL_MATCH_THRESHOLD_RASTER_PX * 1.6) {
                return true;
            }
        }
        return false;
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

    private double meanScreenDistance(List<Point2D.Double> left, List<Point2D.Double> right) {
        int count = Math.min(left.size(), right.size());
        if (count == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            sum += left.get(i).distance(right.get(i));
        }
        return sum / count;
    }

    private double averageOffsetDistance(CenterlineCandidate left, CenterlineCandidate right) {
        int count = Math.min(left.offsetsPx().size(), right.offsetsPx().size());
        if (count == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            sum += Math.abs(left.offsetsPx().get(i) - right.offsetsPx().get(i));
        }
        return sum / count;
    }

    private double meanNearestDistance(List<Point2D.Double> source, List<Point2D.Double> targetPolyline) {
        if (source.isEmpty() || targetPolyline.size() < 2) {
            return Double.POSITIVE_INFINITY;
        }
        double sum = 0.0;
        for (Point2D.Double point : source) {
            sum += nearestDistanceToPolyline(point, targetPolyline);
        }
        return sum / source.size();
    }

    private double nearestDistanceToPolyline(Point2D.Double point, List<Point2D.Double> polyline) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 1; i < polyline.size(); i++) {
            best = Math.min(best, distanceToSegment(point, polyline.get(i - 1), polyline.get(i)));
        }
        return best;
    }

    private double distanceToSegment(Point2D.Double point, Point2D.Double start, Point2D.Double end) {
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared < 1e-9) {
            return point.distance(start);
        }
        double t = ((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        return point.distance(start.x + dx * t, start.y + dy * t);
    }

    private boolean addsUnsupportedOscillation(List<Double> previous, List<Double> next) {
        double previousOscillation = lateralOscillation(previous);
        double nextOscillation = lateralOscillation(next);
        return nextOscillation > previousOscillation + 18.0;
    }

    private double lateralOscillation(List<Double> offsets) {
        if (offsets.size() < 3) {
            return 0.0;
        }
        double oscillation = 0.0;
        for (int i = 1; i < offsets.size() - 1; i++) {
            double left = offsets.get(i) - offsets.get(i - 1);
            double right = offsets.get(i + 1) - offsets.get(i);
            if (Math.signum(left) != Math.signum(right)) {
                oscillation += Math.min(24.0, Math.abs(left) + Math.abs(right));
            }
        }
        return oscillation;
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
        MapView effectiveMapView = mapView != null ? mapView : org.openstreetmap.josm.gui.MainApplication.getMap().mapView;
        List<EastNorth> candidateCenterline = optimizer.projectCandidate(candidate, effectiveMapView);
        PluginLog.debug("Candidate %s projected centerline point count=%d.", candidate.id(), candidateCenterline.size());
        List<EastNorth> working;
        AlignmentMode effectiveMode = effectiveAlignmentMode(selection, config);
        if (effectiveMode == AlignmentMode.MOVE_EXISTING_NODES) {
            working = candidateCenterline;
            PluginLog.verbose("Move-existing-nodes mode uses direct centerline projection without dense path optimization.");
            if (config.simplifyEnabled()) {
                PluginLog.verbose("Simplification checkbox is ignored in Move Existing Nodes mode.");
            }
        } else {
            working = candidateCenterline;
            PluginLog.verbose("%s mode rebuilds the segment from the traced centerline.",
                isSketchLikeSelection(selection) ? "Sketch-like precise-shape" : "Precise-shape");
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
        List<EastNorth> preview = switch (effectiveMode) {
            case MOVE_EXISTING_NODES -> moveExistingNodesPreview(selection, sourcePolyline, working);
            case PRECISE_SHAPE -> preciseShapePreview(selection, sourcePolyline, working);
        };
        return guardFixedAnchorTurns(selection, sourcePolyline, preview, effectiveMode);
    }

    public static boolean isSketchLikeSelection(SelectionContext selection) {
        return selection.isFullWaySelection()
            && selection.segmentNodes().size() >= 2
            && selection.segmentNodes().size() <= 5;
    }

    public static AlignmentMode effectiveAlignmentMode(SelectionContext selection, ManagedHeatmapConfig config) {
        if (isSketchLikeSelection(selection)) {
            return AlignmentMode.PRECISE_SHAPE;
        }
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
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode mode
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
