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
    /** One original interval and its unmodified production inference and ranked routes. */
    public record IntervalRun(SelectedWayIntervalPartitioner.SlideInterval interval,
            TraceRequest request, ModernTracePipeline.Result result) {
        public IntervalRun {
            if (interval == null || request == null || result == null
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
    }
}
