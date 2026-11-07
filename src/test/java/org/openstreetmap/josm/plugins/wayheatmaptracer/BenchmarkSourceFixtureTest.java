package org.openstreetmap.josm.plugins.wayheatmaptracer;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileAddress;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.config.PluginPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class BenchmarkSourceFixtureTest {
    @Test void selectedOnlySyntheticPngInventorySeedsTypedCacheAndRejectsCorruption(@TempDir Path directory) throws Exception {
        String prior = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", directory.toString());
        try {
            var image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++) image.setRGB(x, y, 0xffff0000);
            Path png = directory.resolve("synthetic.png"); ImageIO.write(image, "png", png.toFile());
            byte[] bytes = Files.readAllBytes(png);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            for (var profile : BenchmarkSourceFixture.values()) {
                var lines = new ArrayList<String>(); lines.add("zoom\tx\ty\tsha256\trelativePath");
                // Distinct paths remain byte-identical synthetic images; no private source bytes.
                for (var entry : profile.expectedTileCounts().entrySet()) for (int x = 0; x < entry.getValue(); x++) {
                    String name = profile.palette() + "-" + entry.getKey() + "-" + x + ".png";
                    Files.write(directory.resolve(name), bytes);
                    lines.add(entry.getKey() + "\t" + x + "\t0\t" + hash + "\t" + name);
                }
                Path tsv = directory.resolve("tiles.tsv"); Files.write(tsv, lines);
                var generation = new ManagedTileGeneration(53);
                BenchmarkHostMain.seedTiles(tsv, generation, profile);
                var cache = new ManagedTileCache(directory.resolve("wayheatmaptracer/managed-source-tile-cache"), new TileDecoderClassifier());
                assertArrayEquals(bytes, Files.readAllBytes(cache.path(generation,
                        new ManagedTileAddress("all", profile.palette(), 15, 0, 0))));
                var duplicate = new ArrayList<>(lines); duplicate.set(2, duplicate.get(1)); Files.write(tsv, duplicate);
                assertThrows(IllegalStateException.class, () -> BenchmarkHostMain.seedTiles(tsv, generation, profile));
                var escape = new ArrayList<>(lines); escape.set(1, escape.get(1).replaceAll("[^\\t]+$", "../escape.png"));
                Files.write(tsv, escape);
                assertThrows(IllegalStateException.class, () -> BenchmarkHostMain.seedTiles(tsv, generation, profile));
                Files.write(tsv, lines.subList(0, lines.size() - 1));
                assertThrows(IllegalStateException.class, () -> BenchmarkHostMain.seedTiles(tsv, generation, profile));
                var corrupt = new ArrayList<>(lines); corrupt.set(1, corrupt.get(1).replace(hash, "0".repeat(64)));
                Files.write(tsv, corrupt);
                assertThrows(IllegalStateException.class, () -> BenchmarkHostMain.seedTiles(tsv, generation, profile));
            }
        } finally {
            if (prior == null) System.clearProperty("java.io.tmpdir"); else System.setProperty("java.io.tmpdir", prior);
        }
    }

    @Test void profilesAreFiniteAndKeepGenerationPaletteRadiusAndPreciseMode() {
        Config.setPreferencesInstance(new MemoryPreferences());
        for (var profile : BenchmarkSourceFixture.values()) {
            assertEquals(profile, BenchmarkSourceFixture.parse(profile.identity()));
            var source = BenchmarkHostMain.fixtureSource(37, profile);
            PluginPreferences.save(source.config());
            source.saveCacheGenerationPreference();
            var loaded = PluginPreferences.load();
            assertEquals(profile.palette(), loaded.color());
            assertEquals(profile.halfWidthMeters(), loaded.searchHalfWidthMeters());
            assertEquals(AlignmentMode.PRECISE_SHAPE, loaded.alignmentMode());
            assertEquals(37, loaded.cacheBuster());
            assertEquals("managed-selected-" + profile.palette() + "-g37", source.sourceIdentity());
            assertEquals(profile == BenchmarkSourceFixture.HOT ? 10 : 6,
                    profile.expectedTileCounts().values().stream().mapToInt(Integer::intValue).sum());
        }
        assertThrows(IllegalArgumentException.class, () -> BenchmarkSourceFixture.parse("hot"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkHostMain.fixtureSource(-1, BenchmarkSourceFixture.BLUERED));
    }
}
