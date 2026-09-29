package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;

import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.ExistingWayNodeOccurrence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageSupportedLocalCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;

/** Bounded typed snapshot and component evidence for one already-produced final result. */
final class FinalOutputComponentsCodec {
    static final String ARTIFACT = "private/final-output-components.bin";
    private static final int MAGIC = 0x57485443; // WHTC
    private static final int VERSION = 1;
    private static final int MAX_ROUTES = 4_096;
    private static final int MAX_HYPOTHESES = 4_096;
    private static final int MAX_ITEMS = 500_000;
    private static final int MAX_STRING_BYTES = 1_048_576;

    enum Component {
        INFERENCE_HEADER, INFERENCE_GEOMETRY, INFERENCE_SEMANTICS,
        ROUTE_RAW_GEOMETRY, ROUTE_RAW_SEMANTICS,
        ROUTE_FINAL_GEOMETRY, ROUTE_FINAL_SEMANTICS,
        ROUTE_ASSIGNMENT_GEOMETRY, ROUTE_PROVENANCE, ROUTE_QUALITY, ROUTE_CLEANUP
    }

    enum Availability { AVAILABLE, UNAVAILABLE_BUDGET }
    enum ComparisonStatus { MATCH, MISMATCH, UNAVAILABLE, NOT_COMPARABLE }

    record Binding(String buildIdentity, String inputHash, String parameterHash,
            TrackerMode capturedEngine, TrackerMode requestedEngine, ReplayLevel replayLevel,
            int fingerprintSchema, String rootFingerprint) {
        Binding {
            Format15Safety.requireSafeExportedMetadata(buildIdentity);
            Format15Safety.requiredHash(inputHash, "inputHash");
            Format15Safety.requiredHash(parameterHash, "parameterHash");
            Format15Safety.requiredHash(rootFingerprint, "rootFingerprint");
            if (capturedEngine == null || requestedEngine == null
                    || replayLevel != ReplayLevel.FINAL_GEOMETRY
                    || fingerprintSchema != FinalReplayFingerprint.SCHEMA_VERSION) {
                throw new IllegalArgumentException("final-components-binding-invalid");
            }
        }
    }

    record Encoded(Availability availability, byte[] bytes, Map<Component, String> digests,
            QualitySummary summary) {
        Encoded {
            if (availability == null || digests == null || summary == null
                    || availability == Availability.AVAILABLE && bytes == null
                    || availability == Availability.UNAVAILABLE_BUDGET && bytes != null) {
                throw new IllegalArgumentException("final-components-encoded-invalid");
            }
            bytes = bytes == null ? null : bytes.clone();
            digests = Map.copyOf(digests);
        }
        @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
    }

    record QualitySummary(int routeCount, int directlySupportedRoutes, int routesWithFindings,
            int findingCount, Map<String, Integer> findings, Map<String, Integer> dispositions,
            Map<String, Integer> supportOwnership, boolean allImageCostsAvailable,
            int unavailableImageCosts, double totalLengthMeters, double directSupportMeters,
            double worstUnsupportedSpanMeters, List<RouteQuality> routeMetrics) {
        QualitySummary {
            findings = Map.copyOf(findings);
            dispositions = Map.copyOf(dispositions);
            supportOwnership = Map.copyOf(supportOwnership);
            routeMetrics = List.copyOf(routeMetrics);
        }
    }

    record RouteQuality(Double meanImageCenterCost, double bendPreservingRoughness) {
        RouteQuality {
            if (meanImageCenterCost != null && !Double.isFinite(meanImageCenterCost)
                    || !Double.isFinite(bendPreservingRoughness)) {
                throw new IllegalArgumentException("summary-nonfinite");
            }
        }
    }

    record Prepared(Binding binding, Format15ReplayRunner.Result output,
            Map<Component, String> digests, QualitySummary summary, int byteCount) { }

    static final class Admitted {
        private final byte[] payload;
        private final int offset;
        private final int length;
        private final Binding binding;
        private Admitted(byte[] payload, int offset, int length, Binding binding) {
            this.payload = payload; this.offset = offset; this.length = length; this.binding = binding;
        }
    }

    record Snapshot(Binding binding, Format15ReplayRunner.Result output,
            Map<Component, String> digests, QualitySummary summary) {
        Snapshot { digests = Map.copyOf(digests); }
    }

    record Comparison(ComparisonStatus status, List<Component> differingComponents,
            String integrityCode, QualitySummary expectedSummary, QualitySummary actualSummary) {
        Comparison { differingComponents = List.copyOf(differingComponents); }
    }

    private FinalOutputComponentsCodec() { }

    static Encoded encode(Binding binding, Format15ReplayRunner.Result output) {
        return encode(binding, output, Format15Safety.MAX_ARTIFACT_BYTES, 0);
    }

    static Encoded encode(Binding binding, Format15ReplayRunner.Result output,
            int maximumBytes, long alreadyRetainedBytes) {
        if (binding == null || output == null) throw new IllegalArgumentException("final-components-output-binding-mismatch");
        if (output.routes().size() > ReplayOutputAdmission.MAX_ROUTES
                || output.inference().hypotheses().size() > ReplayOutputAdmission.MAX_HYPOTHESES) {
            throw new IllegalArgumentException("final-output-invalid");
        }
        QualitySummary summary = qualitySummary(output);
        try {
            validate(binding, output);
            ReplayOutputAdmission.Budget budget = new ReplayOutputAdmission.Budget(alreadyRetainedBytes);
            budget.output(output);
            Prepared prepared = prepare(binding, output, maximumBytes);
            // Exact buffer, returned array, immutable copy/accessor/artifact hand-off, and
            // retained original string characters are covered together before allocation.
            budget.payload(prepared.byteCount(), 6);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(prepared.byteCount());
            try (DataOutputStream data = new DataOutputStream(
                    new BoundedOutputStream(bytes, prepared.byteCount()))) {
                writeSnapshot(data, prepared);
            }
            return new Encoded(Availability.AVAILABLE, bytes.toByteArray(), prepared.digests(), summary);
        } catch (SnapshotBudgetExceeded budget) {
            return new Encoded(Availability.UNAVAILABLE_BUDGET, null, Map.of(), summary);
        } catch (IllegalArgumentException invalid) {
            if (ReplayOutputAdmission.isBudget(invalid)) {
                return new Encoded(Availability.UNAVAILABLE_BUDGET, null, Map.of(), summary);
            }
            throw invalid;
        } catch (IOException failure) {
            throw new IllegalStateException("final-components-write-failed", failure);
        }
    }

