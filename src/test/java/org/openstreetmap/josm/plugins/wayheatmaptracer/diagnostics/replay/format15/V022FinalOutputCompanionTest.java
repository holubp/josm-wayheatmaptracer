package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
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

/** Production-path regression tests for the optional final-output companion. */
class V022FinalOutputCompanionTest {
    private static final String COMPANION = "private/final-output-components.bin";

    @Test
    void liveExportCarriesTheExactAlreadyProducedFinalOutput(@TempDir Path directory)
            throws Exception {
        FrozenReplayInput input = V022ProductionReplayTest.syntheticRidgeInput();
        Format15ReplayRunner.Result produced = Format15ReplayRunner.replay(input,
                ReplayLevel.FINAL_GEOMETRY, TrackerMode.PROBABILISTIC);
        ModernTracePipeline.Result actual = new ModernTracePipeline.Result(
                produced.inference(), produced.routes());
        assertTrue(!produced.routes().isEmpty(), "synthetic ridge fixture must retain a route");

        int routeIndex = produced.routes().isEmpty() ? -1 : 0;
        String status = routeIndex < 0 ? "blocked" : "review-required";
        Format15Bundle live = Format15ProductionBundleFactory.createLive("synthetic-build",
                input, actual, status, "synthetic-source", routeIndex, null, false, false);

        assertTrue(live.artifactNames().contains(COMPANION),
                "live production export must retain typed final-output components");
        assertTrue(live.artifactNames().contains("private/final-output-components-summary.json"));

        Path archivePath = directory.resolve("output-components.zip");
        Format15BundleWriter.write(live, archivePath);
        Format15Archive archive = Format15ArchiveReader.read(archivePath);
        FinalReplayExpectation expected = FinalReplayExpectation.read(archive).orElseThrow();
        FinalOutputComponentsCodec.Snapshot snapshot = FinalOutputComponentsCodec.decode(
                archive.artifact(COMPANION).orElseThrow().bytes(), expected.componentsBinding());
        Format15ReplayRunner.Result reconstructed = snapshot.output();
        assertEquals(FinalReplayFingerprint.sha256(produced),
                FinalReplayFingerprint.sha256(reconstructed));
        assertEquals(FinalOutputComponentsCodec.componentDigests(produced), snapshot.digests());
        assertEquals(produced, reconstructed);
        assertEquals(produced.routes().get(0).quality().findings(),
                reconstructed.routes().get(0).quality().findings(),
                "UI grouping must not replace detailed exported findings");
        var planAvailability = Format15ArchiveReader.parseObject(
                archive.artifact("plan-availability.json").orElseThrow().bytes(),
                "plan-availability.json");
        assertEquals("UNAVAILABLE", planAvailability.get("status"));
        assertEquals("PLAN_UNAVAILABLE", planAvailability.get("reason"));
        assertFalse(archive.artifactNames().contains("frozen-edit-plan.bin"));
        assertFalse(archive.artifactNames().contains("applied-geometry.json"));
        assertFalse(archive.capability().supports(ReplayLevel.FULL_EDIT_PLAN));
        assertTrue(FinalOutputComponentsCodec.summaryJson(snapshot.summary())
                .contains("imageCenterCostAvailability"));
    }

