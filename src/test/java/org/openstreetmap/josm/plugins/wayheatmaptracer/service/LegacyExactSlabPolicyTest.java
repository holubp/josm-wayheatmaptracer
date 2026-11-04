package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;

/** The production entry forwards the explicit strategy without changing positive-route arithmetic. */
class LegacyExactSlabPolicyTest {
    @Test void trackerForwardsNamedPreflightAndPreservesPositiveGeometryAndCosts() {
        try (var a = fixture(); var b = fixture();
             var old = new RidgeTracker().trackEmission(a.input(),CancellationProbe.NONE,QueryPolicy.ZERO_GUARDED_LOCAL_V1,
                ExecutionPolicy.STOP_ON_FINAL_MODE_FAILURE_V1);
             var fresh = new RidgeTracker().trackEmission(b.input(),CancellationProbe.NONE,QueryPolicy.ZERO_GUARDED_LOCAL_V1,
                ExecutionPolicy.EXACT_SLAB_PREFLIGHT_V1)) {
            assertEquals(old.inferenceVertices(),fresh.inferenceVertices());
            assertEquals(old.smoothedVertices(),fresh.smoothedVertices());
            assertEquals(old.firstStage(),fresh.firstStage()); assertEquals(old.intervalStages(),fresh.intervalStages());
            assertEquals(old.stitched(),fresh.stitched()); assertEquals(old.postSmoothing(),fresh.postSmoothing());
            assertEquals(old.work(),fresh.work()); assertEquals(old.peakRetainedBytes(),fresh.peakRetainedBytes());
            assertTrue(fresh.sourceReference().modelPolicyId().endsWith("/execution=EXACT_SLAB_PREFLIGHT_V1"));
            var receipts = fresh.inferenceArcs().stream().flatMap(arc->arc.optionalReceipt().stream()).toList();
            assertFalse(receipts.isEmpty(),"The straight bright ridge selects an optional arc across the curved guide");
            for (var receipt : receipts) assertTrue(receipt.parameterVersion().endsWith("/EXACT_SLAB_PREFLIGHT_V1"));
        }
    }

    @Test void incompatibleQueryIsRefusedBeforeTrackerWork() {
        try (var f = fixture()) {
            var before = f.input().workBudget().snapshot();
            assertThrows(IllegalArgumentException.class,()->new RidgeTracker().trackEmission(f.input(),CancellationProbe.NONE,
                QueryPolicy.DENSE_EIGHTH_NATIVE_V1,ExecutionPolicy.EXACT_SLAB_PREFLIGHT_V1));
            assertEquals(before,f.input().workBudget().snapshot());
        }
    }

    private record Fixture(SampledEmissionInput input,AttemptMemoryLedger.Owner owner,AttemptMemoryLedger ledger)
            implements AutoCloseable {
        @Override public void close() { input.close(); owner.close(); assertEquals(0,ledger.currentBytes()); }
    }
    private static Fixture fixture() {
        int size=96; var values=new double[size*size]; var valid=new boolean[values.length]; Arrays.fill(valid,true);
        for(int y=0;y<size;y++)for(int x=0;x<size;x++) values[y*size+x]=Math.exp(-.5*Math.pow((y-64.5)/3,2));
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-.01,-.01),new GeographicPoint(.01,.01));
        var transform=RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);
        var field=new ScalarEvidenceField(size,size,values,valid,new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"ridge",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
        var evidence=new EvidenceSnapshot("slab-policy",frame,transform,EvidenceResolution.nativeSource(1,1),
            MetricRegion.rectangle(0,0,95,95),MetricRegion.rectangle(-.5,-.5,95.5,95.5),Map.of("ridge",field),"slab-policy");
        var anchors=List.of(new MetricPoint(24,64.22),new MetricPoint(32,62.22),new MetricPoint(40,64.22));
        var sourceS=ProfileChainage.measured(anchors,8); var locations=new ArrayList<DetachedProfileSamplingLocation>();
        for(int i=0;i<anchors.size();i++) locations.add(DetachedProfileSamplingLocation.at(frame.toGeographic(anchors.get(i)),
            frame,transform,sourceS.cumulativeGroundMeters().get(i)));
        var trace=new CorridorTraceInput(locations,1); var ledger=AttemptMemoryLedger.production(); var owner=ledger.rootOwner();
        var work=new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);
        try(var permit=OptionalEmissionContextFactory.bindDeclaredSyntheticMetricGrid(evidence,"ridge",trace,sourceS,25,work,owner)) {
            var retained=new RenderedHeatmapSampler().sampleDetachedRetainingL0(evidence,"ridge",trace.profileLocations(),25,1,1,work,permit);
            var binding=OptionalEmissionContextFactory.createLegacyBinding(permit,retained,List.of(0,1,2),1,List.of());
            return new Fixture(OptionalEmissionContextFactory.fromDeclaredSyntheticMetricGrid(permit,retained,binding),owner,ledger);
        } catch(RuntimeException failure) { owner.close(); throw failure; }
    }
}
