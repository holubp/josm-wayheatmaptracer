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
            String inputHash, List<String> disconnectionPlanReviews,
            List<java.util.Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.model.ExecutionWorkReceipt>> nativeBExecutionWork,
            Format15Artifact legacyBProvenance) {
        public Result(ReplayLevel level, TrackerMode capturedEngine, TrackerMode engine,
                TraceHypothesisSet inference, List<ModernTracePipeline.Route> routes,
                String inputHash, List<String> disconnectionPlanReviews,
                List<java.util.Optional<org.openstreetmap.josm.plugins.wayheatmaptracer.model.ExecutionWorkReceipt>> nativeBExecutionWork) {
            this(level, capturedEngine, engine, inference, routes, inputHash, disconnectionPlanReviews,
                    nativeBExecutionWork, null);
        }
        public Result(ReplayLevel level, TrackerMode capturedEngine, TrackerMode engine,
                TraceHypothesisSet inference, List<ModernTracePipeline.Route> routes,
                String inputHash, List<String> disconnectionPlanReviews) {
            this(level, capturedEngine, engine, inference, routes, inputHash, disconnectionPlanReviews, List.of());
        }
        public Result(ReplayLevel level, TrackerMode capturedEngine, TrackerMode engine,
                TraceHypothesisSet inference, List<ModernTracePipeline.Route> routes, String inputHash) {
            this(level, capturedEngine, engine, inference, routes, inputHash, List.of());
        }
        public Result {
            if (level == null || capturedEngine == null || engine == null || inference == null
                    || routes == null || inputHash == null || inputHash.isBlank()) {
                throw new IllegalArgumentException("Production replay result is incomplete");
            }
            if (inference.engine() != engine) {
                throw new IllegalArgumentException("Production replay engine lineage is inconsistent");
            }
            routes = List.copyOf(routes);
            disconnectionPlanReviews = List.copyOf(disconnectionPlanReviews);
            nativeBExecutionWork = List.copyOf(nativeBExecutionWork);
            if (legacyBProvenance != null && (engine != TrackerMode.LEGACY_THEN_B
                    || !LegacyBProvenance.ARTIFACT.equals(legacyBProvenance.name())))
                throw new IllegalArgumentException("Intermediate provenance is reserved for sequential Hybrid");
            if (engine != TrackerMode.LEGACY_THEN_B && !nativeBExecutionWork.isEmpty())
                throw new IllegalArgumentException("Section work is reserved for sequential Hybrid");
            if (!disconnectionPlanReviews.isEmpty() && (level != ReplayLevel.FINAL_GEOMETRY
                    || disconnectionPlanReviews.size() != routes.size()))
                throw new IllegalArgumentException("Disconnection review output is incomplete");
            disconnectionPlanReviews.forEach(review -> {
                if (review.length() > 1_048_576) throw new IllegalArgumentException("Plan review exceeds text budget");
                Format15Safety.requireSafeExportedMetadata(review);
            });
        }

        /** Hash of the actual bounded intermediate record, absent for historical/non-Hybrid results. */
        public String legacyBProvenanceHash() {
            return legacyBProvenance == null ? "" : legacyBProvenance.sha256();
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
        TrackerMode numericalEngine = Format15NumericalPolicyReceipt.validate(archive, shared, payload.sharedInputHash());
        if (payload.runs().stream().anyMatch(run -> run.request().engine() != numericalEngine)) {
            throw new ReplayMismatchException("numerical-policy-engine-mismatch");
        }
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
            Format15NumericalPolicyReceipt.validateExecuted(production.result().inference());
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
            // The wire format uses zero as the default marker for an empty route list.
            // verifyIndexRun has already proved that the stored index records no choice (-1).
            // Do not turn that absence into an explicit choice rejected by the composer.
            if (!expected.routeIdentities().isEmpty()) {
                choices.put(position, expected.chosenRouteIndex());
            }
            actualRuns.add(new IntervalTraceBatch.IntervalRun(interval, request,
                    replayed, usage));
        }
        IntervalTraceBatch batch = new IntervalTraceBatch(shared.request(), shared.evidence(),
                shared.network(), partition, actualRuns, shared.options(), payload.authority());
        Format15NumericalPolicyReceipt.validateRecordedWork(archive,
                actualRuns.stream().map(run -> run.result().inference()).toList());
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
        if (index.containsKey("contactReviewSchema") || archive.artifact("contact-review.json").isPresent()) {
            String review = DisconnectionReplaySemantics.intervalReview(batch, assessment, choices);
            if (!Long.valueOf(1).equals(index.get("contactReviewSchema"))
                    || !archive.artifact("contact-review.json").map(Format15Artifact::sha256)
                            .filter(Format15Safety.sha256(review)::equals).isPresent())
                throw new ReplayMismatchException("strict-interval-contact-review-mismatch");
        }
        if (!shared.request().permissions().sharedNodeDisconnectionIntent().isEmpty()) {
            String review = DisconnectionReplaySemantics.intervalReview(batch, assessment, choices);
            if (!archive.artifact(DisconnectionReplaySemantics.ARTIFACT)
                    .map(Format15Artifact::sha256).filter(Format15Safety.sha256(review)::equals).isPresent())
                throw new ReplayMismatchException("strict-interval-disconnection-review-mismatch");
            if (assessment.plan().isPresent()) {
                var recorded = archive.artifact("private/interval-frozen-edit-plan.bin")
                        .map(artifact -> FrozenReplayCodec.decodeEditPlan(artifact.bytes()));
                if (recorded.isEmpty() || !recorded.orElseThrow().canonicalHash().equals(actualPlan))
                    throw new ReplayMismatchException("strict-interval-disconnection-plan-mismatch");
            }
        }
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
        Result actual = replay(input, level, input.request().engine());
        Format15NumericalPolicyReceipt.validateRecordedWork(archive, List.of(actual.inference()));
        LegacyBReplayWork.validate(archive, actual);
        verifyDisconnectionReview(archive, input, actual);
        return actual;
    }

    /** Loads a verified archive and runs one requested engine over the exact frozen values. */
    public static Result replay(Format15Archive archive, ReplayLevel level, String expectedInputHash,
            String expectedParameterHash, TrackerMode engine) {
        FrozenReplayInput input = load(archive, level, expectedInputHash, expectedParameterHash);
        Result actual = replay(input, level, engine);
        Format15NumericalPolicyReceipt.validateRecordedWork(archive, List.of(actual.inference()));
        LegacyBReplayWork.validate(archive, actual);
        verifyDisconnectionReview(archive, input, actual);
        return actual;
    }

    private static void verifyDisconnectionReview(Format15Archive archive, FrozenReplayInput input,
            Result actual) {
        if (actual.level() != ReplayLevel.FINAL_GEOMETRY) return;
        if (input.request().permissions().sharedNodeDisconnectionIntent().isEmpty()
                && archive.artifact("attempt-status.json").isPresent()) {
            try {
                var status = Format15ArchiveReader.parseObject(archive.artifact("attempt-status.json")
                        .orElseThrow().bytes(), "attempt-status.json");
                if (status.containsKey("planReviewSchema") && (!Long.valueOf(1).equals(status.get("planReviewSchema"))
                        || archive.artifact("contact-review.json").isEmpty()))
                    throw new IllegalArgumentException("missing or unsupported contact evidence");
            } catch (Exception missing) {
                throw new ReplayMismatchException("contact-production-review-unavailable");
            }
        }
        if (archive.artifact("contact-review.json").isPresent()) {
            if (input.request().permissions().junctionPolicy()
                    != org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy.FIXED)
                throw new ReplayMismatchException("contact-production-replay-unavailable");
            try {
                var artifact = archive.artifact("contact-review.json").orElseThrow();
                var receipt = Format15ArchiveReader.parseObject(artifact.bytes(), "contact-review.json");
                var status = Format15ArchiveReader.parseObject(archive.artifact("attempt-status.json")
                        .orElseThrow().bytes(), "attempt-status.json");
                int route = ((Number) status.get("routeIndex")).intValue();
                double radius = ((Number) receipt.get("searchRadiusMeters")).doubleValue();
                if (radius != input.planSearchRadiusMeters()) throw new IllegalArgumentException("radius binding");
                var plan = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernSingleWayEditPlanAdapter()
                        .adaptFrozen(input.request(), input.evidence(), input.network(), input.options(),
                                new ModernTracePipeline.Result(actual.inference(), actual.routes()), route, radius);
                String candidate = input.options().sourceTier() + ":" + input.request().evidenceContentHash()
                        + ":" + route + ":" + actual.routes().get(route).hypothesis().id();
                if (!artifact.sha256().equals(Format15Safety.sha256(DisconnectionReplaySemantics.review(
                        plan, input.evidence(), radius, candidate) + "\n")))
                    throw new IllegalArgumentException("contact review");
            } catch (Exception invalid) {
                throw new ReplayMismatchException("contact-production-plan-mismatch");
            }
        }
        if (input.request().permissions().sharedNodeDisconnectionIntent().isEmpty()) return;
        var artifact = archive.artifact(DisconnectionReplaySemantics.ARTIFACT);
        if (artifact.isPresent()) {
            if (!artifact.orElseThrow().sha256().equals(Format15Safety.sha256(
                    DisconnectionReplaySemantics.artifact(actual.disconnectionPlanReviews()))))
                throw new ReplayMismatchException("disconnection-production-plan-mismatch");
        } else if (archive.artifact(FinalReplayExpectation.ARTIFACT_NAME).isPresent()) {
            throw new ReplayMismatchException("disconnection-production-review-unavailable");
        }
        if (archive.artifact("frozen-edit-plan.bin").isPresent()) {
            try {
                var status = Format15ArchiveReader.parseObject(archive.artifact("attempt-status.json")
                        .orElseThrow().bytes(), "attempt-status.json");
                int route = ((Number) status.get("routeIndex")).intValue();
                var recorded = FrozenReplayCodec.decodeEditPlan(archive.artifact("frozen-edit-plan.bin").orElseThrow().bytes());
                var receipt = Format15ArchiveReader.parseObject(actual.disconnectionPlanReviews().get(route)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8), "disconnection-review");
                if (!recorded.canonicalHash().equals(receipt.get("planIdentity")))
                    throw new IllegalArgumentException("plan");
            } catch (Exception invalid) {
                throw new ReplayMismatchException("disconnection-recorded-plan-mismatch");
            }
        }
    }

    /**
     * Executes the current production engine over exact frozen values. This direct
     * forensic comparison does not attest which backend produced an old archive.
     */
    public static Result replay(FrozenReplayInput input, ReplayLevel level, TrackerMode engine) {
        if (input == null || level == null || engine == null) {
            throw new IllegalArgumentException("Frozen input, level, and engine are required");
        }
        requireSupportedLevel(level);
        if (engine == TrackerMode.HYBRID) {
            throw new ReplayMismatchException("unsupported-historical-a-b-hybrid");
        }
        if (engine == TrackerMode.LEGACY_V02) {
            throw new ReplayMismatchException("Legacy engine has no modern frozen replay route");
        }
        if ((engine == TrackerMode.CORRIDOR_AWARE || engine == TrackerMode.HYBRID)
                && input.request().corridorInput().isEmpty()) {
            throw new ReplayMismatchException("corridor-input-missing");
        }
        TrackerMode capturedEngine = input.request().engine();
        String inputHash = input.canonicalHash();
        FrozenReplayInput requested = withEngine(input, engine);
        if (engine == TrackerMode.LEGACY_THEN_B) {
            return legacyB(requested, level, capturedEngine, inputHash);
        }
        Result result = level == ReplayLevel.SCALAR_INFERENCE
            ? scalar(requested, capturedEngine, inputHash)
            : finalGeometry(requested, capturedEngine, inputHash);
        if (result.inference().status() == TraceHypothesisSet.Status.COMPLETE
                || result.inference().status() == TraceHypothesisSet.Status.AMBIGUOUS
                || result.inference().workAccounting().isPresent()) {
            Format15NumericalPolicyReceipt.validateExecuted(result.inference());
        }
        return result;
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
        String verifiedInputHash = input.canonicalHash();
        if (!verifiedInputHash.equals(archive.sourceIdentityHash())
                || !input.request().parameterHash().equals(archive.parameterHash())) {
            throw new ReplayMismatchException(
                "Frozen replay input identity disagrees with archive manifest");
        }
        Format15NumericalPolicyReceipt.validate(archive, input, verifiedInputHash);
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
            source.evidenceResolution(), source.corridorInput(), source.disconnectionSource());
        return new FrozenReplayInput(request, input.evidence(), input.network(), input.options(),
                input.planSearchRadiusMeters(), input.legacyB());
    }

    private static Result legacyB(FrozenReplayInput input, ReplayLevel level, TrackerMode capturedEngine,
            String inputHash) {
        if (input.legacyB() == null) throw new ReplayMismatchException("legacy-production-source-unavailable");
        var source = input.legacyB();
        var request = source.bind(input);
        var runner = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBProductionRunner();
        var ledger = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner()) {
            var deadline = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.Deadline.production();
            var discovery = source.visibleSource() != null
                    ? runner.discoverVisible(request, source.visibleSource(), CancellationProbe.NONE, owner, deadline)
                    : runner.discoverManaged(request, source.managedSource(), CancellationProbe.NONE, owner, deadline);
            var policy = source.parentPolicy().orElseGet(() -> new FrozenLegacyBInput.ParentPolicy(
                    discovery.value().seeds().stream().limit(2).map(seed -> seed.id()).toList(),true));
            var actualAdmitted = discovery.value().seeds().stream().map(seed -> seed.id())
                    .filter(policy.admittedParentIds()::contains).toList();
            if (!actualAdmitted.equals(policy.admittedParentIds()))
                throw new ReplayMismatchException("legacy-parent-admission-mismatch");
            var executed = runner.refine(discovery,policy.admittedParentIds(),policy.effectiveBudget().orElse(request.request().budgets()),
                    policy.bInferenceAllowed(),policy.unavailableReason(),CancellationProbe.NONE,owner,deadline);
            for (var section : executed.value().batch().sections()) {
                if (section.execution().isPresent()) {
                    var nativeResult = section.execution().orElseThrow().run().result();
                    if (nativeResult.status() == TraceHypothesisSet.Status.COMPLETE
                            || nativeResult.status() == TraceHypothesisSet.Status.AMBIGUOUS
                            || nativeResult.workAccounting().isPresent()) {
                        Format15NumericalPolicyReceipt.validateExecuted(nativeResult);
                    }
                }
            }
            var actual = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid
                    .LegacyBModernResultAdapter.adapt(executed.value(), request, input.options(), CancellationProbe.NONE);
            return new Result(level, capturedEngine, TrackerMode.LEGACY_THEN_B, actual.inference(),
                    level == ReplayLevel.SCALAR_INFERENCE ? List.of() : actual.routes(), inputHash,
                    level == ReplayLevel.SCALAR_INFERENCE ? List.of() : DisconnectionReplaySemantics.recompute(input, actual),
                    executed.value().batch().sections().stream().flatMap(section -> section.execution().stream())
                        .map(execution -> execution.run().result().workAccounting()).toList(),
                    LegacyBProvenance.artifact(request, executed.value()));
        }
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
                input.request().engine(), actual.inference(), actual.routes(), inputHash,
                DisconnectionReplaySemantics.recompute(input, actual));
        } catch (RuntimeException failure) {
            throw new ReplayMismatchException("production-engine-execution-failed");
        }
    }

    private static TraceEngine engine(TrackerMode mode, String fieldName) {
        return switch (mode) {
            case CORRIDOR_AWARE -> new CorridorEngineAdapter(fieldName);
            case PROBABILISTIC -> new ProbabilisticTraceEngine(fieldName, EvidenceModelParameters.defaults(),
                    ProbabilisticTraceEngine.reliabilityPolicyFor(mode));
            case HYBRID -> throw new ReplayMismatchException("unsupported-historical-a-b-hybrid");
            case DIRECTIONAL_IMAGE -> new DirectionalImageTraceEngine(fieldName);
            case LEGACY_V02 -> throw new ReplayMismatchException(
                "Legacy engine has no modern frozen replay route");
            case LEGACY_THEN_B -> throw new ReplayMismatchException(
                "Sequential Hybrid requires a legacy-then-b-v1 replay bundle");
        };
    }
}
