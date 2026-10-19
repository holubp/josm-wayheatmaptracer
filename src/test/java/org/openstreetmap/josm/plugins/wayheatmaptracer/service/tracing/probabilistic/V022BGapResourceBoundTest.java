package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Independent phase-specific accounting oracles; no observation-model changes. */
class V022BGapResourceBoundTest {
    private static final long BLOCK512=1_052_676;

    @Test void retainedActualSourceOldBoundAndCorrectedPhaseBoundHaveIndependentExactCensus() {
        long scalar=2*(2*191401L+190404)+1024*17L+128*17L*17+256*(6400L+6480)+4096*632700L+128*126540L;
        long delta=64+2*126540L+7*(2+3*17L)+8*17L;
        // Old bound used W162 for both phases: retained actual-source RED.
        assertEquals(17_657_487_682_943L,(102400L+32*126540L+256*632700L)*106276+scalar+delta);
        long geometry=102400L, fixed=128*6400L+128*126540L+256*632700L;
        assertEquals(188_430_794_473_343L,geometry*(106276+8)+fixed*(BLOCK512+8)+scalar+delta);
        assertEquals(188_430_794_473_343L,BGapField.buildBound(17,17,1,6400,6480,0,0,0,162,126540,632700)+delta);
        // Full clamped candidate-column range: [-1,width-1], inclusive.
        long attempts=126540L*82;
        long runtime=geometry*(106276+8)+(128*6400L+128*126540L+256*attempts)*(BLOCK512+8)
                +(scalar-4096*632700L+4096*attempts)+delta;
        assertTrue(runtime<BGapEvidence.BUILD_UNITS);assertEquals(10_376_280,attempts);
        assertEquals(attempts,BGapField.attemptedColumnBound(81,126540));
        assertEquals(2_814_239_860_969_343L,runtime);
        System.out.println("PHASE_BOUND reviewedExample=188430794473343 clampedRuntime="+runtime+" attempts="+attempts);
    }

