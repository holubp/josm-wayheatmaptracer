package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Redacted diagnostics generated during one alignment attempt.
 *
 * @param layerName imagery layer used for sampling
 * @param candidateCount number of generated candidates
 * @param movableNodeCount number of nodes that may be changed on apply
 * @param rasterCaptureMillis elapsed raster/source capture time
 * @param ridgeTrackingMillis elapsed ridge-tracking time
 * @param optimizationMillis elapsed geometry optimization time
 * @param configJson redacted settings JSON
 * @param selectionJson selected-way metadata JSON
 * @param samplingJson sampling source, scale, and tile metadata JSON
 * @param colorSchemesJson sampled detector/color metadata JSON
 * @param candidatesJson candidate geometry/evidence JSON
 * @param profileDiagnosticsJson legacy profile JSON retained for constructor compatibility; format 6 uses CSV artifacts
 * @param candidateMetricsCsv candidate metrics CSV
 * @param profilePeaksCsv per-profile peak CSV
 * @param paletteSamplesCsv palette sample CSV
 * @param profileIntensityCsv complete scalar profile CSV for corridor-aware tracking
 * @param corridorBandsCsv extracted corridor-band CSV
 * @param corridorTracksCsv longitudinal association and grouping CSV
 * @param corridorBundlesCsv sparse longitudinal bundle summary CSV
 * @param bundlePointsCsv profile-aligned sparse bundle evidence CSV
 * @param optimizerCostsCsv decomposed corridor optimizer CSV
 * @param scaleSpaceCsv per-profile Gaussian-level corridor evidence CSV
 * @param corridorTubeCsv robust longitudinal corridor tube CSV
 * @param associationDecisionsCsv selected longitudinal association decisions CSV
 * @param endpointApproachesCsv endpoint boundary evidence CSV
 * @param detectorPerformanceCsv per-detector phase timing and operation-count CSV
 * @param parallelContextJson redacted nearby-way assignment context JSON
 */
