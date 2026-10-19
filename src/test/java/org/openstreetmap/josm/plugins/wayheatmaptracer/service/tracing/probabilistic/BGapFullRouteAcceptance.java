package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationCatalog.CaseDefinition;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationCatalog.Scene;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationCatalog.Split;

/** Separate, truth-side full-route acceptance oracle for the persistent-parallel toy. */
final class BGapFullRouteAcceptance {
    static final String ORACLE_ID = "PARALLEL_SINGLE_BRANCH_FULL_INTERIOR_V1";
    private static final String PARALLEL = "sparse-crossing-noisy-strands-vs-persistent-parallel";
    private static final double FIRST_INTERIOR_X = 1.5;
    private static final double LAST_INTERIOR_X = 118.5;
    private static final int MAX_POINTS = 4096;
    private static final int MAX_BRANCHES = 8;
    private static final long MAX_WORK = 4_000_000;

    enum Reason { EXACT_ANCHORS, ONE_BRANCH_FULL_INTERIOR, ORDERED_APPROACH_DEPARTURE, WHOLE_SEGMENT_CONTAINMENT }
    record Check(Reason reason, BIdentificationAcceptance.Verdict verdict, int count) { }
    record Checks(List<Check> checks) {
        Checks { checks = List.copyOf(checks); }
        boolean passed() {
            if (checks.size() != Reason.values().length) return false;
            var seen = java.util.EnumSet.noneOf(Reason.class);
            for (var check : checks) {
                if (check == null || check.reason() == null || check.verdict() != BIdentificationAcceptance.Verdict.PASS
                        || !seen.add(check.reason())) return false;
            }
            return seen.size() == Reason.values().length;
        }
    }

    private BGapFullRouteAcceptance() { }

    static Checks evaluate(CaseDefinition definition, Scene scene, List<MetricPoint> path,
            double pitchMeters, CancellationProbe cancellation, AttemptMemoryLedger.Owner owner) {
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(owner, "owner");
        try (var transaction = owner.child("b-gap-full-route-result")) {
            var output = transaction.reserve(1024);
            var scratch = transaction.child("b-gap-full-route-scratch");
            try {
                Checks checks = evaluateChecks(definition, scene, path, pitchMeters, cancellation, scratch);
                output.adopt(checks);
                scratch.close();
                transaction.transferTo(owner);
                return checks;
            } finally {
                scratch.close();
                output.close();
            }
        }
    }

