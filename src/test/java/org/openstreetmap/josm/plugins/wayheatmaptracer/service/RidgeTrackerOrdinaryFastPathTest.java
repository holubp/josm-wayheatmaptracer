package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.geom.Point2D;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class RidgeTrackerOrdinaryFastPathTest {
    private static final double[][] INPUT={ {},{1},{-0.0,0.0},{0,2,7,3,0,4,0},{0,1,4,2,0,1,0},
        {0,8,-8,8,-8,8,0},{0,8,-8,8,-8,8,0},{-0.0,0.0,-0.0,0.0,-0.0} };
    // Literal raw-bit oracles extracted from the frozen published RC14 four-argument
    // smoother, never generated using the current implementation during this test.
    private static final double[][] GOLDEN={ {},{0x1.0p0},{-0x0.0p0,0x0.0p0},
        {0x0.0p0,0x1.1fa04942c396ap1,0x1.a9419e9833dp1,0x1.4a6360d0e7395p1,0x0.0p0,0x1.465c731b78856p-1,0x0.0p0},
        {0x0.0p0,0x1.5d25148c7effp0,0x1.5817c91f6e2f8p1,0x1.ec95bae7c7fafp0,0x1.7f5db63e95e7ep-1,0x1.1ee14cb691f8p-1,0x0.0p0},
        {0x0.0p0,-0x1.999999999999p-2,0x1.9999999999998p1,0x1.47ae147ae1478p0,0x1.9999999999998p1,-0x1.999999999999p-2,0x0.0p0},
        {0x0.0p0,0x1.3333333333334p1,-0x1.777777777777ap0,0x1.999999999999ap1,-0x1.777777777777ap0,0x1.3333333333334p1,0x0.0p0},
        {-0x0.0p0,0x0.0p0,-0x0.0p0,0x0.0p0,-0x0.0p0} };
    record Fixture(List<CrossSectionProfile> profiles,List<Double> offsets,List<Double> intensities,double normalization) { }
    static Fixture fixture(int kind) {
        var offsets=Arrays.stream(INPUT[kind]).boxed().toList();var intensities=new ArrayList<Double>();var profiles=new ArrayList<CrossSectionProfile>();
        for(int row=0;row<offsets.size();row++) {
            double intensity=kind==3?new double[]{.8,.3,.1,.25,.7,.1,.8}[row]:kind==6?.7:.9;
            intensities.add(intensity);double offset=offsets.get(row);
            var peak=new CrossSectionPeak(offset,intensity,kind==6?100:4,false,intensity,0,intensity,.3,.8,1,
                offset,offset,offset,0,kind==4?0:1,.5);
            profiles.add(new CrossSectionProfile(new EastNorth(row,0),new Point2D.Double(row,0),new Point2D.Double(0,1),List.of(peak)));
        }
        return new Fixture(profiles,offsets,intensities,kind==6?24:kind==4?.5:1);
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5,6,7})
    void literalRc14ArithmeticAndExplicitMeteredRouteHaveIdenticalBits(int kind) throws Exception {
        var f=fixture(kind);var budget=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        var checks=new AtomicInteger();CancellationProbe cancellation=()->{checks.incrementAndGet();return false;};
        var ordinary=invoke(f,null,null);var metered=invoke(f,cancellation,budget);
        assertEquals(GOLDEN[kind].length,ordinary.size());assertEquals(ordinary.size(),metered.size());
        for(int row=0;row<ordinary.size();row++) {
            assertEquals(Double.doubleToRawLongBits(GOLDEN[kind][row]),Double.doubleToRawLongBits(ordinary.get(row)));
            assertEquals(Double.doubleToRawLongBits(ordinary.get(row)),Double.doubleToRawLongBits(metered.get(row)));
        }
        long expected=ordinary.size()<3?0:4L*(ordinary.size()-1);
        assertEquals(expected,checks.get());assertEquals(expected,budget.workUsed());assertEquals(0,budget.pairVisits());
        if(kind<3){assertSame(f.offsets(),ordinary);assertSame(f.offsets(),metered);}
    }
    @Test void compiledOrdinarySmootherDoesNotDelegateIntoPerRowCancellationOrBudgetHooks() throws Exception {
        var tool=java.util.spi.ToolProvider.findFirst("javap").orElseThrow();
        var output=new StringWriter();var error=new StringWriter();
        int status=tool.run(new PrintWriter(output),new PrintWriter(error),"-classpath",
            Path.of(RidgeTracker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),"-c","-p","-s",RidgeTracker.class.getName());
        assertEquals(0,status,error.toString());String text=output.toString();
        String descriptor="descriptor: (Ljava/util/List;Ljava/util/List;Ljava/util/List;D)Ljava/util/List;";
        int start=text.indexOf(descriptor);assertTrue(start>=0);int end=text.indexOf("\n  private ",start);
        String body=text.substring(start,end<0?text.length():end);
        assertFalse(body.contains("CancellationProbe"),"Ordinary smoothing must not reach NONE checkpoints through a shared bridge");
        assertFalse(body.contains("EmissionWorkBudget"),"Ordinary smoothing must not reach nullable per-row budget hooks");
        assertTrue(body.contains("movingAverage:"));assertTrue(body.contains("selectedPeak:"));assertTrue(body.contains("localDeltaSignFlips:"));
    }
    @Test void explicitMeteredCancellationAndWorkFailureRemainAtTheirOriginalInnerBoundaries() throws Exception {
        var f=fixture(5);var checks=new AtomicInteger();CancellationProbe cancel=()->checks.incrementAndGet()==5;
        assertThrows(java.util.concurrent.CancellationException.class,()->invoke(f,cancel,new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE)));
        assertEquals(5,checks.get());
        var budget=new EmissionWorkBudget(new TraceBudgets(96,8_000_000,5,32,8),CancellationProbe.NONE);
        var failure=assertThrows(EmissionWorkBudget.LimitExceededException.class,()->invoke(f,CancellationProbe.NONE,budget));
        assertEquals(5,budget.workUsed());assertEquals(0,budget.pairVisits());
        assertSame(failure,assertThrows(EmissionWorkBudget.LimitExceededException.class,()->invoke(f,CancellationProbe.NONE,budget)));
    }
    @SuppressWarnings("unchecked")
    static List<Double> invoke(Fixture f,CancellationProbe cancellation,EmissionWorkBudget budget) throws Exception {
        Class<?>[] types=cancellation==null?new Class<?>[]{List.class,List.class,List.class,double.class}
            :new Class<?>[]{List.class,List.class,List.class,double.class,CancellationProbe.class,EmissionWorkBudget.class};
        var method=RidgeTracker.class.getDeclaredMethod("smoothOffsets",types);method.setAccessible(true);
        try{return (List<Double>)(cancellation==null?method.invoke(new RidgeTracker(),f.profiles(),f.offsets(),f.intensities(),f.normalization())
            :method.invoke(new RidgeTracker(),f.profiles(),f.offsets(),f.intensities(),f.normalization(),cancellation,budget));}
        catch(InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException primary)throw primary;throw failure;}
    }
}
