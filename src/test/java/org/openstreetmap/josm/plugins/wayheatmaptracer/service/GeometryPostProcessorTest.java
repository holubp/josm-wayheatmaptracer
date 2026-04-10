package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;

class GeometryPostProcessorTest {
    @Test
    void simplificationKeepsEndpoints() {
        GeometryPostProcessor processor = new GeometryPostProcessor();
        List<EastNorth> input = List.of(
            new EastNorth(0, 0),
            new EastNorth(1, 0.01),
            new EastNorth(2, 0.0),
            new EastNorth(3, 0.01),
            new EastNorth(4, 0.0)
        );

        List<EastNorth> simplified = processor.simplify(input, 0.1);

        assertEquals(input.get(0), simplified.get(0));
        assertEquals(input.get(input.size() - 1), simplified.get(simplified.size() - 1));
        assertTrue(simplified.size() < input.size());
    }

    @Test
    void simplificationPreservesSwitchbackApex() {
        GeometryPostProcessor processor = new GeometryPostProcessor();
        List<EastNorth> input = List.of(
            new EastNorth(0, 0),
            new EastNorth(10, 0),
            new EastNorth(20, 0),
            new EastNorth(25, 8),
            new EastNorth(20, 16),
            new EastNorth(10, 16),
            new EastNorth(0, 16)
        );

        List<EastNorth> simplified = processor.simplify(input, 50.0);

        assertTrue(simplified.contains(new EastNorth(25, 8)), "Switchback apex should be preserved");
        assertTrue(simplified.size() >= 3, "Switchback should not collapse to a single straight segment");
    }
}
