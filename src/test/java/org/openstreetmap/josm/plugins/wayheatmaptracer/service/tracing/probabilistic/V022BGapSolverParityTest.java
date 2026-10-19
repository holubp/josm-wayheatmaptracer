package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

/** Actual solver integration, using declared hand observations only. */
class V022BGapSolverParityTest {
    private static final TraceBudgets BUDGETS=new TraceBudgets(8,10000,100000,4,4);

    @Test void retainedPreIntegrationBaseline() {
        try(var f=BGapTestFixture.standard()) {
            var input=graph(f);long before=f.owner.currentBytes();int[] checks={0};
            var result=BObservationGeometry.solveAdmitted(input.profiles(),input.evidence().decisionRegion(),BUDGETS,
                    BObservationGeometry.Enumeration.FULL_DEFAULT,()->{checks[0]++;return false;},f.owner);
            assertEquals(List.of(1,1),result.states());assertEquals(0,result.energy().orElseThrow(),0);
            assertEquals(7,checks[0]);assertEquals(1552,f.owner.currentBytes()-before);assertEquals(44496,f.owner.peakBytes());
            System.out.println("gap-preintegration-baseline "+result+" energyBits="+Long.toHexString(Double.doubleToRawLongBits(result.energy().orElseThrow()))
                    +" checks="+checks[0]+" currentDelta="+(f.owner.currentBytes()-before)+" peak="+f.owner.peakBytes());
        }
    }

    @Test void nonzeroPotentialChangesActualProductionSolveAndCountsOnlyHotLookups() throws Exception {
        try(var f=BGapTestFixture.standard()) {
            var input=graph(f);var field=BGapField.build(input,CancellationProbe.NONE,f.owner);
            try(var table=BGapPairTable.build(field,input.profiles(),CancellationProbe.NONE,f.owner)) {
                field.close();var before=table.counters();
                var result=solve(input,eta(table,1),CancellationProbe.NONE,f.owner);
                assertEquals(List.of(0,0),result.states(),"actual gap table must change the production MAP");
                assertEquals(.02,result.energy().orElseThrow(),1e-15);
                var after=table.counters();assertTrue(after.solverLookups()>before.solverLookups());
                assertEquals(before.sourceElements(),after.sourceElements());assertEquals(before.maskReads(),after.maskReads());
                assertEquals(before.stripPredicates(),after.stripPredicates());assertEquals(before.prefixOperations(),after.prefixOperations());
                assertEquals(before.cacheReads(),after.cacheReads());assertEquals(before.buildUnits()+2+3*input.profiles().size(),after.buildUnits());
                System.out.println("gap-enabled-table before="+before+" after="+after);
            } finally {field.close();}
        }
    }