    static void validate(Binding binding, Format15ReplayRunner.Result output) {
        if (binding == null || output == null
                || output.level() != binding.replayLevel()
                || output.capturedEngine() != binding.capturedEngine()
                || output.engine() != binding.requestedEngine()
                || !output.inputHash().equals(binding.inputHash())) {
            throw new IllegalArgumentException("final-components-output-binding-mismatch");
        }
        validateOutputMetadata(output);
        String root = FinalReplayFingerprint.sha256(output);
        if (!root.equals(binding.rootFingerprint())) {
            throw new IllegalArgumentException("final-components-root-binding-mismatch");
        }
    }

    static Prepared prepare(Binding binding, Format15ReplayRunner.Result output, int maximumBytes)
            throws IOException {
        if (maximumBytes < 0 || maximumBytes > Format15Safety.MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("snapshot-byte-budget-invalid");
        }
        validate(binding, output);
        Prepared prepared = new Prepared(binding, output, componentDigests(output), qualitySummary(output), 0);
        BoundedOutputStream measured = new BoundedOutputStream(OutputStream.nullOutputStream(), maximumBytes);
        try (DataOutputStream data = new DataOutputStream(measured)) { writeSnapshot(data, prepared); }
        return new Prepared(binding, output, prepared.digests(), prepared.summary(), measured.written);
    }

    static void writeSnapshot(DataOutputStream data, Prepared prepared) throws IOException {
        data.writeInt(MAGIC);
        data.writeInt(VERSION);
        writeBinding(data, prepared.binding());
        writeOutput(data, prepared.output());
        data.writeInt(Component.values().length);
        for (Component component : Component.values()) {
            writeString(data, component.name());
            writeHash(data, prepared.digests().get(component));
        }
    }

    static String requestHash(org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream data = new DataOutputStream(new BoundedOutputStream(
                    new DigestOutputStream(OutputStream.nullOutputStream(), digest), Format15Safety.MAX_ARTIFACT_BYTES))) {
                FrozenReplayCodec.writeRequestOnly(data, request);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (SnapshotBudgetExceeded budget) {
            throw new ReplayOutputAdmission.BudgetExceeded();
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("interval-request-hash-failed", failure);
        }
    }

    static Snapshot decode(byte[] payload, Binding expectedBinding) {
        if (payload == null || payload.length > Format15Safety.MAX_ARTIFACT_BYTES
                || expectedBinding == null) {
            throw new ReplayMismatchException("final-components-invalid");
        }
        ReplayOutputAdmission.Budget budget = new ReplayOutputAdmission.Budget(0);
        try {
            budget.payload(payload.length, 3);
            byte[] sealed = payload.clone();
            return materialize(preflight(sealed, 0, sealed.length, expectedBinding, budget));
        } catch (IOException | RuntimeException malformed) {
            if (malformed instanceof ReplayMismatchException mismatch) throw mismatch;
            throw new ReplayMismatchException("final-components-invalid");
        }
    }

