package org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

class RefinementDiagnosticsRegistryTest {
    @TempDir Path directory;
    @AfterEach void cleanup(){DiagnosticsRegistry.setLastBundle(null);}
    private RefinementDiagnosticsMemory.Snapshot snapshot(RefinementDiagnosticsMemory memory,String name) throws Exception {
        Path file=directory.resolve(name);Files.writeString(file,name);
        try(var admission=memory.reserveExport(512)){return admission.read(file);}
    }
    @Test void lateOldProducerCannotReplaceNewerExportAndRejectedLeaseIsRetired() throws Exception {
        var oldMemory=new RefinementDiagnosticsMemory(new AttemptMemoryLedger(4096));
        var newMemory=new RefinementDiagnosticsMemory(new AttemptMemoryLedger(4096));
        var oldProducer=new Producer();var old=DiagnosticsRegistry.claimRefinement(oldProducer);
        var newerProducer=new Producer();var newer=DiagnosticsRegistry.claimRefinement(newerProducer);
        var accepted=snapshot(newMemory,"current");assertTrue(DiagnosticsRegistry.publishRefinement(newer,accepted,false));
        var rejected=snapshot(oldMemory,"late");assertFalse(DiagnosticsRegistry.publishRefinement(old,rejected,false));
        oldMemory.closeWorkflow();assertEquals(0,oldMemory.totalBytes());
        Path exported=directory.resolve("debug.zip");DiagnosticsRegistry.writeLatest(exported.toFile());
        assertEquals("current",Files.readString(exported));
        newMemory.closeWorkflow();assertTrue(newMemory.registryOwnedBytes()>0);
        DiagnosticsRegistry.setLastBundle(null);assertEquals(0,newMemory.totalBytes());
    }
    @Test void lostLiveOwnerCannotPublishButSealedTerminalExportCanSurviveUiClose() throws Exception {
        var memory=new RefinementDiagnosticsMemory(new AttemptMemoryLedger(4096));
        var producer=new Producer();var owner=DiagnosticsRegistry.claimRefinement(producer);
        producer.current=false;assertFalse(DiagnosticsRegistry.publishRefinement(owner,snapshot(memory,"lost"),false));
        assertEquals(0,memory.registryOwnedBytes());
        producer.current=true;assertTrue(DiagnosticsRegistry.publishRefinement(owner,snapshot(memory,"terminal"),true));
        producer.current=false;memory.closeWorkflow();
        Path exported=directory.resolve("terminal-export.zip");DiagnosticsRegistry.writeLatest(exported.toFile());
        assertEquals("terminal",Files.readString(exported));assertEquals(0,producer.prepares);
        assertFalse(DiagnosticsRegistry.publishRefinement(owner,snapshot(new RefinementDiagnosticsMemory(new AttemptMemoryLedger(4096)),"after-seal"),false));
        DiagnosticsRegistry.setLastBundle(null);assertEquals(0,memory.totalBytes());
    }
    @Test void reentrantNewOwnerDuringPreparationWinsWithoutOldOverwrite() throws Exception {
        var memory=new RefinementDiagnosticsMemory(new AttemptMemoryLedger(4096));
        var producer=new Producer();var owner=DiagnosticsRegistry.claimRefinement(producer);
        var replacement=new Producer();
        producer.prepare=()->{DiagnosticsRegistry.claimRefinement(replacement);try{assertFalse(DiagnosticsRegistry.publishRefinement(owner,snapshot(memory,"old-callback"),false));}catch(Exception e){throw new RuntimeException(e);}};
        assertThrows(IllegalStateException.class,()->DiagnosticsRegistry.writeLatest(directory.resolve("none.zip").toFile()));
        assertEquals(1,producer.prepares);memory.closeWorkflow();assertEquals(0,memory.totalBytes());
    }
    @Test void repeatedOldPluginUnloadCannotClearTheNewPluginExport() throws Exception {
        var oldPlugin=DiagnosticsRegistry.beginPlugin();
        var oldMemory=new RefinementDiagnosticsMemory(new AttemptMemoryLedger(4096));
        var producer=new Producer();var oldOwner=DiagnosticsRegistry.claimRefinement(producer);
        assertTrue(DiagnosticsRegistry.publishRefinement(oldOwner,snapshot(oldMemory,"old-plugin"),true));oldMemory.closeWorkflow();
        var nextPlugin=DiagnosticsRegistry.beginPlugin();assertEquals(0,oldMemory.totalBytes());
        var nextMemory=new RefinementDiagnosticsMemory(new AttemptMemoryLedger(4096));
        var nextProducer=new Producer();var nextOwner=DiagnosticsRegistry.claimRefinement(nextProducer);
        assertTrue(DiagnosticsRegistry.publishRefinement(nextOwner,snapshot(nextMemory,"new-plugin"),true));nextMemory.closeWorkflow();
        DiagnosticsRegistry.clearPlugin(oldPlugin);DiagnosticsRegistry.clearPlugin(oldPlugin);
        Path exported=directory.resolve("new-plugin.zip");DiagnosticsRegistry.writeLatest(exported.toFile());assertEquals("new-plugin",Files.readString(exported));
        assertTrue(nextMemory.registryOwnedBytes()>0);
        DiagnosticsRegistry.clearPlugin(nextPlugin);DiagnosticsRegistry.clearPlugin(nextPlugin);assertEquals(0,nextMemory.totalBytes());assertFalse(DiagnosticsRegistry.hasLatest());
    }
    @Test void terminalBudgetRefusalRetiresOldPreviewAndRemainsHonestAfterUiRelease() throws Exception {
        var memory=new RefinementDiagnosticsMemory(new AttemptMemoryLedger(4096));var producer=new Producer();var owner=DiagnosticsRegistry.claimRefinement(producer);
        assertTrue(DiagnosticsRegistry.publishRefinement(owner,snapshot(memory,"old-preview"),false));
        assertTrue(DiagnosticsRegistry.sealRefinementUnavailable(owner,DiagnosticsRegistry.RefinementUnavailable.BUDGET));
        producer.current=false;memory.closeWorkflow();assertEquals(0,memory.totalBytes());assertTrue(DiagnosticsRegistry.hasLatest());
        var failure=assertThrows(IllegalStateException.class,()->DiagnosticsRegistry.writeLatest(directory.resolve("refused.zip").toFile()));
        assertEquals("Refinement diagnostics unavailable: memory budget reached",failure.getMessage());
        DiagnosticsRegistry.setLastBundle(null);assertFalse(DiagnosticsRegistry.hasLatest());
    }
    static final class Producer implements DiagnosticsRegistry.RefinementProducer {
        boolean current=true;int prepares;Runnable prepare=()->{};
        public boolean isCurrent(){return current;}
        public void prepareExport(){prepares++;prepare.run();}
    }
}
