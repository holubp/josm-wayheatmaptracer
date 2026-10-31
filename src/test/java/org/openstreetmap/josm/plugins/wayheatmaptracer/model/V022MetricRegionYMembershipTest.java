package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Conservative membership exclusion must retain the complete event proof. */
class V022MetricRegionYMembershipTest {
    @Test
    void repeatedExactInteriorProofsFitUnchangedSharedWorkLimit() {
        var region = remoteFirst();
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        AtomicLong units = new AtomicLong();
        LongConsumer admission = amount -> {
            if (amount > 13_000L - units.get()) throw new WorkRefusal();
            units.addAndGet(amount);
        };
        try {
            var index = prepare(region, CancellationProbe.NONE, admission, owner);
            for (int query = 0; query < 10; query++)
                assertTrue(indexed(region, new MetricPoint(-1, 0), new MetricPoint(1, 0),
                        CancellationProbe.NONE, admission, owner, index));
            assertTrue(units.get() <= 13_000L);
            assertEquals(12_079L, units.get(),
                    "709 build plus ten 1137-unit proofs, including all404 edge events each");
        } finally { owner.close(); }
        assertEquals(0L, ledger.currentBytes());
    }

    @Test
    void unindexedBoundedAndHistoricalEntryPointsRetainExactWork() {
        var region = remoteFirst();
        AtomicLong units = new AtomicLong();
        assertTrue(region.containsSegmentWithBoundedEvents(new MetricPoint(-1,0),new MetricPoint(1,0),
                CancellationProbe.NONE, units::addAndGet, null));
        //101 inventory +1212 membership (404 edges, three points) +404 event edges
        //+1 facade +3 backing allocation +2 boxes +4 sort +1 interval.
        assertEquals(1728L,units.get());
        units.set(0);
        assertTrue(region.containsSegment(new MetricPoint(-1,0),new MetricPoint(1,0),
                CancellationProbe.NONE, units::addAndGet, null));
        assertEquals(2532L,units.get()); // Historical810-slot charge replaces bounded6 units.
        units.set(0);
        assertThrows(WorkRefusal.class, () -> {
            for(int i=0;i<10;i++) region.containsSegmentWithBoundedEvents(new MetricPoint(-1,0),
                    new MetricPoint(1,0),CancellationProbe.NONE, amount -> {
                        if(amount>13000-units.get())throw new WorkRefusal();
                        units.addAndGet(amount);
                    },null);
        });
    }

    @Test
    void toleranceNotchUnionExtremeAndReverseProofsMatchFrozenHistoricalOracle() {
        List<MetricRegion> regions = List.of(MetricRegion.rectangle(-5,-5,5,5),
                new MetricRegion(List.of(MetricRegion.rectangle(-5,-5,5,-3).polygons().get(0),
                        MetricRegion.rectangle(-5,3,5,5).polygons().get(0))),
                new MetricRegion(List.of(List.of(new MetricPoint(-5,-4),new MetricPoint(5,-2),
                        new MetricPoint(5,4),new MetricPoint(1,2),new MetricPoint(-5,4)))),
                MetricRegion.rectangle(-1e100,-1e100,1e100,1e100),
                MetricRegion.rectangle(-1e300,-1e300,1e300,1e300));
        var ledger=AttemptMemoryLedger.production(); var owner=ledger.rootOwner();
        try {
            Random random=new Random(1917);
            for(var region:regions) {
                var index=prepare(region,CancellationProbe.NONE,ignored->{},owner);
                for(int i=0;i<500;i++) parity(region,index,
                        new MetricPoint(random.nextDouble()*20-10,random.nextDouble()*20-10),
                        new MetricPoint(random.nextDouble()*20-10,random.nextDouble()*20-10),owner);
                for(var polygon:region.polygons()) for(var vertex:polygon)
                    for(double y:new double[]{vertex.yMeters(),vertex.yMeters()-1e-9,
                            vertex.yMeters()+1e-9,Math.nextDown(vertex.yMeters()-1e-9),
                            Math.nextUp(vertex.yMeters()+1e-9),-0.0,Double.MIN_VALUE})
                        parity(region,index,new MetricPoint(-6,y),new MetricPoint(6,y),owner);
            }
        } finally {owner.close();}
        assertEquals(0,ledger.currentBytes());
    }

    @Test
    void remoteToleranceEventSurvivesPointMembershipExclusion() {
        var region=new MetricRegion(List.of(
                MetricRegion.rectangle(-6,-1,-1.5e-9,1).polygons().get(0),
                MetricRegion.rectangle(1.5e-9,-1,6,1).polygons().get(0),
                MetricRegion.rectangle(0,5e-9,.1,100).polygons().get(0)));
        assertFalse(region.contains(new MetricPoint(0,0)));
        assertFalse(new MetricRegion(region.polygons().subList(0,2))
                .containsSegment(new MetricPoint(-5,0),new MetricPoint(5,0)));
        var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();
        try {
            var index=prepare(region,CancellationProbe.NONE,ignored->{},owner);
            parity(region,index,new MetricPoint(-5,0),new MetricPoint(5,0),owner);
            assertTrue(indexed(region,new MetricPoint(-5,0),new MetricPoint(5,0),
                    CancellationProbe.NONE,ignored->{},owner,index));
        } finally {owner.close();}
        assertEquals(0,ledger.currentBytes());
    }

