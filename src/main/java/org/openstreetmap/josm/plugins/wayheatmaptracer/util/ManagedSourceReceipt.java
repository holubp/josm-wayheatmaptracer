package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import java.util.Objects;
import java.util.function.Supplier;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator;

/** Factual managed acquisition owner and generation retained for Apply and Redo. */
public final class ManagedSourceReceipt {
    private final TileFetchCoordinator capturedOwner;
    private final ManagedTileGeneration capturedGeneration;
    private final ManagedHeatmapConfig capturedSettings;
    private final String capturedProjection;
    private final Supplier<TileFetchCoordinator> currentOwner;
    private final Supplier<ManagedHeatmapConfig> currentSettings;
    private final Supplier<String> currentProjection;

    /** Binds the actual raster request generation to current source-owner suppliers. */
    public ManagedSourceReceipt(TileFetchCoordinator owner, LiveBPreviewService.Captured captured,
            ManagedHeatmapConfig settings, Supplier<TileFetchCoordinator> currentOwner,
            Supplier<ManagedHeatmapConfig> currentSettings, Supplier<String> currentProjection) {
        this.capturedOwner = Objects.requireNonNull(owner, "owner");
        this.capturedSettings = Objects.requireNonNull(settings, "settings");
        this.currentOwner = Objects.requireNonNull(currentOwner, "currentOwner");
        this.currentSettings = Objects.requireNonNull(currentSettings, "currentSettings");
        this.currentProjection = Objects.requireNonNull(currentProjection, "currentProjection");
        if (captured == null || captured.managedRaster() == null) {
            throw new IllegalArgumentException("Managed source receipt requires a managed raster");
        }
        capturedGeneration = captured.managedRaster().generation();
        capturedProjection = captured.projectionCode();
        if (capturedGeneration.value() != captured.network().sourceGeneration()
                || capturedGeneration.value() != Math.max(0L, settings.cacheBuster())
                || !captured.managedRaster().sourceIdentity().equals(
                    "managed-selected-" + settings.color() + "-g" + settings.cacheBuster())) {
            throw new IllegalArgumentException("Managed raster and captured settings lineage differ");
        }
    }

    /** Rejects source changes without acquiring tiles or exposing credentials. */
    public void requireCurrent() {
        TileFetchCoordinator owner = currentOwner.get();
        if (owner != capturedOwner || !owner.isActiveGeneration(capturedGeneration)
                || !capturedSettings.hasSameManagedSource(currentSettings.get())
                || !capturedProjection.equals(currentProjection.get())) {
            throw new IllegalStateException("The captured managed source changed after alignment");
        }
    }
}
