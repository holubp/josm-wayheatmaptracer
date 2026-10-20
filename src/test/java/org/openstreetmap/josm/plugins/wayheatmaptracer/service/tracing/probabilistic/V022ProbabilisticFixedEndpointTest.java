package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

/** Source anchors are immutable input coordinates, not interpolated approximations. */
class V022ProbabilisticFixedEndpointTest {
    static Stream<List<MetricPoint>> sources() {
        return Stream.of(
                List.of(new MetricPoint(32,37),new MetricPoint(Math.nextDown(8d),37)),
                List.of(new MetricPoint(-0.0,+0.0),new MetricPoint(3,-0.0)),
                List.of(new MetricPoint(-21.3,-9.1),new MetricPoint(-4.7,Math.nextDown(-2d))),
                List.of(new MetricPoint(-0.0,-12.25),new MetricPoint(2.3,-8.9),new MetricPoint(9.7,-0.0)));
    }

    private static void sameBits(MetricPoint expected,MetricPoint actual) {
        assertEquals(Double.doubleToRawLongBits(expected.xMeters()),Double.doubleToRawLongBits(actual.xMeters()),"x raw bits");
        assertEquals(Double.doubleToRawLongBits(expected.yMeters()),Double.doubleToRawLongBits(actual.yMeters()),"y raw bits");
    }

    @Test void zeroOffsetMaterializationPreservesAnchorBitsInFreshPointsForBothZeroSigns() {
        for(var anchor:List.of(new MetricPoint(-0.0,-0.0),new MetricPoint(-0.0,37),new MetricPoint(8,-0.0)))
            for(var normal:List.of(new MetricPoint(1,0),new MetricPoint(0,1)))
                for(double offset:new double[]{+0.0,-0.0}) {
                    var profile=pointProfile(0,anchor,normal,offset);var point=profile.point(0);
                    sameBits(anchor,point);assertNotSame(anchor,point);assertNotSame(point,profile.point(0));
                }
    }

    @Test void nonzeroMaterializationKeepsOriginalArithmeticIncludingUnderflow() {
        var anchor=new MetricPoint(-0.0,-3.7);var normal=new MetricPoint(.6,.8);
        for(double offset:new double[]{Double.MIN_VALUE,-Double.MIN_VALUE,3.125,-3.125}) {
            var profile=pointProfile(0,anchor,normal,offset);var point=profile.point(0);
            sameBits(new MetricPoint(anchor.xMeters()+normal.xMeters()*offset,
                    anchor.yMeters()+normal.yMeters()*offset),point);assertNotSame(anchor,point);
        }
    }

    @Test void actualPairStateMaterializationRetainsSignedZeroFixedEndpoints() {
        var anchors=List.of(new MetricPoint(-0.0,-0.0),new MetricPoint(2,-0.0));
        var graph=List.of(pointProfile(0,anchors.get(0),new MetricPoint(0,1),+0.0),
                pointProfile(2,anchors.get(1),new MetricPoint(0,1),-0.0));
        for(var mode:List.of(ProbabilisticExecutionPolicy.Mode.EAGER_V1,ProbabilisticExecutionPolicy.Mode.EXACT_DOMINANCE_V2)) {
            var ledger=new AttemptMemoryLedger(1_000_000);
            try(var owner=ledger.rootOwner()) {
                var result=new ProbabilisticInference().solve(graph,EvidenceModelParameters.defaults(),
                        new TraceBudgets(96,1000,10000,1,1),MetricRegion.rectangle(-1,-1,3,1),CancellationProbe.NONE,owner,mode);
                assertTrue(result.mapPath().isPresent(),result.explanation());assertFalse(result.rawPaths().isEmpty());
                for(var path:result.rawPaths())for(int i=0;i<anchors.size();i++) {
                    sameBits(anchors.get(i),path.points().get(i));assertNotSame(anchors.get(i),path.points().get(i));
                }
                for(int i=0;i<anchors.size();i++)sameBits(anchors.get(i),result.mapPath().orElseThrow().points().get(i));
            }
            assertEquals(0,ledger.currentBytes());
        }
    }

    private static InferenceProfile pointProfile(double chainage,MetricPoint anchor,MetricPoint normal,double offset) {
        return new InferenceProfile(chainage,anchor,normal,List.of(new LateralStateCell(offset,1,true,true,"unlocalized")),
                new double[]{0},List.of(),0,ObservationOwnership.FIXED_TOPOLOGY_ONLY,false);
    }

