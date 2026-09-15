package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorTraceInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedProfileSamplingLocation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageSupportedLocalCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;

/** Production-path replay regressions: frozen values must reach real modern engines. */
class V022ProductionReplayTest {
    @Test
    void finalFingerprintPreservesCompleteOrderedRouteSemanticsAndCanonicalMaps() {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        Format15ReplayRunner.Result actual = Format15ReplayRunner.replay(input,
            ReplayLevel.FINAL_GEOMETRY, TrackerMode.CORRIDOR_AWARE);
        String baseline = FinalReplayFingerprint.sha256(actual);
        assertEquals(baseline, FinalReplayFingerprint.sha256(actual));
        ModernTracePipeline.Route route = actual.routes().get(0);

        TraceHypothesis rawChanged = copyHypothesis(route.rawHypothesis(),
            route.rawHypothesis().id(), route.rawHypothesis().branchSignature(),
            route.rawHypothesis().points(), route.rawHypothesis().support(),
            route.rawHypothesis().objective() + 1.0,
            route.rawHypothesis().posteriorProbability(), route.rawHypothesis().diagnostics());
        ModernTracePipeline.Route changedRaw = copyRoute(route, rawChanged, route.hypothesis(),
            route.pointIds(), route.assignments(), route.sourceOwnership(), route.quality(),
            route.cleanupStatus(), route.geometryChanged());
        assertNotEquals(baseline, finalFingerprint(withRoutes(actual, List.of(changedRaw))));

        int pointIndex = Math.min(1, route.hypothesis().points().size() - 1);
        List<MetricPoint> changedPoints = new java.util.ArrayList<>(route.hypothesis().points());
        MetricPoint point = changedPoints.get(pointIndex);
        changedPoints.set(pointIndex, new MetricPoint(point.xMeters(), point.yMeters() + 0.125));
        TraceHypothesis changedFinalHypothesis = copyHypothesis(route.hypothesis(),
            route.hypothesis().id(), route.hypothesis().branchSignature(), changedPoints,
            route.hypothesis().support(), route.hypothesis().objective(),
            route.hypothesis().posteriorProbability(), route.hypothesis().diagnostics());
        Map<FinalRoutePointId, MetricPoint> changedAssignments =
            new LinkedHashMap<>(route.assignments());
        changedAssignments.put(route.pointIds().get(pointIndex), changedPoints.get(pointIndex));
        ModernTracePipeline.Route changedFinal = copyRoute(route, route.rawHypothesis(),
            changedFinalHypothesis, route.pointIds(), changedAssignments, route.sourceOwnership(),
            route.quality(), route.cleanupStatus(), route.geometryChanged());
        assertNotEquals(baseline, finalFingerprint(withRoutes(actual, List.of(changedFinal))));

        List<FinalRoutePointId> changedIds = new java.util.ArrayList<>(route.pointIds());
        FinalRoutePointId oldId = changedIds.get(0);
        FinalRoutePointId newId = new GeneratedCandidatePoint("changed-identity", 100_000);
        changedIds.set(0, newId);
        Map<FinalRoutePointId, MetricPoint> identityAssignments =
            remap(route.assignments(), oldId, newId);
        Map<FinalRoutePointId, ObservationOwnership> identityOwnership =
            remap(route.sourceOwnership(), oldId, newId);
        ModernTracePipeline.Route changedIdentity = copyRoute(route, route.rawHypothesis(),
            route.hypothesis(), changedIds, identityAssignments, identityOwnership,
            route.quality(), route.cleanupStatus(), route.geometryChanged());
        assertNotEquals(baseline, finalFingerprint(withRoutes(actual, List.of(changedIdentity))));

        Map<FinalRoutePointId, ObservationOwnership> changedOwnership =
            new LinkedHashMap<>(route.sourceOwnership());
        ObservationOwnership ownership = changedOwnership.get(route.pointIds().get(0));
        changedOwnership.put(route.pointIds().get(0),
            ownership == ObservationOwnership.DIRECT_TWO_SIDED
                ? ObservationOwnership.DIRECT_AMBIGUOUS
                : ObservationOwnership.DIRECT_TWO_SIDED);
        assertNotEquals(baseline, finalFingerprint(withRoutes(actual, List.of(copyRoute(route,
            route.rawHypothesis(), route.hypothesis(), route.pointIds(), route.assignments(),
            changedOwnership, route.quality(), route.cleanupStatus(), route.geometryChanged())))));

        FinalGeometryEvaluator.Result quality = route.quality();
        List<FinalGeometryEvaluator.Finding> findings = new java.util.ArrayList<>(
            quality.findings());
        findings.add(new FinalGeometryEvaluator.Finding(
            FinalGeometryEvaluator.FindingCode.SUPPORT_MISMATCH,
            FinalGeometryEvaluator.Severity.REVIEW, 0, 1, 0.25));
        FinalGeometryEvaluator.Result changedQuality = new FinalGeometryEvaluator.Result(
            quality.id(), FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED, findings,
            quality.totalLengthMeters(), quality.directlySupportedLengthMeters(),
            quality.worstUnsupportedSpanMeters(), quality.meanImageCenterCost(),
            quality.bendPreservingRoughness());
        assertNotEquals(baseline, finalFingerprint(withRoutes(actual, List.of(copyRoute(route,
            route.rawHypothesis(), route.hypothesis(), route.pointIds(), route.assignments(),
            route.sourceOwnership(), changedQuality, route.cleanupStatus(),
            route.geometryChanged())))));
        FinalGeometryEvaluator.Result changedMetric = new FinalGeometryEvaluator.Result(
            quality.id(), quality.disposition(), quality.findings(),
            quality.totalLengthMeters() + 0.125, quality.directlySupportedLengthMeters(),
            quality.worstUnsupportedSpanMeters(), quality.meanImageCenterCost(),
            quality.bendPreservingRoughness());
        assertNotEquals(baseline, finalFingerprint(withRoutes(actual, List.of(copyRoute(route,
            route.rawHypothesis(), route.hypothesis(), route.pointIds(), route.assignments(),
            route.sourceOwnership(), changedMetric, route.cleanupStatus(),
            route.geometryChanged())))));
        ImageSupportedLocalCleanup.Status changedCleanup = route.cleanupStatus()
                == ImageSupportedLocalCleanup.Status.SKIPPED
                    ? ImageSupportedLocalCleanup.Status.UNCHANGED
                    : ImageSupportedLocalCleanup.Status.SKIPPED;
        assertNotEquals(baseline, finalFingerprint(withRoutes(actual, List.of(copyRoute(route,
            route.rawHypothesis(), route.hypothesis(), route.pointIds(), route.assignments(),
            route.sourceOwnership(), route.quality(), changedCleanup,
            route.geometryChanged())))));
        assertNotEquals(baseline, finalFingerprint(withRoutes(actual, List.of(copyRoute(route,
            route.rawHypothesis(), route.hypothesis(), route.pointIds(), route.assignments(),
            route.sourceOwnership(), route.quality(), route.cleanupStatus(),
            !route.geometryChanged())))));

        Map<FinalRoutePointId, MetricPoint> reverseAssignments = reverse(route.assignments());
        Map<FinalRoutePointId, ObservationOwnership> reverseOwnership =
            reverse(route.sourceOwnership());
        ModernTracePipeline.Route reverseMaps = copyRoute(route, route.rawHypothesis(),
            route.hypothesis(), route.pointIds(), reverseAssignments, reverseOwnership,
            route.quality(), route.cleanupStatus(), route.geometryChanged());
        assertEquals(baseline, finalFingerprint(withRoutes(actual, List.of(reverseMaps))));
        assertNotEquals(finalFingerprint(withRoutes(actual, List.of(route, changedRaw))),
            finalFingerprint(withRoutes(actual, List.of(changedRaw, route))));
        assertThrows(IllegalArgumentException.class, () -> FinalReplayFingerprint.sha256(
            withRoutes(actual, Collections.nCopies(4_097, route))));
    }

