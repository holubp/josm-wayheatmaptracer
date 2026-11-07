package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.plugins.wayheatmaptracer.model.CorridorQuality;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterMetricTransform;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.IntensitySample;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/**
 * Selects a stable centerline inside one longitudinal corridor using second-order dynamic programming.
 */
public final class CorridorCenterlineOptimizer {
    private static final double NON_SUSTAINED_ACCELERATION_MULTIPLIER = 1.15;
    private static final double WEAK_UNSUPPORTED_TUBE_MULTIPLIER = 800.0;
    private static final Comparator<PairState> PAIR_STATE_COMPARATOR = Comparator
        .comparingDouble(PairState::cost)
        .thenComparingDouble(PairState::currentOffset)
        .thenComparingDouble(PairState::previousOffset);

    private final CorridorOptimizationParameters parameters;
    private final RippleRegularizationParameters rippleParameters;
    private final RasterMetricTransform hardGeometryTransform;
    private final CancellationProbe hardGeometryCancellation;
    private HardGeometryWork hardGeometryWork = new HardGeometryWork(HardGeometryOutcome.DISABLED,
        0, 0, 0, 0, 0, 0, 0, 0);
    private final PreparedCounters preparedCounters=new PreparedCounters();
    /** Spent prepared-domain work, independent of historical/default optimizer diagnostics. */
    public PreparedWork preparedWork(){return preparedCounters.snapshot();}
    public record PreparedWork(long originalStates,long retainedUnaryEvaluations,long bridgeUnaryEvaluations,
        java.util.OptionalLong estimatedPreparedTableBytes,String outcome,long originalRows,long copiedProfileRows,long solverStarts){ }
    private static final class PreparedCounters{
        long original,retained,bridge,rows,copied,solves;long bytes=-1;String outcome="UNSTARTED";
        void reset(){original=retained=bridge=rows=copied=solves=0;bytes=-1;outcome="UNSTARTED";}
        PreparedWork snapshot(){return new PreparedWork(original,retained,bridge,bytes<0?java.util.OptionalLong.empty():java.util.OptionalLong.of(bytes),outcome,rows,copied,solves);}
    }

    /**
     * Creates a stateless centerline optimizer.
     */
    public CorridorCenterlineOptimizer() {
        this(CorridorOptimizationParameters.defaults());
    }

    /**
     * Creates an optimizer with explicit documented parameters.
     *
     * @param parameters immutable corridor-aware optimization parameters
     */
    public CorridorCenterlineOptimizer(CorridorOptimizationParameters parameters) {
        this(parameters, RippleRegularizationParameters.defaults());
    }

    CorridorCenterlineOptimizer(CorridorOptimizationParameters parameters,
        RippleRegularizationParameters rippleParameters) {
        this(parameters, rippleParameters, null, CancellationProbe.NONE);
    }

    private CorridorCenterlineOptimizer(CorridorOptimizationParameters parameters,
            RippleRegularizationParameters rippleParameters, RasterMetricTransform transform,
            CancellationProbe cancellation) {
        this.parameters = java.util.Objects.requireNonNull(parameters);
        this.rippleParameters = java.util.Objects.requireNonNull(rippleParameters);
        this.hardGeometryTransform = transform;
        this.hardGeometryCancellation = java.util.Objects.requireNonNull(cancellation);
    }

    /** Creates an explicit opt-in experiment using the factual raster-to-ground transform.
     * Preserves FGE-legal raw paths only. Finalization may insert/remove points; this is not live activation. */
    public CorridorCenterlineOptimizer withLocalHardBacktrackGuard(RasterMetricTransform transform,
            CancellationProbe cancellation) {
        return new CorridorCenterlineOptimizer(parameters, rippleParameters,
            java.util.Objects.requireNonNull(transform), cancellation);
    }

    /** Actual work of the last explicitly guarded solve, including rejected/no-route/cancelled work. */
    public HardGeometryWork hardGeometryWork() { return hardGeometryWork; }

    /** Original evidence/domain capsule, prepared before any observation/output correspondence choice. */
    public static final class OriginalDomain {
        private final Object token;
        private final CorridorTrack track;private final List<CorridorProfile> profiles;private final double sourcePixel;
        private final JunctionContext junctions;private final Map<String,BandScaleEvidence> scales;
        private final LongitudinalCorridorTube tube;private final EndpointApproachModel approaches;
        private final List<List<Double>> states;private final org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets budgets;
        private final CorridorWorkingSchedule.BoundContext context;private final PreparedWork generationWork;
        private OriginalDomain(CorridorTrack track,List<CorridorProfile> profiles,double sourcePixel,JunctionContext junctions,
            Map<String,BandScaleEvidence> scales,LongitudinalCorridorTube tube,EndpointApproachModel approaches,
            List<List<Double>> states,org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets budgets,
            CorridorWorkingSchedule.BoundContext context,Object token,PreparedWork work){
            this.track=track;this.profiles=List.copyOf(profiles);this.sourcePixel=sourcePixel;this.junctions=junctions;
            this.scales=Map.copyOf(scales);this.tube=tube;this.approaches=approaches;this.states=states.stream().map(List::copyOf).toList();
            this.budgets=budgets;this.context=context;this.token=token;generationWork=work;
        }
        public CorridorTrack track(){return track;}
        /** Defensive scalar-profile copies keep mutable Point2D normals outside the frozen domain. */
        public List<CorridorProfile> profiles(){return copyProfiles(profiles);}
        public double sourcePixel(){return sourcePixel;} public JunctionContext junctions(){return junctions;}
        public Map<String,BandScaleEvidence> scales(){return scales;} public LongitudinalCorridorTube tube(){return tube;}
        public EndpointApproachModel approaches(){return approaches;} public List<List<Double>> states(){return states;}
        public org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets budgets(){return budgets;}
        Object token(){return token;} CorridorWorkingSchedule.BoundContext context(){return context;}
    }

    private static List<CorridorProfile> copyProfiles(List<CorridorProfile> profiles){
        return profiles.stream().map(p->{var s=p.source();var n=s.normalScreen();
            if(!(s.samplingAnchor() instanceof ProfileSamplingAnchor)&&!(s.samplingAnchor() instanceof DetachedProfileSamplingLocation))
                throw new IllegalArgumentException("Immutable known profile sampling location required");
            var copy=new RenderedHeatmapSampler.CrossSectionProfile(s.samplingAnchor(),new Point2D.Double(n.x,n.y),s.peaks(),s.anchorWithinRaster(),s.intensitySamples(),s.projectedLateralTransform());
            return new CorridorProfile(p.index(),copy,p.bands(),p.maxIntensity(),p.noiseFloor(),p.prominence(),p.supported());}).toList();
    }

    /** Builds the historical complete original state domain without executing inference. */
    public OriginalDomain prepareOriginalDomain(CorridorTrack track,List<CorridorProfile> profiles,
        double sourcePixel,JunctionContext junctions,Map<String,BandScaleEvidence> scales,LongitudinalCorridorTube tube){
        return prepareOriginalDomain(track,profiles,sourcePixel,junctions,scales,tube,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults());
    }
    public OriginalDomain prepareOriginalDomain(CorridorTrack track,List<CorridorProfile> profiles,
        double sourcePixel,JunctionContext junctions,Map<String,BandScaleEvidence> scales,LongitudinalCorridorTube tube,
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets budgets){
        return prepareOriginalDomain(track,profiles,sourcePixel,junctions,scales,tube,budgets,null);
    }
    public OriginalDomain prepareOriginalDomain(CorridorTrack track,List<CorridorProfile> profiles,
        double sourcePixel,JunctionContext junctions,Map<String,BandScaleEvidence> scales,LongitudinalCorridorTube tube,
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets budgets,CorridorWorkingSchedule.BoundContext context){
        preparedCounters.reset();preparedCounters.outcome="ORIGINAL_PREPARING";
        hardGeometryWork=new HardGeometryWork(HardGeometryOutcome.DISABLED,0,0,0,0,0,0,0,0);
        try{
        sourcePixel=validSourcePixel(sourcePixel);
        if(profiles.isEmpty()||profiles.size()!=tube.slices().size())throw new IllegalArgumentException("Original tube alignment differs");
        profiles=copyProfiles(profiles);preparedCounters.copied=profiles.size();scales=Map.copyOf(scales);
        if(context!=null)for(var p:profiles)context.requireProfileFrame(p.source().samplingAnchor());
        var approaches=new EndpointApproachBuilder().build(track,profiles,tube,junctions,scales);
        var states=new ArrayList<List<Double>>();
        for(int row=0;row<profiles.size();row++){
            hardGeometryCancellation.checkpoint();
            states.add(allowedOffsets(track,profiles,row,sourcePixel,junctions,scales,tube.at(row),approaches));
            preparedCounters.rows++;preparedCounters.original+=states.get(row).size();
        }
        preparedCounters.outcome="ORIGINAL_PREPARED";
        return new OriginalDomain(track,profiles,sourcePixel,junctions,scales,tube,approaches,states,java.util.Objects.requireNonNull(budgets),context,new Object(),preparedCounters.snapshot());
        }catch(java.util.concurrent.CancellationException cancelled){preparedCounters.outcome="CANCELLED";throw cancelled;}
        catch(IllegalArgumentException corrupt){preparedCounters.outcome="BINDING_OR_INPUT_FAILURE";throw corrupt;}
    }

    /** Identity schedule: uses the already prepared original models/states, with unchanged disabled-cleanup arithmetic. */
    public OptimizationResult optimizeOriginalDomain(OriginalDomain d){
        beginInference(d);
        try{
        long pairs=0,transitions=0;
        for(int i=0;i<d.states().size();i++){
            int count=d.states().get(i).size();
            if(count==0||count>d.budgets().maximumStatesPerProfile())throw new CorridorResourceLimitException("Original state cap");
            if(i>0)pairs=Math.addExact(pairs,Math.multiplyExact((long)count,d.states().get(i-1).size()));
            if(i>1)transitions=Math.addExact(transitions,Math.multiplyExact((long)count,Math.multiplyExact((long)d.states().get(i-1).size(),d.states().get(i-2).size())));
        }
        if(d.states().size()>1)transitions=Math.addExact(transitions,Math.multiplyExact((long)d.states().get(0).size(),d.states().get(1).size()));
        if(pairs>d.budgets().maximumPairVisits()||transitions>d.budgets().maximumTransitions())throw new CorridorResourceLimitException("Original declared solver work cap");
        var cleanup=GeometryCleanupConfig.disabled();
        var ripple=new UnsupportedRippleEvaluator().evaluate(d.track(),d.tube(),d.sourcePixel(),cleanup.rippleScaleMeters(),false);
        var gates=rippleEndpointGates(d.profiles().size(),d.approaches());
        preparedCounters.solves++;
        var solution=solveExact(d.track(),d.profiles,d.states(),d.sourcePixel(),d.junctions(),d.scales(),d.tube(),d.approaches(),ripple,gates,0);
        var result=finishOptimization(d.track(),d.profiles,d.sourcePixel(),d.junctions(),d.scales(),d.tube(),cleanup,d.approaches(),ripple,gates,0,solution);
        preparedCounters.retained=result.profileCostEvaluations();preparedCounters.outcome=result.disposition().name();return result;
        }catch(java.util.concurrent.CancellationException cancelled){preparedCounters.retained=hardGeometryWork.profileCostEvaluations();preparedCounters.outcome="CANCELLED";throw cancelled;}
        catch(CorridorResourceLimitException limited){preparedCounters.outcome="RESOURCE_LIMIT";throw limited;}
        catch(ArithmeticException overflow){preparedCounters.outcome="RESOURCE_LIMIT";throw new CorridorResourceLimitException("Prepared table/work estimate overflow");}
    }

