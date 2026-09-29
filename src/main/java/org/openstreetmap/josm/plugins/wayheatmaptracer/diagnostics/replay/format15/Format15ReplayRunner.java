package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CorridorEngineAdapter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.HybridTraceEngine;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceEngine;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.IntervalTraceBatch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.FixedIntervalEditPlanComposer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.TraceWorkUsage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.IntervalTraceRequestFactory;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.image.DirectionalImageTraceEngine;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticTraceEngine;

/** Strict offline runner that always invokes an actual production engine on frozen inputs. */
public final class Format15ReplayRunner {
    private static final Map<String, Boolean> INTERVAL_CAPABILITIES = Map.of(
            "INTERVAL_PRODUCTION_ARTIFACT", true,
            "STRICT_INTERVAL_PRODUCTION", true,
            "SCALAR_INFERENCE", false,
            "FINAL_GEOMETRY", false,
            "RASTER_INFERENCE", false,
            "FULL_EDIT_PLAN", false);
    /** Result materialized from a real production computation, never a recorded candidate. */
    public record Result(ReplayLevel level, TrackerMode capturedEngine, TrackerMode engine,
            TraceHypothesisSet inference, List<ModernTracePipeline.Route> routes,
            String inputHash) {
        public Result {
            if (level == null || capturedEngine == null || engine == null || inference == null
                    || routes == null || inputHash == null || inputHash.isBlank()) {
                throw new IllegalArgumentException("Production replay result is incomplete");
            }
            if (inference.engine() != engine) {
                throw new IllegalArgumentException("Production replay engine lineage is inconsistent");
            }
            routes = List.copyOf(routes);
        }

        /** Returns actual route geometry for manifest/invariant validation. */
        public List<TraceHypothesis> geometry() {
            return routes.stream().map(ModernTracePipeline.Route::hypothesis).toList();
        }
    }

    private Format15ReplayRunner() { }

