package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;

import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionPeak;

/**
 * Builds a confidence-weighted longitudinal center reference for one associated corridor identity.
 */
public final class CorridorTubeBuilder {
    private static final double LOCAL_HALF_WINDOW_METERS = 5.0;
    private static final int LOCAL_MIN_WINDOW_PROFILES = 5;
    private static final int LOCAL_MAX_WINDOW_PROFILES = 9;
    private static final double STABILITY_HALF_WINDOW_METERS = 12.0;
    private static final int STABILITY_MIN_WINDOW_PROFILES = 9;
    private static final int STABILITY_MAX_WINDOW_PROFILES = 17;
    private static final double WEAK_STABILITY_HALF_WINDOW_METERS = 32.0;
    private static final int WEAK_STABILITY_MIN_WINDOW_PROFILES = 17;
    private static final int WEAK_STABILITY_MAX_WINDOW_PROFILES = 33;
    private static final int HUBER_ITERATIONS = 2;
    private final MotionPolicy motionPolicy;
    private final RasterMetricTransform componentFrame;
    private final double nativePitchMeters;
    private final Set<Integer> protectedProfiles;

    /** Explicit policy identity; historical replay/default construction remains unchanged. */
    public enum MotionPolicy { HISTORICAL, BOUNDED_SECONDARY_REVERSAL, TARGET_OWNED_APEX }

    /** Creates a stateless tube builder. */
    public CorridorTubeBuilder() {
        this(MotionPolicy.HISTORICAL);
    }

    /** Creates an explicitly opted-in motion classifier without changing fit/window parameters. */
    public CorridorTubeBuilder(MotionPolicy motionPolicy) {
        this.motionPolicy = java.util.Objects.requireNonNull(motionPolicy);
        this.componentFrame = null;
        this.nativePitchMeters = Double.NaN;
        this.protectedProfiles = Set.of();
    }

    /** Explicit COMPONENT_LOCAL_METRIC_XY experiment, based solely on TARGET_OWNED_APEX. */
    public CorridorTubeBuilder(RasterMetricTransform frame, double nativePitchMeters, Set<Integer> protectedProfiles) {
        this.motionPolicy = MotionPolicy.TARGET_OWNED_APEX;
        this.componentFrame = java.util.Objects.requireNonNull(frame);
        if (!Double.isFinite(nativePitchMeters) || nativePitchMeters <= 0) throw new IllegalArgumentException("Factual native pitch required");
        this.nativePitchMeters = nativePitchMeters;
        this.protectedProfiles = Set.copyOf(protectedProfiles);
    }

    /**
     * Builds one profile-aligned robust corridor tube.
     *
     * @param track associated elementary or explicit parent corridor identity
     * @param profiles extracted fine-level corridor profiles
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @param scaleEvidence cross-scale evidence keyed by profile and band id
     * @return robust tube with one slice per profile
     */
    public LongitudinalCorridorTube build(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        double sourcePixelSizePx,
        Map<String, BandScaleEvidence> scaleEvidence
    ) {
        if (componentFrame != null) return buildComponentReference(track, profiles, sourcePixelSizePx, scaleEvidence).tube();
        if (profiles.isEmpty()) {
            return new LongitudinalCorridorTube(List.of());
        }
        double sourcePixel = validSourcePixel(sourcePixelSizePx);
        double[] distanceMeters = cumulativeDistanceMeters(profiles);
        List<Observation> observations = track.points().values().stream()
            .map(point -> observation(point, profiles.get(point.profileIndex()), distanceMeters, sourcePixel,
                scaleEvidence))
            .sorted(Comparator.comparingInt(Observation::profileIndex))
            .toList();
        List<Observation> directObservations = observations.stream()
            .filter(value -> value.support() == CorridorPointSupport.DIRECT_UNION).toList();
        List<CorridorTubeSlice> slices = new ArrayList<>(profiles.size());
        for (int profileIndex = 0; profileIndex < profiles.size(); profileIndex++) {
            CorridorTrackPoint point = track.points().get(profileIndex);
            boolean directlyObserved = point != null && point.support() == CorridorPointSupport.DIRECT_UNION;
            boolean allowMinimumExpansion = !track.parent() || directlyObserved;
            List<Observation> localWindow = supportWindow(observations, distanceMeters[profileIndex],
                LOCAL_HALF_WINDOW_METERS, LOCAL_MIN_WINDOW_PROFILES, LOCAL_MAX_WINDOW_PROFILES,
                allowMinimumExpansion);
            List<Observation> stabilityWindow = supportWindow(observations, distanceMeters[profileIndex],
                STABILITY_HALF_WINDOW_METERS, STABILITY_MIN_WINDOW_PROFILES, STABILITY_MAX_WINDOW_PROFILES,
                allowMinimumExpansion);
            List<Observation> directStabilityWindow = supportWindow(directObservations,
                distanceMeters[profileIndex], STABILITY_HALF_WINDOW_METERS, STABILITY_MIN_WINDOW_PROFILES,
                STABILITY_MAX_WINDOW_PROFILES, allowMinimumExpansion);
            List<Observation> weakStabilityWindow = supportWindow(observations, distanceMeters[profileIndex],
                WEAK_STABILITY_HALF_WINDOW_METERS, WEAK_STABILITY_MIN_WINDOW_PROFILES,
                WEAK_STABILITY_MAX_WINDOW_PROFILES, allowMinimumExpansion);
            Regression local = robustRegression(localWindow, distanceMeters[profileIndex], sourcePixel);
            Regression stability = robustRegression(stabilityWindow, distanceMeters[profileIndex], sourcePixel);
            Regression weakStability = robustRegression(
                weakStabilityWindow, distanceMeters[profileIndex], sourcePixel);
            MotionEvidence motion = motionSupport(directStabilityWindow, sourcePixel, distanceMeters[profileIndex]);
            double motionSupport = motion.support();
            CorridorBand band = point == null ? null : point.band();
            double prominence = band == null ? 0.0 : Math.max(0.0, band.peakIntensity() - band.noiseFloor());
            double weakSignal = clamp((0.35 - prominence) / 0.30);
            double weakStabilityWeight = weakSignal * (1.0 - motionSupport);
            double stabilityCenter = blend(stability.centerOffsetPx(), weakStability.centerOffsetPx(),
                weakStabilityWeight);
            double stabilityTangent = blend(stability.tangentOffsetPerMeter(),
                weakStability.tangentOffsetPerMeter(), weakStabilityWeight);
            double stabilityResidual = blend(stability.residualScalePx(), weakStability.residualScalePx(),
                weakStabilityWeight);
            double stabilityConfidence = blend(stability.confidence(), weakStability.confidence(),
                weakStabilityWeight);
            double effectiveCenter = blend(stabilityCenter, local.centerOffsetPx(), motionSupport);
            double effectiveTangent = blend(stabilityTangent, local.tangentOffsetPerMeter(),
                motionSupport);
            BandScaleEvidence evidence = band == null ? null : scaleEvidence.get(
                CorridorCenterlineOptimizer.scaleEvidenceKey(profileIndex, band.id()));
            CenterEvidence centers = band == null
                ? CenterEvidence.missing(effectiveCenter)
                : centers(profiles.get(profileIndex), band);
            double uncertainty = Math.max(sourcePixel * 0.5,
                blend(stabilityResidual, local.residualScalePx(), motionSupport));
            if (band != null) {
                uncertainty = Math.max(uncertainty, band.uncertaintyPx());
            }
            slices.add(new CorridorTubeSlice(
                profileIndex,
                distanceMeters[profileIndex],
                effectiveCenter,
                effectiveTangent,
                local.centerOffsetPx(),
                local.tangentOffsetPerMeter(),
                stabilityCenter,
                stabilityTangent,
                stabilityResidual,
                motionSupport,
                motion.reason(),
                0.0,
                band == null ? Double.NaN : band.coreMinPx(),
                band == null ? Double.NaN : band.coreMaxPx(),
                band == null ? Double.NaN : band.shoulderMinPx(),
                band == null ? Double.NaN : band.shoulderMaxPx(),
                uncertainty,
                blend(stabilityConfidence, local.confidence(), motionSupport),
                evidence != null && evidence.scaleConflict(),
                evidence != null && evidence.parentMerge(),
                centers.rawCenterPx(),
                centers.lightCenterPx(),
                centers.standardCenterPx(),
                band != null
            ));
        }
        return new LongitudinalCorridorTube(slices);
    }

