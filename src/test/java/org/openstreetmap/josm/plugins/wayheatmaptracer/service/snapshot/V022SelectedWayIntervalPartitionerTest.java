package org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedRelation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedRelationMember;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ExternalPort;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole;

class V022SelectedWayIntervalPartitionerTest {
    private static final LocalMetricFrame FRAME = LocalMetricFrame.certifiedEquirectangular(
            new GeographicPoint(0, 0), new GeographicPoint(-0.02, -0.02),
            new GeographicPoint(0.02, 0.02));

    @Test
    void twoSeparatedManualJunctionsOwnExactOrderedIslandsAndSlideSpans() {
        Fixture fixture = pathWithJunctions(20, 4, 15, false);

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertEquals(List.of(new OccurrenceRange(2, 6), new OccurrenceRange(13, 17)),
                result.fixedIslands().stream().map(SelectedWayIntervalPartitioner.FixedIsland::range).toList());
        assertEquals(List.of(new OccurrenceRange(0, 1), new OccurrenceRange(7, 12),
                new OccurrenceRange(18, 20)),
                result.slideIntervals().stream().map(SelectedWayIntervalPartitioner.SlideInterval::range).toList());
        assertEquals(List.of(new OccurrenceRange(0, 2), new OccurrenceRange(6, 13),
                new OccurrenceRange(17, 20)), result.slideIntervals().stream()
                        .map(SelectedWayIntervalPartitioner.SlideInterval::traceRange).toList());
        assertEquals(List.of(4, 15), result.junctionDispositions().stream()
                .map(SelectedWayIntervalPartitioner.JunctionDisposition::selectedOccurrenceIndex).toList());
        assertTrue(result.fixedIslands().stream().allMatch(island -> island.occurrenceKeys().size()
                == island.range().size()));
        assertEquals(21, result.fixedIslands().stream().mapToInt(i -> i.range().size()).sum()
                + result.slideIntervals().stream().mapToInt(i -> i.range().size()).sum());
    }

    @Test
    void overlappingManualFootprintsAreUnionedWithoutMovableSliver() {
        Fixture fixture = pathWithJunctions(15, 5, 9, false);

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertEquals(List.of(new OccurrenceRange(3, 11)),
                result.fixedIslands().stream().map(SelectedWayIntervalPartitioner.FixedIsland::range).toList());
        assertEquals(List.of(new OccurrenceRange(0, 2), new OccurrenceRange(12, 15)),
                result.slideIntervals().stream().map(SelectedWayIntervalPartitioner.SlideInterval::range).toList());
        assertEquals(1, result.fixedIslands().size());
    }

    @Test
    void oneOwnedOccurrenceBetweenFixedIslandsHasBothReadOnlyTraceAnchors() {
        Fixture fixture = pathWithJunctions(16, 5, 11, false);

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertEquals(List.of(new OccurrenceRange(3, 7), new OccurrenceRange(9, 13)),
                result.fixedIslands().stream().map(SelectedWayIntervalPartitioner.FixedIsland::range).toList());
        assertEquals(new OccurrenceRange(8, 8), result.slideIntervals().get(1).range());
        assertEquals(new OccurrenceRange(7, 9), result.slideIntervals().get(1).traceRange());
        assertTrue(result.slideIntervals().get(1).traceRange().size() >= 2);
    }

    @Test
    void manualIslandAtSelectionEndpointKeepsOnlyTheOutsideSpanTraceable() {
        Fixture fixture = pathWithJunctions(10, 2, -1, false);

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertEquals(new OccurrenceRange(0, 4), result.fixedIslands().get(0).range());
        assertEquals(new OccurrenceRange(5, 10), result.slideIntervals().get(0).range());
        assertEquals(new OccurrenceRange(4, 10), result.slideIntervals().get(0).traceRange());
    }

    @Test
    void slideIntervalRejectsMalformedOrSinglePointTraceBounds() {
        var boundary = new SelectedWayIntervalPartitioner.BoundaryConstraint(
                SelectedWayIntervalPartitioner.BoundaryKind.FIXED_ISLAND, 2, nodeKey(1), false,
                false, ManualJunctionEligibility.Reason.SELECTED_INTERIOR);

        assertThrows(IllegalArgumentException.class, () ->
                new SelectedWayIntervalPartitioner.SlideInterval(new OccurrenceRange(2, 2),
                        List.of(nodeKey(1)), boundary, boundary));
    }

    @Test
    void taggedNodeAndRelationMemberWayAreManualAndPreserved() {
        Fixture tagged = pathWithJunctions(12, 5, -1, true)
                .withSelectedPort(3, 2, ExternalPort.Side.BEFORE).selectRange(5, 12);
        Fixture relation = pathWithJunctions(12, 5, -1, false).withParticipatingRelation()
                .withSelectedPort(3, 2, ExternalPort.Side.BEFORE).selectRange(5, 12);

        var taggedResult = partition(tagged);
        var relationResult = partition(relation);

        assertEquals(ManualJunctionEligibility.Reason.AFFECTED_NODE_TAGGED,
                taggedResult.junctionDispositions().get(0).reason());
        assertEquals(ManualJunctionEligibility.Reason.PARTICIPATING_RELATION,
                relationResult.junctionDispositions().get(0).reason());
        assertEquals(new OccurrenceRange(5, 7), taggedResult.fixedIslands().get(0).range());
        assertEquals(new OccurrenceRange(5, 7), relationResult.fixedIslands().get(0).range());
    }

    @Test
    void unprovedReferrerFreezesUncertainSelectionAndHasTypedReason() {
        Fixture fixture = pathWithJunctions(12, 5, -1, true).withUnmaterializedReferrerAt(5);

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertTrue(result.slideIntervals().isEmpty());
        assertEquals(List.of(new OccurrenceRange(0, 12)),
                result.fixedIslands().stream().map(SelectedWayIntervalPartitioner.FixedIsland::range).toList());
        assertEquals(ManualJunctionEligibility.Reason.INCOMPLETE_CLOSURE,
                result.junctionDispositions().get(0).reason());
    }

