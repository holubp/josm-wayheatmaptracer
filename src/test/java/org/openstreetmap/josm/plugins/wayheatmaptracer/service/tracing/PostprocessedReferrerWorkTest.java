package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.*;

class PostprocessedReferrerWorkTest {
    @Test void longWayAndRelationReferencesAreVisitedAndOpaqueCopiesPredebited() {
        int count=10000;var values=new LinkedHashMap<PrimitiveKey,DetachedPrimitive>();var keys=new ArrayList<PrimitiveKey>();var members=new ArrayList<DetachedRelationMember>();
        for(int i=0;i<count;i++){var key=PrimitiveKey.existing(PrimitiveKey.Type.NODE,i+1);keys.add(key);members.add(new DetachedRelationMember(key,""));values.put(key,new DetachedNode(key,new GeographicPoint(50,14+i*1e-9),Map.of(),false,false));}
        var way=PrimitiveKey.existing(PrimitiveKey.Type.WAY,1);values.put(way,new DetachedWay(way,keys,Map.of(),false,false));
        var relation=PrimitiveKey.existing(PrimitiveKey.Type.RELATION,1);values.put(relation,new DetachedRelation(relation,members,Map.of(),false,false));
        var expected=ModernSingleWayEditPlanAdapter.internalWatches(values);
        try(var owner=AttemptMemoryLedger.production().rootOwner();var admitted=owner.reserve(16_000_000)) {
            var work=new GeometryWorkContext(1_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            try(var scope=PostprocessingValidationWork.open(work)){assertEquals(expected,ModernSingleWayEditPlanAdapter.internalWatches(values));}
            assertTrue(work.visits()>=values.size()+count+count+count*2L,"Inventory, node/member references and copied referrers must all be charged");
            long limit=values.size()+count+256L;
            var tiny=new GeometryWorkContext(limit,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            assertThrows(GeometryWorkContext.LimitReached.class,()->{try(var scope=PostprocessingValidationWork.open(tiny)){ModernSingleWayEditPlanAdapter.internalWatches(values);}});
            assertNull(PostprocessingValidationWork.current());
            var reference=new GeometryWorkContext[1];reference[0]=new GeometryWorkContext(1_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,()->reference[0].visits()>=limit,owner);
            assertThrows(java.util.concurrent.CancellationException.class,()->{try(var scope=PostprocessingValidationWork.open(reference[0])){ModernSingleWayEditPlanAdapter.internalWatches(values);}});
            assertEquals(expected,ModernSingleWayEditPlanAdapter.internalWatches(values));assertNull(PostprocessingValidationWork.current());
        }
    }
}
