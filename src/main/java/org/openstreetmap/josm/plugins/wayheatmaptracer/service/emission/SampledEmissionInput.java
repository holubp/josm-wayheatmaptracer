package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

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

    SampledEmissionInput(MultiScaleProfileSet profiles, OptionalEmissionContext context,
            EmissionWorkBudget workBudget, AttemptMemoryLedger.Owner retainedOwner) {
        this.profiles = profiles;
        this.context = context;
        this.workBudget = workBudget;
        this.retainedOwner = retainedOwner;
    }
    public MultiScaleProfileSet profiles() { requireOpen(); return profiles; }
    public OptionalEmissionContext context() { requireOpen(); return context; }
    public EmissionWorkBudget workBudget() { requireOpen(); return workBudget; }
    private void requireOpen() {
        if (retainedOwner.isClosed()) throw new IllegalStateException("Retained emission input is closed");
    }
    /** Required at each numerical entry, using the same admitted attempt budget. */
    public void requireOpenAndUnchanged() {
        requireOpen();
        workBudget.charge(EmissionWorkBudget.Operation.PREPARATION,
            Math.multiplyExact((long) context.observations().size(), profiles.levels().size()));
        context.requireProfilesUnchanged(profiles);
    }
    @Override public void close() { retainedOwner.close(); }
}
