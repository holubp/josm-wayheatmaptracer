package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

/** Named B execution contract, independently versioned from its numerical backend. */
public final class ProbabilisticExecutionPolicy {
    /** Terminal exhaustion/diversity proof and cumulative forward/backward plus k-best work. */
    public static final String VERSION = "terminal-path-count-cumulative-work-v1";

    private ProbabilisticExecutionPolicy() { }
}
