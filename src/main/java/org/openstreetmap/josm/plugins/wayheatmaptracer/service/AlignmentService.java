package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.image.BufferedImage;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.ProjectionBounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorQuality;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetectorAttempt;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetectorAttemptStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NodeMove;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;

/**
 * Coordinates heatmap capture, ridge detection, preview construction, safety checks, and debug export data.
 *
 * <p>The service has two sampling paths: managed Strava source tiles when credentials are configured, and
 * the legacy rendered-visible-layer path otherwise. Both paths produce the same candidate and diagnostics
 * model so preview, rating, apply, and last-slide export can stay independent of the heatmap source.</p>
 */
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
        "purple-strict",
        "bluered-combined",
        "gray-combined",
        "multi-combined"
    );
    private static final List<String> BASE_SOURCE_COLORS = List.of("hot", "blue", "bluered", "purple", "gray");
    private static final String AGGREGATED_COLOR_MODE = "all-colors-combined";
    private static final double MAX_UNSUPPORTED_FIXED_TURN_DEGREES = 75.0;
    private static final double REFERENCE_VIEW_METERS_PER_PIXEL = TileHeatmapSampler.REFERENCE_VIEW_METERS_PER_PIXEL;
    private static final int MIN_EFFECTIVE_HALF_WIDTH_PX = 6;
    private static final int MAX_EFFECTIVE_HALF_WIDTH_PX = 120;
    private static final int MIN_EFFECTIVE_STEP_PX = 1;
    private static final int MAX_EFFECTIVE_STEP_PX = 32;
    private static final double LOCAL_NODE_SEARCH_FRACTION = 0.08;
    private static final double LOCAL_NODE_SEARCH_METERS = 35.0;
    private static final int MAX_CAPTURE_VIEW_DIMENSION_PX = 5000;
    private static final int MAX_CAPTURE_VIEW_AREA_PX = 3_300_000;
    private static final int MIN_APPLY_SUPPORTED_PROFILES = 2;
    private static final double MIN_APPLY_SUPPORT_RATIO = 0.03;

    private final RenderedHeatmapSampler sampler = new RenderedHeatmapSampler();
    private final TileHeatmapSampler tileSampler = new TileHeatmapSampler();
    private final RidgeTracker ridgeTracker = new RidgeTracker();
    private final CorridorAwareTracker corridorAwareTracker = new CorridorAwareTracker();
    private final ParallelWayContextResolver parallelWayContextResolver = new ParallelWayContextResolver();
    private final CorridorAssignmentService corridorAssignmentService = new CorridorAssignmentService();
    private final PathOptimizer optimizer = new PathOptimizer();
    private final GeometryPostProcessor postProcessor = new GeometryPostProcessor();

    /** Creates an alignment service with the standard sampling and tracking stages. */
    public AlignmentService() {
        // Stage services are initialized in field declarations.
    }

    /**
     * Aligns a selected segment using the persisted plugin settings.
     *
     * @param selection validated selected way segment
     * @param imageryLayer visible fallback heatmap layer, or {@code null} for managed tile sampling
     * @param mapView active JOSM map view
     * @return complete preview result and diagnostics
     */
    public AlignmentResult align(SelectionContext selection, ImageryLayer imageryLayer, MapView mapView) {
        return align(selection, imageryLayer, mapView, PluginPreferences.load());
    }

    /**
     * Aligns a selected segment using explicit settings.
     *
     * @param selection validated selected way segment
     * @param imageryLayer visible fallback heatmap layer, or {@code null} for managed tile sampling
     * @param mapView active JOSM map view
     * @param config immutable settings to use for this slide
     * @return complete preview result and diagnostics
     */
    public AlignmentResult align(
        SelectionContext selection,
        ImageryLayer imageryLayer,
        MapView mapView,
        ManagedHeatmapConfig config
    ) {
        if (useManagedTileAlignment(config)) {
            return alignFromManagedTiles(selection, imageryLayer, mapView, config);
        }
        if (imageryLayer == null) {
            throw new IllegalStateException("No visible heatmap imagery layer was resolved.");
        }
        List<EastNorth> sourcePolyline = toEastNorth(selection.segmentNodes());
        PluginLog.verbose("Starting v0.2-compatible visible-layer alignment for way %d segment [%d..%d], nodes=%d, fixed=%d, layer='%s'.",
            selection.way().getUniqueId(),
            selection.startIndex(),
            selection.endIndex(),
            selection.segmentNodes().size(),
            selection.fixedNodes().size(),
            imageryLayer.getName());
        PluginLog.verbose("Alignment mode=%s simplify=%s tolerance=%.2f alternativeDetectors=%s aggregateAllColors=%s renderedLayerZoom=%s.",
            config.alignmentMode(), config.simplifyEnabled(), config.simplifyTolerancePx(),
            config.multiColorDetection(), config.aggregateAllColorSchemes(), renderedZoomSummary(imageryLayer));
        PluginLog.verbose("Redacted alignment settings: %s", config.toRedactedJson());

        long t0 = System.nanoTime();
        RenderedCapture capture = captureVisibleHeatmap(imageryLayer, mapView, sourcePolyline, config);
        BufferedImage raster = capture.raster();
        long t1 = System.nanoTime();

        List<String> colorModes = detectionColorModes(config);
        EffectiveSampling effectiveSampling = effectiveSampling(config, capture.viewMetersPerPixel());
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
        DetectionResult detection = detectCandidates(raster, capture.sourceRasterPolyline(), capture, selection,
            config, colorModes, effectiveSampling);
        List<CenterlineCandidate> contextualCandidates = applyParallelContext(
            detection.candidates(), selection, sourcePolyline, config);
        List<CenterlineCandidate> candidates = rankCandidates(annotateCandidateSafety(
            contextualCandidates, effectiveSampling, selection, config), config, effectiveSampling);
        List<DetectorAttempt> attempts = detectorAttempts(colorModes, candidates, config,
            detection.outsideRasterProfiles(), false);
        long t2 = System.nanoTime();
        if (detection.outsideRasterProfiles() > 0) {
            AlignmentResult partial = withDetectorState(partialResult(selection, raster, sourcePolyline, imageryLayer, mapView,
                config, colorModes, detection, effectiveSampling, candidates, t0, t1, t2, t2, capture),
                candidates, attempts, List.of());
            throw new AlignmentFailureException(
                "Selected segment is not fully inside the captured heatmap raster ("
                    + detection.outsideRasterProfiles() + "/" + detection.totalProfiles()
                    + " sampled cross-sections outside). The plugin attempted to render the selected extent through JOSM; select a shorter segment if this keeps happening.",
                partial);
        }
        if (candidates.isEmpty()) {
            AlignmentResult partial = withDetectorState(partialResult(selection, raster, sourcePolyline, imageryLayer, mapView,
                config, colorModes, detection, effectiveSampling, List.of(), t0, t1, t2, t2, capture),
                List.of(), attempts, List.of());
            throw new AlignmentFailureException("No stable ridge candidate was detected in the sampled heatmap. "
                + attemptSummary(attempts), partial);
        }
        List<CenterlineCandidate> applicableCandidates = applicableCandidates(candidates);
        CenterlineCandidate primary = applicableCandidates.isEmpty() ? candidates.get(0) : applicableCandidates.get(0);
        List<EastNorth> preview = applicableCandidates.isEmpty()
            ? diagnosticGeometry(primary, sourcePolyline)
            : optimize(selection, sourcePolyline, primary, config, null);
        List<NodeMove> nodeMoves = applicableCandidates.isEmpty() ? List.of() : interpolateMoves(selection, preview);
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
            detection,
            effectiveSampling,
            capture
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

        return new AlignmentResult(selection, raster, candidates, sourcePolyline, preview, nodeMoves, diagnostics,
            null, attempts, applicableCandidates);
    }

    private AlignmentResult alignFromManagedTiles(
        SelectionContext selection,
        ImageryLayer imageryLayer,
        MapView mapView,
        ManagedHeatmapConfig config
    ) {
        List<EastNorth> sourcePolyline = toEastNorth(selection.segmentNodes());
        List<String> colorModes = detectionColorModes(config);
        String sourceColor = normalizedVisibleColor(config);
        PluginLog.verbose("Starting fixed-source-tile alignment for way %d segment [%d..%d], nodes=%d, fixed=%d, sourceColor=%s, modes=%s.",
            selection.way().getUniqueId(),
            selection.startIndex(),
            selection.endIndex(),
            selection.segmentNodes().size(),
            selection.fixedNodes().size(),
            sourceColor,
            colorModes);
        PluginLog.verbose("Redacted alignment settings: %s", config.toRedactedJson());

        long t0 = System.nanoTime();
        TileHeatmapSampler.TileMosaicSet mosaics = tileSampler.prepare(config, sourcePolyline, sourceTileColors(config), isSketchLikeSelection(selection));
        TileHeatmapSampler.TileMosaic mosaic = mosaics.require(sourceColor);
        long t1 = System.nanoTime();
        EffectiveSampling effectiveSampling = fixedTileEffectiveSampling(mosaic);
        DetectionResult detection = detectTileCandidates(mosaics, mosaic, sourcePolyline, selection,
            config, colorModes, effectiveSampling);
        List<String> reportedColorModes = reportedTileColorModes(config, mosaics, colorModes);
        List<CenterlineCandidate> contextualCandidates = applyParallelContext(
            detection.candidates(), selection, sourcePolyline, config);
        List<CenterlineCandidate> candidates = rankCandidates(annotateCandidateSafety(
            contextualCandidates, effectiveSampling, selection, config), config, effectiveSampling);
        List<DetectorAttempt> attempts = detectorAttempts(reportedColorModes, candidates, config,
            detection.outsideRasterProfiles(), shouldRunAggregatedSourceDetector(config, mosaics));
        long t2 = System.nanoTime();
        if (detection.outsideRasterProfiles() > 0) {
            AlignmentResult partial = withDetectorState(partialTileResult(selection, sourcePolyline, imageryLayer, config,
                reportedColorModes, detection, effectiveSampling, mosaics, mosaic, candidates, t0, t1, t2, t2),
                candidates, attempts, List.of());
            throw new AlignmentFailureException(
                "Selected segment is not fully inside the sampled fixed-resolution heatmap mosaic ("
                    + detection.outsideRasterProfiles() + "/" + detection.totalProfiles()
                    + " sampled cross-sections outside).",
                partial);
        }
        if (candidates.isEmpty()) {
            AlignmentResult partial = withDetectorState(partialTileResult(selection, sourcePolyline, imageryLayer, config,
                reportedColorModes, detection, effectiveSampling, mosaics, mosaic, List.of(), t0, t1, t2, t2),
                List.of(), attempts, List.of());
            throw new AlignmentFailureException("No stable ridge candidate was detected in the sampled fixed-resolution heatmap tiles. "
                + attemptSummary(attempts), partial);
        }
        List<CenterlineCandidate> applicableCandidates = applicableCandidates(candidates);
        CenterlineCandidate primary = applicableCandidates.isEmpty() ? candidates.get(0) : applicableCandidates.get(0);
        List<EastNorth> preview = applicableCandidates.isEmpty()
            ? diagnosticGeometry(primary, sourcePolyline)
            : optimize(selection, sourcePolyline, primary, config, mapView);
        List<NodeMove> nodeMoves = applicableCandidates.isEmpty() ? List.of() : interpolateMoves(selection, preview);
        long t3 = System.nanoTime();

        AlignmentDiagnostics diagnostics = tileDiagnostics(
            imageryLayer,
            candidates.size(),
            nodeMoves.size(),
            t0,
            t1,
            t2,
            t3,
            config,
            selection,
            reportedColorModes,
            candidates,
            detection,
            effectiveSampling,
            mosaics,
            mosaic
        );
        PluginLog.verbose("Fixed-source-tile alignment finished: tiles=%d ms ridge=%d ms optimize=%d ms candidates=%d movableNodes=%d.",
            millisBetween(t0, t1), millisBetween(t1, t2), millisBetween(t2, t3), candidates.size(), nodeMoves.size());
        return new AlignmentResult(selection, null, candidates, sourcePolyline, preview, nodeMoves, diagnostics,
            mosaics, attempts, applicableCandidates);
    }

    private boolean useManagedTileAlignment(ManagedHeatmapConfig config) {
        return config != null && config.hasManagedAccessValues();
    }

    private AlignmentResult withDetectorState(
        AlignmentResult base,
        List<CenterlineCandidate> candidates,
        List<DetectorAttempt> attempts,
        List<CenterlineCandidate> applicable
    ) {
        return new AlignmentResult(base.selection(), base.capturedHeatmap(), candidates, base.sourcePolyline(),
            base.previewPolyline(), base.nodeMoves(), base.diagnostics(), base.tileMosaics(), attempts, applicable);
    }

    private List<EastNorth> diagnosticGeometry(
        CenterlineCandidate candidate,
        List<EastNorth> sourcePolyline
    ) {
        return candidate.eastNorthPoints().size() >= 2 ? candidate.eastNorthPoints() : sourcePolyline;
    }

    private String attemptSummary(List<DetectorAttempt> attempts) {
        return "Detector outcomes: " + attempts.stream()
            .map(attempt -> attempt.mappingName() + "=" + attempt.status().name().toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.joining(", ")) + ".";
    }

    private AlignmentResult partialResult(
        SelectionContext selection,
        BufferedImage raster,
        List<EastNorth> sourcePolyline,
        ImageryLayer imageryLayer,
        MapView mapView,
        ManagedHeatmapConfig config,
        List<String> colorModes,
        DetectionResult detection,
        EffectiveSampling effectiveSampling,
        List<CenterlineCandidate> candidates,
        long t0,
        long t1,
        long t2,
        long t3,
        RenderedCapture capture
    ) {
        return new AlignmentResult(
            selection,
            raster,
            candidates,
            sourcePolyline,
            sourcePolyline,
            List.of(),
            diagnostics(imageryLayer, candidates.size(), 0, t0, t1, t2, t3, raster, mapView, config, selection, colorModes,
                candidates, detection, effectiveSampling, capture),
            null
        );
    }

    private AlignmentResult partialTileResult(
        SelectionContext selection,
        List<EastNorth> sourcePolyline,
        ImageryLayer imageryLayer,
        ManagedHeatmapConfig config,
        List<String> colorModes,
        DetectionResult detection,
        EffectiveSampling effectiveSampling,
        TileHeatmapSampler.TileMosaicSet mosaics,
        TileHeatmapSampler.TileMosaic mosaic,
        List<CenterlineCandidate> candidates,
        long t0,
        long t1,
        long t2,
        long t3
    ) {
        return new AlignmentResult(
            selection,
            null,
            candidates,
            sourcePolyline,
            sourcePolyline,
            List.of(),
            tileDiagnostics(imageryLayer, candidates.size(), 0, t0, t1, t2, t3, config, selection, colorModes,
                candidates, detection, effectiveSampling, mosaics, mosaic),
            mosaics
        );
    }

    private DetectionResult detectCandidates(
        BufferedImage raster,
        List<Point2D.Double> sourceRasterPolyline,
        RenderedCapture capture,
        SelectionContext selection,
        ManagedHeatmapConfig config,
        List<String> colorModes,
        EffectiveSampling effectiveSampling
    ) {
        List<CenterlineCandidate> candidates = new ArrayList<>();
        StringBuilder profileDiagnostics = new StringBuilder("[");
        StringBuilder profilePeaksCsv = new StringBuilder(
            "detector,intensity_source,components,profile_index,peak_index,offset_px,intensity,prominence,noise_floor,max_profile_intensity,support_width_px,gradient_strength,gradient_balance,native_filtered_agreement,raw_center_px,b3_center_px,b5_center_px,scale_offset_rms_px,scale_agreement,center_uncertainty_px,filter_kernel,filter_power,filter_blend,strong_signal_gate_floor,strong_signal_gate_width,synthetic_center\n");
        StringBuilder paletteSamplesCsv = new StringBuilder(
            "detector,intensity_source,components,profile_index,anchor_within_raster,strongest_intensity,strongest_prominence,noise_floor,max_profile_intensity,strongest_gradient_strength,strongest_gradient_balance,strongest_scale_agreement,strongest_center_uncertainty_px,peak_count,synthetic_center_count\n");
        StringBuilder profileIntensityCsv = new StringBuilder(
            "detector,profile_index,offset_px,native_intensity,b3_intensity,b5_intensity,normalized_intensity,inside_raster\n");
        StringBuilder corridorBandsCsv = new StringBuilder(
            "detector,profile_index,band_id,parent,center_px,shoulder_min_px,shoulder_max_px,core_min_px,core_max_px,peak_intensity,noise_floor,valley_ratio,gradient_strength,gradient_balance,scale_agreement,existence_confidence,localization_confidence,uncertainty_px,child_ids\n");
        StringBuilder corridorTracksCsv = new StringBuilder(
            "detector,track_id,profile_index,band_id,bridged,parent,child_track_ids,grouping_decision,score,support_ratio,group_left,group_right,common_profiles,common_support_ratio,mean_valley_ratio,common_envelope_ratio\n");
        StringBuilder optimizerCostsCsv = new StringBuilder(
            optimizerCostsCsvHeader());
        StringBuilder scaleSpaceCsv = new StringBuilder(scaleSpaceCsvHeader());
        StringBuilder corridorTubeCsv = new StringBuilder(corridorTubeCsvHeader());
        StringBuilder associationDecisionsCsv = new StringBuilder(associationDecisionsCsvHeader());
        StringBuilder endpointApproachesCsv = new StringBuilder(endpointApproachesCsvHeader());
        int modeIndex = 0;
        IntensitySamplingMode intensitySource = intensitySamplingMode(config);
        int outsideRasterProfiles = 0;
        int totalProfiles = 0;
        for (String colorMode : colorModes) {
            boolean multiScale = trackerMode(config) == TrackerMode.CORRIDOR_AWARE;
            MultiScaleProfileSet profileSet = multiScale
                ? sampler.sampleMultiScaleProfilesOnScaledRaster(raster, sourceRasterPolyline,
                    effectiveSampling.effectiveHalfWidthPx(), effectiveSampling.effectiveStepPx(), colorMode,
                    RenderedHeatmapSampler.RASTER_SCALE, 1.0, intensitySource,
                    effectiveSampling.sourcePixelSizeRasterPx())
                : null;
            List<RenderedHeatmapSampler.CrossSectionProfile> profiles = multiScale
                ? profileSet.levelZeroProfiles()
                : sampler.sampleProfilesOnRaster(raster, sourceRasterPolyline,
                    effectiveSampling.effectiveHalfWidthPx(), effectiveSampling.effectiveStepPx(),
                    colorMode, RenderedHeatmapSampler.RASTER_SCALE, intensitySource);
            if (modeIndex == 0) {
                totalProfiles = profiles.size();
                outsideRasterProfiles = (int) profiles.stream().filter(profile -> !profile.anchorWithinRaster()).count();
            }
            TrackerOutput tracking = multiScale
                ? trackProfiles(profileSet, effectiveSampling, config, selection, colorMode)
                : trackProfiles(profiles, effectiveSampling, config, selection, colorMode);
            List<CenterlineCandidate> colorCandidates = tracking.candidates();
            profileIntensityCsv.append(tracking.profileIntensityCsv());
            corridorBandsCsv.append(tracking.corridorBandsCsv());
            corridorTracksCsv.append(tracking.corridorTracksCsv());
            optimizerCostsCsv.append(tracking.optimizerCostsCsv());
            scaleSpaceCsv.append(tracking.scaleSpaceCsv());
            corridorTubeCsv.append(tracking.corridorTubeCsv());
            associationDecisionsCsv.append(tracking.associationDecisionsCsv());
            endpointApproachesCsv.append(tracking.endpointApproachesCsv());
            appendProfilePeaksCsv(profilePeaksCsv, colorMode, intensitySource, profiles);
            appendPaletteSamplesCsv(paletteSamplesCsv, colorMode, intensitySource, profiles);
            if (modeIndex++ > 0) {
                profileDiagnostics.append(',');
            }
            profileDiagnostics.append(profilesToJson(colorMode, intensitySource, profiles, colorCandidates));
            for (CenterlineCandidate candidate : colorCandidates) {
                CenterlineCandidate withMode = candidate
                    .withId(colorMode + "/" + candidate.id())
                    .withEvidence(candidate.evidence().withDetectorMode(colorMode));
                withMode = withMode.withEastNorthPoints(projectRenderedCandidate(capture, withMode.screenPoints()));
                candidates.add(withMode);
            }
            PluginLog.verbose("Color mode '%s' produced %d ridge candidates.", colorMode, colorCandidates.size());
        }
        java.util.Comparator<CenterlineCandidate> candidateComparator = useCalibratedDetectorRanking(config)
            ? java.util.Comparator
                .comparingDouble((CenterlineCandidate candidate) -> calibratedRankingScore(candidate, config, effectiveSampling))
                .reversed()
                .thenComparing(java.util.Comparator.comparingDouble(CenterlineCandidate::score).reversed())
            : java.util.Comparator.comparingDouble(CenterlineCandidate::score).reversed();
        List<CenterlineCandidate> sorted = candidates.stream().sorted(candidateComparator).toList();
        return new DetectionResult(sorted, profileDiagnostics.append(']').toString(),
            profilePeaksCsv.toString(), paletteSamplesCsv.toString(), profileIntensityCsv.toString(),
            corridorBandsCsv.toString(), corridorTracksCsv.toString(), optimizerCostsCsv.toString(),
            scaleSpaceCsv.toString(), corridorTubeCsv.toString(), associationDecisionsCsv.toString(),
            endpointApproachesCsv.toString(),
            outsideRasterProfiles, totalProfiles);
    }

    private DetectionResult detectTileCandidates(
        TileHeatmapSampler.TileMosaicSet mosaics,
        TileHeatmapSampler.TileMosaic mosaic,
        List<EastNorth> sourcePolyline,
        SelectionContext selection,
        ManagedHeatmapConfig config,
        List<String> colorModes,
        EffectiveSampling effectiveSampling
    ) {
        List<CenterlineCandidate> candidates = new ArrayList<>();
        StringBuilder profileDiagnostics = new StringBuilder("[");
        StringBuilder profilePeaksCsv = new StringBuilder(
            "detector,intensity_source,components,profile_index,peak_index,offset_px,intensity,prominence,noise_floor,max_profile_intensity,support_width_px,gradient_strength,gradient_balance,native_filtered_agreement,raw_center_px,b3_center_px,b5_center_px,scale_offset_rms_px,scale_agreement,center_uncertainty_px,filter_kernel,filter_power,filter_blend,strong_signal_gate_floor,strong_signal_gate_width,synthetic_center\n");
        StringBuilder paletteSamplesCsv = new StringBuilder(
            "detector,intensity_source,components,profile_index,anchor_within_raster,strongest_intensity,strongest_prominence,noise_floor,max_profile_intensity,strongest_gradient_strength,strongest_gradient_balance,strongest_scale_agreement,strongest_center_uncertainty_px,peak_count,synthetic_center_count\n");
        StringBuilder profileIntensityCsv = new StringBuilder(
            "detector,profile_index,offset_px,native_intensity,b3_intensity,b5_intensity,normalized_intensity,inside_raster\n");
        StringBuilder corridorBandsCsv = new StringBuilder(
            "detector,profile_index,band_id,parent,center_px,shoulder_min_px,shoulder_max_px,core_min_px,core_max_px,peak_intensity,noise_floor,valley_ratio,gradient_strength,gradient_balance,scale_agreement,existence_confidence,localization_confidence,uncertainty_px,child_ids\n");
        StringBuilder corridorTracksCsv = new StringBuilder(
            "detector,track_id,profile_index,band_id,bridged,parent,child_track_ids,grouping_decision,score,support_ratio,group_left,group_right,common_profiles,common_support_ratio,mean_valley_ratio,common_envelope_ratio\n");
        StringBuilder optimizerCostsCsv = new StringBuilder(
            optimizerCostsCsvHeader());
        StringBuilder scaleSpaceCsv = new StringBuilder(scaleSpaceCsvHeader());
        StringBuilder corridorTubeCsv = new StringBuilder(corridorTubeCsvHeader());
        StringBuilder associationDecisionsCsv = new StringBuilder(associationDecisionsCsvHeader());
        StringBuilder endpointApproachesCsv = new StringBuilder(endpointApproachesCsvHeader());
        IntensitySamplingMode intensitySource = intensitySamplingMode(config);
        int modeIndex = 0;
        int outsideRasterProfiles = 0;
        int totalProfiles = 0;
        if (shouldRunAggregatedSourceDetector(config, mosaics)) {
            boolean multiScale = trackerMode(config) == TrackerMode.CORRIDOR_AWARE;
            MultiScaleProfileSet profileSet = multiScale
                ? tileSampler.sampleAggregatedMultiScaleProfiles(mosaics, mosaic.zoom(), sourcePolyline)
                : null;
            List<RenderedHeatmapSampler.CrossSectionProfile> profiles = multiScale
                ? profileSet.levelZeroProfiles()
                : tileSampler.sampleAggregatedProfiles(mosaics, mosaic.zoom(), sourcePolyline);
            totalProfiles = profiles.size();
            outsideRasterProfiles = (int) profiles.stream().filter(profile -> !profile.anchorWithinRaster()).count();
            TrackerOutput tracking = multiScale
                ? trackProfiles(profileSet, effectiveSampling, config, selection, AGGREGATED_COLOR_MODE)
                : trackProfiles(profiles, effectiveSampling, config, selection, AGGREGATED_COLOR_MODE);
            List<CenterlineCandidate> colorCandidates = tracking.candidates();
            profileIntensityCsv.append(tracking.profileIntensityCsv());
            corridorBandsCsv.append(tracking.corridorBandsCsv());
            corridorTracksCsv.append(tracking.corridorTracksCsv());
            optimizerCostsCsv.append(tracking.optimizerCostsCsv());
            scaleSpaceCsv.append(tracking.scaleSpaceCsv());
            corridorTubeCsv.append(tracking.corridorTubeCsv());
            associationDecisionsCsv.append(tracking.associationDecisionsCsv());
            endpointApproachesCsv.append(tracking.endpointApproachesCsv());
            appendProfilePeaksCsv(profilePeaksCsv, AGGREGATED_COLOR_MODE, intensitySource, profiles);
            appendPaletteSamplesCsv(paletteSamplesCsv, AGGREGATED_COLOR_MODE, intensitySource, profiles);
            profileDiagnostics.append(profilesToJson(AGGREGATED_COLOR_MODE, intensitySource, profiles, colorCandidates));
            for (CenterlineCandidate candidate : colorCandidates) {
                CenterlineCandidate withMode = candidate
                    .withId(AGGREGATED_COLOR_MODE + "/" + candidate.id())
                    .withEvidence(candidate.evidence().withDetectorMode(AGGREGATED_COLOR_MODE)
                        .withConsensusModes(BASE_SOURCE_COLORS));
                withMode = withMode.withEastNorthPoints(tileSampler.projectCandidate(mosaic, withMode.screenPoints()));
                candidates.add(withMode);
            }
            modeIndex++;
            PluginLog.verbose("Aggregated source color mode '%s' produced %d ridge candidates from colors %s.",
                AGGREGATED_COLOR_MODE, colorCandidates.size(), BASE_SOURCE_COLORS);
        }
        for (String colorMode : colorModes) {
            boolean multiScale = trackerMode(config) == TrackerMode.CORRIDOR_AWARE;
            MultiScaleProfileSet profileSet = multiScale
                ? tileSampler.sampleMultiScaleProfiles(mosaic, sourcePolyline, colorMode, config)
                : null;
            List<RenderedHeatmapSampler.CrossSectionProfile> profiles = multiScale
                ? profileSet.levelZeroProfiles()
                : tileSampler.sampleProfiles(mosaic, sourcePolyline, colorMode, config);
            if (modeIndex == 0) {
                totalProfiles = profiles.size();
                outsideRasterProfiles = (int) profiles.stream().filter(profile -> !profile.anchorWithinRaster()).count();
            }
            TrackerOutput tracking = multiScale
                ? trackProfiles(profileSet, effectiveSampling, config, selection, colorMode)
                : trackProfiles(profiles, effectiveSampling, config, selection, colorMode);
            List<CenterlineCandidate> colorCandidates = tracking.candidates();
            profileIntensityCsv.append(tracking.profileIntensityCsv());
            corridorBandsCsv.append(tracking.corridorBandsCsv());
            corridorTracksCsv.append(tracking.corridorTracksCsv());
            optimizerCostsCsv.append(tracking.optimizerCostsCsv());
            scaleSpaceCsv.append(tracking.scaleSpaceCsv());
            corridorTubeCsv.append(tracking.corridorTubeCsv());
            associationDecisionsCsv.append(tracking.associationDecisionsCsv());
            endpointApproachesCsv.append(tracking.endpointApproachesCsv());
            appendProfilePeaksCsv(profilePeaksCsv, colorMode, intensitySource, profiles);
            appendPaletteSamplesCsv(paletteSamplesCsv, colorMode, intensitySource, profiles);
            if (modeIndex++ > 0) {
                profileDiagnostics.append(',');
            }
            profileDiagnostics.append(profilesToJson(colorMode, intensitySource, profiles, colorCandidates));
            for (CenterlineCandidate candidate : colorCandidates) {
                CenterlineCandidate withMode = candidate
                    .withId(colorMode + "/" + candidate.id())
                    .withEvidence(candidate.evidence().withDetectorMode(colorMode));
                withMode = withMode.withEastNorthPoints(tileSampler.projectCandidate(mosaic, withMode.screenPoints()));
                candidates.add(withMode);
            }
            PluginLog.verbose("Fixed tile color mode '%s' produced %d ridge candidates.", colorMode, colorCandidates.size());
        }
        java.util.Comparator<CenterlineCandidate> candidateComparator = useCalibratedDetectorRanking(config)
            ? java.util.Comparator
                .comparingDouble((CenterlineCandidate candidate) -> calibratedRankingScore(candidate, config, effectiveSampling))
                .reversed()
                .thenComparing(java.util.Comparator.comparingDouble(CenterlineCandidate::score).reversed())
            : java.util.Comparator.comparingDouble(CenterlineCandidate::score).reversed();
        List<CenterlineCandidate> sorted = candidates.stream().sorted(candidateComparator).toList();
        return new DetectionResult(sorted, profileDiagnostics.append(']').toString(),
            profilePeaksCsv.toString(), paletteSamplesCsv.toString(), profileIntensityCsv.toString(),
            corridorBandsCsv.toString(), corridorTracksCsv.toString(), optimizerCostsCsv.toString(),
            scaleSpaceCsv.toString(), corridorTubeCsv.toString(), associationDecisionsCsv.toString(),
            endpointApproachesCsv.toString(),
            outsideRasterProfiles, totalProfiles);
    }

    /**
     * Routes sampled profiles through the configured tracking implementation.
     *
     * <p>Keeping the switch centralized prevents managed-tile and rendered-layer sampling paths
     * from acquiring subtly different tracker defaults.</p>
     *
     * @param profiles sampled cross-sections
     * @param effectiveSampling effective raster and source-pixel scale
     * @param config active alignment settings
     * @param selection selected OSM segment used for endpoint constraints
     * @param detector scalar detector identifier used in diagnostics
     * @return detected centerline candidates
     */
    private TrackerOutput trackProfiles(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        EffectiveSampling effectiveSampling,
        ManagedHeatmapConfig config,
        SelectionContext selection,
        String detector
    ) {
        TrackerMode trackerMode = config.trackerMode() == null ? TrackerMode.LEGACY_V02 : config.trackerMode();
        PluginLog.verbose("Tracking %d profiles with %s.", profiles.size(), trackerMode.name());
        return switch (trackerMode) {
            case LEGACY_V02 -> new TrackerOutput(
                ridgeTracker.track(profiles, effectiveSampling.sourcePixelSizeRasterPx()), "", "", "", "", "",
                "", "", "");
            case CORRIDOR_AWARE -> corridorTrackerOutput(detector, profiles,
                corridorAwareTracker.trackDetailed(profiles, effectiveSampling.sourcePixelSizeRasterPx(),
                    junctionContext(selection, profiles.size(), config, effectiveSampling)));
        };
    }

    private TrackerOutput trackProfiles(
        MultiScaleProfileSet profileSet,
        EffectiveSampling effectiveSampling,
        ManagedHeatmapConfig config,
        SelectionContext selection,
        String detector
    ) {
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = profileSet.levelZeroProfiles();
        PluginLog.verbose("Tracking %d profiles with CORRIDOR_AWARE Gaussian levels=%d.",
            profiles.size(), profileSet.levels().size());
        return corridorTrackerOutput(detector, profiles,
            corridorAwareTracker.trackDetailed(profileSet, effectiveSampling.sourcePixelSizeRasterPx(),
                junctionContext(selection, profiles.size(), config, effectiveSampling)));
    }

    private TrackerMode trackerMode(ManagedHeatmapConfig config) {
        return config.trackerMode() == null ? TrackerMode.LEGACY_V02 : config.trackerMode();
    }

    private TrackerOutput corridorTrackerOutput(
        String detector,
        List<RenderedHeatmapSampler.CrossSectionProfile> sourceProfiles,
        CorridorAwareTracker.TrackingResult result
    ) {
        StringBuilder intensities = new StringBuilder();
        StringBuilder bands = new StringBuilder();
        StringBuilder tracks = new StringBuilder();
        StringBuilder costs = new StringBuilder();
        StringBuilder scaleSpace = new StringBuilder();
        StringBuilder tubeRows = new StringBuilder();
        StringBuilder associationRows = new StringBuilder();
        StringBuilder endpointRows = new StringBuilder();
        for (CorridorProfile profile : result.profiles()) {
            double prominence = Math.max(1e-9, profile.prominence());
            for (RenderedHeatmapSampler.IntensitySample sample : sourceProfiles.get(profile.index()).intensitySamples()) {
                double normalized = Math.max(0.0, Math.min(1.0,
                    (sample.standardFilteredIntensity() - profile.noiseFloor()) / prominence));
                intensities.append(csv(detector)).append(',').append(profile.index()).append(',')
                    .append(sample.offsetPx()).append(',').append(sample.nativeIntensity()).append(',')
                    .append(sample.lightFilteredIntensity()).append(',').append(sample.standardFilteredIntensity()).append(',')
                    .append(normalized).append(',').append(sample.insideRaster()).append('\n');
            }
            for (CorridorBand band : profile.bands()) {
                bands.append(csv(detector)).append(',').append(profile.index()).append(',').append(csv(band.id())).append(',')
                    .append(band.parentHypothesis()).append(',').append(band.centerOffsetPx()).append(',')
                    .append(band.shoulderMinPx()).append(',').append(band.shoulderMaxPx()).append(',')
                    .append(band.coreMinPx()).append(',').append(band.coreMaxPx()).append(',')
                    .append(band.peakIntensity()).append(',').append(band.noiseFloor()).append(',')
                    .append(band.valleyRatio()).append(',').append(band.gradientStrength()).append(',')
                    .append(band.gradientBalance()).append(',').append(band.scaleAgreement()).append(',')
                    .append(band.signalExistenceConfidence()).append(',')
                    .append(band.localizationConfidence()).append(',').append(band.uncertaintyPx()).append(',')
                    .append(csv(String.join(";", band.childIds()))).append('\n');
            }
        }
        for (CorridorTrack track : result.tracks()) {
            List<CorridorTrackPoint> orderedPoints = track.points().values().stream()
                .sorted(java.util.Comparator.comparingInt(CorridorTrackPoint::profileIndex)).toList();
            orderedPoints.forEach(point -> tracks.append(csv(detector)).append(',').append(csv(track.id())).append(',')
                    .append(point.profileIndex()).append(',').append(csv(point.band().id())).append(',')
                    .append(point.bridged()).append(',').append(track.parent()).append(',')
                    .append(csv(String.join(";", track.childTrackIds()))).append(',')
                    .append(csv(track.groupingDecision())).append(',').append(track.score()).append(',')
                    .append(track.supportRatio()).append(",,,,,,\n"));
            for (int pointIndex = 0; pointIndex < orderedPoints.size(); pointIndex++) {
                CorridorTrackPoint point = orderedPoints.get(pointIndex);
                CorridorTrackPoint previous = pointIndex == 0 ? null : orderedPoints.get(pointIndex - 1);
                CorridorTrackPoint beforePrevious = pointIndex < 2 ? null : orderedPoints.get(pointIndex - 2);
                double predicted = predictedAssociationOffset(
                    sourceProfiles, beforePrevious, previous, point);
                double residualSourcePx = Math.abs(point.band().centerOffsetPx() - predicted)
                    / Math.max(1e-9, result.sourcePixelSizePx());
                associationRows.append(csv(detector)).append(',').append(csv(track.id())).append(',')
                    .append(point.profileIndex()).append(',')
                    .append(previous == null ? "" : previous.profileIndex()).append(',')
                    .append(predicted).append(',').append(point.band().centerOffsetPx()).append(',')
                    .append(residualSourcePx).append(',')
                    .append(csv(point.bridged() ? "gap-bridge" : "continue")).append(',')
                    .append(csv(previous == null ? "seed" : point.bridged() ? "compatible-after-gap" : "selected-transition"))
                    .append(',').append(csv(point.band().id())).append(',')
                    .append(point.bridged()).append('\n');
            }
        }
        for (CorridorGrouping.GroupingDecision decision : result.groupingDecisions()) {
            tracks.append(csv(detector)).append(",,,,,,,,,,")
                .append(csv(decision.leftTrackId())).append(',').append(csv(decision.rightTrackId())).append(',')
                .append(decision.commonProfiles()).append(',').append(decision.commonSupportRatio()).append(',')
                .append(decision.meanValleyRatio()).append(',').append(decision.commonEnvelopeRatio()).append('\n');
        }
        result.optimizations().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            double totalCost = entry.getValue().totalCost();
            for (CorridorCenterlineOptimizer.CostRow row : entry.getValue().costs()) {
                costs.append(csv(detector)).append(',').append(csv(entry.getKey())).append(',')
                    .append(row.profileIndex()).append(',').append(row.chosenOffsetPx()).append(',')
                    .append(row.profileSpacingPx()).append(',').append(row.dataCost()).append(',').append(row.continuityCost()).append(',')
                    .append(row.accelerationCost()).append(',').append(row.plateauCenterCost()).append(',')
                    .append(row.coarsePriorCost()).append(',').append(row.tubeCenterCost()).append(',')
                    .append(row.endpointCost()).append(',')
                    .append(row.weightedTotal()).append(',')
                    .append(row.insideCore()).append(',').append(row.insideCorridor()).append(',')
                    .append(totalCost).append(',').append(entry.getValue().maximumOffsetStates()).append(',')
                    .append(entry.getValue().maximumPairStates()).append(',')
                    .append(entry.getValue().transitionEvaluations()).append('\n');
            }
            for (EndpointApproachModel.EndpointApproach approach : entry.getValue().endpointApproaches().approaches()) {
                if (approach.targets().isEmpty()) {
                    endpointRows.append(csv(detector)).append(',').append(csv(entry.getKey())).append(',')
                        .append(approach.constraintProfileIndex()).append(',').append(approach.direction()).append(',')
                        .append(approach.interiorAnchorProfileIndex()).append(',').append(approach.supported()).append(',')
                        .append(csv(approach.reason())).append(",,,,\n");
                }
                for (EndpointApproachModel.GuideTarget target : approach.targets()) {
                    endpointRows.append(csv(detector)).append(',').append(csv(entry.getKey())).append(',')
                        .append(approach.constraintProfileIndex()).append(',').append(approach.direction()).append(',')
                        .append(approach.interiorAnchorProfileIndex()).append(',').append(approach.supported()).append(',')
                        .append(csv(approach.reason())).append(',').append(target.profileIndex()).append(',')
                        .append(target.expectedOffsetPx()).append(',').append(target.positionWeight()).append(',')
                        .append(target.ambiguousHeatmap()).append('\n');
                }
            }
        });
        result.tubes().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            for (CorridorTubeSlice slice : entry.getValue().slices()) {
                tubeRows.append(csv(detector)).append(',').append(csv(entry.getKey())).append(',')
                    .append(slice.profileIndex()).append(',').append(slice.distanceMeters()).append(',')
                    .append(slice.centerOffsetPx()).append(',').append(slice.tangentOffsetPerMeter()).append(',')
                    .append(slice.curvatureOffsetPerMeterSquared()).append(',').append(slice.coreMinPx()).append(',')
                    .append(slice.coreMaxPx()).append(',').append(slice.shoulderMinPx()).append(',')
                    .append(slice.shoulderMaxPx()).append(',').append(slice.uncertaintyPx()).append(',')
                    .append(slice.confidence()).append(',').append(slice.scaleConflict()).append(',')
                    .append(slice.parentMerge()).append(',').append(slice.rawCenterPx()).append(',')
                    .append(slice.lightCenterPx()).append(',').append(slice.standardCenterPx()).append(',')
                    .append(slice.observed()).append('\n');
            }
        });
        for (MultiScaleCorridorProfile profile : result.multiScaleProfiles()) {
            for (ScaleCorridorObservation observation : profile.observations()) {
                if (observation.bands().isEmpty()) {
                    scaleSpace.append(csv(detector)).append(',').append(profile.profileIndex()).append(',')
                        .append(observation.level()).append(',').append(observation.reduction()).append(',')
                        .append(observation.effectiveSigmaL0()).append(',').append(observation.valid())
                        .append(",,,,,,,,,,,,\n");
                }
                for (CorridorBand band : observation.bands()) {
                    BandScaleEvidence evidence = observation.level() == 0
                        ? result.scaleEvidence().get(CorridorCenterlineOptimizer.scaleEvidenceKey(
                            profile.profileIndex(), band.id())) : null;
                    scaleSpace.append(csv(detector)).append(',').append(profile.profileIndex()).append(',')
                        .append(observation.level()).append(',').append(observation.reduction()).append(',')
                        .append(observation.effectiveSigmaL0()).append(',').append(observation.valid()).append(',')
                        .append(csv(band.id())).append(',').append(band.centerOffsetPx()).append(',')
                        .append(band.shoulderMinPx()).append(',').append(band.shoulderMaxPx()).append(',')
                        .append(band.coreMinPx()).append(',').append(band.coreMaxPx()).append(',')
                        .append(evidence == null ? "" : evidence.scalePersistence()).append(',')
                        .append(evidence == null ? "" : evidence.coarseCenterPx()).append(',')
                        .append(evidence == null ? "" : evidence.coarseUncertaintyPx()).append(',')
                        .append(evidence != null && evidence.scaleConflict()).append(',')
                        .append(evidence != null && evidence.parentMerge()).append(',')
                        .append(csv(evidence == null ? "" : evidence.participatingLevels().toString())).append('\n');
                }
            }
        }
        return new TrackerOutput(result.candidates(), intensities.toString(), bands.toString(),
            tracks.toString(), costs.toString(), scaleSpace.toString(), tubeRows.toString(),
            associationRows.toString(), endpointRows.toString());
    }

    private double predictedAssociationOffset(
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
        CorridorTrackPoint beforePrevious,
        CorridorTrackPoint previous,
        CorridorTrackPoint current
    ) {
        if (previous == null || beforePrevious == null) {
            return previous == null ? current.band().centerOffsetPx() : previous.band().centerOffsetPx();
        }
        double previousDistance = profiles.get(beforePrevious.profileIndex()).anchorScreen().distance(
            profiles.get(previous.profileIndex()).anchorScreen());
        if (previousDistance <= 1e-9) {
            return previous.band().centerOffsetPx();
        }
        double currentDistance = profiles.get(previous.profileIndex()).anchorScreen().distance(
            profiles.get(current.profileIndex()).anchorScreen());
        double slope = (previous.band().centerOffsetPx() - beforePrevious.band().centerOffsetPx())
            / previousDistance;
        return previous.band().centerOffsetPx() + slope * currentDistance;
    }

    private String scaleSpaceCsvHeader() {
        return "detector,profile_index,level,reduction,effective_sigma_l0,valid,band_id,center_px,shoulder_min_px,shoulder_max_px,core_min_px,core_max_px,scale_persistence,coarse_center_px,coarse_uncertainty_px,scale_conflict,parent_merge,participating_levels\n";
    }

    private String optimizerCostsCsvHeader() {
        return "detector,track_id,profile_index,chosen_offset_px,profile_spacing_px,data_cost,continuity_cost,acceleration_cost,plateau_center_cost,coarse_prior_cost,tube_center_cost,endpoint_cost,weighted_row_total,inside_core,inside_corridor,total_cost,maximum_offset_states,maximum_pair_states,transition_evaluations\n";
    }

    private String corridorTubeCsvHeader() {
        return "detector,track_id,profile_index,distance_m,center_px,tangent_px_per_m,curvature_px_per_m2,core_min_px,core_max_px,shoulder_min_px,shoulder_max_px,uncertainty_px,confidence,scale_conflict,parent_merge,raw_center_px,b3_center_px,b5_center_px,observed\n";
    }

    private String associationDecisionsCsvHeader() {
        return "detector,track_id,profile_index,previous_profile_index,predicted_offset_px,observed_offset_px,prediction_residual_source_px,decision,reason,band_id,bridged\n";
    }

    private String endpointApproachesCsvHeader() {
        return "detector,track_id,constraint_profile_index,direction,anchor_profile_index,supported,reason,target_profile_index,expected_offset_px,position_weight,ambiguous_heatmap\n";
    }

    private JunctionContext junctionContext(
        SelectionContext selection,
        int profileCount,
        ManagedHeatmapConfig config,
        EffectiveSampling effectiveSampling
    ) {
        if (profileCount <= 0 || selection.segmentNodes().isEmpty()) {
            return JunctionContext.empty();
        }
        List<EastNorth> source = toEastNorth(selection.segmentNodes());
        List<Double> fractions = PolylineMath.fractionsForSegment(source);
        double rasterMeters = effectiveSampling.rasterMetersPerPixel();
        if (!Double.isFinite(rasterMeters) || rasterMeters <= 0.0) {
            rasterMeters = Math.max(0.01, effectiveSampling.sourceMetersPerPixel()
                / effectiveSampling.sourcePixelSizeRasterPx());
        }
        double maxMovableMeters = Math.min(Math.max(0.0, config.searchHalfWidthMeters()), 10.0);
        double maxMovablePx = maxMovableMeters / rasterMeters;
        double profileSpacingMeters = Math.max(0.5, effectiveSampling.effectiveStepMeters());
        int approachProfiles = Math.max(2, (int) Math.round(15.0 / profileSpacingMeters));
        List<EndpointConstraint> constraints = new ArrayList<>();
        for (int nodeIndex = 0; nodeIndex < selection.segmentNodes().size(); nodeIndex++) {
            Node node = selection.segmentNodes().get(nodeIndex);
            boolean endpoint = nodeIndex == 0 || nodeIndex == selection.segmentNodes().size() - 1;
            boolean junction = node.referrers(org.openstreetmap.josm.data.osm.Way.class).count() > 1;
            boolean fixed = selection.fixedNodes().contains(node);
            if (!fixed && !(config.adjustJunctionNodes() && (endpoint || junction))) {
                continue;
            }
            int profileIndex = (int) Math.round(fractions.get(nodeIndex) * (profileCount - 1));
            EndpointConstraint constraint = new EndpointConstraint(profileIndex, node.getUniqueId(), fixed,
                junction, fixed ? 0.0 : maxMovablePx, fixed ? 0.0 : 1.25, approachProfiles);
            int existing = -1;
            for (int i = 0; i < constraints.size(); i++) {
                if (constraints.get(i).profileIndex() == profileIndex) {
                    existing = i;
                    break;
                }
            }
            if (existing < 0 || (fixed && !constraints.get(existing).fixed())) {
                if (existing >= 0) {
                    constraints.set(existing, constraint);
                } else {
                    constraints.add(constraint);
                }
            }
            PluginLog.verbose(
                "Corridor constraint node=%d profile=%d fixed=%s junction=%s maxDisplacement=%.2fm/%.2fpx approachProfiles=%d.",
                node.getUniqueId(), profileIndex, fixed, junction, fixed ? 0.0 : maxMovableMeters,
                fixed ? 0.0 : maxMovablePx, approachProfiles);
        }
        return new JunctionContext(constraints);
    }

    private List<CenterlineCandidate> applyParallelContext(
        List<CenterlineCandidate> candidates,
        SelectionContext selection,
        List<EastNorth> sourcePolyline,
        ManagedHeatmapConfig config
    ) {
        TrackerMode mode = config.trackerMode() == null ? TrackerMode.LEGACY_V02 : config.trackerMode();
        if (mode != TrackerMode.CORRIDOR_AWARE || !config.parallelWayAwareness()) {
            return candidates;
        }
        List<ParallelWayContext> contexts = parallelWayContextResolver.resolve(
            selection, true, config.searchHalfWidthMeters());
        CorridorAssignmentService.AssignmentResult assigned = corridorAssignmentService.assign(
            candidates, selection.way(), sourcePolyline, contexts, config.searchHalfWidthMeters());
        PluginLog.verbose("Parallel-way context resolved %d nearby ways and %d candidate assignment decisions.",
            contexts.size(), assigned.decisions().size());
        return assigned.candidates().stream()
            .sorted(java.util.Comparator.comparingDouble(CenterlineCandidate::score).reversed())
            .toList();
    }

    private double calibratedRankingScore(CenterlineCandidate candidate, ManagedHeatmapConfig config, EffectiveSampling effectiveSampling) {
        String detector = detectorMode(candidate);
        String visibleColor = normalizedVisibleColor(config);
        CandidateMetrics metrics = candidateMetrics(candidate, effectiveSampling);
        double signalReward =
            0.85 * clamp01(candidate.evidence().signalToNoise() / 0.55)
            + 0.25 * clamp01(candidate.evidence().meanIntensity() / 0.75)
            + 0.25 * clamp01(candidate.evidence().supportRatio())
            + 0.12 * clamp01(candidate.evidence().meanGradientStrength() / 0.25)
            + 0.10 * clamp01(candidate.evidence().longitudinalStability());
        double roughnessPenalty =
            0.22 * clamp01(candidate.evidence().ambiguity() / 0.60)
            + 0.20 * clamp01(metrics.p95DeltaReferencePx() / 35.0)
            + 0.20 * clamp01(metrics.p95AccelerationReferencePx() / 35.0)
            + 0.32 * clamp01(metrics.highFrequencyP95SourcePx() / 1.15)
            + 0.18 * clamp01(metrics.subSourceWiggleRatio() / 0.18)
            + 0.10 * clamp01(metrics.signFlips() / 5.0)
            + 0.65 * clamp01(metrics.edgeRatio() / 0.08);
        double noOpPenalty = metrics.absMeanOffsetMeters() < 0.39 && candidate.evidence().meanIntensity() < 0.35 ? 0.80 : 0.0;
        double largeOffsetPenalty = metrics.absMeanOffsetMeters() > 4.54 ? 0.20 : 0.0;
        return detectorPrior(visibleColor, detector)
            + globalDetectorAdjustment(detector)
            + signalReward
            + corridorQualityAdjustment(candidate.evidence().corridorQuality())
            - roughnessPenalty
            - noOpPenalty
            - largeOffsetPenalty;
    }

    double corridorQualityAdjustment(CorridorQuality quality) {
        if (quality == null || !quality.measured()) {
            return 0.0;
        }
        double reward = 0.45 * clamp01(quality.longitudinalPersistence());
        double penalty = 0.45 * clamp01(quality.highFrequencyP95SourcePx() / 0.50)
            + 0.30 * clamp01(quality.p95AccelerationSourcePx() / 1.50)
            + 0.20 * clamp01(quality.tubeResidualP95SourcePx() / 1.00)
            + 0.35 * clamp01(quality.unsupportedExcursions())
            + 0.45 * clamp01(quality.forwardProgressViolations());
        if (!quality.endpointApproachesSupported()) {
            penalty += 0.75;
        }
        return reward - penalty;
    }

    private List<CenterlineCandidate> rankCandidates(
        List<CenterlineCandidate> candidates,
        ManagedHeatmapConfig config,
        EffectiveSampling effectiveSampling
    ) {
        String source = normalizedVisibleColor(config);
        java.util.Comparator<CenterlineCandidate> prefix = java.util.Comparator
            .comparing((CenterlineCandidate candidate) -> isApplicableCandidate(candidate),
                java.util.Comparator.reverseOrder())
            .thenComparing(java.util.Comparator.comparingInt((CenterlineCandidate candidate) ->
                DetectorFamily.sourceTier(source, detectorMode(candidate))).reversed());
        TrackerMode mode = config.trackerMode() == null ? TrackerMode.LEGACY_V02 : config.trackerMode();
        java.util.Comparator<CenterlineCandidate> qualityOrder = mode == TrackerMode.CORRIDOR_AWARE
            ? java.util.Comparator.comparingDouble((CenterlineCandidate candidate) ->
                calibratedRankingScore(candidate, config, effectiveSampling)).reversed()
            : java.util.Comparator.comparingDouble((CenterlineCandidate candidate) ->
                candidate.evidence().scalePersistence()).reversed();
        return candidates.stream().sorted(prefix
            .thenComparing(qualityOrder)
            .thenComparing(java.util.Comparator.comparingDouble((CenterlineCandidate candidate) ->
                candidate.evidence().scalePersistence()).reversed())
            .thenComparing(java.util.Comparator.comparingDouble((CenterlineCandidate candidate) ->
                candidate.evidence().inCorridorFraction()).reversed())
            .thenComparing(java.util.Comparator.comparingDouble((CenterlineCandidate candidate) ->
                candidate.evidence().localizationConfidence()).reversed())
            .thenComparing(java.util.Comparator.comparingDouble((CenterlineCandidate candidate) ->
                candidate.evidence().longitudinalStability()).reversed())
            .thenComparing(java.util.Comparator.comparingDouble((CenterlineCandidate candidate) ->
                detectorPrior(source, detectorMode(candidate))).reversed())
            .thenComparing(java.util.Comparator.comparingDouble((CenterlineCandidate candidate) ->
                calibratedRankingScore(candidate, config, effectiveSampling)).reversed())
            .thenComparing(java.util.Comparator.comparingDouble(CenterlineCandidate::score).reversed())
        ).toList();
    }

    List<DetectorAttempt> detectorAttempts(
        List<String> requestedModes,
        List<CenterlineCandidate> candidates,
        ManagedHeatmapConfig config,
        int outsideRasterProfiles,
        boolean aggregateAvailable
    ) {
        List<String> modes = new ArrayList<>(requestedModes);
        if (config.aggregateAllColorSchemes() && !modes.contains(AGGREGATED_COLOR_MODE)) {
            modes.add(0, AGGREGATED_COLOR_MODE);
        }
        List<DetectorAttempt> attempts = new ArrayList<>();
        for (String mode : modes.stream().distinct().toList()) {
            List<CenterlineCandidate> produced = candidates.stream()
                .filter(candidate -> detectorMode(candidate).equals(mode))
                .toList();
            DetectorAttemptStatus status;
            String reasonCode;
            String reason;
            if (AGGREGATED_COLOR_MODE.equals(mode) && !aggregateAvailable) {
                status = DetectorAttemptStatus.SOURCE_UNAVAILABLE;
                reasonCode = "aggregate-source-incomplete";
                reason = "The complete managed all-color source frame was unavailable.";
            } else if (outsideRasterProfiles > 0) {
                status = DetectorAttemptStatus.OFF_RASTER;
                reasonCode = "profile-off-raster";
                reason = "One or more requested cross-sections were outside the sampled raster.";
            } else if (produced.isEmpty()) {
                status = DetectorAttemptStatus.NO_PERSISTENT_CORRIDOR;
                reasonCode = "no-persistent-corridor";
                reason = "No longitudinally persistent corridor was extracted.";
            } else if (produced.stream().anyMatch(this::isApplicableCandidate)) {
                status = DetectorAttemptStatus.APPLICABLE;
                reasonCode = "applicable";
                reason = "At least one candidate passed signal and structural safety checks.";
            } else if (produced.stream().anyMatch(candidate -> !candidate.safetyWarnings().isEmpty())) {
                status = DetectorAttemptStatus.STRUCTURALLY_UNSAFE;
                reasonCode = "structurally-unsafe";
                reason = produced.stream().flatMap(candidate -> candidate.safetyWarnings().stream())
                    .distinct().collect(java.util.stream.Collectors.joining("; "));
            } else {
                status = DetectorAttemptStatus.INSUFFICIENT_SIGNAL;
                reasonCode = "insufficient-signal";
                reason = "Candidates did not contain enough supported heatmap signal.";
            }
            attempts.add(new DetectorAttempt(normalizedVisibleColor(config), mode, trackerMode(config), status,
                produced.stream().map(CenterlineCandidate::id).toList(), reasonCode, reason));
            PluginLog.verbose("Detector attempt source=%s mapping=%s status=%s candidates=%d reason=%s.",
                normalizedVisibleColor(config), mode, status, produced.size(), reasonCode);
        }
        return List.copyOf(attempts);
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

    static double detectorPrior(String visibleColor, String detector) {
        return switch (visibleColor) {
            case "hot" -> switch (detector) {
                case "all-colors-combined" -> 1.12;
                case "hot-corridor" -> 1.00;
                case "hot" -> 0.65;
                case "dual-corridor" -> 0.55;
                case "dual", "bluered", "gray", "gray-corridor", "gray-magenta" -> 0.25;
                case "blue", "purple", "purple-strict" -> -0.35;
                case "hot-strict" -> 0.0;
                default -> 0.0;
            };
            case "bluered" -> switch (detector) {
                case "all-colors-combined" -> 1.28;
                case "bluered-combined" -> 1.18;
                case "bluered-corridor" -> 1.08;
                case "bluered-cool" -> 1.00;
                case "bluered" -> 0.82;
                case "gray-combined" -> -0.05;
                case "gray-corridor" -> -0.12;
                case "gray", "gray-magenta" -> -0.18;
                case "blue" -> -0.25;
                case "multi-combined" -> -0.55;
                case "dual-corridor", "dual" -> -0.65;
                case "hot-corridor" -> -0.75;
                case "hot" -> -1.10;
                case "hot-strict" -> -1.25;
                case "gray-strict", "purple", "purple-strict" -> -0.70;
                default -> -0.40;
            };
            case "blue" -> switch (detector) {
                case "all-colors-combined" -> 1.08;
                case "dual-corridor" -> 1.00;
                case "hot", "hot-corridor" -> 0.75;
                case "bluered-cool", "bluered-corridor" -> 0.55;
                case "gray", "gray-corridor", "gray-magenta" -> 0.25;
                case "blue" -> 0.20;
                case "purple", "purple-strict" -> -0.35;
                default -> 0.0;
            };
            case "gray" -> switch (detector) {
                case "all-colors-combined" -> 1.16;
                case "gray-combined" -> 1.10;
                case "multi-combined" -> 0.82;
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
                case "all-colors-combined" -> 1.10;
                case "purple" -> 1.05;
                case "purple-strict" -> 0.82;
                case "dual-corridor" -> 0.78;
                case "hot", "hot-corridor" -> 0.60;
                case "bluered-cool", "bluered-corridor", "gray-strict" -> 0.45;
                case "gray", "gray-corridor", "gray-magenta" -> 0.30;
                default -> 0.0;
            };
            default -> switch (detector) {
                case "all-colors-combined" -> 1.00;
                case "multi-combined" -> 0.72;
                case "bluered-combined", "gray-combined" -> 0.62;
                case "hot-corridor", "dual-corridor" -> 0.60;
                case "bluered-corridor", "bluered-cool", "gray-corridor" -> 0.50;
                case "gray-magenta" -> 0.35;
                default -> 0.0;
            };
        };
    }

    private double globalDetectorAdjustment(String detector) {
        return switch (detector) {
            case "all-colors-combined" -> 0.30;
            case "bluered-combined", "gray-combined" -> 0.22;
            case "multi-combined" -> 0.18;
            case "hot-corridor" -> 0.25;
            case "dual-corridor" -> 0.20;
            case "bluered-corridor" -> 0.15;
            case "bluered-cool", "gray-corridor" -> 0.10;
            case "gray-magenta" -> 0.05;
            case "purple" -> 0.05;
            case "hot-strict" -> -0.35;
            case "gray-strict" -> -0.30;
            case "purple-strict" -> -0.08;
            default -> 0.0;
        };
    }

    private CandidateMetrics candidateMetrics(CenterlineCandidate candidate, EffectiveSampling effectiveSampling) {
        List<Double> offsets = candidate.offsetsPx();
        if (offsets.isEmpty()) {
            return new CandidateMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, effectiveSampling.sourceMetersPerPixel());
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
        List<Double> highFrequencyResiduals = highFrequencyResiduals(offsets, 7);
        double highFrequencyP95 = percentileAbs(highFrequencyResiduals, 0.95);
        double normalization = effectiveSampling.rasterMetersPerPixel() / effectiveSampling.referenceRasterMetersPerPixel();
        double sourcePixelSize = effectiveSampling.sourcePixelSizeRasterPx();
        double edgeLimit = effectiveSampling.effectiveHalfWidthPx() * effectiveSampling.rasterScale() * 0.90;
        long edgeCount = offsets.stream().filter(offset -> Math.abs(offset) >= edgeLimit).count();
        int subSourceWiggles = subSourceWiggles(offsets, highFrequencyResiduals, sourcePixelSize);
        return new CandidateMetrics(
            absMean,
            p95Delta,
            p95Acceleration,
            highFrequencyP95,
            p95Delta * normalization,
            p95Acceleration * normalization,
            sourcePixelSize <= 0.0 ? 0.0 : p95Delta / sourcePixelSize,
            sourcePixelSize <= 0.0 ? 0.0 : p95Acceleration / sourcePixelSize,
            sourcePixelSize <= 0.0 ? 0.0 : highFrequencyP95 / sourcePixelSize,
            signFlips(deltas, effectiveSampling),
            offsets.isEmpty() ? 0.0 : (double) subSourceWiggles / offsets.size(),
            offsets.isEmpty() ? 0.0 : (double) edgeCount / offsets.size(),
            absMean * effectiveSampling.rasterMetersPerPixel(),
            p95Delta * effectiveSampling.rasterMetersPerPixel(),
            p95Acceleration * effectiveSampling.rasterMetersPerPixel(),
            highFrequencyP95 * effectiveSampling.rasterMetersPerPixel(),
            effectiveSampling.sourceMetersPerPixel()
        );
    }

    private List<Double> highFrequencyResiduals(List<Double> offsets, int window) {
        if (offsets.size() < 3) {
            return List.of();
        }
        int radius = Math.max(1, window / 2);
        List<Double> residuals = new ArrayList<>();
        for (int i = 0; i < offsets.size(); i++) {
            int start = Math.max(0, i - radius);
            int end = Math.min(offsets.size() - 1, i + radius);
            double total = 0.0;
            for (int j = start; j <= end; j++) {
                total += offsets.get(j);
            }
            residuals.add(offsets.get(i) - total / (end - start + 1));
        }
        return residuals;
    }

    private int subSourceWiggles(List<Double> offsets, List<Double> residuals, double sourcePixelSizePx) {
        if (offsets.size() < 3 || residuals.size() != offsets.size() || sourcePixelSizePx <= 0.0) {
            return 0;
        }
        double minimumDelta = Math.max(3.0, sourcePixelSizePx * 0.12);
        double maximumDelta = sourcePixelSizePx * 1.25;
        double residualThreshold = Math.max(4.0, sourcePixelSizePx * 0.20);
        int count = 0;
        for (int i = 1; i < offsets.size() - 1; i++) {
            double left = offsets.get(i) - offsets.get(i - 1);
            double right = offsets.get(i + 1) - offsets.get(i);
            if (Math.abs(left) >= minimumDelta
                && Math.abs(right) >= minimumDelta
                && Math.abs(left) <= maximumDelta
                && Math.abs(right) <= maximumDelta
                && Math.signum(left) != Math.signum(right)
                && Math.abs(residuals.get(i)) >= residualThreshold) {
                count++;
            }
        }
        return count;
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

    List<String> detectionColorModes(ManagedHeatmapConfig config) {
        IntensitySamplingMode source = intensitySamplingMode(config);
        if (!source.usesColorMapping()) {
            return List.of(source.detectorName());
        }
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

    List<String> sourceTileColors(ManagedHeatmapConfig config) {
        String selected = normalizedVisibleColor(config);
        if (!shouldPrepareAggregateSourceColors(config)) {
            return List.of(selected);
        }
        List<String> colors = new ArrayList<>();
        colors.add(selected);
        for (String color : BASE_SOURCE_COLORS) {
            if (!colors.contains(color)) {
                colors.add(color);
            }
        }
        return colors;
    }

    private boolean shouldRunAggregatedSourceDetector(ManagedHeatmapConfig config) {
        return config.aggregateAllColorSchemes() && intensitySamplingMode(config).usesColorMapping();
    }

    private boolean shouldPrepareAggregateSourceColors(ManagedHeatmapConfig config) {
        return (config.aggregateAllColorSchemes() || config.showAggregateIntensityLayer())
            && intensitySamplingMode(config).usesColorMapping();
    }

    private boolean shouldRunAggregatedSourceDetector(ManagedHeatmapConfig config, TileHeatmapSampler.TileMosaicSet mosaics) {
        return shouldRunAggregatedSourceDetector(config)
            && BASE_SOURCE_COLORS.stream().allMatch(color -> mosaics.mosaics().containsKey(color + "@" + mosaics.inferenceZoom()));
    }

    private List<String> reportedTileColorModes(
        ManagedHeatmapConfig config,
        TileHeatmapSampler.TileMosaicSet mosaics,
        List<String> colorModes
    ) {
        if (!shouldRunAggregatedSourceDetector(config, mosaics)) {
            return colorModes;
        }
        List<String> reported = new ArrayList<>();
        reported.add(AGGREGATED_COLOR_MODE);
        reported.addAll(colorModes);
        return reported;
    }

    private boolean useCalibratedDetectorRanking(ManagedHeatmapConfig config) {
        return config.multiColorDetection() || shouldRunAggregatedSourceDetector(config);
    }

    private IntensitySamplingMode intensitySamplingMode(ManagedHeatmapConfig config) {
        return config.intensitySamplingMode() == null ? IntensitySamplingMode.COLOR_MAPPING : config.intensitySamplingMode();
    }

    /**
     * Rebuilds a preview for a selected candidate using persisted settings.
     *
     * @param base original slide result
     * @param candidate candidate selected in the preview dialog
     * @return result containing the candidate-specific preview and node moves
     */
    public AlignmentResult applyCandidate(AlignmentResult base, CenterlineCandidate candidate) {
        return applyCandidate(base, candidate, PluginPreferences.load());
    }

    /**
     * Rebuilds a preview for a selected candidate using explicit settings.
     *
     * @param base original slide result
     * @param candidate candidate selected in the preview dialog
     * @param config immutable settings used to create candidate geometry
     * @return result containing the candidate-specific preview and node moves
     */
    public AlignmentResult applyCandidate(AlignmentResult base, CenterlineCandidate candidate, ManagedHeatmapConfig config) {
        if (!isApplicableCandidate(candidate)) {
            if (!candidate.safetyWarnings().isEmpty()) {
                throw new IllegalStateException("Selected ridge is structurally unsafe for safe alignment: "
                    + String.join("; ", candidate.safetyWarnings()));
            }
            throw new IllegalStateException("Selected ridge does not contain enough heatmap signal for safe alignment.");
        }
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
            base.tileMosaics(),
            base.detectorAttempts(),
            base.applicableCandidates()
        );
    }

    private List<CenterlineCandidate> applicableCandidates(List<CenterlineCandidate> candidates) {
        List<CenterlineCandidate> result = candidates.stream()
            .filter(this::isApplicableCandidate)
            .toList();
        if (result.size() != candidates.size()) {
            PluginLog.verbose("Rejected %d detected ridge candidates without enough heatmap signal or structurally safe continuity.",
                candidates.size() - result.size());
        }
        return result;
    }

    private boolean isApplicableCandidate(CenterlineCandidate candidate) {
        if (!candidate.safetyWarnings().isEmpty()) {
            return false;
        }
        if (!candidate.evidence().hasSignal()) {
            return false;
        }
        if (candidate.evidence().supportedProfiles() >= MIN_APPLY_SUPPORTED_PROFILES) {
            return true;
        }
        return candidate.evidence().supportRatio() >= MIN_APPLY_SUPPORT_RATIO;
    }

    private List<CenterlineCandidate> annotateCandidateSafety(
        List<CenterlineCandidate> candidates,
        EffectiveSampling effectiveSampling,
        SelectionContext selection,
        ManagedHeatmapConfig config
    ) {
        return candidates.stream()
            .map(candidate -> candidate.withSafetyWarnings(candidateSafetyWarnings(candidate, effectiveSampling, selection, config)))
            .toList();
    }

    private List<String> candidateSafetyWarnings(
        CenterlineCandidate candidate,
        EffectiveSampling effectiveSampling,
        SelectionContext selection,
        ManagedHeatmapConfig config
    ) {
        CandidateMetrics metrics = candidateMetrics(candidate, effectiveSampling);
        List<String> warnings = new ArrayList<>();
        double unsafeAccelerationMeters = Math.max(8.0, effectiveSampling.targetHalfWidthMeters() * 0.18);
        double unsafeDeltaMeters = Math.max(12.0, effectiveSampling.targetHalfWidthMeters() * 0.40);
        if (metrics.highFrequencyP95SourcePx() > 1.65 && metrics.subSourceWiggleRatio() > 0.28) {
            warnings.add(String.format(java.util.Locale.ROOT,
                "source-resolution aliasing wiggles %.1fpx", metrics.highFrequencyP95SourcePx()));
        }
        if (metrics.p95AccelerationMeters() > unsafeAccelerationMeters) {
            warnings.add(String.format(java.util.Locale.ROOT,
                "abrupt lateral acceleration %.1fm", metrics.p95AccelerationMeters()));
        }
        if (metrics.p95DeltaMeters() > unsafeDeltaMeters) {
            warnings.add(String.format(java.util.Locale.ROOT,
                "abrupt lateral jump %.1fm", metrics.p95DeltaMeters()));
        }
        if (metrics.edgeRatio() >= 0.20) {
            warnings.add(String.format(java.util.Locale.ROOT,
                "too many samples near search edge %.0f%%", metrics.edgeRatio() * 100.0));
        }
        TrackerMode trackerMode = config.trackerMode() == null ? TrackerMode.LEGACY_V02 : config.trackerMode();
        if (trackerMode == TrackerMode.CORRIDOR_AWARE) {
            warnings.addAll(corridorQualityWarnings(candidate.evidence().corridorQuality()));
            if (crossesConnectedWayBeforeJunction(candidate, selection,
                junctionIntersectionToleranceMeters(effectiveSampling))) {
                warnings.add("crosses a connected way before its junction");
            }
        }
        return warnings;
    }

    List<String> corridorQualityWarnings(CorridorQuality quality) {
        if (quality == null || !quality.measured()) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        if (quality.forwardProgressViolations() > 0) {
            warnings.add("candidate folds backward along the selected way");
        }
        if (quality.unsupportedExcursions() > 0) {
            warnings.add(String.format(java.util.Locale.ROOT,
                "unsupported short lateral excursions %d", quality.unsupportedExcursions()));
        }
        if (quality.p95AccelerationSourcePx() > 2.0) {
            warnings.add(String.format(java.util.Locale.ROOT,
                "source-normalized lateral acceleration %.1fpx", quality.p95AccelerationSourcePx()));
        }
        if (quality.endpointApproachMaximumTurnDegrees() > 35.0
            && quality.tubeResidualP95SourcePx() > 0.5) {
            warnings.add(String.format(java.util.Locale.ROOT,
                "unsupported terminal turn %.0f degrees", quality.endpointApproachMaximumTurnDegrees()));
        }
        return List.copyOf(warnings);
    }

    boolean crossesConnectedWayBeforeJunction(CenterlineCandidate candidate, SelectionContext selection) {
        return crossesConnectedWayBeforeJunction(candidate, selection, 2.5);
    }

    private boolean crossesConnectedWayBeforeJunction(
        CenterlineCandidate candidate,
        SelectionContext selection,
        double junctionToleranceMeters
    ) {
        List<EastNorth> geometry = candidate.eastNorthPoints();
        if (geometry.size() < 2) {
            return false;
        }
        for (Node junction : selection.segmentNodes()) {
            List<Way> connected = junction.referrers(Way.class)
                .filter(way -> way != selection.way() && !way.isDeleted())
                .toList();
            if (connected.isEmpty()) {
                continue;
            }
            EastNorth junctionPoint = junction.getEastNorth(ProjectionRegistry.getProjection());
            int nearest = nearestPointIndex(geometry, junctionPoint);
            int start = Math.max(0, nearest - 6);
            int end = Math.min(geometry.size() - 2, nearest + 5);
            for (Way way : connected) {
                for (int candidateIndex = start; candidateIndex <= end; candidateIndex++) {
                    for (int wayIndex = 0; wayIndex + 1 < way.getNodesCount(); wayIndex++) {
                        if (way.getNode(wayIndex) != junction && way.getNode(wayIndex + 1) != junction) {
                            continue;
                        }
                        EastNorth connectedStart = way.getNode(wayIndex).getEastNorth(
                            ProjectionRegistry.getProjection());
                        EastNorth connectedEnd = way.getNode(wayIndex + 1).getEastNorth(
                            ProjectionRegistry.getProjection());
                        if (connectedStart == null || connectedEnd == null) {
                            continue;
                        }
                        EastNorth intersection = segmentIntersection(
                            geometry.get(candidateIndex), geometry.get(candidateIndex + 1),
                            connectedStart, connectedEnd);
                        if (intersection != null
                            && intersection.distance(junctionPoint) > junctionToleranceMeters) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private double junctionIntersectionToleranceMeters(EffectiveSampling effectiveSampling) {
        double samplingTolerance = Math.max(effectiveSampling.effectiveStepMeters() * 1.5,
            effectiveSampling.sourceMetersPerPixel() * 2.0);
        return Math.max(1.0, Math.min(5.0, samplingTolerance));
    }

    private int nearestPointIndex(List<EastNorth> geometry, EastNorth target) {
        int bestIndex = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < geometry.size(); i++) {
            double distance = geometry.get(i).distance(target);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private EastNorth segmentIntersection(EastNorth a, EastNorth b, EastNorth c, EastNorth d) {
        double denominator = (b.east() - a.east()) * (d.north() - c.north())
            - (b.north() - a.north()) * (d.east() - c.east());
        if (Math.abs(denominator) < 1e-9) {
            return null;
        }
        double ua = ((d.east() - c.east()) * (a.north() - c.north())
            - (d.north() - c.north()) * (a.east() - c.east())) / denominator;
        double ub = ((b.east() - a.east()) * (a.north() - c.north())
            - (b.north() - a.north()) * (a.east() - c.east())) / denominator;
        if (ua <= 1e-9 || ua >= 1.0 - 1e-9 || ub <= 1e-9 || ub >= 1.0 - 1e-9) {
            return null;
        }
        return new EastNorth(a.east() + ua * (b.east() - a.east()),
            a.north() + ua * (b.north() - a.north()));
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

        List<EastNorth> sourcePolyline = toEastNorth(selection.segmentNodes());
        List<EastNorth> samples;
        if (preview.size() == selection.segmentNodes().size()) {
            samples = preview;
            PluginLog.debug("Applying node moves directly from preview points without resampling.");
        } else {
            List<Double> sourceFractions = PolylineMath.fractionsForSegment(sourcePolyline);
            List<Double> previewFractions = PolylineMath.fractionsForSegment(preview);
            double previewLength = PolylineMath.length(preview);
            double window = Math.max(LOCAL_NODE_SEARCH_FRACTION, LOCAL_NODE_SEARCH_METERS / Math.max(1.0, previewLength));
            samples = new ArrayList<>(selection.segmentNodes().size());
            for (int i = 0; i < selection.segmentNodes().size(); i++) {
                samples.add(PolylineMath.closestPointNearFraction(
                    preview,
                    previewFractions,
                    sourcePolyline.get(i),
                    sourceFractions.get(i),
                    window
                ).point());
            }
            PluginLog.debug("Mapped %d source nodes to local preview projections from %d preview points for node move diagnostics.",
                selection.segmentNodes().size(), preview.size());
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
                PluginLog.verbose("Simplification enabled: precise-shape intervals will be simplified after fixed anchors are restored.");
            } else {
                PluginLog.verbose("Simplification disabled; using raw traced centerline.");
            }
        }
        List<EastNorth> preview = switch (config.alignmentMode()) {
            case MOVE_EXISTING_NODES -> moveExistingNodesPreview(selection, sourcePolyline, working);
            case PRECISE_SHAPE -> preciseShapePreview(selection, sourcePolyline, working);
        };
        if (config.alignmentMode() == AlignmentMode.PRECISE_SHAPE
            && config.simplifyEnabled()
            && !config.adjustJunctionNodes()) {
            preview = simplifyPrecisePreview(selection, sourcePolyline, preview, config.simplifyTolerancePx());
        }
        preview = guardFixedAnchorTurns(selection, sourcePolyline, preview, config.alignmentMode());
        return cleanPreviewTopology(selection, sourcePolyline, preview, config.alignmentMode());
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

    /**
     * Reports whether a selection is a rough full-way sketch with two to five nodes.
     *
     * @param selection selected way segment
     * @return {@code true} for a two-to-five-node full-way selection
     */
    public static boolean isSketchLikeSelection(SelectionContext selection) {
        return selection.isFullWaySelection()
            && selection.segmentNodes().size() >= 2
            && selection.segmentNodes().size() <= 5;
    }

    /**
     * Resolves the alignment mode for a selection without silently changing rough sketches.
     *
     * @param selection selected way segment
     * @param config active settings
     * @return configured alignment mode
     */
    public static AlignmentMode effectiveAlignmentMode(SelectionContext selection, ManagedHeatmapConfig config) {
        return config.alignmentMode();
    }

    private List<EastNorth> moveExistingNodesPreview(SelectionContext selection, List<EastNorth> sourcePolyline, List<EastNorth> working) {
        List<Double> sourceFractions = PolylineMath.fractionsForSegment(sourcePolyline);
        List<Double> centerlineFractions = PolylineMath.fractionsForSegment(working);
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
            EastNorth projected = PolylineMath.interpolateAtFraction(working, centerlineFractions, fraction);
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
        List<Double> sourceFractions = PolylineMath.fractionsForSegment(sourcePolyline);
        List<Double> workingFractions = PolylineMath.fractionsForSegment(working);
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

    private List<EastNorth> simplifyPrecisePreview(
        SelectionContext selection,
        List<EastNorth> sourcePolyline,
        List<EastNorth> preview,
        double tolerance
    ) {
        if (preview.size() <= 2 || tolerance <= 0.0) {
            return preview;
        }
        List<Integer> previewAnchors = new ArrayList<>();
        for (int fixedIndex : fixedIndices(selection)) {
            int previewIndex = findMatchingPoint(preview, sourcePolyline.get(fixedIndex));
            if (previewIndex >= 0 && !previewAnchors.contains(previewIndex)) {
                previewAnchors.add(previewIndex);
            }
        }
        previewAnchors.sort(Integer::compareTo);
        if (previewAnchors.isEmpty() || previewAnchors.get(0) != 0) {
            previewAnchors.add(0, 0);
        }
        int last = preview.size() - 1;
        if (previewAnchors.get(previewAnchors.size() - 1) != last) {
            previewAnchors.add(last);
        }

        List<EastNorth> simplifiedPreview = new ArrayList<>();
        for (int i = 0; i < previewAnchors.size() - 1; i++) {
            int start = previewAnchors.get(i);
            int end = previewAnchors.get(i + 1);
            if (end <= start) {
                continue;
            }
            List<EastNorth> section = new ArrayList<>(preview.subList(start, end + 1));
            List<EastNorth> simplifiedSection = postProcessor.simplify(section, tolerance);
            if (!simplifiedPreview.isEmpty() && !simplifiedSection.isEmpty()) {
                simplifiedSection = new ArrayList<>(simplifiedSection.subList(1, simplifiedSection.size()));
            }
            simplifiedPreview.addAll(simplifiedSection);
        }
        if (simplifiedPreview.size() < 2) {
            return preview;
        }
        PluginLog.verbose("Precise-shape interval simplification: %d -> %d points with tolerance %.2f.",
            preview.size(), simplifiedPreview.size(), tolerance);
        return simplifiedPreview;
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

    private List<EastNorth> cleanPreviewTopology(
        SelectionContext selection,
        List<EastNorth> sourcePolyline,
        List<EastNorth> preview,
        AlignmentMode mode
    ) {
        if (preview.size() < 4 || mode != AlignmentMode.PRECISE_SHAPE) {
            return preview;
        }
        List<EastNorth> protectedPoints = fixedIndices(selection).stream()
            .map(sourcePolyline::get)
            .toList();
        double nearAnchorDistance = Math.max(3.0, Math.min(12.0, PolylineMath.length(sourcePolyline) * 0.08));
        List<EastNorth> cleaned = postProcessor.pruneEndpointClusters(preview, nearAnchorDistance, 95.0);
        cleaned = postProcessor.removeSelfIntersectionLoops(cleaned, protectedPoints);
        if (cleaned.size() < 2) {
            return preview;
        }
        if (cleaned.size() != preview.size()) {
            PluginLog.verbose("Topology cleanup reduced precise preview points from %d to %d.", preview.size(), cleaned.size());
        }
        return cleaned;
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

    private RenderedCapture captureVisibleHeatmap(
        ImageryLayer imageryLayer,
        MapView mapView,
        List<EastNorth> sourcePolyline,
        ManagedHeatmapConfig config
    ) {
        ProjectionBounds originalBounds = mapView.getProjectionBounds();
        EastNorth originalCenter = mapView.getCenter();
        double originalScale = mapView.getScale();
        Dimension originalSize = mapView.getSize();
        ProjectionBounds requestedBounds = expandedBounds(sourcePolyline, visibleCaptureMarginMeters(config));
        double targetScale = REFERENCE_VIEW_METERS_PER_PIXEL;
        Dimension captureSize = requiredCaptureSize(requestedBounds, targetScale);
        ProjectionBounds captureBounds = captureBoundsForSize(requestedBounds.getCenter(), captureSize, targetScale);
        VisibleCaptureState captureState = new VisibleCaptureState(originalCenter, originalScale, originalBounds,
            originalSize, requestedBounds, captureBounds, captureSize, targetScale, false, 1);
        try {
            BufferedImage raster;
            if (captureSize.width <= MAX_CAPTURE_VIEW_DIMENSION_PX && captureSize.height <= MAX_CAPTURE_VIEW_DIMENSION_PX) {
                captureState = captureState.withCapturePlan(false, 1);
                mapView.setSize(captureSize);
                mapView.zoomTo(captureBounds.getCenter(), targetScale, false);
                raster = sampler.captureLayer(imageryLayer, mapView);
            } else {
                raster = captureLayerInChunks(imageryLayer, mapView, captureBounds, captureSize, targetScale, originalSize);
                int chunkCount = chunkCount(captureSize, chunkSize(originalSize));
                captureState = captureState.withCapturePlan(true, chunkCount);
            }
            return new RenderedCapture(
                raster,
                captureBounds,
                targetScale,
                sourcePolyline.stream().map(point -> toCaptureRasterPoint(point, captureBounds, targetScale)).toList(),
                captureState
            );
        } finally {
            mapView.setSize(originalSize);
            mapView.zoomTo(originalCenter, originalScale, false);
            PluginLog.verbose("Restored JOSM map view after heatmap capture.");
        }
    }

    private BufferedImage captureLayerInChunks(
        ImageryLayer imageryLayer,
        MapView mapView,
        ProjectionBounds captureBounds,
        Dimension captureSize,
        double targetScale,
        Dimension originalSize
    ) {
        Dimension chunkSize = chunkSize(originalSize);
        BufferedImage mosaic = new BufferedImage(
            (int) Math.round(captureSize.width * RenderedHeatmapSampler.RASTER_SCALE),
            (int) Math.round(captureSize.height * RenderedHeatmapSampler.RASTER_SCALE),
            BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D graphics = mosaic.createGraphics();
        int chunks = 0;
        try {
            for (int y = 0; y < captureSize.height; y += chunkSize.height) {
                int viewHeight = Math.min(chunkSize.height, captureSize.height - y);
                for (int x = 0; x < captureSize.width; x += chunkSize.width) {
                    int viewWidth = Math.min(chunkSize.width, captureSize.width - x);
                    mapView.setSize(new Dimension(viewWidth, viewHeight));
                    EastNorth center = new EastNorth(
                        captureBounds.minEast + (x + viewWidth / 2.0) * targetScale,
                        captureBounds.maxNorth - (y + viewHeight / 2.0) * targetScale
                    );
                    mapView.zoomTo(center, targetScale, false);
                    BufferedImage chunk = sampler.captureLayer(imageryLayer, mapView);
                    graphics.drawImage(chunk,
                        (int) Math.round(x * RenderedHeatmapSampler.RASTER_SCALE),
                        (int) Math.round(y * RenderedHeatmapSampler.RASTER_SCALE),
                        null);
                    chunks++;
                }
            }
        } finally {
            graphics.dispose();
        }
        PluginLog.verbose("Rendered heatmap extent in %d JOSM viewport chunks at %.3f m/px.", chunks, targetScale);
        return mosaic;
    }

    private ProjectionBounds expandedBounds(List<EastNorth> points, double marginMeters) {
        if (points.isEmpty()) {
            return new ProjectionBounds();
        }
        double minEast = Double.POSITIVE_INFINITY;
        double minNorth = Double.POSITIVE_INFINITY;
        double maxEast = Double.NEGATIVE_INFINITY;
        double maxNorth = Double.NEGATIVE_INFINITY;
        for (EastNorth point : points) {
            minEast = Math.min(minEast, point.east());
            minNorth = Math.min(minNorth, point.north());
            maxEast = Math.max(maxEast, point.east());
            maxNorth = Math.max(maxNorth, point.north());
        }
        double margin = Math.max(5.0, marginMeters);
        return new ProjectionBounds(minEast - margin, minNorth - margin, maxEast + margin, maxNorth + margin);
    }

    private double visibleCaptureMarginMeters(ManagedHeatmapConfig config) {
        double halfWidthMeters = config.crossSectionHalfWidthPx() * REFERENCE_VIEW_METERS_PER_PIXEL;
        return Math.max(halfWidthMeters * 2.0, halfWidthMeters + 20.0);
    }

    private Dimension requiredCaptureSize(ProjectionBounds bounds, double scale) {
        int width = (int) Math.ceil(Math.max(1.0, bounds.maxEast - bounds.minEast) / scale);
        int height = (int) Math.ceil(Math.max(1.0, bounds.maxNorth - bounds.minNorth) / scale);
        if ((long) width * (long) height > MAX_CAPTURE_VIEW_AREA_PX) {
            throw new IllegalStateException(
                "Selected segment is too large to render at the required heatmap resolution in one slide. "
                    + "Select a shorter segment or use managed source-tile alignment.");
        }
        return new Dimension(width, height);
    }

    private Dimension chunkSize(Dimension originalSize) {
        int width = Math.max(320, Math.min(MAX_CAPTURE_VIEW_DIMENSION_PX, originalSize == null ? 1200 : originalSize.width));
        int height = Math.max(240, Math.min(MAX_CAPTURE_VIEW_DIMENSION_PX, originalSize == null ? 800 : originalSize.height));
        return new Dimension(width, height);
    }

    private int chunkCount(Dimension captureSize, Dimension chunkSize) {
        int x = (int) Math.ceil((double) captureSize.width / chunkSize.width);
        int y = (int) Math.ceil((double) captureSize.height / chunkSize.height);
        return Math.max(1, x * y);
    }

    private ProjectionBounds captureBoundsForSize(EastNorth center, Dimension size, double scale) {
        double halfWidth = size.width * scale / 2.0;
        double halfHeight = size.height * scale / 2.0;
        return new ProjectionBounds(
            center.east() - halfWidth,
            center.north() - halfHeight,
            center.east() + halfWidth,
            center.north() + halfHeight
        );
    }

    private Point2D.Double toCaptureRasterPoint(EastNorth point, ProjectionBounds bounds, double viewMetersPerPixel) {
        return new Point2D.Double(
            (point.east() - bounds.minEast) / viewMetersPerPixel * RenderedHeatmapSampler.RASTER_SCALE,
            (bounds.maxNorth - point.north()) / viewMetersPerPixel * RenderedHeatmapSampler.RASTER_SCALE
        );
    }

    private List<EastNorth> projectRenderedCandidate(RenderedCapture capture, List<Point2D.Double> rasterPoints) {
        return rasterPoints.stream()
            .map(point -> new EastNorth(
                capture.bounds().minEast + point.x / RenderedHeatmapSampler.RASTER_SCALE * capture.viewMetersPerPixel(),
                capture.bounds().maxNorth - point.y / RenderedHeatmapSampler.RASTER_SCALE * capture.viewMetersPerPixel()
            ))
            .toList();
    }

    private EffectiveSampling effectiveSampling(ManagedHeatmapConfig config, double viewMetersPerPixel) {
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
            targetStepMeters,
            RenderedHeatmapSampler.RASTER_SCALE,
            effectiveViewMetersPerPixel
        );
    }

    private EffectiveSampling fixedTileEffectiveSampling(TileHeatmapSampler.TileMosaic mosaic) {
        double targetHalfWidthMeters = mosaic.parameters().halfWidthMeters();
        double targetStepMeters = mosaic.parameters().sampleStepMeters();
        int referenceHalfWidthPx = Math.max(1, (int) Math.round(targetHalfWidthMeters / REFERENCE_VIEW_METERS_PER_PIXEL));
        int referenceStepPx = Math.max(1, (int) Math.round(targetStepMeters / REFERENCE_VIEW_METERS_PER_PIXEL));
        return new EffectiveSampling(
            referenceHalfWidthPx,
            referenceStepPx,
            referenceHalfWidthPx,
            Math.max(1, Math.min(referenceStepPx, Math.max(1, referenceHalfWidthPx))),
            REFERENCE_VIEW_METERS_PER_PIXEL,
            REFERENCE_VIEW_METERS_PER_PIXEL,
            targetHalfWidthMeters,
            targetStepMeters,
            TileHeatmapSampler.REFERENCE_RASTER_SCALE,
            mosaic.parameters().metersPerPixel()
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
        DetectionResult detection,
        EffectiveSampling effectiveSampling,
        RenderedCapture capture
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
            samplingJson(imageryLayer, raster, mapView, effectiveSampling, capture),
            stringArray(colorModes),
            candidatesToJson(candidates, config, effectiveSampling),
            detection.profilesJson() == null || detection.profilesJson().isBlank() ? "[]" : detection.profilesJson(),
            candidateMetricsCsv(candidates, config, effectiveSampling),
            detection.profilePeaksCsv() == null ? "" : detection.profilePeaksCsv(),
            detection.paletteSamplesCsv() == null ? "" : detection.paletteSamplesCsv(),
            detection.profileIntensityCsv(),
            detection.corridorBandsCsv(),
            detection.corridorTracksCsv(),
            detection.optimizerCostsCsv(),
            detection.scaleSpaceCsv(),
            detection.corridorTubeCsv(),
            detection.associationDecisionsCsv(),
            detection.endpointApproachesCsv(),
            parallelContextJson(selection, candidates, config)
        );
    }

    private AlignmentDiagnostics tileDiagnostics(
        ImageryLayer imageryLayer,
        int candidateCount,
        int nodeMoveCount,
        long t0,
        long t1,
        long t2,
        long t3,
        ManagedHeatmapConfig config,
        SelectionContext selection,
        List<String> colorModes,
        List<CenterlineCandidate> candidates,
        DetectionResult detection,
        EffectiveSampling effectiveSampling,
        TileHeatmapSampler.TileMosaicSet mosaics,
        TileHeatmapSampler.TileMosaic mosaic
    ) {
        String layerName = imageryLayer == null ? "Managed Strava source tiles" : imageryLayer.getName();
        return new AlignmentDiagnostics(
            layerName,
            candidateCount,
            nodeMoveCount,
            millisBetween(t0, t1),
            millisBetween(t1, t2),
            millisBetween(t2, t3),
            config.toRedactedJson(),
            selectionToJson(selection),
            tileSamplingJson(mosaics, mosaic, effectiveSampling),
            stringArray(colorModes),
            candidatesToJson(candidates, config, effectiveSampling),
            detection.profilesJson() == null || detection.profilesJson().isBlank() ? "[]" : detection.profilesJson(),
            candidateMetricsCsv(candidates, config, effectiveSampling),
            detection.profilePeaksCsv() == null ? "" : detection.profilePeaksCsv(),
            detection.paletteSamplesCsv() == null ? "" : detection.paletteSamplesCsv(),
            detection.profileIntensityCsv(),
            detection.corridorBandsCsv(),
            detection.corridorTracksCsv(),
            detection.optimizerCostsCsv(),
            detection.scaleSpaceCsv(),
            detection.corridorTubeCsv(),
            detection.associationDecisionsCsv(),
            detection.endpointApproachesCsv(),
            parallelContextJson(selection, candidates, config)
        );
    }

    private String parallelContextJson(
        SelectionContext selection,
        List<CenterlineCandidate> candidates,
        ManagedHeatmapConfig config
    ) {
        TrackerMode trackerMode = config.trackerMode() == null ? TrackerMode.LEGACY_V02 : config.trackerMode();
        if (trackerMode != TrackerMode.CORRIDOR_AWARE || !config.parallelWayAwareness()) {
            return "{\"enabled\":false,\"ways\":[],\"assignments\":[]}";
        }
        List<EastNorth> source = toEastNorth(selection.segmentNodes());
        List<ParallelWayContext> contexts = parallelWayContextResolver.resolve(
            selection, true, config.searchHalfWidthMeters());
        CorridorAssignmentService.AssignmentResult assignment = corridorAssignmentService.assign(
            candidates, selection.way(), source, contexts, config.searchHalfWidthMeters());
        StringBuilder builder = new StringBuilder("{\"enabled\":true,\"ways\":[");
        for (int i = 0; i < contexts.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            ParallelWayContext context = contexts.get(i);
            builder.append("{\"wayId\":").append(context.wayId())
                .append(",\"meanDistanceMeters\":").append(context.meanDistanceMeters())
                .append(",\"directionAgreement\":").append(context.directionAgreement())
                .append(",\"side\":").append(context.side())
                .append(",\"overlapRatio\":").append(context.overlapRatio())
                .append(",\"tags\":{");
            int tagIndex = 0;
            for (Map.Entry<String, String> tag : context.tags().entrySet()) {
                if (tagIndex++ > 0) {
                    builder.append(',');
                }
                builder.append('\"').append(jsonEscape(tag.getKey())).append("\":\"")
                    .append(jsonEscape(tag.getValue())).append('\"');
            }
            builder.append("}}");
        }
        builder.append("],\"assignments\":[");
        for (int i = 0; i < assignment.decisions().size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            CorridorAssignmentService.AssignmentDecision decision = assignment.decisions().get(i);
            builder.append("{\"candidateId\":\"").append(jsonEscape(decision.candidateId())).append("\",")
                .append("\"sourceDistanceMeters\":").append(decision.sourceDistanceMeters()).append(',')
                .append("\"reservationPenaltyMeters\":").append(decision.reservationPenaltyMeters()).append(',')
                .append("\"normalizedCost\":").append(decision.normalizedCost()).append(',')
                .append("\"reservedByWayIds\":").append(longArray(decision.reservedByWayIds())).append('}');
        }
        return builder.append("]}").toString();
    }

    private String longArray(List<Long> values) {
        return values.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private String tileSamplingJson(
        TileHeatmapSampler.TileMosaicSet mosaics,
        TileHeatmapSampler.TileMosaic mosaic,
        EffectiveSampling effectiveSampling
    ) {
        return "{"
            + "\"type\":\"managed-source-tiles\","
            + "\"algorithm\":\"fixed-scale source tiles\","
            + "\"tileZoom\":" + mosaic.zoom() + ','
            + "\"bestTileZoom\":" + mosaics.inferenceZoom() + ','
            + "\"sourceTileZoom\":" + mosaic.zoom() + ','
            + "\"bestSourceTileZoom\":" + mosaics.inferenceZoom() + ','
            + "\"rasterScale\":" + effectiveSampling.rasterScale() + ','
            + "\"rasterWidth\":" + mosaic.image().getWidth() + ','
            + "\"rasterHeight\":" + mosaic.image().getHeight() + ','
            + "\"sourceMetersPerPixel\":" + jsonDouble(mosaic.parameters().metersPerPixel()) + ','
            + "\"sourcePixelSizeRasterPx\":" + jsonDouble(effectiveSampling.sourcePixelSizeRasterPx()) + ','
            + "\"virtualRasterScale\":" + jsonDouble(mosaic.virtualRasterScale()) + ','
            + "\"viewMetersPerPixel\":" + jsonDouble(effectiveSampling.viewMetersPerPixel()) + ','
            + "\"rasterMetersPerPixel\":" + jsonDouble(effectiveSampling.rasterMetersPerPixel()) + ','
            + "\"referenceViewMetersPerPixel\":" + jsonDouble(effectiveSampling.referenceViewMetersPerPixel()) + ','
            + "\"configuredHalfWidthPx\":" + effectiveSampling.configuredHalfWidthPx() + ','
            + "\"configuredStepPx\":" + effectiveSampling.configuredStepPx() + ','
            + "\"effectiveHalfWidthPx\":" + effectiveSampling.effectiveHalfWidthPx() + ','
            + "\"effectiveStepPx\":" + effectiveSampling.effectiveStepPx() + ','
            + "\"targetHalfWidthMeters\":" + jsonDouble(effectiveSampling.targetHalfWidthMeters()) + ','
            + "\"targetStepMeters\":" + jsonDouble(effectiveSampling.targetStepMeters()) + ','
            + "\"effectiveHalfWidthMeters\":" + jsonDouble(effectiveSampling.effectiveHalfWidthMeters()) + ','
            + "\"effectiveStepMeters\":" + jsonDouble(effectiveSampling.effectiveStepMeters()) + ','
            + "\"tileManifest\":" + mosaics.manifestJson()
            + "}";
    }

    private String samplingJson(
        ImageryLayer imageryLayer,
        BufferedImage raster,
        MapView mapView,
        EffectiveSampling effectiveSampling,
        RenderedCapture capture
    ) {
        int zoom = -1;
        int bestZoom = -1;
        if (imageryLayer instanceof AbstractTileSourceLayer<?> tileLayer) {
            zoom = tileLayer.getZoomLevel();
            bestZoom = tileLayer.getBestZoom();
        }
        VisibleCaptureState captureState = capture == null ? null : capture.state();
        int viewWidth = captureState == null ? (mapView == null ? 0 : mapView.getWidth()) : captureState.captureSize().width;
        int viewHeight = captureState == null ? (mapView == null ? 0 : mapView.getHeight()) : captureState.captureSize().height;
        double viewMetersPerPixel = capture == null ? currentViewMetersPerPixel(mapView) : capture.viewMetersPerPixel();
        double dist100Pixel = viewMetersPerPixel > 0.0 ? viewMetersPerPixel * 100.0 : Double.NaN;
        double rasterMetersPerPixel = viewMetersPerPixel > 0.0 ? viewMetersPerPixel / effectiveSampling.rasterScale() : Double.NaN;
        double mapScale = capture == null ? safeDouble(mapView == null ? Double.NaN : mapView.getScale()) : capture.viewMetersPerPixel();
        EastNorth center = capture == null ? (mapView == null ? null : mapView.getCenter()) : capture.bounds().getCenter();
        LatLon centerLatLon = center == null ? null : ProjectionRegistry.getProjection().eastNorth2latlon(center);
        ProjectionBounds projectionBounds = capture == null ? (mapView == null ? null : mapView.getProjectionBounds()) : capture.bounds();
        Bounds realBounds = projectionBoundsToRealBounds(projectionBounds);
        return "{"
            + "\"type\":\"rendered-visible-layer\","
            + "\"algorithm\":\"v0.2-compatible\","
            + "\"layerClass\":\"" + jsonEscape(imageryLayer.getClass().getName()) + "\","
            + "\"tileZoom\":" + nullableInt(zoom) + ','
            + "\"bestTileZoom\":" + nullableInt(bestZoom) + ','
            + "\"sourceTileZoom\":" + nullableInt(zoom) + ','
            + "\"bestSourceTileZoom\":" + nullableInt(bestZoom) + ','
            + "\"rasterScale\":" + effectiveSampling.rasterScale() + ','
            + "\"rasterWidth\":" + (raster == null ? 0 : raster.getWidth()) + ','
            + "\"rasterHeight\":" + (raster == null ? 0 : raster.getHeight()) + ','
            + "\"viewWidthPx\":" + viewWidth + ','
            + "\"viewHeightPx\":" + viewHeight + ','
            + "\"dist100PixelMeters\":" + jsonDouble(dist100Pixel) + ','
            + "\"sourceMetersPerPixel\":" + jsonDouble(effectiveSampling.sourceMetersPerPixel()) + ','
            + "\"sourcePixelSizeRasterPx\":" + jsonDouble(effectiveSampling.sourcePixelSizeRasterPx()) + ','
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
            + "\"viewportAdjustedForSelection\":" + (captureState != null && captureState.adjusted()) + ','
            + "\"chunkedCapture\":" + (captureState != null && captureState.chunked()) + ','
            + "\"captureChunkCount\":" + (captureState == null ? 1 : captureState.chunkCount()) + ','
            + "\"requestedCaptureBounds\":" + projectionBoundsJson(captureState == null ? null : captureState.requestedBounds()) + ','
            + "\"captureProjectionBounds\":" + projectionBoundsJson(capture == null ? null : capture.bounds()) + ','
            + "\"originalProjectionBounds\":" + projectionBoundsJson(captureState == null ? null : captureState.originalBounds()) + ','
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
        IntensitySamplingMode intensitySource,
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
            .append("\"intensitySource\":\"").append(jsonEscape(intensitySource.name())).append("\",")
            .append("\"profileCount\":").append(profiles.size()).append(',')
            .append("\"outsideRasterProfiles\":").append(profiles.stream().filter(profile -> !profile.anchorWithinRaster()).count()).append(',')
            .append("\"candidateCount\":").append(colorCandidates.size()).append(',')
            .append("\"supportedProfiles\":").append(supportedProfiles).append(',')
            .append("\"emptyProfiles\":").append(emptyProfiles).append(',')
            .append("\"supportRatio\":").append(profiles.isEmpty() ? 0.0 : (double) supportedProfiles / profiles.size()).append(',')
            .append("\"maxPeaksPerProfile\":").append(maxPeaks).append(',')
            .append("\"maxIntensity\":").append(maxIntensity).append(',')
            .append("\"meanStrongestIntensity\":").append(meanStrongest).append(',')
            .append("\"combinedIntensity\":").append(RenderedHeatmapSampler.isCombinedColorMode(colorMode)).append(',')
            .append("\"intensityComponents\":").append(intensityComponentsJson(colorMode)).append(',')
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
                .append("\"anchorWithinRaster\":").append(profile.anchorWithinRaster()).append(',')
                .append("\"peaks\":").append(peaksToJson(profile.peaks()))
                .append('}');
        }
        return builder.append("]}").toString();
    }

    private String intensityComponentsJson(String colorMode) {
        StringBuilder builder = new StringBuilder("[");
        List<RenderedHeatmapSampler.IntensityComponent> components = RenderedHeatmapSampler.intensityComponents(colorMode);
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            RenderedHeatmapSampler.IntensityComponent component = components.get(i);
            builder.append('{')
                .append("\"mode\":\"").append(jsonEscape(component.mode())).append("\",")
                .append("\"weight\":").append(format(component.weight()))
                .append('}');
        }
        return builder.append(']').toString();
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
                .append(',')
                .append("\"prominence\":").append(format(peak.prominence())).append(',')
                .append("\"noiseFloor\":").append(format(peak.noiseFloor())).append(',')
                .append("\"maxProfileIntensity\":").append(format(peak.maxProfileIntensity()))
                .append(',')
                .append("\"gradientStrength\":").append(format(peak.gradientStrength())).append(',')
                .append("\"gradientBalance\":").append(format(peak.gradientBalance())).append(',')
                .append("\"nativeFilteredAgreement\":").append(format(peak.nativeFilteredAgreement())).append(',')
                .append("\"rawCenterPx\":").append(format(peak.rawCenterPx())).append(',')
                .append("\"b3CenterPx\":").append(format(peak.lightFilteredCenterPx())).append(',')
                .append("\"b5CenterPx\":").append(format(peak.standardFilteredCenterPx())).append(',')
                .append("\"scaleOffsetRmsPx\":").append(format(peak.scaleOffsetRmsPx())).append(',')
                .append("\"scaleAgreement\":").append(format(peak.scaleAgreement())).append(',')
                .append("\"centerUncertaintyPx\":").append(format(peak.centerUncertaintyPx())).append(',')
                .append("\"filterKernel\":\"raw+B3+B5\",")
                .append("\"filterPower\":\"2.0 strong/1.25 weak\",")
                .append("\"filterBlend\":\"B3 0.45/0.30/0.15; B5 0.35/0.25/0.10\",")
                .append("\"signalGateFloor\":\"0.18*max\",")
                .append("\"signalGateWidth\":\"max(0.08,0.42*max)\"")
                .append('}');
        }
        return builder.append(']').toString();
    }

    private void appendProfilePeaksCsv(
        StringBuilder builder,
        String colorMode,
        IntensitySamplingMode intensitySource,
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles
    ) {
        String components = intensityComponentsCsv(colorMode);
        for (int profileIndex = 0; profileIndex < profiles.size(); profileIndex++) {
            List<RenderedHeatmapSampler.CrossSectionPeak> peaks = profiles.get(profileIndex).peaks();
            for (int peakIndex = 0; peakIndex < peaks.size(); peakIndex++) {
                RenderedHeatmapSampler.CrossSectionPeak peak = peaks.get(peakIndex);
                builder.append(csv(colorMode)).append(',')
                    .append(csv(intensitySource.name())).append(',')
                    .append(csv(components)).append(',')
                    .append(profileIndex).append(',')
                    .append(peakIndex).append(',')
                    .append(format(peak.offsetPx())).append(',')
                    .append(format(peak.intensity())).append(',')
                    .append(format(peak.prominence())).append(',')
                    .append(format(peak.noiseFloor())).append(',')
                    .append(format(peak.maxProfileIntensity())).append(',')
                    .append(format(peak.supportWidthPx())).append(',')
                    .append(format(peak.gradientStrength())).append(',')
                    .append(format(peak.gradientBalance())).append(',')
                    .append(format(peak.nativeFilteredAgreement())).append(',')
                    .append(format(peak.rawCenterPx())).append(',')
                    .append(format(peak.lightFilteredCenterPx())).append(',')
                    .append(format(peak.standardFilteredCenterPx())).append(',')
                    .append(format(peak.scaleOffsetRmsPx())).append(',')
                    .append(format(peak.scaleAgreement())).append(',')
                    .append(format(peak.centerUncertaintyPx())).append(',')
                    .append(csv("raw+B3+B5")).append(',')
                    .append(csv("2.0 strong/1.25 weak")).append(',')
                    .append(csv("B3 0.45/0.30/0.15; B5 0.35/0.25/0.10")).append(',')
                    .append(csv("0.18*max")).append(',')
                    .append(csv("max(0.08,0.42*max)")).append(',')
                    .append(peak.syntheticCenter())
                    .append('\n');
            }
        }
    }

    private void appendPaletteSamplesCsv(
        StringBuilder builder,
        String colorMode,
        IntensitySamplingMode intensitySource,
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles
    ) {
        String components = intensityComponentsCsv(colorMode);
        for (int profileIndex = 0; profileIndex < profiles.size(); profileIndex++) {
            List<RenderedHeatmapSampler.CrossSectionPeak> peaks = profiles.get(profileIndex).peaks();
            RenderedHeatmapSampler.CrossSectionPeak strongest = peaks.stream()
                .max(java.util.Comparator.comparingDouble(RenderedHeatmapSampler.CrossSectionPeak::intensity))
                .orElse(new RenderedHeatmapSampler.CrossSectionPeak(0.0, 0.0));
            long syntheticCount = peaks.stream().filter(RenderedHeatmapSampler.CrossSectionPeak::syntheticCenter).count();
            builder.append(csv(colorMode)).append(',')
                .append(csv(intensitySource.name())).append(',')
                .append(csv(components)).append(',')
                .append(profileIndex).append(',')
                .append(profiles.get(profileIndex).anchorWithinRaster()).append(',')
                .append(format(strongest.intensity())).append(',')
                .append(format(strongest.prominence())).append(',')
                .append(format(strongest.noiseFloor())).append(',')
                .append(format(strongest.maxProfileIntensity())).append(',')
                .append(format(strongest.gradientStrength())).append(',')
                .append(format(strongest.gradientBalance())).append(',')
                .append(format(strongest.scaleAgreement())).append(',')
                .append(format(strongest.centerUncertaintyPx())).append(',')
                .append(peaks.size()).append(',')
                .append(syntheticCount)
                .append('\n');
        }
    }

    private String intensityComponentsCsv(String colorMode) {
        return RenderedHeatmapSampler.intensityComponents(colorMode).stream()
            .map(component -> component.mode() + ":" + format(component.weight()))
            .collect(java.util.stream.Collectors.joining(";"));
    }

    private String candidateMetricsCsv(
        List<CenterlineCandidate> candidates,
        ManagedHeatmapConfig config,
        EffectiveSampling effectiveSampling
    ) {
        StringBuilder builder = new StringBuilder(
            "rank,candidate_id,display_name,detector,visible_color,intensity_source,source_tier,applicable,raw_score,calibrated_score,support_ratio,mean_intensity,mean_gradient_strength,longitudinal_stability,signal_to_noise,ambiguity,signal_existence_confidence,localization_confidence,optimizer_cost,in_corridor_fraction,scale_persistence,scale_conflict_fraction,max_consecutive_empty_profiles,source_meters_per_pixel,offset_abs_mean_px,p95_delta_px,p95_acceleration_px,high_frequency_p95_px,p95_delta_source_px,p95_acceleration_source_px,high_frequency_p95_source_px,sub_source_wiggle_ratio,sign_flips,edge_ratio,offset_abs_mean_m,p95_delta_m,p95_acceleration_m,high_frequency_p95_m,tube_residual_mean_source_px,tube_residual_p95_source_px,corridor_hf_rms_source_px,corridor_hf_p95_source_px,turn_p95_deg,turn_max_deg,curvature_change_p95_deg,forward_progress_violations,unsupported_excursions,max_gap_m,endpoint_approach_max_turn_deg,true_longitudinal_persistence,endpoint_approaches_supported,safety_warnings\n");
        IntensitySamplingMode source = intensitySamplingMode(config);
        for (int i = 0; i < candidates.size(); i++) {
            CenterlineCandidate candidate = candidates.get(i);
            CandidateMetrics metrics = candidateMetrics(candidate, effectiveSampling);
            CorridorQuality quality = candidate.evidence().corridorQuality();
            builder.append(i + 1).append(',')
                .append(csv(candidate.id())).append(',')
                .append(csv(candidate.displayName())).append(',')
                .append(csv(detectorMode(candidate))).append(',')
                .append(csv(normalizedVisibleColor(config))).append(',')
                .append(csv(source.name())).append(',')
                .append(DetectorFamily.sourceTier(normalizedVisibleColor(config), detectorMode(candidate))).append(',')
                .append(isApplicableCandidate(candidate)).append(',')
                .append(format(candidate.score())).append(',')
                .append(format(calibratedRankingScore(candidate, config, effectiveSampling))).append(',')
                .append(format(candidate.evidence().supportRatio())).append(',')
                .append(format(candidate.evidence().meanIntensity())).append(',')
                .append(format(candidate.evidence().meanGradientStrength())).append(',')
                .append(format(candidate.evidence().longitudinalStability())).append(',')
                .append(format(candidate.evidence().signalToNoise())).append(',')
                .append(format(candidate.evidence().ambiguity())).append(',')
                .append(format(candidate.evidence().signalExistenceConfidence())).append(',')
                .append(format(candidate.evidence().localizationConfidence())).append(',')
                .append(format(candidate.evidence().optimizerCost())).append(',')
                .append(format(candidate.evidence().inCorridorFraction())).append(',')
                .append(format(candidate.evidence().scalePersistence())).append(',')
                .append(format(candidate.evidence().scaleConflictFraction())).append(',')
                .append(candidate.evidence().maxConsecutiveEmptyProfiles()).append(',')
                .append(format(metrics.sourceMetersPerPixel())).append(',')
                .append(format(metrics.absMeanOffsetPx())).append(',')
                .append(format(metrics.p95DeltaPx())).append(',')
                .append(format(metrics.p95AccelerationPx())).append(',')
                .append(format(metrics.highFrequencyP95Px())).append(',')
                .append(format(metrics.p95DeltaSourcePx())).append(',')
                .append(format(metrics.p95AccelerationSourcePx())).append(',')
                .append(format(metrics.highFrequencyP95SourcePx())).append(',')
                .append(format(metrics.subSourceWiggleRatio())).append(',')
                .append(metrics.signFlips()).append(',')
                .append(format(metrics.edgeRatio())).append(',')
                .append(format(metrics.absMeanOffsetMeters())).append(',')
                .append(format(metrics.p95DeltaMeters())).append(',')
                .append(format(metrics.p95AccelerationMeters())).append(',')
                .append(format(metrics.highFrequencyP95Meters())).append(',')
                .append(format(quality.tubeResidualMeanSourcePx())).append(',')
                .append(format(quality.tubeResidualP95SourcePx())).append(',')
                .append(format(quality.highFrequencyRmsSourcePx())).append(',')
                .append(format(quality.highFrequencyP95SourcePx())).append(',')
                .append(format(quality.turnP95Degrees())).append(',')
                .append(format(quality.turnMaximumDegrees())).append(',')
                .append(format(quality.curvatureChangeP95Degrees())).append(',')
                .append(quality.forwardProgressViolations()).append(',')
                .append(quality.unsupportedExcursions()).append(',')
                .append(format(quality.maximumGapMeters())).append(',')
                .append(format(quality.endpointApproachMaximumTurnDegrees())).append(',')
                .append(format(quality.longitudinalPersistence())).append(',')
                .append(quality.endpointApproachesSupported()).append(',')
                .append(csv(String.join("; ", candidate.safetyWarnings())))
                .append('\n');
        }
        return builder.toString();
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

    private Bounds projectionBoundsToRealBounds(ProjectionBounds bounds) {
        if (bounds == null || !bounds.hasExtend()) {
            return null;
        }
        LatLon min = ProjectionRegistry.getProjection().eastNorth2latlon(bounds.getMin());
        LatLon max = ProjectionRegistry.getProjection().eastNorth2latlon(bounds.getMax());
        return new Bounds(min, max);
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
                .append("\"offsetHighFrequencyP95Px\":").append(jsonDouble(metrics.highFrequencyP95Px())).append(',')
                .append("\"offsetP95DeltaReferencePx\":").append(jsonDouble(metrics.p95DeltaReferencePx())).append(',')
                .append("\"offsetP95AccelerationReferencePx\":").append(jsonDouble(metrics.p95AccelerationReferencePx())).append(',')
                .append("\"offsetP95DeltaSourcePx\":").append(jsonDouble(metrics.p95DeltaSourcePx())).append(',')
                .append("\"offsetP95AccelerationSourcePx\":").append(jsonDouble(metrics.p95AccelerationSourcePx())).append(',')
                .append("\"offsetHighFrequencyP95SourcePx\":").append(jsonDouble(metrics.highFrequencyP95SourcePx())).append(',')
                .append("\"offsetAbsMeanMeters\":").append(jsonDouble(metrics.absMeanOffsetMeters())).append(',')
                .append("\"offsetP95DeltaMeters\":").append(jsonDouble(metrics.p95DeltaMeters())).append(',')
                .append("\"offsetP95AccelerationMeters\":").append(jsonDouble(metrics.p95AccelerationMeters())).append(',')
                .append("\"offsetHighFrequencyP95Meters\":").append(jsonDouble(metrics.highFrequencyP95Meters())).append(',')
                .append("\"sourceMetersPerPixel\":").append(jsonDouble(metrics.sourceMetersPerPixel())).append(',')
                .append("\"subSourceWiggleRatio\":").append(jsonDouble(metrics.subSourceWiggleRatio())).append(',')
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

    private String csv(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }

    /** Alignment failure that retains a partial result for last-slide diagnostics. */
    public static final class AlignmentFailureException extends IllegalStateException {
        private final AlignmentResult partialResult;

        AlignmentFailureException(String message, AlignmentResult partialResult) {
            super(message);
            this.partialResult = partialResult;
        }

        /**
         * Returns the partial result captured before alignment failed.
         *
         * @return diagnostic result, possibly without an applicable candidate
         */
        public AlignmentResult partialResult() {
            return partialResult;
        }
    }

    private record CandidateMetrics(
        double absMeanOffsetPx,
        double p95DeltaPx,
        double p95AccelerationPx,
        double highFrequencyP95Px,
        double p95DeltaReferencePx,
        double p95AccelerationReferencePx,
        double p95DeltaSourcePx,
        double p95AccelerationSourcePx,
        double highFrequencyP95SourcePx,
        int signFlips,
        double subSourceWiggleRatio,
        double edgeRatio,
        double absMeanOffsetMeters,
        double p95DeltaMeters,
        double p95AccelerationMeters,
        double highFrequencyP95Meters,
        double sourceMetersPerPixel
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
        double targetStepMeters,
        double rasterScale,
        double sourceMetersPerPixel
    ) {
        double referenceRasterMetersPerPixel() {
            return referenceViewMetersPerPixel / rasterScale;
        }

        double rasterMetersPerPixel() {
            return viewMetersPerPixel / rasterScale;
        }

        double sourcePixelSizeRasterPx() {
            double rasterMeters = rasterMetersPerPixel();
            if (!Double.isFinite(sourceMetersPerPixel) || sourceMetersPerPixel <= 0.0
                || !Double.isFinite(rasterMeters) || rasterMeters <= 0.0) {
                return rasterScale;
            }
            return Math.max(1.0, sourceMetersPerPixel / rasterMeters);
        }

        double effectiveHalfWidthMeters() {
            return effectiveHalfWidthPx * viewMetersPerPixel;
        }

        double effectiveStepMeters() {
            return effectiveStepPx * viewMetersPerPixel;
        }
    }

    private record RenderedCapture(
        BufferedImage raster,
        ProjectionBounds bounds,
        double viewMetersPerPixel,
        List<Point2D.Double> sourceRasterPolyline,
        VisibleCaptureState state
    ) {
    }

    private record VisibleCaptureState(
        EastNorth originalCenter,
        double originalScale,
        ProjectionBounds originalBounds,
        Dimension originalSize,
        ProjectionBounds requestedBounds,
        ProjectionBounds captureBounds,
        Dimension captureSize,
        double targetViewMetersPerPixel,
        boolean chunked,
        int chunkCount
    ) {
        boolean adjusted() {
            return true;
        }

        VisibleCaptureState withCapturePlan(boolean newChunked, int newChunkCount) {
            return new VisibleCaptureState(originalCenter, originalScale, originalBounds, originalSize,
                requestedBounds, captureBounds, captureSize, targetViewMetersPerPixel, newChunked, newChunkCount);
        }
    }

    private record DetectionResult(
        List<CenterlineCandidate> candidates,
        String profilesJson,
        String profilePeaksCsv,
        String paletteSamplesCsv,
        String profileIntensityCsv,
        String corridorBandsCsv,
        String corridorTracksCsv,
        String optimizerCostsCsv,
        String scaleSpaceCsv,
        String corridorTubeCsv,
        String associationDecisionsCsv,
        String endpointApproachesCsv,
        int outsideRasterProfiles,
        int totalProfiles
    ) {
    }

    private record TrackerOutput(
        List<CenterlineCandidate> candidates,
        String profileIntensityCsv,
        String corridorBandsCsv,
        String corridorTracksCsv,
        String optimizerCostsCsv,
        String scaleSpaceCsv,
        String corridorTubeCsv,
        String associationDecisionsCsv,
        String endpointApproachesCsv
    ) {
    }
}
