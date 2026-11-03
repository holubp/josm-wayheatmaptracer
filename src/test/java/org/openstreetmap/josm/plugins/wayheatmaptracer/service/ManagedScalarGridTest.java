package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.IntensitySamplingMode;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.BilinearSegmentIntegral;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class ManagedScalarGridTest {
    @Test void exactMappedNodesAndNearestReadsMatchHistoricalPaletteAndDirectSampler() throws Exception {
        BufferedImage image = image(3, 2, 0xFF191257, 0xFF551F80, 0x00000000,
                0xFF8860F2, 0xFFCF9FEA, 0xFF111111);
        Method historical = RenderedHeatmapSampler.class.getDeclaredMethod("intensityAt",
                BufferedImage.class, double.class, double.class, String.class, IntensitySamplingMode.class);
        historical.setAccessible(true);
        RenderedHeatmapSampler sampler = new RenderedHeatmapSampler();
        for (String palette : List.of("hot", "blue", "bluered", "purple", "gray")) {
            for (IntensitySamplingMode mode : IntensitySamplingMode.values()) {
                var owner = AttemptMemoryLedger.production().rootOwner();
                var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
                try {
                    ManagedScalarGrid grid = ManagedScalarGrid.fromAcquiredImage(image, palette, mode, budget, owner);
                    assertEquals(0.0, grid.view().nodeValue(2, 0));
                    assertTrue(grid.view().supportsCell(1, 0),
                            "Acquired transparent source pixels remain valid zero nodes");
                    for (double[] point : new double[][] {
                            {0, 0}, {1, 0}, {2, 0}, {1.499, 1}, {-0.49, 0}, {-0.51, 0},
                            {2.49, 1}, {2.51, 1}, {0.51, 1}, {1.5, 0.5}}) {
                        double expected = (double) historical.invoke(sampler, image, point[0], point[1], palette, mode);
                        assertEquals(Double.doubleToLongBits(expected),
                                Double.doubleToLongBits(grid.sampleNearest(point[0], point[1])),
                                palette + "/" + mode + " at " + point[0] + "," + point[1]);
                    }
                } finally {
                    owner.close();
                }
            }
        }
    }

    @Test void bilinearViewUsesSameNodesAndRejectsOutsideAcquiredImage() {
        BufferedImage image = image(2, 2, 0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF);
        var owner = AttemptMemoryLedger.production().rootOwner();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        try {
            ManagedScalarGrid grid = ManagedScalarGrid.fromAcquiredImage(image, "hot",
                    IntensitySamplingMode.DIRECT_VALUE, budget, owner);
            var view = grid.view();
            assertEquals(0.0, view.nodeValue(0, 0));
            assertEquals(1.0, view.nodeValue(1, 0));
            assertTrue(view.supportsCell(0, 0));
            assertFalse(view.supportsCell(1, 0));
            assertEquals(new RasterPoint(0.5, 0.0), view.toGrid(new RasterPoint(0.5, 0.0)));
            var integral = BilinearSegmentIntegral.evaluate(view, new RasterPoint(0, 0),
                    new RasterPoint(1, 0), CancellationProbe.NONE, () -> { });
            assertEquals(BilinearSegmentIntegral.Status.COMPLETE, integral.status());
            assertEquals(0.5, integral.mean().orElseThrow(), 1e-15);
            assertEquals(BilinearSegmentIntegral.Reason.OUTSIDE_DOMAIN,
                    BilinearSegmentIntegral.evaluate(view, new RasterPoint(-0.1, 0),
                            new RasterPoint(0.5, 0), CancellationProbe.NONE, () -> { }).reason());
            assertEquals(0.0, grid.sampleNearest(-0.49, 0.0));
            assertTrue(Double.isNaN(view.nodeValue(2, 0)));
        } finally {
            owner.close();
        }
    }

    @Test void gridMemoryIsAdmittedBeforeAllocationAndReleasedWithOwner() {
        BufferedImage image = image(2, 2, 0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF);
        var tooSmall = new AttemptMemoryLedger(16);
        var rejected = tooSmall.rootOwner();
        var budget = new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE);
        assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                () -> ManagedScalarGrid.fromAcquiredImage(image, "hot", IntensitySamplingMode.DIRECT_VALUE,
                        budget, rejected));
        assertEquals(0, tooSmall.currentBytes());
        rejected.close();

        var ledger = AttemptMemoryLedger.production();
        var owner = ledger.rootOwner();
        ManagedScalarGrid.fromAcquiredImage(image, "hot", IntensitySamplingMode.DIRECT_VALUE,
                new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE), owner);
        assertTrue(ledger.currentBytes() >= 4L * Double.BYTES);
        owner.close();
        assertEquals(0, ledger.currentBytes());
    }

    @Test void mappingChargesEachSourceNodeAndStopsOnTerminalBudgetOrCancellation() {
        BufferedImage image = image(3, 2, 0xFF000000, 0xFF101010, 0xFF202020,
                0xFF303030, 0xFF404040, 0xFF505050);
        var limitedLedger = AttemptMemoryLedger.production();
        var limitedOwner = limitedLedger.rootOwner();
        var limit = new EmissionWorkBudget(new TraceBudgets(1, 1, 3, 1, 1), CancellationProbe.NONE);
        assertThrows(EmissionWorkBudget.LimitExceededException.class,
                () -> ManagedScalarGrid.fromAcquiredImage(image, "gray", IntensitySamplingMode.COLOR_MAPPING,
                        limit, limitedOwner));
        assertEquals(0, limitedLedger.currentBytes());
        assertThrows(EmissionWorkBudget.LimitExceededException.class,
                () -> ManagedScalarGrid.fromAcquiredImage(image, "gray", IntensitySamplingMode.COLOR_MAPPING,
                        limit, limitedOwner));
        limitedOwner.close();

        var checks = new AtomicInteger();
        var cancelledLedger = AttemptMemoryLedger.production();
        var cancelledOwner = cancelledLedger.rootOwner();
        var cancelled = new EmissionWorkBudget(TraceBudgets.defaults(), () -> checks.incrementAndGet() > 3);
        assertThrows(CancellationException.class,
                () -> ManagedScalarGrid.fromAcquiredImage(image, "gray", IntensitySamplingMode.COLOR_MAPPING,
                        cancelled, cancelledOwner));
        assertEquals(0, cancelledLedger.currentBytes());
        cancelledOwner.close();
    }

    private static BufferedImage image(int width, int height, int... argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        return image;
    }
}
