package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateGeometryCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupEvidenceProvenance;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupSamplingFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupSamplingProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.FinalPreviewCleanupContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProjectedLateralTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PreviewNodeAssignmentPlanner;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ReplaceWaySegmentCommand;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class GeometryCleanupServiceTest {
    private final GeometryCleanupService service = new GeometryCleanupService();

    @BeforeAll
    static void setPreferences() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void preservesRawAndCreatesExactlyOneDeterministicCleanedSiblingWithFreshAssignments() {
        Fixture fixture = fixture(points(0.0, 0.2, -0.2, 0.2, 0.0), Set.of(), Set.of(), false);
        CenterlineCandidate raw = fixture.candidate().withFinalPreviewGeometry(fixture.geometry(), Map.of(
            fixture.selection().segmentNodes().get(0).getUniqueId(), fixture.geometry().get(0),
            fixture.selection().segmentNodes().get(4).getUniqueId(), fixture.geometry().get(4),
            999_999L, new EastNorth(99.0, 99.0)));

        List<CenterlineCandidate> result = service.expand(raw, fixture.selection(), fixture.geometry(),
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertEquals(2, result.size(), result.get(0).geometryCleanup().toString());
        CenterlineCandidate retainedRaw = result.get(0);
        CenterlineCandidate cleaned = result.get(1);
        assertEquals(raw.id(), retainedRaw.id());
        assertEquals(raw.finalPreviewPoints(), retainedRaw.finalPreviewPoints());
        assertEquals(CandidateGeometryCleanup.Outcome.CLEANED_ALTERNATIVE_AVAILABLE,
            retainedRaw.geometryCleanup().outcome());
        assertEquals(raw.id() + "#cleaned", cleaned.id());
        assertEquals(CandidateGeometryCleanup.Outcome.CLEANED, cleaned.geometryCleanup().outcome());
        assertEquals(raw.id(), cleaned.geometryCleanup().parentCandidateId());
        assertTrue(cleaned.finalPreviewPoints().size() < raw.finalPreviewPoints().size());
        assertEquals(cleaned.finalPreviewPoints().size(), cleaned.cleanupEvidence().profiles().size());
        assertFalse(cleaned.proposedNodePositions().containsKey(999_999L));
        assertEquals(PreviewNodeAssignmentPlanner.targetMap(PreviewNodeAssignmentPlanner.preciseAssignments(
            fixture.selection(), fixture.geometry(), cleaned.finalPreviewPoints())), cleaned.proposedNodePositions());
        assertNotSame(raw.proposedNodePositions(), cleaned.proposedNodePositions());
    }

    @Test
    void reportsDisabledLegacyAndMoveModeWithoutCreatingChild() {
        Fixture fixture = fixture(points(0.0, 0.2, -0.2, 0.2, 0.0), Set.of(), Set.of(), false);

        assertOutcome(service.expand(fixture.candidate(), fixture.selection(), fixture.geometry(),
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE, GeometryCleanupConfig.disabled()),
            CandidateGeometryCleanup.Outcome.NOT_REQUESTED, "cleanup-disabled");
        assertOutcome(service.expand(fixture.candidate(), fixture.selection(), fixture.geometry(),
            AlignmentMode.PRECISE_SHAPE, TrackerMode.LEGACY_V02, config(GeometryCleanupMode.REDUCE_POINTS_ONLY)),
            CandidateGeometryCleanup.Outcome.SKIPPED, "tracker-mode-ineligible");
        assertOutcome(service.expand(fixture.candidate(), fixture.selection(), fixture.geometry(),
            AlignmentMode.MOVE_EXISTING_NODES, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.REDUCE_POINTS_ONLY)), CandidateGeometryCleanup.Outcome.SKIPPED,
            "alignment-mode-ineligible");
    }

    @Test
    void noEligibleReductionIntervalIsExplicitlySkipped() {
        Fixture fixture = fixture(points(0.0, 0.0, 0.0), Set.of(), Set.of(1), false);

        List<CenterlineCandidate> result = service.expand(
            fixture.candidate(), fixture.selection(), fixture.geometry(),
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertOutcome(result, CandidateGeometryCleanup.Outcome.SKIPPED, "no-eligible-cleanup-interval");
        assertEquals(fixture.geometry(), result.get(0).finalPreviewPoints());
        assertTrue(result.get(0).geometryCleanup().reasons().contains("NO_ELIGIBLE_INTERVAL"));
    }

    @Test
    void rejectsIncompleteAndUnmappedOrDuplicatedFinalPreviewEvidence() {
        Fixture incomplete = fixture(points(0.0, 0.2, -0.2, 0.2, 0.0), Set.of(), Set.of(), false);
        CenterlineCandidate withoutEvidence = incomplete.candidate().withCleanupEvidence(CandidateCleanupEvidence.empty());
        assertOutcome(service.expand(withoutEvidence, incomplete.selection(), incomplete.geometry(),
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.REDUCE_POINTS_ONLY)), CandidateGeometryCleanup.Outcome.SKIPPED,
            "context-incomplete_evidence");

        Fixture unmapped = fixture(points(0.0, 0.2, -0.2, 0.2, 0.0), Set.of(), Set.of(), false);
        List<EastNorth> altered = new ArrayList<>(unmapped.geometry());
        altered.set(2, new EastNorth(altered.get(2).east(), 0.9));
        CenterlineCandidate unmappedCandidate = unmapped.candidate().withFinalPreviewPoints(altered);
        assertOutcome(service.expand(unmappedCandidate, unmapped.selection(), unmapped.geometry(),
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.REDUCE_POINTS_ONLY)), CandidateGeometryCleanup.Outcome.SKIPPED,
            "context-unmapped_or_duplicate_profile");

        List<EastNorth> duplicate = List.of(new EastNorth(0, 0), new EastNorth(2, 0), new EastNorth(2, 0),
            new EastNorth(6, 0), new EastNorth(8, 0));
        Fixture duplicateFixture = fixture(duplicate, Set.of(), Set.of(), false);
        assertOutcome(service.expand(duplicateFixture.candidate(), duplicateFixture.selection(), duplicate,
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.REDUCE_POINTS_ONLY)), CandidateGeometryCleanup.Outcome.SKIPPED,
            "context-unmapped_or_duplicate_profile");
    }

    @Test
    void freezesLocalEvidenceDefectsButStillRejectsNonMonotonicMapping() {
        for (CleanupEvidenceProvenance provenance : List.of(
            CleanupEvidenceProvenance.BOUNDED_INTERPOLATION, CleanupEvidenceProvenance.UNSUPPORTED)) {
            Fixture fixture = fixture(points(0.0, 0.1, -0.1, 0.1, 0.0), Set.of(), Set.of(), false,
                provenance, false);
            assertLocalDefectPreserved(fixture, 2);
        }
        Fixture offRaster = fixture(points(0.0, 0.1, -0.1, 0.1, 0.0), Set.of(1), Set.of(), false);
        assertLocalDefectPreserved(offRaster, 1);
        Fixture noSignal = fixture(points(0.0, 0.1, -0.1, 0.1, 0.0), Set.of(), Set.of(3), false);
        assertLocalDefectPreserved(noSignal, 3);

        Fixture monotonic = fixture(points(0.0, 0.1, -0.1, 0.1, 0.0), Set.of(), Set.of(), false);
        List<EastNorth> reordered = List.of(monotonic.geometry().get(0), monotonic.geometry().get(2),
            monotonic.geometry().get(1), monotonic.geometry().get(3), monotonic.geometry().get(4));
        assertOutcome(service.expand(monotonic.candidate().withFinalPreviewPoints(reordered), monotonic.selection(),
            monotonic.geometry(), AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.REDUCE_POINTS_ONLY)), CandidateGeometryCleanup.Outcome.SKIPPED,
            "context-non_monotonic_profile_mapping");
    }

    private void assertLocalDefectPreserved(Fixture fixture, int defectIndex) {
        List<CenterlineCandidate> result = service.expand(
            fixture.candidate(), fixture.selection(), fixture.geometry(),
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertEquals(2, result.size(), result.get(0).geometryCleanup().toString());
        assertTrue(result.get(1).finalPreviewPoints().contains(fixture.geometry().get(defectIndex)));
        assertEquals(CandidateGeometryCleanup.Outcome.PARTIALLY_CLEANED,
            result.get(1).geometryCleanup().outcome());
    }

    @Test
    void protectsTaggedInteriorAnchorAndSplitsIndependentIntervals() {
        Fixture fixture = fixture(points(0.0, 0.2, 0.0, -0.2, 0.0), Set.of(), Set.of(), true);
        FinalPreviewCleanupContext context = FinalPreviewCleanupContext.create(
            fixture.candidate(), fixture.selection(), fixture.geometry());

        assertTrue(context.complete());
        assertEquals(Set.of(0, 2, 4), context.protectedIndexes());
        assertEquals(List.of(new FinalPreviewCleanupContext.ProtectedInterval(0, 2),
            new FinalPreviewCleanupContext.ProtectedInterval(2, 4)), context.protectedIntervals());
        List<CenterlineCandidate> result = service.expand(fixture.candidate(), fixture.selection(), fixture.geometry(),
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.REDUCE_POINTS_ONLY));
        assertEquals(2, result.size());
        assertTrue(result.get(1).finalPreviewPoints().contains(fixture.geometry().get(2)));
    }

    @Test
    void reportsPartialWhenOneReductionSpanSucceedsAfterAnotherChordIsRejected() {
        Fixture fixture = fixture(points(0.0, 0.0, 0.0, 3.0, 0.0),
            Set.of(), Set.of(), true);

        List<CenterlineCandidate> result = service.expand(
            fixture.candidate(), fixture.selection(), fixture.geometry(),
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertEquals(2, result.size(), result.get(0).geometryCleanup().toString());
        assertEquals(CandidateGeometryCleanup.Outcome.PARTIALLY_CLEANED,
            result.get(1).geometryCleanup().outcome());
        assertTrue(result.get(1).geometryCleanup().acceptedChordCount() > 0);
        assertTrue(result.get(1).geometryCleanup().eligibleIntervalCount() > 0);
        assertTrue(result.get(1).geometryCleanup().changedIntervalCount() > 0);
        assertEquals(1, result.get(1).geometryCleanup().frozenIntervalCount());
        assertTrue(result.get(1).geometryCleanup().attemptedChordCount()
            > result.get(1).geometryCleanup().acceptedChordCount());
    }

    @Test
    void rejectsCleanupInsteadOfRetargetingExistingMovableTopologyTarget() {
        Fixture fixture = fixture(points(0.0, 0.1, 0.2, -0.1, 0.0), Set.of(), Set.of(), true);
        List<EastNorth> originalSource = points(0.0, 0.0, 0.0, 0.0, 0.0);
        Node taggedInterior = fixture.selection().segmentNodes().get(2);
        assertTrue(fixture.candidate().proposedNodePositions().containsKey(taggedInterior.getUniqueId()));

        List<CenterlineCandidate> result = service.expand(fixture.candidate(), fixture.selection(), originalSource,
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertOutcome(result, CandidateGeometryCleanup.Outcome.REJECTED,
            "fresh-assignment-rejected");
    }

    @Test
    void cleansWhenFixedEndpointsWereRestoredAwayFromRawRidgeEndpoints() {
        List<EastNorth> rawRidge = List.of(new EastNorth(0, 1.0), new EastNorth(2, 0.2),
            new EastNorth(4, -0.2), new EastNorth(6, 0.2), new EastNorth(8, 1.0));
        Fixture fixture = fixture(rawRidge, Set.of(), Set.of(), false);
        List<EastNorth> restoredPreview = points(0.0, 0.2, -0.2, 0.2, 0.0);
        List<EastNorth> immutableSource = points(0.0, 0.0, 0.0, 0.0, 0.0);
        CenterlineCandidate restored = fixture.candidate().withFinalPreviewGeometry(restoredPreview,
            fixture.candidate().proposedNodePositions());
        FinalPreviewCleanupContext context = FinalPreviewCleanupContext.create(restored, fixture.selection(),
            immutableSource);

        assertTrue(context.complete());
        assertEquals(List.of(0, 1, 2, 3, 4), context.originalProfileIndexes());
        List<CenterlineCandidate> result = service.expand(restored, fixture.selection(), immutableSource,
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertEquals(2, result.size());
        assertEquals(immutableSource.get(0), result.get(1).finalPreviewPoints().get(0));
        assertEquals(immutableSource.get(4), result.get(1).finalPreviewPoints()
            .get(result.get(1).finalPreviewPoints().size() - 1));
    }

    @Test
    void cleansPreviewWithInsertedProtectedJunctionAnchorWithoutInventingAProfile() {
        List<EastNorth> rawRidge = points(0.0, 0.2, -0.2, 0.2, 0.0);
        List<EastNorth> source = List.of(rawRidge.get(0), new EastNorth(3.0, 0.0), rawRidge.get(4));
        List<Node> nodes = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            nodes.add(new Node(new LatLon(50.0 + index * 0.0001, 14.0)));
        }
        nodes.get(1).put("barrier", "yes");
        DataSet dataSet = new DataSet();
        nodes.forEach(dataSet::addPrimitive);
        Way way = new Way();
        way.setNodes(nodes);
        dataSet.addPrimitive(way);
        SelectionContext selection = new SelectionContext(way, 0, 2, List.copyOf(nodes),
            Set.of(nodes.get(0), nodes.get(2)));

        EastNorth junctionTarget = source.get(1);
        List<EastNorth> reconstructed = List.of(rawRidge.get(0), rawRidge.get(1), junctionTarget,
            rawRidge.get(2), rawRidge.get(3), rawRidge.get(4));
        Map<Long, EastNorth> targets = Map.of(
            nodes.get(0).getUniqueId(), source.get(0),
            nodes.get(1).getUniqueId(), junctionTarget,
            nodes.get(2).getUniqueId(), source.get(2));
        CenterlineCandidate raw = new CenterlineCandidate("hot/ridge-inserted-anchor", 0.73,
            List.of(), List.of())
            .withEastNorthPoints(rawRidge)
            .withFinalPreviewGeometry(reconstructed, targets)
            .withCleanupEvidence(evidence(rawRidge, Set.of(), Set.of(), null));

        FinalPreviewCleanupContext context = FinalPreviewCleanupContext.create(raw, selection, source);
        assertTrue(context.complete(), context.status().name());
        assertEquals(rawRidge.size(), context.geometry().size());
        assertTrue(context.geometry().contains(junctionTarget));
        int protectedJunctionIndex = context.protectedIndexes().stream()
            .filter(index -> context.geometry().get(index).equals(junctionTarget))
            .findFirst().orElseThrow();
        assertEquals(CleanupEvidenceProvenance.UNSUPPORTED,
            context.evidence().profiles().get(protectedJunctionIndex).provenance());

        List<CenterlineCandidate> result = service.expand(raw, selection, source,
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.REDUCE_POINTS_ONLY));
        assertEquals(2, result.size(), result.get(0).geometryCleanup().toString());
        assertEquals(reconstructed, result.get(0).finalPreviewPoints());
        assertEquals(CandidateGeometryCleanup.Outcome.CLEANED_ALTERNATIVE_AVAILABLE,
            result.get(0).geometryCleanup().outcome());
        assertTrue(result.get(1).finalPreviewPoints().contains(junctionTarget));
        assertEquals(junctionTarget,
            result.get(1).proposedNodePositions().get(nodes.get(1).getUniqueId()));
    }

    @Test
    void freezesNonadjacentProtectedAnchorAndCleansIndependentMappedInterval() {
        List<EastNorth> rawRidge = points(0.0, 0.2, -0.2, 0.2, -0.2, 0.2, 0.0);
        EastNorth junctionTarget = new EastNorth(3.0, 0.0);
        List<EastNorth> source = List.of(rawRidge.get(0), junctionTarget, rawRidge.get(6));
        List<Node> nodes = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            nodes.add(new Node(new LatLon(50.0 + index * 0.0001, 14.0)));
        }
        nodes.get(1).put("barrier", "yes");
        DataSet dataSet = new DataSet();
        nodes.forEach(dataSet::addPrimitive);
        Way way = new Way();
        way.setNodes(nodes);
        dataSet.addPrimitive(way);
        SelectionContext selection = new SelectionContext(way, 0, 2, List.copyOf(nodes),
            Set.of(nodes.get(0), nodes.get(2)));
        List<EastNorth> reconstructed = List.of(rawRidge.get(0), rawRidge.get(1), junctionTarget,
            rawRidge.get(4), rawRidge.get(5), rawRidge.get(6));
        CenterlineCandidate raw = new CenterlineCandidate("hot/nonadjacent-anchor", 0.73,
            List.of(), List.of())
            .withEastNorthPoints(rawRidge)
            .withFinalPreviewGeometry(reconstructed, Map.of(
                nodes.get(0).getUniqueId(), source.get(0),
                nodes.get(1).getUniqueId(), junctionTarget,
                nodes.get(2).getUniqueId(), source.get(2)))
            .withCleanupEvidence(evidence(rawRidge, Set.of(), Set.of(), null));

        FinalPreviewCleanupContext context = FinalPreviewCleanupContext.create(raw, selection, source);

        assertEquals(FinalPreviewCleanupContext.Status.NONADJACENT_PROTECTED_ANCHOR,
            context.status());
        FinalPreviewCleanupContext.CleanupReconciliation reconciliation =
            FinalPreviewCleanupContext.reconcile(raw, selection, source);
        assertTrue(reconciliation.cleanable());
        assertFalse(reconciliation.globallyComplete());
        assertEquals(Set.of(1, 2, 3), reconciliation.frozenIndexes());
        assertEquals(List.of(List.of(3, 4, 5)),
            reconciliation.slices().stream().map(FinalPreviewCleanupContext.CleanupSlice::geometryIndexes).toList());
        List<CenterlineCandidate> result = service.expand(raw, selection, source, AlignmentMode.PRECISE_SHAPE,
            TrackerMode.CORRIDOR_AWARE, config(GeometryCleanupMode.REDUCE_POINTS_ONLY));

        assertEquals(2, result.size(), result.get(0).geometryCleanup().toString());
        assertEquals(CandidateGeometryCleanup.Outcome.CLEANED_ALTERNATIVE_AVAILABLE,
            result.get(0).geometryCleanup().outcome());
        CenterlineCandidate cleaned = result.get(1);
        assertEquals(CandidateGeometryCleanup.Outcome.PARTIALLY_CLEANED,
            cleaned.geometryCleanup().outcome());
        assertEquals(1, cleaned.geometryCleanup().eligibleIntervalCount());
        assertEquals(1, cleaned.geometryCleanup().changedIntervalCount());
        assertEquals(1, cleaned.geometryCleanup().frozenIntervalCount());
        assertTrue(cleaned.finalPreviewPoints().contains(junctionTarget));
        assertEquals(junctionTarget,
            cleaned.proposedNodePositions().get(nodes.get(1).getUniqueId()));
        assertTrue(cleaned.cleanupEvidence().profiles().isEmpty());
        assertTrue(cleaned.finalPreviewPoints().size() < reconstructed.size());
    }

    @Test
    void reportsRejectedAndSkippedWithoutHidingRawCandidate() {
        List<EastNorth> crossing = List.of(new EastNorth(0, 0), new EastNorth(2, 2), new EastNorth(0, 2),
            new EastNorth(2, 0), new EastNorth(4, 0));
        Fixture rejected = fixture(crossing, Set.of(), Set.of(), false);
        assertOutcome(service.expand(rejected.candidate(), rejected.selection(), crossing,
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE)), CandidateGeometryCleanup.Outcome.REJECTED,
            "smoothing-rejected");

        Fixture unchanged = fixture(points(0.0, 0.2, -0.2, 0.2, 0.0), Set.of(), Set.of(), false, null, true);
        assertOutcome(service.expand(unchanged.candidate(), unchanged.selection(), unchanged.geometry(),
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.REDUCE_POINTS_ONLY)), CandidateGeometryCleanup.Outcome.SKIPPED,
            "no-eligible-cleanup-interval");
    }

    @Test
    void reportsSkippedWhenNoCleanupIntervalIsEligible() {
        Fixture frozen = fixture(points(0.0, 0.2, -0.2, 0.2, 0.0),
            Set.of(), Set.of(1, 2, 3), false);

        assertOutcome(service.expand(frozen.candidate(), frozen.selection(), frozen.geometry(),
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.REDUCE_POINTS_ONLY)), CandidateGeometryCleanup.Outcome.SKIPPED,
            "no-eligible-cleanup-interval");
    }

    @Test
    void supportsBothCleanupModesAndProducesDeterministicResults() {
        Fixture fixture = fixture(points(0.0, 0.6, -0.6, 0.6, 0.0), Set.of(), Set.of(), false);
        List<CenterlineCandidate> smooth = service.expand(fixture.candidate(), fixture.selection(), fixture.geometry(),
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE));
        List<CenterlineCandidate> reduce = service.expand(fixture.candidate(), fixture.selection(), fixture.geometry(),
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.REDUCE_POINTS_ONLY));
        List<CenterlineCandidate> repeat = service.expand(fixture.candidate(), fixture.selection(), fixture.geometry(),
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE));

        assertEquals(2, smooth.size());
        assertEquals(2, reduce.size());
        assertEquals(smooth.get(1).id(), repeat.get(1).id());
        assertEquals(smooth.get(1).finalPreviewPoints(), repeat.get(1).finalPreviewPoints());
        assertEquals(smooth.get(1).proposedNodePositions(), repeat.get(1).proposedNodePositions());
    }

    @Test
    void generatedCleanedSiblingAppliesAndReplaysExactTopologyAcrossUndoRedo() {
        List<EastNorth> source = List.of(new EastNorth(0.0, 0.0), new EastNorth(10.0, 0.0),
            new EastNorth(20.0, 0.0), new EastNorth(30.0, 0.0), new EastNorth(40.0, 0.0));
        List<Node> nodes = source.stream()
            .map(point -> new Node(ProjectionRegistry.getProjection().eastNorth2latlon(point)))
            .toList();
        Node sideEnd = new Node(ProjectionRegistry.getProjection().eastNorth2latlon(
            new EastNorth(20.0, 20.0)));
        DataSet dataSet = new DataSet();
        nodes.forEach(dataSet::addPrimitive);
        dataSet.addPrimitive(sideEnd);
        Way selected = new Way();
        selected.setNodes(nodes);
        dataSet.addPrimitive(selected);
        Way connected = new Way();
        connected.setNodes(List.of(nodes.get(2), sideEnd));
        dataSet.addPrimitive(connected);
        SelectionContext selection = new SelectionContext(selected, 0, 4, nodes,
            Set.of(nodes.get(0), nodes.get(4)));
        List<EastNorth> ridge = List.of(source.get(0), new EastNorth(10.0, 0.4),
            source.get(2), new EastNorth(30.0, -0.4), source.get(4));
        List<EastNorth> reconstructed = PreviewNodeAssignmentPlanner.constrainPreciseTopology(
            selection, source, ridge);
        Map<Long, EastNorth> targets = PreviewNodeAssignmentPlanner.targetMap(
            PreviewNodeAssignmentPlanner.preciseAssignments(selection, source, reconstructed));
        CenterlineCandidate raw = new CenterlineCandidate("hot/apply-cleaned", 0.8,
            List.of(), List.of())
            .withEastNorthPoints(ridge)
            .withFinalPreviewGeometry(reconstructed, targets)
            .withCleanupEvidence(evidence(ridge, Set.of(), Set.of(), null));

        List<CenterlineCandidate> expanded = service.expand(raw, selection, source,
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE,
            config(GeometryCleanupMode.REDUCE_POINTS_ONLY));
        assertEquals(2, expanded.size(), expanded.get(0).geometryCleanup().toString());
        CenterlineCandidate cleaned = expanded.get(1);
        EastNorth junctionTarget = cleaned.proposedNodePositions().get(nodes.get(2).getUniqueId());
        List<LatLon> originalCoordinates = nodes.stream().map(Node::getCoor).toList();
        ReplaceWaySegmentCommand command = new ReplaceWaySegmentCommand(dataSet, selected, selection,
            cleaned.finalPreviewPoints(), cleaned.proposedNodePositions(), "apply cleaned test");

        command.executeCommand();
        List<Node> appliedNodes = List.copyOf(selected.getNodes());
        assertEquals(junctionTarget, nodes.get(2).getEastNorth(ProjectionRegistry.getProjection()));
        assertEquals(nodes.get(2), connected.firstNode());
        assertTrue(appliedNodes.size() < nodes.size());

        command.undoCommand();
        assertEquals(nodes, selected.getNodes());
        for (int index = 0; index < nodes.size(); index++) {
            assertEquals(originalCoordinates.get(index), nodes.get(index).getCoor());
            assertEquals(dataSet, nodes.get(index).getDataSet());
        }
        assertEquals(nodes.get(2), connected.firstNode());

        command.executeCommand();
        assertEquals(appliedNodes, selected.getNodes());
        assertEquals(junctionTarget, nodes.get(2).getEastNorth(ProjectionRegistry.getProjection()));
        assertEquals(nodes.get(2), connected.firstNode());
    }

    @Test
    void rejectsCleanedSiblingWhenReductionWouldChangeMovableJunctionCommandTarget() {
        List<EastNorth> source = List.of(new EastNorth(0.0, 0.0), new EastNorth(10.0, 0.0),
            new EastNorth(20.0, 0.0), new EastNorth(30.0, 0.0), new EastNorth(40.0, 0.0));
        List<Node> nodes = source.stream()
            .map(point -> new Node(ProjectionRegistry.getProjection().eastNorth2latlon(point)))
            .toList();
        Node sideEnd = new Node(ProjectionRegistry.getProjection().eastNorth2latlon(
            new EastNorth(20.0, 20.0)));
        DataSet dataSet = new DataSet();
        nodes.forEach(dataSet::addPrimitive);
        dataSet.addPrimitive(sideEnd);
        Way selected = new Way();
        selected.setNodes(nodes);
        dataSet.addPrimitive(selected);
        Way connected = new Way();
        connected.setNodes(List.of(nodes.get(2), sideEnd));
        dataSet.addPrimitive(connected);
        SelectionContext selection = new SelectionContext(selected, 0, 4, nodes,
            Set.of(nodes.get(0), nodes.get(4)));
        List<EastNorth> ridge = List.of(source.get(0), new EastNorth(10.0, 0.4),
            new EastNorth(20.0, 1.0), new EastNorth(30.0, -0.4), source.get(4));
        List<EastNorth> reconstructed = PreviewNodeAssignmentPlanner.constrainPreciseTopology(
            selection, source, ridge);
        Map<Long, EastNorth> targets = PreviewNodeAssignmentPlanner.targetMap(
            PreviewNodeAssignmentPlanner.preciseAssignments(selection, source, reconstructed));
        CenterlineCandidate raw = new CenterlineCandidate("hot/reject-command-mismatch", 0.8,
            List.of(), List.of())
            .withEastNorthPoints(ridge)
            .withFinalPreviewGeometry(reconstructed, targets)
            .withCleanupEvidence(evidence(ridge, Set.of(), Set.of(), null));

        assertOutcome(service.expand(raw, selection, source, AlignmentMode.PRECISE_SHAPE,
            TrackerMode.CORRIDOR_AWARE, config(GeometryCleanupMode.REDUCE_POINTS_ONLY)),
            CandidateGeometryCleanup.Outcome.REJECTED, "fresh-assignment-rejected");
    }

    private static void assertOutcome(
        List<CenterlineCandidate> result,
        CandidateGeometryCleanup.Outcome expectedOutcome,
        String reason
    ) {
        assertEquals(1, result.size());
        assertEquals(expectedOutcome, result.get(0).geometryCleanup().outcome());
        assertEquals(reason, result.get(0).geometryCleanup().reasonCode());
    }

    private static GeometryCleanupConfig config(GeometryCleanupMode mode) {
        return GeometryCleanupPreset.BALANCED.apply(mode);
    }

    private static Fixture fixture(
        List<EastNorth> geometry,
        Set<Integer> offRaster,
        Set<Integer> noSignal,
        boolean taggedInterior
    ) {
        return fixture(geometry, offRaster, noSignal, taggedInterior, null, false);
    }

    private static Fixture fixture(
        List<EastNorth> geometry,
        Set<Integer> offRaster,
        Set<Integer> noSignal,
        boolean taggedInterior,
        CleanupEvidenceProvenance movableProvenance,
        boolean allFixed
    ) {
        List<Node> nodes = new ArrayList<>();
        for (int index = 0; index < geometry.size(); index++) {
            nodes.add(new Node(new LatLon(50.0 + index * 0.0001, 14.0)));
        }
        if (taggedInterior) {
            nodes.get(2).put("barrier", "yes");
        }
        DataSet dataSet = new DataSet();
        nodes.forEach(dataSet::addPrimitive);
        Way way = new Way();
        way.setNodes(nodes);
        dataSet.addPrimitive(way);
        Set<Node> fixed = allFixed ? Set.copyOf(nodes) : Set.of(nodes.get(0), nodes.get(nodes.size() - 1));
        SelectionContext selection = new SelectionContext(way, 0, nodes.size() - 1, List.copyOf(nodes), fixed);
        Map<Long, EastNorth> targets = PreviewNodeAssignmentPlanner.targetMap(
            PreviewNodeAssignmentPlanner.preciseAssignments(selection, geometry, geometry));
        CandidateCleanupEvidence evidence = evidence(geometry, offRaster, noSignal, movableProvenance);
        CenterlineCandidate candidate = new CenterlineCandidate("hot/ridge-1", 0.73, List.of(), List.of())
            .withEastNorthPoints(geometry)
            .withFinalPreviewGeometry(geometry, targets)
            .withCleanupEvidence(evidence);
        return new Fixture(geometry, selection, candidate);
    }

    private static CandidateCleanupEvidence evidence(
        List<EastNorth> geometry,
        Set<Integer> offRaster,
        Set<Integer> noSignal,
        CleanupEvidenceProvenance movableProvenance
    ) {
        List<CleanupSamplingProfile> samples = new ArrayList<>();
        List<CandidateCleanupProfile> rows = new ArrayList<>();
        double[] offsets = {-4.0, -3.0, -2.0, -1.0, 0.0, 1.0, 2.0, 3.0, 4.0};
        for (int index = 0; index < geometry.size(); index++) {
            double[] intensity = new double[offsets.length];
            if (!noSignal.contains(index)) {
                for (int sample = 0; sample < offsets.length; sample++) {
                    intensity[sample] = Math.exp(-0.5 * offsets[sample] * offsets[sample]);
                }
            }
            boolean[] valid = new boolean[offsets.length];
            java.util.Arrays.fill(valid, true);
            EastNorth point = geometry.get(index);
            samples.add(new CleanupSamplingProfile(index, index * 2.0, !offRaster.contains(index), 1.0,
                new ProjectedLateralTransform(new EastNorth(point.east(), 0.0), 0.0, 1.0), offsets,
                intensity, intensity, intensity, valid));
            CleanupEvidenceProvenance provenance = movableProvenance != null && index == 2
                ? movableProvenance : CleanupEvidenceProvenance.DIRECT;
            rows.add(provenance == CleanupEvidenceProvenance.UNSUPPORTED
                ? new CandidateCleanupProfile(index, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    0.0, 1.0, provenance, 0.0, 0.0, false)
                : new CandidateCleanupProfile(index, -2.0, 2.0, -4.0, 4.0, 0.0, 1.0, provenance,
                    0.0, 0.0, false));
        }
        return CandidateCleanupEvidence.validated(new CleanupSamplingFrame("cleanup-service-test", samples, 1.0), rows);
    }

    private static List<EastNorth> points(double... offsets) {
        List<EastNorth> result = new ArrayList<>();
        for (int index = 0; index < offsets.length; index++) {
            result.add(new EastNorth(index * 2.0, offsets[index]));
        }
        return List.copyOf(result);
    }

    private record Fixture(List<EastNorth> geometry, SelectionContext selection, CenterlineCandidate candidate) {
    }
}
