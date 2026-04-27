package org.openstreetmap.josm.plugins.wayheatmaptracer.config;

import java.util.Objects;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.IPreferences;

public final class PluginPreferences {
    private static final String PREFIX = "wayheatmaptracer.";
    private static final String KEY_PAIR_ID = PREFIX + "keyPairId";
    private static final String POLICY = PREFIX + "policy";
    private static final String SIGNATURE = PREFIX + "signature";
    private static final String SESSION = PREFIX + "session";
    private static final String ACTIVITY = PREFIX + "activity";
    private static final String COLOR = PREFIX + "color";
    private static final String MANUAL_LAYER = PREFIX + "manualLayerName";
    private static final String REGEX = PREFIX + "layerRegex";
    private static final String ALIGNMENT_MODE = PREFIX + "alignmentMode";
    private static final String VERBOSE = PREFIX + "verbose";
    private static final String DEBUG = PREFIX + "debug";
    private static final String MULTI_COLOR_DETECTION = PREFIX + "multiColorDetection";
    private static final String PARALLEL_WAY_AWARENESS = PREFIX + "parallelWayAwareness";
    private static final String ALLOW_UNDOWNLOADED_ALIGNMENT = PREFIX + "allowUndownloadedAlignment";
    private static final String ADJUST_JUNCTION_NODES = PREFIX + "adjustJunctionNodes";
    private static final String SIMPLIFY_ENABLED = PREFIX + "simplifyEnabled";
    private static final String CROSS_SECTION_HALF_WIDTH = PREFIX + "crossSectionHalfWidthPx";
    private static final String CROSS_SECTION_STEP = PREFIX + "crossSectionStepPx";
    private static final String SIMPLIFY_TOLERANCE = PREFIX + "simplifyTolerancePx";

    private PluginPreferences() {
    }

    public static ManagedHeatmapConfig load() {
        IPreferences pref = Config.getPref();
        if (pref == null) {
            return defaultConfig();
        }
        return new ManagedHeatmapConfig(
            pref.get(KEY_PAIR_ID, ""),
            pref.get(POLICY, ""),
            pref.get(SIGNATURE, ""),
            pref.get(SESSION, ""),
            pref.get(ACTIVITY, "all"),
            pref.get(COLOR, "hot"),
            pref.get(MANUAL_LAYER, ""),
            pref.get(REGEX, ".*(Heatmap|Strava).*"),
            AlignmentMode.fromPreference(pref.get(ALIGNMENT_MODE, AlignmentMode.MOVE_EXISTING_NODES.name())),
            pref.getBoolean(VERBOSE, false),
            pref.getBoolean(DEBUG, false),
            pref.getBoolean(MULTI_COLOR_DETECTION, false),
            pref.getBoolean(PARALLEL_WAY_AWARENESS, true),
            pref.getBoolean(ALLOW_UNDOWNLOADED_ALIGNMENT, false),
            pref.getBoolean(ADJUST_JUNCTION_NODES, false),
            pref.getBoolean(SIMPLIFY_ENABLED, false),
            pref.getInt(CROSS_SECTION_HALF_WIDTH, 18),
            pref.getInt(CROSS_SECTION_STEP, 4),
            pref.getDouble(SIMPLIFY_TOLERANCE, 3.0)
        );
    }

    public static void save(ManagedHeatmapConfig config) {
        Objects.requireNonNull(config, "config");
        Config.getPref().put(KEY_PAIR_ID, nullToEmpty(config.keyPairId()));
        putSensitive(POLICY, config.policy());
        putSensitive(SIGNATURE, config.signature());
        putSensitive(SESSION, config.sessionToken());
        Config.getPref().put(ACTIVITY, nullToEmpty(config.activity()));
        Config.getPref().put(COLOR, nullToEmpty(config.color()));
        Config.getPref().put(MANUAL_LAYER, nullToEmpty(config.manualLayerName()));
        Config.getPref().put(REGEX, nullToEmpty(config.layerRegex()));
        Config.getPref().put(ALIGNMENT_MODE, config.alignmentMode().name());
        Config.getPref().putBoolean(VERBOSE, config.verbose());
        Config.getPref().putBoolean(DEBUG, config.debug());
        Config.getPref().putBoolean(MULTI_COLOR_DETECTION, config.multiColorDetection());
        Config.getPref().putBoolean(PARALLEL_WAY_AWARENESS, config.parallelWayAwareness());
        Config.getPref().putBoolean(ALLOW_UNDOWNLOADED_ALIGNMENT, config.allowUndownloadedAlignment());
        Config.getPref().putBoolean(ADJUST_JUNCTION_NODES, config.adjustJunctionNodes());
        Config.getPref().putBoolean(SIMPLIFY_ENABLED, config.simplifyEnabled());
        Config.getPref().putInt(CROSS_SECTION_HALF_WIDTH, config.crossSectionHalfWidthPx());
        Config.getPref().putInt(CROSS_SECTION_STEP, config.crossSectionStepPx());
        Config.getPref().putDouble(SIMPLIFY_TOLERANCE, config.simplifyTolerancePx());
    }

    public static boolean isVerboseEnabled() {
        return load().verbose();
    }

    public static boolean isDebugEnabled() {
        return load().debug();
    }

    private static void putSensitive(String key, String value) {
        Config.getPref().put(key, value == null ? "" : value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static ManagedHeatmapConfig defaultConfig() {
        return new ManagedHeatmapConfig(
            "", "", "", "",
            "all",
            "hot",
            "",
            ".*(Heatmap|Strava).*",
            AlignmentMode.MOVE_EXISTING_NODES,
            false,
            false,
            true,
            false,
            false,
            false,
            false,
            18,
            4,
            3.0
        );
    }
}
