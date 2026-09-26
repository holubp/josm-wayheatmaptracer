package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.nio.charset.StandardCharsets;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.IntervalTraceBatch;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.FixedIntervalEditPlanComposer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.ManualJunctionEligibility;

/** Produces the named, checksummed frozen inputs consumed by strict production replay. */
public final class Format15ProductionBundleFactory {
    private static final String INTERVAL_INDEX_ARTIFACT = "interval-production.json";
    private static final String INTERVAL_PREVIEW_ARTIFACT = "private/interval-composed-preview.json";

    /** Typed per-interval failure/no-op reasons exported by the additive interval artifact. */
    public enum IntervalReason {
        VALIDATED, NO_PRODUCTION_ROUTE, NO_GEOMETRY_CHANGE, LOCAL_IMAGE_SUPPORT,
        LOCAL_ROUTE_BLOCKED, LOCAL_CONNECTOR_SUPPORT, T_LOCAL_PRECOMMAND_EVIDENCE,
        GLOBAL_FINAL_VALIDATION, RESOURCE_LIMIT, CANCELLED, LOCAL_FAILURE
    }

    private Format15ProductionBundleFactory() {
    }

    /** Creates a Format-15 archive payload for scalar/final replay only; it does not claim raster or edit-plan replay. */
    public static Format15Bundle create(String buildIdentity, FrozenReplayInput input) {
        if (buildIdentity == null || buildIdentity.isBlank() || input == null) {
            throw new IllegalArgumentException("Build identity and frozen replay input are required");
        }
        byte[] frozenInput = FrozenReplayCodec.encode(input);
        String inputHash = Format15Safety.sha256(frozenInput);
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>();
        artifacts.put("frozen-input.bin",
            Format15Artifact.binary("frozen-input.bin", frozenInput));
        String identities = "{\"codecVersion\":" + FrozenReplayCodec.VERSION
            + ",\"inputHash\":" + quote(inputHash) + ",\"evidenceHash\":"
            + quote(input.evidence().canonicalHash()) + ",\"networkHash\":"
            + quote(input.network().canonicalHash()) + ",\"parameterHash\":"
            + quote(input.request().parameterHash()) + "}\n";
        artifacts.put("frozen-input-identities.json",
            Format15Artifact.text("frozen-input-identities.json", identities));
        artifacts.put("trace-request.json", Format15Artifact.text("trace-request.json",
            "{\"engine\":" + quote(input.request().engine().name()) + ",\"fieldName\":"
                + quote(input.options().fieldName()) + ",\"frozenInputHash\":"
                + quote(inputHash) + "}\n"));
        artifacts.put("evidence-frame.json", Format15Artifact.text("evidence-frame.json",
            "{\"snapshotId\":" + quote(input.evidence().snapshotId())
                + ",\"contentHash\":" + quote(input.evidence().canonicalHash()) + "}\n"));
        return new Format15Bundle(buildIdentity, inputHash,
            input.request().parameterHash(), artifacts);
    }

    /** Captures the result actually produced by the live pipeline, without running inference again. */
    public static Format15Bundle createLive(String buildIdentity, FrozenReplayInput input,
            ModernTracePipeline.Result actual, String status, String sourceLineage,
            int routeIndex, AlignmentEditPlan plan, boolean reviewed, boolean applied) {
        return createLive(buildIdentity, input, actual, status, sourceLineage,
            routeIndex, plan, reviewed, applied, Map.of());
    }

    /** Captures worker counters beside the frozen input and actual output. */
    public static Format15Bundle createLive(String buildIdentity, FrozenReplayInput input,
            ModernTracePipeline.Result actual, String status, String sourceLineage,
            int routeIndex, AlignmentEditPlan plan, boolean reviewed, boolean applied,
            Map<String, Number> counters) {
        return createLive(buildIdentity, input, actual, status, sourceLineage, routeIndex,
                plan, reviewed, applied, counters, null);
    }

