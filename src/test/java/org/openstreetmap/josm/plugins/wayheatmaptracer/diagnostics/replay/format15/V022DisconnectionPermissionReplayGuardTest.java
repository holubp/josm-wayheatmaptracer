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
    void oldRequestSchemaRejectsNonemptyDisconnectionAuthorityInsteadOfDroppingIt() {
        PrimitiveKey way = PrimitiveKey.existing(PrimitiveKey.Type.WAY, 10);
        PrimitiveKey node = PrimitiveKey.existing(PrimitiveKey.Type.NODE, 11);
        SharedNodeDisconnectionIntent intent = new SharedNodeDisconnectionIntent(way,
                new OccurrenceRange(0, 1), Map.of(1, node), "factual-source-hash");
        RecoveryPermissions explicit = new RecoveryPermissions(false, 7.0, 7.0,
                JunctionPolicy.FIXED, false, intent);

        assertThrows(IllegalArgumentException.class,
                () -> FrozenReplayCodec.encodeRequestOnly(request(explicit)));
    }

    private static TraceRequest request(RecoveryPermissions permissions) {
        return new TraceRequest(PrimitiveKey.existing(PrimitiveKey.Type.WAY, 10),
                new OccurrenceRange(0, 1), TrackerMode.PROBABILISTIC, AlignmentMode.PRECISE_SHAPE,
                permissions, TraceBudgets.defaults(), "evidence-id", "evidence-hash",
                "network-id", "network-hash", "settings-hash", "parameter-hash", "sampler", 1.0,
                new ProfileChainage(List.of(0.0), 1.0), EvidenceResolution.renderedOnly(1.0),
                Optional.empty());
    }
}
