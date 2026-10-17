package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import static org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.BIdentificationFacts.*;

class V022BIdentificationFactsTest {
    private final BIdentificationFactsExtractor extractor = new BIdentificationFactsExtractor();

    @Test void diagnosticChordChecksTheBetweenProbeMinimumWithoutGrantingACertificate() {
        assertEquals(Availability.UNAVAILABLE,extractor.connectionCertificateAvailability());
        var field = field(2, 2, new double[]{.6, 0, 0, .6}, new boolean[]{true,true,true,true});
        // Endpoints pass .4; the bilinear diagonal reaches .3 at t=.5.
        assertFalse(extractor.diagnosticChord(field, transform(), MetricRegion.rectangle(0,0,1,1),
                new MetricPoint(0,0), new MetricPoint(1,1), .4, .4, CancellationProbe.NONE));
        assertTrue(extractor.diagnosticChord(field, transform(), MetricRegion.rectangle(0,0,1,1),
                new MetricPoint(0,0), new MetricPoint(1,1), .2, .2, CancellationProbe.NONE));
    }

    @Test void ownFlankThresholdCannotBorrowAnotherPeak() {
        var field = field(2,2, new double[]{.3,.3,.3,.3}, new boolean[]{true,true,true,true});
        var region = MetricRegion.rectangle(0,0,1,1);
        assertFalse(extractor.diagnosticChord(field, transform(), region,
                new MetricPoint(.1,.1), new MetricPoint(.9,.9), .4,.4,CancellationProbe.NONE));
        assertTrue(extractor.diagnosticChord(field, transform(), region,
                new MetricPoint(.1,.1), new MetricPoint(.9,.9), .2,.2,CancellationProbe.NONE));
    }

    @Test void invalidSliverCannotBeSkippedByFiniteProbes() {
        boolean[] valid = new boolean[12]; Arrays.fill(valid,true); valid[2]=false;
        var field = field(6,2,new double[]{1,1,1,1,1,1,1,1,1,1,1,1},valid);
        assertFalse(extractor.diagnosticChord(field,transform(),MetricRegion.rectangle(0,0,5,1),
                new MetricPoint(.1,.5),new MetricPoint(4.9,.5),.2,.2,CancellationProbe.NONE));
    }

    @Test void diagnosticUsesAffineRasterCellsInEitherDirection() {
        var field=field(2,2,new double[]{.6,0,0,.6},new boolean[]{true,true,true,true});
        var affine=RasterMetricTransform.metricGrid(new MetricPoint(12,-7),2,1,-1,3);
        var region=new MetricRegion(List.of(List.of(affine.pixelCenterToMetric(0,0),
                affine.pixelCenterToMetric(1,0),affine.pixelCenterToMetric(1,1),affine.pixelCenterToMetric(0,1))));
        var start=affine.pixelCenterToMetric(0,0); var end=affine.pixelCenterToMetric(1,1);
        assertFalse(extractor.diagnosticChord(field,affine,region,start,end,.4,.4,CancellationProbe.NONE));
        assertFalse(extractor.diagnosticChord(field,affine,region,end,start,.4,.4,CancellationProbe.NONE));
        assertTrue(extractor.diagnosticChord(field,affine,region,start,end,.2,.2,CancellationProbe.NONE));
        assertTrue(extractor.diagnosticChord(field,affine,region,end,start,.2,.2,CancellationProbe.NONE));
    }

    @Test void unknownPrecisionIsUnavailableNotOneOver255() {
        var mode = facts().get(0).modes().get(0);
        assertEquals(Availability.UNAVAILABLE, mode.fact(Feature.QUANTIZATION).availability());
        assertTrue(mode.fact(Feature.QUANTIZATION).value().isEmpty());
    }