    @Test
    void componentDigestsLocalizeIndependentProductionOutputChanges() {
        FrozenReplayInput input = V022ProductionReplayTest.syntheticRidgeInput();
        Format15ReplayRunner.Result source = Format15ReplayRunner.replay(input,
                ReplayLevel.FINAL_GEOMETRY, TrackerMode.PROBABILISTIC);
        var route = source.routes().get(0);
        Map<FinalOutputComponentsCodec.Component, String> baseline =
                FinalOutputComponentsCodec.componentDigests(source);

        TraceHypothesis scalarGeometry = movePoint(source.inference().hypotheses().get(0), false);
        var scalarGeometryChanged = withInference(source, replaceHypothesis(
                source.inference(), 0, scalarGeometry));
        assertOnlyChanged(baseline, FinalReplayFingerprint.sha256(source), scalarGeometryChanged,
                FinalOutputComponentsCodec.Component.INFERENCE_GEOMETRY);

        TraceHypothesis scalarSemantics = changeObjective(source.inference().hypotheses().get(0));
        var scalarSemanticsChanged = withInference(source, replaceHypothesis(
                source.inference(), 0, scalarSemantics));
        assertOnlyChanged(baseline, FinalReplayFingerprint.sha256(source), scalarSemanticsChanged,
                FinalOutputComponentsCodec.Component.INFERENCE_SEMANTICS);

        var header = source.inference();
        assertOnlyChanged(baseline, FinalReplayFingerprint.sha256(source), withInference(source,
                new TraceHypothesisSet(header.engine(), header.hypotheses(), header.status(),
                        header.alternativesTruncated(), header.evaluatedStates() + 1,
                        header.evaluatedTransitions(), header.explanation())),
                FinalOutputComponentsCodec.Component.INFERENCE_HEADER);

        var rawGeometry = replaceRoute(source, 0, routeWithRaw(route,
                movePoint(route.rawHypothesis(), false)));
        assertOnlyChanged(baseline, FinalReplayFingerprint.sha256(source), rawGeometry,
                FinalOutputComponentsCodec.Component.ROUTE_RAW_GEOMETRY);

        var rawSemantics = replaceRoute(source, 0, routeWithRaw(route,
                changeObjective(route.rawHypothesis())));
        assertOnlyChanged(baseline, FinalReplayFingerprint.sha256(source), rawSemantics,
                FinalOutputComponentsCodec.Component.ROUTE_RAW_SEMANTICS);

        TraceHypothesis finalHypothesis = route.hypothesis();
        List<ObservationOwnership> finalSupport = new ArrayList<>(finalHypothesis.support());
        finalSupport.set(0, finalSupport.get(0) == ObservationOwnership.DIRECT_TWO_SIDED
                ? ObservationOwnership.NO_SIGNAL_VALID_RASTER : ObservationOwnership.DIRECT_TWO_SIDED);
        Map<String, Double> finalDiagnostics = new LinkedHashMap<>(finalHypothesis.diagnostics());
        finalDiagnostics.put("public-localization-control", 1.0);
        for (TraceHypothesis changed : List.of(changeObjective(finalHypothesis),
                new TraceHypothesis(finalHypothesis.id(), finalHypothesis.branchSignature() + "/public-control",
                        finalHypothesis.points(), finalHypothesis.support(), finalHypothesis.objective(),
                        finalHypothesis.posteriorProbability(), finalHypothesis.diagnostics()),
                new TraceHypothesis(finalHypothesis.id(), finalHypothesis.branchSignature(),
                        finalHypothesis.points(), finalSupport, finalHypothesis.objective(),
                        finalHypothesis.posteriorProbability(), finalHypothesis.diagnostics()),
                new TraceHypothesis(finalHypothesis.id(), finalHypothesis.branchSignature(),
                        finalHypothesis.points(), finalHypothesis.support(), finalHypothesis.objective(),
                        finalHypothesis.posteriorProbability(), finalDiagnostics))) {
            assertOnlyChanged(baseline, FinalReplayFingerprint.sha256(source), replaceRoute(source, 0,
                    new ModernTracePipeline.Route(route.rawHypothesis(), changed, route.pointIds(),
                            route.assignments(), route.sourceOwnership(), route.quality(),
                            route.cleanupStatus(), route.geometryChanged())),
                    FinalOutputComponentsCodec.Component.ROUTE_FINAL_SEMANTICS);
        }

        var ownershipChanged = replaceRoute(source, 0, routeWithOwnership(route));
        assertOnlyChanged(baseline, FinalReplayFingerprint.sha256(source), ownershipChanged,
                FinalOutputComponentsCodec.Component.ROUTE_PROVENANCE);

        var identityChanged = replaceRoute(source, 0, routeWithIdentity(route));
        assertOnlyChanged(baseline, FinalReplayFingerprint.sha256(source), identityChanged,
                FinalOutputComponentsCodec.Component.ROUTE_PROVENANCE);

        var qualityChanged = replaceRoute(source, 0, routeWithQuality(route));
        assertOnlyChanged(baseline, FinalReplayFingerprint.sha256(source), qualityChanged,
                FinalOutputComponentsCodec.Component.ROUTE_QUALITY);

        var findingChanged = replaceRoute(source, 0, routeWithFinding(route));
        assertOnlyChanged(baseline, FinalReplayFingerprint.sha256(source), findingChanged,
                FinalOutputComponentsCodec.Component.ROUTE_QUALITY);

        var cleanupChanged = replaceRoute(source, 0, routeWithCleanup(route));
        assertOnlyChanged(baseline, FinalReplayFingerprint.sha256(source), cleanupChanged,
                FinalOutputComponentsCodec.Component.ROUTE_CLEANUP);

        var assignmentAndGeometryChanged = replaceRoute(source, 0,
                routeWithFinalPoint(route));
        assertOnlyChanged(baseline, FinalReplayFingerprint.sha256(source), assignmentAndGeometryChanged,
                FinalOutputComponentsCodec.Component.ROUTE_FINAL_GEOMETRY,
                FinalOutputComponentsCodec.Component.ROUTE_ASSIGNMENT_GEOMETRY);
    }

