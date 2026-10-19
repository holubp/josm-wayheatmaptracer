package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022SingleTrainComparatorRegionTest {
    static MetricRegion union(){return new MetricRegion(List.of(MetricRegion.rectangle(0,-.5,120,.5).polygons().get(0),MetricRegion.rectangle(0,3.5,120,4.5).polygons().get(0)));}
    static MetricRegion hard(){return MetricRegion.rectangle(-1,-10,121,10);}
    @Test void verticalSelectorBoundaryMismatchCannotInvokeSolver() throws Exception {
        for(double x:List.of(40d,80d)) {
            var ledger=new AttemptMemoryLedger(2_000_000);var calls=new AtomicInteger();
            try(var owner=ledger.rootOwner()) {
                var rows=List.of(List.of(new MetricPoint(x,0)),List.of(new MetricPoint(x,4)));
                var candidate=BSingleTrainComparatorRegion.candidate(rows,union().polygons(),owner);
                assertTrue(candidate.containsSegment(rows.get(0).get(0),rows.get(1).get(0)));
                assertFalse(union().containsSegment(rows.get(0).get(0),rows.get(1).get(0)));
                assertThrows(SecurityException.class,()->BSingleTrainComparatorRegion.certify(rows,hard(),candidate,union(),
                        new BSingleTrainComparatorRegion.Work(1,1_000_000),owner,CancellationProbe.NONE).solveOnce(r->{calls.incrementAndGet();return null;}));
                assertEquals(0,calls.get());
            }
            assertEquals(0,ledger.currentBytes());
        }
    }
    @Test void disconnectedOriginalHardRegionCannotBeErasedByOutsideRectangle() throws Exception {
        var original=new MetricRegion(List.of(MetricRegion.rectangle(-1,-1,10,1).polygons().get(0),MetricRegion.rectangle(20,-1,121,1).polygons().get(0)));
        var rows=List.of(List.of(new MetricPoint(0,0)),List.of(new MetricPoint(30,0)));var ledger=new AttemptMemoryLedger(2_000_000);var calls=new AtomicInteger();
        try(var owner=ledger.rootOwner()) {
            assertTrue(original.contains(rows.get(0).get(0)));assertTrue(original.contains(rows.get(1).get(0)));assertFalse(original.containsSegment(rows.get(0).get(0),rows.get(1).get(0)));
            var candidate=BSingleTrainComparatorRegion.candidate(rows,union().polygons(),owner);
            assertThrows(SecurityException.class,()->BSingleTrainComparatorRegion.certify(rows,original,candidate,union(),
                    new BSingleTrainComparatorRegion.Work(1,1_000_000),owner,CancellationProbe.NONE).solveOnce(r->{calls.incrementAndGet();return null;}));
            assertEquals(0,calls.get());
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void touchingCrossingOverlapAndEveryOrderedPairUseActualWholeSegmentAnswers() throws Exception {
        var ledger=new AttemptMemoryLedger(2_000_000);var calls=new AtomicInteger();
        try(var owner=ledger.rootOwner()) {
            var touch=List.of(List.of(new MetricPoint(0,2)),List.of(new MetricPoint(40,2)));
            assertTrue(BSingleTrainDiagnosticBridge.clip(touch.get(0).get(0),touch.get(1).get(0)).isEmpty());
            assertFalse(union().containsSegment(touch.get(0).get(0),touch.get(1).get(0)),"unsupported exterior is intentionally not constrained by the original selector");
            var touchCandidate=BSingleTrainComparatorRegion.candidate(touch,union().polygons(),owner);var touchWork=new BSingleTrainComparatorRegion.Work(1,1_000_000);
            var gate=BSingleTrainComparatorRegion.certify(touch,hard(),touchCandidate,union(),touchWork,owner,CancellationProbe.NONE);
            assertSame(touchCandidate,gate.solveOnce(r->{calls.incrementAndGet();return r;}));assertEquals(1,calls.get());
            assertThrows(IllegalStateException.class,()->gate.solveOnce(r->{calls.incrementAndGet();return r;}));assertEquals(1,calls.get());
            var rows=List.of(List.of(new MetricPoint(39,0),new MetricPoint(39,4)),List.of(new MetricPoint(60,0),new MetricPoint(60,4)),List.of(new MetricPoint(81,0),new MetricPoint(81,4)));
            var candidate=BSingleTrainComparatorRegion.candidate(rows,union().polygons(),owner);var work=new BSingleTrainComparatorRegion.Work(8,1_000_000);
            BSingleTrainComparatorRegion.certify(rows,hard(),candidate,union(),work,owner,CancellationProbe.NONE);assertTrue(work.complete);assertEquals(8,work.pairs);
            assertTrue(work.units>0);assertEquals(16,work.noHookSegmentQueries);assertEquals(8,work.clips);assertTrue(((String)work.fields().get("orderedAnswerSha256")).matches("[0-9a-f]{64}"));
            for(int i=1;i<3;i++)for(var a:rows.get(i-1))for(var b:rows.get(i))assertEquals(a.yMeters()==b.yMeters(),candidate.contains(a)&&candidate.contains(b)&&candidate.containsSegment(a,b));
            var again=new BSingleTrainComparatorRegion.Work(8,1_000_000);BSingleTrainComparatorRegion.certify(rows,hard(),candidate,union(),again,owner,CancellationProbe.NONE);
            assertEquals(work.fields(),again.fields());
            var overlap=new MetricRegion(List.of(MetricRegion.rectangle(0,-.5,120,.5).polygons().get(0),MetricRegion.rectangle(0,.25,120,1.25).polygons().get(0)));
            var overlapRows=List.of(List.of(new MetricPoint(39,0)),List.of(new MetricPoint(81,1)));
            assertTrue(overlap.containsSegment(overlapRows.get(0).get(0),overlapRows.get(1).get(0)));
            BSingleTrainComparatorRegion.certify(overlapRows,hard(),BSingleTrainComparatorRegion.candidate(overlapRows,overlap.polygons(),owner),overlap,new BSingleTrainComparatorRegion.Work(1,1_000_000),owner,CancellationProbe.NONE);
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void holesBudgetsCancellationClosedOwnersAndFixedGraphRefuseWithoutCallback() throws Exception {
        var rows=List.of(List.of(new MetricPoint(39,0)),List.of(new MetricPoint(60,0)),List.of(new MetricPoint(81,0)));var ledger=new AttemptMemoryLedger(2_000_000);
        try(var owner=ledger.rootOwner()) {
            var candidate=BSingleTrainComparatorRegion.candidate(rows,union().polygons(),owner);
            var work=new BSingleTrainComparatorRegion.Work(1,1_000_000);
            assertThrows(BObservationIdentificationTool.RunLimitException.class,()->BSingleTrainComparatorRegion.certify(rows,hard(),candidate,union(),work,owner,CancellationProbe.NONE));assertFalse(work.complete);assertEquals(1,work.pairs);
            var units=new BSingleTrainComparatorRegion.Work(2,1);
            assertThrows(BObservationIdentificationTool.RunLimitException.class,()->BSingleTrainComparatorRegion.certify(rows,hard(),candidate,union(),units,owner,CancellationProbe.NONE));assertEquals(1,units.refusedCharges);assertTrue(units.units<=1);
            var cancel=new BSingleTrainComparatorRegion.Work(2,1_000_000);
            assertThrows(java.util.concurrent.CancellationException.class,()->BSingleTrainComparatorRegion.certify(rows,hard(),candidate,union(),cancel,owner,()->true));assertEquals(0,cancel.pairs);
            assertThrows(SecurityException.class,()->BSingleTrainComparatorRegion.requireFixedGraph(V022SingleTrainCostObjectiveTest.graph(false),rows));
            var hole=new MetricRegion(List.of(MetricRegion.rectangle(0,-1,121,-.2).polygons().get(0),MetricRegion.rectangle(0,.2,121,1).polygons().get(0),
                    MetricRegion.rectangle(0,-1,50,1).polygons().get(0),MetricRegion.rectangle(70,-1,121,1).polygons().get(0)));
            assertFalse(hole.containsSegment(new MetricPoint(39,0),new MetricPoint(81,0)));
            assertThrows(SecurityException.class,()->BSingleTrainComparatorRegion.certify(List.of(rows.get(0),rows.get(2)),hole,candidate,union(),new BSingleTrainComparatorRegion.Work(1,1_000_000),owner,CancellationProbe.NONE));
        }
        assertEquals(0,ledger.currentBytes());
        var tiny=new AttemptMemoryLedger(1);var closed=tiny.rootOwner();
        assertThrows(RuntimeException.class,()->BSingleTrainComparatorRegion.candidate(rows,union().polygons(),closed));assertEquals(0,tiny.currentBytes());closed.close();
        assertThrows(IllegalStateException.class,()->BSingleTrainComparatorRegion.certify(rows,hard(),union(),union(),new BSingleTrainComparatorRegion.Work(2,100),closed,CancellationProbe.NONE));
    }
    @Test void actualIndexedAndFallbackAdmissionVariantsMatchCallbackGeometryAtBoundariesAndHoles() {
        var ledger=new AttemptMemoryLedger(4_000_000);
        try(var owner=ledger.rootOwner()){for(double endX:List.of(40d,60d,80d)) {
            var profiles=new ArrayList<InferenceProfile>();
            for(int i=0;i<2;i++){var cells=new ArrayList<LateralStateCell>();for(double y:List.of(0d,1d,3d,4d))cells.add(new LateralStateCell(y,1,false,true,"s"+y));
                profiles.add(new InferenceProfile(i,new MetricPoint(i==0?40:endX,0),new MetricPoint(0,1),cells,new double[4],List.of(),0,ObservationOwnership.NO_RASTER,true));}
            for(var region:List.of(hard(),union(),new MetricRegion(List.of(MetricRegion.rectangle(0,-1,49,5).polygons().get(0),MetricRegion.rectangle(51,-1,121,5).polygons().get(0))))) {
                var indexed=IndexedGeometryAdmissions.createIfEligible(profiles,region,16,owner);assertNotNull(indexed);var fallback=new TransitionAdmissionMemo(32,owner);
                for(int a=0;a<4;a++)for(int b=0;b<4;b++) {
                    var start=profiles.get(0).point(a);var end=profiles.get(1).point(b);boolean actual=region.contains(start)&&region.contains(end)&&region.containsSegment(start,end);
                    assertEquals(actual,indexed.allowed(0,a,b,profiles,region));assertEquals(actual,fallback.allowed(0,a,b,profiles,region));
                    assertEquals(region.containsSegment(start,end),region.containsSegment(start,end,CancellationProbe.NONE,n->{},owner));
                }
            }
        }
        }
        assertEquals(0,ledger.currentBytes());
    }
    @Test void fixedGraphGateAcceptsOnlyExactDeclaredCoordinateInventory() {
        var profiles=new ArrayList<InferenceProfile>();var rows=new ArrayList<List<MetricPoint>>();
        for(int i=0;i<81;i++){var cells=new ArrayList<LateralStateCell>();for(int s=0;s<(i==0||i==80?1:36);s++)cells.add(new LateralStateCell(s,1,i==0||i==80,true,"s"+s));
            var p=new InferenceProfile(1.5*i,new MetricPoint(1.5*i,0),new MetricPoint(0,1),cells,new double[cells.size()],List.of(),0,ObservationOwnership.NO_RASTER,true);profiles.add(p);
            var points=new ArrayList<MetricPoint>();for(int s=0;s<cells.size();s++)points.add(p.point(s));rows.add(points);}
        assertDoesNotThrow(()->BSingleTrainComparatorRegion.requireFixedGraph(profiles,rows));rows.get(20).set(3,new MetricPoint(40,3));
        assertThrows(SecurityException.class,()->BSingleTrainComparatorRegion.requireFixedGraph(profiles,rows));
    }
}
