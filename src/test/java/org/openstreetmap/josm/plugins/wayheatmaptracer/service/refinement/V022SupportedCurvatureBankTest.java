package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ImageOrientationSupport;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField.FrozenProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.ImageCostField.FrozenSupport;

class V022SupportedCurvatureBankTest {
    @Test
    void tenTwentyCompatibilityUsesItsActualPhysicalWindowLengths() throws Exception {
        assertFalse(compatible(0.020, 10.0, 0.040, 20.0));
    }

    @Test
    void physicalQuadratureDoesNotGiveInsertedRowsExtraStatisticalMass() throws Exception {
        List<Double> base = new ArrayList<>();
        for (int value = -6; value <= 6; value++) base.add((double) value);
        List<Double> refined = new ArrayList<>(base);
        for (int index = 0; index < 100; index++) refined.add(-2.1 + 0.2 * index / 100.0);
        Collections.sort(refined);

        double baseSix = fit(6.0, base, 0.0, V022SupportedCurvatureBankTest::cubic).slope();
        double refinedSix = fit(6.0, refined, 0.0,
                value -> piecewiseCubic(base, value)).slope();
        double baseTen = fit(10.0, base, 0.0, V022SupportedCurvatureBankTest::cubic).slope();
        double refinedTen = fit(10.0, refined, 0.0,
                value -> piecewiseCubic(base, value)).slope();

        assertTrue(Math.abs(baseSix - refinedSix) < 1.0e-10,
                "6m changed from " + baseSix + " to " + refinedSix);
        assertTrue(Math.abs(baseTen - refinedTen) < 1.0e-10,
                "10m changed from " + baseTen + " to " + refinedTen);
    }

    @Test
    void exactWindowBoundariesAreInterpolatedWithinObservedCells() throws Exception {
        List<Double> chainage = new ArrayList<>();
        for (int index = 0; index <= 16; index++) chainage.add(0.75 * index);
        chainage.add(6.063);
        Collections.sort(chainage);

        FitView fit = fit(6.0, chainage, 6.063, value -> 0.01 * value);

        assertTrue(fit.measured());
        assertTrue(Math.abs(fit.slope() - 0.01) < 1.0e-12);
    }

    @Test
    void exactWindowInterpolationNeverCrossesMissingSupport() throws Exception {
        List<Double> chainage = new ArrayList<>();
        for (int index = 0; index <= 16; index++) chainage.add(0.75 * index);
        chainage.add(6.063);
        Collections.sort(chainage);

        FitView fit = fit(6.0, chainage, 6.063, value ->
                value == 3.0 || value == 3.75 ? Double.NaN : 0.01 * value);

        assertFalse(fit.measured());
    }

    @Test
    void straightCertificateRejectsMissingCompetingBentAndFoldedEvidence() {
        List<FrozenRefitMesh.Row> rows = new ArrayList<>();
        List<FrozenProfile> straight = new ArrayList<>();
        double[] angles = new double[13];
        for (int index = 0; index < angles.length; index++) {
            double x = index - 6.0;
            rows.add(new FrozenRefitMesh.Row(index, index, 0.0, index));
            straight.add(profileAt(new MetricPoint(x, 0.0), 0.0, FrozenSupport.MEASURED, 1));
        }
        assertTrue(SupportedCurvatureBank.straightCorridorCertificate(
                6, rows, straight, angles, 1.5));

        List<FrozenProfile> missing = new ArrayList<>(straight);
        missing.set(6, profileAt(new MetricPoint(0.0, 0.0), 0.0, FrozenSupport.MISSING, 0));
        double[] missingAngles = angles.clone();
        missingAngles[6] = Double.NaN;
        assertFalse(SupportedCurvatureBank.straightCorridorCertificate(
                6, rows, missing, missingAngles, 1.5));

        List<FrozenProfile> competing = new ArrayList<>(straight);
        competing.set(6, profileAt(new MetricPoint(0.0, 0.0), 0.0, FrozenSupport.MEASURED, 2));
        assertFalse(SupportedCurvatureBank.straightCorridorCertificate(
                6, rows, competing, angles, 1.5));

        List<FrozenProfile> bent = new ArrayList<>();
        for (int index = 0; index < angles.length; index++) {
            double x = index - 6.0;
            bent.add(profileAt(new MetricPoint(x, 0.12 * x * x), 0.0,
                    FrozenSupport.MEASURED, 1));
        }
        assertFalse(SupportedCurvatureBank.straightCorridorCertificate(
                6, rows, bent, angles, 1.5));

        List<FrozenProfile> folded = new ArrayList<>(straight);
        folded.set(7, profileAt(new MetricPoint(-2.0, 0.0), 0.0, FrozenSupport.MEASURED, 1));
        assertFalse(SupportedCurvatureBank.straightCorridorCertificate(
                6, rows, folded, angles, 1.5));
    }

