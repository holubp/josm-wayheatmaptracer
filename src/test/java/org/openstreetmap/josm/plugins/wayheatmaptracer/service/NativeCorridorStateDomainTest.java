package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;

class NativeCorridorStateDomainTest {
    @Test
    void exposesCanonicalOrderedStatesAndImmutableNativeOwnership() {
        var band = new CorridorBand("owner-band", 1.0, -2.0, 2.0, -1.0, 1.0,
            List.of(-0.5, 0.0, 0.5), 1.0, 0.0, 0.2, 0.8, 0.9, 1.0,
            0.95, 0.9, 0.25, false, List.of());
        var source = new RenderedHeatmapSampler.CrossSectionProfile(
            new ProfileSamplingAnchor(new EastNorth(10.0, 20.0), 10.0, 20.0, 0.0),
            new Point2D.Double(0.0, 1.0), List.of(), true,
            List.of(new IntensitySample(-2.0, 0.1, 0.1, 0.1, true),
                new IntensitySample(-1.0, 0.7, 0.7, 0.7, true),
                new IntensitySample(0.0, 1.0, 1.0, 1.0, true),
                new IntensitySample(1.0, 0.7, 0.7, 0.7, true),
                new IntensitySample(2.0, 0.1, 0.1, 0.1, true)));
        var profile = new CorridorProfile(0, source, List.of(band), 1.0, 0.0, 1.0, true);
        var point = new CorridorTrackPoint(0, band, false);
        var track = new CorridorTrack("native-track", Map.of(0, point), 1.0, 1.0, false, List.of(), "");
        List<CorridorProfile> profiles = List.of(profile);
        JunctionContext junctions = JunctionContext.empty();
        Map<String, BandScaleEvidence> scale = Map.of();
        var tube = new CorridorTubeBuilder().build(track, profiles, 1.0, scale);
        var approaches = new EndpointApproachBuilder().build(track, profiles, tube, junctions, scale);

        var domain = new CorridorCenterlineOptimizer().nativeStateDomain(
            track, profiles, 1.0, junctions, scale, tube, approaches);

        assertEquals(1, domain.rows().size());
        var row = domain.rows().get(0);
        assertEquals(0, row.rowIndex());
        assertEquals(List.of(-2.0, -1.0, 0.0, 0.49909041912141827, 1.0, 2.0),
            row.states().stream().map(CorridorCenterlineOptimizer.NativeState::offsetPx).toList());
        assertEquals(List.of(0, 1, 2, 3, 4, 5),
            row.states().stream().map(CorridorCenterlineOptimizer.NativeState::stateId).toList());
        assertSame(band, row.bandOwner());
        assertSame(point, row.trackPointOwner());
        assertThrows(UnsupportedOperationException.class, () -> domain.rows().clear());
        assertThrows(UnsupportedOperationException.class, () -> row.states().clear());
    }

