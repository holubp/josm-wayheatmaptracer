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
    private void executeWorkflow(AlignmentMode mode,TrackerMode engine,IntensitySamplingMode intensity) throws Exception {
        var decoder=new TileDecoderClassifier();var requests=new AtomicInteger();var palettes=ConcurrentHashMap.<String>newKeySet();
        try(var coordinator=new TileFetchCoordinator((request,credentials)->{
            requests.incrementAndGet();palettes.add(request.address().color());
            return new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(request.address(),intensity),null,Duration.ZERO,"");
        },new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults())) {
            var workflow=new LegacyRefinementWorkflow[1];var owner=new LegacyReviewOwner[1];var original=new AlignmentResult[1];var data=new DataSet[1];
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
                    mode,TrackerMode.LEGACY_V02,false,false,false,false,false,false,false,true,false,false,
                    7,4,3,InferenceMode.RAW_HIGH_RESOLUTION,15,15,7.01,1.56,intensity,7));
                owner[0]=LegacyReviewOwner.begin(data[0],selection,config);
                var receipt=new TileHeatmapSampler.AcquisitionReceipt[1];
                original[0]=new AlignmentService(new TileHeatmapSampler(coordinator)).alignLegacyReviewed(selection,null,null,config,
                    new AlignmentService.LegacyCaptureObserver(){public void managed(TileHeatmapSampler.AcquisitionReceipt actual){receipt[0]=actual;}});
                assertHistoryUnchanged(historyBefore.get(),redoBefore.get());
                assertFalse(original[0].candidates().isEmpty());
                owner[0].captured(original[0],null,receipt[0]);owner[0].select(original[0].candidates().get(0));
                workflow[0]=new LegacyRefinementWorkflow(owner[0],coordinator,owner[0]::select,selected::set,message->{
                    status.set(message);if(started.get()&&workflow[0]!=null&&!workflow[0].busy())completed.get().countDown();
                });
                started.set(true);((JButton)find(workflow[0].controls(),engine==TrackerMode.CORRIDOR_AWARE?"refineA":"refineB")).doClick();
            });
            assertTrue(completed.get().await(90,TimeUnit.SECONDS),"Actual refinement did not settle");
            var firstStage=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Stage[1];
            var firstCalls=new AtomicInteger();
            SwingUtilities.invokeAndWait(()->{
                assertHistoryUnchanged(historyBefore.get(),redoBefore.get());
                assertTrue(workflow[0].nativeSelected(),status.get()+" "+workflow[0].computationEvidence());assertNotNull(selected.get());
                assertTrue(workflow[0].actualNativeCalls()>0,"Actual kernel entry is required");
                assertEquals(Set.copyOf(RefinementSourcePlanner.BASE_PALETTES),palettes);
                var factual=workflow[0].currentNative().orElseThrow();
                assertFalse(factual.sources().isEmpty());
                var attempts=factual.operation().orElseThrow().attempts();
                assertEquals(Set.copyOf(RefinementSourcePlanner.BASE_PALETTES),attempts.stream().map(RefinementOperationReceipt.Attempt::palette).collect(java.util.stream.Collectors.toSet()));
                assertTrue(attempts.stream().anyMatch(a->a.nativeCalls().orElse(0)>0&&a.evidenceHash().isPresent()&&a.parameterHash().isPresent()));
                for(var attempt:attempts)if(attempt.nativeCalls().orElse(0)>0)assertTrue(attempt.nativeStatus().isPresent(),"Preserve actual no-result as well as successful native status");
                int actualCalls=workflow[0].actualNativeCalls();
                assertFalse(data[0].isModified(),"Preview cannot mutate OSM");
                var chosen=selected.get().candidate().finalPreviewPoints();int after=requests.get();
                ((JButton)find(workflow[0].controls(),"undo")).doClick();assertFalse(workflow[0].nativeSelected());
                ((JButton)find(workflow[0].controls(),"redo")).doClick();assertTrue(workflow[0].nativeSelected());
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
    private static void assertHistoryUnchanged(java.util.List<org.openstreetmap.josm.command.Command> undoBefore,
            java.util.List<org.openstreetmap.josm.command.Command> redoBefore) {
        var undo=UndoRedoHandler.getInstance();
        assertEquals(undoBefore,undo.getUndoCommands(),"Preview must not add or alter Undo history");
        assertEquals(redoBefore,undo.getRedoCommands(),"Preview must not alter Redo history");
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
