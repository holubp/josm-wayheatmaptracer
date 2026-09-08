package org.openstreetmap.josm.plugins.wayheatmaptracer.v022;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Pure, deterministic synthetic heatmap fixture with separated truth, evidence, and editable graphs. */
public final class SyntheticHeatmapScene {
    /** Scene bounds in physical metres. */
    public record Bounds(double minX, double minY, double maxX, double maxY) {
    }

    /** A deliberately defective geometry, never a substitute for physical truth. */
    public record DefectiveCandidate(List<V022Point> points) {
        /** Defensively copies proposal coordinates. */
        public DefectiveCandidate {
            points = List.copyOf(points);
        }
    }

    /** Independent raster-observation mask; false means unavailable rather than low intensity. */
    @FunctionalInterface
    public interface EvidenceMask {
        /** Returns whether scalar evidence exists at physical point {@code (x,y)}. */
        boolean isObserved(double x, double y);
    }

    private final String id;
    private final int seed;
    private final TopologyOracle.Network truthGraph;
    private final TopologyOracle.Network initialGraph;
    private final EvidenceMask evidenceMask;
    private final Optional<DefectiveCandidate> defectiveCandidate;
    private final Optional<TopologyOracle.Network> defectiveNetwork;
    private final Map<String, String> attributes;
    private final Bounds bounds;

    /** Creates a test-only scene with the default metric scalar-raster contract. */
    SyntheticHeatmapScene(String id, int seed, TopologyOracle.Network truthGraph, TopologyOracle.Network initialGraph,
            EvidenceMask evidenceMask, DefectiveCandidate defectiveCandidate,
            TopologyOracle.Network defectiveNetwork, Map<String, String> attributes) {
        this.id = id;
        this.seed = seed;
        this.truthGraph = truthGraph;
        this.initialGraph = initialGraph;
        this.evidenceMask = evidenceMask;
        this.defectiveCandidate = Optional.ofNullable(defectiveCandidate);
        this.defectiveNetwork = Optional.ofNullable(defectiveNetwork);
        this.attributes = Map.copyOf(new LinkedHashMap<>(attributes));
        this.bounds = calculateBounds(truthGraph);
    }

    /** Returns the public scene identifier. */
    public String id() {
        return id;
    }

    /** Returns the deterministic fixture seed. */
    public int seed() {
        return seed;
    }

    /** Returns the physical truth graph. */
    public TopologyOracle.Network truthGraph() {
        return truthGraph;
    }

    /** Returns the separate editable initial graph. */
    public TopologyOracle.Network initialGraph() {
        return initialGraph;
    }

    /** Returns the independent missing-data mask. */
    public EvidenceMask evidenceMask() {
        return evidenceMask;
    }

    /** Returns an optional known defective candidate. */
    public Optional<DefectiveCandidate> defectiveCandidate() {
        return defectiveCandidate;
    }

    /** Returns an optional known defective topology proposal. */
    public Optional<TopologyOracle.Network> defectiveNetwork() {
        return defectiveNetwork;
    }

    /** Returns immutable scene mechanism metadata. */
    public Map<String, String> attributes() {
        return attributes;
    }

    /** Returns raster bounds: all truth routes plus the required 30 metre halo. */
    public Bounds bounds() {
        return bounds;
    }

    /** Returns the default metric raster pitch in metres. */
    public double rasterPitchMeters() {
        return 1.5;
    }

    /** Returns the deterministic background scalar intensity. */
    public double backgroundIntensity() {
        return 0.01;
    }

    /** Returns the required physical halo around truth geometry. */
    public double haloMeters() {
        return 30.0;
    }

    /** Returns the physical x coordinate of the center of raster column {@code index}. */
    public double pixelCenterX(int index) {
        return bounds.minX() + (index + 0.5) * rasterPitchMeters();
    }

    /** Returns the physical y coordinate of the center of raster row {@code index}. */
    public double pixelCenterY(int index) {
        return bounds.minY() + (index + 0.5) * rasterPitchMeters();
    }

    /** Returns raw scalar intensity before any palette mapping or production filtering. */
    public double intensityAt(double x, double y) {
        V022Point point = new V022Point(x, y);
        double intensity = backgroundIntensity();
        for (TopologyOracle.Branch branch : truthGraph.branches()) {
            double distance = branch.curve().distanceTo(point);
            intensity += branch.amplitude() * Math.exp(-(distance * distance) / (2.0 * branch.sigmaMeters() * branch.sigmaMeters()));
        }
        return Math.max(0.0, Math.min(1.0, intensity + portableNoise(x, y)));
    }

    private double portableNoise(double x, double y) {
        long cellX = (long) Math.floor((x - bounds.minX()) / rasterPitchMeters());
        long cellY = (long) Math.floor((y - bounds.minY()) / rasterPitchMeters());
        long hash = 0x9E3779B97F4A7C15L ^ seed;
        hash ^= cellX * 0xC2B2AE3D27D4EB4FL;
        hash ^= cellY * 0x165667B19E3779F9L;
        hash ^= hash >>> 33;
        hash *= 0xFF51AFD7ED558CCDL;
        hash ^= hash >>> 33;
        double unit = (hash >>> 11) * 0x1.0p-53;
        return (unit - 0.5) * 0.01;
    }

    private static Bounds calculateBounds(TopologyOracle.Network graph) {
        if (graph.branches().isEmpty()) {
            return new Bounds(-30.0, -30.0, 30.0, 30.0);
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (TopologyOracle.Branch branch : graph.branches()) {
            for (V022Point point : branch.sampledPoints()) {
                minX = Math.min(minX, point.x());
                minY = Math.min(minY, point.y());
                maxX = Math.max(maxX, point.x());
                maxY = Math.max(maxY, point.y());
            }
        }
        return new Bounds(minX - 30.0, minY - 30.0, maxX + 30.0, maxY + 30.0);
    }
}