    @Test
    void terminalNoRouteSnapshotAndMalformedBindingsRemainExplicit() {
        FrozenReplayInput input = V022ProductionReplayTest.syntheticNoSignalInput();
        Format15ReplayRunner.Result actual = Format15ReplayRunner.replay(input,
                ReplayLevel.FINAL_GEOMETRY, TrackerMode.PROBABILISTIC);
        assertTrue(actual.routes().isEmpty());
        var live = Format15ProductionBundleFactory.createLive("synthetic-build", input,
                new ModernTracePipeline.Result(actual.inference(), actual.routes()),
                "blocked", "synthetic-source", -1, null, false, false);
        assertTrue(live.artifactNames().contains(COMPANION));
        FinalOutputComponentsCodec.Binding binding = binding("synthetic-build",
                input.request().parameterHash(), actual);
        byte[] bytes = live.artifact(COMPANION).bytes();
        var snapshot = FinalOutputComponentsCodec.decode(bytes, binding);
        assertTrue(snapshot.output().routes().isEmpty());
        assertEquals(FinalReplayFingerprint.sha256(actual),
                FinalReplayFingerprint.sha256(snapshot.output()));

        var stale = new FinalOutputComponentsCodec.Binding(binding.buildIdentity(),
                binding.inputHash(), binding.parameterHash(), binding.capturedEngine(),
                binding.requestedEngine(), binding.replayLevel(), binding.fingerprintSchema(),
                "f".repeat(64));
        assertThrows(ReplayMismatchException.class,
                () -> FinalOutputComponentsCodec.decode(bytes, stale));
        assertThrows(ReplayMismatchException.class,
                () -> FinalOutputComponentsCodec.decode(java.util.Arrays.copyOf(bytes, bytes.length + 1),
                        binding));
        byte[] maliciousCount = bytes.clone();
        maliciousCount[8] = 0x7f;
        maliciousCount[9] = (byte) 0xff;
        maliciousCount[10] = (byte) 0xff;
        maliciousCount[11] = (byte) 0xff;
        assertThrows(ReplayMismatchException.class,
                () -> FinalOutputComponentsCodec.decode(maliciousCount, binding));
        assertThrows(ReplayMismatchException.class,
                () -> FinalOutputComponentsCodec.decode(
                        new byte[Format15Safety.MAX_ARTIFACT_BYTES + 1], binding));
    }