    private static Checks evaluateChecks(CaseDefinition definition, Scene scene, List<MetricPoint> path,
            double pitchMeters, CancellationProbe cancellation, AttemptMemoryLedger.Owner scratch) {
        cancellation.checkpoint();
        if (!(pitchMeters > 0.0) || !Double.isFinite(pitchMeters) || definition == null || scene == null
                || Double.compare(pitchMeters, definition.sourcePitchMeters()) != 0
                || !nativePitchMatches(definition, scene, pitchMeters, cancellation)) {
            return unavailable();
        }
        if (!applicable(definition, scene, cancellation)) return unavailable();
        if (path == null || path.size() < 2 || path.size() > MAX_POINTS) {
            return failed(Reason.EXACT_ANCHORS, 0, Reason.ONE_BRANCH_FULL_INTERIOR, 0,
                    Reason.ORDERED_APPROACH_DEPARTURE, 0, Reason.WHOLE_SEGMENT_CONTAINMENT, 0);
        }
        for (var point : path) {
            cancellation.checkpoint();
            if (point == null) return failed(Reason.EXACT_ANCHORS, 0, Reason.ONE_BRANCH_FULL_INTERIOR, 0,
                    Reason.ORDERED_APPROACH_DEPARTURE, 0, Reason.WHOLE_SEGMENT_CONTAINMENT, 0);
        }
        long vertices = 0;
        for (var label : scene.truth().regions()) {
            cancellation.checkpoint();
            if (label.association() == BIdentificationCatalog.AssociationLabel.AMBIGUOUS) {
                for (var curve : label.acceptableCurves()) {
                    cancellation.checkpoint();
                    if (curve.size() > MAX_POINTS) return unavailable();
                    vertices = Math.addExact(vertices, curve.size());
                }
            }
        }
        long bytes = Math.addExact(8192L, Math.addExact(Math.multiplyExact(vertices, 512L),
                Math.multiplyExact(path.size(), 192L)));
        scratch.reserve(bytes);
        var work = new Work(cancellation);
        List<List<MetricPoint>> branches = branches(scene, work);
        List<MetricRegion> tubes = new ArrayList<>(branches.size());
        for (var branch : branches) {
            work.step();
            tubes.add(tube(branch, pitchMeters, work));
        }
        if (!persistentParallel(branches, pitchMeters, work)) return unavailable();

        MetricPoint left = scene.source().get(0), right = scene.source().get(scene.source().size() - 1);
        boolean exact = new MetricPoint(0.0, 30.0).equals(left)
                && new MetricPoint(120.0, 30.0).equals(right)
                && path.get(0).equals(left) && path.get(path.size() - 1).equals(right);
        Check exactCheck = check(Reason.EXACT_ANCHORS, exact, 2);

        int branchChoice = -1, interiorSegments = 0;
        for (int branchIndex = 0; branchIndex < tubes.size(); branchIndex++) {
            boolean contained = true;
            int count = 0;
            for (int index = 1; index < path.size(); index++) {
                work.step();
                var clipped = clipX(path.get(index - 1), path.get(index), FIRST_INTERIOR_X, LAST_INTERIOR_X);
                if (clipped == null) continue;
                count++;
                contained &= tubes.get(branchIndex).containsSegment(clipped[0], clipped[1], cancellation,
                        work::charge, scratch);
            }
            if (contained && count > 0) {
                branchChoice = branchIndex;
                interiorSegments = count;
                break;
            }
        }
        boolean oneBranch = exact && branchChoice >= 0;
        Check branchCheck = check(Reason.ONE_BRANCH_FULL_INTERIOR, oneBranch, interiorSegments);

        int edgeCount = path.size() - 1;
        boolean ordered = exact && monotonicX(path, work);
        int contactCount = 0;
        if (branchChoice >= 0) {
            for (int index = 1; index < path.size(); index++) {
                work.step();
                for (int other = 0; other < tubes.size(); other++) {
                    if (other == branchChoice) continue;
                    work.step();
                    if (intersectsSegment(path.get(index - 1), path.get(index), tubes.get(other), work)) {
                        ordered = false;
                        contactCount++;
                    }
                }
            }
            var chosenTube = tubes.get(branchChoice);
            ordered &= contiguousFanContact(path, chosenTube, FIRST_INTERIOR_X, true, work, cancellation, scratch);
            ordered &= contiguousFanContact(path, chosenTube, LAST_INTERIOR_X, false, work, cancellation, scratch);
        } else {
            ordered = false;
        }
        Check orderedCheck = check(Reason.ORDERED_APPROACH_DEPARTURE, ordered, Math.max(1, edgeCount + contactCount));

        boolean whole = branchChoice >= 0;
        int containedEdges = 0;
        if (branchChoice >= 0) {
            List<MetricPoint> chosen = branches.get(branchChoice);
            double firstY = yAt(chosen, FIRST_INTERIOR_X, work);
            double lastY = yAt(chosen, LAST_INTERIOR_X, work);
            var regions = new ArrayList<List<MetricPoint>>(tubes.get(branchChoice).polygons());
            regions.add(List.of(left, new MetricPoint(FIRST_INTERIOR_X, firstY - pitchMeters),
                    new MetricPoint(FIRST_INTERIOR_X, firstY + pitchMeters)));
            regions.add(List.of(new MetricPoint(LAST_INTERIOR_X, lastY - pitchMeters), right,
                    new MetricPoint(LAST_INTERIOR_X, lastY + pitchMeters)));
            var allowed = new MetricRegion(regions);
            for (int index = 1; index < path.size(); index++) {
                work.step();
                boolean edgeContained = allowed.containsSegment(path.get(index - 1), path.get(index),
                        cancellation, work::charge, scratch);
                if (edgeContained) containedEdges++;
                else whole = false;
            }
        }
        cancellation.checkpoint();
        return new Checks(List.of(exactCheck, branchCheck, orderedCheck,
                check(Reason.WHOLE_SEGMENT_CONTAINMENT, whole && containedEdges == edgeCount, containedEdges)));
    }

