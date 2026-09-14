package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceCorrelationGroup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceFieldLineage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeographicPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.LocalMetricFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ScalarEvidenceField;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.MultiScaleProfileSet.ScaleProfileLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Contract tests for the bounded detached scalar input prerequisite of corridor-aware Engine A. */
class DetachedScalarProfileSamplerTest {
    private static final String FIELD_NAME = "scalar";

    @Test
    void detachedScalarProfilesMatchLegacyReferenceAndFeedActualTracker() {
        int width = 480;
        int height = 256;
        double[] values = filledValues(width, height, 5.0 / 255.0);
        BufferedImage raster = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        fillRaster(raster, 0xFF050505);
        for (int y = 130; y <= 134; y++) {
            for (int x = 0; x < width; x++) {
                values[y * width + x] = y == 132 ? 1.0 : 128.0 / 255.0;
                raster.setRGB(x, y, y == 132 ? 0xFFFFFFFF : 0xFF808080);
            }
        }
        LocalMetricFrame frame = frame();
        RasterMetricTransform transform = RasterMetricTransform.metricGrid(
            new MetricPoint(0.0, 0.0), 0.5, 0.0, 0.0, 0.5);
        EvidenceResolution resolution = EvidenceResolution.nativeSource(2.0, 1.0).resampledTo(0.5);
        MetricRegion decision = rasterRectangle(transform, 2.0, 64.0, 478.0, 192.0);
        EvidenceSnapshot evidence = snapshot(width, height, frame, transform, resolution,
            decision, values, allTrue(width * height), allTrue((width - 1) * (height - 1)));
        List<DetachedProfileSamplingLocation> detachedAnchors = new ArrayList<>();
        List<ProfileSamplingAnchor> legacyAnchors = new ArrayList<>();
        for (int index = 0; index < 31; index++) {
            RasterPoint rasterPoint = new RasterPoint(80.0 + index * 10.0, 128.0);
            MetricPoint metric = transform.pixelCenterToMetric(rasterPoint.x(), rasterPoint.y());
            double chainage = index * 5.0;
            detachedAnchors.add(new DetachedProfileSamplingLocation(
                frame.toGeographic(metric), metric, rasterPoint, frame, transform, chainage));
            legacyAnchors.add(new ProfileSamplingAnchor(
                new EastNorth(1000.0 + index, 2000.0), rasterPoint.x(), rasterPoint.y(), chainage));
        }

        MultiScaleProfileSet actual = new DetachedScalarProfileSampler().sample(
            evidence, FIELD_NAME, detachedAnchors, 8.0, 0.5, CancellationProbe.NONE);
        MultiScaleProfileSet legacy = new RenderedHeatmapSampler().sampleMultiScaleProfilesOnAnchors(
            raster, legacyAnchors, 16, 1, "hot", 1.0, 1.0,
            IntensitySamplingMode.DIRECT_VALUE, 4.0);

        GaussianIntensityPyramid strictPyramid = GaussianIntensityPyramid.build(
            ScalarIntensityField.fromEvidence(evidence.fields().get(FIELD_NAME), () -> { }), 16);
        for (ScaleProfileLevel level : legacy.levels()) {
            ScalarIntensityField filtered = strictPyramid.levels().stream()
                .filter(candidate -> candidate.reduction() == level.reduction())
                .findFirst().orElseThrow().field();
            for (CrossSectionProfile profile : level.profiles()) {
                for (IntensitySample sample : profile.intensitySamples()) {
                    double x = profile.anchorScreen().x + profile.normalScreen().x * sample.offsetPx();
                    double y = profile.anchorScreen().y + profile.normalScreen().y * sample.offsetPx();
                    assertTrue(Double.isFinite(filtered.sampleStrict(x, y)),
                        "parity requires complete filtered interpolation support at every sample");
                    assertTrue(evidence.routePositionAuthorized(transform.pixelCenterToMetric(x, y)),
                        "parity requires every sample within the decision region");
                }
            }
        }

        assertEquals(List.of(1, 8, 16), actual.levels().stream()
            .map(ScaleProfileLevel::reduction).toList());
        assertEquivalentProfileEvidence(legacy, actual);
        assertEquals(25.0, actual.levelZeroProfiles().get(5).cumulativeGroundDistanceMeters(), 0.0);
        assertThrows(UnsupportedOperationException.class, () -> actual.levelZeroProfiles().get(0).anchor());

        CorridorAwareTracker.TrackingResult tracked = new CorridorAwareTracker().trackDetailed(
            actual, resolution.effectivePitchMeters() / resolution.outputRasterPitchMeters(),
            JunctionContext.empty());
        assertFalse(tracked.candidates().isEmpty(), "actual corridor tracker must consume adapter output");
    }

