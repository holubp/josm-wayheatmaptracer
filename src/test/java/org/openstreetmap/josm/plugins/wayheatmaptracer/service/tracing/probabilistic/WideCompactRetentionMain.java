package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;
import org.openstreetmap.josm.plugins.wayheatmaptracer.diagnostics.replay.format15.FrozenReplayInput;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;

/** Separately named public performance-retention assessment over actual native full/reference and compact/candidate runs. */
public final class WideCompactRetentionMain {
    static final String DEFAULT_OUTPUT="build/reports/wide-compact-retention";
    private static final double P=WideCompactScenes.P;
    private static final double AMPLITUDE_LEFT=50-2*P, AMPLITUDE_RIGHT=50+2*P, AMPLITUDE_BASELINE=0.0;
    record Invocation(Path output) { }
    record Geometry(WideCompactRetentionAssessment.GeometryOracle oracle, WideCompactRetentionFlow.Status branchStatus,
            WideCompactRetentionFlow.Status measurementStatus, WideCompactQualityMain.Distance distance) { }
    record PairEvaluation(WideCompactRetentionAssessment.Input input, WideCompactRetentionAssessment.Result result,
            WideCompactRetentionFlow.SceneRecord record, WideCompactQualityMain.Run referenceRun, WideCompactQualityMain.Run candidateRun) { }

    static Invocation invocation(String[] args) {
        if(args.length<1||args.length>2||!"--retention".equals(args[0]))throw new IllegalArgumentException("Expected --retention [output]");
        return new Invocation(Path.of(args.length==1?DEFAULT_OUTPUT:args[1]));
    }

