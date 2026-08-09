package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.Locale;
import java.util.Set;

/** Central source-palette compatibility metadata for detector mappings. */
public final class DetectorFamily {
    private static final Set<String> HOT = Set.of("hot", "hot-corridor", "hot-strict");
    private static final Set<String> BLUERED = Set.of(
        "bluered", "bluered-cool", "bluered-corridor", "bluered-combined");
    private static final Set<String> GRAY = Set.of(
        "gray", "gray-magenta", "gray-corridor", "gray-strict", "gray-combined");
    private static final Set<String> PURPLE = Set.of("purple", "purple-corridor", "purple-strict");
    private static final Set<String> BLUE = Set.of("blue", "blue-corridor");

    private DetectorFamily() {
        // Utility class.
    }

    /**
     * Returns whether a mapping has native semantics for the configured source palette.
     *
     * @param sourcePalette configured source palette
     * @param mapping detector mapping name
     * @return true when the mapping belongs to the source's native family
     */
    public static boolean isNative(String sourcePalette, String mapping) {
        String source = normalize(sourcePalette);
        String detector = normalize(mapping);
        return switch (source) {
            case "hot" -> HOT.contains(detector);
            case "bluered" -> BLUERED.contains(detector);
            case "gray" -> GRAY.contains(detector);
            case "purple" -> PURPLE.contains(detector);
            case "blue" -> BLUE.contains(detector);
            default -> detector.equals(source);
        };
    }

    /**
     * Returns ranking tier: complete aggregate/native mapping 1, alternative mapping 0.
     *
     * @param sourcePalette configured source palette
     * @param mapping detector mapping name
     * @return source compatibility tier
     */
    public static int sourceTier(String sourcePalette, String mapping) {
        String detector = normalize(mapping);
        if ("all-colors-combined".equals(detector)) {
            return 1;
        }
        return isNative(sourcePalette, detector) ? 1 : 0;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
