package org.openstreetmap.josm.plugins.wayheatmaptracer.imagery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.jcs3.access.behavior.ICacheAccess;
import org.openstreetmap.gui.jmapviewer.interfaces.TileLoaderListener;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.cache.BufferedImageCacheEntry;
import org.openstreetmap.josm.data.cache.CacheEntryAttributes;
import org.openstreetmap.josm.data.cache.ICachedLoaderListener.LoadResult;
import org.openstreetmap.josm.data.imagery.TileJobOptions;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.IBaseDirectories;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class ManagedHeatmapLayerTest {
    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        File testDirectory = new File(System.getProperty("java.io.tmpdir"), "josm-managed-layer-test");
        Config.setBaseDirectoriesProvider(new IBaseDirectories() {
            @Override public File getPreferencesDirectory(boolean create) { return testDirectory; }
            @Override public File getUserDataDirectory(boolean create) { return testDirectory; }
            @Override public File getCacheDirectory(boolean create) { return testDirectory; }
        });
    }

    @Test
    void exposesJosmReflectiveLoaderConstructor() throws Exception {
        assertNotNull(ManagedHeatmapLayer.EmptyAreaTileLoader.class.getConstructor(
            TileLoaderListener.class, ICacheAccess.class, TileJobOptions.class));
    }

    @Test
    void filterChangeInvalidatesAVisibleSourceReceipt() {
        ManagedHeatmapLayer layer = new ManagedHeatmapLayer(new ImageryInfo(
            "Test heatmap", "https://example.invalid/{zoom}/{x}/{y}.png", "tms"));
        VisibleSourceEpoch.Receipt before = layer.sourceEpoch().captureStable();

        layer.filterChanged();

        assertThrows(IllegalStateException.class, () -> layer.sourceEpoch().requireCurrent(before));
    }

    @Test
    void opacityChangeInvalidatesAVisibleSourceReceipt() {
        ManagedHeatmapLayer layer = new ManagedHeatmapLayer(new ImageryInfo(
            "Test heatmap", "https://example.invalid/{zoom}/{x}/{y}.png", "tms"));
        VisibleSourceEpoch.Receipt before = layer.sourceEpoch().captureStable();

        layer.setOpacity(0.5);

        assertThrows(IllegalStateException.class, () -> layer.sourceEpoch().requireCurrent(before));
    }

    @Test
    void filterWriterWaitsForTheSourceLease() throws Exception {
        ManagedHeatmapLayer layer = new ManagedHeatmapLayer(new ImageryInfo(
            "Test heatmap", "https://example.invalid/{zoom}/{x}/{y}.png", "tms"));
        CountDownLatch attempted = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            attempted.countDown();
            layer.filterChanged();
            completed.countDown();
        }, "managed-filter-writer");

        synchronized (layer.sourceEpoch()) {
            writer.start();
            assertTrue(attempted.await(2, TimeUnit.SECONDS));
            assertTrue(!completed.await(100, TimeUnit.MILLISECONDS));
        }
        assertTrue(completed.await(2, TimeUnit.SECONDS));
        writer.join(2000);
    }

    @Test
    void substitutesSpatial404WithTransparentDisplayTile() throws Exception {
        BufferedImageCacheEntry original = new BufferedImageCacheEntry(new byte[0]);
        CacheEntryAttributes attributes = attributes(404, true);
        attributes.setEtag("etag-value");
        attributes.setLastModification(1234L);
        attributes.setExpirationTime(5678L);
        attributes.setMetadata(Map.of("safe", "value"));

        ManagedHeatmapLayer.DisplayLoad normalized = ManagedHeatmapLayer.normalizeForDisplay(
            original, attributes, LoadResult.SUCCESS, 512);

        assertNotSame(original, normalized.entry());
        assertNotSame(attributes, normalized.attributes());
        assertEquals(LoadResult.SUCCESS, normalized.result());
        assertEquals(200, normalized.attributes().getResponseCode());
        assertFalse(normalized.attributes().isNoTileAtZoom());
        assertEquals("value", normalized.attributes().getMetadata().get("safe"));
        assertEquals("etag-value", normalized.attributes().getEtag());
        assertEquals(1234L, normalized.attributes().getLastModification());
        assertEquals(5678L, normalized.attributes().getExpirationTime());
        assertEquals(404, attributes.getResponseCode());
        assertTrue(attributes.isNoTileAtZoom());

        BufferedImage image = ((BufferedImageCacheEntry) normalized.entry()).getImage();
        assertEquals(512, image.getWidth());
        assertEquals(512, image.getHeight());
        assertEquals(0, image.getRGB(256, 256) >>> 24);
    }

    @Test
    void preservesAuthenticationAndOtherFailures() {
        BufferedImageCacheEntry original = new BufferedImageCacheEntry(new byte[0]);

        ManagedHeatmapLayer.DisplayLoad forbidden = ManagedHeatmapLayer.normalizeForDisplay(
            original, attributes(403, false), LoadResult.FAILURE, 512);
        ManagedHeatmapLayer.DisplayLoad ordinary404 = ManagedHeatmapLayer.normalizeForDisplay(
            original, attributes(404, false), LoadResult.FAILURE, 512);
        ManagedHeatmapLayer.DisplayLoad failedSpatial404 = ManagedHeatmapLayer.normalizeForDisplay(
            original, attributes(404, true), LoadResult.FAILURE, 512);
        ManagedHeatmapLayer.DisplayLoad rateLimited = ManagedHeatmapLayer.normalizeForDisplay(
            original, attributes(429, false), LoadResult.FAILURE, 512);

        assertSame(original, forbidden.entry());
        assertEquals(403, forbidden.attributes().getResponseCode());
        assertEquals(LoadResult.FAILURE, forbidden.result());
        assertSame(original, ordinary404.entry());
        assertEquals(404, ordinary404.attributes().getResponseCode());
        assertEquals(LoadResult.FAILURE, ordinary404.result());
        assertSame(original, failedSpatial404.entry());
        assertEquals(404, failedSpatial404.attributes().getResponseCode());
        assertEquals(LoadResult.FAILURE, failedSpatial404.result());
        assertSame(original, rateLimited.entry());
        assertEquals(429, rateLimited.attributes().getResponseCode());
        assertEquals(LoadResult.FAILURE, rateLimited.result());
    }

    private CacheEntryAttributes attributes(int responseCode, boolean noTile) {
        CacheEntryAttributes attributes = new CacheEntryAttributes();
        attributes.setResponseCode(responseCode);
        attributes.setNoTileAtZoom(noTile);
        return attributes;
    }
}
