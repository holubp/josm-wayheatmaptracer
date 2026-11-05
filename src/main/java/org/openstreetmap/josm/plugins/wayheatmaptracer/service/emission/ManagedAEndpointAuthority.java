package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.util.*;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;

/** Captured endpoint correspondence only. A's private native inventory still issues every state owner. */
public final class ManagedAEndpointAuthority {
    public static final String POLICY_ID="MANAGED_A_ENDPOINT_ONLY_PHYSICAL_V1/FLOAT_L0_Q_V1";
    public static final class Endpoint {
        private final ManagedAEndpointAuthority authority;
        private final int row;
        private final RenderedHeatmapSampler.CrossSectionProfile profile;
        private final SourceParameterCertificate.SourceAlias alias;
        private final SourceParameterCertificate.OriginalOccurrence occurrence;
        private final RasterPoint nativeRaster,evidenceRaster;
        private Endpoint(ManagedAEndpointAuthority authority,int row,
                RenderedHeatmapSampler.CrossSectionProfile profile,SourceParameterCertificate.SourceAlias alias,
                SourceParameterCertificate.OriginalOccurrence occurrence,RasterPoint p,RasterPoint evidence){
            this.authority=authority;this.row=row;this.profile=profile;this.alias=alias;this.occurrence=occurrence;
            nativeRaster=p;evidenceRaster=evidence;
        }
        public int row(){authority.requireOpen();return row;}
        public RasterPoint nativeRaster(){authority.requireOpen();return nativeRaster;}
        public RasterPoint evidenceRaster(){authority.requireOpen();return evidenceRaster;}
        public MetricPoint exactMetric(){authority.requireOpen();return occurrence.exactMetric();}
        public LatLon exactGeographic(){authority.requireOpen();return occurrence.exactGeographic();}
        public String occurrenceId(){authority.requireOpen();return occurrence.occurrenceId();}
        public long nodeId(){authority.requireOpen();return occurrence.osmNodeId();}
        /** Exact source issuance/identity, never a caller's alias descriptor or value-nearby state. */
        public void requireNativeZero(RenderedHeatmapSampler.CrossSectionProfile profile,RasterPoint actual,double offset,
                EndpointConstraint constraint){
            authority.requireOpen();
            if(this.profile!=profile||!nativeRaster.equals(actual)||Double.doubleToRawLongBits(offset)!=0L
                    ||authority.junctions.at(row)!=constraint||!constraint.fixed()||constraint.nodeId()!=nodeId())
                throw new IllegalArgumentException("Managed A endpoint differs from its actual captured native zero state");
        }
    }
    private final OptionalEmissionContextFactory.ManagedCaptureBinding permit;
    private final RetainedManagedScalarProfiles retained;
    private final AttemptMemoryLedger.Owner owner;
    private final JunctionContext junctions;
    private final List<Endpoint> endpoints;
    private SampledEmissionInput input;
    ManagedAEndpointAuthority(OptionalEmissionContextFactory.ManagedCaptureBinding permit,
            RetainedManagedScalarProfiles retained,OptionalEmissionContext context){
        if(!permit.isManagedA()||!retained.isManagedA()||!retained.boundTo(permit.retainedOwner(),permit.workBudget()))
            throw new IllegalArgumentException("Captured A producer authority is unavailable");
        this.permit=permit;this.retained=retained;owner=permit.retainedOwner();var budget=permit.workBudget();
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);var memory=owner.reserve(4096);
        var certificate=retained.sourceParameters().orElseThrow();var occurrences=certificate.originalOccurrences();
        var profiles=retained.profiles();int last=profiles.size()-1;
        var issued=new ArrayList<Endpoint>();var constraints=new ArrayList<EndpointConstraint>();
        for(int side=0;side<2;side++){
            budget.charge(EmissionWorkBudget.Operation.PREPARATION,32L+certificate.aliases().size());
            int row=side==0?0:last,vertex=side==0?0:occurrences.size()-1;
            var occurrence=occurrences.get(vertex);
            SourceParameterCertificate.SourceAlias alias=null;
            for(var a:certificate.aliases())if(a.row()==row&&a.originalVertex()==vertex){if(alias!=null)throw new IllegalArgumentException("Endpoint alias is ambiguous");alias=a;}
            if(alias==null||!occurrence.explicitlyFixed()||!Double.isFinite(alias.metricErrorUpperMeters())||alias.metricErrorUpperMeters()<0)
                throw new IllegalArgumentException("Captured endpoint has no certified producer alias");
            var profile=profiles.get(row);RasterPoint p=profile.samplingAnchor().rasterPoint();
            if(context.metricAt(p).distanceTo(occurrence.exactMetric())>alias.metricErrorUpperMeters())
                throw new IllegalArgumentException("Endpoint is outside its source-issued alias enclosure");
            RasterPoint evidence=context.rasterAt(occurrence.exactMetric());
            try(var scratch=owner.child("managed-a-endpoint-support")){
                if(!context.routePositionAuthorized(occurrence.exactMetric(),budget,scratch)
                        ||!context.routeSegmentAuthorized(p,evidence,budget,scratch)
                        ||!context.evidenceSegmentAuthorized(p,evidence,budget,scratch))
                    throw new IllegalArgumentException("Captured endpoint mapping/support is unproved");
            }
            var grid=context.nativeGrid();var g=grid.toGrid(evidence);
            int x=Math.min(grid.width()-2,(int)Math.floor(g.x())),y=Math.min(grid.height()-2,(int)Math.floor(g.y()));
            budget.charge(EmissionWorkBudget.Operation.CELL_QUERY,1);
            if(g.x()<0||g.y()<0||g.x()>grid.width()-1.0||g.y()>grid.height()-1.0||!grid.supportsCell(x,y))
                throw new IllegalArgumentException("Physical endpoint has no strict float-cell support");
            issued.add(new Endpoint(this,row,profile,alias,occurrence,p,evidence));
            constraints.add(new EndpointConstraint(row,occurrence.osmNodeId(),true,
                permit.capture().selectionWitness().orElseThrow().capturedSharedWayAtOrdinal(vertex),0,0,
                permit.capture().nativeAEndpointApproachWindow()));
        }
        endpoints=List.copyOf(issued);junctions=new JunctionContext(constraints);memory.adopt(this);
    }
    void attachInput(SampledEmissionInput actual){
        if(input!=null||actual.context().managedAEndpoints().orElse(null)!=this)throw new IllegalArgumentException("Managed A endpoint input is foreign");
        input=actual;
    }
    private void requireOpen(){if(owner.isClosed())throw new IllegalStateException("Managed A endpoint authority is closed");if(input!=null)input.requireRetainedOpen();}
    public JunctionContext junctions(){requireOpen();return junctions;}
    public List<Endpoint> endpoints(){requireOpen();return endpoints;}
    public Endpoint at(int row){requireOpen();return row==0?endpoints.get(0):row==retained.profiles().size()-1?endpoints.get(1):null;}
    public void requireInvocation(JunctionContext actual,List<NativeConnectorEvaluator.Barrier> barriers){
        requireOpen();if(junctions!=actual||!barriers.isEmpty())throw new IllegalArgumentException("Managed A requires its captured endpoint-only invocation");
    }
    long finalPublicationWork(){
        requireOpen();var certificate=retained.sourceParameters().orElseThrow();
        return Math.addExact(64L,Math.multiplyExact(2L,Math.addExact((long)certificate.aliases().size(),certificate.originalOccurrences().size())));
    }
    void requireUnchangedForFinalPublication(){
        requireOpen();var certificate=retained.sourceParameters().orElseThrow();
        for(var endpoint:endpoints){
            boolean aliasFound=false,occurrenceFound=false;
            for(var a:certificate.aliases())if(a==endpoint.alias)aliasFound=true;
            for(var occurrence:certificate.originalOccurrences())if(occurrence==endpoint.occurrence)occurrenceFound=true;
            if(retained.profiles().get(endpoint.row)!=endpoint.profile
                    ||!aliasFound||!occurrenceFound)
                throw new IllegalStateException("Captured A endpoint producer changed");
        }
    }
}