    static OriginalDomain withBudgets(OriginalDomain d,org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets budgets){
        return new OriginalDomain(d.track(),d.profiles,d.sourcePixel(),d.junctions(),d.scales(),d.tube(),d.approaches(),d.states(),budgets,d.context,d.token,d.generationWork);
    }

    private void beginInference(OriginalDomain d){
        preparedCounters.reset();preparedCounters.original=d.generationWork.originalStates();preparedCounters.rows=d.generationWork.originalRows();
        preparedCounters.copied=d.generationWork.copiedProfileRows();preparedCounters.outcome="INFERENCE_PREPARING";
        hardGeometryWork=new HardGeometryWork(HardGeometryOutcome.DISABLED,0,0,0,0,0,0,0,0);
    }

    /** Explicitly selected prepared schedule; no original-domain fallback on failure. */
    public PreparedOptimization optimizePrepared(OriginalDomain d,CorridorWorkingSchedule.Preparation p){
        beginInference(d);
        try{p.requireBound(d,hardGeometryTransform);}catch(IllegalArgumentException corrupt){preparedCounters.outcome="BINDING_REFUSED";throw corrupt;}
        if(!p.admitted())throw new IllegalArgumentException("Prepared correspondence was not admitted: "+p.status());
        if(hardGeometryTransform==null)throw new IllegalArgumentException("Prepared correspondence requires factual metric hard geometry guard");
        long spent=Math.addExact(p.contactTests(),p.pairTests());
        if(spent>=d.budgets().maximumPairVisits()){preparedCounters.outcome="RESOURCE_LIMIT";throw new CorridorResourceLimitException("Prepared admission exhausted pair work");}
        var remaining=new org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets(d.budgets().maximumStatesPerProfile(),
            d.budgets().maximumPairVisits()-spent,d.budgets().maximumTransitions(),d.budgets().maximumRawAlternatives(),d.budgets().maximumDistinctAlternatives());
        return optimizePrepared(d.track(),d.profiles,d.sourcePixel(),d.junctions(),d.scales(),d.tube(),d.approaches(),p.schedule(),d.states(),p.domain(),remaining);
    }

    /**
     * Optimizes one corridor track without endpoint constraints.
     *
     * @param track selected corridor identity
     * @param profiles profile-aligned corridor evidence
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @return optimized geometry and decomposed costs
     */
    public OptimizationResult optimize(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        double sourcePixelSizePx
    ) {
        return optimize(track, profiles, sourcePixelSizePx, JunctionContext.empty(), Map.of());
    }

    /**
     * Optimizes one corridor track with endpoint and junction constraints.
     *
     * @param track selected corridor identity
     * @param profiles profile-aligned corridor evidence
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @param junctionContext endpoint and junction boundary conditions
     * @return optimized geometry and decomposed costs
     */
    public OptimizationResult optimize(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        double sourcePixelSizePx,
        JunctionContext junctionContext
    ) {
        return optimize(track, profiles, sourcePixelSizePx, junctionContext, Map.of());
    }

    /**
     * Optimizes one track with endpoint constraints and cross-scale localization evidence.
     *
     * @param track selected corridor identity
     * @param profiles fine L0 corridor profiles
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @param junctionContext endpoint and junction constraints
     * @param scaleEvidence cross-scale evidence keyed by profile index and fine band id
     * @return optimized fine-coordinate geometry
     */
    public OptimizationResult optimize(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        double sourcePixelSizePx,
        JunctionContext junctionContext,
        Map<String, BandScaleEvidence> scaleEvidence
    ) {
        LongitudinalCorridorTube tube = new CorridorTubeBuilder().build(
            track, profiles, sourcePixelSizePx, scaleEvidence);
        return optimize(track, profiles, sourcePixelSizePx, junctionContext, scaleEvidence, tube);
    }

    /**
     * Optimizes one track against an already built longitudinal corridor tube.
     *
     * @param track selected corridor identity
     * @param profiles fine L0 corridor profiles
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @param junctionContext endpoint and junction constraints
     * @param scaleEvidence cross-scale evidence keyed by profile index and fine band id
     * @param tube profile-aligned robust longitudinal reference for this track
     * @return optimized fine-coordinate geometry
     */
    public OptimizationResult optimize(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        double sourcePixelSizePx,
        JunctionContext junctionContext,
        Map<String, BandScaleEvidence> scaleEvidence,
        LongitudinalCorridorTube tube
    ) {
        return optimize(track, profiles, sourcePixelSizePx, junctionContext, scaleEvidence, tube,
            GeometryCleanupConfig.disabled());
    }

    /**
     * Optimizes one track with configured unsupported-ripple regularization in the same exact DP.
     *
     * @param track selected corridor identity
     * @param profiles fine L0 corridor profiles
     * @param sourcePixelSizePx source heatmap pixel size in sampled-raster pixels
     * @param junctionContext endpoint and junction constraints
     * @param scaleEvidence cross-scale evidence keyed by profile index and fine band id
     * @param tube profile-aligned robust longitudinal reference
     * @param cleanupConfig slide-time cleanup settings controlling ripple costs
     * @return optimized fine-coordinate geometry
     */
    public OptimizationResult optimize(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        double sourcePixelSizePx,
        JunctionContext junctionContext,
        Map<String, BandScaleEvidence> scaleEvidence,
        LongitudinalCorridorTube tube,
        GeometryCleanupConfig cleanupConfig
    ) {
        if (profiles.isEmpty() || track.points().isEmpty()) {
            return OptimizationResult.empty();
        }
        if (tube.slices().size() != profiles.size()) {
            throw new IllegalArgumentException("Corridor tube must be profile aligned");
        }
        EndpointApproachModel endpointApproaches = new EndpointApproachBuilder().build(
            track, profiles, tube, junctionContext, scaleEvidence);
        double[] rippleEndpointGates = rippleEndpointGates(profiles.size(), endpointApproaches);
        double sourcePixel = validSourcePixel(sourcePixelSizePx);
        double rippleStrength = cleanupConfig.isDisabled() ? 0.0 : cleanupConfig.rippleStrength();
        List<UnsupportedRippleEvaluator.RippleSupport> rippleSupport = new UnsupportedRippleEvaluator().evaluate(
            track, tube, sourcePixel, cleanupConfig.rippleScaleMeters(), rippleStrength > 0.0);
        List<List<Double>> allowed = new ArrayList<>(profiles.size());
        for (int i = 0; i < profiles.size(); i++) {
            allowed.add(allowedOffsets(track, profiles, i, sourcePixel, junctionContext, scaleEvidence, tube.at(i),
                endpointApproaches));
        }
        ExactSolution exactSolution = solveExact(track, profiles, allowed, sourcePixel, junctionContext,
            scaleEvidence, tube, endpointApproaches, rippleSupport, rippleEndpointGates, rippleStrength);
        return finishOptimization(track,profiles,sourcePixel,junctionContext,scaleEvidence,tube,cleanupConfig,
            endpointApproaches,rippleSupport,rippleEndpointGates,rippleStrength,exactSolution);
    }

    /** Prepared-domain test seam. All evidence is constructed on original rows before retention. */
    OptimizationResult optimizePrepared(CorridorTrack track,List<CorridorProfile> profiles,double sourcePixel,
        CorridorWorkingSchedule schedule,List<List<Double>> allowed) {
        var tube=new CorridorTubeBuilder().build(track,profiles,sourcePixel,Map.of());
        var approaches=new EndpointApproachBuilder().build(track,profiles,tube,JunctionContext.empty(),Map.of());
        var domain=CorridorWorkingSchedule.BridgeDomain.intersections(schedule,track,profiles,allowed);
        return optimizePrepared(track,profiles,sourcePixel,JunctionContext.empty(),Map.of(),tube,
            approaches,schedule,allowed,domain).geometry();
    }

    /**
     * Runs the same pair recurrence on a caller-certified prepared domain. This is an explicit
     * experiment seam, not live eligibility: native/source authority must certify the domain first.
     * Original observations, fits, states and endpoint dependencies are never reconstructed here.
     */
    PreparedOptimization optimizePrepared(CorridorTrack track,List<CorridorProfile> profiles,
        double sourcePixel,JunctionContext junctions,Map<String,BandScaleEvidence> scale,
        LongitudinalCorridorTube tube,EndpointApproachModel approaches,CorridorWorkingSchedule schedule,
        List<List<Double>> allowed,CorridorWorkingSchedule.BridgeDomain domain) {
        preparedCounters.reset();preparedCounters.outcome="GEOMETRY_ONLY_PREPARING";
        return optimizePrepared(track,profiles,sourcePixel,junctions,scale,tube,approaches,schedule,allowed,domain,
            org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets.defaults());
    }
    private PreparedOptimization optimizePrepared(CorridorTrack track,List<CorridorProfile> profiles,
        double sourcePixel,JunctionContext junctions,Map<String,BandScaleEvidence> scale,
        LongitudinalCorridorTube tube,EndpointApproachModel approaches,CorridorWorkingSchedule schedule,
        List<List<Double>> allowed,CorridorWorkingSchedule.BridgeDomain domain,
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets limits) {
        try{return optimizePreparedInternal(track,profiles,sourcePixel,junctions,scale,tube,approaches,schedule,allowed,domain,limits);}
        catch(java.util.concurrent.CancellationException cancelled){preparedCounters.outcome="CANCELLED";throw cancelled;}
        catch(CorridorResourceLimitException limited){preparedCounters.outcome="RESOURCE_LIMIT";throw limited;}
        catch(ArithmeticException overflow){preparedCounters.outcome="RESOURCE_LIMIT";throw new CorridorResourceLimitException("Prepared table/work estimate overflow");}
    }

