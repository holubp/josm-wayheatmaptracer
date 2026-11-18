package org.openstreetmap.josm.plugins.wayheatmaptracer.actions;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ArchiveReader;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CorridorPolicyFixture;

/** The actual action registry boundary must preserve rich diagnostics in either window ordering. */
class CorridorCancellationExportTest {
    @Test void newerAttemptFailedBeforeOpenRevokesOlderSidecar(@TempDir Path directory)throws Exception{
        var cancelled=CorridorPolicyFixture.finalizationCancellation();
        var fixture=CorridorPolicyFixture.straight(true,false,Set.of());var events=new LinkedBlockingQueue<Runnable>();
        try(var session=new PreviewSessionController<LiveBPreviewService.Computed>(events::add)){
            var owner=session.open(()->{});
            var snapshot=new AlignmentJob.AttemptSnapshot(fixture.network().snapshotId(),"synthetic-source",
                fixture.request().settingsHash(),fixture.network().canonicalHash());
            session.startDetached(owner,()->new AlignmentJob.CapturedAttempt<>(snapshot,"detached"),
                (input,context)->{throw cancelled;},attempt->fail("No cancelled preview"),"old-identity",
                event->AlignWayAction.publishFinalizationCancellationIfLatest(session,owner,event,"visible-layer","old-identity"));
            var delivery=events.poll(10,TimeUnit.SECONDS);assertNotNull(delivery);session.close(owner);
            String newer=AlignWayAction.beginDiagnosticAttempt(session);
            AlignWayAction.recordModernUnavailable("failed","unavailable",newer);
            delivery.run();Path exported=directory.resolve("newer-failed.zip");DiagnosticsRegistry.writeLatest(exported.toFile());
            var archive=Format15ArchiveReader.read(exported);
            assertTrue(archive.artifact("corridor-execution.csv").isEmpty());
            String status=new String(archive.artifact("attempt-status.json").orElseThrow().bytes(),StandardCharsets.UTF_8);
            assertTrue(status.contains("\"status\":\"failed\""));
            assertFalse(status.contains("old-identity"));
        }
    }
    @Test void callbackAndWindowOrdersKeepChecksummedCompletedWorkWithoutPreview(@TempDir Path directory)throws Exception{
        var cancelled=CorridorPolicyFixture.finalizationCancellation();
        var fixture=CorridorPolicyFixture.straight(true,false,Set.of());
        String networkBefore=fixture.network().canonicalHash();
        for(boolean windowFirst:new boolean[]{false,true}){
            var events=new LinkedBlockingQueue<Runnable>();var previews=new AtomicInteger();
            try(var session=new PreviewSessionController<LiveBPreviewService.Computed>(events::add)){
                var owner=session.open(()->{});
                var snapshot=new AlignmentJob.AttemptSnapshot(fixture.network().snapshotId(),"synthetic-source",
                    fixture.request().settingsHash(),fixture.network().canonicalHash());
                String identity="native-finalization-"+windowFirst;
                AlignWayAction.recordModernUnavailable("started","visible-layer",identity);
                session.startDetached(owner,()->new AlignmentJob.CapturedAttempt<>(snapshot,"detached"),
                    (input,context)->{throw cancelled;},attempt->previews.incrementAndGet(),identity,
                    event->AlignWayAction.publishFinalizationCancellationIfLatest(session,owner,event,"visible-layer",identity));
                Runnable delivery=events.poll(10,TimeUnit.SECONDS);assertNotNull(delivery);
                if(windowFirst)assertTrue(AlignWayAction.closeAndPublishIfCurrent(session,owner,
                    ()->AlignWayAction.recordModernUnavailable("cancelled","visible-layer",identity)));
                delivery.run();
                Path exported=directory.resolve("cancel-"+windowFirst+".zip");DiagnosticsRegistry.writeLatest(exported.toFile());
                var first=Format15ArchiveReader.read(exported).artifact("corridor-execution.csv").orElseThrow();
                String csv=new String(first.bytes(),StandardCharsets.UTF_8);
                assertTrue(csv.contains("CANCELLED_AT_FINALIZATION"));assertTrue(csv.contains("FINALIZATION"));
                assertTrue(csv.contains("completed_engine_transitions"));assertTrue(csv.contains("UNAVAILABLE"));
                assertTrue(csv.contains(cancelled.receipt().resultHash()));
                assertFalse(AlignWayAction.closeAndPublishIfCurrent(session,owner,
                    ()->AlignWayAction.recordModernUnavailable("cancelled","visible-layer",identity)),"Monitor fallback cannot overwrite rich evidence");
                assertFalse(AlignWayAction.closeAndPublishIfCurrent(session,owner,
                    ()->AlignWayAction.recordModernUnavailable("cancelled","visible-layer",identity)),"Window fallback cannot overwrite rich evidence");
                delivery.run();DiagnosticsRegistry.writeLatest(exported.toFile());
                assertEquals(first.sha256(),Format15ArchiveReader.read(exported).artifact("corridor-execution.csv").orElseThrow().sha256());
                assertNull(session.currentAttempt().result());assertEquals(0,previews.get());
            }
        }
        assertEquals(networkBefore,fixture.network().canonicalHash(),"Detached cancellation must preserve every source primitive");
    }
}
