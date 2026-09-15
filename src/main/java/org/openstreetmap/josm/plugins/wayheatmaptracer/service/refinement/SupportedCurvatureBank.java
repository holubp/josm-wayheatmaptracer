package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.GeometricCurvatureOperator.InverseMetersVector;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField.FrozenProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField.FrozenSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Immutable 6/10/20 metre same-branch image-curvature evidence. */
public final class SupportedCurvatureBank {
    /** Evidence ownership of one fine-mesh curvature target. */
    public enum Status { MEASURED, AUTHORIZED_ZERO, UNKNOWN }

    /** One typed curvature target at an interior fine-mesh point. */
    public record Target(Status status, InverseMetersVector curvature) { }

    private static final double[] WINDOWS_METERS = {6.0, 10.0, 20.0};
    private static final double HUBER_DELTA_RADIANS = Math.toRadians(30.0);
    private final List<Target> targets;

    private SupportedCurvatureBank(List<Target> targets) {
        this.targets = List.copyOf(targets);
    }

    /** Builds deterministic physical-window fits from already frozen branch profiles. */
    public static SupportedCurvatureBank build(FrozenRefitMesh mesh, List<FrozenProfile> profiles,
            double sourcePitchMeters, CancellationProbe cancellation) {
        if (mesh == null || profiles == null || profiles.size() != mesh.meshPointCount()
                || !Double.isFinite(sourcePitchMeters) || sourcePitchMeters <= 0.0
                || cancellation == null) {
            throw new IllegalArgumentException("Curvature-bank inputs are inconsistent");
        }
        List<FrozenRefitMesh.Row> rows = mesh.rows();
        double[] unwrapped = unwrap(profiles);
        ArrayList<Target> targets = new ArrayList<>(profiles.size() - 2);
        for (int center = 1; center < profiles.size() - 1; center++) {
            cancellation.checkpoint();
            double[] slope = new double[WINDOWS_METERS.length];
            boolean[] measured = new boolean[WINDOWS_METERS.length];
            for (int window = 0; window < WINDOWS_METERS.length; window++) {
                Fit fit = fitWindow(center, WINDOWS_METERS[window], rows, profiles, unwrapped,
                        cancellation);
                slope[window] = fit.slope();
                measured[window] = fit.measured();
            }
            boolean primaryMeasured = measured[0] && measured[1]
                    && compatible(slope[0], WINDOWS_METERS[0], slope[1], WINDOWS_METERS[1]);
            if (!primaryMeasured) {
                Status status = straightCorridorCertificate(center, rows, profiles, unwrapped,
                        sourcePitchMeters, cancellation)
                        ? Status.AUTHORIZED_ZERO : Status.UNKNOWN;
                targets.add(new Target(status, new InverseMetersVector(0.0, 0.0)));
                continue;
            }
            double selected = measured[2]
                    && compatible(slope[1], WINDOWS_METERS[1], slope[2], WINDOWS_METERS[2])
                    ? (slope[0] + slope[1] + slope[2]) / 3.0 : 0.5 * (slope[0] + slope[1]);
            double angle = unwrapped[center];
            if (!Double.isFinite(angle)) {
                angle = interpolatedAngle(center, rows, unwrapped);
            }
            if (!Double.isFinite(angle)) {
                targets.add(new Target(Status.UNKNOWN, new InverseMetersVector(0.0, 0.0)));
                continue;
            }
            targets.add(new Target(Status.MEASURED, new InverseMetersVector(
                    -Math.sin(angle) * selected, Math.cos(angle) * selected)));
        }
        return new SupportedCurvatureBank(targets);
    }

    public List<Target> targets() { return targets; }

    private static double[] unwrap(List<FrozenProfile> profiles) {
        double[] result = new double[profiles.size()];
        double previous = 0.0;
        boolean havePrevious = false;
        for (int index = 0; index < profiles.size(); index++) {
            FrozenProfile profile = profiles.get(index);
            if (profile.support() != FrozenSupport.MEASURED || profile.orientationCertainty() <= 0.0) {
                result[index] = Double.NaN;
                continue;
            }
            double angle = profile.orientationRadians();
            if (havePrevious) {
                while (angle - previous > Math.PI / 2.0) angle -= Math.PI;
                while (angle - previous < -Math.PI / 2.0) angle += Math.PI;
            }
            result[index] = angle;
            previous = angle;
            havePrevious = true;
        }
        return result;
    }

    private static Fit fitWindow(int center, double windowMeters, List<FrozenRefitMesh.Row> rows,
            List<FrozenProfile> profiles, double[] angles) {
        return fitWindow(center, windowMeters, rows, profiles, angles, CancellationProbe.NONE);
    }