    @Test
    void preservesGapOwnershipEndpointBoundsAndOffsetStateCap() throws Exception {
        var band = new CorridorBand("band", 0.0, -20.0, 20.0, -2.0, 2.0,
            List.of(-1.0, 0.0, 1.0), 1.0, 0.0, 0.2, 0.8, 0.9, 1.0,
            0.95, 0.9, 0.25, false, List.of());
        List<CorridorProfile> profiles = List.of(profile(0, band), profile(1, band), profile(2, band));
        var firstPoint = new CorridorTrackPoint(0, band, false);
        var rightBoundary = new CorridorTrackPoint(2, band, true);
        var track = new CorridorTrack("gap-track", Map.of(0, firstPoint, 2, rightBoundary),
            2.0, 2.0 / 3.0, false, List.of(), "");
        Map<String, BandScaleEvidence> scale = Map.of();
        var tube = new CorridorTubeBuilder().build(track, profiles, 1.0, scale);
        var optimizer = new CorridorCenterlineOptimizer();
        var freeApproaches = new EndpointApproachBuilder().build(
            track, profiles, tube, JunctionContext.empty(), scale);
        var gapDomain = optimizer.nativeStateDomain(
            track, profiles, 1.0, JunctionContext.empty(), scale, tube, freeApproaches);

        var gap = gapDomain.rows().get(1);
        assertEquals(1, gap.states().size());
        assertEquals(0.0, gap.states().get(0).offsetPx(), 1e-12);
        assertEquals(null, gap.bandOwner());
        assertEquals(null, gap.trackPointOwner());
        assertEquals(0.16, gap.continuityWeight(), 0.0);
        assertEquals(0.0, gap.accelerationWeight(), 0.0);
        assertEquals(rightBoundary, gapDomain.rows().get(2).trackPointOwner());

        var fixed = new EndpointConstraint(0, 5L, true, true, 0.0, 0.0, 2);
        var fixedContext = new JunctionContext(List.of(fixed));
        var fixedApproaches = new EndpointApproachBuilder().build(track, profiles, tube, fixedContext, scale);
        var fixedRow = optimizer.nativeStateDomain(
            track, profiles, 1.0, fixedContext, scale, tube, fixedApproaches).rows().get(0);
        assertEquals(List.of(0.0), fixedRow.states().stream()
            .map(CorridorCenterlineOptimizer.NativeState::offsetPx).toList());
        assertEquals(fixed, fixedRow.endpointConstraint());

        var movable = new EndpointConstraint(0, 5L, false, true, 1.0, 0.5, 2);
        var movableContext = new JunctionContext(List.of(movable));
        var movableApproaches = new EndpointApproachBuilder().build(track, profiles, tube, movableContext, scale);
        var movableRow = optimizer.nativeStateDomain(
            track, profiles, 1.0, movableContext, scale, tube, movableApproaches).rows().get(0);
        assertTrue(movableRow.states().stream().allMatch(state -> Math.abs(state.offsetPx()) <= 1.0 + 1e-9));

        var capped = new CorridorCenterlineOptimizer(new CorridorOptimizationParameters(21, 0.55, 4.0, 0.55, 4.0));
        long eligibleDistinctSamples = profiles.get(0).source().intensitySamples().stream()
            .filter(IntensitySample::insideRaster)
            .filter(sample -> sample.offsetPx() >= band.shoulderMinPx()
                && sample.offsetPx() <= band.shoulderMaxPx())
            .map(IntensitySample::offsetPx).distinct().count();
        assertTrue(eligibleDistinctSamples > 21,
            "fixture must offer more than the native state cap: " + eligibleDistinctSamples);

        double nonUnitSourcePixel = 0.5;
        var nonUnitTube = new CorridorTubeBuilder().build(track, profiles, nonUnitSourcePixel, scale);
        var nonUnitApproaches = new EndpointApproachBuilder().build(
            track, profiles, nonUnitTube, JunctionContext.empty(), scale);
        var nonUnitRow = capped.nativeStateDomain(track, profiles, nonUnitSourcePixel,
            JunctionContext.empty(), scale, nonUnitTube, nonUnitApproaches).rows().get(0);
        List<Double> nonUnitCanonical = canonicalAllowedOffsets(capped, track, profiles, 0,
            nonUnitSourcePixel, JunctionContext.empty(), scale, nonUnitTube, nonUnitApproaches);
        assertEquals(nonUnitCanonical,
            nonUnitRow.states().stream().map(CorridorCenterlineOptimizer.NativeState::offsetPx).toList());
        assertEquals(19, nonUnitRow.states().size());
        assertEquals(java.util.stream.IntStream.range(0, 19).boxed().toList(),
            nonUnitRow.states().stream().map(CorridorCenterlineOptimizer.NativeState::stateId).toList());

        double invalidSourcePixel = Double.NaN;
        var invalidTube = new CorridorTubeBuilder().build(track, profiles, 1.0, scale);
        var invalidApproaches = new EndpointApproachBuilder().build(
            track, profiles, invalidTube, JunctionContext.empty(), scale);
        var invalidRow = capped.nativeStateDomain(track, profiles, invalidSourcePixel,
            JunctionContext.empty(), scale, invalidTube, invalidApproaches).rows().get(0);
        List<Double> invalidCanonical = canonicalAllowedOffsets(capped, track, profiles, 0,
            1.0, JunctionContext.empty(), scale, invalidTube, invalidApproaches);
        assertEquals(invalidCanonical,
            invalidRow.states().stream().map(CorridorCenterlineOptimizer.NativeState::offsetPx).toList());
    }

    @SuppressWarnings("unchecked")
    private List<Double> canonicalAllowedOffsets(
        CorridorCenterlineOptimizer optimizer,
        CorridorTrack track,
        List<CorridorProfile> profiles,
        int row,
        double normalizedSourcePixel,
        JunctionContext junctions,
        Map<String, BandScaleEvidence> scale,
        LongitudinalCorridorTube tube,
        EndpointApproachModel approaches
    ) throws Exception {
        Method method = CorridorCenterlineOptimizer.class.getDeclaredMethod("allowedOffsets",
            CorridorTrack.class, List.class, int.class, double.class, JunctionContext.class,
            Map.class, CorridorTubeSlice.class, EndpointApproachModel.class);
        method.setAccessible(true);
        return (List<Double>) method.invoke(optimizer, track, profiles, row, normalizedSourcePixel,
            junctions, scale, tube.at(row), approaches);
    }

    private CorridorProfile profile(int index, CorridorBand band) {
        List<IntensitySample> samples = java.util.stream.IntStream.rangeClosed(-20, 20)
            .mapToObj(offset -> new IntensitySample(offset, Math.max(0.0, 1.0 - Math.abs(offset) / 20.0),
                Math.max(0.0, 1.0 - Math.abs(offset) / 20.0),
                Math.max(0.0, 1.0 - Math.abs(offset) / 20.0), true)).toList();
        return new CorridorProfile(index, new RenderedHeatmapSampler.CrossSectionProfile(
            new ProfileSamplingAnchor(new EastNorth(index * 2.0, 0.0), index * 2.0, 0.0, index * 2.0),
            new Point2D.Double(0.0, 1.0), List.of(), true, samples), List.of(band), 1.0, 0.0, 1.0, true);
    }
}
