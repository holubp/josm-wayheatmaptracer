package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Independent truth-side geometry checks for the fixed synthetic thin-strand recipes. */
final class BThinStrandAcceptance {
    static final String ORACLE_ID = "THIN_STRAND_WHOLE_ROUTE_NATIVE_TUBE_V1";
    private static final double LEFT_X = -8.0;
    private static final double RIGHT_X = 52.0;
    private static final double ANCHOR_Y = 37.0;
    private static final double TUBE_RADIUS_METERS = 1.0;
    private static final double MERGE_LEFT_X = 21.0;
    private static final double MERGE_RIGHT_X = 23.0;
    private static final int MAX_POINTS = 4096;
    private static final int MAX_TRACKS = 4;
    private static final int NATIVE_COLUMNS = 81;
    private static final long MAX_WORK = 8_000_000;

    enum Verdict { PASS, FAIL, UNAVAILABLE }
    enum Reason { EXACT_ANCHORS, NONDECREASING_X, WHOLE_PATH_TUBE, BRANCH_CONTINUITY, SHARP_APEX, OVERLAP_REFERENCE }

    record Check(Reason reason, Verdict verdict, int count) { }

    record Checks(String expectedRouteKind, List<Check> checks) {
        Checks { checks = List.copyOf(checks); }

        boolean passed() {
            List<Reason> required = requiredReasons(expectedRouteKind);
            if (required.isEmpty() || checks.size() != required.size()) return false;
            var seen = EnumSet.noneOf(Reason.class);
            for (var check : checks) {
                if (check == null || check.reason() == null || check.verdict() != Verdict.PASS
                        || !seen.add(check.reason())) return false;
            }
            return seen.size() == required.size() && seen.containsAll(required);
        }
    }

    record Result(String fixtureId, String expectedRouteKind, List<Check> checks) {
        Result { checks = List.copyOf(checks); }
        boolean passed() { return new Checks(expectedRouteKind, checks).passed(); }
    }

    private BThinStrandAcceptance() { }

    static Result evaluate(BThinStrandFixtures.Fixture fixture, List<MetricPoint> path,
            double nativePitchMeters, CancellationProbe cancellation, AttemptMemoryLedger.Owner owner) {
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(owner, "owner");
        try (var transaction = owner.child("thin-strand-acceptance-result")) {
            var resultReservation = transaction.reserve(1024);
            var scratch = transaction.child("thin-strand-acceptance-scratch");
            try {
                Checks checks = evaluateChecks(fixture, path, nativePitchMeters, cancellation, scratch);
                Result result = new Result(fixture == null ? null : fixture.id(), checks.expectedRouteKind(), checks.checks());
                resultReservation.adopt(result);
                scratch.close();
                transaction.transferTo(owner);
                return result;
            } finally {
                scratch.close();
                resultReservation.close();
            }
        }
    }

