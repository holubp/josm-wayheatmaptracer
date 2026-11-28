package org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.PostprocessingValidationWork;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.refinement.RefinementModels.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.ProbabilisticProfileFactory;

/** Selected final parent geometry and original-occurrence lineage, never a replacement OSM capture. */
public final class ParentTraceReference {
    public static final String SAMPLER_SUFFIX="/stepwise-reference-v1";
    public enum Unavailable { MAPPING_UNAVAILABLE, MAPPING_AMBIGUOUS, DEGENERATE_REFERENCE, FROZEN_INTERIOR, EVIDENCE_COVERAGE, SPATIAL_RESOLUTION_UNAVAILABLE,
        FROZEN_GEOMETRY_PROOF_UNAVAILABLE, FROZEN_GEOMETRY_SPAN }
    public static final class UnavailableException extends IllegalStateException {
        private final Unavailable reason;
        public UnavailableException(Unavailable reason) { super(reason.name()); this.reason=reason; }
        public Unavailable reason() { return reason; }
    }
    private final RefinementRootAuthority root;
    private final StageId stage;
    private final CandidateId choice;
    private final String nativeId,parentIdentity,identity;
    private final RefinementSourcePlanner.SourceRequest selectedSource;
    private final List<MetricPoint> points;
    private final List<ParentArc> arcs;
    private final Map<Integer,ParentArc> byOccurrence;
    private final Map<Integer,MetricPoint> frozen;
    private final List<Integer> missing;
    private final Set<Integer> ambiguous;
    private final boolean degenerate;
    private final double[] chainage;
    private final OccurrenceRange range;
    private final String operationIdentity;
    private final Optional<ProfileChainage> measuredSampling;
    private record SliceProof(List<MetricPoint> enclosingPoints,List<MetricPoint> cutPoints) { }
    private final SliceProof sliceProof;