    @ParameterizedTest @MethodSource("sources")
    void sourceGeometryPreservesBothEndpointBitsAndFreshPointIdentities(List<MetricPoint> source) {
        var factory=new ProbabilisticProfileFactory();
        for(double step:List.of(1.5,100.0)) {
            var geometry=factory.sourceSamplingGeometry(source,step);
            sameBits(source.get(0),geometry.points().get(0));
            sameBits(source.get(source.size()-1),geometry.points().get(geometry.points().size()-1));
            assertNotSame(source.get(0),geometry.points().get(0));
            assertNotSame(source.get(source.size()-1),geometry.points().get(geometry.points().size()-1));
            assertEquals(factory.profileChainage(source,step).cumulativeGroundMeters(),geometry.chainageMeters());
        }
    }

    @ParameterizedTest @MethodSource("sources")
    void ownedProductionProfilesRetainFreshAccountedExactAnchors(List<MetricPoint> source) {
        var factory=new ProbabilisticProfileFactory();var evidence=scene();
        var ledger=new AttemptMemoryLedger(64L*1024*1024);
        try(var owner=ledger.rootOwner()) {
            var chainage=factory.profileChainage(source,3);
            var profiles=factory.create(source,chainage,2,true,evidence,evidence.fields().get("scalar"),
                    EvidenceModelParameters.defaults(),CancellationProbe.NONE,true,owner);
            assertEquals(chainage.cumulativeGroundMeters().size(),profiles.size());
            for(int i:List.of(0,profiles.size()-1)) {
                var original=source.get(i==0?0:source.size()-1);var profile=profiles.get(i);
                sameBits(original,profile.anchor());assertNotSame(original,profile.anchor());
                assertEquals(0,profile.exactAnchorOffsetMeters().orElseThrow(),0);
                try(var proof=owner.child("endpoint-ownership-proof")) {proof.retain(profile.anchor());}
            }
            assertTrue(ledger.currentBytes()>0);assertTrue(ledger.peakBytes()<=64L*1024*1024);
        }
        assertEquals(0,ledger.currentBytes());
    }

    @Test void interiorSamplingAndChainageKeepOriginalOperationOrder() {
        var source=List.of(new MetricPoint(-9.7,3.1),new MetricPoint(2.3,-4.6),new MetricPoint(15.8,8.2));
        double first=source.get(0).distanceTo(source.get(1));double length=first+source.get(1).distanceTo(source.get(2));
        int intervals=(int)Math.ceil(length/2.7);
        var geometry=new ProbabilisticProfileFactory().sourceSamplingGeometry(source,2.7);
        assertEquals(intervals+1,geometry.points().size());
        assertEquals(Double.doubleToRawLongBits(length),Double.doubleToRawLongBits(geometry.chainageMeters().get(intervals)));
        for(int i=1;i<intervals;i++) {
            double target=i*length/intervals;boolean second=target>first;
            var start=source.get(second?1:0);var end=source.get(second?2:1);
            double fraction=(target-(second?first:0))/((second?length:first)-(second?first:0));
            sameBits(new MetricPoint(start.xMeters()+fraction*(end.xMeters()-start.xMeters()),
                    start.yMeters()+fraction*(end.yMeters()-start.yMeters())),geometry.points().get(i));
            assertEquals(Double.doubleToRawLongBits(target),Double.doubleToRawLongBits(geometry.chainageMeters().get(i)));
        }
    }

    @Test void originalResamplingAllocationCensusIsUnchangedAndEndpointsCannotBeBorrowed() throws Exception {
        var source=List.of(new MetricPoint(32,37),new MetricPoint(Math.nextDown(8d),37));
        int n=(int)Math.ceil(source.get(0).distanceTo(source.get(1))/3)+1;
        // Double boxes:16B header+8B payload; MetricPoint:16B header+16B payload.
        long expected=list(2)+2*24L+4*list(n)+n*(24L+32L)+32;
        var method=ProbabilisticProfileFactory.class.getDeclaredMethod("resample",List.class,double.class,AttemptMemoryLedger.Owner.class);
        method.setAccessible(true);var ledger=new AttemptMemoryLedger(expected);
        try(var owner=ledger.rootOwner()) {
            method.invoke(null,source,3d,owner);
            assertEquals(expected,ledger.currentBytes());assertEquals(expected,ledger.peakBytes());
        }
        assertEquals(0,ledger.currentBytes());
    }
    private static long list(int n) {return 32L+((16L+8L*n+7)/8)*8;}