    @Test
    void supportsRotatedMetricRasterWithoutProjectedOrRgbInput() {
        int width = 100;
        int height = 100;
        double pitch = 0.75;
        double cosine = Math.sqrt(0.5);
        RasterMetricTransform transform = RasterMetricTransform.metricGrid(new MetricPoint(40.0, 40.0),
            pitch * cosine, pitch * cosine, -pitch * cosine, pitch * cosine);
        LocalMetricFrame frame = frame();
        double[] values = filledValues(width, height, 0.03125);
        for (int x = 0; x < width; x++) {
            values[52 * width + x] = 0.7311234;
        }
        EvidenceSnapshot evidence = snapshot(width, height, frame, transform,
            EvidenceResolution.nativeSource(1.5, pitch).resampledTo(pitch),
            rasterRectangle(transform, 10.0, 20.0, 90.0, 80.0), values,
            allTrue(width * height), allTrue((width - 1) * (height - 1)));
        List<DetachedProfileSamplingLocation> anchors = anchors(frame, transform,
            List.of(new RasterPoint(20.0, 50.0), new RasterPoint(40.0, 50.0),
                new RasterPoint(60.0, 50.0), new RasterPoint(80.0, 50.0)), 15.0);

        MultiScaleProfileSet profiles = new DetachedScalarProfileSampler().sample(
            evidence, FIELD_NAME, anchors, 6.0, 0.75, CancellationProbe.NONE);

        IntensitySample offsetTwo = sampleAt(profiles.levelZeroProfiles().get(1), 2.0);
        assertTrue(offsetTwo.insideRaster());
        assertEquals((double) (float) 0.7311234, offsetTwo.nativeIntensity(), 0.0);
        assertFalse(profiles.levelZeroProfiles().get(1).projectedLateralTransform().isPresent());
    }

    @Test
    void explicitFractionalCellHoleRemainsUnsupportedThroughAllPyramidLevels() {
        int width = 100;
        int height = 100;
        RasterMetricTransform transform = RasterMetricTransform.metricGrid(
            new MetricPoint(0.0, 0.0), 1.0, 0.0, 0.0, 1.0);
        LocalMetricFrame frame = frame();
        boolean[] interpolation = allTrue((width - 1) * (height - 1));
        interpolation[50 * (width - 1) + 40] = false;
        EvidenceSnapshot evidence = snapshot(width, height, frame, transform,
            EvidenceResolution.nativeSource(1.0, 1.0),
            rasterRectangle(transform, 10.0, 10.0, 90.0, 90.0),
            filledValues(width, height, 0.7), allTrue(width * height), interpolation);
        List<DetachedProfileSamplingLocation> anchors = anchors(frame, transform,
            List.of(new RasterPoint(40.5, 50.5), new RasterPoint(70.5, 50.5)), 30.0);

        MultiScaleProfileSet profiles = new DetachedScalarProfileSampler().sample(
            evidence, FIELD_NAME, anchors, 4.0, 1.0, CancellationProbe.NONE);

        assertEquals(List.of(1, 2, 4), profiles.levels().stream()
            .map(ScaleProfileLevel::reduction).toList());
        for (ScaleProfileLevel level : profiles.levels()) {
            assertFalse(sampleAt(level.profiles().get(0), 0.0).insideRaster(),
                "hole became direct support at L" + level.level());
            assertTrue(sampleAt(level.profiles().get(1), 0.0).insideRaster(),
                "adjacent complete control was lost at L" + level.level());
        }
    }