    /** Reads the producer's actual fields; the private chainage buffer is charged without cloning. */
    void inventoryInto(RefinementRootAuthority.Inventory inventory) {
        inventory.put(this,Math.addExact(256L,AttemptMemoryLedger.arrayBytes(chainage.length,8)));
        inventory.visit(root);inventory.visit(stage);inventory.visit(choice);inventory.visit(nativeId);inventory.visit(selectedSource);
        inventory.visit(parentIdentity);inventory.visit(identity);inventory.visit(points);inventory.visit(arcs);inventory.visit(byOccurrence);
        inventory.visit(frozen);inventory.visit(missing);inventory.visit(ambiguous);inventory.visit(range);inventory.visit(operationIdentity);inventory.visit(measuredSampling);
        inventory.visit(sliceProof);
    }
    // Package-private: the validating binder is the only public construction entry.
    ParentTraceReference(RefinementRootAuthority root,StageId stage,CandidateId choice,String nativeId,
            String palette,String parentIdentity,List<MetricPoint> points,List<ParentArc> arcs) {
        this(root,stage,choice,nativeId,new RefinementSourcePlanner.SourceRequest(palette,palette,false,""),parentIdentity,points,arcs);
    }
    ParentTraceReference(RefinementRootAuthority root,StageId stage,CandidateId choice,String nativeId,
            RefinementSourcePlanner.SourceRequest selectedSource,String parentIdentity,List<MetricPoint> points,List<ParentArc> arcs) {
        this(root,stage,choice,nativeId,selectedSource,parentIdentity,points,arcs,root.request().selectedRange(),"stage-reference",Optional.empty(),null);
    }
    private ParentTraceReference(RefinementRootAuthority root,StageId stage,CandidateId choice,String nativeId,
            RefinementSourcePlanner.SourceRequest selectedSource,String parentIdentity,List<MetricPoint> points,List<ParentArc> arcs,
            OccurrenceRange range,String operationIdentity,Optional<ProfileChainage> measuredSampling,SliceProof sliceProof) {
        this.measuredSampling=Objects.requireNonNull(measuredSampling);
        this.sliceProof=sliceProof;
        this.range=Objects.requireNonNull(range);this.operationIdentity=Objects.requireNonNull(operationIdentity);
        if(operationIdentity.isBlank()||range.firstIndex()<root.request().selectedRange().firstIndex()
                ||range.lastIndex()>root.request().selectedRange().lastIndex())throw new IllegalArgumentException("Reference cannot expand original selection");
        // Projection round trips may differ below the existing 0.1-micrometre binding
        // tolerance. Original immovable vertices have one canonical captured coordinate;
        // do this before chainage, cuts and identity, never as a tolerant composition join.
        points=canonicalImmovableVertices(root,points,arcs);
        this.root=root;this.stage=stage;this.choice=choice;this.nativeId=nativeId;
        this.selectedSource=Objects.requireNonNull(selectedSource);
        this.parentIdentity=parentIdentity;this.points=List.copyOf(points);this.arcs=List.copyOf(arcs);
        chainage=new double[points.size()];boolean zeroEdge=false;
        for(int i=1;i<points.size();i++) {
            double length=points.get(i-1).distanceTo(points.get(i));
            if(!(length>1e-9))zeroEdge=true;
            chainage[i]=chainage[i-1]+length;
            if(!root.network().closure().editRegion().containsSegment(points.get(i-1),points.get(i)))
                throw new IllegalArgumentException("Parent exceeds original edit envelope");
        }
        var mapped=new TreeMap<Integer,ParentArc>();var fixed=new TreeMap<Integer,MetricPoint>();var absent=new ArrayList<Integer>();
        for(var arc:arcs) {
            if(arc.originalOccurrence()<originalRange().firstIndex()||arc.originalOccurrence()>originalRange().lastIndex()
                    ||arc.parentEdge()>=points.size()-1 ||arc.fraction()==1 &&arc.parentEdge()!=points.size()-2)
                throw new IllegalArgumentException("Parent occurrence/edge correspondence exceeds original range or final geometry");
            mapped.put(arc.originalOccurrence(),arc);
        }
        degenerate=zeroEdge;
        var uncertain=new TreeSet<Integer>();ParentArc previous=null;
        for(var arc:arcs){if(previous!=null&&chainageAt(arc)<=chainageAt(previous)){uncertain.add(previous.originalOccurrence());uncertain.add(arc.originalOccurrence());}previous=arc;}
        ambiguous=Set.copyOf(uncertain);
        var disconnections=SharedNodeDisconnectionPlanner.forRequest(root.request(),root.network());
        var way=(DetachedWay)root.network().primitives().get(root.request().selectedWayKey());
        for(int i=originalRange().firstIndex();i<=originalRange().lastIndex();i++) {
            var arc=mapped.get(i);if(arc==null){absent.add(i);continue;}
            var key=way.nodeKeys().get(i);var node=(DetachedNode)root.network().primitives().get(key);
            var captured=root.evidence().coordinateFrame().toMetric(node.coordinate());
            var target=interpolate(points.get(arc.parentEdge()),points.get(arc.parentEdge()+1),arc.fraction());
            boolean relation=root.network().incomingReferrerWatches().getOrDefault(key,Set.of()).stream()
                .anyMatch(k->k.type()==PrimitiveKey.Type.RELATION&&(!(root.network().primitives().get(k) instanceof DetachedRelation other)||!other.deleted()));
            boolean immovable=(root.network().closure().protectedExistingNodeKeys().contains(key)||!node.tags().isEmpty()||relation)
                &&!disconnections.mayPosition(i);
            if(immovable&&target.distanceTo(captured)>1e-7)
                throw new IllegalArgumentException("Parent moved an originally immovable anchor");
            boolean shared=!disconnections.replacements().containsKey(i)&&root.network().incomingReferrerWatches().getOrDefault(key,Set.of()).stream()
                .anyMatch(k->k.type()==PrimitiveKey.Type.WAY&&!k.equals(way.key())
                    &&(!(root.network().primitives().get(k) instanceof DetachedWay other)||!other.deleted()));
            if(immovable||shared||i==originalRange().firstIndex()||i==originalRange().lastIndex())fixed.put(i,immovable?captured:target);
        }
        byOccurrence=Map.copyOf(mapped);frozen=Map.copyOf(fixed);missing=List.copyOf(absent);
        if(mapped.containsKey(originalRange().firstIndex())&&chainageAt(mapped.get(originalRange().firstIndex()))!=0
                ||mapped.containsKey(originalRange().lastIndex())&&chainageAt(mapped.get(originalRange().lastIndex()))!=chainage[chainage.length-1])
            throw new IllegalArgumentException("Parent correspondence does not retain selected endpoints");
        var canonical=new StringBuilder("stepwise-parent-reference-v2");
        for(String value:List.of(root.network().canonicalHash(),
                root.frozenGeometryIdentity(),
                org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayCodec.canonicalRequestHash(root.request()),
                stage.value().toString(),choice.value(),nativeId,selectedSource.palette(),selectedSource.mapping(),
                Boolean.toString(selectedSource.aggregate()),parentIdentity,range.firstIndex()+":"+range.lastIndex(),operationIdentity))
            field(canonical,value);
        field(canonical,Integer.toString(points.size()));field(canonical,Integer.toString(arcs.size()));
        for(var point:points){field(canonical,Double.toHexString(point.xMeters()));field(canonical,Double.toHexString(point.yMeters()));}
        for(var arc:arcs){field(canonical,Integer.toString(arc.originalOccurrence()));field(canonical,Integer.toString(arc.parentEdge()));field(canonical,Double.toHexString(arc.fraction()));}
                measuredSampling.ifPresent(sampling->{
            var originalWay=(DetachedWay)root.network().primitives().get(root.request().selectedWayKey());
            var originalPoints=new ArrayList<MetricPoint>();
            for(int occurrence=root.request().selectedRange().firstIndex();occurrence<=root.request().selectedRange().lastIndex();occurrence++)
                originalPoints.add(root.evidence().coordinateFrame().toMetric(((DetachedNode)root.network().primitives().get(originalWay.nodeKeys().get(occurrence))).coordinate()));
            var capturedPolicy=ProfileChainage.measured(originalPoints,root.request().configuredSampleStepMeters())
                .withSourceOrigin(root.request().profileChainage().sourceOriginGroundMeters());
            if(!capturedPolicy.equals(root.request().profileChainage())||!range.equals(root.request().selectedRange())
                    ||this.points.size()!=originalPoints.size()||arcs.size()!=originalPoints.size())
                throw new IllegalArgumentException("Explicit measured A rows require their factual captured vertex policy and complete lineage");
            for(int i=0;i<arcs.size();i++) {
                var arc=arcs.get(i);int occurrence=range.firstIndex()+i;
                if(arc.originalOccurrence()!=occurrence||arc.parentEdge()!=Math.min(i,this.points.size()-2)
                        ||arc.fraction()!=(i==this.points.size()-1?1:0))
                    throw new IllegalArgumentException("Explicit measured A rows cannot coarsen captured occurrence ownership");
            }
            if(!sampling.equals(ProfileChainage.measured(this.points,root.request().configuredSampleStepMeters())))
                throw new IllegalArgumentException("Explicit A rows must be factual measured parent vertices under the frozen physical step");
            // Numerical projection round trips may differ below0.1 micrometre; physical spacing may not change.
            for(int i=0;i<sampling.cumulativeGroundMeters().size();i++)
                if(Math.abs(sampling.cumulativeGroundMeters().get(i)-capturedPolicy.cumulativeGroundMeters().get(i))>1e-7)
                    throw new IllegalArgumentException("Explicit measured A rows changed captured physical spacing");
            field(canonical,"captured-measured-parent-vertices-v1");
            for(double distance:sampling.cumulativeGroundMeters())field(canonical,Double.toHexString(distance));
        });
        identity=hash(canonical.toString());
    }
    public RefinementRootAuthority root(){return root;}
    private static List<MetricPoint> canonicalImmovableVertices(RefinementRootAuthority root,List<MetricPoint> source,List<ParentArc> arcs) {
        var points=new ArrayList<>(source);var way=(DetachedWay)root.network().primitives().get(root.request().selectedWayKey());
        var disconnections=SharedNodeDisconnectionPlanner.forRequest(root.request(),root.network());
        for(var arc:arcs) {
            if(arc.originalOccurrence()>=way.nodeKeys().size()||arc.parentEdge()>=points.size()-1)
                throw new IllegalArgumentException("Original anchor correspondence exceeds source geometry");
            var key=way.nodeKeys().get(arc.originalOccurrence());var node=(DetachedNode)root.network().primitives().get(key);
            boolean relation=root.network().incomingReferrerWatches().getOrDefault(key,Set.of()).stream()
                .anyMatch(k->k.type()==PrimitiveKey.Type.RELATION&&(!(root.network().primitives().get(k) instanceof DetachedRelation other)||!other.deleted()));
            boolean immovable=(root.network().closure().protectedExistingNodeKeys().contains(key)||!node.tags().isEmpty()||relation)
                &&!disconnections.mayPosition(arc.originalOccurrence());
            if(!immovable)continue;
            int vertex=arc.fraction()==0?arc.parentEdge():arc.fraction()==1?arc.parentEdge()+1:-1;
            if(vertex<0)throw new IllegalArgumentException("Originally immovable anchor lacks a retained parent vertex");
            var captured=root.evidence().coordinateFrame().toMetric(node.coordinate());
            if(points.get(vertex).distanceTo(captured)>1e-7)throw new IllegalArgumentException("Parent moved an originally immovable anchor");
            points.set(vertex,captured);
        }
        return List.copyOf(points);
    }
    public StageId parentStage(){return stage;}
    public CandidateId parentChoice(){return choice;}
    public String nativeCandidateId(){return nativeId;}
    public String parentPalette(){return selectedSource.palette();}
    public RefinementSourcePlanner.SourceRequest selectedSource(){return selectedSource;}
    public String parentIdentity(){return parentIdentity;}
    public String canonicalIdentity(){return identity;}
    public List<MetricPoint> points(){return points;}
    public List<ParentArc> correspondence(){return arcs;}
    public List<Integer> missingOccurrences(){return missing;}
    public Set<Integer> ambiguousOccurrences(){return ambiguous;}
    public Map<Integer,MetricPoint> frozenAnchors(){return frozen;}
    public OccurrenceRange originalRange(){return range;}
    public double chainageAt(ParentArc arc){return chainage[arc.parentEdge()]+arc.fraction()*(chainage[arc.parentEdge()+1]-chainage[arc.parentEdge()]);}
    public double occurrenceChainage(int occurrence){var arc=byOccurrence.get(occurrence);if(arc==null)throw new UnavailableException(Unavailable.MAPPING_UNAVAILABLE);return chainageAt(arc);}
    public MetricPoint occurrencePosition(int occurrence){var arc=byOccurrence.get(occurrence);if(arc==null)throw new UnavailableException(Unavailable.MAPPING_UNAVAILABLE);return interpolate(points.get(arc.parentEdge()),points.get(arc.parentEdge()+1),arc.fraction());}
    /** Pins generated identities to one explicit operation without changing its selected parent. */
    public ParentTraceReference withSampling(ProfileChainage measured) {
        return new ParentTraceReference(root,stage,choice,nativeId,selectedSource,parentIdentity,points,arcs,range,operationIdentity,Optional.of(measured),sliceProof);
    }
    public ParentTraceReference forOperation(String operation) {
        return new ParentTraceReference(root,stage,choice,nativeId,selectedSource,parentIdentity,points,arcs,range,operation,measuredSampling,sliceProof);
    }
    /** Exact edge subdivision for an original-occurrence interval. Missing lineage remains local. */
    public ParentTraceReference slice(OccurrenceRange selected) {
        if(selected.firstIndex()<range.firstIndex()||selected.lastIndex()>range.lastIndex())throw new IllegalArgumentException("Reference cut expands parent range");
        double first=occurrenceChainage(selected.firstIndex()),last=occurrenceChainage(selected.lastIndex());
        var cutPoints=new ArrayList<MetricPoint>();var positions=new ArrayList<Double>();
        cutPoints.add(occurrencePosition(selected.firstIndex()));positions.add(first);
        for(int i=1;i<points.size()-1;i++)if(chainage[i]>first&&chainage[i]<last){cutPoints.add(points.get(i));positions.add(chainage[i]);}
        cutPoints.add(occurrencePosition(selected.lastIndex()));positions.add(last);
        var cuts=new ArrayList<ParentArc>();
        for(var arc:arcs)if(arc.originalOccurrence()>=selected.firstIndex()&&arc.originalOccurrence()<=selected.lastIndex()) {
            double target=chainageAt(arc);int edge=0;
            while(edge<positions.size()-2&&positions.get(edge+1)<=target)edge++;
            double edgeStart=positions.get(edge),edgeEnd=positions.get(edge+1);
            // Whole retained edges keep their exact occurrence fraction. Subtracting two
            // cumulative distances can move a frozen cut even when no vertex changed.
            boolean wholeEdge=edgeStart==chainage[arc.parentEdge()]&&edgeEnd==chainage[arc.parentEdge()+1];
            double fraction=wholeEdge?arc.fraction():(target-edgeStart)/(edgeEnd-edgeStart);
            cuts.add(new ParentArc(arc.originalOccurrence(),edge,fraction));
        }
        return new ParentTraceReference(root,stage,choice,nativeId,selectedSource,parentIdentity,cutPoints,cuts,selected,operationIdentity,
                Optional.empty(),new SliceProof(points,List.copyOf(cutPoints)));
    }
    public void requireComplete(){
        if(!missing.isEmpty())throw new UnavailableException(Unavailable.MAPPING_UNAVAILABLE);
        if(!ambiguous.isEmpty())throw new UnavailableException(Unavailable.MAPPING_AMBIGUOUS);
        if(degenerate)throw new UnavailableException(Unavailable.DEGENERATE_REFERENCE);
    }
    /** Frozen spans retain the selected parent curve, not only its existing endpoint coordinates. */
    public void requireRefinementGeometryAuthority() {
        var proof=root.frozenGeometryProof().orElseThrow(()->new UnavailableException(Unavailable.FROZEN_GEOMETRY_PROOF_UNAVAILABLE));
        for(var frozen:proof.ranges())if(range.firstIndex()<frozen.lastIndex()&&range.lastIndex()>frozen.firstIndex())
            throw new UnavailableException(Unavailable.FROZEN_GEOMETRY_SPAN);
    }
    /**
     * Pre-allocation bound for derived identity/request validation. Evidence hashing clones its
     * double/validity/cell arrays; UTF-8 fields, sorted entries, canonical geometry and request
     * containers also materialize. No hashing or raster copy is performed by this inventory.
     */
    public long bindingScratchBytes(EvidenceSnapshot evidence) {
        try {
        double intervals=Math.ceil(chainage[chainage.length-1]/root.request().configuredSampleStepMeters());
        if(!Double.isFinite(intervals)||intervals>=Integer.MAX_VALUE-1)return Long.MAX_VALUE;
        long rows=measuredSampling.map(value->(long)value.cumulativeGroundMeters().size()).orElse(Math.max(1L,(long)intervals)+1L);
        // Resampled source/chainage arrays, geographic/metric/raster locations, detached graph
        // validation, request/Gson canonical trees and their string/byte staging coexist.
        long rowBytes=Math.multiplyExact(rows,16384L);
        long bytes=Math.addExact(65536L,Math.multiplyExact(root.retainedBytes(),2L));
        bytes=Math.addExact(bytes,Math.addExact(Math.multiplyExact(points.size(),2048L),Math.multiplyExact(arcs.size(),128L)));
        bytes=Math.addExact(bytes,Math.multiplyExact(4L,evidence.sourceIdentity().length()));
        var transform=evidence.transform();var resampling=evidence.resampling();
        for(String value:List.of(transform.transformId(),transform.accuracyCertificate().method(),resampling.method(),
                resampling.sourceTransformKind(),resampling.sourceTransformIdentity(),resampling.inputCoordinateConvention(),
                resampling.scalarOrder(),resampling.validityRule()))bytes=Math.addExact(bytes,4L*value.length());
        bytes=Math.addExact(bytes,128L*evidence.resolution().spatialPitchSamples().size());
        for(var region:List.of(evidence.decisionRegion(),evidence.evidenceRegion()))for(var polygon:region.polygons())
            bytes=Math.addExact(bytes,Math.addExact(256L,512L*polygon.size()));
        for(var entry:evidence.fields().entrySet()) {
            var field=entry.getValue();long cells=(long)field.width()*field.height();
            bytes=Math.addExact(bytes,Math.addExact(512L,Math.addExact(9L*cells,(long)(field.width()-1)*(field.height()-1))));
            bytes=Math.addExact(bytes,4L*(entry.getKey().length()+(long)field.lineage().sourcePalette().length()));
            for(String operation:field.lineage().scalarOperations())bytes=Math.addExact(bytes,128L+4L*operation.length());
        }
        return Math.addExact(bytes,rowBytes);
        }catch(ArithmeticException overflow){return Long.MAX_VALUE;}
    }
    /** One thread-confined validation workspace. Its ledger owner may settle on any thread. */
    public static final class BindingWorkspace implements AutoCloseable {
        private final RefinementRootAuthority root;
        private final EvidenceSnapshot evidence;
        private final AttemptMemoryLedger.Owner owner;
        private final Thread worker;
        private final AttemptMemoryLedger.Reservation reservation;
        private BindingWorkspace(ParentTraceReference reference,EvidenceSnapshot evidence,AttemptMemoryLedger.Owner owner,
                AttemptMemoryLedger.Reservation reservation) {
            this.root=reference.root;this.evidence=evidence;this.owner=owner;this.reservation=reservation;worker=Thread.currentThread();
        }
        /** Proves capacity/lifetime only; every entry still validates the actual request/source authority. */
        public void requireCapacity(ParentTraceReference reference,EvidenceSnapshot source,AttemptMemoryLedger.Owner actualOwner) {
            if(root!=reference.root||evidence!=source||owner!=actualOwner||worker!=Thread.currentThread()
                    ||!reservation.isActiveFor(owner)||reference.bindingScratchBytes(source)>reservation.bytes())
                throw new IllegalArgumentException("Validation workspace belongs to another owner/source/worker or is insufficient");
        }
        @Override public void close(){reservation.close();}
    }
    /** Reserves before deriving a request, hashing buffers or constructing profile locations. */
    public BindingWorkspace reserveBindingWorkspace(EvidenceSnapshot evidence,AttemptMemoryLedger.Owner owner) {
        return new BindingWorkspace(this,evidence,owner,owner.reserve(bindingScratchBytes(evidence)));
    }

