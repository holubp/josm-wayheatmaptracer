package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageSupportedLocalCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;

/** Public repeated-value boundary witnesses; no engine run or captured private fixture. */
class V022OutputCompanionBudgetTest {
    @Test
    void sharedWirePreflightRejectsCumulativeItemsBeforeAnyResultIsMaterialized() throws Exception {
        TraceHypothesis hypothesis = new TraceHypothesis("public-shared", "public-branch",
                Collections.nCopies(125_000, new MetricPoint(0, 0)),
                Collections.nCopies(125_000, ObservationOwnership.NO_RASTER),
                1, OptionalDouble.empty(), Map.of("public-diagnostic", 1.0));
        var output = output(List.of(hypothesis), List.of(), "public-explanation");
        assertEquals(250_002, ReplayOutputAdmission.rootItems(output));
        var binding = binding(output);
        byte[] wire = FinalOutputComponentsCodec.encode(binding, output).bytes();
        var shared = new ReplayOutputAdmission.Budget(0);
        shared.payload(wire.length, 3);
        // Exactly the production interval preflight seam: each independently valid
        // snapshot shares admission, with no typed point/list/map model yet created.
        FinalOutputComponentsCodec.preflight(wire, 0, wire.length, binding, shared);
        assertEquals(250_002, shared.items());
        assertThrows(ReplayOutputAdmission.BudgetExceeded.class,
                () -> FinalOutputComponentsCodec.preflight(wire, 0, wire.length, binding, shared));
    }

    @Test
    void admissionChargesAllThreeRowCollectionsAndSimultaneousCopyPeak() {
        var output = smallOutput();
        var budget = new ReplayOutputAdmission.Budget(0);
        budget.output(output);
        // Each hypothesis: 1 + 2 points + 2 supports + 1 diagnostic = 6.
        // Inference + raw + final = 18; two rows own three collections = 6.
        assertEquals(24, budget.items());
        assertEquals(24, ReplayOutputAdmission.rootItems(output));
        var rows = new ReplayOutputAdmission.Budget(0);
        rows.route();
        assertEquals(0, rows.items());
        rows.rows(1);
        assertEquals(3, rows.items());
        assertThrows(ReplayOutputAdmission.BudgetExceeded.class, () -> rows.rows(166_666));
        var retained = new ReplayOutputAdmission.Budget(0);
        retained.payload(Format15Safety.MAX_ARTIFACT_BYTES, 3);
        retained.payload(Format15Safety.MAX_ARTIFACT_BYTES, 3);
        assertThrows(ReplayOutputAdmission.BudgetExceeded.class,
                () -> retained.payload(Format15Safety.MAX_ARTIFACT_BYTES, 2));
        assertEquals(FinalOutputComponentsCodec.Availability.UNAVAILABLE_BUDGET,
                FinalOutputComponentsCodec.encode(binding(output), output,
                        Format15Safety.MAX_ARTIFACT_BYTES, Format15Safety.MAX_TOTAL_BYTES - 1).availability());
    }

    @Test
    void remainingMandatoryBudgetIsAdmittedBeforeOptionalByteCopies() throws Exception {
        assertEquals(0, Format15ProductionBundleFactory.optionalPayloadAllowance(511, 0, 100));
        assertEquals(0, Format15ProductionBundleFactory.optionalPayloadAllowance(510,
                Format15Safety.MAX_TOTAL_BYTES - 100, 100));
        assertEquals(1, Format15ProductionBundleFactory.optionalPayloadAllowance(510,
                Format15Safety.MAX_TOTAL_BYTES - 101, 100));
        assertEquals(Format15Safety.MAX_ARTIFACT_BYTES,
                Format15ProductionBundleFactory.optionalPayloadAllowance(0, 0, 100));
        var output = smallOutput();
        int exactBytes = FinalOutputComponentsCodec.encode(binding(output), output).bytes().length;
        assertEquals(FinalOutputComponentsCodec.Availability.UNAVAILABLE_BUDGET,
                FinalOutputComponentsCodec.encode(binding(output), output, exactBytes - 1, 0).availability());
        assertEquals(FinalOutputComponentsCodec.Availability.AVAILABLE,
                FinalOutputComponentsCodec.encode(binding(output), output, exactBytes, 0).availability());

        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>();
        for (int index = 0; index < 511; index++) {
            String name = "mandatory-" + index + ".bin";
            artifacts.put(name, Format15Artifact.binary(name, new byte[0]));
        }
        addCompanion(artifacts, output);
        assertFalse(artifacts.containsKey(FinalOutputComponentsCodec.ARTIFACT));
        assertTrue(new String(artifacts.get("private/final-output-components-summary.json").bytes(),
                java.nio.charset.StandardCharsets.UTF_8)
                .contains("UNAVAILABLE_BUDGET"));
        assertEquals(512, new Format15Bundle("public-build", "a".repeat(64), "b".repeat(64), artifacts)
                .artifactNames().size());
    }

