package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.Objects;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CrossStreamWorkAccounting;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Pure partial-bound capability; it does not score, admit, or remove graph states. */
final class CrossStreamDominance {
    static final long STATE_BYTES = AttemptMemoryLedger.objectBytes(200);
    static final long SNAPSHOT_BYTES = AttemptMemoryLedger.objectBytes(160);
    static final long WRAPPER_BYTES = AttemptMemoryLedger.objectBytes(16);
    static final long PAYLOAD_BYTES = STATE_BYTES + SNAPSHOT_BYTES + WRAPPER_BYTES;
    private static final double PI_UPPER = 0x1.921fb54442d19p1;
    private static final double QUARTER_MAX = 0x1.fffffffffffffp1021;
    private static final int UNCOMPUTED = 0, SAFE = 1, UNSAFE = 2;

    private CrossStreamDominance() { }

    static final class State {
        long stageCertificates, certificateFallbacks, boundQueries, boundFallbacks;
        long finiteTests, magnitudeTests, arithmeticOps, outwardSteps;
        long partialAdditions, strictComparisons, boundCheckpoints, skippedStreams;
        long skippedLogicalPrefixes, firstHeadingEvaluations, secondHeadingEvaluations;
        long wrapEvaluations, huberEvaluations, tripleEvaluations, ceilingChecks, boundUnits;
        long queryCeiling, unitCeiling;
        double cachedSpan = Double.NaN, cachedUpper = Double.NaN;
        int stageIndex = 1, certificateState;

        private State() {
            // Execute and account the once-per-attempt initialization, not once per stage.
            if (Math.nextUp(Math.PI) != PI_UPPER || Math.scalb(Double.MAX_VALUE, -2) != QUARTER_MAX) {
                throw new ArithmeticException("Cross-stream arithmetic constants changed");
            }
            arithmeticOps = outwardSteps = 1;
            unitCeiling = boundUnits = 8;
        }

        static State allocate(AttemptMemoryLedger.Owner owner) {
            try (var reservation = owner.reserve(STATE_BYTES)) {
                var state = new State();
                reservation.adopt(state);
                return state;
            }
        }

        void beginStage(int index, int beforeStates, int priorStates, int nextStates) {
            if (index != Math.addExact(stageIndex, 1) || beforeStates <= 0 || priorStates <= 0 || nextStates <= 0) {
                throw new IllegalArgumentException("Cross-stream stages must be consecutive and nonempty");
            }
            ceilingChecks = Math.addExact(ceilingChecks, 1);
            long queries = Math.multiplyExact(Math.multiplyExact((long) beforeStates, priorStates), nextStates);
            long nextQueryCeiling = Math.addExact(queryCeiling, queries);
            long nextUnitCeiling = Math.addExact(unitCeiling, Math.addExact(128L, Math.multiplyExact(64L, queries)));
            long nextUnits = Math.addExact(boundUnits, 128L);
            if (nextUnits > nextUnitCeiling) throw new ArithmeticException("Cross-stream stage work ceiling exceeded");
            queryCeiling = nextQueryCeiling;
            unitCeiling = nextUnitCeiling;
            boundUnits = nextUnits;
            stageIndex = index;
            cachedSpan = cachedUpper = Double.NaN;
            certificateState = UNCOMPUTED;
        }

        /** A caller polls before beginStage; query admission polls here before any skip. */
        void checkpoint(CancellationProbe probe) {
            boundCheckpoints = Math.addExact(boundCheckpoints, 1);
            probe.checkpoint();
        }

        private void beginQuery() {
            if (stageIndex < 2) throw new IllegalStateException("Cross-stream stage is absent");
            ceilingChecks = Math.addExact(ceilingChecks, 1);
            long nextQuery = Math.addExact(boundQueries, 1);
            long nextUnits = Math.addExact(boundUnits, 64L);
            if (nextQuery > queryCeiling || nextUnits > unitCeiling) {
                throw new ArithmeticException("Cross-stream query work ceiling exceeded");
            }
            boundQueries = nextQuery;
            boundUnits = nextUnits;
        }

        /** Consumes exactly the caller's prepaid immutable-record reservation. */
        CrossStreamWorkAccounting snapshot(AttemptMemoryLedger.Reservation reservation) {
            if (reservation.bytes() != SNAPSHOT_BYTES) {
                throw new IllegalArgumentException("Cross-stream snapshot reservation has the wrong size");
            }
            var result = new CrossStreamWorkAccounting(stageCertificates, certificateFallbacks,
                    boundQueries, boundFallbacks, finiteTests, magnitudeTests, arithmeticOps, outwardSteps,
                    partialAdditions, strictComparisons, boundCheckpoints, skippedStreams,
                    skippedLogicalPrefixes, firstHeadingEvaluations, secondHeadingEvaluations,
                    wrapEvaluations, huberEvaluations, tripleEvaluations, ceilingChecks, boundUnits);
            reservation.adopt(result);
            return result;
        }
    }