    private PreparedOptimization optimizePreparedInternal(CorridorTrack track,List<CorridorProfile> profiles,
        double sourcePixel,JunctionContext junctions,Map<String,BandScaleEvidence> scale,
        LongitudinalCorridorTube tube,EndpointApproachModel approaches,CorridorWorkingSchedule schedule,
        List<List<Double>> allowed,CorridorWorkingSchedule.BridgeDomain domain,
        org.openstreetmap.josm.plugins.wayheatmaptracer.model.TraceBudgets limits) {
        long originalInventory=allowed.stream().mapToLong(List::size).sum();
        preparedCounters.retained=0;preparedCounters.bridge=0;preparedCounters.bytes=-1;preparedCounters.outcome="PREPARING";
        if(profiles.size()!=schedule.originalCount()||allowed.size()!=profiles.size()
            ||tube.slices().size()!=profiles.size())throw new IllegalArgumentException("Original domain size differs");
        sourcePixel=validSourcePixel(sourcePixel);
        var geometryProfiles=schedule.retain(profiles);
        var geometryTrack=schedule.geometryTrack(track);
        var geometryTube=schedule.geometryTube(tube);
        var geometryScale=schedule.geometryScale(track,scale);
        var geometryJunctions=schedule.geometryJunctions(junctions);
        var geometryApproaches=schedule.geometryApproaches(approaches);
        domain.requireBinding(schedule,allowed);
        long points=0,edges=0,transitions=0;
        if(allowed.stream().anyMatch(row->row.isEmpty()||row.size()>limits.maximumStatesPerProfile()))
            throw new CorridorResourceLimitException("Prepared original state cap");
        for(int i=0;i<schedule.workingCount();i++){
            int size=allowed.get(schedule.originalRow(i)).size();
            if(size<=0||size>limits.maximumStatesPerProfile())throw new CorridorResourceLimitException("Prepared original state cap");
            points=Math.addExact(points,size);
            if(i>0)edges=Math.addExact(edges,Math.multiplyExact((long)size,allowed.get(schedule.originalRow(i-1)).size()));
            if(i>1)transitions=Math.addExact(transitions,Math.multiplyExact((long)size,
                Math.multiplyExact((long)allowed.get(schedule.originalRow(i-1)).size(),allowed.get(schedule.originalRow(i-2)).size())));
        }
        transitions=Math.addExact(transitions,Math.multiplyExact((long)allowed.get(schedule.originalRow(0)).size(),allowed.get(schedule.originalRow(1)).size()));
        long pairBytes=Math.multiplyExact((long)allowed.get(schedule.leftOriginalRow()).size(),allowed.get(schedule.rightOriginalRow()).size());
        long reserved=estimatePreparedTableBytes(originalInventory,points,edges,profiles.size(),schedule.workingCount(),pairBytes,domain.edges().size(),schedule.consumedRows().size());
        preparedCounters.bytes=reserved;
        if(reserved>128L*1024*1024||edges>limits.maximumPairVisits()||transitions>limits.maximumTransitions()){
            preparedCounters.outcome="RESOURCE_LIMIT";throw new CorridorResourceLimitException("Prepared table/work cap");
        }
        preparedCounters.bytes=reserved;
        var cleanup=GeometryCleanupConfig.disabled();
        var ripple=new UnsupportedRippleEvaluator().evaluate(track,tube,sourcePixel,cleanup.rippleScaleMeters(),false);
        var gates=rippleEndpointGates(profiles.size(),approaches);
        var tables=new ArrayList<List<OffsetState>>();
        for(int row:schedule.originalRows()){
            hardGeometryCancellation.checkpoint();
            var states=new ArrayList<OffsetState>();
            for(double offset:allowed.get(row)){
                double cost=profileCost(track,profiles,row,offset,sourcePixel,junctions,scale,tube,approaches,ripple.get(row),0,gates[row]);
                preparedCounters.retained++;
                requireFiniteCost(cost,row);states.add(new OffsetState(offset,pointFor(profiles.get(row),offset),cost));
            }
            if(states.isEmpty())throw new IllegalArgumentException("Empty original state array");
            tables.add(List.copyOf(states));
        }
        int left=schedule.leftOriginalRow(),right=schedule.rightOriginalRow();
        double[][] extra=new double[allowed.get(left).size()][allowed.get(right).size()];
        long bridgeCosts=0;var bridgeRows=new ArrayList<BridgeUnaryRow>();
        for(var edge:domain.edges()){
            hardGeometryCancellation.checkpoint();
            double cost=0;
            for(int k=0;k<edge.offsets().size();k++){
                int row=left+1+k;
                double offset=edge.offsets().get(k);
                var components=dataCostComponents(track,profiles.get(row),row,offset,sourcePixel,scale,tube.at(row),
                    heatmapWeight(approaches,row),ripple.get(row),0,gates[row]);
                double endpoint=constraintCost(row,offset,junctions,approaches,sourcePixel);
                double unary=components.total()+endpoint;cost+=unary;
                bridgeRows.add(new BridgeUnaryRow(edge.leftState(),edge.rightState(),row,offset,components.total(),
                    components.plateauCenterCost(),components.coarsePriorCost(),components.tubeCenterCost(),endpoint,unary));
                bridgeCosts++;requireFiniteCost(cost,row);
                preparedCounters.bridge++;
            }
            extra[edge.leftState()][edge.rightState()]=cost;
        }
        var prepared=new PreparedTables(List.copyOf(tables),schedule.rightWorkingRow(),domain,extra);
        var geometryRipple=schedule.retain(ripple);
        double[] geometryGates=new double[schedule.workingCount()];
        for(int i=0;i<geometryGates.length;i++)geometryGates[i]=gates[schedule.originalRow(i)];
        HardGeometryCounters work=hardGeometryTransform==null?null:new HardGeometryCounters();
        ExactSolution solution;
        try{
            preparedCounters.solves++;
            solution=solveExactInternal(geometryTrack,geometryProfiles,schedule.retain(allowed),sourcePixel,
                geometryJunctions,geometryScale,geometryTube,geometryApproaches,geometryRipple,geometryGates,0,work,prepared);
        }catch(java.util.concurrent.CancellationException cancelled){
            preparedCounters.outcome="CANCELLED";
            if(work!=null){work.outcome=HardGeometryOutcome.CANCELLED;throw new HardGeometryCancelledException(work.snapshot(),cancelled);}
            throw cancelled;
        }finally{if(work!=null)hardGeometryWork=work.snapshot();}
        var result=finishOptimization(geometryTrack,geometryProfiles,sourcePixel,geometryJunctions,geometryScale,
            geometryTube,cleanup,geometryApproaches,geometryRipple,geometryGates,0,solution);
        List<Double> consumed=List.of();double bridgeCost=0;
        if(!result.offsetsPx().isEmpty()){
            int l=allowed.get(left).indexOf(result.offsetsPx().get(schedule.workingRow(left)));
            int r=allowed.get(right).indexOf(result.offsetsPx().get(schedule.workingRow(right)));
            consumed=domain.edge(l,r).offsets();bridgeCost=extra[l][r];
            result=new OptimizationResult(result.offsetsPx(),result.screenPoints(),result.totalCost()+bridgeCost,
                result.costs(),result.inCorridorFraction(),result.longitudinalStability(),result.quality(),
                result.endpointApproaches(),result.maximumOffsetStates(),result.maximumPairStates(),
                result.transitionEvaluations(),result.profileCostEvaluations(),result.pointTableEntries(),
                result.adjacentGeometryEntries(),result.retainedPairStateAllocations());
        }
        preparedCounters.outcome=result.disposition().name();
        return new PreparedOptimization(result,schedule,consumed,bridgeCost,bridgeCosts,approaches,bridgeRows);
    }

    void startTrackPreparation(){preparedCounters.reset();hardGeometryWork=new HardGeometryWork(HardGeometryOutcome.DISABLED,0,0,0,0,0,0,0,0);}
    void preparationFailed(String outcome){preparedCounters.outcome=outcome;}

    /** Scoped prepared/state/factor/metric-table estimate, excluding PairState ancestry and ordinary
     * adjacent-geometry/transient/native-field allocations. No heap reservation or owner release is implied. */
    static long estimatePreparedTableBytes(long originalStates,long points,long edges,long originalRows,
        long retainedRows,long bridgePairs,long admittedEdges,long consumedRows){
        for(long value:new long[]{originalStates,points,edges,originalRows,retainedRows,bridgePairs,admittedEdges,consumedRows})
            if(value<0)throw new IllegalArgumentException("Prepared table counts must be nonnegative");
        long bytes=Math.addExact(Math.multiplyExact(Math.addExact(points,edges),64L),Math.multiplyExact(retainedRows,128L));
        bytes=Math.addExact(bytes,Math.addExact(Math.multiplyExact(points,80L),Math.multiplyExact(originalStates,40L)));
        bytes=Math.addExact(bytes,Math.multiplyExact(originalRows,640L));
        bytes=Math.addExact(bytes,Math.multiplyExact(bridgePairs,24L));
        return Math.addExact(bytes,Math.multiplyExact(admittedEdges,Math.addExact(64L,Math.multiplyExact(consumedRows,128L))));
    }

    /** Separate original observation identity; the shortened geometry is never cleanup-ready. */
    public record PreparedOptimization(OptimizationResult geometry,CorridorWorkingSchedule schedule,
        List<Double> consumedOffsets,double bridgeUnaryCost,long bridgeUnaryEvaluations,
        EndpointApproachModel originalEndpointApproaches,List<BridgeUnaryRow> bridgeUnaryRows){
        public PreparedOptimization{consumedOffsets=List.copyOf(consumedOffsets);bridgeUnaryRows=List.copyOf(bridgeUnaryRows);}
        public String cleanupCorrespondenceStatus(){return "UNSUPPORTED_CORRESPONDENCE";}
        /** Every admitted consumed hit is in its measured core; retain the original observation population. */
        public double observationInCorridorFraction(){
            return geometry.offsetsPx().isEmpty()?0:(geometry.inCorridorFraction()*schedule.workingCount()+consumedOffsets.size())/schedule.originalCount();
        }
        /** Cost rows retain an explicit original-observation identity separate from output index. */
        public List<OriginalCostRow> originalCostRows(){
            return geometry.costs().stream().map(c->new OriginalCostRow(schedule.originalRow(c.profileIndex()),c)).toList();
        }
    }
    public record OriginalCostRow(int originalRow,CostRow retainedGeometryCost){ }
    /** All original-row bridge factors, including nonwinning admitted edges, separately from output geometry. */
    public record BridgeUnaryRow(int leftState,int rightState,int originalRow,double offsetPx,double dataCost,
        double plateauCenterCost,double coarsePriorCost,double tubeCenterCost,double endpointCost,double totalCost){ }

    private record PreparedTables(List<List<OffsetState>> states,int bridgeRight,
        CorridorWorkingSchedule.BridgeDomain domain,double[][] costs){
        boolean allowed(int row,int left,int right){return row!=bridgeRight||domain.edge(left,right)!=null;}
        double extra(int row,int left,int right){return row==bridgeRight?costs[left][right]:0;}
    }

