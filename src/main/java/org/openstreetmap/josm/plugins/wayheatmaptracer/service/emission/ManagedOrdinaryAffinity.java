package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.math.BigDecimal;
import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedGroundTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.AcceptedModeThresholdReceipt;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.OrdinaryAffinityClassifier.Status;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeMeasuredProfileReceipt.*;

/** Explicit managed, actual-port experiment. It issues no tracker state or future-family claim. */
public final class ManagedOrdinaryAffinity implements AutoCloseable {
    public static final String POLICY_ID="legacy-managed-local-connector-affinity-v1";
    /** Fixed geometry precision; no scalar samples or adaptive rescue search. */
    private static final int NATIVE_CELL_PARTS=4;
    public enum Reason { LOCAL_OWNED_CONTINUATION, LOCAL_FIXED_CONNECTOR_DARK_CROSSCUT, NATIVE_FALLBACK,
        UNSUPPORTED_CHART, POINT_OWNERSHIP, SOURCE_AUTHORITY, SCALAR_SUPPORT, UNRESOLVED_MODES,
        UNRESOLVED_LINKS, INTERIOR_PORT_UNRESOLVED, DOMAIN_UNRESOLVED, NO_CERTIFIED_CROSSCUT,
        UNPROVED_CONNECTOR, RELEVANT_UNKNOWN }
    public enum LinkStatus { ADMITTED, REJECTED, UNAVAILABLE, NOT_QUERIED }
    public enum LinkReason { NONE, CONTINUATION_BOUND, SOURCE_AUTHORITY, SCALAR_SUPPORT, BELOW_THRESHOLD,
        INCOMPLETE_PROFILE, MODE_COUNT_MISMATCH, DOMAIN_UNRESOLVED }
    public record Domain(MetricPoint origin,MetricPoint direction,MetricPoint normal,
        double innerFirst,double innerLast,double outerFirst,double outerLast,double halfWidth,
        double minimumNativePitch,double localLowerU,double localUpperU,int nativeCellSubdivisions) { }
    public enum UnknownScope { OUTSIDE_LOCAL_PROOF, INTERSECTS_LOCAL_PROOF, UNRESOLVED }
    public record UnknownInterval(int profileIndex,int sampleIndex,double fromParameter,double toParameter,
        Optional<ParameterBox> enclosure,UnknownScope scope) { }
    public record ModeFootprint(int profileIndex,int modeIndex,AcceptedModeThresholdReceipt owner,
        Optional<ParameterBox> enclosure,UnknownScope scope) { }
    public record StationPlan(int index,double parameter,MetricPoint numericAnchor) { }
    /** Immutable diagnostic copies: no live token getter is required after logical input closure. */
    public record ModeFact(AcceptedModeThresholdReceipt accepted,Optional<MetricPoint> numericPosition,
        Optional<RasterPoint> actualRaster,Optional<RasterPoint> actualNative,Support support,boolean ordered) { }
    public record StationFact(int index,double parameter,NativeConnectorEvaluator.Probe probe,
        List<QueryPoint> queries,List<ModeFact> modes,Optional<List<Integer>> localModeIndices) {
        public StationFact { queries=List.copyOf(queries);modes=List.copyOf(modes);localModeIndices=localModeIndices.map(List::copyOf); }
    }
    public record DirectLink(int fromStation,int toStation,int fromMode,int toMode,
        AcceptedModeThresholdReceipt fromOwner,AcceptedModeThresholdReceipt toOwner,double threshold,
        LinkStatus status,LinkReason reason,Optional<BilinearSegmentIntegral.Result> scalar) { }
    public record PointFact(int originalRow,int retainedRow,boolean moved,RasterPoint actualRaster,
        MetricPoint actualMetric,RasterPoint inverseZero,Availability availability,
        NativeMeasuredProfileReceipt.Reason reason,Optional<AcceptedModeThresholdReceipt> component,
        OptionalDouble strictScalar,Optional<BilinearSegmentIntegral.Result> bridge,
        Optional<BilinearSegmentIntegral.Result> componentBridge,NativeConnectorEvaluator.Probe probe,
        List<QueryPoint> queries) { public PointFact { queries=List.copyOf(queries); } }
    public record CutPiece(RasterPoint from,RasterPoint to,double threshold,double lowerSupport,double upperSupport,
        AcceptedModeThresholdReceipt lowerFrom,AcceptedModeThresholdReceipt lowerTo,
        AcceptedModeThresholdReceipt upperFrom,AcceptedModeThresholdReceipt upperTo,
        List<OrdinaryAffinityClassifier.CellEvidence> cells,List<ParameterBox> wholePathBounds) {
        public CutPiece { cells=List.copyOf(cells);wholePathBounds=List.copyOf(wholePathBounds); }
    }
    public record Proof(Optional<Domain> domain,List<StationPlan> plan,List<StationFact> stations,
        List<DirectLink> links,List<CutPiece> cut,Reason reason,int failingStation,
        List<UnknownInterval> unknownIntervals,List<ModeFootprint> usedFootprints,List<ParameterBox> connectorBounds,
        Availability globalModeFamilyAvailability) {
        public Proof { plan=List.copyOf(plan);stations=List.copyOf(stations);links=List.copyOf(links);cut=List.copyOf(cut);
            unknownIntervals=List.copyOf(unknownIntervals);usedFootprints=List.copyOf(usedFootprints);connectorBounds=List.copyOf(connectorBounds);
            if(globalModeFamilyAvailability!=Availability.UNAVAILABLE||stations.size()>plan.size())
                throw new IllegalArgumentException("Local connector proof cannot certify a global Mode family");
        }
        public Optional<StationFact> availableStation(int index){
            if(index<0||index>=plan.size())throw new IndexOutOfBoundsException("Outside frozen managed plan");
            return index<stations.size()?Optional.of(stations.get(index)):Optional.empty();
        }
    }
    public record Receipt(Status status,Reason reason,NativeConnectorEvaluator.RowState from,
        NativeConnectorEvaluator.RowState to,RasterPoint actualFrom,RasterPoint actualTo,
        Optional<PointFact> fromOwnership,Optional<PointFact> toOwnership,Proof proof,
        int fromChain,int toChain,Optional<BilinearSegmentIntegral.Result> scalar) {
        public Receipt {
            Objects.requireNonNull(status);Objects.requireNonNull(reason);Objects.requireNonNull(proof);
            Objects.requireNonNull(fromOwnership);Objects.requireNonNull(toOwnership);Objects.requireNonNull(scalar);
            if(status!=Status.UNKNOWN&&(fromChain<0||toChain<0||fromOwnership.isEmpty()||toOwnership.isEmpty()
                    ||fromOwnership.orElseThrow().availability()!=Availability.COMPLETE||toOwnership.orElseThrow().availability()!=Availability.COMPLETE
                    ||scalar.isEmpty()||scalar.orElseThrow().status()!=BilinearSegmentIntegral.Status.COMPLETE||proof.connectorBounds().isEmpty())
                ||status==Status.INCOMPATIBLE&&(reason!=Reason.LOCAL_FIXED_CONNECTOR_DARK_CROSSCUT||fromChain==toChain||proof.cut().isEmpty())
                ||status==Status.COMPATIBLE&&(reason!=Reason.LOCAL_OWNED_CONTINUATION||fromChain!=toChain))
                throw new IllegalArgumentException("Local connector classification lacks its positive witness");
        }
        public boolean ordinaryEligible(){return status!=Status.INCOMPATIBLE;}
    }
    private static final class Identity {
        final Object value;Identity(Object value){this.value=value;}
        @Override public int hashCode(){return System.identityHashCode(value);}
        @Override public boolean equals(Object other){return other instanceof Identity i&&i.value==value;}
    }
    private record Key(Identity from,Identity to,RasterPoint a,RasterPoint b,boolean moved){}
    private record Port(Profile profile,PointObservation observation,PointFact fact){}
    private record Extent(double lower,double upper){}
    private final SampledEmissionInput input;
    private final LegacyEmissionDomain domain;
    private final OptionalEmissionContext context;
    private final EmissionWorkBudget budget;
    private final AttemptMemoryLedger.Owner memory;
    private final Session session;
    private final Map<Key,Receipt> receipts=new HashMap<>();
    private final Set<Long> rowPairs=new HashSet<>();
    private long stationExtractions;
    private boolean closed;
    private RuntimeException terminal;

