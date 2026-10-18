package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** Actual live capture, production inference and immutable plan authority regressions. */
class V022SharedNodeDisconnectionPlannerTest {
    @BeforeAll static void configure() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @ParameterizedTest
    @EnumSource(value = AlignmentMode.class, names = {"PRECISE_SHAPE", "MOVE_EXISTING_NODES"})
    void multipleChoicesRecomputeAndReplaceOnlySelectedOccurrences(AlignmentMode mode) throws Exception {
        Fixture fixture = fixture(false);
        var result = compute(fixture, mode, Map.of(1, key(fixture.nodes().get(1)),
                2, key(fixture.nodes().get(2))));
        AlignmentEditPlan plan = result.plan();
        DetachedWay proposed = (DetachedWay) plan.after().primitives().get(plan.selectedWayKey());
        assertEquals(result.original().canonicalHash(), plan.disconnectionSource().canonicalHash());
        assertEquals(result.original().primitives(), plan.before().primitives());
        assertEquals(Set.of(plan.selectedWayKey()), plan.affectedWayKeys());
        for (int occurrence : List.of(1, 2)) {
            PrimitiveKey original = key(fixture.nodes().get(occurrence));
            assertFalse(proposed.nodeKeys().contains(original));
            assertEquals(plan.before().primitives().get(original), plan.after().primitives().get(original));
            assertTrue(plan.before().closure().protectedExistingNodeKeys().contains(original));
            var id = new FinalRoutePointId.ExistingWayNodeOccurrence(plan.selectedWayKey(), original, occurrence);
            MetricPoint assigned = result.computed().pipeline().routes().get(0).assignments().get(id);
            assertNotNull(assigned, "the original occurrence remains immutable source provenance");
            assertTrue(assigned.yMeters() > 1.0, "production reconstruction must release the chosen junction");
        }
        assertEquals(plan.before().primitives().get(key(fixture.nodes().get(0))),
                plan.after().primitives().get(key(fixture.nodes().get(0))));
        assertEquals(plan.before().primitives().get(key(fixture.nodes().get(3))),
                plan.after().primitives().get(key(fixture.nodes().get(3))));
        plan.before().primitives().forEach((key, value) -> {
            if (!key.equals(plan.selectedWayKey())) assertEquals(value, plan.after().primitives().get(key));
        });
        assertEquals(Map.of("highway", "path", "name", "selected"), proposed.tags());
        if (mode == AlignmentMode.MOVE_EXISTING_NODES) {
            assertEquals(2, plan.createdPrimitives().size(), "no generic new-node authority in Move Nodes");
            assertEquals(fixture.nodes().size(), proposed.nodeKeys().size());
        }
        assertTrue(fixture.nodes().stream().noneMatch(Node::isModified));
        assertTrue(fixture.data().getWays().stream().noneMatch(Way::isModified));
    }

    @ParameterizedTest
    @EnumSource(value = AlignmentMode.class, names = {"PRECISE_SHAPE", "MOVE_EXISTING_NODES"})
    void partialSelectionAndUnchosenOutsideOccurrencesRemainProtected(AlignmentMode mode) throws Exception {
        Fixture fixture = fixture(true);
        var result = compute(fixture, mode, Map.of(2, key(fixture.nodes().get(2)),
                3, key(fixture.nodes().get(3))));
        AlignmentEditPlan plan = result.plan();
        DetachedWay proposed = (DetachedWay) plan.after().primitives().get(plan.selectedWayKey());
        assertEquals(key(fixture.nodes().get(0)), proposed.nodeKeys().get(0));
        assertEquals(key(fixture.nodes().get(5)), proposed.nodeKeys().get(proposed.nodeKeys().size() - 1));
        assertFalse(proposed.nodeKeys().contains(key(fixture.nodes().get(2))));
        assertFalse(proposed.nodeKeys().contains(key(fixture.nodes().get(3))));
        for (int occurrence : List.of(0, 1, 4, 5)) {
            PrimitiveKey key = key(fixture.nodes().get(occurrence));
            assertEquals(plan.before().primitives().get(key), plan.after().primitives().get(key));
        }
    }

    @Test void selectedEndpointReplacementRemainsAtItsExactCapturedCoordinate() throws Exception {
        Fixture fixture = fixture(false);
        Node sharedEndpoint = fixture.nodes().get(0);
        Node other = liveNode(90, -8, -8);
        fixture.data().addPrimitive(other);
        fixture.data().addPrimitive(liveWay(91, List.of(sharedEndpoint, other), Map.of()));
        var result = compute(fixture, AlignmentMode.MOVE_EXISTING_NODES,
                Map.of(0, key(sharedEndpoint), 1, key(fixture.nodes().get(1)), 2, key(fixture.nodes().get(2))));
        DetachedWay after = (DetachedWay) result.plan().after().primitives().get(result.plan().selectedWayKey());
        assertNotEquals(key(sharedEndpoint), after.nodeKeys().get(0));
        assertEquals(((DetachedNode) result.plan().before().primitives().get(key(sharedEndpoint))).coordinate(),
                ((DetachedNode) result.plan().after().primitives().get(after.nodeKeys().get(0))).coordinate());
        assertTrue(result.plan().validation().reviewRequired(), "coincident disconnected contact remains reviewable");
        var details = ModernSingleWayEditPlanAdapter.assessFinalTopology(result.plan(),
                result.computed().evidence(), 7.01);
        assertTrue(details.contacts().stream().anyMatch(contact ->
                contact.disposition() == ContextContactAssessment.Disposition.REVIEW_REQUIRED));
        assertTrue(details.findingCodes().contains(ContextContactAssessment.REVIEW_CODE));
        assertTrue(ModernSingleWayEditPlanAdapter.assessFinalTopology(result.plan().before(),
                result.plan().after().primitives(), result.plan().selectedWayKey(), result.plan().selectedRange(),
                result.computed().evidence(), 7.01).findingCodes().contains("final-topology:VERTEX_TOUCH"),
                "raw callers without validated destination mapping retain conservative refusal");
    }