    @Test
    void sampledCellHoleCannotCreateAnUnsupportedPeak() {
        int width = 100;
        int height = 100;
        RasterMetricTransform transform = RasterMetricTransform.metricGrid(
            new MetricPoint(0.0, 0.0), 1.0, 0.0, 0.0, 1.0);
        LocalMetricFrame frame = frame();
        double[] values = filledValues(width, height, 0.05);
        for (int y = 47; y <= 54; y++) {
            Arrays.fill(values, y * width, (y + 1) * width, 0.9);
        }
        boolean[] interpolation = allTrue((width - 1) * (height - 1));
        interpolation[50 * (width - 1) + 40] = false;
        EvidenceSnapshot evidence = snapshot(width, height, frame, transform,
            EvidenceResolution.nativeSource(1.0, 1.0),
            rasterRectangle(transform, 10.0, 10.0, 90.0, 90.0),
            values, allTrue(width * height), interpolation);
        List<DetachedProfileSamplingLocation> locations = anchors(frame, transform,
            List.of(new RasterPoint(40.5, 50.5), new RasterPoint(70.5, 50.5)), 30.0);

        CrossSectionProfile profile = new DetachedScalarProfileSampler().sample(
            evidence, FIELD_NAME, locations, 8.0, 1.0, CancellationProbe.NONE)
            .levelZeroProfiles().get(0);

        ScalarEvidenceField source = evidence.fields().get(FIELD_NAME);
        assertTrue(profile.peaks().stream().allMatch(peak -> source.sampleBilinear(
            40.5, 50.5 + peak.offsetPx()).isPresent()));
    }

    @Test
    void unsampledCellHoleCannotCreateACompleteBandOrTrackerCenter() {
        int width = 100;
        int height = 100;
        RasterMetricTransform transform = RasterMetricTransform.metricGrid(
            new MetricPoint(0.0, 0.0), 1.0, 0.0, 0.0, 1.0);
        LocalMetricFrame frame = frame();
        double[] values = filledValues(width, height, 0.05);
        for (int y = 49; y <= 53; y++) {
            Arrays.fill(values, y * width, (y + 1) * width, 0.9);
        }
        boolean[] interpolation = allTrue((width - 1) * (height - 1));
        for (int x = 0; x < width - 1; x++) {
            interpolation[51 * (width - 1) + x] = false;
        }
        EvidenceSnapshot evidence = snapshot(width, height, frame, transform,
            EvidenceResolution.nativeSource(1.0, 1.0),
            rasterRectangle(transform, 10.0, 10.0, 90.0, 90.0),
            values, allTrue(width * height), interpolation);
        List<DetachedProfileSamplingLocation> locations = anchors(frame, transform,
            List.of(new RasterPoint(40.5, 50.0), new RasterPoint(70.5, 50.0)), 30.0);

        MultiScaleProfileSet profiles = new DetachedScalarProfileSampler().sample(
            evidence, FIELD_NAME, locations, 12.0, 4.0, CancellationProbe.NONE);
        CrossSectionProfile profile = profiles.levelZeroProfiles().get(0);
        ScalarEvidenceField source = evidence.fields().get(FIELD_NAME);
        assertTrue(profile.intensitySamples().stream().allMatch(sample -> source.sampleBilinear(
            40.5, 50.0 + sample.offsetPx()).isPresent()),
            "fixture requires valid sampled points around an unsupported interval");
        List<CorridorBand> bands = new CorridorExtractor().extract(0, profile, 1.0).bands();
        assertTrue(bands.stream().allMatch(band -> source.sampleBilinear(
            40.5, 50.0 + band.centerOffsetPx()).isPresent()));
        var tracked = new CorridorAwareTracker().trackDetailed(
            profiles, 1.0, JunctionContext.empty());
        assertTrue(tracked.candidates().stream().flatMap(candidate -> candidate.offsetsPx().stream())
            .allMatch(offset -> source.sampleBilinear(40.5, 50.0 + offset).isPresent()));
    }

