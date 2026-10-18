package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.JunctionPolicy;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.PrimitiveKey;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RecoveryPermissions;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SharedNodeDisconnectionIntent;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;

class V022DisconnectionPermissionReplayGuardTest {
    @Test
    void historicalEmptyPermissionIdentityAndEncodingRemainStable() {
        RecoveryPermissions empty = RecoveryPermissions.disabled(7.0);
        assertEquals("RecoveryPermissions[widerDiscovery=false, ordinaryRadiusMeters=7.0, "
                + "maximumDiscoveryRadiusMeters=7.0, junctionPolicy=FIXED, reconstructIncidentWays=false]",
                empty.canonicalIdentity());
        assertDoesNotThrow(() -> FrozenReplayCodec.encodeRequestOnly(request(empty)));
    }

    @Test
    void versionedRequestSchemaAcceptsSourceBoundNonemptyDisconnectionAuthority() {
        PrimitiveKey way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 10);
        PrimitiveKey node = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 11);
        var source = source();
        SharedNodeDisconnectionIntent intent = new SharedNodeDisconnectionIntent(way,
                new OccurrenceRange(0, 1), Map.of(0, node), source.canonicalHash());
        RecoveryPermissions explicit = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.FIXED, false, intent);

        TraceRequest valid = request(explicit, source);
        assertDoesNotThrow(() -> FrozenReplayCodec.encodeRequestOnly(valid));
    }

    private static TraceRequest request(RecoveryPermissions permissions) {
        return request(permissions, null);
    }

    private static TraceRequest request(RecoveryPermissions permissions,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot source) {
        return new TraceRequest(PrimitiveKey.existing(PrimitiveKey.Type.WAY, 10),
                new OccurrenceRange(0, 1), TrackerMode.PROBABILISTIC, AlignmentMode.PRECISE_SHAPE,
                permissions, TraceBudgets.defaults(), "evidence-id", "evidence-hash",
                "network-id", "network-hash", "settings-hash", "parameter-hash", "sampler", 1.0,
                new ProfileChainage(List.of(0.0), 1.0), EvidenceResolution.renderedOnly(1.0),
                Optional.empty(), source);
    }

    private static org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot source() {
        var way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 10);
        var other = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 20);
        var shared = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 11);
        var end = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 12);
        var otherEnd = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 13);
        var region = org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion.rectangle(-100, -100, 100, 100);
        Map<PrimitiveKey, org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedPrimitive> values = Map.of(
                way, new org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay(way, List.of(shared, end), Map.of(), false, false),
                other, new org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay(other, List.of(shared, otherEnd), Map.of(), false, false),
                shared, new org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode(shared,
                        new org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint(0, 0), Map.of(), false, false),
                end, new org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode(end,
                        new org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint(0, 0.0001), Map.of(), false, false),
                otherEnd, new org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode(otherEnd,
                        new org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint(0.0001, 0), Map.of(), false, false));
        var closure = new org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor(
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.ClosureDescriptor.Scope.EDIT_COMPONENT,
                "codec-guard", values.keySet(), java.util.Set.of(way), java.util.Set.of(),
                java.util.Set.of(shared, end, otherEnd), java.util.Set.of(),
                Map.of(way, List.of(new OccurrenceRange(0, 1))), List.of(), region, region, true, true, true, true);
        return new org.openstreetmap.josm.plugins.wayheatmaptracer.model.NetworkSnapshot("source",
                org.openstreetmap.josm.plugins.wayheatmaptracer.model.SnapshotRole.CAPTURED_BEFORE,
                "dataset", 1, closure, values, Map.of(way, java.util.Set.of(), other, java.util.Set.of(),
                        shared, java.util.Set.of(way, other), end, java.util.Set.of(way), otherEnd, java.util.Set.of(other)));
    }
}
