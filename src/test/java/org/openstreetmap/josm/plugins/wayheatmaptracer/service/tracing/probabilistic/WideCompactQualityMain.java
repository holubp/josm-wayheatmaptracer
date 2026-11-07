package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.quality.FinalGeometryEvaluator;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;
import org.openstreetmap.josm.spi.preferences.*;

/** Required CI-only numerical gate. Public analytic inputs, ordered cases, stop first failure. */
public final class WideCompactQualityMain {
    static final double P=WideCompactScenes.P,GAP=P/256,ANALYTIC_CHORD_ERROR=P/10000;
    static final String MANIFEST="/public-wide-compact-v1.properties";
    static final class QualityFailure extends AssertionError {
        QualityFailure(String message) { super(message); }
    }
    record Invocation(boolean diagnostic, Path output) { }
    static Invocation invocation(String[] args) {
        boolean diagnostic=args.length>0&&args[0].equals("--diagnostic");
        if(args.length>(diagnostic?2:1)||(!diagnostic&&args.length==1&&args[0].startsWith("--"))||(diagnostic&&args.length==2&&args[1].startsWith("--")))throw new IllegalArgumentException("Expected [output] or --diagnostic [output]");
        return new Invocation(diagnostic,Path.of(diagnostic?(args.length==2?args[1]:"build/reports/wide-compact-diagnostic"):(args.length==1?args[0]:"build/reports/wide-compact-quality")));
    }
    static void require(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
    static void qualityRequire(boolean condition,String reason){if(!condition)throw new QualityFailure(reason);}
    static void write(Path out,String name,String text)throws IOException{Files.writeString(out.resolve(name),text,StandardOpenOption.CREATE_NEW);}
    static final class Capture implements BQualityProbe.Sink {
        final boolean demandCompact;int denseCalls,closedCalls,inferredCalls;long closedBytes=-1,peakBytes,denseStates;
        final List<Integer> widths=new ArrayList<>();final List<BitSet> required=new ArrayList<>();
        final List<BitSet> sourceFrontiers=new ArrayList<>();
        final Map<Integer,Map<Integer,List<Integer>>> retained=new TreeMap<>();
        final List<int[]> rawStates=new ArrayList<>();final List<Double> rawEnergies=new ArrayList<>();
        DenseBAdmission.Bounds bound;boolean posteriorUsable;String completion="unavailable";
        final TraceBudgets expectedBudgets;
        final BPolicy expectedPolicy;
        Capture(boolean demandCompact){this(demandCompact,TraceBudgets.defaults());}
        Capture(boolean demandCompact,TraceBudgets expectedBudgets){this(demandCompact,expectedBudgets,BPolicy.INVENTORY_PREP_V4);}
        Capture(boolean demandCompact,TraceBudgets expectedBudgets,BPolicy policy){
            require(policy==BPolicy.INVENTORY_PREP_V4||policy==BPolicy.INVENTORY_FRONTIER_V5,"Unknown fixture inventory policy");
            this.demandCompact=demandCompact;this.expectedBudgets=expectedBudgets;this.expectedPolicy=policy;
        }
        public void dense(List<ProbabilisticProfile> sampled,List<InferenceProfile> dense,TraceBudgets budgets) {
            denseCalls++;require(denseCalls==1,"More than one original preparation");
            require(budgets.equals(expectedBudgets),"Candidate budgets differ from the explicit run input");
            for(int row=0;row<dense.size();row++) {
                var p=sampled.get(row);var d=dense.get(row);widths.add(d.cells().size());denseStates+=d.cells().size();var needed=new BitSet(d.cells().size());
                require(Math.abs(p.minimumOffsetMeters()+25)<1e-8&&Math.abs(p.maximumOffsetMeters()-25)<1e-8,"Profile decision extent clipped at row "+row);
                require(p.samples().stream().anyMatch(s->s.valid()&&s.offsetMeters()<=-25+1e-8)
                        &&p.samples().stream().anyMatch(s->s.valid()&&s.offsetMeters()>=25-1e-8),"Both lateral extremes not actually sampled at row "+row);
                for(int i=0;i<d.cells().size();i++) {
                    var cell=d.cells().get(i);
                    if(cell.mandatory()||cell.exactAnchor()){needed.set(i);if(i>0)needed.set(i-1);if(i+1<d.cells().size())needed.set(i+1);}
                    for(var mode:p.modes())if(cell.offsetMeters()>=mode.coreMinimumMeters()-.5*p.sourcePitchMeters()
                            &&cell.offsetMeters()<=mode.coreMaximumMeters()+.5*p.sourcePitchMeters())needed.set(i);
                }
                required.add(needed);
                if(expectedPolicy==BPolicy.INVENTORY_FRONTIER_V5) {
                    var frontier=(BitSet)needed.clone();
                    for(int seed=needed.nextSetBit(0);seed>=0;seed=needed.nextSetBit(seed+1))
                        for(int i=0;i<d.cells().size();i++)
                            if(Math.abs(d.cells().get(i).offsetMeters()-d.cells().get(seed).offsetMeters())<=p.sourcePitchMeters())frontier.set(i);
                    sourceFrontiers.add(frontier);
                }
            }
            bound=DenseBAdmission.estimate(widths.size(),widths::get,budgets,CancellationProbe.NONE);
            if(demandCompact)require(!bound.admitted()&&bound.scoresExceeded(),
                    "Fixed fixture did not naturally reject full admission: "+bound);
        }
        public void closed(long retainedBytes,long peak){closedCalls++;closedBytes=retainedBytes;peakBytes=peak;}
        public void inferred(ProbabilisticInferenceResult result) {
            inferredCalls++;posteriorUsable=result.posteriorUsable();completion=result.completion().toString();
            for(var path:result.rawPaths()){
                int[] states=path.stateIndices();
                if(!retained.isEmpty()){var finalRows=retained.get(retained.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow());for(int row=0;row<states.length;row++)states[row]=finalRows.get(row).get(states[row]);}
                rawStates.add(states);rawEnergies.add(path.energy());
            }
        }
        void retained(int pass,int row,int compact,int original) {
            var indices=retained.computeIfAbsent(pass,k->new TreeMap<>()).computeIfAbsent(row,k->new ArrayList<>());
            require(compact==indices.size(),"Retained cell order changed");indices.add(original);
        }
        void dump(Path out)throws IOException {
            StringBuilder rows=new StringBuilder("row,original_width,required_cells\n");for(int i=0;i<widths.size();i++)rows.append(i).append(',').append(widths.get(i)).append(',').append('"').append(required.get(i)).append("\"\n");write(out,"dense-census.csv",rows.toString());
            StringBuilder cells=new StringBuilder("pass,row,compact_index,original_index\n");retained.forEach((pass,rs)->rs.forEach((row,is)->{for(int i=0;i<is.size();i++)cells.append(pass).append(',').append(row).append(',').append(i).append(',').append(is.get(i)).append('\n');}));write(out,"retained-indices.csv",cells.toString());
            StringBuilder paths=new StringBuilder("rank,energy,original_graph_state_sequence\n");for(int i=0;i<rawStates.size();i++)paths.append(i).append(',').append(rawEnergies.get(i)).append(",\"").append(Arrays.toString(rawStates.get(i))).append("\"\n");write(out,"native-raw-path-order.csv",paths.toString());
            write(out,"probe.txt","denseCalls="+denseCalls+"\ninferredCalls="+inferredCalls+"\nclosedCalls="+closedCalls+"\nclosedBytes="+closedBytes+"\npeakBytes="+peakBytes+"\ndenseStates="+denseStates+"\nbounds="+bound+"\nposteriorUsable="+posteriorUsable+"\ncompletion="+completion+"\n");
            if(expectedPolicy==BPolicy.INVENTORY_FRONTIER_V5) {
                StringBuilder frontier=new StringBuilder("row,original_fine_cells,source_frontier_cells\n");
                for(int i=0;i<widths.size();i++)frontier.append(i).append(",\"").append(required.get(i)).append("\",\"").append(sourceFrontiers.get(i)).append("\"\n");
                write(out,"source-frontier-census.csv",frontier.toString());
            }
        }
    }
    record Run(ModernTracePipeline.PipelineRun pipeline,Capture capture,Map<String,Number> counters) { }
    static Run execute(WideCompactScenes.Scene scene,FrozenReplayInput input,boolean oracle,boolean compact,Path out)throws Exception {
        return executeInternal(scene,input,oracle,compact,out,TraceBudgets.defaults(),BPolicy.INVENTORY_PREP_V4);
    }
    static Run execute(WideCompactScenes.Scene scene,FrozenReplayInput input,boolean oracle,boolean compact,Path out,BPolicy policy)throws Exception {
        return executeInternal(scene,input,oracle,compact,out,TraceBudgets.defaults(),policy);
    }
    /** Dedicated bounded seam for tiny production-flow tests with a recorded alternate budget. */
    static Run executeTinyTestOnly(WideCompactScenes.Scene scene,FrozenReplayInput input,boolean oracle,boolean compact,
            Path out,TraceBudgets expectedBudgets)throws Exception {
        require(scene!=null&&scene.id().equals("tiny"),"Non-tiny scene cannot use the test-only budget seam");
        require(input!=null&&expectedBudgets!=null&&input.request().budgets().equals(expectedBudgets),
                "Tiny test budget does not match the actual frozen request");
        return executeInternal(scene,input,oracle,compact,out,expectedBudgets,BPolicy.INVENTORY_PREP_V4);
    }
    private static Run executeInternal(WideCompactScenes.Scene scene,FrozenReplayInput input,boolean oracle,boolean compact,
            Path out,TraceBudgets expectedBudgets,BPolicy policy)throws Exception {
        require(expectedBudgets.equals(TraceBudgets.defaults())||scene.id().equals("tiny"),
                "Non-default production budget is restricted to the tiny test seam");
        require(input.request().budgets().equals(expectedBudgets),"Run input budgets differ from the pinned execution budget");
        if(compact)require(BPolicy.recorded(input.request())==policy&&BPolicy.recorded(scene.input().request())==policy,"Compact fixture policy does not match its actual frozen source");
        Files.createDirectories(out);write(out,"input-identity.txt","fixture="+scene.id()+"\ninputHash="+input.canonicalHash()+"\nparameterHash="+input.request().parameterHash()+"\nbackend="+(oracle?BQualityProbe.ORACLE_VERSION:compact?"ordinary recorded "+policy+" candidate":"historical full default production")+"\nstrictHistoricalReplay=false\n");
        Files.write(out.resolve("frozen-public-input.bin"),FrozenReplayCodec.encode(input),StandardOpenOption.CREATE_NEW);
        var capture=new Capture(compact,expectedBudgets,policy);ModernDiagnosticCounters.begin();long start=System.nanoTime();
        try(var probe=BQualityProbe.install(oracle,capture);var observer=AdaptiveBObserver.observe(capture::retained)) {
            require(BQualityProbe.fullOracle()==oracle,"Unexpected oracle selector");
            var pipeline=new ModernTracePipeline((r,e,n,c)->{throw new AssertionError("A engine must not execute");});
            var run=pipeline.runWithUsage(input.request(),input.evidence(),input.network(),input.options(),
                    ()->Thread.currentThread().isInterrupted()||System.nanoTime()-start>240_000_000_000L,Set.of());
            var result=run.result();
            write(out,"native-result.txt","status="+result.inference().status()+"\nexplanation="+result.inference().explanation()+"\nwork="+result.inference().workAccounting()+"\nusage="+run.usage()+"\nroutes="+result.routes().size()+"\n");
            var coordinates=new StringBuilder("route,stage,point,x_m,y_m\n");var quality=new StringBuilder();var assignments=new StringBuilder();
            for(var route:result.routes()) {
                quality.append(route.hypothesis().id()).append(':').append(route.quality()).append('\n');
                for(int stage=0;stage<2;stage++){var points=stage==0?route.rawHypothesis().points():route.hypothesis().points();for(int i=0;i<points.size();i++)coordinates.append(route.hypothesis().id()).append(',').append(stage==0?"raw":"final").append(',').append(i).append(',').append(points.get(i).xMeters()).append(',').append(points.get(i).yMeters()).append('\n');}
                assignments.append(route.hypothesis().id()).append(':').append(route.assignments()).append('\n');
            }
            write(out,"geometry.csv",coordinates.toString());write(out,"quality.txt",quality.toString());write(out,"assignments.txt",assignments.toString());
            return new Run(run,capture,ModernDiagnosticCounters.snapshot());
        }catch(Throwable failure){try(var writer=new PrintWriter(Files.newBufferedWriter(out.resolve("failure.txt"),StandardOpenOption.CREATE_NEW))){failure.printStackTrace(writer);}throw failure;}
        finally{try{write(out,"native-counters.txt",new TreeMap<>(ModernDiagnosticCounters.snapshot())+"\n");capture.dump(out);}finally{ModernDiagnosticCounters.end();}}
    }
    static void candidateProof(Run run,WideCompactScenes.Scene scene) {
        candidateProof(run,scene,TraceBudgets.defaults(),BPolicy.INVENTORY_PREP_V4);
    }
    static void candidateProof(Run run,WideCompactScenes.Scene scene,BPolicy policy) {
        candidateProof(run,scene,TraceBudgets.defaults(),policy);
    }
    static void candidateProofTinyTestOnly(Run run,WideCompactScenes.Scene scene,TraceBudgets expectedBudgets) {
        require(scene!=null&&scene.id().equals("tiny"),"Non-tiny scene cannot use the tiny candidate proof seam");
        candidateProof(run,scene,expectedBudgets,BPolicy.INVENTORY_PREP_V4);
    }
    private static void candidateProof(Run run,WideCompactScenes.Scene scene,TraceBudgets expectedBudgets,BPolicy policy) {
        require(run!=null&&expectedBudgets!=null&&run.capture().expectedBudgets.equals(expectedBudgets),
                "Candidate proof budget differs from the actual captured run");
        require(run.capture().expectedPolicy==policy&&BPolicy.recorded(scene.input().request())==policy,"Candidate proof policy differs from its actual source capture");
        var result=run.pipeline().result().inference();var c=run.capture();
        require(result.status()==TraceHypothesisSet.Status.AMBIGUOUS,"Conditional compact candidate must remain ambiguous, without COMPLETE/global-certainty claim: "+result.status());
        require(!run.pipeline().result().routes().isEmpty(),"Empty native final geometry");
        require(result.workAccounting().orElseThrow().hasPolicy(policy.compactExecutionVersion()),"Candidate was bypassed or used full/oracle backend");
        require(c.denseCalls==1&&c.inferredCalls==1&&c.closedCalls==1&&c.closedBytes==0,"Native execution or ownership receipt missing");
        require(c.widths.size()==scene.input().request().profileChainage().cumulativeGroundMeters().size(),"Physical profile rows changed");
        require(result.evaluatedStates()==c.denseStates,"Native dense state census mismatch");
        require(!c.posteriorUsable&&result.hypotheses().stream().allMatch(h->h.posteriorProbability().isEmpty()&&h.diagnostics().getOrDefault("bPosteriorConditional",0.0)==1),"False full/global posterior claim");
        var declared=DenseBAdmission.validateWitness(DenseBAdmission.witnessFromExplanation(result.explanation()),expectedBudgets,c.widths.size());require(declared.equals(c.bound)&&!declared.admitted()
                &&declared.scoresExceeded()
                &&declared.effectiveK()==Math.min(32,expectedBudgets.maximumRawAlternatives()),"Actual admission/census/K differs");
        require(!c.retained.isEmpty()&&c.retained.keySet().stream().allMatch(i->i==0||i==1),"Refinement count outside0/1");
        int last=c.retained.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow();
        require(run.counters().get("b.adaptive.refinements").intValue()==last,"Recorded refinement differs");var rows=c.retained.get(last);long omitted=0;
        require(rows.size()==c.widths.size(),"Compact graph dropped profile rows");
        for(int row=0;row<c.widths.size();row++) {
            var indices=rows.get(row);require(indices!=null&&!indices.isEmpty(),"Missing retained row "+row);var set=new BitSet(c.widths.get(row));
            for(int index:indices){require(index>=0&&index<c.widths.get(row),"Foreign original cell");set.set(index);}
            var missing=(BitSet)c.required.get(row).clone();missing.andNot(set);require(missing.isEmpty(),"Measured/mandatory cell omitted");omitted+=c.widths.get(row)-set.cardinality();
            if(policy==BPolicy.INVENTORY_FRONTIER_V5) {
                var initial=new BitSet(c.widths.get(row));for(int index:c.retained.get(0).get(row))initial.set(index);
                var frontier=(BitSet)c.sourceFrontiers.get(row).clone();frontier.andNot(initial);
                require(frontier.isEmpty(),"Original source frontier omitted before the first solve");
            }
        }
        require(omitted>0,"Final graph did not genuinely omit original nonmandatory cells");
        var work=result.workAccounting().orElseThrow();require(work.computedTransitions()>0&&work.computedTransitions()==run.pipeline().usage().transitions()
                &&work.computedTransitions()<=expectedBudgets.maximumTransitions()&&run.pipeline().usage().pairVisits()<=expectedBudgets.maximumPairVisits()
                &&run.pipeline().usage().rawAlternatives()<=expectedBudgets.maximumRawAlternatives()
                &&run.pipeline().usage().distinctAlternatives()<=expectedBudgets.maximumDistinctAlternatives()
                &&work.bStages().stream().allMatch(s->s.refusedScoreAttempts()==0),"Native cumulative work is unavailable/refused/untruthful");
    }
    static void safety(Run run,WideCompactScenes.Scene scene,Path out)throws Exception {
        safety(run,scene,scene.input(),out);
    }
    static void safety(Run run,WideCompactScenes.Scene scene,FrozenReplayInput input,Path out)throws Exception {
        Files.createDirectories(out);
        var route=run.pipeline().result().routes().get(0);
        require(route.quality().disposition()!=FinalGeometryEvaluator.Disposition.HARD_BLOCKED,"Hard final geometry finding");
        require(route.quality().findings().stream().noneMatch(f->f.severity()==FinalGeometryEvaluator.Severity.HARD_BLOCK),"Hard native safety finding");
        require(route.assignments().size()==route.pointIds().size()&&new HashSet<>(route.pointIds()).size()==route.pointIds().size(),"Incomplete native assignments");
        var points=route.hypothesis().points();for(int i=0;i<points.size();i++){require(points.get(i).equals(route.assignments().get(route.pointIds().get(i))),"Assignment drift");require(input.evidence().routePositionAuthorized(points.get(i)),"Point outside native authority");if(i>0)require(input.evidence().routeSegmentAuthorized(points.get(i-1),points.get(i)),"Continuous edge outside native authority");}
        var plan=new ModernSingleWayEditPlanAdapter().adaptFrozen(input.request(),input.evidence(),input.network(),input.options(),run.pipeline().result(),0,25);
        var topology=ModernSingleWayEditPlanAdapter.assessFinalTopology(plan,input.evidence(),25);write(out,"topology.txt",topology+"\nplanValidation="+plan.validation()+"\n");
        require(topology.findingCodes().isEmpty()&&plan.validation().disposition()!=ValidationReport.Disposition.HARD_BLOCKED,"Hard/unknown topology or edit-plan failure");
    }
    record Distance(double p95,double maxSample,double maxGap,double certifiedMaximum) { }
    private record Weighted(double distance,double weight) { }
    static Distance compare(List<MetricPoint> a,List<MetricPoint> b,double targetChordError) {
        var values=new ArrayList<Weighted>();double gap=Math.max(sample(a,b,values),sample(b,a,values));values.sort(Comparator.comparingDouble(Weighted::distance));
        double total=values.stream().mapToDouble(Weighted::weight).sum(),sum=0,p95=0,max=0;
        for(var v:values){max=Math.max(max,v.distance());if(sum<.95*total)p95=v.distance();sum+=v.weight();}
        return new Distance(p95,max,gap,max+gap/2+targetChordError+1e-9);
    }
    private static double sample(List<MetricPoint> a,List<MetricPoint> b,List<Weighted> values) {
        double maxGap=0;for(int i=1;i<a.size();i++){var p=a.get(i-1);var q=a.get(i);double length=Math.hypot(q.xMeters()-p.xMeters(),q.yMeters()-p.yMeters());if(length==0)continue;int n=(int)Math.ceil(length/GAP);double gap=length/n;maxGap=Math.max(maxGap,gap);
            for(int j=0;j<=n;j++){double t=(double)j/n;var point=new MetricPoint(p.xMeters()+t*(q.xMeters()-p.xMeters()),p.yMeters()+t*(q.yMeters()-p.yMeters()));values.add(new Weighted(distance(point,b),gap*(j==0||j==n?.5:1)));}}
        return maxGap;
    }
    private static double distance(MetricPoint p,List<MetricPoint> curve) {
        double best=Double.POSITIVE_INFINITY;
        for(int i=1;i<curve.size();i++){var a=curve.get(i-1);var b=curve.get(i);double dx=b.xMeters()-a.xMeters(),dy=b.yMeters()-a.yMeters(),den=dx*dx+dy*dy;double t=den==0?0:Math.max(0,Math.min(1,((p.xMeters()-a.xMeters())*dx+(p.yMeters()-a.yMeters())*dy)/den));double x=p.xMeters()-a.xMeters()-t*dx,y=p.yMeters()-a.yMeters()-t*dy;best=Math.min(best,x*x+y*y);}
        return Math.sqrt(best);
    }
    static void analytic(Run run,WideCompactScenes.Scene scene,Path out)throws Exception {
        Files.createDirectories(out);
        var points=run.pipeline().result().routes().get(0).hypothesis().points();var d=compare(points,scene.analytic(),ANALYTIC_CHORD_ERROR);write(out,"analytic-distance.txt",d+"\np95=bidirectional trapezoidal arc-length-weighted left empirical quantile; gap<=p/256; analytic chord error<=p/10000\n");
        qualityRequire(d.certifiedMaximum()<=P&&d.p95()<=(scene.strong()?.5*P:P),"Unsuitable analytic fixture/reference or failed analytic geometry: "+scene.id()+" "+d);
        if(scene.id().startsWith("remote-drift")){var central=points.stream().filter(p->p.xMeters()>.4*scene.length()&&p.xMeters()<.6*scene.length()).toList();qualityRequire(!central.isEmpty()&&central.stream().allMatch(p->p.yMeters()>20),"Remote corridor identity/reach lost");}
        if(scene.id().equals("faint-apex")){double apex=points.stream().filter(p->Math.abs(p.xMeters()-50)<=2*P).mapToDouble(MetricPoint::yMeters).max().orElseThrow();qualityRequire(apex>=2.7&&Math.abs(apex-3)<=P,"Faint supported amplitude/apex lost");}
        if(scene.hairpin()) {
            double previous=-Double.MAX_VALUE;boolean lower=false,upper=false,apex=false;
            for(var p:points){double s=WideCompactScenes.hairpinChainage(p);qualityRequire(s>=previous-.25*P,"Hairpin ordering/leg switch");previous=Math.max(previous,s);lower|=p.xMeters()>.25*WideCompactScenes.LEG&&p.xMeters()<.75*WideCompactScenes.LEG&&Math.abs(p.yMeters()+WideCompactScenes.R)<=P;upper|=p.xMeters()>.25*WideCompactScenes.LEG&&p.xMeters()<.75*WideCompactScenes.LEG&&Math.abs(p.yMeters()-WideCompactScenes.R)<=P;apex|=p.xMeters()>=WideCompactScenes.LEG+WideCompactScenes.R-P&&Math.abs(p.yMeters())<=2*P;}qualityRequire(lower&&upper&&apex,"Hairpin shortcut/lost leg/apex");
        }
        if(scene.id().equals("distinct-parallel"))for(var p:points)qualityRequire(Math.abs(p.yMeters()+4*P)<=P,"Parallel corridor switch/midpoint/impulse excursion");
        if(scene.id().equals("diffuse-three-strand")) {
            var extrema=new ArrayList<MetricPoint>();for(int i=1;i+1<points.size();i++){var a=points.get(i-1);var b=points.get(i);var c=points.get(i+1);if((b.yMeters()-a.yMeters())*(c.yMeters()-b.yMeters())<0)extrema.add(b);}
            for(int i=1;i<extrema.size();i++){var a=extrema.get(i-1);var b=extrema.get(i);qualityRequire(!(Math.abs(a.xMeters()-b.xMeters())<=4*P&&Math.abs(a.yMeters()-b.yMeters())>=.5*P),"Resolved alternating short-wave turn introduced into straight mean");}
        }
    }
    static FrozenReplayInput historical(FrozenReplayInput f) {
        var r=f.request();var exact=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),r.budgets(),r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),r.settingsHash(),BPolicy.visibleExactParameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),r.corridorInput());
        return new FrozenReplayInput(exact,f.evidence(),f.network(),f.options(),25,null);
    }
    static String manifest(){var s=new StringBuilder("spec="+WideCompactScenes.SPEC+"\n");for(String id:WideCompactScenes.IDS)s.append(id).append('=').append(WideCompactScenes.scene(id).input().canonicalHash()).append('\n');return s.toString();}
    public static void main(String[] args)throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());
        if(args.length==2&&args[0].equals("--manifest")){Files.writeString(Path.of(args[1]),manifest(),StandardOpenOption.CREATE_NEW);return;}
        var invocation=invocation(args);boolean diagnostic=invocation.diagnostic();Path out=invocation.output();Files.createDirectories(out);require(!Files.exists(out.resolve("summary.json")),"Existing evidence must not be overwritten");
        var report=new WideCompactDiagnosticFlow.Report();for(String id:WideCompactScenes.IDS)report.ensureScene(id);
        Throwable failure=null;var drift=new AtomicReference<List<MetricPoint>>();String[] currentStage={"fixed-input-preflight"};
        try {
            write(out,"runtime.txt","sourceCommit="+System.getenv().getOrDefault("GITHUB_SHA","unavailable-local")+"\njavaVersion="+System.getProperty("java.version")+"\nnumericalParameters="+EvidenceModelParameters.defaults().version()+"\nsceneSpecification="+WideCompactScenes.SPEC+"\ndiagnosticOnly="+diagnostic+"\nreleaseQualified=false\n");
            var expected=new Properties();try(var in=WideCompactQualityMain.class.getResourceAsStream(MANIFEST)){require(in!=null,"Missing frozen public scene hashes");expected.load(in);}
            require(WideCompactScenes.SPEC.equals(expected.getProperty("spec")),"Scene specification changed after freeze");
            for(String id:WideCompactScenes.IDS)require(WideCompactScenes.scene(id).input().canonicalHash().equals(expected.getProperty(id)),"Frozen scene hash changed: "+id);
            for(String id:WideCompactScenes.IDS) {
                var scene=WideCompactScenes.scene(id);var dir=out.resolve(id);Files.createDirectories(dir);
                final Run[] reference={null},candidate={null};final boolean[] referenceAnalyticFailed={false},candidateAnalyticFailed={false};
                currentStage[0]=id+":reference-execution";
                WideCompactDiagnosticFlow.runAssessment(report,id,WideCompactDiagnosticFlow.Assessment.REFERENCE,currentStage[0],diagnostic,false,false,()->{
                    Run r=execute(scene,historical(scene.input()),false,false,dir.resolve("historical-full"));if(r.pipeline().result().inference().status()==TraceHypothesisSet.Status.RESOURCE_LIMIT)r=execute(scene,scene.input(),true,false,dir.resolve("full-cached-v4-oracle"));reference[0]=r;
                    var inf=r.pipeline().result().inference();
                    if(r.pipeline().result().routes().isEmpty()){report.markUnavailable(id,WideCompactDiagnosticFlow.Assessment.COMPARISON);throw new IllegalStateException("Completed full reference unavailable");}
                    require((inf.status()==TraceHypothesisSet.Status.COMPLETE||inf.status()==TraceHypothesisSet.Status.AMBIGUOUS)&&r.capture().posteriorUsable&&r.capture().retained.isEmpty(),"Full reference is incomplete/conditional/sparse");
                    require(inf.workAccounting().orElseThrow().bStages().stream().allMatch(x->x.refusedScoreAttempts()==0),"Full reference has a refused score attempt");
                    require(r.capture().closedBytes==0&&r.capture().inferredCalls==1,"Reference execution/ownership missing");
                    require(r.capture().bound!=null&&!r.capture().bound.admitted()&&r.capture().bound.scoresExceeded(),"Frozen wide scene does not naturally exercise compact admission");
                });
                currentStage[0]=id+":reference-safety";
                WideCompactDiagnosticFlow.runAssessment(report,id,WideCompactDiagnosticFlow.Assessment.REFERENCE_SAFETY,currentStage[0],diagnostic,false,false,()->safety(reference[0],scene,dir.resolve("reference-safety")));
                currentStage[0]=id+":reference-analytic";
                referenceAnalyticFailed[0]=!WideCompactDiagnosticFlow.runAssessment(report,id,WideCompactDiagnosticFlow.Assessment.REFERENCE_ANALYTIC,currentStage[0],diagnostic,true,false,()->analytic(reference[0],scene,dir.resolve("reference-analytic")));
                currentStage[0]=id+":candidate-execution";
                WideCompactDiagnosticFlow.runAssessment(report,id,WideCompactDiagnosticFlow.Assessment.CANDIDATE,currentStage[0],diagnostic,false,false,()->candidate[0]=execute(scene,scene.input(),false,true,dir.resolve("candidate")));
                currentStage[0]=id+":candidate-branch-proof";
                WideCompactDiagnosticFlow.runAssessment(report,id,WideCompactDiagnosticFlow.Assessment.CANDIDATE_BRANCH,currentStage[0],diagnostic,false,false,()->candidateProof(candidate[0],scene));
                currentStage[0]=id+":candidate-safety";
                WideCompactDiagnosticFlow.runAssessment(report,id,WideCompactDiagnosticFlow.Assessment.CANDIDATE_SAFETY,currentStage[0],diagnostic,false,false,()->{
                    safety(candidate[0],scene,dir.resolve("candidate-safety"));
                    if(id.equals("remote-drift"))drift.set(candidate[0].pipeline().result().routes().get(0).hypothesis().points());
                });
                currentStage[0]=id+":candidate-analytic";
                candidateAnalyticFailed[0]=!WideCompactDiagnosticFlow.runAssessment(report,id,WideCompactDiagnosticFlow.Assessment.CANDIDATE_ANALYTIC,currentStage[0],diagnostic,true,false,()->analytic(candidate[0],scene,dir.resolve("candidate-analytic")));
                currentStage[0]=id+":reference-physical-comparison";
                boolean comparisonDiagnostic=referenceAnalyticFailed[0]||candidateAnalyticFailed[0];
                if(reference[0]==null||reference[0].pipeline().result().routes().isEmpty())report.markUnavailable(id,WideCompactDiagnosticFlow.Assessment.COMPARISON);
                else WideCompactDiagnosticFlow.runAssessment(report,id,WideCompactDiagnosticFlow.Assessment.COMPARISON,currentStage[0],diagnostic,true,comparisonDiagnostic,()->{
                    Run c=candidate[0],r=reference[0];var raw=c.pipeline().result().routes().get(0).rawHypothesis().points();var oldRaw=r.pipeline().result().routes().get(0).rawHypothesis().points();require(raw.size()==oldRaw.size(),"Raw physical profile rows differ");
                    double[] distances=new double[raw.size()];for(int i=0;i<raw.size();i++)distances[i]=Math.hypot(raw.get(i).xMeters()-oldRaw.get(i).xMeters(),raw.get(i).yMeters()-oldRaw.get(i).yMeters());Arrays.sort(distances);
                    double p95=distances[(int)Math.ceil(.95*distances.length)-1],maximum=distances[distances.length-1];double p95Limit=(scene.strong()?.25:.5)*P,maxLimit=(scene.strong()?.5:1)*P;
                    var finalPoints=c.pipeline().result().routes().get(0).hypothesis().points();var comparison=compare(finalPoints,r.pipeline().result().routes().get(0).hypothesis().points(),0);
                    write(dir,"reference-comparison.txt","rawP95="+p95+"\nrawMaximum="+maximum+"\nfinal="+comparison+"\np95Limit="+p95Limit+"\nmaximumLimit="+maxLimit+"\ncomparisonBaseline="+(comparisonDiagnostic?"DIAGNOSTIC_ONLY":"VALID_REFERENCE")+"\n");
                    qualityRequire(p95<=p95Limit&&maximum<=maxLimit&&comparison.p95()<=p95Limit&&comparison.certifiedMaximum()<=maxLimit,"Compact quality loss exceeds fixed physical bounds: "+id);
                });
                if(id.equals("remote-drift-reversed")) {
                    currentStage[0]=id+":physical-reversal-comparison";
                    WideCompactDiagnosticFlow.runAssessment(report,id,WideCompactDiagnosticFlow.Assessment.REVERSAL,currentStage[0],diagnostic,true,comparisonDiagnostic,()->{
                        var finalPoints=candidate[0].pipeline().result().routes().get(0).hypothesis().points();require(drift.get()!=null,"Forward drift comparison unavailable");var reversal=compare(finalPoints,drift.get(),0);
                        write(dir,"reversal-comparison.txt",reversal+"\n");qualityRequire(reversal.p95()<=.25*P&&reversal.certifiedMaximum()<=.5*P,"Physical drift reversal changed corridor");
                    });
                }
            }
        } catch(Throwable caught) {
            failure=caught;
            if(report.firstFailure()==null)report.preserve("driver",WideCompactDiagnosticFlow.Assessment.REFERENCE,currentStage[0],caught);
        } finally {
            report.write(out,diagnostic);
        }
        if(WideCompactDiagnosticFlow.exitFailed(report))throw new AssertionError(diagnostic?"Diagnostic-only wide compact assessment failed; release remains unqualified":"Public wide compact qualification stopped; preserved first failure",failure);
    }
    private WideCompactQualityMain() { }
}
