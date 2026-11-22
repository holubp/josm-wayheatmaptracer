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
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path diagnosticsDirectory;
    @Test void rejectedLazyBootstrapExportsCurrentFailureWithoutInventingNativeInputs() throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        var fixture=V022LiveBPreviewServiceTest.fiveNodeFixture();var service=new LiveBPreviewService();
        var config=V022LiveBPreviewServiceTest.config(TrackerMode.PROBABILISTIC);
        var raw=V022LiveBPreviewServiceTest.raster();var epoch=new VisibleSourceEpoch();
        var raster=new LiveBPreviewService.VisibleRaster(raw.width(),raw.height(),raw.argb(),raw.minimumEast(),raw.minimumNorth(),raw.maximumEast(),raw.maximumNorth(),raw.projectionUnitsPerViewPixel(),raw.groundMetersPerViewPixel(),raw.nativePitchMeters(),raw.sourceIdentity(),raw.projectionCode(),epoch.captureStable());
        var captured=new LiveBPreviewService.Captured[1];SwingUtilities.invokeAndWait(()->captured[0]=service.capture(fixture.dataSet(),fixture.selection(),raster,config,true));
        var computed=service.compute(captured[0],CancellationProbe.NONE);
        var choices=service.adaptChoices(computed,p->ProjectionRegistry.getProjection().latlon2eastNorth(new org.openstreetmap.josm.data.coor.LatLon(p.latitudeDegrees(),p.longitudeDegrees())));
        var publications=new java.util.concurrent.LinkedBlockingQueue<Runnable>();var review=new ModernReviewRoot[1];var workflow=new LegacyRefinementWorkflow[1];
        org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry.setLastBundle(null);
        SwingUtilities.invokeAndWait(()->{
            review[0]=ModernReviewRoot.fromFullChoices(fixture.dataSet(),fixture.selection(),config,choices,null,()->{});
            workflow[0]=new LegacyRefinementWorkflow(review[0],null,review[0]::select,d->{},s->{},task->{throw new java.util.concurrent.RejectedExecutionException();},publications::add);
            workflow[0].enableNativeRuntimeDiagnostics(c->{throw new AssertionError("No prepared root exists");},c->{throw new AssertionError("No prepared root exists");});
            workflow[0].refine(TrackerMode.CORRIDOR_AWARE);
        });
        try {
            SwingUtilities.invokeAndWait(publications.remove());
            SwingUtilities.invokeAndWait(()->{assertFalse(workflow[0].busy());assertThrows(IllegalStateException.class,workflow[0]::currentStage);assertEquals(0,workflow[0].actualNativeCalls());});
            var path=diagnosticsDirectory.resolve("rejected-bootstrap.zip");
            org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry.writeLatest(path.toFile());
            var archive=org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ArchiveReader.read(path);
            var json=new String(archive.artifact("latest-operation.json").orElseThrow().bytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(json.contains("\"phase\":\"BOOTSTRAP\""));assertTrue(json.contains("\"cause\":\"EXECUTOR_REJECTED\""));assertTrue(json.contains("\"requestedEngine\":\"CORRIDOR_AWARE\""));
            assertTrue(json.contains("\"nativeCalls\":0"));assertTrue(json.contains("\"nativeFrozenInputs\":\"unavailable\""));
            assertTrue(archive.artifact("refinement-lineage.json").isEmpty());assertTrue(archive.artifact("original-osm-geographic.csv").isPresent());
        } finally {SwingUtilities.invokeAndWait(()->{workflow[0].close();review[0].close();});org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry.setLastBundle(null);}
    }
    @Test void nativeIntervalRootRetainsExactSelectedOccurrences() throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        var projection=ProjectionRegistry.getProjection();
        var data=new org.openstreetmap.josm.data.osm.DataSet();
        var nodes=new ArrayList<org.openstreetmap.josm.data.osm.Node>();
        for(int i=0;i<=30;i++) {
            var node=new org.openstreetmap.josm.data.osm.Node(projection.eastNorth2latlon(new org.openstreetmap.josm.data.coor.EastNorth((i-15)*10,0)));
            data.addPrimitive(node);nodes.add(node);
        }
        var way=new org.openstreetmap.josm.data.osm.Way();way.setNodes(nodes);way.put("highway","path");data.addPrimitive(way);
        for(int i:List.of(4,12,18,26))nodes.get(i).put("note","fixed boundary");
        for(int i:List.of(8,22)) {
            var remote=new org.openstreetmap.josm.data.osm.Node(projection.eastNorth2latlon(new org.openstreetmap.josm.data.coor.EastNorth((i-15)*10,40)));
            data.addPrimitive(remote);var branch=new org.openstreetmap.josm.data.osm.Way();branch.setNodes(List.of(nodes.get(i),remote));data.addPrimitive(branch);
        }
        var selection=new SelectionContext(way,1,29,nodes.subList(1,30),Set.of(nodes.get(1),nodes.get(29)));
        var config=V022LiveBPreviewServiceTest.config(TrackerMode.PROBABILISTIC);
        // A straight measured primary preserves every original connector. A secondary branch joins
        // that primary exactly at the first eligible interval boundaries; the middle interval has no signal.
        int width=1200,height=120;var pixels=new int[width*height];var epoch=new VisibleSourceEpoch();
        for(int x=0;x<width;x++)for(int y=0;y<height;y++) {
            double east=-200+x/3.0,north=20-y/3.0;
            double branch=east>-140&&east< -110?-4*Math.pow(Math.sin(Math.PI*(east+140)/30),2):0;
            double primary=Math.exp(-.5*north*north/1.44),secondary=Math.exp(-.5*Math.pow(north-branch,2)/1.44);
            // Actual middle trace is x=-40..40. Strict output-grid bilinear reaches one
            // 1.56m cell; capture inverse bilinear adds one1/3m input pixel. Snap
            // ceil(3*(40+1.56+1/3))/3=42 outward in this original pixel frame.
            int gray=Math.abs(east)<=42?0:(int)Math.round(255*(.02+.8*Math.max(primary,secondary)));
            pixels[y*width+x]=0xff000000|gray<<16|gray<<8|gray;
        }
        var raster=new LiveBPreviewService.VisibleRaster(width,height,pixels,-200,-20,200,20,2,2,OptionalDouble.of(2),"visible-interval-root-test","EPSG:3857",epoch.captureStable());
        var service=new LiveBPreviewService();var capture=new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(()->capture[0]=service.capture(data,selection,raster,config,true));
        var computed=service.compute(capture[0],CancellationProbe.NONE);assertTrue(computed.partitioned());assertNull(computed.pipeline());
        assertEquals(1.56,computed.evidence().resolution().resampledPitchMeters().orElseThrow(),1e-12);
        var missing=computed.intervalBatch().runs().stream().filter(run->run.interval().traceRange().firstIndex()==11&&run.interval().traceRange().lastIndex()==19).findFirst().orElseThrow();
        assertEquals("NO_ROUTE",missing.result().inference().status().name());assertTrue(missing.routes().isEmpty());
        var assessment=new FixedIntervalEditPlanComposer().compose(computed.intervalBatch(),Map.of());assertTrue(assessment.plan().isPresent(),assessment.toString());
        var frozen=assessment.intervals().stream().filter(row->row.intervalIndex()==computed.intervalBatch().runs().indexOf(missing)).findFirst().orElseThrow();
        assertEquals(FixedIntervalEditPlanComposer.Disposition.FROZEN_LOCAL_FAILURE,frozen.disposition());assertEquals("NO_PRODUCTION_ROUTE",frozen.reason());
        assertTrue(assessment.intervals().stream().anyMatch(row->row.disposition()==FixedIntervalEditPlanComposer.Disposition.CHANGED));
        var stored=assessment.plan().orElseThrow();var originalWay=(DetachedWay)computed.intervalBatch().network().primitives().get(computed.request().selectedWayKey());
        var afterWay=(DetachedWay)stored.after().primitives().get(originalWay.key());
        var frozenKeys=originalWay.nodeKeys().subList(missing.interval().range().firstIndex(),missing.interval().range().lastIndex()+1);
        int retainedStart=afterWay.nodeKeys().indexOf(frozenKeys.get(0));
        assertTrue(retainedStart>=0);assertEquals(frozenKeys,afterWay.nodeKeys().subList(retainedStart,retainedStart+frozenKeys.size()));
        for(int occurrence=missing.interval().range().firstIndex();occurrence<=missing.interval().range().lastIndex();occurrence++) {
            var key=originalWay.nodeKeys().get(occurrence);assertTrue(afterWay.nodeKeys().contains(key));
            assertEquals(((DetachedNode)computed.intervalBatch().network().primitives().get(key)).coordinate(),((DetachedNode)stored.after().primitives().get(key)).coordinate());
            var id=new FinalRoutePointId.ExistingWayNodeOccurrence(originalWay.key(),key,occurrence);
            assertEquals(computed.evidence().coordinateFrame().toMetric(((DetachedNode)computed.intervalBatch().network().primitives().get(key)).coordinate()),assessment.assignments().get(id));
        }
        assertTrue(assessment.intervals().stream().anyMatch(i->i.disposition()==FixedIntervalEditPlanComposer.Disposition.FROZEN_LOCAL_FAILURE),
            ()->"Native intervals="+computed.intervalBatch().runs().stream().map(run->run.interval().traceRange()+":"+run.result().inference().status()+":routes="+run.routes().size()+":quality="+run.routes().stream().limit(8).map(route->originalRouteFacts(computed.intervalBatch(),run,route)+":"+route.quality().disposition()+":"+route.quality().findings().stream().map(f->f.code().name()).toList()).toList()).toList()+"; composition="+assessment.intervals());
        var root=new ModernReviewRoot[1];
        SwingUtilities.invokeAndWait(()->root[0]=ModernReviewRoot.fromIntervalSelection(data,selection,config,new ModernReviewRoot.IntervalSelection(computed,Map.of(),assessment),null,()->{}));
        try(var memory=AttemptMemoryLedger.production().rootOwner()) {
            var work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,memory);
            try(var prepared=new ModernRefinementBootstrap().complete(root[0],work)) {
                var selected=prepared.root().stage().candidates().get(0);var payload=selected.payload();
                assertSame(assessment.plan().orElseThrow(),payload.nativeReview().orElseThrow().plan());
                var points=root[0].selectedCandidate().finalPreviewPoints();
                assertEquals(nodes.get(1).getEastNorth(),points.get(0));assertEquals(nodes.get(29).getEastNorth(),points.get(points.size()-1));
                assertEquals(points.size(),payload.nativeReview().orElseThrow().finalPointIds().orElseThrow().size());
                assertEquals(assessment,payload.rootReviewState().orElseThrow().intervalAssessment().orElseThrow());
            }
            var alternatives=java.util.stream.IntStream.range(0,computed.intervalBatch().runs().size()).filter(i->computed.intervalBatch().runs().get(i).routes().size()>1).boxed().toList();
            assertFalse(alternatives.isEmpty(),"Public native interval fixture must contain a factual alternative");
            var alternativeChoices=Map.of(alternatives.get(0),1);
            var alternativeAssessment=new FixedIntervalEditPlanComposer().compose(computed.intervalBatch(),alternativeChoices);
            assertTrue(alternativeAssessment.plan().isPresent(),alternativeAssessment.toString());
            var workers=new java.util.concurrent.LinkedBlockingQueue<Runnable>();var publications=new java.util.concurrent.LinkedBlockingQueue<Runnable>();
            var workflow=new LegacyRefinementWorkflow[1];
            SwingUtilities.invokeAndWait(()->{
                workflow[0]=new LegacyRefinementWorkflow(root[0],null,root[0]::select,d->{},s->{},workers::add,publications::add);
                workflow[0].refine(TrackerMode.CORRIDOR_AWARE);
            });
            try {
                VisibleRefinementWorkflowTest.drain(workers,publications,workflow[0]);
                SwingUtilities.invokeAndWait(()->{
                    assertTrue(workflow[0].currentStage().kind().name().startsWith("REFINE"),workflow[0].computationEvidence());
                    ((javax.swing.JButton)VisibleRefinementWorkflowTest.find(workflow[0].controls(),"undo")).doClick();
                    workflow[0].selectIntervalComposition(new ModernReviewRoot.IntervalSelection(computed,alternativeChoices,alternativeAssessment));
                });
                int calls=workflow[0].actualNativeCalls();
                VisibleRefinementWorkflowTest.drain(workers,publications,workflow[0]);
                SwingUtilities.invokeAndWait(()->{
                    assertEquals(calls,workflow[0].actualNativeCalls(),"Root selection materialization performs no inference");
                    var selected=workflow[0].currentStage().candidates().stream().filter(c->c.id().equals(workflow[0].currentStage().selected())).findFirst().orElseThrow();
                    assertEquals(alternativeChoices,selected.payload().rootReviewState().orElseThrow().routeChoices());
                    var selectedId=selected.id();
                    var redo=(javax.swing.JButton)VisibleRefinementWorkflowTest.find(workflow[0].controls(),"redo");assertTrue(redo.isEnabled());redo.doClick();
                    assertNotEquals(selectedId,workflow[0].currentStage().parentChoice().orElseThrow(),"The saved child retains its original exact composition parent");
                    ((javax.swing.JButton)VisibleRefinementWorkflowTest.find(workflow[0].controls(),"undo")).doClick();
                    assertEquals(selectedId,workflow[0].currentStage().selected());assertEquals(calls,workflow[0].actualNativeCalls());
                });
            } finally {SwingUtilities.invokeAndWait(workflow[0]::close);}
        } finally {SwingUtilities.invokeAndWait(root[0]::close);}
    }
    private static String originalRouteFacts(IntervalTraceBatch batch,IntervalTraceBatch.IntervalRun run,ModernTracePipeline.Route route) {
        var way=(DetachedWay)batch.network().primitives().get(batch.fullRequest().selectedWayKey());
        boolean originalIds=route.pointIds().stream().allMatch(id->id instanceof FinalRoutePointId.ExistingWayNodeOccurrence);
        boolean exact=route.pointIds().size()==run.request().selectedRange().size();
        for(int j=0;j<route.pointIds().size();j++) {
            var id=route.pointIds().get(j);int occurrence=run.request().selectedRange().firstIndex()+j;
            if(!(id instanceof FinalRoutePointId.ExistingWayNodeOccurrence existing)||occurrence>run.request().selectedRange().lastIndex()||existing.originalOccurrenceIndex()!=occurrence||!existing.nodeKey().equals(way.nodeKeys().get(occurrence))) {exact=false;continue;}
            var node=(DetachedNode)batch.network().primitives().get(existing.nodeKey());
            if(!batch.evidence().coordinateFrame().toMetric(node.coordinate()).equals(route.assignments().get(id)))exact=false;
        }
        return "points="+route.pointIds().size()+",originalIds="+originalIds+",exactOriginal="+exact;
    }
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
