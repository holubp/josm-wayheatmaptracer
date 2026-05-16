package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/**
 * User-configurable heatmap, alignment, debug, and sampling settings.
 *
 * @param keyPairId Strava CloudFront key-pair id, stored as a sensitive value
 * @param policy Strava CloudFront policy token, stored as a sensitive value
 * @param signature Strava CloudFront signature token, stored as a sensitive value
 * @param sessionToken Strava session cookie token, stored as a sensitive value
 * @param activity selected Strava activity layer such as {@code all} or {@code ride}
 * @param color selected visible Strava color scheme
 * @param manualLayerName explicit non-managed imagery layer name
 * @param layerRegex fallback regular expression for locating a manual heatmap layer
 * @param alignmentMode default apply mode for alignment commands
 * @param verbose whether verbose slide logging is enabled
 * @param debug whether debug overlay rendering is enabled
 * @param multiColorDetection whether alternative detector mappings are shown for the selected color source
 * @param aggregateAllColorSchemes whether managed source colors are fused into one aggregate intensity map
 * @param showAggregateIntensityLayer whether a visual layer of the aggregate intensity map is shown
 * @param candidateRatingEnabled whether preview rating controls are visible
 * @param parallelWayAwareness whether nearby parallel OSM ways are considered as context
 * @param allowUndownloadedAlignment whether OSM downloaded-area checks are bypassed
 * @param adjustJunctionNodes whether endpoints and junction nodes may move
 * @param simplifyEnabled whether plugin simplification runs after alignment
 * @param crossSectionHalfWidthPx visible-layer half-width in view pixels
 * @param crossSectionStepPx visible-layer profile step in view pixels
 * @param simplifyTolerancePx simplification tolerance in view pixels
 * @param inferenceMode source-tile sampling mode
 * @param inferenceZoom preferred inference tile zoom
 * @param validationZoom optional validation tile zoom
 * @param searchHalfWidthMeters fixed-resolution source-tile search half-width
 * @param sampleStepMeters fixed-resolution source-tile profile step
 * @param intensitySamplingMode palette mapping or direct scalar sampling mode
 * @param cacheBuster cache generation marker for managed tiles
 */
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
    boolean aggregateAllColorSchemes,
    boolean showAggregateIntensityLayer,
    boolean candidateRatingEnabled,
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
    IntensitySamplingMode intensitySamplingMode,
    long cacheBuster
) {
    /**
     * Checks whether all managed Strava access fields are configured.
     *
     * @return {@code true} when signed managed tile URLs can be created
     */
    public boolean hasManagedAccessValues() {
        return notBlank(keyPairId) && notBlank(policy) && notBlank(signature) && notBlank(sessionToken);
    }

    /**
     * Returns a copy with a temporary alignment-mode override.
     *
     * @param mode requested one-shot mode, or {@code null} to keep the current setting
     * @return copied configuration with the effective mode
     */
    public ManagedHeatmapConfig withAlignmentMode(AlignmentMode mode) {
        return new ManagedHeatmapConfig(
            keyPairId,
            policy,
            signature,
            sessionToken,
            activity,
            color,
            manualLayerName,
            layerRegex,
            mode == null ? alignmentMode : mode,
            verbose,
            debug,
            multiColorDetection,
            aggregateAllColorSchemes,
            showAggregateIntensityLayer,
            candidateRatingEnabled,
            parallelWayAwareness,
            allowUndownloadedAlignment,
            adjustJunctionNodes,
            simplifyEnabled,
            crossSectionHalfWidthPx,
            crossSectionStepPx,
            simplifyTolerancePx,
            inferenceMode,
            inferenceZoom,
            validationZoom,
            searchHalfWidthMeters,
            sampleStepMeters,
            intensitySamplingMode,
            cacheBuster
        );
    }

    /**
     * Builds the cookie header needed for managed Strava heatmap tile requests.
     *
     * @return raw cookie header; callers must never write this value to debug exports
     */
    public String toCookieHeader() {
        return "CloudFront-Key-Pair-Id=" + keyPairId
            + ";CloudFront-Policy=" + policy
            + ";CloudFront-Signature=" + signature
            + ";_strava_idcf=" + sessionToken;
    }

    /**
     * Serializes non-secret settings for logs and debug bundles.
     *
     * @return JSON object with access values represented only by a boolean flag
     */
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
            + "\"aggregateAllColorSchemes\":" + aggregateAllColorSchemes + ','
            + "\"showAggregateIntensityLayer\":" + showAggregateIntensityLayer + ','
            + "\"candidateRatingEnabled\":" + candidateRatingEnabled + ','
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
            + "\"intensitySamplingMode\":\"" + (intensitySamplingMode == null ? IntensitySamplingMode.COLOR_MAPPING : intensitySamplingMode).name() + "\","
            + "\"cacheBuster\":" + cacheBuster
            + "}";
    }

    /**
     * Summarizes sensitive fields with short redacted markers for console logging.
     *
     * @return human-readable redacted access summary
     */
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
