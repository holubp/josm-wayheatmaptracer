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
    boolean simplifyEnabled,
    int crossSectionHalfWidthPx,
    int crossSectionStepPx,
    double simplifyTolerancePx
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
            + "\"simplifyEnabled\":" + simplifyEnabled + ','
            + "\"crossSectionHalfWidthPx\":" + crossSectionHalfWidthPx + ','
            + "\"crossSectionStepPx\":" + crossSectionStepPx + ','
            + "\"simplifyTolerancePx\":" + simplifyTolerancePx
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
