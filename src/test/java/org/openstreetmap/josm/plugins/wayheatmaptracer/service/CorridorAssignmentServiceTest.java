package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CenterlineCandidate;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

class CorridorAssignmentServiceTest {
    @BeforeAll
    static void setPreferences() {
        Config.setPreferencesInstance(new MemoryPreferences());
    }

    @Test
    void penalizesCandidateThatOccupiesCompatibleNeighborCorridor() {
        Way selected = new Way();
        selected.put("highway", "primary");
        List<EastNorth> source = List.of(new EastNorth(0, 0), new EastNorth(100, 0));
        ParallelWayContext neighbor = new ParallelWayContext(42L,
            List.of(new EastNorth(0, 8), new EastNorth(100, 8)), Map.of("highway", "primary", "oneway", "yes"),
            8.0, 1.0, 1.0, 1.0);
        CenterlineCandidate own = candidate("own", 1.0, 1.0);
        CenterlineCandidate occupied = candidate("occupied", 1.0, 8.0);

        CorridorAssignmentService.AssignmentResult result = new CorridorAssignmentService().assign(
            List.of(own, occupied), selected, source, List.of(neighbor), 10.0);

        double ownScore = result.candidates().stream().filter(candidate -> candidate.id().startsWith("own")).findFirst().orElseThrow().score();
        double occupiedScore = result.candidates().stream().filter(candidate -> candidate.id().startsWith("occupied")).findFirst().orElseThrow().score();
        assertTrue(ownScore > occupiedScore);
        assertTrue(result.decisions().stream().anyMatch(decision -> decision.reservedByWayIds().contains(42L)));
    }

    private CenterlineCandidate candidate(String id, double score, double north) {
        return new CenterlineCandidate(id, score,
            List.of(new Point2D.Double(0, north), new Point2D.Double(100, north)), List.of(north, north))
            .withEastNorthPoints(List.of(new EastNorth(0, north), new EastNorth(100, north)));
    }
}
