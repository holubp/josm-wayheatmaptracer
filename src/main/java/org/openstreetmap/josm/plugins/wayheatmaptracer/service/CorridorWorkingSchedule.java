package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/** Immutable original-observation to retained-vertex identity; never an eligibility decision. */
public final class CorridorWorkingSchedule {
    private final int originalCount;
    private final List<Integer> originalRows;
    private final int[] workingRows;
    private final int left;
    private final int right;

    private CorridorWorkingSchedule(int originalCount,int left,int right) {
        if(originalCount<=0||left<0||right>=originalCount||right<=left+1||right-left+1>16)
            throw new IllegalArgumentException("Bridge schedule requires bounded original collars");
        this.originalCount=originalCount;this.left=left;this.right=right;
        var rows=new ArrayList<Integer>();workingRows=new int[originalCount];java.util.Arrays.fill(workingRows,-1);
        for(int row=0;row<originalCount;row++)if(row<=left||row>=right){workingRows[row]=rows.size();rows.add(row);}
        originalRows=List.copyOf(rows);
    }
    /** Constructs topology only. Callers must separately certify source/identity/native eligibility. */
    static CorridorWorkingSchedule bridge(int originalCount,int left,int right){return new CorridorWorkingSchedule(originalCount,left,right);}
    public int originalCount(){return originalCount;}
    public int workingCount(){return originalRows.size();}
    public int originalRow(int workingIndex){return originalRows.get(workingIndex);}
    public int workingRow(int originalRow){return workingRows[originalRow];}
    public List<Integer> originalRows(){return originalRows;}
    public int leftOriginalRow(){return left;}
    public int rightOriginalRow(){return right;}
    public int rightWorkingRow(){return workingRows[right];}
    public List<Integer> consumedRows(){var rows=new ArrayList<Integer>();for(int i=left+1;i<right;i++)rows.add(i);return List.copyOf(rows);}
    public <T> List<T> retain(List<T> original){if(original.size()!=originalCount)throw new IllegalArgumentException("Original row count differs");return originalRows.stream().map(original::get).toList();}
    CorridorTrack geometryTrack(CorridorTrack original){var points=new LinkedHashMap<Integer,CorridorTrackPoint>();for(int i=0;i<workingCount();i++){var p=original.points().get(originalRow(i));if(p!=null)points.put(i,new CorridorTrackPoint(i,p.band(),p.bridged(),p.support()));}return new CorridorTrack(original.id(),points,original.score(),original.supportRatio(),original.parent(),original.childTrackIds(),original.groupingDecision());}
    LongitudinalCorridorTube geometryTube(LongitudinalCorridorTube original){var rows=new ArrayList<CorridorTubeSlice>();for(int i=0;i<workingCount();i++){var s=original.at(originalRow(i));rows.add(new CorridorTubeSlice(i,s.distanceMeters(),s.centerOffsetPx(),s.tangentOffsetPerMeter(),s.localCenterOffsetPx(),s.localTangentOffsetPerMeter(),s.stabilityCenterOffsetPx(),s.stabilityTangentOffsetPerMeter(),s.stabilityUncertaintyPx(),s.motionSupport(),s.motionSupportReason(),s.curvatureOffsetPerMeterSquared(),s.coreMinPx(),s.coreMaxPx(),s.shoulderMinPx(),s.shoulderMaxPx(),s.uncertaintyPx(),s.confidence(),s.scaleConflict(),s.parentMerge(),s.rawCenterPx(),s.lightCenterPx(),s.standardCenterPx(),s.observed()));}return new LongitudinalCorridorTube(rows);}
    Map<String,BandScaleEvidence> geometryScale(CorridorTrack original,Map<String,BandScaleEvidence> scale){var result=new LinkedHashMap<String,BandScaleEvidence>();for(int i=0;i<workingCount();i++){var p=original.points().get(originalRow(i));if(p==null)continue;var e=scale.get(CorridorCenterlineOptimizer.scaleEvidenceKey(originalRow(i),p.band().id()));if(e!=null)result.put(CorridorCenterlineOptimizer.scaleEvidenceKey(i,p.band().id()),e);}return Map.copyOf(result);}
    EndpointApproachModel geometryApproaches(EndpointApproachModel original){var result=new ArrayList<EndpointApproachModel.EndpointApproach>();for(var a:original.approaches()){int c=requiredWorking(a.constraintProfileIndex()),anchor=a.interiorAnchorProfileIndex()<0?-1:requiredWorking(a.interiorAnchorProfileIndex());var targets=new ArrayList<EndpointApproachModel.GuideTarget>();for(var t:a.targets())targets.add(new EndpointApproachModel.GuideTarget(requiredWorking(t.profileIndex()),t.expectedOffsetPx(),t.positionWeight(),t.ambiguousHeatmap()));result.add(new EndpointApproachModel.EndpointApproach(c,a.direction(),anchor,a.supported(),a.reason(),targets));}return new EndpointApproachModel(result);}
    JunctionContext geometryJunctions(JunctionContext original){return new JunctionContext(original.constraints().stream().map(c->new EndpointConstraint(requiredWorking(c.profileIndex()),c.nodeId(),c.fixed(),c.junction(),c.maxDisplacementPx(),c.priorWeight(),c.approachWindowProfiles())).toList());}
    private int requiredWorking(int original){int row=workingRow(original);if(row<0)throw new IllegalArgumentException("Cannot omit constrained/approach dependency at original row "+original);return row;}

