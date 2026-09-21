package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/**
 * Owns one detached background alignment attempt and prevents stale late results from publishing.
 *
 * <p>The caller captures the immutable {@link AttemptSnapshot} on JOSM's event thread before this
 * class submits compute work. Workers receive that snapshot only; they have no live-data or apply
 * API. Publication is dispatched back to the supplied event dispatcher after attempt identity and
 * cancellation have been checked again.</p>
 *
 * @param <R> immutable preview payload type
 */
public final class AlignmentJob<R> implements AutoCloseable {
    /** Explicit observable lifecycle states for an individual attempt. */
    public enum State {
        CAPTURING, ACQUIRING, INFERRING, REFINING, VALIDATING, PREVIEW_READY, CANCELLED, FAILED;

        /** Returns whether a state cannot advance further. */
        public boolean terminal() {
            return this == PREVIEW_READY || this == CANCELLED || this == FAILED;
        }
    }

    /** Immutable, redacted attempt identity passed to a background worker. */
    public record AttemptSnapshot(String attemptId, String sourceIdentity, String settingsHash,
        String contentHash) {
        /** Validates immutable attempt identity components. */
        public AttemptSnapshot {
            require(attemptId, "attemptId");
            require(sourceIdentity, "sourceIdentity");
            require(settingsHash, "settingsHash");
            require(contentHash, "contentHash");
        }
    }

    /** Immutable attempt-owned state, including an optional preview result. */
    public record Attempt<R>(long sequence, AttemptSnapshot snapshot, State state, boolean cancelled,
        R result, String failureReason) {
        /** Validates a complete state transition snapshot. */
        public Attempt {
            if (sequence < 1 || snapshot == null || state == null || failureReason == null
                || state == State.PREVIEW_READY && result == null
                || state != State.PREVIEW_READY && result != null) {
                throw new IllegalArgumentException("Attempt state is inconsistent");
            }
        }
    }

    /** Result of an action invocation: a new attempt or cancellation of the current active one. */
    public record StartResult<R>(Attempt<R> attempt, boolean started, boolean cancelledExisting) {
        /** Validates a result that always identifies an attempt. */
        public StartResult {
            if (attempt == null || started == cancelledExisting) {
                throw new IllegalArgumentException("Start result must be exactly started or cancelled");
            }
        }
    }

    /** Captures detached values synchronously on the caller's event thread. */
    @FunctionalInterface
    public interface Capture {
        /** Returns the immutable attempt snapshot. */
        AttemptSnapshot capture();
    }

    /** One immutable attempt identity and its caller-owned detached worker payload. */
    public record CapturedAttempt<I>(AttemptSnapshot snapshot, I input) {
        /** Rejects incomplete captures before any work is submitted. */
        public CapturedAttempt {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(input, "input");
        }
    }

    /** Captures an immutable typed payload synchronously on the caller's event thread. */
    @FunctionalInterface
    public interface DetachedCapture<I> {
        /** Returns the attempt identity together with its sole worker input. */
        CapturedAttempt<I> capture();
    }

    /** Performs side-effect-free work using only the captured typed payload. */
    @FunctionalInterface
    public interface DetachedWorker<I, R> {
        /** Computes one immutable preview from the captured input. */
        R compute(I input, JobContext context) throws Exception;
    }

    /** Performs side-effect-free background acquisition, inference, refinement, and validation. */
    @FunctionalInterface
    public interface Worker<R> {
        /** Computes an immutable preview payload from a detached snapshot. */
        R compute(AttemptSnapshot snapshot, JobContext context) throws Exception;
    }

    /** Receives a ready immutable preview only on the event dispatcher. */
    @FunctionalInterface
    public interface PreviewPublisher<R> {
        /** Publishes a current preview-ready attempt. */
        void publish(Attempt<R> attempt);
    }

    /** Minimal event-loop adapter so the job is testable without creating JOSM UI objects. */
    @FunctionalInterface
    public interface EventDispatcher {
        /** Schedules event-thread publication. */
        void dispatch(Runnable task);

