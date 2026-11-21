package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Shared invocation/worker ownership. Cancellation never substitutes for physical/result settlement. */
public final class PreviewWorkGate {
    public static final class Work {
        private final CompletableFuture<Void> settled=new CompletableFuture<>();
        private Work(){ }
    }
    private final AlignmentJob.EventDispatcher dispatcher;
    private final Set<Work> workers=Collections.newSetFromMap(new IdentityHashMap<>());
    private Object owner,pendingKey;
    private Runnable closeOwner,pendingAbandon;
    private long sequence;
    private boolean closed;
    public PreviewWorkGate(AlignmentJob.EventDispatcher dispatcher){this.dispatcher=Objects.requireNonNull(dispatcher);}
    public void adopt(Object next,Runnable close) {
        Runnable previous;
        synchronized(this){
            if(closed||!workers.isEmpty())throw new IllegalStateException("Previous alignment work is still settling");
            previous=closeOwner;owner=Objects.requireNonNull(next);closeOwner=Objects.requireNonNull(close);
        }
        if(previous!=null)previous.run();
    }
    synchronized Runnable detachOwner(){var previous=closeOwner;owner=null;closeOwner=null;return previous;}
    public synchronized boolean isCurrent(Object expected){return !closed&&owner==expected;}
    public synchronized void release(Object expected){if(owner==expected){owner=null;closeOwner=null;}}
    public synchronized Work beginWork(Object expected) {
        if(!isCurrent(expected)||!workers.isEmpty())throw new IllegalStateException("The preview owner is stale or busy");
        var work=new Work();workers.add(work);return work;
    }
    public void workerSettled(Work work) {
        synchronized(this){if(!workers.remove(work))throw new IllegalStateException("Unknown settled preview worker");}
        work.settled.complete(null);
    }
    public synchronized CompletionStage<Void> settlement(){return CompletableFuture.allOf(workers.stream().map(w->w.settled).toArray(CompletableFuture[]::new));}
    /** Returns true for the unchanged idle fast path; deferred intent remains the caller's frozen input. */
    public boolean requestInvocation(Object key,Runnable resume,Runnable abandon,Supplier<CompletionStage<Void>> otherWork) {
        Runnable oldClose,oldAbandon;long request;
        synchronized(this){
            if(closed)throw new IllegalStateException("The preview owner is closed");
            request=++sequence;oldClose=closeOwner;oldAbandon=pendingAbandon;owner=null;closeOwner=null;pendingKey=null;pendingAbandon=null;
        }
        if(oldAbandon!=null)oldAbandon.run();if(oldClose!=null)oldClose.run();
        var wait=CompletableFuture.allOf(settlement().toCompletableFuture(),otherWork.get().toCompletableFuture());
        synchronized(this){
            if(closed||request!=sequence){abandon.run();return false;}
            if(wait.isDone())return true;
            pendingKey=Objects.requireNonNull(key);pendingAbandon=Objects.requireNonNull(abandon);
        }
        wait.whenComplete((ignored,failure)->dispatcher.dispatch(()->{
            synchronized(this){if(closed||request!=sequence||pendingKey!=key)return;pendingKey=null;pendingAbandon=null;}
            resume.run();
        }));
        return false;
    }
    public void cancelPending(Object key) {
        Runnable abandon;
        synchronized(this){if(pendingKey!=key)return;sequence++;pendingKey=null;abandon=pendingAbandon;pendingAbandon=null;}
        if(abandon!=null)abandon.run();
    }
    public void close() {
        Runnable close,abandon;
        synchronized(this){if(closed)return;closed=true;sequence++;close=closeOwner;abandon=pendingAbandon;owner=null;pendingKey=null;closeOwner=null;pendingAbandon=null;}
        if(close!=null)close.run();if(abandon!=null)abandon.run();
    }
}