    /** Complete source-occurrence protection over a certified chainage domain, including unsampled nodes. */
    public record SourceProtection(double firstChainage,double lastChainage,List<Double> protectedChainages){
        public SourceProtection{
            protectedChainages=List.copyOf(protectedChainages);
            if(!Double.isFinite(firstChainage)||!Double.isFinite(lastChainage)||lastChainage<firstChainage
                ||protectedChainages.stream().anyMatch(v->!Double.isFinite(v)||v<firstChainage||v>lastChainage))
                throw new IllegalArgumentException("Complete finite source-occurrence protection required");
        }
    }

    /** Strict native cell support and exact decision authority; no sampled-point approximation. */
    @FunctionalInterface public interface SegmentAuthority {
        boolean admits(MetricPoint first,MetricPoint second);
        /** Actual evidence binding when available; injected analytic fixtures have no frozen evidence. */
        default RasterMetricTransform frame(){return null;}
    }

    /** Actual complete-cell native support plus exact frozen route decision authority. */
    public static SegmentAuthority nativeAuthority(
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.EvidenceSnapshot evidence,String fieldName,
        CancellationProbe cancellation){
        var source=evidence.fields().get(fieldName);
        if(source==null)throw new IllegalArgumentException("Requested original scalar field unavailable");
        var field=ScalarIntensityField.fromEvidence(source,cancellation::checkpoint);
        return new SegmentAuthority(){
            @Override public RasterMetricTransform frame(){return evidence.transform();}
            @Override public boolean admits(MetricPoint a,MetricPoint b){
                cancellation.checkpoint();
                var p=evidence.transform().metricToPixelCenter(a);var q=evidence.transform().metricToPixelCenter(b);
                return field.supportsStrictSegment(p.x(),p.y(),q.x(),q.y(),cancellation::checkpoint)
                    &&evidence.routeSegmentAuthorized(a,b);
            }
        };
    }

    /** Admission result, including spent work on refused domains; no solver work is implied. */
    public record Preparation(String status,CorridorWorkingSchedule schedule,BridgeDomain domain,
        long contactTests,long pairTests,long rayTests,long nativeChecks){
        public boolean admitted(){return "ADMITTED".equals(status);}
    }

