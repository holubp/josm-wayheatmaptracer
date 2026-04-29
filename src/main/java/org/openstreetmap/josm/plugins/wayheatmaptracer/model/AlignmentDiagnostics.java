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
    String candidatesJson
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
            + "\"candidates\":" + candidatesJson
            + "}";
    }

    public String samplingSummary() {
        String type = jsonString(samplingJson, "type");
        String algorithm = jsonString(samplingJson, "algorithm");
        String tileZoom = jsonValue(samplingJson, "tileZoom");
        String bestTileZoom = jsonValue(samplingJson, "bestTileZoom");
        String rasterScale = jsonValue(samplingJson, "rasterScale");
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
            summary.append(", tile z").append(tileZoom);
            if (!bestTileZoom.isBlank() && !"null".equals(bestTileZoom)) {
                summary.append(" (best z").append(bestTileZoom).append(')');
            }
        } else {
            summary.append(", tile zoom unavailable");
        }
        if (!rasterScale.isBlank()) {
            summary.append(", raster ").append(rasterScale).append("x");
        }
        return summary.toString();
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
