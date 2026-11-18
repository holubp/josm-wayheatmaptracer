package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Deterministic owner/event ordering; native engine completes inside the detached worker. */
class CorridorCancellationOwnerTest {
    private enum StartFamily { SNAPSHOT, DETACHED, DIAGNOSTIC }

    @Test void pausedHistoricalSuccessCannotPublishUnderReusedOwnerBeforeNewAReceipt()throws Exception{
        for(var old:List.of(StartFamily.SNAPSHOT,StartFamily.DETACHED))
            pausedSuccessRace(old,StartFamily.DIAGNOSTIC,true);
    }
    @Test void pausedASuccessCannotClearDifferentOwnerOrSameOwnerCancellationReceipt()throws Exception{
        for(boolean sameOwner:List.of(false,true))pausedSuccessRace(StartFamily.DIAGNOSTIC,StartFamily.DIAGNOSTIC,sameOwner);
    }
    @Test void pausedASuccessCannotPublishOverNewHistoricalStart()throws Exception{
        for(var next:List.of(StartFamily.SNAPSHOT,StartFamily.DETACHED))
            for(boolean sameOwner:List.of(false,true))pausedSuccessRace(StartFamily.DIAGNOSTIC,next,sameOwner);
    }
    private static void pausedSuccessRace(StartFamily oldFamily,StartFamily nextFamily,boolean sameOwner)throws Exception{
        try(var h=new Harness()){
            var oldPublished=new AtomicInteger();var newPublished=new AtomicInteger();
            startFamily(h,oldFamily,h.owner,context->"old",oldPublished);
            h.executor.queue.remove().run();var oldDelivery=h.events.remove();
            Thread delivery=new Thread(oldDelivery,"paused-old-success");
            synchronized(h.session){
                delivery.start();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(h.session.currentAttempt().state()!=AlignmentJob.State.PREVIEW_READY&&System.nanoTime()<deadline)
                    java.util.concurrent.locks.LockSupport.parkNanos(100_000);
                assertEquals(AlignmentJob.State.PREVIEW_READY,h.session.currentAttempt().state(),"Pause after the actual job CAS while the callback waits for this session monitor");
                var nextOwner=sameOwner?h.owner:h.session.open(()->{});
                startFamily(h,nextFamily,nextOwner,context->{
                    if(nextFamily==StartFamily.DIAGNOSTIC)throw NativeCancellation.VALUE;
                    return "new";
                },newPublished);
            }
            delivery.join(5000);assertFalse(delivery.isAlive());
            assertEquals(0,oldPublished.get(),"Old success must not publish after another start, even with the same owner");
            h.executor.queue.remove().run();h.events.remove().run();
            if(nextFamily==StartFamily.DIAGNOSTIC){
                assertEquals(1,h.published.size(),"Old success must not revoke the newer completed-work token");
                assertNull(h.session.currentAttempt().result());assertEquals(0,newPublished.get());
            }else{assertEquals(1,newPublished.get());assertEquals("new",h.session.currentAttempt().result());}
        }
    }
    private static void startFamily(Harness h,StartFamily family,PreviewSessionController.Owner owner,
            CancelWorker worker,AtomicInteger previews){
        if(family==StartFamily.SNAPSHOT)h.session.start(owner,()->h.snapshot,(snapshot,context)->worker.compute(context),a->previews.incrementAndGet());
        else if(family==StartFamily.DETACHED)h.session.startDetached(owner,
            ()->new AlignmentJob.CapturedAttempt<>(h.snapshot,"input"),(input,context)->worker.compute(context),a->previews.incrementAndGet());
        else h.session.startDetached(owner,()->new AlignmentJob.CapturedAttempt<>(h.snapshot,"input"),
            (input,context)->worker.compute(context),a->previews.incrementAndGet(),"new-diagnostic",
            event->h.session.publishCancellationDiagnosticsIfLatest(owner,event,"new-diagnostic",()->h.published.add(event)));
    }
    private static final class NativeCancellation {
        static final CorridorFinalizationCancelledException VALUE=CorridorPolicyFixture.finalizationCancellation();
    }
    @Test void completedEngineCancellationPublishesOnlyOwnedDiagnosticsBeforeGenericFallback(){
        var executor=new QueueExecutor();var events=new ArrayDeque<Runnable>();var previews=new AtomicInteger();
        var published=new ArrayList<AlignmentJob.CancellationDiagnosticEvent>();
        var f=CorridorPolicyFixture.straight(true,false,Set.of());
        try(var session=new PreviewSessionController<String>(events::add,executor)){
            var owner=session.open(()->{});
            var snapshot=new AlignmentJob.AttemptSnapshot(f.network().snapshotId(),"synthetic-source",f.request().settingsHash(),f.network().canonicalHash());
            session.startDetached(owner,()->new AlignmentJob.CapturedAttempt<>(snapshot,f),
                (input,context)->{
                    CancellationProbe probe=()->StackWalker.getInstance().walk(s->s.anyMatch(frame->frame.getClassName().endsWith(".FinalGeometryEvaluator")));
                    new ModernTracePipeline(new CorridorEngineAdapter("native")).runWithUsage(input.request(),input.evidence(),input.network(),input.options(),probe);
                    return "not-a-cancelled-preview";
                },attempt->previews.incrementAndGet(),"diagnostic-owner-1",
                event->session.publishCancellationDiagnosticsIfLatest(owner,event,"diagnostic-owner-1",()->published.add(event)));
            executor.queue.remove().run();
            assertEquals(AlignmentJob.State.CANCELLED,session.currentAttempt().state());assertNull(session.currentAttempt().result());
            assertEquals(1,events.size(),"Recognized finalization cancellation must dispatch bounded completed work");
            events.remove().run();assertEquals(1,published.size());assertEquals(0,previews.get());
            var summary=published.get(0).summary();assertTrue(summary.usage().transitions()>0);
            assertEquals("CANCELLED_AT_FINALIZATION",summary.overallOutcome());assertEquals("UNAVAILABLE",summary.finalGeometryAvailability());
            assertFalse(session.close(owner),"Callback-first must close generic fallback publication authority");
        }
    }
    @Test void windowFirstKeepsOnlyDiagnosticsTokenAndRepeatedDeliveryCannotPublish(){
        try(var h=new Harness()){
            h.start(context->{h.session.close(h.owner);throw NativeCancellation.VALUE;},false);
            h.executor.queue.remove().run();assertFalse(h.session.isCurrent(h.owner));
            var delivery=h.events.remove();delivery.run();delivery.run();
            assertEquals(1,h.published.size());assertEquals(0,h.previews.get());
            assertNull(h.session.currentAttempt().result());
        }
    }
    @Test void queuedOlderCallbackCannotTouchNewOwner(){
        try(var h=new Harness()){
            h.start(context->{throw NativeCancellation.VALUE;},false);h.executor.queue.remove().run();
            var newer=h.session.open(()->{});assertEquals(1,h.windowCloses.get(),"Opening the new owner closes the old window");
            h.events.remove().run();
            assertTrue(h.session.isCurrent(newer));assertTrue(h.published.isEmpty());assertEquals(1,h.windowCloses.get(),"Stale delivery must not close another window");
        }
    }
    @Test void closeAllAndShutdownRevokeQueuedDiagnostics(){
        for(boolean shutdown:List.of(false,true))try(var h=new Harness()){
            h.start(context->{throw NativeCancellation.VALUE;},false);h.executor.queue.remove().run();
            if(shutdown)h.session.close();else h.session.closeAll();
            h.events.remove().run();assertTrue(h.published.isEmpty());
        }
    }
    @Test void everyWorkerPhaseCancelsLatestSnapshotWithoutPreview(){
        for(var phase:List.of(AlignmentJob.State.ACQUIRING,AlignmentJob.State.INFERRING,
                AlignmentJob.State.REFINING,AlignmentJob.State.VALIDATING))try(var h=new Harness()){
            h.start(context->{context.transition(phase);h.session.close(h.owner);throw NativeCancellation.VALUE;},false);
            h.executor.queue.remove().run();h.events.remove().run();
            assertEquals(1,h.published.size());assertEquals(AlignmentJob.State.CANCELLED,h.session.currentAttempt().state());
            assertNull(h.session.currentAttempt().result());assertEquals(0,h.previews.get());
        }
    }
    @Test void mismatchedSourceOrPlainCancellationCannotOfferCompletedWork(){
        for(boolean mismatch:List.of(false,true))try(var h=new Harness()){
            h.start(context->{if(mismatch)throw NativeCancellation.VALUE;throw new CancellationException();},mismatch);
            h.executor.queue.remove().run();assertTrue(h.events.isEmpty());assertTrue(h.published.isEmpty());
            assertEquals(AlignmentJob.State.CANCELLED,h.session.currentAttempt().state());
        }
    }
    @Test void terminalSuccessThenLateEventCannotReplaceResult(){
        try(var h=new Harness()){
            h.start(context->{throw NativeCancellation.VALUE;},false);h.executor.queue.remove().run();
            var old=h.events.remove();
            h.session.startDetached(h.owner,()->new AlignmentJob.CapturedAttempt<>(h.snapshot,"next"),
                (input,context)->"ready",a->h.previews.incrementAndGet());
            h.executor.queue.remove().run();old.run();h.events.remove().run();
            assertTrue(h.published.isEmpty());assertEquals(1,h.previews.get());
            assertEquals(AlignmentJob.State.PREVIEW_READY,h.session.currentAttempt().state());
        }
    }
    @FunctionalInterface private interface CancelWorker {String compute(AlignmentJob.JobContext context)throws Exception;}
    private static final class Harness implements AutoCloseable {
        final QueueExecutor executor=new QueueExecutor();final ArrayDeque<Runnable> events=new ArrayDeque<>();
        final AtomicInteger previews=new AtomicInteger(),windowCloses=new AtomicInteger();
        final ArrayList<AlignmentJob.CancellationDiagnosticEvent> published=new ArrayList<>();
        final PreviewSessionController<String> session=new PreviewSessionController<>(events::add,executor);
        final PreviewSessionController.Owner owner=session.open(windowCloses::incrementAndGet);
        final CorridorPolicyFixture.Fixture fixture=CorridorPolicyFixture.straight(true,false,Set.of());
        final AlignmentJob.AttemptSnapshot snapshot=new AlignmentJob.AttemptSnapshot(fixture.network().snapshotId(),
            "synthetic-source",fixture.request().settingsHash(),fixture.network().canonicalHash());
        void start(CancelWorker worker,boolean mismatch){
            var selected=mismatch?new AlignmentJob.AttemptSnapshot(snapshot.attemptId(),snapshot.sourceIdentity(),"different",snapshot.contentHash()):snapshot;
            session.startDetached(owner,()->new AlignmentJob.CapturedAttempt<>(selected,"input"),
                (input,context)->worker.compute(context),a->previews.incrementAndGet(),"owner-diagnostic",
                event->session.publishCancellationDiagnosticsIfLatest(owner,event,"owner-diagnostic",()->published.add(event)));
        }
        @Override public void close(){session.close();}
    }
    private static final class QueueExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> queue=new ArrayDeque<>();boolean closed;
        @Override public void execute(Runnable task){if(closed)throw new RejectedExecutionException();queue.add(task);}
        @Override public void shutdown(){closed=true;}@Override public List<Runnable> shutdownNow(){closed=true;var pending=List.copyOf(queue);queue.clear();return pending;}
        @Override public boolean isShutdown(){return closed;}@Override public boolean isTerminated(){return closed&&queue.isEmpty();}
        @Override public boolean awaitTermination(long timeout,TimeUnit unit){return isTerminated();}
    }
}