    /**
     * Selects the one local ordinary-tube component and certifies its complete prepared edge domain.
     * Caller supplies detached, complete source protection and the actual native segment predicate.
     */
    public static Preparation prepare(CorridorTrack track,List<CorridorProfile> profiles,
        LongitudinalCorridorTube tube,List<List<Double>> states,Map<String,BandScaleEvidence> scales,
        EndpointApproachModel approaches,JunctionContext junctions,RasterMetricTransform frame,
        double nativePitch,SourceProtection protection,SegmentAuthority authority,CancellationProbe cancellation){
        return prepare(track,profiles,tube,states,scales,approaches,junctions,frame,nativePitch,protection,authority,cancellation,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults());
    }
    public static Preparation prepare(CorridorCenterlineOptimizer.OriginalDomain original,RasterMetricTransform frame,
        double nativePitch,SourceProtection protection,SegmentAuthority authority,CancellationProbe cancellation){
        return prepare(original.track(),original.profiles(),original.tube(),original.states(),original.scales(),original.approaches(),
            original.junctions(),frame,nativePitch,protection,authority,cancellation,original.budgets());
    }
    private static Preparation prepare(CorridorTrack track,List<CorridorProfile> profiles,
        LongitudinalCorridorTube tube,List<List<Double>> states,Map<String,BandScaleEvidence> scales,
        EndpointApproachModel approaches,JunctionContext junctions,RasterMetricTransform frame,
        double nativePitch,SourceProtection protection,SegmentAuthority authority,CancellationProbe cancellation,
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets limits){
        java.util.Objects.requireNonNull(frame);java.util.Objects.requireNonNull(authority);
        java.util.Objects.requireNonNull(cancellation);
        if(authority.frame()!=null&&!frame.equals(authority.frame()))
            return new Preparation("REFUSED_NATIVE_FRAME_BINDING",null,null,0,0,0,0);
        if(protection==null)return new Preparation("REFUSED_SOURCE_PROTECTION_UNAVAILABLE",null,null,0,0,0,0);
        if(!(nativePitch>0)||!Double.isFinite(nativePitch)||profiles.size()!=tube.slices().size()
            ||profiles.size()!=states.size())throw new IllegalArgumentException("Original preparation identity differs");
        if((long)profiles.size()*(profiles.size()-1)/2>limits.maximumPairVisits()
            ||states.stream().anyMatch(row->row.isEmpty()||row.size()>limits.maximumStatesPerProfile()))
            return new Preparation("RESOURCE_LIMIT",null,null,0,0,0,0);
        if(states.stream().flatMap(List::stream).anyMatch(v->!Double.isFinite(v)))
            throw new IllegalArgumentException("Original state coordinates must be finite");
        int n=profiles.size();var reference=new ArrayList<MetricPoint>();var source=new ArrayList<MetricPoint>();
        for(int i=0;i<n;i++){reference.add(metric(profiles.get(i),tube.at(i).centerOffsetPx(),frame));source.add(metric(profiles.get(i),0,frame));}
        boolean[] marked=new boolean[n];long contacts=0;
        for(int a=1;a+1<n-1;a++)for(int b=a+2;b+1<n-1;b++){
            if(contacts>=limits.maximumPairVisits())return new Preparation("RESOURCE_LIMIT",null,null,contacts,0,0,0);
            checkpoint(cancellation,contacts,0,0,0);contacts++;
            if(FinalGeometryEvaluator.properIntersection(reference.get(a),reference.get(a+1),reference.get(b),reference.get(b+1))
                &&direct(track,a)&&direct(track,a+1)&&direct(track,b)&&direct(track,b+1))
                for(int i=a;i<=b+1;i++)marked[i]=true;
        }
        var hard=FinalGeometryEvaluator.backtrackFindings(reference);
        boolean changed;do{changed=false;for(var h:hard){boolean shares=false;for(int i=h.firstVertex();i<=h.lastVertex();i++)shares|=marked[i];if(shares)for(int i=h.firstVertex();i<=h.lastVertex();i++)if(!marked[i]){marked[i]=true;changed=true;}}}while(changed);
        int first=-1,last=-1,runs=0;for(int i=0;i<n;i++)if(marked[i]){if(i==0||!marked[i-1])runs++;if(first<0)first=i;last=i;}
        if(first<0)return new Preparation("NO_TRIGGER",null,null,contacts,0,0,0);
        if(track.parent()||!track.childTrackIds().isEmpty())return new Preparation("REFUSED_PARENT_TRACK_IDENTITY",null,null,contacts,0,0,0);
        if(runs!=1||first<1||last>=n-1||last-first+3>16)return new Preparation("REFUSED_COMPONENT_DOMAIN",null,null,contacts,0,0,0);
        var s=bridge(n,first-1,last+1);double lo=profiles.get(s.left).source().cumulativeGroundDistanceMeters(),hi=profiles.get(s.right).source().cumulativeGroundDistanceMeters();
        if(!Double.isFinite(lo)||!Double.isFinite(hi)||hi<lo||hi-lo>20||protection.firstChainage()>lo||protection.lastChainage()<hi
            ||protection.protectedChainages().stream().anyMatch(v->v>lo&&v<hi))return new Preparation("REFUSED_SOURCE_PROTECTION_OR_SPAN",s,null,contacts,0,0,0);
        if(protection.protectedChainages().contains(lo)&&!states.get(s.left).equals(List.of(0.0))
            ||protection.protectedChainages().contains(hi)&&!states.get(s.right).equals(List.of(0.0)))
            return new Preparation("REFUSED_MOVABLE_PROTECTED_COLLAR",s,null,contacts,0,0,0);
        try{s.geometryApproaches(approaches);s.geometryJunctions(junctions);}catch(IllegalArgumentException omitted){return new Preparation("REFUSED_ENDPOINT_DEPENDENCY",s,null,contacts,0,0,0);}
        for(int row=s.left;row<=s.right;row++){
            var p=track.points().get(row);
            if(!direct(track,row)||!profiles.get(row).supported()||p.band().parentHypothesis()||!p.band().hasMeasuredCenter())return new Preparation("REFUSED_NOT_DIRECT_ELEMENTARY",s,null,contacts,0,0,0);
            if(row>s.left&&row<s.right&&(p.band().boundaryCompleteness()!=CorridorBand.BoundaryCompleteness.COMPLETE||!p.band().childIds().isEmpty()))
                return new Preparation("REFUSED_CENSORED_OR_UNRESOLVED_INTERIOR",s,null,contacts,0,0,0);
            var ids=new java.util.HashSet<String>();
            if(profiles.get(row).bands().stream().anyMatch(b->!ids.add(b.id()))
                ||profiles.get(row).bands().stream().noneMatch(b->b.equals(p.band())))
                return new Preparation("REFUSED_UNRESOLVED_HIERARCHY",s,null,contacts,0,0,0);
            var e=scales.get(CorridorCenterlineOptimizer.scaleEvidenceKey(row,p.band().id()));
            if(e!=null&&e.scaleConflict())return new Preparation("REFUSED_SCALE_CONFLICT",s,null,contacts,0,0,0);
            if(profiles.get(row).bands().stream().anyMatch(b->b.parentHypothesis()&&(b.childIds().isEmpty()
                ||b.childIds().stream().anyMatch(id->profiles.get(p.profileIndex()).bands().stream().noneMatch(c->c.id().equals(id)&&!c.parentHypothesis())))))
                return new Preparation("REFUSED_UNRESOLVED_HIERARCHY",s,null,contacts,0,0,0);
        }
        for(int a=first;a<last;a++)for(int b=a+2;b<last;b++){contacts++;if(FinalGeometryEvaluator.properIntersection(source.get(a),source.get(a+1),source.get(b),source.get(b+1)))return new Preparation("REFUSED_INHERITED_SOURCE_CONTACT",s,null,contacts,0,0,0);}
        var measuredLeft=metric(profiles.get(s.left),track.points().get(s.left).band().centerOffsetPx(),frame);
        var measuredRight=metric(profiles.get(s.right),track.points().get(s.right).band().centerOffsetPx(),frame);
        if(!bounds(s,track,profiles,frame,measuredLeft,measuredRight,nativePitch))return new Preparation("REFUSED_MEASURED_CHORD_BOUNDS",s,null,contacts,0,0,0);
        long rays=0,pairs=0,nativeChecks=0;var edges=new ArrayList<BridgeEdge>();
        for(int l=0;l<states.get(s.left).size();l++)for(int r=0;r<states.get(s.right).size();r++){
            if(contacts+pairs>=limits.maximumPairVisits())return new Preparation("RESOURCE_LIMIT",s,null,contacts,pairs,rays,nativeChecks);
            checkpoint(cancellation,contacts,pairs,rays,nativeChecks);pairs++;
            var p=metric(profiles.get(s.left),states.get(s.left).get(l),frame);var q=metric(profiles.get(s.right),states.get(s.right).get(r),frame);
            if(!bounds(s,track,profiles,frame,p,q,nativePitch)
                ||!unique(profiles.get(s.left),track.points().get(s.left).band(),states.get(s.left).get(l))
                ||!unique(profiles.get(s.right),track.points().get(s.right).band(),states.get(s.right).get(r)))continue;
            var offsets=new ArrayList<Double>();boolean ok=true;
            for(int row=s.left+1;row<s.right;row++){
                rays++;var band=track.points().get(row).band();var a=metric(profiles.get(row),band.coreMinPx(),frame);var b=metric(profiles.get(row),band.coreMaxPx(),frame);
                double dx=q.xMeters()-p.xMeters(),dy=q.yMeters()-p.yMeters(),sx=b.xMeters()-a.xMeters(),sy=b.yMeters()-a.yMeters(),cx=a.xMeters()-p.xMeters(),cy=a.yMeters()-p.yMeters(),det=dx*sy-dy*sx;
                if(det==0||!Double.isFinite(det)){ok=false;break;}
                double t=(cx*sy-cy*sx)/det,u=(cx*dy-cy*dx)/det,offset=band.coreMinPx()+u*(band.coreMaxPx()-band.coreMinPx());
                if(!Double.isFinite(t)||!Double.isFinite(u)||!Double.isFinite(offset)||t<0||t>1||u<=0||u>=1
                    ||!FinalGeometryEvaluator.properIntersection(p,q,a,b)||!unique(profiles.get(row),band,offset)){ok=false;break;}
                offsets.add(offset);
            }
            if(ok){nativeChecks++;try{if(authority.admits(p,q))edges.add(new BridgeEdge(l,r,offsets));}
                catch(java.util.concurrent.CancellationException cancelled){throw new PreparationCancelledException(new Preparation("CANCELLED",s,null,contacts,pairs,rays,nativeChecks),cancelled);}}
        }
        return new Preparation(edges.isEmpty()?"REFUSED_NO_ADMITTED_EDGES":"ADMITTED",s,new BridgeDomain(s,states,edges),contacts,pairs,rays,nativeChecks);
    }

