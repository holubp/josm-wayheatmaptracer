package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedNode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.DetachedWay;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorTraceInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceResolution;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.InferenceMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ManagedHeatmapConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.OccurrenceRange;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceRequest;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedProfileSamplingLocation;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.DetachedScalarProfileSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.ManualJunctionEligibility;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class V022IntervalTraceRequestFactoryTest {
    @Test
    void secondIntervalSamplesVariableSourcePitchAtItsOriginalPhysicalChainage() throws Exception {
        Fixture fixture = fixture();
        LiveBPreviewService service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] holder = new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = service.capture(fixture.dataSet(),
                fixture.selection(), raster(), config(TrackerMode.PROBABILISTIC)));
        var captured = holder[0];
        var computed = service.compute(captured, CancellationProbe.NONE);
        var full = computed.request();
        var original = computed.evidence();
        double start = captured.sourceMetric().get(0).distanceTo(captured.sourceMetric().get(1))
                + captured.sourceMetric().get(1).distanceTo(captured.sourceMetric().get(2));
        double total = full.profileChainage().cumulativeGroundMeters().get(
                full.profileChainage().cumulativeGroundMeters().size() - 1);
        var resolution = new EvidenceResolution(EvidenceResolution.Kind.NATIVE_SOURCE,
                OptionalDouble.of(1.0), original.resolution().renderedPitchMeters(),
                List.of(new EvidenceResolution.PitchSample(0.0, OptionalDouble.of(1.0),
                                original.resolution().renderedPitchMeters()),
                        new EvidenceResolution.PitchSample(start, OptionalDouble.of(2.0),
                                original.resolution().renderedPitchMeters()),
                        new EvidenceResolution.PitchSample(total, OptionalDouble.of(4.0),
                                original.resolution().renderedPitchMeters())),
                original.resolution().resampledPitchMeters());
        var evidence = new EvidenceSnapshot(original.snapshotId(), original.coordinateFrame(),
                original.transform(), resolution, original.decisionRegion(), original.evidenceRegion(),
                original.fields(), original.resampling(), original.sourceIdentity());
        var bound = new TraceRequest(full.selectedWayKey(), full.selectedRange(), full.engine(),
                full.geometryMode(), full.permissions(), full.budgets(), evidence.snapshotId(),
                evidence.canonicalHash(), full.networkSnapshotId(), full.networkContentHash(),
                full.settingsHash(), full.parameterHash(), full.samplerId(),
                full.configuredSampleStepMeters(), full.profileChainage(), resolution,
                full.corridorInput());
        var right = new IntervalTraceRequestFactory().create(bound, captured, evidence,
                captured.network(), partition(captured).slideIntervals().get(1));
        assertEquals(evidence.canonicalHash(), right.evidenceContentHash());
        assertEquals(original.transform(), evidence.transform());
        assertEquals(original.coordinateFrame(), evidence.coordinateFrame());
        assertEquals(start, right.profileChainage().sourceOriginGroundMeters(), 1.0e-9);
        assertEquals(0.0, right.profileChainage().cumulativeGroundMeters().get(0));
        var anchors = captured.sourceMetric().subList(2, 5);
        var profiles = new ProbabilisticProfileFactory().create(anchors, right.profileChainage(),
                right.permissions().ordinaryRadiusMeters(), true, evidence,
                evidence.fields().get("selected-visible-source"));

        assertEquals(2.0, profiles.get(0).sourcePitchMeters(), 1.0e-9);
        assertEquals(4.0, profiles.get(profiles.size() - 1).sourcePitchMeters(), 1.0e-9);
        var corridor = CorridorTraceInput.from(right.profileChainage(), anchors,
                evidence.coordinateFrame(), evidence.transform(),
                evidence.resolution().outputRasterPitchMeters());
        var sampler = new DetachedScalarProfileSampler();
        var physicalLevels = sampler.sample(evidence, "selected-visible-source",
                corridor.profileLocations(), right.permissions().ordinaryRadiusMeters(),
                corridor.lateralStepMeters(), CancellationProbe.NONE,
                right.profileChainage().sourceOriginGroundMeters());
        assertFalse(physicalLevels.levels().isEmpty());
        var way = (DetachedWay) captured.network().primitives().get(right.selectedWayKey());
        var locations = new java.util.ArrayList<>(corridor.profileLocations());
        for (int local : List.of(0, locations.size() - 1)) {
            int occurrence = local == 0 ? right.selectedRange().firstIndex()
                    : right.selectedRange().lastIndex();
            var node = (DetachedNode) captured.network().primitives()
                    .get(way.nodeKeys().get(occurrence));
            locations.set(local, DetachedProfileSamplingLocation.at(node.coordinate(),
                            evidence.coordinateFrame(), evidence.transform(),
                            right.profileChainage().cumulativeGroundMeters().get(local)));
        }
        var exactCorridor = new CorridorTraceInput(locations, corridor.lateralStepMeters());
        var aRequest = new TraceRequest(right.selectedWayKey(), right.selectedRange(),
                TrackerMode.CORRIDOR_AWARE, right.geometryMode(), right.permissions(),
                right.budgets(), right.evidenceSnapshotId(), right.evidenceContentHash(),
                right.networkSnapshotId(), right.networkContentHash(), right.settingsHash(),
                right.parameterHash(), right.samplerId(), right.configuredSampleStepMeters(),
                right.profileChainage(), resolution, java.util.Optional.of(exactCorridor));
        var aRun = new CorridorEngineAdapter("selected-visible-source").traceWithUsage(
                aRequest, evidence, captured.network(), CancellationProbe.NONE);
        assertFalse(aRun.result().hypotheses().isEmpty());
        assertEquals(4.0 / resolution.outputRasterPitchMeters(),
                aRun.result().hypotheses().get(0).diagnostics().get("sourcePixelSizeRasterPixels"),
                1.0e-9);
        var production = new ModernTracePipeline(new CorridorEngineAdapter("selected-visible-source"))
                .run(right, evidence, captured.network(), computed.options(), CancellationProbe.NONE,
                        Set.of(3));
        assertEquals(TrackerMode.PROBABILISTIC, production.inference().engine());
    }

    @BeforeAll
    static void configureJosm() {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void rebasesPhysicalChainageWithoutMovingGeographicMetricOrRasterAnchors() throws Exception {
        var fixture = fixture();
        TrackerMode engine = TrackerMode.CORRIDOR_AWARE;
        var service = new LiveBPreviewService();
        LiveBPreviewService.Captured[] holder = new LiveBPreviewService.Captured[1];
            SwingUtilities.invokeAndWait(() -> holder[0] = service.capture(fixture.dataSet(),
                    fixture.selection(), raster(), config(engine)));
        var captured = holder[0];
        var batch = service.computePartitioned(captured,
                partition(captured), CancellationProbe.NONE);
        var full = batch.fullRequest();
        var evidence = batch.evidence();
        var intervals = batch.partition().slideIntervals();
        var factory = new IntervalTraceRequestFactory();
        var first = factory.create(full, captured, evidence,
                captured.network(), intervals.get(0));
        var second = factory.create(full, captured, evidence,
                captured.network(), intervals.get(1));
        var way = (DetachedWay) captured.network().primitives().get(first.selectedWayKey());

        assertEquals(intervals.get(0).traceRange(), first.selectedRange());
        assertEquals(intervals.get(1).traceRange(), second.selectedRange());
        assertEquals(full.selectedWayKey(), second.selectedWayKey());
        assertEquals(full.evidenceContentHash(), second.evidenceContentHash());
        assertEquals(full.networkContentHash(), second.networkContentHash());
        assertEquals(0.0, second.profileChainage().cumulativeGroundMeters().get(0));
        assertNotEquals(full.profileChainage().cumulativeGroundMeters(),
                second.profileChainage().cumulativeGroundMeters());
        for (int intervalIndex = 0; intervalIndex < 2; intervalIndex++) {
            var request = intervalIndex == 0 ? first : second;
            int start = request.selectedRange().firstIndex();
            int end = request.selectedRange().lastIndex();
            double physicalLength = 0.0;
            for (int occurrence = start + 1; occurrence <= end; occurrence++) {
                physicalLength += captured.sourceMetric().get(occurrence - 2)
                        .distanceTo(captured.sourceMetric().get(occurrence - 1));
            }
            var chainage = request.profileChainage().cumulativeGroundMeters();
            assertEquals(physicalLength, chainage.get(chainage.size() - 1), 1.0e-8);
                var locations = request.corridorInput().orElseThrow().profileLocations();
                assertEquals(chainage, locations.stream()
                        .map(location -> location.cumulativeGroundDistanceMeters()).toList());
                var anchors = captured.sourceMetric().subList(start - 1, end);
                for (int i = 0; i < locations.size(); i++) {
                    MetricPoint expected = pointAtDistance(anchors, chainage.get(i));
                    assertEquals(expected.xMeters(), locations.get(i).metricPoint().xMeters(), 1.0e-8);
                    assertEquals(expected.yMeters(), locations.get(i).metricPoint().yMeters(), 1.0e-8);
                }
                for (int i : List.of(0, locations.size() - 1)) {
                    int occurrence = i == 0 ? start : end;
                    var geographic = ((DetachedNode) captured.network().primitives()
                            .get(way.nodeKeys().get(occurrence))).coordinate();
                    assertEquals(geographic, locations.get(i).geographicPoint());
                    assertEquals(evidence.coordinateFrame().toMetric(geographic),
                            locations.get(i).metricPoint());
                    assertEquals(evidence.transform().metricToPixelCenter(
                            locations.get(i).metricPoint()), locations.get(i).rasterPoint());
                    assertNotEquals(locations.get(i).metricPoint().xMeters(),
                            locations.get(i).rasterPoint().x());
                }
        }
        assertThrows(IllegalArgumentException.class, () -> factory.create(full,
                captured, evidence, captured.network(),
                new org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner.SlideInterval(
                        intervals.get(0).range(), intervals.get(0).occurrenceKeys(),
                        intervals.get(0).startBoundary(), intervals.get(1).endBoundary())));
    }

    private static MetricPoint pointAtDistance(List<MetricPoint> anchors, double distance) {
        double remaining = distance;
        for (int index = 1; index < anchors.size(); index++) {
            MetricPoint start = anchors.get(index - 1);
            MetricPoint end = anchors.get(index);
            double length = start.distanceTo(end);
            if (remaining <= length || index == anchors.size() - 1) {
                double fraction = Math.min(1.0, remaining / length);
                return new MetricPoint(start.xMeters() + fraction * (end.xMeters() - start.xMeters()),
                        start.yMeters() + fraction * (end.yMeters() - start.yMeters()));
            }
            remaining -= length;
        }
        throw new AssertionError("At least two anchors are required");
    }

    private static Fixture fixture() {
        DataSet dataSet = new DataSet();
        double[] east = {-19, -14, -8, 0, 8, 14, 19};
        double[] north = {0, 0.2, 0.7, 1.0, 0.5, 0.1, 0};
        java.util.ArrayList<Node> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < east.length; i++) {
            Node node = new Node(new LatLon(degrees(north[i]), degrees(east[i])));
            node.setOsmId(100 + i, 1);
            node.setModified(false);
            nodes.add(node);
            dataSet.addPrimitive(node);
        }
        Way way = new Way();
        way.setNodes(nodes);
        way.setOsmId(110, 1);
        way.setModified(false);
        dataSet.addPrimitive(way);
        return new Fixture(dataSet, new SelectionContext(way, 1, 5,
                nodes.subList(1, 6), Set.of(nodes.get(1), nodes.get(5))));
    }

    private static SelectedWayIntervalPartitioner.Partition partition(
            LiveBPreviewService.Captured captured) {
        var selected = (DetachedWay) captured.network().primitives()
                .get(captured.specification().selectedWayKey());
        var keys = selected.nodeKeys();
        var reason = ManualJunctionEligibility.Reason.AFFECTED_NODE_TAGGED;
        var endpoint1 = new SelectedWayIntervalPartitioner.BoundaryConstraint(
                SelectedWayIntervalPartitioner.BoundaryKind.SELECTED_ENDPOINT, 1, keys.get(1),
                false, false, reason);
        var fixed = new SelectedWayIntervalPartitioner.BoundaryConstraint(
                SelectedWayIntervalPartitioner.BoundaryKind.FIXED_ISLAND, 3, keys.get(3),
                false, false, reason);
        var endpoint5 = new SelectedWayIntervalPartitioner.BoundaryConstraint(
                SelectedWayIntervalPartitioner.BoundaryKind.SELECTED_ENDPOINT, 5, keys.get(5),
                false, false, reason);
        var island = new SelectedWayIntervalPartitioner.FixedIsland(new OccurrenceRange(3, 3),
                List.of(keys.get(3)), Set.of(keys.get(3)), List.of(reason), fixed, fixed,
                Set.of(), Set.of());
        var left = new SelectedWayIntervalPartitioner.SlideInterval(new OccurrenceRange(1, 2),
                keys.subList(1, 3), endpoint1, fixed);
        var right = new SelectedWayIntervalPartitioner.SlideInterval(new OccurrenceRange(4, 5),
                keys.subList(4, 6), fixed, endpoint5);
        return new SelectedWayIntervalPartitioner.Partition(captured.specification().selectedWayKey(),
                captured.specification().selectedRange(), List.of(island), List.of(left, right),
                List.of(), Set.of(), captured.network().primitives(),
                captured.network().incomingReferrerWatches(),
                captured.network().datasetIdentity(), captured.network().sourceGeneration());
    }

    private static LiveBPreviewService.VisibleRaster raster() {
        int width = 600;
        int height = 600;
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            double distance = (y - 288.0) / 6.0;
            double intensity = 0.02 + 0.80 * Math.exp(-0.5 * distance * distance / 1.44);
            int gray = (int) Math.round(255.0 * intensity);
            java.util.Arrays.fill(argb, y * width, (y + 1) * width,
                    0xff000000 | gray << 16 | gray << 8 | gray);
        }
        return new LiveBPreviewService.VisibleRaster(width, height, argb,
                -50.0, -50.0, 50.0, 50.0, 1.0, 1.0,
                OptionalDouble.of(1.0), "visible-test", "EPSG:3857");
    }

    private static AlignmentConfig config(TrackerMode engine) {
        ManagedHeatmapConfig heatmap = new ManagedHeatmapConfig("", "", "", "", "all", "hot", "", ".*",
                AlignmentMode.PRECISE_SHAPE, engine, false, false, false, false,
                false, false, false, false, false, false,
                7, 4, 3.0, InferenceMode.RAW_HIGH_RESOLUTION, 15, 15,
                7.01, 1.56, IntensitySamplingMode.COLOR_MAPPING, 0L);
        return new AlignmentConfig(heatmap, GeometryCleanupConfig.disabled());
    }

    private static double degrees(double meters) {
        return Math.toDegrees(meters / 6_378_137.0);
    }

    private record Fixture(DataSet dataSet, SelectionContext selection) { }
}
