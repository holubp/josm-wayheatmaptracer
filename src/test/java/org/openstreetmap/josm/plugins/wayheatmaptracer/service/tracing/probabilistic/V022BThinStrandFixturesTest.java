package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;

class V022BThinStrandFixturesTest {
    private static final List<String> IDS = List.of(
            "thin-three-strands", "thin-three-strands-intermittent", "thin-strands-merge-separate",
            "drifting-strong-with-faint-original", "thin-strong-sharp-bend", "thin-faint-coherent-sharp-bend",
            "thin-local-overlap-noise");

    @Test void preregisteredRecipesAreFrozenBeforeEngineCalls() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/b-observed-gap/thin-strand-controls.json")) {
            assertNotNull(stream);
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("FROZEN_BEFORE_ANY_ENGINE_CALL"));
            for (String id : IDS) assertTrue(json.contains("\"" + id + "\""), id);
        }
        assertEquals(IDS, BThinStrandFixtures.all().stream().map(BThinStrandFixtures.Fixture::id).toList());
    }

    @Test void thinTracksOccupyOneNativePixelAndHaveNoBlurTail() {
        var fixture = BThinStrandFixtures.byId("thin-three-strands");
        int column = 32; // x=12m: three paths are separated by more than one native row.
        Set<Integer> expectedRows = new HashSet<>();
        for (var strand : fixture.strands()) {
            assertEquals(1, strand.nativePixelWidth());
            expectedRows.add((int) Math.floor(strand.centerY(column) + 0.5));
        }
        assertEquals(3, expectedRows.size());
        Set<Integer> actualRows = new HashSet<>();
        for (int row = 0; row < fixture.height(); row++)
            if (fixture.intensity(column, row) > fixture.background()) actualRows.add(row);
        assertEquals(expectedRows, actualRows, "each observed strand contributes exactly its nearest native row");
        for (int row : expectedRows) {
            assertEquals(fixture.background(), fixture.intensity(column, row - 1), 0.0);
            assertEquals(fixture.background(), fixture.intensity(column, row + 1), 0.0);
        }
    }

    @Test void intermittentObservationMaskDoesNotTurnRasterPixelsInvalid() {
        var fixture = BThinStrandFixtures.byId("thin-three-strands-intermittent");
        for (int index = 0; index < fixture.strands().size(); index++) {
            var strand = fixture.strands().get(index);
            int observed = 0;
            for (int column = 0; column < fixture.width(); column++) {
                boolean want = (column + 2 * index) % 7 < 5;
                assertEquals(want, strand.observed(column));
                observed += want ? 1 : 0;
                assertTrue(fixture.valid(column, (int) Math.floor(strand.centerY(column) + 0.5)));
            }
            assertTrue(observed > 0 && observed < fixture.width());
        }
        for (boolean value : fixture.validity()) assertTrue(value);
    }

    @Test void mergedStrandsMayShareCellsAndAddTheirSignal() {
        var fixture = BThinStrandFixtures.byId("thin-strands-merge-separate");
        int column = 42; // world x=22m, within the declared merge window.
        Set<Integer> rows = new HashSet<>();
        for (var strand : fixture.strands()) rows.add((int) Math.floor(strand.centerY(column) + 0.5));
        assertEquals(1, rows.size());
        int row = rows.iterator().next();
        double expected = 0.02 + 0.06 * 0.75
                + 0.08 * (0.75 + 0.25 * Math.sin(5.0 * Math.PI / 3.0))
                + 0.06 * (0.75 + 0.25 * Math.sin(7.0 * Math.PI / 3.0));
        assertEquals(expected, fixture.intensity(column, row), 1e-12);
        assertEquals("allowed-actual-merge", fixture.expectedRouteKind());
        assertTrue(fixture.strands().stream().allMatch(strand -> strand.observed(column)));
    }

    @Test void strongDriftingCourseDoesNotEraseTheFaintOriginalStrands() {
        var fixture = BThinStrandFixtures.byId("drifting-strong-with-faint-original");
        assertEquals("strong-course", fixture.expectedRouteKind());
        assertEquals(3, fixture.strongCourse().orElseThrow().nativePixelWidth());
        assertEquals(0.70, fixture.strongCourse().orElseThrow().baseAmplitude(), 0.0);
        int column = 42; // x=22; strong course is offset from the faint course.
        double strongY = fixture.strongCourse().orElseThrow().centerY(column);
        assertEquals(55.0, strongY, 1e-12);
        for (var strand : fixture.strands()) {
            assertTrue(strand.observed(column));
            assertTrue(Math.abs(strand.centerY(column) - strongY) > 2.0);
            int row = (int) Math.floor(strand.centerY(column) + 0.5);
            assertEquals(0.04, fixture.intensity(column, row) - fixture.background(), 1e-12);
        }
    }

    @Test void thinContinuousStrandsCreateLocalOverlappingRawMaximaWithoutBlur() {
        var fixture = BThinStrandFixtures.byId("thin-local-overlap-noise");
        assertEquals("LATENT_REFERENCE_ONE_METRE", fixture.expectedRouteKind());
        assertEquals(3, fixture.strands().size());
        for (var strand : fixture.strands()) {
            assertEquals(1, strand.nativePixelWidth());
            for (int column = 0; column < fixture.width(); column++) assertTrue(strand.observed(column));
        }

        int overlapColumns = 0;
        boolean sawSinglePixelPeak = false;
        boolean sawTwoStrandsSharePixel = false;
        boolean sawThreeStrandsSharePixel = false;
        for (int column = 12; column <= 72; column++) {
            Set<Integer> supportRows = new HashSet<>();
            for (int row = 0; row < fixture.height(); row++)
                if (fixture.intensity(column, row) > fixture.background()) supportRows.add(row);
            assertTrue(supportRows.size() >= 1 && supportRows.size() <= 3);
            if (supportRows.size() < 3) overlapColumns++;
            double maximum = Double.NEGATIVE_INFINITY;
            for (int row : supportRows) maximum = Math.max(maximum, fixture.intensity(column, row));
            sawSinglePixelPeak |= Math.abs(maximum - 0.08) < 1e-12;
            sawTwoStrandsSharePixel |= Math.abs(maximum - 0.14) < 1e-12;
            sawThreeStrandsSharePixel |= Math.abs(maximum - 0.20) < 1e-12;
        }
        assertEquals(24, overlapColumns, "known continuous centerlines locally alias onto native rows");
        assertTrue(sawSinglePixelPeak && sawTwoStrandsSharePixel && sawThreeStrandsSharePixel,
                "unfiltered raw maxima expose one-, two-, and three-strand pixel occupancy");
        for (int column = 12; column <= 72; column++)
            assertTrue(Math.abs(fixture.strands().get(1).centerY(column) - 37.0) <= 0.9,
                    "declared middle strand remains within the fixed one-metre latent reference");
        for (int row = 0; row < fixture.height(); row++) {
            boolean touched = false;
            for (int column = 0; column < fixture.width(); column++)
                touched |= fixture.intensity(column, row) > fixture.background();
            if (touched) assertTrue(row >= 35 && row <= 39, "thin support has no smoothing tail");
        }
    }

    @Test void amplitudeMatchedSharpBendsKeepIdenticalGeometryAndOnePixelSupport() {
        var strong = BThinStrandFixtures.byId("thin-strong-sharp-bend");
        var faint = BThinStrandFixtures.byId("thin-faint-coherent-sharp-bend");
        assertEquals(strong.expectedRouteKind(), faint.expectedRouteKind());
        for (int column = 0; column < strong.width(); column++) {
            assertEquals(strong.strands().get(0).centerY(column), faint.strands().get(0).centerY(column), 0.0);
            assertEquals(strong.strands().get(0).observed(column), faint.strands().get(0).observed(column));
        }
        int apexColumn = 42; // x=22m.
        int apexRow = 40;
        assertEquals(0.70, strong.intensity(apexColumn, apexRow) - strong.background(), 1e-12);
        assertEquals(0.08, faint.intensity(apexColumn, apexRow) - faint.background(), 1e-12);
        assertEquals(faint.background(), faint.intensity(apexColumn, apexRow - 1), 0.0);
    }

    @Test void everyFixtureKeepsCommonNativeFrameAndExactSourceEndpoints() {
        for (var fixture : BThinStrandFixtures.all()) {
            assertEquals(81, fixture.width());
            assertEquals(81, fixture.height());
            assertEquals(-20.0, fixture.originX());
            assertEquals(0.0, fixture.originY());
            assertEquals(new MetricPoint(-20, 0), fixture.origin());
            assertEquals(1.0, fixture.pitchMeters());
            assertEquals(new MetricPoint(-8, 37), fixture.source().get(0));
            assertEquals(new MetricPoint(52, 37), fixture.source().get(1));
            assertEquals(60.0, fixture.selectedSpanMeters());
            assertEquals(-25.0, fixture.lateralMinMeters());
            assertEquals(25.0, fixture.lateralMaxMeters());
            assertEquals(50.0, fixture.lateralMaxMeters() - fixture.lateralMinMeters());
            for (boolean value : fixture.validity()) assertTrue(value);
            for (var strand : fixture.strands()) {
                assertEquals(37.0, strand.centerY(12));
                assertEquals(37.0, strand.centerY(72));
                assertEquals(new MetricPoint(-8, 37), strand.centerline().get(12));
                assertEquals(new MetricPoint(52, 37), strand.centerline().get(72));
                assertEquals(81, strand.observedMask().length);
            }
        }
    }

    @Test void fixtureArraysAreDefensiveCopies() {
        var fixture = BThinStrandFixtures.byId("thin-strong-sharp-bend");
        double before = fixture.intensity(42, 40);
        double[] pixels = fixture.intensities();
        boolean[] validity = fixture.validity();
        double[] centerline = fixture.strands().get(0).centerlineY();
        boolean[] observed = fixture.strands().get(0).observedMask();
        pixels[40 * fixture.width() + 42] = -9;
        validity[40 * fixture.width() + 42] = false;
        centerline[42] = -9;
        observed[42] = false;
        assertEquals(before, fixture.intensity(42, 40), 0.0);
        assertTrue(fixture.valid(42, 40));
        assertTrue(fixture.strands().get(0).observed(42));
        assertEquals(40.0, fixture.strands().get(0).centerY(42));
    }
}
