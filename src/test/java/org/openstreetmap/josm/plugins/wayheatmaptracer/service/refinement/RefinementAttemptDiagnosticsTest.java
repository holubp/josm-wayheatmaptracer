package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class RefinementAttemptDiagnosticsTest {
    @Test void actualCopyCompositionPlanRefusalKeepsItsPipelineReceipt() {
        var parent=ParentBackedComposerTest.copyParent();var root=parent.root();var ticket=RefinementPipelineTest.ticket(parent,TrackerMode.PROBABILISTIC);
        try(var owner=AttemptMemoryLedger.production().rootOwner();var slot=RefinementAttemptDiagnostics.Slot.open(owner,64L*1024*1024).orElseThrow()) {
            var result=new RefinementPipeline().run(ticket,parent,RefinementPipelineTest.plan(ticket,parent,false),
                new RefinementPipeline.Adapters(RefinementPipelineTest.sources(parent),RefinementPipeline.productionRunner(),owner),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
            assertFalse(result.candidates().isEmpty());assertFalse(result.candidates().get(0).assessed().copies().isEmpty());
            var binding=new RefinementAttemptDiagnostics.Binding(ticket,parent.parentStage(),root.sourceIdentity(),root.network().canonicalHash(),root.request().parameterHash(),root.frozenGeometryIdentity(),parent.canonicalIdentity());
            long pinned=owner.currentBytes();
            try(var tooSmall=RefinementAttemptDiagnostics.Slot.open(owner,32768).orElseThrow()) {
                assertTrue(tooSmall.receipt(binding,parent,result).isEmpty());assertTrue(tooSmall.unavailable());
                assertEquals(pinned+8192,owner.currentBytes());
            }
            assertEquals(pinned,owner.currentBytes(),"Metadata refusal cannot settle/release actual pipeline inputs");
            var progress=new LegacyRefinementWorkflow.AttemptProgress(binding,slot);progress.receipt=slot.receipt(binding,parent,result);progress.pipelineOutcome=result.outcome();
            assertTrue(progress.receipt.isPresent());
            var evidence=new HashMap<RefinementSourcePlanner.SourceRequest,EvidenceSnapshot>();for(var candidate:result.candidates())for(var source:candidate.sources())evidence.put(source,root.evidence());
            var original=new AlignmentResult(null,null,List.of(),List.of(),List.of(),List.of(),null,null,List.of(),List.of());
            var materialized=LegacyRefinementWorkflow.materialize(ticket,parent,result,evidence,RefinementPayload.capture(original,1024*1024),original,1024*1024,CancellationProbe.NONE,progress);
            assertTrue(materialized.stage().isEmpty());assertEquals(RefinementAttemptDiagnostics.Cause.PLAN_REFUSED,materialized.cause());assertTrue(progress.materializationRefusals>0);
            var diagnostic=progress.snapshot(RefinementAttemptDiagnostics.Publication.NO_CHILD,materialized.cause());assertTrue(slot.retain(diagnostic));
            assertEquals(result.outcome(),diagnostic.pipeline().orElseThrow().outcome());assertTrue(diagnostic.pipeline().orElseThrow().attempts().stream().anyMatch(a->a.nativeCalls().orElse(0)>0));
        }
    }
    @Test void refusedMetadataBudgetDoesNotReleaseOrReplacePinnedInputs() {
        var ledger=new AttemptMemoryLedger(128*1024);
        try(var root=ledger.rootOwner();var input=root.child("actual-worker-input")) {
            input.reserve(120*1024);
            assertTrue(RefinementAttemptDiagnostics.Slot.open(root,64L*1024*1024).isEmpty());
            assertEquals(120*1024,ledger.currentBytes());assertFalse(input.isClosed());
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void onlyLatestSlotIsChargedAndNoPipelineMeansUnavailableWork() {
        var ledger=new AttemptMemoryLedger(128*1024);
        try(var root=ledger.rootOwner()) {
            var slot=RefinementAttemptDiagnostics.Slot.open(root,64L*1024*1024).orElseThrow();
            var ticket=new OperationTicket(new SessionId(UUID.randomUUID()),1,new StageId(UUID.randomUUID()),new CandidateId("selected-parent"),TrackerMode.PROBABILISTIC,AlignmentMode.MOVE_EXISTING_NODES);
            var binding=new RefinementAttemptDiagnostics.Binding(ticket,ticket.parent(),"managed-original","network","settings","frozen","selected-parent-identity");
            var diagnostic=new RefinementAttemptDiagnostics(binding,Optional.empty(),RefinementAttemptDiagnostics.Publication.FAILED,RefinementAttemptDiagnostics.Phase.ACQUISITION,RefinementAttemptDiagnostics.Cause.FAILED,0);
            assertTrue(slot.retain(diagnostic));assertEquals(65536,ledger.currentBytes());assertTrue(diagnostic.pipeline().isEmpty());
            slot.close();assertEquals(0,ledger.currentBytes());
            try(var next=RefinementAttemptDiagnostics.Slot.open(root,64L*1024*1024).orElseThrow()){assertEquals(65536,ledger.currentBytes());}
            assertEquals(0,ledger.currentBytes());
        }
    }
    @Test void mismatchedParentReceiptCannotBeRelabelledAsCurrentAttempt() {
        var ticket=new OperationTicket(new SessionId(UUID.randomUUID()),1,new StageId(UUID.randomUUID()),new CandidateId("selected-parent"),TrackerMode.PROBABILISTIC,AlignmentMode.MOVE_EXISTING_NODES);
        var binding=new RefinementAttemptDiagnostics.Binding(ticket,ticket.parent(),"managed-original","network","settings","frozen","selected-parent-identity");
        var other=new RefinementOperationReceipt(ticket,"another-parent","network",Outcome.NO_USABLE_RESULT,List.of(),Optional.empty(),false);
        assertThrows(IllegalArgumentException.class,()->new RefinementAttemptDiagnostics(binding,Optional.of(other),RefinementAttemptDiagnostics.Publication.NO_CHILD,RefinementAttemptDiagnostics.Phase.PIPELINE,RefinementAttemptDiagnostics.Cause.NO_USABLE_RESULT,0));
    }
}
