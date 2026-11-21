package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class SmoothingBindingHasherTest {
    private static final AtomicInteger accessed=new AtomicInteger();
    public record Row(int index,MetricPoint point) {
        @Override public int index(){accessed.incrementAndGet();return index;}
        @Override public String toString(){throw new AssertionError("Nested metadata must be streamed, never converted to text first");}
    }
    private static GeometryWorkContext work(AttemptMemoryLedger.Owner owner,long visits,CancellationProbe cancel){return new GeometryWorkContext(visits,AttemptMemoryLedger.MAX_BYTES,System.nanoTime()+60_000_000_000L,cancel,owner);}
    @Test void nativeRowMetadataStopsBeforeUnbudgetedAccessorWorkAndSettlesScratch() {
        var rows=new ArrayList<Row>();for(int i=0;i<2000;i++)rows.add(new Row(i,new MetricPoint(i,0)));accessed.set(0);
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var work=work(owner,512,CancellationProbe.NONE);
            assertThrows(GeometryWorkContext.LimitReached.class,()->{try(var hash=new SmoothingBindingHasher(work)){hash.value(rows);}});
            assertTrue(accessed.get()>0&&accessed.get()<64);assertTrue(work.visits()<=512);assertEquals(0,owner.currentBytes());
        }
        accessed.set(0);try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var work=work(owner,8_000_000,()->accessed.get()>=4);
            assertThrows(java.util.concurrent.CancellationException.class,()->{try(var hash=new SmoothingBindingHasher(work)){hash.value(rows);}});
            assertEquals(4,accessed.get());assertEquals(0,owner.currentBytes());
        }
    }
    @Test void typedMetadataAndMapOrderProduceDeterministicVersionedDigests() {
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var work=work(owner,8_000_000,CancellationProbe.NONE);var first=new LinkedHashMap<String,Object>();first.put("z",List.of(new Row(1,new MetricPoint(3,4))));first.put("a",Optional.of("factual"));
            var second=new LinkedHashMap<String,Object>();second.put("a",Optional.of("factual"));second.put("z",List.of(new Row(1,new MetricPoint(3,4))));String a,b;
            try(var hash=new SmoothingBindingHasher(work)){hash.value(first);a=hash.finish();}try(var hash=new SmoothingBindingHasher(work)){hash.value(second);b=hash.finish();}
            assertEquals(a,b);assertEquals(64,a.length());assertEquals(0,owner.currentBytes());
        }
    }
    @Test void metadataPreflightChecksEveryInheritedWarningBeforeReservation() {
        var root=RefinementParentFixtures.authority(AlignmentMode.PRECISE_SHAPE);var parent=RefinementParentFixtures.parent(root,"blue");var old=parent.result();var original=old.candidates().get(1);
        var warnings=new ArrayList<String>();for(int i=0;i<10000;i++)warnings.add("inherited-warning-"+i);
        var candidate=new CenterlineCandidate(original.id(),1,List.of(),List.of(),original.eastNorthPoints(),original.finalPreviewPoints(),Map.of(),List.of(),1,CandidateEvidence.empty(),CandidateCleanupEvidence.empty(),warnings);
        var result=new AlignmentResult(old.selection(),null,List.of(candidate),old.sourcePolyline(),candidate.finalPreviewPoints(),List.of(),null,null,old.detectorAttempts(),List.of());
        var payload=new org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.Candidate(parent.id(),result,parent.correspondence(),"warning-parent").payload();
        try(var owner=AttemptMemoryLedger.production().rootOwner()) {
            var work=work(owner,12,CancellationProbe.NONE);assertThrows(GeometryWorkContext.LimitReached.class,()->payload.smoothingFactsBytes(candidate.id(),work));
            assertEquals(12,work.visits());assertEquals(0,owner.currentBytes());
        }
    }
}