    @Test void runtimePreflightCountsAllCandidateColumnsEvenWhenThereIsNoOwnedGap() {
        try(var f=new BGapTestFixture(1,(x,y)->BGapTestFixture.observed(y),false,false,2,1);
                var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner)) {
            // No complete flanks =>K/I/B0; b64=>W162. Grid3x11 has20 cells,
            //22 prefix entries; one pair can attempt4 clamped columns.
            assertEquals(BGapField.buildBound(2,4,2,20,22,0,0,0,162,1,4),field.work.reservedUpperUnits());
        }
    }

    @Test void actualSourceMaximumFixed512CallsRespectIndependentCellCeilings() throws Exception {
        for(int shape:new int[]{0,1})for(boolean phase:new boolean[]{false,true})
            try(var f=new BGapTestFixture(phase?2:1,(x,y)->BGapTestFixture.observed(y),false,true,3,1,phase,false,shape);
                    var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner);
                    var arena=f.owner.reserve(BGapPairTable.prefixBytes(field.grid)+65_536);
                    var source=BGapEvidence.copySource(f.input,f.owner,CancellationProbe.NONE)) {
                assertEquals(BGapEvidence.Status.OBSERVED_GAP,field.status(0,4));assertTrue(field.counters().maskReads()>0);
                // Invoke the SAME private metadata consumer, isolating its existing
                // counters rather than adding an observer or pretending all calls use W.
                var work=new BGapEvidence.WorkCounter();
                var type=Class.forName(BGapField.class.getName()+"$Metadata");
                var constructor=type.getDeclaredConstructor(BGapEvidence.Input.class,BGapGrid.class,int.class,int.class,
                        BGapEvidence.WorkCounter.class,CancellationProbe.class);constructor.setAccessible(true);
                var metadata=constructor.newInstance(f.input,field.grid,6,2,work,CancellationProbe.NONE);
                var maximum=type.getDeclaredMethod("sourceMaximum",int.class,int.class,BGapEvidence.SourceCopy.class);maximum.setAccessible(true);
                var before=work.details();assertEquals(.125,(double)maximum.invoke(metadata,0,4,source),0);
                var counts=fixedCalls(before,work.details());
                assertTrue(counts.heavy<=90,"sourceMaximum heavy calls "+counts);
                assertTrue(counts.api<=264,"sourceMaximum total API calls "+counts);
                assertTrue(field.counters().buildUnits()<=field.work.reservedUpperUnits());
                System.out.println("SOURCE_MAX_CALLS shape="+shape+" phase="+phase+" "+counts);
            }
    }

    @Test void actualPairBranchesRespectHeavyAndApiBoundsIncludingZeroDurationCandidates() {
        double[][] edges={{0,.5,1,.5,2},{0,.5,2,.5,2},{.1,.5,1.9,1.5,2},{1,-3,1,5,1},
                {-2,.5,4,.5,4},{1,.5,1,.5,0},{1,5,1,-3,1}};
        for(boolean invalid:new boolean[]{false,true})
            try(var f=new BGapTestFixture(1,(x,y)->BGapTestFixture.observed(y),invalid,true,2,1);
                    var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner);
                    var integral=BGapPairTable.prefix(field,CancellationProbe.NONE,f.owner)) {
                for(var edge:edges) {
                    var before=field.workDetails();long positive=field.counters().slabEvaluations();
                    integral.integral(p(edge[0],edge[1]),p(edge[2],edge[3]));
                    var calls=fixedCalls(before,field.workDetails());long attempted=(long)edge[4];
                    assertTrue(calls.heavy<=128+256*attempted,"pair heavy calls "+calls);
                    assertTrue(calls.api<=260+576*attempted,"pair total API calls "+calls);
                    assertTrue(field.counters().slabEvaluations()-positive<=attempted);
                    if(edge[0]==0&&edge[2]==1)assertEquals(1,field.counters().slabEvaluations()-positive,"one of two candidates has zero duration");
                    System.out.println("PAIR_CALLS invalid="+invalid+" edge="+List.of(edge[0],edge[1],edge[2],edge[3])+" attempted="+attempted+" "+calls);
                }
            }
    }

    @Test void unchangedGlobalCapOverflowRefusalAndCancellationPreserveOwnedState() throws Exception {
        assertEquals(78_320_919_079_993_196L,BGapEvidence.BUILD_UNITS);
        assertThrows(ArithmeticException.class,()->BGapField.buildBound(2,4,2,20,22,0,Long.MAX_VALUE,3,162,1,4));
        assertThrows(ArithmeticException.class,()->BGapField.attemptedColumnBound(Integer.MAX_VALUE,Long.MAX_VALUE));
        var work=new BGapEvidence.WorkCounter();assertThrows(IllegalArgumentException.class,()->work.reserveUpper(BGapEvidence.BUILD_UNITS+1));
        assertEquals(0,work.snapshot().buildUnits());
        try(var f=BGapTestFixture.standard()) {
            long bytes=f.owner.currentBytes();var tree=BGapTestFixture.ownerTree(f.owner);int[] calls={0};
            assertThrows(IllegalStateException.class,()->BGapField.build(f.input,()->{if(++calls[0]==100)throw new IllegalStateException("fixed cancel");return false;},f.owner));
            assertEquals(bytes,f.owner.currentBytes());assertEquals(tree,BGapTestFixture.ownerTree(f.owner));
            try(var field=BGapField.build(f.input,CancellationProbe.NONE,f.owner)) {
                field.work.reserveUpper(BGapEvidence.BUILD_UNITS);long before=f.owner.currentBytes();var owners=BGapTestFixture.ownerTree(f.owner);
                assertThrows(IllegalArgumentException.class,()->BGapPairTable.build(field,f.input.profiles(),CancellationProbe.NONE,f.owner));
                assertEquals(before,f.owner.currentBytes());assertEquals(owners,BGapTestFixture.ownerTree(f.owner));
            }
            assertEquals(bytes,f.owner.currentBytes());assertEquals(tree,BGapTestFixture.ownerTree(f.owner));
        }
    }
    private record Calls(long heavy,long api) { }
    private static Calls fixedCalls(BGapEvidence.WorkDetails before,BGapEvidence.WorkDetails after) {
        long api=after.bigIntegerApiCalls()-before.bigIntegerApiCalls(),complexity=after.complexityUpperUnits()-before.complexityUpperUnits();
        assertEquals(0,(complexity-api)%(BLOCK512-1),"isolated calls must use only fixed512 blocks or unit scalar calls");
        return new Calls((complexity-api)/(BLOCK512-1),api);
    }
    private static MetricPoint p(double x,double y){return new MetricPoint(x,y);}
}
