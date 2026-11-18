package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.*;
import java.util.function.Consumer;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Certified R4 geometric choices at unobserved rows. Never changes observation evidence. */
public final class CorridorGapDomain {
    private static final long CACHE_LIMIT = 128L * 1024 * 1024;
    private CorridorGapDomain() { }

    /** Source authority invocations are admission work, separate from scored DP entries. */
    public record Work(long pointChecks, long edgeChecks, long rejectedPoints, long rejectedEdges,
            long addedStates, long retainedBytes, String outcome, List<Row> rows) {
        public Work { rows = List.copyOf(rows); }
        public long admissionUnits() { return Math.addExact(pointChecks, edgeChecks); }
    }
    /** Relative state coordinates and raw row identity; no absolute/private geometry. */
    public record Row(int originalRow, double historicalOffset, double envelopeMin, double envelopeMax,
            List<Double> addedOffsets, long rejectedPoints, String status) {
        public Row { addedOffsets = List.copyOf(addedOffsets); }
    }

    /** Sealed masks bind finalized ordered states and one frozen original invocation. */
    public static final class Plan {
        private final Object token;
        private final CorridorWorkingSchedule.BoundContext context;
        private final List<List<Double>> states;
        private final List<List<Double>> historical;
        private final boolean[][][] edges;
        private final Work work;
        private Plan(Object token, CorridorWorkingSchedule.BoundContext context, List<List<Double>> states,
                List<List<Double>> historical, boolean[][][] edges, Work work) {
            this.token = token; this.context = context; this.states = List.copyOf(states);
            this.historical = historical; this.edges = edges; this.work = work;
        }
        public Work work() { return work; }
        public boolean hasAdditions() { return work.addedStates() > 0; }
        List<List<Double>> states() { return states; }
        void requireBinding(CorridorCenterlineOptimizer.OriginalDomain domain) {
            if (domain.token() != token || domain.context() != context || domain.states() != states)
                throw new IllegalArgumentException("Gap domain token, ordered states or frozen context differs");
        }
        boolean allows(int rightRow, int leftIndex, int rightIndex) {
            if (rightRow <= 0 || rightRow >= states.size()) throw new IllegalArgumentException("Gap edge row differs");
            double left = states.get(rightRow - 1).get(leftIndex), right = states.get(rightRow).get(rightIndex);
            if (historical.get(rightRow - 1).contains(left) && historical.get(rightRow).contains(right)) return true;
            return edges[rightRow] != null && edges[rightRow][leftIndex][rightIndex];
        }
    }