    /** Measure an already executed pair. This method never runs either solver. */
    static PairEvaluation measurePair(WideCompactScenes.Scene scene, FrozenReplayInput referenceInput,
            WideCompactQualityMain.Run reference, String referenceBackend, FrozenReplayInput candidateInput,
            WideCompactQualityMain.Run candidate, WideCompactRetentionFlow.SceneRecord record, Path output) throws IOException {
        // Preserve factual counters even when a later measurement or filesystem operation fails.
        record.setFacts(facts(referenceBackend,referenceInput,reference),facts("INVENTORY_COMPACT_CANDIDATE",candidateInput,candidate));
        var refExpected=referenceBackend.equals("CACHED_V4_ORACLE_AFTER_HISTORICAL_RESOURCE_LIMIT")
                ?scene.input():WideCompactQualityMain.historical(scene.input());
        markIdentity(record,WideCompactRetentionFlow.Stage.REFERENCE_IDENTITY,scene,refExpected,referenceInput,reference,"reference");
        markIdentity(record,WideCompactRetentionFlow.Stage.CANDIDATE_IDENTITY,scene,candidateInput,candidateInput,candidate,"candidate");
        verifyReferenceCompletion(reference,referenceInput,referenceBackend,record);
        verifyCandidateExecution(scene,candidate,candidateInput.request().budgets(),record);
        try { WideCompactQualityMain.safety(reference,scene,referenceInput,output.resolve("reference-safety"));record.mark(WideCompactRetentionFlow.Stage.REFERENCE_NATIVE_AUTHORITY,WideCompactRetentionFlow.Status.PASS,"actual native finalization, assignment, authority and topology checks passed"); }
        catch(Throwable e) { record.fail(WideCompactRetentionFlow.Stage.REFERENCE_NATIVE_AUTHORITY,e); }
        try { WideCompactQualityMain.safety(candidate,scene,candidateInput,output.resolve("candidate-safety"));record.mark(WideCompactRetentionFlow.Stage.CANDIDATE_NATIVE_AUTHORITY,WideCompactRetentionFlow.Status.PASS,"actual native finalization, assignment, authority and topology checks passed");record.mark(WideCompactRetentionFlow.Stage.CANDIDATE_SAFETY,WideCompactRetentionFlow.Status.PASS,"candidate safety passed"); }
        catch(Throwable e) { record.fail(WideCompactRetentionFlow.Stage.CANDIDATE_NATIVE_AUTHORITY,e);record.fail(WideCompactRetentionFlow.Stage.CANDIDATE_SAFETY,e); }
        Geometry referenceGeometry=geometry(reference,scene,output.resolve("reference-geometry"));
        Geometry candidateGeometry=geometry(candidate,scene,output.resolve("candidate-geometry"));
        record.mark(WideCompactRetentionFlow.Stage.REFERENCE_GEOMETRY,referenceGeometry.measurementStatus(),"analytic geometry measured from actual reference final route");
        record.mark(WideCompactRetentionFlow.Stage.REFERENCE_BRANCH,referenceGeometry.branchStatus(),"independent scene branch and anti-zigzag checks");
        record.mark(WideCompactRetentionFlow.Stage.CANDIDATE_GEOMETRY,candidateGeometry.measurementStatus(),"analytic geometry measured from actual candidate final route");
        record.mark(WideCompactRetentionFlow.Stage.CANDIDATE_BRANCH,candidateGeometry.branchStatus(),"independent scene branch and anti-zigzag checks");
        if(candidateGeometry.branchStatus()==WideCompactRetentionFlow.Status.FAILED)
            record.fail(WideCompactRetentionFlow.Stage.CANDIDATE_BRANCH,new AssertionError("candidate branch/anti-zigzag proof failed"));
        if(candidateGeometry.measurementStatus()==WideCompactRetentionFlow.Status.FAILED)
            record.fail(WideCompactRetentionFlow.Stage.CANDIDATE_GEOMETRY,new AssertionError("candidate analytic geometry proof failed"));

        var distances=distances(referenceInput,candidateInput,reference,candidate);
        boolean supportedBend=scene.id().equals("faint-apex");
        WideCompactRetentionAssessment.Amplitudes amplitudes=supportedBend?amplitudes(reference,candidate):null;
        WideCompactRetentionAssessment.AbsoluteAmplitude absolute=supportedBend?absolute(amplitudes):null;
        boolean comparisonComplete=distances!=null&&valid(distances.rawP95())&&valid(distances.rawCertifiedMaximum())&&valid(distances.finalP95())&&valid(distances.finalCertifiedMaximum());
        record.mark(WideCompactRetentionFlow.Stage.COMPARISON,comparisonComplete?WideCompactRetentionFlow.Status.PASS:WideCompactRetentionFlow.Status.UNAVAILABLE,
                comparisonComplete?"all four raw/final distance metrics measured from in-memory native outputs":"one or more required raw/final comparison metrics unavailable");
        var input=new WideCompactRetentionAssessment.Input(scene.id(),scene.strong()?WideCompactRetentionAssessment.Strength.STRONG:WideCompactRetentionAssessment.Strength.WEAK,
                record.runnerChecks(),distances,supportedBend,supportedBend,amplitudes,absolute,referenceGeometry.oracle(),candidateGeometry.oracle());
        var result=record.setAssessment(input);
        return new PairEvaluation(input,result,record,reference,candidate);
    }

    /** Small actual-pipeline full/compact pair for orchestration tests; never used by the public six-scene gate. */
    static PairEvaluation executeTinyProductionPair(Path output) throws Exception {
        var scene=WideCompactScenes.tinyRetention();
        var referenceInput=WideCompactQualityMain.historical(scene.input());
        var reference=WideCompactQualityMain.execute(scene,referenceInput,false,false,output.resolve("reference"));
        var candidateInput=tinyCompactInput(scene.input());
        var candidate=WideCompactQualityMain.executeTinyTestOnly(scene,candidateInput,false,true,output.resolve("candidate"),candidateInput.request().budgets());
        var record=new WideCompactRetentionFlow.SceneRecord(scene.id());
        record.mark(WideCompactRetentionFlow.Stage.REFERENCE_EXECUTION,WideCompactRetentionFlow.Status.PASS,"actual tiny full production run");
        record.mark(WideCompactRetentionFlow.Stage.CANDIDATE_EXECUTION,WideCompactRetentionFlow.Status.PASS,"actual tiny production candidate run");
        return measurePair(scene,referenceInput,reference,"HISTORICAL_FULL_PRODUCTION",candidateInput,candidate,record,output.resolve("assessment"));
    }

