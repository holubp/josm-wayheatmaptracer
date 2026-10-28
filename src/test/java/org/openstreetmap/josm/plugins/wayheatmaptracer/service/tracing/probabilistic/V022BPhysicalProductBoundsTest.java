package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Exact real-product expectations independent of the interval implementation. */
class V022BPhysicalProductBoundsTest {
    @Test void nonfiniteProductsStillRefuse()throws Exception{
        for(String name:new String[]{"downProduct","upProduct"}){
            var method=BPhysicalMaxPanel.class.getDeclaredMethod(name,double.class,double.class);method.setAccessible(true);
            for(double[] pair:new double[][]{{Double.MAX_VALUE,2},{Double.NaN,1},{Double.POSITIVE_INFINITY,1}}){
                var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(null,pair[0],pair[1]));
                assertInstanceOf(ArithmeticException.class,failure.getCause());
            }
        }
    }
    @Test void subnormalPositiveProductsRemainEnclosedInsteadOfAborting()throws Exception{
        double[][] inputs={{Double.MIN_VALUE,.5},{Math.scalb(1.0,-600),Math.scalb(1.0,-600)},
            {Double.MIN_NORMAL,Math.scalb(1.0,-53)},{Math.nextDown(Double.MIN_NORMAL),.5},
            {0,.5},{.5,0},{.3,.7},{Double.MIN_NORMAL,1}};
        var down=BPhysicalMaxPanel.class.getDeclaredMethod("downProduct",double.class,double.class);
        var up=BPhysicalMaxPanel.class.getDeclaredMethod("upProduct",double.class,double.class);
        down.setAccessible(true);up.setAccessible(true);
        for(var pair:inputs){
            double lo=assertDoesNotThrow(()->(double)down.invoke(null,pair[0],pair[1]),"Positive underflow has a finite outward enclosure");
            double hi=assertDoesNotThrow(()->(double)up.invoke(null,pair[0],pair[1]),"Positive underflow has a finite outward enclosure");
            var exact=new BigDecimal(pair[0]).multiply(new BigDecimal(pair[1]));
            assertTrue(Double.isFinite(lo)&&Double.isFinite(hi));
            assertTrue(new BigDecimal(lo).compareTo(exact)<=0,"Lower endpoint must not exclude the exact product");
            assertTrue(new BigDecimal(hi).compareTo(exact)>=0,"Upper endpoint must not exclude the exact product");
            if(exact.signum()>0&&pair[0]*pair[1]==0)assertTrue(hi>0,"A positive real tail must retain a positive upper bound");
        }
    }
}
