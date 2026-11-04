package org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission;

import java.math.BigDecimal;
import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.ManagedGroundTransform.LineBounds;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/**
 * Receipt-only M1. No state, classifier, solver or acquisition consumer is enabled.
 * Tokens borrow their issuing session/input lifetime; public numeric records confer no ownership.
 */
public final class NativeMeasuredProfileReceipt {
    public static final String VERSION = "managed-metric-profile-facts-m1-v1";
    private NativeMeasuredProfileReceipt() { }
    public enum Availability { COMPLETE, UNAVAILABLE }
    public enum Reason { NONE, INVERSE_ENCLOSURE, SUPPORT_LEAVES_QUERY, ROUTE_UNPROVED,
        ACQUIRED_BOX_UNPROVED, CONSERVATIVE_CELL_SUPPORT_UNPROVED, NUMERIC_ORDER_UNRESOLVED,
        UNRESOLVED_COMPONENT, POINT_SUPPORT, BRIDGE_SUPPORT }
    public record QueryPoint(Sample sample, MetricPoint metric, Optional<RasterPoint> raster, Optional<RasterPoint> nativeGrid,
        boolean originalRouteAuthorized, boolean acquiredQueried, boolean acquiredAuthorized, boolean scalarQueried,
        boolean originalMetricQueryGuardAuthorized) { }
    /** Conservative component footprint, not full-query alternative completeness or native state authority. */
    public record Support(Availability availability, Reason reason, double minimumParameter, double maximumParameter,
        Optional<LineBounds> rasterBounds, boolean routeProved, boolean acquiredBoxProved,
        OptionalLong presentCellVisits, OptionalLong invalidCellVisits, OptionalLong outsideCellVisits) { }
    public record ParameterBox(double minimumS,double maximumS,double minimumU,double maximumU) { }

