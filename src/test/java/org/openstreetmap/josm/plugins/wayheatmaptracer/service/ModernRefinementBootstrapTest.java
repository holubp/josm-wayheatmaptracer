package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.imagery.VisibleSourceEpoch;
import org.openstreetmap.josm.spi.preferences.*;

class ModernRefinementBootstrapTest {
    @Test void nativeRootSupportsHistoryWithoutLegacyInference() throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        var service=new LiveBPreviewService();
        for(var engine:List.of(TrackerMode.CORRIDOR_AWARE,TrackerMode.PROBABILISTIC))for(var mode:AlignmentMode.values()) {
            var fixture=V022LiveBPreviewServiceTest.fiveNodeFixture();
            var base=V022LiveBPreviewServiceTest.config(engine);
            var config=new AlignmentConfig(base.heatmap().withAlignmentMode(mode),base.cleanup());
            var pixels=V022LiveBPreviewServiceTest.raster();var epoch=new VisibleSourceEpoch();
            // Same world pixels and source scale, cropped around the complete selected decision corridor.
            // The optional8M operation also hashes scalar canonical bytes; a600-square fixture exceeds it.
            int size=320,start=140;var crop=new int[size*size];var full=pixels.argb();
            for(int y=0;y<size;y++)System.arraycopy(full,(y+start)*pixels.width()+start,crop,y*size,size);
            double pitch=pixels.projectionUnitsPerViewPixel()/6;
            var raster=new LiveBPreviewService.VisibleRaster(size,size,crop,pixels.minimumEast()+start*pitch,pixels.maximumNorth()-(start+size)*pitch,pixels.minimumEast()+(start+size)*pitch,pixels.maximumNorth()-start*pitch,pixels.projectionUnitsPerViewPixel(),pixels.groundMetersPerViewPixel(),pixels.nativePitchMeters(),pixels.sourceIdentity(),pixels.projectionCode(),epoch.captureStable());
            var capture=new LiveBPreviewService.Captured[1];
            SwingUtilities.invokeAndWait(()->capture[0]=service.capture(fixture.dataSet(),fixture.selection(),raster,config,true));
            var computed=service.compute(capture[0],CancellationProbe.NONE);
            var choices=service.adaptChoices(computed,g->ProjectionRegistry.getProjection().latlon2eastNorth(new org.openstreetmap.josm.data.coor.LatLon(g.latitudeDegrees(),g.longitudeDegrees())));
            assertFalse(choices.isEmpty());
            var review=new ModernReviewRoot[1];
            SwingUtilities.invokeAndWait(()->{
                review[0]=ModernReviewRoot.fromFullChoices(fixture.dataSet(),fixture.selection(),config,choices,null,()->{});
                review[0].select(choices.get(choices.size()-1).candidate());
            });
            try(var memory=AttemptMemoryLedger.production().rootOwner()) {
                var work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,memory);
                try(var prepared=new ModernRefinementBootstrap().complete(review[0],work)) {
                    assertSame(capture[0].network(),prepared.root().authority().network());
                    assertSame(computed.request(),prepared.root().authority().request());
                    assertEquals(engine,prepared.root().stage().engine());
                    assertEquals("ROOT_NATIVE",prepared.root().stage().kind().name());
                    assertSame(capture[0].originalReceipt().orElseThrow(),prepared.root().receipt());
                    assertTrue(prepared.memory().retainIfAbsent(capture[0]).bytes()>=(long)size*size*4,"Original native raster owner must be admitted");
                    assertDoesNotThrow(()->prepared.memory().retainIfAbsent(computed),"Actual native owner must be admitted");
                    var selected=prepared.root().stage().candidates().stream().filter(c->c.id().equals(prepared.root().stage().selected())).findFirst().orElseThrow();
                    assertEquals(choices.get(choices.size()-1).localRouteIndex(),selected.payload().rootReviewState().orElseThrow().fullRouteIndex().orElseThrow());
                    assertEquals(mode,prepared.root().authority().request().geometryMode());
                    assertSame(capture[0].network(),selected.payload().nativeReview().orElseThrow().plan().before());
                    var workers=new java.util.concurrent.LinkedBlockingQueue<Runnable>();
                    var publications=new java.util.concurrent.LinkedBlockingQueue<Runnable>();
                    var workflow=new LegacyRefinementWorkflow[1];
                    SwingUtilities.invokeAndWait(()->{
                        workflow[0]=new LegacyRefinementWorkflow(review[0],null,review[0]::select,display->{},status->{},workers::add,publications::add);
                        workflow[0].refine(engine==TrackerMode.CORRIDOR_AWARE?TrackerMode.PROBABILISTIC:TrackerMode.CORRIDOR_AWARE);
                    });
                    try {
                        for(int i=0;i<4&&workflow[0].busy();i++) {
                            var worker=workers.poll();assertNotNull(worker);worker.run();
                            SwingUtilities.invokeAndWait(()->{var publication=publications.poll();assertNotNull(publication);publication.run();});
                        }
                        SwingUtilities.invokeAndWait(()->{
                            assertFalse(workflow[0].busy());assertNotNull(workflow[0].currentStage());
                            assertTrue(workflow[0].actualNativeCalls()>0,"Native root refinement must reach actual requested inference");
                        });
                    } finally {SwingUtilities.invokeAndWait(workflow[0]::close);}
                    epoch.sourceChanged();assertThrows(IllegalStateException.class,prepared.source()::requireCurrent);
                }
            } finally {SwingUtilities.invokeAndWait(review[0]::close);}
        }
    }
}
