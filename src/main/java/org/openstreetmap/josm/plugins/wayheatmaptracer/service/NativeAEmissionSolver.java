package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import java.util.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.GeometryCleanupConfig;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.MetricPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.RasterPoint;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.CompetitiveAdmissionExecutor.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.AttemptMemoryLedger;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.CancellationProbe;

/**
 * Explicit experimental native-A binding for optional-emission v2. Ordinary tracker entries never
 * select it. Graphs, source receipts and Q/C/T/K cannot be supplied by a caller. Results borrow the
 * retained input and own their charged execution storage until {@link Result#close()}.
 */
public final class NativeAEmissionSolver {
    public static final String EXECUTION_VERSION="native-a-certified-competitive-v1";
    private NativeAEmissionSolver() { }

    /** Read-only native core evidence. Only the private A pipeline creates these facts. */
    public static final class NativeCoreOwner {
        private final CorridorBand band;
        private final RasterPoint minimum,maximum;
        private NativeCoreOwner(CorridorBand band,RasterPoint minimum,RasterPoint maximum) {
            this.band=band;this.minimum=minimum;this.maximum=maximum;
        }
        public RasterPoint minimumRaster(){return minimum;}
        public RasterPoint maximumRaster(){return maximum;}
        public double uncertaintyRasterPixels(){return band.uncertaintyPx();}
        public String bandId(){return band.id();}
    }

    /** Exact original A state authority, distinct from a Legacy peak or a parent midpoint. */
    public static final class NativeEndpointOwner {
        private final RowState state;
        private final RenderedHeatmapSampler.CrossSectionProfile profile;
        private final RasterPoint position;
        private final List<NativeCoreOwner> cores;
        private NativeEndpointOwner(RowState state,RenderedHeatmapSampler.CrossSectionProfile profile,
                RasterPoint position,List<NativeCoreOwner> cores) {
            this.state=state;this.profile=profile;this.position=position;this.cores=cores;
        }
        public RasterPoint actualRasterPosition(){return position;}
        public List<NativeCoreOwner> directCores(){return cores;}
    }

    /**
     * Opaque side table issued by a private, retained-source A domain. No public descriptor or
     * foreign/equal-valued RowState can mint an entry or extend this inventory's lifetime.
     */
    public static final class NativeEndpointInventory {
        private final Engine issuer;
        private final Map<RowState,NativeEndpointOwner> owners=new IdentityHashMap<>();
        private boolean sealed;
        private NativeEndpointInventory(Engine issuer){this.issuer=issuer;}
        private void issue(RowState state,RasterPoint point,List<NativeCoreOwner> cores) {
            if(sealed)throw new IllegalStateException("Native A owner inventory is sealed");
            issuer.reserve(256);issuer.work(1);
            if(owners.put(state,new NativeEndpointOwner(state,issuer.profiles.get(state.row()).source(),point,cores))!=null)
                throw new IllegalStateException("Native A state owner was duplicated");
        }
        public boolean belongsTo(SampledEmissionInput input) {
            return sealed&&!issuer.closed&&!issuer.owner.isClosed()&&issuer.input==input;
        }
        public NativeEndpointOwner ownerFor(RowState state) {
            if(!belongsTo(issuer.input))return null;
            NativeEndpointOwner owner=owners.get(state);
            if(owner==null||owner.state!=state||owner.profile!=issuer.input.profiles().levelZeroProfiles().get(state.row()))return null;
            return owner;
        }
    }

    /** Complete original numeric state inventory, before ordinary/optional image admission. */
    public record NativeStateInventory(int row,int nativeStateId,double offsetPx,SourceClass sourceClass) { }

    /** Original occurrence; boundary events have neither a numeric offset nor observation credit. */
    public record EmittedOccurrence(int vertexId,int row,int nativeStateId,String occurrenceId,
            RasterPoint raster,MetricPoint metric,Double offsetPx) { }

    /** One actual selected edge's objective and scalar availability. */
    public record SelectedCost(int arcId,double sourceSpanMeters,boolean optional,
            boolean scalarAvailable,double q,double c,double t,double k) { }

