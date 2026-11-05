package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Existing retained-raster stage authority is reused; affine input honestly has no managed chart. */
class ManagedGroupedStagesTest {
    @Test void changedUnownedProposalRetainsWholeRawRunWithProtectedMversusNMapping()throws Exception{
        try(var f=LegacyGroupedAllStageTest.stageFixture(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE))){
            var calls=new java.util.concurrent.atomic.AtomicInteger();
            LegacyEmissionKernel.Smoother smoother=(p,o,s)->{calls.incrementAndGet();var moved=new ArrayList<>(o);moved.set(moved.size()/2,moved.get(moved.size()/2)+2);return moved;};
            try(var result=new LegacyEmissionKernel().solveManagedGroupedChartAffinity(f.input(),f.profiles(),List.of(0.0,.25),List.of(),smoother,CancellationProbe.NONE,
                    LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,LegacyEmissionKernel.GroupedExecution.EXACT_Q0_CUTOFF_V1,null)){
                var emission=result.emission();var diagnostics=emission.managedAffinity().orElseThrow();
                assertTrue(calls.get()>0);assertEquals(calls.get(),diagnostics.smoothingOutcomes().size(),"One unchanged proposal per actual consecutive run");
                assertTrue(diagnostics.smoothingOutcomes().stream().anyMatch(s->s.disposition()==EmissionResult.SmoothingDisposition.RAW_RETAINED));
                assertEquals(emission.inferenceVertices(),emission.smoothedVertices());
                assertTrue(emission.inferenceVertices().stream().anyMatch(v->v.owner() instanceof EmissionMapping.BoundaryVertex));
                assertTrue(java.util.stream.IntStream.range(0,emission.inferenceVertices().size()).anyMatch(i->emission.inferenceVertices().get(i).owner() instanceof EmissionMapping.ObservationVertex o&&o.observationIndex()!=i));
                assertEquals(emission.postSmoothingArcs().size(),diagnostics.finalGeometry().size());
            }
        }
    }
    @ParameterizedTest @EnumSource(LegacyEmissionKernel.GroupedExecution.class)
    void everyOwnedSeedIntervalAndStitchMatchesFullPrefixOracle(LegacyEmissionKernel.GroupedExecution execution)throws Exception{
        try(var f=LegacyGroupedAllStageTest.stageFixture(new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE))){
            if(execution==LegacyEmissionKernel.GroupedExecution.EXACT_Q0_OWNED_NATIVE_GUIDE_V1){
                var before=f.input().workBudget().snapshot();
                assertThrows(IllegalArgumentException.class,()->new LegacyEmissionKernel().solveManagedGroupedChartAffinity(f.input(),f.profiles(),List.of(0.0),List.of(),(p,o,s)->o,CancellationProbe.NONE,
                    LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,execution,null));
                assertEquals(before,f.input().workBudget().snapshot());return;
            }
            var seeds=List.of(0.0,.25);var anchors=new ArrayList<LegacyEmissionKernel.AnchorProposal>();
            for(int row:List.of(0,2,4,6))anchors.add(new LegacyEmissionKernel.AnchorProposal(row,f.profiles().get(row).peaks().get(0)));
            var expected=LegacyGroupedExhaustiveOracle.enumerate(f,seeds,anchors);
            try(var progress=LegacyEmissionProgress.open(f.input(),LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC);
                var result=new LegacyEmissionKernel().solveManagedGroupedChartAffinity(f.input(),f.profiles(),seeds,anchors,(p,o,s)->o,CancellationProbe.NONE,
                    LegacyCompetitiveProofTest.QUERY,LegacyCompetitiveProofTest.EXEC,execution,progress)){
                LegacyGroupedAllStageTest.assertOracle(expected,result);assertEquals(3,result.emission().intervalStageReferences().size());
                assertEquals(5,result.stages().size());assertEquals(result.work(),f.input().workBudget().snapshot());
                assertTrue(result.emission().generatedStateReferences().stream().anyMatch(s->s.kind()==EmissionResult.StateKind.BASE_CARRY));
                assertTrue(result.emission().generatedStateReferences().stream().anyMatch(s->s.kind()==EmissionResult.StateKind.INTERVAL_TARGET));
                assertTrue(result.emission().inferenceVertices().stream().anyMatch(v->v.owner() instanceof EmissionMapping.BoundaryVertex));
                assertEquals(NativeMeasuredProfileReceipt.Availability.UNAVAILABLE,result.emission().managedAffinity().orElseThrow().globalModeFamilyAvailability());
                assertTrue(result.emission().sourceReference().modelPolicyId().contains(execution.name()));
                for(var stage:result.emission().intervalStageReferences()){
                    var fact=result.stages().stream().filter(s->s.ordinal()==stage.ordinal()).findFirst().orElseThrow();
                    assertEquals(fact.terminal().orElseThrow(),LegacyGroupedAllStageTest.forwardBlocks(fact.prefix().orElseThrow(),stage));
                }
            }
        }
    }
}
