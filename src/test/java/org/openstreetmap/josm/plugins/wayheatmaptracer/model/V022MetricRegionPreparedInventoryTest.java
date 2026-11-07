package org.openstreetmap.josm.plugins.wayheatmaptracer.model;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022MetricRegionPreparedInventoryTest {
    @Test void exactRepeatedQueriesSaveOnlyTheRepeatedEdgeInventoryAfterChargedSetup() {
        int polygons=12,queries=10;var quads=new ArrayList<List<MetricPoint>>();
        for(int i=0;i<polygons;i++)quads.add(MetricRegion.rectangle(-2,i*10-2,2,i*10+2).polygons().get(0));
        var region=new MetricRegion(quads);long[] oldWork={0},newWork={0};var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var old=region.prepareYMembership(CancellationProbe.NONE,n->oldWork[0]+=n,owner);
            var prepared=region.prepareYMembershipWithInventory(CancellationProbe.NONE,n->newWork[0]+=n,owner);
            for(int i=0;i<queries;i++) {
                var a=new MetricPoint(-1,0);var b=new MetricPoint(1,0);
                assertTrue(region.containsSegmentWithBoundedEvents(a,b,CancellationProbe.NONE,n->oldWork[0]+=n,owner,old));
                assertTrue(region.containsSegmentWithBoundedEvents(a,b,CancellationProbe.NONE,n->newWork[0]+=n,owner,prepared));
            }
            assertEquals(queries*(polygons-1L)-(polygons+1L),oldWork[0]-newWork[0],
                    "each new query replaces P inventory visits with one read; setup adds P checked additions and one store");
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void allEventWorkAfterInventoryAndAuthorityRemainIdenticalAcrossGeometries() {
        var regions=List.of(MetricRegion.rectangle(-3,-3,3,3),new MetricRegion(List.of(
                MetricRegion.rectangle(-3,-3,0,3).polygons().get(0),MetricRegion.rectangle(0,-3,3,3).polygons().get(0))),
                new MetricRegion(List.of(MetricRegion.rectangle(-3,-3,1,3).polygons().get(0),MetricRegion.rectangle(-1,-2,3,2).polygons().get(0))),
                new MetricRegion(List.of(MetricRegion.rectangle(-3,-3,-1,3).polygons().get(0),MetricRegion.rectangle(1,-3,3,3).polygons().get(0))));
        var random=new Random(8117);var segments=new ArrayList<List<MetricPoint>>();
        for(double[] v:new double[][]{{-2,0,2,0},{-3,-3,3,-3},{-3,3,3,3},{0,0,0,0},{0,0,1e-12,0},{-4,-4,4,4},{-3,0,-3,3},{4,4,5,5}})
            segments.add(List.of(new MetricPoint(v[0],v[1]),new MetricPoint(v[2],v[3])));
        for(int i=0;i<60;i++)segments.add(List.of(new MetricPoint(random.nextDouble()*10-5,random.nextDouble()*10-5),new MetricPoint(random.nextDouble()*10-5,random.nextDouble()*10-5)));
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            for(var region:regions) {
                var ordinary=region.prepareYMembership(CancellationProbe.NONE,n->{},owner);
                var cached=region.prepareYMembershipWithInventory(CancellationProbe.NONE,n->{},owner);
                for(var segment:segments)for(boolean reverse:new boolean[]{false,true}) {
                    var a=segment.get(reverse?1:0);var b=segment.get(reverse?0:1);
                    var oldTrace=new ArrayList<Long>();var newTrace=new ArrayList<Long>();
                    assertEquals(region.containsSegmentWithBoundedEvents(a,b,CancellationProbe.NONE,oldTrace::add,owner,ordinary),
                            region.containsSegmentWithBoundedEvents(a,b,CancellationProbe.NONE,newTrace::add,owner,cached));
                    assertEquals(oldTrace.subList(region.polygons().size(),oldTrace.size()),newTrace.subList(1,newTrace.size()),
                            "all edge/event/sort/midpoint admission operations after inventory are unchanged");
                }
            }
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void nullForeignOrdinaryExpiredAndUnownedIndicesUseHistoricalInventoryWork() {
        var region=new MetricRegion(List.of(MetricRegion.rectangle(-3,-3,3,3).polygons().get(0),MetricRegion.rectangle(-2,-2,2,2).polygons().get(0)));
        var foreign=new MetricRegion(region.polygons());var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner();var preparation=owner.child("cache-owner")) {
            var old=foreign.prepareYMembership(CancellationProbe.NONE,n->{},preparation);
            var cached=foreign.prepareYMembershipWithInventory(CancellationProbe.NONE,n->{},preparation);
            assertEquals(work(region,old,owner),work(region,cached,owner));assertEquals(0,MetricRegion.preparedInventoryReads(cached));
            var ordinary=region.prepareYMembership(CancellationProbe.NONE,n->{},preparation);
            var unowned=region.prepareYMembershipWithInventory(CancellationProbe.NONE,n->{},null);
            assertEquals(work(region,ordinary,owner),work(region,unowned,owner));
            var matching=region.prepareYMembershipWithInventory(CancellationProbe.NONE,n->{},preparation);
            assertEquals(work(region,ordinary,null),work(region,matching,null));assertEquals(0,MetricRegion.preparedInventoryReads(matching));
            preparation.close();assertEquals(work(region,null,owner),work(region,matching,owner));
        }
        assertEquals(0,ledger.currentBytes());
    }
    private static long work(MetricRegion region,MetricRegion.YMembershipIndex index,AttemptMemoryLedger.Owner owner) {
        long[] work={0};assertTrue(region.containsSegmentWithBoundedEvents(new MetricPoint(-1,0),new MetricPoint(1,0),CancellationProbe.NONE,n->work[0]+=n,owner,index));return work[0];
    }
    @Test void checkedOverflowCancellationMemoryAndExactWorkLimitsRemainBounded() {
        assertThrows(ArithmeticException.class,()->MetricRegion.addInventoryEdges(Long.MAX_VALUE,1));
        assertThrows(ArithmeticException.class,()->MetricRegion.checkedSegmentEventBound(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->MetricRegion.checkedSegmentEventBound(Integer.MAX_VALUE));
        var region=MetricRegion.rectangle(-3,-3,3,3);var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            assertThrows(java.util.concurrent.CancellationException.class,()->region.prepareYMembershipWithInventory(()->true,n->{},owner));assertEquals(0,ledger.currentBytes());
            var index=region.prepareYMembershipWithInventory(CancellationProbe.NONE,n->{},owner);long held=ledger.currentBytes();
            assertThrows(java.util.concurrent.CancellationException.class,()->region.containsSegmentWithBoundedEvents(new MetricPoint(0,0),new MetricPoint(1,0),()->true,n->{},owner,index));
            assertEquals(0,MetricRegion.preparedInventoryReads(index));
            long exact=work(region,index,owner);
            for(long cap:new long[]{exact,exact-1}) {
                long[] used={0};Runnable query=()->region.containsSegmentWithBoundedEvents(new MetricPoint(-1,0),new MetricPoint(1,0),CancellationProbe.NONE,n->{
                    if(n>cap-used[0])throw new IllegalStateException("work refused");used[0]+=n;
                },owner,index);
                if(cap==exact)assertDoesNotThrow(query::run);else assertThrows(IllegalStateException.class,query::run);
                assertEquals(held,ledger.currentBytes());
            }
        }
        assertEquals(0,ledger.currentBytes());
        var tiny=new AttemptMemoryLedger(1);try(var owner=tiny.rootOwner()) {
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->region.prepareYMembershipWithInventory(CancellationProbe.NONE,n->{},owner));assertEquals(0,tiny.currentBytes());
        }
    }
}
