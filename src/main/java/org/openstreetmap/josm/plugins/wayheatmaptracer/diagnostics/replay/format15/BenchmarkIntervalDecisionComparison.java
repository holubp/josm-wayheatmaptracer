package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.Objects;

import org.openstreetmap.josm.plugins.wayheatmaptracer.BenchmarkDecisionComparatorMain;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner;

/** Typed comparison of the production interval replay envelope. */
public final class BenchmarkIntervalDecisionComparison {
    private BenchmarkIntervalDecisionComparison() { }

    public static FrozenReplayInput sharedInput(Format15Archive archive) {
        return archive.artifact("frozen-input.bin").map(value -> FrozenReplayCodec.decode(value.bytes()))
                .orElseGet(() -> FrozenIntervalReplayCodec.decode(archive.artifact(
                        FrozenIntervalReplayCodec.ARTIFACT).orElseThrow(() ->
                            new IllegalStateException("Native production input missing")).bytes()).shared());
    }

    public static SelectedWayIntervalPartitioner.Partition nativePartition(Format15Archive archive) {
        if (archive.artifact("frozen-input.bin").isPresent()) return null;
        var payload = FrozenIntervalReplayCodec.decode(archive.artifact(
                FrozenIntervalReplayCodec.ARTIFACT).orElseThrow().bytes());
        var partition = SelectedWayIntervalPartitioner.partition(payload.shared().network(), payload.authority());
        if (!FrozenReplayCodec.partitionProofHash(partition).equals(payload.partitionProofHash())) {
            throw new IllegalStateException("Native interval partition proof differs from decoded authority");
        }
        return partition;
    }

    /** Binds all interval authority and partition semantics to the factual original-OSM recapture. */
    public static SelectedWayIntervalPartitioner.Partition requireFreshCapture(Format15Archive archive,
            NetworkSnapshotCapture.Specification authority, SelectedWayIntervalPartitioner.Partition factualPartition) {
        if (archive.artifact("frozen-input.bin").isPresent()) {
            if (factualPartition != null) {
                throw new IllegalStateException("Native publication kind differs from factual fixed-island capture");
            }
            return null;
        }
        if (factualPartition == null) {
            throw new IllegalStateException("Native interval publication has no factual fixed-island capture");
        }
        var payload = FrozenIntervalReplayCodec.decode(archive.artifact(FrozenIntervalReplayCodec.ARTIFACT).orElseThrow().bytes());
        requireSameAuthority(payload.authority(), authority);
        var nativePartition = nativePartition(archive);
        requireSamePartition(nativePartition, factualPartition);
        return nativePartition;
    }

    public static void requireSameNativeOutputs(Format15Archive first, FrozenReplayInput firstInput,
            Format15Archive second, FrozenReplayInput secondInput) {
        if (first.artifact("frozen-input.bin").isPresent() != second.artifact("frozen-input.bin").isPresent()) {
            throw new IllegalStateException("Within-version publication kind differs");
        }
        if (first.artifact("frozen-input.bin").isPresent()) {
            BenchmarkFinalOutputComparison.compare(first, firstInput, second, secondInput);
            return;
        }
        compare(first, second);
        var a = FrozenIntervalReplayCodec.decode(first.artifact(FrozenIntervalReplayCodec.ARTIFACT).orElseThrow().bytes());
        var b = FrozenIntervalReplayCodec.decode(second.artifact(FrozenIntervalReplayCodec.ARTIFACT).orElseThrow().bytes());
        var x = IntervalFinalOutputComponentsCodec.decode(first.artifact(IntervalFinalOutputComponentsCodec.ARTIFACT)
                .orElseThrow(() -> new IllegalStateException("Complete native interval output companion is required")).bytes(), first.buildIdentity(), a);
        var y = IntervalFinalOutputComponentsCodec.decode(second.artifact(IntervalFinalOutputComponentsCodec.ARTIFACT)
                .orElseThrow(() -> new IllegalStateException("Complete native interval output companion is required")).bytes(), second.buildIdentity(), b);
        for (int i = 0; i < x.snapshots().size(); i++) {
            if (!x.snapshots().get(i).digests().equals(y.snapshots().get(i).digests())) {
                throw new IllegalStateException("Within-version complete interval output differs");
            }
        }
    }