    @Test
    void aggregatePointCountIsRejectedBeforeAllocationAndRootCoverageCannotPass() throws Exception {
        FrozenReplayInput input = V022ProductionReplayTest.syntheticRidgeInput();
        Format15ReplayRunner.Result actual = Format15ReplayRunner.replay(input,
                ReplayLevel.FINAL_GEOMETRY, TrackerMode.PROBABILISTIC);
        var binding = binding("synthetic-build", input.request().parameterHash(), actual);
        byte[] encoded = FinalOutputComponentsCodec.encode(binding, actual).bytes();
        byte[] excessivePointCount = encoded.clone();
        int pointCountOffset = firstHypothesisPointCountOffset(encoded);
        ByteBuffer.wrap(excessivePointCount).putInt(pointCountOffset, 500_000);
        assertThrows(ReplayMismatchException.class,
                () -> FinalOutputComponentsCodec.decode(excessivePointCount, binding));

        var uncoveredRoot = new FinalOutputComponentsCodec.Binding(binding.buildIdentity(),
                binding.inputHash(), binding.parameterHash(), binding.capturedEngine(),
                binding.requestedEngine(), binding.replayLevel(), binding.fingerprintSchema(),
                "f".repeat(64));
        var internallyConsistentSections = new FinalOutputComponentsCodec.Snapshot(uncoveredRoot,
                actual, FinalOutputComponentsCodec.componentDigests(actual),
                FinalOutputComponentsCodec.qualitySummary(actual));
        var comparison = FinalOutputComponentsCodec.compare(internallyConsistentSections, actual);
        assertEquals(FinalOutputComponentsCodec.ComparisonStatus.MISMATCH, comparison.status());
        assertEquals("component-coverage-integrity", comparison.integrityCode());
    }

    @Test
    void qualitySummaryDistinguishesUnavailableImageCostFromMeasuredCost() {
        FrozenReplayInput input = V022ProductionReplayTest.syntheticRidgeInput();
        Format15ReplayRunner.Result source = Format15ReplayRunner.replay(input,
                ReplayLevel.FINAL_GEOMETRY, TrackerMode.PROBABILISTIC);
        assertTrue(source.routes().get(0).quality().meanImageCenterCost() < Double.POSITIVE_INFINITY);
        assertTrue(FinalOutputComponentsCodec.qualitySummary(source).allImageCostsAvailable());

        Format15ReplayRunner.Result unavailable = replaceRoute(source, 0,
                routeWithUnavailableImageCost(source.routes().get(0)));
        var binding = binding("synthetic-build", input.request().parameterHash(), unavailable);
        FinalOutputComponentsCodec.Encoded encoded = FinalOutputComponentsCodec.encode(binding,
                unavailable);
        assertEquals(FinalOutputComponentsCodec.Availability.AVAILABLE, encoded.availability());
        var snapshot = FinalOutputComponentsCodec.decode(encoded.bytes(), binding);
        assertEquals(1, snapshot.summary().unavailableImageCosts());
        assertFalse(snapshot.summary().allImageCostsAvailable());
        assertTrue(FinalOutputComponentsCodec.summaryJson(snapshot.summary())
                .contains("\"imageCenterCostAvailability\":\"UNAVAILABLE\""));
    }

    @Test
    void boundedCompanionOverflowIsTypedUnavailableAndPreservesRootResult() {
        List<TraceHypothesis> hypotheses = new ArrayList<>();
        String body = "x".repeat(500_000);
        for (int index = 0; index < 17; index++) {
            hypotheses.add(new TraceHypothesis(index + body, index + body,
                    List.of(new MetricPoint(0, 0), new MetricPoint(1, 0)),
                    List.of(ObservationOwnership.DIRECT_TWO_SIDED,
                            ObservationOwnership.DIRECT_TWO_SIDED), 0.25,
                    java.util.OptionalDouble.empty(), Map.of()));
        }
        TraceHypothesisSet inference = new TraceHypothesisSet(TrackerMode.PROBABILISTIC,
                hypotheses, TraceHypothesisSet.Status.COMPLETE, false, 1, 1, "synthetic-overflow");
        Format15ReplayRunner.Result output = new Format15ReplayRunner.Result(
                ReplayLevel.FINAL_GEOMETRY, TrackerMode.PROBABILISTIC,
                TrackerMode.PROBABILISTIC, inference, List.of(), "a".repeat(64));
        String root = FinalReplayFingerprint.sha256(output);
        var binding = new FinalOutputComponentsCodec.Binding("synthetic-build", "a".repeat(64),
                "b".repeat(64), TrackerMode.PROBABILISTIC, TrackerMode.PROBABILISTIC,
                ReplayLevel.FINAL_GEOMETRY, FinalReplayFingerprint.SCHEMA_VERSION, root);

        FinalOutputComponentsCodec.Encoded encoded = FinalOutputComponentsCodec.encode(binding, output);

        assertEquals(FinalOutputComponentsCodec.Availability.UNAVAILABLE_BUDGET,
                encoded.availability());
        assertEquals(root, FinalReplayFingerprint.sha256(output));
        assertEquals(null, encoded.bytes());
    }