    private static boolean nativePitchMatches(CaseDefinition definition, Scene scene, double pitch,
            CancellationProbe cancellation) {
        if (!definition.capabilities().contains("typed-native-resolution")) return false;
        var resolution = scene.evidence().resolution();
        if (resolution.kind() != org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution.Kind.NATIVE_SOURCE
                || resolution.nativePitchMeters().isEmpty()
                || Double.compare(resolution.nativePitchMeters().getAsDouble(), pitch) != 0) return false;
        for (var sample : resolution.spatialPitchSamples()) {
            cancellation.checkpoint();
            if (sample.nativePitchMeters().isEmpty()
                    || Double.compare(sample.nativePitchMeters().getAsDouble(), pitch) != 0) return false;
        }
        return true;
    }

    private static boolean applicable(CaseDefinition definition, Scene scene, CancellationProbe cancellation) {
        if (!PARALLEL.equals(definition.contrastId()) || !"right".equals(definition.side())
                || !(definition.key().split() == Split.TRAIN || definition.key().split() == Split.SELECT
                        || definition.key().split() == Split.REGRESSION)
                || scene.source().size() != 2 || !new MetricPoint(0, 30).equals(scene.source().get(0))
                || !new MetricPoint(120, 30).equals(scene.source().get(1))
                || scene.truth().regions().size() > 512) return false;
        int branchCount = 0;
        for (var label : scene.truth().regions()) {
            cancellation.checkpoint();
            if (label.association() != BIdentificationCatalog.AssociationLabel.AMBIGUOUS) continue;
            branchCount += label.acceptableCurves().size();
        }
        return branchCount == 2 && branchCount <= MAX_BRANCHES;
    }

    private static List<List<MetricPoint>> branches(Scene scene, Work work) {
        var result = new ArrayList<List<MetricPoint>>();
        for (var label : scene.truth().regions()) {
            work.step();
            if (label.association() == BIdentificationCatalog.AssociationLabel.AMBIGUOUS) {
                for (var curve : label.acceptableCurves()) { work.step(); result.add(curve); }
            }
        }
        return result;
    }

    private static boolean persistentParallel(List<List<MetricPoint>> branches, double pitch, Work work) {
        for (var branch : branches) {
            if (branch.size() < 2 || branch.size() > MAX_POINTS
                    || branch.get(0).xMeters() != 0.0 || branch.get(branch.size() - 1).xMeters() != 120.0) return false;
            for (int index = 1; index < branch.size(); index++) {
                work.step();
                if (!(branch.get(index).xMeters() > branch.get(index - 1).xMeters())) return false;
            }
        }
        double minimum = Double.POSITIVE_INFINITY;
        for (int i = 1; i < branches.get(0).size(); i++) {
            for (int j = 1; j < branches.get(1).size(); j++) {
                work.step();
                var a = branches.get(0).get(i - 1); var b = branches.get(0).get(i);
                var c = branches.get(1).get(j - 1); var d = branches.get(1).get(j);
                if (segmentsIntersect(a, b, c, d)) return false;
                minimum = Math.min(minimum, Math.min(Math.min(distance(a, c, d), distance(b, c, d)),
                        Math.min(distance(c, a, b), distance(d, a, b))));
            }
        }
        // Separated branch tubes are required; a genuine junction is a different control.
        return minimum > 2.0 * pitch;
    }

