package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PolylineMath;

/** Existing Legacy fixed-anchor reconstruction, expressed through detached projected values. */
public final class LegacyPreciseGeometry {
    private LegacyPreciseGeometry() { }

    /** Coordinates in the captured projection's units; these are explicitly NOT ground metres. */
    public record ProjectedPoint(double east, double north) {
        public ProjectedPoint {
            if (!Double.isFinite(east) || !Double.isFinite(north)) {
                throw new IllegalArgumentException("Projected coordinates must be finite");
            }
        }
    }

    /** Reconstruction and original source-index mapping, before optional protected-interval simplification. */
    public record Result(List<ProjectedPoint> points, Map<Integer,Integer> originalToVertex) {
        public Result {
            points = List.copyOf(points);
            originalToVertex = Map.copyOf(originalToVertex);
        }
    }

    /**
     * Uses the same projected path fractions and 1e-9 strict interval ownership as
     * Legacy. The input contains no Node, DataSet, active projection or MapView.
     */
    public static Result reconstruct(List<ProjectedPoint> source, List<ProjectedPoint> working,
            List<Integer> protectedSourceIndices) {
        if (source == null || source.size() < 2 || working == null || working.size() < 2
                || protectedSourceIndices == null || source.stream().anyMatch(java.util.Objects::isNull)
                || working.stream().anyMatch(java.util.Objects::isNull)
                || protectedSourceIndices.stream().anyMatch(i -> i == null || i < 0 || i >= source.size())) {
            throw new IllegalArgumentException("Complete projected curves and valid protected indices are required");
        }
        if (protectedSourceIndices.isEmpty()) {
            return new Result(working, Map.of(0, 0, source.size() - 1, working.size() - 1));
        }
        var boundaries = new TreeSet<>(protectedSourceIndices);
        boundaries.add(0);
        boundaries.add(source.size() - 1);
        var indices = new ArrayList<>(boundaries);
        var sourceFractions = PolylineMath.fractionsForSegment(asEastNorth(source));
        var workingFractions = PolylineMath.fractionsForSegment(asEastNorth(working));
        var result = new ArrayList<ProjectedPoint>();
        var mapping = new TreeMap<Integer,Integer>();
        for (int interval = 0; interval < indices.size() - 1; interval++) {
            int start = indices.get(interval), end = indices.get(interval + 1);
            double startFraction = sourceFractions.get(start), endFraction = sourceFractions.get(end);
            var section = new ArrayList<ProjectedPoint>();
            section.add(source.get(start));
            for (int i = 0; i < working.size(); i++) {
                double fraction = workingFractions.get(i);
                if (fraction > startFraction + 1e-9 && fraction < endFraction - 1e-9) {
                    section.add(working.get(i));
                }
            }
            section.add(source.get(end));
            mapping.put(start, result.isEmpty() ? 0 : result.size() - 1);
            if (!result.isEmpty()) { section = new ArrayList<>(section.subList(1, section.size())); }
            result.addAll(section);
            mapping.put(end, result.size() - 1);
        }
        return new Result(result, mapping);
    }

    /** Creates temporary immutable coordinate values, without accessing any live JOSM state. */
    public static List<EastNorth> asEastNorth(List<ProjectedPoint> points) {
        return points.stream().map(point -> new EastNorth(point.east(), point.north())).toList();
    }

    /** Copies projected numeric values so worker input need not retain JOSM coordinate objects. */
    public static List<ProjectedPoint> fromEastNorth(List<EastNorth> points) {
        return points.stream().map(point -> new ProjectedPoint(point.east(), point.north())).toList();
    }
}