    private static void assertOnlyChanged(
            Map<FinalOutputComponentsCodec.Component, String> baseline,
            String baselineRoot,
            Format15ReplayRunner.Result changed,
            FinalOutputComponentsCodec.Component... expected) {
        Map<FinalOutputComponentsCodec.Component, String> actual =
                FinalOutputComponentsCodec.componentDigests(changed);
        for (FinalOutputComponentsCodec.Component component :
                FinalOutputComponentsCodec.Component.values()) {
            assertEquals(java.util.Set.of(expected).contains(component), !baseline.get(component).equals(actual.get(component)),
                    "unexpected component difference: " + component);
        }
        assertNotEquals(baselineRoot, FinalReplayFingerprint.sha256(changed));
    }

    private static TraceHypothesis movePoint(TraceHypothesis source, boolean preserveObjective) {
        List<MetricPoint> points = new ArrayList<>(source.points());
        MetricPoint point = points.get(1);
        points.set(1, new MetricPoint(point.xMeters() + 0.01, point.yMeters()));
        return new TraceHypothesis(source.id(), source.branchSignature(), points, source.support(),
                preserveObjective ? source.objective() : source.objective(),
                source.posteriorProbability(), source.diagnostics());
    }

    private static TraceHypothesis changeObjective(TraceHypothesis source) {
        return new TraceHypothesis(source.id(), source.branchSignature(), source.points(),
                source.support(), source.objective() + 0.001, source.posteriorProbability(),
                source.diagnostics());
    }

    private static TraceHypothesisSet replaceHypothesis(TraceHypothesisSet source, int index,
            TraceHypothesis hypothesis) {
        List<TraceHypothesis> hypotheses = new ArrayList<>(source.hypotheses());
        hypotheses.set(index, hypothesis);
        return new TraceHypothesisSet(source.engine(), hypotheses, source.status(),
                source.alternativesTruncated(), source.evaluatedStates(),
                source.evaluatedTransitions(), source.explanation());
    }

    private static Format15ReplayRunner.Result withInference(Format15ReplayRunner.Result source,
            TraceHypothesisSet inference) {
        return new Format15ReplayRunner.Result(source.level(), source.capturedEngine(), source.engine(),
                inference, source.routes(), source.inputHash());
    }

    private static Format15ReplayRunner.Result replaceRoute(Format15ReplayRunner.Result source,
            int index, ModernTracePipeline.Route route) {
        List<ModernTracePipeline.Route> routes = new ArrayList<>(source.routes());
        routes.set(index, route);
        return new Format15ReplayRunner.Result(source.level(), source.capturedEngine(), source.engine(),
                source.inference(), routes, source.inputHash());
    }

    private static FinalOutputComponentsCodec.Binding binding(String buildIdentity,
            String parameterHash, Format15ReplayRunner.Result result) {
        return new FinalOutputComponentsCodec.Binding(buildIdentity, result.inputHash(),
                parameterHash, result.capturedEngine(), result.engine(), result.level(),
                FinalReplayFingerprint.SCHEMA_VERSION, FinalReplayFingerprint.sha256(result));
    }

