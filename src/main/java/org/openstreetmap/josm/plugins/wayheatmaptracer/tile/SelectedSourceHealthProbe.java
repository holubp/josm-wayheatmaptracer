package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;

/** Asynchronously checks one selected source tile near the current map centre. */
public final class SelectedSourceHealthProbe {
    private static final int PROBE_ZOOM = 15;
    private final TileFetchCoordinator coordinator;

    /**
     * Creates a probe using the plugin-owned tile coordinator.
     *
     * @param coordinator shared acquisition coordinator
     */
    public SelectedSourceHealthProbe(TileFetchCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /**
     * Checks one selected activity/palette tile without testing unrelated aggregate palettes.
     *
     * @param config current managed settings
     * @param location visible map location to test
     * @param cachePolicy whether normal plugin cache reads are permitted
     * @param cancellation lifecycle token for this probe
     * @return asynchronous controlled result
     */
    public CompletionStage<SelectedSourceProbeResult> probe(ManagedHeatmapConfig config, LatLon location,
        TileCachePolicy cachePolicy, CancellationToken cancellation) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(location, "location");
        ManagedTileGeneration generation = new ManagedTileGeneration(Math.max(0L, config.cacheBuster()));
        coordinator.updateActiveGeneration(generation);
        TileRequest request = new TileRequest(addressAt(config, location), generation, TilePurpose.ACCESS_PROBE,
            cachePolicy, Instant.now().plusSeconds(30), cancellation);
        return coordinator.fetch(request, CredentialSnapshot.fromConfig(config)).thenApply(SelectedSourceProbeResult::from);
    }

    static ManagedTileAddress addressAt(ManagedHeatmapConfig config, LatLon location) {
        double latitude = Math.max(-85.05112878, Math.min(85.05112878, location.lat()));
        int tiles = 1 << PROBE_ZOOM;
        int x = clamp((int) Math.floor((location.lon() + 180.0) / 360.0 * tiles), tiles - 1);
        double sinLatitude = Math.sin(Math.toRadians(latitude));
        double worldY = (0.5 - Math.log((1.0 + sinLatitude) / (1.0 - sinLatitude))
            / (4.0 * Math.PI)) * tiles;
        int y = clamp((int) Math.floor(worldY), tiles - 1);
        return new ManagedTileAddress(config.activity(), config.color(), PROBE_ZOOM, x, y);
    }

    private static int clamp(int value, int maximum) {
        return Math.max(0, Math.min(maximum, value));
    }
}