    private static Fit fitWindow(int center, double windowMeters, List<FrozenRefitMesh.Row> rows,
            List<FrozenProfile> profiles, double[] angles, CancellationProbe cancellation) {
        double centerChainage = rows.get(center).referenceChainageMeters();
        WindowData window = windowData(centerChainage, windowMeters, rows, profiles, angles,
                cancellation);
        if (window.leftSupportedMeters() + 1.0e-9 < 0.4 * windowMeters
                || window.rightSupportedMeters() + 1.0e-9 < 0.4 * windowMeters
                || window.leftSupportedMeters() + window.rightSupportedMeters() + 1.0e-9
                        < 0.8 * windowMeters) {
            return Fit.UNKNOWN;
        }
        double[] robust = new double[window.samples().size()];
        java.util.Arrays.fill(robust, 1.0);
        Regression regression = regression(window.samples(), centerChainage, robust);
        if (!regression.measured()) return Fit.UNKNOWN;
        for (int iteration = 0; iteration < 10; iteration++) {
            cancellation.checkpoint();
            for (int index = 0; index < window.samples().size(); index++) {
                WindowSample sample = window.samples().get(index);
                if (!sample.measured()) {
                    robust[index] = 0.0;
                } else {
                    double residual = sample.angleRadians() - regression.intercept()
                            - regression.slope() * (sample.chainageMeters() - centerChainage);
                    robust[index] = Math.abs(residual) <= HUBER_DELTA_RADIANS
                            ? 1.0 : HUBER_DELTA_RADIANS / Math.abs(residual);
                }
            }
            regression = regression(window.samples(), centerChainage, robust);
            if (!regression.measured()) return Fit.UNKNOWN;
        }
        return new Fit(true, regression.slope());
    }

    private static Regression regression(List<WindowSample> samples, double centerChainage,
            double[] robust) {
        double sw = 0.0, sx = 0.0, sy = 0.0, sxx = 0.0, sxy = 0.0;
        double root = Math.sqrt(3.0 / 5.0);
        double[] nodes = {-root, 0.0, root};
        double[] weights = {5.0 / 9.0, 8.0 / 9.0, 5.0 / 9.0};
        for (int index = 0; index + 1 < samples.size(); index++) {
            WindowSample first = samples.get(index);
            WindowSample second = samples.get(index + 1);
            if (!first.measured() || !second.measured()) continue;
            double length = second.chainageMeters() - first.chainageMeters();
            if (!(length > 0.0)) continue;
            for (int quadrature = 0; quadrature < nodes.length; quadrature++) {
                double fraction = 0.5 * (nodes[quadrature] + 1.0);
                double x = first.chainageMeters() + fraction * length - centerChainage;
                double y = first.angleRadians()
                        + fraction * (second.angleRadians() - first.angleRadians());
                double robustWeight = robust[index]
                        + fraction * (robust[index + 1] - robust[index]);
                double weight = 0.5 * length * weights[quadrature] * robustWeight;
                sw += weight;
                sx += weight * x;
                sy += weight * y;
                sxx += weight * x * x;
                sxy += weight * x * y;
            }
        }
        double denominator = sw * sxx - sx * sx;
        if (!(denominator > 1.0e-12) || !(sw > 0.0)) return Regression.UNKNOWN;
        double slope = (sw * sxy - sx * sy) / denominator;
        double intercept = (sy - slope * sx) / sw;
        return Double.isFinite(slope) && Double.isFinite(intercept)
                ? new Regression(true, intercept, slope) : Regression.UNKNOWN;
    }

    private static WindowData windowData(double centerChainage, double windowMeters,
            List<FrozenRefitMesh.Row> rows, List<FrozenProfile> profiles, double[] angles) {
        return windowData(centerChainage, windowMeters, rows, profiles, angles,
                CancellationProbe.NONE);
    }