    private static FrozenReplayInput tinyCompactInput(FrozenReplayInput source) {
        // Eight native rows make the conservative K32 score bound exceed 1M, while the actual
        // V4 compact solve fits. Keep the ordinary state/pair/alternative budgets fully funded.
        // No production budget, profile evidence, selector or public six-scene input changes.
        var r=source.request();var exact=new TraceRequest(r.selectedWayKey(),r.selectedRange(),r.engine(),r.geometryMode(),r.permissions(),
                new TraceBudgets(96,8_000_000,1_000_000,32,8),r.evidenceSnapshotId(),r.evidenceContentHash(),r.networkSnapshotId(),r.networkContentHash(),
                r.settingsHash(),BPolicy.visibleExactParameterHash(),r.samplerId(),r.configuredSampleStepMeters(),r.profileChainage(),r.evidenceResolution(),
                r.corridorInput(),r.disconnectionSource());
        var request=BPolicy.adaptiveRequest(exact,BPolicy.INVENTORY_PREP_V4);
        return new FrozenReplayInput(request,source.evidence(),source.network(),source.options(),source.planSearchRadiusMeters(),source.legacyB());
    }

    private static void markIdentity(WideCompactRetentionFlow.SceneRecord record,WideCompactRetentionFlow.Stage stage,
            WideCompactScenes.Scene scene,FrozenReplayInput expected,FrozenReplayInput actual,WideCompactQualityMain.Run run,String role) {
        boolean okay=expected!=null&&actual!=null&&run!=null&&expected.canonicalHash().equals(actual.canonicalHash())
                &&expected.evidence().canonicalHash().equals(actual.evidence().canonicalHash())
                &&expected.network().canonicalHash().equals(actual.network().canonicalHash())
                &&actual.evidence().canonicalHash().equals(scene.input().evidence().canonicalHash())
                &&actual.network().canonicalHash().equals(scene.input().network().canonicalHash())
                &&actual.request().profileChainage().equals(scene.input().request().profileChainage())
                &&actual.request().evidenceSnapshotId().equals(scene.input().request().evidenceSnapshotId())
                &&actual.request().networkSnapshotId().equals(scene.input().request().networkSnapshotId());
        if(okay)record.mark(stage,WideCompactRetentionFlow.Status.PASS,role+" canonical/input/source identities match the frozen scene");
        else if(expected==null||actual==null||run==null)record.unavailable(stage,role+" frozen input or actual run missing");
        else record.fail(stage,new AssertionError(role+" canonical/input/source identity mismatch"));
    }

    private static void verifyReferenceCompletion(WideCompactQualityMain.Run run,FrozenReplayInput input,String backend,WideCompactRetentionFlow.SceneRecord record) {
        if(run==null) { record.unavailable(WideCompactRetentionFlow.Stage.REFERENCE_EXECUTION,"reference execution missing");record.unavailable(WideCompactRetentionFlow.Stage.REFERENCE_DENSE_COMPLETE,"reference missing");return; }
        var inf=run.pipeline().result().inference();ExecutionWorkReceipt work=inf.workAccounting().orElse(null);
        String expectedPolicy=backend.equals("CACHED_V4_ORACLE_AFTER_HISTORICAL_RESOURCE_LIMIT")?BQualityProbe.ORACLE_VERSION:BPolicy.recorded(input.request()).version();
        boolean complete=!run.pipeline().result().routes().isEmpty()
                &&(inf.status()==TraceHypothesisSet.Status.COMPLETE||inf.status()==TraceHypothesisSet.Status.AMBIGUOUS)
                &&run.capture().posteriorUsable&&run.capture().retained.isEmpty()&&run.capture().inferredCalls==1
                &&run.capture().closedBytes==0&&work!=null&&work.bStages().stream().allMatch(w->w.refusedScoreAttempts()==0)
                &&work.hasPolicy(expectedPolicy);
        if(complete) {
            record.mark(WideCompactRetentionFlow.Stage.REFERENCE_EXECUTION,WideCompactRetentionFlow.Status.PASS,"actual complete full reference execution");
            record.mark(WideCompactRetentionFlow.Stage.REFERENCE_DENSE_COMPLETE,WideCompactRetentionFlow.Status.PASS,"complete dense reference with usable posterior and closed ownership");
        } else {
            var failure=new AssertionError("reference incomplete, conditional, refused, or retained native work");
            record.fail(WideCompactRetentionFlow.Stage.REFERENCE_EXECUTION,failure);
            record.fail(WideCompactRetentionFlow.Stage.REFERENCE_DENSE_COMPLETE,failure);
        }
    }