    @Test
    void equalButForeignIndexUsesOriginalPredicate() {
        var region=MetricRegion.rectangle(-2,-2,2,2);
        var foreign=new MetricRegion(region.polygons());
        assertEquals(region,foreign);assertNotSame(region,foreign);
        var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();
        try {
            var index=prepare(foreign,CancellationProbe.NONE,ignored->{},owner);
            parity(region,index,new MetricPoint(-1,0),new MetricPoint(1,0),owner);
            parity(region,index,new MetricPoint(-1,3),new MetricPoint(1,3),owner);
        } finally {owner.close();}
        assertEquals(0,ledger.currentBytes());
    }

    @Test
    void invalidControlsRefuseBeforePreparationAllocation() {
        var region=remoteFirst();var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();
        try {
            assertThrows(IllegalArgumentException.class,()->prepare(region,null,ignored->{},owner));
            assertThrows(IllegalArgumentException.class,()->prepare(region,CancellationProbe.NONE,null,owner));
            assertEquals(0,ledger.currentBytes());
        } finally {owner.close();}
    }

    @Test
    void memoryAndCancellationRefusalsReleaseEveryPartialBuild() {
        var region=remoteFirst();
        var tiny=new AttemptMemoryLedger(64);var tinyOwner=tiny.rootOwner();
        try {assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                ()->prepare(region,CancellationProbe.NONE,ignored->{},tinyOwner));}
        finally {tinyOwner.close();}
        assertEquals(0,tiny.currentBytes());
        for(int boundary:new int[]{1,2,3,4,20,505}) {
            var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();AtomicLong visits=new AtomicLong();
            try {
                assertThrows(CancellationException.class,()->prepare(region,
                        ()->visits.incrementAndGet()==boundary,ignored->{},owner));
                assertEquals(0,ledger.currentBytes());
            } finally {owner.close();}
        }
    }

    @Test
    void indexedQueryRefusalAndCancellationRetainOnlyAttemptIndex() {
        var region=remoteFirst();var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();
        try {
            var index=prepare(region,CancellationProbe.NONE,ignored->{},owner);
            long retained=ledger.currentBytes();
            AtomicLong units=new AtomicLong();
            assertThrows(WorkRefusal.class,()->indexed(region,new MetricPoint(-1,0),new MetricPoint(1,0),
                    CancellationProbe.NONE,amount->{
                        if(amount>105-units.get())throw new WorkRefusal();units.addAndGet(amount);
                    },owner,index));
            assertEquals(retained,ledger.currentBytes());
            AtomicLong visits=new AtomicLong();
            assertThrows(CancellationException.class,()->indexed(region,new MetricPoint(-1,0),new MetricPoint(1,0),
                    ()->visits.incrementAndGet()==105,ignored->{},owner,index));
            assertEquals(retained,ledger.currentBytes());
        } finally {owner.close();}
        assertEquals(0,ledger.currentBytes());
    }

    private static void parity(MetricRegion region,MetricRegion.YMembershipIndex index,
            MetricPoint a,MetricPoint b,AttemptMemoryLedger.Owner owner) {
        for(boolean reverse:List.of(false,true)) {
            var from=reverse?b:a;var to=reverse?a:b;
            boolean expected=V022MetricRegionSegmentWorkTest.historicalSegment(region,from,to);
            assertEquals(expected,region.containsSegment(from,to,CancellationProbe.NONE,ignored->{},owner));
            assertEquals(expected,region.containsSegmentWithBoundedEvents(from,to,
                    CancellationProbe.NONE,ignored->{},owner));
            assertEquals(expected,indexed(region,from,to,CancellationProbe.NONE,ignored->{},owner,index));
        }
    }

    @Test
    void preparedIndexRemainsOwnedUntilAttemptClose() {
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        try {
            prepare(MetricRegion.rectangle(-2,-2,2,2), CancellationProbe.NONE, ignored -> {}, owner);
            assertEquals(AttemptMemoryLedger.objectBytes(24)
                    + 2L * AttemptMemoryLedger.arrayBytes(1, Double.BYTES), ledger.currentBytes());
        } finally { owner.close(); }
        assertEquals(0L, ledger.currentBytes());
    }

    @Test
    void lateBuildWorkRefusalReleasesUncommittedIndex() {
        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        AtomicLong units = new AtomicLong();
        try {
            assertThrows(WorkRefusal.class, () -> prepare(remoteFirst(), CancellationProbe.NONE, amount -> {
                if (amount > 210 - units.get()) throw new WorkRefusal();
                units.addAndGet(amount);
            }, owner));
            assertEquals(0L, ledger.currentBytes());
        } finally { owner.close(); }
    }

    static MetricRegion remoteFirst() {
        List<List<MetricPoint>> polygons = new ArrayList<>();
        for (int i=0; i<100; i++)
            polygons.add(MetricRegion.rectangle(-2,10+4*i,2,12+4*i).polygons().get(0));
        polygons.add(MetricRegion.rectangle(-2,-2,2,2).polygons().get(0));
        return new MetricRegion(polygons);
    }

    private static MetricRegion.YMembershipIndex prepare(MetricRegion region, CancellationProbe cancel,
            LongConsumer admission, AttemptMemoryLedger.Owner owner) {
        return region.prepareYMembership(cancel,admission,owner);
    }

    private static boolean indexed(MetricRegion region, MetricPoint a, MetricPoint b,
            CancellationProbe cancel, LongConsumer admission, AttemptMemoryLedger.Owner owner,
            MetricRegion.YMembershipIndex index) {
        return region.containsSegmentWithBoundedEvents(a,b,cancel,admission,owner,index);
    }
    private static final class WorkRefusal extends RuntimeException { }
}
