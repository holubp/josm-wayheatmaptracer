package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/** Immutable credential-free result returned to tile consumers. */
public final class TileFetchResult {
    private static final java.util.Set<String> SAFE_REASON_CODES = java.util.Set.of(
        "auth-circuit", "bad-dimensions", "body-too-large", "cancelled", "closed", "connect-timeout", "content-type",
        "deadline", "decode", "empty-body", "http-auth", "http-client", "http-no-tile",
        "http-rate-limit", "http-server", "internal-fetch", "network", "placeholder", "png-header",
        "png-magic", "queue-full", "rate-circuit", "read-timeout", "redirect-blocked",
        "stale-generation", "unclassified"
    );
    private final ManagedTileAddress address;
    private final ManagedTileGeneration generation;
    private final TilePurpose purpose;
    private final TileFetchStatus status;
    private final BufferedImage image;
    private final byte[] encodedBytes;
    private final int httpStatus;
    private final String contentType;
    private final Duration elapsed;
    private final int attempts;
    private final Instant retryNotBefore;
    private final TileQuality quality;
    private final String reasonCode;
    private final String message;

    /**
     * Creates one controlled safe tile result.
     *
     * @param address tile identity
     * @param generation cache/settings generation
     * @param purpose consumer purpose
     * @param status structured outcome
     * @param image decoded image when usable
     * @param encodedBytes validated encoded bytes when usable
     * @param httpStatus HTTP response status, or -1
     * @param contentType response content type
     * @param elapsed transport duration
     * @param attempts transport attempt count
     * @param retryNotBefore optional eligibility deadline
     * @param quality controlled content evidence
     * @param reasonCode controlled reason code
     * @param message controlled safe message
     */
    public TileFetchResult(ManagedTileAddress address, ManagedTileGeneration generation, TilePurpose purpose,
        TileFetchStatus status, BufferedImage image, byte[] encodedBytes, int httpStatus, String contentType,
        Duration elapsed, int attempts, Instant retryNotBefore, TileQuality quality, String reasonCode,
        String message) {
        this.address = Objects.requireNonNull(address, "address");
        this.generation = Objects.requireNonNull(generation, "generation");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.status = Objects.requireNonNull(status, "status");
        this.image = image;
        this.encodedBytes = encodedBytes == null ? new byte[0] : Arrays.copyOf(encodedBytes, encodedBytes.length);
        this.httpStatus = httpStatus;
        this.contentType = sanitizeContentType(contentType);
        this.elapsed = elapsed == null ? Duration.ZERO : elapsed;
        this.attempts = Math.max(0, attempts);
        this.retryNotBefore = retryNotBefore;
        this.quality = quality == null ? TileQuality.unavailable(status.name().toLowerCase()) : quality;
        this.reasonCode = sanitizeReasonCode(reasonCode);
        this.message = message == null ? "" : message;
    }

    /** Returns the tile identity.
     * @return credential-free tile identity */
    public ManagedTileAddress address() { return address; }
    /** Returns the generation.
     * @return cache/settings generation */
    public ManagedTileGeneration generation() { return generation; }
    /** Returns the purpose.
     * @return requesting consumer purpose */
    public TilePurpose purpose() { return purpose; }
    /** Returns the status.
     * @return structured outcome */
    public TileFetchStatus status() { return status; }
    /** Returns the image.
     * @return decoded image, or null */
    public BufferedImage image() { return image; }
    /** Returns encoded data.
     * @return defensive copy of validated encoded bytes */
    public byte[] encodedBytes() { return Arrays.copyOf(encodedBytes, encodedBytes.length); }
    /** Returns the HTTP status.
     * @return HTTP response status, or -1 */
    public int httpStatus() { return httpStatus; }
    /** Returns the content type.
     * @return normalized safe content type */
    public String sanitizedContentType() { return contentType; }
    /** Returns the response size.
     * @return retained validated response byte count */
    public long responseBytes() { return encodedBytes.length; }
    /** Returns elapsed time.
     * @return total transport elapsed time */
    public Duration elapsed() { return elapsed; }
    /** Returns the attempt count.
     * @return transport attempt count */
    public int attemptCount() { return attempts; }
    /** Returns retry eligibility.
     * @return next eligibility time, or null */
    public Instant retryNotBefore() { return retryNotBefore; }
    /** Returns quality evidence.
     * @return controlled content quality evidence */
    public TileQuality quality() { return quality; }
    /** Returns the reason.
     * @return controlled diagnostic reason code */
    public String safeReasonCode() { return reasonCode; }
    /** Returns the message.
     * @return controlled user-facing message */
    public String safeMessage() { return message; }
    /** Checks image usability.
     * @return true when a validated image is available */
    public boolean usable() { return status.usable() && image != null; }

    /** Returns a copy with a cache-hit status and requesting purpose. */
    TileFetchResult as(TileFetchStatus replacement, TilePurpose requestedPurpose) {
        return new TileFetchResult(address, generation, requestedPurpose, replacement, image, encodedBytes,
            httpStatus, contentType, elapsed, attempts, retryNotBefore, quality, reasonCode, message);
    }

    @Override
    public String toString() {
        return "TileFetchResult[address=" + address + ", generation=" + generation.value() + ", purpose="
            + purpose + ", status=" + status + ", httpStatus=" + httpStatus + ", responseBytes="
            + responseBytes() + ", reasonCode=" + reasonCode + "]";
    }

    private static String sanitizeContentType(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String base = value.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        return base.matches("[a-z0-9.+-]+/[a-z0-9.+-]+") ? base : "";
    }

    private static String sanitizeReasonCode(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return SAFE_REASON_CODES.contains(normalized) ? normalized : "unclassified";
    }
}
