package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;

/**
 * Pure alignment tile-source plan independent of diagnostic layer visibility.
 *
 * @param selectedColor selected managed source palette
 * @param requiredSelectedColors palettes required for native detection
 * @param optionalAggregateColors additional palettes required by an explicit aggregate detector
 * @param requiredZooms source zooms required by inference and validation
 * @param aggregateDetectorRequested whether complete aggregate acquisition was requested
 */
public record AlignmentTileSourcePlan(
    String selectedColor,
    Set<String> requiredSelectedColors,
    Set<String> optionalAggregateColors,
    Set<Integer> requiredZooms,
    boolean aggregateDetectorRequested
) {
    private static final List<String> BASE_COLORS = List.of("hot", "blue", "bluered", "purple", "gray");

    /**
     * Creates the exact source acquisition plan for one alignment configuration.
     *
     * @param config immutable slide configuration
     * @return source plan independent of diagnostic-layer visibility
     */
    public static AlignmentTileSourcePlan from(ManagedHeatmapConfig config) {
        String selected = BASE_COLORS.contains(config.color()) ? config.color() : "hot";
        boolean colorMapping = config.intensitySamplingMode() == null
            || config.intensitySamplingMode() == IntensitySamplingMode.COLOR_MAPPING;
        boolean aggregate = config.aggregateAllColorSchemes() && colorMapping;
        LinkedHashSet<String> optional = new LinkedHashSet<>();
        if (aggregate) {
            BASE_COLORS.stream().filter(color -> !color.equals(selected)).forEach(optional::add);
        }
        LinkedHashSet<Integer> zooms = new LinkedHashSet<>();
        int inference = Math.max(10, Math.min(16, config.inferenceZoom()));
        int validation = Math.max(10, Math.min(inference, config.validationZoom()));
        zooms.add(inference);
        zooms.add(validation);
        LinkedHashSet<String> required = new LinkedHashSet<>();
        required.add(selected);
        return new AlignmentTileSourcePlan(selected,
            java.util.Collections.unmodifiableSet(required),
            java.util.Collections.unmodifiableSet(optional),
            java.util.Collections.unmodifiableSet(zooms), aggregate);
    }

    /**
     * Returns selected then aggregate palettes in stable acquisition order.
     *
     * @return ordered palette list
     */
    public List<String> orderedColors() {
        LinkedHashSet<String> colors = new LinkedHashSet<>();
        colors.add(selectedColor);
        BASE_COLORS.stream().filter(optionalAggregateColors::contains).forEach(colors::add);
        return List.copyOf(colors);
    }

    /**
     * Serializes a credential-free source plan for support diagnostics.
     *
     * @return safe JSON object
     */
    public String toRedactedJson() {
        return "{\"selectedColor\":\"" + selectedColor + "\",\"requiredSelectedColors\":"
            + json(requiredSelectedColors) + ",\"optionalAggregateColors\":" + json(optionalAggregateColors)
            + ",\"requiredZooms\":" + requiredZooms + ",\"aggregateDetectorRequested\":"
            + aggregateDetectorRequested + '}';
    }

    private static String json(Set<String> values) {
        return values.stream().sorted().map(value -> "\"" + value + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
