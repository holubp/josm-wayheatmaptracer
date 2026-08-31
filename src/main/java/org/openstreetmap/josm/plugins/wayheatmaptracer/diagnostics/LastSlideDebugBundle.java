package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.WayHeatmapTracerPlugin;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateRating;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateGeometryCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetectorAttempt;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileRuntime;

/**
 * Redacted diagnostic archive for the latest alignment attempt.
 */
public final class LastSlideDebugBundle {
    private final String diagnosticsJson;
    private final String verboseLog;
    private final String originalOsm;
    private final String previewOsm;
    private final String appliedOsm;
    private final String candidateOsm;
    private final String candidatePreviewOsm;
    private final String junctionSafetyCsv;
    private final String proposedNodePositionsCsv;
    private final String junctionContextOsm;
    private final String statusJson;
    private final String candidateRatingsJson;
    private final String candidateMetricsCsv;
    private final String geometryCleanupCsv;
    private final String geometryCleanupAnchorsCsv;
    private final String profilePeaksCsv;
    private final String paletteSamplesCsv;
    private final String profileIntensityCsv;
    private final String corridorBandsCsv;
    private final String corridorTracksCsv;
    private final String corridorBundlesCsv;
    private final String bundlePointsCsv;
    private final String optimizerCostsCsv;
    private final String scaleSpaceCsv;
    private final String corridorTubeCsv;
    private final String associationDecisionsCsv;
    private final String endpointApproachesCsv;
    private final String detectorPerformanceCsv;
    private final String parallelContextJson;
    private final String tileManifestJson;
    private final String aggregateMetadataJson;
    private final String detectorAttemptsJson;
    private final String tileAcquisitionJson;
    private final Map<String, BufferedImage> tileImages;

    private LastSlideDebugBundle(
        String diagnosticsJson,
        String verboseLog,
        String originalOsm,
        String previewOsm,
        String appliedOsm,
        String candidateOsm,
        String candidatePreviewOsm,
        String junctionSafetyCsv,
        String proposedNodePositionsCsv,
        String junctionContextOsm,
        String statusJson,
        String candidateRatingsJson,
        String candidateMetricsCsv,
        String geometryCleanupCsv,
        String geometryCleanupAnchorsCsv,
        String profilePeaksCsv,
        String paletteSamplesCsv,
        String profileIntensityCsv,
        String corridorBandsCsv,
        String corridorTracksCsv,
        String corridorBundlesCsv,
        String bundlePointsCsv,
        String optimizerCostsCsv,
        String scaleSpaceCsv,
        String corridorTubeCsv,
        String associationDecisionsCsv,
        String endpointApproachesCsv,
        String detectorPerformanceCsv,
        String parallelContextJson,
        String tileManifestJson,
        String aggregateMetadataJson,
        String detectorAttemptsJson,
        String tileAcquisitionJson,
        Map<String, BufferedImage> tileImages
    ) {
        this.diagnosticsJson = diagnosticsJson;
        this.verboseLog = verboseLog;
        this.originalOsm = originalOsm;
        this.previewOsm = previewOsm;
        this.appliedOsm = appliedOsm;
        this.candidateOsm = candidateOsm;
        this.candidatePreviewOsm = candidatePreviewOsm;
        this.junctionSafetyCsv = junctionSafetyCsv;
        this.proposedNodePositionsCsv = proposedNodePositionsCsv;
        this.junctionContextOsm = junctionContextOsm;
        this.statusJson = statusJson;
        this.candidateRatingsJson = candidateRatingsJson;
        this.candidateMetricsCsv = candidateMetricsCsv;
        this.geometryCleanupCsv = geometryCleanupCsv;
        this.geometryCleanupAnchorsCsv = geometryCleanupAnchorsCsv;
        this.profilePeaksCsv = profilePeaksCsv;
        this.paletteSamplesCsv = paletteSamplesCsv;
        this.profileIntensityCsv = profileIntensityCsv;
        this.corridorBandsCsv = corridorBandsCsv;
        this.corridorTracksCsv = corridorTracksCsv;
        this.corridorBundlesCsv = corridorBundlesCsv;
        this.bundlePointsCsv = bundlePointsCsv;
        this.optimizerCostsCsv = optimizerCostsCsv;
        this.scaleSpaceCsv = scaleSpaceCsv;
        this.corridorTubeCsv = corridorTubeCsv;
        this.associationDecisionsCsv = associationDecisionsCsv;
        this.endpointApproachesCsv = endpointApproachesCsv;
        this.detectorPerformanceCsv = detectorPerformanceCsv;
        this.parallelContextJson = parallelContextJson;
        this.tileManifestJson = tileManifestJson;
        this.aggregateMetadataJson = aggregateMetadataJson;
        this.detectorAttemptsJson = detectorAttemptsJson;
        this.tileAcquisitionJson = tileAcquisitionJson;
        this.tileImages = tileImages;
    }

