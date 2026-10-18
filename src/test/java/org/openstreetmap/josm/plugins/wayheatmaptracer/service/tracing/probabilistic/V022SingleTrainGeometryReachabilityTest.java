package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class V022SingleTrainGeometryReachabilityTest {
    private static MetricPoint p(double x,double y) {return new MetricPoint(x,y);}
    private static List<List<MetricPoint>> parallel() {
        return List.of(List.of(p(0,-2),p(120,-2)),List.of(p(0,2),p(120,2)));
    }
    @Test void supportedEndpointsNeverAuthorizeAnUnsupportedWholeSegment() {
        var ledger=new AttemptMemoryLedger(1_000_000);var owner=ledger.rootOwner();
        try {
            var oracle=new BSingleTrainGeometryReachability.Oracle(parallel(),.5,owner,CancellationProbe.NONE);
            assertTrue(oracle.allowed(p(40,-2),p(60,-2)));
            assertFalse(oracle.allowed(p(40,-2),p(60,2)));
            assertTrue(oracle.allowed(p(0,0),p(20,0)),"outside selector is unrestricted");
        } finally {owner.close();}
        assertEquals(0,ledger.currentBytes());
    }
    @Test void deterministicWitnessUsesOriginalCellsAndHardAdmission() {
        var rows=List.of(List.of(p(0,0)),List.of(p(40,0),p(40,-2)),List.of(p(80,-2),p(80,2)),List.of(p(120,0)));
        var ledger=new AttemptMemoryLedger(1_000_000);var owner=ledger.rootOwner();
        try {
            var oracle=new BSingleTrainGeometryReachability.Oracle(parallel(),.5,owner,CancellationProbe.NONE);
            var result=BSingleTrainGeometryReachability.search(rows,MetricRegion.rectangle(-1,-3,121,3),oracle,owner,CancellationProbe.NONE,100);
            assertEquals(BSingleTrainGeometryReachability.Status.WITNESS,result.status());
            assertEquals(List.of(0,1,0,0),result.states());
        } finally {owner.close();}
        assertEquals(0,ledger.currentBytes());
    }
    @Test void completeNoRouteAndLimitedSearchAreDifferent() {
        var rows=List.of(List.of(p(0,0)),List.of(p(40,0)),List.of(p(80,0)),List.of(p(120,0)));
        var ledger=new AttemptMemoryLedger(1_000_000);var owner=ledger.rootOwner();
        try {
            var oracle=new BSingleTrainGeometryReachability.Oracle(parallel(),.5,owner,CancellationProbe.NONE);
            assertEquals(BSingleTrainGeometryReachability.Status.EXHAUSTIVE_ABSENCE,
                    BSingleTrainGeometryReachability.search(rows,null,oracle,owner,CancellationProbe.NONE,100).status());
            assertEquals(BSingleTrainGeometryReachability.Status.INCOMPLETE_UNKNOWN,
                    BSingleTrainGeometryReachability.search(rows,null,oracle,owner,CancellationProbe.NONE,0).status());
        } finally {owner.close();}
        assertEquals(0,ledger.currentBytes());
    }
    private static BIdentificationCatalog.Scene scene(List<List<MetricPoint>> curves) throws Exception {
        var toy=V022SingleTrainDiagnosticBridgeTest.toy();
        return new BIdentificationCatalog.Scene(toy.evidence(),toy.source(),new BIdentificationCatalog.Truth(List.of(
                new BIdentificationCatalog.LabelRegion(MetricRegion.rectangle(-1,-4,121,4),
                        BIdentificationCatalog.StructureLabel.OBSERVED_STRUCTURE,BIdentificationCatalog.RasterLabel.VALID,
                        curves.size()==1?BIdentificationCatalog.AssociationLabel.UNIQUE:BIdentificationCatalog.AssociationLabel.AMBIGUOUS,curves))));
    }
    private static boolean actual(BIdentificationCatalog.Scene scene,List<MetricPoint> path,AttemptMemoryLedger.Owner owner) {
        return BIdentificationAcceptance.geometry(BIdentificationAcceptance.GeometrySelector.PARALLEL_SEGMENTS_X40_80,
                scene,path,.5,CancellationProbe.NONE,owner).passed();
    }
    @Test void cachedEdgeOracleMatchesActualWholeOracleAcrossBoundariesAndOverlappingTubes() throws Exception {
        for(var curves:List.of(parallel(),List.of(List.of(p(0,0),p(120,0)),List.of(p(0,.5),p(120,.5))),
                List.of(List.of(p(0,0),p(60,1),p(120,0))))) {
            var scene=scene(curves);var ledger=new AttemptMemoryLedger(2_000_000);var owner=ledger.rootOwner();
            try {
                var oracle=new BSingleTrainGeometryReachability.Oracle(curves,.5,owner,CancellationProbe.NONE);
                for(double x:new double[]{0,40,50,80,120})for(double y:new double[]{-2.5,-2,0,.5,1,2,2.5})
                    for(double otherX:new double[]{40,60,80})for(double otherY:new double[]{-2,0,2}) {
                        var a=p(x,y);var b=p(otherX,otherY);
                        boolean expected=BSingleTrainDiagnosticBridge.clip(a,b).isEmpty()||actual(scene,List.of(a,b),owner);
                        assertEquals(expected,oracle.allowed(a,b),a+" -> "+b);
                    }
            } finally {owner.close();}
            assertEquals(0,ledger.currentBytes());
        }
    }
    private static boolean brute(List<List<MetricPoint>> rows,int depth,List<MetricPoint> path,
            BIdentificationCatalog.Scene scene,MetricRegion hard,AttemptMemoryLedger.Owner owner) {
        if(depth==rows.size())return actual(scene,path,owner);
        for(var point:rows.get(depth)) {
            if(!path.isEmpty()&&hard!=null&&!hard.containsSegment(path.get(path.size()-1),point))continue;
            path.add(point);boolean found=brute(rows,depth+1,path,scene,hard,owner);path.remove(path.size()-1);
            if(found)return true;
        }
        return false;
    }
    @Test void reachableWitnessAndExhaustiveAbsenceAgreeWithIndependentTinyBruteForce() throws Exception {
        var curves=parallel();var scene=scene(curves);var ledger=new AttemptMemoryLedger(2_000_000);var owner=ledger.rootOwner();
        try {
            var oracle=new BSingleTrainGeometryReachability.Oracle(curves,.5,owner,CancellationProbe.NONE);
            for(int first=1;first<8;first++)for(int second=1;second<8;second++) {
                var a=new ArrayList<MetricPoint>();var b=new ArrayList<MetricPoint>();
                for(int s=0;s<3;s++){if((first&(1<<s))!=0)a.add(p(40,2*s-2));if((second&(1<<s))!=0)b.add(p(80,2*s-2));}
                var rows=List.of(List.of(p(0,0)),a,b,List.of(p(120,0)));
                for(var hard:Arrays.asList(null,MetricRegion.rectangle(-1,-3,121,3),MetricRegion.rectangle(-1,-.1,121,3))) {
                    boolean expected=brute(rows,0,new ArrayList<>(),scene,hard,owner);
                    var result=BSingleTrainGeometryReachability.search(rows,hard,oracle,owner,CancellationProbe.NONE,100);
                    assertEquals(expected?BSingleTrainGeometryReachability.Status.WITNESS:BSingleTrainGeometryReachability.Status.EXHAUSTIVE_ABSENCE,result.status());
                    if(expected){var path=new ArrayList<MetricPoint>();for(int i=0;i<rows.size();i++)path.add(rows.get(i).get(result.states().get(i)));assertTrue(actual(scene,path,owner));}
                }
            }
        } finally {owner.close();}
        assertEquals(0,ledger.currentBytes());
    }
    @Test void cancellationAndMemoryRefusalNeverClaimAbsenceAndReleaseTheirSearchPayload() {
        var ledger=new AttemptMemoryLedger(100_000);var owner=ledger.rootOwner();
        try {
            var oracle=new BSingleTrainGeometryReachability.Oracle(parallel(),.5,owner,CancellationProbe.NONE);
            var rows=List.of(List.of(p(0,0)),List.of(p(40,-2)),List.of(p(80,-2)),List.of(p(120,0)));
            long before=ledger.currentBytes();
            assertEquals(BSingleTrainGeometryReachability.Status.INCOMPLETE_UNKNOWN,
                    BSingleTrainGeometryReachability.search(rows,null,oracle,owner,()->true,100).status());
            assertEquals(before,ledger.currentBytes());
            try(var filler=owner.reserve(ledger.limitBytes()-ledger.currentBytes())) {
                assertEquals(BSingleTrainGeometryReachability.Status.INCOMPLETE_UNKNOWN,
                        BSingleTrainGeometryReachability.search(rows,null,oracle,owner,CancellationProbe.NONE,100).status());
            }
            assertEquals(before,ledger.currentBytes());
            var outside=List.of(List.of(p(0,0)),List.of(p(10,0)));
            assertEquals(BSingleTrainGeometryReachability.Status.EXHAUSTIVE_ABSENCE,
                    BSingleTrainGeometryReachability.search(outside,null,oracle,owner,CancellationProbe.NONE,100).status(),"no selector is not a witness");
        } finally {owner.close();}
        assertEquals(0,ledger.currentBytes());
    }
    @Test void originalHardAdmissionRejectsDisconnectedSupportedEndpointsAndPartialExpansionStaysUnknown() {
        var ledger=new AttemptMemoryLedger(100_000);var owner=ledger.rootOwner();
        try {
            var oracle=new BSingleTrainGeometryReachability.Oracle(parallel(),.5,owner,CancellationProbe.NONE);
            var rows=List.of(List.of(p(0,0)),List.of(p(40,-2)),List.of(p(80,-2)),List.of(p(120,0)));
            var hard=new MetricRegion(List.of(MetricRegion.rectangle(-1,-3,51,3).polygons().get(0),
                    MetricRegion.rectangle(69,-3,121,3).polygons().get(0)));
            assertTrue(hard.contains(rows.get(1).get(0)));assertTrue(hard.contains(rows.get(2).get(0)));
            assertFalse(hard.containsSegment(rows.get(1).get(0),rows.get(2).get(0)));
            assertEquals(BSingleTrainGeometryReachability.Status.EXHAUSTIVE_ABSENCE,
                    BSingleTrainGeometryReachability.search(rows,hard,oracle,owner,CancellationProbe.NONE,100).status());
            var partial=BSingleTrainGeometryReachability.search(rows,null,oracle,owner,CancellationProbe.NONE,2);
            assertEquals(BSingleTrainGeometryReachability.Status.INCOMPLETE_UNKNOWN,partial.status());assertEquals(2,partial.examinedPairs());
        } finally {owner.close();}
        assertEquals(0,ledger.currentBytes());
    }
}
