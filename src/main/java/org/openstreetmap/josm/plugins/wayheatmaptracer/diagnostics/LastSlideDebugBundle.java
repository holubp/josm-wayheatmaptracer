package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentResult;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.TileHeatmapSampler;

public final class LastSlideDebugBundle {
    private final String diagnosticsJson;
    private final String verboseLog;
    private final String originalOsm;
    private final String previewOsm;
    private final String candidateOsm;
    private final String statusJson;
    private final String tileManifestJson;
    private final Map<String, BufferedImage> tileImages;

    private LastSlideDebugBundle(
        String diagnosticsJson,
        String verboseLog,
        String originalOsm,
        String previewOsm,
        String candidateOsm,
        String statusJson,
        String tileManifestJson,
        Map<String, BufferedImage> tileImages
    ) {
        this.diagnosticsJson = diagnosticsJson;
        this.verboseLog = verboseLog;
        this.originalOsm = originalOsm;
        this.previewOsm = previewOsm;
        this.candidateOsm = candidateOsm;
        this.statusJson = statusJson;
        this.tileManifestJson = tileManifestJson;
        this.tileImages = tileImages;
    }

    public static LastSlideDebugBundle fromResult(AlignmentResult result, CenterlineCandidate selected, String status, String verboseLog) {
        Map<String, BufferedImage> images = new LinkedHashMap<>();
        String tileManifest = "{\"sampling\":\"rendered-visible-layer\",\"images\":[\"rendered-layer-capture.png\"],"
            + "\"details\":\"see diagnostics.json sampling and profiles\"}";
        if (result.tileMosaics() != null) {
            tileManifest = result.tileMosaics().manifestJson();
            for (TileHeatmapSampler.TileMosaic mosaic : result.tileMosaics().mosaics().values()) {
                images.put("tiles/" + safeName(mosaic.color()) + "-mosaic-z" + mosaic.zoom() + ".png", mosaic.image());
                for (Map.Entry<String, BufferedImage> tile : mosaic.tileImages().entrySet()) {
                    images.put("tiles/source/" + safeName(tile.getKey()), tile.getValue());
                }
            }
        } else if (result.capturedHeatmap() != null) {
            images.put("rendered-layer-capture.png", result.capturedHeatmap());
        }
        String statusJson = "{"
            + "\"status\":\"" + escape(status) + "\","
            + "\"selectedCandidate\":\"" + escape(selected == null ? "" : selected.id()) + "\""
            + "}";
        return new LastSlideDebugBundle(
            result.diagnostics().toJson(),
            verboseLog == null ? "" : verboseLog,
            originalOsm(result),
            previewOsm(result),
            candidateOsm(result),
            statusJson,
            tileManifest,
            images
        );
    }

    public File writeTo(File file) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            writeText(zip, "manifest.json", manifestJson());
            writeText(zip, "diagnostics.json", diagnosticsJson);
            writeText(zip, "status.json", statusJson);
            writeText(zip, "verbose-log.txt", verboseLog);
            writeText(zip, "original-segment.osm", originalOsm);
            writeText(zip, "preview-segment.osm", previewOsm);
            writeText(zip, "candidate-ridges.osm", candidateOsm);
            writeText(zip, "tile-manifest.json", tileManifestJson);
            for (Map.Entry<String, BufferedImage> entry : tileImages.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                ImageIO.write(entry.getValue(), "png", zip);
                zip.closeEntry();
            }
        }
        return file;
    }

    private String manifestJson() {
        return "{"
            + "\"type\":\"wayheatmaptracer-last-slide-debug-bundle\","
            + "\"formatVersion\":1,"
            + "\"containsSecrets\":false,"
            + "\"files\":[\"diagnostics.json\",\"status.json\",\"verbose-log.txt\",\"original-segment.osm\",\"preview-segment.osm\",\"candidate-ridges.osm\",\"tile-manifest.json\"]"
            + "}";
    }

    private static void writeText(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String originalOsm(AlignmentResult result) {
        StringBuilder builder = new StringBuilder(osmHeader());
        for (Node node : result.selection().segmentNodes()) {
            LatLon latLon = node.getCoor();
            if (latLon != null) {
                builder.append(nodeXml(node.getUniqueId(), latLon));
            }
        }
        builder.append("  <way id=\"").append(result.selection().way().getUniqueId()).append("\">\n");
        for (Node node : result.selection().segmentNodes()) {
            builder.append("    <nd ref=\"").append(node.getUniqueId()).append("\" />\n");
        }
        builder.append("  </way>\n</osm>\n");
        return builder.toString();
    }

    private static String previewOsm(AlignmentResult result) {
        StringBuilder builder = new StringBuilder(osmHeader());
        long id = -1;
        for (EastNorth point : result.previewPolyline()) {
            builder.append(nodeXml(id--, ProjectionRegistry.getProjection().eastNorth2latlon(point)));
        }
        builder.append("  <way id=\"-1000000\">\n");
        for (long ref = -1; ref >= -result.previewPolyline().size(); ref--) {
            builder.append("    <nd ref=\"").append(ref).append("\" />\n");
        }
        builder.append("  </way>\n</osm>\n");
        return builder.toString();
    }

    private static String candidateOsm(AlignmentResult result) {
        StringBuilder builder = new StringBuilder(osmHeader());
        long nodeId = -10_000_000;
        long wayId = -20_000_000;
        for (CenterlineCandidate candidate : result.candidates()) {
            if (candidate.eastNorthPoints().isEmpty()) {
                continue;
            }
            long firstNodeId = nodeId;
            for (EastNorth point : candidate.eastNorthPoints()) {
                builder.append(nodeXml(nodeId--, ProjectionRegistry.getProjection().eastNorth2latlon(point)));
            }
            builder.append("  <way id=\"").append(wayId--).append("\">\n");
            for (long ref = firstNodeId; ref > nodeId; ref--) {
                builder.append("    <nd ref=\"").append(ref).append("\" />\n");
            }
            builder.append("    <tag k=\"wayheatmaptracer:candidate\" v=\"").append(xmlEscape(candidate.id())).append("\" />\n");
            builder.append("    <tag k=\"wayheatmaptracer:score\" v=\"").append(candidate.score()).append("\" />\n");
            if (!candidate.safetyWarnings().isEmpty()) {
                builder.append("    <tag k=\"wayheatmaptracer:warnings\" v=\"")
                    .append(xmlEscape(String.join("; ", candidate.safetyWarnings())))
                    .append("\" />\n");
            }
            builder.append("  </way>\n");
        }
        builder.append("</osm>\n");
        return builder.toString();
    }

    private static String osmHeader() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<osm version=\"0.6\" generator=\"WayHeatmapTracer\">\n";
    }

    private static String nodeXml(long id, LatLon latLon) {
        return "  <node id=\"" + id + "\" lat=\"" + latLon.lat() + "\" lon=\"" + latLon.lon() + "\" />\n";
    }

    private static String safeName(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String xmlEscape(String value) {
        return value == null ? "" : value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }
}
