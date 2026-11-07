package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.util.ModernDiagnosticCounters;

class V022InventoryPreparationTest {
    private static final double PITCH=.25;
    private static final List<MetricPoint> SOURCE=List.of(new MetricPoint(0,0),new MetricPoint(64,0));
    private record Run(List<ProbabilisticProfile> profiles,Map<String,Number> counters) { }
    private static Run prepare(EvidenceSnapshot evidence,boolean inventory) {
        var ledger=AttemptMemoryLedger.production();ModernDiagnosticCounters.begin();
        try(var owner=ledger.rootOwner()) {
            var factory=new ProbabilisticProfileFactory();var chainage=factory.profileChainage(SOURCE,16);
            var profiles=inventory?factory.createWithInventory(SOURCE,chainage,7,false,evidence,evidence.fields().get("scalar"),EvidenceModelParameters.defaults(),CancellationProbe.NONE,owner)
                    :factory.create(SOURCE,chainage,7,false,evidence,evidence.fields().get("scalar"),EvidenceModelParameters.defaults(),CancellationProbe.NONE,true,owner);
            return new Run(profiles,ModernDiagnosticCounters.snapshot());
        }finally{ModernDiagnosticCounters.end();assertEquals(0,ledger.currentBytes());}
    }
    @Test void completeStrongFaintCompetingAndClippedContextPreparationIsNumericallyExact() {
        for(int variant=0;variant<4;variant++) {
            var evidence=scene(variant);var old=prepare(evidence,false);var current=prepare(evidence,true);
            assertEquals(old.profiles(),current.profiles(),"all samples/modes/orientation/reliability/span/numerical fields, variant "+variant);
            assertFalse(current.profiles().isEmpty());
            for(String key:List.of("reliability.bridgeQueries","reliability.intervalSamplingUnits","reliability.bridgeScalarQueries","reliability.intervalSamplingLimit","reliability.bridgeFeatureLimit"))
                assertEquals(old.counters().get(key),current.counters().get(key),key);
            assertEquals(8_000_000L,current.counters().get("reliability.bridgeFeatureLimit").longValue());
            assertEquals(2_000_000L,current.counters().get("reliability.intervalSamplingLimit").longValue());
            assertFalse(old.counters().containsKey("b.prep.inventoryVersion"));
            assertEquals(1,current.counters().get("b.prep.inventoryVersion").longValue());
            assertTrue(current.counters().get("b.prep.inventoryBuilds").longValue()>0);
            assertTrue(current.counters().get("b.prep.inventoryHits").longValue()>0);
            assertTrue(current.counters().get("reliability.bridgeFeatureUnits").longValue()<old.counters().get("reliability.bridgeFeatureUnits").longValue(),"actual feature work after charged construction");
            System.out.println("syntheticInventory variant="+variant+" oldFeatures="+old.counters().get("reliability.bridgeFeatureUnits")
                    +" newFeatures="+current.counters().get("reliability.bridgeFeatureUnits")+" builds="+current.counters().get("b.prep.inventoryBuilds")
                    +" hits="+current.counters().get("b.prep.inventoryHits"));
        }
    }
    @Test void ownedPreparationCancellationAndMemoryRefusalReleaseAllScratch() {
        var e=scene(3);var factory=new ProbabilisticProfileFactory();var chainage=factory.profileChainage(SOURCE,16);
        var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()) {
            assertThrows(java.util.concurrent.CancellationException.class,()->factory.createWithInventory(SOURCE,chainage,7,false,e,e.fields().get("scalar"),EvidenceModelParameters.defaults(),()->true,owner));
            assertEquals(0,ledger.currentBytes());
        }
        var tiny=new AttemptMemoryLedger(1);try(var owner=tiny.rootOwner()) {
            assertThrows(ProbabilisticInference.MemoryLimit.class,()->factory.createWithInventory(SOURCE,chainage,7,false,e,e.fields().get("scalar"),EvidenceModelParameters.defaults(),CancellationProbe.NONE,owner));
            assertEquals(0,tiny.currentBytes());
        }
    }
    @Test void realEightMillionFeatureGateAdmitsEqualityAndRefusesBeforeTheNextCachedRead() {
        var e=scene(0);var region=e.decisionRegion();var a=new MetricPoint(1,0);var b=new MetricPoint(2,0);
        var measure=AttemptMemoryLedger.production();long[] exact={0};
        try(var owner=measure.rootOwner()) {
            var index=region.prepareYMembershipWithInventory(CancellationProbe.NONE,n->exact[0]+=n,owner);
            assertTrue(region.containsSegmentWithBoundedEvents(a,b,CancellationProbe.NONE,n->exact[0]+=n,owner,index));
        }
        var ledger=AttemptMemoryLedger.production();try(var owner=ledger.rootOwner()) {
            var gate=new ProbabilisticProfileFactory.RasterIntervalEvidence(e,e.fields().get("scalar"),EvidenceModelParameters.defaults().localization(),owner);
            gate.accept(8_000_000-exact[0]);
            var index=region.prepareYMembershipWithInventory(CancellationProbe.NONE,gate,owner);
            assertTrue(region.containsSegmentWithBoundedEvents(a,b,CancellationProbe.NONE,gate,owner,index));
            assertEquals(1,MetricRegion.preparedInventoryReads(index));long held=ledger.currentBytes();
            assertThrows(LongitudinalModeReliability.ResourceLimitException.class,()->region.containsSegmentWithBoundedEvents(a,b,CancellationProbe.NONE,gate,owner,index));
            assertEquals(1,MetricRegion.preparedInventoryReads(index),"refused inventory read is not performed or counted");assertEquals(held,ledger.currentBytes());
        }
        assertEquals(0,measure.currentBytes());assertEquals(0,ledger.currentBytes());
    }
    private static EvidenceSnapshot scene(int variant) {
        int width=417,height=161;double[] values=new double[width*height];boolean[] valid=new boolean[values.length];Arrays.fill(valid,true);
        double prominence=variant==1?.012:.12;
        for(int row=0;row<height;row++)for(int col=0;col<width;col++) {
            double y=-20+row*PITCH;
            values[row*width+col]=.02+prominence*Math.exp(-.5*y*y/(.75*.75));
            if(variant>=2)values[row*width+col]+=.11*Math.exp(-.5*(y-5)*(y-5)/(.6*.6));
        }
        var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"inventory-control",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
        var field=new ScalarEvidenceField(width,height,values,valid,lineage);
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),new GeographicPoint(-1,-1),new GeographicPoint(1,1));
        var transform=new RasterMetricTransform("inventory-control",RasterMetricTransform.OriginKind.VISIBLE_FIRST_PIXEL_CENTER,new MetricPoint(-20,-20),PITCH,0,0,PITCH,1);
        var quads=new ArrayList<List<MetricPoint>>();double half=variant==3?4:9;
        quads.add(MetricRegion.rectangle(-10,-half,74,half).polygons().get(0));
        for(int i=0;i<11;i++)quads.add(MetricRegion.rectangle(i*6,-3,i*6+6,3).polygons().get(0));
        return new EvidenceSnapshot("inventory-control",frame,transform,EvidenceResolution.nativeSource(PITCH,PITCH),new MetricRegion(quads),
                MetricRegion.rectangle(-19,-19,83,19),Map.of("scalar",field),"inventory-control");
    }
}