    /** Strict detached interval replay of one captured partitioned attempt. */
    public static IntervalResult replayIntervals(Format15Archive archive,
            String expectedSourceHash, String expectedParameterHash) {
        if (archive == null || archive.formatVersion() != 15
                || !archive.sourceIdentityHash().equals(expectedSourceHash)
                || !archive.parameterHash().equals(expectedParameterHash)
                || archive.artifact(FrozenIntervalReplayCodec.ARTIFACT).isEmpty()) {
            throw new ReplayMismatchException("strict-interval-replay-input-unavailable");
        }
        FrozenIntervalReplayCodec.Payload payload;
        Map<String, Object> index;
        java.util.Optional<IntervalFinalOutputComponentsCodec.Decoded> outputComponents;
        try {
            payload = FrozenIntervalReplayCodec.decode(archive.artifact(
                    FrozenIntervalReplayCodec.ARTIFACT).orElseThrow().bytes());
            outputComponents = archive.artifact(IntervalFinalOutputComponentsCodec.ARTIFACT)
                    .map(Format15Artifact::bytes)
                    .map(bytes -> IntervalFinalOutputComponentsCodec.decode(bytes,
                            archive.buildIdentity(), payload));
            index = Format15ArchiveReader.parseObject(archive.artifact("interval-production.json")
                    .orElseThrow().bytes(), "interval-production.json");
        } catch (Exception malformed) {
            throw new ReplayMismatchException("strict-interval-replay-input-malformed");
        }
        FrozenReplayInput shared = payload.shared();
        if (!shared.request().parameterHash().equals(archive.parameterHash())
                || !shared.network().snapshotId().equals(payload.authority().snapshotId())
                || !shared.network().datasetIdentity().equals(payload.authority().datasetIdentity())
                || shared.network().sourceGeneration() != payload.authority().sourceGeneration()
                || !shared.request().selectedWayKey().equals(payload.authority().selectedWayKey())
                || !shared.request().selectedRange().equals(payload.authority().selectedRange())) {
            throw new ReplayMismatchException("strict-interval-authority-mismatch");
        }
        SelectedWayIntervalPartitioner.Partition partition;
        try {
            partition = SelectedWayIntervalPartitioner.partition(shared.network(), payload.authority());
        } catch (RuntimeException invalidAuthority) {
            throw new ReplayMismatchException("strict-interval-partition-recomputation-failed");
        }
        if (!SelectedWayIntervalPartitioner.verifyFrozenParity(partition, shared.network())
                || !FrozenReplayCodec.partitionProofHash(partition).equals(payload.partitionProofHash())
                || partition.slideIntervals().size() != payload.runs().size()) {
            throw new ReplayMismatchException("strict-interval-partition-mismatch");
        }
        if (!Long.valueOf(1L).equals(index.get("schema"))
                || !Boolean.TRUE.equals(index.get("privateData"))
                || !INTERVAL_CAPABILITIES.equals(object(index, "capabilities"))
                || !"INTERVAL_PRODUCTION".equals(index.get("artifactKind"))
                || !shared.network().canonicalHash().equals(index.get("networkHash"))
                || !shared.evidence().canonicalHash().equals(index.get("evidenceHash"))
                || !shared.request().parameterHash().equals(index.get("parameterHash"))
                || !payload.partitionProofHash().equals(index.get("partitionProofHash"))) {
            throw new ReplayMismatchException("strict-interval-index-mismatch");
        }
        var sourceReceipt = sourceReceipt(index.get("sourceReceipt"));
        if (!Format15ProductionBundleFactory.intervalSourceHash(shared.network().canonicalHash(),
                shared.evidence().canonicalHash(), sourceReceipt)
                .equals(archive.sourceIdentityHash())) {
            throw new ReplayMismatchException("strict-interval-source-receipt-mismatch");
        }
        validateStatus(index, payload.planHash());
        List<?> indexRuns = list(index, "intervals");
        if (indexRuns.size() != payload.runs().size()) {
            throw new ReplayMismatchException("strict-interval-count-mismatch");
        }
        ModernTracePipeline pipeline = new ModernTracePipeline(
                new CorridorEngineAdapter(shared.options().fieldName()));
        List<IntervalTraceBatch.IntervalRun> actualRuns = new ArrayList<>();
        List<FinalOutputComponentsCodec.Comparison> componentComparisons = new ArrayList<>();
        Map<Integer, Integer> choices = new LinkedHashMap<>();
        long remainingPairs = shared.request().budgets().maximumPairVisits();
        long remainingTransitions = shared.request().budgets().maximumTransitions();
        int retainedRoutes = 0;
        for (int position = 0; position < payload.runs().size(); position++) {
            var expected = payload.runs().get(position);
            var interval = partition.slideIntervals().get(position);
            var request = expected.request();
            if (!request.selectedWayKey().equals(shared.request().selectedWayKey())
                    || !request.selectedRange().equals(interval.traceRange())
                    || !request.evidenceSnapshotId().equals(shared.evidence().snapshotId())
                    || !request.evidenceContentHash().equals(shared.evidence().canonicalHash())
                    || !request.networkSnapshotId().equals(shared.network().snapshotId())
                    || !request.networkContentHash().equals(shared.network().canonicalHash())
                    || !request.settingsHash().equals(shared.request().settingsHash())
                    || !request.parameterHash().equals(shared.request().parameterHash())
                    || request.engine() != shared.request().engine()
                    || request.geometryMode() != shared.request().geometryMode()
                    || !request.permissions().equals(shared.request().permissions())
                    || !request.samplerId().equals(shared.request().samplerId())
                    || Double.compare(request.configuredSampleStepMeters(),
                            shared.request().configuredSampleStepMeters()) != 0
                    || !request.evidenceResolution().equals(shared.request().evidenceResolution())
                    || request.budgets().maximumStatesPerProfile()
                            != shared.request().budgets().maximumStatesPerProfile()
                    || request.budgets().maximumRawAlternatives()
                            != shared.request().budgets().maximumRawAlternatives()
                    || request.budgets().maximumDistinctAlternatives()
                            != shared.request().budgets().maximumDistinctAlternatives()
                    || request.budgets().maximumPairVisits() != remainingPairs
                    || request.budgets().maximumTransitions() != remainingTransitions) {
                throw new ReplayMismatchException("strict-interval-request-mismatch");
            }
            try {
                TraceRequest derived = new IntervalTraceRequestFactory().createDetached(
                        shared.request(), shared.evidence(), shared.network(), interval,
                        request.budgets());
                if (!derived.equals(request)) {
                    throw new ReplayMismatchException("strict-interval-request-derivation-mismatch");
                }
            } catch (IllegalArgumentException invalidRequest) {
                throw new ReplayMismatchException("strict-interval-request-derivation-mismatch");
            }
            Set<Integer> fixedOccurrences = new LinkedHashSet<>();
            if (interval.startBoundary().kind()
                    == SelectedWayIntervalPartitioner.BoundaryKind.FIXED_ISLAND) {
                fixedOccurrences.add(interval.startBoundary().occurrenceIndex());
            }
            if (interval.endBoundary().kind()
                    == SelectedWayIntervalPartitioner.BoundaryKind.FIXED_ISLAND) {
                fixedOccurrences.add(interval.endBoundary().occurrenceIndex());
            }
            ModernTracePipeline.PipelineRun production;
            try {
                production = pipeline.runWithUsage(request, shared.evidence(), shared.network(),
                        shared.options(), CancellationProbe.NONE, fixedOccurrences);
            } catch (RuntimeException failure) {
                throw new ReplayMismatchException("strict-interval-production-execution-failed");
            }
            TraceWorkUsage usage = production.usage();
            if (usage.pairVisits() > remainingPairs || usage.transitions() > remainingTransitions
                    || retainedRoutes + production.result().routes().size()
                            > IntervalTraceBatch.MAX_RETAINED_ROUTES) {
                throw new ReplayMismatchException("strict-interval-work-budget-mismatch");
            }
            remainingPairs -= usage.pairVisits();
            remainingTransitions -= usage.transitions();
            retainedRoutes += production.result().routes().size();
            var replayed = production.result();
            var fingerprint = new Result(ReplayLevel.FINAL_GEOMETRY, request.engine(),
                    request.engine(), replayed.inference(), replayed.routes(),
                    Format15Safety.sha256(FrozenReplayCodec.encodeRequestOnly(request)));
            List<String> routeIds = replayed.routes().stream()
                    .map(route -> route.hypothesis().id()).toList();
            if (!expected.scalarHash().equals(ScalarReplayFingerprint.sha256(replayed.inference()))
                    || !expected.finalHash().equals(FinalReplayFingerprint.sha256(fingerprint))
                    || !expected.routeIdentities().equals(routeIds)) {
                throw new ReplayMismatchException("strict-interval-production-output-mismatch");
            }
            if (outputComponents.isPresent()) {
                FinalOutputComponentsCodec.Comparison comparison =
                        FinalOutputComponentsCodec.compare(
                                outputComponents.orElseThrow().snapshots().get(position), fingerprint);
                if (comparison.status() != FinalOutputComponentsCodec.ComparisonStatus.MATCH) {
                    throw new ReplayMismatchException("strict-interval-components-mismatch");
                }
                componentComparisons.add(comparison);
            }
            verifyIndexRun(indexRuns.get(position), position, interval, expected,
                    replayed.inference().alternativesTruncated());
            choices.put(position, expected.chosenRouteIndex());
            actualRuns.add(new IntervalTraceBatch.IntervalRun(interval, request,
                    replayed, usage));
        }
        IntervalTraceBatch batch = new IntervalTraceBatch(shared.request(), shared.evidence(),
                shared.network(), partition, actualRuns, shared.options(), payload.authority());
        FixedIntervalEditPlanComposer.Assessment assessment;
        try {
            assessment = new FixedIntervalEditPlanComposer().compose(batch, choices);
        } catch (RuntimeException invalidComposition) {
            throw new ReplayMismatchException("strict-interval-composition-failed");
        }
        for (int position = 0; position < assessment.intervals().size(); position++) {
            var captured = payload.runs().get(position);
            var actual = assessment.intervals().get(position);
            if (actual.disposition() != captured.disposition()
                    || Format15ProductionBundleFactory.intervalReason(actual.reason()) != captured.reason()
                    || !actual.routeIdentity().equals(captured.routeIdentities().isEmpty()
                            ? "unavailable" : captured.routeIdentities().get(captured.chosenRouteIndex()))) {
                throw new ReplayMismatchException("strict-interval-composition-disposition-mismatch");
            }
        }
        String actualPlan = assessment.plan().map(plan -> plan.canonicalHash()).orElse(null);
        Map<org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey,
                List<org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint>> previewWays =
                assessment.plan().map(plan -> plan.finalPreviewWays()).orElseGet(() ->
                        Map.of(shared.request().selectedWayKey(), assessment.selectedWayPreview()));
        String actualPreview = Format15Safety.sha256(
                Format15ProductionBundleFactory.geographicWays(previewWays));
        if (!archive.artifact("private/interval-composed-preview.json")
                    .map(Format15Artifact::sha256).filter(actualPreview::equals).isPresent()
                || !archive.artifact("private/interval-point-provenance.json")
                    .map(Format15Artifact::sha256)
                    .filter(Format15Safety.sha256(
                            Format15ProductionBundleFactory.intervalPointProvenanceJson(batch,
                                    assessment, assessment.plan().orElse(null), previewWays))::equals)
                    .isPresent()) {
            throw new ReplayMismatchException("strict-interval-preview-artifact-mismatch");
        }
        for (int position = 0; position < actualRuns.size(); position++) {
            String name = "private/interval-" + position + "-routes.json";
            String expectedArtifact = Format15Safety.sha256(
                    Format15ProductionBundleFactory.intervalRoutesJson(batch, choices, position));
            if (!archive.artifact(name).map(Format15Artifact::sha256)
                    .filter(expectedArtifact::equals).isPresent()) {
                throw new ReplayMismatchException("strict-interval-route-artifact-mismatch");
            }
        }
        if (!java.util.Objects.equals(payload.planHash(), actualPlan)
                || !payload.previewHash().equals(actualPreview)
                || !java.util.Objects.equals(index.get("planIdentity"), actualPlan)
                || !payload.previewHash().equals(index.get("previewSha256"))
                || !Boolean.valueOf(assessment.applyAvailable()).equals(index.get("applyAvailable"))
                || index.get("reviewedPlanIdentity") != null
                        && !index.get("reviewedPlanIdentity").equals(actualPlan)
                || index.get("appliedPlanIdentity") != null
                        && !index.get("appliedPlanIdentity").equals(actualPlan)) {
            throw new ReplayMismatchException("strict-interval-composed-plan-mismatch");
        }
        return new IntervalResult(partition, batch, assessment,
                outputComponents.isPresent() ? "MATCH" : "UNAVAILABLE",
                componentComparisons);
    }

