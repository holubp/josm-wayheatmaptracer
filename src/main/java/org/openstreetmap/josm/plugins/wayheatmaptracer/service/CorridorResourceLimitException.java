package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

/** Signals that declared modern trace budgets cannot admit the fixed production corridor state space. */
public final class CorridorResourceLimitException extends RuntimeException {
    public enum Stage { UNAVAILABLE, TRACKER_ADMISSION, ORIGINAL_ADMISSION, PREPARED_ESTIMATE, PREPARED_ADMISSION, PREPARED_TABLE, CUMULATIVE_WORK }
    public enum Reason { UNAVAILABLE, STATE_CAP, DECLARED_WORK_CAP, ARITHMETIC_OVERFLOW, PAIR_WORK_CAP, CUMULATIVE_WORK_CAP, TABLE_OR_WORK_CAP, ADMISSION_RESOURCE_LIMIT }
    public enum Metric { OBSERVED_STATES, LIMIT_STATES, DECLARED_PAIRS, LIMIT_PAIRS, DECLARED_TRANSITIONS,
        LIMIT_TRANSITIONS, CONSUMED_CONTACT_TESTS, CONSUMED_PAIR_TESTS, CONSUMED_PAIR_WORK,
        CONSUMED_TRANSITIONS, OBSERVED_TRACKS, LIMIT_RAW_ALTERNATIVES, DECLARED_STATES, REQUESTED_TABLE_BYTES, LIMIT_TABLE_BYTES }
    private final int rawAlternatives;
    private final Stage stage;
    private final Reason reason;
    private final java.util.Map<Metric, Long> metrics;

    /** Creates a typed bounded-failure signal before raw proposal work is known. */
    public CorridorResourceLimitException(String message) {
        this(message, 0);
    }

    /** Creates a typed bounded-failure signal with completed raw proposal work. */
    public CorridorResourceLimitException(String message, int rawAlternatives) {
        this(message, rawAlternatives, Stage.UNAVAILABLE, Reason.UNAVAILABLE, java.util.Map.of());
    }

    public CorridorResourceLimitException(String message, Stage stage, Reason reason, java.util.Map<Metric, Long> metrics) {
        this(message, 0, stage, reason, metrics);
    }

    public CorridorResourceLimitException(String message, int rawAlternatives, Stage stage, Reason reason, java.util.Map<Metric, Long> metrics) {
        super(message);
        if (rawAlternatives < 0) {
            throw new IllegalArgumentException("Raw alternative count must be nonnegative");
        }
        this.rawAlternatives = rawAlternatives;
        this.stage = java.util.Objects.requireNonNull(stage);
        this.reason = java.util.Objects.requireNonNull(reason);
        this.metrics = java.util.Map.copyOf(metrics);
        if (this.metrics.values().stream().anyMatch(value -> value < 0)) throw new IllegalArgumentException("Negative resource fact");
    }

    public Stage stage() { return stage; }
    public Reason reason() { return reason; }
    public java.util.Map<Metric, Long> metrics() { return metrics; }

    /** Returns raw tracker proposals completed before solver admission failed. */
    public int rawAlternatives() {
        return rawAlternatives;
    }
}