    private static boolean contiguousFanContact(List<MetricPoint> path, MetricRegion selectedTube,
            double boundaryX, boolean initial, Work work, CancellationProbe cancellation,
            AttemptMemoryLedger.Owner scratch) {
        boolean seen = false, reachedBoundary = false;
        if (initial) {
            for (int index = 1; index < path.size(); index++) {
                work.step();
                MetricPoint start = path.get(index - 1), end = path.get(index);
                if (start.xMeters() > boundaryX || end.xMeters() < start.xMeters()) break;
                boolean crossesBoundary = end.xMeters() >= boundaryX;
                if (crossesBoundary) end = atX(start, end, boundaryX);
                var intervals = contactIntervals(start, end, selectedTube, work, cancellation, scratch);
                if (intervals.isEmpty()) {
                    if (seen) return false;
                } else {
                    if (intervals.size() != 1 || (seen && intervals.get(0)[0] > 1e-10)
                            || intervals.get(0)[1] < 1.0 - 1e-10) return false;
                    seen = true;
                }
                if (crossesBoundary) { reachedBoundary = true; break; }
            }
            return seen && reachedBoundary;
        }
        for (int index = path.size() - 1; index > 0; index--) {
            work.step();
            MetricPoint start = path.get(index), end = path.get(index - 1);
            if (start.xMeters() < boundaryX || end.xMeters() > start.xMeters()) break;
            boolean crossesBoundary = end.xMeters() <= boundaryX;
            if (crossesBoundary) end = atX(start, end, boundaryX);
            var intervals = contactIntervals(start, end, selectedTube, work, cancellation, scratch);
            if (intervals.isEmpty()) {
                if (seen) return false;
            } else {
                if (intervals.size() != 1 || (seen && intervals.get(0)[0] > 1e-10)
                        || intervals.get(0)[1] < 1.0 - 1e-10) return false;
                seen = true;
            }
            if (crossesBoundary) { reachedBoundary = true; break; }
        }
        return seen && reachedBoundary;
    }

    private static List<double[]> contactIntervals(MetricPoint start, MetricPoint end,
            MetricRegion region, Work work, CancellationProbe cancellation, AttemptMemoryLedger.Owner scratch) {
        if (!intersectsSegment(start, end, region, work)) return List.of();
        if (region.containsSegment(start, end, cancellation, work::charge, scratch)) {
            return List.of(new double[] {0.0, 1.0});
        }
        var events = new ArrayList<Double>();
        events.add(0.0); events.add(1.0);
        for (var polygon : region.polygons()) {
            for (int edge = 0; edge < polygon.size(); edge++) {
                work.step();
                addIntersectionParameters(start, end, polygon.get(edge),
                        polygon.get((edge + 1) % polygon.size()), events);
            }
        }
        work.charge(Math.multiplyExact((long) events.size(), 64L - Long.numberOfLeadingZeros(events.size())));
        events.sort(Double::compare);
        var distinct = new ArrayList<Double>();
        for (double event : events) {
            double clipped = Math.max(0.0, Math.min(1.0, event));
            if (distinct.isEmpty() || clipped - distinct.get(distinct.size() - 1) > 1e-10) distinct.add(clipped);
        }
        var intervals = new ArrayList<double[]>();
        for (int index = 0; index < distinct.size(); index++) {
            double point = distinct.get(index);
            if (containsPoint(interpolate(start, end, point), region, work)) appendInterval(intervals, point, point);
            if (index + 1 < distinct.size()) {
                double next = distinct.get(index + 1);
                if (next > point && containsPoint(interpolate(start, end, (point + next) / 2.0), region, work)) {
                    appendInterval(intervals, point, next);
                }
            }
        }
        return intervals;
    }

    private static MetricPoint atX(MetricPoint start, MetricPoint end, double x) {
        if (start.xMeters() == end.xMeters()) return end;
        double fraction = (x - start.xMeters()) / (end.xMeters() - start.xMeters());
        return interpolate(start, end, fraction);
    }

    private static void appendInterval(List<double[]> intervals, double start, double end) {
        if (!intervals.isEmpty() && start <= intervals.get(intervals.size() - 1)[1] + 1e-10) {
            intervals.get(intervals.size() - 1)[1] = Math.max(end, intervals.get(intervals.size() - 1)[1]);
        } else {
            intervals.add(new double[] {start, end});
        }
    }

    private static void addIntersectionParameters(MetricPoint a, MetricPoint b, MetricPoint c,
            MetricPoint d, List<Double> events) {
        double rx = b.xMeters() - a.xMeters(), ry = b.yMeters() - a.yMeters();
        double sx = d.xMeters() - c.xMeters(), sy = d.yMeters() - c.yMeters();
        double denominator = rx * sy - ry * sx;
        double qx = c.xMeters() - a.xMeters(), qy = c.yMeters() - a.yMeters();
        if (Math.abs(denominator) > 1e-12) {
            double t = (qx * sy - qy * sx) / denominator;
            double u = (qx * ry - qy * rx) / denominator;
            if (t >= -1e-10 && t <= 1.0 + 1e-10 && u >= -1e-10 && u <= 1.0 + 1e-10) events.add(t);
        } else if (Math.abs(qx * ry - qy * rx) <= 1e-10) {
            addProjectedParameter(a, b, c, events);
            addProjectedParameter(a, b, d, events);
        }
    }

