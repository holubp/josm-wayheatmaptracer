package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded single-flight coordinator for every plugin-direct managed tile consumer. */
public final class TileFetchCoordinator implements AutoCloseable {
    private final ManagedTileTransport transport;
    private final ManagedTileCache diskCache;
    private final TileDecoderClassifier classifier;
    private final TileReliabilityPolicy policy;
    private final Clock clock;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor scheduler;
    private final ConcurrentHashMap<FlightKey, Flight> flights = new ConcurrentHashMap<>();
    // Dedicated ownership/publication fence. Never held for transport, decode or temporary writes.
    // Final rename is deliberately serialized with last detach, generation change and close.
    private final Object publicationFence = new Object();
    private final Runnable beforePublication;
    private final ConcurrentHashMap<CacheKey, NegativeState> negatives = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, TileFetchStatus> authBlocked = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Instant> rateLimited = new ConcurrentHashMap<>();
    private final MemoryCache memoryCache;
    private final ArrayDeque<TileFetchResult> recentResults = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicInteger admitted = new AtomicInteger();
    private final AtomicInteger scheduledCallbacks = new AtomicInteger();
    private final AtomicLong transportExecutions = new AtomicLong();
    private final AtomicLong memoryHits = new AtomicLong();
    private final AtomicLong diskHits = new AtomicLong();
    private final AtomicLong negativeHits = new AtomicLong();
    private final AtomicLong joins = new AtomicLong();
    private final AtomicLong retrySuppressed = new AtomicLong();
    private volatile long activeGeneration = -1L;
    private long generationRevision;

    /** Conservative encoded-body allowance for a tile including its one bounded retry. */
    public long maximumTransportBodyAllowancePerTile() {
        return Math.multiplyExact(2L,policy.maximumBodyBytes());
    }

    /**
     * Creates a production coordinator with the system clock.
     *
     * @param transport credentialed transport boundary
     * @param cache validated positive disk cache
     * @param classifier pure PNG classifier
     * @param policy centralized reliability limits
     */
    public TileFetchCoordinator(ManagedTileTransport transport, ManagedTileCache cache,
        TileDecoderClassifier classifier, TileReliabilityPolicy policy) {
        this(transport, cache, classifier, policy, Clock.systemUTC());
    }

    /**
     * Creates a deterministic coordinator with an injectable clock.
     *
     * @param transport credentialed transport boundary
     * @param cache validated positive disk cache
     * @param classifier pure PNG classifier
     * @param policy centralized reliability limits
     * @param clock eligibility and deadline clock
     */
    public TileFetchCoordinator(ManagedTileTransport transport, ManagedTileCache cache,
        TileDecoderClassifier classifier, TileReliabilityPolicy policy, Clock clock) {
        this(transport, cache, classifier, policy, clock, () -> { });
    }