    @Test void sameRawB3B5PixelsAreCorrelated() {
        var mode = facts().get(0).modes().get(0);
        assertEquals(1,mode.provenance().stream().filter(CorrelationReceipt.class::isInstance).count());
        assertEquals(Availability.UNAVAILABLE,mode.fact(Feature.B3_CENTER).availability());
        assertEquals(Availability.UNAVAILABLE,mode.fact(Feature.B5_CENTER).availability());
    }

    @Test void oversamplingCannotCreateIndependentNativeEvidence() {
        var mode = facts().get(0).modes().get(0);
        assertEquals(Availability.UNAVAILABLE,mode.fact(Feature.INDEPENDENT_SAMPLE_COUNT).availability());
        assertEquals(1,mode.fact(Feature.NATIVE_PITCH).value().orElseThrow());
        var original=profile();
        var dense=java.util.stream.IntStream.rangeClosed(-10,10).mapToObj(i ->
                new ProbabilisticProfile.Sample(i*.5,StrictMath.exp(-.5*i*.5*i*.5),true)).toList();
        var resampled=new ProbabilisticProfile(0,0,original.anchor(),original.normalUnit(),-5,5,1,true,0,
                dense,original.modes(),List.of(),OptionalDouble.empty(),List.of(),0);
        var denseFacts=extractor.extract(snapshot(),List.of(resampled),CancellationProbe.NONE,null).get(0).modes().get(0);
        assertEquals(mode.fact(Feature.INDEPENDENT_SAMPLE_COUNT),denseFacts.fact(Feature.INDEPENDENT_SAMPLE_COUNT));
        assertEquals(mode.fact(Feature.NATIVE_PITCH),denseFacts.fact(Feature.NATIVE_PITCH));
    }

    @Test void parallelHaloConnectionDoesNotProveCenterOwnership() {
        var original = profile();
        var result = extractor.extract(snapshot(),List.of(original),CancellationProbe.NONE,null);
        assertSame(original,result.get(0).baseline());
        assertEquals(original.modes().get(0).evidenceLineage(),result.get(0).modes().get(0).lineage());
        assertEquals(original.modes(),result.get(0).baseline().modes());
    }

    @Test void cancelledExtractionPublishesNoFactsOrRetainedBytes() {
        var ledger=AttemptMemoryLedger.production();
        try (var owner=ledger.rootOwner()) {
            long before=ledger.currentBytes();
            assertThrows(CancellationException.class,()->extractor.extract(snapshot(),List.of(profile()),
                    ()->{throw new CancellationException();},owner));
            assertEquals(before,ledger.currentBytes());
        }
    }

    @Test void cancellationAfterWorkAndMemoryRefusalAreAtomic() {
        var ledger=AttemptMemoryLedger.production();
        var polls=new java.util.concurrent.atomic.AtomicInteger();
        try(var owner=ledger.rootOwner()) {
            assertThrows(CancellationException.class,()->extractor.extract(snapshot(),List.of(profile(),profile()),
                    ()->polls.incrementAndGet()>12,owner));
            assertTrue(polls.get()>12);
            assertEquals(0,ledger.currentBytes());
        }
        var tiny=new AttemptMemoryLedger(64);
        try(var owner=tiny.rootOwner()) {
            assertThrows(AttemptMemoryLedger.ResourceLimitException.class,()->
                    extractor.extract(snapshot(),List.of(profile()),CancellationProbe.NONE,owner));
            assertEquals(0,tiny.currentBytes());
        }
    }