    @Test
    void unsampledDecisionNotchCannotAuthorizeACompleteBandOrTrackerCenter() {
        int width = 100;
        int height = 100;
        RasterMetricTransform transform = RasterMetricTransform.metricGrid(
            new MetricPoint(0.0, 0.0), 1.0, 0.0, 0.0, 1.0);
        LocalMetricFrame frame = frame();
        double[] values = filledValues(width, height, 0.05);
        for (int y = 49; y <= 53; y++) {
            Arrays.fill(values, y * width, (y + 1) * width, 0.9);
        }
        MetricRegion decision = new MetricRegion(List.of(
            MetricRegion.rectangle(10.0, 10.0, 90.0, 50.5).polygons().get(0),
            MetricRegion.rectangle(10.0, 51.5, 90.0, 90.0).polygons().get(0),
            MetricRegion.rectangle(10.0, 10.0, 20.0, 90.0).polygons().get(0)));
        EvidenceSnapshot evidence = snapshot(width, height, frame, transform,
            EvidenceResolution.nativeSource(1.0, 1.0), decision, values,
            allTrue(width * height), allTrue((width - 1) * (height - 1)));
        List<DetachedProfileSamplingLocation> locations = anchors(frame, transform,
            List.of(new RasterPoint(40.5, 50.0), new RasterPoint(70.5, 50.0)), 30.0);

        MultiScaleProfileSet profiles = new DetachedScalarProfileSampler().sample(
            evidence, FIELD_NAME, locations, 12.0, 4.0, CancellationProbe.NONE);
        CrossSectionProfile profile = profiles.levelZeroProfiles().get(0);
        assertTrue(evidence.routePositionAuthorized(transform.pixelCenterToMetric(40.5, 50.0)));
        assertTrue(evidence.routePositionAuthorized(transform.pixelCenterToMetric(40.5, 52.0)));
        assertFalse(evidence.routeSegmentAuthorized(
            transform.pixelCenterToMetric(40.5, 50.0),
            transform.pixelCenterToMetric(40.5, 52.0)));
        assertTrue(sampleAt(profile, 0.0).nativeIntensity() > 0.8);
        assertTrue(sampleAt(profile, 2.0).standardFilteredIntensity() > 0.8,
            "decision rejection erased valid acquisition halo from filtering");
        List<CorridorBand> bands = new CorridorExtractor().extract(0, profile, 1.0).bands();
        assertTrue(bands.stream().allMatch(band -> evidence.routePositionAuthorized(
            transform.pixelCenterToMetric(40.5, 50.0 + band.centerOffsetPx()))));
        var tracked = new CorridorAwareTracker().trackDetailed(
            profiles, 1.0, JunctionContext.empty());
        assertTrue(tracked.candidates().stream().flatMap(candidate -> candidate.offsetsPx().stream())
            .allMatch(offset -> evidence.routePositionAuthorized(
                transform.pixelCenterToMetric(40.5, 50.0 + offset))));
    }

    @Test
    void fullyAuthorizedIntervalRetainsItsCompleteBandAndTrackerCenter() {
        int width = 100;
        int height = 100;
        RasterMetricTransform transform = RasterMetricTransform.metricGrid(
            new MetricPoint(0.0, 0.0), 1.0, 0.0, 0.0, 1.0);
        LocalMetricFrame frame = frame();
        double[] values = filledValues(width, height, 0.05);
        for (int y = 49; y <= 53; y++) {
            Arrays.fill(values, y * width, (y + 1) * width, 0.9);
        }
        EvidenceSnapshot evidence = snapshot(width, height, frame, transform,
            EvidenceResolution.nativeSource(1.0, 1.0),
            rasterRectangle(transform, 10.0, 10.0, 90.0, 90.0), values,
            allTrue(width * height), allTrue((width - 1) * (height - 1)));
        List<DetachedProfileSamplingLocation> locations = anchors(frame, transform,
            List.of(new RasterPoint(40.5, 50.0), new RasterPoint(70.5, 50.0)), 30.0);

        MultiScaleProfileSet profiles = new DetachedScalarProfileSampler().sample(
            evidence, FIELD_NAME, locations, 12.0, 4.0, CancellationProbe.NONE);
        List<CorridorBand> bands = new CorridorExtractor().extract(
            0, profiles.levelZeroProfiles().get(0), 1.0).bands();
        assertTrue(bands.stream().anyMatch(band -> band.boundaryCompleteness()
            == CorridorBand.BoundaryCompleteness.COMPLETE
            && Math.abs(band.centerOffsetPx() - 1.0) < 1.0e-9));
        var tracked = new CorridorAwareTracker().trackDetailed(
            profiles, 1.0, JunctionContext.empty());
        assertTrue(tracked.candidates().stream()
            .anyMatch(candidate -> candidate.offsetsPx().equals(List.of(1.0, 1.0))));
    }