    /** Immutable audit for the explicit component experiment; no tracker/optimizer work is implied. */
    public record ComponentReferenceResult(LongitudinalCorridorTube tube, LongitudinalCorridorTube ordinaryTube,
            List<ReferenceComponent> components, List<WorldFitDiagnostic> fits, long contactTests, String status,
            Optional<LongitudinalCorridorTube> proposedTube, List<String> numericalFailures, Set<Integer> literalCollars) {
        public ComponentReferenceResult { components=List.copyOf(components); fits=List.copyOf(fits); proposedTube=java.util.Objects.requireNonNull(proposedTube); numericalFailures=List.copyOf(numericalFailures); literalCollars=Set.copyOf(literalCollars); }
        public ComponentReferenceResult(LongitudinalCorridorTube tube, LongitudinalCorridorTube ordinaryTube,
                List<ReferenceComponent> components, List<WorldFitDiagnostic> fits, long contactTests, String status) {
            this(tube,ordinaryTube,components,fits,contactTests,status,Optional.empty(),List.of(),Set.of());
        }
    }
    public record ReferenceComponent(int firstProfile, int lastProfile) { }
    public record WorldFitDiagnostic(int profile, String window, double metricEast, double metricNorth,
            double velocityEastPerMeter, double velocityNorthPerMeter, double offsetPx, double offsetPerMeter,
            double residualMeters, double confidence, double projectionMissMeters, double normalScaleMetersPerPixel,
            List<Double> finalSharedWeights, boolean requiredForStoredFields, boolean projectionPasses,
            double positionalEnvelopeMeters, String dependencyStatus) {
        public WorldFitDiagnostic { finalSharedWeights=List.copyOf(finalSharedWeights); }
    }