    @Test
    void finalCliMatchesActualFactoryOutputWhileScalarOnlyRemainsUnavailable(
            @TempDir Path directory) throws Exception {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        Format15Bundle expected = Format15ProductionBundleFactory
            .createWithExpectedFinalOutput("test", input, TrackerMode.CORRIDOR_AWARE);
        CliRun matching = runCli(directory.resolve("matching-final"), expected, "A",
            51.0, 55.0, false, "{}", "FINAL_GEOMETRY",
            "[\"complete-edit-plan\",\"incident-relations\"]", "[]");
        assertEquals(0, matching.exit(), matching.output());
        assertTrue(expected.artifactNames().contains("expected-final-output.json"));
        assertTrue(matching.output().contains("\"fidelityStatus\":\"MATCH\""));
        assertTrue(matching.output().contains("\"qualityStatus\":\"PASS\""));

        Format15Bundle scalarOnly = Format15ProductionBundleFactory
            .createWithExpectedScalarOutput("test", input, TrackerMode.CORRIDOR_AWARE);
        CliRun unavailable = runCli(directory.resolve("scalar-only-final"), scalarOnly, "A",
            51.0, 55.0, false, "{}", "FINAL_GEOMETRY",
            "[\"complete-edit-plan\",\"incident-relations\"]", "[]");
        assertEquals(0, unavailable.exit(), unavailable.output());
        assertTrue(unavailable.output().contains(
            "\"fidelityStatus\":\"UNAVAILABLE\""));
    }