    /** Borrowed result; closing releases the connector session, receipts and execution tables. */
    public static final class Result implements AutoCloseable {
        private final Engine engine;
        private final List<Integer> vertexIds,arcIds,consumedRows;
        private final List<EmittedOccurrence> emitted;
        private final List<SelectedCost> costs;
        private final List<NativeStateInventory> nativeStates;
        private final double cost,q,c,t,k;
        private final CompetitiveAdmissionExecutor.Result closure;
        private Result(Engine engine,Label best,CompetitiveAdmissionExecutor.Result closure) {
            this.engine=engine;this.closure=closure;
            int depth=best==null?0:best.depth;
            engine.reserve(1024L+depth*512L+engine.profiles.size()*32L+engine.vertices.size()*128L);
            var states=new ArrayList<NativeStateInventory>();
            for(var vertex:engine.vertices)if(vertex.endpoint instanceof RowState row) {
                engine.work(1);states.add(new NativeStateInventory(row.row(),row.nativeStateId(),row.offsetRasterPixels(),row.sourceClass()));
            }
            nativeStates=List.copyOf(states);
            var vertices=new ArrayList<Integer>(depth);var arcs=new ArrayList<Integer>(depth);
            var occurrences=new ArrayList<EmittedOccurrence>(depth);var selected=new ArrayList<SelectedCost>(depth);
            var consumed=new ArrayList<Integer>();
            for(Label at=best;at!=null;at=at.parent) {
                engine.pair(EmissionWorkBudget.Operation.BACKPOINTER_VISIT);
                VertexFact v=engine.vertices.get(at.key.cursorId());
                vertices.add(v.id);occurrences.add(new EmittedOccurrence(v.id,v.row,
                    v.endpoint instanceof RowState row?row.nativeStateId():-1,
                    v.endpoint instanceof Barrier event?event.occurrenceId():"",v.raster,v.metric,v.offset));
                if(at.arc!=null) {
                    arcs.add(at.arc.id());var a=at.admission;
                    selected.add(new SelectedCost(at.arc.id(),at.arc.sourceSpanMeters(),at.arc.optional(),
                        a.scalar().isPresent(),a.q(),a.c(),a.t(),a.k()));
                    if(at.arc.optional())for(int row=engine.vertices.get(at.arc.fromId()).row+1;row<v.row;row++) {
                        engine.work(1);consumed.add(row);
                    }
                }
            }
            Collections.reverse(vertices);Collections.reverse(arcs);Collections.reverse(occurrences);
            Collections.reverse(selected);Collections.reverse(consumed);
            vertexIds=List.copyOf(vertices);arcIds=List.copyOf(arcs);emitted=List.copyOf(occurrences);
            costs=List.copyOf(selected);consumedRows=List.copyOf(consumed);
            cost=best==null?Double.POSITIVE_INFINITY:best.cost;
            q=best==null?0:best.q;c=best==null?0:best.c;t=best==null?0:best.t;k=best==null?0:best.k;
        }
        private void requireOpen(){engine.requireOpen();}
        public List<Integer> vertexIds(){requireOpen();return vertexIds;}
        public List<Integer> arcIds(){requireOpen();return arcIds;}
        public List<Integer> consumedRows(){requireOpen();return consumedRows;}
        public List<EmittedOccurrence> emitted(){requireOpen();return emitted;}
        public List<SelectedCost> costs(){requireOpen();return costs;}
        public List<NativeStateInventory> nativeStates(){requireOpen();return nativeStates;}
        public List<Arc> potentialInventory(){requireOpen();return engine.graph.arcs();}
        public double cost(){requireOpen();return cost;}
        public double q(){requireOpen();return q;}
        public double c(){requireOpen();return c;}
        public double t(){requireOpen();return t;}
        public double k(){requireOpen();return k;}
        public int potentialVertices(){requireOpen();return engine.vertices.size();}
        public int potentialArcs(){requireOpen();return engine.arcs.size();}
        public CompetitiveAdmissionExecutor.Result closure(){requireOpen();return closure;}
        public String sourceIdentity(){requireOpen();return engine.input.context().sourceIdentity();}
        public String sourceContentHash(){requireOpen();return engine.input.context().evidenceContentHash();}
        public String executionVersion(){requireOpen();return EXECUTION_VERSION;}
        public String numericalPolicyId(){requireOpen();return engine.input.context().policyId();}
        public EmissionWorkBudget.Snapshot work(){requireOpen();return engine.budget.snapshot();}
        @Override public void close(){engine.close();}
    }

    static Result solve(CorridorCenterlineOptimizer optimizer, SampledEmissionInput input,
            CorridorTrack track, List<CorridorProfile> profiles, double sourcePixel,
            JunctionContext junctions, Map<String,BandScaleEvidence> scale,
            List<NativeConnectorEvaluator.Barrier> barriers, boolean competitive) {
        return solve(optimizer,input,track,profiles,sourcePixel,junctions,scale,barriers,competitive,
            QueryPolicy.DENSE_EIGHTH_NATIVE_V1,ExecutionPolicy.EAGER_ALL_PROBES_V1,GeometryCleanupConfig.disabled());
    }

    static Result solve(CorridorCenterlineOptimizer optimizer,SampledEmissionInput input,
            CorridorTrack track,List<CorridorProfile> profiles,double sourcePixel,JunctionContext junctions,
            Map<String,BandScaleEvidence> scale,List<Barrier> barriers,boolean competitive,
            QueryPolicy query,ExecutionPolicy execution,GeometryCleanupConfig cleanup) {
        return solve(optimizer,input,track,profiles,sourcePixel,junctions,scale,barriers,competitive,query,execution,cleanup,null);
    }

