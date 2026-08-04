package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

class CorridorGroupingTest {
    @Test
    void formsParentForPersistentShallowValleyAndRetainsChildren() {
        Scenario scenario = scenario(0.72);

        CorridorGrouping.GroupingResult result = new CorridorGrouping().group(scenario.tracks(), scenario.profiles());

        assertEquals(3, result.tracks().size());
        CorridorTrack parent = result.tracks().stream().filter(CorridorTrack::parent).findFirst().orElseThrow();
        assertEquals("combined", parent.groupingDecision());
        assertEquals(2, parent.childTrackIds().size());
    }

    @Test
    void keepsDeepValleyCarriagewaysSeparate() {
        Scenario scenario = scenario(0.20);

        CorridorGrouping.GroupingResult result = new CorridorGrouping().group(scenario.tracks(), scenario.profiles());

        assertEquals(2, result.tracks().size());
        assertTrue(result.tracks().stream().noneMatch(CorridorTrack::parent));
        assertEquals("separate", result.decisions().get(0).decision());
    }

    @Test
    void retainsAmbiguousParentAndChildrenForIntermittentSeparatedModes() {
        Scenario base = scenario(0.20);
        List<CorridorTrack> intermittent = base.tracks().stream().map(track -> {
            boolean left = track.id().equals("left");
            Map<Integer, CorridorTrackPoint> points = track.points().entrySet().stream()
                .filter(entry -> left ? entry.getKey() <= 5 : entry.getKey() >= 4)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                    (first, second) -> first, LinkedHashMap::new));
            return new CorridorTrack(track.id(), points, track.score(), 0.6, false, List.of(), "");
        }).toList();

        CorridorGrouping.GroupingResult result = new CorridorGrouping().group(intermittent, base.profiles());

        CorridorTrack parent = result.tracks().stream().filter(CorridorTrack::parent).findFirst().orElseThrow();
        assertEquals("ambiguous", parent.groupingDecision());
        assertEquals(3, result.tracks().size());
    }

    @Test
    void combinesComplementarySparseStrandsAcrossCrossSectionHoles() {
        Scenario scenario = complementarySparseScenario();

        CorridorGrouping.GroupingResult result = new CorridorGrouping().group(
            scenario.tracks(), scenario.profiles());

        CorridorTrack parent = result.tracks().stream().filter(CorridorTrack::parent).findFirst().orElseThrow();
        assertEquals("combined", parent.groupingDecision());
        assertEquals(32, parent.points().size(), "Child-union support should span every physical profile");
        double maximumCenterError = parent.points().values().stream()
            .filter(point -> point.profileIndex() > 0 && point.profileIndex() < 31)
            .mapToDouble(point -> Math.abs(point.band().centerOffsetPx()))
            .max().orElseThrow();
        assertTrue(maximumCenterError <= 0.35,
            "Longitudinal child predictions should center alternating sparse strands, error=" + maximumCenterError
                + ", offsets=" + parent.points().values().stream()
                    .map(point -> point.profileIndex() + ":" + point.band().centerOffsetPx()).toList());
    }

    @Test
    void combinesThreeComplementaryTracksOnlyWhenTheWholeUnionIsPersistent() {
        Scenario scenario = complementaryMultiTrackScenario();

        CorridorGrouping.GroupingResult result = new CorridorGrouping().group(
            scenario.tracks(), scenario.profiles());

        CorridorTrack parent = result.tracks().stream().filter(CorridorTrack::parent).findFirst().orElseThrow();
        assertEquals(List.of("left", "middle", "right"), parent.childTrackIds());
        assertEquals(36, parent.points().size());
        assertEquals(4, result.tracks().size(), "The parent must supplement, not replace, three children");
    }

    @Test
    void doesNotTransitivelyMergeAnIncompatibleOuterPair() {
        Scenario scenario = threePersistentTracksScenario();

        CorridorGrouping.GroupingResult result = new CorridorGrouping().group(
            scenario.tracks(), scenario.profiles());

        assertTrue(result.tracks().stream().filter(CorridorTrack::parent)
            .noneMatch(parent -> parent.childTrackIds().size() == 3),
            "A-B and B-C compatibility must not override incompatible A-C separation");
    }

    private Scenario threePersistentTracksScenario() {
        List<CorridorProfile> profiles = new ArrayList<>();
        List<Map<Integer, CorridorTrackPoint>> childPoints = List.of(
            new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
        List<String> ids = List.of("left", "middle", "right");
        List<Double> centers = List.of(-6.0, 0.0, 6.0);
        for (int index = 0; index < 12; index++) {
            List<CorridorBand> bands = new ArrayList<>();
            for (int child = 0; child < ids.size(); child++) {
                double center = centers.get(child);
                CorridorBand band = band(ids.get(child) + '-' + index, center, center - 1.0, center + 1.0);
                childPoints.get(child).put(index, new CorridorTrackPoint(index, band, false));
                bands.add(band);
            }
            List<IntensitySample> samples = new ArrayList<>();
            for (int offset = -8; offset <= 8; offset++) {
                double intensity = centers.contains((double) offset) ? 1.0 : 0.70;
                samples.add(new IntensitySample(offset, intensity, intensity, intensity, true));
            }
            var source = new RenderedHeatmapSampler.CrossSectionProfile(
                new ProfileSamplingAnchor(new EastNorth(index * 2.5, 0.0), index * 15.0, 0.0,
                    index * 2.5),
                new Point2D.Double(0.0, 1.0), List.of(), true, samples);
            profiles.add(new CorridorProfile(index, source, bands, 1.0, 0.0, 1.0, true));
        }
        List<CorridorTrack> tracks = new ArrayList<>();
        for (int child = 0; child < ids.size(); child++) {
            tracks.add(new CorridorTrack(ids.get(child), childPoints.get(child), 12.0, 1.0,
                false, List.of(), ""));
        }
        return new Scenario(tracks, profiles);
    }

    private Scenario complementaryMultiTrackScenario() {
        List<CorridorProfile> profiles = new ArrayList<>();
        List<Map<Integer, CorridorTrackPoint>> childPoints = List.of(
            new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
        List<String> ids = List.of("left", "middle", "right");
        List<Double> centers = List.of(-3.0, 0.0, 3.0);
        for (int index = 0; index < 36; index++) {
            int child = index % 3;
            CorridorBand observed = band(ids.get(child) + '-' + index, centers.get(child),
                centers.get(child) - 1.0, centers.get(child) + 1.0);
            childPoints.get(child).put(index, new CorridorTrackPoint(index, observed, false));
            List<IntensitySample> samples = new ArrayList<>();
            for (int offset = -6; offset <= 6; offset++) {
                double intensity = offset == centers.get(child) ? 0.16 : 0.0;
                samples.add(new IntensitySample(offset, intensity, intensity, intensity, true));
            }
            var source = new RenderedHeatmapSampler.CrossSectionProfile(
                new ProfileSamplingAnchor(new EastNorth(index * 2.5, 0.0), index * 15.0, 0.0,
                    index * 2.5),
                new Point2D.Double(0.0, 1.0), List.of(), true, samples);
            profiles.add(new CorridorProfile(index, source, List.of(observed), 0.16, 0.0, 0.16, true));
        }
        List<CorridorTrack> tracks = new ArrayList<>();
        for (int child = 0; child < ids.size(); child++) {
            tracks.add(new CorridorTrack(ids.get(child), childPoints.get(child), 6.0, 1.0 / 3.0,
                false, List.of(), ""));
        }
        return new Scenario(tracks, profiles);
    }

    private Scenario complementarySparseScenario() {
        List<CorridorProfile> profiles = new ArrayList<>();
        Map<Integer, CorridorTrackPoint> leftPoints = new LinkedHashMap<>();
        Map<Integer, CorridorTrackPoint> rightPoints = new LinkedHashMap<>();
        for (int index = 0; index < 32; index++) {
            CorridorBand left = band("left-" + index, -2.0, -3.0, -1.0);
            CorridorBand right = band("right-" + index, 2.0, 1.0, 3.0);
            List<CorridorBand> observedBands = new ArrayList<>();
            if (index % 2 == 0) {
                leftPoints.put(index, new CorridorTrackPoint(index, left, false));
                observedBands.add(left);
            } else {
                rightPoints.put(index, new CorridorTrackPoint(index, right, false));
                observedBands.add(right);
            }
            List<IntensitySample> samples = new ArrayList<>();
            for (int offset = -5; offset <= 5; offset++) {
                double intensity = index % 2 == 0 && offset == -2 || index % 2 != 0 && offset == 2
                    ? 0.18 : 0.0;
                samples.add(new IntensitySample(offset, intensity, intensity, intensity, true));
            }
            var source = new RenderedHeatmapSampler.CrossSectionProfile(
                new ProfileSamplingAnchor(new EastNorth(index * 2.5, 0.0), index * 15.0, 0.0,
                    index * 2.5),
                new Point2D.Double(0.0, 1.0), List.of(), true, samples);
            profiles.add(new CorridorProfile(index, source, observedBands, 0.18, 0.0, 0.18, true));
        }
        CorridorTrack left = new CorridorTrack("left", leftPoints, 8.0, 0.5, false, List.of(), "");
        CorridorTrack right = new CorridorTrack("right", rightPoints, 8.0, 0.5, false, List.of(), "");
        return new Scenario(List.of(left, right), profiles);
    }

    private Scenario scenario(double valley) {
        List<CorridorProfile> profiles = new ArrayList<>();
        Map<Integer, CorridorTrackPoint> leftPoints = new LinkedHashMap<>();
        Map<Integer, CorridorTrackPoint> rightPoints = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            CorridorBand left = band("left", -3.0, -5.0, 0.0);
            CorridorBand right = band("right", 3.0, 0.0, 5.0);
            leftPoints.put(i, new CorridorTrackPoint(i, left, false));
            rightPoints.put(i, new CorridorTrackPoint(i, right, false));
            List<IntensitySample> samples = new ArrayList<>();
            for (int offset = -6; offset <= 6; offset++) {
                double intensity = Math.abs(offset) <= 1 ? valley : (Math.abs(offset) == 3 ? 1.0 : 0.75);
                samples.add(new IntensitySample(offset, intensity, intensity, intensity, true));
            }
            RenderedHeatmapSampler.CrossSectionProfile source = new RenderedHeatmapSampler.CrossSectionProfile(
                new ProfileSamplingAnchor(new EastNorth(i * 10.0, 0.0), i * 10.0, 0.0, i * 10.0),
                new Point2D.Double(0.0, 1.0), List.of(), true, samples);
            profiles.add(new CorridorProfile(i, source, List.of(left, right), 1.0, 0.0, 1.0, true));
        }
        CorridorTrack left = new CorridorTrack("left", leftPoints, 10.0, 1.0, false, List.of(), "");
        CorridorTrack right = new CorridorTrack("right", rightPoints, 10.0, 1.0, false, List.of(), "");
        return new Scenario(List.of(left, right), profiles);
    }

    private CorridorBand band(String id, double center, double shoulderMin, double shoulderMax) {
        return new CorridorBand(id, center, shoulderMin, shoulderMax, center - 1.0, center + 1.0,
            List.of(center), 1.0, 0.0, 1.0, 0.9, 0.8, 0.5, false, List.of());
    }

    private record Scenario(List<CorridorTrack> tracks, List<CorridorProfile> profiles) {
    }
}
