package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupInterval;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Bounded geometric proposal in the factual independent-control basis. */
public final class LongScaleNormalFairing {
    private static final long DEFAULT_WORK = 8_000_000L;
    private static final long MAX_SCRATCH = 256L * 1024 * 1024;
    private static final double EPS = 1e-9;
    private LongScaleNormalFairing() { }

    /** Proposal status; no status certifies source fit, branch identity or applicability. */
    public enum Status { UNCHANGED, PROPOSED, PARTIALLY_PROPOSED, REJECTED, REFUSED_RESOURCE }
    /** Disposition of one independently backtracked native-control island. */
    public enum Disposition { FROZEN, UNCHANGED, PROPOSED, REJECTED }

    /** One trial against the complete lifted final polyline. */
    public record Trial(double scale, String reason) { }
    /** Stable slot bounds and truthful trial evidence for one local island. */
    public record IntervalResult(int firstIndex, int lastIndex, Disposition disposition,
            String reason, List<Trial> trials) {
        public IntervalResult { trials = List.copyOf(trials); }
    }

    /** Explicit ancestry, physical scale, caller-owned eligibility and external validation hooks. */
    public record Request(FinalControlBasis basis, double sourcePitchMeters,
            List<CleanupInterval> intervals, List<ImageSupportedRefitter.GeometryValidator> validators,
            long maximumWorkVisits) {
        public Request {
            if (basis == null || !Double.isFinite(sourcePitchMeters) || sourcePitchMeters <= 0
                    || maximumWorkVisits < 0)
                throw new IllegalArgumentException("Valid basis and source pitch are required");
            intervals = List.copyOf(intervals);
            validators = List.copyOf(validators);
            if (intervals.size() > FinalControlBasis.MAX_FINAL_POINTS
                    || validators.size() > 1_024) throw new IllegalArgumentException("Too many request entries");
        }
        public Request(FinalControlBasis basis, double sourcePitchMeters,
                List<CleanupInterval> intervals, List<ImageSupportedRefitter.GeometryValidator> validators) {
            this(basis, sourcePitchMeters, intervals, validators, DEFAULT_WORK);
        }
    }

    /** Immutable native-slot proposal. Source validation is always a separate caller obligation. */
    public record Result(List<MetricPoint> points, Status status, List<IntervalResult> intervals,
            long workVisits, long externalValidationCalls, double maximumMovementMeters, String reason) {
        public Result { points = List.copyOf(points); intervals = List.copyOf(intervals); }
        public boolean requiresSourceValidation() { return true; }
    }