    private static void verifyCandidateExecution(WideCompactScenes.Scene scene,WideCompactQualityMain.Run run,
            TraceBudgets expectedBudgets,WideCompactRetentionFlow.SceneRecord record) {
        if(run==null) { record.unavailable(WideCompactRetentionFlow.Stage.CANDIDATE_EXECUTION,"candidate execution missing");return; }
        try {
            if(scene.id().equals("tiny")) WideCompactQualityMain.candidateProofTinyTestOnly(run,scene,expectedBudgets);
            else WideCompactQualityMain.candidateProof(run,scene);
            record.mark(WideCompactRetentionFlow.Stage.CANDIDATE_EXECUTION,WideCompactRetentionFlow.Status.PASS,"actual production compact candidate proof passed");
        } catch(Throwable e) { record.fail(WideCompactRetentionFlow.Stage.CANDIDATE_EXECUTION,e); }
    }

    private static Geometry geometry(WideCompactQualityMain.Run run,WideCompactScenes.Scene scene,Path out) throws IOException {
        Files.createDirectories(out);
        if(run==null||run.pipeline().result().routes().isEmpty())return new Geometry(new WideCompactRetentionAssessment.GeometryOracle(null,null,WideCompactRetentionAssessment.Evidence.UNAVAILABLE),WideCompactRetentionFlow.Status.UNAVAILABLE,WideCompactRetentionFlow.Status.UNAVAILABLE,null);
        var points=run.pipeline().result().routes().get(0).hypothesis().points();
        if(points.size()<2||scene.analytic().size()<2)return new Geometry(new WideCompactRetentionAssessment.GeometryOracle(null,null,WideCompactRetentionAssessment.Evidence.UNAVAILABLE),WideCompactRetentionFlow.Status.UNAVAILABLE,WideCompactRetentionFlow.Status.UNAVAILABLE,null);
        var distance=WideCompactQualityMain.compare(points,scene.analytic(),WideCompactQualityMain.ANALYTIC_CHORD_ERROR);
        WideCompactRetentionFlow.Status branch=branchProof(points,scene);
        var oracle=new WideCompactRetentionAssessment.GeometryOracle(metric(distance.p95()),metric(distance.certifiedMaximum()),evidence(branch));
        boolean geometryPass=distance.certifiedMaximum()<=P&&distance.p95()<=(scene.strong()?.5*P:P);
        var geometryStatus=branch==WideCompactRetentionFlow.Status.UNAVAILABLE?WideCompactRetentionFlow.Status.UNAVAILABLE:
                branch==WideCompactRetentionFlow.Status.FAILED||!geometryPass?WideCompactRetentionFlow.Status.FAILED:WideCompactRetentionFlow.Status.PASS;
        Files.writeString(out.resolve("analytic-distance.txt"),distance+"\nindependentBranchStatus="+branch+"\n");
        return new Geometry(oracle,branch,geometryStatus,distance);
    }

