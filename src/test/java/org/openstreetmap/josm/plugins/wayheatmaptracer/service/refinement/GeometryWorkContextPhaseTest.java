package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Phase changes share actual spent work and the existing physical operation owner. */
class GeometryWorkContextPhaseTest {
    private static GeometryWorkContext phase(GeometryWorkContext original,long ceiling) throws Exception {
        try{return (GeometryWorkContext)GeometryWorkContext.class.getMethod("phaseView",long.class).invoke(original,ceiling);}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException runtime)throw runtime;throw failure;}
    }
    private static long ceiling(GeometryWorkContext work) throws Exception {
        return (long)GeometryWorkContext.class.getMethod("maximumVisits").invoke(work);
    }
    private static AutoCloseable tightening(long ceiling) throws Exception {
        try{return (AutoCloseable)LegacyRefinementWorkflow.class.getMethod("tightenPreparationForTesting",long.class).invoke(null,ceiling);}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException runtime)throw runtime;throw failure;}
    }
    @Test void guiTestScopeIsEdtOwnedTighteningOnlyAndRestoresProduction() throws Exception {
        assertThrows(IllegalStateException.class,()->tightening(8_000_000));
        javax.swing.SwingUtilities.invokeAndWait(()->{
            try {
                assertEquals(64_000_000,LegacyRefinementWorkflow.SourcePreparationControl.production().refinementVisits());
                assertThrows(IllegalArgumentException.class,()->tightening(8_000_001));
                try(var ignored=tightening(8_000_000)) {
                    var actual=LegacyRefinementWorkflow.SourcePreparationControl.production();
                    assertEquals(8_000_000,actual.visits());assertEquals(8_000_000,actual.refinementVisits());
                    assertThrows(IllegalStateException.class,()->tightening(1));
                }
                assertEquals(64_000_000,LegacyRefinementWorkflow.SourcePreparationControl.production().refinementVisits());
            }catch(Exception failure){throw new AssertionError(failure);}
        });
    }
    @Test void productionOnlyWidensRefinementAndOldControlTightensBothPhases() throws Exception {
        var production=LegacyRefinementWorkflow.SourcePreparationControl.production();
        var accessor=production.getClass().getDeclaredMethod("refinementVisits");accessor.setAccessible(true);
        assertEquals(8_000_000L,production.visits());assertEquals(64_000_000L,accessor.invoke(production));
        assertEquals(AttemptMemoryLedger.MAX_BYTES,production.bytes());
        var tightened=new LegacyRefinementWorkflow.SourcePreparationControl(257,1024,value->{});
        assertEquals(257,tightened.visits());assertEquals(257L,accessor.invoke(tightened));
        assertThrows(IllegalArgumentException.class,()->new LegacyRefinementWorkflow.SourcePreparationControl(8_000_001L,1024,value->{}));
    }
    @Test void phaseIncludesSpentBootstrapAndDoesNotRenewBetweenSources() throws Exception {
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var bootstrap=new GeometryWorkContext(4,4096,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            bootstrap.visit(GeometryWorkContext.Kind.BIND,2);
            var refine=phase(bootstrap,64);
            assertSame(owner,refine.owner());assertEquals(4,ceiling(bootstrap));assertEquals(64,ceiling(refine));
            assertTrue(refine.visits()>2,"Phase metadata is charged, not hidden or reset");
            assertEquals(bootstrap.visits(),refine.visits());
            assertThrows(GeometryWorkContext.LimitReached.class,()->bootstrap.visit(GeometryWorkContext.Kind.MAP,1));
            refine.visit(GeometryWorkContext.Kind.COPY,20); // first palette
            refine.visit(GeometryWorkContext.Kind.MAP,20); // second palette / fallback
            refine.visit(GeometryWorkContext.Kind.HASH,64-refine.visits());
            assertEquals(64,bootstrap.visits());assertEquals(bootstrap.counters(),refine.counters());
            assertEquals(64,refine.counters().values().stream().mapToLong(Long::longValue).sum());
            assertThrows(GeometryWorkContext.LimitReached.class,()->refine.visit(GeometryWorkContext.Kind.BIND,1));
            assertThrows(GeometryWorkContext.LimitReached.class,()->phase(refine,64));
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void aTightenedPhaseCannotPublishWithSpentWorkAboveItsCeiling() throws Exception {
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var original=new GeometryWorkContext(8,4096,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            assertThrows(GeometryWorkContext.LimitReached.class,()->phase(original,0));
            assertEquals(0,ledger.currentBytes());assertTrue(original.visits()<=8);
        }
    }
    @Test void phaseRetainsCancellationAbsoluteDeadlineAndByteCeiling() throws Exception {
        var cancelled=new AtomicBoolean();var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
            var original=new GeometryWorkContext(8,128,deadline,()->cancelled.get(),owner);
            var refine=phase(original,64);
            assertTrue(ledger.currentBytes()>0,"The small phase view must be admitted before publication");
            var field=GeometryWorkContext.class.getDeclaredField("deadlineNanos");field.setAccessible(true);
            assertEquals(deadline,field.getLong(refine));
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->refine.reserve("same-byte-cap",129));
            cancelled.set(true);
            assertThrows(java.util.concurrent.CancellationException.class,()->refine.visit(GeometryWorkContext.Kind.COPY,1));
            assertThrows(java.util.concurrent.CancellationException.class,()->original.visit(GeometryWorkContext.Kind.COPY,1));
        }
        var expiredLedger=AttemptMemoryLedger.production();
        try(var owner=expiredLedger.rootOwner()) {
            var expired=new GeometryWorkContext(8,128,0,CancellationProbe.NONE,owner);
            assertThrows(GeometryWorkContext.LimitReached.class,()->phase(expired,64));
            assertEquals(0,expiredLedger.currentBytes(),"An expired phase cannot allocate a replacement context");
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void originalHashCacheAcceptsOnlyOfficialPhaseAndChargesItsSharedSpentState() throws Exception {
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var bootstrap=new GeometryWorkContext(16,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            var hashes=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture.OriginalInputHashes(bootstrap);
            bootstrap.visit(GeometryWorkContext.Kind.BIND,3);
            var refine=phase(bootstrap,128);refine.visit(GeometryWorkContext.Kind.COPY,20);
            assertTrue(refine.visits()>16);
            hashes.requireContext(refine);
            var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var identity=new Object();long before=refine.visits();
            String hash=hashes.single(identity,image);
            assertTrue(refine.visits()>before);assertEquals(refine.visits(),bootstrap.visits());
            var foreign=new GeometryWorkContext(128,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            assertThrows(IllegalArgumentException.class,()->hashes.requireContext(foreign));
            assertEquals(hash,hashes.single(identity,image));assertEquals(1,hashes.singleImagesHashed());
        }
    }
}