    static Plan prepare(CorridorCenterlineOptimizer.OriginalDomain d, double radius, CancellationProbe cancellation,
            Consumer<Work> spent) {
        if (d.context() == null || !(radius > 0) || !Double.isFinite(radius))
            throw new IllegalArgumentException("Frozen gap authority and physical radius required");
        var context = d.context(); var profiles = d.frozenProfiles(); int n = profiles.size();
        double previous = -1;
        for (var p : profiles) {
            context.requireProfileFrame(p.source().samplingAnchor());
            double s = p.source().cumulativeGroundDistanceMeters();
            if (!Double.isFinite(s) || s <= previous) throw new IllegalArgumentException("Strict physical chainage required");
            previous = s;
        }
        // Inventory bounds are counted before retaining state/mask tables or invoking authority.
        long points = 0, pairs = 0, newPoints = 0, checks = 0; int prior = 0;
        for (int row = 0; row < n; row++) {
            cancellation.checkpoint();
            int extra = proposals(d, profiles, row, radius).size();
            int count = Math.addExact(d.states().get(row).size(), extra);
            if (count > d.budgets().maximumStatesPerProfile()) throw limit("Gap state admission cap");
            points = Math.addExact(points, count); newPoints = Math.addExact(newPoints, extra);
            if (row > 0) {
                long full = Math.multiplyExact((long) prior, count);
                long old = Math.multiplyExact((long) d.states().get(row - 1).size(), d.states().get(row).size());
                pairs = Math.addExact(pairs, full); checks = Math.addExact(checks, full - old);
            }
            prior = count;
        }
        long maskBytes = estimateMaskBytes(n, points, checks);
        long combined = Math.addExact(maskBytes, estimateMetricBytes(n, points, pairs));
        combined=Math.addExact(combined,d.observedPlan().map(p->p.work().retainedBytes()).orElse(0L));
        if (combined > CACHE_LIMIT || Math.addExact(Math.addExact(newPoints, checks), pairs) > d.budgets().maximumPairVisits())
            throw limit("Combined gap mask/metric cache or source/solver work cap");
        var rows = new ArrayList<Row>(); var states = new ArrayList<List<Double>>(n);
        long pointChecks = 0, edgeChecks = 0, rejectedPoints = 0, rejectedEdges = 0, additions = 0;
        spent.accept(new Work(0, 0, 0, 0, 0, maskBytes, "PREPARING", rows));
        for (int row = 0; row < n; row++) {
            var old = d.states().get(row); var expanded = new TreeSet<Double>(old); var accepted = new ArrayList<Double>();
            var candidates = proposals(d, profiles, row, radius); long refused = 0;
            double[] bounds = candidates.isEmpty() ? null : envelope(d, profiles, row);
            String status = candidates.isEmpty() ? null : persistenceBoundary(d, row) ? "CERTIFIED_COARSE_PERSISTENCE" : "CERTIFIED";
            if (!candidates.isEmpty()) rows.add(new Row(row, old.get(0), bounds[0], bounds[1], accepted, 0, status));
            for (double offset : candidates) {
                cancellation.checkpoint(); pointChecks++;
                spent.accept(new Work(pointChecks, edgeChecks, rejectedPoints, rejectedEdges, additions, maskBytes, "PREPARING", rows));
                var point = metric(profiles.get(row), offset, context);
                if (context.sourceAuthority().admits(point, point)) { expanded.add(offset); accepted.add(offset); additions++; }
                else { rejectedPoints++; refused++; }
                rows.set(rows.size() - 1, new Row(row, old.get(0), bounds[0], bounds[1], accepted, refused, status));
                spent.accept(new Work(pointChecks, edgeChecks, rejectedPoints, rejectedEdges, additions, maskBytes, "PREPARING", rows));
            }
            states.add(accepted.isEmpty() ? old : List.copyOf(expanded));
            if (candidates.isEmpty() && !d.track().points().containsKey(row)) {
                String reason = eligibilityFailure(d, profiles, row);
                double[] envelope = reason == null ? envelope(d, profiles, row) : new double[] {Double.NaN, Double.NaN};
                rows.add(new Row(row, old.get(0), envelope[0], envelope[1], List.of(), 0, reason == null ? "NO_DISTINCT_ADMISSIBLE_CENTER" : reason));
            }
        }
        var masks = new boolean[n][][];
        for (int row = 1; row < n; row++) {
            boolean changed = !states.get(row - 1).equals(d.states().get(row - 1)) || !states.get(row).equals(d.states().get(row));
            if (!changed) continue;
            masks[row] = new boolean[states.get(row - 1).size()][states.get(row).size()];
            for (int a = 0; a < masks[row].length; a++) for (int b = 0; b < masks[row][a].length; b++) {
                double left = states.get(row - 1).get(a), right = states.get(row).get(b);
                if (d.states().get(row - 1).contains(left) && d.states().get(row).contains(right)) continue;
                cancellation.checkpoint(); edgeChecks++;
                spent.accept(new Work(pointChecks, edgeChecks, rejectedPoints, rejectedEdges, additions, maskBytes, "PREPARING", rows));
                boolean allowed = context.sourceAuthority().admits(metric(profiles.get(row - 1), left, context), metric(profiles.get(row), right, context));
                masks[row][a][b] = allowed; if (!allowed) rejectedEdges++;
                spent.accept(new Work(pointChecks, edgeChecks, rejectedPoints, rejectedEdges, additions, maskBytes, "PREPARING", rows));
            }
        }
        var work = new Work(pointChecks, edgeChecks, rejectedPoints, rejectedEdges, additions, maskBytes, "PREPARED", rows);
        spent.accept(work);
        return new Plan(d.token(), context, states, d.states(), masks, work);
    }

