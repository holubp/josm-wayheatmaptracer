package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class FixtureRegressionTest {
    private static final String FIXTURE_ARCHIVE = "wayheatmaptracer-testing.zip";
    private static final List<String> COLORS = List.of("hot", "blue", "bluered", "purple", "gray");
    private static final double SAMPLE_SPACING_PX = 8.0;
    private static final double COMPARISON_SPACING_PX = 6.0;
    private static final double MAX_MEAN_DISTANCE_PX = 20.0;
    private static final double MAX_HAUSDORFF_DISTANCE_PX = 75.0;
    private static final double ACCEPTABLE_OFFSET_METERS =
        doubleProperty("wayheatmaptracer.fixture.acceptableOffsetMeters", 15.0);
    private static final double ARC_STEP_METERS = 2.0;
    private static final int VIS_ZOOM = 15;
    private static final int VIS_TILE_SIZE = 512;
    private static final Path VISUALIZATION_DIR = Path.of("build", "fixture-regression");
    private static final Path ACCEPTABLE_LIMITS_OSM = VISUALIZATION_DIR.resolve("acceptable-limits.osm");
    private static final Path VIOLATIONS_OSM = VISUALIZATION_DIR.resolve("violations.osm");

    @Test
    void tracedChangedSegmentsStayCloseToManualBaseline() throws Exception {
        Path archive = Path.of(FIXTURE_ARCHIVE).toAbsolutePath().normalize();
        assertTrue(Files.exists(archive), "Expected fixture archive at " + archive);

        FixtureBundle fixture = FixtureBundle.load(archive);
        assertFalse(fixture.changedWays().isEmpty(), "Expected at least one changed way in the fixture bundle");
        List<ChangedWaySegment> regressionCases = fixture.changedWays().stream()
            .filter(this::isMaterialRegressionCase)
            .toList();
        assertTrue(regressionCases.size() >= 8, "Expected at least 8 material changed segments for regression coverage");
        Files.createDirectories(VISUALIZATION_DIR);
        writeAcceptableLimitsOsm(ACCEPTABLE_LIMITS_OSM, fixture.bounds(), fixture.changedWays());

        Map<String, TileMosaic> mosaics = new HashMap<>();
        RenderedHeatmapSampler sampler = new RenderedHeatmapSampler();
        RidgeTracker tracker = new RidgeTracker();

        List<String> failures = new ArrayList<>();
        List<ComparisonResult> bestResults = new ArrayList<>();
        for (ChangedWaySegment changed : regressionCases) {
            ComparisonResult best = null;
            for (String color : COLORS) {
                TileMosaic mosaic = mosaics.computeIfAbsent(color, c -> fixture.loadMosaic(c));
                ComparisonResult result = traceAndCompare(changed, color, mosaic, sampler, tracker);
                if (best == null || result.betterThan(best)) {
                    best = result;
                }
            }
            assertTrue(best != null, "No comparison result produced for way " + changed.wayId());
            bestResults.add(best);
            if (best.outsideEnvelope()
                || best.meanDistancePx() > MAX_MEAN_DISTANCE_PX
                || best.hausdorffDistancePx() > MAX_HAUSDORFF_DISTANCE_PX) {
                failures.add(best.describe());
            }
        }
        writeViolationsOsm(VIOLATIONS_OSM, fixture.bounds(), bestResults);

        assertTrue(failures.isEmpty(),
            "Changed-way fixture regression exceeded tolerance:\n" + String.join("\n", failures));
    }

    private boolean isMaterialRegressionCase(ChangedWaySegment changed) {
        double maxDisplacement = segmentMaxDisplacement(changed);
        return changed.beforeSegment().size() >= 3
            && changed.afterSegment().size() >= 3
            && Math.min(pixelLength(changed.beforeSegment()), pixelLength(changed.afterSegment())) >= 40.0
            && maxDisplacement >= 8.0
            && maxDisplacement <= 90.0;
    }

    private ComparisonResult traceAndCompare(
        ChangedWaySegment changed,
        String color,
        TileMosaic mosaic,
        RenderedHeatmapSampler sampler,
        RidgeTracker tracker
    ) {
        List<Point2D.Double> beforePixels = mosaic.toLocalPixels(changed.beforeSegment());
        List<Point2D.Double> afterPixels = mosaic.toLocalPixels(changed.afterSegment());
        if (!mosaic.containsAll(beforePixels) || !mosaic.containsAll(afterPixels)) {
            return ComparisonResult.outOfCoverage(changed.wayId(), color);
        }

        List<Point2D.Double> dense = resamplePixels(beforePixels, SAMPLE_SPACING_PX);
        List<RenderedHeatmapSampler.CrossSectionProfile> profiles =
            sampler.sampleProfilesOnRaster(mosaic.image(), dense, 18, 4, color, 1.0);
        List<CenterlineCandidate> candidates = tracker.track(profiles);
        if (candidates.isEmpty()) {
            return ComparisonResult.noCandidate(changed.wayId(), color, changed.afterSegment());
        }

        List<Point2D.Double> traced = new ArrayList<>(candidates.get(0).screenPoints());
        traced.set(0, beforePixels.get(0));
        traced.set(traced.size() - 1, beforePixels.get(beforePixels.size() - 1));

        CurveMetrics metrics = compareCurves(traced, afterPixels, COMPARISON_SPACING_PX);
        List<LatLonPoint> tracedPolyline = mosaic.toLatLonPolyline(traced);
        EnvelopeCheck envelope = AcceptanceEnvelope.forPolyline(changed.fullAfterWay(), ACCEPTABLE_OFFSET_METERS)
            .check(tracedPolyline);
        return new ComparisonResult(
            changed.wayId(),
            color,
            metrics.meanDistancePx(),
            metrics.hausdorffDistancePx(),
            envelope.maxOverflowMeters(),
            false,
            false,
            envelope.outside(),
            tracedPolyline,
            changed.afterSegment()
        );
    }

    private CurveMetrics compareCurves(List<Point2D.Double> left, List<Point2D.Double> right, double spacingPx) {
        List<Point2D.Double> sampledLeft = resamplePixels(left, spacingPx);
        List<Point2D.Double> sampledRight = resamplePixels(right, spacingPx);
        double mean = (meanNearestDistance(sampledLeft, sampledRight) + meanNearestDistance(sampledRight, sampledLeft)) / 2.0;
        double hausdorff = Math.max(maxNearestDistance(sampledLeft, sampledRight), maxNearestDistance(sampledRight, sampledLeft));
        return new CurveMetrics(mean, hausdorff);
    }

    private double meanNearestDistance(List<Point2D.Double> source, List<Point2D.Double> target) {
        double sum = 0.0;
        for (Point2D.Double point : source) {
            sum += nearestDistance(point, target);
        }
        return source.isEmpty() ? Double.POSITIVE_INFINITY : sum / source.size();
    }

    private double maxNearestDistance(List<Point2D.Double> source, List<Point2D.Double> target) {
        double max = 0.0;
        for (Point2D.Double point : source) {
            max = Math.max(max, nearestDistance(point, target));
        }
        return max;
    }

    private double nearestDistance(Point2D.Double point, List<Point2D.Double> target) {
        double best = Double.POSITIVE_INFINITY;
        for (Point2D.Double candidate : target) {
            best = Math.min(best, point.distance(candidate));
        }
        return best;
    }

    private double pixelLength(List<LatLonPoint> polyline) {
        return length(toWorldPixels(polyline));
    }

    private double segmentMaxDisplacement(ChangedWaySegment changed) {
        List<Point2D.Double> before = toWorldPixels(changed.beforeSegment());
        List<Point2D.Double> after = toWorldPixels(changed.afterSegment());
        return Math.max(maxNearestDistance(before, after), maxNearestDistance(after, before));
    }

    private List<Point2D.Double> toWorldPixels(List<LatLonPoint> polyline) {
        List<Point2D.Double> points = new ArrayList<>(polyline.size());
        for (LatLonPoint point : polyline) {
            points.add(TileMosaic.toWorldPixel(point, VIS_ZOOM, VIS_TILE_SIZE));
        }
        return points;
    }

    private void writeAcceptableLimitsOsm(Path output, OsmBounds bounds, List<ChangedWaySegment> changedWays) throws IOException {
        List<ExportWay> ways = new ArrayList<>();
        for (ChangedWaySegment changed : changedWays) {
            if (changed.fullAfterWay().size() < 2) {
                continue;
            }
            AcceptanceEnvelope envelope = AcceptanceEnvelope.forPolyline(changed.fullAfterWay(), ACCEPTABLE_OFFSET_METERS);
            addAcceptableLimitWays(ways, changed.wayId(), "left", "acceptable_limit_left",
                parallelOffsetPolylines(changed.fullAfterWay(), envelope, 1.0));
            addAcceptableLimitWays(ways, changed.wayId(), "right", "acceptable_limit_right",
                parallelOffsetPolylines(changed.fullAfterWay(), envelope, -1.0));
            addAcceptableLimitCapWays(ways, changed.wayId(), envelope);
        }
        for (ExportWay way : ways) {
            assertTrue(!hasSelfIntersection(way.polyline()),
                "Visual acceptable limit self-intersects for " + way.name());
        }
        writeOsm(output, bounds, ways);
    }

    private void addAcceptableLimitCapWays(List<ExportWay> ways, String wayId, AcceptanceEnvelope envelope) {
        List<LatLonPoint> startCap = capArcPolyline(envelope, true);
        List<LatLonPoint> endCap = capArcPolyline(envelope, false);
        if (startCap.size() >= 2) {
            ways.add(new ExportWay(
                "limit-cap-start-" + wayId,
                acceptableLimitTags("acceptable_limit_cap_start", wayId),
                startCap
            ));
        }
        if (endCap.size() >= 2) {
            ways.add(new ExportWay(
                "limit-cap-end-" + wayId,
                acceptableLimitTags("acceptable_limit_cap_end", wayId),
                endCap
            ));
        }
    }

    private void addAcceptableLimitWays(List<ExportWay> ways, String wayId, String side, String tagValue,
                                        List<List<LatLonPoint>> polylines) {
        for (List<LatLonPoint> polyline : polylines) {
            if (polyline.size() < 2) {
                continue;
            }
            ways.add(new ExportWay(
                "limit-" + side + "-" + wayId,
                acceptableLimitTags(tagValue, wayId),
                polyline
            ));
        }
    }

    private Map<String, String> acceptableLimitTags(String tagValue, String wayId) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("wayheatmaptracer", tagValue);
        tags.put("reference_way_id", wayId);
        tags.put("acceptable_offset_m", String.format(Locale.ROOT, "%.1f", ACCEPTABLE_OFFSET_METERS));
        tags.put("hausdorff_tolerance_px", String.format(Locale.ROOT, "%.1f", MAX_HAUSDORFF_DISTANCE_PX));
        tags.put("mean_tolerance_px", String.format(Locale.ROOT, "%.1f", MAX_MEAN_DISTANCE_PX));
        return tags;
    }

    private void writeViolationsOsm(Path output, OsmBounds bounds, List<ComparisonResult> results) throws IOException {
        List<ExportWay> ways = new ArrayList<>();
        for (ComparisonResult result : results) {
            if (!result.outsideEnvelope()
                && result.meanDistancePx() <= MAX_MEAN_DISTANCE_PX
                && result.hausdorffDistancePx() <= MAX_HAUSDORFF_DISTANCE_PX) {
                continue;
            }
            List<LatLonPoint> polyline = result.tracedPolyline().isEmpty() ? result.expectedPolyline() : result.tracedPolyline();
            ways.add(new ExportWay(
                "violation-" + result.wayId() + "-" + result.color(),
                new LinkedHashMap<>(Map.of(
                    "wayheatmaptracer", result.noCandidate() ? "missing_candidate" : "violation_trace",
                    "reference_way_id", result.wayId(),
                    "color", result.color(),
                    "mean_distance_px", Double.isFinite(result.meanDistancePx()) ? String.format(Locale.ROOT, "%.2f", result.meanDistancePx()) : "inf",
                    "hausdorff_distance_px", Double.isFinite(result.hausdorffDistancePx()) ? String.format(Locale.ROOT, "%.2f", result.hausdorffDistancePx()) : "inf",
                    "outside_envelope", Boolean.toString(result.outsideEnvelope()),
                    "max_envelope_overflow_m", String.format(Locale.ROOT, "%.2f", result.maxEnvelopeOverflowMeters())
                )),
                polyline
            ));
        }
        writeOsm(output, bounds, ways);
    }

    private List<List<LatLonPoint>> parallelOffsetPolylines(List<LatLonPoint> polyline, AcceptanceEnvelope envelope, double sign) {
        double distanceMeters = envelope.offsetMeters();
        if (polyline.size() < 2) {
            return List.of(polyline);
        }
        LocalProjection projection = envelope.projection();
        List<Point2D.Double> metric = envelope.source();
        List<Point2D.Double> cleaned = dedupe(metric);
        if (cleaned.size() < 2) {
            return List.of(polyline);
        }

        List<Point2D.Double> segmentDirections = new ArrayList<>(cleaned.size() - 1);
        List<Point2D.Double> segmentNormals = new ArrayList<>(cleaned.size() - 1);
        for (int i = 1; i < cleaned.size(); i++) {
            Point2D.Double start = cleaned.get(i - 1);
            Point2D.Double end = cleaned.get(i);
            Point2D.Double tangent = PolylineMath.normalize(end.x - start.x, end.y - start.y);
            segmentDirections.add(tangent);
            segmentNormals.add(new Point2D.Double(-tangent.y * sign, tangent.x * sign));
        }

        List<Point2D.Double> offset = new ArrayList<>();
        offset.add(add(cleaned.get(0), scale(segmentNormals.get(0), distanceMeters)));
        for (int i = 1; i < cleaned.size() - 1; i++) {
            Point2D.Double vertex = cleaned.get(i);
            Point2D.Double prevDir = segmentDirections.get(i - 1);
            Point2D.Double nextDir = segmentDirections.get(i);
            Point2D.Double prevNormal = segmentNormals.get(i - 1);
            Point2D.Double nextNormal = segmentNormals.get(i);
            Point2D.Double prevOffset = add(vertex, scale(prevNormal, distanceMeters));
            Point2D.Double nextOffset = add(vertex, scale(nextNormal, distanceMeters));

            double turn = cross(prevDir, nextDir);
            boolean outerJoin = sign * turn < 0.0;
            if (outerJoin) {
                appendArc(offset, vertex, prevNormal, nextNormal, distanceMeters, sign);
            } else {
                Point2D.Double intersection = lineIntersection(
                    prevOffset, prevDir,
                    nextOffset, nextDir
                );
                if (intersection != null && intersection.distance(vertex) <= distanceMeters * 4.0) {
                    appendPoint(offset, intersection);
                } else {
                    appendPoint(offset, prevOffset);
                    appendPoint(offset, nextOffset);
                }
            }
        }
        offset.add(add(cleaned.get(cleaned.size() - 1), scale(segmentNormals.get(segmentNormals.size() - 1), distanceMeters)));

        List<Point2D.Double> simplified = removeSelfIntersectionLoops(dedupe(offset));
        return List.of(projection.toLatLon(simplified));
    }

    private List<LatLonPoint> capArcPolyline(AcceptanceEnvelope envelope, boolean startCap) {
        List<Point2D.Double> source = envelope.source();
        if (source.size() < 2) {
            return List.of();
        }
        Point2D.Double center = startCap ? source.get(0) : source.get(source.size() - 1);
        Point2D.Double tangent = startCap
            ? PolylineMath.normalize(source.get(1).x - source.get(0).x, source.get(1).y - source.get(0).y)
            : PolylineMath.normalize(
                source.get(source.size() - 1).x - source.get(source.size() - 2).x,
                source.get(source.size() - 1).y - source.get(source.size() - 2).y
            );
        Point2D.Double leftNormal = new Point2D.Double(-tangent.y, tangent.x);
        Point2D.Double rightNormal = new Point2D.Double(tangent.y, -tangent.x);
        double fromAngle;
        double toAngle;
        double throughAngle;
        if (startCap) {
            fromAngle = Math.atan2(leftNormal.y, leftNormal.x);
            toAngle = Math.atan2(rightNormal.y, rightNormal.x);
            throughAngle = Math.atan2(-tangent.y, -tangent.x);
        } else {
            fromAngle = Math.atan2(rightNormal.y, rightNormal.x);
            toAngle = Math.atan2(leftNormal.y, leftNormal.x);
            throughAngle = Math.atan2(tangent.y, tangent.x);
        }
        return envelope.projection().toLatLon(sampleArc(center, envelope.offsetMeters(), fromAngle, toAngle, throughAngle));
    }

    private List<Point2D.Double> sampleArc(Point2D.Double center, double radiusMeters,
                                           double fromAngle, double toAngle, double throughAngle) {
        double sweep = chooseSweep(fromAngle, toAngle, throughAngle);
        int steps = Math.max(8, (int) Math.ceil(Math.abs(sweep) * radiusMeters / ARC_STEP_METERS));
        List<Point2D.Double> points = new ArrayList<>(steps + 1);
        for (int i = 0; i <= steps; i++) {
            double angle = fromAngle + sweep * i / steps;
            points.add(new Point2D.Double(
                center.x + Math.cos(angle) * radiusMeters,
                center.y + Math.sin(angle) * radiusMeters
            ));
        }
        return points;
    }

    private double chooseSweep(double fromAngle, double toAngle, double throughAngle) {
        double direct = normalizeAngle(toAngle - fromAngle);
        if (angleOnSweep(fromAngle, direct, throughAngle)) {
            return direct;
        }
        return direct > 0.0 ? direct - Math.PI * 2.0 : direct + Math.PI * 2.0;
    }

    private boolean angleOnSweep(double startAngle, double sweep, double targetAngle) {
        double delta = normalizeAngle(targetAngle - startAngle);
        if (sweep >= 0.0) {
            if (delta < 0.0) {
                delta += Math.PI * 2.0;
            }
            return delta <= sweep + 1e-9;
        }
        if (delta > 0.0) {
            delta -= Math.PI * 2.0;
        }
        return delta >= sweep - 1e-9;
    }

    private static double nearestDistanceToPolyline(Point2D.Double point, List<Point2D.Double> polyline) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 1; i < polyline.size(); i++) {
            best = Math.min(best, distanceToSegment(point, polyline.get(i - 1), polyline.get(i)));
        }
        return best;
    }

    private static double distanceToSegment(Point2D.Double point, Point2D.Double start, Point2D.Double end) {
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared < 1e-9) {
            return point.distance(start);
        }
        double t = ((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        double projX = start.x + dx * t;
        double projY = start.y + dy * t;
        return point.distance(projX, projY);
    }


    private void appendArc(List<Point2D.Double> points, Point2D.Double center, Point2D.Double fromNormal,
                           Point2D.Double toNormal, double radiusMeters, double sign) {
        double startAngle = Math.atan2(fromNormal.y, fromNormal.x);
        double endAngle = Math.atan2(toNormal.y, toNormal.x);
        double sweep = normalizeAngle(endAngle - startAngle);
        if (sign > 0 && sweep > 0) {
            sweep -= Math.PI * 2.0;
        } else if (sign < 0 && sweep < 0) {
            sweep += Math.PI * 2.0;
        }
        int steps = Math.max(2, (int) Math.ceil(Math.abs(sweep) * radiusMeters / ARC_STEP_METERS));
        for (int step = 0; step <= steps; step++) {
            double angle = startAngle + sweep * step / steps;
            Point2D.Double point = new Point2D.Double(
                center.x + Math.cos(angle) * radiusMeters,
                center.y + Math.sin(angle) * radiusMeters
            );
            appendPoint(points, point);
        }
    }

    private double normalizeAngle(double angle) {
        while (angle <= -Math.PI) {
            angle += Math.PI * 2.0;
        }
        while (angle > Math.PI) {
            angle -= Math.PI * 2.0;
        }
        return angle;
    }

    private Point2D.Double lineIntersection(Point2D.Double p1, Point2D.Double d1, Point2D.Double p2, Point2D.Double d2) {
        double denominator = cross(d1, d2);
        if (Math.abs(denominator) < 1e-9) {
            return null;
        }
        Point2D.Double delta = new Point2D.Double(p2.x - p1.x, p2.y - p1.y);
        double t = cross(delta, d2) / denominator;
        return new Point2D.Double(p1.x + d1.x * t, p1.y + d1.y * t);
    }

    private double cross(Point2D.Double left, Point2D.Double right) {
        return left.x * right.y - left.y * right.x;
    }

    private Point2D.Double add(Point2D.Double base, Point2D.Double delta) {
        return new Point2D.Double(base.x + delta.x, base.y + delta.y);
    }

    private Point2D.Double scale(Point2D.Double vector, double factor) {
        return new Point2D.Double(vector.x * factor, vector.y * factor);
    }

    private void appendPoint(List<Point2D.Double> points, Point2D.Double candidate) {
        if (points.isEmpty() || points.get(points.size() - 1).distance(candidate) > 0.05) {
            points.add(candidate);
        }
    }

    private List<Point2D.Double> dedupe(List<Point2D.Double> polyline) {
        List<Point2D.Double> result = new ArrayList<>();
        for (Point2D.Double point : polyline) {
            appendPoint(result, point);
        }
        return result;
    }

    private List<Point2D.Double> removeSelfIntersectionLoops(List<Point2D.Double> polyline) {
        List<Point2D.Double> current = new ArrayList<>(polyline);
        boolean changed = true;
        while (changed && current.size() >= 4) {
            changed = false;
            for (int i = 1; i < current.size(); i++) {
                Point2D.Double a1 = current.get(i - 1);
                Point2D.Double a2 = current.get(i);
                for (int j = i + 2; j < current.size(); j++) {
                    if (i == 1 && j == current.size() - 1) {
                        continue;
                    }
                    Point2D.Double b1 = current.get(j - 1);
                    Point2D.Double b2 = current.get(j);
                    Point2D.Double intersection = segmentIntersection(a1, a2, b1, b2);
                    if (intersection != null) {
                        List<Point2D.Double> next = new ArrayList<>();
                        for (int k = 0; k < i; k++) {
                            appendPoint(next, current.get(k));
                        }
                        appendPoint(next, intersection);
                        for (int k = j; k < current.size(); k++) {
                            appendPoint(next, current.get(k));
                        }
                        current = next;
                        changed = true;
                        break;
                    }
                }
                if (changed) {
                    break;
                }
            }
        }
        return current;
    }

    private Point2D.Double segmentIntersection(Point2D.Double a1, Point2D.Double a2, Point2D.Double b1, Point2D.Double b2) {
        double dx1 = a2.x - a1.x;
        double dy1 = a2.y - a1.y;
        double dx2 = b2.x - b1.x;
        double dy2 = b2.y - b1.y;
        double denominator = dx1 * dy2 - dy1 * dx2;
        if (Math.abs(denominator) < 1e-9) {
            return null;
        }
        double cx = b1.x - a1.x;
        double cy = b1.y - a1.y;
        double t = (cx * dy2 - cy * dx2) / denominator;
        double u = (cx * dy1 - cy * dx1) / denominator;
        if (t <= 1e-6 || t >= 1.0 - 1e-6 || u <= 1e-6 || u >= 1.0 - 1e-6) {
            return null;
        }
        return new Point2D.Double(a1.x + t * dx1, a1.y + t * dy1);
    }

    private boolean hasSelfIntersection(List<LatLonPoint> polyline) {
        List<Point2D.Double> metric = LocalProjection.forPolyline(polyline).toLocalMeters(polyline);
        for (int i = 1; i < metric.size(); i++) {
            Point2D.Double a1 = metric.get(i - 1);
            Point2D.Double a2 = metric.get(i);
            for (int j = i + 2; j < metric.size(); j++) {
                if (i == 1 && j == metric.size() - 1) {
                    continue;
                }
                if (segmentIntersection(a1, a2, metric.get(j - 1), metric.get(j)) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private void writeOsm(Path output, OsmBounds bounds, List<ExportWay> ways) throws IOException {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version='1.0' encoding='UTF-8'?>\n");
        xml.append("<osm version='0.6' generator='WayHeatmapTracer FixtureRegressionTest'>\n");
        if (bounds != null) {
            xml.append(String.format(Locale.ROOT,
                "  <bounds minlat='%.7f' minlon='%.7f' maxlat='%.7f' maxlon='%.7f' />\n",
                bounds.minLat(), bounds.minLon(), bounds.maxLat(), bounds.maxLon()));
        }
        long nextNodeId = -1;
        long nextWayId = -1;
        List<String> wayXml = new ArrayList<>();
        for (ExportWay way : ways) {
            if (way.polyline().size() < 2) {
                continue;
            }
            List<Long> nodeIds = new ArrayList<>(way.polyline().size());
            for (LatLonPoint point : way.polyline()) {
                long nodeId = nextNodeId--;
                nodeIds.add(nodeId);
                xml.append(String.format(Locale.ROOT,
                    "  <node id='%d' visible='true' lat='%.11f' lon='%.11f' />\n",
                    nodeId, point.lat(), point.lon()));
            }
            StringBuilder wayBuilder = new StringBuilder();
            wayBuilder.append(String.format(Locale.ROOT, "  <way id='%d' visible='true'>\n", nextWayId--));
            for (Long nodeId : nodeIds) {
                wayBuilder.append(String.format(Locale.ROOT, "    <nd ref='%d' />\n", nodeId));
            }
            wayBuilder.append(String.format(Locale.ROOT, "    <tag k='name' v='%s' />\n", escapeXml(way.name())));
            for (Map.Entry<String, String> tag : way.tags().entrySet()) {
                wayBuilder.append(String.format(Locale.ROOT,
                    "    <tag k='%s' v='%s' />\n",
                    escapeXml(tag.getKey()),
                    escapeXml(tag.getValue())));
            }
            wayBuilder.append("  </way>\n");
            wayXml.add(wayBuilder.toString());
        }
        for (String wayFragment : wayXml) {
            xml.append(wayFragment);
        }
        xml.append("</osm>\n");
        Files.writeString(output, xml.toString());
    }

    private String escapeXml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    private static List<Point2D.Double> resamplePixels(List<Point2D.Double> polyline, double spacingPx) {
        if (polyline.size() < 2) {
            return polyline;
        }
        double total = length(polyline);
        if (total == 0.0) {
            return new ArrayList<>(polyline);
        }
        int count = Math.max(2, (int) Math.round(total / Math.max(1.0, spacingPx)) + 1);
        List<Point2D.Double> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double target = total * i / (count - 1.0);
            result.add(interpolate(polyline, target));
        }
        return result;
    }

    private static double length(List<Point2D.Double> polyline) {
        double total = 0.0;
        for (int i = 1; i < polyline.size(); i++) {
            total += polyline.get(i - 1).distance(polyline.get(i));
        }
        return total;
    }

    private static Point2D.Double interpolate(List<Point2D.Double> polyline, double targetDistance) {
        double walked = 0.0;
        for (int i = 1; i < polyline.size(); i++) {
            Point2D.Double start = polyline.get(i - 1);
            Point2D.Double end = polyline.get(i);
            double segment = start.distance(end);
            if (walked + segment >= targetDistance) {
                double factor = segment == 0.0 ? 0.0 : (targetDistance - walked) / segment;
                return new Point2D.Double(
                    start.x + (end.x - start.x) * factor,
                    start.y + (end.y - start.y) * factor
                );
            }
            walked += segment;
        }
        return polyline.get(polyline.size() - 1);
    }

    private record CurveMetrics(double meanDistancePx, double hausdorffDistancePx) {
    }

    private record ComparisonResult(
        String wayId,
        String color,
        double meanDistancePx,
        double hausdorffDistancePx,
        double maxEnvelopeOverflowMeters,
        boolean outOfCoverage,
        boolean noCandidate,
        boolean outsideEnvelope,
        List<LatLonPoint> tracedPolyline,
        List<LatLonPoint> expectedPolyline
    ) {
        static ComparisonResult outOfCoverage(String wayId, String color) {
            return new ComparisonResult(
                wayId, color, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                true, false, true, List.of(), List.of()
            );
        }

        static ComparisonResult noCandidate(String wayId, String color, List<LatLonPoint> expectedPolyline) {
            return new ComparisonResult(
                wayId, color, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                false, true, true, List.of(), expectedPolyline
            );
        }

        boolean betterThan(ComparisonResult other) {
            if (other == null) {
                return true;
            }
            if (outsideEnvelope != other.outsideEnvelope) {
                return !outsideEnvelope;
            }
            if (Double.compare(hausdorffDistancePx, other.hausdorffDistancePx) != 0) {
                return hausdorffDistancePx < other.hausdorffDistancePx;
            }
            return meanDistancePx < other.meanDistancePx;
        }

        String describe() {
            if (outOfCoverage) {
                return "way " + wayId + " color=" + color + " is outside extracted tile coverage";
            }
            if (noCandidate) {
                return "way " + wayId + " color=" + color + " produced no ridge candidate";
            }
            return String.format(Locale.ROOT,
                "way %s bestColor=%s mean=%.2fpx hausdorff=%.2fpx outsideEnvelope=%s overflow=%.2fm",
                wayId, color, meanDistancePx, hausdorffDistancePx, outsideEnvelope, maxEnvelopeOverflowMeters);
        }
    }

    private record ChangedWaySegment(
        String wayId,
        List<LatLonPoint> beforeSegment,
        List<LatLonPoint> afterSegment,
        List<LatLonPoint> fullAfterWay
    ) {
    }

    private record LatLonPoint(double lat, double lon) {
    }

    private record ExportWay(String name, Map<String, String> tags, List<LatLonPoint> polyline) {
    }

    private record OsmBounds(double minLat, double minLon, double maxLat, double maxLon) {
    }

    private static final class FixtureBundle {
        private static final Pattern TILE_PATTERN = Pattern.compile(".*_(\\d+)_(\\d+)_(\\d+)\\.(png|jpg)$");

        private final Path archive;
        private final OsmBounds bounds;
        private final OsmData before;
        private final OsmData after;
        private final List<ChangedWaySegment> changedWays;

        private FixtureBundle(Path archive, OsmBounds bounds, OsmData before, OsmData after, List<ChangedWaySegment> changedWays) {
            this.archive = archive;
            this.bounds = bounds;
            this.before = before;
            this.after = after;
            this.changedWays = changedWays;
        }

        static FixtureBundle load(Path archive) throws Exception {
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                OsmData before = OsmData.parse(zip.getInputStream(require(zip, "example_before.osm")));
                OsmData after = OsmData.parse(zip.getInputStream(require(zip, "example_after.osm")));
                return new FixtureBundle(archive, before.bounds, before, after, detectChangedWays(before, after));
            }
        }

        List<ChangedWaySegment> changedWays() {
            return changedWays;
        }

        OsmBounds bounds() {
            return bounds;
        }

        TileMosaic loadMosaic(String color) {
            String entryName = "ride-" + color + ".zip";
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                byte[] nested = readAll(zip.getInputStream(require(zip, entryName)));
                return TileMosaic.fromNestedZip(nested, TILE_PATTERN);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to load nested tile archive " + entryName, ex);
            }
        }

        private static ZipEntry require(ZipFile zip, String name) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                throw new IllegalStateException("Missing fixture entry " + name);
            }
            return entry;
        }

        private static List<ChangedWaySegment> detectChangedWays(OsmData before, OsmData after) {
            List<ChangedWaySegment> changed = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : before.ways.entrySet()) {
                String wayId = entry.getKey();
                List<String> beforeRefs = entry.getValue();
                List<String> afterRefs = after.ways.get(wayId);
                if (afterRefs == null) {
                    continue;
                }
                List<LatLonPoint> fullBefore = resolveRange(beforeRefs, before.nodes, 0, beforeRefs.size() - 1);
                List<LatLonPoint> fullAfter = resolveRange(afterRefs, after.nodes, 0, afterRefs.size() - 1);
                if (!isModifiedWay(beforeRefs, before.nodes, afterRefs, after.nodes)) {
                    continue;
                }

                int beforeStart;
                int beforeEnd;
                int afterStart;
                int afterEnd;
                if (beforeRefs.equals(afterRefs)) {
                    int prefix = commonResolvedPrefix(fullBefore, fullAfter);
                    int suffix = commonResolvedSuffix(fullBefore, fullAfter, prefix);
                    beforeStart = Math.max(0, prefix - 1);
                    beforeEnd = Math.min(fullBefore.size() - 1, fullBefore.size() - suffix);
                    afterStart = Math.max(0, prefix - 1);
                    afterEnd = Math.min(fullAfter.size() - 1, fullAfter.size() - suffix);
                } else {
                    int prefix = commonPrefix(beforeRefs, afterRefs);
                    int suffix = commonSuffix(beforeRefs, afterRefs, prefix);
                    beforeStart = Math.max(0, prefix - 1);
                    beforeEnd = Math.min(beforeRefs.size() - 1, beforeRefs.size() - suffix);
                    afterStart = Math.max(0, prefix - 1);
                    afterEnd = Math.min(afterRefs.size() - 1, afterRefs.size() - suffix);
                }

                changed.add(new ChangedWaySegment(
                    wayId,
                    resolveRange(beforeRefs, before.nodes, beforeStart, beforeEnd),
                    resolveRange(afterRefs, after.nodes, afterStart, afterEnd),
                    fullAfter
                ));
            }
            changed.sort(Comparator.comparing(ChangedWaySegment::wayId));
            return changed;
        }

        private static boolean sameResolvedPolyline(List<String> leftRefs, Map<String, LatLonPoint> leftNodes, List<String> rightRefs, Map<String, LatLonPoint> rightNodes) {
            if (leftRefs.size() != rightRefs.size()) {
                return false;
            }
            for (int i = 0; i < leftRefs.size(); i++) {
                LatLonPoint left = leftNodes.get(leftRefs.get(i));
                LatLonPoint right = rightNodes.get(rightRefs.get(i));
                if (left == null || right == null) {
                    return false;
                }
                if (Math.abs(left.lat() - right.lat()) > 1e-12 || Math.abs(left.lon() - right.lon()) > 1e-12) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isModifiedWay(List<String> beforeRefs, Map<String, LatLonPoint> beforeNodes,
                                             List<String> afterRefs, Map<String, LatLonPoint> afterNodes) {
            if (!beforeRefs.equals(afterRefs)) {
                return true;
            }
            return !sameResolvedPolyline(beforeRefs, beforeNodes, afterRefs, afterNodes);
        }

        private static int commonPrefix(List<String> beforeRefs, List<String> afterRefs) {
            int limit = Math.min(beforeRefs.size(), afterRefs.size());
            int prefix = 0;
            while (prefix < limit && beforeRefs.get(prefix).equals(afterRefs.get(prefix))) {
                prefix++;
            }
            return prefix;
        }

        private static int commonSuffix(List<String> beforeRefs, List<String> afterRefs, int prefix) {
            int suffix = 0;
            while (suffix < beforeRefs.size() - prefix && suffix < afterRefs.size() - prefix
                && beforeRefs.get(beforeRefs.size() - 1 - suffix).equals(afterRefs.get(afterRefs.size() - 1 - suffix))) {
                suffix++;
            }
            return suffix;
        }

        private static int commonResolvedPrefix(List<LatLonPoint> before, List<LatLonPoint> after) {
            int limit = Math.min(before.size(), after.size());
            int prefix = 0;
            while (prefix < limit && samePoint(before.get(prefix), after.get(prefix))) {
                prefix++;
            }
            return prefix;
        }

        private static int commonResolvedSuffix(List<LatLonPoint> before, List<LatLonPoint> after, int prefix) {
            int suffix = 0;
            while (suffix < before.size() - prefix && suffix < after.size() - prefix
                && samePoint(before.get(before.size() - 1 - suffix), after.get(after.size() - 1 - suffix))) {
                suffix++;
            }
            return suffix;
        }

        private static boolean samePoint(LatLonPoint left, LatLonPoint right) {
            return Math.abs(left.lat() - right.lat()) <= 1e-12
                && Math.abs(left.lon() - right.lon()) <= 1e-12;
        }

        private static List<LatLonPoint> resolveRange(List<String> refs, Map<String, LatLonPoint> nodes, int startInclusive, int endInclusive) {
            List<LatLonPoint> result = new ArrayList<>();
            for (int i = startInclusive; i <= endInclusive; i++) {
                LatLonPoint point = nodes.get(refs.get(i));
                if (point != null) {
                    result.add(point);
                }
            }
            return result;
        }
    }

    private static final class OsmData {
        private OsmBounds bounds;
        private final Map<String, LatLonPoint> nodes = new LinkedHashMap<>();
        private final Map<String, List<String>> ways = new LinkedHashMap<>();

        static OsmData parse(InputStream input) throws Exception {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Document document = factory.newDocumentBuilder().parse(input);
            OsmData data = new OsmData();

            NodeList bounds = document.getDocumentElement().getElementsByTagName("bounds");
            if (bounds.getLength() > 0) {
                Element bound = (Element) bounds.item(0);
                data.bounds = new OsmBounds(
                    Double.parseDouble(bound.getAttribute("minlat")),
                    Double.parseDouble(bound.getAttribute("minlon")),
                    Double.parseDouble(bound.getAttribute("maxlat")),
                    Double.parseDouble(bound.getAttribute("maxlon"))
                );
            }

            NodeList nodes = document.getDocumentElement().getElementsByTagName("node");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element node = (Element) nodes.item(i);
                data.nodes.put(node.getAttribute("id"), new LatLonPoint(
                    Double.parseDouble(node.getAttribute("lat")),
                    Double.parseDouble(node.getAttribute("lon"))
                ));
            }

            NodeList ways = document.getDocumentElement().getElementsByTagName("way");
            for (int i = 0; i < ways.getLength(); i++) {
                Element way = (Element) ways.item(i);
                NodeList nds = way.getElementsByTagName("nd");
                List<String> refs = new ArrayList<>(nds.getLength());
                for (int j = 0; j < nds.getLength(); j++) {
                    refs.add(((Element) nds.item(j)).getAttribute("ref"));
                }
                data.ways.put(way.getAttribute("id"), refs);
            }
            return data;
        }
    }

    private static final class TileMosaic {
        private final BufferedImage image;
        private final int zoom;
        private final int minTileX;
        private final int minTileY;
        private final int tileSize;

        private TileMosaic(BufferedImage image, int zoom, int minTileX, int minTileY, int tileSize) {
            this.image = image;
            this.zoom = zoom;
            this.minTileX = minTileX;
            this.minTileY = minTileY;
            this.tileSize = tileSize;
        }

        static TileMosaic fromNestedZip(byte[] zipBytes, Pattern tilePattern) throws Exception {
            List<TileEntry> tiles = new ArrayList<>();
            try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    Matcher matcher = tilePattern.matcher(entry.getName());
                    if (!matcher.matches()) {
                        continue;
                    }
                    int zoom = Integer.parseInt(matcher.group(1));
                    int tileX = Integer.parseInt(matcher.group(2));
                    int tileY = Integer.parseInt(matcher.group(3));
                    BufferedImage image = ImageIO.read(input);
                    if (image != null) {
                        tiles.add(new TileEntry(zoom, tileX, tileY, image));
                    }
                }
            }

            int maxZoom = tiles.stream().mapToInt(TileEntry::zoom).max()
                .orElseThrow(() -> new IllegalStateException("No tile images found in nested archive"));
            List<TileEntry> topZoom = tiles.stream().filter(tile -> tile.zoom == maxZoom).toList();
            int tileSize = topZoom.get(0).image.getWidth();
            int minX = topZoom.stream().mapToInt(TileEntry::tileX).min().orElseThrow();
            int maxX = topZoom.stream().mapToInt(TileEntry::tileX).max().orElseThrow();
            int minY = topZoom.stream().mapToInt(TileEntry::tileY).min().orElseThrow();
            int maxY = topZoom.stream().mapToInt(TileEntry::tileY).max().orElseThrow();

            BufferedImage mosaic = new BufferedImage((maxX - minX + 1) * tileSize, (maxY - minY + 1) * tileSize, BufferedImage.TYPE_INT_ARGB);
            for (TileEntry tile : topZoom) {
                int offsetX = (tile.tileX - minX) * tileSize;
                int offsetY = (tile.tileY - minY) * tileSize;
                mosaic.getGraphics().drawImage(tile.image, offsetX, offsetY, null);
            }
            return new TileMosaic(mosaic, maxZoom, minX, minY, tileSize);
        }

        BufferedImage image() {
            return image;
        }

        List<Point2D.Double> toLocalPixels(List<LatLonPoint> polyline) {
            List<Point2D.Double> points = new ArrayList<>(polyline.size());
            for (LatLonPoint point : polyline) {
                points.add(toLocalPixel(point));
            }
            return points;
        }

        List<LatLonPoint> toLatLonPolyline(List<Point2D.Double> polyline) {
            List<LatLonPoint> points = new ArrayList<>(polyline.size());
            for (Point2D.Double point : polyline) {
                points.add(worldToLatLon(point.x + minTileX * tileSize, point.y + minTileY * tileSize, zoom, tileSize));
            }
            return points;
        }

        boolean containsAll(List<Point2D.Double> points) {
            for (Point2D.Double point : points) {
                if (point.x < 0 || point.y < 0 || point.x >= image.getWidth() || point.y >= image.getHeight()) {
                    return false;
                }
            }
            return true;
        }

        private Point2D.Double toLocalPixel(LatLonPoint point) {
            Point2D.Double world = toWorldPixel(point, zoom, tileSize);
            double worldX = world.x;
            double worldY = world.y;
            return new Point2D.Double(worldX - minTileX * tileSize, worldY - minTileY * tileSize);
        }

        static Point2D.Double toWorldPixel(LatLonPoint point, int zoom, int tileSize) {
            double scale = (1 << zoom) * tileSize;
            double worldX = (point.lon() + 180.0) / 360.0 * scale;
            double sinLat = Math.sin(Math.toRadians(point.lat()));
            double worldY = (0.5 - Math.log((1 + sinLat) / (1 - sinLat)) / (4 * Math.PI)) * scale;
            return new Point2D.Double(worldX, worldY);
        }

        static LatLonPoint worldToLatLon(double worldX, double worldY, int zoom, int tileSize) {
            double scale = (1 << zoom) * tileSize;
            double lon = worldX / scale * 360.0 - 180.0;
            double y = 0.5 - worldY / scale;
            double lat = Math.toDegrees(Math.atan(Math.sinh(y * 2.0 * Math.PI)));
            return new LatLonPoint(lat, lon);
        }

        private record TileEntry(int zoom, int tileX, int tileY, BufferedImage image) {
        }
    }

    private static final class LocalProjection {
        private static final double METERS_PER_DEGREE_LAT = 111_320.0;

        private final double originLat;
        private final double originLon;
        private final double metersPerDegreeLon;

        private LocalProjection(double originLat, double originLon, double metersPerDegreeLon) {
            this.originLat = originLat;
            this.originLon = originLon;
            this.metersPerDegreeLon = metersPerDegreeLon;
        }

        static LocalProjection forPolyline(List<LatLonPoint> polyline) {
            double sumLat = 0.0;
            double sumLon = 0.0;
            for (LatLonPoint point : polyline) {
                sumLat += point.lat();
                sumLon += point.lon();
            }
            double originLat = sumLat / polyline.size();
            double originLon = sumLon / polyline.size();
            double metersPerDegreeLon = METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(originLat));
            return new LocalProjection(originLat, originLon, metersPerDegreeLon);
        }

        List<Point2D.Double> toLocalMeters(List<LatLonPoint> polyline) {
            List<Point2D.Double> result = new ArrayList<>(polyline.size());
            for (LatLonPoint point : polyline) {
                result.add(new Point2D.Double(
                    (point.lon() - originLon) * metersPerDegreeLon,
                    (point.lat() - originLat) * METERS_PER_DEGREE_LAT
                ));
            }
            return result;
        }

        List<LatLonPoint> toLatLon(List<Point2D.Double> polyline) {
            List<LatLonPoint> result = new ArrayList<>(polyline.size());
            for (Point2D.Double point : polyline) {
                result.add(new LatLonPoint(
                    originLat + point.y / METERS_PER_DEGREE_LAT,
                    originLon + point.x / metersPerDegreeLon
                ));
            }
            return result;
        }
    }

    private record EnvelopeCheck(boolean outside, double maxOverflowMeters) {
    }

    private static double doubleProperty(String key, double fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid double system property " + key + "=" + value, ex);
        }
    }

    private static final class AcceptanceEnvelope {
        private final LocalProjection projection;
        private final List<Point2D.Double> source;
        private final Shape shape;
        private final double offsetMeters;

        private AcceptanceEnvelope(LocalProjection projection, List<Point2D.Double> source, Shape shape, double offsetMeters) {
            this.projection = projection;
            this.source = source;
            this.shape = shape;
            this.offsetMeters = offsetMeters;
        }

        static AcceptanceEnvelope forPolyline(List<LatLonPoint> polyline, double offsetMeters) {
            LocalProjection projection = LocalProjection.forPolyline(polyline);
            List<Point2D.Double> source = projection.toLocalMeters(polyline);
            List<Point2D.Double> cleaned = new ArrayList<>();
            for (Point2D.Double point : source) {
                if (cleaned.isEmpty() || cleaned.get(cleaned.size() - 1).distance(point) > 0.05) {
                    cleaned.add(point);
                }
            }

            Path2D.Double path = new Path2D.Double();
            path.moveTo(cleaned.get(0).x, cleaned.get(0).y);
            for (int i = 1; i < cleaned.size(); i++) {
                path.lineTo(cleaned.get(i).x, cleaned.get(i).y);
            }
            Shape shape = new BasicStroke(
                (float) (offsetMeters * 2.0),
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND
            ).createStrokedShape(path);
            return new AcceptanceEnvelope(projection, cleaned, shape, offsetMeters);
        }

        LocalProjection projection() {
            return projection;
        }

        List<Point2D.Double> source() {
            return source;
        }

        double offsetMeters() {
            return offsetMeters;
        }

        boolean containsLocalPoint(Point2D.Double point) {
            return shape.contains(point);
        }

        EnvelopeCheck check(List<LatLonPoint> polyline) {
            List<Point2D.Double> traced = projection.toLocalMeters(polyline);
            List<Point2D.Double> sampled = resamplePixels(traced, 2.0);
            boolean outside = false;
            double maxOverflow = 0.0;
            for (Point2D.Double point : sampled) {
                if (!shape.contains(point)) {
                    outside = true;
                    double overflow = Math.max(0.0, nearestDistanceToPolyline(point, source) - offsetMeters);
                    maxOverflow = Math.max(maxOverflow, overflow);
                }
            }
            return new EnvelopeCheck(outside, maxOverflow);
        }
    }

    private static byte[] readAll(InputStream input) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
