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
    private final SourceParameterCertificate sourceParameters;
    private final ManagedGroundTransform groundTransform;
    private final AttemptMemoryLedger.Owner producerParent;
    private final org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget budget;

    RetainedManagedScalarProfiles(TileHeatmapSampler.TileMosaic sourceMosaic, ManagedScalarGrid grid,
            MultiScaleProfileSet profileSet, AttemptMemoryLedger.Owner owner,
            TileHeatmapSampler.PreparedManagedCapture sourceCapture,
            SourceParameterCertificate sourceParameters, ManagedGroundTransform groundTransform,
            AttemptMemoryLedger.Owner producerParent,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget budget) {
        this.sourceMosaic = sourceMosaic;
        this.grid = grid;
        this.profileSet = profileSet;
        this.owner = owner;
        this.sourceCapture = sourceCapture;
        this.sourceParameters = sourceParameters;
        this.groundTransform = groundTransform;
        this.producerParent = producerParent;
        this.budget = budget;
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
    /** Present only for rows produced from a pre-fetch source-owned capture. */
    public java.util.Optional<SourceParameterCertificate> sourceParameters() {
        requireOpen(); return java.util.Optional.ofNullable(sourceParameters);
    }
    public java.util.Optional<ManagedGroundTransform> groundTransform() {
        requireOpen(); return java.util.Optional.ofNullable(groundTransform);
    }
    public boolean boundTo(AttemptMemoryLedger.Owner parent,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget sharedBudget) {
        requireOpen(); return producerParent == parent && budget == sharedBudget;
    }
    @Override public void close() { owner.close(); }
}