    /** Recomputed production partition, batch, and one composed edit assessment. */
    public record IntervalResult(
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner.Partition partition,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.IntervalTraceBatch batch,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.FixedIntervalEditPlanComposer.Assessment assessment,
            String outputComponentStatus,
            List<FinalOutputComponentsCodec.Comparison> outputComponentComparisons) {
        public IntervalResult {
            outputComponentComparisons = List.copyOf(outputComponentComparisons);
        }
    }

    private static void verifyIndexRun(Object value, int position,
            SelectedWayIntervalPartitioner.SlideInterval interval,
            FrozenIntervalReplayCodec.ExpectedRun expected, boolean alternativesTruncated) {
        if (!(value instanceof Map<?, ?> row)
                || !(row.get("intervalIndex") instanceof Number number)
                || number.intValue() != position
                || !(row.get("chosenRouteIndex") instanceof Number choice)
                || choice.intValue() != (expected.routeIdentities().isEmpty()
                        ? -1 : expected.chosenRouteIndex())
                || !java.util.Objects.equals(row.get("chosenRouteIdentity"),
                        expected.routeIdentities().isEmpty() ? "unavailable"
                                : expected.routeIdentities().get(expected.chosenRouteIndex()))
                || !expected.disposition().name().equals(row.get("disposition"))
                || !expected.reason().name().equals(row.get("reason"))
                || !Boolean.valueOf(alternativesTruncated).equals(row.get("alternativesTruncated"))
                || !rangeMatches(row.get("occurrenceRange"), interval.range())
                || !rangeMatches(row.get("traceRange"), interval.traceRange())) {
            throw new ReplayMismatchException("strict-interval-index-choice-mismatch");
        }
        Object alternatives = row.get("alternatives");
        if (!(alternatives instanceof List<?> list) || list.size() != expected.routeIdentities().size()) {
            throw new ReplayMismatchException("strict-interval-index-alternatives-mismatch");
        }
        for (int index = 0; index < list.size(); index++) {
            if (!(list.get(index) instanceof Map<?, ?> alternative)
                    || !(alternative.get("index") instanceof Number alternativeIndex)
                    || alternativeIndex.intValue() != index
                    || !expected.routeIdentities().get(index).equals(alternative.get("identity"))) {
                throw new ReplayMismatchException("strict-interval-index-alternatives-mismatch");
            }
        }
    }