    private static int firstHypothesisPointCountOffset(byte[] bytes) throws IOException {
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(bytes))) {
            data.readInt(); // magic
            data.readInt(); // version
            skipString(data); // build identity
            data.skipNBytes(32); // input hash
            data.skipNBytes(32); // parameter hash
            skipString(data); // captured engine
            skipString(data); // requested engine
            skipString(data); // replay level
            data.readInt(); // fingerprint schema
            data.skipNBytes(32); // root fingerprint
            skipString(data); // output level
            skipString(data); // captured engine
            skipString(data); // output engine
            data.skipNBytes(32); // output input hash
            skipString(data); // inference engine
            skipString(data); // inference status
            data.readBoolean();
            data.readLong();
            data.readLong();
            skipString(data); // explanation
            if (data.readInt() < 1) throw new IllegalArgumentException("fixture-has-no-hypotheses");
            skipString(data); // hypothesis id
            skipString(data); // branch signature
            data.readLong(); // objective
            if (data.readBoolean()) data.readLong();
            return bytes.length - data.available();
        }
    }

    private static void skipString(DataInputStream data) throws IOException {
        int length = data.readInt();
        if (length < 0 || length > 1_048_576) throw new IllegalArgumentException("fixture-string-length");
        data.skipNBytes(length);
    }

    private static ModernTracePipeline.Route routeWithRaw(ModernTracePipeline.Route route,
            TraceHypothesis raw) {
        return new ModernTracePipeline.Route(raw, route.hypothesis(), route.pointIds(),
                route.assignments(), route.sourceOwnership(), route.quality(), route.cleanupStatus(),
                route.geometryChanged());
    }

    private static ModernTracePipeline.Route routeWithOwnership(ModernTracePipeline.Route route) {
        Map<FinalRoutePointId, ObservationOwnership> owners = new LinkedHashMap<>(route.sourceOwnership());
        FinalRoutePointId first = route.pointIds().get(0);
        owners.put(first, owners.get(first) == ObservationOwnership.NO_SIGNAL_VALID_RASTER
                ? ObservationOwnership.DIRECT_TWO_SIDED : ObservationOwnership.NO_SIGNAL_VALID_RASTER);
        return new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(), route.pointIds(),
                route.assignments(), owners, route.quality(), route.cleanupStatus(), route.geometryChanged());
    }

    private static ModernTracePipeline.Route routeWithIdentity(ModernTracePipeline.Route route) {
        List<FinalRoutePointId> ids = new ArrayList<>(route.pointIds());
        FinalRoutePointId first = ids.get(0);
        FinalRoutePointId replacement;
        if (first instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing) {
            replacement = new FinalRoutePointId.ExistingWayNodeOccurrence(existing.wayKey(),
                    existing.nodeKey(), existing.originalOccurrenceIndex() + 1);
        } else {
            var generated = (FinalRoutePointId.GeneratedCandidatePoint) first;
            replacement = new FinalRoutePointId.GeneratedCandidatePoint(
                    generated.candidateId() + "-changed", generated.originalPointIndex());
        }
        ids.set(0, replacement);
        Map<FinalRoutePointId, MetricPoint> assignments = new LinkedHashMap<>(route.assignments());
        MetricPoint point = assignments.remove(first);
        assignments.put(replacement, point);
        Map<FinalRoutePointId, ObservationOwnership> ownership =
                new LinkedHashMap<>(route.sourceOwnership());
        ObservationOwnership owner = ownership.remove(first);
        ownership.put(replacement, owner);
        return new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(), ids,
                assignments, ownership, route.quality(), route.cleanupStatus(), route.geometryChanged());
    }

    private static ModernTracePipeline.Route routeWithQuality(ModernTracePipeline.Route route) {
        FinalGeometryEvaluator.Result q = route.quality();
        FinalGeometryEvaluator.Result changed = new FinalGeometryEvaluator.Result(q.id(),
                q.disposition(), q.findings(), q.totalLengthMeters() + 0.01,
                q.directlySupportedLengthMeters(), q.worstUnsupportedSpanMeters(),
                q.meanImageCenterCost(), q.bendPreservingRoughness());
        return new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(), route.pointIds(),
                route.assignments(), route.sourceOwnership(), changed, route.cleanupStatus(), route.geometryChanged());
    }

    private static ModernTracePipeline.Route routeWithFinding(ModernTracePipeline.Route route) {
        FinalGeometryEvaluator.Result q = route.quality();
        List<FinalGeometryEvaluator.Finding> findings = new ArrayList<>(q.findings());
        if (findings.isEmpty()) {
            findings.add(new FinalGeometryEvaluator.Finding(
                    FinalGeometryEvaluator.FindingCode.UNSUPPORTED_ISOLATED_EXCURSION,
                    FinalGeometryEvaluator.Severity.REVIEW, 0, 1, 0.01));
        } else {
            var first = findings.get(0);
            findings.set(0, new FinalGeometryEvaluator.Finding(first.code(), first.severity(),
                    first.firstVertex(), first.lastVertex(), first.amplitudeMeters() + 0.01));
        }
        FinalGeometryEvaluator.Result changed = new FinalGeometryEvaluator.Result(q.id(),
                q.disposition(), findings, q.totalLengthMeters(),
                q.directlySupportedLengthMeters(), q.worstUnsupportedSpanMeters(),
                q.meanImageCenterCost(), q.bendPreservingRoughness());
        return new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(), route.pointIds(),
                route.assignments(), route.sourceOwnership(), changed, route.cleanupStatus(), route.geometryChanged());
    }

    private static ModernTracePipeline.Route routeWithUnavailableImageCost(
            ModernTracePipeline.Route route) {
        FinalGeometryEvaluator.Result q = route.quality();
        List<FinalGeometryEvaluator.Finding> findings = new ArrayList<>(q.findings());
        findings.add(new FinalGeometryEvaluator.Finding(
                FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY,
                FinalGeometryEvaluator.Severity.REVIEW, 0,
                route.hypothesis().points().size() - 1, q.worstUnsupportedSpanMeters()));
        FinalGeometryEvaluator.Result changed = new FinalGeometryEvaluator.Result(q.id(),
                FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED, findings,
                q.totalLengthMeters(), q.directlySupportedLengthMeters(),
                q.worstUnsupportedSpanMeters(), Double.POSITIVE_INFINITY,
                q.bendPreservingRoughness());
        return new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(), route.pointIds(),
                route.assignments(), route.sourceOwnership(), changed, route.cleanupStatus(), route.geometryChanged());
    }

    private static ModernTracePipeline.Route routeWithCleanup(ModernTracePipeline.Route route) {
        ImageSupportedLocalCleanup.Status next = route.cleanupStatus()
                == ImageSupportedLocalCleanup.Status.SKIPPED
                ? ImageSupportedLocalCleanup.Status.UNCHANGED : ImageSupportedLocalCleanup.Status.SKIPPED;
        return new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(), route.pointIds(),
                route.assignments(), route.sourceOwnership(), route.quality(), next,
                !route.geometryChanged());
    }

    private static ModernTracePipeline.Route routeWithFinalPoint(ModernTracePipeline.Route route) {
        List<MetricPoint> points = new ArrayList<>(route.hypothesis().points());
        MetricPoint old = points.get(1);
        MetricPoint moved = new MetricPoint(old.xMeters(), old.yMeters() + 0.01);
        points.set(1, moved);
        TraceHypothesis finalHypothesis = new TraceHypothesis(route.hypothesis().id(),
                route.hypothesis().branchSignature(), points, route.hypothesis().support(),
                route.hypothesis().objective(), route.hypothesis().posteriorProbability(),
                route.hypothesis().diagnostics());
        Map<FinalRoutePointId, MetricPoint> assignments = new LinkedHashMap<>(route.assignments());
        assignments.put(route.pointIds().get(1), moved);
        return new ModernTracePipeline.Route(route.rawHypothesis(), finalHypothesis, route.pointIds(),
                assignments, route.sourceOwnership(), route.quality(), route.cleanupStatus(),
                route.geometryChanged());
    }
}