    @Test
    void decisionRegionExcludesPositionEvidenceWhileHaloStillBuildsFilters() {
        int width = 80;
        int height = 80;
        RasterMetricTransform transform = RasterMetricTransform.metricGrid(
            new MetricPoint(0.0, 0.0), 1.0, 0.0, 0.0, 1.0);
        LocalMetricFrame frame = frame();
        double[] values = filledValues(width, height, 0.05);
        Arrays.fill(values, 45 * width, 46 * width, 1.0);
        MetricRegion decision = rasterRectangle(transform, 10.0, 37.0, 70.0, 43.0);
        EvidenceSnapshot evidence = snapshot(width, height, frame, transform,
            EvidenceResolution.nativeSource(1.0, 1.0), decision, values,
            allTrue(width * height), allTrue((width - 1) * (height - 1)));
        List<DetachedProfileSamplingLocation> anchors = anchors(frame, transform,
            List.of(new RasterPoint(20.0, 40.5), new RasterPoint(60.0, 40.5)), 40.0);

        MultiScaleProfileSet profiles = new DetachedScalarProfileSampler().sample(
            evidence, FIELD_NAME, anchors, 6.0, 1.0, CancellationProbe.NONE);

        for (ScaleProfileLevel level : profiles.levels()) {
            CrossSectionProfile profile = level.profiles().get(0);
            assertTrue(profile.anchorWithinRaster(), "anchor fell outside raster at L" + level.level());
            IntensitySample inside = sampleAt(profile, 2.0);
            assertTrue(inside.insideRaster());
            if (level.level() == 2) {
                List<Double> offsets = profile.intensitySamples().stream()
                    .map(IntensitySample::offsetPx).toList();
                assertEquals(List.of(-6.0, -2.0, 2.0, 6.0), offsets, "unexpected L2 offsets");
            }
            IntensitySample outOfDecisionWindowSample = sampleAt(profile, 6.0);
            assertFalse(outOfDecisionWindowSample.insideRaster(),
                "acquisition halo became route-position evidence at L" + level.level());
            if (level.level() > 0) {
                assertTrue(inside.nativeIntensity() > 0.051,
                    "coarse sample did not retain out-of-decision row 45 signal at L" + level.level());

            }
            assertTrue(profile.peaks().stream().allMatch(peak -> evidence.routePositionAuthorized(
                transform.pixelCenterToMetric(profile.anchorScreen().x + profile.normalScreen().x * peak.offsetPx(),
                    profile.anchorScreen().y + profile.normalScreen().y * peak.offsetPx()))));
        }
    }

    @Test
    void rejectsUnsupportedFramesMismatchedAnchorsAndNonmonotonicGroundChainage() {
        int width = 40;
        int height = 40;
        LocalMetricFrame frame = frame();
        RasterMetricTransform anisotropic = new RasterMetricTransform("anisotropic-test",
            RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER, new MetricPoint(0.0, 0.0),
            1.0, 0.0, 0.0, 2.0, 1.0);
        EvidenceSnapshot unsupported = snapshot(width, height, frame, anisotropic,
            EvidenceResolution.renderedOnly(1.0),
            rasterRectangle(anisotropic, 2.0, 2.0, 38.0, 38.0),
            filledValues(width, height, 0.5), allTrue(width * height),
            allTrue((width - 1) * (height - 1)));
        List<DetachedProfileSamplingLocation> unsupportedAnchors = anchors(frame, anisotropic,
            List.of(new RasterPoint(10.0, 20.0), new RasterPoint(30.0, 20.0)), 20.0);
        DetachedScalarProfileSampler sampler = new DetachedScalarProfileSampler();

        assertThrows(IllegalArgumentException.class, () -> sampler.sample(unsupported, FIELD_NAME,
            unsupportedAnchors, 3.0, 1.0, CancellationProbe.NONE));

        RasterMetricTransform supported = RasterMetricTransform.metricGrid(
            new MetricPoint(0.0, 0.0), 1.0, 0.0, 0.0, 1.0);
        EvidenceSnapshot evidence = snapshot(width, height, frame, supported,
            EvidenceResolution.nativeSource(1.0, 1.0),
            rasterRectangle(supported, 2.0, 2.0, 38.0, 38.0),
            filledValues(width, height, 0.5), allTrue(width * height),
            allTrue((width - 1) * (height - 1)));
        List<DetachedProfileSamplingLocation> reversed = List.of(
            detachedAt(frame, supported, new RasterPoint(10.0, 20.0), 5.0),
            detachedAt(frame, supported, new RasterPoint(30.0, 20.0), 4.0));
        assertThrows(IllegalArgumentException.class, () -> sampler.sample(evidence, FIELD_NAME,
            reversed, 3.0, 1.0, CancellationProbe.NONE));
        assertThrows(IllegalArgumentException.class, () -> sampler.sample(evidence, "missing",
            anchors(frame, supported, List.of(new RasterPoint(10.0, 20.0),
                new RasterPoint(30.0, 20.0)), 20.0), 3.0, 1.0, CancellationProbe.NONE));
    }

