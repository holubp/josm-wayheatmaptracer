package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15Archive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ArchiveReader;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15BundleWriter;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ProductionBundleFactory;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ReplayRunner;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15Artifact;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15Bundle;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.ReplayMismatchException;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId.GeneratedCandidatePoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.ManualJunctionEligibility;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner;
import org.openstreetmap.josm.plugins.wayheatmaptracer.actions.AlignWayAction;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class V022FixedIntervalEditPlanComposerTest {
    private record IndexedCoordinate(int index, GeographicPoint coordinate) { }

    @Test
    void unsupportedTerminalKinkCannotAuthorizeEndpointLocalizationGrace() throws Exception {
        IntervalTraceBatch original = batch(296.0, 296.0, TrackerMode.CORRIDOR_AWARE, false);
        var assessment = new FixedIntervalEditPlanComposer().compose(original, Map.of());
        assertTrue(assessment.applyAvailable());
        var after = assessment.plan().orElseThrow().after().primitives();
        var first = original.runs().get(0);
        PrimitiveKey endpoint = first.interval().startBoundary().nodeKey();
        assertEquals(SelectedWayIntervalPartitioner.BoundaryKind.SELECTED_ENDPOINT,
                first.interval().startBoundary().kind());
        var selected = (DetachedWay) original.network().primitives()
                .get(original.fullRequest().selectedWayKey());
        var forgedInterior = new SelectedWayIntervalPartitioner.BoundaryConstraint(
                SelectedWayIntervalPartitioner.BoundaryKind.SELECTED_ENDPOINT,
                first.interval().endBoundary().occurrenceIndex(),
                first.interval().endBoundary().nodeKey(), false, false,
                ManualJunctionEligibility.Reason.NO_JUNCTION);
        assertFalse(ModernSingleWayEditPlanAdapter.isOuterSelectedEndpoint(
                forgedInterior, original.fullRequest(), selected));
        var wrongKey = new SelectedWayIntervalPartitioner.BoundaryConstraint(
                SelectedWayIntervalPartitioner.BoundaryKind.SELECTED_ENDPOINT,
                first.interval().startBoundary().occurrenceIndex(),
                first.interval().endBoundary().nodeKey(), false, false,
                ManualJunctionEligibility.Reason.NO_JUNCTION);
        assertFalse(ModernSingleWayEditPlanAdapter.isOuterSelectedEndpoint(
                wrongKey, original.fullRequest(), selected));
        assertTrue(ModernSingleWayEditPlanAdapter.terminalUncertaintyEndpoints(original, after, Map.of())
                .contains(endpoint));
        var route = first.routes().get(0);
        var quality = route.quality();
        var findings = new ArrayList<>(quality.findings());
        findings.add(new FinalGeometryEvaluator.Finding(
                FinalGeometryEvaluator.FindingCode.UNSUPPORTED_TERMINAL_KINK,
                FinalGeometryEvaluator.Severity.REVIEW, 0, 2, 3.0));
        var unsafeQuality = new FinalGeometryEvaluator.Result(quality.id(),
                FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED, findings,
                quality.totalLengthMeters(), quality.directlySupportedLengthMeters(),
                quality.worstUnsupportedSpanMeters(), quality.meanImageCenterCost(), quality.bendPreservingRoughness());
        var routes = new ArrayList<>(first.routes());
        routes.set(0, new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(),
                route.pointIds(), route.assignments(), route.sourceOwnership(), unsafeQuality,
                route.cleanupStatus(), route.geometryChanged()));
        var runs = new ArrayList<>(original.runs());
        runs.set(0, new IntervalTraceBatch.IntervalRun(first.interval(), first.request(),
                new ModernTracePipeline.Result(first.result().inference(), routes), first.usage()));
        var unsafe = new IntervalTraceBatch(original.fullRequest(), original.evidence(), original.network(),
                original.partition(), runs, original.options(), original.authoritySpecification());
        assertFalse(ModernSingleWayEditPlanAdapter.terminalUncertaintyEndpoints(unsafe, after, Map.of())
                .contains(endpoint));
        assertFalse(ModernSingleWayEditPlanAdapter.terminalUncertaintyEndpoints(original, after, Map.of())
                .contains(first.interval().endBoundary().nodeKey()), "fixed-island seam is never eligible");
    }

    @Test
    void liveRouteChoiceRecomposesFrozenProductionBatchAndClearsReview() throws Exception {
        IntervalTraceBatch batch = batch(296.0, 296.0, TrackerMode.CORRIDOR_AWARE, true);
        assertTrue(batch.runs().get(0).routes().size() > 1);
        AlignWayAction.IntervalPreviewState state = new AlignWayAction.IntervalPreviewState(batch);
        assertTrue(state.assessment().applyAvailable());
        assertEquals(JunctionPolicy.FIXED, batch.fullRequest().permissions().junctionPolicy());
        assertTrue(AlignWayAction.intervalPreviewSummary(state).contains("Junction adjustment is off"));
        assertFalse(AlignWayAction.intervalPreviewSummary(state)
                .contains("Adjust this junction manually first."));
        String firstHash = state.assessment().plan().orElseThrow().canonicalHash();
        state.confirmReview();
        assertTrue(state.review().confirmed());

        state.choose(0, 1);

        assertEquals(Map.of(0, 1), state.routeChoices());
        assertFalse(state.review().confirmed());
        assertNotEquals(firstHash, state.assessment().plan().orElseThrow().canonicalHash());
        assertEquals(batch.runs().get(1).routes().get(0).hypothesis().id(),
                state.assessment().intervals().get(1).routeIdentity());
        assertEquals(batch.runs().get(0).routes().get(1).hypothesis().id(),
                state.assessment().intervals().get(0).routeIdentity());
        if (state.review().disposition() == ValidationReport.Disposition.REVIEW_REQUIRED) {
            state.confirmReview();
        }
        assertEquals(state.assessment().plan().orElseThrow().canonicalHash(),
                state.currentPlanForApply().canonicalHash());
    }

    @Test
    void liveAllFrozenSelectionCannotApplyOrConfirm() throws Exception {
        AlignWayAction.IntervalPreviewState state = new AlignWayAction.IntervalPreviewState(
                batch(288.0, 288.0));
        assertFalse(state.applyAvailable());
        assertTrue(state.assessment().plan().isEmpty());
        assertEquals(null, state.review());
        assertThrows(IllegalStateException.class, state::confirmReview);
    }

    @Test
    void liveLocalFailureKeepsOneSafeIntervalAndOneCompletePlan() throws Exception {
        AlignWayAction.IntervalPreviewState state = new AlignWayAction.IntervalPreviewState(
                batch(288.0, 296.0));
        assertEquals(List.of(FixedIntervalEditPlanComposer.Disposition.FROZEN_LOCAL_FAILURE,
                FixedIntervalEditPlanComposer.Disposition.CHANGED),
                state.assessment().intervals().stream().map(
                        FixedIntervalEditPlanComposer.IntervalAssessment::disposition).toList());
        assertTrue(state.assessment().applyAvailable());
        assertTrue(AlignWayAction.intervalPreviewSummary(state).contains("Kept in place"));
        assertEquals(state.assessment().plan().orElseThrow().finalPreviewWays()
                        .get(state.batch().fullRequest().selectedWayKey()),
                state.assessment().selectedWayPreview());
    }
    @BeforeAll
    static void josm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void twoProductionIntervalsComposeOneExactPlanWithFixedIsland() throws Exception {
        IntervalTraceBatch batch = batch();
        var result = new FixedIntervalEditPlanComposer().compose(batch, Map.of());
        assertEquals(List.of(FixedIntervalEditPlanComposer.Disposition.CHANGED,
                FixedIntervalEditPlanComposer.Disposition.CHANGED),
                result.intervals().stream().map(FixedIntervalEditPlanComposer.IntervalAssessment::disposition)
                        .toList(), "routes=" + batch.runs().stream().map(run ->
                        run.routes().get(0).quality().toString()).toList()
                        + " ids=" + batch.runs().stream().map(run -> run.routes().get(0).pointIds()).toList()
                        + " closure=" + batch.network().closure() + " outcomes=" + result.intervals());
        assertTrue(result.applyAvailable());
        var plan = result.plan().orElseThrow();
        assertEquals(Set.of(batch.fullRequest().selectedWayKey()), plan.affectedWayKeys());
        assertEquals(plan.finalPreviewWays().get(plan.selectedWayKey()), result.selectedWayPreview());
        var selected = (DetachedWay) batch.network().primitives().get(plan.selectedWayKey());
        assertEquals(selected.nodeKeys().get(3),
                ((DetachedWay) plan.after().primitives().get(plan.selectedWayKey())).nodeKeys()
                        .stream().filter(key -> key.equals(selected.nodeKeys().get(3))).findFirst()
                        .orElseThrow());
        assertEquals(batch.network().primitives().get(selected.nodeKeys().get(3)),
                plan.after().primitives().get(selected.nodeKeys().get(3)));
        assertFalse(result.assignments().isEmpty());
        assertTrue(List.of(2, 4).stream().anyMatch(index -> !batch.network().primitives()
                .get(selected.nodeKeys().get(index)).equals(
                        plan.after().primitives().get(selected.nodeKeys().get(index)))),
                "at least one ordinary movable existing node must actually move");
    }

    @Test
    void intervalProductionArtifactPreservesComposedPreviewAndDoesNotClaimReplay(
            @TempDir Path directory) throws Exception {
        IntervalTraceBatch batch = batch(296.0, 296.0, TrackerMode.CORRIDOR_AWARE, true);
        assertTrue(batch.runs().get(0).routes().size() > 1);
        // Exercise direct optional codec admission using this already-produced public
        // batch: later-run privacy must refuse even when the byte allowance is zero.
        assertEquals(2, batch.runs().size());
        Class<?> codec = Class.forName("org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15."
                + "IntervalFinalOutputComponentsCodec");
        var encode = codec.getDeclaredMethod("encode", String.class, IntervalTraceBatch.class, int.class, long.class);
        encode.setAccessible(true);
        Object budgetOnly = encode.invoke(null, "test-build", batch, 0, 0L);
        var availability = budgetOnly.getClass().getDeclaredMethod("availability");
        availability.setAccessible(true);
        assertEquals("UNAVAILABLE_BUDGET", availability.invoke(budgetOnly).toString());
        var originalRun = batch.runs().get(1);
        var originalInference = originalRun.result().inference();
        var unsafeInference = new TraceHypothesisSet(originalInference.engine(), originalInference.hypotheses(),
                originalInference.status(), originalInference.alternativesTruncated(),
                originalInference.evaluatedStates(), originalInference.evaluatedTransitions(),
                "Cookie: public-synthetic-refusal-control");
        List<IntervalTraceBatch.IntervalRun> unsafeRuns = new java.util.ArrayList<>(batch.runs());
        unsafeRuns.set(1, new IntervalTraceBatch.IntervalRun(originalRun.interval(), originalRun.request(),
                new ModernTracePipeline.Result(unsafeInference, originalRun.routes()), originalRun.usage()));
        var unsafeBatch = new IntervalTraceBatch(batch.fullRequest(), batch.evidence(), batch.network(),
                batch.partition(), unsafeRuns, batch.options(), batch.authoritySpecification());
        var refused = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> encode.invoke(null, "test-build", unsafeBatch, 0, 0L));
        assertTrue(refused.getCause() instanceof IllegalArgumentException);
        assertTrue(refused.getCause().getMessage().contains("Credential-bearing"));
        var originalRoute = originalRun.routes().get(0);
        var quality = originalRoute.quality();
        var nonfiniteQuality = new FinalGeometryEvaluator.Result(quality.id(), quality.disposition(),
                quality.findings(), quality.totalLengthMeters(), quality.directlySupportedLengthMeters(),
                quality.worstUnsupportedSpanMeters(), quality.meanImageCenterCost(), Double.NaN);
        List<ModernTracePipeline.Route> malformedRoutes = new java.util.ArrayList<>(originalRun.routes());
        malformedRoutes.set(0, new ModernTracePipeline.Route(originalRoute.rawHypothesis(), originalRoute.hypothesis(),
                originalRoute.pointIds(), originalRoute.assignments(), originalRoute.sourceOwnership(),
                nonfiniteQuality, originalRoute.cleanupStatus(), originalRoute.geometryChanged()));
        unsafeRuns.set(1, new IntervalTraceBatch.IntervalRun(originalRun.interval(), originalRun.request(),
                new ModernTracePipeline.Result(originalInference, malformedRoutes), originalRun.usage()));
        var malformedBatch = new IntervalTraceBatch(batch.fullRequest(), batch.evidence(), batch.network(),
                batch.partition(), unsafeRuns, batch.options(), batch.authoritySpecification());
        var malformed = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> encode.invoke(null, "test-build", malformedBatch, 0, 128L * 1024 * 1024 - 1));
        assertTrue(malformed.getCause() instanceof IllegalArgumentException);
        assertEquals("final-output-invalid", malformed.getCause().getMessage());
        Map<Integer, Integer> routeChoices = Map.of(0, 1);
        var assessment = new FixedIntervalEditPlanComposer().compose(batch, routeChoices);
        var plan = assessment.plan().orElseThrow();

        var receipt = new Format15ProductionBundleFactory.ManagedTileSourceReceipt(
                41L, 15, "a".repeat(64));
        var bundle = Format15ProductionBundleFactory.createLiveIntervals("test-build", batch,
                assessment, routeChoices, receipt,
                Format15ProductionBundleFactory.IntervalArtifactStatus.CONFIRMED,
                plan.canonicalHash(), null);
        Path file = directory.resolve("intervals.zip");
        Format15BundleWriter.write(bundle, file);
        Format15Archive decoded = Format15ArchiveReader.read(file);
        String index = new String(decoded.artifact("interval-production.json").orElseThrow()
                .bytes(), java.nio.charset.StandardCharsets.UTF_8);
        String geometry = new String(decoded.artifact("private/interval-composed-preview.json")
                .orElseThrow().bytes(), java.nio.charset.StandardCharsets.UTF_8);
        String provenance = new String(decoded.artifact("private/interval-point-provenance.json")
                .orElseThrow().bytes(), java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(index.contains("\"chosenRouteIndex\":1"));
        for (var route : batch.runs().get(0).routes()) {
            assertTrue(index.contains(route.hypothesis().id()));
        }
        assertTrue(index.contains("\"occurrenceRange\""));
        assertTrue(index.contains(plan.canonicalHash()));
        assertTrue(index.contains("MANAGED_TILES"));
        assertTrue(index.contains("\"generation\":41"));
        assertTrue(index.contains("\"FINAL_GEOMETRY\":false"));
        assertTrue(index.endsWith("]}\n"));
        assertTrue(geometry.contains("\"ways\""));
        assertFalse(index.contains("\"latitude\""));
        for (int intervalIndex = 0; intervalIndex < batch.runs().size(); intervalIndex++) {
            String intervalRoutes = new String(decoded.artifact("private/interval-" + intervalIndex
                    + "-routes.json").orElseThrow().bytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(intervalRoutes.contains("\"intervalIndex\":" + intervalIndex));
            assertTrue(intervalRoutes.contains("\"alternatives\""));
            for (var route : batch.runs().get(intervalIndex).routes()) {
                assertTrue(intervalRoutes.contains(route.hypothesis().id()));
            }
        }
        assertTrue(provenance.contains("\"kind\":\"EXISTING_WAY_NODE_OCCURRENCE\""));
        assertTrue(provenance.contains("\"kind\":\"GENERATED_CANDIDATE_POINT\""));
        Pattern point = Pattern.compile("\\{\\\"sequence\\\":(\\d+),\\\"wayKey\\\":\\\"([^\\\"]+)\\\","
                + "\\\"pointId\\\":(\\{.*?\\}),\\\"ownerInterval\\\":(null|\\d+),"
                + "\\\"latitude\\\":([^,]+),\\\"longitude\\\":([^}]+)\\}");
        Matcher matcher = point.matcher(provenance);
        Map<String, List<IndexedCoordinate>> decodedWays = new LinkedHashMap<>();
        boolean fixedIslandPointFound = false;
        int generatedPointCount = 0;
        while (matcher.find()) {
            String pointId = matcher.group(3);
            String owner = matcher.group(4);
            if (pointId.contains("GENERATED_CANDIDATE_POINT")) {
                generatedPointCount++;
                assertFalse("null".equals(owner), "generated final points must have an interval owner");
                assertTrue(Integer.parseInt(owner) < batch.runs().size());
            }
            if (pointId.contains("\"occurrenceIndex\":3")) {
                fixedIslandPointFound = true;
                assertEquals("null", owner, "fixed island points have no slide-interval owner");
            }
            decodedWays.computeIfAbsent(matcher.group(2), ignored -> new ArrayList<>())
                    .add(new IndexedCoordinate(Integer.parseInt(matcher.group(1)),
                            new GeographicPoint(Double.parseDouble(matcher.group(5)),
                                    Double.parseDouble(matcher.group(6)))));
        }
        assertTrue(generatedPointCount > 0, "fixture should exercise generated candidate identities");
        assertTrue(fixedIslandPointFound, "fixture should retain fixed island occurrence 3");
        for (var way : plan.finalPreviewWays().entrySet()) {
            List<IndexedCoordinate> decodedWay = decodedWays.get(way.getKey().toString());
            assertTrue(decodedWay != null, "private provenance omitted way " + way.getKey());
            decodedWay.sort(Comparator.comparingInt(IndexedCoordinate::index));
            assertEquals(way.getValue(), decodedWay.stream().map(IndexedCoordinate::coordinate).toList(),
                    "private point provenance must reconstruct every displayed way exactly");
        }

        var augmentedPlan = withUnownedPlanLocalPreviewPoint(plan);
        var augmentedAssessment = new FixedIntervalEditPlanComposer.Assessment(
                java.util.Optional.of(augmentedPlan), assessment.selectedWayPreview(),
                assessment.assignments(), assessment.intervals());
        assertThrows(IllegalArgumentException.class, () ->
                Format15ProductionBundleFactory.createLiveIntervals("test-build", batch,
                        augmentedAssessment, routeChoices, receipt,
                        Format15ProductionBundleFactory.IntervalArtifactStatus.PREVIEW, null, null),
                "an unassigned plan-local point on the selected way must fail closed");
        assertFalse(decoded.capability().supports(ReplayLevel.SCALAR_INFERENCE));
        assertFalse(decoded.capability().supports(ReplayLevel.FINAL_GEOMETRY));
        assertFalse(decoded.capability().supports(ReplayLevel.FULL_EDIT_PLAN));
        assertEquals(bundle.artifact("interval-production.json").sha256(),
                decoded.artifact("interval-production.json").orElseThrow().sha256());
        assertThrows(IllegalArgumentException.class, () ->
                Format15ProductionBundleFactory.createLiveIntervals("test-build", batch,
                        assessment, routeChoices, new Format15ProductionBundleFactory.ManagedTileSourceReceipt(
                                0L, 15, "receipt:access_token=secret"),
                        Format15ProductionBundleFactory.IntervalArtifactStatus.PREVIEW, null, null));
        assertThrows(IllegalArgumentException.class, () ->
                new Format15ProductionBundleFactory.ManagedTileSourceReceipt(
                        0L, 15, "api_key=secret"));
        assertThrows(IllegalArgumentException.class, () ->
                Format15ProductionBundleFactory.createLiveIntervals("test-build", batch,
                        assessment, routeChoices, receipt,
                        Format15ProductionBundleFactory.IntervalArtifactStatus.CANCELLED,
                        plan.canonicalHash(), plan.canonicalHash()));
        assertThrows(IllegalArgumentException.class, () ->
                Format15ProductionBundleFactory.createLiveIntervals("test-build", batch,
                        assessment, routeChoices, receipt,
                        Format15ProductionBundleFactory.IntervalArtifactStatus.PREVIEW,
                        plan.canonicalHash(), null));
        assertThrows(IllegalArgumentException.class, () ->
                Format15ProductionBundleFactory.createLiveIntervals("test-build", batch,
                        assessment, routeChoices, receipt,
                        Format15ProductionBundleFactory.IntervalArtifactStatus.REVIEWED, "wrong-plan", null));
        var wrongSettingsPlan = new org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan(
                plan.selectedWayKey(), plan.selectedRange(), plan.before(), plan.after(),
                plan.metricFrame(), plan.permissions(), plan.settingsHash() + "-wrong",
                plan.evidenceHash(), plan.parameterHash(), plan.routeIdentity(),
                plan.finalPreviewWays(), plan.validation());
        var wrongSettingsAssessment = new FixedIntervalEditPlanComposer.Assessment(
                java.util.Optional.of(wrongSettingsPlan), assessment.selectedWayPreview(),
                assessment.assignments(), assessment.intervals());
        assertThrows(IllegalArgumentException.class, () ->
                Format15ProductionBundleFactory.createLiveIntervals("test-build", batch,
                        wrongSettingsAssessment, routeChoices, receipt,
                        Format15ProductionBundleFactory.IntervalArtifactStatus.PREVIEW, null, null));
        var visibleBundle = Format15ProductionBundleFactory.createLiveIntervals("test-build", batch,
                assessment, routeChoices,
                Format15ProductionBundleFactory.VisibleLayerSourceReceipt.unavailable(),
                Format15ProductionBundleFactory.IntervalArtifactStatus.PREVIEW, null, null);
        String visibleIndex = new String(visibleBundle.artifact("interval-production.json").bytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(visibleIndex.contains("\"kind\":\"VISIBLE_RENDERED_LAYER\",\"revision\":null,\"zoom\":null"));
    }

    private static org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan
            withUnownedPlanLocalPreviewPoint(
                    org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan plan) {
        var selectedKey = plan.selectedWayKey();
        var afterValues = new LinkedHashMap<>(plan.after().primitives());
        var selected = (DetachedWay) afterValues.get(selectedKey);
        var generatedKey = org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.planned(
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.Type.NODE,
                afterValues.keySet().stream()
                        .filter(key -> key.identityKind()
                                == org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.IdentityKind.PLAN_LOCAL)
                        .mapToLong(org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey::id)
                        .max().orElse(0L) + 100L);
        var preview = new LinkedHashMap<>(plan.finalPreviewWays());
        List<GeographicPoint> points = new ArrayList<>(preview.get(selectedKey));
        GeographicPoint left = points.get(1);
        GeographicPoint right = points.get(2);
        GeographicPoint inserted = new GeographicPoint(
                (left.latitudeDegrees() + right.latitudeDegrees()) / 2.0,
                (left.longitudeDegrees() + right.longitudeDegrees()) / 2.0);
        points.add(2, inserted);
        preview.put(selectedKey, List.copyOf(points));
        List<org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey> nodes =
                new ArrayList<>(selected.nodeKeys());
        nodes.add(2, generatedKey);
        afterValues.put(selectedKey, new DetachedWay(selected.key(), nodes, selected.tags(),
                selected.deleted(), true));
        afterValues.put(generatedKey, new DetachedNode(generatedKey, inserted, Map.of(), false, true));
        var watches = new LinkedHashMap<>(plan.after().incomingReferrerWatches());
        watches.put(generatedKey, Set.of(selectedKey));
        var after = new org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot(
                "interval-topology-point-after", plan.after().role(), plan.after().datasetIdentity(),
                plan.after().sourceGeneration(), plan.after().closure(), afterValues, watches);
        return new org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan(
                plan.selectedWayKey(), plan.selectedRange(), plan.before(), after,
                plan.metricFrame(), plan.permissions(), plan.settingsHash(), plan.evidenceHash(),
                plan.parameterHash(), plan.routeIdentity(), preview, plan.validation());
    }

    @Test
    void coincidentOriginalOccurrencesOwnOnlyTheirIncidentSourcePieces() throws Exception {
        var fixture = coincidentSourceProofFixture();
        var batch = fixture.batch();
        var selected = (DetachedWay) batch.network().primitives().get(batch.fullRequest().selectedWayKey());
        var b = new FinalRoutePointId.ExistingWayNodeOccurrence(selected.key(), selected.nodeKeys().get(2), 2);
        var c = new FinalRoutePointId.ExistingWayNodeOccurrence(selected.key(), selected.nodeKeys().get(3), 3);
        assertNotEquals(b.nodeKey(), c.nodeKey());
        assertEquals(originalMetric(batch, 2), originalMetric(batch, 3), "Distinct B/C occurrences share a coordinate");
        var assignments = Map.<FinalRoutePointId, MetricPoint>of(b, originalMetric(batch, 2),
                c, originalMetric(batch, 3), fixture.generated(), fixture.point());
        var forbidden = OriginalSourceSubdivisionVerifier.verify(batch, List.of(b, fixture.generated()), assignments,
                List.of(b.nodeKey(), fixture.generatedKey()), fixture.after(), Map.of());
        assertTrue(forbidden.isEmpty(), "B to generated on C-D crosses distinct original occurrence C");
        var permitted = OriginalSourceSubdivisionVerifier.verify(batch, List.of(c, fixture.generated()), assignments,
                List.of(c.nodeKey(), fixture.generatedKey()), fixture.after(), Map.of());
        assertEquals(Set.of(new OriginalSourceSubdivisionVerifier.Edge(c.nodeKey(), fixture.generatedKey())), permitted,
                "Actual native source subdivision of C-D remains provable");
    }

    @Test
    void generatedAnchorAtCoincidentOriginalOccurrencesFailsClosed() throws Exception {
        var fixture = coincidentSourceProofFixture();
        var batch = fixture.batch();
        var route = batch.runs().get(1).routes().get(0);
        var existingC = existingPointId(route, 3);
        var originalC = route.assignments().get(existingC);
        assertEquals(route.rawHypothesis().points().get(0), originalC);
        // Typed identity forgery keeps the actual native final geometry/quality unchanged.
        var rawStart = new GeneratedCandidatePoint(route.rawHypothesis().id(), 0);
        var pointIds = new ArrayList<>(route.pointIds());
        pointIds.set(pointIds.indexOf(existingC), rawStart);
        var finalAssignments = new LinkedHashMap<>(route.assignments());
        finalAssignments.remove(existingC); finalAssignments.put(rawStart, originalC);
        var ownership = new LinkedHashMap<>(route.sourceOwnership());
        ownership.put(rawStart, ownership.remove(existingC));
        var forged = new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(), pointIds,
                finalAssignments, ownership, route.quality(), route.cleanupStatus(), route.geometryChanged());
        var generatedC = new GeneratedCandidatePoint("interval-1:" + route.hypothesis().id(), 1_000_000);
        var generatedCKey = PrimitiveKey.planned(PrimitiveKey.Type.NODE, 900_000);
        var after = new LinkedHashMap<>(fixture.after());
        after.put(generatedCKey, new DetachedNode(generatedCKey, batch.evidence().coordinateFrame().toGeographic(originalC),
                Map.of(), false, true));
        var assignments = Map.<FinalRoutePointId, MetricPoint>of(generatedC, originalC,
                fixture.generated(), fixture.point());
        assertTrue(OriginalSourceSubdivisionVerifier.verify(withRoute(batch, 1, forged),
                List.of(generatedC, fixture.generated()), assignments,
                List.of(generatedCKey, fixture.generatedKey()), after, Map.of()).isEmpty(),
                "Generated anchor cannot choose between repeated-chainage original occurrences");
    }

    private record CoincidentSourceProofFixture(IntervalTraceBatch batch, GeneratedCandidatePoint generated,
            PrimitiveKey generatedKey, MetricPoint point, Map<PrimitiveKey, DetachedPrimitive> after) { }

    private static CoincidentSourceProofFixture coincidentSourceProofFixture() throws Exception {
        var batch = batch(300.0, 300.0, TrackerMode.PROBABILISTIC, false, false, false, true, false, true);
        var route = batch.runs().get(1).routes().get(0);
        var generated = route.pointIds().stream().filter(GeneratedCandidatePoint.class::isInstance)
                .map(GeneratedCandidatePoint.class::cast).filter(id -> id.originalPointIndex() == 1).findFirst().orElseThrow();
        var point = route.assignments().get(generated);
        assertEquals(route.rawHypothesis().points().get(1), point);
        var anchors = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic
                .ProbabilisticProfileFactory().sourceSamplingGeometry(List.of(originalMetric(batch, 3),
                        originalMetric(batch, 4), originalMetric(batch, 5)), batch.fullRequest().configuredSampleStepMeters());
        assertEquals(anchors.points().get(1), point, "Actual production raw/final/native source anchor");
        var composite = new GeneratedCandidatePoint("interval-1:" + route.hypothesis().id(), 1_000_001);
        var key = PrimitiveKey.planned(PrimitiveKey.Type.NODE, 900_001);
        var after = new LinkedHashMap<>(batch.network().primitives());
        after.put(key, new DetachedNode(key, batch.evidence().coordinateFrame().toGeographic(point), Map.of(), false, true));
        return new CoincidentSourceProofFixture(batch, composite, key, point, Map.copyOf(after));
    }

    @Test
    void sourceSubdivisionProofDoesNotProjectRemoteUnselectedWayEndpoints() throws Exception {
        var batch = batch(300.0, 300.0, TrackerMode.PROBABILISTIC, false, false, false, true, true);
        var selected = (DetachedWay) batch.network().primitives().get(batch.fullRequest().selectedWayKey());
        var remote = (DetachedNode) batch.network().primitives().get(selected.nodeKeys().get(0));
        assertThrows(IllegalArgumentException.class, () -> batch.evidence().coordinateFrame().toMetric(remote.coordinate()),
                "Fixture must have a factual unselected endpoint outside the selected frame");
        var result = new FixedIntervalEditPlanComposer().compose(batch, Map.of());
        assertTrue(result.plan().isPresent(), () -> "Unselected remote geometry is not sampling input for this proof: "
                + result.intervals().stream().map(FixedIntervalEditPlanComposer.IntervalAssessment::reason).toList());
        assertEquals(remote.coordinate(), ((DetachedNode) result.plan().orElseThrow().after().primitives()
                .get(remote.key())).coordinate());
    }

    @Test
    void productionZeroOffsetBoundarySubdivisionPreservesOriginalCurveWithoutNewSupport() throws Exception {
        IntervalTraceBatch batch = batch(300.0, 300.0, TrackerMode.PROBABILISTIC,
                false, false, false, true);
        var route = batch.runs().get(0).routes().get(0);
        var selected = (DetachedWay) batch.network().primitives().get(batch.fullRequest().selectedWayKey());
        var first = batch.evidence().coordinateFrame().toMetric(((DetachedNode) batch.network()
                .primitives().get(selected.nodeKeys().get(1))).coordinate());
        var second = batch.evidence().coordinateFrame().toMetric(((DetachedNode) batch.network()
                .primitives().get(selected.nodeKeys().get(2))).coordinate());
        var third = batch.evidence().coordinateFrame().toMetric(((DetachedNode) batch.network()
                .primitives().get(selected.nodeKeys().get(3))).coordinate());
        var generated = route.pointIds().stream().filter(GeneratedCandidatePoint.class::isInstance)
                .map(GeneratedCandidatePoint.class::cast).toList();
        var nativeAnchors = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic
                .ProbabilisticProfileFactory().sourceSamplingGeometry(List.of(first, second, third),
                        batch.runs().get(0).request().configuredSampleStepMeters());
        assertFalse(generated.isEmpty(), "Public production fixture must actually create a subdivision");
        assertEquals(first, route.assignments().get(route.pointIds().get(0)), "Existing endpoint stays exact");
        for (var id : generated) {
            double target = batch.runs().get(0).request().profileChainage().cumulativeGroundMeters()
                    .get(id.originalPointIndex());
            boolean firstPiece = target <= first.distanceTo(second);
            var start = firstPiece ? first : second;
            var end = firstPiece ? second : third;
            double fraction = (target - (firstPiece ? 0.0 : first.distanceTo(second))) / start.distanceTo(end);
            var expected = new MetricPoint(start.xMeters() + fraction * (end.xMeters() - start.xMeters()),
                    start.yMeters() + fraction * (end.yMeters() - start.yMeters()));
            assertEquals(expected, route.assignments().get(id), "Actual production zero-offset source point");
            assertEquals(nativeAnchors.points().get(id.originalPointIndex()), route.assignments().get(id));
            assertEquals(route.rawHypothesis().points().get(id.originalPointIndex()), route.assignments().get(id));
        }
        assertTrue(route.quality().directlySupportedLengthMeters() > 0.0);
        assertEquals(FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED, route.quality().disposition());
        var result = new FixedIntervalEditPlanComposer().compose(batch, Map.of());
        assertEquals(FixedIntervalEditPlanComposer.Disposition.CHANGED, result.intervals().get(0).disposition(),
                "Original straight-piece subdivision needs no new positional authorization");
        assertEquals(ValidationReport.Disposition.REVIEW_REQUIRED, result.plan().orElseThrow().validation().disposition());

        // Exercise the proof with native production assignments and the actual stored plan.
        var plan = result.plan().orElseThrow();
        var ids = route.pointIds().stream().map(id -> id instanceof GeneratedCandidatePoint point
                ? (FinalRoutePointId) new GeneratedCandidatePoint("interval-0:" + route.hypothesis().id(),
                        point.originalPointIndex()) : id).toList();
        Map<FinalRoutePointId, MetricPoint> assignments = new LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            assignments.put(ids.get(index), route.assignments().get(route.pointIds().get(index)));
        }
        var keys = ((DetachedWay) plan.after().primitives().get(selected.key())).nodeKeys()
                .subList(batch.fullRequest().selectedRange().firstIndex(),
                        batch.fullRequest().selectedRange().firstIndex() + ids.size());
        var proven = OriginalSourceSubdivisionVerifier.verify(batch, ids, assignments, keys,
                plan.after().primitives(), Map.of());
        int generatedIndex = 1;
        while (!(ids.get(generatedIndex) instanceof GeneratedCandidatePoint)
                || !proven.contains(new OriginalSourceSubdivisionVerifier.Edge(
                        keys.get(generatedIndex - 1), keys.get(generatedIndex)))) generatedIndex++;
        var edge = new OriginalSourceSubdivisionVerifier.Edge(keys.get(generatedIndex - 1), keys.get(generatedIndex));
        var pointId = ids.get(generatedIndex);
        var originalPoint = assignments.get(pointId);
        var oneUlp = new MetricPoint(Math.nextUp(originalPoint.xMeters()), originalPoint.yMeters());
        Map<FinalRoutePointId, MetricPoint> moved = new LinkedHashMap<>(assignments);
        moved.put(pointId, oneUlp);
        assertFalse(OriginalSourceSubdivisionVerifier.verify(batch, ids, moved, keys,
                plan.after().primitives(), Map.of()).contains(edge), "One ULP is real movement");
        var raw = route.rawHypothesis();
        List<MetricPoint> wrongRawPoints = new ArrayList<>(raw.points());
        wrongRawPoints.set(((GeneratedCandidatePoint) pointId).originalPointIndex(), oneUlp);
        var wrongRaw = new TraceHypothesis(raw.id(), raw.branchSignature(), wrongRawPoints, raw.support(),
                raw.objective(), raw.posteriorProbability(), raw.diagnostics());
        var forgedNative = new ModernTracePipeline.Route(wrongRaw, route.hypothesis(), route.pointIds(),
                route.assignments(), route.sourceOwnership(), route.quality(), route.cleanupStatus(), route.geometryChanged());
        assertFalse(OriginalSourceSubdivisionVerifier.verify(withRoute(batch, 0, forgedNative), ids, assignments,
                keys, plan.after().primitives(), Map.of()).contains(edge), "Native raw geometry must also be exact");
        var nativeRequest = batch.runs().get(0).request();
        var changedRequest = new org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest(
                nativeRequest.selectedWayKey(), nativeRequest.selectedRange(), nativeRequest.engine(),
                nativeRequest.geometryMode(), nativeRequest.permissions(), nativeRequest.budgets(),
                nativeRequest.evidenceSnapshotId(), nativeRequest.evidenceContentHash(), nativeRequest.networkSnapshotId(),
                nativeRequest.networkContentHash(), "changed-settings", nativeRequest.parameterHash(), nativeRequest.samplerId(),
                nativeRequest.configuredSampleStepMeters(), nativeRequest.profileChainage(), nativeRequest.evidenceResolution(),
                nativeRequest.corridorInput());
        var changedRuns = new ArrayList<>(batch.runs());
        changedRuns.set(0, new IntervalTraceBatch.IntervalRun(batch.runs().get(0).interval(), changedRequest,
                batch.runs().get(0).result(), batch.runs().get(0).usage()));
        var changedNative = new IntervalTraceBatch(batch.fullRequest(), batch.evidence(), batch.network(),
                batch.partition(), changedRuns, batch.options(), batch.authoritySpecification());
        assertFalse(OriginalSourceSubdivisionVerifier.verify(changedNative, ids, assignments, keys,
                plan.after().primitives(), Map.of()).contains(edge), "Native request must reconstruct from frozen source");
        var evidence = batch.evidence();
        var oldFrame = evidence.coordinateFrame();
        var certificate = oldFrame.distortionCertificate();
        var staleFrame = org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame
                .legacyCertifiedEquirectangular(oldFrame.origin(), certificate.southWest(), certificate.northEast());
        var staleEvidence = new org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot(
                evidence.snapshotId(), staleFrame, evidence.transform(), evidence.resolution(), evidence.decisionRegion(),
                evidence.evidenceRegion(), evidence.fields(), evidence.resampling(), evidence.sourceIdentity());
        assertThrows(IllegalArgumentException.class, () -> new IntervalTraceBatch(batch.fullRequest(), staleEvidence,
                batch.network(), batch.partition(), batch.runs(), batch.options(), batch.authoritySpecification()),
                "Stale source frame cannot enter native proof with an old evidence binding");
        List<FinalRoutePointId> forged = new ArrayList<>(ids);
        var generatedId = (GeneratedCandidatePoint) pointId;
        forged.set(generatedIndex, new GeneratedCandidatePoint(generatedId.candidateId(),
                route.rawHypothesis().points().size() + 1));
        assertFalse(OriginalSourceSubdivisionVerifier.verify(batch, forged, assignments, keys,
                plan.after().primitives(), Map.of()).contains(edge), "Raw index must be native and bound");
        var reversedIds = List.of(ids.get(generatedIndex), ids.get(generatedIndex - 1));
        var reversedKeys = List.of(keys.get(generatedIndex), keys.get(generatedIndex - 1));
        assertTrue(OriginalSourceSubdivisionVerifier.verify(batch, reversedIds, assignments, reversedKeys,
                plan.after().primitives(), Map.of()).isEmpty(), "Source chainage must be increasing");
        assertTrue(OriginalSourceSubdivisionVerifier.verify(batch, List.of(pointId, pointId), assignments,
                List.of(keys.get(generatedIndex), keys.get(generatedIndex)), plan.after().primitives(), Map.of())
                .isEmpty(), "Duplicate anchors cannot authorize edges");
        Map<PrimitiveKey, DetachedPrimitive> changedAfter = new LinkedHashMap<>(plan.after().primitives());
        var generatedNode = (DetachedNode) changedAfter.get(keys.get(generatedIndex));
        var geographic = generatedNode.coordinate();
        changedAfter.put(generatedNode.key(), new DetachedNode(generatedNode.key(),
                new GeographicPoint(Math.nextUp(geographic.latitudeDegrees()), geographic.longitudeDegrees()),
                generatedNode.tags(), generatedNode.deleted(), generatedNode.modified()));
        assertFalse(OriginalSourceSubdivisionVerifier.verify(batch, ids, assignments, keys, changedAfter, Map.of())
                .contains(edge), "Generated geographic coordinates must be canonical frame conversion");
        var firstId = ids.get(0);
        var firstKey = keys.get(0);
        var endpoint = (DetachedNode) plan.after().primitives().get(firstKey);
        changedAfter = new LinkedHashMap<>(plan.after().primitives());
        changedAfter.put(firstKey, new DetachedNode(firstKey,
                new GeographicPoint(Math.nextUp(endpoint.coordinate().latitudeDegrees()),
                        endpoint.coordinate().longitudeDegrees()), endpoint.tags(), endpoint.deleted(), endpoint.modified()));
        assertTrue(OriginalSourceSubdivisionVerifier.verify(batch, List.of(firstId, ids.get(1)), assignments,
                keys.subList(0, 2), changedAfter, Map.of()).isEmpty(), "Existing endpoint keeps original geographic bits");
        var lastId = new FinalRoutePointId.ExistingWayNodeOccurrence(selected.key(), selected.nodeKeys().get(3), 3);
        Map<FinalRoutePointId, MetricPoint> shortcut = Map.of(firstId, first, lastId, third);
        assertTrue(OriginalSourceSubdivisionVerifier.verify(batch, List.of(firstId, lastId), shortcut,
                List.of(firstKey, selected.nodeKeys().get(3)), plan.after().primitives(), Map.of()).isEmpty(),
                "Even straight source vertices cannot be crossed by this proof");
    }

    @Test
    void productionNativeInteriorGapRetainsExactReviewPlan() throws Exception {
        var batch = batch(296.0, 296.0, TrackerMode.PROBABILISTIC, false, false, true);
        var route = batch.runs().get(0).routes().get(0);
        var quality = route.quality();
        assertEquals(FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED, quality.disposition());
        assertTrue(quality.directlySupportedLengthMeters() > 0.0);
        assertEquals(Double.POSITIVE_INFINITY, quality.meanImageCenterCost());
        assertFalse(quality.findings().stream().anyMatch(finding ->
                finding.severity() == FinalGeometryEvaluator.Severity.HARD_BLOCK));
        var source = new ArrayList<MetricPoint>();
        for (int occurrence = batch.runs().get(0).request().selectedRange().firstIndex();
                occurrence <= batch.runs().get(0).request().selectedRange().lastIndex(); occurrence++) {
            source.add(originalMetric(batch, occurrence));
        }
        var anchors = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic
                .ProbabilisticProfileFactory().sourceSamplingGeometry(source,
                        batch.runs().get(0).request().configuredSampleStepMeters());
        var image = new ImageCostField(batch.evidence().fields().get(batch.options().fieldName()),
                batch.evidence().transform(), batch.evidence().decisionRegion(), batch.evidence().resolution().effectivePitchMeters());
        var points = route.pointIds().stream().map(route.assignments()::get).toList();
        List<Boolean> sampledDirect = new ArrayList<>(); List<Integer> sampledEdges = new ArrayList<>();
        for (int edge = 1; edge < points.size(); edge++) {
            var a = points.get(edge - 1); var b = points.get(edge);
            var tangent = new MetricPoint(b.xMeters() - a.xMeters(), b.yMeters() - a.yMeters());
            int count = Math.max(1, (int) Math.ceil(a.distanceTo(b)
                    / Math.min(1.0, batch.evidence().resolution().effectivePitchMeters() / 2.0)));
            for (int sample = 0; sample < count; sample++) {
                double fraction = (sample + 0.5) / count;
                var point = new MetricPoint(a.xMeters() + fraction * tangent.xMeters(),
                        a.yMeters() + fraction * tangent.yMeters());
                sampledDirect.add(image.sampleRoute(point, tangent).map(value ->
                        value.ownership() == ObservationOwnership.DIRECT_TWO_SIDED).orElse(false));
                sampledEdges.add(edge);
            }
        }
        int firstDirect = sampledDirect.indexOf(true), lastDirect = sampledDirect.lastIndexOf(true);
        boolean movedBoundedNativeWitness = false;
        for (int edge = 2; edge < points.size() - 1; edge++) {
            var left = route.pointIds().get(edge - 1); var right = route.pointIds().get(edge);
            var leftSource = left instanceof GeneratedCandidatePoint generated
                    ? anchors.points().get(generated.originalPointIndex())
                    : originalMetric(batch, ((FinalRoutePointId.ExistingWayNodeOccurrence) left).originalOccurrenceIndex());
            var rightSource = right instanceof GeneratedCandidatePoint generated
                    ? anchors.points().get(generated.originalPointIndex())
                    : originalMetric(batch, ((FinalRoutePointId.ExistingWayNodeOccurrence) right).originalOccurrenceIndex());
            boolean moved = !points.get(edge - 1).equals(leftSource) || !points.get(edge).equals(rightSource);
            int firstSample = sampledEdges.indexOf(edge), lastSample = sampledEdges.lastIndexOf(edge);
            boolean noDirect = !sampledDirect.subList(firstSample, lastSample + 1).contains(true);
            movedBoundedNativeWitness |= moved && noDirect && firstSample > firstDirect && lastSample < lastDirect;
        }
        assertTrue(movedBoundedNativeWitness, "Fixture must contain a genuinely moved native FINAL body edge with direct brackets");
        var state = new AlignWayAction.IntervalPreviewState(batch);
        assertEquals(FixedIntervalEditPlanComposer.Disposition.CHANGED,
                state.assessment().intervals().get(0).disposition(),
                "Retained native body gap with physical review support is not a new connector: "
                        + state.assessment().intervals().get(0).reason().split(":", 2)[0]);
        var plan = state.assessment().plan().orElseThrow();
        assertEquals(ValidationReport.Disposition.REVIEW_REQUIRED, plan.validation().disposition());
        for (var finding : quality.findings()) {
            assertTrue(plan.validation().findingCodes().contains("interval-0:" + finding.code().name()));
        }
        assertFalse(state.applyAvailable());
        assertThrows(IllegalStateException.class, state::currentPlanForApply);
        state.confirmReview();
        assertEquals(plan.canonicalHash(), state.currentPlanForApply().canonicalHash());
        for (var island : batch.partition().fixedIslands()) {
            for (var key : island.occurrenceKeys()) assertEquals(batch.network().primitives().get(key),
                    plan.after().primitives().get(key));
        }
        assertEquals(Double.POSITIVE_INFINITY, route.quality().meanImageCenterCost());
    }

    @Test
    void retainedNativeInteriorProofRejectsChangedGeometryIdentityAndBorrowedBrackets() throws Exception {
        var batch = batch(296.0, 296.0, TrackerMode.PROBABILISTIC, false, false, true);
        var view = nativeInteriorView(batch);
        var proved = NativeInteriorSupportVerifier.verify(batch, view.ids(), view.assignments(), view.keys(), view.after(), Map.of());
        assertFalse(proved.isEmpty());
        var edge = proved.iterator().next();
        int left = view.keys().indexOf(edge.left()), right = left + 1;
        var id = view.ids().get(left);
        var point = view.assignments().get(id);
        var changed = new LinkedHashMap<>(view.assignments());
        changed.put(id, new MetricPoint(Math.nextUp(point.xMeters()), point.yMeters()));
        assertFalse(NativeInteriorSupportVerifier.verify(batch, view.ids(), changed, view.keys(), view.after(), Map.of()).contains(edge));
        var changedAfter = new LinkedHashMap<>(view.after());
        var node = (DetachedNode) changedAfter.get(edge.left());
        changedAfter.put(node.key(), new DetachedNode(node.key(), new GeographicPoint(
                Math.nextUp(node.coordinate().latitudeDegrees()), node.coordinate().longitudeDegrees()), node.tags(), node.deleted(), node.modified()));
        assertFalse(NativeInteriorSupportVerifier.verify(batch, view.ids(), view.assignments(), view.keys(), changedAfter, Map.of()).contains(edge));
        var reorderedIds = new ArrayList<>(view.ids());
        var reorderedKeys = new ArrayList<>(view.keys());
        java.util.Collections.swap(reorderedIds, left, right); java.util.Collections.swap(reorderedKeys, left, right);
        assertFalse(NativeInteriorSupportVerifier.verify(batch, reorderedIds, view.assignments(), reorderedKeys, view.after(), Map.of()).contains(edge));
        var duplicatedIds = new ArrayList<>(view.ids()); duplicatedIds.set(left, duplicatedIds.get(right));
        assertTrue(NativeInteriorSupportVerifier.verify(batch, duplicatedIds, view.assignments(), view.keys(), view.after(), Map.of()).isEmpty());
        var insertedIds = new ArrayList<>(view.ids()); var insertedKeys = new ArrayList<>(view.keys());
        insertedIds.add(right, new GeneratedCandidatePoint("foreign-join", 0));
        insertedKeys.add(right, PrimitiveKey.planned(PrimitiveKey.Type.NODE, 999_999));
        assertFalse(NativeInteriorSupportVerifier.verify(batch, insertedIds, view.assignments(), insertedKeys, view.after(), Map.of()).contains(edge));
        assertTrue(NativeInteriorSupportVerifier.verify(batch, view.ids(), view.assignments(), view.keys(), view.after(), Map.of(0, -1)).isEmpty());
        var wrongIds = new ArrayList<>(view.ids());
        wrongIds.set(left, new GeneratedCandidatePoint("wrong-choice", 0));
        assertFalse(NativeInteriorSupportVerifier.verify(batch, wrongIds, view.assignments(), view.keys(), view.after(), Map.of()).contains(edge));

        var route = batch.runs().get(0).routes().get(0);
        // Real common evaluation of truncated native geometry: direct evidence from the removed
        // prefix/suffix or the neighboring interval cannot bracket this retained edge.
        for (boolean removePrefix : List.of(true, false)) {
            int first = removePrefix ? left : 0, last = removePrefix ? route.pointIds().size() : right + 1;
            var cropped = cropNativeRoute(batch, route, first, last);
            var changedBatch = withRoute(batch, 0, cropped);
            assertFalse(NativeInteriorSupportVerifier.verify(changedBatch,
                    view.ids().subList(first, last), view.assignments(), view.keys().subList(first, last), view.after(), Map.of()).contains(edge),
                    "Native prefix/suffix cannot borrow a direct bracket across its boundary");
        }
        var request = batch.fullRequest();
        var wrongRequest = new org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest(
                request.selectedWayKey(), request.selectedRange(), request.engine(), request.geometryMode(),
                request.permissions(), request.budgets(), request.evidenceSnapshotId(), "stale-evidence",
                request.networkSnapshotId(), request.networkContentHash(), request.settingsHash(), request.parameterHash(),
                request.samplerId(), request.configuredSampleStepMeters(), request.profileChainage(), request.evidenceResolution(), request.corridorInput());
        var input = new NativeInteriorSupportVerifier.Input(wrongRequest, batch.evidence(), batch.network(), batch.options(),
                batch.runs().stream().map(run -> new NativeInteriorSupportVerifier.Geometry(run.interval(), run.request(),
                        run.result().inference(), run.routes())).toList());
        assertTrue(NativeInteriorSupportVerifier.verify(input, view.ids(), view.assignments(), view.keys(), view.after(), Map.of()).isEmpty());
        var evidence = batch.evidence(); var origin = evidence.coordinateFrame().origin();
        var changedFrame = org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame.certifiedEquirectangular(
                new GeographicPoint(Math.nextUp(origin.latitudeDegrees()), origin.longitudeDegrees()),
                new GeographicPoint(origin.latitudeDegrees() - 0.01, origin.longitudeDegrees() - 0.01),
                new GeographicPoint(origin.latitudeDegrees() + 0.01, origin.longitudeDegrees() + 0.01));
        var changedEvidence = new org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot(evidence.snapshotId(),
                changedFrame, evidence.transform(), evidence.resolution(), evidence.decisionRegion(), evidence.evidenceRegion(),
                evidence.fields(), evidence.resampling(), evidence.sourceIdentity());
        var staleFrame = new NativeInteriorSupportVerifier.Input(batch.fullRequest(), changedEvidence, batch.network(),
                batch.options(), input.runs());
        assertTrue(NativeInteriorSupportVerifier.verify(staleFrame, view.ids(), view.assignments(), view.keys(), view.after(), Map.of()).isEmpty());
        var raw = route.rawHypothesis(); var changedRawPoints = new ArrayList<>(raw.points());
        var rawPoint = changedRawPoints.get(1);
        changedRawPoints.set(1, new MetricPoint(Math.nextUp(rawPoint.xMeters()), rawPoint.yMeters()));
        var changedRaw = new TraceHypothesis(raw.id(), raw.branchSignature(), changedRawPoints, raw.support(),
                raw.objective(), raw.posteriorProbability(), raw.diagnostics());
        var changedRoute = new ModernTracePipeline.Route(changedRaw, route.hypothesis(), route.pointIds(), route.assignments(),
                route.sourceOwnership(), route.quality(), route.cleanupStatus(), route.geometryChanged());
        assertThrows(IllegalArgumentException.class, () -> NativeInteriorSupportVerifier.verify(withRoute(batch, 0, changedRoute),
                view.ids(), view.assignments(), view.keys(), view.after(), Map.of()), "Native raw route must belong to the captured inference");
    }

    @Test
    void retainedNativeInteriorSupportsMovedExistingNodesAndStopsAtProtectedOwnership() throws Exception {
        var batch = batch(296.0, 296.0, TrackerMode.PROBABILISTIC, false, false, true,
                false, false, false, AlignmentMode.MOVE_EXISTING_NODES);
        var route = batch.runs().get(0).routes().get(0);
        assertTrue(route.pointIds().stream().allMatch(FinalRoutePointId.ExistingWayNodeOccurrence.class::isInstance));
        assertEquals(FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED, route.quality().disposition());
        assertEquals(Double.POSITIVE_INFINITY, route.quality().meanImageCenterCost());
        var view = nativeInteriorView(batch);
        var body = NativeInteriorSupportVerifier.verify(batch, view.ids(), view.assignments(), view.keys(), view.after(), Map.of());
        assertFalse(body.isEmpty(), "Moved all-existing native FINAL geometry needs the same physical body review policy");
        var edge = body.iterator().next(); int left = view.keys().indexOf(edge.left());
        var existing = (FinalRoutePointId.ExistingWayNodeOccurrence) view.ids().get(left);
        assertNotEquals(originalMetric(batch, existing.originalOccurrenceIndex()), view.assignments().get(existing),
                "Positive must represent actual movement, not a source subdivision exemption");
        var ownership = new LinkedHashMap<>(route.sourceOwnership());
        ownership.put(route.pointIds().get(left), ObservationOwnership.FIXED_TOPOLOGY_ONLY);
        var protectedRoute = new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(), route.pointIds(),
                route.assignments(), ownership, route.quality(), route.cleanupStatus(), route.geometryChanged());
        assertFalse(NativeInteriorSupportVerifier.verify(withRoute(batch, 0, protectedRoute), view.ids(), view.assignments(),
                view.keys(), view.after(), Map.of()).contains(edge), "A protected boundary cannot borrow a direct bracket across itself");
        var state = new AlignWayAction.IntervalPreviewState(batch);
        var plan = state.assessment().plan().orElseThrow();
        assertEquals(ValidationReport.Disposition.REVIEW_REQUIRED, plan.validation().disposition());
        assertFalse(state.applyAvailable()); state.confirmReview();
        assertEquals(plan.canonicalHash(), state.currentPlanForApply().canonicalHash());
        assertEquals(((DetachedWay) batch.network().primitives().get(batch.fullRequest().selectedWayKey())).nodeKeys(),
                ((DetachedWay) plan.after().primitives().get(batch.fullRequest().selectedWayKey())).nodeKeys());
        for (var island : batch.partition().fixedIslands()) for (var key : island.occurrenceKeys())
            assertEquals(batch.network().primitives().get(key), plan.after().primitives().get(key));
    }

    @Test
    void retainedNativeInteriorRejectsForgedPhysicalSummaryAndHardFindingWithReviewDisposition() throws Exception {
        var batch = batch(296.0, 296.0, TrackerMode.PROBABILISTIC, false, false, true);
        var view = nativeInteriorView(batch); var route = batch.runs().get(0).routes().get(0); var q = route.quality();
        var findings = new ArrayList<>(q.findings());
        findings.add(new FinalGeometryEvaluator.Finding(FinalGeometryEvaluator.FindingCode.SELF_INTERSECTION,
                FinalGeometryEvaluator.Severity.HARD_BLOCK, 0, 1, 1.0));
        var other = batch.runs().get(1).routes().get(0).quality();
        var forged = List.of(
                new FinalGeometryEvaluator.Result(q.id(), q.disposition(), q.findings(), Math.nextUp(q.totalLengthMeters()), q.directlySupportedLengthMeters(), q.worstUnsupportedSpanMeters(), q.meanImageCenterCost(), q.bendPreservingRoughness()),
                new FinalGeometryEvaluator.Result(q.id(), q.disposition(), q.findings(), q.totalLengthMeters(), Math.nextUp(q.directlySupportedLengthMeters()), q.worstUnsupportedSpanMeters(), q.meanImageCenterCost(), q.bendPreservingRoughness()),
                new FinalGeometryEvaluator.Result(q.id(), q.disposition(), q.findings(), q.totalLengthMeters(), q.directlySupportedLengthMeters(), Math.nextUp(q.worstUnsupportedSpanMeters()), q.meanImageCenterCost(), q.bendPreservingRoughness()),
                new FinalGeometryEvaluator.Result(q.id(), q.disposition(), q.findings(), other.totalLengthMeters(), other.directlySupportedLengthMeters(), other.worstUnsupportedSpanMeters(), q.meanImageCenterCost(), q.bendPreservingRoughness()),
                new FinalGeometryEvaluator.Result(q.id(), q.disposition(), findings, q.totalLengthMeters(), q.directlySupportedLengthMeters(), q.worstUnsupportedSpanMeters(), q.meanImageCenterCost(), q.bendPreservingRoughness()));
        for (var quality : forged) {
            var changed = withRoute(batch, 0, new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(),
                    route.pointIds(), route.assignments(), route.sourceOwnership(), quality, route.cleanupStatus(), route.geometryChanged()));
            assertThrows(IllegalArgumentException.class, () -> NativeInteriorSupportVerifier.verify(changed,
                    view.ids(), view.assignments(), view.keys(), view.after(), Map.of()));
            var assessment = new FixedIntervalEditPlanComposer().compose(changed, Map.of());
            assertFalse(assessment.applyAvailable());
            assertTrue(assessment.plan().isEmpty(), "Forged native support/finding cannot produce a confirmable plan");
        }
    }

    private record NativeInteriorView(List<FinalRoutePointId> ids, Map<FinalRoutePointId, MetricPoint> assignments,
            List<PrimitiveKey> keys, Map<PrimitiveKey, DetachedPrimitive> after) { }

    private static NativeInteriorView nativeInteriorView(IntervalTraceBatch batch) {
        var assessment = new FixedIntervalEditPlanComposer().compose(batch, Map.of());
        var plan = assessment.plan().orElseThrow();
        var route = batch.runs().get(0).routes().get(0);
        List<FinalRoutePointId> ids = route.pointIds().stream().map(id -> id instanceof GeneratedCandidatePoint generated
                ? (FinalRoutePointId) new GeneratedCandidatePoint("interval-0:" + route.hypothesis().id(), generated.originalPointIndex()) : id).toList();
        var afterWay = (DetachedWay) plan.after().primitives().get(batch.fullRequest().selectedWayKey());
        int first = batch.fullRequest().selectedRange().firstIndex();
        return new NativeInteriorView(ids, assessment.assignments(), afterWay.nodeKeys().subList(first, first + ids.size()), plan.after().primitives());
    }

    private static ModernTracePipeline.Route cropNativeRoute(IntervalTraceBatch batch, ModernTracePipeline.Route route, int first, int last) {
        var ids = route.pointIds().subList(first, last);
        var points = ids.stream().map(route.assignments()::get).toList();
        var h = route.hypothesis();
        var hypothesis = new TraceHypothesis(h.id(), h.branchSignature(), points,
                h.support().subList(first, last), h.objective(), h.posteriorProbability(), h.diagnostics());
        Map<FinalRoutePointId, MetricPoint> assignments = new LinkedHashMap<>();
        Map<FinalRoutePointId, ObservationOwnership> ownership = new LinkedHashMap<>();
        for (var id : ids) { assignments.put(id, route.assignments().get(id)); ownership.put(id, route.sourceOwnership().get(id)); }
        var image = new ImageCostField(batch.evidence().fields().get(batch.options().fieldName()), batch.evidence().transform(),
                batch.evidence().decisionRegion(), batch.evidence().resolution().effectivePitchMeters());
        var quality = new FinalGeometryEvaluator().evaluate(new FinalGeometryEvaluator.Request(h.id(), points, ids, image,
                batch.evidence().resolution().effectivePitchMeters(), Map.of(), List.of(), false, false, false, false));
        return new ModernTracePipeline.Route(route.rawHypothesis(), hypothesis, ids, assignments, ownership,
                quality, route.cleanupStatus(), route.geometryChanged());
    }

    @Test
    void supportedReviewRouteWithUnavailableImageCostRetainsExactReviewPlan() throws Exception {
        IntervalTraceBatch batch = batch(296.0, 296.0, TrackerMode.CORRIDOR_AWARE,
                false, false, true);
        var quality = batch.runs().get(0).routes().get(0).quality();
        assertEquals(FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED, quality.disposition());
        assertTrue(quality.has(FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY), quality.toString());
        assertTrue(quality.directlySupportedLengthMeters() > 0.0);
        assertEquals(Double.POSITIVE_INFINITY, quality.meanImageCenterCost());

        AlignWayAction.IntervalPreviewState state = new AlignWayAction.IntervalPreviewState(batch);
        assertEquals(FixedIntervalEditPlanComposer.Disposition.CHANGED,
                state.assessment().intervals().get(0).disposition(),
                "Supported review evidence must reach the ordinary composite validators");
        var plan = state.assessment().plan().orElseThrow();
        assertEquals(ValidationReport.Disposition.REVIEW_REQUIRED, plan.validation().disposition());
        for (var finding : quality.findings()) {
            assertTrue(plan.validation().findingCodes().contains("interval-0:" + finding.code().name()));
        }
        assertFalse(state.applyAvailable(), "Review is session-local and initially unconfirmed");
        assertThrows(IllegalStateException.class, state::currentPlanForApply);
        state.confirmReview();
        assertTrue(state.applyAvailable());
        assertEquals(plan.canonicalHash(), state.currentPlanForApply().canonicalHash());
        for (var island : batch.partition().fixedIslands()) {
            for (var key : island.occurrenceKeys()) {
                assertEquals(batch.network().primitives().get(key), plan.after().primitives().get(key));
            }
        }
        assertEquals(Double.POSITIVE_INFINITY, batch.runs().get(0).routes().get(0)
                .quality().meanImageCenterCost(), "Unavailable cost must remain unavailable after composition");
    }

    @Test
    void unsupportedFirstIntervalFreezesLocallyWhileSecondRetainsOnePlan() throws Exception {
        IntervalTraceBatch batch = batch(288.0, 296.0);
        var assessment = new FixedIntervalEditPlanComposer().compose(batch, Map.of());
        assertEquals(List.of(FixedIntervalEditPlanComposer.Disposition.FROZEN_LOCAL_FAILURE,
                FixedIntervalEditPlanComposer.Disposition.CHANGED),
                assessment.intervals().stream().map(
                        FixedIntervalEditPlanComposer.IntervalAssessment::disposition).toList(),
                assessment.intervals().toString());
        assertTrue(assessment.applyAvailable());
        var selected = (DetachedWay) batch.network().primitives()
                .get(batch.fullRequest().selectedWayKey());
        var after = assessment.plan().orElseThrow().after().primitives();
        assertEquals(batch.network().primitives().get(selected.nodeKeys().get(2)),
                after.get(selected.nodeKeys().get(2)));
        assertEquals(batch.network().primitives().get(selected.nodeKeys().get(3)),
                after.get(selected.nodeKeys().get(3)));
    }

    @Test
    void allFrozenIntervalsHaveNoApplyPlan() throws Exception {
        IntervalTraceBatch batch = batch(288.0, 288.0);
        var assessment = new FixedIntervalEditPlanComposer().compose(batch, Map.of());
        assertFalse(assessment.applyAvailable());
        assertTrue(assessment.plan().isEmpty());
        assertEquals(List.of(FixedIntervalEditPlanComposer.Disposition.FROZEN_LOCAL_FAILURE,
                FixedIntervalEditPlanComposer.Disposition.FROZEN_LOCAL_FAILURE),
                assessment.intervals().stream().map(
                        FixedIntervalEditPlanComposer.IntervalAssessment::disposition).toList());
    }

    @Test
    void geometricallyUnchangedRouteIsNotReportedAsALocalFailure() throws Exception {
        IntervalTraceBatch batch = batch();
        var first = batch.runs().get(0).routes().get(0);
        var selected = (DetachedWay) batch.network().primitives()
                .get(batch.fullRequest().selectedWayKey());
        int start = batch.runs().get(0).interval().traceRange().firstIndex();
        int end = batch.runs().get(0).interval().traceRange().lastIndex();
        List<FinalRoutePointId> ids = java.util.stream.IntStream.rangeClosed(start, end)
                .mapToObj(index -> (FinalRoutePointId) new FinalRoutePointId.ExistingWayNodeOccurrence(
                        selected.key(), selected.nodeKeys().get(index), index)).toList();
        Map<FinalRoutePointId, MetricPoint> positions = new java.util.LinkedHashMap<>();
        Map<FinalRoutePointId, org.openstreetmap.josm.plugins.wayheatmaptracer.model
                .ObservationOwnership> ownership = new java.util.LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            positions.put(ids.get(i), originalMetric(batch, start + i));
            ownership.put(ids.get(i), org.openstreetmap.josm.plugins.wayheatmaptracer.model
                    .ObservationOwnership.FIXED_TOPOLOGY_ONLY);
        }
        var raw = first.rawHypothesis();
        var noOpHypothesis = new TraceHypothesis(raw.id(), raw.branchSignature(),
                ids.stream().map(positions::get).toList(),
                ids.stream().map(ownership::get).toList(), raw.objective(),
                raw.posteriorProbability(), raw.diagnostics());
        var image = new ImageCostField(batch.evidence().fields().get(batch.options().fieldName()),
                batch.evidence().transform(), batch.evidence().decisionRegion(),
                batch.evidence().resolution().effectivePitchMeters());
        Map<Integer, MetricPoint> protectedPositions = new java.util.LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            if (batch.network().closure().protectedExistingNodeKeys().contains(
                    selected.nodeKeys().get(start + i))) {
                protectedPositions.put(i, positions.get(ids.get(i)));
            }
        }
        var quality = new FinalGeometryEvaluator().evaluate(new FinalGeometryEvaluator.Request(
                raw.id(), noOpHypothesis.points(), ids, image,
                batch.evidence().resolution().effectivePitchMeters(), protectedPositions,
                List.of(), false, false, false, false));
        var noOp = new ModernTracePipeline.Route(raw, noOpHypothesis, ids, positions,
                ownership, quality, first.cleanupStatus(), false);
        var assessment = new FixedIntervalEditPlanComposer().compose(
                withRoute(batch, 0, noOp), Map.of());
        assertEquals(FixedIntervalEditPlanComposer.Disposition.UNCHANGED_NOOP,
                assessment.intervals().get(0).disposition());
        assertEquals("NO_GEOMETRY_CHANGE", assessment.intervals().get(0).reason());
        assertEquals(FixedIntervalEditPlanComposer.Disposition.CHANGED,
                assessment.intervals().get(1).disposition());
        assertTrue(assessment.applyAvailable());
    }

    @Test
    void emptyProductionAlternativesFreezeButExplicitChoiceIsRejected() throws Exception {
        IntervalTraceBatch batch = batch();
        var first = batch.runs().get(0);
        var original = first.result().inference();
        var noRoute = new TraceHypothesisSet(original.engine(), List.of(),
                TraceHypothesisSet.Status.NO_ROUTE, false, original.evaluatedStates(),
                original.evaluatedTransitions(), "no local route");
        var missing = new IntervalTraceBatch.IntervalRun(first.interval(), first.request(),
                new ModernTracePipeline.Result(noRoute, List.of()), first.usage());
        var unavailable = new IntervalTraceBatch(batch.fullRequest(), batch.evidence(),
                batch.network(), batch.partition(), List.of(missing, batch.runs().get(1)),
                batch.options());
        assertThrows(IllegalArgumentException.class,
                () -> new FixedIntervalEditPlanComposer().compose(unavailable, Map.of(0, 0)));
        var assessment = new FixedIntervalEditPlanComposer().compose(unavailable, Map.of());
        assertEquals(FixedIntervalEditPlanComposer.Disposition.FROZEN_LOCAL_FAILURE,
                assessment.intervals().get(0).disposition());
        assertEquals("NO_PRODUCTION_ROUTE", assessment.intervals().get(0).reason());
        assertEquals(FixedIntervalEditPlanComposer.Disposition.CHANGED,
                assessment.intervals().get(1).disposition());
        assertTrue(assessment.applyAvailable());
    }

    @Test
    void resourceLimitedEmptyIntervalBlocksTheWholeBatch() throws Exception {
        IntervalTraceBatch batch = withResourceLimit(batch(), 0, false);
        var assessment = new FixedIntervalEditPlanComposer().compose(batch, Map.of());
        assertFalse(assessment.applyAvailable());
        assertTrue(assessment.plan().isEmpty());
        assertEquals(List.of(FixedIntervalEditPlanComposer.Disposition.BLOCKED_GLOBAL,
                FixedIntervalEditPlanComposer.Disposition.BLOCKED_GLOBAL),
                assessment.intervals().stream().map(
                        FixedIntervalEditPlanComposer.IntervalAssessment::disposition).toList());
        assertTrue(assessment.intervals().stream().allMatch(interval ->
                interval.reason().startsWith("RESOURCE_LIMIT")), assessment.intervals().toString());
        assertEquals(batch.runs().get(1).routes().get(0).hypothesis().id(),
                assessment.intervals().get(1).routeIdentity());
    }

    @Test
    void resourceLimitedRetainedRouteIsDiagnosticOnlyForEveryInterval() throws Exception {
        IntervalTraceBatch batch = withResourceLimit(batch(), 0, true);
        var assessment = new FixedIntervalEditPlanComposer().compose(batch, Map.of());
        assertFalse(assessment.applyAvailable());
        assertTrue(assessment.plan().isEmpty());
        assertEquals(List.of(FixedIntervalEditPlanComposer.Disposition.BLOCKED_GLOBAL,
                FixedIntervalEditPlanComposer.Disposition.BLOCKED_GLOBAL),
                assessment.intervals().stream().map(
                        FixedIntervalEditPlanComposer.IntervalAssessment::disposition).toList());
        assertEquals(batch.runs().get(0).routes().get(0).hypothesis().id(),
                assessment.intervals().get(0).routeIdentity());
        assertTrue(assessment.intervals().stream().allMatch(interval ->
                interval.reason().startsWith("RESOURCE_LIMIT")), assessment.intervals().toString());
    }

    @Test
    void completeInferenceWithTruncatedAlternativesStillUsesItsRetainedRoute() throws Exception {
        IntervalTraceBatch batch = batch();
        List<IntervalTraceBatch.IntervalRun> runs = new java.util.ArrayList<>(batch.runs());
        var run = runs.get(0);
        var original = run.result().inference();
        var truncated = new TraceHypothesisSet(original.engine(), original.hypotheses(),
                TraceHypothesisSet.Status.COMPLETE, true, original.evaluatedStates(),
                original.evaluatedTransitions(), "retained alternatives truncated");
        runs.set(0, new IntervalTraceBatch.IntervalRun(run.interval(), run.request(),
                new ModernTracePipeline.Result(truncated, run.routes()), run.usage()));
        var retained = new IntervalTraceBatch(batch.fullRequest(), batch.evidence(),
                batch.network(), batch.partition(), runs, batch.options());
        var assessment = new FixedIntervalEditPlanComposer().compose(retained, Map.of());
        assertTrue(assessment.applyAvailable());
        assertEquals(List.of(FixedIntervalEditPlanComposer.Disposition.CHANGED,
                FixedIntervalEditPlanComposer.Disposition.CHANGED),
                assessment.intervals().stream().map(
                        FixedIntervalEditPlanComposer.IntervalAssessment::disposition).toList());
    }

    @Test
    void cancelledInferenceDoesNotFreezeLocallyAndApplyTheOtherInterval() throws Exception {
        IntervalTraceBatch batch = batch();
        List<IntervalTraceBatch.IntervalRun> runs = new java.util.ArrayList<>(batch.runs());
        var run = runs.get(0);
        var original = run.result().inference();
        var cancelled = new TraceHypothesisSet(original.engine(), List.of(),
                TraceHypothesisSet.Status.CANCELLED, false,
                original.evaluatedStates(), original.evaluatedTransitions(), "cancelled");
        runs.set(0, new IntervalTraceBatch.IntervalRun(run.interval(), run.request(),
                new ModernTracePipeline.Result(cancelled, List.of()), run.usage()));
        var cancelledBatch = new IntervalTraceBatch(batch.fullRequest(), batch.evidence(),
                batch.network(), batch.partition(), runs, batch.options());
        var assessment = new FixedIntervalEditPlanComposer().compose(cancelledBatch, Map.of());
        assertFalse(assessment.applyAvailable());
        assertEquals(List.of(FixedIntervalEditPlanComposer.Disposition.BLOCKED_GLOBAL,
                FixedIntervalEditPlanComposer.Disposition.BLOCKED_GLOBAL),
                assessment.intervals().stream().map(
                        FixedIntervalEditPlanComposer.IntervalAssessment::disposition).toList());
        assertTrue(assessment.intervals().stream().allMatch(interval ->
                interval.reason().startsWith("CANCELLED")));
    }

    private static IntervalTraceBatch withResourceLimit(IntervalTraceBatch batch, int interval,
            boolean retainRoutes) {
        List<IntervalTraceBatch.IntervalRun> runs = new java.util.ArrayList<>(batch.runs());
        var run = runs.get(interval);
        var original = run.result().inference();
        var limited = new TraceHypothesisSet(original.engine(),
                retainRoutes ? original.hypotheses() : List.of(),
                TraceHypothesisSet.Status.RESOURCE_LIMIT, true,
                original.evaluatedStates(), original.evaluatedTransitions(),
                "bounded interval inference exhausted");
        runs.set(interval, new IntervalTraceBatch.IntervalRun(run.interval(), run.request(),
                new ModernTracePipeline.Result(limited,
                        retainRoutes ? run.routes() : List.of()), run.usage()));
        return new IntervalTraceBatch(batch.fullRequest(), batch.evidence(), batch.network(),
                batch.partition(), runs, batch.options());
    }

    @Test
    void capturedIndependentCrossingRequiresReviewOfTheCompleteComposedPlan() throws Exception {
        IntervalTraceBatch batch = batch(296.0, 296.0, TrackerMode.CORRIDOR_AWARE,
                false, true);
        var assessment = new FixedIntervalEditPlanComposer().compose(batch, Map.of());
        assertTrue(assessment.applyAvailable());
        assertEquals(List.of(FixedIntervalEditPlanComposer.Disposition.CHANGED,
                FixedIntervalEditPlanComposer.Disposition.CHANGED),
                assessment.intervals().stream().map(
                        FixedIntervalEditPlanComposer.IntervalAssessment::disposition).toList(),
                assessment.intervals().toString());
        var plan = assessment.plan().orElseThrow();
        assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport.Disposition.REVIEW_REQUIRED,
                plan.validation().disposition());
        assertTrue(plan.validation().findingCodes().contains(ContextContactAssessment.REVIEW_CODE));
        assertFalse(plan.validation().findingCodes().stream().anyMatch(code -> code.startsWith("final-topology:")));
        for (long id : List.of(201L, 202L)) {
            var key = PrimitiveKey.existing(PrimitiveKey.Type.NODE, id);
            assertEquals(batch.network().primitives().get(key), plan.after().primitives().get(key));
        }
        var context = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 203);
        assertEquals(batch.network().primitives().get(context), plan.after().primitives().get(context));
        var withoutContext = new FixedIntervalEditPlanComposer().compose(
                batch(296.0, 296.0, TrackerMode.CORRIDOR_AWARE, false, false), Map.of());
        assertEquals(withoutContext.selectedWayPreview(), assessment.selectedWayPreview(),
                "read-only independent contact policy must preserve the production geometry");
    }

    private record CrossingFixture(IntervalTraceBatch source,
            ModernTracePipeline.Route left, ModernTracePipeline.Route right) { }

    private static CrossingFixture crossingFixture() throws Exception {
        return crossingFixture(true);
    }

    private static CrossingFixture crossingFixture(boolean paintedApproaches) throws Exception {
        return bendFixture(uRaster(paintedApproaches), 4.0);
    }

    private static CrossingFixture bendFixture(LiveBPreviewService.VisibleRaster raster,
            double bendHeightMeters) throws Exception {
        IntervalTraceBatch source = uBatch(raster);
        var selected = (DetachedWay) source.network().primitives()
                .get(source.fullRequest().selectedWayKey());
        MetricPoint lower = source.evidence().coordinateFrame().toMetric(
                ((DetachedNode) source.network().primitives().get(selected.nodeKeys().get(3)))
                        .coordinate());
        MetricPoint upper = source.evidence().coordinateFrame().toMetric(
                ((DetachedNode) source.network().primitives().get(selected.nodeKeys().get(4)))
                        .coordinate());
        var left = source.runs().get(0).routes().get(0);
        var right = source.runs().get(1).routes().get(0);
        assertEquals(6, left.pointIds().size());
        assertEquals(6, right.pointIds().size());
        assertEquals(List.of(1, -1, -1, 2, -1, 3), left.pointIds().stream()
                .map(id -> id instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing
                        ? existing.originalOccurrenceIndex() : -1).toList());
        assertEquals(List.of(4, -1, 5, -1, -1, 6), right.pointIds().stream()
                .map(id -> id instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing
                        ? existing.originalOccurrenceIndex() : -1).toList());
        // The 4 m bumps cross; the 1 m control keeps the two branches separate.
        var alteredLeft = withGeneratedPositions(source, left, Map.of(
                left.pointIds().get(1),
                        new MetricPoint(lower.xMeters() - 13.5, lower.yMeters()),
                left.pointIds().get(2),
                        new MetricPoint(lower.xMeters() - 12.0, lower.yMeters()),
                existingPointId(left, 2),
                        new MetricPoint(lower.xMeters() - 6.0, lower.yMeters() + bendHeightMeters),
                left.pointIds().get(4),
                        new MetricPoint(lower.xMeters() - 4.0, lower.yMeters())));
        var alteredRight = withGeneratedPositions(source, right, Map.of(
                right.pointIds().get(1),
                        new MetricPoint(upper.xMeters() - 4.0, upper.yMeters()),
                existingPointId(right, 5),
                        new MetricPoint(upper.xMeters() - 6.0, upper.yMeters() - bendHeightMeters),
                right.pointIds().get(3),
                        new MetricPoint(upper.xMeters() - 12.0, upper.yMeters()),
                right.pointIds().get(4),
                        new MetricPoint(upper.xMeters() - 13.5, upper.yMeters())));
        assertNotEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED,
                alteredLeft.quality().disposition(), alteredLeft.quality().toString());
        assertNotEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED,
                alteredRight.quality().disposition(), alteredRight.quality().toString());
        return new CrossingFixture(source, alteredLeft, alteredRight);
    }

    @Test
    void twoTopologicallySafeIntervalRoutesCreateAnUnsafeCombinedCrossing() throws Exception {
        var fixture = crossingFixture();
        var source = fixture.source();
        var left = topologyProposal(source, Map.of(0, fixture.left()));
        var right = topologyProposal(source, Map.of(1, fixture.right()));
        var both = topologyProposal(source, Map.of(0, fixture.left(), 1, fixture.right()));
        assertEquals(List.of(), topologyFindings(source, left));
        assertEquals(List.of(), topologyFindings(source, right));
        assertTrue(topologyFindings(source, both).contains("final-topology:CROSSING"),
                topologyFindings(source, both).toString());
    }

    @Test
    void strongPaintedEvidenceCannotMakeCombinedCrossingGeometrySafe() throws Exception {
        var fixture = crossingFixture();
        var source = fixture.source();
        var image = new ImageCostField(source.evidence().fields().get(source.options().fieldName()),
                source.evidence().transform(), source.evidence().decisionRegion(),
                source.evidence().resolution().effectivePitchMeters());
        MetricPoint start = fixture.left().assignments().get(fixture.left().pointIds().get(2));
        MetricPoint end = fixture.left().assignments().get(existingPointId(fixture.left(), 2));
        MetricPoint tangent = new MetricPoint(end.xMeters() - start.xMeters(),
                end.yMeters() - start.yMeters());
        double length = start.distanceTo(end);
        MetricPoint normal = new MetricPoint(-tangent.yMeters() / length, tangent.xMeters() / length);
        int count = (int) Math.ceil(length / 0.5);
        List<ImageCostField.RouteSample> samples = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            MetricPoint point = lerp(start, end, (index + 0.5) / count);
            for (double offset = -8.0; offset <= 8.0; offset += 0.5) {
                assertTrue(image.supports(new MetricPoint(point.xMeters() + normal.xMeters() * offset,
                        point.yMeters() + normal.yMeters() * offset)));
            }
            var value = image.sampleRoute(point, tangent).orElseThrow();
            assertTrue(value.rawIntensity() > 0.75 && value.presenceResponse() > 0.9, value.toString());
            samples.add(value);
        }
        assertTrue(samples.stream().anyMatch(value -> value.ownership() == ObservationOwnership.DIRECT_AMBIGUOUS));
        var combined = topologyProposal(source, Map.of(0, fixture.left(), 1, fixture.right()));
        assertTrue(topologyFindings(source, combined).contains("final-topology:CROSSING"));
        // A composer may freeze a failed interval. Any available plan must still
        // validate its actual stored geometry, not the two raw crossing proposals.
        var composed = new FixedIntervalEditPlanComposer().compose(
                withRoute(withRoute(source, 0, fixture.left()), 1, fixture.right()), Map.of());
        if (composed.applyAvailable()) {
            assertEquals(List.of(), topologyFindings(source,
                    composed.plan().orElseThrow().after().primitives()));
            assertNotEquals(List.of(FixedIntervalEditPlanComposer.Disposition.CHANGED,
                    FixedIntervalEditPlanComposer.Disposition.CHANGED),
                    composed.intervals().stream().map(
                            FixedIntervalEditPlanComposer.IntervalAssessment::disposition).toList());
        }
    }

    @Test
    void supportedNonCrossingBendsComposeBothIntervalsAndPreserveFixedIsland() throws Exception {
        var fixture = bendFixture(uRaster(true, 1.0), 1.0);
        var source = fixture.source();
        var combined = topologyProposal(source, Map.of(0, fixture.left(), 1, fixture.right()));
        assertEquals(List.of(), topologyFindings(source, combined));
        var result = new FixedIntervalEditPlanComposer().compose(
                withRoute(withRoute(source, 0, fixture.left()), 1, fixture.right()), Map.of());
        assertTrue(result.applyAvailable(), result.intervals().toString());
        assertEquals(List.of(FixedIntervalEditPlanComposer.Disposition.CHANGED,
                FixedIntervalEditPlanComposer.Disposition.CHANGED), result.intervals().stream()
                .map(FixedIntervalEditPlanComposer.IntervalAssessment::disposition).toList());
        var after = result.plan().orElseThrow().after().primitives();
        var expectedWay = (DetachedWay) combined.get(source.fullRequest().selectedWayKey());
        var expectedCoordinates = expectedWay.nodeKeys().stream()
                .map(key -> ((DetachedNode) combined.get(key)).coordinate()).toList();
        assertEquals(expectedCoordinates, result.selectedWayPreview(),
                "Both supported bends must appear in the stored final geometry");
        var storedWay = (DetachedWay) after.get(source.fullRequest().selectedWayKey());
        assertEquals(expectedCoordinates, storedWay.nodeKeys().stream()
                .map(key -> ((DetachedNode) after.get(key)).coordinate()).toList(),
                "The immutable Apply plan must store exactly the supported preview");
        assertEquals(List.of(), topologyFindings(source, after));
        var original = (DetachedWay) source.network().primitives()
                .get(source.fullRequest().selectedWayKey());
        for (int occurrence : List.of(0, 1, 3, 4, 6, 7)) {
            var key = original.nodeKeys().get(occurrence);
            assertEquals(source.network().primitives().get(key), after.get(key),
                    "Fixed or unselected occurrence " + occurrence + " must remain exact");
        }
    }

    @Test
    void unpaintedCrossingApproachCannotAuthorizeAnIntervalConnector() throws Exception {
        var unsupported = crossingFixture(false);
        var refused = new FixedIntervalEditPlanComposer().compose(withoutRoutes(
                withRoute(unsupported.source(), 0, unsupported.left()), 1), Map.of());
        assertFalse(refused.applyAvailable(), "Unpainted interval connector must remain unavailable");
        assertTrue(refused.intervals().get(0).reason().startsWith("LOCAL_CONNECTOR_SUPPORT:"),
                refused.intervals().toString());
    }

    private static List<String> topologyFindings(IntervalTraceBatch source,
            Map<PrimitiveKey, DetachedPrimitive> after) {
        return ModernSingleWayEditPlanAdapter.finalTopologyFindings(source.network(), after,
                source.fullRequest().selectedWayKey(), source.fullRequest().selectedRange(),
                source.evidence(), source.fullRequest().permissions().ordinaryRadiusMeters());
    }

    private static Map<PrimitiveKey, DetachedPrimitive> topologyProposal(IntervalTraceBatch source,
            Map<Integer, ModernTracePipeline.Route> routes) {
        var before = source.network();
        var original = (DetachedWay) before.primitives().get(source.fullRequest().selectedWayKey());
        Map<PrimitiveKey, DetachedPrimitive> after = new LinkedHashMap<>(before.primitives());
        List<PrimitiveKey> sequence = new ArrayList<>();
        Set<PrimitiveKey> changedExisting = routes.keySet().stream()
                .map(interval -> original.nodeKeys().get(interval == 0 ? 2 : 5))
                .collect(java.util.stream.Collectors.toSet());
        for (int occurrence = 0; occurrence < original.nodeKeys().size();) {
            Integer owner = null;
            for (int interval : routes.keySet()) {
                if (source.partition().slideIntervals().get(interval).traceRange().firstIndex() == occurrence) {
                    owner = interval;
                    break;
                }
            }
            if (owner == null) {
                sequence.add(original.nodeKeys().get(occurrence++));
                continue;
            }
            var route = routes.get(owner);
            for (int point = 0; point < route.pointIds().size(); point++) {
                var id = route.pointIds().get(point);
                var coordinate = source.evidence().coordinateFrame().toGeographic(route.assignments().get(id));
                PrimitiveKey key;
                if (id instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing) {
                    key = existing.nodeKey();
                    var old = (DetachedNode) before.primitives().get(key);
                    if (changedExisting.contains(key)) {
                        assertTrue(before.closure().movableExistingNodeKeys().contains(key));
                        after.put(key, new DetachedNode(key, coordinate, old.tags(), old.deleted(), true));
                    } else {
                        assertEquals(source.evidence().coordinateFrame().toMetric(old.coordinate()),
                                route.assignments().get(id));
                    }
                } else {
                    key = PrimitiveKey.planned(PrimitiveKey.Type.NODE, 1000L * (owner + 1) + point);
                    assertFalse(after.containsKey(key));
                    after.put(key, new DetachedNode(key, coordinate, Map.of(), false, true));
                }
                sequence.add(key);
            }
            occurrence = source.partition().slideIntervals().get(owner).traceRange().lastIndex() + 1;
        }
        after.put(original.key(), new DetachedWay(original.key(), sequence, original.tags(), false, true));
        before.primitives().values().stream().filter(DetachedNode.class::isInstance)
                .filter(node -> !changedExisting.contains(node.key()))
                .forEach(node -> assertEquals(node, after.get(node.key())));
        assertEquals(original.nodeKeys().get(0), sequence.get(0));
        assertEquals(original.nodeKeys().get(7), sequence.get(sequence.size() - 1));
        var selected = sequence.subList(1, sequence.size() - 1);
        assertEquals(List.of(), ModernSingleWayEditPlanAdapter.compositeBacktrackFindings(
                before, after, original, selected, source.evidence()));
        return Map.copyOf(after);
    }

    @Test
    void changedApproachOverlappingAdjacentFrozenIslandEdgeBlocksWholePlan() throws Exception {
        IntervalTraceBatch batch = uBatch(overlapRaster());
        var left = batch.runs().get(0).routes().get(0);
        assertEquals(6, left.pointIds().size());
        MetricPoint lower = originalMetric(batch, 3);
        var flat = withGeneratedPositions(batch, left, Map.of(
                existingPointId(left, 2), originalMetric(batch, 2)));
        flat = withGeneratedPositions(batch, flat, linearGeneratedPositions(flat));
        var approach = withGeneratedPositions(batch, flat, Map.of(
                left.pointIds().get(4), new MetricPoint(lower.xMeters(),
                        lower.yMeters() + 1.5)));
        assertNotEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED,
                approach.quality().disposition(), approach.quality().toString());
        assertFalse(approach.quality().has(
                FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY),
                approach.quality().toString());
        var assessment = new FixedIntervalEditPlanComposer().compose(
                withoutRoutes(withRoute(batch, 0, approach), 1), Map.of());
        assertFalse(assessment.applyAvailable());
        assertEquals(FixedIntervalEditPlanComposer.Disposition.BLOCKED_GLOBAL,
                assessment.intervals().get(0).disposition(), assessment.intervals().toString());
        assertTrue(assessment.plan().orElseThrow().validation().findingCodes().stream()
                .anyMatch(code -> code.equals("final-topology:CONTINUATION")
                        || code.equals("final-topology:COLLINEAR_OVERLAP")),
                assessment.plan().orElseThrow().validation().findingCodes().toString());
    }

    @Test
    void twoChangedIntervalsCannotOverlapAtSingletonFixedIsland() throws Exception {
        IntervalTraceBatch batch = uBatch(overlapRaster(), true);
        assertEquals(new OccurrenceRange(3, 3), batch.partition().fixedIslands().get(0).range());
        var left = batch.runs().get(0).routes().get(0);
        var right = batch.runs().get(1).routes().get(0);
        assertFalse(right.quality().has(
                FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY),
                right.quality().toString());
        MetricPoint fixed = originalMetric(batch, 3);
        var flatLeft = withGeneratedPositions(batch, left, Map.of(
                existingPointId(left, 2), originalMetric(batch, 2)));
        flatLeft = withGeneratedPositions(batch, flatLeft, linearGeneratedPositions(flatLeft));
        var overlapLeft = withGeneratedPositions(batch, flatLeft, Map.of(
                left.pointIds().get(left.pointIds().size() - 2),
                new MetricPoint(fixed.xMeters(), fixed.yMeters() + 1.5)));
        var overlapRight = right;
        assertNotEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED,
                overlapLeft.quality().disposition(), overlapLeft.quality().toString());
        assertNotEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED,
                overlapRight.quality().disposition(), overlapRight.quality().toString());
        assertFalse(overlapLeft.quality().has(
                FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY),
                overlapLeft.quality().toString());
        assertFalse(overlapRight.quality().has(
                FinalGeometryEvaluator.FindingCode.UNAVAILABLE_IMAGE_QUALITY),
                overlapRight.quality().toString());
        var assessment = new FixedIntervalEditPlanComposer().compose(
                withRoute(withRoute(batch, 0, overlapLeft), 1, overlapRight), Map.of());
        assertFalse(assessment.applyAvailable());
        assertEquals(List.of(FixedIntervalEditPlanComposer.Disposition.BLOCKED_GLOBAL,
                FixedIntervalEditPlanComposer.Disposition.BLOCKED_GLOBAL),
                assessment.intervals().stream().map(
                        FixedIntervalEditPlanComposer.IntervalAssessment::disposition).toList(),
                assessment.intervals().toString());
        assertTrue(assessment.plan().orElseThrow().validation().findingCodes().contains(
                "final-topology:COLLINEAR_OVERLAP"),
                assessment.plan().orElseThrow().validation().findingCodes().toString());
    }

    private static IntervalTraceBatch withRoute(IntervalTraceBatch batch, int interval,
            ModernTracePipeline.Route route) {
        List<IntervalTraceBatch.IntervalRun> runs = new java.util.ArrayList<>(batch.runs());
        var original = runs.get(interval);
        List<ModernTracePipeline.Route> routes = new java.util.ArrayList<>(original.routes());
        routes.set(0, route);
        runs.set(interval, new IntervalTraceBatch.IntervalRun(original.interval(),
                original.request(), new ModernTracePipeline.Result(
                        original.result().inference(), routes), original.usage()));
        return new IntervalTraceBatch(batch.fullRequest(), batch.evidence(), batch.network(),
                batch.partition(), runs, batch.options());
    }

    private static IntervalTraceBatch withoutRoutes(IntervalTraceBatch batch, int interval) {
        List<IntervalTraceBatch.IntervalRun> runs = new java.util.ArrayList<>(batch.runs());
        var original = runs.get(interval);
        runs.set(interval, new IntervalTraceBatch.IntervalRun(original.interval(),
                original.request(), new ModernTracePipeline.Result(
                        original.result().inference(), List.of()), original.usage()));
        return new IntervalTraceBatch(batch.fullRequest(), batch.evidence(), batch.network(),
                batch.partition(), runs, batch.options());
    }

    private static IntervalTraceBatch uBatch() throws Exception {
        return uBatch(uRaster(), false);
    }

    private static IntervalTraceBatch uBatch(LiveBPreviewService.VisibleRaster raster)
            throws Exception {
        return uBatch(raster, false);
    }

    private static IntervalTraceBatch uBatch(LiveBPreviewService.VisibleRaster raster,
            boolean singletonIsland) throws Exception {
        DataSet dataSet = new DataSet();
        double[] east = {-20, -15, -7, 0, 0, -7, -15, -20};
        double[] north = {0, 0, 0, 0, 5, 5, 5, 5};
        List<Node> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < east.length; i++) {
            Node node = new Node(new LatLon(degrees(north[i]), degrees(east[i])));
            node.setOsmId(700 + i, 1);
            node.setModified(false);
            dataSet.addPrimitive(node);
            nodes.add(node);
        }
        Way way = new Way();
        way.setNodes(nodes);
        way.setOsmId(710, 1);
        way.setModified(false);
        dataSet.addPrimitive(way);
        var selection = new SelectionContext(way, 1, 6, nodes.subList(1, 7),
                Set.of(nodes.get(1), nodes.get(6)));
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(dataSet, selection,
                raster, config()));
        var selected = (DetachedWay) captured[0].network().primitives()
                .get(captured[0].specification().selectedWayKey());
        var keys = selected.nodeKeys();
        var reason = ManualJunctionEligibility.Reason.AFFECTED_NODE_TAGGED;
        var start = new SelectedWayIntervalPartitioner.BoundaryConstraint(
                SelectedWayIntervalPartitioner.BoundaryKind.SELECTED_ENDPOINT, 1, keys.get(1),
                false, false, reason);
        var lower = new SelectedWayIntervalPartitioner.BoundaryConstraint(
                SelectedWayIntervalPartitioner.BoundaryKind.FIXED_ISLAND, 3, keys.get(3),
                false, false, reason);
        var upper = new SelectedWayIntervalPartitioner.BoundaryConstraint(
                SelectedWayIntervalPartitioner.BoundaryKind.FIXED_ISLAND, 4, keys.get(4),
                false, false, reason);
        var end = new SelectedWayIntervalPartitioner.BoundaryConstraint(
                SelectedWayIntervalPartitioner.BoundaryKind.SELECTED_ENDPOINT, 6, keys.get(6),
                false, false, reason);
        var island = singletonIsland
                ? new SelectedWayIntervalPartitioner.FixedIsland(new OccurrenceRange(3, 3),
                        List.of(keys.get(3)), Set.of(), List.of(reason), lower, lower,
                        Set.of(), Set.of())
                : new SelectedWayIntervalPartitioner.FixedIsland(new OccurrenceRange(3, 4),
                        keys.subList(3, 5), Set.of(), List.of(reason), lower, upper,
                        Set.of(), Set.of());
        var left = new SelectedWayIntervalPartitioner.SlideInterval(new OccurrenceRange(1, 2),
                keys.subList(1, 3), start, lower);
        var right = singletonIsland
                ? new SelectedWayIntervalPartitioner.SlideInterval(new OccurrenceRange(4, 6),
                        keys.subList(4, 7), lower, end)
                : new SelectedWayIntervalPartitioner.SlideInterval(new OccurrenceRange(5, 6),
                        keys.subList(5, 7), upper, end);
        var partition = new SelectedWayIntervalPartitioner.Partition(selected.key(),
                captured[0].specification().selectedRange(), List.of(island),
                List.of(left, right), List.of(), Set.of(),
                captured[0].network().primitives(),
                captured[0].network().incomingReferrerWatches(),
                captured[0].network().datasetIdentity(), captured[0].network().sourceGeneration());
        return service.computePartitioned(captured[0], partition, CancellationProbe.NONE);
    }

    private static LiveBPreviewService.VisibleRaster uRaster() {
        return uRaster(false);
    }

    private static LiveBPreviewService.VisibleRaster uRaster(boolean extendedCrossingApproaches) {
        return uRaster(extendedCrossingApproaches, 4.0);
    }

    private static LiveBPreviewService.VisibleRaster uRaster(boolean extendedCrossingApproaches,
            double bendHeightMeters) {
        int width = 600;
        int height = 600;
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double east = (x + 0.5 - 300.0) / 6.0;
                double north = (300.0 - y - 0.5) / 6.0;
                double distance = Math.min(
                        distanceToSegment(east, north, -20, 0,
                                extendedCrossingApproaches ? -12 : 0, 0),
                        distanceToSegment(east, north,
                                extendedCrossingApproaches ? -12 : 0, 5, -20, 5));
                distance = Math.min(distance,
                        distanceToSegment(east, north, 0, 0, 0, 5));
                if (extendedCrossingApproaches) {
                    distance = Math.min(distance,
                            distanceToSegment(east, north, -12, 0, -6, bendHeightMeters));
                    distance = Math.min(distance,
                            distanceToSegment(east, north, -6, bendHeightMeters, -4, 0));
                    distance = Math.min(distance,
                            distanceToSegment(east, north, -4, 0, 0, 0));
                    distance = Math.min(distance,
                            distanceToSegment(east, north, 0, 5, -4, 5));
                    distance = Math.min(distance,
                            distanceToSegment(east, north, -4, 5, -6, 5 - bendHeightMeters));
                    distance = Math.min(distance,
                            distanceToSegment(east, north, -6, 5 - bendHeightMeters, -12, 5));
                } else {
                    distance = Math.min(distance,
                            distanceToSegment(east, north, -7, 0, -5, 3));
                    distance = Math.min(distance,
                            distanceToSegment(east, north, -5, 3, 0, 0));
                    distance = Math.min(distance,
                            distanceToSegment(east, north, 0, 5, -5, 2));
                    distance = Math.min(distance,
                            distanceToSegment(east, north, -5, 2, -7, 5));
                }
                double intensity = 0.02 + 0.80 * Math.exp(-0.5 * distance * distance / 1.44);
                int gray = (int) Math.round(255.0 * intensity);
                argb[y * width + x] = 0xff000000 | gray << 16 | gray << 8 | gray;
            }
        }
        return new LiveBPreviewService.VisibleRaster(width, height, argb,
                -50, -50, 50, 50, 1.0, 1.0, OptionalDouble.of(1.0),
                "u-visible-test", "EPSG:3857");
    }

    private static LiveBPreviewService.VisibleRaster overlapRaster() {
        int width = 600;
        int height = 600;
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double east = (x + 0.5 - 300.0) / 6.0;
                double north = (300.0 - y - 0.5) / 6.0;
                double distance = distanceToSegment(east, north, -20, 0, -7, 0);
                distance = Math.min(distance,
                        distanceToSegment(east, north, -7, 0, 0, 1.5));
                distance = Math.min(distance,
                        distanceToSegment(east, north, 0, -5, 0, 5));
                distance = Math.min(distance,
                        distanceToSegment(east, north, 0, 5, -20, 5));
                double intensity = 0.02 + 0.80 * Math.exp(-0.5 * distance * distance / 1.44);
                int gray = (int) Math.round(255.0 * intensity);
                argb[y * width + x] = 0xff000000 | gray << 16 | gray << 8 | gray;
            }
        }
        return new LiveBPreviewService.VisibleRaster(width, height, argb,
                -50, -50, 50, 50, 1.0, 1.0, OptionalDouble.of(1.0),
                "overlap-visible-test", "EPSG:3857");
    }

    private static double distanceToSegment(double x, double y,
            double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double t = Math.max(0.0, Math.min(1.0,
                ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)));
        return Math.hypot(x - ax - t * dx, y - ay - t * dy);
    }

    private static MetricPoint lerp(MetricPoint a, MetricPoint b, double fraction) {
        return new MetricPoint(a.xMeters() + fraction * (b.xMeters() - a.xMeters()),
                a.yMeters() + fraction * (b.yMeters() - a.yMeters()));
    }

    private static MetricPoint originalMetric(IntervalTraceBatch batch, int index) {
        var way = (DetachedWay) batch.network().primitives()
                .get(batch.fullRequest().selectedWayKey());
        var node = (DetachedNode) batch.network().primitives().get(way.nodeKeys().get(index));
        return batch.evidence().coordinateFrame().toMetric(node.coordinate());
    }

    private static FinalRoutePointId existingPointId(ModernTracePipeline.Route route,
            int occurrenceIndex) {
        return route.pointIds().stream()
                .filter(id -> id instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing
                        && existing.originalOccurrenceIndex() == occurrenceIndex)
                .findFirst().orElseThrow();
    }

    private static Map<FinalRoutePointId, MetricPoint> linearGeneratedPositions(
            ModernTracePipeline.Route route) {
        Map<FinalRoutePointId, MetricPoint> result = new java.util.LinkedHashMap<>();
        List<FinalRoutePointId> ids = route.pointIds();
        for (int first = 0; first < ids.size() - 1;) {
            int last = first + 1;
            while (last < ids.size() - 1
                    && ids.get(last) instanceof FinalRoutePointId.GeneratedCandidatePoint) {
                last++;
            }
            MetricPoint a = route.assignments().get(ids.get(first));
            MetricPoint b = route.assignments().get(ids.get(last));
            for (int i = first + 1; i < last; i++) {
                result.put(ids.get(i), lerp(a, b, (double) (i - first) / (last - first)));
            }
            first = last;
        }
        return result;
    }

    private static ModernTracePipeline.Route withGeneratedPositions(IntervalTraceBatch batch,
            ModernTracePipeline.Route original, Map<FinalRoutePointId, MetricPoint> changed) {
        Map<FinalRoutePointId, MetricPoint> positions =
                new java.util.LinkedHashMap<>(original.assignments());
        for (var entry : changed.entrySet()) {
            assertTrue(entry.getKey() instanceof FinalRoutePointId.GeneratedCandidatePoint
                    || entry.getKey() instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing
                            && batch.network().closure().movableExistingNodeKeys()
                                    .contains(existing.nodeKey()),
                    original.pointIds().toString());
            positions.put(entry.getKey(), entry.getValue());
        }
        List<MetricPoint> points = original.pointIds().stream().map(positions::get).toList();
        var hypothesis = original.hypothesis();
        var finalHypothesis = new TraceHypothesis(hypothesis.id(),
                hypothesis.branchSignature(), points, hypothesis.support(),
                hypothesis.objective(), hypothesis.posteriorProbability(),
                hypothesis.diagnostics());
        var image = new ImageCostField(batch.evidence().fields().get(batch.options().fieldName()),
                batch.evidence().transform(), batch.evidence().decisionRegion(),
                batch.evidence().resolution().effectivePitchMeters());
        Map<Integer, MetricPoint> protectedPoints = new java.util.LinkedHashMap<>();
        for (int i = 0; i < original.pointIds().size(); i++) {
            if (original.pointIds().get(i)
                    instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing
                    && batch.network().closure().protectedExistingNodeKeys()
                            .contains(existing.nodeKey())) {
                protectedPoints.put(i, positions.get(original.pointIds().get(i)));
            }
        }
        var quality = new FinalGeometryEvaluator().evaluate(new FinalGeometryEvaluator.Request(
                hypothesis.id(), points, original.pointIds(), image,
                batch.evidence().resolution().effectivePitchMeters(), protectedPoints,
                List.of(), original.geometryChanged(), false, false, false));
        return new ModernTracePipeline.Route(original.rawHypothesis(), finalHypothesis,
                original.pointIds(), positions, original.sourceOwnership(), quality,
                original.cleanupStatus(), true);
    }

    @Test
    void typedMissingIncidentEvidenceDemotesOnlyAnEligibleSimpleT() throws Exception {
        IntervalTraceBatch batch = tBatch(true);
        var result = new FixedIntervalEditPlanComposer().compose(batch, Map.of());
        assertEquals(FixedIntervalEditPlanComposer.Disposition.FROZEN_LOCAL_FAILURE,
                result.intervals().get(0).disposition(), result.intervals().toString());
        assertEquals("T_LOCAL_PRECOMMAND_EVIDENCE", result.intervals().get(0).reason());
        assertFalse(result.applyAvailable());
    }

    @Test
    void frozenLocusReceiverShapePointHasPrivateUnownedProvenance(@TempDir Path directory)
            throws Exception {
        IntervalTraceBatch batch = tBatch(true, false, true);
        FixedIntervalEditPlanComposer.Assessment assessment = null;
        Map<Integer, Integer> choices = Map.of();
        for (int routeIndex = 0; routeIndex < batch.runs().get(0).routes().size(); routeIndex++) {
            choices = Map.of(0, routeIndex);
            assessment = new FixedIntervalEditPlanComposer().compose(batch, choices);
            if (assessment.intervals().get(0).routeIndex() == routeIndex) break;
        }
        var plan = planWithFrozenReceiverShape(batch, assessment);
        assessment = new FixedIntervalEditPlanComposer.Assessment(java.util.Optional.of(plan),
                plan.finalPreviewWays().get(plan.selectedWayKey()), assessment.assignments(),
                assessment.intervals());
        var selectedKey = batch.fullRequest().selectedWayKey();
        var receiverKey = plan.finalPreviewWays().keySet().stream()
                .filter(key -> !key.equals(selectedKey)).findFirst().orElseThrow();
        var unownedShape = plan.createdPrimitives().entrySet().stream()
                .filter(entry -> entry.getValue() instanceof DetachedNode
                        && plan.after().internalIncomingReferrers().getOrDefault(entry.getKey(), Set.of())
                                .equals(Set.of(receiverKey)))
                .findFirst().orElseThrow();

        var bundle = Format15ProductionBundleFactory.createLiveIntervals("test-build", batch,
                assessment, choices, Format15ProductionBundleFactory.VisibleLayerSourceReceipt.unavailable(),
                Format15ProductionBundleFactory.IntervalArtifactStatus.PREVIEW, null, null);
        Path file = directory.resolve("frozen-locus-receiver-shape.zip");
        Format15BundleWriter.write(bundle, file);
        Format15Archive decoded = Format15ArchiveReader.read(file);
        String provenance = new String(decoded.artifact("private/interval-point-provenance.json")
                .orElseThrow().bytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(provenance.contains("PLAN_LOCAL_TOPOLOGY_SHAPE_NODE"));
        Pattern point = Pattern.compile("\\{\\\"sequence\\\":(\\d+),\\\"wayKey\\\":\\\"([^\\\"]+)\\\","
                + "\\\"pointId\\\":(\\{.*?\\}),\\\"ownerInterval\\\":(null|\\d+),"
                + "\\\"latitude\\\":([^,]+),\\\"longitude\\\":([^}]+)\\}");
        Matcher matcher = point.matcher(provenance);
        Map<String, List<IndexedCoordinate>> decodedWays = new LinkedHashMap<>();
        boolean foundShape = false;
        while (matcher.find()) {
            if (matcher.group(3).contains("PLAN_LOCAL_TOPOLOGY_SHAPE_NODE")) {
                assertEquals(unownedShape.getKey().toString(), matcher.group(3).replaceAll(".*\\\"nodeKey\\\":\\\"([^\\\"]+)\\\".*", "$1"));
                assertEquals(receiverKey.toString(), matcher.group(2));
                assertEquals("null", matcher.group(4));
                foundShape = true;
            }
            decodedWays.computeIfAbsent(matcher.group(2), ignored -> new ArrayList<>())
                    .add(new IndexedCoordinate(Integer.parseInt(matcher.group(1)),
                            new GeographicPoint(Double.parseDouble(matcher.group(5)),
                                    Double.parseDouble(matcher.group(6)))));
        }
        assertTrue(foundShape);
        for (var way : plan.finalPreviewWays().entrySet()) {
            List<IndexedCoordinate> decodedWay = decodedWays.get(way.getKey().toString());
            assertTrue(decodedWay != null, "receiver provenance omitted way " + way.getKey());
            decodedWay.sort(Comparator.comparingInt(IndexedCoordinate::index));
            assertEquals(way.getValue(), decodedWay.stream().map(IndexedCoordinate::coordinate).toList());
        }
    }

    private static org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan
            planWithFrozenReceiverShape(IntervalTraceBatch batch,
                    FixedIntervalEditPlanComposer.Assessment assessment) {
        var selectedKey = batch.fullRequest().selectedWayKey();
        var originalSelected = (DetachedWay) batch.network().primitives().get(selectedKey);
        var afterValues = new LinkedHashMap<>(batch.network().primitives());
        List<org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey> selectedNodes =
                new ArrayList<>();
        Map<org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey, GeographicPoint> previewNodePoints =
                new LinkedHashMap<>();
        long nextPlanNode = 1_000_000L;
        for (var key : afterValues.keySet()) {
            if (key.identityKind() == org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.IdentityKind.PLAN_LOCAL) {
                nextPlanNode = Math.max(nextPlanNode, key.id() + 1);
            }
        }
        int cursor = batch.fullRequest().selectedRange().firstIndex();
        for (int runIndex = 0; runIndex < batch.runs().size(); runIndex++) {
            var run = batch.runs().get(runIndex);
            int first = run.interval().traceRange().firstIndex();
            int last = run.interval().traceRange().lastIndex();
            for (int occurrence = cursor; occurrence < first; occurrence++) {
                var key = originalSelected.nodeKeys().get(occurrence);
                selectedNodes.add(key);
                previewNodePoints.put(key, ((DetachedNode) afterValues.get(key)).coordinate());
            }
            int routeIndex = assessment.intervals().get(runIndex).routeIndex();
            var route = run.routes().get(routeIndex);
            for (var sourceId : route.pointIds()) {
                FinalRoutePointId composedId = sourceId;
                if (sourceId instanceof FinalRoutePointId.GeneratedCandidatePoint generated) {
                    if (generated.originalPointIndex() >= 1_000_000) throw new AssertionError();
                    composedId = new FinalRoutePointId.GeneratedCandidatePoint("interval-" + runIndex
                            + ":" + route.hypothesis().id(), runIndex * 1_000_000
                                    + generated.originalPointIndex());
                }
                var point = route.assignments().get(sourceId);
                GeographicPoint geographic = batch.evidence().coordinateFrame().toGeographic(point);
                var nodeKey = composedId instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing
                        ? existing.nodeKey()
                        : org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.planned(
                                org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.Type.NODE,
                                ((FinalRoutePointId.GeneratedCandidatePoint) composedId).originalPointIndex());
                selectedNodes.add(nodeKey);
                previewNodePoints.put(nodeKey, geographic);
                if (nodeKey.identityKind() == org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.IdentityKind.PLAN_LOCAL) {
                    afterValues.put(nodeKey, new DetachedNode(nodeKey, geographic, Map.of(), false, true));
                } else {
                    var existing = (DetachedNode) afterValues.get(nodeKey);
                    if (!existing.coordinate().equals(geographic)) {
                        afterValues.put(nodeKey, new DetachedNode(nodeKey, geographic,
                                existing.tags(), false, true));
                    }
                }
            }
            cursor = last + 1;
        }
        for (int occurrence = cursor; occurrence < originalSelected.nodeKeys().size(); occurrence++) {
            var key = originalSelected.nodeKeys().get(occurrence);
            selectedNodes.add(key);
            previewNodePoints.putIfAbsent(key, ((DetachedNode) afterValues.get(key)).coordinate());
        }
        afterValues.put(selectedKey, new DetachedWay(originalSelected.key(), selectedNodes,
                originalSelected.tags(), false, true));
        int junctionIndex = batch.partition().junctionDispositions().stream()
                .filter(SelectedWayIntervalPartitioner.JunctionDisposition::automaticEligible)
                .findFirst().orElseThrow().selectedOccurrenceIndex();
        var junctionKey = originalSelected.nodeKeys().get(junctionIndex);
        var junctionPoint = previewNodePoints.get(junctionKey);
        if (junctionPoint.equals(((DetachedNode) batch.network().primitives().get(junctionKey)).coordinate())) {
            throw new AssertionError("fixture must move its shared junction");
        }
        var receiverKey = batch.network().closure().editableWayOccurrences().keySet().stream()
                .filter(key -> !key.equals(selectedKey)).findFirst().orElseThrow();
        var receiver = (DetachedWay) afterValues.get(receiverKey);
        var receiverNodes = new ArrayList<>(receiver.nodeKeys());
        int oldJunctionIndex = receiverNodes.indexOf(junctionKey);
        var shapeKey = org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.planned(
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey.Type.NODE,
                nextPlanNode);
        afterValues.put(shapeKey, new DetachedNode(shapeKey,
                ((DetachedNode) batch.network().primitives().get(junctionKey)).coordinate(),
                Map.of(), false, true));
        receiverNodes.add(oldJunctionIndex, shapeKey);
        afterValues.put(receiverKey, new DetachedWay(receiver.key(), receiverNodes,
                receiver.tags(), false, true));
        Map<org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey, Set<org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey>> watches =
                referrerWatches(batch.network(), afterValues);
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot after;
        try {
            after = new org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot(
                    "interval-topology-receiver-after",
                    org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole.PROPOSED_AFTER,
                    batch.network().datasetIdentity(), batch.network().sourceGeneration(),
                    batch.network().closure(), afterValues, watches);
        } catch (IllegalArgumentException failure) {
            throw new AssertionError("receiver fixture ports="
                    + batch.network().closure().externalPorts() + " selected=" + selectedNodes
                    + " receiver=" + receiverNodes, failure);
        }
        Map<org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey, List<GeographicPoint>> previews =
                new LinkedHashMap<>();
        for (var wayKey : List.of(selectedKey, receiverKey)) {
            var way = (DetachedWay) afterValues.get(wayKey);
            previews.put(wayKey, way.nodeKeys().stream().map(key ->
                    ((DetachedNode) afterValues.get(key)).coordinate()).toList());
        }
        return new org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentEditPlan(
                selectedKey, batch.fullRequest().selectedRange(), batch.network(), after,
                batch.evidence().coordinateFrame(), batch.fullRequest().permissions(),
                batch.fullRequest().settingsHash(), batch.evidence().canonicalHash(),
                batch.fullRequest().parameterHash(), "interval-composite:0="
                        + batch.runs().get(0).routes().get(assessment.intervals().get(0).routeIndex())
                                .hypothesis().id(), previews,
                new org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport(
                        org.openstreetmap.josm.plugins.wayheatmaptracer.model.ValidationReport.Disposition.REVIEW_REQUIRED,
                        List.of("test-review-required")));
    }

    private static Map<org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey,
            Set<org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey>> referrerWatches(
                    org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot before,
                    Map<org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey,
                            org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive> after) {
        var priorInternal = before.internalIncomingReferrers();
        Map<org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey,
                Set<org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey>> refs =
                new LinkedHashMap<>();
        after.keySet().forEach(key -> refs.put(key, new java.util.LinkedHashSet<>()));
        after.values().forEach(primitive -> {
            if (primitive instanceof DetachedWay way) {
                way.nodeKeys().forEach(node -> refs.get(node).add(way.key()));
            }
        });
        Map<org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey,
                Set<org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey>> result =
                new LinkedHashMap<>();
        refs.forEach((key, internal) -> {
            var all = new java.util.LinkedHashSet<>(internal);
            var external = new java.util.LinkedHashSet<>(before.incomingReferrerWatches()
                    .getOrDefault(key, Set.of()));
            external.removeAll(priorInternal.getOrDefault(key, Set.of()));
            all.addAll(external);
            result.put(key, Set.copyOf(all));
        });
        return Map.copyOf(result);
    }

    @Test
    void nonLocalPrecommandFailureDoesNotDemoteEligibleSimpleT() throws Exception {
        IntervalTraceBatch batch = tBatch(false);
        var result = new FixedIntervalEditPlanComposer().compose(batch, Map.of());
        assertEquals(FixedIntervalEditPlanComposer.Disposition.BLOCKED_GLOBAL,
                result.intervals().get(0).disposition(), result.intervals().toString());
        assertTrue(result.intervals().get(0).reason().contains("exact external ports"));
        assertFalse(result.applyAvailable());
    }

    @Test
    void stalePartitionParityIsRejectedBeforeEligibleTDemotion() throws Exception {
        IntervalTraceBatch batch = tBatch(true);
        var partition = batch.partition();
        var selected = (DetachedWay) batch.network().primitives()
                .get(batch.fullRequest().selectedWayKey());
        var key = selected.nodeKeys().get(batch.fullRequest().selectedRange().firstIndex());
        var original = (DetachedNode) partition.parityPrimitives().get(key);
        Map<org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey,
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive> altered =
                new java.util.LinkedHashMap<>(partition.parityPrimitives());
        altered.put(key, new DetachedNode(key,
                new GeographicPoint(original.coordinate().latitudeDegrees() + 1.0e-8,
                        original.coordinate().longitudeDegrees()),
                original.tags(), original.deleted(), original.modified()));
        var stale = new SelectedWayIntervalPartitioner.Partition(partition.selectedWayKey(),
                partition.selectedRange(), partition.fixedIslands(), partition.slideIntervals(),
                partition.junctionDispositions(), partition.provedPorts(), altered,
                partition.parityIncomingReferrers(), partition.datasetIdentity(),
                partition.sourceGeneration());
        var staleBatch = new IntervalTraceBatch(batch.fullRequest(), batch.evidence(),
                batch.network(), stale, batch.runs(), batch.options());
        assertThrows(IllegalArgumentException.class,
                () -> new FixedIntervalEditPlanComposer().compose(staleBatch, Map.of()));
    }

    private static IntervalTraceBatch tBatch(boolean completePorts) throws Exception {
        return tBatch(completePorts, true, false);
    }

    private static IntervalTraceBatch tBatch(boolean completePorts,
            boolean reconstructIncidentWays) throws Exception {
        return tBatch(completePorts, reconstructIncidentWays, false);
    }

    private static IntervalTraceBatch tBatch(boolean completePorts,
            boolean reconstructIncidentWays, boolean flatSelected) throws Exception {
        DataSet dataSet = new DataSet();
        List<Node> selectedNodes = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Node node = new Node(new LatLon(degrees(flatSelected ? 0.0 : 0.2 * Math.sin(i)),
                    degrees(-18 + 6 * i)));
            node.setOsmId(600 + i, 1);
            node.setModified(false);
            selectedNodes.add(node);
            dataSet.addPrimitive(node);
        }
        Way selectedWay = new Way();
        selectedWay.setNodes(selectedNodes.subList(3, 7));
        selectedWay.setOsmId(610, 1);
        selectedWay.put("highway", "path");
        selectedWay.setModified(false);
        dataSet.addPrimitive(selectedWay);
        Node south = new Node(new LatLon(degrees(-35), degrees(flatSelected ? 3.6 : 0)));
        south.setOsmId(611, 1);
        south.setModified(false);
        dataSet.addPrimitive(south);
        Node north = new Node(new LatLon(degrees(35), degrees(flatSelected ? -3.4 : 0)));
        north.setOsmId(612, 1);
        north.setModified(false);
        dataSet.addPrimitive(north);
        Node farSouth = new Node(new LatLon(degrees(-70), degrees(flatSelected ? 7.1 : 0)));
        farSouth.setOsmId(614, 1);
        farSouth.setModified(false);
        dataSet.addPrimitive(farSouth);
        Node farNorth = new Node(new LatLon(degrees(70), degrees(flatSelected ? -6.9 : 0)));
        farNorth.setOsmId(615, 1);
        farNorth.setModified(false);
        dataSet.addPrimitive(farNorth);
        Way receiver = new Way();
        receiver.setNodes(completePorts
                ? List.of(farSouth, south, selectedNodes.get(3), north, farNorth)
                : List.of(south, selectedNodes.get(3), north));
        receiver.setOsmId(613, 1);
        receiver.put("highway", "path");
        receiver.setModified(false);
        dataSet.addPrimitive(receiver);
        var selection = new SelectionContext(selectedWay, 0, 2,
                selectedNodes.subList(3, 6), Set.of(selectedNodes.get(3), selectedNodes.get(5)));
        var permissions = new RecoveryPermissions(false, 7.01, 7.01,
                JunctionPolicy.REATTACH, reconstructIncidentWays);
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(dataSet, selection,
                flatSelected ? tRaster(1.0) : tRaster(), config(), true, permissions));
        var partition = SelectedWayIntervalPartitioner.partition(captured[0].network(),
                captured[0].specification());
        assertTrue(partition.junctionDispositions().stream().anyMatch(
                SelectedWayIntervalPartitioner.JunctionDisposition::automaticEligible),
                partition.toString());
        return service.computePartitioned(captured[0], partition, CancellationProbe.NONE);
    }

    @Test
    void secondRankedChoiceRevalidatesOnlyItsIntervalAndChangesPlanIdentity() throws Exception {
        IntervalTraceBatch batch = batch(296.0, 296.0, TrackerMode.CORRIDOR_AWARE, true);
        assertTrue(batch.runs().get(0).routes().size() > 1,
                "production must retain a second-ranked first-interval route");
        var first = new FixedIntervalEditPlanComposer().compose(batch, Map.of());
        var second = new FixedIntervalEditPlanComposer().compose(batch, Map.of(0, 1));
        assertEquals(batch.runs().get(1).routes().get(0).hypothesis().id(),
                second.intervals().get(1).routeIdentity());
        assertEquals(batch.runs().get(0).routes().get(1).hypothesis().id(),
                second.intervals().get(0).routeIdentity());
        assertNotEquals(first.plan().orElseThrow().canonicalHash(),
                second.plan().orElseThrow().canonicalHash());
        var selected = (DetachedWay) batch.network().primitives()
                .get(batch.fullRequest().selectedWayKey());
        var fixedKey = selected.nodeKeys().get(3);
        var firstNodes = ((DetachedWay) first.plan().orElseThrow().after().primitives()
                .get(selected.key())).nodeKeys();
        var secondNodes = ((DetachedWay) second.plan().orElseThrow().after().primitives()
                .get(selected.key())).nodeKeys();
        assertEquals(firstNodes.subList(firstNodes.indexOf(fixedKey), firstNodes.size()),
                secondNodes.subList(secondNodes.indexOf(fixedKey), secondNodes.size()));
        assertEquals(first.assignments().entrySet().stream().filter(entry ->
                entry.getKey() instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.model
                        .FinalRoutePointId.GeneratedCandidatePoint generated
                        && generated.candidateId().startsWith("interval-1:"))
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                                Map.Entry::getValue)),
                second.assignments().entrySet().stream().filter(entry ->
                entry.getKey() instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.model
                        .FinalRoutePointId.GeneratedCandidatePoint generated
                        && generated.candidateId().startsWith("interval-1:"))
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                                Map.Entry::getValue)));
    }

    @Test
    void realCapturedTaggedJunctionPartitionKeepsFixedFootprintExact(@TempDir Path directory)
            throws Exception {
        DataSet dataSet = new DataSet();
        List<Node> nodes = new java.util.ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            Node node = new Node(new LatLon(degrees(0.4 * Math.sin(i * 0.5)),
                    degrees(-50 + 5.0 * i)));
            node.setOsmId(400 + i, 1);
            node.setModified(false);
            nodes.add(node);
            dataSet.addPrimitive(node);
        }
        nodes.get(10).put("highway", "traffic_signals");
        nodes.get(4).put("note", "fixed west arm boundary");
        nodes.get(16).put("note", "fixed east arm boundary");
        nodes.get(17).put("note", "second fixed east arm boundary");
        Way selectedWay = new Way();
        selectedWay.setNodes(nodes);
        selectedWay.setOsmId(450, 1);
        selectedWay.setModified(false);
        dataSet.addPrimitive(selectedWay);
        Node north = new Node(new LatLon(degrees(40), degrees(0)));
        north.setOsmId(451, 1);
        north.setModified(false);
        dataSet.addPrimitive(north);
        Node south = new Node(new LatLon(degrees(-40), degrees(0)));
        south.setOsmId(453, 1);
        south.setModified(false);
        dataSet.addPrimitive(south);
        Way receiver = new Way();
        receiver.setNodes(List.of(south, nodes.get(10), north));
        receiver.setOsmId(452, 1);
        receiver.setModified(false);
        dataSet.addPrimitive(receiver);
        nodes.get(11).put("highway", "traffic_signals");
        Node otherSouth = new Node(new LatLon(degrees(-40), degrees(5)));
        otherSouth.setOsmId(454, 1);
        otherSouth.setModified(false);
        dataSet.addPrimitive(otherSouth);
        Node otherNorth = new Node(new LatLon(degrees(40), degrees(5)));
        otherNorth.setOsmId(455, 1);
        otherNorth.setModified(false);
        dataSet.addPrimitive(otherNorth);
        Way otherReceiver = new Way();
        otherReceiver.setNodes(List.of(otherSouth, nodes.get(11), otherNorth));
        otherReceiver.setOsmId(456, 1);
        otherReceiver.setModified(false);
        dataSet.addPrimitive(otherReceiver);
        var selection = new SelectionContext(selectedWay, 0, 20, nodes,
                Set.of(nodes.get(0), nodes.get(20)));
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(dataSet, selection,
                wideRaster(), config()));
        var partition = SelectedWayIntervalPartitioner.partition(captured[0].network(),
                captured[0].specification());
        assertFalse(partition.fixedIslands().isEmpty());
        assertEquals(1, partition.fixedIslands().size(),
                "overlapping proved junction footprints must union into one exact island");
        assertEquals(2, partition.slideIntervals().size(), partition.toString());
        IntervalTraceBatch batch = service.computePartitioned(captured[0], partition,
                CancellationProbe.NONE);
        var assessment = new FixedIntervalEditPlanComposer().compose(batch, Map.of());
        assertTrue(assessment.applyAvailable(), assessment.intervals().toString());
        var replayBundle = Format15ProductionBundleFactory.withCurrentNumericalPolicy(
                Format15ProductionBundleFactory.createLiveIntervals("test-build", batch,
                assessment, Map.of(), new Format15ProductionBundleFactory.ManagedTileSourceReceipt(
                        1L, 15, "a".repeat(64)),
                Format15ProductionBundleFactory.IntervalArtifactStatus.PREVIEW, null, null),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayInput(
                        batch.fullRequest(), batch.evidence(), batch.network(), batch.options()),
                batch.fullRequest().engine(),
                batch.runs().stream().map(run -> run.result().inference()).toList());
        assertTrue(replayBundle.artifactNames().contains("private/frozen-interval-input.bin"),
                "a real two-interval production batch must include strict replay input");
        Path replayPath = Files.createTempFile("strict-interval-", ".zip");
        try {
            Format15BundleWriter.write(replayBundle, replayPath);
            var archive = Format15ArchiveReader.read(replayPath);
            var replay = Format15ReplayRunner.replayIntervals(archive,
                    archive.sourceIdentityHash(), archive.parameterHash());
            assertTrue(archive.artifact("private/interval-output-components.bin").isPresent());
            assertEquals("MATCH", replay.outputComponentStatus());

            Map<String, Format15Artifact> oldPolicyMembers = new LinkedHashMap<>(replayBundle.artifacts());
            oldPolicyMembers.remove("numerical-policy.json");
            Path oldPolicyPath = directory.resolve("strict-interval-unattested-policy.zip");
            Format15BundleWriter.write(new Format15Bundle(replayBundle.buildIdentity(),
                    replayBundle.sourceIdentityHash(), replayBundle.parameterHash(), oldPolicyMembers), oldPolicyPath);
            Format15Archive oldPolicyArchive = Format15ArchiveReader.read(oldPolicyPath);
            var oldPolicyFailure = assertThrows(ReplayMismatchException.class, () ->
                    Format15ReplayRunner.replayIntervals(oldPolicyArchive,
                        oldPolicyArchive.sourceIdentityHash(), oldPolicyArchive.parameterHash()));
            assertEquals("numerical-policy-unattested", oldPolicyFailure.getMessage());
            assertEquals(batch.runs().size(), replay.outputComponentComparisons().size());
            assertEquals(partition.slideIntervals(), replay.partition().slideIntervals());
            assertEquals(2, replay.batch().runs().size());
            assertEquals(assessment.plan().orElseThrow().canonicalHash(),
                    replay.assessment().plan().orElseThrow().canonicalHash());
            assertEquals("MATCH", replay.outputComponentStatus());

            Map<String, Format15Artifact> legacyMembers = new LinkedHashMap<>(replayBundle.artifacts());
            legacyMembers.remove("private/interval-output-components.bin");
            legacyMembers.remove("private/interval-output-components-summary.json");
            Format15Bundle legacyBundle = new Format15Bundle(replayBundle.buildIdentity(),
                    replayBundle.sourceIdentityHash(), replayBundle.parameterHash(), legacyMembers);
            Path legacyPath = directory.resolve("strict-interval-without-components.zip");
            Format15BundleWriter.write(legacyBundle, legacyPath);
            Format15Archive legacyArchive = Format15ArchiveReader.read(legacyPath);
            var legacyReplay = Format15ReplayRunner.replayIntervals(legacyArchive,
                    legacyArchive.sourceIdentityHash(), legacyArchive.parameterHash());
            assertEquals("UNAVAILABLE", legacyReplay.outputComponentStatus());

            byte[] staleRequest = replayBundle.artifact(
                    "private/interval-output-components.bin").bytes();
            int requestHashOffset = 4 + 4 + 4 + "test-build".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8).length + 4 + 4;
            staleRequest[requestHashOffset] = staleRequest[requestHashOffset] == 'a' ? (byte) 'b' : (byte) 'a';
            Map<String, Format15Artifact> staleMembers = new LinkedHashMap<>(replayBundle.artifacts());
            staleMembers.put("private/interval-output-components.bin", Format15Artifact.binary(
                    "private/interval-output-components.bin", staleRequest));
            Format15Bundle staleBundle = new Format15Bundle(replayBundle.buildIdentity(),
                    replayBundle.sourceIdentityHash(), replayBundle.parameterHash(), staleMembers);
            Path stalePath = directory.resolve("stale-interval-output-components.zip");
            Format15BundleWriter.write(staleBundle, stalePath);
            Format15Archive staleArchive = Format15ArchiveReader.read(stalePath);
            assertThrows(ReplayMismatchException.class, () -> Format15ReplayRunner.replayIntervals(
                    staleArchive, staleArchive.sourceIdentityHash(), staleArchive.parameterHash()));

            byte[] reordered = replayBundle.artifact(
                    "private/interval-output-components.bin").bytes();
            reordered[25] = 1; // first interval index, now out of order
            Map<String, Format15Artifact> reorderedMembers = new LinkedHashMap<>(replayBundle.artifacts());
            reorderedMembers.put("private/interval-output-components.bin", Format15Artifact.binary(
                    "private/interval-output-components.bin", reordered));
            Format15Bundle reorderedBundle = new Format15Bundle(replayBundle.buildIdentity(),
                    replayBundle.sourceIdentityHash(), replayBundle.parameterHash(), reorderedMembers);
            Path reorderedPath = directory.resolve("reordered-interval-output-components.zip");
            Format15BundleWriter.write(reorderedBundle, reorderedPath);
            Format15Archive reorderedArchive = Format15ArchiveReader.read(reorderedPath);
            assertThrows(ReplayMismatchException.class, () -> Format15ReplayRunner.replayIntervals(
                    reorderedArchive, reorderedArchive.sourceIdentityHash(), reorderedArchive.parameterHash()));

            Map<String, Format15Artifact> omitted = new LinkedHashMap<>(replayBundle.artifacts());
            omitted.remove("private/frozen-interval-input.bin");
            var withoutInput = new Format15Bundle(replayBundle.buildIdentity(),
                    replayBundle.sourceIdentityHash(), replayBundle.parameterHash(), omitted);
            Format15BundleWriter.write(withoutInput, replayPath);
            var missing = Format15ArchiveReader.read(replayPath);
            assertThrows(ReplayMismatchException.class, () -> Format15ReplayRunner.replayIntervals(
                    missing, missing.sourceIdentityHash(), missing.parameterHash()));

            byte[] originalPayload = replayBundle.artifact("private/frozen-interval-input.bin").bytes();
            java.nio.ByteBuffer layout = java.nio.ByteBuffer.wrap(originalPayload);
            assertEquals(0x57544952, layout.getInt());
            assertEquals(3, layout.getInt(), "strict frames require the complete interval envelope");
            int sharedBytes = layout.getInt();
            layout.position(layout.position() + sharedBytes);
            int authorityBytes = layout.getInt();
            layout.position(layout.position() + authorityBytes);
            int proofBytes = Short.toUnsignedInt(layout.getShort());
            layout.position(layout.position() + proofBytes);
            int intervalCountOffset = layout.position();
            assertEquals(2, layout.getInt());
            int firstRequestBytes = layout.getInt();
            layout.position(layout.position() + firstRequestBytes);
            int firstSourceOriginOffset = layout.position();
            int firstScalarHashOffset = firstSourceOriginOffset + Double.BYTES + 2;
            for (int corruption : List.of(0, 1, 2, 3)) {
                byte[] alteredPayload = originalPayload.clone();
                if (corruption == 0) {
                    java.nio.ByteBuffer.wrap(alteredPayload).putInt(intervalCountOffset, 1);
                } else if (corruption == 1) {
                    alteredPayload[firstScalarHashOffset] = alteredPayload[firstScalarHashOffset]
                            == 'a' ? (byte) 'b' : (byte) 'a';
                } else if (corruption == 2) {
                    java.nio.ByteBuffer tamperedOrigin = java.nio.ByteBuffer.wrap(alteredPayload);
                    tamperedOrigin.putDouble(firstSourceOriginOffset,
                            tamperedOrigin.getDouble(firstSourceOriginOffset) + 5.0);
                } else {
                    java.nio.ByteBuffer.wrap(alteredPayload).putInt(4, 2);
                }
                Map<String, Format15Artifact> changed = new LinkedHashMap<>(replayBundle.artifacts());
                changed.put("private/frozen-interval-input.bin", Format15Artifact.binary(
                        "private/frozen-interval-input.bin", alteredPayload));
                var tampered = new Format15Bundle(replayBundle.buildIdentity(),
                        replayBundle.sourceIdentityHash(), replayBundle.parameterHash(), changed);
                Format15BundleWriter.write(tampered, replayPath);
                var read = Format15ArchiveReader.read(replayPath);
                ReplayMismatchException failure = assertThrows(ReplayMismatchException.class,
                        () -> Format15ReplayRunner.replayIntervals(read,
                                read.sourceIdentityHash(), read.parameterHash()));
                assertEquals(switch (corruption) {
                    case 0 -> "strict-interval-replay-input-malformed";
                    case 1 -> "strict-interval-production-output-mismatch";
                    case 2 -> "strict-interval-request-derivation-mismatch";
                    default -> "strict-interval-replay-input-malformed";
                }, failure.getMessage());
            }

            String index = new String(replayBundle.artifact("interval-production.json").bytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(index.contains("\"chosenRouteIndex\":0"));
            int proofStart = index.indexOf("\"partitionProofHash\":\"")
                    + "\"partitionProofHash\":\"".length();
            String alteredProof = index.substring(0, proofStart)
                    + (index.charAt(proofStart) == 'a' ? 'b' : 'a')
                    + index.substring(proofStart + 1);
            List<String> alteredIndexes = List.of(
                    index.replaceFirst("\\\"chosenRouteIndex\\\":0", "\\\"chosenRouteIndex\\\":1"),
                    alteredProof,
                    index.replaceFirst("\\\"generation\\\":1", "\\\"generation\\\":2"),
                    index.replaceFirst("\\\"status\\\":\\\"PREVIEW\\\"",
                            "\\\"status\\\":\\\"APPLIED\\\""),
                    index.replaceFirst("\\\"alternativesTruncated\\\":false",
                            "\\\"alternativesTruncated\\\":true"),
                    index.replaceFirst("\\\"FULL_EDIT_PLAN\\\":false",
                            "\\\"FULL_EDIT_PLAN\\\":true"));
            List<String> expectedReasons = List.of("strict-interval-index-choice-mismatch",
                    "strict-interval-index-mismatch", "strict-interval-source-receipt-mismatch",
                    "strict-interval-status-mismatch", "strict-interval-index-choice-mismatch",
                    "strict-interval-index-mismatch");
            for (int changeIndex = 0; changeIndex < alteredIndexes.size(); changeIndex++) {
                String altered = alteredIndexes.get(changeIndex);
                assertNotEquals(index, altered);
                Map<String, Format15Artifact> changed = new LinkedHashMap<>(replayBundle.artifacts());
                changed.put("interval-production.json", Format15Artifact.text(
                        "interval-production.json", altered));
                var tampered = new Format15Bundle(replayBundle.buildIdentity(),
                        replayBundle.sourceIdentityHash(), replayBundle.parameterHash(), changed);
                Format15BundleWriter.write(tampered, replayPath);
                var read = Format15ArchiveReader.read(replayPath);
                ReplayMismatchException failure = assertThrows(ReplayMismatchException.class,
                        () -> Format15ReplayRunner.replayIntervals(read,
                                read.sourceIdentityHash(), read.parameterHash()));
                assertEquals(expectedReasons.get(changeIndex), failure.getMessage());
            }
            for (String name : List.of("private/interval-composed-preview.json",
                    "private/interval-0-routes.json")) {
                Map<String, Format15Artifact> changed = new LinkedHashMap<>(replayBundle.artifacts());
                String text = new String(replayBundle.artifact(name).bytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                changed.put(name, Format15Artifact.text(name, text + " "));
                var tampered = new Format15Bundle(replayBundle.buildIdentity(),
                        replayBundle.sourceIdentityHash(), replayBundle.parameterHash(), changed);
                Format15BundleWriter.write(tampered, replayPath);
                var read = Format15ArchiveReader.read(replayPath);
                assertThrows(ReplayMismatchException.class, () -> Format15ReplayRunner.replayIntervals(
                        read, read.sourceIdentityHash(), read.parameterHash()));
            }
            for (int intervalIndex = 0; intervalIndex < batch.runs().size(); intervalIndex++) {
                assertEquals(batch.runs().get(intervalIndex).request().profileChainage(),
                        replay.batch().runs().get(intervalIndex).request().profileChainage(),
                        "strict replay must retain the factual full-source origin");
            }
        } finally {
            Files.deleteIfExists(replayPath);
        }
        var plan = assessment.plan().orElseThrow();
        var beforeSelected = (DetachedWay) batch.network().primitives().get(plan.selectedWayKey());
        var afterSelected = (DetachedWay) plan.after().primitives().get(plan.selectedWayKey());
        for (var island : partition.fixedIslands()) {
            for (int index = island.range().firstIndex(); index <= island.range().lastIndex(); index++) {
                var key = beforeSelected.nodeKeys().get(index);
                assertTrue(afterSelected.nodeKeys().contains(key));
                assertEquals(batch.network().primitives().get(key), plan.after().primitives().get(key));
            }
        }
    }

    private static LiveBPreviewService.VisibleRaster wideRaster() {
        int width = 800;
        int height = 400;
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            double distance = (y - 200.0) / 5.0;
            double intensity = 0.02 + 0.80 * Math.exp(-0.5 * distance * distance / 1.44);
            int gray = (int) Math.round(255.0 * intensity);
            Arrays.fill(argb, y * width, (y + 1) * width,
                    0xff000000 | gray << 16 | gray << 8 | gray);
        }
        return new LiveBPreviewService.VisibleRaster(width, height, argb,
                -80, -40, 80, 40, 1.2, 1.2, OptionalDouble.of(1.0),
                "wide-visible-test", "EPSG:3857");
    }

    private static LiveBPreviewService.VisibleRaster tRaster() {
        return tRaster(0.0);
    }

    private static LiveBPreviewService.VisibleRaster tRaster(double northMeters) {
        int width = 800;
        int height = 1000;
        int[] argb = new int[width * height];
        double centerRow = (100.0 - northMeters) * 5.0;
        for (int y = 0; y < height; y++) {
            double distance = (y - centerRow) / 5.0;
            double intensity = 0.02 + 0.80 * Math.exp(-0.5 * distance * distance / 1.44);
            int gray = (int) Math.round(255.0 * intensity);
            Arrays.fill(argb, y * width, (y + 1) * width,
                    0xff000000 | gray << 16 | gray << 8 | gray);
        }
        return new LiveBPreviewService.VisibleRaster(width, height, argb,
                -80, -100, 80, 100, 1.2, 1.2, OptionalDouble.of(1.0),
                "t-visible-test", "EPSG:3857");
    }

    private static IntervalTraceBatch batch() throws Exception {
        return batch(296.0, 296.0);
    }

    private static IntervalTraceBatch batch(double leftCenter, double rightCenter) throws Exception {
        return batch(leftCenter, rightCenter, TrackerMode.CORRIDOR_AWARE);
    }

    private static IntervalTraceBatch batch(double leftCenter, double rightCenter,
            TrackerMode engine) throws Exception {
        return batch(leftCenter, rightCenter, engine, false);
    }

    private static IntervalTraceBatch batch(double leftCenter, double rightCenter,
            TrackerMode engine, boolean dualLeft) throws Exception {
        return batch(leftCenter, rightCenter, engine, dualLeft, false);
    }

    private static IntervalTraceBatch batch(double leftCenter, double rightCenter,
            TrackerMode engine, boolean dualLeft, boolean crossingContext) throws Exception {
        return batch(leftCenter, rightCenter, engine, dualLeft, crossingContext, false);
    }

    private static IntervalTraceBatch batch(double leftCenter, double rightCenter,
            TrackerMode engine, boolean dualLeft, boolean crossingContext, boolean shortInternalGap) throws Exception {
        return batch(leftCenter, rightCenter, engine, dualLeft, crossingContext, shortInternalGap, false);
    }

    private static IntervalTraceBatch batch(double leftCenter, double rightCenter,
            TrackerMode engine, boolean dualLeft, boolean crossingContext, boolean shortInternalGap,
            boolean sourcePreservingBoundary) throws Exception {
        return batch(leftCenter, rightCenter, engine, dualLeft, crossingContext, shortInternalGap,
                sourcePreservingBoundary, false);
    }

    private static IntervalTraceBatch batch(double leftCenter, double rightCenter,
            TrackerMode engine, boolean dualLeft, boolean crossingContext, boolean shortInternalGap,
            boolean sourcePreservingBoundary, boolean remoteUnselectedEndpoints) throws Exception {
        return batch(leftCenter, rightCenter, engine, dualLeft, crossingContext, shortInternalGap,
                sourcePreservingBoundary, remoteUnselectedEndpoints, false);
    }

    private static IntervalTraceBatch batch(double leftCenter, double rightCenter,
            TrackerMode engine, boolean dualLeft, boolean crossingContext, boolean shortInternalGap,
            boolean sourcePreservingBoundary, boolean remoteUnselectedEndpoints, boolean coincidentOriginalOccurrences) throws Exception {
        return batch(leftCenter, rightCenter, engine, dualLeft, crossingContext, shortInternalGap,
                sourcePreservingBoundary, remoteUnselectedEndpoints, coincidentOriginalOccurrences, AlignmentMode.PRECISE_SHAPE);
    }

    private static IntervalTraceBatch batch(double leftCenter, double rightCenter,
            TrackerMode engine, boolean dualLeft, boolean crossingContext, boolean shortInternalGap,
            boolean sourcePreservingBoundary, boolean remoteUnselectedEndpoints, boolean coincidentOriginalOccurrences,
            AlignmentMode mode) throws Exception {
        DataSet dataSet = new DataSet();
        boolean moveExisting = mode == AlignmentMode.MOVE_EXISTING_NODES;
        double[] east = moveExisting ? new double[] {-20, -16, -12, -8, -4, 0, 4, 8, 12, 16, 20}
                : sourcePreservingBoundary ? new double[] {-40, -32, -16, 0, 16, 32, 40}
                : new double[] {-19, -14, -8, 0, 8, 14, 19};
        if (remoteUnselectedEndpoints) { east[0] = -200_000.0; east[east.length - 1] = 200_000.0; }
        if (coincidentOriginalOccurrences) east[2] = east[3];
        double[] north = moveExisting ? new double[] {0, 0.2, 0.4, 0.7, 0.9, 1.0, 0.9, 0.7, 0.4, 0.2, 0}
                : new double[] {0, 0.2, 0.7, 1.0, 0.5, 0.1, 0};
        java.util.ArrayList<Node> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < east.length; i++) {
            Node node = new Node(new LatLon(degrees(sourcePreservingBoundary ? 0.0 : north[i]), degrees(east[i])));
            node.setOsmId(100 + i, 1);
            node.setModified(false);
            nodes.add(node);
            dataSet.addPrimitive(node);
        }
        Way way = new Way();
        way.setNodes(nodes);
        way.setOsmId(110, 1);
        way.setModified(false);
        dataSet.addPrimitive(way);
        if (crossingContext) {
            Node south = new Node(new LatLon(degrees(-10), degrees(-11)));
            south.setOsmId(201, 1);
            south.setModified(false);
            dataSet.addPrimitive(south);
            Node crossingNorth = new Node(new LatLon(degrees(10), degrees(-11)));
            crossingNorth.setOsmId(202, 1);
            crossingNorth.setModified(false);
            dataSet.addPrimitive(crossingNorth);
            Way crossing = new Way();
            crossing.setNodes(List.of(south, crossingNorth));
            crossing.setOsmId(203, 1);
            crossing.setModified(false);
            dataSet.addPrimitive(crossing);
        }
        int middle = east.length / 2, last = east.length - 2;
        var selection = new SelectionContext(way, 1, last, nodes.subList(1, last + 1),
                Set.of(nodes.get(1), nodes.get(last)));
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(dataSet, selection,
                raster(leftCenter, rightCenter, dualLeft, shortInternalGap, sourcePreservingBoundary,
                        engine == TrackerMode.PROBABILISTIC && shortInternalGap ? 21 : 11), config(engine, mode)));
        var selected = (DetachedWay) captured[0].network().primitives()
                .get(captured[0].specification().selectedWayKey());
        var keys = selected.nodeKeys();
        var reason = ManualJunctionEligibility.Reason.AFFECTED_NODE_TAGGED;
        var endpoint1 = new SelectedWayIntervalPartitioner.BoundaryConstraint(
                SelectedWayIntervalPartitioner.BoundaryKind.SELECTED_ENDPOINT, 1, keys.get(1),
                false, false, reason);
        var fixed = new SelectedWayIntervalPartitioner.BoundaryConstraint(
                SelectedWayIntervalPartitioner.BoundaryKind.FIXED_ISLAND, middle, keys.get(middle),
                false, false, reason);
        var endpoint5 = new SelectedWayIntervalPartitioner.BoundaryConstraint(
                SelectedWayIntervalPartitioner.BoundaryKind.SELECTED_ENDPOINT, last, keys.get(last),
                false, false, reason);
        var island = new SelectedWayIntervalPartitioner.FixedIsland(new OccurrenceRange(middle, middle),
                List.of(keys.get(middle)), Set.of(keys.get(middle)), List.of(reason), fixed, fixed,
                Set.of(), Set.of());
        var left = new SelectedWayIntervalPartitioner.SlideInterval(new OccurrenceRange(1, middle - 1),
                keys.subList(1, middle), endpoint1, fixed);
        var right = new SelectedWayIntervalPartitioner.SlideInterval(new OccurrenceRange(middle + 1, last),
                keys.subList(middle + 1, last + 1), fixed, endpoint5);
        var partition = new SelectedWayIntervalPartitioner.Partition(
                captured[0].specification().selectedWayKey(),
                captured[0].specification().selectedRange(), List.of(island), List.of(left, right),
                List.of(), Set.of(), captured[0].network().primitives(),
                captured[0].network().incomingReferrerWatches(),
                captured[0].network().datasetIdentity(), captured[0].network().sourceGeneration());
        return service.computePartitioned(captured[0], partition, CancellationProbe.NONE);
    }

    private static LiveBPreviewService.VisibleRaster raster() {
        return raster(296.0, 296.0);
    }

    private static LiveBPreviewService.VisibleRaster raster(double leftCenter, double rightCenter) {
        return raster(leftCenter, rightCenter, false);
    }

    private static LiveBPreviewService.VisibleRaster raster(double leftCenter, double rightCenter,
            boolean dualLeft) {
        return raster(leftCenter, rightCenter, dualLeft, false);
    }

    private static LiveBPreviewService.VisibleRaster raster(double leftCenter, double rightCenter,
            boolean dualLeft, boolean shortInternalGap) {
        return raster(leftCenter, rightCenter, dualLeft, shortInternalGap, false);
    }

    private static LiveBPreviewService.VisibleRaster raster(double leftCenter, double rightCenter,
            boolean dualLeft, boolean shortInternalGap, boolean sourcePreservingBoundary) {
        return raster(leftCenter, rightCenter, dualLeft, shortInternalGap, sourcePreservingBoundary, 11);
    }

    private static LiveBPreviewService.VisibleRaster raster(double leftCenter, double rightCenter,
            boolean dualLeft, boolean shortInternalGap, boolean sourcePreservingBoundary, int shortGapPixelCount) {
        int width = 600;
        int height = 600;
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double center = x < width / 2 ? leftCenter : rightCenter;
                double distance = (y - center) / 6.0;
                double intensity = 0.02 + 0.80 * Math.exp(-0.5 * distance * distance / 1.44);
                if (dualLeft && x < width / 2) {
                    double other = (y - 270.0) / 6.0;
                    intensity = Math.max(intensity,
                            0.02 + 0.78 * Math.exp(-0.5 * other * other / 1.44));
                }
                if (shortInternalGap && x >= 239 && x < 239 + shortGapPixelCount) intensity = 0.0;
                if (sourcePreservingBoundary && x >= 107 && x <= 167) intensity = 0.0;
                int gray = (int) Math.round(255.0 * intensity);
                argb[y * width + x] = 0xff000000 | gray << 16 | gray << 8 | gray;
            }
        }
        return new LiveBPreviewService.VisibleRaster(width, height, argb,
                -50.0, -50.0, 50.0, 50.0, 1.0, 1.0,
                OptionalDouble.of(1.0), "visible-test", "EPSG:3857");
    }

    private static AlignmentConfig config() {
        return config(TrackerMode.CORRIDOR_AWARE);
    }

    private static AlignmentConfig config(TrackerMode engine) {
        return config(engine, AlignmentMode.PRECISE_SHAPE);
    }

    private static AlignmentConfig config(TrackerMode engine, AlignmentMode mode) {
        ManagedHeatmapConfig heatmap = new ManagedHeatmapConfig("", "", "", "", "all", "hot", "", ".*",
                mode, engine, false, false,
                false, false, false, false, false, false, false, false,
                7, 4, 3.0, InferenceMode.RAW_HIGH_RESOLUTION, 15, 15,
                7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, 0L);
        return new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled());
    }

    private static double degrees(double meters) {
        return Math.toDegrees(meters / 6_378_137.0);
    }
}
