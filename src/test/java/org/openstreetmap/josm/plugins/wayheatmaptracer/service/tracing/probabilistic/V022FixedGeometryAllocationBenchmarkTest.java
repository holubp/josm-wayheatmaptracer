package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

/** Public fixed-geometry timing and exact-output receipt for allocation-only changes. */
class V022FixedGeometryAllocationBenchmarkTest {
    private static final int PROFILE_COUNT = 600;
    private static final int STATE_COUNT = 16;
    private static final String BASELINE_DIGEST =
            "06c7eb97a3b04192546a70f8b94f506ab3fdc8553b0d80a30a1d79d34df213b7";
    private static final long BASELINE_PEAK_BYTES = 14_455_408L;
    private static final long BASELINE_RETAINED_BYTES = 396_968L;
    private static final EvidenceModelParameters PARAMETERS = EvidenceModelParameters.defaults();
    private static final ImageOrientationSupport ORIENTATION = new ImageOrientationSupport(
            ImageOrientationSupport.Status.MEASURED_TWO_SIDED,
            List.of(new ImageOrientationSupport.AngularMode(0.0, 0.0, 0.0, 1.0)), 0.8);

    @Test
    void fixedGeometryMap11ReportsExactOutputWorkAndOwnedMemory() throws Exception {
        List<InferenceProfile> profiles = profiles();
        MetricRegion decisionRegion = MetricRegion.rectangle(-2.0, -20.0,
                PROFILE_COUNT * 2.0, 20.0);
        AttemptMemoryLedger ledger = AttemptMemoryLedger.production();
        AttemptMemoryLedger.Owner owner = ledger.rootOwner();
        long started = System.nanoTime();
        ModernDiagnosticCounters.begin();
        ProbabilisticInferenceResult result;
        Map<String, Number> counters;
        try {
            result = new ProbabilisticInference().solve(profiles, PARAMETERS,
                    new TraceBudgets(96, 8_000_000L, 128_000_000L, 1, 1),
                    decisionRegion, () -> false, owner,
                    ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            counters = ModernDiagnosticCounters.snapshot();
        } finally {
            ModernDiagnosticCounters.end();
        }
        long elapsedNanos = System.nanoTime() - started;
        long peakBytes = ledger.peakBytes();
        long retainedBytes = ledger.currentBytes();
        String digest = exactOutputDigest(result);
        var map = result.mapPath().orElseThrow();
        var work = result.workAccounting().orElseThrow();

        try {
            assertEquals(PROFILE_COUNT, map.stateIndices().length);
            assertEquals(PROFILE_COUNT, map.points().size());
            assertEquals(ProbabilisticInferenceResult.Status.REVIEW_REQUIRED, result.status());
            assertEquals(ProbabilisticExecutionPolicy.CURRENT_VERSION, work.executionPolicy());
            assertEquals(result.evaluatedTransitions(), work.computedScores());
            assertEquals(BASELINE_DIGEST, digest, "complete path, energy, partition and marginal output is frozen");
            assertEquals("0x1.7ff9724745379p-1", Double.toHexString(map.energy()));
            assertEquals("0x1.31545437814ep9", Double.toHexString(result.logPartition()));
            assertEquals(153_344L, result.evaluatedPairVisits());
            assertEquals(7_348_224L, result.evaluatedTransitions());
            assertEquals(4_898_816L, work.forwardBackwardScores());
            assertEquals(2_449_408L, work.kBestScores());
            assertEquals(2_449_408L, work.logicalKBestExtensions());
            assertEquals(0L, work.refusedScoreAttempts());
            assertTrue(Double.isFinite(map.energy()));
            assertTrue(Double.isFinite(result.logPartition()));
            assertTrue(result.posteriorUsable());
            assertEquals(PROFILE_COUNT, result.positionMarginals().size());
            long indexedTableBytes = AttemptMemoryLedger.objectBytes(56)
                    + AttemptMemoryLedger.arrayBytes(PROFILE_COUNT, Integer.BYTES)
                    + AttemptMemoryLedger.arrayBytes((long) (PROFILE_COUNT - 1) * STATE_COUNT * STATE_COUNT,
                            Byte.BYTES);
            assertTrue(count(counters, "optimization.geometryAdmission.indexedCalculations") > 0);
            assertTrue(count(counters, "optimization.geometryAdmission.indexedReuses") > 0);
            assertTrue(indexedTableBytes > 100_000L, "the full fixed graph owns its indexed answer bytes");
            assertTrue(peakBytes < BASELINE_PEAK_BYTES + AttemptMemoryLedger.objectBytes(16) - 1_000_000L,
                    "the indexed table materially lowers the original memo peak despite its owned table charge");
            assertEquals(BASELINE_RETAINED_BYTES, retainedBytes);

            System.out.printf("OBSERVATION_FIXED_GEOMETRY profiles=%d states=%d mode=EXACT_DOMINANCE_V2 map=1/1 "
                            + "elapsedNanos=%d status=%s digest=%s energy=%s logPartition=%s pairVisits=%d "
                            + "transitions=%d forwardBackwardScores=%d kBestScores=%d logicalExtensions=%d "
                            + "refusedScores=%d peakBytes=%d retainedBytes=%d%n",
                    PROFILE_COUNT, STATE_COUNT, elapsedNanos, result.status(), digest,
                    Double.toHexString(map.energy()), Double.toHexString(result.logPartition()),
                    result.evaluatedPairVisits(), result.evaluatedTransitions(), work.forwardBackwardScores(),
                    work.kBestScores(), work.logicalKBestExtensions(), work.refusedScoreAttempts(),
                    peakBytes, retainedBytes);
        } finally {
            owner.close();
        }
        assertEquals(0L, ledger.currentBytes(), "all caller-owned graph/result storage is released");
    }

    private static List<InferenceProfile> profiles() {
        List<InferenceProfile> result = new ArrayList<>(PROFILE_COUNT);
        for (int profileIndex = 0; profileIndex < PROFILE_COUNT; profileIndex++) {
            double chainage = profileIndex * 2.0;
            List<LateralStateCell> cells = new ArrayList<>(STATE_COUNT);
            double[] unary = new double[STATE_COUNT];
            for (int state = 0; state < STATE_COUNT; state++) {
                double offset = state - (STATE_COUNT - 1) * 0.5;
                cells.add(new LateralStateCell(offset, 1.0, false, true, "main"));
                double centered = state - 7.25;
                unary[state] = 0.002 * centered * centered
                        + 0.0001 * ((profileIndex * 7 + state * 13) % 11);
            }
            result.add(new InferenceProfile(chainage, new MetricPoint(chainage, 0.0),
                    new MetricPoint(0.0, 1.0), cells, unary, Map.of("main", ORIENTATION),
                    Map.of("main", 0.75), ObservationOwnership.DIRECT_TWO_SIDED, false,
                    new double[STATE_COUNT][0]));
        }
        return List.copyOf(result);
    }

    private static String exactOutputDigest(ProbabilisticInferenceResult result) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            var map = result.mapPath().orElseThrow();
            int[] states = map.stateIndices();
            output.writeInt(states.length);
            for (int state : states) output.writeInt(state);
            output.writeInt(map.points().size());
            for (MetricPoint point : map.points()) {
                output.writeLong(Double.doubleToLongBits(point.xMeters()));
                output.writeLong(Double.doubleToLongBits(point.yMeters()));
            }
            output.writeLong(Double.doubleToLongBits(map.energy()));
            output.writeLong(Double.doubleToLongBits(result.logPartition()));
            List<double[]> marginals = result.positionMarginals();
            output.writeInt(marginals.size());
            for (double[] row : marginals) {
                output.writeInt(row.length);
                for (double value : row) output.writeLong(Double.doubleToLongBits(value));
            }
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }

    private static long count(Map<String, Number> counters, String name) {
        return counters.getOrDefault(name, 0L).longValue();
    }
}
