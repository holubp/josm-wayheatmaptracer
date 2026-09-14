package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.image;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.PriorityQueue;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/**
 * Deterministic bounded A* over a 16-vector orientation-lifted image graph.
 *
 * <p>The solver is deliberately independent of live primitives and image acquisition. The supplied
 * immutable evidence frame bounds every position and edge; a later wider attempt is a separate input.</p>
 */
public final class DirectionalImageSearch {
    private static final int[][] DIRECTIONS = directions();
    private static final double LENGTH_DENSITY = 0.05;
    private static final double HEADING_WEIGHT = 0.25;
    private static final int HARD_RAW_ALTERNATIVES = 32;
    private static final int HARD_DISTINCT_ALTERNATIVES = 8;

    /** Runs a search with deterministic replay cancellation behavior. */
    public DirectionalImageSearchResult solve(DirectionalImageSearchProblem problem) {
        return solve(problem, CancellationProbe.NONE);
    }

    /** Runs a pure bounded search, polling the owner probe at most every 1024 transitions. */
    public DirectionalImageSearchResult solve(DirectionalImageSearchProblem problem, CancellationProbe cancellation) {
        if (problem == null || cancellation == null) {
            throw new IllegalArgumentException("Directional image search requires immutable input and cancellation");
        }
        cancellation.checkpoint();
        SearchContext context = new SearchContext(problem, cancellation);
        List<DirectionalImagePath> raw = new ArrayList<>();
        PathResult first = shortest(context, Set.of());
        if (first.limit) {
            return result(DirectionalImageSearchResult.Status.RESOURCE_LIMIT, List.of(), true, context,
                "state or transition budget exhausted");
        }
        if (first.path == null) {
            return result(DirectionalImageSearchResult.Status.NO_ROUTE, List.of(), false, context,
                "no route inside the immutable decision region");
        }
        raw.add(first.path);
        int rawLimit = Math.min(HARD_RAW_ALTERNATIVES, problem.budgets().maximumRawAlternatives());
        int probes = Math.min(rawLimit - 1, first.edgeKeys.size());
        for (int probe = 1; probe <= probes; probe++) {
            int edgeIndex = probe * first.edgeKeys.size() / (probes + 1);
            PathResult deviation = shortest(context, Set.of(first.edgeKeys.get(edgeIndex)));
            if (deviation.limit) {
                return result(DirectionalImageSearchResult.Status.RESOURCE_LIMIT, raw, true, context,
                    "state or transition budget exhausted during bounded alternatives");
            }
            if (deviation.path != null && raw.stream().noneMatch(path -> sameGeometry(path, deviation.path))) {
                raw.add(deviation.path);
            }
        }
        List<DirectionalImagePath> distinct = new ArrayList<>();
        for (DirectionalImagePath path : raw) {
            if (distinct.stream().noneMatch(existing -> !geometricallyDistinct(existing, path, context.pitch))) {
                distinct.add(path);
            }
            if (distinct.size() == Math.min(HARD_DISTINCT_ALTERNATIVES,
                problem.budgets().maximumDistinctAlternatives())) {
                break;
            }
        }
        boolean truncated = first.edgeKeys.size() > probes;
        DirectionalImageSearchResult.Status status = distinct.size() > 1
            ? DirectionalImageSearchResult.Status.AMBIGUOUS : DirectionalImageSearchResult.Status.COMPLETE;
        return result(status, distinct, truncated, context, truncated
            ? "bounded image alternatives truncated" : "bounded image search complete");
    }

    private static DirectionalImageSearchResult result(DirectionalImageSearchResult.Status status,
        List<DirectionalImagePath> paths, boolean truncated, SearchContext context, String explanation) {
        return new DirectionalImageSearchResult(status, paths, truncated, context.states, context.transitions,
            context.problem.scope() == DirectionalImageSearchProblem.Scope.WIDER_DISCOVERY, 0, explanation);
    }

