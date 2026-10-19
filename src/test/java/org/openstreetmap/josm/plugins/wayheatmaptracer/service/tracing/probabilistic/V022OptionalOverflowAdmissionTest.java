package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022OptionalOverflowAdmissionTest {
    @Test void temporaryCensusIncludesTheBorrowedListsInheritedModCount() {
        // int[5]=40, stateless builder24, Arrays.ArrayList reference8 + inherited modCount4 =>32.
        assertEquals(96,ProbabilisticOptionalOverflowAdmission.temporaryBytes(5));
    }
    @Test void noDeclineCoarseningUsesTheUnchangedStrictPlanAndCells() {
        var raw=profiles(5,0);var proposals=proposals(5);var budgets=new TraceBudgets(12,200,3300,1,1);
        var strict=ProbabilisticLatticeAdmission.select(raw,proposals,budgets,CancellationProbe.NONE,null);
        assertTrue(strict.admitted());assertTrue(strict.coarsened());
        var ledger=new AttemptMemoryLedger(64L*1024*1024);
        try(var owner=ledger.rootOwner();var selected=ProbabilisticOptionalOverflowAdmission.select(raw,proposals,budgets,CancellationProbe.NONE,owner)) {
            assertTrue(selected.admitted());assertTrue(selected.coarsened());assertEquals(0,selected.declinedCount());
            assertEquals(strict.pairBound(),selected.pairBound());assertEquals(strict.completeScoreBound(),selected.completeScoreBound());
            for(int i=0;i<raw.size();i++)assertCells(new ProbabilisticStateBuilder().buildWithReservedProposal(raw.get(i),strict.maximumStates(i),proposals[i],null).lattice().orElseThrow(),
                    selected.buildProfile(i,owner,CancellationProbe.NONE).lattice().orElseThrow());
        }
        assertEquals(0,ledger.currentBytes());
    }

    @Test void foreignOwnerAndLateResourceRefusalCloseOnlyTheSelection() throws Exception {
        var ledger=new AttemptMemoryLedger(64L*1024*1024);var foreign=new AttemptMemoryLedger(64L*1024*1024);
        try(var owner=ledger.rootOwner();var other=foreign.rootOwner()) {
            var tree=BGapTestFixture.ownerTree(owner);
            var first=ProbabilisticOptionalOverflowAdmission.select(profiles(5,0),proposals(5),TraceBudgets.defaults(),CancellationProbe.NONE,owner);
            assertThrows(IllegalArgumentException.class,()->first.buildProfile(0,other,CancellationProbe.NONE));
            assertEquals(0,owner.currentBytes());assertEquals(tree,BGapTestFixture.ownerTree(owner));assertEquals(0,other.currentBytes());
            var selected=ProbabilisticOptionalOverflowAdmission.select(profiles(5,0),proposals(5),TraceBudgets.defaults(),CancellationProbe.NONE,owner);
            var finalSelected=selected;long held=ledger.limitBytes()-owner.currentBytes()-16;
            try(var blocker=owner.reserve(held)) {
                assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->finalSelected.buildProfile(0,owner,CancellationProbe.NONE));
                assertEquals(held,owner.currentBytes());assertEquals(tree,BGapTestFixture.ownerTree(owner));
            }
            assertEquals(0,owner.currentBytes());
        }
    }
    @Test void validAugmentationRetainsStrictCellsAndSeparatesNativeFromSelectedWorkBounds() {
        var profiles=new ArrayList<>(profiles(5,0));
        profiles.set(2,profile(2,List.of(new ProbabilisticProfile.Mode("zero","zero-lineage",-.05,.05,.5,.8,.9,List.of(),List.of(),false)),OptionalDouble.empty()));
        var proposals=proposals(5);proposals[2]=.123;var ledger=new AttemptMemoryLedger(64L*1024*1024);
        try(var owner=ledger.rootOwner();var selected=ProbabilisticOptionalOverflowAdmission.select(profiles,proposals,TraceBudgets.defaults(),CancellationProbe.NONE,owner)) {
            assertTrue(selected.admitted());assertEquals(0,selected.declinedCount());
            assertEquals(5_262_336,selected.nativeForwardBackwardBound());assertEquals(5_317_632,selected.selectedForwardBackwardBound());
            assertCells(new ProbabilisticStateBuilder().buildWithProposal(profiles.get(2),96,.123,null).lattice().orElseThrow(),
                    selected.buildProfile(2,owner,CancellationProbe.NONE).lattice().orElseThrow());
        }
        assertEquals(0,ledger.currentBytes());
    }

    @Test void proposalCopiesDuplicateNaNAndAnchorSemanticsAreOriginalAndImmutable() {
        var profiles=new ArrayList<>(profiles(5,0));profiles.set(0,profile(0,List.of(),OptionalDouble.of(-0.0)));
        var proposals=proposals(5);proposals[0]=Double.POSITIVE_INFINITY;proposals[1]=-25;
        proposals[2]=Double.longBitsToDouble(0x7ff8000000000012L);proposals[3]=-0.0;
        var ledger=new AttemptMemoryLedger(64L*1024*1024);
        try(var owner=ledger.rootOwner();var selected=ProbabilisticOptionalOverflowAdmission.select(profiles,proposals,TraceBudgets.defaults(),CancellationProbe.NONE,owner)) {
            proposals[1]=1000;profiles.set(1,profile(100,List.of(),OptionalDouble.of(3)));
            assertEquals(1,selected.declinedCount());assertTrue(selected.declined(3));assertFalse(selected.declined(0));
            assertEquals(Double.doubleToRawLongBits(-0.0),Double.doubleToRawLongBits(selected.originalProposal(3)));
            assertEquals(0x7ff8000000000012L,Double.doubleToRawLongBits(selected.effectiveProposal(2)));
            assertEquals(-25,selected.effectiveProposal(1),0);
            assertEquals(96,selected.buildProfile(1,owner,CancellationProbe.NONE).lattice().orElseThrow().cells().size());
            assertEquals(Double.doubleToRawLongBits(-0.0),Double.doubleToRawLongBits(selected.buildProfile(0,owner,CancellationProbe.NONE).lattice().orElseThrow().cells().get(0).offsetMeters()));
        }
        assertEquals(0,ledger.currentBytes());
    }

    @Test void nativeFailureInvalidProposalAndResourceFailureAreNeverDeclines() throws Exception {
        var ledger=new AttemptMemoryLedger(64L*1024*1024);
        try(var owner=ledger.rootOwner()) {
            for(double invalid:new double[]{Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,26,-26}) {
                var proposals=proposals(5);proposals[2]=invalid;var tree=BGapTestFixture.ownerTree(owner);
                assertThrows(IllegalArgumentException.class,()->ProbabilisticOptionalOverflowAdmission.select(profiles(5,0),proposals,TraceBudgets.defaults(),CancellationProbe.NONE,owner));
                assertEquals(0,owner.currentBytes());assertEquals(tree,BGapTestFixture.ownerTree(owner));
            }
            try(var selected=ProbabilisticOptionalOverflowAdmission.select(profiles(5,11),proposals(5),new TraceBudgets(12,1000,20000,1,1),CancellationProbe.NONE,owner)) {
                assertFalse(selected.admitted());assertEquals("NATIVE_STATE_LIMIT",selected.refusalReason());assertEquals(0,selected.declinedCount());
                assertEquals(0,selected.work().strictSelections);
            }
            assertEquals(0,owner.currentBytes());
        }
        var tiny=new AttemptMemoryLedger(1);
        try(var owner=tiny.rootOwner()) {
            var tree=BGapTestFixture.ownerTree(owner);
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->ProbabilisticOptionalOverflowAdmission.select(profiles(5,0),proposals(5),TraceBudgets.defaults(),CancellationProbe.NONE,owner));
            assertEquals(0,owner.currentBytes());assertEquals(tree,BGapTestFixture.ownerTree(owner));
        }
    }

    @Test void cancellationSelectionMaterializationAndClosureHaveAtomicOwnership() throws Exception {
        var raw=profiles(5,0);var proposal=proposals(5);proposal[2]=0;
        for(int stop:new int[]{1,8,30}) {
            var ledger=new AttemptMemoryLedger(64L*1024*1024);
            try(var owner=ledger.rootOwner()) {
                int[] calls={0};var tree=BGapTestFixture.ownerTree(owner);
                assertThrows(CancellationException.class,()->ProbabilisticOptionalOverflowAdmission.select(raw,proposal,TraceBudgets.defaults(),()->++calls[0]>=stop,owner));
                assertEquals(0,owner.currentBytes());assertEquals(tree,BGapTestFixture.ownerTree(owner));
            }
        }
        for(int stop:new int[]{1,2}) {
            var ledger=new AttemptMemoryLedger(64L*1024*1024);
            try(var owner=ledger.rootOwner()) {
                var tree=BGapTestFixture.ownerTree(owner);
                var selected=ProbabilisticOptionalOverflowAdmission.select(raw,proposal,TraceBudgets.defaults(),CancellationProbe.NONE,owner);
                int[] calls={0};assertThrows(CancellationException.class,()->selected.buildProfile(2,owner,()->++calls[0]>=stop));
                assertEquals(0,owner.currentBytes());assertEquals(tree,BGapTestFixture.ownerTree(owner));
                assertThrows(IllegalStateException.class,selected::admitted);selected.close();
            }
        }
    }

    @Test void ownedMetadataCensusWorkBoundsAndSingleMaterializationAreExplicit() {
        // Selection104B fields+16 header=120; Work64+16=80;
        // three A(5,8)=168, A(5,1)=24 =>392, plus original strict Plan64.
        assertEquals(392,ProbabilisticOptionalOverflowAdmission.retainedBytes(5));
        assertEquals(1072,ProbabilisticOptionalOverflowAdmission.preflightUpper(5,96));
        var ledger=new AttemptMemoryLedger(64L*1024*1024);
        try(var owner=ledger.rootOwner()) {
            var selected=ProbabilisticOptionalOverflowAdmission.select(profiles(5,0),proposals(5),TraceBudgets.defaults(),CancellationProbe.NONE,owner);
            assertEquals(456,owner.currentBytes());assertEquals(15,selected.work().copiedElements);assertEquals(5,selected.work().nativeBuilds);
            assertEquals(1,selected.work().strictSelections);assertTrue(selected.work().units<=selected.work().upper);
            // Five copied rows, five native rows, five bounds, one strict call and final publication.
            assertEquals(58,selected.work().units);assertEquals(17,selected.work().checkpoints);
            selected.buildProfile(0,owner,CancellationProbe.NONE);assertEquals(1,selected.work().materializations);
            assertEquals(61,selected.work().units);assertEquals(19,selected.work().checkpoints);
            assertThrows(IllegalStateException.class,()->selected.buildProfile(0,owner,CancellationProbe.NONE));
            selected.close();assertThrows(IllegalStateException.class,()->selected.buildProfile(1,owner,CancellationProbe.NONE));
        }
        assertEquals(0,ledger.currentBytes());
    }

    @Test void alreadyStrictAdmittedB0GraphKeepsActualSolverEnergyStatesPartitionAndWork() throws Exception {
        try(var f=new BGapTestFixture(1,(x,y)->BGapTestFixture.observed(y),false,true,3,3)) {
            var raw=f.input.facts().stream().map(BIdentificationFacts.ProfileFacts::baseline).toList();var proposal=proposals(raw.size());
            var budget=new TraceBudgets(8,10000,100000,2,2);var strict=ProbabilisticLatticeAdmission.select(raw,proposal,budget,CancellationProbe.NONE,null);
            assertTrue(strict.admitted());
            try(var selected=ProbabilisticOptionalOverflowAdmission.select(raw,proposal,budget,CancellationProbe.NONE,f.owner);
                    var oldOwner=f.owner.child("strict-parity");var newOwner=f.owner.child("new-parity")) {
                var oldGraph=new ArrayList<InferenceProfile>();var newGraph=new ArrayList<InferenceProfile>();
                var kernel=new BIdentifiedObservationKernel(new BIdentifiedObservationKernel.Parameters(1,1,0));
                for(int i=0;i<raw.size();i++) {
                    var oldLattice=new ProbabilisticStateBuilder().buildWithProposal(raw.get(i),strict.maximumStates(),proposal[i],null).lattice().orElseThrow();
                    var newLattice=selected.buildProfile(i,f.owner,CancellationProbe.NONE).lattice().orElseThrow();assertCells(oldLattice,newLattice);
                    oldGraph.add(BObservationAdapter.evaluate(f.input.facts().get(i),oldLattice,EvidenceModelParameters.defaults(),true,kernel,CancellationProbe.NONE,oldOwner));
                    newGraph.add(BObservationAdapter.evaluate(f.input.facts().get(i),newLattice,EvidenceModelParameters.defaults(),true,kernel,CancellationProbe.NONE,newOwner));
                }
                var solver=new ProbabilisticInference();
                var a=solver.solve(oldGraph,EvidenceModelParameters.defaults(),budget,f.input.evidence().decisionRegion(),CancellationProbe.NONE,oldOwner);
                var b=solver.solve(newGraph,EvidenceModelParameters.defaults(),budget,f.input.evidence().decisionRegion(),CancellationProbe.NONE,newOwner);
                assertArrayEquals(a.mapPath().orElseThrow().stateIndices(),b.mapPath().orElseThrow().stateIndices());
                assertEquals(Double.doubleToRawLongBits(a.mapPath().orElseThrow().energy()),Double.doubleToRawLongBits(b.mapPath().orElseThrow().energy()));
                assertEquals(Double.doubleToRawLongBits(a.logPartition()),Double.doubleToRawLongBits(b.logPartition()));assertEquals(a.workAccounting(),b.workAccounting());
                assertEquals(a.evaluatedTransitions(),b.evaluatedTransitions());assertEquals(a.completion(),b.completion());
                for(int i=0;i<a.positionMarginals().size();i++)assertArrayEquals(a.positionMarginals().get(i),b.positionMarginals().get(i),0);
                assertEquals(0,selected.declinedCount());
            }
        }
    }
    @Test void explicitPolicyDeclinesNovelZeroAtNative96WithoutChangingAnyNativeCell() {
        var profiles=profiles(5,0);double[] proposals=proposals(5);proposals[2]=0;
        assertFalse(ProbabilisticLatticeAdmission.select(profiles,proposals,TraceBudgets.defaults(),CancellationProbe.NONE,null).admitted());
        var ledger=new AttemptMemoryLedger(64L*1024*1024);
        try(var owner=ledger.rootOwner();var selection=ProbabilisticOptionalOverflowAdmission.select(profiles,proposals,TraceBudgets.defaults(),CancellationProbe.NONE,owner)) {
            assertTrue(selection.admitted());assertFalse(selection.coarsened());assertEquals(1,selection.declinedCount());
            assertTrue(selection.declined(2));assertEquals(0,selection.originalProposal(2),0);assertTrue(Double.isNaN(selection.effectiveProposal(2)));
            try(var output=owner.child("test-lattice")) {
                var nativeLattice=new ProbabilisticStateBuilder().build(profiles.get(2),96).lattice().orElseThrow();
                var selected=selection.buildProfile(2,output,CancellationProbe.NONE).lattice().orElseThrow();
                assertCells(nativeLattice,selected);assertEquals(96,selected.cells().size());
            }
        }
        assertEquals(0,ledger.currentBytes());
    }

    @Test void declinedGraphThatCannotFitWorkIsRefusedWithoutCoarseningOrRetry() {
        var profiles=profiles(5,4);double[] proposals=proposals(5);proposals[2]=.123;
        var budget=new TraceBudgets(12,200,3300,1,1);
        assertTrue(ProbabilisticLatticeAdmission.select(profiles,proposals,budget,CancellationProbe.NONE,null).coarsened());
        var ledger=new AttemptMemoryLedger(64L*1024*1024);
        try(var owner=ledger.rootOwner();var selection=ProbabilisticOptionalOverflowAdmission.select(profiles,proposals,budget,CancellationProbe.NONE,owner)) {
            assertFalse(selection.admitted());assertFalse(selection.coarsened());assertEquals(1,selection.declinedCount());
            assertThrows(IllegalStateException.class,()->selection.buildProfile(2,owner,CancellationProbe.NONE));
        }
        assertEquals(0,ledger.currentBytes());
    }

    static List<ProbabilisticProfile> profiles(int count,int modes) {
        var result=new ArrayList<ProbabilisticProfile>();
        for(int row=0;row<count;row++) {
            var measured=new ArrayList<ProbabilisticProfile.Mode>();
            if(row==count/2)for(int i=0;i<modes;i++) {
                double center=-18+4*i;measured.add(new ProbabilisticProfile.Mode("mode"+i,"lineage"+i,center-.05,center+.05,.5,.8,.9,List.of(),List.of(),false));
            }
            result.add(profile(row,measured,OptionalDouble.empty()));
        }
        return List.copyOf(result);
    }
    static ProbabilisticProfile profile(int row,List<ProbabilisticProfile.Mode> modes,OptionalDouble anchor) {
        return new ProbabilisticProfile(row,row,new MetricPoint(row,0),new MetricPoint(0,1),-25,25,1,true,.02,
                List.of(new ProbabilisticProfile.Sample(-25,.02,true),new ProbabilisticProfile.Sample(0,.02,true),new ProbabilisticProfile.Sample(25,.02,true)),
                modes,List.of(),anchor,ImageOrientationSupport.unknown(ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT));
    }
    static double[] proposals(int count){var values=new double[count];Arrays.fill(values,Double.NaN);return values;}
    static void assertCells(ProbabilisticStateLattice expected,ProbabilisticStateLattice actual) {
        assertEquals(expected.cells().size(),actual.cells().size());
        for(int i=0;i<expected.cells().size();i++) {
            var a=expected.cells().get(i);var b=actual.cells().get(i);
            assertEquals(Double.doubleToRawLongBits(a.offsetMeters()),Double.doubleToRawLongBits(b.offsetMeters()));
            assertEquals(Double.doubleToRawLongBits(a.quadratureWidthMeters()),Double.doubleToRawLongBits(b.quadratureWidthMeters()));
            assertEquals(a.mandatory(),b.mandatory());assertEquals(a.exactAnchor(),b.exactAnchor());assertEquals(a.branchLabel(),b.branchLabel());
        }
    }
}
