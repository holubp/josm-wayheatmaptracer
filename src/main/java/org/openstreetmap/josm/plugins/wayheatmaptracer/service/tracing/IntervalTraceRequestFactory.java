package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorTraceInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedProfileSamplingLocation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;

/** Builds one detached engine request for an original selected-way occurrence interval. */
public final class IntervalTraceRequestFactory {
    /** Reuses the one frozen source and rebases only physical chainage within the trace range. */
    public TraceRequest create(TraceRequest fullRequest, LiveBPreviewService.Captured captured,
            EvidenceSnapshot evidence, NetworkSnapshot network,
            SelectedWayIntervalPartitioner.SlideInterval interval) {
        return create(fullRequest, captured, evidence, network, interval,
                fullRequest == null ? null : fullRequest.budgets());
    }

    /** Constructs an interval request with attempt-wide remaining work limits. */
    public TraceRequest create(TraceRequest fullRequest, LiveBPreviewService.Captured captured,
            EvidenceSnapshot evidence, NetworkSnapshot network,
            SelectedWayIntervalPartitioner.SlideInterval interval, TraceBudgets remainingBudgets) {
        if (fullRequest == null || captured == null || evidence == null || network == null
                || interval == null || remainingBudgets == null || captured.specification() == null
                || remainingBudgets.maximumStatesPerProfile()
                        != fullRequest.budgets().maximumStatesPerProfile()
                || remainingBudgets.maximumPairVisits() > fullRequest.budgets().maximumPairVisits()
                || remainingBudgets.maximumTransitions() > fullRequest.budgets().maximumTransitions()
                || remainingBudgets.maximumRawAlternatives()
                        != fullRequest.budgets().maximumRawAlternatives()
                || remainingBudgets.maximumDistinctAlternatives()
                        != fullRequest.budgets().maximumDistinctAlternatives()
                || !captured.specification().selectedWayKey().equals(fullRequest.selectedWayKey())
                || !captured.specification().selectedRange().equals(fullRequest.selectedRange())
                || captured.engine() != fullRequest.engine()
                || captured.geometryMode() != fullRequest.geometryMode()
                || !captured.specification().permissions().equals(fullRequest.permissions())
                || !fullRequest.settingsHash().equals(captured.settingsHash())
                || !fullRequest.parameterHash().equals(captured.parameterHash())
                || captured.sampleStepMeters() != fullRequest.configuredSampleStepMeters()
                || !network.equals(captured.network())
                || !fullRequest.networkSnapshotId().equals(network.snapshotId())
                || !fullRequest.networkContentHash().equals(network.canonicalHash())
                || !fullRequest.evidenceSnapshotId().equals(evidence.snapshotId())
                || !fullRequest.evidenceContentHash().equals(evidence.canonicalHash())
                || !fullRequest.evidenceResolution().equals(evidence.resolution())) {
            throw new IllegalArgumentException("Interval request source does not match its frozen capture");
        }
        OccurrenceRange full = fullRequest.selectedRange();
        OccurrenceRange trace = interval.traceRange();
        OccurrenceRange owned = interval.range();
        if (trace.firstIndex() < full.firstIndex() || trace.lastIndex() > full.lastIndex()
                || trace.firstIndex() < owned.firstIndex() - 1
                || trace.lastIndex() > owned.lastIndex() + 1
                || captured.sourceGeographic().size() != full.size()
                || captured.sourceMetric().size() != full.size()) {
            throw new IllegalArgumentException("Interval trace range exceeds its original ownership");
        }
        if (!(network.primitives().get(fullRequest.selectedWayKey()) instanceof DetachedWay way)
                || way.nodeKeys().size() <= trace.lastIndex()) {
            throw new IllegalArgumentException("Interval selected way is absent from the network");
        }
        List<PrimitiveKey> ownedKeys = way.nodeKeys().subList(owned.firstIndex(), owned.lastIndex() + 1);
        if (!ownedKeys.equals(interval.occurrenceKeys())
                || !way.nodeKeys().get(trace.firstIndex()).equals(interval.startBoundary().nodeKey())
                || !way.nodeKeys().get(trace.lastIndex()).equals(interval.endBoundary().nodeKey())) {
            throw new IllegalArgumentException("Interval occurrence identities changed");
        }
        int start = trace.firstIndex() - full.firstIndex();
        int end = trace.lastIndex() - full.firstIndex() + 1;
        List<GeographicPoint> geographic = captured.sourceGeographic().subList(start, end);
        List<MetricPoint> metric = captured.sourceMetric().subList(start, end);
        for (int local = 0; local < geographic.size(); local++) {
            PrimitiveKey key = way.nodeKeys().get(trace.firstIndex() + local);
            if (!(network.primitives().get(key) instanceof DetachedNode node)
                    || !node.coordinate().equals(geographic.get(local))
                    || evidence.coordinateFrame().toMetric(geographic.get(local))
                            .distanceTo(metric.get(local)) > 1.0e-8) {
                throw new IllegalArgumentException("Interval geographic and metric anchors differ from snapshot");
            }
        }
        ProfileChainage chainage = new ProbabilisticProfileFactory().profileChainage(metric,
                fullRequest.configuredSampleStepMeters());
        double sourceOrigin = fullRequest.profileChainage().sourceOriginGroundMeters();
        for (int index = 1; index <= start; index++) {
            sourceOrigin += captured.sourceMetric().get(index - 1)
                    .distanceTo(captured.sourceMetric().get(index));
        }
        chainage = chainage.withSourceOrigin(sourceOrigin);
        Optional<CorridorTraceInput> corridor = Optional.empty();
        if (fullRequest.engine() == TrackerMode.CORRIDOR_AWARE
                || fullRequest.engine() == TrackerMode.HYBRID) {
            CorridorTraceInput derived = CorridorTraceInput.from(chainage, metric,
                    evidence.coordinateFrame(), evidence.transform(),
                    evidence.resolution().outputRasterPitchMeters());
            List<DetachedProfileSamplingLocation> locations = new ArrayList<>(derived.profileLocations());
            locations.set(0, DetachedProfileSamplingLocation.at(geographic.get(0),
                    evidence.coordinateFrame(), evidence.transform(), 0.0));
            int last = locations.size() - 1;
            locations.set(last, DetachedProfileSamplingLocation.at(geographic.get(geographic.size() - 1),
                    evidence.coordinateFrame(), evidence.transform(),
                    chainage.cumulativeGroundMeters().get(last)));
            corridor = Optional.of(new CorridorTraceInput(locations, derived.lateralStepMeters()));
        }
        return new TraceRequest(fullRequest.selectedWayKey(), trace, fullRequest.engine(),
                fullRequest.geometryMode(), fullRequest.permissions(), remainingBudgets,
                fullRequest.evidenceSnapshotId(), fullRequest.evidenceContentHash(),
                fullRequest.networkSnapshotId(), fullRequest.networkContentHash(),
                fullRequest.settingsHash(), fullRequest.parameterHash(), fullRequest.samplerId(),
                fullRequest.configuredSampleStepMeters(), chainage,
                fullRequest.evidenceResolution(), corridor);
    }
}