    @Test
    void streamedRequestIdentityPreservesExistingExactRequestEncoding() {
        var request = V022ProductionReplayTest.syntheticRidgeInput().request();
        assertEquals(Format15Safety.sha256(FrozenReplayCodec.encodeRequestOnly(request)),
                FinalOutputComponentsCodec.requestHash(request));
    }

    @Test
    void exactRootItemBoundaryRoundTripsWithoutDoubleChargingHypotheses() {
        // 1 hypothesis + 249999 point entries + 249999 support entries + 1 diagnostic.
        // Repeated immutable public values keep this exact wire oracle compact in memory.
        TraceHypothesis hypothesis = new TraceHypothesis("public-boundary", "public-branch",
                Collections.nCopies(249_999, new MetricPoint(0, 0)),
                Collections.nCopies(249_999, ObservationOwnership.DIRECT_TWO_SIDED),
                1, OptionalDouble.empty(), Map.of("public-diagnostic", 1.0));
        var output = output(List.of(hypothesis), List.of(), "public-explanation");
        var binding = binding(output);
        var encoded = FinalOutputComponentsCodec.encode(binding, output);
        assertEquals(FinalOutputComponentsCodec.Availability.AVAILABLE, encoded.availability());
        assertEquals(output, FinalOutputComponentsCodec.decode(encoded.bytes(), binding).output());
    }

    @Test
    void nonRootExplanationBudgetOmitsOnlyTheOptionalCompanion() {
        var ordinary = smallOutput();
        var oversized = output(ordinary.inference().hypotheses(), ordinary.routes(), "x".repeat(1_048_577));
        assertEquals(FinalReplayFingerprint.sha256(ordinary), FinalReplayFingerprint.sha256(oversized));
        var encoded = FinalOutputComponentsCodec.encode(binding(oversized), oversized);
        assertEquals(FinalOutputComponentsCodec.Availability.UNAVAILABLE_BUDGET, encoded.availability());
        assertEquals(null, encoded.bytes());
        var unsafe = output(ordinary.inference().hypotheses(), ordinary.routes(), "Cookie: synthetic-secret");
        assertThrows(IllegalArgumentException.class, () -> FinalOutputComponentsCodec.encode(binding(unsafe), unsafe));
    }

    @Test
    void readableMeasuredQualityRetainsValuesAndUnavailableNull() {
        var measured = smallOutput();
        String summary = FinalOutputComponentsCodec.summaryJson(FinalOutputComponentsCodec.qualitySummary(measured));
        assertTrue(summary.contains("\"meanImageCenterCost\":0.25"), summary);
        assertTrue(summary.contains("\"bendPreservingRoughness\":0.5"), summary);
        var route = measured.routes().get(0);
        var zero = withQuality(route, quality(0, 0, false));
        String availableZero = FinalOutputComponentsCodec.summaryJson(FinalOutputComponentsCodec.qualitySummary(
                output(measured.inference().hypotheses(), List.of(zero), "public-explanation")));
        assertTrue(availableZero.contains("\"meanImageCenterCost\":0.0"));
        assertFalse(availableZero.contains("\"imageCenterCostAvailability\":\"UNAVAILABLE\""));
        var unavailable = withQuality(route, quality(Double.POSITIVE_INFINITY, 0.75, true));
        String mixed = FinalOutputComponentsCodec.summaryJson(FinalOutputComponentsCodec.qualitySummary(
                output(measured.inference().hypotheses(), List.of(route, unavailable), "public-explanation")));
        assertTrue(mixed.contains("\"meanImageCenterCost\":0.25"));
        assertTrue(mixed.contains("\"meanImageCenterCost\":null"));
        assertTrue(mixed.contains("\"bendPreservingRoughness\":0.75"));
        assertFalse(mixed.contains("Infinity"));
        assertFalse(mixed.contains("public-route"));
    }

