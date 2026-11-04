package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;

class NativeAPreprocessingTest {
    @Test void boundedPipelinePreservesEveryNativeChildParentAndState() {
        var anchors=List.of(new RasterPoint(10,20),new RasterPoint(12,20),new RasterPoint(14,20));
        try(var fixture=new NativeAEmissionSolverTest.Fixture(anchors,(x,y)-> {
                double d=y-20;if(Math.abs(d)>5)return 0;
                return .70+.30*Math.max(Math.exp(-.5*Math.pow((d-2)/.45,2)),Math.exp(-.5*Math.pow((d+2)/.45,2)));
            },12)) {
            var extractor=new CorridorExtractor();var tracker=new CorridorTracker();var grouping=new CorridorGrouping();
            var profiles=fixture.input.profiles().levelZeroProfiles();
            var originalFine=extractor.extract(profiles,1);var originalTracks=tracker.track(originalFine,1);
            var original=grouping.group(originalTracks,originalFine,1);
            assertTrue(original.tracks().stream().anyMatch(CorridorTrack::parent));
            long baseline=fixture.ledger.currentBytes();
            try(var owner=fixture.input.openExecutionOwner("bounded-native-parity")) {
                var work=new NativeAPreprocessing(fixture.input,owner);
                var fine=extractor.extract(profiles,1,work);assertEquals(originalFine,fine);
                var tracks=tracker.track(fine,1,Map.of(),work);assertEquals(originalTracks,tracks);
                var bounded=grouping.group(tracks,fine,1,work);assertEquals(original,bounded);
                var optimizer=new CorridorCenterlineOptimizer();
                for(int i=0;i<original.tracks().size();i++) {
                    var track=original.tracks().get(i);var boundedTrack=bounded.tracks().get(i);
                    var tube=new CorridorTubeBuilder().build(track,originalFine,1,Map.of());
                    var boundedTube=new CorridorTubeBuilder().build(boundedTrack,fine,1,Map.of());
                    var approaches=new EndpointApproachBuilder().build(track,originalFine,tube,JunctionContext.empty(),Map.of());
                    var boundedApproaches=new EndpointApproachBuilder().build(boundedTrack,fine,boundedTube,JunctionContext.empty(),Map.of());
                    assertEquals(optimizer.nativeStateDomain(track,originalFine,1,JunctionContext.empty(),Map.of(),tube,approaches),
                        optimizer.nativeStateDomain(boundedTrack,fine,1,JunctionContext.empty(),Map.of(),boundedTube,boundedApproaches));
                }
            }
            assertEquals(baseline,fixture.ledger.currentBytes());
        }
    }
    @ParameterizedTest @ValueSource(strings={"intervals","associateBand","extend"})
    void cancellationOccursInsideExtractionAssociationAndNativeTracking(String phase) {
            var armed=new AtomicBoolean();
            var budget=new EmissionWorkBudget(TraceBudgets.defaults(),()->armed.get()&&StackWalker.getInstance().walk(frames->
                frames.anyMatch(frame->frame.getMethodName().equals(phase)&&frame.getClassName().startsWith(
                    "org.openstreetmap.josm.plugins.wayheatmaptracer.service.Corridor"))));
            try(var fixture=new NativeAEmissionSolverTest.Fixture(3,budget)) {
                var levels=fixture.input.profiles().levels().stream().map(level->new CorridorExtractor().extract(level.profiles(),1)).toList();
                long baseline=fixture.ledger.currentBytes();armed.set(true);
                try(var owner=fixture.input.openExecutionOwner("native-phase-cancel")) {
                    var work=new NativeAPreprocessing(fixture.input,owner);
                    var failure=assertThrows(CancellationException.class,()-> {
                        if(phase.equals("intervals"))new CorridorExtractor().extract(fixture.input.profiles().levelZeroProfiles(),1,work);
                        else if(phase.equals("associateBand"))new CorridorAwareTracker().associateScales(fixture.input.profiles(),levels,1,work);
                        else new CorridorTracker().track(levels.get(0),1,Map.of(),work);
                    },phase);
                    assertSame(failure,assertThrows(CancellationException.class,fixture.input::requireOpenAndUnchanged));
                }
                assertEquals(baseline,fixture.ledger.currentBytes());
            }
    }
    @Test void completeManyBandGroupingIsAdmittedBeforeItsPairAndGroupGrowth() {
        try(var fixture=new ManyBands(CancellationProbe.NONE)) {
            var fine=new CorridorExtractor().extract(fixture.input.profiles().levelZeroProfiles(),1);
            var tracks=new CorridorTracker().track(fine,1);
            assertTrue(tracks.size()>16,"The native first-profile seed family is not capped at16");
            var budget=fixture.input.workBudget();
            budget.charge(EmissionWorkBudget.Operation.PREPARATION,budget.snapshot().workLimit()-budget.snapshot().workUsed()-5000);
            long baseline=fixture.ledger.currentBytes();
            try(var owner=fixture.input.openExecutionOwner("grouping-admission-test")) {
                var failure=assertThrows(EmissionWorkBudget.LimitExceededException.class,()->new CorridorGrouping()
                    .group(tracks,fine,1,new NativeAPreprocessing(fixture.input,owner)));
                assertEquals(EmissionWorkBudget.LimitKind.TOTAL_WORK,failure.kind());
                assertSame(failure,assertThrows(EmissionWorkBudget.LimitExceededException.class,fixture.input::requireOpenAndUnchanged));
            }
            assertEquals(baseline,fixture.ledger.currentBytes());
        }
    }

