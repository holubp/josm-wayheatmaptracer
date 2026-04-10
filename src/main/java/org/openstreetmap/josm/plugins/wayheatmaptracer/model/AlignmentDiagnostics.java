package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

public record AlignmentDiagnostics(
    String layerName,
    int candidateCount,
    int movableNodeCount,
    long rasterCaptureMillis,
    long ridgeTrackingMillis,
    long optimizationMillis,
    String configJson,
    String selectionJson
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
            + "\"selection\":" + selectionJson
            + "}";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

