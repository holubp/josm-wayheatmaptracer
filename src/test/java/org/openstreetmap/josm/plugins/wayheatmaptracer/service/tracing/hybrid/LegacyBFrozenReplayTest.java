package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyVisibleProfileSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;

class LegacyBFrozenReplayTest {
    @Test void canonicalFrozenIdentityIncludesActualLegacyPixelsAndPolicy() {
        var fixture = LegacyBProductionRunnerTest.fixture(false, false);
        var input = fixture.input();
        var stage = FrozenLegacyBInput.visible(fixture.visible(), input.occurrences(), input.frozenRanges(),
                input.simplify(), input.projectedTolerance());
        var options = new ModernTracePipeline.Options(input.fieldName(), GeometryCleanupConfig.disabled(),
                "selected-visible", 0);
        var frozen = new FrozenReplayInput(input.request(), input.evidence(), input.network(), options, 4, stage);
        byte[] bytes = FrozenReplayCodec.encode(frozen);
        assertEquals(6, ByteBuffer.wrap(bytes).getInt(4));
        var decoded = FrozenReplayCodec.decode(bytes);
        assertArrayEquals(bytes, FrozenReplayCodec.encode(decoded));
        assertEquals(frozen.canonicalHash(), decoded.canonicalHash());
        assertArrayEquals(fixture.visible().raster().argb(), decoded.legacyB().visibleSource().raster().argb());
        assertEquals(input.occurrences(), decoded.legacyB().occurrences());
        var raster = fixture.visible().raster();
        int[] pixels = raster.argb(); pixels[0] ^= 1;
        var changedRaster = new LiveBPreviewService.VisibleRaster(raster.width(), raster.height(), pixels,
                raster.minimumEast(), raster.minimumNorth(), raster.maximumEast(), raster.maximumNorth(),
                raster.projectionUnitsPerViewPixel(), raster.groundMetersPerViewPixel(), raster.nativePitchMeters(),
                raster.sourceIdentity(), raster.projectionCode());
        var changedSource = new LegacyVisibleProfileSampler.Input(changedRaster, fixture.visible().anchors(),
                fixture.visible().detectors(), fixture.visible().options());
        var changed = new FrozenReplayInput(input.request(), input.evidence(), input.network(), options, 4,
                FrozenLegacyBInput.visible(changedSource, input.occurrences(), input.frozenRanges(), false, 0));
        assertNotEquals(frozen.canonicalHash(), changed.canonicalHash(),
                "Scalar evidence equality cannot hide changed native Legacy pixels");
        var differentPolicy = new FrozenReplayInput(input.request(), input.evidence(), input.network(), options, 4,
                FrozenLegacyBInput.visible(fixture.visible(), input.occurrences(), input.frozenRanges(), true, 1.5));
        assertNotEquals(frozen.canonicalHash(), differentPolicy.canonicalHash());
        assertThrows(IllegalArgumentException.class, () -> FrozenReplayCodec.decode(Arrays.copyOf(bytes, bytes.length - 1)));
    }

    @Test void strictFinalReplayExecutesActualLegacyAndB() {
        var fixture = LegacyBProductionRunnerTest.fixture(false, false);
        var input = fixture.input();
        var frozen = new FrozenReplayInput(input.request(), input.evidence(), input.network(),
                new ModernTracePipeline.Options(input.fieldName(), GeometryCleanupConfig.disabled(), "selected-visible", 0),
                4, FrozenLegacyBInput.visible(fixture.visible(), input.occurrences(), input.frozenRanges(), false, 0));
        var replay = Format15ReplayRunner.replay(FrozenReplayCodec.decode(FrozenReplayCodec.encode(frozen)),
                ReplayLevel.FINAL_GEOMETRY, TrackerMode.LEGACY_THEN_B);
        assertEquals(TrackerMode.LEGACY_THEN_B, replay.engine());
        assertTrue(replay.inference().evaluatedTransitions() > 0, "Production B cannot be bypassed");
        assertTrue(replay.routes().stream().flatMap(route -> route.hypothesis().points().stream())
                .anyMatch(point -> point.xMeters() > 18 && point.xMeters() < 22 && point.yMeters() > 2.8));
        assertTrue(replay.routes().stream().allMatch(route -> route.hypothesis().posteriorProbability().isEmpty()));
    }
}
