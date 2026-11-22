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
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayCodec;
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
            workflow[0].enableNativeRuntimeDiagnostics(c->{throw new AssertionError("No prepared root exists");},c->{throw new AssertionError("No prepared root exists");},
                ()->org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15Artifact.text("original-native-work.json",
                    "{\"scope\":\"original-production-attempt\",\"requestHash\":\""+FrozenReplayCodec.canonicalRequestHash(computed.request())+"\",\"transitions\":"+computed.pipeline().inference().evaluatedTransitions()+"}"));
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
            assertTrue(json.contains("\"originalNativeWork\":\"available\""));
            var originalWork=new String(archive.artifact("original-native-work.json").orElseThrow().bytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(originalWork.contains(FrozenReplayCodec.canonicalRequestHash(computed.request())));
            assertTrue(originalWork.contains("\"transitions\":"+computed.pipeline().inference().evaluatedTransitions()));
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
            // Actual middle trace is x=-40..40. RAW_HIGH_RESOLUTION retains this
            // 1/3m grid. One output bilinear cell plus one input pixel reaches
            // ceil(3*(40+1/3+1/3))/3=40 2/3m in the original pixel frame.
            int gray=intervalGray(east,north);
            pixels[y*width+x]=0xff000000|gray<<16|gray<<8|gray;
        }
        var raster=new LiveBPreviewService.VisibleRaster(width,height,pixels,-200,-20,200,20,2,2,OptionalDouble.of(2),"visible-interval-root-test","EPSG:3857",epoch.captureStable());
        var service=new LiveBPreviewService();var capture=new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(()->capture[0]=service.capture(data,selection,raster,config,true));
        var computed=service.compute(capture[0],CancellationProbe.NONE);assertTrue(computed.partitioned());assertNull(computed.pipeline());
        assertProducedEvidenceProof(computed);
        assertEquals(1./3,computed.evidence().resolution().resampledPitchMeters().orElseThrow(),1e-12);
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
            var recaptures=new java.util.concurrent.atomic.AtomicInteger();
            var failRender=new java.util.concurrent.atomic.AtomicBoolean();
            var cancelRender=new java.util.concurrent.atomic.AtomicBoolean();
            var renderer=new VisibleRefinementCapture.Renderer(){
                public void requireCurrent(VisibleRefinementCapture.Request request){epoch.requireCurrent(raster.sourceReceipt());}
                public VisibleRefinementCapture.Viewport snapshot(){return new VisibleRefinementCapture.Viewport(0,0,2,200,20);}
                public void restore(VisibleRefinementCapture.Viewport saved){}
                public VisibleRefinementCapture.RenderedChunk render(VisibleRefinementCapture.Request request,VisibleRefinementCapture.Chunk chunk){
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    if(failRender.get())throw new IllegalStateException("public-synthetic-render-refusal");
                    if(cancelRender.get())workflow[0].cancel();
                    var grid=request.evidencePlan().grid();assertTrue(grid.coordinateFrame().isCertifiedExtensionOf(computed.evidence().coordinateFrame()));
                    for(var polygon:grid.footprint().polygons())for(var point:polygon)assertDoesNotThrow(()->grid.coordinateFrame().toGeographic(point));
                    var image=new java.awt.image.BufferedImage(chunk.viewWidth()*6,chunk.viewHeight()*6,java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){
                        double east=chunk.bounds().minimumEast()+x*2./6,north=chunk.bounds().maximumNorth()-y*2./6;int gray=intervalGray(east,north);
                        image.setRGB(x,y,0xff000000|gray<<16|gray<<8|gray);
                    }
                    return new VisibleRefinementCapture.RenderedChunk(image,chunk.bounds(),-1,OptionalDouble.of(2),2);
                }
            };
            var capturePort=new VisibleRefinementCapture(renderer,SwingUtilities::invokeLater,java.util.concurrent.ForkJoinPool.commonPool(),320,320).port();
            SwingUtilities.invokeAndWait(()->{
                workflow[0]=new LegacyRefinementWorkflow(root[0],null,root[0]::select,d->{},s->{},workers::add,publications::add,(request,context)->{recaptures.incrementAndGet();return capturePort.capture(request,context);});
                workflow[0].enableNativeRuntimeDiagnostics(candidate->null,candidate->null);
                workflow[0].refine(TrackerMode.CORRIDOR_AWARE);
            });
            try {
                VisibleRefinementWorkflowTest.drain(workers,publications,workflow[0]);
                SwingUtilities.invokeAndWait(()->{
                    assertEquals(1,recaptures.get(),"A must attempt one bounded same-source capture when the original B image lacks its halo");
                    var diagnostic=workflow[0].latestAttemptDiagnostics().orElseThrow(()->new AssertionError("Latest diagnostics "+workflow[0].attemptDiagnosticsAvailability()+"; "+workflow[0].computationEvidence()));
                    var sourceWork=diagnostic.sourcePreparation().orElseThrow(()->new AssertionError("Source work unavailable: "+diagnostic.phase()+"/"+diagnostic.cause()+"; "+workflow[0].computationEvidence()));
                    assertEquals(RefinementAttemptDiagnostics.Coverage.RECAPTURED,sourceWork.sourceFacts().orElseThrow().coverage(),()->diagnostic.phase()+"/"+diagnostic.cause()+"; auxiliary="+sourceWork.visits()+"; "+workflow[0].computationEvidence());
                    assertTrue(workflow[0].currentStage().kind().name().startsWith("REFINE"),workflow[0].computationEvidence()+";actualSourceWork="+sourceWork);
                    assertSame(computed.evidence().coordinateFrame(),workflow[0].currentNative().orElseThrow().plan().metricFrame());
                    assertSame(capture[0].network(),workflow[0].currentNative().orElseThrow().plan().before());
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
                var retainedRoot=VisibleRefinementWorkflowTest.stage(workflow[0]);
                for(boolean cancel:List.of(false,true)) {
                    SwingUtilities.invokeAndWait(()->{
                        assertNotEquals(ValidationReport.Disposition.HARD_BLOCKED,workflow[0].currentNative().orElseThrow().plan().validation().disposition());
                        if(!workflow[0].canApplyNative())workflow[0].confirm();assertTrue(workflow[0].canApplyNative());
                        failRender.set(!cancel);cancelRender.set(cancel);workflow[0].refine(TrackerMode.CORRIDOR_AWARE);
                    });
                    VisibleRefinementWorkflowTest.drain(workers,publications,workflow[0]);
                    SwingUtilities.invokeAndWait(()->{
                        assertSame(retainedRoot,workflow[0].currentStage());assertEquals(calls,workflow[0].actualNativeCalls());assertFalse(workflow[0].busy());
                        var diagnostic=workflow[0].latestAttemptDiagnostics().orElseThrow();
                        assertEquals(cancel?RefinementAttemptDiagnostics.Cause.CANCELLED:RefinementAttemptDiagnostics.Cause.FAILED,diagnostic.cause());
                        assertEquals(RefinementAttemptDiagnostics.Coverage.CAPTURE_REQUESTED,diagnostic.sourcePreparation().orElseThrow().sourceFacts().orElseThrow().coverage());
                        assertTrue(((javax.swing.JButton)VisibleRefinementWorkflowTest.find(workflow[0].controls(),"redo")).isEnabled());
                        if(!workflow[0].canApplyNative())workflow[0].confirm();assertTrue(workflow[0].canApplyNative(),"Source capture refusal/cancellation must preserve ordinary Apply on the exact safe retained parent");
                    });
                }
                assertEquals(3,recaptures.get());
            } finally {SwingUtilities.invokeAndWait(workflow[0]::close);}
        } finally {SwingUtilities.invokeAndWait(root[0]::close);}
    }
    private static int intervalGray(double east,double north) {
        double branch=east>-140&&east< -110?-4*Math.pow(Math.sin(Math.PI*(east+140)/30),2):0;
        double primary=Math.exp(-.5*north*north/1.44),secondary=Math.exp(-.5*Math.pow(north-branch,2)/1.44);
        return Math.abs(east)<=40+2./3?0:(int)Math.round(255*(.02+.8*Math.max(primary,secondary)));
    }
    @Test void compactNativeRecaptureKeepsSuccessfulHistoryParentIdentityAndStoredCommand() throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        var fixture=V022LiveBPreviewServiceTest.fiveNodeFixture();var config=V022LiveBPreviewServiceTest.config(TrackerMode.PROBABILISTIC);
        // Purpose-owned ordinary OSM error makes the first native plan a real edit. The measured
        // primary, fixed endpoints, acquisition policy and budgets remain the same public input.
        SwingUtilities.invokeAndWait(()->{for(int i=1;i<4;i++){var node=fixture.selection().segmentNodes().get(i);var point=node.getEastNorth();node.setCoor(ProjectionRegistry.getProjection().eastNorth2latlon(new org.openstreetmap.josm.data.coor.EastNorth(point.east(),point.north()+1)));}});
        var epoch=new VisibleSourceEpoch();int width=320,height=160;var pixels=new int[width*height];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){int gray=compactGray(-width/12.+x/6.,height/12.-y/6.);pixels[y*width+x]=0xff000000|gray<<16|gray<<8|gray;}
        var raster=new LiveBPreviewService.VisibleRaster(width,height,pixels,-width/12.,-height/12.,width/12.,height/12.,1,1,OptionalDouble.of(1),"compact-native-recapture","EPSG:3857",epoch.captureStable());
        var service=new LiveBPreviewService();var capture=new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(()->capture[0]=service.capture(fixture.dataSet(),fixture.selection(),raster,config,true));
        var computed=service.compute(capture[0],CancellationProbe.NONE);
        assertProducedEvidenceProof(computed);
        var choices=service.adaptChoices(computed,p->ProjectionRegistry.getProjection().latlon2eastNorth(new org.openstreetmap.josm.data.coor.LatLon(p.latitudeDegrees(),p.longitudeDegrees())));
        assertFalse(choices.isEmpty(),"The compact same-policy capture must produce an actual native root choice");
        var initial=choices.get(choices.size()-1);var initialAssessment=new ModernSingleWayEditPlanAdapter().assess(initial.owner(),initial.localRouteIndex());
        assertTrue(initialAssessment.plan().isPresent(),()->"Compact actual native root: "+initialAssessment.availability()+"; "+initialAssessment.detail()
            +"; quality="+initial.owner().pipeline().routes().get(initial.localRouteIndex()).quality().findings());
        // Alternate native root/source selection with a saved child is exercised by the actual
        // managed interval GUI smoke. This compact public scene has one factual native choice;
        // it qualifies recapture, exact parent ownership, cached history and detached commands.
        var workers=new java.util.concurrent.LinkedBlockingQueue<Runnable>();var publications=new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        var review=new ModernReviewRoot[1];var workflow=new LegacyRefinementWorkflow[1];var paints=new java.util.concurrent.atomic.AtomicInteger();
        var renderer=new VisibleRefinementCapture.Renderer(){
            public void requireCurrent(VisibleRefinementCapture.Request request){epoch.requireCurrent(raster.sourceReceipt());}
            public VisibleRefinementCapture.Viewport snapshot(){return new VisibleRefinementCapture.Viewport(0,0,1,54,27);}
            public void restore(VisibleRefinementCapture.Viewport saved){}
            public VisibleRefinementCapture.RenderedChunk render(VisibleRefinementCapture.Request request,VisibleRefinementCapture.Chunk chunk){
                assertTrue(SwingUtilities.isEventDispatchThread());paints.incrementAndGet();
                assertTrue(request.evidencePlan().grid().coordinateFrame().isCertifiedExtensionOf(computed.evidence().coordinateFrame()));
                for(var polygon:request.evidencePlan().grid().footprint().polygons())for(var point:polygon)assertDoesNotThrow(()->request.evidencePlan().grid().coordinateFrame().toGeographic(point));
                var image=new java.awt.image.BufferedImage(chunk.viewWidth()*6,chunk.viewHeight()*6,java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int gray=compactGray(chunk.bounds().minimumEast()+x/6.,chunk.bounds().maximumNorth()-y/6.);image.setRGB(x,y,0xff000000|gray<<16|gray<<8|gray);}
                return new VisibleRefinementCapture.RenderedChunk(image,chunk.bounds(),-1,OptionalDouble.of(1),1);
            }
        };
        var port=new VisibleRefinementCapture(renderer,SwingUtilities::invokeLater,java.util.concurrent.ForkJoinPool.commonPool(),320,320).port();
        var originalNodes=List.copyOf(fixture.selection().way().getNodes());var originalCoordinates=originalNodes.stream().map(org.openstreetmap.josm.data.osm.Node::getCoor).toList();
        var originalFlags=fixture.dataSet().allPrimitives().stream().collect(java.util.stream.Collectors.toMap(p->p.getUniqueId(),p->List.of(p.isModified(),p.isDeleted())));
        SwingUtilities.invokeAndWait(()->{
            review[0]=ModernReviewRoot.fromFullChoices(fixture.dataSet(),fixture.selection(),config,choices,null,()->{});review[0].select(choices.get(choices.size()-1).candidate());
            workflow[0]=new LegacyRefinementWorkflow(review[0],null,review[0]::select,d->{},s->{},workers::add,publications::add,port);
            workflow[0].refine(TrackerMode.CORRIDOR_AWARE);
        });
        try {
            VisibleRefinementWorkflowTest.drain(workers,publications,workflow[0]);
            SwingUtilities.invokeAndWait(()->assertDoesNotThrow(workflow[0]::currentStage,workflow[0].computationEvidence()));
            var first=VisibleRefinementWorkflowTest.stage(workflow[0]);assertEquals(RefinementModels.StageKind.REFINE_A,first.kind(),workflow[0].computationEvidence());
            assertTrue(workflow[0].actualNativeCalls()>0);assertTrue(paints.get()>0);
            var originalParent=first.parentChoice().orElseThrow();int calls=workflow[0].actualNativeCalls(),captured=paints.get();
            SwingUtilities.invokeAndWait(()->{
                ((javax.swing.JButton)VisibleRefinementWorkflowTest.find(workflow[0].controls(),"undo")).doClick();
                var selector=(javax.swing.JComboBox<?>)VisibleRefinementWorkflowTest.find(workflow[0].controls(),"candidate");selector.setSelectedIndex(0);
                var selected=workflow[0].currentStage().selected();assertEquals(originalParent,selected);
                var redo=(javax.swing.JButton)VisibleRefinementWorkflowTest.find(workflow[0].controls(),"redo");assertTrue(redo.isEnabled());redo.doClick();
                assertSame(first,workflow[0].currentStage());assertEquals(originalParent,workflow[0].currentStage().parentChoice().orElseThrow());
                ((javax.swing.JButton)VisibleRefinementWorkflowTest.find(workflow[0].controls(),"undo")).doClick();assertEquals(selected,workflow[0].currentStage().selected());
                assertEquals(calls,workflow[0].actualNativeCalls());assertEquals(captured,paints.get());redo.doClick();
                workflow[0].smooth(org.openstreetmap.josm.plugins.wayheatmaptracer.ui.RefinementControls.Strength.MEDIUM);
            });
            VisibleRefinementWorkflowTest.drain(workers,publications,workflow[0]);
            var smoothed=VisibleRefinementWorkflowTest.stage(workflow[0]);assertEquals(calls,workflow[0].actualNativeCalls());assertEquals(captured,paints.get());
            SwingUtilities.invokeAndWait(()->{
                assertEquals(RefinementModels.StageKind.SMOOTH,workflow[0].latestAttemptDiagnostics().orElseThrow().binding().ticket().kind());
                workflow[0].refine(TrackerMode.PROBABILISTIC);
            });
            VisibleRefinementWorkflowTest.drain(workers,publications,workflow[0]);
            SwingUtilities.invokeAndWait(()->{
                assertEquals(RefinementModels.StageKind.REFINE_B,workflow[0].currentStage().kind(),workflow[0].computationEvidence());
                assertEquals(smoothed.id(),workflow[0].currentStage().parent().orElseThrow());assertEquals(smoothed.selected(),workflow[0].currentStage().parentChoice().orElseThrow());
                assertSame(capture[0].network(),workflow[0].currentNative().orElseThrow().plan().before());assertSame(computed.evidence().coordinateFrame(),workflow[0].currentNative().orElseThrow().plan().metricFrame());
                VisibleRefinementWorkflowTest.assertApplicableAfterRequiredConfirmation(workflow[0]);
                var command=workflow[0].command();workflow[0].close();review[0].close();assertTrue(command.executeCommand());
                var applied=fixture.selection().way().getNodes().stream().map(org.openstreetmap.josm.data.osm.Node::getCoor).toList();
                command.undoCommand();assertEquals(originalNodes,fixture.selection().way().getNodes());assertEquals(originalCoordinates,originalNodes.stream().map(org.openstreetmap.josm.data.osm.Node::getCoor).toList());
                assertTrue(command.executeCommand());assertEquals(applied,fixture.selection().way().getNodes().stream().map(org.openstreetmap.josm.data.osm.Node::getCoor).toList());command.undoCommand();
                assertEquals(originalNodes,fixture.selection().way().getNodes());assertEquals(originalCoordinates,originalNodes.stream().map(org.openstreetmap.josm.data.osm.Node::getCoor).toList());
                assertEquals(originalFlags,fixture.dataSet().allPrimitives().stream().collect(java.util.stream.Collectors.toMap(p->p.getUniqueId(),p->List.of(p.isModified(),p.isDeleted()))));
            });
        }finally{SwingUtilities.invokeAndWait(()->{workflow[0].close();review[0].close();});}
    }
    private static int compactGray(double east,double north) {
        double branch=east> -8&&east<8?3*Math.pow(Math.sin(Math.PI*(east+8)/16),2):0;
        double primary=Math.exp(-.5*north*north/.25),secondary=Math.exp(-.5*Math.pow(north-branch,2)/.25);
        return (int)Math.round(255*(.02+.8*Math.max(primary,secondary)));
    }
    @Test void unchangedNativeRootsKeepExactFinalOccurrencesAndAllowOptionalEntryWithoutAnIdentityCommand() throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        for(var mode:AlignmentMode.values()) {
            var fixture=V022LiveBPreviewServiceTest.fiveNodeFixture();var base=V022LiveBPreviewServiceTest.config(TrackerMode.PROBABILISTIC);
            var config=new AlignmentConfig(base.heatmap().withAlignmentMode(mode),base.cleanup());var epoch=new VisibleSourceEpoch();
            int width=320,height=160;var pixels=new int[width*height];
            for(int y=0;y<height;y++)for(int x=0;x<width;x++){int gray=compactGray(-width/12.+x/6.,height/12.-y/6.);pixels[y*width+x]=0xff000000|gray<<16|gray<<8|gray;}
            var raster=new LiveBPreviewService.VisibleRaster(width,height,pixels,-width/12.,-height/12.,width/12.,height/12.,1,1,OptionalDouble.of(1),"unchanged-native-root","EPSG:3857",epoch.captureStable());
            var service=new LiveBPreviewService();var capture=new LiveBPreviewService.Captured[1];
            SwingUtilities.invokeAndWait(()->capture[0]=service.capture(fixture.dataSet(),fixture.selection(),raster,config,true));
            var computed=service.compute(capture[0],CancellationProbe.NONE);
            var choices=service.adaptChoices(computed,g->ProjectionRegistry.getProjection().latlon2eastNorth(new org.openstreetmap.josm.data.coor.LatLon(g.latitudeDegrees(),g.longitudeDegrees())));
            var selected=choices.stream().filter(c->new ModernSingleWayEditPlanAdapter().assess(c.owner(),c.localRouteIndex()).availability()==ModernSingleWayEditPlanAdapter.ApplyAvailability.NO_CHANGE).findFirst().orElseThrow(()->new AssertionError("Actual native no-change proof required in "+mode));
            var assessment=new ModernSingleWayEditPlanAdapter().assess(selected.owner(),selected.localRouteIndex());
            var expected=assessment.noChangeProof().orElseThrow().finalSelection().selectedGeometry().stream().map(g->ProjectionRegistry.getProjection().latlon2eastNorth(new org.openstreetmap.josm.data.coor.LatLon(g.latitudeDegrees(),g.longitudeDegrees()))).toList();
            var original=fixture.selection().way().getNodes().stream().map(org.openstreetmap.josm.data.osm.Node::getCoor).toList();
            var originalNodes=List.copyOf(fixture.selection().way().getNodes());var flags=fixture.dataSet().allPrimitives().stream().collect(java.util.stream.Collectors.toMap(p->p.getUniqueId(),p->List.of(p.isModified(),p.isDeleted())));
            var host=org.openstreetmap.josm.data.UndoRedoHandler.getInstance();var undo=List.copyOf(host.getUndoCommands());var redo=List.copyOf(host.getRedoCommands());
            var review=new ModernReviewRoot[1];var workflow=new LegacyRefinementWorkflow[1];var workers=new java.util.concurrent.LinkedBlockingQueue<Runnable>();var publications=new java.util.concurrent.LinkedBlockingQueue<Runnable>();
            var renderer=new VisibleRefinementCapture.Renderer(){
                public void requireCurrent(VisibleRefinementCapture.Request request){epoch.requireCurrent(raster.sourceReceipt());}
                public VisibleRefinementCapture.Viewport snapshot(){return new VisibleRefinementCapture.Viewport(0,0,1,54,27);}
                public void restore(VisibleRefinementCapture.Viewport saved){}
                public VisibleRefinementCapture.RenderedChunk render(VisibleRefinementCapture.Request request,VisibleRefinementCapture.Chunk chunk){
                    var image=new java.awt.image.BufferedImage(chunk.viewWidth()*6,chunk.viewHeight()*6,java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int gray=compactGray(chunk.bounds().minimumEast()+x/6.,chunk.bounds().maximumNorth()-y/6.);image.setRGB(x,y,0xff000000|gray<<16|gray<<8|gray);}
                    return new VisibleRefinementCapture.RenderedChunk(image,chunk.bounds(),-1,OptionalDouble.of(1),1);
                }
            };
            var port=new VisibleRefinementCapture(renderer,SwingUtilities::invokeLater,java.util.concurrent.ForkJoinPool.commonPool(),320,320).port();
            SwingUtilities.invokeAndWait(()->{
                review[0]=ModernReviewRoot.fromFullChoices(fixture.dataSet(),fixture.selection(),config,choices,null,()->{});review[0].select(selected.candidate());review[0].bindDisplayedSelection(selected,assessment);
                assertSame(selected.candidate(),review[0].selectedCandidate(),"The original selector keeps its actual candidate pointer");assertEquals(expected,review[0].selectedFinalGeometry());
                workflow[0]=new LegacyRefinementWorkflow(review[0],null,review[0]::select,d->{assertTrue(d.nativeReview().isPresent()!=d.noChange().isPresent());},s->{},workers::add,publications::add,port);
                workflow[0].enableNativeRuntimeDiagnostics(c->null,c->null);assertEquals(expected,workflow[0].selectedGeometry());workflow[0].smooth(org.openstreetmap.josm.plugins.wayheatmaptracer.ui.RefinementControls.Strength.MEDIUM);
            });
            try {
                VisibleRefinementWorkflowTest.drain(workers,publications,workflow[0]);
                SwingUtilities.invokeAndWait(()->{
                    assertEquals(RefinementModels.StageKind.SMOOTH,workflow[0].latestAttemptDiagnostics().orElseThrow().binding().ticket().kind());
                    try {
                        var path=diagnosticsDirectory.resolve("no-change-smoothing-"+mode.name()+".zip");org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry.writeLatest(path.toFile());
                        var archive=org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ArchiveReader.read(path);
                        var actual=new String(archive.artifact("latest-smoothing-attempt.json").orElseThrow().bytes(),java.nio.charset.StandardCharsets.UTF_8);
                        assertTrue(actual.contains("\"method\":\"existing-gaussian-capture-reference-v1\""));assertTrue(actual.contains("\"nativeCalls\":0"));
                    }catch(Exception unavailable){throw new AssertionError("Factual no-change-root smoothing receipt required",unavailable);}
                    if(!workflow[0].atRoot()){assertEquals(RefinementModels.StageKind.SMOOTH,workflow[0].currentStage().kind());((javax.swing.JButton)VisibleRefinementWorkflowTest.find(workflow[0].controls(),"undo")).doClick();}
                    assertTrue(workflow[0].currentNoChangeRoot().isPresent(),workflow[0].computationEvidence());assertTrue(workflow[0].currentNative().isEmpty());
                    assertEquals(expected,workflow[0].selectedGeometry());assertEquals(5,workflow[0].currentNoChangeRoot().orElseThrow().finalSelection().pointIds().size());assertEquals(0,workflow[0].actualNativeCalls());
                });
                for(var engine:List.of(TrackerMode.CORRIDOR_AWARE,TrackerMode.PROBABILISTIC)) {
                    int calls=workflow[0].actualNativeCalls();SwingUtilities.invokeAndWait(()->workflow[0].refine(engine));VisibleRefinementWorkflowTest.drain(workers,publications,workflow[0]);
                    SwingUtilities.invokeAndWait(()->{
                        assertTrue(workflow[0].actualNativeCalls()>calls,workflow[0].computationEvidence());
                        if(!workflow[0].atRoot())((javax.swing.JButton)VisibleRefinementWorkflowTest.find(workflow[0].controls(),"undo")).doClick();
                        assertTrue(workflow[0].currentNoChangeRoot().isPresent());assertEquals(expected,workflow[0].selectedGeometry());
                    });
                }
                SwingUtilities.invokeAndWait(()->{
                    assertTrue(workflow[0].canApplyNative());assertThrows(IllegalStateException.class,workflow[0]::command);
                    org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.begin();
                    try{workflow[0].acceptNoChangeRoot();assertFalse(org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.snapshot().containsKey("apply.commandExecutions"));}
                    finally{org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters.end();}
                    assertEquals(originalNodes,fixture.selection().way().getNodes());assertEquals(original,originalNodes.stream().map(org.openstreetmap.josm.data.osm.Node::getCoor).toList());
                    assertEquals(flags,fixture.dataSet().allPrimitives().stream().collect(java.util.stream.Collectors.toMap(p->p.getUniqueId(),p->List.of(p.isModified(),p.isDeleted()))));assertEquals(undo,List.copyOf(host.getUndoCommands()));assertEquals(redo,List.copyOf(host.getRedoCommands()));
                });
                SwingUtilities.invokeAndWait(()->{
                    workflow[0].close();workflow[0]=new LegacyRefinementWorkflow(review[0],null,review[0]::select,d->{},s->{},workers::add,publications::add,port);
                    workflow[0].smooth(org.openstreetmap.josm.plugins.wayheatmaptracer.ui.RefinementControls.Strength.MEDIUM);
                });
                VisibleRefinementWorkflowTest.drain(workers,publications,workflow[0]);
                SwingUtilities.invokeAndWait(()->{
                    if(!workflow[0].atRoot())((javax.swing.JButton)VisibleRefinementWorkflowTest.find(workflow[0].controls(),"undo")).doClick();
                    var retained=workflow[0].currentStage();epoch.sourceChanged();assertFalse(workflow[0].canApplyNative());
                    assertThrows(IllegalStateException.class,workflow[0]::acceptNoChangeRoot);assertSame(retained,workflow[0].currentStage());
                    assertEquals(originalNodes,fixture.selection().way().getNodes());assertEquals(original,originalNodes.stream().map(org.openstreetmap.josm.data.osm.Node::getCoor).toList());
                    assertEquals(flags,fixture.dataSet().allPrimitives().stream().collect(java.util.stream.Collectors.toMap(p->p.getUniqueId(),p->List.of(p.isModified(),p.isDeleted()))));
                    assertEquals(undo,List.copyOf(host.getUndoCommands()));assertEquals(redo,List.copyOf(host.getRedoCommands()));
                });
            }finally{SwingUtilities.invokeAndWait(()->{workflow[0].close();review[0].close();});}
        }
    }
    @Test void nativeOriginalViewRetainsCachedProjectionOnlyUnderExactOriginalGeographicAuthority() throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        var fixture=V022LiveBPreviewServiceTest.fiveNodeFixture();var config=V022LiveBPreviewServiceTest.config(TrackerMode.PROBABILISTIC);
        SwingUtilities.invokeAndWait(()->{for(int i=1;i<4;i++){var node=fixture.selection().segmentNodes().get(i);var point=node.getEastNorth();node.setEastNorth(new org.openstreetmap.josm.data.coor.EastNorth(point.east(),point.north()+1));}});
        var observed=fixture.selection().segmentNodes().stream().map(org.openstreetmap.josm.data.osm.Node::getEastNorth).toList();
        var image=V022LiveBPreviewServiceTest.raster();var epoch=new VisibleSourceEpoch();
        var raster=new LiveBPreviewService.VisibleRaster(image.width(),image.height(),image.argb(),image.minimumEast(),image.minimumNorth(),image.maximumEast(),image.maximumNorth(),image.projectionUnitsPerViewPixel(),image.groundMetersPerViewPixel(),image.nativePitchMeters(),image.sourceIdentity(),image.projectionCode(),epoch.captureStable());
        var service=new LiveBPreviewService();var captured=new LiveBPreviewService.Captured[1];
        SwingUtilities.invokeAndWait(()->captured[0]=service.capture(fixture.dataSet(),fixture.selection(),raster,config,true));
        var computed=service.compute(captured[0],CancellationProbe.NONE);var choices=service.adaptChoices(computed,g->ProjectionRegistry.getProjection().latlon2eastNorth(new org.openstreetmap.josm.data.coor.LatLon(g.latitudeDegrees(),g.longitudeDegrees())));
        var reconstructed=captured[0].sourceGeographic().stream().map(g->ProjectionRegistry.getProjection().latlon2eastNorth(new org.openstreetmap.josm.data.coor.LatLon(g.latitudeDegrees(),g.longitudeDegrees()))).toList();
        assertNotEquals(observed,reconstructed,"This regression requires the factual setEastNorth cache roundtrip difference");
        SwingUtilities.invokeAndWait(()->{
            try(var root=ModernReviewRoot.fromFullChoices(fixture.dataSet(),fixture.selection(),config,choices,null,()->{})){assertEquals(observed,root.originalResult().sourcePolyline());root.requireAuthorityCurrent();}
            var node=fixture.selection().segmentNodes().get(2);var old=node.getCoor();node.setCoor(new org.openstreetmap.josm.data.coor.LatLon(Math.nextUp(old.lat()),old.lon()));
            assertThrows(IllegalStateException.class,()->ModernReviewRoot.fromFullChoices(fixture.dataSet(),fixture.selection(),config,choices,null,()->{}),"Even a one-ulp geographic change cannot borrow the original projected view stamp");
        });
    }
    @Test void actualUnchangedIntervalRootKeepsItsOwnProofFixedIslandAndNoWriteAcceptance() throws Exception {exerciseActualUnchangedIntervalRoot(true);}
    @Test void actualAllFrozenIntervalCaptureCannotBorrowNoChangeAuthority() throws Exception {exerciseActualUnchangedIntervalRoot(false);}
    private void exerciseActualUnchangedIntervalRoot(boolean usable) throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        var projection=ProjectionRegistry.getProjection();var data=new org.openstreetmap.josm.data.osm.DataSet();var nodes=new ArrayList<org.openstreetmap.josm.data.osm.Node>();
        int count=usable?21:11,middle=count/2,last=count-2;double spacing=usable?10:4;
        for(int i=0;i<count;i++){var node=new org.openstreetmap.josm.data.osm.Node(projection.eastNorth2latlon(new org.openstreetmap.josm.data.coor.EastNorth((i-middle)*spacing,0)));data.addPrimitive(node);nodes.add(node);}
        var way=new org.openstreetmap.josm.data.osm.Way();way.setNodes(nodes);way.put("highway","path");data.addPrimitive(way);nodes.get(middle).put("note","protected original island");
        // Partition capture is owned by actual shared topology, not a tagged node alone.
        var remote=new org.openstreetmap.josm.data.osm.Node(projection.eastNorth2latlon(new org.openstreetmap.josm.data.coor.EastNorth(0,usable?100:30)));data.addPrimitive(remote);
        var branch=new org.openstreetmap.josm.data.osm.Way();branch.setNodes(List.of(nodes.get(middle),remote));branch.put("highway","path");data.addPrimitive(branch);
        var selection=new SelectionContext(way,1,last,nodes.subList(1,last+1),Set.of(nodes.get(1),nodes.get(last)));var base=V022LiveBPreviewServiceTest.config(TrackerMode.PROBABILISTIC);
        var config=new AlignmentConfig(base.heatmap().withAlignmentMode(AlignmentMode.MOVE_EXISTING_NODES),base.cleanup());var epoch=new VisibleSourceEpoch();int width=usable?720:600,height=usable?180:160;double pitch=usable?1./3:1./6,ground=pitch*6;var pixels=new int[width*height];
        assertTrue(width*pitch/2>90+config.heatmap().crossSectionHalfWidthPx()*ground||!usable,"The factual visible view-pixel decision radius must fit beside both original endpoints");
        assertTrue(height*pitch/2>config.heatmap().crossSectionHalfWidthPx()*ground,"The factual complete B decision window must fit vertically before capture");
        for(int y=0;y<height;y++){double north=(height/2.-y)*pitch;int gray=(int)Math.round(255*(.02+.8*Math.exp(-.5*north*north/1.44)));java.util.Arrays.fill(pixels,y*width,(y+1)*width,0xff000000|gray<<16|gray<<8|gray);}
        var raster=new LiveBPreviewService.VisibleRaster(width,height,pixels,-width*pitch/2,-height*pitch/2,width*pitch/2,height*pitch/2,ground,ground,OptionalDouble.of(1),"actual-unchanged-interval","EPSG:3857",epoch.captureStable());
        var service=new LiveBPreviewService();var captured=new LiveBPreviewService.Captured[1];SwingUtilities.invokeAndWait(()->captured[0]=service.capture(data,selection,raster,config,true));
        var computed=service.compute(captured[0],CancellationProbe.NONE);assertTrue(computed.partitioned());var assessment=new FixedIntervalEditPlanComposer().compose(computed.intervalBatch(),Map.of());
        if(!usable){assertTrue(computed.intervalBatch().runs().isEmpty());assertTrue(assessment.plan().isEmpty());assertTrue(assessment.noChangeProof().isEmpty());assertFalse(assessment.applyAvailable());return;}
        assertFalse(computed.intervalBatch().runs().isEmpty());
        assertTrue(assessment.plan().isEmpty(),assessment.toString());var proof=assessment.noChangeProof().orElseThrow(()->new AssertionError("Actual usable unchanged interval proof required: "+assessment.intervals()));
        proof.requireMatches(computed.intervalBatch(),Map.of(),captured[0].network());assertEquals(last,proof.finalSelection().pointIds().size());assertFalse(computed.intervalBatch().partition().fixedIslands().isEmpty());
        var initialCoordinates=nodes.stream().map(org.openstreetmap.josm.data.osm.Node::getCoor).toList();var initialFlags=data.allPrimitives().stream().collect(java.util.stream.Collectors.toMap(p->p.getUniqueId(),p->List.of(p.isModified(),p.isDeleted())));
        var host=org.openstreetmap.josm.data.UndoRedoHandler.getInstance();var undo=List.copyOf(host.getUndoCommands());var redo=List.copyOf(host.getRedoCommands());
        var workers=new java.util.concurrent.LinkedBlockingQueue<Runnable>();var publications=new java.util.concurrent.LinkedBlockingQueue<Runnable>();var review=new ModernReviewRoot[1];var workflow=new LegacyRefinementWorkflow[1];
        var renderer=new VisibleRefinementCapture.Renderer(){
            public void requireCurrent(VisibleRefinementCapture.Request request){epoch.requireCurrent(raster.sourceReceipt());}
            public VisibleRefinementCapture.Viewport snapshot(){return new VisibleRefinementCapture.Viewport(0,0,ground,(int)(width/6.),(int)(height/6.));}
            public void restore(VisibleRefinementCapture.Viewport saved){}
            public VisibleRefinementCapture.RenderedChunk render(VisibleRefinementCapture.Request request,VisibleRefinementCapture.Chunk chunk){var image=new java.awt.image.BufferedImage(chunk.viewWidth()*6,chunk.viewHeight()*6,java.awt.image.BufferedImage.TYPE_INT_ARGB);for(int y=0;y<image.getHeight();y++){double north=chunk.bounds().maximumNorth()-y*pitch;int gray=(int)Math.round(255*(.02+.8*Math.exp(-.5*north*north/1.44)));for(int x=0;x<image.getWidth();x++)image.setRGB(x,y,0xff000000|gray<<16|gray<<8|gray);}return new VisibleRefinementCapture.RenderedChunk(image,chunk.bounds(),-1,OptionalDouble.of(1),ground);}
        };
        var port=new VisibleRefinementCapture(renderer,SwingUtilities::invokeLater,java.util.concurrent.ForkJoinPool.commonPool(),320,320).port();
        SwingUtilities.invokeAndWait(()->review[0]=ModernReviewRoot.fromIntervalSelection(data,selection,config,new ModernReviewRoot.IntervalSelection(computed,Map.of(),assessment),null,()->{}));
        // Qualify the actual proof-backed bootstrap separately so a preparation failure cannot
        // be mistaken for a missing Gaussian receipt or an invalid original interval proof.
        try(var memory=AttemptMemoryLedger.production().rootOwner()) {
            var work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,memory);
            try(var prepared=new ModernRefinementBootstrap().complete(review[0],work,port)) {
                assertTrue(prepared.root().stage().candidates().stream().allMatch(c->c.payload().rootNoChangeReview().isPresent()));
                var stage=prepared.root().stage();var candidate=stage.candidates().stream().filter(c->c.id().equals(stage.selected())).findFirst().orElseThrow();
                var source=candidate.payload().rootNoChangeReview().orElseThrow().source();
                var input=new RefinementModels.SmoothingInput(stage.id(),candidate.id(),stage.id(),candidate.id(),"Medium");
                var ticket=new RefinementModels.OperationTicket(new RefinementModels.SessionId(java.util.UUID.randomUUID()),1,stage.id(),candidate.id(),TrackerMode.PROBABILISTIC,AlignmentMode.MOVE_EXISTING_NODES,RefinementModels.StageKind.SMOOTH,Optional.of(input));
                var bound=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.SmoothingParentBinder().bind(prepared.root().authority(),ticket,candidate,source,work);
                assertSame(source.evidence(),bound.evidence());assertEquals(proof.finalSelection().pointIds().size(),bound.reference().points().size());
            }
        }
        SwingUtilities.invokeAndWait(()->{workflow[0]=new LegacyRefinementWorkflow(review[0],null,review[0]::select,d->{},s->{},workers::add,publications::add,port);workflow[0].enableNativeRuntimeDiagnostics(c->null,c->null);workflow[0].smooth(org.openstreetmap.josm.plugins.wayheatmaptracer.ui.RefinementControls.Strength.MEDIUM);});
        try {
            VisibleRefinementWorkflowTest.drain(workers,publications,workflow[0]);
            SwingUtilities.invokeAndWait(()->{
                assertTrue(workflow[0].latestAttemptDiagnostics().isPresent(),"Actual interval smoothing attempt unavailable: "+workflow[0].computationEvidence());
                assertTrue(workflow[0].currentNoChangeRoot().isPresent()||workflow[0].currentNative().isPresent(),"Actual interval bootstrap did not publish its proof-backed root: "+workflow[0].computationEvidence());
            });
            SwingUtilities.invokeAndWait(()->{if(!workflow[0].atRoot())((javax.swing.JButton)VisibleRefinementWorkflowTest.find(workflow[0].controls(),"undo")).doClick();assertTrue(workflow[0].currentNoChangeRoot().orElseThrow() instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementPayload.RootIntervalNoChangeReview);assertTrue(workflow[0].currentNative().isEmpty());assertEquals(RefinementModels.StageKind.SMOOTH,workflow[0].latestAttemptDiagnostics().orElseThrow().binding().ticket().kind());try{var path=diagnosticsDirectory.resolve("unchanged-interval-smoothing.zip");org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry.writeLatest(path.toFile());var archive=org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ArchiveReader.read(path);var actual=new String(archive.artifact("latest-smoothing-attempt.json").orElseThrow().bytes(),java.nio.charset.StandardCharsets.UTF_8);assertTrue(actual.contains("existing-gaussian-capture-reference-v1"));assertTrue(actual.contains("\"nativeCalls\":0"));}catch(Exception unavailable){throw new AssertionError("Actual interval Gaussian receipt required",unavailable);}});
            for(var engine:List.of(TrackerMode.CORRIDOR_AWARE,TrackerMode.PROBABILISTIC)){
                int before=workflow[0].actualNativeCalls();SwingUtilities.invokeAndWait(()->workflow[0].refine(engine));VisibleRefinementWorkflowTest.drain(workers,publications,workflow[0]);
                SwingUtilities.invokeAndWait(()->{assertTrue(workflow[0].actualNativeCalls()>before,workflow[0].computationEvidence());if(!workflow[0].atRoot())((javax.swing.JButton)VisibleRefinementWorkflowTest.find(workflow[0].controls(),"undo")).doClick();assertTrue(workflow[0].currentNoChangeRoot().isPresent());assertEquals(nodes.get(middle).getCoor(),initialCoordinates.get(middle));});
            }
            SwingUtilities.invokeAndWait(()->{assertTrue(workflow[0].canApplyNative());assertThrows(IllegalStateException.class,workflow[0]::command);workflow[0].acceptNoChangeRoot();assertEquals(nodes,way.getNodes());assertEquals(initialCoordinates,nodes.stream().map(org.openstreetmap.josm.data.osm.Node::getCoor).toList());assertEquals(initialFlags,data.allPrimitives().stream().collect(java.util.stream.Collectors.toMap(p->p.getUniqueId(),p->List.of(p.isModified(),p.isDeleted()))));assertEquals(undo,List.copyOf(host.getUndoCommands()));assertEquals(redo,List.copyOf(host.getRedoCommands()));});
            SwingUtilities.invokeAndWait(()->{
                workflow[0].close();workflow[0]=new LegacyRefinementWorkflow(review[0],null,review[0]::select,d->{},s->{},workers::add,publications::add,port);
                workflow[0].smooth(org.openstreetmap.josm.plugins.wayheatmaptracer.ui.RefinementControls.Strength.MEDIUM);
            });
            VisibleRefinementWorkflowTest.drain(workers,publications,workflow[0]);
            SwingUtilities.invokeAndWait(()->{
                if(!workflow[0].atRoot())((javax.swing.JButton)VisibleRefinementWorkflowTest.find(workflow[0].controls(),"undo")).doClick();
                assertTrue(workflow[0].currentNoChangeRoot().isPresent(),workflow[0].computationEvidence());
                var retained=workflow[0].currentStage();int nativeCalls=workflow[0].actualNativeCalls();epoch.sourceChanged();
                assertFalse(workflow[0].canApplyNative());assertThrows(IllegalStateException.class,workflow[0]::acceptNoChangeRoot);
                assertSame(retained,workflow[0].currentStage());assertEquals(nativeCalls,workflow[0].actualNativeCalls());
                assertEquals(nodes,way.getNodes());assertEquals(initialCoordinates,nodes.stream().map(org.openstreetmap.josm.data.osm.Node::getCoor).toList());
                assertEquals(initialFlags,data.allPrimitives().stream().collect(java.util.stream.Collectors.toMap(p->p.getUniqueId(),p->List.of(p.isModified(),p.isDeleted()))));
                assertEquals(undo,List.copyOf(host.getUndoCommands()));assertEquals(redo,List.copyOf(host.getRedoCommands()));
            });
        }finally{SwingUtilities.invokeAndWait(()->{workflow[0].close();review[0].close();});}
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
            assertProducedEvidenceProof(computed);
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
                    assertOriginalNativeAcquisitionAuthority(prepared,selected,review[0],work,computed);
                    assertEquals(choices.get(choices.size()-1).localRouteIndex(),selected.payload().rootReviewState().orElseThrow().fullRouteIndex().orElseThrow());
                    assertEquals(mode,prepared.root().authority().request().geometryMode());
                    assertSame(capture[0].network(),selected.payload().nativeReview().orElseThrow().plan().before());
                    var workers=new java.util.concurrent.LinkedBlockingQueue<Runnable>();
                    var publications=new java.util.concurrent.LinkedBlockingQueue<Runnable>();
                    var workflow=new LegacyRefinementWorkflow[1];
                    var captures=new java.util.concurrent.atomic.AtomicInteger();
                    SwingUtilities.invokeAndWait(()->{
                        workflow[0]=new LegacyRefinementWorkflow(review[0],null,review[0]::select,display->{},status->{},workers::add,publications::add,(request,context)->{captures.incrementAndGet();throw new AssertionError("Covered original native raster must not paint");});
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
                            assertEquals(0,captures.get());
                        });
                    } finally {SwingUtilities.invokeAndWait(workflow[0]::close);}
                    var retainedSource=selected.payload().nativeReview().orElseThrow().retainedSources().values().iterator().next();
                    var hashProof=registeredOriginalProof(prepared.root().authority(),selected,retainedSource,work).orElseThrow();
                    epoch.sourceChanged();assertThrows(IllegalStateException.class,prepared.source()::requireCurrent);
                    assertThrows(IllegalStateException.class,()->hashProof.requireMatches(prepared.root().authority(),retainedSource.evidence(),retainedSource,work));
                    assertThrows(IllegalStateException.class,()->prepared.root().authority().requireSelectedAcquisitionEvidence(selected,retainedSource));
                    assertThrows(IllegalStateException.class,()->registeredOriginalHash(prepared.root().authority(),selected,retainedSource,work));
                    prepared.close();assertThrows(IllegalArgumentException.class,()->prepared.root().authority().requireSelectedAcquisitionEvidence(selected,retainedSource));
                    assertThrows(IllegalArgumentException.class,()->registeredOriginalHash(prepared.root().authority(),selected,retainedSource,work));
                    assertThrows(IllegalArgumentException.class,()->hashProof.requireMatches(prepared.root().authority(),retainedSource.evidence(),retainedSource,work));
                }
            } finally {SwingUtilities.invokeAndWait(review[0]::close);}
        }
    }
    private static void assertProducedEvidenceProof(LiveBPreviewService.Computed run) {
        var proof=run.producedEvidenceProof().orElseThrow();var e=run.evidence();var r=run.request();var o=run.options();
        try(var memory=AttemptMemoryLedger.production().rootOwner()) {
            var work=new GeometryWorkContext(256,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,memory);
            assertEquals(r.evidenceContentHash(),proof.requireMatches(run,work));assertEquals(8,work.visits(),"Production proof reuse has constant charged metadata work");
            assertEquals(r.evidenceContentHash(),proof.requireMatches(run.withSettingsResolution("{}").withSourceAttempts(List.of()).withCorridorReceipt(run.corridorReceipt()),work));
            var cloned=new EvidenceSnapshot(e.snapshotId(),e.coordinateFrame(),e.transform(),e.resolution(),e.decisionRegion(),e.evidenceRegion(),e.fields(),e.resampling(),e.sourceIdentity());
            var request=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),r.parameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput(),r.disconnectionSource());
            var options=new ModernTracePipeline.Options(o.fieldName(),o.cleanup(),o.sourceTier(),o.mappingPreference());
            for(var foreign:List.<java.util.function.Supplier<LiveBPreviewService.Computed>>of(
                ()->proofCopy(run,run.captured().withOriginalReceipt(run.captured().originalReceipt().orElseThrow()),e,r,run.pipeline(),run.intervalBatch(),o),
                ()->proofCopy(run,run.captured(),cloned,r,run.pipeline(),run.intervalBatch(),o),
                ()->proofCopy(run,run.captured(),e,request,run.pipeline(),run.intervalBatch(),o),
                ()->proofCopy(run,run.captured(),e,r,run.pipeline(),run.intervalBatch(),options)))
                assertThrows(IllegalArgumentException.class,()->proof.requireMatches(foreign.get(),work));
            if(run.pipeline()!=null)assertThrows(IllegalArgumentException.class,()->proof.requireMatches(proofCopy(run,run.captured(),e,r,new ModernTracePipeline.Result(run.pipeline().inference(),run.pipeline().routes()),null,o),work));
            else {
                var b=run.intervalBatch();var clone=new IntervalTraceBatch(b.fullRequest(),b.evidence(),b.network(),b.partition(),b.runs(),b.options(),run.captured().specification());
                assertThrows(IllegalArgumentException.class,()->proof.requireMatches(proofCopy(run,run.captured(),e,r,null,clone,o),work));
            }
            var cancelled=new GeometryWorkContext(256,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,()->{throw new java.util.concurrent.CancellationException();},memory);
            assertThrows(java.util.concurrent.CancellationException.class,()->proof.requireMatches(run,cancelled));assertEquals(0,cancelled.visits());
            var refused=new GeometryWorkContext(0,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,memory);
            assertThrows(GeometryWorkContext.LimitReached.class,()->proof.requireMatches(run,refused));assertEquals(0,refused.visits());
            assertTrue(new LiveBPreviewService.Computed(run.captured(),e,r,run.pipeline(),o,run.counters(),run.intervalBatch(),run.settingsResolutionJson(),List.of(),run.legacyB(),run.corridorReceipt()).producedEvidenceProof().isEmpty(),"Compatibility data cannot mint a traversal proof");
        }
    }
    private static LiveBPreviewService.Computed proofCopy(LiveBPreviewService.Computed run,LiveBPreviewService.Captured capture,EvidenceSnapshot evidence,TraceRequest request,ModernTracePipeline.Result pipeline,IntervalTraceBatch batch,ModernTracePipeline.Options options) {
        return new LiveBPreviewService.Computed(capture,evidence,request,pipeline,options,run.counters(),batch,run.settingsResolutionJson(),List.of(),run.legacyB(),run.corridorReceipt(),run.producedEvidenceProof());
    }
    private static void assertOriginalNativeAcquisitionAuthority(PreparedRefinementRoot prepared,RefinementModels.Candidate selected,
            ModernReviewRoot review,GeometryWorkContext work,LiveBPreviewService.Computed computed) throws Exception {
        var root=prepared.root().authority();var nativeReview=selected.payload().nativeReview().orElseThrow();
        var source=nativeReview.retainedSources().values().iterator().next();var evidence=source.evidence();
        assertDoesNotThrow(()->root.requireSelectedAcquisitionEvidence(selected,source));
        var lookupWork=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,prepared.memory());
        var actualOwner=computed.productionRuns().stream().filter(run->run.evidence()==evidence).findFirst().orElseThrow();
        assertEquals(Optional.of(actualOwner.request().evidenceContentHash()),registeredOriginalHash(root,selected,source,lookupWork));
        assertTrue(lookupWork.counters().get(GeometryWorkContext.Kind.BIND)>0);assertEquals(0L,lookupWork.counters().get(GeometryWorkContext.Kind.HASH));
        var proof=registeredOriginalProof(root,selected,source,lookupWork).orElseThrow();
        assertEquals(actualOwner.request().evidenceContentHash(),proof.requireMatches(root,evidence,source,lookupWork));
        assertEquals(0L,lookupWork.counters().get(GeometryWorkContext.Kind.HASH),"Both consumers reuse only the producer's actual immutable hash");
        var sameValues=new EvidenceSnapshot(evidence.snapshotId(),evidence.coordinateFrame(),evidence.transform(),evidence.resolution(),evidence.decisionRegion(),evidence.evidenceRegion(),evidence.fields(),evidence.resampling(),evidence.sourceIdentity());
        assertThrows(IllegalArgumentException.class,()->proof.requireMatches(root,sameValues,source,lookupWork));
        var copyRoot=RefinementRootAuthority.class.getDeclaredMethod("withRetainedFacts",Map.class);copyRoot.setAccessible(true);
        var foreignRoot=(RefinementRootAuthority)copyRoot.invoke(root,Map.of());
        assertThrows(IllegalArgumentException.class,()->proof.requireMatches(foreignRoot,evidence,source,lookupWork));
        var exhausted=new GeometryWorkContext(0,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,prepared.memory());
        assertThrows(GeometryWorkContext.LimitReached.class,()->proof.requireMatches(root,evidence,source,exhausted));
        var cancelled=new GeometryWorkContext(256,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,()->{throw new java.util.concurrent.CancellationException();},prepared.memory());
        assertThrows(java.util.concurrent.CancellationException.class,()->proof.requireMatches(root,evidence,source,cancelled));
        var foreign=new EvidenceSnapshot(evidence.snapshotId(),evidence.coordinateFrame(),evidence.transform(),evidence.resolution(),
            evidence.decisionRegion(),evidence.evidenceRegion(),evidence.fields(),evidence.resampling(),evidence.sourceIdentity()+"/foreign-owner");
        assertThrows(IllegalArgumentException.class,()->root.requireAcquisitionEvidence(foreign),"Same dimensions/axes/geometry never authorize a foreign source");
        for(int forgedKind=0;forgedKind<3;forgedKind++) {
        var receipt=forgedKind==1?Optional.of(new VisibleSourceEpoch().captureStable()):source.visibleReceipt();
        var suppliedEvidence=forgedKind==2?new EvidenceSnapshot(evidence.snapshotId(),evidence.coordinateFrame(),evidence.transform(),evidence.resolution(),
            evidence.decisionRegion(),evidence.evidenceRegion(),evidence.fields(),evidence.resampling(),evidence.sourceIdentity()):evidence;
        if(forgedKind==2){work.reserveOn(prepared.memory(),"test-foreign-original-snapshot",source.backingBytes()).adopt(suppliedEvidence);assertEquals(evidence.canonicalHash(),suppliedEvidence.canonicalHash());}
        var fake=RetainedSmoothingSource.fromOriginalConversion(source.source(),source.intensityMode(),source.fieldName(),suppliedEvidence,
            source.referenceTransform(),source.referenceWidth(),source.referenceHeight(),source.backingBytes(),()->{},source.imageOwnershipIdentity(),
            source.inputByteHash(),source.generation(),receipt,source.legacyDisposition(),source.inputHashProtocol(),work,prepared.memory());
        var forgedReview=new RefinementPayload.NativeReview(nativeReview.plan(),nativeReview.quality(),nativeReview.operation(),nativeReview.sources(),
            nativeReview.retainedParentSections(),Map.of(source.source(),fake),nativeReview.sourceSpans(),nativeReview.finalPointIds(),nativeReview.smoothingReceipt());
        var payload=RefinementPayload.captureNative(review.originalResult(),selected.payload(),forgedReview,RefinementLimits.defaults().maxPayloadBytes())
            .withRootReviewState(selected.payload().rootReviewState().orElseThrow());
        var forged=new RefinementModels.Candidate(selected.id(),payload,selected.correspondence(),selected.immutableIdentity(),selected.nativeCandidateId(),selected.sourcePalette());
        assertThrows(IllegalArgumentException.class,()->root.requireSelectedAcquisitionEvidence(forged,fake),"A constructed descriptor and matching candidate alias cannot create original acquisition authority");
        assertThrows(IllegalArgumentException.class,()->registeredOriginalHash(root,forged,fake,lookupWork));
        assertThrows(IllegalArgumentException.class,()->proof.requireMatches(root,suppliedEvidence,fake,lookupWork));
        }
        var r=actualOwner.request();
        var foreignRequest=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),
            r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId()+"-foreign",r.networkContentHash(),r.settingsHash(),r.parameterHash(),
            r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput(),r.disconnectionSource());
        var foreignOwner=new LiveBPreviewService.Computed(computed.captured(),evidence,foreignRequest,actualOwner.pipeline(),actualOwner.options(),
            actualOwner.counters(),actualOwner.intervalBatch(),actualOwner.settingsResolutionJson(),List.of());
        var register=RefinementRootAuthority.class.getDeclaredMethod("withNativeAcquisitions",LiveBPreviewService.Captured.class,Collection.class,Map.class,GeometryWorkContext.class,AttemptMemoryLedger.Owner.class,Map.class);
        register.setAccessible(true);
        var rejected=assertThrows(java.lang.reflect.InvocationTargetException.class,()->register.invoke(root,computed.captured(),computed.productionRuns(),Map.of(foreignOwner,source),work,prepared.memory(),new IdentityHashMap<>()));
        assertInstanceOf(IllegalArgumentException.class,rejected.getCause(),"A foreign request/owner cannot register an original acquisition");
        var otherReceipt=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture.CapturedSnapshot[1];
        SwingUtilities.invokeAndWait(()->otherReceipt[0]=org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture.captureBound(review.dataSet(),computed.captured().specification()));
        assertThrows(IllegalArgumentException.class,()->computed.captured().withOriginalReceipt(otherReceipt[0]),"Another original receipt cannot replace the acquisition's captured snapshot identity");
        if(!evidence.sourceIdentity().equals(root.sourceIdentity())) {
            var clone=new EvidenceSnapshot(evidence.snapshotId(),evidence.coordinateFrame(),evidence.transform(),evidence.resolution(),evidence.decisionRegion(),
                evidence.evidenceRegion(),evidence.fields(),evidence.resampling(),evidence.sourceIdentity());
            assertEquals(evidence.canonicalHash(),clone.canonicalHash());
            assertThrows(IllegalArgumentException.class,()->root.requireAcquisitionEvidence(clone),"Even exact alternative hashes require the registered evidence object");
        }
    }
    @SuppressWarnings("unchecked")
    private static Optional<RefinementRootAuthority.OriginalEvidenceHashProof> registeredOriginalProof(RefinementRootAuthority root,RefinementModels.Candidate candidate,RetainedSmoothingSource source,GeometryWorkContext work) throws Exception {
        var method=RefinementRootAuthority.class.getDeclaredMethod("registeredOriginalEvidenceHashProof",RefinementModels.Candidate.class,RetainedSmoothingSource.class,GeometryWorkContext.class);method.setAccessible(true);
        try{return (Optional<RefinementRootAuthority.OriginalEvidenceHashProof>)method.invoke(root,candidate,source,work);}
        catch(java.lang.reflect.InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException actual)throw actual;throw failure;}
    }
    @SuppressWarnings("unchecked")
    private static Optional<String> registeredOriginalHash(RefinementRootAuthority root,RefinementModels.Candidate candidate,RetainedSmoothingSource source,GeometryWorkContext work) throws Exception {
        var method=RefinementRootAuthority.class.getDeclaredMethod("registeredOriginalEvidenceHash",RefinementModels.Candidate.class,RetainedSmoothingSource.class,GeometryWorkContext.class);method.setAccessible(true);
        try{return (Optional<String>)method.invoke(root,candidate,source,work);}
        catch(java.lang.reflect.InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException actual)throw actual;throw failure;}
    }
}
