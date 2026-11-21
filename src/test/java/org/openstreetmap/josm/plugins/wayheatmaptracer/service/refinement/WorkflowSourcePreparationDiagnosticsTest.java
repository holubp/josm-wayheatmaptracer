package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.*;

/** Actual unchanged A/B worker/export path, with only tighter auxiliary limits or extra real cancellation checkpoints. */
class WorkflowSourcePreparationDiagnosticsTest {
    @TempDir Path temp;
    @BeforeAll static void setup(){Config.setPreferencesInstance(new MemoryPreferences());ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));}
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> failures(){return java.util.stream.Stream.of(RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT,RefinementAttemptDiagnostics.Cause.CANCELLED,RefinementAttemptDiagnostics.Cause.STALE).flatMap(c->java.util.stream.Stream.of(org.junit.jupiter.params.provider.Arguments.of(c,false),org.junit.jupiter.params.provider.Arguments.of(c,true)));}
    @Test void packageControlCannotLoosenProductionVisitsOrPhysicalBound() {
        assertThrows(IllegalArgumentException.class,()->new LegacyRefinementWorkflow.SourcePreparationControl(8_000_001,AttemptMemoryLedger.MAX_BYTES,v->{}));
        assertThrows(IllegalArgumentException.class,()->new LegacyRefinementWorkflow.SourcePreparationControl(8_000_000,AttemptMemoryLedger.MAX_BYTES+1,v->{}));
        assertThrows(IllegalArgumentException.class,()->new LegacyRefinementWorkflow.SourcePreparationControl(-1,AttemptMemoryLedger.MAX_BYTES,v->{}));
    }
    @ParameterizedTest @MethodSource("failures")
    void actualHashFailureExportsItsCurrentOwnedWorkAndPriorNativeFacts(RefinementAttemptDiagnostics.Cause cause,boolean afterNative) throws Exception {
        var decoder=new TileDecoderClassifier();var work=new LinkedBlockingQueue<Runnable>();var publications=new LinkedBlockingQueue<Runnable>();
        var workflow=new LegacyRefinementWorkflow[1];var review=new LegacyReviewOwner[1];var data=new DataSet[1];var fired=new AtomicBoolean();
        try(var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(request.address()),null,Duration.ZERO,""),new ManagedTileCache(temp.resolve(cause.name()+afterNative),decoder),decoder,TileReliabilityPolicy.defaults())) {
            long limit=cause==RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT?(afterNative?262_200:0):8_000_000;
            long trigger=afterNative?262_400:256;
            var control=new LegacyRefinementWorkflow.SourcePreparationControl(limit,AttemptMemoryLedger.MAX_BYTES,visits->{
                if(cause==RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT||visits<trigger||!fired.compareAndSet(false,true))return;
                if(cause==RefinementAttemptDiagnostics.Cause.STALE)coordinator.updateActiveGeneration(new ManagedTileGeneration(8));
                else try{SwingUtilities.invokeAndWait(()->((JButton)find(workflow[0].controls(),"cancel")).doClick());}catch(Exception failure){throw new AssertionError(failure);}
            });
            try {
                SwingUtilities.invokeAndWait(()->{
                    data[0]=new DataSet();var nodes=new java.util.ArrayList<Node>();for(int i=0;i<5;i++){var node=new Node(new LatLon(50.000018,14+(i-2)*.00028));data[0].addPrimitive(node);nodes.add(node);}
                    var way=new Way();way.put("highway","path");way.setNodes(nodes);data[0].addPrimitive(way);data[0].allPrimitives().forEach(p->p.setModified(false));
                    var selection=new SelectionContext(way,0,4,nodes,Set.of(nodes.get(0),nodes.get(4)));
                    var config=AlignmentConfig.withoutCleanup(new ManagedHeatmapConfig("key","policy","signature","session","all","hot","",".*",AlignmentMode.PRECISE_SHAPE,TrackerMode.LEGACY_V02,false,false,false,false,false,false,false,true,false,false,7,4,3,InferenceMode.RAW_HIGH_RESOLUTION,15,15,7.01,1.56,IntensitySamplingMode.DIRECT_VALUE,7));
                    review[0]=LegacyReviewOwner.begin(data[0],selection,config);var receipt=new TileHeatmapSampler.AcquisitionReceipt[1];
                    var original=new AlignmentService(new TileHeatmapSampler(coordinator)).alignLegacyReviewed(selection,null,null,config,new AlignmentService.LegacyCaptureObserver(){public void managed(TileHeatmapSampler.AcquisitionReceipt actual){receipt[0]=actual;}});
                    review[0].captured(original,null,receipt[0]);review[0].select(original.candidates().get(0));
                    workflow[0]=new LegacyRefinementWorkflow(review[0],coordinator,review[0]::select,shown->{},status->{},work::add,publications::add,control);workflow[0].enableRuntimeDiagnostics(()->null);
                    ((JButton)find(workflow[0].controls(),"refineB")).doClick();
                });
                work.remove().run();SwingUtilities.invokeAndWait(publications.remove());work.remove().run();
                assertTrue(workflow[0].ownedBytes()>0,"Returned worker still owns queued inputs/results");assertFalse(publications.isEmpty());
                SwingUtilities.invokeAndWait(publications.remove());
                var diagnostic=workflow[0].latestAttemptDiagnostics().orElseThrow();var auxiliary=diagnostic.sourcePreparation().orElseThrow();
                assertEquals(cause,auxiliary.cause());assertEquals(afterNative?"blue":"hot",auxiliary.failedSource().orElseThrow().palette());
                assertTrue(auxiliary.visits()<=limit);assertEquals(auxiliary.visits(),auxiliary.counters().values().stream().mapToLong(Long::longValue).sum());
                var nativeWork=auxiliary.nativeWork().orElseThrow();assertEquals(afterNative,nativeWork.nativeCalls().orElseThrow()>0);assertEquals(afterNative,workflow[0].actualNativeCalls()>0);
                assertEquals(afterNative,nativeWork.actualWork().orElseThrow().transitions()>0);
                var receipt=diagnostic.pipeline().orElseThrow();assertEquals(nativeWork.actualWork(),receipt.actualWork());assertEquals(nativeWork.outcome(),receipt.outcome());
                assertTrue(receipt.attempts().stream().anyMatch(a->a.reason().equals("source-preparation-"+cause.name().toLowerCase(java.util.Locale.ROOT).replace('_','-'))&&a.kind()==RefinementPipeline.AttemptKind.UNAVAILABLE&&a.nativeCalls().orElseThrow()==0));
                if(cause!=RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT)assertTrue(fired.get());
                if(!afterNative||cause!=RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT){assertEquals(RefinementAttemptDiagnostics.Phase.SOURCE_PREPARATION,diagnostic.phase());assertEquals(cause,diagnostic.cause());}
                Path file=temp.resolve("failure-"+cause+afterNative+".zip");DiagnosticsRegistry.writeLatest(file.toFile());
                var archive=Format15ArchiveReader.read(file);var exported=RefinementLineageCodec.read(archive.artifact("refinement-lineage.json").orElseThrow()).latestPublication().orElseThrow().diagnostic();
                assertEquals(diagnostic,exported,"Export retains actual phase/cause/counters and prior native work under the exact current attempt");
                SwingUtilities.invokeAndWait(()->{assertFalse(workflow[0].busy());assertFalse(data[0].isModified());});
            } finally {
                SwingUtilities.invokeAndWait(()->{if(workflow[0]!=null)workflow[0].close();});while(!work.isEmpty())work.remove().run();while(!publications.isEmpty())SwingUtilities.invokeAndWait(publications.remove());
                SwingUtilities.invokeAndWait(()->{if(review[0]!=null)review[0].close();DiagnosticsRegistry.setLastBundle(null);});if(workflow[0]!=null)assertEquals(0,workflow[0].ownedBytes());
            }
        }
    }
    private static Component find(Container root,String name){for(Component child:root.getComponents()){if(name.equals(child.getName()))return child;if(child instanceof Container nested){var found=find(nested,name);if(found!=null)return found;}}return null;}
    private static byte[] ridge(ManagedTileAddress address) {
        try{var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<512;y++){double worldY=(address.y()*512.0+y+.5)/(512.0*(1<<address.zoom()));double lat=Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*worldY))));int red=(int)Math.round(245*Math.exp(-.5*Math.pow((lat-50)*111195/2,2)));for(int x=0;x<512;x++)image.setRGB(x,y,(red==0?0:0xff000000)|(red<<16)|Math.min(red,(x/8+y/8)%4)<<8);}var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}catch(Exception failure){throw new IllegalStateException(failure);}
    }
}
