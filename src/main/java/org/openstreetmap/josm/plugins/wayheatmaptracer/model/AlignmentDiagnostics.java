package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

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
    String profileDiagnosticsJson
) {
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
            + "\"profiles\":" + profileDiagnosticsJson
            + "}";
    }

    public String samplingSummary() {
        String type = jsonString(samplingJson, "type");
        String algorithm = jsonString(samplingJson, "algorithm");
        String tileZoom = jsonValue(samplingJson, "tileZoom");
        String bestTileZoom = jsonValue(samplingJson, "bestTileZoom");
        String viewMetersPerPixel = jsonValue(samplingJson, "viewMetersPerPixel");
        String rasterMetersPerPixel = jsonValue(samplingJson, "rasterMetersPerPixel");
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
        if (!viewMetersPerPixel.isBlank() && !"null".equals(viewMetersPerPixel)) {
            summary.append(", view ").append(formatDouble(viewMetersPerPixel, 3)).append(" m/px");
        }
        if (!rasterMetersPerPixel.isBlank() && !"null".equals(rasterMetersPerPixel)) {
            summary.append(", sampled ").append(formatDouble(rasterMetersPerPixel, 4)).append(" m/raster-px");
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
}
