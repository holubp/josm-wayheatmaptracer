package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

class V022BObservationReplacementTest {
    @Test void replacesOnlyObservationArraysAndPreservesAllOtherInferenceInputs() {
        var original=baseline();double[] costs={7,9};double[][] responsibilities={{.2,.8},{.4,.6}};
        var updated=original.withObservationRows(costs,responsibilities,null);
        costs[0]=100;responsibilities[0][0]=100;
        assertEquals(7,updated.unaryCost(0));assertEquals(9,updated.unaryCost(1));
        assertEquals(.2,updated.componentResponsibility(0,0));
        assertArrayEquals(new double[]{2,3},original.unaryCosts());
        assertEquals(original.chainageMeters(),updated.chainageMeters());
        assertEquals(original.anchor(),updated.anchor());assertEquals(original.normalUnit(),updated.normalUnit());
        assertEquals(original.cells(),updated.cells());assertEquals(original.ownership(),updated.ownership());
        assertEquals(original.entirelyMissing(),updated.entirelyMissing());
        for(int i=0;i<2;i++) {
            assertEquals(original.orientationSupport(i),updated.orientationSupport(i));
            assertEquals(original.orientationReliability(i),updated.orientationReliability(i));
            assertEquals(original.structuralGuideCost(i),updated.structuralGuideCost(i));
        }
    }
    @Test void refusalLeavesOriginalAndCallerOwnerUnchanged() {
        var original=baseline();var ledger=new AttemptMemoryLedger(8);
        try(var owner=ledger.rootOwner()) {
            assertThrows(ProbabilisticInference.MemoryLimit.class,()->original.withObservationRows(
                    new double[]{7,9},new double[][]{{1},{1}},owner));
            assertEquals(0,ledger.currentBytes());assertArrayEquals(new double[]{2,3},original.unaryCosts());
        }
    }
    @Test void successfulReplacementIsRetainedOnlyByItsAttemptOwner() {
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var updated=baseline().withObservationRows(new double[]{7,9},new double[][]{{1},{1}},owner);
            assertTrue(ledger.currentBytes()>0);assertEquals(7,updated.unaryCost(0));
        }
        assertEquals(0,ledger.currentBytes());
    }
    private static InferenceProfile baseline() {
        return new InferenceProfile(3,new MetricPoint(2,4),new MetricPoint(0,1),
                List.of(new LateralStateCell(-1,1,false,false,"image"),new LateralStateCell(1,1,false,false,"image")),
                new double[]{2,3},List.of(0.0),.5,ObservationOwnership.DIRECT_TWO_SIDED,false,
                new double[][]{{1},{1}}).withStructuralGuideCosts(new double[]{.4,.8});
    }
}
