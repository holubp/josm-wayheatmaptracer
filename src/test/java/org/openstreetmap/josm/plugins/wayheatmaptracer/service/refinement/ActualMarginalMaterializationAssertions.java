package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import javax.swing.SwingUtilities;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LegacyRefinementBootstrap;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;

/** Two unit admission containers over one actual assessed B result, not two native outputs or executions. */
public final class ActualMarginalMaterializationAssertions {
    private ActualMarginalMaterializationAssertions(){}
    public static void verify(LegacyRefinementWorkflow workflow,RefinementPipeline.Result actual) throws Exception {
        var session=(RefinementSession)get(workflow,"session");var root=(LegacyRefinementBootstrap.Root)get(workflow,"root");
        var progress=(LegacyRefinementWorkflow.AttemptProgress)get(workflow,"activeProgress");var ticket=(OperationTicket)get(workflow,"active");
        var parentStage=new Stage[1];var originals=new AlignmentResult[1];SwingUtilities.invokeAndWait(()->{parentStage[0]=session.current();originals[0]=((LegacyReviewOwner)get(workflow,"review")).originalResult();});
        var choice=parentStage[0].candidates().stream().filter(c->c.id().equals(parentStage[0].selected())).findFirst().orElseThrow();
        var parent=new ParentReferenceBinder().bind(root.authority(),parentStage[0].id(),choice);
        var original=originals[0];assertFalse(actual.candidates().isEmpty());
        var first=actual.candidates().get(0);var second=first;
        System.out.println("marginal-unit-admission-containers native-result="+first.assessed().candidate().id()+" native-executions=unchanged");
        var pair=new RefinementPipeline.Result(actual.outcome(),List.of(first,second),actual.attempts(),actual.actualWork(),actual.truncated(),actual.rejected(),actual.actualSourceWork(),actual.sourceOutcomes());
        var evidence=new HashMap<RefinementSourcePlanner.SourceRequest,EvidenceSnapshot>();for(var retained:pair.candidates())retained.retainedSources().forEach((source,value)->evidence.put(source,value.evidence()));
        try(var owner=((AttemptMemoryLedger.Owner)get(workflow,"rootMemory")).child("marginal-test-workspace");var scratch=owner.reserve(8L*1024*1024)) {
            var baseline=new ArrayList<Map<Object,Long>>();SwingUtilities.invokeAndWait(()->baseline.addAll(session.admissionInventories()));
            var factualReceipt=RefinementOperationReceipt.capture(ticket,parent,actual);assertEquals(actual.actualWork(),factualReceipt.actualWork());
            var large=materialize(ticket,parent,pair,evidence,choice.payload(),original,64L*1024*1024,progress,baseline,factualReceipt).stage().orElseThrow();assertEquals(2,large.candidates().size());
            var one=large.candidates().get(0);var two=large.candidates().get(1);assertNotEquals(one.id(),two.id());assertNotSame(one.payload(),two.payload());
            assertEquals(one.nativeCandidateId(),two.nativeCandidateId());assertEquals(one.payload().finalPreviewGeometry(),two.payload().finalPreviewGeometry());
            var r1=one.payload().nativeReview().orElseThrow();var r2=two.payload().nativeReview().orElseThrow();assertSame(r1.quality(),r2.quality());assertEquals(r1.sourceSpans(),r2.sourceSpans());assertEquals(r1.operation(),r2.operation());assertSame(factualReceipt,r1.operation().orElseThrow());
            for(var source:r1.sources()){assertSame(r1.retainedSources().get(source),r2.retainedSources().get(source));assertSame(r1.retainedSources().get(source).evidence(),r2.retainedSources().get(source).evidence());}
            long sum=large.candidates().stream().mapToLong(c->c.payload().retainedBytes()).sum();long union=additional(session,large);
            long allowance=union+(sum-union)/2;assertTrue(union<allowance&&allowance<sum,"Tight bound must distinguish union from summed groups");
            var tight=assertDoesNotThrow(()->materialize(ticket,parent,pair,evidence,choice.payload(),original,allowance,progress,baseline,factualReceipt)).stage().orElseThrow();
            assertEquals(2,tight.candidates().size());assertTrue(additional(session,tight)<=allowance,"Session's exact history/input-pin identity union must fit the same bound");
            System.out.println("marginal-unit-containers sum="+sum+" union="+union+" allowance="+allowance+" measured="+additional(session,tight));
            var groups=new IdentityHashMap<Object,Long>();r1.retainedSources().values().forEach(source->source.charges().forEach((key,bytes)->groups.merge(key,bytes,Math::max)));
            long groupBytes=groups.values().stream().mapToLong(Long::longValue).sum();assertEquals(groupBytes,new MarginalPayloadAdmission(baseline,64L*1024*1024,CancellationProbe.NONE).require(groups,0));
            var model=new RefinementSession[1];var modelTicket=new OperationTicket[1];
            SwingUtilities.invokeAndWait(()->{model[0]=new RefinementSession(parentStage[0],root.authority().effectiveConfig(),root.authority().sourceIdentity(),root.authority(),RefinementLimits.defaults());long before=model[0].retainedBytes();modelTicket[0]=model[0].begin(ticket.engine());model[0].reserve(modelTicket[0],2,allowance);var child=Stage.child(modelTicket[0],tight.candidates(),tight.selected());assertTrue(model[0].publish(modelTicket[0],child));assertTrue(model[0].retainedBytes()-before<=allowance);model[0].workerSettled(modelTicket[0]);modelTicket[0]=model[0].begin(ticket.engine());});
            try {
                var history=new ArrayList<Map<Object,Long>>();SwingUtilities.invokeAndWait(()->history.addAll(model[0].admissionInventories()));
                assertEquals(0,new MarginalPayloadAdmission(history,64L*1024*1024,CancellationProbe.NONE).require(groups,0),"Same factual acquired groups already in logical history add zero bytes");
                var historyLarge=materialize(ticket,parent,pair,evidence,choice.payload(),original,64L*1024*1024,progress,history,factualReceipt).stage().orElseThrow();
                var historyStage=Stage.child(modelTicket[0],historyLarge.candidates(),historyLarge.selected());long historyUnion=additional(model[0],historyStage);long historyAllowance=historyUnion+4096;
                SwingUtilities.invokeAndWait(()->model[0].reserve(modelTicket[0],2,historyAllowance));
                var historyTight=materialize(ticket,parent,pair,evidence,choice.payload(),original,historyAllowance,progress,history,factualReceipt).stage().orElseThrow();
                SwingUtilities.invokeAndWait(()->{long before=model[0].retainedBytes();assertTrue(model[0].publish(modelTicket[0],Stage.child(modelTicket[0],historyTight.candidates(),historyTight.selected())));assertTrue(model[0].retainedBytes()-before<=historyAllowance);model[0].workerSettled(modelTicket[0]);});
                for(var source:r1.retainedSources().values()){source.requireCurrent();owner.retainIfAbsent(source.evidence());}assertTrue(owner.currentBytes()>0,"Logical history admission never settles the actual acquired physical groups");history.clear();
                System.out.println("marginal-unit-history group="+groupBytes+" additional-group=0 metadata-delta="+historyUnion+" allowance="+historyAllowance);
            } finally {SwingUtilities.invokeAndWait(()->model[0].close());}
            baseline.clear();
        }
    }
    private static LegacyRefinementWorkflow.Computed materialize(OperationTicket ticket,ParentTraceReference parent,RefinementPipeline.Result result,Map<RefinementSourcePlanner.SourceRequest,EvidenceSnapshot> evidence,RefinementPayload payload,AlignmentResult original,long allowance,LegacyRefinementWorkflow.AttemptProgress actual,List<Map<Object,Long>> baseline,RefinementOperationReceipt receipt) {
        var progress=new LegacyRefinementWorkflow.AttemptProgress(actual.binding,null);progress.receipt=Optional.of(receipt);progress.pipelineOutcome=result.outcome();
        return LegacyRefinementWorkflow.materialize(ticket,parent,result,evidence,payload,original,allowance,CancellationProbe.NONE,progress,baseline);
    }
    private static long additional(RefinementSession session,Stage child) throws Exception {
        var result=new long[1];SwingUtilities.invokeAndWait(()->{try{var stages=new ArrayList<>(session.history());stages.add(child);var method=RefinementSession.class.getDeclaredMethod("retainedBytesWithPins",List.class);method.setAccessible(true);result[0]=(long)method.invoke(session,stages)-session.retainedBytes();}catch(Exception failure){throw new AssertionError(failure);}});return result[0];
    }
    private static Object get(Object target,String name){try{var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}catch(Exception failure){throw new AssertionError(failure);}}
}
