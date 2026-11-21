package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.*;
import org.openstreetmap.josm.data.coor.*;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.spi.preferences.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;

class InvocationSourceWatchTest {
    @BeforeAll static void setup(){var preferences=new org.openstreetmap.josm.data.Preferences();preferences.enableSaveOnPut(false);Config.setPreferencesInstance(preferences);ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));}
    @Test void deferredSelectionAndModeCannotFollowADifferentCurrentSelection() throws Exception {
        SwingUtilities.invokeAndWait(()->{var fixture=fixture();try(var watch=InvocationSourceWatch.capture(fixture.data,fixture.requested,AlignmentMode.PRECISE_SHAPE)){
            assertDoesNotThrow(()->watch.requireCurrent(fixture.data,fixture.requested,AlignmentMode.PRECISE_SHAPE));
            assertThrows(IllegalStateException.class,()->watch.requireCurrent(fixture.data,fixture.other,AlignmentMode.PRECISE_SHAPE));
            assertThrows(IllegalStateException.class,()->watch.requireCurrent(fixture.data,fixture.requested,AlignmentMode.MOVE_EXISTING_NODES));
            assertThrows(IllegalStateException.class,()->watch.requireCurrent(new DataSet(),fixture.requested,AlignmentMode.PRECISE_SHAPE));
            assertFalse(fixture.data.isModified());
        }});
    }
    @Test void mutationAndRestorationCannotRefreshTheDeferredGesture() throws Exception {
        SwingUtilities.invokeAndWait(()->{var fixture=fixture();try(var watch=InvocationSourceWatch.capture(fixture.data,fixture.requested,AlignmentMode.PRECISE_SHAPE)){
            var node=fixture.requested.segmentNodes().get(0);var original=node.getCoor();node.setCoor(new LatLon(original.lat(),original.lon()+.00001));node.setCoor(original);
            assertThrows(IllegalStateException.class,()->watch.requireCurrent(fixture.data,fixture.requested,AlignmentMode.PRECISE_SHAPE));
        }});
    }
    @Test void onlyMaterialPreferenceEventsInvalidateTheCapturedIntent() throws Exception {
        SwingUtilities.invokeAndWait(()->{var fixture=fixture();try(var watch=InvocationSourceWatch.capture(fixture.data,fixture.requested,AlignmentMode.PRECISE_SHAPE)){
            Config.getPref().putBoolean("wayheatmaptracer.debug",!Config.getPref().getBoolean("wayheatmaptracer.debug",false));
            assertDoesNotThrow(()->watch.requireCurrent(fixture.data,fixture.requested,AlignmentMode.PRECISE_SHAPE));
            String activity=Config.getPref().get("wayheatmaptracer.activity","all");Config.getPref().put("wayheatmaptracer.activity","ride");Config.getPref().put("wayheatmaptracer.activity",activity);
            assertThrows(IllegalStateException.class,()->watch.requireCurrent(fixture.data,fixture.requested,AlignmentMode.PRECISE_SHAPE));
        }});
    }
    private record Fixture(DataSet data,SelectionContext requested,SelectionContext other){ }
    private static Fixture fixture(){
        var data=new DataSet();var a=new Node(new LatLon(50,14));var b=new Node(new LatLon(50,14.001));data.addPrimitive(a);data.addPrimitive(b);
        var way=new Way();way.setNodes(List.of(a,b));data.addPrimitive(way);var other=new Way();other.setNodes(List.of(a,b));data.addPrimitive(other);data.allPrimitives().forEach(p->p.setModified(false));
        return new Fixture(data,new SelectionContext(way,0,1,way.getNodes(),Set.of(a,b)),new SelectionContext(other,0,1,other.getNodes(),Set.of(a,b)));
    }
}
