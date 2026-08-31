package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import java.time.Instant;

/**
 * Credential-free user-facing outcome of one selected managed-source access probe.
 *
 * @param status structured acquisition status
 * @param available whether a validated tile was available
 * @param message controlled user-facing status
 * @param retryNotBefore optional rate/eligibility deadline
 */
public record SelectedSourceProbeResult(TileFetchStatus status, boolean available, String message,
                                        Instant retryNotBefore) {
    /**
     * Creates the controlled UI result corresponding to a structured tile result.
     *
     * @param result structured tile acquisition result
     * @return credential-free probe outcome
     */
    public static SelectedSourceProbeResult from(TileFetchResult result) {
        String message = switch (result.status()) {
            case SUCCESS_NETWORK -> "Selected source available - fresh network tile.";
            case SUCCESS_DISK_CACHE, SUCCESS_MEMORY_CACHE -> "Selected source available - plugin cache.";
            case AUTH_FAILURE -> "Selected source authentication failed - update the Strava access values.";
            case RATE_LIMITED -> "Selected source is rate limited; retry after the reported time.";
            case NO_TILE -> "No selected-source tile exists at the probe coordinate; try another visible location.";
            case CONNECT_TIMEOUT, READ_TIMEOUT, NETWORK_ERROR, OFFLINE -> "Selected source network is unavailable.";
            case CONTENT_TYPE_ERROR, BODY_TOO_LARGE, DECODE_ERROR, BAD_DIMENSIONS, PLACEHOLDER_SUSPECTED ->
                "Selected source returned unusable imagery; see the redacted debug export.";
            case CANCELLED, STALE_GENERATION -> "Selected-source check was cancelled because settings changed.";
            default -> "Selected-source check failed; see the redacted debug export.";
        };
        return new SelectedSourceProbeResult(result.status(), result.usable(), message, result.retryNotBefore());
    }
}
