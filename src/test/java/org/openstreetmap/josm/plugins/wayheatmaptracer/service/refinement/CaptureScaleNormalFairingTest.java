package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class CaptureScaleNormalFairingTest {
    private static final String HASH = "a".repeat(64);
    private static final RasterMetricTransform FRAME = RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);

    @Test void radiusIsTwentyAndSigmaIsOneThirdOfIt() {
        var p=policy(FRAME,1,1);
        assertEquals(20,p.radiusReferencePixels()); assertEquals(20./3,p.sigmaReferencePixels());
        assertEquals(Math.exp(-4.5),p.kernelWeight(20),1e-15);
        assertEquals(0,p.kernelWeight(Math.nextUp(20.))); assertEquals(0,p.kernelWeight(-20.001));
        assertEquals(Math.exp(-.5),p.kernelWeight(20./3),1e-15);
    }

    @Test void actualNormalCapsDifferForOneAndTwoPixelsWithoutSourceEligibility() {
        var basis=wave(); var one=run(basis,policy(FRAME,1,1)); var two=run(basis,policy(FRAME,2,1));
        assertEquals(LongScaleNormalFairing.RequestedStatus.REQUESTED,one.status());
        assertEquals(1,Math.abs(motion(one,41).clippedNormalPixels()),1e-12);
        assertEquals(2,Math.abs(motion(two,41).clippedNormalPixels()),1e-12);
        assertEquals(1,one.requestedPoints().orElseThrow().get(41).distanceTo(basis.initialCoordinates().get(41)),1e-10);
        assertEquals(2,two.requestedPoints().orElseThrow().get(41).distanceTo(basis.initialCoordinates().get(41)),1e-10);
        assertEquals(basis.size()-2,one.movements().size(),"every unprotected native control is geometric, including historically unsupported controls");
        assertFalse(one.isApplicable()); assertEquals(basis.size(),one.requestedPoints().orElseThrow().size());
    }

    @Test void actualFieldDoesNotReadOutsideTwentyReferencePixels() {
        var a=new ArrayList<MetricPoint>();for(int i=0;i<=100;i++)a.add(new MetricPoint(i-50,i==50?.5:0));
        var b=new ArrayList<>(a);b.set(5,new MetricPoint(-45,4.3));
        var original=run(basis(a,Set.of(0,100)),policy(FRAME,1,1)).requestedPoints().orElseThrow().get(50);
        var outsideChanged=run(basis(b,Set.of(0,100)),policy(FRAME,1,1)).requestedPoints().orElseThrow().get(50);
        assertEquals(original.xMeters(),outsideChanged.xMeters(),1e-10);
        assertEquals(original.yMeters(),outsideChanged.yMeters(),1e-10,
                "outside-window geometry must not shift the integration phase inside the kernel");
    }

    @Test void onePixelC1FadeHasFullGainInsideAFivePixelIsland() {
        var p=policy(FRAME,1,1); assertEquals(0,p.fade(0)); assertEquals(.5,p.fade(.5)); assertEquals(1,p.fade(1));
        assertTrue(p.fade(1e-6)/1e-6<1e-4); assertTrue((1-p.fade(1-1e-6))/1e-6<1e-4);
        var b=basis(List.of(new MetricPoint(0,0),new MetricPoint(1,0),new MetricPoint(2,.3),new MetricPoint(3,-.3),new MetricPoint(4,0),new MetricPoint(5,0)),Set.of(0,5));
        var r=run(b,p); var m=motion(r,2);assertTrue(Math.abs(m.requestedNormalPixels())>.01);
        assertEquals(m.clippedNormalPixels(),m.boundaryNormalPixels(),0);
    }

    @Test void anisotropicSkewUsesGroundNormalAndDirectionalPixelDistance() {
        var frame=RasterMetricTransform.metricGrid(new MetricPoint(1000,2000),2,1,0,1);var p=policy(frame,2,1);
        var n=new MetricPoint(-1/Math.sqrt(5),2/Math.sqrt(5));
        assertEquals(1/Math.sqrt(1.3),p.normalPixelMeters(n),1e-12);
        var points=List.of(frame.pixelCenterToMetric(-1,0),frame.pixelCenterToMetric(0,.4),frame.pixelCenterToMetric(1,0));
        var r=run(basis(points,Set.of(0,2)),p);var q=r.requestedPoints().orElseThrow().get(1);var d=new MetricPoint(q.xMeters()-points.get(1).xMeters(),q.yMeters()-points.get(1).yMeters());
        assertTrue(Math.hypot(d.xMeters(),d.yMeters())>.01);assertEquals(0,4*d.xMeters()+2*d.yMeters(),1e-10);
        assertTrue(p.referenceDistance(d)<=2+1e-10);
    }

    @Test void doubledWorkingRasterDoesNotRedefineOriginalReferencePixels() {
        var ref=RasterMetricTransform.visible(new MetricPoint(100,200),2);
        var work=new RasterMetricTransform("working-double",RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,new MetricPoint(100,200),2,0,0,-2,2);
        var a=new ArrayList<MetricPoint>();var b=new ArrayList<MetricPoint>();
        for(int i=0;i<=40;i++){double y=Math.sin(i*Math.PI/2);a.add(ref.pixelCenterToMetric(i,y));b.add(work.pixelCenterToMetric(2*i,2*y));}
        assertEquals(a,b);var policy=policy(ref,1,1);
        assertEquals(run(basis(a,Set.of(0,40)),policy).requestedPoints(),run(basis(b,Set.of(0,40)),policy).requestedPoints());
        assertEquals(2,policy.referenceDistance(new MetricPoint(4,0)),0);
    }

    @Test void nonMidpointOffLineDependentOriginDoesNotConditionNativeTargets() {
        var base=wave();var original=run(base,policy(FRAME,1,1)).requestedPoints().orElseThrow();int left=20,right=21;double w=.23;
        var points=new ArrayList<MetricPoint>();var rows=new ArrayList<FinalControlBasis.Row>();
        for(int i=0;i<base.size();i++){
            points.add(base.initialCoordinates().get(i));rows.add(FinalControlBasis.Row.independent(points.size()-1));
            if(i==left){var a=base.initialCoordinates().get(left);var b=base.initialCoordinates().get(right);points.add(new MetricPoint(a.xMeters()*(1-w)+b.xMeters()*w,a.yMeters()*(1-w)+b.yMeters()*w+.7));rows.add(FinalControlBasis.Row.dependent(points.size()-1,left,right+1,w));}
        }
        var expanded=new FinalControlBasis(points,rows,Set.of(0,points.size()-1));var actual=run(expanded,policy(FRAME,1,1)).requestedPoints().orElseThrow();
        for(int i=0;i<base.size();i++)assertEquals(original.get(i),actual.get(i<=left?i:i+1));
        var origin=points.get(left+1);var al=base.initialCoordinates().get(left);var ar=base.initialCoordinates().get(right);
        assertEquals(origin.xMeters()+(1-w)*(original.get(left).xMeters()-al.xMeters())+w*(original.get(right).xMeters()-ar.xMeters()),actual.get(left+1).xMeters(),1e-12);
        assertEquals(origin.yMeters()+(1-w)*(original.get(left).yMeters()-al.yMeters())+w*(original.get(right).yMeters()-ar.yMeters()),actual.get(left+1).yMeters(),1e-12);
    }

    @Test void protectedInteriorAndEndpointsRemainBitExactAndGainZeroIsExact() {
        var points=new ArrayList<>(wave().initialCoordinates());points.set(0,new MetricPoint(-0.0,-0.0));points.set(40,new MetricPoint(40,-0.0));
        var b=basis(points,Set.of(0,40,80));var r=run(b,policy(FRAME,2,1)).requestedPoints().orElseThrow();
        for(int i:List.of(0,40,80)){assertSame(points.get(i),r.get(i));assertEquals(Double.doubleToRawLongBits(points.get(i).yMeters()),Double.doubleToRawLongBits(r.get(i).yMeters()));}
        assertEquals(points,run(b,policy(FRAME,2,0)).requestedPoints().orElseThrow());
    }

    @Test void finiteBudgetsRefuseWithoutFabricatingRequestedGeometry() {
        var b=wave();var p=policy(FRAME,1,1);
        var work=LongScaleNormalFairing.requestCaptureScale(new LongScaleNormalFairing.CaptureRequest(b,p,0,256L*1024*1024),CancellationProbe.NONE);
        assertEquals(LongScaleNormalFairing.RequestedStatus.REFUSED_RESOURCE,work.status());assertTrue(work.requestedPoints().isEmpty());assertEquals("work-limit",work.reason());
        var memory=LongScaleNormalFairing.requestCaptureScale(new LongScaleNormalFairing.CaptureRequest(b,p,8_000_000,0),CancellationProbe.NONE);
        assertEquals(LongScaleNormalFairing.RequestedStatus.REFUSED_RESOURCE,memory.status());assertEquals("scratch-limit",memory.reason());assertTrue(memory.requestedPoints().isEmpty());
        assertThrows(IllegalArgumentException.class,()->policy(FRAME,3,1));assertThrows(IllegalArgumentException.class,()->policy(FRAME,1,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->new CaptureScaleFairingPolicy(HASH,"image",0,100,FRAME,1,1));
    }

    @Test void degenerateReferenceIsUnavailableAndCancellationPropagates() {
        var b=basis(List.of(new MetricPoint(0,0),new MetricPoint(0,0),new MetricPoint(2,0)),Set.of(0,2));
        var r=run(b,policy(FRAME,1,1));assertEquals(LongScaleNormalFairing.RequestedStatus.REFUSED_GEOMETRY,r.status());assertTrue(r.requestedPoints().isEmpty());
        assertThrows(CancellationException.class,()->LongScaleNormalFairing.requestCaptureScale(new LongScaleNormalFairing.CaptureRequest(wave(),policy(FRAME,1,1),8_000_000,256L*1024*1024),()->true));
        var checks=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(CancellationException.class,()->LongScaleNormalFairing.requestCaptureScale(new LongScaleNormalFairing.CaptureRequest(wave(),policy(FRAME,1,1),8_000_000,256L*1024*1024),()->checks.incrementAndGet()>100));
        assertTrue(checks.get()>100,"cancellation must also interrupt an in-progress request");
    }

    @Test void noUnprotectedInteriorIsExplicitAndLegacyEntryHasNoNewState() {
        var b=basis(List.of(new MetricPoint(0,0),new MetricPoint(5,0)),Set.of(0,1));var r=run(b,policy(FRAME,1,1));
        assertEquals(LongScaleNormalFairing.RequestedStatus.UNCHANGED,r.status());assertEquals("no-movable-interior",r.reason());
        var w=wave();double length=0;for(int i=1;i<w.size();i++)length+=w.initialCoordinates().get(i).distanceTo(w.initialCoordinates().get(i-1));
        var old=new LongScaleNormalFairing.Request(w,1,List.of(new CleanupInterval(0,80,0,length,79,true,true,false,List.of())),List.of());
        var before=LongScaleNormalFairing.propose(old,CancellationProbe.NONE);run(w,policy(FRAME,2,1));assertEquals(before,LongScaleNormalFairing.propose(old,CancellationProbe.NONE));
    }

    private static LongScaleNormalFairing.CaptureMovement motion(LongScaleNormalFairing.CaptureRequestedResult r,int index){return r.movements().stream().filter(x->x.finalIndex()==index).findFirst().orElseThrow();}
    private static CaptureScaleFairingPolicy policy(RasterMetricTransform frame,int k,double gain){return new CaptureScaleFairingPolicy(HASH,"original-image",1000,1000,frame,k,gain);}
    private static LongScaleNormalFairing.CaptureRequestedResult run(FinalControlBasis basis,CaptureScaleFairingPolicy policy){return LongScaleNormalFairing.requestCaptureScale(new LongScaleNormalFairing.CaptureRequest(basis,policy,8_000_000,256L*1024*1024),CancellationProbe.NONE);}
    private static FinalControlBasis wave(){var p=new ArrayList<MetricPoint>();for(int i=0;i<=80;i++)p.add(new MetricPoint(i,3*Math.sin(i*Math.PI/2)));return basis(p,Set.of(0,80));}
    private static FinalControlBasis basis(List<MetricPoint> p,Set<Integer> fixed){var rows=new ArrayList<FinalControlBasis.Row>();for(int i=0;i<p.size();i++)rows.add(FinalControlBasis.Row.independent(i));return new FinalControlBasis(p,rows,fixed);}
}
