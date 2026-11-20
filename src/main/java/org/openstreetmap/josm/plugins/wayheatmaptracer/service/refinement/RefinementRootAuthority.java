package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import java.lang.reflect.RecordComponent;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.JunctionTopologyRules;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.SharedNodeDisconnectionPlanner;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.Projections;
import java.nio.charset.StandardCharsets;
import java.security.*;

/** Factual immutable root capture; retained detached data is owned by the bounded history. */
public final class RefinementRootAuthority {
    public enum FrozenGeometryProofKind { DERIVED_CLOSED_TOPOLOGY, CAPTURED_PARTITION }
    public record FrozenGeometryProof(FrozenGeometryProofKind kind,List<OccurrenceRange> ranges,
            Optional<SelectedWayIntervalPartitioner.Partition> capturedPartition,String identity) {
        public FrozenGeometryProof {ranges=List.copyOf(ranges);Objects.requireNonNull(kind);Objects.requireNonNull(capturedPartition);Objects.requireNonNull(identity);}
    }
    private final NetworkSnapshot network;
    private final TraceRequest request;
    private final EvidenceSnapshot evidence;
    private final AlignmentConfig effectiveConfig;
    private final String projectionCode,sourceIdentity;
    private final Optional<FrozenGeometryProof> frozenGeometryProof;
    private final Map<Object,Long> charges;
    private final long retainedBytes;
    public RefinementRootAuthority(NetworkSnapshot network,TraceRequest request,EvidenceSnapshot evidence,
            AlignmentConfig effectiveConfig,String projectionCode,String sourceIdentity) {
        this(network,request,evidence,effectiveConfig,projectionCode,sourceIdentity,null);
    }
    /** The partition must be the actual initial capture result, never a claimed empty range list. */
    public RefinementRootAuthority(NetworkSnapshot network,TraceRequest request,EvidenceSnapshot evidence,
            AlignmentConfig effectiveConfig,String projectionCode,String sourceIdentity,
            SelectedWayIntervalPartitioner.Partition capturedPartition) {
        Objects.requireNonNull(network); Objects.requireNonNull(request); Objects.requireNonNull(evidence);
        Objects.requireNonNull(effectiveConfig);
        var inventory=new Inventory();
        inventory.visit(network);inventory.visit(request);inventory.visit(evidence);inventory.visit(effectiveConfig);
        inventory.visit(projectionCode);inventory.visit(sourceIdentity);
        inventory.visit(capturedPartition);
        if (network.role() != SnapshotRole.CAPTURED_BEFORE || request.engine() != TrackerMode.LEGACY_V02
                || !request.networkSnapshotId().equals(network.snapshotId())
                || !request.networkContentHash().equals(network.canonicalHash())
                || !request.evidenceSnapshotId().equals(evidence.snapshotId())
                || !request.evidenceContentHash().equals(evidence.canonicalHash())
                || !request.evidenceResolution().equals(evidence.resolution())
                || request.geometryMode() != effectiveConfig.heatmap().alignmentMode()
                || effectiveConfig.heatmap().trackerMode()!=TrackerMode.LEGACY_V02 || request.selectedRange().size()<2
                || projectionCode == null || projectionCode.isBlank() || sourceIdentity == null || sourceIdentity.isBlank()
                || !sourceIdentity.equals(evidence.sourceIdentity())
                || !(network.primitives().get(request.selectedWayKey()) instanceof DetachedWay way)
                || way.deleted() || request.selectedRange().lastIndex() >= way.nodeKeys().size())
            throw new IllegalArgumentException("Root authority differs from its factual Legacy capture");
        var counts=new java.util.HashMap<PrimitiveKey,Integer>();
        way.nodeKeys().forEach(key->counts.merge(key,1,Integer::sum));
        for (int i=request.selectedRange().firstIndex();i<=request.selectedRange().lastIndex();i++) {
            var key=way.nodeKeys().get(i);
            if (counts.get(key)!=1
                    || !(network.primitives().get(key) instanceof DetachedNode node) || node.deleted())
                throw new IllegalArgumentException("Original occurrence geometry is incomplete or repeated");
            var closure=network.closure();
            if(!closure.protectedExistingNodeKeys().contains(key)&&!closure.movableExistingNodeKeys().contains(key)
                    &&!closure.removableExistingNodeKeys().contains(key))throw new IllegalArgumentException("Original selected occurrence authority is incomplete");
            boolean relation=network.incomingReferrerWatches().getOrDefault(key,java.util.Set.of()).stream()
                .anyMatch(k->k.type()==PrimitiveKey.Type.RELATION&&(!(network.primitives().get(k) instanceof DetachedRelation ref)||!ref.deleted()));
            if((!node.tags().isEmpty()||relation)&&(closure.movableExistingNodeKeys().contains(key)||closure.removableExistingNodeKeys().contains(key)))
                throw new IllegalArgumentException("Original tagged/relation occurrence cannot gain movement or removal authority");
            if(closure.removableExistingNodeKeys().contains(key)&&network.incomingReferrerWatches().getOrDefault(key,java.util.Set.of()).stream()
                    .anyMatch(k->!k.equals(request.selectedWayKey())&&(!(network.primitives().get(k) instanceof DetachedWay other)||!other.deleted())))
                throw new IllegalArgumentException("Original removable occurrence has another live referrer");
        }
        this.network=network;this.request=request;this.evidence=evidence;this.effectiveConfig=effectiveConfig;
        this.projectionCode=projectionCode;this.sourceIdentity=sourceIdentity;
        frozenGeometryProof=geometryProof(network,request,effectiveConfig,projectionCode,capturedPartition);
        inventory.visit(frozenGeometryProof);
        inventory.put(this,256);
        charges=Collections.unmodifiableMap(inventory.owned);retainedBytes=inventory.total;
    }
    public NetworkSnapshot network(){return network;}
    public TraceRequest request(){return request;}
    public EvidenceSnapshot evidence(){return evidence;}
    public AlignmentConfig effectiveConfig(){return effectiveConfig;}
    public String projectionCode(){return projectionCode;}
    public String sourceIdentity(){return sourceIdentity;}
    public long retainedBytes(){return retainedBytes;}
    public Optional<FrozenGeometryProof> frozenGeometryProof(){return frozenGeometryProof;}
    public String frozenGeometryIdentity(){return frozenGeometryProof.map(FrozenGeometryProof::identity).orElse("frozen-geometry-unavailable");}
    private static Optional<FrozenGeometryProof> geometryProof(NetworkSnapshot network,TraceRequest request,AlignmentConfig config,
            String projectionCode,SelectedWayIntervalPartitioner.Partition captured) {
        var way=(DetachedWay)network.primitives().get(request.selectedWayKey());
        if(captured!=null) {
            if(!captured.selectedWayKey().equals(way.key())||!captured.selectedRange().equals(request.selectedRange())
                    ||!SelectedWayIntervalPartitioner.verifyFrozenParity(captured,network))
                throw new IllegalArgumentException("Captured frozen-geometry partition differs from original selection/source");
            for(var island:captured.fixedIslands())if(!island.occurrenceKeys().equals(way.nodeKeys().subList(island.range().firstIndex(),island.range().lastIndex()+1)))
                throw new IllegalArgumentException("Frozen island occurrence identities differ from original source");
            var ranges=captured.fixedIslands().stream().map(SelectedWayIntervalPartitioner.FixedIsland::range)
                .sorted(Comparator.comparingInt(OccurrenceRange::firstIndex)).toList();
            var copied=SharedNodeDisconnectionPlanner.forRequest(request,network).replacements().keySet();
            for(int i=request.selectedRange().firstIndex();i<=request.selectedRange().lastIndex();i++) {
                final int occurrence=i;var key=way.nodeKeys().get(i);
                boolean shared=network.incomingReferrerWatches().getOrDefault(key,Set.of()).stream()
                    .anyMatch(ref->ref.type()==PrimitiveKey.Type.WAY&&!ref.equals(way.key())
                        &&(!(network.primitives().get(ref) instanceof DetachedWay other)||!other.deleted()));
                if(shared&&!copied.contains(i)&&ranges.stream().noneMatch(r->r.firstIndex()<=occurrence&&r.lastIndex()>=occurrence)
                        &&captured.junctionDispositions().stream().noneMatch(d->d.selectedOccurrenceIndex()==occurrence&&d.nodeKey().equals(key)))
                    throw new IllegalArgumentException("Captured partition omitted a shared occurrence without this request's copy authority");
            }
            return Optional.of(proof(FrozenGeometryProofKind.CAPTURED_PARTITION,ranges,Optional.of(captured),network,request,config));
        }
        if(!provesNoIslands(network,request,projectionCode))return Optional.empty();
        return Optional.of(proof(FrozenGeometryProofKind.DERIVED_CLOSED_TOPOLOGY,List.of(),Optional.empty(),network,request,config));
    }
    private static FrozenGeometryProof proof(FrozenGeometryProofKind kind,List<OccurrenceRange> ranges,
            Optional<SelectedWayIntervalPartitioner.Partition> captured,NetworkSnapshot network,TraceRequest request,AlignmentConfig config) {
        var text="stepwise-frozen-geometry-v1\n"+kind+"\n"+network.canonicalHash()+"\n"+request.selectedWayKey()+"\n"+request.selectedRange()+"\n"
            +request.permissions().canonicalIdentity()+"\n"+config.heatmap().toRedactedJson()+"\n"+ranges;
        try{return new FrozenGeometryProof(kind,ranges,captured,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    /** Only closed ordinary topology is derived. Branching, tags, relations or missing facts require capture. */
    private static boolean provesNoIslands(NetworkSnapshot network,TraceRequest request,String projectionCode) {
        var closure=network.closure();var selected=(DetachedWay)network.primitives().get(request.selectedWayKey());
        if(!closure.wayReferrersComplete()||!closure.relationReferrersComplete()||!closure.nearbyGeometryComplete()
                ||!noLiveRelations(network,selected.key())||!completeWay(network,selected,projectionCode))return false;
        for(int i=request.selectedRange().firstIndex();i<=request.selectedRange().lastIndex();i++) {
            var key=selected.nodeKeys().get(i);var node=(DetachedNode)network.primitives().get(key);
            if(!node.tags().isEmpty()||!network.incomingReferrerWatches().containsKey(key))return false;
            var ways=new ArrayList<DetachedWay>();
            for(var ref:network.incomingReferrerWatches().get(key)) {
                var value=network.primitives().get(ref);if(value==null)return false;if(value.deleted())continue;
                if(!(value instanceof DetachedWay way))return false;ways.add(way);
            }
            if(ways.size()==1&&ways.get(0).key().equals(selected.key()))continue;
            if(ways.size()!=2)return false;
            var facts=new ArrayList<JunctionTopologyRules.Referrer>();
            for(var way:ways)facts.add(new JunctionTopologyRules.Referrer(way.key().equals(selected.key()),true,
                completeWay(network,way,projectionCode),Collections.frequency(way.nodeKeys(),key)==1
                    &&(way.nodeKeys().get(0).equals(key)||way.nodeKeys().get(way.nodeKeys().size()-1).equals(key))));
            if(!JunctionTopologyRules.safeEndpoint(facts))return false;
            for(var other:ways)if(!other.key().equals(selected.key())) {
                if(!plainTransport(selected)||!plainTransport(other)||!noLiveRelations(network,other.key()))return false;
                for(var otherKey:other.nodeKeys()) {
                    var otherNode=(DetachedNode)network.primitives().get(otherKey);
                    if(!otherNode.tags().isEmpty()||!network.incomingReferrerWatches().containsKey(otherKey))return false;
                    for(var ref:network.incomingReferrerWatches().get(otherKey)) {
                        var value=network.primitives().get(ref);if(value==null)return false;if(value.deleted())continue;
                        if(!ref.equals(other.key())&&!(otherKey.equals(key)&&ref.equals(selected.key())))return false;
                    }
                }
            }
        }
        return true;
    }
    private static boolean noLiveRelations(NetworkSnapshot network,PrimitiveKey key) {
        var refs=network.incomingReferrerWatches().get(key);if(refs==null)return false;
        for(var ref:refs) {var value=network.primitives().get(ref);if(value==null||!value.deleted())return false;}
        return true;
    }
    private static boolean plainTransport(DetachedWay way) {
        return way.tags().containsKey("highway")&&!"yes".equals(way.tags().get("area"))
            &&Collections.disjoint(way.tags().keySet(),Set.of("building","landuse","boundary","railway","waterway"));
    }
    private static boolean completeWay(NetworkSnapshot network,DetachedWay way,String projectionCode) {
        if(way.deleted()||new HashSet<>(way.nodeKeys()).size()!=way.nodeKeys().size())return false;
        var projection=Projections.getProjectionByCode(projectionCode);if(projection==null)return false;
        org.openstreetmap.josm.data.coor.EastNorth previous=null;boolean positive=false;
        for(var key:way.nodeKeys()) {
            if(!(network.primitives().get(key) instanceof DetachedNode node)||node.deleted())return false;
            var p=projection.latlon2eastNorth(new LatLon(node.coordinate().latitudeDegrees(),node.coordinate().longitudeDegrees()));
            if(p==null||!Double.isFinite(p.east())||!Double.isFinite(p.north()))return false;
            if(previous!=null&&previous.distance(p)>0)positive=true;previous=p;
        }
        return positive;
    }
    Map<Object,Long> charges(){return charges;}

    /** Detached records only. No pixel getters, source buffers, mutable OSM graphs or cache ownership assumptions. */
    private static final class Inventory {
        private final IdentityHashMap<Object,Long> owned=new IdentityHashMap<>();
        private final Map<Class<?>,RecordComponent[]> records=new HashMap<>();
        private long total;
        void put(Object value,long bytes) {
            if(owned.putIfAbsent(value,bytes)!=null)return;
            total=RefinementPayload.add(total,bytes);
            if(total>RefinementLimits.defaults().maxPayloadBytes())
                throw new RefinementSession.Refused(RefinementModels.RefusalReason.PAYLOAD_LIMIT);
        }
        void visit(Object value) {
            if(value==null||value instanceof Enum<?>||owned.containsKey(value))return;
            if(value instanceof String text){put(value,96L+2L*text.length());return;}
            if(value instanceof Number||value instanceof Boolean||value instanceof Character){put(value,64);return;}
            if(value instanceof Optional<?> optional){put(value,64);optional.ifPresent(this::visit);return;}
            if(value instanceof OptionalDouble||value instanceof OptionalInt||value instanceof OptionalLong){put(value,64);return;}
            if(value instanceof ScalarEvidenceField field) {
                // The constructor privately clones all three arrays; aliases to one field share them exactly.
                long pixels=(long)field.width()*field.height();
                put(value,RefinementPayload.add(512,9L*pixels+(long)(field.width()-1)*(field.height()-1)));
                visit(field.lineage());return;
            }
            if(value instanceof MetricRegion region){put(value,256);visit(region.polygons());return;}
            if(value instanceof DistortionCertificate certificate){put(value,512);visit(certificate.method());visit(certificate.origin());visit(certificate.southWest());visit(certificate.northEast());return;}
            if(value instanceof Map<?,?> map){put(value,128L+80L*map.size());map.forEach((key,item)->{visit(key);visit(item);});return;}
            if(value instanceof Collection<?> collection){put(value,128L+24L*collection.size());collection.forEach(this::visit);return;}
            if(value.getClass().isRecord()) {
                var components=records.computeIfAbsent(value.getClass(),Class::getRecordComponents);
                put(value,192L+16L*components.length);
                for(var component:components)if(!component.getType().isPrimitive()) {
                    try {visit(component.getAccessor().invoke(value));}
                    catch(ReflectiveOperationException failure){throw new IllegalArgumentException("Immutable root retention component is inaccessible",failure);}
                }
                return;
            }
            throw new IllegalArgumentException("Root retention requires a known detached immutable value");
        }
    }
}
