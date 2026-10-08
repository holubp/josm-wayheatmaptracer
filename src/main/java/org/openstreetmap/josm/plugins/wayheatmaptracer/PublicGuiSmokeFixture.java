package org.openstreetmap.josm.plugins.wayheatmaptracer;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.io.OsmReader;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileAddress;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileCache;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.ManagedTileGeneration;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.TileDecoderClassifier;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginDirectories;

/** Builds only public analytic OSM and Hot PNG inputs for the GUI smoke. */
final class PublicGuiSmokeFixture {
    private static final int TILE_SIZE = 512;
    private static final int[] ZOOMS = {14, 15};
    private final DataSet dataSet;
    private final Way selectedWay;
    private final Map<Integer, Tile> tiles;

    private PublicGuiSmokeFixture(DataSet dataSet, Way selectedWay, Map<Integer, Tile> tiles) {
        this.dataSet = dataSet;
        this.selectedWay = selectedWay;
        this.tiles = Map.copyOf(tiles);
    }

    static PublicGuiSmokeFixture create(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path osm = directory.resolve("public-analytic-way.osm");
        Files.writeString(osm, osmXml(), StandardCharsets.UTF_8);
        DataSet dataSet;
        try (InputStream stream = Files.newInputStream(osm)) {
            dataSet = OsmReader.parseDataSet(stream, NullProgressMonitor.INSTANCE);
        }
        Way selected = (Way) dataSet.getPrimitiveById(110L, OsmPrimitiveType.WAY);
        if (selected == null || selected.getNodesCount() != 11) {
            throw new IllegalStateException("Generated public OSM selection is incomplete");
        }
        Map<Integer, Tile> tiles = new LinkedHashMap<>();
        for (int zoom : ZOOMS) {
            double centerX = worldPixelX(zoom, PublicGuiSmokeOracle.BASE_LONGITUDE);
            double centerY = worldPixelY(zoom, PublicGuiSmokeOracle.BASE_LATITUDE);
            int x = (int) Math.floor(centerX / TILE_SIZE);
            int y = (int) Math.floor(centerY / TILE_SIZE);
            BufferedImage image = new BufferedImage(TILE_SIZE, TILE_SIZE,
                    BufferedImage.TYPE_INT_ARGB);
            for (int row = 0; row < TILE_SIZE; row++) {
                for (int column = 0; column < TILE_SIZE; column++) {
                    double pixelPitchMeters = 2.0 * Math.PI
                            * PublicGuiSmokeOracle.EARTH_RADIUS_METERS /
                            (TILE_SIZE * (1 << zoom));
                    double east = (x * TILE_SIZE + column + 0.5 - centerX)
                            * pixelPitchMeters;
                    double bend = 4.0 * Math.max(0.0, Math.cos(Math.PI * east / 64.0));
                    double ridgeY = worldPixelY(zoom,
                            PublicGuiSmokeOracle.point(0, bend).latitudeDegrees());
                    double distance = y * TILE_SIZE + row + 0.5 - ridgeY;
                    double strength = Math.exp(-0.5 * distance * distance / 1.44);
                    int background = 3 + (column * 7 + row * 3) % 29;
                    int gray = (int) Math.round(background + strength * (253 - background));
                    image.setRGB(column, row, 0xff000000 | gray << 16 | gray << 8 | gray);
                }
            }
            Path png = directory.resolve("public-hot-z" + zoom + ".png");
            if (!ImageIO.write(image, "png", png.toFile())) {
                throw new IllegalStateException("PNG writer is unavailable");
            }
            tiles.put(zoom, new Tile(new ManagedTileAddress("all", "hot", zoom, x, y), png));
        }
        return new PublicGuiSmokeFixture(dataSet, selected, tiles);
    }

    DataSet dataSet() { return dataSet; }
    Way selectedWay() { return selectedWay; }
    int tileCount() { return tiles.size(); }

    boolean tilesUsable() throws Exception {
        TileDecoderClassifier classifier = new TileDecoderClassifier();
        for (Tile tile : tiles.values()) {
            if (!classifier.decodeAndClassify(tile.address(), "image/png",
                    Files.readAllBytes(tile.path())).usable()) return false;
        }
        return true;
    }

    void seedTiles() throws Exception {
        if (!tilesUsable()) throw new IllegalStateException("Public Hot tile is unusable");
        ManagedTileCache cache = new ManagedTileCache(
                PluginDirectories.ensurePluginDataDirectory().toPath()
                        .resolve("managed-source-tile-cache"), new TileDecoderClassifier());
        for (Tile tile : tiles.values()) {
            Path destination = cache.path(new ManagedTileGeneration(0L), tile.address());
            Files.createDirectories(destination.getParent());
            Files.copy(tile.path(), destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    double measuredRidgeOffsetMeters() throws Exception {
        Tile tile = tiles.get(15);
        BufferedImage image = ImageIO.read(tile.path().toFile());
        int column = (int) Math.floor(worldPixelX(15, PublicGuiSmokeOracle.BASE_LONGITUDE))
                - tile.address().x() * TILE_SIZE;
        int peak = -1;
        int peakRow = -1;
        for (int row = 0; row < TILE_SIZE; row++) {
            int gray = image.getRGB(column, row) & 0xff;
            if (gray > peak) {
                peak = gray;
                peakRow = row;
            }
        }
        if (peak < 128) throw new IllegalStateException("Public tile has no bright ridge core");
        double pixelY = tile.address().y() * TILE_SIZE + peakRow + 0.5;
        double latitude = Math.toDegrees(Math.atan(Math.sinh(Math.PI
                * (1.0 - 2.0 * pixelY / (TILE_SIZE * (1 << 15))))));
        return Math.toRadians(latitude - PublicGuiSmokeOracle.BASE_LATITUDE)
                * PublicGuiSmokeOracle.EARTH_RADIUS_METERS;
    }

    private static String osmXml() {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<osm version=\"0.6\" generator=\"public-gui-smoke\">\n"
                + "<bounds minlat=\"0.009\" minlon=\"0.009\" maxlat=\"0.011\" maxlon=\"0.011\"/>\n");
        int index = 0;
        for (int east = -40; east <= 40; east += 8) {
            var point = PublicGuiSmokeOracle.point(east, 0);
            xml.append("<node id=\"").append(101 + index++).append("\" version=\"1\" lat=\"")
                    .append(point.latitudeDegrees()).append("\" lon=\"")
                    .append(point.longitudeDegrees()).append("\"/>\n");
        }
        xml.append("<way id=\"110\" version=\"1\"><tag k=\"highway\" v=\"path\"/>");
        for (int id = 101; id <= 111; id++) xml.append("<nd ref=\"").append(id).append("\"/>");
        return xml.append("</way></osm>\n").toString();
    }

    private static double worldPixelX(int zoom, double longitude) {
        return (longitude + 180.0) / 360.0 * TILE_SIZE * (1 << zoom);
    }

    private static double worldPixelY(int zoom, double latitude) {
        double radians = Math.toRadians(latitude);
        double tangent = Math.tan(radians);
        double asinh = Math.log(tangent + Math.sqrt(tangent * tangent + 1.0));
        return (1.0 - asinh / Math.PI) / 2.0
                * TILE_SIZE * (1 << zoom);
    }

    private record Tile(ManagedTileAddress address, Path path) { }
}
