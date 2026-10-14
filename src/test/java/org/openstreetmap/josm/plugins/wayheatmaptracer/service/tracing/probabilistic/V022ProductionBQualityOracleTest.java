package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Existing analytic expectations through the default production engine, not a parallel solver. */
class V022ProductionBQualityOracleTest {
    @ParameterizedTest
    @ValueSource(strings={"impulse","three-strand","faint-apex","gap"})
    void defaultProductionBPreservesTheOriginalPhysicalQualityOracles(String scenario) {
        var evidence=V022LocalizationEvidenceTest.snapshot((x,y)->switch(scenario) {
            case "impulse" -> Math.min(1,.02+.08*gaussian(y,26,.9)+(x==60&&y==28?.9:0));
            case "three-strand" -> {
                double v=.02;
                for(int s=0;s<3;s++) v+=.06*gaussian(y,30+2.5*Math.sin(2*Math.PI*(x-10)/24+s*2*Math.PI/3),.65);
                yield v;
            }
            case "faint-apex" -> .02+.08*gaussian(y,30+3*Math.max(0,1-Math.abs(x-60)/6),.65);
            case "gap" -> x<35||x>85?.02+.6*gaussian(y,26+.07*(x-10),1):.02;
            default -> throw new AssertionError(scenario);
        });
        double center=scenario.equals("impulse")?26:scenario.equals("gap")?28:30;
        double step=scenario.equals("impulse")?5:scenario.equals("three-strand")?2:1.5;
        var source=List.of(new MetricPoint(10,center),new MetricPoint(110,center));
        var first=PrimitiveKey.existing(PrimitiveKey.Type.NODE,1);
        var last=PrimitiveKey.existing(PrimitiveKey.Type.NODE,2);
        var way=PrimitiveKey.existing(PrimitiveKey.Type.WAY,3);
        Map<PrimitiveKey,DetachedPrimitive> primitives=new LinkedHashMap<>();
        primitives.put(first,new DetachedNode(first,evidence.coordinateFrame().toGeographic(source.get(0)),Map.of(),false,false));
        primitives.put(last,new DetachedNode(last,evidence.coordinateFrame().toGeographic(source.get(1)),Map.of(),false,false));
        primitives.put(way,new DetachedWay(way,List.of(first,last),Map.of("highway","path"),false,false));
        var region=evidence.decisionRegion();
        var closure=new ClosureDescriptor(ClosureDescriptor.Scope.SELECTION_SAFETY,"quality-oracle",primitives.keySet(),
                Set.of(way),Set.of(),Set.of(first,last),Set.of(),Map.of(way,List.of(new OccurrenceRange(0,1))),
                List.of(),region,region,false,true,true,true);
        var network=new NetworkSnapshot("quality-network",SnapshotRole.CAPTURED_BEFORE,"quality-dataset",1,closure,
                primitives,V022SnapshotFixtures.closedWorldReferrerWatches(primitives));
        var request=new TraceRequest(way,new OccurrenceRange(0,1),TrackerMode.PROBABILISTIC,AlignmentMode.PRECISE_SHAPE,
                RecoveryPermissions.disabled(7.01),TraceBudgets.defaults(),evidence.snapshotId(),evidence.canonicalHash(),
                network.snapshotId(),network.canonicalHash(),"settings","parameters","sampler",step,
                new ProbabilisticProfileFactory().profileChainage(source,step),evidence.resolution());
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner()) {
            var run=new ProbabilisticTraceEngine("scalar").traceWithUsage(request,evidence,network,CancellationProbe.NONE,owner);
            var result=run.value().result();
            assertTrue(result.status()==TraceHypothesisSet.Status.COMPLETE||result.status()==TraceHypothesisSet.Status.AMBIGUOUS,
                    result.status()+": "+result.explanation());
            assertFalse(result.hypotheses().isEmpty());
            assertTrue(result.workAccounting().orElseThrow().hasPolicy("terminal-path-count-scored-work-v2"));
            assertTrue(run.value().usage().transitions()>0);
            assertTrue(run.value().usage().transitions()<=request.budgets().maximumTransitions());
            var points=result.hypotheses().get(0).points();
            if(scenario.equals("faint-apex")) {
                double apex=points.stream().filter(p->Math.abs(p.xMeters()-60)<=2).mapToDouble(MetricPoint::yMeters).max().orElseThrow();
                assertTrue(apex>=32,"Concentrated faint 33m apex must stay within its original 1m bound: "+apex);
            } else if(scenario.equals("gap")) {
                double turn=0;
                for(int i=1;i+1<points.size();i++) {
                    var a=points.get(i-1);var b=points.get(i);var c=points.get(i+1);
                    if(b.xMeters()<40||b.xMeters()>80)continue;
                    double t=Math.atan2(c.yMeters()-b.yMeters(),c.xMeters()-b.xMeters())
                            -Math.atan2(b.yMeters()-a.yMeters(),b.xMeters()-a.xMeters());
                    turn+=Math.abs(Math.atan2(Math.sin(t),Math.cos(t)));
                }
                assertTrue(turn<Math.toRadians(10),"Unsupported straight gap must not acquire staircase turns: "+turn);
            } else {
                double deviation=points.stream().filter(p->!scenario.equals("three-strand")||p.xMeters()>=25&&p.xMeters()<=95)
                        .mapToDouble(p->Math.abs(p.yMeters()-center)).max().orElseThrow();
                assertTrue(deviation<=1,"Original native-pixel bound must hold through production B: "+scenario+" "+deviation);
            }
            assertTrue(ledger.currentBytes()>0,"Successful result remains accounted until owner close");
        }
        assertEquals(0,ledger.currentBytes(),"No production graph may outlive the attempt owner");
    }
    private static double gaussian(double y,double center,double sigma) {
        double d=(y-center)/sigma;return Math.exp(-.5*d*d);
    }
}