    private static Format15ProductionBundleFactory.IntervalSourceReceipt sourceReceipt(Object value) {
        if (!(value instanceof Map<?, ?> map) || !(map.get("kind") instanceof String kind)) {
            throw new ReplayMismatchException("strict-interval-source-receipt-mismatch");
        }
        try {
            if ("MANAGED_TILES".equals(kind)
                    && map.keySet().equals(Set.of("kind", "generation", "zoom", "sourceIdentityHash"))
                    && map.get("sourceIdentityHash") instanceof String hash) {
                return new Format15ProductionBundleFactory.ManagedTileSourceReceipt(
                        exactLong(map.get("generation")), exactInt(map.get("zoom")), hash);
            }
            if ("VISIBLE_RENDERED_LAYER".equals(kind)
                    && map.keySet().equals(Set.of("kind", "revision", "zoom", "sourceIdentityHash"))
                    && (map.get("sourceIdentityHash") == null
                            || map.get("sourceIdentityHash") instanceof String)) {
                return new Format15ProductionBundleFactory.VisibleLayerSourceReceipt(
                        map.get("revision") == null ? null : exactLong(map.get("revision")),
                        map.get("zoom") == null ? null : exactInt(map.get("zoom")),
                        (String) map.get("sourceIdentityHash"));
            }
        } catch (IllegalArgumentException malformed) {
            throw new ReplayMismatchException("strict-interval-source-receipt-mismatch");
        }
        throw new ReplayMismatchException("strict-interval-source-receipt-mismatch");
    }

