package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** One retained L0 grid and the Legacy profiles produced from its managed source. */
public final class RetainedManagedScalarProfiles implements AutoCloseable {
    private final TileHeatmapSampler.TileMosaic sourceMosaic;
    private final ManagedScalarGrid grid;
    private final MultiScaleProfileSet profileSet;
    private final AttemptMemoryLedger.Owner owner;

    RetainedManagedScalarProfiles(TileHeatmapSampler.TileMosaic sourceMosaic, ManagedScalarGrid grid,
            MultiScaleProfileSet profileSet, AttemptMemoryLedger.Owner owner) {
        this.sourceMosaic = sourceMosaic;
        this.grid = grid;
        this.profileSet = profileSet;
        this.owner = owner;
    }
    private void requireOpen() {
        if (owner.isClosed()) throw new IllegalStateException("Retained managed profiles are closed");
    }
    public TileHeatmapSampler.TileMosaic sourceMosaic() { requireOpen(); return sourceMosaic; }
    public ManagedScalarGrid grid() { requireOpen(); return grid; }
    public MultiScaleProfileSet profileSet() { requireOpen(); return profileSet; }
    public List<RenderedHeatmapSampler.CrossSectionProfile> profiles() {
        return profileSet().levelZeroProfiles();
    }
    @Override public void close() { owner.close(); }
}
