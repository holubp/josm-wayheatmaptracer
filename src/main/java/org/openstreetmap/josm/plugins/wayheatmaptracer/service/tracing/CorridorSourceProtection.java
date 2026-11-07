package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.CorridorWorkingSchedule;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayCodec;

/** Pure detached source-occurrence certificate. Only this validating factory can construct one. */
public final class CorridorSourceProtection {
    private CorridorSourceProtection() { }

    /** Full selected occurrence facts, including nodes between sampled profiles. */
    public record Occurrence(int originalIndex, PrimitiveKey key, GeographicPoint geographic,
        MetricPoint metric, double localChainageMeters, double absoluteChainageMeters,
        Map<String,String> tags, Set<PrimitiveKey> referrers, boolean protectedMembership,
        boolean movableMembership, boolean removableMembership, boolean replacementAuthorized,
        boolean additionalFixed, boolean ambiguousSourceFraction, boolean fixedCoordinate) {
        public Occurrence { tags=Map.copyOf(tags); referrers=Set.copyOf(referrers); }
    }

    /** Unforgeable immutable result. Refusals retain facts but never supply an empty allow-list. */
    public static final class Certificate {
        private final String status, identity, field;
        private final TraceRequest request;
        private final EvidenceSnapshot evidence;
        private final NetworkSnapshot network;
        private final List<Occurrence> occurrences;
        private final CorridorWorkingSchedule.SourceProtection protection;
        private Certificate(String status,String identity,String field,TraceRequest request,
            EvidenceSnapshot evidence,NetworkSnapshot network,List<Occurrence> occurrences,
            CorridorWorkingSchedule.SourceProtection protection) {
            this.status=status;this.identity=identity;this.field=field;this.request=request;
            this.evidence=evidence;this.network=network;this.occurrences=List.copyOf(occurrences);this.protection=protection;
        }
        public String status(){return status;}
        public boolean available(){return protection!=null;}
        public String identity(){return identity;}
        public List<Occurrence> occurrences(){return occurrences;}
        public Optional<CorridorWorkingSchedule.SourceProtection> protection(){return Optional.ofNullable(protection);}
        /** Actual native field/frame binding; refused certificates cannot authorize a prepared domain. */
        public CorridorWorkingSchedule.BoundContext bind(CancellationProbe cancellation){
            if(!available())throw new IllegalStateException(status);
            return CorridorWorkingSchedule.bindContext(FrozenReplayCodec.canonicalRequestHash(request),
                network.canonicalHash(),evidence,field,protection,identity,cancellation);
        }
    }

    /** The unchanged native source-coordinate permission rule, also consumed by finalization. */
    record Permission(boolean protectedCoordinate,boolean movable,boolean removable,boolean fixed){ }
    static Permission permission(NetworkSnapshot network,PrimitiveKey key,boolean replacement,
            boolean additionalFixed,boolean ambiguous){
        var closure=network.closure();
        boolean protect=closure.protectedExistingNodeKeys().contains(key)&&!replacement;
        boolean movable=closure.movableExistingNodeKeys().contains(key)||replacement;
        boolean removable=closure.removableExistingNodeKeys().contains(key);
        if(!protect&&!movable&&!removable)
            throw new IllegalArgumentException("Selected existing occurrence lacks movement, protection or removal authority");
        return new Permission(protect,movable,removable,protect||!movable||ambiguous||additionalFixed);
    }