public record AlignmentDiagnostics(
    String layerName,
    int candidateCount,
    int movableNodeCount,
    long rasterCaptureMillis,
    long ridgeTrackingMillis,
    long optimizationMillis,
    String configJson,
    String selectionJson,
    String samplingJson,
    String colorSchemesJson,
    String candidatesJson,
    String profileDiagnosticsJson,
    String candidateMetricsCsv,
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
    String parallelContextJson
) {
    /**
     * Creates diagnostics using the pre-scale-space payload set.
     *
     * @param layerName sampled imagery layer
     * @param candidateCount candidate count
     * @param movableNodeCount movable node count
     * @param rasterCaptureMillis source-capture time
     * @param ridgeTrackingMillis ridge-tracking time
     * @param optimizationMillis geometry-optimization time
     * @param configJson redacted settings JSON
     * @param selectionJson selection metadata JSON
     * @param samplingJson sampling metadata JSON
     * @param colorSchemesJson detector metadata JSON
     * @param candidatesJson candidate metadata JSON
     * @param profileDiagnosticsJson profile metadata JSON
     * @param candidateMetricsCsv candidate metrics CSV
     * @param profilePeaksCsv profile peak CSV
     * @param paletteSamplesCsv palette sample CSV
     * @param profileIntensityCsv scalar intensity CSV
     * @param corridorBandsCsv corridor band CSV
     * @param corridorTracksCsv corridor track CSV
     * @param optimizerCostsCsv optimizer cost CSV
     * @param parallelContextJson nearby-way context JSON
     */
    public AlignmentDiagnostics(
        String layerName, int candidateCount, int movableNodeCount, long rasterCaptureMillis,
        long ridgeTrackingMillis, long optimizationMillis, String configJson, String selectionJson,
        String samplingJson, String colorSchemesJson, String candidatesJson, String profileDiagnosticsJson,
        String candidateMetricsCsv, String profilePeaksCsv, String paletteSamplesCsv,
        String profileIntensityCsv, String corridorBandsCsv, String corridorTracksCsv,
        String optimizerCostsCsv, String parallelContextJson
    ) {
        this(layerName, candidateCount, movableNodeCount, rasterCaptureMillis, ridgeTrackingMillis,
            optimizationMillis, configJson, selectionJson, samplingJson, colorSchemesJson, candidatesJson,
            profileDiagnosticsJson, candidateMetricsCsv, profilePeaksCsv, paletteSamplesCsv,
            profileIntensityCsv, corridorBandsCsv, corridorTracksCsv, "", "", optimizerCostsCsv,
            "", "", "", "", "",
            parallelContextJson);
    }

    /**
     * Creates diagnostics using the legacy CSV payload set.
     *
     * @param layerName imagery layer used for sampling
     * @param candidateCount generated candidate count
     * @param movableNodeCount movable node count
     * @param rasterCaptureMillis raster capture time
     * @param ridgeTrackingMillis ridge tracking time
     * @param optimizationMillis geometry optimization time
     * @param configJson redacted configuration JSON
     * @param selectionJson selection metadata JSON
     * @param samplingJson sampling metadata JSON
     * @param colorSchemesJson sampled color metadata JSON
     * @param candidatesJson candidate JSON
     * @param profileDiagnosticsJson profile JSON
     * @param candidateMetricsCsv candidate metrics CSV
     * @param profilePeaksCsv legacy peak CSV
     * @param paletteSamplesCsv palette CSV
     */
    public AlignmentDiagnostics(
        String layerName,
        int candidateCount,
        int movableNodeCount,
        long rasterCaptureMillis,
        long ridgeTrackingMillis,
        long optimizationMillis,
        String configJson,
        String selectionJson,
        String samplingJson,
        String colorSchemesJson,
        String candidatesJson,
        String profileDiagnosticsJson,
        String candidateMetricsCsv,
        String profilePeaksCsv,
        String paletteSamplesCsv
    ) {
        this(layerName, candidateCount, movableNodeCount, rasterCaptureMillis, ridgeTrackingMillis,
            optimizationMillis, configJson, selectionJson, samplingJson, colorSchemesJson, candidatesJson,
            profileDiagnosticsJson, candidateMetricsCsv, profilePeaksCsv, paletteSamplesCsv,
            "", "", "", "", "", "", "", "", "", "", "", "{}");
    }

    /**
     * Creates diagnostics without CSV calibration payloads.
     *
     * @param layerName imagery layer used for sampling
     * @param candidateCount number of generated candidates
     * @param movableNodeCount number of nodes that may be changed on apply
     * @param rasterCaptureMillis elapsed raster/source capture time
     * @param ridgeTrackingMillis elapsed ridge-tracking time
     * @param optimizationMillis elapsed geometry optimization time
     * @param configJson redacted settings JSON
     * @param selectionJson selected-way metadata JSON
     * @param samplingJson sampling source, scale, and tile metadata JSON
     * @param colorSchemesJson sampled detector/color metadata JSON
     * @param candidatesJson candidate geometry/evidence JSON
     * @param profileDiagnosticsJson per-profile diagnostic JSON
     */
    public AlignmentDiagnostics(
        String layerName,
        int candidateCount,
        int movableNodeCount,
        long rasterCaptureMillis,
        long ridgeTrackingMillis,
        long optimizationMillis,
        String configJson,
        String selectionJson,
        String samplingJson,
        String colorSchemesJson,
        String candidatesJson,
        String profileDiagnosticsJson
    ) {
        this(layerName, candidateCount, movableNodeCount, rasterCaptureMillis, ridgeTrackingMillis, optimizationMillis,
            configJson, selectionJson, samplingJson, colorSchemesJson, candidatesJson, profileDiagnosticsJson,
            "", "", "", "", "", "", "", "", "", "", "", "", "", "", "{}");
    }

    /**
     * Serializes the main diagnostics fields for status and debug exports.
     *
     * @return JSON object string with checksummed CSV artifact metadata instead of duplicated profile payloads
     */
    public String toJson() {
        return "{"
            + "\"layerName\":\"" + escape(layerName) + "\","
            + "\"candidateCount\":" + candidateCount + ','
            + "\"movableNodeCount\":" + movableNodeCount + ','
            + "\"rasterCaptureMillis\":" + rasterCaptureMillis + ','
            + "\"ridgeTrackingMillis\":" + ridgeTrackingMillis + ','
            + "\"optimizationMillis\":" + optimizationMillis + ','
            + "\"config\":" + configJson + ','
            + "\"selection\":" + selectionJson + ','
            + "\"sampling\":" + samplingJson + ','
            + "\"colorSchemes\":" + colorSchemesJson + ','
            + "\"candidates\":" + candidatesJson + ','
            + "\"profiles\":" + profileArtifactSummaryJson() + ','
            + "\"parallelContext\":" + (parallelContextJson == null || parallelContextJson.isBlank() ? "{}" : parallelContextJson)
            + "}";
    }

    private String profileArtifactSummaryJson() {
        return "{\"storage\":\"dedicated-csv-artifacts\",\"artifacts\":["
            + artifactJson("profile-peaks.csv", profilePeaksCsv) + ','
            + artifactJson("palette-samples.csv", paletteSamplesCsv) + ','
            + artifactJson("profile-intensity.csv", profileIntensityCsv) + ','
            + artifactJson("corridor-bands.csv", corridorBandsCsv) + ','
            + artifactJson("corridor-tracks.csv", corridorTracksCsv) + ','
            + artifactJson("corridor-bundles.csv", corridorBundlesCsv) + ','
            + artifactJson("bundle-points.csv", bundlePointsCsv) + ','
            + artifactJson("optimizer-costs.csv", optimizerCostsCsv) + ','
            + artifactJson("scale-space.csv", scaleSpaceCsv) + ','
            + artifactJson("corridor-tube.csv", corridorTubeCsv) + ','
            + artifactJson("detector-performance.csv", detectorPerformanceCsv)
            + "]}";
    }

    private String artifactJson(String file, String contents) {
        String value = contents == null ? "" : contents;
        long lineCount = value.lines().count();
        long rowCount = Math.max(0L, lineCount - (value.isBlank() ? 0L : 1L));
        return "{\"file\":\"" + escape(file) + "\",\"rows\":" + rowCount
            + ",\"bytes\":" + value.getBytes(StandardCharsets.UTF_8).length
            + ",\"sha256\":\"" + sha256(value) + "\"}";
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required for diagnostic artifact summaries", ex);
        }
    }

    /**
     * Builds the concise sampling description shown in the preview dialog.
     *
     * @return human-readable sampling source, zoom, scale, and search summary
     */
    public String samplingSummary() {
        String type = jsonString(samplingJson, "type");
        String algorithm = jsonString(samplingJson, "algorithm");
        String tileZoom = jsonValue(samplingJson, "tileZoom");
        String bestTileZoom = jsonValue(samplingJson, "bestTileZoom");
        String projectionUnitsPerViewPixel = jsonValue(samplingJson, "projectionUnitsPerViewPixel");
        String viewMetersPerPixel = firstJsonValue(samplingJson,
            "groundMetersPerViewPixel", "viewMetersPerPixel");
        String rasterMetersPerPixel = firstJsonValue(samplingJson,
            "groundMetersPerRasterPixel", "rasterMetersPerPixel");
        String nativeSourcePixelSize = jsonValue(samplingJson, "nativeSourcePixelSizeRasterPx");
        String nativeTileSize = jsonValue(samplingJson, "nativeSourceTileSizePx");
        String nativeResolutionKnown = jsonValue(samplingJson, "nativeSourceResolutionKnown");
        String rasterScale = jsonValue(samplingJson, "rasterScale");
        String rasterWidth = jsonValue(samplingJson, "rasterWidth");
        String rasterHeight = jsonValue(samplingJson, "rasterHeight");
        String effectiveHalfWidthMeters = jsonValue(samplingJson, "effectiveHalfWidthMeters");
        String effectiveStepMeters = jsonValue(samplingJson, "effectiveStepMeters");
        String effectiveHalfWidthPx = jsonValue(samplingJson, "effectiveHalfWidthPx");
        String effectiveStepPx = jsonValue(samplingJson, "effectiveStepPx");
        StringBuilder summary = new StringBuilder();
        if ("rendered-visible-layer".equals(type)) {
            summary.append("visible rendered layer");
        } else if ("managed-source-tiles".equals(type)) {
            summary.append("managed fixed-resolution source tiles");
        } else if (!type.isBlank()) {
            summary.append(type);
        } else {
            summary.append("unknown source");
        }
        if (!algorithm.isBlank()) {
            summary.append(", ").append(algorithm);
        }
        if (!tileZoom.isBlank() && !"null".equals(tileZoom)) {
            summary.append(", source tile z").append(tileZoom);
            if (!bestTileZoom.isBlank() && !"null".equals(bestTileZoom)) {
                summary.append(" (best z").append(bestTileZoom).append(')');
            }
        } else {
            summary.append(", tile zoom unavailable");
        }
        if (!rasterScale.isBlank()) {
            summary.append(", raster ").append(rasterScale).append("x");
        }
        if (!projectionUnitsPerViewPixel.isBlank() && !"null".equals(projectionUnitsPerViewPixel)) {
            summary.append(", capture ").append(formatDouble(projectionUnitsPerViewPixel, 3))
                .append(" projection-units/view-px");
        }
        if (!viewMetersPerPixel.isBlank() && !"null".equals(viewMetersPerPixel)) {
            summary.append(", ground ").append(formatDouble(viewMetersPerPixel, 3)).append(" m/view-px");
        }
        if (!rasterMetersPerPixel.isBlank() && !"null".equals(rasterMetersPerPixel)) {
            summary.append(", sampled ").append(formatDouble(rasterMetersPerPixel, 4)).append(" m/raster-px");
        }
        if ("true".equals(nativeResolutionKnown)
            && !nativeSourcePixelSize.isBlank() && !"null".equals(nativeSourcePixelSize)) {
            if (!nativeTileSize.isBlank() && !"null".equals(nativeTileSize)) {
                summary.append(", ").append(nativeTileSize).append(" px tiles");
            }
            summary.append(", ").append(formatDouble(nativeSourcePixelSize, 2)).append(" raster-px/source-px");
        } else if ("false".equals(nativeResolutionKnown)) {
            summary.append(", native source resolution unavailable");
        }
        if (!effectiveHalfWidthMeters.isBlank() && !"null".equals(effectiveHalfWidthMeters)
                && !effectiveStepMeters.isBlank() && !"null".equals(effectiveStepMeters)) {
            summary.append(", search half ").append(formatDouble(effectiveHalfWidthMeters, 2)).append(" m");
            if (!effectiveHalfWidthPx.isBlank()) {
                summary.append(" (").append(effectiveHalfWidthPx).append(" px)");
            }
            summary.append(", step ").append(formatDouble(effectiveStepMeters, 2)).append(" m");
            if (!effectiveStepPx.isBlank()) {
                summary.append(" (").append(effectiveStepPx).append(" px)");
            }
        }
        if (!rasterWidth.isBlank() && !rasterHeight.isBlank()) {
            summary.append(", capture ").append(rasterWidth).append('x').append(rasterHeight);
        }
        return summary.toString();
    }

    private static String formatDouble(String value, int decimals) {
        try {
            return String.format(java.util.Locale.ROOT, "%." + decimals + "f", Double.parseDouble(value));
        } catch (NumberFormatException ex) {
            return value;
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String jsonString(String json, String key) {
        String value = jsonValue(json, key);
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return "";
    }

    private static String jsonValue(String json, String key) {
        if (json == null || key == null || key.isBlank()) {
            return "";
        }
        String marker = "\"" + key + "\":";
        int start = json.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int index = start + marker.length();
        while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
            index++;
        }
        if (index >= json.length()) {
            return "";
        }
        if (json.charAt(index) == '"') {
            StringBuilder builder = new StringBuilder();
            builder.append('"');
            boolean escaped = false;
            for (int i = index + 1; i < json.length(); i++) {
                char ch = json.charAt(i);
                builder.append(ch);
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    break;
                }
            }
            return builder.toString();
        }
        int end = index;
        while (end < json.length()) {
            char ch = json.charAt(end);
            if (ch == ',' || ch == '}') {
                break;
            }
            end++;
        }
        return json.substring(index, end).trim();
    }

    private static String firstJsonValue(String json, String primaryKey, String fallbackKey) {
        String primary = jsonValue(json, primaryKey);
        return primary.isBlank() ? jsonValue(json, fallbackKey) : primary;
    }
}
