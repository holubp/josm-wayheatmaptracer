package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;

class V022SourceFrontierPolicyReplayTest {
    private static FrozenReplayInput input(BPolicy policy, boolean compact, boolean managed) {
        var f = V022FrozenFrameTest.input(V022FrozenFrameTest.frame()); var r = f.request();
        var exact = new TraceRequest(r.selectedWayKey(), r.selectedRange(), r.engine(), r.geometryMode(),
                compact ? RecoveryPermissions.disabled(1) : r.permissions(),
                compact ? new TraceBudgets(16, 100_000, 1, 4, 4) : r.budgets(),
                r.evidenceSnapshotId(), r.evidenceContentHash(), r.networkSnapshotId(), r.networkContentHash(),
                r.settingsHash(), managed ? BPolicy.exactParameterHash() : BPolicy.visibleExactParameterHash(),
                r.samplerId(), compact ? .5 : r.configuredSampleStepMeters(),
                compact ? new ProfileChainage(List.of(0., .5, 1.), .5) : r.profileChainage(),
                r.evidenceResolution(), r.corridorInput());
        return new FrozenReplayInput(BPolicy.adaptiveRequest(exact, policy), f.evidence(), f.network(), f.options());
    }

    @Test void explicitV5FullAndCompactPoliciesCarryNativeWorkAndReplay(@TempDir Path directory) throws Exception {
        for (boolean managed : new boolean[] {false, true}) for (boolean compact : new boolean[] {false, true}) {
            var old = input(BPolicy.INVENTORY_PREP_V4, compact, managed);
            var requested = input(BPolicy.INVENTORY_FRONTIER_V5, compact, managed);
            assertNotEquals(old.request().parameterHash(), requested.request().parameterHash());
            var frozen = FrozenReplayCodec.decode(FrozenReplayCodec.encode(requested));
            assertEquals(requested.request(), frozen.request());
            assertEquals(requested.canonicalHash(), frozen.canonicalHash());
            assertEquals(BPolicy.INVENTORY_FRONTIER_V5, BPolicy.recorded(frozen.request()));
            var bundle = Format15ProductionBundleFactory.createWithExpectedScalarOutput(
                    "source-frontier-test", frozen, TrackerMode.PROBABILISTIC);
            assertTrue(bundle.artifactNames().contains("b-dense-admission.json"));
            var path = directory.resolve(managed + "-" + compact + ".zip");
            Format15BundleWriter.write(bundle, path);
            var archive = Format15ArchiveReader.read(path);
            var result = Format15ReplayRunner.replay(archive, ReplayLevel.SCALAR_INFERENCE,
                    frozen.canonicalHash(), frozen.request().parameterHash());
            assertTrue(result.inference().workAccounting().orElseThrow().hasPolicy(compact
                    ? BPolicy.FRONTIER_COMPACT_VERSION : BPolicy.FRONTIER_FULL_VERSION));
            var nativeResult = result.inference();
            var stage = nativeResult.workAccounting().orElseThrow().bStages().get(0);
            var oldStage = new InferenceWorkAccounting(compact ? BPolicy.INVENTORY_COMPACT_VERSION : BPolicy.INVENTORY_FULL_VERSION,
                    stage.forwardBackwardScores(), stage.kBestScores(), stage.logicalKBestExtensions(), stage.refusedScoreAttempts());
            var downgraded = new TraceHypothesisSet(nativeResult.engine(), nativeResult.hypotheses(), nativeResult.status(),
                    nativeResult.alternativesTruncated(), nativeResult.evaluatedStates(), nativeResult.evaluatedTransitions(),
                    nativeResult.explanation(), Optional.of(new ExecutionWorkReceipt(List.of(oldStage), 0)));
            assertThrows(ReplayMismatchException.class, () -> Format15NumericalPolicyReceipt.validateExecuted(
                    downgraded, frozen.request().parameterHash()), "V4 work cannot attest V5 source bytes");
            if (compact) {
                assertEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT, result.inference().status());
                assertEquals(1, result.inference().evaluatedTransitions());
                assertTrue(result.inference().hypotheses().isEmpty());
            } else {
                var historical = Format15ReplayRunner.replay(old, ReplayLevel.SCALAR_INFERENCE, TrackerMode.PROBABILISTIC);
                assertEquals(historical.inference().hypotheses(), result.inference().hypotheses());
            }
            var changed = new LinkedHashMap<>(bundle.artifacts());
            changed.remove("b-dense-admission.json");
            var missing = new Format15Bundle(bundle.buildIdentity(), bundle.sourceIdentityHash(), bundle.parameterHash(), changed);
            Format15BundleWriter.write(missing, path);
            var missingArchive = Format15ArchiveReader.read(path);
            assertThrows(ReplayMismatchException.class, () -> Format15ReplayRunner.replay(missingArchive,
                    ReplayLevel.SCALAR_INFERENCE, frozen.canonicalHash(), frozen.request().parameterHash()));
        }
    }

    @Test void optInDoesNotRelabelHistoricalPoliciesOrPromoteNewCaptures() {
        assertEquals("68d3b706f28674e60cb45646be640e4d83d1c14200c93987b4c0a42dcceb5fda",
                BPolicy.adaptiveParameterHash(BPolicy.INVENTORY_PREP_V4));
        for (boolean managed : new boolean[] {false, true}) {
            var historical = input(BPolicy.INVENTORY_PREP_V4, false, managed);
            assertEquals(historical.request().parameterHash(), BPolicy.newCaptureParameterHash(managed
                    ? BPolicy.exactParameterHash() : BPolicy.visibleExactParameterHash()));
            assertEquals(BPolicy.INVENTORY_PREP_V4, BPolicy.recorded(historical.request()));
            assertEquals(input(BPolicy.INVENTORY_FRONTIER_V5, false, managed).request().parameterHash(),
                    BPolicy.newCaptureParameterHash(managed ? BPolicy.exactParameterHash() : BPolicy.visibleExactParameterHash(),
                            BPolicy.INVENTORY_FRONTIER_V5));
        }
        assertThrows(IllegalArgumentException.class, () -> BPolicy.newCaptureParameterHash("unknown", BPolicy.INVENTORY_FRONTIER_V5));
        assertThrows(IllegalArgumentException.class, () -> BPolicy.newCaptureParameterHash(BPolicy.exactParameterHash(), BPolicy.ADAPTIVE_MULTIMODAL_V2));
    }
}