    /** Scene-specific branch evidence is measured separately from the faint absolute-apex requirement. */
    static WideCompactRetentionFlow.Status branchProof(List<MetricPoint> points,WideCompactScenes.Scene scene) {
        try {
            if(points==null||points.size()<2)return WideCompactRetentionFlow.Status.UNAVAILABLE;
            if(scene.id().startsWith("remote-drift")) {
                var central=points.stream().filter(p->p.xMeters()>.4*scene.length()&&p.xMeters()<.6*scene.length()).toList();
                WideCompactQualityMain.qualityRequire(!central.isEmpty()&&central.stream().allMatch(p->p.yMeters()>20),"Remote corridor identity/reach lost");
            }
            if(scene.hairpin()) {
                double previous=-Double.MAX_VALUE;boolean lower=false,upper=false,apex=false;
                for(var p:points){double s=WideCompactScenes.hairpinChainage(p);WideCompactQualityMain.qualityRequire(s>=previous-.25*P,"Hairpin ordering/leg switch");previous=Math.max(previous,s);lower|=p.xMeters()>.25*WideCompactScenes.LEG&&p.xMeters()<.75*WideCompactScenes.LEG&&Math.abs(p.yMeters()+WideCompactScenes.R)<=P;upper|=p.xMeters()>.25*WideCompactScenes.LEG&&p.xMeters()<.75*WideCompactScenes.LEG&&Math.abs(p.yMeters()-WideCompactScenes.R)<=P;apex|=p.xMeters()>=WideCompactScenes.LEG+WideCompactScenes.R-P&&Math.abs(p.yMeters())<=2*P;}
                WideCompactQualityMain.qualityRequire(lower&&upper&&apex,"Hairpin shortcut/lost leg/apex");
            }
            if(scene.id().equals("distinct-parallel"))for(var p:points)WideCompactQualityMain.qualityRequire(Math.abs(p.yMeters()+4*P)<=P,"Parallel corridor switch/midpoint/impulse excursion");
            if(scene.id().equals("diffuse-three-strand")) {
                var extrema=new ArrayList<MetricPoint>();
                for(int i=1;i+1<points.size();i++){var a=points.get(i-1);var b=points.get(i);var c=points.get(i+1);if((b.yMeters()-a.yMeters())*(c.yMeters()-b.yMeters())<0)extrema.add(b);}
                for(int i=1;i<extrema.size();i++){var a=extrema.get(i-1);var b=extrema.get(i);WideCompactQualityMain.qualityRequire(!(Math.abs(a.xMeters()-b.xMeters())<=4*P&&Math.abs(a.yMeters()-b.yMeters())>=.5*P),"Resolved alternating short-wave turn introduced into straight mean");}
            }
            return WideCompactRetentionFlow.Status.PASS;
        } catch(WideCompactQualityMain.QualityFailure failure) { return WideCompactRetentionFlow.Status.FAILED; }
    }

    static WideCompactRetentionAssessment.Distances distances(FrozenReplayInput referenceInput,FrozenReplayInput candidateInput,
            WideCompactQualityMain.Run reference,WideCompactQualityMain.Run candidate) {
        if(reference==null||candidate==null||reference.pipeline().result().routes().isEmpty()||candidate.pipeline().result().routes().isEmpty())return unavailableDistances();
        if(referenceInput==null||candidateInput==null||!referenceInput.request().profileChainage().cumulativeGroundMeters()
                .equals(candidateInput.request().profileChainage().cumulativeGroundMeters()))return unavailableDistances();
        var r=reference.pipeline().result().routes().get(0);var c=candidate.pipeline().result().routes().get(0);
        var rawReference=r.rawHypothesis().points();var rawCandidate=c.rawHypothesis().points();
        int rows=referenceInput.request().profileChainage().cumulativeGroundMeters().size();
        if(rawReference.size()!=rawCandidate.size()||rawReference.size()!=rows||rawReference.isEmpty()
                ||r.hypothesis().points().size()<2||c.hypothesis().points().size()<2)return unavailableDistances();
        double[] raw=new double[rawReference.size()];
        for(int i=0;i<raw.length;i++)raw[i]=Math.hypot(rawReference.get(i).xMeters()-rawCandidate.get(i).xMeters(),rawReference.get(i).yMeters()-rawCandidate.get(i).yMeters());
        Arrays.sort(raw);double p95=raw[(int)Math.ceil(.95*raw.length)-1],maximum=raw[raw.length-1];
        var finalDistance=WideCompactQualityMain.compare(c.hypothesis().points(),r.hypothesis().points(),0);
        return new WideCompactRetentionAssessment.Distances(metric(p95),metric(maximum),metric(finalDistance.p95()),metric(finalDistance.certifiedMaximum()));
    }

    private static WideCompactRetentionAssessment.Distances unavailableDistances() {
        var m=new WideCompactRetentionAssessment.Metric(null,WideCompactRetentionAssessment.Evidence.UNAVAILABLE);
        return new WideCompactRetentionAssessment.Distances(m,m,m,m);
    }

