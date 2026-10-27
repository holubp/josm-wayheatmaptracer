package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceReadView;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.ProfileChainage;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/**
 * Real initialized-normal DATA preparation and lifetime mechanics. The checked
 * graph covers all per-call working objects, not just the returned chainage.
 * Cold Java bootstrap, failure and source-work qualification remain open.
 * The fixed runtime caller's existing gates must dominate this body until
 * those prerequisites are supplied; this class issues no permit or warm flag.
 */
final class BPreparedNativeChainage implements AutoCloseable {
    static final long INITIALIZED_GRAPH_BYTES=12416;
    //Five references and one flag: O(41)=64, prepaid in the existing holder.
    static final long CONTROL_BYTES=64;
    private final BPhysicalWork physical;
    private final BExperimentalScalarCapture capture;
    private final EvidenceReadView view;
    private final AttemptMemoryLedger.Owner owner;
    private final ProfileChainage chainage;
    private boolean closed;

    private BPreparedNativeChainage(BPhysicalWork physical,BExperimentalScalarCapture capture,
            EvidenceReadView view,AttemptMemoryLedger.Owner owner,ProfileChainage chainage){
        this.physical=physical;this.capture=capture;this.view=view;this.owner=owner;this.chainage=chainage;
    }

    static BPreparedNativeChainage prepareData(BExperimentalScalarCapture capture,EvidenceReadView view,
            BExperimentalScalarCapture.AcquisitionPath path,BPhysicalWork physical){
        if(capture==null||view==null||path==null||physical==null)
            throw new IllegalArgumentException("Incomplete captured native preparation");
        var root=physical.owner();var work=physical.sourceWork();var probe=physical.probe();
        if(root.limitBytes()>64L<<20)throw new IllegalArgumentException("Native preparation requires64MiB root");
        capture.requireProduced(view,root,work,probe);
        long points=path.pointCountIssued(view,physical);
        if(points<2||points>12)throw new IllegalArgumentException("Bounded original source count");
        probe.checkpoint();
        work.metadata(CONTROL_BYTES);
        var owner=root.child("prepared-native-chainage");
        try{
            var reservation=owner.reserve(INITIALIZED_GRAPH_BYTES);
            //Both the factory shell and all sampler temporaries are prepaid.
            var chainage=new ProbabilisticProfileFactory().profileChainageCancellableBounded(
                    path.metric(),1.5,probe);
            probe.checkpoint();
            capture.requireProduced(view,root,work,probe);
            path.pointCountIssued(view,physical);
            reservation.adopt(chainage);
            var result=new BPreparedNativeChainage(physical,capture,view,owner,chainage);
            probe.checkpoint();result.live();
            return result;
        }catch(RuntimeException|Error primary){
            try{owner.close();}catch(RuntimeException|Error secondary){/* preserve primary */}
            throw primary;
        }
    }

    private void live(){
        if(closed||owner.isClosed())throw new IllegalStateException("Prepared native chainage is closed");
        capture.requireProduced(view,physical.owner(),physical.sourceWork(),physical.probe());
    }
    ProfileChainage chainage(){live();return chainage;}
    @Override public void close(){if(!closed){closed=true;owner.close();}}
}
