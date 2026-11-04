package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.Optional;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.MultiScaleProfileSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/**
 * Explicit retained lifetime for profiles/L0 and immutable source facts.
 * Native profiles keep their existing mutable-normal API; engine entry must revalidate the binding.
 */
public final class SampledEmissionInput implements AutoCloseable {
    private final MultiScaleProfileSet profiles;
    private final OptionalEmissionContext context;
    private final EmissionWorkBudget workBudget;
    private final AttemptMemoryLedger.Owner retainedOwner;
    private final Optional<LegacyEmissionBinding> legacyBinding;
    private volatile RuntimeException terminalExecutionFailure;
    private boolean logicallyClosed;
    private int liveResults;
    private ExperimentalMeasuredModeInventory measuredModes;

    SampledEmissionInput(MultiScaleProfileSet profiles, OptionalEmissionContext context,
            EmissionWorkBudget workBudget, AttemptMemoryLedger.Owner retainedOwner) {
        this(profiles, context, workBudget, retainedOwner, Optional.empty());
    }
    SampledEmissionInput(MultiScaleProfileSet profiles, OptionalEmissionContext context,
            EmissionWorkBudget workBudget, AttemptMemoryLedger.Owner retainedOwner,
            Optional<LegacyEmissionBinding> legacyBinding) {
        this.profiles = profiles;
        this.context = context;
        this.workBudget = workBudget;
        this.retainedOwner = retainedOwner;
        this.legacyBinding = java.util.Objects.requireNonNull(legacyBinding, "legacyBinding");
    }
    public MultiScaleProfileSet profiles() { requireOpen(); return profiles; }
    public OptionalEmissionContext context() { requireOpen(); return context; }
    public EmissionWorkBudget workBudget() { requireOpen(); return workBudget; }
    /** Original Legacy row ownership, absent for ordinary optional-emission inputs. */
    public Optional<LegacyEmissionBinding> legacyBinding() { requireOpen(); return legacyBinding; }
    private synchronized void requireOpen() {
        if (logicallyClosed || retainedOwner.isClosed())
            throw new IllegalStateException("Retained emission input is closed");
    }
    synchronized void requireRetainedOpen() { requireOpen(); }

    /** One immutable source-mode inventory per retained input, only on explicit revised entry. */
    public synchronized ExperimentalMeasuredModeInventory experimentalMeasuredModes() {
        try {
            requireOpenAndUnchanged();
            if (measuredModes == null) measuredModes = new ExperimentalMeasuredModeInventory(this);
            return measuredModes;
        } catch (RuntimeException failure) {
            if (ExperimentalMeasuredModeInventory.terminal(failure)) recordTerminalExecutionFailure(failure);
            throw failure;
        }
    }
    /** Required at each numerical entry, using the same admitted attempt budget. */
    public void requireOpenAndUnchanged() {
        requireOpen();
        RuntimeException failure = terminalExecutionFailure;
        if (failure != null) throw failure;
        workBudget.charge(EmissionWorkBudget.Operation.PREPARATION,
            Math.multiplyExact((long) context.observations().size(), profiles.levels().size()));
        context.requireProfilesUnchanged(profiles);
        legacyBinding.ifPresent(binding -> binding.requireNativeProfilesUnchanged(workBudget));
    }
    /** First terminal admission failure belongs to the whole retained attempt, not one phase. */
    public synchronized void recordTerminalExecutionFailure(RuntimeException failure) {
        if (terminalExecutionFailure == null) terminalExecutionFailure = failure;
        workBudget.failTerminally(failure);
    }
    /** Shares the retained input's exact attempt/lifetime with a bounded numerical service. */
    public synchronized AttemptMemoryLedger.Owner openExecutionOwner(String phase) {
        requireOpenAndUnchanged();
        workBudget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        return retainedOwner.child(phase);
    }

    /** Holds the input's charged receipt tree after logical input closure. */
    synchronized ResultLease retainResultLifetime() {
        requireOpen();
        liveResults++;
        return new ResultLease(this);
    }

    static final class ResultLease implements AutoCloseable {
        private final SampledEmissionInput input;
        private boolean closed;
        private ResultLease(SampledEmissionInput input) { this.input = input; }
        @Override public void close() {
            synchronized (input) {
                if (closed) return;
                closed = true;
                input.liveResults--;
                if (input.logicallyClosed && input.liveResults == 0)
                    input.retainedOwner.close();
            }
        }
    }

    @Override public synchronized void close() {
        if (logicallyClosed) return;
        logicallyClosed = true;
        if (liveResults == 0) retainedOwner.close();
    }
}
