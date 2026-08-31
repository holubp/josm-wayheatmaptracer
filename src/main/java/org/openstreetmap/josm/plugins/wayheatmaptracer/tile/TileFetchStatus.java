package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

/** Safe, structured outcome of one plugin-direct managed tile request. */
public enum TileFetchStatus {
    /** Validated tile returned from network transport. */
    SUCCESS_NETWORK,
    /** Validated tile returned from disk cache. */
    SUCCESS_DISK_CACHE,
    /** Validated tile returned from memory cache. */
    SUCCESS_MEMORY_CACHE,
    /** Source reported no tile for the coordinate. */
    NO_TILE,
    /** Credentials were rejected. */
    AUTH_FAILURE,
    /** Provider rate limit is active. */
    RATE_LIMITED,
    /** Non-authentication HTTP 4xx response. */
    HTTP_CLIENT_ERROR,
    /** HTTP 5xx response. */
    HTTP_SERVER_ERROR,
    /** Other transport/network failure. */
    NETWORK_ERROR,
    /** Connection could not be established before timeout. */
    CONNECT_TIMEOUT,
    /** Connected response did not arrive before timeout. */
    READ_TIMEOUT,
    /** Redirect was rejected by the credential-safety policy. */
    UNSAFE_REDIRECT,
    /** Response type or PNG signature was invalid. */
    CONTENT_TYPE_ERROR,
    /** Encoded response exceeded the byte limit. */
    BODY_TOO_LARGE,
    /** PNG decoding failed. */
    DECODE_ERROR,
    /** Decoded dimensions were not native tile dimensions. */
    BAD_DIMENSIONS,
    /** Existing content heuristic identified an unusable placeholder. */
    PLACEHOLDER_SUSPECTED,
    /** Request was not eligible because the operation is unavailable or expired. */
    OFFLINE,
    /** Consumer or runtime cancelled the request. */
    CANCELLED,
    /** Request belongs to an inactive settings generation. */
    STALE_GENERATION;

    /**
     * Returns whether this status carries a validated usable tile.
     *
     * @return true for network, disk-cache, and memory-cache success
     */
    public boolean usable() {
        return this == SUCCESS_NETWORK || this == SUCCESS_DISK_CACHE || this == SUCCESS_MEMORY_CACHE;
    }
}