    static WideCompactRetentionAssessment.Amplitudes amplitudes(WideCompactQualityMain.Run reference,WideCompactQualityMain.Run candidate) {
        Double referenceAmplitude=amplitude(reference,AMPLITUDE_LEFT,AMPLITUDE_RIGHT,AMPLITUDE_BASELINE);
        Double candidateAmplitude=amplitude(candidate,AMPLITUDE_LEFT,AMPLITUDE_RIGHT,AMPLITUDE_BASELINE);
        String interval="x=[50-2p,50+2p]m;p="+P;
        if(referenceAmplitude==null||candidateAmplitude==null)return new WideCompactRetentionAssessment.Amplitudes(
                new WideCompactRetentionAssessment.Metric(candidateAmplitude,WideCompactRetentionAssessment.Evidence.UNAVAILABLE),
                new WideCompactRetentionAssessment.Metric(referenceAmplitude,WideCompactRetentionAssessment.Evidence.UNAVAILABLE),interval,interval,"y=0m","y=0m");
        return new WideCompactRetentionAssessment.Amplitudes(metric(candidateAmplitude),metric(referenceAmplitude),interval,interval,"y=0m","y=0m");
    }

    private static Double amplitude(WideCompactQualityMain.Run run,double left,double right,double baseline) {
        if(run==null||run.pipeline().result().routes().isEmpty())return null;
        var points=run.pipeline().result().routes().get(0).hypothesis().points();
        if(points.size()<2||points.get(0).xMeters()>left||points.get(points.size()-1).xMeters()<right)return null;
        double maximum=Double.NEGATIVE_INFINITY;
        for(var point:points)if(point.xMeters()>=left&&point.xMeters()<=right)maximum=Math.max(maximum,point.yMeters());
        for(int i=1;i<points.size();i++) {
            var a=points.get(i-1);var b=points.get(i);
            if(a.xMeters()<=left&&b.xMeters()>=left&&b.xMeters()!=a.xMeters())maximum=Math.max(maximum,interpolateY(a,b,left));
            if(a.xMeters()<=right&&b.xMeters()>=right&&b.xMeters()!=a.xMeters())maximum=Math.max(maximum,interpolateY(a,b,right));
        }
        return Double.isFinite(maximum)?maximum-baseline:null;
    }
    private static double interpolateY(MetricPoint a,MetricPoint b,double x) { double t=(x-a.xMeters())/(b.xMeters()-a.xMeters());return a.yMeters()+t*(b.yMeters()-a.yMeters()); }
    private static WideCompactRetentionAssessment.AbsoluteAmplitude absolute(WideCompactRetentionAssessment.Amplitudes a) {
        if(a==null)return null;
        return new WideCompactRetentionAssessment.AbsoluteAmplitude(a.candidate(),a.reference(),metric(3),metric(2.7),a.candidateInterval(),a.candidateBaseline());
    }
    private static WideCompactRetentionAssessment.Metric metric(double value) {
        return Double.isFinite(value)&&value>=0?new WideCompactRetentionAssessment.Metric(value,WideCompactRetentionAssessment.Evidence.RUNNER_VALIDATED)
                :new WideCompactRetentionAssessment.Metric(null,WideCompactRetentionAssessment.Evidence.UNAVAILABLE);
    }
    private static boolean valid(WideCompactRetentionAssessment.Metric metric) { return metric!=null&&metric.evidence()==WideCompactRetentionAssessment.Evidence.RUNNER_VALIDATED&&metric.value()!=null&&Double.isFinite(metric.value())&&metric.value()>=0; }
    private static WideCompactRetentionAssessment.Evidence evidence(WideCompactRetentionFlow.Status status) {
        return switch(status) { case PASS -> WideCompactRetentionAssessment.Evidence.RUNNER_VALIDATED;case FAILED -> WideCompactRetentionAssessment.Evidence.FAILED;default -> WideCompactRetentionAssessment.Evidence.UNAVAILABLE; };
    }

