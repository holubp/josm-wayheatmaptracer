package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PluginLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.PolylineMath;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.GeometryPostProcessor;

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

    /** Native Legacy fixed-turn limit, shared with the standalone route. */
    public static final double MAX_FIXED_TURN_DEGREES = 75.0;
    private static final GeometryPostProcessor postProcessor = new GeometryPostProcessor();

    /** Typed rejection rather than inventing a protected identity removed by native postprocessing. */
    public static final class ProtectedOccurrenceLostException extends RuntimeException {
        private final int sourceIndex;
        private ProtectedOccurrenceLostException(int sourceIndex) {
            super("Legacy final preview lost a protected original occurrence");
            this.sourceIndex = sourceIndex;
        }
        public int sourceIndex() { return sourceIndex; }
    }

    /** Exact native Legacy reconstruction, optional interval reduction, fixed-turn guard and topology cleanup. */
    public static Result finish(List<ProjectedPoint> source, List<ProjectedPoint> traced,
            List<Integer> fixedIndices, boolean simplify, double projectedTolerance) {
        if (!Double.isFinite(projectedTolerance) || projectedTolerance < 0) {
            throw new IllegalArgumentException("Projected simplification tolerance must be finite and nonnegative");
        }
        Result reconstructed = reconstruct(source, traced, fixedIndices);
        List<EastNorth> original = asEastNorth(source);
        List<EastNorth> preview = asEastNorth(reconstructed.points());
        if (simplify) { preview = simplifyPreview(original, preview, fixedIndices, projectedTolerance); }
        preview = guardFixedTurns(original, preview, fixedIndices);
        preview = cleanTopology(original, preview, fixedIndices);
        List<ProjectedPoint> finished = fromEastNorth(preview);
        var mapping = new TreeMap<Integer,Integer>();
        int previous = -1;
        for (var entry : new TreeMap<>(reconstructed.originalToVertex()).entrySet()) {
            ProjectedPoint expected = reconstructed.points().get(entry.getValue());
            int retained = -1;
            for (int index = previous+1; index < finished.size(); index++) {
                if (finished.get(index).equals(expected)) { retained = index; break; }
            }
            if (retained < 0) { throw new ProtectedOccurrenceLostException(entry.getKey()); }
            mapping.put(entry.getKey(),retained); previous = retained;
        }
        return new Result(finished,mapping);
    }

    /** Existing projected-unit interval simplification, also used by standalone Legacy. */
    public static List<EastNorth> simplifyPreview(List<EastNorth> sourcePolyline,
            List<EastNorth> preview, List<Integer> fixedIndices, double tolerance) {
        if (preview.size() <= 2 || tolerance <= 0.0) {
            return preview;
        }
        List<Integer> previewAnchors = new ArrayList<>();
        for (int fixedIndex : fixedIndices) {
            int previewIndex = findMatchingPoint(preview, sourcePolyline.get(fixedIndex));
            if (previewIndex >= 0 && !previewAnchors.contains(previewIndex)) {
                previewAnchors.add(previewIndex);
            }
        }
        previewAnchors.sort(Integer::compareTo);
        if (previewAnchors.isEmpty() || previewAnchors.get(0) != 0) {
            previewAnchors.add(0, 0);
        }
        int last = preview.size() - 1;
        if (previewAnchors.get(previewAnchors.size() - 1) != last) {
            previewAnchors.add(last);
        }

        List<EastNorth> simplifiedPreview = new ArrayList<>();
        for (int i = 0; i < previewAnchors.size() - 1; i++) {
            int start = previewAnchors.get(i);
            int end = previewAnchors.get(i + 1);
            if (end <= start) {
                continue;
            }
            List<EastNorth> section = new ArrayList<>(preview.subList(start, end + 1));
            List<EastNorth> simplifiedSection = postProcessor.simplify(section, tolerance);
            if (!simplifiedPreview.isEmpty() && !simplifiedSection.isEmpty()) {
                simplifiedSection = new ArrayList<>(simplifiedSection.subList(1, simplifiedSection.size()));
            }
            simplifiedPreview.addAll(simplifiedSection);
        }
        if (simplifiedPreview.size() < 2) {
            return preview;
        }
        PluginLog.verbose("Precise-shape interval simplification: %d -> %d points with tolerance %.2f.",
            preview.size(), simplifiedPreview.size(), tolerance);
        return simplifiedPreview;
    }

    /** Existing Legacy guard around exact fixed anchors; no live selection is required. */
    public static List<EastNorth> guardFixedTurns(List<EastNorth> sourcePolyline,
            List<EastNorth> preview, List<Integer> fixedIndices) {
        if (preview.size() < 3 || fixedIndices.isEmpty()) { return preview; }

        List<EastNorth> guarded = new ArrayList<>(preview);
        for (int fixedIndex : fixedIndices) {
            EastNorth anchor = sourcePolyline.get(fixedIndex);
            int previewIndex = findMatchingPoint(guarded, anchor);
            if (previewIndex <= 0 || previewIndex >= guarded.size() - 1) {
                continue;
            }
            while (guarded.size() >= 3 && previewIndex > 0 && previewIndex < guarded.size() - 1) {
                double turn = turningAngleDegrees(guarded.get(previewIndex - 1), guarded.get(previewIndex), guarded.get(previewIndex + 1));
                if (turn < MAX_FIXED_TURN_DEGREES) {
                    break;
                }
                double leftDistance = guarded.get(previewIndex - 1).distance(anchor);
                double rightDistance = guarded.get(previewIndex + 1).distance(anchor);
                boolean canRemoveLeft = previewIndex - 1 > 0
                    && !isFixedAnchorPoint(fixedIndices, sourcePolyline, guarded.get(previewIndex - 1));
                boolean canRemoveRight = previewIndex + 1 < guarded.size() - 1
                    && !isFixedAnchorPoint(fixedIndices, sourcePolyline, guarded.get(previewIndex + 1));
                if (leftDistance <= rightDistance && canRemoveLeft) {
                    guarded.remove(previewIndex - 1);
                    previewIndex--;
                } else if (canRemoveRight) {
                    guarded.remove(previewIndex + 1);
                } else if (canRemoveLeft) {
                    guarded.remove(previewIndex - 1);
                    previewIndex--;
                } else {
                    break;
                }
                PluginLog.debug("Removed a near-anchor precise preview point to avoid an unsupported %.1f degree fixed-node turn.", turn);
            }
        }
        return guarded;
    }

    /** Existing Legacy endpoint-cluster and loop cleanup in captured projection units. */
    public static List<EastNorth> cleanTopology(List<EastNorth> sourcePolyline,
            List<EastNorth> preview, List<Integer> fixedIndices) {
        if (preview.size() < 4) {
            return preview;
        }
        List<EastNorth> protectedPoints = fixedIndices.stream()
            .map(sourcePolyline::get)
            .toList();
        double nearAnchorDistance = Math.max(3.0, Math.min(12.0, PolylineMath.length(sourcePolyline) * 0.08));
        List<EastNorth> cleaned = postProcessor.pruneEndpointClusters(preview, nearAnchorDistance, 35.0);
        cleaned = postProcessor.removeSelfIntersectionLoops(cleaned, protectedPoints);
        if (cleaned.size() < 2) {
            return preview;
        }
        if (cleaned.size() != preview.size()) {
            PluginLog.verbose(
                "Topology cleanup reduced precise preview points from %d to %d; terminal turn %.1f -> %.1f degrees.",
                preview.size(), cleaned.size(), terminalTurnDegrees(preview), terminalTurnDegrees(cleaned));
        }
        return cleaned;
    }

    private static boolean isFixedAnchorPoint(List<Integer> fixedIndices,
            List<EastNorth> sourcePolyline, EastNorth point) {
        for (int fixedIndex : fixedIndices) {
            if (sourcePolyline.get(fixedIndex).distance(point) < 0.01) {
                return true;
            }
        }
        return false;
    }

    private static int findMatchingPoint(List<EastNorth> points, EastNorth target) {
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).distance(target) < 0.01) {
                return i;
            }
        }
        return -1;
    }

    private static double turningAngleDegrees(EastNorth previous, EastNorth current, EastNorth next) {
        double ax = current.east() - previous.east();
        double ay = current.north() - previous.north();
        double bx = next.east() - current.east();
        double by = next.north() - current.north();
        double aNorm = Math.hypot(ax, ay);
        double bNorm = Math.hypot(bx, by);
        if (aNorm == 0.0 || bNorm == 0.0) {
            return 0.0;
        }
        double cosine = (ax * bx + ay * by) / (aNorm * bNorm);
        cosine = Math.max(-1.0, Math.min(1.0, cosine));
        return Math.toDegrees(Math.acos(cosine));
    }

    private static double terminalTurnDegrees(List<EastNorth> polyline) {
        if (polyline.size() < 3) {
            return 0.0;
        }
        return Math.max(
            turningAngleDegrees(polyline.get(0), polyline.get(1), polyline.get(2)),
            turningAngleDegrees(polyline.get(polyline.size() - 3), polyline.get(polyline.size() - 2),
                polyline.get(polyline.size() - 1))
        );
    }
}