    /** Request preparation acquires nothing and allocates no new authority or solver work. */
    public TraceRequest request(TrackerMode engine,EvidenceSnapshot evidence,TraceBudgets remaining,double lateralStep) {
        return request(engine,evidence,remaining,lateralStep,CancellationProbe.NONE);
    }
    TraceRequest request(TrackerMode engine,EvidenceSnapshot evidence,TraceBudgets remaining,double lateralStep,CancellationProbe cancellation) {
        requireRefinementGeometryAuthority();requireComplete();
        if(engine!=TrackerMode.CORRIDOR_AWARE&&engine!=TrackerMode.PROBABILISTIC)throw new IllegalArgumentException("Only A/B refine a parent");
        var original=root.request();var maximum=original.budgets();
        if(remaining.maximumStatesPerProfile()!=maximum.maximumStatesPerProfile()||remaining.maximumPairVisits()>maximum.maximumPairVisits()
                ||remaining.maximumTransitions()>maximum.maximumTransitions()||remaining.maximumRawAlternatives()>maximum.maximumRawAlternatives()
                ||remaining.maximumDistinctAlternatives()>maximum.maximumDistinctAlternatives())throw new IllegalArgumentException("Remaining work exceeds root policy");
        root.requireAcquisitionEvidence(evidence);
        if(evidence.resolution().spatialPitchSamples().size()!=1)throw new UnavailableException(Unavailable.SPATIAL_RESOLUTION_UNAVAILABLE);
        var corridor=MetricCorridorRegion.aroundPolyline(points,original.permissions().ordinaryRadiusMeters());
        if((!hasEnclosingSliceProof(evidence.decisionRegion(),cancellation)
                &&!decisionRegionContains(evidence.decisionRegion(),corridor,cancellation))
                ||!evidence.evidenceRegion().containsRegion(corridor))
            throw new UnavailableException(Unavailable.EVIDENCE_COVERAGE);
        if(engine==TrackerMode.PROBABILISTIC&&measuredSampling.isPresent())
            throw new IllegalArgumentException("B requires its unchanged deterministic profile resampling");
        var profiles=measuredSampling.orElseGet(()->new ProbabilisticProfileFactory().profileChainage(points,original.configuredSampleStepMeters()));
        Optional<CorridorTraceInput> input=engine==TrackerMode.CORRIDOR_AWARE?Optional.of(CorridorTraceInput.from(profiles,points,
            evidence.coordinateFrame(),evidence.transform(),lateralStep).withPolicy(CorridorTraceInput.ExecutionPolicy.R7_OWNED_MEASURED_CORE_COST_V1,frozen.keySet())):Optional.empty();
        return new TraceRequest(original.selectedWayKey(),originalRange(),engine,original.geometryMode(),original.permissions(),remaining,
            evidence.snapshotId(),evidence.canonicalHash(),original.networkSnapshotId(),original.networkContentHash(),original.settingsHash(),
            parameterHash(engine,evidence),original.samplerId()+SAMPLER_SUFFIX,original.configuredSampleStepMeters(),profiles,evidence.resolution(),input,original.disconnectionSource());
    }
    boolean hasEnclosingSliceProof(MetricRegion decision,CancellationProbe cancellation) {
        if(sliceProof!=null&&points.equals(sliceProof.cutPoints())) {
            cancellation.checkpoint();
            // Each cut edge is either an unchanged parent chord or a subdivision of one chord.
            // With the same radius and orientation, its capped rectangle lies inside the
            // corresponding parent rectangle. Use that construction proof only for the exact
            // captured parent corridor. Area subtraction can leave slivers at recomputed caps.
            var enclosing=MetricCorridorRegion.aroundPolyline(sliceProof.enclosingPoints(),root.request().permissions().ordinaryRadiusMeters());
            if(decision.polygons().equals(enclosing.polygons()))return true;
        }
        return false;
    }
    static boolean decisionRegionContains(MetricRegion decision,MetricRegion corridor,CancellationProbe cancellation) {
        // A local occurrence cut usually keeps these exact segment rectangles in their original order.
        // Matching them proves containment even if subtracting the differently ordered Area unions leaves a rounding sliver.
        var localPolygons=corridor.polygons();
        int matched=0;
        for(var polygon:decision.polygons()) {
            cancellation.checkpoint();
            PostprocessingValidationWork.visit();
            if(localPolygons.get(matched).equals(polygon)) {
                matched++;
                if(matched==localPolygons.size())return true;
            }
        }
        cancellation.checkpoint();
        return decision.containsRegion(corridor);
    }
    public void requireBinding(TraceRequest request,EvidenceSnapshot evidence,NetworkSnapshot network,CancellationProbe cancel) {
        cancel.checkpoint();
        if(network!=root.network())throw new IllegalArgumentException("Refinement must retain factual original network object");
        double step=request.corridorInput().map(CorridorTraceInput::lateralStepMeters).orElse(1.0);
        if(!request.equals(request(request.engine(),evidence,request.budgets(),step,cancel)))throw new IllegalArgumentException("Derived request differs from exact parent binding");
    }
    private String parameterHash(TrackerMode engine,EvidenceSnapshot evidence){return hash(root.request().parameterHash()+"\n"+identity+"\n"+engine+"\nR7/reference-v1\n"+evidence.canonicalHash());}
    public static MetricPoint interpolate(MetricPoint a,MetricPoint b,double f){return f==0?a:f==1?b:new MetricPoint(a.xMeters()+f*(b.xMeters()-a.xMeters()),a.yMeters()+f*(b.yMeters()-a.yMeters()));}
    private static void field(StringBuilder target,String value){target.append(value.length()).append(':').append(value);}
    private static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
