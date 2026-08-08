package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntToDoubleFunction;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.AlignmentMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupEvidence;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateCleanupProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CandidateGeometryCleanup;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupEvidenceProvenance;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupSamplingFrame;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CleanupSamplingProfile;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupPreset;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProjectedLateralTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.SelectionContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.PreviewNodeAssignmentPlanner;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/**
 * End-to-end numerical acceptance coverage for the optional final-preview cleanup path.
 *
 * <p>The harness deliberately supplies the slide-time projected lateral transforms which the
 * raw tracker-only fixtures do not retain. It therefore exercises the same complete-evidence
 * and immutable-final-preview contract required before {@link GeometryCleanupService} can create
 * a cleaned sibling.</p>
 */
class GeometryCleanupCalibrationTest {
    private static final int PROFILE_COUNT = 61;
    private static final double EPSILON = 1e-9;
    private static final GeometryCleanupService SERVICE = new GeometryCleanupService();
    private static final GeometryCleanupConfig STRONG = GeometryCleanupPreset.STRONG
        .apply(GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE);

    @BeforeAll
    static void setPreferences() {
        Config.setPreferencesInstance(new MemoryPreferences());
    }

    @Test
    void strongCleanupReducesKnownFiveToTwentyMetreUnsupportedRipplesByFortyPercent() {
        for (int wavelengthMeters : List.of(5, 10, 20)) {
            Fixture fixture = fixture("ripple-" + wavelengthMeters,
                index -> 0.20 + 0.70 * Math.sin(2.0 * Math.PI * index / wavelengthMeters),
                index -> 0.20, 1.0, Set.of(), Set.of(), Set.of(), Set.of());

            CenterlineCandidate cleaned = cleaned(fixture, STRONG);
            double before = residualRmsSourcePixels(fixture.candidate(), 0.20, 1.0);
            double after = residualRmsSourcePixels(cleaned, 0.20, 1.0);

            assertTrue(after <= before * 0.60 + EPSILON,
                () -> wavelengthMeters + "m ripple before=" + before + ", after=" + after);
            assertFitsAllBands(fixture.candidate(), cleaned, STRONG);
        }
    }

    @Test
    void cleanedBroadCorridorRemainsWithinQuarterSourcePixelOfItsHighIntensityCenter() {
        for (GeometryCleanupPreset preset : List.of(GeometryCleanupPreset.CONSERVATIVE,
            GeometryCleanupPreset.BALANCED, GeometryCleanupPreset.STRONG)) {
            GeometryCleanupConfig config = preset.apply(GeometryCleanupMode.CONSTRAINED_SMOOTH_AND_REDUCE);
            Fixture fixture = fixture("broad-" + preset,
                index -> 0.18 + ((index & 1) == 0 ? 0.38 : -0.38), index -> 0.18,
                1.0, Set.of(), Set.of(), Set.of(), Set.of());

            CenterlineCandidate result = cleaned(fixture, config);
            double bias = Math.abs(meanOffsetsSourcePixels(result, 1.0) - 0.18);

            assertTrue(bias <= 0.25,
                () -> preset + " broad-corridor center bias=" + bias + " source pixels");
            assertFitsAllBands(fixture.candidate(), result, config);
        }
    }

    @Test
    void strongCleanupRetainsSupportedSineAndSwitchbackAmplitude() {
        List<Scenario> scenarios = List.of(
            new Scenario("sine", index -> 2.2 * Math.sin(2.0 * Math.PI * index / 40.0),
                Set.of(10, 30, 50)),
            new Scenario("switchback", GeometryCleanupCalibrationTest::switchback,
                Set.of(10, 30, 50))
        );
        for (Scenario scenario : scenarios) {
            Fixture fixture = fixture(scenario.name(), scenario.shape(), scenario.shape(), 1.0,
                scenario.turnSupport(), Set.of(), Set.of(), Set.of());
            CenterlineCandidate result = terminalCandidate(fixture, STRONG);

            assertTrue(amplitude(result) >= 0.90 * amplitude(fixture.candidate()),
                () -> scenario.name() + " before=" + amplitude(fixture.candidate())
                    + ", after=" + amplitude(result));
            assertFitsAllBands(fixture.candidate(), result, STRONG);
        }
    }