    private static Checks evaluateChecks(BThinStrandFixtures.Fixture fixture, List<MetricPoint> input,
            double pitch, CancellationProbe cancellation, AttemptMemoryLedger.Owner scratch) {
        cancellation.checkpoint();
        String kind = fixture == null ? null : fixture.expectedRouteKind();
        if (fixture == null || !Double.isFinite(pitch) || pitch <= 0.0
                || Double.compare(pitch, fixture.pitchMeters()) != 0 || input == null
                || input.size() < 2 || input.size() > MAX_POINTS) return unavailable(kind);
        if (fixture.width() != NATIVE_COLUMNS || fixture.height() != NATIVE_COLUMNS
                || Double.compare(fixture.pitchMeters(), 1.0) != 0
                || fixture.strands().size() > MAX_TRACKS) return unavailable(kind);

        int pointCount = input.size();
        int strandCount = fixture.strands().size() + (kind.equals("strong-course") ? 1 : 0);
        if (strandCount == 0 || strandCount > MAX_TRACKS) return unavailable(kind);
        long bytes = Math.addExact(4096L, Math.addExact(Math.multiplyExact(32L, pointCount),
                Math.multiplyExact(Math.multiplyExact((long) strandCount, NATIVE_COLUMNS), 8L)));
        var workspace = scratch.reserve(bytes);
        try {
            cancellation.checkpoint();
            var path = new ArrayList<MetricPoint>(input);
            var work = new Work(cancellation);
            boolean finite = true;
            for (var point : path) {
                work.step();
                finite &= point != null && Double.isFinite(point.xMeters()) && Double.isFinite(point.yMeters());
            }
            if (!finite) return unavailable(kind);

            boolean exact = path.get(0).equals(new MetricPoint(LEFT_X, ANCHOR_Y))
                    && path.get(path.size() - 1).equals(new MetricPoint(RIGHT_X, ANCHOR_Y));
            boolean increasing = nondecreasingX(path, work);
            if (!increasing) {
                return checks(kind, check(Reason.EXACT_ANCHORS, exact, 2),
                        check(Reason.NONDECREASING_X, false, Math.max(1, path.size() - 1)),
                        check(Reason.WHOLE_PATH_TUBE, false, 0), check(Reason.BRANCH_CONTINUITY, false, 0));
            }

            var tracks = copyTracks(fixture, kind, work, cancellation);
            boolean whole;
            boolean continuous;
            int wholeCount;
            int continuousCount;
            switch (kind) {
                case "single-coherent-strand" -> {
                    wholeCount = anyPerEdgeInTrack(path, tracks, LEFT_X, RIGHT_X, work);
                    continuous = anyWholeTrack(path, tracks, LEFT_X, RIGHT_X, work);
                    whole = wholeCount == path.size() - 1;
                    continuousCount = continuous ? 1 : 0;
                }
                case "allowed-actual-merge" -> {
                    wholeCount = mergePerEdge(path, tracks, work);
                    continuous = anyAllowedMergePair(path, tracks, work);
                    whole = wholeCount == path.size() - 1;
                    continuousCount = continuous ? 1 : 0;
                }
                case "strong-course" -> {
                    Track strong = tracks.stream().filter(track -> track.strong).findFirst().orElse(null);
                    if (strong == null) return unavailable(kind);
                    whole = containsPath(path, strong, LEFT_X, RIGHT_X, work);
                    wholeCount = whole ? path.size() - 1 : 0;
                    continuous = whole;
                    continuousCount = continuous ? 1 : 0;
                }
                case "sharp-course" -> {
                    Track sharp = tracks.size() == 1 ? tracks.get(0) : null;
                    if (sharp == null) return unavailable(kind);
                    whole = containsPath(path, sharp, LEFT_X, RIGHT_X, work);
                    wholeCount = whole ? path.size() - 1 : 0;
                    continuous = whole;
                    continuousCount = continuous ? 1 : 0;
                }
                case "LATENT_REFERENCE_ONE_METRE" -> {
                    whole = insideHorizontalBand(path, 36.0, 38.0, work);
                    wholeCount = whole ? path.size() - 1 : 0;
                    continuous = tracks.size() == 3;
                    continuousCount = continuous ? tracks.size() : 0;
                }
                default -> { return unavailable(kind); }
            }

            var output = new ArrayList<Check>(6);
            output.add(check(Reason.EXACT_ANCHORS, exact, 2));
            output.add(check(Reason.NONDECREASING_X, increasing, Math.max(1, path.size() - 1)));
            output.add(check(Reason.WHOLE_PATH_TUBE, whole, wholeCount));
            output.add(check(Reason.BRANCH_CONTINUITY, continuous, continuousCount));
            if (kind.equals("sharp-course")) {
                var apex = apex(path, work);
                output.add(check(Reason.SHARP_APEX,
                        apex.distance <= 1.0 && apex.crossingHeight >= 39.0, apex.crossings));
            } else if (kind.equals("LATENT_REFERENCE_ONE_METRE")) {
                boolean reference = insideHorizontalBand(path, 36.0, 38.0, work);
                output.add(check(Reason.OVERLAP_REFERENCE, reference, reference ? path.size() - 1 : 0));
            }
            work.finish();
            return new Checks(kind, output);
        } finally {
            workspace.close();
        }
    }