    static Snapshot materialize(Admitted admitted) {
        Binding expectedBinding = admitted.binding;
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(
                admitted.payload, admitted.offset, admitted.length))) {
            if (data.readInt() != MAGIC || data.readInt() != VERSION) {
                throw new IllegalArgumentException("header");
            }
            Binding binding = readBinding(data);
            if (!sameNonRootBinding(binding, expectedBinding)) {
                throw new IllegalArgumentException("binding");
            }
            if (!binding.rootFingerprint().equals(expectedBinding.rootFingerprint())) {
                throw new ReplayMismatchException("final-output-mismatch");
            }
            Format15ReplayRunner.Result output = readOutput(data);
            int digestCount = readCount(data, Component.values().length, "digest-count");
            if (digestCount != Component.values().length) throw new IllegalArgumentException("digest-count");
            Map<Component, String> encodedDigests = new EnumMap<>(Component.class);
            for (int index = 0; index < digestCount; index++) {
                Component component = Component.valueOf(readString(data));
                String digest = readHash(data);
                if (encodedDigests.put(component, digest) != null) throw new IllegalArgumentException("duplicate");
            }
            if (data.read() != -1) throw new IllegalArgumentException("trailing");
            if (output.level() != binding.replayLevel()
                    || output.capturedEngine() != binding.capturedEngine()
                    || output.engine() != binding.requestedEngine()
                    || !output.inputHash().equals(binding.inputHash())) {
                throw new IllegalArgumentException("output-binding");
            }
            String root = FinalReplayFingerprint.sha256(output);
            if (!root.equals(binding.rootFingerprint())) throw new IllegalArgumentException("root");
            Map<Component, String> actualDigests = componentDigests(output);
            if (!actualDigests.equals(encodedDigests)) throw new IllegalArgumentException("component-digests");
            return new Snapshot(binding, output, actualDigests, qualitySummary(output));
        } catch (ReplayMismatchException mismatch) {
            throw mismatch;
        } catch (IOException | RuntimeException malformed) {
            throw new ReplayMismatchException("final-components-invalid");
        }
    }

    static Comparison compare(Snapshot expected, Format15ReplayRunner.Result actual) {
        if (expected == null || actual == null) {
            return new Comparison(ComparisonStatus.UNAVAILABLE, List.of(), "", null, null);
        }
        if (actual.level() != expected.binding().replayLevel()
                || actual.capturedEngine() != expected.binding().capturedEngine()
                || actual.engine() != expected.binding().requestedEngine()
                || !actual.inputHash().equals(expected.binding().inputHash())) {
            return new Comparison(ComparisonStatus.MISMATCH, List.of(),
                    "binding-mismatch", expected.summary(), qualitySummary(actual));
        }
        Map<Component, String> actualDigests = componentDigests(actual);
        List<Component> differing = new ArrayList<>();
        for (Component component : Component.values()) {
            if (!expected.digests().get(component).equals(actualDigests.get(component))) {
                differing.add(component);
            }
        }
        boolean rootMatches = expected.binding().rootFingerprint()
                .equals(FinalReplayFingerprint.sha256(actual));
        if (differing.isEmpty() && !rootMatches) {
            return new Comparison(ComparisonStatus.MISMATCH, List.of(),
                    "component-coverage-integrity", expected.summary(), qualitySummary(actual));
        }
        return new Comparison(rootMatches && differing.isEmpty()
                ? ComparisonStatus.MATCH : ComparisonStatus.MISMATCH, differing, "",
                expected.summary(), qualitySummary(actual));
    }

    static Map<Component, String> componentDigests(Format15ReplayRunner.Result output) {
        if (output == null) throw new IllegalArgumentException("final-output-invalid");
        EnumMap<Component, String> result = new EnumMap<>(Component.class);
        result.put(Component.INFERENCE_HEADER, digest(Component.INFERENCE_HEADER, data -> {
            var inference = output.inference();
            writeString(data, inference.engine().name());
            writeString(data, inference.status().name());
            data.writeBoolean(inference.alternativesTruncated());
            data.writeLong(inference.evaluatedStates());
            data.writeLong(inference.evaluatedTransitions());
            data.writeInt(inference.hypotheses().size());
        }));
        result.put(Component.INFERENCE_GEOMETRY, digest(Component.INFERENCE_GEOMETRY, data -> {
            data.writeInt(output.inference().hypotheses().size());
            for (TraceHypothesis hypothesis : output.inference().hypotheses()) {
                ReplayOutputCanonical.writeHypothesisGeometry(data, hypothesis);
            }
        }));
        result.put(Component.INFERENCE_SEMANTICS, digest(Component.INFERENCE_SEMANTICS, data -> {
            data.writeInt(output.inference().hypotheses().size());
            for (TraceHypothesis hypothesis : output.inference().hypotheses()) {
                ReplayOutputCanonical.writeHypothesisIdentityAndScore(data, hypothesis);
                ReplayOutputCanonical.writeHypothesisSupport(data, hypothesis);
                ReplayOutputCanonical.writeHypothesisDiagnostics(data, hypothesis);
            }
        }));
        result.put(Component.ROUTE_RAW_GEOMETRY, digest(Component.ROUTE_RAW_GEOMETRY, data -> {
            data.writeInt(output.routes().size());
            for (ModernTracePipeline.Route route : output.routes()) {
                ReplayOutputCanonical.writeHypothesisGeometry(data, route.rawHypothesis());
            }
        }));
        result.put(Component.ROUTE_RAW_SEMANTICS, digest(Component.ROUTE_RAW_SEMANTICS, data -> {
            data.writeInt(output.routes().size());
            for (ModernTracePipeline.Route route : output.routes()) {
                TraceHypothesis hypothesis = route.rawHypothesis();
                ReplayOutputCanonical.writeHypothesisIdentityAndScore(data, hypothesis);
                ReplayOutputCanonical.writeHypothesisSupport(data, hypothesis);
                ReplayOutputCanonical.writeHypothesisDiagnostics(data, hypothesis);
            }
        }));
        result.put(Component.ROUTE_FINAL_GEOMETRY, digest(Component.ROUTE_FINAL_GEOMETRY, data -> {
            data.writeInt(output.routes().size());
            for (ModernTracePipeline.Route route : output.routes()) {
                ReplayOutputCanonical.writeHypothesisGeometry(data, route.hypothesis());
            }
        }));
        result.put(Component.ROUTE_FINAL_SEMANTICS, digest(Component.ROUTE_FINAL_SEMANTICS, data -> {
            data.writeInt(output.routes().size());
            for (ModernTracePipeline.Route route : output.routes()) {
                TraceHypothesis hypothesis = route.hypothesis();
                ReplayOutputCanonical.writeHypothesisIdentityAndScore(data, hypothesis);
                ReplayOutputCanonical.writeHypothesisSupport(data, hypothesis);
                ReplayOutputCanonical.writeHypothesisDiagnostics(data, hypothesis);
            }
        }));
        result.put(Component.ROUTE_ASSIGNMENT_GEOMETRY, digest(Component.ROUTE_ASSIGNMENT_GEOMETRY, data -> {
            data.writeInt(output.routes().size());
            for (ModernTracePipeline.Route route : output.routes()) {
                data.writeInt(route.pointIds().size());
                for (FinalRoutePointId id : route.pointIds()) {
                    ReplayOutputCanonical.writePoint(data, route.assignments().get(id));
                }
            }
        }));
        result.put(Component.ROUTE_PROVENANCE, digest(Component.ROUTE_PROVENANCE, data -> {
            data.writeInt(output.routes().size());
            for (ModernTracePipeline.Route route : output.routes()) {
                data.writeInt(route.pointIds().size());
                for (FinalRoutePointId id : route.pointIds()) {
                    ReplayOutputCanonical.writePointId(data, id);
                    writeString(data, route.sourceOwnership().get(id).name());
                }
            }
        }));
        result.put(Component.ROUTE_QUALITY, digest(Component.ROUTE_QUALITY, data -> {
            data.writeInt(output.routes().size());
            for (ModernTracePipeline.Route route : output.routes()) {
                ReplayOutputCanonical.writeQuality(data, route.quality());
            }
        }));
        result.put(Component.ROUTE_CLEANUP, digest(Component.ROUTE_CLEANUP, data -> {
            data.writeInt(output.routes().size());
            for (ModernTracePipeline.Route route : output.routes()) {
                writeString(data, route.cleanupStatus().name());
                data.writeBoolean(route.geometryChanged());
            }
        }));
        return Map.copyOf(result);
    }

    static String summaryJson(QualitySummary summary) {
        StringBuilder json = new StringBuilder("{\"schema\":1,\"routeCount\":")
                .append(summary.routeCount()).append(",\"directlySupportedRoutes\":")
                .append(summary.directlySupportedRoutes()).append(",\"routesWithFindings\":")
                .append(summary.routesWithFindings()).append(",\"findingCount\":")
                .append(summary.findingCount()).append(",\"findings\":{");
        boolean comma = false;
        for (Map.Entry<String, Integer> entry : new TreeMap<>(summary.findings()).entrySet()) {
            if (comma) json.append(',');
            comma = true;
            json.append(quote(entry.getKey())).append(':').append(entry.getValue());
        }
        json.append("},\"dispositions\":{");
        comma = false;
        for (Map.Entry<String, Integer> entry : new TreeMap<>(summary.dispositions()).entrySet()) {
            if (comma) json.append(',');
            comma = true;
            json.append(quote(entry.getKey())).append(':').append(entry.getValue());
        }
        json.append("},\"supportOwnership\":{");
        comma = false;
        for (Map.Entry<String, Integer> entry : new TreeMap<>(summary.supportOwnership()).entrySet()) {
            if (comma) json.append(',');
            comma = true;
            json.append(quote(entry.getKey())).append(':').append(entry.getValue());
        }
        return json.append("},\"imageCenterCostAvailability\":")
                .append(quote(summary.allImageCostsAvailable() ? "AVAILABLE" : "UNAVAILABLE"))
                .append(",\"unavailableImageCosts\":").append(summary.unavailableImageCosts())
                .append(",\"totalLengthMeters\":").append(number(summary.totalLengthMeters()))
                .append(",\"directSupportMeters\":").append(number(summary.directSupportMeters()))
                .append(",\"worstUnsupportedSpanMeters\":")
                .append(number(summary.worstUnsupportedSpanMeters())).append(",\"routeMetrics\":[")
                .append(routeMetricsJson(summary.routeMetrics())).append("]}\n").toString();
    }

    private static String routeMetricsJson(List<RouteQuality> metrics) {
        StringBuilder json = new StringBuilder();
        for (RouteQuality row : metrics) {
            if (!json.isEmpty()) json.append(',');
            json.append("{\"imageCenterCostAvailability\":")
                    .append(quote(row.meanImageCenterCost() == null ? "UNAVAILABLE" : "AVAILABLE"))
                    .append(",\"meanImageCenterCost\":")
                    .append(row.meanImageCenterCost() == null ? "null" : number(row.meanImageCenterCost()))
                    .append(",\"bendPreservingRoughness\":").append(number(row.bendPreservingRoughness())).append('}');
        }
        return json.toString();
    }

    static QualitySummary qualitySummary(Format15ReplayRunner.Result output) {
        Map<String, Integer> findings = new TreeMap<>();
        Map<String, Integer> dispositions = new TreeMap<>();
        Map<String, Integer> ownershipCounts = new TreeMap<>();
        int directRoutes = 0;
        int routesWithFindings = 0;
        int findingCount = 0;
        int unavailable = 0;
        double total = 0;
        double direct = 0;
        double worst = 0;
        List<RouteQuality> metrics = new ArrayList<>();
        for (ModernTracePipeline.Route route : output.routes()) {
            FinalGeometryEvaluator.Result quality = route.quality();
            dispositions.merge(quality.disposition().name(), 1, Math::addExact);
            route.sourceOwnership().values().forEach(owner ->
                    ownershipCounts.merge(owner.name(), 1, Math::addExact));
            if (quality.directlySupportedLengthMeters() > 0) directRoutes++;
            if (!quality.findings().isEmpty()) routesWithFindings++;
            findingCount = Math.addExact(findingCount, quality.findings().size());
            for (FinalGeometryEvaluator.Finding finding : quality.findings()) {
                findings.merge(finding.code().name() + ":" + finding.severity().name(), 1, Math::addExact);
            }
            if (quality.meanImageCenterCost() == Double.POSITIVE_INFINITY) unavailable++;
            metrics.add(new RouteQuality(quality.meanImageCenterCost() == Double.POSITIVE_INFINITY
                    ? null : quality.meanImageCenterCost(), quality.bendPreservingRoughness()));
            total = finiteSum(total, quality.totalLengthMeters());
            direct = finiteSum(direct, quality.directlySupportedLengthMeters());
            worst = Math.max(worst, quality.worstUnsupportedSpanMeters());
        }
        return new QualitySummary(output.routes().size(), directRoutes, routesWithFindings,
                findingCount, findings, dispositions, ownershipCounts,
                unavailable == 0, unavailable, total, direct, worst, metrics);
    }

    static void validateOutputMetadata(Format15ReplayRunner.Result output) {
        validateOutputMetadata(output.inference(), output.routes());
    }

    static void validateOutputMetadata(TraceHypothesisSet inference, List<ModernTracePipeline.Route> routes) {
        safe(inference.explanation());
        for (TraceHypothesis hypothesis : inference.hypotheses()) {
            safe(hypothesis.id());
            safe(hypothesis.branchSignature());
            validateDiagnostics(hypothesis);
        }
        for (ModernTracePipeline.Route route : routes) {
            safe(route.rawHypothesis().id());
            safe(route.rawHypothesis().branchSignature());
            validateDiagnostics(route.rawHypothesis());
            safe(route.hypothesis().id());
            safe(route.hypothesis().branchSignature());
            validateDiagnostics(route.hypothesis());
            FinalGeometryEvaluator.Result quality = route.quality();
            safe(quality.id());
            if (quality.disposition() == null || !Double.isFinite(quality.totalLengthMeters())
                    || !Double.isFinite(quality.directlySupportedLengthMeters())
                    || !Double.isFinite(quality.worstUnsupportedSpanMeters())
                    || !Double.isFinite(quality.bendPreservingRoughness())) {
                throw new IllegalArgumentException("final-output-invalid");
            }
            for (FinalGeometryEvaluator.Finding finding : quality.findings()) {
                if (finding.code() == null || finding.severity() == null
                        || !Double.isFinite(finding.amplitudeMeters())) {
                    throw new IllegalArgumentException("final-output-invalid");
                }
            }
            for (FinalRoutePointId pointId : route.pointIds()) {
                if (pointId instanceof GeneratedCandidatePoint generated) safe(generated.candidateId());
            }
        }
    }

    private static void validateDiagnostics(TraceHypothesis hypothesis) {
        for (var entry : hypothesis.diagnostics().entrySet()) {
            safe(entry.getKey());
            if (entry.getKey().isBlank() || !Double.isFinite(entry.getValue())) {
                throw new IllegalArgumentException("scalar-output-invalid");
            }
        }
    }

    private static void safe(String value) {
        Format15Safety.requireSafeExportedMetadata(value);
    }

    /** Complete wire admission before any point/list/map/model materialization. */
    static Admitted preflight(byte[] sealed, int offset, int length, Binding expected,
            ReplayOutputAdmission.Budget budget) throws IOException {
        if (sealed == null || expected == null || offset < 0 || length < 0
                || length > Format15Safety.MAX_ARTIFACT_BYTES
                || (long) offset + length > sealed.length) {
            throw new IllegalArgumentException("snapshot-range-invalid");
        }
        budget.result();
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(sealed, offset, length))) {
            if (data.readInt() != MAGIC || data.readInt() != VERSION) throw new IllegalArgumentException("header");
            Binding binding = new Binding(readString(data, budget), readHash(data), readHash(data),
                    enumValue(TrackerMode.class, readString(data, budget)),
                    enumValue(TrackerMode.class, readString(data, budget)),
                    enumValue(ReplayLevel.class, readString(data, budget)), data.readInt(), readHash(data));
            if (!sameNonRootBinding(binding, expected)) throw new IllegalArgumentException("binding");
            if (!binding.rootFingerprint().equals(expected.rootFingerprint())) {
                throw new ReplayMismatchException("final-output-mismatch");
            }
            if (enumValue(ReplayLevel.class, readString(data, budget)) != binding.replayLevel()
                    || enumValue(TrackerMode.class, readString(data, budget)) != binding.capturedEngine()
                    || enumValue(TrackerMode.class, readString(data, budget)) != binding.requestedEngine()
                    || !readHash(data).equals(binding.inputHash())) {
                throw new IllegalArgumentException("output-binding");
            }
            enumValue(TrackerMode.class, readString(data, budget));
            enumValue(TraceHypothesisSet.Status.class, readString(data, budget));
            data.readBoolean();
            if (data.readLong() < 0 || data.readLong() < 0) throw new IllegalArgumentException("inference-count");
            readString(data, budget);
            int hypotheses = readCount(data, MAX_HYPOTHESES, "hypothesis-count");
            for (int index = 0; index < hypotheses; index++) scanHypothesis(data, budget);
            int routes = readCount(data, MAX_ROUTES, "route-count");
            for (int index = 0; index < routes; index++) scanRoute(data, budget);
            int digests = readCount(data, Component.values().length, "digest-count");
            if (digests != Component.values().length) throw new IllegalArgumentException("digest-count");
            var seen = java.util.EnumSet.noneOf(Component.class);
            for (int index = 0; index < digests; index++) {
                if (!seen.add(enumValue(Component.class, readString(data, budget)))) {
                    throw new IllegalArgumentException("duplicate-component");
                }
                readHash(data);
            }
            if (data.read() != -1) throw new IllegalArgumentException("trailing");
        }
        return new Admitted(sealed, offset, length, expected);
    }

    private static String scanHypothesis(DataInputStream data, ReplayOutputAdmission.Budget budget)
            throws IOException {
        budget.hypothesis(); // One root item, including for raw/final route hypotheses.
        String id = readString(data, budget);
        readString(data, budget);
        readFiniteDouble(data);
        if (data.readBoolean()) {
            double probability = readFiniteDouble(data);
            if (probability < 0 || probability > 1) throw new IllegalArgumentException("posterior");
        }
        int points = readCount(data, MAX_ITEMS, "point-count");
        budget.points(points);
        if (points < 2) throw new IllegalArgumentException("point-count");
        for (int index = 0; index < points; index++) { readFiniteDouble(data); readFiniteDouble(data); }
        int support = readCount(data, MAX_ITEMS, "support-count");
        budget.support(support);
        if (support != points) throw new IllegalArgumentException("support-alignment");
        for (int index = 0; index < support; index++) {
            enumValue(ObservationOwnership.class, readString(data, budget));
        }
        int diagnostics = readCount(data, MAX_ITEMS, "diagnostic-count");
        budget.diagnostics(diagnostics);
        for (int index = 0; index < diagnostics; index++) { readString(data, budget); readFiniteDouble(data); }
        return id;
    }

    private static void scanRoute(DataInputStream data, ReplayOutputAdmission.Budget budget)
            throws IOException {
        budget.route();
        String rawId = scanHypothesis(data, budget);
        String finalId = scanHypothesis(data, budget);
        if (!rawId.equals(finalId)) throw new IllegalArgumentException("route-identity");
        int rows = readCount(data, MAX_ITEMS, "route-point-count");
        budget.rows(rows); // Ordered IDs + assignments + ownership, exactly three root items.
        for (int index = 0; index < rows; index++) {
            int type = data.readUnsignedByte();
            if (type == 0) {
                for (int key = 0; key < 2; key++) {
                    enumValue(PrimitiveKey.Type.class, readString(data, budget));
                    enumValue(PrimitiveKey.IdentityKind.class, readString(data, budget));
                    data.readLong();
                }
            } else if (type == 1) readString(data, budget);
            else throw new IllegalArgumentException("point-id-type");
            if (data.readInt() < 0) throw new IllegalArgumentException("point-id-index");
            readFiniteDouble(data); readFiniteDouble(data);
            enumValue(ObservationOwnership.class, readString(data, budget));
        }
        if (!readString(data, budget).equals(finalId)) throw new IllegalArgumentException("quality-identity");
        enumValue(FinalGeometryEvaluator.Disposition.class, readString(data, budget));
        int findings = readCount(data, MAX_ITEMS, "finding-count");
        budget.findings(findings);
        boolean unavailable = false;
        for (int index = 0; index < findings; index++) {
            unavailable |= enumValue(FinalGeometryEvaluator.FindingCode.class, readString(data, budget))
                    == FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY;
            enumValue(FinalGeometryEvaluator.Severity.class, readString(data, budget));
            data.readInt(); data.readInt(); readFiniteDouble(data);
        }
        readFiniteDouble(data); readFiniteDouble(data); readFiniteDouble(data);
        double cost = Double.longBitsToDouble(data.readLong());
        if (unavailable != (cost == Double.POSITIVE_INFINITY) || !unavailable && (!Double.isFinite(cost) || cost < 0)) {
            throw new IllegalArgumentException("image-cost");
        }
        readFiniteDouble(data);
        enumValue(ImageSupportedLocalCleanup.Status.class, readString(data, budget));
        data.readBoolean();
    }

    private static void writeBinding(DataOutputStream data, Binding binding) throws IOException {
        writeString(data, binding.buildIdentity());
        writeHash(data, binding.inputHash());
        writeHash(data, binding.parameterHash());
        writeString(data, binding.capturedEngine().name());
        writeString(data, binding.requestedEngine().name());
        writeString(data, binding.replayLevel().name());
        data.writeInt(binding.fingerprintSchema());
        writeHash(data, binding.rootFingerprint());
    }

    private static boolean sameNonRootBinding(Binding actual, Binding expected) {
        return actual.buildIdentity().equals(expected.buildIdentity())
                && actual.inputHash().equals(expected.inputHash())
                && actual.parameterHash().equals(expected.parameterHash())
                && actual.capturedEngine() == expected.capturedEngine()
                && actual.requestedEngine() == expected.requestedEngine()
                && actual.replayLevel() == expected.replayLevel()
                && actual.fingerprintSchema() == expected.fingerprintSchema();
    }

    private static Binding readBinding(DataInputStream data) throws IOException {
        return new Binding(readString(data), readHash(data), readHash(data),
                enumValue(TrackerMode.class, readString(data)), enumValue(TrackerMode.class, readString(data)),
                enumValue(ReplayLevel.class, readString(data)), data.readInt(), readHash(data));
    }

    private static void writeOutput(DataOutputStream data, Format15ReplayRunner.Result output)
            throws IOException {
        writeString(data, output.level().name());
        writeString(data, output.capturedEngine().name());
        writeString(data, output.engine().name());
        writeHash(data, output.inputHash());
        TraceHypothesisSet inference = output.inference();
        writeString(data, inference.engine().name());
        writeString(data, inference.status().name());
        data.writeBoolean(inference.alternativesTruncated());
        data.writeLong(inference.evaluatedStates());
        data.writeLong(inference.evaluatedTransitions());
        writeString(data, inference.explanation());
        writeHypotheses(data, inference.hypotheses());
        data.writeInt(output.routes().size());
        for (ModernTracePipeline.Route route : output.routes()) writeRoute(data, route);
    }

    private static Format15ReplayRunner.Result readOutput(DataInputStream data) throws IOException {
        ReplayLevel level = enumValue(ReplayLevel.class, readString(data));
        TrackerMode captured = enumValue(TrackerMode.class, readString(data));
        TrackerMode engine = enumValue(TrackerMode.class, readString(data));
        String inputHash = readHash(data);
        TrackerMode inferenceEngine = enumValue(TrackerMode.class, readString(data));
        TraceHypothesisSet.Status status = enumValue(TraceHypothesisSet.Status.class, readString(data));
        boolean truncated = data.readBoolean();
        long states = data.readLong();
        long transitions = data.readLong();
        String explanation = readString(data);
        List<TraceHypothesis> hypotheses = readHypotheses(data);
        TraceHypothesisSet inference = new TraceHypothesisSet(inferenceEngine, hypotheses,
                status, truncated, states, transitions, explanation);
        int routeCount = readCount(data, MAX_ROUTES, "route-count");
        List<ModernTracePipeline.Route> routes = new ArrayList<>(routeCount);
        for (int index = 0; index < routeCount; index++) routes.add(readRoute(data));
        return new Format15ReplayRunner.Result(level, captured, engine, inference, routes, inputHash);
    }

    private static void writeHypotheses(DataOutputStream data, List<TraceHypothesis> hypotheses)
            throws IOException {
        data.writeInt(hypotheses.size());
        for (TraceHypothesis hypothesis : hypotheses) writeHypothesis(data, hypothesis);
    }

    private static List<TraceHypothesis> readHypotheses(DataInputStream data)
            throws IOException {
        int count = readCount(data, MAX_HYPOTHESES, "hypothesis-count");
        List<TraceHypothesis> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) result.add(readHypothesis(data));
        return List.copyOf(result);
    }

    private static void writeHypothesis(DataOutputStream data, TraceHypothesis hypothesis)
            throws IOException {
        writeString(data, hypothesis.id());
        writeString(data, hypothesis.branchSignature());
        ReplayOutputCanonical.writeFiniteDouble(data, hypothesis.objective());
        data.writeBoolean(hypothesis.posteriorProbability().isPresent());
        if (hypothesis.posteriorProbability().isPresent()) {
            ReplayOutputCanonical.writeFiniteDouble(data, hypothesis.posteriorProbability().getAsDouble());
        }
        data.writeInt(hypothesis.points().size());
        for (MetricPoint point : hypothesis.points()) ReplayOutputCanonical.writePoint(data, point);
        data.writeInt(hypothesis.support().size());
        for (ObservationOwnership ownership : hypothesis.support()) writeString(data, ownership.name());
        Map<String, Double> diagnostics = new TreeMap<>(hypothesis.diagnostics());
        data.writeInt(diagnostics.size());
        for (Map.Entry<String, Double> entry : diagnostics.entrySet()) {
            writeString(data, entry.getKey());
            ReplayOutputCanonical.writeFiniteDouble(data, entry.getValue());
        }
    }

    private static TraceHypothesis readHypothesis(DataInputStream data)
            throws IOException {
        String id = readString(data);
        String branch = readString(data);
        double objective = readFiniteDouble(data);
        OptionalDouble posterior = data.readBoolean()
                ? OptionalDouble.of(readFiniteDouble(data)) : OptionalDouble.empty();
        int pointCount = readCount(data, MAX_ITEMS, "point-count");
        List<MetricPoint> points = new ArrayList<>(pointCount);
        for (int index = 0; index < pointCount; index++) {
            points.add(new MetricPoint(readFiniteDouble(data), readFiniteDouble(data)));
        }
        int supportCount = readCount(data, MAX_ITEMS, "support-count");
        List<ObservationOwnership> support = new ArrayList<>(supportCount);
        for (int index = 0; index < supportCount; index++) {
            support.add(enumValue(ObservationOwnership.class, readString(data)));
        }
        int diagnosticCount = readCount(data, MAX_ITEMS, "diagnostic-count");
        Map<String, Double> diagnostics = new LinkedHashMap<>();
        for (int index = 0; index < diagnosticCount; index++) {
            if (diagnostics.put(readString(data), readFiniteDouble(data)) != null) {
                throw new IllegalArgumentException("duplicate-diagnostic");
            }
        }
        return new TraceHypothesis(id, branch, points, support, objective, posterior, diagnostics);
    }

    private static void writeRoute(DataOutputStream data, ModernTracePipeline.Route route)
            throws IOException {
        writeHypothesis(data, route.rawHypothesis());
        writeHypothesis(data, route.hypothesis());
        data.writeInt(route.pointIds().size());
        for (FinalRoutePointId id : route.pointIds()) {
            ReplayOutputCanonical.writePointId(data, id);
            ReplayOutputCanonical.writePoint(data, route.assignments().get(id));
            writeString(data, route.sourceOwnership().get(id).name());
        }
        FinalGeometryEvaluator.Result quality = route.quality();
        writeString(data, quality.id());
        writeString(data, quality.disposition().name());
        data.writeInt(quality.findings().size());
        for (FinalGeometryEvaluator.Finding finding : quality.findings()) {
            writeString(data, finding.code().name());
            writeString(data, finding.severity().name());
            data.writeInt(finding.firstVertex());
            data.writeInt(finding.lastVertex());
            ReplayOutputCanonical.writeFiniteDouble(data, finding.amplitudeMeters());
        }
        ReplayOutputCanonical.writeFiniteDouble(data, quality.totalLengthMeters());
        ReplayOutputCanonical.writeFiniteDouble(data, quality.directlySupportedLengthMeters());
        ReplayOutputCanonical.writeFiniteDouble(data, quality.worstUnsupportedSpanMeters());
        data.writeLong(Double.doubleToLongBits(quality.meanImageCenterCost()));
        ReplayOutputCanonical.writeFiniteDouble(data, quality.bendPreservingRoughness());
        writeString(data, route.cleanupStatus().name());
        data.writeBoolean(route.geometryChanged());
    }

    private static ModernTracePipeline.Route readRoute(DataInputStream data)
            throws IOException {
        TraceHypothesis raw = readHypothesis(data);
        TraceHypothesis hypothesis = readHypothesis(data);
        int pointCount = readCount(data, MAX_ITEMS, "route-point-count");
        List<FinalRoutePointId> ids = new ArrayList<>(pointCount);
        Map<FinalRoutePointId, MetricPoint> assignments = new LinkedHashMap<>();
        Map<FinalRoutePointId, ObservationOwnership> ownership = new LinkedHashMap<>();
        for (int index = 0; index < pointCount; index++) {
            FinalRoutePointId id = readPointId(data);
            MetricPoint point = new MetricPoint(readFiniteDouble(data), readFiniteDouble(data));
            ObservationOwnership owner = enumValue(ObservationOwnership.class, readString(data));
            ids.add(id);
            assignments.put(id, point);
            ownership.put(id, owner);
        }
        String qualityId = readString(data);
        FinalGeometryEvaluator.Disposition disposition = enumValue(
                FinalGeometryEvaluator.Disposition.class, readString(data));
        int findingCount = readCount(data, MAX_ITEMS, "finding-count");
        List<FinalGeometryEvaluator.Finding> findings = new ArrayList<>(findingCount);
        for (int index = 0; index < findingCount; index++) {
            findings.add(new FinalGeometryEvaluator.Finding(
                    enumValue(FinalGeometryEvaluator.FindingCode.class, readString(data)),
                    enumValue(FinalGeometryEvaluator.Severity.class, readString(data)),
                    data.readInt(), data.readInt(), readFiniteDouble(data)));
        }
        double total = readFiniteDouble(data);
        double direct = readFiniteDouble(data);
        double worst = readFiniteDouble(data);
        double imageCost = Double.longBitsToDouble(data.readLong());
        if (imageCost != Double.POSITIVE_INFINITY && !Double.isFinite(imageCost)) {
            throw new IllegalArgumentException("image-cost");
        }
        double roughness = readFiniteDouble(data);
        FinalGeometryEvaluator.Result quality = new FinalGeometryEvaluator.Result(qualityId,
                disposition, findings, total, direct, worst, imageCost, roughness);
        ImageSupportedLocalCleanup.Status cleanup = enumValue(
                ImageSupportedLocalCleanup.Status.class, readString(data));
        boolean changed = data.readBoolean();
        return new ModernTracePipeline.Route(raw, hypothesis, ids, assignments, ownership,
                quality, cleanup, changed);
    }

    private static FinalRoutePointId readPointId(DataInputStream data) throws IOException {
        int type = data.readUnsignedByte();
        if (type == 0) {
            PrimitiveKey way = readPrimitiveKey(data);
            PrimitiveKey node = readPrimitiveKey(data);
            return new ExistingWayNodeOccurrence(way, node, data.readInt());
        }
        if (type == 1) return new GeneratedCandidatePoint(readString(data), data.readInt());
        throw new IllegalArgumentException("point-id-type");
    }

    private static PrimitiveKey readPrimitiveKey(DataInputStream data) throws IOException {
        return new PrimitiveKey(enumValue(PrimitiveKey.Type.class, readString(data)),
                enumValue(PrimitiveKey.IdentityKind.class, readString(data)), data.readLong());
    }

    private static String digest(Component component, Writer writer) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream data = new DataOutputStream(new DigestOutputStream(
                    OutputStream.nullOutputStream(), digest))) {
                data.writeInt(VERSION);
                writeString(data, component.name());
                writer.write(data);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        } catch (IOException failure) {
            throw new IllegalStateException("final-components-digest-failed", failure);
        }
    }

    private static void writeHash(DataOutputStream data, String hash) throws IOException {
        Format15Safety.requiredHash(hash, "componentHash");
        data.write(HexFormat.of().parseHex(hash));
    }

    private static String readHash(DataInputStream data) throws IOException {
        byte[] bytes = data.readNBytes(32);
        if (bytes.length != 32) throw new IllegalArgumentException("hash-truncated");
        return HexFormat.of().formatHex(bytes);
    }

    private static void writeString(DataOutputStream data, String value) throws IOException {
        Format15Safety.requireSafeExportedMetadata(value);
        ScalarReplayFingerprint.writeString(data, value);
    }

    private static String readString(DataInputStream data) throws IOException {
        return readString(data, null);
    }

    private static String readString(DataInputStream data, ReplayOutputAdmission.Budget budget) throws IOException {
        int length = readCount(data, MAX_STRING_BYTES, "string-length");
        if (budget != null) budget.metadata(length); // Before UTF-8 buffer/decoder/string allocation.
        byte[] bytes = data.readNBytes(length);
        if (bytes.length != length) throw new IllegalArgumentException("string-truncated");
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            Format15Safety.requireSafeExportedMetadata(value);
            return value;
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("string-encoding");
        }
    }

    private static int readCount(DataInputStream data, int maximum, String code) throws IOException {
        int count = data.readInt();
        if (count < 0 || count > maximum) throw new IllegalArgumentException(code);
        return count;
    }

    private static double readFiniteDouble(DataInputStream data) throws IOException {
        double value = Double.longBitsToDouble(data.readLong());
        if (!Double.isFinite(value)) throw new IllegalArgumentException("nonfinite-double");
        return value;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return Enum.valueOf(type, value);
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("summary-nonfinite");
        return Double.toString(value);
    }

    private static double finiteSum(double current, double addition) {
        double result = current + addition;
        if (!Double.isFinite(result)) throw new IllegalArgumentException("summary-nonfinite");
        return result;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @FunctionalInterface private interface Writer { void write(DataOutputStream data) throws IOException; }

    static final class SnapshotBudgetExceeded extends IOException {
        SnapshotBudgetExceeded() { super("snapshot-budget"); }
    }

    private static final class BoundedOutputStream extends FilterOutputStream {
        private final int maximum;
        private int written;
        BoundedOutputStream(OutputStream delegate, int maximum) {
            super(delegate);
            this.maximum = maximum;
        }
        @Override public void write(int value) throws IOException {
            reserve(1);
            out.write(value);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            reserve(length);
            out.write(bytes, offset, length);
        }
        private void reserve(int length) throws SnapshotBudgetExceeded {
            if (length < 0 || (long) written + length > maximum) throw new SnapshotBudgetExceeded();
            written += length;
        }
    }
}
