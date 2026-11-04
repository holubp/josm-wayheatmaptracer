package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.SelectionResolver;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.ResourceLimitException;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class ManagedSelectionWitnessTest {
    @ParameterizedTest @ValueSource(strings={"coordinate","tag","node-identity","relation-role","new-referrer","branch-coordinate","projection"})
    void prepaidFinalComparisonRetainsEveryCapturedDependency(String change){
        Fixture fixture=fixture();var selection=SelectionResolver.resolve(fixture.dataSet,false);
        Way branch=way(fixture.middle,node(.003));fixture.dataSet.addPrimitive(branch.getNode(1));fixture.dataSet.addPrimitive(branch);
        Relation route=new Relation();route.setMembers(List.of(new RelationMember("selected",selection.way())));fixture.dataSet.addPrimitive(route);
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner();var witness=ManagedSelectionWitness.capture(fixture.dataSet,selection,ProjectionRegistry.getProjection(),source(selection),fixture.budget(),owner)){
            try(var first=witness.prepareFinalPublicationCheck(fixture.budget(),owner)){
                var before=fixture.budget().snapshot();first.validate();assertEquals(before,fixture.budget().snapshot());
            }
            try(var check=witness.prepareFinalPublicationCheck(fixture.budget(),owner)){
                switch(change){
                    case "coordinate" -> fixture.middle.setCoor(new LatLon(.000001,.001));
                    case "tag" -> fixture.middle.put("synthetic","changed");
                    case "node-identity" -> {Node replacement=node(.001);fixture.dataSet.addPrimitive(replacement);var nodes=new ArrayList<>(selection.way().getNodes());nodes.set(1,replacement);selection.way().setNodes(nodes);}
                    case "relation-role" -> route.setMembers(List.of(new RelationMember("changed",selection.way())));
                    case "new-referrer" -> {Way added=way(fixture.middle,node(.004));fixture.dataSet.addPrimitive(added.getNode(1));fixture.dataSet.addPrimitive(added);}
                    case "branch-coordinate" -> branch.getNode(1).setCoor(new LatLon(.000001,.003));
                    case "projection" -> ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:4326"));
                }
                var before=fixture.budget().snapshot();assertThrows(IllegalStateException.class,check::validate);assertEquals(before,fixture.budget().snapshot());
            }
        }finally{ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));}
        assertEquals(0,ledger.currentBytes());
    }
    @Test void finalComparisonRejectsForeignAttemptAndClosedTicket(){
        Fixture fixture=fixture();var selection=SelectionResolver.resolve(fixture.dataSet,false);
        try(var owner=AttemptMemoryLedger.production().rootOwner();var witness=ManagedSelectionWitness.capture(fixture.dataSet,selection,ProjectionRegistry.getProjection(),source(selection),fixture.budget(),owner)){
            var foreign=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);var before=fixture.budget().snapshot();
            assertThrows(IllegalArgumentException.class,()->witness.prepareFinalPublicationCheck(foreign,owner));assertEquals(before,fixture.budget().snapshot());
            var check=witness.prepareFinalPublicationCheck(fixture.budget(),owner);check.close();assertThrows(IllegalStateException.class,check::validate);
        }
    }
    @BeforeAll
    static void configure() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void capturesSelectedSourceAndOnlyRelevantReferrers() {
        Fixture fixture = fixture();
        Node unrelated = node(0.02);
        fixture.dataSet.addPrimitive(unrelated);
        Node unloaded = new Node(99);
        fixture.dataSet.addPrimitive(unloaded);
        Relation unrelatedIncomplete = relation(unrelated, unloaded);
        fixture.dataSet.addPrimitive(unrelatedIncomplete);
        Way branch = way(fixture.middle, node(0.003));
        fixture.dataSet.addPrimitive(branch.getNode(1));
        fixture.dataSet.addPrimitive(branch);
        SelectionContext selection = SelectionResolver.resolve(fixture.dataSet, false);
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            ManagedSelectionWitness witness = ManagedSelectionWitness.capture(fixture.dataSet,
                    selection, ProjectionRegistry.getProjection(), source(selection),
                    fixture.budget(), owner);
            assertEquals(selection.segmentNodes(), witness.selectedOccurrences());
            assertTrue(witness.isProtected(fixture.middle));
            assertFalse(witness.isProtected(unrelated));
            witness.requireCurrent(fixture.budget(), owner);
            branch.getNode(1).setCoor(new LatLon(0.0, 0.004));
            assertThrows(IllegalStateException.class,
                    () -> witness.requireCurrent(fixture.budget(), owner));
        }
    }

    @Test
    void retainsRelevantRelationMembershipWithDistantUnloadedMember() {
        Fixture fixture = fixture();
        Way unloaded = new Way(100);
        fixture.dataSet.addPrimitive(unloaded);
        Relation route = new Relation();
        route.setMembers(List.of(new RelationMember("selected", fixture.dataSet.getSelectedWays().iterator().next()),
                new RelationMember("distant", unloaded)));
        fixture.dataSet.addPrimitive(route);
        SelectionContext selection = SelectionResolver.resolve(fixture.dataSet, false);
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            ManagedSelectionWitness witness = ManagedSelectionWitness.capture(
                    fixture.dataSet, selection, ProjectionRegistry.getProjection(),
                    source(selection), fixture.budget(), owner);
            witness.requireCurrent(fixture.budget(), owner);
            route.setMembers(List.of(new RelationMember("changed", selection.way()),
                    new RelationMember("distant", unloaded)));
            assertThrows(IllegalStateException.class,
                    () -> witness.requireCurrent(fixture.budget(), owner));
            Way otherUnloaded = new Way(102);
            fixture.dataSet.addPrimitive(otherUnloaded);
            route.setMembers(List.of(new RelationMember("selected", selection.way()),
                    new RelationMember("distant", otherUnloaded)));
            assertThrows(IllegalStateException.class,
                    () -> witness.requireCurrent(fixture.budget(), owner));
        }
    }

    @Test
    void refusesIncompleteIncidentWayGeometry() {
        Fixture fixture = fixture();
        SelectionContext selection = SelectionResolver.resolve(fixture.dataSet, false);
        Node unloaded = new Node(101);
        fixture.dataSet.addPrimitive(unloaded);
        Way incident = way(fixture.middle, unloaded);
        fixture.dataSet.addPrimitive(incident);
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            assertThrows(IllegalStateException.class, () -> ManagedSelectionWitness.capture(
                    fixture.dataSet, selection, ProjectionRegistry.getProjection(),
                    source(selection), fixture.budget(), owner));
        }
    }

    @Test
    void rejectsChangedSourceAndNewIncomingReferrer() {
        Fixture fixture = fixture();
        SelectionContext selection = SelectionResolver.resolve(fixture.dataSet, false);
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            ManagedSelectionWitness witness = ManagedSelectionWitness.capture(fixture.dataSet,
                    selection, ProjectionRegistry.getProjection(), source(selection),
                    fixture.budget(), owner);
            fixture.middle.setCoor(new LatLon(0.0, 0.008));
            assertThrows(IllegalStateException.class,
                    () -> witness.requireCurrent(fixture.budget(), owner));
        }
        Fixture changedReferrer = fixture();
        SelectionContext secondSelection = SelectionResolver.resolve(changedReferrer.dataSet, false);
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            ManagedSelectionWitness witness = ManagedSelectionWitness.capture(changedReferrer.dataSet,
                    secondSelection, ProjectionRegistry.getProjection(), source(secondSelection),
                    changedReferrer.budget(), owner);
            Relation incoming = relation(changedReferrer.middle);
            changedReferrer.dataSet.addPrimitive(incoming);
            assertThrows(IllegalStateException.class,
                    () -> witness.requireCurrent(changedReferrer.budget(), owner));
        }
    }

    @Test
    void protectsDirectRelationMemberEvenWithoutCallerFixedFlag() {
        Fixture fixture = fixture();
        fixture.dataSet.addPrimitive(relation(fixture.middle));
        SelectionContext resolved = SelectionResolver.resolve(fixture.dataSet, false);
        SelectionContext noFixedClaim = new SelectionContext(resolved.way(), resolved.startIndex(),
                resolved.endIndex(), resolved.segmentNodes(), Set.of());
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            ManagedSelectionWitness witness = ManagedSelectionWitness.capture(fixture.dataSet,
                    noFixedClaim, ProjectionRegistry.getProjection(), source(noFixedClaim),
                    fixture.budget(), owner);
            assertTrue(witness.isProtected(fixture.middle));
        }
    }

    @Test
    void refusesMemoryAndCancellationBeforePublishingWitness() {
        Fixture fixture = fixture();
        SelectionContext selection = SelectionResolver.resolve(fixture.dataSet, false);
        AttemptMemoryLedger memory = new AttemptMemoryLedger(1024);
        try (var owner = memory.rootOwner()) {
            var failure = assertThrows(ResourceLimitException.class, () -> ManagedSelectionWitness.capture(
                    fixture.dataSet, selection, ProjectionRegistry.getProjection(),
                    source(selection), fixture.budget(), owner));
            assertEquals(0, memory.currentBytes());
            assertSame(failure, assertThrows(ResourceLimitException.class,
                    () -> fixture.budget().charge(EmissionWorkBudget.Operation.PREPARATION, 0)));
        }
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            EmissionWorkBudget cancelled = new EmissionWorkBudget(TraceBudgets.defaults(), () -> true);
            assertThrows(CancellationException.class, () -> ManagedSelectionWitness.capture(
                    fixture.dataSet, selection, ProjectionRegistry.getProjection(),
                    source(selection), cancelled, owner));
        }
    }

    @Test
    void selectedOccurrenceAccessorsRetainFrozenIdentityEvenIfCallerListChanges() {
        Fixture fixture = fixture();
        SelectionContext resolved = SelectionResolver.resolve(fixture.dataSet, false);
        List<Node> callerList = new ArrayList<>(resolved.segmentNodes());
        SelectionContext mutableCaller = new SelectionContext(resolved.way(),
                resolved.startIndex(), resolved.endIndex(), callerList, resolved.fixedNodes());
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            ManagedSelectionWitness witness = ManagedSelectionWitness.capture(fixture.dataSet,
                    mutableCaller, ProjectionRegistry.getProjection(), source(mutableCaller),
                    fixture.budget(), owner);
            callerList.set(1, fixture.before);
            assertEquals(fixture.middle, witness.selectedOccurrences().get(1));
            assertThrows(IllegalStateException.class,
                    () -> witness.requireCurrent(fixture.budget(), owner));
        }
    }

    @Test
    void recheckScratchMemoryFailureRemainsTerminalAfterPressureRelief() {
        Fixture fixture = fixture();
        var selection = SelectionResolver.resolve(fixture.dataSet, false);
        var ledger = AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner()) {
            var witness = ManagedSelectionWitness.capture(fixture.dataSet, selection,
                    ProjectionRegistry.getProjection(), source(selection), fixture.budget(), owner);
            var pressure = owner.reserve(ledger.limitBytes() - ledger.currentBytes() - 1);
            var failure = assertThrows(ResourceLimitException.class,
                    () -> witness.requireCurrent(fixture.budget(), owner));
            pressure.close();
            assertSame(failure, assertThrows(ResourceLimitException.class,
                    () -> witness.requireCurrent(fixture.budget(), owner)));
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void frozenOccurrenceValuesDoNotReadMovedOrRetaggedNodesAfterLock() {
        Fixture fixture = fixture();
        fixture.middle.put("name", "before");
        SelectionContext selection = SelectionResolver.resolve(fixture.dataSet, false);
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            ManagedSelectionWitness witness = ManagedSelectionWitness.capture(fixture.dataSet,
                    selection, ProjectionRegistry.getProjection(), source(selection),
                    fixture.budget(), owner);
            var fact = witness.frozenSelectedFacts().get(1);
            assertTrue(fact.protectedOccurrence());
            assertEquals("before", fact.tags().get("name"));
            LatLon original = fact.coordinate();
            fixture.middle.setCoor(new LatLon(0.0, 0.008));
            fixture.middle.put("name", "after");
            assertEquals(original, fact.coordinate());
            assertEquals("before", witness.frozenSelectedFacts().get(1).tags().get("name"));
            assertThrows(IllegalStateException.class,
                    () -> witness.requireCurrent(fixture.budget(), owner));
        }
    }

    @Test
    void recheckRejectsASeparateMemoryAttempt() {
        Fixture fixture = fixture();
        SelectionContext selection = SelectionResolver.resolve(fixture.dataSet, false);
        var otherLedger = AttemptMemoryLedger.production();
        try (var owner = AttemptMemoryLedger.production().rootOwner();
                var foreign = otherLedger.rootOwner()) {
            ManagedSelectionWitness witness = ManagedSelectionWitness.capture(fixture.dataSet,
                    selection, ProjectionRegistry.getProjection(), source(selection),
                    fixture.budget(), owner);
            assertThrows(IllegalArgumentException.class,
                    () -> witness.requireCurrent(fixture.budget(), foreign));
            assertThrows(IllegalArgumentException.class,
                    () -> witness.requireCurrent(budget(), owner), "A fresh work budget is foreign even on the same memory ledger");
            assertEquals(0L, otherLedger.currentBytes());
            witness.requireCurrent(fixture.budget(), owner);
        }
    }

    private static Fixture fixture() {
        DataSet dataSet = new DataSet();
        Node before = node(0.0), middle = node(0.001), after = node(0.002);
        for (Node node : List.of(before, middle, after)) dataSet.addPrimitive(node);
        Way selected = way(before, middle, after);
        dataSet.addPrimitive(selected);
        dataSet.setSelected(List.of(selected));
        return new Fixture(dataSet, before, middle, budget());
    }

    private record Fixture(DataSet dataSet, Node before, Node middle, EmissionWorkBudget budget) { }

    private static Node node(double lon) { return new Node(new LatLon(0, lon)); }

    private static Way way(Node... nodes) {
        Way way = new Way();
        way.setNodes(List.of(nodes));
        return way;
    }

    private static Relation relation(Node... nodes) {
        Relation relation = new Relation();
        relation.setMembers(java.util.Arrays.stream(nodes)
                .map(node -> new RelationMember("via", node)).toList());
        return relation;
    }

    private static List<EastNorth> source(SelectionContext selection) {
        return selection.segmentNodes().stream()
                .map(node -> node.getEastNorth(ProjectionRegistry.getProjection())).toList();
    }

    private static EmissionWorkBudget budget() {
        return new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
    }
}
