package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

public record ManagedHeatmapConfig(
    String keyPairId,
    String policy,
    String signature,
    String sessionToken,
    String activity,
    String color,
    String manualLayerName,
    String layerRegex,
    AlignmentMode alignmentMode,
    boolean verbose,
    boolean debug,
    boolean multiColorDetection,
    boolean parallelWayAwareness,
    boolean allowUndownloadedAlignment,
    boolean adjustJunctionNodes,
    boolean simplifyEnabled,
    int crossSectionHalfWidthPx,
    int crossSectionStepPx,
    double simplifyTolerancePx,
    InferenceMode inferenceMode,
    int inferenceZoom,
    int validationZoom,
    double searchHalfWidthMeters,
    double sampleStepMeters,
    long cacheBuster
) {
    public boolean hasManagedAccessValues() {
        return notBlank(keyPairId) && notBlank(policy) && notBlank(signature) && notBlank(sessionToken);
    }

    public String toCookieHeader() {
        return "CloudFront-Key-Pair-Id=" + keyPairId
            + ";CloudFront-Policy=" + policy
            + ";CloudFront-Signature=" + signature
            + ";_strava_idcf=" + sessionToken;
    }

    public String toRedactedJson() {
        return "{"
            + "\"managedAccessConfigured\":" + hasManagedAccessValues() + ','
            + "\"activity\":\"" + escape(activity) + "\","
            + "\"color\":\"" + escape(color) + "\","
            + "\"manualLayerName\":\"" + escape(manualLayerName) + "\","
            + "\"layerRegex\":\"" + escape(layerRegex) + "\","
            + "\"alignmentMode\":\"" + alignmentMode.name() + "\","
            + "\"verbose\":" + verbose + ','
            + "\"debug\":" + debug + ','
            + "\"multiColorDetection\":" + multiColorDetection + ','
            + "\"parallelWayAwareness\":" + parallelWayAwareness + ','
            + "\"allowUndownloadedAlignment\":" + allowUndownloadedAlignment + ','
            + "\"adjustJunctionNodes\":" + adjustJunctionNodes + ','
            + "\"simplifyEnabled\":" + simplifyEnabled + ','
            + "\"crossSectionHalfWidthPx\":" + crossSectionHalfWidthPx + ','
            + "\"crossSectionStepPx\":" + crossSectionStepPx + ','
            + "\"simplifyTolerancePx\":" + simplifyTolerancePx + ','
            + "\"inferenceMode\":\"" + (inferenceMode == null ? InferenceMode.STABLE_FIXED_SCALE : inferenceMode).name() + "\","
            + "\"inferenceZoom\":" + inferenceZoom + ','
            + "\"validationZoom\":" + validationZoom + ','
            + "\"searchHalfWidthMeters\":" + searchHalfWidthMeters + ','
            + "\"sampleStepMeters\":" + sampleStepMeters + ','
            + "\"cacheBuster\":" + cacheBuster
            + "}";
    }

    public String redactedSummary() {
        return "keyPairId=" + redact(keyPairId)
            + ", policy=" + redact(policy)
            + ", signature=" + redact(signature)
            + ", session=" + redact(sessionToken);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String redact(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
