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
    public Optional<ManagedAEndpointAuthority> managedAEndpoints(){requireOpen();return context.managedAEndpoints();}
    /** Managed-A only: existing prepaid source ticket, after the actual outer result copies. */
    public FinalPublication prepareManagedAFinalPublication(AttemptMemoryLedger.Owner owner,long copyWork,long copyBytes){
        if(managedAEndpoints().isEmpty())throw new IllegalArgumentException("Captured managed A input is required");
        return prepareManagedFinalPublication(owner,copyWork,copyBytes);
    }
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
        requireRetainedSourceUnchanged();
        legacyBinding.ifPresent(binding -> binding.requireNativeProfilesUnchanged(workBudget));
    }
    /** Package-owned single-use ticket; no caller-supplied source facts or budget bypass. */
    FinalPublication prepareManagedFinalPublication(AttemptMemoryLedger.Owner owner,long copyWork,long copyBytes){
        requireOpen();if(terminalExecutionFailure!=null)throw terminalExecutionFailure;
        workBudget.charge(EmissionWorkBudget.Operation.PREPARATION,32L+legacyBinding.map(value->value.observations().size()).orElse(0));
        workBudget.charge(EmissionWorkBudget.Operation.PREPARATION,Math.addExact(copyWork,
            Math.addExact(Math.multiplyExact(8L*context.observations().size(),profiles.levels().size()),
                Math.addExact(legacyBinding.map(LegacyEmissionBinding::finalPublicationWork).orElse(0L),context.managedAEndpointFinalPublicationWork()))));
        workBudget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);
        var scratch=owner.child("managed-final-publication");
        try{scratch.reserve(copyBytes);return new FinalPublication(scratch,context.prepareFinalPublication(scratch));}
        catch(RuntimeException failure){scratch.close();if(ExperimentalMeasuredModeInventory.terminal(failure))recordTerminalExecutionFailure(failure);throw failure;}
    }
    public final class FinalPublication implements AutoCloseable{
        private final AttemptMemoryLedger.Owner scratch;
        private final OptionalEmissionContextFactory.ManagedCaptureBinding.FinalPublicationCheck source;
        private boolean used;
        private FinalPublication(AttemptMemoryLedger.Owner scratch,OptionalEmissionContextFactory.ManagedCaptureBinding.FinalPublicationCheck source){this.scratch=scratch;this.source=source;}
        public void validate(){
            if(used||scratch.isClosed())throw new IllegalStateException("Managed final publication is closed or consumed");used=true;
            requireOpen();if(terminalExecutionFailure!=null)throw terminalExecutionFailure;
            workBudget.requireActiveForFinalPublication();
            if(source!=null)source.validate();
            requireOpen();if(terminalExecutionFailure!=null)throw terminalExecutionFailure;
            workBudget.requireActiveForFinalPublication();
            context.requireProfilesUnchangedForFinalPublication(profiles);
            legacyBinding.ifPresent(LegacyEmissionBinding::requireNativeProfilesUnchangedForFinalPublication);
        }
        @Override public void close(){try{if(source!=null)source.close();}finally{scratch.close();}}
    }
    /** Source-only freshness; a native engine must separately validate its own original state authority. */
    public void requireRetainedSourceUnchanged() {
        requireOpen();
        RuntimeException failure = terminalExecutionFailure;
        if (failure != null) throw failure;
        workBudget.charge(EmissionWorkBudget.Operation.PREPARATION,
            Math.multiplyExact((long) context.observations().size(), profiles.levels().size()));
        context.requireProfilesUnchanged(profiles);
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
    /** Shares the unchanged retained source without granting Legacy or A original-state authority. */
    public synchronized AttemptMemoryLedger.Owner openRetainedSourceExecutionOwner(String phase) {
        requireRetainedSourceUnchanged();
        workBudget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);
        return retainedOwner.child(phase);
    }

    /** Holds the input's charged receipt tree after logical input closure. */
    synchronized ResultLease retainResultLifetime() {
        requireOpen();
        liveResults++;
        return new ResultLease(this);
    }
    /** Managed A is still a borrowed API; this lease retains charged payload until result closure. */
    public synchronized AutoCloseable retainManagedAResultLifetime(){
        if(managedAEndpoints().isEmpty())throw new IllegalArgumentException("Managed A lifetime requires its captured input");
        workBudget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);var owner=retainedOwner.child("managed-a-result-lifetime");
        try{
            var memory=owner.reserve(128);var lease=retainResultLifetime();
            AutoCloseable result=new AutoCloseable(){boolean closed;
                public void close(){if(closed)return;closed=true;try{owner.close();}finally{lease.close();}}
            };memory.adopt(result);return result;
        }catch(RuntimeException failure){owner.close();throw failure;}
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