        /** Returns a synchronous dispatcher suitable only for focused deterministic tests. */
        static EventDispatcher direct() {
            return Runnable::run;
        }
    }

    /** Worker-visible transition and cancellation boundary. */
    public static final class JobContext implements CancellationProbe {
        private final BooleanSupplier cancelled;
        private final Consumer<State> transition;
        private final AtomicBoolean cancellationSignalled = new AtomicBoolean();
        private final CopyOnWriteArrayList<Runnable> cancellationListeners =
                new CopyOnWriteArrayList<>();

        private JobContext(BooleanSupplier cancelled, Consumer<State> transition) {
            this.cancelled = cancelled;
            this.transition = transition;
        }

        /** Records a nonterminal numerical phase for the owning current attempt. */
        public void transition(State state) {
            if (state == null || state.terminal() || state == State.CAPTURING) {
                throw new IllegalArgumentException("Workers may transition only to active compute states");
            }
            transition.accept(state);
        }

        /** Returns whether this attempt was cancelled, superseded, or its layer was destroyed. */
        @Override
        public boolean cancelled() {
            return cancellationSignalled.get() || cancelled.getAsBoolean();
        }

        @Override
        public Registration onCancellation(Runnable callback) {
            Objects.requireNonNull(callback, "callback");
            if (cancelled()) {
                callback.run();
                return () -> { };
            }
            cancellationListeners.add(callback);
            if (cancelled() && cancellationListeners.remove(callback)) {
                callback.run();
                return () -> { };
            }
            return () -> cancellationListeners.remove(callback);
        }

        private void signalCancellation() {
            if (cancellationSignalled.compareAndSet(false, true)) {
                cancellationListeners.forEach(Runnable::run);
                cancellationListeners.clear();
            }
        }
    }

    private final EventDispatcher dispatcher;
    private final ExecutorService executor;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicReference<Attempt<R>> current = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentHashMap<Long, JobContext> contexts = new ConcurrentHashMap<>();

