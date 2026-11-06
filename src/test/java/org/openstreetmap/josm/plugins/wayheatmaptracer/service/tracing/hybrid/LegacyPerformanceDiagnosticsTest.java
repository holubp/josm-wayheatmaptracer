package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringWriter;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

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

    private static LegacyPerformanceDiagnostics.Identity identity() {
        return new LegacyPerformanceDiagnostics.Identity("GAUSSIAN_KINK", "MANAGED", 4,
                "PROTECTED_SECANT3_V1", "candidate-first", "measured");
    }

    private static LegacyPerformanceDiagnostics.Snapshot snapshot() {
        return new LegacyPerformanceDiagnostics.Snapshot(Instant.parse("2026-11-06T00:00:00Z"), 10L, 20L,
                null, null, null, 100L, 200L, null, null, null);
    }
}
