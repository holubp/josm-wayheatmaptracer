package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.PersistentProposalExperiment.*;

class V022ReservedContinuityCounterfactualTest {
    record Input(List<Row> rows,List<InferenceProfile> profiles,List<CertifiedLink> links) { }
    static Input straight(double[][] cores,List<Double> offsets,List<CertifiedLink> links) {
        List<Row> rows=new ArrayList<>();List<InferenceProfile> profiles=new ArrayList<>();
        double spacing=12.0/(cores.length-1);
        for(int r=0;r<cores.length;r++) {
            List<Band> bands=new ArrayList<>();for(int b=0;b<cores[r].length;b++)bands.add(new Band(new BandRef(r,b),cores[r][b],cores[r][b],0,1,List.of(offsets.indexOf(cores[r][b])),BoundaryStatus.OBSERVED));
            rows.add(new Row(r*spacing,new MetricPoint(r*spacing,0),new MetricPoint(0,1),bands,offsets,OptionalInt.empty()));
            List<LateralStateCell> cells=offsets.stream().map(d->new LateralStateCell(d,1,false,true,"original")).toList();
            profiles.add(new InferenceProfile(r*spacing,rows.get(r).originalMetricAnchor(),rows.get(r).originalUnitNormal(),cells,new double[cells.size()],
                ImageOrientationSupport.unknown(ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT),cores[r].length==0?ObservationOwnership.INFERRED_GAP:ObservationOwnership.DIRECT_TWO_SIDED,cores[r].length==0));
        }
        return new Input(List.copyOf(rows),List.copyOf(profiles),links);
    }
    static CertifiedLink port(int r,int a,int b,int first,int second) {
        return new CertifiedLink(new BandRef(r,a),new BandRef(r+1,b),LinkKind.OBSERVED_JUNCTION,Set.of(new StatePair(first,second)),Map.of());
    }
    static ReservedContinuityCounterfactual.Carrier carrier(Input input,ProposalReservation history,Context context) {
        return ReservedContinuityCounterfactual.prepare(input.rows(),history,input.profiles(),null,Map.of(),context);
    }
    static Input hairpin() {
        List<MetricPoint> anchors=List.of(new MetricPoint(0,0),new MetricPoint(4,0),new MetricPoint(4,4),new MetricPoint(0,4));
        List<MetricPoint> normals=List.of(new MetricPoint(0,1),new MetricPoint(-1,0),new MetricPoint(0,-1),new MetricPoint(1,0));
        List<Row> rows=new ArrayList<>();List<InferenceProfile> profiles=new ArrayList<>();
        for(int r=0;r<4;r++) {
            boolean gap=r==1||r==2;boolean fixed=!gap;
            var bands=gap?List.<Band>of():List.of(new Band(new BandRef(r,0),0,0,0,1,List.of(0),BoundaryStatus.OBSERVED));
            rows.add(new Row(r*4,anchors.get(r),normals.get(r),bands,List.of(0.),fixed?OptionalInt.of(0):OptionalInt.empty()));
            profiles.add(new InferenceProfile(r*4,anchors.get(r),normals.get(r),List.of(new LateralStateCell(0,1,fixed,true,"hairpin")),new double[]{0},
                ImageOrientationSupport.unknown(ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT),gap?ObservationOwnership.INFERRED_GAP:ObservationOwnership.FIXED_TOPOLOGY_ONLY,gap));
        }
        return new Input(List.copyOf(rows),List.copyOf(profiles),List.of(new CertifiedLink(new BandRef(0,0),new BandRef(3,0),LinkKind.DIRECT_CONTINUATION,
            Set.of(new StatePair(0,0)),Map.of(1,Set.of(0),2,Set.of(0)))));
    }
    static Map<Integer,MetricRegion> hairpinPatches() {
        return Map.of(0,MetricRegion.rectangle(-.1,-.1,4.1,.1),1,MetricRegion.rectangle(3.9,-.1,4.1,4.1),2,MetricRegion.rectangle(-.1,3.9,4.1,4.1));
    }
    static List<InferenceProfile> tiny() {
        List<InferenceProfile> result=new ArrayList<>();
        for(int i=0;i<3;i++)result.add(new InferenceProfile(i,new MetricPoint(i,0),new MetricPoint(0,1),
            List.of(new LateralStateCell(0,1,false,true,"a"),new LateralStateCell(1,1,false,true,"b")),new double[]{0,1},
            ImageOrientationSupport.unknown(ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT),ObservationOwnership.DIRECT_TWO_SIDED,false));
        return List.copyOf(result);
    }
    @Test void cachedPreparationConjoinsAdditionalAdmission() {
        var ledger=AttemptMemoryLedger.production();var p=tiny();
        try(var owner=ledger.rootOwner()) {
            var cache=PreparedBKernel.prepare(p,EvidenceModelParameters.defaults(),null,CancellationProbe.NONE,owner,(r,a,b,ps,region)->false);
            assertFalse(cache.allowed(0,0,0),"Additional forbidden edge must survive cached preparation");
        }finally{assertEquals(0,ledger.currentBytes());}
    }
    @Test void frozenFullForkKeepsAllStatesButRejectsOldSpliceForEachHistory() {
        var f=V022PersistentProposalNativeCharacterizationTest.fixture();var ledger=AttemptMemoryLedger.production();
        var region=MetricRegion.rectangle(0,-25,19*1.56,25);
        try(var owner=ledger.rootOwner()) {
            var context=new Context(TraceBudgets.defaults(),CancellationProbe.NONE,owner);var proposals=reserve(f.rows(),f.links(),context);
            assertEquals(2,proposals.reservations().size());
            for(var history:proposals.reservations()) {
                var gate=ReservedContinuityCounterfactual.prepare(f.rows(),history,f.profiles(),region,Map.of(),context);
                assertEquals(938,gate.profiles().stream().mapToInt(p->p.cells().size()).sum());
                assertFalse(gate.allowed(9,25,33,f.profiles(),region),"Pinned old winner row9→10 transfer must be forbidden");
                for(int row=0;row<19;row++) {
                    var from=history.bandTrace().get(row);var to=history.bandTrace().get(row+1);
                    int a=f.rows().get(row).bands().stream().filter(b->b.ref().equals(from)).findFirst().orElseThrow().originalCoreIndices().get(0);
                    int b=f.rows().get(row+1).bands().stream().filter(band->band.ref().equals(to)).findFirst().orElseThrow().originalCoreIndices().get(0);
                    assertTrue(gate.allowed(row,a,b,f.profiles(),region),"Complete core arm must remain feasible");
                }
            }
        }finally{assertEquals(0,ledger.currentBytes());}
    }
    @Test void publicTinyNativeUnitAppliesGateToPosteriorAndAllRawPaths() {
        var ledger=AttemptMemoryLedger.production();var p=tiny();
        try(var owner=ledger.rootOwner()) {
            var result=new ProbabilisticInference().solveReservedContinuity(p,EvidenceModelParameters.defaults(),TraceBudgets.defaults(),null,
                CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4,(r,a,b,ps,region)->a==1&&b==1);
            assertEquals(1,result.rawPaths().size());assertArrayEquals(new int[]{1,1,1},result.rawPaths().get(0).stateIndices());
            for(var row:result.positionMarginals())assertArrayEquals(new double[]{0,1},row,1e-14);
            for(var row:result.forwardPositionMarginals())assertArrayEquals(new double[]{0,1},row,1e-14);
            for(var row:result.backwardPositionMarginals())assertArrayEquals(new double[]{0,1},row,1e-14);
        }finally{assertEquals(0,ledger.currentBytes());}
    }
    @Test void experimentalEntryRefusesOtherBackendsBeforePreparation() {
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            assertThrows(IllegalArgumentException.class,()->new ProbabilisticInference().solveReservedContinuity(tiny(),EvidenceModelParameters.defaults(),TraceBudgets.defaults(),null,
                CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V3,(r,a,b,ps,region)->true));
        }finally{assertEquals(0,ledger.currentBytes());}
    }
    @Test void overlappingHistoriesCannotStitchAtSharedCell() {
        for(boolean witnessedCrossing:List.of(false,true)) {
        var input=straight(new double[][]{{-2,2},{0,0},{-2,2}},List.of(-2.,0.,2.),List.of(port(0,0,0,0,1),port(0,1,1,2,1),
            port(1,0,witnessedCrossing?1:0,1,witnessedCrossing?2:0),port(1,1,witnessedCrossing?0:1,1,witnessedCrossing?0:2)));
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var c=new Context(TraceBudgets.defaults(),CancellationProbe.NONE,owner);var set=reserve(input.rows(),input.links(),c);assertEquals(2,set.reservations().size());
            for(var history:set.reservations()) {
                var gate=carrier(input,history,c);boolean left=history.bandTrace().get(0).ordinal()==0;
                int entry=left?0:2,exit=witnessedCrossing?2-entry:entry;
                assertTrue(gate.allowed(0,entry,1,input.profiles(),null));assertTrue(gate.allowed(1,1,exit,input.profiles(),null));
                assertFalse(gate.allowed(1,1,2-exit,input.profiles(),null),"Only explicit exact port relation can change the complete history");
            }
        }finally{assertEquals(0,ledger.currentBytes());}
        }
    }
    @Test void uniqueGapUsesAlreadyExpandedBoundsAndKeepsUnsupportedProvenance() {
        var input=straight(new double[][]{{0},{},{},{0}},List.of(-2.,-1.,0.,1.,2.),List.of());var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var c=new Context(TraceBudgets.defaults(),CancellationProbe.NONE,owner);var history=reserve(input.rows(),input.links(),c).reservations().get(0);
            var gate=carrier(input,history,c);assertEquals(Status.COMPLETE,history.status());
            assertTrue(gate.provenance().contains("UNSUPPORTED_GAP"));assertTrue(gate.allowed(1,3,3,input.profiles(),null));
            assertFalse(gate.patches().get(1).contains(new MetricPoint(6,1.01)),"No second source-pitch expansion");
            assertFalse(gate.allowed(1,4,3,input.profiles(),null));
        }finally{assertEquals(0,ledger.currentBytes());}
    }
    @Test void carrierRejectsStaleDomainAndClosedOwner() {
        var input=straight(new double[][]{{0},{0}},List.of(0.),List.of());var ledger=AttemptMemoryLedger.production();ReservedContinuityCounterfactual.Carrier gate;
        try(var owner=ledger.rootOwner()) {
            var c=new Context(TraceBudgets.defaults(),CancellationProbe.NONE,owner);gate=carrier(input,reserve(input.rows(),input.links(),c).reservations().get(0),c);
            var bound=gate;assertThrows(IllegalArgumentException.class,()->bound.allowed(0,0,0,tiny(),null));
            assertThrows(IllegalArgumentException.class,()->bound.allowed(0,0,0,input.profiles(),MetricRegion.rectangle(0,-1,1,1)));
            assertThrows(IllegalArgumentException.class,()->bound.allowed(2,0,0,input.profiles(),null));
        }
        var closed=gate;assertThrows(IllegalStateException.class,()->closed.allowed(0,0,0,input.profiles(),null));assertEquals(0,ledger.currentBytes());
    }
    @Test void clippedOrOpenHistoryCannotBecomeAFreePrefix() {
        var input=straight(new double[][]{{0},{0}},List.of(0.),List.of());var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var c=new Context(TraceBudgets.defaults(),CancellationProbe.NONE,owner);var h=reserve(input.rows(),input.links(),c).reservations().get(0);
            var clipped=new ProposalReservation(h.rowsOfAllowedOriginalIndices(),h.bandTrace(),h.gapRows(),Status.INCOMPLETE_CLIPPED,"clipped",h.certifiedLinks(),h.originalStateCounts());
            assertThrows(ReservedContinuityCounterfactual.Refusal.class,()->carrier(input,clipped,c));
        }finally{assertEquals(0,ledger.currentBytes());}
    }
    @Test void certifiedRotatingHairpinGapNeedsActualPatchesAndPreservesGapOwnership() {
        var input=hairpin();var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var c=new Context(TraceBudgets.defaults(),CancellationProbe.NONE,owner);var history=reserve(input.rows(),input.links(),c).reservations().get(0);assertEquals(Status.COMPLETE,history.status());
            assertEquals("UNKNOWN_ROTATED_FRAME",assertThrows(ReservedContinuityCounterfactual.Refusal.class,()->carrier(input,history,c)).getMessage());
            var gate=ReservedContinuityCounterfactual.prepare(input.rows(),history,input.profiles(),null,hairpinPatches(),c);
            for(int r=0;r<3;r++)assertTrue(gate.allowed(r,0,0,input.profiles(),null));
            assertEquals(List.of("UNSUPPORTED_GAP","UNSUPPORTED_GAP","UNSUPPORTED_GAP"),gate.provenance());
            assertEquals(ObservationOwnership.INFERRED_GAP,input.profiles().get(1).ownership());
            assertTrue(input.profiles().get(0).cells().get(0).exactAnchor());assertTrue(input.profiles().get(3).cells().get(0).exactAnchor());
        }finally{assertEquals(0,ledger.currentBytes());}
    }
    @Test void nonfactorableGapWitnessCannotSilentlyBecomeCrossProduct() {
        var input=straight(new double[][]{{-1,1},{},{-1,1}},List.of(-1.,1.),List.of());
        var link=new CertifiedLink(new BandRef(0,0),new BandRef(2,0),LinkKind.DIRECT_CONTINUATION,Set.of(new StatePair(0,0),new StatePair(1,1)),Map.of(1,Set.of(0,1)));
        var h=new ProposalReservation(List.of(List.of(0,1),List.of(0,1),List.of(0,1)),List.of(new BandRef(0,0),new BandRef(2,0)),Set.of(1),Status.COMPLETE,"fixture obligation",List.of(link),List.of(2,2,2));
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var c=new Context(TraceBudgets.defaults(),CancellationProbe.NONE,owner);
            assertEquals("INCOMPLETE_UNREPRESENTABLE_CERTIFICATE",assertThrows(ReservedContinuityCounterfactual.Refusal.class,()->carrier(input,h,c)).getMessage());
            assertEquals(0,ledger.currentBytes(),"Refused preparation retains no owned partial carrier");
        }finally{assertEquals(0,ledger.currentBytes());}
    }
    @Test void historicalNullGatePreservesNativeBitsAndOriginalRegionRemainsConjunctive() {
        var ledger=AttemptMemoryLedger.production();var p=tiny();var region=MetricRegion.rectangle(0,-.5,2,.5);
        try(var owner=ledger.rootOwner()) {
            var old=new ProbabilisticInference().solve(p,EvidenceModelParameters.defaults(),TraceBudgets.defaults(),null,CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4);
            var current=new ProbabilisticInference().solveReservedContinuity(p,EvidenceModelParameters.defaults(),TraceBudgets.defaults(),null,CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4,null);
            assertEquals(Double.doubleToRawLongBits(old.logPartition()),Double.doubleToRawLongBits(current.logPartition()));assertEquals(old.status(),current.status());
            assertEquals(old.evaluatedPairVisits(),current.evaluatedPairVisits());assertEquals(old.evaluatedTransitions(),current.evaluatedTransitions());assertEquals(old.workAccounting(),current.workAccounting());
            assertEquals(old.rawPaths().size(),current.rawPaths().size());
            for(int i=0;i<old.rawPaths().size();i++) {assertArrayEquals(old.rawPaths().get(i).stateIndices(),current.rawPaths().get(i).stateIndices());assertEquals(Double.doubleToRawLongBits(old.rawPaths().get(i).energy()),Double.doubleToRawLongBits(current.rawPaths().get(i).energy()));}
            for(int r=0;r<3;r++)assertArrayEquals(old.positionMarginals().get(r),current.positionMarginals().get(r));
            var cache=PreparedBKernel.prepare(p,EvidenceModelParameters.defaults(),region,CancellationProbe.NONE,owner,(r,a,b,ps,reg)->true);
            assertTrue(cache.allowed(0,0,0));assertFalse(cache.allowed(0,1,1),"Extra predicate never expands original authority");
        }finally{assertEquals(0,ledger.currentBytes());}
    }
    @Test void preparationCancellationWorkAndMemoryRefuseWithoutPartialOwner() {
        var input=straight(new double[][]{{0},{0}},List.of(0.),List.of());ProposalReservation history;
        var initial=AttemptMemoryLedger.production();try(var owner=initial.rootOwner()){history=reserve(input.rows(),input.links(),new Context(TraceBudgets.defaults(),CancellationProbe.NONE,owner)).reservations().get(0);}
        for(int failure=0;failure<3;failure++) {
            var ledger=new AttemptMemoryLedger(failure==2?32:AttemptMemoryLedger.MAX_BYTES);int kind=failure;
            try(var owner=ledger.rootOwner()) {
                var limits=kind==1?new TraceBudgets(96,1,1,32,8):TraceBudgets.defaults();
                var c=new Context(limits,()->kind==0,owner);
                Class<? extends RuntimeException> expected=kind==0?java.util.concurrent.CancellationException.class:kind==1?WorkRefusal.class:AttemptMemoryLedger.ResourceLimitException.class;
                assertThrows(expected,()->carrier(input,history,c));assertEquals(0,ledger.currentBytes());
            }finally{assertEquals(0,ledger.currentBytes());}
        }
    }
    @Test void publicTinyNativeLimitsAndCancellationLeaveNoUsablePartialResult() {
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var limited=new ProbabilisticInference().solveReservedContinuity(tiny(),EvidenceModelParameters.defaults(),new TraceBudgets(96,8_000_000,1,32,8),null,
                CancellationProbe.NONE,owner,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4,(r,a,b,p,region)->true);
            assertEquals(ProbabilisticInferenceResult.Status.RESOURCE_LIMIT,limited.status());assertTrue(limited.rawPaths().isEmpty());assertFalse(limited.posteriorUsable());
        }finally{assertEquals(0,ledger.currentBytes());}
        var cancelled=AttemptMemoryLedger.production();var checks=new java.util.concurrent.atomic.AtomicInteger();
        try(var owner=cancelled.rootOwner()) {
            assertThrows(java.util.concurrent.CancellationException.class,()->new ProbabilisticInference().solveReservedContinuity(tiny(),EvidenceModelParameters.defaults(),TraceBudgets.defaults(),null,
                ()->checks.incrementAndGet()>=4,owner,ProbabilisticExecutionPolicy.Mode.CACHED_EXACT_V4,(r,a,b,p,region)->true));
            assertEquals(0,cancelled.currentBytes());
        }finally{assertEquals(0,cancelled.currentBytes());}
    }
    @Test void invalidPatchIsRejectedAndForeignPatchCannotReplaceUncertifiedFrame() {
        assertThrows(IllegalArgumentException.class,()->new MetricRegion(List.of(List.of(new MetricPoint(0,0),new MetricPoint(1,1),new MetricPoint(0,1),new MetricPoint(1,0)))));
        var input=straight(new double[][]{{0},{0}},List.of(0.),List.of());var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var c=new Context(TraceBudgets.defaults(),CancellationProbe.NONE,owner);var h=reserve(input.rows(),input.links(),c).reservations().get(0);
            assertEquals("FOREIGN_PATCH_WITHOUT_CERTIFICATE",assertThrows(ReservedContinuityCounterfactual.Refusal.class,()->ReservedContinuityCounterfactual.prepare(input.rows(),h,input.profiles(),null,Map.of(0,MetricRegion.rectangle(0,-1,12,1)),c)).getMessage());
        }finally{assertEquals(0,ledger.currentBytes());}
    }
    @Test void overlapRenamingAndPhysicalReversalPreserveExactHistoryRuling() {
        for(boolean reverse:List.of(false,true)) {
            List<Row> rows=new ArrayList<>();List<InferenceProfile> profiles=new ArrayList<>();List<CertifiedLink> links=new ArrayList<>();
            for(int r=0;r<3;r++) {
                List<Band> bands=new ArrayList<>();for(int arm=0;arm<2;arm++) {double core=r==1?0:arm==0?-2:2;bands.add(new Band(new BandRef(r,100-arm),core,core,0,1,List.of(r==1?1:arm==0?0:2),BoundaryStatus.OBSERVED));}
                var anchor=new MetricPoint(reverse?12-r*6:r*6,0);var normal=new MetricPoint(0,reverse?-1:1);
                rows.add(new Row(r*6,anchor,normal,bands,List.of(-2.,0.,2.),OptionalInt.empty()));
                profiles.add(new InferenceProfile(r*6,anchor,normal,List.of(new LateralStateCell(-2,1,false,true,"foreign-name-z"),new LateralStateCell(0,1,false,true,"foreign-name-x"),new LateralStateCell(2,1,false,true,"foreign-name-y")),new double[3],ImageOrientationSupport.unknown(ImageOrientationSupport.Status.INSUFFICIENT_TWO_SIDED_SUPPORT),ObservationOwnership.DIRECT_TWO_SIDED,false));
            }
            links.add(port(0,100,100,0,1));links.add(port(0,99,99,2,1));links.add(port(1,100,100,1,0));links.add(port(1,99,99,1,2));
            var ledger=AttemptMemoryLedger.production();
            try(var owner=ledger.rootOwner()) {
                var c=new Context(TraceBudgets.defaults(),CancellationProbe.NONE,owner);var set=reserve(rows,links,c);assertEquals(2,set.reservations().size());
                for(var h:set.reservations()) {
                    var gate=ReservedContinuityCounterfactual.prepare(rows,h,profiles,null,Map.of(),c);
                    int flank=h.bandTrace().get(0).ordinal()==100?0:2;
                    assertTrue(gate.allowed(0,flank,1,profiles,null));assertTrue(gate.allowed(1,1,flank,profiles,null));
                    assertFalse(gate.allowed(0,0,1,profiles,null)&&gate.allowed(1,1,2,profiles,null));
                }
            }finally{assertEquals(0,ledger.currentBytes());}
        }
    }
}
