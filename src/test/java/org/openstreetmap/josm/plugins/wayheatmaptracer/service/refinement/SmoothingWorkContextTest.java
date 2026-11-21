package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class SmoothingWorkContextTest {
    @Test void tighterReservationIsAtomicAcrossTwoOwnersAndRejectsForeignOwners() throws Exception {
        var ledger=new AttemptMemoryLedger(1024);try(var root=ledger.rootOwner()) {
            var first=root.child("first");var second=root.child("second");var start=new java.util.concurrent.CountDownLatch(1);
            var accepted=new java.util.concurrent.atomic.AtomicInteger();var rejected=new java.util.concurrent.atomic.AtomicInteger();
            var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var tasks=new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for(var owner:java.util.List.of(first,second))tasks.add(executor.submit(()->{try{start.await();owner.reserveWithin(100,80).adopt(new Object());accepted.incrementAndGet();}
                    catch(AttemptMemoryLedger.ResourceLimitException expected){rejected.incrementAndGet();}catch(InterruptedException interrupted){throw new AssertionError(interrupted);}}));
                start.countDown();for(var task:tasks)task.get();assertEquals(1,accepted.get());assertEquals(1,rejected.get());assertEquals(80,ledger.currentBytes());
            }finally{executor.shutdownNow();}
            var work=new GeometryWorkContext(100,100,Long.MAX_VALUE,CancellationProbe.NONE,first);
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->work.reserveOn(second,"overlap",21));assertEquals(80,ledger.currentBytes());
            try(var foreign=AttemptMemoryLedger.production().rootOwner()) {
                long visits=work.visits();
                assertThrows(IllegalArgumentException.class,()->work.reserveOn(foreign,"foreign",1));
                assertEquals(visits,work.visits(),"Foreign owners are rejected before work");
            }
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->first.reserveWithin(79,0));
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->first.reserveWithin(Long.MAX_VALUE,Long.MAX_VALUE));
            assertEquals(80,ledger.currentBytes());assertEquals(80,ledger.peakBytes(),"Rejected reservations cannot change current or peak charges");
            second.close();assertThrows(IllegalStateException.class,()->work.reserveOn(second,"closed",1));
            assertThrows(IllegalArgumentException.class,()->first.reserveWithin(-1,1));assertThrows(IllegalArgumentException.class,()->first.reserveWithin(100,-1));
        }assertEquals(0,ledger.currentBytes());
    }
    @Test void admittedImageCopiesAndStreamingHashKeepOldValuesAndBytes() {
        var evidence=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE).evidence();
        var expected=evidence.canonicalHash();
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            var image=ImageCostField.fromEvidence(evidence,"native",work);
            assertEquals(expected,evidence.canonicalHash(work));
            assertEquals(ImageCostField.fromEvidence(evidence,"native").sample(new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(50,53)),image.sample(new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(50,53)));
            assertTrue(work.visits()>16_261,"every scalar/mask/hash element must be debited");
        }
    }
    @Test void zeroBudgetAndCancelledCopyRefuseBeforeRetainedImagePublication() {
        var evidence=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE).evidence();
        for(long limit:new long[]{0,257}) {
            var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()) {
                var work=new GeometryWorkContext(limit,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
                assertThrows(GeometryWorkContext.LimitReached.class,()->ImageCostField.fromEvidence(evidence,"native",work));
                assertTrue(work.visits()<=limit);assertEquals(0,ledger.currentBytes(),"failed copy settles its temporary image allocation");
            }
        }
    }
}