    /**
     * Derives source authority without dataset mutation, inference, image fitting or supplied booleans.
     * Missing extra completeness is a typed refusal; contradictory native bindings remain exceptions.
     */
    public static Certificate inspect(TraceRequest request,EvidenceSnapshot evidence,NetworkSnapshot network,
            String field,CancellationProbe cancellation){
        Objects.requireNonNull(cancellation,"cancellation");cancellation.checkpoint();
        if(request==null||evidence==null||network==null||field==null||!evidence.fields().containsKey(field)
            ||request.engine()!=TrackerMode.CORRIDOR_AWARE||network.role()!=SnapshotRole.CAPTURED_BEFORE
            ||!request.evidenceSnapshotId().equals(evidence.snapshotId())||!request.evidenceContentHash().equals(evidence.canonicalHash())
            ||!request.networkSnapshotId().equals(network.snapshotId())||!request.networkContentHash().equals(network.canonicalHash())
            ||!request.evidenceResolution().equals(evidence.resolution()))
            throw new IllegalArgumentException("Source protection native snapshot binding differs");
        var input=request.corridorInput().orElseThrow(()->new IllegalArgumentException("Missing frozen A input"));
        if(!input.boundedBridgePolicy())throw new IllegalArgumentException("Source certificate requires explicit frozen A policy");
        if(!(network.primitives().get(request.selectedWayKey()) instanceof DetachedWay way)
            ||request.selectedRange().lastIndex()>=way.nodeKeys().size())
            throw new IllegalArgumentException("Selected source occurrences unavailable");
        ModernTracePipeline.validateSelectedOccurrenceIdentity(way,request);
        var disconnections=SharedNodeDisconnectionPlanner.forRequest(request,network);
        var metric=new ArrayList<MetricPoint>();var geographic=new ArrayList<GeographicPoint>();
        for(int i=request.selectedRange().firstIndex();i<=request.selectedRange().lastIndex();i++){
            cancellation.checkpoint();
            if(!(network.primitives().get(way.nodeKeys().get(i)) instanceof DetachedNode node))
                throw new IllegalArgumentException("Selected native source node missing");
            geographic.add(node.coordinate());metric.add(evidence.coordinateFrame().toMetric(node.coordinate()));
        }
        double[] fractions=ModernTracePipeline.normalizedChainage(metric,"captured selected geometry");
        var expected=IntervalTraceRequestFactory.corridorForAnchors(request.profileChainage(),metric,geographic,evidence);
        if(!input.profileLocations().equals(expected.profileLocations()))
            throw new IllegalArgumentException("Original profile/source/frame/chainage binding differs");
        for(var anchor:input.profileLocations()){
            cancellation.checkpoint();
            if(!evidence.routePositionAuthorized(anchor.metricPoint()))
                throw new IllegalArgumentException("Original profile outside native decision authority");
        }
        evidence.resolution().effectivePitchMetersAt(request.profileChainage().sourceOriginGroundMeters()
            +request.profileChainage().cumulativeGroundMeters().get(input.profileLocations().size()-1));
        var facts=new ArrayList<Occurrence>();var protectedChainages=new ArrayList<Double>();
        double chainage=0;var closure=network.closure();
        StringBuilder proof=new StringBuilder("corridor-source-certificate-v1\n")
            .append(FrozenReplayCodec.canonicalRequestHash(request)).append('\n').append(network.canonicalHash())
            .append('\n').append(evidence.canonicalHash()).append('\n').append(field).append('\n');
        for(int local=0;local<metric.size();local++){
            cancellation.checkpoint();if(local>0)chainage+=metric.get(local-1).distanceTo(metric.get(local));
            int index=request.selectedRange().firstIndex()+local;var key=way.nodeKeys().get(index);
            var node=(DetachedNode)network.primitives().get(key);boolean replacement=disconnections.mayPosition(index);
            boolean additional=input.additionalFixedOccurrences().contains(index);
            boolean ambiguous=ModernTracePipeline.fractionMultiplicity(fractions,fractions[local])>1;
            var permission=permission(network,key,replacement,additional,ambiguous);
            var fact=new Occurrence(index,key,node.coordinate(),metric.get(local),chainage,
                request.profileChainage().sourceOriginGroundMeters()+chainage,node.tags(),network.incomingReferrerWatches().get(key),
                closure.protectedExistingNodeKeys().contains(key),closure.movableExistingNodeKeys().contains(key),
                closure.removableExistingNodeKeys().contains(key),replacement,additional,ambiguous,permission.fixed());
            facts.add(fact);if(permission.fixed())protectedChainages.add(chainage);
            // Canonical network/request identities bind all coordinate/tag/watch/permission facts;
            // ordered derived decisions and factual distances additionally bind the certificate.
            proof.append(index).append(':').append(key).append(':').append(Double.toHexString(chainage))
                .append(':').append(replacement).append(':').append(additional).append(':').append(ambiguous)
                .append(':').append(permission.fixed()).append('\n');
        }
        String identity=hash(proof.toString());
        if(!closure.wayReferrersComplete()||!closure.relationReferrersComplete())
            return new Certificate("REFUSED_SOURCE_PROTECTION_INCOMPLETE_WATCHES",identity,field,request,evidence,network,facts,null);
        double last=request.profileChainage().cumulativeGroundMeters().get(input.profileLocations().size()-1);
        // Source versus sampling extents must be identical; do not snap a protected boundary.
        if(Double.doubleToLongBits(chainage)!=Double.doubleToLongBits(last))
            return new Certificate("REFUSED_SOURCE_PROTECTION_CHAINAGE_OWNERSHIP",identity,field,request,evidence,network,facts,null);
        return new Certificate("COMPLETE",identity,field,request,evidence,network,facts,
            new CorridorWorkingSchedule.SourceProtection(0,last,protectedChainages));
    }

    private static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