    /** Builds the ordinary tube once, then modifies only native-triggered closed components. */
    public ComponentReferenceResult buildComponentReference(CorridorTrack track, List<CorridorProfile> profiles,
            double sourcePixelSizePx, Map<String,BandScaleEvidence> scaleEvidence) {
        if (componentFrame == null) throw new IllegalStateException("Explicit metric reference policy required");
        var ordinary = new CorridorTubeBuilder(MotionPolicy.TARGET_OWNED_APEX).build(track,profiles,sourcePixelSizePx,scaleEvidence);
        double sourcePixel=validSourcePixel(sourcePixelSizePx);
        List<MetricPoint> reference=metricPoints(profiles,ordinary), source=metricPoints(profiles,null);
        List<ReferenceComponent> components=new ArrayList<>();long contactTests=0;
        for(int a=1;a+1<profiles.size()-1;a++)for(int b=a+2;b+1<profiles.size()-1;b++){
            contactTests++;
            if(!FinalGeometryEvaluator.properIntersection(reference.get(a),reference.get(a+1),reference.get(b),reference.get(b+1)))continue;
            if(!direct(track,a)||!direct(track,a+1)||!direct(track,b)||!direct(track,b+1))continue;
            // Refuse inherited source contacts anywhere in the prospective closed component.
            boolean inherited=false;
            for(int c=a;c<=b&&!inherited;c++)for(int d=c+2;d<=b;d++){
                contactTests++;
                if(FinalGeometryEvaluator.properIntersection(source.get(c),source.get(c+1),source.get(d),source.get(d+1))){inherited=true;break;}
            }
            if(!inherited)components.add(new ReferenceComponent(a,b+1));
        }
        if(components.isEmpty())return new ComponentReferenceResult(ordinary,ordinary,List.of(),List.of(),contactTests,"NO_DEFINITE_DIRECT_INTERIOR_TRIGGER");
        var hard=FinalGeometryEvaluator.backtrackFindings(reference).stream()
            .map(f->new ReferenceComponent(f.firstVertex(),f.lastVertex())).toList();
        components=componentMask(profiles.size(),components,hard);
        Set<Integer> collars=literalCollars(profiles.size(),components);
        for(var component:components)for(int row=component.firstProfile();row<=component.lastProfile();row++)
            if(row==0||row==profiles.size()-1||protectedProfiles.contains(row)||!direct(track,row))
                return new ComponentReferenceResult(ordinary,ordinary,components,List.of(),contactTests,"REFUSED_PROTECTED_OR_MISSING_DIRECT_COMPONENT",Optional.empty(),List.of(),collars);
        for(var component:components)for(int a=component.firstProfile();a<component.lastProfile();a++)for(int b=a+2;b<component.lastProfile();b++){
            contactTests++;
            if(FinalGeometryEvaluator.properIntersection(source.get(a),source.get(a+1),source.get(b),source.get(b+1)))
                return new ComponentReferenceResult(ordinary,ordinary,components,List.of(),contactTests,"REFUSED_EXPANDED_INHERITED_SOURCE_CONTACT",Optional.empty(),List.of(),collars);
        }
        double[] distance=cumulativeDistanceMeters(profiles);
        List<Observation> observations=track.points().values().stream().map(p->observation(p,profiles.get(p.profileIndex()),distance,sourcePixel,scaleEvidence)).sorted(Comparator.comparingInt(Observation::profileIndex)).toList();
        var slices=new ArrayList<>(ordinary.slices());var diagnostics=new ArrayList<WorldFitDiagnostic>();var failures=new ArrayList<String>();
        for(var component:components)for(int row=component.firstProfile();row<=component.lastProfile();row++){
            var old=ordinary.at(row);var band=track.points().get(row).band();double w=weakReferenceWeight(old,band);
            var windows=List.of(supportWindow(observations,distance[row],LOCAL_HALF_WINDOW_METERS,LOCAL_MIN_WINDOW_PROFILES,LOCAL_MAX_WINDOW_PROFILES,true),supportWindow(observations,distance[row],STABILITY_HALF_WINDOW_METERS,STABILITY_MIN_WINDOW_PROFILES,STABILITY_MAX_WINDOW_PROFILES,true),supportWindow(observations,distance[row],WEAK_STABILITY_HALF_WINDOW_METERS,WEAK_STABILITY_MIN_WINDOW_PROFILES,WEAK_STABILITY_MAX_WINDOW_PROFILES,true));
            String[] names={"local5m","stability12m","weak32m"};Regression[] fits=new Regression[3];
            for(int i=0;i<3;i++)try{fits[i]=worldRegression(windows.get(i),row,names[i],profiles,distance,sourcePixel,band,diagnostics,requiredForStoredFields(names[i],w));}
                catch(IllegalArgumentException failure){failures.add(row+"/"+names[i]+": "+failure.getMessage());}
            if(fits[0]!=null&&fits[1]!=null&&fits[2]!=null)try{slices.set(row,assembleComponentSlice(old,band,sourcePixel,fits[0],fits[1],fits[2]));}
                catch(IllegalArgumentException failure){failures.add(row+"/assembly: "+failure.getMessage());}
        }
        if(!failures.isEmpty())return new ComponentReferenceResult(ordinary,ordinary,components,diagnostics,contactTests,"REFUSED_NUMERICAL_FIT",Optional.empty(),failures,collars);
        var proposal=new LongitudinalCorridorTube(slices);boolean admissible=diagnostics.stream().allMatch(CorridorTubeBuilder::projectionAdmitted);
        return new ComponentReferenceResult(admissible?proposal:ordinary,ordinary,components,diagnostics,contactTests,admissible?"COMPONENT_LOCAL_METRIC_XY":"UNADMITTED_REQUIRED_PROJECTION_FAILURE",Optional.of(proposal),List.of(),collars);
    }

    /** Causal hard absorption shares vertices only; normalization never adds a marked vertex. */
    private static List<ReferenceComponent> componentMask(int size,List<ReferenceComponent> seeds,List<ReferenceComponent> hard){
        boolean[] marked=new boolean[size];for(var seed:seeds)for(int i=seed.firstProfile();i<=seed.lastProfile();i++)marked[i]=true;
        boolean changed;do{changed=false;for(var interval:hard){boolean intersects=false;for(int i=interval.firstProfile();i<=interval.lastProfile();i++)intersects|=marked[i];if(intersects)for(int i=interval.firstProfile();i<=interval.lastProfile();i++)if(!marked[i]){marked[i]=true;changed=true;}}}while(changed);
        var result=new ArrayList<ReferenceComponent>();for(int i=0;i<size;i++)if(marked[i]){int first=i;while(i+1<size&&marked[i+1])i++;result.add(new ReferenceComponent(first,i));}return List.copyOf(result);
    }
    private static Set<Integer> literalCollars(int size,List<ReferenceComponent> components){var result=new java.util.TreeSet<Integer>();for(var c:components){if(c.firstProfile()>0)result.add(c.firstProfile()-1);if(c.lastProfile()+1<size)result.add(c.lastProfile()+1);}return Set.copyOf(result);}
    private static boolean requiredForStoredFields(String window,double w){return switch(window){case "local5m"->true;case "stability12m"->1-w!=0;case "weak32m"->w!=0;default->throw new IllegalArgumentException("Unknown reference window");};}
    private static boolean projectionAdmitted(WorldFitDiagnostic fit){return !fit.requiredForStoredFields()||fit.projectionPasses();}
    private double weakReferenceWeight(CorridorTubeSlice old,CorridorBand band){return clamp((.35-Math.max(0,band.peakIntensity()-band.noiseFloor()))/.30)*(1-old.motionSupport());}
    /** Exact-zero dependency isolation belongs to this new path only; historical blend is unchanged. */
    private double componentBlend(double a,double b,double weight){return weight==0?a:weight==1?b:blend(a,b,weight);}
    private CorridorTubeSlice assembleComponentSlice(CorridorTubeSlice old,CorridorBand band,double sourcePixel,Regression local,Regression stable,Regression weak){
        double motion=old.motionSupport(),w=weakReferenceWeight(old,band);
        double center=componentBlend(stable.centerOffsetPx(),weak.centerOffsetPx(),w),slope=componentBlend(stable.tangentOffsetPerMeter(),weak.tangentOffsetPerMeter(),w),residual=componentBlend(stable.residualScalePx(),weak.residualScalePx(),w),confidence=componentBlend(stable.confidence(),weak.confidence(),w);
        var result=new CorridorTubeSlice(old.profileIndex(),old.distanceMeters(),componentBlend(center,local.centerOffsetPx(),motion),componentBlend(slope,local.tangentOffsetPerMeter(),motion),local.centerOffsetPx(),local.tangentOffsetPerMeter(),center,slope,residual,motion,old.motionSupportReason(),old.curvatureOffsetPerMeterSquared(),old.coreMinPx(),old.coreMaxPx(),old.shoulderMinPx(),old.shoulderMaxPx(),Math.max(Math.max(sourcePixel*.5,band.uncertaintyPx()),componentBlend(residual,local.residualScalePx(),motion)),componentBlend(confidence,local.confidence(),motion),old.scaleConflict(),old.parentMerge(),old.rawCenterPx(),old.lightCenterPx(),old.standardCenterPx(),old.observed());
        for(double value:new double[]{result.centerOffsetPx(),result.tangentOffsetPerMeter(),result.localCenterOffsetPx(),result.localTangentOffsetPerMeter(),result.stabilityCenterOffsetPx(),result.stabilityTangentOffsetPerMeter(),result.stabilityUncertaintyPx(),result.uncertaintyPx(),result.confidence()})if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite assembled metric tuple");
        return result;
    }

