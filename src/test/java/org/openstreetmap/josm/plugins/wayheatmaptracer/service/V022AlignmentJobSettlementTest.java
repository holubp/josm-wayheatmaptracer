package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class V022AlignmentJobSettlementTest {
    @Test void returnedWorkerStillOwnsItsQueuedResultUntilDiscard() {
        var executor=new QueueExecutor();var publications=new ArrayDeque<Runnable>();var published=new AtomicInteger();
        try(var job=new AlignmentJob<String>(publications::add,executor)) {
            var snapshot=new AlignmentJob.AttemptSnapshot("same-way","source","settings","original");
            job.start(()->snapshot,(input,context)->"actual-output",result->published.incrementAndGet());
            var settled=job.settlement().toCompletableFuture();assertFalse(settled.isDone());executor.tasks.remove().run();
            assertFalse(settled.isDone());assertEquals(1,publications.size());job.cancel();assertEquals(snapshot,job.currentAttempt().snapshot());
            assertFalse(settled.isDone(),"Cancellation state cannot release a queued result");publications.remove().run();
            assertTrue(settled.isDone());assertEquals(0,published.get());
        }
    }
    @Test void cancelledWorkerIgnoringInterruptDoesNotSettleEarly() throws Exception {
        var executor=new QueueExecutor();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var ignored=new CountDownLatch(1);var published=new AtomicInteger();
        try(var job=new AlignmentJob<String>(Runnable::run,executor)) {
            var snapshot=new AlignmentJob.AttemptSnapshot("same-way","source","settings","original");
            job.start(()->snapshot,(input,context)->{
                entered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException interrupt){ignored.countDown();}
                return "late-output";
            },result->published.incrementAndGet());
            var settled=job.settlement().toCompletableFuture();var thread=new Thread(executor.tasks.remove());thread.start();
            assertTrue(entered.await(3,TimeUnit.SECONDS));job.cancel();thread.interrupt();assertTrue(ignored.await(3,TimeUnit.SECONDS));
            assertFalse(settled.isDone());assertEquals(snapshot,job.currentAttempt().snapshot());release.countDown();thread.join(3000);
            assertFalse(thread.isAlive());assertTrue(settled.isDone());assertEquals(0,published.get());
        }finally{release.countDown();}
    }
    @Test void throwingInlinePublicationStillSettlesExactlyOnce() {
        var executor=new QueueExecutor();try(var job=new AlignmentJob<String>(Runnable::run,executor)) {
            job.start(()->new AlignmentJob.AttemptSnapshot("same-way","source","settings","original"),(input,context)->"actual-output",result->{throw new IllegalStateException("synthetic publication failure");});
            var settled=job.settlement().toCompletableFuture();executor.tasks.remove().run();assertTrue(settled.isDone());
        }
    }
    @Test void workerErrorKeepsItsOriginalPropagationContract() {
        var executor=new QueueExecutor();try(var job=new AlignmentJob<String>(Runnable::run,executor)) {
            job.start(()->new AlignmentJob.AttemptSnapshot("same-way","source","settings","original"),(input,context)->{throw new AssertionError("synthetic worker error");},result->fail());
            var settled=job.settlement().toCompletableFuture();assertThrows(AssertionError.class,()->executor.tasks.remove().run());assertTrue(settled.isDone());
        }
    }
    @Test void shutdownOnlySettlesTasksTheExecutorProvesNeverStarted() {
        var executor=new QueueExecutor();var calls=new AtomicInteger();var job=new AlignmentJob<String>(Runnable::run,executor);
        job.start(()->new AlignmentJob.AttemptSnapshot("same-way","source","settings","original"),(input,context)->{calls.incrementAndGet();return "none";},result->fail());
        var settled=job.settlement().toCompletableFuture();job.close();assertTrue(settled.isDone());assertEquals(0,calls.get());
    }
    static final class QueueExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>();boolean closed;
        public void execute(Runnable task){if(closed)throw new RejectedExecutionException();tasks.add(task);}
        public void shutdown(){closed=true;}
        public List<Runnable> shutdownNow(){closed=true;var remaining=List.copyOf(tasks);tasks.clear();return remaining;}
        public boolean isShutdown(){return closed;}public boolean isTerminated(){return closed&&tasks.isEmpty();}
        public boolean awaitTermination(long timeout,TimeUnit unit){return isTerminated();}
    }
}
