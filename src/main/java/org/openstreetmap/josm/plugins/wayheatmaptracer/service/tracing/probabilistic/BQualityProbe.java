package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;
import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;

/** Package-private public-fixture seam; no preference, request hash or live caller selects the oracle. */
final class BQualityProbe {
    static final String ORACLE_VERSION="full-lattice-production-backend-numerical-oracle-v1/cached-pair-stream-merge-scored-work-v4";
    interface Sink {
        void dense(List<ProbabilisticProfile> sampled,List<InferenceProfile> dense,TraceBudgets budgets);
        void closed(long retainedBytes,long peakBytes);
        default void inferred(ProbabilisticInferenceResult result) { }
    }
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    static Scope install(boolean fullOracle,Sink sink) {
        if(sink==null||CURRENT.get()!=null)throw new IllegalStateException("Quality probe requires one explicit scope");
        var scope=new Scope(fullOracle,sink);CURRENT.set(scope);return scope;
    }
    static boolean fullOracle(){var s=CURRENT.get();return s!=null&&s.fullOracle;}
    static void dense(List<ProbabilisticProfile> sampled,List<InferenceProfile> dense,TraceBudgets budgets) {
        var s=CURRENT.get();if(s!=null)s.sink.dense(sampled,dense,budgets);
    }
    static void closed(long retainedBytes,long peakBytes){var s=CURRENT.get();if(s!=null)s.sink.closed(retainedBytes,peakBytes);}
    static void inferred(ProbabilisticInferenceResult result){var s=CURRENT.get();if(s!=null)s.sink.inferred(result);}
    static final class Scope implements AutoCloseable {
        final boolean fullOracle;final Sink sink;final Thread thread=Thread.currentThread();boolean closed;
        Scope(boolean fullOracle,Sink sink){this.fullOracle=fullOracle;this.sink=sink;}
        public void close(){if(Thread.currentThread()!=thread)throw new IllegalStateException("Quality scope changed thread");if(!closed){CURRENT.remove();closed=true;}}
    }
    private BQualityProbe() { }
}