    /** Adds the capture/plan junction decision to the live attempt without changing replay inputs. */
    public static Format15Bundle createLive(String buildIdentity, FrozenReplayInput input,
            ModernTracePipeline.Result actual, String status, String sourceLineage,
            int routeIndex, AlignmentEditPlan plan, boolean reviewed, boolean applied,
            Map<String, Number> counters, ManualJunctionEligibility.Reason manualJunctionReason) {
        if (actual == null || status == null || status.isBlank()
                || sourceLineage == null || sourceLineage.isBlank()
                || routeIndex < -1 || routeIndex >= actual.routes().size()
                || (routeIndex == -1 && !actual.routes().isEmpty())
                || (routeIndex == -1 && (plan != null || reviewed || applied))
                || ((reviewed || applied) && plan == null)
                || ("applied".equals(status) && !applied)
                || ("confirmed".equals(status) && !reviewed)) {
            throw new IllegalArgumentException("Live diagnostic attempt is incomplete");
        }
        Format15Safety.requireSafeExportedMetadata(status);
        Format15Safety.requireSafeExportedMetadata(sourceLineage);
        if (plan != null && (!plan.selectedWayKey().equals(input.request().selectedWayKey())
                || !plan.selectedRange().equals(input.request().selectedRange())
                || !plan.before().canonicalHash().equals(input.network().canonicalHash())
                || !plan.evidenceHash().equals(input.evidence().canonicalHash())
                || !plan.settingsHash().equals(input.request().settingsHash())
                || !plan.parameterHash().equals(input.request().parameterHash())
                || !plan.routeIdentity().equals(actual.routes().get(routeIndex).hypothesis().id())
                || applied && plan.validation().disposition()
                    == org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport.Disposition.HARD_BLOCKED)) {
            throw new IllegalArgumentException("Live diagnostic plan differs from the computed route or input");
        }
        Format15Bundle base = create(buildIdentity, input);
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>(base.artifacts());
        Format15ReplayRunner.Result finalResult = new Format15ReplayRunner.Result(
            ReplayLevel.FINAL_GEOMETRY, input.request().engine(), input.request().engine(),
            actual.inference(), actual.routes(), input.canonicalHash());
        FinalReplayExpectation expectation = FinalReplayExpectation.capture(buildIdentity,
            input, finalResult);
        artifacts.put(FinalReplayExpectation.ARTIFACT_NAME, Format15Artifact.binary(
            FinalReplayExpectation.ARTIFACT_NAME, expectation.bytes()));
        artifacts.put("original-geometry.json", Format15Artifact.text("original-geometry.json",
            originalGeometry(input.network(), input.request().selectedWayKey(),
                input.request().selectedRange().firstIndex(),
                input.request().selectedRange().lastIndex())));
        if (routeIndex >= 0) {
            ModernTracePipeline.Route route = actual.routes().get(routeIndex);
            artifacts.put("raw-route.json", Format15Artifact.text("raw-route.json",
                metricGeometry(route.rawHypothesis().points())));
            artifacts.put("final-route.json", Format15Artifact.text("final-route.json",
                metricGeometry(route.hypothesis().points())));
            if (reviewed) {
                String reviewedGeometry = geographicWays(plan.finalPreviewWays());
                artifacts.put("reviewed-route.json", Format15Artifact.text("reviewed-route.json",
                    reviewedGeometry));
                artifacts.put("reviewed-route-identity.json", Format15Artifact.text(
                    "reviewed-route-identity.json", "{\"editPlanHash\":" + quote(plan.canonicalHash())
                        + ",\"reviewedGeometryHash\":" + quote(Format15Safety.sha256(
                            reviewedGeometry.getBytes(StandardCharsets.UTF_8))) + "}\n"));
            }
        }
        if (plan != null) {
            artifacts.put("frozen-edit-plan.bin", Format15Artifact.binary("frozen-edit-plan.bin",
                FrozenReplayCodec.encodeEditPlan(plan)));
            artifacts.put("planned-geometry.json", Format15Artifact.text("planned-geometry.json",
                geographicWays(plan.finalPreviewWays())));
            artifacts.put("edit-plan-identity.json", Format15Artifact.text("edit-plan-identity.json",
                "{\"planHash\":" + quote(plan.canonicalHash()) + ",\"beforeHash\":"
                    + quote(plan.before().canonicalHash()) + ",\"afterHash\":"
                    + quote(plan.after().canonicalHash()) + "}\n"));
            if (applied) {
                artifacts.put("applied-geometry.json", Format15Artifact.text("applied-geometry.json",
                    geographicWays(plan.finalPreviewWays())));
            }
        }
        artifacts.put("performance-counters.json", Format15Artifact.text(
            "performance-counters.json", performanceCounters(actual, counters)));
        artifacts.put("attempt-status.json", Format15Artifact.text("attempt-status.json",
            "{\"status\":" + quote(status) + ",\"sourceLineage\":"
                + quote(sourceLineage) + ",\"routeIndex\":" + routeIndex
                + ",\"manualJunctionReason\":" + (manualJunctionReason == null
                    ? "null" : quote(manualJunctionReason.name()))
                + ",\"reviewed\":" + reviewed + ",\"applied\":" + applied
                + ",\"privateData\":true,\"capabilities\":{\"SCALAR_INFERENCE\":true,"
                + "\"FINAL_GEOMETRY\":true,\"RASTER_INFERENCE\":false,"
                + "\"FULL_EDIT_PLAN\":false}}\n"));
        return new Format15Bundle(base.buildIdentity(), base.sourceIdentityHash(),
            base.parameterHash(), artifacts);
    }

