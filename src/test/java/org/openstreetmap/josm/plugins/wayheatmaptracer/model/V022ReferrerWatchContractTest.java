package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class V022ReferrerWatchContractTest {
    private static final PrimitiveKey A = node(1);
    private static final PrimitiveKey B = node(2);
    private static final PrimitiveKey C = node(3);
    private static final PrimitiveKey AB = way(10);
    private static final PrimitiveKey BC = way(11);
    private static final PrimitiveKey EXTERNAL_CD = way(12);

    @Test
    void boundedFivePayloadChainAcceptsExplicitExternalWayWatch() {
        Map<PrimitiveKey, DetachedPrimitive> values = fivePayloadChain();
        Map<PrimitiveKey, Set<PrimitiveKey>> watches = mutableWatches(values);
        watches.get(C).add(EXTERNAL_CD);

        NetworkSnapshot snapshot = assertDoesNotThrow(() -> snapshot(values, watches));

        assertEquals(Set.of(BC, EXTERNAL_CD), snapshot.incomingReferrerWatches().get(C));
        assertEquals(Set.of(BC), snapshot.internalIncomingReferrers().get(C));
    }

    @Test
    void everyMaterializedPrimitiveRequiresAWatchEntryIncludingEmpty() {
        Map<PrimitiveKey, DetachedPrimitive> values = fivePayloadChain();
        Map<PrimitiveKey, Set<PrimitiveKey>> watches = mutableWatches(values);
        watches.remove(AB);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> snapshot(values, watches));

        assertTrue(failure.getMessage().contains("every materialized primitive"));
    }

    @Test
    void materializedPayloadAndInternalWatchMustAgreeExactly() {
        Map<PrimitiveKey, DetachedPrimitive> values = fivePayloadChain();
        Map<PrimitiveKey, Set<PrimitiveKey>> missing = mutableWatches(values);
        missing.get(A).remove(AB);
        Map<PrimitiveKey, Set<PrimitiveKey>> phantom = mutableWatches(values);
        phantom.get(A).add(BC);

        assertThrows(IllegalArgumentException.class, () -> snapshot(values, missing));
        assertThrows(IllegalArgumentException.class, () -> snapshot(values, phantom));
    }

    @Test
    void externalWatchMustBeExistingWayOrRelationIdentity() {
        Map<PrimitiveKey, DetachedPrimitive> values = fivePayloadChain();
        Map<PrimitiveKey, Set<PrimitiveKey>> nodeWatch = mutableWatches(values);
        nodeWatch.get(C).add(node(99));
        Map<PrimitiveKey, Set<PrimitiveKey>> plannedWatch = mutableWatches(values);
        plannedWatch.get(C).add(PrimitiveKey.planned(PrimitiveKey.Type.WAY, 99));

        assertThrows(IllegalArgumentException.class, () -> snapshot(values, nodeWatch));
        assertThrows(IllegalArgumentException.class, () -> snapshot(values, plannedWatch));
    }

    @Test
    void externalWatchIdentityChangesCanonicalSnapshotHash() {
        Map<PrimitiveKey, DetachedPrimitive> values = fivePayloadChain();
        Map<PrimitiveKey, Set<PrimitiveKey>> first = mutableWatches(values);
        first.get(C).add(EXTERNAL_CD);
        Map<PrimitiveKey, Set<PrimitiveKey>> second = mutableWatches(values);
        second.get(C).add(way(13));

        assertNotEquals(snapshot(values, first).canonicalHash(), snapshot(values, second).canonicalHash());
    }

    @Test
    void oversizedWatchInventoryFailsBeforeSetTraversalOrCopy() {
        Map<PrimitiveKey, DetachedPrimitive> values = fivePayloadChain();
        Map<PrimitiveKey, Set<PrimitiveKey>> watches = mutableWatches(values);
        watches.put(C, new AbstractSet<>() {
            @Override
            public Iterator<PrimitiveKey> iterator() {
                throw new AssertionError("oversized watch set must not be traversed");
            }

            @Override
            public int size() {
                return NetworkSnapshot.MAX_REFERRER_WATCH_IDENTITIES + 1;
            }
        });

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> snapshot(values, watches));

        assertTrue(failure.getMessage().contains("watch identity budget"));
    }

    private static NetworkSnapshot snapshot(Map<PrimitiveKey, DetachedPrimitive> values,
        Map<PrimitiveKey, Set<PrimitiveKey>> watches) {
        Set<PrimitiveKey> keys = Set.copyOf(values.keySet());
        ClosureDescriptor closure = new ClosureDescriptor(ClosureDescriptor.Scope.EDIT_COMPONENT,
            "referrer-watch-fixture-v1", keys, Set.of(), Set.of(), keys.stream()
                .filter(key -> key.type() == PrimitiveKey.Type.NODE).collect(java.util.stream.Collectors.toSet()),
            Set.of(), Map.of(), List.of(), MetricRegion.rectangle(-20, -20, 20, 20),
            MetricRegion.rectangle(-20, -20, 20, 20), false, true, true, true);
        return new NetworkSnapshot("watch-fixture", SnapshotRole.CAPTURED_BEFORE,
            "dataset", 1, closure, values, watches);
    }

    private static Map<PrimitiveKey, DetachedPrimitive> fivePayloadChain() {
        Map<PrimitiveKey, DetachedPrimitive> values = new LinkedHashMap<>();
        values.put(A, detachedNode(A, 0.0));
        values.put(B, detachedNode(B, 0.001));
        values.put(C, detachedNode(C, 0.002));
        values.put(AB, new DetachedWay(AB, List.of(A, B), Map.of(), false, false));
        values.put(BC, new DetachedWay(BC, List.of(B, C), Map.of(), false, false));
        return values;
    }

    private static Map<PrimitiveKey, Set<PrimitiveKey>> mutableWatches(
        Map<PrimitiveKey, DetachedPrimitive> values) {
        Map<PrimitiveKey, Set<PrimitiveKey>> result = new LinkedHashMap<>();
        V022SnapshotFixtures.closedWorldReferrerWatches(values)
            .forEach((key, refs) -> result.put(key, new LinkedHashSet<>(refs)));
        return result;
    }

    private static DetachedNode detachedNode(PrimitiveKey key, double longitude) {
        return new DetachedNode(key, new GeographicPoint(0.0, longitude), Map.of(), false, false);
    }

    private static PrimitiveKey node(long id) {
        return PrimitiveKey.existing(PrimitiveKey.Type.NODE, id);
    }

    private static PrimitiveKey way(long id) {
        return PrimitiveKey.existing(PrimitiveKey.Type.WAY, id);
    }
}
