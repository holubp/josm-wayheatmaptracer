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
    /** Diagnostics-only authority; it never authorizes a preview or edit. */
    private record CancellationDiagnosticOwner(Owner owner, long jobSequence,
        AlignmentJob.AttemptSnapshot snapshot, String diagnosticAttemptIdentity) { }
    private CancellationDiagnosticOwner cancellationDiagnosticOwner;

    /** A new diagnostic attempt may fail before opening a preview owner; revoke only the old sidecar token. */
    public synchronized void revokeCancellationDiagnostics() {
        cancellationDiagnosticOwner = null;
    }

    /** Starts an explicitly owned cancellation-only diagnostic channel. */
    public synchronized <I> AlignmentJob.StartResult<R> startDetached(Owner owner,
        AlignmentJob.DetachedCapture<I> capture,AlignmentJob.DetachedWorker<I,R> worker,
        AlignmentJob.PreviewPublisher<R> publisher,String diagnosticAttemptIdentity,
        java.util.function.Consumer<AlignmentJob.CancellationDiagnosticEvent> diagnostics){
        requireCurrent(owner);
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (diagnosticAttemptIdentity == null || diagnosticAttemptIdentity.isBlank()) {
            throw new IllegalArgumentException("Diagnostic attempt identity is required");
        }
        cancellationDiagnosticOwner = null;
        try {
            var result = job.startDetached(() -> {
                var captured = capture.capture();
                cancellationDiagnosticOwner = new CancellationDiagnosticOwner(owner,
                    job.currentAttempt().sequence(), captured.snapshot(), diagnosticAttemptIdentity);
                return captured;
            }, worker, attempt -> publishSuccessIfCurrent(owner, attempt, publisher), diagnostics);
            if (!result.started()) cancellationDiagnosticOwner = null;
            return result;
        } catch (RuntimeException failure) {
            cancellationDiagnosticOwner = null;
            throw failure;
        }
    }
    /** Atomically consumes only this cancelled attempt's diagnostics publication authority. */
    public synchronized boolean publishCancellationDiagnosticsIfLatest(Owner owner,
        AlignmentJob.CancellationDiagnosticEvent event,String diagnosticAttemptIdentity,Runnable publish){
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(publish, "publish");
        var token = cancellationDiagnosticOwner;
        if (token == null || !token.owner().equals(owner)
                || !token.diagnosticAttemptIdentity().equals(diagnosticAttemptIdentity)
                || token.jobSequence() != event.sequence() || !token.snapshot().equals(event.snapshot())
                || !job.cancellationDiagnosticCurrent(event.sequence(), event.snapshot())) return false;
        Runnable closeWindow = isCurrent(owner) ? currentWindowClose : null;
        cancellationDiagnosticOwner = null;
        if (isCurrent(owner)) close(owner);
        publish.run();
        if (closeWindow != null) closeWindow.run();
        return true;
    }

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
        cancellationDiagnosticOwner = null;
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
        cancellationDiagnosticOwner = null;
        return job.start(capture, worker, attempt -> publishSuccessIfCurrent(owner, attempt, publisher));
    }

    /** Starts a detached typed worker only for the current plugin-wide owner. */
    public synchronized <I> AlignmentJob.StartResult<R> startDetached(Owner owner,
            AlignmentJob.DetachedCapture<I> capture, AlignmentJob.DetachedWorker<I, R> worker,
            AlignmentJob.PreviewPublisher<R> publisher) {
        requireCurrent(owner);
        cancellationDiagnosticOwner = null;
        return job.startDetached(capture, worker, attempt -> publishSuccessIfCurrent(owner, attempt, publisher));
    }

    /** Registration, immutable-attempt validation and publication share the same session monitor. */
    private synchronized void publishSuccessIfCurrent(Owner owner, AlignmentJob.Attempt<R> attempt,
            AlignmentJob.PreviewPublisher<R> publisher) {
        if (!isCurrent(owner) || job.currentAttempt() != attempt
                || attempt.state() != AlignmentJob.State.PREVIEW_READY || attempt.cancelled()) return;
        var token = cancellationDiagnosticOwner;
        if (token != null && token.owner().equals(owner) && token.jobSequence() == attempt.sequence()
                && token.snapshot().equals(attempt.snapshot())) cancellationDiagnosticOwner = null;
        publisher.publish(attempt);
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
        cancellationDiagnosticOwner = null;
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
