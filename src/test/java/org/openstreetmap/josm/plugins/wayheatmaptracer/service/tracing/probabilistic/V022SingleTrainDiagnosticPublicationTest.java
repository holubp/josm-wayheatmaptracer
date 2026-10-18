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
}
