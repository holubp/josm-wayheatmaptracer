package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Objects;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Exact double-valued L0 nodes from one already acquired managed image. */
public final class ManagedScalarGrid {
    private final int width;
    private final int height;
    private final double[] values;
    private final boolean[] acquiredNodes;
    private final AttemptMemoryLedger.Owner owner;
    private final ManagedScalarGridView view;

    private ManagedScalarGrid(int width, int height, double[] values, boolean[] acquiredNodes,
            AttemptMemoryLedger.Owner owner) {
        this.width = width;
        this.height = height;
        this.values = values;
        this.acquiredNodes = acquiredNodes;
        this.owner = owner;
        this.view = new ManagedScalarGridView(this);
    }

    /** Maps every acquired source pixel once, charging its retained array before allocation. */
    static ManagedScalarGrid fromAcquiredImage(BufferedImage image, String detector,
            IntensitySamplingMode mode, EmissionWorkBudget budget, AttemptMemoryLedger.Owner owner) {
        return map(image, null, detector, mode, budget, owner);
    }

    /** Uses only usable, fully recorded tiles as connector acquisition support. */
    static ManagedScalarGrid fromManagedMosaic(TileHeatmapSampler.TileMosaic mosaic, String detector,
            IntensitySamplingMode mode, EmissionWorkBudget budget, AttemptMemoryLedger.Owner owner) {
        Objects.requireNonNull(mosaic, "prepared managed mosaic");
        return map(mosaic.image(), mosaic, detector, mode, budget, owner);
    }

    private static ManagedScalarGrid map(BufferedImage image, TileHeatmapSampler.TileMosaic mosaic,
            String detector, IntensitySamplingMode mode, EmissionWorkBudget budget,
            AttemptMemoryLedger.Owner owner) {
        Objects.requireNonNull(image, "managed image");
        Objects.requireNonNull(detector, "detector");
        Objects.requireNonNull(mode, "intensity mode");
        Objects.requireNonNull(budget, "shared work budget");
        Objects.requireNonNull(owner, "retained owner");
        int width = image.getWidth(), height = image.getHeight();
        if (width < 2 || height < 2) {
            throw new IllegalArgumentException("Managed L0 needs at least one complete source cell");
        }
        long nodes = Math.multiplyExact((long) width, height);
        int tileColumns = Math.max(1, (width + TileHeatmapSampler.TILE_SIZE - 1)
                / TileHeatmapSampler.TILE_SIZE);
        int tileRows = Math.max(1, (height + TileHeatmapSampler.TILE_SIZE - 1)
                / TileHeatmapSampler.TILE_SIZE);
        long tileCount = Math.multiplyExact((long) tileColumns, tileRows);
        long bytes = Math.addExact(AttemptMemoryLedger.arrayBytes(nodes, Double.BYTES),
                AttemptMemoryLedger.arrayBytes(nodes, 1));
        bytes = Math.addExact(bytes, AttemptMemoryLedger.arrayBytes(tileCount, 1));
        bytes = Math.addExact(bytes, AttemptMemoryLedger.objectBytes(88));
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION, 1);
        var reservation = owner.reserve(bytes);
        try {
            double[] values = new double[Math.toIntExact(nodes)];
            boolean[] acquiredNodes = new boolean[values.length];
            boolean[] acquiredTiles = new boolean[Math.toIntExact(tileCount)];
            if (mosaic == null) {
                Arrays.fill(acquiredTiles, true); // Explicit numerical test source, not a production receipt.
            } else if (width % TileHeatmapSampler.TILE_SIZE == 0
                    && height % TileHeatmapSampler.TILE_SIZE == 0
                    && mosaic.tileImages().size() == tileCount
                    && mosaic.tiles().size() == tileCount
                    && mosaic.originWorldPxX() % TileHeatmapSampler.TILE_SIZE == 0
                    && mosaic.originWorldPxY() % TileHeatmapSampler.TILE_SIZE == 0) {
                long firstX = (long) (mosaic.originWorldPxX() / TileHeatmapSampler.TILE_SIZE);
                long firstY = (long) (mosaic.originWorldPxY() / TileHeatmapSampler.TILE_SIZE);
                for (var tile : mosaic.tiles()) {
                    budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                    long x = (long) tile.x() - firstX, y = (long) tile.y() - firstY;
                    if (x >= 0 && x < tileColumns && y >= 0 && y < tileRows
                            && tile.color().equals(mosaic.color()) && tile.zoom() == mosaic.zoom()
                            && tile.usable() && tile.width() == TileHeatmapSampler.TILE_SIZE
                            && tile.height() == TileHeatmapSampler.TILE_SIZE) {
                        acquiredTiles[(int) (y * tileColumns + x)] = true;
                    }
                }
            }
            for (int y = 0; y < height; y++) {
                budget.charge(EmissionWorkBudget.Operation.PREPARATION, 1);
                for (int x = 0; x < width; x++) {
                    budget.charge(EmissionWorkBudget.Operation.NODE_READ, 1);
                    int index = y * width + x;
                    values[index] = RenderedHeatmapSampler.scalarIntensity(
                            image.getRGB(x, y), detector, mode);
                    acquiredNodes[index] = acquiredTiles[(y / TileHeatmapSampler.TILE_SIZE) * tileColumns
                            + x / TileHeatmapSampler.TILE_SIZE];
                }
            }
            var grid = new ManagedScalarGrid(width, height, values, acquiredNodes, owner);
            reservation.adopt(grid);
            return grid;
        } catch (RuntimeException | Error failure) {
            reservation.close();
            throw failure;
        }
    }

    private void requireOpen() {
        if (owner.isClosed()) throw new IllegalStateException("Managed L0 owner is closed");
    }

    /** Uses the historical nearest-pixel rounding, including negative fractional coordinates. */
    public double sampleNearest(double x, double y) {
        requireOpen();
        int sx = (int) Math.round(x), sy = (int) Math.round(y);
        return sx >= 0 && sy >= 0 && sx < width && sy < height
                ? values[sy * width + sx] : 0.0;
    }

    int width() { requireOpen(); return width; }
    int height() { requireOpen(); return height; }
    double nodeValue(int x, int y) {
        requireOpen();
        return x >= 0 && y >= 0 && x < width && y < height
                && acquiredNodes[y * width + x] ? values[y * width + x] : Double.NaN;
    }

    boolean supportsCell(int x, int y) {
        requireOpen();
        if (x < 0 || y < 0 || x >= width - 1 || y >= height - 1) return false;
        int upperLeft = y * width + x;
        return acquiredNodes[upperLeft] && acquiredNodes[upperLeft + 1]
                && acquiredNodes[upperLeft + width] && acquiredNodes[upperLeft + width + 1];
    }

    /** Read-only source-image-center coordinates for the connector's bilinear integral. */
    public ManagedScalarGridView view() { requireOpen(); return view; }
}