    private static boolean direct(CorridorTrack track,int row){var point=track.points().get(row);return point!=null&&point.support()==CorridorPointSupport.DIRECT_UNION;}
    private List<MetricPoint> metricPoints(List<CorridorProfile> profiles,LongitudinalCorridorTube tube){var result=new ArrayList<MetricPoint>();for(int i=0;i<profiles.size();i++){var p=profiles.get(i).source();var a=p.anchorScreen();var n=p.normalScreen();double d=tube==null?0:tube.at(i).centerOffsetPx();result.add(componentFrame.pixelCenterToMetric(a.x+n.x*d,a.y+n.y*d));}return result;}

    private record WorldFrame(double ax,double ay,double nx,double ny,double scale) { }
    private WorldFrame worldFrame(CorridorProfile profile){var p=profile.source();var a=p.anchorScreen();var n=p.normalScreen();var origin=componentFrame.pixelCenterToMetric(a.x,a.y);var normal=componentFrame.pixelCenterToMetric(a.x+n.x,a.y+n.y);double x=normal.xMeters()-origin.xMeters(),y=normal.yMeters()-origin.yMeters(),scale=StrictMath.hypot(x,y);if(!Double.isFinite(scale)||scale<=1e-12)throw new IllegalArgumentException("Singular metric normal");return new WorldFrame(origin.xMeters(),origin.yMeters(),x/scale,y/scale,scale);}
    private record WorldLine(double x,double y,double vx,double vy) { }
    private Regression worldRegression(List<Observation> observations,int target,String window,List<CorridorProfile> profiles,double[] distances,double sourcePixel,CorridorBand band,List<WorldFitDiagnostic> diagnostics){
        return worldRegression(observations,target,window,profiles,distances,sourcePixel,band,diagnostics,true);
    }
    private Regression worldRegression(List<Observation> observations,int target,String window,List<CorridorProfile> profiles,double[] distances,double sourcePixel,CorridorBand band,List<WorldFitDiagnostic> diagnostics,boolean required){
        if(observations.size()<2)throw new IllegalArgumentException("Insufficient metric fit window");
        double[] x=new double[observations.size()],y=new double[x.length],weights=observations.stream().mapToDouble(Observation::weight).toArray();
        for(int i=0;i<x.length;i++){var f=worldFrame(profiles.get(observations.get(i).profileIndex()));x[i]=f.ax()+f.nx()*f.scale()*observations.get(i).centerOffsetPx();y[i]=f.ay()+f.ny()*f.scale()*observations.get(i).centerOffsetPx();}
        WorldLine fit=weightedWorld(observations,weights,x,y,distances[target]);double residual=nativePitchMeters*.5;
        for(int iteration=0;iteration<HUBER_ITERATIONS;iteration++){
            double[] radial=new double[x.length];for(int i=0;i<x.length;i++){double ds=observations.get(i).distanceMeters()-distances[target];radial[i]=StrictMath.hypot(x[i]-fit.x()-fit.vx()*ds,y[i]-fit.y()-fit.vy()*ds);}
            residual=Math.max(nativePitchMeters*.5,1.5*weightedMedianAbsolute(radial,weights));
            for(int i=0;i<weights.length;i++)weights[i]=observations.get(i).weight()*(radial[i]<=residual?1:residual/Math.max(radial[i],1e-12));
            fit=weightedWorld(observations,weights,x,y,distances[target]);
        }
        var frame=worldFrame(profiles.get(target));double dx=fit.x()-frame.ax(),dy=fit.y()-frame.ay(),normalDistance=dx*frame.nx()+dy*frame.ny();
        double miss=StrictMath.hypot(dx-normalDistance*frame.nx(),dy-normalDistance*frame.ny());
        // Symmetric secants in factual chainage, clipped to the adjacent pair at endpoints.
        int left=Math.max(0,target-1),right=Math.min(profiles.size()-1,target+1);double span=distances[right]-distances[left];if(span<=1e-12)throw new IllegalArgumentException("Degenerate factual derivative");
        var l=worldFrame(profiles.get(left));var r=worldFrame(profiles.get(right));
        double apx=(r.ax()-l.ax())/span,apy=(r.ay()-l.ay())/span,npx=(r.nx()-l.nx())/span,npy=(r.ny()-l.ny())/span,kp=(r.scale()-l.scale())/span;
        double offset=normalDistance/frame.scale(),slope=((fit.vx()-apx)*frame.nx()+(fit.vy()-apy)*frame.ny()+dx*npx+dy*npy)/frame.scale()-normalDistance*kp/(frame.scale()*frame.scale());
        double confidence=Math.min(1,java.util.Arrays.stream(weights).sum()/Math.max(1,observations.size()));
        double envelope=Math.max(nativePitchMeters,band.uncertaintyPx()*frame.scale()),uncertainty=Math.max(Math.max(sourcePixel*.5,nativePitchMeters*.5/frame.scale()),StrictMath.hypot(residual,miss)/frame.scale());
        for(double value:new double[]{fit.x(),fit.y(),fit.vx(),fit.vy(),offset,slope,residual,confidence,miss,envelope,uncertainty})if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite metric fit at "+target+"/"+window);
        for(double weight:weights)if(!Double.isFinite(weight))throw new IllegalArgumentException("Nonfinite shared metric weight");
        boolean passes=miss<=envelope;
        diagnostics.add(new WorldFitDiagnostic(target,window,fit.x(),fit.y(),fit.vx(),fit.vy(),offset,slope,residual,confidence,miss,frame.scale(),java.util.Arrays.stream(weights).boxed().toList(),required,passes,envelope,required?(passes?"REQUIRED_ADMISSIBLE":"REQUIRED_PROJECTION_FAILURE"):(passes?"UNUSED_WITHIN_ENVELOPE":"UNUSED_OUTSIDE_ENVELOPE")));
        return new Regression(offset,slope,uncertainty,confidence);
    }
    private WorldLine weightedWorld(List<Observation> observations,double[] weights,double[] x,double[] y,double target){double sum=0,ts=0,xs=0,ys=0;for(int i=0;i<x.length;i++){sum+=weights[i];ts+=weights[i]*(observations.get(i).distanceMeters()-target);xs+=weights[i]*x[i];ys+=weights[i]*y[i];}if(sum<=1e-12)throw new IllegalArgumentException("Zero metric fit weight");double mt=ts/sum,mx=xs/sum,my=ys/sum,var=0,cx=0,cy=0;for(int i=0;i<x.length;i++){double dt=observations.get(i).distanceMeters()-target-mt;var+=weights[i]*dt*dt;cx+=weights[i]*dt*(x[i]-mx);cy+=weights[i]*dt*(y[i]-my);}if(var<=1e-12)throw new IllegalArgumentException("Singular metric fit span");double vx=cx/var,vy=cy/var;return new WorldLine(mx-vx*mt,my-vy*mt,vx,vy);}

