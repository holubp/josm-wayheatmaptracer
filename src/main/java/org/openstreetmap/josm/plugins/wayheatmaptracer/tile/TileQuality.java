package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

/**
 * Content classification preserved from the v0.20.0 fixed sampler.
 *
 * @param label controlled quality label
 * @param width decoded width
 * @param height decoded height
 * @param opaqueRatio sampled opaque-pixel ratio
 * @param heatCoverage sampled palette-intensity coverage
 * @param sampledColorCount sampled distinct color count
 * @param sha256 validated PNG content digest retained for existing diagnostics
 */
public record TileQuality(
    String label,
    int width,
    int height,
    double opaqueRatio,
    double heatCoverage,
    int sampledColorCount,
    String sha256
) {
    /**
     * Provides a safe empty quality value for transport failures.
     *
     * @param label controlled failure label
     * @return unavailable quality metadata
     */
    public static TileQuality unavailable(String label) {
        return new TileQuality(label, 0, 0, 0.0, 0.0, 0, "");
    }
}
