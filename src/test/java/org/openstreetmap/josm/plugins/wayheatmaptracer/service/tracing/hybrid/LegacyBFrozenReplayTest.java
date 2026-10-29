package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.ReplayLevel;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TrackerMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyVisibleProfileSampler;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.ModernTracePipeline;

class LegacyBFrozenReplayTest {
    @Test void inheritedCliffReplayPreservesActualRefinementAndReviewWithoutChangingContext() {
        var fixture = LegacyBProductionRunnerTest.taggedContextFixture(java.util.Map.of("natural","cliff"),true);
        var input = fixture.input(); var hash = input.network().canonicalHash();
        var options = new ModernTracePipeline.Options(input.fieldName(),GeometryCleanupConfig.disabled(),"selected-visible",0);
        try (var owner = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.production().rootOwner()) {
            var actual = new LegacyBProductionRunner().runVisible(input,fixture.visible(),
                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE,owner,
                    LegacyBPipeline.Deadline.production());
            var stage = FrozenLegacyBInput.capture(new LiveBPreviewService.LegacyBComputation(input,actual.value(),fixture.visible(),null));
            var frozen = new FrozenReplayInput(input.request(),input.evidence(),input.network(),options,4,stage);
            var decoded = FrozenReplayCodec.decode(FrozenReplayCodec.encode(frozen));
            var replay = Format15ReplayRunner.replay(decoded,ReplayLevel.FINAL_GEOMETRY,TrackerMode.LEGACY_THEN_B);
            var expected = LegacyBModernResultAdapter.adapt(actual.value(),input,options,
                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE);
            assertEquals(expected.routes().stream().map(route -> route.hypothesis().points()).toList(),
                    replay.routes().stream().map(route -> route.hypothesis().points()).toList());
            assertEquals(expected.routes().stream().map(route -> route.quality()).toList(),
                    replay.routes().stream().map(route -> route.quality()).toList());
            assertEquals(actual.value().batch().usage().transitions(),replay.inference().evaluatedTransitions());
            assertTrue(replay.inference().evaluatedTransitions()>0);
            assertTrue(replay.routes().stream().anyMatch(route -> route.quality().has(
                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.FindingCode.INDEPENDENT_CONTEXT_CONTACT)));
            assertEquals(hash,decoded.network().canonicalHash());
            assertEquals(hash,input.network().canonicalHash());
        }
    }

    @Test void frozenCaptureUsesActualRefinementAllocationRatherThanDiscoveryCeiling() {
        var fixture = LegacyBProductionRunnerTest.fixture(false,false); var input = fixture.input();
        var allocation = new TraceBudgets(96,8_000_000,10_000,32,8);
        try (var owner = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.production().rootOwner()) {
            var runner = new LegacyBProductionRunner(); var deadline = LegacyBPipeline.Deadline.production();
            var discovery = runner.discoverVisible(input,fixture.visible(),
                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE,owner,deadline);
            var actual = runner.refine(discovery,List.of(discovery.value().seeds().get(0).id()),allocation,
                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE,owner,deadline);
            assertEquals(10_000,actual.value().batch().usage().transitions());
            var stage = FrozenLegacyBInput.capture(new LiveBPreviewService.LegacyBComputation(input,actual.value(),fixture.visible(),null));
            var frozen = new FrozenReplayInput(input.request(),input.evidence(),input.network(),
                    new ModernTracePipeline.Options(input.fieldName(),GeometryCleanupConfig.disabled(),"selected-visible",0),4,stage);
            var replay = Format15ReplayRunner.replay(FrozenReplayCodec.decode(FrozenReplayCodec.encode(frozen)),
                    ReplayLevel.FINAL_GEOMETRY,TrackerMode.LEGACY_THEN_B);
            assertEquals(actual.value().batch().usage().transitions(),replay.inference().evaluatedTransitions(),
                    "Source replay must not receive the larger original discovery ceiling");
        }
    }