    @Test void realFactoryDescriptorsArePreservedWithoutBorrowingUnavailableMeasurements() {
        var evidence=snapshot();
        var profiles=new ProbabilisticProfileFactory().create(List.of(new MetricPoint(1,5),new MetricPoint(9,5)),
                2,4,false,evidence,evidence.fields().get("scalar"));
        var result=extractor.extract(evidence,profiles,CancellationProbe.NONE,null);
        assertEquals(profiles.size(),result.size());
        assertTrue(profiles.stream().anyMatch(p->!p.modes().isEmpty()));
        for(int i=0;i<profiles.size();i++) {
            assertSame(profiles.get(i),result.get(i).baseline());
            assertEquals(profiles.get(i).modes().size(),result.get(i).modes().size());
            for(var mode:result.get(i).modes()) {
                assertEquals(Availability.MEASURED,mode.fact(Feature.BACKGROUND).availability());
                assertEquals(Availability.UNAVAILABLE,mode.fact(Feature.QUANTIZATION).availability());
                assertEquals(Availability.MEASURED,mode.fact(Feature.RAW_CENTER).availability(),
                        "an original tagged raw center is now exposed separately from its winner");
                assertFalse(mode.provenance().isEmpty());
            }
        }
    }

    @Test void asymmetricPlateauWinnerDoesNotBecomeARawLocalizationCenter() {
        var base=snapshot();double[] profile={.02,.02,.02,.10,.40,.90,.90,.70,.20,.02,.02};
        double[] values=new double[121];boolean[] valid=new boolean[121];Arrays.fill(valid,true);
        for(int y=0;y<11;y++) Arrays.fill(values,y*11,(y+1)*11,profile[y]);
        var evidence=new EvidenceSnapshot("asymmetric",base.coordinateFrame(),base.transform(),base.resolution(),
                base.decisionRegion(),base.evidenceRegion(),Map.of("scalar",field(11,11,values,valid)),"asymmetric");
        var profiles=new ProbabilisticProfileFactory().create(List.of(new MetricPoint(1,5),new MetricPoint(9,5)),
                2,4,false,evidence,evidence.fields().get("scalar"));
        var facts=extractor.extract(evidence,profiles,CancellationProbe.NONE,null);
        var modes=facts.stream().flatMap(p->p.modes().stream()).toList();
        assertFalse(modes.isEmpty());
        assertTrue(modes.stream().flatMap(m->m.provenance().stream()).filter(RawReceipt.class::isInstance)
                .map(RawReceipt.class::cast).anyMatch(r->r.lastPeakIndex()>r.firstPeakIndex()));
        assertTrue(modes.stream().allMatch(m->m.fact(Feature.RAW_CENTER).availability()==Availability.MEASURED));
        assertTrue(modes.stream().allMatch(m->m.fact(Feature.RAW_CENTER).value().orElseThrow()!=0),
                "asymmetric plateau localization must not collapse to the winning sample at zero");
    }

