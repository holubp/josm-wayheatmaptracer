package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

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

/** Produces the named, checksummed frozen inputs consumed by strict production replay. */
public final class Format15ProductionBundleFactory {
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
                artifacts.put("reviewed-route.json", Format15Artifact.text("reviewed-route.json",
                    metricGeometry(route.hypothesis().points())));
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
                + ",\"reviewed\":" + reviewed + ",\"applied\":" + applied
                + ",\"privateData\":true,\"capabilities\":{\"SCALAR_INFERENCE\":true,"
                + "\"FINAL_GEOMETRY\":true,\"RASTER_INFERENCE\":false,"
                + "\"FULL_EDIT_PLAN\":false}}\n"));
        return new Format15Bundle(base.buildIdentity(), base.sourceIdentityHash(),
            base.parameterHash(), artifacts);
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
