package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.*;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.spi.preferences.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;

class LegacyManagedAcquisitionTest {
    @TempDir Path temp;
    @BeforeAll static void setup() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }
    @Test void receiptOwnsTheActualOriginalMosaicsWithoutAnotherTileAcquisition() throws Exception {
        var bytes = png(); var reads = new AtomicInteger(); var decoder = new TileDecoderClassifier();
        try (var coordinator = new TileFetchCoordinator((request, credentials) -> {
            reads.incrementAndGet();
            return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK, 200, "image/png", bytes, null, Duration.ZERO, "");
        }, new ManagedTileCache(temp, decoder), decoder, TileReliabilityPolicy.defaults())) {
            var sampler = new TileHeatmapSampler(coordinator);
            var actual = sampler.prepareWithReceipt(config(), source(), List.of("hot"), false);
            assertSame(actual.mosaics(), actual.receipt().mosaics());
            int tiles = actual.mosaics().mosaics().values().stream().mapToInt(m -> m.tiles().size()).sum();
            assertTrue(tiles > 0); assertEquals(tiles, reads.get());
            assertEquals(7, actual.receipt().generation().value());
            coordinator.updateActiveGeneration(new ManagedTileGeneration(7));
            actual.receipt().requireCurrent(coordinator, actual.mosaics());
            assertEquals(tiles, reads.get(), "Receipt validation cannot acquire or decode anything");
            var foreign = new TileHeatmapSampler.TileMosaicSet(actual.mosaics().inferenceMode(),
                15, 15, 15, 15, 512, actual.mosaics().mosaics(), actual.mosaics().inferenceParameters(), actual.mosaics().validationParameters());
            assertThrows(IllegalStateException.class, () -> actual.receipt().requireCurrent(coordinator, foreign));
        }
    }
    @Test void generationChangeAndRestorationCannotRenewAnOriginalReceipt() throws Exception {
        var bytes = png(); var decoder = new TileDecoderClassifier();
        try (var coordinator = new TileFetchCoordinator((r,c) -> new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,
                200, "image/png", bytes, null, Duration.ZERO, ""), new ManagedTileCache(temp, decoder), decoder, TileReliabilityPolicy.defaults())) {
            var actual = new TileHeatmapSampler(coordinator).prepareWithReceipt(config(), source(), List.of("hot"), false);
            coordinator.updateActiveGeneration(new ManagedTileGeneration(8));
            coordinator.updateActiveGeneration(new ManagedTileGeneration(7));
            assertThrows(IllegalStateException.class, () -> actual.receipt().requireCurrent(coordinator, actual.mosaics()));
        }
    }
    private static List<EastNorth> source() {
        var p = ProjectionRegistry.getProjection();
        return List.of(p.latlon2eastNorth(new LatLon(50, 14)), p.latlon2eastNorth(new LatLon(50, 14.0001)));
    }
    private static ManagedHeatmapConfig config() {
        return new ManagedHeatmapConfig("test-key", "test-policy", "test-signature", "test-session", "all", "hot", "", ".*",
            AlignmentMode.PRECISE_SHAPE, TrackerMode.LEGACY_V02, false,false,false,false,false,false,false,false,false,false,
            7,4,3,InferenceMode.RAW_HIGH_RESOLUTION,15,15,7.01,1.56,IntensitySamplingMode.COLOR_MAPPING,7);
    }
    private static byte[] png() throws Exception {
        var image = new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);
        for (int y=0;y<512;y++) for(int x=0;x<512;x++) image.setRGB(x,y,0xff000000|((x+y)%200+20)<<16);
        var output = new ByteArrayOutputStream(); ImageIO.write(image,"png",output); return output.toByteArray();
    }
}
