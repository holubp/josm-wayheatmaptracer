package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.probabilistic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class V022WideCompactRetentionAssessmentTest {
    private static final WideCompactRetentionAssessment.Evidence E = WideCompactRetentionAssessment.Evidence.RUNNER_VALIDATED;
    private static WideCompactRetentionAssessment.RunnerChecks checks() { return new WideCompactRetentionAssessment.RunnerChecks(E,E,E,E,E,E,E,E,E); }
    private static WideCompactRetentionAssessment.Metric m(double v) { return new WideCompactRetentionAssessment.Metric(v,E); }
    private static WideCompactRetentionAssessment.GeometryOracle geometry(double p95,double max) { return new WideCompactRetentionAssessment.GeometryOracle(m(p95),m(max),E); }
    private static WideCompactRetentionAssessment.Input input(String scene,WideCompactRetentionAssessment.Strength strength,double distance,double bendCandidate,double bendReference,
            double refMax,double candidateMax) {
        return new WideCompactRetentionAssessment.Input(scene,strength,checks(),new WideCompactRetentionAssessment.Distances(m(distance),m(distance),m(distance),m(distance)),true,
                new WideCompactRetentionAssessment.Amplitudes(m(bendCandidate),m(bendReference),"x=40..60m","x=40..60m","y=0m","y=0m"),
                new WideCompactRetentionAssessment.AbsoluteAmplitude(m("faint-apex".equals(scene) ? bendCandidate : Math.max(2.7,bendCandidate)),m(3),m(2.7)),
                geometry(0,refMax),geometry(0,candidateMax));
    }
    @Test void suitableStrongAndWeakReferencesUseTheirSeparateRawAndFinalLimits() {
        assertEquals(WideCompactRetentionAssessment.Verdict.RETENTION_PASS,WideCompactRetentionAssessment.assess(input("strong",WideCompactRetentionAssessment.Strength.STRONG,.25*WideCompactRetentionAssessment.P,1,1,1,1)).verdict());
        assertEquals(WideCompactRetentionAssessment.Verdict.RETENTION_PASS,WideCompactRetentionAssessment.assess(input("weak",WideCompactRetentionAssessment.Strength.WEAK,.5*WideCompactRetentionAssessment.P,1,1,1,1)).verdict());
    }
    @Test void displacementOutsideEitherStrengthLimitFails() {
        assertEquals(WideCompactRetentionAssessment.Status.FAILED,WideCompactRetentionAssessment.assess(input("strong",WideCompactRetentionAssessment.Strength.STRONG,.251*WideCompactRetentionAssessment.P,1,1,1,1)).distanceStatus());
        assertEquals(WideCompactRetentionAssessment.Status.FAILED,WideCompactRetentionAssessment.assess(input("weak",WideCompactRetentionAssessment.Strength.WEAK,.501*WideCompactRetentionAssessment.P,1,1,1,1)).distanceStatus());
    }
    @Test void unchangedFaintAmplitudeKeepsRetentionAndAbsoluteDebtSeparate() {
        var r=WideCompactRetentionAssessment.assess(input("faint-apex",WideCompactRetentionAssessment.Strength.WEAK,0,1.923077,1.923077,1,1));
        assertEquals(WideCompactRetentionAssessment.Status.PASS,r.amplitudeRetentionStatus()); assertEquals(1.0,r.amplitudeRetentionRatio());
        assertEquals(WideCompactRetentionAssessment.Status.FAILED,r.absoluteQualityDebt()); assertEquals(WideCompactRetentionAssessment.Verdict.RETENTION_PASS_WITH_BASELINE_DEBT,r.verdict());
    }
    @Test void retentionBelowNinetyPercentFails() {
        assertEquals(WideCompactRetentionAssessment.Status.FAILED,WideCompactRetentionAssessment.assess(input("bend",WideCompactRetentionAssessment.Strength.STRONG,0,.899,1,1,1)).amplitudeRetentionStatus());
    }
    @Test void missingZeroReferenceOrNonfiniteAmplitudeCannotMakeAUsableRatio() {
        var base=input("bend",WideCompactRetentionAssessment.Strength.STRONG,0,1,1,1,1);
        assertEquals(WideCompactRetentionAssessment.Status.UNAVAILABLE,WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(base.sceneId(),base.strength(),base.runnerChecks(),base.distances(),true,
                new WideCompactRetentionAssessment.Amplitudes(null,m(1),"i","i","b","b"),base.absoluteAmplitude(),base.referenceGeometry(),base.candidateGeometry())).amplitudeRetentionStatus());
        assertEquals(WideCompactRetentionAssessment.Status.UNAVAILABLE,WideCompactRetentionAssessment.assess(input("bend",WideCompactRetentionAssessment.Strength.STRONG,0,1,0,1,1)).amplitudeRetentionStatus());
        assertEquals(WideCompactRetentionAssessment.Status.UNAVAILABLE,WideCompactRetentionAssessment.assess(input("bend",WideCompactRetentionAssessment.Strength.STRONG,0,Double.NaN,1,1,1)).amplitudeRetentionStatus());
    }
    @Test void diffuseUnsuitableReferenceRetainsFailedDistanceAndCannotExcuseCandidateFailure() {
        var r=WideCompactRetentionAssessment.assess(input("diffuse-three-strand",WideCompactRetentionAssessment.Strength.WEAK,2.307692,1,1,1.926272,1.518346));
        assertEquals(WideCompactRetentionAssessment.Status.FAILED,r.distanceStatus());
        assertEquals(WideCompactRetentionAssessment.ReferenceDisposition.UNSUITABLE_REFERENCE,r.referenceDisposition());
        assertEquals(WideCompactRetentionAssessment.Verdict.ASSESSED_WITH_UNSUITABLE_REFERENCE,r.verdict());
        var unsafe=input("diffuse-three-strand",WideCompactRetentionAssessment.Strength.WEAK,2.307692,1,1,1.926272,1.518346);
        var badChecks=new WideCompactRetentionAssessment.RunnerChecks(E,E,E,E,E,E,E,E, WideCompactRetentionAssessment.Evidence.FAILED);
        var blocked=WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(unsafe.sceneId(),unsafe.strength(),badChecks,unsafe.distances(),true,unsafe.amplitudes(),unsafe.absoluteAmplitude(),unsafe.referenceGeometry(),unsafe.candidateGeometry()));
        assertEquals(WideCompactRetentionAssessment.Verdict.FAILED,blocked.verdict()); assertFalse(blocked.releaseQualified());
        var badGeometry=WideCompactRetentionAssessment.assess(new WideCompactRetentionAssessment.Input(unsafe.sceneId(),unsafe.strength(),unsafe.runnerChecks(),unsafe.distances(),true,unsafe.amplitudes(),unsafe.absoluteAmplitude(),unsafe.referenceGeometry(),geometry(0,1.01*WideCompactRetentionAssessment.P)));
        assertEquals(WideCompactRetentionAssessment.Verdict.FAILED,badGeometry.verdict());
        assertEquals(WideCompactRetentionAssessment.ReferenceDisposition.SUITABILITY_FAILED,badGeometry.referenceDisposition());
    }
    @Test void unsuitableReferenceExceptionIsFrozenToDiffuseSceneAndAssessmentNeverQualifiesRelease() {
        var r=WideCompactRetentionAssessment.assess(input("another-scene",WideCompactRetentionAssessment.Strength.WEAK,2.307692,1,1,1.926272,1.518346));
        assertEquals(WideCompactRetentionAssessment.Verdict.FAILED,r.verdict()); assertFalse(r.releaseQualified());
    }
}