    private OptimizationResult finishOptimization(CorridorTrack track,List<CorridorProfile> profiles,
        double sourcePixel,JunctionContext junctionContext,Map<String,BandScaleEvidence> scaleEvidence,
        LongitudinalCorridorTube tube,GeometryCleanupConfig cleanupConfig,EndpointApproachModel endpointApproaches,
        List<UnsupportedRippleEvaluator.RippleSupport> rippleSupport,double[] rippleEndpointGates,
        double rippleStrength,ExactSolution exactSolution) {
        List<Double> optimizedOffsets = exactSolution.offsetsPx();
        if (optimizedOffsets.isEmpty()) {
            return new OptimizationResult(List.of(), List.of(), Double.POSITIVE_INFINITY, List.of(), 0, 0,
                CorridorQuality.empty(), endpointApproaches, exactSolution.maximumOffsetStates(),
                exactSolution.maximumPairStates(), exactSolution.transitionEvaluations(),
                exactSolution.profileCostEvaluations(), exactSolution.pointTableEntries(),
                exactSolution.adjacentGeometryEntries(), exactSolution.retainedPairStateAllocations());
        }
        List<Point2D.Double> points = new ArrayList<>(profiles.size());
        List<CostRow> costs = new ArrayList<>(profiles.size());
        int inCorridor = 0;
        double accelerationEnergy = 0.0;
        for (int i = 0; i < profiles.size(); i++) {
            double offset = optimizedOffsets.get(i);
            CorridorProfile profile = profiles.get(i);
            points.add(new Point2D.Double(
                profile.source().anchorScreen().x + profile.source().normalScreen().x * offset,
                profile.source().anchorScreen().y + profile.source().normalScreen().y * offset
            ));
            CorridorBand band = bandAt(track, i);
            boolean contained = band == null || (offset >= band.shoulderMinPx() - 1e-9 && offset <= band.shoulderMaxPx() + 1e-9);
            boolean insideCore = band != null && offset >= band.coreMinPx() - 1e-9 && offset <= band.coreMaxPx() + 1e-9;
            if (contained) {
                inCorridor++;
            }
            DataCost dataComponents = dataCostComponents(track, profile, i, offset, sourcePixel, scaleEvidence,
                tube.at(i), heatmapWeight(endpointApproaches, i), rippleSupport.get(i), rippleStrength,
                rippleEndpointGates[i]);
            double data = dataComponents.total();
            double baseContinuityWeight = i == 0 ? 0.0 : continuityWeight(track, i);
            double continuityWeight = effectiveContinuityWeight(
                baseContinuityWeight, rippleSupport.get(i), rippleStrength, rippleEndpointGates[i]);
            double continuity = i == 0 ? 0.0 : continuityWeight
                * square((offset - optimizedOffsets.get(i - 1)) / profileSpacing(profiles, i, sourcePixel));
            double baseAccelerationWeight = i < 2 ? 0.0 : accelerationWeight(track, i, tube);
            double accelerationWeight = effectiveAccelerationWeight(
                baseAccelerationWeight, rippleSupport.get(i), rippleStrength, rippleEndpointGates[i]);
            double acceleration = i < 2 ? 0.0 : accelerationWeight
                * geometricCurvatureCost(profiles, optimizedOffsets, tube, i, sourcePixel);
            double absoluteTurn = i < 2 ? 0.0 : absoluteShortWaveTurnCost(profiles,
                optimizedOffsets, i, sourcePixel, rippleSupport.get(i), rippleStrength,
                rippleEndpointGates[i]);
            accelerationEnergy += acceleration;
            double endpoint = constraintCost(i, offset, junctionContext, endpointApproaches, sourcePixel);
            double spacing = i == 0 && profiles.size() > 1
                ? profileSpacing(profiles, 1, sourcePixel)
                : (i == 0 ? sourcePixel : profileSpacing(profiles, i, sourcePixel));
            double rippleAdditional = dataComponents.rippleAdditionalCost()
                + Math.max(0.0, continuity - (i == 0 ? 0.0 : baseContinuityWeight
                    * square((offset - optimizedOffsets.get(i - 1)) / profileSpacing(profiles, i, sourcePixel))))
                + Math.max(0.0, acceleration - (i < 2 ? 0.0 : baseAccelerationWeight
                    * geometricCurvatureCost(profiles, optimizedOffsets, tube, i, sourcePixel))) + absoluteTurn;
            costs.add(new CostRow(i, offset, spacing, data, continuity, acceleration, absoluteTurn,
                dataComponents.plateauCenterCost(), dataComponents.coarsePriorCost(),
                dataComponents.tubeCenterCost(), endpoint,
                rippleStrength > 0.0 ? cleanupConfig.rippleScaleMeters() : 0.0, rippleStrength,
                rippleSupport.get(i).support(),
                rippleSupport.get(i).supportedTurnWeight(), rippleSupport.get(i).shortScaleExposure(),
                rippleSupport.get(i).residualAmplitudeSourcePixels(),
                rippleSupport.get(i).maximumResidualSourcePixels(), rippleSupport.get(i).directCoverage(),
                rippleSupport.get(i).trendCenterOffsetPx(), rippleSupport.get(i).trendSlopePxPerMeter(),
                rippleSupport.get(i).trendUncertaintyPx(), rippleSupport.get(i).trendAuthorization(),
                rippleSupport.get(i).unsupportedWeight(), rippleSupport.get(i).reversalSpacingMeters(),
                rippleSupport.get(i).reason(), rippleAdditional,
                data + continuity + acceleration + absoluteTurn + endpoint, insideCore, contained));
        }
        double inCorridorFraction = (double) inCorridor / profiles.size();
        double stability = 1.0 / (1.0 + accelerationEnergy / Math.max(1, profiles.size() - 2));
        double totalCost = costs.stream().mapToDouble(CostRow::weightedTotal).sum();
        CorridorQuality quality = new CorridorQualityCalculator().calculate(track, profiles, tube,
            optimizedOffsets, points, sourcePixel, endpointApproaches);
        return new OptimizationResult(optimizedOffsets, points, totalCost, costs, inCorridorFraction, stability,
            quality, endpointApproaches, exactSolution.maximumOffsetStates(), exactSolution.maximumPairStates(),
            exactSolution.transitionEvaluations(), exactSolution.profileCostEvaluations(),
            exactSolution.pointTableEntries(), exactSolution.adjacentGeometryEntries(),
            exactSolution.retainedPairStateAllocations());
    }

    private ExactSolution solveExact(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        List<List<Double>> allowed,
        double sourcePixel,
        JunctionContext junctionContext,
        Map<String, BandScaleEvidence> scaleEvidence,
        LongitudinalCorridorTube tube,
        EndpointApproachModel endpointApproaches,
        List<UnsupportedRippleEvaluator.RippleSupport> rippleSupport,
        double[] rippleEndpointGates,
        double rippleStrength
    ) {
        HardGeometryCounters work = hardGeometryTransform == null ? null : new HardGeometryCounters();
        try {
            return solveExactInternal(track, profiles, allowed, sourcePixel, junctionContext, scaleEvidence,
                tube, endpointApproaches, rippleSupport, rippleEndpointGates, rippleStrength, work);
        } catch (java.util.concurrent.CancellationException cancelled) {
            if (work == null) throw cancelled;
            work.outcome = HardGeometryOutcome.CANCELLED;
            throw new HardGeometryCancelledException(work.snapshot(), cancelled);
        } finally {
            if (work != null) hardGeometryWork = work.snapshot();
        }
    }

    private ExactSolution solveExactInternal(CorridorTrack track, List<CorridorProfile> profiles,
        List<List<Double>> allowed, double sourcePixel, JunctionContext junctionContext,
        Map<String, BandScaleEvidence> scaleEvidence, LongitudinalCorridorTube tube,
        EndpointApproachModel endpointApproaches,
        List<UnsupportedRippleEvaluator.RippleSupport> rippleSupport,
        double[] rippleEndpointGates, double rippleStrength, HardGeometryCounters work) {
        return solveExactInternal(track,profiles,allowed,sourcePixel,junctionContext,scaleEvidence,tube,
            endpointApproaches,rippleSupport,rippleEndpointGates,rippleStrength,work,null);
    }