    @Test
    void clippedBoundaryRetainsContributorBranchAndHeadingAuthority() {
        assertTrue(boundaryTarget(FrozenSupport.MEASURED, 1, 0.0)
                == SupportedCurvatureBank.Status.AUTHORIZED_ZERO);
        assertFalse(boundaryTarget(FrozenSupport.MEASURED, 2, 0.0)
                == SupportedCurvatureBank.Status.AUTHORIZED_ZERO);
        assertFalse(boundaryTarget(FrozenSupport.MEASURED, 1, Math.PI / 2.0)
                == SupportedCurvatureBank.Status.AUTHORIZED_ZERO);
        assertFalse(boundaryTarget(FrozenSupport.MISSING, 0, 0.0)
                == SupportedCurvatureBank.Status.AUTHORIZED_ZERO);
    }

    @Test
    void physicalWindowLookupDoesNotRescanDistantMeshRows() throws Exception {
        long small = countedWindowReads(128);
        long large = countedWindowReads(256);

        assertTrue(small < 30_000, "128-row lookup reads=" + small);
        assertTrue(large < 80_000, "256-row lookup reads=" + large);
        assertTrue(large < 3 * small, "growth " + small + " -> " + large);
    }

    private static FitView fit(double window, List<Double> chainage, double center,
            java.util.function.DoubleUnaryOperator orientation) throws Exception {
        List<FrozenRefitMesh.Row> rows = new ArrayList<>();
        List<FrozenProfile> profiles = new ArrayList<>();
        double[] angles = new double[chainage.size()];
        int centerIndex = -1;
        for (int index = 0; index < chainage.size(); index++) {
            double value = chainage.get(index);
            rows.add(new FrozenRefitMesh.Row(index, index, 0.0, value));
            angles[index] = orientation.applyAsDouble(value);
            profiles.add(profile(value, angles[index]));
            if (value == center) centerIndex = index;
        }
        Method method = SupportedCurvatureBank.class.getDeclaredMethod("fitWindow", int.class,
                double.class, List.class, List.class, double[].class);
        method.setAccessible(true);
        Object result = method.invoke(null, centerIndex, window, rows, profiles, angles);
        Method measured = result.getClass().getDeclaredMethod("measured");
        Method slope = result.getClass().getDeclaredMethod("slope");
        measured.setAccessible(true);
        slope.setAccessible(true);
        return new FitView((boolean) measured.invoke(result), (double) slope.invoke(result));
    }

    private static boolean compatible(double firstSlope, double firstWindow,
            double secondSlope, double secondWindow) throws Exception {
        try {
            Method method = SupportedCurvatureBank.class.getDeclaredMethod("compatible",
                    double.class, double.class, double.class, double.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, firstSlope, firstWindow, secondSlope, secondWindow);
        } catch (NoSuchMethodException exception) {
            Method method = SupportedCurvatureBank.class.getDeclaredMethod("compatible",
                    double.class, double.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, firstSlope, secondSlope);
        }
    }

    private static FrozenProfile profile(double chainage, double angle) {
        boolean measured = Double.isFinite(angle);
        double normalized = measured ? ImageOrientationSupport.normalize(angle) : 0.0;
        List<ImageOrientationSupport.AngularMode> modes = measured
                ? List.of(new ImageOrientationSupport.AngularMode(normalized, normalized, normalized, 1.0))
                : List.of();
        return new FrozenProfile(measured ? FrozenSupport.MEASURED : FrozenSupport.MISSING,
                new MetricPoint(chainage, 0.0), new MetricPoint(0.0, 1.0), -0.1, 0.1,
                0.5, 0.0, 1.0, new double[] {-1.0, 1.0}, new double[] {0.0, 0.0},
                modes, normalized, measured ? 1.0 : 0.0);
    }