    private static PathResult shortest(SearchContext context, Set<String> banned) {
        Node start = Node.anchor(0, context.anchors.get(0));
        State initial = new State(start, 0, -1);
        PriorityQueue<Entry> queue = new PriorityQueue<>(ENTRY_ORDER);
        Map<State, Double> best = new HashMap<>();
        queue.add(new Entry(initial, 0.0, heuristic(context, start, 0), null, 0L, List.of()));
        best.put(initial, 0.0);
        long serial = 1L;
        while (!queue.isEmpty()) {
            Entry entry = queue.remove();
            if (entry.g > best.getOrDefault(entry.state, Double.POSITIVE_INFINITY) + 1e-12) {
                continue;
            }
            context.states++;
            if (context.states > context.problem.budgets().maximumPairVisits()) {
                return PathResult.resourceLimit();
            }
            if (entry.state.node.anchorIndex == context.anchors.size() - 1) {
                DirectionalImagePath path = toPath(entry);
                if (!DirectionalImageGeometry.hasSelfIntersection(path.points())) {
                    return PathResult.path(path, entry.edgeKeys);
                }
                continue;
            }
            for (Transition transition : transitions(context, entry.state)) {
                context.transitions++;
                if (context.transitions > context.problem.budgets().maximumTransitions()) {
                    return PathResult.resourceLimit();
                }
                if ((context.transitions & 1023L) == 0L) {
                    context.cancellation.checkpoint();
                }
                String edgeKey = edgeKey(entry.state.node, transition.node);
                if (banned.contains(edgeKey) || !admissibleEdge(context, entry.state.node.point, transition.node.point)) {
                    continue;
                }
                double edgeCost = edgeCost(context, entry.state, transition);
                if (!Double.isFinite(edgeCost)) {
                    continue;
                }
                State next = new State(transition.node, transition.phase, transition.heading);
                double nextG = entry.g + edgeCost;
                if (nextG + 1e-12 >= best.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    continue;
                }
                best.put(next, nextG);
                List<String> edgeKeys = new ArrayList<>(entry.edgeKeys);
                edgeKeys.add(edgeKey);
                double f = nextG + heuristic(context, next.node, next.phase);
                queue.add(new Entry(next, nextG, f, entry, serial++, List.copyOf(edgeKeys)));
            }
        }
        return PathResult.none();
    }

    private static List<Transition> transitions(SearchContext context, State state) {
        List<Transition> result = new ArrayList<>();
        if (state.node.anchorIndex >= 0) {
            if (state.node.anchorIndex == context.anchors.size() - 1) {
                return result;
            }
            for (int x = lower(state.node.point.xMeters() - context.maxStep, context.pitch);
                 x <= upper(state.node.point.xMeters() + context.maxStep, context.pitch); x++) {
                for (int y = lower(state.node.point.yMeters() - context.maxStep, context.pitch);
                     y <= upper(state.node.point.yMeters() + context.maxStep, context.pitch); y++) {
                    Node target = Node.grid(x, y, context.pitch);
                    double distance = state.node.point.distanceTo(target.point);
                    if (distance > 1e-9 && distance <= context.maxStep + 1e-9) {
                        result.add(new Transition(target, state.phase, nearestHeading(state.node.point, target.point)));
                    }
                }
            }
            return result;
        }
        for (int heading = 0; heading < DIRECTIONS.length; heading++) {
            int[] vector = DIRECTIONS[heading];
            result.add(new Transition(Node.grid(state.node.x + vector[0], state.node.y + vector[1], context.pitch),
                state.phase, heading));
        }
        int nextAnchor = state.phase + 1;
        MetricPoint anchor = context.anchors.get(nextAnchor);
        if (state.node.point.distanceTo(anchor) <= context.maxStep + 1e-9) {
            result.add(new Transition(Node.anchor(nextAnchor, anchor), nextAnchor,
                nearestHeading(state.node.point, anchor)));
        }
        return result;
    }

    private static boolean admissibleEdge(SearchContext context, MetricPoint start, MetricPoint end) {
        if (!DirectionalImageGeometry.segmentAuthorized(context.problem.evidence().decisionRegion(), start, end)) {
            return false;
        }
        int samples = Math.max(1, (int) Math.ceil(start.distanceTo(end) / (context.pitch / 2.0)));
        for (int sample = 0; sample <= samples; sample++) {
            double fraction = (double) sample / samples;
            MetricPoint point = interpolate(start, end, fraction);
            OptionalDouble value = sample(context, point);
            if (value.isEmpty() || value.getAsDouble() <= 1e-3) {
                return false;
            }
        }
        return true;
    }

