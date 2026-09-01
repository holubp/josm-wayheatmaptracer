package org.openstreetmap.josm.plugins.wayheatmaptracer.tile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;

/** Asynchronously checks a bounded stencil of one selected managed source. */
public final class SelectedSourceHealthProbe {
    private static final int PROBE_ZOOM = 15;
    private static final int MAX_PROBE_TILES = 5;
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
        Objects.requireNonNull(location, "location");
        return probe(config, List.of(location), cachePolicy, cancellation);
    }

    /**
     * Checks unique z15 tile identities for the selected activity and palette.
     * Requests run in input order so a validated tile or terminal auth/rate
     * status stops the stencil without adding unnecessary requests.
     *
     * @param config current managed settings
     * @param locations locations defining the bounded probe stencil
     * @param cachePolicy positive-cache policy for this probe
     * @param cancellation lifecycle token for this probe
     * @return asynchronous aggregate result
     */
    public CompletionStage<SelectedSourceProbeResult> probe(ManagedHeatmapConfig config,
        Collection<LatLon> locations, TileCachePolicy cachePolicy, CancellationToken cancellation) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(locations, "locations");
        Objects.requireNonNull(cachePolicy, "cachePolicy");
        Objects.requireNonNull(cancellation, "cancellation");
        LinkedHashSet<ManagedTileAddress> uniqueAddresses = new LinkedHashSet<>();
        for (LatLon location : locations) {
            uniqueAddresses.add(addressAt(config, Objects.requireNonNull(location, "location")));
            if (uniqueAddresses.size() == MAX_PROBE_TILES) {
                break;
            }
        }
        if (uniqueAddresses.isEmpty()) {
            throw new IllegalArgumentException("At least one probe location is required");
        }
        ManagedTileGeneration generation = new ManagedTileGeneration(Math.max(0L, config.cacheBuster()));
        coordinator.updateActiveGeneration(generation);
        return probeNext(config, new ArrayList<>(uniqueAddresses), 0, generation, cachePolicy, cancellation,
            new ArrayList<>());
    }

    private CompletionStage<SelectedSourceProbeResult> probeNext(ManagedHeatmapConfig config,
        List<ManagedTileAddress> addresses, int index, ManagedTileGeneration generation,
        TileCachePolicy cachePolicy, CancellationToken cancellation, List<TileFetchResult> results) {
        if (index >= addresses.size()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                SelectedSourceProbeResult.aggregate(results, cachePolicy == TileCachePolicy.BYPASS_READ_ALLOW_WRITE));
        }
        TileRequest request = new TileRequest(addresses.get(index), generation, TilePurpose.ACCESS_PROBE,
            cachePolicy, Instant.now().plusSeconds(30), cancellation);
        return coordinator.fetch(request, CredentialSnapshot.fromConfig(config)).thenCompose(result -> {
            results.add(result);
            if (isTerminal(result)) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                    SelectedSourceProbeResult.aggregate(results, cachePolicy == TileCachePolicy.BYPASS_READ_ALLOW_WRITE));
            }
            return probeNext(config, addresses, index + 1, generation, cachePolicy, cancellation, results);
        });
    }

    private static boolean isTerminal(TileFetchResult result) {
        return result.usable()
            || result.status() == TileFetchStatus.AUTH_FAILURE
            || result.status() == TileFetchStatus.RATE_LIMITED
            || result.status() == TileFetchStatus.CANCELLED
            || result.status() == TileFetchStatus.STALE_GENERATION;
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