    /**
     * Creates a bundle from an alignment result without user ratings.
     *
     * @param result alignment result to export
     * @param selected selected preview candidate
     * @param status slide status such as {@code preview-open}, {@code applied}, or {@code failed}
     * @param verboseLog per-slide verbose log text
     * @return debug bundle ready to write
     */
    public static LastSlideDebugBundle fromResult(AlignmentResult result, CenterlineCandidate selected, String status, String verboseLog) {
        return fromResult(result, selected, status, verboseLog, Map.of());
    }

    /**
     * Creates a bundle from an alignment result with optional user candidate ratings.
     *
     * @param result alignment result to export
     * @param selected selected preview candidate
     * @param status slide status such as {@code preview-open}, {@code applied}, or {@code failed}
     * @param verboseLog per-slide verbose log text
     * @param candidateRatings preview ratings keyed by candidate id
     * @return debug bundle ready to write
     */
    public static LastSlideDebugBundle fromResult(
        AlignmentResult result,
        CenterlineCandidate selected,
        String status,
        String verboseLog,
        Map<String, CandidateRating> candidateRatings
    ) {
        Map<String, BufferedImage> images = new LinkedHashMap<>();
        String tileManifest = "{\"sampling\":\"rendered-visible-layer\",\"images\":[\"rendered-layer-capture.png\"],"
            + "\"details\":\"see diagnostics.json sampling and profiles\"}";
        String aggregateMetadata = "{}";
        if (result.tileMosaics() != null) {
            tileManifest = result.tileMosaics().manifestJson();
            for (TileHeatmapSampler.TileMosaic mosaic : result.tileMosaics().mosaics().values()) {
                images.put("tiles/" + safeName(mosaic.color()) + "-mosaic-z" + mosaic.zoom() + ".png", mosaic.image());
                for (Map.Entry<String, BufferedImage> tile : mosaic.tileImages().entrySet()) {
                    images.put("tiles/source/" + safeName(tile.getKey()), tile.getValue());
                }
            }
            try {
                TileHeatmapSampler.AggregateVisualization visualization = new TileHeatmapSampler()
                    .buildAggregatedIntensityVisualization(result.tileMosaics(), result.tileMosaics().inferenceZoom());
                if (visualization != null) {
                    images.put("aggregate-intensity/all-colors-combined-z" + visualization.zoom() + ".png", visualization.image());
                    aggregateMetadata = visualization.metadataJson();
                }
            } catch (RuntimeException ex) {
                aggregateMetadata = "{\"error\":\"" + escape(redactSensitiveValues(ex.getMessage())) + "\"}";
            }
        } else if (result.capturedHeatmap() != null) {
            images.put("rendered-layer-capture.png", result.capturedHeatmap());
        }
        String ratingsJson = redactSensitiveValues(ratingsJson(candidateRatings));
        String attemptsJson = redactSensitiveValues(detectorAttemptsJson(result));
        String cleanupCsv = geometryCleanupCsv(result);
        String cleanupAnchorsCsv = geometryCleanupAnchorsCsv(result);
        String statusJson = "{"
            + "\"status\":\"" + escape(status) + "\","
            + "\"selectedCandidate\":\"" + escape(selected == null ? "" : selected.id()) + "\","
            + "\"detectorAttempts\":" + attemptsJson + ','
            + "\"candidateRatings\":" + ratingsJson
            + "}";
        String version = pluginVersion();
        String build = buildIdentity();
        return new LastSlideDebugBundle(
            redactSensitiveValues(addGeometryCleanupSummary(
                addBuildIdentity(result.diagnostics().toJson(), version, build), cleanupCsv, cleanupAnchorsCsv)),
            "Plugin-Version: " + version + '\n' + "Plugin-Build: " + build + '\n'
                + redactSensitiveValues(verboseLog),
            originalOsm(result),
            previewOsm(result),
            "applied".equals(status) ? appliedOsm(result) : "",
            candidateOsm(result),
            candidatePreviewOsm(result),
            junctionSafetyCsv(result),
            proposedNodePositionsCsv(result),
            junctionContextOsm(result),
            statusJson,
            ratingsJson,
            result.diagnostics().candidateMetricsCsv(),
            cleanupCsv,
            cleanupAnchorsCsv,
            result.diagnostics().profilePeaksCsv(),
            result.diagnostics().paletteSamplesCsv(),
            result.diagnostics().profileIntensityCsv(),
            result.diagnostics().corridorBandsCsv(),
            result.diagnostics().corridorTracksCsv(),
            result.diagnostics().corridorBundlesCsv(),
            result.diagnostics().bundlePointsCsv(),
            result.diagnostics().optimizerCostsCsv(),
            result.diagnostics().scaleSpaceCsv(),
            result.diagnostics().corridorTubeCsv(),
            result.diagnostics().associationDecisionsCsv(),
            result.diagnostics().endpointApproachesCsv(),
            result.diagnostics().detectorPerformanceCsv(),
            redactSensitiveValues(result.diagnostics().parallelContextJson()),
            redactSensitiveValues(tileManifest),
            redactSensitiveValues(aggregateMetadata),
            attemptsJson,
            redactSensitiveValues(ManagedTileRuntime.diagnosticsJsonIfInitialized()),
            images
        );
    }