    @Test
    void completeSelectedOccurrencesProvideExactInternalCutsWithoutInventedPorts() {
        Fixture fixture = pathWithJunctions(12, 5, -1, true).withoutSelectedArmPorts();

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertEquals(List.of(new OccurrenceRange(3, 7)),
                result.fixedIslands().stream().map(SelectedWayIntervalPartitioner.FixedIsland::range).toList());
        assertEquals(List.of(new OccurrenceRange(0, 2), new OccurrenceRange(8, 12)),
                result.slideIntervals().stream().map(SelectedWayIntervalPartitioner.SlideInterval::range)
                        .toList());
        assertTrue(SelectedWayIntervalPartitioner.verifyFrozenParity(result, fixture.snapshot()));
        assertFalse(SelectedWayIntervalPartitioner.verifyFrozenParity(result,
                fixture.withSelectedNodeAt(3, 46.0, 0.0).snapshot()));
        assertEquals(ManualJunctionEligibility.Reason.SELECTED_INTERIOR,
                result.junctionDispositions().get(0).reason());
    }

    @Test
    void internalCutCannotCrossAnUnprovedLongSelectedSegment() {
        Fixture fixture = pathWithJunctions(12, 5, -1, true).withoutSelectedArmPorts()
                .withSelectedNodeAt(4, 100.0, 0.0);

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertTrue(result.slideIntervals().isEmpty());
        assertEquals(List.of(new OccurrenceRange(0, 12)),
                result.fixedIslands().stream().map(SelectedWayIntervalPartitioner.FixedIsland::range)
                        .toList());
        assertEquals(ManualJunctionEligibility.Reason.INCOMPLETE_CLOSURE,
                result.junctionDispositions().get(0).reason());
    }

    @Test
    void selectionBoundaryStillRequiresProofOfThePhysicalOutsideArm() {
        Fixture fixture = pathWithJunctions(12, 5, -1, false).selectRange(5, 12);

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertTrue(result.slideIntervals().isEmpty());
        assertEquals(List.of(new OccurrenceRange(5, 12)),
                result.fixedIslands().stream().map(SelectedWayIntervalPartitioner.FixedIsland::range).toList());
        assertEquals(ManualJunctionEligibility.Reason.INCOMPLETE_CLOSURE,
                result.junctionDispositions().get(0).reason());
    }

    @Test
    void unmaterializedReferrerJustOutsideSelectionFreezesIt() {
        Fixture fixture = pathWithJunctions(12, 5, -1, false)
                .withUnmaterializedReferrerAt(4).selectRange(5, 12);

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertTrue(result.slideIntervals().isEmpty());
        assertEquals(List.of(new OccurrenceRange(5, 12)),
                result.fixedIslands().stream().map(SelectedWayIntervalPartitioner.FixedIsland::range).toList());
        assertEquals(ManualJunctionEligibility.Reason.INCOMPLETE_CLOSURE,
                result.junctionDispositions().get(0).reason());
    }

    @Test
    void taggedNodeInProvedOutsideArmMakesSubrangeEndpointManual() {
        Fixture fixture = pathWithJunctions(12, 5, -1, false)
                .withSelectedPort(3, 2, ExternalPort.Side.BEFORE)
                .withTaggedNodeAt(4).selectRange(5, 12);
        assertEquals(ManualJunctionEligibility.Reason.SIMPLE_T,
                ManualJunctionEligibility.evaluateOccurrence(fixture.snapshot, fixture.specification, 5).reason());

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertEquals(ManualJunctionEligibility.Reason.AFFECTED_NODE_TAGGED,
                result.junctionDispositions().get(0).reason());
        assertFalse(result.junctionDispositions().get(0).automaticEligible());
        assertEquals(List.of(new OccurrenceRange(5, 7)),
                result.fixedIslands().stream().map(SelectedWayIntervalPartitioner.FixedIsland::range).toList());
    }

    @Test
    void relationReferencedNodeInProvedOutsideArmMakesSubrangeEndpointManual() {
        Fixture fixture = pathWithJunctions(12, 5, -1, false)
                .withSelectedPort(3, 2, ExternalPort.Side.BEFORE)
                .withRelationReferrerAt(4).selectRange(5, 12);
        assertEquals(ManualJunctionEligibility.Reason.SIMPLE_T,
                ManualJunctionEligibility.evaluateOccurrence(fixture.snapshot, fixture.specification, 5).reason());

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertEquals(ManualJunctionEligibility.Reason.AFFECTED_NODE_RELATION,
                result.junctionDispositions().get(0).reason());
        assertFalse(result.junctionDispositions().get(0).automaticEligible());
        assertEquals(List.of(new OccurrenceRange(5, 7)),
                result.fixedIslands().stream().map(SelectedWayIntervalPartitioner.FixedIsland::range).toList());
    }

