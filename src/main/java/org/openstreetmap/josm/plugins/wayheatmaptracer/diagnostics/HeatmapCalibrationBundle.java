package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler;

/**
 * Writes redacted managed heatmap tile mosaics for offline palette and filter calibration.
 */
public final class HeatmapCalibrationBundle {
    private final TileHeatmapSampler.TileMosaicSet mosaics;
    private final String sourceSummaryJson;

    /**
     * Creates a calibration bundle writer.
     *
     * @param mosaics source-tile mosaics to include
     * @param sourceSummaryJson redacted selected-way and settings metadata
     */
    public HeatmapCalibrationBundle(TileHeatmapSampler.TileMosaicSet mosaics, String sourceSummaryJson) {
        this.mosaics = mosaics;
        this.sourceSummaryJson = sourceSummaryJson;
    }

    /**
     * Writes the bundle zip file.
     *
     * @param file destination zip file
     * @return the written file
     * @throws Exception when the zip or image encoding fails
     */
    public File writeTo(File file) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            writeText(zip, "manifest.json", manifestJson());
            writeText(zip, "source-selection.json", sourceSummaryJson);
            writeText(zip, "tile-manifest.json", mosaics.manifestJson());
            writeText(zip, "README.txt",
                "This bundle contains redacted Strava heatmap tile images for palette calibration.\n"
                    + "It contains no cookies, signed headers, or signed URLs.\n\n"
                    + "Analyze it with:\n"
                    + "  python3 scripts/heatmap-palette-lab.py " + file.getName() + " --output-dir palette-lab --copy-images\n");
            for (TileHeatmapSampler.TileMosaic mosaic : mosaics.mosaics().values()) {
                writeImage(zip, "mosaics/" + safeName(mosaic.color()) + "-z" + mosaic.zoom() + ".png", mosaic.image());
                for (Map.Entry<String, BufferedImage> tile : mosaic.tileImages().entrySet()) {
                    writeImage(zip, "tiles/source/" + safeName(tile.getKey()), tile.getValue());
                }
            }
        }
        return file;
    }

    private String manifestJson() {
        return "{"
            + "\"type\":\"wayheatmaptracer-heatmap-calibration-bundle\","
            + "\"formatVersion\":1,"
            + "\"containsSecrets\":false,"
            + "\"files\":[\"source-selection.json\",\"tile-manifest.json\",\"mosaics/*.png\",\"tiles/source/*.png\"]"
            + "}";
    }

    private static void writeText(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void writeImage(ZipOutputStream zip, String name, BufferedImage image) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        ImageIO.write(image, "png", zip);
        zip.closeEntry();
    }

    private static String safeName(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
