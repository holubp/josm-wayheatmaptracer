package org.openstreetmap.josm.plugins.wayheatmaptracer;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.data.projection.*;
import org.openstreetmap.josm.spi.preferences.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.tile.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.LastSlideDebugBundle;

/** Exact public regression with controlled physical worker and publication boundaries. */
class PublicPreciseBAResourceDiagnosisTest {
    @TempDir java.nio.file.Path temporary;
    @Test void currentPublicPreciseBAKeepsActualResourceAndOwnerEvidence() throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());
        Config.setBaseDirectoriesProvider(new IBaseDirectories(){public java.io.File getPreferencesDirectory(boolean create){return temporary.toFile();}
            public java.io.File getUserDataDirectory(boolean create){return temporary.toFile();}public java.io.File getCacheDirectory(boolean create){return temporary.toFile();}});
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
        var fixture=PublicGuiSmokeFixture.create(temporary.resolve("inputs"));var decoder=new TileDecoderClassifier();
        var cache=new ManagedTileCache(temporary.resolve("tiles"),decoder);fixture.seedRefinementTiles(cache);
        var pending=new LinkedBlockingQueue<Runnable>();var workers=new LinkedBlockingQueue<Runnable>();
        var workflow=new LegacyRefinementWorkflow[1];var review=new LegacyReviewOwner[1];
        var originalNodes=List.copyOf(fixture.selectedWay().getNodes());var originalCoordinates=originalNodes.stream().map(n->n.getCoor()).toList();
        try(var coordinator=new TileFetchCoordinator((request,credentials)->{throw new AssertionError("public reproduction must remain offline");},cache,decoder,TileReliabilityPolicy.defaults())) {
            SwingUtilities.invokeAndWait(()->{
                var config=AlignmentConfig.withoutCleanup(PublicGuiSmokeMain.stepwiseConfig(AlignmentMode.PRECISE_SHAPE));
                var way=fixture.selectedWay();var nodes=List.copyOf(way.getNodes());
                var selection=new SelectionContext(way,0,nodes.size()-1,nodes,Set.of(nodes.get(0),nodes.get(nodes.size()-1)));
                review[0]=LegacyReviewOwner.begin(fixture.dataSet(),selection,config);var receipt=new TileHeatmapSampler.AcquisitionReceipt[1];
                var original=new AlignmentService(new TileHeatmapSampler(coordinator)).alignLegacyReviewed(selection,null,null,config,
                    new AlignmentService.LegacyCaptureObserver(){public void managed(TileHeatmapSampler.AcquisitionReceipt value){receipt[0]=value;}});
                review[0].captured(original,null,receipt[0]);var initial=original.candidates().get(0);
                var context=LastSlideDebugBundle.fromResult(original,initial,initial,"preview-open","",Map.of());
                workflow[0]=new LegacyRefinementWorkflow(review[0],coordinator,review[0]::select,value->{},value->{},workers::add,pending::add);
                workflow[0].enableRuntimeDiagnostics(()->context,Map::of);
                var choices=(JComboBox<?>)named(workflow[0].controls(),"candidate");assertTrue(choices.getItemCount()>1);choices.setSelectedIndex(1);
                workflow[0].refine(TrackerMode.PROBABILISTIC);
            });
            pump(workflow[0],workers,pending,"B");
            SwingUtilities.invokeAndWait(()->{
                assertEquals(StageKind.REFINE_B,workflow[0].currentStage().kind(),workflow[0].computationEvidence());
                assertEquals(Outcome.COMPLETE,workflow[0].currentNative().orElseThrow().operation().orElseThrow().outcome());
                var choices=(JComboBox<?>)named(workflow[0].controls(),"candidate");choices.setSelectedIndex(choices.getItemCount()-1);
                assertEquals(1,choices.getItemCount());
                assertTrue(workflow[0].selectedGeometry().size()>2);
                System.out.println("BA-selected-B candidates="+choices.getItemCount()+" points="+workflow[0].selectedGeometry().size()+" ownedBytes="+workflow[0].ownedBytes());
                ((JButton)named(workflow[0].controls(),"undo")).doClick();((JButton)named(workflow[0].controls(),"redo")).doClick();
                workflow[0].refine(TrackerMode.CORRIDOR_AWARE);
            });
            pump(workflow[0],workers,pending,"A");
            SwingUtilities.invokeAndWait(()->{
                System.out.println("BA-settled stage="+workflow[0].currentStage().kind()+" ownedBytes="+workflow[0].ownedBytes()+" evidence="+workflow[0].computationEvidence());
                assertEquals(StageKind.REFINE_A,workflow[0].currentStage().kind(),"Exact public Precise B->A must publish the requested child");
                var actual=workflow[0].currentNative().orElseThrow().operation().orElseThrow();
                assertTrue(actual.attempts().stream().anyMatch(a->a.nativeStatus().filter(s->s==TraceHypothesisSet.Status.COMPLETE).isPresent()));
                assertTrue(actual.actualWork().orElseThrow().transitions()>0,"Published A geometry must come from an actual completed solve");
                assertEquals(1,actual.attempts().size(),"A reuses the exact selected B detector");
                assertEquals(RefinementAttemptDiagnostics.Publication.PUBLISHED,workflow[0].latestAttemptDiagnostics().orElseThrow().publication());
                assertEquals(originalNodes,fixture.selectedWay().getNodes());assertEquals(originalCoordinates,originalNodes.stream().map(n->n.getCoor()).toList());
            });
        }finally{SwingUtilities.invokeAndWait(()->{if(workflow[0]!=null)workflow[0].close();if(review[0]!=null)review[0].close();});ManagedTileRuntime.close();}
        assertEquals(0,workflow[0].workflowOwnedBytes(),"Actual close settles physical workflow inputs and output");
    }
    private static void pump(LegacyRefinementWorkflow workflow,BlockingQueue<Runnable> workers,BlockingQueue<Runnable> pending,String label) throws Exception {
        while(true) {
            var busy=new AtomicBoolean();SwingUtilities.invokeAndWait(()->busy.set(workflow.busy()));if(!busy.get())return;
            var worker=workers.poll(120,TimeUnit.SECONDS);assertNotNull(worker,"actual worker must be queued");
            var session=(RefinementSession)field(workflow,"session");
            if(session!=null)SwingUtilities.invokeAndWait(()->{
                assertTrue(session.reservedBytes()>0,"Logical history admission precedes the worker");
                assertEquals(0,reservations(publicationOwner(workflow)),"Unallocated future Stage capacity must not compete with native work");
                assertTrue(workflow.ownedBytes()>0,"Root and selected-history inputs stay physically charged");
            });
            worker.run();
            var publication=pending.poll(120,TimeUnit.SECONDS);assertNotNull(publication,"actual worker publication must settle");
            var memory=(AttemptMemoryLedger)field(workflow,"memory");
            synchronized(memory) {
                System.out.println("BA-inventory phase="+label+" current="+memory.currentBytes()+" peak="+memory.peakBytes()+" limit="+memory.limitBytes());
                var seen=new IdentityHashMap<Object,Boolean>();long accounted=inventory(field(memory,"root"),seen,label);
                assertEquals(memory.currentBytes(),accounted,"unique active charges plus reservations must explain the entire ledger");
            }
            if(session!=null)SwingUtilities.invokeAndWait(()->assertEquals(session.reservedBytes(),reservations(publicationOwner(workflow)),"The same full allowance pins materialized/queued Stage while raw operation owners remain live"));
            SwingUtilities.invokeAndWait(publication);
        }
    }
    private static Object publicationOwner(LegacyRefinementWorkflow workflow) {
        try {
            var ticket=(OperationTicket)field(workflow,"active");var root=field(workflow,"rootMemory");
            for(var child:(Set<?>)field(root,"children"))if(field(child,"name").equals("publication-"+ticket.sequence()))return child;
            throw new AssertionError("Current publication owner missing");
        }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static long reservations(Object owner) {
        try{return ((Set<?>)field(owner,"reservations")).stream().map(AttemptMemoryLedger.Reservation.class::cast).mapToLong(AttemptMemoryLedger.Reservation::bytes).sum();}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static long inventory(Object owner,IdentityHashMap<Object,Boolean> seen,String phase) throws Exception {
        long reservations=0,leases=0;var types=new TreeMap<String,Long>();
        for(Object value:(Set<?>)field(owner,"reservations"))reservations+=((AttemptMemoryLedger.Reservation)value).bytes();
        for(Object value:((Map<?,?>)field(owner,"leases")).values()) {
            var lease=(AttemptMemoryLedger.MemoryLease)value;var charge=field(value,"charge");
            if(seen.put(charge,Boolean.TRUE)!=null)continue;leases+=lease.bytes();var identity=field(value,"identity");
            types.merge(identity.getClass().getSimpleName(),lease.bytes(),Long::sum);
            if(identity instanceof RefinementPipeline.Result result)dumpResult(phase,result);
        }
        if(reservations+leases>0)System.out.println("BA-owner phase="+phase+" name="+field(owner,"name")+" reserved="+reservations+" leased="+leases+" types="+types);
        long total=reservations+leases;for(Object child:(Set<?>)field(owner,"children"))total+=inventory(child,seen,phase);return total;
    }
    private static void dumpResult(String phase,RefinementPipeline.Result result) {
        System.out.println("BA-pipeline phase="+phase+" outcome="+result.outcome()+" candidates="+result.candidates().size()+" work="+result.actualWork());
        if(phase.equals("B")) {
            assertEquals(new TraceWorkUsage(9968,2552262,32,1),result.actualWork().orElseThrow(),"Only the selected B detector contributes native work");
            assertEquals(1,result.attempts().stream().flatMap(a->a.run().stream()).mapToInt(RefinementEngineAdapter.Run::nativeCalls).sum());
            assertTrue(result.attempts().stream().flatMap(a->a.run().stream()).allMatch(r->r.request().profileChainage().cumulativeGroundMeters().size()==54));
        }
        for(var attempt:result.attempts()) {
            System.out.println("BA-attempt phase="+phase+" mapping="+attempt.source().mapping()+" kind="+attempt.kind()+" reason="+attempt.reason()+" issued="+attempt.issuedBudgets());
            attempt.run().ifPresent(run->{System.out.println("BA-run phase="+phase+" calls="+run.nativeCalls()+" work="+run.actualWorkAvailability()
                +" requestRows="+(run.request()==null?"UNAVAILABLE":run.request().profileChainage().cumulativeGroundMeters().size()));
                run.nativeRun().ifPresent(nativeRun->{var raw=nativeRun.result();System.out.println("BA-native phase="+phase+" status="+raw.status()+" hypotheses="+raw.hypotheses().size()
                    +" truncated="+raw.alternativesTruncated()+" evaluatedStates="+raw.evaluatedStates()+" evaluatedTransitions="+raw.evaluatedTransitions()+" explanation="+raw.explanation());
                    nativeRun.corridorReceipt().ifPresent(receipt->System.out.println("BA-corridor phase="+phase+" nativePhase="+receipt.phase()+" totals="+receipt.totals()+" tracks="+receipt.tracks().size()+" sampling="+receipt.sampling()));});});
        }
    }
    private static Object field(Object object,String name) throws ReflectiveOperationException {
        for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try{Field value=type.getDeclaredField(name);value.setAccessible(true);return value.get(object);}catch(NoSuchFieldException missing){ }
        throw new NoSuchFieldException(name);
    }
    private static java.awt.Component named(java.awt.Container root,String name) {
        for(var component:root.getComponents()){if(name.equals(component.getName()))return component;if(component instanceof java.awt.Container child){var match=named(child,name);if(match!=null)return match;}}return null;
    }
}
