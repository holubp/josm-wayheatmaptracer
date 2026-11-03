package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;

/** Independent straight image ridge sampled through regular or folded source-normal charts. */
final class PhysicalGeometryFixture {
    static final double CENTER = 64.0;
    static final double DISPLACEMENT = 9.7;
    static final double NATIVE_PITCH = 1.556;
    static final double PRESERVATION_PIXEL_PITCH = 1.0;

    private PhysicalGeometryFixture() { }

    /** One fixture pixel represents one metre; image truth is y=64 independently of source geometry. */
    static List<RenderedHeatmapSampler.CrossSectionProfile> straightRidgeThroughParabola(double radius) {
        return r4SyntheticFrame(radius).profiles().subList(1, 18);
    }

    /** Full synthetic source and untrimmed profiles used to prove the R4 crop mapping. */
    static R4SyntheticFrame r4SyntheticFrame(double radius) {
        BufferedImage raster = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        double[] scalar = new double[128 * 128];
        boolean[] valid = new boolean[scalar.length];
        java.util.Arrays.fill(valid, true);
        for (int y = 0; y < raster.getHeight(); y++) {
            int value = (int) Math.round(255.0 * Math.exp(-0.5 * Math.pow((y - CENTER) / 0.8, 2)));
            for (int x = 0; x < raster.getWidth(); x++) {
                raster.setRGB(x, y, 0xff000000 | value << 16 | value << 8 | value);
                scalar[y * raster.getWidth() + x] = value / 255.0;
            }
        }
        List<Point2D.Double> anchors = new ArrayList<>();
        // Pad the range so every observed profile has the same central-secant normal rule.
        for (int j = -9; j <= 9; j++) {
            anchors.add(new Point2D.Double(CENTER + j,
                    CENTER - DISPLACEMENT + j * j / (2.0 * radius)));
        }
        var profiles = new RenderedHeatmapSampler().sampleMultiScaleProfilesOnScaledRaster(
                raster, anchors, 25, 1, "hot", 1.0, 1.0,
            IntensitySamplingMode.DIRECT_VALUE, 1.0).levelZeroProfiles();
        var grid = new ScalarEvidenceField(128, 128, scalar, valid,
            new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY, "R4 synthetic raster",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        return new R4SyntheticFrame(profiles, anchors, grid);
    }

    record R4SyntheticFrame(List<RenderedHeatmapSampler.CrossSectionProfile> profiles,
            List<Point2D.Double> anchors, ScalarEvidenceField fullScalarGrid) { }

    static Point2D.Double strongestObservedPoint(RenderedHeatmapSampler.CrossSectionProfile profile) {
        var peak = profile.peaks().stream().filter(p -> !p.syntheticCenter())
                .max(java.util.Comparator.comparingDouble(RenderedHeatmapSampler.CrossSectionPeak::intensity))
                .orElseThrow();
        return new Point2D.Double(profile.anchorScreen().x + profile.normalScreen().x * peak.offsetPx(),
                profile.anchorScreen().y + profile.normalScreen().y * peak.offsetPx());
    }

    /** Paints an independent 150-degree arc opposite the source, within the explicit 25m search. */
    static List<RenderedHeatmapSampler.CrossSectionProfile> supportedOppositeArcProfiles() {
        double radius = 4 * NATIVE_PITCH;
        double first = Math.toRadians(105), last = Math.toRadians(255);
        BufferedImage raster = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                double dx = x - CENTER, dy = y - CENTER;
                double angle = Math.atan2(dy, dx);
                if (angle < 0) angle += 2 * Math.PI;
                double distance = angle >= first && angle <= last ? Math.abs(Math.hypot(dx, dy) - radius)
                        : Math.min(Math.hypot(dx - radius * Math.cos(first), dy - radius * Math.sin(first)),
                                Math.hypot(dx - radius * Math.cos(last), dy - radius * Math.sin(last)));
                int value = (int) Math.round(255 * Math.exp(-0.5 * distance * distance / (0.7 * 0.7)));
                raster.setRGB(x, y, 0xff000000 | value << 16 | value << 8 | value);
            }
        }
        var anchors = new ArrayList<Point2D.Double>();
        for (int i = 0; i <= 40; i++) {
            double a = Math.toRadians(-75 + 150.0 * i / 40);
            anchors.add(new Point2D.Double(CENTER + 8 * NATIVE_PITCH * Math.cos(a),
                    CENTER + 8 * NATIVE_PITCH * Math.sin(a)));
        }
        return new RenderedHeatmapSampler().sampleMultiScaleProfilesOnScaledRaster(raster, anchors,
                25, 1, "hot", 1, 1, IntensitySamplingMode.DIRECT_VALUE, NATIVE_PITCH).levelZeroProfiles();
    }

    /** Builds two independent thin circular strands with a quantized dark gap at the sampling frame. */
    static ParallelFrame parallelFrame(double amplitude) {
        int center = 80;
        double innerRadius = 24.0;
        double sourceRadius = 28.0;
        double outerRadius = 32.0;
        BufferedImage raster = parallelRaster(amplitude, center, innerRadius, outerRadius);
        List<Point2D.Double> anchors = new ArrayList<>();
        List<Point2D.Double> inner = new ArrayList<>();
        List<Point2D.Double> outer = new ArrayList<>();
        int profileCount = 81;
        for (int i = 0; i < profileCount; i++) {
            double angle = Math.toRadians(-40.0 + 80.0 * i / (profileCount - 1.0));
            anchors.add(new Point2D.Double(center + sourceRadius * Math.cos(angle),
                center + sourceRadius * Math.sin(angle)));
            inner.add(new Point2D.Double(center + innerRadius * Math.cos(angle),
                center + innerRadius * Math.sin(angle)));
            outer.add(new Point2D.Double(center + outerRadius * Math.cos(angle),
                center + outerRadius * Math.sin(angle)));
        }
        MultiScaleProfileSet profiles = new RenderedHeatmapSampler().sampleMultiScaleProfilesOnScaledRaster(
            raster, anchors, 12, 1, "hot", 1.0, 1.0, IntensitySamplingMode.DIRECT_VALUE,
            PRESERVATION_PIXEL_PITCH);
        return new ParallelFrame(raster, profiles, inner, outer, PRESERVATION_PIXEL_PITCH);
    }

    static List<RenderedHeatmapSampler.CrossSectionProfile> parallelFrameProfiles() {
        return parallelFrame(0.95).profileSet().levelZeroProfiles();
    }

    /** Resamples equivalent curved source polylines before image sampling, with one off-grid collinear insertion. */
    static CollinearInsertionFrame collinearInsertionFrame() {
        List<EastNorth> source = new ArrayList<>();
        for (double x : new double[] {20.0, 40.0, 64.0, 88.0, 108.0}) {
            source.add(new EastNorth(x, sourceCurveY(x)));
        }
        List<EastNorth> inserted = new ArrayList<>();
        for (int i = 0; i < source.size() - 1; i++) {
            EastNorth start = source.get(i);
            EastNorth end = source.get(i + 1);
            inserted.add(start);
            inserted.add(new EastNorth(start.east() + (end.east() - start.east()) * 0.37,
                start.north() + (end.north() - start.north()) * 0.37));
        }
        inserted.add(source.get(source.size() - 1));

        List<EastNorth> resampled = PolylineMath.resampleBySpacing(source, 2.0);
        List<EastNorth> insertedResampled = PolylineMath.resampleBySpacing(inserted, 2.0);
        BufferedImage raster = curvedRidgeRaster();
        List<Point2D.Double> resampledAnchors = toRasterAnchors(resampled);
        List<Point2D.Double> insertedAnchors = toRasterAnchors(insertedResampled);
        RenderedHeatmapSampler sampler = new RenderedHeatmapSampler();
        MultiScaleProfileSet originalProfiles = sampler.sampleMultiScaleProfilesOnScaledRaster(
            raster, resampledAnchors, 12, 1, "hot", 1.0, 1.0, IntensitySamplingMode.DIRECT_VALUE,
            PRESERVATION_PIXEL_PITCH);
        MultiScaleProfileSet insertedProfiles = sampler.sampleMultiScaleProfilesOnScaledRaster(
            raster, insertedAnchors, 12, 1, "hot", 1.0, 1.0, IntensitySamplingMode.DIRECT_VALUE,
            PRESERVATION_PIXEL_PITCH);
        return new CollinearInsertionFrame(raster, originalProfiles, insertedProfiles, resampled,
            insertedResampled, PRESERVATION_PIXEL_PITCH);
    }

    static double independentCurvedRidgeY(double x) {
        return sourceCurveY(x) + 4.5 * Math.sin(Math.PI * (x - 20.0) / 88.0);
    }

    static double distanceToParallelStrand(Point2D point, int profileIndex, int strand, ParallelFrame frame) {
        Point2D.Double expected = (strand == 0 ? frame.innerRidge() : frame.outerRidge()).get(profileIndex);
        return point.distance(expected);
    }

    private static double sourceCurveY(double x) {
        return 50.0 + 0.005 * (x - 64.0) * (x - 64.0);
    }

    private static BufferedImage parallelRaster(double amplitude, int center, double innerRadius, double outerRadius) {
        BufferedImage raster = new BufferedImage(160, 160, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < raster.getHeight(); y++) {
            for (int x = 0; x < raster.getWidth(); x++) {
                double radius = Math.hypot(x - center, y - center);
                double inner = amplitude * Math.exp(-0.5 * Math.pow((radius - innerRadius) / 0.65, 2.0));
                double outer = amplitude * Math.exp(-0.5 * Math.pow((radius - outerRadius) / 0.65, 2.0));
                raster.setRGB(x, y, grayscale(Math.max(inner, outer)));
            }
        }
        return raster;
    }

    private static BufferedImage curvedRidgeRaster() {
        BufferedImage raster = new BufferedImage(128, 96, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < raster.getHeight(); y++) {
            for (int x = 0; x < raster.getWidth(); x++) {
                double value = 0.95 * Math.exp(-0.5 * Math.pow((y - independentCurvedRidgeY(x)) / 0.65, 2.0));
                raster.setRGB(x, y, grayscale(value));
            }
        }
        return raster;
    }

    private static int grayscale(double value) {
        int sample = (int) Math.round(Math.max(0.0, Math.min(1.0, value)) * 255.0);
        return 0xff000000 | sample << 16 | sample << 8 | sample;
    }

    private static List<Point2D.Double> toRasterAnchors(List<EastNorth> points) {
        return points.stream().map(point -> new Point2D.Double(point.east(), point.north())).toList();
    }

    record ParallelFrame(
        BufferedImage raster,
        MultiScaleProfileSet profileSet,
        List<Point2D.Double> innerRidge,
        List<Point2D.Double> outerRidge,
        double sourcePixelPitch
    ) {
        ParallelFrame {
            innerRidge = List.copyOf(innerRidge);
            outerRidge = List.copyOf(outerRidge);
        }
    }

    record CollinearInsertionFrame(
        BufferedImage raster,
        MultiScaleProfileSet originalProfiles,
        MultiScaleProfileSet insertedProfiles,
        List<EastNorth> originalResampledSource,
        List<EastNorth> insertedResampledSource,
        double sourcePixelPitch
    ) {
        CollinearInsertionFrame {
            originalResampledSource = List.copyOf(originalResampledSource);
            insertedResampledSource = List.copyOf(insertedResampledSource);
        }
    }
}
