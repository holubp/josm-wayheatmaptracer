package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

/** Bounded credential-free response returned by the transport boundary. */
public final class TransportResponse {
    private final TileFetchStatus status;
    private final int httpStatus;
    private final String contentType;
    private final byte[] body;
    private final Instant retryNotBefore;
    private final Duration elapsed;
    private final String reasonCode;

    /**
     * Creates one transport response with defensive body ownership.
     *
     * @param status transport status
     * @param httpStatus HTTP response status, or -1
     * @param contentType response content type
     * @param body bounded response body for successful imagery only
     * @param retryNotBefore optional server eligibility deadline
     * @param elapsed transport elapsed time
     * @param reasonCode controlled reason code
     */
    public TransportResponse(TileFetchStatus status, int httpStatus, String contentType, byte[] body,
        Instant retryNotBefore, Duration elapsed, String reasonCode) {
        this.status = status;
        this.httpStatus = httpStatus;
        this.contentType = contentType == null ? "" : contentType;
        this.body = body == null ? new byte[0] : Arrays.copyOf(body, body.length);
        this.retryNotBefore = retryNotBefore;
        this.elapsed = elapsed == null ? Duration.ZERO : elapsed;
        this.reasonCode = reasonCode == null ? "" : reasonCode;
    }

    /** Returns the status.
     * @return transport status */
    public TileFetchStatus status() { return status; }
    /** Returns the HTTP status.
     * @return HTTP response status, or -1 */
    public int httpStatus() { return httpStatus; }
    /** Returns the content type.
     * @return response content type */
    public String contentType() { return contentType; }
    /** Returns the body.
     * @return defensive copy of the bounded body */
    public byte[] body() { return Arrays.copyOf(body, body.length); }
    /** Returns retry eligibility.
     * @return next eligibility time, or null */
    public Instant retryNotBefore() { return retryNotBefore; }
    /** Returns elapsed time.
     * @return transport elapsed time */
    public Duration elapsed() { return elapsed; }
    /** Returns the reason.
     * @return controlled reason code */
    public String reasonCode() { return reasonCode; }
}
