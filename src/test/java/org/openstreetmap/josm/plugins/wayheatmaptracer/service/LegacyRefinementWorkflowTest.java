package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.spi.preferences.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;

/** Production controls, original Legacy engine, Task4 pipeline and stored command; no window claim. */
class LegacyRefinementWorkflowTest {
    @TempDir Path temp;
    @BeforeAll static void setup(){Config.setPreferencesInstance(new MemoryPreferences());ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));}
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> modes(){return java.util.stream.Stream.of(
        org.junit.jupiter.params.provider.Arguments.of(AlignmentMode.PRECISE_SHAPE,TrackerMode.CORRIDOR_AWARE),
        org.junit.jupiter.params.provider.Arguments.of(AlignmentMode.PRECISE_SHAPE,TrackerMode.PROBABILISTIC),
        org.junit.jupiter.params.provider.Arguments.of(AlignmentMode.MOVE_EXISTING_NODES,TrackerMode.CORRIDOR_AWARE),
        org.junit.jupiter.params.provider.Arguments.of(AlignmentMode.MOVE_EXISTING_NODES,TrackerMode.PROBABILISTIC));}
    @ParameterizedTest @MethodSource("modes")
    void actualManagedLegacyRefineControlsHistoryAndFinalCommand(AlignmentMode mode,TrackerMode engine) throws Exception {
        executeWorkflow(mode,engine,IntensitySamplingMode.DIRECT_VALUE);
    }
    @ParameterizedTest @MethodSource("modes")
    void defaultColorMappingRefineControlsHistoryAndFinalCommand(AlignmentMode mode,TrackerMode engine) throws Exception {
        executeWorkflow(mode,engine,IntensitySamplingMode.COLOR_MAPPING);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=TrackerMode.class,names={"PROBABILISTIC"})
    void actualPublishedAlternativeMappingRetainsItsOwnSourceAfterSettlement(TrackerMode engine) throws Exception {
        executeWorkflow(AlignmentMode.PRECISE_SHAPE,engine,IntensitySamplingMode.COLOR_MAPPING,true,true);
    }
    @Test void actualNativeAggregatePublicationRetainsCompleteOriginalInputHashAfterSettlement() throws Exception {
        executeWorkflow(AlignmentMode.PRECISE_SHAPE,TrackerMode.PROBABILISTIC,IntensitySamplingMode.COLOR_MAPPING,false,false,true);
    }
    @Test void scopedCompactARealStripePublishesItsActualHotCorridorSource() throws Exception {
        final double step=1.56;final int zoom=15;int tileX=(int)Math.floor((14.+180)/360*(1<<zoom));double latRad=Math.toRadians(50);
        int tileY=(int)Math.floor((1-Math.log(Math.tan(latRad)+1/Math.cos(latRad))/Math.PI)/2*(1<<zoom));
        double centerLon=(tileX+.5)/(1<<zoom)*360-180,centerLat=Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*(tileY+.5)/(1<<zoom)))));
        var stripe=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<512;y++)for(int x=0;x<512;x++)stripe.setRGB(x,y,y==255||y==256?0xffffffff:0xff000000);
        assertEquals(0.,ManagedModernPreviewSource.scalarIntensity(stripe.getRGB(256,254),"hot",IntensitySamplingMode.COLOR_MAPPING));
        assertEquals(0.,ManagedModernPreviewSource.scalarIntensity(stripe.getRGB(256,257),"hot-corridor",IntensitySamplingMode.COLOR_MAPPING));
        double whiteHot=.85*((.2126*255+.7152*255+.0722*255)/255.)+.15;
        assertEquals(whiteHot,ManagedModernPreviewSource.scalarIntensity(stripe.getRGB(256,255),"hot",IntensitySamplingMode.COLOR_MAPPING));
        assertEquals(Math.pow(Math.min(1.,whiteHot),.55),ManagedModernPreviewSource.scalarIntensity(stripe.getRGB(256,255),"hot-corridor",IntensitySamplingMode.COLOR_MAPPING));
        var png=new ByteArrayOutputStream();ImageIO.write(stripe,"png",png);var bytes=png.toByteArray();var requests=new AtomicInteger();var palettes=ConcurrentHashMap.<String>newKeySet();var decoder=new TileDecoderClassifier();
        try(var coordinator=new TileFetchCoordinator((request,credentials)->{assertEquals(tileX,request.address().x());assertEquals(tileY,request.address().y());assertEquals(zoom,request.address().zoom());requests.incrementAndGet();palettes.add(request.address().color());return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",bytes,null,Duration.ZERO,"");},new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())) {
            var work=new LinkedBlockingQueue<Runnable>();var publication=new LinkedBlockingQueue<Runnable>();var workflow=new LegacyRefinementWorkflow[1];var review=new LegacyReviewOwner[1];var data=new DataSet[1];var root=new LegacyRefinementBootstrap.Root[1];
            try {
                SwingUtilities.invokeAndWait(()->{
                    data[0]=new DataSet();var nodes=new java.util.ArrayList<Node>();
                    for(int i=0;i<5;i++){var node=new Node(new LatLon(centerLat,centerLon+(i-2)*step/(111195.*Math.cos(Math.toRadians(centerLat)))));data[0].addPrimitive(node);nodes.add(node);}
                    var way=new Way();way.put("highway","path");way.setNodes(nodes);data[0].addPrimitive(way);data[0].allPrimitives().forEach(p->p.setModified(false));
                    var selected=new SelectionContext(way,0,4,nodes,Set.of(nodes.get(0),nodes.get(4)));
                    var config=AlignmentConfig.withoutCleanup(new ManagedHeatmapConfig("key","policy","signature","session","all","hot","",".*",AlignmentMode.PRECISE_SHAPE,TrackerMode.LEGACY_V02,false,false,true,false,false,false,false,true,false,false,7,4,3,InferenceMode.RAW_HIGH_RESOLUTION,15,15,7.01,step,IntensitySamplingMode.COLOR_MAPPING,7));
                    review[0]=LegacyReviewOwner.begin(data[0],selected,config);var receipt=new TileHeatmapSampler.AcquisitionReceipt[1];
                    var original=new AlignmentService(new TileHeatmapSampler(coordinator)).alignLegacyReviewed(selected,null,null,config,new AlignmentService.LegacyCaptureObserver(){public void managed(TileHeatmapSampler.AcquisitionReceipt actual){receipt[0]=actual;}});
                    assertFalse(original.candidates().isEmpty());review[0].captured(original,null,receipt[0]);
                    review[0].select(detectorChoice(original,"hot-corridor"));
                    workflow[0]=new LegacyRefinementWorkflow(review[0],coordinator,review[0]::select,shown->{},status->{},work::add,publication::add);
                    ((JButton)find(workflow[0].controls(),"refineA")).doClick();
                });
                work.remove().run();SwingUtilities.invokeAndWait(publication.remove());
                SwingUtilities.invokeAndWait(()->{try {
                    var rootField=LegacyRefinementWorkflow.class.getDeclaredField("root");rootField.setAccessible(true);root[0]=(LegacyRefinementBootstrap.Root)rootField.get(workflow[0]);
                    var seedField=LegacyRefinementWorkflow.class.getDeclaredField("seed");seedField.setAccessible(true);var seed=(LegacyRefinementBootstrap.Seed)seedField.get(workflow[0]);
                    var stage=workflow[0].currentStage();var chosen=stage.candidates().stream().filter(c->c.id().equals(stage.selected())).findFirst().orElseThrow();var parent=new ParentReferenceBinder().bind(root[0].authority(),stage.id(),chosen);
                    var activeField=LegacyRefinementWorkflow.class.getDeclaredField("active");activeField.setAccessible(true);var ticket=(org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.OperationTicket)activeField.get(workflow[0]);
                    var plan=RefinementSourcePlanner.plan(ticket,seed.settings(),parent);assertEquals(List.of("hot/hot-corridor"),plan.requests().stream().map(s->s.palette()+"/"+s.mapping()).toList());
                    assertSame(seed.facts().network(),root[0].authority().network());assertEquals(seed.config(),root[0].authority().effectiveConfig());assertEquals(TraceBudgets.defaults(),root[0].authority().request().budgets());
                    assertTrue(root[0].authority().request().profileChainage().cumulativeGroundMeters().size()<=6);
                }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}});
                work.remove().run();var actual=dumpOwnedPipelineFacts(workflow[0]);
                var target=new RefinementSourcePlanner.SourceRequest("hot","hot-corridor",false,"");
                try {
                    assertTrue(workflow[0].ownedBytes()>0,"Worker return retains queued result/source pins");
                    assertEquals(Set.of("hot"),palettes);
                    assertTrue(actual.attempts().stream().allMatch(a->a.source().equals(target)));
                    assertTrue(actual.attempts().stream().filter(a->a.source().equals(target)).flatMap(a->a.run().stream()).flatMap(r->r.nativeRun().stream()).anyMatch(r->r.result().status()==TraceHypothesisSet.Status.COMPLETE),
                        ()->actual.outcome()+" "+actual.attempts().stream().map(a->a.source().palette()+"/"+a.source().mapping()+":"+a.kind()+":"+a.reason()+":"+a.run().flatMap(r->r.nativeRun()).map(r->r.result().status().name()).orElse("no-native-result")).toList());
                    assertTrue(actual.sourceOutcomes().stream().filter(s->s.source().equals(target)).flatMap(s->s.assessments().stream()).anyMatch(a->a.quality().disposition()==org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator.Disposition.APPLICABLE));
                    assertTrue(actual.candidates().stream().anyMatch(c->c.sources().contains(target)&&c.retainedSources().containsKey(target)&&c.sourceSpans().stream().anyMatch(s->s.source().equals(target)&&s.nativeProvenance().isPresent())));
                    for(var attempt:actual.attempts())attempt.run().ifPresent(run->assertTrue(run.request().profileChainage().cumulativeGroundMeters().size()<=6));
                }finally{if(!publication.isEmpty())SwingUtilities.invokeAndWait(publication.remove());}
                SwingUtilities.invokeAndWait(()->{
                    assertFalse(workflow[0].busy());assertEquals(org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageKind.REFINE_A,workflow[0].currentStage().kind());
                    var stage=workflow[0].currentStage();var chosen=stage.candidates().stream().filter(c->c.payload().nativeReview().orElseThrow().sources().contains(target)).findFirst().orElseThrow();
                    var combo=(JComboBox<?>)find(workflow[0].controls(),"candidate");for(int i=0;i<combo.getItemCount();i++)if(((org.openstreetmap.josm.plugins.wayheatmaptracer.ui.RefinementControls.Choice)combo.getItemAt(i)).id().equals(chosen.id().value()))combo.setSelectedIndex(i);
                    assertEquals(chosen.id(),workflow[0].currentStage().selected());var descriptor=chosen.payload().nativeReview().orElseThrow().retainedSources().get(target);descriptor.requireCurrent();assertEquals(512,descriptor.referenceWidth());assertEquals(512,descriptor.referenceHeight());
                    assertTrue(chosen.payload().detectorAttempts().stream().anyMatch(a->a.mappingName().equals("hot-corridor")&&a.candidateIds().contains(chosen.nativeCandidateId())));
                });
                int before=requests.get(),nativeCalls=workflow[0].actualNativeCalls();assertRetainedBinderConsumesPublishedSources(workflow[0],true);assertEquals(before,requests.get());assertEquals(nativeCalls,workflow[0].actualNativeCalls());
                SwingUtilities.invokeAndWait(()->{
                    var stage=workflow[0].currentStage();
                    ((JButton)find(workflow[0].controls(),"undo")).doClick();
                    var combo=(JComboBox<?>)find(workflow[0].controls(),"candidate");
                    assertEquals(review[0].choices().size(),combo.getItemCount(),"Back restores the complete detector chooser");
                    var nativeChoice=detectorChoice(review[0].originalResult(),"hot");
                    var id=review[0].choiceId(nativeChoice);
                    for(int index=0;index<combo.getItemCount();index++) {
                        var entry=(org.openstreetmap.josm.plugins.wayheatmaptracer.ui.RefinementControls.Choice)combo.getItemAt(index);
                        if(entry.id().equals(id))combo.setSelectedIndex(index);
                    }
                    assertSame(nativeChoice,review[0].selectedCandidate());
                    assertEquals(id,workflow[0].currentStage().selected().value());
                    ((JButton)find(workflow[0].controls(),"redo")).doClick();
                    assertEquals(stage.id(),workflow[0].currentStage().id());
                    assertEquals(stage.parentChoice(),workflow[0].currentStage().parentChoice());
                    assertFalse(data[0].isModified());
                });
                assertEquals(before,requests.get());assertEquals(nativeCalls,workflow[0].actualNativeCalls());
            }finally{
                SwingUtilities.invokeAndWait(()->{if(workflow[0]!=null)workflow[0].close();});
                while(!work.isEmpty())work.remove().run();while(!publication.isEmpty())SwingUtilities.invokeAndWait(publication.remove());
                SwingUtilities.invokeAndWait(()->{if(workflow[0]!=null)assertEquals(0,workflow[0].ownedBytes());if(review[0]!=null)review[0].close();});
            }
        }
    }
    private void executeWorkflow(AlignmentMode mode,TrackerMode engine,IntensitySamplingMode intensity) throws Exception {
        executeWorkflow(mode,engine,intensity,false);
    }
    private void executeWorkflow(AlignmentMode mode,TrackerMode engine,IntensitySamplingMode intensity,boolean alternatives) throws Exception {
        executeWorkflow(mode,engine,intensity,alternatives,false);
    }
    private void executeWorkflow(AlignmentMode mode,TrackerMode engine,IntensitySamplingMode intensity,boolean alternatives,boolean diagnostic) throws Exception {
        executeWorkflow(mode,engine,intensity,alternatives,diagnostic,false);
    }
    private void executeWorkflow(AlignmentMode mode,TrackerMode engine,IntensitySamplingMode intensity,boolean alternatives,boolean diagnostic,boolean aggregate) throws Exception {
        var decoder=new TileDecoderClassifier();var requests=new AtomicInteger();var palettes=ConcurrentHashMap.<String>newKeySet();
        try(var coordinator=new TileFetchCoordinator((request,credentials)->{
            requests.incrementAndGet();palettes.add(request.address().color());
            return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(request.address(),intensity),null,Duration.ZERO,"");
        },new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())) {
            var workflow=new LegacyRefinementWorkflow[1];var owner=new LegacyReviewOwner[1];var original=new AlignmentResult[1];var data=new DataSet[1];
            var controlledWork=new LinkedBlockingQueue<Runnable>();var controlledPublication=new LinkedBlockingQueue<Runnable>();
            var diagnosticResult=new RefinementPipeline.Result[1];
            var beforeState=new AtomicReference<DatasetState>();
            var historyBefore=new AtomicReference<java.util.List<org.openstreetmap.josm.command.Command>>();
            var redoBefore=new AtomicReference<java.util.List<org.openstreetmap.josm.command.Command>>();
            try {
            var completed=new AtomicReference<>(new CountDownLatch(1));var started=new AtomicBoolean();var status=new AtomicReference<>("");var selected=new AtomicReference<LegacyRefinementWorkflow.Display>();
            SwingUtilities.invokeAndWait(()->{
                data[0]=new DataSet();var nodes=new java.util.ArrayList<Node>();
                for(int i=0;i<5;i++){var n=new Node(new LatLon(50.000018,14+(i-2)*.00028));data[0].addPrimitive(n);nodes.add(n);}
                var way=new Way();way.put("highway","path");way.setNodes(nodes);data[0].addPrimitive(way);data[0].allPrimitives().forEach(p->p.setModified(false));
                beforeState.set(snapshot(data[0]));
                var undo=UndoRedoHandler.getInstance();
                historyBefore.set(java.util.List.copyOf(undo.getUndoCommands()));
                redoBefore.set(java.util.List.copyOf(undo.getRedoCommands()));
                assertTrue(redoBefore.get().isEmpty(),"Fixture cannot add host commands while unrelated redo history exists");
                var selection=new SelectionContext(way,0,4,nodes,Set.of(nodes.get(0),nodes.get(4)));
                var config=AlignmentConfig.withoutCleanup(new ManagedHeatmapConfig("key","policy","signature","session","all","hot","",".*",
                    mode,TrackerMode.LEGACY_V02,false,false,alternatives,aggregate,false,false,false,true,false,false,
                    7,4,3,InferenceMode.RAW_HIGH_RESOLUTION,15,15,7.01,1.56,intensity,7));
                owner[0]=LegacyReviewOwner.begin(data[0],selection,config);
                var receipt=new TileHeatmapSampler.AcquisitionReceipt[1];
                original[0]=new AlignmentService(new TileHeatmapSampler(coordinator)).alignLegacyReviewed(selection,null,null,config,
                    new AlignmentService.LegacyCaptureObserver(){public void managed(TileHeatmapSampler.AcquisitionReceipt actual){receipt[0]=actual;}});
                assertHistoryUnchanged(historyBefore.get(),redoBefore.get());
                assertFalse(original[0].candidates().isEmpty());
                owner[0].captured(original[0],null,receipt[0]);
                owner[0].select(aggregate?detectorChoice(original[0],"all-colors-combined"):
                        alternatives?detectorChoice(original[0],"hot-corridor"):original[0].candidates().get(0));
                java.util.function.Consumer<String> listener=message->{
                    status.set(message);if(started.get()&&workflow[0]!=null&&!workflow[0].busy())completed.get().countDown();
                };
                workflow[0]=diagnostic?new LegacyRefinementWorkflow(owner[0],coordinator,owner[0]::select,selected::set,listener,controlledWork::add,controlledPublication::add):
                    new LegacyRefinementWorkflow(owner[0],coordinator,owner[0]::select,selected::set,listener);
                started.set(true);((JButton)find(workflow[0].controls(),engine==TrackerMode.CORRIDOR_AWARE?"refineA":"refineB")).doClick();
            });
            if(diagnostic) {
                controlledWork.remove().run();SwingUtilities.invokeAndWait(controlledPublication.remove());
                controlledWork.remove().run();diagnosticResult[0]=dumpOwnedPipelineFacts(workflow[0]);
                if(engine==TrackerMode.PROBABILISTIC)try{ActualMarginalMaterializationAssertions.verify(workflow[0],diagnosticResult[0]);}finally{SwingUtilities.invokeAndWait(controlledPublication.remove());}
                else SwingUtilities.invokeAndWait(controlledPublication.remove());
                SwingUtilities.invokeAndWait(()->dumpPublishedFacts(workflow[0]));
            }
            assertTrue(completed.get().await(90,TimeUnit.SECONDS),"Actual refinement did not settle");
            assertRetainedBinderConsumesPublishedSources(workflow[0],alternatives);
            if(alternatives||aggregate) {
                if(aggregate) {
                    SwingUtilities.invokeAndWait(()->assertTrue(workflow[0].currentStage().candidates().stream().flatMap(c->c.payload().nativeReview().orElseThrow().sources().stream()).anyMatch(RefinementSourcePlanner.SourceRequest::aggregate),"Actual all-five aggregate must be in the genuine published candidate lineage"));
                    assertEquals(Set.copyOf(RefinementSourcePlanner.BASE_PALETTES),palettes);
                }
                SwingUtilities.invokeAndWait(()->{assertHistoryUnchanged(historyBefore.get(),redoBefore.get());assertFalse(data[0].isModified());assertTrue(workflow[0].nativeSelected());assertTrue(workflow[0].actualNativeCalls()>0);});
                return; // This case qualifies first publication/binding; the original eight retain full receipt/chained Apply assertions.
            }
            var firstStage=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Stage[1];
            var firstCalls=new AtomicInteger();
            SwingUtilities.invokeAndWait(()->{
                assertHistoryUnchanged(historyBefore.get(),redoBefore.get());
                assertTrue(workflow[0].nativeSelected(),status.get()+" "+workflow[0].computationEvidence());assertNotNull(selected.get());
                assertTrue(workflow[0].actualNativeCalls()>0,"Actual kernel entry is required");
                assertEquals(Set.of("hot"),palettes);
                var factual=workflow[0].currentNative().orElseThrow();
                assertFalse(factual.sources().isEmpty());
                for(var source:factual.sources()) {
                    var retained=factual.retainedSources().get(source);
                    assertNotNull(retained,"Published descendants must retain every exact scalar mapping after worker settlement");
                    assertEquals(source,retained.source());assertEquals(intensity,retained.intensityMode());retained.requireCurrent();
                    assertNotEquals(retained.referenceTransform().parameterIdentity(),retained.outputTransform().transformId());
                }
                assertFalse(factual.sourceSpans().isEmpty());
                assertEquals(selected.get().candidate().finalPreviewPoints().size(),factual.finalPointIds().orElseThrow().size());
                assertTrue(factual.sourceSpans().stream().filter(s->!s.span().parentRetained()).allMatch(s->s.nativeProvenance().isPresent()));
                var attempts=factual.operation().orElseThrow().attempts();
                assertEquals(Set.of("hot"),attempts.stream().map(RefinementOperationReceipt.Attempt::palette).collect(java.util.stream.Collectors.toSet()));
                assertTrue(attempts.stream().anyMatch(a->a.nativeCalls().orElse(0)>0&&a.evidenceHash().isPresent()&&a.parameterHash().isPresent()));
                for(var attempt:attempts)if(attempt.nativeCalls().orElse(0)>0)assertTrue(attempt.nativeStatus().isPresent(),"Preserve actual no-result as well as successful native status");
                int actualCalls=workflow[0].actualNativeCalls();
                assertFalse(data[0].isModified(),"Preview cannot mutate OSM");
                var chosen=selected.get().candidate().finalPreviewPoints();int after=requests.get();
                ((JButton)find(workflow[0].controls(),"undo")).doClick();assertFalse(workflow[0].nativeSelected());
                ((JButton)find(workflow[0].controls(),"redo")).doClick();assertTrue(workflow[0].nativeSelected());
                workflow[0].currentNative().orElseThrow().retainedSources().values().forEach(RetainedSmoothingSource::requireCurrent);
                assertEquals(chosen,selected.get().candidate().finalPreviewPoints());assertEquals(after,requests.get());assertEquals(actualCalls,workflow[0].actualNativeCalls(),"Cached navigation cannot run a kernel");
                assertHistoryUnchanged(historyBefore.get(),redoBefore.get());
                firstStage[0]=workflow[0].currentStage();firstCalls.set(actualCalls);
                completed.set(new CountDownLatch(1));
                ((JButton)find(workflow[0].controls(),engine==TrackerMode.CORRIDOR_AWARE?"refineB":"refineA")).doClick();
            });
            assertTrue(completed.get().await(90,TimeUnit.SECONDS),"Chained refinement did not settle");
            SwingUtilities.invokeAndWait(()->{
                assertHistoryUnchanged(historyBefore.get(),redoBefore.get());
                var chained=workflow[0].currentStage();
                assertEquals(java.util.Optional.of(firstStage[0].id()),chained.parent(),status.get()+" "+workflow[0].computationEvidence());
                assertEquals(java.util.Optional.of(firstStage[0].selected()),chained.parentChoice());
                assertTrue(workflow[0].actualNativeCalls()>firstCalls.get(),"The opposite engine must actually execute");
                var chosen=selected.get().candidate().finalPreviewPoints();int after=requests.get(),calls=workflow[0].actualNativeCalls();
                ((JButton)find(workflow[0].controls(),"undo")).doClick();assertEquals(firstStage[0].id(),workflow[0].currentStage().id());
                ((JButton)find(workflow[0].controls(),"redo")).doClick();assertEquals(chained.id(),workflow[0].currentStage().id());
                assertEquals(chosen,selected.get().candidate().finalPreviewPoints());assertEquals(after,requests.get());assertEquals(calls,workflow[0].actualNativeCalls());
                if(!workflow[0].canApplyNative())workflow[0].confirm();
                var command=workflow[0].command();
                var undo=UndoRedoHandler.getInstance();
                assertHistoryUnchanged(historyBefore.get(),redoBefore.get());
                assertEquals(beforeState.get(),snapshot(data[0]),"All stages are previews");
                undo.add(command);
                assertEquals(historyBefore.get().size()+1,undo.getUndoCommands().size(),"One final host command");
                assertEquals(historyBefore.get(),undo.getUndoCommands().subList(0,historyBefore.get().size()));
                assertSame(command,undo.getUndoCommands().get(undo.getUndoCommands().size()-1));
                assertTrue(owner[0].invalidated(),"Own Apply must invalidate the UI owner without poisoning command Redo");
                workflow[0].close();owner[0].close();
                var applied=original[0].selection().way().getNodes().stream().map(Node::getEastNorth).toList();
                assertEquals(chosen,applied);
                var afterState=snapshot(data[0]);
                undo.undo();assertEquals(beforeState.get(),snapshot(data[0]),"Undo restores primitives, flags, geometry and membership");
                undo.redo();assertEquals(afterState,snapshot(data[0]),"Redo uses stored final state");
                assertEquals(after,requests.get());assertEquals(calls,workflow[0].actualNativeCalls());

                // A separate host history entry behind the attempted alignment redo must survive rejection.
                var unrelated=new Node(new LatLon(50.01,14.01));
                var unrelatedHistory=new AddCommand(data[0],unrelated);
                undo.add(unrelatedHistory);undo.undo();undo.undo();
                assertEquals(java.util.List.of(command,unrelatedHistory),undo.getRedoCommands());
                var sourceNode=original[0].selection().way().getNode(1);
                sourceNode.setCoor(new LatLon(sourceNode.lat()+0.00001,sourceNode.lon()));
                var staleState=snapshot(data[0]);
                int undoCount=undo.getUndoCommands().size();
                assertThrows(IllegalStateException.class,undo::redo,"Stale descendant redo must fail closed");
                assertEquals(staleState,snapshot(data[0]),"Rejected redo cannot mutate dataset");
                assertEquals(undoCount,undo.getUndoCommands().size(),"Rejected redo adds no undo entry");
                assertEquals(java.util.List.of(unrelatedHistory),undo.getRedoCommands(),"Only attempted redo may be consumed");
                assertEquals(after,requests.get());assertEquals(calls,workflow[0].actualNativeCalls());
            });
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    if(workflow[0]!=null) workflow[0].close();
                    if(owner[0]!=null) owner[0].close();
                    if(data[0]!=null) UndoRedoHandler.getInstance().clean(data[0]);
                });
            }
        }
    }
    private static CenterlineCandidate detectorChoice(AlignmentResult result,String mapping) {
        var ids=result.detectorAttempts().stream().filter(attempt->mapping.equals(attempt.mappingName()))
                .flatMap(attempt->attempt.candidateIds().stream()).collect(java.util.stream.Collectors.toSet());
        return result.applicableCandidates().stream().filter(candidate->ids.contains(candidate.id())).findFirst().orElseThrow();
    }
    private static void assertHistoryUnchanged(java.util.List<org.openstreetmap.josm.command.Command> undoBefore,
            java.util.List<org.openstreetmap.josm.command.Command> redoBefore) {
        var undo=UndoRedoHandler.getInstance();
        assertEquals(undoBefore,undo.getUndoCommands(),"Preview must not add or alter Undo history");
        assertEquals(redoBefore,undo.getRedoCommands(),"Preview must not alter Redo history");
    }
    private static RefinementPipeline.Result dumpOwnedPipelineFacts(LegacyRefinementWorkflow workflow) throws Exception {
        var memoryField=LegacyRefinementWorkflow.class.getDeclaredField("rootMemory");memoryField.setAccessible(true);var memory=memoryField.get(workflow);
        var activeField=LegacyRefinementWorkflow.class.getDeclaredField("active");activeField.setAccessible(true);
        var ticket=(org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.OperationTicket)activeField.get(workflow);
        var accounted=(org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.Owner)memory;
        System.out.println("alias-diagnostic engine="+ticket.engine()+" sequence="+ticket.sequence()+" currentBytes="+accounted.currentBytes()+" peakBytes="+accounted.peakBytes());
        var ledgerField=memory.getClass().getDeclaredField("ledger");ledgerField.setAccessible(true);var ledger=ledgerField.get(memory);
        var chargesField=ledger.getClass().getDeclaredField("charges");chargesField.setAccessible(true);
        var results=new java.util.ArrayList<RefinementPipeline.Result>();
        for(var key:((java.util.Map<?,?>)chargesField.get(ledger)).keySet()) {
            var identityField=key.getClass().getSuperclass().getDeclaredField("identity");identityField.setAccessible(true);var identity=identityField.get(key);
            if(identity instanceof RefinementPipeline.Result result)results.add(result);
        }
        assertEquals(1,results.size(),"Diagnostic reads the actual still-owned native pipeline result");var result=results.get(0);
        System.out.println("alias-diagnostic pipeline outcome="+result.outcome()+" truncated="+result.truncated()+" work="+result.actualWork()+" retained="+result.candidates().size()+" rejected="+result.rejected().size());
        int ordinal=0;for(var source:result.sourceOutcomes()) {
            System.out.println("alias-diagnostic source ordinal="+ordinal+++" palette="+source.source().palette()+" mapping="+source.source().mapping()+" aggregate="+source.source().aggregate()+" unavailable="+source.source().unavailableReason()+" assessments="+source.assessments().size());
            for(var assessment:source.assessments())System.out.println("alias-diagnostic assessment disposition="+assessment.quality().disposition()+" findings="+assessment.quality().findings().stream().map(f->f.code().name()).toList()+" topology="+assessment.topologyFindings().stream().map(f->f.matches("[A-Za-z0-9_-]{1,100}")?f:"UNAVAILABLE").toList());
        }
        for(var attempt:result.attempts()) {
            System.out.println("alias-diagnostic attempt palette="+attempt.source().palette()+" mapping="+attempt.source().mapping()+" kind="+attempt.kind()+" range="+attempt.range()+" issued="+attempt.issuedBudgets()+" calls="+attempt.nativeCallAvailability()+" work="+attempt.run().flatMap(r->r.actualWorkAvailability())+" status="+attempt.run().flatMap(r->r.nativeRun()).map(r->r.result().status())+" resourceFamily=UNAVAILABLE");
            attempt.run().flatMap(r->r.nativeRun()).flatMap(r->r.corridorReceipt()).ifPresent(receipt->System.out.println("alias-diagnostic corridor phase="+receipt.phase()+" totals="+receipt.totals()));
        }
        ordinal=0;for(var retained:result.candidates())System.out.println("alias-diagnostic retained ordinal="+ordinal+++" points="+retained.assessed().candidate().points().size()+" aliases="+retained.sources()+" descriptors="+retained.retainedSources().keySet()+" spans="+retained.sourceSpans().stream().map(s->s.source().palette()+"/"+s.source().mapping()+":"+s.nativeProvenance().isPresent()).toList());
        return result;
    }
    private static void dumpPublishedFacts(LegacyRefinementWorkflow workflow) {
        System.out.println("alias-diagnostic published stage="+workflow.currentStage().kind()+" ownedBytes="+workflow.ownedBytes()+" diagnostics="+workflow.latestAttemptDiagnostics().isPresent());
        int ordinal=0;for(var candidate:workflow.currentStage().candidates()) {
            var review=candidate.payload().nativeReview().orElseThrow();
            System.out.println("alias-diagnostic published ordinal="+ordinal+++" primary="+candidate.sourcePalette()+" aliases="+review.sources()+" descriptors="+review.retainedSources().keySet()+" receipt="+review.operation().isPresent()+" detectors="+candidate.payload().detectorAttempts().stream().map(a->a.sourcePalette()+"/"+a.mappingName()).toList());
        }
    }
    private static void assertRetainedBinderConsumesPublishedSources(LegacyRefinementWorkflow workflow,boolean requireAlternative) throws Exception {
        var root=new AtomicReference<RefinementRootAuthority>();var stage=new AtomicReference<org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Stage>();
        var operation=new AtomicReference<org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.Owner>();
        SwingUtilities.invokeAndWait(()->{try {
            var rootField=LegacyRefinementWorkflow.class.getDeclaredField("root");rootField.setAccessible(true);
            root.set(((LegacyRefinementBootstrap.Root)rootField.get(workflow)).authority());stage.set(workflow.currentStage());
            var memoryField=LegacyRefinementWorkflow.class.getDeclaredField("rootMemory");memoryField.setAccessible(true);
            operation.set(((org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.Owner)memoryField.get(workflow)).child("retained-binding-test"));
        }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}});
        try(var owner=operation.get()) {
            int calls=workflow.actualNativeCalls();boolean sawAlternative=false;
            for(var candidate:stage.get().candidates()) {
                var review=candidate.payload().nativeReview().orElseThrow();
                for(var request:review.sources())try(var bindingOwner=owner.child("one-selected-source")) {
                var source=review.retainedSources().get(request);sawAlternative|=request.palette().equals(root.get().effectiveConfig().heatmap().color())&&!request.mapping().equals(request.palette());
                var input=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.SmoothingInput(stage.get().id(),candidate.id(),stage.get().id(),candidate.id(),"Medium");
                var ticket=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.OperationTicket(new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.SessionId(java.util.UUID.randomUUID()),1,
                    stage.get().id(),candidate.id(),TrackerMode.LEGACY_V02,root.get().request().geometryMode(),org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.StageKind.SMOOTH,java.util.Optional.of(input));
                var context=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext(8_000_000,org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,
                    org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE,bindingOwner);
                var parent=new SmoothingParentBinder().bind(root.get(),ticket,candidate,source,context);
                assertSame(source.evidence(),parent.evidence());assertNotSame(root.get().evidence(),parent.evidence());
                assertEquals(64,parent.frame().inputHash().length());assertEquals(source.inputByteHash(),parent.frame().inputHash());assertEquals(request.aggregate()?org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture.ORIGINAL_ARGB_SET_PROTOCOL:org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.RasterEvidenceCapture.ORIGINAL_ARGB_PROTOCOL,source.inputHashProtocol());
                assertEquals(review.finalPointIds().orElseThrow(),parent.pointIds());assertTrue(parent.nativeBasis().isEmpty());
                assertEquals(java.util.Optional.of(review.quality()),parent.nativeFacts().quality());
                assertEquals(java.util.Optional.of(review.plan().validation().disposition()),parent.nativeFacts().nativeDisposition());
                assertEquals(review.sourceSpans(),parent.nativeFacts().sourceSpans());
                assertEquals(source.referenceTransform(),parent.frame().originalInputTransform());assertTrue(parent.frame().originalAffine().isEmpty());
                assertEquals(source.referenceWidth(),parent.frame().inputWidth());assertEquals(source.referenceHeight(),parent.frame().inputHeight());
                assertEquals(request.mapping(),parent.frame().mappingIdentity());
                }
            }
            if(requireAlternative)assertTrue(sawAlternative,"An actual published non-base same-palette mapping must reach the binder");
            assertEquals(calls,workflow.actualNativeCalls(),"Binding retained publications must never reenter a native engine");
            System.out.println("smoothing-native-binding kind="+stage.get().kind()+" candidates="+stage.get().candidates().size()+" currentBytes="+owner.currentBytes()+" peakBytes="+owner.peakBytes());
        }
    }
    private record PrimitiveState(boolean modified, boolean deleted, java.util.Map<String,String> keys,
            LatLon coordinate, java.util.List<Node> wayNodes) { }
    private record DatasetState(java.util.Map<OsmPrimitive,PrimitiveState> primitives, boolean modified) { }
    private static DatasetState snapshot(DataSet data) {
        var states=new java.util.HashMap<OsmPrimitive,PrimitiveState>();
        for(var primitive:data.allPrimitives()) states.put(primitive,new PrimitiveState(
            primitive.isModified(),primitive.isDeleted(),java.util.Map.copyOf(primitive.getKeys()),
            primitive instanceof Node node ? node.getCoor() : null,
            primitive instanceof Way way ? java.util.List.copyOf(way.getNodes()) : null));
        return new DatasetState(states,data.isModified());
    }
    private static Component find(Container parent,String name){for(var c:parent.getComponents()){if(name.equals(c.getName()))return c;if(c instanceof Container nested){var result=find(nested,name);if(result!=null)return result;}}return null;}
    private static byte[] ridge(ManagedTileAddress address,IntensitySamplingMode intensity) {
        try {
            var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<512;y++) {
                double worldY=(address.y()*512.0+y+.5)/(512.0*(1<<address.zoom()));
                double lat=Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*worldY))));
                double ground=(lat-50)*111195;int red=(int)Math.round(245*Math.exp(-.5*Math.pow(ground/2,2)));
                for(int x=0;x<512;x++) {
                    if(intensity==IntensitySamplingMode.DIRECT_VALUE)image.setRGB(x,y,(red==0?0:0xff000000)|(red<<16)|Math.min(red,(x/8+y/8)%4)<<8);
                    else {
                        // Native centers from OrdinaryModernApplyPreparationTest.managedChoicePng.
                        int center=switch(address.color()){case "blue"->0xff4ca9ff;case "bluered"->0xffff24b2;case "purple"->0xffbd9cff;case "gray"->0xffe84bea;default->0xfffdfdfd;};
                        int background=3+(x*7+y*3)%29;double strength=red/245.0;
                        int r=(int)Math.round(background+strength*(((center>>>16)&255)-background));
                        int g=(int)Math.round(background+strength*(((center>>>8)&255)-background));
                        int b=(int)Math.round(background+strength*((center&255)-background));
                        image.setRGB(x,y,0xff000000|r<<16|g<<8|b);
                    }
                }
            }
            var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
        }catch(Exception failure){throw new IllegalStateException(failure);}
    }
}
