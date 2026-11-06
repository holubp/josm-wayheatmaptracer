package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Optional per-discovery JVM probes for one diagnostic-only public harness run. */
final class LegacyPerformanceDiagnostics implements AutoCloseable {
    record Identity(String scene, String sourceKind, int pair, String policy, String executionOrder, String phase) { }

    /** Nullable counters mean the runtime does not expose that metric. */
    record Snapshot(Instant wallTime, long monotonicNanos, long wallUncertaintyNanos,
            Long threadCpuNanos, Long threadAllocatedBytes, Long processCpuNanos,
            Long heapUsedBytes, Long heapCommittedBytes, Long gcCount, Long gcTimeMillis,
            Long compilationTimeMillis) { }

    record ClockAnchor(Instant wallTime, long wallNanoBefore, long wallNanoAfter,
            long uptimeMillis, long uptimeNanoBefore, long uptimeNanoAfter) {
        long wallMidpointNanos() { return midpoint(wallNanoBefore, wallNanoAfter); }
        long uptimeMidpointNanos() { return midpoint(uptimeNanoBefore, uptimeNanoAfter); }
        long wallUncertaintyNanos() { return halfWidthCeil(wallNanoBefore, wallNanoAfter); }
        long uptimeUncertaintyNanos() { return Math.addExact(halfWidthCeil(uptimeNanoBefore, uptimeNanoAfter), 1_000_000L); }
        long uptimeEstimateNanos(long monotonicNanos) {
            return Math.addExact(Math.multiplyExact(uptimeMillis, 1_000_000L), monotonicNanos - uptimeMidpointNanos());
        }
        Instant wallAt(long monotonicNanos) { return wallTime.plusNanos(monotonicNanos - wallMidpointNanos()); }
    }

    private final Writer writer;
    private final Supplier<Snapshot> probe;
    private final boolean enabled;
    private final ClockAnchor clockAnchor;
    private Call pending;

    static final class Call {
        final Identity identity;
        final Snapshot start;
        Call(Identity identity, Snapshot start) {
            this.identity = identity;
            this.start = start;
        }
    }

    private LegacyPerformanceDiagnostics(boolean enabled, Writer writer, Supplier<Snapshot> probe, ClockAnchor clockAnchor) {
        this.enabled = enabled;
        this.writer = writer;
        this.probe = probe;
        this.clockAnchor = clockAnchor;
    }

    static LegacyPerformanceDiagnostics create(boolean enabled, Writer writer, Supplier<Snapshot> probe) {
        return create(enabled, writer, probe, null);
    }

    static LegacyPerformanceDiagnostics create(boolean enabled, Writer writer, Supplier<Snapshot> probe,
            ClockAnchor clockAnchor) {
        if (!enabled) return new LegacyPerformanceDiagnostics(false, null, null, null);
        return new LegacyPerformanceDiagnostics(true, Objects.requireNonNull(writer), Objects.requireNonNull(probe), clockAnchor);
    }

