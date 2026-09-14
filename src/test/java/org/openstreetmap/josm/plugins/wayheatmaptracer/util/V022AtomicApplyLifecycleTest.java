package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.APIDataSet;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/** Successful apply/undo/redo cases T108-T114 for the v0.22 atomic command. */
class V022AtomicApplyLifecycleTest {
    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @BeforeEach
    void clearUndoStack() {
        UndoRedoHandler.getInstance().clean();
    }

    @AfterEach
    void leaveUndoStackClean() {
        UndoRedoHandler.getInstance().clean();
    }

    @Test
    void t108SuccessfulMultiWayApplyCreatesExactlyOneUndoEntry() {
        V022AtomicApplyTest.Fixture fixture = V022AtomicApplyTest.Fixture.create(false);

        apply(fixture);

        assertEquals(1, UndoRedoHandler.getInstance().getUndoCommands().size());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
        assertAfterState(fixture);
    }

    @Test
    void g601OffEdtUndoIsRejectedWithoutLateMutationOrUndoOwnershipLoss() {
        V022AtomicApplyTest.Fixture fixture = V022AtomicApplyTest.Fixture.create(false);
        ApplyAlignmentEditPlanCommand command = apply(fixture);
        V022AtomicApplyTest.LiveState after = V022AtomicApplyTest.LiveState.capture(fixture);

        IllegalStateException failure = assertThrows(IllegalStateException.class, command::undoCommand);

        assertEquals("Alignment Undo must execute on the EDT", failure.getMessage());
        V022AtomicApplyTest.onEdt(() -> { });
        after.assertMatches(fixture);
        assertEquals(List.of(command), UndoRedoHandler.getInstance().getUndoCommands());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void g602NeverUploadedExistingRemovalIsPhysicalAndUndoRedoExact() {
        V022AtomicApplyTest.Fixture fixture =
            V022AtomicApplyTest.Fixture.createWithNeverUploadedSecondNode();
        V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(fixture);
        Node transientNode = way(fixture, 10L).getNode(1);
        assertTrue(transientNode.isNew());

        ApplyAlignmentEditPlanCommand command = apply(fixture);
        V022AtomicApplyTest.LiveState after = V022AtomicApplyTest.LiveState.capture(fixture);

        assertNull(transientNode.getDataSet());
        APIDataSet upload = new APIDataSet(fixture.dataSet);
        assertFalse(upload.getPrimitivesToDelete().contains(transientNode));
        assertFalse(upload.getPrimitives().contains(transientNode));

        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().undo());
        before.assertMatches(fixture);
        assertSame(transientNode, way(fixture, 10L).getNode(1));

        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().redo());
        after.assertMatches(fixture);
        assertNull(transientNode.getDataSet());
        assertEquals(List.of(command), UndoRedoHandler.getInstance().getUndoCommands());
    }

    @Test
    void g602UploadSetContainsCreatedModifiedAndDeletedPrimitives() {
        V022AtomicApplyTest.Fixture fixture = V022AtomicApplyTest.Fixture.create(false);
        Node removedSecond = node(fixture, 2L);
        Node removedVia = node(fixture, 3L);

        apply(fixture);

        Node created = appliedJunction(fixture);
        APIDataSet upload = new APIDataSet(fixture.dataSet);
        assertEquals(Set.of(created), Set.copyOf(upload.getPrimitivesToAdd()));
        assertEquals(Set.of(node(fixture, 8L), way(fixture, 10L), way(fixture, 11L), relation(fixture)),
            Set.copyOf(upload.getPrimitivesToUpdate()));
        assertEquals(Set.of(removedSecond, removedVia), Set.copyOf(upload.getPrimitivesToDelete()));
        assertDeletedTombstone(fixture, removedSecond);
        assertDeletedTombstone(fixture, removedVia);
    }

    @Test
    void t109UndoRestoresAnInitiallyCleanDatasetExactly() {
        V022AtomicApplyTest.Fixture fixture = V022AtomicApplyTest.Fixture.create(false);
        V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(fixture);
        assertFalse(fixture.dataSet.isModified());
        apply(fixture);

        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().undo());

        before.assertMatches(fixture);
        assertFalse(fixture.dataSet.isModified());
        assertTrue(UndoRedoHandler.getInstance().getUndoCommands().isEmpty());
        assertEquals(1, UndoRedoHandler.getInstance().getRedoCommands().size());
    }

    @Test
    void t110UndoPreservesPreExistingDirtyPrimitivesAndDatasetState() {
        V022AtomicApplyTest.Fixture fixture = V022AtomicApplyTest.Fixture.create(true);
        V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(fixture);
        assertTrue(fixture.dataSet.isModified());
        apply(fixture);

        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().undo());

        before.assertMatches(fixture);
        assertTrue(fixture.dataSet.isModified());
        assertEquals(1, fixture.dataSet.getNodes().stream().filter(Node::isNew).count());
    }

    @Test
    void t111TwentyUndoRedoCyclesRemainBitStable() {
        V022AtomicApplyTest.Fixture fixture = V022AtomicApplyTest.Fixture.create(false);
        V022AtomicApplyTest.LiveState before = V022AtomicApplyTest.LiveState.capture(fixture);
        apply(fixture);
        V022AtomicApplyTest.LiveState after = V022AtomicApplyTest.LiveState.capture(fixture);

        for (int cycle = 0; cycle < 20; cycle++) {
            V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().undo());
            before.assertMatches(fixture);
            V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().redo());
            after.assertMatches(fixture);
        }

        assertEquals(1, UndoRedoHandler.getInstance().getUndoCommands().size());
        assertTrue(UndoRedoHandler.getInstance().getRedoCommands().isEmpty());
    }

    @Test
    void t112UndoLeavesNoCommandOwnedOrphanNodes() {
        V022AtomicApplyTest.Fixture fixture = V022AtomicApplyTest.Fixture.create(false);
        Set<Long> originalNodeIds = fixture.dataSet.getNodes().stream()
            .map(Node::getUniqueId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        apply(fixture);
        Node created = appliedJunction(fixture);
        assertFalse(originalNodeIds.contains(created.getUniqueId()));

        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().undo());

        assertNull(created.getDataSet());
        assertTrue(fixture.dataSet.getWays().stream().noneMatch(way -> way.containsNode(created)));
        assertTrue(fixture.dataSet.getRelations().stream()
            .noneMatch(relation -> relation.getMembers().stream()
                .anyMatch(member -> member.getMember() == created)));
        assertEquals(originalNodeIds, fixture.dataSet.getNodes().stream()
            .map(Node::getUniqueId).collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    @Test
    void t113RedoReusesTheSameCreatedNodeIdObjectAndCoordinate() {
        V022AtomicApplyTest.Fixture fixture = V022AtomicApplyTest.Fixture.create(false);
        apply(fixture);
        Node firstApplied = appliedJunction(fixture);
        long uniqueId = firstApplied.getUniqueId();
        double latitude = firstApplied.lat();
        double longitude = firstApplied.lon();

        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().undo());
        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().redo());

        Node redone = appliedJunction(fixture);
        assertSame(firstApplied, redone);
        assertEquals(uniqueId, redone.getUniqueId());
        assertEquals(latitude, redone.lat());
        assertEquals(longitude, redone.lon());
    }

    @Test
    void t114RelationMembersAndAllReferrersRestoreWithOriginalIdentity() {
        V022AtomicApplyTest.Fixture fixture = V022AtomicApplyTest.Fixture.create(false);
        Relation relation = relation(fixture);
        Node originalVia = (Node) relation.getMember(1).getMember();
        assertEquals(3L, originalVia.getUniqueId());
        assertEquals(Set.of(10L, 11L, 20L), referrerIds(originalVia));

        apply(fixture);
        Node replacement = appliedJunction(fixture);
        assertSame(replacement, relation.getMember(1).getMember());
        assertEquals("via", relation.getMember(1).getRole());
        assertEquals(Set.of(10L, 11L, 20L), referrerIds(replacement));
        assertDeletedTombstone(fixture, originalVia);

        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().undo());

        assertSame(originalVia, relation.getMember(1).getMember());
        assertEquals("via", relation.getMember(1).getRole());
        assertEquals(Set.of(10L, 11L, 20L), referrerIds(originalVia));
        assertTrue(fixture.dataSet.getWays().stream().noneMatch(way -> way.containsNode(replacement)));
        assertTrue(fixture.dataSet.getRelations().stream()
            .noneMatch(current -> current.getMembers().stream()
                .anyMatch(member -> member.getMember() == replacement)));
        assertNull(replacement.getDataSet());
    }

    private static ApplyAlignmentEditPlanCommand apply(V022AtomicApplyTest.Fixture fixture) {
        ApplyAlignmentEditPlanCommand command = fixture.command(point -> { });
        V022AtomicApplyTest.onEdt(() -> UndoRedoHandler.getInstance().add(command));
        return command;
    }

    private static void assertAfterState(V022AtomicApplyTest.Fixture fixture) {
        Way selected = way(fixture, 10L);
        Way incident = way(fixture, 11L);
        Way independent = way(fixture, 12L);
        Relation relation = relation(fixture);
        Node replacement = appliedJunction(fixture);
        DetachedNode expectedMoved = (DetachedNode) fixture.plan.after().primitives().get(
            PrimitiveKey.existing(PrimitiveKey.Type.NODE, 8L));

        assertEquals(List.of(node(fixture, 1L), replacement, node(fixture, 4L)), selected.getNodes());
        assertEquals(List.of(node(fixture, 5L), replacement), incident.getNodes());
        assertSame(replacement, relation.getMember(1).getMember());
        assertCoordinate(node(fixture, 8L), expectedMoved.coordinate());
        assertDeletedTombstone(fixture, node(fixture, 2L));
        assertDeletedTombstone(fixture, node(fixture, 3L));
        assertEquals(List.of(7L, 8L), independent.getNodeIds());
        assertTrue(fixture.dataSet.isModified());
    }

    private static Node appliedJunction(V022AtomicApplyTest.Fixture fixture) {
        return way(fixture, 10L).getNode(1);
    }

    private static Node node(V022AtomicApplyTest.Fixture fixture, long id) {
        return (Node) fixture.dataSet.getPrimitiveById(id, OsmPrimitiveType.NODE);
    }

    private static Way way(V022AtomicApplyTest.Fixture fixture, long id) {
        return (Way) fixture.dataSet.getPrimitiveById(id, OsmPrimitiveType.WAY);
    }

    private static Relation relation(V022AtomicApplyTest.Fixture fixture) {
        return (Relation) fixture.dataSet.getPrimitiveById(20L, OsmPrimitiveType.RELATION);
    }

    private static void assertDeletedTombstone(V022AtomicApplyTest.Fixture fixture, Node node) {
        assertSame(fixture.dataSet, node.getDataSet());
        assertSame(node, fixture.dataSet.getPrimitiveById(node.getPrimitiveId()));
        assertTrue(node.isDeleted());
        assertTrue(node.isModified());
    }

    private static void assertCoordinate(Node actual, GeographicPoint expected) {
        assertEquals(expected.latitudeDegrees(), actual.lat());
        assertEquals(expected.longitudeDegrees(), actual.lon());
    }

    private static Set<Long> referrerIds(OsmPrimitive primitive) {
        return primitive.getReferrers().stream().map(OsmPrimitive::getUniqueId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