    private static WindowData windowData(double centerChainage, double windowMeters,
            List<FrozenRefitMesh.Row> rows, List<FrozenProfile> profiles, double[] angles,
            CancellationProbe cancellation) {
        double leftBoundary = centerChainage - 0.5 * windowMeters;
        double rightBoundary = centerChainage + 0.5 * windowMeters;
        ArrayList<WindowSample> samples = new ArrayList<>();
        samples.add(sampleAt(leftBoundary, rows, profiles, angles));
        int firstInterior = lowerBound(rows, leftBoundary + 1.0e-12);
        int afterInterior = lowerBound(rows, rightBoundary - 1.0e-12);
        for (int index = firstInterior; index < afterInterior; index++) {
            if ((index & 1023) == 0) cancellation.checkpoint();
            double chainage = rows.get(index).referenceChainageMeters();
            if (chainage > leftBoundary + 1.0e-12 && chainage < rightBoundary - 1.0e-12) {
                samples.add(sampleAtRow(index, rows, profiles, angles));
            }
        }
        samples.add(sampleAt(rightBoundary, rows, profiles, angles));
        double leftSupported = 0.0;
        double rightSupported = 0.0;
        for (int index = 0; index + 1 < samples.size(); index++) {
            WindowSample first = samples.get(index);
            WindowSample second = samples.get(index + 1);
            if (!first.measured() || !second.measured()) continue;
            double a = first.chainageMeters();
            double b = second.chainageMeters();
            leftSupported += Math.max(0.0, Math.min(b, centerChainage) - a);
            rightSupported += Math.max(0.0, b - Math.max(a, centerChainage));
        }
        return new WindowData(List.copyOf(samples), leftSupported, rightSupported);
    }

    private static WindowSample sampleAt(double chainage, List<FrozenRefitMesh.Row> rows,
            List<FrozenProfile> profiles, double[] angles) {
        if (chainage < rows.get(0).referenceChainageMeters() - 1.0e-12
                || chainage > rows.get(rows.size() - 1).referenceChainageMeters() + 1.0e-12) {
            return WindowSample.missing(chainage);
        }
        int upper = lowerBound(rows, chainage - 1.0e-12);
        if (upper < rows.size()
                && Math.abs(rows.get(upper).referenceChainageMeters() - chainage) <= 1.0e-12) {
            return sampleAtRow(upper, rows, profiles, angles);
        }
        if (upper <= 0 || upper >= rows.size()) return WindowSample.missing(chainage);
        WindowSample first = sampleAtRow(upper - 1, rows, profiles, angles);
        WindowSample second = sampleAtRow(upper, rows, profiles, angles);
        if (!first.measured() || !second.measured()) return WindowSample.missing(chainage);
        double fraction = (chainage - first.chainageMeters())
                / (second.chainageMeters() - first.chainageMeters());
        return new WindowSample(chainage,
                first.angleRadians() + fraction * (second.angleRadians() - first.angleRadians()),
                interpolate(first.center(), second.center(), fraction),
                interpolate(first.origin(), second.origin(), fraction), true,
                List.of(first.profileContributors().get(0), second.profileContributors().get(0)));
    }

    private static WindowSample sampleAtRow(int index, List<FrozenRefitMesh.Row> rows,
            List<FrozenProfile> profiles, double[] angles) {
        FrozenProfile profile = profiles.get(index);
        boolean measured = profile.support() == FrozenSupport.MEASURED
                && Double.isFinite(angles[index]);
        return measured ? new WindowSample(rows.get(index).referenceChainageMeters(), angles[index],
                centerPoint(profile), profile.origin(), true, List.of(profile))
                : WindowSample.missing(rows.get(index).referenceChainageMeters());
    }