    private Observation observation(
        CorridorTrackPoint point,
        CorridorProfile profile,
        double[] distanceMeters,
        double sourcePixel,
        Map<String, BandScaleEvidence> scaleEvidence
    ) {
        CorridorBand band = point.band();
        BandScaleEvidence evidence = scaleEvidence.getOrDefault(
            CorridorCenterlineOptimizer.scaleEvidenceKey(point.profileIndex(), band.id()),
            BandScaleEvidence.levelZeroOnly());
        double weight = band.signalExistenceConfidence()
            * (0.25 + 0.75 * band.localizationConfidence())
            * (0.50 + 0.50 * evidence.scalePersistence());
        if (point.support() == CorridorPointSupport.BOUNDED_INTERPOLATION) {
            weight *= 0.45;
        }
        CenterEvidence centers = centers(profile, band);
        List<Double> semanticCenters = new ArrayList<>(band.nestedCentersPx());
        double coreCenter = (band.coreMinPx() + band.coreMaxPx()) / 2.0;
        semanticCenters.add(coreCenter);
        if (!profile.source().peaks().isEmpty()) {
            semanticCenters.add(centers.rawCenterPx());
            semanticCenters.add(centers.lightCenterPx());
            semanticCenters.add(centers.standardCenterPx());
        }
        semanticCenters.sort(Comparator.naturalOrder());
        int middle = semanticCenters.size() / 2;
        double semanticCenter = semanticCenters.size() % 2 == 0
            ? 0.5 * (semanticCenters.get(middle - 1) + semanticCenters.get(middle))
            : semanticCenters.get(middle);
        double semanticSpread = semanticCenters.get(semanticCenters.size() - 1) - semanticCenters.get(0);
        double normalizedSpread = semanticSpread / sourcePixel;
        double channelCenterSpread = Math.max(Math.abs(centers.rawCenterPx() - centers.lightCenterPx()),
            Math.max(Math.abs(centers.rawCenterPx() - centers.standardCenterPx()),
                Math.abs(centers.lightCenterPx() - centers.standardCenterPx())));
        double centerAgreement = Math.exp(-normalizedSpread * normalizedSpread);
        double prominence = Math.max(0.0, band.peakIntensity() - band.noiseFloor());
        double semanticCenterStrength = clamp((prominence - 0.25) / 0.30);
        double centerIdentifiability = band.hasMeasuredCenter() && !band.parentHypothesis()
            ? semanticCenterStrength
                * clamp(0.75 * centerAgreement + 0.25 * band.localizationConfidence()) : 0.0;
        if (evidence.scaleConflict() || evidence.parentMerge()) {
            centerIdentifiability *= 0.25;
        }
        double positionCenter = band.centerOffsetPx()
            + centerIdentifiability * (semanticCenter - band.centerOffsetPx());
        return new Observation(point.profileIndex(), distanceMeters[point.profileIndex()], positionCenter,
            Math.max(1e-6, weight), band.uncertaintyPx(), channelCenterSpread / sourcePixel,
            evidence.scaleConflict(), evidence.parentMerge(), point.support());
    }

