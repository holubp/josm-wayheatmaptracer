package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;

/** Exact root item admission and conservative companion-owned retained/copy peak. */
final class ReplayOutputAdmission {
    static final int MAX_ITEMS = 500_000;
    static final int MAX_ROUTES = 4_096;
    static final int MAX_HYPOTHESES = 4_096;
    static final int MAX_STRING_BYTES = 1_048_576;
    private static final long SCRATCH_BYTES = 8L * MAX_STRING_BYTES;

    private ReplayOutputAdmission() { }

    static long rootItems(Format15ReplayRunner.Result output) {
        Budget root = new Budget(0, false);
        root.output(output);
        return root.items;
    }

    static boolean isBudget(IllegalArgumentException failure) {
        return failure instanceof BudgetExceeded
                || "final-output-budget".equals(failure.getMessage())
                || "scalar-output-budget".equals(failure.getMessage());
    }

    static final class BudgetExceeded extends IllegalArgumentException {
        BudgetExceeded() { super("final-output-budget"); }
    }

    static final class Budget {
        private long items;
        private long retained;
        private final boolean checkPeak;

        Budget(long alreadyRetained) { this(alreadyRetained, true); }

        private Budget(long alreadyRetained, boolean checkPeak) {
            if (alreadyRetained < 0) throw new IllegalArgumentException("retention-invalid");
            this.retained = alreadyRetained;
            this.checkPeak = checkPeak;
            peak();
        }

        long items() { return items; }

        void output(Format15ReplayRunner.Result output) {
            if (output == null) throw new IllegalArgumentException("final-output-invalid");
            output(output.inference(), output.routes());
        }

        void output(org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet inference,
                java.util.List<org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline.Route> routes) {
            if (inference == null || routes == null || routes.size() > MAX_ROUTES
                    || inference.hypotheses().size() > MAX_HYPOTHESES) {
                throw new IllegalArgumentException("final-output-invalid");
            }
            result();
            for (TraceHypothesis hypothesis : inference.hypotheses()) hypothesis(hypothesis);
            for (var route : routes) {
                if (route == null) throw new IllegalArgumentException("final-output-invalid");
                route();
                hypothesis(route.rawHypothesis());
                hypothesis(route.hypothesis());
                items(route.pointIds().size());
                items(route.assignments().size());
                items(route.sourceOwnership().size());
                retain(Math.multiplyExact(768L, route.pointIds().size()));
                findings(route.quality().findings().size());
            }
        }

        private void hypothesis(TraceHypothesis hypothesis) {
            if (hypothesis == null) throw new IllegalArgumentException("final-output-invalid");
            hypothesis();
            points(hypothesis.points().size());
            support(hypothesis.support().size());
            diagnostics(hypothesis.diagnostics().size());
        }

        // Bounds include simultaneous mutable/immutable storage, value objects, map entries
        // and constructor validation sets. String characters are separate; enums are shared.
        // This is conservative admission, not VM-specific heap telemetry.
        void result() { retain(64L * 1024); }
        void route() { retain(512); } // Route count is not a canonical root item.
        void hypothesis() { items(1); retain(256); }
        void points(int count) { items(count); retain(Math.multiplyExact(128L, count)); }
        void support(int count) { items(count); retain(Math.multiplyExact(32L, count)); }
        void diagnostics(int count) { items(count); retain(Math.multiplyExact(192L, count)); }
        void rows(int count) { items(Math.multiplyExact(3L, count)); retain(Math.multiplyExact(768L, count)); }
        void findings(int count) { items(count); retain(Math.multiplyExact(192L, count)); }
        void metadata(int utf8Bytes) { retain(Math.multiplyExact(4L, utf8Bytes)); }
        void payload(long bytes, int simultaneousCopies) {
            retain(Math.multiplyExact(bytes, simultaneousCopies));
        }

        private void items(long count) {
            if (count < 0 || count > MAX_ITEMS - items) throw new BudgetExceeded();
            items += count;
        }

        private void retain(long bytes) {
            if (!checkPeak) return; // Canonical root admission has no companion retention limit.
            if (bytes < 0 || bytes > Format15Safety.MAX_TOTAL_BYTES - retained) throw new BudgetExceeded();
            retained += bytes;
            peak();
        }

        private void peak() {
            if (checkPeak && retained > Format15Safety.MAX_TOTAL_BYTES - SCRATCH_BYTES) {
                throw new BudgetExceeded();
            }
        }
    }
}