    /** Cancellation retains actual preparation work and never initiates a fallback solve. */
    public static final class PreparationCancelledException extends java.util.concurrent.CancellationException{
        private final Preparation spent;
        PreparationCancelledException(Preparation spent,Throwable cause){super("Prepared correspondence cancelled");this.spent=spent;initCause(cause);}
        public Preparation spent(){return spent;}
    }
    private static void checkpoint(CancellationProbe cancellation,long contacts,long pairs,long rays,long nativeChecks){
        try{cancellation.checkpoint();}catch(java.util.concurrent.CancellationException cancelled){
            throw new PreparationCancelledException(new Preparation("CANCELLED",null,null,contacts,pairs,rays,nativeChecks),cancelled);
        }
    }

    private static boolean direct(CorridorTrack t,int row){var p=t.points().get(row);return p!=null&&p.support()==CorridorPointSupport.DIRECT_UNION;}
    private static boolean unique(CorridorProfile p,CorridorBand selected,double offset){
        if(!Double.isFinite(offset)||offset<=selected.coreMinPx()||offset>=selected.coreMaxPx())return false;
        for(var b:p.bands())if(!b.parentHypothesis()&&offset>=b.coreMinPx()&&offset<=b.coreMaxPx()
            &&(!b.id().equals(selected.id())||!b.hasMeasuredCenter()||offset==b.coreMinPx()||offset==b.coreMaxPx()))return false;
        return true;
    }
    private static MetricPoint metric(CorridorProfile p,double offset,RasterMetricTransform frame){var a=p.source().anchorScreen();var n=p.source().normalScreen();return frame.pixelCenterToMetric(a.x+n.x*offset,a.y+n.y*offset);}
    private static boolean bounds(CorridorWorkingSchedule s,CorridorTrack track,List<CorridorProfile> profiles,RasterMetricTransform frame,MetricPoint a,MetricPoint b,double pitch){
        if(a.distanceTo(b)>2*pitch)return false;double dx=b.xMeters()-a.xMeters(),dy=b.yMeters()-a.yMeters(),length=dx*dx+dy*dy;
        for(int row=s.left+1;row<s.right;row++){var p=metric(profiles.get(row),track.points().get(row).band().centerOffsetPx(),frame);double t=length==0?0:Math.max(0,Math.min(1,((p.xMeters()-a.xMeters())*dx+(p.yMeters()-a.yMeters())*dy)/length));if(StrictMath.hypot(p.xMeters()-a.xMeters()-t*dx,p.yMeters()-a.yMeters()-t*dy)>.5*pitch)return false;}return true;
    }

