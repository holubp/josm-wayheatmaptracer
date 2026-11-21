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
            var completed=new AtomicReference<>(new CountDownLatch(1));var started=new AtomicBoolean();var status=new AtomicReference<>("");var selected=new AtomicReference<LegacyRefinementWorkflow.Display>();
            SwingUtilities.invokeAndWait(()->{
                data[0]=new DataSet();var nodes=new java.util.ArrayList<Node>();
                for(int i=0;i<5;i++){var n=new Node(new LatLon(50.000018,14+(i-2)*.00028));data[0].addPrimitive(n);nodes.add(n);}
                var way=new Way();way.put("highway","path");way.setNodes(nodes);data[0].addPrimitive(way);data[0].allPrimitives().forEach(p->p.setModified(false));
                var selection=new SelectionContext(way,0,4,nodes,Set.of(nodes.get(0),nodes.get(4)));
                var config=AlignmentConfig.withoutCleanup(new ManagedHeatmapConfig("key","policy","signature","session","all","hot","",".*",
                    mode,TrackerMode.LEGACY_V02,false,false,false,false,false,false,false,true,false,false,
                    7,4,3,InferenceMode.RAW_HIGH_RESOLUTION,15,15,7.01,1.56,intensity,7));
                owner[0]=LegacyReviewOwner.begin(data[0],selection,config);
                var receipt=new TileHeatmapSampler.AcquisitionReceipt[1];
                original[0]=new AlignmentService(new TileHeatmapSampler(coordinator)).alignLegacyReviewed(selection,null,null,config,
                    new AlignmentService.LegacyCaptureObserver(){public void managed(TileHeatmapSampler.AcquisitionReceipt actual){receipt[0]=actual;}});
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
                firstStage[0]=workflow[0].currentStage();firstCalls.set(actualCalls);
                completed.set(new CountDownLatch(1));
                ((JButton)find(workflow[0].controls(),engine==TrackerMode.CORRIDOR_AWARE?"refineB":"refineA")).doClick();
            });
            assertTrue(completed.get().await(90,TimeUnit.SECONDS),"Chained refinement did not settle");
            SwingUtilities.invokeAndWait(()->{
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
                assertTrue(command.executeCommand());
                assertTrue(owner[0].invalidated(),"Own Apply must invalidate the UI owner without poisoning command Redo");
                workflow[0].close();owner[0].close();
                var applied=original[0].selection().way().getNodes().stream().map(Node::getEastNorth).toList();
                assertEquals(chosen,applied);command.undoCommand();assertFalse(data[0].isModified());
                assertTrue(command.executeCommand(),"Redo must survive the closed UI and its own dataset changes");
                assertEquals(applied,original[0].selection().way().getNodes().stream().map(Node::getEastNorth).toList());
                command.undoCommand();assertFalse(data[0].isModified());
            });
        }
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
