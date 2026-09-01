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
 * @param trackerMode ridge-tracking implementation used for candidate detection
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
    TrackerMode trackerMode,
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
     * Normalizes the optional tracker input to the effective persisted-settings default.
     */
    public ManagedHeatmapConfig {
        trackerMode = trackerMode == null ? TrackerMode.defaultMode() : trackerMode;
    }

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
            trackerMode,
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
     * Returns an in-memory copy with a different physical managed-tile search width.
     *
     * <p>This helper is used only by a slide-time retry configuration. Callers must not persist
     * the returned object unless they intentionally want to change the normal setting.</p>
     *
     * @param halfWidthMeters one-shot physical cross-section half-width
     * @return copied configuration with the requested search width
     */
    public ManagedHeatmapConfig withSearchHalfWidthMeters(double halfWidthMeters) {
        if (!Double.isFinite(halfWidthMeters) || halfWidthMeters <= 0.0) {
            throw new IllegalArgumentException("Search half-width must be finite and positive");
        }
        return new ManagedHeatmapConfig(
            keyPairId, policy, signature, sessionToken, activity, color, manualLayerName, layerRegex,
            alignmentMode, trackerMode, verbose, debug, multiColorDetection, aggregateAllColorSchemes,
            showAggregateIntensityLayer, candidateRatingEnabled, parallelWayAwareness,
            allowUndownloadedAlignment, adjustJunctionNodes, simplifyEnabled, crossSectionHalfWidthPx,
            crossSectionStepPx, simplifyTolerancePx, inferenceMode, inferenceZoom, validationZoom,
            halfWidthMeters, sampleStepMeters, intensitySamplingMode, cacheBuster
        );
    }

    /**
     * Tests whether another configuration still identifies the same managed tile source generation.
     *
     * <p>The comparison intentionally includes sensitive values without exposing them. It is used
     * only to reject a stale retry after the user has refreshed source settings.</p>
     *
     * @param other current configuration to compare
     * @return true when managed credentials, generation, activity, and visible source agree
     */
    public boolean hasSameManagedSource(ManagedHeatmapConfig other) {
        return other != null
            && cacheBuster == other.cacheBuster
            && java.util.Objects.equals(keyPairId, other.keyPairId)
            && java.util.Objects.equals(policy, other.policy)
            && java.util.Objects.equals(signature, other.signature)
            && java.util.Objects.equals(sessionToken, other.sessionToken)
            && java.util.Objects.equals(activity, other.activity)
            && java.util.Objects.equals(color, other.color);
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
            + "\"trackerMode\":\"" + trackerMode.name() + "\","
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
        return "managedAccessConfigured=" + hasManagedAccessValues();
    }

    @Override
    public String toString() {
        return "ManagedHeatmapConfig" + toRedactedJson();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