    /**
     * Bounds the unevaluated source triple on one immutable stage. The caller retains
     * original point validation/cache-allocation order and frozen parameters. An unsafe
     * stage is cached as NaN and never reinterpreted as a source numerical failure.
     */
    static double upperTriple(double leftChainage, double rightChainage,
            double weight, double scale, State state) {
        Objects.requireNonNull(state);
        if (state.stageIndex < 2) throw new IllegalStateException("Cross-stream stage is absent");
        if (state.certificateState != UNCOMPUTED) return state.cachedUpper;
        state.stageCertificates = Math.addExact(state.stageCertificates, 1);
        state.certificateState = UNSAFE;
        if (!finite(leftChainage, state) || !finite(rightChainage, state)
                || !finite(weight, state) || !finite(scale, state)
                || !magnitude(leftChainage >= 0, state) || !magnitude(rightChainage > leftChainage, state)
                || !magnitude(weight > 0, state) || !magnitude(scale > 0, state)) return unsafe(state);
        double span = maximum(1e-6, multiply(0.5, subtract(rightChainage, leftChainage, state), state), state);
        state.cachedSpan = span;
        if (!finite(span, state) || !magnitude(span > 0, state)) return unsafe(state);
        double x = up(divide(PI_UPPER, scale, state), state);
        if (!finite(x, state) || !magnitude(x >= 0, state)) return unsafe(state);
        double h;
        if (magnitude(x <= 1, state)) {
            double half = up(multiply(0.5, x, state), state);
            if (!finite(half, state) || !magnitude(half >= 0, state)) return unsafe(state);
            h = up(multiply(half, x, state), state);
        } else h = up(subtract(x, 0.5, state), state);
        if (!finite(h, state) || !magnitude(h >= 0, state)) return unsafe(state);
        double product = up(multiply(weight, h, state), state);
        if (!finite(product, state) || !magnitude(product >= 0, state)) return unsafe(state);
        double upper = up(divide(product, span, state), state);
        if (!finite(upper, state) || !magnitude(upper >= 0, state)) return unsafe(state);
        state.cachedUpper = upper;
        state.certificateState = SAFE;
        return upper;
    }

    /** Tests a partial bound only; never evaluates prefix plus the completed increment. */
    static boolean strictlyDominatedRaw1(double prefix, double unaryPair, double upper,
            double worst, State state, CancellationProbe probe) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(probe);
        state.checkpoint(probe);
        state.beginQuery();
        return strictlyDominatedAdmitted(prefix, unaryPair, upper, worst, state, probe);
    }

    /** Admits a query before any lazy certificate work; a non-full heap never builds it. */
    static boolean strictlyDominatedRaw1(double prefix, double unaryPair, double worst,
            boolean fullHeap, double leftChainage, double rightChainage, double weight,
            double scale, State state, CancellationProbe probe) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(probe);
        state.checkpoint(probe);
        state.beginQuery();
        if (!fullHeap) return fallback(state);
        double upper = upperTriple(leftChainage, rightChainage, weight, scale, state);
        return strictlyDominatedAdmitted(prefix, unaryPair, upper, worst, state, probe);
    }

    private static boolean strictlyDominatedAdmitted(double prefix, double unaryPair, double upper,
            double worst, State state, CancellationProbe probe) {
        if (state.certificateState != SAFE
                || Double.doubleToRawLongBits(upper) != Double.doubleToRawLongBits(state.cachedUpper)
                || !finite(prefix, state) || !finite(unaryPair, state) || !finite(worst, state)) return fallback(state);
        double prefixMagnitude = absolute(prefix, state);
        double unaryMagnitude = absolute(unaryPair, state);
        if (!magnitude(prefixMagnitude <= QUARTER_MAX, state)
                || !magnitude(unaryMagnitude <= QUARTER_MAX, state)
                || !magnitude(upper <= QUARTER_MAX, state)) return fallback(state);
        state.arithmeticOps = Math.addExact(state.arithmeticOps, 1);
        state.partialAdditions = Math.addExact(state.partialAdditions, 1);
        double lower = prefix + unaryPair;
        state.strictComparisons = Math.addExact(state.strictComparisons, 1);
        if (!(lower > worst)) return fallback(state);
        state.checkpoint(probe);
        state.skippedStreams = Math.addExact(state.skippedStreams, 1);
        state.skippedLogicalPrefixes = Math.addExact(state.skippedLogicalPrefixes, 1);
        return true;
    }

    private static double unsafe(State state) {
        state.certificateFallbacks = Math.addExact(state.certificateFallbacks, 1);
        return Double.NaN;
    }

    private static boolean fallback(State state) {
        state.boundFallbacks = Math.addExact(state.boundFallbacks, 1);
        return false;
    }

    private static boolean finite(double value, State state) {
        state.finiteTests = Math.addExact(state.finiteTests, 1);
        return Double.isFinite(value);
    }

    private static boolean magnitude(boolean condition, State state) {
        state.magnitudeTests = Math.addExact(state.magnitudeTests, 1);
        return condition;
    }

    private static double up(double value, State state) {
        state.outwardSteps = Math.addExact(state.outwardSteps, 1);
        return Math.nextUp(value);
    }

    private static double multiply(double left, double right, State state) {
        state.arithmeticOps = Math.addExact(state.arithmeticOps, 1);
        return left * right;
    }

    private static double divide(double left, double right, State state) {
        state.arithmeticOps = Math.addExact(state.arithmeticOps, 1);
        return left / right;
    }

    private static double subtract(double left, double right, State state) {
        state.arithmeticOps = Math.addExact(state.arithmeticOps, 1);
        return left - right;
    }

    private static double maximum(double left, double right, State state) {
        state.arithmeticOps = Math.addExact(state.arithmeticOps, 1);
        return Math.max(left, right);
    }

    private static double absolute(double value, State state) {
        state.arithmeticOps = Math.addExact(state.arithmeticOps, 1);
        return Math.abs(value);
    }
}