    @ParameterizedTest
    @EnumSource(value = TrackerMode.class, names = {"HYBRID", "PROBABILISTIC"})
    void sourceBoundIntentSurvivesActualProductionEngineRequests(TrackerMode engine) throws Exception {
        Fixture fixture = fixture(false);
        Result result = compute(fixture, AlignmentMode.PRECISE_SHAPE,
                Map.of(1, key(fixture.nodes().get(1)), 2, key(fixture.nodes().get(2))), engine);
        assertEquals(engine, result.computed().pipeline().inference().engine());
        assertEquals(result.original().canonicalHash(), result.computed().request().disconnectionSource().canonicalHash());
        assertEquals(2, result.plan().disconnectedOccurrenceReplacements().size());
    }

    @ParameterizedTest
    @EnumSource(value = AlignmentMode.class, names = {"PRECISE_SHAPE", "MOVE_EXISTING_NODES"})
    void removingIslandRecomputesOriginallyMovableOrdinaryNeighbors(AlignmentMode mode) throws Exception {
        Fixture fixture = ordinaryNeighborFixture();
        Result result = compute(fixture, mode, Map.of(2, key(fixture.nodes().get(2))));
        for (int occurrence : List.of(1, 3)) {
            PrimitiveKey key = key(fixture.nodes().get(occurrence));
            assertTrue(result.conservative().closure().protectedExistingNodeKeys().contains(key));
            assertTrue(result.original().closure().movableExistingNodeKeys().contains(key));
            assertTrue(result.plan().before().closure().movableExistingNodeKeys().contains(key));
            var moved = (DetachedNode) result.plan().after().primitives().get(key);
            assertTrue(result.plan().metricFrame().toMetric(moved.coordinate()).yMeters() > 1.0,
                    "ordinary neighbors frozen only by the removed island must be recomputed");
        }
    }

    @ParameterizedTest
    @EnumSource(value = AlignmentMode.class, names = {"PRECISE_SHAPE", "MOVE_EXISTING_NODES"})
    void productionIntervalCompositionKeepsUnchosenIslandWhileRecomputingChosenJunction(AlignmentMode mode)
            throws Exception {
        Fixture fixture = separatedJunctionFixture();
        Result result = compute(fixture, mode, Map.of(10, key(fixture.nodes().get(10))));
        assertTrue(result.computed().partitioned());
        assertFalse(result.computed().intervalBatch().runs().isEmpty());
        var selected = (DetachedWay) result.plan().after().primitives().get(result.plan().selectedWayKey());
        assertFalse(selected.nodeKeys().contains(key(fixture.nodes().get(10))));
        assertTrue(selected.nodeKeys().contains(key(fixture.nodes().get(30))));
        var destination = result.plan().disconnectedOccurrenceReplacements().get(10);
        assertTrue(result.plan().metricFrame().toMetric(
                ((DetachedNode) result.plan().after().primitives().get(destination)).coordinate()).yMeters() > 1.0,
                result.intervalSummary());
        result.computed().intervalBatch().partition().fixedIslands().forEach(island ->
                island.occurrenceKeys().forEach(key -> assertEquals(result.plan().before().primitives().get(key),
                        result.plan().after().primitives().get(key))));
    }

