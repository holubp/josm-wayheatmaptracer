package org.openstreetmap.josm.plugins.wayheatmaptracer.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.openstreetmap.josm.plugins.wayheatmaptracer.model.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.emission.NativeConnectorEvaluator.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.evidence.LocalScalarProfileExtractor;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.*;
import org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic.EvidenceModelParameters;

class NativeConnectorEvaluatorTest {
    private static final int[] WITNESS = {0, 1, 15, 16};
    private static final List<QueryPolicy> ESTABLISHED_POLICIES = List.of(
        QueryPolicy.DENSE_EIGHTH_NATIVE_V1, QueryPolicy.HALF_NATIVE_L0_V1,
        QueryPolicy.PIXEL_CAPPED_L0_V1);

    @Test void queryPoliciesRecordVersionedIdentityAndPreserveExplicitDenseNumerics() {
        try (var f = straight(false, SourceClass.OBSERVED);
             var dense = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()));
             var explicitDense = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()),
                 QueryPolicy.DENSE_EIGHTH_NATIVE_V1);
             var half = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()),
                 QueryPolicy.HALF_NATIVE_L0_V1);
             var pixel = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()),
                 QueryPolicy.PIXEL_CAPPED_L0_V1)) {
            var from = f.states().get(0);
            var to = f.states().get(1);
            var defaultReceipt = dense.evaluate(from, to);
            var explicitDenseReceipt = explicitDense.evaluate(from, to);
            var halfReceipt = half.evaluate(from, to);
            var pixelReceipt = pixel.evaluate(from, to);
            assertEquals(QueryPolicy.DENSE_EIGHTH_NATIVE_V1, defaultReceipt.queryPolicy());
            assertEquals(defaultReceipt.parameterVersion(), explicitDenseReceipt.parameterVersion());
            assertEquals(defaultReceipt.probes().get(0).samples(), explicitDenseReceipt.probes().get(0).samples());
            assertEquals(defaultReceipt.probes().get(0).selectedMode(), explicitDenseReceipt.probes().get(0).selectedMode());
            assertEquals(defaultReceipt.scalar().orElseThrow().mean(), explicitDenseReceipt.scalar().orElseThrow().mean());
            assertEquals(defaultReceipt.scalar().orElseThrow().minimum(), explicitDenseReceipt.scalar().orElseThrow().minimum());
            assertNotEquals(defaultReceipt.parameterVersion(), halfReceipt.parameterVersion());
            assertNotEquals(halfReceipt.parameterVersion(), pixelReceipt.parameterVersion());
        }
    }

    @Test void nullQueryPolicyFailsBeforeOpeningExecutionOwner() {
        try (var f = straight(false, SourceClass.OBSERVED)) {
            long retainedBytes = f.ledger().currentBytes();
            assertThrows(NullPointerException.class, () -> new NativeConnectorEvaluator(
                f.input(), new Domain(f.states(), List.of()), null));
            assertEquals(retainedBytes, f.ledger().currentBytes());
            try (var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
                assertEquals(Status.ADMITTED, evaluator.evaluate(f.states().get(0), f.states().get(1)).status());
            }
        }
    }

    @Test void oneMeterPolicyControlsRetainOriginalL0GeometryAtOneMeterOutputPitch() {
        try (var original = straight(false, SourceClass.OBSERVED);
             var oneMeter = straight(false, SourceClass.OBSERVED, 1)) {
            assertEquals(.5, original.input().context().lateralStepMeters());
            assertEquals(1, oneMeter.input().context().lateralStepMeters());
            for (int row = 0; row < 2; row++) {
                var originalProfile = original.input().profiles().levelZeroProfiles().get(row);
                var oneMeterProfile = oneMeter.input().profiles().levelZeroProfiles().get(row);
                assertEquals(originalProfile.intensitySamples(), oneMeterProfile.intensitySamples());
                assertEquals(originalProfile.peaks(), oneMeterProfile.peaks());
            }
        }
    }

    @Test void zeroGuardedLocalPolicyUsesActualLocalSamplesAndExplicitBackendReceipt() {
        try (var f = scene((x, y) -> y == 32 ? 1.0 : y == 31 || y == 33 ? 0.5 : 0.0,
                (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED,
                2, 1, RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1), budget(), 1);
             var evaluator = new NativeConnectorEvaluator(f.input(),
                 new Domain(f.states(), List.of()), QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertEquals(Status.ADMITTED, receipt.status(), receipt::toString);
            assertEquals(QueryPolicy.ZERO_GUARDED_LOCAL_V1, receipt.queryPolicy());
            assertTrue(receipt.parameterVersion().endsWith("/ZERO_GUARDED_LOCAL_V1"));
            assertEquals(3, receipt.plannedProbeCount());
            assertEquals(-1, receipt.firstFailingSlabIndex());
            assertTrue(receipt.probes().stream().allMatch(probe -> {
                var query = probe.profileQuery();
                return query.backend() == ProfileBackend.LOCAL
                    && query.halfWindowMeters() == 4.0
                    && query.sampleCount() == 17
                    && query.totalSampleQueries() == 17
                    && query.expansionCount() == 0
                    && query.fallbackCause() == FallbackCause.NONE
                    && !query.localNoModeProven();
            }), receipt.probes().stream().map(Probe::profileQuery).toList().toString());
        }
    }

    @Test void broadGaussianUsesDenseFallbackAndRetainsFullNativeEvidence() {
        try (var f=scene((x,y)->Math.exp(-0.5*Math.pow((y-32)/1.5,2)),(x,y)->true,
                MetricRegion.rectangle(0,0,63,63),SourceClass.GAP,2,1,
                RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),budget(),1);
             var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                 QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var receipt=evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertFalse(receipt.probes().isEmpty());
            assertTrue(receipt.probes().stream().allMatch(p->p.profileQuery().backend()==ProfileBackend.DENSE_FALLBACK));
            assertTrue(receipt.probes().stream().allMatch(p->p.profileQuery().halfWindowMeters()==8.0
                && p.profileQuery().sampleCount()==129 && p.profileQuery().totalSampleQueries()>129
                && p.extraction().result().baseline().noiseFloor()>0));
            assertEquals(3,receipt.plannedProbeCount());
        }
    }

    @Test void localPolicyFallsBackForExtractorMeasuredOffCenterCompetitor() {
        var directSamples=new ArrayList<LocalScalarProfileExtractor.Sample>();
        for(int i=-16;i<=16;i++) {
            double offset=i*0.5;
            directSamples.add(new LocalScalarProfileExtractor.Sample(offset,competitorProfile(offset),true));
        }
        var extracted=new LocalScalarProfileExtractor().extractDetailedWithAcceptedThresholds(directSamples,1.0,
            EvidenceModelParameters.Localization.defaults(),null,CancellationProbe.NONE,n->{ });
        assertEquals(0.0,extracted.result().baseline().noiseFloor());
        assertEquals(2,extracted.result().rawPeaks().size());
        assertEquals(2,extracted.acceptedReceipts().size());
        var competitor=extracted.acceptedReceipts().stream()
            .filter(r->r.originPeak().offsetMeters()>2).findFirst().orElseThrow();
        assertEquals(2.5,competitor.originPeak().offsetMeters(),1e-12);
        assertEquals(3.5,competitor.mode().centerMeters(),1e-12);
        assertEquals(1.0,Math.abs(competitor.originPeak().offsetMeters()-competitor.mode().centerMeters()),1e-12);
        try(var f=scene((x,y)->competitorProfile(y-32),(x,y)->true,
                MetricRegion.rectangle(0,0,63,63),SourceClass.GAP,2,20,
                RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),budget(),0.5);
            var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var receipt=evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertTrue(receipt.probes().stream().allMatch(p->p.profileQuery().backend()==ProfileBackend.DENSE_FALLBACK),
                receipt.probes().stream().map(Probe::profileQuery).toList().toString());
            assertTrue(receipt.probes().stream().allMatch(p->p.profileQuery().totalSampleQueries()
                >p.profileQuery().sampleCount()));
        }
    }

    @Test void localPolicyFallsBackWhenRemoteBrightStrandHasNoZeroSeparation() {
        try(var f=scene((x,y)->Math.max(0.2,Math.max(Math.exp(-0.5*Math.pow((y-32)/0.45,2)),
                    0.95*Math.exp(-0.5*Math.pow((y-35)/0.45,2)))),(x,y)->true,
                MetricRegion.rectangle(0,0,63,63),SourceClass.GAP,2,20,
                RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),budget(),0.5);
            var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var receipt=evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertTrue(receipt.probes().stream().allMatch(p->p.profileQuery().backend()==ProfileBackend.DENSE_FALLBACK));
            assertTrue(receipt.probes().stream().allMatch(p->p.extraction().result().baseline().noiseFloor()>0.0));
            assertTrue(receipt.probes().stream().allMatch(p->p.samples().stream()
                .filter(sample->sample.valid()).allMatch(sample->sample.intensity()>=0.2)));
            assertTrue(receipt.probes().stream().anyMatch(p->p.extraction().result().rawPeaks().stream()
                .anyMatch(peak->peak.offsetMeters()>2.5)),"Retain the remote bright competitor in dense fallback evidence");
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {true,false})
    void localPolicyKeepsRemoteBrightStrandOutsideAProvedZeroGuard(boolean zeroSeparated) {
        java.util.function.DoubleBinaryOperator intensity=(x,y)->zeroSeparated
            ? (y==32?1.0:y==37?0.95:0.0)
            : Math.max(0.2,Math.max(Math.exp(-0.5*Math.pow((y-32)/0.45,2)),
                0.95*Math.exp(-0.5*Math.pow((y-37)/0.45,2))));
        try(var f=scene(intensity,(x,y)->true,MetricRegion.rectangle(0,0,63,63),SourceClass.OBSERVED,
                2,20,RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),budget(),0.5);
            var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var receipt=evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertEquals(Status.ADMITTED,receipt.status(),receipt.toString());
            if(zeroSeparated) {
                assertTrue(receipt.probes().stream().allMatch(p->p.profileQuery().backend()==ProfileBackend.LOCAL));
                assertTrue(receipt.probes().stream().allMatch(p->p.samples().stream()
                    .anyMatch(s->s.valid()&&Math.abs(s.offsetMeters())>3*p.nativePitchMeters()&&s.intensity()==0.0)));
            } else {
                assertTrue(receipt.probes().stream().allMatch(p->p.profileQuery().backend()==ProfileBackend.DENSE_FALLBACK));
                assertTrue(receipt.probes().stream().anyMatch(p->p.extraction().result().rawPeaks().stream()
                    .anyMatch(peak->peak.offsetMeters()>3.5)));
            }
        }
    }

    private static double competitorProfile(double offset) {
        int lower=(int)Math.floor(offset);
        return competitorNode(lower)*(lower+1-offset)+competitorNode(lower+1)*(offset-lower);
    }

    private static double competitorNode(int offset) {
        return switch(offset) {
            case 0 -> 1.0;
            case 2,3 -> 1.0;
            case 4,5 -> 0.93;
            default -> 0.0;
        };
    }

    @Test void fullyObservedAllZeroLocalDomainIsTypedNoMode() {
        try (var f=scene((x,y)->0.0,(x,y)->true,MetricRegion.rectangle(0,0,63,63),SourceClass.GAP,
                2,1,RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),budget(),1);
             var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                 QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var receipt=evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertEquals(Status.REJECTED,receipt.status());
            assertEquals(Reason.LOCAL_NO_MODE,receipt.reason());
            assertTrue(receipt.probes().stream().allMatch(p->p.profileQuery().backend()==ProfileBackend.LOCAL
                && p.profileQuery().localNoModeProven() && p.samples().stream().allMatch(sample->sample.valid())));
        }
    }

    @Test void localPolicyReceiptIdentifiesTheFirstFinalThresholdSlabFailure() {
        try (var f=scene((x,y)->(x<19.25?1.0:0.4)*Math.exp(-0.5*Math.pow(y-32,2)),(x,y)->true,
                MetricRegion.rectangle(0,0,63,63),SourceClass.OBSERVED,2,20,
                RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),budget(),1);
             var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                 QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var receipt=evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertEquals(Status.REJECTED,receipt.status(),receipt::toString);
            assertEquals(Reason.SCALAR_BELOW_ENVELOPE,receipt.reason());
            assertTrue(receipt.plannedProbeCount()>=receipt.probes().size());
            assertTrue(receipt.firstFailingSlabIndex()>=0);
            assertTrue(receipt.measuredNativeContinuationComplete());
        }
    }

    @Test void rightNeighborFallbackSettlesMiddleBeforeTheLeftSlabThresholdCheck() {
        java.util.function.DoubleUnaryOperator profile=offset -> offset==0?1.0:offset<=0.5?0.85:
            offset<=1?0.55:offset<=1.5?0.2:offset<=4?0.0:0.3;
        var provisionalSamples=new ArrayList<LocalScalarProfileExtractor.Sample>();
        for(int i=-8;i<=8;i++)provisionalSamples.add(new LocalScalarProfileExtractor.Sample(i*0.5,
            profile.applyAsDouble(Math.abs(i*0.5)),true));
        var provisional=new LocalScalarProfileExtractor().extractDetailedWithAcceptedThresholds(provisionalSamples,
            1.0,EvidenceModelParameters.Localization.defaults(),null,CancellationProbe.NONE,n->{ });
        assertEquals(0.0,provisional.result().baseline().noiseFloor());
        assertEquals(1,provisional.result().baseline().modes().size());
        assertEquals(0.72,provisional.acceptedReceipts().get(0).shoulderThreshold(),1e-12);
        var resolution=new EvidenceResolution(EvidenceResolution.Kind.NATIVE_SOURCE,
            java.util.OptionalDouble.of(1),0.5,List.of(
                new EvidenceResolution.PitchSample(0,java.util.OptionalDouble.of(1),0.5),
                new EvidenceResolution.PitchSample(0.5,java.util.OptionalDouble.of(1),0.5),
                new EvidenceResolution.PitchSample(1,java.util.OptionalDouble.of(5),0.5)),java.util.OptionalDouble.of(0.5));
        try (var f=scene((x,y)->{
                    double offset=Math.abs((y-(x==12?35:32))*0.5);
                    if(x==10)return 0.76*profile.applyAsDouble(offset);
                    if(x==11)return profile.applyAsDouble(offset);
                    if(x==12)return y>=32 && y<=38?1.0:0.0;
                    return 0.0;
                },(x,y)->true,MetricRegion.rectangle(0,0,31.5,31.5),SourceClass.OBSERVED,2,1,
                RasterMetricTransform.metricGrid(new MetricPoint(0,0),0.5,0,0,0.5),budget(),1,resolution,25);
             var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                 QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var receipt=evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertEquals(3,receipt.plannedProbeCount(),receipt::toString);
            assertEquals(3,receipt.probes().size(),receipt::toString);
            var left=receipt.probes().get(0);var middle=receipt.probes().get(1);var right=receipt.probes().get(2);
            assertEquals(ProfileBackend.LOCAL,left.profileQuery().backend());
            assertEquals(ProfileBackend.DENSE_FALLBACK,middle.profileQuery().backend());
            assertEquals(FallbackCause.CROSS_PROBE_SUPPORT_UNAVAILABLE,middle.profileQuery().fallbackCause());
            assertEquals(401,middle.profileQuery().sampleCount());
            assertEquals(418,middle.profileQuery().totalSampleQueries());
            assertEquals(0,middle.profileQuery().expansionCount());
            assertTrue(middle.threshold().shoulderThreshold()>provisional.acceptedReceipts().get(0).shoulderThreshold());
            assertEquals(0.76,receipt.scalar().orElseThrow().minimum().orElseThrow(),1e-6);
            assertTrue(left.threshold().shoulderThreshold()<0.76);
            assertTrue(middle.threshold().shoulderThreshold()>0.76,middle.threshold().toString());
            assertEquals(Status.REJECTED,receipt.status(),receipt::toString);
            assertEquals(Reason.SCALAR_BELOW_ENVELOPE,receipt.reason());
            assertEquals(0,receipt.firstFailingSlabIndex());
            assertTrue(receipt.measuredNativeContinuationComplete());
            assertTrue(right.selectedMode().distanceToCenterSet(0)<=right.selectedMode().localizationSigmaMeters());
        }
    }

    @Test void leftNeighborFallbackSettlesMiddleBeforeTheRightSlabThresholdCheck() {
        java.util.function.DoubleUnaryOperator profile=offset -> offset==0?1.0:offset<=0.5?0.85:
            offset<=1?0.55:offset<=1.5?0.2:offset<=4?0.0:0.3;
        var resolution=new EvidenceResolution(EvidenceResolution.Kind.NATIVE_SOURCE,
            java.util.OptionalDouble.of(5),0.5,List.of(
                new EvidenceResolution.PitchSample(0,java.util.OptionalDouble.of(5),0.5),
                new EvidenceResolution.PitchSample(0.5,java.util.OptionalDouble.of(1),0.5),
                new EvidenceResolution.PitchSample(1,java.util.OptionalDouble.of(1),0.5)),java.util.OptionalDouble.of(0.5));
        try(var f=scene((x,y)->{
                    double xx=22-x;
                    double offset=Math.abs((y-(xx==12?35:32))*0.5);
                    if(xx==10)return 0.76*profile.applyAsDouble(offset);
                    if(xx==11)return profile.applyAsDouble(offset);
                    if(xx==12)return y>=32&&y<=38?1.0:0.0;
                    return 0.0;
                },(x,y)->true,MetricRegion.rectangle(0,0,31.5,31.5),SourceClass.OBSERVED,2,1,
                RasterMetricTransform.metricGrid(new MetricPoint(0,0),0.5,0,0,0.5),budget(),1,resolution,25);
            var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var receipt=evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertEquals(3,receipt.plannedProbeCount(),receipt.toString());
            assertEquals(3,receipt.probes().size(),receipt.toString());
            var left=receipt.probes().get(0);var middle=receipt.probes().get(1);var right=receipt.probes().get(2);
            assertEquals(ProfileBackend.DENSE_FALLBACK,middle.profileQuery().backend());
            assertEquals(FallbackCause.CROSS_PROBE_SUPPORT_UNAVAILABLE,middle.profileQuery().fallbackCause());
            assertEquals(401,middle.profileQuery().sampleCount());
            assertEquals(418,middle.profileQuery().totalSampleQueries());
            assertEquals(0,middle.profileQuery().expansionCount());
            assertTrue(middle.threshold().shoulderThreshold()>0.72);
            assertEquals(0.76,receipt.scalar().orElseThrow().minimum().orElseThrow(),1e-6);
            assertTrue(left.threshold().shoulderThreshold()<0.76);
            assertTrue(middle.threshold().shoulderThreshold()>0.76,middle.threshold().toString());
            assertEquals(Status.REJECTED,receipt.status(),receipt.toString());
            assertEquals(Reason.SCALAR_BELOW_ENVELOPE,receipt.reason());
            assertEquals(1,receipt.firstFailingSlabIndex());
            assertTrue(receipt.measuredNativeContinuationComplete());
            assertTrue(right.selectedMode().distanceToCenterSet(0)<=right.selectedMode().localizationSigmaMeters());
        }
    }

    @Test void oneSidedLocalGuardFallsBackWithoutManufacturingTheMissingSide() {
        try (var f=scene((x,y)->y==32?1.0:0.0,(x,y)->true,
                MetricRegion.rectangle(0,0,63,32),SourceClass.OBSERVED,2,20,
                RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),budget(),1);
             var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                 QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var receipt=evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertNotEquals(Status.ADMITTED,receipt.status());
            assertTrue(receipt.probes().stream().anyMatch(p->p.profileQuery().backend()==ProfileBackend.DENSE_FALLBACK));
            assertTrue(receipt.probes().stream().filter(p->p.profileQuery().backend()==ProfileBackend.DENSE_FALLBACK)
                .allMatch(p->p.profileQuery().fallbackCause()!=FallbackCause.NONE));
            assertTrue(receipt.probes().stream().filter(p->p.profileQuery().backend()==ProfileBackend.DENSE_FALLBACK)
                .allMatch(p->p.profileQuery().expansionCount()>0
                    && p.profileQuery().totalSampleQueries()>p.profileQuery().sampleCount()));
        }
    }

    @Test void localPolicyRetainsSplitForkAsARealCompetitorFailure() {
        try (var f=scene((x,y)->x<19?Math.exp(-0.5*Math.pow(y-32,2)):
                    Math.max(Math.exp(-0.5*Math.pow(y-29,2)),Math.exp(-0.5*Math.pow(y-35,2))),
                (x,y)->true,MetricRegion.rectangle(0,0,63,63),SourceClass.OBSERVED,2,20,
                RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),budget(),1);
             var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                 QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var receipt=evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertNotEquals(Status.ADMITTED,receipt.status(),receipt::toString);
            assertNotEquals(Reason.ENDPOINT_OWNERSHIP,receipt.reason());
            assertFalse(receipt.measuredNativeContinuationComplete());
            assertTrue(receipt.probes().stream().allMatch(p->p.profileQuery().backend()!=ProfileBackend.FULL_WINDOW));
        }
    }

    @Test void localWindowUsesFactualSpatialPitchAlongTheConnector() {
        var resolution=new EvidenceResolution(EvidenceResolution.Kind.NATIVE_SOURCE,
            java.util.OptionalDouble.of(1),1,List.of(
                new EvidenceResolution.PitchSample(0,java.util.OptionalDouble.of(1),1),
                new EvidenceResolution.PitchSample(20,java.util.OptionalDouble.of(2),1)),java.util.OptionalDouble.empty());
        try (var f=scene((x,y)->y==32?1.0:0.0,(x,y)->true,
                MetricRegion.rectangle(0,0,63,63),SourceClass.OBSERVED,2,20,
                RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),budget(),1,resolution);
             var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                 QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var receipt=evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertEquals(41,receipt.plannedProbeCount());
            assertTrue(receipt.probes().get(0).nativePitchMeters()<receipt.probes().get(receipt.probes().size()-1).nativePitchMeters());
            assertTrue(receipt.probes().stream().allMatch(p->p.profileQuery().backend()!=ProfileBackend.FULL_WINDOW));
            assertTrue(receipt.probes().get(0).profileQuery().halfWindowMeters()
                < receipt.probes().get(receipt.probes().size()-1).profileQuery().halfWindowMeters());
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void localCrossProbeRetainsSupportWhenFactualPitchChangesInEitherDirection(boolean reverse) {
        double first=reverse?2:1, last=reverse?1:2;
        var resolution=new EvidenceResolution(EvidenceResolution.Kind.NATIVE_SOURCE,
            java.util.OptionalDouble.of(first),1,List.of(
                new EvidenceResolution.PitchSample(0,java.util.OptionalDouble.of(first),1),
                new EvidenceResolution.PitchSample(20,java.util.OptionalDouble.of(last),1)),java.util.OptionalDouble.empty());
        try(var f=scene((x,y)->y==32?1.0:y==31||y==33?0.5:0.0,(x,y)->true,
                MetricRegion.rectangle(0,0,63,63),SourceClass.OBSERVED,2,20,
                RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),budget(),0.5,resolution);
            var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var receipt=evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertEquals(Status.ADMITTED,receipt.status(),receipt.toString());
            assertEquals(first,receipt.probes().get(0).nativePitchMeters(),1e-12);
            assertEquals(last,receipt.probes().get(receipt.probes().size()-1).nativePitchMeters(),1e-12);
            assertTrue(receipt.probes().stream().allMatch(p->p.profileQuery().backend()==ProfileBackend.LOCAL));
        }
    }

    @Test void fractionalLocalStepKeepsIntegerCenteredLatticeThroughExpansion() {
        var resolution=new EvidenceResolution(EvidenceResolution.Kind.NATIVE_SOURCE,
            java.util.OptionalDouble.of(2),0.2,List.of(
                new EvidenceResolution.PitchSample(0,java.util.OptionalDouble.of(2),0.2)),java.util.OptionalDouble.of(0.2));
        try (var f=scene((x,y)->{
                    double offset=Math.abs((y-32)*0.2);
                    return offset==0?1.0:offset<=0.2?0.5:0.0;
                },(x,y)->true,MetricRegion.rectangle(0,0,12.6,12.6),SourceClass.OBSERVED,2,0.2,
                RasterMetricTransform.metricGrid(new MetricPoint(0,0),0.2,0,0,0.2),budget(),0.01,resolution,6.2);
             var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                 QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var receipt=evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertEquals(Status.ADMITTED,receipt.status(),receipt::toString);
            for(var probe:receipt.probes()) {
                var evidence=probe.profileQuery();
                assertEquals(ProfileBackend.LOCAL,evidence.backend());
                assertEquals(6.01,evidence.halfWindowMeters(),1e-12);
                assertEquals(99,evidence.expansionCount());
                assertEquals(1203,evidence.sampleCount());
                long finalK=Math.round(evidence.halfWindowMeters()/0.01);
                long firstK=finalK-evidence.expansionCount();
                long expectedQueries=evidence.expansionCount()+1L;
                expectedQueries=Math.multiplyExact(expectedQueries,firstK+finalK+1);
                assertEquals(expectedQueries,evidence.totalSampleQueries());
                assertEquals(0.0,probe.samples().get((probe.samples().size()-1)/2).offsetMeters());
                for(int i=0;i<probe.samples().size()/2;i++) {
                    assertEquals(-probe.samples().get(probe.samples().size()-1-i).offsetMeters(),
                        probe.samples().get(i).offsetMeters());
                }
            }
        }
    }

    @Test void fractionalLocalStepSamplesExactCenterOnInitialAllZeroWindow() {
        var resolution=new EvidenceResolution(EvidenceResolution.Kind.NATIVE_SOURCE,
            java.util.OptionalDouble.of(2),0.2,List.of(
                new EvidenceResolution.PitchSample(0,java.util.OptionalDouble.of(2),0.2)),java.util.OptionalDouble.of(0.2));
        try (var f=scene((x,y)->0.0,(x,y)->true,MetricRegion.rectangle(0,0,12.6,12.6),
                SourceClass.GAP,2,0.2,RasterMetricTransform.metricGrid(new MetricPoint(0,0),0.2,0,0,0.2),
                budget(),0.01,resolution,6.2);
             var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                 QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var receipt=evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertEquals(Status.REJECTED,receipt.status());
            assertEquals(Reason.LOCAL_NO_MODE,receipt.reason());
            for(var probe:receipt.probes()) {
                var evidence=probe.profileQuery();
                assertEquals(ProfileBackend.LOCAL,evidence.backend());
                assertEquals(5.02,evidence.halfWindowMeters(),1e-12);
                assertEquals(1005,evidence.sampleCount());
                assertEquals(1005,evidence.totalSampleQueries());
                assertEquals(0.0,probe.samples().get(502).offsetMeters());
                for(int i=0;i<probe.samples().size()/2;i++)
                    assertEquals(-probe.samples().get(probe.samples().size()-1-i).offsetMeters(),
                        probe.samples().get(i).offsetMeters());
            }
        }
    }

    @Test void admitsActualR4NativeWitnessForBothEnginesThroughRetainedCroppedField() throws Exception {
        var original = PhysicalGeometryFixture.straightRidgeThroughParabola(4);
        // This is the production crop, not a full-image field or a profile-derived raster.
        int minX=18,minY=17,w=93,h=86;
        double[] values=new double[w*h];
        for(int y=0;y<h;y++) for(int x=0;x<w;x++)
            values[y*w+x]=Math.round(255*Math.exp(-0.5*Math.pow((minY+y-64)/0.8,2)))/255.0;
        var evidence=evidence(w,h,values,RasterMetricTransform.metricGrid(new MetricPoint(minX,minY),1,0,0,1),
            MetricRegion.rectangle(minX,minY,110,102));
        var locations=new ArrayList<DetachedProfileSamplingLocation>();
        double distance=0; RasterPoint previous=null;
        for(int j=-9;j<=9;j++) {
            var r=new RasterPoint(64+j-minX,64-9.7+j*j/8.0-minY);
            if(previous!=null)distance+=Math.hypot(r.x()-previous.x(),r.y()-previous.y());
            locations.add(at(evidence,r,distance)); previous=r;
        }
        var input=new CorridorTraceInput(locations,1);
        var chainage=new ProfileChainage(locations.stream().map(DetachedProfileSamplingLocation::cumulativeGroundDistanceMeters).toList(),1);
        var ledger=AttemptMemoryLedger.production();
        try(var owner=ledger.rootOwner();var retained=new DetachedScalarProfileSampler().sampleDeclaredSyntheticForEmission(
                evidence,"native",input,chainage,25,budget(),owner)) {
            assertFalse(retained.context().productionEligible());
            assertEquals(0,retained.context().nativeGrid().originX());
            assertEquals(0,retained.context().nativeGrid().originY());
            assertEquals(w,retained.context().nativeGrid().width());
            assertEquals(h,retained.context().nativeGrid().height());
            // Pixel conversion is factual captured transform: local grid y47 is full-image y64.
            assertEquals(64,retained.context().rasterTransform().pixelCenterToMetric(0,47).yMeters(),0);
            var result=new CorridorAwareTracker().trackDetailed(original,1,JunctionContext.empty());
            var selected=result.candidates().get(0);
            var track=result.tracks().stream().filter(t->t.id().equals(selected.id())).findFirst().orElseThrow();
            var tube=result.tubes().get(selected.id());
            var optimization=result.optimizations().get(selected.id());
            var optimizer=new CorridorCenterlineOptimizer();
            for(String engine:List.of("A","Legacy")) {
                var states=new ArrayList<RowState>();
                for(int row=0;row<original.size();row++) {
                    var profile=original.get(row);
                    var peaks=retained.profiles().levelZeroProfiles().get(row+1).peaks();
                    assertEquals(1,peaks.size());
                    List<Double> offsets;
                    if(engine.equals("A")) {
                        @SuppressWarnings("unchecked") var actual=(List<Double>)invoke(optimizer,"allowedOffsets",track,result.profiles(),row,
                            1.0,JunctionContext.empty(),result.scaleEvidence(),tube.at(row),optimization.endpointApproaches());
                        offsets=actual;
                    } else offsets=List.of(profile.peaks().get(0).offsetPx());
                    int best=0;
                    for(int k=1;k<offsets.size();k++)
                        if(Math.abs(profile.anchorScreen().y+profile.normalScreen().y*offsets.get(k)-64)
                           <Math.abs(profile.anchorScreen().y+profile.normalScreen().y*offsets.get(best)-64))best=k;
                    states.add(new RowState(row+1,best,offsets.get(best),SourceClass.OBSERVED,peaks.get(0)));
                    var factual=retained.context().observations().get(row+1);
                    var local=factual.location().rasterPoint();var n=factual.normal();
                    var point=retained.context().rasterTransform().pixelCenterToMetric(local.x()+n.x()*offsets.get(best),local.y()+n.y()*offsets.get(best));
                    if(Arrays.stream(WITNESS).anyMatch(i->i==factual.index()-1))assertEquals(64,point.yMeters(),engine.equals("A")?.001:.08);
                }
                try(var evaluator=new NativeConnectorEvaluator(retained,new Domain(states,List.of()))) {
                    for(int i=1;i<WITNESS.length;i++) {
                        var receipt=evaluator.evaluate(states.get(WITNESS[i-1]),states.get(WITNESS[i]));
                        assertEquals(Status.ADMITTED,receipt.status(),engine+" "+WITNESS[i-1]+"->"+WITNESS[i]+" "+receipt);
                    }
                }
                var expectedSampleCounts = Map.of(QueryPolicy.DENSE_EIGHTH_NATIVE_V1, 401,
                    QueryPolicy.HALF_NATIVE_L0_V1, 101, QueryPolicy.PIXEL_CAPPED_L0_V1, 51);
                for (var policy : ESTABLISHED_POLICIES) {
                    try (var evaluator = new NativeConnectorEvaluator(retained, new Domain(states,List.of()), policy)) {
                        for (int i = 1; i < WITNESS.length; i++) {
                            var receipt = evaluator.evaluate(states.get(WITNESS[i - 1]), states.get(WITNESS[i]));
                            assertEquals(Status.ADMITTED, receipt.status(), engine + " " + policy + " "
                                + WITNESS[i - 1] + "->" + WITNESS[i] + " " + receipt);
                            assertTrue(receipt.scalarEnvelopeComplete(), policy.name());
                            assertTrue(receipt.measuredNativeContinuationComplete(), policy.name());
                            assertTrue(receipt.probes().stream().allMatch(probe -> probe.selectedMode() != null), policy.name());
                            assertTrue(receipt.from().peakOwner() != null && !receipt.from().peakOwner().syntheticCenter(), policy.name());
                            assertTrue(receipt.to().peakOwner() != null && !receipt.to().peakOwner().syntheticCenter(), policy.name());
                            assertTrue(receipt.probes().stream().allMatch(probe ->
                                probe.samples().size() == expectedSampleCounts.get(policy)), policy.name());
                            assertTrue(receipt.probes().stream().allMatch(probe ->
                                probe.profileQuery().backend() == ProfileBackend.FULL_WINDOW), policy.name());
                        }
                    }
                }
                try (var evaluator=new NativeConnectorEvaluator(retained,new Domain(states,List.of()),
                        QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
                    for(int i=1;i<WITNESS.length;i++) {
                        var receipt=evaluator.evaluate(states.get(WITNESS[i-1]),states.get(WITNESS[i]));
                        assertEquals(Status.ADMITTED,receipt.status(),engine+" local "+WITNESS[i-1]+"->"+WITNESS[i]+" "+receipt);
                        assertTrue(receipt.scalarEnvelopeComplete() && receipt.measuredNativeContinuationComplete());
                        assertTrue(receipt.probes().stream().allMatch(p->p.selectedMode()!=null
                            && p.profileQuery().backend()!=ProfileBackend.FULL_WINDOW));
                    }
                }
            }
        }
        assertEquals(0,ledger.currentBytes());
    }
    @ParameterizedTest @EnumSource(value = QueryPolicy.class, names = {"DENSE_EIGHTH_NATIVE_V1", "HALF_NATIVE_L0_V1", "PIXEL_CAPPED_L0_V1"})
    void rejectsUniformBrightFieldWithoutNativeRidge(QueryPolicy policy) {
        try(var f=straight(true,SourceClass.GAP,1)) {
            try(var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),policy)) {
                assertEquals(Status.REJECTED,evaluator.evaluate(f.states().get(0),f.states().get(1)).status());
            }
        }
    }

    @Test void uniformBrightLocalPolicyFallsBackWithoutCallingItAnAllZeroNeighborhood() {
        try(var f=straight(true,SourceClass.GAP,1);
            var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var receipt=evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertEquals(Status.REJECTED,receipt.status());
            assertEquals(Reason.NO_COMPLETE_MODE,receipt.reason());
            assertTrue(receipt.probes().stream().allMatch(p->p.profileQuery().backend()==ProfileBackend.DENSE_FALLBACK
                && !p.profileQuery().localNoModeProven()));
        }
    }

    @Test void localPolicyKeepsWorkBudgetFailureStickyInsteadOfTryingFallback() {
        try(var f=straight(false,SourceClass.OBSERVED,1);
            var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var failure=assertThrows(EmissionWorkBudget.LimitExceededException.class,
                ()->f.input().workBudget().charge(EmissionWorkBudget.Operation.PREPARATION,Long.MAX_VALUE));
            assertSame(failure,assertThrows(EmissionWorkBudget.LimitExceededException.class,
                ()->evaluator.evaluate(f.states().get(0),f.states().get(1))));
            assertSame(failure,assertThrows(EmissionWorkBudget.LimitExceededException.class,
                ()->evaluator.evaluate(f.states().get(0),f.states().get(1))));
        }
    }

    @Test void cancellationAtFirstExtractionBoundaryKeepsLocalSamplingBoundedAndSticky() {
        var local=cancelAtFirstExtractionBoundary(QueryPolicy.ZERO_GUARDED_LOCAL_V1);
        var dense=cancelAtFirstExtractionBoundary(QueryPolicy.DENSE_EIGHTH_NATIVE_V1);
        assertTrue(local.modeExtractionWork()>0 && dense.modeExtractionWork()>0);
        assertTrue(local.cellQueries()>0 && local.nodeReads()>0);
        assertTrue(local.cellQueries()<dense.cellQueries(),local+" vs "+dense);
        assertTrue(local.nodeReads()<dense.nodeReads(),local+" vs "+dense);
    }

    private record CancelledSampling(long cellQueries,long nodeReads,long modeExtractionWork) { }

    private static CancelledSampling cancelAtFirstExtractionBoundary(QueryPolicy policy) {
        EmissionWorkBudget[] holder=new EmissionWorkBudget[1];
        var armed=new java.util.concurrent.atomic.AtomicBoolean();
        var baselineModeWork=new java.util.concurrent.atomic.AtomicLong();
        var work=new EmissionWorkBudget(TraceBudgets.defaults(),()->armed.get() && holder[0]!=null
            && holder[0].snapshot().operationUnits().get(EmissionWorkBudget.Operation.MODE_EXTRACTION)
                >baselineModeWork.get());
        holder[0]=work;
        try(var f=scene((x,y)->y==32?1.0:y==31||y==33?0.5:0.0,(x,y)->true,
                MetricRegion.rectangle(0,0,63,63),SourceClass.OBSERVED,2,20,
                RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),work,1);
            var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),policy)) {
            long baselineWork=work.snapshot().workUsed();
            long baselineCells=work.snapshot().operationUnits().get(EmissionWorkBudget.Operation.CELL_QUERY);
            long baselineNodes=work.snapshot().operationUnits().get(EmissionWorkBudget.Operation.NODE_READ);
            baselineModeWork.set(work.snapshot().operationUnits().get(EmissionWorkBudget.Operation.MODE_EXTRACTION));
            armed.set(true);
            var failure=assertThrows(java.util.concurrent.CancellationException.class,
                ()->evaluator.evaluate(f.states().get(0),f.states().get(1)));
            var after=work.snapshot();
            assertSame(failure,assertThrows(java.util.concurrent.CancellationException.class,
                ()->evaluator.evaluate(f.states().get(0),f.states().get(1))));
            assertEquals(after.workUsed(),work.snapshot().workUsed());
            assertTrue(after.workUsed()>baselineWork);
            return new CancelledSampling(after.operationUnits().get(EmissionWorkBudget.Operation.CELL_QUERY)-baselineCells,
                after.operationUnits().get(EmissionWorkBudget.Operation.NODE_READ)-baselineNodes,
                after.operationUnits().get(EmissionWorkBudget.Operation.MODE_EXTRACTION)-baselineModeWork.get());
        }
    }

    @Test void localInEvaluationWorkLimitIsStickyBeforeDenseFallback() {
        EmissionWorkBudget[] holder=new EmissionWorkBudget[1];
        var armed=new java.util.concurrent.atomic.AtomicBoolean();
        var injected=new java.util.concurrent.atomic.AtomicBoolean();
        var baselineModeWork=new java.util.concurrent.atomic.AtomicLong();
        var work=new EmissionWorkBudget(TraceBudgets.defaults(),()->{
            if(armed.get() && !injected.get() && holder[0].snapshot().operationUnits()
                    .get(EmissionWorkBudget.Operation.MODE_EXTRACTION)>baselineModeWork.get()) {
                injected.set(true);
                var snapshot=holder[0].snapshot();
                holder[0].charge(EmissionWorkBudget.Operation.PREPARATION,
                    snapshot.workLimit()-snapshot.workUsed());
            }
            return false;
        });
        holder[0]=work;
        try(var f=scene((x,y)->y==32?1.0:y==31||y==33?0.5:0.0,(x,y)->true,
                MetricRegion.rectangle(0,0,63,63),SourceClass.OBSERVED,2,20,
                RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),work,1);
            var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            baselineModeWork.set(work.snapshot().operationUnits().get(EmissionWorkBudget.Operation.MODE_EXTRACTION));
            armed.set(true);
            var failure=assertThrows(EmissionWorkBudget.LimitExceededException.class,
                ()->evaluator.evaluate(f.states().get(0),f.states().get(1)));
            assertEquals(EmissionWorkBudget.LimitKind.TOTAL_WORK,failure.kind());
            assertTrue(injected.get(),"Work pressure must be injected at the first in-local-extractor boundary");
            assertTrue(work.snapshot().operationUnits().get(EmissionWorkBudget.Operation.MODE_EXTRACTION)>baselineModeWork.get());
            long charged=work.snapshot().workUsed();
            assertSame(failure,assertThrows(EmissionWorkBudget.LimitExceededException.class,
                ()->evaluator.evaluate(f.states().get(0),f.states().get(1))));
            assertEquals(charged,work.snapshot().workUsed());
        }
        assertEquals(work.snapshot().workLimit(),work.snapshot().workUsed());
    }

    @Test void localExtractorMemoryRefusalIsStickyAfterItsScratchIsReleased() {
        try(var f=straight(false,SourceClass.OBSERVED)) {
            long retained=f.ledger().currentBytes();
            LocalScalarProfileExtractor.ResourceLimitException failure;
            try(var held=f.owner().reserve(f.ledger().limitBytes()-retained-90*1024L);
                var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                    QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
                failure=assertThrows(LocalScalarProfileExtractor.ResourceLimitException.class,
                    ()->evaluator.evaluate(f.states().get(0),f.states().get(1)));
                assertInstanceOf(AttemptMemoryLedger.ResourceLimitException.class,failure.getCause());
                assertTrue(f.input().workBudget().snapshot().operationUnits()
                    .get(EmissionWorkBudget.Operation.MODE_EXTRACTION)>0);
            }
            assertEquals(retained,f.ledger().currentBytes());
            assertSame(failure,assertThrows(LocalScalarProfileExtractor.ResourceLimitException.class,
                ()->new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                    QueryPolicy.ZERO_GUARDED_LOCAL_V1)));
            assertEquals(retained,f.ledger().currentBytes());
        }
    }

    @Test void halfSampleGaussianWithTinyIntegerSupportCannotBorrowMissingOldProducerOwnership() {
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow((y - 32.5) / 0.12, 2)),
                (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.GAP,
                2, 20, RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1), budget(), 1)) {
            assertTrue(f.states().stream().allMatch(state -> state.sourceClass() == SourceClass.GAP
                && state.peakOwner() == null));
            assertTrue(f.input().profiles().levelZeroProfiles().stream()
                .allMatch(profile -> profile.peaks().get(0).syntheticCenter()));
            assertEquals(1.698566775303334e-4,
                f.input().profiles().levelZeroProfiles().get(0).peaks().get(0).maxProfileIntensity(), 1e-15);
            for (var policy : ESTABLISHED_POLICIES) {
                try (var evaluator = new NativeConnectorEvaluator(f.input(),
                        new Domain(f.states(), List.of()), policy)) {
                    var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
                    assertNotEquals(Status.ADMITTED, receipt.status(), policy.name() + " " + receipt);
                    assertEquals(Reason.ENDPOINT_OWNERSHIP, receipt.reason(), policy.name());
                    assertFalse(receipt.probes().isEmpty(), policy.name());
                    assertTrue(receipt.probes().stream().allMatch(probe -> probe.selectedMode() != null),
                        "Retain the faint measured modes rather than calling the pixels no-signal");
                    assertTrue(receipt.measuredNativeContinuationComplete(), policy.name());
                    assertTrue(receipt.scalar().isPresent(), policy.name());
                }
            }
            try(var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                    QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
                var receipt=evaluator.evaluate(f.states().get(0),f.states().get(1));
                assertNotEquals(Status.ADMITTED,receipt.status());
                assertEquals(Reason.ENDPOINT_OWNERSHIP,receipt.reason());
                assertTrue(receipt.probes().stream().allMatch(p->p.selectedMode()!=null
                    && !p.profileQuery().localNoModeProven()));
            }
        }
    }

    @Test void areaRasterizedHalfSampleStrokeHasGenuineOwnedCenterForEveryPolicy() {
        try (var f = scene((x, y) -> y == 32 || y == 33 ? 0.5 : 0.0,
                (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED,
                2, 20, RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1), budget(), 1)) {
            var ownedStates = new ArrayList<RowState>();
            assertEquals(1.0, f.input().context().lateralStepMeters());
            for (var state : f.states()) {
                var peak = state.peakOwner();
                assertNotNull(peak);
                assertFalse(peak.syntheticCenter());
                assertTrue(peak.offsetPx() > 0.4 && peak.offsetPx() < 0.6,
                    "Equal area coverage at rows 32/33 should center the measured peak at 32.5");
                ownedStates.add(new RowState(state.row(), state.nativeStateId(), peak.offsetPx(),
                    SourceClass.OBSERVED, peak));
            }
            var expectedSampleCounts = Map.of(QueryPolicy.DENSE_EIGHTH_NATIVE_V1, 129,
                QueryPolicy.HALF_NATIVE_L0_V1, 33, QueryPolicy.PIXEL_CAPPED_L0_V1, 17);
            for (var policy : ESTABLISHED_POLICIES) {
                try (var evaluator = new NativeConnectorEvaluator(f.input(),
                        new Domain(ownedStates, List.of()), policy)) {
                    var receipt = evaluator.evaluate(ownedStates.get(0), ownedStates.get(1));
                    assertEquals(Status.ADMITTED, receipt.status(), policy.name() + " " + receipt);
                    assertEquals(expectedSampleCounts.get(policy), receipt.probes().get(0).samples().size(), policy.name());
                }
            }
            try (var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(ownedStates,List.of()),
                    QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
                var receipt=evaluator.evaluate(ownedStates.get(0),ownedStates.get(1));
                assertEquals(Status.ADMITTED,receipt.status(),receipt::toString);
                assertTrue(receipt.probes().stream().allMatch(p->p.profileQuery().backend()==ProfileBackend.LOCAL
                    && p.samples().size()==17));
            }
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(doubles = {0.25, 0.5, 0.75})
    void localPolicyAdmitsAreaRasterizedThinStrokeAtShiftedPhases(double phase) {
        try (var f=scene((x,y)->y==32?1-phase:y==33?phase:0.0,(x,y)->true,
                MetricRegion.rectangle(0,0,63,63),SourceClass.OBSERVED,2,20,
                RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),budget(),1)) {
            var states=new ArrayList<RowState>();
            for(var state:f.states()) {
                var owner=state.peakOwner();
                assertNotNull(owner);
                assertFalse(owner.syntheticCenter());
                assertTrue(Math.abs(owner.offsetPx()-phase)<=0.5,
                    "Measured owned center remains within one source-pixel localization bound");
                states.add(new RowState(state.row(),state.nativeStateId(),owner.offsetPx(),SourceClass.OBSERVED,owner));
            }
            try(var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(states,List.of()),
                    QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
                var receipt=evaluator.evaluate(states.get(0),states.get(1));
                assertEquals(Status.ADMITTED,receipt.status(),receipt.toString());
                assertTrue(receipt.probes().stream().allMatch(p->p.profileQuery().backend()==ProfileBackend.LOCAL));
            }
        }
    }

    @ParameterizedTest @EnumSource(value = QueryPolicy.class, names = {"DENSE_EIGHTH_NATIVE_V1", "HALF_NATIVE_L0_V1", "PIXEL_CAPPED_L0_V1"})
    void admitsMeasuredStraightRidgeWithOriginalPeakOwnership(QueryPolicy policy) {
        try (var f = straight(false, SourceClass.OBSERVED,1);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()), policy)) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertEquals(Status.ADMITTED, receipt.status(), receipt::toString);
            assertTrue(receipt.scalarEnvelopeComplete());
            assertTrue(receipt.measuredNativeContinuationComplete());
            assertEquals(20.0, receipt.emittedLengthMeters(), 1e-12);
            assertEquals(20.0, receipt.sourceSpanMeters(), 1e-12);
        }
    }

    @ParameterizedTest @EnumSource(value = QueryPolicy.class, names = {"DENSE_EIGHTH_NATIVE_V1", "HALF_NATIVE_L0_V1", "PIXEL_CAPPED_L0_V1"})
    void freshUnownedPortsCannotBindEachOtherWithoutAnOriginalBranchOwner(QueryPolicy policy) {
        try (var f = straight(false, SourceClass.GAP,1);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()), policy)) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertEquals(Status.UNAVAILABLE, receipt.status());
            assertEquals(Reason.ENDPOINT_OWNERSHIP, receipt.reason());
        }
    }

    @Test void rejectsEqualValuedForeignStateIdentity() {
        try (var f = straight(false, SourceClass.OBSERVED);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            var nativeState = f.states().get(0);
            var foreign = new RowState(nativeState.row(), nativeState.nativeStateId(),
                nativeState.offsetRasterPixels(), nativeState.sourceClass(), nativeState.peakOwner());
            var receipt = evaluator.evaluate(foreign, f.states().get(1));
            assertEquals(Status.UNAVAILABLE, receipt.status());
            assertEquals(Reason.FOREIGN_STATE, receipt.reason());
        }
    }

    @Test void refusesUseAfterInputCloses() {
        try (var f = straight(false, SourceClass.OBSERVED);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            f.input().close();
            assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(f.states().get(0), f.states().get(1)));
        }
    }

    @Test void rejectsDuplicatedNativeRowStateIdInDeclaredDomain() {
        try (var f = straight(false, SourceClass.OBSERVED)) {
            var state = f.states().get(0);
            var duplicate = new RowState(state.row(), state.nativeStateId(), 0.5,
                state.sourceClass(), state.peakOwner());
            assertThrows(IllegalArgumentException.class, () -> new NativeConnectorEvaluator(f.input(),
                new Domain(List.of(state, duplicate, f.states().get(1)), List.of())));
        }
    }

    @Test void reportsOutOfRangeStateBeforeProjectingOverflowingOffset() {
        try (var f = straight(false, SourceClass.OBSERVED)) {
            var state = f.states().get(0);
            var invalid = new RowState(state.row(), state.nativeStateId(), Double.MAX_VALUE,
                state.sourceClass(), state.peakOwner());
            try (var evaluator = new NativeConnectorEvaluator(f.input(),
                    new Domain(List.of(invalid, f.states().get(1)), List.of()))) {
                var receipt = evaluator.evaluate(invalid, f.states().get(1));
                assertEquals(Status.UNAVAILABLE, receipt.status());
                assertEquals(Reason.INVALID_STATE, receipt.reason());
            }
        }
    }

    @ParameterizedTest @EnumSource(value = QueryPolicy.class, names = {"DENSE_EIGHTH_NATIVE_V1", "HALF_NATIVE_L0_V1", "PIXEL_CAPPED_L0_V1", "ZERO_GUARDED_LOCAL_V1"})
    void brightEndpointsDoNotAuthorizeAnInteriorDarkGap(QueryPolicy policy) {
        try (var f = scene((x, y) -> Math.abs(x - 20) <= 1 ? 0
                    : Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED, 1);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()), policy)) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertNotEquals(Status.ADMITTED, receipt.status());
            assertEquals(0.0, receipt.scalar().orElseThrow().minimum().orElseThrow());
        }
    }

    @ParameterizedTest @EnumSource(value = QueryPolicy.class, names = {"DENSE_EIGHTH_NATIVE_V1", "HALF_NATIVE_L0_V1", "PIXEL_CAPPED_L0_V1"})
    void invalidInteriorCellIsNotInterpolatedAcross(QueryPolicy policy) {
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> x != 20 || y != 32, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED, 1);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()), policy)) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertEquals(Status.UNAVAILABLE, receipt.status());
            assertEquals(Reason.SCALAR_SUPPORT, receipt.reason());
        }
    }

    @ParameterizedTest @EnumSource(value = QueryPolicy.class, names = {"DENSE_EIGHTH_NATIVE_V1", "HALF_NATIVE_L0_V1", "PIXEL_CAPPED_L0_V1", "ZERO_GUARDED_LOCAL_V1"})
    void invalidCellTouchedOnlyAtConnectorCornerRemainsUnsupported(QueryPolicy policy) {
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> x != 20 || y != 33, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED, 1);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()), policy)) {
            assertTrue(f.states().stream().allMatch(state -> state.peakOwner() != null
                && !state.peakOwner().syntheticCenter()));
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertEquals(Status.UNAVAILABLE, receipt.status(), policy.name());
            assertEquals(Reason.SCALAR_SUPPORT, receipt.reason(), policy.name());
        }
    }

    @ParameterizedTest @EnumSource(value = QueryPolicy.class, names = {"DENSE_EIGHTH_NATIVE_V1", "HALF_NATIVE_L0_V1", "PIXEL_CAPPED_L0_V1"})
    void coarsePoliciesConservativelyLoseLocalModeAtMaskedSupportEdge(QueryPolicy policy) {
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> x != 20 || y != 34, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED, 1);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()), policy)) {
            assertTrue(f.states().stream().allMatch(state -> state.peakOwner() != null
                && !state.peakOwner().syntheticCenter()));
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertTrue(receipt.scalar().isPresent());
            assertEquals(BilinearSegmentIntegral.Status.COMPLETE, receipt.scalar().orElseThrow().status());
            if (policy == QueryPolicy.DENSE_EIGHTH_NATIVE_V1) {
                assertEquals(Status.ADMITTED, receipt.status(), receipt::toString);
                assertTrue(receipt.scalarEnvelopeComplete());
                assertTrue(receipt.measuredNativeContinuationComplete());
                var edgeProbe = receipt.probes().stream()
                    .filter(probe -> Math.abs(probe.metricPosition().xMeters() - 19) < 1e-12)
                    .findFirst().orElseThrow();
                var selected = edgeProbe.selectedMode();
                assertNotNull(selected);
                assertEquals(0, selected.centerMeters(), 1e-12);
                assertEquals(.5, selected.localizationSigmaMeters(), 1e-12);
                var threshold = edgeProbe.threshold();
                assertNotNull(threshold);
                assertEquals(0, threshold.originPeak().offsetMeters(), 1e-12);
                assertEquals(1, threshold.originPeak().value(), 1e-12);
                assertEquals(.903600013256073, threshold.shoulderThreshold(), 1e-12);
                for (double offset : new double[] {-.25, .25}) {
                    var sample = edgeProbe.samples().stream()
                        .filter(value -> Math.abs(value.offsetMeters() - offset) < 1e-12)
                        .findFirst().orElseThrow();
                    assertTrue(sample.valid());
                    assertEquals(.9016326665878296, sample.intensity(), 1e-12);
                    assertTrue(sample.intensity() < threshold.shoulderThreshold());
                }
                var flank = edgeProbe.extraction().result().flankReceipts().stream()
                    .filter(value -> value.mode() == selected).findFirst().orElseThrow();
                assertEquals(62, flank.leftBracketSampleIndex());
                assertEquals(66, flank.rightBracketSampleIndex());
                assertTrue(flank.hasTwoSidedValidOutwardContinuation());
                var core = edgeProbe.extraction().result().rawCoreReceipts().stream()
                    .filter(value -> value.mode() == selected).findFirst().orElseThrow();
                assertEquals(64, core.rawStageWinnerSampleIndex());
                assertTrue(core.leftAdjacentObservedValid() && core.leftAdjacentBelowCore());
                assertTrue(core.rightAdjacentObservedValid() && core.rightAdjacentBelowCore());
                assertTrue(edgeProbe.extraction().result().baseline().censoredModes().stream()
                    .allMatch(mode -> Math.abs(mode.boundaryOffsetMeters()) > 1.5 * edgeProbe.nativePitchMeters()));
            } else {
                assertEquals(Status.REJECTED, receipt.status(), policy.name());
                assertEquals(Reason.NO_COMPLETE_MODE, receipt.reason(), policy.name());
                assertFalse(receipt.measuredNativeContinuationComplete());
                var lastProbe = receipt.probes().get(receipt.probes().size() - 1);
                assertEquals(19, lastProbe.metricPosition().xMeters(), 1e-12);
                assertEquals(.45, lastProbe.parameter(), 1e-12);
                assertNull(lastProbe.selectedMode());
                assertNull(lastProbe.threshold());
                assertTrue(lastProbe.samples().stream().anyMatch(sample -> !sample.valid()
                    && Math.abs(sample.offsetMeters() - 2) < 1e-12));
            }
        }
    }

    @Test void localPolicyFallsBackAtTheSameCensoredMaskAndUsesDenseSelectedEvidence() {
        try (var f=scene((x,y)->Math.exp(-0.5*Math.pow(y-32,2)),
                (x,y)->x!=20 || y!=34,MetricRegion.rectangle(0,0,63,63),SourceClass.OBSERVED,2,20,
                RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),budget(),1);
             var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                 QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var receipt=evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertEquals(Status.ADMITTED,receipt.status(),receipt::toString);
            var edge=receipt.probes().stream().filter(p->Math.abs(p.metricPosition().xMeters()-19)<1e-12)
                .findFirst().orElseThrow();
            assertEquals(ProfileBackend.DENSE_FALLBACK,edge.profileQuery().backend());
            assertNotNull(edge.selectedMode());
            assertNotNull(edge.threshold());
            assertTrue(edge.profileQuery().totalSampleQueries()>edge.profileQuery().sampleCount());
        }
    }

    @ParameterizedTest @EnumSource(value = QueryPolicy.class, names = {"DENSE_EIGHTH_NATIVE_V1", "HALF_NATIVE_L0_V1", "PIXEL_CAPPED_L0_V1", "ZERO_GUARDED_LOCAL_V1"})
    void decisionAuthorityMustCoverWholeConnectorIncludingNarrowNotch(QueryPolicy policy) {
        var authority = new MetricRegion(List.of(List.of(new MetricPoint(0, 0),
            new MetricPoint(63, 0), new MetricPoint(63, 63), new MetricPoint(20.01, 63),
            new MetricPoint(20.01, 31.99), new MetricPoint(19.99, 31.99),
            new MetricPoint(19.99, 63), new MetricPoint(0, 63))));
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> true, authority, SourceClass.OBSERVED, 1);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()), policy)) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertEquals(Status.REJECTED, receipt.status());
            assertEquals(Reason.DECISION_AUTHORITY, receipt.reason());
        }
    }

    @ParameterizedTest @EnumSource(value = QueryPolicy.class, names = {"DENSE_EIGHTH_NATIVE_V1", "HALF_NATIVE_L0_V1", "PIXEL_CAPPED_L0_V1"})
    void oneFreshGapPortCanBindThroughDirectEvidenceToAnOwnedPort(QueryPolicy policy) {
        try (var f = straight(false, SourceClass.OBSERVED,1)) {
            var gap = new RowState(0, 0, 0, SourceClass.GAP, null);
            try (var evaluator = new NativeConnectorEvaluator(f.input(),
                    new Domain(List.of(gap, f.states().get(1)), List.of()), policy)) {
                var receipt = evaluator.evaluate(gap, f.states().get(1));
                assertEquals(Status.ADMITTED, receipt.status(), receipt::toString);
                assertEquals(SourceClass.GAP, receipt.from().sourceClass());
                assertNull(receipt.from().peakOwner());
            }
        }
    }

    @Test void refusesProtectedEventEvenWhenItsEndpointsHaveBrightSupport() {
        try (var f = straight(false, SourceClass.OBSERVED);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of(),
                 List.of(new Barrier("protected-occurrence", 0, 1, 10, new MetricPoint(20, 32)))))) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertEquals(Status.REJECTED, receipt.status());
            assertEquals(Reason.MANDATORY_BARRIER, receipt.reason());
        }
    }

    @Test void exactSixteenConsumedRowsAndTwentyMetersAreAllowed() {
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED, 18, 20);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(17));
            assertEquals(Status.ADMITTED, receipt.status(), receipt::toString);
            assertEquals(20, receipt.sourceSpanMeters());
        }
    }

    @Test void seventeenthConsumedRowIsRejectedWithoutNarrowingStates() {
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED, 19, 20);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(18));
            assertEquals(Status.REJECTED, receipt.status());
            assertEquals(Reason.SPAN, receipt.reason());
        }
    }

    @Test void sourceSpanBeyondTwentyMetersIsRejected() {
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED, 2, 20.001);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            assertEquals(Reason.SPAN, evaluator.evaluate(f.states().get(0), f.states().get(1)).reason());
        }
    }

    @Test void anisotropicFramesAreRejectedByTheRetainedInputFactory() {
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 2);
        assertThrows(IllegalArgumentException.class, () -> {
            try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                    (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 126), SourceClass.OBSERVED, 2, 20, transform)) {
                // Admission never receives unsupported anisotropy; do not bypass that guard.
            }
        });
    }

    @Test void invalidAffineStateIsRefusedBeforeProjectionCanOverflow() {
        var transform = RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 2, 0, 0, 2);
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> true, MetricRegion.rectangle(0, 0, 126, 126), SourceClass.OBSERVED, 2, 20, transform)) {
            var outside = new RowState(0, 0, Double.MAX_VALUE, SourceClass.OBSERVED, f.states().get(0).peakOwner());
            try (var evaluator = new NativeConnectorEvaluator(f.input(),
                    new Domain(List.of(outside, f.states().get(1)), List.of()))) {
                var receipt = assertDoesNotThrow(() -> evaluator.evaluate(outside, f.states().get(1)));
                assertEquals(Reason.INVALID_STATE, receipt.reason());
            }
        }
    }

    @ParameterizedTest @EnumSource(value = QueryPolicy.class, names = {"DENSE_EIGHTH_NATIVE_V1", "HALF_NATIVE_L0_V1", "PIXEL_CAPPED_L0_V1"})
    void representableSubEventToleranceNotchCannotBeSkipped(QueryPolicy policy) {
        double left = 20 - 0.5e-9, right = 20 + 0.5e-9;
        var authority = new MetricRegion(List.of(
            MetricRegion.rectangle(0, 0, left, 63).polygons().get(0),
            MetricRegion.rectangle(right, 0, 63, 63).polygons().get(0)));
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> true, authority, SourceClass.OBSERVED, 1);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()), policy)) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertNotEquals(Status.ADMITTED, receipt.status(), "A representable authority hole is not permission");
            assertEquals(Reason.DECISION_AUTHORITY, receipt.reason());
        }
    }

    @Test void anExhaustedAttemptCannotContinueWithAnotherConnector() {
        try (var f = straight(false, SourceClass.OBSERVED);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            var failure = assertThrows(EmissionWorkBudget.LimitExceededException.class,
                () -> f.input().workBudget().charge(EmissionWorkBudget.Operation.PREPARATION, Long.MAX_VALUE));
            assertSame(failure, assertThrows(EmissionWorkBudget.LimitExceededException.class,
                () -> evaluator.evaluate(f.states().get(0), f.states().get(1))));
        }
    }

    @Test void memoryRefusalCannotBeResetByOpeningAnotherSessionOnTheSameAttempt() {
        try (var f = straight(false, SourceClass.OBSERVED);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            AttemptMemoryLedger.ResourceLimitException failure;
            // Simulate retained work owned by another phase, leaving only2KiB for admission.
            try (var held = f.owner().reserve(f.ledger().limitBytes() - f.ledger().currentBytes() - 2048)) {
                failure = assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                    () -> evaluator.evaluate(f.states().get(0), f.states().get(1)));
                assertSame(failure, assertThrows(AttemptMemoryLedger.ResourceLimitException.class,
                    () -> evaluator.evaluate(f.states().get(0), f.states().get(1))));
            }
            assertSame(failure, assertThrows(AttemptMemoryLedger.ResourceLimitException.class, () -> {
                try (var retried = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
                    // Releasing scratch must not turn a failed attempt into a new numerical run.
                }
            }));
        }
    }

    @Test void extractorMemoryRefusalIsTerminalAcrossSessionsAfterScratchIsReleased() {
        try (var f = straight(false, SourceClass.OBSERVED)) {
            long retainedBytes = f.ledger().currentBytes();
            LocalScalarProfileExtractor.ResourceLimitException failure;
            // Leave enough room for connector setup and transverse samples, but force a real
            // allocation refusal inside detailed extraction rather than at evaluator setup.
            try (var held = f.owner().reserve(f.ledger().limitBytes() - retainedBytes - 90 * 1024L);
                 var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
                failure = assertThrows(LocalScalarProfileExtractor.ResourceLimitException.class,
                    () -> evaluator.evaluate(f.states().get(0), f.states().get(1)));
                assertInstanceOf(AttemptMemoryLedger.ResourceLimitException.class, failure.getCause());
            }
            assertEquals(retainedBytes, f.ledger().currentBytes(), "failed extractor scratch and pressure must be released");
            var retry = assertThrows(LocalScalarProfileExtractor.ResourceLimitException.class,
                () -> new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of())));
            assertSame(failure, retry, "the retained input must preserve the first terminal failure identity");
            assertEquals(retainedBytes, f.ledger().currentBytes());
        }
    }

    @Test void deterministicCancellationDuringEvaluationIsStickyAcrossSessions() {
        var remaining = new java.util.concurrent.atomic.AtomicInteger(-1);
        var work = new EmissionWorkBudget(TraceBudgets.defaults(),
            () -> remaining.get() >= 0 && remaining.getAndDecrement() == 0);
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED, 2, 20,
                RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1), work);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            long before = work.snapshot().workUsed();
            remaining.set(20);
            var failure = assertThrows(java.util.concurrent.CancellationException.class,
                () -> evaluator.evaluate(f.states().get(0), f.states().get(1)));
            assertTrue(work.snapshot().workUsed() > before);
            long terminalWork = work.snapshot().workUsed();
            assertSame(failure, assertThrows(java.util.concurrent.CancellationException.class,
                () -> evaluator.evaluate(f.states().get(0), f.states().get(1))));
            assertSame(failure, assertThrows(java.util.concurrent.CancellationException.class, () -> {
                try (var retried = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) { }
            }));
            assertEquals(terminalWork, work.snapshot().workUsed());
        }
    }

    @ParameterizedTest @EnumSource(value = QueryPolicy.class, names = {"DENSE_EIGHTH_NATIVE_V1", "HALF_NATIVE_L0_V1", "PIXEL_CAPPED_L0_V1"})
    void completeBroadPlateauRemainsAdmissible(QueryPolicy policy) {
            try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(Math.max(0, Math.abs(y - 32) - 2), 2)),
                    (x, y) -> true,
                    MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED, 1);
                 var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()), policy)) {
                var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
                assertEquals(Status.ADMITTED, receipt.status(), receipt::toString);
            }
    }

    @ParameterizedTest @EnumSource(value = QueryPolicy.class, names = {"DENSE_EIGHTH_NATIVE_V1", "HALF_NATIVE_L0_V1", "PIXEL_CAPPED_L0_V1"})
    void faintConcentratedEndpointCanBindWithoutRelabellingSyntheticSourceAsObserved(QueryPolicy policy) {
        try (var f = scene((x, y) -> (0.1 + 0.6 * Math.max(0, Math.min(1, (30 - x) / 20)))
                    * Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED, 1)) {
            assertFalse(f.states().get(0).peakOwner().syntheticCenter());
            assertTrue(f.states().get(1).peakOwner().syntheticCenter(),
                "Original sampler supplies a fallback at intensity0.1, not an observed peak");
            var gap = new RowState(1, 0, 0, SourceClass.GAP, null);
            try (var evaluator = new NativeConnectorEvaluator(f.input(),
                    new Domain(List.of(f.states().get(0), gap), List.of()), policy)) {
                var receipt = evaluator.evaluate(f.states().get(0), gap);
                assertEquals(Status.ADMITTED, receipt.status(), receipt::toString);
                assertEquals(SourceClass.GAP, receipt.to().sourceClass());
                assertNull(receipt.to().peakOwner());
            }
            try (var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(List.of(f.states().get(0),gap),List.of()),
                    QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
                var receipt=evaluator.evaluate(f.states().get(0),gap);
                assertEquals(Status.ADMITTED,receipt.status(),receipt::toString);
                assertEquals(SourceClass.GAP,receipt.to().sourceClass());
            }
        }
    }

    @Test void offConnectorInvalidPixelDoesNotIntroduceADiskTopologyVeto() {
        try (var f = scene((x, y) -> Math.exp(-0.5 * Math.pow(y - 32, 2)),
                (x, y) -> x != 20 || y != 45, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()))) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertEquals(Status.ADMITTED, receipt.status(), receipt::toString);
        }
    }

    @ParameterizedTest @EnumSource(value = QueryPolicy.class, names = {"DENSE_EIGHTH_NATIVE_V1", "HALF_NATIVE_L0_V1", "PIXEL_CAPPED_L0_V1", "ZERO_GUARDED_LOCAL_V1"})
    void measuredForkCannotAuthorizeTheStraightDarkMiddle(QueryPolicy policy) {
        try (var f = scene((x, y) -> x < 19 ? Math.exp(-0.5 * Math.pow(y - 32, 2))
                    : Math.max(Math.exp(-0.5 * Math.pow(y - 29, 2)),
                        Math.exp(-0.5 * Math.pow(y - 35, 2))),
                (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), SourceClass.OBSERVED, 1);
             var evaluator = new NativeConnectorEvaluator(f.input(), new Domain(f.states(), List.of()), policy)) {
            var receipt = evaluator.evaluate(f.states().get(0), f.states().get(1));
            assertNotEquals(Status.ADMITTED, receipt.status());
            assertNotEquals(Reason.ENDPOINT_OWNERSHIP, receipt.reason());
            assertFalse(receipt.measuredNativeContinuationComplete());
        }
    }

    @Test void localPolicyAdmitsSupportedGentleTurnWithObservedCenterlineEvidence() {
        try(var f=scene((x,y)->Math.exp(-0.5*Math.pow((y-(32+0.005*Math.pow(x-20,2)))/0.7,2)),
                (x,y)->true,MetricRegion.rectangle(0,0,63,63),SourceClass.OBSERVED,2,20,
                RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1),budget(),0.5);
            var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var receipt=evaluator.evaluate(f.states().get(0),f.states().get(1));
            assertEquals(Status.ADMITTED,receipt.status(),receipt.toString());
            assertTrue(receipt.probes().stream().allMatch(p->p.selectedMode()!=null
                && !p.profileQuery().localNoModeProven()));
            assertTrue(receipt.measuredNativeContinuationComplete());
        }
    }

    @Test void localPolicyAdmitsBothObservedArcsOfAQuantizedGentleTurn() {
        try(var f=guided((x,y)->quantizedTubeDistance(x,y,12,20,20,20,28,24),
                List.of(new RasterPoint(12,20),new RasterPoint(20,20),new RasterPoint(28,24)),1);
            var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var first=evaluator.evaluate(f.states().get(0),f.states().get(1));
            var second=evaluator.evaluate(f.states().get(1),f.states().get(2));
            var chord=evaluator.evaluate(f.states().get(0),f.states().get(2));
            assertEquals(Status.ADMITTED,first.status(),first.toString());
            assertEquals(Status.ADMITTED,second.status(),second.toString());
            assertNotEquals(Status.ADMITTED,chord.status(),chord.toString());
            assertTrue(chord.scalar().isPresent());
            assertTrue(chord.scalar().orElseThrow().minimum().orElseThrow()<0.3,chord.toString());
        }
    }

    @Test void localPolicyRejectsDarkCrossStrandJumpBesideSupportedArc() {
        try(var f=guided((x,y)->Math.max(quantizedTubeDistance(x,y,10,20,30,20),
                    quantizedTubeDistance(x,y,10,24,30,24)),
                List.of(new RasterPoint(12,20),new RasterPoint(28,20),new RasterPoint(28,24)),1);
            var evaluator=new NativeConnectorEvaluator(f.input(),new Domain(f.states(),List.of()),
                QueryPolicy.ZERO_GUARDED_LOCAL_V1)) {
            var supported=evaluator.evaluate(f.states().get(0),f.states().get(1));
            var jump=evaluator.evaluate(f.states().get(0),f.states().get(2));
            assertEquals(Status.ADMITTED,supported.status(),supported.toString());
            assertNotEquals(Status.ADMITTED,jump.status(),jump.toString());
            assertTrue(jump.scalar().isPresent());
            assertTrue(jump.scalar().orElseThrow().minimum().orElseThrow()<0.3,jump.toString());
        }
    }

    private static double quantizedTubeDistance(double x,double y,double x0,double y0,double x1,double y1,
            double x2,double y2) {
        double distance=Math.min(segmentDistance(x,y,x0,y0,x1,y1),segmentDistance(x,y,x1,y1,x2,y2));
        if(distance>3)return 0.0;
        return Math.round(255*Math.exp(-0.5*distance*distance))/255.0;
    }

    private static double quantizedTubeDistance(double x,double y,double x1,double y1,double x2,double y2) {
        double distance=segmentDistance(x,y,x1,y1,x2,y2);
        if(distance>3)return 0.0;
        return Math.round(255*Math.exp(-0.5*distance*distance))/255.0;
    }

    private static double segmentDistance(double x,double y,double x1,double y1,double x2,double y2) {
        double dx=x2-x1,dy=y2-y1,denominator=dx*dx+dy*dy;
        double t=denominator==0?0:Math.max(0,Math.min(1,((x-x1)*dx+(y-y1)*dy)/denominator));
        return Math.hypot(x-(x1+t*dx),y-(y1+t*dy));
    }

    @ParameterizedTest @EnumSource(value = QueryPolicy.class, names = {"DENSE_EIGHTH_NATIVE_V1", "HALF_NATIVE_L0_V1", "PIXEL_CAPPED_L0_V1", "ZERO_GUARDED_LOCAL_V1"})
    void zeroLengthEdgeRequiresAndTransportsAnActualIssuedIncomingArc(QueryPolicy policy) {
        int w = 64, h = 64;
        double[] values = new double[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            values[y * w + x] = Math.exp(-0.5 * Math.pow(y - 15, 2));
        var evidence = evidence(w, h, values,
            RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1),
            MetricRegion.rectangle(0, 0, 63, 63));
        var guide = List.of(new RasterPoint(10, 20), new RasterPoint(20, 10),
            new RasterPoint(20, 20), new RasterPoint(30, 10));
        var locations = new ArrayList<DetachedProfileSamplingLocation>();
        double distance = 0;
        for (int row = 0; row < guide.size(); row++) {
            if (row > 0) distance += Math.hypot(guide.get(row).x() - guide.get(row - 1).x(),
                guide.get(row).y() - guide.get(row - 1).y());
            locations.add(at(evidence, guide.get(row), distance));
        }
        var ledger = AttemptMemoryLedger.production();
        try (var owner = ledger.rootOwner();
             var sampled = new DetachedScalarProfileSampler().sampleDeclaredSyntheticForEmission(
                 evidence, "native", new CorridorTraceInput(locations, 1),
                 new ProfileChainage(locations.stream().map(
                     DetachedProfileSamplingLocation::cumulativeGroundDistanceMeters).toList(), 1),
                 12, budget(), owner)) {
            var states = new ArrayList<RowState>();
            double[] offsets = {-5 * Math.sqrt(2), 5, -5, 5 * Math.sqrt(2)};
            for (int row = 0; row < 4; row++) states.add(new RowState(row, 0, offsets[row],
                SourceClass.OBSERVED, sampled.profiles().levelZeroProfiles().get(row).peaks().get(0)));
            try (var evaluator = new NativeConnectorEvaluator(sampled, new Domain(states, List.of()), policy)) {
                assertEquals(Reason.INCOMING_DIRECTION_UNAVAILABLE,
                    evaluator.evaluate(states.get(1), states.get(2)).reason());
                var incoming = evaluator.evaluate(states.get(0), states.get(1));
                assertEquals(Status.ADMITTED, incoming.status(), incoming::toString);
                var zero = evaluator.evaluate(states.get(1), states.get(2), incoming);
                assertEquals(Status.ADMITTED, zero.status(), zero::toString);
                assertEquals(0, zero.emittedLengthMeters());
                assertSame(incoming, zero.transportedIncoming().orElseThrow());
                assertTrue(zero.sourceSpanMeters() > 0);
                var forged = new Receipt(incoming.status(), incoming.reason(), incoming.context(),
                    incoming.from(), incoming.to(), incoming.queryPolicy(), incoming.parameterVersion(), incoming.sourceSpanMeters(),
                    incoming.emittedLengthMeters(), incoming.minimumNativePitchMeters(), incoming.scalar(),
                    incoming.scalarEnvelopeComplete(), incoming.measuredNativeContinuationComplete(),
                    incoming.probes(), incoming.transportedIncoming());
                assertEquals(Reason.INCOMING_DIRECTION_UNAVAILABLE,
                    evaluator.evaluate(states.get(1), states.get(2), forged).reason());
            }
        }
        assertEquals(0, ledger.currentBytes());
    }

    private record Straight(AttemptMemoryLedger ledger,AttemptMemoryLedger.Owner owner,
            SampledEmissionInput input,List<RowState> states) implements AutoCloseable {
        @Override public void close(){ input.close(); owner.close(); assertEquals(0,ledger.currentBytes()); }
    }
    private static Straight straight(boolean uniform,SourceClass sourceClass) {
        return scene((x, y) -> uniform ? 1 : Math.exp(-.5 * Math.pow(y - 32, 2)),
            (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), sourceClass);
    }
    private static Straight straight(boolean uniform,SourceClass sourceClass,double requestedStep) {
        return scene((x, y) -> uniform ? 1 : Math.exp(-.5 * Math.pow(y - 32, 2)),
            (x, y) -> true, MetricRegion.rectangle(0, 0, 63, 63), sourceClass,
            2, 20, RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1), budget(), requestedStep);
    }
    private static Straight scene(java.util.function.DoubleBinaryOperator intensity,
            java.util.function.BiPredicate<Integer, Integer> validity, MetricRegion decision, SourceClass sourceClass) {
        return scene(intensity, validity, decision, sourceClass, 2, 20);
    }
    private static Straight scene(java.util.function.DoubleBinaryOperator intensity,
            java.util.function.BiPredicate<Integer, Integer> validity, MetricRegion decision,
            SourceClass sourceClass, double requestedStep) {
        return scene(intensity, validity, decision, sourceClass, 2, 20,
            RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1), budget(), requestedStep);
    }
    private static Straight scene(java.util.function.DoubleBinaryOperator intensity,
            java.util.function.BiPredicate<Integer, Integer> validity, MetricRegion decision,
            SourceClass sourceClass, int rows, double span) {
        return scene(intensity, validity, decision, sourceClass, rows, span,
            RasterMetricTransform.metricGrid(new MetricPoint(0, 0), 1, 0, 0, 1));
    }
    private static Straight scene(java.util.function.DoubleBinaryOperator intensity,
            java.util.function.BiPredicate<Integer, Integer> validity, MetricRegion decision,
            SourceClass sourceClass, int rows, double span, RasterMetricTransform transform) {
        return scene(intensity, validity, decision, sourceClass, rows, span, transform, budget());
    }
    private static Straight scene(java.util.function.DoubleBinaryOperator intensity,
            java.util.function.BiPredicate<Integer, Integer> validity, MetricRegion decision,
            SourceClass sourceClass, int rows, double span, RasterMetricTransform transform,
            EmissionWorkBudget work) {
        return scene(intensity, validity, decision, sourceClass, rows, span, transform, work, .5);
    }
    private static Straight scene(java.util.function.DoubleBinaryOperator intensity,
            java.util.function.BiPredicate<Integer, Integer> validity, MetricRegion decision,
            SourceClass sourceClass, int rows, double span, RasterMetricTransform transform,
            EmissionWorkBudget work, double requestedStep) {
        return scene(intensity,validity,decision,sourceClass,rows,span,transform,work,requestedStep,null);
    }
    private static Straight scene(java.util.function.DoubleBinaryOperator intensity,
            java.util.function.BiPredicate<Integer, Integer> validity, MetricRegion decision,
            SourceClass sourceClass, int rows, double span, RasterMetricTransform transform,
            EmissionWorkBudget work, double requestedStep, EvidenceResolution resolution) {
        return scene(intensity,validity,decision,sourceClass,rows,span,transform,work,requestedStep,resolution,8);
    }
    private static Straight scene(java.util.function.DoubleBinaryOperator intensity,
            java.util.function.BiPredicate<Integer, Integer> validity, MetricRegion decision,
            SourceClass sourceClass, int rows, double span, RasterMetricTransform transform,
            EmissionWorkBudget work, double requestedStep, EvidenceResolution resolution,double searchHalfWidth) {
        int w=64,h=64; double[] values=new double[w*h];
        boolean[] valid = new boolean[w * h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            values[y*w+x]=intensity.applyAsDouble(x,y);
            valid[y*w+x]=validity.test(x,y);
        }
        var evidence=evidence(w,h,values,valid,transform,decision,resolution);
        var locations = new ArrayList<DetachedProfileSamplingLocation>();
        for (int row = 0; row < rows; row++) {
            double chainage = span * row / (rows - 1);
            locations.add(at(evidence, new RasterPoint(10 + chainage / evidence.resolution().outputRasterPitchMeters(), 32), chainage));
        }
        var input=new CorridorTraceInput(locations,requestedStep);
        var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();
        SampledEmissionInput sampled;
        try {
            sampled=new DetachedScalarProfileSampler().sampleDeclaredSyntheticForEmission(evidence,"native",input,
                new ProfileChainage(locations.stream().map(DetachedProfileSamplingLocation::cumulativeGroundDistanceMeters).toList(), span / (rows - 1)),
                searchHalfWidth,work,owner);
        } catch (RuntimeException failure) {
            owner.close();
            assertEquals(0, ledger.currentBytes());
            throw failure;
        }
        var states=new ArrayList<RowState>();
        for(int i=0;i<rows;i++)states.add(new RowState(i,0,0,sourceClass,sourceClass==SourceClass.OBSERVED?sampled.profiles().levelZeroProfiles().get(i).peaks().get(0):null));
        return new Straight(ledger,owner,sampled,states);
    }
    private static Straight guided(java.util.function.DoubleBinaryOperator intensity,List<RasterPoint> guide,
            double requestedStep) {
        int w=64,h=64;double[] values=new double[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)values[y*w+x]=intensity.applyAsDouble(x,y);
        var transform=RasterMetricTransform.metricGrid(new MetricPoint(0,0),1,0,0,1);
        var evidence=evidence(w,h,values,transform,MetricRegion.rectangle(0,0,63,63));
        var locations=new ArrayList<DetachedProfileSamplingLocation>();
        var distances=new ArrayList<Double>();double distance=0;
        for(int i=0;i<guide.size();i++) {
            if(i>0)distance+=Math.hypot(guide.get(i).x()-guide.get(i-1).x(),guide.get(i).y()-guide.get(i-1).y());
            distances.add(distance);locations.add(at(evidence,guide.get(i),distance));
        }
        var ledger=AttemptMemoryLedger.production();var owner=ledger.rootOwner();
        var input=new CorridorTraceInput(locations,requestedStep);
        SampledEmissionInput sampled;
        try {
            sampled=new DetachedScalarProfileSampler().sampleDeclaredSyntheticForEmission(evidence,"native",input,
                new ProfileChainage(distances,1.0),8,budget(),owner);
        } catch(RuntimeException failure) {
            owner.close();assertEquals(0,ledger.currentBytes());throw failure;
        }
        var states=new ArrayList<RowState>();
        for(int i=0;i<guide.size();i++) {
            var peak=sampled.profiles().levelZeroProfiles().get(i).peaks().get(0);
            states.add(new RowState(i,0,peak.offsetPx(),SourceClass.OBSERVED,peak));
        }
        return new Straight(ledger,owner,sampled,states);
    }
    private static Object invoke(Object object,String name,Object...args)throws Exception {
        Method m=Arrays.stream(object.getClass().getDeclaredMethods()).filter(x->x.getName().equals(name)&&x.getParameterCount()==args.length).findFirst().orElseThrow();
        m.setAccessible(true);return m.invoke(object,args);
    }
    private static EmissionWorkBudget budget(){return new EmissionWorkBudget(TraceBudgets.defaults(),CancellationProbe.NONE);}
    private static EvidenceSnapshot evidence(int w,int h,double[] values,RasterMetricTransform transform,MetricRegion decision) {
        boolean[] valid=new boolean[w*h]; Arrays.fill(valid,true);
        return evidence(w,h,values,valid,transform,decision);
    }
    private static EvidenceSnapshot evidence(int w,int h,double[] values,boolean[] valid,RasterMetricTransform transform,MetricRegion decision) {
        return evidence(w,h,values,valid,transform,decision,null);
    }
    private static EvidenceSnapshot evidence(int w,int h,double[] values,boolean[] valid,RasterMetricTransform transform,
            MetricRegion decision,EvidenceResolution resolution) {
        var origin=new GeographicPoint(0,0);
        var frame=LocalMetricFrame.certifiedEquirectangular(origin,new GeographicPoint(-.01,-.01),new GeographicPoint(.01,.01));
        var field=new ScalarEvidenceField(w,h,values,valid,new EvidenceFieldLineage(EvidenceFieldLineage.AcquisitionKind.SYNTHETIC,
            EvidenceFieldLineage.DerivationKind.DIRECT_INTENSITY,"native",EvidenceCorrelationGroup.SYNTHETIC_TRUTH,false));
        double scale = transform.axisUnit() == RasterMetricTransform.AxisUnit.SOURCE_PIXEL
            ? transform.rasterPixelsPerSourcePixel() : 1;
        double pitch = Math.hypot(transform.xAxisEastMetersPerSourcePixel(), transform.xAxisNorthMetersPerSourcePixel()) / scale;
        EvidenceResolution effective=resolution==null?EvidenceResolution.nativeSource(pitch,pitch):resolution;
        return new EvidenceSnapshot("native-connector-test",frame,transform,effective,decision,
            MetricRegion.rectangle(transform.origin().xMeters(),transform.origin().yMeters(),transform.pixelCenterToMetric(w-1,h-1).xMeters(),transform.pixelCenterToMetric(w-1,h-1).yMeters()),Map.of("native",field),"declared-only");
    }
    private static DetachedProfileSamplingLocation at(EvidenceSnapshot evidence,RasterPoint raster,double chainage) {
        var metric=evidence.transform().pixelCenterToMetric(raster.x(),raster.y());
        return new DetachedProfileSamplingLocation(evidence.coordinateFrame().toGeographic(metric),metric,raster,
            evidence.coordinateFrame(),evidence.transform(),chainage);
    }
}