    /** Creates one daemon worker with capacity for one pending attempt; overload fails explicitly. */
    public AlignmentJob(EventDispatcher dispatcher) {
        this(dispatcher, new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), new JobThreadFactory(), new ThreadPoolExecutor.AbortPolicy()));
    }

    /** Creates a job with an injected executor for lifecycle integration tests. */
    AlignmentJob(EventDispatcher dispatcher, ExecutorService executor) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Starts one detached attempt, or cancels the current active attempt on a repeated invocation.
     *
     * @param capture event-thread capture callback
     * @param worker pure background worker
     * @param publisher event-thread preview publisher
     * @return the owned new attempt (possibly cancelled during capture), or the active attempt cancelled
     */
    public StartResult<R> start(Capture capture, Worker<R> worker, PreviewPublisher<R> publisher) {
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(publisher, "publisher");
        requireOpen();
        Attempt<R> existing = current.get();
        if (existing != null && !existing.state().terminal()) {
            Attempt<R> cancelled = cancelAttempt(existing, "cancelled by repeated invocation");
            return new StartResult<>(cancelled, false, true);
        }

        long next = sequence.incrementAndGet();
        Attempt<R> capturing = new Attempt<>(next, provisionalSnapshot(next), State.CAPTURING, false, null, "");
        current.set(capturing);
        final AttemptSnapshot snapshot;
        try {
            snapshot = Objects.requireNonNull(capture.capture(), "capture result");
        } catch (RuntimeException exception) {
            Attempt<R> failed = new Attempt<>(next, capturing.snapshot(), State.FAILED, false, null,
                safeFailure(exception));
            current.compareAndSet(capturing, failed);
            throw exception;
        }
        Attempt<R> acquiring = new Attempt<>(next, snapshot, State.ACQUIRING, false, null, "");
        if (!current.compareAndSet(capturing, acquiring)) {
            Attempt<R> active = current.get();
            Attempt<R> abandoned = active != null && active.sequence() == next ? active
                : new Attempt<>(next, snapshot, State.CANCELLED, true, null,
                    "superseded during capture");
            return new StartResult<>(abandoned, true, false);
        }
        try {
            executor.execute(() -> runWorker(acquiring, worker, publisher));
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            transitionIfCurrent(next, State.FAILED, null, safeFailure(exception));
            throw exception;
        }
        return new StartResult<>(acquiring, true, false);
    }

    /** Starts one attempt whose complete typed payload is captured before worker submission. */
    public <I> StartResult<R> startDetached(DetachedCapture<I> capture,
            DetachedWorker<I, R> worker, PreviewPublisher<R> publisher) {
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(publisher, "publisher");
        requireOpen();
        Attempt<R> existing = current.get();
        if (existing != null && !existing.state().terminal()) {
            Attempt<R> cancelled = cancelAttempt(existing, "cancelled by repeated invocation");
            return new StartResult<>(cancelled, false, true);
        }
        long next = sequence.incrementAndGet();
        Attempt<R> capturing = new Attempt<>(next, provisionalSnapshot(next), State.CAPTURING,
                false, null, "");
        current.set(capturing);
        final CapturedAttempt<I> captured;
        try {
            captured = Objects.requireNonNull(capture.capture(), "capture result");
        } catch (RuntimeException exception) {
            Attempt<R> failed = new Attempt<>(next, capturing.snapshot(), State.FAILED,
                    false, null, safeFailure(exception));
            current.compareAndSet(capturing, failed);
            throw exception;
        }
        AttemptSnapshot snapshot = captured.snapshot();
        Attempt<R> acquiring = new Attempt<>(next, snapshot, State.ACQUIRING, false, null, "");
        if (!current.compareAndSet(capturing, acquiring)) {
            Attempt<R> active = current.get();
            Attempt<R> abandoned = active != null && active.sequence() == next ? active
                    : new Attempt<>(next, snapshot, State.CANCELLED, true, null,
                            "superseded during capture");
            return new StartResult<>(abandoned, true, false);
        }
        try {
            executor.execute(() -> runDetachedWorker(acquiring, captured.input(), worker, publisher));
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            transitionIfCurrent(next, State.FAILED, null, safeFailure(exception));
            throw exception;
        }
        return new StartResult<>(acquiring, true, false);
    }

    /** Cancels the active attempt without applying or publishing partial geometry. */
    public void cancel() {
        Attempt<R> attempt = current.get();
        if (attempt != null && !attempt.state().terminal()) {
            cancelAttempt(attempt, "cancelled");
        }
    }

    /** Returns the current immutable state, or {@code null} before the first attempt. */
    public Attempt<R> currentAttempt() {
        return current.get();
    }

    /** Cancels outstanding work and rejects subsequent starts when a layer or plugin is destroyed. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cancel();
            executor.shutdownNow();
        }
    }

    private void runWorker(Attempt<R> started, Worker<R> worker, PreviewPublisher<R> publisher) {
        JobContext context = new JobContext(() -> isCancelled(started.sequence()),
            state -> transitionIfCurrent(started.sequence(), state, null, ""));
        contexts.put(started.sequence(), context);
        if (isCancelled(started.sequence())) {
            context.signalCancellation();
        }
        try {
            context.checkpoint();
            context.transition(State.INFERRING);
            R result = worker.compute(started.snapshot(), context);
            context.checkpoint();
            if (result == null) {
                throw new IllegalStateException("Alignment worker returned no preview");
            }
            context.transition(State.VALIDATING);
            dispatcher.dispatch(() -> publishIfCurrent(started.sequence(), result, publisher));
        } catch (CancellationException exception) {
            cancelAttempt(started, "cancelled");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancelAttempt(started, "interrupted");
        } catch (Exception exception) {
            String failure = safeFailure(exception);
            Logging.warn("[WayHeatmapTracer] Preview worker failed safely: " + failure);
            transitionIfCurrent(started.sequence(), State.FAILED, null, failure);
        } finally {
            contexts.remove(started.sequence(), context);
        }
    }

    private boolean isCancelled(long attemptSequence) {
        Attempt<R> attempt = current.get();
        return closed.get() || attempt == null || attempt.sequence() != attemptSequence || attempt.cancelled();
    }

    private <I> void runDetachedWorker(Attempt<R> started, I input,
            DetachedWorker<I, R> worker, PreviewPublisher<R> publisher) {
        JobContext context = new JobContext(() -> isCancelled(started.sequence()),
            state -> transitionIfCurrent(started.sequence(), state, null, ""));
        contexts.put(started.sequence(), context);
        if (isCancelled(started.sequence())) {
            context.signalCancellation();
        }
        try {
            context.checkpoint();
            context.transition(State.INFERRING);
            R result = worker.compute(input, context);
            context.checkpoint();
            if (result == null) {
                throw new IllegalStateException("Alignment worker returned no preview");
            }
            context.transition(State.VALIDATING);
            dispatcher.dispatch(() -> publishIfCurrent(started.sequence(), result, publisher));
        } catch (CancellationException exception) {
            cancelAttempt(started, "cancelled");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancelAttempt(started, "interrupted");
        } catch (Exception exception) {
            String failure = safeFailure(exception);
            Logging.warn("[WayHeatmapTracer] Preview worker failed safely: " + failure);
            transitionIfCurrent(started.sequence(), State.FAILED, null, failure);
        } finally {
            contexts.remove(started.sequence(), context);
        }
    }

    private void publishIfCurrent(long attemptSequence, R result, PreviewPublisher<R> publisher) {
        Attempt<R> attempt = current.get();
        if (closed.get() || attempt == null || attempt.sequence() != attemptSequence || attempt.cancelled()) {
            return;
        }
        Attempt<R> ready = new Attempt<>(attempt.sequence(), attempt.snapshot(), State.PREVIEW_READY,
            false, result, "");
        if (current.compareAndSet(attempt, ready)) {
            publisher.publish(ready);
        }
    }

    private Attempt<R> cancelAttempt(Attempt<R> attempt, String reason) {
        Attempt<R> updated = current.updateAndGet(currentAttempt -> currentAttempt == null
            || currentAttempt.sequence() != attempt.sequence() || currentAttempt.state().terminal() ? currentAttempt
                : new Attempt<>(currentAttempt.sequence(), currentAttempt.snapshot(), State.CANCELLED,
                    true, null, reason));
        JobContext context = contexts.get(attempt.sequence());
        if (context != null) {
            context.signalCancellation();
        }
        return updated;
    }

    private void transitionIfCurrent(long attemptSequence, State state, R result, String failureReason) {
        current.updateAndGet(attempt -> attempt == null || attempt.sequence() != attemptSequence
            || attempt.cancelled() || attempt.state().terminal() ? attempt
                : new Attempt<>(attempt.sequence(), attempt.snapshot(), state, false, result, failureReason));
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Alignment job is closed");
        }
    }

    private static AttemptSnapshot provisionalSnapshot(long sequence) {
        return new AttemptSnapshot("capturing-" + sequence, "capturing", "capturing", "capturing");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static String safeFailure(Exception exception) {
        String type = exception.getClass().getSimpleName();
        if (type == null || type.isBlank()) {
            return "alignment-failure";
        }
        if (!(exception instanceof IllegalArgumentException)) {
            return type;
        }
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return type;
        }
        String normalized = message.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').strip();
        if (normalized.length() > 240) {
            normalized = normalized.substring(0, 237) + "...";
        }
        return type + ": " + normalized;
    }

    private static final class JobThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "wayheatmaptracer-alignment");
            thread.setDaemon(true);
            return thread;
        }
    }
}
