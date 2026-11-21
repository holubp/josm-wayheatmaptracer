package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class PostprocessedWayReferenceWorkTest {
    @Test void scopedConstructorWayMembershipChecksEveryReferenceAndRestoresAfterRefusal() {
        var key=PrimitiveKey.existing(PrimitiveKey.Type.NODE,1);var keys=Set.of(key);var refs=Collections.nCopies(10000,key);
        assertTrue(NetworkSnapshot.scopedWayNodesPresent(keys,refs));
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var work=new GeometryWorkContext(256,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            assertThrows(GeometryWorkContext.LimitReached.class,()->{try(var scope=PostprocessingValidationWork.open(work)){NetworkSnapshot.scopedWayNodesPresent(keys,refs);}});
            assertNull(PostprocessingValidationWork.current());
            var complete=new GeometryWorkContext(20000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            try(var scope=PostprocessingValidationWork.open(complete)){assertTrue(NetworkSnapshot.scopedWayNodesPresent(keys,refs));}
            assertEquals(10000,complete.visits());assertTrue(NetworkSnapshot.scopedWayNodesPresent(keys,refs));
        }
    }
}
