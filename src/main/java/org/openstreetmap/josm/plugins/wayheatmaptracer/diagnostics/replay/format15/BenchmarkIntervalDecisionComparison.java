package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.Objects;

import org.openstreetmap.josm.plugins.wayheatmaptracer.BenchmarkDecisionComparatorMain;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner;

/** Typed comparison of the production interval replay envelope. */
public final class BenchmarkIntervalDecisionComparison {
    private BenchmarkIntervalDecisionComparison() { }

    public static void compare(Format15Archive firstArchive, Format15Archive secondArchive) {
        var first = FrozenIntervalReplayCodec.decode(firstArchive.artifact(
                FrozenIntervalReplayCodec.ARTIFACT).orElseThrow(() ->
                    new IllegalStateException("Baseline interval input missing")).bytes());
        var second = FrozenIntervalReplayCodec.decode(secondArchive.artifact(
                FrozenIntervalReplayCodec.ARTIFACT).orElseThrow(() ->
                    new IllegalStateException("Candidate interval input missing")).bytes());
        BenchmarkDecisionComparatorMain.requireSame(first.shared(), second.shared());
        requireSameAuthority(first.authority(), second.authority());
        var firstPartition = SelectedWayIntervalPartitioner.partition(first.shared().network(),
                first.authority());
        var secondPartition = SelectedWayIntervalPartitioner.partition(second.shared().network(),
                second.authority());
        if (!FrozenReplayCodec.partitionProofHash(firstPartition).equals(first.partitionProofHash())
                || !FrozenReplayCodec.partitionProofHash(secondPartition).equals(second.partitionProofHash())) {
            throw new IllegalStateException("Native interval partition proof differs from decoded authority");
        }
        requireSamePartition(firstPartition, secondPartition);
        if (first.runs().size() != second.runs().size()
                || !first.previewHash().equals(second.previewHash())
                || (first.planHash() == null) != (second.planHash() == null)) {
            throw new IllegalStateException("Interval partition, preview or plan availability differs");
        }
        String planName = "private/interval-frozen-edit-plan.bin";
        if (first.planHash() != null) {
            var firstPlan = FrozenReplayCodec.decodeEditPlan(firstArchive.artifact(planName)
                    .orElseThrow(() -> new IllegalStateException("Baseline interval plan missing")).bytes());
            var secondPlan = FrozenReplayCodec.decodeEditPlan(secondArchive.artifact(planName)
                    .orElseThrow(() -> new IllegalStateException("Candidate interval plan missing")).bytes());
            if (!firstPlan.canonicalHash().equals(first.planHash())
                    || !secondPlan.canonicalHash().equals(second.planHash())) {
                throw new IllegalStateException("Native interval plan identity differs from decoded plan");
            }
            BenchmarkDecisionComparatorMain.requireSamePlan(firstPlan, secondPlan);
        } else if (firstArchive.artifact(planName).isPresent()
                || secondArchive.artifact(planName).isPresent()) {
            throw new IllegalStateException("Unexpected interval plan artifact");
        }
        System.out.println("baselineNativeSharedInputHash=" + first.sharedInputHash());
        System.out.println("candidateNativeSharedInputHash=" + second.sharedInputHash());
        System.out.println("baselineNativePartitionHash=" + first.partitionProofHash());
        System.out.println("candidateNativePartitionHash=" + second.partitionProofHash());
        for (int index = 0; index < first.runs().size(); index++) {
            var x = first.runs().get(index);
            var y = second.runs().get(index);
            BenchmarkDecisionComparatorMain.requireSameRequest(x.request(), y.request());
            if (!x.scalarHash().equals(y.scalarHash()) || !x.finalHash().equals(y.finalHash())
                    || !x.routeIdentities().equals(y.routeIdentities())
                    || x.chosenRouteIndex() != y.chosenRouteIndex()
                    || x.disposition() != y.disposition() || x.reason() != y.reason()) {
                throw new IllegalStateException("Interval result differs at " + index);
            }
        }
        // Native hashes remain in each original envelope; only process-local
        // dataset identity and the hashes derived from it differ across runs.
    }

    private static void requireSamePartition(SelectedWayIntervalPartitioner.Partition a,
            SelectedWayIntervalPartitioner.Partition b) {
        if (!a.selectedWayKey().equals(b.selectedWayKey())
                || !a.selectedRange().equals(b.selectedRange())
                || !a.fixedIslands().equals(b.fixedIslands())
                || !a.slideIntervals().equals(b.slideIntervals())
                || !a.junctionDispositions().equals(b.junctionDispositions())
                || !a.provedPorts().equals(b.provedPorts())
                || !a.parityPrimitives().equals(b.parityPrimitives())
                || !a.parityIncomingReferrers().equals(b.parityIncomingReferrers())
                || a.sourceGeneration() != b.sourceGeneration()) {
            throw new IllegalStateException("Substantive interval partition differs");
        }
    }

    private static void requireSameAuthority(NetworkSnapshotCapture.Specification a,
            NetworkSnapshotCapture.Specification b) {
        if (!a.snapshotId().equals(b.snapshotId())
                || a.sourceGeneration() != b.sourceGeneration()
                || !a.selectedWayKey().equals(b.selectedWayKey())
                || !a.selectedRange().equals(b.selectedRange())
                || !a.metricFrame().equals(b.metricFrame())
                || !a.collisionEnvelope().equals(b.collisionEnvelope())
                || !a.editRegion().equals(b.editRegion())
                || !a.editableWayOccurrences().equals(b.editableWayOccurrences())
                || !a.editableExistingKeys().equals(b.editableExistingKeys())
                || !a.movableExistingNodeKeys().equals(b.movableExistingNodeKeys())
                || !a.removableExistingNodeKeys().equals(b.removableExistingNodeKeys())
                || !a.explicitlyProtectedNodeKeys().equals(b.explicitlyProtectedNodeKeys())
                || a.mayCreateNodes() != b.mayCreateNodes()
                || !a.permissions().equals(b.permissions())
                || !a.readOnlyPorts().equals(b.readOnlyPorts())) {
            throw new IllegalStateException("Interval authority differs");
        }
        Objects.requireNonNull(a.datasetIdentity());
        Objects.requireNonNull(b.datasetIdentity());
    }
}