    @Test void cancellationAndAllocationRefusalPublishNoOwnedProfiles() {
        var factory=new ProbabilisticProfileFactory();var source=List.of(new MetricPoint(32,37),new MetricPoint(Math.nextDown(8d),37));
        var evidence=scene();var chainage=factory.profileChainage(source,3);
        var ledger=new AttemptMemoryLedger(64L*1024*1024);
        try(var owner=ledger.rootOwner()) {
            assertThrows(CancellationException.class,()->factory.create(source,chainage,2,true,evidence,
                    evidence.fields().get("scalar"),EvidenceModelParameters.defaults(),()->true,true,owner));
            assertEquals(0,ledger.currentBytes());
        }
        var small=new AttemptMemoryLedger(1);
        try(var owner=small.rootOwner()) {
            assertThrows(ProbabilisticInference.MemoryLimit.class,()->factory.create(source,chainage,2,true,evidence,
                    evidence.fields().get("scalar"),EvidenceModelParameters.defaults(),CancellationProbe.NONE,true,owner));
            assertEquals(0,small.currentBytes());
        }
    }

    @Test void newSamplingIdentityDistinguishesChangedInputsFromTheHistoricalPolicy() {
        String policy="profile-window-endpoint-preserving-v3";
        assertEquals(policy,EvidenceModelParameters.profileSamplingPolicyVersion());
        assertTrue(EvidenceModelParameters.defaults().version().contains("/"+policy));
        assertTrue(EvidenceModelParameters.withoutShapeTerms().version().contains("/"+policy));
        assertFalse(EvidenceModelParameters.defaults().version().contains("/profile-window-centered-v2"));
    }

    @Test void tinyActualNativeEngineReturnsItsActualRequestEndpointsExactly() {
        var evidence=scene(true);var ledger=new AttemptMemoryLedger(64L*1024*1024);
        try(var owner=ledger.rootOwner()) {
            var bound=BNativeProductionComparison.bind(evidence,List.of(new MetricPoint(11,37),new MetricPoint(8,37)));
            sameBits(new MetricPoint(Math.nextDown(8d),37),bound.actual().get(bound.actual().size()-1));
            var owned=new ProbabilisticTraceEngine("scalar").traceWithUsage(bound.request(),evidence,bound.network(),CancellationProbe.NONE,owner);
            try {
                var result=owned.value().result();
                assertTrue(result.status()==TraceHypothesisSet.Status.COMPLETE||result.status()==TraceHypothesisSet.Status.AMBIGUOUS,
                        ()->result.status()+": "+result.explanation());
                assertFalse(result.hypotheses().isEmpty());
                for(var route:result.hypotheses()) {
                    sameBits(bound.actual().get(0),route.points().get(0));
                    sameBits(bound.actual().get(bound.actual().size()-1),route.points().get(route.points().size()-1));
                }
            } finally {owned.owner().close();}
            assertEquals(0,ledger.currentBytes());
        }
    }

    private static EvidenceSnapshot scene() {return scene(false);}
    private static EvidenceSnapshot scene(boolean ridge) {
        int size=161;double[] values=new double[size*size];boolean[] valid=new boolean[values.length];
        Arrays.fill(values,.02);Arrays.fill(valid,true);
        if(ridge)for(int x=0;x<size;x++){values[77*size+x]=.8;values[76*size+x]=.2;values[78*size+x]=.2;}
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-1,-1),new GeographicPoint(1,1));
        var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"fixed-endpoint-unit",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
        return new EvidenceSnapshot("fixed-endpoint-unit",frame,RasterMetricTransform.metricGrid(new MetricPoint(-40,-40),1,0,0,1),
                EvidenceResolution.nativeSource(1,1),MetricRegion.rectangle(-39,-39,119,119),MetricRegion.rectangle(-40,-40,120,120),
                Map.of("scalar",new ScalarEvidenceField(size,size,values,valid,lineage)),"fixed-endpoint-unit");
    }
}
