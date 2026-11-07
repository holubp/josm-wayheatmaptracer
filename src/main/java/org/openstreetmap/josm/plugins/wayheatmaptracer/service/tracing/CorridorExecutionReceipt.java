package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayCodec;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.ScalarReplayFingerprint;

/** Small immutable A-only receipt. No tracker, domain, source field or candidate geometry is retained. */
public final class CorridorExecutionReceipt {
    public enum Phase { SAMPLING, SOURCE_CERTIFICATE, TRACKING, ADAPTING, COMPLETED }
    public enum Availability { COMPLETE, UNAVAILABLE }
    private static final int MAX_TRACK_DIAGNOSTICS=512;

    /** Exact single-component row mapping encoded with constant storage. */
    public record Mapping(int originalCount,int leftOriginalRow,int rightOriginalRow){
        public Mapping {
            if(originalCount<2||leftOriginalRow<0||rightOriginalRow>=originalCount||rightOriginalRow<=leftOriginalRow+1)
                throw new IllegalArgumentException("Invalid native correspondence mapping");
        }
        public int consumedCount(){return rightOriginalRow-leftOriginalRow-1;}
        public int workingCount(){return originalCount-consumedCount();}
        public int originalRow(int working){
            if(working<0||working>=workingCount())throw new IndexOutOfBoundsException(working);
            return working<=leftOriginalRow?working:working+consumedCount();
        }
    }
    public record TrackWork(String trackId,CorridorCenterlineOptimizer.PreparedWork preparation,
        CorridorCenterlineOptimizer.HardGeometryWork hardGeometry,String admissionStatus,long contactTests,
        long pairTests,long rayTests,long nativeChecks,long adjacentObjectiveEntries,Optional<Mapping> mapping){ }
    public record Totals(long originalStates,long retainedUnaryEvaluations,long bridgeUnaryEvaluations,
        long admissionContacts,long admissionPairs,long adjacentObjectiveEntries,long solverStarts,
        long attemptedTransitions,long testedTriples,long rejectedTriples,long scoredTransitions){
        public long unaryEvaluations(){return Math.addExact(retainedUnaryEvaluations,bridgeUnaryEvaluations);}
        public long pairVisits(){return Math.addExact(Math.addExact(admissionContacts,admissionPairs),adjacentObjectiveEntries);}
    }

    /** Sealed provenance from an actual prepared optimization, never an interpreted diagnostic flag. */
    public static final class CandidateProvenance {
        private final String hypothesisId,trackId;
        private final Mapping mapping;
        private final CleanupEvidenceStatus cleanupStatus;
        private CandidateProvenance(String hypothesisId,String trackId,Mapping mapping,CleanupEvidenceStatus cleanupStatus){
            this.hypothesisId=hypothesisId;this.trackId=trackId;this.mapping=mapping;this.cleanupStatus=cleanupStatus;
        }
        public String hypothesisId(){return hypothesisId;}
        public String trackId(){return trackId;}
        public Mapping mapping(){return mapping;}
        public CleanupEvidenceStatus cleanupStatus(){return cleanupStatus;}
        public boolean cleanupUnavailable(){return mapping.consumedCount()>0&&cleanupStatus==CleanupEvidenceStatus.MISALIGNED_CANDIDATE_ROWS;}
    }

    private final String requestHash,evidenceHash,networkHash,resultHash,certificateStatus,networkSnapshotId,settingsHash;
    private final OptionalInt sourceOccurrences,fixedSourceCoordinates;
    private final Optional<String> certificateIdentity;
    private final Phase phase;
    private final Optional<DetachedScalarProfileSampler.RadiusThreeWork> sampling;
    private final OptionalInt discoveredTracks;
    private final Map<String,TrackWork> tracks;
    private final int visitedTracks;
    private final Map<String,CandidateProvenance> candidates;
    private final Totals totals;
    private CorridorExecutionReceipt(String requestHash,String evidenceHash,String networkHash,String resultHash,
        String certificateStatus,Optional<String> certificateIdentity,String networkSnapshotId,String settingsHash,
        OptionalInt sourceOccurrences,OptionalInt fixedSourceCoordinates,Phase phase,
        Optional<DetachedScalarProfileSampler.RadiusThreeWork> sampling,OptionalInt discoveredTracks,
        Map<String,TrackWork> tracks,int visitedTracks,Map<String,CandidateProvenance> candidates,Totals totals){
        this.requestHash=requestHash;this.evidenceHash=evidenceHash;this.networkHash=networkHash;this.resultHash=resultHash;
        this.certificateStatus=certificateStatus;this.certificateIdentity=certificateIdentity;this.phase=phase;this.sampling=sampling;
        this.networkSnapshotId=networkSnapshotId;this.settingsHash=settingsHash;this.sourceOccurrences=sourceOccurrences;this.fixedSourceCoordinates=fixedSourceCoordinates;
        this.discoveredTracks=discoveredTracks;this.tracks=Map.copyOf(tracks);this.visitedTracks=visitedTracks;
        this.candidates=Map.copyOf(candidates);this.totals=totals;
    }
    public String requestHash(){return requestHash;} public String evidenceHash(){return evidenceHash;}
    public String networkHash(){return networkHash;} public String resultHash(){return resultHash;}
    public String networkSnapshotId(){return networkSnapshotId;} public String settingsHash(){return settingsHash;}
    public OptionalInt sourceOccurrences(){return sourceOccurrences;} public OptionalInt fixedSourceCoordinates(){return fixedSourceCoordinates;}
    /** The compatibility run-memory seam is not connected to a factual A heap owner. */
    public Availability retainedHeapPeakAvailability(){return Availability.UNAVAILABLE;}
    public String certificateStatus(){return certificateStatus;} public Optional<String> certificateIdentity(){return certificateIdentity;}
    public Phase phase(){return phase;} public Optional<DetachedScalarProfileSampler.RadiusThreeWork> sampling(){return sampling;}
    public Availability samplingAvailability(){return sampling.isPresent()?Availability.COMPLETE:Availability.UNAVAILABLE;}
    public OptionalInt discoveredTracks(){return discoveredTracks;}
    public Map<String,TrackWork> tracks(){return tracks;} public int visitedTracks(){return visitedTracks;}
    public boolean trackDiagnosticsTruncated(){return tracks.size()!=visitedTracks;}
    public Map<String,CandidateProvenance> candidates(){return candidates;} public Totals totals(){return totals;}

