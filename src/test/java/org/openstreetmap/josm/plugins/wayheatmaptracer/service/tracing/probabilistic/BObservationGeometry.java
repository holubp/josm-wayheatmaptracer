package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.util.List;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Offline raw-graph comparison through the real solver; final-preview safety is a later gate. */
final class BObservationGeometry {
    enum Enumeration { FULL_DEFAULT, MAP_ONLY }
    record MapResult(PathOutcome path,List<Integer> states,java.util.OptionalDouble energy,
            TraceBudgets solverBudgets,Enumeration enumeration) {
        MapResult {states=List.copyOf(states);}
    }
    static MapResult solveAdmitted(List<InferenceProfile> profiles,MetricRegion region,TraceBudgets originalBudgets,
            Enumeration enumeration,CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
        return solveAdmitted(profiles,region,originalBudgets,enumeration,cancellation,owner,"admitted-profile-rows");
    }
    private static MapResult solveAdmitted(List<InferenceProfile> profiles,MetricRegion region,TraceBudgets originalBudgets,
            Enumeration enumeration,CancellationProbe cancellation,AttemptMemoryLedger.Owner owner,String modelHash) {
        java.util.Objects.requireNonNull(owner);java.util.Objects.requireNonNull(cancellation).checkpoint();
        if(profiles==null||profiles.isEmpty()||profiles.size()>4096||region==null||originalBudgets==null||enumeration==null)
            throw new IllegalArgumentException("Complete already-admitted graph inputs required");
        try(var transaction=owner.child("admitted-map-result");var work=transaction.child("admitted-map-solver");
                var mapPayload=transaction.reserve(1024L+80L*profiles.size())) {
            var requested=enumeration==Enumeration.MAP_ONLY?new TraceBudgets(originalBudgets.maximumStatesPerProfile(),
                    originalBudgets.maximumPairVisits(),originalBudgets.maximumTransitions(),1,1):originalBudgets;
            // This method neither builds nor admits a lattice. Only retained alternatives change.
            var result=new ProbabilisticInference().solve(profiles,EvidenceModelParameters.defaults(),requested,region,cancellation,
                    work,ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2);
            var path=result.mapPath();var points=path.map(ProbabilisticPath::points).orElse(List.of());
            long bytes=AttemptMemoryLedger.objectBytes(40)+2*(AttemptMemoryLedger.objectBytes(24)
                    +AttemptMemoryLedger.arrayBytes(points.size(),8))+points.size()*AttemptMemoryLedger.objectBytes(16)
                    +AttemptMemoryLedger.objectBytes(24)+AttemptMemoryLedger.arrayBytes(modelHash.length(),2);
            try(var pathPayload=transaction.reserve(bytes)) {
                var copy=new java.util.ArrayList<MetricPoint>(points.size());var states=new java.util.ArrayList<Integer>(points.size());
                for(int i=0;i<points.size();i++) {
                    cancellation.checkpoint();var p=points.get(i);copy.add(new MetricPoint(p.xMeters(),p.yMeters()));
                    states.add(path.orElseThrow().ownedStateIndicesIdentity()[i]);
                }
                var outcome=new PathOutcome(result.status(),copy,result.evaluatedTransitions(),result.alternativeSearchTruncated(),modelHash);
                pathPayload.adopt(outcome);
                var map=new MapResult(outcome,states,path.isPresent()?java.util.OptionalDouble.of(path.orElseThrow().energy()):java.util.OptionalDouble.empty(),requested,enumeration);
                mapPayload.adopt(map);cancellation.checkpoint();work.close();transaction.transferTo(owner);return map;
            }
        }
    }
    record PathOutcome(ProbabilisticInferenceResult.Status status,List<MetricPoint> points,
            long transitions,boolean truncated,String modelHash) {
        PathOutcome {points=List.copyOf(points);}
    }
    static PathOutcome solve(List<BIdentificationFacts.ProfileFacts> facts,List<ProbabilisticStateLattice> lattices,
            MetricRegion region,TraceBudgets budgets,BObservationKernel kernel,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
        return solve(facts,lattices,region,budgets,kernel,Enumeration.FULL_DEFAULT,cancellation,owner).path();
    }
    static MapResult solve(List<BIdentificationFacts.ProfileFacts> facts,List<ProbabilisticStateLattice> lattices,
            MetricRegion region,TraceBudgets budgets,BObservationKernel kernel,Enumeration enumeration,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
        java.util.Objects.requireNonNull(owner);
        java.util.Objects.requireNonNull(cancellation).checkpoint();
        if(facts.isEmpty()||facts.size()>4096||facts.size()!=lattices.size())
            throw new IllegalArgumentException("Matching bounded facts and lattices are required");
        try(var transaction=owner.child("observation-geometry-result");
                var work=transaction.child("observation-geometry-work")) {
            var parameters=EvidenceModelParameters.defaults();
            var profiles=ProbabilisticInference.allocated(work,
                    AttemptMemoryLedger.objectBytes(24)+AttemptMemoryLedger.arrayBytes(facts.size(),8),
                    "comparison profiles",()->new java.util.ArrayList<InferenceProfile>(facts.size()));
            var baseline=new ProbabilisticObservationModel();
            for(int i=0;i<facts.size();i++) {
                cancellation.checkpoint();
                profiles.add(kernel==null?baseline.evaluate(facts.get(i).baseline(),lattices.get(i),
                        parameters,true,work):BObservationAdapter.evaluate(facts.get(i),lattices.get(i),
                        parameters,true,kernel,cancellation,work));
            }
            var outcome=solveAdmitted(profiles,region,budgets,enumeration,cancellation,transaction,
                    kernel==null?"baseline:"+parameters.version():kernel.identityHash());
            cancellation.checkpoint();work.close();transaction.transferTo(owner);return outcome;
        }
    }
    private BObservationGeometry() { }
}