    @Test
    void denseMediumSparseHoleyOutlierAndParallelControlsStayWithinTheirAuthorizedCorridor() {
        Fixture dense = fixture("dense", index -> 0.20 + alternating(index, 0.40), index -> 0.20,
            1.0, Set.of(), Set.of(), Set.of(), Set.of());
        Fixture medium = fixture("medium", index -> -0.45 + alternating(index, 0.30), index -> -0.45,
            1.0, Set.of(), Set.of(), Set.of(), Set.of());
        Fixture parallel = fixture("parallel-selected-left",
            index -> -2.0 + alternating(index, 0.30), index -> -2.0, 1.0,
            Set.of(), Set.of(), Set.of(), Set.of());

        for (Fixture fixture : List.of(dense, medium, parallel)) {
            CenterlineCandidate result = cleaned(fixture, STRONG);
            assertTrue(result.finalPreviewPoints().stream().allMatch(point -> {
                double offset = point.north() / fixture.groundMetersPerRasterPixel();
                return offset >= fixture.centerAtProfile(0) - 4.0 - EPSILON
                    && offset <= fixture.centerAtProfile(0) + 4.0 + EPSILON;
            }), fixture.name() + " left selected corridor shoulder");
            assertFitsAllBands(fixture.candidate(), result, STRONG);
        }

        for (Fixture fixture : List.of(
            fixture("sparse-holes", index -> 0.30 + alternating(index, 0.25), index -> 0.30,
                1.0, Set.of(), Set.of(20), Set.of(), Set.of()),
            fixture("wandering-outlier", index -> index == 30 ? 5.0 : 0.30,
                index -> 0.30, 1.0, Set.of(), Set.of(), Set.of(30), Set.of())
        )) {
            CenterlineCandidate result = terminalCandidate(fixture, STRONG);
            assertEquals(fixture.candidate().finalPreviewPoints(), result.finalPreviewPoints(), fixture.name());
            assertTrue(result.geometryCleanup().outcome() == CandidateGeometryCleanup.Outcome.SKIPPED
                    || result.geometryCleanup().outcome() == CandidateGeometryCleanup.Outcome.REJECTED,
                fixture.name() + " must fail closed: " + result.geometryCleanup());
        }
    }

    @Test
    void protectedEndpointAndJunctionControlsRemainExactAcrossRepeatAndResolutionScales() {
        Set<Integer> junction = Set.of(PROFILE_COUNT / 2);
        Fixture protectedFixture = fixture("junction-endpoints",
            index -> 0.20 + alternating(index, 0.35), index -> 0.20, 1.0,
            Set.of(), Set.of(), Set.of(), junction);
        CenterlineCandidate once = terminalCandidate(protectedFixture, STRONG);

        assertEquals(protectedFixture.candidate().finalPreviewPoints().get(0), once.finalPreviewPoints().get(0));
        assertEquals(protectedFixture.candidate().finalPreviewPoints().get(PROFILE_COUNT - 1),
            once.finalPreviewPoints().get(once.finalPreviewPoints().size() - 1));
        assertTrue(once.finalPreviewPoints().contains(
            protectedFixture.candidate().finalPreviewPoints().get(PROFILE_COUNT / 2)));

        Fixture repeatedFixture = fixture("repeat", index -> 0.20, index -> 0.20, 1.0,
            Set.of(), Set.of(), Set.of(), Set.of());
        CenterlineCandidate repeated = terminalCandidate(repeatedFixture, STRONG);
        assertTrue(Math.abs(meanOffsetsSourcePixels(repeated, 1.0) - 0.20) <= 0.25);

        for (double groundMetersPerRasterPixel : List.of(0.50, 1.0, 2.0)) {
            Fixture scaled = fixture("resolution-" + groundMetersPerRasterPixel,
                index -> 0.18 + alternating(index, 0.35), index -> 0.18,
                groundMetersPerRasterPixel, Set.of(), Set.of(), Set.of(), Set.of());
            CenterlineCandidate result = cleaned(scaled, STRONG);
            assertTrue(Math.abs(meanOffsetsSourcePixels(result, groundMetersPerRasterPixel) - 0.18) <= 0.25,
                () -> "scale=" + groundMetersPerRasterPixel + ", center="
                    + meanOffsetsSourcePixels(result, groundMetersPerRasterPixel));
        }
    }

    private static CenterlineCandidate cleaned(Fixture fixture, GeometryCleanupConfig config) {
        List<CenterlineCandidate> candidates = SERVICE.expand(fixture.candidate(), fixture.selection(), fixture.geometry(),
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE, config);
        assertEquals(2, candidates.size(), () -> fixture.name() + ": " + candidates.get(0).geometryCleanup());
        assertEquals(CandidateGeometryCleanup.Outcome.CLEANED, candidates.get(1).geometryCleanup().outcome());
        return candidates.get(1);
    }

