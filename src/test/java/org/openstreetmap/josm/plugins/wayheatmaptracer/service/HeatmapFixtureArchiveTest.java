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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;

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
        assertTrue(blueredBlue > 0.05, "v0.2 bluered keeps blue as weaker low-activity evidence");

        double blueShoulder = RenderedHeatmapSampler.colorIntensity(40, 95, 220, "blue");
        double blueMedium = RenderedHeatmapSampler.colorIntensity(80, 170, 245, "blue");
        double blueCenter = RenderedHeatmapSampler.colorIntensity(170, 225, 255, "blue");
        assertTrue(blueCenter > blueShoulder, "blue should prioritize the bright cyan/light-blue center over dark blue shoulders");
        assertTrue(blueCenter > blueMedium, "blue should rank the bright core above medium blue");
        assertTrue(blueMedium > blueShoulder, "blue should still rank coherent medium blue above dark shoulders");

        double grayLightPink = RenderedHeatmapSampler.colorIntensity(235, 190, 205, "gray");
        double grayViolet = RenderedHeatmapSampler.colorIntensity(120, 70, 180, "gray");
        double grayWarmPink = RenderedHeatmapSampler.colorIntensity(215, 65, 160, "gray");
        double grayNeutral = RenderedHeatmapSampler.colorIntensity(180, 180, 180, "gray");
        assertTrue(grayViolet > grayLightPink, "gray should prioritize saturated violet center over pale pink");
        assertTrue(grayLightPink > grayNeutral, "gray should not treat neutral gray brightness as strong heatmap evidence");
        assertTrue(grayWarmPink > grayNeutral, "gray should treat pink/magenta high-activity pixels as center evidence");

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
    void experimentalDetectorVariantsExposeCalibrationAlternatives() {
        double blueredBlue = RenderedHeatmapSampler.colorIntensity(50, 110, 255, "bluered");
        double blueredCoolBlue = RenderedHeatmapSampler.colorIntensity(50, 110, 255, "bluered-cool");
        double blueredCoolCyan = RenderedHeatmapSampler.colorIntensity(70, 220, 255, "bluered-cool");
        double blueredCorridorRed = RenderedHeatmapSampler.colorIntensity(255, 70, 110, "bluered-corridor");
        double blueredCoolRed = RenderedHeatmapSampler.colorIntensity(255, 70, 110, "bluered-cool");
        assertTrue(blueredCoolBlue > blueredBlue, "cool bluered variant should expose weak blue/cyan traces for rating");
        assertTrue(blueredCoolCyan > 0.20, "cool bluered variant should keep cyan as usable low-activity evidence");
        assertTrue(blueredCorridorRed <= 1.0, "corridor variant should stay normalized");
        assertTrue(blueredCorridorRed / blueredCoolRed < RenderedHeatmapSampler.colorIntensity(50, 110, 255, "bluered-corridor") / blueredCoolBlue,
            "corridor variant should compress dynamic range by lifting shoulders more than saturated peaks");

        double dualWarm = RenderedHeatmapSampler.colorIntensity(255, 70, 110, "dual");
        double dualCorridorWarm = RenderedHeatmapSampler.colorIntensity(255, 70, 110, "dual-corridor");
        double dualBlue = RenderedHeatmapSampler.colorIntensity(50, 110, 255, "dual");
        double dualCorridorBlue = RenderedHeatmapSampler.colorIntensity(50, 110, 255, "dual-corridor");
        assertTrue(dualCorridorWarm <= 1.0, "corridor variant remains normalized");
        assertTrue(dualCorridorBlue > dualBlue, "corridor variant should make lower-intensity shoulders visible as bands");
        assertTrue(dualCorridorWarm >= dualWarm * 0.85, "corridor variant should not discard strong warm evidence");

        double grayNeutral = RenderedHeatmapSampler.colorIntensity(180, 180, 180, "gray");
        double grayMagenta = RenderedHeatmapSampler.colorIntensity(215, 65, 160, "gray-magenta");
        double grayMagentaNeutral = RenderedHeatmapSampler.colorIntensity(180, 180, 180, "gray-magenta");
        double grayCorridorNeutral = RenderedHeatmapSampler.colorIntensity(180, 180, 180, "gray-corridor");
        double grayCorridorMagenta = RenderedHeatmapSampler.colorIntensity(215, 65, 160, "gray-corridor");
        double strictGrayNeutral = RenderedHeatmapSampler.colorIntensity(180, 180, 180, "gray-strict");
        double strictGrayViolet = RenderedHeatmapSampler.colorIntensity(120, 70, 180, "gray-strict");
        assertTrue(grayMagenta > grayMagentaNeutral, "magenta gray variant should ignore neutral grayscale evidence");
        assertTrue(grayCorridorNeutral > grayNeutral, "corridor gray variant should lift weak neutral corridors");
        assertTrue(grayCorridorMagenta <= 1.0, "corridor gray variant remains normalized");
        assertTrue(strictGrayNeutral < grayNeutral, "strict gray should suppress neutral no-op evidence");
        assertTrue(strictGrayViolet > strictGrayNeutral, "strict gray should still retain violet center evidence");

        double purpleBright = RenderedHeatmapSampler.colorIntensity(205, 120, 245, "purple");
        double strictPurpleBright = RenderedHeatmapSampler.colorIntensity(205, 120, 245, "purple-strict");
        double strictPurpleNeutral = RenderedHeatmapSampler.colorIntensity(180, 180, 180, "purple-strict");
        assertTrue(strictPurpleBright > strictPurpleNeutral, "strict purple should require actual purple evidence");
        assertTrue(strictPurpleBright <= purpleBright, "strict purple should only gate the baseline purple detector");
    }

    @Test
    void combinedDetectorIntensityIsWeightedBeforeRidgeExtraction() {
        int red = 255;
        int green = 70;
        int blue = 110;

        double combined = RenderedHeatmapSampler.colorIntensity(red, green, blue, "bluered-combined");
        double expected = weightedComponentIntensity(red, green, blue, "bluered-combined");

        assertTrue(RenderedHeatmapSampler.isCombinedColorMode("bluered-combined"));
        assertEquals(expected, combined, 1e-12,
            "Combined detectors must fuse color-to-intensity mappings before profile peak extraction");
        assertTrue(RenderedHeatmapSampler.intensityComponents("bluered-combined").size() > 1,
            "Combined detector diagnostics should expose component mappings");
    }

    @Test
    void combinedDetectorProfilesAreSampledFromFusedIntensityField() {
        BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < image.getWidth(); x++) {
            image.setRGB(x, 20, 0xFFFF466E);
            image.setRGB(x, 26, 0xFF326EFF);
        }

        RenderedHeatmapSampler sampler = new RenderedHeatmapSampler();
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = sampler.sampleProfilesOnRaster(
            image,
            List.of(new Point2D.Double(10, 20), new Point2D.Double(70, 20)),
            12,
            2,
            "bluered-combined",
            1.0
        );

        assertTrue(profiles.stream().allMatch(profile -> profile.peaks().stream()
            .anyMatch(peak -> Math.abs(peak.offsetPx()) <= 1.0 && peak.intensity() > 0.35)),
            "The fused intensity field should preserve the warm center before ridge tracking");
    }

    @Test
    void allColorAggregateProfilesFuseSourceColorIntensitiesBeforeRidgeExtraction() {
        BufferedImage hot = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        BufferedImage bluered = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        BufferedImage purple = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        BufferedImage gray = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        BufferedImage blue = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < hot.getWidth(); x++) {
            hot.setRGB(x, 20, 0xFFFFFFFF);
            bluered.setRGB(x, 20, 0xFFFF466E);
            purple.setRGB(x, 20, 0xFFCD78F5);
            gray.setRGB(x, 20, 0xFFD741A0);
            blue.setRGB(x, 26, 0xFF326EFF);
        }
        Map<String, BufferedImage> sources = new LinkedHashMap<>();
        sources.put("hot", hot);
        sources.put("blue", blue);
        sources.put("bluered", bluered);
        sources.put("purple", purple);
        sources.put("gray", gray);

        RenderedHeatmapSampler sampler = new RenderedHeatmapSampler();
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = sampler.sampleProfilesOnAggregatedScaledRasters(
            sources,
            List.of(new Point2D.Double(10, 20), new Point2D.Double(70, 20)),
            12,
            2,
            1.0,
            1.0
        );

        assertTrue(profiles.stream().allMatch(profile -> profile.peaks().stream()
            .anyMatch(peak -> Math.abs(peak.offsetPx()) <= 1.0 && peak.intensity() > 0.35)),
            "Aggregating all source colors should keep the common center before ridge tracking");
    }

    @Test
    void profilePeaksExposeMultiScaleCenterAgreement() {
        BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < image.getWidth(); x++) {
            image.setRGB(x, 18, 0xFFCC3300);
            image.setRGB(x, 19, 0xFFFFFF00);
            image.setRGB(x, 20, 0xFFFFFFFF);
            image.setRGB(x, 21, 0xFFFFFF00);
            image.setRGB(x, 22, 0xFFCC3300);
        }

        RenderedHeatmapSampler sampler = new RenderedHeatmapSampler();
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = sampler.sampleProfilesOnRaster(
            image,
            List.of(new Point2D.Double(10, 20), new Point2D.Double(70, 20)),
            12,
            1,
            "hot",
            1.0
        );

        RenderedHeatmapSampler.CrossSectionPeak peak = strongestPeak(profiles.get(0));
        assertTrue(Math.abs(peak.rawCenterPx()) <= 1.0, "Raw center should stay on the white core");
        assertTrue(Math.abs(peak.lightFilteredCenterPx()) <= 1.0, "B3 center should stay on the same core");
        assertTrue(Math.abs(peak.standardFilteredCenterPx()) <= 1.0, "B5 center should stay on the same core");
        assertTrue(peak.scaleAgreement() >= 0.85, "Stable core should have high cross-scale agreement");
    }

    @Test
    void highIntensityBiasedFilteringDoesNotLetShouldersOutrankCore() {
        BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 14; y <= 26; y++) {
                image.setRGB(x, y, 0xFFFF6600);
            }
            image.setRGB(x, 19, 0xFFFFFF00);
            image.setRGB(x, 20, 0xFFFFFFFF);
            image.setRGB(x, 21, 0xFFFFFF00);
        }

        RenderedHeatmapSampler sampler = new RenderedHeatmapSampler();
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = sampler.sampleProfilesOnRaster(
            image,
            List.of(new Point2D.Double(10, 20), new Point2D.Double(70, 20)),
            14,
            1,
            "hot",
            1.0
        );

        RenderedHeatmapSampler.CrossSectionPeak peak = strongestPeak(profiles.get(0));
        assertTrue(Math.abs(peak.offsetPx()) <= 2.0,
            "Broad lower-intensity shoulders must not pull the selected peak away from the high-intensity core");
        assertTrue(peak.centerUncertaintyPx() <= 1.5,
            "A clear hot core should remain a low-uncertainty center after profile filtering");
    }

    @Test
    void directAlphaSamplingBypassesColorMappingForScalarImagery() {
        BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < image.getWidth(); x++) {
            image.setRGB(x, 20, 0xFF000000);
        }

        RenderedHeatmapSampler sampler = new RenderedHeatmapSampler();
        List<RenderedHeatmapSampler.CrossSectionProfile> colorProfiles = sampler.sampleProfilesOnRaster(
            image,
            List.of(new Point2D.Double(10, 20), new Point2D.Double(70, 20)),
            12,
            2,
            "hot",
            1.0
        );
        List<RenderedHeatmapSampler.CrossSectionProfile> directProfiles = sampler.sampleProfilesOnRaster(
            image,
            List.of(new Point2D.Double(10, 20), new Point2D.Double(70, 20)),
            12,
            2,
            "direct-alpha",
            1.0,
            IntensitySamplingMode.DIRECT_ALPHA
        );

        assertTrue(colorProfiles.stream().allMatch(profile -> profile.peaks().stream()
                .allMatch(peak -> peak.intensity() == 0.0)),
            "Black scalar imagery should not be interpreted as heat by palette color mapping");
        assertTrue(directProfiles.stream().allMatch(profile -> profile.peaks().stream()
                .anyMatch(peak -> Math.abs(peak.offsetPx()) <= 1.0 && peak.intensity() > 0.8)),
            "Direct alpha mode should use pixel opacity as scalar intensity without palette semantics");
    }

    @Test
    void noSignalProfilesExposeZeroOffsetFallbackPeaks() {
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

        assertTrue(profiles.stream().allMatch(profile -> profile.peaks().size() == 1
                && profile.peaks().get(0).offsetPx() == 0.0
                && profile.peaks().get(0).intensity() == 0.0),
            "The v0.2-compatible sampler exposes a zero-offset fallback peak for no-signal cross-sections");
    }

    @Test
    void profilesOutsideCapturedRasterAreMarkedForViewportSafety() {
        BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < image.getWidth(); x++) {
            image.setRGB(x, 20, 0xFFFFFFFF);
        }

        RenderedHeatmapSampler sampler = new RenderedHeatmapSampler();
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = sampler.sampleProfilesOnRaster(
            image,
            List.of(new Point2D.Double(10, -20), new Point2D.Double(10, 20)),
            12,
            2,
            "hot",
            1.0
        );

        assertTrue(profiles.stream().anyMatch(profile -> !profile.anchorWithinRaster()),
            "Visible-layer alignment must be able to detect selected segments extending outside the captured viewport");
        assertTrue(profiles.stream().anyMatch(RenderedHeatmapSampler.CrossSectionProfile::anchorWithinRaster),
            "Profiles inside the capture should remain marked as sampled");
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

    @Test
    void broadCorridorExposesCenterPeak() {
        BufferedImage image = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 12; y <= 28; y++) {
                image.setRGB(x, y, 0xFFFFFFFF);
            }
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
            .anyMatch(peak -> !peak.syntheticCenter() && Math.abs(peak.offsetPx()) <= 1.0)),
            "The v0.2-compatible sampler exposes the broad conduit center as a normal band peak");
        assertTrue(profiles.stream().allMatch(profile -> profile.peaks().stream()
            .anyMatch(peak -> peak.gradientStrength() > 0.0)),
            "Cross-section peaks should carry gradient evidence for diagnostics and ridge confirmation");
    }

    @Test
    void saturatedBlueredCorridorUsesHighIntensityCoreCenter() {
        BufferedImage image = new BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 8; y <= 70; y++) {
                image.setRGB(x, y, 0xFF326EFF);
            }
            for (int y = 28; y <= 32; y++) {
                image.setRGB(x, y, 0xFFFF466E);
            }
        }

        RenderedHeatmapSampler sampler = new RenderedHeatmapSampler();
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles = sampler.sampleProfilesOnRaster(
            image,
            List.of(new Point2D.Double(10, 30), new Point2D.Double(70, 30)),
            34,
            2,
            "bluered",
            1.0
        );

        assertTrue(profiles.stream().allMatch(profile -> profile.peaks().stream()
            .anyMatch(peak -> Math.abs(peak.offsetPx()) <= 3.0 && peak.intensity() > 0.65)),
            "A broad cooler shoulder must not pull the detected bluered ridge away from the saturated warm center");
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

    private static double weightedComponentIntensity(int red, int green, int blue, String colorMode) {
        double total = 0.0;
        double weight = 0.0;
        for (RenderedHeatmapSampler.IntensityComponent component : RenderedHeatmapSampler.intensityComponents(colorMode)) {
            total += component.weight() * RenderedHeatmapSampler.colorIntensity(red, green, blue, component.mode());
            weight += component.weight();
        }
        return total / weight;
    }

    private static RenderedHeatmapSampler.CrossSectionPeak strongestPeak(RenderedHeatmapSampler.CrossSectionProfile profile) {
        return profile.peaks().stream()
            .max(Comparator.comparingDouble(RenderedHeatmapSampler.CrossSectionPeak::intensity))
            .orElseThrow();
    }
}