    private ExactSolution solveExactInternal(CorridorTrack track,List<CorridorProfile> profiles,
        List<List<Double>> allowed,double sourcePixel,JunctionContext junctionContext,
        Map<String,BandScaleEvidence> scaleEvidence,LongitudinalCorridorTube tube,
        EndpointApproachModel endpointApproaches,List<UnsupportedRippleEvaluator.RippleSupport> rippleSupport,
        double[] rippleEndpointGates,double rippleStrength,HardGeometryCounters work,PreparedTables prepared) {
        List<List<OffsetState>> stateTables = prepared==null?buildStateTables(track, profiles, allowed, sourcePixel,
            junctionContext, scaleEvidence, tube, endpointApproaches, rippleSupport,
            rippleEndpointGates, rippleStrength):prepared.states();
        long profileCostEvaluations = stateTables.stream().mapToLong(List::size).sum();
        long pointTableEntries = profileCostEvaluations;
        MetricEdge[][][] metricEdges = null;
        if (work != null) {
            work.profileCosts = profileCostEvaluations;
            metricEdges = metricEdgeCache(stateTables, work);
        }
        if (profiles.size() == 1) {
            OffsetState best = stateTables.get(0).stream().min(Comparator
                .comparingDouble(OffsetState::profileCost)
                .thenComparingDouble(OffsetState::offset)).orElseThrow();
            int stateCount = stateTables.get(0).size();
            if (work != null) work.outcome = HardGeometryOutcome.ROUTE;
            return new ExactSolution(List.of(best.offset()), stateCount, stateCount, stateCount,
                profileCostEvaluations, pointTableEntries, 0L, 0L);
        }

        PairState[][] states = new PairState[stateTables.get(0).size()][stateTables.get(1).size()];
        long transitionEvaluations = 0L;
        long adjacentGeometryEntries = 0L;
        long retainedPairStateAllocations = 0L;
        int maximumOffsetStates = allowed.stream().mapToInt(List::size).max().orElse(0);
        double spacing = profileSpacing(profiles, 1, sourcePixel);
        double spacingSourcePixels = spacing / sourcePixel;
        AdjacentGeometry firstGeometry = adjacentGeometry(
            stateTables.get(0), stateTables.get(1), profiles, tube, track, 1, spacing,
            rippleSupport.get(1), rippleStrength, rippleEndpointGates[1]);
        adjacentGeometryEntries += firstGeometry.entryCount();
        for (int firstIndex = 0; firstIndex < stateTables.get(0).size(); firstIndex++) {
            OffsetState first = stateTables.get(0).get(firstIndex);
            for (int secondIndex = 0; secondIndex < stateTables.get(1).size(); secondIndex++) {
                OffsetState second = stateTables.get(1).get(secondIndex);
                transitionEvaluations++;
                if (work != null) work.attempted++;
                if(prepared!=null&&!prepared.allowed(1,firstIndex,secondIndex))continue;
                double cost = first.profileCost() + second.profileCost()
                    + firstGeometry.continuityCost(firstIndex, secondIndex);
                if(prepared!=null)cost+=prepared.extra(1,firstIndex,secondIndex);
                requireFiniteCost(cost, 1);
                if (work != null) work.scored++;
                states[firstIndex][secondIndex] = new PairState(1, first.offset(), second.offset(),
                    firstGeometry.heading(firstIndex, secondIndex), firstGeometry.referenceHeading(),
                    spacingSourcePixels, cost, null);
                retainedPairStateAllocations++;
            }
        }
        int maximumPairStates = countStates(states);
        if(prepared!=null&&maximumPairStates==0){if(work!=null)work.outcome=HardGeometryOutcome.NO_LEGAL_PATH;return new ExactSolution(List.of(),maximumOffsetStates,0,transitionEvaluations,profileCostEvaluations,pointTableEntries,adjacentGeometryEntries,retainedPairStateAllocations);}

        for (int profileIndex = 2; profileIndex < profiles.size(); profileIndex++) {
            if (work != null) hardGeometryCancellation.checkpoint();
            List<OffsetState> previousOffsets = stateTables.get(profileIndex - 1);
            List<OffsetState> currentOffsets = stateTables.get(profileIndex);
            PairState[][] next = new PairState[previousOffsets.size()][currentOffsets.size()];
            spacing = profileSpacing(profiles, profileIndex, sourcePixel);
            spacingSourcePixels = spacing / sourcePixel;
            AdjacentGeometry geometry = adjacentGeometry(previousOffsets, currentOffsets,
                profiles, tube, track, profileIndex, spacing, rippleSupport.get(profileIndex), rippleStrength,
                rippleEndpointGates[profileIndex]);
            adjacentGeometryEntries += geometry.entryCount();
            double profileAccelerationWeight = effectiveAccelerationWeight(
                accelerationWeight(track, profileIndex, tube), rippleSupport.get(profileIndex), rippleStrength,
                rippleEndpointGates[profileIndex]);
            for (int beforeIndex = 0; beforeIndex < states.length; beforeIndex++) {
                for (int previousIndex = 0; previousIndex < states[beforeIndex].length; previousIndex++) {
                    PairState previous = states[beforeIndex][previousIndex];
                    if (previous == null) {
                        continue;
                    }
                    for (int currentIndex = 0; currentIndex < currentOffsets.size(); currentIndex++) {
                        OffsetState current = currentOffsets.get(currentIndex);
                        transitionEvaluations++;
                        if(prepared!=null&&!prepared.allowed(profileIndex,previousIndex,currentIndex)){if(work!=null)work.attempted++;continue;}
                        if (work != null) {
                            work.attempted++;
                            if ((work.attempted & 1023L) == 0L) hardGeometryCancellation.checkpoint();
                            work.tested++;
                            MetricEdge incoming = metricEdges[profileIndex - 1][beforeIndex][previousIndex];
                            MetricEdge outgoing = metricEdges[profileIndex][previousIndex][currentIndex];
                            if (FinalGeometryEvaluator.hardBacktrackEdges(incoming.east(), incoming.north(),
                                    incoming.length(), outgoing.east(), outgoing.north(), outgoing.length())) {
                                work.rejected++;
                                continue;
                            }
                        }
                        double candidateHeading = geometry.heading(previousIndex, currentIndex);
                        double acceleration = profileAccelerationWeight
                            * geometricCurvatureCost(candidateHeading, geometry.referenceHeading(),
                                previous, spacingSourcePixels);
                        double absoluteTurn = absoluteShortWaveTurnCost(candidateHeading, previous,
                            spacingSourcePixels, rippleSupport.get(profileIndex), rippleStrength,
                            rippleEndpointGates[profileIndex]);
                        double cost = previous.cost()
                            + current.profileCost()
                            + geometry.continuityCost(previousIndex, currentIndex) + acceleration + absoluteTurn;
                        if(prepared!=null)cost+=prepared.extra(profileIndex,previousIndex,currentIndex);
                        requireFiniteCost(cost, profileIndex);
                        if (work != null) work.scored++;
                        PairState existing = next[previousIndex][currentIndex];
                        if (existing == null || cost < existing.cost()) {
                            next[previousIndex][currentIndex] = new PairState(profileIndex,
                                previous.currentOffset(), current.offset(), candidateHeading,
                                geometry.referenceHeading(), spacingSourcePixels, cost, previous);
                            retainedPairStateAllocations++;
                        }
                    }
                }
            }
            states = next;
            maximumPairStates = Math.max(maximumPairStates, countStates(states));
            if ((work != null||prepared!=null) && countStates(states) == 0) {
                if(work!=null)work.outcome = HardGeometryOutcome.NO_LEGAL_PATH;
                return new ExactSolution(List.of(), maximumOffsetStates, maximumPairStates,
                    transitionEvaluations, profileCostEvaluations, pointTableEntries,
                    adjacentGeometryEntries, retainedPairStateAllocations);
            }
        }

        PairState best = bestState(states);
        if (work != null) work.outcome = HardGeometryOutcome.ROUTE;
        Double[] result = new Double[profiles.size()];
        PairState cursor = best;
        while (cursor != null) {
            result[cursor.profileIndex()] = cursor.currentOffset();
            result[cursor.profileIndex() - 1] = cursor.previousOffset();
            cursor = cursor.predecessor();
        }
        return new ExactSolution(List.of(result), maximumOffsetStates, maximumPairStates, transitionEvaluations,
            profileCostEvaluations, pointTableEntries, adjacentGeometryEntries, retainedPairStateAllocations);
    }

    private MetricEdge[][][] metricEdgeCache(List<List<OffsetState>> states, HardGeometryCounters work) {
        long points = states.stream().mapToLong(List::size).sum(), edges = 0;
        for (int i = 1; i < states.size(); i++) edges = Math.addExact(edges,
            Math.multiplyExact((long) states.get(i - 1).size(), states.get(i).size()));
        long reserved = Math.addExact(Math.multiplyExact(Math.addExact(points, edges), 64L),
            Math.multiplyExact(states.size(), 128L));
        if (reserved > 128L * 1024 * 1024) {
            work.outcome = HardGeometryOutcome.RESOURCE_LIMIT;
            throw new HardGeometryResourceLimitException(work.snapshot());
        }
        work.reserved = reserved;
        MetricPoint[][] metric = new MetricPoint[states.size()][];
        for (int i = 0; i < states.size(); i++) {
            hardGeometryCancellation.checkpoint();
            metric[i] = new MetricPoint[states.get(i).size()];
            for (int j = 0; j < metric[i].length; j++) {
                var point = states.get(i).get(j).point();
                metric[i][j] = hardGeometryTransform.pixelCenterToMetric(point.x, point.y);
                work.points++;
            }
        }
        MetricEdge[][][] result = new MetricEdge[states.size()][][];
        for (int i = 1; i < states.size(); i++) {
            hardGeometryCancellation.checkpoint();
            result[i] = new MetricEdge[metric[i - 1].length][metric[i].length];
            for (int a = 0; a < metric[i - 1].length; a++) {
                hardGeometryCancellation.checkpoint();
                for (int b = 0; b < metric[i].length; b++) {
                    double east = metric[i][b].xMeters() - metric[i - 1][a].xMeters();
                    double north = metric[i][b].yMeters() - metric[i - 1][a].yMeters();
                    result[i][a][b] = new MetricEdge(east, north, StrictMath.hypot(east, north));
                    work.edges++;
                }
            }
        }
        return result;
    }

    /** Typed experimental outcome; default solvers retain their historical path and work. */
    public enum HardGeometryOutcome { DISABLED, ROUTE, NO_LEGAL_PATH, CANCELLED, RESOURCE_LIMIT }
    public record HardGeometryWork(HardGeometryOutcome outcome, long profileCostEvaluations,
        long attemptedTransitions, long testedTriples, long rejectedTriples, long scoredTransitions,
        long metricPoints, long metricEdges, long metricCacheReservedBytes) { }
    public static final class HardGeometryCancelledException extends java.util.concurrent.CancellationException {
        private final HardGeometryWork work;
        HardGeometryCancelledException(HardGeometryWork work, Throwable cause) {
            super("Explicit hard-geometry experiment cancelled"); this.work = work; initCause(cause);
        }
        public HardGeometryWork work() { return work; }
    }
    public static final class HardGeometryResourceLimitException extends IllegalStateException {
        private final HardGeometryWork work;
        HardGeometryResourceLimitException(HardGeometryWork work) {
            super("Explicit hard-geometry metric cache exceeds 128 MiB"); this.work = work;
        }
        public HardGeometryWork work() { return work; }
    }
    private static final class HardGeometryCounters {
        HardGeometryOutcome outcome = HardGeometryOutcome.ROUTE;
        long profileCosts, attempted, tested, rejected, scored, points, edges, reserved;
        HardGeometryWork snapshot() { return new HardGeometryWork(outcome, profileCosts, attempted,
            tested, rejected, scored, points, edges, reserved); }
    }
    private record MetricEdge(double east, double north, double length) { }

    private List<List<OffsetState>> buildStateTables(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        List<List<Double>> allowed,
        double sourcePixel,
        JunctionContext junctionContext,
        Map<String, BandScaleEvidence> scaleEvidence,
        LongitudinalCorridorTube tube,
        EndpointApproachModel endpointApproaches,
        List<UnsupportedRippleEvaluator.RippleSupport> rippleSupport,
        double[] rippleEndpointGates,
        double rippleStrength
    ) {
        List<List<OffsetState>> tables = new ArrayList<>(profiles.size());
        for (int profileIndex = 0; profileIndex < profiles.size(); profileIndex++) {
            List<OffsetState> states = new ArrayList<>(allowed.get(profileIndex).size());
            for (double offset : allowed.get(profileIndex)) {
                double cost = profileCost(track, profiles, profileIndex, offset, sourcePixel,
                    junctionContext, scaleEvidence, tube, endpointApproaches,
                    rippleSupport.get(profileIndex), rippleStrength, rippleEndpointGates[profileIndex]);
                requireFiniteCost(cost, profileIndex);
                states.add(new OffsetState(offset, pointFor(profiles.get(profileIndex), offset), cost));
            }
            tables.add(List.copyOf(states));
        }
        return List.copyOf(tables);
    }

    private void requireFiniteCost(double cost, int profileIndex) {
        if (!Double.isFinite(cost)) {
            throw new IllegalStateException("Corridor optimizer produced a non-finite cost at profile "
                + profileIndex);
        }
    }

