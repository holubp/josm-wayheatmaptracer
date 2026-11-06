package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.management.GarbageCollectorMXBean;
import javax.management.ObjectName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyPerformanceDiagnosticsTest {
    @Test void disabledDiagnosticsDoNotReadProbeCountersOrWriteCalls() throws Exception {
        var reads = new AtomicInteger();
        var output = new StringWriter();
        try (var diagnostics = LegacyPerformanceDiagnostics.create(false, output, () -> {
            reads.incrementAndGet();
            return snapshot();
        })) {
            var call = diagnostics.begin(identity());
            diagnostics.complete(call, 100L, 112L, 12L);
            diagnostics.failPending(new IllegalStateException("synthetic"));
            assertFalse(diagnostics.isEnabled());
        }
        assertEquals(0, reads.get());
        assertEquals("", output.toString());
    }

    @Test void unsupportedCountersAreSerializedAsNullAndCallsRetainIdentity() throws Exception {
        var output = new StringWriter();
        try (var diagnostics = LegacyPerformanceDiagnostics.create(true, output, LegacyPerformanceDiagnosticsTest::snapshot)) {
            var call = diagnostics.begin(identity());
            diagnostics.complete(call, 100L, 1334L, 1234L);
        }
        String json = output.toString();
        assertTrue(json.contains("\"status\":\"completed\""), json);
        assertTrue(json.contains("\"scene\":\"GAUSSIAN_KINK\""), json);
        assertTrue(json.contains("\"policy\":\"PROTECTED_SECANT3_V1\""), json);
        assertTrue(json.contains("\"elapsedNanos\":1234"), json);
        assertTrue(json.contains("\"threadCpuNanosStart\":null"), json);
        assertTrue(json.contains("\"threadAllocatedBytesEnd\":null"), json);
        assertTrue(json.contains("\"processCpuNanosStart\":null"), json);
        assertTrue(json.contains("\"gcCountEnd\":null"), json);
        assertTrue(json.contains("\"compilationTimeMillisStart\":null"), json);
    }

    @Test void failedCallIsRecordedAndDiagnosticCompletionStatusCannotPassOrdinaryGate() throws Exception {
        var output = new StringWriter();
        try (var diagnostics = LegacyPerformanceDiagnostics.create(true, output, LegacyPerformanceDiagnosticsTest::snapshot)) {
            diagnostics.begin(identity());
            diagnostics.failPending(new IllegalArgumentException("controlled failure"));
        }
        assertTrue(output.toString().contains("\"status\":\"failed\""), output.toString());
        assertTrue(output.toString().contains("\"failureClass\":\"java.lang.IllegalArgumentException\""), output.toString());
        assertEquals("COMPLETED_ONE_DIAGNOSTIC_FORK", LegacyPerformanceDiagnostics.completionStatus(false, true));
        assertEquals("COMPLETED_ONE_DIAGNOSTIC_PREFLIGHT", LegacyPerformanceDiagnostics.completionStatus(true, true));
        assertEquals("COMPLETED_ONE_PUBLIC_SYNTHETIC_FORK", LegacyPerformanceDiagnostics.completionStatus(false, false));
        assertEquals("COMPLETED_ONE_PUBLIC_PREFLIGHT_PAIR_PER_SCENE_AND_SOURCE",
                LegacyPerformanceDiagnostics.completionStatus(true, false));
    }

    @Test void uptimeUsesOneBracketedStartupAnchorDespiteProbeDelay() throws Exception {
        var anchor = new LegacyPerformanceDiagnostics.ClockAnchor(Instant.parse("2026-11-06T00:00:00Z"),
                1_000_000_000L, 1_000_000_100L, 10_000L, 1_000_000_000L, 1_000_000_100L);
        var delayedProbeSnapshot = new LegacyPerformanceDiagnostics.Snapshot(
                Instant.parse("2026-11-06T00:00:00.150Z"), 1_150_000_000L, 2_000_000L,
                null, null, null, null, null, null, null, null);
        var output = new StringWriter();
        try (var diagnostics = LegacyPerformanceDiagnostics.create(true, output, () -> delayedProbeSnapshot, anchor)) {
            long exactStart = 1_200_000_050L;
            var call = diagnostics.begin(identity());
            diagnostics.complete(call, exactStart, exactStart + 100L, 100L);
        }
        String json = output.toString();
        assertTrue(json.contains("\"jvmUptimeEstimateNanosStart\":10200000000"), json);
        assertTrue(json.contains("\"jvmUptimeEstimateNanosEnd\":10200000100"), json);
        assertTrue(json.contains("\"jvmUptimeUncertaintyNanosStart\":1000050"), json);
        assertTrue(json.contains("\"wallStart\":\"2026-11-06T00:00:00.200000050Z\""), json);
        assertTrue(json.contains("\"wallEnd\":\"2026-11-06T00:00:00.200000150Z\""), json);
    }

    @Test void diagnosticExportFailureDoesNotBlockCompletedRowsOrCoreFailureSummary(@TempDir Path directory)
            throws Exception {
        Writer refusing = new Writer() {
            @Override public void write(char[] chars, int offset, int length) throws IOException {
                throw new IOException("synthetic-diagnostic-write-refusal");
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        var diagnostics = LegacyPerformanceDiagnostics.create(true, refusing,
                LegacyPerformanceDiagnosticsTest::snapshot);
        diagnostics.begin(identity());
        var row = new LegacyWideSecantPerformanceMain.Observation(
                LegacyWideSecantPerformanceMain.Scene.STRAIGHT,
                LegacyWideSecantPerformanceMain.SourceKind.VISIBLE, 3, "candidate-first", "PROTECTED_SECANT3_V1",
                123L, 15, 2, 1, 1, "alternatives-digest", "geometry-digest", true, "", 15,
                "fixture-digest", "[]", true, "", Map.of(), Map.of(), "legacy-protected-secant3-v1",
                "legacy-v02", 6.0, 24.0, "");
        Throwable original = new IllegalStateException("synthetic-quality-failure");
        try {
            LegacyWideSecantPerformanceMain.preserveFailureArtifacts(directory, -1, List.of(row), original,
                    diagnostics);
            throw original;
        } catch (Throwable observed) {
            assertSame(original, observed, "Failure exporting diagnostics must preserve the original throwable");
        }
        assertTrue(Files.readString(directory.resolve("observations.csv")).contains("alternatives-digest"));
        assertTrue(Files.readString(directory.resolve("summary.json")).contains("\"completedObservationRows\":1"));
        assertTrue(java.util.Arrays.stream(original.getSuppressed())
                .anyMatch(error -> String.valueOf(error.getMessage()).contains("synthetic-diagnostic-write-refusal")));
        diagnostics.close();
    }

    @Test void gcCountersBecomeUnavailablePerMetricForMixedEmptyAndThrowingCollectors() {
        Long[] mixed = LegacyPerformanceDiagnostics.gcMetrics(List.of(
                collector("unavailable-count", -1, 0, false, false),
                collector("available", 0, 5, false, false)));
        assertNull(mixed[0], "An unavailable collector count must not be reported as zero collections");
        assertEquals(5L, mixed[1], "Collection time remains available when all collectors report it");

        Long[] empty = LegacyPerformanceDiagnostics.gcMetrics(List.of());
        assertNull(empty[0]);
        assertNull(empty[1]);

        Long[] throwing = LegacyPerformanceDiagnostics.gcMetrics(List.of(
                collector("throws-count", 0, 4, true, false),
                collector("available", 2, 6, false, false)));
        assertNull(throwing[0], "A throwing collector makes the aggregate count unavailable");
        assertEquals(10L, throwing[1], "The independent time metric remains available");
    }

    private static LegacyPerformanceDiagnostics.Identity identity() {
        return new LegacyPerformanceDiagnostics.Identity("GAUSSIAN_KINK", "MANAGED", 4,
                "PROTECTED_SECANT3_V1", "candidate-first", "measured");
    }

    private static LegacyPerformanceDiagnostics.Snapshot snapshot() {
        return new LegacyPerformanceDiagnostics.Snapshot(Instant.parse("2026-11-06T00:00:00Z"), 10L, 20L,
                null, null, null, 100L, 200L, null, null, null);
    }

    private static GarbageCollectorMXBean collector(String name, long count, long time,
            boolean throwCount, boolean throwTime) {
        return new GarbageCollectorMXBean() {
            @Override public long getCollectionCount() {
                if (throwCount) throw new UnsupportedOperationException("count unavailable");
                return count;
            }
            @Override public long getCollectionTime() {
                if (throwTime) throw new UnsupportedOperationException("time unavailable");
                return time;
            }
            @Override public String getName() { return name; }
            @Override public boolean isValid() { return true; }
            @Override public String[] getMemoryPoolNames() { return new String[0]; }
            @Override public ObjectName getObjectName() { return null; }
        };
    }
}