    /** Immutable offsets consumed by one adjacent retained edge, in ORIGINAL observation order. */
    public record BridgeEdge(int leftState,int rightState,List<Double> offsets) {
        public BridgeEdge { offsets=List.copyOf(offsets); }
    }

    /** Prepared factor domain. Construction is package-private, never a public authority bypass. */
    public static final class BridgeDomain {
        private final CorridorWorkingSchedule schedule;
        private final List<List<Double>> originalStates;
        private final List<BridgeEdge> edges;
        private final BridgeEdge[][] indexed;
        private BridgeDomain(CorridorWorkingSchedule schedule,List<List<Double>> states,List<BridgeEdge> edges){
            this.schedule=schedule;originalStates=states.stream().map(List::copyOf).toList();
            this.edges=List.copyOf(edges);
            indexed=new BridgeEdge[states.get(schedule.left).size()][states.get(schedule.right).size()];
            for(var e:edges){
                if(e.offsets().size()!=schedule.right-schedule.left-1||indexed[e.leftState()][e.rightState()]!=null)
                    throw new IllegalArgumentException("Invalid or duplicate prepared edge");
                indexed[e.leftState()][e.rightState()]=e;
            }
        }
        public List<BridgeEdge> edges(){return edges;}
        public BridgeEdge edge(int left,int right){return indexed[left][right];}
        void requireBinding(CorridorWorkingSchedule s,List<List<Double>> states){
            if(s!=schedule||!originalStates.equals(states))throw new IllegalArgumentException("Prepared edge/state binding differs");
        }

