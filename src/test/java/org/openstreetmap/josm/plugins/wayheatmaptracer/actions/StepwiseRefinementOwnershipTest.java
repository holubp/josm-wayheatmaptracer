package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.spi.preferences.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;

/** Actual shared action-entry/worker glue. GUI window coverage remains a separate host gate. */
class StepwiseRefinementOwnershipTest {
    @TempDir Path temp;
    @BeforeEach void setup() throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        var content=MainApplication.class.getDeclaredField("contentPanePrivate");content.setAccessible(true);content.set(null,new JPanel());
    }
    @Test void sameWayAndConfigSupersessionWaitsForActualLateResultDiscard() throws Exception {exercise(false);}
    @Test void ignoredInterruptCannotReleaseNativeInputPinsBeforeWorkerAndQueuedCleanup() throws Exception {exercise(true);}
    @Test void returningToLegacyKeepsTheOriginalManagedSourceAuthority() throws Exception {
        var decoder=new TileDecoderClassifier();
        try(var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(request.address()),null,Duration.ZERO,""),
                new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());
            var session=new PreviewSessionController<LiveBPreviewService.Computed>(SwingUtilities::invokeLater)) {
            var work=new LinkedBlockingQueue<Runnable>();var publications=new LinkedBlockingQueue<Runnable>();var state=new Fixture[1];
            SwingUtilities.invokeAndWait(()->{state[0]=fixture(coordinator,session,work,publications,new AtomicInteger());((JButton)find(state[0].workflow.controls(),"refineA")).doClick();});
            work.remove().run();SwingUtilities.invokeAndWait(publications.remove());work.remove().run();SwingUtilities.invokeAndWait(publications.remove());
            SwingUtilities.invokeAndWait(()->{
                var f=state[0];try {
                    assertTrue(f.workflow.nativeSelected());((JButton)find(f.workflow.controls(),"undo")).doClick();assertFalse(f.workflow.nativeSelected());
                    var result=f.review.originalResult();var candidate=f.review.selectedCandidate();
                    var service=new AlignmentService(new TileHeatmapSampler(coordinator));
                    CandidateReviewConfirmation confirmation=AlignmentService.assessCandidate(candidate).reviewRequired()
                        ?AlignWayAction.confirmLegacyReview(f.review,service,f.data,result.selection(),result,candidate,f.config.heatmap()):null;
                    assertNotNull(AlignWayAction.createLegacyReviewCommand(f.review,service,f.data,result.selection(),result,candidate,f.config.heatmap(),confirmation));
                    var apply=new JButton("Apply");var confirm=new JButton("Confirm");
                    var refresh=AlignWayAction.createReviewButtonRefresh(f.review,apply,confirm,()->new AlignWayAction.ReviewButtonContext(
                        AlignmentService.assessCandidate(candidate).disposition(),confirmation!=null,false,false,false,true));
                    refresh.run();assertTrue(apply.isEnabled());
                    coordinator.updateActiveGeneration(new ManagedTileGeneration(8));coordinator.updateActiveGeneration(new ManagedTileGeneration(7));
                    assertFalse(f.review.invalidated(),"No dataset or preference invalidation may mask the original receipt check");
                    f.review.requireOriginalAttemptCurrent();refresh.run();assertFalse(apply.isEnabled());assertFalse(confirm.isEnabled());
                    assertThrows(IllegalStateException.class,()->AlignWayAction.createLegacyReviewCommand(f.review,service,f.data,result.selection(),result,candidate,f.config.heatmap(),confirmation));
                    assertFalse(f.data.isModified());
                }finally{f.workflow.close();f.review.close();session.closeAll();}
            });
        }
    }
    @Test void actualNativeEntryCancelWaitsForPhysicalAndQueuedResultSettlement() throws Exception {
        var decoder=new TileDecoderClassifier();
        try(var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(request.address()),null,Duration.ZERO,""),
                new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());
            var session=new PreviewSessionController<LiveBPreviewService.Computed>(SwingUtilities::invokeLater)) {
            var work=new LinkedBlockingQueue<Runnable>();var publications=new LinkedBlockingQueue<Runnable>();var state=new Fixture[1];var displays=new AtomicInteger();
            var cancelled=new CompletableFuture<LegacyRefinementWorkflow.NativeEntry>();
            try {
                SwingUtilities.invokeAndWait(()->{
                    state[0]=fixture(coordinator,session,work,publications,displays);var workflow=state[0].workflow;
                    workflow.firstNativeEntry().whenComplete((entry,failure)->{
                        try {
                            assertNull(failure);assertTrue(SwingUtilities.isEventDispatchThread());assertTrue(entry.entered());assertTrue(workflow.ownsNativeEntry(entry));
                            assertEquals(AlignmentMode.PRECISE_SHAPE,entry.ticket().mode());assertTrue(workflow.ownedBytes()>0);assertFalse(entry.settlement().toCompletableFuture().isDone());
                            var cancel=(JButton)find(workflow.controls(),"cancel");assertTrue(cancel.isEnabled());cancel.doClick();assertTrue(entry.cancellationRequested());cancelled.complete(entry);
                        }catch(Throwable failed){cancelled.completeExceptionally(failed);}
                    });
                    ((JButton)find(workflow.controls(),"refineA")).doClick();
                });
                work.remove().run();SwingUtilities.invokeAndWait(publications.remove());
                work.remove().run(); // real production native call; entry queues the real Cancel on EDT
                var entry=cancelled.get(5,TimeUnit.SECONDS);
                assertTrue(entry.workerReturned());assertFalse(entry.settlement().toCompletableFuture().isDone());
                assertFalse(session.workGate().settlement().toCompletableFuture().isDone());assertTrue(state[0].workflow.ownedBytes()>0);assertEquals(0,displays.get());
                SwingUtilities.invokeAndWait(publications.remove());
                assertTrue(entry.cancellationObserved());assertTrue(entry.settlement().toCompletableFuture().isDone());assertTrue(session.workGate().settlement().toCompletableFuture().isDone());
                SwingUtilities.invokeAndWait(()->{assertFalse(state[0].workflow.busy());assertFalse(state[0].workflow.nativeSelected());assertEquals(entry.ticket().parent(),state[0].workflow.currentStage().id());assertFalse(state[0].data.isModified());assertEquals(0,displays.get());});
            } finally {SwingUtilities.invokeAndWait(()->{if(state[0]!=null){state[0].workflow.close();state[0].review.close();}session.closeAll();});}
        }
    }
    private void exercise(boolean holdWorker) throws Exception {
        var decoder=new TileDecoderClassifier();
        try(var coordinator=new TileFetchCoordinator((request,credentials)->new TransportResponse(TileFetchStatus.SUCCESS_NETWORK,200,"image/png",ridge(request.address()),null,Duration.ZERO,""),
                new ManagedTileCache(temp,decoder),decoder,TileReliabilityPolicy.defaults());
            var session=new PreviewSessionController<LiveBPreviewService.Computed>(SwingUtilities::invokeLater)) {
            var work=new LinkedBlockingQueue<Runnable>();var publications=new LinkedBlockingQueue<Runnable>();var fixture=new Fixture[1];
            var normal=new AlignWayAction[1];var precise=new AlignWayAction[1];var move=new AlignWayAction[1];
            var display=new AtomicInteger();var nextPrecise=new AtomicInteger();var nextMove=new AtomicInteger();var resumed=new CountDownLatch(1);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var ignored=new CountDownLatch(1);Thread running=null;
            try {
                SwingUtilities.invokeAndWait(()->{
                    normal[0]=new AlignWayAction(session);precise[0]=new AlignWayAction(AlignmentMode.PRECISE_SHAPE,session);move[0]=new AlignWayAction(AlignmentMode.MOVE_EXISTING_NODES,session);
                    assertTrue(normal[0].requestPreparedInvocation(new Object(),()->fail("Idle start is immediate"),()->{}));
                    fixture[0]=fixture(coordinator,session,work,publications,display);
                    assertEquals(AlignmentMode.PRECISE_SHAPE,normal[0].effectiveConfig(fixture[0].config.heatmap()).alignmentMode());
                    assertEquals(AlignmentMode.PRECISE_SHAPE,precise[0].effectiveConfig(fixture[0].config.heatmap()).alignmentMode());
                    ((JButton)find(fixture[0].workflow.controls(),"refineA")).doClick();
                });
                work.remove().run();SwingUtilities.invokeAndWait(publications.remove()); // actual bootstrap -> queued native operation
                assertEquals(1,work.size());
                if(holdWorker) {
                    Runnable actual=work.remove();running=new Thread(()->{
                        entered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException interrupted){ignored.countDown();}
                        actual.run();
                    });running.start();assertTrue(entered.await(3,TimeUnit.SECONDS));
                } else {
                    work.remove().run(); // actual Task4 result is complete, publication intentionally held
                    assertFalse(publications.isEmpty());assertTrue(fixture[0].workflow.actualNativeCalls()>0);
                }
                SwingUtilities.invokeAndWait(()->{
                    assertTrue(fixture[0].workflow.busy());assertTrue(fixture[0].workflow.ownedBytes()>0);
                    assertFalse(fixture[0].review.invalidated(),"No epoch change may mask missing global ownership");
                    assertFalse(precise[0].requestPreparedInvocation(new Object(),nextPrecise::incrementAndGet,()->{}));
                    assertFalse(move[0].requestPreparedInvocation(new Object(),()->{nextMove.incrementAndGet();resumed.countDown();},()->{}));
                    assertFalse(fixture[0].review.invalidated());assertFalse(fixture[0].data.isModified());
                    assertFalse(session.workGate().settlement().toCompletableFuture().isDone());
                    assertTrue(fixture[0].workflow.ownedBytes()>0,"Cancelled/closed UI still owns input and queued-result reservations");
                    assertEquals(0,nextPrecise.get());assertEquals(0,nextMove.get());assertEquals(0,display.get());
                });
                if(holdWorker) {
                    running.interrupt();assertTrue(ignored.await(3,TimeUnit.SECONDS));
                    assertFalse(session.workGate().settlement().toCompletableFuture().isDone());
                    release.countDown();running.join(5000);assertFalse(running.isAlive());
                }
                assertTrue(fixture[0].workflow.ownedBytes()>0,"Physical return alone cannot release queued publication input");
                SwingUtilities.invokeAndWait(publications.remove());
                assertTrue(resumed.await(3,TimeUnit.SECONDS));
                assertEquals(0,nextPrecise.get(),"Superseded queued intent never starts");assertEquals(1,nextMove.get());assertEquals(0,display.get(),"Late old output cannot overwrite overlay/publication");
                assertEquals(0,fixture[0].workflow.ownedBytes());assertTrue(session.workGate().settlement().toCompletableFuture().isDone());
            } finally {
                release.countDown();if(running!=null)running.join(5000);
                SwingUtilities.invokeAndWait(()->{if(fixture[0]!=null){fixture[0].workflow.close();fixture[0].review.close();}if(normal[0]!=null)normal[0].destroy();if(precise[0]!=null)precise[0].destroy();if(move[0]!=null)move[0].destroy();});
            }
        }
    }
    private record Fixture(DataSet data,AlignmentConfig config,LegacyReviewOwner review,LegacyRefinementWorkflow workflow){ }
    private static Fixture fixture(TileFetchCoordinator coordinator,PreviewSessionController<LiveBPreviewService.Computed> session,
            LinkedBlockingQueue<Runnable> work,LinkedBlockingQueue<Runnable> publications,AtomicInteger displayed) {
        var data=new DataSet();var nodes=new ArrayList<Node>();for(int i=0;i<5;i++){var n=new Node(new LatLon(50.000018,14+(i-2)*.00028));data.addPrimitive(n);nodes.add(n);}
        var way=new Way();way.put("highway","path");way.setNodes(nodes);data.addPrimitive(way);data.allPrimitives().forEach(p->p.setModified(false));data.setSelected(way);
        var selected=new SelectionContext(way,0,4,nodes,Set.of(nodes.get(0),nodes.get(4)));
        var config=AlignmentConfig.withoutCleanup(new ManagedHeatmapConfig("key","policy","signature","session","all","hot","",".*",AlignmentMode.PRECISE_SHAPE,
            TrackerMode.LEGACY_V02,false,false,false,false,false,false,false,true,false,false,7,4,3,InferenceMode.RAW_HIGH_RESOLUTION,15,15,7.01,1.56,IntensitySamplingMode.DIRECT_VALUE,7));
        var review=LegacyReviewOwner.begin(data,selected,config);var receipt=new TileHeatmapSampler.AcquisitionReceipt[1];
        var result=new AlignmentService(new TileHeatmapSampler(coordinator)).alignLegacyReviewed(selected,null,null,config,new AlignmentService.LegacyCaptureObserver(){public void managed(TileHeatmapSampler.AcquisitionReceipt actual){receipt[0]=actual;}});
        review.captured(result,null,receipt[0]);review.select(result.candidates().get(0));
        var owner=session.open(()->{});
        var workflow=new LegacyRefinementWorkflow(review,coordinator,review::select,actual->displayed.incrementAndGet(),status->{},work::add,publications::add);
        session.replaceWindow(owner,()->{workflow.close();review.close();});
        workflow.attachOwnership(session.workGate(),owner,()->{session.close(owner);review.close();});
        return new Fixture(data,config,review,workflow);
    }
    private static Component find(Container parent,String name){for(var c:parent.getComponents()){if(name.equals(c.getName()))return c;if(c instanceof Container nested){var found=find(nested,name);if(found!=null)return found;}}return null;}
    private static byte[] ridge(ManagedTileAddress address) {
        try {var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<512;y++){
            double world=(address.y()*512.0+y+.5)/(512.0*(1<<address.zoom()));double latitude=Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-2*world))));
            int red=(int)Math.round(245*Math.exp(-.5*Math.pow((latitude-50)*111195/2,2)));
            for(int x=0;x<512;x++)image.setRGB(x,y,(red==0?0:0xff000000)|(red<<16)|Math.min(red,(x/8+y/8)%4)<<8);
        }var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();}catch(Exception failure){throw new IllegalStateException(failure);}
    }
}
