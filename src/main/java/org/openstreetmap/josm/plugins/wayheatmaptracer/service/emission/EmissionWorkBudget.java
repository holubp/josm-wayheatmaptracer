package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.Map;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Objects;
import java.util.concurrent.CancellationException;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Shared bounded-work admission ledger for optional emission phases. */
public final class EmissionWorkBudget {
    private static final long TOTAL_WORK_HARD_CAP = 128_000_000L;
    private static final long PAIR_VISIT_HARD_CAP = 8_000_000L;
    private static final String LIMIT_MESSAGE = "Emission work limit exceeded";
    private static final String CANCELLATION_MESSAGE = "Emission work was cancelled";

    /** Categories of work charged before execution. */
    public enum Operation {
        PREPARATION(false), CELL_QUERY(false), NODE_READ(false), MODE_EXTRACTION(false),
        REGION_QUERY(false), ALLOCATION(false), PAIR_INITIALIZATION(true), PAIR_LOOKUP(true),
        TRANSITION(true), TRANSPORT(true), TIE_COMPARISON(true), BACKPOINTER_VISIT(true);

        private final boolean pairVisits;

        Operation(boolean pairVisits) {
            this.pairVisits = pairVisits;
        }

        /** Returns whether this category consumes the pair-visit allowance. */
        public boolean chargesPairVisits() {
            return pairVisits;
        }
    }

    /** Resource whose admission limit was exceeded. */
    public enum LimitKind { TOTAL_WORK, PAIR_VISITS }

    /** Immutable view of this attempt's emission work accounting. */
    public record Snapshot(long workLimit, long pairLimit, long workUsed, long pairVisits,
        Map<Operation, Long> operationUnits) {
        public Snapshot {
            Objects.requireNonNull(operationUnits, "operationUnits");
            EnumMap<Operation, Long> complete = new EnumMap<>(Operation.class);
            for (Operation operation : Operation.values()) {
                complete.put(operation, operationUnits.getOrDefault(operation, 0L));
            }
            operationUnits = Collections.unmodifiableMap(complete);
        }
    }

    /** Typed, safe-to-report resource limit failure. */
    public static final class LimitExceededException extends RuntimeException {
        private final LimitKind kind;
        private final long requestedUnits;
        private final long used;
        private final long limit;

        private LimitExceededException(LimitKind kind, long requestedUnits, long used, long limit) {
            super(LIMIT_MESSAGE);
            this.kind = kind;
            this.requestedUnits = requestedUnits;
            this.used = used;
            this.limit = limit;
        }

        public LimitKind kind() { return kind; }
        public long requestedUnits() { return requestedUnits; }
        public long used() { return used; }
        public long limit() { return limit; }
    }

    private final long workLimit;
    private final long pairLimit;
    private final int stateFamilyLimit;
    private final CancellationProbe cancellation;
    private final EnumMap<Operation, Long> operationUnits = new EnumMap<>(Operation.class);
    private long workUsed;
    private long pairVisits;
    private RuntimeException terminalFailure;

    /** Creates a budget using caller limits bounded by the approved hard caps. */
    public EmissionWorkBudget(TraceBudgets limits, CancellationProbe cancellation) {
        Objects.requireNonNull(limits, "limits");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        workLimit = Math.min(TOTAL_WORK_HARD_CAP, limits.maximumTransitions());
        pairLimit = Math.min(PAIR_VISIT_HARD_CAP, limits.maximumPairVisits());
        stateFamilyLimit = Math.min(96, limits.maximumStatesPerProfile());
        for (Operation operation : Operation.values()) {
            operationUnits.put(operation, 0L);
        }
    }

    /** Admits units before an operation, or permanently refuses further work. */
    public synchronized void charge(Operation op, long units) {
        Objects.requireNonNull(op, "op");
        if (units < 0) {
            throw new IllegalArgumentException("units must be non-negative");
        }
        if (terminalFailure != null) {
            throw terminalFailure;
        }
        try {
            if (cancellation.cancelled()) {
                terminalFailure = new CancellationException(CANCELLATION_MESSAGE);
                throw terminalFailure;
            }
        } catch (CancellationException cancelled) {
            terminalFailure = cancelled;
            throw cancelled;
        }

        if (exceeds(workUsed, workLimit, units)) {
            terminalFailure = new LimitExceededException(LimitKind.TOTAL_WORK, units,
                workUsed, workLimit);
            throw terminalFailure;
        }
        if (op.chargesPairVisits() && exceeds(pairVisits, pairLimit, units)) {
            terminalFailure = new LimitExceededException(LimitKind.PAIR_VISITS, units,
                pairVisits, pairLimit);
            throw terminalFailure;
        }

        workUsed += units;
        if (op.chargesPairVisits()) {
            pairVisits += units;
        }
        operationUnits.put(op, operationUnits.get(op) + units);
    }

    private static boolean exceeds(long used, long limit, long requested) {
        return requested > limit - used;
    }

    /** Returns the current immutable accounting snapshot. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(workLimit, pairLimit, workUsed, pairVisits, operationUnits);
    }

    /** Effective original-row state-family ceiling, including a stricter caller limit. */
    public int stateFamilyLimit() { return stateFamilyLimit; }

    /** Retains a same-attempt terminal producer/memory failure before any later phase can retry. */
    public synchronized void failTerminally(RuntimeException failure) {
        if (terminalFailure == null) terminalFailure = Objects.requireNonNull(failure, "terminal failure");
    }
}
