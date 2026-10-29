package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ExecutionWorkReceipt;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;

/** Actual ordered native B receipts, separate from the assembled Hybrid and unmeasured Legacy work. */
public final class LegacyBReplayWork {
    public static final String ARTIFACT = "legacy-b-native-work.json";
    private LegacyBReplayWork() { }

    public static Format15Bundle attach(Format15Bundle bundle, FrozenReplayInput frozen,
            LiveBPreviewService.LegacyBComputation computation) {
        if (frozen.legacyB() == null || frozen.request().engine() != TrackerMode.LEGACY_THEN_B
                || computation.input().request() != frozen.request()
                || computation.input().network() != frozen.network()
                || computation.input().evidence() != frozen.evidence()
                || !bundle.sourceIdentityHash().equals(frozen.canonicalHash())) {
            throw new IllegalArgumentException("Legacy B work has another frozen owner");
        }
        var receipts = computation.result().batch().sections().stream()
                .flatMap(section -> section.execution().stream()).map(execution -> {
                    var reference = execution.reference().orElseThrow(() ->
                            new IllegalArgumentException("Native B work lacks its actual reference"));
                    if (!reference.originalRequest().equals(frozen.request())
                            || !reference.originalEvidence().equals(frozen.evidence())) {
                        throw new IllegalArgumentException("Native B work belongs to another original capture");
                    }
                    return execution.run().result().workAccounting();
                }).toList();
        var artifacts = new LinkedHashMap<>(bundle.artifacts());
        artifacts.put(ARTIFACT, Format15Artifact.binary(ARTIFACT,
                Format15ExecutionWorkCodec.artifact(receipts).bytes()));
        return new Format15Bundle(bundle.buildIdentity(), bundle.sourceIdentityHash(), bundle.parameterHash(), artifacts);
    }

    static void validate(Format15Archive archive, Format15ReplayRunner.Result result) {
        if (result.engine() != TrackerMode.LEGACY_THEN_B) return;
        try {
            List<Optional<ExecutionWorkReceipt>> recorded = Format15ExecutionWorkCodec.read(
                    archive.artifact(ARTIFACT).orElseThrow());
            if (!recorded.equals(result.nativeBExecutionWork())) {
                throw new IllegalArgumentException("Native B section work differs");
            }
        } catch (RuntimeException malformed) {
            throw new ReplayMismatchException("legacy-native-work-mismatch-or-unavailable");
        }
    }
}