    private static List<Track> copyTracks(BThinStrandFixtures.Fixture fixture, String kind,
            Work work, CancellationProbe cancellation) {
        var tracks = new ArrayList<Track>(MAX_TRACKS);
        if (kind.equals("strong-course")) {
            var strong = fixture.strongCourse().orElse(null);
            if (strong == null) return List.of();
            tracks.add(new Track(strong.centerlineY(), true));
        }
        for (var strand : fixture.strands()) {
            cancellation.checkpoint();
            tracks.add(new Track(strand.centerlineY(), false));
        }
        if (tracks.isEmpty() || tracks.size() > MAX_TRACKS) return List.of();
        for (var track : tracks) {
            if (track.centerY.length != NATIVE_COLUMNS) return List.of();
            for (double y : track.centerY) { work.step(); if (!Double.isFinite(y)) return List.of(); }
        }
        return List.copyOf(tracks);
    }

    private static int anyPerEdgeInTrack(List<MetricPoint> path, List<Track> tracks,
            double fromX, double toX, Work work) {
        int passed = 0;
        for (int edge = 1; edge < path.size(); edge++) {
            work.step();
            boolean edgePassed = false;
            for (var track : tracks) {
                work.step();
                if (containsEdge(path.get(edge - 1), path.get(edge), track, fromX, toX, work)) {
                    edgePassed = true;
                    break;
                }
            }
            if (edgePassed) passed++;
        }
        return passed;
    }

    private static boolean anyWholeTrack(List<MetricPoint> path, List<Track> tracks,
            double fromX, double toX, Work work) {
        for (var track : tracks) {
            work.step();
            if (containsPath(path, track, fromX, toX, work)) return true;
        }
        return false;
    }

    private static int mergePerEdge(List<MetricPoint> path, List<Track> tracks, Work work) {
        int passed = 0;
        for (int edge = 1; edge < path.size(); edge++) {
            work.step();
            boolean edgePassed = false;
            for (var left : tracks) {
                for (var right : tracks) {
                    work.step();
                    if (mergeEdge(path.get(edge - 1), path.get(edge), tracks, left, right, work)) {
                        edgePassed = true;
                        break;
                    }
                }
                if (edgePassed) break;
            }
            if (edgePassed) passed++;
        }
        return passed;
    }

    private static boolean anyAllowedMergePair(List<MetricPoint> path, List<Track> tracks, Work work) {
        for (var left : tracks) for (var right : tracks) {
            work.step();
            boolean accepted = true;
            for (int edge = 1; edge < path.size(); edge++) {
                work.step();
                if (!mergeEdge(path.get(edge - 1), path.get(edge), tracks, left, right, work)) {
                    accepted = false;
                    break;
                }
            }
            if (accepted) return true;
        }
        return false;
    }

    /** Tests every truth piece, including integer native columns and both merge boundaries. */
    private static boolean containsEdge(MetricPoint start, MetricPoint end, Track track,
            double fromX, double toX, Work work) {
        if (start.xMeters() == end.xMeters()) {
            work.step();
            return inTube(start, track, fromX, toX, work) && inTube(end, track, fromX, toX, work);
        }
        double previousX = start.xMeters();
        if (!inTube(atX(start, end, previousX), track, fromX, toX, work)) return false;
        int firstInteger = (int) Math.floor(previousX) + 1;
        int lastInteger = (int) Math.ceil(end.xMeters()) - 1;
        for (int x = firstInteger; x <= lastInteger; x++) {
            work.step();
            if (!inTube(atX(start, end, x), track, fromX, toX, work)) return false;
            previousX = x;
        }
        work.step();
        return inTube(atX(start, end, end.xMeters()), track, fromX, toX, work);
    }

