package org.openstreetmap.josm.plugins.wayheatmaptracer.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class V022ExecutionWorkReceiptTest {
    private static final String CURRENT = "terminal-path-count-scored-work-v2";
    private static final String OLD = "terminal-path-count-cumulative-work-v1";

    private static InferenceWorkAccounting stage(String policy) {
        return new InferenceWorkAccounting(policy, 2, 1, 3, 0);
    }

    @Test
    void currentPolicyRequiresEveryExecutedStage() {
        var current = new ExecutionWorkReceipt(List.of(stage(CURRENT), stage(CURRENT)), 7);
        assertTrue(current.hasPolicy(CURRENT));
        assertFalse(new ExecutionWorkReceipt(List.of(stage(CURRENT), stage(OLD)), 7).hasPolicy(CURRENT));
    }

    @Test
    void cancelledAndNonBResultsCannotAttestExecutedBSolver() {
        var receipt = new ExecutionWorkReceipt(List.of(stage(CURRENT)), 0);
        assertThrows(IllegalArgumentException.class, () -> result(TrackerMode.PROBABILISTIC,
                TraceHypothesisSet.Status.CANCELLED, receipt));
        assertThrows(IllegalArgumentException.class, () -> result(TrackerMode.CORRIDOR_AWARE,
                TraceHypothesisSet.Status.NO_ROUTE, receipt));
    }

    @Test
    void standaloneBHasOneStageAndNoIndependentEngineWork() {
        assertThrows(IllegalArgumentException.class, () -> result(TrackerMode.PROBABILISTIC,
                TraceHypothesisSet.Status.NO_ROUTE, new ExecutionWorkReceipt(List.of(stage(CURRENT)), 1)));
        assertThrows(IllegalArgumentException.class, () -> result(TrackerMode.PROBABILISTIC,
                TraceHypothesisSet.Status.NO_ROUTE, new ExecutionWorkReceipt(List.of(stage(CURRENT), stage(CURRENT)), 0)));
    }

    @Test
    void aggregateAndCountBoundsAreCheckedBeforeReceiptPublication() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionWorkReceipt(List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionWorkReceipt(
                List.of(stage(CURRENT), stage(CURRENT), stage(CURRENT)), 0));
        assertThrows(ArithmeticException.class, () -> new ExecutionWorkReceipt(List.of(stage(CURRENT)), Long.MAX_VALUE));
    }

    private static TraceHypothesisSet result(TrackerMode engine, TraceHypothesisSet.Status status,
            ExecutionWorkReceipt receipt) {
        return new TraceHypothesisSet(engine, List.of(), status, false, 0,
                receipt.computedTransitions(), "controlled no-route", Optional.of(receipt));
    }
}