    /**
     * Writes the redacted debug bundle zip file.
     *
     * @param file destination zip file
     * @return the written file
     * @throws Exception when zip writing or image encoding fails
     */
    public File writeTo(File file) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            writeText(zip, "manifest.json", manifestJson());
            writeText(zip, "diagnostics.json", diagnosticsJson);
            writeText(zip, "status.json", statusJson);
            writeText(zip, "verbose-log.txt", verboseLog);
            writeText(zip, "original-segment.osm", originalOsm);
            writeText(zip, "preview-segment.osm", previewOsm);
            writeText(zip, "applied-segment.osm", appliedOsm);
            writeText(zip, "candidate-ridges.osm", candidateOsm);
            writeText(zip, "candidate-previews.osm", candidatePreviewOsm);
            writeText(zip, "junction-safety.csv", junctionSafetyCsv);
            writeText(zip, "proposed-node-positions.csv", proposedNodePositionsCsv);
            writeText(zip, "junction-context.osm", junctionContextOsm);
            writeText(zip, "candidate-ratings.json", candidateRatingsJson);
            writeText(zip, "candidate-metrics.csv", candidateMetricsCsv);
            writeText(zip, "geometry-cleanup.csv", geometryCleanupCsv);
            writeText(zip, "geometry-cleanup-anchors.csv", geometryCleanupAnchorsCsv);
            writeText(zip, "profile-peaks.csv", profilePeaksCsv);
            writeText(zip, "palette-samples.csv", paletteSamplesCsv);
            writeText(zip, "profile-intensity.csv", profileIntensityCsv);
            writeText(zip, "corridor-bands.csv", corridorBandsCsv);
            writeText(zip, "corridor-tracks.csv", corridorTracksCsv);
            writeText(zip, "corridor-bundles.csv", corridorBundlesCsv);
            writeText(zip, "bundle-points.csv", bundlePointsCsv);
            writeText(zip, "optimizer-costs.csv", optimizerCostsCsv);
            writeText(zip, "scale-space.csv", scaleSpaceCsv);
            writeText(zip, "corridor-tube.csv", corridorTubeCsv);
            writeText(zip, "association-decisions.csv", associationDecisionsCsv);
            writeText(zip, "endpoint-approaches.csv", endpointApproachesCsv);
            writeText(zip, "detector-performance.csv", detectorPerformanceCsv);
            writeText(zip, "parallel-context.json", parallelContextJson);
            writeText(zip, "tile-manifest.json", tileManifestJson);
            writeText(zip, "aggregate-intensity/metadata.json", aggregateMetadataJson);
            writeText(zip, "detector-attempts.json", detectorAttemptsJson);
            writeText(zip, "tile-acquisition.json", tileAcquisitionJson);
            for (Map.Entry<String, BufferedImage> entry : tileImages.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                ImageIO.write(entry.getValue(), "png", zip);
                zip.closeEntry();
            }
        }
        return file;
    }

    private String manifestJson() {
        return "{"
            + "\"type\":\"wayheatmaptracer-last-slide-debug-bundle\","
            + "\"formatVersion\":11,"
            + "\"pluginVersion\":\"" + escape(pluginVersion()) + "\","
            + "\"buildIdentity\":\"" + escape(buildIdentity()) + "\","
            + "\"containsSecrets\":false,"
            + "\"files\":[\"diagnostics.json\",\"status.json\",\"verbose-log.txt\",\"original-segment.osm\",\"preview-segment.osm\",\"applied-segment.osm\",\"candidate-ridges.osm\",\"candidate-previews.osm\",\"junction-safety.csv\",\"proposed-node-positions.csv\",\"junction-context.osm\",\"candidate-ratings.json\",\"candidate-metrics.csv\",\"geometry-cleanup.csv\",\"geometry-cleanup-anchors.csv\",\"profile-peaks.csv\",\"palette-samples.csv\",\"profile-intensity.csv\",\"corridor-bands.csv\",\"corridor-tracks.csv\",\"corridor-bundles.csv\",\"bundle-points.csv\",\"optimizer-costs.csv\",\"scale-space.csv\",\"corridor-tube.csv\",\"association-decisions.csv\",\"endpoint-approaches.csv\",\"detector-performance.csv\",\"detector-attempts.json\",\"parallel-context.json\",\"tile-manifest.json\",\"tile-acquisition.json\",\"aggregate-intensity/metadata.json\"]"
            + "}";
    }

    /**
     * Serializes cleanup attempts without altering the established candidate-metrics schema.
     *
     * <p>Projection-unit displacements deliberately remain labelled as JOSM projection units.
     * They are not ground-metre values and must not be treated as such by analysis tools.</p>
     *
     * @param result completed alignment result
     * @return complete geometry-cleanup CSV, including an explicit row for every candidate
     */
    private static String geometryCleanupCsv(AlignmentResult result) {
        StringBuilder builder = new StringBuilder(
            "candidate_id,parent_candidate_id,outcome,reason_code,reasons,before_point_count,"
                + "smoothed_point_count,after_point_count,accepted_smoothing_passes,"
                + "smoothing_backtrack_count,attempted_chord_count,accepted_chord_count,"
                + "containment_failure_count,fit_before,fit_after,"
                + "maximum_displacement_projection_units,projection_unit_name,"
                + "maximum_removed_deviation_meters,worst_fit_retention\n");
        for (CenterlineCandidate candidate : result.candidates()) {
            CandidateGeometryCleanup cleanup = candidate.geometryCleanup();
            builder.append(csv(candidate.id())).append(',')
                .append(csv(cleanup.parentCandidateId())).append(',')
                .append(csv(cleanup.outcome().name())).append(',')
                .append(csv(cleanup.reasonCode())).append(',')
                .append(csv(String.join(";", cleanup.reasons()))).append(',')
                .append(cleanup.beforePointCount()).append(',')
                .append(cleanup.smoothedPointCount()).append(',')
                .append(cleanup.afterPointCount()).append(',')
                .append(cleanup.acceptedSmoothingPasses()).append(',')
                .append(cleanup.smoothingBacktrackCount()).append(',')
                .append(cleanup.attemptedChordCount()).append(',')
                .append(cleanup.acceptedChordCount()).append(',')
                .append(cleanup.containmentFailureCount()).append(',')
                .append(cleanup.fitBefore()).append(',')
                .append(cleanup.fitAfter()).append(',')
                .append(cleanup.maximumDisplacementProjectionUnits()).append(',')
                .append(csv("JOSM-projection-units")).append(',')
                .append(optionalDoubleCsv(cleanup.maximumRemovedDeviationMeters())).append(',')
                .append(optionalDoubleCsv(cleanup.worstFitRetention())).append('\n');
        }
        return builder.toString();
    }

    /**
     * Serializes candidate-owned protected-node targets without inventing profile provenance.
     *
     * <p>Existing-node identity and source/proposed coordinates come from the immutable slide
     * result. The heatmap profile index remains blank because candidates do not retain that
     * anchor-to-profile relation after final-preview reconstruction.</p>
     *
     * @param result completed alignment result
     * @return anchor CSV with one row per proposed existing-node target, or an unavailable row
     */
    private static String geometryCleanupAnchorsCsv(AlignmentResult result) {
        StringBuilder builder = new StringBuilder(
            "candidate_id,parent_candidate_id,cleanup_outcome,anchor_data_state,reason_code,"
                + "profile_index,final_preview_index,protected,reused_node_id,"
                + "source_east,source_north,proposed_east,proposed_north\n");
        for (CenterlineCandidate candidate : result.candidates()) {
            CandidateGeometryCleanup cleanup = candidate.geometryCleanup();
            if (candidate.proposedNodePositions().isEmpty()) {
                builder.append(csv(candidate.id())).append(',')
                    .append(csv(cleanup.parentCandidateId())).append(',')
                    .append(csv(cleanup.outcome().name())).append(',')
                    .append(csv("unavailable")).append(',')
                    .append(csv("no-candidate-owned-node-targets"))
                    .append(",,,,,,,,")
                    .append('\n');
                continue;
            }
            for (Map.Entry<Long, EastNorth> entry : candidate.proposedNodePositions().entrySet()) {
                int sourceIndex = selectedNodeIndex(result, entry.getKey());
                EastNorth source = sourceIndex >= 0 && sourceIndex < result.sourcePolyline().size()
                    ? result.sourcePolyline().get(sourceIndex) : null;
                int previewIndex = matchingPointIndex(candidate.finalPreviewPoints(), entry.getValue());
                builder.append(csv(candidate.id())).append(',')
                    .append(csv(cleanup.parentCandidateId())).append(',')
                    .append(csv(cleanup.outcome().name())).append(',')
                    .append(csv("available")).append(',')
                    .append(csv("candidate-owned-proposed-node"))
                    .append(',')
                    .append(',').append(previewIndex < 0 ? "" : Integer.toString(previewIndex))
                    .append(',').append("true")
                    .append(',').append(entry.getKey())
                    .append(',').append(source == null ? "" : Double.toString(source.east()))
                    .append(',').append(source == null ? "" : Double.toString(source.north()))
                    .append(',').append(entry.getValue().east())
                    .append(',').append(entry.getValue().north())
                    .append('\n');
            }
        }
        return builder.toString();
    }

    private static int selectedNodeIndex(AlignmentResult result, long nodeId) {
        for (int index = 0; index < result.selection().segmentNodes().size(); index++) {
            if (result.selection().segmentNodes().get(index).getUniqueId() == nodeId) {
                return index;
            }
        }
        return -1;
    }

    private static int matchingPointIndex(List<EastNorth> geometry, EastNorth target) {
        for (int index = 0; index < geometry.size(); index++) {
            if (geometry.get(index).distance(target) <= 1e-7) {
                return index;
            }
        }
        return -1;
    }

    private static String optionalDoubleCsv(OptionalDouble value) {
        return value.isPresent() ? Double.toString(value.orElseThrow()) : "";
    }

    private static String addGeometryCleanupSummary(String diagnosticsJson, String cleanupCsv, String anchorsCsv) {
        String summary = "{\"storage\":\"dedicated-csv-artifacts\","
            + "\"anchorEvidence\":\"candidate-owned-node-targets\",\"artifacts\":["
            + artifactJson("geometry-cleanup.csv", cleanupCsv) + ','
            + artifactJson("geometry-cleanup-anchors.csv", anchorsCsv) + "]}";
        return addJsonField(diagnosticsJson, "geometryCleanup", summary);
    }

    private static String artifactJson(String file, String contents) {
        String value = contents == null ? "" : contents;
        long lineCount = value.lines().count();
        long rowCount = Math.max(0L, lineCount - (value.isBlank() ? 0L : 1L));
        return "{\"file\":\"" + escape(file) + "\",\"rows\":" + rowCount
            + ",\"bytes\":" + value.getBytes(StandardCharsets.UTF_8).length
            + ",\"sha256\":\"" + sha256(value) + "\"}";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is required for debug artifact summaries", ex);
        }
    }

    private static String addJsonField(String json, String fieldName, String fieldJson) {
        String value = json == null || json.isBlank() ? "{}" : json.trim();
        if (!value.startsWith("{") || !value.endsWith("}")) {
            return value;
        }
        String body = value.substring(1, value.length() - 1).trim();
        return "{\"" + escape(fieldName) + "\":" + fieldJson + (body.isEmpty() ? "" : "," + body) + "}";
    }

    /**
     * Removes common Strava credential forms from diagnostic text before archive writing.
     *
     * @param value potentially sensitive text
     * @return text with credential values replaced by {@code <redacted>}
     */
    private static String redactSensitiveValues(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String redacted = value.replaceAll(
            "(?i)(\\\"?(?:CloudFront-(?:Key-Pair-Id|Policy|Signature)|_strava_idcf)\\\"?\\s*[:=]\\s*\\\"?)([^,;\\s\\\"&}]+)",
            "$1<redacted>");
        redacted = redacted.replaceAll(
            "(?i)([?&](?:Policy|Signature|Key-Pair-Id|X-Amz-Signature|X-Amz-Credential)=)([^&\\s\\\"']+)",
            "$1<redacted>");
        return redacted.replaceAll(
            "(?im)^([\\t ]*(?:cookie|authorization|x-amz-[a-z0-9-]+)\\s*:\\s*).*$",
            "$1<redacted>");
    }

    private static String detectorAttemptsJson(AlignmentResult result) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < result.detectorAttempts().size(); i++) {
            DetectorAttempt attempt = result.detectorAttempts().get(i);
            if (i > 0) {
                builder.append(',');
            }
            builder.append('{')
                .append("\"sourcePalette\":\"").append(escape(attempt.sourcePalette())).append("\",")
                .append("\"mappingName\":\"").append(escape(attempt.mappingName())).append("\",")
                .append("\"trackerMode\":\"").append(attempt.trackerMode()).append("\",")
                .append("\"status\":\"").append(attempt.status()).append("\",")
                .append("\"reasonCode\":\"").append(escape(attempt.reasonCode())).append("\",")
                .append("\"reason\":\"").append(escape(attempt.reason())).append("\",")
                .append("\"candidateIds\":[");
            for (int idIndex = 0; idIndex < attempt.candidateIds().size(); idIndex++) {
                if (idIndex > 0) {
                    builder.append(',');
                }
                builder.append('"').append(escape(attempt.candidateIds().get(idIndex))).append('"');
            }
            builder.append("]}");
        }
        return builder.append(']').toString();
    }

    private static String ratingsJson(Map<String, CandidateRating> candidateRatings) {
        StringBuilder builder = new StringBuilder("{");
        int index = 0;
        for (Map.Entry<String, CandidateRating> entry : candidateRatings.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            if (index++ > 0) {
                builder.append(',');
            }
            builder.append('"').append(escape(entry.getKey())).append("\":").append(entry.getValue().toJson());
        }
        return builder.append('}').toString();
    }

    private static void writeText(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String originalOsm(AlignmentResult result) {
        StringBuilder builder = new StringBuilder(osmHeader());
        java.util.List<EastNorth> source = result.sourcePolyline();
        java.util.List<Node> identifiers = result.selection().segmentNodes();
        java.util.List<Long> ids = new java.util.ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            long id = index < identifiers.size() ? identifiers.get(index).getUniqueId() : -1_000_000L - index;
            ids.add(id);
            builder.append(nodeXml(id, ProjectionRegistry.getProjection().eastNorth2latlon(source.get(index))));
        }
        builder.append("  <way id=\"").append(result.selection().way().getUniqueId()).append("\">\n");
        for (long id : ids) {
            builder.append("    <nd ref=\"").append(id).append("\" />\n");
        }
        builder.append("  </way>\n</osm>\n");
        return builder.toString();
    }

    private static String appliedOsm(AlignmentResult result) {
        java.util.List<Node> original = result.selection().segmentNodes();
        if (original.isEmpty()) {
            return osmHeader() + "</osm>\n";
        }
        java.util.List<Node> wayNodes = result.selection().way().getNodes();
        int first = wayNodes.indexOf(original.get(0));
        int last = wayNodes.indexOf(original.get(original.size() - 1));
        if (first < 0 || last < 0) {
            return osmHeader() + "</osm>\n";
        }
        int from = Math.min(first, last);
        int to = Math.max(first, last);
        java.util.List<Node> applied = new java.util.ArrayList<>(wayNodes.subList(from, to + 1));
        if (first > last) {
            java.util.Collections.reverse(applied);
        }
        StringBuilder builder = new StringBuilder(osmHeader());
        for (Node node : applied) {
            if (node.getCoor() != null) {
                builder.append(nodeXml(node.getUniqueId(), node.getCoor()));
            }
        }
        builder.append("  <way id=\"").append(result.selection().way().getUniqueId()).append("\">\n");
        for (Node node : applied) {
            builder.append("    <nd ref=\"").append(node.getUniqueId()).append("\" />\n");
        }
        return builder.append("  </way>\n</osm>\n").toString();
    }

    private static String previewOsm(AlignmentResult result) {
        StringBuilder builder = new StringBuilder(osmHeader());
        long id = -1;
        for (EastNorth point : result.previewPolyline()) {
            builder.append(nodeXml(id--, ProjectionRegistry.getProjection().eastNorth2latlon(point)));
        }
        builder.append("  <way id=\"-1000000\">\n");
        for (long ref = -1; ref >= -result.previewPolyline().size(); ref--) {
            builder.append("    <nd ref=\"").append(ref).append("\" />\n");
        }
        builder.append("  </way>\n</osm>\n");
        return builder.toString();
    }

    private static String candidateOsm(AlignmentResult result) {
        StringBuilder builder = new StringBuilder(osmHeader());
        long nodeId = -10_000_000;
        long wayId = -20_000_000;
        for (CenterlineCandidate candidate : result.candidates()) {
            if (candidate.eastNorthPoints().isEmpty()) {
                continue;
            }
            long firstNodeId = nodeId;
            for (EastNorth point : candidate.eastNorthPoints()) {
                builder.append(nodeXml(nodeId--, ProjectionRegistry.getProjection().eastNorth2latlon(point)));
            }
            builder.append("  <way id=\"").append(wayId--).append("\">\n");
            for (long ref = firstNodeId; ref > nodeId; ref--) {
                builder.append("    <nd ref=\"").append(ref).append("\" />\n");
            }
            builder.append("    <tag k=\"wayheatmaptracer:candidate\" v=\"").append(xmlEscape(candidate.id())).append("\" />\n");
            builder.append("    <tag k=\"wayheatmaptracer:score\" v=\"").append(candidate.score()).append("\" />\n");
            if (!candidate.safetyWarnings().isEmpty()) {
                builder.append("    <tag k=\"wayheatmaptracer:warnings\" v=\"")
                    .append(xmlEscape(String.join("; ", candidate.safetyWarnings())))
                    .append("\" />\n");
            }
            builder.append("  </way>\n");
        }
        builder.append("</osm>\n");
        return builder.toString();
    }

    private static String candidatePreviewOsm(AlignmentResult result) {
        StringBuilder builder = new StringBuilder(osmHeader());
        long nodeId = -30_000_000;
        long wayId = -40_000_000;
        for (CenterlineCandidate candidate : result.candidates()) {
            if (candidate.finalPreviewPoints().isEmpty()) {
                continue;
            }
            long firstNodeId = nodeId;
            for (EastNorth point : candidate.finalPreviewPoints()) {
                builder.append(nodeXml(nodeId--, ProjectionRegistry.getProjection().eastNorth2latlon(point)));
            }
            builder.append("  <way id=\"").append(wayId--).append("\">\n");
            for (long ref = firstNodeId; ref > nodeId; ref--) {
                builder.append("    <nd ref=\"").append(ref).append("\" />\n");
            }
            builder.append("    <tag k=\"wayheatmaptracer:candidate\" v=\"")
                .append(xmlEscape(candidate.id())).append("\" />\n")
                .append("    <tag k=\"wayheatmaptracer:geometry-stage\" v=\"final-preview\" />\n")
                .append("  </way>\n");
        }
        return builder.append("</osm>\n").toString();
    }

    private static String junctionSafetyCsv(AlignmentResult result) {
        StringBuilder builder = new StringBuilder(
            "candidate_id,reason_code,geometry_stage,junction_node_id,connected_way_id,connected_start_node_id,connected_end_node_id,candidate_segment_index,original_junction_east,original_junction_north,proposed_junction_east,proposed_junction_north,candidate_start_east,candidate_start_north,candidate_end_east,candidate_end_north,connected_start_east,connected_start_north,connected_end_east,connected_end_north,intersection_east,intersection_north,distance_from_junction_m,tolerance_m\n");
        for (CenterlineCandidate candidate : result.candidates()) {
            for (var finding : candidate.junctionSafetyFindings()) {
                builder.append(csv(candidate.id())).append(',').append(csv(finding.reasonCode())).append(',')
                    .append(csv(finding.geometryStage())).append(',').append(finding.junctionNodeId()).append(',')
                    .append(finding.connectedWayId()).append(',').append(finding.connectedStartNodeId()).append(',')
                    .append(finding.connectedEndNodeId()).append(',').append(finding.candidateSegmentIndex()).append(',')
                    .append(finding.originalJunctionPoint().east()).append(',')
                    .append(finding.originalJunctionPoint().north()).append(',')
                    .append(finding.junctionPoint().east()).append(',').append(finding.junctionPoint().north()).append(',')
                    .append(finding.candidateStart().east()).append(',').append(finding.candidateStart().north()).append(',')
                    .append(finding.candidateEnd().east()).append(',').append(finding.candidateEnd().north()).append(',')
                    .append(finding.connectedStart().east()).append(',').append(finding.connectedStart().north()).append(',')
                    .append(finding.connectedEnd().east()).append(',').append(finding.connectedEnd().north()).append(',')
                    .append(finding.intersection().east()).append(',').append(finding.intersection().north()).append(',')
                    .append(finding.distanceFromJunctionMeters()).append(',').append(finding.toleranceMeters())
                    .append('\n');
            }
        }
        return builder.toString();
    }

    private static String proposedNodePositionsCsv(AlignmentResult result) {
        Map<Long, EastNorth> original = new LinkedHashMap<>();
        for (int index = 0; index < result.selection().segmentNodes().size(); index++) {
            original.put(result.selection().segmentNodes().get(index).getUniqueId(), result.sourcePolyline().get(index));
        }
        StringBuilder builder = new StringBuilder(
            "candidate_id,node_id,original_east,original_north,proposed_east,proposed_north\n");
        for (CenterlineCandidate candidate : result.candidates()) {
            for (Map.Entry<Long, EastNorth> entry : candidate.proposedNodePositions().entrySet()) {
                EastNorth source = original.get(entry.getKey());
                if (source == null) {
                    continue;
                }
                builder.append(csv(candidate.id())).append(',').append(entry.getKey()).append(',')
                    .append(source.east()).append(',').append(source.north()).append(',')
                    .append(entry.getValue().east()).append(',').append(entry.getValue().north()).append('\n');
            }
        }
        return builder.toString();
    }

    private static String junctionContextOsm(AlignmentResult result) {
        StringBuilder builder = new StringBuilder(osmHeader());
        long nodeId = -50_000_000;
        long wayId = -60_000_000;
        for (CenterlineCandidate candidate : result.candidates()) {
            for (var finding : candidate.junctionSafetyFindings()) {
                long junction = nodeId--;
                long start = nodeId--;
                long end = nodeId--;
                builder.append(nodeXml(junction,
                    ProjectionRegistry.getProjection().eastNorth2latlon(finding.junctionPoint())));
                builder.append(nodeXml(start,
                    ProjectionRegistry.getProjection().eastNorth2latlon(finding.connectedStart())));
                builder.append(nodeXml(end,
                    ProjectionRegistry.getProjection().eastNorth2latlon(finding.connectedEnd())));
                builder.append("  <way id=\"").append(wayId--).append("\">\n")
                    .append("    <nd ref=\"").append(start).append("\" />\n")
                    .append("    <nd ref=\"").append(end).append("\" />\n")
                    .append("    <tag k=\"wayheatmaptracer:connected-way-id\" v=\"")
                    .append(finding.connectedWayId()).append("\" />\n")
                    .append("    <tag k=\"wayheatmaptracer:junction-node-id\" v=\"")
                    .append(finding.junctionNodeId()).append("\" />\n")
                    .append("  </way>\n");
            }
        }
        return builder.append("</osm>\n").toString();
    }

    private static String pluginVersion() {
        String version = WayHeatmapTracerPlugin.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }

    private static String buildIdentity() {
        try {
            var codeSource = WayHeatmapTracerPlugin.class.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null) {
                Path path = Path.of(codeSource.getLocation().toURI());
                if (Files.isRegularFile(path)) {
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
                    return "sha256:" + HexFormat.of().formatHex(digest, 0, 8);
                }
            }
        } catch (Exception ignored) {
            // Development class directories and restricted plugin loaders have no stable jar digest.
        }
        return "development";
    }

    private static String addBuildIdentity(String json, String version, String build) {
        String value = json == null || json.isBlank() ? "{}" : json.trim();
        if (!value.startsWith("{") || !value.endsWith("}")) {
            return value;
        }
        String body = value.substring(1, value.length() - 1);
        return "{\"pluginVersion\":\"" + escape(version) + "\""
            + ",\"buildIdentity\":\"" + escape(build) + "\""
            + (body.isBlank() ? "" : "," + body) + '}';
    }

    private static String csv(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }

    private static String osmHeader() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<osm version=\"0.6\" generator=\"WayHeatmapTracer\">\n";
    }

    private static String nodeXml(long id, LatLon latLon) {
        return "  <node id=\"" + id + "\" lat=\"" + latLon.lat() + "\" lon=\"" + latLon.lon() + "\" />\n";
    }

    private static String safeName(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String xmlEscape(String value) {
        return value == null ? "" : value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }
}
