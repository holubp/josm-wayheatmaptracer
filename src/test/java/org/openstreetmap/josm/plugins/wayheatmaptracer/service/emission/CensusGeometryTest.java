package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

class CensusGeometryTest {
    private static EmissionWorkBudget budget() { return new EmissionWorkBudget(TraceBudgets.defaults(), CancellationProbe.NONE); }

    @Test void knownCropReductionAndRoundedSourceLineConstructionEncloseEveryActualPoint() {
        var math = new CensusGeometry(budget()); var grid = CurvedChartCensusTest.croppedGrid();
        var mapping = math.new GridMap(grid); var anchor = new RasterPoint(20.3, 27.9); var normal = new RasterPoint(.3, .7);
        var box = mapping.sourceLine(anchor, normal, -20.1, 31.3);
        for (int i = 0; i <= 100; i++) {
            double u = -20.1 + (31.3 + 20.1) * i / 100;
            var actual = new RasterPoint(anchor.x() + normal.x() * u, anchor.y() + normal.y() * u);
            var nativePoint = grid.toGrid(actual);
            assertTrue(nativePoint.x() >= box.x().lower() && nativePoint.x() <= box.x().upper());
            assertTrue(nativePoint.y() >= box.y().lower() && nativePoint.y() <= box.y().upper());
            var point = mapping.pointBounds(actual);
            assertTrue(nativePoint.x() >= point.x().lower() && nativePoint.x() <= point.x().upper());
            assertTrue(nativePoint.y() >= point.y().lower() && nativePoint.y() <= point.y().upper());
        }
        assertEquals(new RasterPoint(1, 2), grid.toGrid(new RasterPoint(13, 26)));
        assertEquals(new RasterPoint(13, 26), grid.toBaseRaster(new RasterPoint(1, 2)));
    }

    @Test void capturedRoundedChordHasExactPositiveDeterminantAndOutwardRatios() {
        var math = new CensusGeometry(budget());
        var a = new RasterPoint(.1, .2); var b = new RasterPoint(1e10, 1e9);
        var chart = math.new Chart(a, b);
        assertTrue(chart.determinant.signum() > 0);
        var dx = new BigDecimal(b.x() - a.x()); var dy = new BigDecimal(b.y() - a.y());
        var px = new BigDecimal(b.x()).subtract(new BigDecimal(a.x()));
        var py = new BigDecimal(b.y()).subtract(new BigDecimal(a.y()));
        var determinant = dx.multiply(dx).add(dy.multiply(dy));
        assertEquals(0, determinant.compareTo(chart.determinant));
        var exactX = px.multiply(dx).add(py.multiply(dy));
        var exactY = py.multiply(dx).subtract(px.multiply(dy));
        var bounds = chart.coordinates(b);
        for (var item : List.of(new Object[] { exactX, bounds.x() }, new Object[] { exactY, bounds.y() })) {
            var n = (BigDecimal) item[0]; var interval = (CensusGeometry.Interval) item[1];
            assertTrue(new BigDecimal(interval.lower()).multiply(chart.determinant).compareTo(n) <= 0);
            assertTrue(new BigDecimal(interval.upper()).multiply(chart.determinant).compareTo(n) >= 0);
        }
        assertNotEquals(0, chart.determinant.compareTo(exactX), "Rounded B-A is not an exact-real subtraction in general");
        assertThrows(CensusGeometry.Unavailable.class, () -> math.new Chart(a, a));
    }

    @Test void exactClosedCellSweepIncludesVertexContactsAndSeparatesInvalidOutsideAndObservedNodes() {
        var math = new CensusGeometry(budget());
        var all = grid(false, false);
        var horizontal = math.support(all, new RasterPoint(1, 1), new RasterPoint(2, 1));
        assertEquals(6, horizontal.presentCells()); assertEquals(24, horizontal.nodeReads());
        var diagonal = math.support(all, new RasterPoint(.25, .25), new RasterPoint(1.75, 1.75));
        assertEquals(4, diagonal.presentCells());
        var hole = math.support(grid(true, false), new RasterPoint(.25, .25), new RasterPoint(1.75, 1.75));
        assertEquals(3, hole.presentCells()); assertEquals(1, hole.invalidCells()); assertEquals(12, hole.nodeReads());
        var invalidNode = math.support(grid(false, true), new RasterPoint(.25, .25), new RasterPoint(1.75, 1.75));
        assertEquals(4, invalidNode.invalidCells()); assertEquals(16, invalidNode.nodeReads());
        var outside = math.support(all, new RasterPoint(-.5, -.5), new RasterPoint(.5, .5));
        assertEquals(1, outside.presentCells()); assertEquals(3, outside.outsideCells());
        assertThrows(CensusGeometry.Unavailable.class, () -> math.new GridMap(all), "Unknown mapping is unavailable, never inferred from three points");
    }