    private static void addProjectedParameter(MetricPoint start, MetricPoint end, MetricPoint point,
            List<Double> events) {
        double dx = end.xMeters() - start.xMeters(), dy = end.yMeters() - start.yMeters();
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0.0 ? 0.0
                : ((point.xMeters() - start.xMeters()) * dx + (point.yMeters() - start.yMeters()) * dy) / lengthSquared;
        if (t >= -1e-10 && t <= 1.0 + 1e-10) events.add(t);
    }

    private static double distance(MetricPoint point, MetricPoint start, MetricPoint end) {
        double dx = end.xMeters() - start.xMeters(), dy = end.yMeters() - start.yMeters();
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0.0) return point.distanceTo(start);
        double projection = ((point.xMeters() - start.xMeters()) * dx + (point.yMeters() - start.yMeters()) * dy) / lengthSquared;
        double t = Math.max(0.0, Math.min(1.0, projection));
        return point.distanceTo(new MetricPoint(start.xMeters() + t * dx, start.yMeters() + t * dy));
    }

    private static MetricRegion tube(List<MetricPoint> curve, double pitch, Work work) {
        var polygons = new ArrayList<List<MetricPoint>>(curve.size() - 1);
        for (int index = 1; index < curve.size(); index++) {
            work.charge(5);
            var left = curve.get(index - 1); var right = curve.get(index);
            polygons.add(List.of(new MetricPoint(left.xMeters(), left.yMeters() - pitch),
                    new MetricPoint(right.xMeters(), right.yMeters() - pitch),
                    new MetricPoint(right.xMeters(), right.yMeters() + pitch),
                    new MetricPoint(left.xMeters(), left.yMeters() + pitch)));
        }
        work.step();
        return new MetricRegion(polygons);
    }

    private static double yAt(List<MetricPoint> curve, double x, Work work) {
        for (int index = 1; index < curve.size(); index++) {
            work.step();
            var left = curve.get(index - 1); var right = curve.get(index);
            if (x <= right.xMeters()) {
                double fraction = (x - left.xMeters()) / (right.xMeters() - left.xMeters());
                return left.yMeters() + fraction * (right.yMeters() - left.yMeters());
            }
        }
        throw new IllegalArgumentException("Branch does not cover the interior endpoint");
    }

    private static MetricPoint[] clipX(MetricPoint start, MetricPoint end, double minimumX, double maximumX) {
        double dx = end.xMeters() - start.xMeters();
        if (dx == 0.0) return start.xMeters() >= minimumX && start.xMeters() <= maximumX
                ? new MetricPoint[] {start, end} : null;
        double first = (minimumX - start.xMeters()) / dx;
        double last = (maximumX - start.xMeters()) / dx;
        double low = Math.max(0.0, Math.min(first, last));
        double high = Math.min(1.0, Math.max(first, last));
        if (low > high) return null;
        return new MetricPoint[] {interpolate(start, end, low), interpolate(start, end, high)};
    }

    private static MetricPoint interpolate(MetricPoint start, MetricPoint end, double fraction) {
        return new MetricPoint(start.xMeters() + fraction * (end.xMeters() - start.xMeters()),
                start.yMeters() + fraction * (end.yMeters() - start.yMeters()));
    }

    private static boolean monotonicX(List<MetricPoint> path, Work work) {
        for (int index = 1; index < path.size(); index++) {
            work.step();
            if (path.get(index).xMeters() < path.get(index - 1).xMeters()) return false;
        }
        return true;
    }

    private static boolean segmentsIntersect(MetricPoint a, MetricPoint b, MetricPoint c, MetricPoint d) {
        int abC = orientation(a, b, c), abD = orientation(a, b, d);
        int cdA = orientation(c, d, a), cdB = orientation(c, d, b);
        return (abC == 0 && onSegment(a, b, c)) || (abD == 0 && onSegment(a, b, d))
                || (cdA == 0 && onSegment(c, d, a)) || (cdB == 0 && onSegment(c, d, b))
                || abC != 0 && abD != 0 && abC != abD && cdA != 0 && cdB != 0 && cdA != cdB;
    }

    private static int orientation(MetricPoint a, MetricPoint b, MetricPoint p) {
        double value = cross(a, b, p);
        double scale = Math.abs(b.xMeters() - a.xMeters()) + Math.abs(b.yMeters() - a.yMeters()) + 1.0;
        return Math.abs(value) <= 1e-10 * scale ? 0 : value < 0.0 ? -1 : 1;
    }

    private static double cross(MetricPoint a, MetricPoint b, MetricPoint p) {
        return (b.xMeters() - a.xMeters()) * (p.yMeters() - a.yMeters())
                - (b.yMeters() - a.yMeters()) * (p.xMeters() - a.xMeters());
    }

    private static boolean onSegment(MetricPoint a, MetricPoint b, MetricPoint p) {
        return p.xMeters() >= Math.min(a.xMeters(), b.xMeters()) - 1e-10
                && p.xMeters() <= Math.max(a.xMeters(), b.xMeters()) + 1e-10
                && p.yMeters() >= Math.min(a.yMeters(), b.yMeters()) - 1e-10
                && p.yMeters() <= Math.max(a.yMeters(), b.yMeters()) + 1e-10;
    }

    private static boolean intersectsSegment(MetricPoint start, MetricPoint end, MetricRegion region, Work work) {
        if (containsPoint(start, region, work) || containsPoint(end, region, work)) return true;
        for (var polygon : region.polygons()) {
            for (int edge = 0; edge < polygon.size(); edge++) {
                work.step();
                if (segmentsIntersect(start, end, polygon.get(edge), polygon.get((edge + 1) % polygon.size()))) return true;
            }
        }
        return false;
    }

    private static boolean containsPoint(MetricPoint point, MetricRegion region, Work work) {
        for (var polygon : region.polygons()) {
            boolean inside = false;
            for (int edge = 0; edge < polygon.size(); edge++) {
                work.step();
                var start = polygon.get(edge); var end = polygon.get((edge + 1) % polygon.size());
                if (orientation(start, end, point) == 0 && onSegment(start, end, point)) return true;
                if ((start.yMeters() > point.yMeters()) != (end.yMeters() > point.yMeters())) {
                    double crossingX = start.xMeters() + (point.yMeters() - start.yMeters())
                            * (end.xMeters() - start.xMeters()) / (end.yMeters() - start.yMeters());
                    if (point.xMeters() < crossingX) inside = !inside;
                }
            }
            if (inside) return true;
        }
        return false;
    }

    private static Check check(Reason reason, boolean pass, int count) {
        return new Check(reason, pass ? BIdentificationAcceptance.Verdict.PASS : BIdentificationAcceptance.Verdict.FAIL, count);
    }

    private static Checks unavailable() {
        return new Checks(List.of(Reason.EXACT_ANCHORS, Reason.ONE_BRANCH_FULL_INTERIOR,
                Reason.ORDERED_APPROACH_DEPARTURE, Reason.WHOLE_SEGMENT_CONTAINMENT).stream()
                .map(reason -> new Check(reason, BIdentificationAcceptance.Verdict.UNAVAILABLE, 0)).toList());
    }

    private static Checks failed(Object... values) {
        var checks = new ArrayList<Check>();
        for (int index = 0; index < values.length; index += 2) {
            checks.add(new Check((Reason) values[index], BIdentificationAcceptance.Verdict.FAIL, (int) values[index + 1]));
        }
        return new Checks(checks);
    }

    private static final class Work {
        private final CancellationProbe cancellation;
        private long count;
        Work(CancellationProbe cancellation) { this.cancellation = cancellation; }
        void step() { charge(1); }
        void charge(long amount) {
            cancellation.checkpoint();
            if (amount < 0 || amount > MAX_WORK - count) throw new IllegalArgumentException("Full-route oracle work limit");
            count += amount;
        }
    }
}