    @Test void cancellationIsObservedInsideNativeGroupingAndReturnsNoPartialResult() {
        var armed=new AtomicBoolean();
        try(var fixture=new ManyBands(()->armed.get()&&StackWalker.getInstance().walk(frames->frames.anyMatch(
                frame->frame.getClassName().equals(CorridorGrouping.class.getName())&&frame.getMethodName().equals("pairEvidence"))))) {
            var fine=new CorridorExtractor().extract(fixture.input.profiles().levelZeroProfiles(),1);
            var tracks=new CorridorTracker().track(fine,1);assertTrue(tracks.size()>16);
            long baseline=fixture.ledger.currentBytes();armed.set(true);
            try(var owner=fixture.input.openExecutionOwner("grouping-cancel-test")) {
                var failure=assertThrows(CancellationException.class,()->new CorridorGrouping()
                    .group(tracks,fine,1,new NativeAPreprocessing(fixture.input,owner)));
                assertSame(failure,assertThrows(CancellationException.class,fixture.input::requireOpenAndUnchanged));
            }
            assertEquals(baseline,fixture.ledger.currentBytes());
        }
    }

    @Test void groupingCapacityFailurePoisonsTheSameAttemptAndRollsBack() {
        try(var fixture=new ManyBands(CancellationProbe.NONE)) {
            var fine=new CorridorExtractor().extract(fixture.input.profiles().levelZeroProfiles(),1);
            var tracks=new CorridorTracker().track(fine,1);assertTrue(tracks.size()>16);
            try(var pressure=fixture.root.reserve(fixture.ledger.limitBytes()-fixture.ledger.currentBytes()-512)) {
                long baseline=fixture.ledger.currentBytes();
                try(var owner=fixture.input.openExecutionOwner("grouping-memory-test")) {
                    var failure=assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->new CorridorGrouping()
                        .group(tracks,fine,1,new NativeAPreprocessing(fixture.input,owner)));
                    assertSame(failure,assertThrows(AttemptMemoryLedger.ResourceLimitException.class,fixture.input::requireOpenAndUnchanged));
                }
                assertEquals(baseline,fixture.ledger.currentBytes());
            }
        }
    }

    static final class ManyBands implements AutoCloseable {
        final AttemptMemoryLedger ledger=AttemptMemoryLedger.production();
        final AttemptMemoryLedger.Owner root=ledger.rootOwner();
        final SampledEmissionInput input;
        ManyBands(CancellationProbe cancellation) {
            int w=40,h=512;double[] values=new double[w*h];boolean[] valid=new boolean[values.length];Arrays.fill(valid,true);
            for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
                double distance=Math.abs((y+3)%6-3);values[y*w+x]=.95*Math.exp(-.5*Math.pow(distance/.65,2));
            }
            var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(50,14),
                new GeographicPoint(49.99,13.99),new GeographicPoint(50.01,14.02));
            var transform=RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);
            var field=new ScalarEvidenceField(w,h,values,valid,new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"bands",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
            var evidence=new EvidenceSnapshot("many-a-bands",frame,transform,EvidenceResolution.nativeSource(1,1),
                MetricRegion.rectangle(1,1,38,510),MetricRegion.rectangle(-.5,-.5,39.5,511.5),Map.of("bands",field),"public-synthetic");
            var anchors=new ArrayList<DetachedProfileSamplingLocation>();
            for(int row=0;row<3;row++) {
                var r=new RasterPoint(10+row*2,256);var m=transform.pixelCenterToMetric(r.x(),r.y());
                anchors.add(new DetachedProfileSamplingLocation(frame.toGeographic(m),m,r,frame,transform,row*2));
            }
            input=new DetachedScalarProfileSampler().sampleDeclaredSyntheticForEmission(evidence,"bands",
                new CorridorTraceInput(anchors,1),new ProfileChainage(List.of(0.,2.,4.),2),120,
                new EmissionWorkBudget(TraceBudgets.defaults(),cancellation),root);
        }
        @Override public void close(){input.close();root.close();assertEquals(0,ledger.currentBytes());}
    }
}
