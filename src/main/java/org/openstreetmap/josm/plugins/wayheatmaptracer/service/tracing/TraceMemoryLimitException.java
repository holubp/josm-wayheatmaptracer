package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

/**
 * Control-plane abort used when a charged native resource-limit result cannot itself fit.
 *
 * <p>The exception deliberately carries no hypothesis set or posterior. Later attempt owners must
 * translate it to a bounded {@code RESOURCE_LIMIT} sidecar rather than a generic failure.</p>
 */
public final class TraceMemoryLimitException extends RuntimeException {
    private final String stage;
    private final long pairVisits;
    private final long transitions;
    private final long currentBytes;
    private final long peakBytes;
    private final long limitBytes;

    /** Creates one factual abort after all candidate and working scopes have been closed. */
    public TraceMemoryLimitException(String stage, long pairVisits, long transitions,
            long currentBytes, long peakBytes, long limitBytes, Throwable cause) {
        super(stage + ": attempt memory limit prevented a native RESOURCE_LIMIT result; current="
                + currentBytes + ", peak=" + peakBytes + ", limit=" + limitBytes, cause);
        if (stage == null || stage.isBlank() || pairVisits < 0L || transitions < 0L
                || currentBytes < 0L || peakBytes < currentBytes || peakBytes > limitBytes
                || limitBytes <= 0L
                || !(cause instanceof AttemptMemoryLedger.ResourceLimitException)) {
            throw new IllegalArgumentException("Trace memory abort evidence is invalid");
        }
        this.stage = stage;
        this.pairVisits = pairVisits;
        this.transitions = transitions;
        this.currentBytes = currentBytes;
        this.peakBytes = peakBytes;
        this.limitBytes = limitBytes;
    }

    public String stage() { return stage; }
    public long pairVisits() { return pairVisits; }
    public long transitions() { return transitions; }
    public long currentBytes() { return currentBytes; }
    public long peakBytes() { return peakBytes; }
    public long limitBytes() { return limitBytes; }
}
