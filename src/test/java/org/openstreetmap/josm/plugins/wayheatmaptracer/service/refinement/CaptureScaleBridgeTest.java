package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class CaptureScaleBridgeTest {
    @Test void captureRequestExposesCallerSharedWorkContext() {
        assertTrue(Arrays.stream(LongScaleNormalFairing.class.getMethods())
                .filter(method -> method.getName().equals("requestCaptureScale"))
                .anyMatch(method -> Arrays.asList(method.getParameterTypes()).contains(GeometryWorkContext.class)),
                "capture request must accept the caller's shared work context");
    }

    private static LongScaleNormalFairing.CaptureRequest request(int k, double gain, boolean reverse, int stride, boolean protect) {
        var points = new ArrayList<MetricPoint>(); var rows = new ArrayList<FinalControlBasis.Row>();
        for (int i = 0; i <= 40; i += stride) {
            int x = reverse ? 40-i : i;
            points.add(new MetricPoint(x, Math.sin(x * Math.PI / 2)));
            rows.add(FinalControlBasis.Row.independent(rows.size()));
        }
        int end = points.size()-1;
        var basis = new FinalControlBasis(points, rows, protect ? Set.of(0,end/2,end) : Set.of(0,end));
        var frame = RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);
        var policy = new CaptureScaleFairingPolicy("a".repeat(64), "evidence", 100,100,frame,k,gain);
        return new LongScaleNormalFairing.CaptureRequest(basis,policy,8_000_000,256L*1024*1024);
    }
    @Test void bridgePreservesPrototypeAndRetainsOnlyItsOwnOutput() {
        for (int k : List.of(1,2)) for (double gain : List.of(0.,1.))
            for (boolean reverse : List.of(false,true)) for (int stride : List.of(1,2))
                for (boolean protect : List.of(false,true)) {
            var request=request(k,gain,reverse,stride,protect);
            var expected=LongScaleNormalFairing.requestCaptureScale(request,CancellationProbe.NONE);
            var ledger=new AttemptMemoryLedger(8_000_000); var owner=ledger.rootOwner();
            try (var sentinel=owner.reserve(32)) {
                var context=new GeometryWorkContext(8_000_000,8_000_000,Long.MAX_VALUE,CancellationProbe.NONE,owner);
                try (var actual=LongScaleNormalFairing.requestCaptureScale(request,CancellationProbe.NONE,context)) {
                    assertEquals(expected,actual.result()); assertFalse(actual.result().isApplicable());
                    assertTrue(context.visits() >= expected.workVisits()); assertTrue(ledger.currentBytes()>32);
                }
                assertEquals(32,ledger.currentBytes());
            }
            assertEquals(0,ledger.currentBytes()); owner.close();
        }
    }
    @Test void callerLimitsDeadlineAndCancellationInterruptWithoutReleasingSentinel() {
        var request=request(1,1,false,1,false); var ledger=new AttemptMemoryLedger(8_000_000); var owner=ledger.rootOwner();
        try (var sentinelReservation=owner.reserve(32); var sentinel=sentinelReservation.adopt(new Object())) {
            assertThrows(GeometryWorkContext.LimitReached.class, () -> LongScaleNormalFairing.requestCaptureScale(request,CancellationProbe.NONE,
                    new GeometryWorkContext(0,8_000_000,Long.MAX_VALUE,CancellationProbe.NONE,owner)));
            var tiny = new GeometryWorkContext(1000,8_000_000,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            assertThrows(GeometryWorkContext.LimitReached.class, () -> LongScaleNormalFairing.requestCaptureScale(request,CancellationProbe.NONE,tiny));
            assertTrue(tiny.visits() > 0); assertTrue(tiny.visits() <= 1000);
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class, () -> LongScaleNormalFairing.requestCaptureScale(request,CancellationProbe.NONE,
                    new GeometryWorkContext(8_000_000,33,Long.MAX_VALUE,CancellationProbe.NONE,owner)));
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class, () -> LongScaleNormalFairing.requestCaptureScale(request,CancellationProbe.NONE,
                    new GeometryWorkContext(8_000_000,0,Long.MAX_VALUE,CancellationProbe.NONE,owner)));
            assertThrows(GeometryWorkContext.LimitReached.class, () -> LongScaleNormalFairing.requestCaptureScale(request,CancellationProbe.NONE,
                    new GeometryWorkContext(8_000_000,8_000_000,0,CancellationProbe.NONE,owner)));
            var count=new AtomicInteger();
            var context=new GeometryWorkContext(8_000_000,8_000_000,Long.MAX_VALUE,()->count.incrementAndGet()>100,owner);
            assertThrows(CancellationException.class, () -> LongScaleNormalFairing.requestCaptureScale(request,CancellationProbe.NONE,context));
            assertTrue(context.visits()>0); assertEquals(32,ledger.currentBytes());
        }
        owner.close();
    }
    @Test void dependentOffLineControlLiftMatchesPrototype() {
        var points=List.of(new MetricPoint(0,0),new MetricPoint(3,1),new MetricPoint(5,2),new MetricPoint(10,0));
        var rows=List.of(FinalControlBasis.Row.independent(0),FinalControlBasis.Row.dependent(1,0,2,.6),
                FinalControlBasis.Row.independent(2),FinalControlBasis.Row.independent(3));
        var basis=new FinalControlBasis(points,rows,Set.of(0,3));
        var frame=RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);
        var policy=new CaptureScaleFairingPolicy("a".repeat(64),"evidence",100,100,frame,2,1);
        var request=new LongScaleNormalFairing.CaptureRequest(basis,policy,8_000_000,256L*1024*1024);
        var expected=LongScaleNormalFairing.requestCaptureScale(request,CancellationProbe.NONE);
        var ledger=new AttemptMemoryLedger(8_000_000); var owner=ledger.rootOwner();
        var context=new GeometryWorkContext(8_000_000,8_000_000,Long.MAX_VALUE,CancellationProbe.NONE,owner);
        try (var actual=LongScaleNormalFairing.requestCaptureScale(request,CancellationProbe.NONE,context)) {
            assertEquals(expected,actual.result());
            assertThrows(UnsupportedOperationException.class,()->actual.result().requestedPoints().orElseThrow().set(0,points.get(1)));
        }
        assertEquals(0,ledger.currentBytes()); owner.close();
    }
    @Test void largeControlSetPreservesParityAndHonorsMidRunCancellation() {
        var points=new ArrayList<MetricPoint>(); var rows=new ArrayList<FinalControlBasis.Row>();
        for (int i=0;i<=300;i++) { points.add(new MetricPoint(i,Math.sin(i*.3))); rows.add(FinalControlBasis.Row.independent(i)); }
        var basis=new FinalControlBasis(points,rows,Set.of(0,300));
        var frame=RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);
        var policy=new CaptureScaleFairingPolicy("a".repeat(64),"evidence",1000,1000,frame,1,1);
        var request=new LongScaleNormalFairing.CaptureRequest(basis,policy,8_000_000,256L*1024*1024);
        var expected=LongScaleNormalFairing.requestCaptureScale(request,CancellationProbe.NONE);
        var ledger=new AttemptMemoryLedger(8_000_000); var owner=ledger.rootOwner();
        var context=new GeometryWorkContext(8_000_000,8_000_000,Long.MAX_VALUE,CancellationProbe.NONE,owner);
        try (var actual=LongScaleNormalFairing.requestCaptureScale(request,CancellationProbe.NONE,context)) {
            assertEquals(expected,actual.result()); assertEquals(299,actual.result().movements().size());
        }
        var checks=new AtomicInteger();
        var cancelled=new GeometryWorkContext(8_000_000,8_000_000,Long.MAX_VALUE,()->checks.incrementAndGet()>2000,owner);
        assertThrows(CancellationException.class,()->LongScaleNormalFairing.requestCaptureScale(request,CancellationProbe.NONE,cancelled));
        assertTrue(cancelled.visits()>256); assertEquals(0,ledger.currentBytes()); owner.close();
    }
}