    /** Validates native complete output, selected route, composition and stored plan independently. */
    public static void verifyNativePublication(Format15Archive archive, FrozenReplayInput input,
            boolean candidate, org.openstreetmap.josm.data.osm.DataSet original, double halfWidth) {
        if (archive.artifact("frozen-input.bin").isPresent()) {
            var output = BenchmarkFinalOutputComparison.verifyNative(archive, input);
            if (candidate) {
                Format15NumericalPolicyReceipt.validate(archive, input, input.canonicalHash());
                var actual = Format15ReplayRunner.replay(archive,
                        org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel.FINAL_GEOMETRY,
                        archive.sourceIdentityHash(), archive.parameterHash());
                Format15NumericalPolicyReceipt.validateRecordedWork(archive, java.util.List.of(actual.inference()));
                var expectation = FinalReplayExpectation.read(archive).orElseThrow();
                var snapshot = FinalOutputComponentsCodec.decode(archive.artifact(FinalOutputComponentsCodec.ARTIFACT)
                        .orElseThrow().bytes(), expectation.componentsBinding());
                if (FinalOutputComponentsCodec.compare(snapshot, actual).status()
                        != FinalOutputComponentsCodec.ComparisonStatus.MATCH) {
                    throw new IllegalStateException("Current native output differs from complete production replay");
                }
            }
            var artifact = archive.artifact("frozen-edit-plan.bin");
            if (artifact.isEmpty()) {
                if (candidate || input.network().semanticWitness() != null) {
                    throw new IllegalStateException("Candidate production edit plan is missing");
                }
                return; // Reviewed RC6 absence, never an invented historical plan.
            }
            var plan = FrozenReplayCodec.decodeEditPlan(artifact.orElseThrow().bytes());
            requireNativeSinglePlanEnvelope(archive, plan);
            requireNativeSinglePlan(plan, input, output.selectedRoute());
            if (candidate) {
                requireWitness(original, plan.before());
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter
                        .requireFixedNativePlan(plan, input.request(), input.evidence(),
                                input.options(), output.selectedRoute(), halfWidth);
            }
            System.out.println((candidate ? "candidate" : "baseline") + "NativePlanHash=" + plan.canonicalHash());
            return;
        }
        // Interval publications are current-only under this contract; replay never rebinds RC6 output.
        var replayed = Format15ReplayRunner.replayIntervals(archive, archive.sourceIdentityHash(), archive.parameterHash());
        if (!"MATCH".equals(replayed.outputComponentStatus())) {
            throw new IllegalStateException("Complete native interval output companion is required");
        }
        var plan = FrozenReplayCodec.decodeEditPlan(archive.artifact("private/interval-frozen-edit-plan.bin")
                .orElseThrow(() -> new IllegalStateException("Native interval edit plan is required")).bytes());
        if (!plan.equals(replayed.assessment().plan().orElseThrow())) {
            throw new IllegalStateException("Stored native interval plan differs from complete production composition");
        }
        org.openstreetmap.josm.plugins.wayheatmaptracer.BenchmarkComparisonContract.requireFrozenPreserved(plan,
                org.openstreetmap.josm.plugins.wayheatmaptracer.BenchmarkComparisonContract.frozenKeys(replayed.partition()));
        requireWitness(original, plan.before());
        System.out.println((candidate ? "candidate" : "baseline") + "NativePlanHash=" + plan.canonicalHash());
        System.out.println("candidateTracedIntervals=" + replayed.partition().slideIntervals());
        var payload = FrozenIntervalReplayCodec.decode(archive.artifact(FrozenIntervalReplayCodec.ARTIFACT).orElseThrow().bytes());
        var components = IntervalFinalOutputComponentsCodec.decode(archive.artifact(
                IntervalFinalOutputComponentsCodec.ARTIFACT).orElseThrow().bytes(), archive.buildIdentity(), payload);
        for (int i = 0; i < components.snapshots().size(); i++) {
            System.out.println((candidate ? "candidate" : "baseline") + "NativeIntervalComponents" + i
                    + "=" + components.snapshots().get(i).digests());
        }
    }

    private static void requireWitness(org.openstreetmap.josm.data.osm.DataSet original,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot network) {
        try {
            org.openstreetmap.josm.plugins.wayheatmaptracer.BenchmarkHostMain.onEventThread(() -> {
                NetworkSnapshotCapture.requireSemanticWitnessCurrent(original, network);
                return null;
            });
        } catch (Exception invalid) {
            throw new IllegalStateException("Original OSM semantic witness could not be verified", invalid);
        }
    }