    static LegacyPerformanceDiagnostics open(Path output) throws IOException {
        if (!Boolean.getBoolean("legacyNormalPerformanceDiagnostics")) return create(false, null, null);
        Writer writer = Files.newBufferedWriter(output.resolve("calls.jsonl"), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        try {
            ClockAnchor anchor = captureClockAnchor();
            Files.writeString(output.resolve("clock-anchor.json"), anchorJson(anchor), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            return new LegacyPerformanceDiagnostics(true, writer, LegacyPerformanceDiagnostics::systemSnapshot, anchor);
        } catch (IOException | RuntimeException | Error failure) {
            try { writer.close(); } catch (Throwable closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    boolean isEnabled() { return enabled; }

    Call begin(Identity identity) {
        if (!enabled) return null;
        if (pending != null) throw new IllegalStateException("diagnostic-call-already-open");
        Snapshot start = probe.get();
        pending = new Call(Objects.requireNonNull(identity), start);
        return pending;
    }

    void complete(Call call, long exactStartNanos, long exactEndNanos, long exactElapsedNanos) throws IOException {
        if (!enabled || call == null) return;
        if (call != pending) throw new IllegalStateException("diagnostic-call-mismatch");
        Snapshot end = probe.get();
        write(call, end, "completed", exactElapsedNanos, exactStartNanos, exactEndNanos, null);
        pending = null;
    }

    void failPending(Throwable failure) throws IOException {
        if (!enabled || pending == null) return;
        Call call = pending;
        Snapshot end = probe.get();
        write(call, end, "failed", null, null, null,
                Objects.requireNonNull(failure).getClass().getName());
        pending = null;
    }

    private void write(Call call, Snapshot end, String status, Long elapsedNanos, Long timingStart,
            Long timingEnd, String failureClass) throws IOException {
        Identity id = call.identity;
        Snapshot start = call.start;
        Instant wallStart = timingStart == null ? null
                : start.wallTime().plusNanos(timingStart - start.monotonicNanos());
        Instant wallEnd = timingEnd == null ? null
                : end.wallTime().plusNanos(timingEnd - end.monotonicNanos());
        Long uptimeStart = timingStart == null || clockAnchor == null ? null : clockAnchor.uptimeEstimateNanos(timingStart);
        Long uptimeEnd = timingEnd == null || clockAnchor == null ? null : clockAnchor.uptimeEstimateNanos(timingEnd);
        String json = "{\"schema\":1,\"status\":\"" + status + "\",\"scene\":\"" + escape(id.scene())
                + "\",\"sourceKind\":\"" + escape(id.sourceKind()) + "\",\"pair\":" + id.pair()
                + ",\"policy\":\"" + escape(id.policy()) + "\",\"executionOrder\":\""
                + escape(id.executionOrder()) + "\",\"phase\":\"" + escape(id.phase()) + "\""
                + ",\"wallStart\":" + quoted(wallStart) + ",\"wallEnd\":" + quoted(wallEnd)
                + ",\"wallStartProbe\":\"" + start.wallTime() + "\",\"wallEndProbe\":\"" + end.wallTime() + "\""
                + metric("wallStartUncertaintyNanos", start.wallUncertaintyNanos())
                + metric("wallEndUncertaintyNanos", end.wallUncertaintyNanos())
                + metric("jvmUptimeEstimateNanosStart", uptimeStart) + metric("jvmUptimeEstimateNanosEnd", uptimeEnd)
                + metric("jvmUptimeUncertaintyNanosStart", clockAnchor == null ? null : clockAnchor.uptimeUncertaintyNanos())
                + metric("jvmUptimeUncertaintyNanosEnd", clockAnchor == null ? null : clockAnchor.uptimeUncertaintyNanos())
                + ",\"timingStartMonotonicNanos\":" + timingStart + ",\"timingEndMonotonicNanos\":"
                + nullable(timingEnd) + ",\"elapsedNanos\":" + nullable(elapsedNanos)
                + metric("threadCpuNanosStart", start.threadCpuNanos()) + metric("threadCpuNanosEnd", end.threadCpuNanos())
                + metric("threadAllocatedBytesStart", start.threadAllocatedBytes())
                + metric("threadAllocatedBytesEnd", end.threadAllocatedBytes())
                + metric("processCpuNanosStart", start.processCpuNanos()) + metric("processCpuNanosEnd", end.processCpuNanos())
                + metric("heapUsedBytesStart", start.heapUsedBytes()) + metric("heapUsedBytesEnd", end.heapUsedBytes())
                + metric("heapCommittedBytesStart", start.heapCommittedBytes())
                + metric("heapCommittedBytesEnd", end.heapCommittedBytes())
                + metric("gcCountStart", start.gcCount()) + metric("gcCountEnd", end.gcCount())
                + metric("gcTimeMillisStart", start.gcTimeMillis()) + metric("gcTimeMillisEnd", end.gcTimeMillis())
                + metric("compilationTimeMillisStart", start.compilationTimeMillis())
                + metric("compilationTimeMillisEnd", end.compilationTimeMillis())
                + (failureClass == null ? "" : ",\"failureClass\":\"" + escape(failureClass) + "\"") + "}\n";
        writer.write(json);
        writer.flush();
    }

    static String completionStatus(boolean preflight, boolean diagnosticOnly) {
        if (diagnosticOnly) return preflight ? "COMPLETED_ONE_DIAGNOSTIC_PREFLIGHT" : "COMPLETED_ONE_DIAGNOSTIC_FORK";
        return preflight ? "COMPLETED_ONE_PUBLIC_PREFLIGHT_PAIR_PER_SCENE_AND_SOURCE" : "COMPLETED_ONE_PUBLIC_SYNTHETIC_FORK";
    }

    private static Snapshot systemSnapshot() {
        ThreadMXBean thread = ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().getId();
        Long threadCpu = safeLong(() -> thread.isCurrentThreadCpuTimeSupported() && thread.isThreadCpuTimeEnabled()
                ? thread.getCurrentThreadCpuTime() : -1L);
        Long allocated = safeLong(() -> {
            if (thread instanceof com.sun.management.ThreadMXBean extended
                    && extended.isThreadAllocatedMemorySupported() && extended.isThreadAllocatedMemoryEnabled())
                return extended.getThreadAllocatedBytes(threadId);
            return -1L;
        });
        Long processCpu = safeLong(() -> ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean extended ? extended.getProcessCpuTime() : -1L);
        MemoryUsage heap = safe(() -> ManagementFactory.getMemoryMXBean().getHeapMemoryUsage());
        Long heapUsed = heap == null ? null : nonnegative(heap.getUsed());
        Long heapCommitted = heap == null ? null : nonnegative(heap.getCommitted());
        List<GarbageCollectorMXBean> gcBeans = safe(ManagementFactory::getGarbageCollectorMXBeans);
        Long[] gc = gcBeans == null ? new Long[] {null, null} : gcMetrics(gcBeans);
        CompilationMXBean compiler = safe(ManagementFactory::getCompilationMXBean);
        Long compilation = safeLong(() -> compiler != null && compiler.isCompilationTimeMonitoringSupported()
                ? compiler.getTotalCompilationTime() : -1L);
        long wallBefore = System.nanoTime();
        Instant wall = Instant.now();
        long wallAfter = System.nanoTime();
        return new Snapshot(wall, midpoint(wallBefore, wallAfter), halfWidthCeil(wallBefore, wallAfter),
                threadCpu, allocated, processCpu,
                heapUsed, heapCommitted, gc[0], gc[1], compilation);
    }

    private static ClockAnchor captureClockAnchor() {
        var runtime = ManagementFactory.getRuntimeMXBean();
        runtime.getUptime(); // warm the management path before measuring the single serialized anchor
        long uptimeBefore = System.nanoTime();
        long uptime = runtime.getUptime();
        long uptimeAfter = System.nanoTime();
        long wallBefore = System.nanoTime();
        Instant wall = Instant.now();
        long wallAfter = System.nanoTime();
        return new ClockAnchor(wall, wallBefore, wallAfter, uptime, uptimeBefore, uptimeAfter);
    }

    private static String anchorJson(ClockAnchor anchor) {
        return "{\"schema\":1,\"wallClock\":{\"anchorInstant\":\"" + anchor.wallTime()
                + "\",\"monotonicBeforeNanos\":" + anchor.wallNanoBefore()
                + ",\"monotonicAfterNanos\":" + anchor.wallNanoAfter()
                + ",\"uncertaintyNanos\":" + anchor.wallUncertaintyNanos() + "}"
                + ",\"jvmUptime\":{\"anchorMillis\":" + anchor.uptimeMillis()
                + ",\"monotonicBeforeNanos\":" + anchor.uptimeNanoBefore()
                + ",\"monotonicAfterNanos\":" + anchor.uptimeNanoAfter()
                + ",\"midpointNanos\":" + anchor.uptimeMidpointNanos()
                + ",\"uncertaintyNanos\":" + anchor.uptimeUncertaintyNanos()
                + ",\"quantizationIncludedNanos\":1000000}}\n";
    }

    private interface LongRead { long get(); }
    private interface ObjectRead<T> { T get(); }

    private static Long safeLong(LongRead read) {
        try { return nonnegative(read.get()); }
        catch (RuntimeException | LinkageError unavailable) { return null; }
    }

    private static <T> T safe(ObjectRead<T> read) {
        try { return read.get(); }
        catch (RuntimeException | LinkageError unavailable) { return null; }
    }

    private static Long nonnegative(long value) { return value < 0 ? null : value; }

    private static long midpoint(long before, long after) {
        return before + Math.floorDiv(after - before, 2L);
    }

    private static long halfWidthCeil(long before, long after) {
        long width = after - before;
        if (width < 0) throw new IllegalArgumentException("clock-anchor-order");
        return width / 2 + width % 2;
    }

    private static Long[] gcMetrics(List<GarbageCollectorMXBean> beans) {
        long count = 0, time = 0;
        boolean countSeen = false, timeSeen = false;
        try {
            for (GarbageCollectorMXBean bean : beans) {
                long c = bean.getCollectionCount(), t = bean.getCollectionTime();
                if (c >= 0) { count = Math.addExact(count, c); countSeen = true; }
                if (t >= 0) { time = Math.addExact(time, t); timeSeen = true; }
            }
        } catch (RuntimeException | LinkageError unavailable) { return new Long[] {null, null}; }
        return new Long[] {countSeen ? count : null, timeSeen ? time : null};
    }

    private static String metric(String name, Long value) { return ",\"" + name + "\":" + nullable(value); }
    private static String nullable(Long value) { return value == null ? "null" : value.toString(); }
    private static String quoted(Instant value) { return value == null ? "null" : "\"" + value + "\""; }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }

    @Override public void close() throws IOException {
        if (writer != null) writer.close();
    }
}
