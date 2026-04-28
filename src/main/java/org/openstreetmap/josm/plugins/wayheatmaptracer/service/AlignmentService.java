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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NodeMove;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;

public final class AlignmentService {
    private static final List<String> ALL_COLOR_MODES = List.of("hot", "blue", "bluered", "purple", "gray");
    private static final double MAX_UNSUPPORTED_FIXED_TURN_DEGREES = 75.0;
    private static final int MAX_INTERNAL_REFINEMENT_PASSES = 0;
    private static final double MIN_REFINEMENT_SCORE_GAIN = 0.75;
    private static final double PARALLEL_MATCH_THRESHOLD_METERS = 18.0;
    private static final double MIN_FIXED_TILE_SUPPORT_RATIO = 0.34;
    private static final double MIN_FIXED_TILE_VALIDATION_SUPPORT_RATIO = 0.12;
    private static final int MAX_FIXED_TILE_CONSECUTIVE_EMPTY = 24;
    private static final double WARN_FIXED_TILE_BOUNDARY_HIT_RATIO = 0.30;
    private static final double HARD_FIXED_TILE_BOUNDARY_HIT_RATIO = 0.85;
    private static final double MAX_LOW_SUPPORT_MEAN_DISPLACEMENT_METERS = 45.0;
    private static final double MAX_LOW_SUPPORT_MAX_DISPLACEMENT_METERS = 110.0;

    private final RenderedHeatmapSampler sampler = new RenderedHeatmapSampler();
    private final TileHeatmapSampler tileSampler = new TileHeatmapSampler();
    private final RidgeTracker ridgeTracker = new RidgeTracker();
    private final PathOptimizer optimizer = new PathOptimizer();
    private final GeometryPostProcessor postProcessor = new GeometryPostProcessor();

