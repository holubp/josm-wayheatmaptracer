package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Analytical scalar-reducer tests; synthetic coefficients grant no source authority. */
class V022BMaximumReductionTest {
    @Test void laterWholePanelDominanceRecoversOnePolynomialAfterCrossingRanges()throws Exception{
        try(var w=BPhysicalWork.createExploratory(CancellationProbe.NONE)){
            var f=V022BPhysicalPairTableTest.fixture(w,true,2);var p=f.profiles().get(0);
            var l=V022BSharedPositionFieldTest.tinyLattice(w.owner(),p);
            try(var r=BPhysicalResponse.open(w,f.snapshot(),"scalar",List.of(p),List.of(l));var max=BPhysicalMaxPanel.open(r)){
                var field=BPhysicalMaxPanel.class.getDeclaredField("poly");field.setAccessible(true);
                var poly=(BPhysicalComponentPanel.Polynomial)field.get(max);
                var update=BPhysicalMaxPanel.class.getDeclaredMethod("update",int.class,int.class,int.class,int.class,int.class,int.class);update.setAccessible(true);
                int state=feed(max,poly,update,0,1,new double[]{.1,.125,.15,.175,.2});
                assertEquals(1,state);
                state=feed(max,poly,update,state,1,new double[]{.2,.175,.15,.125,.1});
                assertEquals(2,state,"the first two polynomials cross");
                assertEquals(2,feed(max,poly,update,state,1,new double[]{.15,.15,.15,.15,.15}),"non-dominating candidates retain RANGE");
                state=feed(max,poly,update,2,1,new double[]{.8,.8,.8,.8,.8});
                assertEquals(1,state,"constant0.8 dominates the entire earlier maximum");
                for(int i=0;i<5;i++)assertEquals(.8,poly.c[14][i],0);
                assertEquals(3,feed(max,poly,update,3,1,new double[]{1,1,1,1,1}),"unknown source remains unknown");
                assertEquals(4,feed(max,poly,update,4,1,new double[]{1,1,1,1,1}),"unresolved source remains unresolved");
                assertEquals(2,feed(max,poly,update,2,2,new double[]{1,1,1,1,1}),"a range cannot supply a winner polynomial");
            }
        }
    }
    private static int feed(BPhysicalMaxPanel max,BPhysicalComponentPanel.Polynomial poly,java.lang.reflect.Method update,
            int state,int incoming,double[] coefficients)throws Exception{
        double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;
        for(int i=0;i<5;i++){poly.put(13,i,coefficients[i],coefficients[i],coefficients[i]);lo=Math.min(lo,coefficients[i]);hi=Math.max(hi,coefficients[i]);}
        poly.registers[15]=poly.registers[17]=lo;poly.registers[16]=poly.registers[18]=hi;poly.registers[19]=0;
        return (int)update.invoke(max,state,incoming,14,13,0,15);
    }
}
