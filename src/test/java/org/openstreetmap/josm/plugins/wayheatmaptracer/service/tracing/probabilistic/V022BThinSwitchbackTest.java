package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.*;
import java.nio.file.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class V022BThinSwitchbackTest {
    private static MetricPoint p(double x,double y){return new MetricPoint(x,y);}
    private static BThinSwitchbackAcceptance.Result evaluate(List<MetricPoint> path) {
        var ledger=new AttemptMemoryLedger(1024*1024);
        try(var owner=ledger.rootOwner()){return BThinSwitchbackAcceptance.evaluate(u(),path,1,CancellationProbe.NONE,owner);}
        finally {assertEquals(0,ledger.currentBytes());}
    }
    /** Independent analytic U is not either live fixture. */
    private static List<MetricPoint> u() {
        var points=new ArrayList<MetricPoint>();points.add(p(0,20));
        for(int k=0;k<=8;k++){double angle=-StrictMath.PI/2+k*StrictMath.PI/8;
            points.add(k==0?p(24,20):k==8?p(24,28):p(24+4*StrictMath.cos(angle),24+4*StrictMath.sin(angle)));}
        points.add(p(0,28));return points;
    }
    @Test void genuineSchematicHasTwoUnevenTurnsDistinctNativeStrandsAndFrozenFrame() throws Exception {
        var truth=BThinSwitchbackFixtures.source();assertEquals(List.of(p(0,0),p(9,4),p(19,12),p(24,16),p(25,19),p(20,20),
                p(11,18),p(4,17),p(1,20),p(5,25),p(14,27),p(23,29)),truth);
        assertTrue(BThinSwitchbackFixtures.length()>80&&BThinSwitchbackFixtures.length()<90);
        assertEquals(p(-25,-20),BThinSwitchbackFixtures.origin());assertEquals(2,BThinSwitchbackFixtures.fragments().size());
        double[] strong=BThinSwitchbackFixtures.byId(BThinSwitchbackFixtures.STRONG).scalar();
        double[] faint=BThinSwitchbackFixtures.byId(BThinSwitchbackFixtures.FAINT).scalar();
        for(String id:BThinSwitchbackFixtures.IDS){var f=BThinSwitchbackFixtures.byId(id);var v=f.scalar();var valid=f.validity();
            assertEquals(6561,v.length);assertEquals(6561,valid.length);for(boolean bit:valid)assertTrue(bit);
            assertEquals(.02+f.amplitude(),v[20*81+25]);assertEquals(.02+1.25*f.amplitude(),v[39*81+50]);
            assertEquals(.02+.5*f.amplitude(),v[24*81+39]);assertEquals(.02,v[31*81+35],"observed gap not interpolated blur");
            for(double sample:v)assertTrue(Double.isFinite(sample)&&sample>=0&&sample<=1);
            v[0]=99;valid[0]=false;assertEquals(.02,f.scalar()[0]);assertTrue(f.validity()[0]);
        }
        for(int i=0;i<strong.length;i++){assertEquals(strong[i]>.02,faint[i]>.02);assertEquals((strong[i]-.02)/.70,(faint[i]-.02)/.08,1e-14);}
        try(var resource=getClass().getResourceAsStream("/b-observed-gap/thin-switchback-controls.json")) {
            assertNotNull(resource);assertEquals("b3cf926005e3203ae60f8f417f49e5eca79bff291a8cf8b4730df2634b29964f",BIdentificationReceipt.hash(resource.readAllBytes()));
        }
    }
    @Test void finalSchematicRetainsBothTurnsAndRejectsWrongLegBacktrackingAndApexLoss() {
        var truth=BThinSwitchbackFixtures.source();var ledger=new AttemptMemoryLedger(1024*1024);
        try(var owner=ledger.rootOwner()) {
            assertTrue(BThinSwitchbackAcceptance.evaluate(truth,truth,1,CancellationProbe.NONE,owner).passed());
            for(int missing:new int[]{4,8}){var shortcut=new ArrayList<>(truth);shortcut.remove(missing);
                assertFalse(BThinSwitchbackAcceptance.evaluate(truth,shortcut,1,CancellationProbe.NONE,owner).passed());}
            var jumped=new ArrayList<>(truth.subList(0,3));jumped.addAll(truth.subList(6,truth.size()));
            assertFalse(BThinSwitchbackAcceptance.evaluate(truth,jumped,1,CancellationProbe.NONE,owner).passed());
            var back=new ArrayList<>(truth.subList(0,3));back.add(truth.get(1));back.addAll(truth.subList(2,truth.size()));
            assertFalse(BThinSwitchbackAcceptance.evaluate(truth,back,1,CancellationProbe.NONE,owner).passed());
            var lost=new ArrayList<>(truth);lost.set(4,p(24,18));assertFalse(BThinSwitchbackAcceptance.evaluate(truth,lost,1,CancellationProbe.NONE,owner).passed());
        }assertEquals(0,ledger.currentBytes());
    }
    @Test void nativeViewerExportIsExactAndEveryLeafRemainsBounded() throws Exception {
        Path directory=Path.of("build/gap-switchback-matrix");BThinSwitchbackFixtures.exportNative(directory);
        var metadata=BIdentificationReceipt.parse(Files.readAllBytes(directory.resolve("native-fixtures.json")));
        assertEquals("thin-switchback-native-v1",metadata.get("schema"));assertEquals(2,((List<?>)metadata.get("fixtures")).size());
        for(String id:BThinSwitchbackFixtures.IDS) {
            var fixture=BThinSwitchbackFixtures.byId(id);byte[] bytes=Files.readAllBytes(directory.resolve(id+".f64")),mask=Files.readAllBytes(directory.resolve(id+".valid"));
            assertEquals(52488,bytes.length);assertEquals(6561,mask.length);var buffer=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            for(double value:fixture.scalar())assertEquals(Double.doubleToRawLongBits(value),Double.doubleToRawLongBits(buffer.getDouble()));
            for(byte value:mask)assertEquals(1,value);
        }
        try(var leaves=Files.list(directory)){for(var leaf:leaves.toList())assertTrue(Files.size(leaf)<=65536);}
    }
    @Test void trueRouteAndEquivalentSubdivisionPassWithoutWorldXOrder() {
        var truth=u();var result=evaluate(truth);assertTrue(result.passed());assertTrue(result.cells()>0);
        var subdivided=new ArrayList<MetricPoint>();subdivided.add(truth.get(0));
        for(int i=1;i<truth.size();i++){var a=truth.get(i-1);var b=truth.get(i);subdivided.add(p((a.xMeters()+b.xMeters())/2,(a.yMeters()+b.yMeters())/2));subdivided.add(b);}
        assertTrue(evaluate(subdivided).passed());
    }
    @Test void wholeEdgeShortcutAndWrongLegJumpFailDespiteSupportedEndpoints() {
        var truth=u();assertFalse(evaluate(List.of(truth.get(0),truth.get(10))).passed());
        assertFalse(evaluate(List.of(p(0,20),p(24,20),p(24,28),p(0,28))).passed(),"turn chord crosses unsupported interior");
        assertFalse(evaluate(List.of(p(0,20),p(16,20),p(16,28),p(0,28))).passed(),"wrong-leg jump before real turn");
    }
    @Test void backwardsTraversalCannotPassEvenWhileEveryPointStaysOnSupportedRoute() {
        var truth=u();var path=new ArrayList<MetricPoint>();
        path.add(p(0,20));path.add(p(20,20));path.add(p(12,20));path.addAll(truth.subList(1,truth.size()));
        assertFalse(evaluate(path).orderedWholePath());
        var reversed=new ArrayList<>(truth);Collections.reverse(reversed);assertFalse(evaluate(reversed).passed());assertFalse(evaluate(reversed).anchors());
    }
    @Test void missingWrongPitchWrongAnchorAndDegeneratePathAreUnavailableOrFail() {
        assertFalse(evaluate(null).available());assertFalse(evaluate(List.of()).available());
        var truth=new ArrayList<>(u());truth.set(0,p(.01,20));assertFalse(evaluate(truth).anchors());
        var ledger=new AttemptMemoryLedger(1024*1024);try(var owner=ledger.rootOwner()) {
            assertFalse(BThinSwitchbackAcceptance.evaluate(u(),u(),.5,CancellationProbe.NONE,owner).available());
        }
        var duplicates=new ArrayList<>(u());duplicates.add(1,duplicates.get(0));assertTrue(evaluate(duplicates).passed());
    }
    @Test void subMetreJitterAndExactTubeBoundaryPassButExteriorDoesNot() {
        var truth=u();var jitter=new ArrayList<MetricPoint>();
        jitter.add(p(0,20));jitter.add(p(10,20));jitter.add(p(9.5,20));jitter.addAll(truth.subList(1,truth.size()));
        assertTrue(evaluate(jitter).passed(),"continuous progress may pause during harmless subpixel jitter");
        var boundary=new ArrayList<MetricPoint>();boundary.add(p(0,20));boundary.add(p(0,21));boundary.add(p(24,21));boundary.addAll(truth.subList(1,truth.size()));
        assertTrue(evaluate(boundary).passed(),"closed one-metre whole-edge boundary");
        var exterior=new ArrayList<MetricPoint>();exterior.add(p(0,20));exterior.add(p(12,21.000001));exterior.addAll(truth.subList(1,truth.size()));
        assertFalse(evaluate(exterior).passed());
    }
    @Test void cancellationAndBudgetRefusalLeaveOwnerTreeAndBytesUnchanged() throws Exception {
        var ledger=new AttemptMemoryLedger(1024*1024);try(var owner=ledger.rootOwner()) {
            var tree=BGapTestFixture.ownerTree(owner);assertThrows(CancellationException.class,()->BThinSwitchbackAcceptance.evaluate(u(),u(),1,()->true,owner));
            assertEquals(0,owner.currentBytes());assertEquals(tree,BGapTestFixture.ownerTree(owner));
            try(var held=owner.reserve(ledger.limitBytes()-10)){
                assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->BThinSwitchbackAcceptance.evaluate(u(),u(),1,CancellationProbe.NONE,owner));
            }
            assertEquals(0,owner.currentBytes());assertEquals(tree,BGapTestFixture.ownerTree(owner));
        }assertEquals(0,ledger.currentBytes());
    }
}
