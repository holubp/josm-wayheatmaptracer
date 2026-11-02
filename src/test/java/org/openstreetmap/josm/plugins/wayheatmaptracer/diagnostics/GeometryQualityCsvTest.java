package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;

class GeometryQualityCsvTest {
    @BeforeAll
    static void setupProjection() {
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    @Test
    void measuresExactFinalPreviewTurnWithFrozenProjectionCode() {
        CenterlineCandidate candidate = new CenterlineCandidate("candidate-1", 1.0,
            List.of(new Point2D.Double(0, 0), new Point2D.Double(10, 0)), List.of(0.0, 0.0))
            .withEastNorthPoints(List.of(new EastNorth(0, 0), new EastNorth(10, 0)))
            .withFinalPreviewGeometry(List.of(new EastNorth(0, 0), new EastNorth(100, 0),
                new EastNorth(100, 100)), Map.of());
        String csv = GeometryQualityCsv.create(List.of(candidate), "candidate-1",
            List.of(new EastNorth(0, 0), new EastNorth(100, 0), new EastNorth(100, 100)),
            "EPSG:3857", false, List.of());
        assertTrue(csv.contains("\"candidate-1\",final-preview,available,"));
        String row = csv.lines().filter(line -> line.startsWith("\"candidate-1\",final-preview,")).findFirst().orElseThrow();
        String[] fields = row.split(",", -1);
        assertEquals(199.33056, Double.parseDouble(fields[5]), 0.02);
        assertEquals(1, Integer.parseInt(fields[6]));
        assertEquals(90.0, Double.parseDouble(fields[7]), 1e-7);
    }

    @Test
    void highLatitudeLengthUsesCertifiedGroundMetersRatherThanProjectionUnits() {
        EastNorth start = new EastNorth(0, 15_538_711.09630922);
        EastNorth end = new EastNorth(100, 15_538_711.09630922);
        CenterlineCandidate candidate = new CenterlineCandidate("high-lat", 1.0,
            List.of(new Point2D.Double(0, 0), new Point2D.Double(10, 0)), List.of(0.0, 0.0))
            .withEastNorthPoints(List.of(start, end)).withFinalPreviewGeometry(List.of(start, end), Map.of());
        String csv = GeometryQualityCsv.create(List.of(candidate), "high-lat", List.of(start, end),
            "EPSG:3857", false, List.of());
        String row = csv.lines().filter(line -> line.startsWith("\"high-lat\",final-preview,")).findFirst().orElseThrow();
        double actualMeters = Double.parseDouble(row.split(",", -1)[5]);
        double latitudeRadians = Math.toRadians(80.0);
        double eccentricitySquared = 6.69437999014e-3;
        double expectedMeters = 100.0 * Math.cos(latitudeRadians)
            / Math.sqrt(1.0 - eccentricitySquared * Math.sin(latitudeRadians) * Math.sin(latitudeRadians));
        assertEquals(expectedMeters, actualMeters, 0.02);
        assertTrue(actualMeters < 25.0);
    }

    @Test
    void selectedStoredPreviewMismatchIsExplicitlyUnavailable() {
        List<EastNorth> stored = List.of(new EastNorth(0, 0), new EastNorth(100, 0), new EastNorth(100, 100));
        List<EastNorth> displayed = List.of(new EastNorth(0, 0), new EastNorth(50, 50), new EastNorth(100, 0));
        CenterlineCandidate selected = new CenterlineCandidate("selected", 1.0,
            List.of(new Point2D.Double(0, 0), new Point2D.Double(10, 0)), List.of(0.0, 0.0))
            .withEastNorthPoints(List.of(stored.get(0), stored.get(2))).withFinalPreviewGeometry(stored, Map.of());
        CenterlineCandidate other = new CenterlineCandidate("other", 0.5,
            List.of(new Point2D.Double(0, 0), new Point2D.Double(10, 0)), List.of(0.0, 0.0))
            .withEastNorthPoints(List.of(stored.get(0), stored.get(2))).withFinalPreviewGeometry(stored, Map.of());
        String csv = GeometryQualityCsv.create(List.of(selected, other), "selected", displayed,
            "EPSG:3857", false, List.of());
        String selectedRow = csv.lines().filter(line -> line.startsWith("\"selected\",final-preview,")).findFirst().orElseThrow();
        assertTrue(selectedRow.startsWith("\"selected\",final-preview,unavailable,\"review-preview-mismatch\""));
        String[] selectedFields = selectedRow.split(",", -1);
        assertEquals("", selectedFields[5]);
        assertEquals("", selectedFields[6]);
        assertEquals("", selectedFields[7]);
        String otherRow = csv.lines().filter(line -> line.startsWith("\"other\",final-preview,")).findFirst().orElseThrow();
        assertTrue(otherRow.startsWith("\"other\",final-preview,available,"));
    }

    @Test
    void missingFrozenProjectionIsUnavailableRatherThanZero() {
        CenterlineCandidate candidate = new CenterlineCandidate("candidate-1", 1.0,
            List.of(new Point2D.Double(0, 0), new Point2D.Double(10, 0)), List.of(0.0, 0.0))
            .withEastNorthPoints(List.of(new EastNorth(0, 0), new EastNorth(10, 10)));
        String csv = GeometryQualityCsv.create(List.of(candidate), "candidate-1",
            List.of(), "", false, List.of());
        assertTrue(csv.contains("\"candidate-1\",raw,unavailable,\"missing-projection-code\""));
        assertFalse(csv.contains(",0.000000000,0,"));
    }
}
