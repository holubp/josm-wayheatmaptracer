package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import java.lang.reflect.RecordComponent;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;

/** Factual immutable root capture; retained detached data is owned by the bounded history. */
public final class RefinementRootAuthority {
    private final NetworkSnapshot network;
    private final TraceRequest request;
    private final EvidenceSnapshot evidence;
    private final AlignmentConfig effectiveConfig;
    private final String projectionCode,sourceIdentity;
    private final Map<Object,Long> charges;
    private final long retainedBytes;
    public RefinementRootAuthority(NetworkSnapshot network,TraceRequest request,EvidenceSnapshot evidence,
            AlignmentConfig effectiveConfig,String projectionCode,String sourceIdentity) {
        Objects.requireNonNull(network); Objects.requireNonNull(request); Objects.requireNonNull(evidence);
        Objects.requireNonNull(effectiveConfig);
        var inventory=new Inventory();
        inventory.visit(network);inventory.visit(request);inventory.visit(evidence);inventory.visit(effectiveConfig);
        inventory.visit(projectionCode);inventory.visit(sourceIdentity);
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