    private static boolean mergeEdge(MetricPoint start, MetricPoint end, List<Track> tracks,
            Track fixedLeft, Track fixedRight, Work work) {
        if (start.xMeters() == end.xMeters()) {
            double x = start.xMeters();
            Track selected = x < MERGE_LEFT_X ? fixedLeft : x > MERGE_RIGHT_X ? fixedRight : tracks.get(1);
            if (selected != null) return inTube(start, selected, LEFT_X, RIGHT_X, work)
                    && inTube(end, selected, LEFT_X, RIGHT_X, work);
            return anyAtX(start, end, tracks, x, work);
        }
        if (!mergePointAllowed(atX(start, end, start.xMeters()), start.xMeters(), tracks, fixedLeft, fixedRight, work))
            return false;
        int firstInteger = (int) Math.floor(start.xMeters()) + 1;
        int lastInteger = (int) Math.ceil(end.xMeters()) - 1;
        for (int x = firstInteger; x <= lastInteger; x++) {
            work.step();
            if (!mergePointAllowed(atX(start, end, x), x, tracks, fixedLeft, fixedRight, work)) return false;
        }
        return mergePointAllowed(atX(start, end, end.xMeters()), end.xMeters(), tracks, fixedLeft, fixedRight, work);
    }

    private static boolean mergePointAllowed(MetricPoint point, double x, List<Track> tracks,
            Track fixedLeft, Track fixedRight, Work work) {
        if (x < MERGE_LEFT_X) {
            if (fixedLeft != null) return inTube(point, fixedLeft, LEFT_X, RIGHT_X, work);
            for (var track : tracks) { work.step(); if (inTube(point, track, LEFT_X, RIGHT_X, work)) return true; }
            return false;
        }
        if (x > MERGE_RIGHT_X) {
            if (fixedRight != null) return inTube(point, fixedRight, LEFT_X, RIGHT_X, work);
            for (var track : tracks) { work.step(); if (inTube(point, track, LEFT_X, RIGHT_X, work)) return true; }
            return false;
        }
        Track common = tracks.get(1);
        return inTube(point, common, LEFT_X, RIGHT_X, work);
    }

    private static boolean anyAtX(MetricPoint start, MetricPoint end, List<Track> tracks, double x, Work work) {
        for (var track : tracks) {
            work.step();
            if (inTube(start, track, LEFT_X, RIGHT_X, work) && inTube(end, track, LEFT_X, RIGHT_X, work)) return true;
        }
        return false;
    }

    private static boolean containsPath(List<MetricPoint> path, Track track,
            double fromX, double toX, Work work) {
        for (int edge = 1; edge < path.size(); edge++) {
            work.step();
            if (!containsEdge(path.get(edge - 1), path.get(edge), track, fromX, toX, work)) return false;
        }
        return true;
    }

    private static boolean inTube(MetricPoint point, Track track, double fromX, double toX, Work work) {
        work.step();
        if (point.xMeters() < fromX || point.xMeters() > toX) return false;
        return Math.abs(point.yMeters() - yAt(track, point.xMeters(), work)) <= Math.nextUp(TUBE_RADIUS_METERS);
    }

    private static double yAt(Track track, double x, Work work) {
        work.step();
        double column = x + 20.0;
        int lower = (int) Math.floor(column);
        if (lower < 0 || lower >= NATIVE_COLUMNS) return Double.NaN;
        if (lower == NATIVE_COLUMNS - 1 || column == lower) return track.centerY[lower];
        int upper = lower + 1;
        double fraction = column - lower;
        return track.centerY[lower] + fraction * (track.centerY[upper] - track.centerY[lower]);
    }

    private static MetricPoint atX(MetricPoint start, MetricPoint end, double x) {
        double fraction = (x - start.xMeters()) / (end.xMeters() - start.xMeters());
        if (start.xMeters() == end.xMeters()) fraction = 0.0;
        double y = start.yMeters() + fraction * (end.yMeters() - start.yMeters());
        return new MetricPoint(x, y);
    }

    private static boolean nondecreasingX(List<MetricPoint> path, Work work) {
        for (int index = 1; index < path.size(); index++) {
            work.step();
            if (path.get(index).xMeters() < path.get(index - 1).xMeters()) return false;
        }
        return true;
    }

    private static boolean insideHorizontalBand(List<MetricPoint> path, double minY, double maxY, Work work) {
        for (var point : path) {
            work.step();
            if (point.xMeters() < LEFT_X || point.xMeters() > RIGHT_X
                    || point.yMeters() < minY || point.yMeters() > maxY) return false;
        }
        return true;
    }