    /** Validates the actual frozen invocation and entire native raw result before consuming provenance. */
    public void requireBinding(TraceRequest request,EvidenceSnapshot evidence,NetworkSnapshot network,TraceHypothesisSet result){
        if(!requestHash.equals(FrozenReplayCodec.canonicalRequestHash(request))||!evidenceHash.equals(evidence.canonicalHash())
            ||!networkHash.equals(network.canonicalHash())||!resultHash.equals(ScalarReplayFingerprint.sha256(result)))
            throw new IllegalArgumentException("A execution receipt binding differs");
    }

    static Mapping mapping(CorridorWorkingSchedule schedule){
        return new Mapping(schedule.originalCount(),schedule.leftOriginalRow(),schedule.rightOriginalRow());
    }
    static Totals totals(CorridorAwareTracker tracker){
        long[] sums=new long[11];
        if(tracker!=null)tracker.visitCorrespondenceWork((id,item)->{
            var p=item.preparation();var h=item.hardGeometry();var a=item.admission();
            // These are two snapshots of the SAME retained-unary quantity. Ordinary resource
            // failure may publish the hard-work snapshot before prepared-work completion.
            long[] values={p.originalStates(),Math.max(p.retainedUnaryEvaluations(),h.profileCostEvaluations()),p.bridgeUnaryEvaluations(),
                a.map(CorridorWorkingSchedule.Preparation::contactTests).orElse(0L),a.map(CorridorWorkingSchedule.Preparation::pairTests).orElse(0L),
                item.adjacentObjectiveEntries(),p.solverStarts(),h.attemptedTransitions(),h.testedTriples(),h.rejectedTriples(),h.scoredTransitions()};
            for(int i=0;i<sums.length;i++)sums[i]=Math.addExact(sums[i],values[i]);
        });
        return new Totals(sums[0],sums[1],sums[2],sums[3],sums[4],sums[5],sums[6],sums[7],sums[8],sums[9],sums[10]);
    }
    static CorridorExecutionReceipt freeze(TraceRequest request,EvidenceSnapshot evidence,NetworkSnapshot network,
        TraceHypothesisSet result,Phase phase,Optional<DetachedScalarProfileSampler.RadiusThreeWork> sampling,
        CorridorSourceProtection.Certificate certificate,CorridorAwareTracker tracker,CorridorAwareTracker.TrackingResult tracked){
        var work=new LinkedHashMap<String,TrackWork>();int[] visited={0};
        if(tracker!=null){
            tracker.visitCorrespondenceWork((id,item)->{
                visited[0]++;
                if(work.size()==MAX_TRACK_DIAGNOSTICS)return;
                var a=item.admission().orElse(null);
                work.put(id,new TrackWork(id,item.preparation(),item.hardGeometry(),a==null?"UNAVAILABLE":a.status(),
                    a==null?0:a.contactTests(),a==null?0:a.pairTests(),a==null?0:a.rayTests(),a==null?0:a.nativeChecks(),item.adjacentObjectiveEntries(),
                    a==null||!a.admitted()?Optional.empty():Optional.of(mapping(a.schedule()))));
            });
        }
        var provenance=new LinkedHashMap<String,CandidateProvenance>();
        if(tracked!=null&&tracker!=null)for(var hypothesis:result.hypotheses()){
            var prepared=tracker.preparedOptimization(hypothesis.branchSignature()).orElse(null);
            if(prepared==null)continue;
            var candidate=tracked.candidates().stream().filter(c->c.id().equals(hypothesis.branchSignature())).findFirst().orElseThrow();
            var map=mapping(prepared.schedule());
            if(candidate.offsetsPx().size()!=map.workingCount()||hypothesis.points().size()!=map.workingCount()
                ||candidate.cleanupEvidence().status()!=CleanupEvidenceStatus.MISALIGNED_CANDIDATE_ROWS)
                throw new IllegalArgumentException("Prepared candidate lost actual mapping/cleanup provenance");
            provenance.put(hypothesis.id(),new CandidateProvenance(hypothesis.id(),candidate.id(),map,candidate.cleanupEvidence().status()));
        }
        return new CorridorExecutionReceipt(FrozenReplayCodec.canonicalRequestHash(request),evidence.canonicalHash(),network.canonicalHash(),
            ScalarReplayFingerprint.sha256(result),certificate==null?"UNAVAILABLE":certificate.status(),
            certificate==null?Optional.empty():Optional.of(certificate.identity()),network.snapshotId(),request.settingsHash(),
            certificate==null?OptionalInt.empty():OptionalInt.of(certificate.occurrences().size()),
            certificate==null?OptionalInt.empty():OptionalInt.of((int)certificate.occurrences().stream().filter(CorridorSourceProtection.Occurrence::fixedCoordinate).count()),phase,sampling,
            tracker==null?OptionalInt.empty():tracker.correspondenceDiscoveredTracks(),work,visited[0],provenance,totals(tracker));
    }
}