    @Test void frozenSourceAdmissionCannotReAdmitOmittedParentsOrInventBWork() {
        var fixture = LegacyBProductionRunnerTest.fixture(false,false); var input = fixture.input();
        var base = FrozenLegacyBInput.visible(fixture.visible(),input.occurrences(),input.frozenRanges(),false,0);
        var options = new ModernTracePipeline.Options(input.fieldName(),GeometryCleanupConfig.disabled(),"selected-visible",0);
        var stage = base.withParentPolicy(new FrozenLegacyBInput.ParentPolicy(List.of(),true));
        var frozen = new FrozenReplayInput(input.request(),input.evidence(),input.network(),options,4,stage);
        byte[] bytes = FrozenReplayCodec.encode(frozen);
        assertEquals(7,ByteBuffer.wrap(bytes).getInt(4));
        var decoded = FrozenReplayCodec.decode(bytes);
        assertArrayEquals(bytes,FrozenReplayCodec.encode(decoded));
        assertEquals(stage.parentPolicy(),decoded.legacyB().parentPolicy());
        var replay = Format15ReplayRunner.replay(decoded,ReplayLevel.FINAL_GEOMETRY,TrackerMode.LEGACY_THEN_B);
        assertEquals(0,replay.inference().evaluatedTransitions());
        assertTrue(replay.routes().isEmpty());
        assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceHypothesisSet.Status.RESOURCE_LIMIT,
                replay.inference().status());
        assertNotEquals(frozen.canonicalHash(),new FrozenReplayInput(input.request(),input.evidence(),input.network(),options,4,base).canonicalHash());
    }

    @Test void frozenExhaustedAllocationKeepsAdmittedLegacyButDoesNotRunB() {
        var fixture = LegacyBProductionRunnerTest.fixture(false,false); var input = fixture.input();
        String parent;
        try (var owner = org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.production().rootOwner()) {
            var discovery = new LegacyBProductionRunner().discoverVisible(input,fixture.visible(),
                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE,owner,
                    LegacyBPipeline.Deadline.production());
            parent = discovery.value().seeds().get(0).id();
        }
        for (var reason : List.of(LegacyBPipeline.Reason.WORK_BUDGET,LegacyBPipeline.Reason.DEADLINE,
                LegacyBPipeline.Reason.RESOURCE_LIMIT)) {
            var stage = FrozenLegacyBInput.visible(fixture.visible(),input.occurrences(),input.frozenRanges(),false,0)
                    .withParentPolicy(new FrozenLegacyBInput.ParentPolicy(List.of(parent),false,reason));
            var frozen = new FrozenReplayInput(input.request(),input.evidence(),input.network(),
                    new ModernTracePipeline.Options(input.fieldName(),GeometryCleanupConfig.disabled(),"selected-visible",0),4,stage);
            var replay = Format15ReplayRunner.replay(FrozenReplayCodec.decode(FrozenReplayCodec.encode(frozen)),
                    ReplayLevel.FINAL_GEOMETRY,TrackerMode.LEGACY_THEN_B);
            assertEquals(0,replay.inference().evaluatedTransitions());
            assertFalse(replay.routes().isEmpty());
            assertTrue(replay.routes().stream().allMatch(route -> route.hypothesis().diagnostics()
                    .getOrDefault("hasBRefinement",0.0) == 0));
            assertTrue(new String(replay.legacyBProvenance().bytes(),java.nio.charset.StandardCharsets.UTF_8)
                    .contains(reason.name()),"A fresh replay deadline must not relabel the actual root stop reason");
        }
    }

    @Test void selfConsistentFrozenPolicyCannotAdmitAnUncapturedParent() {
        var fixture = LegacyBProductionRunnerTest.fixture(false,false); var input = fixture.input();
        var stage = FrozenLegacyBInput.visible(fixture.visible(),input.occurrences(),input.frozenRanges(),false,0)
                .withParentPolicy(new FrozenLegacyBInput.ParentPolicy(List.of("not-a-discovered-parent"),true));
        var frozen = new FrozenReplayInput(input.request(),input.evidence(),input.network(),
                new ModernTracePipeline.Options(input.fieldName(),GeometryCleanupConfig.disabled(),"selected-visible",0),4,stage);
        var decoded = FrozenReplayCodec.decode(FrozenReplayCodec.encode(frozen));
        var failure = assertThrows(ReplayMismatchException.class,() -> Format15ReplayRunner.replay(decoded,
                ReplayLevel.FINAL_GEOMETRY,TrackerMode.LEGACY_THEN_B));
        assertEquals("legacy-parent-admission-mismatch",failure.getMessage());
    }

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
