package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.ProjectionBounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
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
    private static final List<String> ALL_COLOR_MODES = List.of(
        "hot",
        "blue",
        "bluered",
        "purple",
        "gray",
        "gray-magenta",
        "gray-corridor",
        "dual",
        "hot-corridor",
        "hot-strict",
        "bluered-cool",
        "bluered-corridor",
        "dual-corridor",
        "gray-strict",
        "purple-strict"
    );
    private static final double MAX_UNSUPPORTED_FIXED_TURN_DEGREES = 75.0;
    private static final double REFERENCE_VIEW_METERS_PER_PIXEL = 0.389;
    private static final int MIN_EFFECTIVE_HALF_WIDTH_PX = 6;
    private static final int MAX_EFFECTIVE_HALF_WIDTH_PX = 120;
    private static final int MIN_EFFECTIVE_STEP_PX = 1;
    private static final int MAX_EFFECTIVE_STEP_PX = 32;

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
        EffectiveSampling effectiveSampling = effectiveSampling(config, mapView);
        PluginLog.verbose(
            "Effective visible-layer sampling: configured halfWidth=%d px step=%d px; target halfWidth=%.2f m step=%.2f m; effective halfWidth=%d px step=%d px at view %.3f m/px.",
            config.crossSectionHalfWidthPx(),
            config.crossSectionStepPx(),
            effectiveSampling.targetHalfWidthMeters(),
            effectiveSampling.targetStepMeters(),
            effectiveSampling.effectiveHalfWidthPx(),
            effectiveSampling.effectiveStepPx(),
            effectiveSampling.viewMetersPerPixel()
        );
        DetectionResult detection = detectCandidates(raster, mapView, sourcePolyline, config, colorModes, effectiveSampling);
        List<CenterlineCandidate> candidates = detection.candidates();
        long t2 = System.nanoTime();
        if (candidates.isEmpty()) {
            AlignmentResult partial = partialResult(selection, raster, sourcePolyline, imageryLayer, mapView,
                config, colorModes, detection.profilesJson(), effectiveSampling, t0, t1, t2, t2);
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
            mapView,
            config,
            selection,
            colorModes,
            candidates,
            detection.profilesJson(),
            effectiveSampling
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
        MapView mapView,
        ManagedHeatmapConfig config,
        List<String> colorModes,
        String profilesJson,
        EffectiveSampling effectiveSampling,
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
            diagnostics(imageryLayer, 0, 0, t0, t1, t2, t3, raster, mapView, config, selection, colorModes,
                List.of(), profilesJson, effectiveSampling),
            null
        );
    }

    private DetectionResult detectCandidates(
        BufferedImage raster,
        MapView mapView,
        List<EastNorth> sourcePolyline,
        ManagedHeatmapConfig config,
        List<String> colorModes,
        EffectiveSampling effectiveSampling
    ) {
        List<CenterlineCandidate> candidates = new ArrayList<>();
        StringBuilder profileDiagnostics = new StringBuilder("[");
        int modeIndex = 0;
        for (String colorMode : colorModes) {
            List<RenderedHeatmapSampler.CrossSectionProfile> profiles =
                sampler.sampleProfiles(raster, mapView, sourcePolyline,
                    effectiveSampling.effectiveHalfWidthPx(), effectiveSampling.effectiveStepPx(), colorMode);
            List<CenterlineCandidate> colorCandidates = ridgeTracker.track(profiles);
            if (modeIndex++ > 0) {
                profileDiagnostics.append(',');
            }
            profileDiagnostics.append(profilesToJson(colorMode, profiles, colorCandidates));
            for (CenterlineCandidate candidate : colorCandidates) {
                CenterlineCandidate withMode = candidate
                    .withId(colorMode + "/" + candidate.id())
                    .withEvidence(candidate.evidence().withDetectorMode(colorMode));
                withMode = withMode.withEastNorthPoints(optimizer.projectCandidate(withMode, mapView));
                candidates.add(withMode);
            }
            PluginLog.verbose("Color mode '%s' produced %d ridge candidates.", colorMode, colorCandidates.size());
        }
        java.util.Comparator<CenterlineCandidate> candidateComparator = config.multiColorDetection()
            ? java.util.Comparator
                .comparingDouble((CenterlineCandidate candidate) -> calibratedRankingScore(candidate, config, effectiveSampling))
                .reversed()
                .thenComparing(java.util.Comparator.comparingDouble(CenterlineCandidate::score).reversed())
            : java.util.Comparator.comparingDouble(CenterlineCandidate::score).reversed();
        List<CenterlineCandidate> sorted = candidates.stream().sorted(candidateComparator).toList();
        return new DetectionResult(sorted, profileDiagnostics.append(']').toString());
    }

    private double calibratedRankingScore(CenterlineCandidate candidate, ManagedHeatmapConfig config, EffectiveSampling effectiveSampling) {
        String detector = detectorMode(candidate);
        String visibleColor = normalizedVisibleColor(config);
        CandidateMetrics metrics = candidateMetrics(candidate, effectiveSampling);
        double signalReward =
            0.85 * clamp01(candidate.evidence().signalToNoise() / 0.55)
            + 0.25 * clamp01(candidate.evidence().meanIntensity() / 0.75)
            + 0.25 * clamp01(candidate.evidence().supportRatio());
        double roughnessPenalty =
            0.22 * clamp01(candidate.evidence().ambiguity() / 0.60)
            + 0.20 * clamp01(metrics.p95DeltaReferencePx() / 35.0)
            + 0.20 * clamp01(metrics.p95AccelerationReferencePx() / 35.0)
            + 0.10 * clamp01(metrics.signFlips() / 5.0)
            + 0.65 * clamp01(metrics.edgeRatio() / 0.08);
        double noOpPenalty = metrics.absMeanOffsetMeters() < 0.39 && candidate.evidence().meanIntensity() < 0.35 ? 0.80 : 0.0;
        double largeOffsetPenalty = metrics.absMeanOffsetMeters() > 4.54 ? 0.20 : 0.0;
        return detectorPrior(visibleColor, detector)
            + globalDetectorAdjustment(detector)
            + signalReward
            - roughnessPenalty
            - noOpPenalty
            - largeOffsetPenalty;
    }

    private String detectorMode(CenterlineCandidate candidate) {
        String detector = candidate.evidence().detectorMode();
        if (detector == null || detector.isBlank()) {
            detector = candidate.id();
        }
        int slash = detector.indexOf('/');
        if (slash >= 0) {
            detector = detector.substring(0, slash);
        }
        return detector.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizedVisibleColor(ManagedHeatmapConfig config) {
        if (config.color() == null || config.color().isBlank()) {
            return "hot";
        }
        return config.color().trim().toLowerCase(Locale.ROOT);
    }

    private double detectorPrior(String visibleColor, String detector) {
        return switch (visibleColor) {
            case "hot" -> switch (detector) {
                case "hot-corridor" -> 1.00;
                case "hot" -> 0.65;
                case "dual-corridor" -> 0.55;
                case "dual", "bluered", "gray", "gray-corridor", "gray-magenta" -> 0.25;
                case "blue", "purple", "purple-strict" -> -0.35;
                case "hot-strict" -> 0.0;
                default -> 0.0;
            };
            case "bluered" -> switch (detector) {
                case "bluered-corridor" -> 1.00;
                case "bluered-cool" -> 0.90;
                case "hot-corridor" -> 0.85;
                case "dual-corridor" -> 0.55;
                case "blue" -> 0.05;
                case "bluered", "hot" -> 0.0;
                case "dual", "gray", "gray-strict", "purple", "hot-strict" -> -0.30;
                default -> -0.05;
            };
            case "blue" -> switch (detector) {
                case "dual-corridor" -> 1.00;
                case "hot", "hot-corridor" -> 0.75;
                case "bluered-cool", "bluered-corridor" -> 0.55;
                case "gray", "gray-corridor", "gray-magenta" -> 0.25;
                case "blue" -> 0.20;
                case "purple", "purple-strict" -> -0.35;
                default -> 0.0;
            };
            case "gray" -> switch (detector) {
                case "blue" -> 1.00;
                case "dual-corridor" -> 0.75;
                case "gray-corridor" -> 0.65;
                case "gray-magenta" -> 0.55;
                case "hot", "hot-corridor", "hot-strict", "dual" -> 0.50;
                case "gray" -> 0.25;
                case "gray-strict", "purple-strict" -> -0.25;
                default -> 0.0;
            };
            case "purple" -> switch (detector) {
                case "hot", "dual-corridor" -> 1.00;
                case "hot-corridor" -> 0.75;
                case "bluered-cool", "bluered-corridor", "gray-strict" -> 0.65;
                case "gray", "gray-corridor", "gray-magenta" -> 0.45;
                case "purple", "purple-strict" -> -0.30;
                default -> 0.0;
            };
            default -> switch (detector) {
                case "hot-corridor", "dual-corridor" -> 0.60;
                case "bluered-corridor", "bluered-cool", "gray-corridor" -> 0.50;
                case "gray-magenta" -> 0.35;
                default -> 0.0;
            };
        };
    }

    private double globalDetectorAdjustment(String detector) {
        return switch (detector) {
            case "hot-corridor" -> 0.25;
            case "dual-corridor" -> 0.20;
            case "bluered-corridor" -> 0.15;
            case "bluered-cool", "gray-corridor" -> 0.10;
            case "gray-magenta" -> 0.05;
            case "hot-strict" -> -0.35;
            case "gray-strict" -> -0.30;
            case "purple", "purple-strict" -> -0.35;
            default -> 0.0;
        };
    }

    private CandidateMetrics candidateMetrics(CenterlineCandidate candidate, EffectiveSampling effectiveSampling) {
        List<Double> offsets = candidate.offsetsPx();
        if (offsets.isEmpty()) {
            return new CandidateMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0, 0.0, 0.0, 0.0);
        }
        double absMean = offsets.stream().mapToDouble(Math::abs).average().orElse(0.0);
        List<Double> deltas = new ArrayList<>();
        for (int i = 1; i < offsets.size(); i++) {
            deltas.add(offsets.get(i) - offsets.get(i - 1));
        }
        List<Double> accelerations = new ArrayList<>();
        for (int i = 1; i < deltas.size(); i++) {
            accelerations.add(deltas.get(i) - deltas.get(i - 1));
        }
        double p95Delta = percentileAbs(deltas, 0.95);
        double p95Acceleration = percentileAbs(accelerations, 0.95);
        double normalization = effectiveSampling.rasterMetersPerPixel() / effectiveSampling.referenceRasterMetersPerPixel();
        double edgeLimit = effectiveSampling.effectiveHalfWidthPx() * RenderedHeatmapSampler.RASTER_SCALE * 0.90;
        long edgeCount = offsets.stream().filter(offset -> Math.abs(offset) >= edgeLimit).count();
        return new CandidateMetrics(
            absMean,
            p95Delta,
            p95Acceleration,
            p95Delta * normalization,
            p95Acceleration * normalization,
            signFlips(deltas, effectiveSampling),
            offsets.isEmpty() ? 0.0 : (double) edgeCount / offsets.size(),
            absMean * effectiveSampling.rasterMetersPerPixel(),
            p95Delta * effectiveSampling.rasterMetersPerPixel(),
            p95Acceleration * effectiveSampling.rasterMetersPerPixel()
        );
    }

    private double percentileAbs(List<Double> values, double percentile) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> absolute = values.stream()
            .map(Math::abs)
            .sorted()
            .toList();
        int index = Math.max(0, Math.min(absolute.size() - 1, (int) Math.ceil(percentile * absolute.size()) - 1));
        return absolute.get(index);
    }

    private int signFlips(List<Double> deltas, EffectiveSampling effectiveSampling) {
        int flips = 0;
        double flipThresholdPx = 0.52 / effectiveSampling.rasterMetersPerPixel();
        for (int i = 1; i < deltas.size(); i++) {
            double left = deltas.get(i - 1);
            double right = deltas.get(i);
            if (Math.abs(left) > flipThresholdPx && Math.abs(right) > flipThresholdPx && Math.signum(left) != Math.signum(right)) {
                flips++;
            }
        }
        return flips;
    }

    private double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
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
        List<EastNorth> candidateCenterline = candidateCenterline(candidate, mapView);
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

    private List<EastNorth> candidateCenterline(CenterlineCandidate candidate, MapView mapView) {
        if (!candidate.eastNorthPoints().isEmpty()) {
            PluginLog.debug("Using slide-time EastNorth geometry for candidate %s.", candidate.id());
            return candidate.eastNorthPoints();
        }
        MapView effectiveMapView = mapView;
        if (effectiveMapView == null && MainApplication.getMap() != null) {
            effectiveMapView = MainApplication.getMap().mapView;
        }
        if (effectiveMapView == null) {
            throw new IllegalStateException("No slide-time candidate geometry or map view is available for candidate projection.");
        }
        PluginLog.debug("Candidate %s has no stored EastNorth geometry; projecting from the current map view.", candidate.id());
        return optimizer.projectCandidate(candidate, effectiveMapView);
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

    private EffectiveSampling effectiveSampling(ManagedHeatmapConfig config, MapView mapView) {
        double viewMetersPerPixel = currentViewMetersPerPixel(mapView);
        double targetHalfWidthMeters = config.crossSectionHalfWidthPx() * REFERENCE_VIEW_METERS_PER_PIXEL;
        double targetStepMeters = config.crossSectionStepPx() * REFERENCE_VIEW_METERS_PER_PIXEL;
        int effectiveHalfWidthPx = scaleMetersToViewPixels(
            targetHalfWidthMeters,
            viewMetersPerPixel,
            config.crossSectionHalfWidthPx(),
            MIN_EFFECTIVE_HALF_WIDTH_PX,
            MAX_EFFECTIVE_HALF_WIDTH_PX
        );
        int effectiveStepPx = scaleMetersToViewPixels(
            targetStepMeters,
            viewMetersPerPixel,
            config.crossSectionStepPx(),
            MIN_EFFECTIVE_STEP_PX,
            MAX_EFFECTIVE_STEP_PX
        );
        effectiveStepPx = Math.max(1, Math.min(effectiveStepPx, Math.max(1, effectiveHalfWidthPx)));
        double effectiveViewMetersPerPixel = Double.isFinite(viewMetersPerPixel) && viewMetersPerPixel > 0.0
            ? viewMetersPerPixel
            : REFERENCE_VIEW_METERS_PER_PIXEL;
        return new EffectiveSampling(
            config.crossSectionHalfWidthPx(),
            config.crossSectionStepPx(),
            effectiveHalfWidthPx,
            effectiveStepPx,
            REFERENCE_VIEW_METERS_PER_PIXEL,
            effectiveViewMetersPerPixel,
            targetHalfWidthMeters,
            targetStepMeters
        );
    }

    private int scaleMetersToViewPixels(double meters, double viewMetersPerPixel, int fallbackPx, int minPx, int maxPx) {
        if (!Double.isFinite(viewMetersPerPixel) || viewMetersPerPixel <= 0.0) {
            return Math.max(minPx, Math.min(maxPx, fallbackPx));
        }
        int pixels = (int) Math.round(meters / viewMetersPerPixel);
        return Math.max(minPx, Math.min(maxPx, pixels));
    }

    private double currentViewMetersPerPixel(MapView mapView) {
        double dist100Pixel = mapView == null ? Double.NaN : safeDouble(mapView.getDist100Pixel());
        return dist100Pixel > 0.0 ? dist100Pixel / 100.0 : Double.NaN;
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
        MapView mapView,
        ManagedHeatmapConfig config,
        SelectionContext selection,
        List<String> colorModes,
        List<CenterlineCandidate> candidates,
        String profilesJson,
        EffectiveSampling effectiveSampling
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
            samplingJson(imageryLayer, raster, mapView, effectiveSampling),
            stringArray(colorModes),
            candidatesToJson(candidates, config, effectiveSampling),
            profilesJson == null || profilesJson.isBlank() ? "[]" : profilesJson
        );
    }

    private String samplingJson(ImageryLayer imageryLayer, BufferedImage raster, MapView mapView, EffectiveSampling effectiveSampling) {
        int zoom = -1;
        int bestZoom = -1;
        if (imageryLayer instanceof AbstractTileSourceLayer<?> tileLayer) {
            zoom = tileLayer.getZoomLevel();
            bestZoom = tileLayer.getBestZoom();
        }
        int viewWidth = mapView == null ? 0 : mapView.getWidth();
        int viewHeight = mapView == null ? 0 : mapView.getHeight();
        double dist100Pixel = safeDouble(mapView == null ? Double.NaN : mapView.getDist100Pixel());
        double viewMetersPerPixel = dist100Pixel > 0.0 ? dist100Pixel / 100.0 : Double.NaN;
        double rasterMetersPerPixel = viewMetersPerPixel > 0.0 ? viewMetersPerPixel / RenderedHeatmapSampler.RASTER_SCALE : Double.NaN;
        double mapScale = safeDouble(mapView == null ? Double.NaN : mapView.getScale());
        EastNorth center = mapView == null ? null : mapView.getCenter();
        LatLon centerLatLon = center == null ? null : ProjectionRegistry.getProjection().eastNorth2latlon(center);
        ProjectionBounds projectionBounds = mapView == null ? null : mapView.getProjectionBounds();
        Bounds realBounds = mapView == null ? null : mapView.getRealBounds();
        return "{"
            + "\"type\":\"rendered-visible-layer\","
            + "\"algorithm\":\"v0.2-compatible\","
            + "\"layerClass\":\"" + jsonEscape(imageryLayer.getClass().getName()) + "\","
            + "\"tileZoom\":" + nullableInt(zoom) + ','
            + "\"bestTileZoom\":" + nullableInt(bestZoom) + ','
            + "\"sourceTileZoom\":" + nullableInt(zoom) + ','
            + "\"bestSourceTileZoom\":" + nullableInt(bestZoom) + ','
            + "\"rasterScale\":" + RenderedHeatmapSampler.RASTER_SCALE + ','
            + "\"rasterWidth\":" + (raster == null ? 0 : raster.getWidth()) + ','
            + "\"rasterHeight\":" + (raster == null ? 0 : raster.getHeight()) + ','
            + "\"viewWidthPx\":" + viewWidth + ','
            + "\"viewHeightPx\":" + viewHeight + ','
            + "\"dist100PixelMeters\":" + jsonDouble(dist100Pixel) + ','
            + "\"viewMetersPerPixel\":" + jsonDouble(viewMetersPerPixel) + ','
            + "\"rasterMetersPerPixel\":" + jsonDouble(rasterMetersPerPixel) + ','
            + "\"referenceViewMetersPerPixel\":" + jsonDouble(effectiveSampling.referenceViewMetersPerPixel()) + ','
            + "\"configuredHalfWidthPx\":" + effectiveSampling.configuredHalfWidthPx() + ','
            + "\"configuredStepPx\":" + effectiveSampling.configuredStepPx() + ','
            + "\"effectiveHalfWidthPx\":" + effectiveSampling.effectiveHalfWidthPx() + ','
            + "\"effectiveStepPx\":" + effectiveSampling.effectiveStepPx() + ','
            + "\"targetHalfWidthMeters\":" + jsonDouble(effectiveSampling.targetHalfWidthMeters()) + ','
            + "\"targetStepMeters\":" + jsonDouble(effectiveSampling.targetStepMeters()) + ','
            + "\"effectiveHalfWidthMeters\":" + jsonDouble(effectiveSampling.effectiveHalfWidthMeters()) + ','
            + "\"effectiveStepMeters\":" + jsonDouble(effectiveSampling.effectiveStepMeters()) + ','
            + "\"mapScale\":" + jsonDouble(mapScale) + ','
            + "\"layerPPD\":" + jsonDouble(safeDouble(imageryLayer.getPPD())) + ','
            + "\"viewportCenter\":" + eastNorthLatLonJson(center, centerLatLon) + ','
            + "\"projectionBounds\":" + projectionBoundsJson(projectionBounds) + ','
            + "\"realBounds\":" + realBoundsJson(realBounds) + ','
            + "\"estimatedVisibleTiles\":" + estimatedVisibleTilesJson(realBounds, zoom)
            + "}";
    }

    private String profilesToJson(
        String colorMode,
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        List<CenterlineCandidate> colorCandidates
    ) {
        int supportedProfiles = 0;
        int emptyProfiles = 0;
        int maxPeaks = 0;
        double strongestTotal = 0.0;
        double maxIntensity = 0.0;
        for (RenderedHeatmapSampler.CrossSectionProfile profile : profiles) {
            maxPeaks = Math.max(maxPeaks, profile.peaks().size());
            double strongest = profile.peaks().stream()
                .mapToDouble(RenderedHeatmapSampler.CrossSectionPeak::intensity)
                .max()
                .orElse(0.0);
            if (strongest > 0.0) {
                supportedProfiles++;
                strongestTotal += strongest;
            } else {
                emptyProfiles++;
            }
            maxIntensity = Math.max(maxIntensity, strongest);
        }
        double meanStrongest = supportedProfiles == 0 ? 0.0 : strongestTotal / supportedProfiles;
        StringBuilder builder = new StringBuilder("{")
            .append("\"detectorMode\":\"").append(jsonEscape(colorMode)).append("\",")
            .append("\"profileCount\":").append(profiles.size()).append(',')
            .append("\"candidateCount\":").append(colorCandidates.size()).append(',')
            .append("\"supportedProfiles\":").append(supportedProfiles).append(',')
            .append("\"emptyProfiles\":").append(emptyProfiles).append(',')
            .append("\"supportRatio\":").append(profiles.isEmpty() ? 0.0 : (double) supportedProfiles / profiles.size()).append(',')
            .append("\"maxPeaksPerProfile\":").append(maxPeaks).append(',')
            .append("\"maxIntensity\":").append(maxIntensity).append(',')
            .append("\"meanStrongestIntensity\":").append(meanStrongest).append(',')
            .append("\"candidateSummaries\":").append(colorCandidateSummariesJson(colorCandidates)).append(',')
            .append("\"profiles\":[");
        for (int i = 0; i < profiles.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            RenderedHeatmapSampler.CrossSectionProfile profile = profiles.get(i);
            builder.append('{')
                .append("\"index\":").append(i).append(',')
                .append("\"anchorRasterX\":").append(format(profile.anchorScreen().x)).append(',')
                .append("\"anchorRasterY\":").append(format(profile.anchorScreen().y)).append(',')
                .append("\"normalX\":").append(format(profile.normalScreen().x)).append(',')
                .append("\"normalY\":").append(format(profile.normalScreen().y)).append(',')
                .append("\"peaks\":").append(peaksToJson(profile.peaks()))
                .append('}');
        }
        return builder.append("]}").toString();
    }

    private String colorCandidateSummariesJson(List<CenterlineCandidate> candidates) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            CenterlineCandidate candidate = candidates.get(i);
            builder.append('{')
                .append("\"id\":\"").append(jsonEscape(candidate.id())).append("\",")
                .append("\"score\":").append(candidate.score()).append(',')
                .append("\"pointCount\":").append(candidate.screenPoints().size()).append(',')
                .append("\"offsetMinPx\":").append(jsonDouble(min(candidate.offsetsPx()))).append(',')
                .append("\"offsetMaxPx\":").append(jsonDouble(max(candidate.offsetsPx()))).append(',')
                .append("\"offsetMeanPx\":").append(jsonDouble(mean(candidate.offsetsPx()))).append(',')
                .append("\"evidence\":").append(candidate.evidence().toJson())
                .append('}');
        }
        return builder.append(']').toString();
    }

    private String peaksToJson(List<RenderedHeatmapSampler.CrossSectionPeak> peaks) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < peaks.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            RenderedHeatmapSampler.CrossSectionPeak peak = peaks.get(i);
            builder.append('{')
                .append("\"offsetPx\":").append(format(peak.offsetPx())).append(',')
                .append("\"intensity\":").append(format(peak.intensity())).append(',')
                .append("\"supportWidthPx\":").append(format(peak.supportWidthPx())).append(',')
                .append("\"syntheticCenter\":").append(peak.syntheticCenter())
                .append('}');
        }
        return builder.append(']').toString();
    }

    private String renderedZoomSummary(ImageryLayer imageryLayer) {
        if (imageryLayer instanceof AbstractTileSourceLayer<?> tileLayer) {
            return "z" + tileLayer.getZoomLevel() + " (best z" + tileLayer.getBestZoom() + ")";
        }
        return "not a tiled imagery layer";
    }

    private String eastNorthLatLonJson(EastNorth eastNorth, LatLon latLon) {
        if (eastNorth == null || latLon == null) {
            return "null";
        }
        return "{"
            + "\"east\":" + jsonDouble(safeDouble(eastNorth.east())) + ','
            + "\"north\":" + jsonDouble(safeDouble(eastNorth.north())) + ','
            + "\"lat\":" + jsonDouble(safeDouble(latLon.lat())) + ','
            + "\"lon\":" + jsonDouble(safeDouble(latLon.lon()))
            + "}";
    }

    private String projectionBoundsJson(ProjectionBounds bounds) {
        if (bounds == null) {
            return "null";
        }
        return "{"
            + "\"minEast\":" + jsonDouble(safeDouble(bounds.minEast)) + ','
            + "\"minNorth\":" + jsonDouble(safeDouble(bounds.minNorth)) + ','
            + "\"maxEast\":" + jsonDouble(safeDouble(bounds.maxEast)) + ','
            + "\"maxNorth\":" + jsonDouble(safeDouble(bounds.maxNorth))
            + "}";
    }

    private String realBoundsJson(Bounds bounds) {
        if (bounds == null) {
            return "null";
        }
        return "{"
            + "\"minLat\":" + jsonDouble(safeDouble(bounds.getMinLat())) + ','
            + "\"minLon\":" + jsonDouble(safeDouble(bounds.getMinLon())) + ','
            + "\"maxLat\":" + jsonDouble(safeDouble(bounds.getMaxLat())) + ','
            + "\"maxLon\":" + jsonDouble(safeDouble(bounds.getMaxLon()))
            + "}";
    }

    private String estimatedVisibleTilesJson(Bounds bounds, int zoom) {
        if (bounds == null || zoom < 0) {
            return "null";
        }
        int maxTile = (int) Math.pow(2.0, zoom) - 1;
        int x1 = lonToTileX(bounds.getMinLon(), zoom);
        int x2 = lonToTileX(bounds.getMaxLon(), zoom);
        int y1 = latToTileY(bounds.getMinLat(), zoom);
        int y2 = latToTileY(bounds.getMaxLat(), zoom);
        int minX = clamp(Math.min(x1, x2), 0, maxTile);
        int maxX = clamp(Math.max(x1, x2), 0, maxTile);
        int minY = clamp(Math.min(y1, y2), 0, maxTile);
        int maxY = clamp(Math.max(y1, y2), 0, maxTile);
        int count = Math.max(0, maxX - minX + 1) * Math.max(0, maxY - minY + 1);
        return "{"
            + "\"zoom\":" + zoom + ','
            + "\"tileSize\":512,"
            + "\"minX\":" + minX + ','
            + "\"maxX\":" + maxX + ','
            + "\"minY\":" + minY + ','
            + "\"maxY\":" + maxY + ','
            + "\"count\":" + count
            + "}";
    }

    private int lonToTileX(double lon, int zoom) {
        double n = Math.pow(2.0, zoom);
        return (int) Math.floor((lon + 180.0) / 360.0 * n);
    }

    private int latToTileY(double lat, int zoom) {
        double clampedLat = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double n = Math.pow(2.0, zoom);
        double latRad = Math.toRadians(clampedLat);
        return (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String nullableInt(int value) {
        return value < 0 ? "null" : Integer.toString(value);
    }

    private double safeDouble(double value) {
        return Double.isFinite(value) ? value : Double.NaN;
    }

    private String jsonDouble(double value) {
        return Double.isFinite(value) ? format(value) : "null";
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private double min(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
    }

    private double max(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
    }

    private double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
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

    private String candidatesToJson(List<CenterlineCandidate> candidates, ManagedHeatmapConfig config, EffectiveSampling effectiveSampling) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < candidates.size(); i++) {
            CenterlineCandidate candidate = candidates.get(i);
            CandidateMetrics metrics = candidateMetrics(candidate, effectiveSampling);
            if (i > 0) {
                builder.append(',');
            }
            builder.append('{')
                .append("\"id\":\"").append(jsonEscape(candidate.id())).append("\",")
                .append("\"score\":").append(candidate.score()).append(',')
                .append("\"calibratedRankingScore\":").append(jsonDouble(calibratedRankingScore(candidate, config, effectiveSampling))).append(',')
                .append("\"points\":").append(candidate.screenPoints().size()).append(',')
                .append("\"offsetsPx\":").append(doubleArray(candidate.offsetsPx())).append(',')
                .append("\"offsetAbsMeanPx\":").append(jsonDouble(metrics.absMeanOffsetPx())).append(',')
                .append("\"offsetP95DeltaPx\":").append(jsonDouble(metrics.p95DeltaPx())).append(',')
                .append("\"offsetP95AccelerationPx\":").append(jsonDouble(metrics.p95AccelerationPx())).append(',')
                .append("\"offsetP95DeltaReferencePx\":").append(jsonDouble(metrics.p95DeltaReferencePx())).append(',')
                .append("\"offsetP95AccelerationReferencePx\":").append(jsonDouble(metrics.p95AccelerationReferencePx())).append(',')
                .append("\"offsetAbsMeanMeters\":").append(jsonDouble(metrics.absMeanOffsetMeters())).append(',')
                .append("\"offsetP95DeltaMeters\":").append(jsonDouble(metrics.p95DeltaMeters())).append(',')
                .append("\"offsetP95AccelerationMeters\":").append(jsonDouble(metrics.p95AccelerationMeters())).append(',')
                .append("\"offsetSignFlips\":").append(metrics.signFlips()).append(',')
                .append("\"offsetEdgeRatio\":").append(jsonDouble(metrics.edgeRatio())).append(',')
                .append("\"screenPoints\":").append(screenPointArray(candidate.screenPoints())).append(',')
                .append("\"eastNorthPoints\":").append(eastNorthArray(candidate.eastNorthPoints())).append(',')
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

    private String screenPointArray(List<java.awt.geom.Point2D.Double> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            java.awt.geom.Point2D.Double point = values.get(i);
            builder.append("{\"x\":").append(format(point.x)).append(",\"y\":").append(format(point.y)).append('}');
        }
        return builder.append(']').toString();
    }

    private String eastNorthArray(List<EastNorth> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            EastNorth point = values.get(i);
            builder.append("{\"east\":").append(format(point.east())).append(",\"north\":").append(format(point.north())).append('}');
        }
        return builder.append(']').toString();
    }

    private String doubleArray(List<Double> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(String.format(Locale.ROOT, "%.3f", values.get(i)));
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

    private record CandidateMetrics(
        double absMeanOffsetPx,
        double p95DeltaPx,
        double p95AccelerationPx,
        double p95DeltaReferencePx,
        double p95AccelerationReferencePx,
        int signFlips,
        double edgeRatio,
        double absMeanOffsetMeters,
        double p95DeltaMeters,
        double p95AccelerationMeters
    ) {
    }

    private record EffectiveSampling(
        int configuredHalfWidthPx,
        int configuredStepPx,
        int effectiveHalfWidthPx,
        int effectiveStepPx,
        double referenceViewMetersPerPixel,
        double viewMetersPerPixel,
        double targetHalfWidthMeters,
        double targetStepMeters
    ) {
        double referenceRasterMetersPerPixel() {
            return referenceViewMetersPerPixel / RenderedHeatmapSampler.RASTER_SCALE;
        }

        double rasterMetersPerPixel() {
            return viewMetersPerPixel / RenderedHeatmapSampler.RASTER_SCALE;
        }

        double effectiveHalfWidthMeters() {
            return effectiveHalfWidthPx * viewMetersPerPixel;
        }

        double effectiveStepMeters() {
            return effectiveStepPx * viewMetersPerPixel;
        }
    }

    private record DetectionResult(List<CenterlineCandidate> candidates, String profilesJson) {
    }
}