    public static final class Family {
        private final Session issuer;
        private final MetricPoint origin, direction, normal;
        private final double first, last;
        private final BigDecimal determinant;
        private final boolean transportCertified;
        private Family(Session issuer,MetricPoint origin,MetricPoint direction,MetricPoint normal,double first,double last,
                BigDecimal determinant,boolean transportCertified) {
            this.issuer=issuer;this.origin=origin;this.direction=direction;this.normal=normal;
            this.first=first;this.last=last;this.determinant=determinant;this.transportCertified=transportCertified;
        }
        public BigDecimal exactDeterminant(){issuer.live();return determinant;}
        public boolean continuousTransportCertified(){issuer.live();return transportCertified;}
        /** Correct dual-basis parameters, with no assumed-unit dot product. */
        public ParameterBox project(MetricPoint point){
            try{
            issuer.fresh();return projectValidated(point);
            }catch(RuntimeException failure){issuer.abort(failure);throw failure;}
        }
        private ParameterBox projectValidated(MetricPoint point){
            issuer.work(64);
            var dx=new BigDecimal(point.xMeters()).subtract(new BigDecimal(origin.xMeters()));
            var dy=new BigDecimal(point.yMeters()).subtract(new BigDecimal(origin.yMeters()));
            var a=dx.multiply(new BigDecimal(normal.yMeters())).subtract(dy.multiply(new BigDecimal(normal.xMeters())));
            var b=new BigDecimal(direction.xMeters()).multiply(dy).subtract(new BigDecimal(direction.yMeters()).multiply(dx));
            var denominator=determinant;
            if(denominator.signum()<0){denominator=denominator.negate();a=a.negate();b=b.negate();}
            var math=new CensusGeometry(issuer.budget);
            var s=math.ratio(a,denominator);var u=math.ratio(b,denominator);
            return new ParameterBox(s.lower(),s.upper(),u.lower(),u.upper());
        }
        public MetricPoint numericAnchor(double s){
            issuer.live();if(!Double.isFinite(s)||s<first||s>last)throw new IllegalArgumentException("Family station outside frozen domain");
            return new MetricPoint(origin.xMeters()+s*direction.xMeters(),origin.yMeters()+s*direction.yMeters());
        }
        /** Whole actual native subsegment projected through continuous F_N^-1, never rounded G. */
        public ParameterBox projectNativeSegment(RasterPoint from,RasterPoint to,double first,double last){
            try{
                issuer.fresh();issuer.work(16);issuer.budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);
                try(var scratch=issuer.memory.reserve(32768)){
                    var box=issuer.context.nativeSegmentContinuousMetricBounds(from,to,first,last,issuer.budget);
                    var a=projectValidated(new MetricPoint(box.minimumEast(),box.minimumNorth()));
                    var b=projectValidated(new MetricPoint(box.minimumEast(),box.maximumNorth()));
                    var c=projectValidated(new MetricPoint(box.maximumEast(),box.minimumNorth()));
                    var d=projectValidated(new MetricPoint(box.maximumEast(),box.maximumNorth()));
                    return new ParameterBox(Math.min(Math.min(a.minimumS(),b.minimumS()),Math.min(c.minimumS(),d.minimumS())),
                        Math.max(Math.max(a.maximumS(),b.maximumS()),Math.max(c.maximumS(),d.maximumS())),
                        Math.min(Math.min(a.minimumU(),b.minimumU()),Math.min(c.minimumU(),d.minimumU())),
                        Math.max(Math.max(a.maximumU(),b.maximumU()),Math.max(c.maximumU(),d.maximumU())));
                }
            }catch(RuntimeException failure){issuer.abort(failure);throw failure;}
        }
    }

    public static final class Profile {
        private final Session issuer;
        private final int retainedRow;
        private final NativeConnectorEvaluator.Probe probe;
        private final List<QueryPoint> queries;
        private final List<OwnedModeGeometry> modes;
        private final Optional<RasterPoint> authoritativeAnchor;
        private Profile(Session issuer,int retainedRow,NativeConnectorEvaluator.Probe probe,List<QueryPoint> queries,
                Optional<RasterPoint> authoritativeAnchor,List<OwnedModeGeometry> buildingModes){
            this.issuer=issuer;this.retainedRow=retainedRow;this.probe=probe;this.queries=List.copyOf(queries);
            this.authoritativeAnchor=authoritativeAnchor;this.modes=Collections.unmodifiableList(buildingModes);
        }
        public int retainedRow(){issuer.live();return retainedRow;}
        public NativeConnectorEvaluator.Probe probe(){issuer.live();return probe;}
        public List<QueryPoint> queries(){issuer.live();return queries;}
        public List<OwnedModeGeometry> modes(){issuer.live();return modes;}
        public Optional<RasterPoint> authoritativeAnchor(){issuer.live();return authoritativeAnchor;}
        public void validate(){issuer.validate(this);}
    }

    public static final class OwnedModeGeometry {
        private final Profile profile;
        private final AcceptedModeThresholdReceipt accepted;
        private final RawCoreReceipt core;
        private final FlankReceipt flanks;
        private final double numericCenter,physicalSigmaUpper;
        private final boolean componentOrderResolved;
        private final MetricPoint metric,forwardMetric;
        private final RasterPoint raster,nativePoint;
        private final Support support;
        private OwnedModeGeometry(Profile profile,AcceptedModeThresholdReceipt accepted,RawCoreReceipt core,FlankReceipt flanks,
                double c,MetricPoint metric,RasterPoint raster,MetricPoint forwardMetric,RasterPoint nativePoint,
                double physicalSigmaUpper,Support support,boolean componentOrderResolved){
            this.profile=profile;this.accepted=accepted;this.core=core;this.flanks=flanks;this.numericCenter=c;
            this.metric=metric;this.raster=raster;this.forwardMetric=forwardMetric;this.nativePoint=nativePoint;
            this.physicalSigmaUpper=physicalSigmaUpper;this.support=support;this.componentOrderResolved=componentOrderResolved;
        }
        private void live(){profile.issuer.live();}
        public AcceptedModeThresholdReceipt accepted(){live();return accepted;}
        public Optional<RawCoreReceipt> rawCore(){live();return Optional.ofNullable(core);}
        public Optional<FlankReceipt> flanks(){live();return Optional.ofNullable(flanks);}
        public double numericCenter(){live();return numericCenter;}
        public MetricPoint metricPosition(){live();if(metric==null)throw new IllegalStateException("Numeric mode position unavailable");return metric;}
        public RasterPoint rasterPosition(){live();if(raster==null)throw new IllegalStateException("Numeric inverse position unavailable");return raster;}
        public RasterPoint nativeGridPosition(){live();if(nativePoint==null)throw new IllegalStateException("Native point unavailable");return nativePoint;}
        public MetricPoint forwardMetricPosition(){live();if(forwardMetric==null)throw new IllegalStateException("Forward image unavailable");return forwardMetric;}
        public double physicalSigmaUpper(){live();return physicalSigmaUpper;}
        public Support support(){live();return support;}
        public boolean componentOrderResolved(){live();return componentOrderResolved;}
    }

    /** A fresh source point observation is not native state authority. */
    public static final class PointObservation {
        private final Session issuer;
        private final Profile profile;
        private final RasterPoint actual,roundTrip;
        private final MetricPoint actualMetric,numericZero;
        private final Optional<OwnedModeGeometry> component;
        private final OptionalDouble strictScalar;
        private final Optional<BilinearSegmentIntegral.Result> bridge;
        private final Optional<BilinearSegmentIntegral.Result> componentBridge;
        private final Availability availability;
        private final Reason reason;
        private PointObservation(Session issuer,Profile profile,RasterPoint actual,RasterPoint roundTrip,
                MetricPoint actualMetric,MetricPoint numericZero,OwnedModeGeometry component,OptionalDouble strictScalar,
                Optional<BilinearSegmentIntegral.Result> bridge,Optional<BilinearSegmentIntegral.Result> componentBridge,Availability availability,Reason reason){
            this.issuer=issuer;this.profile=profile;this.actual=actual;this.roundTrip=roundTrip;this.actualMetric=actualMetric;
            this.numericZero=numericZero;this.component=Optional.ofNullable(component);this.strictScalar=strictScalar;
            this.bridge=bridge;this.componentBridge=componentBridge;this.availability=availability;this.reason=reason;
        }
        public Availability availability(){issuer.live();return availability;}
        public Reason reason(){issuer.live();return reason;}
        public RasterPoint actualRaster(){issuer.live();return actual;}
        public RasterPoint inverseZeroRaster(){issuer.live();return roundTrip;}
        public MetricPoint actualMetric(){issuer.live();return actualMetric;}
        public MetricPoint numericZeroMetric(){issuer.live();return numericZero;}
        public Optional<OwnedModeGeometry> component(){issuer.live();return component;}
        public OptionalDouble strictScalar(){issuer.live();return strictScalar;}
        public Optional<BilinearSegmentIntegral.Result> bridge(){issuer.live();return bridge;}
        public Optional<BilinearSegmentIntegral.Result> componentBridge(){issuer.live();return componentBridge;}
    }

    /** Only identity membership in the real domain can issue this token. It never creates a state. */
    public static final class NativePointBinding {
        private final Session issuer;
        // Do not keep the caller's entire domain/list tree alive outside its charged kernel scope.
        private final java.lang.ref.WeakReference<LegacyEmissionDomain> domain;
        private final LegacyEmissionDomain.State state;
        private final PointObservation point;
        private NativePointBinding(Session issuer,LegacyEmissionDomain domain,LegacyEmissionDomain.State state,PointObservation point){
            this.issuer=issuer;this.domain=new java.lang.ref.WeakReference<>(domain);this.state=state;this.point=point;
        }
        public PointObservation observation(){issuer.live();return point;}
        public LegacyEmissionDomain.State state(){issuer.live();return state;}
        public void validate(){
            try{
                issuer.fresh();var current=domain.get();
                if(current==null)throw new IllegalStateException("Original native domain unavailable");
                issuer.requireIssued(current,state);
            }catch(RuntimeException failure){issuer.abort(failure);throw failure;}
        }
    }

    private record Query(int row,MetricPoint center,MetricPoint normal,Optional<RasterPoint> anchor) { }
    public static final class Session implements AutoCloseable {
        private final SampledEmissionInput input;
        private final OptionalEmissionContext context;
        private final EmissionWorkBudget budget;
        private final AttemptMemoryLedger.Owner memory;
        private final NativeConnectorEvaluator evaluator;
        private final Map<Query,Profile> profiles;
        private RuntimeException terminalFailure;
        private boolean closed;
        Session(SampledEmissionInput input){
            this.input=Objects.requireNonNull(input);this.context=input.context();this.budget=input.workBudget();
            memory=input.openExecutionOwner(VERSION);
            try{
                reserve(1024);profiles=new HashMap<>();
                evaluator=new NativeConnectorEvaluator(input,new NativeConnectorEvaluator.Domain(List.of(),List.of()),
                    NativeConnectorEvaluator.QueryPolicy.DENSE_EIGHTH_NATIVE_V1);
            }catch(RuntimeException failure){memory.close();terminal(failure);throw failure;}
        }
        private void live(){if(terminalFailure!=null)throw terminalFailure;input.requireRetainedOpen();if(closed||memory.isClosed())throw new IllegalStateException("Measured profile session closed");}
        private void fresh(){live();try{input.requireOpenAndUnchanged();}catch(RuntimeException failure){abort(failure);throw failure;}}
        private void work(long n){budget.charge(EmissionWorkBudget.Operation.PREPARATION,n);}
        private void reserve(long bytes){budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);memory.reserve(bytes);}
        private void terminal(RuntimeException failure){if(ExperimentalMeasuredModeInventory.terminal(failure))input.recordTerminalExecutionFailure(failure);}
        private void abort(RuntimeException failure){
            if(ExperimentalMeasuredModeInventory.terminal(failure)){
                if(terminalFailure==null)terminalFailure=failure;
                terminal(failure);try{close();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}
            }
        }
        public Family freezeFamily(MetricPoint origin,MetricPoint direction,MetricPoint normal,double first,double last){
            try{
                fresh();work(128);reserve(65_536);
                if(!Double.isFinite(first)||!Double.isFinite(last)||first>last)throw new IllegalArgumentException("Invalid family domain");
                BigDecimal determinant=new BigDecimal(direction.xMeters()).multiply(new BigDecimal(normal.yMeters()))
                    .subtract(new BigDecimal(direction.yMeters()).multiply(new BigDecimal(normal.xMeters())));
                if(determinant.signum()==0)throw new IllegalArgumentException("Degenerate physical family");
                boolean available=true;
                try{context.metricFamilyBounds(origin,direction,normal,first,last,-context.searchHalfWidthMeters(),context.searchHalfWidthMeters(),budget);}
                catch(IllegalArgumentException unresolved){available=false;}
                fresh();return new Family(this,origin,direction,normal,first,last,determinant,available);
            }catch(RuntimeException failure){abort(failure);throw failure;}
        }
        public Profile query(Family family,double s,int retainedRow){
            if(family==null||family.issuer!=this)throw new IllegalArgumentException("Foreign physical family");
            return query(retainedRow,family.numericAnchor(s),family.normal);
        }
        public Profile query(int row,MetricPoint center,MetricPoint normal){return query(row,center,normal,Optional.empty());}
        public Profile metricAnchoredQuery(int row,RasterPoint authoritativePoint,MetricPoint normal){
            try{
                fresh();work(2);return query(row,context.metricAt(authoritativePoint),normal,Optional.of(authoritativePoint));
            }catch(RuntimeException failure){abort(failure);throw failure;}
        }
        private Profile query(int row,MetricPoint center,MetricPoint normal,Optional<RasterPoint> anchor){
            try{
                fresh();work(1);var key=new Query(row,center,normal,anchor);var cached=profiles.get(key);if(cached!=null)return cached;
                if(!Double.isFinite(Math.hypot(normal.xMeters(),normal.yMeters()))||!(Math.hypot(normal.xMeters(),normal.yMeters())>0))
                    throw new IllegalArgumentException("Invalid metric direction");
                // Capture actual scalar-query inverse bits at the evaluator call, never by reevaluating transcendental functions afterward.
                var queries=new ArrayList<QueryPoint>();
                var probe=evaluator.measureAffinityProfile(row,center,normal,memory,queries);
                reserve(1024);
                reserve(2048L*probe.extraction().acceptedReceipts().size());
                var buildingModes=new ArrayList<OwnedModeGeometry>(probe.extraction().acceptedReceipts().size());
                var profile=new Profile(this,row,probe,queries,anchor,buildingModes);
                for(var receipt:probe.extraction().acceptedReceipts()){
                    work(1);RawCoreReceipt core=null;FlankReceipt flank=null;
                    for(var c:probe.extraction().result().rawCoreReceipts()){work(1);if(c.mode()==receipt.mode())core=c;}
                    for(var f:probe.extraction().result().flankReceipts()){work(1);if(f.mode()==receipt.mode())flank=f;}
                    double c=receipt.mode().centerMeters(),sigma=receipt.mode().localizationSigmaMeters();
                    MetricPoint m=null,forward=null;RasterPoint r=null,g=null;
                    Support support=unavailable(Reason.INVERSE_ENCLOSURE,Double.NaN,Double.NaN);
                    double physicalSigma=Double.NaN;
                    if(Double.isFinite(c)&&Double.isFinite(sigma)&&sigma>=0){
                        work(24);
                        try{
                            m=displace(center,normal,c);r=context.rasterAt(m);g=context.nativeGrid().toGrid(r);forward=context.metricAt(r);
                            physicalSigma=physicalRadiusUpper(center,normal,c,sigma,m);
                            double lo=Math.nextDown(receipt.mode().coreMinimumMeters()-sigma);
                            double hi=Math.nextUp(receipt.mode().coreMaximumMeters()+sigma);
                            if(core!=null){
                                if(core.leftAdjacentSampleIndex()>=0)lo=Math.min(lo,probe.samples().get(core.leftAdjacentSampleIndex()).offsetMeters());
                                if(core.rightAdjacentSampleIndex()>=0&&core.rightAdjacentSampleIndex()<probe.samples().size())hi=Math.max(hi,probe.samples().get(core.rightAdjacentSampleIndex()).offsetMeters());
                            }
                            if(flank!=null){
                                if(flank.leftBracketSampleIndex()>=1)lo=Math.min(lo,probe.samples().get(flank.leftBracketSampleIndex()-1).offsetMeters());
                                if(flank.rightBracketSampleIndex()>=0&&flank.rightBracketSampleIndex()+1<probe.samples().size())hi=Math.max(hi,probe.samples().get(flank.rightBracketSampleIndex()+1).offsetMeters());
                            }
                            // Source checks inside support are not caught as geometric uncertainty.
                            support=unavailable(Reason.NONE,lo,hi);
                        }catch(IllegalArgumentException unresolved){support=unavailable(Reason.INVERSE_ENCLOSURE,Double.NaN,Double.NaN);}
                    }
                    boolean ordered=componentOrder(profile,core,flank);
                    if(!ordered)support=unavailable(Reason.NUMERIC_ORDER_UNRESOLVED,support.minimumParameter(),support.maximumParameter());
                    else if(support.reason()==Reason.NONE)support=support(center,normal,support.minimumParameter(),support.maximumParameter());
                    buildingModes.add(new OwnedModeGeometry(profile,receipt,core,flank,c,m,r,forward,g,physicalSigma,support,ordered));
                }
                fresh();profiles.put(key,profile);return profile;
            }catch(RuntimeException failure){abort(failure);throw failure;}
        }
        private Support unavailable(Reason reason,double lo,double hi){
            return new Support(Availability.UNAVAILABLE,reason,lo,hi,Optional.empty(),false,false,OptionalLong.empty(),OptionalLong.empty(),OptionalLong.empty());
        }
        private boolean componentOrder(Profile profile,RawCoreReceipt core,FlankReceipt flank){
            if(core==null||flank==null||!flank.hasTwoSidedValidOutwardContinuation())return false;
            int first=Math.min(core.leftAdjacentSampleIndex(),flank.leftBracketSampleIndex()-1);
            int last=Math.max(core.rightAdjacentSampleIndex(),flank.rightBracketSampleIndex()+1);
            if(first<0||last>=profile.queries.size())return false;
            for(int i=first;i<=last;i++){
                work(4);var current=profile.queries.get(i);
                if(!current.sample.valid()||!current.scalarQueried||!current.originalRouteAuthorized||!current.acquiredAuthorized
                    ||current.raster.isEmpty()||current.nativeGrid.isEmpty())return false;
                if(i>first){var prior=profile.queries.get(i-1);
                    if(!(prior.sample.offsetMeters()<current.sample.offsetMeters())||prior.metric.equals(current.metric)
                        ||prior.raster.equals(current.raster)||prior.nativeGrid.equals(current.nativeGrid))return false;
                }
            }
            return true;
        }
        private double physicalRadiusUpper(MetricPoint center,MetricPoint normal,double c,double sigma,MetricPoint actual){
            var math=new CensusGeometry(budget);
            var u=new CensusGeometry.Interval(Math.nextDown(c-sigma),Math.nextUp(c+sigma));
            var x=math.add(CensusGeometry.Interval.point(center.xMeters()),math.mul(CensusGeometry.Interval.point(normal.xMeters()),u));
            var y=math.add(CensusGeometry.Interval.point(center.yMeters()),math.mul(CensusGeometry.Interval.point(normal.yMeters()),u));
            double dx=Math.nextUp(Math.max(Math.abs(x.lower()-actual.xMeters()),Math.abs(x.upper()-actual.xMeters())));
            double dy=Math.nextUp(Math.max(Math.abs(y.lower()-actual.yMeters()),Math.abs(y.upper()-actual.yMeters())));
            double norm=Math.hypot(dx,dy);
            return Math.nextUp(norm+2*Math.ulp(norm));
        }
        private Support support(MetricPoint center,MetricPoint normal,double lo,double hi){
            if(!Double.isFinite(lo)||!Double.isFinite(hi)||lo>hi)return unavailable(Reason.INVERSE_ENCLOSURE,lo,hi);
            if(lo < -context.searchHalfWidthMeters()||hi > context.searchHalfWidthMeters())return unavailable(Reason.SUPPORT_LEAVES_QUERY,lo,hi);
            final LineBounds box;
            try{box=context.metricLineBounds(center,normal,lo,hi,budget);}
            catch(IllegalArgumentException unknown){return unavailable(Reason.INVERSE_ENCLOSURE,lo,hi);}
            boolean route=context.routeCurveBoxAuthorized(box,budget,memory);
            var grid=context.nativeGrid();work(16);
            var a=grid.toGrid(new RasterPoint(box.minimumX(),box.minimumY()));
            var b=grid.toGrid(new RasterPoint(box.maximumX(),box.maximumY()));
            double x0=Math.nextDown(Math.min(a.x(),b.x())),x1=Math.nextUp(Math.max(a.x(),b.x()));
            double y0=Math.nextDown(Math.min(a.y(),b.y())),y1=Math.nextUp(Math.max(a.y(),b.y()));
            boolean acquired=x0>=0&&y0>=0&&x1<=grid.width()-1.0&&y1<=grid.height()-1.0;
            if(!acquired)return new Support(Availability.UNAVAILABLE,Reason.ACQUIRED_BOX_UNPROVED,lo,hi,Optional.of(box),route,false,OptionalLong.empty(),OptionalLong.empty(),OptionalLong.empty());
            long present=0,invalid=0;
            int firstX=Math.max(0,(int)Math.floor(x0)),lastX=Math.min(grid.width()-2,(int)Math.floor(x1));
            int firstY=Math.max(0,(int)Math.floor(y0)),lastY=Math.min(grid.height()-2,(int)Math.floor(y1));
            for(int y=firstY;y<=lastY;y++)for(int x=firstX;x<=lastX;x++){
                budget.charge(EmissionWorkBudget.Operation.CELL_QUERY,1);
                boolean valid=grid.supportsCell(x,y);
                for(int dy=0;dy<=1;dy++)for(int dx=0;dx<=1;dx++){
                    budget.charge(EmissionWorkBudget.Operation.NODE_READ,1);double value=grid.nodeValue(x+dx,y+dy);
                    valid &= Double.isFinite(value)&&value>=0&&value<=1;
                }
                if(valid)present++;else invalid++;
            }
            Reason reason=!route?Reason.ROUTE_UNPROVED:invalid>0?Reason.CONSERVATIVE_CELL_SUPPORT_UNPROVED:Reason.NONE;
            return new Support(reason==Reason.NONE?Availability.COMPLETE:Availability.UNAVAILABLE,reason,lo,hi,Optional.of(box),route,true,OptionalLong.of(present),OptionalLong.of(invalid),OptionalLong.of(0));
        }
        public void validate(Profile profile){
            fresh();if(profile==null||profile.issuer!=this)throw new IllegalArgumentException("Foreign measured profile");
        }
        public PointObservation observePoint(Profile profile,RasterPoint actual){
            try{
                validate(profile);work(64);reserve(1024);
                var actualMetric=context.metricAt(actual);var zero=displace(profile.probe.metricPosition(),profile.probe.metricNormal(),0);
                var inverse=context.rasterAt(zero);
                OwnedModeGeometry selected=null;Reason reason=Reason.UNRESOLVED_COMPONENT;
                boolean complete=profile.probe.extraction().result().suppressedModes().isEmpty()
                    &&profile.probe.extraction().result().baseline().censoredModes().isEmpty()
                    &&profile.probe.extraction().acceptedReceipts().size()==profile.probe.extraction().result().rawPeaks().size();
                if(actualMetric.equals(profile.probe.metricPosition())&&complete){
                    for(var mode:profile.modes){
                        work(1);
                        if(!mode.componentOrderResolved||mode.accepted.rawCore().isEmpty()||mode.core==null||mode.flanks==null||!mode.flanks.hasTwoSidedValidOutwardContinuation()
                            ||!mode.core.leftAdjacentObservedValid()||!mode.core.leftAdjacentBelowCore()
                            ||!mode.core.rightAdjacentObservedValid()||!mode.core.rightAdjacentBelowCore())continue;
                        var samples=profile.probe.samples();
                        double left=samples.get(mode.core.leftAdjacentSampleIndex()).offsetMeters();
                        double right=samples.get(mode.core.rightAdjacentSampleIndex()).offsetMeters();
                        if(!(left<0&&right>0)||mode.accepted.mode().distanceToCenterSet(0)!=0)continue;
                        if(selected!=null){selected=null;break;}selected=mode;
                    }
                    if(selected!=null)for(var other:profile.modes){
                        work(1);if(other==selected)continue;
                        var mode=other.accepted.mode();
                        double lower=Math.nextDown(mode.coreMinimumMeters()-mode.localizationSigmaMeters());
                        double upper=Math.nextUp(mode.coreMaximumMeters()+mode.localizationSigmaMeters());
                        if(!Double.isFinite(lower)||!Double.isFinite(upper)||lower<=0&&upper>=0){selected=null;break;}
                    }
                }
                OptionalDouble scalar=OptionalDouble.empty();Optional<BilinearSegmentIntegral.Result> bridge=Optional.empty(),componentBridge=Optional.empty();
                if(selected!=null){
                    boolean authority=context.routeSegmentAuthorized(actual,actual,budget,memory)
                        &&context.evidenceSegmentAuthorized(actual,actual,budget,memory);
                    double value=authority?evaluator.affinityStrictValue(actual):Double.NaN;
                    if(Double.isFinite(value))scalar=OptionalDouble.of(value);
                    double threshold=selected.accepted.rawCore().orElseThrow().threshold();
                    reason=Reason.POINT_SUPPORT;
                    if(scalar.isPresent()&&value>=threshold){
                        reason=Reason.BRIDGE_SUPPORT;
                        if(context.routeSegmentAuthorized(actual,inverse,budget,memory)
                            &&context.evidenceSegmentAuthorized(actual,inverse,budget,memory)){
                            var result=integral(actual,inverse);bridge=Optional.of(result);
                            if(result.status()==BilinearSegmentIntegral.Status.COMPLETE&&result.minimum().orElseThrow()>=threshold){
                                // A stronger component witness: connect R(0) to this exact owned raw-stage winner.
                                var winner=profile.queries.get(selected.core.rawStageWinnerSampleIndex()).raster;
                                if(winner.isPresent()&&context.routeSegmentAuthorized(inverse,winner.orElseThrow(),budget,memory)
                                    &&context.evidenceSegmentAuthorized(inverse,winner.orElseThrow(),budget,memory)){
                                    var connected=integral(inverse,winner.orElseThrow());componentBridge=Optional.of(connected);
                                    if(connected.status()==BilinearSegmentIntegral.Status.COMPLETE&&connected.minimum().orElseThrow()>=threshold)reason=Reason.NONE;
                                }
                            }
                        }
                    }
                }
                fresh();
                return new PointObservation(this,profile,actual,inverse,actualMetric,zero,selected,scalar,bridge,componentBridge,
                    reason==Reason.NONE?Availability.COMPLETE:Availability.UNAVAILABLE,reason);
            }catch(RuntimeException failure){abort(failure);throw failure;}
        }
        private BilinearSegmentIntegral.Result integral(RasterPoint a,RasterPoint b){
            var ga=context.nativeGrid().toGrid(a);var gb=context.nativeGrid().toGrid(b);
            double bound=Math.ceil(Math.abs(gb.x()-ga.x())+Math.abs(gb.y()-ga.y()))+16;
            if(!Double.isFinite(bound)||bound>Integer.MAX_VALUE)work(Long.MAX_VALUE);
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);
            try(var scratch=memory.reserve(Math.addExact(4096,Math.multiplyExact((long)bound,2048)))){
                return BilinearSegmentIntegral.evaluate(context.nativeGrid(),ga,gb,CancellationProbe.NONE,()->{
                    budget.charge(EmissionWorkBudget.Operation.CELL_QUERY,1);budget.charge(EmissionWorkBudget.Operation.NODE_READ,4);
                });
            }
        }
        private void requireIssued(LegacyEmissionDomain domain,LegacyEmissionDomain.State state){
            if(domain==null||state==null||domain.binding()!=input.legacyBinding().orElse(null)
                ||state.originalRow()<0||state.originalRow()>=domain.rowCount())throw new IllegalArgumentException("Foreign native domain/row");
            boolean member=false;for(var candidate:domain.observedAt(state.originalRow())){work(1);member |= candidate==state;}
            if(!member||state.kind()!=LegacyEmissionDomain.Kind.OBSERVED_PEAK
                ||state.evaluatorState().sourceClass()!=NativeConnectorEvaluator.SourceClass.OBSERVED
                ||state.peakOwner()==null||state.peakOwner().syntheticCenter()
                ||state.evaluatorState().row()!=domain.binding().observations().get(state.originalRow()).retainedObservationIndex())
                throw new IllegalArgumentException("State lacks issued native identity");
            boolean producerOwned=false;
            for(var sourceOwner:domain.binding().observations().get(state.originalRow()).producerPeaks()){
                work(1);
                if(sourceOwner.originalPeakId()==state.nativeStateId()&&sourceOwner.peak()==state.peakOwner()
                    &&sourceOwner.measurementPeakOwner()==state.evaluatorState().peakOwner()
                    &&ExperimentalMeasuredModeInventory.same(sourceOwner.peak().offsetPx(),state.offsetRasterPixels()))producerOwned=true;
            }
            if(!producerOwned)throw new IllegalArgumentException("Native state has no exact original-to-measurement peak owner");
        }
        public NativePointBinding bindNativeState(Profile profile,LegacyEmissionDomain domain,LegacyEmissionDomain.State state){
            try{
                validate(profile);requireIssued(domain,state);
                if(profile.retainedRow!=state.evaluatorState().row())throw new IllegalArgumentException("Wrong profile row");
                var point=observePoint(profile,domain.rasterPoint(state));fresh();reserve(512);
                return new NativePointBinding(this,domain,state,point);
            }catch(RuntimeException failure){abort(failure);throw failure;}
        }
        private static MetricPoint displace(MetricPoint center,MetricPoint normal,double u){
            return new MetricPoint(center.xMeters()+u*normal.xMeters(),center.yMeters()+u*normal.yMeters());
        }
        @Override public void close(){if(closed)return;closed=true;try{memory.close();}finally{evaluator.close();}}
    }
}
