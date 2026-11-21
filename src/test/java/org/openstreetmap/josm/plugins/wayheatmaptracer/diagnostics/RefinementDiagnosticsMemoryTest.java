package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

class RefinementDiagnosticsMemoryTest {
    @TempDir Path directory;

    @Test void closedWorkflowReleasesItsInputsButExportSurvivesUntilLastReader() throws Exception {
        var ledger=new AttemptMemoryLedger(4096);
        var memory=new RefinementDiagnosticsMemory(ledger);
        memory.workflowOwner().reserve(300).adopt(new Object());
        Path encoded=directory.resolve("encoded.zip");Files.write(encoded,new byte[200]);
        var admission=memory.reserveExport(1024);
        var snapshot=admission.read(encoded);
        long exported=memory.registryOwnedBytes();
        assertTrue(exported>=200);
        assertEquals(300,memory.workflowOwnedBytes());
        assertEquals(ledger.currentBytes(),memory.workflowOwnedBytes()+memory.registryOwnedBytes());
        var reader=snapshot.pin();
        memory.closeWorkflow();
        assertEquals(0,memory.workflowOwnedBytes());
        assertEquals(exported,ledger.currentBytes());
        snapshot.close(); // Replaced registry entry must not retire bytes pinned by a writer.
        assertEquals(exported,ledger.currentBytes());
        Path written=directory.resolve("written.zip");reader.writeTo(written);
        assertArrayEquals(Files.readAllBytes(encoded),Files.readAllBytes(written));
        reader.close();reader.close();snapshot.close();memory.closeWorkflow();
        assertEquals(0,ledger.currentBytes());
        assertThrows(IllegalStateException.class,snapshot::pin);
    }

    @Test void admissionFailureAndUnusedAdmissionNeverHideOrLeakBytes() throws Exception {
        var ledger=new AttemptMemoryLedger(1024);var memory=new RefinementDiagnosticsMemory(ledger);
        memory.workflowOwner().reserve(900).adopt(new Object());
        assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->memory.reserveExport(200));
        assertEquals(900,memory.workflowOwnedBytes());assertEquals(0,memory.registryOwnedBytes());
        memory.closeWorkflow();assertEquals(0,ledger.currentBytes());
        var second=new RefinementDiagnosticsMemory(new AttemptMemoryLedger(1024));
        try(var unused=second.reserveExport(512)){assertEquals(512,second.workflowOwnedBytes());}
        second.closeWorkflow();assertEquals(0,second.totalBytes());
    }

    @Test void oversizedEncodedResultIsRefusedWithoutRetainingAnUnchargedCopy() throws Exception {
        var memory=new RefinementDiagnosticsMemory(new AttemptMemoryLedger(1024));
        Path encoded=directory.resolve("oversized.zip");Files.write(encoded,new byte[600]);
        try(var admission=memory.reserveExport(512)){
            assertThrows(IllegalArgumentException.class,()->admission.read(encoded));
            assertEquals(0,memory.registryOwnedBytes());assertEquals(512,memory.workflowOwnedBytes());
        }
        memory.closeWorkflow();assertEquals(0,memory.totalBytes());
    }
}
