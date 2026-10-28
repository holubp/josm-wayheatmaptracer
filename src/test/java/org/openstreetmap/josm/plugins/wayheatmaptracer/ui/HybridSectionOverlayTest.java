package org.openstreetmap.josm.plugins.wayheatmaptracer.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid.LegacyBPipeline.VertexRange;

class HybridSectionOverlayTest {
    @Test void highlightKeepsImmutableSlideTimeCoordinatesAndHideClearsThem() {
        var projected=new ArrayList<>(List.of(new EastNorth(100,200),new EastNorth(110,201),
                new EastNorth(120,202),new EastNorth(130,203)));
        var overlay=PreviewOverlay.getInstance();
        try {
            overlay.setHighlightedSection(projected,new VertexRange(1,2));
            projected.clear();
            assertEquals(List.of(new EastNorth(110,201),new EastNorth(120,202)),overlay.highlightedSection());
            // Selection is stored in projected coordinates, independent of any viewport/pan/zoom.
            assertThrows(UnsupportedOperationException.class,()->overlay.highlightedSection().clear());
            assertThrows(IllegalArgumentException.class,()->overlay.setHighlightedSection(
                    List.of(new EastNorth(0,0),new EastNorth(1,1)),new VertexRange(0,2)));
            overlay.hide();
            assertTrue(overlay.highlightedSection().isEmpty());
        } finally { overlay.hide(); }
    }
}