    @Test
    void laterUnprovedEndpointFreezesEarlierEligibleTWithoutDuplicateDispositions() {
        Fixture fixture = endpointTJunctions(new double[] {0, 40, 80, 120}, Set.of(0, 3),
                List.of(port(1, 2, ExternalPort.Side.AFTER))).withReceiverArmLengthAt(3, 100);
        assertEquals(ManualJunctionEligibility.Reason.SIMPLE_T,
                ManualJunctionEligibility.evaluateOccurrence(fixture.snapshot, fixture.specification, 0).reason());

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertEquals(2, result.junctionDispositions().size());
        assertEquals(Set.of(0, 3), result.junctionDispositions().stream()
                .map(SelectedWayIntervalPartitioner.JunctionDisposition::selectedOccurrenceIndex)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(result.junctionDispositions().stream().noneMatch(
                SelectedWayIntervalPartitioner.JunctionDisposition::automaticEligible));
        assertTrue(result.slideIntervals().isEmpty());
        assertEquals(List.of(new OccurrenceRange(0, 3)),
                result.fixedIslands().stream().map(SelectedWayIntervalPartitioner.FixedIsland::range).toList());
    }

    @Test
    void classificationResourceLimitFreezesTheWholeSelection() {
        Fixture fixture = resourceLimitFixture();
        assertEquals(ManualJunctionEligibility.Reason.RESOURCE_LIMIT,
                ManualJunctionEligibility.evaluateOccurrence(fixture.snapshot, fixture.specification, 0).reason());

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertTrue(result.slideIntervals().isEmpty());
        assertEquals(List.of(new OccurrenceRange(0, 12)),
                result.fixedIslands().stream().map(SelectedWayIntervalPartitioner.FixedIsland::range).toList());
        assertEquals(List.of(ManualJunctionEligibility.Reason.RESOURCE_LIMIT,
                        ManualJunctionEligibility.Reason.RESOURCE_LIMIT),
                result.junctionDispositions().stream().map(
                        SelectedWayIntervalPartitioner.JunctionDisposition::reason).toList());
    }

    @Test
    void eligibleEndpointSimpleTRemainsMovableOnlyThroughJointBoundary() {
        Fixture fixture = endpointSimpleT();

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertEquals(ManualJunctionEligibility.Reason.SIMPLE_T,
                result.junctionDispositions().get(0).reason());
        assertTrue(result.fixedIslands().isEmpty());
        assertEquals(new OccurrenceRange(0, 1), result.slideIntervals().get(0).range());
        assertEquals(SelectedWayIntervalPartitioner.BoundaryKind.ELIGIBLE_SIMPLE_T,
                result.slideIntervals().get(0).startBoundary().kind());
        assertTrue(result.slideIntervals().get(0).startBoundary().requiresJointPlan());
        assertEquals(SelectedWayIntervalPartitioner.BoundaryKind.SELECTED_ENDPOINT,
                result.slideIntervals().get(0).endBoundary().kind());
        assertFalse(result.slideIntervals().get(0).endBoundary().mayMove());
    }

    @Test
    void overlappingEndpointSimpleTAffectedFootprintsAreBothManual() {
        Fixture fixture = endpointTJunctions(new double[] {0, 50, 100}, Set.of(0, 2),
                List.of(port(1, 0, ExternalPort.Side.BEFORE), port(1, 2, ExternalPort.Side.AFTER)));

        assertEquals(ManualJunctionEligibility.Reason.SIMPLE_T,
                ManualJunctionEligibility.evaluateOccurrence(fixture.snapshot, fixture.specification, 0).reason());
        assertEquals(ManualJunctionEligibility.Reason.SIMPLE_T,
                ManualJunctionEligibility.evaluateOccurrence(fixture.snapshot, fixture.specification, 2).reason());

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertEquals(2, result.junctionDispositions().size());
        assertTrue(result.junctionDispositions().stream().noneMatch(
                SelectedWayIntervalPartitioner.JunctionDisposition::automaticEligible));
        assertTrue(result.junctionDispositions().stream().allMatch(d ->
                d.reason() == ManualJunctionEligibility.Reason.COUPLED_JUNCTION));
        assertEquals(List.of(new OccurrenceRange(0, 2)),
                result.fixedIslands().stream().map(SelectedWayIntervalPartitioner.FixedIsland::range).toList());
    }

    @Test
    void fullSelectedRangeKeepsSupportedLongEndpointSimpleTEligible() {
        Fixture fixture = endpointTJunctions(new double[] {0, 15, 30, 45, 60}, Set.of(0),
                List.of(port(3, 4, ExternalPort.Side.AFTER)));

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertEquals(ManualJunctionEligibility.Reason.SIMPLE_T,
                result.junctionDispositions().get(0).reason());
        assertTrue(result.junctionDispositions().get(0).automaticEligible());
        assertTrue(result.fixedIslands().isEmpty());
    }

    @Test
    void distantDisjointEndpointSimpleTFootprintsRemainEligible() {
        Fixture fixture = endpointTJunctions(new double[] {0, 40, 80, 120, 160}, Set.of(0, 4),
                List.of(port(1, 2, ExternalPort.Side.AFTER),
                        port(3, 2, ExternalPort.Side.BEFORE)));

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertEquals(2, result.junctionDispositions().size());
        assertTrue(result.junctionDispositions().stream().allMatch(
                SelectedWayIntervalPartitioner.JunctionDisposition::automaticEligible));
        assertTrue(result.fixedIslands().isEmpty());
    }

    @Test
    void distantEndpointTSharingManualIslandsReceiverIsCoupledAndFrozen() {
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>();
        List<PrimitiveKey> selectedNodes = new ArrayList<>();
        for (int index = 0; index <= 6; index++) {
            PrimitiveKey key = nodeKey(100 + index);
            selectedNodes.add(key);
            values.put(key, node(key, index * 40.0, 0.0, Map.of()));
        }
        PrimitiveKey selected = wayKey(100);
        PrimitiveKey receiver = wayKey(200);
        PrimitiveKey north0 = nodeKey(1000);
        PrimitiveKey south0 = nodeKey(1001);
        PrimitiveKey north5 = nodeKey(1002);
        values.put(north0, node(north0, 0.0, 40.0, Map.of()));
        values.put(south0, node(south0, 0.0, -40.0, Map.of()));
        values.put(north5, node(north5, 200.0, 40.0, Map.of()));
        List<PrimitiveKey> receiverNodes = new ArrayList<>(List.of(north0,
                selectedNodes.get(0), south0));
        for (int index = 1; index <= 5; index++) {
            PrimitiveKey key = nodeKey(1100 + index);
            values.put(key, node(key, index * 40.0, -40.0, Map.of()));
            receiverNodes.add(key);
        }
        receiverNodes.add(selectedNodes.get(5));
        receiverNodes.add(north5);
        values.put(selected, way(selected, selectedNodes));
        values.put(receiver, way(receiver, receiverNodes));
        List<ExternalPort> ports = List.of(
                new ExternalPort(selected, selectedNodes.get(1), selectedNodes.get(2), 1,
                        ExternalPort.Side.AFTER,
                        ((DetachedNode) values.get(selectedNodes.get(2))).coordinate()),
                new ExternalPort(receiver, south0, receiverNodes.get(3), 2,
                        ExternalPort.Side.AFTER,
                        ((DetachedNode) values.get(receiverNodes.get(3))).coordinate()),
                new ExternalPort(receiver, receiverNodes.get(7), receiverNodes.get(6), 7,
                        ExternalPort.Side.BEFORE,
                        ((DetachedNode) values.get(receiverNodes.get(6))).coordinate()));
        Fixture fixture = fixture(values, selected, selectedNodes, 6, Map.of(), ports);
        assertEquals(ManualJunctionEligibility.Reason.SIMPLE_T,
                ManualJunctionEligibility.evaluateOccurrence(
                        fixture.snapshot(), fixture.specification(), 0).reason());

        SelectedWayIntervalPartitioner.Partition partition = partition(fixture);

        assertEquals(ManualJunctionEligibility.Reason.COUPLED_JUNCTION,
                partition.junctionDispositions().get(0).reason());
        assertFalse(partition.junctionDispositions().get(0).automaticEligible());
        assertEquals(List.of(new OccurrenceRange(0, 1), new OccurrenceRange(4, 6)),
                partition.fixedIslands().stream().map(
                        SelectedWayIntervalPartitioner.FixedIsland::range).toList());
        assertEquals(List.of(new OccurrenceRange(2, 3)),
                partition.slideIntervals().stream().map(
                        SelectedWayIntervalPartitioner.SlideInterval::range).toList());
    }

    @Test
    void endpointSimpleTOverlappingManualInteriorFootprintIsDemoted() {
        Fixture fixture = endpointTJunctions(new double[] {0, 50, 100, 150}, Set.of(0, 2),
                List.of(port(1, 2, ExternalPort.Side.AFTER),
                        port(1, 0, ExternalPort.Side.BEFORE)));

        assertEquals(ManualJunctionEligibility.Reason.SIMPLE_T,
                ManualJunctionEligibility.evaluateOccurrence(fixture.snapshot, fixture.specification, 0).reason());
        assertEquals(ManualJunctionEligibility.Reason.SELECTED_INTERIOR,
                ManualJunctionEligibility.evaluateOccurrence(fixture.snapshot, fixture.specification, 2).reason());

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertTrue(result.junctionDispositions().stream().noneMatch(
                SelectedWayIntervalPartitioner.JunctionDisposition::automaticEligible));
        assertTrue(result.junctionDispositions().stream().anyMatch(d ->
                d.selectedOccurrenceIndex() == 0
                        && d.reason() == ManualJunctionEligibility.Reason.COUPLED_JUNCTION));
        assertTrue(result.junctionDispositions().stream().anyMatch(d ->
                d.selectedOccurrenceIndex() == 2
                        && d.reason() == ManualJunctionEligibility.Reason.SELECTED_INTERIOR),
                result.junctionDispositions().toString());
    }

    @Test
    void laterUnprovedEndpointDoesNotLeaveDuplicateOrAutomaticJunctionDispositions() {
        Fixture fixture = endpointTJunctions(new double[] {0, 50, 100}, Set.of(0, 2),
                List.of(port(1, 2, ExternalPort.Side.AFTER)));

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertEquals(2, result.junctionDispositions().size());
        assertEquals(Set.of(0, 2), result.junctionDispositions().stream()
                .map(SelectedWayIntervalPartitioner.JunctionDisposition::selectedOccurrenceIndex)
                .collect(java.util.stream.Collectors.toSet()));
        assertTrue(result.junctionDispositions().stream().noneMatch(
                SelectedWayIntervalPartitioner.JunctionDisposition::automaticEligible));
        assertTrue(result.slideIntervals().isEmpty());
        assertEquals(List.of(new OccurrenceRange(0, 2)),
                result.fixedIslands().stream().map(SelectedWayIntervalPartitioner.FixedIsland::range).toList());
    }

    @Test
    void provedExternalPortSurvivesFreezeAndChangedPortOrReferrerPayloadFailsParity() {
        Fixture fixture = endpointSimpleT().withLongReceiverAndPort();
        SelectedWayIntervalPartitioner.Partition partition = partition(fixture);

        assertTrue(partition.provedPorts().contains(fixture.port));
        assertTrue(SelectedWayIntervalPartitioner.verifyFrozenParity(partition, fixture.snapshot));
        assertFalse(SelectedWayIntervalPartitioner.verifyFrozenParity(partition,
                fixture.withChangedPort().snapshot));
        assertFalse(SelectedWayIntervalPartitioner.verifyFrozenParity(partition,
                fixture.withChangedReferrerPayload().snapshot));
        assertFalse(SelectedWayIntervalPartitioner.verifyFrozenParity(partition,
                fixture.withChangedReferrerIdentity().snapshot));
    }

    @Test
    void repeatedOccurrenceIsTypedAndCannotCreateSlideRanges() {
        Fixture fixture = pathWithJunctions(12, 5, -1, true).withRepeatedSelectedOccurrence();

        SelectedWayIntervalPartitioner.Partition result = partition(fixture);

        assertTrue(result.slideIntervals().isEmpty());
        assertEquals(ManualJunctionEligibility.Reason.REPEATED_OCCURRENCE,
                result.junctionDispositions().get(0).reason());
    }

    private static SelectedWayIntervalPartitioner.Partition partition(Fixture fixture) {
        return SelectedWayIntervalPartitioner.partition(fixture.snapshot, fixture.specification);
    }

    private static Fixture pathWithJunctions(int count, int first, int second, boolean tagged) {
        List<PrimitiveKey> selectedNodes = new ArrayList<>();
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>();
        for (int index = 0; index <= count; index++) {
            PrimitiveKey key = nodeKey(index + 1);
            selectedNodes.add(key);
            Map<String, String> tags = tagged && index == first ? Map.of("barrier", "gate") : Map.of();
            values.put(key, node(key, index * 15.0, 0.0, tags));
        }
        PrimitiveKey selected = wayKey(100);
        values.put(selected, way(selected, selectedNodes));
        for (int index : second < 0 ? List.of(first) : List.of(first, second)) {
            PrimitiveKey armA = nodeKey(1000 + index * 10L);
            PrimitiveKey armB = nodeKey(1001 + index * 10L);
            PrimitiveKey receiver = wayKey(200 + index);
            values.put(armA, node(armA, index * 15.0, 40.0, Map.of()));
            values.put(armB, node(armB, index * 15.0, -40.0, Map.of()));
            values.put(receiver, way(receiver, List.of(armA, selectedNodes.get(index), armB)));
        }
        return fixture(values, selected, selectedNodes, count, Map.of(), List.of());
    }

    private static Fixture endpointSimpleT() {
        PrimitiveKey junction = nodeKey(1);
        PrimitiveKey endpoint = nodeKey(2);
        PrimitiveKey west = nodeKey(3);
        PrimitiveKey east = nodeKey(4);
        PrimitiveKey selected = wayKey(10);
        PrimitiveKey receiver = wayKey(11);
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>();
        values.put(junction, node(junction, 0, 0, Map.of()));
        values.put(endpoint, node(endpoint, 40, 0, Map.of()));
        values.put(west, node(west, 0, -40, Map.of()));
        values.put(east, node(east, 0, 40, Map.of()));
        values.put(selected, way(selected, List.of(junction, endpoint)));
        values.put(receiver, way(receiver, List.of(west, junction, east)));
        return fixture(values, selected, List.of(junction, endpoint), 1, Map.of(), List.of());
    }

    private static Fixture endpointTJunctions(double[] positions, Set<Integer> junctionIndices,
            List<ExternalPort> ports) {
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>();
        List<PrimitiveKey> selectedNodes = new ArrayList<>();
        for (int index = 0; index < positions.length; index++) {
            PrimitiveKey key = nodeKey(100 + index);
            selectedNodes.add(key);
            values.put(key, node(key, positions[index], 0, Map.of()));
        }
        PrimitiveKey selected = wayKey(100);
        values.put(selected, way(selected, selectedNodes));
        for (int index : junctionIndices) {
            PrimitiveKey north = nodeKey(1000 + index * 10L);
            PrimitiveKey south = nodeKey(1001 + index * 10L);
            PrimitiveKey receiver = wayKey(200 + index);
            values.put(north, node(north, positions[index], 40, Map.of()));
            values.put(south, node(south, positions[index], -40, Map.of()));
            values.put(receiver, way(receiver, List.of(north, selectedNodes.get(index), south)));
        }
        Map<PrimitiveKey, Set<PrimitiveKey>> watches = new LinkedHashMap<>();
        values.keySet().forEach(key -> watches.put(key, new LinkedHashSet<>()));
        values.values().forEach(primitive -> {
            if (primitive instanceof DetachedWay way) way.nodeKeys().forEach(node -> watches.get(node).add(way.key()));
            if (primitive instanceof DetachedRelation relation) relation.members().forEach(member ->
                    watches.get(member.memberKey()).add(relation.key()));
        });
        List<ExternalPort> exactPorts = ports.stream().map(port -> new ExternalPort(selected,
                selectedNodes.get(port.boundaryOccurrenceIndex()), port.outsideNeighborKey(),
                port.boundaryOccurrenceIndex(), port.side(),
                ((DetachedNode) values.get(port.outsideNeighborKey())).coordinate())).toList();
        return fixture(values, selected, selectedNodes, selectedNodes.size() - 1, Map.of(), exactPorts);
    }

    private static Fixture resourceLimitFixture() {
        PrimitiveKey selectedKey = wayKey(301);
        PrimitiveKey receiverKey = wayKey(302);
        PrimitiveKey decoyKey = wayKey(303);
        PrimitiveKey junction = nodeKey(3000);
        PrimitiveKey secondJunction = nodeKey(3008);
        List<PrimitiveKey> selectedNodes = new ArrayList<>();
        List<PrimitiveKey> receiverNodes = new ArrayList<>();
        List<PrimitiveKey> decoyNodes = new ArrayList<>();
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>();
        for (int index = 0; index <= 12; index++) {
            PrimitiveKey key = index == 0 ? junction : index == 8 ? secondJunction : nodeKey(3000 + index);
            selectedNodes.add(key);
            values.put(key, node(key, index * 15.0, 0, Map.of()));
        }
        values.put(selectedKey, way(selectedKey, selectedNodes));
        for (int index = 0; index <= 1000; index++) {
            PrimitiveKey receiverNode = nodeKey(4000 + index);
            receiverNodes.add(receiverNode);
            values.put(receiverNode, node(receiverNode, 0, (index - 500) * 0.05, Map.of()));
            PrimitiveKey decoyNode = nodeKey(6000 + index);
            decoyNodes.add(decoyNode);
            values.put(decoyNode, node(decoyNode, 100 + index * 0.05, 100, Map.of()));
        }
        receiverNodes.set(500, junction);
        values.remove(nodeKey(4500));
        values.put(receiverKey, way(receiverKey, receiverNodes));
        values.put(decoyKey, way(decoyKey, decoyNodes));
        PrimitiveKey north = nodeKey(8000);
        PrimitiveKey south = nodeKey(8001);
        PrimitiveKey secondReceiver = wayKey(304);
        values.put(north, node(north, 120, 40, Map.of()));
        values.put(south, node(south, 120, -40, Map.of()));
        values.put(secondReceiver, way(secondReceiver, List.of(north, secondJunction, south)));
        List<ExternalPort> ports = List.of(
                new ExternalPort(selectedKey, selectedNodes.get(2), selectedNodes.get(3), 2,
                        ExternalPort.Side.AFTER, ((DetachedNode) values.get(selectedNodes.get(3))).coordinate()),
                new ExternalPort(selectedKey, selectedNodes.get(6), selectedNodes.get(5), 6,
                        ExternalPort.Side.BEFORE, ((DetachedNode) values.get(selectedNodes.get(5))).coordinate()),
                new ExternalPort(selectedKey, selectedNodes.get(10), selectedNodes.get(11), 10,
                        ExternalPort.Side.AFTER, ((DetachedNode) values.get(selectedNodes.get(11))).coordinate()));
        return fixture(values, selectedKey, selectedNodes, 12, Map.of(), ports);
    }

    private static ExternalPort port(int boundaryIndex, int outsideIndex, ExternalPort.Side side) {
        return new ExternalPort(wayKey(100), nodeKey(100 + boundaryIndex), nodeKey(100 + outsideIndex),
                boundaryIndex, side, new GeographicPoint(0, 0));
    }

    private static Fixture fixture(Map<PrimitiveKey, DetachedPrimitive> input, PrimitiveKey selected,
            List<PrimitiveKey> selectedNodes, int last, Map<PrimitiveKey, Set<PrimitiveKey>> watchOverrides,
            List<ExternalPort> ports) {
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>(input);
        Map<PrimitiveKey, Set<PrimitiveKey>> watches = new LinkedHashMap<>();
        values.keySet().forEach(key -> watches.put(key, new LinkedHashSet<>()));
        values.values().forEach(primitive -> {
            if (primitive instanceof DetachedWay way) way.nodeKeys().forEach(node -> watches.get(node).add(way.key()));
            if (primitive instanceof DetachedRelation relation) relation.members().forEach(member ->
                    watches.get(member.memberKey()).add(relation.key()));
        });
        watchOverrides.forEach((key, refs) -> watches.put(key, new LinkedHashSet<>(refs)));
        Set<PrimitiveKey> primitiveKeys = Set.copyOf(values.keySet());
        Map<PrimitiveKey, List<OccurrenceRange>> ranges = new LinkedHashMap<>();
        values.values().stream().filter(DetachedWay.class::isInstance).map(DetachedWay.class::cast)
                .forEach(way -> ranges.put(way.key(), List.of(new OccurrenceRange(0, way.nodeKeys().size() - 1))));
        Set<PrimitiveKey> nodeKeys = values.keySet().stream().filter(key -> key.type() == PrimitiveKey.Type.NODE)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<PrimitiveKey> junctionKeys = values.values().stream().filter(DetachedWay.class::isInstance)
                .map(DetachedWay.class::cast).flatMap(way -> way.nodeKeys().stream())
                .filter(node -> watches.get(node).stream().filter(key -> key.type() == PrimitiveKey.Type.WAY)
                        .count() > 1)
                .collect(java.util.stream.Collectors.toSet());
        Set<PrimitiveKey> protectedNodes = new LinkedHashSet<>(nodeKeys);
        protectedNodes.removeAll(junctionKeys);
        Set<PrimitiveKey> movableNodes = new LinkedHashSet<>(nodeKeys);
        movableNodes.removeAll(protectedNodes);
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.EDIT_COMPONENT,
                "fixture-v1", primitiveKeys, primitiveKeys, movableNodes, protectedNodes, Set.of(), ranges,
                ports, MetricRegion.rectangle(-500, -500, 500, 500),
                MetricRegion.rectangle(-500, -500, 500, 500), true, true, true, true);
        NetworkSnapshot snapshot = new NetworkSnapshot("authority", SnapshotRole.CAPTURED_BEFORE,
                "dataset", 1, closure, values, watches);
        var specification = new NetworkSnapshotCapture.Specification("authority", "dataset", 1,
                selected, new OccurrenceRange(0, last), FRAME,
                MetricRegion.rectangle(-500, -500, 500, 500),
                MetricRegion.rectangle(-500, -500, 500, 500), ranges, primitiveKeys,
                movableNodes, Set.of(), protectedNodes, true,
                new RecoveryPermissions(false, 7.01, 7.01, JunctionPolicy.REATTACH, true));
        return new Fixture(snapshot, specification, selected, selectedNodes, ports.isEmpty() ? null : ports.get(0));
    }