    private static FrozenProfile profileAt(MetricPoint origin, double angle,
            FrozenSupport support, int modeCount) {
        double normalized = ImageOrientationSupport.normalize(angle);
        List<ImageOrientationSupport.AngularMode> modes = modeCount == 0 ? List.of()
                : modeCount == 1
                        ? List.of(new ImageOrientationSupport.AngularMode(
                                normalized, normalized, normalized, 1.0))
                        : List.of(
                                new ImageOrientationSupport.AngularMode(
                                        normalized, normalized, normalized, 1.0),
                                new ImageOrientationSupport.AngularMode(Math.PI / 2.0,
                                        Math.PI / 2.0, Math.PI / 2.0, 0.8));
        return new FrozenProfile(support, origin, new MetricPoint(0.0, 1.0), -0.1, 0.1,
                0.75, 0.0, 1.0, new double[] {-1.0, 1.0}, new double[] {0.0, 0.0},
                modes, normalized, modeCount == 0 ? 0.0 : 1.0);
    }

    private static SupportedCurvatureBank.Status boundaryTarget(
            FrozenSupport contributorSupport, int contributorModes, double contributorAngle) {
        List<MetricPoint> controls = new ArrayList<>();
        for (int index = 0; index < 14; index++) {
            double chainage = index == 7 ? 6.25 : index > 7 ? index - 1.0 : index;
            controls.add(new MetricPoint(chainage, 0.0));
        }
        FrozenRefitMesh mesh = FrozenRefitMesh.build(controls, java.util.Set.of(7), 1.0,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE);
        List<FrozenProfile> profiles = new ArrayList<>();
        for (FrozenRefitMesh.Row row : mesh.rows()) {
            double x = row.referenceChainageMeters();
            double relative = x - 6.25;
            double angle = 0.001 * (relative * relative * relative - 10.0 * relative);
            profiles.add(profileAt(new MetricPoint(x, 0.0), angle, FrozenSupport.MEASURED, 1));
        }
        int contributor = java.util.stream.IntStream.range(0, mesh.rows().size())
                .filter(index -> Math.abs(mesh.rows().get(index).referenceChainageMeters() - 1.0) < 1.0e-12)
                .findFirst().orElseThrow();
        profiles.set(contributor, profileAt(new MetricPoint(1.0, 0.0), contributorAngle,
                contributorSupport, contributorModes));
        return SupportedCurvatureBank.build(mesh, profiles, 1.5,
                org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE)
                .targets().get(6).status();
    }

    private static long countedWindowReads(int rowCount) throws Exception {
        CountingRows rows = new CountingRows(rowCount);
        List<FrozenProfile> profiles = new ArrayList<>();
        for (int index = 0; index < rowCount; index++) {
            profiles.add(profile(0.5 * index, 0.0));
        }
        double[] angles = new double[rowCount];
        Method method = SupportedCurvatureBank.class.getDeclaredMethod("windowData", double.class,
                double.class, List.class, List.class, double[].class);
        method.setAccessible(true);
        for (int center = 1; center < rowCount - 1; center++) {
            for (double window : List.of(6.0, 10.0, 20.0)) {
                method.invoke(null, 0.5 * center, window, rows, profiles, angles);
            }
        }
        return rows.reads;
    }

    private static final class CountingRows extends AbstractList<FrozenRefitMesh.Row> {
        private final int size;
        private long reads;

        private CountingRows(int size) {
            this.size = size;
        }

        @Override public FrozenRefitMesh.Row get(int index) {
            reads++;
            return new FrozenRefitMesh.Row(index, index, 0.0, 0.5 * index);
        }

        @Override public int size() {
            return size;
        }
    }

    private static double cubic(double value) {
        return 0.001 * value * value * value;
    }

    private static double piecewiseCubic(List<Double> base, double value) {
        int exact = Collections.binarySearch(base, value);
        if (exact >= 0) return cubic(value);
        int upper = -exact - 1;
        double first = base.get(upper - 1);
        double second = base.get(upper);
        double fraction = (value - first) / (second - first);
        return cubic(first) + fraction * (cubic(second) - cubic(first));
    }

    private record FitView(boolean measured, double slope) { }
}
