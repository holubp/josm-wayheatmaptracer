package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Owns the one plugin-wide experimental preview attempt, window, and cleanup authority.
 *
 * @param <R> immutable preview result type
 */
public final class PreviewSessionController<R> implements AutoCloseable {
    /** Immutable authority token for one preview attempt/window sequence. */
    public record Owner(long sequence) {
        /** Rejects non-production owner identities. */
        public Owner {
            if (sequence < 1L) {
                throw new IllegalArgumentException("Preview owner sequence must be positive");
            }
        }
    }

    private final AlignmentJob<R> job;
    private long sequence;
    private Owner currentOwner;
    private Runnable currentWindowClose;

    /** Creates a plugin session with the normal bounded alignment worker. */
    public PreviewSessionController(AlignmentJob.EventDispatcher dispatcher) {
        this.job = new AlignmentJob<>(dispatcher);
    }

    /** Creates a session with a controlled executor for deterministic lifecycle tests. */
    PreviewSessionController(AlignmentJob.EventDispatcher dispatcher, ExecutorService executor) {
        this.job = new AlignmentJob<>(dispatcher, executor);
    }

    /**
     * Supersedes any prior attempt and transfers window/overlay cleanup authority.
     *
     * @param closeWindow callback that closes this owner's current window
     * @return new sole owner
     */
    public synchronized Owner open(Runnable closeWindow) {
        Objects.requireNonNull(closeWindow, "closeWindow");
        Runnable supersededWindow = currentWindowClose;
        job.cancel();
        Owner owner = new Owner(++sequence);
        currentOwner = owner;
        currentWindowClose = closeWindow;
        if (supersededWindow != null) {
            supersededWindow.run();
        }
        return owner;
    }

    /** Transfers the current owner's close callback from progress to preview window. */
    public synchronized void replaceWindow(Owner owner, Runnable closeWindow) {
        requireCurrent(owner);
        currentWindowClose = Objects.requireNonNull(closeWindow, "closeWindow");
    }

    /** Starts a snapshot-based worker only for the current plugin-wide owner. */
    public synchronized AlignmentJob.StartResult<R> start(Owner owner,
            AlignmentJob.Capture capture, AlignmentJob.Worker<R> worker,
            AlignmentJob.PreviewPublisher<R> publisher) {
        requireCurrent(owner);
        return job.start(capture, worker, attempt -> {
            if (isCurrent(owner)) {
                publisher.publish(attempt);
            }
        });
    }

    /** Starts a detached typed worker only for the current plugin-wide owner. */
    public synchronized <I> AlignmentJob.StartResult<R> startDetached(Owner owner,
            AlignmentJob.DetachedCapture<I> capture, AlignmentJob.DetachedWorker<I, R> worker,
            AlignmentJob.PreviewPublisher<R> publisher) {
        requireCurrent(owner);
        return job.startDetached(capture, worker, attempt -> {
            if (isCurrent(owner)) {
                publisher.publish(attempt);
            }
        });
    }

    /** Returns the shared job's current immutable attempt state. */
    public AlignmentJob.Attempt<R> currentAttempt() {
        return job.currentAttempt();
    }

    /** Returns whether this token still owns publication, switching, and cleanup. */
    public synchronized boolean isCurrent(Owner owner) {
        return owner != null && owner.equals(currentOwner);
    }

    /** Requires both owner authority and the action-local expected window identity. */
    public synchronized boolean isCurrentWindow(Owner owner, Object expectedWindow,
            Object activeWindow, boolean displayable) {
        return isCurrent(owner) && expectedWindow == activeWindow && displayable;
    }

    /**
     * Closes one owner. A stale owner cannot cancel or hide the current preview.
     *
     * @return true only when the caller owned the current preview
     */
    public synchronized boolean close(Owner owner) {
        if (!isCurrent(owner)) {
            return false;
        }
        currentOwner = null;
        currentWindowClose = null;
        job.cancel();
        return true;
    }

    /** Closes the current window and cancels the current attempt. */
    public synchronized void closeAll() {
        Runnable closeWindow = currentWindowClose;
        currentOwner = null;
        currentWindowClose = null;
        job.cancel();
        if (closeWindow != null) {
            closeWindow.run();
        }
    }

    /** Cancels and shuts down preview work before running the supplied runtime close action. */
    public synchronized void closeThen(Runnable closeRuntime) {
        Objects.requireNonNull(closeRuntime, "closeRuntime");
        close();
        closeRuntime.run();
    }

    @Override
    public synchronized void close() {
        closeAll();
        job.close();
    }

    private void requireCurrent(Owner owner) {
        if (!isCurrent(owner)) {
            throw new IllegalStateException("The preview owner was superseded");
        }
    }
}