    static Result solve(CorridorCenterlineOptimizer optimizer,SampledEmissionInput input,
            CorridorTrack track,List<CorridorProfile> profiles,double sourcePixel,JunctionContext junctions,
            Map<String,BandScaleEvidence> scale,List<Barrier> barriers,boolean competitive,
            QueryPolicy query,ExecutionPolicy execution,GeometryCleanupConfig cleanup,
            CorridorAwareTracker.NativeOwnerSource nativeSource) {
        var admittedOwner=input.openExecutionOwner("native-a-optional-v2");
        Engine engine=null;
        try {
            input.workBudget().charge(EmissionWorkBudget.Operation.ALLOCATION,1);
            admittedOwner.reserve(2048); // Engine/map/list shells, before construction.
            engine=new Engine(optimizer,input,track,profiles,sourcePixel,junctions,scale,barriers,query,execution,cleanup,admittedOwner,nativeSource);
            var closure=competitive?new CompetitiveAdmissionExecutor(engine.graph,engine,engine.budget,engine.owner).run():null;
            Label best=engine.solveExact(closure);
            if(closure!=null&&(!closure.vertexIds().equals(engine.pathIds(best))
                    ||Double.doubleToLongBits(closure.cost())!=Double.doubleToLongBits(best==null?Double.POSITIVE_INFINITY:best.cost)))
                throw new InvalidCertificateException("Native canonical DP disagrees with closure");
            return new Result(engine,best,closure);
        } catch(RuntimeException failure){
            recordTerminal(input,failure);
            if(engine!=null)engine.close();else admittedOwner.close();throw failure;
        }
    }

    private record VertexFact(int id,int row,int group,double chainage,PhysicalEndpoint endpoint,
            RasterPoint raster,MetricPoint metric,Double offset) { }
    private record TransitionKey(CanonicalKey prefix,int arcId) { }
    private record ArcEvidence(BilinearSegmentIntegral.Result scalar,Receipt optional,Object direction) { }
    private record Label(CanonicalKey key,Label parent,Arc arc,Admission admission,double cost,
            double q,double c,double t,double k,int consumed,int depth) { }

    private static final class Engine implements TransitionProvider,AutoCloseable {
        final SampledEmissionInput input;
        final EmissionWorkBudget budget;
        final AttemptMemoryLedger.Owner owner;
        final List<CorridorProfile> profiles;
        final List<VertexFact> vertices=new ArrayList<>();
        final List<Arc> arcs=new ArrayList<>();
        final Map<Integer,ArcEvidence> positiveEvidence=new HashMap<>();
        final Map<CanonicalKey,Object> directions=new HashMap<>();
        final Map<TransitionKey,Admission> admissions=new HashMap<>();
        final double sourcePixel,pitch;
        final double[] spacingIntervals,continuityWeights;
        final CorridorCenterlineOptimizer.NativeStateDomain domain;
        final LongitudinalCorridorTube tube;
        final Graph graph;
        final int[] arcStarts;
        final NativeEndpointInventory endpointOwners;
        NativeConnectorEvaluator evaluator;
        boolean closed;

