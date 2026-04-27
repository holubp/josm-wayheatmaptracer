package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class HeatmapFixtureArchiveTest {
    private static final String ARCHIVE_NAME = "extracted-tiles.zip";
    private static final String SPARSE_TILE =
        "extracted-tiles/Strava_All_15_https_content-a.strava.com_identified_globalheat_all_hot__zoom___x___y_.png_14_8872_5715.png";
    private static final String DENSE_TILE =
        "extracted-tiles/Strava_All_15_https_content-a.strava.com_identified_globalheat_all_hot__zoom___x___y_.png_15_17895_11215.png";

    @Test
    void realHeatmapArchiveDecodesAndContainsExpectedStructure() throws Exception {
        Path archive = Path.of(ARCHIVE_NAME).toAbsolutePath().normalize();
        assertTrue(Files.exists(archive), "Expected real heatmap fixture archive at " + archive);

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<? extends ZipEntry> pngEntries = zip.stream()
                .filter(entry -> !entry.isDirectory())
                .filter(entry -> entry.getName().endsWith(".png"))
                .sorted(Comparator.comparing(ZipEntry::getName))
                .toList();

            assertTrue(pngEntries.size() >= 20, "Expected at least 20 PNG tiles in the fixture archive");

            for (ZipEntry entry : pngEntries) {
                BufferedImage image = readImage(zip, entry.getName());
                assertEquals(512, image.getWidth(), "Unexpected tile width for " + entry.getName());
                assertEquals(512, image.getHeight(), "Unexpected tile height for " + entry.getName());
            }

            BufferedImage sparse = readImage(zip, SPARSE_TILE);
            BufferedImage dense = readImage(zip, DENSE_TILE);

            long sparseNonTransparent = countNonTransparent(sparse);
            long denseNonTransparent = countNonTransparent(dense);

            assertTrue(sparseNonTransparent >= 3_000 && sparseNonTransparent <= 10_000,
                "Sparse tile should stay sparse enough to exercise narrow-ridge cases");
            assertTrue(denseNonTransparent >= 100_000,
                "Dense tile should retain a broad high-activity footprint");

            assertTrue(countHotRuns(sparse, 289, 0.40) >= 1,
                "Sparse tile should keep at least one bright run on the chosen scanline");
            assertTrue(countHotRuns(dense, 288, 0.40) >= 4,
                "Dense tile should keep multiple bright runs on the chosen scanline");
        }
    }

    @Test
    void intensityPrefersBrightCenterOverSaturatedShoulder() {
        double white = intensity(0xFFFFFFFF);
        double yellow = intensity(0xFFFFFF00);
        double orange = intensity(0xFFFF8000);

        assertTrue(white > yellow, "White center should rank above yellow shoulder");
        assertTrue(yellow > orange, "Yellow shoulder should rank above orange fringe");
    }

    @Test
    void paletteSpecificIntensityOrderingMatchesExpectedCenters() {
        double hotWhite = RenderedHeatmapSampler.colorIntensity(255, 255, 255, "hot");
        double hotYellow = RenderedHeatmapSampler.colorIntensity(255, 255, 0, "hot");
        double hotOrange = RenderedHeatmapSampler.colorIntensity(255, 128, 0, "hot");
        double hotRed = RenderedHeatmapSampler.colorIntensity(180, 0, 0, "hot");
        assertTrue(hotWhite > hotYellow, "hot should rank white above yellow");
        assertTrue(hotYellow > hotOrange, "hot should rank yellow above orange");
        assertTrue(hotOrange > hotRed, "hot should rank orange above dark red");

        double blueredBlue = RenderedHeatmapSampler.colorIntensity(50, 110, 255, "bluered");
        double blueredCyan = RenderedHeatmapSampler.colorIntensity(70, 220, 255, "bluered");
        double blueredPurple = RenderedHeatmapSampler.colorIntensity(190, 70, 230, "bluered");
        double blueredRed = RenderedHeatmapSampler.colorIntensity(255, 70, 110, "bluered");
        assertTrue(blueredRed > blueredBlue, "bluered should prioritize the warm center over strong blue shoulders");
        assertTrue(blueredRed > blueredCyan, "bluered should treat cyan as lower activity than red/magenta");
        assertTrue(blueredPurple > blueredBlue, "bluered should keep magenta/purple transition above blue shoulders");

        double blueShoulder = RenderedHeatmapSampler.colorIntensity(40, 95, 220, "blue");
        double blueMedium = RenderedHeatmapSampler.colorIntensity(80, 170, 245, "blue");
        double blueCenter = RenderedHeatmapSampler.colorIntensity(170, 225, 255, "blue");
        assertTrue(blueCenter > blueShoulder, "blue should prioritize the bright cyan/light-blue center over dark blue shoulders");
        assertTrue(blueCenter > blueMedium, "blue should rank the bright core above medium blue");
        assertTrue(blueMedium > blueShoulder, "blue should still rank coherent medium blue above dark shoulders");

        double grayLightPink = RenderedHeatmapSampler.colorIntensity(235, 190, 205, "gray");
        double grayViolet = RenderedHeatmapSampler.colorIntensity(120, 70, 180, "gray");
        double grayNeutral = RenderedHeatmapSampler.colorIntensity(180, 180, 180, "gray");
        assertTrue(grayViolet > grayLightPink, "gray should prioritize saturated violet center over pale pink");
        assertTrue(grayLightPink > grayNeutral, "gray should not treat neutral gray brightness as strong heatmap evidence");

        double purpleDark = RenderedHeatmapSampler.colorIntensity(85, 40, 110, "purple");
        double purpleBright = RenderedHeatmapSampler.colorIntensity(205, 120, 245, "purple");
        assertTrue(purpleBright > purpleDark, "purple should prioritize brighter pixels");

        double dualWarm = RenderedHeatmapSampler.colorIntensity(255, 70, 110, "dual");
        double dualBright = RenderedHeatmapSampler.colorIntensity(245, 245, 245, "dual");
        assertTrue(dualWarm > 0.4, "dual classifier should preserve warm dual-color center evidence");
        assertTrue(dualBright > 0.6, "dual classifier should preserve bright high-frequency center evidence");
        assertTrue(dualWarm > RenderedHeatmapSampler.colorIntensity(50, 110, 255, "dual"),
            "dual classifier should still favor warm high-activity evidence over blue shoulders");
    }

    @Test
    void noSignalProfilesExposeNoPeaksForGapBridging() {
        BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);

        RenderedHeatmapSampler sampler = new RenderedHeatmapSampler();
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = sampler.sampleProfilesOnRaster(
            image,
            List.of(new Point2D.Double(10, 20), new Point2D.Double(70, 20)),
            12,
            2,
            "hot",
            1.0
        );

        assertTrue(profiles.stream().allMatch(profile -> profile.peaks().isEmpty()),
            "No-signal cross-sections should stay empty so the tracker can bridge gaps from coherent later seeds");
    }

    @Test
    void pairedShouldersExposeCenterPeak() {
        BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < image.getWidth(); x++) {
            image.setRGB(x, 14, 0xFFFFFFFF);
            image.setRGB(x, 26, 0xFFFFFFFF);
        }

        RenderedHeatmapSampler sampler = new RenderedHeatmapSampler();
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = sampler.sampleProfilesOnRaster(
            image,
            List.of(new Point2D.Double(10, 20), new Point2D.Double(70, 20)),
            12,
            2,
            "hot",
            1.0
        );

        assertTrue(profiles.stream().allMatch(profile -> profile.peaks().stream()
            .anyMatch(peak -> Math.abs(peak.offsetPx()) <= 1.0)),
            "Balanced heatmap shoulders should produce a center candidate for road middle detection");
    }

    private static BufferedImage readImage(ZipFile zip, String entryName) throws Exception {
        ZipEntry entry = zip.getEntry(entryName);
        assertNotNull(entry, "Missing fixture entry " + entryName);
        try (InputStream input = zip.getInputStream(entry)) {
            BufferedImage image = ImageIO.read(input);
            assertNotNull(image, "Failed to decode image " + entryName);
            return image;
        }
    }

    private static long countNonTransparent(BufferedImage image) {
        long count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (((image.getRGB(x, y) >>> 24) & 0xFF) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int countHotRuns(BufferedImage image, int row, double threshold) {
        List<Double> intensities = new ArrayList<>(image.getWidth());
        for (int x = 0; x < image.getWidth(); x++) {
            intensities.add(intensity(image.getRGB(x, row)));
        }

        int runs = 0;
        boolean inRun = false;
        for (double value : intensities) {
            if (value > threshold) {
                if (!inRun) {
                    runs++;
                    inRun = true;
                }
            } else {
                inRun = false;
            }
        }
        return runs;
    }

    private static double intensity(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        if (alpha == 0) {
            return 0.0;
        }
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;

        double luminance = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0;
        double value = Math.max(red, Math.max(green, blue)) / 255.0;
        return 0.85 * luminance + 0.15 * value;
    }
}