    private static PrimitiveKey nodeKey(long id) {
        return new PrimitiveKey(PrimitiveKey.Type.NODE, PrimitiveKey.IdentityKind.OSM_UNIQUE, id);
    }

    private static PrimitiveKey wayKey(long id) {
        return new PrimitiveKey(PrimitiveKey.Type.WAY, PrimitiveKey.IdentityKind.OSM_UNIQUE, id);
    }

    private static DetachedNode node(PrimitiveKey key, double x, double y, Map<String, String> tags) {
        return new DetachedNode(key, new GeographicPoint(y / 111_000.0, x / 111_000.0), tags, false, false);
    }

    private static DetachedWay way(PrimitiveKey key, List<PrimitiveKey> nodes) {
        return new DetachedWay(key, nodes, Map.of(), false, false);
    }

    private record Fixture(NetworkSnapshot snapshot, NetworkSnapshotCapture.Specification specification,
            PrimitiveKey selected, List<PrimitiveKey> selectedNodes, ExternalPort port) {
        Fixture withParticipatingRelation() {
            Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>(snapshot.primitives());
            PrimitiveKey relationKey = new PrimitiveKey(PrimitiveKey.Type.RELATION,
                    PrimitiveKey.IdentityKind.OSM_UNIQUE, 9000);
            PrimitiveKey receiver = snapshot.closure().editableWayOccurrences().keySet().stream()
                    .filter(key -> !key.equals(selected)).findFirst().orElseThrow();
            values.put(relationKey, new DetachedRelation(relationKey,
                    List.of(new DetachedRelationMember(receiver, "route")), Map.of(), false, false));
            return rebuild(values, Map.of());
        }
        Fixture selectRange(int first, int last) {
            var spec = new NetworkSnapshotCapture.Specification("authority", "dataset", 1,
                    selected, new OccurrenceRange(first, last), FRAME,
                    snapshot.closure().collisionEnvelope(), snapshot.closure().editRegion(),
                    snapshot.closure().editableWayOccurrences(), snapshot.closure().editableExistingKeys(),
                    snapshot.closure().movableExistingNodeKeys(), snapshot.closure().removableExistingNodeKeys(),
                    snapshot.closure().protectedExistingNodeKeys(), true,
                    new RecoveryPermissions(false, 7.01, 7.01, JunctionPolicy.REATTACH, true));
            return new Fixture(snapshot, spec, selected, selectedNodes, port);
        }
        Fixture withUnmaterializedReferrerAt(int index) {
            PrimitiveKey node = selectedNodes.get(index);
            PrimitiveKey ghostWay = wayKey(99999);
            Set<PrimitiveKey> refs = new LinkedHashSet<>(snapshot.incomingReferrerWatches().get(node));
            refs.add(ghostWay);
            return rebuild(snapshot.primitives(), Map.of(node, refs));
        }
        Fixture withRepeatedSelectedOccurrence() {
            Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>(snapshot.primitives());
            DetachedWay source = (DetachedWay) values.get(selected);
            List<PrimitiveKey> repeated = new ArrayList<>(source.nodeKeys());
            repeated.set(8, repeated.get(7));
            values.put(selected, way(selected, repeated));
            return rebuild(values, Map.of());
        }
        Fixture withReceiverArmLengthAt(int selectedOccurrence, double length) {
            PrimitiveKey junction = selectedNodes.get(selectedOccurrence);
            PrimitiveKey receiverKey = snapshot.incomingReferrerWatches().get(junction).stream()
                    .filter(key -> key.type() == PrimitiveKey.Type.WAY && !key.equals(selected))
                    .findFirst().orElseThrow();
            DetachedWay receiver = (DetachedWay) snapshot.primitives().get(receiverKey);
            Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>(snapshot.primitives());
            PrimitiveKey first = receiver.nodeKeys().get(0);
            PrimitiveKey last = receiver.nodeKeys().get(receiver.nodeKeys().size() - 1);
            DetachedNode oldFirst = (DetachedNode) values.get(first);
            DetachedNode oldLast = (DetachedNode) values.get(last);
            values.put(first, node(first, oldFirst.coordinate().longitudeDegrees() * 111_000.0,
                    length, Map.of()));
            values.put(last, node(last, oldLast.coordinate().longitudeDegrees() * 111_000.0,
                    -length, Map.of()));
            return rebuild(values, Map.of());
        }
        Fixture withSelectedNodeAt(int index, double x, double y) {
            Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>(snapshot.primitives());
            PrimitiveKey key = selectedNodes.get(index);
            values.put(key, node(key, x, y, Map.of()));
            return rebuild(values, Map.of(), snapshot.closure().externalPorts());
        }
        Fixture withSelectedPort(int boundaryIndex, int outsideIndex, ExternalPort.Side side) {
            DetachedWay selectedWay = (DetachedWay) snapshot.primitives().get(selected);
            PrimitiveKey boundary = selectedWay.nodeKeys().get(boundaryIndex);
            PrimitiveKey outside = selectedWay.nodeKeys().get(outsideIndex);
            ExternalPort port = new ExternalPort(selected, boundary, outside, boundaryIndex, side,
                    ((DetachedNode) snapshot.primitives().get(outside)).coordinate());
            return rebuild(snapshot.primitives(), Map.of(), List.of(port));
        }
        Fixture withTaggedNodeAt(int index) {
            Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>(snapshot.primitives());
            PrimitiveKey nodeKey = selectedNodes.get(index);
            DetachedNode old = (DetachedNode) values.get(nodeKey);
            values.put(nodeKey, new DetachedNode(nodeKey, old.coordinate(), Map.of("barrier", "gate"),
                    old.deleted(), old.modified()));
            return rebuild(values, Map.of(), snapshot.closure().externalPorts());
        }
        Fixture withRelationReferrerAt(int index) {
            Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>(snapshot.primitives());
            PrimitiveKey relationKey = new PrimitiveKey(PrimitiveKey.Type.RELATION,
                    PrimitiveKey.IdentityKind.OSM_UNIQUE, 9100);
            values.put(relationKey, new DetachedRelation(relationKey,
                    List.of(new DetachedRelationMember(selectedNodes.get(index), "label")), Map.of(), false, false));
            return rebuild(values, Map.of(), snapshot.closure().externalPorts());
        }
        Fixture withLongReceiverAndPort() {
            PrimitiveKey receiver = snapshot.closure().editableWayOccurrences().keySet().stream()
                    .filter(key -> !key.equals(selected)).findFirst().orElseThrow();
            DetachedWay original = (DetachedWay) snapshot.primitives().get(receiver);
            PrimitiveKey boundary = original.nodeKeys().get(0);
            PrimitiveKey outside = nodeKey(88);
            Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>(snapshot.primitives());
            values.put(outside, node(outside, -2000, 0, Map.of()));
            List<PrimitiveKey> sequence = new ArrayList<>(original.nodeKeys());
            sequence.add(0, outside);
            values.put(receiver, way(receiver, sequence));
            ExternalPort externalPort = new ExternalPort(receiver, boundary, outside, 1,
                    ExternalPort.Side.BEFORE, ((DetachedNode) values.get(outside)).coordinate());
            return rebuild(values, Map.of(), List.of(externalPort));
        }
        Fixture withChangedPort() {
            if (port == null) return withLongReceiverAndPort().withChangedPort();
            Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>(snapshot.primitives());
            values.put(port.outsideNeighborKey(), node(port.outsideNeighborKey(), -1990, 0, Map.of()));
            ExternalPort changed = new ExternalPort(port.wayKey(), port.boundaryNodeKey(),
                    port.outsideNeighborKey(), port.boundaryOccurrenceIndex(), port.side(),
                    ((DetachedNode) values.get(port.outsideNeighborKey())).coordinate());
            return rebuild(values, Map.of(), List.of(changed));
        }
        Fixture withChangedReferrerPayload() {
            PrimitiveKey receiver = snapshot.closure().editableWayOccurrences().keySet().stream()
                    .filter(key -> !key.equals(selected)).findFirst().orElseThrow();
            DetachedWay current = (DetachedWay) snapshot.primitives().get(receiver);
            Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>(snapshot.primitives());
            values.put(receiver, new DetachedWay(receiver, current.nodeKeys(), Map.of("name", "changed"),
                    false, current.modified()));
            return rebuild(values, Map.of());
        }
        Fixture withChangedReferrerIdentity() {
            PrimitiveKey node = selectedNodes.get(0);
            Set<PrimitiveKey> changed = new LinkedHashSet<>(snapshot.incomingReferrerWatches().get(node));
            changed.add(wayKey(77777));
            return rebuild(snapshot.primitives(), Map.of(node, changed));
        }
        Fixture withoutSelectedArmPorts() {
            Set<PrimitiveKey> nodes = snapshot.primitives().keySet().stream()
                    .filter(key -> key.type() == PrimitiveKey.Type.NODE)
                    .collect(java.util.stream.Collectors.toSet());
            ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.EDIT_COMPONENT,
                    "fixture-no-port", snapshot.closure().primitiveKeys(), snapshot.closure().editableExistingKeys(),
                    nodes, Set.of(), Set.of(), snapshot.closure().editableWayOccurrences(), List.of(),
                    snapshot.closure().collisionEnvelope(), snapshot.closure().editRegion(), true,
                    true, true, true);
            NetworkSnapshot changed = new NetworkSnapshot("authority-no-port", SnapshotRole.CAPTURED_BEFORE,
                    "dataset", 1, closure, snapshot.primitives(), snapshot.incomingReferrerWatches());
            var spec = new NetworkSnapshotCapture.Specification("authority-no-port", "dataset", 1,
                    selected, specification.selectedRange(), FRAME, closure.collisionEnvelope(),
                    closure.editRegion(), closure.editableWayOccurrences(), closure.editableExistingKeys(),
                    nodes, Set.of(), Set.of(), true,
                    new RecoveryPermissions(false, 7.01, 7.01, JunctionPolicy.REATTACH, true));
            return new Fixture(changed, spec, selected, selectedNodes, null);
        }
        private Fixture rebuild(Map<PrimitiveKey, DetachedPrimitive> values,
                Map<PrimitiveKey, Set<PrimitiveKey>> override) {
            return rebuild(values, override, snapshot.closure().externalPorts());
        }
        private Fixture rebuild(Map<PrimitiveKey, DetachedPrimitive> values,
                Map<PrimitiveKey, Set<PrimitiveKey>> override, List<ExternalPort> ports) {
            Map<PrimitiveKey, Set<PrimitiveKey>> watches = new LinkedHashMap<>();
            values.keySet().forEach(key -> watches.put(key, new LinkedHashSet<>()));
            values.values().forEach(primitive -> {
                if (primitive instanceof DetachedWay way) way.nodeKeys().forEach(node -> watches.get(node).add(way.key()));
                if (primitive instanceof DetachedRelation relation) relation.members().forEach(member ->
                        watches.get(member.memberKey()).add(relation.key()));
            });
            snapshot.incomingReferrerWatches().forEach((key, refs) -> {
                if (watches.containsKey(key)) refs.stream().filter(ref -> !values.containsKey(ref))
                        .forEach(watches.get(key)::add);
            });
            override.forEach(watches::put);
            Map<PrimitiveKey, List<OccurrenceRange>> ranges = new LinkedHashMap<>();
            values.values().stream().filter(DetachedWay.class::isInstance).map(DetachedWay.class::cast)
                    .forEach(way -> ranges.put(way.key(), List.of(new OccurrenceRange(0, way.nodeKeys().size() - 1))));
            Set<PrimitiveKey> keys = Set.copyOf(values.keySet());
            Set<PrimitiveKey> nodes = keys.stream().filter(key -> key.type() == PrimitiveKey.Type.NODE)
                    .collect(java.util.stream.Collectors.toSet());
            Set<PrimitiveKey> movable = new LinkedHashSet<>(snapshot.closure().movableExistingNodeKeys());
            movable.retainAll(nodes);
            Set<PrimitiveKey> protectedNodes = new LinkedHashSet<>(snapshot.closure().protectedExistingNodeKeys());
            protectedNodes.retainAll(nodes);
            Set<PrimitiveKey> classified = new LinkedHashSet<>(movable);
            classified.addAll(protectedNodes);
            nodes.stream().filter(node -> !classified.contains(node)).forEach(protectedNodes::add);
            ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.EDIT_COMPONENT,
                    "fixture-v1", keys, keys, movable, protectedNodes, Set.of(), ranges, ports,
                    snapshot.closure().collisionEnvelope(), snapshot.closure().editRegion(), true,
                    true, true, true);
            NetworkSnapshot changed = new NetworkSnapshot("recaptured", SnapshotRole.CAPTURED_BEFORE,
                    "dataset", 1, closure, values, watches);
            var spec = new NetworkSnapshotCapture.Specification("authority", "dataset", 1, selected,
                    new OccurrenceRange(0, selectedNodes.size() - 1), FRAME,
                    closure.collisionEnvelope(), closure.editRegion(), ranges, keys, movable, Set.of(),
                    protectedNodes, true,
                    new RecoveryPermissions(false, 7.01, 7.01, JunctionPolicy.REATTACH, true));
            return new Fixture(changed, spec, selected, selectedNodes, ports.isEmpty() ? null : ports.get(0));
        }
    }
}