    @Test void replacementMappingAndSourceAreImmutableAndMandatory() throws Exception {
        Fixture fixture = fixture(false);
        AlignmentEditPlan plan = compute(fixture, AlignmentMode.MOVE_EXISTING_NODES,
                Map.of(1, key(fixture.nodes().get(1)), 2, key(fixture.nodes().get(2)))).plan();
        assertEquals(2, plan.disconnectedOccurrenceReplacements().size());
        assertEquals(Set.copyOf(plan.disconnectedOccurrenceReplacements().values()), plan.createdPrimitives().keySet());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.disconnectedOccurrenceReplacements().clear());
        assertThrows(IllegalArgumentException.class, () -> copyPlan(plan, plan.after(), null,
                plan.disconnectedOccurrenceReplacements()));
        assertThrows(IllegalArgumentException.class, () -> copyPlan(plan, plan.after(), plan.disconnectionSource(), Map.of()));
        Map<Integer, PrimitiveKey> swapped = Map.of(1, plan.disconnectedOccurrenceReplacements().get(2),
                2, plan.disconnectedOccurrenceReplacements().get(1));
        assertThrows(IllegalArgumentException.class, () -> copyPlan(plan, plan.after(), plan.disconnectionSource(), swapped));
        assertThrows(IllegalArgumentException.class, () -> new AlignmentEditPlan(plan.selectedWayKey(),
                plan.selectedRange(), plan.before(), plan.after(), plan.metricFrame(), plan.permissions(),
                plan.settingsHash(), plan.evidenceHash(), plan.parameterHash(), plan.routeIdentity(),
                plan.finalPreviewWays(), plan.validation()));

        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>(plan.after().primitives());
        PrimitiveKey spare = PrimitiveKey.planned(PrimitiveKey.Type.NODE, 999);
        values.put(spare, new DetachedNode(spare, ((DetachedNode) values.get(key(fixture.nodes().get(0)))).coordinate(),
                Map.of(), false, true));
        Map<PrimitiveKey, Set<PrimitiveKey>> watches = new LinkedHashMap<>(plan.after().incomingReferrerWatches());
        watches.put(spare, Set.of());
        NetworkSnapshot extraNode = new NetworkSnapshot("extra", SnapshotRole.PROPOSED_AFTER,
                plan.after().datasetIdentity(), plan.after().sourceGeneration(), plan.after().closure(), values, watches);
        assertThrows(IllegalArgumentException.class, () -> copyPlan(plan, extraNode, plan.disconnectionSource(),
                plan.disconnectedOccurrenceReplacements()), "Move Nodes cannot create an unchosen shape node");
        values = new LinkedHashMap<>(plan.after().primitives());
        PrimitiveKey originalShared = key(fixture.nodes().get(1));
        DetachedNode shared = (DetachedNode) values.get(originalShared);
        values.put(originalShared, new DetachedNode(originalShared,
                new GeographicPoint(shared.coordinate().latitudeDegrees() + 0.000001,
                        shared.coordinate().longitudeDegrees()), Map.of(), false, true));
        NetworkSnapshot movedOriginal = new NetworkSnapshot("moved-original", SnapshotRole.PROPOSED_AFTER,
                plan.after().datasetIdentity(), plan.after().sourceGeneration(), plan.after().closure(), values,
                plan.after().incomingReferrerWatches());
        assertThrows(IllegalArgumentException.class, () -> copyPlan(plan, movedOriginal, plan.disconnectionSource(),
                plan.disconnectedOccurrenceReplacements()));
    }

    @Test void entirelyFrozenAttemptCannotManufactureDisconnectedPlanWithoutProductionOwner() throws Exception {
        Fixture fixture = fixture(false);
        var computed = rawCompute(fixture, Map.of(1, key(fixture.nodes().get(1))), raster(50));
        assertTrue(computed.partitioned());
        assertTrue(computed.intervalBatch().runs().isEmpty());
        var refused = new FixedIntervalEditPlanComposer().compose(computed.intervalBatch(), Map.of());
        assertFalse(refused.applyAvailable());
        assertEquals(List.of(1), refused.unownedDisconnectionOccurrences());
    }

    @Test void noRouteAndCancellationCannotSupplyDisconnectionAuthority() throws Exception {
        Fixture fixture = separatedJunctionFixture();
        Result result = compute(fixture, AlignmentMode.PRECISE_SHAPE, Map.of(10, key(fixture.nodes().get(10))));
        var batch = result.computed().intervalBatch();
        for (var status : List.of(TraceHypothesisSet.Status.NO_ROUTE, TraceHypothesisSet.Status.CANCELLED)) {
            List<IntervalTraceBatch.IntervalRun> emptyRuns = batch.runs().stream().map(run ->
                    new IntervalTraceBatch.IntervalRun(run.interval(), run.request(),
                            new ModernTracePipeline.Result(new TraceHypothesisSet(run.request().engine(), List.of(),
                                    status, false, 0, 0, "synthetic-refusal"), List.of()), TraceWorkUsage.none())).toList();
            var empty = new IntervalTraceBatch(batch.fullRequest(), batch.evidence(), batch.network(),
                    batch.partition(), emptyRuns, batch.options(), batch.authoritySpecification());
            assertFalse(new FixedIntervalEditPlanComposer().compose(empty, Map.of()).applyAvailable(), status.toString());
        }
        List<IntervalTraceBatch.IntervalRun> blockedRuns = batch.runs().stream().map(run -> {
            var routes = run.routes().stream().map(route -> {
                var quality = route.quality();
                var blocked = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.Result(
                        quality.id(), org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.Disposition.HARD_BLOCKED,
                        quality.findings(), quality.totalLengthMeters(), 0, quality.worstUnsupportedSpanMeters(),
                        quality.meanImageCenterCost(), quality.bendPreservingRoughness());
                return new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(), route.pointIds(),
                        route.assignments(), route.sourceOwnership(), blocked, route.cleanupStatus(), route.geometryChanged());
            }).toList();
            return new IntervalTraceBatch.IntervalRun(run.interval(), run.request(),
                    new ModernTracePipeline.Result(run.result().inference(), routes), run.usage());
        }).toList();
        var unsafe = new IntervalTraceBatch(batch.fullRequest(), batch.evidence(), batch.network(),
                batch.partition(), blockedRuns, batch.options(), batch.authoritySpecification());
        assertFalse(new FixedIntervalEditPlanComposer().compose(unsafe, Map.of()).applyAvailable());
        assertAll(List.of(TraceHypothesisSet.Status.NO_ROUTE, TraceHypothesisSet.Status.CANCELLED,
                TraceHypothesisSet.Status.RESOURCE_LIMIT, TraceHypothesisSet.Status.COMPLETE).stream()
                .map(status -> (org.junit.jupiter.api.function.Executable) () -> {
                    var staleRuns = batch.runs().stream().map(run -> {
                        if (run.routes().isEmpty()) return run;
                        var raw = run.routes().get(0).rawHypothesis();
                        var foreign = new TraceHypothesis(raw.id() + ":foreign", raw.branchSignature() + ":foreign",
                                raw.points(), raw.support(), raw.objective(), raw.posteriorProbability(), raw.diagnostics());
                        var inference = new TraceHypothesisSet(run.request().engine(),
                                status == TraceHypothesisSet.Status.COMPLETE ? List.of(foreign) : List.of(),
                                status, false, 0, 0, "synthetic-stale-positive-route");
                        return new IntervalTraceBatch.IntervalRun(run.interval(), run.request(),
                                new ModernTracePipeline.Result(inference, run.routes()), run.usage());
                    }).toList();
                    var stale = new IntervalTraceBatch(batch.fullRequest(), batch.evidence(), batch.network(),
                            batch.partition(), staleRuns, batch.options(), batch.authoritySpecification());
                    assertFalse(new FixedIntervalEditPlanComposer().compose(stale, Map.of()).applyAvailable(),
                            "Stale positive route cannot own disconnection: " + status);
                }));
    }

    @ParameterizedTest
    @EnumSource(value = AlignmentMode.class, names = {"PRECISE_SHAPE", "MOVE_EXISTING_NODES"})
    void supportedAlreadyAlignedRouteMayDisconnectWithoutGeometricDisplacement(AlignmentMode mode) throws Exception {
        Fixture whole = fixture(false);
        Fixture fixture = new Fixture(whole.data(), whole.way(), whole.nodes(),
                new SelectionContext(whole.way(), 1, 2, whole.nodes().subList(1, 3),
                        Set.of(whole.nodes().get(1), whole.nodes().get(2))));
        var result = compute(fixture, mode,
                Map.of(1, key(fixture.nodes().get(1)), 2, key(fixture.nodes().get(2))), TrackerMode.CORRIDOR_AWARE, 0);
        assertTrue(result.computed().pipeline().routes().get(0).quality().directlySupportedLengthMeters() > 0);
        for (var entry : result.plan().disconnectedOccurrenceReplacements().entrySet()) {
            var original = (DetachedNode) result.plan().before().primitives().get(key(fixture.nodes().get(entry.getKey())));
            var replacement = (DetachedNode) result.plan().after().primitives().get(entry.getValue());
            assertEquals(original.coordinate(), replacement.coordinate());
        }
        var plan = result.plan();
        var after = plan.after();
        var ports = after.proposedPortReplacements();
        assertEquals(2, ports.size());
        assertEquals(plan.before().closure(), after.closure());
        assertTrue(plan.before().proposedPortReplacements().isEmpty());
        assertThrows(UnsupportedOperationException.class, ports::clear);
        ports.forEach((port, destination) -> {
            assertEquals(plan.selectedWayKey(), port.wayKey());
            assertEquals(plan.disconnectedOccurrenceReplacements().get(port.boundaryOccurrenceIndex()), destination);
            assertEquals(plan.before().primitives().get(port.outsideNeighborKey()),
                    after.primitives().get(port.outsideNeighborKey()));
            assertFalse(plan.before().closure().editRegion().contains(plan.metricFrame().toMetric(
                    ((DetachedNode) after.primitives().get(port.outsideNeighborKey())).coordinate())),
                    "The unchanged outside edge must exercise the exact port-identity proof");
        });
        assertThrows(IllegalArgumentException.class, () -> new NetworkSnapshot(after.snapshotId(), after.role(),
                after.datasetIdentity(), after.sourceGeneration(), after.closure(), after.primitives(),
                after.incomingReferrerWatches()));
        var portList = new ArrayList<>(ports.keySet());
        var swapped = Map.of(portList.get(0), ports.get(portList.get(1)),
                portList.get(1), ports.get(portList.get(0)));
        assertThrows(IllegalArgumentException.class, () -> copySnapshotWithPorts(after, after.primitives(), swapped));
        ExternalPort port = portList.get(0);
        DetachedWay selected = (DetachedWay) after.primitives().get(plan.selectedWayKey());
        int boundaryIndex = selected.nodeKeys().indexOf(ports.get(port));
        int outsideSegment = port.side() == ExternalPort.Side.BEFORE ? boundaryIndex - 1 : boundaryIndex;
        assertNotNull(SharedNodeDisconnectionPlanner.exactBoundaryIdentityChange(plan.before(), after.primitives(),
                plan.selectedWayKey(), outsideSegment, ports));
        assertNull(SharedNodeDisconnectionPlanner.exactBoundaryIdentityChange(plan.before(), after.primitives(),
                plan.selectedWayKey(), outsideSegment, swapped));
        assertNull(SharedNodeDisconnectionPlanner.exactBoundaryIdentityChange(plan.before(), after.primitives(),
                plan.selectedWayKey(), outsideSegment, Map.of()));
        ExternalPort otherWay = new ExternalPort(key(fixture.data().getWays().stream()
                .filter(way -> !way.equals(fixture.way())).findFirst().orElseThrow()), port.boundaryNodeKey(),
                port.outsideNeighborKey(), port.boundaryOccurrenceIndex(), port.side(), port.outsideNeighborCoordinate());
        var extra = new LinkedHashMap<>(ports); extra.put(otherWay, ports.get(port));
        assertThrows(IllegalArgumentException.class, () -> copySnapshotWithPorts(after, after.primitives(), extra));
        var moved = new LinkedHashMap<>(after.primitives());
        DetachedNode boundary = (DetachedNode) moved.get(ports.get(port));
        moved.put(boundary.key(), new DetachedNode(boundary.key(),
                new GeographicPoint(boundary.coordinate().latitudeDegrees() + 0.000001,
                        boundary.coordinate().longitudeDegrees()), boundary.tags(), false, true));
        assertThrows(IllegalArgumentException.class, () -> copySnapshotWithPorts(after, moved, ports));
        assertNull(SharedNodeDisconnectionPlanner.exactBoundaryIdentityChange(plan.before(), moved,
                plan.selectedWayKey(), outsideSegment, ports));
        var movedOutside = new LinkedHashMap<>(after.primitives());
        DetachedNode outside = (DetachedNode) movedOutside.get(port.outsideNeighborKey());
        movedOutside.put(outside.key(), new DetachedNode(outside.key(),
                new GeographicPoint(outside.coordinate().latitudeDegrees() + 0.000001,
                        outside.coordinate().longitudeDegrees()), outside.tags(), false, true));
        assertThrows(IllegalArgumentException.class, () -> copySnapshotWithPorts(after, movedOutside, ports));
        assertNull(SharedNodeDisconnectionPlanner.exactBoundaryIdentityChange(plan.before(), movedOutside,
                plan.selectedWayKey(), outsideSegment, ports));
        assertThrows(IllegalArgumentException.class, () -> new NetworkSnapshot(plan.before().snapshotId(),
                SnapshotRole.CAPTURED_BEFORE, after.datasetIdentity(), after.sourceGeneration(), after.closure(),
                plan.before().primitives(), plan.before().incomingReferrerWatches(), null, ports));
        assertTrue(plan.validation().reviewRequired());
        assertTrue(plan.validation().findingCodes().contains(ContextContactAssessment.REVIEW_CODE));
        assertFalse(ModernSingleWayEditPlanAdapter.assessFinalTopology(plan, result.computed().evidence(), 7.01)
                .findingCodes().stream().anyMatch(code -> code.startsWith("final-topology:")));
        assertTrue(ModernSingleWayEditPlanAdapter.assessFinalTopology(plan.before(), after.primitives(),
                plan.selectedWayKey(), plan.selectedRange(), result.computed().evidence(), 7.01)
                .findingCodes().contains("final-topology:VERTEX_TOUCH"));
    }

    private static NetworkSnapshot copySnapshotWithPorts(NetworkSnapshot source,
            Map<PrimitiveKey, DetachedPrimitive> values, Map<ExternalPort, PrimitiveKey> ports) {
        return new NetworkSnapshot(source.snapshotId(), source.role(), source.datasetIdentity(),
                source.sourceGeneration(), source.closure(), values, source.incomingReferrerWatches(),
                source.semanticWitness(), ports);
    }

    @Test void zeroSupportedReviewRouteCannotAuthorizeFullWayDisconnection() throws Exception {
        Fixture fixture = fixture(false);
        var result = compute(fixture, AlignmentMode.PRECISE_SHAPE,
                Map.of(1, key(fixture.nodes().get(1)), 2, key(fixture.nodes().get(2))));
        var computed = result.computed();
        var route = computed.pipeline().routes().get(0);
        var quality = route.quality();
        assertTrue(quality.directlySupportedLengthMeters() > 0);
        assertTrue(SharedNodeDisconnectionPlanner.retainedProductionOwner(computed.pipeline(), route));
        var differentFinal = new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(),
                route.pointIds(), route.assignments(), route.sourceOwnership(), quality,
                route.cleanupStatus(), !route.geometryChanged());
        assertFalse(SharedNodeDisconnectionPlanner.retainedProductionOwner(computed.pipeline(), differentFinal),
                "A different final route is not a retained production owner");
        var unsupported = new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.Result(
                quality.id(), org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.Disposition.REVIEW_REQUIRED,
                quality.findings(), quality.totalLengthMeters(), 0, quality.worstUnsupportedSpanMeters(),
                quality.meanImageCenterCost(), quality.bendPreservingRoughness());
        var unsupportedRoute = new ModernTracePipeline.Route(route.rawHypothesis(), route.hypothesis(),
                route.pointIds(), route.assignments(), route.sourceOwnership(), unsupported,
                route.cleanupStatus(), route.geometryChanged());
        var retained = new LiveBPreviewService.Computed(computed.captured(), computed.evidence(), computed.request(),
                new ModernTracePipeline.Result(computed.pipeline().inference(), List.of(unsupportedRoute)),
                computed.options(), computed.counters(), null, computed.settingsResolutionJson());
        var assessment = new ModernSingleWayEditPlanAdapter().assess(retained, 0);
        assertFalse(assessment.applyAvailable());
        assertTrue(assessment.plan().isPresent(), "The exact rejected preview remains inspectable");
        assertTrue(assessment.plan().orElseThrow().validation().findingCodes()
                .contains("disconnection-route-support-unavailable"));
        assertSame(unsupportedRoute, retained.pipeline().routes().get(0));
        for (var status : List.of(TraceHypothesisSet.Status.NO_ROUTE, TraceHypothesisSet.Status.CANCELLED,
                TraceHypothesisSet.Status.RESOURCE_LIMIT)) {
            var inference = new TraceHypothesisSet(computed.request().engine(), List.of(), status,
                    false, 0, 0, "synthetic-unretained-owner");
            var unretained = new LiveBPreviewService.Computed(computed.captured(), computed.evidence(),
                    computed.request(), new ModernTracePipeline.Result(inference, List.of(route)),
                    computed.options(), computed.counters(), null, computed.settingsResolutionJson());
            var refused = new ModernSingleWayEditPlanAdapter().assess(unretained, 0);
            assertFalse(refused.applyAvailable(), status.toString());
            assertTrue(refused.plan().orElseThrow().validation().findingCodes()
                    .contains("disconnection-route-support-unavailable"));
        }
    }

    @Test void genuineNoSignalNeverCreatesApplicableDisconnectedPlan() throws Exception {
        Fixture fixture = fixture(false);
        var visible = raster(50);
        int[] dark = visible.argb(); java.util.Arrays.fill(dark, 0xff000000);
        var raster = new LiveBPreviewService.VisibleRaster(visible.width(), visible.height(), dark,
                -50, -50, 50, 50, 1, 1, OptionalDouble.of(1), "synthetic-dark", "EPSG:3857");
        var computed = rawCompute(fixture, Map.of(1, key(fixture.nodes().get(1)),
                2, key(fixture.nodes().get(2))), raster);
        assertFalse(computed.partitioned());
        assertTrue(computed.pipeline().routes().isEmpty());
        assertFalse(new ModernSingleWayEditPlanAdapter().assess(computed, 0).applyAvailable());
    }

    private static LiveBPreviewService.Computed rawCompute(Fixture fixture, Map<Integer, PrimitiveKey> choices,
            LiveBPreviewService.VisibleRaster input) throws Exception {
        var service = new LiveBPreviewService();
        var config = config(AlignmentMode.PRECISE_SHAPE);
        var original = onEdt(() -> service.capture(fixture.data(), fixture.selection(), input, config, false,
                RecoveryPermissions.disabled(7.01)));
        var intent = new SharedNodeDisconnectionIntent(key(fixture.way()), original.specification().selectedRange(),
                choices, original.disconnectionSource().canonicalHash());
        var permissions = new RecoveryPermissions(false, 7.01, 7.01, JunctionPolicy.FIXED, false, intent);
        var captured = onEdt(() -> service.capture(fixture.data(), fixture.selection(), input, config, false, permissions));
        return service.compute(captured, CancellationProbe.NONE);
    }

    @Test void sourcePairingAndFreshRecaptureRejectChangedOrdinaryFactsAndReferrers() throws Exception {
        Fixture fixture = ordinaryNeighborFixture();
        Result result = compute(fixture, AlignmentMode.PRECISE_SHAPE, Map.of(2, key(fixture.nodes().get(2))));
        var request = result.computed().request();
        var source = result.original();
        var effective = result.plan().before();
        var intent = request.permissions().sharedNodeDisconnectionIntent();
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>(effective.primitives());
        PrimitiveKey ordinary = key(fixture.nodes().get(1));
        DetachedNode old = (DetachedNode) values.get(ordinary);
        values.put(ordinary, new DetachedNode(ordinary,
                new GeographicPoint(old.coordinate().latitudeDegrees() + 0.000001,
                        old.coordinate().longitudeDegrees()), old.tags(), false, false));
        NetworkSnapshot changed = new NetworkSnapshot(effective.snapshotId(), effective.role(), effective.datasetIdentity(),
                effective.sourceGeneration(), effective.closure(), values, effective.incomingReferrerWatches());
        assertThrows(IllegalArgumentException.class,
                () -> SharedNodeDisconnectionPlanner.requireSourceParity(source, changed, intent));
        Map<PrimitiveKey, Set<PrimitiveKey>> watches = new LinkedHashMap<>(effective.incomingReferrerWatches());
        watches.put(ordinary, Set.of(key(fixture.way()), PrimitiveKey.existing(PrimitiveKey.Type.WAY, 9999)));
        NetworkSnapshot changedReferrers = new NetworkSnapshot(effective.snapshotId(), effective.role(),
                effective.datasetIdentity(), effective.sourceGeneration(), effective.closure(),
                effective.primitives(), watches);
        assertThrows(IllegalArgumentException.class,
                () -> SharedNodeDisconnectionPlanner.requireSourceParity(source, changedReferrers, intent));
        assertThrows(IllegalArgumentException.class, () -> SharedNodeDisconnectionPlanner.replacements(changed, intent));
        PrimitiveKey chosen = key(fixture.nodes().get(2));
        var closure = effective.closure();
        var editable = new java.util.LinkedHashSet<>(closure.editableExistingKeys()); editable.add(chosen);
        var movable = new java.util.LinkedHashSet<>(closure.movableExistingNodeKeys()); movable.add(chosen);
        var protectedNodes = new java.util.LinkedHashSet<>(closure.protectedExistingNodeKeys()); protectedNodes.remove(chosen);
        var stronger = new ClosureDescriptor(closure.scope(), closure.queryVersion(), closure.primitiveKeys(),
                editable, movable, protectedNodes, closure.removableExistingNodeKeys(), closure.editableWayOccurrences(),
                closure.externalPorts(), closure.collisionEnvelope(), closure.editRegion(), closure.mayCreateNodes(),
                true, true, true);
        NetworkSnapshot expanded = new NetworkSnapshot(effective.snapshotId(), effective.role(), effective.datasetIdentity(),
                effective.sourceGeneration(), stronger, effective.primitives(), effective.incomingReferrerWatches());
        assertThrows(IllegalArgumentException.class,
                () -> SharedNodeDisconnectionPlanner.requireSourceParity(source, expanded, intent),
                "replacement authority never makes the original shared node movable");
        onEdt(() -> { fixture.nodes().get(1).setCoor(new LatLon(0.000001, fixture.nodes().get(1).lon())); return null; });
        assertThrows(RuntimeException.class, () -> onEdt(() -> NetworkSnapshotCapture.capture(fixture.data(),
                result.computed().captured().specification())));
    }

    @Test void mismatchedSourceAndSelectionAreRejectedBeforeInferenceEvenForAnEmptyEngineResult() throws Exception {
        Fixture fixture = ordinaryNeighborFixture();
        Result result = compute(fixture, AlignmentMode.PRECISE_SHAPE, Map.of(2, key(fixture.nodes().get(2))));
        var captured = result.computed().captured();
        var request = result.computed().request();
        Map<PrimitiveKey, DetachedPrimitive> changedValues = new LinkedHashMap<>(result.original().primitives());
        PrimitiveKey ordinary = key(fixture.nodes().get(1));
        DetachedNode old = (DetachedNode) changedValues.get(ordinary);
        changedValues.put(ordinary, new DetachedNode(ordinary,
                new GeographicPoint(old.coordinate().latitudeDegrees() + 0.000001,
                        old.coordinate().longitudeDegrees()), Map.of(), false, false));
        NetworkSnapshot changed = new NetworkSnapshot("different-initial-source", SnapshotRole.CAPTURED_BEFORE,
                result.original().datasetIdentity(), result.original().sourceGeneration(),
                result.original().closure(), changedValues, result.original().incomingReferrerWatches());
        var newIntent = new SharedNodeDisconnectionIntent(request.selectedWayKey(), request.selectedRange(),
                request.permissions().sharedNodeDisconnectionIntent().originalNodeKeys(), changed.canonicalHash());
        var newPermissions = new RecoveryPermissions(false, 7.01, 7.01, JunctionPolicy.FIXED, false, newIntent);
        TraceRequest mismatched = requestCopy(request, request.selectedWayKey(), newPermissions, changed);
        var managed = new LiveBPreviewService.ManagedCaptureSeed(captured.specification(), captured.network(),
                captured.sourceGeographic(), captured.sourceMetric(), result.plan().metricFrame(), captured.palette(),
                captured.searchRadiusMeters(), captured.sampleStepMeters(), captured.settingsHash(),
                captured.parameterHash(), captured.cleanup(), captured.geometryMode(), captured.engine(),
                "synthetic-managed", captured.projectionCode(), captured.junctionDecision(),
                captured.intervalPartition(), captured.intensityMode());
        java.util.concurrent.atomic.AtomicInteger engineCalls = new java.util.concurrent.atomic.AtomicInteger();
        ModernTracePipeline pipeline = new ModernTracePipeline((r, e, n, c) -> {
            engineCalls.incrementAndGet();
            return new TraceHypothesisSet(r.engine(), List.of(), TraceHypothesisSet.Status.NO_ROUTE,
                    false, 0, 0, "synthetic-empty");
        });
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> captured.withInitialSource(changed)),
                () -> assertThrows(IllegalArgumentException.class, () -> managed.withInitialSource(changed)),
                () -> assertThrows(IllegalArgumentException.class, () -> requestCopy(request,
                        PrimitiveKey.existing(PrimitiveKey.Type.WAY, 999), request.permissions(), request.disconnectionSource())),
                () -> assertThrows(IllegalArgumentException.class, () -> pipeline.runWithUsage(mismatched,
                        result.computed().evidence(), captured.network(), result.computed().options(),
                        CancellationProbe.NONE, Set.of())),
                () -> assertEquals(0, engineCalls.get(), "source refusal must precede invocation of even an empty engine"));
    }

    private static TraceRequest requestCopy(TraceRequest request, PrimitiveKey way,
            RecoveryPermissions permissions, NetworkSnapshot source) {
        return new TraceRequest(way, request.selectedRange(), request.engine(), request.geometryMode(),
                permissions, request.budgets(), request.evidenceSnapshotId(), request.evidenceContentHash(),
                request.networkSnapshotId(), request.networkContentHash(), request.settingsHash(),
                request.parameterHash(), request.samplerId(), request.configuredSampleStepMeters(),
                request.profileChainage(), request.evidenceResolution(), request.corridorInput(), source);
    }

    private static AlignmentEditPlan copyPlan(AlignmentEditPlan plan, NetworkSnapshot after,
            NetworkSnapshot source, Map<Integer, PrimitiveKey> replacements) {
        return new AlignmentEditPlan(plan.selectedWayKey(), plan.selectedRange(), plan.before(), after,
                plan.metricFrame(), plan.permissions(), plan.settingsHash(), plan.evidenceHash(),
                plan.parameterHash(), plan.routeIdentity(), plan.finalPreviewWays(), plan.validation(),
                source, replacements, plan.disconnectionGeometryMode());
    }

    private record Result(NetworkSnapshot original, NetworkSnapshot conservative,
            LiveBPreviewService.Computed computed, AlignmentEditPlan plan, String intervalSummary) { }

    private static Result compute(Fixture fixture, AlignmentMode mode,
            Map<Integer, PrimitiveKey> choices) throws Exception {
        return compute(fixture, mode, choices, TrackerMode.CORRIDOR_AWARE);
    }

    private static Result compute(Fixture fixture, AlignmentMode mode,
            Map<Integer, PrimitiveKey> choices, TrackerMode engine) throws Exception {
        return compute(fixture, mode, choices, engine, 4);
    }
    private static Result compute(Fixture fixture, AlignmentMode mode,
            Map<Integer, PrimitiveKey> choices, TrackerMode engine, double shift) throws Exception {
        LiveBPreviewService service = new LiveBPreviewService();
        AlignmentConfig config = new AlignmentConfig(config(mode).heatmap().withTrackerMode(engine),
                GeometryCleanupConfig.disabled());
        int halfExtent = fixture.nodes().size() > 10 ? 100 : 50;
        var original = onEdt(() -> service.capture(fixture.data(), fixture.selection(), raster(halfExtent, shift), config, false,
                RecoveryPermissions.disabled(7.01)));
        var intent = new SharedNodeDisconnectionIntent(key(fixture.way()),
                new OccurrenceRange(fixture.selection().startIndex(), fixture.selection().endIndex()),
                choices, original.disconnectionSource().canonicalHash());
        var permissions = new RecoveryPermissions(false, 7.01, 7.01, JunctionPolicy.FIXED, false, intent);
        var captured = onEdt(() -> service.capture(fixture.data(), fixture.selection(), raster(halfExtent, shift), config, false,
                permissions));
        var computed = service.compute(captured, CancellationProbe.NONE);
        AlignmentEditPlan plan;
        String intervalSummary = "full-selection";
        if (computed.partitioned()) {
            var assessment = new FixedIntervalEditPlanComposer().compose(computed.intervalBatch(), Map.of());
            intervalSummary = assessment.intervals().toString() + "; quality=" + computed.intervalBatch().runs().stream()
                    .map(run -> run.routes().stream().map(route -> route.quality().findings()).toList()).toList();
            assertTrue(assessment.plan().isPresent(), assessment.intervals().toString());
            plan = assessment.plan().orElseThrow();
        } else {
            assertFalse(computed.pipeline().routes().isEmpty());
            var assessment = new ModernSingleWayEditPlanAdapter().assess(computed, 0);
            assertTrue(assessment.plan().isPresent(), assessment.detail());
            plan = assessment.plan().orElseThrow();
        }
        assertTrue(plan.validation().applicable(), plan.validation() + "; routeEvidence="
                + (computed.partitioned() ? intervalSummary : computed.pipeline().routes().stream().map(route ->
                        "status=" + computed.pipeline().inference().status()
                                + ", direct=" + route.quality().directlySupportedLengthMeters()
                                + ", rawRetained=" + computed.pipeline().inference().hypotheses()
                                        .contains(route.rawHypothesis())).toList()));
        return new Result(original.disconnectionSource(), original.network(), computed, plan, intervalSummary);
    }

    private record Fixture(DataSet data, Way way, List<Node> nodes, SelectionContext selection) { }
    private static Fixture separatedJunctionFixture() {
        DataSet data = new DataSet(); List<Node> nodes = new ArrayList<>();
        for (int i = 0; i <= 40; i++) {
            Node node = liveNode(i + 1, -80 + i * 4, 0);
            nodes.add(node); data.addPrimitive(node);
        }
        Way selected = liveWay(100, nodes, Map.of("highway", "path")); data.addPrimitive(selected);
        for (int occurrence : List.of(10, 30)) {
            Node other = liveNode(200 + occurrence, -80 + occurrence * 4, -8); data.addPrimitive(other);
            Node north = liveNode(400 + occurrence, -80 + occurrence * 4, 8); data.addPrimitive(north);
            data.addPrimitive(liveWay(300 + occurrence, List.of(other, nodes.get(occurrence), north), Map.of("highway", "path")));
        }
        return new Fixture(data, selected, nodes,
                new SelectionContext(selected, 0, 40, nodes, Set.of(nodes.get(0), nodes.get(40))));
    }
    private static Fixture ordinaryNeighborFixture() {
        DataSet data = new DataSet();
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Node node = liveNode(i + 1, -8 + i * 4, 0);
            nodes.add(node); data.addPrimitive(node);
        }
        Way selected = liveWay(10, nodes, Map.of("highway", "path")); data.addPrimitive(selected);
        Node other = liveNode(30, 0, -8); data.addPrimitive(other);
        data.addPrimitive(liveWay(20, List.of(nodes.get(2), other), Map.of("highway", "path")));
        return new Fixture(data, selected, nodes, new SelectionContext(selected, 0, 4,
                nodes, Set.of(nodes.get(0), nodes.get(4))));
    }
    private static Fixture fixture(boolean partial) {
        DataSet data = new DataSet();
        // Sparse Move Nodes geometry needs a real supported body between the fixed approaches.
        double[] positions = partial ? new double[] {-24, -20, -12, 12, 20, 24} : new double[] {-20, -12, 12, 20};
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < positions.length; i++) {
            Node node = liveNode(i + 1, positions[i], 0);
            nodes.add(node); data.addPrimitive(node);
        }
        Way selected = liveWay(10, nodes, Map.of("highway", "path", "name", "selected"));
        data.addPrimitive(selected);
        int firstShared = partial ? 2 : 1;
        for (int i = firstShared; i <= firstShared + 1; i++) {
            Node other = liveNode(30 + i, positions[i], -8);
            data.addPrimitive(other);
            data.addPrimitive(liveWay(20 + i, List.of(nodes.get(i), other), Map.of("highway", "path")));
        }
        int first = partial ? 1 : 0, last = partial ? 4 : 3;
        if (partial) {
            for (int occurrence : List.of(0, 5)) {
                Node other = liveNode(70 + occurrence, positions[occurrence], -8); data.addPrimitive(other);
                data.addPrimitive(liveWay(80 + occurrence, List.of(nodes.get(occurrence), other), Map.of("highway", "path")));
            }
        }
        return new Fixture(data, selected, nodes, new SelectionContext(selected, first, last,
                nodes.subList(first, last + 1), Set.of(nodes.get(first), nodes.get(last))));
    }
    private static AlignmentConfig config(AlignmentMode mode) {
        var heatmap = new ManagedHeatmapConfig("", "", "", "", "all", "hot", "", ".*", mode,
                TrackerMode.CORRIDOR_AWARE, false, false, false, false, false, false,
                false, false, false, false, 7, 4, 3.0, InferenceMode.RAW_HIGH_RESOLUTION,
                15, 15, 7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, 0L);
        return new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled());
    }
    private static LiveBPreviewService.VisibleRaster raster(int halfExtent) {
        return raster(halfExtent, 4);
    }
    private static LiveBPreviewService.VisibleRaster raster(int halfExtent, double shift) {
        int width = 12 * halfExtent, height = width;
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            double north = (width / 2.0 - y) / 6.0;
            for (int x = 0; x < width; x++) {
                double east = (x - width / 2.0) / 6.0;
                // The mixed-island case returns to the unchanged fixed boundary at x=8 m.
                double center = halfExtent > 50 ? shift * Math.min(1,
                        Math.min(Math.max(0, (east + 80) / 16), Math.max(0, (8 - east) / 16))) : shift;
                double distance = north - center;
                int gray = (int) Math.round(255 * (0.02 + 0.80 * Math.exp(-0.5 * distance * distance / 1.44)));
                argb[y * width + x] = 0xff000000 | gray << 16 | gray << 8 | gray;
            }
        }
        return new LiveBPreviewService.VisibleRaster(width, height, argb, -halfExtent, -halfExtent, halfExtent, halfExtent,
                1, 1, OptionalDouble.of(1), "synthetic-disconnection", "EPSG:3857");
    }
    private static Node liveNode(long id, double x, double y) {
        Node node = new Node(new LatLon(Math.toDegrees(y / 6378137.0), Math.toDegrees(x / 6378137.0)));
        node.setOsmId(id, 1); node.setModified(false); return node;
    }
    private static Way liveWay(long id, List<Node> nodes, Map<String, String> tags) {
        Way way = new Way(); way.setNodes(nodes); way.setKeys(tags); way.setOsmId(id, 1);
        way.setModified(false); return way;
    }
    private static PrimitiveKey key(org.openstreetmap.josm.data.osm.OsmPrimitive primitive) {
        return PrimitiveKey.existing(primitive instanceof Way ? PrimitiveKey.Type.WAY : PrimitiveKey.Type.NODE,
                primitive.getUniqueId());
    }
    private static <T> T onEdt(java.util.concurrent.Callable<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { value.set(action.call()); } catch (Throwable e) { error.set(e); } });
        if (error.get() instanceof Exception exception) throw exception;
        if (error.get() != null) throw new AssertionError(error.get());
        return value.get();
    }
}
