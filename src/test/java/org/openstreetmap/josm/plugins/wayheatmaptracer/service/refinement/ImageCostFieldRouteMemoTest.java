package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class ImageCostFieldRouteMemoTest {
    private static ImageCostField image(int kind) {
        int size=101;double[] values=new double[size*size];boolean[] valid=new boolean[values.length];
        for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
            int i=y*size+x;double center=kind==3?3*Math.sin((x-50)/8.0):0;
            values[i]=kind==1?.1:Math.exp(-Math.pow(y-50-center,2)/8);
            valid[i]=kind!=2||x<55;
        }
        return new ImageCostField(new ScalarEvidenceField(size,size,values,valid,new EvidenceFieldLineage(
            EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,
            "route-memo",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false)),
            new RasterMetricTransform("route-memo",RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,
                new MetricPoint(-50,-50),1,0,0,1,1),MetricRegion.rectangle(-50,-50,50,50),1);
    }
    private static GeometryWorkContext work(AttemptMemoryLedger.Owner owner) {
        return new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
    }
    @Test void deferredCoreDirectionMatchesEagerAndChargesOnlyTheFirstRead() {
        for (int kind : List.of(0, 1, 2, 3)) {
            var image = image(kind);
            var point = new MetricPoint(0, 0); var tangent = new MetricPoint(4, 0);
            var eager = image.freezeProfileWithCoreOrientation(point, tangent, CancellationProbe.NONE);
            try (var owner = AttemptMemoryLedger.production().rootOwner()) {
                var work = work(owner);
                try (var scope = PostprocessingValidationWork.open(work)) {
                    var prepared = image.prepareCoreProfile(point, tangent, CancellationProbe.NONE);
                    assertEquals(eager.support(), prepared.scalar().support());
                    assertArrayEquals(eager.offsetsMeters(), prepared.scalar().offsetsMeters());
                    assertArrayEquals(eager.values(), prepared.scalar().values());
                    long before = work.visits();
                    var actual = prepared.direction(CancellationProbe.NONE);
                    long after = work.visits();
                    assertEquals(eager.coreOrientationModes(), actual.modes());
                    assertEquals(eager.coreOrientationRadians(), actual.radians());
                    assertEquals(eager.coreOrientationCertainty(), actual.certainty());
                    assertEquals(eager.directedOrientationModes(), actual.directedModes());
                    assertSame(actual, prepared.direction(CancellationProbe.NONE));
                    assertEquals(after + 1, work.visits());
                    assertThrows(java.util.concurrent.CancellationException.class,
                            () -> prepared.direction(() -> true));
                    assertTrue(after > before);
                }
            }
        }
    }
    @Test void cancelledDirectionMissDoesNotPublishPartialResult() {
        var image = image(0);
        try (var owner = AttemptMemoryLedger.production().rootOwner()) {
            var work = work(owner);
            try (var scope = PostprocessingValidationWork.open(work)) {
                var point = new MetricPoint(0, 0); var tangent = new MetricPoint(4, 1);
                var prepared = image.prepareCoreProfile(point, tangent, CancellationProbe.NONE);
                var checks = new java.util.concurrent.atomic.AtomicInteger();
                assertThrows(java.util.concurrent.CancellationException.class,
                        () -> prepared.direction(() -> checks.incrementAndGet() > 2));
                var actual = prepared.direction(CancellationProbe.NONE);
                var expected = image.freezeProfileWithCoreOrientation(point, tangent, CancellationProbe.NONE);
                assertEquals(expected.coreOrientationModes(), actual.modes());
                assertEquals(expected.directedOrientationModes(), actual.directedModes());
            }
        }
    }
    @Test void identicalSupportAndEnergySchedulesReuseCompleteSamplesWithRealFirstMissWork() {
        var image=image(0);var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var work=work(owner);
            try(var validation=PostprocessingValidationWork.open(work);var memo=image.memoizeRouteSamples(work)) {
                var tangent=new MetricPoint(4,0);var samples=new ArrayList<Optional<ImageCostField.RouteSample>>();
                for(int i=0;i<8;i++)samples.add(image.sampleRoute(new MetricPoint(-2+4*(i+.5)/8,0),tangent));
                assertTrue(samples.stream().allMatch(Optional::isPresent));
                long mapped=work.counters().get(GeometryWorkContext.Kind.MAP);assertTrue(mapped>0);
                double total=0;for(var sample:samples)total+=sample.orElseThrow().imageEnergy();
                assertEquals(Double.doubleToRawLongBits(total/8),Double.doubleToRawLongBits(image.meanRouteSegmentCost(new MetricPoint(-2,0),new MetricPoint(2,0))));
                assertEquals(mapped,work.counters().get(GeometryWorkContext.Kind.MAP),"Exact repeated midpoint queries do not repeat extraction");
            }
            assertEquals(0,ledger.currentBytes());
        }
    }
    @Test void fullQualityMetricsOrderedFindingsAndDispositionRemainBitExact() {
        for(int kind=0;kind<4;kind++) {
            var image=image(kind);var points=List.of(new MetricPoint(-12,0),new MetricPoint(-4,0),
                new MetricPoint(0,3),new MetricPoint(4,0),new MetricPoint(12,0));
            var request=new FinalGeometryEvaluator.Request("memo-parity",points,image,1,Set.of(0,4),List.of(),false,false,false,false);
            var expected=new FinalGeometryEvaluator().evaluate(request);var ledger=AttemptMemoryLedger.production();
            try(var owner=ledger.rootOwner()) {
                var work=work(owner);
                try(var validation=PostprocessingValidationWork.open(work);var memo=image.memoizeRouteSamples(work)) {
                    var actual=new FinalGeometryEvaluator().evaluate(request);
                    assertEquals(expected,actual,"Complete quality result for scalar fixture "+kind);
                    assertArrayEquals(new long[]{bits(expected.totalLengthMeters()),bits(expected.directlySupportedLengthMeters()),
                        bits(expected.worstUnsupportedSpanMeters()),bits(expected.meanImageCenterCost()),bits(expected.bendPreservingRoughness())},
                        new long[]{bits(actual.totalLengthMeters()),bits(actual.directlySupportedLengthMeters()),
                        bits(actual.worstUnsupportedSpanMeters()),bits(actual.meanImageCenterCost()),bits(actual.bendPreservingRoughness())});
                }
                assertEquals(0,ledger.currentBytes());
            }
        }
    }
    @Test void rawPointTangentDirectionMagnitudeAndSignedZeroKeysDoNotAlias() {
        var image=image(0);var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var work=work(owner);var point=new MetricPoint(0,0);var tangent=new MetricPoint(4,0);
            try(var validation=PostprocessingValidationWork.open(work);var memo=image.memoizeRouteSamples(work)) {
                var first=image.sampleRoute(point,tangent);long mapped=maps(work),visits=work.visits();
                assertSame(first,image.sampleRoute(point,tangent));assertEquals(visits+1,work.visits());assertEquals(mapped,maps(work));
                for(var query:List.of(new MetricPoint[]{new MetricPoint(Math.nextUp(0.0),0),tangent},
                    new MetricPoint[]{new MetricPoint(-0.0,0),tangent},new MetricPoint[]{point,new MetricPoint(-4,0)},
                    new MetricPoint[]{point,new MetricPoint(Math.nextUp(4.0),0)},new MetricPoint[]{point,new MetricPoint(4,-0.0)})) {
                    long before=maps(work);image.sampleRoute(query[0],query[1]);assertTrue(maps(work)>before);
                }
                long before=maps(work);image(0).sampleRoute(point,tangent);assertTrue(maps(work)>before,"A different image cannot borrow this memo");
                assertThrows(IllegalArgumentException.class,()->image.sampleRoute(point,new MetricPoint(0,0)));
                assertThrows(IllegalArgumentException.class,()->image.sampleRoute(point,new MetricPoint(0,0)));
            }
            try(var validation=PostprocessingValidationWork.open(work)) {
                long before=maps(work);image.sampleRoute(point,tangent);image.sampleRoute(point,tangent);
                assertTrue(maps(work)>before,"Closed scope leaves ordinary calls uncached");
            }
            assertEquals(0,ledger.currentBytes());
        }
    }
    @Test void emptyResultsAndDeterministicEvictionPreserveOriginalSampling() {
        var image=image(0);var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var work=work(owner);var tangent=new MetricPoint(4,0);var point=new MetricPoint(0,0);
            try(var validation=PostprocessingValidationWork.open(work);var memo=image.memoizeRouteSamples(work)) {
                var original=image.sampleRoute(point,tangent);
                for(int i=0;i<2048;i++)assertTrue(image.sampleRoute(new MetricPoint(1000+i,0),tangent).isEmpty());
                long before=maps(work);assertEquals(original,image.sampleRoute(point,tangent));assertTrue(maps(work)>before,"Oldest entry must be evicted at fixed capacity");
                long visits=work.visits();assertTrue(image.sampleRoute(new MetricPoint(3047,0),tangent).isEmpty());assertEquals(visits+1,work.visits());
            }
            assertEquals(0,ledger.currentBytes());
        }
    }
    @Test void hitsRemainCancellableAndBoundToTheOriginalAbsoluteDeadline() throws Exception {
        var image=image(0);var ledger=AttemptMemoryLedger.production();var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        try(var owner=ledger.rootOwner()) {
            var work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,cancelled::get,owner);
            try(var validation=PostprocessingValidationWork.open(work);var memo=image.memoizeRouteSamples(work)) {
                image.sampleRoute(new MetricPoint(0,0),new MetricPoint(4,0));cancelled.set(true);
                assertThrows(java.util.concurrent.CancellationException.class,()->image.sampleRoute(new MetricPoint(0,0),new MetricPoint(4,0)));
            }
            cancelled.set(false);
            long deadline=System.nanoTime()+2_000_000_000L;
            work=new GeometryWorkContext(8_000_000,AttemptMemoryLedger.MAX_BYTES,deadline,CancellationProbe.NONE,owner);
            try(var validation=PostprocessingValidationWork.open(work);var memo=image.memoizeRouteSamples(work)) {
                image.sampleRoute(new MetricPoint(0,0),new MetricPoint(4,0));
                long remaining=deadline-System.nanoTime();if(remaining>0)Thread.sleep(remaining/1_000_000+1);
                assertThrows(GeometryWorkContext.LimitReached.class,()->image.sampleRoute(new MetricPoint(0,0),new MetricPoint(4,0)));
            }
            assertEquals(0,ledger.currentBytes());
        }
    }
    @Test void admissionWorkExhaustionNestedForeignContextAndWrongThreadFailClosed() throws Exception {
        var image=image(0);var small=new AttemptMemoryLedger(ImageCostField.ROUTE_MEMO_BYTES-1);
        try(var owner=small.rootOwner()) {
            var work=work(owner);try(var validation=PostprocessingValidationWork.open(work)) {
                assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->image.memoizeRouteSamples(work));assertEquals(0,small.currentBytes());assertEquals(0,maps(work));
            }
        }
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var none=new GeometryWorkContext(0,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            try(var validation=PostprocessingValidationWork.open(none)){assertThrows(GeometryWorkContext.LimitReached.class,()->image.memoizeRouteSamples(none));}
            var work=work(owner);
            assertThrows(IllegalStateException.class,()->image.memoizeRouteSamples(work));
            try(var validation=PostprocessingValidationWork.open(work);var memo=image.memoizeRouteSamples(work)) {
                assertEquals(ImageCostField.ROUTE_MEMO_BYTES,ledger.currentBytes());
                assertThrows(IllegalStateException.class,()->image.memoizeRouteSamples(work));
                assertThrows(IllegalStateException.class,()->{try(var nested=image(0).memoizeRouteSamples(work)) { }});
                var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
                var other=new Thread(()->{try{image.sampleRoute(new MetricPoint(0,0),new MetricPoint(4,0));}catch(Throwable actual){failure.set(actual);}});
                other.start();other.join();assertInstanceOf(IllegalStateException.class,failure.get());
                assertThrows(IllegalStateException.class,()->image.memoizeRouteSamples(work(owner)));
            }
            assertEquals(0,ledger.currentBytes());
            var exhausted=new GeometryWorkContext(5,AttemptMemoryLedger.MAX_BYTES,Long.MAX_VALUE,CancellationProbe.NONE,owner);
            try(var validation=PostprocessingValidationWork.open(exhausted);var memo=image.memoizeRouteSamples(exhausted)) {
                assertThrows(GeometryWorkContext.LimitReached.class,()->image.sampleRoute(new MetricPoint(0,0),new MetricPoint(4,0)));
            }
            assertEquals(0,ledger.currentBytes());
        }
    }
    private static long maps(GeometryWorkContext work){return work.counters().get(GeometryWorkContext.Kind.MAP);}
    private static long bits(double value){return Double.doubleToRawLongBits(value);}
}