    private List<ProfileFacts> facts() {
        return extractor.extract(snapshot(),List.of(profile()),CancellationProbe.NONE,null);
    }
    @Test void factUnitsAndOriginalStageCoordinatesRejectMalformedInputs() {
        var evidence=snapshot();
        var profiles=new ProbabilisticProfileFactory().create(List.of(new MetricPoint(1,5),new MetricPoint(9,5)),
                2,4,false,evidence,evidence.fields().get("scalar"));
        var original=extractor.extract(evidence,profiles,CancellationProbe.NONE,null).stream()
                .filter(p->!p.modes().isEmpty()).findFirst().orElseThrow();
        var mode=original.modes().get(0);var values=new ArrayList<>(mode.values());
        values.set(Feature.RAW_CENTER.ordinal(),new ScalarFact(Feature.RAW_CENTER,Unit.METRE,
                Availability.MEASURED,OptionalDouble.of(mode.fact(Feature.RAW_CENTER).value().orElseThrow()+.25)));
        var receipts=new ArrayList<>(mode.provenance());
        for(int i=0;i<receipts.size();i++) if(receipts.get(i) instanceof StageReceipt stage&&stage.original().available()) {
            receipts.set(i,new StageReceipt(stage.original(),stage.firstOffsetMeters()-.25,stage.lastOffsetMeters()+.25));
        }
        assertAll(
                ()->assertThrows(IllegalArgumentException.class,()->new ScalarFact(Feature.BACKGROUND,Unit.METRE,
                        Availability.MEASURED,OptionalDouble.of(.1))),
                ()->assertThrows(IllegalArgumentException.class,()->new ProfileFacts(original.baseline(),
                        List.of(new ModeFacts(mode.lineage(),values,mode.provenance())),VERSION)),
                ()->assertThrows(IllegalArgumentException.class,()->new ProfileFacts(original.baseline(),
                        List.of(new ModeFacts(mode.lineage(),mode.values(),receipts)),VERSION)));
    }
    @Test void alteredModeCannotBorrowAnOriginalReceiptFromMatchingCoreAndPeak() {
        var evidence=snapshot();
        var profiles=new ProbabilisticProfileFactory().create(List.of(new MetricPoint(1,5),new MetricPoint(9,5)),
                2,4,false,evidence,evidence.fields().get("scalar"));
        var original=profiles.stream().filter(p->!p.modes().isEmpty()).findFirst().orElseThrow();
        var mode=original.modes().get(0);
        var altered=new ProbabilisticProfile.Mode(mode.id(),mode.evidenceLineage(),mode.coreMinimumMeters(),
                mode.coreMaximumMeters(),mode.localizationSigmaMeters()+.125,mode.existenceConfidence(),
                mode.localizationConfidence(),mode.peakOffsetsMeters(),mode.nestedCenterOffsetsMeters(),mode.groupedParent(),
                mode.orientationSupport(),mode.scalarAmplitudeReliability(),mode.branchCoherence(),mode.positionalReliability(),
                mode.leftDirectSpanMeters(),mode.rightDirectSpanMeters());
        var changed=new ProbabilisticProfile(original.profileIndex(),original.chainageMeters(),original.anchor(),
                original.normalUnit(),original.minimumOffsetMeters(),original.maximumOffsetMeters(),original.sourcePitchMeters(),
                original.nativePitchKnown(),original.noiseFloor(),original.samples(),List.of(altered),original.censoredModes(),
                original.exactAnchorOffsetMeters(),original.orientationSupport());
        var result=extractor.extract(evidence,List.of(changed),CancellationProbe.NONE,null).get(0).modes().get(0);
        assertEquals(Availability.UNAVAILABLE,result.fact(Feature.BACKGROUND).availability());
        assertTrue(result.provenance().stream().noneMatch(StageReceipt.class::isInstance));
    }
    private static ProbabilisticProfile profile() {
        var samples = java.util.stream.IntStream.rangeClosed(-5,5)
                .mapToObj(i->new ProbabilisticProfile.Sample(i,StrictMath.exp(-.5*i*i),true)).toList();
        var mode = new ProbabilisticProfile.Mode("m","m",-1,1,1,.9,.9,List.of(0.0),List.of(0.0),false);
        return new ProbabilisticProfile(0,0,new MetricPoint(5,5),new MetricPoint(0,1),-5,5,
                1,true,0,samples,List.of(mode),List.of(),OptionalDouble.empty(),List.of(),0);
    }
    private static EvidenceSnapshot snapshot() {
        double[] values=new double[121]; boolean[] valid=new boolean[121]; Arrays.fill(valid,true);
        for(int y=0;y<11;y++) for(int x=0;x<11;x++) values[y*11+x]=StrictMath.exp(-.5*(y-5)*(y-5));
        var region=MetricRegion.rectangle(0,0,10,10);
        var frame=LocalMetricFrame.certifiedEquirectangular(new GeographicPoint(0,0),
                new GeographicPoint(-1,-1),new GeographicPoint(1,1));
        return new EvidenceSnapshot("facts",frame,transform(),EvidenceResolution.nativeSource(1,1),
                region,region,Map.of("scalar",field(11,11,values,valid)),"synthetic");
    }
    private static RasterMetricTransform transform() { return RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1); }
    private static ScalarEvidenceField field(int w,int h,double[] values,boolean[] valid) {
        var lineage=new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
                EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"scalar",
                EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false);
        return new ScalarEvidenceField(w,h,values,valid,lineage);
    }
}