    private static WideCompactRetentionFlow.RunFacts facts(String backend,FrozenReplayInput input,WideCompactQualityMain.Run run) {
        if(input==null||run==null)return null;
        var inference=run.pipeline().result().inference();var work=inference.workAccounting().orElse(null);
        String policy=work==null?"unavailable":work.bStages().stream().map(InferenceWorkAccounting::executionPolicy).distinct().collect(java.util.stream.Collectors.joining(","));
        long retainedCells=run.capture().retained.values().stream().flatMap(rows->rows.values().stream()).mapToLong(List::size).sum();
        return new WideCompactRetentionFlow.RunFacts(backend,input.canonicalHash(),input.evidence().canonicalHash(),input.network().canonicalHash(),input.request().parameterHash(),
                policy,inference.status().toString(),run.capture().completion,run.pipeline().usage().transitions(),run.capture().denseStates,
                run.capture().denseCalls,run.capture().inferredCalls,run.capture().closedCalls,run.capture().closedBytes,run.capture().peakBytes,
                run.capture().retained.size(),retainedCells);
    }

    static WideCompactQualityMain.Run executeReference(WideCompactScenes.Scene scene,Path output) throws Exception {
        var historicalInput=WideCompactQualityMain.historical(scene.input());
        var reference=WideCompactQualityMain.execute(scene,historicalInput,false,false,output.resolve("historical-full"));
        if(reference.pipeline().result().inference().status()==TraceHypothesisSet.Status.RESOURCE_LIMIT)
            return WideCompactQualityMain.execute(scene,scene.input(),true,false,output.resolve("cached-v4-oracle"));
        return reference;
    }
    static String referenceBackend(WideCompactScenes.Scene scene,WideCompactQualityMain.Run run) {
        return run!=null&&run.pipeline().result().inference().workAccounting().map(w->w.hasPolicy(BQualityProbe.ORACLE_VERSION)).orElse(false)
                ?"CACHED_V4_ORACLE_AFTER_HISTORICAL_RESOURCE_LIMIT":"HISTORICAL_FULL_PRODUCTION";
    }

