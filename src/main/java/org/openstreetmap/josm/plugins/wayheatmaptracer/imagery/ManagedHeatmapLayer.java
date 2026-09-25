package org.openstreetmap.josm.plugins.wayheatmaptracer.imagery;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.jcs3.access.behavior.ICacheAccess;
import org.openstreetmap.gui.jmapviewer.Tile;
import org.openstreetmap.gui.jmapviewer.interfaces.TileJob;
import org.openstreetmap.gui.jmapviewer.interfaces.TileLoader;
import org.openstreetmap.gui.jmapviewer.interfaces.TileLoaderListener;
import org.openstreetmap.josm.data.cache.BufferedImageCacheEntry;
import org.openstreetmap.josm.data.cache.CacheEntry;
import org.openstreetmap.josm.data.cache.CacheEntryAttributes;
import org.openstreetmap.josm.data.cache.ICachedLoaderListener.LoadResult;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.data.imagery.TMSCachedTileLoader;
import org.openstreetmap.josm.data.imagery.TMSCachedTileLoaderJob;
import org.openstreetmap.josm.data.imagery.TileJobOptions;
import org.openstreetmap.josm.gui.layer.TMSLayer;
import org.openstreetmap.josm.gui.layer.imagery.TileSourceDisplaySettings;

/**
 * Managed Strava display layer that paints confirmed spatially empty source tiles transparently.
 *
 * <p>The substitution is deliberately limited to JOSM's visual TMS path. Plugin-owned alignment,
 * source probing, and aggregate detection continue to receive {@code NO_TILE} for the same address.</p>
 */
public final class ManagedHeatmapLayer extends TMSLayer {
    private static final Map<Integer, BufferedImageCacheEntry> TRANSPARENT_TILES = new ConcurrentHashMap<>();
    private static final Set<String> RESERVED_CACHE_METADATA = Set.of(
        "noTileAtZoom", "Etag", "lastModification", "expirationTime",
        "httpResponseCode", "errorMessage", "exception");
    private final VisibleSourceEpoch sourceEpoch = new VisibleSourceEpoch();

    /**
     * Creates a managed display layer for the supplied imagery definition.
     *
     * @param info managed Strava imagery definition
     */
    public ManagedHeatmapLayer(ImageryInfo info) {
        super(info);
    }

    /** Returns the source owner whose revisions guard rendered alignment evidence. */
    public VisibleSourceEpoch sourceEpoch() {
        return sourceEpoch;
    }

    private void mutateVisibleSource(Runnable mutation) {
        VisibleSourceEpoch epoch = sourceEpoch;
        if (epoch == null) {
            mutation.run();
            return;
        }
        synchronized (epoch) {
            epoch.sourceChanged();
            try {
                mutation.run();
            } finally {
                epoch.sourceChanged();
            }
        }
    }

    @Override
    public void setOpacity(double opacity) {
        mutateVisibleSource(() -> super.setOpacity(opacity));
    }

    @Override
    public void setVisible(boolean visible) {
        mutateVisibleSource(() -> super.setVisible(visible));
    }

    @Override
    public void setName(String name) {
        mutateVisibleSource(() -> super.setName(name));
    }

    @Override
    public void filterChanged() {
        mutateVisibleSource(super::filterChanged);
    }

    @Override
    public void displaySettingsChanged(TileSourceDisplaySettings.DisplaySettingsChangeEvent event) {
        mutateVisibleSource(() -> super.displaySettingsChanged(event));
    }

    @Override
    public void clearTileCache() {
        mutateVisibleSource(super::clearTileCache);
    }

    @Override
    public void destroy() {
        mutateVisibleSource(super::destroy);
    }

    @Override
    public void tileLoadingFinished(Tile tile, boolean success) {
        mutateVisibleSource(() -> super.tileLoadingFinished(tile, success));
    }

    @Override
    protected Class<? extends TileLoader> getTileLoaderClass() {
        return EmptyAreaTileLoader.class;
    }