    private static CenterlineCandidate terminalCandidate(Fixture fixture, GeometryCleanupConfig config) {
        List<CenterlineCandidate> candidates = SERVICE.expand(fixture.candidate(), fixture.selection(), fixture.geometry(),
            AlignmentMode.PRECISE_SHAPE, TrackerMode.CORRIDOR_AWARE, config);
        assertFalse(candidates.isEmpty());
        return candidates.size() == 2 ? candidates.get(1) : candidates.get(0);
    }

    private static Fixture fixture(
        String name,
        IntToDoubleFunction geometryOffsetsPx,
        IntToDoubleFunction centerOffsetsPx,
        double groundMetersPerRasterPixel,
        Set<Integer> turnSupport,
        Set<Integer> noSignal,
        Set<Integer> unsupported,
        Set<Integer> taggedIndexes
    ) {
        List<EastNorth> geometry = new ArrayList<>();
        List<CleanupSamplingProfile> samples = new ArrayList<>();
        List<CandidateCleanupProfile> rows = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();
        double[] offsets = offsets();
        for (int index = 0; index < PROFILE_COUNT; index++) {
            double center = centerOffsetsPx.applyAsDouble(index);
            double x = index * 2.0;
            geometry.add(new EastNorth(x, geometryOffsetsPx.applyAsDouble(index) * groundMetersPerRasterPixel));
            nodes.add(new Node(new LatLon(50.0 + index * 0.00001, 14.0)));
            if (taggedIndexes.contains(index)) {
                nodes.get(index).put("barrier", "yes");
            }
            double[] raw = intensity(offsets, center, 1.65, noSignal.contains(index));
            double[] b3 = intensity(offsets, center + 0.12, 1.95, noSignal.contains(index));
            double[] b5 = intensity(offsets, center - 0.10, 2.25, noSignal.contains(index));
            boolean[] valid = new boolean[offsets.length];
            java.util.Arrays.fill(valid, true);
            samples.add(new CleanupSamplingProfile(index, x, true, 1.0,
                new ProjectedLateralTransform(new EastNorth(x, 0.0), 0.0, groundMetersPerRasterPixel),
                offsets, raw, b3, b5, valid));
            if (unsupported.contains(index)) {
                rows.add(new CandidateCleanupProfile(index, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    0.0, 1.0, CleanupEvidenceProvenance.UNSUPPORTED, 0.0, 0.0, false));
            } else {
                rows.add(new CandidateCleanupProfile(index, center - 0.80, center + 0.80,
                    center - 4.0, center + 4.0, center, 1.0, CleanupEvidenceProvenance.DIRECT,
                    turnSupport.contains(index) ? 1.0 : 0.0,
                    turnSupport.contains(index) ? 1.0 : 0.0, false));
            }
        }
        DataSet dataSet = new DataSet();
        nodes.forEach(dataSet::addPrimitive);
        Way way = new Way();
        way.setNodes(nodes);
        dataSet.addPrimitive(way);
        SelectionContext selection = new SelectionContext(way, 0, nodes.size() - 1, List.copyOf(nodes),
            Set.of(nodes.get(0), nodes.get(nodes.size() - 1)));
        Map<Long, EastNorth> assignments = PreviewNodeAssignmentPlanner.targetMap(
            PreviewNodeAssignmentPlanner.preciseAssignments(selection, geometry, geometry));
        CandidateCleanupEvidence evidence = CandidateCleanupEvidence.complete(
            new CleanupSamplingFrame(name, samples, groundMetersPerRasterPixel), rows);
        CenterlineCandidate candidate = new CenterlineCandidate(name, 0.8, screenPoints(geometry), offsetsOf(geometry,
            groundMetersPerRasterPixel)).withEastNorthPoints(geometry).withFinalPreviewGeometry(geometry, assignments)
                .withCleanupEvidence(evidence);
        return new Fixture(name, geometry, selection, candidate, groundMetersPerRasterPixel, centerOffsetsPx);
    }

    private static void assertFitsAllBands(
        CenterlineCandidate raw,
        CenterlineCandidate result,
        GeometryCleanupConfig config
    ) {
        for (int band = 0; band < 3; band++) {
            int selectedBand = band;
            double before = meanFit(raw, selectedBand);
            double after = meanFit(result, selectedBand);
            assertTrue(after + EPSILON >= before * config.minimumFitRetention(),
                () -> "band=" + selectedBand + ", before=" + before + ", after=" + after
                    + ", floor=" + config.minimumFitRetention());
        }
    }