    private static double edgeCost(SearchContext context, State state, Transition transition) {
        MetricPoint start = state.node.point;
        MetricPoint end = transition.node.point;
        double length = start.distanceTo(end);
        int samples = Math.max(1, (int) Math.ceil(length / (context.pitch / 2.0)));
        double presence = 0.0;
        for (int sample = 0; sample <= samples; sample++) {
            OptionalDouble value = sample(context, interpolate(start, end, (double) sample / samples));
            if (value.isEmpty()) {
                return Double.POSITIVE_INFINITY;
            }
            presence += -Math.log(Math.max(1e-6, value.getAsDouble()));
        }
        double heading = state.heading < 0 ? 0.0 : HEADING_WEIGHT * (1.0
            - Math.cos(bearing(state.heading) - bearing(transition.heading)));
        return length * (LENGTH_DENSITY + presence / (samples + 1.0)) + heading;
    }

    private static OptionalDouble sample(SearchContext context, MetricPoint point) {
        RasterPoint raster = context.problem.evidence().transform().metricToPixelCenter(point);
        if (raster.x() < 0.0 || raster.y() < 0.0 || raster.x() > context.field.width() - 1.0
            || raster.y() > context.field.height() - 1.0
            || !context.field.supportsInterpolationAt(raster.x(), raster.y())) {
            return OptionalDouble.empty();
        }
        int x = (int) Math.round(raster.x());
        int y = (int) Math.round(raster.y());
        return context.field.sample(x, y);
    }

    private static double heuristic(SearchContext context, Node node, int phase) {
        if (phase >= context.anchors.size() - 1 || context.problem.zeroHeuristic()) {
            return 0.0;
        }
        double remaining = node.point.distanceTo(context.anchors.get(phase + 1));
        for (int index = phase + 1; index + 1 < context.anchors.size(); index++) {
            remaining += context.anchors.get(index).distanceTo(context.anchors.get(index + 1));
        }
        return remaining * LENGTH_DENSITY;
    }

    private static DirectionalImagePath toPath(Entry entry) {
        List<MetricPoint> reversePoints = new ArrayList<>();
        List<Integer> reverseHeadings = new ArrayList<>();
        for (Entry current = entry; current != null; current = current.previous) {
            reversePoints.add(current.state.node.point);
            if (current.previous != null) {
                reverseHeadings.add(current.state.heading);
            }
        }
        java.util.Collections.reverse(reversePoints);
        java.util.Collections.reverse(reverseHeadings);
        List<Double> headings = reverseHeadings.stream().map(DirectionalImageSearch::bearing).toList();
        return new DirectionalImagePath(reversePoints, headings, entry.g, branchSignature(reversePoints));
    }

    private static String branchSignature(List<MetricPoint> points) {
        MetricPoint first = points.get(0);
        MetricPoint last = points.get(points.size() - 1);
        double dx = last.xMeters() - first.xMeters();
        double dy = last.yMeters() - first.yMeters();
        double maximum = 0.0;
        for (MetricPoint point : points) {
            maximum += (point.xMeters() - first.xMeters()) * -dy + (point.yMeters() - first.yMeters()) * dx;
        }
        return maximum > 1e-9 ? "left" : maximum < -1e-9 ? "right" : "center";
    }

    private static boolean sameGeometry(DirectionalImagePath left, DirectionalImagePath right) {
        return left.points().equals(right.points());
    }

    private static boolean geometricallyDistinct(DirectionalImagePath left, DirectionalImagePath right, double pitch) {
        for (int index = 1; index < 16; index++) {
            MetricPoint a = pointAtFraction(left.points(), index / 16.0);
            MetricPoint b = pointAtFraction(right.points(), index / 16.0);
            if (a.distanceTo(b) > Math.max(1.0, pitch)) {
                return true;
            }
        }
        return false;
    }

    private static MetricPoint pointAtFraction(List<MetricPoint> points, double fraction) {
        double length = 0.0;
        for (int index = 1; index < points.size(); index++) length += points.get(index - 1).distanceTo(points.get(index));
        double target = fraction * length;
        for (int index = 1; index < points.size(); index++) {
            double span = points.get(index - 1).distanceTo(points.get(index));
            if (target <= span || index == points.size() - 1) return interpolate(points.get(index - 1), points.get(index), target / span);
            target -= span;
        }
        return points.get(points.size() - 1);
    }

