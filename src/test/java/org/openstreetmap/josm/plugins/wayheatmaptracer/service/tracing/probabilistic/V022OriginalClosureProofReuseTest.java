package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricRegion;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

class V022OriginalClosureProofReuseTest {
    @Test
    void literalOriginalTuplesReuseOnlyTheTwoAlreadyCompletedProofs() {
        var receipt = receipt();
        var region = MetricRegion.rectangle(-2, -2, 2, 2);
        AtomicInteger measured = new AtomicInteger();
        int winner = receipt.rawStageWinnerSampleIndex();
        for (int bracket : new int[]{receipt.leftAdjacentSampleIndex(), receipt.rightAdjacentSampleIndex()})
            assertTrue(measure(region, winner, bracket, measured));
        assertEquals(2, measured.get());
        for (int bracket : new int[]{receipt.leftAdjacentSampleIndex(), receipt.rightAdjacentSampleIndex()})
            assertTrue(authority(receipt, region, winner, bracket, measured));
        assertEquals(2, measured.get(), "context may borrow only the identical completed proofs");
    }

    @Test
    void distinctReversedShiftedAndWiderTuplesMeasureTheirOwnSegments() {
        var receipt = receipt();
        var region = MetricRegion.rectangle(-2,-2,2,2);
        AtomicInteger measured = new AtomicInteger();
        int winner = receipt.rawStageWinnerSampleIndex(), left = receipt.leftAdjacentSampleIndex();
        for (int[] tuple : new int[][]{{left,winner},{winner+1,left},{winner,0},{winner,16}}) {
            assertTrue(authority(receipt, region, tuple[0], tuple[1], measured));
        }
        assertEquals(4, measured.get());
    }

    @Test
    void widerContextCannotReuseOriginalProofAcrossSliverHole() {
        var receipt = receipt();
        var region = new MetricRegion(List.of(MetricRegion.rectangle(-2,-2,2,.8).polygons().get(0),
                MetricRegion.rectangle(-2,.9,2,2).polygons().get(0)));
        AtomicInteger measured = new AtomicInteger();
        assertFalse(authority(receipt, region, receipt.rawStageWinnerSampleIndex(),16,measured));
        assertEquals(1, measured.get());
    }

    @Test
    void absentOrUnprovedReceiptNeverBorrowsAuthority() {
        // A missing, ambiguous, or failed full original closure supplies no proved receipt.
        AtomicInteger measured = new AtomicInteger();
        var region = MetricRegion.rectangle(-2,-2,2,2);
        assertTrue(authority(null,region,8,4,measured));
        assertEquals(1, measured.get());
        AtomicLong units = new AtomicLong();
        assertFalse(matches(null,8,4,CancellationProbe.NONE,units::addAndGet));
        assertEquals(1L,units.get());
    }

    @Test
    void onlyUniqueExactSelectedReceiptCanBecomeReuseInput() throws Exception {
        var receipt = receipt();
        var samples = new ArrayList<LocalScalarProfileExtractor.Sample>();
        for(int i=0;i<=16;i++) {
            double offset=-1+i*.125,z=offset/.25;
            samples.add(new LocalScalarProfileExtractor.Sample(offset,.02+.4*StrictMath.exp(-.5*z*z),true));
        }
        var detailed = new LocalScalarProfileExtractor().extractDetailed(samples,.25,
                EvidenceModelParameters.Localization.defaults());
        var mode = detailed.baseline().modes().stream().filter(m->Math.abs(m.centerMeters())<.1).findFirst().orElseThrow();
        var exact = detailed.rawCoreReceipts().stream().filter(r->r.mode()==mode).findFirst().orElseThrow();
        var scene = V022RmsSuppressedRasterProofTest.class.getDeclaredMethod("scene",boolean.class,boolean.class);
        scene.setAccessible(true);
        var evidence = (org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot) scene.invoke(null,false,false);
        var callback = new ProbabilisticProfileFactory.RasterIntervalEvidence(evidence,
                evidence.fields().get("scalar"),EvidenceModelParameters.Localization.defaults(),null);
        var select = callback.getClass().getDeclaredMethod("exactRawCoreReceipt",
                LocalScalarProfileExtractor.DetailedResult.class,LocalScalarProfileExtractor.Mode.class,CancellationProbe.class);
        select.setAccessible(true);
        assertSame(exact,select.invoke(callback,detailed,mode,CancellationProbe.NONE));
        var missing = new LocalScalarProfileExtractor.DetailedResult(detailed.baseline(),detailed.suppressedModes(),
                detailed.rawPeaks(),detailed.flankReceipts(),List.of());
        assertNull(select.invoke(callback,missing,mode,CancellationProbe.NONE));
        var duplicate = new LocalScalarProfileExtractor.DetailedResult(detailed.baseline(),detailed.suppressedModes(),
                detailed.rawPeaks(),detailed.flankReceipts(),List.of(exact,exact));
        assertNull(select.invoke(callback,duplicate,mode,CancellationProbe.NONE));
        assertNull(select.invoke(callback,detailed,receipt.mode(),CancellationProbe.NONE),
                "an equal but foreign shape cannot establish a selected exact receipt");
    }