    /** Package-private deterministic boundary after decode/staging, outside the publication fence. */
    TileFetchCoordinator(ManagedTileTransport transport, ManagedTileCache cache,
        TileDecoderClassifier classifier, TileReliabilityPolicy policy, Clock clock, Runnable beforePublication) {
        this.beforePublication = Objects.requireNonNull(beforePublication);
        this.transport = Objects.requireNonNull(transport, "transport");
        this.diskCache = Objects.requireNonNull(cache, "cache");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.memoryCache = new MemoryCache(policy.memoryCacheBytes());
        this.workers = new ThreadPoolExecutor(policy.workerConcurrency(), policy.workerConcurrency(),
            30L, TimeUnit.SECONDS, new PriorityBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "WayHeatmapTracer tile worker");
                thread.setDaemon(true);
                return thread;
            });
        this.scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "WayHeatmapTracer tile retry scheduler");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.setRemoveOnCancelPolicy(true);
    }

    /**
     * Fetches one tile while preserving consumer-specific cancellation over shared work.
     *
     * @param request immutable consumer request
     * @param credentials short-lived credential snapshot
     * @return asynchronous structured result
     */
    public CompletionStage<TileFetchResult> fetch(TileRequest request, CredentialSnapshot credentials) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(credentials, "credentials");
        if (closed.get()) {
            return completedObserved(failure(request, TileFetchStatus.CANCELLED, "closed",
                "Tile acquisition is closed.", null));
        }
        if (request.cancellation().isCancelled()) {
            return completedObserved(failure(request, TileFetchStatus.CANCELLED, "cancelled",
                "Tile acquisition was cancelled.", null));
        }
        if (activeGeneration >= 0L && request.generation().value() != activeGeneration) {
            return completedObserved(failure(request, TileFetchStatus.STALE_GENERATION,
                "stale-generation", "Tile settings changed before acquisition completed.", null));
        }
        CacheKey cacheKey = new CacheKey(request.generation(), request.address());
        if (request.cachePolicy().reads()) {
            TileFetchResult memory = memoryCache.get(cacheKey);
            if (memory != null) {
                memoryHits.incrementAndGet();
                return completedObserved(memory.as(TileFetchStatus.SUCCESS_MEMORY_CACHE, request.purpose()));
            }
        }
        TileFetchResult blocked = blockedResult(request, cacheKey);
        if (blocked != null) {
            return completedObserved(blocked);
        }
        FlightKey flightKey = new FlightKey(cacheKey, request.cachePolicy());
        Flight proposed = new Flight(flightKey, request);
        Flight previous = flights.putIfAbsent(flightKey, proposed);
        return subscriber(previous == null ? proposed : previous, request, credentials, previous != null);
    }

    private void start(Flight flight, CredentialSnapshot credentials) {
        TileFetchResult refused = null;
        synchronized (publicationFence) {
            refused = unavailable(flight);
            if (refused == null) {
                int maximumAdmitted = policy.maximumQueueSize() + policy.workerConcurrency();
                if (admitted.incrementAndGet() > maximumAdmitted) {
                    admitted.decrementAndGet();
                    refused = failure(flight.request, TileFetchStatus.OFFLINE, "queue-full",
                        "Tile acquisition queue is full.", clock.instant().plus(policy.transientFailureTtl()));
                } else flight.admitted = true;
            }
        }
        if (refused != null) { finish(flight, refused); return; }
        try {
            workers.execute(new PrioritizedTask(flight.request.purpose().priority(), sequence.getAndIncrement(), () -> {
                try {
                    TileFetchResult result;
                    try { result = execute(flight, credentials); }
                    catch (RuntimeException ex) {
                        synchronized (publicationFence) {
                            result = unavailable(flight);
                            if (result == null) {
                                result = failure(flight.request, TileFetchStatus.NETWORK_ERROR, "internal-fetch",
                                    "Tile acquisition failed safely.", clock.instant().plus(policy.transientFailureTtl()));
                                rememberFailure(flight.key.key(), result);
                            }
                        }
                    }
                    finish(flight, result);
                } finally {
                    // Retire even an abnormal worker exit; never strand admission or subscribers.
                    if (retire(flight)) flight.result.completeExceptionally(new IllegalStateException("Tile acquisition stopped."));
                }
            }));
        } catch (RejectedExecutionException ex) {
            finish(flight, failure(flight.request, TileFetchStatus.CANCELLED, "closed",
                "Tile acquisition is closed.", null));
        }
    }

    private boolean retire(Flight flight) {
        synchronized (publicationFence) {
            if (flight.terminal) return false;
            flight.terminal = true;
            flight.accepting = false;
            flights.remove(flight.key, flight);
            if (flight.admitted) { flight.admitted = false; admitted.decrementAndGet(); }
            return true;
        }
    }

    private void finish(Flight flight, TileFetchResult result) {
        if (!retire(flight)) return;
        if (!closed.get()) observe(result);
        flight.result.complete(result); // User continuations must never execute under the fence.
    }

    /**
     * Marks one generation active and invalidates stale circuit/negative state.
     *
     * @param generation newly active settings generation
     */
    public void updateActiveGeneration(ManagedTileGeneration generation) {
        long next = Objects.requireNonNull(generation, "generation").value();
        synchronized (publicationFence) {
            if (closed.get() || activeGeneration == next) return;
            activeGeneration = next;
            generationRevision = Math.incrementExact(generationRevision);
            negatives.keySet().removeIf(key -> key.generation().value() != next);
            authBlocked.keySet().removeIf(value -> value != next);
            rateLimited.keySet().removeIf(value -> value != next);
        }
    }

    /**
     * Returns whether the supplied settings generation is the runtime-owned active generation.
     *
     * @param generation captured attempt generation
     * @return true only for the currently active runtime generation
     */
    public boolean isActiveGeneration(ManagedTileGeneration generation) {
        return !closed.get() && generation != null && generation.value() == activeGeneration;
    }

    /** Opaque observation of one continuous generation lifetime; no transport/cache ownership changes. */
    public static final class GenerationStamp {
        private final TileFetchCoordinator owner;
        private final long generation, revision;
        private GenerationStamp(TileFetchCoordinator owner, long generation, long revision) {
            this.owner = owner; this.generation = generation; this.revision = revision;
        }
    }
    public GenerationStamp captureGenerationStamp(ManagedTileGeneration generation) {
        synchronized (publicationFence) {
            if (!isActiveGeneration(generation)) throw new IllegalStateException("Managed source generation is unavailable");
            return new GenerationStamp(this, activeGeneration, generationRevision);
        }
    }
    public boolean isCurrent(GenerationStamp stamp) {
        synchronized (publicationFence) {
            return stamp != null && stamp.owner == this && !closed.get()
                && stamp.generation == activeGeneration && stamp.revision == generationRevision;
        }
    }

    /** Returns the factual currently active generation without starting tile work. */
    public long activeGenerationValue() {
        return activeGeneration;
    }

    /**
     * Schedules one non-blocking eligibility callback; closed coordinators ignore it.
     *
     * @param when earliest callback time
     * @param callback bounded callback action
     */
    public void schedule(Instant when, Runnable callback) {
        if (closed.get() || scheduledCallbacks.incrementAndGet() > policy.maximumQueueSize()) {
            scheduledCallbacks.decrementAndGet();
            return;
        }
        long delay = Math.max(0L, Duration.between(clock.instant(), when).toMillis());
        try {
            scheduler.schedule(() -> {
                try {
                    if (!closed.get()) {
                        callback.run();
                    }
                } finally {
                    scheduledCallbacks.decrementAndGet();
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            scheduledCallbacks.decrementAndGet();
        }
    }

    /**
     * Dispatches bounded non-transport work without allowing callers to run it inline.
     *
     * @param purpose scheduling priority
     * @param callback callback to execute on a tile worker
     * @return true when admitted, false when closed or full
     */
    public boolean dispatch(TilePurpose purpose, Runnable callback) {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(callback, "callback");
        int maximumAdmitted = policy.maximumQueueSize() + policy.workerConcurrency();
        if (closed.get() || admitted.incrementAndGet() > maximumAdmitted) {
            admitted.decrementAndGet();
            return false;
        }
        try {
            workers.execute(new PrioritizedTask(purpose.priority(), sequence.getAndIncrement(), () -> {
                try {
                    callback.run();
                } finally {
                    admitted.decrementAndGet();
                }
            }));
            return true;
        } catch (RejectedExecutionException ex) {
            admitted.decrementAndGet();
            return false;
        }
    }

    /**
     * Returns bounded credential-free operational counters.
     *
     * @return immutable counter snapshot
     */
    public TileCoordinatorStats stats() {
        return new TileCoordinatorStats(transportExecutions.get(), memoryHits.get(), diskHits.get(),
            negativeHits.get(), joins.get(), workers.getQueue().size(), workers.getActiveCount(),
            authBlocked.containsKey(activeGeneration), retrySuppressed.get());
    }

    /**
     * Serializes bounded credential-free acquisition history for support bundles.
     *
     * @return safe JSON object
     */
    public synchronized String diagnosticsJson() {
        TileCoordinatorStats snapshot = stats();
        StringBuilder builder = new StringBuilder("{\"formatVersion\":1,\"activeGeneration\":")
            .append(activeGeneration).append(",\"stats\":{")
            .append("\"transportExecutions\":").append(snapshot.transportExecutions()).append(',')
            .append("\"memoryHits\":").append(snapshot.memoryHits()).append(',')
            .append("\"diskHits\":").append(snapshot.diskHits()).append(',')
            .append("\"negativeHits\":").append(snapshot.negativeHits()).append(',')
            .append("\"singleFlightJoins\":").append(snapshot.singleFlightJoins()).append(',')
            .append("\"queued\":").append(snapshot.queued()).append(',')
            .append("\"inFlight\":").append(snapshot.inFlight()).append(',')
            .append("\"authBlocked\":").append(snapshot.authBlocked()).append(',')
            .append("\"retrySuppressed\":").append(snapshot.retrySuppressed())
            .append("},\"recentResults\":[");
        int index = 0;
        for (TileFetchResult result : recentResults) {
            if (index++ > 0) builder.append(',');
            builder.append('{')
                .append("\"generation\":").append(result.generation().value()).append(',')
                .append("\"purpose\":\"").append(result.purpose()).append("\",")
                .append("\"activity\":\"").append(result.address().activity()).append("\",")
                .append("\"color\":\"").append(result.address().color()).append("\",")
                .append("\"zoom\":").append(result.address().zoom()).append(',')
                .append("\"x\":").append(result.address().x()).append(',')
                .append("\"y\":").append(result.address().y()).append(',')
                .append("\"status\":\"").append(result.status()).append("\",")
                .append("\"httpStatus\":").append(result.httpStatus()).append(',')
                .append("\"contentType\":\"").append(result.sanitizedContentType()).append("\",")
                .append("\"responseBytes\":").append(result.responseBytes()).append(',')
                .append("\"attempts\":").append(result.attemptCount()).append(',')
                .append("\"elapsedMs\":").append(result.elapsed().toMillis()).append(',')
                .append("\"safeReasonCode\":\"").append(result.safeReasonCode()).append("\"}");
        }
        return builder.append("]}").toString();
    }

    @Override
    public void close() {
        java.util.List<Flight> abandoned;
        synchronized (publicationFence) {
            if (!closed.compareAndSet(false, true)) return;
            generationRevision = Math.incrementExact(generationRevision);
            abandoned = java.util.List.copyOf(flights.values());
            abandoned.forEach(flight -> flight.accepting = false);
        }
        for (Flight flight : abandoned) {
            flight.request.cancellation().cancel();
            finish(flight, failure(flight.request, TileFetchStatus.CANCELLED, "closed",
                "Tile acquisition is closed.", null));
        }
        workers.shutdownNow();
        scheduler.shutdownNow();
        negatives.clear();
        memoryCache.clear();
        synchronized (this) { recentResults.clear(); }
    }

    private CompletionStage<TileFetchResult> subscriber(Flight flight, TileRequest request,
        CredentialSnapshot credentials, boolean joined) {
        CompletableFuture<TileFetchResult> consumer = new CompletableFuture<>();
        boolean start;
        synchronized (publicationFence) {
            if (closed.get() || !flight.accepting || flight.terminal || request.cancellation().isCancelled()) {
                discardUnstarted(flight);
                return CompletableFuture.completedFuture(failure(request, TileFetchStatus.CANCELLED,
                    "cancelled", "Tile acquisition was cancelled.", null));
            }
            if (activeGeneration >= 0 && activeGeneration != request.generation().value()) {
                discardUnstarted(flight);
                return CompletableFuture.completedFuture(failure(request, TileFetchStatus.STALE_GENERATION,
                    "stale-generation", "Tile settings changed before acquisition completed.", null));
            }
            flight.subscribers.add(consumer);
            start = !flight.started;
            flight.started = true;
            if (joined) joins.incrementAndGet();
        }
        consumer.whenComplete((value, error) -> detach(flight, consumer));
        CancellationToken.Registration registration = request.cancellation().onCancellation(() ->
            consumer.complete(failure(request, TileFetchStatus.CANCELLED, "cancelled",
                "Tile acquisition was cancelled.", null)));
        consumer.whenComplete((value, error) -> registration.close());
        flight.result.whenComplete((result, error) -> {
            if (request.cancellation().isCancelled())
                consumer.complete(failure(request, TileFetchStatus.CANCELLED, "cancelled", "Tile acquisition was cancelled.", null));
            else if (error != null)
                consumer.complete(failure(request, TileFetchStatus.CANCELLED, "closed", "Tile acquisition was closed.", null));
            else consumer.complete(result.as(result.status(), request.purpose()));
        });
        if (start) start(flight, credentials);
        return consumer;
    }

    private void discardUnstarted(Flight flight) {
        if (!flight.started && flight.subscribers.isEmpty()) {
            flight.accepting = false;
            flight.terminal = true;
            flights.remove(flight.key, flight);
        }
    }

    private void detach(Flight flight, CompletableFuture<TileFetchResult> consumer) {
        boolean cancel = false;
        synchronized (publicationFence) {
            if (flight.subscribers.remove(consumer) && flight.subscribers.isEmpty() && !flight.terminal) {
                flight.accepting = false;
                cancel = true;
            }
        }
        if (cancel) flight.request.cancellation().cancel();
    }

    /** Requires the publication fence; token signaling may lag the irreversible ownership transition. */
    private TileFetchResult unavailable(Flight flight) {
        TileRequest request = flight.request;
        if (closed.get() || !flight.accepting || flight.subscribers.isEmpty() || request.cancellation().isCancelled())
            return failure(request, TileFetchStatus.CANCELLED, "cancelled", "Tile acquisition was cancelled.", null);
        if (activeGeneration >= 0 && request.generation().value() != activeGeneration)
            return failure(request, TileFetchStatus.STALE_GENERATION, "stale-generation", "Tile settings changed before acquisition completed.", null);
        return null;
    }

    private CompletionStage<TileFetchResult> completedObserved(TileFetchResult result) {
        observe(result);
        return CompletableFuture.completedFuture(result);
    }

    private synchronized void observe(TileFetchResult result) {
        recentResults.addLast(result);
        while (recentResults.size() > 512) {
            recentResults.removeFirst();
        }
    }

    private TileFetchResult execute(Flight flight, CredentialSnapshot credentials) {
        TileRequest request = flight.request;
        CacheKey cacheKey = flight.key.key();
        synchronized (publicationFence) {
            TileFetchResult invalid = unavailable(flight);
            if (invalid != null) return invalid;
        }
        if (!request.deadline().isAfter(clock.instant())) {
            return failure(request, TileFetchStatus.OFFLINE, "deadline",
                "The tile acquisition deadline expired before transport started.", null);
        }
        if (activeGeneration >= 0L && request.generation().value() != activeGeneration) {
            return failure(request, TileFetchStatus.STALE_GENERATION, "stale-generation",
                "Tile settings changed before acquisition started.", null);
        }
        if (request.cachePolicy().reads()) {
            Optional<TileFetchResult> disk = diskCache.read(request);
            if (disk.isPresent()) {
                synchronized (publicationFence) {
                    TileFetchResult invalid = unavailable(flight);
                    if (invalid != null) return invalid;
                    diskHits.incrementAndGet();
                    memoryCache.put(cacheKey, disk.get());
                    return disk.get();
                }
            }
        }
        TileFetchResult blocked = blockedResult(request, cacheKey);
        if (blocked != null) {
            return blocked;
        }
        transportExecutions.incrementAndGet();
        TransportResponse response = transport.execute(request, credentials);
        int attempts = 1;
        Duration elapsed = response.elapsed();
        boolean retryRequest;
        synchronized (publicationFence) { retryRequest = unavailable(flight) == null && shouldRetryRequired(request, response.status()); }
        if (retryRequest) {
            transportExecutions.incrementAndGet();
            TransportResponse retried = transport.execute(request, credentials);
            attempts = 2;
            elapsed = elapsed.plus(retried.elapsed());
            response = retried;
        }
        synchronized (publicationFence) {
            TileFetchResult invalid = unavailable(flight);
            if (invalid != null) return invalid;
        }
        TileFetchResult result;
        if (response.status() == TileFetchStatus.SUCCESS_NETWORK) {
            byte[] body = response.body();
            if (body.length > policy.maximumBodyBytes()) {
                result = failure(request, TileFetchStatus.BODY_TOO_LARGE, "body-too-large",
                    "The tile response exceeded the size limit.", null);
            } else {
                DecodedTile decoded = classifier.decodeAndClassify(request.address(), response.contentType(), body);
                result = new TileFetchResult(request.address(), request.generation(), request.purpose(),
                    decoded.status(), decoded.image(), body, response.httpStatus(), response.contentType(),
                    elapsed, attempts, null, decoded.quality(), decoded.safeReasonCode(), decoded.safeMessage());
                if (result.usable()) {
                    try (ManagedTileCache.PreparedWrite prepared = request.cachePolicy().writes()
                            ? diskCache.prepareWrite(request, body) : null) {
                        beforePublication.run();
                        synchronized (publicationFence) {
                            TileFetchResult invalid = unavailable(flight);
                            if (invalid != null) return invalid;
                            if (prepared != null) prepared.commit();
                            memoryCache.put(cacheKey, result);
                            negatives.remove(cacheKey);
                        }
                    }
                }
            }
        } else {
            Instant retry = response.retryNotBefore();
            result = new TileFetchResult(request.address(), request.generation(), request.purpose(), response.status(),
                null, null, response.httpStatus(), response.contentType(), elapsed, attempts, retry,
                TileQuality.unavailable(response.reasonCode()), response.reasonCode(), safeMessage(response.status()));
        }
        synchronized (publicationFence) {
            TileFetchResult invalid = unavailable(flight);
            if (invalid != null) return invalid;
            rememberFailure(cacheKey, result);
        }
        return result;
    }

    private boolean shouldRetryRequired(TileRequest request, TileFetchStatus status) {
        if (request.purpose() != TilePurpose.ALIGNMENT_REQUIRED || !request.deadline().isAfter(clock.instant())
            || request.cancellation().isCancelled()) {
            return false;
        }
        return status == TileFetchStatus.HTTP_SERVER_ERROR || status == TileFetchStatus.NETWORK_ERROR
            || status == TileFetchStatus.CONNECT_TIMEOUT || status == TileFetchStatus.READ_TIMEOUT;
    }

    private TileFetchResult blockedResult(TileRequest request, CacheKey cacheKey) {
        if (authBlocked.containsKey(request.generation().value())) {
            retrySuppressed.incrementAndGet();
            return failure(request, TileFetchStatus.AUTH_FAILURE, "auth-circuit",
                "Managed tile authentication is blocked until settings change.", null);
        }
        Instant rate = rateLimited.get(request.generation().value());
        if (rate != null && rate.isAfter(clock.instant())) {
            retrySuppressed.incrementAndGet();
            return failure(request, TileFetchStatus.RATE_LIMITED, "rate-circuit",
                "Managed tile requests are rate limited.", rate);
        }
        NegativeState negative = negatives.get(cacheKey);
        if (negative != null && negative.retryNotBefore().isAfter(clock.instant())) {
            negativeHits.incrementAndGet();
            retrySuppressed.incrementAndGet();
            return failure(request, negative.status(), negative.reasonCode(), safeMessage(negative.status()),
                negative.retryNotBefore());
        }
        if (negative != null) {
            negatives.remove(cacheKey, negative);
        }
        return null;
    }

    private void rememberFailure(CacheKey key, TileFetchResult result) {
        if (result.usable() || result.status() == TileFetchStatus.CANCELLED
            || result.status() == TileFetchStatus.STALE_GENERATION) {
            return;
        }
        if (result.status() == TileFetchStatus.AUTH_FAILURE) {
            authBlocked.put(key.generation().value(), result.status());
            return;
        }
        if (result.status() == TileFetchStatus.RATE_LIMITED) {
            Instant retry = result.retryNotBefore() == null
                ? clock.instant().plus(policy.rateLimitFallback()) : result.retryNotBefore();
            rateLimited.put(key.generation().value(), retry);
            return;
        }
        Instant retry = clock.instant().plus(ttl(result.status()));
        if (negatives.size() >= policy.negativeCacheEntries()) {
            negatives.keys().asIterator().forEachRemaining(candidate -> {
                if (negatives.size() >= policy.negativeCacheEntries()) {
                    negatives.remove(candidate);
                }
            });
        }
        negatives.put(key, new NegativeState(result.status(), retry, result.safeReasonCode()));
    }

    private Duration ttl(TileFetchStatus status) {
        return switch (status) {
            case NO_TILE -> policy.noTileTtl();
            case CONTENT_TYPE_ERROR, BODY_TOO_LARGE, DECODE_ERROR, BAD_DIMENSIONS, PLACEHOLDER_SUSPECTED ->
                policy.contentFailureTtl();
            default -> policy.transientFailureTtl();
        };
    }

    private TileFetchResult failure(TileRequest request, TileFetchStatus status, String reason, String message,
        Instant retry) {
        return new TileFetchResult(request.address(), request.generation(), request.purpose(), status, null, null,
            -1, "", Duration.ZERO, 0, retry, TileQuality.unavailable(reason), reason, message);
    }

    private String safeMessage(TileFetchStatus status) {
        return switch (status) {
            case AUTH_FAILURE -> "Managed tile authentication failed; update access values.";
            case RATE_LIMITED -> "Managed tile requests are rate limited.";
            case NO_TILE -> "No managed tile exists at this coordinate.";
            case CONTENT_TYPE_ERROR, DECODE_ERROR, BAD_DIMENSIONS, PLACEHOLDER_SUSPECTED, BODY_TOO_LARGE ->
                "The managed tile response was not usable imagery.";
            case HTTP_SERVER_ERROR, NETWORK_ERROR, CONNECT_TIMEOUT, READ_TIMEOUT, OFFLINE ->
                "The managed tile source was temporarily unavailable.";
            default -> "The managed tile request failed.";
        };
    }

    private record CacheKey(ManagedTileGeneration generation, ManagedTileAddress address) { }
    private static final class Flight {
        final FlightKey key;
        final TileRequest request;
        final CompletableFuture<TileFetchResult> result = new CompletableFuture<>();
        final java.util.Set<CompletableFuture<TileFetchResult>> subscribers =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        boolean accepting = true, started, terminal, admitted;
        Flight(FlightKey key, TileRequest source) {
            this.key = key;
            request = new TileRequest(source.address(), source.generation(), source.purpose(), source.cachePolicy(),
                source.deadline(), new CancellationToken());
        }
    }

    private record FlightKey(CacheKey key, TileCachePolicy policy) { }
    private record NegativeState(TileFetchStatus status, Instant retryNotBefore, String reasonCode) { }

    private record PrioritizedTask(int priority, long sequence, Runnable delegate)
        implements Runnable, Comparable<PrioritizedTask> {
        @Override public void run() { delegate.run(); }
        @Override public int compareTo(PrioritizedTask other) {
            int byPriority = Integer.compare(priority, other.priority);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }

    private static final class MemoryCache {
        private final long maximumBytes;
        private final LinkedHashMap<CacheKey, TileFetchResult> entries = new LinkedHashMap<>(16, 0.75f, true);
        private long bytes;

        MemoryCache(long maximumBytes) { this.maximumBytes = maximumBytes; }
        synchronized TileFetchResult get(CacheKey key) { return entries.get(key); }
        synchronized void put(CacheKey key, TileFetchResult value) {
            TileFetchResult previous = entries.put(key, value);
            if (previous != null) bytes -= estimate(previous);
            bytes += estimate(value);
            var iterator = entries.entrySet().iterator();
            while (bytes > maximumBytes && iterator.hasNext()) {
                Map.Entry<CacheKey, TileFetchResult> eldest = iterator.next();
                bytes -= estimate(eldest.getValue());
                iterator.remove();
            }
        }
        synchronized void clear() { entries.clear(); bytes = 0L; }
        private long estimate(TileFetchResult value) {
            long image = value.image() == null ? 0L
                : (long) value.image().getWidth() * value.image().getHeight() * Integer.BYTES;
            return image + value.responseBytes();
        }
    }
}