    @Test
    void fullMandatoryArtifactInventoryStillExportsWithoutOptionalDetail() throws Exception {
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>();
        for (int index = 0; index < Format15Safety.MAX_ARTIFACTS; index++) {
            String name = "mandatory-" + index + ".bin";
            artifacts.put(name, Format15Artifact.binary(name, new byte[0]));
        }
        var output = smallOutput();
        addCompanion(artifacts, output);
        assertEquals(Format15Safety.MAX_ARTIFACTS, artifacts.size());
        assertFalse(artifacts.containsKey(FinalOutputComponentsCodec.ARTIFACT));
        assertEquals(Format15Safety.MAX_ARTIFACTS,
                new Format15Bundle("public-build", "a".repeat(64), "b".repeat(64), artifacts).artifactNames().size());
    }

    private static void addCompanion(Map<String, Format15Artifact> artifacts,
            Format15ReplayRunner.Result output) throws Exception {
        var constructor = FinalReplayExpectation.class.getDeclaredConstructor(String.class, String.class,
                String.class, TrackerMode.class, TrackerMode.class, String.class);
        constructor.setAccessible(true);
        var expectation = constructor.newInstance("public-build", output.inputHash(), "b".repeat(64),
                output.capturedEngine(), output.engine(), FinalReplayFingerprint.sha256(output));
        var method = Format15ProductionBundleFactory.class.getDeclaredMethod("addFinalOutputComponents",
                Map.class, FinalReplayExpectation.class, Format15ReplayRunner.Result.class);
        method.setAccessible(true);
        method.invoke(null, artifacts, expectation, output);
    }

    static Format15ReplayRunner.Result smallOutput() {
        List<MetricPoint> points = List.of(new MetricPoint(0, 0), new MetricPoint(1, 0));
        List<ObservationOwnership> support = List.of(ObservationOwnership.DIRECT_TWO_SIDED,
                ObservationOwnership.DIRECT_TWO_SIDED);
        TraceHypothesis hypothesis = new TraceHypothesis("public-route", "public-branch", points,
                support, 1, OptionalDouble.empty(), Map.of("public-diagnostic", 2.0));
        List<FinalRoutePointId> ids = List.of(new FinalRoutePointId.GeneratedCandidatePoint("public-route", 0),
                new FinalRoutePointId.GeneratedCandidatePoint("public-route", 1));
        var route = new ModernTracePipeline.Route(hypothesis, hypothesis, ids,
                Map.of(ids.get(0), points.get(0), ids.get(1), points.get(1)),
                Map.of(ids.get(0), support.get(0), ids.get(1), support.get(1)), quality(0.25, 0.5, false),
                ImageSupportedLocalCleanup.Status.SKIPPED, true);
        return output(List.of(hypothesis), List.of(route), "public-explanation");
    }

    static Format15ReplayRunner.Result output(List<TraceHypothesis> hypotheses,
            List<ModernTracePipeline.Route> routes, String explanation) {
        return new Format15ReplayRunner.Result(ReplayLevel.FINAL_GEOMETRY, TrackerMode.PROBABILISTIC,
                TrackerMode.PROBABILISTIC, new TraceHypothesisSet(TrackerMode.PROBABILISTIC, hypotheses,
                        TraceHypothesisSet.Status.COMPLETE, false, 1, 1, explanation), routes, "a".repeat(64));
    }

    static FinalOutputComponentsCodec.Binding binding(Format15ReplayRunner.Result output) {
        return new FinalOutputComponentsCodec.Binding("public-build", output.inputHash(), "b".repeat(64),
                output.capturedEngine(), output.engine(), output.level(), FinalReplayFingerprint.SCHEMA_VERSION,
                FinalReplayFingerprint.sha256(output));
    }

    private static FinalGeometryEvaluator.Result quality(double cost, double roughness, boolean unavailable) {
        return new FinalGeometryEvaluator.Result("public-route", unavailable
                ? FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED : FinalGeometryEvaluator.Disposition.APPLICABLE,
                unavailable ? List.of(new FinalGeometryEvaluator.Finding(
                        FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY,
                        FinalGeometryEvaluator.Severity.REVIEW, 0, 1, 0)) : List.of(), 1, 1, 0, cost, roughness);
    }

    private static ModernTracePipeline.Route withQuality(ModernTracePipeline.Route route,
            FinalGeometryEvaluator.Result quality) {
        return new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(), route.pointIds(),
                route.assignments(), route.sourceOwnership(), quality, route.cleanupStatus(), route.geometryChanged());
    }
}