    private static Apex apex(List<MetricPoint> path, Work work) {
        double minimumDistance = Double.POSITIVE_INFINITY;
        double crossingHeight = Double.NEGATIVE_INFINITY;
        int crossings = 0;
        double px = 22.0, py = 40.0;
        for (int index = 1; index < path.size(); index++) {
            work.step();
            MetricPoint a = path.get(index - 1), b = path.get(index);
            minimumDistance = Math.min(minimumDistance, pointSegmentDistance(px, py, a, b));
            if (a.xMeters() == b.xMeters()) {
                if (a.xMeters() == px) {
                    crossings++;
                    crossingHeight = Math.max(crossingHeight, Math.max(a.yMeters(), b.yMeters()));
                }
            } else if (a.xMeters() <= px && b.xMeters() >= px) {
                crossings++;
                crossingHeight = Math.max(crossingHeight, atX(a, b, px).yMeters());
            }
        }
        return new Apex(minimumDistance, crossingHeight, crossings);
    }

    private static double pointSegmentDistance(double px, double py, MetricPoint a, MetricPoint b) {
        double dx = b.xMeters() - a.xMeters(), dy = b.yMeters() - a.yMeters();
        double length2 = dx * dx + dy * dy;
        double t = length2 == 0.0 ? 0.0 : ((px - a.xMeters()) * dx + (py - a.yMeters()) * dy) / length2;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.hypot(px - (a.xMeters() + t * dx), py - (a.yMeters() + t * dy));
    }

    private static Checks checks(String kind, Check exact, Check monotonic, Check whole, Check branch) {
        var output = new ArrayList<Check>(5);
        output.add(exact); output.add(monotonic); output.add(whole); output.add(branch);
        if (kind.equals("sharp-course")) output.add(check(Reason.SHARP_APEX, false, 0));
        else if (kind.equals("LATENT_REFERENCE_ONE_METRE")) output.add(check(Reason.OVERLAP_REFERENCE, false, 0));
        return new Checks(kind, output);
    }

    private static List<Reason> requiredReasons(String kind) {
        if (kind == null) return List.of();
        var base = new ArrayList<>(List.of(Reason.EXACT_ANCHORS, Reason.NONDECREASING_X,
                Reason.WHOLE_PATH_TUBE, Reason.BRANCH_CONTINUITY));
        if (kind.equals("sharp-course")) base.add(Reason.SHARP_APEX);
        else if (kind.equals("LATENT_REFERENCE_ONE_METRE")) base.add(Reason.OVERLAP_REFERENCE);
        else if (!kind.equals("single-coherent-strand") && !kind.equals("allowed-actual-merge")
                && !kind.equals("strong-course")) return List.of();
        return List.copyOf(base);
    }

    private static Checks unavailable(String kind) {
        var output = new ArrayList<Check>();
        for (var reason : requiredReasons(kind)) output.add(check(reason, Verdict.UNAVAILABLE, 0));
        if (output.isEmpty()) for (var reason : Reason.values()) output.add(check(reason, Verdict.UNAVAILABLE, 0));
        return new Checks(kind, output);
    }

    private static Check check(Reason reason, boolean passed, int count) {
        return check(reason, passed ? Verdict.PASS : Verdict.FAIL, count);
    }
    private static Check check(Reason reason, Verdict verdict, int count) { return new Check(reason, verdict, count); }

    private record Track(double[] centerY, boolean strong) { }
    private record Apex(double distance, double crossingHeight, int crossings) { }

    private static final class Work {
        private final CancellationProbe cancellation;
        private long units;
        Work(CancellationProbe cancellation) { this.cancellation = cancellation; }
        void step() { charge(1); }
        void charge(long amount) {
            cancellation.checkpoint();
            if (amount < 0 || amount > MAX_WORK - units) throw new IllegalArgumentException("Thin-strand oracle work limit");
            units += amount;
        }
        void finish() { cancellation.checkpoint(); }
    }
}