    private AdjacentGeometry adjacentGeometry(
        List<OffsetState> previous,
        List<OffsetState> current,
        List<CorridorProfile> profiles,
        LongitudinalCorridorTube tube,
        CorridorTrack track,
        int profileIndex,
        double spacing,
        UnsupportedRippleEvaluator.RippleSupport rippleSupport,
        double rippleStrength,
        double rippleEndpointGate
    ) {
        double[][] headings = new double[previous.size()][current.size()];
        double[][] continuityCosts = new double[previous.size()][current.size()];
        double continuityWeight = effectiveContinuityWeight(
            continuityWeight(track, profileIndex), rippleSupport, rippleStrength, rippleEndpointGate);
        for (int previousIndex = 0; previousIndex < previous.size(); previousIndex++) {
            for (int currentIndex = 0; currentIndex < current.size(); currentIndex++) {
                headings[previousIndex][currentIndex] = heading(
                    previous.get(previousIndex).point(), current.get(currentIndex).point());
                continuityCosts[previousIndex][currentIndex] = continuityWeight * square(
                    (current.get(currentIndex).offset() - previous.get(previousIndex).offset()) / spacing);
            }
        }
        return new AdjacentGeometry(headings, continuityCosts,
            tubeHeading(profiles, tube, profileIndex - 1, profileIndex));
    }

    private int countStates(PairState[][] states) {
        int count = 0;
        for (PairState[] row : states) {
            for (PairState state : row) {
                if (state != null) {
                    count++;
                }
            }
        }
        return count;
    }

    private PairState bestState(PairState[][] states) {
        PairState best = null;
        for (PairState[] row : states) {
            for (PairState state : row) {
                if (state != null && (best == null || PAIR_STATE_COMPARATOR.compare(state, best) < 0)) {
                    best = state;
                }
            }
        }
        if (best == null) {
            throw new IllegalStateException("Exact corridor optimizer retained no pair state");
        }
        return best;
    }

    private double profileCost(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        int profileIndex,
        double offset,
        double sourcePixel,
        JunctionContext junctionContext,
        Map<String, BandScaleEvidence> scaleEvidence,
        LongitudinalCorridorTube tube,
        EndpointApproachModel endpointApproaches,
        UnsupportedRippleEvaluator.RippleSupport rippleSupport,
        double rippleStrength,
        double rippleEndpointGate
    ) {
        return dataCost(track, profiles.get(profileIndex), profileIndex, offset, sourcePixel, scaleEvidence,
            tube.at(profileIndex), heatmapWeight(endpointApproaches, profileIndex), rippleSupport, rippleStrength,
            rippleEndpointGate)
            + constraintCost(profileIndex, offset, junctionContext, endpointApproaches, sourcePixel);
    }

    private double tubeHeading(
        List<CorridorProfile> profiles,
        LongitudinalCorridorTube tube,
        int fromIndex,
        int toIndex
    ) {
        return heading(pointFor(profiles.get(fromIndex), tube.at(fromIndex).centerOffsetPx()),
            pointFor(profiles.get(toIndex), tube.at(toIndex).centerOffsetPx()));
    }

    private List<Double> allowedOffsets(
        CorridorTrack track,
        List<CorridorProfile> profiles,
        int profileIndex,
        double sourcePixel,
        JunctionContext junctionContext,
        Map<String, BandScaleEvidence> scaleEvidence,
        CorridorTubeSlice tubeSlice,
        EndpointApproachModel endpointApproaches
    ) {
        EndpointConstraint constraint = junctionContext.at(profileIndex);
        if (constraint != null && constraint.fixed()) {
            return List.of(0.0);
        }
        CorridorBand band = bandAt(track, profileIndex);
        if (band == null) {
            double gapOffset = interpolatedGapOffset(track, profileIndex);
            return List.of(constraint == null ? gapOffset
                : Math.max(-constraint.maxDisplacementPx(), Math.min(constraint.maxDisplacementPx(), gapOffset)));
        }
        Set<Double> mandatory = new LinkedHashSet<>();
        mandatory.add(band.centerOffsetPx());
        mandatory.add(band.coreMinPx());
        mandatory.add(band.coreMaxPx());
        mandatory.add((band.coreMinPx() + band.coreMaxPx()) / 2.0);
        mandatory.add(band.shoulderMinPx());
        mandatory.add(band.shoulderMaxPx());
        mandatory.add(tubeSlice.centerOffsetPx());
        mandatory.add(tubeSlice.localCenterOffsetPx());
        mandatory.add(tubeSlice.stabilityCenterOffsetPx());
        mandatory.add(tubeSlice.rawCenterPx());
        mandatory.add(tubeSlice.lightCenterPx());
        mandatory.add(tubeSlice.standardCenterPx());
        endpointApproaches.targetsAt(profileIndex).forEach(target -> mandatory.add(target.expectedOffsetPx()));
        BandScaleEvidence evidence = scaleEvidence.get(scaleEvidenceKey(profileIndex, band.id()));
        if (evidence != null && evidence.hasCoarseCenterPrior()) {
            mandatory.add(evidence.coarseCenterPx());
        }
        Set<Double> offsets = new LinkedHashSet<>(mandatory);
        for (IntensitySample sample : profiles.get(profileIndex).source().intensitySamples()) {
            if (sample.insideRaster()
                && sample.offsetPx() >= band.shoulderMinPx()
                && sample.offsetPx() <= band.shoulderMaxPx()) {
                offsets.add(sample.offsetPx());
            }
        }
        if (constraint != null) {
            mandatory.add(0.0);
            mandatory.add(-constraint.maxDisplacementPx());
            mandatory.add(constraint.maxDisplacementPx());
            offsets.addAll(mandatory);
            offsets.removeIf(offset -> Math.abs(offset) > constraint.maxDisplacementPx() + 1e-9);
            mandatory.removeIf(offset -> Math.abs(offset) > constraint.maxDisplacementPx() + 1e-9);
        }
        List<Double> sorted = offsets.stream().sorted().toList();
        if (sorted.size() <= parameters.maxOffsetStates()) {
            return sorted;
        }
        List<Double> reduced = deduplicateMandatory(mandatory, 0.05 * sourcePixel);
        if (reduced.size() > parameters.maxOffsetStates()) {
            throw new IllegalStateException("Mandatory corridor states exceed configured state bound");
        }
        int remaining = parameters.maxOffsetStates() - reduced.size();
        for (int i = 0; i < remaining; i++) {
            double position = remaining == 1 ? (sorted.size() - 1.0) / 2.0
                : i * (sorted.size() - 1.0) / (remaining - 1.0);
            reduced.add(sorted.get((int) Math.round(position)));
        }
        return reduced.stream().distinct().toList();
    }

