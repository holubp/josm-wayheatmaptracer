package org.openstreetmap.josm.plugins.wayheatmaptracer;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ArchiveReader;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayCodec;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.BenchmarkIntervalDecisionComparison;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;

/** Cross-process typed admission of complete frozen decision inputs. */
public final class BenchmarkDecisionComparatorMain {
    private BenchmarkDecisionComparatorMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Two production archives required");
        var firstArchive = Format15ArchiveReader.read(Path.of(args[0]));
        var secondArchive = Format15ArchiveReader.read(Path.of(args[1]));
        var firstArtifact = firstArchive.artifact("frozen-input.bin");
        var secondArtifact = secondArchive.artifact("frozen-input.bin");
        if (firstArtifact.isEmpty() != secondArtifact.isEmpty()) {
            throw new IllegalStateException("Single/interval production input kind differs");
        }
        if (firstArtifact.isEmpty()) {
            BenchmarkIntervalDecisionComparison.compare(firstArchive, secondArchive);
            System.out.println("typedDecisionInput=IDENTICAL_INTERVAL datasetIdentity=RUN_LOCAL");
            return;
        }
        FrozenReplayInput first = FrozenReplayCodec.decode(firstArtifact.orElseThrow().bytes());
        FrozenReplayInput second = FrozenReplayCodec.decode(secondArtifact.orElseThrow().bytes());
        requireSame(first, second);
        var firstPlan = firstArchive.artifact("frozen-edit-plan.bin");
        var secondPlan = secondArchive.artifact("frozen-edit-plan.bin");
        if (firstPlan.isEmpty() != secondPlan.isEmpty()) {
            throw new IllegalStateException("Production edit-plan availability differs");
        }
        if (firstPlan.isPresent()) {
            requireSamePlan(FrozenReplayCodec.decodeEditPlan(firstPlan.orElseThrow().bytes()),
                    FrozenReplayCodec.decodeEditPlan(secondPlan.orElseThrow().bytes()));
        }
        System.out.println("typedDecisionInput=IDENTICAL datasetIdentity=RUN_LOCAL");
        System.out.println("baselineNativeFrozenHash=" + first.canonicalHash());
        System.out.println("candidateNativeFrozenHash=" + second.canonicalHash());
    }

    public static void requireSame(FrozenReplayInput a, FrozenReplayInput b) {
        requireSameNetwork(a.network(), b.network());
        requireSameRequest(a.request(), b.request());
        if (!a.evidence().canonicalHash().equals(b.evidence().canonicalHash())
                || !a.options().equals(b.options())) {
            throw new IllegalStateException("Evidence frame or pipeline options differ");
        }
        Map<String, ScalarEvidenceField> fieldsA = a.evidence().fields();
        Map<String, ScalarEvidenceField> fieldsB = b.evidence().fields();
        if (!fieldsA.keySet().equals(fieldsB.keySet())) {
            throw new IllegalStateException("Scalar field inventory differs");
        }
        for (String name : fieldsA.keySet()) {
            ScalarEvidenceField p = fieldsA.get(name), q = fieldsB.get(name);
            if (p.width() != q.width() || p.height() != q.height()
                    || !p.lineage().equals(q.lineage())
                    || !Arrays.equals(p.copiedValidity(), q.copiedValidity())
                    || !Arrays.equals(p.copiedInterpolationValidity(), q.copiedInterpolationValidity())) {
                throw new IllegalStateException("Scalar support differs: " + name);
            }
            requireRawValuesEqual(p.copiedValues(), q.copiedValues(), name);
        }
    }

    public static void requireSameNetwork(NetworkSnapshot first, NetworkSnapshot second) {
        if (!first.snapshotId().equals(second.snapshotId()) || first.role() != second.role()
                || first.sourceGeneration() != second.sourceGeneration()
                || !first.closure().equals(second.closure())
                || !first.primitives().equals(second.primitives())
                || !first.incomingReferrerWatches().equals(second.incomingReferrerWatches())) {
            throw new IllegalStateException("Decision-bearing network closure differs");
        }
    }

    public static void requireSamePlan(AlignmentEditPlan x, AlignmentEditPlan y) {
        requireSameNetwork(x.before(), y.before());
        requireSameNetwork(x.after(), y.after());
        if (!x.selectedWayKey().equals(y.selectedWayKey())
                || !x.selectedRange().equals(y.selectedRange())
                || !x.metricFrame().equals(y.metricFrame())
                || !x.permissions().equals(y.permissions())
                || !x.settingsHash().equals(y.settingsHash())
                || !x.evidenceHash().equals(y.evidenceHash())
                || !x.parameterHash().equals(y.parameterHash())
                || !x.routeIdentity().equals(y.routeIdentity())
                || !x.finalPreviewWays().equals(y.finalPreviewWays())
                || !x.validation().equals(y.validation())) {
            throw new IllegalStateException("Production edit plan differs");
        }
    }

    static void requireRawValuesEqual(double[] first, double[] second, String name) {
        if (first.length != second.length) {
            throw new IllegalStateException("Scalar length differs: " + name);
        }
        for (int index = 0; index < first.length; index++) {
            if (Double.doubleToRawLongBits(first[index])
                    != Double.doubleToRawLongBits(second[index])) {
                throw new IllegalStateException("Scalar raw bits differ: " + name + " at " + index);
            }
        }
    }

    public static void requireSameRequest(TraceRequest x, TraceRequest y) {
        if (!x.selectedWayKey().equals(y.selectedWayKey())
                || !x.selectedRange().equals(y.selectedRange())
                || x.engine() != y.engine() || x.geometryMode() != y.geometryMode()
                || !x.permissions().equals(y.permissions()) || !x.budgets().equals(y.budgets())
                || !x.evidenceSnapshotId().equals(y.evidenceSnapshotId())
                || !x.evidenceContentHash().equals(y.evidenceContentHash())
                || !x.networkSnapshotId().equals(y.networkSnapshotId())
                || !x.settingsHash().equals(y.settingsHash())
                || !x.parameterHash().equals(y.parameterHash())
                || !x.samplerId().equals(y.samplerId())
                || Double.doubleToRawLongBits(x.configuredSampleStepMeters())
                    != Double.doubleToRawLongBits(y.configuredSampleStepMeters())
                || !x.profileChainage().equals(y.profileChainage())
                || !x.evidenceResolution().equals(y.evidenceResolution())
                || !x.corridorInput().equals(y.corridorInput())) {
            throw new IllegalStateException("Frozen request parameters differ");
        }
    }
}