    /**
     * Serializes the existing per-interval production routes and their already composed preview.
     * The caller must pass the source receipt that accompanied acquisition; the detached batch
     * deliberately does not synthesize that external receipt. The preview geometry is private
     * evidence. This entry point emits no frozen full-way inference input and never grants
     * {@code FULL_EDIT_PLAN} replay capability.
     *
     * <p>The live worker interface is: call this after production interval tracing and
     * {@link FixedIntervalEditPlanComposer#compose(IntervalTraceBatch, Map)} with the same batch
     * and route-choice map, then pass the actual safe source receipt, UI status, and the exact
     * composed plan identity shown at review/apply. Pass {@code null} identities before review
     * or apply.</p>
     */
    public static Format15Bundle createLiveIntervals(String buildIdentity,
            IntervalTraceBatch batch, FixedIntervalEditPlanComposer.Assessment assessment,
            Map<Integer, Integer> routeChoices, String sourceReceipt, String status,
            String reviewedPlanIdentity, String appliedPlanIdentity) {
        if (buildIdentity == null || buildIdentity.isBlank() || batch == null || assessment == null
                || routeChoices == null || sourceReceipt == null || sourceReceipt.isBlank()
                || status == null || status.isBlank()
                || assessment.intervals().size() != batch.runs().size()) {
            throw new IllegalArgumentException("Interval production artifact inputs are incomplete");
        }
        Format15Safety.requireSafeExportedMetadata(buildIdentity);
        requireSafeSourceReceipt(sourceReceipt);
        Format15Safety.requireSafeExportedMetadata(status);
        if (!batch.fullRequest().selectedWayKey().equals(batch.partition().selectedWayKey())
                || !batch.fullRequest().selectedRange().equals(batch.partition().selectedRange())
                || !batch.fullRequest().networkContentHash().equals(batch.network().canonicalHash())
                || !batch.fullRequest().evidenceContentHash().equals(batch.evidence().canonicalHash())) {
            throw new IllegalArgumentException("Interval batch source lineage is inconsistent");
        }
        if (routeChoices.keySet().stream().anyMatch(index -> index == null || index < 0
                    || index >= batch.runs().size())
                || routeChoices.values().stream().anyMatch(index -> index == null || index < 0)) {
            throw new IllegalArgumentException("Interval route choice is outside the batch");
        }

        AlignmentEditPlan plan = assessment.plan().orElse(null);
        String planIdentity = plan == null ? null : plan.canonicalHash();
        if (plan != null && (!plan.selectedWayKey().equals(batch.fullRequest().selectedWayKey())
                || !plan.selectedRange().equals(batch.fullRequest().selectedRange())
                || !plan.before().canonicalHash().equals(batch.network().canonicalHash())
                || !plan.evidenceHash().equals(batch.evidence().canonicalHash())
                || !plan.parameterHash().equals(batch.fullRequest().parameterHash()))) {
            throw new IllegalArgumentException("Composed interval plan differs from its frozen source");
        }
        if (reviewedPlanIdentity != null && !reviewedPlanIdentity.equals(planIdentity)
                || appliedPlanIdentity != null && !appliedPlanIdentity.equals(planIdentity)
                || appliedPlanIdentity != null && reviewedPlanIdentity == null
                || appliedPlanIdentity != null && !assessment.applyAvailable()
                || ("confirmed".equals(status) || "reviewed".equals(status))
                        && reviewedPlanIdentity == null
                || ("applied".equals(status) || "applied-after-review".equals(status))
                        && appliedPlanIdentity == null) {
            throw new IllegalArgumentException("Interval review or applied identity differs from the composed plan");
        }
        if (reviewedPlanIdentity != null) Format15Safety.requireSafeExportedMetadata(reviewedPlanIdentity);
        if (appliedPlanIdentity != null) Format15Safety.requireSafeExportedMetadata(appliedPlanIdentity);

        Map<PrimitiveKey, List<GeographicPoint>> previewWays = plan == null
                ? Map.of(batch.fullRequest().selectedWayKey(), assessment.selectedWayPreview())
                : plan.finalPreviewWays();
        String preview = geographicWays(previewWays);
        String previewHash = Format15Safety.sha256(preview.getBytes(StandardCharsets.UTF_8));
        StringBuilder index = new StringBuilder("{\"schema\":1,\"artifactKind\":\"INTERVAL_PRODUCTION\"")
                .append(",\"sourceReceipt\":").append(quote(sourceReceipt))
                .append(",\"networkHash\":").append(quote(batch.network().canonicalHash()))
                .append(",\"evidenceHash\":").append(quote(batch.evidence().canonicalHash()))
                .append(",\"networkSnapshotId\":").append(quote(batch.network().snapshotId()))
                .append(",\"evidenceSnapshotId\":").append(quote(batch.evidence().snapshotId()))
                .append(",\"parameterHash\":").append(quote(batch.fullRequest().parameterHash()))
                .append(",\"status\":").append(quote(status))
                .append(",\"planIdentity\":").append(planIdentity == null ? "null" : quote(planIdentity))
                .append(",\"previewArtifact\":").append(quote(INTERVAL_PREVIEW_ARTIFACT))
                .append(",\"previewSha256\":").append(quote(previewHash))
                .append(",\"reviewedPlanIdentity\":")
                .append(reviewedPlanIdentity == null ? "null" : quote(reviewedPlanIdentity))
                .append(",\"appliedPlanIdentity\":")
                .append(appliedPlanIdentity == null ? "null" : quote(appliedPlanIdentity))
                .append(",\"applyAvailable\":").append(assessment.applyAvailable())
                .append(",\"privateData\":true,\"capabilities\":{\"INTERVAL_PRODUCTION_ARTIFACT\":true,\"FULL_EDIT_PLAN\":false,\"SCALAR_INFERENCE\":false,\"RASTER_INFERENCE\":false}")
                .append(",\"intervals\":[");
        for (int i = 0; i < batch.runs().size(); i++) {
            if (i > 0) index.append(',');
            IntervalTraceBatch.IntervalRun run = batch.runs().get(i);
            FixedIntervalEditPlanComposer.IntervalAssessment result = assessment.intervals().get(i);
            int selectedRouteChoice = routeChoices.getOrDefault(i, 0);
            if (run.routes().isEmpty() && routeChoices.containsKey(i)
                    || !run.routes().isEmpty() && selectedRouteChoice >= run.routes().size()) {
                throw new IllegalArgumentException("Interval route choice is outside production alternatives");
            }
            int routeIndex = run.routes().isEmpty() ? -1 : selectedRouteChoice;
            String routeIdentity = routeIndex < 0 ? "unavailable"
                    : run.routes().get(routeIndex).hypothesis().id();
            if (result.intervalIndex() != i || result.routeIndex() < 0
                    || result.routeIndex() >= Math.max(1, run.routes().size())
                    || result.routeIdentity() == null) {
                throw new IllegalArgumentException("Composer assessment differs from the selected production route");
            }
            String assessmentIdentity = run.routes().isEmpty() ? "unavailable"
                    : run.routes().get(result.routeIndex()).hypothesis().id();
            if (!routeIdentity.equals(assessmentIdentity) || !routeIdentity.equals(result.routeIdentity())) {
                throw new IllegalArgumentException("Composer assessment differs from the selected production route");
            }
            Format15Safety.requireSafeExportedMetadata(routeIdentity);
            IntervalReason reason = intervalReason(result.reason());
            index.append("{\"intervalIndex\":").append(i)
                    .append(",\"occurrenceRange\":").append(rangeJson(run.interval().range()))
                    .append(",\"traceRange\":").append(rangeJson(run.interval().traceRange()))
                    .append(",\"chosenRouteIndex\":").append(routeIndex)
                    .append(",\"chosenRouteIdentity\":").append(quote(routeIdentity))
                    .append(",\"alternativesTruncated\":").append(run.alternativesTruncated())
                    .append(",\"disposition\":").append(quote(result.disposition().name()))
                    .append(",\"reason\":").append(quote(reason.name()))
                    .append(",\"alternatives\":[");
            for (int route = 0; route < run.routes().size(); route++) {
                if (route > 0) index.append(',');
                String id = run.routes().get(route).hypothesis().id();
                Format15Safety.requireSafeExportedMetadata(id);
                index.append("{\"index\":").append(route)
                        .append(",\"identity\":").append(quote(id)).append('}');
            }
            index.append("]}");
        }
        byte[] indexBytes = index.append("]}\n").toString().getBytes(StandardCharsets.UTF_8);
        String sourceHash = Format15Safety.sha256((batch.network().canonicalHash() + ":"
                + batch.evidence().canonicalHash() + ":" + sourceReceipt)
                .getBytes(StandardCharsets.UTF_8));
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>();
        artifacts.put(INTERVAL_INDEX_ARTIFACT,
                Format15Artifact.text(INTERVAL_INDEX_ARTIFACT,
                        new String(indexBytes, StandardCharsets.UTF_8)));
        artifacts.put(INTERVAL_PREVIEW_ARTIFACT,
                Format15Artifact.text(INTERVAL_PREVIEW_ARTIFACT, preview));
        return new Format15Bundle(buildIdentity, sourceHash, batch.fullRequest().parameterHash(), artifacts);
    }

