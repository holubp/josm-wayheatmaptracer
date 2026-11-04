package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.Sample;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

class LocalNoiseRankProofTest {
    @Test void rankBoundariesMatchActualExtractorIncludingMissingAndSubnormalSamples() {
        int[][] cases={{0,-1},{1,0},{2,0},{5,0},{6,1},{10,1},{11,2},{16,3},{17,3},{31,6},{32,6},{129,25}};
        var extractor=new LocalScalarProfileExtractor();
        for(var c:cases)for(int zeros:new int[]{Math.max(0,c[1]),Math.min(c[0],c[1]+1)}) {
            var samples=new ArrayList<Sample>();
            for(int i=0;i<c[0];i++)samples.add(new Sample(samples.size(),i<zeros?(i%2==0?-0.0:0.0):Double.MIN_VALUE,true));
            samples.add(new Sample(samples.size(),Double.NaN,false));samples.add(new Sample(samples.size(),0,false));
            samples.add(new Sample(samples.size(),-1,false));samples.add(new Sample(samples.size(),Double.POSITIVE_INFINITY,false));
            var actual=extractor.extract(samples,1,EvidenceModelParameters.Localization.defaults());
            boolean expected=c[0]>0&&zeros<=c[1];
            assertEquals(expected,actual.noiseFloor()!=0,"Actual stored-value order statistic V="+c[0]+" Z="+zeros);
            assertEquals(expected?c[1]:-1,witnessRank(c[0],zeros));
            if(expected)assertEquals(Double.MIN_VALUE,actual.noiseFloor(),0);
        }
    }
    @Test void positiveNearZeroIsNotCollapsedAndValidInvalidBoundariesAreEnforced() {
        var samples=List.of(new Sample(-1,1e-300,true),new Sample(0,Math.nextUp(0.0),true),new Sample(1,0,false));
        var actual=new LocalScalarProfileExtractor().extract(samples,1,EvidenceModelParameters.Localization.defaults());
        assertTrue(actual.noiseFloor()>0);assertEquals(0,witnessRank(2,0));
        assertThrows(IllegalArgumentException.class,()->new Sample(0,Double.NaN,true));
        assertThrows(IllegalArgumentException.class,()->new Sample(0,-1,true));
        assertThrows(IllegalArgumentException.class,()->witnessRank(-1,0));
        assertThrows(IllegalArgumentException.class,()->witnessRank(1,-1));
        assertThrows(IllegalArgumentException.class,()->witnessRank(1,2));
        assertThrows(IllegalArgumentException.class,()->LocalScalarProfileExtractor.rawNoiseFloorRank(-1));
    }
    private static int witnessRank(int valid,int zeros) {
        return NativeConnectorEvaluator.nonzeroNoiseFloorRank(valid,zeros);
    }
}
