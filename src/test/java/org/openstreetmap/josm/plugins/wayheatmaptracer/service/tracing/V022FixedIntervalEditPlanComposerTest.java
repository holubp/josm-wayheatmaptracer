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

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalRoutePointId;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesis;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class V022FixedIntervalEditPlanComposerTest {
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
        var missing = new IntervalTraceBatch.IntervalRun(first.interval(), first.request(),
                new ModernTracePipeline.Result(first.result().inference(), List.of()), first.usage());
        var unavailable = new IntervalTraceBatch(batch.fullRequest(), batch.evidence(),
                batch.network(), batch.partition(), List.of(missing, batch.runs().get(1)),
                batch.options());
        assertThrows(IllegalArgumentException.class,
                () -> new FixedIntervalEditPlanComposer().compose(unavailable, Map.of(0, 0)));
        var assessment = new FixedIntervalEditPlanComposer().compose(unavailable, Map.of());
        assertEquals(FixedIntervalEditPlanComposer.Disposition.FROZEN_LOCAL_FAILURE,
                assessment.intervals().get(0).disposition());
        assertEquals("NO_PRODUCTION_ROUTE", assessment.intervals().get(0).reason());
    }

    @Test
    void capturedSurroundingCrossingBlocksTheCompleteComposedPlan() throws Exception {
        IntervalTraceBatch batch = batch(296.0, 296.0, TrackerMode.CORRIDOR_AWARE,
                false, true);
        var assessment = new FixedIntervalEditPlanComposer().compose(batch, Map.of());
        assertFalse(assessment.applyAvailable());
        assertEquals(List.of(FixedIntervalEditPlanComposer.Disposition.BLOCKED_GLOBAL,
                FixedIntervalEditPlanComposer.Disposition.BLOCKED_GLOBAL),
                assessment.intervals().stream().map(
                        FixedIntervalEditPlanComposer.IntervalAssessment::disposition).toList(),
                assessment.intervals().toString());
        assertTrue(assessment.plan().orElseThrow().validation().findingCodes().stream()
                .anyMatch(code -> code.startsWith("final-topology:")));
    }

    @Test
    void twoIndividuallySafeIntervalRoutesAreBlockedWhenTheirFinalSegmentsCross() throws Exception {
        IntervalTraceBatch source = uBatch();
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
        MetricPoint leftBend = new MetricPoint(lower.xMeters() - 5.0,
                lower.yMeters() + 3.0);
        MetricPoint rightBend = new MetricPoint(upper.xMeters() - 5.0,
                upper.yMeters() - 3.0);
        var straightLeft = withGeneratedPositions(source, left, Map.of(
                existingPointId(left, 2), originalMetric(source, 2)));
        var straightRight = withGeneratedPositions(source, right, Map.of(
                existingPointId(right, 5), originalMetric(source, 5)));
        var safeLeft = withGeneratedPositions(source, straightLeft,
                linearGeneratedPositions(straightLeft));
        var safeRight = withGeneratedPositions(source, straightRight,
                linearGeneratedPositions(straightRight));
        var alteredLeft = withGeneratedPositions(source, safeLeft, Map.of(
                left.pointIds().get(left.pointIds().size() - 2), leftBend));
        var alteredRight = withGeneratedPositions(source, safeRight, Map.of(
                right.pointIds().get(1), rightBend));
        assertNotEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED,
                alteredLeft.quality().disposition(), alteredLeft.quality().toString());
        assertNotEquals(FinalGeometryEvaluator.Disposition.HARD_BLOCKED,
                alteredRight.quality().disposition(), alteredRight.quality().toString());
        var composer = new FixedIntervalEditPlanComposer();
        var safe = withRoute(withRoute(source, 0, safeLeft), 1, safeRight);
        var leftAlone = composer.compose(withoutRoutes(withRoute(safe, 0, alteredLeft), 1),
                Map.of());
        var rightAlone = composer.compose(withoutRoutes(withRoute(safe, 1, alteredRight), 0),
                Map.of());
        assertTrue(leftAlone.applyAvailable(), leftAlone.intervals().toString());
        assertTrue(rightAlone.applyAvailable(), rightAlone.intervals().toString());
        var both = composer.compose(withRoute(withRoute(safe, 0, alteredLeft), 1,
                alteredRight), Map.of());
        assertFalse(both.applyAvailable());
        assertEquals(List.of(FixedIntervalEditPlanComposer.Disposition.BLOCKED_GLOBAL,
                FixedIntervalEditPlanComposer.Disposition.BLOCKED_GLOBAL),
                both.intervals().stream().map(
                        FixedIntervalEditPlanComposer.IntervalAssessment::disposition).toList(),
                both.intervals().toString());
        assertTrue(both.plan().orElseThrow().validation().findingCodes().stream()
                .anyMatch(code -> code.equals("final-topology:CROSSING")
                        || code.equals("final-topology:COLLINEAR_OVERLAP")));
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
                uRaster(), config()));
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
        var island = new SelectedWayIntervalPartitioner.FixedIsland(new OccurrenceRange(3, 4),
                keys.subList(3, 5), Set.of(), List.of(reason), lower, upper, Set.of(), Set.of());
        var left = new SelectedWayIntervalPartitioner.SlideInterval(new OccurrenceRange(1, 2),
                keys.subList(1, 3), start, lower);
        var right = new SelectedWayIntervalPartitioner.SlideInterval(new OccurrenceRange(5, 6),
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
        int width = 600;
        int height = 600;
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double east = (x + 0.5 - 300.0) / 6.0;
                double north = (300.0 - y - 0.5) / 6.0;
                double distance = Math.min(
                        distanceToSegment(east, north, -20, 0, 0, 0),
                        distanceToSegment(east, north, 0, 5, -20, 5));
                distance = Math.min(distance,
                        distanceToSegment(east, north, 0, 0, 0, 5));
                distance = Math.min(distance,
                        distanceToSegment(east, north, -7, 0, -5, 3));
                distance = Math.min(distance,
                        distanceToSegment(east, north, -5, 3, 0, 0));
                distance = Math.min(distance,
                        distanceToSegment(east, north, 0, 5, -5, 2));
                distance = Math.min(distance,
                        distanceToSegment(east, north, -5, 2, -7, 5));
                double intensity = 0.02 + 0.80 * Math.exp(-0.5 * distance * distance / 1.44);
                int gray = (int) Math.round(255.0 * intensity);
                argb[y * width + x] = 0xff000000 | gray << 16 | gray << 8 | gray;
            }
        }
        return new LiveBPreviewService.VisibleRaster(width, height, argb,
                -50, -50, 50, 50, 1.0, 1.0, OptionalDouble.of(1.0),
                "u-visible-test", "EPSG:3857");
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
        DataSet dataSet = new DataSet();
        List<Node> selectedNodes = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Node node = new Node(new LatLon(degrees(0.2 * Math.sin(i)), degrees(-18 + 6 * i)));
            node.setOsmId(600 + i, 1);
            node.setModified(false);
            selectedNodes.add(node);
            dataSet.addPrimitive(node);
        }
        Way selectedWay = new Way();
        selectedWay.setNodes(selectedNodes.subList(3, 7));
        selectedWay.setOsmId(610, 1);
        selectedWay.setModified(false);
        dataSet.addPrimitive(selectedWay);
        Node south = new Node(new LatLon(degrees(-35), degrees(0)));
        south.setOsmId(611, 1);
        south.setModified(false);
        dataSet.addPrimitive(south);
        Node north = new Node(new LatLon(degrees(35), degrees(0)));
        north.setOsmId(612, 1);
        north.setModified(false);
        dataSet.addPrimitive(north);
        Node farSouth = new Node(new LatLon(degrees(-70), degrees(0)));
        farSouth.setOsmId(614, 1);
        farSouth.setModified(false);
        dataSet.addPrimitive(farSouth);
        Node farNorth = new Node(new LatLon(degrees(70), degrees(0)));
        farNorth.setOsmId(615, 1);
        farNorth.setModified(false);
        dataSet.addPrimitive(farNorth);
        Way receiver = new Way();
        receiver.setNodes(completePorts
                ? List.of(farSouth, south, selectedNodes.get(3), north, farNorth)
                : List.of(south, selectedNodes.get(3), north));
        receiver.setOsmId(613, 1);
        receiver.setModified(false);
        dataSet.addPrimitive(receiver);
        var selection = new SelectionContext(selectedWay, 0, 2,
                selectedNodes.subList(3, 6), Set.of(selectedNodes.get(3), selectedNodes.get(5)));
        var permissions = new RecoveryPermissions(false, 7.01, 7.01,
                JunctionPolicy.REATTACH, true);
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(dataSet, selection,
                tRaster(), config(), true, permissions));
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
    void realCapturedTaggedJunctionPartitionKeepsFixedFootprintExact() throws Exception {
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
        int width = 800;
        int height = 1000;
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            double distance = (y - 500.0) / 5.0;
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
        DataSet dataSet = new DataSet();
        double[] east = {-19, -14, -8, 0, 8, 14, 19};
        double[] north = {0, 0.2, 0.7, 1.0, 0.5, 0.1, 0};
        java.util.ArrayList<Node> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < east.length; i++) {
            Node node = new Node(new LatLon(degrees(north[i]), degrees(east[i])));
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
        var selection = new SelectionContext(way, 1, 5, nodes.subList(1, 6),
                Set.of(nodes.get(1), nodes.get(5)));
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] captured = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> captured[0] = service.capture(dataSet, selection,
                raster(leftCenter, rightCenter, dualLeft), config(engine)));
        var selected = (DetachedWay) captured[0].network().primitives()
                .get(captured[0].specification().selectedWayKey());
        var keys = selected.nodeKeys();
        var reason = ManualJunctionEligibility.Reason.AFFECTED_NODE_TAGGED;
        var endpoint1 = new SelectedWayIntervalPartitioner.BoundaryConstraint(
                SelectedWayIntervalPartitioner.BoundaryKind.SELECTED_ENDPOINT, 1, keys.get(1),
                false, false, reason);
        var fixed = new SelectedWayIntervalPartitioner.BoundaryConstraint(
                SelectedWayIntervalPartitioner.BoundaryKind.FIXED_ISLAND, 3, keys.get(3),
                false, false, reason);
        var endpoint5 = new SelectedWayIntervalPartitioner.BoundaryConstraint(
                SelectedWayIntervalPartitioner.BoundaryKind.SELECTED_ENDPOINT, 5, keys.get(5),
                false, false, reason);
        var island = new SelectedWayIntervalPartitioner.FixedIsland(new OccurrenceRange(3, 3),
                List.of(keys.get(3)), Set.of(keys.get(3)), List.of(reason), fixed, fixed,
                Set.of(), Set.of());
        var left = new SelectedWayIntervalPartitioner.SlideInterval(new OccurrenceRange(1, 2),
                keys.subList(1, 3), endpoint1, fixed);
        var right = new SelectedWayIntervalPartitioner.SlideInterval(new OccurrenceRange(4, 5),
                keys.subList(4, 6), fixed, endpoint5);
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
        ManagedHeatmapConfig heatmap = new ManagedHeatmapConfig("", "", "", "", "all", "hot", "", ".*",
                AlignmentMode.PRECISE_SHAPE, engine, false, false,
                false, false, false, false, false, false, false, false,
                7, 4, 3.0, InferenceMode.RAW_HIGH_RESOLUTION, 15, 15,
                7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, 0L);
        return new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled());
    }

    private static double degrees(double meters) {
        return Math.toDegrees(meters / 6_378_137.0);
    }
}