        Engine(CorridorCenterlineOptimizer optimizer,SampledEmissionInput input,CorridorTrack track,
                List<CorridorProfile> profiles,double sourcePixel,JunctionContext junctions,
                Map<String,BandScaleEvidence> scale,List<Barrier> barriers,QueryPolicy query,
                ExecutionPolicy execution,GeometryCleanupConfig cleanup,AttemptMemoryLedger.Owner admittedOwner,
                CorridorAwareTracker.NativeOwnerSource nativeSource) {
            this.input=Objects.requireNonNull(input);budget=input.workBudget();
            owner=admittedOwner;
            try {
                reserve(128L+profiles.size()*16L);this.profiles=List.copyOf(profiles);
                if(!cleanup.isDisabled())throw new IllegalArgumentException("Native A optional cleanup is ineligible");
                var eligibility=input.context().eligibility();
                if(!input.context().productionEligible()&&eligibility!=OptionalEmissionContext.Eligibility.DECLARED_SYNTHETIC)
                    throw new IllegalArgumentException("Native A optional source is ineligible");
                if(!Double.isFinite(sourcePixel)||sourcePixel<=0)
                    throw new IllegalArgumentException("Captured native tracker normalization is required");
                this.sourcePixel=sourcePixel;pitch=input.context().nativePitchMeters().orElseThrow();
                if(Double.doubleToLongBits(sourcePixel)!=Double.doubleToLongBits(input.context().trackerNormalizationRasterPx()))
                    throw new IllegalArgumentException("A tracker normalization must match its retained producer");
                if(profiles.size()<2||profiles.size()!=input.context().observations().size()
                        ||input.profiles().levels().get(0).profiles().size()!=profiles.size())
                    throw new IllegalArgumentException("Native A requires the complete unchanged retained selection");
                long samples=0;int maximumSamples=0;
                int constraintCount=junctions.constraints().size();
                reserve(256L+constraintCount*64L);
                work(Math.addExact(constraintCount,Math.multiplyExact((long)profiles.size(),constraintCount)));
                Set<Integer> constrainedRows=new HashSet<>();int fixedRows=0;
                for(var constraint:junctions.constraints()) {
                    work(1);
                    if(constraint.profileIndex()<0||constraint.profileIndex()>=profiles.size()
                            ||!constrainedRows.add(constraint.profileIndex()))
                        throw new IllegalArgumentException("Native endpoint constraints must identify unique retained rows");
                    if(constraint.fixed())fixedRows++;
                }
                for(int row=0;row<profiles.size();row++) {
                    work(1);
                    if(profiles.get(row).source()!=input.profiles().levels().get(0).profiles().get(row))
                        throw new IllegalArgumentException("Extracted A profile belongs to another producer");
                    samples=Math.addExact(samples,profiles.get(row).source().intensitySamples().size());
                    maximumSamples=Math.max(maximumSamples,profiles.get(row).source().intensitySamples().size());
                    var point=track.points().get(row);
                    if(point!=null&&point.profileIndex()!=row)
                        throw new IllegalArgumentException("Native grouped track occurrence is misplaced");
                    var constraint=junctions.at(row);
                    if(constraint!=null&&constraint.junction()&&!constraint.fixed())
                        throw new IllegalArgumentException("Movable protected junction target adapter is unavailable");
                }
                // Native constructors retain their arithmetic and order. Reserve their temporary
                // offset sets, tube/approach tables and row records before entering those builders.
                // Each constrained side can retain at most N guides. targetsAt scans both sides
                // for every nonfixed native row; do not hide that dense-input work behind a
                // constant per-row allowance. Bounds are conservatively prepaid, not measured IO.
                long guideCount=Math.multiplyExact(2L,Math.multiplyExact((long)constraintCount,profiles.size()));
                long guideScans=Math.multiplyExact(guideCount,profiles.size()-fixedRows);
                long endpointScans=Math.multiplyExact(guideCount,Math.max(1,maximumSamples));
                long mandatoryComparisons=Math.multiplyExact((long)profiles.size(),
                    Math.addExact(Math.multiplyExact(96L,constraintCount),Math.multiplyExact(4L,
                        Math.multiplyExact((long)constraintCount,constraintCount))));
                reserve(Math.addExact(Math.addExact(8192L+profiles.size()*8192L,Math.multiplyExact(samples,1024L)),
                    Math.multiplyExact(guideCount,128L)));
                work(Math.addExact(Math.addExact(profiles.size()*4096L,Math.multiplyExact(samples,128L)),
                    Math.addExact(Math.multiplyExact(guideScans,4L),Math.addExact(Math.multiplyExact(endpointScans,64L),
                        mandatoryComparisons))));
                tube=new CorridorTubeBuilder().build(track,profiles,sourcePixel,scale);
                var approaches=new EndpointApproachBuilder().build(track,profiles,tube,junctions,scale);
                domain=optimizer.nativeStateDomain(track,profiles,sourcePixel,junctions,scale,tube,approaches);
                if(domain.rows().size()!=profiles.size())throw new IllegalArgumentException("No native A track domain");
                spacingIntervals=new double[profiles.size()];continuityWeights=new double[profiles.size()];
                for(int row=1;row<profiles.size();row++) {
                    work(1);
                    spacingIntervals[row]=Math.max(sourcePixel,profiles.get(row-1).source()
                        .anchorScreen().distance(profiles.get(row).source().anchorScreen()));
                    continuityWeights[row]=domain.rows().get(row).continuityWeight();
                }
                Set<Integer> mandatoryRows=new HashSet<>();
                reserve(1024L+profiles.size()*64L+barriers.size()*512L);
                for(int row=0;row<profiles.size();row++)if(junctions.at(row)!=null){work(1);mandatoryRows.add(row);}
                for(Barrier barrier:barriers){work(1);mandatoryRows.add(barrier.leftRow());mandatoryRows.add(barrier.rightRow());}
                reserve(512);endpointOwners=new NativeEndpointInventory(this);
                List<RowState> states=new ArrayList<>();Set<Integer> mandatoryVertices=new HashSet<>();int group=0,barrierCursor=0;
                for(var row:domain.rows()) {
                    work(1);
                    var sourceClass=row.bandOwner()==null||row.trackPointOwner().support()!=CorridorPointSupport.DIRECT_UNION
                        ?SourceClass.GAP:!row.bandOwner().hasMeasuredCenter()?SourceClass.CENSORED:SourceClass.OBSERVED;
                    List<NativeCoreOwner> cores=List.of();
                    if(sourceClass==SourceClass.OBSERVED) {
                        List<CorridorBand> direct;
                        if(nativeSource!=null)direct=nativeSource.directBands(input,track,row.rowIndex());
                        else {
                            if(track.parent()||profiles.get(row.rowIndex()).bands().stream().noneMatch(band->band==row.bandOwner()))
                                throw new IllegalArgumentException("Native parent ownership requires the original grouped source");
                            direct=List.of(row.bandOwner());
                        }
                        reserve(128L+direct.size()*256L);var facts=new ArrayList<NativeCoreOwner>();
                        Set<CorridorBand> seen=Collections.newSetFromMap(new IdentityHashMap<>());
                        for(CorridorBand band:direct) {
                            work(1);
                            if(!band.hasMeasuredCenter()||!Double.isFinite(band.uncertaintyPx())||band.uncertaintyPx()<0
                                    ||!Double.isFinite(band.coreMinPx())||!Double.isFinite(band.coreMaxPx())
                                    ||band.coreMinPx()>band.coreMaxPx())
                                throw new IllegalArgumentException("Native direct core ownership is incomplete");
                            if(seen.add(band))facts.add(new NativeCoreOwner(band,point(row.rowIndex(),band.coreMinPx()),
                                point(row.rowIndex(),band.coreMaxPx())));
                        }
                        if(facts.isEmpty())throw new IllegalArgumentException("Observed native A state has no direct core owner");
                        cores=List.copyOf(facts);
                    }
                    for(var state:row.states()) {
                        reserve(512);work(1);
                        var endpoint=new RowState(row.rowIndex(),state.stateId(),state.offsetPx(),sourceClass,null);
                        states.add(endpoint);RasterPoint point=point(row.rowIndex(),state.offsetPx());
                        endpointOwners.issue(endpoint,point,cores);
                        vertices.add(new VertexFact(vertices.size(),row.rowIndex(),group,
                            input.context().sourceChainageMeters(row.rowIndex()),endpoint,point,metric(point),state.offsetPx()));
                    }
                    group++;
                    while(barrierCursor<barriers.size()&&barriers.get(barrierCursor).leftRow()==row.rowIndex()) {
                        Barrier event=barriers.get(barrierCursor++);reserve(512);work(1);
                        if(event.rightRow()!=row.rowIndex()+1)throw new IllegalArgumentException("Protected event bracket is not adjacent");
                        RasterPoint point=input.context().rasterAt(event.exactPosition());
                        mandatoryVertices.add(vertices.size());
                        vertices.add(new VertexFact(vertices.size(),row.rowIndex(),group++,event.sourceChainageMeters(),
                            event,point,event.exactPosition(),null));
                    }
                }
                if(barrierCursor!=barriers.size())throw new IllegalArgumentException("Protected events are not in native source order");
                endpointOwners.sealed=true;
                evaluator=NativeConnectorEvaluator.forNativeA(input,new Domain(states,mandatoryRows.stream().sorted().toList(),barriers),
                    query,execution,endpointOwners);
                var declared=new ArrayList<Vertex>();reserve(256L+vertices.size()*128L);
                for(VertexFact v:vertices){work(1);declared.add(new Vertex(v.id,v.row,0,v.offset!=null,v.group==0,v.group==group-1,v.id));}
                for(VertexFact from:vertices)for(int destination=from.id+1;destination<vertices.size();destination++) {
                    work(1);VertexFact to=vertices.get(destination);if(to.group<=from.group)continue;
                    boolean ordinary=to.group==from.group+1;int consumed=to.row-from.row-1;
                    if(!ordinary&&consumed>16)break;
                    if(!ordinary) {
                        if(from.offset==null||to.offset==null||consumed<1||consumed>16
                                ||input.context().sourceSpanUpperMeters(from.row,to.row)>20)continue;
                        boolean crosses=false;
                        for(int row:mandatoryRows){work(1);if(row>from.row&&row<to.row){crosses=true;break;}}
                        if(crosses)continue;
                        for(int id:mandatoryVertices){work(1);if(id>from.id&&id<to.id){crosses=true;break;}}
                        if(crosses)continue;
                    }
                    reserve(256);
                    arcs.add(new Arc(arcs.size(),from.id,to.id,to.chainage-from.chainage,ordinary?0:consumed,
                        !ordinary,from.metric.distanceTo(to.metric)==0.0,arcs.size()));
                }
                graph=Graph.create(declared,arcs,mandatoryVertices,pitch,budget,owner);
                reserve(AttemptMemoryLedger.arrayBytes(vertices.size()+1L,4));
                arcStarts=new int[vertices.size()+1];int edge=0;
                for(int vertex=0;vertex<vertices.size();vertex++) {
                    work(1);arcStarts[vertex]=edge;
                    while(edge<arcs.size()&&arcs.get(edge).fromId()==vertex){work(1);edge++;}
                }
                arcStarts[vertices.size()]=edge;
            }catch(RuntimeException failure){recordTerminal(input,failure);close();throw failure;}
        }