    public ManagedOrdinaryAffinity(SampledEmissionInput input,LegacyEmissionDomain domain){
        this.input=Objects.requireNonNull(input);this.domain=Objects.requireNonNull(domain);
        input.requireOpenAndUnchanged();
        if(domain.binding()!=input.legacyBinding().orElse(null))throw new IllegalArgumentException("Foreign managed native domain");
        context=input.context();budget=input.workBudget();memory=input.openExecutionOwner("managed-ordinary-affinity-v1");
        try{reserve(1024);session=NativeConnectorEvaluator.openAffinityProfiles(input);}
        catch(RuntimeException failure){memory.close();if(ExperimentalMeasuredModeInventory.terminal(failure))input.recordTerminalExecutionFailure(failure);throw failure;}
    }
    public long stationExtractions(){live();return stationExtractions;}
    public int preparedRowPairs(){live();return rowPairs.size();}
    public Receipt classify(LegacyEmissionDomain.State from,LegacyEmissionDomain.State to){
        return classifyActual(from,to,null,null,false);
    }
    /** Actual proposal positions are reconstructed by the caller's unchanged native row/offset law. */
    public Receipt classifyFinal(LegacyEmissionDomain.State from,LegacyEmissionDomain.State to,RasterPoint a,RasterPoint b){
        return classifyActual(from,to,a,b,true);
    }
    Receipt classifyFinalGeometry(LegacyEmissionDomain.State from,LegacyEmissionDomain.State to,EmissionResult.Vertex a,EmissionResult.Vertex b){
        return classifyActual(from,to,null,null,true,a,b);
    }
    private Receipt classifyActual(LegacyEmissionDomain.State from,LegacyEmissionDomain.State to,RasterPoint a,RasterPoint b,boolean moved){
        return classifyActual(from,to,a,b,moved,null,null);
    }
    private Receipt classifyActual(LegacyEmissionDomain.State from,LegacyEmissionDomain.State to,RasterPoint a,RasterPoint b,boolean moved,
            EmissionResult.Vertex proposedA,EmissionResult.Vertex proposedB){
        try{
            fresh();work(8);
            if(!moved){a=domain.rasterPoint(from);b=domain.rasterPoint(to);}
            else if(proposedA!=null||proposedB!=null){work(16);a=proposalRaster(from,proposedA);b=proposalRaster(to,proposedB);}
            var key=new Key(new Identity(from),new Identity(to),a,b,moved);
            var cached=receipts.get(key);if(cached!=null)return cached;
            reserve(1024);var result=prepare(from,to,a,b,moved);fresh();receipts.put(key,result);return result;
        }catch(RuntimeException failure){abort(failure);throw failure;}
    }
    /** Source-owned final gate shared by the kernel and genuine run regression controls. */
    EmissionResult.ManagedSmoothingOutcome certifyRun(List<LegacyEmissionDomain.State> states,List<EmissionResult.Vertex> raw,
            List<EmissionResult.Vertex> actual,int firstVertex){
        try{
            fresh();int size=states.size();work(32L+8L*size);reserve(256L+128L*size);
            if(size<2||raw.size()!=size||actual.size()!=size||firstVertex<0)throw new IllegalArgumentException("Managed run dimensions disagree");
            for(int i=0;i<size;i++){
                var state=states.get(i);
                if(i>0&&state.originalRow()!=states.get(i-1).originalRow()+1)throw new IllegalArgumentException("Managed run is not consecutive in original row ownership");
                if(!raw.get(i).offsetRasterPixels().orElseThrow().equals(state.offsetRasterPixels())
                        ||!proposalRaster(state,raw.get(i)).equals(domain.rasterPoint(state))
                        ||actual.get(i).kind()!=raw.get(i).kind())throw new IllegalArgumentException("Managed raw run differs from its actual native origin");
                proposalRaster(state,actual.get(i));
            }
            boolean changed=false,safe=true;var reason=EmissionResult.SmoothingReason.NO_CHANGE;
            var evidence=new ArrayList<Receipt>();
            for(int edge=0;edge+1<size;edge++){
                work(1);
                if(actual.get(edge).position().equals(raw.get(edge).position())&&actual.get(edge).offsetRasterPixels().equals(raw.get(edge).offsetRasterPixels())
                        &&actual.get(edge+1).position().equals(raw.get(edge+1).position())&&actual.get(edge+1).offsetRasterPixels().equals(raw.get(edge+1).offsetRasterPixels()))continue;
                changed=true;var receipt=classifyFinalGeometry(states.get(edge),states.get(edge+1),actual.get(edge),actual.get(edge+1));evidence.add(receipt);
                if(receipt.status()!=Status.COMPATIBLE){safe=false;
                    if(receipt.status()==Status.INCOMPATIBLE)reason=EmissionResult.SmoothingReason.FINAL_BRANCH_CONFLICT;
                    else if(reason!=EmissionResult.SmoothingReason.FINAL_BRANCH_CONFLICT)reason=EmissionResult.SmoothingReason.FINAL_EVIDENCE_UNAVAILABLE;
                }
            }
            if(changed&&safe)reason=EmissionResult.SmoothingReason.MEASURED_COMPATIBLE;
            if(!safe)for(int i=0;i<size;i++)actual.set(i,raw.get(i));
            fresh();work(16L+evidence.size());
            return new EmissionResult.ManagedSmoothingOutcome(firstVertex,Math.addExact(firstVertex,size-1),!changed?EmissionResult.SmoothingDisposition.UNCHANGED:
                safe?EmissionResult.SmoothingDisposition.ACCEPTED:EmissionResult.SmoothingDisposition.RAW_RETAINED,reason,evidence);
        }catch(RuntimeException failure){abort(failure);throw failure;}
    }
    private RasterPoint proposalRaster(LegacyEmissionDomain.State state,EmissionResult.Vertex vertex){
        if(!(vertex.owner() instanceof EmissionMapping.ObservationVertex observation)||observation.observationIndex()!=state.originalRow()
                ||vertex.nativeStateId().orElseThrow()!=state.nativeStateId())throw new IllegalArgumentException("Managed proposal lacks its original native occurrence");
        var profile=domain.binding().observations().get(state.originalRow()).measurementProfile();double offset=vertex.offsetRasterPixels().orElseThrow();
        var point=new RasterPoint(profile.anchorScreen().x+profile.normalScreen().x*offset,profile.anchorScreen().y+profile.normalScreen().y*offset);
        if(!context.metricAt(point).equals(vertex.position()))throw new IllegalArgumentException("Managed geometry differs from its native row/offset proposal");
        return point;
    }
    private final class Building {
        Domain geometry;Family family;
        final List<StationPlan> plan=new ArrayList<>();final List<StationFact> facts=new ArrayList<>();
        final List<Profile> profiles=new ArrayList<>();final List<DirectLink> links=new ArrayList<>();
        final List<List<Integer>> localModes=new ArrayList<>();
        final List<CutPiece> cut=new ArrayList<>();Port a,b;int ca=-1,cb=-1;
        final List<UnknownInterval> unknownIntervals=new ArrayList<>();final List<ModeFootprint> footprints=new ArrayList<>();
        final List<ParameterBox> connectorBounds=new ArrayList<>();
        BilinearSegmentIntegral.Result scalar;int failing=-1;
        Receipt finish(Status status,Reason reason,LegacyEmissionDomain.State from,LegacyEmissionDomain.State to,RasterPoint pa,RasterPoint pb){
            work(32L+plan.size()+facts.size()+links.size()+cut.size()+unknownIntervals.size()+footprints.size()+connectorBounds.size());reserve(512L+32L*(plan.size()+facts.size()+links.size()+cut.size()+unknownIntervals.size()+footprints.size()+connectorBounds.size()));
            return new Receipt(status,reason,from.evaluatorState(),to.evaluatorState(),pa,pb,
                Optional.ofNullable(a).map(Port::fact),Optional.ofNullable(b).map(Port::fact),
                new Proof(Optional.ofNullable(geometry),plan,facts,links,cut,reason,failing,unknownIntervals,footprints,connectorBounds,Availability.UNAVAILABLE),ca,cb,Optional.ofNullable(scalar));
        }
    }
    private Receipt prepare(LegacyEmissionDomain.State from,LegacyEmissionDomain.State to,RasterPoint pa,RasterPoint pb,boolean moved){
        var out=new Building();
        if(from.kind()!=LegacyEmissionDomain.Kind.OBSERVED_PEAK||to.kind()!=LegacyEmissionDomain.Kind.OBSERVED_PEAK
                ||from.evaluatorState().sourceClass()!=NativeConnectorEvaluator.SourceClass.OBSERVED
                ||to.evaluatorState().sourceClass()!=NativeConnectorEvaluator.SourceClass.OBSERVED
                ||from.peakOwner()==null||to.peakOwner()==null||from.peakOwner().syntheticCenter()||to.peakOwner().syntheticCenter()
                ||to.originalRow()!=from.originalRow()+1)
            return out.finish(Status.UNKNOWN,Reason.NATIVE_FALLBACK,from,to,pa,pb);
        var left=domain.binding().observations().get(from.originalRow());var right=domain.binding().observations().get(to.originalRow());
        if(left.sourceClass()!=NativeConnectorEvaluator.SourceClass.OBSERVED||right.sourceClass()!=NativeConnectorEvaluator.SourceClass.OBSERVED)
            return out.finish(Status.UNKNOWN,Reason.NATIVE_FALLBACK,from,to,pa,pb);
        var origin=context.metricAt(left.measurementProfile().samplingAnchor().rasterPoint());
        var end=context.metricAt(right.measurementProfile().samplingAnchor().rasterPoint());
        double dx=end.xMeters()-origin.xMeters(),dy=end.yMeters()-origin.yMeters(),length=Math.hypot(dx,dy);
        if(!(length>0)||!Double.isFinite(length)||context.minimumNativePitchMeters().isEmpty())
            return out.finish(Status.UNKNOWN,Reason.UNSUPPORTED_CHART,from,to,pa,pb);
        var v=new MetricPoint(dx/length,dy/length);var n=new MetricPoint(-v.yMeters(),v.xMeters());
        double pitch=context.minimumNativePitchMeters().orElseThrow();double half=context.searchHalfWidthMeters();
        // Fixed once from actual endpoint geometry; no Mode or winning strand chooses the guard.
        Family initial;
        try{initial=session.freezeFamily(origin,v,n,-4*pitch,length+4*pitch);
            var xa=initial.projectNativeSegment(context.nativeGrid().toGrid(pa),context.nativeGrid().toGrid(pa),0,1);
            var xb=initial.projectNativeSegment(context.nativeGrid().toGrid(pb),context.nativeGrid().toGrid(pb),0,1);
            double innerFirst=Math.nextDown(Math.min(xa.minimumS(),xb.minimumS())-.25*pitch);
            double innerLast=Math.nextUp(Math.max(xa.maximumS(),xb.maximumS())+.25*pitch);
            double normLower=Math.nextDown(Math.nextDown(Math.hypot(n.xMeters(),n.yMeters())));
            if(!(normLower>0))return out.finish(Status.UNKNOWN,Reason.UNSUPPORTED_CHART,from,to,pa,pb);
            double guard=Math.nextUp(Math.nextUp(3*pitch)/normLower);
            double lowerU=Math.nextDown(Math.min(xa.minimumU(),xb.minimumU())-guard),upperU=Math.nextUp(Math.max(xa.maximumU(),xb.maximumU())+guard);
            if(!(lowerU>-half&&upperU<half))return out.finish(Status.UNKNOWN,Reason.DOMAIN_UNRESOLVED,from,to,pa,pb);
            out.geometry=new Domain(origin,v,n,innerFirst,innerLast,Math.nextDown(innerFirst-3*pitch),Math.nextUp(innerLast+3*pitch),half,pitch,lowerU,upperU,NATIVE_CELL_PARTS);
            out.family=session.freezeFamily(origin,v,n,out.geometry.outerFirst(),out.geometry.outerLast());
            if(!out.family.continuousTransportCertified())return out.finish(Status.UNKNOWN,Reason.UNSUPPORTED_CHART,from,to,pa,pb);
        }catch(IllegalArgumentException unresolved){return out.finish(Status.UNKNOWN,Reason.UNSUPPORTED_CHART,from,to,pa,pb);}
        long pair=((long)from.originalRow()<<32)|(to.originalRow()&0xffffffffL);if(!rowPairs.contains(pair)){reserve(128);rowPairs.add(pair);}
        double first=out.geometry.innerFirst()-1.5*pitch,last=out.geometry.innerLast()+1.5*pitch;
        double magnitude=Math.nextUp(Math.max(Math.abs(origin.xMeters()),Math.abs(origin.yMeters()))
            +Math.nextUp(Math.max(Math.abs(out.geometry.outerFirst()),Math.abs(out.geometry.outerLast()))
                *Math.nextUp(Math.abs(v.xMeters())+Math.abs(v.yMeters())))
            +Math.nextUp(half*Math.nextUp(Math.abs(n.xMeters())+Math.abs(n.yMeters()))));
        // At most two rounded constructions per coordinate and both adjacent stations.
        // 32 ulps of the whole coordinate magnitude strictly encloses those errors.
        double roundingGuard=Math.nextUp(32*Math.ulp(magnitude));
        double step=Math.nextDown(Math.nextDown(.5*pitch)-roundingGuard);
        if(!(step>roundingGuard))return out.finish(Status.UNKNOWN,Reason.DOMAIN_UNRESOLVED,from,to,pa,pb);
        double norm=Math.nextUp(Math.nextUp(Math.hypot(v.xMeters(),v.yMeters())));
        int pieces=count(Math.ceil(Math.nextUp(Math.nextUp(Math.nextUp(last-first)*norm)/step)));
        work(pieces+1L);reserve(256L*(pieces+1L));
        for(int i=0;i<=pieces;i++){
            double s=first+(last-first)*((double)i/pieces);
            out.plan.add(new StationPlan(i,s,out.family.numericAnchor(s)));
        }
        // Check the actual frozen parameter bits, so no station-parameter rounding
        // assumption can make the certified physical spacing exceed half-native.
        for(int i=1;i<out.plan.size();i++){
            work(8);double ds=Math.nextUp(out.plan.get(i).parameter()-out.plan.get(i-1).parameter());
            double upper=Math.nextUp(Math.nextUp(ds*norm)+Math.nextUp(8*Math.ulp(magnitude)));
            if(!(out.plan.get(i).parameter()>out.plan.get(i-1).parameter())||upper>Math.nextDown(.5*pitch))
                return out.finish(Status.UNKNOWN,Reason.DOMAIN_UNRESOLVED,from,to,pa,pb);
        }
        // The complete domain/plan is now frozen before any Mode extraction.
        // Native identity and the moved proposal remain separate; neither is a proximity alias.
        out.a=port(from,pa,n,moved);out.b=port(to,pb,n,moved);
        if(out.a.observation.availability()!=Availability.COMPLETE||out.b.observation.availability()!=Availability.COMPLETE)
            return out.finish(Status.UNKNOWN,Reason.POINT_OWNERSHIP,from,to,pa,pb);
        for(var planned:out.plan){
            out.failing=planned.index();work(1);
            var profile=session.query(out.family,planned.parameter(),from.evaluatorState().row());stationExtractions++;
            out.profiles.add(profile);
            var local=localProofReady(out,profile,planned.index());out.localModes.add(local);
            out.facts.add(snapshot(planned.index(),planned.parameter(),profile,Optional.ofNullable(local)));
            var previous=planned.index()==0?null:out.profiles.get(planned.index()-1);
            if(local==null){
                unqueried(out,previous,profile,planned.index(),LinkReason.INCOMPLETE_PROFILE);
                return out.finish(Status.UNKNOWN,Reason.UNRESOLVED_MODES,from,to,pa,pb);
            }
            if(previous!=null){
                var previousLocal=out.localModes.get(planned.index()-1);
                if(previousLocal.size()!=local.size()){
                    unqueried(out,previous,profile,planned.index(),LinkReason.MODE_COUNT_MISMATCH);
                    return out.finish(Status.UNKNOWN,Reason.UNRESOLVED_LINKS,from,to,pa,pb);
                }
                int count=local.size(),base=out.links.size();reserve(192L*count*count);
                for(int i=0;i<count;i++)for(int j=0;j<count;j++)out.links.add(link(out.family,out.geometry,planned.index()-1,planned.index(),previousLocal.get(i),local.get(j),previous,profile));
                if(!uniqueIdentity(out.links,base,count,count))return out.finish(Status.UNKNOWN,Reason.UNRESOLVED_LINKS,from,to,pa,pb);
            }
        }
        out.failing=-1;
        out.ca=attach(out,out.a,-1);out.cb=attach(out,out.b,-2);
        if(out.ca<0||out.cb<0)return out.finish(Status.UNKNOWN,Reason.INTERIOR_PORT_UNRESOLVED,from,to,pa,pb);
        var ga=context.nativeGrid().toGrid(pa);var gb=context.nativeGrid().toGrid(pb);
        if(!pathWithin(out.family,ga,gb,out.geometry.innerFirst(),out.geometry.innerLast(),out.geometry.localLowerU(),out.geometry.localUpperU(),out.connectorBounds))
            return out.finish(Status.UNKNOWN,Reason.DOMAIN_UNRESOLVED,from,to,pa,pb);
        if(!authority(ga,gb))return out.finish(Status.UNKNOWN,Reason.SOURCE_AUTHORITY,from,to,pa,pb);
        out.scalar=integral(ga,gb);
        if(out.scalar.status()!=BilinearSegmentIntegral.Status.COMPLETE)return out.finish(Status.UNKNOWN,Reason.SCALAR_SUPPORT,from,to,pa,pb);
        if(out.ca==out.cb){
            double lower=-Double.MAX_VALUE,upper=Double.MAX_VALUE,threshold=1;
            for(int i=0;i<out.profiles.size();i++){work(1);var p=out.profiles.get(i);var mode=p.modes().get(out.localModes.get(i).get(out.ca));
                var e=coreExtent(out.family,p,mode);lower=Math.max(lower,e.lower);upper=Math.min(upper,e.upper);
                threshold=Math.min(threshold,mode.accepted().shoulderThreshold());}
            final double coreLower=lower,coreUpper=upper;
            work(out.connectorBounds.size());
            if(lower<upper&&out.connectorBounds.stream().allMatch(box->box.minimumU()>coreLower&&box.maximumU()<coreUpper)
                    &&out.scalar.minimum().orElseThrow()>=threshold)
                return out.finish(Status.COMPATIBLE,Reason.LOCAL_OWNED_CONTINUATION,from,to,pa,pb);
            return out.finish(Status.UNKNOWN,Reason.UNPROVED_CONNECTOR,from,to,pa,pb);
        }
        if(!cut(out,Math.min(out.ca,out.cb),Math.max(out.ca,out.cb)))
            return out.finish(Status.UNKNOWN,Reason.NO_CERTIFIED_CROSSCUT,from,to,pa,pb);
        boolean crossing=false;
        for(var p:out.cut){work(1);if(properCross(ga,gb,p.from,p.to))for(var cell:p.cells){work(1);
            if(OrdinaryAffinityClassifier.crossesCellInterior(ga,gb,cell.cell()))crossing=true;}}
        if(crossing)return out.finish(Status.INCOMPATIBLE,Reason.LOCAL_FIXED_CONNECTOR_DARK_CROSSCUT,from,to,pa,pb);
        return out.finish(Status.UNKNOWN,Reason.UNPROVED_CONNECTOR,from,to,pa,pb);
    }
    private Port port(LegacyEmissionDomain.State state,RasterPoint actual,MetricPoint normal,boolean moved){
        var raw=domain.rasterPoint(state);
        var rawProfile=session.metricAnchoredQuery(state.evaluatorState().row(),raw,normal);
        var binding=session.bindNativeState(rawProfile,domain,state);binding.validate();
        var profile=actual.equals(raw)?rawProfile:session.metricAnchoredQuery(state.evaluatorState().row(),actual,normal);
        var point=actual.equals(raw)?binding.observation():session.observePoint(profile,actual);
        reserve(512+16L*profile.queries().size());work(32L+profile.queries().size());
        var fact=new PointFact(state.originalRow(),state.evaluatorState().row(),moved&&!actual.equals(raw),actual,point.actualMetric(),point.inverseZeroRaster(),point.availability(),point.reason(),
            point.component().map(OwnedModeGeometry::accepted),point.strictScalar(),point.bridge(),point.componentBridge(),profile.probe(),profile.queries());
        return new Port(profile,point,fact);
    }
    private StationFact snapshot(int index,double s,Profile profile,Optional<List<Integer>> local){
        work(16L+profile.modes().size()+profile.queries().size());reserve(256L+192L*profile.modes().size()+16L*profile.queries().size());
        var facts=new ArrayList<ModeFact>();
        for(var mode:profile.modes()){
            boolean position=Double.isFinite(mode.numericCenter())&&Double.isFinite(mode.physicalSigmaUpper());
            facts.add(new ModeFact(mode.accepted(),position?Optional.of(mode.metricPosition()):Optional.empty(),position?Optional.of(mode.rasterPosition()):Optional.empty(),
                position?Optional.of(mode.nativeGridPosition()):Optional.empty(),mode.support(),mode.componentOrderResolved()));
        }
        return new StationFact(index,s,profile.probe(),profile.queries(),facts,local);
    }
    private List<Integer> localProofReady(Building out,Profile profile,int profileIndex){
        var family=out.family;var geometry=out.geometry;
        var extraction=profile.probe().extraction();
        // Conservatively reject ambiguity among actual measured candidates. This finite
        // check makes no absence claim about the unqueried outside intervals below.
        if(profile.modes().isEmpty()||profile.modes().size()!=extraction.result().rawPeaks().size()
                ||!extraction.result().suppressedModes().isEmpty()||!extraction.result().baseline().censoredModes().isEmpty())return null;
        boolean unknownTouches=false;
        for(int i=0;i<profile.queries().size();i++){
            work(1);var q=profile.queries().get(i);
            if(q.sample().valid()&&q.scalarQueried()&&q.originalRouteAuthorized()&&q.acquiredAuthorized())continue;
            double first=profile.queries().get(Math.max(0,i-1)).sample().offsetMeters(),last=profile.queries().get(Math.min(profile.queries().size()-1,i+1)).sample().offsetMeters();
            Optional<ParameterBox> bounds=Optional.empty();UnknownScope scope=UnknownScope.UNRESOLVED;
            try{
                var raster=context.metricLineBounds(profile.probe().metricPosition(),profile.probe().metricNormal(),first,last,budget);
                var box=projectRasterBox(family,raster);bounds=Optional.of(box);
                scope=box.maximumU()<geometry.localLowerU()||box.minimumU()>geometry.localUpperU()?UnknownScope.OUTSIDE_LOCAL_PROOF:UnknownScope.INTERSECTS_LOCAL_PROOF;
            }catch(IllegalArgumentException unresolved){/* An unresolved exclusion cannot authorize the local proof. */}
            reserve(192);out.unknownIntervals.add(new UnknownInterval(profileIndex,i,first,last,bounds,scope));
            unknownTouches |= scope!=UnknownScope.OUTSIDE_LOCAL_PROOF;
        }
        if(unknownTouches)return null;
        reserve(64L+16L*profile.modes().size());var local=new ArrayList<Integer>();
        ParameterBox previous=null;int modeIndex=0;
        for(var m:profile.modes()){
            work(1);if(!m.componentOrderResolved()||m.support().availability()!=Availability.COMPLETE||m.rawCore().isEmpty()||m.flanks().isEmpty()){
                reserve(160);out.footprints.add(new ModeFootprint(profileIndex,modeIndex,m.accepted(),Optional.empty(),UnknownScope.UNRESOLVED));return null;
            }
            Optional<ParameterBox> footprint=Optional.empty();
            try{if(m.support().rasterBounds().isPresent())footprint=Optional.of(projectRasterBox(family,m.support().rasterBounds().orElseThrow()));}
            catch(IllegalArgumentException unresolved){/* Preserve unavailable footprint; never approximate it by a center. */}
            UnknownScope scope=footprint.isEmpty()?UnknownScope.UNRESOLVED:
                footprint.orElseThrow().maximumU()<geometry.localLowerU()||footprint.orElseThrow().minimumU()>geometry.localUpperU()
                    ?UnknownScope.OUTSIDE_LOCAL_PROOF:UnknownScope.INTERSECTS_LOCAL_PROOF;
            reserve(160);out.footprints.add(new ModeFootprint(profileIndex,modeIndex,m.accepted(),footprint,scope));
            if(footprint.isEmpty())return null;
            if(scope==UnknownScope.OUTSIDE_LOCAL_PROOF){modeIndex++;continue;}
            var f=footprint.orElseThrow();
            if(!(f.minimumS()>geometry.outerFirst()&&f.maximumS()<geometry.outerLast()&&f.minimumU()>geometry.localLowerU()&&f.maximumU()<geometry.localUpperU()))return null;
            var current=family.project(m.metricPosition());if(previous!=null&&!(previous.maximumU()<current.minimumU()))return null;previous=current;
            local.add(modeIndex++);
        }
        return local.isEmpty()?null:List.copyOf(local);
    }
    private ParameterBox projectRasterBox(Family family,ManagedGroundTransform.LineBounds raster){
        var map=new CensusGeometry(budget).new GridMap(context.nativeGrid());
        var a=map.pointBounds(new RasterPoint(raster.minimumX(),raster.minimumY()));
        var b=map.pointBounds(new RasterPoint(raster.maximumX(),raster.maximumY()));
        return family.projectNativeSegment(new RasterPoint(a.x().lower(),a.y().lower()),new RasterPoint(b.x().upper(),b.y().upper()),0,1);
    }
    private void unqueried(Building out,Profile previous,Profile current,int station,LinkReason reason){
        if(previous==null)return;reserve(192L*previous.modes().size()*current.modes().size());
        for(int i=0;i<previous.modes().size();i++)for(int j=0;j<current.modes().size();j++){
            work(1);var a=previous.modes().get(i).accepted();var b=current.modes().get(j).accepted();
            out.links.add(new DirectLink(station-1,station,i,j,a,b,Math.min(a.shoulderThreshold(),b.shoulderThreshold()),LinkStatus.NOT_QUERIED,reason,Optional.empty()));
        }
    }
    private DirectLink link(Family family,Domain geometry,int fromStation,int toStation,int i,int j,Profile previous,Profile current){
        work(8);var a=previous.modes().get(i);var b=current.modes().get(j);
        double threshold=Math.min(a.accepted().shoulderThreshold(),b.accepted().shoulderThreshold());
        var ua=family.project(a.metricPosition());var ub=family.project(b.metricPosition());
        double difference=Math.max(Math.abs(Math.nextDown(ua.minimumU()-ub.maximumU())),Math.abs(Math.nextUp(ua.maximumU()-ub.minimumU())));
        double normalLength=Math.nextUp(Math.nextUp(Math.hypot(previous.probe().metricNormal().xMeters(),previous.probe().metricNormal().yMeters())));
        if(Math.nextUp(difference*normalLength)>Math.nextDown(1.5*Math.min(previous.probe().nativePitchMeters(),current.probe().nativePitchMeters())))
            return new DirectLink(fromStation,toStation,i,j,a.accepted(),b.accepted(),threshold,LinkStatus.REJECTED,LinkReason.CONTINUATION_BOUND,Optional.empty());
        var ga=a.nativeGridPosition();var gb=b.nativeGridPosition();
        if(!pathWithin(family,ga,gb,geometry.outerFirst(),geometry.outerLast(),geometry.localLowerU(),geometry.localUpperU(),null))
            return new DirectLink(fromStation,toStation,i,j,a.accepted(),b.accepted(),threshold,LinkStatus.UNAVAILABLE,LinkReason.DOMAIN_UNRESOLVED,Optional.empty());
        if(!authority(ga,gb))return new DirectLink(fromStation,toStation,i,j,a.accepted(),b.accepted(),threshold,LinkStatus.REJECTED,LinkReason.SOURCE_AUTHORITY,Optional.empty());
        var scalar=integral(ga,gb);
        var status=scalar.status()!=BilinearSegmentIntegral.Status.COMPLETE?LinkStatus.UNAVAILABLE:scalar.minimum().orElseThrow()>=threshold?LinkStatus.ADMITTED:LinkStatus.REJECTED;
        var reason=status==LinkStatus.UNAVAILABLE?LinkReason.SCALAR_SUPPORT:status==LinkStatus.ADMITTED?LinkReason.NONE:LinkReason.BELOW_THRESHOLD;
        return new DirectLink(fromStation,toStation,i,j,a.accepted(),b.accepted(),threshold,status,reason,Optional.of(scalar));
    }
    private boolean uniqueIdentity(List<DirectLink> links,int base,int rows,int cols){
        if(rows!=cols)return false;
        for(int i=0;i<rows;i++){int outgoing=0,incoming=0;for(int j=0;j<cols;j++){work(2);
            if(links.get(base+i*cols+j).status==LinkStatus.ADMITTED)outgoing++;
            if(links.get(base+j*cols+i).status==LinkStatus.ADMITTED)incoming++;}
            if(outgoing!=1||incoming!=1||links.get(base+i*cols+i).status!=LinkStatus.ADMITTED)return false;}
        return true;
    }
    private int attach(Building out,Port port,int portIndex){
        var portModes=localProofReady(out,port.profile,portIndex);if(portModes==null)return -1;
        var grid=context.nativeGrid().toGrid(port.fact.actualRaster());ParameterBox point;
        try{point=out.family.projectNativeSegment(grid,grid,0,1);}catch(IllegalArgumentException unresolved){return -1;}
        int bracket=-1;
        for(int i=0;i+1<out.plan.size();i++){work(1);if(point.minimumS()>out.plan.get(i).parameter()&&point.maximumS()<out.plan.get(i+1).parameter()){
            if(bracket>=0)return -1;bracket=i;}}
        if(bracket<0)return -1;
        work(port.profile.modes().size()+portModes.size());int sourceSelected=port.profile.modes().indexOf(port.observation.component().orElseThrow());if(sourceSelected<0)throw new IllegalStateException("Port lost exact accepted Mode owner");
        int selected=portModes.indexOf(sourceSelected);if(selected<0)return -1;
        int chain=-1;
        for(int station: new int[]{bracket,bracket+1}){
            var target=out.profiles.get(station);var targetModes=out.localModes.get(station);int rows=portModes.size(),cols=targetModes.size(),base=out.links.size();reserve(192L*rows*cols);
            for(int i=0;i<rows;i++)for(int j=0;j<cols;j++)out.links.add(link(out.family,out.geometry,portIndex,station,portModes.get(i),targetModes.get(j),port.profile,target));
            if(!uniqueIdentity(out.links,base,rows,cols))return -1;
            if(chain>=0&&chain!=selected)return -1;chain=selected;
        }
        return chain;
    }
    private Extent coreExtent(Family family,Profile profile,OwnedModeGeometry owner){
        var local=localCoreExtent(profile,owner);
        var a=family.project(displace(profile.probe().metricPosition(),profile.probe().metricNormal(),local.lower));
        var b=family.project(displace(profile.probe().metricPosition(),profile.probe().metricNormal(),local.upper));
        return new Extent(Math.min(a.minimumU(),b.minimumU()),Math.max(a.maximumU(),b.maximumU()));
    }
    private Extent localCoreExtent(Profile profile,OwnedModeGeometry owner){
        work(8);var mode=owner.accepted().mode();var core=owner.rawCore().orElseThrow();var samples=profile.probe().samples();
        return new Extent(Math.nextDown(Math.min(mode.coreMinimumMeters(),samples.get(core.firstHighCoreSampleIndex()).offsetMeters())-mode.localizationSigmaMeters()),
            Math.nextUp(Math.max(mode.coreMaximumMeters(),samples.get(core.lastHighCoreSampleIndex()).offsetMeters())+mode.localizationSigmaMeters()));
    }
    private boolean cut(Building out,int lower,int upper){
        reserve(512L*out.profiles.size());var vertices=new ArrayList<RasterPoint>();var lows=new ArrayList<Double>();var highs=new ArrayList<Double>();
        for(int i=0;i<out.profiles.size();i++){
            var p=out.profiles.get(i);var lowerOwner=p.modes().get(out.localModes.get(i).get(lower));var upperOwner=p.modes().get(out.localModes.get(i).get(upper));
            var lo=coreExtent(out.family,p,lowerOwner);var hi=coreExtent(out.family,p,upperOwner);
            if(!(lo.upper<hi.lower))return false;
            // The actual numeric midpoint is only a proposed cut vertex; the whole native path is separately proved.
            var anchor=p.probe().metricPosition();var n=p.probe().metricNormal();
            var localLo=localCoreExtent(p,lowerOwner);var localHi=localCoreExtent(p,upperOwner);
            double midpoint=localLo.upper+(localHi.lower-localLo.upper)*.5;
            vertices.add(context.nativeGrid().toGrid(context.rasterAt(displace(anchor,n,midpoint))));lows.add(lo.upper);highs.add(hi.lower);
        }
        double minimumCut=Double.POSITIVE_INFINITY,maximumCut=Double.NEGATIVE_INFINITY;
        for(int i=1;i<vertices.size();i++){
            var a=vertices.get(i-1);var b=vertices.get(i);double lo=Math.max(lows.get(i-1),lows.get(i)),hi=Math.min(highs.get(i-1),highs.get(i));
            if(!(lo<hi))return false;
            var bounds=new ArrayList<ParameterBox>();reserve(256);
            if(!pathWithin(out.family,a,b,out.geometry.outerFirst(),out.geometry.outerLast(),lo,hi,bounds)||!authority(a,b))return false;
            for(var box:bounds){minimumCut=Math.min(minimumCut,box.minimumU());maximumCut=Math.max(maximumCut,box.maximumU());}
            var previous=out.profiles.get(i-1);var current=out.profiles.get(i);
            var l0=previous.modes().get(out.localModes.get(i-1).get(lower)).accepted();var l1=current.modes().get(out.localModes.get(i).get(lower)).accepted();
            var h0=previous.modes().get(out.localModes.get(i-1).get(upper)).accepted();var h1=current.modes().get(out.localModes.get(i).get(upper)).accepted();
            double threshold=Math.min(Math.min(l0.shoulderThreshold(),l1.shoulderThreshold()),Math.min(h0.shoulderThreshold(),h1.shoulderThreshold()));
            var cells=OrdinaryAffinityClassifier.closedCellSupercover(a,b,budget,memory);reserve(128L+96L*cells.size());
            var evidence=new ArrayList<OrdinaryAffinityClassifier.CellEvidence>();
            for(var cell:cells){work(1);var measured=OrdinaryAffinityClassifier.darkCellEvidence(context.nativeGrid(),cell,threshold,budget);if(measured==null)return false;
                for(int yy=0;yy<=1;yy++)for(int xx=0;xx<=1;xx++){
                    var corner=new RasterPoint(cell.x()+xx,cell.y()+yy);
                    if(!authority(corner,corner)||!pathWithin(out.family,corner,corner,out.geometry.outerFirst(),out.geometry.outerLast(),out.geometry.localLowerU(),out.geometry.localUpperU(),null))return false;
                }evidence.add(measured);}
            out.cut.add(new CutPiece(a,b,threshold,lo,hi,l0,l1,h0,h1,evidence,bounds));
        }
        var first=out.family.projectNativeSegment(vertices.get(0),vertices.get(0),0,1);
        var last=out.family.projectNativeSegment(vertices.get(vertices.size()-1),vertices.get(vertices.size()-1),0,1);
        if(!(first.maximumS()<out.geometry.innerFirst()&&last.minimumS()>out.geometry.innerLast()))return false;
        var pa=context.nativeGrid().toGrid(out.a.fact.actualRaster());var pb=context.nativeGrid().toGrid(out.b.fact.actualRaster());
        var ba=out.family.projectNativeSegment(pa,pa,0,1);var bb=out.family.projectNativeSegment(pb,pb,0,1);
        if(out.ca==lower?!(ba.maximumU()<minimumCut&&bb.minimumU()>maximumCut):!(bb.maximumU()<minimumCut&&ba.minimumU()>maximumCut))return false;
        // Exact native simplicity, including endpoint contacts/collinear overlaps, with no guessed epsilon.
        return simpleCut(vertices);
    }
    private boolean simpleCut(List<RasterPoint> vertices){
        work(16L*vertices.size()*vertices.size());
        if(vertices.size()<2)return false;
        for(int i=1;i<vertices.size();i++){
            var a=vertices.get(i-1);var b=vertices.get(i);
            if(a.x()==b.x()&&a.y()==b.y())return false;
            if(i>1){
                var previous=vertices.get(i-2);
                // Adjacent noncollinear pieces meet only at b's predecessor.
                // Collinear pieces must continue in the same exact native direction;
                // reversal overlaps the previous piece even without a remote contact.
                if(orientation(previous,a,b)==0&&(Math.signum(a.x()-previous.x())*Math.signum(b.x()-a.x())<0
                        ||Math.signum(a.y()-previous.y())*Math.signum(b.y()-a.y())<0))return false;
            }
        }
        for(int i=1;i<vertices.size();i++)for(int j=1;j<i-1;j++)if(closedCross(vertices.get(i-1),vertices.get(i),vertices.get(j-1),vertices.get(j)))return false;
        return true;
    }
    /** Fixed actual native cell-line partition. Both outward neighbors of every exact crossing are retained. */
    private List<Double> partition(RasterPoint a,RasterPoint b){
        long estimate=(long)Math.ceil(Math.abs(b.x()-a.x())+Math.abs(b.y()-a.y()))+8;
        if(estimate<0||estimate>Integer.MAX_VALUE)work(Long.MAX_VALUE);reserve(256+64*estimate);work(estimate);
        var points=new TreeSet<Double>();points.add(0.0);points.add(1.0);var math=new CensusGeometry(budget);
        for(int axis=0;axis<2;axis++){
            double from=axis==0?a.x():a.y(),to=axis==0?b.x():b.y();if(from==to)continue;
            long first=(long)Math.ceil(Math.min(from,to)),last=(long)Math.floor(Math.max(from,to));
            for(long k=first;k<=last;k++){
                work(4);var numerator=BigDecimal.valueOf(k).subtract(new BigDecimal(from));var denominator=new BigDecimal(to).subtract(new BigDecimal(from));
                if(denominator.signum()<0){denominator=denominator.negate();numerator=numerator.negate();}
                var t=math.ratio(numerator,denominator);
                points.add(Math.max(0,Math.min(1,t.lower())));points.add(Math.max(0,Math.min(1,t.upper())));
            }
        }
        // A native cell diagonal's axis box can otherwise project wider than the
        // fixed endpoint margin of the inner ribbon. Quarter each actual cell
        // parameter interval once, by a predeclared law; never widen a domain.
        var cells=List.copyOf(points);reserve(192L*NATIVE_CELL_PARTS*cells.size());work((long)NATIVE_CELL_PARTS*cells.size());
        for(int i=1;i<cells.size();i++)for(int part=1;part<NATIVE_CELL_PARTS;part++)
            points.add(cells.get(i-1)+(cells.get(i)-cells.get(i-1))*((double)part/NATIVE_CELL_PARTS));
        return List.copyOf(points);
    }
    private boolean pathWithin(Family family,RasterPoint a,RasterPoint b,double s0,double s1,double u0,double u1,List<ParameterBox> retained){
        try{
            var split=partition(a,b);if(retained!=null)reserve(96L*split.size());
            for(int i=1;i<split.size();i++){
                work(1);var box=family.projectNativeSegment(a,b,split.get(i-1),split.get(i));if(retained!=null)retained.add(box);
                if(!(box.minimumS()>s0&&box.maximumS()<s1&&box.minimumU()>u0&&box.maximumU()<u1))return false;
            }return true;
        }catch(IllegalArgumentException unresolved){return false;}
    }
    private boolean authority(RasterPoint a,RasterPoint b){
        var grid=context.nativeGrid();
        if(a.x()<0||a.y()<0||b.x()<0||b.y()<0||a.x()>grid.width()-1.0||b.x()>grid.width()-1.0||a.y()>grid.height()-1.0||b.y()>grid.height()-1.0)return false;
        var math=new CensusGeometry(budget);var split=partition(a,b);double scale=context.profileCoordinateScale();
        for(int i=1;i<split.size();i++){
            var t=new CensusGeometry.Interval(split.get(i-1),split.get(i));
            var x=math.mul(math.add(CensusGeometry.Interval.point(a.x()),math.mul(math.sub(CensusGeometry.Interval.point(b.x()),a.x()),t)),CensusGeometry.Interval.point(scale));
            var y=math.mul(math.add(CensusGeometry.Interval.point(a.y()),math.mul(math.sub(CensusGeometry.Interval.point(b.y()),a.y()),t)),CensusGeometry.Interval.point(scale));
            var box=new ManagedGroundTransform.LineBounds(x.lower(),x.upper(),y.lower(),y.upper());
            // Sufficient original-capsule certificate for this actual native subpiece; acquired support is separate.
            if(!context.routeCurveBoxAuthorized(box,budget,memory))return false;
        }return true;
    }
    private BilinearSegmentIntegral.Result integral(RasterPoint a,RasterPoint b){
        long bound=(long)Math.ceil(Math.abs(b.x()-a.x())+Math.abs(b.y()-a.y()))+16;
        budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);
        try(var scratch=memory.reserve(Math.addExact(4096,Math.multiplyExact(bound,2048)))){
            return BilinearSegmentIntegral.evaluate(context.nativeGrid(),a,b,CancellationProbe.NONE,()->{
                budget.charge(EmissionWorkBudget.Operation.CELL_QUERY,1);budget.charge(EmissionWorkBudget.Operation.NODE_READ,4);
            });
        }
    }
    private int orientation(RasterPoint a,RasterPoint b,RasterPoint c){
        work(16);var ax=new BigDecimal(b.x()).subtract(new BigDecimal(a.x()));var ay=new BigDecimal(b.y()).subtract(new BigDecimal(a.y()));
        return ax.multiply(new BigDecimal(c.y()).subtract(new BigDecimal(a.y()))).subtract(ay.multiply(new BigDecimal(c.x()).subtract(new BigDecimal(a.x())))).signum();
    }
    private boolean properCross(RasterPoint a,RasterPoint b,RasterPoint c,RasterPoint d){return orientation(a,b,c)*orientation(a,b,d)<0&&orientation(c,d,a)*orientation(c,d,b)<0;}
    private boolean closedCross(RasterPoint a,RasterPoint b,RasterPoint c,RasterPoint d){
        if(Math.max(a.x(),b.x())<Math.min(c.x(),d.x())||Math.max(c.x(),d.x())<Math.min(a.x(),b.x())
                ||Math.max(a.y(),b.y())<Math.min(c.y(),d.y())||Math.max(c.y(),d.y())<Math.min(a.y(),b.y()))return false;
        return orientation(a,b,c)*orientation(a,b,d)<=0&&orientation(c,d,a)*orientation(c,d,b)<=0;
    }
    private static MetricPoint displace(MetricPoint a,MetricPoint n,double u){return new MetricPoint(a.xMeters()+u*n.xMeters(),a.yMeters()+u*n.yMeters());}
    private int count(double count){if(!Double.isFinite(count)||count<1||count>=Integer.MAX_VALUE)work(Long.MAX_VALUE);return (int)count;}
    private void work(long count){budget.charge(EmissionWorkBudget.Operation.PREPARATION,count);}
    private void reserve(long bytes){budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);memory.reserve(bytes);}
    private void live(){if(terminal!=null)throw terminal;if(closed||memory.isClosed())throw new IllegalStateException("Managed affinity closed");}
    private void fresh(){live();input.requireOpenAndUnchanged();}
    private void abort(RuntimeException failure){if(ExperimentalMeasuredModeInventory.terminal(failure)){
        if(terminal==null)terminal=failure;input.recordTerminalExecutionFailure(failure);try{close();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}}}
    @Override public void close(){if(closed)return;closed=true;try{session.close();}finally{memory.close();}}
}
