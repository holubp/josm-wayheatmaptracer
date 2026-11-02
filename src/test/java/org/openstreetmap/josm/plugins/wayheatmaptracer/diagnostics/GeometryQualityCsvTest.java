package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertFalse(row.matches(".*,[0-9]+\\.0{9},1,0\\.0{9},0$"));
        assertTrue(row.matches(".*,[0-9]+\\.[0-9]{9},1,([1-9][0-9]*\\.[0-9]{9}),0$"));
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