        void requireOpen(){if(closed)throw new IllegalStateException("Native A result is closed");input.context();}
        void work(long units){budget.charge(EmissionWorkBudget.Operation.PREPARATION,units);}
        void pair(EmissionWorkBudget.Operation op){budget.charge(op,1);}
        void reserve(long bytes){budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);owner.reserve(bytes);}
        RasterPoint point(int row,double offset) {
            var source=profiles.get(row).source();return new RasterPoint(source.anchorScreen().x+source.normalScreen().x*offset,
                source.anchorScreen().y+source.normalScreen().y*offset);
        }
        MetricPoint metric(RasterPoint p){return input.context().metricAt(p);}
        @Override public BilinearSegmentIntegral.ReadOnlyScalarGrid nativeGrid(){return input.context().nativeGrid();}
        @Override public Admission certify(CanonicalKey key,Arc arc) {
            requireOpen();input.requireOpenAndUnchanged();pair(EmissionWorkBudget.Operation.PAIR_LOOKUP);
            if(key.cursorId()!=arc.fromId())throw new IllegalArgumentException("Native A prefix is misplaced");
            reserve(512);var cacheKey=new TransitionKey(key,arc.id());var cached=admissions.get(cacheKey);
            if(cached!=null)return cached;
            VertexFact from=vertices.get(arc.fromId()),to=vertices.get(arc.toId());
            ArcEvidence evidence=positiveEvidence.get(arc.id());
            if(evidence==null) {
                if(arc.optional()) {
                    Receipt receipt;
                    if(!arc.zeroPhysical())receipt=evaluator.evaluate((RowState)from.endpoint,(RowState)to.endpoint);
                    else {
                        Object direction=directions.get(key);
                        if(direction instanceof Receipt incoming)receipt=evaluator.evaluate((RowState)from.endpoint,(RowState)to.endpoint,incoming);
                        else if(direction instanceof OrdinaryPhysicalLeg incoming)
                            receipt=evaluator.evaluateWithOrdinaryIncoming((RowState)from.endpoint,(RowState)to.endpoint,incoming);
                        else if(direction instanceof PhysicalDirectionCursor incoming)
                            receipt=evaluator.evaluateWithDirectionCursor((RowState)from.endpoint,(RowState)to.endpoint,incoming);
                        else receipt=evaluator.evaluate((RowState)from.endpoint,(RowState)to.endpoint);
                    }
                    if(receipt.context()!=input.context()||receipt.from()!=from.endpoint||receipt.to()!=to.endpoint
                            ||Double.doubleToLongBits(receipt.sourceSpanMeters())!=Double.doubleToLongBits(arc.sourceSpanMeters()))
                        throw new InvalidCertificateException("Native optional receipt has a foreign source/span");
                    if(receipt.status()!=NativeConnectorEvaluator.Status.ADMITTED) {
                        Admission refused=Admission.refused(receipt.reason().name());admissions.put(cacheKey,refused);return refused;
                    }
                    evidence=new ArcEvidence(receipt.scalar().orElseThrow(),receipt,receipt);
                } else {
                    var scalar=integral(from.raster,to.raster);Object direction=null;
                    if(!arc.zeroPhysical())direction=ordinaryLeg(from,to,referenceAvailable(arc));
                    else if(key.effectivePositiveArcId()>=0) {
                        Object incoming=directions.get(key);
                        if(incoming instanceof Receipt receipt)direction=evaluator.advanceOrdinaryZero(receipt,from.endpoint,to.endpoint);
                        else if(incoming instanceof OrdinaryPhysicalLeg leg)direction=evaluator.advanceOrdinaryZero(leg,from.endpoint,to.endpoint);
                        else if(incoming instanceof PhysicalDirectionCursor cursor)direction=evaluator.advanceOrdinaryZero(cursor,from.endpoint,to.endpoint);
                        else throw new IllegalStateException("Native A lost transported physical direction");
                    }
                    evidence=new ArcEvidence(scalar,null,direction);
                }
                reserve(384);if(!arc.zeroPhysical())positiveEvidence.put(arc.id(),evidence);
            }
            double c=continuity(key,to),t=0,k=0;
            if(!arc.zeroPhysical()&&key.effectivePositiveArcId()>=0) {
                Arc incoming=arcs.get(key.effectivePositiveArcId());
                VertexFact ia=vertices.get(incoming.fromId()),ib=vertices.get(incoming.toId());
                double groundTurn=wrap(heading(from.metric,to.metric)-heading(ia.metric,ib.metric));
                k=square(groundTurn)/Math.max(1,(ia.metric.distanceTo(ib.metric)+from.metric.distanceTo(to.metric))/(2*pitch));
                if(referenceAvailable(incoming)&&referenceAvailable(arc)) {
                    double turn=wrap(heading(from.raster,to.raster)-heading(ia.raster,ib.raster));
                    double referenceTurn=wrap(referenceHeading(arc)-referenceHeading(incoming));
                    double h=(spanH(incoming)+spanH(arc))/(2*sourcePixel);
                    t=domain.rows().get(to.row).accelerationWeight()*square(wrap(turn-referenceTurn)/Math.max(1,h));
                }
            }
            Admission admitted=evidence.scalar.status()==BilinearSegmentIntegral.Status.COMPLETE
                ?Admission.measured(arc.sourceSpanMeters(),pitch,evidence.scalar,c,t,k,evidence)
                :Admission.ordinaryUnavailable(arc.sourceSpanMeters(),pitch,c,t,k,evidence);
            CanonicalKey next=nextKey(key,arc);
            if(evidence.direction!=null){reserve(192);directions.putIfAbsent(next,evidence.direction);}
            admissions.put(cacheKey,admitted);return admitted;
        }
        private Object ordinaryLeg(VertexFact a,VertexFact b,boolean reference) {
            var status=reference?NativeReferenceStatus.AVAILABLE:NativeReferenceStatus.UNAVAILABLE;
            if(a.endpoint instanceof RowState from&&b.endpoint instanceof RowState to)return evaluator.registerOrdinaryPhysicalLeg(from,to,status);
            if(a.endpoint instanceof RowState from&&b.endpoint instanceof Barrier to)return evaluator.registerOrdinaryPhysicalLeg(from,to,status);
            if(a.endpoint instanceof Barrier from&&b.endpoint instanceof RowState to)return evaluator.registerOrdinaryPhysicalLeg(from,to,status);
            return evaluator.registerOrdinaryPhysicalLeg((Barrier)a.endpoint,(Barrier)b.endpoint,status);
        }
        private BilinearSegmentIntegral.Result integral(RasterPoint a,RasterPoint b) {
            var grid=input.context().nativeGrid();var ga=grid.toGrid(a);var gb=grid.toGrid(b);
            long bound=(long)Math.ceil(Math.abs(gb.x()-ga.x())+Math.abs(gb.y()-ga.y()))+16;
            budget.charge(EmissionWorkBudget.Operation.ALLOCATION,1);
            try(var scratch=owner.reserve(Math.addExact(4096,Math.multiplyExact(bound,2048)))) {
                return BilinearSegmentIntegral.evaluate(grid,ga,gb,CancellationProbe.NONE,
                    ()->pair(EmissionWorkBudget.Operation.CELL_QUERY));
            }
        }
        private double sum(double[] terms,int from,int to){double sum=0;for(int row=from+1;row<=to;row++){work(1);sum+=terms[row];}return sum;}
        private double continuity(CanonicalKey key,VertexFact to) {
            if(to.offset==null||key.lastNumericVertexId()<0)return 0;
            VertexFact from=vertices.get(key.lastNumericVertexId());double h=sum(spacingIntervals,from.row,to.row);
            if(!(h>0))throw new IllegalStateException("Native numeric continuity has no original interval");
            return square((to.offset-from.offset)/h)*sum(continuityWeights,from.row,to.row);
        }
        private double spanH(Arc arc){return sum(spacingIntervals,vertices.get(arc.fromId()).row,vertices.get(arc.toId()).row);}
        private boolean referenceAvailable(Arc arc) {
            VertexFact a=vertices.get(arc.fromId()),b=vertices.get(arc.toId());
            if(a.offset==null||b.offset==null)return false;
            RasterPoint left=point(a.row,tube.at(a.row).centerOffsetPx()),right=point(b.row,tube.at(b.row).centerOffsetPx());
            return left.x()!=right.x()||left.y()!=right.y();
        }
        private double referenceHeading(Arc arc) {
            VertexFact a=vertices.get(arc.fromId()),b=vertices.get(arc.toId());
            return heading(point(a.row,tube.at(a.row).centerOffsetPx()),point(b.row,tube.at(b.row).centerOffsetPx()));
        }
        private CanonicalKey nextKey(CanonicalKey key,Arc arc) {
            VertexFact to=vertices.get(arc.toId());return new CanonicalKey(to.id,arc.zeroPhysical()?key.effectivePositiveArcId():arc.id(),
                to.offset==null?key.lastNumericVertexId():to.id,0);
        }
        Label solveExact(CompetitiveAdmissionExecutor.Result closure) {
            // Retain exact prefix versions; no IEEE dominance assumption or hidden beam.
            reserve(1024L+vertices.size()*128L);var labels=new ArrayList<List<Label>>(vertices.size());
            for(int vertex=0;vertex<vertices.size();vertex++){work(1);labels.add(new ArrayList<>());}
            Map<TransitionKey,MeasuredTransition> measured=new HashMap<>();
            if(closure!=null)for(var transition:closure.measuredTransitions()) {
                pair(EmissionWorkBudget.Operation.PAIR_INITIALIZATION);reserve(192);
                measured.put(new TransitionKey(transition.prefix(),transition.arcId()),transition);
            }
            for(var vertex:graph.vertices())if(vertex.initial()) {
                pair(EmissionWorkBudget.Operation.PAIR_INITIALIZATION);reserve(320);
                labels.get(vertex.id()).add(new Label(new CanonicalKey(vertex.id(),-1,vertex.id(),0),null,null,null,0,0,0,0,0,0,1));
            }
            Label best=null;
            for(int cursor=0;cursor<vertices.size();cursor++)for(Label prefix:labels.get(cursor)) {
                pair(EmissionWorkBudget.Operation.PAIR_LOOKUP);
                if(graph.vertices().get(cursor).terminal()){if(best==null||compare(prefix,best)<0)best=prefix;continue;}
                for(int edge=arcStarts[cursor];edge<arcStarts[cursor+1];edge++) {
                    pair(EmissionWorkBudget.Operation.PAIR_LOOKUP);Arc arc=arcs.get(edge);
                    pair(EmissionWorkBudget.Operation.TRANSITION);Admission admission;
                    if(closure==null)admission=certify(prefix.key,arc);
                    else {
                        reserve(96);var row=measured.get(new TransitionKey(prefix.key,arc.id()));
                        if(row==null)continue; // Strictly excluded by the completed competitive proof.
                        admission=admissions.get(new TransitionKey(prefix.key,arc.id()));
                        if(admission==null)throw new IllegalStateException("Measured closure lost native source receipt");
                    }
                    if(!admission.admitted())continue;
                    reserve(384);CanonicalKey key=nextKey(prefix.key,arc);
                    double cost=prefix.cost+admission.q()+admission.c()+admission.t()+admission.k();
                    if(!Double.isFinite(cost))throw new IllegalStateException("Native A objective is nonfinite");
                    labels.get(arc.toId()).add(new Label(key,prefix,arc,admission,cost,prefix.q+admission.q(),prefix.c+admission.c(),
                        prefix.t+admission.t(),prefix.k+admission.k(),Math.addExact(prefix.consumed,arc.consumedRows()),prefix.depth+1));
                }
            }
            return best;
        }
        private int compare(Label a,Label b) {
            pair(EmissionWorkBudget.Operation.TIE_COMPARISON);int cost=Double.compare(a.cost,b.cost);if(cost!=0)return cost;
            int consumed=Integer.compare(a.consumed,b.consumed);if(consumed!=0)return consumed;
            var left=pathIds(a);var right=pathIds(b);
            for(int i=0;i<Math.min(left.size(),right.size());i++) {
                pair(EmissionWorkBudget.Operation.TIE_COMPARISON);int order=Integer.compare(left.get(i),right.get(i));if(order!=0)return order;
            }
            return Integer.compare(left.size(),right.size());
        }
        List<Integer> pathIds(Label label) {
            reserve(128L+(label==null?0:label.depth)*32L);var ids=new ArrayList<Integer>();
            for(Label at=label;at!=null;at=at.parent){pair(EmissionWorkBudget.Operation.BACKPOINTER_VISIT);ids.add(at.key.cursorId());}
            Collections.reverse(ids);return ids;
        }
        @Override public void close(){if(!closed){closed=true;if(evaluator!=null)evaluator.close();owner.close();}}
    }
    private static void recordTerminal(SampledEmissionInput input,RuntimeException failure) {
        if(failure instanceof EmissionWorkBudget.LimitExceededException
                ||failure instanceof AttemptMemoryLedger.ResourceLimitException
                ||failure instanceof java.util.concurrent.CancellationException
                ||failure instanceof StateFamilyLimitException)input.recordTerminalExecutionFailure(failure);
    }
    private static double heading(RasterPoint a,RasterPoint b){return Math.atan2(b.y()-a.y(),b.x()-a.x());}
    private static double heading(MetricPoint a,MetricPoint b){return Math.atan2(b.yMeters()-a.yMeters(),b.xMeters()-a.xMeters());}
    private static double square(double value){return value*value;}
    private static double wrap(double value){while(value>Math.PI)value-=2*Math.PI;while(value< -Math.PI)value+=2*Math.PI;return value;}
}