    /** Runs one fixed physical fairing field with independent island backtracking and one budget. */
    public static Result propose(Request request, CancellationProbe cancellation) {
        Objects.requireNonNull(request); Objects.requireNonNull(cancellation);
        cancellation.checkpoint();
        Budget budget = new Budget(request.maximumWorkVisits(), cancellation);
        List<MetricPoint> raw = request.basis().initialCoordinates();
        List<IntervalResult> diagnostics = new ArrayList<>();
        try {
            budget.reserve(request.basis().scratchReservationBytes() + 1_024L * raw.size());
            Reference ref = new Reference(raw, budget);
            List<Integer> independent = request.basis().independentIndices();
            boolean[] movable = new boolean[raw.size()];
            int previousEnd = -1;
            for (CleanupInterval interval : request.intervals()) {
                budget.visit();
                int a = interval.startIndex(), b = interval.endIndex();
                if (a < previousEnd || b >= raw.size()
                        || !request.basis().rows().get(a).independent()
                        || !request.basis().rows().get(b).independent()
                        || !close(interval.startChainageMeters(), ref.s[a])
                        || !close(interval.endChainageMeters(), ref.s[b])) {
                    return result(raw, Status.REJECTED, diagnostics, budget, raw, "invalid-interval-authority");
                }
                previousEnd = b;
                if (!interval.smoothingEligible()) {
                    diagnostics.add(new IntervalResult(a, b, Disposition.FROZEN, "ineligible-interval", List.of()));
                    continue;
                }
                for (int i : independent) { budget.visit(); if (i > a && i < b) movable[i] = true; }
            }
            for (int i : request.basis().protectedIndices()) movable[i] = false;
            int divisions = (int) Math.ceil(ref.length / .5);
            if (divisions < 1 || divisions + 1L + request.basis().protectedIndices().size()
                    > GeometricCurvatureOperator.MAXIMUM_MESH_POINTS) throw new Refusal("mesh-limit");
            budget.reserve(256L * (divisions + 1L));
            double step = ref.length / divisions;
            if (!Double.isFinite(12 / step) || 12 / step > GeometricCurvatureOperator.MAXIMUM_MESH_POINTS)
                throw new Refusal("stencil-limit");
            TreeSet<Double> grid = new TreeSet<>();
            for (int i = 0; i <= divisions; i++) { budget.visit(); grid.add(ref.length * i / divisions); }
            for (int i : request.basis().protectedIndices()) grid.add(ref.s[i]);
            List<Feature> features = new ArrayList<>();
            for (double s : grid) {
                budget.visit();
                MetricPoint p = ref.at(s), left = ref.at(Math.max(0, s - 4)), right = ref.at(Math.min(ref.length, s + 4));
                if (Math.abs(turn(sub(p, left), sub(right, p))) < Math.PI / 4) continue;
                Feature feature = freezeFeature(ref, s, request.sourcePitchMeters(), 12, budget);
                if (feature == null) {
                    freezeIsland(independent, movable, ref, s, 12, "censored-broad-feature", diagnostics, budget);
                } else {
                    features.add(feature);
                    // An opposite neighboring lobe can offset the broad chord. Retain a
                    // meaningful tight apex independently on the existing +/-4m turn scale.
                    Feature apex = freezeFeature(ref, s, request.sourcePitchMeters(), 4, budget);
                    if (apex != null) features.add(apex);
                    else freezeIsland(independent, movable, ref, s, 4, "censored-inner-apex-feature", diagnostics, budget);
                }
            }
            List<MetricPoint> controls = new ArrayList<>(raw);
            boolean incomplete = diagnostics.stream().anyMatch(d -> d.disposition() == Disposition.FROZEN);
            for (int position = 1; position < independent.size() - 1;) {
                budget.visit();
                if (!movable[independent.get(position)]) { position++; continue; }
                int firstPosition = position - 1;
                while (position < independent.size() - 1 && movable[independent.get(position)]) position++;
                int lastPosition = position;
                int first = independent.get(firstPosition), last = independent.get(lastPosition);
                MetricPoint[] deltas = new MetricPoint[lastPosition - firstPosition + 1];
                boolean nonzero = false;
                for (int j = firstPosition; j <= lastPosition; j++) {
                    budget.visit(); int index = independent.get(j);
                    MetricPoint delta = j == firstPosition || j == lastPosition ? new MetricPoint(0, 0)
                            : field(ref, ref.s[index], step, ref.s[first], ref.s[last], request.sourcePitchMeters(), budget);
                    deltas[j - firstPosition] = delta;
                    nonzero |= delta.xMeters() != 0 || delta.yMeters() != 0;
                }
                if (!nonzero) {
                    diagnostics.add(new IntervalResult(first, last, Disposition.UNCHANGED, "zero-field", List.of()));
                    continue;
                }
                budget.add(raw.size()); List<MetricPoint> originalIslandGeometry = request.basis().lift(controls);
                List<Trial> trials = new ArrayList<>(); boolean accepted = false, islandChanged = false;
                for (int half = 0; half <= 12; half++) {
                    budget.visit(); double scale = Math.scalb(1., -half);
                    List<MetricPoint> trialControls = new ArrayList<>(controls);
                    for (int j = firstPosition + 1; j < lastPosition; j++) {
                        budget.visit(); int index = independent.get(j); MetricPoint d = deltas[j - firstPosition];
                        MetricPoint origin = raw.get(index);
                        if (d.xMeters() != 0 || d.yMeters() != 0) {
                            double dx = scale * d.xMeters(), dy = scale * d.yMeters();
                            double x = dx == 0 ? origin.xMeters() : origin.xMeters() + dx;
                            double y = dy == 0 ? origin.yMeters() : origin.yMeters() + dy;
                            trialControls.set(index, x == origin.xMeters() && y == origin.yMeters()
                                    ? origin : new MetricPoint(x, y));
                        }
                    }
                    budget.add(raw.size()); List<MetricPoint> lifted = request.basis().lift(trialControls);
                    String reason = validate(raw, lifted, ref, features, request, budget);
                    budget.reserve(128); trials.add(new Trial(scale, reason));
                    if (reason.equals("ok")) {
                        islandChanged = !samePhysicalGeometry(originalIslandGeometry, lifted, budget);
                        if (islandChanged) controls = trialControls;
                        accepted = true;
                        break;
                    }
                }
                incomplete |= !accepted;
                diagnostics.add(new IntervalResult(first, last, !accepted ? Disposition.REJECTED
                        : islandChanged ? Disposition.PROPOSED : Disposition.UNCHANGED,
                        !accepted ? "no-valid-trial" : islandChanged ? "geometric-proposal-requires-source-validation"
                                : "rounded-away-displacement", trials));
            }
            budget.add(raw.size()); List<MetricPoint> points = request.basis().lift(controls);
            String finalReason = validate(raw, points, ref, features, request, budget);
            if (!finalReason.equals("ok")) return result(raw, Status.REJECTED, diagnostics, budget, raw, finalReason);
            boolean changed = !samePhysicalGeometry(raw, points, budget);
            if (!changed) points = raw;
            return result(points, changed ? incomplete ? Status.PARTIALLY_PROPOSED : Status.PROPOSED : Status.UNCHANGED,
                    diagnostics, budget, raw, changed ? "source-validation-required" : "no-change");
        } catch (Refusal refusal) {
            return result(raw, Status.REFUSED_RESOURCE, diagnostics, budget, raw, refusal.getMessage());
        } catch (IllegalArgumentException invalidGeometry) {
            return result(raw, Status.REJECTED, diagnostics, budget, raw, "invalid-reference-or-lift");
        }
    }