    static long estimateMaskBytes(long rows, long points, long checkedEdges) {
        return Math.addExact(Math.multiplyExact(rows, 192), Math.addExact(Math.multiplyExact(points, 64), Math.multiplyExact(checkedEdges, 16)));
    }
    static long estimateMetricBytes(long rows, long points, long pairs) {
        return Math.addExact(Math.multiplyExact(Math.addExact(points, pairs), 64), Math.multiplyExact(rows, 128));
    }
    private static CorridorResourceLimitException limit(String message) {
        return new CorridorResourceLimitException(message, CorridorResourceLimitException.Stage.ORIGINAL_ADMISSION,
                CorridorResourceLimitException.Reason.TABLE_OR_WORK_CAP, Map.of());
    }

    private static List<Double> proposals(CorridorCenterlineOptimizer.OriginalDomain d, List<CorridorProfile> profiles,
            int row, double radius) {
        if (eligibilityFailure(d, profiles, row) != null) return List.of();
        double[] bounds = envelope(d, profiles, row); var result = new TreeSet<Double>();
        var slice = d.tube().at(row);
        for (double value : new double[] {slice.centerOffsetPx(), slice.stabilityCenterOffsetPx()}) {
            if (Double.isFinite(value) && value >= bounds[0] && value <= bounds[1] && !d.states().get(row).contains(value)
                    && metric(profiles.get(row), value, d.context()).distanceTo(metric(profiles.get(row), 0, d.context())) <= radius)
                result.add(value);
        }
        return List.copyOf(result);
    }
    private static String eligibilityFailure(CorridorCenterlineOptimizer.OriginalDomain d, List<CorridorProfile> profiles, int row) {
        var track = d.track();
        if (track.parent() || !track.childTrackIds().isEmpty() || track.points().containsKey(row) || d.states().get(row).size() != 1) return "PARENT_OR_OBSERVED";
        int left = row - 1, right = row + 1;
        while (left >= 0 && row - left <= 17 && !track.points().containsKey(left)) left--;
        while (right < profiles.size() && right - row <= 17 && !track.points().containsKey(right)) right++;
        if (left < 0 || right >= profiles.size() || right - left - 1 > 16) return "ONE_SIDED_OR_OVERSIZED";
        if (!direct(track, left) || !direct(track, right)) return "NON_DIRECT_OR_CENSORED_BOUNDARY";
        if (!track.points().get(right).bridged()) return "MISSING_RIGHT_BRIDGE";
        double lo = profiles.get(left).source().cumulativeGroundDistanceMeters(), hi = profiles.get(right).source().cumulativeGroundDistanceMeters();
        var protection = d.context().sourceProtection();
        if (hi - lo > 20 || protection.firstChainage() > lo || protection.lastChainage() < hi
                || protection.protectedChainages().stream().anyMatch(v -> v >= lo && v <= hi)) return "PROTECTED_OR_PHYSICAL_SPAN";
        for (int i = left; i <= right; i++) {
            var slice = d.tube().at(i); var constraint = d.junctions().at(i);
            if (slice.scaleConflict()) return "TUBE_SCALE_CONFLICT";
            if (slice.parentMerge() && !finePersistenceMerge(d, i)) return "UNRESOLVED_TUBE_PARENT";
            if (constraint != null && constraint.fixed()) return "FIXED_ROW";
            var point = track.points().get(i);
            if (point != null) {
                var scale = d.scales().get(CorridorCenterlineOptimizer.scaleEvidenceKey(i, point.band().id()));
                if (scale != null && scale.scaleConflict()) return "BOUNDARY_SCALE_CONFLICT";
                if (scale != null && scale.parentMerge() && !finePersistenceMerge(d, i)) return "UNRESOLVED_BOUNDARY_PARENT";
                if (!profiles.get(i).bands().contains(point.band())) return "BOUNDARY_NOT_IN_PROFILE";
            }
        }
        return null;
    }
    /** Coarse parents supply persistence only. These exact interval fields prove the tube's fine child. */
    private static boolean finePersistenceMerge(CorridorCenterlineOptimizer.OriginalDomain d, int row) {
        var point = d.track().points().get(row);
        if (point == null || !direct(d.track(), row)) return false;
        var band = point.band(); var slice = d.tube().at(row);
        var scale = d.scales().get(CorridorCenterlineOptimizer.scaleEvidenceKey(row, band.id()));
        return scale != null && scale.parentMerge() && !scale.scaleConflict() && !scale.hasCoarseCenterPrior()
                && slice.observed() && slice.parentMerge() && !slice.scaleConflict()
                && Double.compare(slice.coreMinPx(), band.coreMinPx()) == 0 && Double.compare(slice.coreMaxPx(), band.coreMaxPx()) == 0
                && Double.compare(slice.shoulderMinPx(), band.shoulderMinPx()) == 0 && Double.compare(slice.shoulderMaxPx(), band.shoulderMaxPx()) == 0;
    }
    private static boolean persistenceBoundary(CorridorCenterlineOptimizer.OriginalDomain d, int row) {
        int left = row - 1, right = row + 1;
        while (!d.track().points().containsKey(left)) left--;
        while (!d.track().points().containsKey(right)) right++;
        return finePersistenceMerge(d, left) || finePersistenceMerge(d, right);
    }
    private static boolean direct(CorridorTrack track, int row) {
        var point = track.points().get(row); var band = point.band();
        return point.support() == CorridorPointSupport.DIRECT_UNION && !band.parentHypothesis() && band.childIds().isEmpty()
                && band.boundaryCompleteness() == CorridorBand.BoundaryCompleteness.COMPLETE;
    }
    private static double[] envelope(CorridorCenterlineOptimizer.OriginalDomain d, List<CorridorProfile> profiles, int row) {
        int left = row - 1, right = row + 1;
        while (!d.track().points().containsKey(left)) left--;
        while (!d.track().points().containsKey(right)) right++;
        var anchor = metric(profiles.get(row), 0, d.context()); var step = metric(profiles.get(row), 1, d.context());
        double nx = step.xMeters() - anchor.xMeters(), ny = step.yMeters() - anchor.yMeters(), norm = nx * nx + ny * ny;
        if (!(norm > 0) || !Double.isFinite(norm)) throw new IllegalArgumentException("Finite physical gap normal required");
        double[] first = projected(d, profiles, left, anchor, nx, ny, norm), last = projected(d, profiles, right, anchor, nx, ny, norm);
        double lo = profiles.get(left).source().cumulativeGroundDistanceMeters(), hi = profiles.get(right).source().cumulativeGroundDistanceMeters();
        double t = (profiles.get(row).source().cumulativeGroundDistanceMeters() - lo) / (hi - lo);
        double lower = first[0] + t * (last[0] - first[0]), upper = first[1] + t * (last[1] - first[1]);
        if (!Double.isFinite(lower) || !Double.isFinite(upper) || lower > upper) throw new IllegalArgumentException("Finite projected shoulder envelope required");
        return new double[] {lower, upper};
    }
    private static double[] projected(CorridorCenterlineOptimizer.OriginalDomain d, List<CorridorProfile> profiles,
            int row, MetricPoint anchor, double nx, double ny, double norm) {
        var band = d.track().points().get(row).band();
        var a = metric(profiles.get(row), band.shoulderMinPx(), d.context()); var b = metric(profiles.get(row), band.shoulderMaxPx(), d.context());
        double first = ((a.xMeters() - anchor.xMeters()) * nx + (a.yMeters() - anchor.yMeters()) * ny) / norm;
        double last = ((b.xMeters() - anchor.xMeters()) * nx + (b.yMeters() - anchor.yMeters()) * ny) / norm;
        return new double[] {Math.min(first, last), Math.max(first, last)};
    }
    private static MetricPoint metric(CorridorProfile profile, double offset, CorridorWorkingSchedule.BoundContext context) {
        var anchor = profile.source().anchorScreen(); var normal = profile.source().normalScreen();
        return context.frame().pixelCenterToMetric(anchor.x + offset * normal.x, anchor.y + offset * normal.y);
    }
}