    private static long exactLong(Object value) {
        if (!(value instanceof Long number) || number < 0) {
            throw new IllegalArgumentException("Non-canonical receipt integer");
        }
        return number;
    }

    private static int exactInt(Object value) {
        long number = exactLong(value);
        if (number > Integer.MAX_VALUE) throw new IllegalArgumentException("Receipt integer overflow");
        return (int) number;
    }

    private static void validateStatus(Map<String, Object> index, String planIdentity) {
        Format15ProductionBundleFactory.IntervalArtifactStatus status;
        String reviewed = nullableHash(index.get("reviewedPlanIdentity"));
        String applied = nullableHash(index.get("appliedPlanIdentity"));
        try {
            status = Format15ProductionBundleFactory.IntervalArtifactStatus.valueOf(
                    (String) index.get("status"));
        } catch (RuntimeException malformed) {
            throw new ReplayMismatchException("strict-interval-status-mismatch");
        }
        boolean valid = switch (status) {
            case PRODUCED, PREVIEW, CANCELLED, RESOURCE_LIMIT, FAILED ->
                reviewed == null && applied == null;
            case REVIEWED, CONFIRMED -> planIdentity != null
                    && planIdentity.equals(reviewed) && applied == null;
            case APPLIED -> Boolean.TRUE.equals(index.get("applyAvailable"))
                    && planIdentity != null && planIdentity.equals(applied)
                    && (reviewed == null || planIdentity.equals(reviewed));
            case APPLIED_AFTER_REVIEW -> Boolean.TRUE.equals(index.get("applyAvailable"))
                    && planIdentity != null
                    && planIdentity.equals(reviewed) && planIdentity.equals(applied);
        };
        if (!valid) throw new ReplayMismatchException("strict-interval-status-mismatch");
    }