    private List<Observation> supportWindow(
        List<Observation> observations,
        double targetDistanceMeters,
        double halfWindowMeters,
        int minimumProfiles,
        int maximumProfiles,
        boolean allowMinimumExpansion
    ) {
        int low = 0;
        int high = observations.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (observations.get(middle).distanceMeters() < targetDistanceMeters) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        int left = low - 1;
        int right = low;
        List<Observation> local = new ArrayList<>(Math.min(maximumProfiles, observations.size()));
        while (local.size() < maximumProfiles) {
            double leftDistance = left >= 0
                ? Math.abs(observations.get(left).distanceMeters() - targetDistanceMeters)
                : Double.POSITIVE_INFINITY;
            double rightDistance = right < observations.size()
                ? Math.abs(observations.get(right).distanceMeters() - targetDistanceMeters)
                : Double.POSITIVE_INFINITY;
            if (Math.min(leftDistance, rightDistance) > halfWindowMeters + 1e-9) {
                break;
            }
            if (leftDistance <= rightDistance) {
                local.add(observations.get(left--));
            } else {
                local.add(observations.get(right++));
            }
        }
        while (allowMinimumExpansion
            && local.size() < Math.min(minimumProfiles, observations.size())) {
            double leftDistance = left >= 0
                ? Math.abs(observations.get(left).distanceMeters() - targetDistanceMeters)
                : Double.POSITIVE_INFINITY;
            double rightDistance = right < observations.size()
                ? Math.abs(observations.get(right).distanceMeters() - targetDistanceMeters)
                : Double.POSITIVE_INFINITY;
            if (!Double.isFinite(Math.min(leftDistance, rightDistance))) {
                break;
            }
            if (leftDistance <= rightDistance) {
                local.add(observations.get(left--));
            } else {
                local.add(observations.get(right++));
            }
        }
        return local.stream().sorted(Comparator.comparingDouble(Observation::distanceMeters)).toList();
    }

    private MotionEvidence motionSupport(List<Observation> observations, double sourcePixel) {
        return motionSupport(observations, sourcePixel, Double.NaN);
    }

    private MotionEvidence motionSupport(List<Observation> observations, double sourcePixel,
            double targetDistanceMeters) {
        if (observations.size() < 5) {
            return new MotionEvidence(0.0, "insufficient-direct-profiles");
        }
        double span = observations.get(observations.size() - 1).distanceMeters()
            - observations.get(0).distanceMeters();
        if (span < 8.0) {
            return new MotionEvidence(0.0, "insufficient-span");
        }
        int positive = 0;
        int negative = 0;
        int signChanges = 0;
        int previousSign = 0;
        double deadband = 0.15 * sourcePixel;
        for (int index = 1; index < observations.size(); index++) {
            double motion = observations.get(index).centerOffsetPx()
                - observations.get(index - 1).centerOffsetPx();
            int sign = 0;
            if (motion > deadband) {
                positive++;
                sign = 1;
            } else if (motion < -deadband) {
                negative++;
                sign = -1;
            }
            if (sign != 0) {
                if (previousSign != 0 && sign != previousSign) {
                    signChanges++;
                }
                previousSign = sign;
            }
        }
        int directional = positive + negative;
        if (directional == 0) {
            return new MotionEvidence(0.0, "stationary");
        }
        double coherence = (double) Math.max(positive, negative) / directional;
        double displacement = Math.abs(observations.get(observations.size() - 1).centerOffsetPx()
            - observations.get(0).centerOffsetPx());
        double motionRange = observations.stream().mapToDouble(Observation::centerOffsetPx).max().orElse(0.0)
            - observations.stream().mapToDouble(Observation::centerOffsetPx).min().orElse(0.0);
        double uncertainty = observations.stream().mapToDouble(Observation::uncertaintyPx).average()
            .orElse(sourcePixel);
        double centerAgreement = observations.stream()
            .mapToDouble(value -> clamp(1.0 - value.centerSpreadSourcePx())).average().orElse(0.0);
        double conflictFraction = observations.stream().filter(Observation::scaleConflict).count()
            / (double) observations.size();
        boolean coherentDirection = coherence >= 0.70 && displacement > Math.max(sourcePixel, uncertainty);
        boolean supportedApex = signChanges <= 1 && positive >= 2 && negative >= 2
            && motionRange > Math.max(sourcePixel, uncertainty);
        boolean boundedApex = !coherentDirection && !supportedApex && signChanges > 1
            && motionPolicy == MotionPolicy.BOUNDED_SECONDARY_REVERSAL
            && !boundedApexWitnesses(observations, sourcePixel, Math.max(sourcePixel, uncertainty)).isEmpty();
        List<OwnedTurnWitness> owned = !coherentDirection && !supportedApex && signChanges > 1
            && motionPolicy == MotionPolicy.TARGET_OWNED_APEX
            ? ownedTurnWitnesses(observations, sourcePixel, Math.max(sourcePixel, uncertainty), targetDistanceMeters)
            : List.of();
        boolean ownedApex = !owned.isEmpty();
        boolean authorizedApex = supportedApex || boundedApex || ownedApex;
        if (!coherentDirection && !authorizedApex) {
            return new MotionEvidence(0.0, signChanges > 1 ? "reversing-noise" : "incoherent-motion");
        }
        double coherenceScore = authorizedApex ? 1.0 : clamp((coherence - 0.70) / 0.30);
        double supportedDisplacement = ownedApex
            ? owned.stream().mapToDouble(OwnedTurnWitness::ownedRangePx).max().orElseThrow()
            : authorizedApex ? motionRange : displacement;
        double displacementScore = clamp((supportedDisplacement - Math.max(sourcePixel, uncertainty)) / sourcePixel);
        double support = clamp(coherenceScore * displacementScore * centerAgreement * (1.0 - conflictFraction));
        String reason = ownedApex ? "target-owned-apex" : boundedApex ? "supported-apex-bounded-reversal"
            : supportedApex ? "supported-apex" : "coherent-direction";
        if (centerAgreement < 0.75) {
            reason += "+center-disagreement";
        }
        if (conflictFraction > 0.0) {
            reason += "+scale-conflict";
        }
        return new MotionEvidence(support, reason);
    }

