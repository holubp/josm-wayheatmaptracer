package org.openstreetmap.josm.plugins.wayheatmaptracer.util;

import java.util.Objects;
import java.util.function.Supplier;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedModernPreviewSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.AlignmentTileSourcePlan;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileFetchCoordinator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileRuntime;

/** Factual managed acquisition owner and generation retained for Apply and Redo. */
public final class ManagedSourceReceipt {
    private final TileFetchCoordinator capturedOwner;
    private final ManagedTileGeneration capturedGeneration;
    private final ManagedHeatmapConfig capturedSettings;
    private final String capturedProjection;
    private final Supplier<TileFetchCoordinator> currentOwner;
    private final Supplier<ManagedHeatmapConfig> currentSettings;
    private final Supplier<String> currentProjection;
    private final ManagedModernPreviewSource.SourceRasters capturedSources;
    private final String sourceTier;

    /** Binds direct managed acquisition to the live plugin runtime, saved settings, and projection. */
    public static ManagedSourceReceipt forCurrentPlugin(TileFetchCoordinator owner,
            LiveBPreviewService.Captured captured, ManagedHeatmapConfig settings) {
        return forCurrentPlugin(owner, captured, settings, "selected-visible");
    }

    /** Binds the chosen native, selected-raster mapping, or complete aggregate owner. */
    public static ManagedSourceReceipt forCurrentPlugin(TileFetchCoordinator owner,
            LiveBPreviewService.Captured captured, ManagedHeatmapConfig settings,
            String sourceTier) {
        return new ManagedSourceReceipt(owner, captured, settings,
                sourceTier,
                ManagedTileRuntime::initializedCoordinator, PluginPreferences::load,
                () -> ProjectionRegistry.getProjection().toCode());
    }

    /** Binds the actual raster request generation to current source-owner suppliers. */
    public ManagedSourceReceipt(TileFetchCoordinator owner, LiveBPreviewService.Captured captured,
            ManagedHeatmapConfig settings, Supplier<TileFetchCoordinator> currentOwner,
            Supplier<ManagedHeatmapConfig> currentSettings, Supplier<String> currentProjection) {
        this(owner, captured, settings, "selected-visible", currentOwner, currentSettings,
                currentProjection);
    }

    public ManagedSourceReceipt(TileFetchCoordinator owner, LiveBPreviewService.Captured captured,
            ManagedHeatmapConfig settings, String sourceTier,
            Supplier<TileFetchCoordinator> currentOwner,
            Supplier<ManagedHeatmapConfig> currentSettings, Supplier<String> currentProjection) {
        this.capturedOwner = Objects.requireNonNull(owner, "owner");
        this.capturedSettings = Objects.requireNonNull(settings, "settings");
        this.sourceTier = Objects.requireNonNull(sourceTier, "sourceTier");
        this.currentOwner = Objects.requireNonNull(currentOwner, "currentOwner");
        this.currentSettings = Objects.requireNonNull(currentSettings, "currentSettings");
        this.currentProjection = Objects.requireNonNull(currentProjection, "currentProjection");
        if (captured == null || captured.managedRaster() == null) {
            throw new IllegalArgumentException("Managed source receipt requires a managed raster");
        }
        capturedSources = captured.sourceRasters();
        capturedGeneration = captured.managedRaster().generation();
        capturedProjection = captured.projectionCode();
        if (capturedGeneration.value() != captured.network().sourceGeneration()
                || capturedGeneration.value() != Math.max(0L, settings.cacheBuster())
                || !captured.managedRaster().sourceIdentity().equals(
                    "managed-selected-" + settings.color() + "-g" + settings.cacheBuster())
                || capturedSources != null && (!capturedSources.plan()
                        .equals(AlignmentTileSourcePlan.from(settings))
                        || captured.sourceOwner() != owner)
                || !validSourceChoice(captured)) {
            throw new IllegalArgumentException("Managed raster and captured settings lineage differ");
        }
    }

    private boolean validSourceChoice(LiveBPreviewService.Captured captured) {
        if ("selected-visible".equals(sourceTier)) return true;
        if (capturedSources == null) return false;
        if ("all-colors-combined".equals(sourceTier)) {
            return capturedSources.provenCompleteAggregate(capturedOwner);
        }
        return sourceTier.startsWith("selected-mapping-")
                && captured.alternativeMappings().contains(
                        sourceTier.substring("selected-mapping-".length()));
    }

    /** Rejects source changes without acquiring tiles or exposing credentials. */
    public void requireCurrent() {
        TileFetchCoordinator owner = currentOwner.get();
        ManagedHeatmapConfig settings = currentSettings.get();
        if (owner != capturedOwner || !owner.isActiveGeneration(capturedGeneration)
                || !capturedSettings.hasSameManagedSource(settings)
                || capturedSources != null && !sameChoiceSettings(settings)
                || !capturedProjection.equals(currentProjection.get())
                || "all-colors-combined".equals(sourceTier)
                        && (capturedSources == null
                                || !capturedSources.provenCompleteAggregate(owner))) {
            throw new IllegalStateException("The captured managed source changed after alignment");
        }
    }

    private boolean sameChoiceSettings(ManagedHeatmapConfig current) {
        return current != null
                && capturedSources.plan().equals(AlignmentTileSourcePlan.from(current))
                && capturedSettings.intensitySamplingMode() == current.intensitySamplingMode()
                && capturedSettings.inferenceMode() == current.inferenceMode()
                && capturedSettings.inferenceZoom() == current.inferenceZoom()
                && capturedSettings.validationZoom() == current.validationZoom()
                && (!capturedSettings.intensitySamplingMode().usesColorMapping()
                        || capturedSettings.multiColorDetection() == current.multiColorDetection());
    }
}