        /** Geometry-only fixture preparation. Native/source certification is a separate admission gate. */
        static BridgeDomain intersections(CorridorWorkingSchedule s,CorridorTrack track,
            List<CorridorProfile> profiles,List<List<Double>> states){
            var edges=new ArrayList<BridgeEdge>();
            for(int l=0;l<states.get(s.left).size();l++)for(int r=0;r<states.get(s.right).size();r++){
                var p=point(profiles.get(s.left),states.get(s.left).get(l));
                var q=point(profiles.get(s.right),states.get(s.right).get(r));
                var offsets=new ArrayList<Double>();boolean accepted=true;
                for(int row=s.left+1;row<s.right;row++){
                    var selected=track.points().get(row);
                    if(selected==null||selected.support()!=CorridorPointSupport.DIRECT_UNION||selected.band().parentHypothesis()
                        ||!selected.band().hasMeasuredCenter()){accepted=false;break;}
                    var profile=profiles.get(row);var band=selected.band();
                    var a=point(profile,band.coreMinPx());var b=point(profile,band.coreMaxPx());
                    double dx=q.x-p.x,dy=q.y-p.y,sx=b.x-a.x,sy=b.y-a.y,cx=a.x-p.x,cy=a.y-p.y;
                    double determinant=dx*sy-dy*sx;
                    if(determinant==0||!Double.isFinite(determinant)){accepted=false;break;}
                    double t=(cx*sy-cy*sx)/determinant,u=(cx*dy-cy*dx)/determinant;
                    double offset=band.coreMinPx()+u*(band.coreMaxPx()-band.coreMinPx());
                    if(!Double.isFinite(t)||!Double.isFinite(u)||!Double.isFinite(offset)||t<0||t>1||u<=0||u>=1){accepted=false;break;}
                    offsets.add(offset);
                }
                if(accepted)edges.add(new BridgeEdge(l,r,offsets));
            }
            return new BridgeDomain(s,states,edges);
        }
        private static java.awt.geom.Point2D.Double point(CorridorProfile p,double offset){
            var a=p.source().anchorScreen();var n=p.source().normalScreen();
            return new java.awt.geom.Point2D.Double(a.x+n.x*offset,a.y+n.y*offset);
        }
    }
}