    private static double meanFit(CenterlineCandidate candidate, int band) {
        CandidateCleanupEvidence evidence = candidate.cleanupEvidence();
        double sum = 0.0;
        for (int index = 0; index < candidate.finalPreviewPoints().size(); index++) {
            CleanupSamplingProfile sample = evidence.samplingFrame().profiles().get(index);
            ProjectedLateralTransform transform = sample.projectedLateralTransform();
            EastNorth point = candidate.finalPreviewPoints().get(index);
            double offset = (point.north() - transform.zeroOffset().north()) / transform.northPerRasterPixel();
            sum += sampleAt(sample, offset, band);
        }
        return sum / candidate.finalPreviewPoints().size();
    }

    private static double sampleAt(CleanupSamplingProfile sample, double offset, int band) {
        for (int index = 1; index < sample.sampleCount(); index++) {
            double left = sample.offsetPxAt(index - 1);
            double right = sample.offsetPxAt(index);
            if (offset + EPSILON < left || offset - EPSILON > right) {
                continue;
            }
            double fraction = (offset - left) / (right - left);
            double before = intensity(sample, index - 1, band);
            return before + fraction * (intensity(sample, index, band) - before);
        }
        throw new AssertionError("Offset outside fixture evidence: " + offset);
    }

    private static double intensity(CleanupSamplingProfile sample, int index, int band) {
        return switch (band) {
            case 0 -> sample.nativeIntensityAt(index);
            case 1 -> sample.lightFilteredIntensityAt(index);
            case 2 -> sample.standardFilteredIntensityAt(index);
            default -> throw new IllegalArgumentException("Unknown band " + band);
        };
    }

    private static double residualRmsSourcePixels(
        CenterlineCandidate candidate,
        double centerPx,
        double groundMetersPerRasterPixel
    ) {
        return Math.sqrt(candidate.finalPreviewPoints().stream().mapToDouble(point -> {
            double residual = point.north() / groundMetersPerRasterPixel - centerPx;
            return residual * residual;
        }).average().orElseThrow());
    }

    private static double meanOffsetsSourcePixels(CenterlineCandidate candidate, double groundMetersPerRasterPixel) {
        return candidate.finalPreviewPoints().stream().mapToDouble(point ->
            point.north() / groundMetersPerRasterPixel).average().orElseThrow();
    }

    private static double amplitude(CenterlineCandidate candidate) {
        double minimum = candidate.finalPreviewPoints().stream().mapToDouble(EastNorth::north).min().orElseThrow();
        double maximum = candidate.finalPreviewPoints().stream().mapToDouble(EastNorth::north).max().orElseThrow();
        return (maximum - minimum) * 0.5;
    }

    private static double alternating(int index, double amplitude) {
        return (index & 1) == 0 ? amplitude : -amplitude;
    }

    private static double switchback(int index) {
        int phase = index % 20;
        double rising = -2.2 + 4.4 * phase / 20.0;
        return (index / 20) % 2 == 0 ? rising : -rising;
    }

    private static double[] offsets() {
        double[] result = new double[33];
        for (int index = 0; index < result.length; index++) {
            result[index] = index - 16;
        }
        return result;
    }

    private static double[] intensity(double[] offsets, double center, double sigma, boolean empty) {
        double[] result = new double[offsets.length];
        if (empty) {
            return result;
        }
        for (int index = 0; index < offsets.length; index++) {
            double delta = offsets[index] - center;
            result[index] = 0.02 + 0.98 * Math.exp(-0.5 * delta * delta / (sigma * sigma));
        }
        return result;
    }

    private static List<Point2D.Double> screenPoints(List<EastNorth> geometry) {
        return geometry.stream().map(point -> new Point2D.Double(point.east(), point.north())).toList();
    }

    private static List<Double> offsetsOf(List<EastNorth> geometry, double groundMetersPerRasterPixel) {
        return geometry.stream().map(point -> point.north() / groundMetersPerRasterPixel).toList();
    }

    private record Scenario(String name, IntToDoubleFunction shape, Set<Integer> turnSupport) {
    }

    private record Fixture(
        String name,
        List<EastNorth> geometry,
        SelectionContext selection,
        CenterlineCandidate candidate,
        double groundMetersPerRasterPixel,
        IntToDoubleFunction centerOffsetsPx
    ) {
        double centerAtProfile(int index) {
            return centerOffsetsPx.applyAsDouble(index);
        }
    }
}
