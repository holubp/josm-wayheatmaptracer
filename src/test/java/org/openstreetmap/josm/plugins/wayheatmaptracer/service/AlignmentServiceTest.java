package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import org.openstreetmap.josm.data.osm.DataSet;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentDiagnostics;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateGeometryCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorQuality;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorCoverage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetectorAttemptStatus;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class AlignmentServiceTest {
    @BeforeAll
    static void setPreferences() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void roughSketchSelectionIsRecognizedButKeepsConfiguredModeForV02CompatibleSliding() {
        SelectionContext sketch = selection(4);
        ManagedHeatmapConfig config = config(AlignmentMode.MOVE_EXISTING_NODES);

        assertTrue(AlignmentService.isSketchLikeSelection(sketch));
        assertEquals(AlignmentMode.MOVE_EXISTING_NODES, AlignmentService.effectiveAlignmentMode(sketch, config));
    }

    @Test
    void snapshotsCleanupEvidenceWithoutDuplicatingProfileArrays() {
        String json = new AlignmentService().cleanupEvidenceSummaryJson(
            new CenterlineCandidate("hot/ridge-1", 1.0, List.of(), List.of()));

        assertTrue(json.contains("\"status\":\"LEGACY_NOT_AVAILABLE\""));
        assertTrue(json.contains("\"samplingProfiles\":0"));
        assertTrue(json.contains("\"sharedEstimatedBytes\":0"));
        assertFalse(json.contains("nativeIntensity"));
    }

    @Test
    void newCleanupOwnsReductionWithoutRunningDowngradeCompatibilitySimplificationFirst() {
        SelectionContext selection = selection(5);
        DataSet dataSet = new DataSet();
        selection.segmentNodes().forEach(dataSet::addPrimitive);
        dataSet.addPrimitive(selection.way());
        List<EastNorth> source = selection.segmentNodes().stream()
            .map(node -> node.getEastNorth(ProjectionRegistry.getProjection()))
            .toList();
        EastNorth start = source.get(0);
        EastNorth end = source.get(source.size() - 1);
        List<EastNorth> ridge = new java.util.ArrayList<>();
        for (int index = 0; index < 7; index++) {
            double fraction = index / 6.0;
            ridge.add(new EastNorth(
                start.east() + fraction * (end.east() - start.east()),
                start.north() + fraction * (end.north() - start.north())
                    + (index == 0 || index == 6 ? 0.0 : (index % 2 == 0 ? 0.2 : -0.2))));
        }
        CenterlineCandidate candidate = new CenterlineCandidate("hot/ridge-1", 1.0, List.of(), List.of())
            .withEastNorthPoints(ridge);
        ManagedHeatmapConfig compatibility = corridorPreciseConfigWithLegacySimplification();
        AlignmentService service = new AlignmentService();

        CenterlineCandidate legacy = service.attachFinalPreviewGeometry(
            List.of(candidate), selection, source, compatibility, GeometryCleanupConfig.disabled(), null).get(0);
        CenterlineCandidate modern = service.attachFinalPreviewGeometry(
            List.of(candidate), selection, source, compatibility,
            GeometryCleanupPreset.BALANCED.apply(GeometryCleanupMode.REDUCE_POINTS_ONLY), null).get(0);

        assertTrue(legacy.finalPreviewPoints().size() < ridge.size());
        assertEquals(ridge, modern.finalPreviewPoints());
    }

    @Test
    void groupsRawAndCleanedSiblingsAtTheirBestRankWithoutAnImplicitCleanupBonus() {
        CandidateGeometryCleanup rawReport = cleanupReport(
            "hot/ridge-raw", CandidateGeometryCleanup.Outcome.CLEANED_ALTERNATIVE_AVAILABLE);
        CandidateGeometryCleanup cleanedReport = cleanupReport(
            "hot/ridge-raw", CandidateGeometryCleanup.Outcome.CLEANED);
        CenterlineCandidate raw = new CenterlineCandidate(
            "hot/ridge-raw", 1.0, List.of(), List.of()).withGeometryCleanup(rawReport);
        CenterlineCandidate cleaned = new CenterlineCandidate(
            "hot/ridge-raw#cleaned", 100.0, List.of(), List.of()).withGeometryCleanup(cleanedReport);
        CenterlineCandidate unrelated = new CenterlineCandidate(
            "hot/ridge-other", 50.0, List.of(), List.of());

        List<CenterlineCandidate> ranked = new AlignmentService().rankCandidatesForTesting(
            List.of(unrelated, cleaned, raw), config(AlignmentMode.PRECISE_SHAPE));

        assertEquals(List.of(raw.id(), cleaned.id(), unrelated.id()),
            ranked.stream().map(CenterlineCandidate::id).toList());
    }

    @Test
    void detectsCandidateCrossingConnectedWayBeforeSharedJunction() {
        DataSet dataSet = new DataSet();
        Node start = nodeAt(0, 0);
        Node junction = nodeAt(10, 0);
        Node branchEnd = nodeAt(8, 10);
        dataSet.addPrimitive(start);
        dataSet.addPrimitive(junction);
        dataSet.addPrimitive(branchEnd);
        Way selectedWay = new Way();
        selectedWay.setNodes(List.of(start, junction));
        dataSet.addPrimitive(selectedWay);
        Way connectedWay = new Way();
        connectedWay.setNodes(List.of(junction, branchEnd));
        dataSet.addPrimitive(connectedWay);
        SelectionContext selected = new SelectionContext(selectedWay, 0, 1,
            List.of(start, junction), Set.of(start, junction));
        CenterlineCandidate crossing = new CenterlineCandidate("strand", 1.0, List.of(), List.of())
            .withEastNorthPoints(List.of(new EastNorth(0, 5), new EastNorth(12, 5), new EastNorth(10, 0)));
        CenterlineCandidate samplingScaleJoin = new CenterlineCandidate("strand", 1.0, List.of(), List.of())
            .withEastNorthPoints(List.of(new EastNorth(0, 1.5), new EastNorth(12, 1.5), new EastNorth(10, 0)));
        CenterlineCandidate correct = new CenterlineCandidate("strand", 1.0, List.of(), List.of())
            .withEastNorthPoints(List.of(new EastNorth(0, 0), new EastNorth(10, 0)));
        CenterlineCandidate repairedPreview = crossing.withFinalPreviewPoints(
            List.of(new EastNorth(0, 0), new EastNorth(10, 0)));
        CenterlineCandidate brokenPreview = correct.withFinalPreviewPoints(
            List.of(new EastNorth(0, 5), new EastNorth(12, 5), new EastNorth(10, 0)));

        AlignmentService service = new AlignmentService();
        assertTrue(service.crossesConnectedWayBeforeJunction(crossing, selected));
        assertFalse(service.crossesConnectedWayBeforeJunction(samplingScaleJoin, selected));
        assertFalse(service.crossesConnectedWayBeforeJunction(correct, selected));
        assertFalse(service.crossesConnectedWayBeforeJunction(repairedPreview, selected));
        assertTrue(service.crossesConnectedWayBeforeJunction(brokenPreview, selected));
    }

    @Test
    void evaluatesIncidentWayAtMovableJunctionProposedByFinalPreview() {
        DataSet dataSet = new DataSet();
        Node start = nodeAt(0, 0);
        Node junction = nodeAt(10, 0);
        Node branchEnd = nodeAt(8, 10);
        for (Node node : List.of(start, junction, branchEnd)) {
            dataSet.addPrimitive(node);
        }
        Way selectedWay = new Way();
        selectedWay.setNodes(List.of(start, junction));
        dataSet.addPrimitive(selectedWay);
        Way connectedWay = new Way();
        connectedWay.setNodes(List.of(junction, branchEnd));
        dataSet.addPrimitive(connectedWay);
        SelectionContext selected = new SelectionContext(selectedWay, 0, 1,
            List.of(start, junction), Set.of());
        CenterlineCandidate movedJunction = new CenterlineCandidate("strand", 1.0, List.of(), List.of())
            .withEastNorthPoints(List.of(new EastNorth(0, 0), new EastNorth(12, 5)))
            .withFinalPreviewGeometry(
                List.of(new EastNorth(0, 0), new EastNorth(12, 5)),
                Map.of(junction.getUniqueId(), new EastNorth(12, 5)));

        assertFalse(new AlignmentService().crossesConnectedWayBeforeJunction(movedJunction, selected),
            "The connected segment must terminate at the candidate's proposed shared-node position");
    }

    @Test
    void stillRejectsRealIncidentWayCrossingInProposedJunctionState() {
        DataSet dataSet = new DataSet();
        Node start = nodeAt(0, 0);
        Node junction = nodeAt(10, 0);
        Node branchEnd = nodeAt(0, 10);
        for (Node node : List.of(start, junction, branchEnd)) {
            dataSet.addPrimitive(node);
        }
        Way selectedWay = new Way();
        selectedWay.setNodes(List.of(start, junction));
        dataSet.addPrimitive(selectedWay);
        Way connectedWay = new Way();
        connectedWay.setNodes(List.of(junction, branchEnd));
        dataSet.addPrimitive(connectedWay);
        SelectionContext selected = new SelectionContext(selectedWay, 0, 1,
            List.of(start, junction), Set.of());
        CenterlineCandidate crossing = new CenterlineCandidate("strand", 1.0, List.of(), List.of())
            .withEastNorthPoints(List.of(new EastNorth(0, 0), new EastNorth(6, 8), new EastNorth(12, 5)))
            .withFinalPreviewGeometry(
                List.of(new EastNorth(0, 0), new EastNorth(6, 8), new EastNorth(12, 5)),
                Map.of(junction.getUniqueId(), new EastNorth(12, 5)));

        assertTrue(new AlignmentService().crossesConnectedWayBeforeJunction(crossing, selected),
            "A crossing away from the consistently moved shared endpoint must remain unsafe");
    }

    @Test
    void rejectsIncidentWayCrossingAtCandidateVertex() {
        DataSet dataSet = new DataSet();
        Node start = nodeAt(0, 0);
        Node junction = nodeAt(10, 0);
        Node branchEnd = nodeAt(10, 10);
        for (Node node : List.of(start, junction, branchEnd)) {
            dataSet.addPrimitive(node);
        }
        Way selectedWay = new Way();
        selectedWay.setNodes(List.of(start, junction));
        dataSet.addPrimitive(selectedWay);
        Way connectedWay = new Way();
        connectedWay.setNodes(List.of(junction, branchEnd));
        dataSet.addPrimitive(connectedWay);
        SelectionContext selected = new SelectionContext(selectedWay, 0, 1,
            List.of(start, junction), Set.of(start, junction));
        CenterlineCandidate crossing = new CenterlineCandidate("strand", 1.0, List.of(), List.of())
            .withEastNorthPoints(List.of(
                new EastNorth(0, 5), new EastNorth(10, 5), new EastNorth(12, 5), new EastNorth(10, 0)));

        assertTrue(new AlignmentService().crossesConnectedWayBeforeJunction(crossing, selected),
            "A crossing must not disappear merely because it coincides with a candidate vertex");
    }

    @Test
    void rejectsCollinearOverlapWithIncidentWayBeforeJunction() {
        DataSet dataSet = new DataSet();
        Node start = nodeAt(0, 0);
        Node junction = nodeAt(10, 0);
        Node branchEnd = nodeAt(10, 10);
        for (Node node : List.of(start, junction, branchEnd)) {
            dataSet.addPrimitive(node);
        }
        Way selectedWay = new Way();
        selectedWay.setNodes(List.of(start, junction));
        dataSet.addPrimitive(selectedWay);
        Way connectedWay = new Way();
        connectedWay.setNodes(List.of(junction, branchEnd));
        dataSet.addPrimitive(connectedWay);
        SelectionContext selected = new SelectionContext(selectedWay, 0, 1,
            List.of(start, junction), Set.of(start, junction));
        CenterlineCandidate overlapping = new CenterlineCandidate("strand", 1.0, List.of(), List.of())
            .withEastNorthPoints(List.of(new EastNorth(0, 5), new EastNorth(10, 5), new EastNorth(10, 0)));

        assertTrue(new AlignmentService().crossesConnectedWayBeforeJunction(overlapping, selected),
            "Following an incident segment before the junction is an unsafe overlap");
    }

    @Test
    void ignoresCrossingsWithRemoteSegmentsOfAConnectedWay() {
        DataSet dataSet = new DataSet();
        Node start = nodeAt(0, 0);
        Node junction = nodeAt(10, 0);
        Node branchBend = nodeAt(8, 10);
        Node branchEnd = nodeAt(20, 10);
        for (Node node : List.of(start, junction, branchBend, branchEnd)) {
            dataSet.addPrimitive(node);
        }
        Way selectedWay = new Way();
        selectedWay.setNodes(List.of(start, junction));
        dataSet.addPrimitive(selectedWay);
        Way connectedWay = new Way();
        connectedWay.setNodes(List.of(junction, branchBend, branchEnd));
        dataSet.addPrimitive(connectedWay);
        SelectionContext selected = new SelectionContext(selectedWay, 0, 1,
            List.of(start, junction), Set.of(start, junction));
        CenterlineCandidate remoteCrossing = new CenterlineCandidate("strand", 1.0, List.of(), List.of())
            .withEastNorthPoints(List.of(new EastNorth(12, 12), new EastNorth(12, 5), new EastNorth(10, 0)));

        assertFalse(new AlignmentService().crossesConnectedWayBeforeJunction(remoteCrossing, selected));
    }

    @Test
    void connectedWayCrossingToleranceUsesGroundMetersAtNonEquatorialLatitude() {
        EastNorth center = ProjectionRegistry.getProjection().latlon2eastNorth(new LatLon(49.44, 14.5));
        DataSet dataSet = new DataSet();
        Node start = nodeAt(center.east() - 20.0, center.north());
        Node junction = nodeAt(center.east(), center.north());
        Node branchEnd = nodeAt(center.east(), center.north() + 20.0);
        for (Node node : List.of(start, junction, branchEnd)) {
            dataSet.addPrimitive(node);
        }
        Way selectedWay = new Way();
        selectedWay.setNodes(List.of(start, junction));
        dataSet.addPrimitive(selectedWay);
        Way connectedWay = new Way();
        connectedWay.setNodes(List.of(junction, branchEnd));
        dataSet.addPrimitive(connectedWay);
        SelectionContext selected = new SelectionContext(selectedWay, 0, 1,
            List.of(start, junction), Set.of(start, junction));
        CenterlineCandidate insidePhysicalTolerance = crossingAtNorthing(center, 3.0);
        CenterlineCandidate outsidePhysicalTolerance = crossingAtNorthing(center, 5.0);

        AlignmentService service = new AlignmentService();
        assertFalse(service.crossesConnectedWayBeforeJunction(insidePhysicalTolerance, selected));
        assertTrue(service.crossesConnectedWayBeforeJunction(outsidePhysicalTolerance, selected));
    }

    @Test
    void applyTopologyRecheckDetectsAConnectedWayAddedDuringModelessPreview() {
        DataSet dataSet = new DataSet();
        Node start = nodeAt(0, 0);
        Node junction = nodeAt(10, 0);
        Node connectedEnd = nodeAt(8, 10);
        for (Node node : List.of(start, junction, connectedEnd)) {
            dataSet.addPrimitive(node);
        }
        Way selectedWay = new Way();
        selectedWay.setNodes(List.of(start, junction));
        dataSet.addPrimitive(selectedWay);
        SelectionContext selected = new SelectionContext(selectedWay, 0, 1,
            List.of(start, junction), Set.of(start, junction));
        CenterlineCandidate candidate = new CenterlineCandidate("strand", 1.0, List.of(), List.of())
            .withEastNorthPoints(List.of(new EastNorth(0, 5), new EastNorth(12, 5), new EastNorth(10, 0)))
            .withFinalPreviewPoints(List.of(new EastNorth(0, 5), new EastNorth(12, 5), new EastNorth(10, 0)))
            .withJunctionSafetyEvaluation(List.of(), 2.5);
        AlignmentService service = new AlignmentService();
        service.requireCurrentTopologySafe(candidate, selected);

        Way connectedWay = new Way();
        connectedWay.setNodes(List.of(junction, connectedEnd));
        dataSet.addPrimitive(connectedWay);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> service.requireCurrentTopologySafe(candidate, selected));
    }

    @Test
    void applyTopologyRecheckRejectsFinalPreviewSelfIntersection() {
        SelectionContext selected = selection(2);
        CenterlineCandidate candidate = new CenterlineCandidate("strand", 1.0, List.of(), List.of())
            .withFinalPreviewPoints(List.of(
                new EastNorth(0, 0), new EastNorth(10, 10),
                new EastNorth(0, 10), new EastNorth(10, 0)));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> new AlignmentService().requireCurrentTopologySafe(candidate, selected));
    }

    @Test
    void applyTopologyRecheckRejectsNonAdjacentVertexSelfTouch() {
        SelectionContext selected = selection(2);
        CenterlineCandidate candidate = new CenterlineCandidate("strand", 1.0, List.of(), List.of())
            .withFinalPreviewPoints(List.of(
                new EastNorth(0, 0), new EastNorth(10, 10), new EastNorth(20, 0),
                new EastNorth(10, 10), new EastNorth(20, 20)));

        assertThrows(IllegalStateException.class,
            () -> new AlignmentService().requireCurrentTopologySafe(candidate, selected));
    }

    @Test
    void preciseTopologyCleanupRemovesShortUnsupportedEndpointHook() {
        Node start = nodeAt(0.0, 0.0);
        Node end = nodeAt(20.0, 10.0);
        Way way = new Way();
        way.setNodes(List.of(start, end));
        SelectionContext selected = new SelectionContext(way, 0, 1, List.of(start, end), Set.of(start, end));
        List<EastNorth> source = List.of(new EastNorth(0.0, 0.0), new EastNorth(20.0, 10.0));
        EastNorth hook = new EastNorth(2.0, 0.0);
        List<EastNorth> preview = List.of(
            source.get(0),
            hook,
            new EastNorth(3.035276, 3.863703),
            new EastNorth(10.0, 7.0),
            source.get(1)
        );

        List<EastNorth> cleaned = new AlignmentService().cleanPreviewTopology(
            selected, source, preview, AlignmentMode.PRECISE_SHAPE);

        assertFalse(cleaned.contains(hook), "The short 75-degree endpoint hook should be removed");
        assertEquals(source.get(0), cleaned.get(0));
        assertEquals(source.get(1), cleaned.get(cleaned.size() - 1));
    }

    @Test
    void preciseTopologyCleanupPreservesSupportedTurnBeyondTerminalWindow() {
        Node start = nodeAt(0.0, 0.0);
        Node end = nodeAt(5.0, 15.0);
        Way way = new Way();
        way.setNodes(List.of(start, end));
        SelectionContext selected = new SelectionContext(way, 0, 1, List.of(start, end), Set.of(start, end));
        List<EastNorth> preview = List.of(
            new EastNorth(0.0, 0.0),
            new EastNorth(5.0, 0.0),
            new EastNorth(5.0, 5.0),
            new EastNorth(5.0, 15.0)
        );

        List<EastNorth> cleaned = new AlignmentService().cleanPreviewTopology(
            selected, List.of(preview.get(0), preview.get(3)), preview, AlignmentMode.PRECISE_SHAPE);

        assertEquals(preview, cleaned);
    }

    @Test
    void corridorQualityPenalizesRipplesWithoutBlockingMissingEndpointEvidenceAlone() {
        CorridorQuality smooth = new CorridorQuality(0.1, 0.2, 0.08, 0.15, 0.2, 0.3,
            4.0, 8.0, 3.0, 0, 0, 0.0, 8.0, 0.85, true);
        CorridorQuality rough = new CorridorQuality(0.6, 1.1, 0.7, 1.2, 1.8, 2.4,
            20.0, 45.0, 30.0, 1, 2, 12.0, 42.0, 0.20, false);
        AlignmentService service = new AlignmentService();

        assertTrue(service.corridorQualityAdjustment(smooth) > service.corridorQualityAdjustment(rough));
        assertFalse(service.corridorQualityWarnings(rough).contains("unsupported endpoint approach"));
        assertTrue(service.corridorQualityWarnings(rough).stream()
            .anyMatch(value -> value.contains("folds backward")));
        assertTrue(service.corridorQualityWarnings(smooth).isEmpty());
    }

    @Test
    void repairedFinalPreviewDoesNotRetainRawTerminalTurnWarning() {
        CorridorQuality rawEndpointHook = new CorridorQuality(0.4, 0.8, 0.2, 0.3, 0.4, 0.5,
            10.0, 75.0, 30.0, 0, 0, 0.0, 75.0, 0.6, true);
        List<EastNorth> repaired = List.of(
            new EastNorth(0.0, 0.0), new EastNorth(5.0, 0.0),
            new EastNorth(10.0, 0.0), new EastNorth(15.0, 0.0));

        List<String> warnings = new AlignmentService().corridorQualityWarnings(rawEndpointHook, repaired);

        assertFalse(warnings.stream().anyMatch(value -> value.contains("terminal turn")));
    }

    @Test
    void corridorRankingPrefersCompleteLowResidualCandidateBeforeDetectorPrior() {
        CorridorCoverage complete = completeCoverage(100, 99);
        CenterlineCandidate fragmentedQuality = candidate("all-colors-combined/strand-2",
            corridorEvidence("all-colors-combined", 27, 76.93,
                quality(25.50, 0.04), complete));
        CenterlineCandidate continuousQuality = candidate("hot-corridor/strand-8",
            corridorEvidence("hot-corridor", 99, 0.12,
                quality(0.44, 0.58), complete));

        List<CenterlineCandidate> ranked = new AlignmentService().rankCandidatesForTesting(
            List.of(fragmentedQuality, continuousQuality), corridorConfig());

        assertEquals(continuousQuality.id(), ranked.get(0).id());
    }

    @Test
    void corridorRankingPrefersLowCostNativeHotTrack() {
        CorridorCoverage complete = completeCoverage(100, 80);
        CenterlineCandidate bad = candidate("hot-corridor/strand-4",
            corridorEvidence("hot-corridor", 53, 61.35, quality(23.68, 0.04), complete));
        CenterlineCandidate good = candidate("hot/strand-8",
            corridorEvidence("hot", 81, 0.37, quality(1.50, 0.36), complete));

        List<CenterlineCandidate> ranked = new AlignmentService().rankCandidatesForTesting(
            List.of(bad, good), corridorConfig());

        assertEquals(good.id(), ranked.get(0).id());
    }

    @Test
    void incompleteMeasuredCorridorCannotBeApplied() {
        CenterlineCandidate incomplete = candidate("hot/strand-2", signalEvidence().withCorridorCoverage(
            new CorridorCoverage(true, false, 3, 10, 0.3, 2, 4, 5.0, 12.0,
                0, 0.0, 0, true, "unsupported-trailing-corridor")));
        SelectionContext selection = selection(3);
        AlignmentResult base = new AlignmentResult(selection, null, List.of(incomplete),
            List.of(new EastNorth(0, 0), new EastNorth(10, 0), new EastNorth(20, 0)),
            List.of(), List.of(),
            new AlignmentDiagnostics("Strava", 1, 0, 0, 0, 0, "{}", "{}", "{}", "[]", "[]", "[]"),
            null);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> new AlignmentService().applyCandidate(base, incomplete, corridorConfig()));
    }

    @Test
    void detailedSelectionKeepsConfiguredAlignmentMode() {
        SelectionContext detailed = selection(8);
        ManagedHeatmapConfig config = config(AlignmentMode.MOVE_EXISTING_NODES);

        assertEquals(AlignmentMode.MOVE_EXISTING_NODES, AlignmentService.effectiveAlignmentMode(detailed, config));
    }

    @Test
    void shortSegmentOfLongerWayIsNotTreatedAsSketch() {
        SelectionContext segment = segmentSelection(8, 2, 5);
        ManagedHeatmapConfig config = config(AlignmentMode.MOVE_EXISTING_NODES);

        assertEquals(AlignmentMode.MOVE_EXISTING_NODES, AlignmentService.effectiveAlignmentMode(segment, config));
    }

    @Test
    void alternativeDetectorMappingsAndSourceColorAggregationAreIndependent() {
        AlignmentService service = new AlignmentService();
        ManagedHeatmapConfig alternativesOnly = config(AlignmentMode.MOVE_EXISTING_NODES, true, false);
        ManagedHeatmapConfig aggregateOnly = config(AlignmentMode.MOVE_EXISTING_NODES, false, true);
        ManagedHeatmapConfig visualizationOnly = config(AlignmentMode.MOVE_EXISTING_NODES, false, false, true);

        assertTrue(service.detectionColorModes(alternativesOnly).contains("bluered-combined"));
        assertTrue(service.detectionColorModes(alternativesOnly).contains("blue-corridor"));
        assertTrue(service.detectionColorModes(alternativesOnly).contains("purple-corridor"));
        assertEquals(List.of("hot"), service.sourceTileColors(alternativesOnly));
        assertEquals(List.of("hot"), service.detectionColorModes(aggregateOnly));
        assertTrue(service.sourceTileColors(aggregateOnly).containsAll(List.of("hot", "blue", "bluered", "purple", "gray")));
        assertFalse(service.detectionColorModes(aggregateOnly).contains("bluered-combined"));
        assertEquals(List.of("hot"), service.detectionColorModes(visualizationOnly));
        assertEquals(List.of("hot"), service.sourceTileColors(visualizationOnly),
            "Diagnostic aggregate visibility must not expand alignment source acquisition");
    }

    @Test
    void everyRequestedHotMappingLeavesAnAttemptEvenWithoutCandidates() {
        AlignmentService service = new AlignmentService();
        ManagedHeatmapConfig alternatives = config(AlignmentMode.MOVE_EXISTING_NODES, true, false);

        var attempts = service.detectorAttempts(service.detectionColorModes(alternatives), List.of(),
            alternatives, 0, false);

        assertTrue(attempts.stream().anyMatch(attempt -> attempt.mappingName().equals("hot")
            && attempt.status() == DetectorAttemptStatus.NO_PERSISTENT_CORRIDOR));
    }

    @Test
    void renderedFallbackReportsManagedAggregateAsUnavailable() {
        AlignmentService service = new AlignmentService();
        ManagedHeatmapConfig aggregate = config(AlignmentMode.MOVE_EXISTING_NODES, false, true);

        var attempts = service.detectorAttempts(service.detectionColorModes(aggregate), List.of(),
            aggregate, 0, false);

        assertTrue(attempts.stream().anyMatch(attempt -> attempt.mappingName().equals("all-colors-combined")
            && attempt.status() == DetectorAttemptStatus.SOURCE_UNAVAILABLE));
    }

    @Test
    void samplingSummaryReportsVisibleRenderedLayerZoom() {
        AlignmentDiagnostics diagnostics = new AlignmentDiagnostics(
            "Strava",
            2,
            3,
            10,
            20,
            30,
            "{}",
            "{}",
            "{\"type\":\"rendered-visible-layer\",\"algorithm\":\"v0.2-compatible\",\"tileZoom\":15,\"bestTileZoom\":15,"
                + "\"rasterScale\":6.0,\"rasterWidth\":6000,\"rasterHeight\":3600,"
                + "\"viewMetersPerPixel\":0.75,\"rasterMetersPerPixel\":0.125}",
            "[\"hot\",\"blue\"]",
            "[]",
            "[]"
        );

        assertEquals("visible rendered layer, v0.2-compatible, source tile z15 (best z15), raster 6.0x, "
                + "ground 0.750 m/view-px, sampled 0.1250 m/raster-px, capture 6000x3600",
            diagnostics.samplingSummary());
    }

    @Test
    void samplingSummarySeparatesProjectionGroundAndNativeSourceScales() {
        AlignmentDiagnostics diagnostics = new AlignmentDiagnostics(
            "Strava", 1, 0, 1, 2, 3, "{}", "{}",
            "{\"type\":\"rendered-visible-layer\",\"algorithm\":\"v0.2-compatible\","
                + "\"tileZoom\":15,\"bestTileZoom\":15,\"rasterScale\":6.0,"
                + "\"projectionUnitsPerViewPixel\":0.389,\"groundMetersPerViewPixel\":0.2527,"
                + "\"groundMetersPerRasterPixel\":0.04212,\"nativeSourceResolutionKnown\":true,"
                + "\"nativeSourceTileSizePx\":512,\"nativeSourcePixelSizeRasterPx\":36.84}",
            "[\"hot\"]", "[]", "[]");

        String summary = diagnostics.samplingSummary();
        assertTrue(summary.contains("capture 0.389 projection-units/view-px"));
        assertTrue(summary.contains("ground 0.253 m/view-px"));
        assertTrue(summary.contains("512 px tiles, 36.84 raster-px/source-px"));
    }

    @Test
    void corridorDecisionsUseMeasuredGroundScaleWhileLegacyKeepsReferenceCalibration() {
        double measuredGroundMetersPerViewPixel = 0.2527;

        assertEquals(measuredGroundMetersPerViewPixel,
            AlignmentService.decisionGroundMetersPerViewPixel(
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode.CORRIDOR_AWARE,
                measuredGroundMetersPerViewPixel),
            0.0);
        assertEquals(TileHeatmapSampler.REFERENCE_VIEW_METERS_PER_PIXEL,
            AlignmentService.decisionGroundMetersPerViewPixel(
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode.LEGACY_V02,
                measuredGroundMetersPerViewPixel),
            0.0);
        assertThrows(IllegalArgumentException.class,
            () -> AlignmentService.decisionGroundMetersPerViewPixel(
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode.CORRIDOR_AWARE,
                Double.NaN));
    }

    @Test
    void samplingSummaryReportsEffectiveGroundScaleSampling() {
        AlignmentDiagnostics diagnostics = new AlignmentDiagnostics(
            "Strava",
            2,
            3,
            10,
            20,
            30,
            "{}",
            "{}",
            "{\"type\":\"rendered-visible-layer\",\"algorithm\":\"v0.2-compatible\",\"tileZoom\":15,\"bestTileZoom\":15,"
                + "\"rasterScale\":6.0,\"rasterWidth\":6000,\"rasterHeight\":3600,"
                + "\"viewMetersPerPixel\":0.097266,\"rasterMetersPerPixel\":0.016211,"
                + "\"effectiveHalfWidthMeters\":7.003152,\"effectiveStepMeters\":1.556256,"
                + "\"effectiveHalfWidthPx\":72,\"effectiveStepPx\":16}",
            "[\"hot\",\"blue\"]",
            "[]",
            "[]"
        );

        assertTrue(diagnostics.samplingSummary().contains("search half 7.00 m (72 px), step 1.56 m (16 px)"));
    }

    @Test
    void samplingSummaryReportsManagedSourceTiles() {
        AlignmentDiagnostics diagnostics = new AlignmentDiagnostics(
            "Managed",
            2,
            3,
            10,
            20,
            30,
            "{}",
            "{}",
            "{\"type\":\"managed-source-tiles\",\"algorithm\":\"fixed-scale source tiles\",\"tileZoom\":15,\"bestTileZoom\":15,"
                + "\"rasterScale\":6.0,\"rasterWidth\":1536,\"rasterHeight\":1024,"
                + "\"viewMetersPerPixel\":0.389,\"rasterMetersPerPixel\":0.064833,"
                + "\"effectiveHalfWidthMeters\":7.01,\"effectiveStepMeters\":1.56,"
                + "\"effectiveHalfWidthPx\":18,\"effectiveStepPx\":4}",
            "[\"bluered\",\"bluered-combined\"]",
            "[]",
            "[]"
        );

        assertTrue(diagnostics.samplingSummary().contains("managed fixed-resolution source tiles, fixed-scale source tiles"));
        assertTrue(diagnostics.samplingSummary().contains("source tile z15 (best z15)"));
        assertTrue(diagnostics.samplingSummary().contains("search half 7.01 m (18 px), step 1.56 m (4 px)"));
    }

    @Test
    void candidateSwitchUsesStoredSlideTimeGeometryWithoutCurrentMapView() {
        AlignmentService service = new AlignmentService();
        SelectionContext selection = selection(3);
        List<EastNorth> source = List.of(
            new EastNorth(0.0, 0.0),
            new EastNorth(10.0, 0.0),
            new EastNorth(20.0, 0.0)
        );
        CenterlineCandidate candidate = new CenterlineCandidate(
            "hot/ridge-1",
            1.0,
            List.of(new java.awt.geom.Point2D.Double(9999.0, 9999.0)),
            List.of(0.0)
        ).withEastNorthPoints(List.of(
            new EastNorth(0.0, 0.0),
            new EastNorth(10.0, 10.0),
            new EastNorth(20.0, 0.0)
        )).withEvidence(signalEvidence());
        AlignmentResult base = new AlignmentResult(
            selection,
            null,
            List.of(candidate),
            source,
            source,
            List.of(),
            new AlignmentDiagnostics("Strava", 1, 0, 0, 0, 0, "{}", "{}", "{}", "[\"hot\"]", "[]", "[]"),
            null
        );

        AlignmentResult result = service.applyCandidate(base, candidate);

        assertEquals(1, result.nodeMoves().size());
        assertEquals(10.0, result.nodeMoves().get(0).target().east(), 1e-9);
        assertEquals(10.0, result.nodeMoves().get(0).target().north(), 1e-9);
    }

    @Test
    void blueredVisibleColorPrefersNativeBlueredDetectorsOverGenericCorridorDetectors() {
        assertTrue(AlignmentService.detectorPrior("bluered", "bluered-combined")
            > AlignmentService.detectorPrior("bluered", "multi-combined"));
        assertTrue(AlignmentService.detectorPrior("bluered", "bluered-corridor")
            > AlignmentService.detectorPrior("bluered", "dual-corridor"));
        assertTrue(AlignmentService.detectorPrior("bluered", "bluered")
            > AlignmentService.detectorPrior("bluered", "hot"));
    }

    @Test
    void purpleVisibleColorPrefersRecalibratedPurpleDetectorOverGenericHotDetector() {
        assertTrue(AlignmentService.detectorPrior("purple", "purple")
            > AlignmentService.detectorPrior("purple", "hot"));
        assertTrue(AlignmentService.detectorPrior("purple", "purple-corridor")
            > AlignmentService.detectorPrior("purple", "hot-corridor"));
        assertTrue(AlignmentService.detectorPrior("purple", "purple-strict")
            > AlignmentService.detectorPrior("purple", "gray"));
    }

    @Test
    void candidateSwitchRejectsNoSignalCandidate() {
        AlignmentService service = new AlignmentService();
        SelectionContext selection = selection(3);
        List<EastNorth> source = List.of(
            new EastNorth(0.0, 0.0),
            new EastNorth(10.0, 0.0),
            new EastNorth(20.0, 0.0)
        );
        CenterlineCandidate candidate = new CenterlineCandidate(
            "hot/ridge-1",
            1.0,
            List.of(),
            List.of()
        ).withEastNorthPoints(source);
        AlignmentResult base = new AlignmentResult(
            selection,
            null,
            List.of(candidate),
            source,
            source,
            List.of(),
            new AlignmentDiagnostics("Strava", 1, 0, 0, 0, 0, "{}", "{}", "{}", "[\"hot\"]", "[]", "[]"),
            null
        );

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> service.applyCandidate(base, candidate));
    }

    @Test
    void candidateSwitchRejectsStructurallyUnsafeCandidate() {
        AlignmentService service = new AlignmentService();
        SelectionContext selection = selection(3);
        List<EastNorth> source = List.of(
            new EastNorth(0.0, 0.0),
            new EastNorth(10.0, 0.0),
            new EastNorth(20.0, 0.0)
        );
        CenterlineCandidate candidate = new CenterlineCandidate(
            "hot/ridge-1",
            1.0,
            List.of(),
            List.of()
        ).withEastNorthPoints(source)
            .withEvidence(signalEvidence())
            .withSafetyWarnings(List.of("abrupt lateral acceleration 13.2m"));
        AlignmentResult base = new AlignmentResult(
            selection,
            null,
            List.of(candidate),
            source,
            source,
            List.of(),
            new AlignmentDiagnostics("Strava", 1, 0, 0, 0, 0, "{}", "{}", "{}", "[\"hot\"]", "[]", "[]"),
            null
        );

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> service.applyCandidate(base, candidate));
    }

    private SelectionContext selection(int nodeCount) {
        Way way = new Way();
        List<Node> nodes = java.util.stream.IntStream.range(0, nodeCount)
            .mapToObj(index -> new Node(new LatLon(0.0, index * 0.0001)))
            .toList();
        way.setNodes(nodes);
        return new SelectionContext(way, 0, nodeCount - 1, nodes, Set.of(nodes.get(0), nodes.get(nodeCount - 1)));
    }

    private Node nodeAt(double east, double north) {
        return new Node(ProjectionRegistry.getProjection().eastNorth2latlon(new EastNorth(east, north)));
    }

    private CenterlineCandidate crossingAtNorthing(EastNorth center, double projectedNorthing) {
        return new CenterlineCandidate("strand", 1.0, List.of(), List.of())
            .withEastNorthPoints(List.of(
                new EastNorth(center.east() - 10.0, center.north() + projectedNorthing),
                new EastNorth(center.east() + 10.0, center.north() + projectedNorthing),
                center));
    }

    private SelectionContext segmentSelection(int nodeCount, int start, int end) {
        Way way = new Way();
        List<Node> nodes = java.util.stream.IntStream.range(0, nodeCount)
            .mapToObj(index -> new Node(new LatLon(0.0, index * 0.0001)))
            .toList();
        way.setNodes(nodes);
        List<Node> segment = nodes.subList(start, end + 1);
        return new SelectionContext(way, start, end, segment, Set.of(segment.get(0), segment.get(segment.size() - 1)));
    }

    private ManagedHeatmapConfig config(AlignmentMode mode) {
        return config(mode, false, false);
    }

    private ManagedHeatmapConfig config(AlignmentMode mode, boolean alternativeDetectors, boolean aggregateAllColorSchemes) {
        return config(mode, alternativeDetectors, aggregateAllColorSchemes, false);
    }

    private ManagedHeatmapConfig config(
        AlignmentMode mode,
        boolean alternativeDetectors,
        boolean aggregateAllColorSchemes,
        boolean showAggregateIntensityLayer
    ) {
        return new ManagedHeatmapConfig(
            "", "", "", "",
            "all",
            "hot",
            "",
            ".*",
            mode,
            TrackerMode.LEGACY_V02,
            false,
            false,
            alternativeDetectors,
            aggregateAllColorSchemes,
            showAggregateIntensityLayer,
            false,
            true,
            false,
            false,
            false,
            18,
            4,
            3.0,
            InferenceMode.STABLE_FIXED_SCALE,
            15,
            13,
            7.01,
            1.56,
            IntensitySamplingMode.COLOR_MAPPING,
            0L
        );
    }

    private CandidateEvidence signalEvidence() {
        return new CandidateEvidence("hot", 3, 3, 0, 0, 2.4, 0.8, 0.2, 1.0, 0.4, 0.0, List.of());
    }

    private CandidateGeometryCleanup cleanupReport(
        String parentId,
        CandidateGeometryCleanup.Outcome outcome
    ) {
        return new CandidateGeometryCleanup(parentId, outcome, "test", List.of(),
            5, 5, 3, 1, 0, 2, 1, 0, 1.0, 1.0, 0.0,
            OptionalDouble.of(0.1), OptionalDouble.of(1.0));
    }

    private ManagedHeatmapConfig corridorConfig() {
        ManagedHeatmapConfig legacy = config(AlignmentMode.MOVE_EXISTING_NODES);
        return new ManagedHeatmapConfig(
            legacy.keyPairId(), legacy.policy(), legacy.signature(), legacy.sessionToken(),
            legacy.activity(), legacy.color(), legacy.manualLayerName(), legacy.layerRegex(),
            legacy.alignmentMode(), TrackerMode.CORRIDOR_AWARE, legacy.verbose(), legacy.debug(),
            legacy.multiColorDetection(), legacy.aggregateAllColorSchemes(),
            legacy.showAggregateIntensityLayer(), legacy.candidateRatingEnabled(), legacy.parallelWayAwareness(),
            legacy.allowUndownloadedAlignment(), legacy.adjustJunctionNodes(), legacy.simplifyEnabled(), legacy.crossSectionHalfWidthPx(),
            legacy.crossSectionStepPx(), legacy.simplifyTolerancePx(), legacy.inferenceMode(),
            legacy.inferenceZoom(), legacy.validationZoom(), legacy.searchHalfWidthMeters(),
            legacy.sampleStepMeters(), legacy.intensitySamplingMode(), legacy.cacheBuster());
    }

    private ManagedHeatmapConfig corridorPreciseConfigWithLegacySimplification() {
        ManagedHeatmapConfig base = config(AlignmentMode.PRECISE_SHAPE);
        return new ManagedHeatmapConfig(
            base.keyPairId(), base.policy(), base.signature(), base.sessionToken(),
            base.activity(), base.color(), base.manualLayerName(), base.layerRegex(),
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE, base.verbose(), base.debug(),
            base.multiColorDetection(), base.aggregateAllColorSchemes(), base.showAggregateIntensityLayer(),
            base.candidateRatingEnabled(), base.parallelWayAwareness(), base.allowUndownloadedAlignment(),
            base.adjustJunctionNodes(), true, base.crossSectionHalfWidthPx(), base.crossSectionStepPx(), 3.0,
            base.inferenceMode(), base.inferenceZoom(), base.validationZoom(), base.searchHalfWidthMeters(),
            base.sampleStepMeters(), base.intensitySamplingMode(), base.cacheBuster());
    }

    private CenterlineCandidate candidate(String id, CandidateEvidence evidence) {
        return new CenterlineCandidate(id, 0.0,
            List.of(new java.awt.geom.Point2D.Double(0, 0), new java.awt.geom.Point2D.Double(10, 0),
                new java.awt.geom.Point2D.Double(20, 0)),
            List.of(0.0, 0.0, 0.0)).withEvidence(evidence);
    }

    private CandidateEvidence corridorEvidence(
        String detector,
        int supported,
        double optimizerCost,
        CorridorQuality quality,
        CorridorCoverage coverage
    ) {
        int total = 100;
        return new CandidateEvidence(detector, total, supported, total - supported, total - supported,
            supported * 0.7, 0.7, 0.4, 0.8, 0.5, 0.0, 0.9, 0.85,
            optimizerCost, 0.95, 0.8, 0.0, quality, coverage, List.of());
    }

    private CorridorCoverage completeCoverage(int total, int observed) {
        return new CorridorCoverage(true, true, observed, total, observed / (double) total,
            0, total - 1, 0.0, 0.0, 0, 0.0, 0, false, "complete");
    }

    private CorridorQuality quality(double residualP95, double persistence) {
        return new CorridorQuality(residualP95 * 0.5, residualP95, 0.1, 0.2, 0.3, 0.4,
            3.0, 8.0, 4.0, 0, 0, 0.0, 5.0, persistence, true);
    }

}
