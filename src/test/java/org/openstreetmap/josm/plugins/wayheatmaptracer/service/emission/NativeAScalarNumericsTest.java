package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.*;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class NativeAScalarNumericsTest {
    private static BilinearSegmentIntegral.Result scalar(double mean) {
        return new BilinearSegmentIntegral.Result(BilinearSegmentIntegral.Status.COMPLETE,
            OptionalDouble.of(mean),OptionalDouble.of(mean),0,0,BilinearSegmentIntegral.Reason.NONE);
    }
    @Test void exactExpressionPreservesZeroSubnormalAndOneUlpMeansAgainstOriginalAdmission() {
        for(double span:new double[]{0,Double.MIN_VALUE,Math.nextUp(Double.MIN_VALUE),1,Math.nextDown(Double.MAX_VALUE)})
            for(double pitch:new double[]{Double.MIN_VALUE,1,3,Double.MAX_VALUE})
                for(double mean:new double[]{0,Double.MIN_VALUE,Math.nextDown(1.),1}) {
                    var result=scalar(mean);double actual=NativeConnectorEvaluator.nativeScalarQ(span,pitch,result);
                    double nativeQ=CompetitiveAdmissionExecutor.Admission.measured(span,pitch,result,0,0,0,result).q();
                    assertEquals(Double.doubleToLongBits(nativeQ),Double.doubleToLongBits(actual));
                }
        assertEquals(Double.MIN_VALUE,NativeConnectorEvaluator.nativeScalarQ(Double.MIN_VALUE,1,scalar(0)),0);
        assertEquals(0,NativeConnectorEvaluator.nativeScalarQ(0,Double.MIN_VALUE,scalar(0)),0);
        assertTrue(Double.isInfinite(NativeConnectorEvaluator.nativeScalarQ(1,Double.MIN_VALUE,scalar(0))));
        assertTrue(Double.isNaN(NativeConnectorEvaluator.nativeScalarQ(1,Double.MIN_VALUE,scalar(1))));
    }
    @Test void refinedLowerUsesTheActualFirstAdditionAndUnknownOrNonfiniteNeverStrengthens() {
        for(double prefix:new double[]{0,Double.MIN_VALUE,1,Math.nextDown(2.),Double.MAX_VALUE})
            for(double q:new double[]{0,Double.MIN_VALUE,Math.ulp(1.),Math.nextUp(1.),Double.MAX_VALUE}) {
                double first=prefix+q;
                double lower=OutwardCostBounds.completedPathLower(prefix,q,true);
                if(Double.isFinite(first))assertTrue(lower<=first);
                else assertEquals(Double.NEGATIVE_INFINITY,lower);
                assertEquals(Double.NEGATIVE_INFINITY,OutwardCostBounds.completedPathLower(prefix,q,false));
            }
        assertEquals(Double.NEGATIVE_INFINITY,OutwardCostBounds.completedPathLower(1,Double.NaN,true));
        assertEquals(Double.NEGATIVE_INFINITY,OutwardCostBounds.completedPathLower(1,Double.POSITIVE_INFINITY,true));
        assertEquals(1,OutwardCostBounds.completedPathLower(1,0,true),0,"Equality with U stays open");
    }
}