    @Test void fixedEtaViewsAreScaledCachedAndClosedWithTheirTable() throws Exception {
        try(var f=BGapTestFixture.standard();var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner);
                var table=BGapPairTable.build(field,f.input.profiles(),CancellationProbe.NONE,f.owner)) {
            long bytes=f.owner.currentBytes();var before=table.counters();
            for(int coefficient:new int[]{1,2,4,8,16,32,64}) {
                var view=eta(table,coefficient);assertSame(view,eta(table,coefficient));
                assertEquals(coefficient/16.0,view.energy(0,0,0),0);
            }
            assertEquals(bytes,f.owner.currentBytes());assertEquals(before.buildUnits(),table.counters().buildUnits());
            assertEquals(before.solverLookups()+7,table.counters().solverLookups());
            for(int invalid:new int[]{-1,0,3,65})assertThrows(IllegalArgumentException.class,()->eta(table,invalid));
            var view=eta(table,1);table.close();assertThrows(IllegalStateException.class,()->view.energy(0,0,0));
            assertThrows(IllegalStateException.class,()->eta(table,1));
        }
    }

    @Test void enabledBindingRejectsDifferentActualProfileObjectsBeforeSolver() throws Exception {
        try(var f=BGapTestFixture.standard();var other=BGapTestFixture.standard()) {
            var input=graph(f);var otherInput=graph(other);
            try(var field=BGapField.build(input,CancellationProbe.NONE,f.owner);
                    var table=BGapPairTable.build(field,input.profiles(),CancellationProbe.NONE,f.owner)) {
                long before=f.owner.currentBytes();long lookups=table.counters().solverLookups();
                var refusal=assertThrows(IllegalArgumentException.class,()->solve(otherInput,eta(table,1),CancellationProbe.NONE,f.owner));
                assertTrue(refusal.getMessage().contains("profile identity"));
                assertEquals(before-BGapPairTable.tableBytes(input.profiles()),f.owner.currentBytes());
                assertEquals(lookups,table.counters().solverLookups());
            }
        }
    }

    @Test void tinyRealTablePartitionMarginalsAndMapMatchIndependentFourPathOracle() throws Exception {
        try(var f=BGapTestFixture.standard()) {
            var input=graph(f);var field=BGapField.build(input,CancellationProbe.NONE,f.owner);
            try(var table=BGapPairTable.build(field,input.profiles(),CancellationProbe.NONE,f.owner)) {
                field.close();var view=eta(table,1);long before=table.counters().solverLookups();int[] calls={0};
                var result=new ProbabilisticInference().solve(input.profiles(),EvidenceModelParameters.defaults(),BUDGETS,
                        input.evidence().decisionRegion(),CancellationProbe.NONE,f.owner,ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2,
                        (stage,left,right)->{calls[0]++;return view.energy(stage,left,right);});
                // Independent hand field: g=1/32 only after y=0 on the diagonal;
                // each crossing has one quarter of its whole 2*sqrt(2) length there.
                double diagonal=StrictMath.hypot(2,2)/128;
                double e00=.02,e01=.01+diagonal,e11=1.0/16;
                double a=StrictMath.exp(-e00),b=2*StrictMath.exp(-e01),d=4*StrictMath.exp(-e11),z=a+2*b+d;
                assertEquals(StrictMath.log(z),result.logPartition(),1e-13);
                for(var marginal:result.positionMarginals())assertArrayEquals(new double[]{(a+b)/z,(b+d)/z},marginal,1e-13);
                assertArrayEquals(new int[]{0,0},result.mapPath().orElseThrow().stateIndices());
                assertEquals(.02,result.mapPath().orElseThrow().energy(),1e-15);
                assertEquals(4,result.rawPaths().size());assertEquals(4,result.completion().orElseThrow().effectiveRawLimit());
                for(var path:result.rawPaths()) {
                    int[] states=path.stateIndices();double energy=states[0]==states[1]?(states[0]==0?e00:e11):e01;
                    double measure=(states[0]==0?1:2)*(states[1]==0?1:2);
                    assertEquals(energy,path.energy(),1e-13);
                    assertEquals(measure*StrictMath.exp(-energy)/z,path.conditionalPosteriorMass(),1e-13);
                }
                assertEquals(8,calls[0],"four actual initial FB pairs plus four actual initial K-best pairs");
                assertEquals(calls[0],table.counters().solverLookups()-before);
                assertEquals(0,result.evaluatedTransitions());
                System.out.println("gap-enabled-solver work="+result.workAccounting()+" completion="+result.completion()+" lookups="+calls[0]);
            } finally {field.close();}
        }
    }

    @Test void etaZeroIsRawBitWorkCheckpointAndOwnedByteIdenticalWithoutGapState() throws Exception {
        for(int rows:new int[]{2,3})try(var f=new BGapTestFixture(1,(x,y)->BGapTestFixture.observed(y),false,true,rows,1)) {
            var input=graph(f);
            for(var enumeration:BObservationGeometry.Enumeration.values()) {
                assertEquals(receipt(input,enumeration,false),receipt(input,enumeration,true));
            }
        }
    }

    @Test void cancellationAndOriginalBudgetRefusalDiscardEnabledTableAndPreserveOriginalOwners() throws Exception {
        for(boolean cancel:new boolean[]{true,false})try(var f=BGapTestFixture.standard()) {
            var input=graph(f);long original=f.owner.currentBytes();var tree=BGapTestFixture.ownerTree(f.owner);
            var field=BGapField.build(input,CancellationProbe.NONE,f.owner);
            var table=BGapPairTable.build(field,input.profiles(),CancellationProbe.NONE,f.owner);field.close();
            var view=eta(table,1);
            if(cancel) {
                assertThrows(java.util.concurrent.CancellationException.class,()->solve(input,view,()->true,f.owner));
                assertEquals(original,f.owner.currentBytes());assertEquals(tree,BGapTestFixture.ownerTree(f.owner));
            } else {
                // Original pair-visit guard refuses before any potential call.
                var failed=solve(input,view,new TraceBudgets(8,1,100000,4,4),CancellationProbe.NONE,f.owner);
                assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,failed.path().status());assertTrue(failed.states().isEmpty());
                assertEquals(0,table.counters().solverLookups());
            }
            assertThrows(IllegalStateException.class,()->view.energy(0,0,0));table.close();
        }
    }

    @Test void lateCancellationAndReturnedNumericFailureCloseCompleteEnabledArena() throws Exception {
        for(boolean numeric:new boolean[]{false,true})try(var f=BGapTestFixture.standard()) {
            var input=graph(f);long original=f.owner.currentBytes();var tree=BGapTestFixture.ownerTree(f.owner);
            var field=BGapField.build(input,CancellationProbe.NONE,f.owner);
            var table=BGapPairTable.build(field,input.profiles(),CancellationProbe.NONE,f.owner);field.close();
            var view=eta(table,1);
            if(numeric) {
                // Adversarially break the immutable table promise: the actual
                // solver's returned failure must still discard the whole arena.
                var member=BGapPairTable.class.getDeclaredField("values");member.setAccessible(true);
                ((double[])member.get(table))[0]=Double.NaN;
                var failed=solve(input,view,CancellationProbe.NONE,f.owner);
                assertEquals(ProbabilisticInferenceResult.Status.NUMERIC_FAILURE,failed.path().status());assertTrue(failed.states().isEmpty());
            } else {
                assertThrows(java.util.concurrent.CancellationException.class,()->solve(input,view,
                        ()->table.counters().solverLookups()>0,f.owner));
                assertTrue(table.counters().solverLookups()>0);assertEquals(original,f.owner.currentBytes());
                assertEquals(tree,BGapTestFixture.ownerTree(f.owner));
            }
            assertEquals(0,table.counters().currentOwnedBytes());
            assertThrows(IllegalStateException.class,()->view.energy(0,0,0));table.close();
        }
    }

    private record Receipt(BObservationGeometry.MapResult result,int checkpoints,long current,long peak,Map<String,Number> counters) { }
    private static Receipt receipt(BGapEvidence.Input input,BObservationGeometry.Enumeration enumeration,boolean explicitNull) throws Exception {
        var ledger=new AttemptMemoryLedger(BGapEvidence.BYTES);int[] checks={0};ModernDiagnosticCounters.begin();
        try(var owner=ledger.rootOwner()) {
            CancellationProbe probe=()->{checks[0]++;return false;};
            var result=explicitNull?solve(input,null,BUDGETS,enumeration,probe,owner):
                    BObservationGeometry.solveAdmitted(input.profiles(),input.evidence().decisionRegion(),BUDGETS,enumeration,probe,owner);
            var counters=new TreeMap<>(ModernDiagnosticCounters.snapshot());
            counters.remove("inference.forwardBackwardMs");counters.remove("inference.kBestMs");counters.remove("inference.alternativesMs");
            return new Receipt(result,checks[0],ledger.currentBytes(),ledger.peakBytes(),counters);
        } finally {ModernDiagnosticCounters.end();assertEquals(0,ledger.currentBytes());}
    }

    private static BGapEvidence.Input graph(BGapTestFixture f) {
        var rows=new ArrayList<InferenceProfile>();
        for(var fact:f.input.facts()) {
            var raw=fact.baseline();
            var cells=List.of(new LateralStateCell(-1.5,1,false,false,"unlocalized"),new LateralStateCell(.5,2,false,false,"unlocalized"));
            rows.add(InferenceProfile.accounted(raw.chainageMeters(),raw.anchor(),raw.normalUnit(),cells,new double[]{.01,0},Map.of(),Map.of(),
                    ObservationOwnership.NO_SIGNAL_VALID_RASTER,true,new double[2],new double[2][0],f.owner));
        }
        return new BGapEvidence.Input(f.input.evidence(),f.input.fieldKey(),f.input.facts(),rows,f.input.contract());
    }
    private static AdjacentStatePotential eta(BGapPairTable table,int eta) {return table.forEta(eta);}
    private static BObservationGeometry.MapResult solve(BGapEvidence.Input input,AdjacentStatePotential potential,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) throws Exception {
        return solve(input,potential,BUDGETS,cancellation,owner);
    }
    private static BObservationGeometry.MapResult solve(BGapEvidence.Input input,AdjacentStatePotential potential,TraceBudgets budgets,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) throws Exception {
        return solve(input,potential,budgets,BObservationGeometry.Enumeration.FULL_DEFAULT,cancellation,owner);
    }
    private static BObservationGeometry.MapResult solve(BGapEvidence.Input input,AdjacentStatePotential potential,TraceBudgets budgets,
            BObservationGeometry.Enumeration enumeration,CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) throws Exception {
        return BObservationGeometry.solveAdmitted(input.profiles(),input.evidence().decisionRegion(),budgets,enumeration,cancellation,owner,potential);
    }
}