    public AlignmentResult align(SelectionContext selection, ImageryLayer imageryLayer, MapView mapView) {
        ManagedHeatmapConfig config = PluginPreferences.load();
        String layerName = imageryLayer == null ? "Managed Strava heatmap source tiles" : imageryLayer.getName();
        PluginLog.verbose("Starting alignment for way %d segment [%d..%d], nodes=%d, fixed=%d, layer='%s'.",
            selection.way().getUniqueId(),
            selection.startIndex(),
            selection.endIndex(),
            selection.segmentNodes().size(),
            selection.fixedNodes().size(),
            layerName);
        PluginLog.verbose("Alignment settings: mode=%s simplify=%s tolerance=%.2f multiColor=%s activity=%s displayColor=%s inferenceZoom=%d validationZoom=%d searchHalfWidth=%.1fm sampleStep=%.1fm.",
            config.alignmentMode(), config.simplifyEnabled(), config.simplifyTolerancePx(),
            config.multiColorDetection(), config.activity(), config.color(), config.inferenceZoom(), config.validationZoom(),
            config.searchHalfWidthMeters(), config.sampleStepMeters());

        long t0 = System.nanoTime();
        List<EastNorth> sourcePolyline = toEastNorth(selection.segmentNodes());
        List<String> colorModes = detectionColorModes(config);
        int halfWidthPx = effectiveHalfWidthPx(selection, config);
        SamplingRun sampling = prepareSamplingRun(selection, imageryLayer, mapView, sourcePolyline, config, colorModes, halfWidthPx);
        long t1 = System.nanoTime();

        List<CenterlineCandidate> candidates;
        try {
            candidates = detectCandidates(selection, sampling, mapView, sourcePolyline, config, colorModes, halfWidthPx);
        } catch (CandidateRejectedException ex) {
            long failedAt = System.nanoTime();
            AlignmentDiagnostics diagnostics = diagnostics(
                layerName,
                ex.candidates().size(),
                0,
                t0,
                t1,
                failedAt,
                failedAt,
                config,
                selection,
                sampling,
                colorModes,
                ex.candidates()
            );
            AlignmentResult partial = new AlignmentResult(
                selection,
                sampling.previewRaster(),
                ex.candidates(),
                sourcePolyline,
                sourcePolyline,
                List.of(),
                diagnostics,
                sampling.fixedTiles()
            );
            throw new AlignmentFailureException(ex.getMessage(), partial, ex);
        }
        if (candidates.isEmpty()) {
            long failedAt = System.nanoTime();
            AlignmentDiagnostics diagnostics = diagnostics(
                layerName,
                0,
                0,
                t0,
                t1,
                failedAt,
                failedAt,
                config,
                selection,
                sampling,
                colorModes,
                candidates
            );
            AlignmentResult partial = new AlignmentResult(
                selection,
                sampling.previewRaster(),
                candidates,
                sourcePolyline,
                sourcePolyline,
                List.of(),
                diagnostics,
                sampling.fixedTiles()
            );
            throw new AlignmentFailureException("No heatmap signal was detected in the sampled corridor.", partial);
        }
        candidates = refineCandidates(selection, sampling, mapView, sourcePolyline, candidates, config, colorModes, halfWidthPx);
        long t2 = System.nanoTime();

        CenterlineCandidate primary = candidates.get(0);
        List<EastNorth> preview = optimize(selection, sourcePolyline, primary, config, mapView);
        List<NodeMove> nodeMoves = interpolateMoves(selection, preview);
        long t3 = System.nanoTime();

        AlignmentDiagnostics diagnostics = diagnostics(
            layerName,
            candidates.size(),
            nodeMoves.size(),
            t0,
            t1,
            t2,
            t3,
            config,
            selection,
            sampling,
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

        return new AlignmentResult(selection, sampling.previewRaster(), candidates, sourcePolyline, preview, nodeMoves, diagnostics, sampling.fixedTiles());
    }

    private SamplingRun prepareSamplingRun(
        SelectionContext selection,
        ImageryLayer imageryLayer,
        MapView mapView,
        List<EastNorth> sourcePolyline,
        ManagedHeatmapConfig config,
        List<String> colorModes,
        int halfWidthPx
    ) {
        if (config.hasManagedAccessValues()) {
            PluginLog.verbose("Using managed fixed-tile source sampling; visible layer, opacity, and HSL settings are ignored.");
            TileHeatmapSampler.TileMosaicSet fixedTiles = tileSampler.prepare(config, sourcePolyline, colorModes);
            String sourceJson = "{\"type\":\"managed-fixed-tiles\",\"samplingZoom\":"
                + fixedTiles.inferenceZoom()
                + ",\"validationZoom\":"
                + fixedTiles.validationZoom()
                + ",\"tileSize\":"
                + TileHeatmapSampler.TILE_SIZE
                + ",\"parameters\":"
                + fixedTiles.inferenceParameters().toJson()
                + ",\"activity\":\""
                + jsonEscape(config.activity())
                + "\",\"displayColor\":\""
                + jsonEscape(config.color())
                + "\"}";
            return new SamplingRun(null, fixedTiles, sourceJson);
        }
        if (imageryLayer == null) {
            throw new IllegalStateException("No heatmap layer is available and managed heatmap access values are incomplete.");
        }
        PluginLog.verbose("Using rendered-layer fallback sampling; result may depend on current map zoom and layer styling.");
        BufferedImage raster = sampler.captureLayer(imageryLayer, mapView);
        return new SamplingRun(raster, null, "{\"type\":\"rendered-layer-fallback\",\"zoomInvariant\":false}");
    }

    private List<CenterlineCandidate> detectCandidates(
        SelectionContext selection,
        SamplingRun sampling,
        MapView mapView,
        List<EastNorth> sourcePolyline,
        ManagedHeatmapConfig config,
        List<String> colorModes,
        int halfWidthPx
    ) {
        List<CenterlineCandidate> candidates = new ArrayList<>();
        for (String colorMode : colorModes) {
            List<RenderedHeatmapSampler.CrossSectionProfile> profiles;
            TileHeatmapSampler.TileMosaic mosaic = null;
            if (sampling.fixedTiles() != null) {
                mosaic = sampling.fixedTiles().require(colorMode);
                profiles = tileSampler.sampleProfiles(mosaic, sourcePolyline, colorMode);
            } else {
                profiles = sampler.sampleProfiles(sampling.previewRaster(), mapView, sourcePolyline, halfWidthPx, config.crossSectionStepPx(), colorMode);
            }
            List<CenterlineCandidate> colorCandidates = ridgeTracker.track(profiles);
            for (CenterlineCandidate candidate : colorCandidates) {
                CenterlineCandidate withMode = candidate
                    .withId(colorMode + "/" + candidate.id())
                    .withEvidence(candidate.evidence().withDetectorMode(colorMode));
                if (mosaic != null) {
                    withMode = withMode.withEastNorthPoints(tileSampler.projectCandidate(mosaic, withMode.screenPoints()));
                }
                candidates.add(withMode);
            }
            PluginLog.verbose("Color mode '%s' produced %d ridge candidates.", colorMode, colorCandidates.size());
        }
        if (config.multiColorDetection()) {
            candidates = applyColorConsensus(candidates);
        }
        if (config.parallelWayAwareness()) {
            candidates = applyParallelWayContext(selection, mapView, candidates);
        }
        candidates = discardNoSignalPlaceholders(candidates);
        if (sampling.fixedTiles() != null) {
            candidates = discardUnsafeFixedTileCandidates(sourcePolyline, candidates, sampling.fixedTiles());
        }
        return candidates.stream()
            .sorted(java.util.Comparator.comparingDouble(CenterlineCandidate::score).reversed())
            .toList();
    }

    private List<CenterlineCandidate> discardNoSignalPlaceholders(List<CenterlineCandidate> candidates) {
        boolean hasSignal = candidates.stream().anyMatch(candidate -> candidate.evidence().hasSignal());
        if (!hasSignal) {
            return List.of();
        }
        return candidates.stream()
            .filter(candidate -> candidate.evidence().hasSignal())
            .toList();
    }

    private List<CenterlineCandidate> discardUnsafeFixedTileCandidates(
        List<EastNorth> sourcePolyline,
        List<CenterlineCandidate> candidates,
        TileHeatmapSampler.TileMosaicSet fixedTiles
    ) {
        List<CenterlineCandidate> previewable = new ArrayList<>();
        List<CenterlineCandidate> classifiedCandidates = new ArrayList<>();
        List<String> hardReasons = new ArrayList<>();
        boolean sketch = sourcePolyline.size() <= 5;
        for (CenterlineCandidate candidate : candidates) {
            CandidateSafety safety = fixedTileCandidateSafety(sourcePolyline, candidate, fixedTiles, sketch);
            PluginLog.verbose("Candidate %s safety: hardRejected=%s reason=%s warnings=%s support=%.3f maxEmpty=%d validationSupport=%.3f boundaryHits=%.3f meanMove=%.1fm maxMove=%.1fm selfIntersect=%s.",
                candidate.id(),
                safety.hardRejected(),
                safety.hardReason(),
                safety.warnings(),
                candidate.evidence().supportRatio(),
                candidate.evidence().maxConsecutiveEmptyProfiles(),
                safety.validationSupportRatio(),
                safety.boundaryHitRatio(),
                safety.meanDisplacementMeters(),
                safety.maxDisplacementMeters(),
                safety.selfIntersecting());
            List<String> warnings = safety.hardRejected()
                ? List.of("rejected: " + safety.hardReason())
                : safety.warnings();
            CenterlineCandidate classified = candidate
                .withScore(candidate.score() + safety.scoreAdjustment())
                .withSafetyWarnings(warnings);
            classifiedCandidates.add(classified);
            if (safety.hardRejected()) {
                hardReasons.add(safety.hardReason());
            } else {
                previewable.add(classified);
            }
        }
        if (previewable.isEmpty() && !candidates.isEmpty()) {
            String summary = rejectionSummary(hardReasons);
            PluginLog.verbose("All fixed-tile ridge candidates were hard-rejected: %s.", summary);
            throw new CandidateRejectedException("Detected ridge candidates were structurally unsafe: " + summary, classifiedCandidates);
        }
        return previewable;
    }

    private CandidateSafety fixedTileCandidateSafety(
        List<EastNorth> sourcePolyline,
        CenterlineCandidate candidate,
        TileHeatmapSampler.TileMosaicSet fixedTiles,
        boolean sketch
    ) {
        if (candidate.eastNorthPoints().size() < 2) {
            return CandidateSafety.hardReject("missing projected centerline");
        }
        if (isSelfIntersecting(candidate.eastNorthPoints())) {
            return CandidateSafety.hardReject("self-intersection");
        }

        TileHeatmapSampler.TileMosaic inferenceMosaic = fixedTiles.require(detectorMode(candidate));
        double boundaryHitRatio = boundaryHitRatio(candidate, inferenceMosaic.parameters());
        if (boundaryHitRatio > HARD_FIXED_TILE_BOUNDARY_HIT_RATIO) {
            return CandidateSafety.hardReject("most samples are pinned to the search edge", boundaryHitRatio);
        }

        List<String> warnings = new ArrayList<>();
        double scoreAdjustment = 0.0;
        if (boundaryHitRatio > WARN_FIXED_TILE_BOUNDARY_HIT_RATIO) {
            warnings.add("near search edge");
            scoreAdjustment -= boundaryHitRatio * 4.0;
        }
        double supportRatio = candidate.evidence().supportRatio();
        if (supportRatio < MIN_FIXED_TILE_SUPPORT_RATIO) {
            warnings.add("low support");
            scoreAdjustment -= (MIN_FIXED_TILE_SUPPORT_RATIO - supportRatio) * 10.0;
        }
        if (candidate.evidence().maxConsecutiveEmptyProfiles() > MAX_FIXED_TILE_CONSECUTIVE_EMPTY) {
            warnings.add("long no-signal gap");
            scoreAdjustment -= Math.min(8.0, (candidate.evidence().maxConsecutiveEmptyProfiles() - MAX_FIXED_TILE_CONSECUTIVE_EMPTY) * 0.20);
        }

        double validationSupportRatio = validationSupportRatio(candidate, fixedTiles);
        if (validationSupportRatio < MIN_FIXED_TILE_VALIDATION_SUPPORT_RATIO && supportRatio < 0.55) {
            warnings.add("weak z" + fixedTiles.validationZoom() + " validation");
            scoreAdjustment -= (MIN_FIXED_TILE_VALIDATION_SUPPORT_RATIO - validationSupportRatio) * 12.0;
        }

        double meanMove = meanNearestDistanceEastNorth(candidate.eastNorthPoints(), sourcePolyline);
        double maxMove = maxNearestDistanceEastNorth(candidate.eastNorthPoints(), sourcePolyline);
        if (!sketch && supportRatio < 0.55
                && (meanMove > MAX_LOW_SUPPORT_MEAN_DISPLACEMENT_METERS
                    || maxMove > MAX_LOW_SUPPORT_MAX_DISPLACEMENT_METERS)) {
            warnings.add("large low-support move");
            scoreAdjustment -= Math.min(10.0, Math.max(0.0, meanMove - MAX_LOW_SUPPORT_MEAN_DISPLACEMENT_METERS) / 10.0);
        }

        double supportBonus = Math.max(0.0, supportRatio - MIN_FIXED_TILE_SUPPORT_RATIO) * 6.0;
        double validationBonus = Math.max(0.0, validationSupportRatio - MIN_FIXED_TILE_VALIDATION_SUPPORT_RATIO) * 4.0;
        return CandidateSafety.previewable(
            warnings,
            boundaryHitRatio,
            validationSupportRatio,
            meanMove,
            maxMove,
            scoreAdjustment + supportBonus + validationBonus
        );
    }

    private double boundaryHitRatio(CenterlineCandidate candidate, TileHeatmapSampler.SamplingParameters parameters) {
        if (candidate.offsetsPx().isEmpty()) {
            return 1.0;
        }
        double edge = parameters.halfWidthPx() - Math.max(1.0, parameters.stepPx() * 0.75);
        long hits = candidate.offsetsPx().stream()
            .filter(offset -> Math.abs(offset) >= edge)
            .count();
        return (double) hits / candidate.offsetsPx().size();
    }

    private double validationSupportRatio(CenterlineCandidate candidate, TileHeatmapSampler.TileMosaicSet fixedTiles) {
        TileHeatmapSampler.TileMosaic validationMosaic = fixedTiles.validation(detectorMode(candidate));
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = tileSampler.sampleProfiles(
            validationMosaic,
            candidate.eastNorthPoints(),
            detectorMode(candidate)
        );
        if (profiles.isEmpty()) {
            return 0.0;
        }
        double centerWindow = Math.max(2.0, validationMosaic.parameters().stepPx() * 1.5);
        int supported = 0;
        for (RenderedHeatmapSampler.CrossSectionProfile profile : profiles) {
            boolean centered = profile.peaks().stream()
                .anyMatch(peak -> Math.abs(peak.offsetPx()) <= centerWindow && peak.intensity() >= 0.12);
            if (centered) {
                supported++;
            }
        }
        return (double) supported / profiles.size();
    }

    private String rejectionSummary(List<String> reasons) {
        if (reasons.isEmpty()) {
            return "no previewable candidates";
        }
        java.util.Map<String, Long> counts = reasons.stream()
            .collect(java.util.stream.Collectors.groupingBy(
                reason -> reason,
                java.util.LinkedHashMap::new,
                java.util.stream.Collectors.counting()
            ));
        return counts.entrySet().stream()
            .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
            .collect(java.util.stream.Collectors.joining(", "));
    }

    private List<CenterlineCandidate> refineCandidates(
        SelectionContext selection,
        SamplingRun sampling,
        MapView mapView,
        List<EastNorth> sourcePolyline,
        List<CenterlineCandidate> initial,
        ManagedHeatmapConfig config,
        List<String> colorModes,
        int halfWidthPx
    ) {
        if (sampling.fixedTiles() != null || MAX_INTERNAL_REFINEMENT_PASSES <= 0) {
            PluginLog.verbose("Internal ridge refinement is disabled for managed fixed-tile sampling; one user run uses the full candidate extraction and validation pass.");
            return initial;
        }
        List<CenterlineCandidate> bestCandidates = initial;
        CenterlineCandidate best = bestCandidates.get(0);
        List<EastNorth> currentAxis = optimizer.projectCandidate(best, mapView);
        for (int pass = 1; pass <= MAX_INTERNAL_REFINEMENT_PASSES; pass++) {
            List<CenterlineCandidate> refined = detectCandidates(selection, sampling, mapView, currentAxis, config, colorModes, halfWidthPx);
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
        if (!ALL_COLOR_MODES.contains(selected)) {
            selected = "hot";
        }
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
            if (!candidate.evidence().hasSignal()) {
                scored.add(candidate);
                continue;
            }
            java.util.Set<String> supportingModes = new java.util.LinkedHashSet<>();
            double supportWeight = 0.0;
            for (CenterlineCandidate other : candidates) {
                if (!other.evidence().hasSignal()) {
                    continue;
                }
                if (averageOffsetDistance(candidate, other) > 7.0) {
                    continue;
                }
                String mode = detectorMode(other);
                if (supportingModes.add(mode)) {
                    supportWeight += detectorConsensusWeight(mode) * Math.max(0.05, other.evidence().signalToNoise());
                }
            }
            if (supportingModes.size() <= 1) {
                scored.add(candidate);
                continue;
            }
            double consensusBonus = supportWeight * 8.0 + supportingModes.size() * 0.6;
            String consensusId = "consensus-" + supportingModes.size() + "/" + candidate.id();
            scored.add(candidate
                .withId(consensusId)
                .withScore(candidate.score() + consensusBonus)
                .withEvidence(candidate.evidence().withConsensusModes(List.copyOf(supportingModes))));
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
        boolean projectedCandidates = candidates.stream().anyMatch(candidate -> !candidate.eastNorthPoints().isEmpty());
        List<List<EastNorth>> parallelWayEastNorth = new ArrayList<>();
        List<List<Point2D.Double>> parallelWayScreens = new ArrayList<>();
        for (Way way : dataSet.getWays()) {
            if (way == selection.way() || way.isDeleted() || way.getNodesCount() < 2 || !isRelevantLinearWay(way)) {
                continue;
            }
            if (projectedCandidates) {
                List<EastNorth> points = wayToEastNorth(way);
                if (points.size() >= 2 && isCloseToAnyCandidateEastNorth(points, candidates)) {
                    parallelWayEastNorth.add(points);
                }
            } else {
                List<Point2D.Double> screen = wayToRasterScreen(way, mapView);
                if (screen.size() >= 2 && isCloseToAnyCandidateScreen(screen, candidates)) {
                    parallelWayScreens.add(screen);
                }
            }
        }
        if (parallelWayEastNorth.isEmpty() && parallelWayScreens.isEmpty()) {
            return candidates;
        }

        boolean sketch = isSketchLikeSelection(selection);
        List<CenterlineCandidate> scored = new ArrayList<>(candidates.size());
        for (CenterlineCandidate candidate : candidates) {
            double nearest = !candidate.eastNorthPoints().isEmpty()
                ? parallelWayEastNorth.stream()
                    .mapToDouble(points -> meanNearestDistanceEastNorth(candidate.eastNorthPoints(), points))
                    .min()
                    .orElse(Double.POSITIVE_INFINITY)
                : parallelWayScreens.stream()
                    .mapToDouble(screen -> meanNearestDistance(candidate.screenPoints(), screen))
                    .min()
                    .orElse(Double.POSITIVE_INFINITY);
            double threshold = !candidate.eastNorthPoints().isEmpty()
                ? PARALLEL_MATCH_THRESHOLD_METERS
                : PARALLEL_MATCH_THRESHOLD_METERS * RenderedHeatmapSampler.RASTER_SCALE;
            if (nearest > threshold) {
                scored.add(candidate);
                continue;
            }
            double penalty = sketch ? 2.0 : 3.5;
            double adjusted = candidate.score() - penalty * (1.0 - nearest / threshold);
            scored.add(candidate.withId(candidate.id() + "/near-mapped-parallel").withScore(adjusted));
        }
        PluginLog.verbose("Parallel-way awareness evaluated %d nearby mapped linear ways.",
            projectedCandidates ? parallelWayEastNorth.size() : parallelWayScreens.size());
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

    private List<EastNorth> wayToEastNorth(Way way) {
        List<EastNorth> points = new ArrayList<>();
        for (Node node : way.getNodes()) {
            if (!node.isUsable()) {
                continue;
            }
            EastNorth point = node.getEastNorth(ProjectionRegistry.getProjection());
            if (point != null) {
                points.add(point);
            }
        }
        return points;
    }

    private boolean isCloseToAnyCandidateScreen(List<Point2D.Double> wayScreen, List<CenterlineCandidate> candidates) {
        for (CenterlineCandidate candidate : candidates) {
            if (meanNearestDistance(candidate.screenPoints(), wayScreen)
                    <= PARALLEL_MATCH_THRESHOLD_METERS * RenderedHeatmapSampler.RASTER_SCALE * 1.6) {
                return true;
            }
        }
        return false;
    }

    private boolean isCloseToAnyCandidateEastNorth(List<EastNorth> wayPoints, List<CenterlineCandidate> candidates) {
        for (CenterlineCandidate candidate : candidates) {
            if (!candidate.eastNorthPoints().isEmpty()
                    && meanNearestDistanceEastNorth(candidate.eastNorthPoints(), wayPoints) <= PARALLEL_MATCH_THRESHOLD_METERS * 1.6) {
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

    private double meanNearestDistanceEastNorth(List<EastNorth> source, List<EastNorth> targetPolyline) {
        if (source.isEmpty() || targetPolyline.size() < 2) {
            return Double.POSITIVE_INFINITY;
        }
        double sum = 0.0;
        for (EastNorth point : source) {
            sum += nearestDistanceToPolylineEastNorth(point, targetPolyline);
        }
        return sum / source.size();
    }

    private double maxNearestDistanceEastNorth(List<EastNorth> source, List<EastNorth> targetPolyline) {
        if (source.isEmpty() || targetPolyline.size() < 2) {
            return Double.POSITIVE_INFINITY;
        }
        double max = 0.0;
        for (EastNorth point : source) {
            max = Math.max(max, nearestDistanceToPolylineEastNorth(point, targetPolyline));
        }
        return max;
    }

    private double nearestDistanceToPolylineEastNorth(EastNorth point, List<EastNorth> polyline) {
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

    private double distanceToSegment(EastNorth point, EastNorth start, EastNorth end) {
        double dx = end.east() - start.east();
        double dy = end.north() - start.north();
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared < 1e-9) {
            return point.distance(start);
        }
        double t = ((point.east() - start.east()) * dx + (point.north() - start.north()) * dy) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        return point.distance(new EastNorth(start.east() + dx * t, start.north() + dy * t));
    }

    private boolean isSelfIntersecting(List<EastNorth> points) {
        if (points.size() < 4) {
            return false;
        }
        for (int i = 1; i < points.size(); i++) {
            EastNorth a1 = points.get(i - 1);
            EastNorth a2 = points.get(i);
            for (int j = i + 2; j < points.size(); j++) {
                EastNorth b1 = points.get(j - 1);
                EastNorth b2 = points.get(j);
                if (segmentsIntersect(a1, a2, b1, b2)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean segmentsIntersect(EastNorth a1, EastNorth a2, EastNorth b1, EastNorth b2) {
        double d1 = direction(a1, a2, b1);
        double d2 = direction(a1, a2, b2);
        double d3 = direction(b1, b2, a1);
        double d4 = direction(b1, b2, a2);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
            && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private double direction(EastNorth a, EastNorth b, EastNorth c) {
        return (c.east() - a.east()) * (b.north() - a.north())
            - (c.north() - a.north()) * (b.east() - a.east());
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

    private AlignmentDiagnostics diagnostics(
        String layerName,
        int candidateCount,
        int nodeMoveCount,
        long t0,
        long t1,
        long t2,
        long t3,
        ManagedHeatmapConfig config,
        SelectionContext selection,
        SamplingRun sampling,
        List<String> colorModes,
        List<CenterlineCandidate> candidates
    ) {
        return new AlignmentDiagnostics(
            layerName,
            candidateCount,
            nodeMoveCount,
            millisBetween(t0, t1),
            millisBetween(t1, t2),
            millisBetween(t2, t3),
            config.toRedactedJson(),
            selectionToJson(selection),
            sampling.sourceJson(),
            stringArray(colorModes),
            candidatesToJson(candidates)
        );
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
        int limit = Math.min(values.size(), 40);
        for (int i = 0; i < limit; i++) {
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

    private record SamplingRun(
        BufferedImage previewRaster,
        TileHeatmapSampler.TileMosaicSet fixedTiles,
        String sourceJson
    ) {
    }

    private record CandidateSafety(
        boolean hardRejected,
        String hardReason,
        List<String> warnings,
        double boundaryHitRatio,
        double validationSupportRatio,
        double meanDisplacementMeters,
        double maxDisplacementMeters,
        boolean selfIntersecting,
        double scoreAdjustment
    ) {
        static CandidateSafety previewable(
            List<String> warnings,
            double boundaryHitRatio,
            double validationSupportRatio,
            double meanDisplacementMeters,
            double maxDisplacementMeters,
            double scoreAdjustment
        ) {
            return new CandidateSafety(false, "", warnings == null ? List.of() : List.copyOf(warnings),
                boundaryHitRatio, validationSupportRatio,
                meanDisplacementMeters, maxDisplacementMeters, false, scoreAdjustment);
        }

        static CandidateSafety hardReject(String reason) {
            return hardReject(reason, 0.0);
        }

        static CandidateSafety hardReject(String reason, double boundaryHitRatio) {
            return new CandidateSafety(true, reason, List.of(), boundaryHitRatio, 0.0,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, "self-intersection".equals(reason), 0.0);
        }
    }

    private static final class CandidateRejectedException extends RuntimeException {
        private final List<CenterlineCandidate> candidates;

        CandidateRejectedException(String message, List<CenterlineCandidate> candidates) {
            super(message);
            this.candidates = List.copyOf(candidates);
        }

        List<CenterlineCandidate> candidates() {
            return candidates;
        }
    }

    public static final class AlignmentFailureException extends IllegalStateException {
        private final AlignmentResult partialResult;

        AlignmentFailureException(String message, AlignmentResult partialResult) {
            super(message);
            this.partialResult = partialResult;
        }

        AlignmentFailureException(String message, AlignmentResult partialResult, Throwable cause) {
            super(message, cause);
            this.partialResult = partialResult;
        }

        public AlignmentResult partialResult() {
            return partialResult;
        }
    }
}