    private static boolean samePhysicalGeometry(List<MetricPoint> first, List<MetricPoint> second, Budget budget) {
        for (int i = 0; i < first.size(); i++) {
            budget.visit(); MetricPoint a = first.get(i), b = second.get(i);
            // Finite +0/-0 coordinates denote the same physical location. Restore
            // original objects for an unchanged island rather than publishing a sign-only sibling.
            if (a.xMeters() != b.xMeters() || a.yMeters() != b.yMeters()) return false;
        }
        return true;
    }

    private static void freezeIsland(List<Integer> independent, boolean[] movable, Reference ref,
            double center, double halfWindow, String reason, List<IntervalResult> diagnostics, Budget budget) {
        int first = -1, last = -1;
        for (int i : independent) {
            budget.visit();
            if (ref.s[i] <= center - halfWindow) first = i;
            if (last < 0 && ref.s[i] >= center + halfWindow) last = i;
        }
        if (first < 0) first = independent.get(0);
        if (last < 0) last = independent.get(independent.size() - 1);
        for (int i : independent) if (i >= first && i <= last) { budget.visit(); movable[i] = false; }
        diagnostics.add(new IntervalResult(first, last, Disposition.FROZEN, reason, List.of()));
    }

    private static Result result(List<MetricPoint> points, Status status, List<IntervalResult> intervals,
            Budget budget, List<MetricPoint> raw, String reason) {
        double maximum = 0;
        for (int i = 0; i < points.size(); i++) maximum = Math.max(maximum, points.get(i).distanceTo(raw.get(i)));
        return new Result(points, status, intervals, budget.used, budget.externalCalls, maximum, reason);
    }

    private static MetricPoint field(Reference ref, double s, double step, double first, double last,
            double pitch, Budget budget) {
        MetricPoint p = ref.at(s), tangent = sub(ref.at(Math.min(ref.length, s + 6)), ref.at(Math.max(0, s - 6)));
        double length = norm(tangent);
        if (length < EPS) return new MetricPoint(0, 0);
        double x = 0, y = 0, sum = 0;
        int radius = (int) Math.floor(12 / step);
        for (int tap = -radius; tap <= radius; tap++) {
            budget.visit(); double offset = tap * step, weight = Math.exp(-.5 * offset * offset / 16);
            MetricPoint q = ref.at(Math.max(0, Math.min(ref.length, s + offset)));
            x += weight * q.xMeters(); y += weight * q.yMeters(); sum += weight;
        }
        double nx = -tangent.yMeters() / length, ny = tangent.xMeters() / length;
        double delta = Math.max(-.5 * pitch, Math.min(.5 * pitch, (x / sum - p.xMeters()) * nx + (y / sum - p.yMeters()) * ny));
        if (Math.abs(delta) < 1e-12) return new MetricPoint(0, 0);
        double distance = Math.min(s - first, last - s);
        double taper = Math.sin(Math.PI * .5 * Math.min(1, distance / 12));
        return new MetricPoint(delta * taper * taper * nx, delta * taper * taper * ny);
    }

