package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

/** Signals that declared modern trace budgets cannot admit the fixed production corridor state space. */
public final class CorridorResourceLimitException extends RuntimeException {
    private final int rawAlternatives;

    /** Creates a typed bounded-failure signal before raw proposal work is known. */
    public CorridorResourceLimitException(String message) {
        this(message, 0);
    }

    /** Creates a typed bounded-failure signal with completed raw proposal work. */
    public CorridorResourceLimitException(String message, int rawAlternatives) {
        super(message);
        if (rawAlternatives < 0) {
            throw new IllegalArgumentException("Raw alternative count must be nonnegative");
        }
        this.rawAlternatives = rawAlternatives;
    }

    /** Returns raw tracker proposals completed before solver admission failed. */
    public int rawAlternatives() {
        return rawAlternatives;
    }
}