    /** Direct, local turn ownership only; neighboring extrema delimit each completed arm. */
    private List<OwnedTurnWitness> ownedTurnWitnesses(List<Observation> observations,
            double sourcePixel, double threshold, double targetDistanceMeters) {
        if (!Double.isFinite(targetDistanceMeters)) return List.of();
        List<ObservedTurn> turns = new ArrayList<>();
        double deadband = 0.15 * sourcePixel;
        for (int start = 0; start < observations.size();) {
            int end = start;
            while (end + 1 < observations.size()
                    && observations.get(end + 1).profileIndex() == observations.get(end).profileIndex() + 1) end++;
            int previousSign = 0, previousStep = start;
            for (int step = start + 1; step <= end; step++) {
                double delta = observations.get(step).centerOffsetPx() - observations.get(step - 1).centerOffsetPx();
                int sign = delta > deadband ? 1 : delta < -deadband ? -1 : 0;
                if (sign == 0) continue;
                if (previousSign != 0 && sign != previousSign) {
                    double extreme = previousSign * observations.get(previousStep).centerOffsetPx();
                    int first = previousStep, last = previousStep;
                    for (int i = previousStep + 1; i < step; i++) {
                        double value = previousSign * observations.get(i).centerOffsetPx();
                        if (value > extreme) { extreme = value; first = i; last = i; }
                        else if (value == extreme) last = i;
                    }
                    double position = (observations.get(first).distanceMeters()
                        + observations.get(last).distanceMeters()) / 2.0;
                    turns.add(new ObservedTurn(first, last, previousSign, start, end, position));
                }
                previousSign = sign;
                previousStep = step;
            }
            start = end + 1;
        }
        double nearest = turns.stream().mapToDouble(t -> Math.abs(t.distanceMeters() - targetDistanceMeters))
            .min().orElse(Double.POSITIVE_INFINITY);
        List<OwnedTurnWitness> result = new ArrayList<>();
        for (int index = 0; index < turns.size(); index++) {
            ObservedTurn turn = turns.get(index);
            double distance = Math.abs(turn.distanceMeters() - targetDistanceMeters);
            if (distance != nearest || distance > LOCAL_HALF_WINDOW_METERS) continue;
            int left = index > 0 && turns.get(index - 1).segmentStart() == turn.segmentStart()
                ? turns.get(index - 1).last() : turn.segmentStart();
            int right = index + 1 < turns.size() && turns.get(index + 1).segmentStart() == turn.segmentStart()
                ? turns.get(index + 1).first() : turn.segmentEnd();
            if (observations.get(right).distanceMeters() - observations.get(left).distanceMeters() < 8.0) continue;
            double peak = turn.sign() * observations.get(turn.first()).centerOffsetPx();
            double leftExcursion = peak - turn.sign() * observations.get(left).centerOffsetPx();
            double rightExcursion = peak - turn.sign() * observations.get(right).centerOffsetPx();
            int rising = 0, falling = 0;
            double leftViolation = 0, rightViolation = 0, minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
            for (int i = left; i <= right; i++) {
                double value = observations.get(i).centerOffsetPx();
                minimum = Math.min(minimum, value); maximum = Math.max(maximum, value);
                if (i > left && i <= turn.first() && turn.sign() * (value - observations.get(i - 1).centerOffsetPx()) > deadband) rising++;
                if (i > turn.last() && turn.sign() * (observations.get(i - 1).centerOffsetPx() - value) > deadband) falling++;
            }
            for (int i = left; i <= turn.last(); i++) for (int j = i + 1; j <= turn.last(); j++)
                leftViolation = Math.max(leftViolation, turn.sign() * (observations.get(i).centerOffsetPx() - observations.get(j).centerOffsetPx()));
            for (int i = turn.first(); i <= right; i++) for (int j = i + 1; j <= right; j++)
                rightViolation = Math.max(rightViolation, turn.sign() * (observations.get(j).centerOffsetPx() - observations.get(i).centerOffsetPx()));
            if (leftExcursion > threshold && rightExcursion > threshold && rising >= 2 && falling >= 2
                    && leftViolation <= threshold && rightViolation <= threshold) {
                result.add(new OwnedTurnWitness(turn.sign() > 0 ? "peak" : "valley",
                    observations.get(left).profileIndex(), observations.get(turn.first()).profileIndex(),
                    observations.get(turn.last()).profileIndex(), observations.get(right).profileIndex(),
                    turn.distanceMeters(), rising, falling, leftExcursion, rightExcursion,
                    leftViolation, rightViolation, threshold, maximum - minimum));
            }
        }
        return List.copyOf(result);
    }

    private record ObservedTurn(int first, int last, int sign, int segmentStart, int segmentEnd,
        double distanceMeters) { }
    private record OwnedTurnWitness(String kind, int leftProfile, int firstApexProfile, int lastApexProfile,
        int rightProfile, double apexDistanceMeters, int risingSteps, int fallingSteps,
        double leftExcursionPx, double rightExcursionPx, double leftViolationPx, double rightViolationPx,
        double thresholdPx, double ownedRangePx) { }

    /** Boolean authorization witnesses only: no fit or proposed positions are produced. */
    private List<BoundedApexWitness> boundedApexWitnesses(List<Observation> observations,
            double sourcePixel, double threshold) {
        List<BoundedApexWitness> result = new ArrayList<>();
        double deadband = 0.15 * sourcePixel;
        for (double sign : List.of(1.0, -1.0)) {
            for (int apex = 2; apex + 2 < observations.size(); apex++) {
                double peak = sign * observations.get(apex).centerOffsetPx();
                double leftExcursion = peak - sign * observations.get(0).centerOffsetPx();
                double rightExcursion = peak
                    - sign * observations.get(observations.size() - 1).centerOffsetPx();
                if (leftExcursion <= threshold || rightExcursion <= threshold) continue;
                int rising = 0, falling = 0;
                for (int i = 1; i <= apex; i++) {
                    if (sign * (observations.get(i).centerOffsetPx()
                            - observations.get(i - 1).centerOffsetPx()) > deadband) rising++;
                }
                for (int i = apex + 1; i < observations.size(); i++) {
                    if (sign * (observations.get(i - 1).centerOffsetPx()
                            - observations.get(i).centerOffsetPx()) > deadband) falling++;
                }
                if (rising < 2 || falling < 2) continue;
                double leftViolation = 0.0, rightViolation = 0.0;
                for (int i = 0; i <= apex; i++) for (int j = i + 1; j <= apex; j++) {
                    leftViolation = Math.max(leftViolation, sign * (observations.get(i).centerOffsetPx()
                        - observations.get(j).centerOffsetPx()));
                }
                for (int i = apex; i < observations.size(); i++) {
                    for (int j = i + 1; j < observations.size(); j++) {
                        rightViolation = Math.max(rightViolation,
                            sign * (observations.get(j).centerOffsetPx()
                                - observations.get(i).centerOffsetPx()));
                    }
                }
                if (leftViolation <= threshold && rightViolation <= threshold) {
                    result.add(new BoundedApexWitness(sign > 0 ? "peak" : "valley",
                        observations.get(0).profileIndex(), observations.get(apex).profileIndex(),
                        observations.get(observations.size() - 1).profileIndex(), rising, falling,
                        leftExcursion, rightExcursion, leftViolation, rightViolation, threshold));
                }
            }
        }
        return List.copyOf(result);
    }