    private static String nullableHash(Object value) {
        if (value == null) return null;
        if (!(value instanceof String hash)) {
            throw new ReplayMismatchException("strict-interval-status-mismatch");
        }
        try {
            return Format15Safety.requiredHash(hash, "interval status identity");
        } catch (IllegalArgumentException malformed) {
            throw new ReplayMismatchException("strict-interval-status-mismatch");
        }
    }

    private static boolean rangeMatches(Object value,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange range) {
        return value instanceof Map<?, ?> map
                && map.get("first") instanceof Number first
                && map.get("last") instanceof Number last
                && first.intValue() == range.firstIndex() && last.intValue() == range.lastIndex();
    }

    private static Map<String, Object> object(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof Map<?, ?> map)) {
            throw new ReplayMismatchException("strict-interval-index-mismatch");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((name, item) -> {
            if (!(name instanceof String text)) throw new ReplayMismatchException("strict-interval-index-mismatch");
            result.put(text, item);
        });
        return result;
    }

    private static List<?> list(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof List<?> items)) {
            throw new ReplayMismatchException("strict-interval-index-mismatch");
        }
        return items;
    }

    /** Runs the captured engine at a supported scalar or final level. */
    public static Result replay(Format15Archive archive, ReplayLevel level,
            String expectedInputHash, String expectedParameterHash) {
        FrozenReplayInput input = load(archive, level, expectedInputHash, expectedParameterHash);
        return replay(input, level, input.request().engine());
    }

    /** Loads a verified archive and runs one requested engine over the exact frozen values. */
    public static Result replay(Format15Archive archive, ReplayLevel level, String expectedInputHash,
            String expectedParameterHash, TrackerMode engine) {
        FrozenReplayInput input = load(archive, level, expectedInputHash, expectedParameterHash);
        return replay(input, level, engine);
    }

    /** Executes one requested modern engine over the same complete frozen attempt values. */
    public static Result replay(FrozenReplayInput input, ReplayLevel level, TrackerMode engine) {
        if (input == null || level == null || engine == null) {
            throw new IllegalArgumentException("Frozen input, level, and engine are required");
        }
        requireSupportedLevel(level);
        if (engine == TrackerMode.LEGACY_V02) {
            throw new ReplayMismatchException("Legacy engine has no modern frozen replay route");
        }
        if ((engine == TrackerMode.CORRIDOR_AWARE || engine == TrackerMode.HYBRID)
                && input.request().corridorInput().isEmpty()) {
            throw new ReplayMismatchException(
                "Requested engine requires an exact frozen CorridorTraceInput");
        }
        TrackerMode capturedEngine = input.request().engine();
        String inputHash = input.canonicalHash();
        FrozenReplayInput requested = withEngine(input, engine);
        return level == ReplayLevel.SCALAR_INFERENCE
            ? scalar(requested, capturedEngine, inputHash)
            : finalGeometry(requested, capturedEngine, inputHash);
    }

    private static FrozenReplayInput load(Format15Archive archive, ReplayLevel level,
            String expectedInputHash, String expectedParameterHash) {
        if (archive == null || level == null || expectedInputHash == null
                || expectedParameterHash == null) {
            throw new IllegalArgumentException("Replay archive, level, and identities are required");
        }
        requireSupportedLevel(level);
        if (!archive.capability().supports(level)) {
            throw new ReplayMismatchException(
                "Replay archive lacks required executable capability: " + level);
        }
        if (!archive.sourceIdentityHash().equals(expectedInputHash)
                || !archive.parameterHash().equals(expectedParameterHash)) {
            throw new ReplayMismatchException("Replay source or parameter identity does not match");
        }
        FrozenReplayInput input = archive.artifact("frozen-input.bin")
            .map(Format15Artifact::bytes)
            .map(FrozenReplayCodec::decode)
            .orElseThrow(() -> new ReplayMismatchException(
                "Replay archive lacks frozen production input"));
        if (!input.canonicalHash().equals(archive.sourceIdentityHash())
                || !input.request().parameterHash().equals(archive.parameterHash())) {
            throw new ReplayMismatchException(
                "Frozen replay input identity disagrees with archive manifest");
        }
        return input;
    }

    private static void requireSupportedLevel(ReplayLevel level) {
        if (level != ReplayLevel.SCALAR_INFERENCE && level != ReplayLevel.FINAL_GEOMETRY) {
            throw new ReplayMismatchException(
                "Replay level is not implemented by the production runner: " + level);
        }
    }

    private static FrozenReplayInput withEngine(FrozenReplayInput input, TrackerMode engine) {
        TraceRequest source = input.request();
        TraceRequest request = new TraceRequest(source.selectedWayKey(), source.selectedRange(),
            engine, source.geometryMode(), source.permissions(), source.budgets(),
            source.evidenceSnapshotId(), source.evidenceContentHash(), source.networkSnapshotId(),
            source.networkContentHash(), source.settingsHash(), source.parameterHash(),
            source.samplerId(), source.configuredSampleStepMeters(), source.profileChainage(),
            source.evidenceResolution(), source.corridorInput());
        return new FrozenReplayInput(request, input.evidence(), input.network(), input.options());
    }

    private static Result scalar(FrozenReplayInput input, TrackerMode capturedEngine,
            String inputHash) {
        try {
            TraceEngine engine = engine(input.request().engine(), input.options().fieldName());
            TraceHypothesisSet inference = engine.trace(input.request(), input.evidence(),
                input.network(), CancellationProbe.NONE);
            return new Result(ReplayLevel.SCALAR_INFERENCE, capturedEngine,
                input.request().engine(), inference, List.of(), inputHash);
        } catch (RuntimeException failure) {
            throw new ReplayMismatchException("production-engine-execution-failed");
        }
    }

    private static Result finalGeometry(FrozenReplayInput input, TrackerMode capturedEngine,
            String inputHash) {
        try {
            ModernTracePipeline.Result actual = new ModernTracePipeline(
                new CorridorEngineAdapter(input.options().fieldName())).run(input.request(),
                input.evidence(), input.network(), input.options(), CancellationProbe.NONE);
            return new Result(ReplayLevel.FINAL_GEOMETRY, capturedEngine,
                input.request().engine(), actual.inference(), actual.routes(), inputHash);
        } catch (RuntimeException failure) {
            throw new ReplayMismatchException("production-engine-execution-failed");
        }
    }

    private static TraceEngine engine(TrackerMode mode, String fieldName) {
        return switch (mode) {
            case CORRIDOR_AWARE -> new CorridorEngineAdapter(fieldName);
            case PROBABILISTIC -> new ProbabilisticTraceEngine(fieldName);
            case HYBRID -> new HybridTraceEngine(new CorridorEngineAdapter(fieldName),
                new ProbabilisticTraceEngine(fieldName, EvidenceModelParameters.defaults(),
                        ProbabilisticTraceEngine.ReliabilityPolicy.BASELINE));
            case DIRECTIONAL_IMAGE -> new DirectionalImageTraceEngine(fieldName);
            case LEGACY_V02 -> throw new ReplayMismatchException(
                "Legacy engine has no modern frozen replay route");
        };
    }
}
