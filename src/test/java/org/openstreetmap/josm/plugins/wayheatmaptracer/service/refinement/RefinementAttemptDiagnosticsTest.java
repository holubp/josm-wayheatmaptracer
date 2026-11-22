package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class RefinementAttemptDiagnosticsTest {
    @Test void sourceFactsRequireExactUtf8Sha256Binding() {
        var ticket=new OperationTicket(new SessionId(UUID.randomUUID()),1,new StageId(UUID.randomUUID()),new CandidateId("parent"),TrackerMode.PROBABILISTIC,AlignmentMode.MOVE_EXISTING_NODES);
        String hash="12ea3684bf5746acdebbfc2de080fbbe8afefec7130a8b32f43b0b9504962d63";
        var binding=new RefinementAttemptDiagnostics.Binding(ticket,ticket.parent(),"source-Žluťoučký-東京","network","settings","frozen","parent");
        var counters=new EnumMap<org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind,Long>(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.class);
        for(var kind:org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.values())counters.put(kind,0L);
        java.util.function.BiFunction<OperationTicket,String,RefinementAttemptDiagnostics> diagnostic=(factTicket,sourceHash)->{
            var facts=new RefinementAttemptDiagnostics.SourceFacts(factTicket,sourceHash,RefinementAttemptDiagnostics.SourceKind.VISIBLE,RefinementAttemptDiagnostics.Coverage.COVERED,hash,hash,hash,RefinementAttemptDiagnostics.InputFrameRole.ORIGINAL_INPUT,2,3,4,5,0,OptionalLong.empty());
            var work=new RefinementAttemptDiagnostics.SourcePreparationWork(RefinementAttemptDiagnostics.Cause.NONE,0,counters,Optional.empty(),Optional.empty(),Optional.of(facts));
            return new RefinementAttemptDiagnostics(binding,Optional.empty(),RefinementAttemptDiagnostics.Publication.NO_CHILD,RefinementAttemptDiagnostics.Phase.SOURCE_PREPARATION,RefinementAttemptDiagnostics.Cause.NONE,0,true,Optional.of(work));
        };
        assertEquals(hash,diagnostic.apply(ticket,hash).sourcePreparation().orElseThrow().sourceFacts().orElseThrow().sourceIdentityHash());
        assertThrows(IllegalArgumentException.class,()->diagnostic.apply(ticket,"0".repeat(64)));
        var foreign=new OperationTicket(ticket.session(),2,ticket.parent(),ticket.choice(),ticket.engine(),ticket.mode());
        assertThrows(IllegalArgumentException.class,()->diagnostic.apply(foreign,hash));
    }
    @Test void oneByteShortPublicationAdmissionKeepsActualAssessedReceiptAndInputs() {
        withAssessedPublication((parent,ticket,result,evidence,payload,original,progress,input,publication)->{
            long allowance=1024*1024, pinned=input.currentBytes();
            try(var competitor=input.child("competing-live-input")) {
                competitor.reserve(AttemptMemoryLedger.MAX_BYTES-pinned-allowance+1);
                var failure=assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->LegacyRefinementWorkflow.materialize(
                    ticket,parent,result,evidence,payload,original,allowance,CancellationProbe.NONE,progress,publication));
                assertEquals(allowance,failure.requestedBytes());assertEquals(AttemptMemoryLedger.MAX_BYTES-allowance+1,failure.currentBytes());
                assertEquals(RefinementAttemptDiagnostics.Phase.MATERIALIZATION,progress.phase);
                assertEquals(result.outcome(),progress.pipelineOutcome);assertEquals(0,progress.materializationRefusals);
                assertEquals(0,reserved(publication),"Denied capacity must not allocate or retain a child");
                var diagnostic=progress.snapshot(RefinementAttemptDiagnostics.Publication.FAILED,RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT);
                assertEquals(result.outcome(),diagnostic.pipeline().orElseThrow().outcome());
                assertTrue(diagnostic.pipeline().orElseThrow().attempts().stream().anyMatch(a->a.nativeCalls().orElse(0)>0));
            }
            assertEquals(pinned,input.currentBytes(),"Admission refusal cannot release actual source/native/result pins");
        });
    }
    @Test void sameFullPublicationBoundPrecedesCopyAndRemainsPinnedOnCancelOrException() {
        withAssessedPublication((parent,ticket,result,evidence,payload,original,progress,input,publication)->{
            long allowance=1024*1024,pinned=input.currentBytes();
            for(boolean cancel:List.of(true,false)) {
                var output=publication.child("copy-attempt");var entered=new java.util.concurrent.atomic.AtomicBoolean();
                CancellationProbe probe=()->{
                    if(reserved(output)==0)return false; // the pre-admission cancellation checkpoint
                    assertEquals(allowance,reserved(output),"Full capacity must precede the first edit-plan/copy allocation");
                    assertEquals(pinned+allowance,input.currentBytes());entered.set(true);
                    if(!cancel)throw new IllegalStateException("synthetic materialization failure");return true;
                };
                if(cancel)assertThrows(java.util.concurrent.CancellationException.class,()->LegacyRefinementWorkflow.materialize(ticket,parent,result,evidence,payload,original,allowance,probe,progress,output));
                else assertThrows(IllegalStateException.class,()->LegacyRefinementWorkflow.materialize(ticket,parent,result,evidence,payload,original,allowance,probe,progress,output));
                assertTrue(entered.get());assertEquals(allowance,reserved(output),"A failure cannot settle a still-owned physical result early");
                output.close();assertEquals(pinned,input.currentBytes());
            }
        });
    }
    @Test void emptyNativeResourceResultSkipsFutureStageAdmissionAndKeepsPipelinePhase() {
        withAssessedPublication((parent,ticket,result,evidence,payload,original,progress,input,publication)->{
            var empty=new RefinementPipeline.Result(Outcome.RESOURCE_LIMIT,List.of(),result.attempts(),result.actualWork(),true,List.of(),result.actualSourceWork(),result.sourceOutcomes());
            progress.pipelineOutcome=empty.outcome();progress.receipt=Optional.of(RefinementOperationReceipt.capture(ticket,parent,empty));
            try(var competitor=input.child("all-remaining-capacity")) {
                competitor.reserve(AttemptMemoryLedger.MAX_BYTES-input.currentBytes());
                var computed=LegacyRefinementWorkflow.materialize(ticket,parent,empty,evidence,payload,original,1024*1024,CancellationProbe.NONE,progress,publication);
                assertTrue(computed.stage().isEmpty());assertTrue(computed.publicationAllowance().isEmpty());assertEquals(0,reserved(publication));
                assertEquals(RefinementAttemptDiagnostics.Cause.RESOURCE_LIMIT,computed.cause());assertEquals(RefinementAttemptDiagnostics.Phase.PIPELINE,progress.phase);
                assertEquals(Outcome.RESOURCE_LIMIT,progress.receipt.orElseThrow().outcome());
            }
        });
    }
    @Test void retainedAssessedCandidatesStillRequireFullAdmissionWhenPipelineIsResourceLimited() {
        withAssessedPublication((parent,ticket,result,evidence,payload,original,progress,input,publication)->{
            var partial=new RefinementPipeline.Result(Outcome.RESOURCE_LIMIT,result.candidates(),result.attempts(),result.actualWork(),true,result.rejected(),result.actualSourceWork(),result.sourceOutcomes());
            long allowance=1024*1024,pinned=input.currentBytes();
            var computed=LegacyRefinementWorkflow.materialize(ticket,parent,partial,evidence,payload,original,allowance,CancellationProbe.NONE,progress,publication);
            assertEquals(allowance,computed.publicationAllowance().orElseThrow().bytes());
            assertEquals(pinned+allowance,input.currentBytes());assertEquals(RefinementAttemptDiagnostics.Phase.MATERIALIZATION,progress.phase);
        });
    }
    private interface PublicationCase {
        void run(ParentTraceReference parent,OperationTicket ticket,RefinementPipeline.Result result,
            Map<RefinementSourcePlanner.SourceRequest,EvidenceSnapshot> evidence,RefinementPayload payload,AlignmentResult original,
            LegacyRefinementWorkflow.AttemptProgress progress,AttemptMemoryLedger.Owner input,AttemptMemoryLedger.Owner publication);
    }
    private static long reserved(AttemptMemoryLedger.Owner owner) {
        return ((Set<?>)get(owner,"reservations")).stream().map(AttemptMemoryLedger.Reservation.class::cast).mapToLong(AttemptMemoryLedger.Reservation::bytes).sum();
    }
    private static void withAssessedPublication(PublicationCase check) {
        var parent=ParentBackedComposerTest.copyParent();var root=parent.root();var ticket=RefinementPipelineTest.ticket(parent,TrackerMode.PROBABILISTIC);
        var ledger=AttemptMemoryLedger.production();
        try(var input=ledger.rootOwner();var slot=RefinementAttemptDiagnostics.Slot.open(input,64L*1024*1024).orElseThrow();var publication=input.child("future-publication")) {
            var result=new RefinementPipeline().run(ticket,parent,RefinementPipelineTest.plan(ticket,parent,false),
                new RefinementPipeline.Adapters(RefinementPipelineTest.sources(parent),RefinementPipeline.productionRunner(),input),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
            assertFalse(result.candidates().isEmpty(),"Admission tests require actual production assessed native output");
            var binding=new RefinementAttemptDiagnostics.Binding(ticket,parent.parentStage(),root.sourceIdentity(),root.network().canonicalHash(),root.request().parameterHash(),root.frozenGeometryIdentity(),parent.canonicalIdentity());
            var progress=new LegacyRefinementWorkflow.AttemptProgress(binding,slot);progress.receipt=slot.receipt(binding,parent,result);progress.pipelineOutcome=result.outcome();progress.phase=RefinementAttemptDiagnostics.Phase.PIPELINE;
            assertTrue(progress.receipt.isPresent());var evidence=new HashMap<RefinementSourcePlanner.SourceRequest,EvidenceSnapshot>();
            for(var candidate:result.candidates())for(var source:candidate.sources())evidence.put(source,root.evidence());
            var original=new AlignmentResult(null,null,List.of(),List.of(),List.of(),List.of(),null,null,List.of(),List.of());
            check.run(parent,ticket,result,evidence,RefinementPayload.capture(original,1024*1024),original,progress,input,publication);
        }
        assertEquals(0,ledger.currentBytes(),"Physical owner settlement must release every reservation and lease");
    }
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
    @Test void heldActualCopyRefusalCloseKeepsStableCountReceiptAndPinnedInputsInStrictZip() throws Exception {
        var source=ParentBackedComposerTest.copyParent();var root=source.root();var choice=new CandidateId("legacy-choice-0");
        var parent=new ParentTraceReference(root,source.parentStage(),choice,source.nativeCandidateId(),source.parentPalette(),source.parentIdentity(),source.points(),source.correspondence());
        var workflow=new LegacyRefinementWorkflow[1];var review=new LegacyReviewOwner[1];var history=new RefinementSession[1];var ticket=new OperationTicket[1];
        var memory=new AttemptMemoryLedger.Owner[1];var input=new AttemptMemoryLedger.Owner[1];var work=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.PreviewWorkGate.Work[1];
        var entry=new LegacyRefinementWorkflow.NativeEntry[1];var original=new AlignmentResult[1];var projection=org.openstreetmap.josm.data.projection.Projections.getProjectionByCode(root.projectionCode());
        var publication=new java.util.concurrent.LinkedBlockingQueue<Runnable>();var gate=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.PreviewWorkGate[1];
        java.nio.file.Path file=java.nio.file.Files.createTempFile("held-copy-refusals-",".zip");
        try {
            javax.swing.SwingUtilities.invokeAndWait(()->{
                org.openstreetmap.josm.data.projection.ProjectionRegistry.setProjection(projection);
                var selection=RefinementParentFixtures.selection(root);var data=new org.openstreetmap.josm.data.osm.DataSet();selection.segmentNodes().forEach(data::addPrimitive);data.addPrimitive(selection.way());data.allPrimitives().forEach(p->p.setModified(false));
                var points=parent.points().stream().map(p->{var g=root.evidence().coordinateFrame().toGeographic(p);return projection.latlon2eastNorth(new org.openstreetmap.josm.data.coor.LatLon(g.latitudeDegrees(),g.longitudeDegrees()));}).toList();
                var candidate=new CenterlineCandidate(parent.nativeCandidateId(),1,List.of(),List.of()).withFinalPreviewPoints(points);
                original[0]=new AlignmentResult(selection,null,List.of(candidate),selection.segmentNodes().stream().map(org.openstreetmap.josm.data.osm.Node::getEastNorth).toList(),points,List.of(),null,null);
                // Synthetic Legacy UI shell; the native copy composition and refusal below execute production code.
                review[0]=LegacyReviewOwner.begin(data,selection,root.effectiveConfig());review[0].captured(original[0],null,null);review[0].select(candidate);
                workflow[0]=new LegacyRefinementWorkflow(review[0],null,review[0]::select,display->{},status->{},Runnable::run,publication::add);workflow[0].enableRuntimeDiagnostics(()->null);
                var stage=new Stage(parent.parentStage(),Optional.empty(),Optional.empty(),StageKind.ROOT_LEGACY,TrackerMode.LEGACY_V02,List.of(new Candidate(choice,RefinementPayload.capture(original[0],1024*1024),List.of(),parent.parentIdentity(),parent.nativeCandidateId(),parent.parentPalette())),choice,Optional.empty());
                history[0]=new RefinementSession(stage,root.effectiveConfig(),root.sourceIdentity(),root,RefinementLimits.defaults());
                set(workflow[0],"session",history[0]);set(workflow[0],"root",new org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyRefinementBootstrap.Root(root,stage,null));invoke(workflow[0],"startDiagnostics");
                ticket[0]=history[0].begin(TrackerMode.PROBABILISTIC);history[0].reserve(ticket[0],32,history[0].availablePayloadBytes());set(workflow[0],"active",ticket[0]);set(workflow[0],"busy",true);
                memory[0]=(AttemptMemoryLedger.Owner)get(workflow[0],"rootMemory");input[0]=memory[0].child("held-copy-worker-input");input[0].reserve(root.retainedBytes()).adopt(root);
                gate[0]=(org.openstreetmap.josm.plugins.wayheatmaptracer.service.PreviewWorkGate)get(workflow[0],"workGate");work[0]=gate[0].beginWork(workflow[0]);set(workflow[0],"activeWork",work[0]);
                try{var constructor=LegacyRefinementWorkflow.NativeEntry.class.getDeclaredConstructor(OperationTicket.class);constructor.setAccessible(true);entry[0]=constructor.newInstance(ticket[0]);set(workflow[0],"activeEntry",entry[0]);}catch(Exception failure){throw new RuntimeException(failure);}
            });
            var result=new RefinementPipeline().run(ticket[0],parent,RefinementPipelineTest.plan(ticket[0],parent,false),new RefinementPipeline.Adapters(RefinementPipelineTest.sources(parent),RefinementPipeline.productionRunner(),input[0]),RefinementPipeline.OperationBudget.production(TraceBudgets.defaults()),CancellationProbe.NONE);
            assertTrue(result.candidates().stream().anyMatch(c->!c.assessed().copies().isEmpty()));
            var binding=new RefinementAttemptDiagnostics.Binding(ticket[0],parent.parentStage(),root.sourceIdentity(),root.network().canonicalHash(),org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.RuntimeRefinementDiagnostics.frozenSettingsIdentity(root),root.frozenGeometryIdentity(),parent.canonicalIdentity());
            var slot=RefinementAttemptDiagnostics.Slot.open(memory[0],64L*1024*1024).orElseThrow();var progress=new LegacyRefinementWorkflow.AttemptProgress(binding,slot);progress.receipt=slot.receipt(binding,parent,result);progress.pipelineOutcome=result.outcome();assertTrue(progress.receipt.isPresent());
            var evidence=new HashMap<RefinementSourcePlanner.SourceRequest,EvidenceSnapshot>();for(var c:result.candidates())for(var request:c.sources())evidence.put(request,root.evidence());
            var computed=LegacyRefinementWorkflow.materialize(ticket[0],parent,result,evidence,RefinementPayload.capture(original[0],1024*1024),original[0],1024*1024,CancellationProbe.NONE,progress);assertTrue(computed.stage().isEmpty());assertTrue(progress.materializationRefusals>0);int expected=progress.materializationRefusals;
            javax.swing.SwingUtilities.invokeAndWait(()->{
                set(workflow[0],"activeProgress",progress);set(entry[0],"workerReturned",true);workflow[0].close();assertTrue(workflow[0].workflowOwnedBytes()>0);assertFalse(gate[0].settlement().toCompletableFuture().isDone());
                publication.add(()->{history[0].workerSettled(ticket[0]);set(workflow[0],"busy",false);invoke(workflow[0],"releaseClosed");gate[0].workerSettled(work[0]);((java.util.concurrent.CompletableFuture<?>)get(entry[0],"settlement")).complete(null);});
            });
            org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry.writeLatest(file.toFile());
            var archive=org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.Format15ArchiveReader.read(file);
            var diagnostic=org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.RefinementLineageCodec.read(archive.artifact("refinement-lineage.json").orElseThrow()).latestPublication().orElseThrow().diagnostic();
            assertEquals(expected,diagnostic.materializationRefusals(),"A held returned result must not turn observed refusals into zero");assertTrue(diagnostic.materializationComplete());assertEquals(progress.receipt,diagnostic.pipeline());assertEquals(RefinementAttemptDiagnostics.Publication.CANCELLED,diagnostic.publication());assertEquals(result.outcome(),diagnostic.pipeline().orElseThrow().outcome());
            javax.swing.SwingUtilities.invokeAndWait(publication.remove());assertEquals(0,workflow[0].workflowOwnedBytes());assertTrue(workflow[0].registryOwnedBytes()>0);assertTrue(gate[0].settlement().toCompletableFuture().isDone());assertTrue(entry[0].settlement().toCompletableFuture().isDone());
        }finally{
            javax.swing.SwingUtilities.invokeAndWait(()->{while(!publication.isEmpty())publication.remove().run();if(workflow[0]!=null){workflow[0].close();if(workflow[0].busy()){history[0].workerSettled(ticket[0]);invoke(workflow[0],"releaseClosed");gate[0].workerSettled(work[0]);}}if(review[0]!=null)review[0].close();org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.DiagnosticsRegistry.setLastBundle(null);});java.nio.file.Files.deleteIfExists(file);
        }
        assertEquals(0,workflow[0].ownedBytes());
    }
    private static Object get(Object target,String name){try{var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}catch(Exception failure){throw new RuntimeException(failure);}}
    private static void set(Object target,String name,Object value){try{var field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}catch(Exception failure){throw new RuntimeException(failure);}}
    private static void invoke(Object target,String name){try{var method=target.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(target);}catch(Exception failure){throw new RuntimeException(failure);}}
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
