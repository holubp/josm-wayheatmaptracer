package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Sole replacement adapter, shared by offline inference and eventual production promotion. */
final class BObservationAdapter {
    static InferenceProfile evaluate(BIdentificationFacts.ProfileFacts facts,ProbabilisticStateLattice lattice,
            EvidenceModelParameters parameters,boolean attenuateOrientation,BObservationKernel kernel,
            CancellationProbe cancellation,AttemptMemoryLedger.Owner owner) {
        java.util.Objects.requireNonNull(cancellation).checkpoint();
        var transaction=owner==null?null:owner.child("observation-replacement");
        var scratch=transaction==null?null:transaction.child("replacement-scratch");
        try {
            var baseline=new ProbabilisticObservationModel().evaluate(facts.baseline(),lattice,
                    parameters,attenuateOrientation,scratch);
            cancellation.checkpoint();
            var mixture=kernel.evaluate(facts,lattice,cancellation,scratch);
            var rows=mixture.reduce(lattice,cancellation,scratch);
            if(rows.stateCount()!=lattice.cells().size()||rows.componentCount()!=lattice.components().size())
                throw new IllegalArgumentException("Replacement rows do not match admitted lattice");
            double[] unary=allocate(scratch,AttemptMemoryLedger.arrayBytes(rows.stateCount(),Double.BYTES),
                    ()->new double[rows.stateCount()]);
            double[][] responsibilities=allocate(scratch,ProbabilisticInference.deepArrayBytes(
                    rows.stateCount(),rows.componentCount(),Double.BYTES),
                    ()->new double[rows.stateCount()][rows.componentCount()]);
            for(int state=0;state<rows.stateCount();state++) {
                cancellation.checkpoint();unary[state]=rows.unaryCost(state);
                for(int component=0;component<rows.componentCount();component++)
                    responsibilities[state][component]=rows.responsibility(state,component);
            }
            var result=baseline.withObservationRows(unary,responsibilities,transaction);
            cancellation.checkpoint();
            if(scratch!=null)scratch.close();
            if(transaction!=null)transaction.transferTo(owner);
            return result;
        } finally {if(transaction!=null)transaction.close();}
    }
    private static <T> T allocate(AttemptMemoryLedger.Owner owner,long bytes,java.util.function.Supplier<T> factory) {
        return owner==null?factory.get():ProbabilisticInference.allocated(owner,bytes,"replacement rows",factory);
    }
    private BObservationAdapter() { }
}