    private static void requireNativeSinglePlanEnvelope(Format15Archive archive,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan plan) {
        try {
            var recorded = Format15ArchiveReader.parseObject(archive.artifact("edit-plan-identity.json")
                    .orElseThrow(() -> new IllegalStateException("Native plan identity is required")).bytes(),
                    "edit-plan-identity.json");
            var expected = java.util.Map.of("planHash", plan.canonicalHash(),
                    "beforeHash", plan.before().canonicalHash(), "afterHash", plan.after().canonicalHash());
            byte[] geometry = Format15ProductionBundleFactory.geographicWays(plan.finalPreviewWays())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (!recorded.equals(expected) || !java.util.Arrays.equals(geometry,
                    archive.artifact("planned-geometry.json").orElseThrow(() ->
                            new IllegalStateException("Native planned geometry is required")).bytes())) {
                throw new IllegalStateException("Native plan identity or planned geometry differs");
            }
        } catch (Format15ArchiveException malformed) {
            throw new IllegalStateException("Native plan identity is malformed", malformed);
        }
    }

    private static void requireNativeSinglePlan(org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan plan,
            FrozenReplayInput input,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline.Route route) {
        var r = input.request();
        if (!plan.before().equals(input.network()) || !plan.metricFrame().equals(input.evidence().coordinateFrame())
                || !plan.selectedWayKey().equals(r.selectedWayKey()) || !plan.selectedRange().equals(r.selectedRange())
                || !plan.permissions().equals(r.permissions()) || !plan.settingsHash().equals(r.settingsHash())
                || !plan.parameterHash().equals(r.parameterHash()) || !plan.evidenceHash().equals(input.evidence().canonicalHash())
                || !plan.routeIdentity().equals(route.hypothesis().id())
                || !plan.affectedWayKeys().equals(java.util.Set.of(r.selectedWayKey()))
                || plan.validation().disposition()
                    == org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport.Disposition.HARD_BLOCKED) {
            throw new IllegalStateException("Native single plan binding differs");
        }
        var before = (org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay)
                plan.before().primitives().get(r.selectedWayKey());
        var after = (org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay)
                plan.after().primitives().get(r.selectedWayKey());
        var expectedKeys = new java.util.ArrayList<>(before.nodeKeys().subList(0, r.selectedRange().firstIndex()));
        for (var id : route.pointIds()) {
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey key;
            if (id instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence existing) {
                if (!existing.wayKey().equals(r.selectedWayKey())
                        || existing.originalOccurrenceIndex() < r.selectedRange().firstIndex()
                        || existing.originalOccurrenceIndex() > r.selectedRange().lastIndex()
                        || !before.nodeKeys().get(existing.originalOccurrenceIndex()).equals(existing.nodeKey())) {
                    throw new IllegalStateException("Native route occurrence differs from original selection");
                }
                key = existing.nodeKey();
            } else {
                var generated = (org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint) id;
                key = org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.planned(
                        org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.Type.NODE, generated.originalPointIndex());
            }
            expectedKeys.add(key);
            var node = (org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode) plan.after().primitives().get(key);
            if (node == null) throw new IllegalStateException("Native route assignment missing from plan");
            var metric = route.assignments().get(id);
            var originalNode = (org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode) plan.before().primitives().get(key);
            var expected = originalNode != null && metric.equals(plan.metricFrame().toMetric(originalNode.coordinate()))
                    ? originalNode.coordinate() : plan.metricFrame().toGeographic(metric);
            if (!expected.equals(node.coordinate())) {
                throw new IllegalStateException("Native route assignment differs from proposed plan");
            }
        }
        expectedKeys.addAll(before.nodeKeys().subList(r.selectedRange().lastIndex() + 1, before.nodeKeys().size()));
        if (!expectedKeys.equals(after.nodeKeys())) throw new IllegalStateException("Native route differs from stored plan occurrences");
    }

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
                || !a.parityReceiverRelations().equals(b.parityReceiverRelations())
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
                || a.fixedReceiverGeometry() != b.fixedReceiverGeometry()
                || !a.readOnlyPorts().equals(b.readOnlyPorts())) {
            throw new IllegalStateException("Interval authority differs");
        }
        Objects.requireNonNull(a.datasetIdentity());
        Objects.requireNonNull(b.datasetIdentity());
    }
}