    private static int lowerBound(List<FrozenRefitMesh.Row> rows, double chainageMeters) {
        int low = 0;
        int high = rows.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (rows.get(middle).referenceChainageMeters() < chainageMeters) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static boolean compatible(double first, double firstWindowMeters,
            double second, double secondWindowMeters) {
        boolean sign = Math.abs(first) < 1.0e-12 || Math.abs(second) < 1.0e-12
                || Math.signum(first) == Math.signum(second);
        return sign && Math.abs(first * firstWindowMeters - second * secondWindowMeters)
                <= Math.toRadians(30.0);
    }

    private static double interpolatedAngle(int center, List<FrozenRefitMesh.Row> rows, double[] angles) {
        int left = center - 1;
        while (left >= 0 && !Double.isFinite(angles[left])) left--;
        int right = center + 1;
        while (right < angles.length && !Double.isFinite(angles[right])) right++;
        if (left < 0 || right >= angles.length) return Double.NaN;
        double first = angles[left];
        double second = angles[right];
        while (second - first > Math.PI / 2.0) second -= Math.PI;
        while (second - first < -Math.PI / 2.0) second += Math.PI;
        double span = rows.get(right).referenceChainageMeters()
                - rows.get(left).referenceChainageMeters();
        double fraction = (rows.get(center).referenceChainageMeters()
                - rows.get(left).referenceChainageMeters()) / span;
        return first + fraction * (second - first);
    }

    static boolean straightCorridorCertificate(int center, List<FrozenRefitMesh.Row> rows,
            List<FrozenProfile> profiles, double[] angles, double sourcePitchMeters) {
        return straightCorridorCertificate(center, rows, profiles, angles, sourcePitchMeters,
                CancellationProbe.NONE);
    }

    private static boolean straightCorridorCertificate(int center,
            List<FrozenRefitMesh.Row> rows, List<FrozenProfile> profiles, double[] angles,
            double sourcePitchMeters, CancellationProbe cancellation) {
        double centerChainage = rows.get(center).referenceChainageMeters();
        return straightWindowCertificate(centerChainage, 6.0, rows, profiles, angles,
                sourcePitchMeters, cancellation)
                && straightWindowCertificate(centerChainage, 10.0, rows, profiles, angles,
                        sourcePitchMeters, cancellation);
    }

    private static boolean straightWindowCertificate(double centerChainage, double windowMeters,
            List<FrozenRefitMesh.Row> rows, List<FrozenProfile> profiles, double[] angles,
            double sourcePitchMeters, CancellationProbe cancellation) {
        WindowData window = windowData(centerChainage, windowMeters, rows, profiles, angles,
                cancellation);
        if (window.leftSupportedMeters() + 1.0e-9 < 0.5 * windowMeters
                || window.rightSupportedMeters() + 1.0e-9 < 0.5 * windowMeters) {
            return false;
        }
        MetricPoint a = window.samples().get(0).center();
        MetricPoint b = window.samples().get(window.samples().size() - 1).center();
        if (a == null || b == null) return false;
        double dx = b.xMeters() - a.xMeters();
        double dy = b.yMeters() - a.yMeters();
        double length = Math.hypot(dx, dy);
        if (!(length > 0.0)) return false;
        double ux = dx / length;
        double uy = dy / length;
        double lineBearing = Math.atan2(uy, ux);
        double radius = 0.5 * sourcePitchMeters;
        double endpointBearingUncertainty = Math.asin(Math.min(1.0,
                2.0 * radius / windowMeters));
        double previousWitness = Double.NEGATIVE_INFINITY;
        double previousOrigin = Double.NEGATIVE_INFINITY;
        for (WindowSample sample : window.samples()) {
            cancellation.checkpoint();
            if (!sample.measured() || sample.center() == null || sample.origin() == null) return false;
            double relativeX = sample.center().xMeters() - a.xMeters();
            double relativeY = sample.center().yMeters() - a.yMeters();
            double projection = relativeX * ux + relativeY * uy;
            double residual = Math.abs(relativeX * uy - relativeY * ux);
            if (residual > radius + 1.0e-9) return false;
            double allowance = Math.sqrt(Math.max(0.0, radius * radius - residual * residual));
            double witness = Math.max(previousWitness, projection - allowance);
            if (witness > projection + allowance + 1.0e-9) return false;
            previousWitness = witness;
            double originX = sample.origin().xMeters() - a.xMeters();
            double originY = sample.origin().yMeters() - a.yMeters();
            double originProjection = originX * ux + originY * uy;
            if (originProjection + 1.0e-9 < previousOrigin) return false;
            previousOrigin = originProjection;
            for (FrozenProfile profile : sample.profileContributors()) {
                if (profile.orientationModes().size() != 1
                        || !(profile.orientationCertainty() > 0.0)
                        || profile.orientationModes().get(0).distanceTo(lineBearing)
                                > endpointBearingUncertainty + 1.0e-12) {
                    return false;
                }
            }
        }
        return true;
    }

    private static MetricPoint centerPoint(FrozenProfile profile) {
        double offset = 0.5 * (profile.coreMinimumMeters() + profile.coreMaximumMeters());
        return new MetricPoint(profile.origin().xMeters() + offset * profile.normal().xMeters(),
                profile.origin().yMeters() + offset * profile.normal().yMeters());
    }

    private static MetricPoint interpolate(MetricPoint first, MetricPoint second, double fraction) {
        return new MetricPoint(first.xMeters() + fraction * (second.xMeters() - first.xMeters()),
                first.yMeters() + fraction * (second.yMeters() - first.yMeters()));
    }

    private record WindowSample(double chainageMeters, double angleRadians, MetricPoint center,
            MetricPoint origin, boolean measured, List<FrozenProfile> profileContributors) {
        private WindowSample {
            profileContributors = List.copyOf(profileContributors);
        }

        private static WindowSample missing(double chainageMeters) {
            return new WindowSample(chainageMeters, 0.0, null, null, false, List.of());
        }
    }

    private record WindowData(List<WindowSample> samples, double leftSupportedMeters,
            double rightSupportedMeters) { }

    private record Regression(boolean measured, double intercept, double slope) {
        private static final Regression UNKNOWN = new Regression(false, 0.0, 0.0);
    }

    private record Fit(boolean measured, double slope) {
        private static final Fit UNKNOWN = new Fit(false, 0.0);
    }
}