    private record BoundedApexWitness(String kind, int firstProfile, int apexProfile, int lastProfile,
        int qualifyingRisingSteps, int qualifyingFallingSteps, double leftExcursionPx,
        double rightExcursionPx, double leftViolationPx, double rightViolationPx, double thresholdPx) { }

    private Regression robustRegression(List<Observation> observations, double targetDistance, double sourcePixel) {
        if (observations.isEmpty()) {
            return new Regression(0.0, 0.0, sourcePixel, 0.0);
        }
        double[] weights = observations.stream().mapToDouble(Observation::weight).toArray();
        LinearFit fit = weightedLine(observations, weights, targetDistance);
        double residualScale = sourcePixel * 0.5;
        for (int iteration = 0; iteration < HUBER_ITERATIONS; iteration++) {
            double[] residuals = residuals(observations, fit, targetDistance);
            residualScale = Math.max(sourcePixel * 0.5, 1.5 * weightedMedianAbsolute(residuals, weights));
            for (int index = 0; index < weights.length; index++) {
                double magnitude = Math.abs(residuals[index]);
                double huber = magnitude <= residualScale ? 1.0 : residualScale / Math.max(magnitude, 1e-12);
                weights[index] = observations.get(index).weight() * huber;
            }
            fit = weightedLine(observations, weights, targetDistance);
        }
        double confidence = Math.min(1.0, java.util.Arrays.stream(weights).sum()
            / Math.max(1.0, observations.size()));
        return new Regression(fit.intercept(), fit.slope(), residualScale, confidence);
    }

    private LinearFit weightedLine(List<Observation> observations, double[] weights, double targetDistance) {
        double weightSum = 0.0;
        double xSum = 0.0;
        double ySum = 0.0;
        for (int index = 0; index < observations.size(); index++) {
            double x = observations.get(index).distanceMeters() - targetDistance;
            double weight = weights[index];
            weightSum += weight;
            xSum += weight * x;
            ySum += weight * observations.get(index).centerOffsetPx();
        }
        if (weightSum <= 1e-12) {
            return new LinearFit(observations.get(0).centerOffsetPx(), 0.0);
        }
        double meanX = xSum / weightSum;
        double meanY = ySum / weightSum;
        double covariance = 0.0;
        double variance = 0.0;
        for (int index = 0; index < observations.size(); index++) {
            double x = observations.get(index).distanceMeters() - targetDistance;
            double dx = x - meanX;
            covariance += weights[index] * dx * (observations.get(index).centerOffsetPx() - meanY);
            variance += weights[index] * dx * dx;
        }
        double slope = variance <= 1e-12 ? 0.0 : covariance / variance;
        return new LinearFit(meanY - slope * meanX, slope);
    }

    private double[] residuals(List<Observation> observations, LinearFit fit, double targetDistance) {
        double[] residuals = new double[observations.size()];
        for (int index = 0; index < observations.size(); index++) {
            double x = observations.get(index).distanceMeters() - targetDistance;
            residuals[index] = observations.get(index).centerOffsetPx() - (fit.intercept() + fit.slope() * x);
        }
        return residuals;
    }

    private double weightedMedianAbsolute(double[] values, double[] weights) {
        List<WeightedValue> ordered = new ArrayList<>(values.length);
        for (int index = 0; index < values.length; index++) {
            ordered.add(new WeightedValue(Math.abs(values[index]), weights[index]));
        }
        ordered.sort(Comparator.comparingDouble(WeightedValue::value));
        double total = ordered.stream().mapToDouble(WeightedValue::weight).sum();
        double cumulative = 0.0;
        for (WeightedValue value : ordered) {
            cumulative += value.weight();
            if (cumulative >= total / 2.0) {
                return value.value();
            }
        }
        return 0.0;
    }

    private CenterEvidence centers(CorridorProfile profile, CorridorBand band) {
        CrossSectionPeak peak = profile.source().peaks().stream()
            .min(Comparator.comparingDouble(value -> Math.abs(value.offsetPx() - band.centerOffsetPx())))
            .orElse(null);
        return peak == null
            ? new CenterEvidence(band.centerOffsetPx(), band.centerOffsetPx(), band.centerOffsetPx())
            : new CenterEvidence(peak.rawCenterPx(), peak.lightFilteredCenterPx(), peak.standardFilteredCenterPx());
    }

    private double[] cumulativeDistanceMeters(List<CorridorProfile> profiles) {
        double[] result = new double[profiles.size()];
        if (profiles.isEmpty()) {
            return result;
        }
        double origin = profiles.get(0).source().cumulativeGroundDistanceMeters();
        for (int index = 0; index < profiles.size(); index++) {
            result[index] = profiles.get(index).source().cumulativeGroundDistanceMeters() - origin;
        }
        return result;
    }

    private double validSourcePixel(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 1.0;
    }

    private double blend(double stability, double local, double motionSupport) {
        return stability + clamp(motionSupport) * (local - stability);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record Observation(
        int profileIndex,
        double distanceMeters,
        double centerOffsetPx,
        double weight,
        double uncertaintyPx,
        double centerSpreadSourcePx,
        boolean scaleConflict,
        boolean parentMerge,
        CorridorPointSupport support
    ) {
    }

    private record CenterEvidence(double rawCenterPx, double lightCenterPx, double standardCenterPx) {
        private static CenterEvidence missing(double centerOffsetPx) {
            return new CenterEvidence(centerOffsetPx, centerOffsetPx, centerOffsetPx);
        }
    }

    private record LinearFit(double intercept, double slope) {
    }

    private record Regression(
        double centerOffsetPx,
        double tangentOffsetPerMeter,
        double residualScalePx,
        double confidence
    ) {
    }

    private record WeightedValue(double value, double weight) {
    }

    private record MotionEvidence(double support, String reason) {
    }
}