    @Test
    void admitsBudgetAndCancellationBeforeModernMaterialization() {
        int width = 40;
        int height = 40;
        LocalMetricFrame frame = frame();
        RasterMetricTransform transform = RasterMetricTransform.metricGrid(
            new MetricPoint(0.0, 0.0), 1.0, 0.0, 0.0, 1.0);
        EvidenceSnapshot evidence = snapshot(width, height, frame, transform,
            EvidenceResolution.nativeSource(1.0, 1.0),
            rasterRectangle(transform, 2.0, 2.0, 38.0, 38.0),
            filledValues(width, height, 0.5), allTrue(width * height),
            allTrue((width - 1) * (height - 1)));
        List<DetachedProfileSamplingLocation> anchors = anchors(frame, transform,
            List.of(new RasterPoint(10.0, 20.0), new RasterPoint(30.0, 20.0)), 20.0);

        IllegalArgumentException budget = assertThrows(IllegalArgumentException.class,
            () -> new DetachedScalarProfileSampler(1L).sample(evidence, FIELD_NAME, anchors,
                3.0, 1.0, CancellationProbe.NONE));
        assertTrue(budget.getMessage().contains("budget"));
        assertThrows(CancellationException.class, () -> new DetachedScalarProfileSampler().sample(
            evidence, FIELD_NAME, anchors, 3.0, 1.0, () -> true));
    }

    @Test
    void cancellationDuringProfileProcessingStopsAtTheNextBoundedCheckpoint() {
        int width = 20;
        int height = 2_400;
        LocalMetricFrame frame = frame();
        RasterMetricTransform transform = RasterMetricTransform.metricGrid(
            new MetricPoint(0.0, -1_200.0), 1.0, 0.0, 0.0, 1.0);
        EvidenceSnapshot evidence = snapshot(width, height, frame, transform,
            EvidenceResolution.nativeSource(1.0, 1.0),
            rasterRectangle(transform, 1.0, 1.0, 19.0, 2_399.0),
            filledValues(width, height, 0.7), allTrue(width * height),
            allTrue((width - 1) * (height - 1)));
        List<DetachedProfileSamplingLocation> locations = anchors(frame, transform,
            List.of(new RasterPoint(5.0, 1_200.0), new RasterPoint(15.0, 1_200.0)), 10.0);
        AtomicInteger processingCheckpoints = new AtomicInteger();
        CancellationProbe cancellation = () -> {
            boolean processing = StackWalker.getInstance().walk(frames -> frames.anyMatch(frameInfo ->
                frameInfo.getMethodName().equals("signalGatedPowerBinomialSmooth")
                    || frameInfo.getMethodName().equals("extractBrightBands")));
            return processing && processingCheckpoints.incrementAndGet() >= 2;
        };

        assertThrows(CancellationException.class, () -> new DetachedScalarProfileSampler().sample(
            evidence, FIELD_NAME, locations, 1_000.0, 1.0, cancellation));
        assertEquals(2, processingCheckpoints.get());
    }

    @Test
    void workerBoundaryContainsNoRgbOrJosmProjectionDependency() throws IOException {
        String resource = "/" + DetachedScalarProfileSampler.class.getName().replace('.', '/') + ".class";
        byte[] bytecode;
        try (var input = DetachedScalarProfileSampler.class.getResourceAsStream(resource)) {
            bytecode = input.readAllBytes();
        }
        String constantPool = new String(bytecode, StandardCharsets.ISO_8859_1);

        assertFalse(constantPool.contains("java/awt/image/BufferedImage"));
        assertFalse(constantPool.contains("getRGB"));
        assertFalse(constantPool.contains("org/openstreetmap/josm/gui/MapView"));
        assertFalse(constantPool.contains("ProjectionRegistry"));
    }

