package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

/** Named B execution contract, independently versioned from its numerical backend. */
public final class ProbabilisticExecutionPolicy {
    /** Terminal exhaustion/diversity proof and cumulative forward/backward plus k-best work. */
    public static final String VERSION = "terminal-path-count-cumulative-work-v1";
    /** Exact dominated-tail selection charged by actual scores including cutoff peeks. */
    public static final String CURRENT_VERSION = "terminal-path-count-scored-work-v2";
    /** Explicit funded raw-one comparison, never selected by production defaults. */
    public static final String CROSS_STREAM_VERSION = "terminal-path-count-cross-stream-raw1-v3";

    /** Explicit selection; old solver entry points retain eager execution. */
    public enum Mode {
        EAGER_V1(VERSION), EXACT_DOMINANCE_V2(CURRENT_VERSION), EXACT_CROSS_STREAM_RAW1_V3(CROSS_STREAM_VERSION);

        private final String version;
        Mode(String version) { this.version = version; }
        public String version() { return version; }
    }

    private ProbabilisticExecutionPolicy() { }
}
