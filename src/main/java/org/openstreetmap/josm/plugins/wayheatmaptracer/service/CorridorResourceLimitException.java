package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

/** Signals that declared modern trace budgets cannot admit the fixed production corridor state space. */
public final class CorridorResourceLimitException extends RuntimeException {
    /** Creates a typed bounded-failure signal. */
    public CorridorResourceLimitException(String message) {
        super(message);
    }
}
