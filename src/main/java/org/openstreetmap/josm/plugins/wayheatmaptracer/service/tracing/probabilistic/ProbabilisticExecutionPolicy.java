package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

/** Named B execution contract, independently versioned from its numerical backend. */
public final class ProbabilisticExecutionPolicy {
    /** Terminal exhaustion/diversity proof and cumulative forward/backward plus k-best work. */
    public static final String VERSION = "terminal-path-count-cumulative-work-v1";
    /** Exact dominated-tail selection charged by actual scores including cutoff peeks. */
    public static final String CURRENT_VERSION = "terminal-path-count-scored-work-v2";

    /** Explicit selection; old solver entry points retain eager execution. */
    public enum Mode {
        EAGER_V1(VERSION), EXACT_DOMINANCE_V2(CURRENT_VERSION);

        private final String version;
        Mode(String version) { this.version = version; }
        public String version() { return version; }
    }

    private ProbabilisticExecutionPolicy() { }
}
