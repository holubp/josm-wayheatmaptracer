package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import java.lang.reflect.RecordComponent;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.SelectedWayIntervalPartitioner;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.JunctionTopologyRules;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.LiveBPreviewService;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.snapshot.NetworkSnapshotCapture;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext;
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
    private final Map<EvidenceSnapshot,NativeAcquisition> nativeAcquisitions;
    private record NativeAcquisition(RetainedSmoothingSource source,TraceRequest request,
            NetworkSnapshotCapture.CapturedSnapshot receipt,AttemptMemoryLedger.Owner owner) {
        void requireCurrent(){
            if(owner.isClosed()||source.evidence().snapshotId().equals(request.evidenceSnapshotId())==false
                    ||receipt.snapshot().snapshotId().equals(request.networkSnapshotId())==false)
                throw new IllegalArgumentException("Original native acquisition is no longer retained");
            source.requireCurrent();
        }
    }
    public RefinementRootAuthority(NetworkSnapshot network,TraceRequest request,EvidenceSnapshot evidence,
            AlignmentConfig effectiveConfig,String projectionCode,String sourceIdentity) {
        this(network,request,evidence,effectiveConfig,projectionCode,sourceIdentity,null);
    }
    /** The partition must be the actual initial capture result, never a claimed empty range list. */
    public RefinementRootAuthority(NetworkSnapshot network,TraceRequest request,EvidenceSnapshot evidence,
            AlignmentConfig effectiveConfig,String projectionCode,String sourceIdentity,
            SelectedWayIntervalPartitioner.Partition capturedPartition) {
        this(network,request,evidence,effectiveConfig,projectionCode,sourceIdentity,capturedPartition,null,null);
    }
    /** Same factual authority validation, with a producer-admitted same-ledger evidence group.
     * The new owner adopts metadata only; immutable scalar/mask arrays retain their original lease.
     */
    public RefinementRootAuthority(NetworkSnapshot network,TraceRequest request,EvidenceSnapshot evidence,
            AlignmentConfig effectiveConfig,String projectionCode,String sourceIdentity,
            SelectedWayIntervalPartitioner.Partition capturedPartition,RetainedSmoothingSource source,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext work) {
        this(network,request,evidence,effectiveConfig,projectionCode,sourceIdentity,capturedPartition,source,work,work==null?null:work.owner());
    }
    public RefinementRootAuthority(NetworkSnapshot network,TraceRequest request,EvidenceSnapshot evidence,
            AlignmentConfig effectiveConfig,String projectionCode,String sourceIdentity,
            SelectedWayIntervalPartitioner.Partition capturedPartition,RetainedSmoothingSource source,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext work,
            org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.Owner owner) {
        this(network,request,evidence,effectiveConfig,projectionCode,sourceIdentity,capturedPartition,source,work,owner,null);
    }
    private RefinementRootAuthority(NetworkSnapshot network,TraceRequest request,EvidenceSnapshot evidence,
            AlignmentConfig effectiveConfig,String projectionCode,String sourceIdentity,SelectedWayIntervalPartitioner.Partition capturedPartition,
            RetainedSmoothingSource source,GeometryWorkContext work,AttemptMemoryLedger.Owner owner,String producedHash) {
        Objects.requireNonNull(network); Objects.requireNonNull(request); Objects.requireNonNull(evidence);
        Objects.requireNonNull(effectiveConfig);
        nativeAcquisitions=Map.of();
        org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger.Reservation metadata=null;
        if(source!=null||work!=null) {
            if(source==null||work==null||source.evidence()!=evidence)throw new IllegalArgumentException("Root evidence group is foreign");
            work.visit(org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.GeometryWorkContext.Kind.BIND,1);
            source.requireCurrent();source.retainInto(owner);
            long objects=Math.addExact(network.primitives().size(),Math.addExact(request.selectedRange().size(),
                Math.addExact(evidence.decisionRegion().polygons().size(),evidence.evidenceRegion().polygons().size())));
            long chars=(long)sourceIdentity.length()+projectionCode.length()+request.settingsHash().length()+request.parameterHash().length();
            metadata=work.reserveOn(owner,"original-root-metadata",Math.addExact(16384L,Math.addExact(Math.multiplyExact(8192L,objects),Math.multiplyExact(16L,chars))));
        }
        try {
        var inventory=new Inventory();
        if(source!=null) {
            var minimum=new Inventory();minimum.visit(evidence);
            if(source.backingBytes()<minimum.total)throw new IllegalArgumentException("Root evidence group is undercosted");
            inventory.put(evidence,source.backingBytes());
        }
        inventory.visit(network);inventory.visit(request);inventory.visit(evidence);inventory.visit(effectiveConfig);
        inventory.visit(projectionCode);inventory.visit(sourceIdentity);
        inventory.visit(capturedPartition);
        if (network.role() != SnapshotRole.CAPTURED_BEFORE || !Set.of(TrackerMode.LEGACY_V02,TrackerMode.CORRIDOR_AWARE,TrackerMode.PROBABILISTIC).contains(request.engine())
                || !request.networkSnapshotId().equals(network.snapshotId())
                || !request.networkContentHash().equals(network.canonicalHash())
                || !request.evidenceSnapshotId().equals(evidence.snapshotId())
                || !request.evidenceContentHash().equals(producedHash!=null?producedHash:work==null?evidence.canonicalHash():evidence.canonicalHash(work))
                || !request.evidenceResolution().equals(evidence.resolution())
                || request.geometryMode() != effectiveConfig.heatmap().alignmentMode()
                || effectiveConfig.heatmap().trackerMode()!=request.engine() || request.selectedRange().size()<2
                || projectionCode == null || projectionCode.isBlank() || sourceIdentity == null || sourceIdentity.isBlank()
                || !sourceIdentity.equals(evidence.sourceIdentity())
                || !(network.primitives().get(request.selectedWayKey()) instanceof DetachedWay way)
                || way.deleted() || request.selectedRange().lastIndex() >= way.nodeKeys().size())
            throw new IllegalArgumentException("Root authority differs from its factual capture");
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
        if(source!=null){source.requireCurrent();metadata.adopt(this);}
        } finally {if(metadata!=null)metadata.close();}
    }
    public NetworkSnapshot network(){return network;}
    public TraceRequest request(){return request;}
    public EvidenceSnapshot evidence(){return evidence;}
    /** Acquisition may add certified image support without replacing original network/edit authority. */
    public void requireAcquisitionEvidence(EvidenceSnapshot actual){
        var registered=nativeAcquisitions.get(actual);
        if(registered==null){requireAcquisitionEvidence(evidence,actual);return;}
        registered.requireCurrent();requireAcquisitionFrame(actual);
    }
    private void requireAcquisitionFrame(EvidenceSnapshot actual){
        if(!actual.coordinateFrame().equals(evidence.coordinateFrame())&&!actual.coordinateFrame().isCertifiedExtensionOf(evidence.coordinateFrame()))
            throw new IllegalArgumentException("Original native acquisition uses a different numerical frame");
    }
    /** Selected-source alias is checked before exact original registration or fresh acquisition authority. */
    public void requireSelectedAcquisitionEvidence(RefinementModels.Candidate candidate,RetainedSmoothingSource source){
        var selected=candidate.payload().nativeReview().orElseThrow(()->new IllegalArgumentException("Native source selection unavailable"));
        if(selected.retainedSources().get(source.source())!=source||!selected.sources().contains(source.source()))
            throw new IllegalArgumentException("Source is not the selected candidate's exact retained alias");
        if(candidate.payload().rootReviewState().isPresent()) {
            var registered=nativeAcquisitions.get(source.evidence());
            if(registered==null||registered.source()!=source)throw new IllegalArgumentException("Original native source was not registered by its producer");
        }
        requireAcquisitionEvidence(source.evidence());source.requireCurrent();
    }
    public static void requireAcquisitionEvidence(EvidenceSnapshot authority,EvidenceSnapshot actual) {
        if(authority==null||actual==null||!authority.sourceIdentity().equals(actual.sourceIdentity())
                ||!actual.coordinateFrame().equals(authority.coordinateFrame())&&!actual.coordinateFrame().isCertifiedExtensionOf(authority.coordinateFrame()))
            throw new IllegalArgumentException("Acquisition evidence differs from the original source or certified numerical frame");
    }
    public AlignmentConfig effectiveConfig(){return effectiveConfig;}
    public String projectionCode(){return projectionCode;}
    public String sourceIdentity(){return sourceIdentity;}
    public long retainedBytes(){return retainedBytes;}
    /** Includes already admitted native owner/input identities in the same retained-history limit. */
    RefinementRootAuthority withRetainedFacts(Map<Object,Long> additional) {
        return new RefinementRootAuthority(this,additional);
    }
    private RefinementRootAuthority(RefinementRootAuthority original,Map<Object,Long> additional) {
        this(original,additional,original.nativeAcquisitions);
    }
    private RefinementRootAuthority(RefinementRootAuthority original,Map<Object,Long> additional,Map<EvidenceSnapshot,NativeAcquisition> acquisitions) {
        network=original.network;request=original.request;evidence=original.evidence;effectiveConfig=original.effectiveConfig;
        projectionCode=original.projectionCode;sourceIdentity=original.sourceIdentity;frozenGeometryProof=original.frozenGeometryProof;
        nativeAcquisitions=Collections.unmodifiableMap(new IdentityHashMap<>(acquisitions));
        var inventory=new IdentityHashMap<Object,Long>(original.charges);
        additional.forEach((identity,bytes)->{if(identity==null||bytes==null||bytes<0)throw new IllegalArgumentException("Invalid admitted native ownership");inventory.merge(identity,bytes,Math::max);});
        inventory.put(this,256L);long total=0;for(long value:inventory.values())total=Math.addExact(total,value);
        if(total>RefinementLimits.defaults().maxPayloadBytes())throw new RefinementSession.Refused(RefinementModels.RefusalReason.PAYLOAD_LIMIT);
        charges=Collections.unmodifiableMap(inventory);retainedBytes=total;
    }
    /** Producer-only registration of the actual completed runs, never reconstructed diagnostic data. */
    static RefinementRootAuthority fromNativeProduction(LiveBPreviewService.Computed primary,AlignmentConfig config,String sourceIdentity,
            RetainedSmoothingSource source,GeometryWorkContext work,AttemptMemoryLedger.Owner owner,Map<Object,String> hashCache) {
        String hash=validatedNativeHash(primary,work,hashCache);var capture=primary.captured();
        return new RefinementRootAuthority(capture.network(),primary.request(),primary.evidence(),config,capture.projectionCode(),sourceIdentity,
            capture.intervalPartition(),source,work,owner,hash);
    }
    private static String validatedNativeHash(LiveBPreviewService.Computed run,GeometryWorkContext work,Map<Object,String> hashCache) {
        String actual;
        if(run.producedEvidenceProof().isPresent())actual=run.producedEvidenceProof().orElseThrow().requireMatches(run,work);
        else {
            actual=hashCache.get(run.evidence());
            if(actual==null)actual=run.evidence().canonicalHash(work);
        }
        if(!run.request().evidenceSnapshotId().equals(run.evidence().snapshotId())||!run.request().evidenceContentHash().equals(actual))
            throw new IllegalArgumentException("Original native request differs from its completed evidence traversal");
        if(!hashCache.containsKey(run.evidence())&&hashCache.size()>=64)throw new IllegalArgumentException("Bounded original evidence hash cache exhausted");
        hashCache.put(run.evidence(),actual);return actual;
    }
    RefinementRootAuthority withNativeAcquisitions(LiveBPreviewService.Captured capture,
            Collection<LiveBPreviewService.Computed> actualOwners,Map<LiveBPreviewService.Computed,RetainedSmoothingSource> retained,
            GeometryWorkContext work,AttemptMemoryLedger.Owner owner,Map<Object,String> hashCache) {
        var receipt=capture.originalReceipt().orElseThrow(()->new IllegalArgumentException("Original acquisition receipt unavailable"));
        if(receipt.snapshot()!=network||capture.network()!=network||!receipt.specification().equals(capture.specification())
                ||capture.geometryMode()!=request.geometryMode()||capture.engine()!=request.engine()
                ||!capture.settingsHash().equals(request.settingsHash())||!capture.specification().permissions().equals(request.permissions()))
            throw new IllegalArgumentException("Original acquisition receipt differs from root authority");
        var bindings=new IdentityHashMap<EvidenceSnapshot,NativeAcquisition>();
        for(var entry:retained.entrySet()) {
            work.visit(GeometryWorkContext.Kind.BIND,1);
            var run=entry.getKey();var source=entry.getValue();var nativeRequest=run.request();var actual=run.evidence();
            if(actualOwners.stream().noneMatch(value->value==run)||run.captured()!=capture||source.evidence()!=actual
                    ||!nativeRequest.networkSnapshotId().equals(network.snapshotId())||!nativeRequest.networkContentHash().equals(network.canonicalHash())
                    ||!nativeRequest.evidenceSnapshotId().equals(actual.snapshotId())
                    ||!nativeRequest.permissions().equals(request.permissions())||!nativeRequest.settingsHash().equals(request.settingsHash())
                    ||nativeRequest.engine()!=request.engine()||nativeRequest.geometryMode()!=request.geometryMode()
                    ||!source.source().mapping().equals(ModernReviewRoot.mapping(run))||!source.source().palette().equals(capture.palette())
                    ||!source.hasFactualInputHash())throw new IllegalArgumentException("Native acquisition does not bind the actual original run");
            if(capture.managedRaster()!=null?source.generation().isEmpty()||source.generation().getAsLong()!=capture.managedRaster().generation().value()
                    :source.visibleReceipt().isEmpty()||!source.visibleReceipt().get().equals(capture.raster().sourceReceipt()))
                throw new IllegalArgumentException("Native acquisition generation differs from its original receipt");
            validatedNativeHash(run,work,hashCache);
            requireAcquisitionFrame(actual);source.requireCurrent();source.retainInto(owner);
            if(bindings.put(actual,new NativeAcquisition(source,nativeRequest,receipt,owner))!=null)throw new IllegalArgumentException("Duplicate native acquisition evidence");
        }
        if(bindings.isEmpty()||bindings.size()>32)throw new IllegalArgumentException("Bounded original native acquisitions required");
        long bytes=4096L+512L*bindings.size();
        work.reserveOn(owner,"original-native-acquisition-registry",bytes).adopt(bindings);
        return new RefinementRootAuthority(this,Map.of(bindings,bytes),bindings);
    }
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
    static Map<Object,Long> detachedCharges(Object... values) {
        var inventory=new Inventory();for(Object value:values)inventory.visit(value);
        return inventory.owned;
    }
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
            if(value instanceof UUID){put(value,64);return;}
            if(value instanceof String text){put(value,96L+2L*text.length());return;}
            if(value instanceof Number||value instanceof Boolean||value instanceof Character){put(value,64);return;}
            if(value instanceof Optional<?> optional){put(value,64);optional.ifPresent(this::visit);return;}
            if(value instanceof OptionalDouble||value instanceof OptionalInt||value instanceof OptionalLong){put(value,64);return;}
            if(value instanceof org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CorridorExecutionReceipt receipt) {
                put(value,ModernReviewRoot.nativeReceiptBytes(receipt));return;
            }
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