    /**
     * Converts one confirmed spatial 404 into a transient transparent display entry.
     *
     * @param entry original JOSM cache entry
     * @param attributes original response/cache attributes
     * @param result original load result
     * @param tileSize display tile size in pixels
     * @return normalized display inputs; unchanged for every non-spatial failure
     */
    static DisplayLoad normalizeForDisplay(
        CacheEntry entry,
        CacheEntryAttributes attributes,
        LoadResult result,
        int tileSize
    ) {
        if (attributes == null || result != LoadResult.SUCCESS
            || attributes.getResponseCode() != 404 || !attributes.isNoTileAtZoom()) {
            return new DisplayLoad(entry, attributes, result);
        }
        int safeTileSize = tileSize > 0 && tileSize <= 4096 ? tileSize : 512;
        CacheEntryAttributes displayAttributes = copyDisplayAttributes(attributes);
        displayAttributes.setResponseCode(200);
        return new DisplayLoad(transparentTile(safeTileSize), displayAttributes, result);
    }

    private static CacheEntryAttributes copyDisplayAttributes(CacheEntryAttributes source) {
        CacheEntryAttributes copy = new CacheEntryAttributes();
        copy.setEtag(source.getEtag());
        copy.setLastModification(source.getLastModification());
        copy.setExpirationTime(source.getExpirationTime());
        Map<String, String> metadata = new HashMap<>(source.getMetadata());
        RESERVED_CACHE_METADATA.forEach(metadata::remove);
        copy.setMetadata(metadata);
        return copy;
    }

    private static BufferedImageCacheEntry transparentTile(int tileSize) {
        return TRANSPARENT_TILES.computeIfAbsent(tileSize, size -> BufferedImageCacheEntry.pngEncoded(
            new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)));
    }

    /** Inputs passed to JOSM's ordinary tile completion and painting path. */
    record DisplayLoad(CacheEntry entry, CacheEntryAttributes attributes, LoadResult result) {
    }

    /** Cached JOSM tile loader that installs the managed display completion job. */
    public static final class EmptyAreaTileLoader extends TMSCachedTileLoader {
        /**
         * Creates a loader through JOSM's reflective cached-loader factory.
         *
         * @param listener tile completion listener
         * @param cache ordinary JOSM TMS cache
         * @param options JOSM request options and managed Cookie header
         */
        public EmptyAreaTileLoader(
            TileLoaderListener listener,
            ICacheAccess<String, BufferedImageCacheEntry> cache,
            TileJobOptions options
        ) {
            super(listener, cache, options);
        }

        @Override
        public TileJob createTileLoaderJob(Tile tile) {
            return new EmptyAreaTileLoaderJob(listener, tile, cache, options, getDownloadExecutor(),
                listener instanceof ManagedHeatmapLayer layer ? layer : null);
        }
    }

    /** Adapts only the completion value delivered to the managed display tile. */
    private static final class EmptyAreaTileLoaderJob extends TMSCachedTileLoaderJob {
        private final Tile displayTile;
        private final ManagedHeatmapLayer sourceOwner;
        private final AtomicInteger pendingSubmissions = new AtomicInteger();

        EmptyAreaTileLoaderJob(
            TileLoaderListener listener,
            Tile tile,
            ICacheAccess<String, BufferedImageCacheEntry> cache,
            TileJobOptions options,
            java.util.concurrent.ThreadPoolExecutor downloadExecutor,
            ManagedHeatmapLayer sourceOwner
        ) {
            super(listener, tile, cache, options, downloadExecutor);
            this.displayTile = tile;
            this.sourceOwner = sourceOwner;
        }

        @Override
        public void submit(boolean force) {
            if (sourceOwner != null) {
                sourceOwner.sourceEpoch().beginLoad();
                pendingSubmissions.incrementAndGet();
            }
            super.submit(force);
        }

        @Override
        public void loadingFinished(CacheEntry entry, CacheEntryAttributes attributes, LoadResult result) {
            VisibleSourceEpoch epoch = sourceOwner == null ? null : sourceOwner.sourceEpoch();
            if (epoch == null) {
                completeDisplayLoad(entry, attributes, result);
            } else {
                synchronized (epoch) {
                    try {
                        completeDisplayLoad(entry, attributes, result);
                    } finally {
                        finishSourceLoad();
                    }
                }
            }
        }

        private void completeDisplayLoad(CacheEntry entry, CacheEntryAttributes attributes,
                LoadResult result) {
            DisplayLoad display = normalizeForDisplay(
                entry, attributes, result, displayTile.getTileSource().getTileSize());
            super.loadingFinished(display.entry(), display.attributes(), display.result());
        }

        private void finishSourceLoad() {
            if (sourceOwner != null && pendingSubmissions.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                sourceOwner.sourceEpoch().finishLoad();
            }
        }
    }
}