    @Test
    void matchingNegativeFinalOutputStillFailsPositiveQuality(@TempDir Path directory)
            throws Exception {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.NO_SIGNAL);
        Format15Bundle expected = Format15ProductionBundleFactory
            .createWithExpectedFinalOutput("test", input, TrackerMode.CORRIDOR_AWARE);
        CliRun run = runCli(directory, expected, "A", 0.0, 0.0, false, "{}",
            "FINAL_GEOMETRY",
            "[\"complete-edit-plan\",\"incident-relations\"]", "[]");
        assertEquals(2, run.exit());
        assertTrue(run.output().contains("\"fidelityStatus\":\"MATCH\""));
        assertTrue(run.output().contains("\"qualityStatus\":\"FAIL\""));
        assertTrue(run.output().contains("\"actualStatus\":\"NO_ROUTE\""));
    }

    @Test
    void finalExpectationIsEngineScopedAndInvalidArtifactsFail(@TempDir Path directory)
            throws Exception {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        assertThrows(IllegalArgumentException.class, () -> Format15ProductionBundleFactory
            .createWithExpectedFinalOutput("b".repeat(16_384), input,
                TrackerMode.CORRIDOR_AWARE));
        Format15Bundle expected = Format15ProductionBundleFactory
            .createWithExpectedFinalOutput("test", input, TrackerMode.CORRIDOR_AWARE);
        CliRun otherEngine = runCli(directory.resolve("other-final-engine"), expected, "B",
            51.0, 55.0, false, "{}", "FINAL_GEOMETRY",
            "[\"complete-edit-plan\",\"incident-relations\"]", "[]");
        assertEquals(0, otherEngine.exit(), otherEngine.output());
        assertTrue(otherEngine.output().contains(
            "\"fidelityStatus\":\"UNAVAILABLE\""));

        String valid = new String(expected.artifact("expected-final-output.json").bytes(),
            StandardCharsets.UTF_8);
        Format15Bundle invalid = withFinalExpectedArtifact(expected, valid.replace(
            "wayheatmaptracer-final-output-expectation-1",
            "wayheatmaptracer-final-output-expectation-2"));
        CliRun malformed = runCli(directory.resolve("invalid-final"), invalid, "B",
            51.0, 55.0, false, "{}", "FINAL_GEOMETRY",
            "[\"complete-edit-plan\",\"incident-relations\"]", "[]");
        assertEquals(2, malformed.exit(), malformed.output());
        assertTrue(malformed.output().contains("\"fidelityStatus\":\"MISMATCH\""));

        String changed = valid.replaceFirst(
            "(\"fingerprintSha256\":\")[0-9a-f]{64}", "$1" + "2".repeat(64));
        CliRun mismatch = runCli(directory.resolve("changed-final"),
            withFinalExpectedArtifact(expected, changed), "A", 51.0, 55.0, false, "{}",
            "FINAL_GEOMETRY",
            "[\"complete-edit-plan\",\"incident-relations\"]", "[]");
        assertEquals(2, mismatch.exit(), mismatch.output());
        assertTrue(mismatch.output().contains(
            "\"reason\":\"final-output-mismatch\""));

        Format15Bundle scalar = Format15ProductionBundleFactory
            .createWithExpectedScalarOutput("test", input, TrackerMode.CORRIDOR_AWARE);
        String scalarJson = new String(scalar.artifact("expected-scalar-output.json").bytes(),
            StandardCharsets.UTF_8);
        List<String> invalidScalar = List.of(
            scalarJson.replace("wayheatmaptracer-scalar-output-expectation-1",
                "wayheatmaptracer-scalar-output-expectation-2"),
            scalarJson.replace(expected.sourceIdentityHash(), "0".repeat(64)));
        for (int index = 0; index < invalidScalar.size(); index++) {
            CliRun unusedInvalid = runCli(directory.resolve("invalid-unused-scalar-" + index),
                withExpectedArtifact(expected, invalidScalar.get(index)), "A", 51.0, 55.0,
                false, "{}", "FINAL_GEOMETRY",
                "[\"complete-edit-plan\",\"incident-relations\"]", "[]");
            assertEquals(2, unusedInvalid.exit(), unusedInvalid.output());
            assertTrue(unusedInvalid.output().contains(
                "\"fidelityStatus\":\"MISMATCH\""));
        }
    }

    @Test
    void scalarFingerprintIsStableAndDiscriminatesSemanticOutput() {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        TraceHypothesisSet output = Format15ReplayRunner.replay(input,
            ReplayLevel.SCALAR_INFERENCE, TrackerMode.CORRIDOR_AWARE).inference();
        assertEquals(ScalarReplayFingerprint.sha256(output),
            ScalarReplayFingerprint.sha256(output));

        TraceHypothesis original = output.hypotheses().get(0);
        Map<String, Double> forward = new LinkedHashMap<>(original.diagnostics());
        forward.put("aSynthetic", 1.0);
        forward.put("zSynthetic", 2.0);
        Map<String, Double> reverse = new LinkedHashMap<>(original.diagnostics());
        reverse.put("zSynthetic", 2.0);
        reverse.put("aSynthetic", 1.0);
        TraceHypothesis ordered = copyHypothesis(original, original.id(),
            original.branchSignature(), original.points(), original.support(), original.objective(),
            original.posteriorProbability(), forward);
        TraceHypothesis reverseOrdered = copyHypothesis(original, original.id(),
            original.branchSignature(), original.points(), original.support(), original.objective(),
            original.posteriorProbability(), reverse);
        assertEquals(fingerprint(withHypotheses(output, List.of(ordered))),
            fingerprint(withHypotheses(output, List.of(reverseOrdered))));

        List<MetricPoint> movedPoints = new java.util.ArrayList<>(original.points());
        MetricPoint moved = movedPoints.get(1);
        movedPoints.set(1, new MetricPoint(moved.xMeters(), moved.yMeters() + 0.25));
        assertNotEquals(fingerprint(output), fingerprint(withHypotheses(output, List.of(
            copyHypothesis(original, original.id(), original.branchSignature(), movedPoints,
                original.support(), original.objective(), original.posteriorProbability(),
                original.diagnostics())))));
        List<ObservationOwnership> changedSupport = new java.util.ArrayList<>(original.support());
        changedSupport.set(0, changedSupport.get(0) == ObservationOwnership.DIRECT_TWO_SIDED
            ? ObservationOwnership.DIRECT_AMBIGUOUS : ObservationOwnership.DIRECT_TWO_SIDED);
        assertNotEquals(fingerprint(output), fingerprint(withHypotheses(output, List.of(
            copyHypothesis(original, original.id(), original.branchSignature(), original.points(),
                changedSupport, original.objective(), original.posteriorProbability(),
                original.diagnostics())))));
        assertNotEquals(fingerprint(output), fingerprint(withHypotheses(output, List.of(
            copyHypothesis(original, original.id(), original.branchSignature() + "-changed",
                original.points(), original.support(), original.objective(),
                original.posteriorProbability(), original.diagnostics())))));
        assertNotEquals(fingerprint(output), fingerprint(withHypotheses(output, List.of(
            copyHypothesis(original, original.id(), original.branchSignature(), original.points(),
                original.support(), original.objective() + 1.0,
                original.posteriorProbability(), original.diagnostics())))));
        assertNotEquals(fingerprint(output), fingerprint(new TraceHypothesisSet(output.engine(),
            output.hypotheses(), TraceHypothesisSet.Status.AMBIGUOUS,
            output.alternativesTruncated(), output.evaluatedStates(),
            output.evaluatedTransitions(), output.explanation())));
        assertNotEquals(fingerprint(output), fingerprint(new TraceHypothesisSet(output.engine(),
            output.hypotheses(), output.status(), output.alternativesTruncated(),
            output.evaluatedStates() + 1, output.evaluatedTransitions(), output.explanation())));
        assertNotEquals(fingerprint(output), fingerprint(new TraceHypothesisSet(output.engine(),
            output.hypotheses(), output.status(), output.alternativesTruncated(),
            output.evaluatedStates(), output.evaluatedTransitions() + 1, output.explanation())));
        assertEquals(fingerprint(output), fingerprint(new TraceHypothesisSet(output.engine(),
            output.hypotheses(), output.status(), output.alternativesTruncated(),
            output.evaluatedStates(), output.evaluatedTransitions(), "different explanation")));

        Map<String, Double> changedDiagnostics = new LinkedHashMap<>(original.diagnostics());
        changedDiagnostics.put("syntheticSemanticMetric", 3.0);
        assertNotEquals(fingerprint(output), fingerprint(withHypotheses(output, List.of(
            copyHypothesis(original, original.id(), original.branchSignature(), original.points(),
                original.support(), original.objective(), original.posteriorProbability(),
                changedDiagnostics)))));
        TraceHypothesis second = copyHypothesis(original, original.id() + "-second",
            original.branchSignature() + "-second", original.points(), original.support(),
            original.objective(), OptionalDouble.empty(), original.diagnostics());
        assertNotEquals(fingerprint(withHypotheses(output, List.of(original, second))),
            fingerprint(withHypotheses(output, List.of(second, original))));

        TraceHypothesis absentPosterior = copyHypothesis(original, original.id(),
            original.branchSignature(), original.points(), original.support(), original.objective(),
            OptionalDouble.empty(), original.diagnostics());
        TraceHypothesis zeroPosterior = copyHypothesis(original, original.id(),
            original.branchSignature(), original.points(), original.support(), original.objective(),
            OptionalDouble.of(0.0), original.diagnostics());
        assertNotEquals(fingerprint(withHypotheses(output, List.of(absentPosterior))),
            fingerprint(withHypotheses(output, List.of(zeroPosterior))));
    }

    @Test
    void scalarCliSeparatesMatchingAndUnavailableFidelityFromQuality(
            @TempDir Path directory) throws Exception {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        Format15Bundle expected = Format15ProductionBundleFactory
            .createWithExpectedScalarOutput("test", input, TrackerMode.CORRIDOR_AWARE);
        CliRun matching = runCli(directory.resolve("matching"), expected, "A", 51.0, 55.0,
            false, "{}", "SCALAR_INFERENCE", "[]", "[]");
        assertEquals(0, matching.exit(), matching.output());
        assertTrue(expected.artifactNames().contains("expected-scalar-output.json"));
        assertTrue(matching.output().contains("\"fidelityStatus\":\"MATCH\""));
        assertTrue(matching.output().contains("\"qualityStatus\":\"PASS\""));

        CliRun unavailable = runCli(directory.resolve("unavailable"),
            Format15ProductionBundleFactory.create("test", input), "A", 51.0, 55.0,
            false, "{}", "SCALAR_INFERENCE", "[]", "[]");
        assertEquals(0, unavailable.exit(), unavailable.output());
        assertTrue(unavailable.output().contains(
            "\"fidelityStatus\":\"UNAVAILABLE\""));
        assertTrue(unavailable.output().contains("\"qualityStatus\":\"PASS\""));
    }

    @Test
    void matchingNegativeScalarOutputStillFailsPositiveQuality(@TempDir Path directory)
            throws Exception {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.NO_SIGNAL);
        Format15Bundle expected = Format15ProductionBundleFactory
            .createWithExpectedScalarOutput("test", input, TrackerMode.CORRIDOR_AWARE);
        CliRun run = runCli(directory, expected, "A", 0.0, 0.0, false, "{}",
            "SCALAR_INFERENCE", "[]", "[]");
        assertEquals(2, run.exit());
        assertTrue(run.output().contains("\"fidelityStatus\":\"MATCH\""));
        assertTrue(run.output().contains("\"qualityStatus\":\"FAIL\""));
        assertTrue(run.output().contains("\"actualStatus\":\"NO_ROUTE\""));
        assertTrue(run.output().contains("\"status\":\"failed\""));
    }

    @Test
    void scalarExpectationIsUnavailableForAnotherEngine(@TempDir Path directory)
            throws Exception {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        Format15Bundle expected = Format15ProductionBundleFactory
            .createWithExpectedScalarOutput("test", input, TrackerMode.CORRIDOR_AWARE);
        CliRun run = runCli(directory, expected, "B", 51.0, 55.0, false, "{}",
            "SCALAR_INFERENCE", "[]", "[]");
        assertEquals(0, run.exit(), run.output());
        assertTrue(run.output().contains("\"fidelityStatus\":\"UNAVAILABLE\""));
        assertTrue(run.output().contains("\"qualityStatus\":\"PASS\""));
    }

    @Test
    void invalidScalarExpectationFailsEvenForAnotherEngine(@TempDir Path directory)
            throws Exception {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        Format15Bundle expected = Format15ProductionBundleFactory
            .createWithExpectedScalarOutput("test", input, TrackerMode.CORRIDOR_AWARE);
        String valid = new String(expected.artifact("expected-scalar-output.json").bytes(),
            StandardCharsets.UTF_8);
        List<String> invalid = List.of(
            valid.replace("wayheatmaptracer-scalar-output-expectation-1",
                "wayheatmaptracer-scalar-output-expectation-2"),
            valid.replace(expected.sourceIdentityHash(), "0".repeat(64)),
            valid.replace(expected.parameterHash(), "1".repeat(64)),
            valid.replace("\"buildIdentity\":\"test\"",
                "\"buildIdentity\":\"different-build\""),
            "x".repeat(20_000));
        for (int index = 0; index < invalid.size(); index++) {
            CliRun run = runCli(directory.resolve("invalid-" + index),
                withExpectedArtifact(expected, invalid.get(index)), "B", 51.0, 55.0,
                false, "{}", "SCALAR_INFERENCE", "[]", "[]");
            assertEquals(2, run.exit(), run.output());
            assertTrue(run.output().contains("\"fidelityStatus\":\"MISMATCH\""),
                run.output());
            assertTrue(run.output().contains("\"status\":\"failed\""),
                run.output());
        }

        String changedFingerprint = valid.replaceFirst(
            "(\"fingerprintSha256\":\")[0-9a-f]{64}", "$1" + "2".repeat(64));
        CliRun changed = runCli(directory.resolve("changed-output"),
            withExpectedArtifact(expected, changedFingerprint), "A", 51.0, 55.0,
            false, "{}", "SCALAR_INFERENCE", "[]", "[]");
        assertEquals(2, changed.exit(), changed.output());
        assertTrue(changed.output().contains("\"fidelityStatus\":\"MISMATCH\""));
        assertTrue(changed.output().contains(
            "\"reason\":\"scalar-output-mismatch\""));
    }

    @Test
    void nonExecutableExpectedEngineFieldsFailStrictAdmission(@TempDir Path directory)
            throws Exception {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        Format15Bundle expected = Format15ProductionBundleFactory
            .createWithExpectedScalarOutput("test", input, TrackerMode.CORRIDOR_AWARE);
        String valid = new String(expected.artifact("expected-scalar-output.json").bytes(),
            StandardCharsets.UTF_8);
        List<String> invalid = List.of(
            valid.replace("\"requestedEngine\":\"CORRIDOR_AWARE\"",
                "\"requestedEngine\":\"LEGACY_V02\""),
            valid.replace("\"capturedEngine\":\"CORRIDOR_AWARE\"",
                "\"capturedEngine\":\"LEGACY_V02\""));
        for (int index = 0; index < invalid.size(); index++) {
            CliRun run = runCli(directory.resolve("legacy-" + index),
                withExpectedArtifact(expected, invalid.get(index)), "A", 51.0, 55.0,
                false, "{}", "SCALAR_INFERENCE", "[]", "[]");
            assertEquals(2, run.exit(), run.output());
            assertTrue(run.output().contains("\"fidelityStatus\":\"MISMATCH\""));
            assertTrue(run.output().contains(
                "\"reason\":\"expected-scalar-output-invalid\""));
        }
    }

    @Test
    void scalarExpectationProducerAndReaderShareExactUtf8Envelope(@TempDir Path directory)
            throws Exception {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        assertThrows(IllegalArgumentException.class, () -> Format15ProductionBundleFactory
            .createWithExpectedScalarOutput("b".repeat(16_384), input,
                TrackerMode.CORRIDOR_AWARE));
        assertThrows(IllegalArgumentException.class, () -> Format15ProductionBundleFactory
            .createWithExpectedScalarOutput("é".repeat(8_192), input,
                TrackerMode.CORRIDOR_AWARE));

        Format15Bundle nearBoundary = Format15ProductionBundleFactory
            .createWithExpectedScalarOutput("b".repeat(15_900), input,
                TrackerMode.CORRIDOR_AWARE);
        assertTrue(nearBoundary.artifact("expected-scalar-output.json").bytes().length
            <= 16 * 1024);
        CliRun nearRun = runCli(directory.resolve("near-boundary"), nearBoundary, "A",
            51.0, 55.0, false, "{}", "SCALAR_INFERENCE", "[]", "[]");
        assertEquals(0, nearRun.exit(), nearRun.output());
        assertTrue(nearRun.output().contains("\"fidelityStatus\":\"MATCH\""));

        Format15Bundle escapedUnicode = Format15ProductionBundleFactory
            .createWithExpectedScalarOutput(("é\"\n").repeat(900), input,
                TrackerMode.CORRIDOR_AWARE);
        CliRun unicodeRun = runCli(directory.resolve("escaped-unicode"), escapedUnicode, "A",
            51.0, 55.0, false, "{}", "SCALAR_INFERENCE", "[]", "[]");
        assertEquals(0, unicodeRun.exit(), unicodeRun.output());
        assertTrue(unicodeRun.output().contains("\"fidelityStatus\":\"MATCH\""));
    }

    @Test
    void scalarFingerprintRejectsMalformedSurrogatesAndPreservesValidUnicode() {
        String malformed = String.valueOf((char) 0xd800);
        assertThrows(IllegalArgumentException.class,
            () -> ScalarReplayFingerprint.sha256(syntheticScalar(malformed, "metric")));
        assertThrows(IllegalArgumentException.class,
            () -> ScalarReplayFingerprint.sha256(syntheticScalar("branch", malformed)));
        assertThrows(IllegalArgumentException.class, () -> ScalarReplayFingerprint.sha256(
            syntheticScalar("a".repeat(1_048_577), "metric")));
        assertThrows(IllegalArgumentException.class, () -> ScalarReplayFingerprint.sha256(
            syntheticScalar("é".repeat(524_289), "metric")));

        String supplementary = "route-\ud83d\ude80-é";
        assertEquals(ScalarReplayFingerprint.sha256(syntheticScalar(supplementary, "métric")),
            ScalarReplayFingerprint.sha256(syntheticScalar(supplementary, "métric")));
        assertNotEquals(ScalarReplayFingerprint.sha256(syntheticScalar(supplementary, "métric")),
            ScalarReplayFingerprint.sha256(syntheticScalar("route-?-é", "métric")));
    }

    @Test
    void strictCliTraversesHashedNestedCorpusWithPathsContainingSpaces(
            @TempDir Path directory) throws Exception {
        String fineResults = runStrictCli(directory.resolve("fine fixture with spaces"),
            fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE, RasterFixture.FINE),
            "A,B,HYBRID", 51.0, 55.0);
        assertEquals(3, occurrences(fineResults, "\"status\":\"ok\""));

        String coarseImageResults = runStrictCli(directory.resolve("coarse image fixture"),
            fixture(TrackerMode.DIRECTIONAL_IMAGE, Scene.RIDGE, RasterFixture.COARSE),
            "IMAGE", 53.0, 57.0);
        assertEquals(1, occurrences(coarseImageResults, "\"status\":\"ok\""));
        assertFalse((fineResults + coarseImageResults).contains(directory.toString()));
    }

    @Test
    void frozenInputRoundTripsAndRunsEveryModernEngine(@TempDir Path directory) throws Exception {
        FrozenReplayInput captured = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        FrozenReplayInput decoded = FrozenReplayCodec.decode(FrozenReplayCodec.encode(captured));
        assertEquals(captured.evidence().canonicalHash(), decoded.evidence().canonicalHash());
        assertEquals(captured.network().canonicalHash(), decoded.network().canonicalHash());
        assertEquals(captured.request().corridorInput(), decoded.request().corridorInput());
        Path archivePath = directory.resolve("analytic ridge.zip");
        Format15BundleWriter.write(Format15ProductionBundleFactory.create("test", decoded), archivePath);
        Format15Archive archive = Format15ArchiveReader.read(archivePath);

        for (TrackerMode engine : List.of(TrackerMode.CORRIDOR_AWARE,
                TrackerMode.PROBABILISTIC, TrackerMode.HYBRID)) {
            assertProductionReplay(archive, decoded, engine, 51.0, 55.0);
        }

        FrozenReplayInput image = fixture(TrackerMode.DIRECTIONAL_IMAGE, Scene.RIDGE,
            RasterFixture.COARSE);
        Path imagePath = directory.resolve("coarse image ridge.zip");
        Format15BundleWriter.write(Format15ProductionBundleFactory.create("test", image), imagePath);
        assertProductionReplay(Format15ArchiveReader.read(imagePath), image,
            TrackerMode.DIRECTIONAL_IMAGE, 53.0, 57.0);
    }

    @Test
    void ordinaryFinePitchLongRoutesRejectTruncatedBAndImageAlternatives() {
        for (TrackerMode engine : List.of(TrackerMode.PROBABILISTIC,
                TrackerMode.DIRECTIONAL_IMAGE)) {
            FrozenReplayInput input = fixture(engine, Scene.RIDGE,
                RasterFixture.FINE_DENSE);
            Format15ReplayRunner.Result actual = Format15ReplayRunner.replay(input,
                ReplayLevel.SCALAR_INFERENCE, engine);

            assertFalse(actual.inference().hypotheses().isEmpty());
            assertTrue(actual.inference().alternativesTruncated());
            assertNotEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT,
                actual.inference().status());
            assertTrue(actual.inference().evaluatedStates() > 0);
            assertTrue(actual.inference().evaluatedTransitions() > 0);
            assertThrows(ReplayMismatchException.class,
                () -> ProductionReplayValidator.validateScalar(actual));
        }
    }

    @Test
    void uniformNoSignalCannotSatisfyAProductionReplayGate() {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.NO_SIGNAL);
        for (TrackerMode engine : List.of(TrackerMode.CORRIDOR_AWARE,
                TrackerMode.PROBABILISTIC, TrackerMode.HYBRID,
                TrackerMode.DIRECTIONAL_IMAGE)) {
            assertThrows(ReplayMismatchException.class, () -> {
                Format15ReplayRunner.Result result = Format15ReplayRunner.replay(
                    input, ReplayLevel.SCALAR_INFERENCE, engine);
                ProductionReplayValidator.validateScalar(result);
            }, engine + " must not satisfy the strict no-signal gate");
        }
    }

    @Test
    void weakerParallelRidgeDoesNotReplaceTheSelectedAnalyticRidge() {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.PARALLEL);
        for (TrackerMode engine : List.of(TrackerMode.CORRIDOR_AWARE, TrackerMode.PROBABILISTIC,
                TrackerMode.HYBRID, TrackerMode.DIRECTIONAL_IMAGE)) {
            Format15ReplayRunner.Result result = Format15ReplayRunner.replay(
                input, ReplayLevel.FINAL_GEOMETRY, engine);
            assertFalse(result.geometry().isEmpty());
            assertTrue(interiorMeanY(result.geometry().get(0).points()) > 52.0
                    && interiorMeanY(result.geometry().get(0).points()) < 58.0,
                engine + " switched to the weaker parallel ridge");
        }
    }

    @Test
    void codecRejectsAggregateCollectionsBeforeSerializingThem() {
        FrozenReplayInput base = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        int width = 500;
        int height = 300;
        double[] values = new double[width * height];
        boolean[] valid = new boolean[values.length];
        Arrays.fill(values, 0.1);
        Arrays.fill(valid, true);
        ScalarEvidenceField first = new ScalarEvidenceField(width, height, values, valid,
            base.evidence().fields().get("native").lineage());
        ScalarEvidenceField second = new ScalarEvidenceField(width, height, values, valid,
            base.evidence().fields().get("native").lineage());
        EvidenceSnapshot evidence = new EvidenceSnapshot("large-evidence",
            base.evidence().coordinateFrame(),
            RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1),
            base.evidence().resolution(), MetricRegion.rectangle(1, 1, 160, 100),
            MetricRegion.rectangle(-0.5, -0.5, 499.5, 299.5),
            Map.of("one", first, "two", second), "bounded-source");
        FrozenReplayInput large = withEvidence(base, evidence);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> FrozenReplayCodec.encode(large));
        assertTrue(allMessages(failure).contains("aggregate"));
    }

    @Test
    void codecRejectsAggregateScalarOperationsBeforeSerializingThem() {
        FrozenReplayInput base = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        List<String> operations = Collections.nCopies(250_000, "synthetic-operation");
        EvidenceFieldLineage lineage = new EvidenceFieldLineage(
            EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "synthetic",
            EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false, operations);
        ScalarEvidenceField field = base.evidence().fields().get("native");
        ScalarEvidenceField shared = new ScalarEvidenceField(field.width(), field.height(),
            field.copiedValues(), field.copiedValidity(),
            field.copiedInterpolationValidity(), lineage);
        EvidenceSnapshot evidence = new EvidenceSnapshot("large-lineage",
            base.evidence().coordinateFrame(), base.evidence().transform(),
            base.evidence().resolution(), base.evidence().decisionRegion(),
            base.evidence().evidenceRegion(), Map.of("one", shared, "two", shared,
                "three", shared), base.evidence().resampling(), "bounded-source");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> FrozenReplayCodec.encode(withEvidence(base, evidence)));
        assertTrue(allMessages(failure).contains("aggregate collection"));
    }

    @Test
    void codecRejectsUnknownVersionTrailingDataAndNonfiniteScalar() {
        byte[] encoded = FrozenReplayCodec.encode(
            fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE));

        byte[] unknownVersion = encoded.clone();
        unknownVersion[7] = (byte) (FrozenReplayCodec.VERSION + 1);
        assertThrows(IllegalArgumentException.class,
            () -> FrozenReplayCodec.decode(unknownVersion));

        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IllegalArgumentException.class,
            () -> FrozenReplayCodec.decode(trailing));

        byte[] nonfinite = encoded.clone();
        byte[] finite = ByteBuffer.allocate(Double.BYTES).putDouble(0.02).array();
        int offset = firstOccurrence(nonfinite, finite);
        assertTrue(offset >= 0);
        ByteBuffer.wrap(nonfinite, offset, Double.BYTES).putDouble(Double.NaN);
        assertThrows(IllegalArgumentException.class,
            () -> FrozenReplayCodec.decode(nonfinite));
    }

    @Test
    void codecRejectsMalformedUtf8AndPrivatePathMetadata() {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        byte[] bytes = FrozenReplayCodec.encode(input);
        bytes[12] = (byte) 0xc3;
        IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
            () -> FrozenReplayCodec.decode(bytes));
        assertTrue(allMessages(malformed).toLowerCase().contains("utf-8"));

        EvidenceSnapshot privateEvidence = new EvidenceSnapshot("private-evidence",
            input.evidence().coordinateFrame(), input.evidence().transform(),
            input.evidence().resolution(), input.evidence().decisionRegion(),
            input.evidence().evidenceRegion(), input.evidence().fields(),
            input.evidence().resampling(), "/private/corpus/archive.zip");
        FrozenReplayInput privateInput = withEvidence(input, privateEvidence);
        IllegalArgumentException privatePath = assertThrows(IllegalArgumentException.class,
            () -> FrozenReplayCodec.encode(privateInput));
        assertTrue(allMessages(privatePath).contains("private path"));
    }

    @Test
    void productionWriterRejectsWindowsDriveIdentityBeforeCreatingArchive(
            @TempDir Path directory) {
        assertBuildIdentityRejectedBeforeWrite("C:\\synthetic\\private\\archive.zip",
            fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE), directory.resolve("drive.zip"));
    }

    @Test
    void productionWriterRejectsUncIdentityBeforeCreatingArchive(@TempDir Path directory) {
        assertBuildIdentityRejectedBeforeWrite("\\\\synthetic-server\\private\\archive.zip",
            fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE), directory.resolve("unc.zip"));
    }

    @Test
    void productionBuildIdentityHasPlatformIndependentRootBoundaries(@TempDir Path directory)
            throws Exception {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        List<String> rejected = List.of("/synthetic/private/archive.zip",
            "C:/synthetic/private/archive.zip", "\\synthetic\\rooted.zip",
            "\\\\?\\C:\\synthetic\\device.zip", "file:///synthetic/archive.zip",
            "build?X-Amz-Signature=synthetic");
        for (int index = 0; index < rejected.size(); index++) {
            assertBuildIdentityRejectedBeforeWrite(rejected.get(index), input,
                directory.resolve("rejected-" + index + ".zip"));
        }

        Path safe = directory.resolve("safe.zip");
        Format15BundleWriter.write(Format15ProductionBundleFactory.create(
            "build-2026.09.15+synthetic", input), safe);
        assertTrue(Files.isRegularFile(safe));
    }

    @Test
    void productionBundleRejectsAbsoluteAndSignedBuildIdentityBeforePersistence() {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> Format15ProductionBundleFactory.create(
                "/private/archive.zip?X-Amz-Signature=synthetic", input));
        assertTrue(allMessages(failure).contains("private path or signed value"));
    }

    @Test
    void strictCliTreatsCorpusInventoryErrorsAsTypedFailures(@TempDir Path directory)
            throws Exception {
        CliRun run = runCli(directory, fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE),
            "A", 51.0, 55.0, false, "{}", "FINAL_GEOMETRY",
            "[\"complete-edit-plan\",\"incident-relations\"]",
            "[{\"source\":\"broken.zip#0123456789ab\",\"code\":\"MALFORMED_ZIP\"}]");

        assertEquals(2, run.exit());
        assertTrue(run.output().contains("\"inventoryErrors\":1"));
        assertTrue(run.output().contains("\"code\":\"MALFORMED_ZIP\""));
        assertEquals(1, occurrences(run.output(), "\"status\":\"ok\""));
    }

    @Test
    void officialPythonInventoryFeedsStrictProductionReplay(@TempDir Path directory)
            throws Exception {
        Path corpus = directory.resolve("official corpus with spaces");
        Files.createDirectories(corpus);
        Path inner = directory.resolve("factory bundle.zip");
        Format15BundleWriter.write(Format15ProductionBundleFactory.create("test-build",
            fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE)), inner);
        byte[] innerBytes = Files.readAllBytes(inner);
        Path outer = corpus.resolve("outer archive with spaces.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(outer))) {
            zip.putNextEntry(new ZipEntry("nested/factory bundle.zip"));
            zip.write(innerBytes);
            zip.closeEntry();
        }
        Path manifest = directory.resolve("official manifest with spaces.json");
        Process inventory = new ProcessBuilder("python3", "scripts/v022-corpus.py", "inventory",
            "--inputs", corpus.toString(), "--output", manifest.toString())
            .directory(Path.of("").toAbsolutePath().toFile()).redirectErrorStream(true).start();
        String inventoryOutput = new String(inventory.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        assertEquals(0, inventory.waitFor(), inventoryOutput);
        String manifestText = Files.readString(manifest, StandardCharsets.UTF_8);
        assertEquals(1, occurrences(manifestText, "\"caseId\""));
        assertTrue(manifestText.contains("\"errors\": []"));

        Path output = directory.resolve("strict replay results.json");
        int exit = ProductionReplayCommand.run(new String[] {"--manifest", manifest.toString(),
            "--engines", "A", "--output", output.toString(), "--strict", "--offline",
            "--ablation-config", "{}"});
        assertEquals(0, exit, Files.readString(output, StandardCharsets.UTF_8));
        assertTrue(Files.readString(output, StandardCharsets.UTF_8)
            .contains("\"status\":\"ok\""));
    }

    @Test
    void strictCliRetainsEveryRequestedHashFailureAndRejectsWrongGeometry(
            @TempDir Path directory) throws Exception {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        CliRun hashFailure = runCli(directory.resolve("bad outer hash"), input,
            "A,B,HYBRID", 51.0, 55.0, true, "{}");
        assertEquals(2, hashFailure.exit());
        assertEquals(3, occurrences(hashFailure.output(), "\"status\":\"failed\""));
        assertEquals(3, occurrences(hashFailure.output(),
            "\"reason\":\"outer-sha256-mismatch\""));
        assertFalse(hashFailure.output().contains(directory.toString()));

        CliRun wrongGeometry = runCli(directory.resolve("wrong invariant"), input,
            "A", 80.0, 90.0, false, "{}");
        assertEquals(2, wrongGeometry.exit());
        assertTrue(wrongGeometry.output().contains(
            "\"reason\":\"interior-route-invariant-failed\""));
    }

    @Test
    void unsupportedAblationOptionFailsInsteadOfBeingIgnored(@TempDir Path directory) {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> runCli(directory, input, "A", 51.0, 55.0, false,
                "{\"bypassProductionEngine\":true}"));
        assertEquals("unsupported-ablation-option", failure.getMessage());
    }

    @Test
    void strictCliReportsUnsupportedAndMissingCapabilitiesPerEngine(
            @TempDir Path directory) throws Exception {
        FrozenReplayInput input = fixture(TrackerMode.CORRIDOR_AWARE, Scene.RIDGE);
        CliRun unsupported = runCli(directory.resolve("unsupported raster"), input,
            "A,IMAGE", 0.0, 0.0, false, "{}", "RASTER_INFERENCE", "[]");
        assertEquals(2, unsupported.exit());
        assertEquals(2, occurrences(unsupported.output(),
            "\"reason\":\"unsupported-replay-level\""));

        CliRun missing = runCli(directory.resolve("missing input"), input,
            "A,B", 0.0, 0.0, false, "{}", "FINAL_GEOMETRY",
            "[\"frozenRaster\"]");
        assertEquals(2, missing.exit());
        assertEquals(2, occurrences(missing.output(),
            "\"reason\":\"manifest-inputs-missing\""));

        CliRun scalar = runCli(directory.resolve("scalar only"), input,
            "A", 80.0, 90.0, false, "{}", "SCALAR_INFERENCE", "[]");
        assertEquals(0, scalar.exit(), scalar.output());
        assertTrue(scalar.output().contains(
            "\"replayLevel\":\"SCALAR_INFERENCE\""));

        CliRun noRoute = runCli(directory.resolve("faithful no route"),
            fixture(TrackerMode.CORRIDOR_AWARE, Scene.NO_SIGNAL),
            "A", 0.0, 0.0, false, "{}", "SCALAR_INFERENCE", "[]");
        assertEquals(2, noRoute.exit());
        assertTrue(noRoute.output().contains("\"actualStatus\":\"NO_ROUTE\""));
        assertTrue(noRoute.output().contains("\"alternativesTruncated\":false"));
    }

    @Test
    void rasterAndEditReplayRemainExplicitlyUnsupported(@TempDir Path directory) throws Exception {
        FrozenReplayInput input = fixture(TrackerMode.PROBABILISTIC, Scene.RIDGE);
        Path archivePath = directory.resolve("input.zip");
        Format15BundleWriter.write(Format15ProductionBundleFactory.create("test", input), archivePath);
        Format15Archive archive = Format15ArchiveReader.read(archivePath);
        assertThrows(ReplayMismatchException.class, () -> Format15ReplayRunner.replay(archive,
            ReplayLevel.RASTER_INFERENCE, input.canonicalHash(),
            input.request().parameterHash()));
        assertThrows(ReplayMismatchException.class, () -> Format15ReplayRunner.replay(archive,
            ReplayLevel.FULL_EDIT_PLAN, input.canonicalHash(),
            input.request().parameterHash()));
    }

    private static void assertBuildIdentityRejectedBeforeWrite(String identity,
            FrozenReplayInput input, Path output) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> Format15BundleWriter.write(
                Format15ProductionBundleFactory.create(identity, input), output), identity);
        assertTrue(allMessages(failure).contains("private path or signed value"), identity);
        assertFalse(Files.exists(output), identity);
    }

    private static String runStrictCli(Path directory, FrozenReplayInput input,
            String engines, double minimumY, double maximumY) throws Exception {
        CliRun run = runCli(directory, input, engines, minimumY, maximumY,
            false, "{}");
        assertEquals(0, run.exit(), run.output());
        return run.output();
    }

    private static CliRun runCli(Path directory, FrozenReplayInput input,
            String engines, double minimumY, double maximumY,
            boolean corruptOuterHash, String ablationContents) throws Exception {
        return runCli(directory, input, engines, minimumY, maximumY,
            corruptOuterHash, ablationContents, "FINAL_GEOMETRY",
            "[\"complete-edit-plan\",\"incident-relations\"]", "[]");
    }

    private static CliRun runCli(Path directory, FrozenReplayInput input,
            String engines, double minimumY, double maximumY,
            boolean corruptOuterHash, String ablationContents,
            String capability, String missingInputs) throws Exception {
        return runCli(directory, input, engines, minimumY, maximumY,
            corruptOuterHash, ablationContents, capability, missingInputs, "[]");
    }

    private static CliRun runCli(Path directory, FrozenReplayInput input,
            String engines, double minimumY, double maximumY,
            boolean corruptOuterHash, String ablationContents,
            String capability, String missingInputs, String errors) throws Exception {
        return runCli(directory, Format15ProductionBundleFactory.create("test", input), engines,
            minimumY, maximumY, corruptOuterHash, ablationContents, capability, missingInputs,
            errors);
    }

    private static CliRun runCli(Path directory, Format15Bundle bundle,
            String engines, double minimumY, double maximumY,
            boolean corruptOuterHash, String ablationContents,
            String capability, String missingInputs, String errors) throws Exception {
        Files.createDirectories(directory);
        Path inner = directory.resolve("bundle source.zip");
        Format15BundleWriter.write(bundle, inner);
        byte[] innerBytes = Files.readAllBytes(inner);
        Path outer = directory.resolve("outer archive with spaces.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(outer))) {
            zip.putNextEntry(new ZipEntry("nested/bundle one.zip"));
            zip.write(innerBytes);
            zip.closeEntry();
        }
        String outerHash = Format15Safety.sha256(Files.readAllBytes(outer));
        if (corruptOuterHash) {
            outerHash = "0".repeat(64);
        }
        String bundleName = outer.getFileName() + "!nested/bundle one.zip";
        String manifest = "{\"schema\":\"wayheatmaptracer-v022-corpus-1\","
            + "\"inputRoot\":\"" + json(directory.toString()) + "\","
            + "\"cases\":[{\"caseId\":\"case-001\","
            + "\"sourcePath\":\"" + json(outer.toString()) + "\","
            + "\"outerSha256\":\"" + outerHash + "\","
            + "\"bundleName\":\"" + json(bundleName) + "\","
            + "\"bundleSha256\":\"" + Format15Safety.sha256(innerBytes) + "\","
            + "\"byteSize\":" + innerBytes.length + ","
            + "\"replayCapability\":" + jsonQuote(capability) + ","
            + "\"missingInputs\":" + missingInputs + ","
            + "\"expectedRoute\":{\"minimumLengthMeters\":119.0,"
            + "\"minimumDirectSupportMeters\":50.0,"
            + "\"interiorMeanYMin\":" + minimumY
            + ",\"interiorMeanYMax\":" + maximumY + "}}],\"errors\":" + errors + "}";
        Path manifestPath = directory.resolve("corpus manifest.json");
        Path output = directory.resolve("results folder/replay results.json");
        Path ablation = directory.resolve("ablation config.json");
        Files.writeString(manifestPath, manifest, StandardCharsets.UTF_8);
        Files.writeString(ablation, ablationContents, StandardCharsets.UTF_8);

        int exit = ProductionReplayCommand.run(new String[] {"--manifest",
            manifestPath.toString(), "--engines", engines, "--output", output.toString(),
            "--strict", "--offline", "--ablation-config", ablation.toString()});
        return new CliRun(exit, Files.readString(output, StandardCharsets.UTF_8));
    }

    private static String finalFingerprint(Format15ReplayRunner.Result output) {
        return FinalReplayFingerprint.sha256(output);
    }

    private static Format15ReplayRunner.Result withRoutes(Format15ReplayRunner.Result source,
            List<ModernTracePipeline.Route> routes) {
        return new Format15ReplayRunner.Result(source.level(), source.capturedEngine(),
            source.engine(), source.inference(), routes, source.inputHash());
    }

    private static ModernTracePipeline.Route copyRoute(ModernTracePipeline.Route source,
            TraceHypothesis raw, TraceHypothesis hypothesis, List<FinalRoutePointId> ids,
            Map<FinalRoutePointId, MetricPoint> assignments,
            Map<FinalRoutePointId, ObservationOwnership> ownership,
            FinalGeometryEvaluator.Result quality, ImageSupportedLocalCleanup.Status cleanup,
            boolean changed) {
        return new ModernTracePipeline.Route(raw, hypothesis, ids, assignments, ownership,
            quality, cleanup, changed);
    }

    private static <T> Map<FinalRoutePointId, T> remap(Map<FinalRoutePointId, T> source,
            FinalRoutePointId oldId, FinalRoutePointId newId) {
        Map<FinalRoutePointId, T> result = new LinkedHashMap<>();
        source.forEach((id, value) -> result.put(id.equals(oldId) ? newId : id, value));
        return result;
    }

    private static <T> Map<FinalRoutePointId, T> reverse(
            Map<FinalRoutePointId, T> source) {
        List<Map.Entry<FinalRoutePointId, T>> entries =
            new java.util.ArrayList<>(source.entrySet());
        Collections.reverse(entries);
        Map<FinalRoutePointId, T> result = new LinkedHashMap<>();
        entries.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private static Format15Bundle withFinalExpectedArtifact(Format15Bundle source, String text) {
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>(source.artifacts());
        artifacts.put("expected-final-output.json",
            Format15Artifact.text("expected-final-output.json", text));
        return new Format15Bundle(source.buildIdentity(), source.sourceIdentityHash(),
            source.parameterHash(), artifacts);
    }

    private static TraceHypothesisSet syntheticScalar(String branch, String diagnostic) {
        TraceHypothesis hypothesis = new TraceHypothesis("synthetic", branch,
            List.of(new MetricPoint(0, 0), new MetricPoint(1, 0)),
            List.of(ObservationOwnership.DIRECT_TWO_SIDED,
                ObservationOwnership.DIRECT_TWO_SIDED), 0.0, OptionalDouble.empty(),
            Map.of(diagnostic, 1.0));
        return new TraceHypothesisSet(TrackerMode.CORRIDOR_AWARE, List.of(hypothesis),
            TraceHypothesisSet.Status.COMPLETE, false, 2, 1, "synthetic");
    }

    private static String fingerprint(TraceHypothesisSet output) {
        return ScalarReplayFingerprint.sha256(output);
    }

    private static TraceHypothesisSet withHypotheses(TraceHypothesisSet source,
            List<TraceHypothesis> hypotheses) {
        return new TraceHypothesisSet(source.engine(), hypotheses, source.status(),
            source.alternativesTruncated(), source.evaluatedStates(),
            source.evaluatedTransitions(), source.explanation());
    }

    private static TraceHypothesis copyHypothesis(TraceHypothesis source, String id,
            String branch, List<MetricPoint> points, List<ObservationOwnership> support,
            double objective, OptionalDouble posterior, Map<String, Double> diagnostics) {
        return new TraceHypothesis(id, branch, points, support, objective, posterior, diagnostics);
    }

    private static Format15Bundle withExpectedArtifact(Format15Bundle source, String text) {
        Map<String, Format15Artifact> artifacts = new LinkedHashMap<>(source.artifacts());
        artifacts.put("expected-scalar-output.json",
            Format15Artifact.text("expected-scalar-output.json", text));
        return new Format15Bundle(source.buildIdentity(), source.sourceIdentityHash(),
            source.parameterHash(), artifacts);
    }

    private static void assertProductionReplay(Format15Archive archive,
            FrozenReplayInput input, TrackerMode engine, double minimumY,
            double maximumY) {
        Format15ReplayRunner.Result scalar = Format15ReplayRunner.replay(archive,
            ReplayLevel.SCALAR_INFERENCE, input.canonicalHash(),
            input.request().parameterHash(), engine);
        assertEquals(engine, scalar.engine());
        assertEquals(input.canonicalHash(), scalar.inputHash());
        assertFalse(scalar.inference().hypotheses().isEmpty(),
            engine + " must emit an actual ridge hypothesis");
        assertFalse(scalar.inference().alternativesTruncated(),
            engine + " analytic route must not exhaust alternatives");
        assertNotEquals(TraceHypothesisSet.Status.RESOURCE_LIMIT, scalar.inference().status());
        assertTrue(scalar.inference().evaluatedStates() > 0,
            engine + " must expose actual solver state work");
        assertTrue(scalar.inference().evaluatedTransitions() > 0,
            engine + " must expose actual solver transition work");

        Format15ReplayRunner.Result finalResult = Format15ReplayRunner.replay(archive,
            ReplayLevel.FINAL_GEOMETRY, input.canonicalHash(),
            input.request().parameterHash(), engine);
        assertEquals(engine, finalResult.engine());
        assertFalse(finalResult.geometry().isEmpty(),
            engine + " final replay must evaluate actual geometry");
        var route = finalResult.routes().get(0);
        var points = route.hypothesis().points();
        assertEquals(20.0, points.get(0).xMeters(), 0.05);
        assertEquals(50.0, points.get(0).yMeters(), 0.05);
        assertEquals(140.0, points.get(points.size() - 1).xMeters(), 0.05);
        assertEquals(50.0, points.get(points.size() - 1).yMeters(), 0.05);
        assertTrue(polylineLength(points) > 119.0,
            engine + " route must span the physical selection");
        assertTrue(interiorMeanY(points) > minimumY && interiorMeanY(points) < maximumY,
            engine + " must follow the displaced analytic ridge");
        assertTrue(route.quality().directlySupportedLengthMeters() > 50.0,
            engine + " must retain measured physical support");
    }

    private static FrozenReplayInput fixture(TrackerMode engine, Scene scene) {
        return fixture(engine, scene, RasterFixture.FINE);
    }

    private static FrozenReplayInput fixture(TrackerMode engine, Scene scene,
            RasterFixture raster) {
        GeographicPoint origin = new GeographicPoint(50, 14);
        LocalMetricFrame frame = LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(49.99, 13.99), new GeographicPoint(50.01, 14.02));
        PrimitiveKey first = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 1);
        PrimitiveKey last = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 2);
        PrimitiveKey way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 3);
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>();
        values.put(first, new DetachedNode(first,
            frame.toGeographic(new MetricPoint(20, 50)), Map.of(), false, false));
        values.put(last, new DetachedNode(last,
            frame.toGeographic(new MetricPoint(140, 50)), Map.of(), false, false));
        values.put(way, new DetachedWay(way, List.of(first, last),
            Map.of("highway", "path", "note", "/survey/reference"), false, false));
        MetricRegion region = MetricRegion.rectangle(1, 1, 159, 99);
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,
            "replay-test-v1", values.keySet(), Set.of(way), Set.of(), Set.of(first, last), Set.of(),
            Map.of(way, List.of(new OccurrenceRange(0, 1))), List.of(), region, region,
            false, true, true, true);
        Map<PrimitiveKey, Set<PrimitiveKey>> watches = new LinkedHashMap<>();
        watches.put(first, Set.of(way));
        watches.put(last, Set.of(way));
        watches.put(way, Set.of());
        NetworkSnapshot network = new NetworkSnapshot("network", SnapshotRole.CAPTURED_BEFORE,
            "dataset", 1, closure, values, watches);

        int width = raster == RasterFixture.COARSE ? 33 : 161;
        int height = raster == RasterFixture.COARSE ? 21 : 101;
        double[] intensity = new double[width * height];
        boolean[] valid = new boolean[intensity.length];
        Arrays.fill(intensity, 0.02);
        Arrays.fill(valid, true);
        if (scene != Scene.NO_SIGNAL) {
            int center = raster == RasterFixture.COARSE ? 11 : 53;
            Arrays.fill(intensity, (center - 2) * width, (center - 1) * width, 0.25);
            Arrays.fill(intensity, (center - 1) * width, center * width, 0.65);
            Arrays.fill(intensity, center * width, (center + 1) * width, 1.0);
            Arrays.fill(intensity, (center + 1) * width, (center + 2) * width, 0.65);
            Arrays.fill(intensity, (center + 2) * width, (center + 3) * width, 0.25);
        }
        if (scene == Scene.PARALLEL) {
            int parallel = raster == RasterFixture.COARSE ? 9 : 60;
            Arrays.fill(intensity, parallel * width, (parallel + 1) * width, 0.45);
        }
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, intensity, valid,
            new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "synthetic",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        double rasterPitch = raster == RasterFixture.COARSE ? 5.0 : 1.0;
        EvidenceResolution resolution = raster != RasterFixture.COARSE
            ? EvidenceResolution.nativeSource(2, 1).resampledTo(1)
            : EvidenceResolution.nativeSource(10, 5).resampledTo(5);
        EvidenceSnapshot evidence = new EvidenceSnapshot("evidence", frame,
            RasterMetricTransform.metricGrid(new MetricPoint(0, 0), rasterPitch, 0, 0,
                rasterPitch), resolution,
            region, MetricRegion.rectangle(-0.5 * rasterPitch, -0.5 * rasterPitch,
                160 + 0.5 * rasterPitch, 100 + 0.5 * rasterPitch),
            Map.of("native", field), "source");
        List<Double> chainage = raster == RasterFixture.FINE_DENSE
            ? List.of(0.0, 20.0, 40.0, 60.0, 80.0, 100.0, 120.0)
            : List.of(0.0, 60.0, 120.0);
        List<DetachedProfileSamplingLocation> locations = chainage.stream().map(distance ->
            DetachedProfileSamplingLocation.at(
                frame.toGeographic(new MetricPoint(20 + distance, 50)),
                frame, evidence.transform(), distance)).toList();
        Optional<CorridorTraceInput> corridor = Optional.of(
            new CorridorTraceInput(locations, 1.0));
        TraceRequest request = new TraceRequest(way, new OccurrenceRange(0, 1), engine,
            AlignmentMode.PRECISE_SHAPE, RecoveryPermissions.disabled(7),
            new TraceBudgets(96, 8_000_000L, 128_000_000L, 32, 32),
            evidence.snapshotId(), evidence.canonicalHash(), network.snapshotId(),
            network.canonicalHash(), "settings",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "sampler", chainage.size() == 3 ? 60 : 20,
            new ProfileChainage(chainage, chainage.size() == 3 ? 60 : 20),
            resolution, corridor);
        return new FrozenReplayInput(request, evidence, network,
            new ModernTracePipeline.Options("native", GeometryCleanupConfig.disabled(),
                "synthetic", 0));
    }

    private static FrozenReplayInput withEvidence(FrozenReplayInput base,
            EvidenceSnapshot evidence) {
        TraceRequest source = base.request();
        TraceRequest request = new TraceRequest(source.selectedWayKey(), source.selectedRange(),
            source.engine(), source.geometryMode(), source.permissions(), source.budgets(),
            evidence.snapshotId(), evidence.canonicalHash(), source.networkSnapshotId(),
            source.networkContentHash(), source.settingsHash(), source.parameterHash(),
            source.samplerId(), source.configuredSampleStepMeters(), source.profileChainage(),
            evidence.resolution(), source.corridorInput());
        return new FrozenReplayInput(request, evidence, base.network(), base.options());
    }

    private static double polylineLength(List<MetricPoint> points) {
        double length = 0;
        for (int index = 1; index < points.size(); index++) {
            length += points.get(index - 1).distanceTo(points.get(index));
        }
        return length;
    }

    private static double interiorMeanY(List<MetricPoint> points) {
        return points.subList(1, points.size() - 1).stream()
            .mapToDouble(MetricPoint::yMeters).average().orElse(Double.NaN);
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String jsonQuote(String value) {
        return "\"" + json(value) + "\"";
    }

    private static int firstOccurrence(byte[] value, byte[] token) {
        outer: for (int index = 0; index <= value.length - token.length; index++) {
            for (int offset = 0; offset < token.length; offset++) {
                if (value[index + offset] != token[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }

    private static String allMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current.getMessage()).append(' ');
        }
        return messages.toString();
    }

    private record CliRun(int exit, String output) { }

    private enum Scene { RIDGE, PARALLEL, NO_SIGNAL }

    private enum RasterFixture { FINE, FINE_DENSE, COARSE }
}