    @Test
    void everyMatchComparisonIsChargedAndCancellationPrecedesReuse() {
        var receipt = receipt(); AtomicLong units = new AtomicLong();
        assertTrue(matches(receipt,receipt.rawStageWinnerSampleIndex(),receipt.leftAdjacentSampleIndex(),
                CancellationProbe.NONE,units::addAndGet));
        assertEquals(4L, units.get());
        units.set(0);
        assertThrows(WorkRefusal.class, () -> matches(receipt,receipt.rawStageWinnerSampleIndex(),
                receipt.leftAdjacentSampleIndex(),CancellationProbe.NONE,requested -> {
                    if(requested>3-units.get())throw new WorkRefusal(); units.addAndGet(requested);
                }));
        assertEquals(2L, units.get());
        assertThrows(CancellationException.class, () -> matches(receipt,receipt.rawStageWinnerSampleIndex(),
                receipt.leftAdjacentSampleIndex(),()->true,ignored -> fail("cancelled work was charged")));
    }

    private static boolean authority(LocalScalarProfileExtractor.RawCoreReceipt proved, MetricRegion region,
            int winner,int bracket,AtomicInteger measured) {
        return matches(proved,winner,bracket,CancellationProbe.NONE,ignored->{})
                || measure(region,winner,bracket,measured);
    }
    private static boolean measure(MetricRegion region,int winner,int bracket,AtomicInteger measured) {
        measured.incrementAndGet();
        return region.containsSegment(new MetricPoint(0,-1+winner*.125),new MetricPoint(0,-1+bracket*.125));
    }
    private static LocalScalarProfileExtractor.RawCoreReceipt receipt() {
        var samples = new ArrayList<LocalScalarProfileExtractor.Sample>();
        for(int i=0;i<=16;i++) {
            double offset=-1+i*.125,z=offset/.25;
            samples.add(new LocalScalarProfileExtractor.Sample(offset,.02+.4*StrictMath.exp(-.5*z*z),true));
        }
        var detailed = new LocalScalarProfileExtractor().extractDetailed(samples,.25,
                EvidenceModelParameters.Localization.defaults());
        var mode = detailed.baseline().modes().stream().filter(m->Math.abs(m.centerMeters())<.1).findFirst().orElseThrow();
        var receipt = detailed.rawCoreReceipts().stream().filter(r->r.mode()==mode).findFirst().orElseThrow();
        assertTrue(receipt.leftAdjacentObservedValid()&&receipt.leftAdjacentBelowCore()
                &&receipt.rightAdjacentObservedValid()&&receipt.rightAdjacentBelowCore());
        return receipt;
    }
    private static boolean matches(LocalScalarProfileExtractor.RawCoreReceipt receipt,int winner,int bracket,
            CancellationProbe cancellation,LongConsumer charge) {
        return ProbabilisticProfileFactory.RasterIntervalEvidence.matchesProvedOriginalDecisionPath(
                receipt,winner,bracket,cancellation,charge);
    }
    private static class WorkRefusal extends RuntimeException { }
}