    private List<Double> deduplicateMandatory(Set<Double> mandatory, double tolerance) {
        List<Double> result = new ArrayList<>();
        for (double value : mandatory) {
            if (result.stream().noneMatch(existing -> Math.abs(existing - value) <= tolerance)) {
                result.add(value);
            }
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private double dataCost(
        CorridorTrack track,
        CorridorProfile profile,
        int profileIndex,
        double offset,
        double sourcePixel,
        Map<String, BandScaleEvidence> scaleEvidence,
        CorridorTubeSlice tubeSlice,
        double heatmapWeight,
        UnsupportedRippleEvaluator.RippleSupport rippleSupport,
        double rippleStrength,
        double rippleEndpointGate
    ) {
        return dataCostComponents(track, profile, profileIndex, offset, sourcePixel, scaleEvidence,
            tubeSlice, heatmapWeight, rippleSupport, rippleStrength, rippleEndpointGate).total();
    }

    private DataCost dataCostComponents(
        CorridorTrack track,
        CorridorProfile profile,
        int profileIndex,
        double offset,
        double sourcePixel,
        Map<String, BandScaleEvidence> scaleEvidence,
        CorridorTubeSlice tubeSlice,
        double heatmapWeight,
        UnsupportedRippleEvaluator.RippleSupport rippleSupport,
        double rippleStrength,
        double rippleEndpointGate
    ) {
        CorridorBand band = bandAt(track, profileIndex);
        if (band == null) {
            double tubeDistance = Math.abs(offset - tubeSlice.centerOffsetPx())
                / Math.max(sourcePixel, tubeSlice.uncertaintyPx());
            double tubeCost = parameters.tubeCenterWeight() * tubeSlice.confidence() * square(tubeDistance);
            double rippleCost = rippleTubeCost(offset, sourcePixel, tubeSlice, rippleSupport, rippleStrength,
                heatmapWeight, rippleEndpointGate);
            return new DataCost(0.35 + heatmapWeight * tubeCost + rippleCost, 0.0, 0.0,
                heatmapWeight * tubeCost, rippleCost);
        }
        ProfileIntensityInterpolator.InterpolatedIntensity interpolated = ProfileIntensityInterpolator
            .interpolate(profile.source(), offset).orElse(null);
        double scaleIntensity = interpolated == null ? 0.0 : interpolated.scaleIntensity();
        double bandMaximum = profile.source().intensitySamples().stream()
            .filter(IntensitySample::insideRaster)
            .filter(value -> value.offsetPx() >= band.shoulderMinPx() - 1e-9
                && value.offsetPx() <= band.shoulderMaxPx() + 1e-9)
            .mapToDouble(this::scaleIntensity)
            .max().orElse(scaleIntensity);
        double deadband = plateauDeadband(profile, band);
        boolean equivalentPeak = bandMaximum - scaleIntensity <= deadband;
        double normalizedIntensity = band.peakIntensity() <= band.noiseFloor() + 1e-9
            ? 0.0
            : clamp((scaleIntensity - band.noiseFloor()) / (band.peakIntensity() - band.noiseFloor()));
        double intensityCost = (equivalentPeak ? 0.0 : 1.0 - normalizedIntensity)
            * (0.55 + 0.45 * band.signalExistenceConfidence());
        boolean parentCorridor = track.parent() && band.parentHypothesis();
        if (parentCorridor) {
            // A sparse parent models the center of a longitudinal recording envelope. Its children can be
            // bright on alternating profiles, so raw per-profile intensity is supporting evidence rather
            // than a target that may pull the centerline onto one envelope shoulder.
            intensityCost *= 0.20;
        }
        double coreDistance = distanceOutside(offset, band.coreMinPx(), band.coreMaxPx()) / sourcePixel;
        double shoulderDistance = distanceOutside(offset, band.shoulderMinPx(), band.shoulderMaxPx()) / sourcePixel;
        double robustCoreCenter = (band.coreMinPx() + band.coreMaxPx()) / 2.0;
        double supportedCoreCenter = tubeSlice.stabilityCenterOffsetPx()
            + tubeSlice.motionSupport() * (robustCoreCenter - tubeSlice.stabilityCenterOffsetPx());
        double centerTarget = equivalentPeak ? supportedCoreCenter : band.centerOffsetPx();
        double stabilityDeadband = equivalentPeak ? 0.25 * sourcePixel * (1.0 - tubeSlice.motionSupport()) : 0.0;
        double centerDistance = Math.max(0.0, Math.abs(offset - centerTarget) - stabilityDeadband)
            / Math.max(sourcePixel, band.uncertaintyPx());
        double centerCost = square(centerDistance) * (0.12 + 0.38 * band.localizationConfidence());
        if (parentCorridor) {
            double parentUncertainty = Math.max(sourcePixel,
                Math.min(1.5 * sourcePixel, band.uncertaintyPx()));
            double parentCenterDistance = Math.abs(offset - band.centerOffsetPx()) / parentUncertainty;
            double parentWeight = track.groupingDecision().equals("combined") ? 0.90 : 0.45;
            centerCost += parentWeight * square(parentCenterDistance);
        }
        BandScaleEvidence evidence = scaleEvidence.get(scaleEvidenceKey(profileIndex, band.id()));
        double coarsePrior = evidence == null || !evidence.hasCoarseCenterPrior()
            ? 0.0
            : parameters.coarseCenterWeight() * evidence.scalePersistence()
                * square(1.0 - band.localizationConfidence())
                * square((offset - evidence.coarseCenterPx())
                    / Math.max(sourcePixel, Math.min(evidence.coarseUncertaintyPx(), 2.0 * sourcePixel)));
        double tubeDistance = Math.max(0.0, Math.abs(offset - tubeSlice.centerOffsetPx()) - stabilityDeadband)
            / Math.max(sourcePixel * 0.5, tubeSlice.uncertaintyPx());
        double tubeCost = parameters.tubeCenterWeight() * tubeSlice.confidence()
            * (0.25 + 0.75 * (1.0 - band.localizationConfidence())) * square(tubeDistance);
        double prominence = Math.max(0.0, band.peakIntensity() - band.noiseFloor());
        double weakSignal = clamp((0.35 - prominence) / 0.30);
        double unsupportedMotion = 1.0 - clamp(tubeSlice.motionSupport());
        tubeCost *= 1.0 + WEAK_UNSUPPORTED_TUBE_MULTIPLIER * weakSignal * unsupportedMotion;
        if (tubeSlice.scaleConflict() || tubeSlice.parentMerge()) {
            tubeCost *= 0.35;
        }
        double weightedCenterEvidence = heatmapWeight * (intensityCost
            + parameters.coreDistanceWeight() * square(coreDistance) + centerCost + coarsePrior + tubeCost);
        double shoulderCost = parameters.shoulderDistanceWeight() * square(shoulderDistance);
        double rippleCost = rippleTubeCost(offset, sourcePixel, tubeSlice, rippleSupport, rippleStrength,
            heatmapWeight, rippleEndpointGate);
        double total = weightedCenterEvidence + shoulderCost + rippleCost;
        return new DataCost(total, equivalentPeak ? heatmapWeight * centerCost : 0.0,
            heatmapWeight * coarsePrior, heatmapWeight * tubeCost, rippleCost);
    }

    private double rippleTubeCost(
        double offset,
        double sourcePixel,
        CorridorTubeSlice tubeSlice,
        UnsupportedRippleEvaluator.RippleSupport rippleSupport,
        double rippleStrength,
        double heatmapWeight,
        double rippleEndpointGate
    ) {
        double intervention = rippleInterventionWeight(rippleSupport, rippleEndpointGate);
        if (rippleStrength <= 0.0 || intervention <= 0.0) {
            return 0.0;
        }
        double distance = (offset - tubeSlice.stabilityCenterOffsetPx())
            / Math.max(sourcePixel, tubeSlice.stabilityUncertaintyPx());
        return heatmapWeight * 1.5 * rippleStrength * intervention * square(distance);
    }

    private double effectiveContinuityWeight(
        double base,
        UnsupportedRippleEvaluator.RippleSupport support,
        double strength,
        double endpointGate
    ) {
        double intervention = rippleInterventionWeight(support, endpointGate);
        return strength <= 0.0 || intervention <= 0.0
            ? base : base * (1.0 + 2.0 * strength * intervention);
    }

    private double effectiveAccelerationWeight(
        double base,
        UnsupportedRippleEvaluator.RippleSupport support,
        double strength,
        double endpointGate
    ) {
        double intervention = rippleInterventionWeight(support, endpointGate);
        return strength <= 0.0 || intervention <= 0.0
            ? base : base * (1.0 + 8.0 * strength * intervention);
    }

    private double rippleInterventionWeight(
        UnsupportedRippleEvaluator.RippleSupport support,
        double endpointGate
    ) {
        return support.unsupportedWeight() * support.trendAuthorization() * clamp(endpointGate);
    }

    static String scaleEvidenceKey(int profileIndex, String bandId) {
        return profileIndex + "/" + bandId;
    }

    private double scaleIntensity(IntensitySample sample) {
        return 0.30 * sample.nativeIntensity() + 0.30 * sample.lightFilteredIntensity()
            + 0.40 * sample.standardFilteredIntensity();
    }

    private double plateauDeadband(CorridorProfile profile, CorridorBand band) {
        double disagreement = profile.source().intensitySamples().stream()
            .filter(IntensitySample::insideRaster)
            .filter(sample -> sample.offsetPx() >= band.coreMinPx() - 1e-9
                && sample.offsetPx() <= band.coreMaxPx() + 1e-9)
            .mapToDouble(sample -> (Math.abs(sample.nativeIntensity() - sample.lightFilteredIntensity())
                + Math.abs(sample.lightFilteredIntensity() - sample.standardFilteredIntensity())) / 2.0)
            .average().orElse(0.0);
        return Math.max(0.02, Math.min(0.10, 0.02 + 0.5 * disagreement));
    }

    private double continuityWeight(CorridorTrack track, int profileIndex) {
        CorridorBand band = bandAt(track, profileIndex);
        return band == null ? 0.16 : 0.06 + 0.18 * (1.0 - band.localizationConfidence());
    }

    private double accelerationWeight(
        CorridorTrack track,
        int profileIndex,
        LongitudinalCorridorTube tube
    ) {
        CorridorBand current = bandAt(track, profileIndex);
        if (current == null) {
            return 0.42;
        }
        double base = 0.34 + 0.30 * (1.0 - current.localizationConfidence());
        double motionSupport = clamp(tube.at(profileIndex).motionSupport());
        double multiplier = NON_SUSTAINED_ACCELERATION_MULTIPLIER
            + motionSupport * (0.65 - NON_SUSTAINED_ACCELERATION_MULTIPLIER);
        return base * multiplier;
    }

    private double constraintCost(
        int profileIndex,
        double offset,
        JunctionContext context,
        EndpointApproachModel approaches,
        double sourcePixel
    ) {
        double cost = 0.0;
        for (EndpointConstraint constraint : context.constraints()) {
            if (profileIndex == constraint.profileIndex() && !constraint.fixed()) {
                cost += constraint.priorWeight() * square(offset / Math.max(sourcePixel, constraint.maxDisplacementPx()));
            }
        }
        cost += approaches.targetsAt(profileIndex).stream()
            .mapToDouble(target -> target.positionWeight()
                * square((offset - target.expectedOffsetPx()) / sourcePixel))
            .sum();
        return cost;
    }

    private double heatmapWeight(EndpointApproachModel approaches, int profileIndex) {
        double maximumGuideWeight = approaches.targetsAt(profileIndex).stream()
            .mapToDouble(EndpointApproachModel.GuideTarget::positionWeight).max().orElse(0.0);
        if (maximumGuideWeight <= 0.0) {
            return 1.0;
        }
        double normalizedProximity = Math.sqrt(Math.min(1.0, maximumGuideWeight / 2.5));
        return Math.max(0.15, 1.0 - 0.85 * normalizedProximity);
    }

    private double interpolatedGapOffset(CorridorTrack track, int profileIndex) {
        CorridorTrackPoint before = null;
        CorridorTrackPoint after = null;
        for (CorridorTrackPoint point : track.points().values()) {
            if (point.profileIndex() < profileIndex
                && (before == null || point.profileIndex() > before.profileIndex())) {
                before = point;
            }
            if (point.profileIndex() > profileIndex
                && (after == null || point.profileIndex() < after.profileIndex())) {
                after = point;
            }
        }
        if (before != null && after != null) {
            double fraction = (double) (profileIndex - before.profileIndex()) / (after.profileIndex() - before.profileIndex());
            return before.band().centerOffsetPx()
                + fraction * (after.band().centerOffsetPx() - before.band().centerOffsetPx());
        }
        if (before != null) {
            CorridorTrackPoint previous = track.points().get(before.profileIndex() - 1);
            double slope = previous == null ? 0.0 : before.band().centerOffsetPx() - previous.band().centerOffsetPx();
            int gap = profileIndex - before.profileIndex();
            return before.band().centerOffsetPx() + slope * gap * Math.pow(0.55, gap);
        }
        return after == null ? 0.0 : after.band().centerOffsetPx();
    }

    private CorridorBand bandAt(CorridorTrack track, int profileIndex) {
        CorridorTrackPoint point = track.points().get(profileIndex);
        return point == null ? null : point.band();
    }

    private double profileSpacing(List<CorridorProfile> profiles, int index, double sourcePixel) {
        Point2D.Double previous = profiles.get(index - 1).source().anchorScreen();
        Point2D.Double current = profiles.get(index).source().anchorScreen();
        return Math.max(sourcePixel, previous.distance(current));
    }

    private Point2D.Double pointFor(CorridorProfile profile, double offset) {
        return new Point2D.Double(
            profile.source().anchorScreen().x + profile.source().normalScreen().x * offset,
            profile.source().anchorScreen().y + profile.source().normalScreen().y * offset
        );
    }

    private double heading(Point2D.Double from, Point2D.Double to) {
        return Math.atan2(to.y - from.y, to.x - from.x);
    }

    private double geometricCurvatureCost(
        double heading,
        double referenceHeading,
        PairState previous,
        double spacingSourcePixels
    ) {
        double candidateTurn = angleDifference(heading, previous.segmentHeading());
        double referenceTurn = angleDifference(referenceHeading, previous.referenceHeading());
        double meanSpacing = Math.max(1.0, (spacingSourcePixels + previous.spacingSourcePixels()) / 2.0);
        return square(angleDifference(candidateTurn, referenceTurn) / meanSpacing);
    }

    private double geometricCurvatureCost(
        List<CorridorProfile> profiles,
        List<Double> offsets,
        LongitudinalCorridorTube tube,
        int index,
        double sourcePixel
    ) {
        Point2D.Double a = pointFor(profiles.get(index - 2), offsets.get(index - 2));
        Point2D.Double b = pointFor(profiles.get(index - 1), offsets.get(index - 1));
        Point2D.Double c = pointFor(profiles.get(index), offsets.get(index));
        double candidateTurn = angleDifference(heading(b, c), heading(a, b));
        Point2D.Double referenceA = pointFor(profiles.get(index - 2), tube.at(index - 2).centerOffsetPx());
        Point2D.Double referenceB = pointFor(profiles.get(index - 1), tube.at(index - 1).centerOffsetPx());
        Point2D.Double referenceC = pointFor(profiles.get(index), tube.at(index).centerOffsetPx());
        double referenceTurn = angleDifference(heading(referenceB, referenceC), heading(referenceA, referenceB));
        double spacing = (profileSpacing(profiles, index - 1, sourcePixel)
            + profileSpacing(profiles, index, sourcePixel)) / (2.0 * sourcePixel);
        return square(angleDifference(candidateTurn, referenceTurn) / Math.max(1.0, spacing));
    }

    private double absoluteShortWaveTurnCost(double heading, PairState previous,
        double spacingSourcePixels, UnsupportedRippleEvaluator.RippleSupport support,
        double rippleStrength, double endpointGate) {
        double gate = rippleStrength * rippleInterventionWeight(support, endpointGate);
        if (gate <= 0.0 || rippleParameters.absoluteTurnWeight() <= 0.0) return 0.0;
        double spacing = Math.max(1.0,
            (spacingSourcePixels + previous.spacingSourcePixels()) / 2.0);
        double curvature = Math.abs(angleDifference(heading, previous.segmentHeading())) / spacing;
        double normalized = Math.max(0.0,
            curvature - rippleParameters.curvatureDeadbandRadiansPerSourcePixel())
            / rippleParameters.curvatureScaleRadiansPerSourcePixel();
        return rippleParameters.absoluteTurnWeight() * gate
            * rippleParameters.huber(normalized) * spacing;
    }

    private double absoluteShortWaveTurnCost(List<CorridorProfile> profiles,
        List<Double> offsets, int index, double sourcePixel,
        UnsupportedRippleEvaluator.RippleSupport support, double rippleStrength,
        double endpointGate) {
        Point2D.Double a = pointFor(profiles.get(index - 2), offsets.get(index - 2));
        Point2D.Double b = pointFor(profiles.get(index - 1), offsets.get(index - 1));
        Point2D.Double c = pointFor(profiles.get(index), offsets.get(index));
        PairState prior = new PairState(index - 1, offsets.get(index - 2), offsets.get(index - 1),
            heading(a, b), 0.0, profileSpacing(profiles, index - 1, sourcePixel) / sourcePixel,
            0.0, null);
        return absoluteShortWaveTurnCost(heading(b, c), prior,
            profileSpacing(profiles, index, sourcePixel) / sourcePixel,
            support, rippleStrength, endpointGate);
    }

    private double[] rippleEndpointGates(int profileCount, EndpointApproachModel approaches) {
        double[] result = new double[profileCount];
        java.util.Arrays.fill(result, 1.0);
        for (int index = 0; index < profileCount; index++) {
            double owner = approaches.targetsAt(index).stream()
                .mapToDouble(EndpointApproachModel.GuideTarget::positionWeight).max().orElse(0.0);
            result[index] = 1.0 - clamp(owner / 2.5);
        }
        return result;
    }

    private double angleDifference(double angle, double reference) {
        double difference = angle - reference;
        while (difference > Math.PI) {
            difference -= 2.0 * Math.PI;
        }
        while (difference < -Math.PI) {
            difference += 2.0 * Math.PI;
        }
        return difference;
    }

    private double distanceOutside(double value, double minimum, double maximum) {
        if (value < minimum) {
            return minimum - value;
        }
        if (value > maximum) {
            return value - maximum;
        }
        return 0.0;
    }

    private double validSourcePixel(double sourcePixelSizePx) {
        return Double.isFinite(sourcePixelSizePx) && sourcePixelSizePx > 0.0 ? sourcePixelSizePx : 1.0;
    }

    private double square(double value) {
        return value * value;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /**
     * Full result of centerline optimization.
     *
     * @param offsetsPx optimized lateral offsets
     * @param screenPoints optimized sampled-raster geometry
     * @param totalCost total dynamic-programming objective
     * @param costs per-profile decomposed objective values
     * @param inCorridorFraction fraction within selected shoulder envelopes
     * @param longitudinalStability inverse normalized lateral-acceleration energy
     * @param quality unweighted physical/source-resolution quality metrics
     * @param endpointApproaches endpoint boundary evidence used by the optimizer
     * @param maximumOffsetStates maximum lateral states retained at any profile
     * @param maximumPairStates maximum exact second-order pair states retained at any profile
     * @param transitionEvaluations number of evaluated exact DP transitions
     * @param profileCostEvaluations number of profile/offset costs evaluated before the DP
     * @param pointTableEntries number of profile/offset points constructed before the DP
     * @param adjacentGeometryEntries number of adjacent offset pairs precomputed
     * @param retainedPairStateAllocations number of pair states allocated after strict improvement
     */
    public record OptimizationResult(
        List<Double> offsetsPx,
        List<Point2D.Double> screenPoints,
        double totalCost,
        List<CostRow> costs,
        double inCorridorFraction,
        double longitudinalStability,
        CorridorQuality quality,
        EndpointApproachModel endpointApproaches,
        int maximumOffsetStates,
        int maximumPairStates,
        long transitionEvaluations,
        long profileCostEvaluations,
        long pointTableEntries,
        long adjacentGeometryEntries,
        long retainedPairStateAllocations
    ) {
        /** Makes optimizer output collections immutable. */
        public OptimizationResult {
            offsetsPx = List.copyOf(offsetsPx);
            screenPoints = List.copyOf(screenPoints);
            costs = List.copyOf(costs);
            quality = java.util.Objects.requireNonNull(quality);
            endpointApproaches = java.util.Objects.requireNonNull(endpointApproaches);
        }

        /** Distinguishes a spent no-legal-path attempt from an unstarted empty input. */
        public SolverDisposition disposition() {
            return !offsetsPx.isEmpty() ? SolverDisposition.ROUTE
                : profileCostEvaluations > 0 ? SolverDisposition.NO_LEGAL_PATH : SolverDisposition.EMPTY_INPUT;
        }

        static OptimizationResult empty() {
            return new OptimizationResult(List.of(), List.of(), Double.POSITIVE_INFINITY, List.of(), 0.0, 0.0,
                CorridorQuality.empty(), new EndpointApproachModel(List.of()), 0, 0, 0L, 0L, 0L, 0L, 0L);
        }
    }

    public enum SolverDisposition { ROUTE, NO_LEGAL_PATH, EMPTY_INPUT }

    /**
     * Per-profile optimizer diagnostics.
     *
     * @param profileIndex profile index
     * @param chosenOffsetPx selected lateral offset
     * @param profileSpacingPx longitudinal spacing from the previous profile, or the next spacing at index zero
     * @param dataCost heatmap and corridor-fit cost
     * @param continuityCost first-difference diagnostic
     * @param accelerationCost second-difference diagnostic
     * @param absoluteShortWaveTurnCost cleanup-only absolute turn-rate cost
     * @param endpointCost endpoint prior cost
     * @param plateauCenterCost robust center cost applied within an intensity plateau
     * @param coarsePriorCost compatible coarse-scale localization prior
     * @param tubeCenterCost confidence-weighted longitudinal tube-center prior
     * @param effectiveRippleScaleMeters legacy configured scale exported for format-10 compatibility,
     *     or zero when disabled; shape analysis uses the fixed 6/10/20 m bank
     * @param effectiveRippleStrength active unsupported-ripple strength, or zero when disabled
     * @param rippleSupport sustained motion/turn support
     * @param supportedTurnWeight bounded supported-turn evidence
     * @param shortScaleExposure bounded short-reversal exposure
     * @param residualAmplitudeSourcePixels robust residual amplitude in source pixels
     * @param maximumResidualSourcePixels maximum residual amplitude in source pixels
     * @param directCoverage bounded physical direct-evidence coverage
     * @param trendCenterOffsetPx robust trend center at this profile in sampled-raster pixels
     * @param trendSlopePxPerMeter robust trend slope in sampled-raster pixels per ground metre
     * @param trendUncertaintyPx robust trend uncertainty in sampled-raster pixels
     * @param trendAuthorization bounded authorization for trend-owned cleanup costs
     * @param unsupportedRippleFactor physical-window unsupported-ripple factor
     * @param reversalSpacingMeters median reversal spacing, or NaN
     * @param rippleDecision machine-readable support decision
     * @param rippleAdditionalCost additional configured cost in this row
     * @param weightedTotal exact sum of this row's weighted objective terms
     * @param insideCore whether the point is inside its selected high-level core
     * @param insideCorridor whether the point is inside its selected shoulder envelope
     */
    public record CostRow(
        int profileIndex,
        double chosenOffsetPx,
        double profileSpacingPx,
        double dataCost,
        double continuityCost,
        double accelerationCost,
        double absoluteShortWaveTurnCost,
        double plateauCenterCost,
        double coarsePriorCost,
        double tubeCenterCost,
        double endpointCost,
        double effectiveRippleScaleMeters,
        double effectiveRippleStrength,
        double rippleSupport,
        double supportedTurnWeight,
        double shortScaleExposure,
        double residualAmplitudeSourcePixels,
        double maximumResidualSourcePixels,
        double directCoverage,
        double trendCenterOffsetPx,
        double trendSlopePxPerMeter,
        double trendUncertaintyPx,
        double trendAuthorization,
        double unsupportedRippleFactor,
        double reversalSpacingMeters,
        String rippleDecision,
        double rippleAdditionalCost,
        double weightedTotal,
        boolean insideCore,
        boolean insideCorridor
    ) {
    }

    private record OffsetState(double offset, Point2D.Double point, double profileCost) {
    }

    private record AdjacentGeometry(
        double[][] headings,
        double[][] continuityCosts,
        double referenceHeading
    ) {
        double heading(int previousIndex, int currentIndex) {
            return headings[previousIndex][currentIndex];
        }

        double continuityCost(int previousIndex, int currentIndex) {
            return continuityCosts[previousIndex][currentIndex];
        }

        long entryCount() {
            return headings.length == 0 ? 0L : (long) headings.length * headings[0].length;
        }
    }

    private record PairState(
        int profileIndex,
        double previousOffset,
        double currentOffset,
        double segmentHeading,
        double referenceHeading,
        double spacingSourcePixels,
        double cost,
        PairState predecessor
    ) {
    }

    private record ExactSolution(
        List<Double> offsetsPx,
        int maximumOffsetStates,
        int maximumPairStates,
        long transitionEvaluations,
        long profileCostEvaluations,
        long pointTableEntries,
        long adjacentGeometryEntries,
        long retainedPairStateAllocations
    ) {
    }

    private record DataCost(
        double total,
        double plateauCenterCost,
        double coarsePriorCost,
        double tubeCenterCost,
        double rippleAdditionalCost
    ) {
    }
}
