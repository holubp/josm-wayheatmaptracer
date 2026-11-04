package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.RenderedHeatmapSampler.CrossSectionProfile;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;

class LegacyCompetitiveProofTest {
    static final QueryPolicy QUERY=QueryPolicy.ZERO_GUARDED_LOCAL_V1;
    static final ExecutionPolicy EXEC=ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1;
    static final String FIELD="competitive-public";

    @ParameterizedTest @ValueSource(doubles={0,.25})
    void actualFourPathNativeRasterAvoidsARealCertificateAndMatchesExhaustiveGroupedWinner(double seed) throws Exception {
        try(var f=fixture(false,new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE))) {
            assertEquals(List.of(2,1,1),f.profiles().stream().map(p->p.peaks().size()).toList());
            assertTrue(f.input().legacyBinding().orElseThrow().observations().stream().allMatch(x->x.sourceClass()==SourceClass.OBSERVED));
            var oracle=enumerate(f,seed);assertTrue(oracle.size()>=2&&oracle.size()<=4);
            var expected=oracle.stream().min(LegacyCompetitiveProofTest::compare).orElseThrow();
            try(var offProgress=LegacyEmissionProgress.open(f.input(),QUERY,EXEC);
                var onProgress=LegacyEmissionProgress.open(f.input(),QUERY,EXEC);
                var off=solve(f,"EXACT_VERSIONS_PROOF_V1",offProgress,seed);
                var on=solve(f,"EXACT_CUTOFF_PROOF_V1",onProgress,seed)) {
                assertEquals(expected.costs(),value(off,"costs"));assertEquals(expected.costs(),value(on,"costs"));
                assertEquals(expected.rows(),rows(off));assertEquals(expected.rows(),rows(on));
                assertEquals(expected.ids(),ids(off));assertEquals(expected.ids(),ids(on));
                assertEquals(value(off,"costs"),value(on,"costs"));
                assertEquals(value(off,"vertices"),value(on,"vertices"));
                assertEquals(value(off,"witnessCosts"),value(on,"witnessCosts"));
                assertTrue(((Optional<?>)value(on,"witnessCosts")).isPresent());
                assertTrue(counter(onProgress,"STRICT_CERTIFICATE_DEFERRALS")>0,"A real costly optional certificate must be avoided");
                assertTrue(onProgress.view().count(LegacyEmissionProgress.Counter.EXTRACTION_STARTED)<offProgress.view().count(LegacyEmissionProgress.Counter.EXTRACTION_STARTED));
                assertTrue(onProgress.view().count(LegacyEmissionProgress.Counter.EVALUATION_STARTED)<offProgress.view().count(LegacyEmissionProgress.Counter.EVALUATION_STARTED));
                assertEquals(0,counter(offProgress,"STRICT_CERTIFICATE_DEFERRALS"));
                assertTrue(value(on,"execution").toString().contains("EXACT_CUTOFF"));
                // Same last ordinary leg resets scalar/physical/cursor memories; distinct
                // native first states can therefore genuinely reach one full future Key.
                assertEquals(2,onProgress.view().maxCompetitiveKeyLabels(),"Actual native prefixes share the full Key; strict component advantage cannot drop a tie-worse prefix");
                assertTrue(onProgress.view().hasWitnessUpper());assertEquals(on.witnessCosts().orElseThrow().total(),onProgress.view().witnessUpper(),0);
                var witness=oracle.stream().filter(p->p.rows().size()==3&&p.ids().equals(on.witnessVertices().stream().map(v->v.nativeStateId().orElseThrow()).toList())).findFirst().orElseThrow();
                assertEquals(witness.costs(),on.witnessCosts().orElseThrow());
                if(seed!=0){assertTrue(on.witnessCosts().orElseThrow().c()>.03);assertTrue(on.witnessCosts().orElseThrow().t()>.05);}
                System.out.println("public proof seed="+seed+" witness="+on.witnessCosts().orElseThrow()+" winner="+on.costs()
                    +" evaluations="+offProgress.view().count(LegacyEmissionProgress.Counter.EVALUATION_STARTED)+"/"+onProgress.view().count(LegacyEmissionProgress.Counter.EVALUATION_STARTED)
                    +" extraction-starts="+offProgress.view().count(LegacyEmissionProgress.Counter.EXTRACTION_STARTED)+"/"+onProgress.view().count(LegacyEmissionProgress.Counter.EXTRACTION_STARTED)
                    +" deferrals="+counter(onProgress,"STRICT_CERTIFICATE_DEFERRALS")+" max-key-labels="+onProgress.view().maxCompetitiveKeyLabels());
            }
        }
    }

    static long counter(LegacyEmissionProgress progress,String name) {
        return progress.view().count(LegacyEmissionProgress.Counter.valueOf(name));
    }
    static LegacyEmissionKernel.ObservedSeedProof solve(Fixture f,String policy,LegacyEmissionProgress progress) {
        return solve(f,policy,progress,0);
    }
    static LegacyEmissionKernel.ObservedSeedProof solve(Fixture f,String policy,LegacyEmissionProgress progress,double seed) {
        return new LegacyEmissionKernel().solveObservedSeedProof(f.input(),f.profiles(),seed,CancellationProbe.NONE,
            LegacyEmissionKernel.CompetitiveExecution.valueOf(policy),progress);
    }
    static Object value(Object target,String method) throws Exception {return target.getClass().getMethod(method).invoke(target);}
    @SuppressWarnings("unchecked") static List<EmissionResult.Vertex> vertices(Object result) throws Exception {return (List<EmissionResult.Vertex>)value(result,"vertices");}
    static List<Integer> rows(Object result) throws Exception {return vertices(result).stream().map(v->((EmissionMapping.ObservationVertex)v.owner()).observationIndex()).toList();}
    static List<Integer> ids(Object result) throws Exception {return vertices(result).stream().map(v->v.nativeStateId().orElseThrow()).toList();}

    record Fixture(SampledEmissionInput input,List<CrossSectionProfile> profiles,AttemptMemoryLedger ledger,
            AttemptMemoryLedger.Owner owner) implements AutoCloseable {
        @Override public void close(){input.close();owner.close();assertEquals(0,ledger.currentBytes());}
    }
    static Fixture fixture(boolean missing,EmissionWorkBudget budget) {
        return fixture(missing,false,budget,EvidenceResolution.nativeSource(1,1));
    }
    static Fixture fixture(boolean missing,boolean perfect,EmissionWorkBudget budget,EvidenceResolution resolution) {
        int w=96,h=112;double[] values=new double[w*h];boolean[] valid=new boolean[values.length];Arrays.fill(valid,true);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            double dy=Math.abs(y-64.5);
            double center=perfect?(dy<=.5?1:dy<=1.5?.8:dy<=2.5?.2:0):.85*StrictMath.exp(-.5*Math.pow((y-64.5)/.8,2));
            double initial=!perfect&&x<=25?StrictMath.exp(-.5*Math.pow((y-44.5)/.8,2)):0;
            values[y*w+x]=Math.rint(255*Math.max(center,initial))/255.0;
            if(missing&&x==28)valid[y*w+x]=false;
        }
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-.01,-.01),new GeographicPoint(.01,.01));
        var transform=RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);
        var field=new ScalarEvidenceField(w,h,values,valid,new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,FIELD,EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
        var evidence=new EvidenceSnapshot("competitive-proof-public",frame,transform,resolution,
            MetricRegion.rectangle(0,0,w-1,h-1),MetricRegion.rectangle(-.5,-.5,w-.5,h-.5),Map.of(FIELD,field),"competitive-proof");
        var locations=new ArrayList<DetachedProfileSamplingLocation>();
        for(int i=0;i<3;i++)locations.add(DetachedProfileSamplingLocation.at(frame.toGeographic(new MetricPoint(24+8*i,64.5)),frame,transform,8*i));
        var source=new CorridorTraceInput(locations,1);var chainage=new ProfileChainage(List.of(0.0,8.0,16.0),16);
        var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();
        try(var permit=OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(evidence,FIELD,source,chainage,25,budget,owner)) {
            double nativePitch=DetachedScalarProfileSampler.emissionSourcePitchRasterPixels(evidence,source,chainage.sourceOriginGroundMeters(),budget);
            var retained=new RenderedHeatmapSampler().sampleDetachedRetainingL0(evidence,FIELD,source.profileLocations(),25,1,nativePitch,budget,permit);
            var binding=OptionalEmissionContextFactory.createLegacyBinding(permit,retained,List.of(0,1,2),1,List.of());
            var input=OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,retained,binding);
            return new Fixture(input,binding.observations().stream().map(LegacyEmissionBinding.OriginalObservation::profile).toList(),ledger,owner);
        } catch(RuntimeException failure){owner.close();assertEquals(0,ledger.currentBytes());throw failure;}
    }

    record OraclePath(List<Integer> rows,List<Integer> ids,EmissionResult.Costs costs,int consumed) { }
    static List<OraclePath> enumerate(Fixture f) {
        return enumerate(f,0);
    }
    static List<OraclePath> enumerate(Fixture f,double seed) {
        var binding=f.input().legacyBinding().orElseThrow();var states=new ArrayList<RowState>();var byRow=new ArrayList<List<RowState>>();
        for(var row:binding.observations()) {
            var group=new ArrayList<RowState>();for(var p:row.producerPeaks()) {
                var s=new RowState(row.retainedObservationIndex(),p.originalPeakId(),p.peak().offsetPx(),SourceClass.OBSERVED,p.measurementPeakOwner());
                states.add(s);group.add(s);
            }byRow.add(group);
        }
        var paths=new ArrayList<OraclePath>();
        try(var e=new NativeConnectorEvaluator(f.input(),new Domain(states,List.of()),QUERY,EXEC)) {
            for(var first:byRow.get(0))for(var last:byRow.get(2)) {
                for(var middle:byRow.get(1))paths.add(cost(f,List.of(first,middle,last),null,seed));
                var optional=e.evaluate(first,last);
                if(optional.status()==Status.ADMITTED)paths.add(cost(f,List.of(first,last),optional,seed));
            }
        }
        return paths;
    }
    static OraclePath cost(Fixture f,List<RowState> path,Receipt optional,double seed) {
        double pitch=f.input().context().nativePitchMeters().orElseThrow();
        double prior=Math.abs(path.get(0).offsetRasterPixels()-seed);double q=0,c=.16*prior,t=.12*prior,k=0,slope=path.get(0).offsetRasterPixels()-seed;
        MetricPoint prevFrom=null,prevTo=null;var context=f.input().context();
        for(int i=1;i<path.size();i++) {
            var a=path.get(i-1);var b=path.get(i);var pa=point(f,a);var pb=point(f,b);
            double span=context.sourceChainageMeters(b.row())-context.sourceChainageMeters(a.row());
            double arcQ;
            if(optional!=null)arcQ=optional.sourceWeightedLoss();
            else {
                var grid=context.nativeGrid();var integral=BilinearSegmentIntegral.evaluate(grid,grid.toGrid(context.rasterAt(pa)),grid.toGrid(context.rasterAt(pb)),CancellationProbe.NONE,()->{});
                double mean=integral.status()==BilinearSegmentIntegral.Status.COMPLETE?integral.mean().orElseThrow():0;
                arcQ=span/pitch*(1-mean);
            }
            double nextSlope=(b.offsetRasterPixels()-a.offsetRasterPixels())/(originalRow(f,b)-originalRow(f,a));
            double arcC=.16*Math.abs(b.offsetRasterPixels()-a.offsetRasterPixels());
            double arcT=.12*Math.abs(nextSlope-slope),arcK=0;
            if(prevFrom!=null&&pa.distanceTo(pb)>0) {
                double ax=prevTo.xMeters()-prevFrom.xMeters(),ay=prevTo.yMeters()-prevFrom.yMeters();
                double bx=pb.xMeters()-pa.xMeters(),by=pb.yMeters()-pa.yMeters();
                double angle=Math.atan2(ax*by-ay*bx,ax*bx+ay*by);
                arcK=angle*angle/Math.max(1,(prevFrom.distanceTo(prevTo)+pa.distanceTo(pb))/(2*pitch));
            }
            // Match the normative component additions, never a compensated stream sum.
            q=q+(0+arcQ);c=c+arcC;t=t+arcT;k=k+(0+arcK);slope=nextSlope;
            if(pa.distanceTo(pb)>0){prevFrom=pa;prevTo=pb;}
        }
        return new OraclePath(path.stream().map(s->originalRow(f,s)).toList(),path.stream().map(RowState::nativeStateId).toList(),
            new EmissionResult.Costs(q,c,t,k),3-path.size());
    }
    static MetricPoint point(Fixture f,RowState state) {
        var p=f.input().legacyBinding().orElseThrow().observations().get(originalRow(f,state)).measurementProfile();var a=p.anchorScreen();var n=p.normalScreen();
        return f.input().context().metricAt(new RasterPoint(a.x+n.x*state.offsetRasterPixels(),a.y+n.y*state.offsetRasterPixels()));
    }
    static int originalRow(Fixture f,RowState state) {
        return f.input().legacyBinding().orElseThrow().observations().stream().filter(x->x.retainedObservationIndex()==state.row()).findFirst().orElseThrow().originalRow();
    }
    static int compare(OraclePath a,OraclePath b) {
        int result=Double.compare(a.costs().total(),b.costs().total());if(result!=0)return result;
        result=Integer.compare(a.consumed(),b.consumed());if(result!=0)return result;
        for(int i=0;i<Math.min(a.rows().size(),b.rows().size());i++) {
            result=Integer.compare(a.rows().get(i),b.rows().get(i));if(result!=0)return result;
            result=Integer.compare(a.ids().get(i),b.ids().get(i));if(result!=0)return result;
        }
        return Integer.compare(a.rows().size(),b.rows().size());
    }
}
