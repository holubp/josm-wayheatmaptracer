package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

/**
 * Parsed Strava CloudFront access values from a pasted cookie header.
 *
 * @param keyPairId CloudFront key-pair id
 * @param policy CloudFront policy token
 * @param signature CloudFront signature token
 * @param sessionToken Strava identity/session token
 */
public record StravaCookieValues(
    String keyPairId,
    String policy,
    String signature,
    String sessionToken
) {
}