    private static String validate(List<MetricPoint> raw, List<MetricPoint> points, Reference ref,
            List<Feature> features, Request request, Budget budget) {
        double d = .5 * request.sourcePitchMeters();
        for (int i = 0; i < points.size(); i++) {
            budget.visit();
            if (points.get(i).distanceTo(raw.get(i)) > d + EPS) return "movement-bound";
            if (i > 0) {
                MetricPoint old = sub(raw.get(i), raw.get(i - 1)), now = sub(points.get(i), points.get(i - 1));
                if (norm(now) + 1e-12 < .5 * norm(old) || dot(old, now) <= 0) return "bunching-or-foldback";
            }
        }
        for (int a = 0; a < points.size() - 1; a++) for (int b = a + 2; b < points.size() - 1; b++) {
            budget.visit();
            if (contact(points.get(a), points.get(a + 1), points.get(b), points.get(b + 1))
                    && !contact(raw.get(a), raw.get(a + 1), raw.get(b), raw.get(b + 1))) return "new-self-contact";
        }
        Reference trial = new Reference(points, ref.s, budget);
        for (Feature feature : features) { budget.visit(); String reason = feature.validate(trial, d, budget); if (!reason.equals("ok")) return reason; }
        for (ImageSupportedRefitter.GeometryValidator validator : request.validators()) {
            budget.visit(); budget.externalCalls++;
            ImageSupportedRefitter.Validation validation = validator.validate(List.copyOf(points));
            budget.cancellation.checkpoint(); if (!validation.feasible()) return "external-validator-rejected";
        }
        return "ok";
    }

    private record Segment(MetricPoint a, MetricPoint b) { }
    private record Lobe(double sign, double amplitude, List<Segment> apex) { }

    /** Pure geometric assessment seam; uses the same frozen guards as every production trial. */
    record BroadFeatureAssessment(String armReason, String lobeReason, int meaningfulLobes) {
        boolean accepted() { return armReason.equals("ok") && lobeReason.equals("ok"); }
    }
    static BroadFeatureAssessment assessBroadFeature(List<MetricPoint> raw, List<MetricPoint> proposed,
            double pitch, double center, double halfWindow) {
        if (raw.size() != proposed.size() || !Double.isFinite(pitch) || pitch <= 0
                || !Double.isFinite(halfWindow) || halfWindow <= 0 || halfWindow > 12
                || !Double.isFinite(center)) throw new IllegalArgumentException("Invalid feature geometry");
        Budget budget = new Budget(DEFAULT_WORK, CancellationProbe.NONE);
        Reference ref = new Reference(List.copyOf(raw), budget);
        if (center < 0 || center > ref.length) throw new IllegalArgumentException("Feature center outside reference");
        Feature feature = freezeFeature(ref, center, pitch, halfWindow, budget);
        if (feature == null) return new BroadFeatureAssessment("undefined-broad-feature", "undefined-broad-feature", 0);
        Reference trial = new Reference(List.copyOf(proposed), ref.s, budget);
        return new BroadFeatureAssessment(feature.validateArms(trial),
                feature.validateLobes(trial, .5 * pitch, budget), feature.lobes().size());
    }

