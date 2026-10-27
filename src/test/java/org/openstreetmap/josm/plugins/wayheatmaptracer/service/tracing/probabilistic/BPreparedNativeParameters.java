package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/**
 * Fresh actual defaults under one initialized-normal DATA ownership scope.
 * The840B graph covers both compact and UTF16 version storage. Earlier cold
 * initialization, source work and failures remain separate qualification gates;
 * this producer does not issue runtime or capture authority.
 */
final class BPreparedNativeParameters implements AutoCloseable {
    static final long INITIALIZED_GRAPH_BYTES=840;
    //Three references and one flag: O(25)=48, prepaid in the existing holder.
    static final long CONTROL_BYTES=48;
    private final BPhysicalWork physical;
    private final AttemptMemoryLedger.Owner owner;
    private final EvidenceModelParameters parameters;
    private boolean closed;

    private BPreparedNativeParameters(BPhysicalWork physical,AttemptMemoryLedger.Owner owner,
            EvidenceModelParameters parameters){
        this.physical=physical;this.owner=owner;this.parameters=parameters;
    }

    static BPreparedNativeParameters prepareData(BPhysicalWork physical){
        if(physical==null)throw new IllegalArgumentException("Missing parameter preparation attempt");
        var root=physical.owner();var work=physical.sourceWork();var probe=physical.probe();
        if(root.limitBytes()>64L<<20)throw new IllegalArgumentException("Parameter preparation requires64MiB root");
        probe.checkpoint();
        work.metadata(CONTROL_BYTES);
        var owner=root.child("prepared-native-parameters");
        try{
            var reservation=owner.reserve(INITIALIZED_GRAPH_BYTES);
            var parameters=EvidenceModelParameters.defaults();
            probe.checkpoint();work.ensureOpen();
            reservation.adopt(parameters);
            var result=new BPreparedNativeParameters(physical,owner,parameters);
            probe.checkpoint();result.live();
            return result;
        }catch(RuntimeException|Error primary){
            try{owner.close();}catch(RuntimeException|Error secondary){/* preserve primary */}
            throw primary;
        }
    }

    private void live(){
        if(closed||owner.isClosed())throw new IllegalStateException("Prepared native parameters are closed");
        physical.sourceWork();
    }
    EvidenceModelParameters parameters(){live();return parameters;}
    @Override public void close(){if(!closed){closed=true;owner.close();}}
}
