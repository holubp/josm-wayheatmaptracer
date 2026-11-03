package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** One retained L0 grid and the Legacy profiles produced from its managed source. */
public final class RetainedManagedScalarProfiles implements AutoCloseable {
    private final TileHeatmapSampler.TileMosaic sourceMosaic;
    private final ManagedScalarGrid grid;
    private final MultiScaleProfileSet profileSet;
    private final AttemptMemoryLedger.Owner owner;
    private final TileHeatmapSampler.PreparedManagedCapture sourceCapture;

    RetainedManagedScalarProfiles(TileHeatmapSampler.TileMosaic sourceMosaic, ManagedScalarGrid grid,
            MultiScaleProfileSet profileSet, AttemptMemoryLedger.Owner owner,
            TileHeatmapSampler.PreparedManagedCapture sourceCapture) {
        this.sourceMosaic = sourceMosaic;
        this.grid = grid;
        this.profileSet = profileSet;
        this.owner = owner;
        this.sourceCapture = sourceCapture;
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
    /** Present only when this exact producer used a pre-fetch source-owned request receipt. */
    public java.util.Optional<TileHeatmapSampler.PreparedManagedCapture> sourceCapture() {
        requireOpen();
        return java.util.Optional.ofNullable(sourceCapture);
    }
    @Override public void close() { owner.close(); }
}