    private record Feature(double[] armS, MetricPoint[] arms, MetricPoint origin, MetricPoint normal,
            double[] samples, double incomingBound, double outgoingBound, double netTurn, List<Lobe> lobes) {
        String validate(Reference trial, double d, Budget budget) {
            String armReason = validateArms(trial);
            return armReason.equals("ok") ? validateLobes(trial, d, budget) : armReason;
        }
        String validateArms(Reference trial) {
            MetricPoint[] q = new MetricPoint[4];
            for (int i = 0; i < 4; i++) q[i] = trial.at(armS[i]);
            MetricPoint incoming = sub(q[1], q[0]), outgoing = sub(q[3], q[2]);
            if (norm(incoming) < EPS || norm(outgoing) < EPS) return "degenerate-broad-arm";
            if (Math.abs(turn(sub(arms[1], arms[0]), incoming)) > incomingBound + 1e-12
                    || Math.abs(turn(sub(arms[3], arms[2]), outgoing)) > outgoingBound + 1e-12) return "broad-arm-heading";
            double net = turn(incoming, outgoing), bound = incomingBound + outgoingBound;
            if (Math.abs(wrap(net - netTurn)) > bound + 1e-12
                    || Math.abs(netTurn) > bound && net * netTurn <= 0) return "broad-net-turn";
            return "ok";
        }
        String validateLobes(Reference trial, double d, Budget budget) {
            for (Lobe lobe : lobes) {
                double amplitude = -Double.MAX_VALUE; MetricPoint apex = null;
                for (double s : samples) {
                    budget.visit(); MetricPoint point = trial.at(s); double h = lobe.sign * dot(sub(point, origin), normal);
                    if (h > amplitude) { amplitude = h; apex = point; }
                }
                if (amplitude + EPS < Math.max(.9 * lobe.amplitude, lobe.amplitude - 2 * d)) return "broad-amplitude-loss";
                double distance = Double.POSITIVE_INFINITY;
                for (Segment segment : lobe.apex) { budget.visit(); distance = Math.min(distance, distance(apex, segment.a, segment.b)); }
                if (distance > d + EPS) return "broad-apex-shift";
            }
            return "ok";
        }
    }

    private static Feature freezeFeature(Reference ref, double center, double pitch, double halfWindow, Budget budget) {
        double[] s = {Math.max(0, center - halfWindow), Math.max(0, center - halfWindow / 2),
                Math.min(ref.length, center + halfWindow / 2), Math.min(ref.length, center + halfWindow)};
        MetricPoint[] arms = new MetricPoint[4]; for (int i = 0; i < 4; i++) arms[i] = ref.at(s[i]);
        MetricPoint incoming = sub(arms[1], arms[0]), outgoing = sub(arms[3], arms[2]), chord = sub(arms[3], arms[0]);
        if (norm(incoming) < EPS || norm(outgoing) < EPS || norm(chord) < EPS) return null;
        TreeSet<Double> stations = new TreeSet<>();
        int divisions = Math.max(1, (int) Math.ceil((s[3] - s[0]) / .25));
        for (int i = 0; i <= divisions; i++) { budget.visit(); stations.add(s[0] + (s[3] - s[0]) * i / divisions); }
        for (double station : ref.s) { budget.visit(); if (station >= s[0] && station <= s[3]) stations.add(station); }
        budget.reserve(512L + 128L * stations.size());
        double[] samples = stations.stream().mapToDouble(Double::doubleValue).toArray();
        MetricPoint normal = new MetricPoint(-chord.yMeters() / norm(chord), chord.xMeters() / norm(chord));
        List<Lobe> lobes = new ArrayList<>();
        for (double sign : new double[]{1, -1}) {
            double[] h = new double[samples.length]; double amplitude = 0;
            for (int i = 0; i < h.length; i++) { budget.visit(); h[i] = sign * dot(sub(ref.at(samples[i]), arms[0]), normal); amplitude = Math.max(amplitude, h[i]); }
            if (amplitude + 1e-12 < pitch) continue;
            List<Segment> apex = new ArrayList<>(); double threshold = amplitude - .5 * pitch;
            for (int i = 1; i < h.length; i++) {
                budget.visit(); if (Math.max(h[i - 1], h[i]) < threshold) continue;
                MetricPoint a = ref.at(samples[i - 1]), b = ref.at(samples[i]);
                MetricPoint left = h[i - 1] < threshold ? interpolate(a, b, (threshold - h[i - 1]) / (h[i] - h[i - 1])) : a;
                MetricPoint right = h[i] < threshold ? interpolate(a, b, (threshold - h[i - 1]) / (h[i] - h[i - 1])) : b;
                apex.add(new Segment(left, right));
            }
            lobes.add(new Lobe(sign, amplitude, List.copyOf(apex)));
        }
        return new Feature(s, arms, arms[0], normal, samples, Math.asin(Math.min(1, pitch / norm(incoming))),
                Math.asin(Math.min(1, pitch / norm(outgoing))), turn(incoming, outgoing), List.copyOf(lobes));
    }

