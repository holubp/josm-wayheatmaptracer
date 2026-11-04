package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.SampledEmissionInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.EmissionWorkBudget;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Same-attempt admission/checkpoint scope for the unchanged native preprocessing phases. */
final class NativeAPreprocessing {
    final SampledEmissionInput input;
    final AttemptMemoryLedger.Owner owner;
    private int trackingRows,maximumTrackingBands;
    NativeAPreprocessing(SampledEmissionInput input,AttemptMemoryLedger.Owner owner) {
        this.input=input;this.owner=owner;
    }
    void checkpoint(){work(0);}
    void work(long units) {
        try{input.workBudget().charge(EmissionWorkBudget.Operation.MODE_EXTRACTION,units);}
        catch(RuntimeException failure){fail(failure);throw failure;}
    }
    void reserve(long bytes) {
        try {
            input.workBudget().charge(EmissionWorkBudget.Operation.ALLOCATION,1);owner.reserve(bytes);
        }catch(RuntimeException failure){fail(failure);throw failure;}
    }
    private void fail(RuntimeException failure) {
        if(failure instanceof EmissionWorkBudget.LimitExceededException
                ||failure instanceof AttemptMemoryLedger.ResourceLimitException||failure instanceof CancellationException)
            input.recordTerminalExecutionFailure(failure);
    }
    private long product(long... terms) {
        try{long result=1;for(long term:terms)result=Math.multiplyExact(result,term);return result;}
        catch(ArithmeticException overflow){work(Long.MAX_VALUE);throw overflow;}
    }
    private long sum(long... terms) {
        try{long result=0;for(long term:terms)result=Math.addExact(result,term);return result;}
        catch(ArithmeticException overflow){work(Long.MAX_VALUE);throw overflow;}
    }
    private long logarithm(long size){return size<2?1:64-Long.numberOfLeadingZeros(size-1);}

    void admitExtraction(int samples) {
        reserve(sum(4096,product(samples,1024)));
        work(sum(512,product(samples,logarithm(samples),8),product(samples,192)));
    }
    void admitNestedIntervals(long shoulders,long intervals) {
        work(sum(64,product(shoulders,intervals,32)));
    }
    void admitBand(long samples,long intervals) {
        reserve(sum(1024,product(intervals,256)));
        work(sum(128,product(samples,32),product(intervals,64),product(intervals,logarithm(intervals),8)));
    }
    void admitAssociation(List<List<CorridorProfile>> levels) {
        if(levels.isEmpty())return;
        long bytes=4096,units=0;
        for(int row=0;row<levels.get(0).size();row++) {
            work(1);long fine=levels.get(0).get(row).bands().size();
            units=sum(units,64,product(levels.size(),32),product(fine,64));
            bytes=sum(bytes,product(levels.size(),128),product(fine,sum(256,product(levels.size(),64))));
            for(int level=1;level<levels.size();level++) {
                work(1);
                long coarse=levels.get(level).get(row).bands().size();
                units=sum(units,product(fine,sum(product(coarse,logarithm(coarse),8),product(coarse,32),product(fine,32),64)));
                bytes=sum(bytes,product(fine,coarse,32));
            }
        }
        reserve(bytes);work(units);
    }

    void admitSeedCollection(List<CorridorProfile> profiles) {
        trackingRows=profiles.size();maximumTrackingBands=0;
        for(var profile:profiles){work(1);maximumTrackingBands=Math.max(maximumTrackingBands,profile.bands().size());}
        int stride=Math.max(1,(trackingRows-1)/8);
        long targets=(trackingRows+(long)stride-1)/stride;
        // The native post-profile16 check allows the last profile to append all of its bands.
        long seedUpper=sum(15,maximumTrackingBands);
        reserve(sum(1024,product(seedUpper,128),product(maximumTrackingBands,64)));
        work(sum(product(targets,trackingRows,sum(maximumTrackingBands,1),16),product(seedUpper,seedUpper,8)));
    }
    void admitSeeds(int count) {
        reserve(sum(1024,product(count,sum(512,product(trackingRows,256)))));
        work(product(count,sum(trackingRows,1),64));
    }
    void admitTrackingRow(int states,int bands) {
        long attempts=product(states,sum(bands,1));
        reserve(sum(256,product(attempts,sum(512,product(trackingRows,128)))));
        // Original continuation scans at most16 gap profiles plus the two3-profile lookaheads.
        // Admission uses actual current states and bands, not a guessed seed/track ceiling.
        work(sum(product(attempts,sum(512,product(maximumTrackingBands,512),product(trackingRows,16))),
            product(attempts,logarithm(attempts),16)));
    }
    void admitTrackDeduplication(int tracks) {
        reserve(sum(256,product(tracks,64)));
        work(sum(product(tracks,tracks,sum(trackingRows,1),32),product(tracks,logarithm(tracks),8)));
    }

    void admitGrouping(List<CorridorTrack> tracks,List<CorridorProfile> profiles) {
        long t=tracks.size(),n=profiles.size(),samples=0;
        for(var profile:profiles){work(1);samples=sum(samples,profile.source().intensitySamples().size());}
        long pairs=product(t,Math.max(0,t-1))/2;
        // Retained pair evidence/decision/key/map entries, group reference tables, plus one
        // reusable pair's union/valley/tangent/sort scratch capacity. No16-track assumption.
        reserve(sum(4096,product(t,512),product(pairs,1024),product(t,t,64),product(n,1024)));
        long perPair=sum(512,product(samples,4),product(n,logarithm(n),64),product(n,128));
        long compatibility=product(t,t,t,sum(n,8),16);
        work(sum(product(pairs,perPair),compatibility,product(t,logarithm(t),sum(n,1),16)));
    }

    void admitParent(List<CorridorTrack> children,List<CorridorProfile> profiles) {
        long c=children.size(),n=profiles.size();
        // At most N bundle/parent points, each retaining all C actual contributor references.
        // Bounded prediction can inspect/sort the original child rows; keep those terms explicit.
        reserve(sum(1024,product(n,c,512),product(n,1024),product(c,c,128)));
        long perProfile=sum(product(c,n,logarithm(n),64),product(c,c,32),product(c,128));
        work(sum(product(n,perProfile),product(c,c,64)));
    }

    void admitParentEvidence(List<CorridorTrack> tracks,java.util.Map<String,BandScaleEvidence> evidence) {
        long points=0,children=0,levels=0;
        for(var value:evidence.values()){work(1);levels=Math.max(levels,value.participatingLevels().size());}
        for(var track:tracks) {
            work(1);if(!track.parent())continue;
            for(var point:track.points().values()){work(1);points=sum(points,1);children=sum(children,point.band().childIds().size());}
        }
        reserve(sum(1024,product(evidence.size(),128),product(points,256),product(children,sum(64,product(levels,32)))));
        work(sum(product(evidence.size(),8),product(children,sum(64,product(levels,logarithm(sum(product(children,levels),1)),8)))));
    }
}
