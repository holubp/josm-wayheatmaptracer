package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Immutable, all-mode-preserving view of original dense cells and observation rows. */
final class AdaptiveSolverView {
    private final InferenceProfile profile;
    private final int[] indices;
    private final boolean[] fine;
    private final List<ObservationComponent> components;
    private AdaptiveSolverView(InferenceProfile profile, int[] indices, boolean[] fine,
            List<ObservationComponent> components) {
        this.profile = profile;
        this.indices = indices.clone();
        this.fine = fine.clone();
        this.components = List.copyOf(components);
    }
    static AdaptiveSolverView initial(ProbabilisticProfile sampled, InferenceProfile dense,
            List<ObservationComponent> components, double continuation,
            CancellationProbe cancellation, AttemptMemoryLedger.Owner owner) {
        cancellation.checkpoint();
        if (sampled == null || dense == null || components == null || owner == null)
            throw new IllegalArgumentException("Complete original evidence and owner are required");
        try (var scratch = owner.child("adaptive-retention-mask")) {
            int n = dense.cells().size();
            boolean[] keep = mask(n, scratch), fine = mask(n, scratch);
            for (int i = 0; i < n; i++) {
                cancellation.checkpoint(); var cell = dense.cells().get(i);
                if (cell.mandatory() || cell.exactAnchor()) {
                    keep[i] = true; fine[i] = true;
                    if (i > 0) { keep[i - 1] = true; fine[i - 1] = true; }
                    if (i + 1 < n) { keep[i + 1] = true; fine[i + 1] = true; }
                }
                for (var mode : sampled.modes()) {
                    if (cell.offsetMeters() >= mode.coreMinimumMeters() - 0.5 * sampled.sourcePitchMeters()
                            && cell.offsetMeters() <= mode.coreMaximumMeters() + 0.5 * sampled.sourcePitchMeters()) {
                        keep[i] = true; fine[i] = true;
                    }
                }
            }
            if (Double.isFinite(continuation)) {
                int index = nearest(dense, continuation);
                double tolerance = 1e-6 * Math.max(sampled.sourcePitchMeters(), 1.0);
                if (Math.abs(dense.cells().get(index).offsetMeters() - continuation) > tolerance)
                    throw new IllegalArgumentException("Admitted continuation is missing from dense graph");
                keep[index] = true;
            }
            keep[0] = true; keep[n - 1] = true;
            if (n > 1) {
                int intervals = Math.max(1, (int) Math.ceil((sampled.maximumOffsetMeters()
                        - sampled.minimumOffsetMeters()) / (2.0 * sampled.sourcePitchMeters())));
                for (int i = 0; i <= intervals; i++) {
                    cancellation.checkpoint();
                    double target = i == intervals ? sampled.maximumOffsetMeters()
                            : sampled.minimumOffsetMeters() + i * ((sampled.maximumOffsetMeters()
                                - sampled.minimumOffsetMeters()) / intervals);
                    keep[nearest(dense, target)] = true;
                }
            }
            return create(dense, components, keep, fine, cancellation, owner);
        }
    }
    InferenceProfile profile() { return profile; }
    int size() { return indices.length; }
    int sourceIndexAt(int index) { return indices[index]; }
    int[] copySourceIndices(AttemptMemoryLedger.Owner exportOwner) {
        return ProbabilisticInference.allocated(exportOwner,
                AttemptMemoryLedger.arrayBytes(indices.length, Integer.BYTES),
                "adaptive provenance export", () -> indices.clone());
    }
    boolean fine(int compactIndex) { return fine[compactIndex]; }
    List<ObservationComponent> components() { return components; }

    /** Adds original fine cells around all requested rival/credible centers, never around just a winner. */
    static AdaptiveSolverView refined(ProbabilisticProfile sampled, InferenceProfile dense,
            AdaptiveSolverView initial, boolean[] centers, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner) {
        if (centers.length != initial.size()) throw new IllegalArgumentException("Rival mask differs from view");
        try (var scratch = owner.child("adaptive-refinement-mask")) {
            boolean[] keep = mask(dense.cells().size(), scratch), fine = mask(dense.cells().size(), scratch);
            for (int i = 0; i < initial.size(); i++) {
                cancellation.checkpoint(); int original = initial.sourceIndexAt(i);
                keep[original] = true; fine[original] = initial.fine(i);
                if (!centers[i]) continue;
                double center = dense.cells().get(original).offsetMeters();
                for (int j = 0; j < dense.cells().size(); j++) {
                    cancellation.checkpoint();
                    if (Math.abs(dense.cells().get(j).offsetMeters() - center) <= sampled.sourcePitchMeters()) {
                        keep[j] = true; fine[j] = true;
                    }
                }
            }
            return create(dense, initial.components, keep, fine, cancellation, owner);
        }
    }

    private static AdaptiveSolverView create(InferenceProfile dense, List<ObservationComponent> components,
            boolean[] keep, boolean[] originalFine, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner owner) {
        int count = 0; for (boolean value : keep) if (value) count++;
        int n = count;
        try (var scratch = owner.child("adaptive-index-builder")) {
            int[] indices = ProbabilisticInference.allocated(scratch,
                    AttemptMemoryLedger.arrayBytes(n, Integer.BYTES), "adaptive source indices", () -> new int[n]);
            boolean[] fine = mask(n, scratch); int at = 0;
            for (int i = 0; i < keep.length; i++) if (keep[i]) {
                cancellation.checkpoint(); indices[at] = i; fine[at++] = originalFine[i];
            }
            var profile = dense.retainedView(indices, owner);
            long bytes = Math.addExact(ProbabilisticInference.listBytes(components.size()),
                    Math.addExact(AttemptMemoryLedger.objectBytes(32),
                    Math.addExact(AttemptMemoryLedger.arrayBytes(n, Integer.BYTES),
                            AttemptMemoryLedger.arrayBytes(n, Byte.BYTES))));
            return ProbabilisticInference.allocated(owner, bytes, "adaptive source indices",
                    () -> new AdaptiveSolverView(profile, indices, fine, components));
        }
    }

    private static boolean[] mask(int n, AttemptMemoryLedger.Owner owner) {
        return ProbabilisticInference.allocated(owner, AttemptMemoryLedger.arrayBytes(n, Byte.BYTES),
                "adaptive retention mask", () -> new boolean[n]);
    }
    private static int nearest(InferenceProfile profile, double target) {
        int index = 0; double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < profile.cells().size(); i++) {
            double distance = Math.abs(profile.cells().get(i).offsetMeters() - target);
            if (distance < best) { best = distance; index = i; }
        }
        return index;
    }
}