    private static final class Reference {
        final List<MetricPoint> points; final double[] s; final double length; final Budget budget;
        Reference(List<MetricPoint> points, Budget budget) {
            this.points = points; this.budget = budget; s = new double[points.size()];
            for (int i = 1; i < s.length; i++) {
                budget.visit(); double edge = points.get(i).distanceTo(points.get(i - 1));
                if (!(edge > 0) || !Double.isFinite(edge)) throw new IllegalArgumentException("Degenerate reference");
                s[i] = s[i - 1] + edge;
            }
            length = s[s.length - 1];
            if (!Double.isFinite(length)) throw new IllegalArgumentException("Nonfinite reference length");
        }
        Reference(List<MetricPoint> points, double[] chainage, Budget budget) { this.points = points; s = chainage; length = s[s.length - 1]; this.budget = budget; }
        MetricPoint at(double station) {
            budget.visit();
            int index = Arrays.binarySearch(s, station);
            if (index >= 0) return points.get(index);
            int right = -index - 1;
            if (right == 0) return points.get(0);
            if (right >= s.length) return points.get(s.length - 1);
            return interpolate(points.get(right - 1), points.get(right), (station - s[right - 1]) / (s[right] - s[right - 1]));
        }
    }

    private static final class Refusal extends RuntimeException { Refusal(String reason) { super(reason); } }
    private static final class Budget {
        final long maximum; final CancellationProbe cancellation; long used, scratch, externalCalls;
        Budget(long maximum, CancellationProbe cancellation) { this.maximum = maximum; this.cancellation = cancellation; }
        void visit() { add(1); }
        void add(long count) {
            cancellation.checkpoint();
            if (count > maximum - used) throw new Refusal("work-limit");
            used += count;
        }
        void reserve(long count) {
            if (count < 0 || count > MAX_SCRATCH - scratch) throw new Refusal("scratch-limit");
            scratch += count;
        }
    }
    private static MetricPoint sub(MetricPoint a, MetricPoint b) { return new MetricPoint(a.xMeters() - b.xMeters(), a.yMeters() - b.yMeters()); }
    private static MetricPoint interpolate(MetricPoint a, MetricPoint b, double t) { return new MetricPoint(a.xMeters() + t * (b.xMeters() - a.xMeters()), a.yMeters() + t * (b.yMeters() - a.yMeters())); }
    private static double norm(MetricPoint a) { return Math.hypot(a.xMeters(), a.yMeters()); }
    private static double dot(MetricPoint a, MetricPoint b) { return a.xMeters() * b.xMeters() + a.yMeters() * b.yMeters(); }
    private static double cross(MetricPoint a, MetricPoint b) { return a.xMeters() * b.yMeters() - a.yMeters() * b.xMeters(); }
    private static double turn(MetricPoint a, MetricPoint b) { return Math.atan2(cross(a, b), dot(a, b)); }
    private static double wrap(double angle) { return Math.atan2(Math.sin(angle), Math.cos(angle)); }
    private static boolean close(double a, double b) { return Math.abs(a - b) <= 1e-9 * Math.max(1, Math.max(Math.abs(a), Math.abs(b))); }
    private static double distance(MetricPoint p, MetricPoint a, MetricPoint b) {
        MetricPoint v = sub(b, a); double squared = dot(v, v);
        return p.distanceTo(squared == 0 ? a : interpolate(a, b, Math.max(0, Math.min(1, dot(sub(p, a), v) / squared))));
    }
    private static boolean contact(MetricPoint a, MetricPoint b, MetricPoint c, MetricPoint d) {
        if (Math.max(a.xMeters(), b.xMeters()) + 1e-10 < Math.min(c.xMeters(), d.xMeters())
                || Math.max(c.xMeters(), d.xMeters()) + 1e-10 < Math.min(a.xMeters(), b.xMeters())
                || Math.max(a.yMeters(), b.yMeters()) + 1e-10 < Math.min(c.yMeters(), d.yMeters())
                || Math.max(c.yMeters(), d.yMeters()) + 1e-10 < Math.min(a.yMeters(), b.yMeters())) return false;
        double x = cross(sub(b, a), sub(c, a)), y = cross(sub(b, a), sub(d, a));
        double u = cross(sub(d, c), sub(a, c)), v = cross(sub(d, c), sub(b, c));
        return (x <= 1e-10 && y >= -1e-10 || y <= 1e-10 && x >= -1e-10)
                && (u <= 1e-10 && v >= -1e-10 || v <= 1e-10 && u >= -1e-10);
    }
}
