package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

public record StravaCookieValues(
    String keyPairId,
    String policy,
    String signature,
    String sessionToken
) {
}