    private static MetricPoint interpolate(MetricPoint a, MetricPoint b, double fraction) {
        return new MetricPoint(a.xMeters() + fraction * (b.xMeters() - a.xMeters()),
            a.yMeters() + fraction * (b.yMeters() - a.yMeters()));
    }

    private static int nearestHeading(MetricPoint start, MetricPoint end) {
        double angle = Math.atan2(end.yMeters() - start.yMeters(), end.xMeters() - start.xMeters());
        int best = 0;
        for (int index = 1; index < DIRECTIONS.length; index++) {
            if (Math.abs(Math.atan2(Math.sin(angle - bearing(index)), Math.cos(angle - bearing(index))))
                < Math.abs(Math.atan2(Math.sin(angle - bearing(best)), Math.cos(angle - bearing(best))))) best = index;
        }
        return best;
    }

    private static double bearing(int heading) { return Math.atan2(DIRECTIONS[heading][1], DIRECTIONS[heading][0]); }
    private static int lower(double value, double pitch) { return (int) Math.ceil(value / pitch - 1e-9); }
    private static int upper(double value, double pitch) { return (int) Math.floor(value / pitch + 1e-9); }
    private static String edgeKey(Node start, Node end) { return start + ">" + end; }

    private static int[][] directions() {
        List<int[]> result = new ArrayList<>();
        int[][] seeds = {{1, 0}, {1, 1}, {2, 1}, {1, 2}};
        for (int[] seed : seeds) for (int swap = 0; swap < 2; swap++) for (int sx : new int[] {-1, 1})
            for (int sy : new int[] {-1, 1}) {
                int x = (swap == 0 ? seed[0] : seed[1]) * sx;
                int y = (swap == 0 ? seed[1] : seed[0]) * sy;
                if (result.stream().noneMatch(vector -> vector[0] == x && vector[1] == y)) result.add(new int[] {x, y});
            }
        return result.toArray(int[][]::new);
    }

    private static final Comparator<Entry> ENTRY_ORDER = Comparator.comparingDouble((Entry entry) -> entry.f)
        .thenComparingDouble(entry -> entry.g).thenComparingInt(entry -> entry.state.phase)
        .thenComparingInt(entry -> entry.state.node.y).thenComparingInt(entry -> entry.state.node.x)
        .thenComparingInt(entry -> entry.state.heading).thenComparingLong(entry -> entry.serial);

    private static final class SearchContext {
        private final DirectionalImageSearchProblem problem;
        private final CancellationProbe cancellation;
        private final ScalarEvidenceField field;
        private final List<MetricPoint> anchors;
        private final double pitch;
        private final double maxStep;
        private long states;
        private long transitions;

        SearchContext(DirectionalImageSearchProblem problem, CancellationProbe cancellation) {
            this.problem = problem;
            this.cancellation = cancellation;
            this.field = problem.evidence().fields().get(problem.fieldName());
            this.anchors = problem.orderedAnchors();
            this.pitch = problem.gridPitchMeters();
            this.maxStep = Math.sqrt(5.0) * pitch;
        }
    }
    private record Node(int x, int y, int anchorIndex, MetricPoint point) {
        static Node grid(int x, int y, double pitch) { return new Node(x, y, -1, new MetricPoint(x * pitch, y * pitch)); }
        static Node anchor(int anchorIndex, MetricPoint point) { return new Node(Integer.MIN_VALUE + anchorIndex,
            Integer.MIN_VALUE + anchorIndex, anchorIndex, point); }
    }
    private record State(Node node, int phase, int heading) { }
    private record Transition(Node node, int phase, int heading) { }
    private record Entry(State state, double g, double f, Entry previous, long serial, List<String> edgeKeys) { }
    private record PathResult(DirectionalImagePath path, List<String> edgeKeys, boolean limit) {
        static PathResult path(DirectionalImagePath path, List<String> edgeKeys) { return new PathResult(path, edgeKeys, false); }
        static PathResult none() { return new PathResult(null, List.of(), false); }
        static PathResult resourceLimit() { return new PathResult(null, List.of(), true); }
    }
}
