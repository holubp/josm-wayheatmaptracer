package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner;

/** Frozen per-interval production results from one captured slide attempt. */
public record IntervalTraceBatch(TraceRequest fullRequest, EvidenceSnapshot evidence,
        NetworkSnapshot network, SelectedWayIntervalPartitioner.Partition partition,
        List<IntervalRun> runs, ModernTracePipeline.Options options) {
    /** Bound on final route objects retained across one detached attempt. */
    public static final int MAX_RETAINED_ROUTES = 128;
    /** One original interval and its unmodified production inference and ranked routes. */
    public record IntervalRun(SelectedWayIntervalPartitioner.SlideInterval interval,
            TraceRequest request, ModernTracePipeline.Result result, TraceWorkUsage usage) {
        public IntervalRun {
            if (interval == null || request == null || result == null || usage == null
                    || !interval.traceRange().equals(request.selectedRange())
                    || result.inference().engine() != request.engine()) {
                throw new IllegalArgumentException("Interval result does not match its request");
            }
        }

        /** Every route retained by common production final processing, in rank order. */
        public List<ModernTracePipeline.Route> routes() {
            return result.routes();
        }

        /** Engine declaration of discarded alternatives under its bounded budget. */
        public boolean alternativesTruncated() {
            return result.inference().alternativesTruncated();
        }
    }

    /** Typed refusal when a complete interval batch cannot stay inside its attempt budget. */
    public static final class ResourceLimitException extends IllegalStateException {
        public ResourceLimitException(String reason) {
            super("RESOURCE_LIMIT: " + reason);
        }
    }

    /** Validates complete ordered ownership and one immutable evidence/network identity. */
    public IntervalTraceBatch {
        if (fullRequest == null || evidence == null || network == null || partition == null
                || runs == null || options == null || runs.size() != partition.slideIntervals().size()
                || !fullRequest.selectedWayKey().equals(partition.selectedWayKey())
                || !fullRequest.selectedRange().equals(partition.selectedRange())
                || !fullRequest.evidenceSnapshotId().equals(evidence.snapshotId())
                || !fullRequest.evidenceContentHash().equals(evidence.canonicalHash())
                || !fullRequest.networkSnapshotId().equals(network.snapshotId())
                || !fullRequest.networkContentHash().equals(network.canonicalHash())) {
            throw new IllegalArgumentException("Interval batch does not match its captured source");
        }
        runs = List.copyOf(runs);
        for (int index = 0; index < runs.size(); index++) {
            IntervalRun run = runs.get(index);
            if (!run.interval().equals(partition.slideIntervals().get(index))
                    || !run.request().selectedWayKey().equals(fullRequest.selectedWayKey())
                    || !run.request().evidenceContentHash().equals(fullRequest.evidenceContentHash())
                    || !run.request().networkContentHash().equals(fullRequest.networkContentHash())) {
                throw new IllegalArgumentException("Interval run has a different source or order");
            }
        }
        TraceWorkUsage total = TraceWorkUsage.none();
        int retainedRoutes = 0;
        for (IntervalRun run : runs) {
            total = total.plus(run.usage());
            retainedRoutes = Math.addExact(retainedRoutes, run.routes().size());
        }
        if (total.pairVisits() > fullRequest.budgets().maximumPairVisits()
                || total.transitions() > fullRequest.budgets().maximumTransitions()
                || retainedRoutes > MAX_RETAINED_ROUTES) {
            throw new ResourceLimitException("interval results exceed the attempt work or route envelope");
        }
    }

    /** Actual engine work charged across all completed interval runs. */
    public TraceWorkUsage totalUsage() {
        TraceWorkUsage total = TraceWorkUsage.none();
        for (IntervalRun run : runs) {
            total = total.plus(run.usage());
        }
        return total;
    }
}
