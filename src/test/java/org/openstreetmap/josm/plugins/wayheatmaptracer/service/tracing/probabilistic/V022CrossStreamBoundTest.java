package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Modifier;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CrossStreamWorkAccounting;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Tiny pure arithmetic oracles: no capture, profiles, inference, or graph acquisition. */
class V022CrossStreamBoundTest {
    @Test
    void certificateEnclosesSourceTripleOrFallsBack() {
        var ledger = new AttemptMemoryLedger(4096);
        try (var owner = ledger.rootOwner()) {
            var state = state(owner);
            double upper = CrossStreamDominance.upperTriple(0, 2, 1, 1, state);
            assertTrue(Double.isFinite(upper) && upper > 0, "ordinary right-angle source needs a useful finite certificate");
            double actual = sourceTriple(new MetricPoint(0, 0), new MetricPoint(1, 0),
                    new MetricPoint(1, 1), 0, 2, 1, 1);
            assertTrue(actual > 1 && actual < 2, "independent right-angle Huber witness");
            assertTrue(upper >= actual);
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void finiteCertificateEnclosesBinaryExtremesAndBothHuberArms() {
        MetricPoint[][] points = {
            {new MetricPoint(0, 0), new MetricPoint(1, 0), new MetricPoint(1, 1)},
            {new MetricPoint(-Double.MAX_VALUE, 0), new MetricPoint(Double.MAX_VALUE, 0), new MetricPoint(-Double.MAX_VALUE, 1)},
            {new MetricPoint(0, 0), new MetricPoint(-1, Double.MIN_VALUE), new MetricPoint(-2, 0)},
            {new MetricPoint(0, 0), new MetricPoint(-1, -0.0), new MetricPoint(0, -Double.MIN_VALUE)}
        };
        assertTrue(Double.isInfinite(points[1][1].xMeters() - points[1][0].xMeters()));
        assertTrue(Double.isInfinite(points[1][2].xMeters() - points[1][1].xMeters()));
        double[] scales = {1, 10, Double.MIN_VALUE, Double.MAX_VALUE};
        double[] weights = {0, 1, Double.MIN_VALUE, Double.MAX_VALUE};
        for (var p : points) for (double scale : scales) for (double weight : weights) {
            var ledger = new AttemptMemoryLedger(4096);
            try (var owner = ledger.rootOwner()) {
                var state = state(owner);
                double upper = CrossStreamDominance.upperTriple(0, 2 * Double.MIN_VALUE, weight, scale, state);
                if (Double.isFinite(upper)) {
                    double actual = sourceTriple(p[0], p[1], p[2], 0, 2 * Double.MIN_VALUE, weight, scale);
                    assertTrue(Double.isFinite(actual) && actual >= 0);
                    assertTrue(actual <= upper, () -> "source triple " + actual + " exceeds " + upper);
                } else assertTrue(Double.isNaN(upper), "UNSAFE is explicit, never an infinite usable bound");
            }
            assertEquals(0, ledger.currentBytes());
        }
    }

    @Test
    void exactCounterCensusForBothArmsAndLazyReuse() {
        for (double scale : new double[] {1, 10}) {
            var ledger = new AttemptMemoryLedger(4096);
            try (var owner = ledger.rootOwner()) {
                var state = state(owner);
                double upper = CrossStreamDominance.upperTriple(0, 2, 1, scale, state);
                assertTrue(Double.isFinite(upper));
                assertEquals(scale == 1 ? 9 : 10, state.finiteTests);
                assertEquals(scale == 1 ? 10 : 11, state.magnitudeTests);
                assertEquals(scale == 1 ? 8 : 9, state.arithmeticOps, "includes once-only scalb(Q)");
                assertEquals(scale == 1 ? 5 : 6, state.outwardSteps, "includes once-only nextUp(pi)");
                assertEquals(1, state.stageCertificates);
                long finite = state.finiteTests;
                long magnitude = state.magnitudeTests;
                long arithmetic = state.arithmeticOps;
                long outward = state.outwardSteps;
                assertEquals(Double.doubleToRawLongBits(upper), Double.doubleToRawLongBits(
                        CrossStreamDominance.upperTriple(0, 2, 1, scale, state)));
                assertEquals(finite, state.finiteTests, "same stage certificate is not recomputed");
                assertTrue(CrossStreamDominance.strictlyDominatedRaw1(-16, -8, upper, -25, state, CancellationProbe.NONE));
                assertEquals(64, state.boundUnits - 136);
                assertEquals(1, state.boundQueries);
                assertEquals(1, state.partialAdditions);
                assertEquals(1, state.strictComparisons);
                assertEquals(finite + 3, state.finiteTests);
                assertEquals(magnitude + 3, state.magnitudeTests);
                assertEquals(arithmetic + 3, state.arithmeticOps, "two abs operations and one partial addition");
                assertEquals(outward, state.outwardSteps, "query does not evaluate a turn or outward certificate");
                assertEquals(2, state.boundCheckpoints);
                assertEquals(1, state.skippedStreams);
                assertEquals(1, state.skippedLogicalPrefixes);
                assertEquals(0, state.tripleEvaluations, "helper never computes an actual triple");
            }
            assertEquals(0, ledger.currentBytes());
        }
    }

    @Test
    void strictBoundKeepsEqualitySignedZeroAndNegativeUnary() {
        double q = Math.scalb(Double.MAX_VALUE, -2);
        double[][] cases = {{-16, -8, -25, 1}, {-16, -8, -24, 0}, {-16, -8, -23, 0},
            {-0.0, 0, -0.0, 0}, {0, -0.0, 0, 0}, {Double.NaN, 0, -1, 0},
            {0, Double.POSITIVE_INFINITY, -1, 0}, {0, 0, Double.NaN, 0},
            {q, 0, Math.nextDown(q), 1}, {-q, 0, Math.nextDown(-q), 1},
            {0, q, Math.nextDown(q), 1}, {0, -q, Math.nextDown(-q), 1},
            {Math.nextUp(q), 0, -1, 0}, {-Math.nextUp(q), 0, -Double.MAX_VALUE, 0},
            {0, -Math.nextUp(q), -1, 0}, {0, 1, Math.nextDown(1.0), 1}, {0, 1, 1, 0}};
        for (double[] input : cases) {
            var ledger = new AttemptMemoryLedger(4096);
            try (var owner = ledger.rootOwner()) {
                var state = state(owner);
                double upper = CrossStreamDominance.upperTriple(0, 2, 1, 1, state);
                assertEquals(input[3] == 1, CrossStreamDominance.strictlyDominatedRaw1(
                        input[0], input[1], upper, input[2], state, CancellationProbe.NONE));
            }
            assertEquals(0, ledger.currentBytes());
        }
    }

    @Test
    void subnormalSumsRoundedIncrementAndLargeUpperKeepExpressionBoundaries() {
        double tiny = Double.MIN_VALUE;
        double[][] cases = {{tiny, tiny, tiny, 1}, {tiny, -tiny, -tiny, 1}, {-tiny, tiny, 0, 0},
                {0, 0x1.0p54, Math.nextDown(0x1.0p54), 1}};
        double actualTurn = sourceTriple(new MetricPoint(0, 0), new MetricPoint(1, 0),
                new MetricPoint(1, 1), 0, 2, 1, 1);
        assertTrue(actualTurn > 0);
        assertEquals(Double.doubleToRawLongBits(0x1.0p54),
                Double.doubleToRawLongBits(0x1.0p54 + actualTurn), "completed increment rounds to U");
        assertEquals(2 * tiny, tiny + tiny, "representable subnormal sum");
        assertEquals(Double.doubleToRawLongBits(0.0), Double.doubleToRawLongBits(tiny - tiny),
                "cancellation is exact; no impossible inexact additive underflow claim");
        for (double[] input : cases) {
            var ledger = new AttemptMemoryLedger(4096);
            try (var owner = ledger.rootOwner()) {
                var state = state(owner);
                double upper = CrossStreamDominance.upperTriple(0, 2, 1, 1, state);
                assertEquals(input[3] == 1, CrossStreamDominance.strictlyDominatedRaw1(
                        input[0], input[1], upper, input[2], state, CancellationProbe.NONE));
            }
            assertEquals(0, ledger.currentBytes());
        }
        var ledger = new AttemptMemoryLedger(4096);
        try (var owner = ledger.rootOwner()) {
            var state = state(owner);
            double upper = CrossStreamDominance.upperTriple(0, 2, Math.scalb(Double.MAX_VALUE, -2), 1, state);
            assertTrue(Double.isFinite(upper) && upper > Math.scalb(Double.MAX_VALUE, -2));
            assertFalse(CrossStreamDominance.strictlyDominatedRaw1(0, 0, upper, -1, state, CancellationProbe.NONE));
            assertEquals(0, state.partialAdditions, "unsafe energy magnitude is refused before any partial addition");
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void unsafeCertificateIsCachedAndResetOnlyAtNextStage() {
        var ledger = new AttemptMemoryLedger(4096);
        try (var owner = ledger.rootOwner()) {
            var state = state(owner);
            assertTrue(Double.isNaN(CrossStreamDominance.upperTriple(0, 2, 1, Double.MIN_VALUE, state)));
            assertEquals(1, state.certificateFallbacks);
            long count = state.finiteTests;
            assertTrue(Double.isNaN(CrossStreamDominance.upperTriple(0, 2, 1, Double.MIN_VALUE, state)));
            assertEquals(count, state.finiteTests);
            assertFalse(CrossStreamDominance.strictlyDominatedRaw1(10, 0, Double.NaN, 0, state, CancellationProbe.NONE));
            state.beginStage(3, 1, 1, 1);
            assertTrue(Double.isNaN(state.cachedUpper));
            assertTrue(Double.isFinite(CrossStreamDominance.upperTriple(1, 3, 1, 1, state)));
            assertEquals(2, state.stageCertificates);
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void counterCeilingsAnd424BytePayloadAreEnforced() {
        assertEquals(200, fieldBytes(CrossStreamDominance.State.class));
        assertEquals(160, fieldBytes(CrossStreamWorkAccounting.class));
        assertEquals(216, CrossStreamDominance.STATE_BYTES);
        assertEquals(176, CrossStreamDominance.SNAPSHOT_BYTES);
        assertEquals(32, CrossStreamDominance.WRAPPER_BYTES);
        assertEquals(424, CrossStreamDominance.PAYLOAD_BYTES);
        var small = new AttemptMemoryLedger(423);
        try (var owner = small.rootOwner()) {
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class, () -> owner.reserve(CrossStreamDominance.PAYLOAD_BYTES));
            assertEquals(0, owner.currentBytes());
        }
        var ledger = new AttemptMemoryLedger(4096);
        try (var owner = ledger.rootOwner()) {
            var state = state(owner);
            assertEquals(1, state.queryCeiling);
            assertEquals(200, state.unitCeiling);
            double upper = CrossStreamDominance.upperTriple(0, 2, 1, 1, state);
            CrossStreamDominance.strictlyDominatedRaw1(0, 0, upper, 0, state, CancellationProbe.NONE);
            assertThrows(ArithmeticException.class, () -> CrossStreamDominance.strictlyDominatedRaw1(0, 0, upper, 0, state, CancellationProbe.NONE));
            assertEquals(1, state.boundQueries, "refused query must not masquerade as admitted work");
            int stage = state.stageIndex;
            assertThrows(ArithmeticException.class, () -> state.beginStage(3, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
            assertEquals(stage, state.stageIndex);
            assertThrows(IllegalArgumentException.class, () -> state.beginStage(4, 1, 1, 1));
            try (var snapshotBytes = owner.reserve(CrossStreamDominance.SNAPSHOT_BYTES);
                    var wrapperBytes = owner.reserve(CrossStreamDominance.WRAPPER_BYTES)) {
                var receipt = state.snapshot(snapshotBytes);
                assertEquals(1, receipt.boundQueries());
                assertEquals(424, owner.currentBytes(), "State + immutable snapshot + prepaid future wrapper");
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    @Test
    void snapshotRejectsContradictoryCountsAndCheckedOverflow() {
        assertThrows(IllegalArgumentException.class, () -> accounting(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> accounting(0, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> accounting(0, 0, 0, 1));
        assertThrows(ArithmeticException.class, () -> accounting(Long.MAX_VALUE, 0, 0, 1));
    }

    @Test
    void sameProbeCancellationAtEntryOrSkipAdmissionCannotPublishASkip() {
        for (int cancelAt : new int[] {1, 2}) {
            var ledger = new AttemptMemoryLedger(4096);
            try (var owner = ledger.rootOwner()) {
                var state = state(owner);
                double upper = CrossStreamDominance.upperTriple(0, 2, 1, 1, state);
                int[] calls = {0};
                CancellationProbe probe = () -> ++calls[0] == cancelAt;
                assertThrows(CancellationException.class, () -> CrossStreamDominance.strictlyDominatedRaw1(-16, -8, upper, -25, state, probe));
                assertEquals(cancelAt, calls[0]);
                assertEquals(0, state.skippedStreams);
                assertEquals(cancelAt, state.boundCheckpoints);
            }
            assertEquals(0, ledger.currentBytes());
        }
    }

    private static CrossStreamDominance.State state(AttemptMemoryLedger.Owner owner) {
        var state = CrossStreamDominance.State.allocate(owner);
        state.beginStage(2, 1, 1, 1);
        return state;
    }

    private static CrossStreamWorkAccounting accounting(long stages, long skipped, long logical, long units) {
        return new CrossStreamWorkAccounting(stages, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, skipped, logical, 0, 0, 0, 0, 0, 0, units);
    }

    private static long fieldBytes(Class<?> type) {
        long bytes = 0;
        for (var field : type.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())) {
            assertTrue(field.getType().isPrimitive(), "the frozen payload has no hidden reference/array");
            bytes += field.getType() == int.class ? 4 : 8;
        }
        return bytes;
    }

    /** Independent source-shaped triple oracle: no production scoring/bound helper. */
    private static double sourceTriple(MetricPoint first, MetricPoint middle, MetricPoint last,
            double left, double right, double weight, double scale) {
        double h1 = StrictMath.atan2(middle.yMeters() - first.yMeters(), middle.xMeters() - first.xMeters());
        double h2 = StrictMath.atan2(last.yMeters() - middle.yMeters(), last.xMeters() - middle.xMeters());
        double difference = h2 - h1;
        double turn = StrictMath.atan2(StrictMath.sin(difference), StrictMath.cos(difference)) / scale;
        double absolute = Math.abs(turn);
        double huber = absolute <= 1 ? 0.5 * turn * turn : absolute - 0.5;
        return weight * huber / Math.max(1e-6, 0.5 * (right - left));
    }
}