    private static IntervalReason intervalReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Interval assessment reason is missing");
        }
        String code = reason.split(":", 2)[0];
        try {
            return IntervalReason.valueOf(code);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Interval assessment reason is not a known typed reason", exception);
        }
    }

    private static void requireSafeSourceReceipt(String sourceReceipt) {
        Format15Safety.requireSafeExportedMetadata(sourceReceipt);
        String lower = sourceReceipt.toLowerCase(java.util.Locale.ROOT);
        if (sourceReceipt.length() > 512 || sourceReceipt.indexOf('\n') >= 0
                || sourceReceipt.indexOf('\r') >= 0 || lower.contains("://")
                || lower.contains("authorization") || lower.contains("cookie")
                || lower.contains("header:")) {
            throw new IllegalArgumentException("Source receipt must be bounded redacted metadata");
        }
    }

    private static String rangeJson(org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange range) {
        return "{\"first\":" + range.firstIndex() + ",\"last\":" + range.lastIndex() + "}";
    }

    private static String performanceCounters(ModernTracePipeline.Result actual,
            Map<String, Number> counters) {
        if (counters == null || counters.size() > 128) {
            throw new IllegalArgumentException("Modern counter inventory exceeds budget");
        }
        StringBuilder json = new StringBuilder("{\"evaluatedStates\":")
            .append(actual.inference().evaluatedStates())
            .append(",\"evaluatedTransitions\":")
            .append(actual.inference().evaluatedTransitions())
            .append(",\"routes\":").append(actual.routes().size())
            .append(",\"hypotheses\":").append(actual.inference().hypotheses().size())
            .append(",\"counters\":{");
        boolean first = true;
        for (Map.Entry<String, Number> counter : new java.util.TreeMap<>(counters).entrySet()) {
            String name = counter.getKey();
            Number value = counter.getValue();
            if (name == null || !name.matches("[A-Za-z][A-Za-z0-9.]{0,80}")
                    || value == null || !Double.isFinite(value.doubleValue())) {
                throw new IllegalArgumentException("Modern counter value is invalid");
            }
            if (!first) json.append(',');
            first = false;
            json.append(quote(name)).append(':').append(value);
        }
        return json.append("}}\n").toString();
    }

    /** Emits a truthful terminal attempt even when capture failed before detached input existed. */
    public static Format15Bundle createUnavailableLive(String buildIdentity, String status,
            String sourceLineage, String attemptIdentity) {
        return createUnavailableLive(buildIdentity, status, sourceLineage, attemptIdentity, null);
    }

    /** Adds a bounded manual-junction reason when capture fails before detached input exists. */
    public static Format15Bundle createUnavailableLive(String buildIdentity, String status,
            String sourceLineage, String attemptIdentity,
            ManualJunctionEligibility.Reason manualJunctionReason) {
        if (status == null || status.isBlank() || sourceLineage == null
                || sourceLineage.isBlank() || attemptIdentity == null || attemptIdentity.isBlank()) {
            throw new IllegalArgumentException("Unavailable attempt metadata is incomplete");
        }
        Format15Safety.requireSafeExportedMetadata(sourceLineage);
        Format15Safety.requireSafeExportedMetadata(attemptIdentity);
        Format15Safety.requireSafeExportedMetadata(status);
        String identity = Format15Safety.sha256(attemptIdentity);
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>();
        artifacts.put("attempt-status.json", Format15Artifact.text("attempt-status.json",
            "{\"status\":" + quote(status) + ",\"sourceLineage\":"
                + quote(sourceLineage) + ",\"attemptIdentityHash\":" + quote(identity)
                + ",\"manualJunctionReason\":" + (manualJunctionReason == null
                    ? "null" : quote(manualJunctionReason.name()))
                + ",\"privateData\":true,\"capabilities\":{\"SCALAR_INFERENCE\":false,"
                + "\"FINAL_GEOMETRY\":false,\"RASTER_INFERENCE\":false,"
                + "\"FULL_EDIT_PLAN\":false}}\n"));
        return new Format15Bundle(buildIdentity, identity, identity, artifacts);
    }

    private static String originalGeometry(NetworkSnapshot network, PrimitiveKey key,
            int first, int last) {
        DetachedWay way = (DetachedWay) network.primitives().get(key);
        if (way == null || last >= way.nodeKeys().size()) {
            throw new IllegalArgumentException("Selected original geometry is unavailable");
        }
        StringBuilder json = new StringBuilder("{\"coordinateSpace\":\"geographic-degrees\",\"points\":[");
        for (int index = first; index <= last; index++) {
            if (index > first) json.append(',');
            DetachedNode node = (DetachedNode) network.primitives().get(way.nodeKeys().get(index));
            GeographicPoint point = node.coordinate();
            json.append('[').append(point.latitudeDegrees()).append(',')
                .append(point.longitudeDegrees()).append(']');
        }
        return json.append("]}\n").toString();
    }

    private static String metricGeometry(List<MetricPoint> points) {
        StringBuilder json = new StringBuilder("{\"coordinateSpace\":\"local-meters\",\"points\":[");
        for (int index = 0; index < points.size(); index++) {
            if (index > 0) json.append(',');
            MetricPoint point = points.get(index);
            json.append('[').append(point.xMeters()).append(',').append(point.yMeters()).append(']');
        }
        return json.append("]}\n").toString();
    }

    private static String geographicWays(Map<PrimitiveKey, List<GeographicPoint>> ways) {
        StringBuilder json = new StringBuilder("{\"coordinateSpace\":\"geographic-degrees\",\"ways\":{");
        boolean firstWay = true;
        for (PrimitiveKey key : new java.util.TreeSet<>(ways.keySet())) {
            if (!firstWay) json.append(',');
            firstWay = false;
            json.append(quote(key.toString())).append(':').append('[');
            List<GeographicPoint> points = ways.get(key);
            for (int index = 0; index < points.size(); index++) {
                if (index > 0) json.append(',');
                GeographicPoint point = points.get(index);
                json.append('[').append(point.latitudeDegrees()).append(',')
                    .append(point.longitudeDegrees()).append(']');
            }
            json.append(']');
        }
        return json.append("}}\n").toString();
    }

    /** Creates a frozen bundle that records one actual scalar production result. */
    public static Format15Bundle createWithExpectedScalarOutput(String buildIdentity,
            FrozenReplayInput input, TrackerMode requestedEngine) {
        ScalarReplayExpectation.requireBuildIdentityFitsEnvelope(buildIdentity);
        Format15Bundle base = create(buildIdentity, input);
        Format15ReplayRunner.Result actual = Format15ReplayRunner.replay(input,
            ReplayLevel.SCALAR_INFERENCE, requestedEngine);
        ScalarReplayExpectation expectation = ScalarReplayExpectation.capture(
            base.buildIdentity(), input, actual);
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>(base.artifacts());
        artifacts.put(ScalarReplayExpectation.ARTIFACT_NAME, Format15Artifact.binary(
            ScalarReplayExpectation.ARTIFACT_NAME, expectation.bytes()));
        return new Format15Bundle(base.buildIdentity(), base.sourceIdentityHash(),
            base.parameterHash(), artifacts);
    }

    /** Creates a frozen bundle that records one actual final-geometry production result. */
    public static Format15Bundle createWithExpectedFinalOutput(String buildIdentity,
            FrozenReplayInput input, TrackerMode requestedEngine) {
        ScalarReplayExpectation.requireBuildIdentityFitsEnvelope(buildIdentity);
        Format15Bundle base = create(buildIdentity, input);
        Format15ReplayRunner.Result actual = Format15ReplayRunner.replay(input,
            ReplayLevel.FINAL_GEOMETRY, requestedEngine);
        FinalReplayExpectation expectation = FinalReplayExpectation.capture(
            base.buildIdentity(), input, actual);
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>(base.artifacts());
        artifacts.put(FinalReplayExpectation.ARTIFACT_NAME, Format15Artifact.binary(
            FinalReplayExpectation.ARTIFACT_NAME, expectation.bytes()));
        return new Format15Bundle(base.buildIdentity(), base.sourceIdentityHash(),
            base.parameterHash(), artifacts);
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                result.append('\\');
            }
            if (character < 0x20) {
                result.append(String.format("\\u%04x", (int) character));
            } else {
                result.append(character);
            }
        }
        return result.append('"').toString();
    }
}