    private static void assertEquivalentProfileEvidence(
        MultiScaleProfileSet expected, MultiScaleProfileSet actual
    ) {
        assertEquals(expected.levels().size(), actual.levels().size());
        for (int levelIndex = 0; levelIndex < expected.levels().size(); levelIndex++) {
            ScaleProfileLevel expectedLevel = expected.levels().get(levelIndex);
            ScaleProfileLevel actualLevel = actual.levels().get(levelIndex);
            assertEquals(expectedLevel.level(), actualLevel.level());
            assertEquals(expectedLevel.reduction(), actualLevel.reduction());
            assertEquals(expectedLevel.effectiveSigmaL0(), actualLevel.effectiveSigmaL0(), 0.0);
            assertEquals(expectedLevel.profiles().size(), actualLevel.profiles().size());
            for (int profileIndex = 0; profileIndex < expectedLevel.profiles().size(); profileIndex++) {
                CrossSectionProfile expectedProfile = expectedLevel.profiles().get(profileIndex);
                CrossSectionProfile actualProfile = actualLevel.profiles().get(profileIndex);
                assertEquals(expectedProfile.normalScreen(), actualProfile.normalScreen());
                assertEquals(expectedProfile.anchorWithinRaster(), actualProfile.anchorWithinRaster());
                assertEquals(expectedProfile.intensitySamples(), actualProfile.intensitySamples());
                assertEquals(expectedProfile.peaks(), actualProfile.peaks());
            }
        }
    }

    private static IntensitySample sampleAt(CrossSectionProfile profile, double offset) {
        return profile.intensitySamples().stream()
            .filter(sample -> Math.abs(sample.offsetPx() - offset) < 1e-9)
            .findFirst().orElseThrow();
    }

    private static EvidenceSnapshot snapshot(
        int width,
        int height,
        LocalMetricFrame frame,
        RasterMetricTransform transform,
        EvidenceResolution resolution,
        MetricRegion decision,
        double[] values,
        boolean[] valid,
        boolean[] interpolationValid
    ) {
        ScalarEvidenceField field = new ScalarEvidenceField(width, height, values, valid,
            interpolationValid, new EvidenceFieldLineage(
                EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
                FIELD_NAME, EvidenceCorrelationGroup.SYNTHETIC_TRUTH, false));
        return new EvidenceSnapshot("detached-scalar", frame, transform, resolution,
            decision, rasterRectangle(transform, -0.5, -0.5, width - 0.5, height - 0.5),
            Map.of(FIELD_NAME, field), "synthetic-detached-scalar");
    }

    private static MetricRegion rasterRectangle(
        RasterMetricTransform transform, double minX, double minY, double maxX, double maxY
    ) {
        return new MetricRegion(List.of(List.of(
            transform.pixelCenterToMetric(minX, minY),
            transform.pixelCenterToMetric(maxX, minY),
            transform.pixelCenterToMetric(maxX, maxY),
            transform.pixelCenterToMetric(minX, maxY))));
    }

    private static List<DetachedProfileSamplingLocation> anchors(
        LocalMetricFrame frame,
        RasterMetricTransform transform,
        List<RasterPoint> rasterPoints,
        double totalChainage
    ) {
        List<DetachedProfileSamplingLocation> result = new ArrayList<>();
        for (int index = 0; index < rasterPoints.size(); index++) {
            double chainage = totalChainage * index / (rasterPoints.size() - 1.0);
            result.add(detachedAt(frame, transform, rasterPoints.get(index), chainage));
        }
        return List.copyOf(result);
    }

    private static DetachedProfileSamplingLocation detachedAt(
        LocalMetricFrame frame,
        RasterMetricTransform transform,
        RasterPoint rasterPoint,
        double chainage
    ) {
        MetricPoint metric = transform.pixelCenterToMetric(rasterPoint.x(), rasterPoint.y());
        return new DetachedProfileSamplingLocation(frame.toGeographic(metric), metric, rasterPoint,
            frame, transform, chainage);
    }

    private static LocalMetricFrame frame() {
        GeographicPoint origin = new GeographicPoint(50.0, 14.0);
        return LocalMetricFrame.certifiedEquirectangular(origin,
            new GeographicPoint(49.99, 13.99), new GeographicPoint(50.01, 14.02));
    }

    private static double[] filledValues(int width, int height, double value) {
        double[] result = new double[width * height];
        Arrays.fill(result, value);
        return result;
    }

    private static boolean[] allTrue(int size) {
        boolean[] result = new boolean[size];
        Arrays.fill(result, true);
        return result;
    }

    private static void fillRaster(BufferedImage raster, int argb) {
        for (int y = 0; y < raster.getHeight(); y++) {
            for (int x = 0; x < raster.getWidth(); x++) {
                raster.setRGB(x, y, argb);
            }
        }
    }
}
