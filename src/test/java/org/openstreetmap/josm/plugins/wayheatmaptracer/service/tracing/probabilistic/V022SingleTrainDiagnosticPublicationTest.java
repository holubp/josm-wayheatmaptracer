package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V022SingleTrainDiagnosticPublicationTest {
    @TempDir Path directory;
    @Test void phaseStartIsPersistentBeforeWorkAndExceptionClosureIsFailedNotSuccessful() throws Exception {
        var writer=new BObservationIdentificationTool.Writer(directory,67_108_864);
        try(writer) {
            var progress=new BSingleTrainGeometryDiagnosticTool.Progress(writer);
            progress.begin(BSingleTrainGeometryDiagnosticTool.Phase.ACQUISITION);
            String start=Files.readString(directory.resolve("progress-00.json"));
            assertTrue(start.contains("\"active\":1"));assertTrue(start.contains("\"succeeded\":0"));
            progress.failed();String terminal=BIdentificationReceipt.canonical(progress.snapshot());
            assertTrue(terminal.contains("\"failed\":1"));assertFalse(terminal.contains("\"active\":1"));assertFalse(terminal.contains("\"succeeded\":1"));
            assertEquals(24,BSingleTrainGeometryDiagnosticTool.outputFiles().size());
            assertEquals(24,new HashSet<>(BSingleTrainGeometryDiagnosticTool.outputFiles()).size());
            try(var files=Files.list(directory)){assertEquals(1,files.count());}
        }
        assertEquals(0,writer.metadata.currentBytes());
    }
    @Test void partialPublicationIsConservativelyChargedAndCannotClaimACompletedPhase() throws Exception {
        var writer=new BObservationIdentificationTool.Writer(directory,67_108_864,(path,fields)->{
            Files.writeString(path.resolveSibling(path.getFileName()+".partial"),"prefix");throw new IOException("injected");
        });
        try(writer) {
            var progress=new BSingleTrainGeometryDiagnosticTool.Progress(writer);
            assertThrows(IOException.class,()->progress.begin(BSingleTrainGeometryDiagnosticTool.Phase.SEARCH));
            progress.failed();assertTrue(writer.retainedOrdinaryReservationBytes>0);assertEquals(writer.bytes,writer.retainedOrdinaryReservationBytes);
            assertTrue(BIdentificationReceipt.canonical(progress.snapshot()).contains("\"failed\":1"));
            assertFalse(Files.exists(directory.resolve("progress-03.json")));
            assertTrue(BSingleTrainGeometryDiagnosticTool.outputFiles().stream().noneMatch(p->p.contains("partial")||p.contains("*")));
        }
        assertEquals(0,writer.metadata.currentBytes());
    }
    @Test void unchangedCombinedDiskCeilingRefusesBeforePublicationAndRetainsHonestCharge() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var writer=new BObservationIdentificationTool.Writer(directory,67_108_864,(path,fields)->calls.incrementAndGet());
        try(writer) {
            writer.bytes=2_147_483_648L-8;
            assertThrows(BObservationIdentificationTool.RunLimitException.class,()->writer.publish("bounded.json",Map.of("payload","longer-than-eight-bytes")));
            assertEquals(0,calls.get());assertEquals(2_147_483_648L-8,writer.bytes);assertEquals(0,writer.retainedOrdinaryReservationBytes);
        }
        assertEquals(0,writer.metadata.currentBytes());
    }
    @Test void segmentExportSeparatesFullOriginalAdmissionFromSelectorClipSupport() throws Exception {
        var ledger=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger(100_000);
        var owner=ledger.rootOwner();
        var a=new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(0,0);
        var b=new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(120,0);
        var left=org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion.rectangle(-1,-1,30,1);
        var right=org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion.rectangle(40,-1,121,1);
        var disconnected=new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion(List.of(left.polygons().get(0),right.polygons().get(0)));
        try {
            var cancellation=org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe.NONE;
            var oracle=new BSingleTrainGeometryReachability.Oracle(List.of(List.of(a,b)),.5,owner,cancellation);
            var clipped=BSingleTrainDiagnosticBridge.clip(a,b).orElseThrow();
            assertTrue(disconnected.contains(a));assertTrue(disconnected.contains(b));
            assertFalse(disconnected.containsSegment(a,b));
            assertTrue(disconnected.containsSegment(clipped.get(0),clipped.get(1)),"gap lies outside the selector clip");
            assertTrue(oracle.allowed(a,b),"whole selector clip is supported");
            var fields=BSingleTrainGeometryDiagnosticTool.segmentFields("synthetic",1,a,b,oracle,disconnected,owner,cancellation).orElseThrow();
            assertEquals(true,fields.get("wholeSegmentContained"));
            assertEquals(false,fields.get("originalDecisionWholeEdgeAdmitted"));
            var cell=List.of(new LateralStateCell(0,1,true,true,"fixed"));
            var normal=new org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint(0,1);
            var profiles=List.of(new InferenceProfile(0,a,normal,cell,new double[]{0},List.of(),0,
                    org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership.NO_RASTER,true),
                    new InferenceProfile(120,b,normal,cell,new double[]{0},List.of(),0,
                    org.openstreetmap.josm.plugins.wayheatmaptracer.model.ObservationOwnership.NO_RASTER,true));
            assertEquals(new TransitionAdmissionMemo(8,owner).allowed(0,0,0,profiles,disconnected),
                    fields.get("originalDecisionWholeEdgeAdmitted"),"same original production admission contract");
            assertEquals(true,fields.get("originalDecisionStartContained"));assertEquals(true,fields.get("originalDecisionEndContained"));
            assertEquals("FULL_ORIGINAL_CONSECUTIVE_EDGE",fields.get("originalDecisionScope"));
            assertEquals("SELECTOR_CLIP_X40_80",fields.get("unionContainmentScope"));
            assertEquals(List.of(Map.of("xMeters",0.0,"yMeters",0.0),Map.of("xMeters",120.0,"yMeters",0.0)),fields.get("originalEdge"));
            BIdentificationReceipt.publish(directory.resolve("segments-contract.json"),Map.of("segments",Collections.nCopies(56,fields)));
            assertTrue(Files.size(directory.resolve("segments-contract.json"))<=65_536,"unchanged per-receipt bound covers the fixed56 clips");
            var published=BIdentificationReceipt.parse(Files.readAllBytes(directory.resolve("segments-contract.json")));
            var first=(Map<?,?>)((List<?>)published.get("segments")).get(0);
            assertEquals(false,first.get("originalDecisionWholeEdgeAdmitted"));
            assertEquals("FULL_ORIGINAL_CONSECUTIVE_EDGE",first.get("originalDecisionScope"));
            var connected=org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion.rectangle(-1,-1,121,1);
            assertEquals(true,BSingleTrainGeometryDiagnosticTool.segmentFields("synthetic",1,a,b,oracle,connected,owner,cancellation)
                    .orElseThrow().get("originalDecisionWholeEdgeAdmitted"));
        } finally {owner.close();}
        assertEquals(0,ledger.currentBytes());
    }
}
