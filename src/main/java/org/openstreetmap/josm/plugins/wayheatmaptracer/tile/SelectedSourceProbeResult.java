package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Credential-free user-facing outcome of one selected managed-source access probe.
 *
 * @param status structured acquisition status
 * @param available whether a validated tile was available
 * @param message controlled user-facing status
 * @param retryNotBefore optional rate/eligibility deadline
 * @param sampledTileCount number of unique tile identities tested
 * @param freshNetworkAttempted whether the probe attempted network access
 */
public record SelectedSourceProbeResult(TileFetchStatus status, boolean available, String message,
                                        Instant retryNotBefore, int sampledTileCount,
                                        boolean freshNetworkAttempted) {
    /**
     * Keeps the original four-field construction contract for callers that
     * represent one already classified tile.
     *
     * @param status structured acquisition status
     * @param available whether a validated tile was available
     * @param message controlled user-facing status
     * @param retryNotBefore optional rate/eligibility deadline
     */
    public SelectedSourceProbeResult(TileFetchStatus status, boolean available, String message,
        Instant retryNotBefore) {
        this(status, available, message, retryNotBefore, 1,
            status != TileFetchStatus.SUCCESS_DISK_CACHE && status != TileFetchStatus.SUCCESS_MEMORY_CACHE);
    }

    /** Validates the aggregate counters. */
    public SelectedSourceProbeResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
        if (sampledTileCount < 1) {
            throw new IllegalArgumentException("At least one sampled tile is required");
        }
    }
    /**
     * Creates the controlled UI result corresponding to a structured tile result.
     *
     * @param result structured tile acquisition result
     * @return credential-free probe outcome
     */
    public static SelectedSourceProbeResult from(TileFetchResult result) {
        return aggregate(List.of(Objects.requireNonNull(result, "result")), false);
    }

    /**
     * Aggregates the safe outcomes of a bounded selected-source stencil.
     *
     * @param results tile outcomes in deterministic request order
     * @param freshNetworkAttempted whether the caller intentionally bypassed positive cache reads
     * @return one credential-free user-facing result
     */
    public static SelectedSourceProbeResult aggregate(List<TileFetchResult> results, boolean freshNetworkAttempted) {
        Objects.requireNonNull(results, "results");
        if (results.isEmpty()) {
            throw new IllegalArgumentException("At least one tile result is required");
        }
        TileFetchResult usable = results.stream().filter(TileFetchResult::usable).findFirst().orElse(null);
        TileFetchResult auth = firstWithStatus(results, TileFetchStatus.AUTH_FAILURE);
        TileFetchResult rate = firstWithStatus(results, TileFetchStatus.RATE_LIMITED);
        TileFetchResult first = results.get(0);
        TileFetchStatus status;
        boolean available;
        String message;
        if (usable != null) {
            status = usable.status();
            available = true;
            message = usable.status() == TileFetchStatus.SUCCESS_NETWORK
                ? "Selected source available - a fresh network tile was validated in the sampled area."
                : "A validated tile is available in the plugin cache; fresh network access was not tested.";
        } else if (auth != null) {
            status = TileFetchStatus.AUTH_FAILURE;
            available = false;
            message = "Selected source authentication failed; refresh the Strava access values.";
        } else if (rate != null) {
            status = TileFetchStatus.RATE_LIMITED;
            available = false;
            message = "Selected source is rate limited; retry after the reported time.";
        } else if (results.stream().allMatch(result -> result.status() == TileFetchStatus.NO_TILE)) {
            status = TileFetchStatus.NO_TILE;
            available = false;
            message = "No tile was returned in the sampled area; selected-source access was not verified. "
                + "The area may have no heatmap coverage.";
        } else {
            status = first.status();
            available = false;
            message = switch (status) {
                case CONNECT_TIMEOUT, READ_TIMEOUT, NETWORK_ERROR, OFFLINE ->
                    "Selected source network access could not be verified.";
                case CONTENT_TYPE_ERROR, BODY_TOO_LARGE, DECODE_ERROR, BAD_DIMENSIONS, PLACEHOLDER_SUSPECTED ->
                    "Selected source returned unusable imagery; see the redacted debug export.";
                case CANCELLED, STALE_GENERATION ->
                    "Selected-source check was cancelled because settings changed.";
                default -> "Selected-source check failed; see the redacted debug export.";
            };
        }
        Instant retryNotBefore = results.stream()
            .map(TileFetchResult::retryNotBefore)
            .filter(Objects::nonNull)
            .min(Instant::compareTo)
            .orElse(null);
        boolean attempted = freshNetworkAttempted || results.stream().anyMatch(result ->
            result.status() != TileFetchStatus.SUCCESS_DISK_CACHE
                && result.status() != TileFetchStatus.SUCCESS_MEMORY_CACHE);
        return new SelectedSourceProbeResult(status, available, message, retryNotBefore,
            results.size(), attempted);
    }

    private static TileFetchResult firstWithStatus(List<TileFetchResult> results, TileFetchStatus status) {
        return results.stream().filter(result -> result.status() == status).findFirst().orElse(null);
    }
}