    public static void main(String[] args) throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());
        var invocation=invocation(args);Path output=invocation.output();Files.createDirectories(output);
        if(Files.exists(output.resolve("summary.json")))throw new IOException("Existing retention evidence must not be overwritten");
        var expected=new Properties();try(var in=WideCompactRetentionMain.class.getResourceAsStream(WideCompactQualityMain.MANIFEST)) { WideCompactQualityMain.require(in!=null,"Missing frozen public scene hashes");expected.load(in); }
        String manifest=WideCompactQualityMain.manifest();String commit=System.getenv().getOrDefault("GITHUB_SHA","unavailable-local");
        var report=new WideCompactRetentionFlow.Report();for(String id:WideCompactScenes.IDS)report.ensureScene(id);
        try {
            WideCompactQualityMain.require(WideCompactScenes.SPEC.equals(expected.getProperty("spec")),"Scene specification changed after freeze");
            for(String id:WideCompactScenes.IDS)WideCompactQualityMain.require(WideCompactScenes.scene(id).input().canonicalHash().equals(expected.getProperty(id)),"Frozen scene hash changed: "+id);
        } catch(Throwable preflight) {
            report.fail(WideCompactScenes.IDS.get(0),WideCompactRetentionFlow.Stage.REFERENCE_IDENTITY,preflight);
            report.write(output,commit,manifest);
            throw new AssertionError("Frozen public retention input preflight failed; no numerical execution started",preflight);
        }
        for(String id:WideCompactScenes.IDS) {
            var scene=WideCompactScenes.scene(id);var record=report.scene(id);var dir=output.resolve(id);Files.createDirectories(dir);
            FrozenReplayInput referenceInput=null,candidateInput=scene.input();WideCompactQualityMain.Run reference=null,candidate=null;
            try {
                referenceInput=WideCompactQualityMain.historical(scene.input());
                reference=executeReference(scene,dir.resolve("reference"));
                if(reference.pipeline().result().inference().workAccounting().map(w->w.hasPolicy(BQualityProbe.ORACLE_VERSION)).orElse(false))referenceInput=scene.input();
                record.mark(WideCompactRetentionFlow.Stage.REFERENCE_EXECUTION,WideCompactRetentionFlow.Status.PASS,"native reference execution completed; backend explicitly recorded");
            } catch(Throwable e) { report.fail(id,WideCompactRetentionFlow.Stage.REFERENCE_EXECUTION,e); }
            try {
                candidate=WideCompactQualityMain.execute(scene,candidateInput,false,true,dir.resolve("candidate"));
                record.mark(WideCompactRetentionFlow.Stage.CANDIDATE_EXECUTION,WideCompactRetentionFlow.Status.PASS,"native production candidate execution completed");
            } catch(Throwable e) { report.fail(id,WideCompactRetentionFlow.Stage.CANDIDATE_EXECUTION,e); }
            completeScene(report,scene,referenceInput,reference,candidateInput,candidate,dir);
        }
        report.write(output,commit,manifest);
        requireAccepted(report);
    }

    /** Completes one real runner scene, reusing already executed native outputs. */
    static void completeScene(WideCompactRetentionFlow.Report report,WideCompactScenes.Scene scene,
            FrozenReplayInput referenceInput,WideCompactQualityMain.Run reference,FrozenReplayInput candidateInput,
            WideCompactQualityMain.Run candidate,Path dir) {
        String id=scene.id();var record=report.scene(id);
        if(reference!=null&&candidate!=null) {
            try { measurePair(scene,referenceInput,reference,referenceBackend(scene,reference),candidateInput,candidate,record,dir); }
            catch(Throwable e) { report.fail(id,WideCompactRetentionFlow.Stage.COMPARISON,e); }
        } else {
            record.setFacts(facts(referenceBackend(scene,reference),referenceInput,reference),facts("INVENTORY_COMPACT_CANDIDATE",candidateInput,candidate));
            if(reference==null)record.unavailable(WideCompactRetentionFlow.Stage.REFERENCE_EXECUTION,"reference execution missing");
            if(candidate==null)record.unavailable(WideCompactRetentionFlow.Stage.CANDIDATE_EXECUTION,"candidate execution missing");
            record.unavailable(WideCompactRetentionFlow.Stage.REFERENCE_IDENTITY,"reference/candidate pair unavailable");
            record.unavailable(WideCompactRetentionFlow.Stage.CANDIDATE_IDENTITY,"reference/candidate pair unavailable");
            record.unavailable(WideCompactRetentionFlow.Stage.REFERENCE_DENSE_COMPLETE,"reference/candidate pair unavailable");
            record.setAssessment(unavailableInput(id,record.runnerChecks()));
        }
        report.captureFirstFailure(record);
        if(record.result()!=null&&record.status()!=WideCompactRetentionFlow.Status.PASS&&report.firstFailure()==null) {
            if(record.status()==WideCompactRetentionFlow.Status.FAILED)report.fail(id,WideCompactRetentionFlow.Stage.COMPARISON,new AssertionError("Retention assessment "+record.result().verdict()));
            else report.unavailable(id,WideCompactRetentionFlow.Stage.COMPARISON,"Retention assessment "+record.result().verdict());
        }
    }

    /** The CLI and bounded caller-path regression share the same failing-exit boundary. */
    static void requireAccepted(WideCompactRetentionFlow.Report report) {
        if(WideCompactRetentionFlow.exitFailed(report))throw new AssertionError(
                "Public wide compact performance-retention assessment "+report.status()+"; release remains unqualified",
                report.firstFailure()==null?null:report.firstFailure().cause());
    }

    private static WideCompactRetentionAssessment.Input unavailableInput(String id,WideCompactRetentionAssessment.RunnerChecks checks) {
        var m=new WideCompactRetentionAssessment.Metric(null,WideCompactRetentionAssessment.Evidence.UNAVAILABLE);
        var d=new WideCompactRetentionAssessment.Distances(m,m,m,m);
        boolean faint=id.equals("faint-apex");String interval="x=[50-2p,50+2p]m;p="+P;
        var a=faint?new WideCompactRetentionAssessment.Amplitudes(m,m,interval,interval,"y=0m","y=0m"):null;
        var absolute=faint?new WideCompactRetentionAssessment.AbsoluteAmplitude(m,m,metric(3),metric(2.7),interval,"y=0m"):null;
        var oracle=new WideCompactRetentionAssessment.GeometryOracle(m,m,WideCompactRetentionAssessment.Evidence.UNAVAILABLE);
        return new WideCompactRetentionAssessment.Input(id,WideCompactRetentionAssessment.Strength.WEAK,checks,d,faint,faint,a,absolute,oracle,oracle);
    }

    private WideCompactRetentionMain() { }
}