    @Test void positiveRunProofRetainsGapsAndFallsBackAtHalfFractionIncludingSmallestSubnormal() {
        var math = new CensusGeometry(budget());
        var samples = List.of(sample(-4, 0, true), sample(-2, Double.MIN_VALUE, true), sample(-1, .9, true),
            sample(0, 0, true), sample(2, .8, false), sample(5, .2, true), sample(8, 0, true));
        var positive = CurvedChartCensus.envelope(samples, true, .72, math);
        assertEquals(CurvedChartCensus.EnvelopeKind.POSITIVE_RUN_ENVELOPE, positive.kind());
        assertEquals(List.of(1, 5), positive.runs().stream().map(CurvedChartCensus.Run::firstSample).toList());
        assertEquals(List.of(2, 5), positive.runs().stream().map(CurvedChartCensus.Run::lastSample).toList());
        assertEquals(CurvedChartCensus.Boundary.INVALID_SAMPLE, positive.runs().get(1).leftBoundary());
        assertEquals(3, positive.excludedZeroSamples()); assertEquals(1, positive.excludedInvalidSamples());
        assertEquals(0.0, Double.MIN_VALUE * .5);
        assertTrue(Double.MIN_VALUE * .72 > 0);
        assertEquals(CurvedChartCensus.EnvelopeKind.UNAVAILABLE, CurvedChartCensus.envelope(samples, true, .5, math).kind());
        assertEquals(CurvedChartCensus.EnvelopeKind.VALID_RUN_ENVELOPE, CurvedChartCensus.envelope(samples, false, .5, math).kind());
        assertEquals(CurvedChartCensus.EnvelopeKind.UNAVAILABLE, CurvedChartCensus.envelope(List.of(), false, .72, math).kind());
        assertEquals(CurvedChartCensus.EnvelopeKind.UNAVAILABLE, CurvedChartCensus.envelope(List.of(sample(0, .2, true), sample(0, .3, true)), true, .72, math).kind());
    }

    @Test void unchangedExtractorAcceptedCentersStayInPositiveRunsUnderNonuniformPhysicalCoordinates() {
        var math = new CensusGeometry(budget()); int acceptedCount = 0, weakSwitchbackCount = 0;
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            for (int scenario = 0; scenario < 13; scenario++) {
                var raw = new ArrayList<IntensitySample>(); var physical = new ArrayList<LocalScalarProfileExtractor.Sample>();
                double s = -40;
                for (int i = 0; i < 65; i++) {
                    s += .15 + (i % 5) * .09;
                    double value = switch (scenario) {
                        case 0 -> Math.max(0, 1 - Math.abs(i - 22) / 5.0);
                        case 1 -> Math.max(Math.max(0, .3 * (1 - Math.abs(i - 16) / 5.0)), Math.max(0, .9 * (1 - Math.abs(i - 47) / 5.0)));
                        case 2 -> Math.max(0, 1 - Math.abs(i - 30) / 7.0) + ((i % 3) * .007);
                        case 3 -> .5;
                        case 4 -> 0;
                        case 5 -> i == 30 ? Double.MIN_VALUE : 0;
                        case 6 -> i < 5 || i > 58 ? .8 : Math.max(0, .7 * (1 - Math.abs(i - 35) / 6.0));
                        default -> Math.max(0, .3 * (1 - Math.abs(i - (30 + 9 * Math.sin((scenario - 7) * Math.PI / 2))) / 6.0));
                    };
                    value = Math.min(1, value); boolean valid = i != 10 && i != 52;
                    raw.add(sample(i - 32, value, valid)); physical.add(new LocalScalarProfileExtractor.Sample(s, value, valid));
                }
                var envelope = CurvedChartCensus.envelope(raw, true, .72, math);
                try (var extractionOwner = owner.child("unchanged-extractor-oracle")) {
                    var extraction = new LocalScalarProfileExtractor().extractDetailedWithAcceptedThresholds(physical, .25,
                        EvidenceModelParameters.Localization.defaults(), extractionOwner, CancellationProbe.NONE, ignored -> { });
                    for (var receipt : extraction.acceptedReceipts()) {
                        acceptedCount++; if (scenario >= 7) weakSwitchbackCount++; assertTrue(receipt.shoulderThreshold() > 0);
                        assertTrue(envelope.runs().stream().anyMatch(run -> receipt.mode().centerMeters() >= physical.get(run.firstSample()).offsetMeters()
                            && receipt.mode().centerMeters() <= physical.get(run.lastSample()).offsetMeters()), "No assumed uniform conversion may place the accepted center outside its containing run");
                    }
                }
                if (scenario == 4) assertTrue(envelope.runs().isEmpty(), "Actual all-zero validity/intensity scan proves no positive raw candidate");
            }
        }
        assertTrue(acceptedCount >= 3, "The containment oracle must exercise actual accepted centers");
        assertTrue(weakSwitchbackCount >= 2, "Actual accepted weak displaced/switchback profiles must be included");
        System.out.println("P0 unchanged-extractor acceptedCenters=" + acceptedCount + " weakSwitchbackCenters=" + weakSwitchbackCount);
    }
    private static IntensitySample sample(double offset, double value, boolean valid) { return new IntensitySample(offset, value, value, value, valid); }
    private static NativeScalarGridView grid(boolean hole, boolean nan) {
        return new NativeScalarGridView() {
            public int width() { return 4; } public int height() { return 4; }
            public boolean supportsCell(int x, int y) { return !(hole && x == 1 && y == 1); }
            public double nodeValue(int x, int y) { return nan && x == 1 && y == 1 ? Double.NaN : .2; }
            public RasterPoint toGrid(RasterPoint point) { return point; }
            public RasterPoint toBaseRaster(RasterPoint point) { return point; }
        };
    }
}
